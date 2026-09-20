package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import org.reactivestreams.Subscriber;

/**
 * Minimal {@link TurnContext} implementation (Story #001 default).
 *
 * <p>Holds the {@code done} flag and delegates history mutations to the session.
 * Stories #004 (tools) and #010 (OTel) extend this with cancellation tokens and
 * span correlation.
 */
public class DefaultTurnContext implements TurnContext {

    private final Session session;
    private final AgentConfig config;
    private final Subscriber<? super AgentEvent> sink;
    private final String userInput;
    private volatile boolean done;

    public DefaultTurnContext(Session session, AgentConfig config,
                              Subscriber<? super AgentEvent> sink, String userInput) {
        this.session = session;
        this.config = config;
        this.sink = sink;
        this.userInput = userInput;
    }

    @Override public Session session() { return session; }
    @Override public AgentConfig config() { return config; }
    @Override public Subscriber<? super AgentEvent> sink() { return sink; }
    @Override public String userInput() { return userInput; }
    @Override public boolean done() { return done; }
    @Override public void markDone() { this.done = true; }

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