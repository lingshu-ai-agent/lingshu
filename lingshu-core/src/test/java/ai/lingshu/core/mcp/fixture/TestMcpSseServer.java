package ai.lingshu.core.mcp.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * Fake MCP SSE server used by Story #021c SSE L3 integration tests.
 *
 * <p><b>What</b> — Extends the {@link TestMcpHttpServer} behavior by adding
 * a {@code GET /sse} endpoint that emits {@code text/event-stream} frames
 * forever, including periodic {@code notifications/tools/list_changed}
 * events to verify that the {@link ai.lingshu.core.mcp.SseMcpServerConnection}
 * SSE reader thread picks them up and triggers a tool-list refresh.
 *
 * <p><b>SSE endpoint</b> ({@code GET /sse}):
 * <ul>
 *   <li>{@code Content-Type: text/event-stream}</li>
 *   <li>Every {@code pushIntervalMs} (default 200ms) emits:
 *     <pre>
 *     data: {"method":"notifications/tools/list_changed"}
 *
 *     </pre></li>
 * </ul>
 *
 * <p><b>Behavior switches</b>:
 * <ul>
 *   <li>{@code pushIntervalMs=N} — milliseconds between SSE pushes (default 200).</li>
 *   <li>{@code closeSseAfter=N} — close the SSE stream after {@code N} ms
 *       (used by {@code EC-021c-4} to simulate reverse-proxy timeout).</li>
 *   <li>{@code malformedRatio=N} — every Nth push, emit non-JSON {@code data:}
 *       to verify the parser tolerates it (used by {@code EC-021c-3}).</li>
 *   <li>{@code dontReplyHealth} / {@code delayMs} — forwarded to all handlers.</li>
 * </ul>
 *
 * <p><b>Startup protocol</b> — Prints {@code PORT=<n>} first thing on stdout.
 *
 * <p><b>JDK 8 compatibility</b> — {@code com.sun.net.httpserver} is JDK 1.6+.
 */
public final class TestMcpSseServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestMcpSseServer() {
        // utility
    }

    public static void main(String[] args) throws IOException {
        final boolean dontReplyHealth = Boolean.parseBoolean(
            System.getProperty("dontReplyHealth", "false"));
        final int delayMs = Integer.parseInt(System.getProperty("delayMs", "0"));
        final int port = Integer.parseInt(System.getProperty("port", "0"));
        final int pushIntervalMs = Integer.parseInt(
            System.getProperty("pushIntervalMs", "200"));
        final int closeSseAfterMs = Integer.parseInt(
            System.getProperty("closeSseAfter", "-1"));
        final int malformedRatio = Integer.parseInt(
            System.getProperty("malformedRatio", "-1"));

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/initialize", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sleep(delayMs);
                ObjectNode r = MAPPER.createObjectNode();
                r.put("protocolVersion", "2024-11-05");
                ObjectNode info = MAPPER.createObjectNode();
                info.put("name", "test-mcp-sse");
                info.put("version", "1.0.0");
                r.set("serverInfo", info);
                r.set("capabilities", MAPPER.createObjectNode());
                writeJsonRpc(ex, 1L, r);
            }
        });
        server.createContext("/notifications/initialized", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sleep(delayMs);
                ex.sendResponseHeaders(204, -1);
                ex.close();
            }
        });
        server.createContext("/tools/list", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sleep(delayMs);
                ObjectNode r = MAPPER.createObjectNode();
                ObjectNode tool = MAPPER.createObjectNode();
                r.set("tools", MAPPER.createArrayNode().add(tool));
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
                writeJsonRpc(ex, 1L, r);
            }
        });
        server.createContext("/tools/call", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sleep(delayMs);
                ObjectNode r = MAPPER.createObjectNode();
                r.put("content", "fake-result");
                r.put("isError", false);
                writeJsonRpc(ex, 1L, r);
            }
        });
        server.createContext("/health", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                sleep(delayMs);
                if (dontReplyHealth) {
                    ex.sendResponseHeaders(500, -1);
                    ex.close();
                } else {
                    byte[] body = "OK".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                }
            }
        });
        server.createContext("/sse", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                ex.getResponseHeaders().set("Content-Type", "text/event-stream");
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, 0);
                OutputStream os = ex.getResponseBody();
                PrintWriter pw = new PrintWriter(os, false);
                int pushes = 0;
                long start = System.currentTimeMillis();
                while (!Thread.currentThread().isInterrupted()) {
                    boolean malformed = (malformedRatio > 0
                        && pushes % malformedRatio == malformedRatio - 1);
                    String data;
                    if (malformed) {
                        data = "not-json-{{{";
                    } else {
                        ObjectNode n = MAPPER.createObjectNode();
                        n.put("method", "notifications/tools/list_changed");
                        data = n.toString();
                    }
                    pw.write("data: " + data + "\n\n");
                    pw.flush();
                    try {
                        os.flush();
                    } catch (IOException e) {
                        break;
                    }
                    pushes++;
                    if (closeSseAfterMs > 0
                        && System.currentTimeMillis() - start >= closeSseAfterMs) {
                        pw.close();
                        try {
                            os.close();
                        } catch (IOException ignored) {
                            // best-effort
                        }
                        ex.close();
                        return;
                    }
                    try {
                        Thread.sleep(pushIntervalMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                pw.close();
                try {
                    os.close();
                } catch (IOException ignored) {
                    // best-effort
                }
                ex.close();
            }
        });

        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        int bound = server.getAddress().getPort();
        System.out.println("PORT=" + bound);
        System.out.flush();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static void sleep(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void writeJsonRpc(HttpExchange ex, long id, JsonNode result) throws IOException {
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
}