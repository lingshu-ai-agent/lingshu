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
package ai.lingshu.core.event;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.function.Consumer;

/**
 * Reactive Streams event surfaced from {@code FlowEngine.runTurn} to {@code Agent.run}.
 *
 * <p>Single abstract root + 12 nested static subclasses covers the ReAct loop surface.
 * Use as {@code AgentEvent.TextDelta} / {@code AgentEvent.TurnCompleted} etc.
 *
 * <p>Two non-trivial ones ({@link ApprovalRequired}, {@link MessageAppended}) use
 * {@code @RequiredArgsConstructor} + {@code @Getter} because they hold either a callback
 * ({@link Consumer}) or a polymorphic {@link Message}, which {@code @Value} cannot encode cleanly.
 */
public abstract class AgentEvent {

    private AgentEvent() {
        // sealed marker — only nested subclasses may extend
    }

    @Getter
    @RequiredArgsConstructor
    public static class TextDelta extends AgentEvent {
        /** Incremental text chunk from the model; consumer appends to its buffer. */
        private final String text;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ToolStarted extends AgentEvent {
        /** Mirrors {@code ToolCall.id}. */
        private final String toolCallId;
        /** Mirrors {@code ToolCall.name}. */
        private final String name;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ToolProgress extends AgentEvent {
        private final String toolCallId;
        /** Partial output produced so far (used for streaming read / bash tail). */
        private final String partial;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ToolCompleted extends AgentEvent {
        private final ToolResult result;
    }

    @Getter
    @RequiredArgsConstructor
    public static class TurnCompleted extends AgentEvent {
        private final StopReason reason;
        private final Usage usage;
    }

    /**
     * Engine emitted a {@code Decision.AskUser}; the registered continuation is invoked with the
     * human's eventual answer. Held as a regular class (not {@code @Value}) because
     * {@link Consumer} is a callback, not an immutable field.
     *
     * <p><b>🆕 Story #030 — {@link #approvalId} field added</b>: a stable per-approval
     * UUID emitted alongside the {@link Decision.AskUser} so the host UI (e.g. demo-product
     * {@code ChatController}) can correlate an inbound {@code POST /api/approvals/&#123;sessionId&#125;/&#123;approvalId&#125;}
     * with the pending continuation. Without this identifier the host has no way to look up the
     * right {@code Consumer<Decision>} to invoke when the human clicks Allow/Deny.
     *
     * <p>3-arg constructor (added by Story #030) — the 2-arg constructor still exists
     * for back-compat with tests / fixtures that don't need the correlation id (it
     * defaults to {@code null}). Production paths always go through the 3-arg ctor
     * via {@code LinearTurnEngine.dispatchWithPolicy()}.
     */
    @RequiredArgsConstructor
    @Getter
    public static class ApprovalRequired extends AgentEvent {
        private final Decision.AskUser ask;
        private final Consumer<Decision> continuation;
        /**
         * 🆕 Story #030 — stable UUID for this approval request. The host UI echoes
         * it back when delivering the human's answer so the engine can match the
         * continuation to the right pending approval. May be {@code null} only in
         * tests / fixtures that bypass the engine wiring.
         */
        private final String approvalId;

        /**
         * Back-compat 2-arg constructor for tests / fixtures (Story #030 pre-update sites).
         * Delegates to the 3-arg constructor with {@code approvalId = null}.
         */
        public ApprovalRequired(Decision.AskUser ask, Consumer<Decision> continuation) {
            this(ask, continuation, null);
        }
    }

    /**
     * Engine ran the compactor; subscribers may want to flush UI state.
     *
     * <p>🆕 Bug fix 2026-10-25 — <b>payload added</b>. The pre-payload class was
     * "presence is the signal", which forced host UIs to invent the before/after
     * numbers: demo-product's {@code R-1} workaround hardcoded {@code afterSize =
     * beforeSize} and {@code approxTokensFreed = 0} on the SSE wire, so every
     * compaction looked like a no-op (observed in production as
     * {@code compacted {beforeSize:107, afterSize:107, approxTokensFreed:0}} right
     * before a related protocol error). The engine now measures the session history
     * around {@code compactor.compact(ctx)} and reports real numbers.
     *
     * <p>Field semantics:
     * <ul>
     *   <li>{@link #beforeSize} / {@link #afterSize} — session-history <b>message
     *       counts</b> before / after compaction;</li>
     *   <li>{@link #approxTokensFreed} — conservative (chars/4) token-estimate
     *       delta, clamped {@code >= 0}. Non-zero even when the message count is
     *       unchanged (content-only ToolResult truncation shrinks tokens in place).</li>
     * </ul>
     */
    @Getter
    @RequiredArgsConstructor
    public static class Compacted extends AgentEvent {
        private final int beforeSize;
        private final int afterSize;
        private final int approxTokensFreed;

        /**
         * Back-compat no-arg constructor for tests / fixtures (mirrors the
         * {@link ApprovalRequired} 2-arg back-compat pattern) — zeroed payload.
         * Production paths always go through the 3-arg ctor via
         * {@code LinearTurnEngine.runTurn()}.
         */
        public Compacted() {
            this(0, 0, 0);
        }
    }

    @Getter
    @RequiredArgsConstructor
    public static class ErrorEvent extends AgentEvent {
        private final Throwable error;
    }

    /** ── ReAct iteration markers (§6.1 LinearTurnEngine) ───────────────── */

    @Getter
    @RequiredArgsConstructor
    public static class ReasoningStarted extends AgentEvent {
        private final int step;
        private final int maxSteps;
    }

    /**
     * 🆕 Story #050 — LLM stream started. Carries the provider-side stream id
     * (Anthropic {@code message_id}, OpenAI response id, ...) and the
     * configured max-output-tokens so the SSE UI can render a coherent header
     * at the start of each LLM stream <b>without coupling to the ReAct step
     * counter</b> (which is a separate event).
     *
     * <p><b>Bug-1 root cause fixed in Story #050</b>: the parser used to emit
     * {@link ReasoningStarted} with hard-coded {@code (1, 50)} on every LLM
     * {@code message_start}, which reset the UI {@code step} counter to 1 on
     * every turn iteration (saw {@code step=22 → step=1 → step=24} jumps).
     * ReAct step counter is now emitted exactly once per ReAct step from
     * {@code LinearTurnEngine.runTurn}, decoupled from provider stream events.
     */
    @Getter
    @RequiredArgsConstructor
    public static class LlmStreamStarted extends AgentEvent {
        /** Provider-side stream id (Anthropic {@code message_id} / OpenAI response id). */
        private final String streamId;
        /** Provider-configured max output tokens for this stream. */
        private final int maxOutputTokens;
    }

    @Getter
    @RequiredArgsConstructor
    public static class ObservationAppended extends AgentEvent {
        private final int step;
        private final int toolResultCount;
    }

    @Getter
    @RequiredArgsConstructor
    public static class MaxStepsExceeded extends AgentEvent {
        private final int maxSteps;
        private final Usage totalUsage;
    }

    /** Engine appended a Message to session history (for UI observers that mirror history). */
    @Getter
    @RequiredArgsConstructor
    public static class MessageAppended extends AgentEvent {
        private final Message message;
    }
}