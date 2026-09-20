package ai.lingshu.core.impl.memory;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Provider for {@link ProjectClaudeMdSource}. Spring bean name defaults to
 * {@code projectClaudeMdSourceProvider}; Spring auto-discovers it via component scan.
 */
@Component
public class ProjectClaudeMdSourceProvider implements Providers.MemorySourceProvider {

    @Override public String name() { return "project-claude-md"; }
    @Override public int priority() { return 10; }

    @Override
    public MemorySource create(AgentConfig config) {
        return new ProjectClaudeMdSource(config);
    }
}
