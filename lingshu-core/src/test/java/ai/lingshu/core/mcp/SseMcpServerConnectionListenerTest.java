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
 * Story #021c — L3 tests for {@link SseMcpServerConnection} SSE
 * listener behavior (AC-021c-3 / EC-021c-3 / EC-021c-4), rewritten by
 * Story #054 T033.
 *
 * <p>Three cases verifying the SSE reader's tolerance and reactivity:
 * <ol>
 *   <li>{@code notifications/tools/list_changed} from the server triggers
 *       a tool-list re-fetch (AC-021c-3).</li>
 *   <li>Malformed events do not kill the reader (EC-021c-3).</li>
 *   <li>Server closing the SSE stream triggers disconnect / reconnect
 *       (EC-021c-4).</li>
 * </ol>
 *
 * <p><b>What changed</b> — the old cases asserted a single state snapshot.
 * The re-list case was vacuous (the tool list is already populated by the
 * handshake, so "tools are non-empty" proved nothing about the notification
 * being handled), and the stream-broken case awaited
 * {@code RECONNECTING || CONNECTED} — a condition that is already true at
 * {@code t=0}, so it could not fail. Both now assert the <em>observed state
 * sequence</em> and the <em>request log</em>, which is what actually
 * distinguishes "the notification was handled" from "the connection is up".
 *
 * <p><b>Why {@link McpStateLog}</b> — {@code relistTools()} re-announces
 * {@code CONNECTED} on every {@code notifications/tools/list_changed} push
 * without the state having changed; a raw notification list would therefore
 * show several {@code CONNECTED} entries for a single connection.
 * {@link McpStateLog#transitions()} keeps only genuine transitions.
 */
@DisplayName("Story #021c — SseMcpServerConnection SSE listener")
class SseMcpServerConnectionListenerTest {

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
    @DisplayName("SSE: notifications/tools/list_changed → relist keeps CONNECTED with tools")
    void sseToolsListChanged_triggersRelist() throws Exception {
        // Server pushes notifications/tools/list_changed every 100ms
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "100");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L) // long — keep relay focused on SSE
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // The server's push must actually make the client re-issue tools/list.
        // The handshake already sent one, so waiting for a *second* one is
        // what proves the notification was handled — "tools are non-empty"
        // would have been true before the first push as well.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> toolsListCount(server) >= 3);
        assertThat(toolsListCount(server))
            .as("the tools/list_changed notification must trigger a re-list")
            .isGreaterThanOrEqualTo(3);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).isNotEmpty();
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");
        // A notification must never knock the connection out of CONNECTED.
        assertThat(log.transitions()).contains(ConnectionState.CONNECTED);
        assertThat(log.transitions()).doesNotContain(ConnectionState.DISCONNECTED);
    }

    @Test
    @DisplayName("SSE: malformed event (non-JSON) does NOT kill the reader")
    void sseMalformedEvent_continuesReading() throws Exception {
        // Every other push is non-JSON; valid JSON events must still be seen.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "100");
        sysProps.put("malformedRatio", "2");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // The malformed frames are interleaved with valid ones, so the fact
        // that re-lists keep happening proves the reader survived the junk.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> toolsListCount(server) >= 3);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).isNotEmpty();
        assertThat(log.transitions()).doesNotContain(ConnectionState.DISCONNECTED);
    }

    @Test
    @DisplayName("SSE: server closing the stream triggers RECONNECTING")
    void sseStreamBroken_reconnects() throws Exception {
        // Server closes SSE after 200ms (simulates reverse-proxy timeout)
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "50");
        sysProps.put("closeSseAfter", "200");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L) // long — won't dominate
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(2_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        // First reaches CONNECTED …
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // … then the SSE server closes, the reader notices, and the transport
        // transitions to DISCONNECTED → RECONNECTING. The old case awaited
        // "RECONNECTING || CONNECTED", which was already true at t=0.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> log.transitions().contains(ConnectionState.RECONNECTING));
        assertThat(log.transitions())
            .containsSubsequence(
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED,
                ConnectionState.RECONNECTING);
        // The reconnect succeeds against the same server (the server stays up;
        // only the individual stream is closed), so the listener is told about
        // the recovery too. Counting *transitions* — not notifications — is
        // what makes this meaningful: relistTools() re-announces CONNECTED
        // without reconnecting.
        await().atMost(Duration.ofSeconds(5))
            .until(() -> log.connectCount() >= 2);
        assertThat(log.connectCount())
            .as("the transport must reconnect after the stream breaks")
            .isGreaterThanOrEqualTo(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** How many {@code tools/list} requests the fixture actually received. */
    private static int toolsListCount(McpHttpTestSupport.ProcessHandle s) {
        try {
            int n = 0;
            for (RecordedRequest r : s.recorded()) {
                if ("tools/list".equals(r.getMethod())) {
                    n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }
}
