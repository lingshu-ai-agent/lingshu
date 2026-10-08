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
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.permission.PermissionErrorCodes;
import ai.lingshu.core.runtime.AgentConfig;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #030 — L1 unit tests for {@link AskUserPermissionPolicy} (4 AC + 2 EC = 6 cases).
 *
 * <p>Verifies the 4 decision paths (deny → ask → allow-empty → allow-match → deny-miss)
 * plus pattern matching reuse from Story #031 (exact-name / category-prefix / wildcard).
 */
class AskUserPermissionPolicyTest {

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, NullNode.getInstance());
    }

    private static AgentConfig.ToolsConfig toolsAllowStarAskBash() {
        return new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("*"),
            Collections.<String>emptyList(),
            Arrays.asList("bash_safe"),
            200_000, 1_000_000);
    }

    private static Map<String, String> catMap(String name, String category) {
        Map<String, String> m = new HashMap<String, String>();
        m.put(name, category);
        return m;
    }

    @Test
    @DisplayName("AC-030-1: askListHit_returnsAskUser (path 2)")
    void askListHit_returnsAskUser() {
        AskUserPermissionPolicy policy = new AskUserPermissionPolicy(toolsAllowStarAskBash());

        Decision d = policy.check(call("bash_safe"), null);

        assertThat(d).isInstanceOf(Decision.AskUser.class);
        assertThat(d.kind()).isEqualTo("ask");
        String prompt = ((Decision.AskUser) d).getPrompt();
        assertThat(prompt).contains("bash_safe");
        assertThat(prompt).contains("matches pattern 'bash_safe'");
    }

    @Test
    @DisplayName("AC-030-2: askListMiss_allowListHit_returnsAllow (path 3)")
    void askListMiss_allowListHit_returnsAllow() {
        AskUserPermissionPolicy policy = new AskUserPermissionPolicy(toolsAllowStarAskBash());

        Decision d = policy.check(call("read_file"), null);

        assertThat(d).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) d).getReason()).contains("matches pattern '*'");
    }

    @Test
    @DisplayName("AC-030-3: denyListHit_overridesAskList_returnsDeny (path 1)")
    void denyListHit_overridesAskList_returnsDeny() {
        AgentConfig.ToolsConfig tools = new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("*"),
            Arrays.asList("bash_safe"),         // deny and ask both contain bash_safe
            Arrays.asList("bash_safe"),
            200_000, 1_000_000);
        AskUserPermissionPolicy policy = new AskUserPermissionPolicy(tools);

        Decision d = policy.check(call("bash_safe"), null);

        assertThat(d).isInstanceOf(Decision.Deny.class);
        String reason = ((Decision.Deny) d).getReason();
        assertThat(reason).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
        assertThat(reason).contains("matches deny pattern 'bash_safe'");
    }

    @Test
    @DisplayName("AC-030-4: allowListMiss_returnsDeny (path 4)")
    void allowListMiss_returnsDeny() {
        AgentConfig.ToolsConfig tools = new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("read_file"),           // explicit allow-list, bash_safe not in
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            200_000, 1_000_000);
        AskUserPermissionPolicy policy = new AskUserPermissionPolicy(tools, catMap("bash_safe", "local"));

        Decision d = policy.check(call("bash_safe"), null);

        assertThat(d).isInstanceOf(Decision.Deny.class);
        String reason = ((Decision.Deny) d).getReason();
        assertThat(reason).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
        assertThat(reason).contains("bash_safe");
        assertThat(reason).contains("category=local");
    }

    @Test
    @DisplayName("EC-030-1: categoryPattern_askListMatch_returnsAskUser (Story #031 reuse)")
    void categoryPattern_askListMatch_returnsAskUser() {
        // ask-list with category pattern "mcp:*" — any tool whose sourceCategory() is "mcp"
        // should hit AskUser. Echo tool has category "mcp" → AskUser.
        AgentConfig.ToolsConfig tools = new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("*"),
            Collections.<String>emptyList(),
            Arrays.asList("mcp:*"),
            200_000, 1_000_000);
        AskUserPermissionPolicy policy = new AskUserPermissionPolicy(
            tools, catMap("echo", "mcp"));

        Decision d = policy.check(call("echo"), null);

        assertThat(d).isInstanceOf(Decision.AskUser.class);
        assertThat(((Decision.AskUser) d).getPrompt()).contains("matches pattern 'mcp:*'");
    }

    @Test
    @DisplayName("EC-030-2: allowListEmpty_returnsDefaultAllow (path 3 default)")
    void allowListEmpty_returnsDefaultAllow() {
        AgentConfig.ToolsConfig tools = new AgentConfig.ToolsConfig(
            true,
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            200_000, 1_000_000);
        AskUserPermissionPolicy policy = new AskUserPermissionPolicy(tools);

        Decision d = policy.check(call("anything"), null);

        assertThat(d).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) d).getReason()).contains("default policy: allow");
    }
}