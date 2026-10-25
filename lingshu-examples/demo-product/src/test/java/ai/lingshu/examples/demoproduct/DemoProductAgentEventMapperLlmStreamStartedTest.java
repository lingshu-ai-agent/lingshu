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
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #050 — L2 mapper blackbox verification for the new
 * {@link AgentEvent.LlmStreamStarted} SSE event payload. Demo-product
 * {@link AgentEventMapper} (L114-122) emits {@code {type:"llm.stream.started",
 * streamId, maxOutputTokens}} — the per-stream header that replaces the old
 * reasoning.start step counter reset on every Anthropic {@code message_start}.
 *
 * <p>This test is <b>stateless</b>: it does not bootstrap the full Spring Boot
 * context, only exercises {@link AgentEventMapper#toJson(AgentEvent, ObjectNode)}
 * directly. The mapper is stateless (the old Compacted R-1 size side-channel was
 * removed in the 2026-10-25 payload fix — {@code AgentEvent.Compacted} now carries
 * real before/after numbers), so no baseline reset is needed for isolation.
 *
 * <p><b>Story #050 contract verified by AC-050-14 / AC-050-15 / AC-050-16:</b>
 * <ul>
 *   <li><b>AC-050-14</b> — mapper emits {@code "type":"llm.stream.started"} for
 *       {@link AgentEvent.LlmStreamStarted} (distinct from reasoning.start —
 *       no step counter leaked into this event);</li>
 *   <li><b>AC-050-15</b> — mapper preserves the Anthropic message_id verbatim
 *       into the SSE {@code streamId} field (UI uses it for message bubble id);</li>
 *   <li><b>AC-050-16</b> — mapper forwards {@code maxOutputTokens=0} sentinel
 *       (Anthropic puts {@code max_tokens} in the request not the response —
 *       UI must treat 0 as "unknown");</li>
 * </ul>
 *
 * <p>Also verifies that the OLD reasoning.start path (Bug-1's source) is still
 * emitted by {@link AgentEvent.ReasoningStarted} — Bug-1 fix replaces the
 * provider-side reset, not the ReAct step counter that {@code LinearTurnEngine}
 * genuinely emits once per step.
 */
@DisplayName("Story #050 — demo-product AgentEventMapper → LlmStreamStarted payload")
class DemoProductAgentEventMapperLlmStreamStartedTest {

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

    // ====================================================================
    // AC-050-14 — mapper emits "llm.stream.started" type (NOT "reasoning.start")
    // ====================================================================

    /**
     * AC-050-14 — Bug-1 fix end-to-end: the mapper translates
     * {@link AgentEvent.LlmStreamStarted} into a {@code "llm.stream.started"}
     * SSE event, NOT a {@code "reasoning.start"} event. The pre-Story #050
     * code would have wrapped {@code message_start} into
     * {@code "reasoning.start"} (with hardcoded step=1, maxSteps=50), which
     * was the root cause of the SSE step counter reset bug.
     */
    @Test
    @DisplayName("AC-050-14: llmStreamStarted_mapsTo_llmStreamStartedType_notReasoningStart")
    void llmStreamStarted_mapsTo_llmStreamStartedType_notReasoningStart() {
        AgentEvent.LlmStreamStarted event = new AgentEvent.LlmStreamStarted(
            "msg_test_ac050_14",
            0  // sentinel for "max_tokens not in SSE response"
        );

        JsonNode payload = toJson(event);

        // Type MUST be the new llm.stream.started, NOT reasoning.start.
        assertThat(payload.get("type").asText())
            .as("LlmStreamStarted must map to a distinct 'llm.stream.started' SSE type "
                + "— NOT 'reasoning.start' (that was Bug-1's root cause)")
            .isEqualTo("llm.stream.started");
    }

    // ====================================================================
    // AC-050-15 — mapper preserves Anthropic message_id verbatim into streamId
    // ====================================================================

    /**
     * AC-050-15 — Stream ID wire-through. The mapper forwards the
     * Anthropic {@code data.message.id} value (which carries the actual
     * provider-side identifier like {@code "msg_test_ac050_15"}) directly
     * into the SSE {@code streamId} field. The frontend uses this for the
     * chat bubble id — verification proves the mapper preserves the wire
     * value <em>verbatim</em> (no truncation, no hash, no prefix).
     */
    @Test
    @DisplayName("AC-050-15: llmStreamStarted_preservesStreamIdVerbatim")
    void llmStreamStarted_preservesStreamIdVerbatim() {
        AgentEvent.LlmStreamStarted event = new AgentEvent.LlmStreamStarted(
            "msg_test_ac050_15",
            0
        );

        JsonNode payload = toJson(event);

        assertThat(payload.get("streamId").asText())
            .as("Anthropic message_id must be passed through verbatim into "
                + "SSE streamId field — UI uses this as the chat bubble id")
            .isEqualTo("msg_test_ac050_15");
    }

    // ====================================================================
    // AC-050-16 — mapper forwards maxOutputTokens=0 sentinel (Anthropic quirk)
    // ====================================================================

    /**
     * AC-050-16 — {@code maxOutputTokens=0} sentinel wire-through. Anthropic
     * SSE protocol puts {@code max_tokens} in the <em>request</em> (not in
     * the streaming response). The {@link AgentEvent.LlmStreamStarted}
     * therefore carries {@code maxOutputTokens=0} as a sentinel for "unknown"
     * — the mapper must pass this 0 through verbatim so the UI can render
     * "?" or fall back to a default without seeing a bogus integer.
     */
    @Test
    @DisplayName("AC-050-16: llmStreamStarted_forwardsMaxOutputTokensZeroSentinel")
    void llmStreamStarted_forwardsMaxOutputTokensZeroSentinel() {
        AgentEvent.LlmStreamStarted event = new AgentEvent.LlmStreamStarted(
            "msg_test_ac050_16",
            0  // sentinel = "max_tokens not in SSE response"
        );

        JsonNode payload = toJson(event);

        assertThat(payload.get("maxOutputTokens").asLong())
            .as("maxOutputTokens=0 sentinel must pass through verbatim — "
                + "this is a UI-side 'unknown' signal, not a missing field")
            .isEqualTo(0L);
    }

    /**
     * AC-050-16 (defensive variant) — When a future provider actually
     * populates max_output_tokens (e.g. OpenAI or future Anthropic API
     * extension), the mapper must pass the real value through, NOT clamp
     * it to 0 or synthesize a default.
     */
    @Test
    @DisplayName("AC-050-16: llmStreamStarted_forwardsRealMaxOutputTokensWhenPresent")
    void llmStreamStarted_forwardsRealMaxOutputTokensWhenPresent() {
        AgentEvent.LlmStreamStarted event = new AgentEvent.LlmStreamStarted(
            "msg_test_ac050_16b",
            8192  // realistic Anthropic max_tokens from request
        );

        JsonNode payload = toJson(event);

        assertThat(payload.get("maxOutputTokens").asLong())
            .as("When a provider actually populates max_output_tokens in the "
                + "stream, the mapper must pass it through unchanged — no "
                + "clamping, no synthesis of a default")
            .isEqualTo(8192L);
    }

    // ====================================================================
    // Bug-1 regression guard — reasoning.start path still works
    // ====================================================================

    /**
     * <b>Bug-1 regression guard.</b> Story #050 was SPECIFICALLY about
     * avoiding the step-counter reset bug. The fix <em>replaced</em> the
     * provider-side reset behavior (now LlmStreamStarted), but the engine
     * still emits {@link AgentEvent.ReasoningStarted} once per ReAct step
     * from {@code LinearTurnEngine}. This test verifies the mapper still
     * produces the legacy {@code "reasoning.start"} payload so the UI
     * step counter keeps working unchanged.
     */
    @Test
    @DisplayName("Bug-1 regression guard: reasoningStarted_unaffectedByBug1Fix")
    void reasoningStarted_unaffectedByBug1Fix() {
        AgentEvent.ReasoningStarted event = new AgentEvent.ReasoningStarted(3, 10);

        JsonNode payload = toJson(event);

        assertThat(payload.get("type").asText())
            .as("Engine's genuine ReAct step counter must STILL map to "
                + "'reasoning.start' — Story #050 only changed the SSE-on-"
                + "message_start path, not the engine step counter")
            .isEqualTo("reasoning.start");
        assertThat(payload.get("step").asInt()).isEqualTo(3);
        assertThat(payload.get("maxSteps").asInt()).isEqualTo(10);
    }

    // ====================================================================
    // Defensive — mapper does NOT cross-contaminate with other event types
    // ====================================================================

    /**
     * Defensive case — verify the {@code llm.stream.started} JSON shape is
     * <em>only</em> emitted when input is an {@link AgentEvent.LlmStreamStarted}.
     * Cross-check against {@link AgentEvent.TurnCompleted}: a different event
     * type must NOT accidentally satisfy the {@code instanceof}
     * {@code LlmStreamStarted} chain (catches a regression where a too-broad
     * {@code else if} condition would leak the streaming payload into other
     * events).
     */
    @Test
    @DisplayName("Defensive: turnCompleted_doesNotLeakIntoLlmStreamStartedMapping")
    void turnCompleted_doesNotLeakIntoLlmStreamStartedMapping() {
        AgentEvent.TurnCompleted turnEvent = new AgentEvent.TurnCompleted(
            StopReason.END_TURN,
            Usage.zero()
        );

        JsonNode payload = toJson(turnEvent);

        assertThat(payload.get("type").asText())
            .as("A TurnCompleted event must NEVER map to 'llm.stream.started' — "
                + "defensive regression guard against an over-broad instanceof chain")
            .isNotEqualTo("llm.stream.started");
    }
}
