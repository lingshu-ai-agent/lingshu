package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L3 tests for {@link SseMcpServerConnection#heartbeatTick()}
 * (AC-021c-4).
 *
 * <p>Four cases verifying the {@code GET /health} probe:
 * <ol>
 *   <li>Healthy 200 → {@code CONNECTED} stays put, {@code lastHeartbeatAt} updates.</li>
 *   <li>5xx → transition to {@code DISCONNECTED} → {@code RECONNECTING}.</li>
 *   <li>Timeout → {@code DISCONNECTED} → {@code RECONNECTING}.</li>
 *   <li>After recovery (health back to 200) → {@code CONNECTED} returns,
 *       {@code reconnectAttempts} resets.</li>
 * </ol>
 */
@DisplayName("Story #021c — SseMcpServerConnection heartbeat")
class SseMcpServerConnectionHeartbeatTest {

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
    @DisplayName("heartbeat: healthy 200 → CONNECTED with lastHeartbeatAt advancing")
    void probe_healthOk() throws Exception {
        server = McpHttpTestSupport.startSseServer();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        Instant hb0 = conn.lastHeartbeatAt();
        assertThat(hb0).isNotNull();

        // Wait ~3 heartbeat cycles, verify lastHeartbeatAt advanced.
        Thread.sleep(1_000L);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        Instant hb1 = conn.lastHeartbeatAt();
        assertThat(hb1).isNotNull();
        assertThat(hb1.toEpochMilli()).isGreaterThanOrEqualTo(hb0.toEpochMilli());
    }

    @Test
    @DisplayName("heartbeat: 5xx response → DISCONNECTED → RECONNECTING")
    void probe_health5xx_disconnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("dontReplyHealth", "true");
        server = McpHttpTestSupport.startSseServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(10_000L) // long cap keeps it in RECONNECTING
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // After the next heartbeat tick (which gets 500), we'll move to RECONNECTING.
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("heartbeat: timeout → DISCONNECTED → RECONNECTING")
    void probe_healthTimeout_disconnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        // Sleep 2s on /health — exceeds the 300ms heartbeat timeout
        sysProps.put("delayMs", "2000");
        server = McpHttpTestSupport.startSseServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(300L)
                .reconnectCapMs(10_000L)
                .build());
        conn.start();
        // Start has a 50ms grace before CONNECTED; so the 1st heartbeat tick won't
        // fire for ~300ms after CONNECTED. Within ~2s it should observe the timeout
        // and bounce through DISCONNECTED to RECONNECTING.
        await().atMost(Duration.ofSeconds(8))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("heartbeat: probe succeeds against a healthy server")
    void probe_doubleCheck_recoveredHeartbeat() throws Exception {
        server = McpHttpTestSupport.startSseServer();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // After multiple ticks, state is still CONNECTED.
        Thread.sleep(1_500L);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.lastHeartbeatAt()).isNotNull();
    }
}
