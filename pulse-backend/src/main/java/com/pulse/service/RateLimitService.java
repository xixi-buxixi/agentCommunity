package com.pulse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed fixed-window rate limiter.
 *
 * Used to put a ceiling on endpoints that were previously unbounded: login and
 * register (credential stuffing), the Hermes ingest token (enumerable, and a
 * successful guess rewrites the front page), and tipping (ledger spam).
 *
 * Fails OPEN when Redis is unavailable: a cache outage must not lock users out of
 * logging in. That is logged at warn level so the gap is visible.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private static final String KEY_PREFIX = "pulse:ratelimit:";

    private final StringRedisTemplate redisTemplate;

    /**
     * Count one hit against a bucket.
     *
     * @param bucket logical bucket name, e.g. "login:ip"
     * @param identity caller identity within the bucket (IP, user id, username)
     * @param limit maximum hits allowed inside the window
     * @param window window length
     * @return true when the call is allowed, false when the limit is exhausted
     */
    public boolean tryConsume(String bucket, String identity, int limit, Duration window) {
        String key = KEY_PREFIX + bucket + ":" + identity;
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                return true;
            }
            if (count == 1L) {
                // First hit in this window starts the clock
                redisTemplate.expire(key, window);
            }
            if (count > limit) {
                log.warn("Rate limit exceeded: bucket={}, identity={}, count={}, limit={}",
                        bucket, identity, count, limit);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Rate limiter unavailable, allowing request: bucket={}, error={}",
                    bucket, e.getMessage());
            return true;
        }
    }

    /**
     * Seconds until the current window for this bucket expires, for Retry-After.
     */
    public long retryAfterSeconds(String bucket, String identity, Duration window) {
        String key = KEY_PREFIX + bucket + ":" + identity;
        try {
            Long ttl = redisTemplate.getExpire(key);
            if (ttl != null && ttl > 0) {
                return ttl;
            }
        } catch (Exception e) {
            log.debug("Unable to read rate limit TTL: {}", e.getMessage());
        }
        return window.getSeconds();
    }

    /**
     * Drop a bucket, e.g. after a successful login clears the failure counter.
     */
    public void reset(String bucket, String identity) {
        try {
            redisTemplate.delete(KEY_PREFIX + bucket + ":" + identity);
        } catch (Exception e) {
            log.debug("Unable to reset rate limit bucket: {}", e.getMessage());
        }
    }
}
