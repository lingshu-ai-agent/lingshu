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
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.flow.support.SleepTool;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #050 — L1 unit tests for the per-toolUseId deny-once guard inside
 * {@code LinearTurnEngine.dispatchWithPolicy} AskUser branch.
 *
 * <p>Background (Bug-2 from the Story #050 spec): in ask-mode, the LLM may
 * re-request approval for the <em>same</em> tool call by emitting a fresh
 * {@code tool_use.id} on each {@code message_start}. A human who already denied
 * the same intent once would be re-prompted indefinitely → engine never reaches
 * {@code TurnCompleted} because every loop spawns a new approval round-trip.
 *
 * <p>Fix (Story #050): {@code LinearTurnEngine} tracks {@code attemptedDeny}
 * (a {@code Set<String>} of denied toolUseIds) per turn. On entering the
 * AskUser branch, the guard short-circuits with a deterministic
 * {@code [LINGS-P02]} ToolResult if the toolUseId was already denied. The set
 * is reset at the head of every {@code runTurn()}.
 *
 * <p><b>3 cases covered by AC-050-04 through AC-050-06:</b>
 * <ul>
 *   <li><b>AC-050-04</b> — same toolUseId re-asked: second entry hits the guard
 *       and returns {@code [LINGS-P02]} error content (no re-prompt);</li>
 *   <li><b>AC-050-05</b> — different toolUseIds independent: guard does NOT
 *       cross-contaminate across distinct toolUseIds (c1 denies, c2 still asks);</li>
 *   <li><b>AC-050-06</b> — Allow does not add to guard set: a human Allow
 *       keeps the toolUseId re-askable later (no false-positive short-circuit
 *       if the LLM legitimately retries with a fresh id).</li>
 * </ul>
 */
class LinearTurnEngineAskDenyGuardTest {

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

    /**
     * PermissionPolicy stub that returns {@link Decision.AskUser} for every tool
     * call (used by deny-once tests — we just want every call to enter the
     * AskUser branch).
     */
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

    private LinearTurnEngine engine(EchoLlmProvider llm, PermissionPolicy policy) {
        return new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
    }

    /**
     * Sink that auto-resolves every {@link AgentEvent.ApprovalRequired} with
     * the given decision. Mirrors {@code AskUserResolvingSink} from
     * {@link LinearTurnEngineAskUserTest}.
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
    // AC-050-04 — same toolUseId re-asked: guard short-circuits with LINGS-P02
    // ====================================================================

    /**
     * AC-050-04 — Bug-2 fix verification. The LLM emits tool_use("c1") twice
     * within the same turn (simulating Anthropic re-emitting on each
     * message_start). On the first entry, AskUser → Deny (human said no).
     * On the second entry with the SAME id="c1", the guard must short-circuit
     * with a deterministic "[LINGS-P02] tool previously denied" ToolResult —
     * NOT block on a second approval gate.
     *
     * <p>Why this matters: without the guard, the engine would emit a second
     * {@code AgentEvent.ApprovalRequired} event and the human (who already
     * said no) would be re-prompted. The LLM's history would then see the
     * tool succeed (because of the timeout window), no longer matched to user
     * intent → infinite ask-loop, session never completes.
     */
    @Test
    @DisplayName("AC-050-04: same toolUseId re-asked_guardShortCircuitsToLingsP02")
    void sameToolUseIdReAsked_guardShortCircuitsToLingsP02() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // Engine emits the SAME tool call id="c1" twice — Anthropic protocol
        // requires a fresh approvalId per message_start, but the tool_use.id
        // can stay constant when the LLM retries the same intent.
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse toolCallAgain = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(
            Arrays.asList(toolCallResponse, toolCallAgain, endTurn));

        LinearTurnEngine eng = engine(llm, alwaysAskUser());
        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        // First entry: human says Deny. Second entry: must NOT re-prompt —
        // guard fires immediately.
        AlwaysResolveSink sink = new AlwaysResolveSink(
            resolverPool, new Decision.Deny("user said no"), 50);
        eng.runTurn(ctx, sink);

        // Count ApprovalRequired events — must be exactly 1 (first AskUser),
        // NOT 2 (no re-prompt for the second toolUseId re-entry).
        long approvalCount = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(approvalCount)
            .as("guard must prevent re-approval for the same toolUseId "
                + "— exactly 1 ApprovalRequired event, NOT 2")
            .isEqualTo(1);

        // History: 2 ToolResults for toolUseId=c1 (sentinel upserted by first
        // deny result, then guard short-circuit emits a second ToolResult on
        // the same id when the LLM re-emits c1).
        List<Message> hist = session.history();
        long c1Count = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getToolUseId())
            .filter(id -> "c1".equals(id))
            .count();
        assertThat(c1Count)
            .as("history should record the guard-hit ToolResult alongside the "
                + "first deny result (sentinel+upsert+guard short-circuit chain)")
            .isGreaterThanOrEqualTo(2);

        // At least one ToolResult for c1 must carry the [LINGS-P02] guard marker
        // — that's the deterministic short-circuit that prevents infinite ask-loop.
        boolean guardMarkerFound = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .filter(m -> "c1".equals(((Message.ToolResult) m).getToolUseId()))
            .map(m -> ((Message.ToolResult) m).getContent())
            .anyMatch(content -> content != null && content.contains("[LINGS-P02]")
                && content.contains("tool previously denied"));
        assertThat(guardMarkerFound)
            .as("at least one c1 ToolResult must carry the guard short-circuit "
                + "marker [LINGS-P02] tool previously denied ... (re-approval "
                + "denied to avoid infinite ask-loop)")
            .isTrue();

        // Engine must still reach TurnCompleted (the guard ensures eventual termination).
        boolean turnCompleted = sink.events().stream()
            .anyMatch(e -> e instanceof AgentEvent.TurnCompleted);
        assertThat(turnCompleted)
            .as("engine must still emit TurnCompleted after guard fires "
                + "(guard enables termination, doesn't break it)")
            .isTrue();
    }

    // ====================================================================
    // AC-050-05 — different toolUseIds independent: no cross-contamination
    // ====================================================================

    /**
     * AC-050-05 — Independent toolUseIds. The LLM emits two distinct tool
     * calls in the same turn (c1 → bash, c2 → write_file). The guard tracks
     * per-id; denying c1 must NOT short-circuit c2's AskUser.
     *
     * <p>Why this matters: a naive "if any prior AskUser denied, deny tool"
     * guard would kill all subsequent tool calls in the turn. The per-id
     * design preserves the principle of least surprise: each toolUseId has
     * its own approval lifecycle.
     */
    @Test
    @DisplayName("AC-050-05: differentToolUseIds_independentGuard")
    void differentToolUseIds_independentGuard() {
        toolRegistry.register(new SleepTool("bash_safe", 10));
        toolRegistry.register(new SleepTool("write_file", 10));

        // First response: toolUse c1 (bash_safe). Second response: toolUse c2
        // (write_file). Both are AskUser-required; both should produce an
        // ApprovalRequired event independently.
        LlmResponse c1Call = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse c2Call = new LlmResponse(
            "", Arrays.asList(call("c2", "write_file")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(c1Call, c2Call, endTurn));

        LinearTurnEngine eng = engine(llm, alwaysAskUser());
        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        // Deny c1, Allow c2. Guard should NOT cross-contaminate — c2 must
        // still get its own ApprovalRequired event.
        final AtomicInteger callCount = new AtomicInteger(0);
        Subscriber<AgentEvent> sink = new Subscriber<AgentEvent>() {
            @Override public void onSubscribe(org.reactivestreams.Subscription s) {
                s.request(Long.MAX_VALUE);
            }
            @Override public void onNext(AgentEvent event) {
                if (event instanceof AgentEvent.ApprovalRequired) {
                    final int seq = callCount.getAndIncrement();
                    AgentEvent.ApprovalRequired ar = (AgentEvent.ApprovalRequired) event;
                    final java.util.function.Consumer<Decision> cont = ar.getContinuation();
                    if (cont != null) {
                        // seq=0 → Deny c1; seq=1 → Allow c2.
                        final Decision d = seq == 0
                            ? (Decision) new Decision.Deny("no bash for you")
                            : (Decision) new Decision.Allow("ok write_file");
                        resolverPool.submit(() -> {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                            cont.accept(d);
                        });
                    }
                }
            }
            @Override public void onError(Throwable t) { /* no-op */ }
            @Override public void onComplete() { /* no-op */ }
        };
        eng.runTurn(ctx, sink);

        // Exactly 2 ApprovalRequired events — one per toolUseId. Guard is per-id,
        // c2 is NOT short-circuited by c1's deny.
        assertThat(callCount.get())
            .as("exactly 2 ApprovalRequired events expected (c1 and c2 are "
                + "independent — guard does NOT cross-contaminate)")
            .isEqualTo(2);

        // Both toolUseIds should appear in history.
        List<Message> hist = session.history();
        boolean hasC1 = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .anyMatch(m -> "c1".equals(((Message.ToolResult) m).getToolUseId()));
        boolean hasC2 = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .anyMatch(m -> "c2".equals(((Message.ToolResult) m).getToolUseId()));
        assertThat(hasC1).as("c1 ToolResult must be in history").isTrue();
        assertThat(hasC2).as("c2 ToolResult must be in history (independent guard)").isTrue();
    }

    // ====================================================================
    // AC-050-06 — Allow doesn't add to guard set: re-ask with fresh id still asks
    // ====================================================================

    /**
     * AC-050-06 — Allow does NOT add to the guard set. The LLM emits the
     * same toolUseId twice; on the first entry, human says Allow (tool runs
     * to completion). On the second entry, the LLM legitimately retries
     * with a fresh approvalId for the same intent — the guard must NOT
     * short-circuit because Allow did not record the id as "denied".
     *
     * <p>Why this matters: only Deny / timeout / recursion-limit transitions
     * are recorded. A legitimate "user changed their mind" path (Allow once,
     * then LLM retries for follow-up) must keep the gate open. The guard is
     * <em>deny-once</em>, not <em>ask-once</em>.
     */
    @Test
    @DisplayName("AC-050-06: allowDoesNotAddToGuardSet_reAskStillPrompts")
    void allowDoesNotAddToGuardSet_reAskStillPrompts() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // First response: c1 (Allow). Second response: c1 again (must re-ask).
        LlmResponse firstCall = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse secondCall = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(
            Arrays.asList(firstCall, secondCall, endTurn));

        LinearTurnEngine eng = engine(llm, alwaysAskUser());
        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        // Both entries → Allow (human says yes twice). Guard must NOT fire on
        // the second entry because Allow does not record the id.
        AlwaysResolveSink sink = new AlwaysResolveSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        // Exactly 2 ApprovalRequired events — guard did NOT short-circuit.
        long approvalCount = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ApprovalRequired)
            .count();
        assertThat(approvalCount)
            .as("Allow does NOT add to attemptedDeny — re-asking the same "
                + "toolUseId after Allow must still produce a fresh ApprovalRequired")
            .isEqualTo(2);
    }
}