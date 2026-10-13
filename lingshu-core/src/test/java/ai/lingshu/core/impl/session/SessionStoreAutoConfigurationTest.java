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
import ai.lingshu.core.spi.Providers.SessionStoreProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #014 — L2 unit tests for {@link SessionStoreAutoConfiguration}
 * (4 cases).
 *
 * <p>Mirrors the {@code HttpJsonRpcA2aTransportAutoConfigurationTest} shape (Story
 * #009c + #009e) — uses reflection (no {@code spring-boot-test} dependency) per
 * R-13 mitigation (d). Validates:
 * <ul>
 *   <li>Class is annotated {@code @Configuration} (not {@code @AutoConfiguration} —
 *       see class Javadoc: lingshu-core relies on {@code @ComponentScan} from
 *       {@code @SpringBootApplication}, matches the
 *       {@code PermissionPolicyAutoConfiguration} sibling pattern);</li>
 *   <li>Exposes exactly 2 {@code @Bean} methods, both returning
 *       {@link SessionStoreProvider}, both with explicitly namespaced Bean
 *       names following v1.5.28 §5.4 {@code <slot>Provider_<name>-<version>}
 *       convention;</li>
 *   <li>Both {@code @Bean} names are distinct (multi-Provider mode);</li>
 *   <li>Both returned Providers expose {@code name()}, {@code priority()},
 *       {@code version()} matching the v1.5.28 §5.5 contract, AND each
 *       {@code create()} returns the correct concrete {@link ai.lingshu.core.slot.SessionStore}
 *       implementation (the wiring contract).</li>
 * </ul>
 */
class SessionStoreAutoConfigurationTest {

    @Test
    @DisplayName("TC-AC-SSA-1: autoconfig_class_isAnnotated_andExposesTwoProviderBeans")
    void autoconfig_class_isAnnotated_andExposesTwoProviderBeans() throws Exception {
        Class<?> clazz = SessionStoreAutoConfiguration.class;
        assertThat(clazz.isAnnotationPresent(Configuration.class))
            .as("must be @Configuration (per class Javadoc — picks up via @ComponentScan)")
            .isTrue();

        int sessionStoreProviderBeanCount = 0;
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Bean.class)) continue;
            if (SessionStoreProvider.class.isAssignableFrom(m.getReturnType())) {
                sessionStoreProviderBeanCount++;
                Bean bean = m.getAnnotation(Bean.class);
                assertThat(bean.name())
                    .as("@Bean name must follow §5.4 convention sessionStoreProvider_<name>-<version>")
                    .containsExactly("sessionStoreProvider_" + beanMethodSuffix(m.getName()) + "-1.0.0");
            }
        }
        assertThat(sessionStoreProviderBeanCount)
            .as("SessionStoreAutoConfiguration must expose exactly 2 @Beans (memory + file)")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("TC-AC-SSA-2: memoryBeanName_andFileBeanName_areDistinct_andFollowConvention")
    void memoryBeanName_andFileBeanName_areDistinct_andFollowConvention() {
        String memoryName = beanNameFrom(SessionStoreAutoConfiguration.class, "memorySessionStoreProvider");
        String fileName = beanNameFrom(SessionStoreAutoConfiguration.class, "fileSessionStoreProvider");

        assertThat(memoryName).isEqualTo("sessionStoreProvider_memory-1.0.0");
        assertThat(fileName).isEqualTo("sessionStoreProvider_file-1.0.0");
        assertThat(memoryName).isNotEqualTo(fileName);
    }

    @Test
    @DisplayName("TC-AC-SSA-3: bothProviders_haveSameContract_version_1_0_0_priority_10")
    void bothProviders_haveSameContract_version_1_0_0_priority_10() throws Exception {
        SessionStoreAutoConfiguration cfg = new SessionStoreAutoConfiguration();
        SessionStoreProvider mem = cfg.memorySessionStoreProvider();
        SessionStoreProvider file = cfg.fileSessionStoreProvider();

        // Both Providers must expose the v1.5.28 §5.5 contract identically
        // (name + priority + version).
        assertThat(mem.name()).isEqualTo("memory");
        assertThat(mem.priority()).isEqualTo(10);
        assertThat(mem.version()).isEqualTo("1.0.0");

        assertThat(file.name()).isEqualTo("file");
        assertThat(file.priority()).isEqualTo(10);
        assertThat(file.version()).isEqualTo("1.0.0");

        // Provider names must be distinct (SlotRouter uses them as map keys).
        Set<String> names = new HashSet<>(Arrays.asList(mem.name(), file.name()));
        assertThat(names)
            .as("provider name()s must be distinct — they are the map keys in SessionStoreRouter")
            .hasSize(2);
    }

    @Test
    @DisplayName("TC-AC-SSA-4: eachProvider_create_returnsCorrectConcreteSessionStore")
    void eachProvider_create_returnsCorrectConcreteSessionStore() {
        SessionStoreAutoConfiguration cfg = new SessionStoreAutoConfiguration();
        SessionStoreProvider mem = cfg.memorySessionStoreProvider();
        SessionStoreProvider file = cfg.fileSessionStoreProvider();

        AgentConfig agentCfg = AgentConfigDefaults.defaults();

        assertThat(mem.create(agentCfg))
            .as("memory Provider must build DefaultInMemorySessionStore")
            .isInstanceOf(DefaultInMemorySessionStore.class);

        assertThat(file.create(agentCfg))
            .as("file Provider must build FileSessionStore")
            .isInstanceOf(FileSessionStore.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Derive the {@code <name>} part of the §5.4 convention
     * {@code sessionStoreProvider_<name>-1.0.0} from the {@code @Bean} method name.
     * Strips the {@code "SessionStoreProvider"} suffix and lowercases the first
     * char (e.g. {@code memorySessionStoreProvider} → {@code memory}).
     */
    private static String beanMethodSuffix(String methodName) {
        String stripped = methodName.replaceAll("SessionStoreProvider$", "");
        return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
    }

    private static String beanNameFrom(Class<?> clazz, String methodName) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) continue;
            Bean bean = m.getAnnotation(Bean.class);
            if (bean == null) continue;
            String[] names = bean.name();
            return names.length > 0 ? names[0] : m.getName();
        }
        return null;
    }
}
