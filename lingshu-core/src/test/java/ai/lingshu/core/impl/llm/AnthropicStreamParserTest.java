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
package ai.lingshu.core.impl.llm;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.StopReason;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story #027b L1 unit tests for {@link AnthropicStreamParser} (AC-NN-2—
 * AC-NN-6 + AC-NN-8 — six test cases + two private helper methods).
 *
 * <p>Each test feeds a hand-crafted sequence of {@link AnthropicStreamEvent}
 * payloads through {@link AnthropicStreamParser#feed} and asserts on either
 * the captured {@link AgentEvent}s (incremental emission) or the final
 * {@link LlmResponse} from {@link AnthropicStreamParser#finish}.
 */
@DisplayName("Story #027b — AnthropicStreamParser L1 unit")
class AnthropicStreamParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Test helpers ────────────────────────────────────────────────────

    /** Build an arbitrary SSE event with the given type and JSON data payload. */
    private static AnthropicStreamEvent buildEvent(String type, String dataJson) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", type);
        // Merge `dataJson` into root — caller may have used a different builder,
        // but for our tests the data is always an object whose root has `"type"`.
        // For simplicity we wrap the caller-provided JSON verbatim into {"type":...,"payload":...}
        root.put("payload", dataJson);
        return new AnthropicStreamEvent(type, root);
    }

    /** Build a {@code message_start} event with the given input_tokens. */
    private static AnthropicStreamEvent buildMessageStartEvent(int inputTokens) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "message_start");
        ObjectNode msg = root.putObject("message");
        msg.put("id", "msg_test");
        msg.put("type", "message");
        msg.put("role", "assistant");
        ObjectNode usage = msg.putObject("usage");
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", 0);
        return new AnthropicStreamEvent("message_start", root);
    }

    /** Build a {@code content_block_start} event for a text block. */
    private static AnthropicStreamEvent buildContentBlockStartText(int index) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "content_block_start");
        root.put("index", index);
        ObjectNode cb = root.putObject("content_block");
        cb.put("type", "text");
        cb.put("text", "");
        return new AnthropicStreamEvent("content_block_start", root);
    }

    /** Build a {@code content_block_start} event for a tool_use block. */
    private static AnthropicStreamEvent buildContentBlockStartToolUse(int index, String id, String name) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "content_block_start");
        root.put("index", index);
        ObjectNode cb = root.putObject("content_block");
        cb.put("type", "tool_use");
        if (id != null) cb.put("id", id);
        if (name != null) cb.put("name", name);
        return new AnthropicStreamEvent("content_block_start", root);
    }

    /** Build a {@code content_block_delta} event of type {@code text_delta}. */
    private static AnthropicStreamEvent buildTextDelta(int index, String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "content_block_delta");
        root.put("index", index);
        ObjectNode delta = root.putObject("delta");
        delta.put("type", "text_delta");
        delta.put("text", text);
        return new AnthropicStreamEvent("content_block_delta", root);
    }

    /** Build a {@code content_block_delta} event of type {@code input_json_delta}. */
    private static AnthropicStreamEvent buildInputJsonDelta(int index, String partialJson) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "content_block_delta");
        root.put("index", index);
        ObjectNode delta = root.putObject("delta");
        delta.put("type", "input_json_delta");
        delta.put("partial_json", partialJson);
        return new AnthropicStreamEvent("content_block_delta", root);
    }

    /** Build a {@code content_block_stop} event. */
    private static AnthropicStreamEvent buildContentBlockStop(int index) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "content_block_stop");
        root.put("index", index);
        return new AnthropicStreamEvent("content_block_stop", root);
    }

    /** Build a {@code message_delta} event with stop_reason + output_tokens. */
    private static AnthropicStreamEvent buildMessageDelta(String stopReason, Integer outputTokens) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "message_delta");
        ObjectNode delta = root.putObject("delta");
        delta.put("stop_reason", stopReason);
        ObjectNode usage = root.putObject("usage");
        if (outputTokens != null) usage.put("output_tokens", outputTokens);
        return new AnthropicStreamEvent("message_delta", root);
    }

    /** Build a {@code message_stop} event. */
    private static AnthropicStreamEvent buildMessageStop() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "message_stop");
        return new AnthropicStreamEvent("message_stop", root);
    }

    // ── AC-NN-2: message_start emits ReasoningStarted + init usage ──────

    @Test
    @DisplayName("AC-NN-2: messageStartEmitsReasoningStartedAndInitUsage")
    void messageStartEmitsReasoningStartedAndInitUsage() {
        AnthropicStreamParser parser = new AnthropicStreamParser();
        CapturingSubscriber sink = new CapturingSubscriber();

        parser.feed(buildMessageStartEvent(42), sink);

        // ReasoningStarted(step=1, maxSteps=50) emitted exactly once.
        assertThat(sink.events()).hasSize(1);
        AgentEvent ev0 = sink.events().get(0);
        assertThat(ev0).isInstanceOf(AgentEvent.ReasoningStarted.class);
        assertThat(((AgentEvent.ReasoningStarted) ev0).getStep()).isEqualTo(1);
        assertThat(((AgentEvent.ReasoningStarted) ev0).getMaxSteps()).isEqualTo(50);

        // Usage initialised from message_start.message.usage.
        assertThat(parser.getUsage().getInputTokens()).isEqualTo(42);
        assertThat(parser.getUsage().getOutputTokens()).isEqualTo(0);
    }

    // ── AC-NN-3: text_delta accumulates + emits per delta ───────────────

    @Test
    @DisplayName("AC-NN-3: textDeltaAccumulatesAndEmitsPerDelta")
    void textDeltaAccumulatesAndEmitsPerDelta() {
        AnthropicStreamParser parser = new AnthropicStreamParser();
        CapturingSubscriber sink = new CapturingSubscriber();

        parser.feed(buildContentBlockStartText(0), null);
        parser.feed(buildTextDelta(0, "Hello "), sink);
        parser.feed(buildTextDelta(0, "world"), sink);
        parser.feed(buildContentBlockStop(0), null);
        parser.feed(buildMessageDelta("end_turn", 11), null);
        parser.feed(buildMessageStop(), null);

        // Two TextDelta events emitted (one per delta).
        long textDeltaCount = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.TextDelta)
            .count();
        assertThat(textDeltaCount).isEqualTo(2);
        assertThat(((AgentEvent.TextDelta) sink.events().get(0)).getText()).isEqualTo("Hello ");
        assertThat(((AgentEvent.TextDelta) sink.events().get(1)).getText()).isEqualTo("world");

        // Final assembled text is the concatenation.
        LlmResponse resp = parser.finish();
        assertThat(resp.getText()).isEqualTo("Hello world");
        assertThat(resp.getStopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(resp.getUsage().getOutputTokens()).isEqualTo(11);
    }

    // ── AC-NN-4: input_json_delta concatenates + parses at content_block_stop ─

    @Test
    @DisplayName("AC-NN-4: inputJsonDeltaConcatenatesAndParsesAtStop")
    void inputJsonDeltaConcatenatesAndParsesAtStop() {
        AnthropicStreamParser parser = new AnthropicStreamParser();
        CapturingSubscriber sink = new CapturingSubscriber();

        parser.feed(buildContentBlockStartToolUse(0, "tu_1", "read_file"), sink);
        parser.feed(buildInputJsonDelta(0, "{\"path\":\"/"), null);
        parser.feed(buildInputJsonDelta(0, "tmp/y"), null);
        parser.feed(buildInputJsonDelta(0, "\"}"), null);
        parser.feed(buildContentBlockStop(0), null);
        parser.feed(buildMessageDelta("tool_use", 7), null);
        parser.feed(buildMessageStop(), null);

        // ToolStarted emitted on content_block_start(tool_use event).
        long toolStartedCount = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ToolStarted)
            .count();
        assertThat(toolStartedCount).isEqualTo(1);
        AgentEvent.ToolStarted ts = (AgentEvent.ToolStarted) sink.events().get(0);
        assertThat(ts.getToolCallId()).isEqualTo("tu_1");
        assertThat(ts.getName()).isEqualTo("read_file");

        // ToolCall assembled with the merged partial_json as parsed input.
        LlmResponse resp = parser.finish();
        assertThat(resp.getToolCalls()).hasSize(1);
        assertThat(resp.getToolCalls().get(0).getId()).isEqualTo("tu_1");
        assertThat(resp.getToolCalls().get(0).getName()).isEqualTo("read_file");
        assertThat(resp.getToolCalls().get(0).getInput().path("path").asText()).isEqualTo("/tmp/y");
        assertThat(resp.getStopReason()).isEqualTo(StopReason.TOOL_USE);
    }

    // ── AC-NN-5: multi-block interleaved state machine ─────────────────

    @Test
    @DisplayName("AC-NN-5: multiBlockInterleavedStateMachine")
    void multiBlockInterleavedStateMachine() {
        AnthropicStreamParser parser = new AnthropicStreamParser();
        CapturingSubscriber sink = new CapturingSubscriber();

        // Sequence: text → tool_use → text → tool_use
        // (Anthropic may emit blocks in any order.)
        parser.feed(buildContentBlockStartText(0), null);
        parser.feed(buildTextDelta(0, "Looking at "), sink);
        parser.feed(buildContentBlockStop(0), null);

        parser.feed(buildContentBlockStartToolUse(1, "tu_a", "bash"), sink);
        parser.feed(buildInputJsonDelta(1, "{\"cmd\":\"ls\"}"), null);
        parser.feed(buildContentBlockStop(1), null);

        parser.feed(buildContentBlockStartText(2), null);
        parser.feed(buildTextDelta(2, "and "), sink);
        parser.feed(buildTextDelta(2, "done."), sink);
        parser.feed(buildContentBlockStop(2), null);

        parser.feed(buildContentBlockStartToolUse(3, "tu_b", "read_file"), sink);
        parser.feed(buildInputJsonDelta(3, "{\"path\":\"/etc/hosts\"}"), null);
        parser.feed(buildContentBlockStop(3), null);

        parser.feed(buildMessageDelta("tool_use", 14), null);
        parser.feed(buildMessageStop(), null);

        LlmResponse resp = parser.finish();

        // 2 tool calls assembled in stop-order (index 1, then index 3).
        assertThat(resp.getToolCalls()).hasSize(2);
        assertThat(resp.getToolCalls().get(0).getId()).isEqualTo("tu_a");
        assertThat(resp.getToolCalls().get(0).getName()).isEqualTo("bash");
        assertThat(resp.getToolCalls().get(0).getInput().path("cmd").asText()).isEqualTo("ls");
        assertThat(resp.getToolCalls().get(1).getId()).isEqualTo("tu_b");
        assertThat(resp.getToolCalls().get(1).getName()).isEqualTo("read_file");

        // Text blocks concatenated in order (block 0 + " and done.").
        assertThat(resp.getText()).isEqualTo("Looking at  and done.");
        assertThat(resp.getStopReason()).isEqualTo(StopReason.TOOL_USE);
    }

    // ── AC-NN-6: defensive L01 check on missing tool_use id ─────────────

    @Test
    @DisplayName("AC-NN-6: missingToolUseIdThrowsL01")
    void missingToolUseIdThrowsL01() {
        AnthropicStreamParser parser = new AnthropicStreamParser();
        // Build a tool_use content_block_start with empty id.
        AnthropicStreamEvent badEvent = buildContentBlockStartToolUse(0, "", "read_file");

        Throwable thrown = catchThrowable(() -> parser.feed(badEvent, null));

        assertThat(thrown).isInstanceOf(LingsLlmProviderException.class);
        assertThat(thrown.getMessage()).contains("LINGS-L01");
        assertThat(thrown.getMessage()).contains("tool_use.id missing");
    }

    // ── AC-NN-8: finish() before message_stop throws IllegalStateException ─

    @Test
    @DisplayName("AC-NN-8: finishBeforeMessageStopThrowsIllegalState")
    void finishBeforeMessageStopThrowsIllegalState() {
        AnthropicStreamParser parser = new AnthropicStreamParser();
        // No message_stop fed → finished flag still false.

        Throwable thrown = catchThrowable(() -> parser.finish());

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(thrown.getMessage()).contains("AnthropicStreamParser.finish() called before message_stop");
    }
}