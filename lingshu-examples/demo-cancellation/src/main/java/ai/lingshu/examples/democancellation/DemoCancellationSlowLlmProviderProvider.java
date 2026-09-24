package ai.lingshu.examples.democancellation;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Story #005 demo —— {@link DemoCancellationSlowLlmProvider} SPI Provider。
 * yml {@code agent.llm.provider: demo-cancellation-slow} 启用。
 */
@Component
public class DemoCancellationSlowLlmProviderProvider implements Providers.LlmProviderProvider {

    @Override public String name() { return "demo-cancellation-slow"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public LlmProvider create(AgentConfig config) {
        return new DemoCancellationSlowLlmProvider(3000L);
    }
}
