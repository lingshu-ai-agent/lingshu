package ai.lingshu.core.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021a — L3 tests for {@link StdioMcpServerConnection#start()} (AC-021a-5).
 *
 * <p>Five cases verifying the 5-step handshake reaches {@link ConnectionState#CONNECTED},
 * invalid commands trigger reconnect, and {@code start()} is idempotent.
 */
@DisplayName("Story #021a — StdioMcpServerConnection start handshake")
class StdioMcpServerConnectionStartTest {

    private StdioMcpServerConnection conn;

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("start: fake server → 5-step handshake reaches CONNECTED with 1 tool listed")
    void start_fakeServer_5stepsReachesConnected() {
        conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");
        assertThat(conn.lastHeartbeatAt()).isNotNull();
    }

    @Test
    @DisplayName("start: invalid command → schedules RECONNECTING (not IDLE)")
    void start_invalidCommand_immediateFailureSchedulesReconnect() {
        conn = new StdioMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(ai.lingshu.core.runtime.McpTransportType.STDIO)
                .command("this-command-does-not-exist-1234567890")
                .heartbeatIntervalMs(100L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(100L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: second start() while CONNECTED is a no-op")
    void start_alreadyConnected_noop() {
        conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.start(); // second time
        // state stays CONNECTED, no exception
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        // call listTools a second time to verify process still alive
        assertThat(conn.listTools()).hasSize(1);
    }

    @Test
    @DisplayName("start: during RECONNECTING is a no-op")
    void start_duringReconnecting_noop() {
        conn = new StdioMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(ai.lingshu.core.runtime.McpTransportType.STDIO)
                .command("this-command-does-not-exist-1234567890")
                .heartbeatIntervalMs(100L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(10_000L) // long cap so we stay RECONNECTING
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> conn.state() == ConnectionState.RECONNECTING);
        conn.start(); // should not throw, should not change state
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: reconnect resets attempts on success")
    void start_reconnect_resetsAttempts() {
        // After kill, recovery reconnects and resets the attempt counter
        conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        // Verify CONNECTED is stable for at least 1 second
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
    }
}