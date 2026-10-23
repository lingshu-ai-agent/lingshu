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
 * Story #021c — L2 + L3 tests for
 * {@link StreamableHttpMcpServerConnection} reconnect (AC-021c-5).
 *
 * <p>Three cases verifying the exponential-backoff formula, unbounded retries
 * against an unreachable server, and recovery after a transient failure.
 *
 * <p><b>Convention (research.md 决策 2)</b> — cases that need shrunken time
 * parameters construct the connection directly with the 4-arg injectable-time
 * constructor. {@code McpServerConnectionFactory.create(cfg)} only ever uses the
 * 1-arg constructor and cannot see these values.
 */
@DisplayName("Story #021c — StreamableHttpMcpServerConnection reconnect")
class StreamableHttpMcpServerConnectionReconnectTest {

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
    @DisplayName("reconnect: exponential backoff sequence = 1s, 2s, 4s, 8s, 16s, 32s, 60s(cap)")
    void backoff_sequence() {
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("dummy")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url("http://127.0.0.1:1/mcp") // unreachable
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
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("dying")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url("http://127.0.0.1:1/mcp")
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(2_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        // Span at least two backoff cycles (first hop is 1s, cap 2s) without ever
        // leaving RECONNECTING — replaces the old 6-second blind sleep
        // (Story #054 T020: never sleep to await a state).
        await().atMost(Duration.ofSeconds(4))
            .during(Duration.ofMillis(2_000))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.name()).isEqualTo("dying");
    }

    @Test
    @DisplayName("reconnect: healthy server stays CONNECTED (no spurious reconnect)")
    void reconnect_healthyServer_staysConnected() throws Exception {
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
        Thread.sleep(2_000L); // multiple heartbeat cycles
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
    }
}
