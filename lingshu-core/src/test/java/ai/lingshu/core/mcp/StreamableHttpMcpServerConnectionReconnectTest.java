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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L2 + L3 tests for
 * {@link StreamableHttpMcpServerConnection} reconnect (AC-021c-5).
 *
 * <p>Four cases verifying the exponential-backoff formula, unbounded retries
 * against an unreachable server, recovery after a transient failure, and the
 * FR-029 contract that a reconnect re-fetches the tool list.
 *
 * <p><b>Convention (research.md 决策 2)</b> — cases that need shrunken time
 * parameters construct the connection directly with the 4-arg injectable-time
 * constructor. {@code McpServerConnectionFactory.create(cfg)} only ever uses the
 * 1-arg constructor and cannot see these values.
 *
 * <p><b>Story #054 T044</b> — three changes:
 * <ol>
 *   <li>the blind {@code Thread.sleep(2_000L)} state wait is replaced by
 *       Awaitility polling (a state wait must never burn the 2 s per-case
 *       budget);</li>
 *   <li>the healthy-server case now pins the <em>new protocol</em> contract
 *       (exactly one handshake, exactly one tool-list fetch, the liveness
 *       probe is a JSON-RPC {@code ping});</li>
 *   <li>a new case asserts the <b>FR-029</b> contract — after a reconnect the
 *       tool list is fetched <em>again</em>, never served from the cache the
 *       pre-reconnect session had built.</li>
 * </ol>
 *
 * <p><b>Why the new case uses {@code expireMethod=ping}</b> — the transport's
 * 404-recovery path (clear the session id → re-run {@code initialize} → retry
 * the very same JSON-RPC id once) is driven by a 404 on a request that carries
 * a live session id. The heartbeat {@code ping} is such a request, so making
 * the fixture answer 404 to the {@code ping} — and to nothing else —
 * exercises the whole flap: in-place re-{@code initialize} → the retried
 * {@code ping} 404s again → the connection drops → the scheduled reconnect
 * re-runs the whole handshake. Expiring {@code tools/call} instead would keep
 * the heartbeat healthy and never reach the reconnect path.
 *
 * <p><b>Story #054 T044 — the flap is made deterministic</b> — the case used
 * to expire <em>every</em> {@code ping} ({@code expireMethodCount = -1}),
 * which left the request counts growing without bound and made the
 * "how many pings" part of the assertion depend on how long the test happened
 * to poll. It now expires exactly the first two {@code ping} requests
 * ({@code expireMethodCount = 2}), so the whole request sequence is fixed and
 * the counts can be asserted exactly: {@code initialize} × 3, {@code tools/list}
 * × 2, and the two flap {@code ping}s sharing one JSON-RPC id.
 */
@DisplayName("Story #021c — StreamableHttpMcpServerConnection reconnect")
class StreamableHttpMcpServerConnectionReconnectTest {

    /**
     * Tight polling for the two observations whose <em>distance</em> is
     * measured (the in-place re-{@code initialize} and the reconnect
     * handshake). The default 100 ms poll interval would add up to 100 ms of
     * detection lag to each side of the measurement, which is the whole
     * tolerance budget of the backoff-floor assertion.
     */
    private static final Duration POLL_DELAY = Duration.ofMillis(10);

