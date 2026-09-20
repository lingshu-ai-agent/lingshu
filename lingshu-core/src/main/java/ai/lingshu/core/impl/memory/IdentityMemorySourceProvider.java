package ai.lingshu.core.impl.memory;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Provider for {@link IdentityMemorySource}.
 */
@Component
public class IdentityMemorySourceProvider implements Providers.MemorySourceProvider {

    @Override public String name() { return "identity"; }
    @Override public int priority() { return 30; }

    @Override
    public MemorySource create(AgentConfig config) {
        return new IdentityMemorySource(config);
    }
}
