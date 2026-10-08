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
package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.slot.SkillSource;
import ai.lingshu.core.slot.SkillSourceProvider;
import org.springframework.stereotype.Component;

/**
 * Story #020b — v1 {@link SkillSourceProvider} that resolves YAML entries of
 * {@code agent.skills.sources[].type = "directory"} into {@link DirectorySkillSource}
 * instances.
 *
 * <p><b>Routing key:</b> {@code "directory"} (must match the YAML
 * {@code agent.skills.sources[].type} string and {@link DirectorySkillSource#type()}).
 *
 * <p>The configured {@code location} is a filesystem path (relative or absolute) —
 * forwarded verbatim to {@link DirectorySkillSource}.
 */
@Component
public class DirectorySkillSourceProvider implements SkillSourceProvider {

    /** Stable routing key — must match {@link DirectorySkillSource#type()} and YAML. */
    public static final String TYPE = "directory";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public SkillSource create(String location) {
        return new DirectorySkillSource(location);
    }
}
