package ai.lingshu.core.impl.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story #027b L1 unit tests for {@link AnthropicStreamEvent#parse(String)}.
 *
 * <p>Two cases cover the two boundary conditions the parser must handle:
 * <ol>
 *   <li>Happy path — a well-formed SSE block with {@code event:} + data
 *       lines returns an instance carrying the raw JSON parsed into a
 *       {@link JsonNode}.</li>
 *   <li>Malformed data line — a {@code data:} line whose accumulated JSON
 *       is unparseable throws {@link RuntimeException} so the SSE reader
 *       can surface a clear protocol violation (rather than silently
 *       dropping the event).</li>
 * </ol>
 */
@DisplayName("Story #027b — AnthropicStreamEvent L1 unit")
class AnthropicStreamEventTest {

    @Test
    @DisplayName("parse: well-formed message_start SSE block → type + JsonNode data")
    void parse_wellFormedMessageStart_returnsTypeAndJsonData() {
        // ── Anthropic message_start example (abridged) ─────────────────
        String rawBlock =
            "event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{"
            + "\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"usage\":{\"input_tokens\":42,\"output_tokens\":0}}}\n"
            + "\n";  // blank line terminator

        AnthropicStreamEvent ev = AnthropicStreamEvent.parse(rawBlock);

        assertThat(ev).isNotNull();
        assertThat(ev.getType()).isEqualTo("message_start");
        assertThat(ev.getData().isObject()).isTrue();
        assertThat(ev.getData().path("type").asText()).isEqualTo("message_start");
        assertThat(ev.getData().path("message").path("id").asText()).isEqualTo("msg_test");
        assertThat(ev.getData().path("message").path("usage").path("input_tokens").asInt())
            .isEqualTo(42);
    }

    @Test
    @DisplayName("parse: malformed JSON in data: line → RuntimeException")
    void parse_malformedJsonInDataLine_throwsRuntimeException() {
        // Trailing comma + unclosed brace → Jackson cannot parse.
        String rawBlock =
            "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":0,}\n"  // bad JSON
            + "\n";

        Throwable thrown = catchThrowable(() -> AnthropicStreamEvent.parse(rawBlock));

        assertThat(thrown).isInstanceOf(RuntimeException.class);
        assertThat(thrown.getMessage()).contains("Failed to parse Anthropic SSE event data JSON");
        assertThat(thrown.getMessage()).contains("content_block_start");
    }
}