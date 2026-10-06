package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.exception.LingsConfigException;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #044 — L2 slice test for {@link AgentFactory#loadYamlAndValidate}
 * parsing the new {@code agent.maxConcurrentTurns} / {@code agent.maxConcurrentQueueDepth}
 * top-level keys (dsh §10 NFR row 4 «最大并发 turn 数» + «排队 ≤ 32»).
 *
 * <p>Mirrors {@code AgentFactoryYamlPermissionPolicyIT} Story #029 precedent:
 * locks the YAML → {@link AgentConfig} contract so demo-product and demo-empty's
 * {@code application.yml} continue to bind correctly. Top-level camelCase
 * keys {@code maxConcurrentTurns} / {@code maxConcurrentQueueDepth} mirror
 * the {@code agent.reactMaxSteps} Story #008 precedent.
 */
class AgentFactoryYamlConcurrencyCapIT {

    private static Path writeTemp(String content) throws IOException {
        Path tmp = Files.createTempFile("agent-yml-concap-", ".yml");
        Files.write(tmp, content.getBytes("UTF-8"));
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** Minimal AgentFactory with 7 stub routers; loadYamlAndValidate only uses toAgentConfig + validate. */
    private static AgentFactory stubFactory() {
        Providers.LlmProviderProvider llm = new Providers.LlmProviderProvider() {
            @Override public String name() { return "anthropic"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
        Providers.ToolExecutorProvider tool = new Providers.ToolExecutorProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) { return null; }
        };
        Providers.PermissionPolicyProvider policy = new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return null; }
        };
        Providers.PromptBuilderProvider prompt = new Providers.PromptBuilderProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return null; }
        };
        Providers.FlowEngineProvider flow = new Providers.FlowEngineProvider() {
            @Override public String name() { return "linear"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
        Providers.MemorySourceProvider memory = new Providers.MemorySourceProvider() {
            @Override public String name() { return "project-claude-md"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public MemorySource create(AgentConfig cfg) { return null; }
        };
        Providers.RuntimeSandboxProvider sandbox = new Providers.RuntimeSandboxProvider() {
            @Override public String name() { return "chroot"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public RuntimeSandbox create(AgentConfig cfg) { return null; }
        };
        return new AgentFactory(
            new Routers.LlmProviderRouter(Collections.singletonList(llm)),
            new Routers.ToolExecutorRouter(Collections.singletonList(tool)),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policy)),
            new Routers.PromptBuilderRouter(Collections.singletonList(prompt)),
            new Routers.FlowEngineRouter(Collections.singletonList(flow)),
            new Routers.MemorySourceRouter(Collections.singletonList(memory)),
            new Routers.RuntimeSandboxRouter(Collections.singletonList(sandbox)));
    }

    @Test
    @DisplayName("AC-044-3: yamlDefault_parses16And32")
    void yamlDefault_parses16And32() throws IOException {
        // Empty yml — both fields absent, must fall back to defaults 16 + 32.
        Path yml = writeTemp(
            "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getMaxConcurrentTurns()).isEqualTo(16);
        assertThat(cfg.getMaxConcurrentQueueDepth()).isEqualTo(32);
    }

    @Test
    @DisplayName("AC-044-3: yamlCustomMaxTurns_parsesCustom")
    void yamlCustomMaxTurns_parsesCustom() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  maxConcurrentTurns: 64\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getMaxConcurrentTurns()).isEqualTo(64);
        // queue depth unchanged — falls back to default 32.
        assertThat(cfg.getMaxConcurrentQueueDepth()).isEqualTo(32);
    }

    @Test
    @DisplayName("AC-044-3: yamlCustomQueueDepth_parsesCustom")
    void yamlCustomQueueDepth_parsesCustom() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  maxConcurrentQueueDepth: 128\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getMaxConcurrentTurns()).isEqualTo(16);   // default unchanged
        assertThat(cfg.getMaxConcurrentQueueDepth()).isEqualTo(128);
    }

    @Test
    @DisplayName("AC-044-3: yamlBothCustom_parsesBoth_validatePasses")
    void yamlBothCustom_parsesBoth_validatePasses() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  maxConcurrentTurns: 8\n" +
            "  maxConcurrentQueueDepth: 16\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getMaxConcurrentTurns()).isEqualTo(8);
        assertThat(cfg.getMaxConcurrentQueueDepth()).isEqualTo(16);
        assertThatCode(cfg::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EC-044-4: yamlZeroMaxTurns_throwsC02OnValidate")
    void yamlZeroMaxTurns_throwsC02OnValidate() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  maxConcurrentTurns: 0\n");
        // loadYamlAndValidate does NOT call validate() — that's the caller's job.
        // So the parse succeeds but the resulting AgentConfig will fail validate().
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        assertThat(cfg.getMaxConcurrentTurns()).isEqualTo(0);
        assertThatThrownBy(cfg::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("maxConcurrentTurns");
    }
}
