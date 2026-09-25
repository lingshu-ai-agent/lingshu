package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.runtime.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * In-memory {@code sessionId → Agent} map with TTL eviction.
 *
 * <p>Closes the multi-turn gap: the codebase has no {@code SessionRegistry} /
 * {@code AgentRegistry} — every other demo holds the {@link Agent} reference
 * in {@code CommandLineRunner}-scoped local state. For an HTTP model we need
 * to look up an Agent by sessionId from any request thread.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #create(Supplier)} — UUID-keyed, supplier builds the Agent lazily</li>
 *   <li>{@link #touch(String)}  — bump last-access timestamp, return Agent or null</li>
 *   <li>{@link #evict(String)}  — explicit close (called by DELETE endpoint)</li>
 *   <li>{@link #startJanitor()} — every 60s, evict entries older than {@link #TTL}</li>
 * </ul>
 *
 * <p>R-4: when TTL fires on an in-flight turn, the Agent reference is dropped.
 * The next request that resolves to the same sessionId will get null (caller
 * must create a fresh Agent). For graceful cancel, the controller may invoke
 * {@link AgentFactory#broadcastCancel()} before evict.
 */
@Component
public class SessionRegistry implements InitializingBean, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(SessionRegistry.class);

    /** Idle TTL — entries untouched for this long are evicted. */
    static final Duration TTL = Duration.ofMinutes(30);

    /** How often the janitor scans. */
    private static final long SCAN_INTERVAL_SEC = 60L;

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private ScheduledExecutorService janitor;

    /** Per-entry: agent + last-access epoch ms (atomic for race-free touch). */
    static final class Entry {
        final Agent agent;
        final AtomicLong lastAccessEpochMs = new AtomicLong(System.currentTimeMillis());

        Entry(Agent agent) {
            this.agent = agent;
        }

        void touch() {
            lastAccessEpochMs.set(System.currentTimeMillis());
        }
    }

    @Override
    public void afterPropertiesSet() {
        janitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "session-janitor");
            t.setDaemon(true);
            return t;
        });
        janitor.scheduleAtFixedRate(this::sweep, SCAN_INTERVAL_SEC, SCAN_INTERVAL_SEC, TimeUnit.SECONDS);
        LOG.info("SessionRegistry janitor started — TTL={}, scan every {}s", TTL, SCAN_INTERVAL_SEC);
    }

    @Override
    public void destroy() {
        if (janitor != null) {
            janitor.shutdownNow();
        }
    }

    /** Generate a new sessionId and register a freshly built Agent. */
    public String create(Supplier<Agent> factory) {
        Agent agent = factory.get();
        String id = UUID.randomUUID().toString();
        map.put(id, new Entry(agent));
        LOG.info("session created: id={}", id);
        return id;
    }

    /** Bump last-access and return Agent, or {@code null} if sessionId unknown / evicted. */
    public Agent touch(String sessionId) {
        Entry e = map.get(sessionId);
        if (e == null) {
            return null;
        }
        e.touch();
        return e.agent;
    }

    /** Explicit evict (DELETE endpoint or TTL sweep). */
    public void evict(String sessionId) {
        Entry removed = map.remove(sessionId);
        if (removed != null) {
            LOG.info("session evicted: id={}", sessionId);
        }
    }

    /** Snapshot of all live sessionIds (for {@code GET /api/sessions}). */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new java.util.HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            long ageMs = now - e.getValue().lastAccessEpochMs.get();
            out.put(e.getKey(), ageMs);
        }
        return out;
    }

    /** Janitor callback: evict entries whose last-access is older than {@link #TTL}. */
    void sweep() {
        long cutoff = System.currentTimeMillis() - TTL.toMillis();
        int removed = 0;
        for (Map.Entry<String, Entry> e : map.entrySet()) {
            if (e.getValue().lastAccessEpochMs.get() < cutoff) {
                map.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            LOG.info("session janitor evicted {} entries", removed);
        }
    }
}