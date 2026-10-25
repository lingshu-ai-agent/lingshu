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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L2 + L3 tests for
 * {@link StreamableHttpMcpServerConnection#start()} (AC-021c-2).
 *
 * <p>Four cases verifying the 5-step handshake reaches
 * {@link ConnectionState#CONNECTED}, invalid URLs trigger
 * {@link ConnectionState#RECONNECTING}, missing URLs trigger
 * {@link ConnectionState#RECONNECTING}, and {@code start()} is idempotent.
 *
 * <p><b>Why</b> — mirrors {@link SseMcpServerConnectionStartTest} but the
 * streamable HTTP transport is stateless; no {@code sseReader} side
 * effects complicate the timing.
 *
 * <p><b>Story #054 T042</b> — the two "happy path" cases now also pin the
 * <em>single-endpoint</em> contract (every JSON-RPC message is POSTed to
 * {@code cfg.getUrl()} itself — never to a per-method path such as
 * {@code /initialize} or {@code /tools/list}) and the <em>session-id
 * replay</em> contract (the {@code Mcp-Session-Id} the server issues on
 * {@code initialize} is echoed on every later request, while the handshake
 * itself carries none).
 */
@DisplayName("Story #021c — StreamableHttpMcpServerConnection start handshake")
class StreamableHttpMcpServerConnectionStartTest {

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
    @DisplayName("start: fake HTTP server → 5-step handshake reaches CONNECTED")
    void start_fakeServer_5stepsReachesConnected() throws Exception {
        server = McpHttpTestSupport.startHttpServer();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");

        // Story #054 T042 — single endpoint + session-id replay.
        assertSingleEndpointHandshake(server.recorded());
    }

    @Test
    @DisplayName("start: invalid URL (connection refused) → schedules RECONNECTING")
    void start_invalidUrl_immediateFailureSchedulesReconnect() throws Exception {
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.STREAMABLE_HTTP)
                // A port that is really closed — the old `http://127.0.0.1:1/mcp`
                // literal only proved that a privileged port is refused and never
                // exercised a port that had really been in use (Story #054 T019).
                .url(McpHttpTestSupport.pickClosedBaseUrl() + "/mcp")
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: missing URL → schedules RECONNECTING")
    void start_missingUrl_schedulesReconnect() {
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("missing-url")
                .transport(McpTransportType.STREAMABLE_HTTP)
                // no url
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: second start() while CONNECTED is a no-op")
    void start_alreadyConnected_noop() throws Exception {
        server = McpHttpTestSupport.startHttpServer();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.start(); // second time — must be no-op
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);

        // Story #054 T042 — the no-op second start() must not re-handshake:
        // the session id negotiated by the first handshake is still the one
        // being replayed, and the whole conversation stayed on one endpoint.
        List<RecordedRequest> recorded = server.recorded();
        assertSingleEndpointHandshake(recorded);
        assertThat(byMethod(recorded, "initialize"))
            .as("the no-op start() must not trigger a second handshake")
            .hasSize(1);

        // ctor rejection for mismatched transport type (Streamable only accepts STREAMABLE_HTTP)
        assertThatThrownBy(() -> new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("mismatch")
                .transport(McpTransportType.STDIO)
                .command("ls")
                .build()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("STREAMABLE_HTTP");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Story #054 T042 — the streamable-HTTP transport is a <em>single</em>
     * endpoint: every JSON-RPC message is POSTed to {@code cfg.getUrl()}
     * itself, never to a per-method path. The session id the server issues
     * on {@code initialize} is replayed on every later request, while the
     * handshake itself carries none.
     */
    private static void assertSingleEndpointHandshake(List<RecordedRequest> recorded) {
        assertThat(recorded)
            .as("the handshake must have produced observable traffic")
            .isNotEmpty();
        assertThat(recorded)
            .as("streamable HTTP is a single endpoint — never a per-method path")
            .allMatch((RecordedRequest r) -> "/mcp".equals(r.getPath()));

        List<RecordedRequest> inits = byMethod(recorded, "initialize");
        assertThat(inits).hasSize(1);
        assertThat(inits.get(0).getSessionIdHeader())
            .as("the handshake itself must not carry a session id")
            .isNull();

        List<RecordedRequest> initialized = byMethod(recorded, "notifications/initialized");
        assertThat(initialized).hasSize(1);
        assertThat(initialized.get(0).getSessionIdHeader())
            .as("notifications/initialized rides the negotiated session")
            .isNotNull();

        List<RecordedRequest> lists = byMethod(recorded, "tools/list");
        assertThat(lists).hasSize(1);
        assertThat(lists.get(0).getSessionIdHeader())
            .as("tools/list rides the negotiated session")
            .isEqualTo(initialized.get(0).getSessionIdHeader());
    }

    private static List<RecordedRequest> byMethod(List<RecordedRequest> recorded, String method) {
        List<RecordedRequest> out = new ArrayList<>();
        for (RecordedRequest r : recorded) {
            if (method.equals(r.getMethod())) {
                out.add(r);
            }
        }
        return out;
    }
}
