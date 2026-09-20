package ai.lingshu.core.message;

import lombok.Value;

import java.util.List;

/**
 * Final structured response from {@code LlmProvider.stream}, returned via {@code CompletableFuture}
 * while the same provider may concurrently push {@code TextDelta} / {@code ToolStarted} events
 * to the sink (two-channel pattern, see dsh §4.10).
 */
@Value
public class LlmResponse {
    /** Accumulated text from the streaming response (may be empty if model went straight to tool calls). */
    String text;
    /** Tool calls the model wants executed; engine dispatches each via {@code ToolExecutor.dispatch}. */
    List<ToolCall> toolCalls;
    /** Why the model stopped emitting tokens. */
    StopReason stopReason;
    /** Token accounting for this single LLM call. */
    Usage usage;
}