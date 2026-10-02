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

    /** Engine ran the compactor; subscribers may want to flush UI state. */
    public static class Compacted extends AgentEvent {
        // intentionally empty — presence is the signal
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