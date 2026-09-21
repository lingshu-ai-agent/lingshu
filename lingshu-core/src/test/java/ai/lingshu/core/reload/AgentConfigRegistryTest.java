package ai.lingshu.core.reload;

import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #007 — AC-06 L1 unit tests for {@link AgentConfigRegistry}.
 *
 * <p>US1 (P1) — AtomicReference swap single source of truth. US5 (P2) —
 * listener exception isolation.
 *
 * <p>Coverage (7 cases total):
 * <ul>
 *   <li>{@code publish_initial_returnsConfig_viaCurrent}</li>
 *   <li>{@code publish_next_replacesCurrent_immediately}</li>
 *   <li>{@code concurrent_publishes_allObserversSeeTheLastPublished}</li>
 *   <li>{@code publish_notifiesListenerWithCorrectPrevAndNext}</li>
 *   <li>{@code listenerThrows_publishContinuesAndSubsequentListenersStillCalled}</li>
 *   <li>{@code listenerThrows_publishFailedListenerCannotRollbackNewConfig}</li>
 *   <li>{@code addListener_removeListener_reflectedInCount}</li>
 * </ul>
 */
class AgentConfigRegistryTest {

    private AgentConfigRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentConfigRegistry();
    }

    // ── US1 (P1) — AtomicReference swap single source of truth ──────────

    @Test
    @DisplayName("US1.1 — publish initial → current returns it")
    void publish_initial_returnsConfig_viaCurrent() {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        registry.publishInitial(cfg1);
        assertThat(registry.current()).isSameAs(cfg1);
    }

    @Test
    @DisplayName("US1.2 — publish replaces current")
    void publish_next_replacesCurrent_immediately() {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        AgentConfig cfg2 = TestAgentConfigs.extended();
        registry.publishInitial(cfg1);
        assertThat(registry.current()).isSameAs(cfg1);
        registry.publish(cfg2);
        assertThat(registry.current()).isSameAs(cfg2);
    }

    @Test
    @DisplayName("US1.3 — 100 concurrent readers all observe the last published config (no half-state)")
    void concurrent_publishes_allObserversSeeTheLastPublished() throws Exception {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        AgentConfig cfg2 = TestAgentConfigs.extended();
        registry.publishInitial(cfg1);

        int readers = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(readers);
        AtomicInteger mismatchCount = new AtomicInteger();
        AtomicReference<AgentConfig> lastSeenByReader = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(readers);
        try {
            for (int i = 0; i < readers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int k = 0; k < 1000; k++) {
                            AgentConfig seen = registry.current();
                            // Every observation must be either cfg1 or cfg2 — never a
                            // half-constructed object, never null (post-publishInitial).
                            if (seen != cfg1 && seen != cfg2) {
                                mismatchCount.incrementAndGet();
                            }
                            lastSeenByReader.set(seen);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            registry.publish(cfg2);  // single swap mid-flight
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(mismatchCount.get()).isZero();
            assertThat(lastSeenByReader.get()).isSameAs(cfg2);
        } finally {
            pool.shutdownNow();
        }
    }

    // ── US5 (P2) — listener contract ─────────────────────────────────────

    @Test
    @DisplayName("US5.1 — listener notified with (prev, next) on each publish")
    void publish_notifiesListenerWithCorrectPrevAndNext() {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        AgentConfig cfg2 = TestAgentConfigs.extended();
        registry.publishInitial(cfg1);

        List<String> events = new ArrayList<>();
        ConfigChangeListener listener = (prev, next) -> events.add(prev + "->" + next);
        registry.addListener(listener);

        registry.publish(cfg2);
        registry.publish(cfg1);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).contains("->" + cfg2);
        assertThat(events.get(1)).contains(cfg2 + "->" + cfg1);
    }

    @Test
    @DisplayName("US5.2 — listener exception does NOT block publish main flow")
    void listenerThrows_publishContinuesAndSubsequentListenersStillCalled() {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        AgentConfig cfg2 = TestAgentConfigs.extended();
        registry.publishInitial(cfg1);

        AtomicInteger okCalls = new AtomicInteger();
        ConfigChangeListener throwing = new ConfigChangeListener() {
            @Override public void onConfigChange(AgentConfig previous, AgentConfig next) {
                throw new RuntimeException("boom");
            }
        };
        ConfigChangeListener ok = (prev, next) -> okCalls.incrementAndGet();

        registry.addListener(throwing);
        registry.addListener(ok);

        registry.publish(cfg2);

        // Ok listener must still be called (registration-order iteration)
        assertThat(okCalls.get()).isEqualTo(1);
        // And current() reflects the new config
        assertThat(registry.current()).isSameAs(cfg2);
    }

    @Test
    @DisplayName("US5.3 — listener exception cannot roll back the swap")
    void listenerThrows_publishFailedListenerCannotRollbackNewConfig() {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        AgentConfig cfg2 = TestAgentConfigs.extended();
        registry.publishInitial(cfg1);

        ConfigChangeListener throwing = (prev, next) -> {
            throw new IllegalStateException("nope");
        };
        registry.addListener(throwing);

        registry.publish(cfg2);

        // The AtomicReference swap is irreversible even if listeners call fail
        assertThat(registry.current()).isSameAs(cfg2);
    }

    // ── Registry primitive helpers ───────────────────────────────────────

    @Test
    @DisplayName("addListener / removeListener reflected in listenerCount()")
    void addListener_removeListener_reflectedInCount() {
        ConfigChangeListener l1 = (a, b) -> { };
        ConfigChangeListener l2 = (a, b) -> { };
        registry.addListener(l1);
        registry.addListener(l2);
        assertThat(registry.listenerCount()).isEqualTo(2);
        assertThat(registry.removeListener(l1)).isTrue();
        assertThat(registry.listenerCount()).isEqualTo(1);
        assertThat(registry.removeListener(l1)).isFalse();  // idempotent
    }

    // ── Defensive API contract ───────────────────────────────────────────

    @Test
    @DisplayName("publish(null) / publishInitial(null) → NullPointerException")
    void publishNull_throws() {
        assertThatThrownBy(() -> registry.publish(null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> registry.publishInitial(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("addListener(null) → NullPointerException")
    void addListenerNull_throws() {
        assertThatThrownBy(() -> registry.addListener(null))
            .isInstanceOf(NullPointerException.class);
    }
}