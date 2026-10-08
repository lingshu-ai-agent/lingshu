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
package ai.lingshu.core.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #030 — L1 tests for {@link AgentConfig.ToolsConfig#askList} (2 cases).
 */
class AgentConfigAskListTest {

    @Test
    @DisplayName("AC-030-14: defaults_askListIsEmpty")
    void defaults_askListIsEmpty() {
        AgentConfig.ToolsConfig tc = AgentConfig.ToolsConfig.defaults();

        assertThat(tc.getAskList()).isEmpty();
    }

    @Test
    @DisplayName("AC-030-15: explicitAskList_roundTripsThroughGetters")
    void explicitAskList_roundTripsThroughGetters() {
        AgentConfig.ToolsConfig tc = new AgentConfig.ToolsConfig(
            true,
            Arrays.asList("*"),
            Collections.<String>emptyList(),
            Arrays.asList("bash_safe", "write_file"),
            200_000, 1_000_000);

        assertThat(tc.getAskList()).containsExactly("bash_safe", "write_file");
    }
}