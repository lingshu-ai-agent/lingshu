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

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * Streamable-HTTP MCP transport (Story #021c, dsh §6.5 (2.1)).
 *
 * <p><b>What</b> — MCP over the Streamable HTTP transport: <em>one</em> endpoint
 * that serves POST (and optionally GET/DELETE), per the spec's
 * "single HTTP endpoint path" rule. Every JSON-RPC message — {@code initialize},
 * {@code notifications/initialized}, {@code tools/list}, {@code tools/call} — is
 * POSTed to that same URL; the endpoint is never used as a directory prefix.
 *
 * <p><b>Session</b> — the server may return an {@code Mcp-Session-Id} header on
 * the {@code initialize} response; it is echoed on every later request. A 404
 * means the session expired, so the client re-initializes and retries once.
 *
 * <p><b>Responses</b> — a POST may be answered with {@code application/json}
 * (one message) or {@code text/event-stream} (one or more messages). Both are
 * handled by {@link McpHttpSupport#postJsonRpcSpec}.
 *
 * <p><b>Heartbeat</b> — JSON-RPC {@code ping} (the spec's liveness probe), not a
 * vendor-specific {@code GET /health}.
 *
 * <p><b>JDK 8 compatibility</b> — Uses {@link java.net.HttpURLConnection}
 * (JDK 1.1); no {@code java.net.http.HttpClient} (JDK 11+);
 * no {@code var} / {@code List.of} / {@code record} / {@code sealed}.
 */
public class StreamableHttpMcpServerConnection implements McpServerConnection {

    private static final Logger LOG = LoggerFactory.getLogger(StreamableHttpMcpServerConnection.class);

    private static final String HB_THREAD_NAME = "mcp-hb-%s";
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

    /** {@code Mcp-Session-Id} issued by the server, echoed on every later request. */
    private volatile String sessionId;

    /** Version negotiated in {@code InitializeResult.protocolVersion}. */
    private volatile String protocolVersion;

    private ScheduledExecutorService hbExecutor;
    private volatile ScheduledFuture<?> hbFuture;
    private volatile ScheduledFuture<?> reconnectFuture;

    /** Convenience ctor — uses all defaults from {@link McpServerConfig}. */
    public StreamableHttpMcpServerConnection(McpServerConfig cfg) {
        this(cfg, -1L, -1L, -1L);
    }

    /**
     * Full ctor — tests use this to shrink heartbeat / backoff parameters.
     */
    public StreamableHttpMcpServerConnection(McpServerConfig cfg,
                                             long hbIntervalMs,
                                             long hbTimeoutMs,
                                             long reconnectCapMs) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg must not be null");
        }
        if (cfg.getTransport() != McpTransportType.STREAMABLE_HTTP) {
            throw new IllegalArgumentException(
                "StreamableHttpMcpServerConnection requires STREAMABLE_HTTP transport, got "
                    + cfg.getTransport());
        }
        this.cfg = cfg;
        this.hbIntervalMs = hbIntervalMs < 0 ? cfg.getHeartbeatIntervalMs() : hbIntervalMs;
        this.hbTimeoutMs = hbTimeoutMs < 0 ? cfg.getHeartbeatTimeoutMs() : hbTimeoutMs;
        this.reconnectCapMs = reconnectCapMs < 0 ? cfg.getReconnectCapMs() : reconnectCapMs;
        // Story #033 — copy whitelist defensively (cfg is immutable but the list
        // itself could in principle be shared across builders; take an ArrayList
        // copy so McpHttpSupport.checkOrThrow's contains() is safe).
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
            return McpCallResult.error("MCP streamable HTTP server requires non-empty url");
        }
        // Single MCP endpoint: tools/call goes to cfg.getUrl() itself, never to
        // a "/tools/call" sub-path.
        String endpoint = cfg.getUrl();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", input == null ? mapper.createObjectNode() : input);
        try {
            // Story #033 — Path B + Mitigation 1: check-only domain guard before any
            // outbound HTTP. Same [LINGS-S01] semantics as WebFetchTool (Story #032).
            McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);
            JsonNode resp = request(endpoint, "tools/call", params);
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
            return;
        }
        notifyListeners(ConnectionState.CONNECTING);
        try {
            doConnect();
        } catch (Throwable t) {
            LOG.warn("MCP server {} start failed: {}", cfg.getName(), t.getMessage());
            transition(ConnectionState.RECONNECTING);
            scheduleReconnect();
        }
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        transition(ConnectionState.FAILED);
        stopHeartbeat();
        cancelReconnect();
    }

    // ── Internal — connection lifecycle ─────────────────────────────────

    private void doConnect() throws IOException {
        if (cfg.getUrl() == null || cfg.getUrl().isEmpty()) {
            throw new IOException("MCP streamable HTTP server requires non-empty url");
        }
        final String endpoint = cfg.getUrl();
        // Story #033 — check-only domain guard before each outbound HTTP call.
        McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);

        // A reconnect always negotiates a brand-new session.
        sessionId = null;
        protocolVersion = null;
        initialize(endpoint);

        JsonNode listResp = request(endpoint, "tools/list", mapper.createObjectNode());
        List<McpToolDescriptor> tools = McpHttpSupport.parseToolList(listResp);
        synchronized (cachedTools) {
            cachedTools.clear();
            cachedTools.addAll(tools);
        }

        reconnectAttempts.set(0);
        lastBeat.set(System.currentTimeMillis());
        transition(ConnectionState.CONNECTED);
        startHeartbeat();
    }

    /**
     * {@code initialize} + {@code notifications/initialized}.
     *
     * <p>Only {@code initialize} is a request (has an id); the follow-up
     * initialized message is a <em>notification</em> and must not carry one.
     * The server's {@code protocolVersion} and {@code Mcp-Session-Id} are
     * recorded for every later request.
     */
    private void initialize(String endpoint) {
        long initId = nextRequestId.incrementAndGet();
        ObjectNode initEnv = McpHttpSupport.wrapJsonRpc(initId, "initialize",
            McpHttpSupport.buildInitializeParams());
        McpHttpSupport.HttpRpcResponse initResp = McpHttpSupport.postJsonRpcSpec(
            endpoint, initEnv, hbTimeoutMs, null, null, initId);
        if (initResp.getSessionId() != null && !initResp.getSessionId().isEmpty()) {
            sessionId = initResp.getSessionId();
        }
        JsonNode result = unwrap(initResp.getBody(), "initialize");
        String negotiated = result.path("protocolVersion").asText("");
        protocolVersion = negotiated.isEmpty()
            ? McpHttpSupport.PROTOCOL_VERSION : negotiated;
        LOG.info("[MCP:{}] streamable HTTP negotiated protocol version {}{}",
            cfg.getName(), protocolVersion,
            sessionId == null ? " (stateless)" : " (session " + sessionId + ")");

        ObjectNode notifEnv = McpHttpSupport.wrapNotification(
            "notifications/initialized", null);
        McpHttpSupport.postJsonRpcSpec(endpoint, notifEnv, hbTimeoutMs,
            sessionId, protocolVersion, null);
    }

    /**
     * POST one JSON-RPC request to the single MCP endpoint and return the full
     * response envelope.
     *
     * <p>On {@code 404} with a live session id the server has forgotten the
     * session (spec: clients MUST start a new one), so we re-initialize and
     * retry exactly once.
     */
    private JsonNode request(String endpoint, String method, JsonNode params) {
        long id = nextRequestId.incrementAndGet();
        ObjectNode envelope = McpHttpSupport.wrapJsonRpc(id, method, params);
        try {
            return post(endpoint, envelope, id);
        } catch (McpHttpSupport.McpHttpStatusException e) {
            if (e.getStatus() == 404 && sessionId != null) {
                LOG.info("MCP server {} session expired (404) on {}, re-initializing",
                    cfg.getName(), method);
                sessionId = null;
                protocolVersion = null;
                initialize(endpoint);
                return post(endpoint, envelope, id);
            }
            throw e;
        }
    }

    private JsonNode post(String endpoint, ObjectNode envelope, long id) {
        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint, envelope, hbTimeoutMs, sessionId, protocolVersion, id);
        if (resp.getSessionId() != null && !resp.getSessionId().isEmpty()) {
            sessionId = resp.getSessionId();
        }
        return resp.getBody();
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
                    LOG.debug("MCP HTTP heartbeat tick for {} threw: {}",
                        cfg.getName(), t.getMessage());
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
        String endpoint = cfg.getUrl();
        if (endpoint == null || endpoint.isEmpty()) {
            handleDisconnect("url empty");
            return;
        }
        try {
            // Story #033 — check-only domain guard for the liveness probe.
            McpHttpSupport.checkOrThrow(endpoint, domainWhitelist);
            // Spec liveness probe: JSON-RPC ping, not a vendor /health endpoint.
            unwrap(request(endpoint, "ping", mapper.createObjectNode()), "ping");
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
                try {
                    doConnect();
                } catch (Throwable t) {
                    LOG.info("MCP streamable HTTP reconnect for {} attempt {} failed: {}",
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

    /**
     * Exponential backoff: 1s, 2s, 4s, 8s, 16s, 32s, 60s (cap), then 60s forever.
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
                LOG.warn("MCP HTTP listener for {} threw: {}", cfg.getName(), t.getMessage());
            }
        }
    }
}