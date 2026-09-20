package ai.lingshu.core.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import org.reactivestreams.Subscriber;

/**
 * Per-turn runtime state shared by every Slot the engine invokes (dsh §4.12.1).
 *
 * <p>Distinct from {@link ai.lingshu.core.slot.ToolExecutionContext}:
 * <ul>
 *   <li>{@code TurnContext} — spans the whole turn (engine-wide)</li>
 *   <li>{@code ToolExecutionContext} — issued by the Sandbox per tool call (per-call)</li>
 * </ul>
 *
 * <p>Created by {@code FlowEngine.runTurn}; held by reference throughout the turn.
 * Append methods are synchronized on the underlying session to prevent the compactor
 * and concurrent tool results from racing.
 */
public interface TurnContext {

    /** Session this turn belongs to (history + id + fork / checkpoint). */
    Session session();

    /** Immutable config snapshot for this turn. */
    AgentConfig config();

    /** Reactive subscriber receiving {@link AgentEvent} instances. */
    Subscriber<? super AgentEvent> sink();

    /** Raw user input for this turn. */
    String userInput();

    /** True once {@link #markDone} has been called (cancel / error / etc.). */
    boolean done();

    /** Set {@code done = true}. The {@code FlowEngine} polls this each loop iteration. */
    void markDone();

    /** Append an assistant message (model output) to history. */
    void appendAssistant(String text, Usage usage);

    /** Append a tool result message to history. */
    void appendToolResult(ToolResult result);

    /**
     * Insert a system message at the head of history (used by the compactor to inject
     * its summary so the model sees it as the most recent context).
     */
    void appendSystem(String content, String source);
}