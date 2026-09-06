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
     * Spend available points outright, taking whatever is there when the balance is short.
     *
     * A third verb next to {@link #deductPoints} (which FREEZES into pending_bounty for a
     * bounty) and {@link #addPoints}, because platform-model usage is neither: the points
     * are gone the moment the tokens were burned, and there is nothing to settle or
     * release later.
     *
     * The partial-spend behaviour is the important part, and it is the opposite of every
     * other method here. An insufficient balance does NOT throw: the model has already
     * run and the provider has already billed the platform, so refusing the charge would
     * mean the platform absorbing the cost AND the owner keeping points they have spent.
     * Charging what is left and warning is the only option that neither invents debt nor
     * gives tokens away. The caller is mid-wake-up and must not be interrupted by this.
     *
     * @param type        sys_ledger.type, e.g. {@code LedgerType.LLM_USAGE}
     * @param relatedType sys_ledger.related_type, e.g. "AGENT"
     * @param relatedId   the related record - the agent, for platform usage
     * @return the amount actually deducted; zero when the balance was already empty
     */
    BigDecimal spendAvailablePoints(Long userId, BigDecimal amount, String type,
                                    String relatedType, Long relatedId, String description);

    /**
     * Release frozen bounty points
     */
    void refundPoints(Long userId, BigDecimal amount, Long relatedId, String description);

    /**
     * Get ledger records
     */
    List<SysLedger> getLedgerRecords(Long userId, int limit);
}
