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

import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — callTool in non-CONNECTED state returns error (not exception)
 * (AC-021a-9 + EC-021a-2). Aligns with dsh §4.10.1 hard rule 2.
 */
@DisplayName("Story #021a — callTool when not CONNECTED returns error")
class StdioMcpServerConnectionCallToolNotConnectedTest {

    @Test
    @DisplayName("callTool in IDLE state returns error result, does not throw")
    void callTool_returnsErrorNotThrows() throws Exception {
        StdioMcpServerConnection conn = new StdioMcpServerConnection(
            McpServerConfig.builder()
                .name("idle")
                .transport(McpTransportType.STDIO)
                .command("true")
                .build());
        // state defaults to IDLE — start() not called
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode input = mapper.createObjectNode();
        input.put("input", "hello");
        McpCallResult result = conn.callTool("echo", input);
        assertThat(result).isNotNull();
        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage()).contains("not connected");
        conn.close();
    }
}