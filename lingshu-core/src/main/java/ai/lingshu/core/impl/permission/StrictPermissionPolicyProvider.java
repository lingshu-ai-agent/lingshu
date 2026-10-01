package ai.lingshu.core.impl.permission;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import ai.lingshu.core.spi.Providers;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;

/**
 * Story #029 — Slot 4 strict {@link Providers.PermissionPolicyProvider} (name
 * {@code "strict"}, priority 10). Sibling of the existing
 * {@link AllowAllPermissionPolicyProvider} (name {@code "default"}, priority 0).
 *
 * <p>Aligned with the v1.5.28 §5.5 multi-Provider pattern — each
 * {@code XxxProvider} registers as a separately-named Spring Bean (see
 * {@link PermissionPolicyAutoConfiguration#strictPermissionPolicyProvider()})
 * so {@code PermissionPolicyRouter.resolve("strict", cfg)} matches
 * {@code @Bean(name="permissionPolicyProvider_strict-1.0.0")} via the
 * {@code SlotRouter} parent class's name-keyed map.
 *
 * <p>Per §5.2 同名竞争约束, {@code name()} values across all
 * {@code PermissionPolicyProvider} Beans must be unique — {@code "default"}
 * is already claimed by {@link AllowAllPermissionPolicyProvider}, so
 * {@code "strict"} is reserved here.
 *
 * <p><b>🆕 Story #031 — {@link ToolRegistry} injection</b>: the provider now reads the
 * registered Tools at policy-creation time to populate the
 * {@code name → sourceCategory} map that {@link StrictPermissionPolicy#check} uses for
 * pattern matching. The injection is performed via Spring's constructor injection (no
 * SPI interface change — {@link Providers.PermissionPolicyProvider#create(AgentConfig)}
 * signature is unchanged). Test fixtures that construct the provider manually can pass
 * {@code null} / a stub registry.
 *
 * <p><b>🆕 Story #031 — removed {@code @Component}</b>: registration is exclusively
 * via {@link PermissionPolicyAutoConfiguration#strictPermissionPolicyProvider()}
 * which produces the uniquely-named Bean {@code "permissionPolicyProvider_strict-1.0.0"}.
 * Removing {@code @Component} prevents Spring from auto-registering a second
 * conflicting Bean named {@code "strictPermissionPolicyProvider"} (the default
 * camelCase from class name). Aligned with v1.5.28 §5.5 multi-Provider pattern.
 */
public class StrictPermissionPolicyProvider implements Providers.PermissionPolicyProvider {

    /** 🆕 Story #031 — registry used to populate the {@code name → sourceCategory} map. */
    private final ToolRegistry toolRegistry;

    @Autowired
    public StrictPermissionPolicyProvider(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Story #029 no-arg constructor preserved for test fixtures that instantiate the
     * provider directly without Spring DI (e.g., {@code StrictPermissionPolicyProviderTest},
     * {@code PermissionPolicyRouterStrictIT}). The provider tolerates a {@code null}
     * registry by treating unknown tools' source category as the default {@code "local"}.
     */
    public StrictPermissionPolicyProvider() {
        this(null);
    }

    @Override public String name() { return "strict"; }

    @Override public int priority() { return 10; }

    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public PermissionPolicy create(AgentConfig config) {
        Map<String, String> nameToCategory = new HashMap<String, String>();
        if (toolRegistry != null) {
            for (Tool t : toolRegistry.findAll()) {
                if (t != null && t.name() != null) {
                    String category = t.sourceCategory();
                    nameToCategory.put(t.name(), category == null ? "local" : category);
                }
            }
        }
        return new StrictPermissionPolicy(config.getTools(), nameToCategory);
    }
}