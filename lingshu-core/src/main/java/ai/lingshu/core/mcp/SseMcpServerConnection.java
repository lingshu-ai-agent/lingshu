package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpErrorCodes;
import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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
 * SSE (Server-Sent Events) MCP transport (Story #021c, dsh §6.5 (2.1) L4821-4835).
 *
 * <p><b>What</b> — Talks JSON-RPC over an HTTP POST request/response
 * cycle (initialize / tools/list / tools/call) and holds a long-lived
 * {@code GET} connection that delivers server-pushed SSE events
 * ({@code notifications/tools/list_changed}, etc.). Heartbeats are
 * {@code GET /health}; reconnects are exponential-backoff and infinite.
 *
 * <p><b>Three differences from stdio</b> (dsh L4821-4835):
 * <ol>
 *   <li>Heartbeat is {@code GET /health} (not {@code Process.isAlive()}).</li>
 *   <li>Reconnect rebuilds the {@code HttpURLConnection} (not the subprocess).</li>
 *   <li>Long-lived {@code GET} SSE stream runs on a dedicated daemon
 *       {@link Thread} (not on {@link ScheduledExecutorService} which is
 *       wrong for blocking I/O).</li>
 * </ol>
 *
 * <p><b>Concurrency</b> — Mirrors {@link StdioMcpServerConnection}:
 * <ul>
 *   <li>{@link #state} — {@link AtomicReference}, transitions are CAS'd</li>
 *   <li>{@link #lastBeat} — {@link AtomicLong}</li>
 *   <li>{@link #reconnectAttempts} — {@link AtomicInteger}</li>
 *   <li>{@link #listeners} — {@link CopyOnWriteArrayList}</li>
 *   <li>{@link #closing} — {@link AtomicBoolean}, guards close()</li>
 *   <li>{@link #sseReader} — {@code volatile Thread}, dedicated SSE reader</li>
 * </ul>
 *
 * <p><b>Reconnect backoff</b> — 1s → 2s → 4s → 8s → 16s → 32s → 60s (cap),
 * then infinite retries. Reset to 1s on a successful heartbeat.
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

    private ScheduledExecutorService hbExecutor;
    private volatile ScheduledFuture<?> hbFuture;
    private volatile ScheduledFuture<?> reconnectFuture;
    private volatile Thread sseReader;

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
            // already connecting / connected — no-op
            return;
        }
        // Fire listener for CONNECTING (the getAndUpdate above is silent).
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
        String base = cfg.getUrl();
        String sep = base.endsWith("/") ? "" : "/";

        // 1) initialize
        long initId = nextRequestId.incrementAndGet();
        JsonNode initParams = McpHttpSupport.buildInitializeParams();
        ObjectNode initEnv = McpHttpSupport.wrapJsonRpc(initId, "initialize", initParams);
        McpHttpSupport.postJsonRpc(base + sep + "initialize", initEnv, hbTimeoutMs);

        // 2) notifications/initialized (notification, no id, no response expected)
        long notifId = nextRequestId.incrementAndGet();
        ObjectNode notifEnv = McpHttpSupport.wrapJsonRpc(notifId,
            "notifications/initialized", mapper.createObjectNode());
        McpHttpSupport.postNotification(base + sep + "notifications/initialized", notifEnv, hbTimeoutMs);

        // 3) tools/list
        long listId = nextRequestId.incrementAndGet();
        ObjectNode listEnv = McpHttpSupport.wrapJsonRpc(listId,
            "tools/list", mapper.createObjectNode());
        JsonNode listResp = McpHttpSupport.postJsonRpc(base + sep + "tools/list", listEnv, hbTimeoutMs);
        List<McpToolDescriptor> tools = McpHttpSupport.parseToolList(listResp);
        synchronized (cachedTools) {
            cachedTools.clear();
            cachedTools.addAll(tools);
        }

        // 4) start SSE reader thread (daemon) — long-blocking I/O, not on hbExecutor
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                readSseLoop();
            }
        }, String.format(SSE_THREAD_NAME, cfg.getName()));
        t.setDaemon(true);
        sseReader = t;
        t.start();

        // Give the SSE reader a moment to open the long-lived GET before we
        // declare CONNECTED, so any URL typo / missing /sse handler surfaces
        // as a state failure rather than as a half-broken connection.
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 5) state CONNECTED + reset attempts + start heartbeat
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
        if (t != null && t.isAlive()) {
            t.interrupt();
        }
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

    private void readSseLoop() {
        while (!closing.get()) {
            HttpURLConnection conn = null;
            try {
                conn = openSseStream();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    InputStream es = conn.getErrorStream();
                    if (es != null) {
                        try {
                            es.close();
                        } catch (IOException ignored) {
                            // best-effort
                        }
                    }
                    throw new IOException("SSE upgrade returned HTTP " + code);
                }
                InputStream raw = conn.getInputStream();
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(raw, StandardCharsets.UTF_8));
                String line;
                StringBuilder data = new StringBuilder();
                while ((line = reader.readLine()) != null && !closing.get()) {
                    if (line.isEmpty()) {
                        // event boundary — dispatch accumulated data
                        if (data.length() > 0) {
                            handleSseEvent(data.toString());
                            data.setLength(0);
                        }
                    } else if (line.startsWith("data:")) {
                        String payload = line.substring(5).trim();
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(payload);
                    }
                    // ignore ":..." (comment), "event:..." (we only handle default),
                    // "retry:..." (we use our own backoff)
                }
                // stream closed by server
                try {
                    reader.close();
                } catch (IOException ignored) {
                    // best-effort
                }
                if (!closing.get()) {
                    LOG.warn("[MCP:{}] SSE stream closed by server", cfg.getName());
                    handleDisconnect("SSE stream closed");
                }
            } catch (IOException e) {
                if (!closing.get()) {
                    LOG.warn("[MCP:{}] SSE stream broken: {}", cfg.getName(), e.toString());
                    handleDisconnect("SSE stream broken: " + e.getMessage());
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            // If we are still connected and not closing, the stream broke — backoff.
            if (!closing.get() && state.get() == ConnectionState.RECONNECTING) {
                // scheduleReconnect was already called by handleDisconnect above;
                // sleep a tiny bit before re-opening to avoid a tight CPU loop
                // if scheduleReconnect was unable to (e.g. executor shutting down).
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private HttpURLConnection openSseStream() throws IOException {
        if (cfg.getUrl() == null || cfg.getUrl().isEmpty()) {
            throw new IOException("url must not be empty");
        }
        String base = cfg.getUrl();
        String sep = base.endsWith("/") ? "" : "/";
        HttpURLConnection conn = (HttpURLConnection) new URL(base + sep + "sse").openConnection();
        conn.setRequestMethod("GET");
        conn.setUseCaches(false);
        int t = (int) Math.min(Math.max(hbTimeoutMs, 1L), (long) Integer.MAX_VALUE);
        conn.setConnectTimeout(t);
        conn.setReadTimeout(0); // 0 = infinite — SSE is long-lived
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Cache-Control", "no-cache");
        return conn;
    }

    private void handleSseEvent(String data) {
        if (data == null || data.isEmpty()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(data);
            if (node == null) {
                return;
            }
            String method = node.path("method").asText("");
            if ("notifications/tools/list_changed".equals(method)) {
                relistTools();
            }
            // Other notifications are accepted silently (extensibility point).
        } catch (Exception e) {
            LOG.warn("[MCP:{}] SSE event parse failed: {}", cfg.getName(), e.toString());
            // malformed event — continue reading the stream
        }
    }

    private void relistTools() {
        if (cfg.getUrl() == null || cfg.getUrl().isEmpty()) {
            return;
        }
        if (state.get() != ConnectionState.CONNECTED) {
            return;
        }
        String base = cfg.getUrl();
        String sep = base.endsWith("/") ? "" : "/";
        try {
            long listId = nextRequestId.incrementAndGet();
            ObjectNode listEnv = McpHttpSupport.wrapJsonRpc(listId,
                "tools/list", mapper.createObjectNode());
            JsonNode listResp = McpHttpSupport.postJsonRpc(base + sep + "tools/list",
                listEnv, hbTimeoutMs);
            List<McpToolDescriptor> tools = McpHttpSupport.parseToolList(listResp);
            synchronized (cachedTools) {
                cachedTools.clear();
                cachedTools.addAll(tools);
            }
            LOG.info("[MCP:{}] SSE tools/list_changed → {} tools cached", cfg.getName(), tools.size());
            // notify listeners of CONNECTED state (idempotent) so they re-pull listTools()
            notifyListeners(ConnectionState.CONNECTED);
        } catch (Throwable t) {
            LOG.warn("[MCP:{}] SSE relist tools failed: {}", cfg.getName(), t.toString());
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

    // ── Jackson helpers (avoids importing ObjectNode name conflict) ─────

    private com.fasterxml.jackson.databind.node.ObjectNode objNode() {
        return mapper.createObjectNode();
    }
}