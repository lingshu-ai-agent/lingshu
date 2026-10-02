package ai.lingshu.core.impl.permission;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.spi.Providers;

/**
 * Default Provider for Slot 4 PermissionPolicy — name {@code "default"},
 * priority 0, allow-all behavior.
 *
 * <p>Sibling of {@link StrictPermissionPolicyProvider} (name {@code "strict"}, priority 10)
 * and {@link AskUserPermissionPolicyProvider} (name {@code "ask"}, priority 10).
 *
 * <p>Aligned with the v1.5.28 §5.5 multi-Provider pattern — each {@code XxxProvider}
 * registers as a separately-named Spring Bean (see
 * {@link PermissionPolicyAutoConfiguration#defaultPermissionPolicyProvider()})
 * so {@code PermissionPolicyRouter.resolve("default", cfg)} matches
 * {@code @Bean(name="permissionPolicyProvider_default-1.0.0")} via the
 * {@code SlotRouter} parent class's name-keyed map.
 *
 * <p>Per §5.2 同名竞争约束, {@code name()} values across all
 * {@code PermissionPolicyProvider} Beans must be unique — {@code "strict"} and
 * {@code "ask"} are claimed by the sibling providers above; {@code "default"} is
 * reserved here.
 *
 * <p><b>Not {@code @Component}</b>: registration is exclusively via
 * {@link PermissionPolicyAutoConfiguration#defaultPermissionPolicyProvider()}
 * which produces the uniquely-named Bean {@code "permissionPolicyProvider_default-1.0.0"}.
 * Removing {@code @Component} prevents Spring from auto-registering a second
 * conflicting Bean named {@code "allowAllPermissionPolicyProvider"} (the default
 * camelCase from class name). Aligned with v1.5.28 §5.5 multi-Provider pattern.
 *
 * <p>🆕 v1.5.53 Story #037 — migration from Story #001 {@code @Component} style to
 * v1.5.28 multi-Provider {@code @Bean(name="...")} style, aligning with siblings
 * {@link StrictPermissionPolicyProvider} and {@link AskUserPermissionPolicyProvider}.
 */
public class AllowAllPermissionPolicyProvider implements Providers.PermissionPolicyProvider {

    @Override public String name() { return "default"; }

    @Override public int priority() { return 0; }

    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public PermissionPolicy create(AgentConfig config) {
        return new AllowAllPermissionPolicy();
    }
}
