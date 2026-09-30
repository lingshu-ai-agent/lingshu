package ai.lingshu.core.spi;

import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.runtime.FlowEngine;

/**
 * Typed Provider marker interfaces — one per Slot (dsh §5.1).
 *
 * <p>Each interface just narrows the generic parameter so Spring can inject
 * {@code List<XxxProvider>} without wildcard gymnastics at the Router layer.
 */
public final class Providers {

    private Providers() {}

    public interface LlmProviderProvider extends SlotProvider<LlmProvider> {}

    public interface ToolExecutorProvider extends SlotProvider<ToolExecutor> {}

    public interface PermissionPolicyProvider extends SlotProvider<PermissionPolicy> {}

    public interface SessionStoreProvider extends SlotProvider<SessionStore> {}

    public interface CompactorProvider extends SlotProvider<Compactor> {}

    public interface PromptBuilderProvider extends SlotProvider<PromptBuilder> {}

    public interface MemorySourceProvider extends SlotProvider<MemorySource> {}

    public interface FlowEngineProvider extends SlotProvider<FlowEngine> {}

    public interface A2aTransportProvider extends SlotProvider<A2aTransport> {}

    /**
     * 🆕 Story #028 — Slot 3 Sandbox typed Provider. Resolves a {@link RuntimeSandbox}
     * implementation by {@code cfg.sandbox.runtime} name. The default implementation is
     * the chroot-style {@code DefaultRuntimeSandbox} (tenant-aware, see dsh §6.3).
     *
     * <p>Router is intentionally NOT in {@code SlotResolver}; {@code AgentFactory}
     * autowires {@code RuntimeSandboxRouter} directly so the Sandbox instance is resolved
     * once per Agent and passed to {@code DefaultToolExecutionContext} as a constructor
     * argument (see dsh §5.3.1.0 "7 个隐式 Router concrete 类").
     */
    public interface RuntimeSandboxProvider extends SlotProvider<RuntimeSandbox> {}
}