    private static final Duration POLL_INTERVAL = Duration.ofMillis(20);

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
    void backoff_sequence() throws Exception {
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("dummy")
                .transport(McpTransportType.STREAMABLE_HTTP)
                // A port that is really closed — the old `http://127.0.0.1:1/mcp`
                // literal only proved that a privileged port is refused and never
                // exercised a port that had really been in use (Story #054 T019).
                // This case never dials it: it only pins the backoff formula.
                .url(McpHttpTestSupport.pickClosedBaseUrl() + "/mcp")
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
                // A port that is really closed — the old `http://127.0.0.1:1/mcp`
                // literal only proved that a privileged port is refused (Story
                // #054 T019 / T034 / T035 precedent).
                .url(McpHttpTestSupport.pickClosedBaseUrl() + "/mcp")
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(500L)
                .reconnectCapMs(2_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        // Span the first backoff hop (1 s) without ever leaving
        // RECONNECTING — the client must keep retrying, never give up. The
        // old 2 s window pushed the case past the 2 s per-case budget
        // (Story #054 T044: 1.2 s still covers the 1 s first hop + margin).
        await().atMost(Duration.ofSeconds(3))
            .during(Duration.ofMillis(1_200))
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

        // Wait for two heartbeat cycles — Awaitility polling, never a
        // `Thread.sleep`. The old `Thread.sleep(2_000L)` burned a full two
        // seconds of the 2 s per-case budget for what is a state wait
        // (Story #054 T044).
        await().atMost(Duration.ofSeconds(3))
            .until(() -> {
                return byMethod(server, "ping").size() >= 2;
            });

        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        List<RecordedRequest> recorded = server.recorded();
        assertThat(byMethod(recorded, "initialize"))
            .as("a healthy server must not trigger a second handshake")
            .hasSize(1);
        assertThat(byMethod(recorded, "tools/list"))
            .as("a healthy server must not trigger a second tool-list fetch")
            .hasSize(1);
        assertThat(byMethod(recorded, "ping"))
            .as("the liveness probe must be a JSON-RPC ping, at least two of them")
            .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("reconnect: session expiry → re-initialize → backoff → reconnect re-fetches tools/list (FR-029)")
    void reconnect_afterSessionExpiry_reRunsHandshake() throws Exception {
        // Exactly ONE flap, made deterministic by the fixture's method-scoped
        // expiry: `expireMethodCount = 2` answers 404 to the first two `ping`
        // requests and to nothing else. The resulting request sequence is
        // fixed:
        //
        //   ping#1         -> 404 -> in-place re-initialize (initialize#2)
        //   retried ping#1 -> 404 -> the connection drops, the reconnect is
        //                             scheduled (1000 ms backoff)
        //   reconnect      -> initialize#3 + tools/list#2 -> CONNECTED
        //   ping#2         -> 200 -> the recovered connection keeps beating
        //
        // `initialize` therefore happens exactly 3 times and `tools/list`
        // exactly twice — no timing-dependent counting any more.
        //
        // Story #054 T044 write-back: the old scenario used
        // `expireMethodCount = -1` (every ping 404s forever), which made the
        // request count grow without bound and left the "how many pings" part
        // of the assertion dependent on how long the test happened to poll.
        Map<String, String> props = new HashMap<>();
        props.put("expireMethod", "ping");
        props.put("expireMethodCount", "2");
        server = McpHttpTestSupport.startHttpServer(props);
        conn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(server.mcpEndpoint())
                .heartbeatIntervalMs(100L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());

        // The transport's OWN state timeline. The listener is registered
        // *before* `start()` so the very first CONNECTED is captured, and the
        // timestamps are taken inside the transport's own thread — a client
        // side poll can never add its own detection lag to the measurement.
        final List<StateMark> timeline = new CopyOnWriteArrayList<>();
        conn.onStateChange(new Consumer<ConnectionState>() {
            @Override
            public void accept(ConnectionState state) {
                timeline.add(new StateMark(state, System.nanoTime()));
            }
        });
        conn.start();

        // Synchronise on the reconnect handshake landing (initialize #3) — NOT
        // on `conn.state() == CONNECTED`.
        //
        // Story #054 T044 write-back: the old scenario gated on
        // `await().atMost(5s).until(() -> conn.state() == CONNECTED)` with
        // Awaitility's DEFAULT poll delay (100 ms). Its first evaluation
        // therefore landed at ~200 ms — *after* the first heartbeat tick
        // (~190 ms) had already flipped the state to RECONNECTING. The gate
        // then blocked through the whole ~1 s RECONNECTING window and only
        // resolved once the reconnect had completed, by which time initialize#3
        // had already fired. Both measured awaits then saw `initialize == 3`
        // at once and the measured distance collapsed to ~20 ms — a pure
        // test-design race (measured 5 RED / 3 GREEN out of 8 runs), not a
        // production backoff bug (the backoff was measured at 990–1003 ms by
        // an independent poll loop).
        await().atMost(Duration.ofSeconds(5))
            .pollDelay(POLL_DELAY)
            .pollInterval(POLL_INTERVAL)
            .until(() -> byMethod(server, "initialize").size() >= 3);

        // The reconnect must have gone DISCONNECTED -> RECONNECTING and back to
        // CONNECTED. Measure the distance *inside* the transport so the number
        // carries no client-side detection lag at all.
        StateMark disconnected = firstState(timeline, ConnectionState.DISCONNECTED);
        assertThat(disconnected)
            .as("the failed heartbeat must have knocked the connection down")
            .isNotNull();
        StateMark reconnected = firstStateAfter(timeline, ConnectionState.CONNECTED, disconnected);
        assertThat(reconnected)
            .as("the scheduled reconnect must have brought the connection back")
            .isNotNull();

        long reconnectGapMs = (reconnected.nanoTime - disconnected.nanoTime) / 1_000_000L;
        // The reconnect is scheduled with `computeBackoffMs(1) == 1000` (the
        // formula is pinned exactly by `backoff_sequence()` above). Because the
        // measurement is taken inside the transport, the observed distance is
        // the 1000 ms backoff plus the reconnect handshake — i.e. >= 1000 ms.
        // 900 ms stays the honest floor; the assertion is NOT weakened.
        assertThat(reconnectGapMs)
            .as("the reconnect must wait for the exponential backoff (first hop = 1s)")
            .isGreaterThanOrEqualTo(900L);
        assertThat(reconnectGapMs)
            .as("the reconnect must not be pathologically slow")
            .isLessThan(5_000L);

        // The flap was recovered exactly once. A second flap would have
        // produced a 4th `initialize` (and a 3rd `tools/list`).
        List<RecordedRequest> inits = byMethod(server.recorded(), "initialize");
        assertThat(inits)
            .as("initial handshake + in-place re-initialize + reconnect handshake")
            .hasSize(3);

        // The in-place 404 recovery must retry the *very same* JSON-RPC id,
        // exactly once. Both pings of the flap carry id=3; a second retry
        // would have produced a third ping with the same id.
        List<RecordedRequest> pings = byMethod(server.recorded(), "ping");
        List<RecordedRequest> flapPings = new ArrayList<>();
        for (RecordedRequest p : pings) {
            if (p.getId() != null && p.getId().equals(pings.get(0).getId())) {
                flapPings.add(p);
            }
        }
        assertThat(flapPings)
            .as("the 404-recovery path must retry the failed ping exactly once")
            .hasSize(2);
        assertThat(flapPings.get(1).getId())
            .as("the retried ping must reuse the very same JSON-RPC id")
            .isEqualTo(flapPings.get(0).getId());
        assertThat(flapPings.get(1).getSessionIdHeader())
            .as("the retried ping must ride the freshly negotiated session")
            .isNotNull();
        assertThat(flapPings.get(1).getSessionIdHeader())
            .as("the retried ping must not ride the expired session")
            .isNotEqualTo(flapPings.get(0).getSessionIdHeader());

        // FR-029 — after a reconnect the tool list MUST be fetched again; the
        // pre-reconnect cache must not be reused. The fixture serves a stable
        // tool list, so the observable evidence is the *request* itself: a
        // second `tools/list` really went out, riding the freshly negotiated
        // session rather than the expired one.
        //
        // The reconnect's `tools/list` is guaranteed to have been recorded by
        // the time the transport flipped to CONNECTED: `doConnect()` runs
        // `initialize` -> `tools/list` -> `transition(CONNECTED)`, and the
        // fixture records the request before answering it.
        List<RecordedRequest> lists = byMethod(server.recorded(), "tools/list");
        assertThat(lists)
            .as("FR-029: the reconnect must re-fetch the tool list")
            .hasSize(2);
        assertThat(lists.get(1).getSessionIdHeader())
            .as("the re-fetched tool list must ride the freshly negotiated session")
            .isNotNull();
        assertThat(lists.get(1).getSessionIdHeader())
            .as("the re-fetched tool list must not ride the expired session")
            .isNotEqualTo(lists.get(0).getSessionIdHeader());

        // The recovered connection must keep beating: the expiry budget of 2
        // is exhausted, so the next ping is answered 200 and the connection
        // stays up.
        await().atMost(Duration.ofSeconds(5))
            .pollDelay(POLL_DELAY)
            .pollInterval(POLL_INTERVAL)
            .until(() -> byMethod(server, "ping").size() >= 3);
        assertThat(conn.state())
            .as("the recovered connection must stay CONNECTED")
            .isEqualTo(ConnectionState.CONNECTED);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * One entry of the transport's own state timeline: the state it moved
     * into and the {@code System.nanoTime()} stamp taken <em>inside</em> the
     * transport's thread at that very moment.
     */
    private static final class StateMark {
        final ConnectionState state;
        final long nanoTime;

        StateMark(ConnectionState state, long nanoTime) {
            this.state = state;
            this.nanoTime = nanoTime;
        }
    }

    /** The first time the transport entered {@code state}, or {@code null}. */
    private static StateMark firstState(List<StateMark> timeline, ConnectionState state) {
        for (StateMark m : timeline) {
            if (m.state == state) {
                return m;
            }
        }
        return null;
    }

    /** The first time the transport entered {@code state} <em>after</em> {@code after}. */
    private static StateMark firstStateAfter(List<StateMark> timeline, ConnectionState state,
                                              StateMark after) {
        for (StateMark m : timeline) {
            if (m.nanoTime > after.nanoTime && m.state == state) {
                return m;
            }
        }
        return null;
    }

    private static List<RecordedRequest> byMethod(McpHttpTestSupport.ProcessHandle s, String method)
        throws Exception {
        List<RecordedRequest> out = new ArrayList<>();
        for (RecordedRequest r : s.recorded()) {
            if (method.equals(r.getMethod())) {
                out.add(r);
            }
        }
        return out;
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
