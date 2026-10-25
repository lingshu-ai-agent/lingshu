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
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.tenant.TenantContext;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.impl.compaction.NullCompactor;
import ai.lingshu.core.impl.compaction.TruncatingCompactor;
import ai.lingshu.core.impl.llm.LlmErrorCodes;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.sessionstore.NullSessionStore;
import ai.lingshu.core.impl.tool.DefaultToolExecutionContext;
import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.slot.ToolExecutor;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Linear ReAct loop — fixed sequence prompt → llm → tool → loop (dsh §6.1).
 *
 * <p>Story #001: single iteration when no tool calls (demo-empty case).
 * Story #004: full parallel tool dispatch via {@link #dispatchParallel(List, TurnContext, Subscriber)};
 *            the {@code Action} step now actually executes the tools instead of hard-stopping.
 *
 * <p>ReAct loop structure (dsh §6.1):
 * <ol>
 *   <li><b>Thought</b>: {@code promptBuilder.build(ctx)} + {@code llmProvider.stream()}</li>
 *   <li><b>Action</b>: {@code dispatchParallel(toolCalls)} (Story #004) — concurrent execution
 *       bounded by {@code config.toolParallelism}</li>
 *   <li><b>Observation</b>: results appended to history in LLM-return order</li>
 *   <li>Loop back to step 1 (or finish if no tool calls)</li>
 * </ol>
 *
 * <p>Hard invariants (dsh §7.1.3):
 * <ul>
 *   <li>5 final fields are set at construction (T1 in factory lifecycle) and never mutated</li>
 *   <li>{@link #dispatchParallel} preserves LLM-return order, not completion order</li>
 *   <li>Tool exceptions are translated to {@link ToolResult#error} by {@code DefaultToolExecutor},
 *       so {@code dispatchParallel} only sees {@link ToolResult} values — no need for try-catch
 *       around {@code toolExecutor.dispatch}</li>
 * </ul>
 *
 * <p>Story #008 (FR-001): ReAct loop 上限守卫 — for-loop 因 {@code step == maxSteps} 自然结束
 * 且最后一次响应仍含 tool calls 时,发射 {@link AgentEvent.MaxStepsExceeded} 结构化事件,
 * 紧接着 {@link AgentEvent.TurnCompleted} 正常 {@code END_TURN} 终止。其他 4 条终止路径
 * (break 无 tool calls / ctx.done() / cancellation / exception)均**不**发 {@code MaxStepsExceeded}。
 *
 * <p>Story #048 — AskUser hang safety (sentinel + upsert). When the permission
 * policy returns {@link Decision.AskUser}, the {@code dispatchWithPolicy}
 * AskUser branch commits a sentinel {@code tool_result} to history
 * <em>before</em> blocking on the approval gate (see the inline comment at L619
 * above). The sentinel carries {@code toolUseId=call.getId()},
 * {@code status=SUCCESS}, {@code isError=false}, and the
 * {@code [PENDING_APPROVAL]} marker as content. When the human's decision
 * (Allow / Deny / {@code [LINGS-P02]} timeout) finally commits the real result,
 * {@link ai.lingshu.core.impl.runtime.DefaultTurnContext#appendToolResult}
 * performs <b>upsert-by-toolUseId</b> (Story #048 Phase 1) so the history tail
 * ends up with a single well-formed {@code assistant(tool_use[id=X]) +
 * tool_result(id=X)} pair. Without this, the next turn's Anthropic POST would
 * 400 with "tool_use ids were found without tool_result blocks" if the frontend
 * never rendered the Allow/Deny buttons.
 *
 * <p><b>🆕 Story #052 — boundary sentinel protocol safety.</b>
 * {@link #dispatchParallel} main loop now enforces an Anthropic protocol
 * invariant: every {@code results[i]} returned must be non-null with
 * {@code getToolUseId() == calls.get(i).getId()}. Concretely, after
 * {@link #waitForTool} returns, the loop checks (1) {@code r == null} (race /
 * SPI bug) and (2) {@code !Objects.equals(expectedId, r.getToolUseId())} (i →
 * toolUseId binding leaked) and substitutes a sentinel
 * {@code ToolResult.error("[LINGS-L04] ...")} via the new {@link
 * #dispatchCancelledResult} private static helper. The sentinel content uses a
 * distinct {@code [LINGS-L04]} prefix (mirror of Story #048's
 * {@code [PENDING_APPROVAL]} + {@code LINGS-P02} pattern) so downstream LLMs
 * see this as a protocol-mismatch fallback rather than a user-initiated cancel.
 * The three inline {@code ToolResult.builder()...} blocks at the cancel /
 * catch / cancel-by-shutdown sites have been collapsed onto
 * {@code dispatchCancelledResult} for parity. The {@link #waitForTool}
 * implementation is unchanged (Story #049 reverse-AC guard); only its Javadoc
 * contract was hardened to make the {@code non-null + toolUseId matches} rule
 * explicit so future SPI implementers cannot violate it without breaking tests.
 *
 * <p><b>Story #046 — session save-after-turn.</b> The 8-arg primary constructor
 * accepts a {@link SessionStore}; after each turn boundary (normal-end,
 * cancel-loop-head, maxSteps guard, outer {@code catch (Throwable)}, and the
 * {@code waitForLlm} cancel tail branch — 5 hooks total) a {@link
 * #trySave(TurnContext)} private guard swallows {@link RuntimeException} so an
 * I/O failure on a completed turn never crashes the engine. See
 * {@code LinearTurnEngineSessionStoreTest} for the full coverage matrix.
 */
public class LinearTurnEngine implements FlowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LinearTurnEngine.class);

    private final PromptBuilder promptBuilder;
    private final LlmProvider llmProvider;
    /** 🆕 Story #004 — Slot 2 outer half; runs the 5-step pipeline per call. */
    private final ToolExecutor toolExecutor;
    /** 🆕 Story #004 — Slot 4; consulted before each tool call (Allow / Deny / AskUser). */
    private final PermissionPolicy permissionPolicy;
    /** 🆕 Story #004 — shared thread pool for {@link CompletableFuture} parallel dispatch. */
    private final ExecutorService toolPool;
    /**
     * 🆕 Story #030 — optional Spring-injected registry that maps
     * {@code approvalId → continuation} so an external HTTP endpoint (e.g.
     * {@code POST /api/approvals/{sessionId}/{approvalId}} in demo-product's
     * {@code ChatController}) can deliver the human's {@link Decision} back to
     * the engine thread. {@code null} is tolerated for test fixtures (Story
     * #001–#029) that drive the engine end-to-end without an HTTP layer — the
     * continuation is still carried on the {@code ApprovalRequired} event so the
     * test can invoke it directly.
     */
    private final ApprovalRegistry approvalRegistry;
    /**
     * 🆕 Story #045 — Slot 6 history compactor. Triggered in {@link #runTurn} before
     * each LLM call when {@link Compactor#shouldCompact(Prompt)} returns {@code true};
     * after {@link Compactor#compact(TurnContext)} we re-build the prompt so the LLM
     * sees the post-compaction history. Defaulted to {@link NullCompactor#INSTANCE}
     * by the legacy constructors so tests/fixtures that pre-date Story #045 keep
     * working unchanged.
     */
    private final Compactor compactor;
    /**
     * 🆕 Story #046 — Slot 5 session persistence. Called at each turn-completion
     * point ({@link #runTurn} loop-head cancel, normal end, exception, waitForLlm
     * cancel) to snapshot the current {@link ai.lingshu.core.runtime.Session} via
     * {@link Session#checkpoint()} and persist it. Defaulted to
     * {@link NullSessionStore#INSTANCE} by the legacy 5/6/7-arg constructors so
     * tests/fixtures that pre-date Story #046 keep working unchanged.
     *
     * <p>Save errors are swallowed (logged at WARN) so the engine never crashes a
     * turn-completion because of a transient persistence failure — the in-flight
     * {@code TurnCompleted} event has already been emitted to the subscriber.
     */
    private final SessionStore sessionStore;
    /**
     * 🆕 Story #050 — per-toolUseId deny-once guard for the AskUser branch
     * (Bug-2). Mutable (NOT final) because it's reset at every
     * {@link #runTurn} start, mirroring Story #008's per-turn reset semantics
     * for the ReAct step counter. The set records every {@code toolUseId}
     * for which the human (or {@code [LINGS-P02]} timeout) returned a Deny;
     * any subsequent re-request for the same id short-circuits to a
     * deterministic error {@link ToolResult} instead of opening another
     * approval gate round-trip — that's the loop-prevention mechanism.
     */
    private final Set<String> attemptedDeny = new HashSet<>();

    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider,
                            ToolExecutor toolExecutor, PermissionPolicy permissionPolicy,
                            ExecutorService toolPool) {
        this(promptBuilder, llmProvider, toolExecutor, permissionPolicy, toolPool,
             null, NullCompactor.INSTANCE, NullSessionStore.INSTANCE);
    }

    /**
     * 🆕 Story #030 — 6-arg constructor that wires the optional
     * {@link ApprovalRegistry}. Production paths (via
     * {@code LinearTurnEngineProvider}) pass a Spring-injected singleton; tests
     * that don't need cross-thread continuation lookup use the 5-arg constructor
     * (registry = null). Since Story #045 this delegates to the 8-arg constructor
     * with {@link NullCompactor#INSTANCE} + {@link NullSessionStore#INSTANCE} so
     * legacy call sites stay compaction-free and persistence-free.
     */
    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider,
                            ToolExecutor toolExecutor, PermissionPolicy permissionPolicy,
                            ExecutorService toolPool,
                            ApprovalRegistry approvalRegistry) {
        this(promptBuilder, llmProvider, toolExecutor, permissionPolicy, toolPool,
             approvalRegistry, NullCompactor.INSTANCE, NullSessionStore.INSTANCE);
    }

    /**
     * 🆕 Story #045 — 7-arg constructor that wires the optional
     * {@link Compactor}. Production paths (via {@code LinearTurnEngineProvider})
     * resolve the {@link Compactor} from the {@code CompactorRouter}; tests that
     * don't exercise compaction use {@code NullCompactor.INSTANCE} or one of the
     * legacy 5/6-arg constructors above. Since Story #046 this delegates to the
     * 8-arg constructor with {@link NullSessionStore#INSTANCE} so legacy call
     * sites stay persistence-free.
     */
    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider,
                            ToolExecutor toolExecutor, PermissionPolicy permissionPolicy,
                            ExecutorService toolPool,
                            ApprovalRegistry approvalRegistry,
                            Compactor compactor) {
        this(promptBuilder, llmProvider, toolExecutor, permissionPolicy, toolPool,
             approvalRegistry, compactor, NullSessionStore.INSTANCE);
    }

    /**
     * 🆕 Story #046 — primary 8-arg constructor that wires the optional
     * {@link SessionStore}. Production paths (via {@code LinearTurnEngineProvider})
     * resolve the {@link SessionStore} from the {@code SessionStoreRouter}; tests
     * that don't exercise persistence use {@link NullSessionStore#INSTANCE} or
     * one of the legacy 5/6/7-arg constructors above.
     */
    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider,
                            ToolExecutor toolExecutor, PermissionPolicy permissionPolicy,
                            ExecutorService toolPool,
                            ApprovalRegistry approvalRegistry,
                            Compactor compactor,
                            SessionStore sessionStore) {
        if (promptBuilder == null) throw new IllegalArgumentException("promptBuilder must not be null");
        if (llmProvider == null) throw new IllegalArgumentException("llmProvider must not be null");
        if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor must not be null");
        if (permissionPolicy == null) throw new IllegalArgumentException("permissionPolicy must not be null");
        if (toolPool == null) throw new IllegalArgumentException("toolPool must not be null");
        if (compactor == null) throw new IllegalArgumentException("compactor must not be null");
        if (sessionStore == null) throw new IllegalArgumentException("sessionStore must not be null");
        this.promptBuilder = promptBuilder;
        this.llmProvider = llmProvider;
        this.toolExecutor = toolExecutor;
        this.permissionPolicy = permissionPolicy;
        this.toolPool = toolPool;
        this.approvalRegistry = approvalRegistry;
        this.compactor = compactor;
        this.sessionStore = sessionStore;
    }

    @Override
    public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
        long start = System.currentTimeMillis();
        int maxSteps = ctx.config().getReactMaxSteps();
        LOG.info("LinearTurnEngine.runTurn start: userInput.len={}, reactMaxSteps={}, provider={}, toolParallelism={}",
            ctx.userInput() == null ? 0 : ctx.userInput().length(),
            maxSteps,
            ctx.config().getLlm().getProvider(),
            ctx.config().getToolParallelism());

        LlmResponse last = null;
        Usage totalUsage = Usage.zero();
        // 🆕 Story #008 (FR-001/FR-003) — ReAct loop 上限守卫:仅当 for-loop 因 step == maxSteps
        // 自然 bound 结束(无 break / cancellation / done / exception 触发)时,标记守卫触发。
        // L177 之后根据 maxStepsHit + last.getToolCalls() 联合判定发 MaxStepsExceeded。
        boolean maxStepsHit = false;
        // 🆕 Story #050 — per-turn reset of the ask-loop deny-once guard set. Mirrors
        // the Story #008 reset pattern: every fresh turn gets a fresh allow-state,
        // so re-asking after a deny within the same turn loops once (the first deny
        // is recorded, the second short-circuits), but a fresh human turn starts
        // with a clean slate. Without this reset the set would leak across turns
        // and block legitimate re-asks in subsequent turns.
        attemptedDeny.clear();

        // 🆕 Story #006 (FR-011) — runtime guard: if yml declares tenants, every turn
        // MUST run inside TenantContext.runAs(...). Without this guard, a caller that
        // forgets to set the tenant would silently mix configurations across tenants
        // — the most insidious form of the bug AC-005 exists to prevent.
        // Single-tenant mode (config.getTenants() == null or !isEnabled()) is exempt.
        if (ctx.config().getTenants() != null
                && ctx.config().getTenants().isEnabled()
                && TenantContext.current() == null) {
            throw new IllegalStateException(
                "LINGS-C02 CONFIG_VALIDATION_FAILED: tenants configured but turn started "
                    + "without TenantContext.runAs — wrap call site in "
                    + "TenantContext.runAs(\"<tenantId>\", () -> ...)");
        }

        try {
            for (int step = 1; step <= maxSteps; step++) {
                if (ctx.done()) {
                    LOG.info("ctx.done() at step={} — aborting loop", step);
                    break;
                }

                // 🆕 Story #005 (FR-006) — cooperative cancellation check at loop head.
                // Polls the shared CancellationToken; if fired (Ctrl-C / shutdown hook /
                // programmatic), exit cleanly with stopReason=CANCELLED. The 200ms AC-04
                // budget is met because each tool poll already exits within its own timeout.
                if (ctx.cancellation().isCancelled()) {
                    LOG.info("cancelled at step={} — emitting CANCELLED", step);
                    ctx.markDone();
                    sink.onNext(new AgentEvent.TurnCompleted(StopReason.CANCELLED, totalUsage));
                    // 🆕 Story #046 — Slot 5 persistence: snapshot the session even on a
                    // mid-loop cancel so the next process / agent can resume from here.
                    trySave(ctx);
                    return;
                }

                sink.onNext(new AgentEvent.ReasoningStarted(step, maxSteps));

                Prompt prompt = promptBuilder.build(ctx);
                LOG.debug("step {}: prompt built — messages={}, tools={}",
                    step, prompt.getMessages().size(), prompt.getTools().size());

                // 🆕 Story #045 (dsh §6.1 L3630-3631) — Compactor turn trigger.
                // After building the prompt we ask the compactor whether the history is
                // already too large to send; if yes we shrink ctx.session().history()
                // and rebuild the prompt so the LLM sees the post-compaction view.
                // The previous prompt reference is discarded — its messages list is
                // stale once the compactor mutates the session.
                if (compactor.shouldCompact(prompt)) {
                    // 🆕 Bug fix 2026-10-25 — measure the real before/after delta so
                    // AgentEvent.Compacted carries an honest payload. The pre-payload
                    // event ("presence is the signal") forced host UIs to fabricate the
                    // numbers — demo-product's R-1 workaround hardcoded
                    // afterSize=beforeSize / approxTokensFreed=0, so every compaction
                    // looked like a no-op on the wire.
                    List<Message> historyBefore = ctx.session().history();
                    int tokensBefore = TruncatingCompactor.estimateTokens(historyBefore);
                    LOG.info("step {}: compactor triggered — compacting history (current messages={})",
                        step, historyBefore.size());
                    compactor.compact(ctx);
                    prompt = promptBuilder.build(ctx);
                    List<Message> historyAfter = ctx.session().history();
                    int tokensAfter = TruncatingCompactor.estimateTokens(historyAfter);
                    LOG.info("step {}: compactor done — history {} → {} messages",
                        step, historyBefore.size(), historyAfter.size());
                    // Reuse the existing AgentEvent.Compacted signal (story #001 originally
                    // left it as the "presence is the signal" marker); subscribers (e.g.
                    // demo-product SSE) can flush UI state when this fires.
                    sink.onNext(new AgentEvent.Compacted(
                        historyBefore.size(),
                        historyAfter.size(),
                        Math.max(0, tokensBefore - tokensAfter)));
                }

                // Stream the LLM call; future completes with the final structured response.
                CompletableFuture<LlmResponse> fut = llmProvider.stream(prompt, ctx, sink);
                LlmResponse resp;
                try {
                    // 🆕 Story #005 (FR-007) — short-poll fallback to honour the AC-04 200ms
                    // budget even if the LLM provider never completes. We re-check the
                    // cancellation token between polls; on cancel we interrupt the LLM
                    // future and emit CANCELLED.
                    resp = waitForLlm(fut, ctx, sink, totalUsage);
                    if (resp == null) {
                        // Cancellation surfaced via waitForLlm — already emitted.
                        return;
                    }
                } catch (RuntimeException cancelAlreadyHandled) {
                    // waitForLlm wraps cancellation as a sentinel; bubble up to outer catch
                    // which will emit ERROR + a CANCELLED follow-up would be wrong.
                    throw cancelAlreadyHandled;
                }

                // Record the assistant turn in history.
                // 🆕 Story #027a — forward `resp.getToolCalls()` so the assistant message
                // persisted in session history carries every `tool_use` block from the
                // Anthropic response. Without this, the next request's `messages[]` cannot
                // echo back the `tool_use`/`tool_result` pairing that Anthropic requires
                // (see dsh §6.5 (1.5) protocol gap), and Tool-use turns replay as text-only
                // after the first ReAct step, breaking the round-trip.
                //
                // 🆕 Bug fix 2026-10-17 — MAX_TOKENS empty-text sanitization.
                // Anthropic protocol hard invariant: every `messages[]` entry MUST have
                // non-empty content. When the model hits max-tokens mid-stream without
                // emitting any text (rare but reproducible with very long tool-call-only
                // turns), `resp.getText()` is empty/whitespace. Forwarding that as-is to
                // history causes the next turn's POST to fail HTTP 400:
                //   `messages.X: all messages must have non-empty content`
                // and the entire session cascade-breaks. Sanitize at the boundary:
                // if stopReason == MAX_TOKENS and text is blank, substitute a short
                // sentinel so Anthropic accepts the message AND the model sees a clear
                // hint on the next round to shorten the request.
                String persistText = resp.getText();
                if (resp.getStopReason() == StopReason.MAX_TOKENS
                    && (persistText == null || persistText.trim().isEmpty())) {
                    persistText = "[LLM_OUTPUT_TRUNCATED — response hit max-tokens limit; "
                        + "consider sending a shorter request next turn]";
                    LOG.warn("MAX_TOKENS produced empty Assistant text — substituting "
                        + "sentinel marker to satisfy Anthropic protocol invariant "
                        + "messages.X: all messages must have non-empty content");
                }
                ctx.appendAssistant(persistText, resp.getToolCalls(), resp.getUsage());
                totalUsage = totalUsage.plus(resp.getUsage());
                last = resp;

                // Finish branch: no tool calls → end loop.
                if (resp.getToolCalls() == null || resp.getToolCalls().isEmpty()) {
                    LOG.debug("step {}: no tool calls — ending loop", step);
                    break;
                }

                // 🆕 Story #004 — Action step: parallel tool dispatch with semaphore-bound concurrency.
                ToolResult[] results = dispatchParallel(resp.getToolCalls(), ctx, sink);

                // Observation: append results in LLM-return order (preserves reasoning context).
                for (int i = 0; i < results.length; i++) {
                    ctx.appendToolResult(results[i]);
                }
                sink.onNext(new AgentEvent.ObservationAppended(step, results.length));
                // 🆕 Story #008 (FR-001/FR-003) — 仅当 step == maxSteps 且 for-loop 没因 break 退出时,
                // 守卫触发。注意:此分支在 L162-165 break 之后,所以 step == maxSteps 但 break 提前退出
                // 的情形 maxStepsHit 永远是 false(EC-7)。
                if (step == maxSteps) {
                    maxStepsHit = true;
                }
            }

            // 🆕 Story #008 (FR-001/FR-003) — max-steps 守卫发射:仅当 (a) for-loop 自然 bound 结束
            // (无 break / cancel / done / exception)+ (b) 最后一次 LLM 响应仍含 tool calls 时发射。
            // StopReason 仍为 END_TURN(FR-004),totalUsage 同一对象引用(FR-002 + NFR-002)。
            if (maxStepsHit
                && last != null
                && last.getToolCalls() != null
                && !last.getToolCalls().isEmpty()) {
                sink.onNext(new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage));
            }

            StopReason reason = (last != null && last.getStopReason() != null)
                ? last.getStopReason()
                : StopReason.END_TURN;
            sink.onNext(new AgentEvent.TurnCompleted(reason, totalUsage));
            // 🆕 Story #046 — Slot 5 persistence: snapshot the session at normal turn
            // completion so the next turn (or post-restart resume) can pick up exactly
            // where this one left off.
            trySave(ctx);
            LOG.info("LinearTurnEngine.runTurn done in {}ms, reason={}, totalUsage=({}, {})",
                System.currentTimeMillis() - start, reason,
                totalUsage.getInputTokens(), totalUsage.getOutputTokens());
        } catch (RuntimeException ex) {
            LOG.error("LinearTurnEngine.runTurn failed", ex);
            sink.onNext(new AgentEvent.ErrorEvent(ex));
            sink.onNext(new AgentEvent.TurnCompleted(StopReason.ERROR, totalUsage));
            // 🆕 Story #046 — Slot 5 persistence: snapshot the session even on a turn
            // exception. The history up to the failing step is what the user expects to
            // see on resume; we deliberately persist *before* the exception escapes so
            // the post-mortem snapshot survives a JVM kill.
            trySave(ctx);
        }
    }

    /**
     * 🆕 Story #005 (FR-007) — poll the LLM future with a short 200ms window so that
     * cooperative cancellation interrupts the turn within the AC-04 budget. Returns
     * the {@link LlmResponse} on success, or {@code null} if cancellation surfaced
     * (in which case the caller emits CANCELLED + returns).
     *
     * <p>Why 200ms? AC-04 mandates "200ms 内所有 in-flight turn 停止". Tool polls already
     * exit within their own {@code callConfig.timeoutSeconds} window; the LLM stream is
     * the unbounded tail. Polling at 200ms caps worst-case latency between Ctrl-C and
     * turn exit.
     */
    private LlmResponse waitForLlm(CompletableFuture<LlmResponse> fut, TurnContext ctx,
                                   Subscriber<? super AgentEvent> sink, Usage totalUsage) {
        while (!ctx.cancellation().isCancelled()) {
            try {
                return fut.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                // Not an error — re-loop and re-check cancellation. This is the AC-04
                // 200ms pacing point.
                continue;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("LLM call interrupted", ie);
            } catch (ExecutionException ee) {
                throw new RuntimeException("LLM call failed: " + ee.getCause(), ee.getCause());
            }
        }
        // Cancellation detected — emit CANCELLED and signal caller to return.
        LOG.info("LLM wait loop exited via cancellation");
        ctx.markDone();
        sink.onNext(new AgentEvent.TurnCompleted(StopReason.CANCELLED, totalUsage));
        // 🆕 Story #046 — Slot 5 persistence: snapshot on cancel-during-LLM-wait so
        // the in-flight assistant message that's already in history (Step 266) plus
        // whatever tool results landed earlier are preserved on the next start.
        trySave(ctx);
        return null;
    }

    /**
     * 🆕 Story #046 — Persist the current session via the wired {@link SessionStore}.
     *
     * <p>Called from each of the four turn-completion points in {@link #runTurn}:
     * loop-head cancel, normal end, exception, and waitForLlm cancel. Failures are
     * logged at WARN and swallowed — the in-flight {@code TurnCompleted} event has
     * already reached the subscriber and the engine must never crash a turn on a
     * transient persistence error (e.g. full disk, lost network on remote stores).
     *
     * <p>The {@link ai.lingshu.core.runtime.Session#checkpoint()} call captures the
     * current history snapshot at this instant; concurrent compaction that lands
     * after the snapshot will be reflected in the next turn's checkpoint, never in
     * this one. Mirror of {@code Compactor.compact}'s "snapshot semantics".
     */
    private void trySave(TurnContext ctx) {
        try {
            Checkpoint cp = ctx.session().checkpoint();
            sessionStore.save(cp);
            LOG.debug("session checkpoint saved: sessionId={}, historySize={}",
                cp.getSessionId(), cp.getHistory().size());
        } catch (RuntimeException ex) {
            LOG.warn("session checkpoint save failed (sessionId={}): {}",
                ctx.session().id(), ex.toString());
        }
    }

    /**
     * Execute a batch of tool calls in parallel, bounded by {@code ctx.config().getToolParallelism()}.
     *
     * <p>Concurrency rules (dsh §6.1 L3672-3709 + FR-002):
     * <ul>
     *   <li>{@code parallelism == 1} → Semaphore(1) → strict serial execution</li>
     *   <li>{@code parallelism > 1} → Semaphore(N) → up to N concurrent</li>
     *   <li>{@code parallelism <= 0} → no Semaphore → unbounded (all concurrent)</li>
     * </ul>
     *
     * <p>Result order (FR-003): the returned array preserves the LLM-return order of
     * {@code calls}, NOT the completion order. {@code results[i]} always corresponds to
     * {@code calls.get(i)} — even if it completed after {@code results[i+1]}.
     *
     * <p>Error handling: per-tool failures (timeout, exception, etc.) are translated to
     * {@link ToolResult#error} entries — the loop is never crashed by a single bad call.
     */
    ToolResult[] dispatchParallel(List<ToolCall> calls, TurnContext ctx,
                                  Subscriber<? super AgentEvent> sink) {
        if (calls == null || calls.isEmpty()) {
            return new ToolResult[0];
        }

        final int parallelism = ctx.config().getToolParallelism();
        final int timeoutSec = ctx.config().getToolTimeoutSeconds();
        final Semaphore sem = (parallelism > 0) ? new Semaphore(parallelism) : null;

        @SuppressWarnings("unchecked")
        CompletableFuture<ToolResult>[] futures = new CompletableFuture[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            final ToolCall call = calls.get(i);
            futures[i] = CompletableFuture.supplyAsync(() -> {
                if (sem != null) {
                    sem.acquireUninterruptibly();
                }
                try {
                    ToolResult r = dispatchWithPolicy(call, ctx, sink);
                    sink.onNext(new AgentEvent.ToolCompleted(r));
                    return r;
                } finally {
                    if (sem != null) {
                        sem.release();
                    }
                }
            }, toolPool);
        }

        ToolResult[] results = new ToolResult[calls.size()];
        for (int i = 0; i < calls.size(); i++) {
            // 🆕 Story #005 (FR-008) — poll cancellation before waiting on each future.
            // Honours the shared token: if Ctrl-C fires mid-batch, cancel the in-flight
            // future and substitute a CANCELLED ToolResult so the loop can exit cleanly.
            //
            // 🆕 Story #052 — hoist currentCall + expectedId to loop-local finals so the
            // boundary sentinel below can substitute an error with the *correct* toolUseId
            // even when the inner async task returns null or a wrong-id result. Without
            // this hoist the sentinel would have to call calls.get(i).getId() inline,
            // blocking the same null/wrong-id bug we'd otherwise be vulnerable to.
            final ToolCall currentCall = calls.get(i);
            final String expectedId = currentCall.getId();
            if (ctx.cancellation().isCancelled()) {
                futures[i].cancel(true);
                results[i] = dispatchCancelledResult(expectedId, "cancelled before tool dispatch");
                continue;
            }
            try {
                // 🆕 Story #005 (FR-008) — polling wait: 200ms slices honour AC-04 budget
                // even if the tool never returns. Without polling, the 5s default tool
                // timeout would block the cancel-exit path beyond 200ms.
                ToolResult r = waitForTool(futures[i], ctx, timeoutSec, expectedId);
                // 🆕 Story #052 — boundary sentinel: substitute an ERROR ToolResult with
                // the expected toolUseId when the inner async task returns null or a
                // result whose toolUseId does not match the LLM-return order FR-003
                // invariant. Without this, the next Anthropic POST 400s with
                // "tool_use ids were found without tool_result blocks" because history
                // ends up with an Assistant(tool_use) → ?? gap. Uses Objects.equals
                // for null-safety (Plan D-5 / R-RAC-2).
                if (r == null) {
                    LOG.warn("dispatchParallel: waitForTool returned null for callId={} — substituting sentinel",
                        expectedId);
                    r = dispatchCancelledResult(expectedId,
                        "[" + LlmErrorCodes.LINGS_L04 + "] tool dispatch returned null");
                } else if (!Objects.equals(expectedId, r.getToolUseId())) {
                    LOG.warn("dispatchParallel: toolUseId mismatch expected={} actual={} — substituting sentinel",
                        expectedId, r.getToolUseId());
                    r = dispatchCancelledResult(expectedId,
                        "[" + LlmErrorCodes.LINGS_L04 + "] tool dispatch returned wrong toolUseId: "
                            + r.getToolUseId());
                }
                results[i] = r;
            } catch (RuntimeException cancelHandled) {
                // waitForTool returned a CANCELLED ToolResult or threw on interrupt
                // — either way the result is already in the ToolResult.
                results[i] = dispatchCancelledResult(expectedId,
                    "cancelled: " + cancelHandled.getMessage());
            }
        }
        return results;
    }

    /**
     * 🆕 Story #052 — boundary sentinel helper. Collapses the 3 inline
     * {@code ToolResult.builder()} pattern that previously appeared at dispatchParallel's
     * cancel-before-wait branch (L562-567), its RuntimeException catch block (L588-593),
     * and the new r==null / wrong-toolUseId substitution sites. Implementation is identical
     * — ERROR status + {@code isError=true} + the supplied content + the *expected* toolUseId
     * (so history stays protocol-consistent regardless of which boundary fired).
     *
     * <p>Marked {@code static} because it touches no instance state — the result is
     * fully determined by its arguments. Static-ness also makes it trivially callable
     * from the r==null / wrong-toolUseId substitutions above which themselves are
     * pure expressions.
     */
    private static ToolResult dispatchCancelledResult(String toolUseId, String content) {
        return ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId(toolUseId)
            .content(content)
            .isError(true)
            .build();
    }

    /**
     * 🆕 Story #005 (FR-008) — poll the tool future with a 200ms slice, also honouring
     * the cancellation token. Returns either the tool's {@link ToolResult} or a
     * CANCELLED ToolResult (no exception escapes). Mirrors {@link #waitForLlm} but
     * uses the tool's own {@code timeoutSec} budget as the upper bound on slice count.
     *
     * <p><b>🆕 Story #052 — strengthened return contract:</b> implementations MUST return
     * a non-null {@link ToolResult} whose {@code toolUseId} equals the supplied
     * {@code toolUseId} argument. The downstream boundary sentinel at {@link #dispatchParallel}
     * relies on this invariant to keep Anthropic protocol history consistent — if this
     * contract is violated (e.g. a downstream {@code ToolExecutor} SPI implementation
     * returns {@code null} or a result with a wrong id), the boundary sentinel substitutes
     * an ERROR {@link ToolResult} with {@code [LINGS-L04]} prefix before committing to
     * history. The implementation body below is unchanged from Story #049; only this
     * Javadoc documents the protocol invariant that downstream SPIs must honour.
     */
    private ToolResult waitForTool(CompletableFuture<ToolResult> fut, TurnContext ctx,
                                   int timeoutSec, String toolUseId) {
        long sliceMs = 200L;
        long deadlineMs = (timeoutSec > 0) ? System.currentTimeMillis() + timeoutSec * 1000L : Long.MAX_VALUE;
        while (!ctx.cancellation().isCancelled()) {
            long remaining = deadlineMs - System.currentTimeMillis();
            if (remaining <= 0) {
                fut.cancel(true);
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(toolUseId)
                    .content("tool timeout after " + timeoutSec + "s")
                    .isError(true)
                    .build();
            }
            long thisSlice = Math.min(sliceMs, remaining);
            try {
                return fut.get(thisSlice, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                continue;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                fut.cancel(true);
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(toolUseId)
                    .content("tool interrupted: " + ie.getMessage())
                    .isError(true)
                    .build();
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof java.util.concurrent.CancellationException) {
                    return ToolResult.builder()
                        .status(ToolResult.Status.ERROR)
                        .toolUseId(toolUseId)
                        .content("tool cancelled: " + cause.getMessage())
                        .isError(true)
                        .build();
                }
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(toolUseId)
                    .content("tool error: " + cause)
                    .isError(true)
                    .build();
            }
        }
        // Cancellation detected — cancel the future and return a CANCELLED ToolResult
        fut.cancel(true);
        return ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId(toolUseId)
            .content("tool cancelled by turn shutdown")
            .isError(true)
            .build();
    }

    /**
     * Run a single tool call through the permission policy gate, then dispatch.
     * Translates {@link Decision.Deny} / {@link Decision.AskUser} into {@link ToolResult#error}
     * entries; {@link Decision.Allow} proceeds to {@link ToolExecutor#dispatch(ToolCall, ToolExecutionContext)}.
     */
    private ToolResult dispatchWithPolicy(ToolCall call, TurnContext ctx,
                                          Subscriber<? super AgentEvent> sink) {
        // 🆕 Story #028 — bridge per-turn scope (TurnContext) → per-call sandbox scope
        // (ToolExecutionContext) with the resolved Slot 3 Sandbox. We cast to
        // DefaultTurnContext to read the sandbox (the impl-only accessor stays off the
        // TurnContext interface); the cast is safe because the production path
        // DefaultAgent.buildContext always constructs a DefaultTurnContext.
        // For legacy ctx implementations (4-arg/5-arg ctor sites from Story #005/#006),
        // `runtimeSandbox()` returns null and DefaultToolExecutionContext falls back to
        // its pre-#028 behaviour (default FS + PassThroughHttp stub).
        RuntimeSandbox sandbox = (ctx instanceof DefaultTurnContext)
            ? ((DefaultTurnContext) ctx).runtimeSandbox()
            : null;
        // 🆕 Story #041 — wire the shared ApprovalRegistry so DefaultApprovalGate can
        // register the continuation under the approvalId for HTTP round-trip
        // (e.g. demo-product POST /api/approvals/{sessionId}/{approvalId}). Null
        // is tolerated for legacy tests / fixtures (Story #001–#029) that don't need
        // cross-thread continuation lookup — those drive the round-trip directly off
        // the ApprovalRequired event's continuation field.
        //
        // Also pass the per-call sink so ApprovalRequired events emitted from
        // DefaultApprovalGate reach the user's SSE stream (the runTurn sink), not
        // the per-turn sink on TurnContext — these can differ in test fixtures.
        DefaultToolExecutionContext toolCtx = new DefaultToolExecutionContext(ctx, sandbox, approvalRegistry, sink);
        Decision d = permissionPolicy.check(call, toolCtx);
        if (d instanceof Decision.Allow) {
            // Bridge per-turn scope (TurnContext) → per-call sandbox scope (ToolExecutionContext).
            // The adapter wraps the turn context with the resolved Slot 3 Sandbox for
            // fs/http/process (Story #028); approval/cancellation still delegate to the
            // turn context (Story #005).
            return toolExecutor.dispatch(call, toolCtx);
        }
        if (d instanceof Decision.Deny) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content(((Decision.Deny) d).getReason())
                .isError(true)
                .build();
        }
        if (d instanceof Decision.AskUser) {
            // 🆕 Story #050 — per-toolUseId deny-once guard. If we've recorded a prior
            // deny (or [LINGS-P02] timeout) for this exact toolUseId within the same
            // turn, short-circuit with a deterministic Deny result. Without this guard
            // the LLM can re-request approval with a fresh approvalId every iteration
            // (Anthropic issues a new id on each message_start), and a human who
            // already denied once would be re-prompted indefinitely → the engine never
            // reaches TurnCompleted because each ask-mode loop spawns a new approval
            // round-trip. The set is reset at the head of every runTurn() — see
            // attemptedDeny.clear() in runTurn.
            String toolUseId = call.getId();
            if (attemptedDeny.contains(toolUseId)) {
                LOG.warn("ask-loop guard: toolUseId={} for {} already denied this turn — short-circuiting to Deny",
                    toolUseId, call.getName());
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(toolUseId)
                    .content("[LINGS-P02] tool previously denied for " + call.getName()
                        + " (re-approval denied to avoid infinite ask-loop)")
                    .isError(true)
                    .build();
            }
            // 🆕 Story #041 — delegate to DefaultToolExecutionContext.approval()
            // (formerly inline L467-578, extracted to DefaultApprovalGate). Behaviour
            // matches the prior inline path exactly (verified by AC-041-09/10/11):
            //   * approvalTimeoutSeconds = 0 → wait indefinitely (Claude Code overnight parity)
            //   * approvalTimeoutSeconds > 0 → wait at most N seconds then Deny [LINGS-P02]
            //   * CancellationToken.fire() → interrupt, return Deny [LINGS-P02]
            //   * no sink registered → Deny [LINGS-P02] (no hang)
            // The ApprovalGate SPI is now usable from inside a Tool — see
            // specs/019-built-in-tools/plan.md L498 for the planned Bash "rm -rf"
            // confirm use case.
            //
            // 🆕 Story #048 — sentinel commit BEFORE blocking on ask gate. Prevents
            // Anthropic HTTP 400 "tool_use ids were found without tool_result blocks"
            // when the frontend never renders the Allow/Deny buttons. Without the
            // sentinel, history at end of turn would carry [assistant(tool_use[id=X])]
            // with no matching tool_result — every subsequent turn's Anthropic POST
            // would 400. The sentinel is later upserted in place by
            // DefaultTurnContext.appendToolResult (upsert-by-toolUseId, see Story #048
            // Phase 1) when the real Allow/Deny/LINGS-P02 result commits. Field
            // contract (spec §3.3): toolUseId=call.getId(), status=SUCCESS,
            // isError=false, content="[PENDING_APPROVAL] Awaiting user decision for
            // tool: " + call.getName(). isError=false is intentional: "pending" is
            // not an error, and Anthropic's protocol layer treats isError=true as a
            // tool failure (different semantic from "awaiting response").
            ToolResult sentinel = ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .isError(false)
                .content("[PENDING_APPROVAL] Awaiting user decision for tool: " + call.getName())
                .build();
            ctx.appendToolResult(sentinel);

            Decision resolved = toolCtx.approval().ask((Decision.AskUser) d);

            // Apply the human's response. Allow → proceed (DefaultToolExecutor's
            // defensive re-check returns AskUser again deterministically but
            // Story #030 made that a proceed path; see DefaultToolExecutor.dispatchInternal).
            // Deny → return error with the human-provided reason.
            // AskUser recursion guard (max 3 retries per dsh §9.3) — defensive:
            // DefaultApprovalGate.ask() returns Allow / Deny only, but a future SPI
            // impl might bubble AskUser back, so we cap the recursion here.
            if (resolved instanceof Decision.Allow) {
                return toolExecutor.dispatch(call, toolCtx);
            }
            if (resolved instanceof Decision.Deny) {
                // 🆕 Story #050 — record deny so any re-ask for the same toolUseId
                // (with a fresh approvalId) short-circuits above. Covers both
                // human-deny and [LINGS-P02] timeout (DefaultApprovalGate.ask()
                // returns Decision.Deny for both paths — see Story #041).
                attemptedDeny.add(call.getId());
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content(((Decision.Deny) resolved).getReason())
                    .isError(true)
                    .build();
            }
            // resolved instanceof Decision.AskUser — recursion limited (dsh §9.3 max 3).
            // 🆕 Story #050 — also record this id so the LLM can't keep re-asking
            // via AskUser→AskUser recursion in pathological SPI impls.
            attemptedDeny.add(call.getId());
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("[" + ai.lingshu.core.permission.PermissionErrorCodes.LINGS_P02
                    + "] AskUser recursion limited (max 3 retries)")
                .isError(true)
                .build();
        }
        throw new IllegalStateException("Unknown Decision subtype: " + d.getClass());
    }
}
