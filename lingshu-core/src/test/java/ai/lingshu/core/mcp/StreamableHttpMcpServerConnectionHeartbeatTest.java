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
 * Story #021c — L3 tests for
 * {@link StreamableHttpMcpServerConnection#heartbeatTick()} (AC-021c-4).
 *
 * <p>Three cases verifying the <b>JSON-RPC {@code ping}</b> probe for the
 * streamable-HTTP transport. PR #90 replaced the retired vendor
 * {@code GET /health} with a spec {@code ping}; these cases now drive that
 * path (Story #054 T043):
 * <ol>
 *   <li>A healthy server answers {@code ping} — {@code CONNECTED} holds and
 *       {@code lastHeartbeatAt} <b>strictly</b> advances.</li>
 *   <li>The server expires the session ({@code expireSession}) so the
 *       heartbeat's {@code ping} gets 404 → {@code RECONNECTING}.</li>
 *   <li>A slow server trips the heartbeat timeout → {@code RECONNECTING}.</li>
 * </ol>
 *
 * <p><b>Convention (research.md 决策 2)</b> — cases that need shrunken time
 * parameters construct the connection directly with the 4-arg injectable-time
 * constructor. {@code McpServerConnectionFactory.create(cfg)} only ever uses the
 * 1-arg constructor and cannot see these values.
 */
@DisplayName("Story #021c — StreamableHttpMcpServerConnection heartbeat")
class StreamableHttpMcpServerConnectionHeartbeatTest {

    private McpHttpTestSupport.ProcessHandle server;
    private StreamableHttpMcpServerConnection conn;

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
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
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

        // FR-015: the liveness probe is a JSON-RPC ping, not a vendor /health GET.
        assertThat(server.recorded())
            .anyMatch((RecordedRequest r) -> "ping".equals(r.getMethod()) && r.getId() != null);
    }

    @Test
    @DisplayName("heartbeat: session expired (404 on ping) → DISCONNECTED → RECONNECTING")
    void probe_sessionExpired404_disconnects() throws Exception {
        // The handshake is initialize(#0, no session) → notifications/initialized
        // (#1) → tools/list (#2), so expireSession=3 expires exactly at the first
        // heartbeat ping — the client must reach CONNECTED first, then lose it.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("expireSession", "3");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
                .heartbeatIntervalMs(300L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(10_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("heartbeat: slow server → timeout → DISCONNECTED → RECONNECTING")
    void probe_pingTimeout_disconnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        // 800ms server-side delay against a 300ms probe timeout, so no probe can
        // complete — no second-level sleep needed.
        sysProps.put("delayMs", "800");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
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
}
