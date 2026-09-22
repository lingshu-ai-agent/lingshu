package ai.lingshu.a2a.server;

import ai.lingshu.core.a2a.client.InProcessA2aRegistry;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 + L2 tests — {@link A2aServer#start()} registers its AgentCard into the
 * {@link InProcessA2aRegistry} singleton; {@link A2aServer#stop()} unregisters
 * (3 cases per data-model.md MD-02).
 *
 * <p>Uses port {@code 0} so the OS-assigns a free port — no risk of port
 * collision with other tests in the same module.</p>
 */
class A2aServerInProcessRegistrationTest {

    private A2aServer server;
    private final InProcessA2aRegistry registry = InProcessA2aRegistry.getInstance();

    @BeforeEach
    void cleanRegistry() {
        registry.clear();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Test
    @DisplayName("TC-SRV-IP-1: start_registersAgentCardInRegistry_andStopUnregisters")
    void start_registersAgentCardInRegistry_andStopUnregisters() {
        AgentConfig.Identity id = new AgentConfig.Identity(
            "alice-coding",   // name
            "code assistant", // role
            "auto",           // language
            Collections.<String>emptyList(), // traits
            null,             // tone
            null              // avatar
        );
        server = new A2aServer(configFor(
            new AgentConfig.A2a("127.0.0.1", 0, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10),
            id));
        server.start();

        // registry must contain this agent with a 12-field card
        assertThat(registry.contains("alice-coding")).isTrue();
        Map<String, Object> card = registry.get("alice-coding");
        assertThat(card).isNotNull();
        assertThat(card).containsEntry("name", "alice-coding");
        assertThat(card).containsEntry("description", "code assistant");
        assertThat(card).containsKey("version");
        assertThat(card).containsKey("skills");
        assertThat(card).containsKey("capabilities");

        // stop should remove the entry
        server.stop();
        server = null;
        assertThat(registry.contains("alice-coding")).isFalse();
        assertThat(registry.get("alice-coding")).isNull();
    }

    @Test
    @DisplayName("TC-SRV-IP-2: start_withNullIdentityName_doesNotRegister")
    void start_withNullIdentityName_doesNotRegister() {
        AgentConfig.Identity nullNameId = new AgentConfig.Identity(
            null, "test", "auto",
            Collections.<String>emptyList(), null, null
        );
        server = new A2aServer(configFor(
            new AgentConfig.A2a("127.0.0.1", 0, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10),
            nullNameId));

        // null name → LocalAgentCardGenerator.generate throws LINGS-T02 (T02 = identity.name blank)
        try {
            server.start();
            // If we reach here, the registry should NOT contain a null entry
            assertThat(registry.contains(null)).isFalse();
            server.stop();
            server = null;
        } catch (LingsA2aServerException ex) {
            // expected — identity.name blank → LINGS-T02
            assertThat(ex.getErrorCode()).isEqualTo("LINGS-T02");
            assertThat(registry.size()).isZero();
        }
    }

    @Test
    @DisplayName("TC-SRV-IP-3: start_secondServerWithDifferentName_bothRegisteredInRegistry")
    void start_secondServerWithDifferentName_bothRegisteredInRegistry() {
        // First server
        AgentConfig.Identity id1 = new AgentConfig.Identity(
            "alice", "alice-role", "auto",
            Collections.<String>emptyList(), null, null);
        A2aServer s1 = new A2aServer(configFor(
            new AgentConfig.A2a("127.0.0.1", 0, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10),
            id1));
        s1.start();
        try {
            // Second server — different name, different OS-assigned port
            AgentConfig.Identity id2 = new AgentConfig.Identity(
                "bob", "bob-role", "auto",
                Collections.<String>emptyList(), null, null);
            server = new A2aServer(configFor(
                new AgentConfig.A2a("127.0.0.1", 0, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10),
                id2));
            server.start();

            assertThat(registry.contains("alice")).isTrue();
            assertThat(registry.contains("bob")).isTrue();
            assertThat(registry.size()).isGreaterThanOrEqualTo(2);
        } finally {
            s1.stop();
        }
    }

    // --- helpers -----------------------------------------------------------

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
            a2a);
    }
}
