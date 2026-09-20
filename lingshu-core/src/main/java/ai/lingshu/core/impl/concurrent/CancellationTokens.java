package ai.lingshu.core.impl.concurrent;

import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Factory + concrete {@link CancellationToken} implementation (Story #005).
 *
 * <p>dsh §14.12 N12 mandates a cooperative cancellation token threaded through
 * FlowEngine / ToolExecutor / LlmProvider. Story #004 left the token as a no-op
 * stub in {@code DefaultToolExecutionContext}; Story #005 wires the real impl.
 *
 * <p><b>API surface:</b>
 * <ul>
 *   <li>{@link #create()} — factory returning a fresh token (use this for per-turn
 *       instances; the engine constructs one via {@code DefaultTurnContext.createWithBroadcast})</li>
 * </ul>
 *
 * <p><b>Implementation {@link SimpleCancellationToken}:</b>
 * <ul>
 *   <li>{@link AtomicBoolean} {@code cancelled} — guarantees {@code fire()} idempotency
 *       via {@code compareAndSet(false, true)}</li>
 *   <li>{@link CopyOnWriteArrayList} {@code callbacks} — safe iteration during
 *       concurrent registration (AgentFactory broadcasts while new turns register)</li>
 *   <li>JDK 8 only — no Reactor / RxJava / Guava</li>
 * </ul>
 *
 * <p><b>Concurrency contract (FR-005 + NFR-005 + NFR-008):</b>
 * <ul>
 *   <li>{@code fire()} is <b>synchronous</b> — callbacks run in the firing thread,
 *       in registration order. Asynchronous fire would break the 200ms AC-04 budget.</li>
 *   <li>Per-callback exceptions are caught + logged at WARN; do not propagate (Edge Case).</li>
 *   <li>{@code onCancel(callback)} returns an unregister {@link Runnable} that, when
 *       invoked, removes the callback from the list (subsequent {@code fire()} skips it).</li>
 * </ul>
 */
public final class CancellationTokens {

    private CancellationTokens() {
        // non-instantiable utility
    }

    /**
     * Create a fresh cancellation token in unfired state.
     *
     * @return new {@link CancellationToken} instance, never null
     */
    public static CancellationToken create() {
        return new SimpleCancellationToken();
    }

    /**
     * Default {@link CancellationToken} impl — shared identity, atomic flag, safe
     * iteration over callbacks.
     */
    static final class SimpleCancellationToken implements CancellationToken {

        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public Runnable onCancel(Runnable callback) {
            if (callback == null) {
                throw new IllegalArgumentException("callback must not be null");
            }
            callbacks.add(callback);
            // Return an unregister handle; runs against the CopyOnWriteArrayList's
            // own removeIf semantics (no-op if not present).
            return new Runnable() {
                @Override
                public void run() {
                    callbacks.remove(callback);
                }
            };
        }

        /**
         * Fire cancellation — sets the flag atomically + synchronously triggers callbacks.
         * Idempotent: a second invocation is a no-op (NFR-005).
         */
        @Override
        public void fire() {
            if (cancelled.compareAndSet(false, true)) {
                // Snapshot-then-iterate so callbacks that unregister themselves during
                // execution don't disrupt the iteration (CopyOnWriteArrayList iterators
                // are snapshot-based anyway, but explicit local copy is clearer).
                Object[] snapshot = callbacks.toArray();
                for (Object o : snapshot) {
                    Runnable cb = (Runnable) o;
                    try {
                        cb.run();
                    } catch (Exception e) {
                        // Per-callback swallow: one bad callback must not block siblings.
                        // The default JUL logger is sufficient here — full SLF4J wiring
                        // happens in Spring context; unit tests assert no propagation.
                        java.util.logging.Logger.getLogger(SimpleCancellationToken.class.getName())
                            .warning("cancel callback threw: " + e.getMessage());
                    }
                }
            }
        }
    }
}