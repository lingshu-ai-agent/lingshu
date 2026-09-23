package ai.lingshu.a2a.client;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 unit tests — {@link HttpJsonRpcA2aTransportProvider} (3 cases).
 * Mirrors {@link GrpcA2aTransportProviderTest} but verifies the http-jsonrpc-1.0.0
 * Provider reads {@code agent.a2a.httpBaseUrl} + {@code agent.a2a.callTimeout}.
 */
class HttpJsonRpcA2aTransportProviderTest {

    @Test
    @DisplayName("TC-PROV-H1: name_priority_version contract (FR-003)")
    void testNameVersionPriority() {
        HttpJsonRpcA2aTransportProvider provider = new HttpJsonRpcA2aTransportProvider();
        assertThat(provider.name()).isEqualTo("http-jsonrpc-1.0.0");
        assertThat(provider.priority()).isEqualTo(10);
        assertThat(provider.version()).isEqualTo("1.0.0");
        assertThat(provider).isInstanceOf(ai.lingshu.core.spi.Providers.A2aTransportProvider.class);
    }

    @Test
    @DisplayName("TC-PROV-H2: create_defaults_apply (US-2 AC-2.1 + AC-2.2 + AC-2.3)")
    void testCreateHappyPath() {
        HttpJsonRpcA2aTransportProvider provider = new HttpJsonRpcA2aTransportProvider();
        A2aTransport t = provider.create(cfg());
        assertThat(t).isInstanceOf(HttpJsonRpcA2aTransport.class);
        HttpJsonRpcA2aTransport ht = (HttpJsonRpcA2aTransport) t;
        assertThat(ht.getHttpBaseUrl()).isEqualTo("http://localhost:8080");
        assertThat(ht.getCallTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(ht.getCardCache()).isNotNull();
        assertThat(ht.getCardCache().getCacheTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(ht.getJson()).isNotNull();
    }

    @Test
    @DisplayName("TC-PROV-H3: create_customConfig_overridesDefaults (FR-008)")
    void testCreateReadsCustomConfig() {
        AgentConfig custom = withA2a(
            cfg(),
            new AgentConfig.A2a(
                "0.0.0.0", 8080, "localhost:50051", Duration.ofMinutes(5),
                "http://example.com:9090", Duration.ofSeconds(60),
                Collections.emptyList(), 10)
        );
        HttpJsonRpcA2aTransportProvider provider = new HttpJsonRpcA2aTransportProvider();
        HttpJsonRpcA2aTransport ht = (HttpJsonRpcA2aTransport) provider.create(custom);
        assertThat(ht.getHttpBaseUrl()).isEqualTo("http://example.com:9090");
        assertThat(ht.getCallTimeout()).isEqualTo(Duration.ofSeconds(60));
        // Other A2a fields preserved
        assertThat(ht.getCardCache().getCacheTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    // --- helpers ---

    private static AgentConfig cfg() {
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
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            "default",
            null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults()
        );
    }

    private static AgentConfig withA2a(AgentConfig c, AgentConfig.A2a a2a) {
        return new AgentConfig(
            c.getFlowEngine(), c.getLlm(), c.getPrompt(), c.getToolExecutor(),
            c.getSandbox(), c.getCompactor(), c.getSessionStore(),
            c.getDelegate(), c.getMcp(), c.getSkills(),
            c.getToolParallelism(), c.getToolTimeoutSeconds(),
            c.getApprovalTimeoutSeconds(), c.getTurnTimeoutSeconds(),
            c.getLlmTimeoutSeconds(), c.getReactMaxSteps(),
            c.getIdentity(), c.getInstructions(), c.getMemory(),
            c.getA2aTransport(), c.getTenants(),
            a2a,
            c.getCompactorConfig(),
            c.getTools()
        );
    }
}