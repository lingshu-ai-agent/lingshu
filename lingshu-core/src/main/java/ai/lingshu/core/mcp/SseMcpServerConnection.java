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

import ai.lingshu.core.impl.mcp.McpErrorCodes;
import ai.lingshu.core.runtime.McpTransportType;
import ai.lingshu.core.slot.AccessDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * HTTP+SSE MCP transport (Story #021c, dsh §6.5 (2.1) L4821-4835).
 *
 * <p><b>Protocol shape</b> — the deprecated-but-supported HTTP+SSE transport
 * defines exactly two endpoints:
 * <ol>
 *   <li>{@code GET} on the SSE URL — a long-lived stream. The server's
 *       <em>first</em> event is {@code event: endpoint}, whose {@code data} is
 *       the URI (usually relative) that client messages must be POSTed to.</li>
 *   <li>{@code POST} on that announced URI — the only way to send anything.</li>
 * </ol>
 * Responses do <b>not</b> come back in the POST body: the POST is acknowledged
 * (202) and the JSON-RPC answer is pushed as {@code event: message} on the
 * stream, correlated by request id. {@link #request} implements exactly that.
 *
 * <p><b>Heartbeat</b> — JSON-RPC {@code ping} over the announced POST URI (the
 * spec's liveness probe), not a vendor {@code GET /health}.
 *
 * <p><b>Reconnect backoff</b> — 1s → 2s → 4s → 8s → 16s → 32s → 60s (cap),
 * then infinite retries. Reset on a successful heartbeat.
 *
 * <p><b>JDK 8 compatibility</b> — Uses {@link HttpURLConnection} (JDK 1.1
 * builtin); no {@code java.net.http.HttpClient} (JDK 11+); no
 * {@code var} / {@code List.of} / {@code record} / {@code sealed}.
 */
public class SseMcpServerConnection implements McpServerConnection {

    private static final Logger LOG = LoggerFactory.getLogger(SseMcpServerConnection.class);

    /** Heartbeat thread name pattern. */
    private static final String HB_THREAD_NAME = "mcp-hb-%s";
    /** SSE reader thread name pattern. */
    private static final String SSE_THREAD_NAME = "mcp-sse-%s";
    /** Reconnect thread name pattern. */
    private static final String RECONNECT_THREAD_NAME = "mcp-reconnect-%s";

    private final McpServerConfig cfg;
    private final long hbIntervalMs;
    private final long hbTimeoutMs;
    private final long reconnectCapMs;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<ConnectionState> state = new AtomicReference<>(ConnectionState.IDLE);
    private final AtomicLong lastBeat = new AtomicLong(0L);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicLong nextRequestId = new AtomicLong(0L);
    private final CopyOnWriteArrayList<Consumer<ConnectionState>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final List<McpToolDescriptor> cachedTools = new ArrayList<>();

    /** Story #033 — domain guard whitelist (immutable copy from cfg, see ctor). */
    private final List<String> domainWhitelist;

    /** Absolute POST URI announced by the server's {@code endpoint} event. */
    private volatile String postEndpoint;

    /** Live SSE connection — disconnected to unblock a blocked {@code readLine}. */
    private volatile HttpURLConnection sseConn;

    /** Released when the {@code endpoint} event arrives (one per connect attempt). */
    private volatile CountDownLatch endpointLatch = new CountDownLatch(1);

    /** In-flight requests awaiting their answer on the SSE stream, keyed by JSON-RPC id. */
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /** Version negotiated in {@code InitializeResult.protocolVersion}. */
    private volatile String protocolVersion;

    private ScheduledExecutorService hbExecutor;
    private volatile ScheduledFuture<?> hbFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile Thread sseReader;

    /** One request waiting for its {@code event: message} on the stream. */
    private static final class Pending {

        private final CountDownLatch latch = new CountDownLatch(1);
        private volatile JsonNode response;
    }

    /** Convenience ctor — uses all defaults from {@link McpServerConfig}. */
    public SseMcpServerConnection(McpServerConfig cfg) {
        this(cfg, -1L, -1L, -1L);
    }

    /**
     * Full ctor — tests use this to shrink heartbeat / backoff parameters.
     *
     * @param cfg            server config (must be non-null, transport must be {@link McpTransportType#SSE})
     * @param hbIntervalMs   heartbeat probe interval; {@code -1} = use {@code cfg.getHeartbeatIntervalMs()}
     * @param hbTimeoutMs    heartbeat probe timeout; {@code -1} = use {@code cfg.getHeartbeatTimeoutMs()}
     * @param reconnectCapMs exponential backoff cap; {@code -1} = use {@code cfg.getReconnectCapMs()}
     */
    public SseMcpServerConnection(McpServerConfig cfg,
                                  long hbIntervalMs,
                                  long hbTimeoutMs,
                                  long reconnectCapMs) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg must not be null");
        }
        if (cfg.getTransport() != McpTransportType.SSE) {
            throw new IllegalArgumentException(
                "SseMcpServerConnection requires SSE transport, got " + cfg.getTransport());
        }
        this.cfg = cfg;
        this.hbIntervalMs = hbIntervalMs < 0 ? cfg.getHeartbeatIntervalMs() : hbIntervalMs;
        this.hbTimeoutMs = hbTimeoutMs < 0 ? cfg.getHeartbeatTimeoutMs() : hbTimeoutMs;
        this.reconnectCapMs = reconnectCapMs < 0 ? cfg.getReconnectCapMs() : reconnectCapMs;
        // Story #033 — copy whitelist defensively (cfg is immutable but the list itself
        // could in principle be shared across builders; take an ArrayList copy so
        // McpHttpSupport.checkOrThrow's contains() is safe and mutations on cfg
        // can't leak in).
        this.domainWhitelist = cfg.getDomainWhitelist() != null
            ? new ArrayList<>(cfg.getDomainWhitelist())
            : new ArrayList<>();
    }

    // ── Public API ──────────────────────────────────────────────────────

    @Override
    public String name() {
        return cfg.getName();
    }

    @Override
    public ConnectionState state() {
        return state.get();
    }

    @Override
    public Instant lastHeartbeatAt() {
        long ms = lastBeat.get();
        return ms == 0L ? null : Instant.ofEpochMilli(ms);
    }

    @Override
    public List<McpToolDescriptor> listTools() {
        synchronized (cachedTools) {
            return Collections.unmodifiableList(new ArrayList<>(cachedTools));
        }
    }

    @Override
    public McpCallResult callTool(String toolName, JsonNode input) {
        ConnectionState s = state.get();
        if (s != ConnectionState.CONNECTED) {
            return McpCallResult.error(
                "MCP server " + cfg.getName() + " not connected (state=" + s + ")");
        }
        if (toolName == null || toolName.isEmpty()) {
            return McpCallResult.error("toolName must not be empty");
        }
        if (cfg.getUrl() == null || cfg.getUrl().isEmpty()) {
            return McpCallResult.error("MCP SSE server requires non-empty url");
        }
        String endpoint = postEndpoint;
        if (endpoint == null || endpoint.isEmpty()) {
            return McpCallResult.error(
                "MCP SSE server has not announced its POST endpoint yet");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", input == null ? mapper.createObjectNode() : input);
        try {
            // Story #033 — Path B + Mitigation 1: check-only domain guard before any
            // outbound HTTP. Same [LINGS-S01] semantics as WebFetchTool (Story #032).
            McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);
            JsonNode resp = request("tools/call", params);
            return McpHttpSupport.parseCallResult(resp);
        } catch (AccessDeniedException e) {
            return McpCallResult.error("[LINGS-S01] " + e.getMessage());
        } catch (McpTransportException e) {
            return McpCallResult.error(
                "tools/call " + McpErrorCodes.LINGS_M03 + ": " + e.getMessage());
        } catch (Exception e) {
            return McpCallResult.error("tools/call failed: " + e.getMessage());
        }
    }

    @Override
    public void onStateChange(Consumer<ConnectionState> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void start() {
        if (closing.get()) {
            return;
        }
        ConnectionState prev = state.getAndUpdate(s ->
            s == ConnectionState.IDLE || s == ConnectionState.DISCONNECTED
                || s == ConnectionState.RECONNECTING
                ? ConnectionState.CONNECTING : s);
        if (prev != ConnectionState.IDLE
            && prev != ConnectionState.DISCONNECTED
            && prev != ConnectionState.RECONNECTING) {
            // already connecting / connected — no-op
            return;
        }
        // Fire listener for CONNECTING (the getAndUpdate above is silent).
        notifyListeners(ConnectionState.CONNECTING);
        try {
            doConnect();
        } catch (Throwable t) {
            LOG.warn("MCP server {} start failed: {}", cfg.getName(), t.getMessage());
            // A failed handshake may have left the SSE stream open (e.g.
            // initialize timed out) — drop it before backing off.
            interruptSseReader();
            failAllPending("connection attempt failed");
            transition(ConnectionState.RECONNECTING);
            scheduleReconnect();
        }
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return; // idempotent
        }
        transition(ConnectionState.FAILED);
        stopHeartbeat();
        cancelReconnect();
        interruptSseReader();
    }

    // ── Internal — connection lifecycle ─────────────────────────────────

    private void doConnect() throws IOException {
        if (cfg.getUrl() == null || cfg.getUrl().isEmpty()) {
            throw new IOException("MCP SSE server requires non-empty url");
        }
        final String sseUrl = resolveSseUrl(cfg.getUrl());
        // Story #033 — check-only domain guard before the long-lived GET.
        McpHttpSupport.checkOrThrow(sseUrl, domainWhitelist);

        // 1) Open the stream and block until the server announces its POST URI.
        //    Per spec this "endpoint" event is the operational contract: there is
        //    no fixed "/message" or per-method path to hard-code.
        postEndpoint = null;
        endpointLatch = new CountDownLatch(1);
        startSseReader(sseUrl);
        if (!await(endpointLatch, hbTimeoutMs)) {
            throw new IOException("no 'endpoint' event from SSE server within "
                + hbTimeoutMs + "ms");
        }
        String endpoint = postEndpoint;
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IOException("SSE 'endpoint' event carried no URI");
        }
        McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);

        // 2) initialize — first request on the announced URI.
        JsonNode initResp = request("initialize", McpHttpSupport.buildInitializeParams());
        JsonNode initResult = unwrap(initResp, "initialize");
        String negotiated = initResult.path("protocolVersion").asText("");
        protocolVersion = negotiated.isEmpty()
            ? McpHttpSupport.PROTOCOL_VERSION : negotiated;
        LOG.info("[MCP:{}] SSE negotiated protocol version {}",
            cfg.getName(), protocolVersion);

        // 3) notifications/initialized — a NOTIFICATION: no id, no response.
        send(McpHttpSupport.wrapNotification("notifications/initialized", null));

        // 4) tools/list
        JsonNode listResp = request("tools/list", mapper.createObjectNode());
        List<McpToolDescriptor> tools = McpHttpSupport.parseToolList(listResp);
        synchronized (cachedTools) {
            cachedTools.clear();
            cachedTools.addAll(tools);
        }

        // 5) state CONNECTED + reset attempts + start heartbeat
        reconnectAttempts.set(0);
        lastBeat.set(System.currentTimeMillis());
        transition(ConnectionState.CONNECTED);
        startHeartbeat();
    }

    /**
     * POST a JSON-RPC request and wait for its answer <em>on the SSE stream</em>.
     *
     * <p>The POST itself only acknowledges (202 Accepted); the real response is
     * pushed back as {@code event: message} and matched here by request id.
     */
    private JsonNode request(String method, JsonNode params) throws IOException {
        long id = nextRequestId.incrementAndGet();
        ObjectNode envelope = McpHttpSupport.wrapJsonRpc(id, method, params);
        Pending slot = new Pending();
        pending.put(id, slot);
        try {
            send(envelope);
            if (!await(slot.latch, hbTimeoutMs)) {
                throw new IOException(method + " timed out after " + hbTimeoutMs + "ms");
            }
        } finally {
            pending.remove(id);
        }
        return slot.response;
    }

    /** POST one JSON-RPC message to the announced URI, discarding the POST body. */
    private void send(JsonNode envelope) throws IOException {
        String endpoint = postEndpoint;
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IOException("MCP SSE POST endpoint not announced yet");
        }
        McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);
        McpHttpSupport.postNotification(endpoint, envelope, hbTimeoutMs);
    }

    private boolean await(CountDownLatch latch, long timeoutMs) {
        try {
            return latch.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Unwrap {@code result}, turning a JSON-RPC {@code error} into an exception. */
    private JsonNode unwrap(JsonNode body, String method) {
        if (body == null || body.size() == 0) {
            throw new McpTransportException(McpErrorCodes.LINGS_M03,
                method + ": empty response body");
        }
        JsonNode error = body.get("error");
        if (error != null && !error.isNull()) {
            throw new McpTransportException(McpErrorCodes.LINGS_M03,
                method + " failed: " + error);
        }
        JsonNode result = body.get("result");
        if (result == null || result.isNull()) {
            throw new McpTransportException(McpErrorCodes.LINGS_M03,
                method + ": response has no result");
        }
        return result;
    }

    /**
     * SSE URL for the long-lived GET. The configured {@code url} may already be
     * the stream path ({@code .../sse}) or a bare base; both are accepted.
     */
    static String resolveSseUrl(String url) {
        if (url.endsWith("/sse")) {
            return url;
        }
        return url.endsWith("/") ? url + "sse" : url + "/sse";
    }

    /**
     * Resolve the {@code endpoint} event URI. The spec says it may be relative,
     * in which case it is resolved against the SSE URL's origin/path.
     */
    private String resolveEndpoint(String announced) {
        try {
            return new URL(new URL(resolveSseUrl(cfg.getUrl())), announced).toString();
        } catch (MalformedURLException e) {
            return announced;
        }
    }

    // ── Internal — heartbeat ────────────────────────────────────────────

    private void startHeartbeat() {
        if (hbExecutor == null || hbExecutor.isShutdown()) {
            String threadName = String.format(HB_THREAD_NAME, cfg.getName());
            ThreadFactory tf = new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread th = new Thread(r, threadName);
                    th.setDaemon(true);
                    return th;
                }
            };
            hbExecutor = Executors.newSingleThreadScheduledExecutor(tf);
        }
        ScheduledFuture<?> old = hbFuture;
        if (old != null) {
            old.cancel(false);
        }
        hbFuture = hbExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    heartbeatTick();
                } catch (Throwable t) {
                    LOG.debug("MCP SSE heartbeat tick for {} threw: {}", cfg.getName(), t.getMessage());
                }
            }
        }, hbIntervalMs, hbIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> f = hbFuture;
        if (f != null) {
            f.cancel(false);
        }
        if (hbExecutor != null) {
            hbExecutor.shutdownNow();
        }
    }

    private void heartbeatTick() {
        if (closing.get() || state.get() != ConnectionState.CONNECTED) {
            return;
        }
        String endpoint = postEndpoint;
        if (endpoint == null || endpoint.isEmpty()) {
            handleDisconnect("no POST endpoint");
            return;
        }
        try {
            // Story #033 — check-only domain guard for the liveness probe.
            McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);
            // Spec liveness probe: JSON-RPC ping over the announced POST URI,
            // answered on the SSE stream — not a vendor /health endpoint.
            unwrap(request("ping", mapper.createObjectNode()), "ping");
            lastBeat.set(System.currentTimeMillis());
            reconnectAttempts.set(0);
        } catch (AccessDeniedException e) {
            handleDisconnect("ping " + e.getMessage());
        } catch (McpTransportException e) {
            handleDisconnect("ping " + e.getCode() + ": " + e.getMessage());
        } catch (Throwable t) {
            handleDisconnect("ping error: " + t.getMessage());
        }
    }

    // ── Internal — reconnect ────────────────────────────────────────────

    private void handleDisconnect(String reason) {
        if (closing.get()) {
            return;
        }
        ConnectionState cur = state.get();
        if (cur == ConnectionState.RECONNECTING || cur == ConnectionState.FAILED) {
            return;
        }
        LOG.info("MCP server {} disconnected: {}", cfg.getName(), reason);
        transition(ConnectionState.DISCONNECTED);
        // quick pause so listeners see DISCONNECTED before RECONNECTING
        transition(ConnectionState.RECONNECTING);
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closing.get()) {
            return;
        }
        if (hbExecutor == null || hbExecutor.isShutdown()) {
            String threadName = String.format(RECONNECT_THREAD_NAME, cfg.getName());
            ThreadFactory tf = new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread th = new Thread(r, threadName);
                    th.setDaemon(true);
                    return th;
                }
            };
            hbExecutor = Executors.newSingleThreadScheduledExecutor(tf);
        }
        int attempt = reconnectAttempts.incrementAndGet();
        long delayMs = computeBackoffMs(attempt);
        reconnectFuture = hbExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                if (closing.get()) {
                    return;
                }
                // SSE: drop the old SSE reader; new one is created in doConnect().
                interruptSseReader();
                try {
                    doConnect();
                } catch (Throwable t) {
                    LOG.info("MCP SSE reconnect for {} attempt {} failed: {}",
                        cfg.getName(), attempt, t.getMessage());
                    transition(ConnectionState.RECONNECTING);
                    scheduleReconnect();
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void cancelReconnect() {
        ScheduledFuture<?> f = reconnectFuture;
        if (f != null) {
            f.cancel(false);
        }
    }

    private void interruptSseReader() {
        Thread t = sseReader;
        if (t != null) {
            t.interrupt();
        }
        // Interrupting a thread blocked in readLine() does not unblock the
        // socket; closing the connection does.
        HttpURLConnection conn = sseConn;
        if (conn != null) {
            conn.disconnect();
        }
    }

    private void startSseReader(final String sseUrl) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                readSseLoop(sseUrl);
            }
        }, String.format(SSE_THREAD_NAME, cfg.getName()));
        t.setDaemon(true);
        sseReader = t;
        t.start();
    }

    /**
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 60s (cap), then 60s forever.
     * Mirrors {@link StdioMcpServerConnection#computeBackoffMs(int)}.
     */
    long computeBackoffMs(int attempt) {
        if (attempt <= 0) {
            return Math.min(1_000L, reconnectCapMs);
        }
        long base = 1_000L;
        long delay = base;
        for (int i = 1; i < attempt && delay < reconnectCapMs; i++) {
            delay = delay * 2L;
        }
        return Math.min(delay, reconnectCapMs);
    }

    // ── Internal — SSE parser ───────────────────────────────────────────

    private void readSseLoop(String sseUrl) {
        HttpURLConnection conn = null;
        try {
            conn = openSseStream(sseUrl);
            sseConn = conn;
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("SSE upgrade returned HTTP " + code);
            }
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            String line;
            String event = null;
            StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null && !closing.get()) {
                if (line.isEmpty()) {
                    // Frame boundary — dispatch if we accumulated any data.
                    if (data.length() > 0) {
                        dispatchSseEvent(event, data.toString());
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
                // ":..." comments and "retry:..." are ignored (own backoff).
            }
            try {
                reader.close();
            } catch (IOException ignored) {
                // best-effort
            }
            if (!closing.get()) {
                LOG.info("[MCP:{}] SSE stream closed by server", cfg.getName());
                handleDisconnect("SSE stream closed");
            }
        } catch (AccessDeniedException e) {
            if (!closing.get()) {
                handleDisconnect("[LINGS-S01] " + e.getMessage());
            }
        } catch (IOException e) {
            if (!closing.get()) {
                LOG.info("[MCP:{}] SSE stream broken: {}", cfg.getName(), e.toString());
                handleDisconnect("SSE stream broken: " + e.getMessage());
            }
        } finally {
            sseConn = null;
            if (conn != null) {
                conn.disconnect();
            }
            // Unblock anyone still waiting: their answers can no longer arrive.
            failAllPending("SSE stream ended");
        }
    }

    /**
     * Handle one decoded SSE frame.
     *
     * <p>{@code endpoint} is the handshake (where to POST); {@code message}
     * carries JSON-RPC traffic — either the answer to a pending request, or a
     * server-initiated notification.
     */
    private void dispatchSseEvent(String event, String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        if ("endpoint".equals(event)) {
            String resolved = resolveEndpoint(data);
            postEndpoint = resolved;
            endpointLatch.countDown();
            LOG.info("[MCP:{}] SSE endpoint announced: {}", cfg.getName(), resolved);
            return;
        }
        if (event != null && !"message".equals(event)) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(data);
            if (node == null) {
                return;
            }
            JsonNode idNode = node.get("id");
            if (idNode != null && !idNode.isNull() && idNode.canConvertToLong()) {
                Pending slot = pending.get(idNode.asLong());
                if (slot != null) {
                    slot.response = node;
                    slot.latch.countDown();
                    return;
                }
            }
            handleServerMessage(node);
        } catch (Exception e) {
            LOG.warn("[MCP:{}] SSE event parse failed: {}", cfg.getName(), e.toString());
        }
    }

    /** Server-initiated (no matching pending id): requests and notifications. */
    private void handleServerMessage(JsonNode node) {
        String method = node.path("method").asText("");
        if ("notifications/tools/list_changed".equals(method)) {
            // Never block the reader thread: its next job is to deliver the very
            // answer this refresh waits for.
            dispatchAsync(new Runnable() {
                @Override
                public void run() {
                    relistTools();
                }
            });
        }
        // Other server messages are accepted silently (extensibility point);
        // we advertise no capabilities, so none should arrive.
    }

    /** Run a task off the SSE reader thread (which must stay free to read). */
    private void dispatchAsync(Runnable task) {
        ScheduledExecutorService ex = hbExecutor;
        if (ex != null && !ex.isShutdown()) {
            ex.execute(task);
            return;
        }
        Thread t = new Thread(task, String.format(SSE_THREAD_NAME, cfg.getName()) + "-notif");
        t.setDaemon(true);
        t.start();
    }

    private void failAllPending(String reason) {
        for (Map.Entry<Long, Pending> entry : pending.entrySet()) {
            Pending slot = entry.getValue();
            if (slot.response == null) {
                ObjectNode failure = mapper.createObjectNode();
                failure.put("message", reason);
                ObjectNode envelope = mapper.createObjectNode();
                envelope.set("error", failure);
                slot.response = envelope;
            }
            slot.latch.countDown();
        }
    }

    private HttpURLConnection openSseStream(String sseUrl) throws IOException {
        if (sseUrl == null || sseUrl.isEmpty()) {
            throw new IOException("url must not be empty");
        }
        // Story #033 — check-only domain guard before raw HttpURLConnection opens.
        McpHttpSupport.checkOrThrow(sseUrl, domainWhitelist);
        HttpURLConnection conn = (HttpURLConnection) new URL(sseUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);
        int t = (int) Math.min(Math.max(hbTimeoutMs, 1L), (long) Integer.MAX_VALUE);
        conn.setConnectTimeout(t);
        conn.setReadTimeout(0); // 0 = infinite — SSE is long-lived
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Cache-Control", "no-cache");
        return conn;
    }

    private void relistTools() {
        if (state.get() != ConnectionState.CONNECTED) {
            return;
        }
        if (postEndpoint == null || postEndpoint.isEmpty()) {
            return;
        }
        try {
            JsonNode listResp = request("tools/list", mapper.createObjectNode());
            List<McpToolDescriptor> tools = McpHttpSupport.parseToolList(listResp);
            synchronized (cachedTools) {
                cachedTools.clear();
                cachedTools.addAll(tools);
            }
            LOG.info("[MCP:{}] tools/list_changed → {} tools cached",
                cfg.getName(), tools.size());
            // notify listeners of CONNECTED (idempotent) so they re-pull listTools()
            notifyListeners(ConnectionState.CONNECTED);
        } catch (Throwable t) {
            LOG.warn("[MCP:{}] relist tools failed: {}", cfg.getName(), t.toString());
        }
    }

    // ── Internal — state machine ────────────────────────────────────────

    private void transition(ConnectionState next) {
        ConnectionState prev = state.getAndSet(next);
        if (prev != next) {
            notifyListeners(next);
        }
    }

    private void notifyListeners(ConnectionState next) {
        for (Consumer<ConnectionState> listener : listeners) {
            try {
                listener.accept(next);
            } catch (Throwable t) {
                LOG.warn("MCP SSE listener for {} threw: {}", cfg.getName(), t.getMessage());
            }
        }
    }
}