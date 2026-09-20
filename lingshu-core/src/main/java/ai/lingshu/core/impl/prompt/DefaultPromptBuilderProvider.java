package ai.lingshu.core.impl.prompt;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.spi.Providers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Default Provider for Slot 7 (PromptBuilder) — name "default", Story #001 + #002.
 *
 * <p>Story #002 change: now autowires the {@link Routers.MemorySourceRouter} and
 * resolves the configured MemorySource list up front, passing it into
 * {@link DefaultPromptBuilder} via constructor. This keeps the prompt assembly
 * hot path free of Router lookups.
 */
@Component
public class DefaultPromptBuilderProvider implements Providers.PromptBuilderProvider {

    private final Routers.MemorySourceRouter memorySourceRouter;

    @Autowired
    public DefaultPromptBuilderProvider(Routers.MemorySourceRouter memorySourceRouter) {
        this.memorySourceRouter = memorySourceRouter;
    }

    @Override public String name() { return "default"; }

    @Override public int priority() { return 0; }

    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public PromptBuilder create(AgentConfig config) {
        List<MemorySource> sources = memorySourceRouter.resolveAll(
            config.getPrompt().getMemorySources(), config);
        return new DefaultPromptBuilder(sources);
    }
}
