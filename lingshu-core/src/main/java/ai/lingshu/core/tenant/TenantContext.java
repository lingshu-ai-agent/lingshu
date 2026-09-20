package ai.lingshu.core.tenant;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Supplier;

/**
 * Per-thread tenant context — the cornerstone of Story #006 multi-tenant isolation
 * (dsh §14.9 N9, AC-05).
 *
 * <p><b>Stateless utility.</b> All methods are static; the class is non-instantiable.
 * State lives in a {@link ThreadLocal} stack of tenantId strings — one stack per
 * thread, no sharing across threads, no global state.
 *
 * <h2>Why ThreadLocal + Deque (not InheritableThreadLocal)</h2>
 *
 * <p>Cross-thread tenant propagation is intentionally <b>explicit</b> via
 * {@link #snapshot()} + {@link #runWithSnapshot(String, Supplier)}:
 * <ul>
 *   <li><b>InheritableThreadLocal</b> would silently pollute child threads, and worse,
 *       it pollutes worker threads reused by thread pools (the previous task's tenant
 *       leaks into the next task). Hard to detect at code-review time.</li>
 *   <li><b>snapshot + runWithSnapshot</b> forces the call site to be explicit. If the
 *       developer forgets, the child thread sees {@code current() == null} — a safe
 *       degradation that fails the FR-011 guard at the turn boundary instead of
 *       silently using the wrong tenant.</li>
 * </ul>
 *
 * <p>Mitigates dsh §17 R-02 (ThreadLocal leak) by combining three mechanisms:
 * <ol>
 *   <li>{@code try-finally} wrapping in {@link #runAs(String, Supplier)} — exceptions
 *       still trigger {@link #clear()}.</li>
 *   <li>Stack (Deque) instead of single-value ThreadLocal — nested {@code runAs}
 *       scopes stack correctly without leaking.</li>
 *   <li>Explicit cross-thread transfer instead of {@code InheritableThreadLocal} —
 *       thread-pool reuse cannot accidentally inherit a stale tenant.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * TenantContext.runAs("alice", () -> {
 *     // business code; TenantContext.current() == "alice"
 *     return someResult();
 * });
 * // outside the lambda: TenantContext.current() == null
 *
 * // Cross-thread (ExecutorService.submit(...)):
 * String snap = TenantContext.snapshot();
 * executor.submit(() -> {
 *     TenantContext.runWithSnapshot(snap, () -> {
 *         // sub-thread sees the same tenant
 *         doWork();
 *     });
 * });
 * }</pre>
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I-1: {@code STACK.get().isEmpty() ⇔ current() == null}</li>
 *   <li>I-2: nested {@code runAs(a → b)} does not pollute the outer scope</li>
 *   <li>I-3: exceptions thrown from the {@code Supplier}/{@code Runnable} still
 *       trigger {@link #clear()}</li>
 *   <li>I-4: child threads do not inherit the parent stack — must use
 *       {@link #runWithSnapshot}</li>
 *   <li>I-5: {@link #snapshot()} returns an immutable copy ({@link String} itself)</li>
 *   <li>I-6: validation failure does not mutate the stack — the offending
 *       {@code set}/{@code runAs} call leaves the stack unchanged</li>
 * </ul>
 *
 * <h2>Validation</h2>
 *
 * <p>{@link #set(String)} rejects {@code null}, empty strings, and anything that
 * does not match {@code [a-zA-Z0-9_-]{1,64}} with {@link IllegalArgumentException}.
 * The regex is intentionally restrictive: tenantId appears in file paths and
 * session keys, so preventing colons / slashes / spaces avoids escaping bugs.
 */
public final class TenantContext {

    /** Thread-local stack of tenantIds (outermost first, current tenant at the top). */
    private static final ThreadLocal<Deque<String>> STACK = new ThreadLocal<>();

    /** TenantId regex: alphanumeric + underscore + hyphen, length 1-64. */
    private static final java.util.regex.Pattern TENANT_ID_PATTERN =
        java.util.regex.Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private TenantContext() {
        throw new AssertionError("TenantContext is a static utility — no instances");
    }

    /**
     * @return the current tenantId (top of the stack) or {@code null} if no tenant is
     *         active on this thread. O(1) — one ThreadLocal.get + one peekLast.
     */
    public static String current() {
        Deque<String> s = STACK.get();
        return s == null || s.isEmpty() ? null : s.peekLast();
    }

    /**
     * Push {@code tenantId} onto this thread's stack.
     *
     * @param tenantId non-null, matches {@code [a-zA-Z0-9_-]{1,64}}
     * @throws IllegalArgumentException if the tenantId is invalid (I-6: stack
     *         is left unchanged on failure)
     */
    public static void set(String tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches()) {
            throw new IllegalArgumentException(
                "tenantId '" + tenantId + "' must match [a-zA-Z0-9_-]{1,64}");
        }
        Deque<String> s = STACK.get();
        if (s == null) {
            s = new ArrayDeque<>();
            STACK.set(s);
        }
        s.addLast(tenantId);
    }

    /**
     * Pop the top of the stack. No-op on an empty stack — safe to call when no
     * tenant is active.
     */
    public static void clear() {
        Deque<String> s = STACK.get();
        if (s != null && !s.isEmpty()) {
            s.pollLast();
        }
    }

    /**
     * @return an immutable snapshot of the current tenantId for cross-thread
     *         transfer, or {@code null} when no tenant is active.
     */
    public static String snapshot() {
        return current();
    }

    /**
     * Run {@code work} with {@code tenantId} as the active tenant on this thread.
     * Guarantees {@link #clear()} is called even if {@code work} throws
     * (I-3 — try-finally).
     */
    public static <T> T runAs(String tenantId, Supplier<T> work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        set(tenantId);
        try {
            return work.get();
        } finally {
            clear();
        }
    }

    /**
     * Void-returning variant of {@link #runAs(String, Supplier)} — convenient for
     * {@link Runnable} blocks that don't need a result.
     */
    public static void runAs(String tenantId, Runnable work) {
        if (work == null) {
            throw new IllegalArgumentException("work must not be null");
        }
        runAs(tenantId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Run {@code work} in a child thread with {@code snapshot} restored as the
     * active tenant. The caller must pass {@link #snapshot()} from the parent thread.
     *
     * @param snapshot the tenantId captured from {@link #snapshot()} on the parent
     *                 thread; must not be {@code null} — passing {@code null} would
     *                 silently disable tenant context on the child thread, which is
     *                 almost certainly a bug (FR-006: fail-fast).
     * @param work     the work to execute
     */
    public static <T> T runWithSnapshot(String snapshot, Supplier<T> work) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                "snapshot must not be null — use snapshot() on the parent thread, "
                    + "or run directly without TenantContext if no tenant applies");
        }
        return runAs(snapshot, work);
    }
}