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
 * Story #031 — verify Deny reason strings include pattern information for debuggability
 * (AC-NN-9).
 *
 * <p>Story #029 emitted reason like {@code "Tool 'X' in deny-list"} without the pattern.
 * Story #031 upgrades the reason to {@code "Tool 'X' matches deny pattern 'P'"} so operators
 * can see WHICH pattern triggered the denial.
 */
class StrictPermissionPolicyReasonTest {

    private static ToolCall call(String name) {
        return new ToolCall("call-1", name, NullNode.getInstance());
    }

    @Test
    @DisplayName("AC-031-9: deny reason includes the matched pattern string")
    void denyReason_includesMatchedPattern() {
        // Use category-prefix pattern "mcp:*" as the deny pattern — non-trivial case.
        AgentConfig.ToolsConfig tools = new AgentConfig.ToolsConfig(
            true, Arrays.asList("read_file"), Arrays.asList("mcp:*"),
            Collections.<String>emptyList(),  // 🆕 Story #030 — askList
            200_000, 1_000_000);
        Map<String, String> map = new HashMap<String, String>();
        map.put("echo", "mcp");
        StrictPermissionPolicy policy = new StrictPermissionPolicy(tools, map);

        Decision d = policy.check(call("echo"), null);

        assertThat(d).isInstanceOf(Decision.Deny.class);
        String reason = ((Decision.Deny) d).getReason();
        assertThat(reason)
            .startsWith("[" + PermissionErrorCodes.LINGS_P01 + "]")
            .contains("echo")
            .contains("matches deny pattern 'mcp:*'");
    }
}