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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — EC coverage for
 * {@link StreamableHttpMcpServerConnection} close / callTool-not-connected
 * / close-during-connecting paths, rewritten by Story #054 T045.
 *
 * <p>Five cases:
 * <ol>
 *   <li>close before start → no exception, state FAILED.</li>
 *   <li>close twice is idempotent — <em>and</em> the close really cancels the
 *       pending reconnect.</li>
 *   <li>callTool when never started → returns error (does not throw).</li>
 *   <li>callTool after a <em>real</em> disconnect → returns error naming the
 *       observed state (does not throw).</li>
 *   <li>close right after a failed start → no exception, state FAILED.</li>
 * </ol>
 *
 * <p><b>What changed (Story #054 T045)</b>
 * <ul>
 *   <li>The hand-written {@code http://127.0.0.1:1/mcp} literal is gone
 *       (three occurrences). Every URL is now either the fixture's real MCP
 *       endpoint ({@link McpHttpTestSupport.ProcessHandle#mcpEndpoint()}) or a
 *       port that really was in use and really is closed
 *       ({@link McpHttpTestSupport#pickClosedBaseUrl()}) — the old literal
 *       only proved that a <em>privileged</em> port is refused. The MCP
 *       endpoint is derived by appending {@code /mcp} to that base URL
 *       (FR-017 — the single endpoint is never a path prefix).</li>
 *   <li>{@code close_beforeStart} asserted {@code isIn(FAILED, IDLE)} — a set
 *       that also accepts {@code IDLE}, so it could not tell "close() ran"
 *       from "close() did nothing". {@code close()} always transitions to
 *       {@code FAILED}, so the assertion is now exact.</li>
 *   <li>{@code close_idempotent} only looked at the state right after the
 *       second {@code close()}. It now proves the <em>interesting</em>
 *       property: a closed connection never comes back — no second
 *       handshake, no second {@code tools/list}, no second {@code CONNECTED}
 *       — even though the fixture keeps serving and the reconnect backoff
 *       fires within a second.</li>
 *   <li>{@code callTool_afterDisconnect} called {@code close()} and then only
 *       asserted {@code getErrorMessage() != null} — an empty string would
 *       have passed. It now drives a <em>real</em> disconnect and asserts the
 *       error names the observed state — plus that no {@code tools/call} ever
 *       left the client.</li>
 *   <li>{@code close_duringConnecting} asserted {@code isIn(FAILED,
 *       RECONNECTING)} — the same "either state would do" weakness. The
 *       close always ends in {@code FAILED}, so the assertion is now
 *       exact.</li>
 * </ul>
 *
 * <p><b>Why the disconnect is driven by the fixture's method-scoped
 * expiry</b> — the streamable-HTTP transport has no server-initiated stream
 * that a proxy could break, so the only way to knock the connection down for
 * <em>real</em> is to make the server answer 404 to the heartbeat
 * {@code ping} (Story #054 T037/T038 {@code expireMethod} /
 * {@code expireMethodCount}). Expiring the first two {@code ping}s produces
 * exactly one flap: {@code ping#1 404} → in-place re-{@code initialize} →
 * the retried {@code ping} 404s again → the connection drops → a reconnect
 * is scheduled (the 1000 ms backoff floor). The server keeps serving, so a
 * reconnect <em>would</em> succeed if {@code close()} failed to cancel it —
 * that is what makes the assertions non-vacuous.
 *
 * <p><b>How long the reconnect hop is</b> — {@code computeBackoffMs} returns
 * {@code min(delay, reconnectCapMs)}, so the cap can only <em>shorten</em> the
 * backoff, never extend it. {@code close_idempotent} therefore uses a 400 ms
 * cap: the property under test does not depend on the hop's length, and the
 * shorter hop keeps the case inside the 2 s per-case budget (Story #054
 * T045). The {@code RECONNECTING} window in
 * {@code callTool_afterDisconnect} is left at the 1000 ms floor instead,
 * because there the connection must still be RECONNECTING when the call is
 * made.
 */
@DisplayName("Story #021c — StreamableHttpMcpServerConnection close / callTool / ctor")
class StreamableHttpMcpServerConnectionCloseAndCallTest {

    /**
     * Tight polling for the observation of {@code RECONNECTING}. The default
     * 100 ms poll delay would eat a tenth of the ~1 s RECONNECTING window
     * that the 1000 ms backoff floor gives us.
     */
    private static final Duration POLL_DELAY = Duration.ofMillis(10);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

    private McpHttpTestSupport.ProcessHandle server;
    private StreamableHttpMcpServerConnection conn;
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
        // "127.0.0.1:1" magic literal. The MCP endpoint is the base URL plus
        // "/mcp" (FR-017 — the single endpoint is never a path prefix).
        String baseUrl = McpHttpTestSupport.pickClosedBaseUrl();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(baseUrl + "/mcp")
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
        // The fixture expires the first two heartbeat `ping`s, so the
        // connection flaps exactly once and a reconnect is scheduled — while
        // the server keeps serving, so a reconnect WOULD succeed if the close
        // failed to cancel it. Killing the server instead would make the
        // reconnect fail for an unrelated reason.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("expireMethod", "ping");
        sysProps.put("expireMethodCount", "2");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
                .heartbeatIntervalMs(100L)
                .heartbeatTimeoutMs(1_000L)
                // The reconnect hop is shortened to 400 ms: `reconnectCapMs`
                // can only *shorten* the backoff (`computeBackoffMs` returns
                // `min(delay, cap)`), and the property under test — "a closed
                // connection never comes back" — does not depend on how long
                // the hop is. Shortening it keeps the case inside the 2 s
                // per-case budget (Story #054 T045).
                .reconnectCapMs(400L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();

        // The flapping heartbeat knocks the connection down → DISCONNECTED →
        // RECONNECTING. The reconnect is scheduled 400 ms later, so closing
        // now leaves a wide margin.
        await().atMost(Duration.ofSeconds(5))
            .pollDelay(POLL_DELAY)
            .pollInterval(POLL_INTERVAL)
            .until(() -> conn.state() == ConnectionState.RECONNECTING);

        int connectsAtClose = log.connectCount();
        int initializesAtClose = countMethod(server, "initialize");
        int listsAtClose = countMethod(server, "tools/list");
        assertThat(connectsAtClose)
            .as("the close must happen inside the FIRST reconnect window")
            .isEqualTo(1);
        // The initial handshake plus the in-place 404 recovery — the streamable
        // flap re-initializes in place before the connection finally drops,
        // so the handshake ran twice here (the SSE sibling runs it once).
        assertThat(initializesAtClose)
            .as("the handshake plus the in-place 404 recovery must have run")
            .isEqualTo(2);
        assertThat(listsAtClose)
            .as("the tool list is fetched once per connection")
            .isEqualTo(1);

        conn.close();
        conn.close(); // second close must not throw
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);

        // Stay FAILED across a window longer than the 400 ms reconnect hop:
        // had the reconnect not been cancelled, it would have fired by now.
        await().atMost(Duration.ofSeconds(3))
            .during(Duration.ofMillis(700))
            .until(() -> conn.state() == ConnectionState.FAILED);
        assertThat(log.connectCount())
            .as("a closed connection must never reconnect")
            .isEqualTo(connectsAtClose);
        assertThat(countMethod(server, "initialize"))
            .as("a closed connection must not run the handshake again")
            .isEqualTo(initializesAtClose);
        assertThat(countMethod(server, "tools/list"))
            .as("a closed connection must not re-fetch the tool list")
            .isEqualTo(listsAtClose);
    }

    @Test
    @DisplayName("callTool before start() → returns error result (does not throw)")
    void callTool_beforeStart_returnsError() throws Exception {
        String baseUrl = McpHttpTestSupport.pickClosedBaseUrl();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("never-started")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(baseUrl + "/mcp")
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
        // A REAL disconnect: the fixture expires the heartbeat `ping`, the
        // connection drops and the transport enters RECONNECTING. The old
        // case called close() and then only asserted the message was
        // non-null — an empty string would have passed.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("expireMethod", "ping");
        sysProps.put("expireMethodCount", "2");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
                .heartbeatIntervalMs(100L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(10_000L) // the cap shortens, never extends
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .pollDelay(POLL_DELAY)
            .pollInterval(POLL_INTERVAL)
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        // The reconnect is scheduled 1000 ms after the drop, so the state
        // cannot flip back before the call below.
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);

        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = conn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage())
            .contains("not connected")
            .contains("state=RECONNECTING");
        // The call must short-circuit before any outbound HTTP — a
        // `tools/call` that reached the server would mean the transport
        // called the tool on a connection that is not connected.
        assertThat(countMethod(server, "tools/call"))
            .as("a call on a disconnected connection must not reach the server")
            .isEqualTo(0);
    }

    @Test
    @DisplayName("close right after a failed start → no exception, state FAILED")
    void close_duringConnecting_noException() throws Exception {
        // A port that really was in use, then really closed — the initial
        // handshake fails immediately, so `start()` returns with the
        // connection in RECONNECTING and the close has to take it from there.
        String baseUrl = McpHttpTestSupport.pickClosedBaseUrl();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("racy")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(baseUrl + "/mcp") // unreachable
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        // close immediately while the initial POST is still in flight / had failed
        conn.close();
        // close() always ends in FAILED — the old assertion also accepted
        // RECONNECTING, so it could not prove the close actually happened.
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * How many requests of the given JSON-RPC method the fixture received.
     *
     * <p>Story #054 T045: the read errors are <em>not</em> swallowed. The
     * SSE sibling returns 0 on failure — which would turn the negative
     * assertion ("no {@code tools/call} left the client") into a vacuous
     * pass whenever the recorder could not be read. Here a failed read
     * fails the case instead.
     */
    private static int countMethod(McpHttpTestSupport.ProcessHandle s, String method)
        throws Exception {
        int n = 0;
        for (RecordedRequest r : s.recorded()) {
            if (method.equals(r.getMethod())) {
                n++;
            }
        }
        return n;
    }
}
