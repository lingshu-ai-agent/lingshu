package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.concurrent.CancellationTokens;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import org.reactivestreams.Subscriber;

/**
 * Minimal {@link TurnContext} implementation (Story #001 default, Story #005 cancellation).
 *
 * <p>Holds the {@code done} flag and delegates history mutations to the session.
 * Stories #004 (tools) and #010 (OTel) extend this with cancellation tokens and
 * span correlation.
 *
 * <p><b>🆕 Story #005</b> adds:
 * <ul>
 *   <li>Final {@code cancellation} field + 5-arg constructor (existing 4-arg delegates
 *       with a freshly-created token for back-compat with Story #001 callers)</li>
 *   <li>Static factory {@link #createWithBroadcast} that creates a token AND auto-registers
 *       it with {@code AgentFactory.broadcastRegistry} so Ctrl-C reaches all in-flight turns</li>
 *   <li>{@link #cancellation()} accessor — shared identity with
 *       {@code DefaultToolExecutionContext.cancellation()}</li>
 * </ul>
 */
public class DefaultTurnContext implements TurnContext {

    private final Session session;
    private final AgentConfig config;
    private final Subscriber<? super AgentEvent> sink;
    private final String userInput;
    private final CancellationToken cancellation;
    private volatile boolean done;

    /**
     * Story #001 back-compat constructor — creates a fresh non-broadcast token.
     * Use {@link #createWithBroadcast} in production code paths (DefaultAgent).
     */
    public DefaultTurnContext(Session session, AgentConfig config,
                              Subscriber<? super AgentEvent> sink, String userInput) {
        this(session, config, sink, userInput, CancellationTokens.create());
    }

    /**
     * Story #005 primary constructor — caller supplies the cancellation token
     * (typically from {@code AgentFactory.registerCancellation} flow).
     */
    public DefaultTurnContext(Session session, AgentConfig config,
                              Subscriber<? super AgentEvent> sink, String userInput,
                              CancellationToken cancellation) {
        if (session == null) {
            throw new IllegalArgumentException("session must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (cancellation == null) {
            throw new IllegalArgumentException("cancellation must not be null");
        }
        this.session = session;
        this.config = config;
        this.sink = sink;
        this.userInput = userInput;
        this.cancellation = cancellation;
    }

    /**
     * 🆕 Story #005 — Static factory: create a turn context with a fresh cancellation
     * token AND auto-register it with the {@link AgentFactory} broadcast registry so
     * {@code AgentFactory.broadcastCancel()} reaches this turn on JVM shutdown hook.
     *
     * <p>This is the canonical entry point used by {@code DefaultAgent.buildContext}
     * (FR-011). Tests that don't need broadcast can use the regular constructor instead.
     */
    public static DefaultTurnContext createWithBroadcast(Session session, AgentConfig config,
                                                          Subscriber<? super AgentEvent> sink,
                                                          String userInput) {
        CancellationToken token = CancellationTokens.create();
        AgentFactory.registerCancellationStatic(token);
        return new DefaultTurnContext(session, config, sink, userInput, token);
    }

    @Override public Session session() { return session; }
    @Override public AgentConfig config() { return config; }
    @Override public Subscriber<? super AgentEvent> sink() { return sink; }
    @Override public String userInput() { return userInput; }
    @Override public boolean done() { return done; }
    @Override public void markDone() { this.done = true; }
    @Override public CancellationToken cancellation() { return cancellation; }

    @Override
    public void appendAssistant(String text, Usage usage) {
        if (session instanceof DefaultSession) {
            Message.Assistant a = new Message.Assistant(
                text,
                java.util.Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                ai.lingshu.core.message.StopReason.END_TURN,
                usage);
            ((DefaultSession) session).append(a);
        }
    }

    @Override
    public void appendToolResult(ToolResult result) {
        if (session instanceof DefaultSession) {
            Message.ToolResult tr = new Message.ToolResult(result.getToolUseId(),
                result.getContent(), result.isError());
            ((DefaultSession) session).append(tr);
        }
    }

    @Override
    public void appendSystem(String content, String source) {
        if (session instanceof DefaultSession) {
            ((DefaultSession) session).append(new Message.System(content, source));
        }
    }
}