package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * Stdio MCP connection (Story #021a, dsh §6.5 (2.1) L4577-4871).
 *
 * <p><b>What</b> — Spawns a subprocess (e.g. {@code mcp-github}) and talks
 * JSON-RPC over stdin/stdout. Implements the full lifecycle: 5-step
 * handshake (spawn → initialize → initialized → tools/list → CONNECTED),
 * periodic heartbeat, exponential-backoff reconnect, listener notification,
 * idempotent close.
 *
 * <p><b>Framing</b> — Simplified line-delimited JSON (one JSON object per
 * line). This deviates from the MCP spec's {@code Content-Length} framing
 * to keep the test fixture simple. Story #021b will upgrade to spec-compliant
 * framing when {@code McpTransport} joins the picture.
 *
 * <p><b>Concurrency</b>:
 * <ul>
 *   <li>{@link #state} — {@link AtomicReference}, transitions are CAS'd</li>
 *   <li>{@link #lastBeat} — {@link AtomicLong}, written by hb thread, read by callers</li>
 *   <li>{@link #reconnectAttempts} — {@link AtomicInteger}</li>
 *   <li>{@link #listeners} — {@link CopyOnWriteArrayList} (many reads, few writes)</li>
 *   <li>process / stdin / stdout — guarded by {@link #closing} flag + {@code synchronized} blocks</li>
 * </ul>
 *
 * <p><b>Reconnect backoff</b> — 1s → 2s → 4s → 8s → 16s → 32s → 60s (cap),
 * then infinite retries. Reset to 1s on a successful heartbeat.
 *
 * <p><b>JDK 8 compatibility</b> — No {@code var} / {@code List.of} / sealed / records.
 * Thread naming uses {@code String.format} (compatible with JDK 8).
 */
public class StdioMcpServerConnection implements McpServerConnection {

    private static final Logger LOG = LoggerFactory.getLogger(StdioMcpServerConnection.class);

    /** MCP initialize protocol version per the 2024-11-05 spec. */
    static final String PROTOCOL_VERSION = "2024-11-05";

    /** Heartbeat thread name pattern. */
    private static final String HB_THREAD_NAME = "mcp-hb-%s";

    /** Default JSON factory for tolerant parsing. */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

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

    private volatile Process process;
    private volatile OutputStream stdin;
    private volatile BufferedReader stdout;
    private ScheduledExecutorService hbExecutor;
    private volatile ScheduledFuture<?> hbFuture;
    private volatile ScheduledFuture<?> reconnectFuture;

    /** Convenience ctor — uses all defaults from {@link McpServerConfig}. */
    public StdioMcpServerConnection(McpServerConfig cfg) {
        this(cfg, -1L, -1L, -1L);
    }

    /**
     * Full ctor — tests use this to shrink heartbeat / backoff parameters
     * so 45+ test cases can run in a few seconds.
     *
     * @param cfg              server config (must be non-null)
     * @param hbIntervalMs     heartbeat probe interval; {@code -1} = use {@code cfg.getHeartbeatIntervalMs()}
     * @param hbTimeoutMs      heartbeat probe timeout; {@code -1} = use {@code cfg.getHeartbeatTimeoutMs()}
     * @param reconnectCapMs   exponential backoff cap; {@code -1} = use {@code cfg.getReconnectCapMs()}
     */
    public StdioMcpServerConnection(McpServerConfig cfg,
                                    long hbIntervalMs,
                                    long hbTimeoutMs,
                                    long reconnectCapMs) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg must not be null");
        }
        if (cfg.getTransport() != McpTransportType.STDIO) {
            throw new IllegalArgumentException(
                "StdioMcpServerConnection requires STDIO transport, got " + cfg.getTransport());
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
        try {
            JsonNode result = sendAndAwait("tools/call", input == null
                ? Collections.<String, Object>emptyMap()
                : Collections.singletonMap("name", toolName)
                , hbTimeoutMs);
            if (result == null || result.isMissingNode()) {
                return McpCallResult.error("MCP server returned empty tools/call result");
            }
            String content;
            JsonNode contentNode = result.get("content");
            if (contentNode != null) {
                content = contentNode.isTextual() ? contentNode.asText() : contentNode.toString();
            } else {
                content = result.toString();
            }
            return McpCallResult.success(content);
        } catch (IOException e) {
            return McpCallResult.error("tools/call I/O failed: " + e.getMessage());
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
            s == ConnectionState.IDLE ? ConnectionState.CONNECTING : s);
        if (prev != ConnectionState.IDLE) {
            // already connecting / connected / reconnecting — no-op
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
        stopProcess();
    }

    // ── Internal — connection lifecycle ─────────────────────────────────

    private void doConnect() throws IOException {
        Process p = spawn();
        process = p;
        OutputStream out = p.getOutputStream();
        stdin = out;
        stdout = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));

        // 1) initialize
        JsonNode initResp = sendAndAwaitSync("initialize",
            buildInitializeParams(), hbTimeoutMs, out, stdout);
        if (initResp == null) {
            throw new IOException("initialize: no response");
        }
        // 2) notifications/initialized (no id, no response expected)
        sendNotificationSync("notifications/initialized", Collections.<String, Object>emptyMap(), out);
        // 3) tools/list
        JsonNode listResp = sendAndAwaitSync("tools/list", Collections.<String, Object>emptyMap(),
            hbTimeoutMs, out, stdout);
        if (listResp == null) {
            throw new IOException("tools/list: no response");
        }
        synchronized (cachedTools) {
            cachedTools.clear();
            JsonNode toolsNode = listResp.get("tools");
            if (toolsNode != null && toolsNode.isArray()) {
                for (JsonNode tn : toolsNode) {
                    cachedTools.add(McpToolDescriptor.builder()
                        .name(tn.path("name").asText())
                        .description(tn.path("description").asText(null))
                        .inputSchema(tn.path("inputSchema"))
                        .build());
                }
            }
        }
        reconnectAttempts.set(0);
        lastBeat.set(System.currentTimeMillis());
        transition(ConnectionState.CONNECTED);
        startHeartbeat();
    }

    private Process spawn() throws IOException {
        if (cfg.getCommand() == null || cfg.getCommand().isEmpty()) {
            throw new IOException("MCP STDIO server requires non-empty command");
        }
        ProcessBuilder pb = new ProcessBuilder(buildCommandLine());
        Map<String, String> env = pb.environment();
        if (cfg.getEnv() != null) {
            env.putAll(cfg.getEnv());
        }
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        return pb.start();
    }

    private List<String> buildCommandLine() {
        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.getCommand());
        if (cfg.getArgs() != null) {
            cmd.addAll(cfg.getArgs());
        }
        return cmd;
    }

    private void stopProcess() {
        Process p = process;
        if (p != null) {
            try {
                p.getOutputStream().close();
            } catch (IOException ignored) {
                // best-effort
            }
            p.destroy();
            if (p.isAlive()) {
                try {
                    p.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            }
        }
    }

    // ── Internal — heartbeat ────────────────────────────────────────────

    private void startHeartbeat() {
        if (hbExecutor == null || hbExecutor.isShutdown()) {
            String threadName = String.format(HB_THREAD_NAME, cfg.getName());
            ThreadFactory tf = new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    return t;
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
                    LOG.debug("MCP heartbeat tick for {} threw: {}", cfg.getName(), t.getMessage());
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
        Process p = process;
        if (p == null || !p.isAlive()) {
            handleDisconnect("process not alive");
            return;
        }
        try {
            OutputStream out = stdin;
            BufferedReader in = stdout;
            if (out == null || in == null) {
                handleDisconnect("stdin/stdout null");
                return;
            }
            JsonNode pingResp = sendAndAwaitSync("ping", Collections.<String, Object>emptyMap(),
                hbTimeoutMs, out, in);
            if (pingResp == null) {
                handleDisconnect("ping timeout");
                return;
            }
            lastBeat.set(System.currentTimeMillis());
            reconnectAttempts.set(0);
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
            String threadName = "mcp-reconnect-" + cfg.getName();
            ThreadFactory tf = new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    return t;
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
                stopProcess();
                try {
                    doConnect();
                } catch (Throwable t) {
                    LOG.info("MCP reconnect for {} attempt {} failed: {}",
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

    // ── Internal — JSON-RPC over line-delimited framing ─────────────────

    private Map<String, Object> buildInitializeParams() {
        java.util.LinkedHashMap<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("protocolVersion", PROTOCOL_VERSION);
        java.util.LinkedHashMap<String, Object> info = new java.util.LinkedHashMap<>();
        info.put("name", "lingshu-agent");
        info.put("version", "0.1.0-SNAPSHOT");
        p.put("clientInfo", info);
        java.util.LinkedHashMap<String, Object> caps = new java.util.LinkedHashMap<>();
        caps.put("roots", Collections.emptyMap());
        p.put("capabilities", caps);
        return p;
    }

    private JsonNode sendAndAwait(String method, Map<String, Object> params, long timeoutMs) throws IOException {
        OutputStream out = stdin;
        BufferedReader in = stdout;
        if (out == null || in == null) {
            throw new IOException("stdin/stdout not available");
        }
        return sendAndAwaitSync(method, params, timeoutMs, out, in);
    }

    private JsonNode sendAndAwaitSync(String method,
                                      Map<String, Object> params,
                                      long timeoutMs,
                                      OutputStream out,
                                      BufferedReader in) throws IOException {
        long id = nextRequestId.incrementAndGet();
        ObjectNode frame = mapper.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.put("method", method);
        frame.set("params", mapper.valueToTree(params));
        String line = mapper.writeValueAsString(frame);
        synchronized (out) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            String respLine = readLineWithTimeout(in, deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (respLine == null) {
                return null;
            }
            if (respLine.isEmpty()) {
                continue;
            }
            try (JsonParser p = JSON_FACTORY.createParser(respLine)) {
                JsonNode node = mapper.readTree(p);
                if (node == null) {
                    continue;
                }
                JsonNode idNode = node.get("id");
                if (idNode != null && idNode.asLong() == id) {
                    if (node.has("error")) {
                        throw new IOException("MCP error response: " + node.get("error").toString());
                    }
                    JsonNode result = node.get("result");
                    return result == null ? mapper.createObjectNode() : result;
                }
                // not our message — could be a notification; skip
            }
        }
        throw new IOException("sendAndAwait timeout: " + method);
    }

    private void sendNotificationSync(String method, Map<String, Object> params, OutputStream out) throws IOException {
        ObjectNode frame = mapper.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("method", method);
        frame.set("params", mapper.valueToTree(params));
        String line = mapper.writeValueAsString(frame);
        synchronized (out) {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /**
     * Blocking read with a soft timeout. Uses a polling loop with a tiny sleep.
     * Tests should use larger heartbeats (≥50ms) so this is not a hot path
     * in production code.
     */
    private String readLineWithTimeout(BufferedReader in, long nanosLeft, TimeUnit unit) throws IOException {
        long deadline = System.nanoTime() + nanosLeft;
        while (System.nanoTime() < deadline) {
            if (closing.get()) {
                return null;
            }
            // poll — BufferedReader has no native timeout support
            if (in.ready()) {
                return in.readLine();
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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
                LOG.warn("MCP listener for {} threw: {}", cfg.getName(), t.getMessage());
            }
        }
    }
}