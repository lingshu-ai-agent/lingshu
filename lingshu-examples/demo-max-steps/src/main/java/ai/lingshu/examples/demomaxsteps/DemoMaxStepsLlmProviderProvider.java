package ai.lingshu.examples.demomaxsteps;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Story #008 demo — {@link DemoMaxStepsLlmProvider} SPI Provider。
 */
@Component
public class DemoMaxStepsLlmProviderProvider implements Providers.LlmProviderProvider {

    @Override public String name() { return "demo-max-steps"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public LlmProvider create(AgentConfig config) {
        return new DemoMaxStepsLlmProvider("noop", "call-" + UUID.randomUUID());
    }
}