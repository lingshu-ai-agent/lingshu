package ai.lingshu.core.a2a.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Story #009b — Contract C4 {@code lingshu.contract.in-process-a2a-registry.v1}.
 *
 * <p>Singleton in-process registry for {@code A2aTransport} discovery (dsh
 * §5.6.3.2). Every {@code A2aServer} (dsh §5.6.8) calls {@link #put} with its
 * own {@code Identity.name} → {@code Map<String, Object>} (the AgentCard
 * flattened via {@code LocalAgentCardGenerator.toMap} in the a2a-server
 * module) at {@code start()} time, and {@link #remove} at {@code stop()} time.</p>
 *
 * <p>Peers then look up cards via {@link InProcessA2aTransport#fetchCard}
 * (which routes through this registry's {@link #get}). Zero network, zero
 * JSON parse — pure {@link ConcurrentHashMap} read.</p>
 *
 * <p><b>Thread-safety</b>: all 7 public methods are safe for concurrent use.
 * {@link #get} returns an immutable defensive copy so external mutation cannot
 * pollute the registry. {@link #names} returns an immutable {@link Set} view.</p>
 *
 * <p><b>Lifecycle</b>: this singleton is loaded eagerly with the class
 * (static-init). JVM GC reclaims on process exit; no shutdown hook required.</p>
 *
 * <p><b>Testing</b>: use {@link #clear} only in {@code @BeforeEach} of unit
 * tests to isolate runs; never call {@code clear()} from production code.</p>
 */
public final class InProcessA2aRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(InProcessA2aRegistry.class);

    /**
     * Singleton instance — loaded with the class. Static-init guarantees
     * the same instance is returned by {@link #getInstance()} across all
     * callers in the JVM (no Spring container required).
     */
    private static final InProcessA2aRegistry INSTANCE = new InProcessA2aRegistry();

    /**
     * Internal store — {@link ConcurrentHashMap} guarantees atomic
     * put/get/remove under concurrent access. Values are immutable
     * defensive copies (defensive copies created on {@link #get}, not on put;
     * put stores the caller-provided reference as-is — callers must not
     * mutate the Map after handing it to {@link #put}).
     */
    private final ConcurrentHashMap<String, Map<String, Object>> store = new ConcurrentHashMap<>();

    /** Private constructor — use {@link #getInstance()} only. */
    private InProcessA2aRegistry() {
        LOG.debug("InProcessA2aRegistry initialized");
    }

    /**
     * @return the singleton instance; same object across the JVM.
     */
    public static InProcessA2aRegistry getInstance() {
        return INSTANCE;
    }

    /**
     * Register an agent card under the given {@code agentName}. Overwrites any
     * existing entry under the same key (logs a warn so duplicate agent
     * identities across the JVM surface clearly in boot logs).
     *
     * @param agentName the {@code Identity.name} of the registering A2aServer;
     *                  must be non-null
     * @param card      the AgentCard as an immutable {@code Map<String, Object>};
     *                  typically produced by
     *                  {@code ai.lingshu.a2a.server.LocalAgentCardGenerator#toMap}
     *                  in the a2a-server module. Must be non-null.
     * @throws IllegalArgumentException if either arg is null
     */
    public void put(String agentName, Map<String, Object> card) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Objects.requireNonNull(card, "card must not be null");
        if (store.containsKey(agentName)) {
            LOG.warn("InProcessA2aRegistry.put({}) already exists, overwriting — possible duplicate agent identity", agentName);
        }
        store.put(agentName, card);
        LOG.debug("InProcessA2aRegistry.put({}) -> {} fields", agentName, card.size());
    }

    /**
     * Look up an agent card by {@code agentName}.
     *
     * <p>Returns an <b>immutable defensive copy</b> of the stored Map (LinkedHashMap
     * to preserve insertion order). This guarantees external mutation cannot
     * pollute the registry — callers may freely modify the returned Map without
     * affecting future {@code get} results.</p>
     *
     * @param agentName the {@code Identity.name} to look up; must be non-null
     * @return immutable defensive copy of the card, or {@code null} if not registered
     */
    public Map<String, Object> get(String agentName) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        Map<String, Object> raw = store.get(agentName);
        if (raw == null) {
            return null;
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(raw));
    }

    /**
     * Remove the entry for {@code agentName}. Idempotent — returns {@code false}
     * if no entry existed.
     *
     * @return {@code true} if an entry was removed, {@code false} otherwise
     */
    public boolean remove(String agentName) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        return store.remove(agentName) != null;
    }

    /**
     * @return {@code true} if an entry exists for {@code agentName}
     */
    public boolean contains(String agentName) {
        Objects.requireNonNull(agentName, "agentName must not be null");
        return store.containsKey(agentName);
    }

    /**
     * @return immutable snapshot of all registered agentNames (test-time
     *         inspection helper; production code should not iterate this).
     */
    public Set<String> names() {
        return Collections.unmodifiableSet(store.keySet());
    }

    /**
     * @return the number of registered entries
     */
    public int size() {
        return store.size();
    }

    /**
     * Remove all entries. <b>Test-only helper</b> — production code must not call
     * this method as it would erase all peer agents' registrations.
     */
    public void clear() {
        store.clear();
    }
}
