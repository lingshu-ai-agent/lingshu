/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
