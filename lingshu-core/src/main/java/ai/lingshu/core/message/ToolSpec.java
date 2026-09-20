package ai.lingshu.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;

/**
 * Provider-agnostic tool schema passed to {@code PromptBuilder.build()} and serialized into
 * provider-specific wire format by {@code LlmProvider.stream} (§4.10).
 *
 * <p>Schema source is {@code Tool.inputSchema()} — see dsh §4.6. Three schema sources are
 * equally valid (hand-written JSON, MCP {@code tools/list}, Spring AI {@code @Tool} reflection),
 * see dsh §6.5. The contract is the same regardless of source.
 */
@Value
public class ToolSpec {
    /** Tool name as seen by the LLM (must equal {@code Tool.name()}). */
    String name;
    /** Human-readable description for the LLM to decide when to call. */
    String description;
    /** JSON Schema (draft 2020-12 / OpenAI compatible) describing the input shape. */
    JsonNode inputSchema;
}