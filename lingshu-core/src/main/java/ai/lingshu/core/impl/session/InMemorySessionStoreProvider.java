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

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.spi.Providers;

/**
 * Story #014 — Slot 5 in-memory {@link Providers.SessionStoreProvider} (name
 * {@code "memory"}, priority 10). Sibling of {@link FileSessionStoreProvider}
 * (name {@code "file"}, priority 10).
 *
 * <p>Aligned with the v1.5.28 §5.5 multi-Provider pattern — the Provider registers
 * as a separately-named Spring Bean (see
 * {@code SessionStoreAutoConfiguration#memorySessionStoreProvider()}) so
 * {@code SessionStoreRouter.resolve("memory", cfg)} matches
 * {@code @Bean(name="sessionStoreProvider_memory-1.0.0")} via the {@code SlotRouter}
 * parent class's name-keyed map.
 *
 * <p><b>NO {@code @Component}</b>: registration is exclusively via
 * {@code SessionStoreAutoConfiguration#memorySessionStoreProvider()} which produces
 * the uniquely-named Bean {@code "sessionStoreProvider_memory-1.0.0"}. Removing
 * {@code @Component} prevents Spring from auto-registering a second conflicting
 * Bean named {@code "inMemorySessionStoreProvider"} (the default camelCase from
 * class name). Aligned with v1.5.28 §5.5 multi-Provider pattern (mirror of
 * {@code StrictPermissionPolicyProvider}, Story #031).
 */
public class InMemorySessionStoreProvider implements Providers.SessionStoreProvider {

    @Override public String name() { return "memory"; }

    @Override public int priority() { return 10; }

    /** Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public SessionStore create(AgentConfig config) {
        return new DefaultInMemorySessionStore();
    }
}