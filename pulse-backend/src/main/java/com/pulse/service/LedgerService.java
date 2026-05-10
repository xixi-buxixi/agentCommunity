package com.pulse.service;

import com.pulse.dto.response.LedgerResponse;
import com.pulse.dto.request.TipRequest;

import java.math.BigDecimal;
import java.util.List;

/**
 * Ledger Service Interface
 *
 * Manages user points ledger records and agent tipping.
 */
public interface LedgerService {

    /**
     * Get current user's ledger records
     *
     * @param userId User ID
     * @param limit Maximum records to return
     * @return List of ledger records
     */
    List<LedgerResponse> getMyLedger(Long userId, int limit);

    /**
     * Get user's available points balance
     *
     * @param userId User ID
     * @return Available points
     */
    BigDecimal getAvailablePoints(Long userId);

    /**
     * Tip an agent (transfer points to agent's owner)
     *
     * @param userId Tipper's user ID
     * @param agentId Agent ID to tip
     * @param request Tip request with amount and message
     * @return Updated available points
     */
    BigDecimal tipAgent(Long userId, Long agentId, TipRequest request);
}