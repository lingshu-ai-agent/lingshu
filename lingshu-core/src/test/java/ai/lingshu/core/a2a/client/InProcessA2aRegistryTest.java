package ai.lingshu.core.a2a.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 unit tests — {@link InProcessA2aRegistry} (Contract C4, 6 cases per data-model.md DM-01).
 *
 * <p>Each test calls {@link InProcessA2aRegistry#getInstance()#clear()} in
 * {@code @BeforeEach} so test order is irrelevant (see class Javadoc —
 * {@code clear()} is a test-only helper).</p>
 */
class InProcessA2aRegistryTest {

    @BeforeEach
    void cleanRegistry() {
        InProcessA2aRegistry.getInstance().clear();
    }

    @Test
    @DisplayName("TC-REG-1: put_thenGet_returnsImmutableDefensiveCopy")
    void put_thenGet_returnsImmutableDefensiveCopy() {
        Map<String, Object> card = new HashMap<>();
        card.put("name", "alice-coding");
        card.put("version", "0.1.0");

        InProcessA2aRegistry.getInstance().put("alice-coding", card);
        Map<String, Object> result = InProcessA2aRegistry.getInstance().get("alice-coding");

        assertThat(result).isNotNull();
        assertThat(result).containsEntry("name", "alice-coding").containsEntry("version", "0.1.0");
        // defensive copy — mutating the returned Map must not pollute the registry
        assertThatThrownBy(() -> result.put("name", "mutated"))
            .isInstanceOf(UnsupportedOperationException.class);

        // registry still has the original value
        assertThat(InProcessA2aRegistry.getInstance().get("alice-coding"))
            .containsEntry("name", "alice-coding");
    }

    @Test
    @DisplayName("TC-REG-2: get_unknownAgent_returnsNull")
    void get_unknownAgent_returnsNull() {
        assertThat(InProcessA2aRegistry.getInstance().get("ghost")).isNull();
    }

    @Test
    @DisplayName("TC-REG-3: put_nullArgs_throwsIAEorNPE")
    void put_nullArgs_throwsIAEorNPE() {
        InProcessA2aRegistry reg = InProcessA2aRegistry.getInstance();
        // Objects.requireNonNull throws NPE; explicit checks throw IAE — both are
        // valid signals for null args. The contract is "must not silently accept null".
        assertThatThrownBy(() -> reg.put(null, new HashMap<>()))
            .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class)
            .hasMessageContaining("agentName");
        assertThatThrownBy(() -> reg.put("alice", null))
            .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class)
            .hasMessageContaining("card");
    }

    @Test
    @DisplayName("TC-REG-4: remove_existingAgent_returnsTrue_andClearsEntry")
    void remove_existingAgent_returnsTrue_andClearsEntry() {
        InProcessA2aRegistry reg = InProcessA2aRegistry.getInstance();
        reg.put("bob", new HashMap<>());
        assertThat(reg.contains("bob")).isTrue();

        boolean removed = reg.remove("bob");

        assertThat(removed).isTrue();
        assertThat(reg.contains("bob")).isFalse();
        assertThat(reg.get("bob")).isNull();
    }

    @Test
    @DisplayName("TC-REG-5: names_returnsImmutableView_withAllRegisteredAgents")
    void names_returnsImmutableView_withAllRegisteredAgents() {
        InProcessA2aRegistry reg = InProcessA2aRegistry.getInstance();
        reg.put("alice", new HashMap<>());
        reg.put("bob", new HashMap<>());

        Set<String> names = reg.names();

        assertThat(names).containsExactlyInAnyOrder("alice", "bob");
        assertThatThrownBy(() -> names.add("charlie"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(reg.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("TC-REG-6: concurrentPut10000_threads_noDataLoss")
    void concurrentPut10000_threads_noDataLoss() throws Exception {
        InProcessA2aRegistry reg = InProcessA2aRegistry.getInstance();
        int threadCount = 16;
        int putsPerThread = 625;  // 16 * 625 = 10,000 total puts
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < putsPerThread; i++) {
                        String key = "agent-" + threadId + "-" + i;
                        Map<String, Object> card = new HashMap<>();
                        card.put("name", key);
                        reg.put(key, card);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        boolean terminated = pool.awaitTermination(30, TimeUnit.SECONDS);

        assertThat(terminated).isTrue();
        assertThat(errors.get()).isZero();
        assertThat(reg.size()).isEqualTo(threadCount * putsPerThread);

        // spot-check: each thread's first key should be present with its data
        Map<String, Object> sample = reg.get("agent-7-0");
        assertThat(sample).isNotNull().containsEntry("name", "agent-7-0");
    }

    // ── additional sanity checks (bonus coverage, not counted in the 6 contract cases) ──

    @Test
    @DisplayName("TC-REG-7: getInstance_isSingleton")
    void getInstance_isSingleton() {
        InProcessA2aRegistry r1 = InProcessA2aRegistry.getInstance();
        InProcessA2aRegistry r2 = InProcessA2aRegistry.getInstance();
        assertThat(r1).isSameAs(r2);
    }

    @Test
    @DisplayName("TC-REG-8: put_preservesInsertionOrder_viaLinkedHashMap")
    void put_preservesInsertionOrder_viaLinkedHashMap() {
        InProcessA2aRegistry reg = InProcessA2aRegistry.getInstance();
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("name", "alice");
        ordered.put("description", "test");
        ordered.put("version", "0.1.0");
        reg.put("alice", ordered);

        Map<String, Object> result = reg.get("alice");
        assertThat(result).isNotNull();
        // entries should preserve insertion order
        assertThat(result.keySet().iterator().next()).isEqualTo("name");
        assertThat(Collections.unmodifiableSet(result.keySet()))
            .containsExactly("name", "description", "version");
    }
}
