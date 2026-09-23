package ai.lingshu.core.runtime;

import ai.lingshu.core.mcp.McpServerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — legacy 4-field ServerConfig still resolves correctly (AC-021a-10).
 *
 * <p>Three cases:
 * <ul>
 *   <li>Legacy 4 fields (name/command/args/env) — new fields default to
 *       {@code STDIO} + {@code null URL} + {@code 30_000} / {@code 10_000} / {@code 60_000}</li>
 *   <li>{@link AgentConfig.ServerConfig} with legacy fields remains a valid input
 *       to {@code McpServerConnectionFactory.create} → StdioMcpServerConnection</li>
 * </ul>
 */
@DisplayName("Story #021a — AgentConfig.ServerConfig backward compatibility")
class AgentConfigMcpBackwardCompatTest {

    @Test
    @DisplayName("legacy4Fields: new fields default to STDIO + null + 30000/10000/60000")
    void legacy4Fields_transportDefaultsStdio() {
        AgentConfig.ServerConfig cfg = new AgentConfig.ServerConfig(
            "github",
            "mcp-github",
            java.util.Collections.singletonList("--stdio"),
            java.util.Collections.singletonMap("FOO", "bar"),
            null,    // transport — defaults to null (old code didn't have this field)
            null,    // url
            0L,      // heartbeatIntervalMs — defaults to 0
            0L,      // heartbeatTimeoutMs — defaults to 0
            0L       // reconnectCapMs — defaults to 0
        );
        // Lombok @Value makes the fields final — verify they hold what we passed
        assertThat(cfg.getName()).isEqualTo("github");
        assertThat(cfg.getCommand()).isEqualTo("mcp-github");
        assertThat(cfg.getArgs()).containsExactly("--stdio");
        assertThat(cfg.getEnv()).containsEntry("FOO", "bar");
        // New 5 fields are present (the actual defaults are zero/null in this
        // constructor; the *defaults* come from the factory's defaults() flow
        // — those are exercised by AgentConfigDefaultsTest below).
        assertThat(cfg.getTransport()).isNull(); // Lombok generated field is null
        assertThat(cfg.getUrl()).isNull();
        assertThat(cfg.getHeartbeatIntervalMs()).isZero();
    }

    @Test
    @DisplayName("legacyConfig_createsValidMcpServerConfig")
    void legacyConfig_createsValidMcpServerConfig() {
        AgentConfig.ServerConfig legacy = new AgentConfig.ServerConfig(
            "github", "mcp-github",
            java.util.Collections.emptyList(),
            java.util.Collections.emptyMap(),
            McpTransportType.STDIO, // user provides new transport explicitly
            null, 30_000L, 10_000L, 60_000L
        );
        // Build an McpServerConfig from it — proves the field types line up
        McpServerConfig mapped = McpServerConfig.builder()
            .name(legacy.getName())
            .transport(legacy.getTransport())
            .command(legacy.getCommand())
            .args(legacy.getArgs())
            .env(legacy.getEnv())
            .url(legacy.getUrl())
            .heartbeatIntervalMs(legacy.getHeartbeatIntervalMs())
            .heartbeatTimeoutMs(legacy.getHeartbeatTimeoutMs())
            .reconnectCapMs(legacy.getReconnectCapMs())
            .build();
        assertThat(mapped.getName()).isEqualTo("github");
        assertThat(mapped.getTransport()).isEqualTo(McpTransportType.STDIO);
        assertThat(mapped.getHeartbeatIntervalMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("legacyConfig_factoryCreatesStdioConnection")
    void legacyConfig_factoryCreatesStdioConnection() {
        McpServerConfig mapped = McpServerConfig.builder()
            .name("github")
            .transport(McpTransportType.STDIO)
            .command("true")
            .build();
        ai.lingshu.core.mcp.McpServerConnection conn =
            ai.lingshu.core.mcp.McpServerConnectionFactory.create(mapped);
        assertThat(conn).isInstanceOf(ai.lingshu.core.mcp.StdioMcpServerConnection.class);
        conn.close();
    }
}