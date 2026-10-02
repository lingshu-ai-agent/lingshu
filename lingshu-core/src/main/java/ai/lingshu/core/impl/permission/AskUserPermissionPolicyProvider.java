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
 * Story #030 — Slot 4 ask-user {@link Providers.PermissionPolicyProvider} (name
 * {@code "ask"}, priority 10). Sibling of {@link StrictPermissionPolicyProvider}
 * (name {@code "strict"}, priority 10) and {@link AllowAllPermissionPolicyProvider}
 * (name {@code "default"}, priority 0).
 *
 * <p>Aligned with the v1.5.28 §5.5 multi-Provider pattern — each
 * {@code XxxProvider} registers as a separately-named Spring Bean (see
 * {@link PermissionPolicyAutoConfiguration#askUserPermissionPolicyProvider()})
 * so {@code PermissionPolicyRouter.resolve("ask", cfg)} matches
 * {@code @Bean(name="permissionPolicyProvider_ask-1.0.0")} via the
 * {@code SlotRouter} parent class's name-keyed map.
 *
 * <p>Per §5.2 同名竞争约束, {@code name()} values across all
 * {@code PermissionPolicyProvider} Beans must be unique — {@code "default"} is
 * claimed by {@link AllowAllPermissionPolicyProvider}, {@code "strict"} by
 * {@link StrictPermissionPolicyProvider}, so {@code "ask"} is reserved here.
 *
 * <p>{@link ToolRegistry} injection follows the {@link StrictPermissionPolicyProvider}
 * pattern — populated at policy-creation time for the {@code name → sourceCategory}
 * map used by {@link AskUserPermissionPolicy#check} pattern matching. Test
 * fixtures that construct the provider manually can pass {@code null} / a
 * stub registry.
 *
 * <p>Not {@code @Component}: registration is exclusively via
 * {@link PermissionPolicyAutoConfiguration#askUserPermissionPolicyProvider()}
 * which produces the uniquely-named Bean {@code "permissionPolicyProvider_ask-1.0.0"}.
 * Removing {@code @Component} prevents Spring from auto-registering a second
 * conflicting Bean named {@code "askUserPermissionPolicyProvider"} (the default
 * camelCase from class name).
 */
public class AskUserPermissionPolicyProvider implements Providers.PermissionPolicyProvider {

    private final ToolRegistry toolRegistry;

    @Autowired
    public AskUserPermissionPolicyProvider(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * No-arg constructor preserved for test fixtures that instantiate the
     * provider directly without Spring DI (e.g., {@code AskUserPermissionPolicyProviderTest}).
     * The provider tolerates a {@code null} registry by treating unknown tools'
     * source category as the default {@code "local"}.
     */
    public AskUserPermissionPolicyProvider() {
        this(null);
    }

    @Override public String name() { return "ask"; }

    @Override public int priority() { return 10; }

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
        return new AskUserPermissionPolicy(config.getTools(), nameToCategory);
    }
}
