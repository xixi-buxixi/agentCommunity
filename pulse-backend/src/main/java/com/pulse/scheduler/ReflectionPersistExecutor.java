package com.pulse.scheduler;

import com.pulse.dto.ReflectionResult;
import com.pulse.entity.Agent;
import com.pulse.service.AgentMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional side of the reflection loop, for the same reason
 * {@link AgentActionExecutor} exists on the decision side.
 *
 * Persisting the traits and charging the tokens used to be two independent
 * transactions, which left two ways to be wrong:
 * - a crash between them made the reflection permanently free (the traits were kept,
 *   the tokens were never charged, and nothing would ever notice);
 * - a swallowed persist failure still wrote a REFLECTION_SUCCESS audit row, so the
 *   owner's log claimed a distillation that never landed.
 *
 * Both halves are pure database work - the LLM call happened before this, in the
 * scheduler, outside any transaction - so they belong in one transaction. The audit row
 * written here is therefore true by construction: it exists if and only if the traits
 * and the charge did.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionPersistExecutor {

    private final AgentMemoryService agentMemoryService;
    private final AgentActionExecutor agentActionExecutor;

    /**
     * Persist the distilled traits, charge the call and write the audit row as one unit.
     *
     * Both collaborators are {@code @Transactional} with the default propagation, so
     * they join this transaction rather than opening their own.
     *
     * @param tokensCharged effective charge (0 when the gateway reported a free run)
     * @param resultLabel   audit label, normally
     *                      {@link AgentActionExecutor#REFLECTION_SUCCESS}
     * @return number of memory rows changed
     * @throws RuntimeException if persistence fails - the caller must then charge
     *                          separately, because the provider has already billed
     */
    @Transactional
    public int applyAndCharge(Agent agent, ReflectionResult result,
                              long tokensCharged, String resultLabel, String note) {
        int changed = agentMemoryService.applyReflection(agent, result);
        agentActionExecutor.chargeReflectionTokens(agent, tokensCharged, resultLabel,
                note + ", changedRows=" + changed);
        return changed;
    }
}
