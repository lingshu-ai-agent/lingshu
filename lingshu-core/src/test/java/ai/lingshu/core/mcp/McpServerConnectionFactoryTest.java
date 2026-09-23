package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #021a — {@link McpServerConnectionFactory} L2 dispatch tests (AC-021a-4).
 *
 * <p>Four cases:
 * <ul>
 *   <li>{@code create_stdio_dispatchReturnsStdioConnection} — STDIO returns
 *       {@link StdioMcpServerConnection} in {@code IDLE} state.</li>
 *   <li>{@code create_sse_throwsM01} — SSE throws {@link McpTransportException}
 *       with code {@code LINGS-M01}.</li>
 *   <li>{@code create_streamableHttp_throwsM01} — STREAMABLE_HTTP same.</li>
 *   <li>{@code create_null_throwsIllegalStateException} — null cfg rejected.</li>
 * </ul>
 */
@DisplayName("Story #021a — McpServerConnectionFactory dispatch")
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
            .name("remote")
            .transport(McpTransportType.SSE)
            .url("https://mcp.example.com/sse")
            .build();
    }

    private static McpServerConfig streamCfg() {
        return McpServerConfig.builder()
            .name("remote")
            .transport(McpTransportType.STREAMABLE_HTTP)
            .url("https://mcp.example.com/rpc")
            .build();
    }

    @Test
    @DisplayName("create(STDIO) returns StdioMcpServerConnection in IDLE state")
    void create_stdio_dispatchReturnsStdioConnection() {
        McpServerConnection conn = McpServerConnectionFactory.create(stdioCfg());
        assertThat(conn).isInstanceOf(StdioMcpServerConnection.class);
        assertThat(conn.state()).isEqualTo(ConnectionState.IDLE);
        assertThat(conn.name()).isEqualTo("github");
        // keep alive — close idempotency check is verified elsewhere
        conn.close();
    }

    @Test
    @DisplayName("create(SSE) throws McpTransportException LINGS-M01")
    void create_sse_throwsM01() {
        assertThatThrownBy(() -> McpServerConnectionFactory.create(sseCfg()))
            .isInstanceOf(McpTransportException.class)
            .hasMessageContaining("SSE")
            .satisfies(e -> assertThat(((McpTransportException) e).getCode()).isEqualTo("LINGS-M01"));
    }

    @Test
    @DisplayName("create(STREAMABLE_HTTP) throws McpTransportException LINGS-M01")
    void create_streamableHttp_throwsM01() {
        assertThatThrownBy(() -> McpServerConnectionFactory.create(streamCfg()))
            .isInstanceOf(McpTransportException.class)
            .satisfies(e -> assertThat(((McpTransportException) e).getCode()).isEqualTo("LINGS-M01"));
    }

    @Test
    @DisplayName("create(null) throws IllegalStateException")
    void create_null_throwsIllegalStateException() {
        assertThatThrownBy(() -> McpServerConnectionFactory.create(null))
            .isInstanceOf(IllegalStateException.class);
    }
}