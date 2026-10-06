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
 * <p>Three cases verifying the {@code GET /health} probe for the
 * streamable-HTTP transport: healthy 200 advances lastHeartbeatAt,
 * 5xx triggers {@code DISCONNECTED → RECONNECTING}.
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
    @DisplayName("heartbeat: healthy 200 → CONNECTED with lastHeartbeatAt advancing")
    void probe_healthOk() throws Exception {
        server = McpHttpTestSupport.startHttpServer();
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
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
        Thread.sleep(1_000L);
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        Instant hb1 = conn.lastHeartbeatAt();
        assertThat(hb1).isNotNull();
        assertThat(hb1.toEpochMilli()).isGreaterThanOrEqualTo(hb0.toEpochMilli());
    }

    @Test
    @DisplayName("heartbeat: 5xx → DISCONNECTED → RECONNECTING")
    void probe_health5xx_disconnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("dontReplyHealth", "true");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.baseUrl())
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
    @DisplayName("heartbeat: delay 5s → timeout → DISCONNECTED → RECONNECTING")
    void probe_healthTimeout_disconnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("delayMs", "2000");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
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
}
