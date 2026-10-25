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

import ai.lingshu.core.mcp.fixture.TestMcpHttpServer;
import ai.lingshu.core.mcp.fixture.TestMcpHttpServer.RecordedRequest;
import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #033 — L2 integration test for MCP HTTP transports + domain whitelist,
 * rewritten by Story #054 T046.
 *
 * <p>Four cases (AC-033-7..AC-033-9):
 * <ol>
 *   <li>SSE: whitelisted host → the <em>real</em> handshake reaches
 *       {@code CONNECTED} and a whitelisted call really goes out.</li>
 *   <li>SSE: non-whitelisted host → the guard blocks every attempt; no HTTP
 *       request ever leaves the client.</li>
 *   <li>SSE: non-whitelisted host → {@code CONNECTED} is never announced, not
 *       even across repeated reconnect attempts.</li>
 *   <li>Streamable HTTP: non-whitelisted host → the guard blocks every
 *       attempt; no HTTP request ever leaves the client.</li>
 * </ol>
 *
 * <p><b>What changed (Story #054 T046)</b>
 * <ul>
 *   <li>The three negative cases were <b>vacuous</b> — their own comments
 *       said so: they only asserted that the state was {@code RECONNECTING},
 *       which the connection reaches because the URL check short-circuits
 *       <em>before</em> any request is sent. They proved nothing about the
 *       guard. Each of them now additionally asserts the thing that actually
 *       matters (FR-030 — every outbound call passes the whitelist first):
 *       <b>the server recorded zero requests</b>. The server is real and
 *       reachable in every one of these cases, so a request that slipped past
 *       the guard would be recorded — the assertion cannot pass vacuously.</li>
 *   <li>The two SSE negative cases were near-duplicates of each other. They
 *       now prove different things: one that <em>no request</em> leaves the
 *       client, the other that {@code CONNECTED} is <em>never</em> announced —
 *       not once, and not even after repeated reconnect attempts.</li>
 *   <li>The positive case now proves the handshake is <em>real</em>: the
 *       fixture recorded the {@code initialize} and the SSE stream was
 *       opened, and a whitelisted {@code tools/call} really reaches the
 *       server. It is the counterpart of the "zero recorded requests"
 *       assertions — same fixture, same server, opposite outcome.</li>
 *   <li>The stale class comment claiming the fixture is an "in-process
 *       server … no ProcessBuilder indirection" is gone: since Story #054
 *       Phase 2 the fixture is a <em>subprocess</em>
 *       ({@link McpHttpTestSupport#startHttpServer()}).</li>
 *   <li>Two cases were renamed, because their old names promised something
 *       the code can never deliver: {@code callToolDenied} never called
 *       {@code callTool} (and could never see {@code [LINGS-S01]}, because
 *       {@code callTool} short-circuits on the state before it ever reaches
 *       the guard), and {@code accessDeniedThrown} never observed an
 *       exception — {@code start()} swallows it. No assertion was deleted;
 *       see the T049 checklist.</li>
 * </ul>
 *
 * <p><b>Why the recorder is the right instrument</b> — the fixture records
 * every inbound request it receives ({@code GET /sse}, the announced POST
 * URI, {@code POST /mcp}, and even unmatched paths through its catch-all
 * context). The recorder probe itself ({@code GET /__recorded}) is
 * <em>not</em> recorded, so an empty recording means "the client sent
 * nothing at all".
 */
@DisplayName("Story #033 — MCP HTTP domain guard end-to-end")
class McpHttpDomainGuardIT {

    private SseMcpServerConnection sseConn;
    private StreamableHttpMcpServerConnection streamableConn;
    private McpHttpTestSupport.ProcessHandle sseServer;
    private McpHttpTestSupport.ProcessHandle streamableServer;

    @AfterEach
    void tearDown() {
        if (sseConn != null) {
            sseConn.close();
        }
        if (streamableConn != null) {
            streamableConn.close();
        }
        if (sseServer != null) {
            sseServer.close();
        }
        if (streamableServer != null) {
            streamableServer.close();
        }
    }

    @Test
    @DisplayName("SSE: whitelisted host (127.0.0.1) → the real handshake reaches CONNECTED")
    void sseWhitelisted_reachesConnected() throws Exception {
        sseServer = McpHttpTestSupport.startHttpServer();
        sseConn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(sseServer.baseUrl())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(2_000L)
                .reconnectCapMs(1_000L)
                .domainWhitelist(Arrays.asList("127.0.0.1"))
                .build());
        sseConn.start();
        await().atMost(Duration.ofSeconds(8))
            .until(() -> sseConn.state() == ConnectionState.CONNECTED);
        assertThat(sseConn.state()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(sseConn.listTools()).hasSize(1);

        // Story #054 T046 — the state machine's opinion is not enough: prove
        // the whitelist really let the handshake through by looking at what
        // the server received. The fixture records the request before it
        // answers it, so by the time the client is CONNECTED the handshake is
        // already on record.
        List<String> methods = methodsOf(sseServer.recorded());
        assertThat(methods)
            .as("the whitelisted handshake must have really been sent")
            .contains("initialize");
        assertThat(methods)
            .as("the long-lived SSE stream must have really been opened")
            .contains(TestMcpHttpServer.SENTINEL_SSE_OPENED);

        // The positive counterpart of the "zero recorded requests" assertions
        // below: a whitelisted call really goes out and really comes back.
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = sseConn.callTool("echo", args);
        assertThat(res.isError())
            .as("a whitelisted call must not be blocked by the guard")
            .isFalse();
        assertThat(res.getContent())
            .as("the call must really reach the server and come back")
            .contains("hello");
    }

    @Test
    @DisplayName("SSE: non-whitelisted host → the guard blocks every attempt, no request leaves the client")
    void sseNonWhitelisted_noOutboundRequest() throws Exception {
        // Server is real (so tools/list would succeed if the guard let us through),
        // but the whitelist excludes 127.0.0.1, so the doConnect() checkOrThrow fires
        // first. Connection can never leave RECONNECTING.
        sseServer = McpHttpTestSupport.startHttpServer();
        List<String> whitelist = Arrays.asList("api.allowed.example");
        sseConn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(sseServer.baseUrl()) // 127.0.0.1 — NOT in whitelist
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .domainWhitelist(whitelist)
                .build());
        sseConn.start();
        // Connection never reaches CONNECTED — guard fires at every doConnect()
        // attempt and scheduleReconnect keeps it in RECONNECTING.
        await().atMost(Duration.ofSeconds(3))
            .until(() -> sseConn.state() == ConnectionState.RECONNECTING);
        assertThat(sseConn.state()).isEqualTo(ConnectionState.RECONNECTING);

        // Story #054 T046 — the guard runs BEFORE the request leaves the
        // client, so the server must not have seen a single request. The
        // server is real and reachable: a request that slipped past the guard
        // would be recorded, so this assertion cannot pass vacuously.
        assertThat(sseServer.recorded())
            .as("FR-030: the guard must block the request before it is sent")
            .isEmpty();

        // A call on the blocked connection must not send anything either.
        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = sseConn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage()).contains("not connected");
        assertThat(sseServer.recorded())
            .as("a call on a blocked connection must not reach the server")
            .isEmpty();
    }

    @Test
    @DisplayName("SSE: non-whitelisted host → CONNECTED is never announced, not even across retries")
    void sseNonWhitelisted_neverConnected() throws Exception {
        // The hook fires inside doConnect(), which start() calls; the
        // AccessDeniedException is swallowed by start()'s catch (Throwable t)
        // and turned into RECONNECTING. So the exception itself is not
        // observable here — but the *consequences* are: the handshake never
        // completes, CONNECTED is never announced, and the tool list stays
        // empty. The state log counts real transitions, so "never announced"
        // is a claim about the whole run, not a snapshot.
        sseServer = McpHttpTestSupport.startHttpServer();
        sseConn = new SseMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.SSE)
                .url(sseServer.baseUrl()) // 127.0.0.1 — NOT in whitelist
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L) // retry every 500 ms
                .domainWhitelist(Arrays.asList("only.example.com"))
                .build());
        McpStateLog log = McpStateLog.attach(sseConn);
        sseConn.start();
        await().atMost(Duration.ofSeconds(3))
            .until(() -> sseConn.state() == ConnectionState.RECONNECTING);
        // Stay in RECONNECTING across a reconnect attempt (the cap is 500 ms,
        // so the 700 ms window covers the retry) — the guard has to hold on
        // every attempt, not only on the first.
        await().atMost(Duration.ofSeconds(3))
            .during(Duration.ofMillis(700))
            .until(() -> sseConn.state() == ConnectionState.RECONNECTING);
        // Verify it never got CONNECTED (no host whitelist pass-through).
        assertThat(log.connectCount())
            .as("the guard must stop the handshake before CONNECTED is announced")
            .isZero();
        assertThat(sseConn.listTools()).isEmpty();
        // …and not one request ever reached the server.
        assertThat(sseServer.recorded())
            .as("FR-030: every reconnect attempt must be blocked before sending")
            .isEmpty();
    }

    @Test
    @DisplayName("Streamable HTTP: non-whitelisted host → the guard blocks every attempt, no request leaves the client")
    void streamableHttpNonWhitelisted_noOutboundRequest() throws Exception {
        // Server is real + reachable. The streamable-HTTP transport checks the
        // whitelist before its first POST as well, so the handshake never
        // starts and the connection never leaves RECONNECTING.
        streamableServer = McpHttpTestSupport.startHttpServer();
        streamableConn = new StreamableHttpMcpServerConnection(
            McpServerConfig.builder()
                .name("remote")
                .transport(McpTransportType.STREAMABLE_HTTP)
                // The single MCP endpoint (FR-017) — 127.0.0.1 is NOT in the
                // whitelist below.
                .url(streamableServer.mcpEndpoint())
                .heartbeatIntervalMs(500L)
                .heartbeatTimeoutMs(1_000L)
                .reconnectCapMs(500L)
                .domainWhitelist(Arrays.asList("only.example.com"))
                .build());
        streamableConn.start();
        await().atMost(Duration.ofSeconds(3))
            .until(() -> streamableConn.state() == ConnectionState.RECONNECTING);
        assertThat(streamableConn.state()).isEqualTo(ConnectionState.RECONNECTING);

        // Story #054 T046 — the same FR-030 guarantee for the other
        // transport: the POST to /mcp never leaves the client.
        assertThat(streamableServer.recorded())
            .as("FR-030: the guard must block the POST before it is sent")
            .isEmpty();

        ObjectMapper m = new ObjectMapper();
        ObjectNode args = m.createObjectNode();
        args.put("input", "hello");
        McpCallResult res = streamableConn.callTool("echo", args);
        assertThat(res.isError()).isTrue();
        assertThat(res.getErrorMessage()).contains("not connected");
        assertThat(streamableServer.recorded())
            .as("a call on a blocked connection must not reach the server")
            .isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The JSON-RPC method of every recorded request (sentinels included). */
    private static List<String> methodsOf(List<RecordedRequest> recorded) {
        List<String> out = new ArrayList<String>();
        for (RecordedRequest r : recorded) {
            out.add(r.getMethod());
        }
        return out;
    }
}
