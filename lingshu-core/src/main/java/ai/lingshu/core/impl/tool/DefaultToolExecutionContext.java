package ai.lingshu.core.impl.tool;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import org.reactivestreams.Subscriber;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Story #004 — Minimal {@link ToolExecutionContext} adapter wrapping a {@link TurnContext}.
 *
 * <p>Used by {@link ai.lingshu.core.impl.flow.LinearTurnEngine#dispatchWithPolicy} to bridge
 * the per-turn scope ({@link TurnContext}) into the per-call sandbox scope
 * ({@link ToolExecutionContext}) without bringing in the full Sandbox implementation
 * (deferred to Story #016).
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@link #workingDirectory()} — agent working dir (or cwd if null)</li>
 *   <li>{@link #fs()} — default filesystem (no chroot in Story #004)</li>
 *   <li>{@link #http()} — minimal client, no domain whitelist (rejects everything? no — allows all;
 *       Story #016 will add the sandbox whitelist)</li>
 *   <li>{@link #approval()} — denials as errors (Story #005 replaces with real ApprovalGate)</li>
 *   <li>{@link #cancellation()} — flags {@code ctx.done()} as cancellation source</li>
 *   <li>{@link #callConfig()} — sourced from {@link TurnContext#config()}</li>
 * </ul>
 */
public class DefaultToolExecutionContext implements ToolExecutionContext {

    private final TurnContext turnCtx;

    public DefaultToolExecutionContext(TurnContext turnCtx) {
        if (turnCtx == null) {
            throw new IllegalArgumentException("turnCtx must not be null");
        }
        this.turnCtx = turnCtx;
    }

    @Override
    public Session session() { return turnCtx.session(); }

    @Override
    public ToolSink sink() { return new DefaultToolSink(turnCtx.sink()); }

    @Override
    public Path workingDirectory() {
        Path wd = turnCtx.config().getSandbox().getWorkingDirectory();
        return wd != null ? wd : Paths.get(System.getProperty("user.dir"));
    }

    @Override
    public FileSystem fs() { return FileSystems.getDefault(); }

    @Override
    public NetworkClient http() { return new PassThroughHttp(); }

    @Override
    public ApprovalGate approval() {
        // Story #005 will replace with the full ApprovalGate flow that can pause the turn.
        // Story #004 short-circuits to Deny for AskUser (handled inside DefaultToolExecutor).
        return new ApprovalGate() {
            @Override
            public Decision ask(Decision.AskUser ask) {
                return new Decision.Deny("AskUser approval flow is wired in Story #005 follow-up");
            }
        };
    }

    @Override
    public CancellationToken cancellation() {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() { return turnCtx.done(); }
            @Override
            public Runnable onCancel(Runnable callback) {
                // Story #005 will wire up real cancellation; Story #004 no-op
                return new Runnable() { @Override public void run() { /* unregister */ } };
            }
        };
    }

    @Override
    public ToolCallConfig callConfig() {
        return new ToolCallConfig(
            turnCtx.config().getToolTimeoutSeconds(),
            0,    // maxTokens — story #010 OTel budget will source from LLM config
            0);   // maxCostMicros — Story #012 will source from cost config
    }

    // ── Default impls ───────────────────────────────────────────

    /** Two-channel sink that forwards both partial (LLM) and progress (human) events. */
    private static final class DefaultToolSink implements ToolSink {
        private final Subscriber<? super AgentEvent> outer;
        DefaultToolSink(Subscriber<? super AgentEvent> outer) { this.outer = outer; }

        @Override
        public void emitPartial(String partial) {
            if (outer != null) {
                outer.onNext(new AgentEvent.ToolProgress("?", partial));
            }
        }

        @Override
        public void emitProgress(String progress) {
            // Human-facing — not fed to LLM. In Story #001 there's no human UI;
            // we route to debug logging only.
            org.slf4j.LoggerFactory.getLogger(DefaultToolSink.class)
                .debug("tool progress: {}", progress);
        }
    }

    /** Pass-through HTTP client (Story #016 will wrap with domain whitelist). */
    private static final class PassThroughHttp implements NetworkClient {
        @Override
        public String get(String url) throws IOException {
            throw new UnsupportedOperationException(
                "HTTP tool calls are wired in Story #016 (sandbox) — currently unsupported");
        }
        @Override
        public String post(String url, String body) throws IOException {
            throw new UnsupportedOperationException(
                "HTTP tool calls are wired in Story #016 (sandbox) — currently unsupported");
        }
        @Override
        public InputStream getStream(String url) throws IOException {
            throw new UnsupportedOperationException(
                "HTTP tool calls are wired in Story #016 (sandbox) — currently unsupported");
        }
    }
}
