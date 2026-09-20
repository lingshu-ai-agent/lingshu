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
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutionContext;
import ai.lingshu.core.slot.ToolExecutor;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
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

    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider,
                            ToolExecutor toolExecutor, PermissionPolicy permissionPolicy,
                            ExecutorService toolPool) {
        if (promptBuilder == null) throw new IllegalArgumentException("promptBuilder must not be null");
        if (llmProvider == null) throw new IllegalArgumentException("llmProvider must not be null");
        if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor must not be null");
        if (permissionPolicy == null) throw new IllegalArgumentException("permissionPolicy must not be null");
        if (toolPool == null) throw new IllegalArgumentException("toolPool must not be null");
        this.promptBuilder = promptBuilder;
        this.llmProvider = llmProvider;
        this.toolExecutor = toolExecutor;
        this.permissionPolicy = permissionPolicy;
        this.toolPool = toolPool;
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
                    return;
                }

                sink.onNext(new AgentEvent.ReasoningStarted(step, maxSteps));

                Prompt prompt = promptBuilder.build(ctx);
                LOG.debug("step {}: prompt built — messages={}, tools={}",
                    step, prompt.getMessages().size(), prompt.getTools().size());

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
                ctx.appendAssistant(resp.getText(), resp.getUsage());
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
            }

            StopReason reason = (last != null && last.getStopReason() != null)
                ? last.getStopReason()
                : StopReason.END_TURN;
            sink.onNext(new AgentEvent.TurnCompleted(reason, totalUsage));
            LOG.info("LinearTurnEngine.runTurn done in {}ms, reason={}, totalUsage=({}, {})",
                System.currentTimeMillis() - start, reason,
                totalUsage.getInputTokens(), totalUsage.getOutputTokens());
        } catch (RuntimeException ex) {
            LOG.error("LinearTurnEngine.runTurn failed", ex);
            sink.onNext(new AgentEvent.ErrorEvent(ex));
            sink.onNext(new AgentEvent.TurnCompleted(StopReason.ERROR, totalUsage));
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
        return null;
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
            if (ctx.cancellation().isCancelled()) {
                futures[i].cancel(true);
                results[i] = ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(calls.get(i).getId())
                    .content("cancelled before tool dispatch")
                    .isError(true)
                    .build();
                continue;
            }
            try {
                // 🆕 Story #005 (FR-008) — polling wait: 200ms slices honour AC-04 budget
                // even if the tool never returns. Without polling, the 5s default tool
                // timeout would block the cancel-exit path beyond 200ms.
                results[i] = waitForTool(futures[i], ctx, timeoutSec, calls.get(i).getId());
            } catch (RuntimeException cancelHandled) {
                // waitForTool returned a CANCELLED ToolResult or threw on interrupt
                // — either way the result is already in the ToolResult.
                results[i] = ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(calls.get(i).getId())
                    .content("cancelled: " + cancelHandled.getMessage())
                    .isError(true)
                    .build();
            }
        }
        return results;
    }

    /**
     * 🆕 Story #005 (FR-008) — poll the tool future with a 200ms slice, also honouring
     * the cancellation token. Returns either the tool's {@link ToolResult} or a
     * CANCELLED ToolResult (no exception escapes). Mirrors {@link #waitForLlm} but
     * uses the tool's own {@code timeoutSec} budget as the upper bound on slice count.
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
        // Bridge per-turn scope (TurnContext) → per-call sandbox scope (ToolExecutionContext)
        // once per dispatch — the adapter wraps the turn context with no-op defaults for
        // fs/http/approval/cancellation, deferring the full sandbox to Story #016.
        DefaultToolExecutionContext toolCtx = new DefaultToolExecutionContext(ctx);
        Decision d = permissionPolicy.check(call, toolCtx);
        if (d instanceof Decision.Allow) {
            // Bridge per-turn scope (TurnContext) → per-call sandbox scope (ToolExecutionContext).
            // The adapter wraps the turn context with no-op defaults for fs/http/approval/cancellation,
            // deferring the full sandbox implementation to Story #016.
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
            // Story #005 will replace this stub with the full ApprovalGate flow.
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("AskUser approval flow is wired in Story #005 follow-up")
                .isError(true)
                .build();
        }
        throw new IllegalStateException("Unknown Decision subtype: " + d.getClass());
    }
}
