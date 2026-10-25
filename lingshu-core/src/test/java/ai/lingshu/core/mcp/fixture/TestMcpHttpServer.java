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
 *   <li>{@code delayAfter=N} — apply {@code delayMs} only from the N-th
 *       (1-based) request onward; the first N-1 requests are answered
 *       immediately. This is what makes "the server got slow <em>after</em>
 *       the handshake" observable: with a plain {@code delayMs} the handshake
 *       itself would time out and the heartbeat would never be reached.</li>
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
 *   <li>{@code expireMethod=tools/call} + {@code expireMethodCount=N} —
 *       <b>method-scoped</b> session expiry (Story #054 T037/T038). The first N
 *       occurrences of that JSON-RPC method get a 404; the handshake traffic
 *       ({@code initialize} / {@code notifications/initialized} /
 *       {@code tools/list}) is <em>unaffected</em>. This is the switch that
 *       makes "the client re-handshakes and then retries with the same id"
 *       observable — the global {@code expireSession} threshold is
 *       <em>monotonic</em> ("from the N-th session-bearing request on, every
 *       request 404s"), so the re-handshake's own
 *       {@code notifications/initialized} POST would 404 too and the
 *       retry-once path could never be reached. {@code expireMethodCount=0}
 *       (default) disables it; {@code -1} = every occurrence (unlimited);
 *       {@code N > 0} = the first N occurrences. Orthogonal to
 *       {@code expireSession} — both can be active at the same time.</li>
 *   <li>{@code omitSessionId=true} — {@code initialize} omits the
 *       {@code Mcp-Session-Id} response header, so the client never holds a
 *       local session id (stateless server shape, Story #054 T039). A 404 must
 *       then NOT trigger a re-handshake.</li>
 *   <li>{@code respondSse=true} — answer every {@code POST /mcp} as
 *       {@code text/event-stream}, whatever the request's {@code Accept} header
 *       says (Story #054 T040). Needed because
 *       {@code McpHttpSupport.postJsonRpcSpec} hardcodes
 *       {@code Accept: application/json, text/event-stream} and
 *       {@link #wantsSse} only answers SSE when the caller asks for SSE
 *       <em>exclusively</em> — the spec transport can therefore never obtain
 *       the SSE shape from this fixture without this switch.</li>
 *   <li>{@code noEndpointAnnounce=true} — {@code GET /sse} never writes the
 *       {@code event: endpoint} frame, but the stream stays alive (FR-016).</li>
 *   <li>{@code omitProtocolVersion=true} — {@code initialize} omits
 *       {@code protocolVersion} from its result, to exercise the client-side
 *       fallback to {@code McpHttpSupport.PROTOCOL_VERSION} (FR-019).</li>
 *   <li>{@code announceUri=/some/path} — the URI carried by the
 *       {@code event: endpoint} frame. Default {@value #SSE_POST_URI}. Lets a
 *       test announce a <em>relative</em> URI with a query string
 *       (FR-012) and check how the client resolves it.</li>
 *   <li>{@code outOfOrder=true} — {@code tools/call} answers are buffered in
 *       pairs and pushed on the stream in <em>reverse</em> arrival order, so a
 *       test can prove the client correlates answers by JSON-RPC id rather
 *       than by arrival order (FR-013).</li>
 *   <li>{@code pushNotifications=false} — the periodic SSE push becomes an
 *       SSE <em>comment</em> keepalive instead of a
 *       {@code notifications/tools/list_changed} message. The response keeps
 *       producing bytes (a client disconnecting mid-stream would otherwise
 *       block until the next push) but the client is not nudged into
 *       re-issuing {@code tools/list} on every tick.</li>
 * </ul>
 *
 * <p><b>Story #054 — why the catch-all context exists</b>: without a
 * {@code "/"} context an unmatched request (e.g. a re-introduced vendor
 * {@code GET /health}) would 404 <em>silently</em> and never reach the
 * recorder, so "the client never sent a {@code /health} probe" would be a
 * vacuous assertion. The catch-all records every unmatched request, which
 * turns that assertion into a real one (FR-007).
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
        server.createContext(SSE_POST_URI, new SsePostHandler(cfg, rpcCount, true));
        server.createContext(PATH_MCP, new StreamableHandler(cfg, rpcCount));
        server.createContext(PATH_RECORDED, new RecordedHandler());
        // Catch-all: every path that no other context claims is still recorded
        // (so "the client never sent GET /health" is a real assertion, not a
        // vacuous one). Longest-prefix matching means the explicit contexts
        // above always win.
        server.createContext("/", new SsePostHandler(cfg, rpcCount, false));

        if (cfg.outOfOrder) {
            startOutOfOrderFlusher();
        }

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
            header(ex, "Content-Type"), header(ex, HEADER_SESSION_ID), body,
            ex == null ? null : ex.getRequestMethod(),
            pathOf(ex),
            header(ex, "Host")));
    }

    /**
     * Request path <em>including</em> the query string — the announced
     * {@code event: endpoint} URI may carry one (e.g.
     * {@code /messages?sessionId=abc}) and the client must POST to exactly
     * that URI.
     */
    private static String pathOf(HttpExchange ex) {
        if (ex == null || ex.getRequestURI() == null) {
            return null;
        }
        String rawPath = ex.getRequestURI().getRawPath();
        String query = ex.getRequestURI().getRawQuery();
        if (query == null || query.isEmpty()) {
            return rawPath;
        }
        return rawPath + "?" + query;
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
                    stream.write("endpoint", cfg.announceUri);
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
                    } else if (!cfg.pushNotifications) {
                        // Comment-only keepalive: keeps the response producing
                        // bytes without injecting a JSON-RPC message.
                        stream.writeComment("keepalive");
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

        /**
         * SSE comment frame ({@code ": text"}).
         *
         * <p>Spec-compliant clients ignore comment lines, so a comment is the
         * cheapest way to keep the response producing bytes. It also keeps the
         * response <em>alive</em> without injecting a JSON-RPC message — a
         * {@code notifications/tools/list_changed} push would make the client
         * re-issue {@code tools/list} on every tick.
         */
        void writeComment(String text) throws IOException {
            writeRaw(": " + text + "\n\n");
        }

        private void writeData(String data, String eventLine) throws IOException {
            StringBuilder sb = new StringBuilder();
            if (eventLine != null) {
                sb.append(eventLine);
            }
            sb.append("data: ").append(data).append("\n\n");
            writeRaw(sb.toString());
        }

        private void writeRaw(String frame) throws IOException {
            byte[] payload = frame.getBytes(StandardCharsets.UTF_8);
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

        /** Arrival counter — drives the {@code delayAfter} window. */
        private final AtomicInteger reqCount = new AtomicInteger(0);

        /**
         * {@code true} for the announced POST URI (JSON-RPC only);
         * {@code false} for the catch-all context, which additionally records
         * unmatched non-JSON-RPC requests and answers them with 404.
         */
        private final boolean jsonRpcOnly;

        SsePostHandler(Config cfg, AtomicInteger rpcCount, boolean jsonRpcOnly) {
            this.cfg = cfg;
            this.rpcCount = rpcCount;
            this.jsonRpcOnly = jsonRpcOnly;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = readBody(ex);
            applyDelay(cfg, reqCount);
            JsonNode req = parse(body);
            String method = methodOf(req);
            Long id = idOf(req);
            boolean nonPost = !jsonRpcOnly
                && !"POST".equalsIgnoreCase(ex.getRequestMethod());
            // The out-of-order path records AND buffers under ONE lock — see
            // recordAndBufferOutOfOrder. Recording separately would let two
            // concurrent handlers interleave between the two steps.
            boolean buffered = id != null && !nonPost
                && cfg.outOfOrder && "tools/call".equals(method);
            if (buffered) {
                // The POST is only acknowledged; the answer rides the stream,
                // correlated by this id (FR-013).
                String payload = MAPPER.writeValueAsString(
                    success(id.longValue(), cfg.resultFor(method, req, ex)));
                recordAndBufferOutOfOrder(method, id, ex, body, payload);
            } else {
                record(method, id, ex, body);
                if (nonPost) {
                    // Catch-all context: a non-POST that reached it was not an
                    // MCP request. Record it (done above) and answer 404 — the
                    // point is that it is now *observable*, not that it
                    // succeeds.
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                    return;
                }
                if (id != null) {
                    String payload = MAPPER.writeValueAsString(
                        success(id.longValue(), cfg.resultFor(method, req, ex)));
                    pushFrame("message", payload);
                }
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

        /**
         * Counts occurrences of the {@code expireMethod} — the method-scoped
         * expiry index (Story #054 T037/T038).
         */
        private final AtomicInteger methodExpireCount = new AtomicInteger(0);

        /** Arrival counter — drives the {@code delayAfter} window. */
        private final AtomicInteger reqCount = new AtomicInteger(0);

        StreamableHandler(Config cfg, AtomicInteger rpcCount) {
            this.cfg = cfg;
            this.rpcCount = rpcCount;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = readBody(ex);
            applyDelay(cfg, reqCount);
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

            // FR-020/021 method-scoped expiry (Story #054 T037/T038): the
            // first N occurrences of `expireMethod` get a 404. Unlike the
            // global `expireSession` threshold, the handshake traffic is NOT
            // affected — which is exactly what makes "the client re-handshakes
            // and retries with the same JSON-RPC id" observable.
            if (cfg.expireMethodCount != 0 && method != null
                && cfg.expireMethod.equals(method)) {
                int seen = methodExpireCount.incrementAndGet();
                if (cfg.expireMethodCount < 0 || seen <= cfg.expireMethodCount) {
                    byte[] payload = "session expired".getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(404, payload.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(payload);
                    }
                    ex.close();
                    maybeExit(cfg, rpcCount);
                    return;
                }
            }

            if (id == null) {
                // Notification (FR-014): acknowledged, never answered.
                ex.sendResponseHeaders(202, -1);
                ex.close();
                maybeExit(cfg, rpcCount);
                return;
            }

            if ("initialize".equals(method) && !cfg.omitSessionId) {
                // FR-018/024: the session id rides the *response header*, and
                // every initialize mints a brand-new one.
                ex.getResponseHeaders().set(HEADER_SESSION_ID,
                    "session-" + SESSION_SEQ.incrementAndGet());
            }
            byte[] payload = MAPPER.writeValueAsBytes(
                success(id, cfg.resultFor(method, req, ex)));
            if (cfg.respondSse || wantsSse(ex)) {
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
     *
     * <p>The {@code respondSse} switch bypasses this rule entirely — the
     * spec transport (whose {@code Accept} is hardcoded) can then still be
     * served an SSE-shaped answer (Story #054 T040).
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
        private final String httpMethod;
        private final String path;
        private final String host;

        RecordedRequest(long seq, String method, Long id, String contentType,
                        String sessionIdHeader, String body, String httpMethod,
                        String path, String host) {
            this.seq = seq;
            this.method = method;
            this.id = id;
            this.contentType = contentType;
            this.sessionIdHeader = sessionIdHeader;
            this.body = body;
            this.httpMethod = httpMethod;
            this.path = path;
            this.host = host;
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

        /**
         * HTTP verb of the inbound request ({@code GET} / {@code POST}).
         * Empty string when the request carried no JSON-RPC method.
         */
        public String getHttpMethod() {
            return httpMethod;
        }

        /** Request path including the query string, e.g. {@code /messages?sessionId=abc}. */
        public String getPath() {
            return path;
        }

        /** Raw {@code Host} request header — proves the client kept the origin. */
        public String getHost() {
            return host;
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
            if (httpMethod == null) {
                n.putNull("httpMethod");
            } else {
                n.put("httpMethod", httpMethod);
            }
            if (path == null) {
                n.putNull("path");
            } else {
                n.put("path", path);
            }
            if (host == null) {
                n.putNull("host");
            } else {
                n.put("host", host);
            }
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
                n.path("body").asText(""),
                n.has("httpMethod") && !n.get("httpMethod").isNull()
                    ? n.get("httpMethod").asText() : null,
                n.has("path") && !n.get("path").isNull()
                    ? n.get("path").asText() : null,
                n.has("host") && !n.get("host").isNull()
                    ? n.get("host").asText() : null);
        }

        @Override
        public String toString() {
            return "#" + seq + " " + method + " id=" + id
                + " session=" + sessionIdHeader + " " + httpMethod + " " + path;
        }
    }

    // ── Switches ─────────────────────────────────────────────────────────

    private static final class Config {

        final int port = intProp("port", 0);
        final int delayMs = intProp("delayMs", 0);
        final int delayAfter = intProp("delayAfter", -1);
        final int exitAfter = intProp("exitAfter", -1);
        final int pushIntervalMs = intProp("pushIntervalMs", 200);
        final int closeSseAfterMs = intProp("closeSseAfter", -1);
        final int malformedRatio = intProp("malformedRatio", -1);
        final int expireSession = intProp("expireSession", -1);

        /**
         * JSON-RPC method whose occurrences get a 404 (method-scoped expiry).
         *
         * <p>Story #054 T037/T038. Orthogonal to {@link #expireSession}: the
         * global threshold expires <em>every</em> session-bearing request from
         * the N-th on, which cannot produce a "one-shot 404 that the client's
         * re-handshake survives" — the re-handshake's own
         * {@code notifications/initialized} POST carries a session header and
         * would 404 too, so the retry-once path could never be reached. A
         * method-scoped expiry lets a test expire exactly one method (e.g.
         * {@code tools/call}) while the handshake traffic stays healthy.
         *
         * <p>Empty = disabled.
         */
        final String expireMethod = strProp("expireMethod", "");

        /**
         * How many occurrences of {@link #expireMethod} get a 404.
         *
         * <p>{@code 0} (default) = disabled; {@code -1} = every occurrence
         * (unlimited — used by T038 so the retry 404s too, whatever the
         * transport does); {@code N > 0} = the first N occurrences.
         */
        final int expireMethodCount = intProp("expireMethodCount", 0);

        /**
         * Whether {@code initialize} omits the {@code Mcp-Session-Id} response
         * header (Story #054 T039).
         *
         * <p>Stateless server shape: the client then never holds a local
         * session id, so a 404 must NOT trigger a re-handshake.
         */
        final boolean omitSessionId = boolProp("omitSessionId", false);

        /**
         * Force the {@code text/event-stream} response shape regardless of the
         * request's {@code Accept} header (Story #054 T040).
         *
         * <p>{@code McpHttpSupport.postJsonRpcSpec} hardcodes
         * {@code Accept: application/json, text/event-stream}, and
         * {@code wantsSse} only answers SSE when the caller asks for SSE
         * <em>exclusively</em> — so the spec transport can never obtain the SSE
         * shape from this fixture without this switch.
         */
        final boolean respondSse = boolProp("respondSse", false);

        final boolean noEndpointAnnounce = boolProp("noEndpointAnnounce", false);
        final boolean omitProtocolVersion = boolProp("omitProtocolVersion", false);

        /** URI carried by the {@code event: endpoint} frame (FR-012). */
        final String announceUri = strProp("announceUri", SSE_POST_URI);

        /** Push {@code tools/call} answers in reverse arrival order (FR-013). */
        final boolean outOfOrder = boolProp("outOfOrder", false);

        /**
         * Whether the periodic SSE push carries a
         * {@code notifications/tools/list_changed} JSON-RPC message.
         *
         * <p>{@code false} replaces it with an SSE comment keepalive: the
         * response keeps producing bytes (so the client's disconnect does not
         * block on a chunk that never comes) but the client is not nudged
         * into re-issuing {@code tools/list} every tick.
         */
        final boolean pushNotifications = boolProp("pushNotifications", true);

        ObjectNode resultFor(String method, JsonNode req, HttpExchange ex) {
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
                // Echo the caller's input back, so a test can tell two
                // concurrent calls apart. Falls back to the historical
                // constant when the caller sent no input (existing
                // assertions depend on it).
                String echoed = echoedInput(req);
                r.put("content", echoed.isEmpty() ? "fake-result" : echoed);
                r.put("isError", false);
                return r;
            }
            // ping and anything else: an empty result is a valid JSON-RPC answer.
            return r;
        }

        /** {@code params.arguments.input} of a {@code tools/call} request. */
        private String echoedInput(JsonNode req) {
            if (req == null) {
                return "";
            }
            JsonNode input = req.path("params").path("arguments").path("input");
            if (input.isMissingNode() || input.isNull()) {
                return "";
            }
            return input.isTextual() ? input.asText("") : input.toString();
        }
    }

    private static String strProp(String key, String fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isEmpty()) {
            return fallback;
        }
        return raw.trim();
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

    // ── Out-of-order answer buffering (FR-013) ──────────────────────────

    /**
     * Buffered {@code tools/call} answers, waiting for a second one so the
     * pair can be pushed in reverse arrival order. Each entry is
     * {@code {payload, enqueuedAtMillis}}.
     */
    private static final List<Object[]> OUT_OF_ORDER_BUFFER =
        Collections.synchronizedList(new ArrayList<Object[]>());

    /**
     * Record the request AND buffer its answer under <b>one</b> lock.
     *
     * <p>As soon as a second answer arrives, both are pushed — the later
     * arrival first (reverse order) — so the client cannot rely on arrival
     * order to correlate them.
     *
     * <p>Story #054 T036 hardening: the two concurrent {@code tools/call}
     * POSTs are handled by two {@code HttpServer} threads. If
     * {@code record()} and the buffer append were two separate steps, the
     * handlers could interleave between them — the recorder would show one
     * arrival order while the answers were pushed in the other, and the
     * "the pair is pushed in reverse arrival order" guarantee would be a coin
     * flip. One lock, one order: the recorded order <em>is</em> the buffered
     * order, so the push order is exactly its reverse.
     */
    private static void recordAndBufferOutOfOrder(String method, Long id,
        HttpExchange ex, String body, String payload) {
        List<Object[]> ready;
        synchronized (OUT_OF_ORDER_BUFFER) {
            record(method, id, ex, body);
            OUT_OF_ORDER_BUFFER.add(new Object[] {
                payload, Long.valueOf(System.currentTimeMillis()) });
            if (OUT_OF_ORDER_BUFFER.size() < 2) {
                return;
            }
            ready = new ArrayList<>(OUT_OF_ORDER_BUFFER);
            OUT_OF_ORDER_BUFFER.clear();
        }
        for (int i = ready.size() - 1; i >= 0; i--) {
            pushFrame("message", (String) ready.get(i)[0]);
        }
    }

    /**
     * Safety net: if a second answer never arrives, flush the lonely one
     * after a second instead of leaving the caller hanging until its timeout.
     */
    private static void startOutOfOrderFlusher() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(100L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    List<Object[]> stale = null;
                    synchronized (OUT_OF_ORDER_BUFFER) {
                        if (OUT_OF_ORDER_BUFFER.size() == 1) {
                            Object[] only = OUT_OF_ORDER_BUFFER.get(0);
                            long age = System.currentTimeMillis()
                                - ((Long) only[1]).longValue();
                            if (age > 1_000L) {
                                stale = new ArrayList<>(OUT_OF_ORDER_BUFFER);
                                OUT_OF_ORDER_BUFFER.clear();
                            }
                        }
                    }
                    if (stale != null) {
                        for (Object[] entry : stale) {
                            pushFrame("message", (String) entry[0]);
                        }
                    }
                }
            }
        }, "test-mcp-out-of-order-flusher");
        t.setDaemon(true);
        t.start();
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
     * Apply the configured server-side delay, honoring the {@code delayAfter}
     * window: the first {@code delayAfter - 1} requests are answered
     * immediately, every later one is delayed.
     *
     * <p>Without this window a slow server would time out the <em>handshake</em>
     * as well, and "the heartbeat probe timed out" could never be observed on
     * its own.
     */
    private static void applyDelay(Config cfg, AtomicInteger counter) {
        if (cfg.delayMs <= 0) {
            return;
        }
        int n = counter.incrementAndGet();
        if (cfg.delayAfter >= 0 && n <= cfg.delayAfter) {
            return;
        }
        delay(cfg.delayMs);
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