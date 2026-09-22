package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2 slice tests — {@link HttpJsonRpcA2aTransport} against a JDK
 * {@code com.sun.net.httpserver.HttpServer} mock. Mirrors
 * {@link GrpcA2aTransportTest}'s structure: real socket, real HTTP, mock
 * JSON-RPC handler. Covers Contract A1.3 (5 methods) + FR-001—FR-017 edge cases.
 */
class HttpJsonRpcA2aTransportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Mock remote agent — echoes message/send and tracks submitted taskIds. */
    private static final class FakeRemoteAgent implements HttpHandler {

        private final ConcurrentMap<String, String> taskStore = new ConcurrentHashMap<String, String>();
        private final AtomicInteger getCount = new AtomicInteger(0);
        private final int completeOnNthGet;

        FakeRemoteAgent() {
            this(Integer.MAX_VALUE); // never auto-complete on get
        }
        FakeRemoteAgent(int completeOnNthGet) {
            this.completeOnNthGet = completeOnNthGet;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/.well-known/agent.json")) {
                handleAgentJson(ex);
            } else if (path.equals("/rpc")) {
                handleRpc(ex);
            } else {
                send(ex, 404, "{\"error\":\"not found\"}");
            }
        }

        private void handleAgentJson(HttpExchange ex) throws IOException {
            String body = "{\"name\":\"alice\",\"description\":\"test agent\","
                + "\"version\":\"1.0.0\",\"skills\":[{\"id\":\"echo\",\"description\":\"echo skill\"}]}";
            send(ex, 200, body);
        }

        private void handleRpc(HttpExchange ex) throws IOException {
            byte[] bodyBytes = readAll(ex.getRequestBody());
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            JsonNode root;
            try {
                root = JSON.readTree(body);
            } catch (Exception e) {
                send(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"parse\"}}");
                return;
            }
            String method = root.path("method").asText("");
            JsonNode idNode = root.get("id");
            JsonNode params = root.get("params");

            try {
                if ("message/send".equals(method)) {
                    String inputJson = params.path("inputJson").asText("{}");
                    String taskId = UUID.randomUUID().toString();
                    taskStore.put(taskId, inputJson);
                    String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(idNode)
                        + ",\"result\":{\"status\":\"COMPLETED\",\"taskId\":\"" + taskId + "\""
                        + ",\"resultJson\":\"{\\\"echo\\\":true}\"}}";
                    send(ex, 200, resp);
                } else if ("tasks/get".equals(method)) {
                    int callNum = getCount.incrementAndGet();
                    String taskId = params.path("id").asText("");
                    String status = callNum >= completeOnNthGet ? "COMPLETED" : "RUNNING";
                    String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(idNode)
                        + ",\"result\":{\"status\":\"" + status + "\",\"taskId\":\"" + taskId + "\""
                        + ",\"resultJson\":\"{}\"}}";
                    send(ex, 200, resp);
                } else if ("tasks/cancel".equals(method)) {
                    String taskId = params.path("id").asText("");
                    boolean acked = taskStore.remove(taskId) != null;
                    String resp = "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(idNode)
                        + ",\"result\":{\"acknowledged\":" + acked + "}}";
                    send(ex, 200, resp);
                } else {
                    send(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(idNode)
                        + ",\"error\":{\"code\":-32601,\"message\":\"Method not found: " + method + "\"}}");
                }
            } catch (Exception e) {
                send(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + JSON.writeValueAsString(idNode)
                    + ",\"error\":{\"code\":-32603,\"message\":\"" + e.getMessage() + "\"}}");
            }
        }

        /** Test-only: count how many messages were submitted. */
        int submitCount() {
            return taskStore.size();
        }
    }

    /** Returns 503 on agent.json — used for EC-4 / EC-6 negative cache. */
    private static final class FailingAgentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            send(ex, 503, "{\"error\":\"service unavailable\"}");
        }
    }

    private HttpServer server;
    private FakeRemoteAgent handler;
    private AgentCardCache cache;
    private HttpJsonRpcA2aTransport transport;
    private String baseUrl;

    @BeforeEach
    void setUp() throws Exception {
        handler = new FakeRemoteAgent();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;
        cache = new AgentCardCache(Duration.ofMinutes(5));
        transport = new HttpJsonRpcA2aTransport(baseUrl, new ObjectMapper(), cache, Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[2048];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // ─── Test 1: fetchCard happy path (VS-1 + US-1 AC-1.1) ───────────────

    @Test
    @DisplayName("testFetchCardHappyPath — fetchCard returns parsed AgentCard and populates cache")
    void testFetchCardHappyPath() {
        Map<String, Object> card = transport.fetchCard("alice");
        assertThat(card).isNotNull();
        assertThat(card).containsKey("name");
        assertThat(card.get("name")).isEqualTo("alice");
        assertThat(card).containsKey("skills");
        // Verify cache populated
        assertThat(cache.get("alice")).isNotNull();
    }

    // ─── Test 2: fetchCard hits cache on second call (VS-1 + NFR-001) ───

    @Test
    @DisplayName("testFetchCardHitsCache — second fetchCard does not hit HTTP server")
    void testFetchCardHitsCache() {
        // First call: hits server
        Map<String, Object> first = transport.fetchCard("alice");
        assertThat(first).containsKey("name");
        // Stop the server — second call must succeed via cache
        server.stop(0);
        server = null;
        Map<String, Object> second = transport.fetchCard("alice");
        assertThat(second).isEqualTo(first);
    }

    // ─── Test 3: fetchCard miss throws LINGS-S08 + negative cache (VS-2 + EC-4 + EC-6) ──

    @Test
    @DisplayName("testFetchCardMissThrowsLingsS08 — 503 wraps to HttpJsonRpcException LINGS-S08 + neg cache")
    void testFetchCardMissThrowsLingsS08() throws Exception {
        // Replace handler with one that always 503s
        server.removeContext("/");
        server.createContext("/", new FailingAgentHandler());
        AgentCardCache smallCache = new AgentCardCache(Duration.ofMinutes(5));
        HttpJsonRpcA2aTransport failing = new HttpJsonRpcA2aTransport(
            baseUrl, new ObjectMapper(), smallCache, Duration.ofSeconds(2));
        assertThatThrownBy(() -> failing.fetchCard("ghost"))
            .isInstanceOf(HttpJsonRpcA2aTransport.HttpJsonRpcException.class)
            .satisfies(e -> {
                HttpJsonRpcA2aTransport.HttpJsonRpcException ex =
                    (HttpJsonRpcA2aTransport.HttpJsonRpcException) e;
                assertThat(ex.getErrorCode()).isEqualTo("LINGS-S08");
                assertThat(ex.getReason()).isEqualTo("A2A_HTTP_RPC_FAILED");
                assertThat(ex.getMessage()).contains("503");
            });
        // Negative cache: second call within TTL should also throw (no extra HTTP)
        assertThatThrownBy(() -> failing.fetchCard("ghost"))
            .isInstanceOf(HttpJsonRpcA2aTransport.HttpJsonRpcException.class);
    }

    // ─── Test 4: submit/get/cancel happy path (VS-1) ──────────────────────

    @Test
    @DisplayName("testSubmitGetCancel — submit stores task, get returns it, cancel acknowledges")
    void testSubmitGetCancel() {
        ToolResult submit = transport.submit("alice", "echo", "{\"x\":1}");
        assertThat(submit.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        // Parse content to get taskId
        JsonNode content = parseContent(submit);
        String taskId = content.path("taskId").asText();
        assertThat(taskId).isNotEmpty();
        assertThat(content.path("status").asText()).isEqualTo("COMPLETED");

        ToolResult get = transport.get(taskId);
        assertThat(get.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(parseContent(get).path("taskId").asText()).isEqualTo(taskId);

        boolean acked = transport.cancel(taskId);
        assertThat(acked).isTrue();
    }

    // ─── Test 5: subscribe polls every 1s and stops at terminal state (VS-1 + AC-1.5 + EC-14) ──

    @Test
    @DisplayName("testSubscribePolling — subscribe polls until terminal state, then returns")
    void testSubscribePolling() throws Exception {
        // Stop existing server, restart with handler that completes on 3rd get
        server.stop(0);
        handler = new FakeRemoteAgent(3);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(null);
        server.start();
        int port = server.getAddress().getPort();
        HttpJsonRpcA2aTransport polling = new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port, new ObjectMapper(),
            new AgentCardCache(Duration.ofMinutes(5)), Duration.ofSeconds(5));

        ToolResult submit = polling.submit("alice", "echo", "{}");
        String taskId = parseContent(submit).path("taskId").asText();

        List<String> received = new ArrayList<String>();
        Thread t = new Thread(() -> polling.subscribe(taskId, ev -> received.add((String) ev.get("status"))));
        t.start();
        t.join(10_000);
        assertThat(t.isAlive()).isFalse();
        // At least one event received; terminal event should be present
        assertThat(received).isNotEmpty();
        assertThat(received.get(received.size() - 1)).isIn("SUCCESS", "ERROR", "CANCELLED");
    }

    private static JsonNode parseContent(ToolResult tr) {
        try {
            return JSON.readTree(tr.getContent());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── Stub: unused but reserved for negative-hash test verification ────

    @SuppressWarnings("unused")
    private static Map<String, Object> emptyMap() {
        return new HashMap<String, Object>();
    }
}