package ai.lingshu.examples.demoproducta2aserver;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Story #025b — custom A2A server (port 9090) that exposes local {@code @AgentTool}
 * methods as A2A skills. Pairs with {@code demo-product} (port 8080) to demonstrate
 * cross-JVM tool calls.
 *
 * <p><b>Why custom rather than {@code lingshu.a2a.server.A2aServer}</b> —
 * lingshu's stock A2aServer handles {@code message/send} by storing the
 * submitted JSON in a {@link ConcurrentMap} and returning a synthetic echo —
 * see {@code A2aServer.RpcDispatcherHandler.handleMessageSend}
 * (A2aServer.java:397-424). It does <b>not</b> dispatch to a local
 * {@code ToolRegistry}, so a client calling {@code translate} would get back
 * {@code {"echo": "<inputJson>"}} instead of a real translation. Fixing this
 * in core requires resolving {@code ToolExecutionContext} ownership across the
 * JSON-RPC boundary (currently the context flows from the engine via
 * {@code TurnContext}); out of scope for a demo. We instead:
 *
 * <ol>
 *   <li>Exclude {@code A2aServerAutoConfiguration} (see
 *       {@link DemoProductA2aServerApplication#exclude})</li>
 *   <li>Build a fresh {@code HttpServer} on {@code a2a.demo-server.port} (9090)</li>
 *   <li>Wire a JSON-RPC dispatcher that looks up the requested skill in the
 *       local {@code ToolRegistry} and calls {@link Tool#execute} with a
 *       no-op stub context</li>
 *   <li>Build the {@code AgentCard.skills} list at startup by scanning
 *       {@link ToolRegistry#modelVisibleSpecs()} (mirroring
 *       {@code RemoteAgentSchemaBuilder}, Story #009d)</li>
 * </ol>
 *
 * <p><b>Wire compatibility</b> — the JSON-RPC envelope matches what
 * {@code HttpJsonRpcA2aTransport} (Story #009c) emits:
 * {@code POST /rpc}, method {@code message/send},
 * params {@code {agentName, skill, inputJson}}. So
 * {@code demo-product}'s {@code remote_agent} tool can talk to this server
 * without any client-side change.
 */
@Component
public class DemoA2aServer implements DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(DemoA2aServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final String identityName;
    private final String host;
    private final int port;

    /** Cached AgentCard — generated once at {@link #start()}, immutable thereafter. */
    private volatile String cachedCardJson;

    /** In-process task store: taskId → last-known status JSON. Cleared on stop(). */
    private final ConcurrentMap<String, String> taskStore = new ConcurrentHashMap<String, String>();

    /** The JDK HttpServer; null before {@link #start()} and after {@link #stop()}. */
    private HttpServer server;

    /** Bound port (differs from configured when configured port = 0). */
    private int actualPort;

    @Autowired
    public DemoA2aServer(
            ToolRegistry toolRegistry,
            ai.lingshu.core.runtime.AgentConfig cfg,
            @Value("${a2a.demo-server.host:0.0.0.0}") String host,
            @Value("${a2a.demo-server.port:9090}") int port) {
        this.toolRegistry = toolRegistry;
        this.identityName = (cfg.getIdentity() != null && cfg.getIdentity().getName() != null
            && !cfg.getIdentity().getName().trim().isEmpty())
            ? cfg.getIdentity().getName().trim()
            : "translator";
        this.host = host;
        this.port = port;
    }

    /**
     * Start the embedded HttpServer. Invoked by Spring via {@code @PostConstruct}.
     * Binds the socket, registers the two handlers, starts listening.
     *
     * <p>Note: we do <b>not</b> build the AgentCard here — at {@code @PostConstruct}
     * time the local {@link ToolRegistry} is still empty because
     * {@code AgentToolScanner} registers {@code @AgentTool} methods in response to
     * {@code ContextRefreshedEvent} (fires <i>after</i> all {@code @PostConstruct}
     * callbacks). The AgentCard is built/rebuilt in
     * {@link #onApplicationReady(ApplicationReadyEvent)} instead.
     */
    @PostConstruct
    public void start() throws IOException {
        // Step 1: build a placeholder AgentCard so the field is non-null
        // (a request racing in before ContextRefreshedEvent will get an empty
        // skills[] — acceptable since this happens within microseconds of
        // Spring startup; onContextRefreshed() will overwrite this).
        this.cachedCardJson = AgentCardBuilder.build(identityName, toolRegistry);

        // Step 2: bind.
        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (java.net.BindException e) {
            throw new IOException("Failed to bind on " + host + ":" + port
                + " (is another process already using this port?): " + e.getMessage(), e);
        }
        this.actualPort = server.getAddress().getPort();

        // Step 3: register handlers — same paths as lingshu A2aServer
        // (A2aServer.java:147-149) for wire compatibility.
        server.createContext("/.well-known/agent.json", new AgentCardHandler());
        server.createContext("/rpc", new RpcHandler());
        server.createContext("/", new NotFoundHandler());

        // Step 4: start (default executor — cached thread pool).
        server.setExecutor(null);
        server.start();

        // Step 5: startup log.
        int skillCount = countSkills();
        LOG.info("[DemoA2aServer] '{}' listening on http://{}:{} ({} skill(s) advertised)",
            identityName, host, actualPort, skillCount);
    }

    /**
     * Rebuild the AgentCard after Spring Boot finishes its startup sequence.
     * {@link ApplicationReadyEvent} fires strictly <i>after</i> every
     * {@code ContextRefreshedEvent} listener — including
     * {@code AgentToolScanner.onContextRefreshed}, which registers
     * {@code @AgentTool}-annotated methods on the {@link ToolRegistry}.
     *
     * <p>Why {@code ApplicationReadyEvent} and not {@code ContextRefreshedEvent}?
     * — listener ordering between two {@code @EventListener}-annotated methods
     * for the same event type is bean-creation-order dependent and not
     * guaranteed; in practice {@code AgentToolScanner} ran <i>after</i> our
     * rebuild in initial testing, leaving {@code translate} unregistered at
     * snapshot time. {@code ApplicationReadyEvent} is published once Spring
     * Boot is fully ready (after all {@code ContextRefreshedEvent} listeners
     * complete), so we are guaranteed to see the fully populated registry.
     *
     * <p>Idempotent: re-firing this simply regenerates the same JSON. The
     * card is a snapshot of the registry at ready-time, not a live mirror.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        this.cachedCardJson = AgentCardBuilder.build(identityName, toolRegistry);
        int skillCount = countSkills();
        LOG.info("[DemoA2aServer] AgentCard rebuilt after ApplicationReadyEvent ({} skill(s))",
            skillCount);
    }

    @Override
    public void destroy() {
        stop();
    }

    /** Stop listening and release the port. Idempotent. */
    public void stop() {
        if (server == null) {
            return;
        }
        int port = actualPort;
        taskStore.clear();
        server.stop(0);
        this.server = null;
        LOG.info("[DemoA2aServer] '{}' stopped on port {}", identityName, port);
    }

    public int getActualPort() {
        return actualPort;
    }

    private int countSkills() {
        try {
            JsonNode root = JSON.readTree(cachedCardJson);
            JsonNode skills = root.get("skills");
            return skills != null && skills.isArray() ? skills.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ── Handlers ───────────────────────────────────────────────────────────

    /** Serve the cached AgentCard JSON on GET. */
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
                byte[] body = cachedCardJson.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=60");
                ex.sendResponseHeaders(200, body.length);
                try (java.io.OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            } catch (RuntimeException re) {
                LOG.warn("[DemoA2aServer] agent.json handler failed", re);
                sendJson(ex, 500, "{\"error\":\"internal server error\"}", null);
            }
        }
    }

    /**
     * JSON-RPC 2.0 dispatcher for {@code POST /rpc}. Supports:
     * <ul>
     *   <li>{@code message/send} — looks up {@code params.skill} in
     *       {@link ToolRegistry}, builds a {@link ToolCall}, invokes
     *       {@link Tool#execute} with a no-op stub context, returns the
     *       result JSON.</li>
     *   <li>{@code tasks/get} — returns the cached status JSON for a
     *       previously submitted taskId.</li>
     *   <li>{@code tasks/cancel} — acknowledges but does no actual
     *       cancellation (synchronous dispatch in this demo).</li>
     * </ul>
     */
    private final class RpcHandler implements HttpHandler {

        // JSON-RPC 2.0 standard error codes (jsonrpc.org §5.1).
        private static final int ERR_INVALID_REQUEST = -32600;
        private static final int ERR_METHOD_NOT_FOUND = -32601;
        private static final int ERR_INVALID_PARAMS = -32602;

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405,
                    "{\"error\":\"method not allowed\",\"method\":\""
                        + ex.getRequestMethod() + "\"}",
                    "POST");
                return;
            }
            byte[] body = readAllBytes(ex.getRequestBody());
            JsonNode root;
            try {
                root = JSON.readTree(new String(body, StandardCharsets.UTF_8));
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
            if (method.isEmpty()) {
                writeError(ex, idNode, ERR_INVALID_REQUEST, "missing 'method' field");
                return;
            }
            JsonNode params = root.get("params");
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
                LOG.warn("[DemoA2aServer] /rpc {} handler threw", method, re);
                writeError(ex, idNode, -32603,
                    "Internal error: " + re.getClass().getSimpleName() + ": " + re.getMessage());
            }
        }

        /**
         * {@code message/send}: parse inputJson → look up skill → invoke
         * ToolRegistry.execute → return the ToolResult content as the
         * JSON-RPC result.
         *
         * <p>Output shape (compatible with {@code HttpJsonRpcA2aTransport.submit}):
         * <pre>{@code
         * {
         *   "status":     "COMPLETED" | "FAILED",
         *   "taskId":     "<uuid>",
         *   "resultJson": "<verbatim ToolResult.content, JSON-encoded>"
         * }
         * }</pre>
         */
        private void handleMessageSend(HttpExchange ex, JsonNode idNode, JsonNode params) throws IOException {
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

            // Cross-agent guard: this server only serves its own identity.
            if (!agentName.equals(identityName)) {
                writeError(ex, idNode, ERR_INVALID_PARAMS,
                    "this server only serves agentName='" + identityName
                        + "' (got '" + agentName + "')");
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
            JsonNode toolInput;
            try {
                toolInput = JSON.readTree(inputJson.isEmpty() ? "{}" : inputJson);
            } catch (IOException jpe) {
                writeError(ex, idNode, ERR_INVALID_PARAMS,
                    "params.inputJson is not valid JSON: " + jpe.getMessage());
                return;
            }

            // Dispatch — synthesize an id since the JSON-RPC envelope already
            // carries the request id for correlation; the tool's toolUseId is
            // a separate opaque field.
            String toolCallId = UUID.randomUUID().toString();
            ToolCall call = new ToolCall(toolCallId, skill, toolInput);
            ToolExecutionContext ctx = new StubToolExecutionContext();
            ToolResult result;
            try {
                result = tool.execute(call, ctx);
            } catch (RuntimeException ex2) {
                // Per §4.10.1 硬规则 2, Tool.execute should NEVER throw —
                // SpringAiToolAdapter always returns ToolResult.error. But we
                // belt-and-braces here so an unanticipated throw doesn't kill
                // the JSON-RPC envelope.
                LOG.warn("[DemoA2aServer] tool '{}' threw", skill, ex2);
                ObjectNode errResult = JSON.createObjectNode();
                errResult.put("status", "FAILED");
                errResult.put("taskId", toolCallId);
                errResult.put("error", ex2.getClass().getSimpleName() + ": " + ex2.getMessage());
                ObjectNode envelope = JSON.createObjectNode();
                envelope.put("jsonrpc", "2.0");
                envelope.set("id", idNode != null ? idNode : JSON.nullNode());
                envelope.set("result", errResult);
                sendJson(ex, 200, envelope.toString(), null);
                return;
            }

            String taskId = UUID.randomUUID().toString();
            String status = (result.getStatus() == ToolResult.Status.SUCCESS) ? "COMPLETED" : "FAILED";

            ObjectNode resultObj = JSON.createObjectNode();
            resultObj.put("status", status);
            resultObj.put("taskId", taskId);
            // Embed the ToolResult.content — match the wire shape
            // HttpJsonRpcA2aTransport.submit() expects (an opaque JSON string
            // inside resultJson).
            resultObj.put("resultJson", result.getContent() != null ? result.getContent() : "");
            if (status.equals("FAILED")) {
                resultObj.put("error", result.getContent());
            }

            // Cache so tasks/get has something to return.
            taskStore.put(taskId, resultObj.toString());

            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", idNode != null ? idNode : JSON.nullNode());
            envelope.set("result", resultObj);
            sendJson(ex, 200, envelope.toString(), null);
        }

        private void handleTasksGet(HttpExchange ex, JsonNode idNode, JsonNode params) throws IOException {
            if (params == null || !params.isObject()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS, "params must be an object");
                return;
            }
            String taskId = params.path("id").asText("");
            if (taskId.isEmpty()) {
                writeError(ex, idNode, ERR_INVALID_PARAMS, "params.id must be non-empty");
                return;
            }
            String cached = taskStore.get(taskId);
            ObjectNode result = JSON.createObjectNode();
            result.put("taskId", taskId);
            if (cached == null) {
                result.put("status", "FAILED");
                result.put("error", "taskId not found: " + taskId);
            } else {
                // Merge the cached result into our response. JSON.parse +
                // set() gives us a proper nested object rather than an
                // escaped string.
                try {
                    JsonNode parsed = JSON.readTree(cached);
                    Iterator<String> fields = parsed.fieldNames();
                    while (fields.hasNext()) {
                        String f = fields.next();
                        result.set(f, parsed.get(f));
                    }
                } catch (JsonProcessingException jpe) {
                    result.put("status", "FAILED");
                    result.put("error", "cached payload malformed: " + jpe.getMessage());
                }
            }
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", idNode != null ? idNode : JSON.nullNode());
            envelope.set("result", result);
            sendJson(ex, 200, envelope.toString(), null);
        }

        private void handleTasksCancel(HttpExchange ex, JsonNode idNode, JsonNode params) throws IOException {
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
            ObjectNode result = JSON.createObjectNode();
            result.put("acknowledged", removed != null);
            if (removed != null) {
                result.put("taskId", taskId);
            } else {
                result.put("note", "taskId not in store (best-effort)");
            }
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", idNode != null ? idNode : JSON.nullNode());
            envelope.set("result", result);
            sendJson(ex, 200, envelope.toString(), null);
        }

        private void writeError(HttpExchange ex, JsonNode idNode, int code, String message) throws IOException {
            ObjectNode error = JSON.createObjectNode();
            error.put("code", code);
            error.put("message", message);
            ObjectNode envelope = JSON.createObjectNode();
            envelope.put("jsonrpc", "2.0");
            envelope.set("id", idNode != null ? idNode : JSON.nullNode());
            envelope.set("error", error);
            sendJson(ex, 200, envelope.toString(), null);
        }
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
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private static void sendJson(HttpExchange ex, int status, String body, String allowHeader)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (allowHeader != null) {
            ex.getResponseHeaders().set("Allow", allowHeader);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (java.io.OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    // ── AgentCard builder ──────────────────────────────────────────────────

    /**
     * Build the {@code GET /.well-known/agent.json} payload by scanning
     * {@link ToolRegistry#modelVisibleSpecs()} for advertised skills.
     *
     * <p>Mirrors {@code RemoteAgentSchemaBuilder.buildToolSpecs} (Story #009d):
     * each {@code ToolSpec} becomes an {@code AgentCard.AgentSkill} entry whose
     * {@code id} equals the tool name and whose {@code description} equals
     * the tool description. Tags are derived from the tool name's prefix when
     * present (e.g. {@code "mcp:foo"} → tag {@code "mcp"}).
     */
    static final class AgentCardBuilder {
        static String build(String identityName, ToolRegistry registry) {
            ObjectNode root = JSON.createObjectNode();
            root.put("name", identityName);
            root.put("description", "Story #025b demo-product-a2a-server — "
                + "translates short text across en / es / zh / ja / fr / de.");
            root.put("version", "0.1.0");

            ArrayNode skills = root.putArray("skills");
            if (registry != null) {
                List<ai.lingshu.core.message.ToolSpec> specs =
                    new ArrayList<ai.lingshu.core.message.ToolSpec>(registry.modelVisibleSpecs());
                // Stable sort by name so the JSON is deterministic across restarts.
                Collections.sort(specs, (a, b) -> {
                    String an = a != null ? a.getName() : "";
                    String bn = b != null ? b.getName() : "";
                    if (an == null) an = "";
                    if (bn == null) bn = "";
                    return an.compareTo(bn);
                });
                for (ai.lingshu.core.message.ToolSpec spec : specs) {
                    if (spec == null || spec.getName() == null) {
                        continue;
                    }
                    ObjectNode skill = skills.addObject();
                    skill.put("id", spec.getName());
                    skill.put("name", spec.getName());
                    skill.put("description", spec.getDescription() != null
                        ? spec.getDescription() : "");
                    ArrayNode tags = skill.putArray("tags");
                    String name = spec.getName();
                    int colon = name.indexOf(':');
                    if (colon > 0) {
                        tags.add(name.substring(0, colon));
                    }
                    skill.putArray("examples");
                    skill.putArray("inputModes").add("text");
                    skill.putArray("outputModes").add("text");
                }
            }

            ObjectNode caps = root.putObject("capabilities");
            caps.put("streaming", false);
            caps.put("pushNotifications", false);
            caps.put("stateTransitionHistory", false);

            root.putArray("defaultInputModes").add("text");
            root.putArray("defaultOutputModes").add("text");
            root.putNull("securitySchemes");
            root.putNull("security");
            root.putNull("provider");
            root.putNull("documentationUrl");
            root.putNull("iconUrl");

            try {
                return JSON.writeValueAsString(root);
            } catch (JsonProcessingException jpe) {
                // Shouldn't happen for our hand-crafted schema, but fail loudly
                // rather than silently returning "{}" so a config error surfaces.
                throw new IllegalStateException(
                    "Failed to serialize DemoA2aServer AgentCard: " + jpe.getMessage(), jpe);
            }
        }
    }

    // ── Stub ToolExecutionContext ──────────────────────────────────────────

    /**
     * Minimal no-op {@link ToolExecutionContext} for tool calls invoked outside
     * the engine's normal turn flow. The {@link TranslateTools} translate
     * method doesn't touch any context fields, but {@link Tool#execute} requires
     * a non-null context per the SPI.
     *
     * <p>For tools that <i>do</i> touch the context (e.g. file system access,
     * HTTP calls, approval gates), this stub returns safe defaults — those
     * features are out of scope for this demo. If you wire such a tool here,
     * replace this with a real context sourced from a {@code TurnContext}.
     */
    private static final class StubToolExecutionContext implements ToolExecutionContext {
        @Override public ai.lingshu.core.runtime.Session session() {
            throw new UnsupportedOperationException(
                "DemoA2aServer.StubToolExecutionContext.session() — translate tool does not require session");
        }
        @Override public ToolSink sink() {
            return new ToolSink() {
                @Override public void emitPartial(String p) { /* no-op */ }
                @Override public void emitProgress(String p) { /* no-op */ }
            };
        }
        @Override public Path workingDirectory() { return Paths.get(System.getProperty("user.dir")); }
        @Override public FileSystem fs() { return FileSystems.getDefault(); }
        @Override public NetworkClient http() {
            return new NetworkClient() {
                @Override public java.io.InputStream getStream(String url) {
                    throw new UnsupportedOperationException("HTTP sandbox not wired in DemoA2aServer stub");
                }
                @Override public String get(String url) {
                    throw new UnsupportedOperationException("HTTP sandbox not wired in DemoA2aServer stub");
                }
                @Override public String post(String url, String body) {
                    throw new UnsupportedOperationException("HTTP sandbox not wired in DemoA2aServer stub");
                }
            };
        }
        @Override public ApprovalGate approval() {
            // Always deny AskUser — translate doesn't need approval. If a tool
            // here ever needs approval, replace this stub with a real one.
            return ask -> new ai.lingshu.core.decision.Decision.Deny(
                "DemoA2aServer has no ApprovalGate — AskUser denied");
        }
        @Override public CancellationToken cancellation() {
            return new CancellationToken() {
                @Override public boolean isCancelled() { return false; }
                @Override public Runnable onCancel(Runnable cb) { return () -> { /* no-op unregister */ }; }
            };
        }
        @Override public ai.lingshu.core.slot.ToolCallConfig callConfig() {
            return new ai.lingshu.core.slot.ToolCallConfig(30, 0, 0);
        }
    }
}
