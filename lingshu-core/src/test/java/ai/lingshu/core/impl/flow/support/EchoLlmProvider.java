package ai.lingshu.core.impl.flow.support;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import org.reactivestreams.Subscriber;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test fixture for Story #004 — programmable {@link LlmProvider} that returns a
 * scripted sequence of {@link LlmResponse} values across multiple {@code stream()}
 * calls within a single turn.
 *
 * <p>Usage:
 * <pre>{@code
 * EchoLlmProvider llm = new EchoLlmProvider(Arrays.asList(
 *     LlmResponse.builder().text("").toolCalls(Arrays.asList(callA, callB)).build(),
 *     LlmResponse.builder().text("done").stopReason(StopReason.END_TURN).build()
 * ));
 * // First stream() returns response #0 (tool calls), second returns response #1 (END_TURN).
 * }</pre>
 *
 * <p>Each {@code stream()} call resolves the future immediately with the next
 * scripted response. If the script is exhausted, the future fails with
 * {@link IllegalStateException} — this surfaces as a test failure rather than
 * silent infinite-loop behavior.
 *
 * <p>Test-only class — never used in production code paths.
 */
public class EchoLlmProvider implements LlmProvider {

    private final List<LlmResponse> scriptedResponses;
    private final AtomicInteger callIndex = new AtomicInteger(0);

    public EchoLlmProvider(List<LlmResponse> scriptedResponses) {
        if (scriptedResponses == null || scriptedResponses.isEmpty()) {
            throw new IllegalArgumentException(
                "EchoLlmProvider requires at least one scripted response");
        }
        this.scriptedResponses = Collections.unmodifiableList(scriptedResponses);
    }

    @Override
    public CompletableFuture<LlmResponse> stream(Prompt prompt, TurnContext ctx,
                                                Subscriber<? super AgentEvent> sink) {
        int idx = callIndex.getAndIncrement();
        if (idx >= scriptedResponses.size()) {
            CompletableFuture<LlmResponse> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException(
                "EchoLlmProvider: scripted responses exhausted (call " + (idx + 1)
                    + ", only " + scriptedResponses.size() + " programmed). "
                    + "This usually means the engine entered an unexpected extra loop."));
            return failed;
        }
        LlmResponse resp = scriptedResponses.get(idx);
        return CompletableFuture.completedFuture(resp);
    }
}
