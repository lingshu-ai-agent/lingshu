package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.AccessDeniedException;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2 integration tests — {@link HttpJsonRpcA2aTransport} domain-whitelist
 * end-to-end via {@code submit()}/{@code get()}/{@code cancel()} against a
 * real JDK {@code com.sun.net.httpserver.HttpServer} (Story #034, AC-2.1—AC-2.3).
 *
 * <p>These IT cases are the
 * {@code HttpJsonRpcA2aTransport}-level counterpart to
 * {@code McpHttpDomainGuardIT} from Story #033 (which tested SSE + streamable
 * HTTP). The distinguishing difference: A2A uses JDK 11+ {@code java.net.http.HttpClient}
 * with a polling placeholder for {@code subscribe()}, while MCP SSE/streamable
 * uses raw {@link java.net.http.HttpURLConnection}. Both delegate to the
 * same {@code McpHttpSupport.checkOrThrow()} helper, so behaviour is
 * equivalent.
 *
 * <p><b>Server hit counter</b> — the {@link HttpHandler} fixture tracks every
 * request it receives. AC-2.2 explicitly checks {@code hits == 0} after a denied
 * submit — proving the request never left the JVM.
 */
class HttpJsonRpcA2aTransportDomainGuardIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Mock JSON-RPC server with hit-count tracking. */
    private static final class CountingAgentHandler implements HttpHandler {
        final AtomicInteger hits = new AtomicInteger(0);
        final AtomicInteger rpcHits = new AtomicInteger(0);

        @Override
        public void handle(HttpExchange ex) throws IOException {
            hits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            if ("/.well-known/agent.json".equals(path)) {
                String body = "{\"name\":\"alice\",\"skills\":[{\"id\":\"echo\"}]}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
            if ("/rpc".equals(path)) {
                rpcHits.incrementAndGet();
                String body = "{\"jsonrpc\":\"2.0\",\"id\":\"x\","
                    + "\"result\":{\"status\":\"COMPLETED\",\"taskId\":\"t1\","
                    + "\"resultJson\":\"{\\\"echo\\\":true}\"}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
            ex.sendResponseHeaders(404, -1);
            ex.close();
        }
    }

    private HttpServer server;
    private CountingAgentHandler handler;
    private int port;
    private AgentCardCache cache;

    @BeforeEach
    void setUp() throws Exception {
        handler = new CountingAgentHandler();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
        cache = new AgentCardCache(Duration.ofMinutes(5));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ─── AC-2.1: whitelisted host — full submit() round-trip succeeds ─────

    @Test
    @DisplayName("AC-2.1: submit_whitelistedHost_reachesServer_andSucceeds")
    void submit_whitelistedHost_reachesServer_andSucceeds() {
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port,
            JSON, cache, Duration.ofSeconds(5),
            Arrays.asList("127.0.0.1"));

        ToolResult result = transport.submit("alice", "echo", "{\"x\":1}");

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(result.isError()).isFalse();
        // submit() makes exactly one HTTP call (JSON-RPC POST). fetchCard is
        // called separately by RemoteAgentTool — direct submit() doesn't fetch.
        assertThat(handler.hits.get()).isEqualTo(1);
        assertThat(handler.rpcHits.get()).isEqualTo(1);
    }

    // ─── AC-2.2: non-whitelisted host — submit() throws, ZERO hits ─────

    @Test
    @DisplayName("AC-2.2: submit_nonWhitelistedHost_throwsAccessDenied_serverSeesCount=0")
    void submit_nonWhitelistedHost_throwsAccessDenied_serverSeesCount() {
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port,
            JSON, cache, Duration.ofSeconds(5),
            Arrays.asList("allowed.example.com"));

        assertThatThrownBy(() -> transport.submit("alice", "echo", "{}"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("[LINGS-S01]")
            .hasMessageContaining("Domain not whitelisted")
            .hasMessageContaining("127.0.0.1");

        // Critical: server must NOT have been contacted. The hook fires BEFORE
        // the request leaves the JVM.
        assertThat(handler.hits.get())
            .as("denied hook must fire before any HTTP request leaves the JVM")
            .isEqualTo(0);
        assertThat(handler.rpcHits.get()).isEqualTo(0);
    }

    // ─── AC-2.3: empty whitelist — strict mode denies everything ─────────

    @Test
    @DisplayName("AC-2.3: submit_emptyWhitelist_strictMode_throwsAccessDenied_serverSeesCount=0")
    void submit_emptyWhitelist_strictMode_throwsAccessDenied() {
        // 🆕 Story #034 — strict mode default: empty whitelist = deny ALL HTTP,
        // even to a reachable host. Mirrors #033 McpServerConfig.domainWhitelist.
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port,
            JSON, cache, Duration.ofSeconds(5),
            Collections.<String>emptyList());

        assertThatThrownBy(() -> transport.submit("alice", "echo", "{}"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("[LINGS-S01]")
            .hasMessageContaining("Domain not whitelisted");

        assertThat(handler.hits.get())
            .as("strict mode: empty whitelist must block even localhost")
            .isEqualTo(0);
    }
}