package ai.lingshu.core.impl.flow;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.spi.Providers;
import org.springframework.beans.factory.annotation.Qualifier;
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

    public LinearTurnEngineProvider(Routers.PromptBuilderRouter promptBuilderRouter,
                                   Routers.LlmProviderRouter llmProviderRouter,
                                   Routers.ToolExecutorRouter toolExecutorRouter,
                                   Routers.PermissionPolicyRouter permissionPolicyRouter,
                                   @Qualifier("agentToolPool") ExecutorService agentToolPool) {
        this.promptBuilderRouter = promptBuilderRouter;
        this.llmProviderRouter = llmProviderRouter;
        this.toolExecutorRouter = toolExecutorRouter;
        this.permissionPolicyRouter = permissionPolicyRouter;
        this.agentToolPool = agentToolPool;
    }

    @Override
    public FlowEngine create(AgentConfig config) {
        return new LinearTurnEngine(
            promptBuilderRouter.resolve(config.getPrompt().getBuilder(), config),
            llmProviderRouter.resolve(config.getLlm().getProvider(), config),
            toolExecutorRouter.resolve(config.getToolExecutor(), config),
            permissionPolicyRouter.resolve(config.getSandbox().getPolicy(), config),
            agentToolPool);
    }
}