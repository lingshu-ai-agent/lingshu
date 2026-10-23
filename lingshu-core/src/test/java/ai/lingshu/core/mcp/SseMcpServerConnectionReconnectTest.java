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

import ai.lingshu.core.runtime.McpTransportType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L2 + L3 tests for {@link SseMcpServerConnection} reconnect
 * (AC-021c-5).
 *
 * <p>Three cases verifying:
 * <ol>
 *   <li>{@link SseMcpServerConnection#computeBackoffMs(int)} produces the
 *       expected 1s, 2s, 4s, 8s, 16s, 32s, 60s (cap) sequence.</li>
 *   <li>Reconnect is unbounded — after multiple failures, state remains
 *       {@link ConnectionState#RECONNECTING}.</li>
 *   <li>EC-021c-4: a broken SSE stream triggers reconnect (covered here by
 *       using a server that closes the SSE stream mid-flight).</li>
 * </ol>
 *
 * <p><b>Convention (research.md 决策 2)</b> — cases that need shrunken time
 * parameters construct the connection directly with the 4-arg injectable-time
 * constructor. {@code McpServerConnectionFactory.create(cfg)} only ever uses the
 * 1-arg constructor and cannot see these values.
 */
@DisplayName("Story #021c — SseMcpServerConnection reconnect")
class SseMcpServerConnectionReconnectTest {

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
    @DisplayName("reconnect: exponential backoff sequence = 1s, 2s, 4s, 8s, 16s, 32s, 60s(cap)")
    void backoff_sequence() {
        // Use a fake connection just to access computeBackoffMs
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("dummy")
                .transport(McpTransportType.SSE)
                .url("http://127.0.0.1:1/sse") // unreachable
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(60_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        assertThat(conn.computeBackoffMs(0)).isEqualTo(1_000L);
        assertThat(conn.computeBackoffMs(1)).isEqualTo(1_000L);
        assertThat(conn.computeBackoffMs(2)).isEqualTo(2_000L);
        assertThat(conn.computeBackoffMs(3)).isEqualTo(4_000L);
        assertThat(conn.computeBackoffMs(4)).isEqualTo(8_000L);
        assertThat(conn.computeBackoffMs(5)).isEqualTo(16_000L);
        assertThat(conn.computeBackoffMs(6)).isEqualTo(32_000L);
        assertThat(conn.computeBackoffMs(7)).isEqualTo(60_000L); // cap
        assertThat(conn.computeBackoffMs(20)).isEqualTo(60_000L); // still cap
    }

    @Test
    @DisplayName("reconnect: unbounded retries — bad server stays in RECONNECTING")
    void reconnect_unbounded() throws Exception {
        // Start the fixture, remember its real port, then kill it — that port is
        // now deterministically closed. (The old `pickLikelyClosedPort()` stub
        // hard-returned 1, so the test never exercised a port that had actually
        // been in use; Story #054 T019.)
        server = McpHttpTestSupport.startHttpServer();
        String deadUrl = server.baseUrl();
        server.close();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("dying")
                .transport(McpTransportType.SSE)
                .url(deadUrl)
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(2_000L) // reasonable cap so backoff doesn't sleep too long
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        // First attempt fails immediately, then RECONNECTING with backoff retries.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        // Span at least two backoff cycles (first hop is 1s, cap 2s) without the
        // connection ever leaving RECONNECTING. `during(...)` replaces the old
        // 8-second blind sleep (Story #054 T020: never sleep to await a state).
        await().atMost(Duration.ofSeconds(4))
            .during(Duration.ofMillis(2_000))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.name()).isEqualTo("dying");
    }

    @Test
    @DisplayName("EC-021c-4: SSE stream broken mid-flight → reconnect cycle kicks in")
    void sseStreamBroken_reconnects() throws Exception {
        // We reuse the same fixture used by SseMcpServerConnectionListenerTest:
        // /sse closes after 500ms; the reader breaks → triggers handleDisconnect.
        java.util.Map<String, String> sysProps = new java.util.HashMap<>();
        sysProps.put("closeSseAfter", "500");
        sysProps.put("pushIntervalMs", "100");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(10_000L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(2_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // After ~500ms the server closes /sse; the reader observes the EOF and the
        // connection should leave CONNECTED. We accept either RECONNECTING (still
        // retrying) or back-to-CONNECTED (if a fresh /sse reconnect succeeded).
        await().atMost(Duration.ofSeconds(8))
            .until(() -> conn.state() != ConnectionState.CONNECTED);
        assertThat(conn.state()).isIn(
            ConnectionState.RECONNECTING,
            ConnectionState.DISCONNECTED,
            ConnectionState.CONNECTED);
    }
}
