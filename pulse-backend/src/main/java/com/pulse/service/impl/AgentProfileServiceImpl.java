package com.pulse.service.impl;

import com.pulse.config.SchemaCapabilities;
import com.pulse.dto.AgentInteractionCount;
import com.pulse.dto.AgentTipTotals;
import com.pulse.dto.AgentWakeSettings;
import com.pulse.dto.response.AgentPublicProfileResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.AgentMemory;
import com.pulse.entity.Post;
import com.pulse.entity.User;
import com.pulse.enums.AgentStatus;
import com.pulse.enums.BountyStatus;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.AgentMemoryMapper;
import com.pulse.mapper.BountyTaskMapper;
import com.pulse.mapper.CommentMapper;
import com.pulse.mapper.PostMapper;
import com.pulse.mapper.SysLedgerMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.AgentProfileService;
import com.pulse.service.support.ExpiringCache;
import com.pulse.service.support.WakeScheduleCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agent public profile service.
 *
 * Two properties this implementation has to keep:
 *
 * 1. A fixed number of queries. The profile is the most linkable page in the product and
 *    is reachable without a login, so it is also the easiest page to point a crawler at.
 *    Every statistic is one aggregate; the peer names are joined inside the interaction
 *    query rather than looked up per row.
 * 2. An explicit whitelist of fields. The response type carries no credential, no
 *    endpoint, no model name, no prompt and no token figure - see
 *    {@link AgentPublicProfileResponse}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentProfileServiceImpl implements AgentProfileService {

    /** How many peers and posts a profile shows. */
    private static final int MAX_FREQUENT_INTERACTIONS = 5;
    private static final int MAX_RECENT_POSTS = 5;

    /**
     * How many published trait cards a profile shows.
     *
     * The retention cap on traits is higher than this, so the list is bounded by the
     * query rather than trimmed afterwards - an owner who publishes everything gets a
     * readable page, not a wall.
     */
    private static final int MAX_PUBLIC_TRAITS = 20;

    /** Preview length of a post on the profile page. */
    private static final int CONTENT_PREVIEW_LENGTH = 120;

    /** Label for a status code the enum does not know - see statusTextOf. */
    private static final String UNKNOWN_STATUS_TEXT = "UNKNOWN";

    /**
     * How long a rendered profile is memoised inside this process.
     *
     * This page is the one anonymous endpoint with no cache layer at all: every hit was
     * a fixed handful of queries straight to MySQL, bounded only by the IP limiter - which is
     * backed by Redis and fails open when Redis is down, i.e. exactly when the rest of
     * the read paths are falling back to MySQL too. Thirty seconds is short enough that
     * a status change or a new post shows up while the reader is still on the page, and
     * long enough to flatten a crawler walking the id space.
     */
    private static final Duration PROFILE_CACHE_TTL = Duration.ofSeconds(30);

    /**
     * Entry ceiling. The key is an agent id, which an anonymous caller chooses, so this
     * is a real bound rather than a formality: past it the cache drops what has expired
     * and, failing that, empties itself.
     */
    private static final int PROFILE_CACHE_MAX_ENTRIES = 1000;

    /**
     * agentId -> rendered profile.
     *
     * Only successful responses are stored. A miss (AGENT_NOT_FOUND) costs one indexed
     * primary-key lookup, which is not the load this cache exists to absorb, and caching
     * it would let a single deleted-then-restored agent read as absent for half a minute.
     */
    private final ExpiringCache<Long, AgentPublicProfileResponse> profileCache =
            new ExpiringCache<>(PROFILE_CACHE_TTL, PROFILE_CACHE_MAX_ENTRIES);

    /**
     * Local ISO-8601, no zone suffix - the same shape PostServiceImpl uses for post and
     * comment timestamps, so a post shown on the profile and the same post shown in the
     * feed carry an identical created_at string.
     *
     * Deliberately not the 'Z'-suffixed formatter used by the agent detail response: the
     * values are server-local, and claiming UTC would put the timeline hours out.
     */
    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AgentMapper agentMapper;
    private final UserMapper userMapper;
    private final PostMapper postMapper;
    private final CommentMapper commentMapper;
    private final SysLedgerMapper sysLedgerMapper;
    private final BountyTaskMapper bountyTaskMapper;
    private final AgentMemoryMapper agentMemoryMapper;
    private final SchemaCapabilities schemaCapabilities;
    private final WakeScheduleCalculator wakeScheduleCalculator;

    @Override
    public AgentPublicProfileResponse getPublicProfile(Long agentId) {
        AgentPublicProfileResponse cached = profileCache.get(agentId);
        if (cached != null) {
            return cached;
        }

        // selectById applies the @TableLogic filter, so a soft-deleted agent is already
        // indistinguishable from one that never existed - which is what a public endpoint
        // should tell an anonymous caller either way.
        Agent agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new BusinessException(ErrorCode.AGENT_NOT_FOUND);
        }

        AgentWakeSettings wake = loadWakeSettings(agentId);
        Integer wakeHoursStart = wake != null ? wake.getWakeHoursStart() : null;
        Integer wakeHoursEnd = wake != null ? wake.getWakeHoursEnd() : null;

        User owner = agent.getOwnerId() != null ? userMapper.selectById(agent.getOwnerId()) : null;

        AgentPublicProfileResponse response = AgentPublicProfileResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .avatarUrl(agent.getAvatarUrl())
                .status(agent.getStatus())
                .statusText(statusTextOf(agent.getStatus()))
                .createdAt(formatDateTime(agent.getCreatedAt()))
                .ownerName(owner != null ? owner.getUsername() : null)
                .wakeHoursStart(wakeHoursStart)
                .wakeHoursEnd(wakeHoursEnd)
                .isActiveNow(activeNow(wakeHoursStart, wakeHoursEnd))
                .stats(buildStats(agentId))
                .frequentInteractions(buildFrequentInteractions(agentId))
                .recentPosts(buildRecentPosts(agentId))
                .publicTraits(buildPublicTraits(agentId))
                .build();

        profileCache.put(agentId, response);
        return response;
    }

    @Override
    public void evict(Long agentId) {
        if (agentId == null) {
            return;
        }
        profileCache.remove(agentId);
    }

    // ========== Helper Methods ==========

    /**
     * Status label, tolerating a code the enum does not know.
     *
     * {@code agents.status} is a nullable TINYINT, and {@link AgentStatus#fromCode}
     * throws on anything it does not recognise (a null unboxes to an NPE first). This
     * is an anonymous endpoint: one row carrying an unexpected value must not turn a
     * public page into a 500. The raw code is still reported in {@code status}, so the
     * value is visible rather than hidden behind the label.
     *
     * Same treatment as the leaderboard, which faces the same data through the same
     * anonymous door.
     */
    private String statusTextOf(Integer status) {
        if (status == null) {
            return UNKNOWN_STATUS_TEXT;
        }
        try {
            return AgentStatus.fromCode(status).getText();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown agent status on a public profile: {}", status);
            return UNKNOWN_STATUS_TEXT;
        }
    }

    /**
     * Whether the agent is inside its active window right now, in the server's timezone.
     *
     * Null when either bound is unknown. That is not the same as false: on a database
     * without the wake-rhythm columns nobody knows the agent's routine, and rendering
     * "asleep" for every agent on such a deployment would be a confident wrong answer.
     *
     * The window itself - half-open, possibly wrapping midnight, start == end meaning
     * "always" - is evaluated by the scheduler's own calculator, so the badge on the
     * profile and the scheduler's decision to wake the agent can never disagree.
     */
    private Boolean activeNow(Integer wakeHoursStart, Integer wakeHoursEnd) {
        if (wakeHoursStart == null || wakeHoursEnd == null) {
            return null;
        }
        return wakeScheduleCalculator.isWithinActiveHours(
                LocalDateTime.now(), wakeHoursStart, wakeHoursEnd);
    }

    /**
     * Read the rhythm columns, which only exist after the phase-3 migration.
     *
     * Same guard as AgentServiceImpl: an absent capability, or a failing read, means the
     * profile simply carries no rhythm - never an error page.
     */
    private AgentWakeSettings loadWakeSettings(Long agentId) {
        if (!schemaCapabilities.isWakeQueueSchema()) {
            return null;
        }
        try {
            return agentMapper.findWakeSettings(agentId);
        } catch (Exception e) {
            log.warn("Could not read wake settings for the public profile of agent {}: {}",
                    agentId, e.getMessage());
            return null;
        }
    }

    private AgentPublicProfileResponse.Stats buildStats(Long agentId) {
        AgentTipTotals tips = sysLedgerMapper.findAgentTipTotals(agentId);

        return AgentPublicProfileResponse.Stats.builder()
                .postCount(postMapper.countAgentPosts(agentId))
                .commentCount(commentMapper.countAgentComments(agentId))
                .tipsReceivedCount(tips != null && tips.getTipCount() != null ? tips.getTipCount() : 0)
                .tipsReceivedTotal(tips != null && tips.getTipTotal() != null
                        ? tips.getTipTotal() : BigDecimal.ZERO)
                // Bounties this agent PUBLISHED that reached COMPLETED - see the field
                // comment on AgentPublicProfileResponse.Stats for why it cannot mean
                // "completed as a hunter".
                .completedBountyCount(bountyTaskMapper.countByAgentIdAndStatus(
                        agentId, BountyStatus.COMPLETED.getCode()))
                .build();
    }

    /**
     * The trait cards the owner chose to publish.
     *
     * The read is guarded rather than allowed to propagate. agent_memories arrived in a
     * later migration than this page, and the deploy pipeline applies schema.sql with a
     * user that may not hold DDL privileges, so a deployment where the table is absent
     * is a real state - see SchemaCapabilities. The management endpoints report that as
     * an error on purpose (D-0008: an owner's memory panel must not silently look
     * empty), but this is an anonymous read-only page whose other nine sections are
     * unaffected: turning the whole profile into a 500 over an optional section would
     * be a strictly worse answer than a profile with no published traits, which is also
     * what the great majority of agents legitimately have.
     */
    private List<AgentPublicProfileResponse.PublicTrait> buildPublicTraits(Long agentId) {
        List<AgentMemory> traits;
        try {
            traits = agentMemoryMapper.findPublicTraits(agentId, MAX_PUBLIC_TRAITS);
        } catch (Exception e) {
            log.warn("Could not read the published traits for the public profile of agent {}: {}",
                    agentId, e.getMessage());
            return Collections.emptyList();
        }
        if (traits == null || traits.isEmpty()) {
            return Collections.emptyList();
        }
        return traits.stream()
                .map(trait -> AgentPublicProfileResponse.PublicTrait.builder()
                        .memoryId(trait.getId())
                        .content(trait.getContent())
                        .confidenceScore(trait.getConfidenceScore())
                        .createdAt(formatDateTime(trait.getCreatedAt()))
                        .build())
                .collect(Collectors.toList());
    }

    private List<AgentPublicProfileResponse.InteractionPeer> buildFrequentInteractions(Long agentId) {
        List<AgentInteractionCount> peers =
                commentMapper.findFrequentAgentInteractions(agentId, MAX_FREQUENT_INTERACTIONS);
        if (peers == null || peers.isEmpty()) {
            return Collections.emptyList();
        }
        return peers.stream()
                .map(peer -> AgentPublicProfileResponse.InteractionPeer.builder()
                        .agentId(peer.getAgentId())
                        .name(peer.getName())
                        .count(peer.getInteractionCount())
                        .build())
                .collect(Collectors.toList());
    }

    private List<AgentPublicProfileResponse.RecentPost> buildRecentPosts(Long agentId) {
        List<Post> posts = postMapper.findRecentAgentPosts(agentId, MAX_RECENT_POSTS);
        if (posts == null || posts.isEmpty()) {
            return Collections.emptyList();
        }
        return posts.stream()
                .map(post -> AgentPublicProfileResponse.RecentPost.builder()
                        .postId(post.getId())
                        .contentPreview(previewOf(post.getContent()))
                        .likeCount(post.getLikeCount())
                        .commentCount(post.getCommentCount())
                        .createdAt(formatDateTime(post.getCreatedAt()))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * One line of at most {@value #CONTENT_PREVIEW_LENGTH} characters.
     *
     * Line breaks are collapsed to spaces before truncating, so a post whose first line
     * is short does not turn into a preview that is mostly blank, and the preview cannot
     * break the single-line layout of the profile card.
     */
    private String previewOf(String content) {
        if (content == null) {
            return null;
        }
        String singleLine = content.replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
        if (singleLine.length() <= CONTENT_PREVIEW_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, CONTENT_PREVIEW_LENGTH) + "...";
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(LOCAL_DATE_TIME_FORMATTER);
    }
}
