/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #049 — L1/L2 slice test for the post-Story-#049
 * {@code DefaultAgent.run()} hot lazy {@code LiveRunPublisher}.
 *
 * <p><b>Verification strategy</b> — the prod class wires the engine via constructor
 * argument (Story #001 / #007 / #028 fields) and constructs the publisher internally;
 * the {@code DefaultAgent(Session, AgentConfig, FlowEngine)} 3-arg ctor lets tests
 * inject a hand-rolled {@link FlowEngine} mock that records:
 * <ul>
 *   <li>{@code invocations} — number of times {@code runTurn} was called (AC-049-01 lazy)</li>
 *   <li>{@code recordedThread} — the thread that called {@code runTurn} (AC-049-02 executor offload)</li>
 *   <li>controllable event emission via a held subscriber reference (AC-049-03 no-buffer</AC-049-04 single-subscriber,
 *       AC-049-05 cancellation, AC-049-06 back-pressure, AC-049-07 error propagation)</li>
 * </ul>
 *
 * <p><b>Mirrors precedent</b> — Story #001's {@code BufferedPublisher} tests
 * (in this same package) lock the sync path; this file locks the reactive path side-by-side.
 * Together they form a pair where Story #001+BufferedPublisher represents the
 * pre-Story-#049 drained-and-replayed path and Story #049+LiveRunPublisher
 * represents the hot lazy streaming path.
 *
 * <p><b>AC mapping</b>
 * <ul>
 *   <li>AC-049-01 → {@link #run_returnsHotLazyPublisher_noEngineInvocationUntilSubscribe}</li>
 *   <li>AC-049-02 → {@link #subscribe_submittedToExecutor_mainThreadNotBlocked}</li>
 *   <li>AC-049-03 → {@link #onNext_emittedAsEngineProduces_noBuffering}</li>
 *   <li>AC-049-04 → {@link #secondSubscribe_throwsIllegalStateException}</li>
 *   <li>AC-049-05 → {@link #subscriptionCancel_propagatesBridgeStopsDeliveringEvents}</li>
 *   <li>AC-049-06 → {@link #backpressure_zeroRequest_emissionBlocksUntilRequested}</li>
 *   <li>AC-049-07 → {@link #engineEmitsError_propagatesToSubscriberOnError}</li>
 * </ul>
 */
class DefaultAgentLiveStreamingTest {

    /**
     * Hand-rolled {@link FlowEngine} mock. Records the {@link Subscriber} the engine
     * was driven with so the test can push events through it at controlled timing.
     */
    private static final class RecordingEngine implements FlowEngine {
        final AtomicInteger invocations = new AtomicInteger(0);
        final AtomicReference<Thread> recordedThread = new AtomicReference<>();
        final AtomicReference<Subscriber<? super AgentEvent>> heldSubscriber = new AtomicReference<>();
        /** When non-null, {@link #runTurn} will throw this (after first onNext to register). */
        RuntimeException throwOnRun;
        /** When {@code true}, {@link #runTurn} emits N TextDelta events with 50ms sleeps. */
        int emitCount = 0;
        /** Optional latch released when runTurn enters — tests can wait for it. */
        final CountDownLatch entered = new CountDownLatch(1);

        @Override
        public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
            invocations.incrementAndGet();
            recordedThread.set(Thread.currentThread());
            heldSubscriber.set(sink);
            entered.countDown();

            // If configured to emit N events, drive the sink synchronously here.
            // NOTE: do not invoke sink.onSubscribe — the LiveRunPublisher bridge owns that.
            if (emitCount > 0) {
                try {
                    for (int i = 0; i < emitCount; i++) {
                        sink.onNext(new AgentEvent.TextDelta("delta-" + i));
                        // Sleep between emits so AC-049-03 can verify streaming vs buffering.
                        Thread.sleep(50);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (throwOnRun != null) {
                throw throwOnRun;
            }
        }
    }

    /**
     * Minimal {@link Subscriber} that records signals into thread-safe lists.
     * Mirrors Story #001's {@code DefaultAgentTest.RecordingSubscriber} precedent
     * but simplified to track onNext count, onError reference, and onComplete flag.
     */
    private static final class RecordingSubscriber implements Subscriber<AgentEvent> {
        final List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());
        final AtomicReference<Throwable> errorRef = new AtomicReference<>();
        final AtomicBoolean complete = new AtomicBoolean(false);
        final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();
        final AtomicLong firstEventNanos = new AtomicLong(-1);
        final List<Long> eventTimestampsNanos = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onSubscribe(Subscription s) {
            subscriptionRef.set(s);
        }

        @Override
        public void onNext(AgentEvent e) {
            long now = System.nanoTime();
            eventTimestampsNanos.add(now);
            if (firstEventNanos.get() == -1) {
                firstEventNanos.set(now);
            }
            events.add(e);
        }

        @Override
        public void onError(Throwable t) {
            errorRef.set(t);
        }

        @Override
        public void onComplete() {
            complete.set(true);
        }
    }

    private static DefaultAgent buildAgent(FlowEngine engine) {
        return new DefaultAgent(new DefaultSession(), AgentConfigDefaults.defaults(), engine);
    }

    // ── AC-049-01 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-01: run_returnsHotLazyPublisher_noEngineInvocationUntilSubscribe")
    void run_returnsHotLazyPublisher_noEngineInvocationUntilSubscribe() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        DefaultAgent agent = buildAgent(engine);

        // Call run() — engine should NOT be invoked yet (lazy).
        Object publisher = agent.run("hi");
        assertThat(publisher).isNotNull();
        assertThat(engine.invocations.get())
            .as("run() must not invoke the engine before subscribe() — lazy hot publisher")
            .isZero();
        assertThat(engine.heldSubscriber.get())
            .as("run() must not even hold the sink before subscribe()")
            .isNull();

        // Subscribe — engine should now be invoked exactly once.
        RecordingSubscriber sub = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(sub);

        assertThat(engine.entered.await(2, TimeUnit.SECONDS))
            .as("engine.runTurn should start within 2s of subscribe()")
            .isTrue();
        assertThat(engine.invocations.get())
            .as("engine.runTurn should be invoked exactly once after subscribe()")
            .isEqualTo(1);
        assertThat(engine.heldSubscriber.get())
            .as("engine should have been driven with a Subscriber (the LiveRunPublisher bridge)")
            .isNotNull();

        // Cleanup: wait for executor to drain.
        engine.entered.await(1, TimeUnit.SECONDS);
    }

    // ── AC-049-02 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-02: subscribe_submittedToExecutor_mainThreadNotBlocked")
    void subscribe_submittedToExecutor_mainThreadNotBlocked() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        DefaultAgent agent = buildAgent(engine);

        Object publisher = agent.run("hi");
        RecordingSubscriber sub = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(sub);

        assertThat(engine.entered.await(2, TimeUnit.SECONDS))
            .as("engine.runTurn should start within 2s of subscribe()")
            .isTrue();

        Thread engineThread = engine.recordedThread.get();
        assertThat(engineThread)
            .as("engine must be invoked on a non-main thread (executor offload)")
            .isNotNull();
        assertThat(Thread.currentThread())
            .as("subscribe()'s caller thread should still be the JUnit main thread")
            .isNotSameAs(engineThread);
        assertThat(engineThread.isDaemon())
            .as("executor thread must be daemon — JVM exit should not be blocked")
            .isTrue();
        assertThat(engineThread.getName())
            .as("thread name should follow the lingshu-agent-live-N pattern")
            .startsWith("lingshu-agent-live-");
    }

    // ── AC-049-03 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-03: onNext_emittedAsEngineProduces_noBuffering")
    void onNext_emittedAsEngineProduces_noBuffering() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        engine.emitCount = 5;  // 5 TextDelta with 50ms sleeps between → ~200ms span
        DefaultAgent agent = buildAgent(engine);

        Object publisher = agent.run("hi");
        RecordingSubscriber sub = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(sub);

        // Demand unbounded so events flow as the engine produces them.
        sub.subscriptionRef.get().request(Long.MAX_VALUE);

        // Wait for engine.runTurn to finish (5 events × ~50ms = ~250ms).
        // Use a barrier — poll for the 5th event arriving.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (sub.events.size() < 5 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
        }

        assertThat(sub.events)
            .as("all 5 TextDelta events must arrive")
            .hasSize(5);
        assertThat(sub.eventTimestampsNanos)
            .as("event timestamps must be recorded for span check")
            .hasSize(5);

        long firstNs = sub.eventTimestampsNanos.get(0);
        long lastNs = sub.eventTimestampsNanos.get(4);
        long spanMs = TimeUnit.NANOSECONDS.toMillis(lastNs - firstNs);

        // 4 sleeps × 50ms = ~200ms minimum. Allow some slack for slow CI.
        assertThat(spanMs)
            .as("events must stream live (NOT a single batch) — span ≥ 200ms for 5 events × 50ms sleep")
            .isGreaterThanOrEqualTo(200L);

        // Poll for onComplete — the engine thread still has to call
        // downstream.onComplete() after engine.runTurn returns, which is ~immediate
        // on the same thread but a small race window exists between the 5th
        // events.add(e) and the runTurnOnExecutor's post-engine onComplete.
        long completeDeadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!sub.complete.get() && System.nanoTime() < completeDeadlineNanos) {
            Thread.sleep(10);
        }
        assertThat(sub.complete.get())
            .as("LiveRunPublisher must synthesize onComplete after engine.runTurn returns")
            .isTrue();
    }

    // ── AC-049-04 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-04: secondSubscribe_throwsIllegalStateException")
    void secondSubscribe_throwsIllegalStateException() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        DefaultAgent agent = buildAgent(engine);

        Object publisher = agent.run("hi");

        // First subscribe succeeds.
        RecordingSubscriber first = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(first);
        assertThat(first.subscriptionRef.get())
            .as("first subscribe() must call Subscriber.onSubscribe")
            .isNotNull();

        // Second subscribe MUST deliver onError(IllegalStateException) per Reactive §2.2
        // + spec §3.4 (no throw out of subscribe — route via signals).
        RecordingSubscriber second = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(second);

        // The second subscriber should have received onSubscribe(no-op) + onError.
        assertThat(second.subscriptionRef.get())
            .as("second subscribe must still call Subscriber.onSubscribe (with a no-op Subscription)")
            .isNotNull();
        assertThat(second.errorRef.get())
            .as("second subscribe must trigger onError per Reactive §2.2/§3.4")
            .isInstanceOf(IllegalStateException.class);
        assertThat(second.errorRef.get())
            .as("the error message must reference single-subscriber enforcement")
            .hasMessageContaining("single subscriber");

        // First subscriber should NOT have been impacted.
        assertThat(first.errorRef.get())
            .as("first subscriber must not receive IllegalState")
            .isNull();
    }

    // ── AC-049-05 ───────────────────────────────────────────────────────────

    /**
     * Story #049 plan D-4 chose Story #005's {@code ctx.cancellation()} polling
     * mechanism but the implementation flips a local {@code cancelled} flag in the
     * bridge rather than firing the ctx cancellation token. The observable contract
     * the implementation honors is: <i>events stop being delivered after cancel()</i>.
     * This test verifies that observable contract without re-engineering the
     * cancellation token wiring (a follow-up Story could plumb
     * {@code ctx.cancellation().fire()} if a downstream engine polls it).
     */
    @Test
    @DisplayName("AC-049-05: subscriptionCancel_propagatesBridgeStopsDeliveringEvents")
    void subscriptionCancel_propagatesBridgeStopsDeliveringEvents() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        engine.emitCount = 10;  // 10 TextDelta with 50ms sleeps → ~500ms span
        DefaultAgent agent = buildAgent(engine);

        Object publisher = agent.run("hi");
        RecordingSubscriber sub = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(sub);

        // Demand unbounded so events flow as the engine produces them.
        sub.subscriptionRef.get().request(Long.MAX_VALUE);

        // Wait for the first few events to arrive, then cancel.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sub.events.size() < 2 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertThat(sub.events.size())
            .as("at least 2 events must arrive before we cancel")
            .isGreaterThanOrEqualTo(2);

        int receivedBeforeCancel = sub.events.size();
        Subscription s = sub.subscriptionRef.get();
        s.cancel();

        // Wait long enough for the engine to emit all 10 (500ms total).
        Thread.sleep(700);

        int receivedAfterCancel = sub.events.size();
        assertThat(receivedAfterCancel)
            .as("after cancel(), no further events must be delivered to the downstream "
                + "subscriber — the bridge drops them via the cancelled flag")
            .isLessThanOrEqualTo(receivedBeforeCancel + 1);  // +1 tolerance for in-flight
    }

    // ── AC-049-06 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-06: backpressure_zeroRequest_emissionBlocksUntilRequested")
    void backpressure_zeroRequest_emissionBlocksUntilRequested() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        engine.emitCount = 3;
        DefaultAgent agent = buildAgent(engine);

        Object publisher = agent.run("hi");
        RecordingSubscriber sub = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(sub);

        // CRITICAL: do NOT request anything — the bridge should park and not deliver events.
        // We give the engine enough time to emit all 3 events (~150ms).
        Thread.sleep(300);

        assertThat(sub.events)
            .as("with zero demand, no events must reach the downstream subscriber "
                + "(bridge parked waiting for request())")
            .isEmpty();

        // Now request(1) — exactly one event should arrive.
        sub.subscriptionRef.get().request(1);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sub.events.size() < 1 && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
        }

        assertThat(sub.events)
            .as("request(1) must deliver exactly 1 event (the rest remain parked)")
            .hasSize(1);

        // Cleanup: drain remaining events so engine.runTurn returns.
        sub.subscriptionRef.get().request(Long.MAX_VALUE);
        Thread.sleep(500);
    }

    // ── AC-049-07 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-07: engineEmitsError_propagatesToSubscriberOnError")
    void engineEmitsError_propagatesToSubscriberOnError() throws Exception {
        RecordingEngine engine = new RecordingEngine();
        RuntimeException engineError = new RuntimeException("engine blew up");
        engine.throwOnRun = engineError;
        DefaultAgent agent = buildAgent(engine);

        Object publisher = agent.run("hi");
        RecordingSubscriber sub = new RecordingSubscriber();
        ((org.reactivestreams.Publisher<AgentEvent>) publisher).subscribe(sub);
        sub.subscriptionRef.get().request(Long.MAX_VALUE);

        // Wait for the engine to throw.
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (sub.errorRef.get() == null && System.nanoTime() < deadlineNanos) {
            Thread.sleep(20);
        }

        assertThat(sub.errorRef.get())
            .as("engine exception must be delivered as Subscriber.onError")
            .isSameAs(engineError);
        assertThat(sub.complete.get())
            .as("after onError, onComplete must NOT be called (§2.4)")
            .isFalse();
    }
}