package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.spi.ProviderInitException;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #003 — End-to-end Router wiring integration test
 * (US1 + US2 — quickstart.md Validation 2).
 *
 * <p>Constructs all 6 Routers + AgentFactory together with stub Providers, then
 * verifies {@code describe()} emits the documented format. The logback
 * {@code ListAppender} capture (originally specified in the quickstart) is
 * skipped because the test classpath has no logback-classic binding — the same
 * content is verifiable via {@code describe()} which is the single source of
 * truth for what the Router startup logs print.
 */
class AgentFactoryIntegrationTest {

    private static Providers.LlmProviderProvider anthropic() {
        return new Providers.LlmProviderProvider() {
            @Override public String name() { return "anthropic"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.ToolExecutorProvider defaultTool() {
        return new Providers.ToolExecutorProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.PermissionPolicyProvider allowAll() {
        return new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "allow-all"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.PromptBuilderProvider defaultPrompt() {
        return new Providers.PromptBuilderProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.FlowEngineProvider linear() {
        return new Providers.FlowEngineProvider() {
            @Override public String name() { return "linear"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.MemorySourceProvider mem(String n, int p) {
        return new Providers.MemorySourceProvider() {
            @Override public String name() { return n; }
            @Override public int priority() { return p; }
            @Override public String version() { return "1.0.0"; }
            @Override public MemorySource create(AgentConfig cfg) { return null; }
        };
    }

    @Test
    @DisplayName("startup_describeListsAll9ProvidersWithContractVersion")
    void startup_describeListsAll9ProvidersWithContractVersion() {
        // Construct all 6 Routers + AgentFactory — mirrors Spring boot sequence
        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(anthropic()));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(defaultTool()));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(allowAll()));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(defaultPrompt()));
        Routers.FlowEngineRouter flow = new Routers.FlowEngineRouter(
            Collections.singletonList(linear()));
        Routers.MemorySourceRouter memory = new Routers.MemorySourceRouter(Arrays.asList(
            mem("project-claude-md", 10),
            mem("user-claude-md", 20),
            mem("identity", 30),
            mem("project-tree", 40)));

        AgentFactory factory = new AgentFactory(llm, tool, policy, prompt, flow, memory);

        // Each Router's describe() = 1 entry for singletons, N for MemorySource
        assertThat(llm.describe()).containsExactly("  anthropic v1.0.0 (priority=10)");
        assertThat(tool.describe()).containsExactly("  default v1.0.0 (priority=0)");
        assertThat(policy.describe()).containsExactly("  allow-all v1.0.0 (priority=0)");
        assertThat(prompt.describe()).containsExactly("  default v1.0.0 (priority=0)");
        assertThat(flow.describe()).containsExactly("  linear v1.0.0 (priority=0)");
        assertThat(memory.describe()).containsExactly(
            "  project-claude-md v1.0.0 (priority=10)",
            "  user-claude-md v1.0.0 (priority=20)",
            "  identity v1.0.0 (priority=30)",
            "  project-tree v1.0.0 (priority=40)");

        // AgentFactory.description() should aggregate all 9 providers
        String desc = factory.description();
        // Count "<name> v1.0.0 (priority=N)" occurrences = 9 total
        long providerLines = Arrays.stream(desc.split("\n"))
            .filter(l -> l.matches(".* v1\\.0\\.0 \\(priority=\\d+\\)"))
            .count();
        assertThat(providerLines).isEqualTo(9);
    }

    @Test
    @DisplayName("startup_badVersionOnOneRouter_doesNotAffectOthers")
    void startup_badVersionOnOneRouter_doesNotAffectOthers() {
        // Bad LlmProvider version (major mismatch) — should throw at Router construction
        Providers.LlmProviderProvider badLlm = new Providers.LlmProviderProvider() {
            @Override public String name() { return "bad-llm"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "2.0.0"; } // slot is v1.0.0
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
        assertThatThrownBy(() -> new Routers.LlmProviderRouter(Collections.singletonList(badLlm)))
            .isInstanceOf(ProviderInitException.class)
            .hasMessageContaining("LINGS-S05")
            .hasMessageContaining("bad-llm")
            .hasMessageContaining("2.0.0");
        // Other Routers should still construct cleanly — failure is localized to the bad Router
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(defaultTool()));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(allowAll()));
        assertThat(tool.available()).containsExactly("default");
        assertThat(policy.available()).containsExactly("allow-all");
    }

    @Test
    @DisplayName("startup_memorySourceProvidersPreserveInputOrder")
    void startup_memorySourceProvidersPreserveInputOrder() {
        // describe() iterates the LinkedHashMap insertion order, NOT priority.
        // Priority only governs same-name conflicts (dsh §5.2); for distinct names,
        // input order is preserved end-to-end (consistent with yml prompt.memorySources).
        Routers.MemorySourceRouter memory = new Routers.MemorySourceRouter(Arrays.asList(
            mem("identity", 30),       // input first
            mem("project-tree", 40),   // input second
            mem("project-claude-md", 10), // input third
            mem("user-claude-md", 20)));
        List<String> lines = memory.describe();
        assertThat(lines).containsExactly(
            "  identity v1.0.0 (priority=30)",
            "  project-tree v1.0.0 (priority=40)",
            "  project-claude-md v1.0.0 (priority=10)",
            "  user-claude-md v1.0.0 (priority=20)");
    }

    @Test
    @DisplayName("startup_allRoutersRegistered_countMatchesDefaultProviderCount")
    void startup_allRoutersRegistered_countMatchesDefaultProviderCount() {
        // 1 + 1 + 1 + 1 + 1 + 4 = 9 default Providers across 6 Routers.
        // Unique names = 8 (ToolExecutor + PromptBuilder share name "default").
        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(anthropic()));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(defaultTool()));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(allowAll()));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(defaultPrompt()));
        Routers.FlowEngineRouter flow = new Routers.FlowEngineRouter(
            Collections.singletonList(linear()));
        Routers.MemorySourceRouter memory = new Routers.MemorySourceRouter(Arrays.asList(
            mem("project-claude-md", 10),
            mem("user-claude-md", 20),
            mem("identity", 30),
            mem("project-tree", 40)));

        // Total registered providers across all 6 Routers = 9
        int totalProviders = llm.available().size()
            + tool.available().size()
            + policy.available().size()
            + prompt.available().size()
            + flow.available().size()
            + memory.available().size();
        assertThat(totalProviders).isEqualTo(9);

        // Unique names = 8 (default is shared by tool + prompt)
        Set<String> allNames = new HashSet<>();
        allNames.addAll(llm.available());
        allNames.addAll(tool.available());
        allNames.addAll(policy.available());
        allNames.addAll(prompt.available());
        allNames.addAll(flow.available());
        allNames.addAll(memory.available());
        assertThat(allNames).hasSize(8);
        assertThat(allNames).containsExactlyInAnyOrder(
            "anthropic", "default", "allow-all", "linear",
            "project-claude-md", "user-claude-md", "identity", "project-tree");
    }
}
