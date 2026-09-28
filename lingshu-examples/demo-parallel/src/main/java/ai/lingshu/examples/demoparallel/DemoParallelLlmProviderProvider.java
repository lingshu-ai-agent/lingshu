package ai.lingshu.examples.demoparallel;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Story #004 demo —— {@link DemoParallelLlmProvider} 的 SPI Provider 包装。
 * yml {@code agent.llm.provider: demo-parallel-echo} 启用。
 */
@Component
public class DemoParallelLlmProviderProvider implements Providers.LlmProviderProvider {

    @Override public String name() { return "demo-parallel-echo"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public LlmProvider create(AgentConfig config) {
        return new DemoParallelLlmProvider();
    }
}
