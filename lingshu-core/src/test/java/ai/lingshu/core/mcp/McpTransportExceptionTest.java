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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — {@link McpTransportException} L1 test (AC-021a-deps-2).
 *
 * <p>Single case verifying the carrier exception preserves code + message.
 */
@DisplayName("Story #021a — McpTransportException contract")
class McpTransportExceptionTest {

    @Test
    @DisplayName("carriesCodeAndMessage")
    void carriesCodeAndMessage() {
        McpTransportException ex = new McpTransportException("LINGS-M01", "SSE not implemented");
        assertThat(ex.getCode()).isEqualTo("LINGS-M01");
        assertThat(ex.getMessage()).isEqualTo("SSE not implemented");
    }
}