package ai.lingshu.core.impl.permission;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

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
 */
@Component
public class StrictPermissionPolicyProvider implements Providers.PermissionPolicyProvider {

    @Override public String name() { return "strict"; }

    @Override public int priority() { return 10; }

    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public PermissionPolicy create(AgentConfig config) {
        return new StrictPermissionPolicy(config.getTools());
    }
}