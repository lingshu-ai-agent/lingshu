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

import ai.lingshu.core.mcp.fixture.TestMcpHttpServer.RecordedRequest;
import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #054 T037–T040 — MCP Streamable HTTP single-endpoint session recovery
 * and dual content-type answers.
 *
 * <p>Four behaviours of {@link StreamableHttpMcpServerConnection} are pinned
 * down here, all of them through the <em>observable</em> channel (the fixture's
 * request recorder) rather than through internal state:
 *
 * <ol>
 *   <li><b>T037</b> — a {@code tools/call} answered with HTTP 404 while the
 *       transport holds a live session id ⇒ the transport clears the local
 *       session id + protocol version, re-runs {@code initialize}, and retries
 *       the request <em>with the very same JSON-RPC id</em> exactly once.</li>
 *   <li><b>T038</b> — a server that keeps answering 404 ⇒ the transport
 *       retries exactly once and then surfaces the failure honestly
 *       ({@code LINGS-M03}); it never loops.</li>
 *   <li><b>T039</b> — a 404 arriving while the transport holds <em>no</em>
 *       local session id ⇒ no re-handshake at all, straight failure.</li>
 *   <li><b>T040</b> — the same JSON-RPC request answered as
 *       {@code application/json} and as {@code text/event-stream} parses into
 *       the same result (FR-022).</li>
 * </ol>
 *
 * <p><b>Why the fixture grew a method-scoped expiry switch</b> — the pre-existing
 * {@code expireSession} switch is a <em>monotonic</em> threshold over requests that
 * carry a session header. The re-{@code initialize} triggered by a 404 posts a
 * {@code notifications/initialized} notification that <em>does</em> carry the
 * (new) session header, so under {@code expireSession} the re-handshake itself
 * 404s before the retry can ever be issued — the "same id appears twice, first
 * 404 then success" scenario was unreachable. The fixture therefore gained
 * {@code expireMethod} / {@code expireMethodCount}, which expire only the named
 * JSON-RPC method and leave the handshake traffic healthy.
 *
 * <p><b>Deviation from the literal task text (T040)</b> — the task asks for
 * "the same request issued with {@code Accept: application/json} and
 * {@code Accept: text/event-stream}". The production transport hardcodes
 * {@code Accept: application/json, text/event-stream} in
 * {@link McpHttpSupport#postJsonRpcSpec}, so the narrowed headers cannot be
 * driven through it. The case is therefore split in two halves: the transport
 * half uses the fixture's {@code respondSse} switch (the transport parses
 * SSE-shaped answers), and the wire half issues two raw HTTP POSTs with the
 * narrowed {@code Accept} values against a default fixture and asserts both
 * answers parse into the same JSON-RPC payload.
 *
 * <p><b>No production code is touched</b> — this file only exercises the
 * existing 404-recovery path in {@code StreamableHttpMcpServerConnection}.
 */
@DisplayName("Story #054 — Streamable HTTP session recovery + dual content type")
class StreamableHttpMcpServerConnectionSessionRecoveryTest {

