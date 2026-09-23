package ai.lingshu.core.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021a — close() contract tests (AC-021a-9).
 *
 * <p>Two cases verifying close() transitions to FAILED and is idempotent.
 */
@DisplayName("Story #021a — StdioMcpServerConnection close")
class StdioMcpServerConnectionCloseTest {

    @Test
    @DisplayName("close: transitions to FAILED, second close() is no-op")
    void close_transitionsToFailedIdempotent() {
        StdioMcpServerConnection conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.close();
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);
        // second call must not throw
        conn.close();
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);
        // third for good measure
        conn.close();
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);
    }

    @Test
    @DisplayName("close: daemon heartbeat thread is shut down")
    void close_daemonThreadTerminated() throws Exception {
        StdioMcpServerConnection conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.close();
        // After close(), no live thread named mcp-hb-github should remain
        Thread.sleep(100);
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int n = Thread.enumerate(threads);
        boolean stillAlive = false;
        for (int i = 0; i < n; i++) {
            if (threads[i].getName().equals("mcp-hb-github") && threads[i].isAlive()) {
                stillAlive = true;
                break;
            }
        }
        assertThat(stillAlive).isFalse();
    }
}