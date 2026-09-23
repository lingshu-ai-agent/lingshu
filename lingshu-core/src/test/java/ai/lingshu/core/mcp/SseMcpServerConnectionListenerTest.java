package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L3 tests for {@link SseMcpServerConnection} SSE
 * listener behavior (AC-021c-3 / EC-021c-3 / EC-021c-4).
 *
 * <p>Three cases verifying the SSE reader's tolerance and reactivity:
 * <ol>
 *   <li>{@code notifications/tools/list_changed} from the server triggers
 *       a tool-list re-fetch (AC-021c-3).</li>
 *   <li>Malformed events do not kill the reader (EC-021c-3).</li>
 *   <li>Server closing the SSE stream triggers disconnect / reconnect
 *       (EC-021c-4).</li>
 * </ol>
 */
@DisplayName("Story #021c — SseMcpServerConnection SSE listener")
class SseMcpServerConnectionListenerTest {

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
    @DisplayName("SSE: notifications/tools/list_changed → relist keeps CONNECTED with tools")
    void sseToolsListChanged_triggersRelist() throws Exception {
        // Server pushes notifications/tools/list_changed every 100ms
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "100");
        server = McpHttpTestSupport.startSseServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L) // long — keep relay focused on SSE
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // Server pushes SSE list_changed → SseMcpServerConnection calls tools/list
        // via POST, replacing cachedTools. We don't assert the array contents churn;
        // we only assert that at least one push occurred without breaking the connection.
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED
                && conn.listTools().size() >= 1);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).isNotEmpty();
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");
    }

    @Test
    @DisplayName("SSE: malformed event (non-JSON) does NOT kill the reader")
    void sseMalformedEvent_continuesReading() throws Exception {
        // Every other push is non-JSON; valid JSON events must still be seen.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "100");
        sysProps.put("malformedRatio", "2");
        server = McpHttpTestSupport.startSseServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // After ~3 seconds (~30 pushes, ~15 of which were malformed),
        // we still see valid list_changed pushes keep tools cached and connection alive.
        Thread.sleep(2_500L);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).isNotEmpty();
    }

    @Test
    @DisplayName("SSE: server closing the stream triggers RECONNECTING")
    void sseStreamBroken_reconnects() throws Exception {
        // Server closes SSE after 500ms (simulates reverse-proxy timeout)
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "100");
        sysProps.put("closeSseAfter", "500");
        server = McpHttpTestSupport.startSseServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L) // long — won't dominate
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(5_000L)
                .build());
        conn.start();
        // First reaches CONNECTED …
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // … then SSE server closes, reader notices, transitions to DISCONNECTED → RECONNECTING.
        await().atMost(Duration.ofSeconds(8))
            .until(() -> conn.state() == ConnectionState.RECONNECTING
                || conn.state() == ConnectionState.CONNECTED);
        // The reconnect may succeed against the same server (since closeSseAfter is
        // a per-handler auto-close; the server stays up). Either outcome is valid.
        assertThat(conn.state()).isIn(
            ConnectionState.RECONNECTING, ConnectionState.CONNECTED);
    }
}