    private McpHttpTestSupport.ProcessHandle server;
    private StreamableHttpMcpServerConnection conn;

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // ── T037 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T037: one-shot 404 → session cleared → re-initialize → same JSON-RPC id retried once → success")
    void t037_oneShot404_clearsSessionAndRetriesWithSameId() throws Exception {
        server = startFixture("expireMethod", "tools/call", "expireMethodCount", "1");
        conn = connect(server.mcpEndpoint());
        conn.start();
        await().atMost(Duration.ofSeconds(2))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        McpCallResult result = conn.callTool("echo", echoInput("hello"));

        assertThat(result.isError()).as("the retry must succeed").isFalse();
        assertThat(result.getContent()).isEqualTo("hello");

        List<RecordedRequest> calls = byMethod(server, "tools/call");
        assertThat(calls)
            .as("one original attempt + exactly one retry — never a loop")
            .hasSize(2);
        assertThat(calls.get(0).getId())
            .as("a tools/call always carries a JSON-RPC id")
            .isNotNull();
        assertThat(calls.get(1).getId())
            .as("the retry must reuse the very same JSON-RPC id")
            .isEqualTo(calls.get(0).getId());
        assertThat(calls.get(0).getSessionIdHeader())
            .as("the first attempt rides the original session")
            .isEqualTo("session-1");
        assertThat(calls.get(1).getSessionIdHeader())
            .as("the retry rides the freshly negotiated session")
            .isEqualTo("session-2");

        List<RecordedRequest> inits = byMethod(server, "initialize");
        assertThat(inits)
            .as("the transport must re-run the spec handshake")
            .hasSize(2);
        assertThat(inits.get(1).getSessionIdHeader())
            .as("the re-initialize must not carry the stale session id")
            .isNull();
        assertThat(byMethod(server, "notifications/initialized"))
            .as("each handshake is followed by the initialized notification")
            .hasSize(2);
    }

    // ── T038 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T038: persistent 404 → exactly one retry, then the error surfaces honestly (LINGS-M03)")
    void t038_persistent404_retriesOnceThenSurfacesError() throws Exception {
        server = startFixture("expireMethod", "tools/call", "expireMethodCount", "-1");
        conn = connect(server.mcpEndpoint());
        conn.start();
        await().atMost(Duration.ofSeconds(2))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        McpCallResult result = conn.callTool("echo", echoInput("hello"));

        assertThat(result.isError()).as("the failure must be surfaced, not swallowed").isTrue();
        assertThat(result.getErrorMessage())
            .contains("LINGS-M03")
            .contains("404");

        List<RecordedRequest> calls = byMethod(server, "tools/call");
        assertThat(calls)
            .as("one original attempt + exactly one retry — the retry must not loop")
            .hasSize(2);
        assertThat(calls.get(1).getId())
            .as("the retry still reuses the original JSON-RPC id")
            .isEqualTo(calls.get(0).getId());
    }

    // ── T039 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T039: 404 without a live local session → no re-handshake, straight failure")
    void t039_404WithoutLocalSession_doesNotRehandshake() throws Exception {
        // omitSessionId=true ⇒ the server never issues an Mcp-Session-Id, so
        // the transport never holds a local session id to recover.
        server = startFixture("omitSessionId", "true",
            "expireMethod", "tools/call", "expireMethodCount", "1");
        conn = connect(server.mcpEndpoint());
        conn.start();
        await().atMost(Duration.ofSeconds(2))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        McpCallResult result = conn.callTool("echo", echoInput("hello"));

        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage())
            .contains("LINGS-M03")
            .contains("404");

