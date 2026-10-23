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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L3 tests for {@link SseMcpServerConnection#heartbeatTick()}
 * (AC-021c-4), updated by Story #054 T032.
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
 *   <li>The server dies after the handshake → {@code DISCONNECTED} →
 *       {@code RECONNECTING}.</li>
 *   <li>A slow server trips the request timeout → {@code RECONNECTING}.</li>
 *   <li>Sustained healthy probing keeps {@code CONNECTED} and a live
 *       {@code lastHeartbeatAt}.</li>
 * </ol>
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
        server = McpHttpTestSupport.startHttpServer();
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
        // Strictly greater — `isGreaterThanOrEqualTo` passes even when the
        // timestamp never advances, which is why the old case was vacuous.
        assertThat(hb1.toEpochMilli()).isGreaterThan(hb0.toEpochMilli());

        // FR-015: the liveness probe is a JSON-RPC ping over the announced POST
        // endpoint — never a vendor GET /health.
        assertThat(server.recorded())
            .anyMatch((RecordedRequest r) -> "ping".equals(r.getMethod())
                && r.getId() != null);
    }

    @Test
    @DisplayName("heartbeat: server gone after handshake → DISCONNECTED → RECONNECTING")
    void probe_pingServerGone_disconnects() throws Exception {
        // The SSE handshake is exactly 3 JSON-RPC POSTs (initialize,
        // notifications/initialized, tools/list), so exitAfter=3 lets the
        // connection reach CONNECTED and then kills the server — the next
        // heartbeat ping has nowhere to go.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("exitAfter", "3");
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
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("heartbeat: slow server → timeout → DISCONNECTED → RECONNECTING")
    void probe_pingTimeout_disconnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        // 800ms server-side delay against a 300ms probe timeout. The fixture's
        // delayMs is global (every JSON-RPC endpoint), so this proves "a server
        // slower than the probe budget surfaces as RECONNECTING" rather than
        // "the heartbeat specifically timed out" — the two are not separable
        // with a process-wide switch.
        sysProps.put("delayMs", "800");
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
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(8))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("heartbeat: probe succeeds against a healthy server")
    void probe_doubleCheck_recoveredHeartbeat() throws Exception {
        server = McpHttpTestSupport.startHttpServer();
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
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // After multiple ticks, state is still CONNECTED.
        Thread.sleep(1_500L);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.lastHeartbeatAt()).isNotNull();
    }
}
