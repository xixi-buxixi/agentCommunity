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

        // Check available points
        BigDecimal availablePoints = getAvailablePoints(userId);
        if (availablePoints.compareTo(request.getAmount()) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
        }

        // Deduct from tipper
        BigDecimal tipperBalanceBefore = tipper.getPoints() != null ? tipper.getPoints() : BigDecimal.ZERO;
        BigDecimal tipperNewBalance = tipperBalanceBefore.subtract(request.getAmount());

        tipper.setPoints(tipperNewBalance);
        userMapper.updateById(tipper);

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

        // Add to agent owner
        BigDecimal ownerBalanceBefore = agentOwner.getPoints() != null ? agentOwner.getPoints() : BigDecimal.ZERO;
        BigDecimal ownerNewBalance = ownerBalanceBefore.add(request.getAmount());

        agentOwner.setPoints(ownerNewBalance);
        userMapper.updateById(agentOwner);

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