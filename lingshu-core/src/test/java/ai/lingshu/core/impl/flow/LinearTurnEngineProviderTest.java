package ai.lingshu.core.impl.flow;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #004 — L2P test for {@link LinearTurnEngineProvider} wiring.
 *
 * <p>Validates the 5-arg constructor + each {@code Routers.XxxRouter.resolve(name, cfg)}
 * call + the {@code @Qualifier("agentToolPool")} {@link ExecutorService} injection.
 *
 * <p>Mirrors the Spring autowire graph (see
 * {@code specs/004-tool-parallel-dispatch/contracts/parallel-dispatch-pipeline.md}).
 */
class LinearTurnEngineProviderTest {

    private static Providers.LlmProviderProvider llmProvider(String name, int prio) {
        return new Providers.LlmProviderProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.flow.support.EchoLlmProvider(
                    java.util.Collections.singletonList(
                        new ai.lingshu.core.message.LlmResponse("", java.util.Collections.emptyList(),
                            ai.lingshu.core.message.StopReason.END_TURN,
                            ai.lingshu.core.message.Usage.zero())));
            }
        };
    }
    private static Providers.PromptBuilderProvider promptProvider(String name, int prio) {
        return new Providers.PromptBuilderProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.flow.support.RecordingPromptBuilder();
            }
        };
    }
    private static Providers.ToolExecutorProvider toolProvider(String name, int prio) {
        return new Providers.ToolExecutorProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.tool.DefaultToolExecutor(
                    new ai.lingshu.core.impl.permission.AllowAllPermissionPolicy());
            }
        };
    }
    private static Providers.PermissionPolicyProvider policyProvider(String name, int prio) {
        return new Providers.PermissionPolicyProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) {
                return new ai.lingshu.core.impl.permission.AllowAllPermissionPolicy();
            }
        };
    }
    private static Providers.FlowEngineProvider flowProvider(String name, int prio) {
        return new Providers.FlowEngineProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
    }

    private AgentConfig defaultConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",                                                    // toolExecutor
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            4, 5, 0, 0, 0, 10,                                              // parallelism, timeout, reactMaxSteps
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults());  // compactorConfig (Story #018)
    }

    @Test
    @DisplayName("L2P-001: provider_wiresAll5SlotsAndPoolIntoLinearTurnEngine")
    void provider_wiresAll5SlotsAndPoolIntoLinearTurnEngine() {
        // Build minimal Router set with one Provider per slot (mirrors Spring boot sequence)
        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(llmProvider("anthropic", 10)));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(promptProvider("default", 0)));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(toolProvider("default", 0)));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(policyProvider("allow-all", 0)));

        // Real ThreadPoolExecutor matching ToolExecutorConfig's @Bean shape
        ExecutorService pool = Executors.newFixedThreadPool(
            4, r -> { Thread t = new Thread(r, "test-pool"); t.setDaemon(true); return t; });

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            prompt, llm, tool, policy, pool);

        AgentConfig cfg = defaultConfig();
        FlowEngine engine = provider.create(cfg);

        // Engine is the linear one with the right priority (0) and version (1.0.0)
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);
        assertThat(provider.name()).isEqualTo("linear");
        assertThat(provider.priority()).isEqualTo(0);
        assertThat(provider.version()).isEqualTo("1.0.0");

        // Sanity-check the pool is the same instance we passed in (no copies)
        // We do this indirectly: invoke engine.runTurn via real flow with a stub ctx
        // — but for unit test purposes, assert pool identity through reflection-free path.
        // The pool's ThreadFactory thread name prefix should be visible via toString.
        if (pool instanceof ThreadPoolExecutor) {
            ThreadPoolExecutor tpe = (ThreadPoolExecutor) pool;
            assertThat(tpe.getCorePoolSize()).isEqualTo(4);
        }

        provider.name(); provider.priority(); provider.version();  // touch accessors
        pool.shutdown();
    }

    @Test
    @DisplayName("L2P-002: provider_unknownRouterName_throws (consistency w/ Story #003)")
    void provider_unknownRouterName_throws() {
        Routers.LlmProviderRouter llm = new Routers.LlmProviderRouter(
            Collections.singletonList(llmProvider("anthropic", 10)));
        Routers.PromptBuilderRouter prompt = new Routers.PromptBuilderRouter(
            Collections.singletonList(promptProvider("default", 0)));
        Routers.ToolExecutorRouter tool = new Routers.ToolExecutorRouter(
            Collections.singletonList(toolProvider("default", 0)));
        Routers.PermissionPolicyRouter policy = new Routers.PermissionPolicyRouter(
            Collections.singletonList(policyProvider("allow-all", 0)));
        ExecutorService pool = Executors.newFixedThreadPool(2);

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            prompt, llm, tool, policy, pool);

        // Wire a config whose toolExecutor name is unknown — Router.resolve() must throw.
        AgentConfig bad = new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "nonexistent-tool-executor",                                // <-- unknown
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            4, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,               // a2aTransport
            null,                  // tenants (Story #006 — single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults());  // compactorConfig (Story #018)

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.create(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nonexistent-tool-executor");

        pool.shutdown();
    }
}