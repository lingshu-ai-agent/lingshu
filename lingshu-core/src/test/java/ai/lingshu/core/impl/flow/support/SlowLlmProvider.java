package ai.lingshu.core.impl.flow.support;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import org.reactivestreams.Subscriber;

import java.util.concurrent.CompletableFuture;

/**
 * Story #005 test fixture — {@link LlmProvider} that returns a {@link CompletableFuture}
 * which is only completed when the turn's cancellation token fires (or a default
 * timeout elapses as a safety net).
 *
 * <p>This lets AC-04 black-box tests trigger a real cancellation mid-flight: the engine
 * is blocked inside {@code fut.get(200ms, MILLISECONDS)}; firing the token surfaces
 * cancellation to the engine's loop-head check.
 *
 * <p>Test-only class.
 */
public class SlowLlmProvider implements LlmProvider {

    private final String text;
    private final long maxWaitMs;

    public SlowLlmProvider(String text, long maxWaitMs) {
        this.text = text;
        this.maxWaitMs = maxWaitMs;
    }

    @Override
    public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                Subscriber<? super AgentEvent> sink) {
        CompletableFuture<LlmResponse> fut = new CompletableFuture<>();
        // Register a callback on the cancellation token so Ctrl-C completes the future
        // with a CANCELLED response. If the token never fires within maxWaitMs,
        // complete with END_TURN to keep tests deterministic on slow CI.
        Runnable unregister = ctx.cancellation().onCancel(() -> fut.complete(
            new LlmResponse(text, java.util.Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                StopReason.CANCELLED, Usage.zero())));

        // Safety net: schedule a completion in case cancellation never fires
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(maxWaitMs);
                fut.complete(new LlmResponse(text, java.util.Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                    StopReason.END_TURN, Usage.zero()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "slow-llm-safety-net-" + System.nanoTime());
        t.setDaemon(true);
        t.start();
        return fut;
    }
}
