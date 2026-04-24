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
        // Get current balance for ledger record (before atomic update)
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        BigDecimal totalBefore = user.getPoints() != null ? user.getPoints() : BigDecimal.ZERO;
        BigDecimal pendingBefore = user.getPendingBounty() != null ? user.getPendingBounty() : BigDecimal.ZERO;
        BigDecimal availableBefore = totalBefore.subtract(pendingBefore);

        // Initialize points if null (first time user)
        if (user.getPoints() == null) {
            user.setPoints(new BigDecimal("100.00"));
            user.setPendingBounty(BigDecimal.ZERO);
            userMapper.updateById(user);
            totalBefore = user.getPoints();
            pendingBefore = BigDecimal.ZERO;
            availableBefore = totalBefore;
        }

        // Atomic freeze - concurrency safe
        int rowsAffected = userMapper.deductAndFreezePointsAtomic(userId, amount);
        if (rowsAffected == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
        }

        BigDecimal availableAfter = availableBefore.subtract(amount);

        // Create ledger entry
        SysLedger ledger = new SysLedger();
        ledger.setUserId(userId);
        ledger.setAmount(amount.negate());
        ledger.setType(LedgerType.BOUNTY_PAY.getCode());
        ledger.setRelatedId(relatedId);
        ledger.setRelatedType("BOUNTY");
        ledger.setDescription(description);
        ledger.setBalanceBefore(availableBefore);
        ledger.setBalanceAfter(availableAfter);
        ledger.setCreatedAt(LocalDateTime.now());

        sysLedgerMapper.insert(ledger);

        log.info("Points frozen atomically: userId={}, amount={}, availableAfter={}", userId, amount, availableAfter);
    }

    @Override
    @Transactional
    public void addPoints(Long userId, BigDecimal amount, Long relatedId, String description, String type) {
        // Get current balance for ledger record
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        BigDecimal totalBefore = user.getPoints() != null ? user.getPoints() : BigDecimal.ZERO;
        BigDecimal pendingBefore = user.getPendingBounty() != null ? user.getPendingBounty() : BigDecimal.ZERO;
        BigDecimal availableBefore = totalBefore.subtract(pendingBefore);

        // Initialize points if null (first time user)
        if (user.getPoints() == null) {
            user.setPoints(new BigDecimal("100.00"));
            user.setPendingBounty(BigDecimal.ZERO);
            userMapper.updateById(user);
            totalBefore = user.getPoints();
            pendingBefore = BigDecimal.ZERO;
            availableBefore = totalBefore;
        }

        // Atomic addition - concurrency safe
        userMapper.addPointsAtomic(userId, amount);

        BigDecimal availableAfter = availableBefore.add(amount);

        // Create ledger entry
        SysLedger ledger = new SysLedger();
        ledger.setUserId(userId);
        ledger.setAmount(amount);
        ledger.setType(type);
        ledger.setRelatedId(relatedId);
        ledger.setRelatedType("BOUNTY");
        ledger.setDescription(description);
        ledger.setBalanceBefore(availableBefore);
        ledger.setBalanceAfter(availableAfter);
        ledger.setCreatedAt(LocalDateTime.now());

        sysLedgerMapper.insert(ledger);

        log.info("Points added atomically: userId={}, amount={}, availableAfter={}", userId, amount, availableAfter);
    }

    @Override
    @Transactional
    public void refundPoints(Long userId, BigDecimal amount, Long relatedId, String description) {
        // Get current balance for ledger record
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }

        BigDecimal totalBefore = user.getPoints() != null ? user.getPoints() : BigDecimal.ZERO;
        BigDecimal pendingBefore = user.getPendingBounty() != null ? user.getPendingBounty() : BigDecimal.ZERO;
        BigDecimal availableBefore = totalBefore.subtract(pendingBefore);

        // Initialize points if null (first time user)
        if (user.getPoints() == null) {
            user.setPoints(new BigDecimal("100.00"));
            user.setPendingBounty(BigDecimal.ZERO);
            userMapper.updateById(user);
            totalBefore = user.getPoints();
            pendingBefore = BigDecimal.ZERO;
            availableBefore = totalBefore;
        }

        // Atomic release - concurrency safe
        int rowsAffected = userMapper.refundPointsAtomic(userId, amount);
        if (rowsAffected == 0) {
            log.warn("Release failed: userId={}, amount={} - insufficient pending bounty", userId, amount);
            // Still proceed with ledger entry but don't change balance
            amount = BigDecimal.ZERO;
        }

        BigDecimal availableAfter = availableBefore.add(amount);

        // Create ledger entry
        SysLedger ledger = new SysLedger();
        ledger.setUserId(userId);
        ledger.setAmount(amount);
        ledger.setType(LedgerType.BOUNTY_RELEASE.getCode());
        ledger.setRelatedId(relatedId);
        ledger.setRelatedType("BOUNTY");
        ledger.setDescription(description);
        ledger.setBalanceBefore(availableBefore);
        ledger.setBalanceAfter(availableAfter);
        ledger.setCreatedAt(LocalDateTime.now());

        sysLedgerMapper.insert(ledger);

        log.info("Frozen points released atomically: userId={}, amount={}, availableAfter={}", userId, amount, availableAfter);
    }

    @Override
    public List<SysLedger> getLedgerRecords(Long userId, int limit) {
        return sysLedgerMapper.findRecentByUserId(userId, limit);
    }
}
