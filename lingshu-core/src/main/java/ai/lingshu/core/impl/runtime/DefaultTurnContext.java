package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.concurrent.CancellationTokens;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import org.reactivestreams.Subscriber;

import java.util.List;

/**
 * Minimal {@link TurnContext} implementation (Story #001 default, Story #005 cancellation,
 * Story #028 sandbox-aware).
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
 *
 * <p><b>🆕 Story #028</b> adds:
 * <ul>
 *   <li>Final {@code runtimeSandbox} field + 6-arg constructor (existing 5-arg delegates
 *       with {@code runtimeSandbox = null} for back-compat with Story #005/Story #006 tests)</li>
 *   <li>Static factory {@link #createWithBroadcast} 5-arg overload that wires the sandbox
 *       AND auto-registers the cancellation token</li>
 *   <li>Impl-only accessor {@link #runtimeSandbox()} — {@link TurnContext} interface stays
 *       minimal; {@code LinearTurnEngine.dispatchWithPolicy} casts to this class to read
 *       the sandbox and pass it to {@code DefaultToolExecutionContext}</li>
 * </ul>
 */
public class DefaultTurnContext implements TurnContext {

    private final Session session;
    private final AgentConfig config;
    private final Subscriber<? super AgentEvent> sink;
    private final String userInput;
    private final CancellationToken cancellation;
    /**
     * 🆕 Story #028 — resolved Slot 3 Sandbox. {@code null} only for legacy ctor sites
     * (Story #005 cancellation tests, Story #006 tenant tests, etc.). Production paths
     * always pass a non-null sandbox via {@link #createWithBroadcast} so each
     * {@code Tool.execute(...)} call routes fs / http / process through the boundary.
     */
    private final RuntimeSandbox runtimeSandbox;
    private volatile boolean done;

    /**
     * Story #001 back-compat constructor — creates a fresh non-broadcast token.
     * Use {@link #createWithBroadcast} in production code paths (DefaultAgent).
     */
    public DefaultTurnContext(Session session, AgentConfig config,
                              Subscriber<? super AgentEvent> sink, String userInput) {
        this(session, config, sink, userInput, CancellationTokens.create(), null);
    }

    /**
     * Story #005 back-compat constructor — caller supplies the cancellation token
     * (typically from {@code AgentFactory.registerCancellation} flow). Sets
     * {@code runtimeSandbox = null} for back-compat with Story #005/Story #006 callers.
     */
    public DefaultTurnContext(Session session, AgentConfig config,
                              Subscriber<? super AgentEvent> sink, String userInput,
                              CancellationToken cancellation) {
        this(session, config, sink, userInput, cancellation, null);
    }

    /**
     * 🆕 Story #028 — primary constructor. All 6 fields are final; the TurnContext is
     * immutable for the lifetime of the turn (consistent with §4.1 invariance rule).
     */
    public DefaultTurnContext(Session session, AgentConfig config,
                              Subscriber<? super AgentEvent> sink, String userInput,
                              CancellationToken cancellation, RuntimeSandbox runtimeSandbox) {
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
        this.runtimeSandbox = runtimeSandbox;
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
        return new DefaultTurnContext(session, config, sink, userInput, token, null);
    }

    /**
     * 🆕 Story #028 — Static factory: same as {@link #createWithBroadcast} but wires the
     * resolved {@link RuntimeSandbox} into the turn so {@code LinearTurnEngine.dispatchWithPolicy}
     * can pass it to {@code DefaultToolExecutionContext}. This is the canonical production
     * path used by {@code DefaultAgent.buildContext}.
     */
    public static DefaultTurnContext createWithBroadcast(Session session, AgentConfig config,
                                                          Subscriber<? super AgentEvent> sink,
                                                          String userInput,
                                                          RuntimeSandbox runtimeSandbox) {
        CancellationToken token = CancellationTokens.create();
        AgentFactory.registerCancellationStatic(token);
        return new DefaultTurnContext(session, config, sink, userInput, token, runtimeSandbox);
    }

    @Override public Session session() { return session; }
    @Override public AgentConfig config() { return config; }
    @Override public Subscriber<? super AgentEvent> sink() { return sink; }
    @Override public String userInput() { return userInput; }
    @Override public boolean done() { return done; }
    @Override public void markDone() { this.done = true; }
    @Override public CancellationToken cancellation() { return cancellation; }

    /**
     * 🆕 Story #028 — impl-only accessor for the resolved Slot 3 Sandbox. The
     * {@link TurnContext} interface intentionally does NOT expose this — keeping the
     * interface minimal lets unrelated code stay decoupled from the Sandbox SPI.
     * {@code LinearTurnEngine.dispatchWithPolicy} casts the {@link TurnContext} to this
     * concrete class to read the sandbox before constructing {@code DefaultToolExecutionContext}.
     *
     * @return the resolved {@link RuntimeSandbox}, or {@code null} for legacy 4-arg/5-arg
     *         ctor sites that pre-date Story #028
     */
    public RuntimeSandbox runtimeSandbox() { return runtimeSandbox; }

    /**
     * 🆕 Story #027a — {@code toolCalls} now flows through from the
     * {@link ai.lingshu.core.message.LlmResponse} recorded by
     * {@link ai.lingshu.core.impl.flow.LinearTurnEngine}. Previously
     * (Story #001) the call site hardcoded an empty list, which made
     * the {@code tool_use} round-trip in the ReAct loop impossible.
     *
     * <p>{@code stopReason} is still pinned to {@code END_TURN} at this
     * layer — the {@code Message.Assistant} 4-arg ctor requires it, and the
     * ReAct loop's {@code LinearTurnEngine.last} reference (set immediately
     * before this call) carries the authoritative stop reason for the
     * turn boundary decisions.
     */
    @Override
    public void appendAssistant(String text, List<ToolCall> toolCalls, Usage usage) {
        if (session instanceof DefaultSession) {
            List<ToolCall> effectiveToolCalls = toolCalls != null
                ? toolCalls
                : java.util.Collections.<ToolCall>emptyList();
            Message.Assistant a = new Message.Assistant(
                text,
                effectiveToolCalls,
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