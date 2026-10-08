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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #031 — L1 unit tests for {@link PermissionPatterns} (6 AC + 2 EC = 8 cases).
 *
 * <p>Verifies the three pattern forms:
 * <ul>
 *   <li>{@code "*"} — match any tool</li>
 *   <li>{@code "<category>:*"} — match by source category</li>
 *   <li>{@code "<exact-name>"} — strict-equals (back-compat with Story #029)</li>
 * </ul>
 */
class PermissionPatternsTest {

    // ─── AC-NN-1: 3-class pattern matching ────────────────────────────────

    @Test
    @DisplayName("AC-031-1: wildcard '*' matches any tool")
    void wildcard_matchesAnyTool() {
        assertThat(PermissionPatterns.matches("read_file", "local", "*")).isTrue();
        assertThat(PermissionPatterns.matches("echo", "mcp", "*")).isTrue();
        assertThat(PermissionPatterns.matches("anything", "skill", "*")).isTrue();
    }

    @Test
    @DisplayName("AC-031-2: exact-name pattern matches strict-equals (Story #029 back-compat)")
    void exactName_matchesStrictEquals() {
        assertThat(PermissionPatterns.matches("read_file", "local", "read_file")).isTrue();
        assertThat(PermissionPatterns.matches("read_file", "mcp", "read_file")).isTrue();
    }

    @Test
    @DisplayName("AC-031-3: exact-name pattern does NOT match a different tool name")
    void exactName_doesNotMatchDifferentName() {
        assertThat(PermissionPatterns.matches("read_file", "local", "write_file")).isFalse();
    }

    @Test
    @DisplayName("AC-031-4: category-prefix 'mcp:*' matches MCP tools")
    void categoryPrefix_mcp_matchesMcpTools() {
        assertThat(PermissionPatterns.matches("echo", "mcp", "mcp:*")).isTrue();
        assertThat(PermissionPatterns.matches("timestamp", "mcp", "mcp:*")).isTrue();
        // Namespaced MCP tool (Story #021b) — "github:search_repos" in mcp category
        assertThat(PermissionPatterns.matches("github:search_repos", "mcp", "mcp:*")).isTrue();
    }

    @Test
    @DisplayName("AC-031-5: category-prefix does NOT match a tool in a different category")
    void categoryPrefix_doesNotMatchDifferentCategory() {
        assertThat(PermissionPatterns.matches("read_file", "local", "mcp:*")).isFalse();
        assertThat(PermissionPatterns.matches("echo", "mcp", "skill:*")).isFalse();
    }

    @Test
    @DisplayName("AC-031-6: category-prefix 'skill:*' matches Skill tools")
    void categoryPrefix_skill_matchesSkillTools() {
        assertThat(PermissionPatterns.matches("agent", "skill", "skill:*")).isTrue();
        assertThat(PermissionPatterns.matches("help", "skill", "skill:*")).isTrue();
        assertThat(PermissionPatterns.matches("clear", "skill", "skill:*")).isTrue();
        assertThat(PermissionPatterns.matches("compact", "skill", "skill:*")).isTrue();
    }

    // ─── EC: edge cases ────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-031-1: null pattern throws IllegalArgumentException")
    void nullPattern_throwsIAE() {
        assertThatThrownBy(() -> PermissionPatterns.matches("any", "local", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("pattern");
    }

    @Test
    @DisplayName("EC-031-2: empty pattern does NOT match anything (returns false)")
    void emptyPattern_doesNotMatch() {
        assertThat(PermissionPatterns.matches("read_file", "local", "")).isFalse();
        assertThat(PermissionPatterns.matches("any", "any", "")).isFalse();
    }
}