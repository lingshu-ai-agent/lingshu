package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpErrorCodes;
import ai.lingshu.core.runtime.McpTransportType;
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
 * <p><b>What</b> — Stateless HTTP POST request/response for
 * {@code initialize} / {@code notifications/initialized} /
 * {@code tools/list} / {@code tools/call}, with periodic
 * {@code GET /health} heartbeats and exponential-backoff reconnects.
 * No SSE long-lived stream — the HTTP layer itself is stateless, so
 * reconnect is just a fresh {@code initialize} cycle.
 *
 * <p><b>Differences from SSE</b>:
 * <ul>
 *   <li>No SSE reader thread — {@link #close()} does not interrupt any I/O thread.</li>
 *   <li>Heartbeat is the same {@code GET /health} but with no long-poll overhead.</li>
 *   <li>{@link #scheduleReconnect()} is identical to
 *       {@link StdioMcpServerConnection} because there is no extra resource
 *       to tear down between attempts.</li>
 * </ul>
 *
 * <p><b>Concurrency</b> — Mirrors {@link SseMcpServerConnection} without
 * the {@code sseReader} field.
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
        String url = cfg.getUrl() + (cfg.getUrl().endsWith("/") ? "" : "/") + "tools/call";
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", input == null ? mapper.createObjectNode() : input);
        long id = nextRequestId.incrementAndGet();
        ObjectNode envelope = McpHttpSupport.wrapJsonRpc(id, "tools/call", params);
        try {
            JsonNode resp = McpHttpSupport.postJsonRpc(url, envelope, hbTimeoutMs);
            return McpHttpSupport.parseCallResult(resp);
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
        String base = cfg.getUrl();
        String sep = base.endsWith("/") ? "" : "/";

        long initId = nextRequestId.incrementAndGet();
        JsonNode initParams = McpHttpSupport.buildInitializeParams();
        ObjectNode initEnv = McpHttpSupport.wrapJsonRpc(initId, "initialize", initParams);
        McpHttpSupport.postJsonRpc(base + sep + "initialize", initEnv, hbTimeoutMs);

        long notifId = nextRequestId.incrementAndGet();
        ObjectNode notifEnv = McpHttpSupport.wrapJsonRpc(notifId,
            "notifications/initialized", mapper.createObjectNode());
        McpHttpSupport.postNotification(base + sep + "notifications/initialized", notifEnv, hbTimeoutMs);

        long listId = nextRequestId.incrementAndGet();
        ObjectNode listEnv = McpHttpSupport.wrapJsonRpc(listId,
            "tools/list", mapper.createObjectNode());
        JsonNode listResp = McpHttpSupport.postJsonRpc(base + sep + "tools/list", listEnv, hbTimeoutMs);
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
        if (cfg.getUrl() == null || cfg.getUrl().isEmpty()) {
            handleDisconnect("url empty");
            return;
        }
        try {
            String healthUrl = cfg.getUrl() + (cfg.getUrl().endsWith("/") ? "" : "/") + "health";
            String body = McpHttpSupport.getJson(healthUrl, hbTimeoutMs);
            if (body == null) {
                handleDisconnect("health null");
                return;
            }
            lastBeat.set(System.currentTimeMillis());
            reconnectAttempts.set(0);
        } catch (McpTransportException e) {
            handleDisconnect("health " + e.getCode() + ": " + e.getMessage());
        } catch (Throwable t) {
            handleDisconnect("health error: " + t.getMessage());
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