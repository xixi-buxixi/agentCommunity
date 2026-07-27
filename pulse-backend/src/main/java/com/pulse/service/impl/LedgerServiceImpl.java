package com.pulse.service.impl;

import com.pulse.dto.request.TipRequest;
import com.pulse.dto.response.LedgerResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.SysLedger;
import com.pulse.entity.User;
import com.pulse.enums.LedgerType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.SysLedgerMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Ledger Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerServiceImpl implements LedgerService {

    private final SysLedgerMapper sysLedgerMapper;
    private final UserMapper userMapper;
    private final AgentMapper agentMapper;

    @Override
    public List<LedgerResponse> getMyLedger(Long userId, int limit) {
        List<SysLedger> records = sysLedgerMapper.findRecentByUserId(userId, Math.min(limit, 50));

        return records.stream()
                .map(this::buildLedgerResponse)
                .collect(Collectors.toList());
    }

    @Override
    public BigDecimal getAvailablePoints(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return BigDecimal.ZERO;
        }

        // Initialize points if null
        if (user.getPoints() == null) {
            user.setPoints(new BigDecimal("100.00"));
            user.setPendingBounty(BigDecimal.ZERO);
            userMapper.updateById(user);
        }

        BigDecimal pending = user.getPendingBounty() != null ? user.getPendingBounty() : BigDecimal.ZERO;
        return user.getPoints().subtract(pending);
    }

    @Override
    @Transactional
    public BigDecimal tipAgent(Long userId, Long agentId, TipRequest request) {
        // Validate tipper exists
        User tipper = userMapper.selectById(userId);
        if (tipper == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // Validate agent exists
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
        }

        // Get agent's owner
        User agentOwner = userMapper.selectById(agent.getOwnerId());
        if (agentOwner == null) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        // Tipping your own agent would only inflate ledger volume
        if (agent.getOwnerId().equals(userId)) {
            throw new BusinessException(ErrorCode.SELF_TIP_FORBIDDEN);
        }

        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }

        // Lazily initialize the points column for accounts created before it existed
        getAvailablePoints(userId);

        // Deduct from tipper with a single conditional UPDATE. A read-modify-write
        // here would both lose concurrent updates and overwrite pending_bounty with
        // a stale value, breaking the bounty freeze.
        int deducted = userMapper.deductAvailablePointsAtomic(userId, request.getAmount());
        if (deducted == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
        }

        // Read the real post-update balance so the ledger trail is continuous
        // instead of derived from a pre-update snapshot.
        BigDecimal tipperNewBalance = currentPoints(userId);
        BigDecimal tipperBalanceBefore = tipperNewBalance.add(request.getAmount());

        // Create tipper ledger entry
        SysLedger tipperLedger = new SysLedger();
        tipperLedger.setUserId(userId);
        tipperLedger.setAmount(request.getAmount().negate());
        tipperLedger.setType(LedgerType.TIP_SEND.getCode());
        tipperLedger.setRelatedId(agentId);
        tipperLedger.setRelatedType("AGENT");
        tipperLedger.setDescription("打赏 Agent [" + agent.getName() + "]" +
            (request.getMessage() != null ? " - " + request.getMessage() : ""));
        tipperLedger.setBalanceBefore(tipperBalanceBefore);
        tipperLedger.setBalanceAfter(tipperNewBalance);
        tipperLedger.setCreatedAt(LocalDateTime.now());
        sysLedgerMapper.insert(tipperLedger);

        // Add to agent owner (atomic, same reasoning as the deduction above)
        int credited = userMapper.addPointsAtomic(agent.getOwnerId(), request.getAmount());
        if (credited == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        BigDecimal ownerNewBalance = currentPoints(agent.getOwnerId());
        BigDecimal ownerBalanceBefore = ownerNewBalance.subtract(request.getAmount());

        // Create owner ledger entry
        SysLedger ownerLedger = new SysLedger();
        ownerLedger.setUserId(agent.getOwnerId());
        ownerLedger.setAmount(request.getAmount());
        ownerLedger.setType(LedgerType.TIP_RECV.getCode());
        ownerLedger.setRelatedId(agentId);
        ownerLedger.setRelatedType("AGENT");
        ownerLedger.setDescription("收到打赏 - Agent [" + agent.getName() + "]");
        ownerLedger.setBalanceBefore(ownerBalanceBefore);
        ownerLedger.setBalanceAfter(ownerNewBalance);
        ownerLedger.setCreatedAt(LocalDateTime.now());
        sysLedgerMapper.insert(ownerLedger);

        log.info("Agent tipped: tipperId={}, agentId={}, ownerId={}, amount={}",
            userId, agentId, agent.getOwnerId(), request.getAmount());

        // Return tipper's remaining available points
        return getAvailablePoints(userId);
    }

    /**
     * Re-read the persisted total points of a user, for ledger balance snapshots.
     */
    private BigDecimal currentPoints(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || user.getPoints() == null) {
            return BigDecimal.ZERO;
        }
        return user.getPoints();
    }

    /**
     * Build ledger response
     */
    private LedgerResponse buildLedgerResponse(SysLedger ledger) {
        String typeText = LedgerType.fromCode(ledger.getType()).getText();

        return LedgerResponse.builder()
                .id(ledger.getId())
                .amount(ledger.getAmount())
                .type(ledger.getType())
                .typeText(typeText)
                .relatedId(ledger.getRelatedId())
                .relatedType(ledger.getRelatedType())
                .description(ledger.getDescription())
                .balanceBefore(ledger.getBalanceBefore())
                .balanceAfter(ledger.getBalanceAfter())
                .createdAt(ledger.getCreatedAt())
                .build();
    }
}