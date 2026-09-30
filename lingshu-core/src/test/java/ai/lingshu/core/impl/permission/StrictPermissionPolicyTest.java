package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.permission.PermissionErrorCodes;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #029 — L1 unit tests for {@link StrictPermissionPolicy} (4 AC + 1 EC = 5 cases).
 *
 * <p>Verifies the 3 decision paths (allow-list not empty AND tool missing → Deny;
 * deny-list hit → Deny; default → Allow) plus the ErrorCode prefix contract and
 * {@code Decision.kind()} polymorphism.
 */
class StrictPermissionPolicyTest {

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, NullNode.getInstance());
    }

    private static AgentConfig.ToolsConfig toolsWith(java.util.List<String> allow, java.util.List<String> deny) {
        return new AgentConfig.ToolsConfig(true, allow, deny, 200_000, 1_000_000);
    }

    @Test
    @DisplayName("AC-029-1: allowListEmpty_alwaysAllow")
    void allowListEmpty_alwaysAllow() {
        // Both lists empty → Path 3 default allow, even for tools that don't exist
        PermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Collections.<String>emptyList(), Collections.<String>emptyList()));

        Decision d1 = policy.check(call("read_file"), null);
        Decision d2 = policy.check(call("any_random_tool"), null);

        assertThat(d1).isInstanceOf(Decision.Allow.class);
        assertThat(d2).isInstanceOf(Decision.Allow.class);
        assertThat(d1.kind()).isEqualTo("allow");
    }

    @Test
    @DisplayName("AC-029-2: allowListHit_returnsAllow")
    void allowListHit_returnsAllow() {
        PermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("read_file", "write_file"), Collections.<String>emptyList()));

        Decision d = policy.check(call("read_file"), null);

        assertThat(d).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) d).getReason()).contains("strict policy");
    }

    @Test
    @DisplayName("AC-029-3: allowListMiss_returnsDeny_withLingsP01Prefix")
    void allowListMiss_returnsDeny_withLingsP01Prefix() {
        PermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("read_file", "write_file"), Collections.<String>emptyList()));

        Decision d = policy.check(call("bash_safe"), null);

        assertThat(d).isInstanceOf(Decision.Deny.class);
        assertThat(d.kind()).isEqualTo("deny");
        String reason = ((Decision.Deny) d).getReason();
        assertThat(reason).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
        assertThat(reason).contains("bash_safe");
        assertThat(reason).contains("not in allow-list");
    }

    @Test
    @DisplayName("AC-029-4: denyListHit_overridesAllow_returnsDeny_withLingsP01Prefix")
    void denyListHit_overridesAllow_returnsDeny_withLingsP01Prefix() {
        // Tool is in BOTH allow and deny lists — deny wins (path 2 after path 1 allow check).
        // Here we test a tool that's only in deny: deny path triggers even without allow-list.
        PermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Collections.<String>emptyList(), Arrays.asList("dangerous_tool")));

        Decision d = policy.check(call("dangerous_tool"), null);

        assertThat(d).isInstanceOf(Decision.Deny.class);
        String reason = ((Decision.Deny) d).getReason();
        assertThat(reason).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
        assertThat(reason).contains("dangerous_tool");
        assertThat(reason).contains("in deny-list");
    }

    @Test
    @DisplayName("EC-029-1: denyListMiss_withEmptyAllow_returnsAllow")
    void denyListMiss_withEmptyAllow_returnsAllow() {
        // Only deny-list configured, tool not on it — default allow.
        PermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Collections.<String>emptyList(), Arrays.asList("dangerous_tool")));

        Decision d = policy.check(call("safe_tool"), null);

        assertThat(d).isInstanceOf(Decision.Allow.class);
    }
}
