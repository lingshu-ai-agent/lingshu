package ai.lingshu.core.message;

import lombok.Builder;
import lombok.Value;

/**
 * Result value object returned from {@link ai.lingshu.core.slot.Tool#execute} and
 * {@link ai.lingshu.core.slot.ToolExecutor#dispatch}.
 *
 * <p>Status semantics (per dsh §4.10.1 硬规则 2):
 * <ul>
 *   <li>{@link Status#SUCCESS} — normal completion, {@code content} is the output</li>
 *   <li>{@link Status#ERROR} — failed but recoverable, {@code content} holds the error message</li>
 *   <li>{@link Status#CANCELLED} — cancellation token fired, no retry</li>
 * </ul>
 *
 * <p>Distinct from {@link Message.ToolResult}, which is a Message subtype that travels
 * through session history. {@code ToolResult} → {@code Message.ToolResult} conversion happens
 * in {@code DefaultTurnContext.appendToolResult}.
 */
@Value
@Builder
public class ToolResult {
    /** Status enum. */
    Status status;
    /** Echoes back {@code ToolCall.id} for correlation in the next LLM message. */
    String toolUseId;
    /** Output text (or error message when {@code status == ERROR}). */
    String content;
    /** Convenience flag — {@code true} iff the tool failed. Mirrors {@code status == ERROR}. */
    boolean isError;

    public enum Status { SUCCESS, ERROR, CANCELLED }
}