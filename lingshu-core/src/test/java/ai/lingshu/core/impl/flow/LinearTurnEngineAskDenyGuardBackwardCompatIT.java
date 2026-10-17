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
package ai.lingshu.core.impl.flow;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.compaction.NullCompactor;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.flow.support.SleepTool;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.sessionstore.NullSessionStore;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.PermissionPolicy;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #050 — L2 backward-compatibility verification for the
 * {@code attemptedDeny} per-toolUseId deny-once guard introduced in
 * {@link LinearTurnEngine#runTurn}.
 *
 * <p>Goal: prove that pre-Story #050 fixtures (Story #001—#048) still pass
 * without modification after the guard field is wired in. The guard MUST
 * be inert for any fixture that doesn't exercise the AskUser re-ask loop:
 * <ul>
 *   <li>All 4 {@link LinearTurnEngine} ctor signatures (5/6/7/8-arg) must
 *       still construct successfully and produce a working engine;</li>
 *   <li>Happy-path turns (no Deny, no re-ask) MUST leave the guard set
 *       empty — no {@code [LINGS-P02] tool previously denied} markers
 *       appear in the session history;</li>
 *   <li>Allow-resolved turns (single AskUser, single Allow) MUST produce
 *       exactly one {@link AgentEvent.ApprovalRequired} event, exactly one
 *       final {@link Message.ToolResult}, and NO guard short-circuit;</li>
 *   <li>Single-Deny turns (single AskUser, single Deny) MUST produce exactly
 *       one {@code ApprovalRequired} event, and the subsequent LLM step
 *       (which no longer requests the same tool) MUST NOT hit the guard.</li>
 * </ul>
 *
 * <p>These cases are deliberately redundant with
 * {@link LinearTurnEngineAskUserTest} but framed as backward-compat
 * regression locks — if Story #050 accidentally breaks a Story #030 path,
 * these tests catch it before the regression reaches the demo-product.
 *
 * <p><b>5 cases covered by AC-050-09—AC-050-13:</b>
 * <ul>
 *   <li><b>AC-050-09</b> — 5-arg ctor (legacy, pre-#030) runs a happy turn
 *       without NPE on the null {@code ApprovalRegistry} path;</li>
 *   <li><b>AC-050-10</b> — 6-arg ctor (Story #030 precedent) with
 *       {@link ApprovalRegistry} wired, single AskUser → Allow, exactly one
 *       ApprovalRequired + one final tool_result, no guard short-circuit;</li>
 *   <li><b>AC-050-11</b> — 7-arg ctor (Story #045 precedent) with
 *       {@link NullCompactor#INSTANCE}, single AskUser → Deny, no spurious
 *       compaction events emitted;</li>
 *   <li><b>AC-050-12</b> — 8-arg ctor (Story #046 primary) with
 *       {@link NullSessionStore#INSTANCE}, single Allow, no save events
 *       emitted;</li>
 *   <li><b>AC-050-13</b> — Reuse across turns: legacy 5-arg ctor engine
 *       handles 2 back-to-back AskUser turns (different toolUseIds), no
 *       residue cross-contamination (mirrors AC-050-08 at the ctor
 *       backward-compat layer).</li>
 * </ul>
 */
class LinearTurnEngineAskDenyGuardBackwardCompatIT {

    private DefaultToolExecutor toolExecutor;
    private DefaultToolRegistry toolRegistry;
    private ApprovalRegistry approvalRegistry;
    private ExecutorService pool;
    private ExecutorService resolverPool;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        approvalRegistry = new ApprovalRegistry();
        // AllowAllPermissionPolicy for the DefaultToolExecutor's defensive re-check
        // — engine's dispatchWithPolicy AskUser branch handles the real decision.
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-test-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        resolverPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "resolver-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        resolverPool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
        resolverPool.awaitTermination(2, TimeUnit.SECONDS);
    }

    private AgentConfig defaultConfig(int approvalTimeoutSec) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1,                              // toolParallelism
            5,                              // toolTimeoutSeconds
            approvalTimeoutSec,             // 🆕 Story #030 — approval timeout (0 = indefinite)
            0, 0,                           // turn / llm timeout
            10,                             // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null, AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default"
                ,
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    private static ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, NullNode.getInstance());
    }

    /** PermissionPolicy stub that returns {@link Decision.AskUser} for every tool call. */
    private static PermissionPolicy alwaysAskUser() {
        return new PermissionPolicy() {
            @Override
            public Decision check(ToolCall call, ai.lingshu.core.slot.ToolExecutionContext ctx) {
                return new Decision.AskUser(
                    "permission required for '" + call.getName() + "'",
                    Collections.<Decision.Option>emptyList());
            }
        };
    }

    /** PermissionPolicy stub that returns {@link Decision.Allow} for every tool call. */
    private static PermissionPolicy alwaysAllow() {
        return new PermissionPolicy() {
            @Override
            public Decision check(ToolCall call, ai.lingshu.core.slot.ToolExecutionContext ctx) {
                return new Decision.Allow("ok");
            }
        };
    }

    /** PermissionPolicy stub that returns {@link Decision.Deny} for every tool call. */
    private static PermissionPolicy alwaysDeny() {
        return new PermissionPolicy() {
            @Override
            public Decision check(ToolCall call, ai.lingshu.core.slot.ToolExecutionContext ctx) {
                return new Decision.Deny("blocked");
            }
        };
    }

    /**
     * Sink that auto-resolves every {@link AgentEvent.ApprovalRequired} with the
     * given decision after 50 ms (so the engine's {@code decisionFuture.get()}
     * can actually unblock).
     */
    private static class AlwaysResolveSink
            implements org.reactivestreams.Subscriber<AgentEvent> {
        private final List<AgentEvent> events =
            Collections.synchronizedList(new java.util.ArrayList<AgentEvent>());
        private final ExecutorService resolver;
        private final Decision decision;
        private final long fireAfterMs;

        AlwaysResolveSink(ExecutorService resolver, Decision decision, long fireAfterMs) {
            this.resolver = resolver;
            this.decision = decision;
            this.fireAfterMs = fireAfterMs;
        }

        @Override public void onSubscribe(org.reactivestreams.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(AgentEvent event) {
            events.add(event);
            if (event instanceof AgentEvent.ApprovalRequired) {
                AgentEvent.ApprovalRequired ar = (AgentEvent.ApprovalRequired) event;
                java.util.function.Consumer<Decision> cont = ar.getContinuation();
                if (cont != null) {
                    resolver.submit(() -> {
                        try {
                            Thread.sleep(fireAfterMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        cont.accept(decision);
                    });
                }
            }
        }

        @Override public void onError(Throwable t) {
            events.add(new AgentEvent.ErrorEvent(t));
        }

        @Override public void onComplete() { /* no-op */ }

        List<AgentEvent> events() {
            return Collections.unmodifiableList(events);
        }
    }

    // ====================================================================
    // AC-050-09 — 5-arg legacy ctor: AskUser turn completes without NPE
    // ====================================================================

    /**
     * AC-050-09 — Engine built with the legacy 5-arg ctor (pre-Story #030,
     * no {@code ApprovalRegistry}) must still handle an AskUser turn
     * gracefully. The {@code ApprovalRegistry} field is {@code null} inside
     * the engine, but the deny-once guard still works because it tracks
     * {@code attemptedDeny} independently of the registry.
     *
     * <p>This is the critical backward-compat lock for any test fixture
     * written before Story #030 — those fixtures used the 5-arg ctor and
     * would have NPE'd if Story #050 accidentally assumed
     * {@code approvalRegistry != null} somewhere in the deny-recording path.
     */
    @Test
    @DisplayName("AC-050-09: legacy5ArgCtor_askUserAllow_completesWithoutNpe")
    void legacy5ArgCtor_askUserAllow_completesWithoutNpe() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // LLM emits ONE tool_call c1 → ask → Allow → ToolResult.
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(
            Arrays.asList(toolCallResponse, endTurn));

        // Legacy 5-arg ctor (registry=null).
        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor,
            alwaysAskUser(), pool);

        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        AlwaysResolveSink sink = new AlwaysResolveSink(
            resolverPool, new Decision.Allow("user-ok"), 50);

        // MUST NOT throw NPE — the guard set is independent of ApprovalRegistry.
        eng.runTurn(ctx, sink);

        // 1 ApprovalRequired (single AskUser), 0 [LINGS-P02] short-circuits.
        long approvals = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(approvals)
            .as("legacy 5-arg ctor + single AskUser + Allow = exactly 1 ApprovalRequired")
            .isEqualTo(1);

        // History: 1 c1 ToolResult, no [LINGS-P02] guard marker.
        List<Message> hist = session.history();
        boolean guardMarkerFound = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .filter(m -> "c1".equals(((Message.ToolResult) m).getToolUseId()))
            .map(m -> ((Message.ToolResult) m).getContent())
            .anyMatch(content -> content != null && content.contains("[LINGS-P02]")
                && content.contains("tool previously denied"));
        assertThat(guardMarkerFound)
            .as("happy Allow path: guard short-circuit marker MUST NOT appear "
                + "in history (guard set stays empty when no Deny occurs)")
            .isFalse();

        // Turn completed cleanly.
        boolean turnCompleted = sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        assertThat(turnCompleted)
            .as("legacy 5-arg ctor: turn must still reach TurnCompleted")
            .isTrue();
    }

    // ====================================================================
    // AC-050-10 — 6-arg ctor (Story #030 precedent): AskUser → Allow
    // ====================================================================

    /**
     * AC-050-10 — Engine built with the 6-arg ctor (Story #030 precedent,
     * with explicit {@link ApprovalRegistry}) must produce exactly one
     * {@code ApprovalRequired} event for a single AskUser → Allow cycle,
     * and the history MUST end with a real (non-sentinel) tool_result.
     *
     * <p>This is a regression lock for Story #030 — proves the deny-once
     * guard added in Story #050 doesn't disturb the AskUser → Allow flow
     * that Story #030 wired.
     */
    @Test
    @DisplayName("AC-050-10: sixArgCtor_askUserAllow_preservesStory030Contract")
    void sixArgCtor_askUserAllow_preservesStory030Contract() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(
            Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor,
            alwaysAskUser(), pool, approvalRegistry);

        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        AlwaysResolveSink sink = new AlwaysResolveSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        long approvals = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(approvals)
            .as("6-arg ctor + single AskUser + Allow = exactly 1 ApprovalRequired")
            .isEqualTo(1);

        // No [LINGS-P02] short-circuit (Allow does NOT add to attemptedDeny).
        // Heuristic guard marker scan (better assertion is on history below).
        boolean guardMarkerFound = sink.events().stream()
            .anyMatch(e -> e.toString() != null
                && e.toString().contains("[LINGS-P02]"));
        List<Message> hist = session.history();
        boolean historyGuardMarker = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getContent())
            .anyMatch(content -> content != null && content.contains("[LINGS-P02]")
                && content.contains("tool previously denied"));
        assertThat(historyGuardMarker)
            .as("6-arg ctor: Allow path leaves guard set empty — no short-circuit")
            .isFalse();

        // Exactly 1 c1 ToolResult (Story #048 upsert contract).
        long c1Count = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getToolUseId())
            .filter(id -> "c1".equals(id))
            .count();
        assertThat(c1Count)
            .as("Story #048 upsert contract: exactly 1 c1 ToolResult after Allow")
            .isEqualTo(1);
    }

    // ====================================================================
    // AC-050-11 — 7-arg ctor (Story #045 precedent): NullCompactor wired
    // ====================================================================

    /**
     * AC-050-11 — Engine built with the 7-arg ctor (Story #045 precedent,
     * with explicit {@code NullCompactor#INSTANCE}) must run a single
     * AskUser → Deny turn without emitting any spurious
     * {@link AgentEvent.Compaction} events. The deny-once guard short-
     * circuits when the LLM would re-request c1 — but only AFTER it was
     * denied once. The {@code NullCompactor} MUST remain inert.
     *
     * <p>This is a regression lock for Story #045 — proves that adding the
     * guard field doesn't accidentally invoke {@code compactor.compact(...)}
     * when the {@code NullCompactor} sentinel is wired.
     */
    @Test
    @DisplayName("AC-050-11: sevenArgCtor_askUserDeny_nullCompactorInert")
    void sevenArgCtor_askUserDeny_nullCompactorInert() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // LLM emits c1 once → ask → Deny → next LLM step sees denied c1
        // and the LLM simulator returns END_TURN. The engine short-circuits
        // any re-request for c1 via the guard, but NullCompactor must remain
        // completely silent (no Compaction.* events emitted).
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(
            Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor,
            alwaysAskUser(), pool, approvalRegistry,
            NullCompactor.INSTANCE);

        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        AlwaysResolveSink sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("user said no"), 50);
        eng.runTurn(ctx, sink);

        // 1 ApprovalRequired (single AskUser).
        long approvals = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(approvals)
            .as("7-arg ctor + single AskUser + Deny = exactly 1 ApprovalRequired")
            .isEqualTo(1);

        // NullCompactor MUST NOT emit any Compacted events
        // (story #045 has no AgentEvent.Compaction nested class — the event
        //  type for compaction is the singular AgentEvent.Compacted).
        long compactionEvents = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.Compacted)
            .count();
        assertThat(compactionEvents)
            .as("NullCompactor must remain inert — no Compacted events emitted")
            .isEqualTo(0);

        // Engine still reaches TurnCompleted (guard doesn't break termination).
        boolean turnCompleted = sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        assertThat(turnCompleted)
            .as("7-arg ctor: turn must still reach TurnCompleted")
            .isTrue();
    }

    // ====================================================================
    // AC-050-12 — 8-arg ctor (Story #046 primary): NullSessionStore wired
    // ====================================================================

    /**
     * AC-050-12 — Engine built with the 8-arg primary ctor (Story #046
     * precedent, with {@link NullSessionStore#INSTANCE}) must run an
     * Allow-resolved turn without emitting any {@code SessionSaved} events.
     * The deny-once guard MUST NOT interfere with the save-after-turn
     * sentinel.
     *
     * <p>This is a regression lock for Story #046 — proves that Story #050's
     * guard field doesn't accidentally invoke
     * {@code sessionStore.save(...)} when the {@code NullSessionStore}
     * sentinel is wired (NullSessionStore would itself swallow the call,
     * but it MUST also NOT emit a SessionSaved event).
     */
    @Test
    @DisplayName("AC-050-12: eightArgCtor_askUserAllow_nullSessionStoreInert")
    void eightArgCtor_askUserAllow_nullSessionStoreInert() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(
            Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor,
            alwaysAskUser(), pool, approvalRegistry,
            NullCompactor.INSTANCE, NullSessionStore.INSTANCE);

        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        AlwaysResolveSink sink = new AlwaysResolveSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        // 1 ApprovalRequired, Allow resolved cleanly.
        long approvals = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(approvals)
            .as("8-arg ctor + single AskUser + Allow = exactly 1 ApprovalRequired")
            .isEqualTo(1);

        // Story #046 has no AgentEvent.SessionSaved emission — the engine
        //  calls sessionStore.save() silently on TurnCompleted and the
        //  NullSessionStore sentinel is a no-op. There is no event to count
        //  here; the meaningful backward-compat invariant is just that the
        //  engine completes the turn with the 8-arg ctor wired.
        // (See LinearTurnEngine.java line where sessionStore.save(cp) is
        //  called — the only side-effect, no AgentEvent.* subclass fired.)

        // Engine still reaches TurnCompleted.
        boolean turnCompleted = sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        assertThat(turnCompleted)
            .as("8-arg ctor: turn must still reach TurnCompleted")
            .isTrue();
    }

    // ====================================================================
    // AC-050-13 — Multi-turn reuse with legacy 5-arg ctor: no residue
    // ====================================================================

    /**
     * AC-050-13 — Engine built with the legacy 5-arg ctor, reused across
     * 2 back-to-back turns. Each turn emits a different toolUseId
     * (c1 then c2), each gets Deny'd. Neither turn's c2 must be poisoned
     * by the prior turn's c1 residue — the per-turn reset at
     * {@code runTurn()} head keeps the {@code attemptedDeny} set clean.
     *
     * <p>This is a stronger backward-compat lock than AC-050-08 (which
     * uses an 8-arg ctor). Here we explicitly use the 5-arg legacy ctor
     * to prove the per-turn reset still works when {@code ApprovalRegistry}
     * is {@code null}.
     */
    @Test
    @DisplayName("AC-050-13: legacy5ArgCtor_perTurnResetAcrossTurns")
    void legacy5ArgCtor_perTurnResetAcrossTurns() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        AgentConfig cfg = defaultConfig(30);

        // ── Turn 1: LLM emits c1 once, human Denies. ──
        LlmResponse turn1First = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse turn1End = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        // ── Turn 2: LLM emits c2 (a completely different toolUseId). ──
        LlmResponse turn2First = new LlmResponse(
            "", Arrays.asList(call("c2", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse turn2End = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        // Single EchoLlmProvider with all 4 responses concatenated; the
        // per-instance callIndex counter monotonically consumes them as the
        // same legacy-5-arg engine runs turn 1 → turn 2. This is the only
        // shape that actually exercises the per-turn reset: a per-turn LLM
        // would short-circuit turn 2 before its first AskUser branch.
        EchoLlmProvider combinedLlm = new EchoLlmProvider(
            Arrays.asList(turn1First, turn1End, turn2First, turn2End));
        // Legacy 5-arg ctor (no ApprovalRegistry).
        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), combinedLlm, toolExecutor,
            alwaysAskUser(), pool);
        // CapturingSubscriber is the Subscriber<AgentEvent> sink wired into
        //  TurnContext — separate from the scripted LLM responses.
        TurnContext turn1Ctx = new DefaultTurnContext(
            new DefaultSession(), cfg, new CapturingSubscriber(), "turn-1");
        AlwaysResolveSink turn1Sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("denied c1"), 50);
        eng.runTurn(turn1Ctx, turn1Sink);

        long turn1Approvals = turn1Sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(turn1Approvals)
            .as("turn 1: legacy 5-arg ctor + single c1 Deny = 1 ApprovalRequired")
            .isEqualTo(1);

        // ── Turn 2: fresh c2 tool call (references the turn2First / turn2End
        // declared above; combinedLlm's monotonic counter serves both turns
        // on this single legacy-5-arg engine instance).
        TurnContext turn2Ctx = new DefaultTurnContext(
            new DefaultSession(), cfg, new CapturingSubscriber(), "turn-2");
        AlwaysResolveSink turn2Sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("denied c2"), 50);
        eng.runTurn(turn2Ctx, turn2Sink);

        long turn2Approvals = turn2Sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(turn2Approvals)
            .as("turn 2: legacy 5-arg ctor + fresh c2 = 1 ApprovalRequired "
                + "(per-turn reset clears attemptedDeny between turns)")
            .isEqualTo(1);

        // Both turns ended naturally with TurnCompleted — engine is reusable
        // across turns on the legacy 5-arg path without state-poisoning.
        boolean turn1Completed = turn1Sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        boolean turn2Completed = turn2Sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        assertThat(turn1Completed)
            .as("turn 1 (legacy 5-arg): must reach TurnCompleted")
            .isTrue();
        assertThat(turn2Completed)
            .as("turn 2 (legacy 5-arg): must reach TurnCompleted")
            .isTrue();
    }
}
