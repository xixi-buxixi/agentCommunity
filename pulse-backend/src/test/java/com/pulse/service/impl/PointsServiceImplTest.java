package com.pulse.service.impl;

import com.pulse.entity.SysLedger;
import com.pulse.entity.User;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.SysLedgerMapper;
import com.pulse.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the money path (M20 / M18).
 *
 * The invariants under test:
 * - a failed atomic update never produces a ledger row,
 * - the ledger before/after pair is derived from the balance that was actually
 *   persisted, not from a snapshot read before the update,
 * - ignored mapper return values cannot silently record money that never moved.
 */
@ExtendWith(MockitoExtension.class)
class PointsServiceImplTest {

    private static final Long USER_ID = 42L;
    private static final Long TASK_ID = 7L;

    @Mock
    private UserMapper userMapper;
    @Mock
    private SysLedgerMapper sysLedgerMapper;

    @InjectMocks
    private PointsServiceImpl pointsService;

    private User user(String points, String pending) {
        User user = new User();
        user.setId(USER_ID);
        user.setPoints(new BigDecimal(points));
        user.setPendingBounty(new BigDecimal(pending));
        return user;
    }

    @Test
    void availablePointsSubtractFrozenAmount() {
        when(userMapper.selectById(USER_ID)).thenReturn(user("500.00", "120.00"));

        assertThat(pointsService.getAvailablePoints(USER_ID)).isEqualByComparingTo("380.00");
    }

    @Test
    void availablePointsOfUnknownUserIsZero() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertThat(pointsService.getAvailablePoints(USER_ID)).isEqualByComparingTo("0");
    }

    @Test
    void freezeWritesLedgerDerivedFromThePersistedBalance() {
        BigDecimal amount = new BigDecimal("30.00");
        // Pre-check read, then the post-update read: 500 total with 30 now frozen
        when(userMapper.selectById(USER_ID))
                .thenReturn(user("500.00", "0.00"))
                .thenReturn(user("500.00", "30.00"));
        when(userMapper.deductAndFreezePointsAtomic(USER_ID, amount)).thenReturn(1);

        pointsService.deductPoints(USER_ID, amount, TASK_ID, "freeze for bounty");

        SysLedger ledger = captureLedger();
        assertThat(ledger.getAmount()).isEqualByComparingTo("-30.00");
        assertThat(ledger.getBalanceAfter()).isEqualByComparingTo("470.00");
        assertThat(ledger.getBalanceBefore()).isEqualByComparingTo("500.00");
        // The pair must be self-consistent with the recorded amount
        assertThat(ledger.getBalanceBefore().add(ledger.getAmount()))
                .isEqualByComparingTo(ledger.getBalanceAfter());
    }

    @Test
    void insufficientBalanceFreezesNothingAndWritesNoLedgerRow() {
        when(userMapper.selectById(USER_ID)).thenReturn(user("10.00", "0.00"));
        when(userMapper.deductAndFreezePointsAtomic(eq(USER_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> pointsService.deductPoints(
                USER_ID, new BigDecimal("30.00"), TASK_ID, "freeze"))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.INSUFFICIENT_VITALITY.getCode());

        verify(sysLedgerMapper, never()).insert(any());
    }

    @Test
    void creditWritesLedgerDerivedFromThePersistedBalance() {
        BigDecimal amount = new BigDecimal("25.00");
        when(userMapper.selectById(USER_ID))
                .thenReturn(user("100.00", "0.00"))
                .thenReturn(user("125.00", "0.00"));
        when(userMapper.addPointsAtomic(USER_ID, amount)).thenReturn(1);

        pointsService.addPoints(USER_ID, amount, TASK_ID, "bounty reward", "BOUNTY_RECV");

        SysLedger ledger = captureLedger();
        assertThat(ledger.getBalanceBefore()).isEqualByComparingTo("100.00");
        assertThat(ledger.getBalanceAfter()).isEqualByComparingTo("125.00");
        assertThat(ledger.getType()).isEqualTo("BOUNTY_RECV");
    }

    /**
     * addPointsAtomic's return value was ignored, so a credit that changed no row
     * still produced a ledger entry claiming money had moved.
     */
    @Test
    void creditThatAffectsNoRowIsRejected() {
        when(userMapper.selectById(USER_ID)).thenReturn(user("100.00", "0.00"));
        when(userMapper.addPointsAtomic(eq(USER_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> pointsService.addPoints(
                USER_ID, new BigDecimal("25.00"), TASK_ID, "reward", "BOUNTY_RECV"))
                .isInstanceOf(BusinessException.class);

        verify(sysLedgerMapper, never()).insert(any());
    }

    @Test
    void releaseWritesLedgerDerivedFromThePersistedBalance() {
        BigDecimal amount = new BigDecimal("30.00");
        when(userMapper.selectById(USER_ID))
                .thenReturn(user("500.00", "30.00"))
                .thenReturn(user("500.00", "0.00"));
        when(userMapper.refundPointsAtomic(USER_ID, amount)).thenReturn(1);

        pointsService.refundPoints(USER_ID, amount, TASK_ID, "bounty expired");

        SysLedger ledger = captureLedger();
        assertThat(ledger.getAmount()).isEqualByComparingTo("30.00");
        assertThat(ledger.getBalanceBefore()).isEqualByComparingTo("470.00");
        assertThat(ledger.getBalanceAfter()).isEqualByComparingTo("500.00");
    }

    /**
     * A release that frees nothing means the freeze is already gone: recording a
     * zero-amount ledger row (the old behaviour) would hide a real inconsistency.
     */
    @Test
    void releaseThatFreesNothingFailsInsteadOfWritingAZeroRow() {
        when(userMapper.selectById(USER_ID)).thenReturn(user("500.00", "0.00"));
        when(userMapper.refundPointsAtomic(eq(USER_ID), any())).thenReturn(0);

        assertThatThrownBy(() -> pointsService.refundPoints(
                USER_ID, new BigDecimal("30.00"), TASK_ID, "expired"))
                .isInstanceOf(BusinessException.class);

        verify(sysLedgerMapper, never()).insert(any());
    }

    @Test
    void unknownUserCannotMoveMoney() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> pointsService.deductPoints(
                USER_ID, new BigDecimal("1.00"), TASK_ID, "x"))
                .isInstanceOf(BusinessException.class);

        verify(userMapper, never()).deductAndFreezePointsAtomic(any(), any());
        verify(sysLedgerMapper, never()).insert(any());
    }

    private SysLedger captureLedger() {
        ArgumentCaptor<SysLedger> captor = ArgumentCaptor.forClass(SysLedger.class);
        verify(sysLedgerMapper).insert(captor.capture());
        return captor.getValue();
    }
}
