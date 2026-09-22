package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.A2aTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Story #009c — HTTP/JSON-RPC 2.0 variant of {@link A2aTransport}
 * (dsh §5.6.3.1 L2995-3172).
 *
 * <p>Speaks the A2A v1.0 wire protocol over HTTPS using the JDK 11+ built-in
 * {@link HttpClient} — <b>0 additional dependencies</b>. Concretely this
 * implementation:</p>
 *
 * <ul>
 *   <li>{@link #fetchCard(String)} — {@code GET <base>/.well-known/agent.json}
 *       (per A2A v1.0 spec §2.1), results cached by {@link AgentCardCache}
 *       (Contract A3, #009a).</li>
 *   <li>{@link #submit}, {@link #get}, {@link #cancel} — JSON-RPC 2.0
 *       {@code POST <base>/rpc} with {@code method} of {@code message/send},
 *       {@code tasks/get}, {@code tasks/cancel} respectively. Responses follow
 *       the JSON-RPC 2.0 envelope ({@code result} / {@code error} fields).</li>
 *   <li>{@link #subscribe(String, Consumer)} — <b>polling placeholder</b>: every
 *       1 second calls {@link #get(String)} until the task reaches a terminal
 *       state ({@code COMPLETED}, {@code FAILED}, {@code CANCELED}). JDK 17's
 *       built-in {@link HttpClient} has no SSE EventSource; future Story may
 *       upgrade to OkHttp EventSource or JDK 21+ streaming.</li>
 * </ul>
 *
 * <p><b>Error handling</b>: any HTTP / parse / JSON-RPC {@code error} failure
 * is wrapped into {@link HttpJsonRpcException} (errorCode {@code LINGS-S08},
 * reason {@code A2A_HTTP_RPC_FAILED}). Callers (e.g. {@link RemoteAgentTool})
 * convert this into a {@code ToolResult.toolError} instead of rethrowing.</p>
 *
 * <p><b>Thread-safety</b>: this class is thread-safe. All fields are final;
 * {@link HttpClient} is documented thread-safe; {@link AgentCardCache} is
 * thread-safe per its contract.</p>
 */
public class HttpJsonRpcA2aTransport implements A2aTransport {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcA2aTransport.class);

    private static final String AGENT_CARD_PATH = "/.well-known/agent.json";
    private static final String RPC_PATH = "/rpc";
    private static final Duration SUBSCRIBE_POLL_INTERVAL = Duration.ofSeconds(1);

    private final String httpBaseUrl;
    private final ObjectMapper json;
    private final AgentCardCache cardCache;
    private final Duration callTimeout;
    private final HttpClient http;

    /**
     * @param httpBaseUrl HTTP base URL (no trailing slash); e.g. {@code "http://localhost:8080"}.
     * @param json        Jackson ObjectMapper (must not be null).
     * @param cardCache   TTL cache for fetched cards (Contract A3, #009a; must not be null).
     * @param callTimeout HTTP call timeout (must not be null).
     * @throws IllegalArgumentException if any arg is null, or if httpBaseUrl is empty.
     */
    public HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json,
                                   AgentCardCache cardCache, Duration callTimeout) {
        if (httpBaseUrl == null || httpBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("httpBaseUrl must not be null/empty");
        }
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        if (cardCache == null) {
            throw new IllegalArgumentException("cardCache must not be null");
        }
        if (callTimeout == null) {
            throw new IllegalArgumentException("callTimeout must not be null");
        }
        // strip trailing slash to avoid "<base>//.well-known/..." double slash
        this.httpBaseUrl = httpBaseUrl.endsWith("/")
            ? httpBaseUrl.substring(0, httpBaseUrl.length() - 1)
            : httpBaseUrl;
        this.json = json;
        this.cardCache = cardCache;
        this.callTimeout = callTimeout;
        this.http = HttpClient.newBuilder()
            .connectTimeout(callTimeout)
            .build();
    }

    // ─── fetchCard ───────────────────────────────────────────────────────

    @Override
    public Map<String, Object> fetchCard(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be null/empty");
        }
        // 1) cache lookup
        Map<String, Object> cached = cardCache.get(agentName);
        if (cached != null) {
            log.debug("[HttpJsonRpcA2aTransport] fetchCard({}) cache hit", agentName);
            return cached;
        }
        // 2) HTTP GET /.well-known/agent.json
        URI uri = URI.create(httpBaseUrl + AGENT_CARD_PATH);
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(callTimeout)
            .GET()
            .header("Accept", "application/json")
            .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            if (status != 200) {
                cardCache.putNegative(agentName);
                throw new HttpJsonRpcException(
                    "fetchCard HTTP " + status + ": " + truncate(resp.body(), 200),
                    "verify '" + httpBaseUrl + "' is reachable and serving the A2A agent.json endpoint"
                );
            }
            Map<String, Object> map = json.readValue(resp.body(), Map.class);
            if (map == null) {
                map = new HashMap<>();
            }
            cardCache.put(agentName, map);
            log.debug("[HttpJsonRpcA2aTransport] fetchCard({}) ok ({} fields)", agentName, map.size());
            return Collections.unmodifiableMap(new HashMap<>(map));
        } catch (HttpJsonRpcException e) {
            throw e;
        } catch (Exception e) {
            cardCache.putNegative(agentName);
            throw new HttpJsonRpcException(
                "fetchCard failed for '" + uri + "': " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                "check network connectivity to remote agent and verify A2A endpoint URL",
                e
            );
        }
    }

    // ─── submit (POST /rpc method=message/send) ──────────────────────────

    @Override
    public ToolResult submit(String agentName, String skill, String inputJson) {
        if (agentName == null || agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be null/empty");
        }
        if (skill == null || skill.isEmpty()) {
            throw new IllegalArgumentException("skill must not be null/empty");
        }
        if (inputJson == null) {
            inputJson = "{}";
        }
        ObjectNode params = json.createObjectNode();
        params.put("agentName", agentName);
        params.put("skill", skill);
        params.put("inputJson", inputJson);
        JsonNode rpc = jsonRpcCall("message/send", params);
        JsonNode result = rpc.get("result");
        if (result != null) {
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .content(safeToJson(result))
                .isError(false)
                .build();
        }
        JsonNode error = rpc.get("error");
        String msg = error != null ? error.path("message").asText("JSON-RPC error")
                                   : "A2A RPC returned no result and no error";
        throw new HttpJsonRpcException(
            "submit failed: " + msg,
            "check remote agent's logs / verify agentName and skill match the AgentCard"
        );
    }

    // ─── get (POST /rpc method=tasks/get) ────────────────────────────────

    @Override
    public ToolResult get(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId must not be null/empty");
        }
        ObjectNode params = json.createObjectNode();
        params.put("id", taskId);
        JsonNode rpc = jsonRpcCall("tasks/get", params);
        JsonNode result = rpc.get("result");
        if (result == null) {
            JsonNode error = rpc.get("error");
            String msg = error != null ? error.path("message").asText("JSON-RPC error")
                                       : "A2A RPC returned no result";
            throw new HttpJsonRpcException(
                "tasks/get failed: " + msg,
                "check remote agent's logs / verify taskId is valid"
            );
        }
        String statusStr = result.path("status").asText("");
        ToolResult.Status status;
        if ("COMPLETED".equalsIgnoreCase(statusStr)) {
            status = ToolResult.Status.SUCCESS;
        } else if ("FAILED".equalsIgnoreCase(statusStr) || "CANCELED".equalsIgnoreCase(statusStr)) {
            status = ToolResult.Status.ERROR;
        } else {
            status = ToolResult.Status.SUCCESS; // RUNNING / PENDING / unknown — surface as success
        }
        return ToolResult.builder()
            .status(status)
            .content(safeToJson(result))
            .isError(status == ToolResult.Status.ERROR)
            .build();
    }

    // ─── cancel (POST /rpc method=tasks/cancel) ──────────────────────────

    @Override
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId must not be null/empty");
        }
        ObjectNode params = json.createObjectNode();
        params.put("id", taskId);
        try {
            JsonNode rpc = jsonRpcCall("tasks/cancel", params);
            JsonNode result = rpc.get("result");
            if (result == null) {
                // JSON-RPC error (e.g. -32001 TaskNotFound) → best-effort, return false
                log.debug("[HttpJsonRpcA2aTransport] cancel({}) returned no result (already done?)", taskId);
                return false;
            }
            return result.path("acknowledged").asBoolean(false);
        } catch (HttpJsonRpcException e) {
            // best-effort: HTTP/timeout/JSON-RPC error → false, do not rethrow (EC-8)
            log.debug("[HttpJsonRpcA2aTransport] cancel({}) failed: {}", taskId, e.getMessage());
            return false;
        }
    }

    // ─── subscribe (polling tasks/get placeholder) ───────────────────────

    @Override
    public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId must not be null/empty");
        }
        if (onEvent == null) {
            throw new IllegalArgumentException("onEvent must not be null");
        }
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(SUBSCRIBE_POLL_INTERVAL.toMillis());
                ToolResult tr = get(taskId);
                Map<String, Object> event = new HashMap<>();
                event.put("status", tr.getStatus().name());
                event.put("content", tr.getContent());
                onEvent.accept(Collections.unmodifiableMap(event));
                String s = tr.getStatus().name();
                if ("SUCCESS".equals(s) || "ERROR".equals(s) || "CANCELLED".equals(s)) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            // restore interrupt flag (FR-010 + EC-14)
            Thread.currentThread().interrupt();
            log.debug("[HttpJsonRpcA2aTransport] subscribe({}) interrupted", taskId);
        }
    }

    // ─── shared JSON-RPC 2.0 helper ──────────────────────────────────────

    private JsonNode jsonRpcCall(String method, JsonNode params) {
        ObjectNode body = json.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", method);
        body.set("params", params);
        URI uri = URI.create(httpBaseUrl + RPC_PATH);
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(callTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            // JSON-RPC 2.0 spec: HTTP 200 always; errors are in body envelope.
            // We tolerate HTTP 5xx as RPC failure.
            if (status >= 500) {
                throw new HttpJsonRpcException(
                    method + " HTTP " + status + ": " + truncate(resp.body(), 200),
                    "remote agent returned 5xx; check its logs"
                );
            }
            return json.readTree(resp.body());
        } catch (HttpJsonRpcException e) {
            throw e;
        } catch (Exception e) {
            throw new HttpJsonRpcException(
                method + " failed for '" + uri + "': " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                "check remote agent's connectivity and A2A endpoint URL",
                e
            );
        }
    }

    private String safeToJson(JsonNode node) {
        try {
            return json.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    // ─── test-only accessors (package-private) ────────────────────────────

    String getHttpBaseUrl() { return httpBaseUrl; }
    ObjectMapper getJson() { return json; }
    AgentCardCache getCardCache() { return cardCache; }
    Duration getCallTimeout() { return callTimeout; }

    // ─── nested exception type (LINGS-S08 A2A_HTTP_RPC_FAILED) ────────────

    /**
     * Thrown by all 5 {@link HttpJsonRpcA2aTransport} methods when the HTTP /
     * JSON-RPC layer fails (timeout, 5xx, parse error, JSON-RPC error envelope).
     *
     * <p>Error code: {@code LINGS-S08} {@code A2A_HTTP_RPC_FAILED}.</p>
     *
     * <p>This is the {@code HttpJsonRpcA2aTransport} counterpart of
     * {@link InProcessA2aTransport.InProcessA2aRegistryEmptyException} — same
     * {@code LINGS-S08} domain (Slot 9 A2A client), sub-distinguished by the
     * nested class name. See {@code specs/009c/.../spec.md FR-016}.</p>
     */
    public static final class HttpJsonRpcException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public static final String ERROR_CODE = "LINGS-S08";
        public static final String REASON = "A2A_HTTP_RPC_FAILED";

        private final String hint;

        public HttpJsonRpcException(String message, String hint) {
            super(message);
            this.hint = hint;
        }

        public HttpJsonRpcException(String message, String hint, Throwable cause) {
            super(message, cause);
            this.hint = hint;
        }

        public String getErrorCode() { return ERROR_CODE; }
        public String getReason() { return REASON; }
        public String getHint() { return hint; }

        @Override
        public String getMessage() {
            return "[" + ERROR_CODE + " " + REASON + "] " + super.getMessage()
                + (hint == null || hint.isEmpty() ? "" : "\nhint: " + hint);
        }
    }
}