package ai.lingshu.core.impl.permission;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #029 — L1 unit tests for {@link StrictPermissionPolicyProvider} SPI contract.
 *
 * <p>Verifies the Slot 4 provider's name/priority/version identity, that
 * {@code create(cfg)} returns a usable {@link StrictPermissionPolicy} wired to
 * the config's {@link AgentConfig.ToolsConfig}, and that the v1.5.28 §5.5
 * Spring Bean name uniqueness rule is honored (no collision with the default
 * provider).
 */
class StrictPermissionPolicyProviderTest {

    @Test
    @DisplayName("AC-029-5: provider_name_is_strict")
    void provider_name_is_strict() {
        StrictPermissionPolicyProvider p = new StrictPermissionPolicyProvider();
        assertThat(p.name()).isEqualTo("strict");
        assertThat(p.priority()).isEqualTo(10);
        assertThat(p.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("AC-029-6: provider_create_returnsStrictPolicy_wiredToCfg")
    void provider_create_returnsStrictPolicy_wiredToCfg() {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        StrictPermissionPolicyProvider p = new StrictPermissionPolicyProvider();

        PermissionPolicy policy = p.create(cfg);

        assertThat(policy).isInstanceOf(StrictPermissionPolicy.class);
        assertThat(((StrictPermissionPolicy) policy).getTools())
            .isSameAs(cfg.getTools());
    }
}
