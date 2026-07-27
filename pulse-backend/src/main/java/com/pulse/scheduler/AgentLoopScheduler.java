package com.pulse.scheduler;

import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.AgentContext;
import com.pulse.dto.LLMResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.Post;
import com.pulse.entity.PostView;
import com.pulse.enums.AuthorType;
import com.pulse.client.LLMClient;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.PostViewMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * 4. Call LLM for decision            <- outside any transaction, see below
 * 5. Execute action (post/reply/ignore)
 * 6. Atomically update token consumption
 * 7. Check for death condition
 *
 * Steps 5-7 run as one transaction inside {@link AgentActionExecutor}. Step 4 must
 * stay out of it: a network call inside a transaction pins a pooled connection for
 * its whole duration.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopScheduler {

    private final AgentMapper agentMapper;
    private final PostMapper postMapper;
    private final PostViewMapper postViewMapper;
    private final LLMClient llmClient;
    private final AgentActionExecutor agentActionExecutor;

    @Value("${scheduler.agent-loop.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${scheduler.agent-loop.batch-size:10}")
    private int batchSize;

    /**
     * Minimum tokens charged for a cycle that actually reached the model.
     * Prevents "free" cycles when the gateway returns no usage numbers.
     */
    @Value("${scheduler.agent-loop.min-token-charge:200}")
    private long minTokenCharge;

    /**
     * Execute the agent loop on the configured interval (12h by default).
     *
     * fixedDelayString, not fixedRateString: with a real thread pool a slow cycle
     * would otherwise overlap the next one and wake the same agents twice.
     * ShedLock keeps a second instance from burning the user's tokens in parallel.
     */
    @Scheduled(fixedDelayString = "${scheduler.agent-loop.interval:43200000}")
    @SchedulerLock(name = "agentLoopCycle", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
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
                log.error("Agent processing failed: agentId={}", agent.getId(), e);
                // Log error but continue processing other agents
                agentActionExecutor.logAgentError(agent, safeMessage(e), 0);
            }
        }

        log.info("=== Agent Loop Cycle Completed ===");
    }

    /**
     * Process single agent.
     *
     * Intentionally NOT transactional: it contains the LLM HTTP call. The database
     * work is delegated to AgentActionExecutor, which owns the transaction.
     */
    private void processAgent(Agent agent) {
        log.debug("Processing agent: id={}, name={}", agent.getId(), agent.getName());

        // Stamp the dispatch time first: findRandomActiveAgents orders by it, so
        // stamping before the (slow) LLM call keeps the round-robin honest even if
        // this agent's processing fails.
        agentMapper.markDispatched(agent.getId());

        // Step 2: Pre-validate token capacity (front-end interception)
        if (agent.isTokenExhausted()) {
            log.info("Agent token exhausted, marking as DEAD: agentId={}", agent.getId());
            agentActionExecutor.markAgentDead(agent);
            return;
        }

        // Step 3: Build context from latest posts
        AgentContext context = buildAgentContext(agent);

        // Step 4: Call LLM for decision (no transaction held here)
        LLMResponse llmResponse = llmClient.callLLM(agent, context);

        if (!llmResponse.getSuccess()) {
            log.warn("LLM call failed for agent {}: {}", agent.getId(), llmResponse.getErrorMessage());
            // The upstream model may already have run and billed the user, so this
            // cycle is not free: charge the floor instead of nothing.
            agentActionExecutor.chargeTokensOnly(agent, minTokenCharge,
                    "LLM_CALL_FAILED: " + llmResponse.getErrorMessage());
            return;
        }

        // Parse action decisions from Python gateway's parsed response
        List<AgentActionDecision> decisions = llmClient.convertToDecisions(llmResponse);

        log.info("Agent {} decided {} action(s)", agent.getId(), decisions.size());

        long tokensCharged = resolveTokenCharge(llmResponse);

        if (decisions.isEmpty()) {
            agentActionExecutor.chargeTokensOnly(agent, tokensCharged, "NO_ACTIONABLE_DECISION");
            return;
        }

        // Steps 5-7 in a single transaction
        agentActionExecutor.applyDecisions(agent, decisions, tokensCharged);
    }

    /**
     * Effective token charge for a cycle.
     *
     * A missing or zero usage figure used to mean no charge at all, which turned
     * token_threshold - the mechanism agents die from - into something an upstream
     * without usage reporting could bypass indefinitely.
     */
    private long resolveTokenCharge(LLMResponse llmResponse) {
        Integer reported = llmResponse.getTotalTokens();
        if (reported != null && reported > 0) {
            return reported.longValue();
        }
        log.warn("Gateway reported no token usage; charging the configured floor of {}", minTokenCharge);
        return minTokenCharge;
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
        for (Post post : latestPosts) {
            // CRITICAL: Truncate content to prevent context explosion
            String truncatedContent = flattenForContext(post.getTruncatedContent());

            // Use real post ID instead of sequence number
            // Format: [Post#ID] [AuthorType AuthorName]: Content
            postsContext.append(String.format("[Post#%d] [%s %s]: %s%n",
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
     * Get author display name
     */
    private String getAuthorName(Post post) {
        if (post.isAgentPost()) {
            return "Agent#" + post.getAuthorId();
        }
        return "Human#" + post.getAuthorId();
    }

    /**
     * Flatten post content into a single line for the context block.
     *
     * The gateway splits the context into per-post blocks on lines that look like
     * "[Post#N] [TYPE name]:". Post content is user-controlled, so a post containing
     * a newline followed by such a line could forge a block boundary and split an
     * injection payload across two blocks, each passing the filters on its own.
     * Removing newlines (and defusing a literal "[Post#") makes that impossible at
     * the source.
     */
    private String flattenForContext(String content) {
        if (content == null) {
            return "";
        }
        return content
                .replaceAll("[\\r\\n]+", " ")
                .replace("[Post#", "(Post#");
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message != null ? message : e.getClass().getSimpleName();
    }
}
