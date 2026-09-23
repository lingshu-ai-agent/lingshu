package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.runtime.AgentConfig;
import lombok.Value;

/**
 * Immutable 2-field config for the four built-in local Tools
 * (Read / Write / Edit / Bash — Story #019, dsh §6.5 (1) L4427-4452).
 *
 * <p>Built from {@link AgentConfig.ToolsConfig} by
 * {@link LocalToolsAutoConfiguration} at startup — the AutoConfiguration is the
 * <em>only</em> place where {@link AgentConfig} gets translated to
 * {@link LocalToolProps}, keeping the Slot core (Tool interface / DefaultToolExecutor)
 * free of any config type.
 */
@Value
public class LocalToolProps {

    /** Read file size cap (bytes); over this length the content is truncated in-place. */
    int maxReadBytes;

    /** Write file size cap (bytes); over this length the write is rejected before any disk write. */
    int maxWriteBytes;

    /**
     * From cfg; {@code cfg.tools} null falls back to {@link AgentConfig.ToolsConfig#defaults()}
     * for back-compat with old YAML that didn't include {@code agent.tools}.
     */
    public static LocalToolProps from(AgentConfig cfg) {
        AgentConfig.ToolsConfig tc = cfg.getTools();
        if (tc == null) {
            tc = AgentConfig.ToolsConfig.defaults();
        }
        return new LocalToolProps(tc.getMaxReadBytes(), tc.getMaxWriteBytes());
    }
}
