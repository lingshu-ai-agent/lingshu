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

import ai.lingshu.core.impl.router.Routers.SessionStoreRouter;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.spi.Providers.SessionStoreProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #014 — L2 integration tests for the v1.5.28 §5.5 multi-Provider alignment
 * of Slot 5 {@link SessionStoreProvider} (5 cases).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@code resolve("memory", cfg)} → {@link DefaultInMemorySessionStore}
 *       (US2-AS1 — confirms Story #014 explicit
 *       {@code @Bean(name="sessionStoreProvider_memory-1.0.0")} produces a functional
 *       Provider that the Router can resolve by name);</li>
 *   <li>{@code resolve("file", cfg)} → {@link FileSessionStore} (US2-AS2 — the
 *       second provider in the multi-Provider list);</li>
 *   <li>The Router's internal {@code Map<String, Provider>} (built from the
 *       Spring-injected Provider list) contains both entries simultaneously
 *       (US2-AS3 — proves the v1.5.28 multi-Provider pattern works for all
 *       Beans under the new naming convention, AND that the Router supports
 *       2 providers, not just 1);</li>
 *   <li>{@link InMemorySessionStoreProvider} no longer carries {@code @Component}
 *       (US2-AS4 — proves the Story #001-era {@code @Component} annotation has
 *       been removed so Spring doesn't auto-register a duplicate Bean named
 *       {@code "inMemorySessionStoreProvider"});</li>
 *   <li>{@link FileSessionStoreProvider} no longer carries {@code @Component}
 *       (US2-AS5 — same defensive migration for the file backend, mirroring
 *       the Story #037 {@code AllowAllPermissionPolicyProvider} precedent).</li>
 * </ul>
 *
 * <p>Uses the same direct-construction pattern as sibling ITs
 * ({@link PermissionPolicyRouterMultiProviderIT}) — no {@code @SpringBootTest}
 * needed because the {@link SessionStoreAutoConfiguration} contract under test
 * is purely "given a list of Providers, the Router resolves by name and the
 * classes are not Spring-managed". The full Spring context wiring is exercised
 * by {@code AgentFactory}-level integration tests (story follow-up that wires
 * Checkpoint persistence into {@code DefaultAgent.run}).
 */
class SessionStoreRouterIT {

    private static AgentConfig.ToolsConfig emptyTools() {
        return new AgentConfig.ToolsConfig(
            true,
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            200_000, 1_000_000);
    }

    private static AgentConfig cfg() {
        return new AgentConfig(
            "linear", null, null, "default", null, "default", "default",
            null, null, null,
            8, 60, 0, 0, 60, 50,
            null, null, null,
            "default", null, null, null,
            emptyTools(),
            "default",
            16,       // 🆕 Story #044 — maxConcurrentTurns
            32);      // 🆕 Story #044 — maxConcurrentQueueDepth
    }

    /**
     * Build the 2 Providers exactly the way {@link SessionStoreAutoConfiguration}
     * does — call the 2 {@code @Bean} methods directly. This mirrors the Spring
     * container's bean-wiring without needing {@code @SpringBootTest}.
     */
    private static SessionStoreRouter routerViaAutoConfiguration() {
        SessionStoreAutoConfiguration cfg = new SessionStoreAutoConfiguration();
        SessionStoreProvider mem = cfg.memorySessionStoreProvider();
        SessionStoreProvider file = cfg.fileSessionStoreProvider();
        return new SessionStoreRouter(Arrays.asList(mem, file));
    }

    @Test
    @DisplayName("US2-AS1: resolve_memory_returnsInMemorySessionStore")
    void resolve_memory_returnsInMemorySessionStore() {
        SessionStoreRouter router = routerViaAutoConfiguration();

        SessionStore store = router.resolve("memory", cfg());

        assertThat(store).isInstanceOf(DefaultInMemorySessionStore.class);
    }

    @Test
    @DisplayName("US2-AS2: resolve_file_returnsFileSessionStore")
    void resolve_file_returnsFileSessionStore() {
        SessionStoreRouter router = routerViaAutoConfiguration();

        SessionStore store = router.resolve("file", cfg());

        assertThat(store).isInstanceOf(FileSessionStore.class);
    }

    @Test
    @DisplayName("US2-AS3: routerInternalMap_containsBothProviders")
    @SuppressWarnings("unchecked")
    void routerInternalMap_containsBothProviders() throws Exception {
        SessionStoreRouter router = routerViaAutoConfiguration();

        // The parent SlotRouter stores providers in a private final Map<String, P> byName.
        Field byNameField = router.getClass().getSuperclass().getDeclaredField("byName");
        byNameField.setAccessible(true);
        Map<String, SessionStoreProvider> byName =
            (Map<String, SessionStoreProvider>) byNameField.get(router);

        assertThat(byName)
            .as("Router's internal byName map must contain both Providers")
            .hasSize(2)
            .containsKeys("memory", "file");
    }

    @Test
    @DisplayName("US2-AS4: inMemoryProviderClass_hasNoComponentAnnotation")
    void inMemoryProviderClass_hasNoComponentAnnotation() {
        // AC-014-2: InMemorySessionStoreProvider must no longer be @Component —
        // registration is exclusively via @Bean(name="sessionStoreProvider_memory-1.0.0").
        Component annotation = InMemorySessionStoreProvider.class
            .getDeclaredAnnotation(Component.class);

        assertThat(annotation)
            .as("InMemorySessionStoreProvider must not be @Component (Story #014 migration)")
            .isNull();
    }

    @Test
    @DisplayName("US2-AS5: fileProviderClass_hasNoComponentAnnotation")
    void fileProviderClass_hasNoComponentAnnotation() {
        // AC-014-3: FileSessionStoreProvider must no longer be @Component —
        // registration is exclusively via @Bean(name="sessionStoreProvider_file-1.0.0").
        Component annotation = FileSessionStoreProvider.class
            .getDeclaredAnnotation(Component.class);

        assertThat(annotation)
            .as("FileSessionStoreProvider must not be @Component (Story #014 migration)")
            .isNull();
    }
}
