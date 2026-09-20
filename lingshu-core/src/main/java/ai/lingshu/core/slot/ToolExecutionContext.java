package ai.lingshu.core.slot;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.runtime.Session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.Path;

/**
 * Sandbox-issued execution credential handed to a {@link Tool} for the duration of a single call.
 *
 * <p>Scope is one {@code Tool.execute} invocation — broader than the turn-scoped
 * {@link ai.lingshu.core.runtime.TurnContext}. The two contexts serve different layers:
 * <ul>
 *   <li>{@code TurnContext} — the whole turn (engine-wide state)</li>
 *   <li>{@code ToolExecutionContext} — sandbox-derived per-call safety envelope</li>
 * </ul>
 */
public interface ToolExecutionContext {

    /** Session the call belongs to (history + config + id). */
    Session session();

    /** Streamed progress sink — {@code emitPartial} feeds the model, {@code emitProgress} feeds humans. */
    ToolSink sink();

    /** Working directory after sandbox chroot; tools resolve relative paths against this. */
    Path workingDirectory();

    /** Bounded filesystem; out-of-bounds reads throw {@code AccessDeniedException}. */
    FileSystem fs();

    /** Bounded HTTP client; off-whitelist domains throw {@code AccessDeniedException}. */
    NetworkClient http();

    /** Channel for tool-internal human-in-the-loop questions (Bash command, WebFetch to a new domain). */
    ApprovalGate approval();

    /** Cancellation token — fires on Ctrl+C / engine.markDone / timeout cascade. */
    CancellationToken cancellation();

    /** Per-call configuration (timeout, token budget, cost cap). */
    ToolCallConfig callConfig();

    /** Two-channel sink: text deltas for the model, human-readable progress for logs / UI. */
    interface ToolSink {
        /** Partial output (e.g. streamed file content); emitted to the LLM stream. */
        void emitPartial(String partial);

        /** Human-facing progress note ("reading 3/10 files..."); NOT fed to the LLM. */
        void emitProgress(String progress);
    }

    /** Minimal HTTP client wrapped to enforce the sandbox domain whitelist. */
    interface NetworkClient {
        String get(String url) throws IOException;
        String post(String url, String body) throws IOException;
        InputStream getStream(String url) throws IOException;
    }

    /**
     * Asks the human a question from inside a tool (e.g. Bash needs confirmation for
     * {@code rm -rf}). Blocks until the user answers or {@code approvalTimeoutSeconds}
     * elapses.
     */
    interface ApprovalGate {
        Decision ask(Decision.AskUser ask);
    }

    /**
     * Cancellation source the tool polls; cancel callbacks registered via {@link #onCancel}.
     *
     * <p><b>Cooperative cancellation semantics (dsh §14.12):</b> {@link #fire()} sets the
     * cancellation flag — implementations (FlowEngine, Tool, LlmProvider) must poll
     * {@link #isCancelled()} or register callbacks to react. {@code fire()} is
     * <b>NOT</b> equivalent to {@link Thread#interrupt()}; cooperative threads must check
     * the token explicitly.
     *
     * <p><b>Idempotent:</b> multiple {@code fire()} invocations after the first are no-ops.
     * Callbacks are fired exactly once (or zero times if the token is never fired).
     *
     * <p><b>🆕 Story #005</b> added the {@link #fire()} default method for back-compat —
     * existing impls without explicit {@code fire()} get a no-op default.
     */
    interface CancellationToken {
        boolean isCancelled();

        /** Register a callback; returned {@link Runnable} unregisters it. */
        Runnable onCancel(Runnable callback);

        /**
         * Trigger cancellation. Idempotent — second invocation is a no-op. Synchronously
         * fires all registered callbacks in registration order; per-callback exceptions
         * are swallowed (logged at WARN) and do not block sibling callbacks.
         *
         * <p>Default implementation is empty (back-compat for Story #004 era tokens).
         * Story #005's {@code SimpleCancellationToken} overrides this with the actual
         * flag-set + callback-iteration logic.
         */
        default void fire() {
            /* no-op default for back-compat */
        }
    }
}