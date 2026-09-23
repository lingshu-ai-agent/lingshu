package ai.lingshu.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCP server-connection lifecycle contract (Story #021a, dsh §6.5 (2.1) L4569-4591).
 *
 * <p><b>What</b> — The interface every concrete transport implementation
 * (stdio / SSE / streamable HTTP) must satisfy. The default concrete class
 * is {@link StdioMcpServerConnection}; Story #021c will add SSE and streamable
 * HTTP variants.
 *
 * <p><b>State machine integration</b> — {@link #onStateChange(Consumer)}
 * lets {@code McpTransport} (Story #021b) register a listener so it can
 * register / unregister {@code McpToolAdapter}s on the
 * {@link ai.lingshu.core.slot.ToolRegistry} as the connection comes up
 * and dies.
 *
 * <p><b>callTool contract — non-CONNECTED state returns error, not exception</b> —
 * Aligns with dsh §4.10.1 hard rule 2: tool dispatch never throws — the
 * permission / sandbox / cancel pipeline must always wrap a tool call. If the
 * connection is unhealthy, callers see an {@link McpCallResult#isError()} result
 * rather than an exception.
 *
 * <p><b>Listener exception isolation</b> — If one listener throws, other
 * registered listeners must still be invoked (Story #021a §T-15 verifies).
 *
 * <p><b>JDK 8 compatibility</b> — Eight abstract methods; no default methods
 * (cleaner contract, friendly to JDK 8 compilers).
 */
public interface McpServerConnection extends AutoCloseable {

    /** @return logical name (matches {@link McpServerConfig#getName()}). */
    String name();

    /** @return current lifecycle state. */
    ConnectionState state();

    /**
     * @return timestamp of the last successful heartbeat probe, or null
     *         if no probe has completed yet.
     */
    Instant lastHeartbeatAt();

    /**
     * @return cached list of tools advertised by the server. Empty (never null)
     *         before {@link #start()} reaches {@link ConnectionState#CONNECTED}.
     */
    List<McpToolDescriptor> listTools();

    /**
     * Invoke a tool on the remote server.
     *
     * @param toolName tool name from {@link #listTools()}
     * @param input    JSON input matching the tool's {@link McpToolDescriptor#getInputSchema()}
     * @return success / error result; never null
     * @throws IllegalArgumentException if {@code toolName} is unknown
     */
    McpCallResult callTool(String toolName, JsonNode input);

    /**
     * Register a listener to be notified on every state transition. Multiple
     * listeners are supported. Exceptions thrown by a listener are caught and
     * logged — they do not affect other listeners.
     */
    void onStateChange(Consumer<ConnectionState> listener);

    /**
     * Begin the connect / initialize / tools/list handshake and transition to
     * {@link ConnectionState#CONNECTED} on success, or schedule a reconnect on
     * failure.
     *
     * <p>Idempotent: a second call while already {@code CONNECTED} or
     * {@code RECONNECTING} is a no-op.
     */
    void start();

    /**
     * Tear down the connection. Idempotent — subsequent calls are no-ops.
     * Transitions to {@link ConnectionState#FAILED}.
     */
    @Override
    void close();
}