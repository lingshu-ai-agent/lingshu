package ai.lingshu.core.impl.memory;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Provider for {@link UserClaudeMdSource}.
 */
@Component
public class UserClaudeMdSourceProvider implements Providers.MemorySourceProvider {

    @Override public String name() { return "user-claude-md"; }
    @Override public int priority() { return 20; }
    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public MemorySource create(AgentConfig config) {
        return new UserClaudeMdSource(config);
    }
}
