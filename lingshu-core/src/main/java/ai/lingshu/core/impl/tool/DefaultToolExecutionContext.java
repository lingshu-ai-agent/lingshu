package ai.lingshu.core.impl.tool;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.RuntimeSandbox;
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
 * ({@link ToolExecutionContext}). Story #028 promotes this from "minimal adapter" to a
 * real sandbox boundary by delegating {@link #fs()} / {@link #http()} / {@link #process()}
 * to the resolved {@link RuntimeSandbox} (Slot 3 chroot-style default).
 *
 * <p>Two constructors:
 * <ul>
 *   <li>{@link #DefaultToolExecutionContext(TurnContext)} — legacy 1-arg form, kept for
 *       back-compat with Story #005/Story #006 tests that don't need a real sandbox
 *       (e.g. {@code CancellationTokenSharingTest}). Falls back to {@code FileSystems.getDefault()}
 *       and a {@link PassThroughHttp} stub that throws on every call.</li>
 *   <li>{@link #DefaultToolExecutionContext(TurnContext, RuntimeSandbox)} — Story #028 primary
 *       form. {@link #fs()} / {@link #http()} both delegate to the sandbox.
 *       This is what {@code LinearTurnEngine.dispatchWithPolicy} now constructs.</li>
 * </ul>
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@link #workingDirectory()} — agent working dir (or cwd if null)</li>
 *   <li>{@link #fs()} — chrooted FileSystem when sandbox != null; default FS otherwise</li>
 *   <li>{@link #http()} — domain-whitelist enforcing {@code WhitelistedHttpClient} when sandbox != null;
 *       legacy {@link PassThroughHttp} stub otherwise</li>
 *   <li>{@link #approval()} — denials as errors (Story #005 replaces with real ApprovalGate)</li>
 *   <li>{@link #cancellation()} — flags {@code ctx.done()} as cancellation source</li>
 *   <li>{@link #callConfig()} — sourced from {@link TurnContext#config()}</li>
 * </ul>
 *
 * <p>Note: the {@link RuntimeSandbox} interface also exposes {@code process()} (tenant-aware
 * command whitelist runner), but {@link ToolExecutionContext} intentionally does NOT
 * surface that capability — process spawning is reserved for {@code Tool}s that explicitly
 * need it (Bash, etc.), not part of the per-call sandbox envelope.
 */
public class DefaultToolExecutionContext implements ToolExecutionContext {

    private final TurnContext turnCtx;
    /**
     * 🆕 Story #028 — resolved Slot 3 Sandbox. {@code null} only for legacy 1-arg ctor
     * sites (e.g. {@code CancellationTokenSharingTest}); production paths always pass a
     * non-null sandbox so {@link #fs()} / {@link #http()} can actually
     * enforce the boundary (dsh §4.10.1 hard rule 2 pipeline step 4).
     */
    private final RuntimeSandbox runtimeSandbox;

    /**
     * Legacy 1-arg constructor — preserved for back-compat with tests that don't need
     * the real sandbox. Sets {@code runtimeSandbox = null}, so {@link #fs()} falls back to
     * the default FS and {@link #http()} returns the {@link PassThroughHttp} stub.
     */
    public DefaultToolExecutionContext(TurnContext turnCtx) {
        this(turnCtx, null);
    }

    /**
     * 🆕 Story #028 — primary constructor. The {@link RuntimeSandbox} is resolved once
     * by {@code AgentFactory.create()} and passed through {@code DefaultAgent.buildContext}
     * → {@code DefaultTurnContext} → {@code LinearTurnEngine.dispatchWithPolicy} so each
     * {@code Tool.execute(...)} call routes fs / http / process through the sandbox boundary.
     */
    public DefaultToolExecutionContext(TurnContext turnCtx, RuntimeSandbox runtimeSandbox) {
        if (turnCtx == null) {
            throw new IllegalArgumentException("turnCtx must not be null");
        }
        this.turnCtx = turnCtx;
        this.runtimeSandbox = runtimeSandbox;
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
    public FileSystem fs() {
        // 🆕 Story #028 — delegate to the resolved Sandbox so the chroot boundary
        // actually fires per Tool.execute(...). Falls back to the default FS for the
        // legacy 1-arg ctor path (cancellation tests, etc.) where no sandbox exists.
        return (runtimeSandbox != null) ? runtimeSandbox.fs() : FileSystems.getDefault();
    }

    @Override
    public NetworkClient http() {
        // 🆕 Story #028 — delegate to the resolved Sandbox. Empty whitelist → every
        // request denied with [LINGS-S01] (see WhitelistedHttpClient.check). Falls back
        // to the legacy PassThroughHttp stub (throws on every call) when no sandbox.
        return (runtimeSandbox != null) ? runtimeSandbox.http() : new PassThroughHttp();
    }

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
        // 🆕 Story #005 — delegate to TurnContext's real token. Shared identity means
        // Ctrl-C propagated to the turn reaches in-flight Tool polls directly (dsh §14.12
        // Tool layer; US2 S1 — same reference via `==`).
        return turnCtx.cancellation();
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
