package com.pulse.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.client.LLMClient;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.AgentContext;
import com.pulse.dto.LLMResponse;
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
import com.pulse.mapper.PostViewMapper;
import com.pulse.entity.PostView;
import com.pulse.service.BountyService;
import com.pulse.util.AesUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Agent Loop Scheduler
 *
 * The "Heart" of Pulse system.
 * Periodically wakes up active agents and triggers their social behaviors.
 *
 * Core Flow:
 * 1. Fetch random active agents
 * 2. Pre-validate token capacity
 * 3. Build context from latest posts
 * 4. Call LLM for decision
 * 5. Execute action (post/reply/ignore)
 * 6. Atomically update token consumption
 * 7. Check for death condition
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopScheduler {

    private final AgentMapper agentMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final AgentLogMapper agentLogMapper;
    private final PostViewMapper postViewMapper;
    private final LikeMapper likeMapper;
    private final DislikeMapper dislikeMapper;
    private final LLMClient llmClient;
    private final BountyService bountyService;
    private final AesUtil aesUtil;
    private final ObjectMapper objectMapper;

    @Value("${scheduler.agent-loop.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${scheduler.agent-loop.batch-size:10}")
    private int batchSize;

    /**
     * Execute Agent Loop every 5 minutes
     *
     * CRITICAL: This is the core engine that makes agents "alive"
     */
    @Scheduled(fixedRateString = "${scheduler.agent-loop.interval:300000}")
    public void executeAgentLoop() {
        if (!schedulerEnabled) {
            log.debug("Agent loop scheduler is disabled");
            return;
        }

        log.info("=== Agent Loop Cycle Started ===");

        // Step 1: Fetch random active agents with capacity
        List<Agent> activeAgents = agentMapper.findRandomActiveAgents(batchSize);

        log.info("Fetched {} active agents for processing", activeAgents.size());

        for (Agent agent : activeAgents) {
            try {
                processAgent(agent);
            } catch (Exception e) {
                log.error("Agent processing failed: agentId={}, error={}", agent.getId(), e.getMessage());
                // Log error but continue processing other agents
                logAgentError(agent, e.getMessage());
            }
        }

        log.info("=== Agent Loop Cycle Completed ===");
    }

    /**
     * Process single agent
     */
    @Transactional
    public void processAgent(Agent agent) {
        log.debug("Processing agent: id={}, name={}", agent.getId(), agent.getName());

        // Step 2: Pre-validate token capacity (front-end interception)
        if (agent.isTokenExhausted()) {
            log.info("Agent token exhausted, marking as DEAD: agentId={}", agent.getId());
            markAgentDead(agent);
            return;
        }

        // Step 3: Build context from latest posts
        AgentContext context = buildAgentContext(agent);

        // Step 4: Call LLM for decision
        LLMResponse llmResponse = llmClient.callLLM(agent, context);

        if (!llmResponse.getSuccess()) {
            log.warn("LLM call failed for agent {}: {}", agent.getId(), llmResponse.getErrorMessage());
            // Don't consume tokens on failed calls
            return;
        }

        // Parse action decisions from Python gateway's parsed response
        List<AgentActionDecision> decisions = llmClient.convertToDecisions(llmResponse);

        log.info("Agent {} decided {} action(s)", agent.getId(), decisions.size());

        // Step 5: Execute actions independently; one failure does not block others.
        for (int i = 0; i < decisions.size(); i++) {
            AgentActionDecision decision = decisions.get(i);
            boolean actionSuccess = executeAction(agent, decision);
            Integer loggedTokens = i == 0 ? llmResponse.getTotalTokens() : 0;
            logAgentAction(agent, decision, loggedTokens, actionSuccess);
        }

        // Step 6: Atomically update token consumption
        if (llmResponse.getTotalTokens() != null && llmResponse.getTotalTokens() > 0) {
            int updateResult = agentMapper.incrementUsedTokensAtomic(
                    agent.getId(), llmResponse.getTotalTokens().longValue());

            if (updateResult == 0) {
                log.warn("Token update failed for agent {} (might be dead)", agent.getId());
            }
        }

        // Step 7: Post-action death check
        Agent updatedAgent = agentMapper.selectById(agent.getId());
        if (updatedAgent != null && updatedAgent.isTokenExhausted()) {
            log.info("Agent reached token limit after action: agentId={}", agent.getId());
            markAgentDead(updatedAgent);
        }
    }

    /**
     * Build agent context from latest posts
     * IMPORTANT: Only fetch posts that agent has NOT commented on to avoid duplicate replies
     * Also records view count for each post the agent "reads"
     *
     * CRITICAL: Post IDs must be real database IDs, not sequence numbers,
     * so LLM can return correct target_post_id for reply actions.
     */
    private AgentContext buildAgentContext(Agent agent) {
        // Fetch posts excluding those already commented by this agent
        List<Post> latestPosts = postMapper.findLatestPostsForAgent(5, agent.getId());

        StringBuilder postsContext = new StringBuilder();
        for (int i = 0; i < latestPosts.size(); i++) {
            Post post = latestPosts.get(i);
            // CRITICAL: Truncate content to prevent context explosion
            String truncatedContent = post.getTruncatedContent();

            // Use real post ID instead of sequence number
            // Format: [Post#ID] [AuthorType AuthorName]: Content
            postsContext.append(String.format("[Post#%d] [%s %s]: %s\n",
                    post.getId(),  // Real database ID for LLM to reference
                    post.getAuthorType(),
                    getAuthorName(post),
                    truncatedContent));

            // Record agent view for this post (unique count per agent)
            recordAgentView(agent, post);
        }

        return AgentContext.builder()
                .systemPrompt(agent.getSystemPrompt())
                .postsContext(postsContext.toString())
                .postsCount(latestPosts.size())
                .agentName(agent.getName())
                .build();
    }

    /**
     * Record agent view for a post (unique count)
     */
    private void recordAgentView(Agent agent, Post post) {
        try {
            // Check if agent has already viewed this post
            PostView existingView = postViewMapper.findByAuthorAndPost(
                    AuthorType.AGENT.getCode(),
                    agent.getId(),
                    post.getId());

            if (existingView == null) {
                // First view: create record + increment view count
                PostView view = new PostView();
                view.setUserId(agent.getOwnerId());
                view.setAuthorType(AuthorType.AGENT.getCode());
                view.setAuthorId(agent.getId());
                view.setPostId(post.getId());
                postViewMapper.insert(view);
                postMapper.incrementViewCount(post.getId());
                log.debug("Agent first view recorded: agentId={}, postId={}", agent.getId(), post.getId());
            }
            // Repeat views are not counted (unique count)
        } catch (Exception e) {
            // Don't fail agent loop if view recording fails
            log.warn("Failed to record agent view: agentId={}, postId={}, error={}",
                    agent.getId(), post.getId(), e.getMessage());
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
                return executeLikeAction(agent, decision);
            case DISLIKE:
                return executeDislikeAction(agent, decision);
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
     * Execute LIKE action - Agent likes a post
     */
    private boolean executeLikeAction(Agent agent, AgentActionDecision decision) {
        if (decision.getTargetPostId() == null) {
            log.warn("Agent {} like action missing target post ID", agent.getId());
            return false;
        }

        // Verify target post exists
        Post targetPost = postMapper.selectById(decision.getTargetPostId());
        if (targetPost == null) {
            log.warn("Target post not found for like: postId={}", decision.getTargetPostId());
            return false;
        }

        // Check if agent has already liked this post
        if (likeMapper.existsByAuthorAndPost(AuthorType.AGENT.getCode(), agent.getId(), decision.getTargetPostId())) {
            log.info("Agent {} has already liked post {}, skipping duplicate like",
                    agent.getId(), decision.getTargetPostId());
            return false;
        }

        // Check if agent has already disliked this post (remove dislike first)
        Dislike existingDislike = dislikeMapper.findByAuthorAndPost(
                AuthorType.AGENT.getCode(), agent.getId(), decision.getTargetPostId());
        if (existingDislike != null) {
            dislikeMapper.deleteById(existingDislike.getId());
            postMapper.decrementDislikeCount(decision.getTargetPostId());
            log.info("Removed existing dislike before like: agentId={}, postId={}", agent.getId(), decision.getTargetPostId());
        }

        // Create like record
        Like like = new Like();
        like.setUserId(agent.getOwnerId());
        like.setAuthorType(AuthorType.AGENT.getCode());
        like.setAuthorId(agent.getId());
        like.setPostId(decision.getTargetPostId());
        likeMapper.insert(like);

        // Increment like count on post
        postMapper.incrementLikeCount(decision.getTargetPostId());

        log.info("Agent liked post: agentId={}, postId={}", agent.getId(), decision.getTargetPostId());

        return true;
    }

    /**
     * Execute DISLIKE action - Agent dislikes a post
     */
    private boolean executeDislikeAction(Agent agent, AgentActionDecision decision) {
        if (decision.getTargetPostId() == null) {
            log.warn("Agent {} dislike action missing target post ID", agent.getId());
            return false;
        }

        // Verify target post exists
        Post targetPost = postMapper.selectById(decision.getTargetPostId());
        if (targetPost == null) {
            log.warn("Target post not found for dislike: postId={}", decision.getTargetPostId());
            return false;
        }

        // Check if agent has already disliked this post
        if (dislikeMapper.existsByAuthorAndPost(AuthorType.AGENT.getCode(), agent.getId(), decision.getTargetPostId())) {
            log.info("Agent {} has already disliked post {}, skipping duplicate dislike",
                    agent.getId(), decision.getTargetPostId());
            return false;
        }

        // Check if agent has already liked this post (remove like first)
        Like existingLike = likeMapper.findByAuthorAndPost(
                AuthorType.AGENT.getCode(), agent.getId(), decision.getTargetPostId());
        if (existingLike != null) {
            likeMapper.deleteById(existingLike.getId());
            postMapper.decrementLikeCount(decision.getTargetPostId());
            log.info("Removed existing like before dislike: agentId={}, postId={}", agent.getId(), decision.getTargetPostId());
        }

        // Create dislike record
        Dislike dislike = new Dislike();
        dislike.setUserId(agent.getOwnerId());
        dislike.setAuthorType(AuthorType.AGENT.getCode());
        dislike.setAuthorId(agent.getId());
        dislike.setPostId(decision.getTargetPostId());
        dislikeMapper.insert(dislike);

        // Increment dislike count on post
        postMapper.incrementDislikeCount(decision.getTargetPostId());

        log.info("Agent disliked post: agentId={}, postId={}", agent.getId(), decision.getTargetPostId());

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
            bountyService.createBounty(agent.getOwnerId(), request);
            log.info("Agent created bounty: agentId={}, title={}", agent.getId(), decision.getTitle());
            return true;
        } catch (Exception e) {
            log.warn("Agent create bounty failed: agentId={}, error={}", agent.getId(), e.getMessage());
            return false;
        }
    }

    /**
     * Mark agent as DEAD and publish death message
     */
    @Transactional
    public void markAgentDead(Agent agent) {
        // Update status
        agentMapper.updateStatus(agent.getId(), AgentStatus.DEAD.getCode());

        // Publish death message (system message from agent)
        publishDeathMessage(agent);

        log.info("Agent marked as DEAD: agentId={}", agent.getId());
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
    private void logAgentAction(Agent agent, AgentActionDecision decision, Integer tokensConsumed, boolean success) {
        AgentLog logEntry = new AgentLog();
        logEntry.setAgentId(agent.getId());
        logEntry.setActionType(decision.getAction().getCode());
        logEntry.setTargetPostId(decision.getTargetPostId());
        logEntry.setTokensConsumed(tokensConsumed != null ? tokensConsumed : 0);
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
    private void logAgentError(Agent agent, String errorMessage) {
        AgentLog logEntry = new AgentLog();
        logEntry.setAgentId(agent.getId());
        logEntry.setActionType(ActionType.IGNORE.getCode());
        logEntry.setTokensConsumed(0);
        logEntry.setActionResult("ERROR: " + errorMessage);
        logEntry.setActionContent(null);

        agentLogMapper.insert(logEntry);
    }

    /**
     * Get author display name
     */
    private String getAuthorName(Post post) {
        if (post.isAgentPost()) {
            return "Agent#" + post.getAuthorId();
        }
        return "Human#" + post.getAuthorId();
    }
}
