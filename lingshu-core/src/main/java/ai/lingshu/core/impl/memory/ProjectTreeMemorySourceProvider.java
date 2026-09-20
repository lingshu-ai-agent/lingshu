package ai.lingshu.core.impl.memory;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Provider for {@link ProjectTreeMemorySource}.
 */
@Component
public class ProjectTreeMemorySourceProvider implements Providers.MemorySourceProvider {

    @Override public String name() { return "project-tree"; }
    @Override public int priority() { return 40; }

    @Override
    public MemorySource create(AgentConfig config) {
        return new ProjectTreeMemorySource(config);
    }
}
