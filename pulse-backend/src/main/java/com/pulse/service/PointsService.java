package com.pulse.service;

import com.pulse.entity.SysLedger;
import com.pulse.entity.User;

import java.math.BigDecimal;
import java.util.List;

/**
 * Points Service Interface
 */
public interface PointsService {

    /**
     * Get user points balance
     */
    BigDecimal getAvailablePoints(Long userId);

    /**
     * Freeze available points for bounty
     */
    void deductPoints(Long userId, BigDecimal amount, Long relatedId, String description);

    /**
     * Add points (reward)
     */
    void addPoints(Long userId, BigDecimal amount, Long relatedId, String description, String type);

    /**
     * Release frozen bounty points
     */
    void refundPoints(Long userId, BigDecimal amount, Long relatedId, String description);

    /**
     * Get ledger records
     */
    List<SysLedger> getLedgerRecords(Long userId, int limit);
}
