package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L2 + L3 tests for
 * {@link StreamableHttpMcpServerConnection#start()} (AC-021c-2).
 *
 * <p>Four cases verifying the 5-step handshake reaches
 * {@link ConnectionState#CONNECTED}, invalid URLs trigger
 * {@link ConnectionState#RECONNECTING}, missing URLs trigger
 * {@link ConnectionState#RECONNECTING}, and {@code start()} is idempotent.
 *
 * <p><b>Why</b> — mirrors {@link SseMcpServerConnectionStartTest} but the
 * streamable HTTP transport is stateless; no {@code sseReader} side
 * effects complicate the timing.
 */
@DisplayName("Story #021c — StreamableHttpMcpServerConnection start handshake")
class StreamableHttpMcpServerConnectionStartTest {

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
    @DisplayName("start: fake HTTP server → 5-step handshake reaches CONNECTED")
    void start_fakeServer_5stepsReachesConnected() throws Exception {
        server = McpHttpTestSupport.startHttpServer();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");
    }

    @Test
    @DisplayName("start: invalid URL (connection refused) → schedules RECONNECTING")
    void start_invalidUrl_immediateFailureSchedulesReconnect() {
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url("http://127.0.0.1:1/tools/call") // port 1 = unreachable
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: missing URL → schedules RECONNECTING")
    void start_missingUrl_schedulesReconnect() {
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("missing-url")
                .transport(McpTransportType.STREAMABLE_HTTP)
                // no url
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: second start() while CONNECTED is a no-op")
    void start_alreadyConnected_noop() throws Exception {
        server = McpHttpTestSupport.startHttpServer();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.start(); // second time — must be no-op
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
        // ctor rejection for mismatched transport type (Streamable only accepts STREAMABLE_HTTP)
        assertThatThrownBy(() -> new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("mismatch")
                .transport(McpTransportType.STDIO)
                .command("ls")
                .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("STREAMABLE_HTTP");
    }
}
