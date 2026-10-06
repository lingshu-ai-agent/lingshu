package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.router.Routers.PermissionPolicyRouter;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.permission.PermissionErrorCodes;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.spi.Providers.PermissionPolicyProvider;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #029 — L2 slice tests for {@link PermissionPolicyRouter} with the new
 * {@link StrictPermissionPolicyProvider} plugged in (4 cases).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@code resolve("strict", cfg)} returns a {@link StrictPermissionPolicy}
 *       (so the demo yml {@code permission-policy: strict} actually wires up
 *       the new policy);</li>
 *   <li>{@code resolve("default", cfg)} still returns the back-compat
 *       {@code AllowAllPermissionPolicy};</li>
 *   <li>Both providers co-exist in the same router (v1.5.28 §5.5 multi-Provider
 *       pattern);</li>
 *   <li>End-to-end: strict policy on a tools-config with an allow-list denies
 *       an unknown tool with the {@code [LINGS-P01]} prefix.</li>
 * </ul>
 */
class PermissionPolicyRouterStrictIT {

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, NullNode.getInstance());
    }

    private static AgentConfig.ToolsConfig toolsWith(List<String> allow) {
        return new AgentConfig.ToolsConfig(true, allow, Collections.<String>emptyList(), Collections.<String>emptyList(), 200_000, 1_000_000);
    }

    private static AgentConfig cfgWith(List<String> allow) {
        // Build via ToolsConfig + permissionPolicy=top-level default.
        AgentConfig base = new AgentConfig(
            "linear", null, null, "default", null, "default", "default",
            null, null, null,
            8, 60, 300, 0, 60, 50,
            null, null, null,
            "default", null, null, null,
            toolsWith(allow),
                        "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
        return base;
    }

    @Test
    @DisplayName("AC-029-11: resolve_strict_returnsStrictPolicy")
    void resolve_strict_returnsStrictPolicy() {
        PermissionPolicyProvider strict = new StrictPermissionPolicyProvider();
        PermissionPolicyProvider def = new ai.lingshu.core.impl.permission.AllowAllPermissionPolicyProvider();
        PermissionPolicyRouter router = new PermissionPolicyRouter(Arrays.asList(def, strict));

        PermissionPolicy policy = router.resolve("strict", cfgWith(Arrays.asList("read_file")));

        assertThat(policy).isInstanceOf(StrictPermissionPolicy.class);
    }

    @Test
    @DisplayName("AC-029-12: resolve_default_returnsAllowAll_backCompat")
    void resolve_default_returnsAllowAll_backCompat() {
        PermissionPolicyProvider strict = new StrictPermissionPolicyProvider();
        PermissionPolicyProvider def = new ai.lingshu.core.impl.permission.AllowAllPermissionPolicyProvider();
        PermissionPolicyRouter router = new PermissionPolicyRouter(Arrays.asList(def, strict));

        PermissionPolicy policy = router.resolve("default", cfgWith(Collections.<String>emptyList()));

        assertThat(policy).isInstanceOf(ai.lingshu.core.impl.permission.AllowAllPermissionPolicy.class);
    }

    @Test
    @DisplayName("AC-029-13: bothProvidersCoexist_resolveByName")
    void bothProvidersCoexist_resolveByName() {
        PermissionPolicyProvider strict = new StrictPermissionPolicyProvider();
        PermissionPolicyProvider def = new ai.lingshu.core.impl.permission.AllowAllPermissionPolicyProvider();
        PermissionPolicyRouter router = new PermissionPolicyRouter(Arrays.asList(def, strict));

        // Both name-resolvable; priority winner for ties goes to the higher one
        // (strict wins priority=10 over default priority=0, but the router resolves
        // by explicit name, not priority — this just verifies name uniqueness).
        assertThat(router.resolve("strict", cfgWith(Collections.<String>emptyList()))).isNotNull();
        assertThat(router.resolve("default", cfgWith(Collections.<String>emptyList()))).isNotNull();
    }

    @Test
    @DisplayName("EC-029-2: endToEnd_strictPolicy_deniesToolNotInAllowList")
    void endToEnd_strictPolicy_deniesToolNotInAllowList() {
        PermissionPolicyProvider strict = new StrictPermissionPolicyProvider();
        PermissionPolicyProvider def = new ai.lingshu.core.impl.permission.AllowAllPermissionPolicyProvider();
        PermissionPolicyRouter router = new PermissionPolicyRouter(Arrays.asList(def, strict));

        PermissionPolicy policy = router.resolve("strict",
            cfgWith(Arrays.asList("read_file", "write_file")));

        Decision deny = policy.check(call("bash_safe"), null);
        assertThat(deny).isInstanceOf(Decision.Deny.class);
        assertThat(((Decision.Deny) deny).getReason()).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
    }
}
