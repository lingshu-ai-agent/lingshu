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
package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.event.AgentEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug fix 2026-10-25 — L2 mapper verification for the {@code compacted} SSE payload.
 *
 * <p>{@link AgentEventMapper} used to fabricate the payload (R-1 workaround:
 * {@code afterSize=beforeSize} hardcoded, {@code approxTokensFreed=0} hardcoded),
 * which made every compaction look like a no-op on the wire and misled the
 * 2026-10-25 production debugging session. The engine now measures the real
 * before/after delta and the mapper must forward it <b>verbatim</b>.
 */
@DisplayName("Bug 2026-10-25 — demo-product AgentEventMapper → Compacted payload")
class DemoProductAgentEventMapperCompactedTest {

    private AgentEventMapper mapper;
    private ObjectMapper jackson;

    @BeforeEach
    void setUp() {
        mapper = new AgentEventMapper();
        jackson = new ObjectMapper();
    }

    private JsonNode toJson(AgentEvent event) {
        ObjectNode node = jackson.createObjectNode();
        mapper.toJson(event, node);
        return node;
    }

    @Test
    @DisplayName("compacted_forwardsEngineMeasuredPayloadVerbatim")
    void compacted_forwardsEngineMeasuredPayloadVerbatim() {
        AgentEvent.Compacted event = new AgentEvent.Compacted(107, 42, 9000);

        JsonNode payload = toJson(event);

        assertThat(payload.get("type").asText()).isEqualTo("compacted");
        assertThat(payload.get("beforeSize").asInt())
            .as("beforeSize must come from the event, not a hardcoded baseline")
            .isEqualTo(107);
        assertThat(payload.get("afterSize").asInt())
            .as("afterSize must NOT be a copy of beforeSize (the old R-1 bug)")
            .isEqualTo(42);
        assertThat(payload.get("approxTokensFreed").asInt())
            .as("approxTokensFreed must NOT be hardcoded 0 (the old R-1 bug)")
            .isEqualTo(9000);
    }

    @Test
    @DisplayName("compacted_zeroDeltaStillHonest")
    void compacted_zeroDeltaStillHonest() {
        // A content-only compaction can leave sizes equal — the payload must still
        // say so honestly rather than faking a delta in either direction.
        AgentEvent.Compacted event = new AgentEvent.Compacted(50, 50, 0);

        JsonNode payload = toJson(event);

        assertThat(payload.get("beforeSize").asInt()).isEqualTo(50);
        assertThat(payload.get("afterSize").asInt()).isEqualTo(50);
        assertThat(payload.get("approxTokensFreed").asInt()).isZero();
    }
}
