package ai.lingshu.core.impl.flow;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Default FlowEngineProvider — wires {@link LinearTurnEngine} with the resolved
 * {@code PromptBuilder} + {@code LlmProvider} from the same config.
 *
 * <p>Story #001 follow-ups add: {@code ToolExecutor}, {@code PermissionPolicy},
 * {@code Compactor} as further constructor args.
 */
@Component
public class LinearTurnEngineProvider implements Providers.FlowEngineProvider {

    /** name "linear" wins on absence of any other FlowEngineProvider — see dsh §4.11.4. */
    @Override
    public String name() { return "linear"; }

    /** Default priority (lower than the external adapters' 5). */
    @Override
    public int priority() { return 0; }

    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    private final Routers.PromptBuilderRouter promptBuilderRouter;
    private final Routers.LlmProviderRouter llmProviderRouter;

    public LinearTurnEngineProvider(Routers.PromptBuilderRouter promptBuilderRouter,
                                   Routers.LlmProviderRouter llmProviderRouter) {
        this.promptBuilderRouter = promptBuilderRouter;
        this.llmProviderRouter = llmProviderRouter;
    }

    @Override
    public FlowEngine create(AgentConfig config) {
        return new LinearTurnEngine(
            promptBuilderRouter.resolve(config.getPrompt().getBuilder(), config),
            llmProviderRouter.resolve(config.getLlm().getProvider(), config));
    }
}