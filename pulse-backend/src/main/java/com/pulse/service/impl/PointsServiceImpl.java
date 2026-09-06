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
 *
 * Every balance change here follows the same shape:
 * 1. one conditional UPDATE that either succeeds or affects zero rows,
 * 2. a re-read of the persisted balance inside the same transaction,
 * 3. one sys_ledger row whose before/after pair is derived from that real value.
 *
 * Step 2 is the point of M18: the ledger snapshot used to be computed from a
 * non-atomic read taken BEFORE the update, so under concurrency the recorded
 * balance trail did not join up - two interleaved operations could both report
 * the same balance_before, and neither matched the row that was actually written.
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
        return availableOf(user);
    }

    @Override
    @Transactional
    public void deductPoints(Long userId, BigDecimal amount, Long relatedId, String description) {
        requireUser(userId);

        // Atomic freeze - concurrency safe
        int rowsAffected = userMapper.deductAndFreezePointsAtomic(userId, amount);
        if (rowsAffected == 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_VITALITY);
        }

        // Freezing lowers the available balance by exactly `amount`
        BigDecimal availableAfter = getAvailablePoints(userId);
        writeLedger(userId, amount.negate(), LedgerType.BOUNTY_PAY.getCode(), relatedId, description,
                availableAfter.add(amount), availableAfter);

        log.info("Points frozen atomically: userId={}, amount={}, availableAfter={}",
                userId, amount, availableAfter);
    }

    @Override
    @Transactional
    public void addPoints(Long userId, BigDecimal amount, Long relatedId, String description, String type) {
        requireUser(userId);

        // Atomic addition - concurrency safe. The return value used to be ignored,
        // so a credit to a deleted or missing user silently wrote a ledger row for
        // money that never moved.
        int rowsAffected = userMapper.addPointsAtomic(userId, amount);
        if (rowsAffected == 0) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        }

        BigDecimal availableAfter = getAvailablePoints(userId);
        writeLedger(userId, amount, type, relatedId, description,
                availableAfter.subtract(amount), availableAfter);

        log.info("Points added atomically: userId={}, amount={}, availableAfter={}",
                userId, amount, availableAfter);
    }

    @Override
    @Transactional
    public BigDecimal spendAvailablePoints(Long userId, BigDecimal amount, String type,
                                           String relatedType, Long relatedId, String description) {
        requireUser(userId);

        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal available = getAvailablePoints(userId);
        if (available.signum() <= 0) {
            // Nothing to take. No ledger row either: a 0-value entry would claim a
            // movement that did not happen, which is the thing refundPoints was fixed to
            // stop doing.
            log.warn("Cannot charge {} to user {}: available balance is {}", amount, userId, available);
            return BigDecimal.ZERO;
        }

        BigDecimal wanted = amount.min(available);
        BigDecimal spent = trySpend(userId, wanted);
        if (spent.signum() <= 0) {
            // Lost a race with a concurrent spend between the read and the update. One
            // retry against the balance as it is now; the atomic predicate is what makes
            // this safe to attempt at all.
            BigDecimal remaining = getAvailablePoints(userId);
            if (remaining.signum() > 0) {
                spent = trySpend(userId, amount.min(remaining));
            }
        }
        if (spent.signum() <= 0) {
            log.warn("Could not charge {} to user {}; the balance was consumed concurrently",
                    amount, userId);
            return BigDecimal.ZERO;
        }

        BigDecimal availableAfter = getAvailablePoints(userId);
        writeLedger(userId, spent.negate(), type, relatedId, relatedType, description,
                availableAfter.add(spent), availableAfter);

        if (spent.compareTo(amount) < 0) {
            // The interesting case for an operator: the platform ate the difference.
            log.warn("Partial charge: user={} owed {} but only {} was available, charged to zero",
                    userId, amount, spent);
        } else {
            log.info("Points spent: userId={}, amount={}, type={}, availableAfter={}",
                    userId, spent, type, availableAfter);
        }
        return spent;
    }

    /**
     * One atomic attempt. Returns what was taken, or zero when the conditional UPDATE
     * matched no row.
     */
    private BigDecimal trySpend(Long userId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return userMapper.deductAvailablePointsAtomic(userId, amount) > 0 ? amount : BigDecimal.ZERO;
    }

    @Override
    @Transactional
    public void refundPoints(Long userId, BigDecimal amount, Long relatedId, String description) {
        requireUser(userId);

        // Atomic release - concurrency safe
        int rowsAffected = userMapper.refundPointsAtomic(userId, amount);
        if (rowsAffected == 0) {
            // Previously this logged a warning, set amount to zero and still wrote a
            // ledger row - papering over an inconsistency with a 0-value entry.
            // Failing the transaction keeps the bounty in its previous state so the
            // next sweep retries it, and makes the drift visible.
            log.error("Release failed: userId={}, amount={}, relatedId={} - insufficient pending_bounty",
                    userId, amount, relatedId);
            throw new BusinessException(ErrorCode.RESOURCE_CONFLICT);
        }

        // Releasing a freeze raises the available balance by exactly `amount`
        BigDecimal availableAfter = getAvailablePoints(userId);
        writeLedger(userId, amount, LedgerType.BOUNTY_RELEASE.getCode(), relatedId, description,
                availableAfter.subtract(amount), availableAfter);

        log.info("Frozen points released atomically: userId={}, amount={}, availableAfter={}",
                userId, amount, availableAfter);
    }

    @Override
    public List<SysLedger> getLedgerRecords(Long userId, int limit) {
        return sysLedgerMapper.findRecentByUserId(userId, limit);
    }

    /**
     * Available balance = total points minus the amount frozen for open bounties.
     */
    private static BigDecimal availableOf(User user) {
        BigDecimal points = user.getPoints() != null ? user.getPoints() : BigDecimal.ZERO;
        BigDecimal pending = user.getPendingBounty() != null ? user.getPendingBounty() : BigDecimal.ZERO;
        return points.subtract(pending);
    }

    /**
     * Verify the account exists.
     *
     * This used to double as lazy initialization of the points column - a read path
     * that wrote to the database. Both columns are NOT NULL DEFAULT in schema.sql,
     * so new accounts are initialized by the database itself.
     */
    private void requireUser(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void writeLedger(Long userId, BigDecimal amount, String type, Long relatedId,
                             String description, BigDecimal balanceBefore, BigDecimal balanceAfter) {
        // Every pre-existing caller of this method is a bounty movement, so the default
        // stays "BOUNTY" and their rows are written exactly as before.
        writeLedger(userId, amount, type, relatedId, "BOUNTY", description, balanceBefore, balanceAfter);
    }

    /**
     * Same, with an explicit related_type. Platform-model usage relates to an AGENT, and
     * filing it under "BOUNTY" would make the ledger unreadable the moment anyone tried
     * to answer "what did this agent cost me".
     */
    private void writeLedger(Long userId, BigDecimal amount, String type, Long relatedId,
                             String relatedType, String description,
                             BigDecimal balanceBefore, BigDecimal balanceAfter) {
        SysLedger ledger = new SysLedger();
        ledger.setUserId(userId);
        ledger.setAmount(amount);
        ledger.setType(type);
        ledger.setRelatedId(relatedId);
        ledger.setRelatedType(relatedType);
        ledger.setDescription(description);
        ledger.setBalanceBefore(balanceBefore);
        ledger.setBalanceAfter(balanceAfter);
        ledger.setCreatedAt(LocalDateTime.now());

        sysLedgerMapper.insert(ledger);
    }
}
