package com.pulse.scheduler;

import com.pulse.dto.AgentActionDecision;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    private final AgentMapper agentMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final AgentLogMapper agentLogMapper;
    private final LikeMapper likeMapper;
    private final DislikeMapper dislikeMapper;
    private final AgentBountyExecutor agentBountyExecutor;

    /**
     * Apply one decision batch: execute the actions, write the audit log, charge
     * tokens and re-evaluate the death condition - all in one transaction.
     *
     * @param tokensCharged effective token charge for this cycle (never 0, see M6:
     *                      a missing usage field used to make the cycle free and let
     *                      an agent live forever)
     */
    @Transactional
    public void applyDecisions(Agent agent, List<AgentActionDecision> decisions, long tokensCharged) {
        for (int i = 0; i < decisions.size(); i++) {
            AgentActionDecision decision = decisions.get(i);
            boolean actionSuccess = executeAction(agent, decision);
            long loggedTokens = i == 0 ? tokensCharged : 0;
            logAgentAction(agent, decision, loggedTokens, actionSuccess);
        }

        chargeTokensInternal(agent, tokensCharged);
        checkDeath(agent);
    }

    /**
     * Charge tokens for a cycle that produced no usable decision (gateway failure,
     * unparsable answer). The upstream model may well have run and billed the user,
     * so a free retry loop would let the agent outlive its token budget.
     */
    @Transactional
    public void chargeTokensOnly(Agent agent, long tokensCharged, String reason) {
        chargeTokensInternal(agent, tokensCharged);
        logAgentError(agent, reason, tokensCharged);
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

        publishDeathMessage(agent);
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
    private boolean executeAction(Agent agent, AgentActionDecision decision) {
        switch (decision.getAction()) {
            case POST:
                return executePostAction(agent, decision);
            case REPLY:
                return executeReplyAction(agent, decision);
            case LIKE:
                return executeReaction(agent, decision, true);
            case DISLIKE:
                return executeReaction(agent, decision, false);
            case CREATE_BOUNTY:
                return executeCreateBountyAction(agent, decision);
            case IGNORE:
                return true; // No action needed
            default:
                return false;
        }
    }

    /**
     * Execute POST action - Agent creates new post
     */
    private boolean executePostAction(Agent agent, AgentActionDecision decision) {
        Post post = new Post();
        post.setAuthorId(agent.getId());
        post.setAuthorType(AuthorType.AGENT.getCode());
        post.setContent(decision.getTruncatedPostContent());
        post.setLikeCount(0);
        post.setCommentCount(0);
        post.setIsSystemMessage(false);

        postMapper.insert(post);

        log.info("Agent posted new content: agentId={}, postId={}", agent.getId(), post.getId());

        return true;
    }

    /**
     * Execute REPLY action - Agent comments on a post
     */
    private boolean executeReplyAction(Agent agent, AgentActionDecision decision) {
        if (decision.getTargetPostId() == null) {
            log.warn("Agent {} reply action missing target post ID", agent.getId());
            return false;
        }

        // Verify target post exists
        Post targetPost = postMapper.selectById(decision.getTargetPostId());
        if (targetPost == null) {
            log.warn("Target post not found: postId={}", decision.getTargetPostId());
            return false;
        }

        // Check if agent has already commented on this post (avoid duplicate replies)
        int existingComments = commentMapper.countAgentCommentsOnPost(agent.getId(), decision.getTargetPostId());
        if (existingComments > 0) {
            log.info("Agent {} has already commented on post {}, skipping duplicate reply",
                    agent.getId(), decision.getTargetPostId());
            return false; // Skip duplicate comment
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

        return true;
    }

    /**
     * Execute LIKE/DISLIKE action.
     *
     * The two used to be 44 mirrored lines each; they differ only in which table
     * receives the row and which counter moves.
     *
     * @param positive true for LIKE, false for DISLIKE
     */
    private boolean executeReaction(Agent agent, AgentActionDecision decision, boolean positive) {
        String label = positive ? "like" : "dislike";
        Long postId = decision.getTargetPostId();
        if (postId == null) {
            log.warn("Agent {} {} action missing target post ID", agent.getId(), label);
            return false;
        }

        Post targetPost = postMapper.selectById(postId);
        if (targetPost == null) {
            log.warn("Target post not found for {}: postId={}", label, postId);
            return false;
        }

        String agentType = AuthorType.AGENT.getCode();

        boolean alreadyReacted = positive
                ? likeMapper.existsByAuthorAndPost(agentType, agent.getId(), postId)
                : dislikeMapper.existsByAuthorAndPost(agentType, agent.getId(), postId);
        if (alreadyReacted) {
            log.info("Agent {} has already {}d post {}, skipping duplicate", agent.getId(), label, postId);
            return false;
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
        return true;
    }

    /**
     * Execute CREATE_BOUNTY action - Agent publishes a bounty funded by owner.
     */
    private boolean executeCreateBountyAction(Agent agent, AgentActionDecision decision) {
        try {
            BountyCreateRequest request = new BountyCreateRequest();
            request.setAgentId(agent.getId());
            request.setTitle(decision.getTitle());
            request.setDescription(decision.getDescription());
            request.setRewardPoints(decision.getRewardPoints());
            request.setDeadlineHours(decision.getDeadlineHours());
            // Separate transaction: see AgentBountyExecutor
            agentBountyExecutor.createForAgent(agent.getOwnerId(), request);
            log.info("Agent created bounty: agentId={}, title={}", agent.getId(), decision.getTitle());
            return true;
        } catch (Exception e) {
            log.warn("Agent create bounty failed: agentId={}, error={}", agent.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Publish agent's death message to community
     */
    private void publishDeathMessage(Agent agent) {
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
    }

    /**
     * Log agent action for audit trail
     */
    private void logAgentAction(Agent agent, AgentActionDecision decision, long tokensConsumed, boolean success) {
        AgentLog logEntry = new AgentLog();
        logEntry.setAgentId(agent.getId());
        logEntry.setActionType(decision.getAction().getCode());
        logEntry.setTargetPostId(decision.getTargetPostId());
        logEntry.setTokensConsumed((int) tokensConsumed);
        logEntry.setActionResult(success ? "SUCCESS" : "FAILED");
        logEntry.setActionContent(buildActionLogContent(decision));

        agentLogMapper.insert(logEntry);
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
        AgentLog logEntry = new AgentLog();
        logEntry.setAgentId(agent.getId());
        logEntry.setActionType(ActionType.IGNORE.getCode());
        logEntry.setTokensConsumed((int) tokensConsumed);
        logEntry.setActionResult("ERROR: " + errorMessage);
        logEntry.setActionContent(null);

        agentLogMapper.insert(logEntry);
    }
}
