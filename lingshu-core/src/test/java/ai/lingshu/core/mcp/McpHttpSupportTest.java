package ai.lingshu.core.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #021c — L1 + L2 tests for {@link McpHttpSupport} (T-09).
 *
 * <p>Four cases using a JDK-internal {@code com.sun.net.httpserver.HttpServer}
 * bound to an ephemeral port, covering: HTTP 200 happy path, HTTP 5xx
 * translation to {@code LINGS-M03}, HTTP 4xx translation to {@code LINGS-M03},
 * and connection-refused translation to {@code LINGS-M03}.
 *
 * <p><b>Why no fake-server subprocess</b> — unlike stdio, HTTP tests can
 * spin up a real local HTTP server in-process. This keeps the tests
 * deterministic and fast (no {@code ProcessBuilder} indirection).
 */
@DisplayName("Story #021c — McpHttpSupport HTTP / JSON-RPC helpers")
class McpHttpSupportTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("postJsonRpc: 200 → returns parsed JSON envelope")
    void postJsonRpc_happy_returnsParsedJsonNode() {
        server.createContext("/echo", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = ex.getRequestBody().read(chunk)) > 0) {
                    buf.write(chunk, 0, n);
                }
                byte[] in = buf.toByteArray();
                JsonNode req = mapper.readTree(in);
                long id = req.path("id").asLong(0L);
                ObjectNode env = mapper.createObjectNode();
                env.put("jsonrpc", "2.0");
                env.put("id", id);
                ObjectNode result = mapper.createObjectNode();
                result.put("ok", true);
                env.set("result", result);
                byte[] body = mapper.writeValueAsBytes(env);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();

        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", 7L);
        body.put("method", "echo");

        JsonNode resp = McpHttpSupport.postJsonRpc(
            "http://127.0.0.1:" + port + "/echo", body, 5_000L);
        assertThat(resp.get("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(resp.get("id").asLong()).isEqualTo(7L);
        assertThat(resp.get("result").get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("postJsonRpc: HTTP 5xx → throws McpTransportException(LINGS-M03)")
    void postJsonRpc_http5xx_throwsM03() {
        server.createContext("/boom", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = "service unavailable".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(503, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();

        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", 1L);
        body.put("method", "x");

        assertThatThrownBy(() -> McpHttpSupport.postJsonRpc(
            "http://127.0.0.1:" + port + "/boom", body, 5_000L))
            .isInstanceOf(McpTransportException.class)
            .satisfies(e -> assertThat(((McpTransportException) e).getCode())
                .isEqualTo("LINGS-M03"))
            .hasMessageContaining("503");
    }

    @Test
    @DisplayName("postJsonRpc: HTTP 4xx → throws McpTransportException(LINGS-M03)")
    void postJsonRpc_http4xx_throwsM03() {
        server.createContext("/bad", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(400, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();

        ObjectNode body = mapper.createObjectNode();
        body.put("method", "x");

        assertThatThrownBy(() -> McpHttpSupport.postJsonRpc(
            "http://127.0.0.1:" + port + "/bad", body, 5_000L))
            .isInstanceOf(McpTransportException.class)
            .satisfies(e -> assertThat(((McpTransportException) e).getCode())
                .isEqualTo("LINGS-M03"))
            .hasMessageContaining("400");
    }

    @Test
    @DisplayName("postJsonRpc: connection refused → throws McpTransportException(LINGS-M03)")
    void postJsonRpc_connectionRefused_throwsM03() {
        // No server started — bound port unreachable.
        ObjectNode body = mapper.createObjectNode();
        body.put("method", "x");

        assertThatThrownBy(() -> McpHttpSupport.postJsonRpc(
            "http://127.0.0.1:1/nope", body, 1_000L))
            .isInstanceOf(McpTransportException.class)
            .satisfies(e -> assertThat(((McpTransportException) e).getCode())
                .isEqualTo("LINGS-M03"))
            .hasMessageContaining("HTTP IO");
    }

    /**
     * Helper for ad-hoc tests — tracks request count and binds a path.
     * Currently unused in production test methods but kept available for
     * follow-on tests that need request counting.
     */
    @SuppressWarnings("unused")
    static HttpHandler countingHandler(AtomicInteger count, HttpHandler inner) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                count.incrementAndGet();
                inner.handle(ex);
            }
        };
    }
}