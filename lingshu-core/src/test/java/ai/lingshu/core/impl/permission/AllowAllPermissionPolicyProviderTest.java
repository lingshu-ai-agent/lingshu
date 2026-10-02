package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #037 — L1 unit tests for {@link AllowAllPermissionPolicyProvider} SPI contract.
 *
 * <p>Verifies the Slot 4 default provider's name/priority/version identity and
 * that {@code create(cfg)} returns a usable {@link AllowAllPermissionPolicy} whose
 * {@code check()} always returns {@link Decision.Allow}. L1 only — no Spring
 * context is started (the registration-path alignment to {@code @Bean(name="...")}
 * is exercised by {@link PermissionPolicyRouterMultiProviderIT}).
 */
class AllowAllPermissionPolicyProviderTest {

    @Test
    @DisplayName("US1-AS1: provider_name_is_default")
    void provider_name_is_default() {
        AllowAllPermissionPolicyProvider p = new AllowAllPermissionPolicyProvider();
        assertThat(p.name()).isEqualTo("default");
    }

    @Test
    @DisplayName("US1-AS2: provider_priority_is_zero")
    void provider_priority_is_zero() {
        AllowAllPermissionPolicyProvider p = new AllowAllPermissionPolicyProvider();
        assertThat(p.priority()).isEqualTo(0);
    }

    @Test
    @DisplayName("US1-AS3: provider_version_is_1_0_0")
    void provider_version_is_1_0_0() {
        AllowAllPermissionPolicyProvider p = new AllowAllPermissionPolicyProvider();
        assertThat(p.version()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("US1-AS4: provider_create_returnsAllowAllPolicy_alwaysAllows")
    void provider_create_returnsAllowAllPolicy_alwaysAllows() {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        AllowAllPermissionPolicyProvider p = new AllowAllPermissionPolicyProvider();

        PermissionPolicy policy = p.create(cfg);

        assertThat(policy).isInstanceOf(AllowAllPermissionPolicy.class);
        // allow-all behavior: any Tool call yields Decision.Allow regardless of name/category.
        Decision d = policy.check(new ToolCall("call-1", "anything", NullNode.getInstance()), null);
        assertThat(d).isInstanceOf(Decision.Allow.class);
    }
}
