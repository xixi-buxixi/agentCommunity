package com.pulse.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pulse.config.SchemaCapabilities;
import com.pulse.entity.Agent;
import com.pulse.entity.Comment;
import com.pulse.entity.Post;
import com.pulse.enums.AgentStatus;
import com.pulse.enums.AuthorType;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.service.AgentMentionService;
import com.pulse.service.AgentWakeEventService;
import com.pulse.service.support.MentionDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Agent Mention Service Implementation.
 *
 * Four rules hold for every call:
 * 1. Never throw. The caller is inside the transaction that creates the post or the
 *    comment, and a mention is worth less than the thing that carried it.
 * 2. Do nothing when the wake-queue schema is absent - the enqueue would be dropped
 *    anyway, and the candidate queries are not worth running for a dropped event.
 * 3. Resolve only inside the bounded candidate set (see {@link AgentMentionService}).
 * 4. One interaction, at most one wake-up per agent: the agent already queued a
 *    COMMENTED / REPLIED event for this same comment is skipped.
 *
 * NAME MATCHING runs the other way round from what "@mention" suggests: the candidates
 * are resolved first, and the body is then searched for each of their names. Nothing is
 * ever parsed out of the text as a name, because agents.name accepts any characters at
 * all and a fixed alphabet of "name characters" silently made every agent with a space or
 * a full stop in its name unreachable by "@". See {@link MentionDetector}.
 *
 * The comparison is case-insensitive, matching the collation of {@code agents.name}
 * itself (utf8mb4 default, which is what {@code uk_owner_active_name} enforces
 * uniqueness under). Matching case-sensitively here would mean "@Alice" failing to
 * reach an agent that the database considers to be called Alice, and no owner could
 * tell why. Several agents may legitimately answer to one name - different owners, and
 * the same name in different cases - so ALL of them are woken rather than an arbitrary
 * first one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMentionServiceImpl implements AgentMentionService {

    static final String SOURCE_POST = "POST";
    static final String SOURCE_COMMENT = "COMMENT";

    /**
     * Ceiling on the agents pulled out of one thread as mention candidates.
     *
     * The comment tree is user-driven, so the IN list built from it has to be bounded
     * somewhere. A thread with more agents than this is already past the point where one
     * more mention target matters.
     */
    static final int MAX_THREAD_AGENTS = 50;

    /**
     * Ceiling on the speaker's own agents considered for a mention. An owner with more
     * than this cannot summon the rest by name; they still wake on their own rhythm.
     */
    static final int MAX_OWNED_AGENTS = 50;

    private final AgentMapper agentMapper;
    private final CommentMapper commentMapper;
    private final AgentWakeEventService agentWakeEventService;
    private final SchemaCapabilities schemaCapabilities;

    @Override
    public void recordMentionsInPost(Post post, String speakerType, Long speakerId) {
        if (post == null) {
            return;
        }
        record(post, post.getContent(), SOURCE_POST, post.getId(), speakerType, speakerId, null);
    }

    @Override
    public void recordMentionsInComment(Post post, Comment comment, String speakerType,
                                        Long speakerId, Long alreadyWokenAgentId) {
        if (post == null || comment == null) {
            return;
        }
        record(post, comment.getContent(), SOURCE_COMMENT, comment.getId(), speakerType, speakerId,
                alreadyWokenAgentId);
    }

    /**
     * The one path. Everything that can go wrong is caught here, so no call site has to
     * think about it.
     */
    private void record(Post post, String body, String sourceType, Long sourceId,
                        String speakerType, Long speakerId, Long alreadyWokenAgentId) {
        if (!schemaCapabilities.isWakeQueueSchema()) {
            return;
        }
        if (post == null || post.getId() == null || sourceId == null) {
            return;
        }

        try {
            // The cheap half of the old regex: a body with no "@" in it mentions nobody,
            // and must not cost the two candidate queries below. Everything past this
            // point is driven by the candidate names themselves.
            if (!MentionDetector.containsMentionMarker(body)) {
                return;
            }

            List<Agent> candidates = resolveCandidates(post, speakerType, speakerId);
            if (candidates.isEmpty()) {
                return;
            }

            Set<String> mentioned = mentionedNames(body, candidates);
            if (mentioned.isEmpty()) {
                return;
            }

            boolean speakerIsAgent = AuthorType.AGENT.getCode().equalsIgnoreCase(speakerType);
            Set<Long> queued = new LinkedHashSet<>();
            for (Agent agent : candidates) {
                Long agentId = agent.getId();
                if (agentId == null || agent.getName() == null || agent.getName().isBlank()) {
                    continue;
                }
                // An agent naming itself would wake itself, which is the cheapest possible
                // way to burn its owner's tokens - the same rule the wake event service
                // applies to self-interactions.
                if (speakerIsAgent && agentId.equals(speakerId)) {
                    continue;
                }
                if (agentId.equals(alreadyWokenAgentId)) {
                    log.debug("Mention skipped, this comment already wakes the agent: agentId={}",
                            agentId);
                    continue;
                }
                if (queued.contains(agentId) || !isMentioned(mentioned, agent.getName())) {
                    continue;
                }
                queued.add(agentId);
                agentWakeEventService.recordMention(agentId, sourceType, sourceId,
                        speakerType, speakerId);
            }

            if (!queued.isEmpty()) {
                log.info("Mentions queued: sourceType={}, sourceId={}, agents={}",
                        sourceType, sourceId, queued);
            }
        } catch (Exception e) {
            // The post or comment already exists and matters more than the wake-up.
            log.warn("Failed to queue mentions: sourceType={}, sourceId={}, error={}",
                    sourceType, sourceId, e.getMessage());
        }
    }

    /**
     * The agents an "@name" in this thread is allowed to reach.
     *
     * Three sources, in the order they are cheapest to justify: the post's author, the
     * agents that already commented under it, and - only when a person is speaking - the
     * agents that person owns. Every one of them is alive: a dead agent's wake event
     * would be retired unanswered by the queue's own sweep, so queueing it is only noise.
     */
    private List<Agent> resolveCandidates(Post post, String speakerType, Long speakerId) {
        List<Agent> candidates = new ArrayList<>();

        Set<Long> threadAgentIds = new LinkedHashSet<>();
        if (post.isAgentPost() && post.getAuthorId() != null) {
            threadAgentIds.add(post.getAuthorId());
        }
        List<Long> commenters = commentMapper.findAgentAuthorIdsByPost(post.getId(), MAX_THREAD_AGENTS);
        if (commenters != null) {
            commenters.stream().filter(Objects::nonNull).forEach(threadAgentIds::add);
        }
        if (!threadAgentIds.isEmpty()) {
            List<Agent> alive = agentMapper.findAliveAgentsByIds(new ArrayList<>(threadAgentIds));
            if (alive != null) {
                candidates.addAll(alive);
            }
        }

        // Only a person's own agents join the set: an agent speaking cannot pull in the
        // agents of the owner it happens to share, or one owner's agents would be able to
        // summon each other without either ever entering the thread.
        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(speakerType) && speakerId != null) {
            LambdaQueryWrapper<Agent> owned = new LambdaQueryWrapper<>();
            owned.eq(Agent::getOwnerId, speakerId)
                    .eq(Agent::getStatus, AgentStatus.ALIVE.getCode())
                    .orderByAsc(Agent::getId)
                    .last("LIMIT " + MAX_OWNED_AGENTS);
            List<Agent> ownedAgents = agentMapper.selectList(owned);
            if (ownedAgents != null) {
                candidates.addAll(ownedAgents);
            }
        }

        return candidates;
    }

    /**
     * The candidates' names the body actually mentions.
     *
     * The names go to the detector rather than the detector handing back fragments for
     * this class to resolve: an agent may legitimately be called "Dr. Ada Lovelace", and
     * no fixed alphabet of name characters can cut that out of a sentence correctly. The
     * search is bounded on both sides - at most {@link #MAX_THREAD_AGENTS} +
     * {@link #MAX_OWNED_AGENTS} candidates, against a body the request layer already
     * limits - so it stays a fixed cost per post or comment.
     */
    private Set<String> mentionedNames(String body, List<Agent> candidates) {
        Set<String> names = new LinkedHashSet<>();
        for (Agent agent : candidates) {
            if (agent.getName() != null && !agent.getName().isBlank()) {
                names.add(agent.getName().trim());
            }
        }
        return MentionDetector.detect(body, names);
    }

    /**
     * Whether one of the mentioned names is this agent's - see the class comment for why
     * the comparison ignores case.
     */
    private boolean isMentioned(Set<String> mentioned, String agentName) {
        String name = agentName.trim();
        for (String candidate : mentioned) {
            if (candidate.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
