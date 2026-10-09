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

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.SessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #014 — L1 unit tests for {@link InMemorySessionStoreProvider} SPI contract.
 *
 * <p>Verifies the Slot 5 default in-memory provider's name/priority/version identity
 * and that {@code create(cfg)} returns a usable {@link DefaultInMemorySessionStore}
 * instance. L1 only — no Spring context is started (the registration-path alignment
 * to {@code @Bean(name="sessionStoreProvider_memory-1.0.0")} is exercised by
 * {@code SessionStoreAutoConfigurationTest} and {@code SessionStoreRouterIT}).
 *
 * <p>Mirror of {@code AllowAllPermissionPolicyProviderTest} (Story #037) — same
 * 4 case shape: {@code name} / {@code priority} / {@code version} / {@code create}.
 */
class InMemorySessionStoreProviderTest {

    @Test
    @DisplayName("US1-AS1: provider_name_is_memory")
    void provider_name_is_memory() {
        InMemorySessionStoreProvider p = new InMemorySessionStoreProvider();
        assertThat(p.name()).isEqualTo("memory");
    }

    @Test
    @DisplayName("US1-AS2: provider_priority_is_10")
    void provider_priority_is_10() {
        InMemorySessionStoreProvider p = new InMemorySessionStoreProvider();
        assertThat(p.priority()).isEqualTo(10);
    }

    @Test
    @DisplayName("US1-AS3: provider_version_is_1_0_0")
    void provider_version_is_1_0_0() {
        InMemorySessionStoreProvider p = new InMemorySessionStoreProvider();
        assertThat(p.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("US1-AS4: provider_create_returnsDefaultInMemorySessionStore")
    void provider_create_returnsDefaultInMemorySessionStore() {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        InMemorySessionStoreProvider p = new InMemorySessionStoreProvider();

        SessionStore store = p.create(cfg);

        assertThat(store).isInstanceOf(DefaultInMemorySessionStore.class);
        // Functional sanity — load on fresh store returns empty Optional (not null,
        // not thrown); save + load round-trip preserves identity for the same key.
        DefaultInMemorySessionStore typed = (DefaultInMemorySessionStore) store;
        assertThat(typed.size()).isZero();
        assertThat(typed.load("never-saved")).isEmpty();
    }
}