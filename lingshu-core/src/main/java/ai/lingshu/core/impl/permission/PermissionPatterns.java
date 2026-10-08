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

import ai.lingshu.core.runtime.AgentConfig;

/**
 * Story #031 — Pattern matching helpers for {@link AgentConfig.ToolsConfig#getAllowList()} /
 * {@link AgentConfig.ToolsConfig#getDenyList()} entries consumed by
 * {@link StrictPermissionPolicy#check}.
 *
 * <p><b>Why a dedicated utility:</b> Story #029 used {@code List#contains} for strict-equals
 * matching. That required operators to enumerate every Tool by name (a 12-line static list
 * for the demo-product: 4 local + 2 MCP + 4 Skill + 1 A2A + 1 Delegate), and any new MCP
 * server / Skill / RemoteAgent required a yml edit (see the Story #029 retrospective note in
 * {@code demo-product/src/main/resources/application.yml} lines 72-80).
 *
 * <p>Story #031 adds three pattern forms, evaluated by {@link #matches(String, String, String)}:
 * <ul>
 *   <li><b>{@code "*"}</b> — match any tool (allow all / deny all).</li>
 *   <li><b>{@code "<category>:*"}</b> — match all tools in the given source category
 *       (e.g. {@code "mcp:*"} matches {@code "echo"}, {@code "timestamp"} etc.; values come
 *       from {@link ai.lingshu.core.slot.Tool#sourceCategory()}).</li>
 *   <li><b>{@code "<exact-name>"}</b> — strict-equals match against {@link ai.lingshu.core.slot.Tool#name()}
 *       (back-compat with Story #029: existing yml entries that happen to be exact names
 *       continue to work).</li>
 * </ul>
 *
 * <p><b>Priority / ordering:</b> there is no priority within a single pattern list. All entries
 * are evaluated as OR; the first match wins. To enforce "deny wins over allow", the policy
 * evaluates the deny-list before the allow-list (see {@link StrictPermissionPolicy}).
 *
 * <p><b>Pure JDK 8 String ops</b> — no regex, no glob library. Patterns are interpreted
 * purely with {@link String#equals}, {@link String#endsWith}, {@link String#startsWith},
 * {@link String#substring}. This is mandated by Story #031's R-13 mitigation (d) —
 * {@code dependency:tree} diff vs the Story #029 baseline must show 0 binary delta, i.e.
 * the new code reuses already-locked JDK 8 / Spring / Lombok / Jackson only.
 *
 * <p><b>Thread-safety:</b> this is a stateless utility; all methods are static and pure.
 */
public final class PermissionPatterns {

    private PermissionPatterns() {
        // no instances
    }

    /**
     * Returns whether {@code pattern} matches the given tool.
     *
     * <p>Three pattern forms (in evaluation order — first non-null return wins):
     * <ol>
     *   <li>{@code "*"} → returns {@code true}.</li>
     *   <li>{@code "<category>:*"} → returns {@code true} iff the suffix is exactly
     *       {@code ":*"} AND the prefix {@link String#equals} {@code toolCategory}.</li>
     *   <li>Otherwise → returns {@code true} iff the pattern {@link String#equals}
     *       {@code toolName} (strict-equals, no wildcards).</li>
     * </ol>
     *
     * @param toolName     the tool's {@link ai.lingshu.core.slot.Tool#name()}; must not be null
     *                     (callers are the strict policy which reads from {@code ToolCall.name()})
     * @param toolCategory the tool's {@link ai.lingshu.core.slot.Tool#sourceCategory()}; must not be null
     *                     (callers fall back to {@code "local"} when the tool is not in the
     *                     precomputed category map — see {@link StrictPermissionPolicy#check})
     * @param pattern      the pattern from the allow-list / deny-list; must not be null
     * @return {@code true} if the pattern matches this tool; {@code false} otherwise
     * @throws IllegalArgumentException if {@code pattern} is {@code null}; {@code toolName} /
     *                                  {@code toolCategory} nulls are tolerated and treated as
     *                                  empty strings (defensive: pattern-match on an unknown
     *                                  tool never silently allows it)
     */
    public static boolean matches(String toolName, String toolCategory, String pattern) {
        if (pattern == null) {
            throw new IllegalArgumentException("pattern must not be null");
        }
        String tn = toolName == null ? "" : toolName;
        String tc = toolCategory == null ? "" : toolCategory;

        // Form 1: literal "*"
        if ("*".equals(pattern)) {
            return true;
        }

        // Form 2: "<category>:*"
        if (pattern.endsWith(":*")) {
            // pattern.length() >= 2 (at least "<x>:*" → 3 chars); split at last index of ':'
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (prefix.isEmpty()) {
                // pattern is exactly ":*" (no category) → not a valid category prefix
                return false;
            }
            return prefix.equals(tc);
        }

        // Form 3: exact name (back-compat with Story #029 字面 equals)
        return pattern.equals(tn);
    }
}