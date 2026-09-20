package ai.lingshu.core.impl.router;

import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.spi.SlotRouter;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Concrete Routers for the 4 Slots that Story #001 actually exercises.
 * One file per Router is overkill at this scope; consolidating them keeps related
 * startup logs together. Story #002/#003/#014/#015 add their own as needed.
 *
 * <p>Each Router:
 * <ul>
 *   <li>Extends {@code SlotRouter<P, T>} with the typed pair</li>
 *   <li>Receives Spring's auto-injected {@code List<P>} (all Providers of that type)</li>
 *   <li>Logs the resolved names + any priority conflicts at startup</li>
 * </ul>
 */
public final class Routers {

    private Routers() {}

    @Component
    public static class LlmProviderRouter
            extends SlotRouter<Providers.LlmProviderProvider, LlmProvider> {
        public LlmProviderRouter(List<Providers.LlmProviderProvider> providers) {
            super(providers, "LlmProvider", LoggerFactory.getLogger(LlmProviderRouter.class));
        }
    }

    @Component
    public static class ToolExecutorRouter
            extends SlotRouter<Providers.ToolExecutorProvider, ToolExecutor> {
        public ToolExecutorRouter(List<Providers.ToolExecutorProvider> providers) {
            super(providers, "ToolExecutor", LoggerFactory.getLogger(ToolExecutorRouter.class));
        }
    }

    @Component
    public static class PermissionPolicyRouter
            extends SlotRouter<Providers.PermissionPolicyProvider, PermissionPolicy> {
        public PermissionPolicyRouter(List<Providers.PermissionPolicyProvider> providers) {
            super(providers, "PermissionPolicy", LoggerFactory.getLogger(PermissionPolicyRouter.class));
        }
    }

    @Component
    public static class PromptBuilderRouter
            extends SlotRouter<Providers.PromptBuilderProvider, PromptBuilder> {
        public PromptBuilderRouter(List<Providers.PromptBuilderProvider> providers) {
            super(providers, "PromptBuilder", LoggerFactory.getLogger(PromptBuilderRouter.class));
        }
    }

    /**
     * FlowEngineRouter — by design NOT in {@code SlotResolver}; {@code AgentFactory}
     * autowires it directly (dsh §5.3.1.0 "7 个隐式 Router concrete 类").
     */
    @Component
    public static class FlowEngineRouter
            extends SlotRouter<Providers.FlowEngineProvider, FlowEngine> {
        public FlowEngineRouter(List<Providers.FlowEngineProvider> providers) {
            super(providers, "FlowEngine", LoggerFactory.getLogger(FlowEngineRouter.class));
        }
    }
}