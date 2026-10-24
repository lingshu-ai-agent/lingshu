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
 * Story #021c — L2 + L3 tests for {@link SseMcpServerConnection} reconnect
 * (AC-021c-5), rewritten by Story #054 T034.
 *
 * <p>Three cases verifying:
 * <ol>
 *   <li>{@link SseMcpServerConnection#computeBackoffMs(int)} produces the
 *       expected 1s, 2s, 4s, 8s, 16s, 32s, 60s (cap) sequence.</li>
 *   <li>Reconnect is unbounded — after multiple failures, state remains
 *       {@link ConnectionState#RECONNECTING}.</li>
 *   <li>EC-021c-4: a broken SSE stream triggers reconnect — and the reconnect
 *       really re-runs the spec handshake instead of reusing the old
 *       session.</li>
 * </ol>
 *
 * <p><b>What changed</b> — the stream-broken case used to assert
 * {@code isIn(RECONNECTING, DISCONNECTED, CONNECTED)}: the set contains
 * {@code CONNECTED}, so the assertion was true by construction and could
 * never fail. It now asserts the observed state sequence.
 *
 * <p><b>Why {@link McpStateLog} and not a raw state list</b> —
 * {@code relistTools()} re-announces {@code CONNECTED} to the listeners every
 * time the server pushes {@code notifications/tools/list_changed}, without
 * the state having changed. Counting {@code CONNECTED} <em>notifications</em>
 * therefore counts one "connection" per server push — the await returned at
 * ~50 ms and the test then failed on a handshake that had not happened yet.
 * {@link McpStateLog#transitions()} keeps only genuine transitions.
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
    @DisplayName("reconnect: exponential backoff sequence = 1s, 2s, 4s, 8s, 16s, 32s, 60s(cap)")
    void backoff_sequence() throws Exception {
        // The URL is never dialed by this case (computeBackoffMs is pure),
        // but it is still a real — and closed — address rather than the old
        // "127.0.0.1:1" magic literal.
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("dummy")
                .transport(McpTransportType.SSE)
                .url(McpHttpTestSupport.pickClosedBaseUrl())
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
        String deadUrl = McpHttpTestSupport.pickClosedBaseUrl();
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
        await().atMost(Duration.ofSeconds(2))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        // Span at least one backoff cycle (first hop is 1s, cap 2s) without the
        // connection ever leaving RECONNECTING. `during(...)` replaces the old
        // 8-second blind sleep (Story #054 T020: never sleep to await a state).
        await().atMost(Duration.ofSeconds(2))
            .during(Duration.ofMillis(1_000))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.name()).isEqualTo("dying");
    }

    @Test
    @DisplayName("EC-021c-4: SSE stream broken mid-flight → reconnect cycle kicks in")
    void sseStreamBroken_reconnects() throws Exception {
        // The server closes every /sse stream after 200ms; the reader observes
        // the EOF → DISCONNECTED → RECONNECTING → a fresh handshake.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushIntervalMs", "50");
        sysProps.put("closeSseAfter", "200");
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
        log = McpStateLog.attach(conn);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        // The stream breaks → the transport must go through DISCONNECTED and
        // RECONNECTING and come back CONNECTED. The old assertion
        // ("isIn(RECONNECTING, DISCONNECTED, CONNECTED)") was true by
        // construction and could never fail.
        //
        // The evidence awaited here is the SECOND `initialize` in the
        // fixture's request log — unambiguous proof that the reconnect really
        // re-ran the spec handshake. (Awaiting a second CONNECTED
        // *notification* would be satisfied by the relist re-announcement
        // after ~50 ms, long before the 1 s backoff even elapses.)
        await().atMost(Duration.ofSeconds(5))
            .until(() -> countMethod(server, "initialize") >= 2);
        assertThat(log.transitions())
            .containsSubsequence(
                ConnectionState.CONNECTED,
                ConnectionState.DISCONNECTED,
                ConnectionState.RECONNECTING,
                ConnectionState.CONNECTED);
        // The reconnect must re-run the spec handshake, not silently reuse the
        // previous session.
        assertThat(countMethod(server, "initialize"))
            .as("the reconnect must re-run initialize")
            .isGreaterThanOrEqualTo(2);
        // Await the *second* genuine CONNECTED — the fixture records the
        // `initialize` POST a moment before the transport transitions, so
        // asserting the count right after the await above would be a race.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> log.connectCount() >= 2);
        assertThat(log.connectCount())
            .as("the transport must have reconnected after the stream broke")
            .isGreaterThanOrEqualTo(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────

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
