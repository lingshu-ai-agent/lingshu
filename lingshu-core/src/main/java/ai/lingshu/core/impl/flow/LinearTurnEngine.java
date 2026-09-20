package ai.lingshu.core.impl.flow;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PromptBuilder;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Linear ReAct loop — fixed sequence prompt → llm → tool → loop (dsh §6.1).
 *
 * <p>v1 scope (Story #001):
 * <ul>
 *   <li>Single iteration when there are no tool calls (demo-empty case)</li>
 *   <li>Honors {@code reactMaxSteps} as a safety cap (default 50)</li>
 *   <li>Honors the {@code ctx.done()} flag every iteration</li>
 *   <li>Saves the final history append</li>
 *   <li>Streams {@link AgentEvent.ReasoningStarted} + {@link AgentEvent.TextDelta} + {@link AgentEvent.TurnCompleted}</li>
 * </ul>
 *
 * <p>Tool dispatch (the {@code Action} step) is implemented in Story #004. Story #001
 * passes through the case "no tools" which is the demo-empty default — the loop runs
 * one iteration, gets the assistant text back, and stops with {@link StopReason#END_TURN}.
 */
public class LinearTurnEngine implements FlowEngine {

    private static final Logger LOG = LoggerFactory.getLogger(LinearTurnEngine.class);

    private final PromptBuilder promptBuilder;
    private final LlmProvider llmProvider;

    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider) {
        this.promptBuilder = promptBuilder;
        this.llmProvider = llmProvider;
    }

    @Override
    public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
        long start = System.currentTimeMillis();
        int maxSteps = ctx.config().getReactMaxSteps();
        LOG.info("LinearTurnEngine.runTurn start: userInput.len={}, reactMaxSteps={}, provider={}",
            ctx.userInput() == null ? 0 : ctx.userInput().length(),
            maxSteps,
            ctx.config().getLlm().getProvider());

        LlmResponse last = null;
        Usage totalUsage = Usage.zero();

        try {
            for (int step = 1; step <= maxSteps; step++) {
                if (ctx.done()) {
                    LOG.info("ctx.done() at step={} — aborting loop", step);
                    break;
                }

                sink.onNext(new AgentEvent.ReasoningStarted(step, maxSteps));

                Prompt prompt = promptBuilder.build(ctx);
                LOG.debug("step {}: prompt built — messages={}, tools={}",
                    step, prompt.getMessages().size(), prompt.getTools().size());

                // Stream the LLM call; future completes with the final structured response.
                CompletableFuture<LlmResponse> fut = llmProvider.stream(prompt, ctx, sink);
                LlmResponse resp;
                try {
                    resp = fut.get();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("LLM call interrupted", ie);
                } catch (ExecutionException ee) {
                    throw new RuntimeException("LLM call failed: " + ee.getCause(), ee.getCause());
                }

                // Record the assistant turn in history.
                ctx.appendAssistant(resp.getText(), resp.getUsage());
                totalUsage = totalUsage.plus(resp.getUsage());
                last = resp;

                // Demo path: no tools → loop ends after first iteration.
                if (resp.getToolCalls() == null || resp.getToolCalls().isEmpty()) {
                    LOG.debug("step {}: no tool calls — ending loop", step);
                    break;
                }

                // Tool dispatch path is wired in Story #004. For now we surface the loop as
                // a hard stop so we don't claim a feature that isn't implemented.
                LOG.warn("step {}: model emitted {} tool call(s) — Story #004 wires ToolExecutor dispatch",
                    step, resp.getToolCalls().size());
                break;
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
}