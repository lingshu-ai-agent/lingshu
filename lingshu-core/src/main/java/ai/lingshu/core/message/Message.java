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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

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
 */
public abstract class Message {

    /** Role tag — "system" / "user" / "assistant" / "tool_result". */
    public abstract String role();

    /** Wall-clock time the message was created; {@link Instant#EPOCH} for static System. */
    public abstract Instant timestamp();

    // ── System ──────────────────────────────────────────────────────────
    /** Static system block assembled by PromptBuilder (§4.5 5 段装配顺序). */
    @Getter
    @RequiredArgsConstructor
    public static class System extends Message {
        private final String content;
        /** Origin tag (e.g. {@code "role"}, {@code "instructions"}, {@code "memory"}). */
        private final String source;

        @Override public String role() { return "system"; }

        @Override public Instant timestamp() { return Instant.EPOCH; }
    }

    // ── User input ───────────────────────────────────────────────────────
    @Getter
    @RequiredArgsConstructor
    public static class User extends Message {
        private final String content;

        @Override public String role() { return "user"; }

        @Override public Instant timestamp() { return Instant.now(); }
    }

    // ── Assistant (LLM output) ───────────────────────────────────────────
    @Getter
    @RequiredArgsConstructor
    public static class Assistant extends Message {
        private final String text;
        private final List<ToolCall> toolCalls;
        private final StopReason stopReason;
        private final Usage usage;

        @Override public String role() { return "assistant"; }

        @Override public Instant timestamp() { return Instant.now(); }
    }

    // ── ToolResult (tool return) ─────────────────────────────────────────
    @Getter
    @RequiredArgsConstructor
    public static class ToolResult extends Message {
        private final String toolUseId;
        private final String content;
        private final boolean isError;

        @Override public String role() { return "tool_result"; }

        @Override public Instant timestamp() { return Instant.now(); }
    }
}