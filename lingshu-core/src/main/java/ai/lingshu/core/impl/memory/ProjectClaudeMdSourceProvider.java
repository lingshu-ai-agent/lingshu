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
    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public MemorySource create(AgentConfig config) {
        return new ProjectClaudeMdSource(config);
    }
}
