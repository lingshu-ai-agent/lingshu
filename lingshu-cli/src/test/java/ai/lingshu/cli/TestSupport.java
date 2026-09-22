package ai.lingshu.cli;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;

import java.util.Arrays;
import java.util.Collections;

/**
 * Test helpers for CLI Story #017. Builds a real {@link AgentFactory} wired to stub
 * Providers (no Spring, no Mockito).
 *
 * <p>The Provider names match {@code AgentConfigDefaults.defaults()}: {@code "default"}
 * for most slots and {@code "linear"} for FlowEngine. This lets tests call
 * {@code factory.create(AgentConfigDefaults.defaults())} without hitting
 * {@code "Unknown ToolExecutorRouter 'default'. Available: [...]"}.
 *
 * <p>Why no Mockito: Mockito 5's inline mock-maker fails on JDK 23 because it can't
 * rewrite {@code java.lang.Object} without specific JVM flags that vary by build.
 * Using a real factory sidesteps this entirely. Same pattern as
 * {@code AgentFactoryDescriptionTest} in lingshu-core.
 */
final class TestSupport {

    private TestSupport() {}

    /** Build an AgentFactory with stub Providers matching AgentConfigDefaults defaults. */
    static AgentFactory buildFactory() {
        Providers.LlmProviderProvider llm = stubLlm();
        Providers.ToolExecutorProvider tool = stubToolExec();
        Providers.PermissionPolicyProvider policy = stubPolicy();
        Providers.PromptBuilderProvider prompt = stubPrompt();
        Providers.FlowEngineProvider flow = stubFlow();
        Providers.MemorySourceProvider mem = stubMemory();

        return new AgentFactory(
            new Routers.LlmProviderRouter(Collections.singletonList(llm)),
            new Routers.ToolExecutorRouter(Collections.singletonList(tool)),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policy)),
            new Routers.PromptBuilderRouter(Collections.singletonList(prompt)),
            new Routers.FlowEngineRouter(Collections.singletonList(flow)),
            new Routers.MemorySourceRouter(Arrays.asList(mem)));
    }

    // ── Stubs — names match AgentConfigDefaults (default / anthropic / linear) ──

    private static Providers.LlmProviderProvider stubLlm() {
        return new Providers.LlmProviderProvider() {
            @Override public String name() { return "anthropic"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.ToolExecutorProvider stubToolExec() {
        return new Providers.ToolExecutorProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.PermissionPolicyProvider stubPolicy() {
        return new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.PromptBuilderProvider stubPrompt() {
        return new Providers.PromptBuilderProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return null; }
        };
    }

    private static Providers.FlowEngineProvider stubFlow() {
        return new Providers.FlowEngineProvider() {
            @Override public String name() { return "linear"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) {
                // Deterministic engine — emits no events, so runBlocking returns
                // an empty RunResult (turns=0, END_TURN, Usage.zero()).
                return new FlowEngine() {
                    @Override
                    public void runTurn(TurnContext ctx,
                                        org.reactivestreams.Subscriber<? super ai.lingshu.core.event.AgentEvent> sink) {
                        sink.onComplete();
                    }
                };
            }
        };
    }

    private static Providers.MemorySourceProvider stubMemory() {
        return new Providers.MemorySourceProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public MemorySource create(AgentConfig cfg) { return null; }
        };
    }
}
