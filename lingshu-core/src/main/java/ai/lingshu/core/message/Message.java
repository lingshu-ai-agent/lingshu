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
package ai.lingshu.core.message;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * JDK 8 compatible polymorphic message — sealed semantics via abstract + nested final classes.
 *
 * <p>Four kinds cover the full ReAct loop surface (🆕 v1.5.46 — {@code ToolUse} removed as
 * dead code; see specs/refactor-remove-message-tooluse/spec.md):
 * <ul>
 *   <li>{@code System} — injected by PromptBuilder (5 段装配的 [ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY])</li>
 *   <li>{@code User} — raw input from CLI / HTTP / Skill trigger</li>
 *   <li>{@code Assistant} — model output (text + tool calls + stop reason + usage)</li>
 *   <li>{@code ToolResult} — tool return (id echoed + content + error flag)</li>
 * </ul>
 *
 * <p><b>Note:</b> Tool-call requests are carried inside {@link Assistant#toolCalls}
 * (Anthropic protocol wires {@code tool_use} blocks directly into Assistant messages);
 * no separate {@code Message.ToolUse} subtype exists.
 *
 * <p>Each subtype is immutable; {@code Assistant.timestamp()} is recorded at construction time.
 * Use {@code @RequiredArgsConstructor} + {@code @Getter} rather than {@code @Value} because
 * {@code @Value} makes the class {@code final} and incompatible with subclassing.
 *
 * <h2>Polymorphic JSON round-trip (🆕 Story #014)</h2>
 *
 * <p>{@code @JsonTypeInfo} + {@code @JsonSubTypes} enables Jackson to round-trip
 * a {@code List<Message>} through {@link com.fasterxml.jackson.databind.ObjectMapper}
 * — needed because {@code Checkpoint.history} is a {@code List<Message>} that
 * {@code FileSessionStore} serializes and deserializes. The discriminator is
 * the fully-qualified class name ({@code JsonTypeInfo.Id.CLASS}) — safe because
 * the four concrete subclasses are part of the production binary, not user-
 * supplied types, so the standard Jackson polymorphic validator suffices and
 * there's no attack surface. Field shape stays the same as before; this is
 * purely additive (no SPI or field rename).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS, include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes({
    @JsonSubTypes.Type(value = Message.System.class,      name = "System"),
    @JsonSubTypes.Type(value = Message.User.class,        name = "User"),
    @JsonSubTypes.Type(value = Message.Assistant.class,   name = "Assistant"),
    @JsonSubTypes.Type(value = Message.ToolResult.class,  name = "ToolResult")
})
public abstract class Message {

    /** Role tag — "system" / "user" / "assistant" / "tool_result". */
    public abstract String role();

    /** Wall-clock time the message was created; {@link Instant#EPOCH} for static System. */
    public abstract Instant timestamp();

    // ── System ──────────────────────────────────────────────────────────
    /** Static system block assembled by PromptBuilder (§4.5 5 段装配顺序). */
    @Getter
    public static class System extends Message {
        private final String content;
        /** Origin tag (e.g. {@code "role"}, {@code "instructions"}, {@code "memory"}). */
        private final String source;

        @JsonCreator
        public System(
            @JsonProperty("content") String content,
            @JsonProperty("source") String source) {
            this.content = content;
            this.source = source;
        }

        @Override public String role() { return "system"; }

        @Override public Instant timestamp() { return Instant.EPOCH; }
    }

    // ── User input ───────────────────────────────────────────────────────
    @Getter
    public static class User extends Message {
        private final String content;

        @JsonCreator
        public User(@JsonProperty("content") String content) {
            this.content = content;
        }

        @Override public String role() { return "user"; }

        @Override public Instant timestamp() { return Instant.now(); }
    }

    // ── Assistant (LLM output) ───────────────────────────────────────────
    @Getter
    public static class Assistant extends Message {
        private final String text;
        private final List<ToolCall> toolCalls;
        private final StopReason stopReason;
        private final Usage usage;

        @JsonCreator
        public Assistant(
            @JsonProperty("text") String text,
            @JsonProperty("toolCalls") List<ToolCall> toolCalls,
            @JsonProperty("stopReason") StopReason stopReason,
            @JsonProperty("usage") Usage usage) {
            this.text = text;
            this.toolCalls = toolCalls;
            this.stopReason = stopReason;
            this.usage = usage;
        }

        @Override public String role() { return "assistant"; }

        @Override public Instant timestamp() { return Instant.now(); }
    }

    // ── ToolResult (tool return) ─────────────────────────────────────────
    @Getter
    public static class ToolResult extends Message {
        private final String toolUseId;
        private final String content;
        private final boolean isError;

        @JsonCreator
        public ToolResult(
            @JsonProperty("toolUseId") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("isError") boolean isError) {
            this.toolUseId = toolUseId;
            this.content = content;
            this.isError = isError;
        }

        @Override public String role() { return "tool_result"; }

        @Override public Instant timestamp() { return Instant.now(); }
    }
}