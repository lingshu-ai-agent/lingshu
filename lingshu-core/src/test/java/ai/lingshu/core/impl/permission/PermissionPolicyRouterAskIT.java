/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.impl.permission;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.impl.router.Routers.PermissionPolicyRouter;
import ai.lingshu.core.message.ToolCall;
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
 * Story #030 — L2 slice tests for {@link PermissionPolicyRouter} with the new
 * {@link AskUserPermissionPolicyProvider} plugged in (3 cases).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@code resolve("ask", cfg)} returns an {@link AskUserPermissionPolicy};</li>
 *   <li>Three providers (default / strict / ask) co-exist in the same router;</li>
 *   <li>End-to-end: ask policy on tools-config with ask-list returns AskUser
 *       for matching tools.</li>
 * </ul>
 */
class PermissionPolicyRouterAskIT {

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, NullNode.getInstance());
    }

    private static AgentConfig.ToolsConfig toolsWith(List<String> allow, List<String> ask) {
        return new AgentConfig.ToolsConfig(
            true, allow, Collections.<String>emptyList(), ask, 200_000, 1_000_000);
    }

    private static AgentConfig cfgWith(List<String> allow, List<String> ask) {
        return new AgentConfig(
            "linear", null, null, "default", null, "default", "default",
            null, null, null,
            8, 60, 0, 0, 60, 50,
            null, null, null,
            "default", null, null, null,
            toolsWith(allow, ask),
                        "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    @Test
    @DisplayName("AC-030-16: resolve_ask_returnsAskUserPolicy")
    void resolve_ask_returnsAskUserPolicy() {
        PermissionPolicyProvider ask = new AskUserPermissionPolicyProvider();
        PermissionPolicyProvider def = new AllowAllPermissionPolicyProvider();
        PermissionPolicyRouter router = new PermissionPolicyRouter(Arrays.asList(def, ask));

        PermissionPolicy policy = router.resolve("ask",
            cfgWith(Arrays.asList("*"), Arrays.asList("bash_safe")));

        assertThat(policy).isInstanceOf(AskUserPermissionPolicy.class);
    }

    @Test
    @DisplayName("AC-030-17: threeProvidersCoexist_resolveByName")
    void threeProvidersCoexist_resolveByName() {
        PermissionPolicyProvider ask = new AskUserPermissionPolicyProvider();
        PermissionPolicyProvider strict = new StrictPermissionPolicyProvider();
        PermissionPolicyProvider def = new AllowAllPermissionPolicyProvider();
        PermissionPolicyRouter router = new PermissionPolicyRouter(Arrays.asList(def, strict, ask));

        AgentConfig cfg = cfgWith(Arrays.asList("read_file"), Arrays.asList("bash_safe"));

        assertThat(router.resolve("default", cfg)).isInstanceOf(AllowAllPermissionPolicy.class);
        assertThat(router.resolve("strict", cfg)).isInstanceOf(StrictPermissionPolicy.class);
        assertThat(router.resolve("ask", cfg)).isInstanceOf(AskUserPermissionPolicy.class);
    }

    @Test
    @DisplayName("AC-030-18: endToEnd_askPolicy_returnsAskUserForMatchingTool")
    void endToEnd_askPolicy_returnsAskUserForMatchingTool() {
        PermissionPolicyProvider ask = new AskUserPermissionPolicyProvider();
        PermissionPolicyProvider def = new AllowAllPermissionPolicyProvider();
        PermissionPolicyRouter router = new PermissionPolicyRouter(Arrays.asList(def, ask));

        PermissionPolicy policy = router.resolve("ask",
            cfgWith(Arrays.asList("*"), Arrays.asList("bash_safe")));

        Decision askDecision = policy.check(call("bash_safe"), null);
        assertThat(askDecision).isInstanceOf(Decision.AskUser.class);
        assertThat(((Decision.AskUser) askDecision).getPrompt()).contains("bash_safe");

        // A tool not in ask-list → falls through to allow-list ("*") → Allow
        Decision allowDecision = policy.check(call("read_file"), null);
        assertThat(allowDecision).isInstanceOf(Decision.Allow.class);
    }
}