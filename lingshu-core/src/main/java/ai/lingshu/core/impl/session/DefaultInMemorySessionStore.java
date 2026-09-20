package ai.lingshu.core.impl.session;

import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.tenant.TenantContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Default in-memory {@link SessionStore} with multi-tenant session-key isolation
 * (Story #006 US5, FR-002, AC-05).
 *
 * <p>Each saved checkpoint is keyed by {@code tid + ":" + sessionId} where
 * {@code tid} comes from {@link TenantContext#current()} at save time. This
 * guarantees alice's session {@code "s1"} and bob's session {@code "s1"} never
 * collide — even though they share the same logical sessionId, they live in
 * separate physical buckets.
 *
 * <h2>Single-tenant mode</h2>
 *
 * <p>When {@link TenantContext#current()} returns {@code null} (single-tenant
 * mode, {@code AgentConfig.tenants} disabled), the key collapses to the bare
 * {@code sessionId}. This is intentional: in single-tenant mode there is no
 * isolation to enforce, and existing Story #001—#005 tests use plain session
 * keys without any tenant prefix.
 *
 * <h2>Concurrency</h2>
 *
 * <p>Backed by a {@link ConcurrentHashMap}. {@link #save(Checkpoint)} is
 * last-write-wins; concurrent saves from the same logical session will produce
 * the surviving checkpoint being whichever finished last. That matches the
 * contract documented on {@link SessionStore#save(Checkpoint)}.
 *
 * <h2>Trade-offs vs. persistent backends</h2>
 *
 * <p>Memory-only is fine for unit tests, CLI short-lived runs, and Story #006
 * L1 validation. Production deployments should swap to {@code FileSessionStore}
 * or {@code RedisSessionStore} (Story #014 follow-up). The tenant-key contract
 * is identical across all backends — see {@link #buildKey(String, String)}.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I-1: {@code save} + {@code load} round-trip preserves the Checkpoint
 *       identity (the in-memory map stores the same object reference).</li>
 *   <li>I-2: alice's checkpoint for sessionId {@code X} is never visible to
 *       bob's {@code load("X")}, even though both call sites pass the same id.</li>
 *   <li>I-3: in single-tenant mode ({@code TenantContext.current() == null}),
 *       keys are bare sessionIds (no {@code ":"} prefix).</li>
 *   <li>I-4: a load for an unknown sessionId returns {@link Optional#empty()}
 *       — not a thrown exception. Matches {@code SessionStore#load} contract.</li>
 * </ul>
 */
@Component("defaultInMemorySessionStore")
public class DefaultInMemorySessionStore implements SessionStore {

    /** Physical bucket store — keyed by {@code buildKey(tid, sessionId)}. */
    private final ConcurrentMap<String, Checkpoint> store = new ConcurrentHashMap<>();

    @Override
    public void save(Checkpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
        String key = buildKey(TenantContext.current(), checkpoint.getSessionId());
        store.put(key, checkpoint);
    }

    @Override
    public Optional<Checkpoint> load(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String key = buildKey(TenantContext.current(), sessionId);
        return Optional.ofNullable(store.get(key));
    }

    /**
     * Build the physical key from the active tenant and logical session id.
     *
     * <p>Multi-tenant mode: {@code tid + ":" + sessionId}
     * <br>Single-tenant mode: {@code sessionId}
     *
     * <p>Exposed package-private for testing — production callers should use
     * {@link #save}/{@link #load}.
     */
    static String buildKey(String tenantId, String sessionId) {
        if (tenantId == null) {
            return sessionId;
        }
        return tenantId + ":" + sessionId;
    }

    /**
     * Drop all checkpoints — useful for test {@code @AfterEach} cleanup.
     * Production callers should not invoke this; tests may.
     */
    public void clear() {
        store.clear();
    }

    /**
     * Number of currently stored checkpoints. Useful for assertions in tests
     * (e.g. "alice's save does not grow bob's bucket").
     */
    public int size() {
        return store.size();
    }
}
