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
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.sandbox.WhitelistedHttpClient;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
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
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #032 — L2 integration test for {@link WebFetchTool} backed by a real
 * JDK {@link HttpServer} (com.sun.net.httpserver, no extra Maven deps).
 *
 * <p>4 cases exercise the full {@code Tool → ctx.http() → WhitelistedHttpClient →
 * JDK HttpURLConnection → real socket → mock server} path. The Tool itself is
 * the production class — only the network endpoints are faked.
 *
 * <p><b>Why an IT class (not {@code *Test}):</b> opens a real local socket on a
 * kernel-assigned free port. JUnit 5 Surefire's {@code *IT} glob picks it up
 * via {@code failsafe} (or whichever runner the parent POM configures); the
 * default {@code *Test} glob runs faster in {@code surefire}.
 */
class WebFetchToolHttpServerIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private int port;
    private final AtomicReference<String> lastUserAgent = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        // Bind to port 0 → kernel assigns a free port. Read it back via
        // server.getAddress().getPort(). Avoids the well-known port-collision
        // foot-gun in CI (multiple test classes spinning up servers on 8080).
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        lastUserAgent.set(null);
        server.start();   // binds the socket + spawns the default executor — must be
                          // called before createContext() handlers can serve traffic
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            // 0 = no grace period — immediate stop, the in-flight request is
            // already completed by the time handle() returns in the test.
            server.stop(0);
        }
    }

    @Test
    @DisplayName("AC-NN-5: L2 happy path — 200 response body returned as ToolResult.success")
    void happyPath_200_returnsBody() throws Exception {
        server.createContext("/data", respondWith(200, "hello world"));

        WebFetchTool t = new WebFetchTool();
        ToolExecutionContext ctx = ctxWithLocalhostWhitelist();

        ToolResult r = t.execute(call("c1", "http://localhost:" + port + "/data"), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.isError()).isFalse();
        assertThat(r.getContent()).isEqualTo("hello world");
    }

    @Test
    @DisplayName("AC-NN-6: L2 HTTPS transparent — User-Agent header propagated to server")
    void userAgentHeaderPropagatedToServer() throws Exception {
        server.createContext("/probe", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                lastUserAgent.set(ex.getRequestHeaders().getFirst("User-Agent"));
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });

        WebFetchTool t = new WebFetchTool();
        ToolExecutionContext ctx = ctxWithLocalhostWhitelist();

        ToolResult r = t.execute(call("c1", "http://localhost:" + port + "/probe"), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        // WhitelistedHttpClient stamps "ChaOS-LingShu-Sandbox/1.0" on every
        // outbound HttpURLConnection — see WhitelistedHttpClient#openConnection.
        // The Tool itself never sets User-Agent, so this proves the request
        // actually reached the JDK HttpURLConnection layer (not a mock short-circuit).
        assertThat(lastUserAgent.get()).isEqualTo("ChaOS-LingShu-Sandbox/1.0");
    }

    @Test
    @DisplayName("AC-NN-8: L2 localhost in domain-whitelist — whitelist check passes end-to-end")
    void localhostWhitelistHit() throws Exception {
        server.createContext("/data", respondWith(200, "whitelist hit"));

        WebFetchTool t = new WebFetchTool();
        // Explicit whitelist=["localhost"] proves the whitelist check fires on
        // the requested host (not bypassed). Empty whitelist would throw
        // AccessDeniedException → would be AC-NN-7 reverse path.
        WhitelistedHttpClient client = new WhitelistedHttpClient(
            Collections.singletonList("localhost"));
        ToolExecutionContext ctx = ctxWithClient(client);

        ToolResult r = t.execute(call("c1", "http://localhost:" + port + "/data"), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).isEqualTo("whitelist hit");
    }

    @Test
    @DisplayName("AC-NN-9: L2 HTTP 404 — non-2xx response surfaced as ToolResult.error")
    void http404_returnsError() throws Exception {
        server.createContext("/missing", respondWith(404, "not found"));

        WebFetchTool t = new WebFetchTool();
        ToolExecutionContext ctx = ctxWithLocalhostWhitelist();

        ToolResult r = t.execute(call("c1", "http://localhost:" + port + "/missing"), ctx);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.isError()).isTrue();
        // WhitelistedHttpClient.get() throws IOException("HTTP 404 ...") for
        // non-2xx → WebFetchTool catches IOException and prefixes "HTTP fetch failed:".
        assertThat(r.getContent()).startsWith("HTTP fetch failed:");
        assertThat(r.getContent()).contains("404");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static HttpHandler respondWith(final int status, final String body) {
        return new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(status, payload.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(payload);
                }
            }
        };
    }

    private static ToolCall call(String id, String url) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", url);
        return new ToolCall(id, "web_fetch", input);
    }

    private static ToolExecutionContext ctxWithLocalhostWhitelist() {
        return ctxWithClient(new WhitelistedHttpClient(Collections.singletonList("localhost")));
    }

    private static ToolExecutionContext ctxWithClient(WhitelistedHttpClient client) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.http()).thenReturn(client);
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }
}
