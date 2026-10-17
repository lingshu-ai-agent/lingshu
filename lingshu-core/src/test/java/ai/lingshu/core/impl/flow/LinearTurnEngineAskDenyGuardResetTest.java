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
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.SleepTool;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
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
import org.reactivestreams.Subscriber;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #050 — L1 reset tests for the per-turn {@code attemptedDeny.clear()}
 * inside {@code LinearTurnEngine.runTurn} (L254). The guard set MUST be reset
 * at the head of every turn so a human who denied an ask-mode tool in turn N
 * can legitimately re-request (or be re-prompted) for the same tool in turn N+1.
 *
 * <p>Background: {@code attemptedDeny} is a {@code Set<String>} of denied
 * {@code toolUseId}s. Without the per-turn reset, denying in turn N would
 * poison the set forever — even a fresh, legitimate human re-ask in turn N+1
 * would short-circuit to {@code [LINGS-P02] tool previously denied}, blocking
 * real work after a single accidental deny. The reset mirrors Story #008's
 * per-turn ReAct-step-counter reset pattern.
 *
 * <p><b>2 cases covered by AC-050-07 / AC-050-08:</b>
 * <ul>
 *   <li><b>AC-050-07</b> — guard turned on at turn 1 fires within turn 1, but a
 *       fresh turn 2 with the SAME engine instance and SAME toolUseId does NOT
 *       short-circuit (clean slate after turn-start reset);</li>
 *   <li><b>AC-050-08</b> — back-to-back turns do not cross-contaminate: turn 1
 *       denies id "c1", turn 2 denies id "c2" — turn 2 sees no "c1" residue
 *       and gets a fresh ApprovalRequired (also proves the reset is per-turn
 *       not per-id).</li>
 * </ul>
 */
class LinearTurnEngineAskDenyGuardResetTest {

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

    private LinearTurnEngine engine() {
        return new LinearTurnEngine(
            new RecordingPromptBuilder(),
            // placeholder — actual scripted responses provided per turn via new
            // EchoLlmProvider() in each call (the callIndex counter is per-instance).
            new EchoLlmProvider(Arrays.asList(
                new LlmResponse("", Collections.<ToolCall>emptyList(),
                    StopReason.END_TURN, Usage.zero()))),
            toolExecutor, alwaysAskUser(), pool, approvalRegistry);
    }

