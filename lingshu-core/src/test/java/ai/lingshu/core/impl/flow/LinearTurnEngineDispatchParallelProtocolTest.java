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
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #052 — L2 slice tests for {@link LinearTurnEngine#dispatchParallel}'s
 * <b>boundary sentinel</b> at the Anthropic protocol invariant.
 *
 * <p>The 6 cases here lock the contract that <em>history always ends with a
 * matching {@code tool_result} block for every {@code tool_use} block emitted
 * by the LLM, regardless of what fails on the parallel-dispatch path</em>.
 * Pre-#052, a downstream SPI that returned {@code null} (programmer error) or
 * a wrong-{@code toolUseId} result (race in serialization) caused the next
 * Anthropic POST to 400 with
 * <i>"tool_use ids were found without tool_result blocks"</i>. The boundary
 * sentinel substitutes an ERROR {@code ToolResult} carrying
 * {@code [LINGS-L04]} so history stays protocol-consistent.
 *
 * <p>Cases:
 * <ul>
 *   <li>AC-052-01 — happy path 2 parallel {@code bash_safe} calls: history ends with
 *       {@code Assistant(tool_use[c00,c01]) → tool_result[c00] → tool_result[c01]}.</li>
 *   <li>AC-052-02 — 1 of 2 calls throws {@code RuntimeException} (race condition):
 *       1 success tool_result + 1 sentinel ERROR tool_result (NOT {@code null}).</li>
 *   <li>AC-052-03 — AskUser mode: pending approval keeps sentinel in history so the
 *       next Anthropic POST stays valid even if approval never resolves (AC-052-03
 *       verifies AskUser + protocol invariant coexist).</li>
 *   <li>AC-052-04 — Cancel before tool dispatch: all calls substitute CANCELLED
 *       tool_results so the LLM never sees an orphan {@code tool_use}.</li>
 *   <li>AC-052-05 — 1 of 2 tools exceeds {@code toolTimeoutSeconds}: 1 success +
 *       1 CANCELLED tool_result carrying timeout diagnostic.</li>
 *   <li>AC-052-06 — All cancelled: history ends with N CANCELLED tool_results, one
 *       per {@code tool_use.id}.</li>
 * </ul>
 *
 * <p>Each test asserts two invariants:
 * (1) {@code session.history()} ends with N tool_result blocks matching the N
 *     {@code tool_use.id}s from the LLM-emitted Assistant message;
 * (2) No tool_result block has a {@code null} {@code toolUseId} (would crash
 *     Anthropic's protocol layer with HTTP 400).
 */
class LinearTurnEngineDispatchParallelProtocolTest {

    private DefaultToolRegistry toolRegistry;
    private DefaultToolExecutor toolExecutor;
    private ApprovalRegistry approvalRegistry;
    private ExecutorService pool;
    private ExecutorService resolverPool;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        // Engine handles AskUser before reaching toolExecutor — defensive AllowAll re-check.
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        approvalRegistry = new ApprovalRegistry();
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-prtcl-" + System.nanoTime());
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

    private AgentConfig defaultConfig(int parallelism, int toolTimeoutSec) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            parallelism,
            toolTimeoutSec,
            0,                  // approvalTimeoutSeconds
            0, 0,               // turn / llm timeout
            10,                 // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null, AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default",
            16,                 // 🆕 Story #044 — maxConcurrentTurns
            32);                // 🆕 Story #044 — maxConcurrentQueueDepth
    }

    private static ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, NullNode.getInstance());
    }

    /** PermissionPolicy stub that returns AskUser for a specific tool name. */
    private static PermissionPolicy askUserPolicyFor(final String askToolName) {
        return new PermissionPolicy() {
            @Override
            public Decision check(ToolCall call, ToolExecutionContext ctx) {
                if (askToolName.equals(call.getName())) {
                    return new Decision.AskUser(
                        "permission required for '" + call.getName() + "'",
                        Collections.<Decision.Option>emptyList());
                }
                return new Decision.Allow("ok");
            }
        };
    }

    /** Trivial {@link Tool} that returns SUCCESS with {@code <toolName>:OK} content. */
    private static Tool echoTool(final String toolName) {
        return new Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return "test echo tool " + toolName; }
            @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
                return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content(toolName + ":OK")
                    .isError(false)
                    .build();
            }
        };
    }

    /** Trivial {@link Tool} that throws {@code RuntimeException} (simulates SPI race). */
    private static Tool throwingTool(final String toolName, final String message) {
        return new Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return "test throwing tool " + toolName; }
            @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
                throw new RuntimeException(message);
            }
        };
    }

    /** Trivial {@link Tool} that sleeps for {@code ms} then returns SUCCESS. */
    private static Tool sleepTool(final String toolName, final long ms) {
        return new Tool() {
            @Override public String name() { return toolName; }
            @Override public String description() { return "test sleep tool " + toolName; }
            @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content(toolName + ":OK")
                    .isError(false)
                    .build();
            }
        };
    }

    /**
     * Verify history ends with an Assistant(tool_calls) followed by N tool_results matching those calls.
     *
     * <p><b>Anthropic protocol invariant</b> — each {@code tool_use} block MUST be paired with a
     * {@code tool_result} block carrying the same {@code toolUseId}. <b>This is a match-by-id
     * invariant, NOT a strict positional order invariant.</b> The Anthropic API does not require
     * tool_results to appear in the same position as their tool_use siblings; what it requires is
     * that every {@code tool_use.id} has a matching {@code tool_result.tool_use_id} somewhere in
     * the next user message (Story #048 sentinel-upsert lets us commit them out of order safely).
     *
     * <p>Therefore this helper asserts the ID <em>set</em> membership of the trailing tool_results,
     * not their positional alignment. As a consequence, Story #048's upsert-by-toolUseId semantics
     * (which can reorder entries when the AskUser sentinel commits early) are explicitly supported.
     */
    private static void assertProtocolConsistent(List<Message> history, List<String> expectedToolUseIds) {
        // 1. The last Assistant message WITH non-empty tool_calls must have
        //    toolCalls.size() == expected.size(). We skip END_TURN Assistants (empty
        //    toolCalls) that come after the action step — for a 2-iteration flow,
        //    history is [Assistant(tool_use[c00,c01]), TR(c00), TR(c01), Assistant("done", [])],
        //    and the last Assistant is the END_TURN one. The "Last Assistant WITH
        //    tool_use" assertion is what locks the Anthropic protocol invariant for
        //    the most-recent action step.
        final int assistantIdx = lastAssistantWithToolUseIdx(history);
        assertThat(assistantIdx)
            .as("history must contain an Assistant(tool_use) message")
            .isGreaterThanOrEqualTo(0);
        Message.Assistant assistant = (Message.Assistant) history.get(assistantIdx);
        assertThat(assistant.getToolCalls())
            .as("Assistant(tool_use).toolCalls must match expected LLM-emitted tool_use count")
            .hasSize(expectedToolUseIds.size());

        // 2. History tail (after last Assistant(tool_use)) must contain N ToolResult messages,
        //    each with a non-null toolUseId. Collect their IDs into a list for the set-membership check.
        java.util.List<String> tailToolResultIds = new java.util.ArrayList<>();
        for (int i = assistantIdx + 1; i < history.size(); i++) {
            if (history.get(i) instanceof Message.ToolResult) {
                tailToolResultIds.add(((Message.ToolResult) history.get(i)).getToolUseId());
            }
        }
        assertThat(tailToolResultIds)
            .as("history tail must contain exactly one tool_result per LLM-emitted tool_use.id")
            .hasSize(expectedToolUseIds.size());
        assertThat(tailToolResultIds)
            .as("each tool_result.toolUseId must NEVER be null — would 400 the next Anthropic POST")
            .doesNotContainNull();
        // 🆕 Story #052 — verify the ID set matches (Anthropic protocol invariant is match-by-id,
        // NOT strict positional order). Story #048's upsert-by-toolUseId legitimately reorders
        // tool_results when an AskUser sentinel commits early during a parallel batch — the API
        // only requires every tool_use.id to have a matching tool_result with the same id.
        assertThat(tailToolResultIds)
            .as("tool_result.toolUseIds must match the LLM-emitted tool_use ids as a SET — "
                + "Anthropic protocol invariant is match-by-id, not strict positional order")
            .containsExactlyInAnyOrderElementsOf(expectedToolUseIds);
    }

    /**
     * Find the index of the last {@link Message.Assistant} in {@code history} whose
     * {@code toolCalls} list is non-null AND non-empty — i.e. the most recent <em>action-step</em>
     * Assistant. Returns {@code -1} if no such Assistant exists.
     *
     * <p>Why this and not "last Assistant overall"? Because a 2-iteration flow ends with an
     * END_TURN Assistant (empty toolCalls) that comes AFTER the tool_result tail we want to inspect.
     * Iterating backwards and breaking on the FIRST Assistant would land on the END_TURN one and
     * skip past all tool_results.
     */
    private static int lastAssistantWithToolUseIdx(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m instanceof Message.Assistant) {
                Message.Assistant candidate = (Message.Assistant) m;
                if (candidate.getToolCalls() != null && !candidate.getToolCalls().isEmpty()) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Collect every {@link Message.ToolResult} that appears after the most-recent action-step
     * Assistant (i.e. the Assistant whose toolCalls list is non-empty), in forward order.
     *
     * <p>Used by AC-052-02 and AC-052-04 to inspect the trailing tool_results without walking
     * back over the END_TURN Assistant. Mirrors the {@code assistantIdx + 1} forward-iteration
     * pattern from {@link #assertProtocolConsistent}.
     */
    private static java.util.List<Message.ToolResult> tailResultsAfterLastToolUseAssistant(
            List<Message> history) {
        final int assistantIdx = lastAssistantWithToolUseIdx(history);
        java.util.List<Message.ToolResult> tail = new java.util.ArrayList<>();
        if (assistantIdx < 0) {
            return tail;
        }
        for (int i = assistantIdx + 1; i < history.size(); i++) {
            if (history.get(i) instanceof Message.ToolResult) {
                tail.add((Message.ToolResult) history.get(i));
            }
        }
        return tail;
    }

    // ──────────────────────────────────────────────────────────────────────
    // AC-052-01 — happy path: 2 parallel bash_safe calls, history is consistent
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("AC-052-01: parallelTwoCalls_bothResultsPersisted_protocolConsistent")
    void parallelTwoCalls_bothResultsPersisted_protocolConsistent() {
        toolRegistry.register(echoTool("bash_safe"));

        LlmResponse twoCalls = new LlmResponse(
            "",
            Arrays.asList(call("c00", "bash_safe"), call("c01", "bash_safe")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "done", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(twoCalls, endTurn));
        AgentConfig cfg = defaultConfig(4, 5);
        DefaultSession session = new DefaultSession();
        CapturingSubscriber sink = new CapturingSubscriber();

        TurnContext ctx = new DefaultTurnContext(session, cfg, sink, "AC-052-01 happy path");
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, new AllowAllPermissionPolicy(), pool);

        engine.runTurn(ctx, sink);

        // Protocol invariant: history ends with Assistant(tool_use[c00,c01]) + 2 tool_results
        // whose toolUseIds == [c00, c01] — exactly the Anthropic-spec invariant.
        assertProtocolConsistent(session.history(), Arrays.asList("c00", "c01"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // AC-052-02 — race condition: 1 of 2 calls throws RuntimeException; boundary sentinel
    // substitutes ERROR ToolResult with the *expected* toolUseId, so history is still consistent.
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("AC-052-02: parallelOneCallRace_oneSentinelPersisted")
    void parallelOneCallRace_oneSentinelPersisted() {
        toolRegistry.register(echoTool("ok_tool"));
        toolRegistry.register(throwingTool("race_tool", "synthetic race"));

        LlmResponse twoCalls = new LlmResponse(
            "",
            Arrays.asList(call("r00", "ok_tool"), call("r01", "race_tool")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "done", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(twoCalls, endTurn));
        AgentConfig cfg = defaultConfig(4, 5);
        DefaultSession session = new DefaultSession();
        CapturingSubscriber sink = new CapturingSubscriber();

        TurnContext ctx = new DefaultTurnContext(session, cfg, sink, "AC-052-02 race");
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, new AllowAllPermissionPolicy(), pool);

        engine.runTurn(ctx, sink);

        // History MUST end with 2 tool_results — one success for r00, one ERROR sentinel for r01.
        // Before #052, r01 might be missing (race) → next Anthropic POST 400s.
        List<Message> hist = session.history();
        assertProtocolConsistent(hist, Arrays.asList("r00", "r01"));

        // The sentinel ToolResult for r01 must be ERROR + non-null ToolUseId (boundary sentinel contract).
        // Use forward-iteration tail helper (skips the trailing END_TURN Assistant that lands AFTER
        // the tool_results we want to inspect — naive reverse-scan-with-break-on-first-Assistant
        // would land on the END_TURN Assistant and skip past the action-step tail).
        java.util.List<Message.ToolResult> tailResults = tailResultsAfterLastToolUseAssistant(hist);
        Message.ToolResult r01Result = tailResults.get(1);
        assertThat(r01Result.getToolUseId()).isEqualTo("r01");
        assertThat(r01Result.isError()).as("r01 result must be ERROR after race").isTrue();
    }

    // ──────────────────────────────────────────────────────────────────────
    // AC-052-03 — AskUser mode + protocol invariant coexist: sentinel ToolResult for
    // the AskUser-blocked tool stays in history so the next Anthropic POST stays valid.
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("AC-052-03: parallelAskUser_sentinelStillUpserted")
    void parallelAskUser_sentinelStillUpserted() {
        toolRegistry.register(echoTool("ok_tool"));
        toolRegistry.register(echoTool("needs_approval"));

        LlmResponse twoCalls = new LlmResponse(
            "",
            Arrays.asList(call("a00", "ok_tool"), call("a01", "needs_approval")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "done", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(twoCalls, endTurn));
        AgentConfig cfg = defaultConfig(4, 5);
        DefaultSession session = new DefaultSession();

        // Custom sink fires Allow(a01) after 50ms so the AskUser gate resolves cleanly.
        Subscriber<AgentEvent> askSink = new AskUserResolvingSink(
            resolverPool, new Decision.Allow("ok"), 50L);
        @SuppressWarnings({"unchecked", "rawtypes"})
        org.reactivestreams.Subscriber<AgentEvent> typedSink = (Subscriber) askSink;

        TurnContext ctx = new DefaultTurnContext(session, cfg, typedSink, "AC-052-03 ask");
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor,
            askUserPolicyFor("needs_approval"), pool, approvalRegistry);

        engine.runTurn(ctx, typedSink);

        // Story #048 sentinel-upsert invariant — only one tool_result per tool_use.id.
        assertProtocolConsistent(session.history(), Arrays.asList("a00", "a01"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // AC-052-04 — cancel BEFORE tool dispatch: substitute CANCELLED tool_results for all calls.
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("AC-052-04: parallelCancelBeforeWait_sentinelPersisted")
    void parallelCancelBeforeWait_sentinelPersisted() {
        // Each tool sleeps 2000ms — long enough that cancel (scheduled to fire 50ms after
        // stream() returns) actually races the dispatchParallel wait. echoTool would finish
        // synchronously and we'd never see the cancel-before-wait branch fire.
        toolRegistry.register(sleepTool("never_runs", 2000L));

        LlmResponse twoCalls = new LlmResponse(
            "",
            Arrays.asList(call("x00", "never_runs"), call("x01", "never_runs")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "aborted", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        AgentConfig cfg = defaultConfig(4, 5);
        DefaultSession session = new DefaultSession();
        CapturingSubscriber sink = new CapturingSubscriber();

        TurnContext ctx = new DefaultTurnContext(session, cfg, sink, "AC-052-04 cancel-before-wait");
        // CancelFiringLlmProvider fires ctx.cancellation() DURING the stream() call — AFTER the
        // loop-head cancel check at L290-298 has fired (and so the engine has already emitted
        // Assistant(tool_use) into history), but BEFORE dispatchParallel's wait. That is the
        // exact race window the cancel-before-wait branch at L556-560 exists to handle.
        EchoLlmProvider llm = new CancelFiringLlmProvider(
            Arrays.asList(twoCalls, endTurn), ctx.cancellation(), resolverPool);
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, new AllowAllPermissionPolicy(), pool);

        engine.runTurn(ctx, sink);

        // Even when cancelled before wait, history MUST end with 2 tool_results whose
        // toolUseIds match the LLM-emitted tool_use ids. Otherwise the next Anthropic
        // POST 400s with "orphan tool_use".
        assertProtocolConsistent(session.history(), Arrays.asList("x00", "x01"));

        // Story #052 contract — every tool_result must have a non-null matching toolUseId
        // AND be marked isError()=true (because the tool never actually ran). The exact
        // cancel path that fires (dispatchParallel's cancel-before-wait at L556-558 vs
        // waitForTool's poll-loop interrupt) is timing-dependent and an implementation
        // detail — both are valid protocol-consistent cancellations. assertProtocolConsistent
        // above already locks the protocol-level invariant; this verifies the cancel-level
        // invariant too (no spurious SUCCESS results leaking through).
        java.util.List<Message.ToolResult> tailResults =
            tailResultsAfterLastToolUseAssistant(session.history());
        assertThat(tailResults).as("cancel-before-wait must produce 2 tool_results").hasSize(2);
        for (Message.ToolResult tr : tailResults) {
            assertThat(tr.getToolUseId()).as("each cancelled tool_result must keep its expected id").startsWith("x");
            assertThat(tr.isError()).as("cancelled tool_result must be isError()=true").isTrue();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // AC-052-05 — 1 of 2 tools exceeds toolTimeoutSeconds: 1 success + 1 CANCELLED sentinel.
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("AC-052-05: parallelTimeout_sentinelPersisted")
    void parallelTimeout_sentinelPersisted() throws InterruptedException {
        // Slow tool exceeds 1-second timeout; fast tool completes normally.
        toolRegistry.register(sleepTool("slow_tool", 6000L));
        toolRegistry.register(sleepTool("fast_tool", 100L));

        LlmResponse twoCalls = new LlmResponse(
            "",
            Arrays.asList(call("t00", "slow_tool"), call("t01", "fast_tool")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "partial", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(twoCalls, endTurn));
        AgentConfig cfg = defaultConfig(4, 1);     // 1-second tool timeout
        DefaultSession session = new DefaultSession();
        CapturingSubscriber sink = new CapturingSubscriber();

        TurnContext ctx = new DefaultTurnContext(session, cfg, sink, "AC-052-05 timeout");
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, new AllowAllPermissionPolicy(), pool);

        engine.runTurn(ctx, sink);

        // History must still end with 2 tool_results — timeout produces a CANCELLED ToolResult
        // via waitForTool's cancel-check loop, NOT a missing tool_result.
        assertProtocolConsistent(session.history(), Arrays.asList("t00", "t01"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // AC-052-06 — all calls cancelled: substitute CANCELLED tool_results, one per tool_use.id.
    // ──────────────────────────────────────────────────────────────────────
    @Test
    @DisplayName("AC-052-06: parallelAllCancelled_protocolConsistent")
    void parallelAllCancelled_protocolConsistent() {
        toolRegistry.register(echoTool("never_runs_again"));

        LlmResponse threeCalls = new LlmResponse(
            "",
            Arrays.asList(call("k00", "never_runs_again"),
                          call("k01", "never_runs_again"),
                          call("k02", "never_runs_again")),
            StopReason.TOOL_USE,
            Usage.zero());
        LlmResponse endTurn = new LlmResponse(
            "aborted", Collections.<ToolCall>emptyList(), StopReason.END_TURN, Usage.zero());

        AgentConfig cfg = defaultConfig(4, 5);
        DefaultSession session = new DefaultSession();
        CapturingSubscriber sink = new CapturingSubscriber();

        TurnContext ctx = new DefaultTurnContext(session, cfg, sink, "AC-052-06 all cancelled");
        // Same CancelFiringLlmProvider trick as AC-052-04: fire cancel during stream() so the
        // dispatchParallel cancel-before-wait branch handles 3 parallel calls.
        EchoLlmProvider llm = new CancelFiringLlmProvider(
            Arrays.asList(threeCalls, endTurn), ctx.cancellation(), resolverPool);
        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, new AllowAllPermissionPolicy(), pool);

        engine.runTurn(ctx, sink);

        // 3 tool_use blocks must map to 3 tool_result blocks — all CANCELLED.
        assertProtocolConsistent(session.history(), Arrays.asList("k00", "k01", "k02"));
    }

    /**
     * Story #030+#048 — AskUserResolvingSink helper, inlined here so the test file
     * is self-contained. Schedules the continuation to fire with the supplied
     * {@link Decision} after {@code fireAfterMs}, so the engine's
     * {@code decisionFuture.get()} can actually unblock.
     */
    private static class AskUserResolvingSink implements Subscriber<AgentEvent> {
        private final List<AgentEvent> events =
            Collections.synchronizedList(new java.util.ArrayList<AgentEvent>());
        private final ExecutorService resolver;
        private final Decision decision;
        private final long fireAfterMs;
        private final AtomicReference<java.util.function.Consumer<Decision>> capturedCont =
            new AtomicReference<java.util.function.Consumer<Decision>>();

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
                capturedCont.set(cont);
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

    /**
     * Story #052 — LLM provider that fires {@link CancellationToken#fire()} the instant
     * {@code stream()} is called, then delegates the scripted response to the parent
     * {@link EchoLlmProvider}.
     *
     * <p>This intentionally places the cancel signal <em>after</em> the engine has passed
     * its loop-head cancel check (L290-298 in {@link LinearTurnEngine}) but <em>before</em>
     * the {@code dispatchParallel} wait. That is exactly the race window
     * AC-052-04 / AC-052-06 exercise: the engine emits Assistant(tool_use[c00,c01]) into
     * history, enters {@code dispatchParallel}, sees {@code ctx.cancellation().isCancelled()}
     * = true at the L556-560 cancel-before-wait check, and substitutes CANCELLED
     * {@link ToolResult}s for every call so the next Anthropic POST stays valid.
     *
     * <p>Calling {@code ctx.cancellation().fire()} BEFORE {@code engine.runTurn()} (the
     * naive approach) would instead trip the loop-head check at L290, the engine would
     * emit a CANCELLED event and return without ever entering {@code dispatchParallel},
     * leaving no Assistant(tool_use) in history — that exercises a different code path
     * (the loop-head branch), not the cancel-before-wait branch under test.
     */
    private static class CancelFiringLlmProvider extends EchoLlmProvider {
        private final CancellationToken cancel;
        private final ExecutorService cancelPool;

        CancelFiringLlmProvider(List<LlmResponse> scripted, CancellationToken cancel,
                                ExecutorService cancelPool) {
            super(scripted);
            this.cancel = cancel;
            this.cancelPool = cancelPool;
        }

        @Override
        public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                     Subscriber<? super AgentEvent> sink) {
            // Schedule cancel-fire on a SEPARATE thread with a 50ms sleep BEFORE returning the
            // scripted LlmResponse. This races the cancel against the engine's waitForLlm in a
            // controlled way: the scripted future resolves synchronously, waitForLlm consumes
            // it on the engine's main thread, appendAssistant commits Assistant(tool_use), then
            // dispatchParallel's cancel-before-wait check at L556-560 sees the now-fired token.
            // 🔑 The 50ms sleep is essential — without it, cancel.fire() would happen
            // synchronously inside stream() and waitForLlm's first loop iteration would
            // observe cancelled=true before consuming the future, returning null without ever
            // appending Assistant(tool_use), which would break protocol-consistent assertions.
            cancelPool.submit(() -> {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                cancel.fire();
            });
            return super.stream(prompt, ctx, sink);
        }
    }
}