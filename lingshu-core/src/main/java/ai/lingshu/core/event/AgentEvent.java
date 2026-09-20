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
 * <p>Single abstract root + 11 concrete subclasses covers the ReAct loop surface. The two
 * non-trivial ones ({@link ApprovalRequired}, {@link MessageAppended}) use
 * {@code @RequiredArgsConstructor} + {@code @Getter} because they hold either a callback
 * ({@link Consumer}) or a polymorphic {@link Message}, which {@code @Value} cannot encode cleanly.
 */
public abstract class AgentEvent {
    // marker root — all events extend this
}

@Getter
@RequiredArgsConstructor
class TextDelta extends AgentEvent {
    /** Incremental text chunk from the model; consumer appends to its buffer. */
    final String text;
}

@Getter
@RequiredArgsConstructor
class ToolStarted extends AgentEvent {
    /** Mirrors {@code ToolCall.id}. */
    final String toolCallId;
    /** Mirrors {@code ToolCall.name}. */
    final String name;
}

@Getter
@RequiredArgsConstructor
class ToolProgress extends AgentEvent {
    final String toolCallId;
    /** Partial output produced so far (used for streaming read / bash tail). */
    final String partial;
}

@Getter
@RequiredArgsConstructor
class ToolCompleted extends AgentEvent {
    final ToolResult result;
}

@Getter
@RequiredArgsConstructor
class TurnCompleted extends AgentEvent {
    final StopReason reason;
    final Usage usage;
}

/**
 * Engine emitted a {@code Decision.AskUser}; the registered continuation is invoked with the
 * human's eventual answer. Held as a regular class (not {@code @Value}) because
 * {@link Consumer} is a callback, not an immutable field.
 */
@RequiredArgsConstructor
@Getter
class ApprovalRequired extends AgentEvent {
    final Decision.AskUser ask;
    final Consumer<Decision> continuation;
}

/** Engine ran the compactor; subscribers may want to flush UI state. */
class Compacted extends AgentEvent {
    // intentionally empty — presence is the signal
}

@Getter
@RequiredArgsConstructor
class ErrorEvent extends AgentEvent {
    final Throwable error;
}

/** ── ReAct iteration markers (§6.1 LinearTurnEngine) ───────────────── */

@Getter
@RequiredArgsConstructor
class ReasoningStarted extends AgentEvent {
    final int step;
    final int maxSteps;
}

@Getter
@RequiredArgsConstructor
class ObservationAppended extends AgentEvent {
    final int step;
    final int toolResultCount;
}

@Getter
@RequiredArgsConstructor
class MaxStepsExceeded extends AgentEvent {
    final int maxSteps;
    final Usage totalUsage;
}

/** Engine appended a Message to session history (for UI observers that mirror history). */
@Getter
@RequiredArgsConstructor
class MessageAppended extends AgentEvent {
    final Message message;
}