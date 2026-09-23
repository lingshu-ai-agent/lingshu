package ai.lingshu.core.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — new MCP ServerConfig fields accept user-provided values (AC-021a-10).
 *
 * <p>Three cases verifying {@code transport}, {@code url}, and
 * {@code heartbeatIntervalMs} overrides flow through {@code ServerConfig}.
 */
@DisplayName("Story #021a — AgentConfig.ServerConfig new fields")
class AgentConfigMcpExpansionTest {

    @Test
    @DisplayName("transport: SSE round-trips through ServerConfig")
    void newFields_transportSse() {
        AgentConfig.ServerConfig cfg = new AgentConfig.ServerConfig(
            "remote", null,
            java.util.Collections.emptyList(),
            java.util.Collections.emptyMap(),
            McpTransportType.SSE, "https://mcp.example.com/sse",
            30_000L, 10_000L, 60_000L
        );
        assertThat(cfg.getTransport()).isEqualTo(McpTransportType.SSE);
        assertThat(cfg.getUrl()).isEqualTo("https://mcp.example.com/sse");
    }

    @Test
    @DisplayName("url: round-trips through ServerConfig")
    void newFields_urlField() {
        AgentConfig.ServerConfig cfg = new AgentConfig.ServerConfig(
            "remote", null,
            java.util.Collections.emptyList(),
            java.util.Collections.emptyMap(),
            McpTransportType.STREAMABLE_HTTP, "https://mcp.example.com/rpc",
            30_000L, 10_000L, 60_000L
        );
        assertThat(cfg.getUrl()).isEqualTo("https://mcp.example.com/rpc");
        assertThat(cfg.getTransport()).isEqualTo(McpTransportType.STREAMABLE_HTTP);
    }

    @Test
    @DisplayName("heartbeatIntervalMs: round-trips through ServerConfig")
    void newFields_heartbeatOverride() {
        AgentConfig.ServerConfig cfg = new AgentConfig.ServerConfig(
            "remote", null,
            java.util.Collections.emptyList(),
            java.util.Collections.emptyMap(),
            McpTransportType.STDIO, null,
            5_000L, 2_000L, 30_000L
        );
        assertThat(cfg.getHeartbeatIntervalMs()).isEqualTo(5_000L);
        assertThat(cfg.getHeartbeatTimeoutMs()).isEqualTo(2_000L);
        assertThat(cfg.getReconnectCapMs()).isEqualTo(30_000L);
    }
}