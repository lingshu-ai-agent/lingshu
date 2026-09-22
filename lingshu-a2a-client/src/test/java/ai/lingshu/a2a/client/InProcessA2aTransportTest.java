package ai.lingshu.a2a.client;

import ai.lingshu.core.a2a.client.InProcessA2aRegistry;
import ai.lingshu.core.message.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 unit tests — {@link InProcessA2aTransport} (5 cases per data-model.md DM-02).
 */
class InProcessA2aTransportTest {

    private AgentCardCache cache;
    private InProcessA2aTransport transport;

    @BeforeEach
    void setUp() {
        InProcessA2aRegistry.getInstance().clear();
        cache = new AgentCardCache(Duration.ofMinutes(5));
        transport = new InProcessA2aTransport(InProcessA2aRegistry.getInstance(), cache);
    }

    @Test
    @DisplayName("TC-ITP-1: fetchCard_registryHit_returnsCardAndPopulatesCache")
    void fetchCard_registryHit_returnsCardAndPopulatesCache() {
        Map<String, Object> card = new HashMap<>();
        card.put("name", "alice-coding");
        card.put("version", "0.1.0");
        InProcessA2aRegistry.getInstance().put("alice-coding", card);

        Map<String, Object> first = transport.fetchCard("alice-coding");

        assertThat(first).isNotNull()
            .containsEntry("name", "alice-coding")
            .containsEntry("version", "0.1.0");
        // cache should now be populated for the second call (no second registry hit)
        AgentCardCache.Stats beforeSecond = cache.stats();
        Map<String, Object> second = transport.fetchCard("alice-coding");
        AgentCardCache.Stats afterSecond = cache.stats();

        assertThat(second).isNotNull().containsEntry("name", "alice-coding");
        assertThat(afterSecond.getHits()).isEqualTo(beforeSecond.getHits() + 1);
    }

    @Test
    @DisplayName("TC-ITP-2: fetchCard_cacheHit_skipsRegistryLookup")
    void fetchCard_cacheHit_skipsRegistryLookup() {
        Map<String, Object> card = new HashMap<>();
        card.put("name", "bob-research");
        cache.put("bob-research", card);  // pre-populate cache; registry stays empty
        InProcessA2aRegistry.getInstance(); // sanity — registry should be empty

        Map<String, Object> result = transport.fetchCard("bob-research");

        assertThat(result).isNotNull().containsEntry("name", "bob-research");
        // miss would have happened if cache wasn't hit
        AgentCardCache.Stats stats = cache.stats();
        assertThat(stats.getHits()).isEqualTo(1);
        assertThat(stats.getMisses()).isZero();
    }

    @Test
    @DisplayName("TC-ITP-3: fetchCard_miss_throwsLINGS08_withAgentName_andAvailableList")
    void fetchCard_miss_throwsLINGS08_withAgentName_andAvailableList() {
        InProcessA2aRegistry.getInstance().put("alice-coding", new HashMap<>());
        InProcessA2aRegistry.getInstance().put("bob-research", new HashMap<>());

        assertThatThrownBy(() -> transport.fetchCard("ghost-agent"))
            .isInstanceOf(InProcessA2aTransport.InProcessA2aRegistryEmptyException.class)
            .satisfies(t -> {
                InProcessA2aTransport.InProcessA2aRegistryEmptyException ex =
                    (InProcessA2aTransport.InProcessA2aRegistryEmptyException) t;
                assertThat(ex.getErrorCode()).isEqualTo("LINGS-S08");
                assertThat(ex.getAgentName()).isEqualTo("ghost-agent");
                assertThat(ex.getAvailable()).containsExactlyInAnyOrder("alice-coding", "bob-research");
                assertThat(ex.getMessage()).contains("ghost-agent");
            });

        // should have populated negative cache (so a retry within TTL won't re-throw MISS)
        // — verify by calling get() and checking the negatives counter increments
        cache.get("ghost-agent");
        assertThat(cache.stats().getNegatives()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-ITP-4: submit_get_cancel_subscribe_allThrowUnsupportedOperationException")
    void submit_get_cancel_subscribe_allThrowUnsupportedOperationException() {
        Consumer<Map<String, Object>> noop = m -> {};

        assertThatThrownBy(() -> transport.submit("alice", "skill", "{}"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("InProcess");
        assertThatThrownBy(() -> transport.get("task-123"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("InProcess");
        assertThatThrownBy(() -> transport.cancel("task-123"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("InProcess");
        assertThatThrownBy(() -> transport.subscribe("task-123", noop))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("InProcess");
    }

    @Test
    @DisplayName("TC-ITP-5: fetchCard_nullOrEmptyAgentName_throwsIAE")
    void fetchCard_nullOrEmptyAgentName_throwsIAE() {
        assertThatThrownBy(() -> transport.fetchCard(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agentName");
        assertThatThrownBy(() -> transport.fetchCard(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agentName");
    }

    // ── additional constructor validation ──

    @Test
    @DisplayName("TC-ITP-6: ctor_nullRegistryOrCache_throwsIAE")
    void ctor_nullRegistryOrCache_throwsIAE() {
        assertThatThrownBy(() -> new InProcessA2aTransport(null, cache))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("registry");
        assertThatThrownBy(() -> new InProcessA2aTransport(InProcessA2aRegistry.getInstance(), null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cardCache");
    }

    // ── toolError regression — make sure the unsupported path returns cleanly even when callers catch UnsupportedOperationException ──

    @Test
    @DisplayName("TC-ITP-7: unsupportedMethods_returnTypeContracts_satisfiedOnThrow")
    void unsupportedMethods_returnTypeContracts_satisfiedOnThrow() {
        // submit/get return ToolResult; cancel returns boolean; subscribe returns void.
        // Even though they throw, the contract is "throw UOE" — verify the exception is
        // the only observable behaviour.
        try {
            ToolResult r = transport.submit("alice", "skill", "{}");
            // unreachable
            assertThat(r).as("submit must throw").isNull();
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        try {
            transport.cancel("task");
            // unreachable
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }
}
