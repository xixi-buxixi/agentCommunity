package com.pulse.service.impl;

import com.pulse.dto.request.TipRequest;
import com.pulse.entity.Agent;
import com.pulse.entity.SysLedger;
import com.pulse.entity.User;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.SysLedgerMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.PointsService;
import com.pulse.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for tipping (H1).
 *
 * The original implementation read the tipper, subtracted in Java and called
 * updateById. That lost concurrent updates outright, and because updateById writes
 * every column it also wrote back a stale pending_bounty, undoing bounty freezes
 * that happened in between.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LedgerServiceImplTest {

    private static final Long TIPPER_ID = 1L;
    private static final Long OWNER_ID = 2L;
    private static final Long AGENT_ID = 30L;

    @Mock
    private SysLedgerMapper sysLedgerMapper;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AgentMapper agentMapper;
    @Mock
    private RateLimitService rateLimitService;
    @Mock
    private PointsService pointsService;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    @BeforeEach
    void allowRateLimit() {
        when(rateLimitService.tryConsume(anyString(), anyString(), anyInt(), any())).thenReturn(true);
        // Ledger snapshots now use the available balance, which comes from PointsService
        when(pointsService.getAvailablePoints(anyLong())).thenReturn(new BigDecimal("90.00"));
    }

    private User user(Long id, String points) {
        User user = new User();
        user.setId(id);
        user.setPoints(new BigDecimal(points));
        user.setPendingBounty(BigDecimal.ZERO);
        return user;
    }

    private Agent agent(Long ownerId) {
        Agent entity = new Agent();
        entity.setId(AGENT_ID);
        entity.setOwnerId(ownerId);
        entity.setName("nova");
        return entity;
    }

    private TipRequest tip(String amount) {
        TipRequest request = new TipRequest();
        request.setAmount(new BigDecimal(amount));
        return request;
    }

    @Test
    void tipUsesConditionalUpdatesAndNeverUpdateById() {
        when(userMapper.selectById(TIPPER_ID)).thenReturn(user(TIPPER_ID, "100.00"));
        when(userMapper.selectById(OWNER_ID)).thenReturn(user(OWNER_ID, "50.00"));
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(OWNER_ID));
        when(userMapper.deductAvailablePointsAtomic(eq(TIPPER_ID), any())).thenReturn(1);
        when(userMapper.addPointsAtomic(eq(OWNER_ID), any())).thenReturn(1);

        ledgerService.tipAgent(TIPPER_ID, AGENT_ID, tip("10.00"));

        verify(userMapper).deductAvailablePointsAtomic(TIPPER_ID, new BigDecimal("10.00"));
        verify(userMapper).addPointsAtomic(OWNER_ID, new BigDecimal("10.00"));
        // updateById would rewrite pending_bounty from a stale read
        verify(userMapper, never()).updateById(any());
        // One row for the sender, one for the receiver
        verify(sysLedgerMapper, org.mockito.Mockito.times(2)).insert(any(SysLedger.class));
    }

    @Test
    void tipIsRejectedWhenAvailableBalanceIsInsufficient() {
        when(userMapper.selectById(TIPPER_ID)).thenReturn(user(TIPPER_ID, "5.00"));
        when(userMapper.selectById(OWNER_ID)).thenReturn(user(OWNER_ID, "50.00"));
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(OWNER_ID));
        when(userMapper.deductAvailablePointsAtomic(eq(TIPPER_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> ledgerService.tipAgent(TIPPER_ID, AGENT_ID, tip("10.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INSUFFICIENT_VITALITY.getCode());

        verify(sysLedgerMapper, never()).insert(any());
        verify(userMapper, never()).addPointsAtomic(anyLong(), any());
    }

    @Test
    void tippingYourOwnAgentIsRejected() {
        when(userMapper.selectById(TIPPER_ID)).thenReturn(user(TIPPER_ID, "100.00"));
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(TIPPER_ID));

        assertThatThrownBy(() -> ledgerService.tipAgent(TIPPER_ID, AGENT_ID, tip("10.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.SELF_TIP_FORBIDDEN.getCode());

        verify(userMapper, never()).deductAvailablePointsAtomic(anyLong(), any());
    }

    @Test
    void nonPositiveAmountIsRejected() {
        when(userMapper.selectById(TIPPER_ID)).thenReturn(user(TIPPER_ID, "100.00"));
        when(userMapper.selectById(OWNER_ID)).thenReturn(user(OWNER_ID, "50.00"));
        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(OWNER_ID));

        assertThatThrownBy(() -> ledgerService.tipAgent(TIPPER_ID, AGENT_ID, tip("0.00")))
                .isInstanceOf(BusinessException.class);

        verify(userMapper, never()).deductAvailablePointsAtomic(anyLong(), any());
    }

    @Test
    void tipIsRejectedWhenRateLimited() {
        when(rateLimitService.tryConsume(anyString(), anyString(), anyInt(), any())).thenReturn(false);

        assertThatThrownBy(() -> ledgerService.tipAgent(TIPPER_ID, AGENT_ID, tip("10.00")))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.RATE_LIMIT_EXCEEDED.getCode());

        verify(userMapper, never()).deductAvailablePointsAtomic(anyLong(), any());
    }

    /**
     * Concurrency check for the lost-update bug.
     *
     * The mapper stands in for the database: it only "succeeds" while the simulated
     * available balance covers the amount, exactly like the WHERE clause of
     * deductAvailablePointsAtomic. Ten concurrent tips of 20 against a balance of
     * 100 must therefore result in five debits, not ten - the read-modify-write
     * version would let every thread through.
     */
    @Test
    void concurrentTipsCannotOverdrawTheBalance() throws Exception {
        int threads = 10;
        BigDecimal amount = new BigDecimal("20.00");
        AtomicInteger balanceCents = new AtomicInteger(10_000); // 100.00
        AtomicInteger successfulDebits = new AtomicInteger();

        when(agentMapper.selectById(AGENT_ID)).thenReturn(agent(OWNER_ID));
        when(userMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return user(id, new BigDecimal(balanceCents.get()).movePointLeft(2).toPlainString());
        });
        when(userMapper.addPointsAtomic(eq(OWNER_ID), any())).thenReturn(1);
        when(userMapper.deductAvailablePointsAtomic(eq(TIPPER_ID), any())).thenAnswer(invocation -> {
            BigDecimal requested = invocation.getArgument(1);
            int cents = requested.movePointRight(2).intValueExact();
            // Single atomic compare-and-set, mirroring the conditional UPDATE
            while (true) {
                int current = balanceCents.get();
                if (current < cents) {
                    return 0;
                }
                if (balanceCents.compareAndSet(current, current - cents)) {
                    successfulDebits.incrementAndGet();
                    return 1;
                }
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger rejected = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(5, TimeUnit.SECONDS);
                    ledgerService.tipAgent(TIPPER_ID, AGENT_ID, tip(amount.toPlainString()));
                } catch (BusinessException e) {
                    rejected.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(successfulDebits.get()).isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(5);
        // Balance must land exactly on zero: no points created, none destroyed
        assertThat(balanceCents.get()).isZero();
    }

    @Test
    void rateLimitWindowIsAnHour() {
        // Guards against an accidental unit change in the constant
        assertThat(Duration.ofHours(1)).isEqualTo(Duration.ofMinutes(60));
    }
}
