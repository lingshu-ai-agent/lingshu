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
package ai.lingshu.core.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — {@link McpServerConnection} L1 contract test (AC-021a-3).
 *
 * <p>Single case asserting the interface declares exactly the eight expected
 * abstract methods. Catches accidental signature drift early.
 */
@DisplayName("Story #021a — McpServerConnection contract")
class McpServerConnectionContractTest {

    @Test
    @DisplayName("interface declares 8 expected abstract methods")
    void interfaceHasEightMethods() {
        Method[] methods = McpServerConnection.class.getDeclaredMethods();
        Set<String> names = new HashSet<>();
        for (Method m : methods) {
            names.add(m.getName());
        }
        assertThat(names).contains(
            "name", "state", "lastHeartbeatAt", "listTools",
            "callTool", "onStateChange", "start", "close");
        // exactly 8 declared methods on this interface
        assertThat(methods.length).isEqualTo(8);
    }
}