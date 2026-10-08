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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Story #021a — EC-021a-1: invalid command schedules RECONNECTING.
 */
@DisplayName("Story #021a — start with invalid command (EC)")
class StdioMcpServerConnectionStartErrorTest {

    private StdioMcpServerConnection conn;

    @AfterEach
    void tearDown() {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("invalidCommand: start() → state moves to RECONNECTING")
    void invalidCommand_schedulesReconnect() {
        conn = new StdioMcpServerConnection(
            McpServerConfig.builder()
                .name("bad")
                .transport(McpTransportType.STDIO)
                .command("this-command-does-not-exist-1234567890")
                .heartbeatIntervalMs(100L)
                .heartbeatTimeoutMs(200L)
                .reconnectCapMs(100L)
                .build());
        conn.start();
        await().atMost(Duration.ofSeconds(3)).until(() -> conn.state() == ConnectionState.RECONNECTING);
        assertThat(conn.state()).isEqualTo(ConnectionState.RECONNECTING);
    }
}