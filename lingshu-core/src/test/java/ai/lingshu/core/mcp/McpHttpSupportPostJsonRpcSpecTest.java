/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #054 T041 — bidirectional codec coverage for
 * {@link McpHttpSupport#postJsonRpcSpec(String, JsonNode, long, String, String, Long)}.
 *
 * <p>Before this file the spec-compliant POST primitive had <b>three</b>
 * production call sites and <b>zero</b> test call sites — the only MCP HTTP
 * test coverage went through the legacy non-spec {@code postJsonRpc}. This
 * file pins the spec primitive down on both sides of the wire:
 *
 * <ul>
 *   <li><b>Outbound</b> — the JSON-RPC 2.0 envelope ({@code jsonrpc} / {@code id} /
 *       {@code method} / {@code params}), the spec-mandated {@code Content-Type}
 *       and {@code Accept} headers, and the {@code Mcp-Session-Id} /
 *       {@code MCP-Protocol-Version} headers (sent when negotiated, omitted
 *       when not).</li>
 *   <li><b>Inbound</b> — the {@code application/json} and
 *       {@code text/event-stream} answer shapes (including the SSE id-matching
 *       rule and the "no matching frame" failure), the {@code Mcp-Session-Id}
 *       response header, the JSON-RPC {@code error} member, the non-2xx
 *       translation, and the IO-failure translation.</li>
 *   <li><b>Notifications</b> — a JSON-RPC message without an {@code id} is
 *       sendable and no reply is awaited.</li>
 * </ul>
 *
 * <p><b>Why an in-process {@code HttpServer} and not the L3 subprocess
 * fixture</b> — T041 asks for a pure codec test. The {@code TestMcpHttpServer}
 * fixture is a full MCP server (handshake, tools, session bookkeeping) and its
 * answer shape is fixed by the fixture contract, which makes it impossible to
 * drive the "SSE answer without a matching id" or "SSE answer with two frames"
 * branches. A JDK-internal {@code com.sun.net.httpserver.HttpServer} bound to
 * {@code new InetSocketAddress("127.0.0.1", 0)} lets each case script the exact
 * bytes the server sends back, while still exercising the real
 * {@link java.net.HttpURLConnection} code path (headers, status line, body
 * streaming) — no mocking of the transport.
 *
 * <p><b>No production code is touched</b> — this file only exercises
 * {@code McHttpSupport.postJsonRpcSpec} as it already exists.
 */
@DisplayName("Story #054 T041 — McpHttpSupport.postJsonRpcSpec bidirectional codec")
class McpHttpSupportPostJsonRpcSpecTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.atomic.AtomicReference<Captured> captured =
        new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void createServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ── outbound side ───────────────────────────────────────────────────

    @Test
    @DisplayName("T041-1: the outbound packet is a JSON-RPC 2.0 envelope with the spec headers")
    void t041_outbound_sendsJsonRpc20EnvelopeAndSpecHeaders() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                respondJson(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}");
            }
        });

        ObjectNode params = mapper.createObjectNode();
        params.put("name", "echo");
        params.putObject("arguments").put("input", "hello");

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", params),
            5_000L, null, null, 7L);

        assertThat(resp.getStatus()).isEqualTo(200);

        Captured req = captured.get();
        assertThat(req.httpMethod).isEqualTo("POST");
        assertThat(req.contentType).contains("application/json");
        assertThat(req.accept)
            .as("the client must accept both answer shapes")
            .contains("application/json")
            .contains("text/event-stream");

        JsonNode sent = mapper.readTree(req.body);
        assertThat(sent.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(sent.path("id").asLong()).isEqualTo(7L);
        assertThat(sent.path("method").asText()).isEqualTo("tools/call");
        assertThat(sent.path("params").path("name").asText()).isEqualTo("echo");
        assertThat(sent.path("params").path("arguments").path("input").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("T041-2: the negotiated session id + protocol version travel in and the new session id comes back")
    void t041_outbound_forwardsSessionAndProtocolVersionAndReadsTheNewSessionBack() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                ex.getResponseHeaders().set("Mcp-Session-Id", "sess-99");
                respondJson(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{}}");
            }
        });

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            5_000L, "sess-42", "2025-06-18", 7L);

        Captured req = captured.get();
        assertThat(req.sessionIdHeader)
            .as("the live session id must be replayed on the next request")
            .isEqualTo("sess-42");
        assertThat(req.protocolVersionHeader).isEqualTo("2025-06-18");
        assertThat(resp.getSessionId())
            .as("a server-issued session id must be readable from the answer")
            .isEqualTo("sess-99");
    }

    @Test
    @DisplayName("T041-3: before version negotiation neither session id nor protocol version is sent")
    void t041_outbound_omitsSessionAndProtocolHeadersWhenNotNegotiated() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                respondJson(ex, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
            }
        });

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(1L, "initialize", mapper.createObjectNode()),
            5_000L, null, null, 1L);

        Captured req = captured.get();
        assertThat(req.sessionIdHeader).isNull();
        assertThat(req.protocolVersionHeader).isNull();
        assertThat(resp.getSessionId()).isNull();
    }

    // ── inbound side ────────────────────────────────────────────────────

    @Test
    @DisplayName("T041-4: an application/json answer decodes into the JSON-RPC result")
    void t041_inbound_parsesApplicationJsonAnswer() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                respondJson(ex, 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":\"hello\",\"isError\":false}}");
            }
        });

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            5_000L, "sess-1", "2025-06-18", 7L);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getBody().path("id").asLong()).isEqualTo(7L);
        assertThat(resp.getBody().path("result").path("content").asText()).isEqualTo("hello");

        McpCallResult result = McpHttpSupport.parseCallResult(resp.getBody());
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("T041-5: a text/event-stream answer decodes into the very same JSON-RPC result")
    void t041_inbound_parsesTextEventStreamAnswer() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                respondSse(ex, 200,
                    "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":\"hello\",\"isError\":false}}\n"
                        + "\n");
            }
        });

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            5_000L, "sess-1", "2025-06-18", 7L);

        assertThat(resp.getStatus()).isEqualTo(200);
        assertThat(resp.getBody().path("id").asLong()).isEqualTo(7L);

        McpCallResult result = McpHttpSupport.parseCallResult(resp.getBody());
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent())
            .as("FR-022 — both content types must yield the same result")
            .isEqualTo("hello");
    }

    @Test
    @DisplayName("T041-6: an SSE answer carrying several frames picks the one matching the request id")
    void t041_inbound_sseAnswerPicksTheFrameMatchingTheExpectedId() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                respondSse(ex, 200,
                    "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/message\",\"params\":{}}\n"
                        + "\n"
                        + "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":\"picked\",\"isError\":false}}\n"
                        + "\n");
            }
        });

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            5_000L, "sess-1", "2025-06-18", 7L);

        assertThat(resp.getBody().path("id").asLong())
            .as("the frame whose id matches the request must be the one returned")
            .isEqualTo(7L);
        assertThat(McpHttpSupport.parseCallResult(resp.getBody()).getContent()).isEqualTo("picked");
    }

    @Test
    @DisplayName("T041-7: an SSE answer without a matching frame fails with LINGS-M03")
    void t041_inbound_sseAnswerWithoutMatchingIdFailsWithM03() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                respondSse(ex, 200,
                    "event: message\n"
                        + "data: {\"jsonrpc\":\"2.0\",\"id\":99,\"result\":{\"content\":\"other\",\"isError\":false}}\n"
                        + "\n");
            }
        });

        assertThatThrownBy(() -> McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            5_000L, "sess-1", "2025-06-18", 7L))
            .isInstanceOf(McpTransportException.class)
            .satisfies(e -> assertThat(((McpTransportException) e).getCode()).isEqualTo("LINGS-M03"))
            .hasMessageContaining("SSE response stream ended without a matching JSON-RPC response");
    }

    @Test
    @DisplayName("T041-8: a JSON-RPC error member decodes into an error result")
    void t041_inbound_decodesJsonRpcErrorMember() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                respondJson(ex, 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}");
            }
        });

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            5_000L, "sess-1", "2025-06-18", 7L);

        assertThat(resp.getBody().has("error")).isTrue();

        McpCallResult result = McpHttpSupport.parseCallResult(resp.getBody());
        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).contains("MCP error");
    }

    @Test
    @DisplayName("T041-9: a non-2xx answer raises the status-preserving exception with LINGS-M03")
    void t041_inbound_non2xxThrowsStatusExceptionWithStatusAndM03() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                byte[] body = "session expired".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(404, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });

        assertThatThrownBy(() -> McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            5_000L, "sess-1", "2025-06-18", 7L))
            .isInstanceOf(McpHttpSupport.McpHttpStatusException.class)
            .satisfies(e -> {
                McpHttpSupport.McpHttpStatusException ex = (McpHttpSupport.McpHttpStatusException) e;
                assertThat(ex.getStatus()).isEqualTo(404);
                assertThat(ex.getCode()).isEqualTo("LINGS-M03");
            })
            .hasMessageContaining("HTTP 404")
            .hasMessageContaining("session expired");
    }

    @Test
    @DisplayName("T041-10: an IO failure raises a plain transport exception with LINGS-M03")
    void t041_inbound_ioFailureThrowsM03() throws Exception {
        // Bind a port, learn it, then close the listener — the port is now
        // deterministically closed without relying on a privileged-port literal.
        server.start();
        server.stop(0);
        server = null;

        assertThatThrownBy(() -> McpHttpSupport.postJsonRpcSpec(
            endpoint(), McpHttpSupport.wrapJsonRpc(7L, "tools/call", mapper.createObjectNode()),
            1_000L, "sess-1", "2025-06-18", 7L))
            .isInstanceOf(McpTransportException.class)
            .isNotInstanceOf(McpHttpSupport.McpHttpStatusException.class)
            .satisfies(e -> assertThat(((McpTransportException) e).getCode()).isEqualTo("LINGS-M03"))
            .hasMessageContaining("HTTP IO");
    }

    // ── notifications ───────────────────────────────────────────────────

    @Test
    @DisplayName("T041-11: a notification (no id) is sendable and no reply is awaited")
    void t041_notification_isSentWithoutIdAndNoReplyIsAwaited() throws Exception {
        serve(new Responder() {
            @Override
            public void respond(HttpExchange ex, Captured req) throws IOException {
                ex.sendResponseHeaders(202, -1);
                ex.close();
            }
        });

        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        notification.set("params", mapper.createObjectNode());

        McpHttpSupport.HttpRpcResponse resp = McpHttpSupport.postJsonRpcSpec(
            endpoint(), notification, 5_000L, "sess-1", "2025-06-18", null);

        assertThat(resp.getStatus()).isEqualTo(202);
        assertThat(resp.getBody().isEmpty())
            .as("a notification carries no answer body")
            .isTrue();

        JsonNode sent = mapper.readTree(captured.get().body);
        assertThat(sent.has("id")).isFalse();
        assertThat(sent.path("method").asText()).isEqualTo("notifications/initialized");
    }

    @Test
    @DisplayName("T041-12: an empty url or a null body is rejected up front")
    void t041_rejectsEmptyUrlAndNullBody() {
        ObjectNode body = McpHttpSupport.wrapJsonRpc(1L, "ping", mapper.createObjectNode());

        assertThatThrownBy(() -> McpHttpSupport.postJsonRpcSpec(
            null, body, 1_000L, null, null, 1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpHttpSupport.postJsonRpcSpec(
            "", body, 1_000L, null, null, 1L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> McpHttpSupport.postJsonRpcSpec(
            endpoint(), null, 1_000L, null, null, 1L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private String endpoint() {
        return "http://127.0.0.1:" + port + "/mcp";
    }

    private void serve(Responder responder) {
        server.createContext("/mcp", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                Captured req = capture(ex);
                captured.set(req);
                responder.respond(ex, req);
            }
        });
        server.start();
    }

    private Captured capture(HttpExchange ex) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        InputStream in = ex.getRequestBody();
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        in.close();
        return new Captured(
            ex.getRequestMethod(),
            ex.getRequestHeaders().getFirst("Content-Type"),
            ex.getRequestHeaders().getFirst("Accept"),
            ex.getRequestHeaders().getFirst("Mcp-Session-Id"),
            ex.getRequestHeaders().getFirst("MCP-Protocol-Version"),
            new String(buf.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void respondJson(HttpExchange ex, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, payload.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(payload);
        }
    }

    private static void respondSse(HttpExchange ex, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(status, payload.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(payload);
        }
    }

    /** What the server actually received on the wire. */
    private static final class Captured {
        private final String httpMethod;
        private final String contentType;
        private final String accept;
        private final String sessionIdHeader;
        private final String protocolVersionHeader;
        private final String body;

        Captured(String httpMethod, String contentType, String accept,
                 String sessionIdHeader, String protocolVersionHeader, String body) {
            this.httpMethod = httpMethod;
            this.contentType = contentType;
            this.accept = accept;
            this.sessionIdHeader = sessionIdHeader;
            this.protocolVersionHeader = protocolVersionHeader;
            this.body = body;
        }
    }

    /** Scripted answer for one test case. */
    private interface Responder {
        void respond(HttpExchange ex, Captured request) throws IOException;
    }
}
