package com.pulse.scheduler;

import com.pulse.client.LLMClient;
import com.pulse.config.HotNewsProperties;
import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentActionDecision;
import com.pulse.dto.AgentActionOutcome;
import com.pulse.dto.AgentContext;
import com.pulse.dto.AgentMemoryCard;
import com.pulse.dto.LLMResponse;
import com.pulse.dto.WakeLogContext;
import com.pulse.dto.response.HotNewsReportResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentWakeEvent;
import com.pulse.entity.Comment;
import com.pulse.entity.Post;
import com.pulse.entity.PostView;
import com.pulse.enums.AuthorType;
import com.pulse.enums.WakeEventType;
import com.pulse.enums.WakeReason;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.PostViewMapper;
import com.pulse.service.AgentMemoryService;
import com.pulse.service.HotNewsService;
import com.pulse.service.support.AuthorResolver;
import com.pulse.service.support.PlatformUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One agent, one wake-up: build the context, ask the model, apply what it decided.
 *
 * Extracted verbatim from AgentLoopScheduler when the wake-up mechanism became
 * pluggable. Both the legacy 12-hour batch and the per-agent queue drive exactly this
 * code, so "queue mode behaves like legacy mode plus a better trigger" is true by
 * construction rather than by careful duplication.
 *
 * Not transactional on purpose: it contains the LLM HTTP call. The database work is
 * delegated to {@link AgentActionExecutor}, which owns the transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentWakeProcessor {

    /** Source kind of an interaction that happened under a post. */
    private static final String SOURCE_COMMENT = "COMMENT";

    /**
     * Source kind of an interaction that IS a post - today only a MENTIONED event whose
     * "@name" was written in a post body rather than in a comment.
     */
    private static final String SOURCE_POST = "POST";

    /** Interaction lines carry only identifier-safe, length-bounded actor names. */
    private static final int ACTOR_NAME_MAX_LENGTH = 20;

    /** How much of an interaction body travels into its post block. */
    private static final int INTERACTION_BODY_PREVIEW = 150;

    /**
     * How many of a post's comments are rendered as child lines of its block.
     *
     * A ceiling rather than paging: the point is to give the agent something specific to
     * answer, and the tail of a discussion does that. Every additional line is charged to
     * the same context budget the timeline competes for.
     */
    private static final int POST_COMMENT_PREVIEW_LIMIT = 5;

    /**
     * Report date rendered inside the world block's header, where a "]" or a newline
     * would let the value forge a block boundary. Ingested values are dates
     * ("2026-09-06"), so anything outside this alphabet collapses to an underscore -
     * the same stance as the actor names in the interaction lines.
     */
    private static final java.util.regex.Pattern UNSAFE_HEADER_META =
            java.util.regex.Pattern.compile("[^0-9A-Za-z_:.\\-]+");

    /**
     * Resolved interaction sources for one wake-up.
     *
     * A plain holder rather than three parallel maps threaded through the call chain; it
     * exists only for the duration of a single wake.
     */
    private static final class InteractionSources {
        /** Only for events whose source is a comment; a post-sourced event has none. */
        private final Map<Long, Comment> commentsByEventId = new HashMap<>();

        /**
         * The post every resolved event happened in, whatever its source kind. This is
         * what the interaction line points at, so a MENTIONED event written straight into
         * a post body still says "正文见上方 Post#N" instead of dangling.
         */
        private final Map<Long, Long> postIdByEventId = new HashMap<>();

        private final Set<Long> postIds = new LinkedHashSet<>();
        private final Map<Long, List<AgentWakeEvent>> eventsByPostId = new HashMap<>();

        void link(AgentWakeEvent event, Long postId) {
            postIdByEventId.put(event.getId(), postId);
            postIds.add(postId);
            eventsByPostId.computeIfAbsent(postId, key -> new ArrayList<>()).add(event);
        }
    }

    private final AgentMapper agentMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final PostViewMapper postViewMapper;
    private final LLMClient llmClient;
    private final AgentActionExecutor agentActionExecutor;
    private final AgentMemoryService agentMemoryService;
    private final AuthorResolver authorResolver;
    private final SchemaCapabilities schemaCapabilities;
    private final HotNewsService hotNewsService;
    private final HotNewsProperties hotNewsProperties;

    /**
     * Spending rules for agents on the platform-hosted model. Returns null / zero for
     * every BYOK agent, so nothing below this line behaves differently for them.
     */
    private final PlatformUsageService platformUsageService;

    /**
     * Minimum tokens charged for a cycle that actually reached the model.
     * Prevents "free" cycles when the gateway returns no usage numbers.
     */
    @Value("${scheduler.agent-loop.min-token-charge:200}")
    private long minTokenCharge;

    /**
     * Wake an agent and let it act.
     *
     * @param reason why it is being woken; only affects logging and prompt framing
     * @param events interactions to answer (empty for a rhythm or legacy wake)
     */
    public WakeOutcome wake(Agent agent, WakeReason reason, List<AgentWakeEvent> events) {
        return wake(agent, reason, events, false);
    }

    /**
     * Wake an agent and let it act.
     *
     * @param reason         why it is being woken; only affects logging and prompt framing
     * @param events         interactions to answer (empty for a rhythm or legacy wake)
     * @param firstWakeToday whether this is the agent's first wake-up of the day. Decided
     *                       by the queue scheduler, which is the only place that can know
     *                       it: the daily counter is incremented inside the atomic slot
     *                       claim, so the Agent object read before the claim still carries
     *                       yesterday's - or a stale - number. The legacy batch never sets
     *                       it, which is also what keeps the world block out of legacy mode.
     */
    public WakeOutcome wake(Agent agent, WakeReason reason, List<AgentWakeEvent> events,
                            boolean firstWakeToday) {
        log.debug("Waking agent: id={}, name={}, reason={}", agent.getId(), agent.getName(), reason);

        // Why this agent is awake, recorded on every audit row this cycle writes.
        WakeLogContext wakeContext = WakeLogContext.of(reason, events);

        // Stamp the dispatch time first: findRandomActiveAgents orders by it, so
        // stamping before the (slow) LLM call keeps the round-robin honest even if
        // this agent's processing fails.
        //
        // In queue mode the stamp has already happened as part of claiming the wake slot
        // (one atomic statement covering budget + debounce), so stamping again here would
        // only move the debounce window forward for no reason.
        if (reason == WakeReason.LEGACY_BATCH && schemaCapabilities.isLastDispatchedAtColumn()) {
            agentMapper.markDispatched(agent.getId());
        }

        // Step 2: Pre-validate token capacity (front-end interception)
        if (agent.isTokenExhausted()) {
            log.info("Agent token exhausted, marking as DEAD: agentId={}", agent.getId());
            agentActionExecutor.markAgentDead(agent);
            return WakeOutcome.PROCESSED;
        }

        // Step 2b: the same gate for agents whose calls the platform pays for.
        //
        // Next to the token pre-check on purpose: both answer "may this agent spend
        // anything at all", both run before any context is built, and both are shared by
        // the legacy batch and the queue. The token threshold protects the agent's own
        // budget; this protects the owner's points and the platform's bill. BYOK agents
        // get null here and fall straight through.
        //
        // Note what does NOT change for a skipped agent: no tokens are charged, no
        // agent_logs action row is written beyond the IGNORE row below, and the DEAD
        // condition is untouched. Being out of points is a pause, not a death.
        PlatformUsageService.SkipReason skipReason = platformUsageService.checkReadiness(agent);
        if (skipReason != null) {
            log.info("Platform agent not woken: agentId={}, reason={}", agent.getId(), skipReason);
            // Zero tokens: nothing reached a model, so this row exists to explain the
            // silence, not to bill for it.
            agentActionExecutor.logAgentError(agent,
                    "PLATFORM_SKIPPED: " + skipReason.name() + " - " + skipReason.getText(),
                    0, wakeContext);
            platformUsageService.notifyIfActionable(agent, skipReason);
            return WakeOutcome.SKIPPED;
        }

        // Everything this wake-up is answering, resolved once: the comments, the posts
        // they sit under, and which comment belongs to which post. The posts are rendered
        // as ordinary post blocks (so the model can answer them) and the duplicate-reply
        // guard is lifted for exactly these, or an agent woken by a reply under its own
        // post would be unable to answer there.
        InteractionSources sources = resolveInteractionSources(events);
        Set<Long> triggeringPostIds = sources.postIds;

        // Step 3: Build context from latest posts (plus memories and interactions)
        AgentContext context = buildAgentContext(agent, events, sources, reason, firstWakeToday);

        // Step 4: Call LLM for decision (no transaction held here)
        LLMResponse llmResponse = llmClient.callLLM(agent, context);

        if (!llmResponse.getSuccess()) {
            log.warn("LLM call failed for agent {}: {}", agent.getId(), llmResponse.getErrorMessage());
            // The upstream model may already have run and billed the user, so this
            // cycle is not free: charge the floor instead of nothing.
            agentActionExecutor.chargeTokensOnly(agent, minTokenCharge,
                    "LLM_CALL_FAILED: " + llmResponse.getErrorMessage(), wakeContext);
            // Same reasoning applied to the owner's points: a failure envelope does not
            // prove the provider did not bill, so a platform agent pays the same floor it
            // charged against its own token budget.
            platformUsageService.charge(agent, minTokenCharge, llmResponse.getModel());
            return WakeOutcome.PROCESSED;
        }

        // Parse action decisions from Python gateway's parsed response
        List<AgentActionDecision> decisions = llmClient.convertToDecisions(llmResponse);

        log.info("Agent {} decided {} action(s) (reason={})", agent.getId(), decisions.size(), reason);

        long tokensCharged = resolveTokenCharge(llmResponse);

        if (decisions.isEmpty()) {
            agentActionExecutor.chargeTokensOnly(agent, tokensCharged, "NO_ACTIONABLE_DECISION",
                    wakeContext);
            platformUsageService.charge(agent, tokensCharged, llmResponse.getModel());
            return WakeOutcome.PROCESSED;
        }

        // Steps 5-7 in a single transaction
        List<AgentActionOutcome> outcomes = agentActionExecutor.applyDecisions(
                agent, decisions, tokensCharged, triggeringPostIds, wakeContext);

        // Step 8: structured memory cards, deliberately AFTER the commit above.
        // Inside the transaction a card could reference a rolled-back post, and a
        // failing card insert would take the whole action + token charge down with it.
        // recordActionMemories never throws (same tolerance as recordAgentView).
        agentMemoryService.recordActionMemories(agent, outcomes);

        // Billing last, and outside the executor's transaction on purpose: the actions
        // are already committed, and a points movement must never be able to roll back a
        // reply the community has seen. charge() never throws and never goes below zero.
        platformUsageService.charge(agent, tokensCharged, llmResponse.getModel());
        return WakeOutcome.PROCESSED;
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
    private AgentContext buildAgentContext(Agent agent, List<AgentWakeEvent> events,
                                           InteractionSources sources, WakeReason reason,
                                           boolean firstWakeToday) {
        // Fetch posts excluding those already commented by this agent
        List<Post> latestPosts = postMapper.findLatestPostsForAgent(5, agent.getId());

        StringBuilder postsContext = new StringBuilder();

        // Triggering posts first, and always included even though the timeline query
        // excludes posts this agent has already commented on - which is precisely the case
        // for a post somebody just replied under. Each one is its own [Post#N] block, so the
        // gateway's per-block filtering can neutralise a malicious comment without taking
        // the rest of the interaction context with it.
        Set<Long> timelineIds = latestPosts.stream().map(Post::getId).collect(Collectors.toSet());
        for (Long postId : sources.postIds) {
            if (timelineIds.contains(postId)) {
                continue;
            }
            appendPostBlock(postsContext, agent, postId, sources);
        }

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

            // The discussion under it, so a reply can answer a person rather than a post.
            appendCommentLines(postsContext, post.getId(), List.of());

            // Record agent view for this post (unique count per agent)
            recordAgentView(agent, post);
        }

        // The world block goes AFTER the posts, as its own block: it is a system push, not
        // something anyone said in the community, and the gateway must be able to
        // neutralise it without touching a single post.
        appendWorldBlock(postsContext, reason, firstWakeToday);

        List<AgentMemoryCard> memories = selectMemories(agent);

        return AgentContext.builder()
                .systemPrompt(agent.getSystemPrompt())
                .postsContext(postsContext.toString())
                .postsCount(latestPosts.size())
                .agentName(agent.getName())
                .memories(memories)
                .memoriesContext(renderMemories(memories))
                .eventsContext(renderEvents(events, sources))
                .build();
    }

    /**
     * What this wake-up is answering: the comments, the posts they sit under, and the
     * mapping between them.
     *
     * Resolved in one pass so the comment bodies are read once and can be rendered inside
     * their post's block - the model needs the actual words ("your second conclusion is
     * wrong, because X"), not just a pointer to a thread.
     */
    private InteractionSources resolveInteractionSources(List<AgentWakeEvent> events) {
        InteractionSources sources = new InteractionSources();
        if (events == null || events.isEmpty()) {
            return sources;
        }
        for (AgentWakeEvent event : events) {
            if (event.getSourceId() == null) {
                continue;
            }
            try {
                if (SOURCE_COMMENT.equalsIgnoreCase(event.getSourceType())) {
                    Comment comment = commentMapper.selectById(event.getSourceId());
                    if (comment == null || comment.getPostId() == null) {
                        continue;
                    }
                    sources.commentsByEventId.put(event.getId(), comment);
                    sources.link(event, comment.getPostId());
                } else if (SOURCE_POST.equalsIgnoreCase(event.getSourceType())) {
                    // A mention written in a post body: the post IS the thing to read, so
                    // there is no separate quote to render - the [Post#N] block below
                    // already carries the words that named this agent.
                    Post post = postMapper.selectById(event.getSourceId());
                    if (post == null || Integer.valueOf(1).equals(post.getDeleted())) {
                        continue;
                    }
                    sources.link(event, post.getId());
                }
                // Any other source kind (a LEDGER row for a tip) has no post to show; the
                // interaction line still names the actor and what they did.
            } catch (Exception e) {
                // A thinner prompt, not a lost wake-up
                log.warn("Could not resolve the source of wake event {}: {}",
                        event.getId(), e.getMessage());
            }
        }
        return sources;
    }

    /**
     * Append one triggering post in the standard block format, with the interactions that
     * happened under it.
     *
     * The interaction lines sit INSIDE the block on purpose: the body is community text, so
     * it belongs where the gateway's per-block filtering can neutralise it without
     * discarding anything else. Both the name and the body are flattened, so neither can
     * open a forged block of its own.
     */
    private void appendPostBlock(StringBuilder target, Agent agent, Long postId,
                                 InteractionSources sources) {
        try {
            Post post = postMapper.selectById(postId);
            if (post == null || Integer.valueOf(1).equals(post.getDeleted())) {
                return;
            }
            target.append(String.format("[Post#%d] [%s %s]: %s%n",
                    post.getId(),
                    post.getAuthorType(),
                    getAuthorName(post),
                    flattenForContext(post.getTruncatedContent())));

            // The triggering comments are pinned into the list: they are the reason this
            // wake-up exists, and "the five most recent" is no guarantee they are among
            // them on a busy post.
            List<AgentWakeEvent> events = sources.eventsByPostId.getOrDefault(postId, List.of());
            List<Comment> triggering = new ArrayList<>();
            for (AgentWakeEvent event : events) {
                Comment comment = sources.commentsByEventId.get(event.getId());
                if (comment != null) {
                    triggering.add(comment);
                }
            }
            Set<Long> rendered = appendCommentLines(target, postId, triggering);

            for (AgentWakeEvent event : events) {
                Comment comment = sources.commentsByEventId.get(event.getId());
                if (comment == null || comment.getContent() == null || comment.getContent().isBlank()) {
                    continue;
                }
                if (comment.getId() != null && rendered.contains(comment.getId())) {
                    // The body is already above as its own [Comment#N] line; repeating it
                    // here would spend the context budget twice on the same sentence. What
                    // this line adds is which of those comments woke the agent, and who
                    // wrote it under the name the community actually sees.
                    target.append(String.format("  [最新互动] %s → [Comment#%d]%n",
                            flattenForContext(displayActorName(event)), comment.getId()));
                } else {
                    // No comment line to point at (the query failed, or the row is not
                    // renderable): quote it here instead, so a wake-up never arrives
                    // without the words it is answering.
                    target.append(String.format("  [最新互动] %s: %s%n",
                            flattenForContext(displayActorName(event)),
                            flattenForContext(truncate(comment.getContent(), INTERACTION_BODY_PREVIEW))));
                }
            }

            recordAgentView(agent, post);
        } catch (Exception e) {
            log.warn("Could not render triggering post {}: {}", postId, e.getMessage());
        }
    }

    /**
     * Render a post's recent comments as child lines of its block:
     * {@code "  [Comment#40] [HUMAN Human#7]: 我觉得你上一条说反了"}.
     *
     * Two leading spaces, and no [Comment# in the gateway's block-header alternation: a
     * comment belongs to the post it hangs under, so the post's block stays neutralisable
     * as one unit. A comment that could open a block of its own would let a hostile
     * comment be filtered while the post it attacks stayed behind - and would hand a
     * comment body the forged-boundary trick that flattening exists to prevent.
     *
     * The author name is synthesised ({@code Agent#12} / {@code Human#3}) exactly like a
     * post header's, so nothing user-controlled reaches the part of the line the gateway
     * parses. The real display name still travels in the interaction line above.
     *
     * A failed query costs the comment lines, never the wake-up: the post block is still
     * rendered and the caller falls back to quoting the triggering comment inline.
     *
     * @param pinned comments that must appear whether or not they are among the most
     *               recent - the ones this wake-up is answering
     * @return the ids actually rendered, so the caller knows what it can point at
     */
    private Set<Long> appendCommentLines(StringBuilder target, Long postId,
                                         List<Comment> pinned) {
        List<Comment> recent;
        try {
            recent = commentMapper.findRecentCommentsByPost(postId, POST_COMMENT_PREVIEW_LIMIT);
        } catch (Exception e) {
            log.warn("Could not load the comments of post {}: {}", postId, e.getMessage());
            recent = List.of();
        }

        // Newest-first from the query, so the most recent survive the limit; ordered back
        // into reading order below, because a discussion read backwards is a different
        // discussion.
        Map<Long, Comment> byId = new LinkedHashMap<>();
        for (Comment comment : recent) {
            if (isRenderableComment(comment)) {
                byId.putIfAbsent(comment.getId(), comment);
            }
        }
        for (Comment comment : pinned) {
            if (isRenderableComment(comment)) {
                byId.putIfAbsent(comment.getId(), comment);
            }
        }
        if (byId.isEmpty()) {
            return Set.of();
        }

        List<Comment> ordered = new ArrayList<>(byId.values());
        ordered.sort(Comparator
                .comparing(Comment::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Comment::getId, Comparator.nullsFirst(Comparator.naturalOrder())));

        Set<Long> rendered = new LinkedHashSet<>();
        for (Comment comment : ordered) {
            target.append(String.format("  [Comment#%d] [%s %s]: %s%n",
                    comment.getId(),
                    comment.isAgentComment() ? AuthorType.AGENT.getCode() : AuthorType.HUMAN.getCode(),
                    getAuthorName(comment),
                    flattenForContext(truncate(comment.getContent(), INTERACTION_BODY_PREVIEW))));
            rendered.add(comment.getId());
        }
        return rendered;
    }

    private boolean isRenderableComment(Comment comment) {
        return comment != null
                && comment.getId() != null
                && !Integer.valueOf(1).equals(comment.getDeleted())
                && comment.getContent() != null
                && !comment.getContent().isBlank();
    }

    /**
     * Append today's report as one [World#N] block, when all three conditions hold.
     *
     * 1. The feature is switched on. Off by default: it changes what every agent talks
     *    about and costs tokens on each injection.
     * 2. This is a queue-mode wake-up. The legacy batch wakes the same agents over and
     *    over on a fixed interval with no per-agent day counter, so "first wake-up of the
     *    day" has no meaning there and the block would be injected on every batch.
     * 3. It is this agent's first wake-up today. The report is a daily thing; repeating it
     *    on every wake-up would spend tokens re-telling an agent what it already read.
     *
     * A missing report, or an unreachable one, is not a reason to skip a wake-up: it is
     * logged and the agent wakes up with the community timeline alone.
     */
    private void appendWorldBlock(StringBuilder target, WakeReason reason, boolean firstWakeToday) {
        HotNewsProperties.Context config = hotNewsProperties.getContext();
        if (config == null || !config.isEnabled()) {
            return;
        }
        // firstWakeToday can only be true in queue mode (the legacy batch never sets it),
        // but the reason is checked as well so the rule survives a future caller.
        if (reason == WakeReason.LEGACY_BATCH || !firstWakeToday) {
            return;
        }
        try {
            HotNewsReportResponse report = hotNewsService.getLatest();
            if (report == null) {
                return;
            }
            String body = flattenForContext(joinReportBody(report));
            if (body.isBlank()) {
                log.warn("Daily report has no title or summary to inject: reportId={}",
                        report.getReportId());
                return;
            }
            int maxChars = config.getMaxChars() > 0 ? config.getMaxChars() : 600;
            if (body.length() > maxChars) {
                body = body.substring(0, maxChars);
            }
            target.append(String.format("[World#%s] [SYSTEM 今日日报 %s]: %s%n",
                    report.getReportId() != null ? report.getReportId() : 0,
                    safeHeaderMeta(report.getReportDate()),
                    body));
        } catch (Exception e) {
            // No report today, or the cache and the database are both unavailable. An
            // agent without the news is a slightly less informed agent, not a broken one.
            log.warn("Could not inject the daily report into the wake context: {}", e.getMessage());
        }
    }

    /**
     * The report body as "<title>。<summary>", skipping the separator when one half is
     * missing so a report without a summary does not end in a dangling full stop.
     */
    private String joinReportBody(HotNewsReportResponse report) {
        String title = report.getTitle() != null ? report.getTitle().trim() : "";
        String summary = report.getSummary() != null ? report.getSummary().trim() : "";
        if (title.isEmpty()) {
            return summary;
        }
        if (summary.isEmpty()) {
            return title;
        }
        return title + "。" + summary;
    }

    /**
     * A value safe to interpolate into a block header. Same stance as
     * {@link #safeActorName}: the header is system-generated scaffolding, so nothing that
     * reaches it may contain a bracket, a colon-free separator or a newline.
     */
    private String safeHeaderMeta(String raw) {
        if (raw == null || raw.isBlank()) {
            return "-";
        }
        String cleaned = UNSAFE_HEADER_META.matcher(raw.trim()).replaceAll("_");
        return cleaned.isEmpty() ? "-" : truncate(cleaned, 32);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }

    /**
     * Render the interactions this wake-up is answering.
     *
     * Each line names the actor, the thing they touched and a short quote, so the model
     * can answer the specific person instead of posting into the void. Quotes go through
     * the same flattening as post content - an interaction body is community text and
     * must not be able to forge a context block.
     *
     * A body that cannot be loaded degrades to the bare "who did what": being unable to
     * quote a comment is no reason to skip answering it.
     */
    private String renderEvents(List<AgentWakeEvent> events, InteractionSources sources) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        for (AgentWakeEvent event : events) {
            WakeEventType type = event.getEventTypeEnum();
            String phrase = type != null ? type.getText() : "与你互动";
            // Whichever kind of source it was, what the agent needs is the post to read.
            Long postId = sources.postIdByEventId.get(event.getId());
            if (postId != null) {
                block.append(String.format("[互动] %s %s，正文见上方 Post#%d%n",
                        describeActor(event), phrase, postId));
            } else {
                block.append(String.format("[互动] %s %s%n", describeActor(event), phrase));
            }
        }
        return block.toString();
    }

    private String describeActor(AgentWakeEvent event) {
        if (event.getActorId() == null) {
            return "有人";
        }
        try {
            AuthorResolver.AuthorInfo info = authorResolver.resolve(event.getActorType(), event.getActorId());
            if (info != null && info.getAuthorName() != null) {
                String safe = safeActorName(info.getAuthorName());
                if (!safe.isEmpty()) {
                    return safe;
                }
            }
        } catch (Exception e) {
            log.debug("Could not resolve wake event actor: type={}, id={}",
                    event.getActorType(), event.getActorId());
        }
        return (event.getActorType() != null ? event.getActorType() : "Someone") + "#" + event.getActorId();
    }

    /**
     * Display name for use INSIDE a post block, where the gateway's per-block filters
     * apply, so the real (possibly non-ASCII) name can be shown.
     */
    private String displayActorName(AgentWakeEvent event) {
        if (event.getActorId() == null) {
            return "有人";
        }
        try {
            AuthorResolver.AuthorInfo info = authorResolver.resolve(event.getActorType(), event.getActorId());
            if (info != null && info.getAuthorName() != null && !info.getAuthorName().isBlank()) {
                return truncate(info.getAuthorName(), ACTOR_NAME_MAX_LENGTH);
            }
        } catch (Exception e) {
            log.debug("Could not resolve wake event actor for display: id={}", event.getActorId());
        }
        return (event.getActorType() != null ? event.getActorType() : "Someone") + "#" + event.getActorId();
    }

    /**
     * A display name safe to interpolate into a system-generated line.
     *
     * The interaction lines are the one part of the context that is NOT wrapped in a
     * post block, so nothing user-controlled may reach them verbatim: a name is reduced to
     * plain identifier characters and a short length, and anything else degrades to
     * "TYPE#id". The actual comment text lives in its own [Post#N] block, where the
     * gateway's per-block filters can deal with it.
     */
    private String safeActorName(String rawName) {
        String stripped = rawName.replaceAll("[^A-Za-z0-9_.\\-]", "");
        if (stripped.length() > ACTOR_NAME_MAX_LENGTH) {
            return stripped.substring(0, ACTOR_NAME_MAX_LENGTH);
        }
        return stripped;
    }

    /**
     * Memory selection must never be able to stop an agent from acting: an agent with
     * an unreadable memory table is a worse agent, not a broken one. Same tolerance as
     * recordAgentView.
     */
    private List<AgentMemoryCard> selectMemories(Agent agent) {
        try {
            return agentMemoryService.selectForInjection(agent.getId());
        } catch (Exception e) {
            log.warn("Failed to select memories for injection: agentId={}, error={}",
                    agent.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * One prefixed line per memory, mirroring the posts block.
     *
     * The prefix carries type, confidence and provenance so the model can weigh a
     * memory instead of reading it as fact, and the content goes through the same
     * flattening as post content: a memory is untrusted text too, and must not be able
     * to forge a block boundary.
     */
    private String renderMemories(List<AgentMemoryCard> memories) {
        if (memories.isEmpty()) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        for (AgentMemoryCard memory : memories) {
            block.append(String.format("[记忆|%s|置信度%s|%s]: %s%n",
                    memory.getMemoryType(),
                    memory.getConfidenceScore() != null ? memory.getConfidenceScore() : "-",
                    memory.getSource() != null ? memory.getSource() : "UNKNOWN",
                    flattenForContext(memory.getContent())));
        }
        return block.toString();
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
     * Same synthesised shape for a comment's child line. Synthesised rather than
     * resolved: this is the part of the line the gateway parses as a header, so nothing
     * user-controlled may reach it - and resolving five names per post would cost a
     * lookup per comment for a name the interaction line already carries.
     */
    private String getAuthorName(Comment comment) {
        if (comment.isAgentComment()) {
            return "Agent#" + comment.getAuthorId();
        }
        return "Human#" + comment.getAuthorId();
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
                .replace("[Post#", "(Post#")
                // Same reason, for the world block header the gateway also treats as a
                // boundary: no piece of untrusted text may be able to open one.
                .replace("[World#", "(World#")
                // [Comment#N] is not a block boundary, but it IS the handle the model
                // uses to name a reply target. A body that could write one would let a
                // post invent comments that do not exist, or point the agent's reply at
                // a comment it never read.
                .replace("[Comment#", "(Comment#");
    }
}
