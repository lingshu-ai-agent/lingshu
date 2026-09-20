package ai.lingshu.core.slot;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Slot 2 — A single callable tool. Stateless across calls except for any
 * internal resources the implementation opens in its constructor.
 *
 * <p>Three input-schema sources are valid and indistinguishable at this contract level
 * (dsh §4.6 + §6.5):
 * <ul>
 *   <li>Hand-written JSON (built-in Read / Write / Edit / Bash)</li>
 *   <li>MCP server {@code tools/list} (McpToolAdapter wraps it)</li>
 *   <li>Spring AI {@code @Tool} annotation reflection (schema generation only — execution
 *       still flows through {@link ToolExecutor}, see dsh §4.10.1 硬规则 2)</li>
 * </ul>
 *
 * <p>The {@link ToolExecutor} never calls {@code tool.execute()} directly; it dispatches through
 * the 5-step pipeline (permission → registry lookup → timeout → sandbox → execute → checkpoint).
 * Direct invocation is reserved for tests only.
 */
public interface Tool {

    /** Unique tool name; the LLM sees this in {@code ToolSpec.name}. */
    String name();

    /** Human-readable description; surfaced to the model so it can decide when to call. */
    String description();

    /**
     * JSON Schema (draft 2020-12 / OpenAI function-calling compatible) describing the input shape.
     * Sourced however the implementation wants; returned as a parsed {@code JsonNode}.
     */
    JsonNode inputSchema();

    /**
     * Execute the call. The {@link ai.lingshu.core.slot.ToolExecutionContext} provides sandbox,
     * cancellation token, progress sink, and approval gate. Long-running tools should emit
     * progress events and respect cancellation.
     */
    ToolResult execute(ToolCall call, ToolExecutionContext ctx);
}