package com.pulse.service.impl;

import com.pulse.dto.AgentRankingScore;
import com.pulse.dto.response.AgentRankingItemResponse;
import com.pulse.entity.Agent;
import com.pulse.entity.User;
import com.pulse.enums.AgentRankingType;
import com.pulse.enums.AgentStatus;
import com.pulse.exception.BusinessException;
import com.pulse.exception.ErrorCode;
import com.pulse.mapper.AgentMapper;
import com.pulse.mapper.AgentRankingMapper;
import com.pulse.mapper.UserMapper;
import com.pulse.service.AgentRankingService;
import com.pulse.service.support.ExpiringCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Ranking Service Implementation
 *
 * Redis key design (same prefix family as the post boards in RankingServiceImpl):
 * - pulse:rank:agent:replied - Sorted Set, score = replies received in 7 days
 * - pulse:rank:agent:tipped  - Sorted Set, score = tips received in 30 days
 * - pulse:rank:agent:active  - Sorted Set, score = posts + comments in 7 days
 *
 * Read path: Redis first, MySQL aggregate on a miss or on any Redis failure. A miss
 * also triggers a cache rebuild, so the fallback is a one-off cost rather than the
 * steady state.
 *
 * Two decisions worth stating:
 *
 * 1. The cache always holds CACHE_SIZE (= the maximum allowed limit) entries, not the
 *    limit that happened to be asked for first. Caching a 10-row board would make
 *    every limit=50 request fall through to MySQL and then overwrite the cache with
 *    another short board - a cache that guarantees its own misses.
 *
 * 2. Agent display data is never read per row. The board gives ids; one selectBatchIds
 *    on agents and one on users resolves the whole page, which is what keeps a 50-row
 *    board at 3 queries instead of 101.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRankingServiceImpl implements AgentRankingService {

    private final StringRedisTemplate redisTemplate;
    private final AgentRankingMapper agentRankingMapper;
    private final AgentMapper agentMapper;
    private final UserMapper userMapper;

    /** Same prefix as the post boards, one more segment for the dimension. */
    private static final String REDIS_KEY_PREFIX = "pulse:rank:agent:";

    /** Upper bound on the requested limit. */
    private static final int MAX_LIMIT = 50;

    /** How many entries each cached board holds - see the class comment. */
    private static final int CACHE_SIZE = MAX_LIMIT;

    /**
     * Suffix of the "refreshed, and the window really is empty" marker key.
     *
     * A Sorted Set cannot hold "no members" and still be distinguishable from "never
     * built": an empty board and a missing board are the same absent key. Without a
     * marker, every anonymous request on a board whose window is empty (a fresh
     * deployment, or the tipped board on an installation that has never had a tip)
     * ran the aggregate twice - once for the answer, once for the rebuild that then
     * wrote nothing - and the next request did it again. The marker is what turns
     * "empty" into a cacheable answer.
     */
    private static final String EMPTY_MARKER_SUFFIX = ":empty";

    /**
     * How long an empty board stays cached.
     *
     * Shorter than the hourly {@code RankingRefreshScheduler} tick on purpose: the
     * first row to land on a board should show up within minutes, not at the top of
     * the next hour. The cost of being wrong is only that the board looks empty for
     * up to this long, which is what it already looked like a moment earlier.
     */
    private static final Duration EMPTY_MARKER_TTL = Duration.ofMinutes(5);

    /**
     * How long the MySQL fallback answer is memoised inside this process.
     *
     * The fallback exists for the minutes when Redis cannot answer - and those are
     * exactly the minutes when the IP limiter in front of this endpoint fails open,
     * because it is backed by the same Redis. Without a second bound, one Redis outage
     * turns an anonymous, cacheable board into two multi-table aggregates per request.
     * Sixty seconds is under the hourly refresh cadence the board already accepts, so
     * nobody sees an answer staler than the design already allows.
     *
     * Only the fallback is memoised. A Redis hit still answers from Redis, so the
     * normal path keeps its current freshness exactly.
     */
    private static final Duration FALLBACK_CACHE_TTL = Duration.ofSeconds(60);

    /**
     * Entry ceiling for that cache. The key is type + limit, and limit is clamped to
     * [1, MAX_LIMIT], so three boards times fifty limits is 150 possible keys - the
     * ceiling is slack, and is here so an unforeseen key shape cannot make this a leak.
     */
    private static final int FALLBACK_CACHE_MAX_ENTRIES = 1000;

    /** type:limit -> the rendered board. Values are immutable copies. */
    private final ExpiringCache<String, List<AgentRankingItemResponse>> fallbackCache =
            new ExpiringCache<>(FALLBACK_CACHE_TTL, FALLBACK_CACHE_MAX_ENTRIES);

    /** Label for a status code the enum does not know - see statusTextOf. */
    private static final String UNKNOWN_STATUS_TEXT = "UNKNOWN";

    @Override
    public List<AgentRankingItemResponse> getAgentRanking(String type, int limit) {
        AgentRankingType rankingType = requireType(type);
        int normalizedLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        // Ordered id -> score, highest first
        LinkedHashMap<Long, BigDecimal> scores = readFromRedis(rankingType, normalizedLimit);

        if (scores == null || scores.isEmpty()) {
            if (emptyMarkerPresent(rankingType)) {
                // The board was refreshed and the window genuinely had no rows. That is
                // an answer, not a miss - going to MySQL for it would mean running the
                // aggregate on every anonymous request for as long as the board is empty.
                log.debug("Agent ranking board is cached as empty: type={}", rankingType.getCode());
                return Collections.emptyList();
            }

            String fallbackKey = fallbackCacheKey(rankingType, normalizedLimit);
            List<AgentRankingItemResponse> memoised = fallbackCache.get(fallbackKey);
            if (memoised != null) {
                // Redis is still unable to answer, and so is the limiter in front of
                // this endpoint. Serving the last fallback answer keeps the aggregate
                // off the database once per minute instead of once per request.
                log.debug("Serving the agent ranking board from the in-process fallback cache: key={}",
                        fallbackKey);
                return memoised;
            }

            log.info("Agent ranking cache miss for type={}, falling back to MySQL", rankingType.getCode());
            scores = toScoreMap(rankingType, queryFromMySQL(rankingType, normalizedLimit));

            // Fire and forget: a failed rebuild must not fail the request that is
            // already holding a correct answer.
            try {
                refreshAgentRankingCache(rankingType.getCode());
            } catch (Exception e) {
                log.warn("Failed to refresh agent ranking cache for type={}", rankingType.getCode(), e);
            }

            List<AgentRankingItemResponse> responses = scores.isEmpty()
                    ? Collections.emptyList()
                    : List.copyOf(buildResponses(rankingType, scores));
            fallbackCache.put(fallbackKey, responses);
            return responses;
        }

        if (scores.isEmpty()) {
            return Collections.emptyList();
        }

        return buildResponses(rankingType, scores);
    }

    @Override
    public void refreshAgentRankingCache(String type) {
        AgentRankingType rankingType = requireType(type);
        String key = redisKey(rankingType);

        List<AgentRankingScore> rows = queryFromMySQL(rankingType, CACHE_SIZE);

        // Delete first and unconditionally: an empty window means the old board is
        // wrong, and leaving it in place would serve last week's leaders for ever.
        redisTemplate.delete(key);

        if (rows.isEmpty()) {
            // Record that the emptiness is a refreshed result rather than an unbuilt
            // cache, so the read path can serve it without touching MySQL.
            markEmpty(rankingType);
            log.info("Agent ranking refresh for type={} found no rows; cache cleared", rankingType.getCode());
            return;
        }

        // The board has rows again: drop the marker before writing them, so a reader
        // that arrives between the two statements sees an unbuilt cache (one fallback
        // query) rather than a stale "this board is empty" (a wrong answer).
        clearEmptyMarker(rankingType);

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] rawKey = redisTemplate.getStringSerializer().serialize(key);
            for (AgentRankingScore row : rows) {
                if (row.getAgentId() == null || row.getScore() == null) {
                    continue;
                }
                connection.zAdd(rawKey, row.getScore().doubleValue(),
                        redisTemplate.getStringSerializer().serialize(String.valueOf(row.getAgentId())));
            }
            return null;
        });

        log.info("Agent ranking cache refreshed for type={}, count={}",
                rankingType.getCode(), rows.size());
    }

    @Override
    public void refreshAllAgentRankingCaches() {
        log.info("Refreshing all agent ranking caches");
        for (AgentRankingType type : AgentRankingType.values()) {
            // Per board, not per sweep: a Redis write failure or a slow aggregate on
            // the first board used to cost the other two their refresh for this tick,
            // even though nothing about them had failed.
            try {
                refreshAgentRankingCache(type.getCode());
            } catch (Exception e) {
                log.error("Failed to refresh agent ranking cache for type={}", type.getCode(), e);
            }
        }
        log.info("All agent ranking caches refreshed");
    }

    // ========== Private helpers ==========

    private AgentRankingType requireType(String type) {
        AgentRankingType rankingType = AgentRankingType.fromCode(type);
        if (rankingType == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER);
        }
        return rankingType;
    }

    private String redisKey(AgentRankingType type) {
        return REDIS_KEY_PREFIX + type.getCode();
    }

    private String fallbackCacheKey(AgentRankingType type, int limit) {
        return type.getCode() + ":" + limit;
    }

    private String emptyMarkerKey(AgentRankingType type) {
        return redisKey(type) + EMPTY_MARKER_SUFFIX;
    }

    /**
     * Whether this board is known to be empty.
     *
     * A Redis failure answers "no": the fallback then costs one aggregate, which is
     * the behaviour a cache outage should have. Answering "yes" would serve an empty
     * board on the strength of a failed read.
     */
    private boolean emptyMarkerPresent(AgentRankingType type) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(emptyMarkerKey(type)));
        } catch (Exception e) {
            log.debug("Could not read the empty-board marker: type={}, {}",
                    type.getCode(), e.getMessage());
            return false;
        }
    }

    private void markEmpty(AgentRankingType type) {
        redisTemplate.opsForValue().set(emptyMarkerKey(type), "1", EMPTY_MARKER_TTL);
    }

    private void clearEmptyMarker(AgentRankingType type) {
        redisTemplate.delete(emptyMarkerKey(type));
    }

    /**
     * Top {@code limit} entries of the cached board.
     *
     * @return ordered id -> score, or null when Redis could not answer at all. Null
     *         and empty are handled identically by the caller, but the distinction
     *         keeps the log line honest.
     */
    private LinkedHashMap<Long, BigDecimal> readFromRedis(AgentRankingType type, int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples;
        try {
            tuples = redisTemplate.opsForZSet().reverseRangeWithScores(redisKey(type), 0, limit - 1);
        } catch (Exception e) {
            log.error("Failed to read agent ranking from Redis: type={}", type.getCode(), e);
            return null;
        }
        if (tuples == null || tuples.isEmpty()) {
            return null;
        }

        LinkedHashMap<Long, BigDecimal> scores = new LinkedHashMap<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String rawId = tuple.getValue();
            Double score = tuple.getScore();
            if (rawId == null || score == null) {
                continue;
            }
            try {
                scores.put(Long.parseLong(rawId), normalize(type, BigDecimal.valueOf(score)));
            } catch (NumberFormatException e) {
                log.warn("Invalid agentId in agent ranking cache: type={}, value={}", type.getCode(), rawId);
            }
        }
        return scores;
    }

    private List<AgentRankingScore> queryFromMySQL(AgentRankingType type, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(type.getWindowDays());
        List<AgentRankingScore> rows = switch (type) {
            case REPLIED -> agentRankingMapper.findTopByRepliesReceived(since, limit);
            case TIPPED -> agentRankingMapper.findTopByTipsReceived(since, limit);
            case ACTIVE -> agentRankingMapper.findTopByActivity(since, limit);
        };
        return rows == null ? Collections.emptyList() : rows;
    }

    private LinkedHashMap<Long, BigDecimal> toScoreMap(AgentRankingType type, List<AgentRankingScore> rows) {
        LinkedHashMap<Long, BigDecimal> scores = new LinkedHashMap<>();
        for (AgentRankingScore row : rows) {
            if (row.getAgentId() == null) {
                continue;
            }
            scores.put(row.getAgentId(), normalize(type, row.getScore()));
        }
        return scores;
    }

    /**
     * Render a score in the board's own unit, so the Redis path and the MySQL path
     * cannot disagree about "3" vs "3.0000000001".
     */
    private BigDecimal normalize(AgentRankingType type, BigDecimal score) {
        BigDecimal value = score == null ? BigDecimal.ZERO : score;
        return value.setScale(type.getScoreScale(), RoundingMode.HALF_UP);
    }

    /**
     * Attach display data to the ranked ids, preserving the ranking order.
     *
     * Ids that no longer resolve to an agent (deleted between the refresh and the
     * read) are dropped rather than rendered blank, and the rank numbers close up
     * behind them.
     */
    private List<AgentRankingItemResponse> buildResponses(AgentRankingType type,
                                                          Map<Long, BigDecimal> scores) {
        List<Long> agentIds = new ArrayList<>(scores.keySet());
        List<Agent> agents = agentMapper.selectBatchIds(agentIds);
        if (agents == null || agents.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Agent> agentsById = new HashMap<>();
        Set<Long> ownerIds = new HashSet<>();
        for (Agent agent : agents) {
            if (agent == null || agent.getId() == null) {
                continue;
            }
            agentsById.put(agent.getId(), agent);
            if (agent.getOwnerId() != null) {
                ownerIds.add(agent.getOwnerId());
            }
        }

        Map<Long, String> ownerNames = loadOwnerNames(ownerIds);

        List<AgentRankingItemResponse> responses = new ArrayList<>();
        int rank = 1;
        for (Long agentId : agentIds) {
            Agent agent = agentsById.get(agentId);
            if (agent == null) {
                log.debug("Ranked agent no longer resolvable: type={}, agentId={}", type.getCode(), agentId);
                continue;
            }
            responses.add(AgentRankingItemResponse.builder()
                    .rank(rank++)
                    .agentId(agent.getId())
                    .name(agent.getName())
                    .avatarUrl(agent.getAvatarUrl())
                    .status(agent.getStatus())
                    .statusText(statusTextOf(agent.getStatus()))
                    .ownerName(ownerNames.get(agent.getOwnerId()))
                    .score(scores.get(agentId))
                    .type(type.getCode())
                    .build());
        }
        return responses;
    }

    private Map<Long, String> loadOwnerNames(Set<Long> ownerIds) {
        Map<Long, String> names = new HashMap<>();
        if (ownerIds.isEmpty()) {
            return names;
        }
        List<User> owners = userMapper.selectBatchIds(ownerIds);
        if (owners == null) {
            return names;
        }
        for (User owner : owners) {
            if (owner != null && owner.getId() != null) {
                names.put(owner.getId(), owner.getUsername());
            }
        }
        return names;
    }

    /**
     * Status label, tolerating a code the enum does not know: a leaderboard must not
     * 500 because one row carries an unexpected status value.
     *
     * The same "UNKNOWN" label the public profile uses. Two anonymous endpoints
     * rendering the same field from the same rows must not disagree about what an
     * unknown status is called - one returning null and the other a label is a contract
     * split waiting to surface the moment a caller starts reading status_text.
     */
    private String statusTextOf(Integer status) {
        if (status == null) {
            return UNKNOWN_STATUS_TEXT;
        }
        try {
            return AgentStatus.fromCode(status).getText();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown agent status in ranking: {}", status);
            return UNKNOWN_STATUS_TEXT;
        }
    }
}
