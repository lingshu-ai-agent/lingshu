package ai.lingshu.a2a.server;

import ai.lingshu.core.a2a.client.InProcessA2aRegistry;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 🆕 Story #009 — embedded A2A HTTP server (dsh §5.6.8).
 * 🆕 Story #009c — POST /rpc is now a minimal JSON-RPC 2.0 dispatcher
 * (dsh §5.6.3.1 L2995-3172), sufficient to drive Story #009c's HTTP transport
 * integration tests. <b>Not</b> a full skill dispatcher — see
 * {@link RpcDispatcherHandler} class Javadoc for scope.
 *
 * <p>Wraps JDK built-in {@code com.sun.net.httpserver.HttpServer} (zero new Maven
 * dependencies — see R-13 mitigation (d) in dsh §17) to serve:
 * <ul>
 *   <li>{@code GET /.well-known/agent.json} → JSON AgentCard (A2A v1.0 spec §2.1 fixed path)</li>
 *   <li>{@code POST /rpc} → JSON-RPC 2.0 dispatcher (Story #009c): handles
 *       {@code message/send} / {@code tasks/get} / {@code tasks/cancel}; unknown
 *       methods return {@code -32601 Method not found}.</li>
 *   <li>anything else → 404 (catch-all)</li>
 * </ul>
 *
 * <p>Lifecycle (contracts/a2a-server-lifecycle.md):
 * <ul>
 *   <li>{@link #start()} — invoked by Spring via {@code @Bean(initMethod = "start")} after
 *       bean construction; binds, registers handlers, calls {@code server.start()}. Fails
 *       Spring context startup on port collision / unknown host / invalid port (LINGS-S06)
 *       or blank {@code Identity.name} (LINGS-T02).</li>
 *   <li>running — handlers serve concurrent GET/POST requests; thread-safe because
 *       {@code LocalAgentCardGenerator} is stateless and {@code ObjectMapper} is thread-safe.
 *       The in-process task map (used by {@link RpcDispatcherHandler}) is a
 *       {@link ConcurrentMap} so concurrent POST /rpc requests are safe.</li>
 *   <li>{@link #stop()} — invoked by Spring via {@code @Bean(destroyMethod = "stop")} on
 *       context close; calls {@code server.stop(0)} and releases the port. Idempotent
 *       (a second call no-ops because {@code server} is nulled).</li>
 * </ul>
 */
@Component
public class A2aServer {

    private static final Logger LOG = LoggerFactory.getLogger(A2aServer.class);

    /** Cached AgentCard — generated once at startup; immutable thereafter. */
    private final AtomicReference<AgentCard> cardRef = new AtomicReference<AgentCard>();

    /**
     * 🆕 Story #009c — minimal in-process task store backing {@link RpcDispatcherHandler}.
     * Keyed by taskId (UUID); value is the JSON string originally submitted via
     * {@code message/send}. Cleared on {@link #stop()}. Concurrent because
     * {@code com.sun.net.httpserver.HttpServer} default executor is multi-threaded.
     */
    private final ConcurrentMap<String, String> taskStore = new ConcurrentHashMap<String, String>();

    /** The JDK HttpServer; null before {@link #start()} and after {@link #stop()}. */
    private HttpServer server;

    /** Actual bound port; differs from {@code cfg.a2a.port} when port = 0 (OS-assigned). */
    private int actualPort;

    private final AgentConfig cfg;

    /**
     * 🆕 Story a2a-server-tool-registry-dispatch — local {@link ToolRegistry} for
     * dispatching inbound {@code message/send} JSON-RPC calls to registered {@link Tool Tools}.
     *
     * <p>May be {@code null} when the server is constructed without Spring DI (some
     * unit tests); in that case {@link RpcDispatcherHandler#handleMessageSend} falls back
     * to returning JSON-RPC {@code -32601 Method not found} for any skill lookup, and
     * {@link AgentCardHandler} advertises an empty {@code skills[]} list.
     */
    private final ToolRegistry toolRegistry;

    @Autowired
    public A2aServer(AgentConfig cfg, ToolRegistry toolRegistry) {
        this.cfg = cfg;
        this.toolRegistry = toolRegistry;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Bind and start listening. Called by Spring via {@code @Bean(initMethod = "start")}
     * after the bean is constructed.
     *
     * @throws LingsA2aServerException {@code LINGS-S06} on bind failure / unknown host /
     *         port out of range / repeated start; {@code LINGS-T02} on empty
     *         {@code Identity.name}.
     */
    public void start() {
        // Step 1: validate Identity.name eagerly so yml typo fails fast (FR-007 / EC-1).
        // 🆕 Story a2a-server-tool-registry-dispatch — pass toolRegistry so the cached
        // card carries skills[] scanned at start-time (used by InProcessA2aRegistry).
        // Note: HTTP path (AgentCardHandler) rebuilds per-request to handle tools
        // registered AFTER @PostConstruct (AgentToolScanner fires on ContextRefreshedEvent).
        AgentCard card = LocalAgentCardGenerator.generate(cfg, toolRegistry);
        cardRef.set(card);

        // Step 2: validate port range.
        AgentConfig.A2a a2a = cfg.getA2a() != null ? cfg.getA2a() : AgentConfig.A2a.defaults();
        int port = a2a.getPort() == null ? 8080 : a2a.getPort();
        if (port < 0 || port > 65535) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "invalid a2a.server.port=" + port,
                "port must be in range 0..65535 (0 = OS-assigned)");
        }
        String host = a2a.getHost() == null ? "0.0.0.0" : a2a.getHost();

        // Step 3: bind.
        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (java.net.BindException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Failed to bind on " + host + ":" + port + ": " + e.getMessage(),
                "change 'a2a.server.port' in application.yml or stop the conflicting process",
                e);
        } catch (java.net.UnknownHostException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Unknown host '" + host + "': " + e.getMessage(),
                "set 'a2a.server.host' to a valid hostname or IP (e.g. '0.0.0.0' / '127.0.0.1')",
                e);
        } catch (IOException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Failed to create HttpServer on " + host + ":" + port + ": " + e.getMessage(),
                "check 'a2a.server.host' and 'a2a.server.port' in application.yml",
                e);
        } catch (IllegalArgumentException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Invalid bind address " + host + ":" + port + ": " + e.getMessage(),
                "check 'a2a.server.host' and 'a2a.server.port' in application.yml",
                e);
        }

        this.actualPort = server.getAddress().getPort();

        // Step 4: register handlers.
        server.createContext("/.well-known/agent.json", new AgentCardHandler());
        server.createContext("/rpc", new RpcDispatcherHandler());
        server.createContext("/", new NotFoundHandler());

        // Step 5: start (use default executor — cached thread pool, see contracts/...lifecycle.md §3.1).
        server.setExecutor(null);
        try {
            server.start();
        } catch (IllegalStateException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "HttpServer.start() failed: " + e.getMessage(),
                "A2aServer.start() called more than once (not idempotent)",
                e);
        }

        // Step 6: register the freshly-built AgentCard into the in-process registry
        // (Story #009b — dsh §5.6.3.2 L3241-3243). Done AFTER bind succeeds so a failed
        // bind doesn't pollute the registry with a card pointing at an unbound port.
        registerInProcess();

        LOG.info("[A2aServer] listening on http://{}:{}", host, actualPort);
    }

    /**
     * Stop listening and release the port. Called by Spring via {@code @Bean(destroyMethod = "stop")}
     * on context close. Idempotent — a second call no-ops because {@code server} is nulled out.
     */
    public void stop() {
        if (server == null) {
            return;
        }
        // Story #009b — unregister BEFORE server.stop(0) so peer agents see the
        // LINGS-S08 miss instead of a stale card pointing at a half-closed port.
        unregisterInProcess();
        // Story #009c — drop in-process task state so a restart doesn't replay
        // tasks from the previous JVM lifetime.
        taskStore.clear();
        int port = actualPort;
        server.stop(0);
        this.server = null;
        LOG.info("[A2aServer] stopped on port {}", port);
    }

    /** Bound port — useful when {@code a2a.port = 0} (OS-assigned). */
    public int getActualPort() {
        return actualPort;
    }

    /**
     * 🆕 Story #009b — register this server's AgentCard into the in-process
     * registry so peer agents (via {@code InProcessA2aTransport.fetchCard})
     * can discover us without going through the network. Called from
     * {@link #start()} AFTER the HTTP bind succeeds (so failed bind doesn't
     * pollute the registry). No-op if {@code Identity.name} is null/blank
     * — that case is rejected upstream by {@code LocalAgentCardGenerator}
     * with {@code LINGS-T02} before {@code start()} reaches this method.
     */
    private void registerInProcess() {
        String identityName = resolveIdentityName();
        if (identityName == null) {
            return;
        }
        AgentCard card = cardRef.get();
        if (card == null) {
            // cardRef is set in step 1 of start(); reaching here means the
            // LocalAgentCardGenerator.generate() call above succeeded, so card
            // is non-null by construction.
            return;
        }
        InProcessA2aRegistry.getInstance().put(identityName, LocalAgentCardGenerator.toMap(card));
        String host = cfg.getA2a() != null && cfg.getA2a().getHost() != null
            ? cfg.getA2a().getHost() : "0.0.0.0";
        LOG.info("[A2aServer] registered in-process card: {} -> http://{}:{}",
            identityName, host, actualPort);
    }

    /**
     * 🆕 Story #009b — counterpart to {@link #registerInProcess()}. Removes
     * this server's entry from the in-process registry. Called from
     * {@link #stop()} BEFORE {@code server.stop(0)} so peer agents see
     * {@code LINGS-S08} (clean miss) instead of a stale card pointing at a
     * half-closed port. No-op if {@code Identity.name} is null/blank.
     */
    private void unregisterInProcess() {
        String identityName = resolveIdentityName();
        if (identityName == null) {
            return;
        }
        InProcessA2aRegistry.getInstance().remove(identityName);
        LOG.info("[A2aServer] unregistered in-process card: {}", identityName);
    }

    /** Resolve {@code Identity.name} defensively for the registry hooks. */
    private String resolveIdentityName() {
        AgentConfig.Identity id = cfg.getIdentity();
        if (id == null) {
            return null;
        }
        String name = id.getName();
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        return name.trim();
    }

    /** Cached card — read-only view for tests. */
    AgentCard getCard() {
        return cardRef.get();
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    /**
     * Serve the AgentCard as JSON on GET.
     *
     * <p>🆕 Story a2a-server-tool-registry-dispatch — the card is rebuilt on every
     * request via {@link LocalAgentCardGenerator#generate(AgentConfig, ToolRegistry)}
     * so {@code skills[]} reflects the current {@link ToolRegistry} state. This is
     * important because {@code AgentToolScanner} registers {@code @AgentTool} methods
     * in response to {@code ContextRefreshedEvent} — <i>after</i> the {@code @PostConstruct}
     * cache snapshot in {@link #start()}. Scanning the registry on each request is
     * O(N) but N is typically small (< 50), so the per-request cost is negligible
     * compared to network latency.
     */
    private final class AgentCardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405,
                        "{\"error\":\"method not allowed\",\"method\":\""
                            + ex.getRequestMethod() + "\"}",
                        "GET");
                    return;
                }
                AgentCard card = LocalAgentCardGenerator.generate(cfg, toolRegistry);
                String body = LocalAgentCardGenerator.toJson(card);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=60");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (JsonProcessingException jpe) {
                sendJson(ex, 500,
                    "{\"error\":\"internal server error\",\"errorCode\":\"LINGS-S06\"}",
                    null);
            } catch (RuntimeException re) {
                LOG.warn("[A2aServer] agent.json handler failed", re);
                sendJson(ex, 500,
                    "{\"error\":\"internal server error\",\"errorCode\":\"LINGS-S06\"}",
                    null);
            }
        }
    }

    /**
     * 🆕 Story #009c — minimal JSON-RPC 2.0 dispatcher for {@code POST /rpc}
     * (dsh §5.6.3.1 L2995-3172 wire protocol).
     *
     * <p><b>Scope is test-driver-only</b>: this is NOT a full skill dispatcher.
     * It supports the 3 methods needed by
     * {@link ai.lingshu.a2a.client.HttpJsonRpcA2aTransport} integration tests:
     * <ul>
     *   <li>{@code message/send} — accepts {@code params.{agentName, skill, inputJson}},
     *       synthesizes a taskId (UUID), echoes the input back inside {@code resultJson},
     *       returns {@code {status:COMPLETED, taskId, resultJson}}. Stores {@code inputJson}
     *       in {@link #taskStore} so {@code tasks/get} and {@code tasks/cancel} can be
     *       exercised by follow-up calls.</li>
     *   <li>{@code tasks/get} — returns {@code {status:COMPLETED, taskId, resultJson}}
     *       for any previously-submitted taskId; unknown taskId returns
     *       {@code {status:FAILED, taskId, error:"LINGS-S08 taskId not found"}}.
     *       Returns HTTP 200 always (JSON-RPC 2.0 envelope); the error lives in
     *       the JSON {@code result} field.</li>
     *   <li>{@code tasks/cancel} — returns {@code {acknowledged:true}} for any
     *       previously-submitted taskId (best-effort, idempotent); unknown taskId
     *       returns {@code {acknowledged:false}} (no exception, per EC-8).</li>
     * </ul>
     *
     * <p><b>JSON-RPC 2.0 error envelope</b> (HTTP 200, body envelope):
     * <ul>
     *   <li>{@code -32600} Invalid Request — body not parseable as JSON object, or
     *       missing {@code method}.</li>
     *   <li>{@code -32601} Method not found — {@code method} is not one of the 3 above.</li>
     *   <li>{@code -32602} Invalid params — {@code params} missing required keys
     *       (e.g. {@code inputJson} on {@code message/send}, {@code id} on
     *       {@code tasks/get} / {@code tasks/cancel}).</li>
     * </ul>
     *
     * <p><b>Thread-safety</b>: writes go through {@link #taskStore} which is a
     * {@link ConcurrentMap}. {@link ObjectMapper} is thread-safe.
     */
    private final class RpcDispatcherHandler implements HttpHandler {

        /** JSON-RPC 2.0 standard error codes (jsonrpc.org §5.1). */
        private static final int ERR_INVALID_REQUEST = -32600;
        private static final int ERR_METHOD_NOT_FOUND = -32601;
        private static final int ERR_INVALID_PARAMS = -32602;

        @Override
        public void handle(HttpExchange ex) throws IOException {
            // Step 1: method gate — only POST is accepted.
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405,
                    "{\"error\":\"method not allowed\",\"method\":\""
                        + ex.getRequestMethod() + "\"}",
                    "POST");
                return;
            }
            // Step 2: read body fully (no streaming — body is small JSON-RPC envelope).
            byte[] body = readAllBytes(ex.getRequestBody());
            String bodyText = new String(body, StandardCharsets.UTF_8);
            JsonNode root;
            try {
                root = SHARED_MAPPER.readTree(bodyText);
            } catch (IOException jpe) {
                writeError(ex, null, ERR_INVALID_REQUEST, "body is not valid JSON");
                return;
            }
            if (root == null || !root.isObject()) {
                writeError(ex, null, ERR_INVALID_REQUEST, "body must be a JSON object");
                return;
            }
            String method = root.path("method").asText("");
            JsonNode idNode = root.get("id");
            // JSON-RPC 2.0 spec: id may be string/number/null; we pass through as-is.
            if (method.isEmpty()) {
                writeError(ex, idNode, ERR_INVALID_REQUEST, "missing 'method' field");
                return;
            }
            JsonNode params = root.get("params");
            // Step 3: dispatch.
            try {
                switch (method) {
                    case "message/send":
                        handleMessageSend(ex, idNode, params);
                        return;
                    case "tasks/get":
                        handleTasksGet(ex, idNode, params);
                        return;
                    case "tasks/cancel":
                        handleTasksCancel(ex, idNode, params);
                        return;
                    default:
                        writeError(ex, idNode, ERR_METHOD_NOT_FOUND,
                            "Method not found: " + method);
                        return;
                }
            } catch (RuntimeException re) {
                LOG.warn("[A2aServer] /rpc {} handler threw", method, re);
                writeError(ex, idNode, -32603,
                    "Internal error: " + re.getClass().getSimpleName() + ": " + re.getMessage());
            }
        }

        /**
         * 🆕 Story a2a-server-tool-registry-dispatch — {@code message/send}:
         * require {@code params.{agentName, skill, inputJson}}, dispatch the skill to
         * the local {@link ToolRegistry}, return the {@link ToolResult} content as
         * {@code resultJson}.
         *
         * <p><b>Dispatch flow</b> (mirrors {@code DemoA2aServer.RpcHandler}):
         * <ol>
         *   <li>Cross-agent guard — reject if {@code agentName != this server's Identity.name}</li>
         *   <li>Look up skill in {@code toolRegistry}; miss → JSON-RPC {@code -32601}</li>
         *   <li>Parse {@code inputJson} (JSON-encoded String) into a {@code JsonNode}</li>
         *   <li>Invoke {@code tool.execute(new ToolCall(id, skill, input), A2aServerToolExecutionContext)}</li>
         *   <li>Wrap the {@link ToolResult} as {@code {status, taskId, resultJson}} envelope</li>
         * </ol>
         *
         * <p><b>Output shape</b> (wire-compatible with the JSON-RPC 2.0 envelope
         * documented in {@code specs/009c-a2a-httpjsonrpc-and-remote-tool/contracts/}):
         * <pre>{@code
         * {
         *   "status":     "COMPLETED" | "FAILED",
         *   "taskId":     "<uuid>",
         *   "resultJson": "<verbatim ToolResult.content>"
         * }
         * }</pre>
         *
         * <p><b>Error code mapping</b>:
         * <ul>
         *   <li>{@code ERR_INVALID_PARAMS} (-32602) — missing/empty params fields,
         *       unparseable {@code inputJson}, or wrong {@code agentName} (cross-agent guard)</li>
         *   <li>{@code ERR_METHOD_NOT_FOUND} (-32601) — ToolRegistry unavailable, or skill not registered</li>
         *   <li>JSON-RPC internal error envelope — tool throws RuntimeException (see
         *       Tool.execute SPI: implementations SHOULD return {@code ToolResult.error}
         *       instead of throwing, but we belt-and-braces here so an unexpected throw
         *       doesn't kill the JSON-RPC envelope)</li>
         * </ul>
         */
        private void handleMessageSend(HttpExchange ex, JsonNode idNode, JsonNode params)
                throws IOException {
            if (params == null || !params.isObject()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS, "params must be an object");
                return;
            }
            String agentName = params.path("agentName").asText("");
            String skill = params.path("skill").asText("");
            String inputJson = params.path("inputJson").asText("{}");
            if (agentName.isEmpty() || skill.isEmpty()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS,
                    "params.agentName and params.skill must be non-empty");
                return;
            }

            // Cross-agent guard: this server only serves its own Identity.name.
            // (Mirrors DemoA2aServer's behavior — defensive even if Identity.name
            // is unique-enough to never collide in practice.)
            String identityName = resolveIdentityName();
            if (identityName != null && !agentName.equals(identityName)) {
                writeError(ex, idNode, ERR_INVALID_PARAMS,
                    "this server only serves agentName='" + identityName
                        + "' (got '" + agentName + "')");
                return;
            }

            // ToolRegistry null guard — server constructed without Spring DI
            // (some unit tests) falls back to method-not-found.
            if (toolRegistry == null) {
                writeError(ex, idNode, ERR_METHOD_NOT_FOUND,
                    "ToolRegistry unavailable — cannot dispatch skill '" + skill + "'");
                return;
            }

            // ToolRegistry lookup.
            Tool tool = toolRegistry.lookup(skill);
            if (tool == null) {
                writeError(ex, idNode, ERR_METHOD_NOT_FOUND,
                    "skill not found in local ToolRegistry: '" + skill + "'");
                return;
            }

            // Parse the input JSON into a JsonNode for ToolCall.input.
            // inputJson arrives as a JSON-encoded String from the client
            // (HttpJsonRpcA2aTransport.submit() serializes the args object to String).
            JsonNode toolInput;
            try {
                toolInput = SHARED_MAPPER.readTree(inputJson.isEmpty() ? "{}" : inputJson);
            } catch (IOException jpe) {
                writeError(ex, idNode, ERR_INVALID_PARAMS,
                    "params.inputJson is not valid JSON: " + jpe.getMessage());
                return;
            }

            // Dispatch — synthesize an id since the JSON-RPC envelope already carries
            // the request id for correlation; the tool's toolUseId is a separate
            // opaque field.
            String toolCallId = UUID.randomUUID().toString();
            ToolCall call = new ToolCall(toolCallId, skill, toolInput);
            ToolExecutionContext toolCtx = new A2aServerToolExecutionContext();
            ToolResult result;
            try {
                result = tool.execute(call, toolCtx);
            } catch (RuntimeException ex2) {
                // Per §4.10.1 硬规则 2, Tool.execute SHOULD never throw — tool
                // implementations should return ToolResult.error. We belt-and-braces
                // here so an unanticipated throw doesn't kill the JSON-RPC envelope.
                LOG.warn("[A2aServer] tool '{}' threw unexpectedly", skill, ex2);
                String errContent = ex2.getClass().getSimpleName() + ": " + ex2.getMessage();
                ObjectNode errObj = SHARED_MAPPER.createObjectNode();
                errObj.put("status", "FAILED");
                errObj.put("taskId", toolCallId);
                errObj.put("error", "tool execution failed: " + errContent);
                errObj.put("errorCode", "LINGS-T03");
                errObj.put("resultJson", errContent);
                taskStore.put(toolCallId, errObj.toString());
                writeResult(ex, idNode, errObj);
                return;
            }

            String taskId = UUID.randomUUID().toString();
            String status = (result.getStatus() == ToolResult.Status.SUCCESS)
                ? "COMPLETED" : "FAILED";

            ObjectNode resultObj = SHARED_MAPPER.createObjectNode();
            resultObj.put("status", status);
            resultObj.put("taskId", taskId);
            // Embed the ToolResult.content — match the wire shape
            // HttpJsonRpcA2aTransport.submit() expects (an opaque JSON string inside
            // resultJson). If the tool returned ERROR, content holds the error message.
            String content = result.getContent() != null ? result.getContent() : "";
            resultObj.put("resultJson", content);
            if (!status.equals("COMPLETED")) {
                // Mirror the error message at the envelope level too so JSON-RPC
                // consumers (which may parse resultJson as a string) can still see
                // it without parsing.
                resultObj.put("error", content);
            }

            // Cache so tasks/get has something to return.
            taskStore.put(taskId, resultObj.toString());
            writeResult(ex, idNode, resultObj);
        }

        /**
         * {@code tasks/get}: require params.id; return synthesized COMPLETED status
         * with the original inputJson as resultJson.
         */
        private void handleTasksGet(HttpExchange ex, JsonNode idNode, JsonNode params)
                throws IOException {
            if (params == null || !params.isObject()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS, "params must be an object");
                return;
            }
            String taskId = params.path("id").asText("");
            if (taskId.isEmpty()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS, "params.id must be non-empty");
                return;
            }
            String inputJson = taskStore.get(taskId);
            ObjectNode result = SHARED_MAPPER.createObjectNode();
            result.put("taskId", taskId);
            if (inputJson == null) {
                result.put("status", "FAILED");
                result.put("error", "LINGS-S08 taskId not found: " + taskId);
            } else {
                result.put("status", "COMPLETED");
                result.put("resultJson", inputJson);
            }
            writeResult(ex, idNode, result);
        }

        /**
         * {@code tasks/cancel}: require params.id; return {@code {acknowledged:true}}
         * for any previously-submitted taskId, {@code false} otherwise
         * (best-effort, idempotent — no exception per EC-8).
         */
        private void handleTasksCancel(HttpExchange ex, JsonNode idNode, JsonNode params)
                throws IOException {
            if (params == null || !params.isObject()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS, "params must be an object");
                return;
            }
            String taskId = params.path("id").asText("");
            if (taskId.isEmpty()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS, "params.id must be non-empty");
                return;
            }
            String removed = taskStore.remove(taskId);
            ObjectNode result = SHARED_MAPPER.createObjectNode();
            result.put("acknowledged", removed != null);
            if (removed == null) {
                result.put("taskId", taskId);
                result.put("note", "taskId not in store (best-effort)");
            }
            writeResult(ex, idNode, result);
        }

        /** Wrap a result object in a JSON-RPC 2.0 success envelope and send. */
        private void writeResult(HttpExchange ex, JsonNode idNode, JsonNode result)
                throws IOException {
            ObjectNode envelope = SHARED_MAPPER.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", idNode != null && !idNode.isMissingNode()
                ? idNode : SHARED_MAPPER.nullNode());
            envelope.set("result", result);
            sendJson(ex, 200, envelope.toString(), null);
        }

        /** Wrap a JSON-RPC 2.0 error object and send (HTTP 200, error in envelope). */
        private void writeError(HttpExchange ex, JsonNode idNode, int code, String message)
                throws IOException {
            ObjectNode error = SHARED_MAPPER.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            ObjectNode envelope = SHARED_MAPPER.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", idNode != null && !idNode.isMissingNode()
                ? idNode : SHARED_MAPPER.nullNode());
            envelope.set("error", error);
            sendJson(ex, 200, envelope.toString(), null);
        }
    }

    /** Shared JSON-RPC ObjectMapper — thread-safe per Jackson contract. */
    private static final ObjectMapper SHARED_MAPPER = new ObjectMapper();

    /** Read entire request body into a byte[]; helper for {@link RpcDispatcherHandler}. */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /** Catch-all 404 for any other path. */
    private static final class NotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = "{\"error\":\"not found\",\"path\":\""
                + ex.getRequestURI().getPath() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(404, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ── Shared helper ───────────────────────────────────────────────────────

    private static void sendJson(HttpExchange ex, int status, String body, String allowHeader)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (allowHeader != null) {
            ex.getResponseHeaders().set("Allow", allowHeader);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}