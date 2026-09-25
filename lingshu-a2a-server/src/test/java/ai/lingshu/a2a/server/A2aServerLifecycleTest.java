package ai.lingshu.a2a.server;

import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 + L2 tests — {@link A2aServer} bean lifecycle (US2-AS1 / AS2 / AS3 / AS4 + EC-4 / EC-5).
 *
 * <p>Verifies the Spring {@code @Bean(initMethod, destroyMethod)} lifecycle contract
 * from {@code specs/009-a2a-agent-card/contracts/a2a-server-lifecycle.md}.
 */
class A2aServerLifecycleTest {

    private A2aServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static AgentConfig configFor(AgentConfig.A2a a2a, AgentConfig.Identity id) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("default", "noop",
                java.nio.file.Paths.get("."), Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            "default", "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            id,
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            "default",
            null,
            a2a,
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults());
    }

    @Test
    @DisplayName("start_withDefaultPort8080_listensOn8080")
    void start_withDefaultPort8080_listensOn8080() throws Exception {
        server = new A2aServer(configFor(AgentConfig.A2a.defaults(), AgentConfig.Identity.defaults()), null);
        server.start();

        assertThat(server.getActualPort()).isEqualTo(8080);
        // sanity: a real GET returns 200
        HttpURLConnection con = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + server.getActualPort() + "/.well-known/agent.json").openConnection();
        con.setRequestMethod("GET");
        assertThat(con.getResponseCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("start_withCustomPort_listensOnCustomPort")
    void start_withCustomPort_listensOnCustomPort() throws Exception {
        AgentConfig.A2a custom = new AgentConfig.A2a("127.0.0.1", 18090, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10);
        server = new A2aServer(configFor(custom, AgentConfig.Identity.defaults()), null);
        server.start();

        assertThat(server.getActualPort()).isEqualTo(18090);
        HttpURLConnection con = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + server.getActualPort() + "/.well-known/agent.json").openConnection();
        con.setRequestMethod("GET");
        assertThat(con.getResponseCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("start_withPortZero_returnsOsAssignedPort")
    void start_withPortZero_returnsOsAssignedPort() throws Exception {
        AgentConfig.A2a zero = new AgentConfig.A2a("127.0.0.1", 0, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10);
        server = new A2aServer(configFor(zero, AgentConfig.Identity.defaults()), null);
        server.start();

        assertThat(server.getActualPort()).isGreaterThan(0).isLessThanOrEqualTo(65535);
    }

    @Test
    @DisplayName("stop_releasesPortForRebind")
    void stop_releasesPortForRebind() throws Exception {
        AgentConfig.A2a a2a = new AgentConfig.A2a("127.0.0.1", 18091, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10);
        server = new A2aServer(configFor(a2a, AgentConfig.Identity.defaults()), null);
        server.start();
        int port = server.getActualPort();
        server.stop();

        // Rebind on same port should succeed (TIME_WAIT may still bite on some OSes,
        // but JDK HttpServer.stop(0) releases immediately).
        A2aServer server2 = new A2aServer(configFor(a2a, AgentConfig.Identity.defaults()), null);
        server2.start();
        try {
            assertThat(server2.getActualPort()).isEqualTo(port);
        } finally {
            server2.stop();
        }
    }

    @Test
    @DisplayName("start_withPortAlreadyInUse_throwsLingsS06")
    void start_withPortAlreadyInUse_throwsLingsS06() throws Exception {
        AgentConfig.A2a a2a = new AgentConfig.A2a("127.0.0.1", 18092, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10);
        server = new A2aServer(configFor(a2a, AgentConfig.Identity.defaults()), null);
        server.start();

        A2aServer conflicting = new A2aServer(configFor(a2a, AgentConfig.Identity.defaults()), null);
        try {
            assertThatThrownBy(conflicting::start)
                .isInstanceOf(LingsA2aServerException.class)
                .hasMessageContaining("LINGS-S06");
        } finally {
            // conflicting.start() may have left an orphan HttpServer only if it succeeded;
            // since it failed, no cleanup needed.
        }
    }

    @Test
    @DisplayName("start_withBlankIdentityName_throwsLingsT02")
    void start_withBlankIdentityName_throwsLingsT02() {
        AgentConfig cfg = configFor(
            AgentConfig.A2a.defaults(),
            new AgentConfig.Identity("", null, "auto",
                Collections.<String>emptyList(), null, null));
        server = new A2aServer(cfg, null);
        assertThatThrownBy(server::start)
            .isInstanceOf(LingsA2aServerException.class)
            .hasMessageContaining("LINGS-T02");
    }

    @Test
    @DisplayName("start_withInvalidPortNegative_throwsLingsS06")
    void start_withInvalidPortNegative_throwsLingsS06() {
        AgentConfig cfg = configFor(
            new AgentConfig.A2a("127.0.0.1", -1, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10),
            AgentConfig.Identity.defaults());
        server = new A2aServer(cfg, null);
        assertThatThrownBy(server::start)
            .isInstanceOf(LingsA2aServerException.class)
            .hasMessageContaining("LINGS-S06");
    }

    @Test
    @DisplayName("getAgentJson_returnsValidCard")
    void getAgentJson_returnsValidCard() throws Exception {
        server = new A2aServer(configFor(
            AgentConfig.A2a.defaults(),
            new AgentConfig.Identity("test-card", "test role", "auto",
                Collections.<String>emptyList(), null, null)), null);
        server.start();

        HttpURLConnection con = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + server.getActualPort() + "/.well-known/agent.json").openConnection();
        con.setRequestMethod("GET");
        assertThat(con.getResponseCode()).isEqualTo(200);
        assertThat(con.getHeaderField("Content-Type")).contains("application/json");
        assertThat(con.getHeaderField("Cache-Control")).contains("max-age=60");

        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        String body = sb.toString();
        assertThat(body).contains("\"name\":\"test-card\"");
        assertThat(body).contains("\"description\":\"test role\"");
        assertThat(body).contains("\"version\":\"0.1.0\"");
    }
}