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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #021a — {@link McpServerConfig} L1 contract tests (AC-021a-1).
 *
 * <p>Four cases:
 * <ul>
 *   <li>{@code builder_minimal_defaultsAreCorrect} — minimal config carries
 *       the documented default values for args/env/heartbeat params.</li>
 *   <li>{@code builder_customHeartbeatValues} — explicit setters override
 *       defaults.</li>
 *   <li>{@code builder_jacksonize_serializationRoundtrip} — JSON
 *       serialization round-trip preserves all fields
 *       (verifies {@code @Jacksonized} is wired).</li>
 *   <li>{@code value_immutable_setterThrows} — Lombok {@code @Value} makes
 *       the class immutable; no setter is generated.</li>
 * </ul>
 */
@DisplayName("Story #021a — McpServerConfig contract")
class McpServerConfigTest {

    @Test
    @DisplayName("builder_minimal_defaultsAreCorrect")
    void builder_minimal_defaultsAreCorrect() {
        McpServerConfig cfg = McpServerConfig.builder()
            .name("github")
            .transport(McpTransportType.STDIO)
            .command("mcp-github")
            .build();
        assertThat(cfg.getName()).isEqualTo("github");
        assertThat(cfg.getTransport()).isEqualTo(McpTransportType.STDIO);
        assertThat(cfg.getCommand()).isEqualTo("mcp-github");
        assertThat(cfg.getArgs()).isEmpty();
        assertThat(cfg.getEnv()).isEmpty();
        assertThat(cfg.getUrl()).isNull();
        assertThat(cfg.getHeartbeatIntervalMs()).isEqualTo(30_000L);
        assertThat(cfg.getHeartbeatTimeoutMs()).isEqualTo(10_000L);
        assertThat(cfg.getReconnectCapMs()).isEqualTo(60_000L);
    }

    @Test
    @DisplayName("builder_customHeartbeatValues")
    void builder_customHeartbeatValues() {
        McpServerConfig cfg = McpServerConfig.builder()
            .name("github")
            .transport(McpTransportType.STDIO)
            .command("mcp-github")
            .heartbeatIntervalMs(50L)
            .heartbeatTimeoutMs(500L)
            .reconnectCapMs(300L)
            .build();
        assertThat(cfg.getHeartbeatIntervalMs()).isEqualTo(50L);
        assertThat(cfg.getHeartbeatTimeoutMs()).isEqualTo(500L);
        assertThat(cfg.getReconnectCapMs()).isEqualTo(300L);
    }

    @Test
    @DisplayName("builder_jacksonize_serializationRoundtrip")
    void builder_jacksonize_serializationRoundtrip() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        McpServerConfig cfg = McpServerConfig.builder()
            .name("github")
            .transport(McpTransportType.STDIO)
            .command("mcp-github")
            .url(null)
            .heartbeatIntervalMs(100L)
            .heartbeatTimeoutMs(200L)
            .reconnectCapMs(300L)
            .build();
        String json = mapper.writeValueAsString(cfg);
        McpServerConfig back = mapper.readValue(json, McpServerConfig.class);
        assertThat(back).isEqualTo(cfg);
    }

    @Test
    @DisplayName("value_immutable_setterThrows")
    void value_immutable_setterThrows() {
        // Lombok @Value generates no setters; reflectively, setName() does not exist.
        assertThatThrownBy(() -> McpServerConfig.class.getMethod("setName", String.class))
            .isInstanceOf(NoSuchMethodException.class);
    }
}