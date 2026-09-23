package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — EC coverage for {@link SseMcpServerConnection} close /
 * callTool-not-connected / start-error paths.
 *
 * <p>Five cases (EC-021c-5..EC-021c-9):
 * <ol>
 *   <li>close before start → state stays IDLE-ish, no exception.</li>
 *   <li>close twice is idempotent.</li>
 *   <li>callTool when never started → returns error (does not throw).</li>
 *   <li>callTool after disconnect → returns error (does not throw).</li>
 *   <li>ctor with mismatched transport → IllegalArgumentException.</li>
 * </ol>
 */
@DisplayName("Story #021c — SseMcpServerConnection close / callTool / ctor")
class SseMcpServerConnectionCloseAndCallTest {

    private McpHttpTestSupport.ProcessHandle server;
    private SseMcpServerConnection conn;

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("close before start → no exception, state untouched")
    void close_beforeStart_noException() {
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.SSE)
                .url("http://127.0.0.1:1/sse")
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .build());
        // Should not throw even before start()
        conn.close();
        assertThat(conn.state()).isIn(
            ConnectionState.FAILED, ConnectionState.IDLE);
    }

    @Test
    @DisplayName("close twice is idempotent")
    void close_idempotent() throws Exception {
        server = McpHttpTestSupport.startSseServer();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.close();
        conn.close(); // second close must not throw
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);
    }

    @Test
    @DisplayName("callTool before start() → returns error result (does not throw)")
    void callTool_beforeStart_returnsError() {
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.SSE)
                .url("http://127.0.0.1:1/sse")
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .build());
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = conn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage()).isNotNull();
        assertThat(res.getErrorMessage()).contains("not connected");
    }

    @Test
    @DisplayName("callTool after disconnect → returns error result (does not throw)")
    void callTool_afterDisconnect_returnsError() throws Exception {
        server = McpHttpTestSupport.startSseServer();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(10_000L) // long, keeps it RECONNECTING
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // close() moves to FAILED; callTool must return error not throw
        conn.close();
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = conn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage()).isNotNull();
    }

    @Test
    @DisplayName("ctor: McpServerConfig with transport=STDIO → IllegalArgumentException")
    void ctor_wrongTransport_throws() {
        assertThatThrownBy(() -> new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("mismatch")
                .transport(McpTransportType.STDIO) // wrong transport
                .command("ls")
                .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SSE");
    }
}
