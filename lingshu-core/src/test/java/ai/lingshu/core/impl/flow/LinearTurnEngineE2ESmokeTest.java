package ai.lingshu.core.impl.flow;

import ai.lingshu.core.impl.config.ToolExecutorConfig;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicyProvider;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolExecutorProvider;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #004 — E2E smoke test for the tool-parallel-dispatch Spring wiring.
 *
 * <p>Boots an {@link AnnotationConfigApplicationContext} with:
 * <ul>
 *   <li>{@link ToolExecutorConfig} — produces the {@code agentToolPool} Bean</li>
 *   <li>{@link DefaultToolExecutorProvider} — wires Slot 2 outer half</li>
 *   <li>{@link AllowAllPermissionPolicyProvider} — Slot 3 stub</li>
 *   <li>{@link LinearTurnEngineProvider} — Slot 8 default</li>
 * </ul>
 * then verifies the resulting {@link LinearTurnEngine} was constructed with a
 * non-null {@code agentToolPool} thread pool — the central invariant for AC-03.
 *
 * <p>Does NOT execute a full turn — that is covered by {@link LinearTurnEngineParallelDispatchTest}.
 *
 * <p>Uses raw {@link AnnotationConfigApplicationContext} instead of {@code @SpringJUnitConfig}
 * because spring-test is not on the classpath (we keep the dep tree minimal per R-13).
 */
class LinearTurnEngineE2ESmokeTest {

    private static AnnotationConfigApplicationContext context;

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    private static synchronized AnnotationConfigApplicationContext bootContext() {
        if (context == null) {
            context = new AnnotationConfigApplicationContext(TestConfig.class);
        }
        return context;
    }

    @Test
    @DisplayName("E2E-001: springContext_wiresAgentToolPoolAndLinearTurnEngine")
    void springContext_wiresAgentToolPoolAndLinearTurnEngine() {
        AnnotationConfigApplicationContext ctx = bootContext();

        // The shared pool bean is alive and a real ThreadPoolExecutor (not a same-thread
        // Executors.newSingleThreadExecutor etc.)
        ExecutorService pool = ctx.getBean("agentToolPool", ExecutorService.class);
        assertThat(pool).isNotNull();
        assertThat(pool).isInstanceOf(ThreadPoolExecutor.class);

        ThreadPoolExecutor tpe = (ThreadPoolExecutor) pool;
        // D-01 sizing: corePoolSize = availableProcessors() * 2
        assertThat(tpe.getCorePoolSize())
            .isEqualTo(Runtime.getRuntime().availableProcessors() * 2);
        assertThat(tpe.getMaximumPoolSize())
            .isEqualTo(tpe.getCorePoolSize() * 2);

        // Provider is registered with the documented name + priority + version
        LinearTurnEngineProvider linearProvider = ctx.getBean(LinearTurnEngineProvider.class);
        assertThat(linearProvider.name()).isEqualTo("linear");
        assertThat(linearProvider.priority()).isEqualTo(0);
        assertThat(linearProvider.version()).isEqualTo("1.0.0");

        // DefaultToolExecutorProvider resolves to a DefaultToolExecutor with the policy wired
        DefaultToolExecutorProvider toolProvider = ctx.getBean(DefaultToolExecutorProvider.class);
        DefaultToolExecutor toolExec = (DefaultToolExecutor)
            toolProvider.create(testConfig());
        assertThat(toolExec).isNotNull();

        // Engine instance can be created end-to-end via the Provider
        FlowEngine engine = linearProvider.create(testConfig());
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);
    }

    private AgentConfig testConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
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
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
            AgentConfig.ToolsConfig.defaults());     // tools (Story #019)
    }

    /**
     * Minimal Spring config that activates the 3 Beans LinearTurnEngineProvider needs.
     *
     * <p>This intentionally does <b>not</b> scan the whole package — it pulls in only
     * the 3 Providers + 1 Config the E2E path depends on. Default LlmProvider / PromptBuilder
     * Providers are not loaded here because the Provider constructor only invokes Router
     * resolution at {@code create(config)} time, not at startup.
     */
    @Configuration
    @Import({
        ToolExecutorConfig.class,
        DefaultToolExecutorProvider.class,
        AllowAllPermissionPolicyProvider.class,
        LinearTurnEngineProvider.class
    })
    static class TestConfig {

        /** Stub router so DefaultToolExecutorProvider can resolve sandbox.policy="allow-all". */
        @Bean
        ai.lingshu.core.impl.router.Routers.PermissionPolicyRouter permissionPolicyRouter() {
            return new ai.lingshu.core.impl.router.Routers.PermissionPolicyRouter(
                Collections.<ai.lingshu.core.spi.Providers.PermissionPolicyProvider>singletonList(
                    new ai.lingshu.core.spi.Providers.PermissionPolicyProvider() {
                        @Override public String name() { return "allow-all"; }
                        @Override public int priority() { return 0; }
                        @Override public String version() { return "1.0.0"; }
                        @Override public ai.lingshu.core.slot.PermissionPolicy create(
                                AgentConfig cfg) {
                            return new ai.lingshu.core.impl.permission.AllowAllPermissionPolicy();
                        }
                    }));
        }

        @Bean
        ai.lingshu.core.impl.router.Routers.PromptBuilderRouter promptBuilderRouter() {
            return new ai.lingshu.core.impl.router.Routers.PromptBuilderRouter(
                Collections.<ai.lingshu.core.spi.Providers.PromptBuilderProvider>singletonList(
                    new ai.lingshu.core.spi.Providers.PromptBuilderProvider() {
                        @Override public String name() { return "default"; }
                        @Override public int priority() { return 0; }
                        @Override public String version() { return "1.0.0"; }
                        @Override public ai.lingshu.core.slot.PromptBuilder create(
                                AgentConfig cfg) {
                            return new ai.lingshu.core.impl.flow.support.RecordingPromptBuilder();
                        }
                    }));
        }

        @Bean
        ai.lingshu.core.impl.router.Routers.LlmProviderRouter llmProviderRouter() {
            return new ai.lingshu.core.impl.router.Routers.LlmProviderRouter(
                Collections.<ai.lingshu.core.spi.Providers.LlmProviderProvider>singletonList(
                    new ai.lingshu.core.spi.Providers.LlmProviderProvider() {
                        @Override public String name() { return "anthropic"; }
                        @Override public int priority() { return 10; }
                        @Override public String version() { return "1.0.0"; }
                        @Override public ai.lingshu.core.slot.LlmProvider create(
                                AgentConfig cfg) {
                            return new ai.lingshu.core.impl.flow.support.EchoLlmProvider(
                                Collections.singletonList(
                                    new ai.lingshu.core.message.LlmResponse(
                                        "", Collections.emptyList(),
                                        ai.lingshu.core.message.StopReason.END_TURN,
                                        ai.lingshu.core.message.Usage.zero())));
                        }
                    }));
        }

        @Bean
        ai.lingshu.core.impl.router.Routers.ToolExecutorRouter toolExecutorRouter(
                DefaultToolExecutorProvider defaultToolExecutorProvider) {
            return new ai.lingshu.core.impl.router.Routers.ToolExecutorRouter(
                Collections.<ai.lingshu.core.spi.Providers.ToolExecutorProvider>singletonList(
                    defaultToolExecutorProvider));
        }
    }
}