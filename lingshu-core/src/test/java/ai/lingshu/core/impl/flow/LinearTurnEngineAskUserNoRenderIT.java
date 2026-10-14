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
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.PermissionPolicy;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #048 — L3 integration test for AC-048-08
 * {@code e2e_frontendNeverRendersButton_historyProtocolConsistent}.
 *
 * <p><b>Why an IT (not a unit test)</b>: the bug Story #048 fixes spans the
 * full engine turn pipeline. The risk is that the engine's gate unblocks
 * somehow (cancellation, outer tool-dispatch timeout) leaving history with
 * an unmatched {@code tool_use} block — and the next Anthropic POST would
 * 400 with
 * {@code "messages.X: tool_use ids were found without tool_result blocks"}.
 *
 * <p><b>What this test locks</b>:
 * <ol>
 *   <li>The engine's session history, after the AskUser gate commits the
 *       sentinel and unblocks without a human decision (cancel via
 *       {@code ctx.markDone()}), must be <em>protocol-consistent</em>:
 *       every {@code Assistant.toolCalls.id} seen in the current turn
 *       (i.e. bounded by the most recent {@link Message.User} that started
 *       it) has a matching {@link Message.ToolResult} carrying the same
 *       {@code toolUseId}. This is the literal contract Anthropic
 *       enforces — failing this is exactly the HTTP 400 we are trying to
 *       prevent.</li>
 *   <li>The session history must contain exactly one {@link Message.ToolResult}
 *       for the pending {@code tool_use.id} — proving the sentinel was
 *       committed <em>before</em> the gate and the upsert (or the sentinel
 *       itself, if no real result ever arrives) carried through to history.</li>
 *   <li>The {@link ApprovalRegistry} must have a pending entry — proving the
 *       engine actually entered the AskUser branch and registered a
 *       continuation (this guards against silent false-positives where the
 *       engine somehow skips the AskUser path).</li>
 * </ol>
 *
 * <p>This test mirrors the structure of {@code LinearTurnEngineAskUserTest}
 * (EchoLlmProvider + RecordingPromptBuilder) so the engine is driven
 * synchronously and the AskUser branch is deterministically reachable. The
 * sink is capture-only — it never invokes the continuation, simulating a
 * frontend that never rendered the Allow/Deny buttons. After 300 ms a
 * daemon thread invokes {@code ctx.markDone()}, the engine's loop-head
 * cancellation check observes this, and the turn completes via the cancel
 * path.
 */
@DisplayName("Story #048 — AC-048-08: e2e_frontendNeverRendersButton_historyProtocolConsistent")
class LinearTurnEngineAskUserNoRenderIT {

