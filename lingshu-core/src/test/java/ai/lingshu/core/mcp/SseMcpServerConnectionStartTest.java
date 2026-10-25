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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021c — L2 + L3 tests for {@link SseMcpServerConnection#start()}
 * (AC-021c-1), rewritten by Story #054 T027—T031.
 *
 * <p><b>What changed</b> — PR #90 rewrote the SSE transport to the MCP spec
 * shape ({@code event: endpoint} announce + JSON-RPC {@code ping} heartbeat)
 * but shipped <em>zero</em> tests, so the old suite kept asserting the
 * retired 5-endpoint protocol and stayed green while no real MCP server could
 * connect. These five cases now assert the four-part spec handshake:
 * <ol>
 *   <li>the client opens {@code GET /sse};</li>
 *   <li>the server's <em>first</em> frame is {@code event: endpoint};</li>
 *   <li>the client POSTs to <em>the URI that was announced</em>;</li>
 *   <li>the answer comes back on the stream as {@code event: message} and its
 *       JSON-RPC id equals the request id.</li>
 * </ol>
 * plus the two protocol guards: a relative announced URI must resolve against
 * the SSE stream's own origin (FR-012), and answers must be correlated by id
 * rather than by arrival order (FR-013 / FR-014).
 *
 * <p><b>Observation technique</b> — the fixture pushes every answer to
 * <em>all</em> open SSE streams, so the test opens a second, independent
 * {@code GET /sse} connection ({@link RawSseObserver}) and reads the wire
 * itself: the first frame, the announced URI, and the id of every answer.
 * That is what makes "the first frame really is {@code event: endpoint}" a
 * real assertion instead of a self-fulfilling one.
 *
 * <p><b>Convention (research.md 决策 2)</b> — cases that need shrunken time
 * parameters construct the connection directly with the 4-arg injectable-time
 * constructor. {@code McpServerConnectionFactory.create(cfg)} only ever uses
 * the 1-arg constructor and cannot see these values.
 */
@DisplayName("Story #021c — SseMcpServerConnection start handshake")
class SseMcpServerConnectionStartTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** SSE path the transport appends to {@code cfg.getUrl()}. */
    private static final String SSE_PATH = "/sse";

    private McpHttpTestSupport.ProcessHandle server;
    private SseMcpServerConnection conn;
    private RawSseObserver observer;
    private ExecutorService pool;

    @AfterEach
    void tearDown() {
        if (observer != null) {
            observer.close();
        }
        if (conn != null) {
            conn.close();
        }
        if (server != null) {
            server.close();
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    // ── T027 — 握手四段断言 ────────────────────────────────────────────

    @Test
    @DisplayName("handshake: ① GET /sse ② event: endpoint 首帧 ③ POST 到公告 URI ④ event: message 回包 id 匹配")
    void start_fakeServer_5stepsReachesConnected() throws Exception {
        // pushNotifications=false — the fixture's periodic
        // notifications/tools/list_changed would otherwise make the transport
        // re-issue tools/list every 200ms, so the request/answer pairs under
        // assertion would never settle. The handshake itself is unaffected:
        // the endpoint frame is written before the push loop starts.
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);

        observer = RawSseObserver.open(server.baseUrl() + SSE_PATH);
        // Sync point: the endpoint frame proves the fixture registered this
        // stream, so no answer can be pushed before the observer is listening.
        await().atMost(Duration.ofSeconds(2))
            .until(() -> observer.endpointData() != null);

        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // ── ① the client opened the SSE stream ──
        List<RecordedRequest> recorded = server.recorded();
        assertThat(recorded)
            .as("① fixture must have received GET /sse")
            .anyMatch(r -> "GET".equals(r.getHttpMethod()) && SSE_PATH.equals(r.getPath()));

        // ── ② the server's FIRST frame is `event: endpoint` ──
        assertThat(observer.firstEvent())
            .as("② the first frame on the SSE stream")
            .isEqualTo("endpoint");
        assertThat(observer.endpointData())
            .as("② the announced URI")
            .isEqualTo("/messages");

        // ── ③ the client POSTs to the announced URI (not a hard-coded path) ──
        assertThat(recorded)
            .as("③ the client must POST to the announced URI")
            .anyMatch(r -> "POST".equals(r.getHttpMethod()) && "/messages".equals(r.getPath()));

        // ── ④ answers come back as `event: message`, id-matched ──
        await().atMost(Duration.ofSeconds(2))
            .until(() -> observer.answerIds().size() >= 2);
        List<RecordedRequest> rpcRequests = new ArrayList<>();
        for (RecordedRequest r : recorded) {
            if (r.getId() != null) {
                rpcRequests.add(r);
            }
        }
        assertThat(rpcRequests)
            .as("the handshake is exactly 2 id-bearing requests: initialize + tools/list")
            .hasSize(2);
        for (RecordedRequest request : rpcRequests) {
            long id = request.getId().longValue();
            RawSseObserver.Frame answer = observer.answerFor(id);
            assertThat(answer)
                .as("④ an `event: message` frame must answer request id=%d", id)
                .isNotNull();
            assertThat(answer.getEvent()).isEqualTo("message");
            JsonNode body = MAPPER.readTree(answer.getData());
            assertThat(body.path("id").asLong())
                .as("④ the answer id must equal the request id")
                .isEqualTo(id);
            // Semantic pairing — an id that merely appears somewhere is not
            // enough: the right answer must carry the right payload.
            if ("initialize".equals(request.getMethod())) {
                assertThat(body.path("result").path("protocolVersion").asText())
                    .as("④ the initialize answer must carry the negotiated version")
                    .isEqualTo("2025-06-18");
            } else if ("tools/list".equals(request.getMethod())) {
                assertThat(body.path("result").path("tools"))
                    .as("④ the tools/list answer must carry the tool list")
                    .isNotEmpty();
            }
        }
        assertThat(observer.answerIds())
            .as("④ no answer may appear without a matching request")
            .allMatch(id -> rpcRequests.stream()
                .anyMatch(r -> r.getId() != null && r.getId().longValue() == id.longValue()));

        // ── final state + the pre-existing assertions of this case ──
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");
    }

    // ── T028 — 相对 URI 解析 (FR-012) ───────────────────────────────────

    @Test
    @DisplayName("handshake: 相对公告 URI 以 SSE 流所在 origin 为基准解析")
    void handshake_relativeAnnouncedUri_resolvesAgainstSseOrigin() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushNotifications", "false");
        sysProps.put("announceUri", "/messages?sessionId=abc");
        server = McpHttpTestSupport.startHttpServer(sysProps);

        observer = RawSseObserver.open(server.baseUrl() + SSE_PATH);
        await().atMost(Duration.ofSeconds(2))
            .until(() -> observer.endpointData() != null);

        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        // The announced URI is relative — resolution must be relative to the
        // SSE stream's own URL, not to the JVM's working directory.
        assertThat(observer.endpointData())
            .as("the fixture announces a relative URI")
            .isEqualTo("/messages?sessionId=abc");

        List<RecordedRequest> recorded = server.recorded();
        List<RecordedRequest> posts = new ArrayList<>();
        for (RecordedRequest r : recorded) {
            if ("POST".equals(r.getHttpMethod())) {
                posts.add(r);
            }
        }
        assertThat(posts).as("the client must POST at least once").isNotEmpty();
        for (RecordedRequest post : posts) {
            assertThat(post.getPath())
                .as("the POST must go to the announced URI, query string included")
                .isEqualTo("/messages?sessionId=abc");
            // Same host:port as the SSE stream — proof the origin was kept.
            assertThat(post.getHost())
                .as("the resolved URI must keep the SSE stream's origin")
                .isEqualTo("127.0.0.1:" + server.baseUrl().substring(
                    "http://127.0.0.1:".length()));
        }
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
    }

    // ── T029 — 并发 / 乱序 id 关联 (FR-013 / FR-014) ────────────────────

    @Test
    @DisplayName("handshake: 并发 tools/call 的应答按 id 关联，与到达顺序无关")
    void handshake_concurrentCalls_correlateByIdNotArrivalOrder() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushNotifications", "false");
        // The fixture buffers two tools/call answers and pushes them in
        // REVERSE arrival order — deterministic proof that the client does
        // not correlate by arrival order.
        sysProps.put("outOfOrder", "true");
        server = McpHttpTestSupport.startHttpServer(sysProps);

        observer = RawSseObserver.open(server.baseUrl() + SSE_PATH);
        await().atMost(Duration.ofSeconds(2))
            .until(() -> observer.endpointData() != null);

        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        pool = Executors.newFixedThreadPool(2);
        Future<McpCallResult> first = pool.submit(
            () -> conn.callTool("echo", toolInput("alpha")));
        Future<McpCallResult> second = pool.submit(
            () -> conn.callTool("echo", toolInput("beta")));

        McpCallResult firstResult = first.get(5, TimeUnit.SECONDS);
        McpCallResult secondResult = second.get(5, TimeUnit.SECONDS);

        // The fixture echoes each call's input back, so a client that
        // mis-correlated the two answers would swap these two strings.
        assertThat(firstResult.isError()).isFalse();
        assertThat(secondResult.isError()).isFalse();
        assertThat(firstResult.getContent()).isEqualTo("alpha");
        assertThat(secondResult.getContent()).isEqualTo("beta");

        // And the wire itself must show the answers arriving in the reverse
        // of the order the requests were POSTed.
        await().atMost(Duration.ofSeconds(2))
            .until(() -> observer.answerIds().size() >= 4);
        List<Long> requestOrder = new ArrayList<>();
        for (RecordedRequest r : server.recorded()) {
            if ("tools/call".equals(r.getMethod()) && r.getId() != null) {
                requestOrder.add(r.getId());
            }
        }
        assertThat(requestOrder).as("two tools/call requests must be recorded").hasSize(2);
        List<Long> reversed = new ArrayList<>(requestOrder);
        Collections.reverse(reversed);
        assertThat(observer.answerIds())
            .as("the two answers must be pushed in reverse request-arrival order")
            .containsSubsequence(reversed);
    }

    // ── T030 — 无公告必失败的反证 (FR-016 / SC-003) ─────────────────────

    @Test
    @DisplayName("handshake: 服务端不公告 endpoint → 永不 CONNECTED，且不发任何 JSON-RPC")
    void handshake_noEndpointAnnounce_neverConnects() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("noEndpointAnnounce", "true");
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);

        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(600L)
                .reconnectCapMs(10_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();

        // The handshake must abort at step ① — before a single JSON-RPC
        // message is sent.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
        await().atMost(Duration.ofSeconds(2))
            .during(Duration.ofMillis(600))
            .until(() -> conn.state() != ConnectionState.CONNECTED);
        assertThat(conn.state())
            .as("without an `endpoint` frame the connection can never be CONNECTED")
            .isNotEqualTo(ConnectionState.CONNECTED);

        for (RecordedRequest r : server.recorded()) {
            assertThat(r.getId())
                .as("no JSON-RPC request may be sent when the endpoint was never announced")
                .isNull();
            assertThat(r.getMethod() == null ? "" : r.getMethod())
                .as("no JSON-RPC method may be sent when the endpoint was never announced")
                .doesNotStartWith("notifications/");
        }
    }

    // ── T031 — 心跳走 JSON-RPC ping，绝不是 GET /health (FR-015 / FR-007) ──

    @Test
    @DisplayName("heartbeat: liveness probe 是 JSON-RPC ping，全过程零 GET /health")
    void heartbeat_usesJsonRpcPing_neverCallsHealthEndpoint() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);

        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(200L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build(),
            200L, 1_000L, 1_000L);
        conn.start();
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        await().atMost(Duration.ofSeconds(3))
            .until(() -> server.recorded().stream()
                .anyMatch(r -> "ping".equals(r.getMethod()) && r.getId() != null));

        List<RecordedRequest> recorded = server.recorded();
        assertThat(recorded)
            .as("the liveness probe must be a JSON-RPC ping")
            .anyMatch(r -> "ping".equals(r.getMethod()) && r.getId() != null);
        assertThat(recorded)
            .as("FR-007: there is no vendor health endpoint")
            .noneMatch(r -> r.getPath() != null && r.getPath().contains("/health"));
        // Stronger form of the same claim: the SSE stream is the ONLY GET the
        // client ever makes.
        List<RecordedRequest> gets = new ArrayList<>();
        for (RecordedRequest r : recorded) {
            if ("GET".equals(r.getHttpMethod())) {
                gets.add(r);
            }
        }
        assertThat(gets)
            .as("the only GET the client may make is the SSE stream itself")
            .allMatch(r -> SSE_PATH.equals(r.getPath()));
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
    }

    // ── 既有用例（保持原有断言） ─────────────────────────────────────────

    @Test
    @DisplayName("start: invalid URL (connection refused) → schedules RECONNECTING")
    void start_invalidUrl_immediateFailureSchedulesReconnect() throws Exception {
        // A real, just-closed port instead of the magic "127.0.0.1:1".
        String deadUrl = McpHttpTestSupport.pickClosedBaseUrl();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.SSE)
                .url(deadUrl)
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
    @DisplayName("start: missing URL → start fails / schedules RECONNECTING")
    void start_missingUrl_illegalArgumentOrReconnect() {
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("missing-url")
                .transport(McpTransportType.SSE)
                // no url
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        // Either IllegalArgumentException was thrown by doConnect() (caught by start()
        // and converted to RECONNECTING) or the state advances to RECONNECTING.
        await().atMost(Duration.ofSeconds(5))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    @Test
    @DisplayName("start: second start() while CONNECTED is a no-op")
    void start_alreadyConnected_noop() throws Exception {
        Map<String, String> sysProps = new HashMap<>();
        sysProps.put("pushNotifications", "false");
        server = McpHttpTestSupport.startHttpServer(sysProps);
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(server.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(8))
            .until(() -> conn.state() == ConnectionState.CONNECTED);
        conn.start(); // second time — must be no-op
        assertThat(conn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(conn.listTools()).hasSize(1);
    }

    @Test
    @DisplayName("start: during RECONNECTING is a no-op")
    void start_duringReconnecting_noop() throws Exception {
        String deadUrl = McpHttpTestSupport.pickClosedBaseUrl();
        conn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.SSE)
                .url(deadUrl)
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(10_000L) // long cap keeps it in RECONNECTING
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3))
            .until(() -> conn.state() == ConnectionState.RECONNECTING);
        conn.start(); // should not throw, should not change state
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** {@code {"input": "<value>"}} — the fixture echoes this back verbatim. */
    private static com.fasterxml.jackson.databind.node.ObjectNode toolInput(String value) {
        com.fasterxml.jackson.databind.node.ObjectNode args =
            MAPPER.createObjectNode();
        args.put("input", value);
        return args;
    }

    /**
     * An independent {@code GET /sse} reader.
     *
     * <p>The fixture pushes every answer to <em>all</em> open streams, so this
     * observer sees the very same frames the transport sees — which is what
     * makes "the first frame is {@code event: endpoint}" and "the answer id
     * equals the request id" observable from the outside.
     */
    private static final class RawSseObserver {

        private final HttpURLConnection conn;
        private final List<Frame> frames = new CopyOnWriteArrayList<>();
        private final Thread reader;
        private volatile boolean stopped;

        private RawSseObserver(HttpURLConnection conn) {
            this.conn = conn;
            this.reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }, "raw-sse-observer");
            this.reader.setDaemon(true);
        }

        static RawSseObserver open(String sseUrl) throws IOException {
            HttpURLConnection conn =
                (HttpURLConnection) new URL(sseUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setConnectTimeout(2_000);
            conn.setReadTimeout(2_000);
            conn.setRequestProperty("Accept", "text/event-stream");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("SSE observer got HTTP " + code);
            }
            RawSseObserver observer = new RawSseObserver(conn);
            observer.reader.start();
            return observer;
        }

        private void readLoop() {
            try {
                BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String event = null;
                StringBuilder data = new StringBuilder();
                while (!stopped) {
                    String line;
                    try {
                        line = in.readLine();
                    } catch (SocketTimeoutException e) {
                        // idle gap — keep waiting
                        continue;
                    }
                    if (line == null) {
                        break;
                    }
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            frames.add(new Frame(event, data.toString()));
                            data.setLength(0);
                        }
                        event = null;
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).trim());
                    }
                }
            } catch (IOException e) {
                // stream gone — whatever was collected so far stands
            }
        }

        /** Event name of the very first frame. */
        String firstEvent() {
            List<Frame> snapshot = new ArrayList<>(frames);
            return snapshot.isEmpty() ? null : snapshot.get(0).getEvent();
        }

        /** Data carried by the first {@code event: endpoint} frame. */
        String endpointData() {
            for (Frame f : new ArrayList<>(frames)) {
                if ("endpoint".equals(f.getEvent())) {
                    return f.getData();
                }
            }
            return null;
        }

        /** Ids of every {@code event: message} answer, in push order. */
        List<Long> answerIds() {
            List<Long> ids = new ArrayList<>();
            for (Frame f : new ArrayList<>(frames)) {
                if (!"message".equals(f.getEvent())) {
                    continue;
                }
                try {
                    JsonNode node = MAPPER.readTree(f.getData());
                    JsonNode id = node.get("id");
                    if (id != null && !id.isNull() && id.canConvertToLong()) {
                        ids.add(Long.valueOf(id.asLong()));
                    }
                } catch (IOException e) {
                    // non-JSON frame — not an answer
                }
            }
            return ids;
        }

        /** The {@code event: message} frame answering {@code id}, or null. */
        Frame answerFor(long id) {
            for (Frame f : new ArrayList<>(frames)) {
                if (!"message".equals(f.getEvent())) {
                    continue;
                }
                try {
                    JsonNode node = MAPPER.readTree(f.getData());
                    JsonNode nodeId = node.get("id");
                    if (nodeId != null && !nodeId.isNull()
                        && nodeId.canConvertToLong() && nodeId.asLong() == id) {
                        return f;
                    }
                } catch (IOException e) {
                    // not an answer
                }
            }
            return null;
        }

        void close() {
            stopped = true;
            conn.disconnect();
        }

        /** One decoded SSE frame. */
        static final class Frame {

            private final String event;
            private final String data;

            Frame(String event, String data) {
                this.event = event;
                this.data = data;
            }

            String getEvent() {
                return event;
            }

            String getData() {
                return data;
            }
        }
    }
}
