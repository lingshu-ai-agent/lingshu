package ai.lingshu.core.runtime;

import ai.lingshu.core.event.AgentEvent;
import org.reactivestreams.Publisher;

/**
 * Session-scoped agent instance (dsh §4.12.3). Built by {@code AgentFactory.create(...)}
 * and held by user code for the lifetime of a multi-turn conversation.
 *
 * <p>Three execution paths:
 * <ul>
 *   <li>{@link #run} — reactive, returns the {@link AgentEvent} stream for subscribers</li>
 *   <li>{@link #runBlocking} — sync, returns the {@link RunResult} terminal value</li>
 *   <li>{@link #continueWithUserMessage} — for Skill-triggered continuation; does not open
 *       a new session, just appends a synthetic User message and runs another turn</li>
 * </ul>
 *
 * <p>Multi-turn sessions call {@code run} repeatedly; a fresh Agent means a fresh session
 * (no shared history across instances). See dsh §7.1 — AgentFactory is the Spring singleton
 * that produces these prototype-like agents.
 */
public interface Agent {

    /** Session backing this agent. */
    Session session();

    /** Immutable config snapshot for the turn(s) this agent will run. */
    AgentConfig config();

    /**
     * Begin a turn with {@code userInput}. The returned {@link Publisher} subscribes lazily —
     * the actual turn starts when the first {@code request(1)} signal arrives.
     */
    Publisher<AgentEvent> run(String userInput);

    /**
     * Sync convenience — subscribes to {@link #run}, collects to a {@link RunResult}, and
     * returns. {@code turnTimeoutSeconds = 0} means "no timeout".
     */
    RunResult runBlocking(String userInput);

    /**
     * Inject {@code content} as a synthetic User message and continue the turn.
     * Used by Skill triggers ({@code /xxx}) to feed results back without opening a new session.
     */
    Publisher<AgentEvent> continueWithUserMessage(String content);

    /**
     * 🆕 Story #020c — Sync wrapper for {@link #continueWithUserMessage}.
     *
     * <p>Subscribes to the reactive {@code Publisher} and collects events to a
     * {@link RunResult}, blocking the calling thread until the turn completes
     * (or {@code turnTimeoutSeconds} elapses — see {@code AgentConfig.getTurnTimeoutSeconds()}).
     *
     * <p><b>For CLI {@code /xxx} dispatch</b> (dsh §6.4 L4266-4267): the Skill tool
     * result is wrapped as a synthetic User message, fed to this method, and the
     * final {@link RunResult} is returned to the CLI caller.
     *
     * <p>Implementation mirrors {@link #runBlocking} — same {@code AtomicReference +
     * CountDownLatch + Subscriber<AgentEvent>} template.
     */
    RunResult continueWithUserMessageBlocking(String content);
}