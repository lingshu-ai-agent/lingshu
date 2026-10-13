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
package ai.lingshu.core.impl.session;

import ai.lingshu.core.spi.Providers.SessionStoreProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Story #014 — Slot 5 {@link SessionStoreProvider} registration.
 *
 * <p>Aligned with v1.5.28 §5.5 multi-Provider pattern: plain
 * {@code @Bean(name = "...")} with an explicit, uniquely-namespaced Bean name
 * (no {@code @ConditionalOnMissingBean} — that was the v1.5.27 single-Provider
 * assumption which is incompatible with the Router's
 * {@code Map<String, P>} shape).
 *
 * <p>Bean naming convention: {@code "<slot>Provider_<name>-<version>"} so
 * {@link ai.lingshu.core.impl.router.Routers.SessionStoreRouter}
 * (and its {@code SlotRouter} parent) can resolve multiple Providers by
 * {@code name()} without forcing a deployment-time choice between memory and file.
 *
 * <p><b>Why {@code @Configuration} (not {@code @AutoConfiguration})</b> —
 * matches the sibling pattern used by {@code PermissionPolicyAutoConfiguration}
 * (Story #029 / #031 / #037), {@code SkillAutoConfiguration},
 * {@code McpTransportAutoConfiguration}, {@code LocalToolsAutoConfiguration},
 * {@code DelegateAutoConfiguration}. The lingering shim of {@code @AutoConfiguration}
 * lives in {@code spring-boot-autoconfigure}, which the {@code lingshu-core}
 * module does not depend on directly — Spring Boot 3.2.5 transitive resolution
 * is enough.
 *
 * <p><b>Scope of this Story</b>: only the {@code memory} and {@code file} backends
 * are provided. The {@code redis} and {@code jdbc} backends are intentionally
 * <b>not</b> shipped in {@code lingshu-core} — they are user-extension points
 * that downstream projects register through their own
 * {@code @AutoConfiguration} following the v1.5.28 §5.5 multi-Provider pattern
 * (see dsh §5.5 "替代实现追加约定"). This keeps the core module dependency-free
 * for the common backends; users who need Redis / JDBC just add the matching
 * provider module to their application classpath.
 */
@Configuration
public class SessionStoreAutoConfiguration {

    /**
     * Registers the {@link InMemorySessionStoreProvider} under the
     * {@code "sessionStoreProvider_memory-1.0.0"} Bean name. User selects via
     * {@code agent.session-store: memory} (or absent — falls back to memory
     * by default; see {@code AgentConfigDefaults#DEFAULT_NAME}).
     */
    @Bean(name = "sessionStoreProvider_memory-1.0.0")
    public SessionStoreProvider memorySessionStoreProvider() {
        return new InMemorySessionStoreProvider();
    }

    /**
     * Registers the {@link FileSessionStoreProvider} under the
     * {@code "sessionStoreProvider_file-1.0.0"} Bean name. User selects via
     * {@code agent.session-store: file} in {@code application.yml}.
     */
    @Bean(name = "sessionStoreProvider_file-1.0.0")
    public SessionStoreProvider fileSessionStoreProvider() {
        return new FileSessionStoreProvider();
    }
}