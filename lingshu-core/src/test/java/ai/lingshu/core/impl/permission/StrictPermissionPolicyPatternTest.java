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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #031 — L1 unit tests for {@link StrictPermissionPolicy} pattern matching
 * (5 pattern AC + 1 back-compat AC = 6 cases).
 *
 * <p>Verifies:
 * <ul>
 *   <li>Category-prefix patterns {@code "mcp:*"} / {@code "skill:*"} match</li>
 *   <li>Exact-name patterns still match (back-compat with Story #029)</li>
 *   <li>Deny-list patterns match first and override allow-list</li>
 *   <li>Unknown tools (not in {@code nameToCategory}) fall back to {@code "local"}</li>
 *   <li>Story #029 字面 equals yml entries (no wildcards) continue to work</li>
 * </ul>
 */
class StrictPermissionPolicyPatternTest {

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, NullNode.getInstance());
    }

    private static AgentConfig.ToolsConfig toolsWith(List<String> allow, List<String> deny) {
        return new AgentConfig.ToolsConfig(true, allow, deny, java.util.Collections.<String>emptyList(), 200_000, 1_000_000);
    }

    private static Map<String, String> categories(Object... pairs) {
        Map<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((String) pairs[i], (String) pairs[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("AC-031-3: mcp:* category pattern matches MCP tool")
    void mcpCategoryPattern_matchesMcpTool() {
        StrictPermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("mcp:*", "skill:*", "read_file"), null),
            categories("echo", "mcp", "agent", "skill", "read_file", "local"));

        Decision d = policy.check(call("echo"), null);

        assertThat(d).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) d).getReason()).contains("matches pattern 'mcp:*'");
    }

    @Test
    @DisplayName("AC-031-4: skill:* category pattern matches Skill tool")
    void skillCategoryPattern_matchesSkillTool() {
        StrictPermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("mcp:*", "skill:*", "read_file"), null),
            categories("echo", "mcp", "agent", "skill", "read_file", "local"));

        Decision d = policy.check(call("agent"), null);

        assertThat(d).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) d).getReason()).contains("matches pattern 'skill:*'");
    }

    @Test
    @DisplayName("AC-031-5: exact-name pattern matches the named tool")
    void exactNamePattern_matchesExactName() {
        StrictPermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("mcp:*", "skill:*", "read_file"), null),
            categories("echo", "mcp", "agent", "skill", "read_file", "local"));

        Decision d = policy.check(call("read_file"), null);

        assertThat(d).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) d).getReason()).contains("matches pattern 'read_file'");
    }

    @Test
    @DisplayName("AC-031-9: deny pattern match overrides allow-list (returns Deny with [LINGS-P01])")
    void denyPatternOverridesAllowList() {
        // Deny pattern uses category-prefix form (the only kind that triggers "matches deny pattern"
        // because exact-name form 3 always equals the deny list entry itself which is trivially matched).
        // Use `local:dangerous_tool` (a category-prefix that happens to be a single tool name)
        // — no, simpler: deny an exact-named tool that is also allowed. The pattern is exact-name,
        // and the reason still says "matches deny pattern '<name>'".
        StrictPermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("mcp:*", "skill:*"), Arrays.asList("danger_tool")),
            categories("danger_tool", "local", "echo", "mcp"));

        Decision d = policy.check(call("danger_tool"), null);

        assertThat(d).isInstanceOf(Decision.Deny.class);
        String reason = ((Decision.Deny) d).getReason();
        assertThat(reason).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
        assertThat(reason).contains("danger_tool");
        assertThat(reason).contains("matches deny pattern 'danger_tool'");
    }

    @Test
    @DisplayName("AC-031-5b: tool not in allow-list patterns → Deny with category context")
    void toolNotInAllowList_returnsDeny_withCategoryContext() {
        StrictPermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("mcp:*", "skill:*"), null),
            categories("write_file", "local", "echo", "mcp"));

        Decision d = policy.check(call("write_file"), null);

        assertThat(d).isInstanceOf(Decision.Deny.class);
        String reason = ((Decision.Deny) d).getReason();
        assertThat(reason).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
        assertThat(reason).contains("write_file");
        assertThat(reason).contains("not in allow-list");
        // 🆕 Story #031 — reason includes category context for debugging
        assertThat(reason).contains("category=local");
    }

    @Test
    @DisplayName("AC-031-9b: Story #029 字面 equals yml entries (no patterns) still work")
    void story029LiteralEqualsBackCompat() {
        // Story #029 had no nameToCategory at all (1-arg constructor with empty map).
        // Reproduce the exact Story #029 StrictPermissionPolicyTest.ac scenario.
        StrictPermissionPolicy policy = new StrictPermissionPolicy(
            toolsWith(Arrays.asList("read_file", "write_file", "list_dir", "bash_safe"),
                Collections.<String>emptyList()));

        Decision allow = policy.check(call("read_file"), null);
        Decision deny = policy.check(call("unknown_tool"), null);

        assertThat(allow).isInstanceOf(Decision.Allow.class);
        assertThat(deny).isInstanceOf(Decision.Deny.class);
        // Deny reason — when allow-list miss (no deny-list hit), reason format is
        // "Tool 'unknown_tool' not in allow-list (category=local)".
        String reason = ((Decision.Deny) deny).getReason();
        assertThat(reason).startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]");
        assertThat(reason).contains("not in allow-list");
        assertThat(reason).contains("unknown_tool");
        assertThat(reason).contains("category=local");
    }
}