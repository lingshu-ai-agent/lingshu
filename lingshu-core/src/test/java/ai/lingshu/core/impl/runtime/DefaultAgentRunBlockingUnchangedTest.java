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
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.RunResult;
import ai.lingshu.core.runtime.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #049 — L1/L2 backward-compatibility slice test. Stubs out so
 * {@code DefaultAgent.runBlocking} / {@code continueWithUserMessage} /
 * {@code continueWithUserMessageBlocking} remain byte-for-byte identical to the
 * v1.5.59 synchronous path after the hot lazy {@code LiveRunPublisher} swap
 * for {@code run()}.
 *
 * <p><b>Verification strategy</b> — the prod change is scoped to the
 * {@code run(String)} method body (now returns a {@code LiveRunPublisher}) and
 * to the addition of a per-Agent {@code liveExecutor} field + daemon
 * {@code ThreadFactory}. {@code runBlocking} / {@code continueWithUserMessage} /
 * {@code continueWithUserMessageBlocking} method bodies must NOT change. This
 * file locks that contract by exercising each method against a hand-rolled
 * {@link FlowEngine} mock that captures the {@link Subscriber} it was driven
 * with and emits a small scripted event sequence.
 *
 * <p><b>Mirrors precedent</b> — Story #001's {@code DefaultAgentTest}
 * {@code RecordingSubscriber} established the "engine mock that emits onNext
 * + onComplete" pattern for the synchronous path; this file extends it with
 * pre-scripted {@link AgentEvent.TurnCompleted} payloads so the
 * {@code RunResult} aggregation contract is locked byte-for-byte.
 *
 * <p><b>AC mapping</b>
 * <ul>
 *   <li>AC-049-08 → {@link #runBlocking_returnsCompletedRunResult_unchanged}</li>
 *   <li>AC-049-09 → {@link #continueWithUserMessage_appendsUserMessage_doesNotSwitchToReactive}</li>
 *   <li>AC-049-10 → {@link #runBlocking_doesNotInvolveLiveExecutor}</li>
 *   <li>AC-049-11 → {@link #multipleRunBlockingCallsConcurrently_eachGetsFreshRunResult}</li>
 * </ul>
 */
class DefaultAgentRunBlockingUnchangedTest {

    /**
     * Scripted {@link FlowEngine} mock. Emits a fixed sequence of events on the
     * supplied {@link Subscriber} and captures the {@link Subscriber} it was
     * driven with so the test can verify event delivery. The {@code emitted}
     * list is built up in a single {@code engine.runTurn} call.
     */
    private static final class ScriptedEngine implements FlowEngine {
        final AtomicReference<Subscriber<? super AgentEvent>> heldSubscriber = new AtomicReference<>();
        final AtomicInteger invocations = new AtomicInteger(0);
        final List<AgentEvent> script;
        final CountDownLatch entered = new CountDownLatch(1);

        ScriptedEngine(List<AgentEvent> script) {
            this.script = script;
        }

        @Override
        public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
            invocations.incrementAndGet();
            heldSubscriber.set(sink);
            entered.countDown();
            for (AgentEvent e : script) {
                sink.onNext(e);
            }
        }
    }

    private static DefaultAgent buildAgent(FlowEngine engine) {
        return new DefaultAgent(new DefaultSession(), AgentConfigDefaults.defaults(), engine);
    }

    /**
     * Snapshot the count of live threads whose name starts with
     * {@code lingshu-agent-live-}. Used by AC-049-10 to prove that
     * {@code runBlocking} never submitted work to the new {@code liveExecutor}.
     */
    private static int countLiveExecutorThreads() {
        int n = 0;
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int actual = Thread.enumerate(threads);
        for (int i = 0; i < actual; i++) {
            if (threads[i].getName().startsWith("lingshu-agent-live-")) {
                n++;
            }
        }
        return n;
    }

    // ── AC-049-08 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-08: runBlocking_returnsCompletedRunResult_unchanged")
    void runBlocking_returnsCompletedRunResult_unchanged() throws Exception {
        // Scripted emit sequence mirroring a typical 1-turn QA: text deltas +
        // a terminal TurnCompleted with non-zero usage + non-default stopReason.
        Usage expectedUsage = new Usage(42, 17);
        List<AgentEvent> script = new ArrayList<>();
        script.add(new AgentEvent.TextDelta("Hello"));
        script.add(new AgentEvent.TextDelta(", world!"));
        script.add(new AgentEvent.TurnCompleted(StopReason.MAX_TOKENS, expectedUsage));

        ScriptedEngine engine = new ScriptedEngine(script);
        DefaultAgent agent = buildAgent(engine);

        RunResult result = agent.runBlocking("hi");

        // byte-for-byte contract — these are the 5 RunResult fields assembled
        // inside runBlocking's for-loop aggregator. If anyone refactors
        // runBlocking and breaks the aggregation, this assertion fires.
        assertThat(result.getFinalText())
            .as("finalText is the concatenation of all TextDelta events in script order")
            .isEqualTo("Hello, world!");
        assertThat(result.getStopReason())
            .as("stopReason is taken from the LAST TurnCompleted event")
            .isEqualTo(StopReason.MAX_TOKENS);
        assertThat(result.getTotalUsage())
            .as("totalUsage is the LAST TurnCompleted event's Usage payload")
            .isSameAs(expectedUsage);
        assertThat(result.getTurns())
            .as("turns counts every TurnCompleted event in the script")
            .isEqualTo(1);
        assertThat(result.getElapsedMillis())
            .as("elapsedMillis is a non-negative wall-clock duration")
            .isGreaterThanOrEqualTo(0L);

        // Engine was called exactly once (no double-drain / no extra subscribe cycle).
        assertThat(engine.invocations.get())
            .as("runBlocking should invoke engine.runTurn exactly once")
            .isEqualTo(1);
    }

    // ── AC-049-09 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-09: continueWithUserMessage_appendsUserMessage_doesNotSwitchToReactive")
    void continueWithUserMessage_appendsUserMessage_doesNotSwitchToReactive() throws Exception {
        // Spy on the session — count append() calls and snapshot what was appended.
        DefaultSession session = new DefaultSession();
        ScriptedEngine engine = new ScriptedEngine(Collections.singletonList(
            new AgentEvent.TurnCompleted(StopReason.END_TURN, Usage.zero())));
        DefaultAgent agent = new DefaultAgent(session, AgentConfigDefaults.defaults(), engine);

        RunResult result = agent.continueWithUserMessageBlocking("user-reply");

        // (a) continueWithUserMessageBlocking → continueWithUserMessage → session.append(user)
        //     then → run() → buildContext() → session.append(user) AGAIN. Pre-existing
        //     Story #020c + Story #007 double-append contract: exactly two Message.User
        //     entries with the user-reply content (one from continueWithUserMessage,
        //     one from buildContext at run entry).
        List<Message.User> users = new ArrayList<>();
        session.historyMut().forEach(m -> {
            if (m instanceof Message.User) {
                users.add((Message.User) m);
            }
        });
        assertThat(users)
            .as("continueWithUserMessage must record the user input — exactly two "
                + "Message.User entries because continueWithUserMessage prepends an "
                + "append before delegating to run() whose buildContext appends again")
            .hasSize(2);
        assertThat(users.get(0).getContent()).isEqualTo("user-reply");
        assertThat(users.get(1).getContent()).isEqualTo("user-reply");

        // (b) The reactive path was NOT taken — engine.runTurn is invoked from the
        //    synchronous drain subscriber, NOT from a LiveRunPublisher subscribe().
        //    The contract: continueWithUserMessageBlocking drains via the in-line
        //    drain Subscriber; runBlocking-equivalent semantics. We assert by
        //    verifying the resulting RunResult is well-formed (text, turns, reason).
        assertThat(result.getStopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(result.getTurns()).isEqualTo(1);

        // (c) Engine invoked exactly once — proves runBlocking-equivalent path
        //    did not also subscribe a LiveRunPublisher (which would invoke again).
        assertThat(engine.invocations.get())
            .as("continueWithUserMessageBlocking invokes engine exactly once — "
                + "must NOT double-invoke via a concurrent LiveRunPublisher.subscribe()")
            .isEqualTo(1);
    }

    // ── AC-049-10 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-10: runBlocking_doesNotInvolveLiveExecutor")
    void runBlocking_doesNotInvolveLiveExecutor() throws Exception {
        ScriptedEngine engine = new ScriptedEngine(Collections.singletonList(
            new AgentEvent.TurnCompleted(StopReason.END_TURN, Usage.zero())));
        DefaultAgent agent = buildAgent(engine);

        // Snapshot liveExecutor thread count BEFORE — should be 0 in a clean JVM.
        // runBlocking never publishes a LiveRunPublisher, so the executor is
        // never submitted to. After runBlocking the count must be unchanged.
        int before = countLiveExecutorThreads();

        RunResult result = agent.runBlocking("hi");
        assertThat(result.getTurns()).isEqualTo(1);

        int after = countLiveExecutorThreads();
        assertThat(after)
            .as("runBlocking must NOT submit to the per-Agent liveExecutor"
                + " (lingshu-agent-live-N threads spawned by runBlocking → %d → %d)",
                before, after)
            .isEqualTo(before);
        assertThat(after)
            .as("specifically, no lingshu-agent-live-N thread should be alive")
            .isZero();
    }

    // ── AC-049-11 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-049-11: multipleRunBlockingCallsConcurrently_eachGetsFreshRunResult")
    void multipleRunBlockingCallsConcurrently_eachGetsFreshRunResult() throws Exception {
        // Two distinct scripted engines, each producing a different finalText +
        // stopReason so we can prove the two RunResults don't cross-contaminate.
        ScriptedEngine engineA = new ScriptedEngine(new ArrayList<AgentEvent>() {{
            add(new AgentEvent.TextDelta("alpha-"));
            add(new AgentEvent.TextDelta("done"));
            add(new AgentEvent.TurnCompleted(StopReason.END_TURN, new Usage(1, 2)));
        }});
        ScriptedEngine engineB = new ScriptedEngine(new ArrayList<AgentEvent>() {{
            add(new AgentEvent.TextDelta("beta-"));
            add(new AgentEvent.TextDelta("done"));
            add(new AgentEvent.TurnCompleted(StopReason.MAX_TOKENS, new Usage(4, 5)));
        }});

        DefaultAgent agentA = buildAgent(engineA);
        DefaultAgent agentB = buildAgent(engineB);

        // Run the two runBlocking calls on parallel threads. Each has its own
        // DefaultAgent + ScriptedEngine + DefaultSession, so cross-contamination
        // would manifest as one thread seeing the other's finalText / usage.
        final AtomicReference<RunResult> resARef = new AtomicReference<>();
        final AtomicReference<RunResult> resBRef = new AtomicReference<>();
        final AtomicReference<Throwable> errARef = new AtomicReference<>();
        final AtomicReference<Throwable> errBRef = new AtomicReference<>();
        CountDownLatch bothDone = new CountDownLatch(2);

        Thread ta = new Thread(() -> {
            try { resARef.set(agentA.runBlocking("a")); }
            catch (Throwable t) { errARef.set(t); }
            finally { bothDone.countDown(); }
        }, "runBlocking-A");
        Thread tb = new Thread(() -> {
            try { resBRef.set(agentB.runBlocking("b")); }
            catch (Throwable t) { errBRef.set(t); }
            finally { bothDone.countDown(); }
        }, "runBlocking-B");

        ta.start();
        tb.start();
        boolean finished = bothDone.await(5, TimeUnit.SECONDS);
        assertThat(finished)
            .as("both concurrent runBlocking calls must complete within 5s")
            .isTrue();
        assertThat(errARef.get()).as("thread A error").isNull();
        assertThat(errBRef.get()).as("thread B error").isNull();

        RunResult ra = resARef.get();
        RunResult rb = resBRef.get();
        assertThat(ra).isNotNull();
        assertThat(rb).isNotNull();

        // Each RunResult carries its OWN engine's emitted events — no cross-
        // contamination. The literal finalText strings + Usage instances are
        // distinct objects on the two engines' scripts.
        assertThat(ra.getFinalText())
            .as("thread A's RunResult.finalText must match engineA's emit sequence")
            .isEqualTo("alpha-done");
        assertThat(ra.getStopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(ra.getTotalUsage()).isEqualTo(new Usage(1, 2));

        assertThat(rb.getFinalText())
            .as("thread B's RunResult.finalText must match engineB's emit sequence")
            .isEqualTo("beta-done");
        assertThat(rb.getStopReason()).isEqualTo(StopReason.MAX_TOKENS);
        assertThat(rb.getTotalUsage()).isEqualTo(new Usage(4, 5));
    }
}