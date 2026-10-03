package ai.lingshu.core.impl.tool;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.permission.PermissionErrorCodes;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

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
 *   <li>{@link #approval()} — {@link DefaultApprovalGate} 真接通 (Story #041),封装 Story #030
 *       inline 路径(emit ApprovalRequired + register ApprovalRegistry + block + 3 error 分支)</li>
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
     * 🆕 Story #041 — Spring-injected {@link ApprovalRegistry} shared with
     * {@code LinearTurnEngine} and the host UI's approval endpoint (e.g. demo-product
     * {@code ChatController.continueApproval}). {@code null} for legacy 1-arg / 2-arg ctor
     * sites (test fixtures); production paths always pass a non-null registry so
     * {@link #approval()} can register the continuation for external round-trip.
     */
    private final ApprovalRegistry approvalRegistry;
    /**
     * 🆕 Story #041 — explicit per-call sink override. When non-null, {@link #sink()}
     * and {@code DefaultApprovalGate} both emit events here instead of the per-turn sink
     * on {@link TurnContext}. Production paths via {@code LinearTurnEngine.dispatchWithPolicy}
     * pass the runTurn sink so events reach the user's SSE stream; legacy / Tool-internal
     * ctor sites leave this {@code null} and fall back to {@code turnCtx.sink()}.
     */
    private final Subscriber<? super AgentEvent> explicitSink;

    /**
     * Legacy 1-arg constructor — preserved for back-compat with tests that don't need
     * the real sandbox. Sets {@code runtimeSandbox = null} and {@code approvalRegistry = null},
     * so {@link #fs()} falls back to the default FS, {@link #http()} returns the
     * {@link PassThroughHttp} stub, and {@link #approval()} falls back to sink-only
     * (no external round-trip).
     */
    public DefaultToolExecutionContext(TurnContext turnCtx) {
        this(turnCtx, null, null);
    }

    /**
     * 🆕 Story #028 — primary constructor (pre-#041). The {@link RuntimeSandbox} is
     * resolved once by {@code AgentFactory.create()} and passed through
     * {@code DefaultAgent.buildContext} → {@code DefaultTurnContext} →
     * {@code LinearTurnEngine.dispatchWithPolicy} so each {@code Tool.execute(...)} call
     * routes fs / http / process through the sandbox boundary. {@code approvalRegistry}
     * defaults to {@code null} (legacy behaviour).
     */
    public DefaultToolExecutionContext(TurnContext turnCtx, RuntimeSandbox runtimeSandbox) {
        this(turnCtx, runtimeSandbox, null);
    }

    /**
     * 🆕 Story #041 — full constructor. {@link #approval()} returns a real
     * {@link DefaultApprovalGate} that registers its continuation in the shared
     * {@link ApprovalRegistry} so an external HTTP endpoint (e.g. demo-product
     * {@code POST /api/approvals/&#123;sessionId&#125;/&#123;approvalId&#125;}) can
     * deliver the human's {@link Decision} back to the engine thread blocked in
     * {@code approval().ask()}. {@code approvalRegistry} may be {@code null} for
     * tests that drive the round-trip directly off the {@code ApprovalRequired} event.
     */
    public DefaultToolExecutionContext(TurnContext turnCtx, RuntimeSandbox runtimeSandbox, ApprovalRegistry approvalRegistry) {
        this(turnCtx, runtimeSandbox, approvalRegistry, null);
    }

    /**
     * 🆕 Story #041 — full constructor with explicit per-call sink. Used by
     * {@code LinearTurnEngine.dispatchWithPolicy} to wire the runTurn sink so emitted
     * events (including the {@link AgentEvent.ApprovalRequired} from DefaultApprovalGate)
     * reach the user's SSE stream rather than the per-turn sink on {@link TurnContext}.
     * {@code explicitSink} may be {@code null} for tests / Tool-internal callers that
     * deliberately want to fall back to {@code turnCtx.sink()}.
     */
    public DefaultToolExecutionContext(TurnContext turnCtx, RuntimeSandbox runtimeSandbox,
                                       ApprovalRegistry approvalRegistry,
                                       Subscriber<? super AgentEvent> explicitSink) {
        if (turnCtx == null) {
            throw new IllegalArgumentException("turnCtx must not be null");
        }
        this.turnCtx = turnCtx;
        this.runtimeSandbox = runtimeSandbox;
        this.approvalRegistry = approvalRegistry;
        this.explicitSink = explicitSink;
    }

    @Override
    public Session session() { return turnCtx.session(); }

    @Override
    public ToolSink sink() {
        // 🆕 Story #041 — prefer the explicit per-call sink when supplied (production
        // paths via LinearTurnEngine.dispatchWithPolicy pass the runTurn sink so events
        // reach the user's SSE stream). Fall back to the per-turn sink from the
        // TurnContext (legacy 1-arg / 2-arg / 3-arg ctor sites, including all Tool-internal
        // callers of ctx.approval().ask()).
        return new DefaultToolSink(explicitSink != null ? explicitSink : turnCtx.sink());
    }

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
        // 🆕 Story #041 — return a real ApprovalGate implementation. The blocking /
        // emit / register / timeout machinery lives in DefaultApprovalGate (this file,
        // private static inner class) — it was extracted from
        // LinearTurnEngine.dispatchWithPolicy L467-559 inline path so the ApprovalGate
        // SPI is now usable from inside a Tool. See specs/019-built-in-tools plan.md
        // L498 for the planned Bash "rm -rf" confirm use case.
        return new DefaultApprovalGate(
            turnCtx,
            cancellation(),
            turnCtx.config().getApprovalTimeoutSeconds(),
            approvalRegistry,
            explicitSink);
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

    /**
     * 🆕 Story #041 — Default {@link ApprovalGate} implementation extracted from
     * {@code LinearTurnEngine.dispatchWithPolicy} L467-559 (where it was inline since
     * Story #030). Blocks the engine thread until the host UI (e.g. demo-product
     * {@code ChatController.continueApproval}) delivers a {@link Decision} back via
     * {@link ApprovalRegistry} → {@link AgentEvent.ApprovalRequired} round-trip.
     *
     * <p>Behaviour matches the prior inline path exactly (verified by AC-041-09 / 10 / 11):
     * <ul>
     *   <li>Generates a fresh UUID {@code approvalId} per call so the host can correlate
     *       an inbound {@code POST /api/approvals/&#123;sessionId&#125;/&#123;approvalId&#125;}
     *       back to the engine thread that's blocked here.</li>
     *   <li>Emits {@link AgentEvent.ApprovalRequired} via {@link TurnContext#sink()} so the
     *       host UI can present the prompt. If the sink is {@code null}, returns Deny with
     *       {@code [LINGS-P02]} immediately (no hang — host can't reach the human).</li>
     *   <li>Registers the continuation in the shared {@link ApprovalRegistry} (if non-null)
     *       so {@code ChatController.continueApproval} can invoke it. Test fixtures with
     *       a null registry drive the round-trip directly off the {@code ApprovalRequired}
     *       event.</li>
     *   <li>{@code approvalTimeoutSeconds = 0} → {@code Long.MAX_VALUE} (wait indefinitely;
     *       matches Claude Code overnight approval — see Story #030 design rationale).</li>
     *   <li>{@code approvalTimeoutSeconds > 0} → at most N seconds; on timeout returns
     *       {@link Decision.Deny} with {@code [LINGS-P02]} embedded.</li>
     *   <li>{@link CancellationToken#fire()} propagates by interrupting the blocked thread
     *       → {@link InterruptedException} → return Deny with {@code [LINGS-P02]}.</li>
     *   <li>{@link AtomicBoolean} guard makes the continuation idempotent so a late or
     *       duplicate answer (e.g. user double-clicks Allow) doesn't NPE on
     *       {@code future.complete} being called twice.</li>
     * </ul>
     *
     * <p>This makes the {@link ApprovalGate} SPI usable from inside a {@link ai.lingshu.core.slot.Tool}
     * — see {@code specs/019-built-in-tools/plan.md} L498 for the planned Bash "rm -rf"
     * confirm use case that this now enables.
     */
    private static final class DefaultApprovalGate implements ApprovalGate {
        private final TurnContext turnCtx;
        private final CancellationToken token;
        private final long timeoutSec;
        private final ApprovalRegistry registry;
        /**
         * 🆕 Story #041 — per-call sink override. When non-null (LinearTurnEngine wiring
         * the runTurn sink), ApprovalRequired events go here instead of {@code turnCtx.sink()}.
         * Null = fall back to the per-turn sink (Tool-internal callers, legacy tests).
         */
        private final Subscriber<? super AgentEvent> explicitSink;

        DefaultApprovalGate(TurnContext turnCtx, CancellationToken token,
                            long timeoutSec, ApprovalRegistry registry,
                            Subscriber<? super AgentEvent> explicitSink) {
            this.turnCtx = turnCtx;
            this.token = token;
            this.timeoutSec = timeoutSec;
            this.registry = registry;
            this.explicitSink = explicitSink;
        }

        @Override
        public Decision ask(Decision.AskUser ask) {
            // 1. Generate approvalId. UUID is sufficient for in-process correlation;
            //    production deployments with cross-process UIs would swap in a longer
            //    opaque token but the SPI shape doesn't change.
            final String approvalId = UUID.randomUUID().toString();

            // 2. CompletableFuture + AtomicBoolean + Consumer continuation. The
            //    AtomicBoolean guards against duplicate answers (e.g. user double-clicks
            //    Allow) so future.complete isn't called twice — the second call is
            //    silently ignored, matching Story #030 inline behaviour.
            final CompletableFuture<Decision> decisionFuture = new CompletableFuture<Decision>();
            final AtomicBoolean alreadyResolved = new AtomicBoolean(false);

            Consumer<Decision> continuation = new Consumer<Decision>() {
                @Override public void accept(Decision decision) {
                    if (alreadyResolved.compareAndSet(false, true)) {
                        decisionFuture.complete(decision);
                    }
                    // else: already resolved — silent drop per Story #030 contract.
                }
            };

            // 3. Register the continuation in the shared registry (if available) so the
            //    host UI's HTTP endpoint can deliver the human's answer back. Tests
            //    with a null registry drive the round-trip directly off the
            //    ApprovalRequired event.
            if (registry != null) {
                registry.register(approvalId, continuation);
            }

            // 3b. 🆕 Story #041 — wire the cancellation token so fire() actually unblocks
            //     the parked engine thread with a Deny[LINGS-P02]. Story #030 inline had a
            //     catch (InterruptedException) branch but never registered an onCancel
            //     callback, so cancel was a silent no-op (test AC-041-04 caught the gap).
            //     The callback competes with the human-continuation via alreadyResolved —
            //     first writer wins; subsequent duplicate fires are silently dropped.
            //     The returned Runnable unregister is intentionally not captured: the
            //     per-turn token becomes unreachable after ask() returns, so the callback
            //     (and the future it closes over) is GC-eligible anyway.
            token.onCancel(new Runnable() {
                @Override public void run() {
                    if (alreadyResolved.compareAndSet(false, true)) {
                        decisionFuture.complete(new Decision.Deny(
                            "[" + PermissionErrorCodes.LINGS_P02 + "] Approval flow interrupted"));
                    }
                }
            });

            // 4. Emit ApprovalRequired event via the per-call sink (LinearTurnEngine wiring)
            //    or the per-turn sink (Tool-internal callers / legacy tests). If both are null
            //    (no subscriber anywhere), fall back to Deny with [LINGS-P02] rather than hanging
            //    forever — the human can't be reached.
            Subscriber<? super AgentEvent> sink = (explicitSink != null) ? explicitSink : turnCtx.sink();
            if (sink != null) {
                sink.onNext(new AgentEvent.ApprovalRequired(ask, continuation, approvalId));
            } else {
                return new Decision.Deny("[" + PermissionErrorCodes.LINGS_P02
                    + "] Approval required but no event sink registered; AskUser cannot be presented to human");
            }

            // 5. Block on the human's answer with a timeout. approvalTimeoutSeconds=0
            //    (the zero-config default) means wait indefinitely so an overnight
            //    approval still works when the human returns the next morning.
            long timeoutMs = (timeoutSec <= 0) ? Long.MAX_VALUE : timeoutSec * 1000L;
            try {
                return decisionFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                return new Decision.Deny("[" + PermissionErrorCodes.LINGS_P02
                    + "] Permission approval timed out after " + timeoutSec
                    + "s (default policy: ask user)");
            } catch (InterruptedException ie) {
                // Re-set the interrupt flag so callers upstream can observe it.
                Thread.currentThread().interrupt();
                return new Decision.Deny("[" + PermissionErrorCodes.LINGS_P02
                    + "] Approval flow interrupted");
            } catch (ExecutionException ee) {
                return new Decision.Deny("[" + PermissionErrorCodes.LINGS_P02
                    + "] Approval flow failed: " + ee.getCause());
            }
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
