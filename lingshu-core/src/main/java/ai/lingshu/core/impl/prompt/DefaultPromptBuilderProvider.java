package ai.lingshu.core.impl.prompt;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Default Provider for Slot 7 (PromptBuilder) — name "default", Story #001 default.
 */
@Component
public class DefaultPromptBuilderProvider implements Providers.PromptBuilderProvider {

    @Override public String name() { return "default"; }

    @Override public int priority() { return 0; }

    @Override
    public PromptBuilder create(AgentConfig config) {
        return new DefaultPromptBuilder();
    }
}