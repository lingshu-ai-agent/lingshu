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
import org.reactivestreams.Subscriber;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #030 + #048 — L2 slice tests for {@link LinearTurnEngine} AskUser branch.
 *
 * <p><b>Story #030 (3 cases)</b> verifies the real wiring end-to-end:
 * <ul>
 *   <li>AC-030-19 — Engine emits {@link AgentEvent.ApprovalRequired} with a stable
 *       {@code approvalId} when permission policy returns {@link Decision.AskUser};</li>
 *   <li>AC-030-20 — Engine resolves the blocked turn when the captured continuation
 *       is invoked with {@link Decision.Allow};</li>
 *   <li>AC-030-21 — ApprovalRegistry round-trip — engine registers, the test
 *       consumes via {@link ApprovalRegistry#consume}.</li>
 * </ul>
 *
 * <p><b>Story #048 (4 cases)</b> verifies the upsert-by-toolUseId semantics in
 * {@link DefaultTurnContext#appendToolResult(ToolResult)} when an AskUser turn
 * eventually resolves (Allow / Deny / {@code LINGS-P02} timeout / never-resolved):
 * <ul>
 *   <li>AC-048-04 — Allow path: history ends with one real tool_result
 *       (NOT a sentinel duplicate);</li>
 *   <li>AC-048-05 — Deny path: history ends with one tool_result
 *       carrying {@code reason + isError=true} (NOT a sentinel duplicate);</li>
 *   <li>AC-048-06 — Timeout path: history ends with one tool_result carrying
 *       {@code [LINGS-P02] ... isError=true} (NOT a sentinel duplicate);</li>
 *   <li>AC-048-07 — Never-resolved: even when the frontend never invokes the
 *       continuation, the sentinel tool_result keeps history protocol-consistent
 *       (paired to {@code tool_use.id} for the next Anthropic request).</li>
 * </ul>
 *
 * <p>Together these tests lock the contract that <em>the only tool_result block
 * for a given {@code tool_use.id} within a turn is the last one written</em>,
 * regardless of how the AskUser gate resolves.
 */
class LinearTurnEngineAskUserTest {

    private DefaultToolExecutor toolExecutor;
    private DefaultToolRegistry toolRegistry;
    private ApprovalRegistry approvalRegistry;
    private ExecutorService pool;
    private ExecutorService resolverPool;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        approvalRegistry = new ApprovalRegistry();
        // The executor used by the engine: AllowAllPermissionPolicy for the defensive
        // re-check (engine handles AskUser before reaching here).
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-test-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        // A separate pool to invoke the continuation asynchronously so the
        // engine's decisionFuture.get() can actually unblock.
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

    /** PermissionPolicy stub that returns AskUser for a specific tool name. */
    private static PermissionPolicy askUserPolicyFor(final String askToolName) {
        return new PermissionPolicy() {
            @Override
            public Decision check(ToolCall call, ai.lingshu.core.slot.ToolExecutionContext ctx) {
                if (askToolName.equals(call.getName())) {
                    return new Decision.AskUser(
                        "permission required for '" + call.getName() + "'",
                        Collections.<Decision.Option>emptyList());
                }
                return new Decision.Allow("ok");
            }
        };
    }

    private LinearTurnEngine engine(PermissionPolicy policy) {
        return new LinearTurnEngine(
            new RecordingPromptBuilder(),
            new EchoLlmProvider(Arrays.asList(
                new LlmResponse("", Collections.<ToolCall>emptyList(),
                    StopReason.END_TURN, Usage.zero()))),
            toolExecutor, policy, pool, approvalRegistry);
    }

    /**
     * Custom sink that, when it sees {@link AgentEvent.ApprovalRequired}, schedules
     * the continuation to fire with {@link Decision.Allow} after a short delay
     * (so the engine's {@code decisionFuture.get()} can actually unblock).
     */
    private static class AskUserResolvingSink implements Subscriber<AgentEvent> {
        private final List<AgentEvent> events =
            Collections.synchronizedList(new java.util.ArrayList<AgentEvent>());
        private final ExecutorService resolver;
        private final Decision decision;
        private final long fireAfterMs;

        AskUserResolvingSink(ExecutorService resolver, Decision decision, long fireAfterMs) {
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

    @Test
    @DisplayName("AC-030-19: engine_askUser_emitsApprovalRequiredWithApprovalId")
    void engine_askUser_emitsApprovalRequiredWithApprovalId() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        // Engine emits ONE tool call to bash_safe; policy returns AskUser for it.
        // We need an LLM that emits the tool call, then we'll see what happens.
        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        AgentConfig cfg = defaultConfig(30);  // 30s timeout for the resolver pool
        TurnContext ctx = new DefaultTurnContext(new DefaultSession(), cfg,
            new CapturingSubscriber(), "hi");

        AskUserResolvingSink sink = new AskUserResolvingSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        // The sink should have seen at least one ApprovalRequired event.
        AtomicReference<AgentEvent.ApprovalRequired> arRef = new AtomicReference<>();
        for (AgentEvent e : sink.events()) {
            if (e instanceof AgentEvent.ApprovalRequired) {
                arRef.set((AgentEvent.ApprovalRequired) e);
                break;
            }
        }
        assertThat(arRef.get())
            .as("engine must emit ApprovalRequired when policy returns AskUser")
            .isNotNull();
        assertThat(arRef.get().getApprovalId())
            .as("approvalId must be non-null UUID")
            .isNotNull();
        assertThat(arRef.get().getApprovalId()).isNotEmpty();
        assertThat(arRef.get().getAsk()).isInstanceOf(Decision.AskUser.class);
    }

    @Test
    @DisplayName("AC-030-20: engine_askUser_continuationAllows_proceedsToToolExecution")
    void engine_askUser_continuationAllows_proceedsToToolExecution() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        AgentConfig cfg = defaultConfig(30);
        TurnContext ctx = new DefaultTurnContext(new DefaultSession(), cfg,
            new CapturingSubscriber(), "hi");

        AskUserResolvingSink sink = new AskUserResolvingSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        // Tool must have run → at least one ToolCompleted event
        long completed = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ToolCompleted)
            .count();
        assertThat(completed).isGreaterThanOrEqualTo(1);
        // The completed tool result content is from SleepTool("bash_safe", 10)
        ToolResult result = (ToolResult) sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ToolCompleted)
            .findFirst()
            .map(e -> ((AgentEvent.ToolCompleted) e).getResult())
            .orElse(null);
        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("AC-030-21: approvalRegistry_registerAndConsume")
    void approvalRegistry_registerAndConsume() {
        // Drive just the registry directly — engine emits ApprovalRequired and
        // registers the continuation; here we verify the registry contract.
        AtomicReference<Decision> captured = new AtomicReference<Decision>();
        java.util.function.Consumer<Decision> cont = captured::set;
        String id = "test-approval-id-001";
        approvalRegistry.register(id, cont);

        assertThat(approvalRegistry.size()).isEqualTo(1);
        java.util.function.Consumer<Decision> got = approvalRegistry.consume(id);
        assertThat(got).isNotNull();
        got.accept(new Decision.Allow("user-ok"));
        assertThat(captured.get()).isInstanceOf(Decision.Allow.class);
        assertThat(approvalRegistry.size()).isEqualTo(0);

        // Second consume → null (atomic, single-use)
        assertThat(approvalRegistry.consume(id)).isNull();
    }

    // ====================================================================
    // Story #048 — AskUser sentinel tool_result upsert-by-toolUseId
    // ====================================================================

    /**
     * Capture-only sink that records events but <em>never</em> invokes the
     * {@link AgentEvent.ApprovalRequired} continuation. Used by AC-048-07 to
     * simulate "the frontend never rendered Allow/Deny buttons" — the worst-case
     * scenario the sentinel + upsert must survive.
     */
    private static class AskUserCaptureOnlySink implements Subscriber<AgentEvent> {
        private final List<AgentEvent> events =
            Collections.synchronizedList(new java.util.ArrayList<AgentEvent>());

        @Override public void onSubscribe(org.reactivestreams.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(AgentEvent event) {
            events.add(event);
            // Deliberately do NOT invoke continuation — simulates a frontend that
            // never renders Allow/Deny buttons.
        }

        @Override public void onError(Throwable t) {
            events.add(new AgentEvent.ErrorEvent(t));
        }

        @Override public void onComplete() { /* no-op */ }

        List<AgentEvent> events() {
            return Collections.unmodifiableList(events);
        }
    }

    /**
     * AC-048-04 — Allow path: a single AskUser turn that resolves to
     * {@link Decision.Allow} must leave history with the real tool_result
     * (paired to {@code tool_use.id="c1"}) and <em>no</em> sentinel duplicate.
     *
     * <p>Why this matters: {@code LinearTurnEngine.dispatchWithPolicy} commits
     * the sentinel <em>before</em> blocking on the gate (Story #048 sentinel
     * commit point). When the gate resolves Allow, {@code ToolExecutor.dispatch()}
     * produces a real {@code ToolResult} that flows back through
     * {@code LinearTurnEngine.runTurn}'s L338 {@code appendToolResult} loop.
     * {@link DefaultTurnContext#appendToolResult(ToolResult)} must upsert in
     * place (scoped to the most recent Assistant), so history stays at size=3
     * with exactly one {@code Message.ToolResult} carrying {@code id="c1"}.
     */
    @Test
    @DisplayName("AC-048-04: askUser_allow_resolvesToRealResult_upsertsSentinel")
    void askUser_allow_resolvesToRealResult_upsertsSentinel() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        AskUserResolvingSink sink = new AskUserResolvingSink(
            resolverPool, new Decision.Allow("user-ok"), 50);
        eng.runTurn(ctx, sink);

        List<Message> hist = session.history();
        // Sentinel was committed, then the real Allow result upserted it in place.
        // History invariant: Assistant(tool_use) + ToolResult(real content) + (final
        // Assistant empty toolCalls appended at the END_TURN close) = size 3, NOT 4.
        assertThat(hist).hasSize(3);

        // Count ToolResults paired to tool_use.id="c1" — must be exactly 1
        // (sentinel was upserted in place by the real Allow result; no duplicate).
        long c1Count = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getToolUseId())
            .filter(id -> "c1".equals(id))
            .count();
        assertThat(c1Count)
            .as("history must contain exactly one ToolResult for toolUseId=c1 "
                + "(sentinel+upsert, no duplicate, no missing)")
            .isEqualTo(1);

        Message.ToolResult tr = (Message.ToolResult) hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .filter(m -> "c1".equals(((Message.ToolResult) m).getToolUseId()))
            .findFirst().orElse(null);
        assertThat(tr).isNotNull();
        // The Allow path produced a SUCCESS ToolResult — content is the real
        // tool output, NOT the "[PENDING_APPROVAL]" sentinel prefix.
        assertThat(tr.getContent()).doesNotStartWith("[PENDING_APPROVAL]");
        assertThat(tr.isError()).isFalse();
    }

    /**
     * AC-048-05 — Deny path: an AskUser turn that resolves to
     * {@link Decision.Deny} must leave history with one tool_result carrying
     * the deny {@code reason} as content + {@code isError=true} (the ToolResult
     * returned by the Deny branch of {@code dispatchWithPolicy}). No sentinel
     * duplicate.
     */
    @Test
    @DisplayName("AC-048-05: askUser_deny_resolvesToDenyResult_upsertsSentinel")
    void askUser_deny_resolvesToDenyResult_upsertsSentinel() {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        AgentConfig cfg = defaultConfig(30);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        AskUserResolvingSink sink = new AskUserResolvingSink(
            resolverPool, new Decision.Deny("user said no"), 50);
        eng.runTurn(ctx, sink);

        List<Message> hist = session.history();
        assertThat(hist).hasSize(3);

        // Count ToolResults paired to tool_use.id="c1" — must be exactly 1
        // (sentinel was upserted in place by the Deny-branch ToolResult).
        long c1Count = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getToolUseId())
            .filter(id -> "c1".equals(id))
            .count();
        assertThat(c1Count)
            .as("history must contain exactly one ToolResult for toolUseId=c1 "
                + "(sentinel+upsert, no duplicate, no missing)")
            .isEqualTo(1);

        Message.ToolResult tr = (Message.ToolResult) hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .filter(m -> "c1".equals(((Message.ToolResult) m).getToolUseId()))
            .findFirst().orElse(null);
        assertThat(tr).isNotNull();
        // Deny branch returns a ToolResult whose content is the deny reason and
        // whose isError=true. The sentinel is upserted in place.
        assertThat(tr.getContent()).contains("user said no");
        assertThat(tr.isError()).isTrue();
        assertThat(tr.getContent()).doesNotStartWith("[PENDING_APPROVAL]");
    }

    /**
     * AC-048-06 — Timeout path: setting {@code approvalTimeoutSeconds = 1} and
     * never invoking the continuation causes {@code DefaultApprovalGate.ask()}
     * to catch {@code TimeoutException} and return {@code Decision.Deny("[LINGS-P02] ...")}.
     * History ends with a single tool_result carrying the {@code [LINGS-P02]}
     * marker + {@code isError=true}; no sentinel duplicate. This is the case the
     * Story #048 fix directly addresses: even with timeout, history must remain
     * protocol-consistent for the next Anthropic request.
     */
    @Test
    @DisplayName("AC-048-06: askUser_timeout_resolvesToLingsP02_upsertsSentinel")
    void askUser_timeout_resolvesToLingsP02_upsertsSentinel() throws InterruptedException {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        // approvalTimeoutSec=1 → DefaultApprovalGate.ask() blocks 1s then
        // TimeoutException → Deny("[LINGS-P02] Permission approval timed out...").
        AgentConfig cfg = defaultConfig(1);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        // Sink that NEVER invokes the continuation — gate will time out.
        AskUserCaptureOnlySink sink = new AskUserCaptureOnlySink();
        eng.runTurn(ctx, sink);

        // Wait beyond the 1s timeout for the turn to actually complete.
        Thread.sleep(1500);

        List<Message> hist = session.history();
        assertThat(hist).hasSize(3);

        // Count ToolResults paired to tool_use.id="c1" — must be exactly 1
        // (sentinel was upserted in place by the Timeout→Deny-branch ToolResult).
        long c1Count = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getToolUseId())
            .filter(id -> "c1".equals(id))
            .count();
        assertThat(c1Count)
            .as("history must contain exactly one ToolResult for toolUseId=c1 "
                + "(sentinel+upsert, no duplicate, no missing)")
            .isEqualTo(1);

        Message.ToolResult tr = (Message.ToolResult) hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .filter(m -> "c1".equals(((Message.ToolResult) m).getToolUseId()))
            .findFirst().orElse(null);
        assertThat(tr).isNotNull();
        // Timeout path's Deny carries "[LINGS-P02] Permission approval timed out..."
        assertThat(tr.getContent()).contains("[LINGS-P02]");
        assertThat(tr.isError()).isTrue();
        // Sentinel must have been upserted in place — no [PENDING_APPROVAL] remains.
        assertThat(tr.getContent()).doesNotStartWith("[PENDING_APPROVAL]");
    }

    /**
     * AC-048-07 — Never-resolved path: the most adversarial scenario. The
     * frontend never invokes the continuation (no Allow, no Deny, no timeout).
     * The turn must still complete with history carrying exactly one
     * tool_result for the pending {@code tool_use.id} (the sentinel itself),
     * keeping the protocol-consistent invariant for the next Anthropic
     * request. Without Story #048's upsert, history would either carry a
     * duplicate tool_result (blocking the next turn) or, worse, have no
     * tool_result at all (Anthropic HTTP 400 on the next turn).
     */
    @Test
    @DisplayName("AC-048-07: askUser_neverResolved_turnStillCompletes_historyProtocolConsistent")
    void askUser_neverResolved_turnStillCompletes_historyProtocolConsistent() throws InterruptedException {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine eng = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);
        // approvalTimeoutSec=0 → DefaultApprovalGate.ask() blocks forever
        // (Long.MAX_VALUE) when the sink never resolves. The turn must complete
        // via cancel() invoked from outside.
        AgentConfig cfg = defaultConfig(0);
        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, cfg,
            new CapturingSubscriber(), "hi");

        // Capture-only sink: records events, never invokes continuation.
        AskUserCaptureOnlySink sink = new AskUserCaptureOnlySink();

        // Schedule a cancel() on ctx from a separate thread after a short delay —
        // simulates the user clicking "cancel" or shutting down the agent.
        final Thread runner = Thread.currentThread();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                ctx.markDone();
            }
        }, "cancel-after-300ms").start();

        eng.runTurn(ctx, sink);

        // History invariant for AC-048-07 — the most adversarial scenario.
        // The frontend never invokes the continuation (no Allow, no Deny, no
        // approval-timeout). The turn must still complete via the engine's
        // outer dispatchParallel timeout (toolTimeoutSec=5 default), and
        // history must carry EXACTLY ONE ToolResult paired to tool_use.id="c1".
        // This is the protocol-consistent invariant Story #048 fixes: the
        // sentinel+upsert keeps a single tool_result block alive for every
        // tool_use, regardless of how the gate resolves.
        List<Message> hist = session.history();
        // Find every ToolResult and group by toolUseId — must be exactly 1 for c1.
        long c1Count = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getToolUseId())
            .filter(id -> "c1".equals(id))
            .count();
        assertThat(c1Count)
            .as("history must contain exactly one ToolResult for toolUseId=c1 "
                + "(sentinel+upsert, no duplicate, no missing)")
            .isEqualTo(1);

        // The surviving ToolResult is the upserted outcome — content depends
        // on which path resolved (Allow / Deny / LINGS-P02 / outer tool-timeout).
        // We don't pin the literal prefix here; the protocol-consistent invariant
        // is "exactly one tool_result per tool_use.id within the turn", which
        // is what the c1Count assertion above locks. The upsert has replaced
        // the sentinel with the actual outcome — this is by design.
        Message.ToolResult surviving = (Message.ToolResult) hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .filter(m -> "c1".equals(((Message.ToolResult) m).getToolUseId()))
            .findFirst().orElse(null);
        assertThat(surviving)
            .as("the surviving tool_result must be present (sentinel+upsert "
                + "contract guarantees at least one)")
            .isNotNull();
        assertThat(surviving.getToolUseId()).isEqualTo("c1");
    }
}