package ai.lingshu.core.impl.flow;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.spi.Providers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * Default FlowEngineProvider — wires {@link LinearTurnEngine} with the resolved
 * {@code PromptBuilder} + {@code LlmProvider} + {@code ToolExecutor} +
 * {@code PermissionPolicy} + shared {@code agentToolPool} from the same config.
 *
 * <p>🆕 Story #004 — adds {@code ToolExecutorRouter} + {@code PermissionPolicyRouter}
 * injections + the {@code agentToolPool} {@link ExecutorService} so the
 * {@link LinearTurnEngine#dispatchParallel} method can run tool calls concurrently.
 *
 * <p><b>🆕 Story #030 — optional {@link ApprovalRegistry} injection</b>: when present
 * (e.g. demo-product Spring context), the engine registers each pending approval
 * so an HTTP endpoint can deliver the human's answer back. When absent (e.g.
 * {@code lingshu-cli} or {@code demo-empty} Spring contexts that don't need
 * HTTP-driven approvals), the registry is null and the engine still works —
 * tests / fixtures invoke the continuation directly off the
 * {@code ApprovalRequired} event.
 */
@Component
public class LinearTurnEngineProvider implements Providers.FlowEngineProvider {

    /** name "linear" wins on absence of any other FlowEngineProvider — see dsh §4.11.4. */
    @Override
    public String name() { return "linear"; }

    /** Default priority (lower than the external adapters' 5). */
    @Override
    public int priority() { return 0; }

    /** Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    private final Routers.PromptBuilderRouter promptBuilderRouter;
    private final Routers.LlmProviderRouter llmProviderRouter;
    private final Routers.ToolExecutorRouter toolExecutorRouter;
    private final Routers.PermissionPolicyRouter permissionPolicyRouter;
    private final ExecutorService agentToolPool;
    /** 🆕 Story #030 — optional; null when no Spring context registers the bean. */
    private final ApprovalRegistry approvalRegistry;

    public LinearTurnEngineProvider(Routers.PromptBuilderRouter promptBuilderRouter,
                                   Routers.LlmProviderRouter llmProviderRouter,
                                   Routers.ToolExecutorRouter toolExecutorRouter,
                                   Routers.PermissionPolicyRouter permissionPolicyRouter,
                                   @Qualifier("agentToolPool") ExecutorService agentToolPool,
                                   @Autowired(required = false) @Nullable ApprovalRegistry approvalRegistry) {
        this.promptBuilderRouter = promptBuilderRouter;
        this.llmProviderRouter = llmProviderRouter;
        this.toolExecutorRouter = toolExecutorRouter;
        this.permissionPolicyRouter = permissionPolicyRouter;
        this.agentToolPool = agentToolPool;
        this.approvalRegistry = approvalRegistry;
    }

    @Override
    public FlowEngine create(AgentConfig config) {
        return new LinearTurnEngine(
            promptBuilderRouter.resolve(config.getPrompt().getBuilder(), config),
            llmProviderRouter.resolve(config.getLlm().getProvider(), config),
            toolExecutorRouter.resolve(config.getToolExecutor(), config),
            permissionPolicyRouter.resolve(config.getSandbox().getPolicy(), config),
            agentToolPool,
            approvalRegistry);
    }
}