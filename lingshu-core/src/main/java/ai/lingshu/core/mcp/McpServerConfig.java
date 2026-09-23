package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server runtime configuration (Story #021a, dsh §6.5 (2.1)).
 *
 * <p><b>What</b> — POJO carrying the per-server knobs that {@link McpServerConnection}
 * needs at runtime. Decoupled from {@code AgentConfig.ServerConfig} so that
 * runtime-mutable parameters (heartbeats / backoff caps) can vary independently
 * of the immutable YAML snapshot.
 *
 * <p><b>Default values</b> — 30s heartbeat / 10s ping timeout / 60s reconnect cap.
 * Conservative for a long-lived subprocess; Story #021a tests override these
 * to small values via the 4-arg constructor
 * ({@link StdioMcpServerConnection#StdioMcpServerConnection(McpServerConfig, long, long, long)}).
 *
 * <p><b>JDK 8 compatibility</b> — {@code @Value @Builder @Jacksonized}; no
 * {@code record} / {@code sealed} / {@code List.of} / {@code var}.
 *
 * <p><b>Serialization</b> — {@code @Jacksonized} enables round-trip through
 * Jackson without a Jackson-specific module, which keeps the MCP integration
 * testable from a JSON fixture file (Story #021b will use this).
 */
@Value
@Builder
@Jacksonized
public class McpServerConfig {

    /** Server logical name; required. */
    String name;

    /** Transport flavor; required. */
    McpTransportType transport;

    /** Subprocess executable; required for {@link McpTransportType#STDIO}, otherwise null. */
    String command;

    /** Subprocess arguments; defaults to empty list. */
    @Builder.Default
    List<String> args = new ArrayList<>();

    /** Subprocess environment overrides; defaults to empty map. */
    @Builder.Default
    Map<String, String> env = new HashMap<>();

    /** HTTP endpoint URL; required for SSE / STREAMABLE_HTTP, otherwise null. */
    String url;

    /** Heartbeat probe interval (ms); default {@code 30_000}. */
    @Builder.Default
    long heartbeatIntervalMs = 30_000L;

    /** Heartbeat probe timeout (ms); default {@code 10_000}. */
    @Builder.Default
    long heartbeatTimeoutMs = 10_000L;

    /** Exponential backoff cap (ms); default {@code 60_000}. */
    @Builder.Default
    long reconnectCapMs = 60_000L;
}