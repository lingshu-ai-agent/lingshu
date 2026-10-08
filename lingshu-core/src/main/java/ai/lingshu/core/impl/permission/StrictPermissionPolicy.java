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
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.ToolExecutionContext;
import lombok.Getter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Story #029 — strict tool-level {@link PermissionPolicy} backed by
 * {@link AgentConfig.ToolsConfig#allowList} (allow-list) and
 * {@link AgentConfig.ToolsConfig#denyList} (deny-list).
 *
 * <p>Wired into {@code ToolExecutor.dispatch()} as §4.10.1 硬规则 2 第 1 步
 * (previously a stub that always returned {@link Decision.Allow}). Now consults
 * the configured lists deterministically.
 *
 * <p>Three decision paths (in order, short-circuit on first match):
 * <ol>
 *   <li><b>deny-list hit</b> → {@link Decision.Deny}
 *       with reason {@code "[LINGS-P01] Tool '<name>' matches deny pattern '<pattern>'"}</li>
 *   <li><b>allow-list empty OR allow-list hit</b> → {@link Decision.Allow}
 *       with reason reflecting default-allow vs explicit-allow-match</li>
 *   <li><b>allow-list non-empty AND no pattern matches</b> → {@link Decision.Deny}
 *       with reason {@code "[LINGS-P01] Tool '<name>' not in allow-list (category=<cat>)"}</li>
 * </ol>
 *
 * <p><b>🆕 Story #031 — pattern matching</b> (replaces Story #029 strict-equals {@code List.contains}
 * with {@link PermissionPatterns#matches}):
 * <ul>
 *   <li>{@code "*"} — match any tool (allow all / deny all).</li>
 *   <li>{@code "<category>:*"} — match all tools whose {@link ai.lingshu.core.slot.Tool#sourceCategory()}
 *       equals {@code <category>}. Five reserved categories: {@code local / mcp / skill / a2a / delegate};
 *       plugin authors may use custom strings.</li>
 *   <li>{@code "<exact-name>"} — strict-equals match against {@link ai.lingshu.core.slot.Tool#name()}
 *       (back-compat with Story #029 yml entries that happen to be exact tool names).</li>
 * </ul>
 *
 * <p>Back-compat with Story #029 is guaranteed: every yml entry that was a literal tool name
 * in Story #029 still resolves via the third pattern form. The five existing
 * {@code StrictPermissionPolicyTest} cases (allowListEmpty / allowListHit / allowListMiss /
 * denyListHit / denyListMiss) pass without modification after this upgrade.
 *
 * <p>ErrorCode prefix {@code "[LINGS-P01]"} is embedded in {@code Decision.Deny.reason}
 * (see {@link PermissionErrorCodes}) so the downstream {@code ToolExecutor} can surface
 * the failure as a {@code ToolResult.error} without ever throwing — aligns with §4.10.1 硬规则 2.
 *
 * <p><b>🆕 Story #031 — removed {@code @Component}</b>: this class is <b>not</b>
 * a Spring bean — instances are produced on demand by
 * {@link StrictPermissionPolicyProvider#create(AgentConfig)} with the
 * {@code AgentConfig.ToolsConfig} + {@code nameToCategory} bound at call
 * time. Marking it {@code @Component} would force Spring to instantiate
 * it directly via reflection, requiring a no-arg constructor (which we
 * deliberately do not provide — the {@code ToolsConfig} is required). The
 * Provider owns the lifecycle; this class is a plain value object.
 */
@Getter
@ToString
public class StrictPermissionPolicy implements PermissionPolicy {

    /** Immutable per-turn config (dsh §4.12.2). Carries allow-list + deny-list. */
    private final AgentConfig.ToolsConfig tools;

    /**
     * Precomputed {@code toolName → sourceCategory} lookup, populated by
     * {@link StrictPermissionPolicyProvider} from {@code ToolRegistry.findAll()}.
     * A tool not in the map falls back to {@code "local"} (per the
     * {@link ai.lingshu.core.slot.Tool#sourceCategory()} default method).
     *
     * <p>Empty / null is tolerated — the policy treats unknown as {@code "local"}.
     */
    private final Map<String, String> nameToCategory;

    /**
     * Story #029 1-arg constructor preserved for test fixtures (e.g., {@code StrictPermissionPolicyTest}
     * which constructs the policy directly without a provider). Delegates to the 2-arg constructor
     * with an empty category map — strict-equals path still works (Pattern form 3).
     */
    public StrictPermissionPolicy(AgentConfig.ToolsConfig tools) {
        this(tools, Collections.<String, String>emptyMap());
    }

    /**
     * 🆕 Story #031 — primary constructor used by {@link StrictPermissionPolicyProvider}.
     * Manually written because {@code @Value} would not auto-generate an all-args constructor
     * when a sibling 1-arg constructor is present.
     */
    public StrictPermissionPolicy(AgentConfig.ToolsConfig tools, Map<String, String> nameToCategory) {
        if (tools == null) {
            throw new IllegalArgumentException("tools must not be null");
        }
        this.tools = tools;
        this.nameToCategory = nameToCategory == null
            ? Collections.<String, String>emptyMap()
            : nameToCategory;
    }

    @Override
    public Decision check(ToolCall call, ToolExecutionContext ctx) {
        String toolName = call.getName();
        Map<String, String> map = nameToCategory == null
            ? Collections.<String, String>emptyMap()
            : nameToCategory;
        String toolCategory = map.get(toolName);
        if (toolCategory == null) {
            // Unknown tool OR provider not wired — default to "local" per
            // Tool.sourceCategory() default method.
            toolCategory = "local";
        }
        List<String> allowList = tools.getAllowList();
        List<String> denyList = tools.getDenyList();

        // Path 1: deny-list — first match wins. Pure defensive check.
        if (denyList != null && !denyList.isEmpty()) {
            for (String pattern : denyList) {
                if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
                    return new Decision.Deny(
                        "[" + PermissionErrorCodes.LINGS_P01 + "] Tool '" + toolName
                            + "' matches deny pattern '" + pattern + "'");
                }
            }
        }

        // Path 2: allow-list empty → default allow (covers zero-config + back-compat).
        if (allowList == null || allowList.isEmpty()) {
            return new Decision.Allow("default policy: allow (no allow-list)");
        }

        // Path 3: allow-list non-empty → first pattern match wins.
        for (String pattern : allowList) {
            if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
                return new Decision.Allow(
                    "strict policy: allow (matches pattern '" + pattern + "')");
            }
        }

        // Path 4: no allow-list pattern matched → Deny with category context.
        return new Decision.Deny(
            "[" + PermissionErrorCodes.LINGS_P01 + "] Tool '" + toolName
                + "' not in allow-list (category=" + toolCategory + ")");
    }
}