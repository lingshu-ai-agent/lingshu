package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021a — EC-021a-1: invalid command schedules RECONNECTING.
 */
@DisplayName("Story #021a — start with invalid command (EC)")
class StdioMcpServerConnectionStartErrorTest {

    private StdioMcpServerConnection conn;

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("invalidCommand: start() → state moves to RECONNECTING")
    void invalidCommand_schedulesReconnect() {
        conn = new StdioMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.STDIO)
                .command("this-command-does-not-exist-1234567890")
                .heartbeatIntervalMs(100L)
                .heartbeatTimeoutMs(200L)
                .reconnectCapMs(100L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }
}