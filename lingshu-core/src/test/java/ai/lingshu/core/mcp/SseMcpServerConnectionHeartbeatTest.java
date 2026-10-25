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
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L3 tests for {@link SseMcpServerConnection#heartbeatTick()}
 * (AC-021c-4), rewritten by Story #054 T032.
 *
 * <p>These four cases used to drive the retired vendor {@code GET /health}
 * endpoint — an endpoint PR #90 deleted from production, so every one of them
 * was green while testing a path the transport never takes (and, per
 * {@code dontReplyHealth} applying to <em>all</em> handlers, a probe hitting the
 * nonexistent {@code GET /} still got an instant 404 that counted as a reply).
 * They now drive the spec's JSON-RPC {@code ping}:
 * <ol>
 *   <li>A healthy server answers {@code ping} → {@code CONNECTED} holds and
 *       {@code lastHeartbeatAt} <b>strictly</b> advances.</li>
 *   <li>The server dies right after the handshake → {@code DISCONNECTED} →
 *       {@code RECONNECTING}.</li>
 *   <li>A server that answers the handshake but stalls on the probe →
 *       {@code DISCONNECTED} → {@code RECONNECTING}.</li>
 *   <li>The SSE stream breaks → the transport reconnects and the heartbeat is
 *       counted again.</li>
 * </ol>
 *
 * <p><b>What makes them non-vacuous</b> — every case asserts on the
 * <em>observed state sequence</em> (captured by a listener), not on a single
 * polling snapshot. "Eventually RECONNECTING" alone would also be satisfied by
 * a connection that never managed to connect in the first place; the sequence
 * proves the handshake really completed and the disconnect really followed.
 *
 * <p><b>Convention (research.md 决策 2)</b> — cases that need shrunken time
 * parameters construct the connection directly with the 4-arg injectable-time
 * constructor. {@code McpServerConnectionFactory.create(cfg)} only ever uses the
 * 1-arg constructor and cannot see these values.
 */
@DisplayName("Story #021c — SseMcpServerConnection heartbeat")
class SseMcpServerConnectionHeartbeatTest {

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
    @DisplayName("heartbeat: ping answered → CONNECTED with lastHeartbeatAt advancing")
    void probe_pingOk() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        // No notifications — otherwise the transport re-issues tools/list on
        // every tick and the request log stops being readable.
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        Instant hb0 = conn.lastHeartbeatAt();
        assertThat(hb0).isNotNull();

        // Wait for the heartbeat to advance — never a blind sleep.
        await().atMost(Duration.ofSeconds(2))
            .until(() -> conn.lastHeartbeatAt() != null
                && conn.lastHeartbeatAt().toEpochMilli() > hb0.toEpochMilli());
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        Instant hb1 = conn.lastHeartbeatAt();
        assertThat(hb1).isNotNull();
        // Strictly greater — `isGreaterThanOrEqualTo` passes even when the
        // timestamp never advances, which is why the old case was vacuous.
        assertThat(hb1.toEpochMilli()).isGreaterThan(hb0.toEpochMilli());

        // FR-015: the liveness probe is a JSON-RPC ping over the announced POST
        // endpoint — never a vendor GET /health.
        assertThat(server.recorded())
            .anyMatch((RecordedRequest r) -> "ping".equals(r.getMethod())
                && r.getId() != null);
        assertThat(log.transitions()).contains(ConnectionState.CONNECTED);
    }

    @Test
    @DisplayName("heartbeat: server gone right after the handshake → DISCONNECTED → RECONNECTING")
    void probe_pingServerGone_disconnects() throws Exception {
        // The SSE handshake is exactly 3 JSON-RPC POSTs (initialize,
        // notifications/initialized, tools/list), so exitAfter=4 lets the
        // connection reach CONNECTED and answers the first heartbeat ping —
        // the server dies right after, and the next probe has nowhere to go.
        // pushNotifications=false keeps the count deterministic: without it the
        // tools/list re-fetch triggered by the first notification would
        // consume the 4th slot instead of the ping.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("exitAfter", "4");
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(10_000L) // long cap keeps it in RECONNECTING
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
        // The handshake really completed before the server vanished — the
        // sequence is the proof, a single snapshot would not be.
        assertThat(log.transitions())
            .containsSubsequence(
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED,
                ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("heartbeat: server answers the handshake but stalls on the probe → RECONNECTING")
    void probe_pingTimeout_disconnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        // 800ms server-side delay, but only from the 4th request on: the
        // handshake (initialize + notification + tools/list) stays fast, so
        // the only thing that can time out is the heartbeat probe. With a
        // plain process-wide delay the handshake itself would time out and
        // "the heartbeat timed out" could never be observed on its own.
        sysProps.put("delayMs", "800");
        sysProps.put("delayAfter", "3");
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(300L)
                .reconnectCapMs(10_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build(),
            300L, 300L, 10_000L);
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
        // The connection DID connect — only the probe stalled.
        assertThat(log.transitions())
            .containsSubsequence(
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED,
                ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("heartbeat: SSE stream broken → reconnect → the heartbeat is counted again")
    void probe_doubleCheck_recoveredHeartbeat() throws Exception {
        // The server closes every SSE stream after 200ms (reverse-proxy
        // timeout). The transport must notice, reconnect, and count the
        // heartbeat again — not keep serving a stale timestamp.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "50");
        sysProps.put("closeSseAfter", "200");
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(2_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // The stream breaks → RECONNECTING → (1s backoff) → CONNECTED again.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> log.connectCount() >= 2);
        assertThat(log.transitions())
            .containsSubsequence(
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED,
                ConnectionState.RECONNECTING,
                ConnectionState.CONNECTED);
        assertThat(log.connectCount())
            .as("the transport must have reconnected after the stream broke")
            .isGreaterThanOrEqualTo(2);
        // The heartbeat is counted again after the reconnect — the timestamp
        // must be refreshed, not left stale from the first connection.
        List<Instant> beats = log.beatsAtConnect();
        assertThat(beats).hasSizeGreaterThanOrEqualTo(2);
        assertThat(beats.get(1).toEpochMilli())
            .as("lastHeartbeatAt must be refreshed by the reconnect")
            .isGreaterThan(beats.get(0).toEpochMilli());
    }

}
