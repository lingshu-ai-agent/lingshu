package ai.lingshu.core.reload;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.runtime.DefaultAgent;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #007 — AC-06 L1 unit test for {@link DefaultAgent} freeze semantics (US3 P1).
 *
 * <p>Contract: when constructed with a non-null {@link AgentConfigRegistry},
 * {@code DefaultAgent} reads {@code registry.current()} once at the entry of every
 * {@code run} / {@code runBlocking} call and freezes that snapshot into the
 * {@link TurnContext} for the duration of the turn. dsh §14.8 R-03 mitigation:
 * <ol>
 *   <li>AtomicReference swap in the registry</li>
 *   <li>Java reference freeze inside the Agent (this test covers the 2nd leg)</li>
 *   <li>validateOrThrow rollback on YAML parse error (covered in YamlWatcherTest)</li>
 * </ol>
 *
 * <p>Two scenarios:
 * <ul>
 *   <li>{@code frozenConfig_acrossTwoTurns_picksUpRegistryChange} —
 *       T1 freezes cfg1, registry publishes cfg2, T2 freezes cfg2 (sequence proves
 *       the freeze happens AFTER registry read each turn)</li>
 *   <li>{@code inFlightTurn_isUnaffectedByConcurrentPublish} —
 *       turn is blocked inside {@code engine.runTurn} with cfg1 already frozen;
 *       a concurrent {@code registry.publish(cfg2)} MUST NOT change the in-flight
 *       TurnContext's config (proves the snapshot is genuinely immutable)</li>
 * </ul>
 */
class InFlightFreezeTest {

    private AgentConfigRegistry registry;
    private DefaultSession session;

    @BeforeEach
    void setUp() {
        registry = new AgentConfigRegistry();
        session = new DefaultSession();
    }

    // ── US3.1 — freeze semantics across sequential turns ─────────────────

    @Test
    @DisplayName("US3.1 — T1 freezes cfg1, after publish T2 freezes cfg2 (registry propagates per turn)")
    void frozenConfig_acrossTwoTurns_picksUpRegistryChange() {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        AgentConfig cfg2 = TestAgentConfigs.extended();
        registry.publishInitial(cfg1);

        CapturingEngine engine = new CapturingEngine();
        DefaultAgent agent = new DefaultAgent(session, cfg1, engine, registry);

        // T1: frozen = cfg1 (registry.current() at entry)
        agent.runBlocking("hello-1");
        assertThat(engine.captured).hasSize(1);
        assertThat(engine.captured.get(0).config()).isSameAs(cfg1);

        // Hot-reload happens between turns
        registry.publish(cfg2);

        // T2: frozen = cfg2 (registry.current() at entry — now the new value)
        agent.runBlocking("hello-2");
        assertThat(engine.captured).hasSize(2);
        assertThat(engine.captured.get(1).config()).isSameAs(cfg2);

        // Crucially, the first turn's captured ctx STILL holds cfg1 — Java
        // reference freeze + @Value immutability means even though the registry
        // has moved on, T1's snapshot is untouched.
        assertThat(engine.captured.get(0).config()).isSameAs(cfg1);
    }

    // ── US3.2 — in-flight turn is unaffected by concurrent publish ────────

    @Test
    @DisplayName("US3.2 — turn in-flight is unaffected by concurrent registry.publish")
    void inFlightTurn_isUnaffectedByConcurrentPublish() throws Exception {
        AgentConfig cfg1 = TestAgentConfigs.baseline();
        AgentConfig cfg2 = TestAgentConfigs.extended();
        registry.publishInitial(cfg1);

        // Blocking engine: runTurn parks on a latch; we control when it returns.
        // This lets us publish cfg2 from the main thread while runTurn is mid-flight.
        BlockingEngine engine = new BlockingEngine();
        DefaultAgent agent = new DefaultAgent(session, cfg1, engine, registry);

        // Background thread runs the turn. It blocks inside engine.runTurn.
        Thread t = new Thread(() -> agent.runBlocking("long-task"));
        t.start();

        // Wait until runTurn has actually been entered with the frozen ctx.
        assertThat(engine.entered.await(2, TimeUnit.SECONDS)).isTrue();

        // Concurrent publish: simulates a hot-reload while T1 is mid-turn.
        registry.publish(cfg2);

        // Inspect the TurnContext that T1 captured at entry — it MUST still be cfg1.
        AgentConfig frozenInFlight = engine.capturedCtx.get().config();
        assertThat(frozenInFlight).isSameAs(cfg1);

        // Now let the engine complete the turn.
        engine.release.countDown();
        t.join(2_000);
        assertThat(t.isAlive()).isFalse();

        // Sanity check: a fresh turn after the reload picks up cfg2.
        BlockingEngine engine2 = new BlockingEngine();
        DefaultAgent agent2 = new DefaultAgent(session, cfg1, engine2, registry);
        Thread t2 = new Thread(() -> agent2.runBlocking("next"));
        t2.start();
        assertThat(engine2.entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(engine2.capturedCtx.get().config()).isSameAs(cfg2);
        engine2.release.countDown();
        t2.join(2_000);
    }

    // ── Test stubs ───────────────────────────────────────────────────────

    /** Records every {@link TurnContext} passed to {@link #runTurn}. */
    private static final class CapturingEngine implements FlowEngine {
        final List<TurnContext> captured = new ArrayList<>();

        @Override
        public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
            captured.add(ctx);
            // Synchronous completion: emit TurnCompleted and complete.
            sink.onSubscribe(new Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            sink.onNext(new AgentEvent.TurnCompleted(StopReason.END_TURN, Usage.zero()));
            sink.onComplete();
        }
    }

    /** Blocks inside runTurn until {@link #release} is counted down — used to
     *  simulate a long-running turn so the test can publish cfg2 mid-flight. */
    private static final class BlockingEngine implements FlowEngine {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<TurnContext> capturedCtx = new AtomicReference<>();

        @Override
        public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
            capturedCtx.set(ctx);
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            sink.onSubscribe(new Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            sink.onNext(new AgentEvent.TurnCompleted(StopReason.END_TURN, Usage.zero()));
            sink.onComplete();
        }
    }
}
