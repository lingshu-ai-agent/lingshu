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
 * {@code agent.skills.sources[].type = "classpath"} into {@link ClasspathSkillSource}
 * instances.
 *
 * <p><b>Routing key:</b> {@code "classpath"} (must match the YAML
 * {@code agent.skills.sources[].type} string and the {@link ClasspathSkillSource#type()}
 * return value, so the two are mutually verified by contract test).
 *
 * <p><b>Discovery order:</b> multi-Provider resolution (§5.3.1.0) — every
 * {@code SkillSourceProvider} Spring discovers with a unique {@code type()} is added to
 * the router's {@code Map<String, SkillSourceProvider>}. Last wins if two providers share
 * a type (asserted via Story #020b T-05 router test).
 *
 * <p><b>Why a no-arg {@code create(String)} takes the YAML-supplied location:</b>
 * the {@code location} string is the full classpath prefix, e.g.
 * {@code "classpath:skills/agent-builtin/"} — Spring's {@code ResourceLoader} understands
 * the {@code classpath:} prefix natively, so we forward it verbatim to
 * {@link ClasspathSkillSource}.
 *
 * <p><b>Why not auto-construct the source eagerly here:</b> the source only needs the
 * configured location; constructing it lazily on first {@code discover()} call defers
 * filesystem access until the SkillAutoConfiguration bootstrap step actually invokes it.
 */
@Component
public class ClasspathSkillSourceProvider implements SkillSourceProvider {

    /** Stable routing key — must match {@link ClasspathSkillSource#type()} and YAML. */
    public static final String TYPE = "classpath";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public SkillSource create(String location) {
        return new ClasspathSkillSource(location);
    }
}
