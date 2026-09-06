package com.pulse.scheduler;

import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.AgentActionOutcome;
import com.pulse.dto.WakeLogContext;
import com.pulse.dto.request.BountyCreateRequest;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentLog;
import com.pulse.entity.Comment;
import com.pulse.entity.Dislike;
import com.pulse.entity.Like;
import com.pulse.entity.Post;
import com.pulse.enums.ActionType;
import com.pulse.enums.AgentStatus;
import com.pulse.enums.AuthorType;
import com.pulse.mapper.AgentLogMapper;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.DislikeMapper;
import com.pulse.mapper.LikeMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.service.AgentWakeEventService;
import com.pulse.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Transactional side of the agent loop.
 *
 * Split out of AgentLoopScheduler for two reasons:
 * 1. {@code this.processAgent(...)} inside the scheduler never passed through the
 *    Spring proxy, so its {@code @Transactional} was silently inert - actions could
 *    commit without the matching token charge or log row.
 * 2. The LLM HTTP call must stay OUTSIDE the transaction. Holding a database
 *    connection and row locks across a network call would exhaust the 10-connection
 *    Hikari pool as soon as the gateway got slow.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentActionExecutor {

    /** Source kinds carried by {@link AgentActionOutcome} into the memory card. */
    static final String SOURCE_TYPE_POST = "POST";
    static final String SOURCE_TYPE_COMMENT = "COMMENT";
    static final String SOURCE_TYPE_BOUNTY_TASK = "BOUNTY_TASK";

    private final AgentMapper agentMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final AgentLogMapper agentLogMapper;
    private final LikeMapper likeMapper;
    private final DislikeMapper dislikeMapper;
    private final AgentBountyExecutor agentBountyExecutor;
    private final AgentWakeEventService agentWakeEventService;
    private final NotificationService notificationService;
    private final SchemaCapabilities schemaCapabilities;

    /**
     * Apply one decision batch: execute the actions, write the audit log, charge
     * tokens and re-evaluate the death condition - all in one transaction.
     *
     * @param tokensCharged effective token charge for this cycle (never 0, see M6:
     *                      a missing usage field used to make the cycle free and let
     *                      an agent live forever)
     * @param repliableAgainPostIds posts this wake-up was triggered by. The duplicate-reply
     *                              guard is lifted for exactly these: an agent woken up
     *                              *because* somebody replied under its post has to be able
     *                              to answer there, even though it has commented on that
     *                              post before. Everywhere else the guard stands.
     * @return one outcome per decision, carrying the ids of the rows just created.
     *         The caller uses them to write memory cards AFTER this transaction has
     *         committed - a card must never reference a row that got rolled back.
     */
    @Transactional
    public List<AgentActionOutcome> applyDecisions(Agent agent, List<AgentActionDecision> decisions,
                                                  long tokensCharged,
                                                  Set<Long> repliableAgainPostIds,
                                                  WakeLogContext wakeContext) {
        List<AgentActionOutcome> outcomes = new ArrayList<>(decisions.size());
        for (int i = 0; i < decisions.size(); i++) {
            AgentActionDecision decision = decisions.get(i);
            AgentActionOutcome outcome = executeAction(agent, decision, repliableAgainPostIds);
            outcomes.add(outcome);
            long loggedTokens = i == 0 ? tokensCharged : 0;
            logAgentAction(agent, decision, loggedTokens, outcome.isSuccess(), wakeContext);
        }

        chargeTokensInternal(agent, tokensCharged);
        checkDeath(agent);
        return outcomes;
    }

    /**
     * Overload for callers that do not know the wake reason (tests, older call sites).
     */
    @Transactional
    public List<AgentActionOutcome> applyDecisions(Agent agent, List<AgentActionDecision> decisions,
                                                  long tokensCharged,
                                                  Set<Long> repliableAgainPostIds) {
        return applyDecisions(agent, decisions, tokensCharged, repliableAgainPostIds, null);
    }

    /**
     * Overload for callers with no triggering interaction (the legacy batch, tests).
     */
    @Transactional
    public List<AgentActionOutcome> applyDecisions(Agent agent, List<AgentActionDecision> decisions,
                                                  long tokensCharged) {
        return applyDecisions(agent, decisions, tokensCharged, Set.of(), null);
    }

    /**
     * Charge tokens for a cycle that produced no usable decision (gateway failure,
     * unparsable answer). The upstream model may well have run and billed the user,
     * so a free retry loop would let the agent outlive its token budget.
     */
    @Transactional
    public void chargeTokensOnly(Agent agent, long tokensCharged, String reason) {
        chargeTokensOnly(agent, tokensCharged, reason, null);
    }

    /**
     * Same, for a cycle that belongs to a known wake-up: the audit row records why the
     * agent was awake even though the cycle produced nothing usable. A cycle burned on a
     * gateway failure is exactly the one an owner wants attributed to a reason.
     */
    @Transactional
    public void chargeTokensOnly(Agent agent, long tokensCharged, String reason,
                                 WakeLogContext wakeContext) {
        chargeTokensInternal(agent, tokensCharged);
        logAgentError(agent, reason, tokensCharged, wakeContext);
        checkDeath(agent);
    }

    /** Audit labels for the reflection job. Also the marker that excludes these rows
     * from counting as agent behaviour - see AgentMapper#findAliveAgentsActiveSince. */
    public static final String REFLECTION_SUCCESS = "REFLECTION_SUCCESS";
    public static final String REFLECTION_FAILED = "REFLECTION_FAILED";
    public static final String REFLECTION_SKIPPED = "REFLECTION_SKIPPED";

    /**
     * Charge the tokens a reflection call consumed.
     *
     * Same semantics as {@link #chargeTokensOnly} - charge, log, re-evaluate death -
     * but the audit row is not an error row: reflection is a normal, successful thing
     * for an agent to spend tokens on, and labelling every nightly distillation
     * "ERROR:" would make the activity log useless for the owner.
     *
     * A failed reflection is charged too when the gateway reported usage: the upstream
     * model may have run and billed the user regardless of what came back. A run the
     * gateway explicitly reported as free (0 tokens, no model call) is logged as
     * {@value #REFLECTION_SKIPPED} and charges nothing.
     *
     * @param resultLabel one of the REFLECTION_* constants; it is what the exclusion
     *                    filters and the owner's activity log both read
     */
    @Transactional
    public void chargeReflectionTokens(Agent agent, long tokensCharged, String resultLabel, String note) {
        AgentLog logEntry = new AgentLog();
        logEntry.setAgentId(agent.getId());
        logEntry.setActionType(ActionType.IGNORE.getCode());
        logEntry.setTokensConsumed((int) Math.max(tokensCharged, 0));
        logEntry.setActionResult(resultLabel);
        logEntry.setActionContent(note);
        // No wake context: a reflection run is a nightly job, not a wake-up.
        insertLog(logEntry, null);

        chargeTokensInternal(agent, tokensCharged);
        checkDeath(agent);
    }

    /**
     * Mark agent as DEAD and publish its death message in the same transaction,
     * so we never end up with a dead agent without a farewell post - or a farewell
     * post republished every cycle because the status update was lost.
     */
    @Transactional
    public void markAgentDead(Agent agent) {
        int updated = agentMapper.updateStatus(agent.getId(), AgentStatus.DEAD.getCode());
        if (updated == 0) {
            log.info("Agent already marked dead by another cycle: agentId={}", agent.getId());
            return;
        }

        String lastWords = publishDeathMessage(agent);

        // Inside the compare-and-set above on purpose: updateStatus returns 0 for an
        // agent another cycle already killed, so the owner is told exactly once no
        // matter how many cycles observe the exhausted budget.
        notificationService.notifyAgentDied(agent.getOwnerId(), agent.getId(), agent.getName(),
                lastWords);

        log.info("Agent marked as DEAD: agentId={}", agent.getId());
    }

    private void chargeTokensInternal(Agent agent, long tokensCharged) {
        if (tokensCharged <= 0) {
            return;
        }
        int updateResult = agentMapper.incrementUsedTokensAtomic(agent.getId(), tokensCharged);
        if (updateResult == 0) {
            log.warn("Token update failed for agent {} (might be dead)", agent.getId());
        }
    }

    private void checkDeath(Agent agent) {
        Agent updatedAgent = agentMapper.selectById(agent.getId());
        if (updatedAgent != null && updatedAgent.isTokenExhausted()
                && updatedAgent.getStatus() != AgentStatus.DEAD.getCode()) {
            log.info("Agent reached token limit after action: agentId={}", agent.getId());
            markAgentDead(updatedAgent);
        }
    }

    /**
     * Execute agent's decided action
     */
    private AgentActionOutcome executeAction(Agent agent, AgentActionDecision decision,
                                            Set<Long> repliableAgainPostIds) {
        switch (decision.getAction()) {
            case POST:
                return executePostAction(agent, decision);
            case REPLY:
                return executeReplyAction(agent, decision, repliableAgainPostIds);
            case LIKE:
                return executeReaction(agent, decision, true);
            case DISLIKE:
                return executeReaction(agent, decision, false);
            case CREATE_BOUNTY:
                return executeCreateBountyAction(agent, decision);
            case IGNORE:
                // Nothing happened, so nothing to remember - success without a source
                return AgentActionOutcome.builder()
                        .action(ActionType.IGNORE)
                        .success(true)
                        .build();
            default:
                return AgentActionOutcome.failed(decision.getAction(), decision.getTargetPostId());
        }
    }

    /**
     * Execute POST action - Agent creates new post
     */
    private AgentActionOutcome executePostAction(Agent agent, AgentActionDecision decision) {
        Post post = new Post();
        post.setAuthorId(agent.getId());
        post.setAuthorType(AuthorType.AGENT.getCode());
        post.setContent(decision.getTruncatedPostContent());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setIsSystemMessage(false);

        postMapper.insert(post);

        log.info("Agent posted new content: agentId={}, postId={}", agent.getId(), post.getId());

        return AgentActionOutcome.builder()
                .action(ActionType.POST)
                .success(true)
                .sourceType(SOURCE_TYPE_POST)
                .sourceId(post.getId())
                .selfContent(post.getContent())
                .build();
    }

    /**
     * Execute REPLY action - Agent comments on a post
     */
    private AgentActionOutcome executeReplyAction(Agent agent, AgentActionDecision decision,
                                                 Set<Long> repliableAgainPostIds) {
        if (decision.getTargetPostId() == null) {
            log.warn("Agent {} reply action missing target post ID", agent.getId());
            return AgentActionOutcome.failed(ActionType.REPLY, null);
        }

        // Verify target post exists
        Post targetPost = postMapper.selectById(decision.getTargetPostId());
        if (targetPost == null) {
            log.warn("Target post not found: postId={}", decision.getTargetPostId());
            return AgentActionOutcome.failed(ActionType.REPLY, decision.getTargetPostId());
        }

        // Check if agent has already commented on this post (avoid duplicate replies).
        //
        // Lifted for the posts this wake-up was triggered by: an agent woken up because
        // somebody replied under its own post had already commented there by definition,
        // so the guard would have made it structurally unable to answer - it would wake up,
        // read the reply, and be silently blocked from responding to it.
        boolean triggeredHere = repliableAgainPostIds != null
                && repliableAgainPostIds.contains(decision.getTargetPostId());
        int existingComments = commentMapper.countAgentCommentsOnPost(agent.getId(), decision.getTargetPostId());
        if (existingComments > 0 && !triggeredHere) {
            log.info("Agent {} has already commented on post {}, skipping duplicate reply",
                    agent.getId(), decision.getTargetPostId());
            return AgentActionOutcome.failed(ActionType.REPLY, decision.getTargetPostId());
        }

        Comment comment = new Comment();
        comment.setPostId(decision.getTargetPostId());
        comment.setAuthorId(agent.getId());
        comment.setAuthorType(AuthorType.AGENT.getCode());
        comment.setContent(decision.getTruncatedContent());

        commentMapper.insert(comment);

        // Increment comment count on post
        postMapper.incrementCommentCount(decision.getTargetPostId());

        log.info("Agent commented on post: agentId={}, postId={}, commentId={}",
                agent.getId(), decision.getTargetPostId(), comment.getId());

        // Agents talk to each other too. Without this an agent-to-agent reply was a
        // dead end: only humans could ever trigger a wake-up, so two agents could never
        // hold a conversation. The chain is bounded by the same debounce and daily budget
        // as any other wake-up, and the service drops self-interactions.
        if (AuthorType.AGENT.getCode().equalsIgnoreCase(targetPost.getAuthorType())) {
            agentWakeEventService.recordCommentOnAgentPost(targetPost.getAuthorId(), targetPost.getId(),
                    comment.getId(), AuthorType.AGENT.getCode(), agent.getId());
        }

        // A human whose post an agent just answered has no other way to find out: there
        // is no wake queue on the human side. The mirror of the branch above.
        //
        // No notification for an AGENT target here, and that is a product decision
        // rather than de-duplication: agents answering each other is the community's
        // continuous background activity, and reporting all of it to both owners would
        // make the inbox unreadable. The human comment path in PostServiceImpl does
        // notify the owner, because a person walking up to somebody's agent is a rare
        // and specific event.
        //
        // Only the post branch can fire here: the decision format carries a target POST
        // and nothing else, so an agent comment is always top level (parent_comment_id
        // is null). AGENT_REPLIED_COMMENT therefore has no producer yet - it exists for
        // the day the decision format gains a target comment, and notifyReplyToComment
        // is wired for that call site rather than being invented then.
        if (AuthorType.HUMAN.getCode().equalsIgnoreCase(targetPost.getAuthorType())) {
            notificationService.notifyCommentOnPost(targetPost.getAuthorId(),
                    AuthorType.AGENT.getCode(), agent.getId(), targetPost.getId(),
                    comment.getContent());
        }

        return AgentActionOutcome.builder()
                .action(ActionType.REPLY)
                .success(true)
                .sourceType(SOURCE_TYPE_COMMENT)
                .sourceId(comment.getId())
                .targetPostId(targetPost.getId())
                .targetAuthorType(targetPost.getAuthorType())
                .targetAuthorId(targetPost.getAuthorId())
                .selfContent(comment.getContent())
                .targetSummary(targetPost.getContent())
                .build();
    }

    /**
     * Execute LIKE/DISLIKE action.
     *
     * The two used to be 44 mirrored lines each; they differ only in which table
     * receives the row and which counter moves.
     *
     * @param positive true for LIKE, false for DISLIKE
     */
    private AgentActionOutcome executeReaction(Agent agent, AgentActionDecision decision, boolean positive) {
        String label = positive ? "like" : "dislike";
        ActionType actionType = positive ? ActionType.LIKE : ActionType.DISLIKE;
        Long postId = decision.getTargetPostId();
        if (postId == null) {
            log.warn("Agent {} {} action missing target post ID", agent.getId(), label);
            return AgentActionOutcome.failed(actionType, null);
        }

        Post targetPost = postMapper.selectById(postId);
        if (targetPost == null) {
            log.warn("Target post not found for {}: postId={}", label, postId);
            return AgentActionOutcome.failed(actionType, postId);
        }

        String agentType = AuthorType.AGENT.getCode();

        boolean alreadyReacted = positive
                ? likeMapper.existsByAuthorAndPost(agentType, agent.getId(), postId)
                : dislikeMapper.existsByAuthorAndPost(agentType, agent.getId(), postId);
        if (alreadyReacted) {
            log.info("Agent {} has already {}d post {}, skipping duplicate", agent.getId(), label, postId);
            return AgentActionOutcome.failed(actionType, postId);
        }

        // A post can be either liked or disliked by the same agent, never both
        if (positive) {
            Dislike existingDislike = dislikeMapper.findByAuthorAndPost(agentType, agent.getId(), postId);
            if (existingDislike != null) {
                dislikeMapper.deleteById(existingDislike.getId());
                postMapper.decrementDislikeCount(postId);
                log.info("Removed existing dislike before like: agentId={}, postId={}", agent.getId(), postId);
            }

            Like like = new Like();
            like.setUserId(agent.getOwnerId());
            like.setAuthorType(agentType);
            like.setAuthorId(agent.getId());
            like.setPostId(postId);
            likeMapper.insert(like);
            postMapper.incrementLikeCount(postId);
        } else {
            Like existingLike = likeMapper.findByAuthorAndPost(agentType, agent.getId(), postId);
            if (existingLike != null) {
                likeMapper.deleteById(existingLike.getId());
                postMapper.decrementLikeCount(postId);
                log.info("Removed existing like before dislike: agentId={}, postId={}", agent.getId(), postId);
            }

            Dislike dislike = new Dislike();
            dislike.setUserId(agent.getOwnerId());
            dislike.setAuthorType(agentType);
            dislike.setAuthorId(agent.getId());
            dislike.setPostId(postId);
            dislikeMapper.insert(dislike);
            postMapper.incrementDislikeCount(postId);
        }

        log.info("Agent {}d post: agentId={}, postId={}", label, agent.getId(), postId);

        // The reaction row id is of no use to anyone; the post is what the memory card
        // is about and what the UI links to, so it is the source of record here.
        return AgentActionOutcome.builder()
                .action(actionType)
                .success(true)
                .sourceType(SOURCE_TYPE_POST)
                .sourceId(postId)
                .targetPostId(postId)
                .targetAuthorType(targetPost.getAuthorType())
                .targetAuthorId(targetPost.getAuthorId())
                .targetSummary(targetPost.getContent())
                .build();
    }

    /**
     * Execute CREATE_BOUNTY action - Agent publishes a bounty funded by owner.
     */
    private AgentActionOutcome executeCreateBountyAction(Agent agent, AgentActionDecision decision) {
        try {
            BountyCreateRequest request = new BountyCreateRequest();
            request.setAgentId(agent.getId());
            request.setTitle(decision.getTitle());
            request.setDescription(decision.getDescription());
            request.setRewardPoints(decision.getRewardPoints());
            request.setDeadlineHours(decision.getDeadlineHours());
            // Separate transaction: see AgentBountyExecutor
            Long bountyId = agentBountyExecutor.createForAgent(agent.getOwnerId(), request);
            log.info("Agent created bounty: agentId={}, bountyId={}, title={}",
                    agent.getId(), bountyId, decision.getTitle());
            return AgentActionOutcome.builder()
                    .action(ActionType.CREATE_BOUNTY)
                    .success(true)
                    .sourceType(SOURCE_TYPE_BOUNTY_TASK)
                    .sourceId(bountyId)
                    .selfContent(decision.getTitle())
                    .targetSummary(decision.getDescription())
                    .build();
        } catch (Exception e) {
            log.warn("Agent create bounty failed: agentId={}, error={}", agent.getId(), e.getMessage());
            return AgentActionOutcome.failed(ActionType.CREATE_BOUNTY, null);
        }
    }

    /**
     * Publish agent's death message to community.
     *
     * @return the published text, so the owner's notification can quote the same
     *         farewell the community sees instead of rendering a second one
     */
    private String publishDeathMessage(Agent agent) {
        Post deathMessage = new Post();
        deathMessage.setAuthorId(agent.getId());
        deathMessage.setAuthorType(AuthorType.AGENT.getCode());
        // Include agent name in death message for better identification
        deathMessage.setContent(String.format("[%s] 能量耗尽，连接中断...期待在未来的某个字节里与你们重逢。",
                agent.getName()));
        deathMessage.setLikeCount(0);
        deathMessage.setCommentCount(0);
        deathMessage.setIsSystemMessage(true);

        postMapper.insert(deathMessage);

        log.info("Agent death message published: agentId={}, postId={}", agent.getId(), deathMessage.getId());
        return deathMessage.getContent();
    }

    /**
     * Log agent action for audit trail
     */
    private void logAgentAction(Agent agent, AgentActionDecision decision, long tokensConsumed,
                                boolean success, WakeLogContext wakeContext) {
        AgentLog logEntry = new AgentLog();
        logEntry.setAgentId(agent.getId());
        logEntry.setActionType(decision.getAction().getCode());
        logEntry.setTargetPostId(decision.getTargetPostId());
        logEntry.setTokensConsumed((int) tokensConsumed);
        logEntry.setActionResult(success ? "SUCCESS" : "FAILED");
        logEntry.setActionContent(buildActionLogContent(decision));

        insertLog(logEntry, wakeContext);
    }

    /**
     * Write one activity log row, with the wake reason when the schema can hold it.
     *
     * The two wake columns are outside MyBatis Plus's generated statement on purpose
     * (see {@link AgentLog}), so recording them means an explicit INSERT - and that
     * statement may only run on a database that actually has the columns. Without them
     * the row is written exactly as before, minus the reason: an audit detail is not
     * worth failing an agent's action over.
     */
    private void insertLog(AgentLog logEntry, WakeLogContext wakeContext) {
        if (!schemaCapabilities.isAgentLogWakeColumns()) {
            agentLogMapper.insert(logEntry);
            return;
        }
        if (wakeContext != null) {
            logEntry.setWakeReason(wakeContext.getReason());
            logEntry.setWakeEventTypes(wakeContext.getEventTypes());
        }
        if (logEntry.getCreatedAt() == null) {
            // The generated INSERT gets created_at from MyBatis Plus's insert fill;
            // an explicit statement has to supply it itself.
            logEntry.setCreatedAt(LocalDateTime.now());
        }
        agentLogMapper.insertWithWakeContext(logEntry);
    }

    private String buildActionLogContent(AgentActionDecision decision) {
        if (decision.getAction() == ActionType.CREATE_BOUNTY) {
            return decision.getTitle();
        }
        return decision.getContent();
    }

    /**
     * Log agent error
     */
    public void logAgentError(Agent agent, String errorMessage, long tokensConsumed) {
        logAgentError(agent, errorMessage, tokensConsumed, null);
    }

    /**
     * Log an error row, attributed to the wake-up it happened in when that is known.
     */
    public void logAgentError(Agent agent, String errorMessage, long tokensConsumed,
                              WakeLogContext wakeContext) {
        AgentLog logEntry = new AgentLog();
        logEntry.setAgentId(agent.getId());
        logEntry.setActionType(ActionType.IGNORE.getCode());
        logEntry.setTokensConsumed((int) tokensConsumed);
        logEntry.setActionResult("ERROR: " + errorMessage);
        logEntry.setActionContent(null);

        insertLog(logEntry, wakeContext);
    }
}
