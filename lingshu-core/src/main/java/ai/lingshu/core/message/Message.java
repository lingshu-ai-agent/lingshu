package ai.lingshu.core.message;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * JDK 8 compatible polymorphic message — sealed semantics via abstract + nested final classes.
 *
 * <p>Five kinds cover the full ReAct loop surface:
 * <ul>
 *   <li>{@code System} — injected by PromptBuilder (5 段装配的 [ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY])</li>
 *   <li>{@code User} — raw input from CLI / HTTP / Skill trigger</li>
 *   <li>{@code Assistant} — model output (text + tool calls + stop reason + usage)</li>
 *   <li>{@code ToolUse} — model-requested action (id + name + JSON args), see dsh §4.3</li>
 *   <li>{@code ToolResult} — tool return (id echoed + content + error flag)</li>
 * </ul>
 *
 * <p>Each subtype is immutable; {@code Assistant.timestamp()} is recorded at construction time.
 * Use {@code @RequiredArgsConstructor} + {@code @Getter} rather than {@code @Value} because
 * {@code @Value} makes the class {@code final} and incompatible with subclassing.
 */
public abstract class Message {

    /** Role tag — "system" / "user" / "assistant" / "tool_use" / "tool_result". */
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

    // ── ToolUse (model-requested action) ─────────────────────────────────
    @Getter
    @RequiredArgsConstructor
    public static class ToolUse extends Message {
        private final String id;
        private final String name;
        private final JsonNode input;

        @Override public String role() { return "tool_use"; }

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