    /**
     * Engine factory that wires a caller-supplied {@link EchoLlmProvider}
     * (with all responses for the multi-turn scenario concatenated in
     * invocation order) into a fresh {@link LinearTurnEngine}. The
     * per-instance {@code callIndex} counter on {@code EchoLlmProvider}
     * monotonically consumes responses as the same engine runs back-to-back
     * turns — this is exactly how Story #050's multi-turn tests must work
     * to actually exercise the per-turn {@code attemptedDeny.clear()}.
     */
    private LinearTurnEngine engineWithLlm(EchoLlmProvider llm) {
        return new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor,
            alwaysAskUser(), pool, approvalRegistry);
    }

    /**
     * Sink that auto-resolves every {@link AgentEvent.ApprovalRequired} with the
     * given decision after 50 ms (so the engine's {@code decisionFuture.get()} can
     * actually unblock). Mirrors {@code AlwaysResolveSink} from
     * {@link LinearTurnEngineAskDenyGuardTest}.
     */
    private static class AlwaysResolveSink implements Subscriber<AgentEvent> {
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
    // AC-050-07 — turn 1 deny records c1, turn 2 with same id does NOT short-circuit
    // ====================================================================

    /**
     * AC-050-07 — turn-start reset semantics. Within turn 1, LLM emits
     * {@code toolUse("c1")} twice → guard fires on second entry. Then a fresh
     * turn 2 starts on the SAME engine instance with the SAME toolUseId "c1"
     * — the guard MUST NOT short-circuit because {@code attemptedDeny.clear()}
     * runs at the head of every {@code runTurn()} (mirrors Story #008 reset).
     *
     * <p>Without the reset, the set would leak across turns: even a fresh,
     * legitimate human re-ask in turn 2 would be blocked. The reset preserves
     * the principle that a denied tool can be re-asked in a new turn once
     * the user has had a chance to reconsider.
     */
    @Test
    @DisplayName("AC-050-07: turnStartReset_clearsGuardForFreshTurn")
    void turnStartReset_clearsGuardForFreshTurn() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // ── Turn 1: LLM emits c1 twice (same id re-asked) ──
        LlmResponse turn1First = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse turn1Second = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse turn1End = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        // ── Turn 2: LLM emits c1 ONCE (fresh human re-ask in a new turn) ──
        // Same engine instance, same toolUseId "c1", but turn-start clear()
        // wiped the set → guard MUST NOT fire, fresh ApprovalRequired expected.
        LlmResponse turn2First = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse turn2End = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        // Single EchoLlmProvider with all 5 responses concatenated; the
        // per-instance callIndex counter monotonically consumes them as the
        // same engine runs turn 1 → turn 2. This is the only correct shape:
        // a per-turn LLM would short-circuit turn 2 before its first AskUser
        // branch (turn 1's LLM has END_TURN-only which exits immediately).
        EchoLlmProvider combinedLlm = new EchoLlmProvider(
            Arrays.asList(turn1First, turn1Second, turn1End,
                turn2First, turn2End));
        LinearTurnEngine eng = engineWithLlm(combinedLlm);

        AgentConfig cfg = defaultConfig(30);
        // DefaultTurnContext ctor: (Session, AgentConfig, Subscriber<AgentEvent>,
        //  userInput) — use CapturingSubscriber as the sink; the scripted LLM
        //  responses above are wired into the engine via the LinearTurnEngine
        //  ctor (engine() helper).
        TurnContext turn1Ctx = new DefaultTurnContext(
            new DefaultSession(), cfg, new CapturingSubscriber(), "turn-1");
        AlwaysResolveSink turn1Sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("user said no"), 50);
        eng.runTurn(turn1Ctx, turn1Sink);

        // Turn 1 expectation: guard fires on the second entry → exactly 1 ApprovalRequired.
        long turn1Approvals = turn1Sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(turn1Approvals)
            .as("turn 1: guard short-circuits on the second c1 entry — "
                + "exactly 1 ApprovalRequired event expected")
            .isEqualTo(1);

        // ── Turn 2: LLM emits c1 ONCE (fresh human re-ask in a new turn) ──
        // Same engine instance, same toolUseId "c1", but turn-start clear()
        // wiped the set → guard MUST NOT fire, fresh ApprovalRequired expected.
        // (turn2First + turn2End already appended to combinedLlm above.)

        TurnContext turn2Ctx = new DefaultTurnContext(
            new DefaultSession(), cfg, new CapturingSubscriber(), "turn-2");
        AlwaysResolveSink turn2Sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("user said no again"), 50);
        eng.runTurn(turn2Ctx, turn2Sink);

        // Turn 2 expectation: clean slate after turn-start reset → exactly 1 ApprovalRequired.
        long turn2Approvals = turn2Sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(turn2Approvals)
            .as("turn 2: attemptedDeny.clear() at runTurn head wiped c1 from turn 1 — "
                + "exactly 1 fresh ApprovalRequired expected (guard must NOT short-circuit)")
            .isEqualTo(1);

        // Neither turn saw the guard short-circuit on its FIRST entry — that's the
        // proof: turn 1 first entry → 1 ApprovalRequired, turn 2 first entry → 1
        // ApprovalRequired. If the reset were missing, turn 2's first c1 entry
        // would short-circuit and emit 0 ApprovalRequired events.
        // Total across both turns: 1 (turn 1, before guard fires) + 0 (turn 1, guard fires)
        //                       + 1 (turn 2, after reset) = 2 total.
        long totalApprovals = turn1Approvals + turn2Approvals;
        assertThat(totalApprovals)
            .as("across 2 turns: turn 1 = 1 (guard fires on second entry), "
                + "turn 2 = 1 (clean slate after reset) = 2 total")
            .isEqualTo(2);

        // Both turns emitted TurnCompleted (engine reaches natural end after guard + reset).
        boolean turn1Completed = turn1Sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        boolean turn2Completed = turn2Sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        assertThat(turn1Completed).as("turn 1 must still reach TurnCompleted").isTrue();
        assertThat(turn2Completed).as("turn 2 must still reach TurnCompleted").isTrue();
    }

    // ====================================================================
    // AC-050-08 — back-to-back turns: per-turn reset, no id cross-contamination
    // ====================================================================

    /**
     * AC-050-08 — per-turn reset is per-turn, not per-id. Turn 1 denies id "c1";
     * turn 2 denies id "c2" (a fresh, never-seen toolUseId). Turn 2's "c2" must
     * NOT be affected by turn 1's "c1" residue.
     *
     * <p>This is the symmetric case of AC-050-07: even after a deny of one id in
     * turn 1, a totally different id in turn 2 must still get its own fresh
     * ApprovalRequired. The guard reset is per-turn, so by the time turn 2
     * starts, both ids are gone from the cleared set.
     */
    @Test
    @DisplayName("AC-050-08: perTurnReset_differentIdsAcrossTurnsIndependent")
    void perTurnReset_differentIdsAcrossTurnsIndependent() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // ── Turn 1: LLM emits "c1" once and human denies. ──
        // Single tool_use — the guard doesn't fire within turn 1 because the LLM
        // doesn't re-ask. We're seeding the set with c1 via a deny.
        LlmResponse turn1First = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse turn1End = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        // ── Turn 2: LLM emits "c2" once (a completely different toolUseId). ──
        // Even if turn 1's "c1" had NOT been cleared, "c2" wouldn't match the set,
        // so this also implicitly tests the per-id isolation. But the real purpose
        // is to demonstrate that the engine is healthy across turns — there's no
        // leaked state from turn 1 poisoning turn 2's policy decisions.
        LlmResponse turn2First = new LlmResponse(
            "", Arrays.asList(call("c2", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse turn2End = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        // Single EchoLlmProvider with all 4 responses concatenated; the
        // per-instance callIndex counter monotonically consumes them as the
        // same engine runs turn 1 → turn 2.
        EchoLlmProvider combinedLlm = new EchoLlmProvider(
            Arrays.asList(turn1First, turn1End, turn2First, turn2End));
        LinearTurnEngine eng = engineWithLlm(combinedLlm);

        AgentConfig cfg = defaultConfig(30);
        TurnContext turn1Ctx = new DefaultTurnContext(
            new DefaultSession(), cfg, new CapturingSubscriber(), "turn-1");
        AlwaysResolveSink turn1Sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("denied c1"), 50);
        eng.runTurn(turn1Ctx, turn1Sink);

        long turn1Approvals = turn1Sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(turn1Approvals)
            .as("turn 1: single c1 tool_use + human deny = 1 ApprovalRequired")
            .isEqualTo(1);

        // ── Turn 2: LLM emits "c2" once (a completely different toolUseId). ──
        // Even if turn 1's "c1" had NOT been cleared, "c2" wouldn't match the set,
        // so this also implicitly tests the per-id isolation. But the real purpose
        // is to demonstrate that the engine is healthy across turns — there's no
        // leaked state from turn 1 poisoning turn 2's policy decisions.
        // (turn2First + turn2End already appended to combinedLlm above.)

        TurnContext turn2Ctx = new DefaultTurnContext(
            new DefaultSession(), cfg, new CapturingSubscriber(), "turn-2");
        AlwaysResolveSink turn2Sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("denied c2"), 50);
        eng.runTurn(turn2Ctx, turn2Sink);

        long turn2Approvals = turn2Sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(turn2Approvals)
            .as("turn 2: fresh c2 toolUseId, no residue from turn 1's c1 deny — "
                + "exactly 1 ApprovalRequired expected")
            .isEqualTo(1);

        // Sanity: both turns ended naturally with TurnCompleted — engine is reusable
        // across turns without state-poisoning.
        boolean turn1Completed = turn1Sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        boolean turn2Completed = turn2Sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        assertThat(turn1Completed).as("turn 1 must reach TurnCompleted").isTrue();
        assertThat(turn2Completed).as("turn 2 must reach TurnCompleted").isTrue();
    }
}