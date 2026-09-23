package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #021c — {@link McpServerConnectionFactory} L2 dispatch tests (AC-021c-1, AC-021c-2).
 *
 * <p>Five cases:
 * <ul>
 *   <li>{@code create_stdio_dispatchReturnsStdioConnection} — STDIO returns
 *       {@link StdioMcpServerConnection} in {@code IDLE} state.</li>
 *   <li>{@code create_sse_dispatchReturnsSseConnection} — SSE returns
 *       {@link SseMcpServerConnection} (was LINGS-M01 throw in Story #021a).</li>
 *   <li>{@code create_streamableHttp_dispatchReturnsStreamableHttpConnection} —
 *       STREAMABLE_HTTP returns {@link StreamableHttpMcpServerConnection}
 *       (was LINGS-M01 throw in Story #021a).</li>
 *   <li>{@code create_name_isPropagated} — name() of the returned instance
 *       equals the cfg name for all three transport flavors.</li>
 *   <li>{@code create_null_throwsIllegalStateException} — null cfg rejected.</li>
 * </ul>
 *
 * <p><b>Note</b> — Story #021c wired up the SSE and streamable-HTTP branches
 * that Story #021a explicitly left as LINGS-M01 throw. The two old
 * {@code create_*_throwsM01} cases are deleted and replaced with the new
 * dispatch-returns cases.
 */
@DisplayName("Story #021c — McpServerConnectionFactory dispatch (3 transports wired)")
class McpServerConnectionFactoryTest {

    private static McpServerConfig stdioCfg() {
        return McpServerConfig.builder()
            .name("github")
            .transport(McpTransportType.STDIO)
            .command("mcp-github")
            .build();
    }

    private static McpServerConfig sseCfg() {
        return McpServerConfig.builder()
            .name("remote-sse")
            .transport(McpTransportType.SSE)
            .url("https://mcp.example.com/sse")
            .build();
    }

    private static McpServerConfig streamCfg() {
        return McpServerConfig.builder()
            .name("remote-stream")
            .transport(McpTransportType.STREAMABLE_HTTP)
            .url("https://mcp.example.com/rpc")
            .build();
    }

    @Test
    @DisplayName("create(STDIO) returns StdioMcpServerConnection in IDLE state")
    void create_stdio_dispatchReturnsStdioConnection() {
        McpServerConnection conn = McpServerConnectionFactory.create(stdioCfg());
        try {
            assertThat(conn).isInstanceOf(StdioMcpServerConnection.class);
            assertThat(conn.state()).isEqualTo(ConnectionState.IDLE);
            assertThat(conn.name()).isEqualTo("github");
        } finally {
            conn.close();
        }
    }

    @Test
    @DisplayName("create(SSE) returns SseMcpServerConnection (was LINGS-M01 throw)")
    void create_sse_dispatchReturnsSseConnection() {
        McpServerConnection conn = McpServerConnectionFactory.create(sseCfg());
        try {
            assertThat(conn).isInstanceOf(SseMcpServerConnection.class);
            assertThat(conn.state()).isEqualTo(ConnectionState.IDLE);
            assertThat(conn.name()).isEqualTo("remote-sse");
        } finally {
            conn.close();
        }
    }

    @Test
    @DisplayName("create(STREAMABLE_HTTP) returns StreamableHttpMcpServerConnection (was LINGS-M01 throw)")
    void create_streamableHttp_dispatchReturnsStreamableHttpConnection() {
        McpServerConnection conn = McpServerConnectionFactory.create(streamCfg());
        try {
            assertThat(conn).isInstanceOf(StreamableHttpMcpServerConnection.class);
            assertThat(conn.state()).isEqualTo(ConnectionState.IDLE);
            assertThat(conn.name()).isEqualTo("remote-stream");
        } finally {
            conn.close();
        }
    }

    @Test
    @DisplayName("create: name() comes from cfg.name for all three transports")
    void create_name_isPropagated() {
        McpServerConnection stdio = McpServerConnectionFactory.create(stdioCfg());
        McpServerConnection sse = McpServerConnectionFactory.create(sseCfg());
        McpServerConnection stream = McpServerConnectionFactory.create(streamCfg());
        try {
            assertThat(stdio.name()).isEqualTo("github");
            assertThat(sse.name()).isEqualTo("remote-sse");
            assertThat(stream.name()).isEqualTo("remote-stream");
        } finally {
            stdio.close();
            sse.close();
            stream.close();
        }
    }

    @Test
    @DisplayName("create(null) throws IllegalStateException")
    void create_null_throwsIllegalStateException() {
        assertThatThrownBy(() -> McpServerConnectionFactory.create(null))
            .isInstanceOf(IllegalStateException.class);
    }
}
