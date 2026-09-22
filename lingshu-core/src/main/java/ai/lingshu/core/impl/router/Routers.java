package ai.lingshu.core.impl.router;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.spi.SlotRouter;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
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
        @Override protected Class<LlmProvider> getSlotInterface() { return LlmProvider.class; }
    }

    @Component
    public static class ToolExecutorRouter
            extends SlotRouter<Providers.ToolExecutorProvider, ToolExecutor> {
        public ToolExecutorRouter(List<Providers.ToolExecutorProvider> providers) {
            super(providers, "ToolExecutor", LoggerFactory.getLogger(ToolExecutorRouter.class));
        }
        @Override protected Class<ToolExecutor> getSlotInterface() { return ToolExecutor.class; }
    }

    @Component
    public static class PermissionPolicyRouter
            extends SlotRouter<Providers.PermissionPolicyProvider, PermissionPolicy> {
        public PermissionPolicyRouter(List<Providers.PermissionPolicyProvider> providers) {
            super(providers, "PermissionPolicy", LoggerFactory.getLogger(PermissionPolicyRouter.class));
        }
        @Override protected Class<PermissionPolicy> getSlotInterface() { return PermissionPolicy.class; }
    }

    @Component
    public static class PromptBuilderRouter
            extends SlotRouter<Providers.PromptBuilderProvider, PromptBuilder> {
        public PromptBuilderRouter(List<Providers.PromptBuilderProvider> providers) {
            super(providers, "PromptBuilder", LoggerFactory.getLogger(PromptBuilderRouter.class));
        }
        @Override protected Class<PromptBuilder> getSlotInterface() { return PromptBuilder.class; }
    }

    /**
     * CompactorRouter — Story #018 (dsh §5.3.1.0 + §6.2). Resolves one Compactor
     * by name from the {@code agent.compactor.name} yaml key. Default implementation:
     * {@code TruncatingCompactorProvider} ({@code name="truncating"}).
     *
     * <p>NOT yet wired into {@code AgentFactory} (separate concern for the follow-up
     * Story that integrates compaction into {@code LinearTurnEngine}); tests resolve
     * the router directly.
     */
    @Component
    public static class CompactorRouter
            extends SlotRouter<Providers.CompactorProvider, Compactor> {
        public CompactorRouter(List<Providers.CompactorProvider> providers) {
            super(providers, "Compactor", LoggerFactory.getLogger(CompactorRouter.class));
        }
        @Override protected Class<Compactor> getSlotInterface() { return Compactor.class; }
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
        @Override protected Class<FlowEngine> getSlotInterface() { return FlowEngine.class; }
    }

    /**
     * MemorySourceRouter — Story #002. Resolves one or more MemorySource instances
     * by name. The standard {@link #resolve(String, AgentConfig)} handles a single
     * source; {@link #resolveAll(List, AgentConfig)} handles the yml
     * {@code agent.prompt.memory-sources} list, preserving input order (NOT priority
     * sort order — priority is for same-name conflict resolution only).
     *
     * <p>Wired into {@code DefaultPromptBuilderProvider}, not into {@code AgentFactory}
     * (see plan.md D-06: MemorySource is only consumed by PromptBuilder).
     */
    @Component
    public static class MemorySourceRouter
            extends SlotRouter<Providers.MemorySourceProvider, MemorySource> {
        public MemorySourceRouter(List<Providers.MemorySourceProvider> providers) {
            super(providers, "MemorySource", LoggerFactory.getLogger(MemorySourceRouter.class));
        }
        @Override protected Class<MemorySource> getSlotInterface() { return MemorySource.class; }

        /**
         * Resolve multiple sources by name, in the input order (NOT priority-sorted).
         *
         * @param names ordered list of MemorySource provider names (typically from
         *               {@code cfg.prompt.memorySources}); null or empty → empty list
         * @param cfg   the immutable AgentConfig
         * @return ordered list of MemorySource instances matching {@code names}
         * @throws IllegalArgumentException if any name in {@code names} is unknown —
         *         surfaces as {@code LINGS-S01} in the boot logs
         */
        public List<MemorySource> resolveAll(List<String> names, AgentConfig cfg) {
            if (names == null || names.isEmpty()) {
                return Collections.emptyList();
            }
            List<MemorySource> result = new ArrayList<>(names.size());
            for (String name : names) {
                result.add(resolve(name, cfg));
            }
            return result;
        }
    }
}