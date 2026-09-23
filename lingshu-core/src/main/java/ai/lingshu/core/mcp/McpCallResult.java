package ai.lingshu.core.mcp;

import lombok.Builder;
import lombok.Value;

/**
 * Minimal MCP tool-call result (Story #021a → #021b).
 *
 * <p>Carries either a successful payload or an error message, never both.
 * Use the static factories {@link #success(String)} and {@link #error(String)}
 * to make the intent explicit at call sites.
 *
 * <p><b>JDK 8 compatibility</b> — {@code @Value @Builder}, no
 * {@code record}/{@code sealed}.
 */
@Value
@Builder
public class McpCallResult {

    /** Tool output (for success). Null when {@link #isError} is true. */
    String content;

    /** Error message (for failure). Null when {@link #isError} is false. */
    String errorMessage;

    /** True iff this result represents a failure (transport / tool error). */
    boolean isError;

    /** Successful result factory. */
    public static McpCallResult success(String content) {
        return McpCallResult.builder().content(content).isError(false).build();
    }

    /** Error result factory. */
    public static McpCallResult error(String errorMessage) {
        return McpCallResult.builder().errorMessage(errorMessage).isError(true).build();
    }
}