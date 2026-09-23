package ai.lingshu.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

/**
 * Minimal MCP tool descriptor (Story #021a → #021b).
 *
 * <p>Story #021a defines the type so that {@link McpServerConnection#listTools()}
 * has a non-null return contract. Story #021b adds richer metadata (annotations,
 * examples, JSON Schema validation hooks) without breaking this contract.
 *
 * <p><b>JDK 8 compatibility</b> — {@code @Value @Builder}, no
 * {@code record}/{@code sealed}.
 */
@Value
@Builder
public class McpToolDescriptor {

    /** Tool name as advertised by the MCP server. */
    String name;

    /** Human-readable description. May be null. */
    String description;

    /** JSON Schema for the tool's input. Must be non-null but may be {@code additionalProperties: true}. */
    JsonNode inputSchema;
}