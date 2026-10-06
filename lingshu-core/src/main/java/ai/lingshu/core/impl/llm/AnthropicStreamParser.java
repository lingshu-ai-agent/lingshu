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
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Value;
import org.reactivestreams.Subscriber;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful consumer of {@link AnthropicStreamEvent}s that turns an Anthropic
 * <code>/v1/messages</code> SSE stream into a {@link LlmResponse} +
 * incremental {@link AgentEvent} emissions (Story #027b, dsh v1.5.45 §6.5 (2)).
 *
 * <p>Designed to handle Anthropic's 6-event SSE protocol with arbitrary
 * <b>interleaving</b> of text and tool_use content blocks. Anthropic may emit
 * blocks in any order, e.g. {@code text → tool_use → text → tool_use}, so the
 * parser keeps a per-index accumulator map rather than assuming a single
 * sequential stream.
 *
 * <p>State machine — see dsh §6.5 SSE protocol state diagram:
 * <table border="1" summary="SSE event handling">
 *   <tr><th>SSE event</th><th>Action</th></tr>
 *   <tr><td>{@code message_start}</td>
 *       <td>Emit {@link AgentEvent.ReasoningStarted}(1, 50); init {@link #usage}
 *           from {@code message.usage.input_tokens} + {@code output_tokens}.</td></tr>
 *   <tr><td>{@code content_block_start} (type=text)</td>
 *       <td>Register a new entry in {@link #textBlocks}.</td></tr>
 *   <tr><td>{@code content_block_start} (type=tool_use)</td>
 *       <td>Defensively validate {@code id} + {@code name} (missing → throws
 *       {@link LingsLlmProviderException} with {@link LlmErrorCodes#LINGS_L01});
 *       register a {@link ToolBlockBuffer} in {@link #toolBlocks}; emit
 *       {@link AgentEvent.ToolStarted}.</td></tr>
 *   <tr><td>{@code content_block_delta} (type=text_delta)</td>
 *       <td>Append text to {@link #textBlocks}{@code [index]}; emit
 *       {@link AgentEvent.TextDelta} per delta for first-token UX.</td></tr>
 *   <tr><td>{@code content_block_delta} (type=input_json_delta)</td>
 *       <td>Append {@code partial_json} to {@link ToolBlockBuffer#inputBuf}.</td></tr>
 *   <tr><td>{@code content_block_stop}</td>
 *       <td>Finalize per-block: parse {@code toolBlocks[index].inputBuf} →
 *       {@link ToolCall} appended to {@link #toolCalls}; or, for text blocks,
 *       copy block content into {@link #textBuf}.</td></tr>
 *   <tr><td>{@code message_delta}</td>
 *       <td>Update {@link #stopReason} from {@code delta.stop_reason} +
 *       refresh {@link #usage.outputTokens} from {@code usage.output_tokens}.</td></tr>
 *   <tr><td>{@code message_stop}</td>
 *       <td>Set {@link #stopReason} to {@link StopReason#END_TURN} if not already
 *       set by {@code message_delta}; mark stream complete so {@link #finish()}
 *       may be called.</td></tr>
 * </table>
 *
 * <p><b>Why not {@code @Value}?</b> — Lombok's {@code @Value} marks every
 * field {@code final}, which is incompatible with the state-machine
 * reassignments {@link #feed} must perform on {@link #usage},
 * {@link #stopReason}, and the {@code finished} flag. The class itself
 * remains {@code public final} (no subclassing), with public getters via
 * Lombok's {@code @Getter}. The Map / List / StringBuilder fields keep
 * their references stable (initialized once in the constructor); only the
 * <i>contents</i> of those collections mutate during {@link #feed}.
 *
 * <p><b>Concurrency</b> — Single-threaded; the SSE reader pushes events
 * sequentially. The caller is expected to invoke {@link #feed} from one
 * thread only (typically the IO executor of {@link AnthropicLlmProvider}).
 *
 * <p><b>JDK 8 compatibility</b> — Plain {@code public final} class with
 * Lombok {@code @Getter}; mutable references are local to the instance.
 * No records / sealed types / {@code var}.
 *
 * @see AnthropicStreamEvent
 * @see AnthropicLlmProvider#stream
 * @since 1.5.45
 */
@Getter
public class AnthropicStreamParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Per-text-block incremental accumulator, keyed by {@code index} from
     * SSE {@code content_block_start.index} / {@code content_block_delta.index}.
     * Allows tracking which block each text chunk belongs to when blocks
     * arrive interleaved. Reference is final; the Map itself is mutated.
     */
    private final Map<Integer, StringBuilder> textBlocks = new HashMap<Integer, StringBuilder>();

    /**
     * Per-tool-use-block accumulator, keyed by SSE {@code index}. Each
     * entry is a {@link ToolBlockBuffer} holding the {@code id}, {@code name},
     * and the running concatenation of {@code input_json_delta.partial_json}
     * fragments (final JSON object is parsed at {@code content_block_stop}).
     * Reference is final; the Map itself is mutated.
     */
    private final Map<Integer, ToolBlockBuffer> toolBlocks = new HashMap<Integer, ToolBlockBuffer>();

    /**
     * Final accumulated text across all text blocks, in arrival order.
     * Populated as {@code content_block_stop} fires for text blocks.
     * Reference is final; the StringBuilder itself is mutated.
     */
    private final StringBuilder textBuf = new StringBuilder();

    /**
     * Completed tool calls, appended in {@code content_block_stop} order.
     * Reference is final; the List itself is mutated.
     */
    private final List<ToolCall> toolCalls = new ArrayList<ToolCall>();

    /**
     * Stop reason, set from {@code message_delta.delta.stop_reason}. Falls
     * back to {@link StopReason#END_TURN} if {@code message_stop} arrives
     * without a prior {@code message_delta}. Mutable.
     */
    private StopReason stopReason = null;

    /**
     * Token accounting, initialized from {@code message_start.message.usage}
     * and updated incrementally by {@code message_delta.usage}. Mutable.
     */
    private Usage usage = Usage.zero();

    /**
     * {@code true} once {@code message_stop} has been observed — gates
     * {@link #finish()} to enforce "stream must terminate before assembling
     * the final response". Mutable.
     */
    private boolean finished = false;

    /**
     * Per-tool-use-block accumulator (private inner POJO). Holds the
     * immutable {@code id} + {@code name} captured at
     * {@code content_block_start} plus the running buffer of
     * {@code input_json_delta.partial_json} fragments that will be parsed
     * into a JSON object at {@code content_block_stop}.
     *
     * <p>{@code @Value}-immutable: the {@code inputBuf} field is
     * default-initialized so the all-args constructor only requires
     * {@code id} + {@code name}; the StringBuilder is mutated in place
     * via {@link #getInputBuf()}.
     */
    @Value
    public static class ToolBlockBuffer {
        /** Tool use id, required by Anthropic protocol — verified at start. */
        String id;
        /** Tool name, required by Anthropic protocol — verified at start. */
        String name;
        /** Running buffer of {@code partial_json} fragments. */
        StringBuilder inputBuf = new StringBuilder();
    }

    /**
     * Feed a single parsed SSE event through the state machine.
     *
     * <p>Each event updates parser state and (when appropriate) emits a
     * downstream {@link AgentEvent} to the supplied {@link Subscriber}.
     * Unknown event types are silently ignored (Anthropic may add new event
     * types without breaking existing clients).
     *
     * <p>Defensive checks raise {@link LingsLlmProviderException} with
     * {@link LlmErrorCodes#LINGS_L01} when a {@code tool_use} start lacks
     * {@code id} or {@code name} (matches {@link AnthropicLlmProvider#parseResponse}
     * behavior in Story #027a).
     *
     * @param event the parsed SSE event; {@code null} (returned by
     *              {@link AnthropicStreamEvent#parse} for empty blocks) is
     *              silently ignored.
     * @param sink  the Reactive Streams subscriber that receives incremental
     *              {@link AgentEvent}s; may be {@code null} for callers that
     *              only care about the final {@link LlmResponse}.
     */
    public void feed(AnthropicStreamEvent event, Subscriber<? super AgentEvent> sink) {
        if (event == null) {
            return;
        }
        String type = event.getType();
        JsonNode data = event.getData();

        if ("message_start".equals(type)) {
            // Payload: {"message":{"usage":{"input_tokens":N,"output_tokens":N},...},"type":"message_start"}
            JsonNode usageNode = data.path("message").path("usage");
            usage = new Usage(
                usageNode.path("input_tokens").asInt(0),
                usageNode.path("output_tokens").asInt(0));
            if (sink != null) {
                sink.onNext(new AgentEvent.ReasoningStarted(1, 50));
            }
        } else if ("content_block_start".equals(type)) {
            int index = data.path("index").asInt(-1);
            JsonNode contentBlock = data.path("content_block");
            String blockType = contentBlock.path("type").asText("");
            if ("text".equals(blockType)) {
                textBlocks.put(index, new StringBuilder());
            } else if ("tool_use".equals(blockType)) {
                String id = contentBlock.path("id").asText("");
                String name = contentBlock.path("name").asText("");
                if (id.isEmpty()) {
                    throw new LingsLlmProviderException(
                        LlmErrorCodes.LINGS_L01,
                        "Anthropic SSE content_block_start.tool_use.id missing — Anthropic protocol violation (every tool_use block must carry an id)");
                }
                if (name.isEmpty()) {
                    throw new LingsLlmProviderException(
                        LlmErrorCodes.LINGS_L01,
                        "Anthropic SSE content_block_start.tool_use.name missing — Anthropic protocol violation (id=" + id + ")");
                }
                toolBlocks.put(index, new ToolBlockBuffer(id, name));
                if (sink != null) {
                    sink.onNext(new AgentEvent.ToolStarted(id, name));
                }
            }
            // Other content_block types (e.g. "thinking", "redacted_thinking")
            // are silently ignored — same forward-compat policy as parseResponse.
        } else if ("content_block_delta".equals(type)) {
            int index = data.path("index").asInt(-1);
            JsonNode delta = data.path("delta");
            String deltaType = delta.path("type").asText("");
            if ("text_delta".equals(deltaType)) {
                String text = delta.path("text").asText("");
                if (!text.isEmpty()) {
                    StringBuilder blockBuf = textBlocks.get(index);
                    if (blockBuf != null) {
                        blockBuf.append(text);
                    }
                    if (sink != null) {
                        sink.onNext(new AgentEvent.TextDelta(text));
                    }
                }
            } else if ("input_json_delta".equals(deltaType)) {
                String partialJson = delta.path("partial_json").asText("");
                if (!partialJson.isEmpty()) {
                    ToolBlockBuffer buf = toolBlocks.get(index);
                    if (buf != null) {
                        buf.getInputBuf().append(partialJson);
                    }
                }
            }
            // Other delta types (signature_delta for thinking blocks, etc.)
            // are silently ignored.
        } else if ("content_block_stop".equals(type)) {
            int index = data.path("index").asInt(-1);
            // Text block: copy block content into overall textBuf.
            StringBuilder blockBuf = textBlocks.remove(index);
            if (blockBuf != null) {
                if (textBuf.length() > 0 && blockBuf.length() > 0) {
                    textBuf.append(' ');
                }
                textBuf.append(blockBuf.toString());
            }
            // Tool-use block: parse accumulated partial_json and finalize.
            ToolBlockBuffer toolBuf = toolBlocks.remove(index);
            if (toolBuf != null) {
                String rawInput = toolBuf.getInputBuf().toString();
                JsonNode inputNode;
                try {
                    if (rawInput.isEmpty()) {
                        inputNode = MAPPER.createObjectNode();  // empty object for no-input tools
                    } else {
                        inputNode = MAPPER.readTree(rawInput);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(
                        "Failed to parse tool_use input JSON for id=" + toolBuf.getId()
                            + " name=" + toolBuf.getName() + ": " + e.getMessage()
                            + "; raw=" + rawInput,
                        e);
                }
                toolCalls.add(new ToolCall(toolBuf.getId(), toolBuf.getName(), inputNode));
            }
        } else if ("message_delta".equals(type)) {
            // Payload: {"delta":{"stop_reason":"end_turn|tool_use|max_tokens"},
            //           "usage":{"output_tokens":N},"type":"message_delta"}
            String sr = data.path("delta").path("stop_reason").asText("");
            if ("tool_use".equalsIgnoreCase(sr)) {
                stopReason = StopReason.TOOL_USE;
            } else if ("max_tokens".equalsIgnoreCase(sr)) {
                stopReason = StopReason.MAX_TOKENS;
            } else if ("end_turn".equalsIgnoreCase(sr)) {
                stopReason = StopReason.END_TURN;
            }
            // Update output_tokens (input_tokens is locked at message_start).
            int newOutputTokens = data.path("usage").path("output_tokens").asInt(-1);
            if (newOutputTokens >= 0) {
                usage = new Usage(usage.getInputTokens(), newOutputTokens);
            }
        } else if ("message_stop".equals(type)) {
            // Anthropic always sends message_delta before message_stop, but
            // be defensive in case the stop_reason was omitted (e.g. unusual
            // proxy behavior). Default to END_TURN.
            if (stopReason == null) {
                stopReason = StopReason.END_TURN;
            }
            finished = true;
        }
        // Other event types (ping, etc.) are silently ignored.
    }

    /**
     * Build the final {@link LlmResponse} from accumulated parser state.
     *
     * <p>Must be called only once {@code message_stop} has been observed
     * (i.e. {@code feed(...)} has been called for the {@code message_stop}
     * event). Calling before {@code message_stop} raises
     * {@link IllegalStateException} to prevent callers from assembling a
     * partial response mid-stream.
     *
     * @return the assembled {@link LlmResponse} — accumulated text, completed
     *         tool calls, stop reason, and token usage.
     * @throws IllegalStateException if invoked before {@code message_stop}
     *                               has been observed via {@link #feed}.
     */
    public LlmResponse finish() {
        if (!finished) {
            throw new IllegalStateException(
                "AnthropicStreamParser.finish() called before message_stop — "
                    + "call feed(message_stop) first to close the stream");
        }
        return new LlmResponse(textBuf.toString(), toolCalls, stopReason, usage);
    }
}