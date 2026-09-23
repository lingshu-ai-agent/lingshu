package ai.lingshu.core.impl.router;

import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.spi.ProviderInitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 unit tests — {@link A2aTransportRouter} (Contract A2, 4 cases).
 */
class A2aTransportRouterTest {

    /** Test-only Provider that creates a StubA2aTransport. */
    static class FakeProvider implements Providers.A2aTransportProvider {
        private final String name;
        private final int priority;
        private final String version;
        public FakeProvider(String name, int priority, String version) {
            this.name = name; this.priority = priority; this.version = version;
        }
        @Override public String name() { return name; }
        @Override public int priority() { return priority; }
        @Override public String version() { return version; }
        @Override public A2aTransport create(AgentConfig cfg) {
            return new StubA2aTransport(name);
        }
    }

    /** Minimal A2aTransport stub — only fetchCard is exercised. */
    static class StubA2aTransport implements A2aTransport {
        private final String providerName;
        StubA2aTransport(String providerName) { this.providerName = providerName; }
        @Override public Map<String, Object> fetchCard(String agentName) {
            return Collections.singletonMap("provider", providerName);
        }
        @Override public ToolResult submit(String agentName, String skill, String inputJson) {
            return ToolResult.builder().status(ToolResult.Status.SUCCESS).content("{}").isError(false).build();
        }
        @Override public ToolResult get(String taskId) {
            return ToolResult.builder().status(ToolResult.Status.SUCCESS).content("{}").isError(false).build();
        }
        @Override public boolean cancel(String taskId) { return true; }
        @Override public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent) { /* no-op */ }
    }

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
            AgentConfig.ToolsConfig.defaults()  // tools (Story #019)
        );
    }

    private static AgentConfig withA2aTransport(AgentConfig cfg, String name) {
        return new AgentConfig(
            cfg.getFlowEngine(), cfg.getLlm(), cfg.getPrompt(), cfg.getToolExecutor(),
            cfg.getSandbox(), cfg.getCompactor(), cfg.getSessionStore(),
            cfg.getDelegate(), cfg.getMcp(), cfg.getSkills(),
            cfg.getToolParallelism(), cfg.getToolTimeoutSeconds(),
            cfg.getApprovalTimeoutSeconds(), cfg.getTurnTimeoutSeconds(),
            cfg.getLlmTimeoutSeconds(), cfg.getReactMaxSteps(),
            cfg.getIdentity(), cfg.getInstructions(), cfg.getMemory(),
            name,                          // new a2aTransport
            cfg.getTenants(), cfg.getA2a(), cfg.getCompactorConfig(),
            cfg.getTools()                   // tools (Story #019)
        );
    }

    @Test
    @DisplayName("TC-RTR-1: singleProvider_resolvesCorrectly")
    void singleProvider_resolvesCorrectly() {
        FakeProvider grpc = new FakeProvider("grpc-1.0.0", 10, "1.0.0");
        A2aTransportRouter router = new A2aTransportRouter(Collections.singletonList(grpc));
        AgentConfig c = withA2aTransport(cfg(), "grpc-1.0.0");

        A2aTransport t = router.resolve("grpc-1.0.0", c);
        assertThat(t).isNotNull();
        assertThat(router.available()).contains("grpc-1.0.0");
    }

    @Test
    @DisplayName("TC-RTR-2: multipleProviders_resolvesByName")
    void multipleProviders_resolvesByName() {
        FakeProvider grpc = new FakeProvider("grpc-1.0.0", 10, "1.0.0");
        FakeProvider http = new FakeProvider("http-jsonrpc-1.0.0", 5, "1.0.0");
        A2aTransportRouter router = new A2aTransportRouter(java.util.Arrays.asList(grpc, http));
        AgentConfig c = withA2aTransport(cfg(), "http-jsonrpc-1.0.0");

        A2aTransport t = router.resolve("http-jsonrpc-1.0.0", c);
        assertThat(t).isNotNull();
        assertThat(t.fetchCard("x")).containsEntry("provider", "http-jsonrpc-1.0.0");
    }

    @Test
    @DisplayName("TC-RTR-3: multipleProviders_describeListsAll")
    void multipleProviders_describeListsAll() {
        FakeProvider grpc = new FakeProvider("grpc-1.0.0", 10, "1.0.0");
        FakeProvider http = new FakeProvider("http-jsonrpc-1.0.0", 5, "1.0.0");
        A2aTransportRouter router = new A2aTransportRouter(java.util.Arrays.asList(grpc, http));
        assertThat(router.available()).contains("grpc-1.0.0", "http-jsonrpc-1.0.0");
        assertThat(router.describe().toString()).contains("grpc-1.0.0", "http-jsonrpc-1.0.0");
    }

    @Test
    @DisplayName("TC-RTR-4: unknownName_throwsIllegalArgumentException (LINGS-S01)")
    void unknownName_throwsIllegalArgumentException() {
        FakeProvider grpc = new FakeProvider("grpc-1.0.0", 10, "1.0.0");
        A2aTransportRouter router = new A2aTransportRouter(Collections.singletonList(grpc));
        AgentConfig c = withA2aTransport(cfg(), "does-not-exist");

        assertThatThrownBy(() -> router.resolve("does-not-exist", c))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-RTR-5: versionMismatch_throwsProviderInitException (LINGS-S05)")
    void versionMismatch_throwsProviderInitException() {
        FakeProvider bad = new FakeProvider("grpc-2.0.0", 10, "2.5.0");
        // SlotRouter validates version at construction (fail-fast).
        assertThatThrownBy(() -> new A2aTransportRouter(Collections.singletonList(bad)))
            .isInstanceOf(ProviderInitException.class)
            .hasMessageContaining("LINGS-S05");
    }
}
