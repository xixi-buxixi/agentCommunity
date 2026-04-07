package com.pulse.service.impl;

import com.pulse.entity.SysLedger;
import com.pulse.entity.User;
import com.pulse.enums.LedgerType;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.SysLedgerMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.PointsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Points Service Implementation
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsServiceImpl implements PointsService {

    private final UserMapper userMapper;
    private final SysLedgerMapper sysLedgerMapper;

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
    public void deductPoints(Long userId, BigDecimal amount, Long relatedId, String description) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // Initialize points if null
        if (user.getPoints() == null) {
            user.setPoints(new BigDecimal("100.00"));
            user.setPendingBounty(BigDecimal.ZERO);
        }

        BigDecimal balanceBefore = user.getPoints();
        BigDecimal pendingBounty = user.getPendingBounty() != null ? user.getPendingBounty() : BigDecimal.ZERO;
        BigDecimal available = balanceBefore.subtract(pendingBounty);

        if (available.compareTo(amount) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
        }

        // Deduct and freeze
        BigDecimal newPoints = balanceBefore.subtract(amount);
        BigDecimal newPending = pendingBounty.add(amount);

        user.setPoints(newPoints);
        user.setPendingBounty(newPending);
        userMapper.updateById(user);

        // Create ledger entry
        SysLedger ledger = new SysLedger();
        ledger.setUserId(userId);
        ledger.setAmount(amount.negate());
        ledger.setType(LedgerType.BOUNTY_PAY.getCode());
        ledger.setRelatedId(relatedId);
        ledger.setRelatedType("BOUNTY");
        ledger.setDescription(description);
        ledger.setBalanceBefore(balanceBefore);
        ledger.setBalanceAfter(newPoints);
        ledger.setCreatedAt(LocalDateTime.now());

        sysLedgerMapper.insert(ledger);

        log.info("Points deducted: userId={}, amount={}, newBalance={}", userId, amount, newPoints);
    }

    @Override
    @Transactional
    public void addPoints(Long userId, BigDecimal amount, Long relatedId, String description, String type) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // Initialize points if null
        if (user.getPoints() == null) {
            user.setPoints(new BigDecimal("100.00"));
            user.setPendingBounty(BigDecimal.ZERO);
        }

        BigDecimal balanceBefore = user.getPoints();
        BigDecimal newPoints = balanceBefore.add(amount);

        user.setPoints(newPoints);
        userMapper.updateById(user);

        // Create ledger entry
        SysLedger ledger = new SysLedger();
        ledger.setUserId(userId);
        ledger.setAmount(amount);
        ledger.setType(type);
        ledger.setRelatedId(relatedId);
        ledger.setRelatedType("BOUNTY");
        ledger.setDescription(description);
        ledger.setBalanceBefore(balanceBefore);
        ledger.setBalanceAfter(newPoints);
        ledger.setCreatedAt(LocalDateTime.now());

        sysLedgerMapper.insert(ledger);

        log.info("Points added: userId={}, amount={}, newBalance={}", userId, amount, newPoints);
    }

    @Override
    @Transactional
    public void refundPoints(Long userId, BigDecimal amount, Long relatedId, String description) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        // Initialize points if null
        if (user.getPoints() == null) {
            user.setPoints(new BigDecimal("100.00"));
            user.setPendingBounty(BigDecimal.ZERO);
        }

        BigDecimal balanceBefore = user.getPoints();
        BigDecimal pendingBounty = user.getPendingBounty() != null ? user.getPendingBounty() : BigDecimal.ZERO;

        // Refund and release frozen points
        BigDecimal newPoints = balanceBefore.add(amount);
        BigDecimal newPending = pendingBounty.subtract(amount);
        if (newPending.compareTo(BigDecimal.ZERO) < 0) {
            newPending = BigDecimal.ZERO;
        }

        user.setPoints(newPoints);
        user.setPendingBounty(newPending);
        userMapper.updateById(user);

        // Create ledger entry
        SysLedger ledger = new SysLedger();
        ledger.setUserId(userId);
        ledger.setAmount(amount);
        ledger.setType(LedgerType.REFUND.getCode());
        ledger.setRelatedId(relatedId);
        ledger.setRelatedType("BOUNTY");
        ledger.setDescription(description);
        ledger.setBalanceBefore(balanceBefore);
        ledger.setBalanceAfter(newPoints);
        ledger.setCreatedAt(LocalDateTime.now());

        sysLedgerMapper.insert(ledger);

        log.info("Points refunded: userId={}, amount={}, newBalance={}", userId, amount, newPoints);
    }

    @Override
    public List<SysLedger> getLedgerRecords(Long userId, int limit) {
        return sysLedgerMapper.findRecentByUserId(userId, limit);
    }
}