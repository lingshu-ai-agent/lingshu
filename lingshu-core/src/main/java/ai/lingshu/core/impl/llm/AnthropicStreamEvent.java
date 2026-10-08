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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;

/**
 * Immutable event parsed from a single Anthropic <code>/v1/messages</code>
 * Server-Sent Events (SSE) block (Story #027b, dsh v1.5.45 §6.5 (2)).
 *
 * <p>Anthropic's streaming response is a sequence of newline-delimited SSE
 * events. Each event block has the shape:
 * <pre>
 * event: &lt;type&gt;
 * data: { ... JSON payload ... }
 *                          &lt;-- blank line marks block end
 * </pre>
 *
 * <p>Anthropic emits exactly <b>6 distinct event types</b> during a streaming
 * turn (see dsh §6.5 SSE protocol table):
 * <ol>
 *   <li>{@code message_start} — fires once at the start of the response;
 *       payload includes the full {@code message} object plus initial
 *       {@code usage} ({@code input_tokens} / {@code output_tokens}).</li>
 *   <li>{@code content_block_start} — fires at the start of each content
 *       block within the assistant turn; payload carries {@code index} plus a
 *       {@code content_block} sub-object describing whether the block is
 *       {@code text} or {@code tool_use} (and the {@code id} / {@code name}
 *       for tool_use).</li>
 *   <li>{@code content_block_delta} — fires for each incremental chunk of a
 *       block; payload carries {@code index} and a {@code delta} sub-object.
 *       For text blocks the delta is {@code {"type":"text_delta","text":"..."}};
 *       for tool_use blocks it is
 *       {@code {"type":"input_json_delta","partial_json":"..."}}.</li>
 *   <li>{@code content_block_stop} — fires once per content block when the
 *       block is fully delivered; payload carries {@code index}.</li>
 *   <li>{@code message_delta} — fires once at the end of the turn with the
 *       final {@code stop_reason} and updated {@code usage} counters.</li>
 *   <li>{@code message_stop} — fires once as the final terminator of the
 *       stream; payload is empty (just the {@code type} field).</li>
 * </ol>
 *
 * <p>This class is the parser-layer DTO; the state machine that consumes
 * these events lives in {@link AnthropicStreamParser}. Together they replace
 * Story #001's non-streaming {@code POST → readAll} path with a true SSE
 * pipeline that supports first-token emission (LLM NFR §14.15.1 P50 ≤ 1.5 s)
 * and incremental {@code input_json_delta} buffering for tool_use blocks.
 *
 * <p><b>JDK 8 compatibility</b> — {@code @Value}-immutable POJO with two
 * {@code final} fields. No records / sealed types / {@code var}.
 *
 * @see AnthropicStreamParser
 * @since 1.5.45
 */
@Value
public class AnthropicStreamEvent {

    /**
     * SSE event type — one of {@code message_start}, {@code content_block_start},
     * {@code content_block_delta}, {@code content_block_stop}, {@code message_delta},
     * {@code message_stop}. Empty string when the SSE block had no
     * {@code event:} line (defensive default — Anthropic always emits one
     * in practice but we never assume it).
     */
    String type;

    /**
     * Parsed JSON payload of the SSE block. {@code MissingNode} (never
     * {@code null}) when the block had no {@code data:} line, so downstream
     * code can call {@code .path(...)} without null checks.
     */
    JsonNode data;

    /**
     * Parse a single Anthropic SSE block into an {@link AnthropicStreamEvent}.
     *
     * <p>The block is split on newlines; the {@code event:} line yields
     * {@link #type}, {@code data:} lines are concatenated (each preserving
     * its raw form so multi-line JSON remains parseable), and the first blank
     * line ends the block. After the blank line any further lines are
     * ignored (they belong to the next event block).
     *
     * <p>Returns {@code null} if the block has no payload at all (purely
     * whitespace / empty input) — callers should treat that as a no-op
     * "heartbeat" / keep-alive line and skip it.
     *
     * @param rawSseBlock the raw SSE block, terminated by a blank line.
     *                    May contain trailing newlines from the reader.
     * @return parsed event, or {@code null} if the block has neither an
     *         {@code event:} nor a {@code data:} line.
     * @throws RuntimeException if a {@code data:} line is present but its
     *         accumulated JSON fails to parse.
     */
    public static AnthropicStreamEvent parse(String rawSseBlock) {
        if (rawSseBlock == null || rawSseBlock.trim().isEmpty()) {
            return null;
        }

        String type = "";
        StringBuilder dataBuf = new StringBuilder();
        boolean sawAny = false;

        // Split on \n and walk line-by-line. SSE lines use LF terminators
        // (CRLF is normalized to LF by the SSE spec but most servers emit LF
        // directly); for our purposes splitting on \n is sufficient because
        // we only care about the prefix ("event:" / "data:") and not the
        // exact line-ending semantics.
        String[] lines = rawSseBlock.split("\n", -1);
        for (String rawLine : lines) {
            // Trim trailing \r if a CRLF terminator slipped through.
            String line = rawLine.endsWith("\r")
                ? rawLine.substring(0, rawLine.length() - 1)
                : rawLine;

            if (line.isEmpty()) {
                // Blank line marks the end of an event block. Anything
                // further belongs to the next block — but since parse()
                // operates on a single block, we stop accumulating here.
                // (Defensive: a trailing newline produces one extra empty
                // iteration which we just ignore.)
                break;
            }
            if (line.startsWith("event:")) {
                type = line.substring("event:".length()).trim();
                sawAny = true;
            } else if (line.startsWith("data:")) {
                // Concatenate raw JSON fragments. Anthropic may emit a single
                // data: line per event block; multiple data: lines per block
                // are spec-allowed and must be joined with a newline to
                // preserve any embedded newlines in the JSON.
                String fragment = line.substring("data:".length());
                // Skip the single leading space the SSE spec mandates
                // ("data: " prefix). If absent (some non-conforming servers),
                // accept the fragment verbatim.
                if (fragment.startsWith(" ")) {
                    fragment = fragment.substring(1);
                }
                if (dataBuf.length() > 0) {
                    dataBuf.append('\n');
                }
                dataBuf.append(fragment);
                sawAny = true;
            }
            // Other SSE field types (id:, retry:, comment lines starting with
            // ":") are ignored — Anthropic does not use them.
        }

        if (!sawAny) {
            return null;
        }

        ObjectMapper mapper = new ObjectMapper();
        JsonNode dataNode;
        String rawJson = dataBuf.toString();
        if (rawJson.isEmpty()) {
            // No data: line — return MissingNode so callers can .path(...) safely.
            dataNode = mapper.createObjectNode();  // empty object; treated as "no data"
        } else {
            try {
                dataNode = mapper.readTree(rawJson);
            } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to parse Anthropic SSE event data JSON (type=" + type + "): "
                        + e.getMessage() + "; raw=" + rawJson,
                    e);
            }
        }

        return new AnthropicStreamEvent(type, dataNode);
    }
}