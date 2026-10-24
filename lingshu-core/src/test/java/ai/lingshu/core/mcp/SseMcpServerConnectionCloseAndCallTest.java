/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.mcp;

import ai.lingshu.core.mcp.fixture.TestMcpHttpServer.RecordedRequest;
import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — EC coverage for {@link SseMcpServerConnection} close /
 * callTool-not-connected / start-error paths, rewritten by Story #054 T035.
 *
 * <p>Five cases (EC-021c-5..EC-021c-9):
 * <ol>
 *   <li>close before start → no exception, state FAILED.</li>
 *   <li>close twice is idempotent — <em>and</em> the close really cancels the
 *       pending reconnect.</li>
 *   <li>callTool when never started → returns error (does not throw).</li>
 *   <li>callTool after a <em>real</em> disconnect → returns error naming the
 *       observed state (does not throw).</li>
 *   <li>ctor with mismatched transport → IllegalArgumentException.</li>
 * </ol>
 *
 * <p><b>What changed (Story #054 T035)</b>
 * <ul>
 *   <li>The hand-written {@code http://127.0.0.1:1/sse} literal is gone.
 *       Every URL is now either the fixture's real base URL, or a port that
 *       really was in use ({@link McpHttpTestSupport#pickClosedBaseUrl()}) —
 *       the old literal only proved that a <em>privileged</em> port is
 *       refused. The SSE path itself is derived through
 *       {@link SseMcpServerConnection#resolveSseUrl(String)} rather than
 *       hand-written.</li>
 *   <li>{@code close_beforeStart} asserted {@code isIn(FAILED, IDLE)} — a set
 *       that also accepts {@code IDLE}, so it could not tell "close() ran"
 *       from "close() did nothing". {@code close()} always transitions to
 *       {@code FAILED}, so the assertion is now exact.</li>
 *   <li>{@code close_idempotent} only looked at the state right after the
 *       second {@code close()}. It now proves the <em>interesting</em>
 *       property: a closed connection never comes back — no second
 *       handshake, no second {@code CONNECTED} — even though the fixture
 *       keeps serving and the reconnect backoff fires within a second.</li>
 *   <li>{@code callTool_afterDisconnect} called {@code close()} and then only
 *       asserted {@code getErrorMessage() != null} — an empty string would
 *       have passed. It now drives a <em>real</em> disconnect (the server
 *       breaks the SSE stream) and asserts the error names the observed
 *       state.</li>
 * </ul>
 */
@DisplayName("Story #021c — SseMcpServerConnection close / callTool / ctor")
class SseMcpServerConnectionCloseAndCallTest {

    private McpHttpTestSupport.ProcessHandle server;
    private SseMcpServerConnection conn;
    private McpStateLog log;

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
    @DisplayName("close before start → no exception, state FAILED")
    void close_beforeStart_noException() throws Exception {
        // A port that really was in use, then really closed — not the old
        // "127.0.0.1:1" magic literal.
        String baseUrl = McpHttpTestSupport.pickClosedBaseUrl();
        // The SSE transport dials <baseUrl>/sse — derived, never hand-written.
        assertThat(SseMcpServerConnection.resolveSseUrl(baseUrl))
            .isEqualTo(baseUrl + "/sse");
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.SSE)
                .url(baseUrl)
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        // Should not throw even before start()
        conn.close();
        // close() always transitions to FAILED — the old assertion accepted
        // IDLE too, so it could not prove the close actually happened.
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);
    }

    @Test
    @DisplayName("close twice is idempotent — and cancels the pending reconnect")
    void close_idempotent() throws Exception {
        // The server breaks every SSE stream after 200 ms (reverse-proxy
        // timeout) but keeps running — so a reconnect WOULD succeed if the
        // close failed to cancel it. That is what makes this assertion
        // non-vacuous: killing the server instead would make the reconnect
        // fail for an unrelated reason.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "50");
        sysProps.put("closeSseAfter", "200");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L) // long — keep the heartbeat out of it
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // The stream breaks → DISCONNECTED → RECONNECTING. The first reconnect
        // hop is scheduled 1000 ms later (the backoff floor — the base delay
        // is not injectable), so closing now leaves a wide margin.
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        int connectsAtClose = log.connectCount();
        int initializesAtClose = countMethod(server, "initialize");
        assertThat(connectsAtClose)
            .as("the close must happen inside the FIRST reconnect window")
            .isEqualTo(1);
        assertThat(initializesAtClose)
            .as("the handshake must have run exactly once so far")
            .isEqualTo(1);

        conn.close();
        conn.close(); // second close must not throw
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);

        // Stay FAILED across a window longer than the 1000 ms backoff floor:
        // had the reconnect not been cancelled, it would have fired by now.
        await().atMost(Duration.ofSeconds(3))
            .during(Duration.ofMillis(1_500))
            .until(() -> conn.state() == ConnectionState.FAILED);
        assertThat(log.connectCount())
            .as("a closed connection must never reconnect")
            .isEqualTo(connectsAtClose);
        assertThat(countMethod(server, "initialize"))
            .as("a closed connection must not run the handshake again")
            .isEqualTo(initializesAtClose);
    }

    @Test
    @DisplayName("callTool before start() → returns error result (does not throw)")
    void callTool_beforeStart_returnsError() throws Exception {
        String baseUrl = McpHttpTestSupport.pickClosedBaseUrl();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.SSE)
                .url(baseUrl)
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = conn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage())
            .contains("not connected")
            .contains("state=IDLE");
    }

    @Test
    @DisplayName("callTool after a real disconnect → error naming the observed state")
    void callTool_afterDisconnect_returnsError() throws Exception {
        // A REAL disconnect: the server breaks the SSE stream after 200 ms.
        // The old case called close() and then only asserted the message was
        // non-null — an empty string would have passed.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "50");
        sysProps.put("closeSseAfter", "200");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(10_000L) // long, keeps it RECONNECTING
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        // The heartbeat probe does not run while RECONNECTING, so nothing can
        // flip the state back before the call below.
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);

        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = conn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage())
            .contains("not connected")
            .contains("state=RECONNECTING");
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

    // ── helpers ─────────────────────────────────────────────────────────

    /** How many requests of the given JSON-RPC method the fixture received. */
    private static int countMethod(McpHttpTestSupport.ProcessHandle s, String method) {
        try {
            int n = 0;
            for (RecordedRequest r : s.recorded()) {
                if (method.equals(r.getMethod())) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
