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
package ai.lingshu.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #023 — L1 Unit tests for {@link SubAgentType} (dsh v1.5.40 §6.6 L5033-5052).
 *
 * <p>Three cases covering:
 * <ul>
 *   <li>US1 — {@link SubAgentType#allKeys()} size invariant + content</li>
 *   <li>US2 — {@link SubAgentType#fromKey(String)} happy path for each value</li>
 *   <li>US3 — {@link SubAgentType#fromKey(String)} failure on unknown / null key</li>
 * </ul>
 */
class SubAgentTypeTest {

    @Test
    @DisplayName("AC-023-SAT-1: allKeys_sizeIs3_andContainsExpectedConfigKeys")
    void allKeys_sizeIs3_andContainsExpectedConfigKeys() {
        Set<String> keys = SubAgentType.allKeys();
        // dsh §6.6 L5039-5046 — closed set of three values mirroring Claude Code fixed types
        assertThat(keys).hasSize(3);
        assertThat(keys).containsExactlyInAnyOrder("explore", "engineer", "reviewer");
        // Each value has a non-null configKey + promptFile pair
        for (SubAgentType t : SubAgentType.values()) {
            assertThat(t.configKey()).isNotBlank();
            assertThat(t.promptFile()).isNotBlank();
            assertThat(t.key()).isEqualTo(t.configKey());
        }
    }

    @Test
    @DisplayName("AC-023-SAT-2: fromKey_eachValidKey_returnsMatchingEnumValue")
    void fromKey_eachValidKey_returnsMatchingEnumValue() {
        assertThat(SubAgentType.fromKey("explore")).isEqualTo(SubAgentType.EXPLORE);
        assertThat(SubAgentType.fromKey("engineer")).isEqualTo(SubAgentType.ENGINEER);
        assertThat(SubAgentType.fromKey("reviewer")).isEqualTo(SubAgentType.REVIEWER);
    }

    @Test
    @DisplayName("AC-023-SAT-3: fromKey_unknownOrNull_throwsIAE_withKnownKeysListed")
    void fromKey_unknownOrNull_throwsIAE_withKnownKeysListed() {
        assertThatThrownBy(() -> SubAgentType.fromKey("unknown"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown subagent_type: unknown")
            .hasMessageContaining("explore")
            .hasMessageContaining("engineer")
            .hasMessageContaining("reviewer");

        assertThatThrownBy(() -> SubAgentType.fromKey(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown subagent_type: null");

        // Case-sensitive — uppercase variant must NOT match (configKeys are exact-match)
        assertThatThrownBy(() -> SubAgentType.fromKey("EXPLORE"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown subagent_type: EXPLORE");
    }
}
