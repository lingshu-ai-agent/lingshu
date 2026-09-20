package ai.lingshu.core.impl.permission;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Default Provider for Slot 3 model layer ({@link PermissionPolicy}) — name "default",
 * priority 0, allow-all behavior.
 */
@Component
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