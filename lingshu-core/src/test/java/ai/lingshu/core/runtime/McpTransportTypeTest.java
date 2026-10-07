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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — McpTransportType enum contract test (L1, AC-021a-deps-1).
 *
 * <p>Single black-box case asserting the three values exist in the documented
 * order. Order is part of the contract because downstream code may rely on
 * {@code values()[0]} being {@code STDIO}.
 */
@DisplayName("Story #021a — McpTransportType contract")
class McpTransportTypeTest {

    @Test
    @DisplayName("values() returns [STDIO, SSE, STREAMABLE_HTTP] in declared order")
    void assertThreeValuesInOrder() {
        McpTransportType[] vs = McpTransportType.values();
        assertThat(vs).hasSize(3);
        assertThat(vs[0]).isEqualTo(McpTransportType.STDIO);
        assertThat(vs[1]).isEqualTo(McpTransportType.SSE);
        assertThat(vs[2]).isEqualTo(McpTransportType.STREAMABLE_HTTP);
    }
}