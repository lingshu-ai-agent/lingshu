package ai.lingshu.core.runtime;

import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.Usage;
import lombok.Value;

/**
 * Synchronous terminal result of {@link Agent#runBlocking}. Mirrors the fields of the last
 * emitted {@link ai.lingshu.core.event.AgentEvent.TurnCompleted} event.
 *
 * <p>{@code turns} counts how many user input iterations the agent went — typically 1 for
 * a single Q&amp;A but grows when the agent loops (ReAct on multiple tool calls, multi-step
 * planning, etc.).
 */
@Value
public class RunResult {
    /** Final assistant text (may be empty if the agent stopped after tool calls). */
    String finalText;
    /** Number of user-input iterations completed. */
    int turns;
    /** Aggregate token usage over the whole turn. */
    Usage totalUsage;
    /** Why the turn terminated. */
    StopReason stopReason;
    /** Wall-clock duration of the turn in milliseconds. */
    long elapsedMillis;
}