        assertThat(byMethod(server, "initialize"))
            .as("no re-handshake without a live session id")
            .hasSize(1);
        assertThat(byMethod(server, "tools/call"))
            .as("the failing request is not retried")
            .hasSize(1);
    }

    // ── T040 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T040a: the transport parses SSE-shaped answers (handshake, tools/list and tools/call)")
    void t040_transport_readsSseShapedAnswers() throws Exception {
        server = startFixture("respondSse", "true");
        conn = connect(server.mcpEndpoint());
        conn.start();
        await().atMost(Duration.ofSeconds(2))
            .until(() -> conn.state() == ConnectionState.CONNECTED);

        assertThat(conn.listTools()).hasSize(1);
        assertThat(conn.listTools().get(0).getName()).isEqualTo("echo");

        McpCallResult result = conn.callTool("echo", echoInput("hello"));
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).isEqualTo("hello");
    }

    @Test
    @DisplayName("T040b: the same request answered as application/json and as text/event-stream parses to the same result")
    void t040_sameRequestTwoContentTypes_parseToTheSameResult() throws Exception {
        McpHttpTestSupport.ProcessHandle raw = McpHttpTestSupport.startHttpServer();
        try {
            String requestBody = toolCallEnvelope(7L, "hello");

            RawAnswer json = rawPost(raw.mcpEndpoint(), "application/json", requestBody);
            RawAnswer sse = rawPost(raw.mcpEndpoint(), "text/event-stream", requestBody);

            assertThat(json.contentType).contains("application/json");
            assertThat(sse.contentType).contains("text/event-stream");
            assertThat(json.body)
                .as("both content types carry the very same JSON-RPC payload")
                .isEqualTo(sse.body);
            assertThat(json.body.get("id").asLong()).isEqualTo(7L);
            assertThat(json.body.get("result").path("content").asText()).isEqualTo("hello");

            McpCallResult fromJson = McpHttpSupport.parseCallResult(json.body);
            McpCallResult fromSse = McpHttpSupport.parseCallResult(sse.body);
            assertThat(fromJson.isError()).isFalse();
            assertThat(fromSse.isError()).isFalse();
            assertThat(fromSse.getContent()).isEqualTo(fromJson.getContent());
            assertThat(fromSse.getContent()).isEqualTo("hello");
        } finally {
            raw.close();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private McpHttpTestSupport.ProcessHandle startFixture(String... keyValues) throws Exception {
        Map<String, String> props = new HashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            props.put(keyValues[i], keyValues[i + 1]);
        }
        return McpHttpTestSupport.startHttpServer(props);
    }

    private StreamableHttpMcpServerConnection connect(String url) {
        return new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                .url(url)
                // 30 s ⇒ no `ping` can land inside the assertion window, so the
                // recorder only ever shows the requests this case made.
                .heartbeatIntervalMs(30_000L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
    }

    private static ObjectNode echoInput(String value) {
        ObjectNode input = McpHttpSupport.mapper().createObjectNode();
        input.put("input", value);
        return input;
    }

    private static List<RecordedRequest> byMethod(McpHttpTestSupport.ProcessHandle s, String method)
        throws Exception {
        List<RecordedRequest> out = new ArrayList<>();
        for (RecordedRequest r : s.recorded()) {
            if (method.equals(r.getMethod())) {
                out.add(r);
            }
        }
        return out;
    }

    private static String toolCallEnvelope(long id, String input) throws Exception {
        ObjectNode params = McpHttpSupport.mapper().createObjectNode();
        params.put("name", "echo");
        params.putObject("arguments").put("input", input);
        return McpHttpSupport.mapper().writeValueAsString(
            McpHttpSupport.wrapJsonRpc(id, "tools/call", params));
    }

    /**
     * Raw HTTP POST with a caller-chosen {@code Accept} header, returning the
     * response content type together with the parsed JSON-RPC payload.
     */
    private static RawAnswer rawPost(String url, String accept, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setConnectTimeout(2_000);
            conn.setReadTimeout(2_000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", accept);
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }
            int status = conn.getResponseCode();
            assertThat(status).as("raw POST must be answered 200").isEqualTo(200);
            String contentType = conn.getContentType();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            } finally {
                reader.close();
            }
            String rawBody = sb.toString();
            JsonNode parsed;
            if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
                parsed = McpHttpSupport.mapper().readTree(firstDataFrame(rawBody));
            } else {
                parsed = McpHttpSupport.mapper().readTree(rawBody);
            }
            return new RawAnswer(contentType, parsed);
        } finally {
            conn.disconnect();
        }
    }

    /** Extract the {@code data:} payload of the first SSE frame. */
    private static String firstDataFrame(String raw) {
        for (String line : raw.split("\n")) {
            if (line.startsWith("data:")) {
                return line.substring("data:".length()).trim();
            }
        }
        throw new IllegalStateException("no SSE data frame in: " + raw);
    }

    private static final class RawAnswer {
        private final String contentType;
        private final JsonNode body;

        RawAnswer(String contentType, JsonNode body) {
            this.contentType = contentType;
            this.body = body;
        }
    }
}
