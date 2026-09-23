package ai.lingshu.core.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021a — listener notification tests (AC-021a-8).
 *
 * <p>Three cases verifying listeners receive state transitions, multiple
 * listeners all fire, and a throwing listener doesn't break the others.
 */
@DisplayName("Story #021a — StdioMcpServerConnection listener")
class StdioMcpServerConnectionListenerTest {

    private StdioMcpServerConnection conn;

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("singleListener: receives CONNECTED notification")
    void singleListener_calledOnStateChange() {
        conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        List<ConnectionState> seen = new ArrayList<>();
        conn.onStateChange(seen::add);
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        assertThat(seen).contains(ConnectionState.CONNECTING, ConnectionState.CONNECTED);
    }

    @Test
    @DisplayName("multiListener: all 3 listeners receive CONNECTED")
    void multiListener_allCalled() {
        conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        AtomicInteger c1 = new AtomicInteger();
        AtomicInteger c2 = new AtomicInteger();
        AtomicInteger c3 = new AtomicInteger();
        conn.onStateChange(s -> c1.incrementAndGet());
        conn.onStateChange(s -> c2.incrementAndGet());
        conn.onStateChange(s -> c3.incrementAndGet());
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        // Each listener saw at least the CONNECTING + CONNECTED transitions
        assertThat(c1.get()).isGreaterThanOrEqualTo(2);
        assertThat(c2.get()).isGreaterThanOrEqualTo(2);
        assertThat(c3.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("listenerThrows: other listeners still invoked")
    void listenerThrows_otherListenersStillCalled() {
        conn = new StdioMcpServerConnection(
            McpTestSupport.stdioCfg("github", 100L, 500L, 200L));
        AtomicInteger survivor = new AtomicInteger();
        conn.onStateChange(s -> {
            throw new RuntimeException("boom");
        });
        conn.onStateChange(s -> survivor.incrementAndGet());
        conn.start();
        await().atMost(Duration.ofSeconds(8)).until(() -> conn.state() == ConnectionState.CONNECTED);
        assertThat(survivor.get()).isGreaterThanOrEqualTo(2);
    }
}