package ai.lingshu.a2a.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 unit tests — {@link AgentCardCache} (Contract A3, 6 cases per data-model.md DM-05).
 */
class AgentCardCacheTest {

    @Test
    @DisplayName("TC-CACHE-1: put_thenGet_returnsCard (positive hit)")
    void put_thenGet_returnsCard() {
        AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
        Map<String, Object> card = new HashMap<>();
        card.put("name", "alice");
        cache.put("alice", card);

        Map<String, Object> result = cache.get("alice");
        assertThat(result).isNotNull().containsEntry("name", "alice");
        assertThat(cache.stats().getHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-CACHE-2: get_unknownKey_returnsNull (miss)")
    void get_unknownKey_returnsNull() {
        AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));

        assertThat(cache.get("unknown")).isNull();
        assertThat(cache.stats().getMisses()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-CACHE-3: putNegative_thenGet_returnsNull (negative hit)")
    void putNegative_thenGet_returnsNull() {
        AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
        cache.putNegative("missing-agent");

        assertThat(cache.get("missing-agent")).isNull();
        assertThat(cache.stats().getNegatives()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-CACHE-4: entryExpires_returnsNull (lazy eviction)")
    void entryExpires_returnsNull() throws InterruptedException {
        // Use a 100ms TTL to test expiry quickly
        AgentCardCache cache = new AgentCardCache(Duration.ofMillis(100));
        Map<String, Object> card = new HashMap<>();
        card.put("name", "alice");
        cache.put("alice", card);

        assertThat(cache.get("alice")).isNotNull();  // immediate hit
        Thread.sleep(200);  // wait past TTL
        assertThat(cache.get("alice")).isNull();    // expired → null + miss
        assertThat(cache.stats().getMisses()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(0);  // lazy eviction removed entry
    }

    @Test
    @DisplayName("TC-CACHE-5: negativeCacheExpires_fallsThrough")
    void negativeCacheExpires_fallsThrough() throws InterruptedException {
        // negative TTL = cacheTtl / 4 → 25ms for 100ms cacheTtl
        AgentCardCache cache = new AgentCardCache(Duration.ofMillis(100));
        cache.putNegative("ghost");

        assertThat(cache.get("ghost")).isNull();
        assertThat(cache.stats().getNegatives()).isEqualTo(1);
        Thread.sleep(150);  // past negative TTL (25ms)
        assertThat(cache.get("ghost")).isNull();
        assertThat(cache.stats().getMisses()).isEqualTo(1);  // expired → miss not negative
    }

    @Test
    @DisplayName("TC-CACHE-6: stats_hitRatio_calculatesCorrectly (1/3 = 0.333)")
    void stats_hitRatio_calculatesCorrectly() {
        AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
        Map<String, Object> card = new HashMap<>();
        card.put("name", "alice");
        cache.put("alice", card);
        cache.putNegative("bob");

        cache.get("alice");   // hit → 1
        cache.get("alice");   // hit → 2
        cache.get("bob");     // negative → 1
        cache.get("charlie"); // miss → 1

        AgentCardCache.Stats stats = cache.stats();
        assertThat(stats.getHits()).isEqualTo(2);
        assertThat(stats.getMisses()).isEqualTo(1);
        assertThat(stats.getNegatives()).isEqualTo(1);
        assertThat(stats.hitRatio()).isCloseTo(2.0 / 4.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // --- constructor validation ---

    @Test
    @DisplayName("TC-CACHE-7: ctor_nullTtl_throwsIAE")
    void ctor_nullTtl_throwsIAE() {
        assertThatThrownBy(() -> new AgentCardCache(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cacheTtl");
    }

    @Test
    @DisplayName("TC-CACHE-8: ctor_zeroTtl_throwsIAE")
    void ctor_zeroTtl_throwsIAE() {
        assertThatThrownBy(() -> new AgentCardCache(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-CACHE-9: put_nullCard_throwsIAE")
    void put_nullCard_throwsIAE() {
        AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
        assertThatThrownBy(() -> cache.put("alice", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-CACHE-10: put_nullAgentName_throwsIAE")
    void put_nullAgentName_throwsIAE() {
        AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
        assertThatThrownBy(() -> cache.put(null, new HashMap<>()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
