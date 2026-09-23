package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — EC coverage for
 * {@link StreamableHttpMcpServerConnection} close / callTool-not-connected
 * / close-during-connecting paths.
 *
 * <p>Five cases mirroring {@link SseMcpServerConnectionCloseAndCallTest} for
 * the streamable-HTTP transport.
 */
@DisplayName("Story #021c — StreamableHttpMcpServerConnection close / callTool / ctor")
class StreamableHttpMcpServerConnectionCloseAndCallTest {

    private McpHttpTestSupport.ProcessHandle server;
    private StreamableHttpMcpServerConnection conn;

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
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url("http://127.0.0.1:1/initialize")
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.close();
        assertThat(conn.state()).isIn(
            ConnectionState.FAILED, ConnectionState.IDLE);
    }

    @Test
    @DisplayName("close twice is idempotent")
    void close_idempotent() throws Exception {
        server = McpHttpTestSupport.startHttpServer();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
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
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url("http://127.0.0.1:1/initialize")
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
        server = McpHttpTestSupport.startHttpServer();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(10_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.close();
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = conn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage()).isNotNull();
    }

    @Test
    @DisplayName("close during CONNECTING (very fast close after start) does not throw")
    void close_duringConnecting_noException() {
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("racy")
                .transport(McpTransportType.STREAMABLE_HTTP)
                // simulate a slow-to-connect server by delaying every handler
                .url("http://127.0.0.1:1/initialize") // unreachable
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        // close immediately while the initial POST is still in flight / had failed
        conn.close();
        assertThat(conn.state()).isIn(
            ConnectionState.FAILED, ConnectionState.RECONNECTING);
    }
}
