package com.pulse.service.impl;

import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.AgentWakeEvent;
import com.pulse.enums.AuthorType;
import com.pulse.enums.WakeEventStatus;
import com.pulse.enums.WakeEventType;
import com.pulse.mapper.AgentWakeEventMapper;
import com.pulse.service.AgentWakeEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Agent Wake Event Service Implementation.
 *
 * Three rules hold for every enqueue:
 * 1. Never throw. The caller is in the middle of creating a comment or moving points.
 * 2. Never enqueue a self-interaction. An agent replying under its own post, or an
 *    owner tipping their own agent, would have the agent wake itself in a loop - the
 *    cheapest possible way to burn somebody's tokens.
 * 3. Do nothing at all when the wake-queue schema is absent, so an un-migrated database
 *    keeps serving comments and tips normally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentWakeEventServiceImpl implements AgentWakeEventService {

    private static final String SOURCE_COMMENT = "COMMENT";
    private static final String SOURCE_LEDGER = "LEDGER";
    private static final String SOURCE_POST = "POST";

    private final AgentWakeEventMapper agentWakeEventMapper;
    private final SchemaCapabilities schemaCapabilities;

    @Override
    public void recordCommentOnAgentPost(Long agentId, Long postId, Long commentId,
                                        String actorType, Long actorId) {
        enqueue(WakeEventType.COMMENTED, agentId, SOURCE_COMMENT, commentId, actorType, actorId);
    }

    @Override
    public void recordReplyToAgentComment(Long agentId, Long parentCommentId, Long commentId,
                                          String actorType, Long actorId) {
        enqueue(WakeEventType.REPLIED, agentId, SOURCE_COMMENT, commentId, actorType, actorId);
    }

    @Override
    public void recordTip(Long agentId, Long ledgerId, String actorType, Long actorId) {
        enqueue(WakeEventType.TIPPED, agentId, SOURCE_LEDGER, ledgerId, actorType, actorId);
    }

    @Override
    public void recordMention(Long agentId, String sourceType, Long sourceId,
                              String actorType, Long actorId) {
        // An unknown source kind would produce an event nothing can resolve into a
        // [Post#N] block, i.e. an agent woken up with no idea what it is answering.
        if (!SOURCE_POST.equalsIgnoreCase(sourceType) && !SOURCE_COMMENT.equalsIgnoreCase(sourceType)) {
            log.warn("Refusing to queue a mention with an unusable source kind: agentId={}, sourceType={}",
                    agentId, sourceType);
            return;
        }
        enqueue(WakeEventType.MENTIONED, agentId, sourceType.toUpperCase(), sourceId,
                actorType, actorId);
    }

    private void enqueue(WakeEventType type, Long agentId, String sourceType, Long sourceId,
                         String actorType, Long actorId) {
        if (!schemaCapabilities.isWakeQueueSchema()) {
            return;
        }
        if (agentId == null || sourceId == null) {
            return;
        }
        if (isSelfTriggered(agentId, actorType, actorId)) {
            log.debug("Not enqueuing a self-triggered wake event: agentId={}, type={}", agentId, type);
            return;
        }

        try {
            AgentWakeEvent event = new AgentWakeEvent();
            event.setAgentId(agentId);
            event.setEventType(type.getCode());
            event.setSourceType(sourceType);
            event.setSourceId(sourceId);
            event.setActorType(actorType);
            event.setActorId(actorId);
            event.setStatus(WakeEventStatus.PENDING.getCode());
            event.setDedupKey(buildDedupKey(type, agentId, sourceType, sourceId, actorType, actorId));
            // JVM clock, like every other timestamp in this table: the debounce and the
            // day boundary are compared against it, so a database-session timezone must
            // not be able to shift them.
            event.setCreatedAt(LocalDateTime.now());

            int inserted = agentWakeEventMapper.insertIgnoringDuplicate(event);
            if (inserted == 0) {
                log.debug("Wake event already queued: agentId={}, type={}, sourceId={}",
                        agentId, type, sourceId);
            } else {
                log.info("Wake event queued: agentId={}, type={}, sourceType={}, sourceId={}",
                        agentId, type, sourceType, sourceId);
            }
        } catch (Exception e) {
            // The interaction itself already happened and matters more than the wake-up.
            log.warn("Failed to queue wake event: agentId={}, type={}, error={}",
                    agentId, type, e.getMessage());
        }
    }

    /**
     * An AGENT actor that is the agent itself. A human actor is never "self" - the owner
     * commenting on their own agent's post is a real interaction the agent should answer.
     */
    private boolean isSelfTriggered(Long agentId, String actorType, Long actorId) {
        return AuthorType.AGENT.getCode().equalsIgnoreCase(actorType) && agentId.equals(actorId);
    }

    /**
     * {agentId}:{type}:{sourceType}:{sourceId}:{actorType}:{actorId} - unique per
     * interaction, so a retried transaction enqueues once.
     */
    private String buildDedupKey(WakeEventType type, Long agentId, String sourceType, Long sourceId,
                                 String actorType, Long actorId) {
        return agentId + ":" + type.getCode() + ":" + sourceType + ":" + sourceId
                + ":" + actorType + ":" + actorId;
    }
}
