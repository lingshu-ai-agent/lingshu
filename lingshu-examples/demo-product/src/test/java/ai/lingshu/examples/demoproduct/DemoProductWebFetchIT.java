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
package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.impl.sandbox.WhitelistedHttpClient;
import ai.lingshu.core.impl.tool.local.WebFetchTool;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #032 — L3 blackbox IT verifying that the demo-product wiring exposes
 * {@link WebFetchTool} (via {@link LocalToolsAutoConfiguration}'s
 * {@code Map<String, Tool>} injection) AND that {@code agent.sandbox.domain-whitelist}
 * is honoured by the real {@link WhitelistedHttpClient} produced from the
 * demo's {@link RuntimeSandbox} bean (AC-NN-15).
 *
 * <p><b>Two halves in one case</b>:
 * <ol>
 *   <li>Mock HTTP server on {@code 127.0.0.1} returns {@code "mock-body"} →
 *       {@link WebFetchTool#execute} returns it (proves end-to-end wiring:
 *       {@code @Component} → auto-registration → ctx.http() → real JDK
 *       HttpURLConnection → localhost socket → mock server).</li>
 *   <li>{@code https://example.com} is NOT in the test-time whitelist
 *       (the {@code @TestPropertySource} only whitelists {@code localhost}) →
 *       the same Tool call returns {@code ToolResult.error("[LINGS-S01] ...")}.
 *       Proves the yml-driven whitelist is the gate, not hardcoded.</li>
 * </ol>
 *
 * <p><b>Why {@code webEnvironment = NONE}</b>: we don't need Tomcat; the
 * {@code WebFetchTool} bean is registered in the application context regardless
 * of the web tier. Skipping Tomcat keeps the test fast and avoids port-8080
 * collisions in CI.
 *
 * <p><b>Why override {@code agent.sandbox.domain-whitelist=localhost}</b>:
 * the production yml lists 7 domains ({@code github.com}, {@code api.openai.com},
 * {@code localhost}, etc. — Story #032) but the test must hit a mock server on
 * localhost while still proving the whitelist gate works; pinning the list to
 * just {@code localhost} makes the assertion crisp: only localhost is allowed,
 * anything else is denied with {@code [LINGS-S01]}.
 */
@SpringBootTest(
    classes = DemoProductApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "agent.sandbox.domain-whitelist=localhost"
})
@DisplayName("AC-NN-15 demo-product: web_fetch end-to-end + domain-whitelist honoured")
class DemoProductWebFetchIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private WebFetchTool webFetchTool;
    @Autowired private RuntimeSandbox sandbox;

    private HttpServer server;
    private int port;

    @BeforeEach
    void startMockServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/data", ex -> {
            byte[] body = "mock-body".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopMockServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("AC-NN-15: localhost whitelisted → mock body returned; example.com NOT whitelisted → [LINGS-S01]")
    void webFetch_endToEnd_localhostAllowed_exampleComDenied() {
        // The demo's DefaultRuntimeSandbox.http() returns a WhitelistedHttpClient
        // constructed from the yml-driven whitelist (test override = "localhost").
        // We cast for direct test-time access — WhitelistedHttpClient is the only
        // NetworkClient implementation in production.
        WhitelistedHttpClient httpClient = (WhitelistedHttpClient) sandbox.http();

        ToolExecutionContext ctx = mockCtx(httpClient);

        // (1) localhost IS in the (test-overridden) whitelist → real JDK
        // HttpURLConnection reaches the mock server → mock body returned.
        ToolResult okResult = webFetchTool.execute(
            call("c1", "http://localhost:" + port + "/data"), ctx);

        assertThat(okResult.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(okResult.isError()).isFalse();
        assertThat(okResult.getContent()).isEqualTo("mock-body");

        // (2) example.com is NOT in the whitelist → WhitelistedHttpClient.check()
        // throws AccessDeniedException BEFORE opening any connection → Tool
        // translates to ToolResult.error with the [LINGS-S01] prefix. This
        // proves the yml-driven whitelist is the actual gate, not a hardcoded
        // value buried in the Tool.
        ToolResult deniedResult = webFetchTool.execute(
            call("c2", "https://example.com/anything"), ctx);

        assertThat(deniedResult.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(deniedResult.isError()).isTrue();
        assertThat(deniedResult.getContent()).startsWith("[LINGS-S01]");
        assertThat(deniedResult.getContent()).contains("Domain not whitelisted");
        assertThat(deniedResult.getContent()).contains("example.com");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static ToolCall call(String id, String url) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("url", url);
        return new ToolCall(id, "web_fetch", input);
    }

    /**
     * Build a {@link ToolExecutionContext} that hands the Tool our real
     * {@link WhitelistedHttpClient} (from the demo's autowired
     * {@link RuntimeSandbox}). Other methods are stubbed because
     * {@link WebFetchTool} only touches {@code http()} on the execute path.
     */
    private static ToolExecutionContext mockCtx(WhitelistedHttpClient httpClient) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.http()).thenReturn(httpClient);
        when(c.workingDirectory()).thenReturn(Paths.get(System.getProperty("user.dir")));
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }
}
