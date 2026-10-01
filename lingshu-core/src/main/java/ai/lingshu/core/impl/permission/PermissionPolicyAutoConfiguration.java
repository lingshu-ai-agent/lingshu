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
 */
@Configuration
public class PermissionPolicyAutoConfiguration {

    /** 🆕 Story #031 — wired from {@code DefaultToolRegistry} via Spring context. */
    @Autowired
    private ToolRegistry toolRegistry;

    /**
     * Registers the {@link StrictPermissionPolicyProvider} under the
     * {@code "permissionPolicyProvider_strict-1.0.0"} Bean name. The sibling
     * {@code "default"} Provider comes from
     * {@link AllowAllPermissionPolicyProvider} (Story #001).
     */
    @Bean(name = "permissionPolicyProvider_strict-1.0.0")
    public PermissionPolicyProvider strictPermissionPolicyProvider() {
        return new StrictPermissionPolicyProvider(toolRegistry);
    }
}