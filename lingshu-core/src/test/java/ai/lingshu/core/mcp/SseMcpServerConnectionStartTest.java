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
 * Story #021c — L2 + L3 tests for {@link SseMcpServerConnection#start()} (AC-021c-1).
 *
 * <p>Five cases verifying the 5-step handshake reaches {@link ConnectionState#CONNECTED},
 * invalid URLs trigger reconnect, missing URLs trigger reconnect, and {@code start()}
 * is idempotent.
 */
@DisplayName("Story #021c — SseMcpServerConnection start handshake")
class SseMcpServerConnectionStartTest {

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
    @DisplayName("start: fake SSE/HTTP server → 5-step handshake reaches CONNECTED")
    void start_fakeServer_5stepsReachesConnected() throws Exception {
        server = McpHttpTestSupport.startSseServer();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(8))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");
    }

    @Test
    @DisplayName("start: invalid URL (connection refused) → schedules RECONNECTING")
    void start_invalidUrl_immediateFailureSchedulesReconnect() {
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.SSE)
                .url("http://127.0.0.1:1/sse") // port 1 = unreachable
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
    @DisplayName("start: missing URL → start fails / schedules RECONNECTING")
    void start_missingUrl_illegalArgumentOrReconnect() {
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("missing-url")
                .transport(McpTransportType.SSE)
                // no url
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .build());
        conn.start();
        // Either IllegalArgumentException was thrown by doConnect() (caught by start()
        // and converted to RECONNECTING) or the state advances to RECONNECTING.
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: second start() while CONNECTED is a no-op")
    void start_alreadyConnected_noop() throws Exception {
        server = McpHttpTestSupport.startSseServer();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(8))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.start(); // second time — must be no-op
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
    }

    @Test
    @DisplayName("start: during RECONNECTING is a no-op")
    void start_duringReconnecting_noop() {
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.SSE)
                .url("http://127.0.0.1:1/sse")
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(10_000L) // long cap keeps it in RECONNECTING
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        conn.start(); // should not throw, should not change state
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }
}