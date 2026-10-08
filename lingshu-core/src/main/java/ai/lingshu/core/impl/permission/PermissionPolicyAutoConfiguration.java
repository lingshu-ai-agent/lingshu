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
package ai.lingshu.core.impl.permission;

import ai.lingshu.core.slot.ToolRegistry;
import ai.lingshu.core.spi.Providers.PermissionPolicyProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Story #029 — Slot 4 strict {@link PermissionPolicyProvider} registration.
 *
 * <p>Aligned with v1.5.28 §5.5 multi-Provider pattern: plain
 * {@code @Bean(name = "...")} with an explicit, uniquely-namespaced Bean name
 * (no {@code @ConditionalOnMissingBean} — that was the v1.5.27 single-Provider
 * assumption which is incompatible with the Router's
 * {@code Map<String, P>} shape).
 *
 * <p>Bean naming convention: {@code "<slot>Provider_<name>-<version>"} so
 * {@link ai.lingshu.core.impl.router.Routers.PermissionPolicyRouter}
 * (and its {@code SlotRouter} parent) can resolve multiple Providers by
 * {@code name()} without forcing a deployment-time choice between strict and
 * default.
 *
 * <p><b>Why {@code @Configuration} (not {@code @AutoConfiguration})</b> —
 * matches the sibling pattern used by {@code SkillAutoConfiguration},
 * {@code McpTransportAutoConfiguration}, {@code LocalToolsAutoConfiguration},
 * {@code DelegateAutoConfiguration} (lingering shim of {@code @AutoConfiguration}
 * lives in {@code spring-boot-autoconfigure}, which the
 * {@code lingshu-core} module does not depend on directly — Spring Boot 3.2.5
 * transitive resolution is enough).
 *
 * <p><b>🆕 Story #031 — {@link ToolRegistry} injection</b>: the strict provider
 * now needs the registry at construction time so {@link StrictPermissionPolicyProvider#create}
 * can populate the {@code name → sourceCategory} map. Spring's
 * {@code @Autowired} set-field is used to wire the singleton registry into
 * the {@code @Bean} method (avoids changing the {@code @Bean} method's
 * signature).
 *
 * <p><b>🆕 v1.5.53 Story #037 — AllowAll also goes explicit {@code @Bean} mode</b>:
 * Prior to Story #037, {@link AllowAllPermissionPolicyProvider} was registered via
 * {@code @Component} (Story #001), which produced the camelCase Bean name
 * {@code "allowAllPermissionPolicyProvider"} — inconsistent with the v1.5.28 §5.5
 * multi-Provider {@code @Bean(name = "<slot>Provider_<name>-<version>")} naming
 * convention used by the strict / ask providers below. Story #037 aligns all
 * 3 Providers to the same registration style; behavior is unchanged.
 */
@Configuration
public class PermissionPolicyAutoConfiguration {

    /** 🆕 Story #031 — wired from {@code DefaultToolRegistry} via Spring context. */
    @Autowired
    private ToolRegistry toolRegistry;

    /**
     * 🆕 v1.5.53 Story #037 — registers the {@link AllowAllPermissionPolicyProvider}
     * under the {@code "permissionPolicyProvider_default-1.0.0"} Bean name. User
     * selects via {@code agent.permission-policy: default} (or absent — falls back
     * to allow-all by default, see Story #001 back-compat).
     *
     * <p>Before Story #037 this Provider was registered via {@code @Component} (Story #001),
     * which produced the camelCase Bean name {@code "allowAllPermissionPolicyProvider"}
     * — inconsistent with the v1.5.28 §5.5 multi-Provider
     * {@code @Bean(name = "<slot>Provider_<name>-<version>")} naming convention used by
     * siblings {@link StrictPermissionPolicyProvider} and {@link AskUserPermissionPolicyProvider}.
     *
     * <p>Behavior is unchanged: {@code PermissionPolicyRouter} resolves by {@code name()}
     * ({@code "default"}), which still maps to {@link AllowAllPermissionPolicy}. The migration
     * is a registration-path alignment, not a behavior change.
     */
    @Bean(name = "permissionPolicyProvider_default-1.0.0")
    public PermissionPolicyProvider defaultPermissionPolicyProvider() {
        return new AllowAllPermissionPolicyProvider();
    }

    /**
     * Registers the {@link StrictPermissionPolicyProvider} under the
     * {@code "permissionPolicyProvider_strict-1.0.0"} Bean name.
     */
    @Bean(name = "permissionPolicyProvider_strict-1.0.0")
    public PermissionPolicyProvider strictPermissionPolicyProvider() {
        return new StrictPermissionPolicyProvider(toolRegistry);
    }

    /**
     * 🆕 Story #030 — registers the {@link AskUserPermissionPolicyProvider} under
     * the {@code "permissionPolicyProvider_ask-1.0.0"} Bean name. User selects via
     * {@code agent.permission-policy: ask} in {@code application.yml}; the
     * {@code PermissionPolicyRouter} (v1.5.28 multi-Provider pattern) resolves
     * {@code "ask"} → this Bean → {@link AskUserPermissionPolicy}, which adds
     * a 3rd input list {@code ask-list} for tools that should pause the turn and
     * route to human approval rather than auto-allow / auto-deny.
     */
    @Bean(name = "permissionPolicyProvider_ask-1.0.0")
    public PermissionPolicyProvider askUserPermissionPolicyProvider() {
        return new AskUserPermissionPolicyProvider(toolRegistry);
    }
}