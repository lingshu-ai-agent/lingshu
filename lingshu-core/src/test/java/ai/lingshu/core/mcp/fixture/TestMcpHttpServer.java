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
package ai.lingshu.core.mcp.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single spec-shaped MCP HTTP server fixture — serves <em>both</em> MCP HTTP
 * transports on one {@link HttpServer} (Story #021c fixture, rewritten by
 * Story #054 T011—T015).
 *
 * <p><b>What changed in Story #054</b> — PR #90 rewrote
 * {@code SseMcpServerConnection} / {@code StreamableHttpMcpServerConnection}
 * to the MCP spec shape (single endpoint + {@code event: endpoint} announce +
 * JSON-RPC {@code ping} heartbeat) with zero tests. The two old fixtures still
 * spoke the retired 5-endpoint protocol and their {@code /sse} handler never
 * wrote an {@code event:} line, so the suite stayed green while no real MCP
 * server could connect. This file replaces both old fixtures with one
 * spec-shaped implementation (SC-006: zero duplication).
 *
 * <p><b>Endpoint map</b>:
 * <ul>
 *   <li>{@code GET /sse} — long-lived {@code text/event-stream}. First frame is
 *       {@code event: endpoint} carrying the <em>relative</em> POST URI
 *       {@code /messages} (FR-012). Subsequent frames are
 *       {@code event: message} carrying full JSON-RPC answers correlated by id
 *       (FR-013) plus optional server pushes (FR-029).</li>
 *   <li>{@code POST /messages} — the URI the SSE stream announced. Answers are
 *       <em>not</em> returned in this response (202, empty body); they are
 *       pushed on the stream.</li>
 *   <li>{@code POST /mcp} — the Streamable HTTP <em>single</em> endpoint. Every
 *       JSON-RPC message (including {@code initialize}) is POSTed here; the URL
 *       is never a path prefix (FR-017).</li>
 *   <li>{@code GET /__recorded} — test probe, <b>not</b> an MCP endpoint.
 *       Returns the recorded request log as JSON.</li>
 * </ul>
 *
 * <p><b>Removed on purpose</b> (see {@code contracts/fixture-protocol.md} §3):
 * {@code POST /initialize}, {@code POST /notifications/initialized},
 * {@code POST /tools/list}, {@code POST /tools/call}, {@code GET /health},
 * hard-coded response {@code id = 1}, and wildcard binds. Heartbeat is
 * JSON-RPC {@code ping}; there is no vendor health endpoint (FR-007).
 *
 * <p><b>Behavior switches</b> (system properties, forwarded verbatim by
 * {@link ai.lingshu.core.mcp.McpHttpTestSupport}):
 * <ul>
 *   <li>{@code port=N} — bind a specific port; {@code 0} = OS-assigned.</li>
 *   <li>{@code delayMs=N} — deliberate delay before every <em>JSON-RPC</em>
 *       reply (simulates a slow server; trips read timeouts).</li>
 *   <li>{@code exitAfter=N} — {@code System.exit(0)} once N JSON-RPC requests
 *       have been answered (simulates a crash). Probe reads don't count.</li>
 *   <li>{@code pushIntervalMs=N} — server-push cadence on {@code /sse}
 *       (default 200).</li>
 *   <li>{@code closeSseAfter=N} — close the SSE stream after N ms (simulates a
 *       reverse-proxy timeout).</li>
 *   <li>{@code malformedRatio=N} — every Nth pushed frame carries non-JSON
 *       {@code data:} (proves the parser tolerates junk).</li>
 *   <li>{@code expireSession=N} — the N-th (1-based) request bearing a
 *       non-empty {@code Mcp-Session-Id}, and every request after it, gets a
 *       <b>persistent</b> 404 (session-expiry chain, FR-020/021/022).
 *       {@code -1} (default) never expires. A <em>threshold</em> rather than a
 *       boolean because the Streamable HTTP handshake itself is
 *       session-bearing: {@code initialize} → {@code notifications/initialized}
 *       → {@code tools/list} are requests #1—#3, so "expire forever" could
 *       never let a client reach {@code CONNECTED}. {@code expireSession=4}
 *       expires exactly at the first heartbeat {@code ping}.</li>
 *   <li>{@code noEndpointAnnounce=true} — {@code GET /sse} never writes the
 *       {@code event: endpoint} frame, but the stream stays alive (FR-016).</li>
 *   <li>{@code omitProtocolVersion=true} — {@code initialize} omits
 *       {@code protocolVersion} from its result, to exercise the client-side
 *       fallback to {@code McpHttpSupport.PROTOCOL_VERSION} (FR-019).</li>
 * </ul>
 *
 * <p><b>Protocol constants are hardcoded on purpose</b> —
 * {@code McpHttpSupport.PROTOCOL_VERSION} / {@code HEADER_PROTOCOL_VERSION} /
 * {@code HEADER_SESSION_ID} are package-private in {@code ai.lingshu.core.mcp}
 * and this fixture lives in {@code ai.lingshu.core.mcp.fixture}, so it cannot
 * import them. The values below must stay character-identical to
 * {@code McpHttpSupport} (L87—L93) or assertions will fail at runtime with no
 * compile error to warn us — exactly the silent drift this Story removes.
 *
 * <p><b>Startup protocol</b> — prints {@code PORT=<n>} as the first stdout line
 * so the parent test can read the bound port from the subprocess pipe.
 *
 * <p><b>JDK 8 compatibility</b> — {@code com.sun.net.httpserver} (JDK 1.6+);
 * no {@code var} / {@code List.of} / {@code record} / {@code sealed} / text
 * blocks; record keeping uses {@code AtomicLong} +
 * {@link Collections#synchronizedList}.
 */
public final class TestMcpHttpServer {

    /**
     * Mirrors {@code McpHttpSupport.PROTOCOL_VERSION} (package-private there).
     */
    static final String PROTOCOL_VERSION = "2025-06-18";

    /** Mirrors {@code McpHttpSupport.HEADER_PROTOCOL_VERSION}. */
    static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    /** Mirrors {@code McpHttpSupport.HEADER_SESSION_ID}. */
    static final String HEADER_SESSION_ID = "Mcp-Session-Id";

    /** SSE stream path — {@code resolveSseUrl()} appends {@code "/sse"}. */
    public static final String PATH_SSE = "/sse";

    /** Streamable HTTP single endpoint — POSTed to by {@code cfg.getUrl()} verbatim. */
    public static final String PATH_MCP = "/mcp";

    /** Relative URI announced by {@code event: endpoint}; resolved against the SSE URL. */
    public static final String SSE_POST_URI = "/messages";

    /** Test probe (NOT an MCP endpoint): returns the recorded request log. */
    public static final String PATH_RECORDED = "/__recorded";

    /**
     * Pseudo-method recorded when the SSE stream is established. Not a JSON-RPC
     * method — it pins the moment "the stream is up" into the same ordered log
     * so a test can assert that zero JSON-RPC requests precede the announce.
     */
    public static final String SENTINEL_SSE_OPENED = "__sse_opened";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AtomicLong SEQ = new AtomicLong(0L);

    private static final List<RecordedRequest> RECORDER =
        Collections.synchronizedList(new ArrayList<RecordedRequest>());

    private static final AtomicInteger SESSION_SEQ = new AtomicInteger(0);

    /** Live SSE streams — answers pushed by POST handlers land on all of them. */
    private static final List<SseStream> SSE_STREAMS = new CopyOnWriteArrayList<>();

    /** One writer at a time across all SSE streams. */
    private static final Object SSE_WRITE_LOCK = new Object();

    private TestMcpHttpServer() {
        // utility class — never instantiated
    }

    // ── Entry point ──────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        final Config cfg = new Config();
        final AtomicInteger rpcCount = new AtomicInteger(0);

        // constitution §6 compatibility matrix: IPv4 loopback only. A wildcard
        // bind plus the `localhost` literal flakes on WSL2, where `localhost`
        // resolves to ::1 first and this server only listens on 127.0.0.1.
        HttpServer server = HttpServer.create(
            new InetSocketAddress("127.0.0.1", cfg.port), 0);

        server.createContext(PATH_SSE, new SseHandler(cfg));
        server.createContext(SSE_POST_URI, new SsePostHandler(cfg, rpcCount));
        server.createContext(PATH_MCP, new StreamableHandler(cfg, rpcCount));
        server.createContext(PATH_RECORDED, new RecordedHandler());

        // Cached pool: the /sse handler blocks for the life of the connection, so
        // POST handling must not queue behind it.
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        System.out.println("PORT=" + server.getAddress().getPort());
        System.out.flush();
    }

    // ── Recorder API (fixture side) ──────────────────────────────────────

    /** Recorded inbound requests, in arrival order (immutable snapshot). */
    public static List<RecordedRequest> recorded() {
        synchronized (RECORDER) {
            return Collections.unmodifiableList(new ArrayList<RecordedRequest>(RECORDER));
        }
    }

    /** Drop the recording — for "assert the handshake first, then the heartbeat". */
    public static void clearRecorded() {
        RECORDER.clear();
    }

    private static void record(String method, Long id, HttpExchange ex, String body) {
        RECORDER.add(new RecordedRequest(SEQ.incrementAndGet(), method, id,
            header(ex, "Content-Type"), header(ex, HEADER_SESSION_ID), body));
    }

    // ── SSE handler (GET /sse) ───────────────────────────────────────────

    private static final class SseHandler implements HttpHandler {

        private final Config cfg;

        SseHandler(Config cfg) {
            this.cfg = cfg;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            // 0 == unbounded / chunked. A fixed Content-Length would tell the
            // client the stream ended after the first event.
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            SseStream stream = new SseStream(os);
            SSE_STREAMS.add(stream);
            try {
                record(SENTINEL_SSE_OPENED, null, ex, "");
                if (!cfg.noEndpointAnnounce) {
                    // FR-012: relative URI, resolved by the client against the
                    // SSE stream's own URL — never an absolute URL.
                    stream.write("endpoint", SSE_POST_URI);
                }
                long start = System.currentTimeMillis();
                int pushes = 0;
                while (!Thread.currentThread().isInterrupted() && !stream.closed) {
                    Thread.sleep(Math.max(1, cfg.pushIntervalMs));
                    if (stream.closed) {
                        break;
                    }
                    boolean malformed = cfg.malformedRatio > 0
                        && pushes % cfg.malformedRatio == cfg.malformedRatio - 1;
                    if (malformed) {
                        stream.writeData("not-json-{{{");
                    } else {
                        ObjectNode note = MAPPER.createObjectNode();
                        note.put("jsonrpc", "2.0");
                        note.put("method", "notifications/tools/list_changed");
                        // No id — this is a notification (FR-014).
                        stream.write("message", note.toString());
                    }
                    pushes++;
                    if (cfg.closeSseAfterMs > 0
                        && System.currentTimeMillis() - start >= cfg.closeSseAfterMs) {
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // client went away — nothing left to push to
            } finally {
                SSE_STREAMS.remove(stream);
                stream.shutdown();
            }
        }
    }

    /** One open {@code GET /sse} response stream. */
    private static final class SseStream {

        private final OutputStream out;

        volatile boolean closed;

        SseStream(OutputStream out) {
            this.out = out;
        }

        void write(String event, String data) throws IOException {
            writeData(data, "event: " + event + "\n");
        }

        void writeData(String data) throws IOException {
            writeData(data, null);
        }

        private void writeData(String data, String eventLine) throws IOException {
            StringBuilder sb = new StringBuilder();
            if (eventLine != null) {
                sb.append(eventLine);
            }
            sb.append("data: ").append(data).append("\n\n");
            byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (SSE_WRITE_LOCK) {
                if (closed) {
                    throw new IOException("SSE stream already closed");
                }
                out.write(payload);
                out.flush();
            }
        }

        void shutdown() {
            synchronized (SSE_WRITE_LOCK) {
                closed = true;
                try {
                    out.close();
                } catch (IOException ignored) {
                    // best-effort
                }
            }
        }
    }

    // ── SSE POST handler (POST /messages — the announced URI) ───────────

    private static final class SsePostHandler implements HttpHandler {

        private final Config cfg;
        private final AtomicInteger rpcCount;

        SsePostHandler(Config cfg, AtomicInteger rpcCount) {
            this.cfg = cfg;
            this.rpcCount = rpcCount;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = readBody(ex);
            delay(cfg.delayMs);
            JsonNode req = parse(body);
            String method = methodOf(req);
            Long id = idOf(req);
            record(method, id, ex, body);
            if (id != null) {
                // The POST is only acknowledged; the answer rides the stream,
                // correlated by this id (FR-013).
                pushFrame("message",
                    MAPPER.writeValueAsString(success(id, cfg.resultFor(method, ex))));
            }
            ex.sendResponseHeaders(202, -1);
            ex.close();
            maybeExit(cfg, rpcCount);
        }
    }

    // ── Streamable HTTP handler (POST /mcp) ──────────────────────────────

    private static final class StreamableHandler implements HttpHandler {

        private final Config cfg;
        private final AtomicInteger rpcCount;

        /** Counts requests that carry a session id — the {@code expireSession} index. */
        private final AtomicInteger sessionCount = new AtomicInteger(0);

        StreamableHandler(Config cfg, AtomicInteger rpcCount) {
            this.cfg = cfg;
            this.rpcCount = rpcCount;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = readBody(ex);
            delay(cfg.delayMs);
            JsonNode req = parse(body);
            String method = methodOf(req);
            Long id = idOf(req);
            String sessionHeader = header(ex, HEADER_SESSION_ID);
            record(method, id, ex, body);

            // FR-020/021/022: once the session has "expired", every further
            // request carrying one gets a *persistent* 404, so the client's
            // re-initialize + retry-once is observable instead of being masked
            // by a one-shot 404.
            if (sessionHeader != null && !sessionHeader.isEmpty()
                && cfg.expireSession >= 0
                && sessionCount.incrementAndGet() >= cfg.expireSession) {
                byte[] payload = "session expired".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, payload.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(payload);
                }
                ex.close();
                maybeExit(cfg, rpcCount);
                return;
            }

            if (id == null) {
                // Notification (FR-014): acknowledged, never answered.
                ex.sendResponseHeaders(202, -1);
                ex.close();
                maybeExit(cfg, rpcCount);
                return;
            }

            if ("initialize".equals(method)) {
                // FR-018/024: the session id rides the *response header*, and
                // every initialize mints a brand-new one.
                ex.getResponseHeaders().set(HEADER_SESSION_ID,
                    "session-" + SESSION_SEQ.incrementAndGet());
            }
            byte[] payload = MAPPER.writeValueAsBytes(
                success(id, cfg.resultFor(method, ex)));
            if (wantsSse(ex)) {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(("event: message\ndata: "
                        + new String(payload, StandardCharsets.UTF_8) + "\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                ex.close();
            } else {
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, payload.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(payload);
                }
                ex.close();
            }
            maybeExit(cfg, rpcCount);
        }
    }

    /**
     * Whether this POST should be answered as an SSE stream (FR-023).
     *
     * <p>{@code McpHttpSupport.postJsonRpcSpec} hardcodes
     * {@code Accept: application/json, text/event-stream} on every call, so the
     * header alone cannot discriminate — preferring either content type would
     * leave the other branch dead. The rule is therefore "SSE only when the
     * caller asks for SSE <em>exclusively</em>": the spec transport keeps the
     * default {@code application/json} path, and a caller that narrows the
     * header (L1/L3 raw-HTTP tests) exercises the {@code text/event-stream}
     * path.
     */
    private static boolean wantsSse(HttpExchange ex) {
        String accept = header(ex, "Accept");
        if (accept == null) {
            return false;
        }
        String lower = accept.toLowerCase();
        return lower.contains("text/event-stream") && !lower.contains("application/json");
    }

    // ── Recorder probe (GET /__recorded) ─────────────────────────────────

    private static final class RecordedHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String query = ex.getRequestURI().getQuery();
            if (query != null && query.contains("clear=true")) {
                clearRecorded();
            }
            ObjectNode root = MAPPER.createObjectNode();
            ArrayNode arr = MAPPER.createArrayNode();
            for (RecordedRequest r : recorded()) {
                arr.add(r.toJson());
            }
            root.set("recorded", arr);
            byte[] payload = MAPPER.writeValueAsBytes(root);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, payload.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(payload);
            }
            ex.close();
        }
    }

    // ── Parent-side recorder probe ───────────────────────────────────────

    /**
     * Parent-process view of a fixture's recording. Works against a fixture
     * running as a subprocess (real socket) by reading {@code GET /__recorded},
     * and against an in-process fixture by calling {@link #recorded()} directly.
     */
    public static final class RequestRecorder {

        private RequestRecorder() {
            // utility
        }

        /**
         * Fetch the recorded request log from a fixture at {@code baseUrl}.
         *
         * @param baseUrl {@code http://127.0.0.1:<port>} as printed by the fixture
         * @return recorded requests in arrival order (never null)
         */
        public static List<RecordedRequest> fetch(String baseUrl) throws IOException {
            String base = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpURLConnection conn =
                (HttpURLConnection) new URL(base + PATH_RECORDED).openConnection();
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            try {
                InputStream is = conn.getInputStream();
                byte[] raw = readAll(is);
                if (raw.length == 0) {
                    return Collections.emptyList();
                }
                JsonNode node = MAPPER.readTree(raw);
                List<RecordedRequest> out = new ArrayList<>();
                JsonNode arr = node.path("recorded");
                if (arr.isArray()) {
                    for (JsonNode n : arr) {
                        out.add(RecordedRequest.fromJson(n));
                    }
                }
                return Collections.unmodifiableList(out);
            } finally {
                conn.disconnect();
            }
        }

        /** Drop the fixture's recording (parent-process view). */
        public static void clear(String baseUrl) throws IOException {
            String base = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            HttpURLConnection conn = (HttpURLConnection)
                new URL(base + PATH_RECORDED + "?clear=true").openConnection();
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            try {
                readAll(conn.getInputStream());
            } finally {
                conn.disconnect();
            }
        }
    }

    // ── Recorded request ─────────────────────────────────────────────────

    /**
     * One inbound JSON-RPC request, as the fixture saw it.
     *
     * <p>Public with public getters because tests live in the parent package
     * {@code ai.lingshu.core.mcp} while the fixture lives in
     * {@code ai.lingshu.core.mcp.fixture}.
     *
     * <p>JDK 8 compatible: plain final class + final fields + getters — no
     * {@code record}, no {@code var}.
     */
    public static final class RecordedRequest {

        private final long seq;
        private final String method;
        private final Long id;
        private final String contentType;
        private final String sessionIdHeader;
        private final String body;

        RecordedRequest(long seq, String method, Long id, String contentType,
                        String sessionIdHeader, String body) {
            this.seq = seq;
            this.method = method;
            this.id = id;
            this.contentType = contentType;
            this.sessionIdHeader = sessionIdHeader;
            this.body = body;
        }

        /** Monotonic arrival sequence — 1-based. */
        public long getSeq() {
            return seq;
        }

        /** JSON-RPC method name, e.g. {@code initialize} / {@code ping}. */
        public String getMethod() {
            return method;
        }

        /** JSON-RPC id; {@code null} means this was a notification (must not be answered). */
        public Long getId() {
            return id;
        }

        /** Raw {@code Content-Type} request header. */
        public String getContentType() {
            return contentType;
        }

        /** Raw {@code Mcp-Session-Id} request header; {@code null} if absent. */
        public String getSessionIdHeader() {
            return sessionIdHeader;
        }

        /** Raw request body. */
        public String getBody() {
            return body;
        }

        ObjectNode toJson() {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("seq", seq);
            n.put("method", method == null ? "" : method);
            if (id == null) {
                n.putNull("id");
            } else {
                n.put("id", id.longValue());
            }
            if (contentType == null) {
                n.putNull("contentType");
            } else {
                n.put("contentType", contentType);
            }
            if (sessionIdHeader == null) {
                n.putNull("sessionIdHeader");
            } else {
                n.put("sessionIdHeader", sessionIdHeader);
            }
            n.put("body", body == null ? "" : body);
            return n;
        }

        static RecordedRequest fromJson(JsonNode n) {
            Long id = (n.has("id") && !n.get("id").isNull())
                ? Long.valueOf(n.get("id").asLong()) : null;
            return new RecordedRequest(
                n.path("seq").asLong(0L),
                n.path("method").asText(""),
                id,
                n.has("contentType") && !n.get("contentType").isNull()
                    ? n.get("contentType").asText() : null,
                n.has("sessionIdHeader") && !n.get("sessionIdHeader").isNull()
                    ? n.get("sessionIdHeader").asText() : null,
                n.path("body").asText(""));
        }

        @Override
        public String toString() {
            return "#" + seq + " " + method + " id=" + id
                + " session=" + sessionIdHeader;
        }
    }

    // ── Switches ─────────────────────────────────────────────────────────

    private static final class Config {

        final int port = intProp("port", 0);
        final int delayMs = intProp("delayMs", 0);
        final int exitAfter = intProp("exitAfter", -1);
        final int pushIntervalMs = intProp("pushIntervalMs", 200);
        final int closeSseAfterMs = intProp("closeSseAfter", -1);
        final int malformedRatio = intProp("malformedRatio", -1);
        final int expireSession = intProp("expireSession", -1);
        final boolean noEndpointAnnounce = boolProp("noEndpointAnnounce", false);
        final boolean omitProtocolVersion = boolProp("omitProtocolVersion", false);

        ObjectNode resultFor(String method, HttpExchange ex) {
            ObjectNode r = MAPPER.createObjectNode();
            if ("initialize".equals(method)) {
                if (!omitProtocolVersion) {
                    String requested = header(ex, HEADER_PROTOCOL_VERSION);
                    r.put("protocolVersion", requested == null || requested.isEmpty()
                        ? PROTOCOL_VERSION : requested);
                }
                ObjectNode info = MAPPER.createObjectNode();
                info.put("name", "test-mcp-http");
                info.put("version", "1.0.0");
                r.set("serverInfo", info);
                r.set("capabilities", MAPPER.createObjectNode());
                return r;
            }
            if ("tools/list".equals(method)) {
                ArrayNode tools = MAPPER.createArrayNode();
                ObjectNode tool = MAPPER.createObjectNode();
                tool.put("name", "echo");
                tool.put("description", "echo input");
                ObjectNode schema = MAPPER.createObjectNode();
                schema.put("type", "object");
                ObjectNode props = MAPPER.createObjectNode();
                ObjectNode inputProp = MAPPER.createObjectNode();
                inputProp.put("type", "string");
                props.set("input", inputProp);
                schema.set("properties", props);
                tool.set("inputSchema", schema);
                tools.add(tool);
                r.set("tools", tools);
                return r;
            }
            if ("tools/call".equals(method)) {
                r.put("content", "fake-result");
                r.put("isError", false);
                return r;
            }
            // ping and anything else: an empty result is a valid JSON-RPC answer.
            return r;
        }
    }

    private static int intProp(String key, int fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean boolProp(String key, boolean fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    // ── JSON-RPC helpers ─────────────────────────────────────────────────

    /**
     * Build a JSON-RPC success envelope. The id always <em>echoes</em> the
     * request id (FR-006) — the old fixtures hard-coded {@code id = 1}, which
     * is why id correlation was never actually exercised.
     */
    private static ObjectNode success(long id, JsonNode result) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.set("result", result);
        return env;
    }

    private static void pushFrame(String event, String data) {
        for (SseStream stream : SSE_STREAMS) {
            try {
                stream.write(event, data);
            } catch (IOException e) {
                SSE_STREAMS.remove(stream);
                stream.shutdown();
            }
        }
    }

    private static String methodOf(JsonNode req) {
        return req.path("method").asText("");
    }

    private static Long idOf(JsonNode req) {
        JsonNode n = req.get("id");
        return (n == null || n.isNull()) ? null : Long.valueOf(n.asLong());
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return MAPPER.createObjectNode();
        }
    }

    private static String header(HttpExchange ex, String name) {
        List<String> values = ex.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) {
            // HttpExchange header lookup is case-sensitive on some platforms.
            for (String key : ex.getRequestHeaders().keySet()) {
                if (key.equalsIgnoreCase(name)) {
                    values = ex.getRequestHeaders().get(key);
                    break;
                }
            }
        }
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(readAll(ex.getRequestBody()), StandardCharsets.UTF_8);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        if (is == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static void delay(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Crash the fixture once {@code exitAfter} JSON-RPC requests have been
     * answered. The probe endpoint never counts, and the exit happens
     * <em>after</em> the response is written so the client sees a complete
     * answer before the socket disappears.
     */
    private static void maybeExit(Config cfg, AtomicInteger rpcCount) {
        if (cfg.exitAfter <= 0) {
            return;
        }
        if (rpcCount.incrementAndGet() >= cfg.exitAfter) {
            System.err.println("[TestMcpHttpServer] exitAfter=" + cfg.exitAfter
                + " reached, exiting");
            System.exit(0);
        }
    }
}