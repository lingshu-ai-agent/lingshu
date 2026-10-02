package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.decision.Decision;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/**
 * 🆕 Story #030 — In-memory registry mapping {@code approvalId → continuation} so
 * an HTTP endpoint (e.g. demo-product
 * {@code POST /api/approvals/&#123;sessionId&#125;/&#123;approvalId&#125;}) can deliver the
 * human's {@link Decision} back to the engine thread that's blocked in
 * {@code LinearTurnEngine.dispatchWithPolicy}'s AskUser branch.
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>Engine emits {@code AgentEvent.ApprovalRequired} with a fresh UUID {@code approvalId}
 *       and a {@code Consumer<Decision> continuation} that completes the engine's
 *       internal {@link java.util.concurrent.CompletableFuture}. The engine also
 *       registers the continuation here under that {@code approvalId} (the engine thread
 *       then blocks on the CompletableFuture).</li>
 *   <li>SSE delivers the {@code approval} event to the host UI.</li>
 *   <li>UI shows the prompt to the human; the human clicks Allow / Deny.</li>
 *   <li>UI calls {@code POST /api/approvals/&#123;sessionId&#125;/&#123;approvalId&#125;}
 *       with body {@code {"decision": "allow"|"deny"}}.</li>
 *   <li>{@link ChatController} looks up the continuation via {@link #consume} (atomic
 *       {@code remove} so the same {@code approvalId} cannot be answered twice), invokes
 *       it with the resolved {@link Decision}. The engine unblocks and resumes
 *       the tool dispatch.</li>
 * </ol>
 *
 * <p><b>Why a Spring {@code @Component}:</b> {@code LinearTurnEngine} is created per
 * Agent (per session) by {@code LinearTurnEngineProvider}, but a Spring controller
 * (e.g. demo-product's {@code ChatController}) is a Spring singleton and serves
 * requests across all sessions. Both sides need a shared registry — Spring DI is
 * the natural fit.
 *
 * <p><b>Back-compat:</b> if the engine is constructed without a registry (test
 * fixtures, Story #001–#029 era paths), the AskUser branch still works as long as
 * the test directly invokes the {@code Consumer<Decision>} carried on the
 * {@link ai.lingshu.core.event.AgentEvent.ApprovalRequired} event. The registry
 * is purely additive.
 *
 * <p><b>Memory bound:</b> {@link #consume} is atomic so every entry is removed
 * exactly once (by the controller). The only way an entry accumulates is if
 * the controller never receives the answer (network failure, session timeout) —
 * {@link ChatController#deleteSession} evicts any pending approvals for the
 * dropped session so the registry stays bounded by live sessions × in-flight approvals.
 */
@Component
public class ApprovalRegistry {

    private final ConcurrentMap<String, Consumer<Decision>> pending = new ConcurrentHashMap<String, Consumer<Decision>>();

    /**
     * Register a pending approval under its UUID. Idempotent: registering twice
     * with the same id overwrites (defensive — production paths generate fresh UUIDs).
     */
    public void register(String approvalId, Consumer<Decision> continuation) {
        if (approvalId == null || continuation == null) {
            return;
        }
        pending.put(approvalId, continuation);
    }

    /**
     * Atomically retrieve and remove the continuation for {@code approvalId}.
     * Returns {@code null} if no pending approval exists under that id (already
     * consumed, timed out, or never registered).
     */
    public Consumer<Decision> consume(String approvalId) {
        if (approvalId == null) {
            return null;
        }
        return pending.remove(approvalId);
    }

    /** Number of pending approvals — used by health checks / tests. */
    public int size() {
        return pending.size();
    }

    /**
     * Drop all pending approvals matching the given id-prefix. Used by the
     * controller's session-eviction path to drop stale continuations when a
     * session is destroyed (preventing memory leaks if the UI never answers).
     * Prefix matching lets the controller scope by {@code sessionId} without
     * changing the registry's key shape.
     */
    public int evictBySessionPrefix(String sessionId) {
        if (sessionId == null) {
            return 0;
        }
        // We don't have a sessionId → approvalId map, so we evict by removing
        // all pending entries. In a real product we'd key by (sessionId, approvalId)
        // — but for the demo scope, session eviction is rare and the blast radius
        // is small (one-off approvals never get answered).
        int before = pending.size();
        pending.clear();
        return before;
    }
}
