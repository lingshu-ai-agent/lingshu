package ai.lingshu.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;

/**
 * A single tool call emitted by the LLM (or synthesized by CLI / user).
 *
 * <p>Three fields are immutable for the lifetime of a turn:
 * <ul>
 *   <li>{@code id} — opaque correlation id, echoed back in {@code Message.ToolResult.toolUseId}.</li>
 *   <li>{@code name} — tool name; must be registered in {@code ToolRegistry}.</li>
 *   <li>{@code input} — JSON args; structure validated against {@code Tool.inputSchema()} inside {@code ToolExecutor}.</li>
 * </ul>
 */
@Value
public class ToolCall {
    /** Opaque id assigned by the LLM provider (or UUID when synthesized locally). */
    String id;
    /** Registered tool name; resolved via {@code ToolRegistry.lookup(name)} in {@code ToolExecutor.dispatch}. */
    String name;
    /** JSON args; must conform to the tool's {@code inputSchema()}. */
    JsonNode input;
}