    private ExecutorService pool;
    private ExecutorService resolverPool;
    private DefaultToolRegistry toolRegistry;
    private DefaultToolExecutor toolExecutor;
    private ApprovalRegistry approvalRegistry;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        approvalRegistry = new ApprovalRegistry();
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "lingshu-it-" + System.nanoTime());
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
            approvalTimeoutSec,             // 🆕 Story #030 — 0 = indefinite (Claude Code overnight parity)
            0, 0,                           // turn / llm timeout
            10,                             // reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null, AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    private static ToolCall call(String id, String toolName) {
        return new ToolCall(id, toolName, NullNode.getInstance());
    }

    /** PermissionPolicy stub that returns {@link Decision.AskUser} for a specific tool name. */
    private static PermissionPolicy askUserPolicyFor(final String askToolName) {
        return new PermissionPolicy() {
            @Override
            public Decision check(ToolCall call,
                                  ai.lingshu.core.slot.ToolExecutionContext ctx) {
                if (askToolName.equals(call.getName())) {
                    return new Decision.AskUser(
                        "permission required for '" + call.getName() + "'",
                        Collections.<Decision.Option>emptyList());
                }
                return new Decision.Allow("ok");
            }
        };
    }

    /**
     * Capture-only sink: records every {@link AgentEvent} it receives but
     * <em>never</em> invokes the {@link AgentEvent.ApprovalRequired}
     * continuation. This is the worst-case "frontend never rendered Allow/Deny
     * buttons" scenario the sentinel+upsert must survive.
     */
    private static class CaptureOnlySink implements Subscriber<AgentEvent> {
        private final List<AgentEvent> events =
            Collections.synchronizedList(new ArrayList<AgentEvent>());

        @Override public void onSubscribe(org.reactivestreams.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override public void onNext(AgentEvent event) {
            events.add(event);
            // Deliberately do NOT invoke continuation.
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
     * Walk the current turn's history slice (from the most recent
     * {@link Message.User} back to the end) and verify the
     * {@code Assistant.toolCalls[].id → ToolResult.toolUseId} pairing is
     * complete: every tool_use id emitted in the current turn must have a
     * matching tool_result in the same slice. This is the literal contract
     * Anthropic enforces — failing this is exactly the HTTP 400 we are
     * trying to prevent.
     *
     * <p>The slice is bounded by the most recent {@link Message.User} so
     * older turns carrying the same {@code toolUseId} (uniqueness not
     * guaranteed across turns) are not considered. This mirrors the
     * bounded scan in {@code DefaultTurnContext.appendToolResult}.
     */
    private static void assertCurrentTurnHistoryIsProtocolConsistent(List<Message> hist) {
        // Find the most recent User message — defines the current turn's
        // start boundary. If there is no User in history (e.g. very first
        // turn), the whole history is the current turn.
        int userStart = 0;
        for (int i = hist.size() - 1; i >= 0; i--) {
            if (hist.get(i) instanceof Message.User) {
                userStart = i;
                break;
            }
        }
        Set<String> toolUseIds = new HashSet<String>();
        Set<String> toolResultIds = new HashSet<String>();
        for (int i = userStart; i < hist.size(); i++) {
            Message m = hist.get(i);
            if (m instanceof Message.Assistant) {
                List<ToolCall> tcs = ((Message.Assistant) m).getToolCalls();
                if (tcs != null) {
                    for (ToolCall tc : tcs) {
                        if (tc.getId() != null && !tc.getId().isEmpty()) {
                            toolUseIds.add(tc.getId());
                        }
                    }
                }
            } else if (m instanceof Message.ToolResult) {
                String id = ((Message.ToolResult) m).getToolUseId();
                if (id != null && !id.isEmpty()) {
                    toolResultIds.add(id);
                }
            }
        }
        Set<String> unmatched = new HashSet<String>(toolUseIds);
        unmatched.removeAll(toolResultIds);
        assertThat(unmatched)
            .as("current-turn history must have every tool_use paired with a "
                + "matching tool_result — otherwise the next Anthropic POST "
                + "would 400 with 'tool_use ids were found without tool_result "
                + "blocks'. toolUseIds=%s toolResultIds=%s history=%s",
                toolUseIds, toolResultIds, hist)
            .isEmpty();
    }

    /**
     * AC-048-08 — {@code e2e_frontendNeverRendersButton_historyProtocolConsistent}.
     *
     * <p>Setup: a tool call to {@code "bash_safe"} fires through the engine.
     * {@link #askUserPolicyFor(String)} returns {@link Decision.AskUser} for it.
     * The engine commits the sentinel {@code tool_result} for that
     * {@code tool_use.id="c1"} (Story #048 Phase 3), emits an
     * {@link AgentEvent.ApprovalRequired} event with the continuation
     * registered in {@link ApprovalRegistry}, and then blocks on the
     * {@code CompletableFuture<Decision>}.
     *
     * <p>The sink is capture-only — it never invokes the continuation,
     * simulating a frontend that never rendered the Allow/Deny buttons.
     *
     * <p>After 300 ms, a daemon thread invokes {@code ctx.markDone()}, which
     * the engine's loop-head cancellation check observes, causing the turn to
     * complete via the cancel path.
     *
     * <p>Assertions:
     * <ol>
     *   <li>The current-turn history slice is protocol-consistent — every
     *       {@code tool_use.id} emitted has a matching {@code tool_result}
     *       carrying the same id. If Anthropic's real server had been on the
     *       other end, it would not have returned HTTP 400.</li>
     *   <li>Session history carries exactly one {@link Message.ToolResult}
     *       paired to {@code tool_use.id="c1"} — sentinel+upsert contract held
     *       even when the gate never resolved (worst case).</li>
     *   <li>Session history carries an Assistant turn whose
     *       {@code toolCalls[0].id == "c1"} — proving the sentinel's
     *       {@code toolUseId} is correctly paired with a real
     *       {@code tool_use}.</li>
     *   <li>{@link ApprovalRegistry#size()} ≥ 1 — proving the engine
     *       actually entered the AskUser branch and registered a
     *       continuation (this is the bug precondition: if this were 0, the
     *       engine would never have hit the AskUser path and the test would
     *       be vacuous).</li>
     * </ol>
     */
    @Test
    @DisplayName("AC-048-08: e2e_frontendNeverRendersButton_historyProtocolConsistent")
    void e2e_frontendNeverRendersButton_historyProtocolConsistent() throws InterruptedException {
        toolRegistry.register(new SleepTool("bash_safe", 10));

        PermissionPolicy policy = askUserPolicyFor("bash_safe");
        LlmResponse toolCallResponse = new LlmResponse(
            "", Arrays.asList(call("c1", "bash_safe")),
            StopReason.TOOL_USE, Usage.zero());
        // Second canned response is unused (engine cancels before reaching
        // it) but is required by EchoLlmProvider's non-empty-scripted invariant
        // if the engine somehow looped back. Keep it for clarity.
        LlmResponse endTurn = new LlmResponse(
            "ok", Collections.<ToolCall>emptyList(),
            StopReason.END_TURN, Usage.zero());
        EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(toolCallResponse, endTurn));

        LinearTurnEngine engine = new LinearTurnEngine(
            new RecordingPromptBuilder(), llm, toolExecutor, policy, pool, approvalRegistry);

        // approvalTimeoutSec=0 → DefaultApprovalGate.ask() blocks indefinitely
        // (Claude Code overnight parity — never auto-denies on its own).
        AgentConfig cfg = defaultConfig(0);
        DefaultSession session = new DefaultSession();
        CaptureOnlySink sink = new CaptureOnlySink();
        TurnContext ctx = new DefaultTurnContext(session, cfg, sink, "run a safe command");

        // Schedule ctx.markDone() after 300 ms — simulates the user clicking
        // "cancel" / shutting down the agent. The engine's loop-head
        // cancellation check will observe this and unblock the gate.
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

        engine.runTurn(ctx, sink);

        List<Message> hist = session.history();

        // Assertion 1: current-turn history is protocol-consistent — the
        // whole point of Story #048. Every tool_use.id in the current turn
        // has a matching tool_result. Anthropic would NEVER 400.
        assertCurrentTurnHistoryIsProtocolConsistent(hist);

        // Assertion 2: session history carries exactly one ToolResult for
        // tool_use.id="c1" — sentinel+upsert contract held even when
        // the gate never resolved (worst case).
        long c1Count = hist.stream()
            .filter(m -> m instanceof Message.ToolResult)
            .map(m -> ((Message.ToolResult) m).getToolUseId())
            .filter(id -> "c1".equals(id))
            .count();
        assertThat(c1Count)
            .as("history must contain exactly one ToolResult for toolUseId=c1 "
                + "(sentinel+upsert, no duplicate, no missing) — otherwise the "
                + "next Anthropic POST would 400 with 'tool_use ids were found "
                + "without tool_result blocks'")
            .isEqualTo(1);

        // Assertion 3: history includes the original Assistant message
        // carrying the tool_use — proving the sentinel's toolUseId matches
        // the Assistant's toolCalls.
        boolean assistantWithC1 = hist.stream()
            .filter(m -> m instanceof Message.Assistant)
            .map(m -> (Message.Assistant) m)
            .flatMap(a -> {
                List<String> ids = new ArrayList<String>();
                if (a.getToolCalls() != null) {
                    for (ToolCall tc : a.getToolCalls()) {
                        ids.add(tc.getId());
                    }
                }
                return ids.stream();
            })
            .anyMatch("c1"::equals);
        assertThat(assistantWithC1)
            .as("history must contain an Assistant turn with a tool_use carrying id=c1 "
                + "(proves the sentinel's toolUseId is correctly paired with a real tool_use)")
            .isTrue();

        // Assertion 4: ApprovalRegistry saw at least one entry — proves the
        // engine actually hit the AskUser branch. (If the engine had skipped
        // AskUser entirely, the test would be vacuous; this assertion guards
        // against silent false-positives.)
        assertThat(approvalRegistry.size())
            .as("ApprovalRegistry must hold ≥1 pending entry — proves the engine "
                + "actually entered the AskUser branch and registered a continuation")
            .isGreaterThanOrEqualTo(1);
    }
}
