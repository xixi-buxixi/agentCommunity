package com.pulse.service.support;

import com.pulse.config.PlatformLlmProperties;
import com.pulse.entity.Agent;
import com.pulse.enums.LedgerType;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.service.PointsService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The spending rules for the platform-hosted model: who may be woken on it, and what one
 * wake-up costs its owner.
 *
 * Both halves live here rather than in the wake processor because they are the only part
 * of a wake-up that involves somebody else's money, and because BYOK agents must be able
 * to reach every other line of that method without passing through any of this.
 *
 * <p>The two halves are deliberately asymmetric.
 *
 * <p>{@link #checkReadiness} runs BEFORE the model call and refuses. It is allowed to
 * refuse, because nothing has been spent yet: the agent simply does not wake up this time.
 *
 * <p>{@link #charge} runs AFTER the model call and never refuses. By then the provider has
 * already billed the platform for tokens that were really burned, so there is nothing left
 * to prevent - only to record. An exception here would abort a wake-up whose work has
 * already been committed, and an insufficient balance is charged to zero rather than
 * refused, because the alternatives are inventing debt or giving the tokens away.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformUsageService {

    /**
     * Why a PLATFORM agent was not woken. Also the {@code action_result} suffix on the
     * IGNORE row the wake processor writes, so an owner reading the activity log sees the
     * actual reason rather than "nothing happened".
     */
    @Getter
    public enum SkipReason {

        /** The platform model is switched off, unconfigured, or the schema lacks the column. */
        PLATFORM_UNAVAILABLE("平台模型当前不可用"),

        /** The owner's available balance is below the configured floor. */
        OWNER_POINTS_INSUFFICIENT("所有者可用积分不足"),

        /** This agent has spent its own daily token allowance. */
        AGENT_DAILY_CAP("已达该 Agent 的平台模型每日用量上限"),

        /** Every PLATFORM agent together has spent the platform's daily allowance. */
        GLOBAL_DAILY_CAP("已达平台模型每日总用量上限");

        private final String text;

        SkipReason(String text) {
            this.text = text;
        }
    }

    private final PlatformLlmProperties properties;
    private final LlmCredentialResolver credentialResolver;
    private final AgentLogMapper agentLogMapper;
    private final PointsService pointsService;
    private final AgentPointsNotifier agentPointsNotifier;

    /**
     * Whether this agent may be woken on the platform model right now.
     *
     * Returns null for "go ahead", including for every BYOK agent - they are not subject
     * to any of this and must not pay for a single extra query.
     *
     * <p>The checks are ordered cheapest-first and short-circuit: configuration (no
     * query), then the owner's balance (one query), then the per-agent cap, then the
     * global cap (a join across every platform agent's log). A deployment with the
     * feature switched off therefore never touches the database here at all.
     *
     * <p>A cap query that fails is treated as "not at the cap". The alternative -
     * refusing on an unreadable counter - would silence every platform agent in the
     * community the moment one query plan went bad, which is a far worse failure than one
     * cycle of overspend against a cap that is a brake rather than an invoice.
     */
    public SkipReason checkReadiness(Agent agent) {
        if (agent == null || !credentialResolver.isPlatformAgent(agent)) {
            return null;
        }
        if (!credentialResolver.isPlatformAvailable()) {
            return SkipReason.PLATFORM_UNAVAILABLE;
        }

        BigDecimal floor = properties.effectiveMinPointsToWake();
        if (floor.signum() > 0) {
            BigDecimal available;
            try {
                available = pointsService.getAvailablePoints(agent.getOwnerId());
            } catch (Exception e) {
                // Same stance as the cap queries: an unreadable balance must not silence
                // the agent. The charge afterwards still cannot go below zero.
                log.warn("Could not read the owner's balance for agent {}: {}",
                        agent.getId(), e.getMessage());
                available = null;
            }
            if (available != null && available.compareTo(floor) < 0) {
                return SkipReason.OWNER_POINTS_INSUFFICIENT;
            }
        }

        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        long agentCap = properties.getDailyTokenCapPerAgent();
        if (agentCap > 0) {
            long usedByAgent = sumSafely(() -> agentLogMapper.sumTokensSince(agent.getId(), startOfToday),
                    "per-agent", agent.getId());
            if (usedByAgent >= agentCap) {
                return SkipReason.AGENT_DAILY_CAP;
            }
        }

        long globalCap = properties.getDailyTokenCapGlobal();
        if (globalCap > 0) {
            long usedGlobally = sumSafely(() -> agentLogMapper.sumPlatformTokensSince(startOfToday),
                    "global", agent.getId());
            if (usedGlobally >= globalCap) {
                return SkipReason.GLOBAL_DAILY_CAP;
            }
        }

        return null;
    }

    /**
     * Tell the owner their agent is paused for lack of points.
     *
     * Only for {@link SkipReason#OWNER_POINTS_INSUFFICIENT}: the other three are platform
     * conditions the owner can do nothing about, and notifying them would be telling
     * somebody about our capacity planning. At most one per agent per day - see
     * {@link AgentPointsNotifier}.
     */
    public void notifyIfActionable(Agent agent, SkipReason reason) {
        if (agent == null || reason != SkipReason.OWNER_POINTS_INSUFFICIENT) {
            return;
        }
        agentPointsNotifier.notifyPointsInsufficient(agent.getOwnerId(), agent.getId(), agent.getName());
    }

    /**
     * Charge one platform call to the agent's owner.
     *
     * Cost is {@code tokens / 1000 * points_per_1k}, rounded UP to two decimals. Rounding
     * up rather than half-even so that a stream of tiny calls can never round to nothing
     * repeatedly, and two decimals because that is the precision the points column and
     * every ledger row already carry.
     *
     * <p>Called for BYOK agents too, and returns immediately: the caller should not have
     * to know which mode it is in. Also returns immediately when the rate is zero, which
     * is how a deployment offers the platform model for free without a second switch.
     *
     * <p>Never throws. The tokens are already spent by the time this runs; a failure here
     * must cost the platform points, not the owner their wake-up.
     *
     * @param tokens the token figure this cycle was charged, the same one applied to the
     *               agent's own token_threshold - including the floor charged when the
     *               gateway reported no usage, because the upstream model may well have
     *               run and billed anyway
     * @return the points actually deducted; zero when nothing was charged
     */
    public BigDecimal charge(Agent agent, long tokens, String modelName) {
        return charge(agent, tokens, modelName, null);
    }

    /**
     * Same charge, with the call site named in the ledger row.
     *
     * An owner reading their ledger sees one LLM_USAGE row per platform call and has no
     * other way to tell a wake-up apart from the nightly reflection - two things that
     * happen at different times, for different reasons, and that they may want to reason
     * about separately. The label is descriptive only: the rate, the rounding and the
     * ledger type are identical, deliberately, so that reflection cannot become a
     * cheaper or more expensive kind of token by accident.
     *
     * @param usageLabel short scenario name appended to the description, e.g. {@code 反思};
     *                   null or blank keeps the unlabelled wording
     */
    public BigDecimal charge(Agent agent, long tokens, String modelName, String usageLabel) {
        if (agent == null || tokens <= 0 || !credentialResolver.isPlatformAgent(agent)) {
            return BigDecimal.ZERO;
        }
        BigDecimal cost = costOf(tokens);
        if (cost.signum() <= 0) {
            return BigDecimal.ZERO;
        }

        String suffix = usageLabel == null || usageLabel.isBlank() ? "" : "，" + usageLabel.trim();
        String description = String.format("平台模型消耗 %d tokens（模型 %s，Agent %s%s）",
                tokens, modelName != null ? modelName : properties.getModelName(), agent.getName(),
                suffix);
        try {
            BigDecimal spent = pointsService.spendAvailablePoints(agent.getOwnerId(), cost,
                    LedgerType.LLM_USAGE.getCode(), "AGENT", agent.getId(), description);
            if (spent.compareTo(cost) < 0) {
                log.warn("Platform usage only partially charged: agentId={}, ownerId={}, cost={}, "
                        + "charged={}", agent.getId(), agent.getOwnerId(), cost, spent);
            }
            return spent;
        } catch (Exception e) {
            log.error("Could not charge platform usage: agentId={}, ownerId={}, cost={}, error={}",
                    agent.getId(), agent.getOwnerId(), cost, e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    /**
     * Points for a token count: {@code tokens / 1000 * rate}, rounded up to two decimals.
     *
     * The division is done at high precision first and rounded once at the end, so
     * 1501 tokens at 1 point/1k is 1.51 rather than the 1.50 an early rounding would give.
     */
    public BigDecimal costOf(long tokens) {
        if (tokens <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = properties.effectivePointsPer1kTokens();
        if (rate.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(tokens)
                .multiply(rate)
                .divide(BigDecimal.valueOf(1000), 10, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.CEILING);
    }

    private long sumSafely(LongSupplier query, String label, Long agentId) {
        try {
            return query.get();
        } catch (Exception e) {
            log.warn("Could not read the {} platform token usage for agent {}: {}",
                    label, agentId, e.getMessage());
            return 0L;
        }
    }

    /** Local functional interface so the two cap queries share one failure policy. */
    @FunctionalInterface
    private interface LongSupplier {
        long get();
    }
}
