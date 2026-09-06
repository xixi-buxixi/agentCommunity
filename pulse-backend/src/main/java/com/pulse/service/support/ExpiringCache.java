package com.pulse.service.support;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A very small in-process cache with a fixed TTL and a hard entry ceiling.
 *
 * Deliberately not a cache library and deliberately not Redis. It exists for exactly
 * one situation: the anonymous read paths (the agent leaderboard and the public
 * profile) fall back to MySQL whenever Redis cannot answer, and that fallback is a
 * multi-table aggregate. With Redis down, the rate limiter fails open at the same
 * moment, so the only two things bounding those endpoints are both gone at once and
 * every anonymous request becomes a database load generator. A few seconds of
 * process-local memoisation turns that back into a bounded cost, and needs nothing
 * that can itself be down.
 *
 * Properties worth stating:
 *
 * - Per process, not per cluster. Two instances may serve answers up to TTL apart,
 *   which is the same staleness the Redis board already has by construction.
 * - No background thread. Expiry is checked on read, and the ceiling is enforced on
 *   write, so an idle process holds at most what it last wrote.
 * - Values are shared with every subsequent reader, so callers must store immutable
 *   ones. Nothing here defensively copies.
 * - {@link System#nanoTime()} rather than wall clock: a clock adjustment must not be
 *   able to hold an entry for ever, or expire the whole cache at once.
 */
public final class ExpiringCache<K, V> {

    private record Entry<V>(V value, long expiresAtNanos) {
        boolean isExpired(long nowNanos) {
            return nowNanos - expiresAtNanos >= 0;
        }
    }

    private final ConcurrentHashMap<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final int maxSize;
    private final LongSupplier clock;

    public ExpiringCache(Duration ttl, int maxSize) {
        this(ttl, maxSize, System::nanoTime);
    }

    /**
     * @param clock nanosecond source; injectable so expiry can be tested without sleeping
     */
    public ExpiringCache(Duration ttl, int maxSize, LongSupplier clock) {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("ttl must be positive");
        }
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be at least 1");
        }
        this.ttlNanos = ttl.toNanos();
        this.maxSize = maxSize;
        this.clock = clock;
    }

    /**
     * @return the cached value, or null when absent or expired. An expired entry is
     *         removed on the way out, so a key that stops being asked for does not
     *         occupy the cache for ever.
     */
    public V get(K key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.getAsLong())) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    /**
     * Store a value for one TTL.
     *
     * The ceiling is enforced here rather than by an eviction policy: expired entries
     * are dropped first, and if that is not enough the cache is emptied. Losing the
     * whole cache costs one round of fallback queries; letting it grow without a bound
     * would be a leak on an endpoint anyone can call with any key.
     */
    public void put(K key, V value) {
        long now = clock.getAsLong();
        if (!entries.containsKey(key) && entries.size() >= maxSize) {
            entries.entrySet().removeIf(e -> e.getValue().isExpired(now));
            if (entries.size() >= maxSize) {
                entries.clear();
            }
        }
        entries.put(key, new Entry<>(value, now + ttlNanos));
    }

    /** Drop everything. Used by tests and by callers that know their data just changed. */
    public void clear() {
        entries.clear();
    }

    /** Entry count including any that have expired but not yet been read. */
    public int size() {
        return entries.size();
    }
}
