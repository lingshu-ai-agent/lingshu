package ai.lingshu.core.mcp.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake MCP HTTP server used by Story #021c L3 integration tests.
 *
 * <p><b>What</b> — A self-contained subprocess fixture that exposes the
 * minimal MCP HTTP surface ({@code initialize}, {@code notifications/initialized},
 * {@code tools/list}, {@code tools/call}, {@code GET /health}) using the
 * JDK-internal {@code com.sun.net.httpserver.HttpServer} — zero Maven
 * dependencies and JDK-8-compatible.
 *
 * <p><b>Endpoint map</b>:
 * <ul>
 *   <li>{@code POST /initialize} → {@code {protocolVersion:"2024-11-05", serverInfo, capabilities}}</li>
 *   <li>{@code POST /notifications/initialized} → 204 (notification, no body)</li>
 *   <li>{@code POST /tools/list} → {@code {tools:[{name:"echo", description, inputSchema}]}}</li>
 *   <li>{@code POST /tools/call} → {@code {content:"fake-result", isError:false}}</li>
 *   <li>{@code GET /health} → 200 "OK" (or 500 if {@code dontReplyHealth=true})</li>
 * </ul>
 *
 * <p><b>Behavior switches</b> (system properties forwarded by
 * {@link ai.lingshu.core.mcp.McpTestSupport}):
 * <ul>
 *   <li>{@code dontReplyHealth=true} — {@code GET /health} returns 500 instead of 200
 *       (used by heartbeat-failure tests).</li>
 *   <li>{@code delayMs=N} — every handler sleeps {@code N} ms before responding
 *       (used to simulate a slow server and trip read timeouts).</li>
 *   <li>{@code exitAfter=N} — after handling {@code N} total requests, the
 *       server calls {@code System.exit(0)} (used to simulate OOM / crash).</li>
 *   <li>{@code port=N} — bind to a specific port (mostly useful for the
 *       {@code StreamableHttpMcpServerConnection} tests; defaults to 0,
 *       meaning OS-assigned ephemeral).</li>
 * </ul>
 *
 * <p><b>Startup protocol</b> — The fixture prints {@code PORT=<n>} as its
 * first line of stdout so a parent test can read the bound port from the
 * subprocess's stdout pipe (mirroring the {@link TestMcpServer} pattern from
 * Story #021a).
 *
 * <p><b>JDK 8 compatibility</b> — No {@code var} / {@code List.of} /
 * {@code record} / {@code sealed}. Uses {@code com.sun.net.httpserver} which
 * is part of the JDK since 1.6.
 */
public final class TestMcpHttpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestMcpHttpServer() {
        // utility
    }

    public static void main(String[] args) throws IOException {
        final boolean dontReplyHealth = Boolean.parseBoolean(
            System.getProperty("dontReplyHealth", "false"));
        final int delayMs = Integer.parseInt(System.getProperty("delayMs", "0"));
        final int exitAfter = Integer.parseInt(System.getProperty("exitAfter", "-1"));
        final int port = Integer.parseInt(System.getProperty("port", "0"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        AtomicInteger count = new AtomicInteger(0);

        server.createContext("/initialize", new CountingHandler(count, delayMs, exitAfter,
            new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    ObjectNode r = MAPPER.createObjectNode();
                    r.put("protocolVersion", "2024-11-05");
                    ObjectNode info = MAPPER.createObjectNode();
                    info.put("name", "test-mcp-http");
                    info.put("version", "1.0.0");
                    r.set("serverInfo", info);
                    r.set("capabilities", MAPPER.createObjectNode());
                    writeJsonRpcResult(ex, r);
                }
            }));
        server.createContext("/notifications/initialized", new CountingHandler(count, delayMs, exitAfter,
            new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                }
            }));
        server.createContext("/tools/list", new CountingHandler(count, delayMs, exitAfter,
            new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] in = readAll(ex.getRequestBody());
                    JsonNode req = MAPPER.readTree(in);
                    long id = req.path("id").asLong(0L);
                    ObjectNode r = MAPPER.createObjectNode();
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
                    writeJsonRpcResultWithId(ex, id, r);
                }
            }));
        server.createContext("/tools/call", new CountingHandler(count, delayMs, exitAfter,
            new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    byte[] in = readAll(ex.getRequestBody());
                    JsonNode req = MAPPER.readTree(in);
                    long id = req.path("id").asLong(0L);
                    ObjectNode r = MAPPER.createObjectNode();
                    r.put("content", "fake-result");
                    r.put("isError", false);
                    writeJsonRpcResultWithId(ex, id, r);
                }
            }));
        server.createContext("/health", new CountingHandler(count, delayMs, exitAfter,
            new HttpHandler() {
                @Override
                public void handle(HttpExchange ex) throws IOException {
                    if (dontReplyHealth) {
                        ex.sendResponseHeaders(500, -1);
                        ex.close();
                    } else {
                        byte[] body = "OK".getBytes(StandardCharsets.UTF_8);
                        ex.sendResponseHeaders(200, body.length);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(body);
                        }
                    }
                }
            }));

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        int bound = server.getAddress().getPort();
        // Print first so parent test can read this from subprocess stdout.
        System.out.println("PORT=" + bound);
        System.out.flush();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void writeJsonRpcResult(HttpExchange ex, JsonNode result) throws IOException {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.put("id", 1L);
        env.set("result", result);
        byte[] body = MAPPER.writeValueAsBytes(env);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static void writeJsonRpcResultWithId(HttpExchange ex, long id, JsonNode result) throws IOException {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.set("result", result);
        byte[] body = MAPPER.writeValueAsBytes(env);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] readAll(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = is.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /**
     * Wraps a handler to (a) honor {@code delayMs}, (b) count total requests,
     * (c) trigger {@code System.exit} when {@code exitAfter} is reached.
     */
    private static final class CountingHandler implements HttpHandler {
        private final AtomicInteger count;
        private final int delayMs;
        private final int exitAfter;
        private final HttpHandler delegate;

        CountingHandler(AtomicInteger count, int delayMs, int exitAfter, HttpHandler delegate) {
            this.count = count;
            this.delayMs = delayMs;
            this.exitAfter = exitAfter;
            this.delegate = delegate;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            int n = count.incrementAndGet();
            delegate.handle(ex);
            if (exitAfter > 0 && n >= exitAfter) {
                System.err.println("[TestMcpHttpServer] exitAfter=" + exitAfter
                    + " reached, exiting");
                System.exit(0);
            }
        }
    }
}