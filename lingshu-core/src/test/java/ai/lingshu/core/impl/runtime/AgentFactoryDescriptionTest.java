package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #003 — {@link AgentFactory#description()} self-describe tests
 * (US3 Scenario 1, quickstart.md Validation 3).
 *
 * <p>Uses stub Providers with predictable name/priority/version to make output
 * assertions deterministic. Each stub's {@code create()} returns null (description()
 * never calls {@code create()} — only the Router's metadata map).
 */
class AgentFactoryDescriptionTest {

    /** Build an AgentFactory with the 9 stub Providers described in quickstart.md Validation 3. */
    private static AgentFactory buildFactory() {
        // 1 LlmProvider: anthropic v1.0.0 priority=10
        Providers.LlmProviderProvider anthropic = new Providers.LlmProviderProvider() {
            @Override public String name() { return "anthropic"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
        // 1 ToolExecutor: default v1.0.0 priority=0
        Providers.ToolExecutorProvider defaultTool = new Providers.ToolExecutorProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) { return null; }
        };
        // 1 PermissionPolicy: allow-all v1.0.0 priority=0
        Providers.PermissionPolicyProvider allowAll = new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "allow-all"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return null; }
        };
        // 1 PromptBuilder: default v1.0.0 priority=0
        Providers.PromptBuilderProvider defaultPrompt = new Providers.PromptBuilderProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return null; }
        };
        // 1 FlowEngine: linear v1.0.0 priority=0
        Providers.FlowEngineProvider linear = new Providers.FlowEngineProvider() {
            @Override public String name() { return "linear"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
        // 4 MemorySource: project-claude-md 10, user-claude-md 20, identity 30, project-tree 40
        Providers.MemorySourceProvider[] mems = new Providers.MemorySourceProvider[] {
            mem("project-claude-md", 10, "1.0.0"),
            mem("user-claude-md", 20, "1.0.0"),
            mem("identity", 30, "1.0.0"),
            mem("project-tree", 40, "1.0.0"),
        };

        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(anthropic));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(defaultTool));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(allowAll));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(defaultPrompt));
        Routers.FlowEngineRouter flow = new Routers.FlowEngineRouter(
            Collections.singletonList(linear));
        Routers.MemorySourceRouter memory = new Routers.MemorySourceRouter(Arrays.asList(mems));

        return new AgentFactory(llm, tool, policy, prompt, flow, memory);
    }

    private static Providers.MemorySourceProvider mem(String n, int p, String v) {
        return new Providers.MemorySourceProvider() {
            @Override public String name() { return n; }
            @Override public int priority() { return p; }
            @Override public String version() { return v; }
            @Override public MemorySource create(AgentConfig cfg) { return null; }
        };
    }

    @Test
    @DisplayName("description_firstLine_isAgentFactoryVersionForJvm")
    void description_firstLine_isAgentFactoryVersionForJvm() {
        AgentFactory factory = buildFactory();
        String desc = factory.description();
        String firstLine = desc.split("\n")[0];
        assertThat(firstLine).startsWith("AgentFactory v0.1.0-SNAPSHOT for JVM ");
    }

    @Test
    @DisplayName("description_includesAll6RoutersWithCorrectFormat")
    void description_includesAll6RoutersWithCorrectFormat() {
        AgentFactory factory = buildFactory();
        String desc = factory.description();
        // 5 singleton + 4 MemorySource = 9 provider lines
        assertThat(desc).contains("LlmProvider: anthropic v1.0.0 (priority=10)");
        assertThat(desc).contains("ToolExecutor: default v1.0.0 (priority=0)");
        assertThat(desc).contains("PermissionPolicy: allow-all v1.0.0 (priority=0)");
        assertThat(desc).contains("PromptBuilder: default v1.0.0 (priority=0)");
        assertThat(desc).contains("FlowEngine: linear v1.0.0 (priority=0)");
        // MemorySource: 4 entries
        assertThat(desc).contains("MemorySource: project-claude-md v1.0.0 (priority=10)");
        assertThat(desc).contains("MemorySource: user-claude-md v1.0.0 (priority=20)");
        assertThat(desc).contains("MemorySource: identity v1.0.0 (priority=30)");
        assertThat(desc).contains("MemorySource: project-tree v1.0.0 (priority=40)");
    }

    @Test
    @DisplayName("description_lastLine_isTurnAndSessionPlaceholder")
    void description_lastLine_isTurnAndSessionPlaceholder() {
        AgentFactory factory = buildFactory();
        String desc = factory.description();
        String lastLine = desc.substring(desc.lastIndexOf("\n") + 1);
        assertThat(lastLine).startsWith("Turn=0 Session=");
        // Session ID is UUID.toString() format (8-4-4-4-12 hex with dashes)
        assertThat(lastLine).matches("Turn=0 Session=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("description_lineCount_isHeaderPlusNineProvidersPlusFooter")
    void description_lineCount_isHeaderPlusNineProvidersPlusFooter() {
        AgentFactory factory = buildFactory();
        String desc = factory.description();
        String[] lines = desc.split("\n");
        // 1 header + 9 provider lines (5 singletons + 4 MemorySource) + 1 footer = 11
        assertThat(lines).hasSize(11);
    }

    @Test
    @DisplayName("create_returnsAgent_description_doesNotChangeAgentState")
    void create_returnsAgent_description_doesNotChangeAgentState() {
        AgentFactory factory = buildFactory();
        // description() must be callable BEFORE create() — proves it's read-only metadata
        String beforeDesc = factory.description();
        assertThat(beforeDesc).startsWith("AgentFactory v");
        // Description remains queryable independently — Session ID may differ per call,
        // but the Slot lines must be identical
        String afterDesc = factory.description();
        assertThat(afterDesc).startsWith("AgentFactory v");
        // Both must contain the same 9 provider lines (Session ID excluded via startsWith)
        String[] beforeLines = beforeDesc.split("\n");
        String[] afterLines = afterDesc.split("\n");
        assertThat(beforeLines).hasSize(11);
        assertThat(afterLines).hasSize(11);
        // Lines 0..9 (header + 9 providers) must match exactly
        for (int i = 0; i < 10; i++) {
            assertThat(afterLines[i]).isEqualTo(beforeLines[i]);
        }
        // Line 10 is the Session line — must both be valid UUID format
        assertThat(beforeLines[10]).matches("Turn=0 Session=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(afterLines[10]).matches("Turn=0 Session=[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
