package ai.lingshu.a2a.client;

import lombok.Getter;

import javax.annotation.concurrent.ThreadSafe;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Story #009a — Contract A3 {@code lingshu.contract.agent-card-cache.v1}.
 *
 * TTL cache for {@code AgentCard} (Map view). Supports positive cache + negative cache + lazy eviction.
 *
 * <p>Thread-safety: all methods are safe for concurrent use (ConcurrentHashMap + AtomicLong counters).
 * Lazy eviction via {@link ConcurrentHashMap#remove(Object, Object)} (CAS-safe).</p>
 *
 * <p>Defaults (per data-model.md DM-05): {@code cacheTtl = passed-in arg}, {@code negativeCacheTtl = cacheTtl / 4}.</p>
 */
@ThreadSafe
public final class AgentCardCache {

    private final ConcurrentHashMap<String, CacheEntry> cache;
    private final Duration cacheTtl;
    private final Duration negativeCacheTtl;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong negatives = new AtomicLong();

    /**
     * @param cacheTtl positive cache TTL; must be non-null and positive (validated eagerly).
     */
    public AgentCardCache(Duration cacheTtl) {
        if (cacheTtl == null) {
            throw new IllegalArgumentException("cacheTtl must not be null");
        }
        if (cacheTtl.isZero() || cacheTtl.isNegative()) {
            throw new IllegalArgumentException("cacheTtl must be positive: " + cacheTtl);
        }
        this.cacheTtl = cacheTtl;
        this.negativeCacheTtl = cacheTtl.dividedBy(4);
        this.cache = new ConcurrentHashMap<>();
    }

    /**
     * Lookup card by agentName.
     *
     * @return cached card map; {@code null} if miss / negative / expired.
     */
    public Map<String, Object> get(String agentName) {
        validateAgentName(agentName);
        CacheEntry entry = cache.get(agentName);
        if (entry == null) {
            misses.incrementAndGet();
            return null;
        }
        Instant now = Instant.now();
        if (now.isAfter(entry.expireAt)) {
            // lazy eviction
            cache.remove(agentName, entry);
            misses.incrementAndGet();
            return null;
        }
        if (entry.value == null) {
            negatives.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return entry.value;
    }

    /**
     * Cache a positive card. Card must be non-null (use {@link #putNegative(String)} for negative caching).
     */
    public void put(String agentName, Map<String, Object> card) {
        validateAgentName(agentName);
        if (card == null) {
            throw new IllegalArgumentException("card must not be null; use putNegative() instead");
        }
        Instant expireAt = Instant.now().plus(cacheTtl);
        cache.put(agentName, new CacheEntry(card, expireAt));
    }

    /**
     * Cache a negative entry (agent not found). Uses {@code negativeCacheTtl = cacheTtl / 4}.
     */
    public void putNegative(String agentName) {
        validateAgentName(agentName);
        Instant expireAt = Instant.now().plus(negativeCacheTtl);
        cache.put(agentName, new CacheEntry(null, expireAt));
    }

    /**
     * Manually invalidate a card entry. Idempotent.
     */
    public void invalidate(String agentName) {
        if (agentName == null) return;
        cache.remove(agentName);
    }

    /**
     * Immutable snapshot of stats counters.
     */
    public Stats stats() {
        return new Stats(hits.get(), misses.get(), negatives.get());
    }

    private static void validateAgentName(String agentName) {
        if (agentName == null || agentName.trim().isEmpty()) {
            throw new IllegalArgumentException("agentName must not be null/empty");
        }
    }

    /** Cache entry: value == null indicates negative entry. */
    private static final class CacheEntry {
        final Map<String, Object> value;
        final Instant expireAt;

        CacheEntry(Map<String, Object> value, Instant expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    /**
     * Immutable stats snapshot.
     */
    @Getter
    public static final class Stats {
        private final long hits;
        private final long misses;
        private final long negatives;

        Stats(long hits, long misses, long negatives) {
            this.hits = hits;
            this.misses = misses;
            this.negatives = negatives;
        }

        /**
         * Hit ratio = hits / (hits + misses + negatives). Returns 0.0 if total is zero (avoids div-by-zero).
         */
        public double hitRatio() {
            long total = hits + misses + negatives;
            if (total == 0) return 0.0;
            return (double) hits / (double) total;
        }
    }

    // Exposed for tests
    Duration getCacheTtl() { return cacheTtl; }
    Duration getNegativeCacheTtl() { return negativeCacheTtl; }
    int size() { return cache.size(); }
}
