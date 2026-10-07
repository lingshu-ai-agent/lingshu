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
package ai.lingshu.a2a.client;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 unit tests — {@link HttpJsonRpcA2aTransport} domain-whitelist hook points
 * (Story #034, AC-1.1—AC-1.6). Verifies the 2 hook points
 * ({@code fetchCard} + {@code jsonRpcCall} shared by submit/get/cancel) all
 * delegate to {@link ai.lingshu.core.mcp.McpHttpSupport#checkOrThrow} with the
 * constructor-supplied {@code domainWhitelist}.
 *
 * <p>These are pure unit tests — no Spring, no Mockito, no real remote agents.
 * The {@link HttpServer} fixture only exists to give the "matching host" case a
 * reachable target; all denial cases fire before any HTTP request is sent, so
 * the server is not contacted.
 *
 * <p><b>Mirrors</b> — {@code McpHttpSupportCheckOrThrowTest} from Story #033
 * (8 L1 cases for the helper itself); this file is the
 * {@code HttpJsonRpcA2aTransport}-level counterpart.
 */
class HttpJsonRpcA2aTransportCheckOrThrowTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private int port;
    private AgentCardCache cache;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = ("{\"name\":\"alice\",\"skills\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });
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

    // ─── AC-1.1: empty whitelist denies ALL outgoing HTTP ────────────────

    @Test
    @DisplayName("AC-1.1: fetchCard_emptyWhitelist_throwsAccessDenied_strictModeDefault")
    void fetchCard_emptyWhitelist_throwsAccessDenied() {
        // 🆕 Story #034 — strict mode (mirrors #033 McpServerConfig.domainWhitelist default).
        // Empty whitelist = deny ALL HTTP, even to a valid reachable host.
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port,
            JSON, cache, Duration.ofSeconds(5),
            Collections.<String>emptyList());

        assertThatThrownBy(() -> transport.fetchCard("alice"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("[LINGS-S01]")
            .hasMessageContaining("Domain not whitelisted");
    }

    // ─── AC-1.2: non-matching host throws AccessDeniedException ───────────

    @Test
    @DisplayName("AC-1.2: fetchCard_nonMatchingHost_throwsAccessDenied")
    void fetchCard_nonMatchingHost_throwsAccessDenied() {
        // Whitelist contains a different host; 127.0.0.1 must be denied.
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port,
            JSON, cache, Duration.ofSeconds(5),
            Arrays.asList("example.com", "another.host.com"));

        assertThatThrownBy(() -> transport.fetchCard("alice"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("[LINGS-S01]")
            .hasMessageContaining("Domain not whitelisted")
            .hasMessageContaining("127.0.0.1");
    }

    // ─── AC-1.3: matching host reaches the HTTP server ───────────────────

    @Test
    @DisplayName("AC-1.3: fetchCard_matchingHost_reachesHttpServer_noAccessDenied")
    void fetchCard_matchingHost_reachesHttpServer() {
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port,
            JSON, cache, Duration.ofSeconds(5),
            Arrays.asList("127.0.0.1"));

        // Should reach the HTTP layer and parse the JSON AgentCard.
        Map<String, Object> card = transport.fetchCard("alice");
        assertThat(card).isNotNull();
        assertThat(card.get("name")).isEqualTo("alice");
    }

    // ─── AC-1.4: constructor validates all 5 args ────────────────────────

    @Test
    @DisplayName("AC-1.4: constructor_nullOrEmptyEachArg_throwsIllegalArgument")
    void constructor_validatesAllArgs() {
        Duration t = Duration.ofSeconds(5);
        List<String> wl = Arrays.asList("127.0.0.1");

        // null httpBaseUrl
        assertThatThrownBy(() -> new HttpJsonRpcA2aTransport(
            null, JSON, cache, t, wl))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("httpBaseUrl");
        // empty httpBaseUrl
        assertThatThrownBy(() -> new HttpJsonRpcA2aTransport(
            "", JSON, cache, t, wl))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("httpBaseUrl");
        // null ObjectMapper
        assertThatThrownBy(() -> new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port, null, cache, t, wl))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("json");
        // null AgentCardCache
        assertThatThrownBy(() -> new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port, JSON, null, t, wl))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("cardCache");
        // null callTimeout
        assertThatThrownBy(() -> new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port, JSON, cache, null, wl))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("callTimeout");
        // null domainWhitelist — explicitly forbidden (must be [] for deny-all)
        assertThatThrownBy(() -> new HttpJsonRpcA2aTransport(
            "http://127.0.0.1:" + port, JSON, cache, t, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("domainWhitelist");
    }

    // ─── AC-1.5: defensive copy on the whitelist ─────────────────────────

    @Test
    @DisplayName("AC-1.5: constructor_defensiveCopiesWhitelist_originalMutationIsNoOp")
    void constructor_defensiveCopiesWhitelist() {
        List<String> original = new ArrayList<>(Arrays.asList("a.com", "b.com"));
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://a.com", JSON, cache, Duration.ofSeconds(5), original);

        // Mutate the original list — transport's internal whitelist must be unaffected.
        original.add("c.com");
        original.clear();

        List<String> snapshot = transport.getDomainWhitelist();
        assertThat(snapshot)
            .as("ctor must defensive-copy: original mutation is invisible to transport")
            .containsExactly("a.com", "b.com");
    }

    // ─── AC-1.6: getDomainWhitelist() returns defensive snapshot ─────────

    @Test
    @DisplayName("AC-1.6: getDomainWhitelist_returnsDefensiveSnapshot")
    void getDomainWhitelist_returnsDefensiveSnapshot() {
        HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
            "http://x.com", JSON, cache, Duration.ofSeconds(5),
            Arrays.asList("x.com", "y.com"));

        List<String> snapshot = transport.getDomainWhitelist();
        assertThat(snapshot).containsExactly("x.com", "y.com");

        // Mutating the snapshot must not affect future calls.
        snapshot.add("z.com");
        snapshot.clear();
        assertThat(transport.getDomainWhitelist())
            .as("getDomainWhitelist() must return a fresh defensive copy each call")
            .containsExactly("x.com", "y.com");
    }
}