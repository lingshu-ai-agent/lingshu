package ai.lingshu.a2a.client;

import ai.lingshu.core.a2a.client.InProcessA2aRegistry;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.A2aTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Story #009b — In-process variant of {@link A2aTransport} (dsh §5.6.3.2 L3174-3320).
 *
 * <p>Same-JVM direct method-call transport: zero network, zero JSON parse,
 * zero new dependencies. Looks up AgentCards from {@link InProcessA2aRegistry}
 * via the {@link AgentCardCache} TTL cache (Contract A3, #009a) — pure
 * {@code ConcurrentHashMap} read with defensive copy.
 *
 * <p><b>Functional scope (Story #009b限定)</b>: {@link #fetchCard(String)}
 * is the only method supported. The remaining 4 A2aTransport methods
 * ({@link #submit}, {@link #get}, {@link #cancel}, {@link #subscribe})
 * throw {@link UnsupportedOperationException} — this transport is for
 * intra-JVM agent discovery only. Use the {@code http-jsonrpc} or
 * {@code grpc} variant when task RPC is needed (planned Story #009c).
 *
 * <p><b>Thread-safety</b>: this class is thread-safe. All fields are final;
 * the registry's {@link InProcessA2aRegistry#get} returns an immutable
 * defensive copy. {@link AgentCardCache} is thread-safe per its contract.
 */
public class InProcessA2aTransport implements A2aTransport {

    private static final Logger log = LoggerFactory.getLogger(InProcessA2aTransport.class);

    /** Message used by all 4 unsupported methods — single source of truth. */
    static final String UNSUPPORTED_MSG =
        "InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC";

    private final InProcessA2aRegistry registry;
    private final AgentCardCache cardCache;

    /**
     * @param registry the singleton in-process registry (must not be null —
     *                 typically obtained via {@link InProcessA2aRegistry#getInstance()})
     * @param cardCache the TTL cache for fetched cards (must not be null —
     *                  reuse the one from {@link GrpcA2aTransportProvider}'s default,
     *                  or create a fresh one from {@code AgentConfig.a2a.cardTtl})
     * @throws IllegalArgumentException if either arg is null
     */
    public InProcessA2aTransport(InProcessA2aRegistry registry, AgentCardCache cardCache) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (cardCache == null) {
            throw new IllegalArgumentException("cardCache must not be null");
        }
        this.registry = registry;
        this.cardCache = cardCache;
    }

    /**
     * Look up an AgentCard by {@code agentName}. Tries cache first; on miss,
     * queries the in-process registry; on registry miss, throws
     * {@link InProcessA2aRegistryEmptyException} (errorCode {@code LINGS-S08}).
     *
     * <p>The returned Map is an <b>immutable defensive copy</b> (per the
     * registry's contract), so callers may mutate it freely without
     * polluting the cache or the registry.</p>
     *
     * @param agentName the {@code Identity.name} of the target A2aServer
     * @return immutable Map view of the AgentCard
     * @throws IllegalArgumentException if {@code agentName} is null/empty
     * @throws InProcessA2aRegistryEmptyException (LINGS-S08) if no peer A2aServer
     *         is registered for this {@code agentName} in the in-process registry
     */
    @Override
    public Map<String, Object> fetchCard(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be null/empty");
        }
        // 1) cache lookup — #009a AgentCardCache handles negative cache + TTL
        Map<String, Object> cached = cardCache.get(agentName);
        if (cached != null) {
            log.debug("[InProcessA2aTransport] cache hit for agent='{}'", agentName);
            return cached;
        }
        // 2) registry lookup — same-JVM ConcurrentHashMap get
        Map<String, Object> raw = registry.get(agentName);
        if (raw == null) {
            // 3) miss — record negative cache + throw LINGS-S08
            cardCache.putNegative(agentName);
            String available = String.valueOf(registry.names());
            throw new InProcessA2aRegistryEmptyException(agentName, registry.names(),
                "No in-process A2A server registered for agentName='" + agentName
                    + "'. Available: " + available);
        }
        // 4) populate cache (positive)
        cardCache.put(agentName, raw);
        log.debug("[InProcessA2aTransport] registry hit for agent='{}' ({} fields)",
            agentName, raw.size());
        return raw;
    }

    /** {@inheritDoc} — <b>unsupported in #009b</b>; throws {@link UnsupportedOperationException}. */
    @Override
    public ToolResult submit(String agentName, String skill, String inputJson) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    /** {@inheritDoc} — <b>unsupported in #009b</b>; throws {@link UnsupportedOperationException}. */
    @Override
    public ToolResult get(String taskId) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    /** {@inheritDoc} — <b>unsupported in #009b</b>; throws {@link UnsupportedOperationException}. */
    @Override
    public boolean cancel(String taskId) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    /** {@inheritDoc} — <b>unsupported in #009b</b>; throws {@link UnsupportedOperationException}. */
    @Override
    public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent) {
        throw new UnsupportedOperationException(UNSUPPORTED_MSG);
    }

    // ── test-only accessors (package-private) ──────────────────────────────

    InProcessA2aRegistry getRegistry() {
        return registry;
    }

    AgentCardCache getCardCache() {
        return cardCache;
    }

    // ── nested exception type ───────────────────────────────────────────────

    /**
     * Thrown by {@link #fetchCard(String)} when the in-process registry has
     * no entry for the requested {@code agentName}.
     *
     * <p>Error code: {@code LINGS-S08} {@code A2A_INPROCESS_REGISTRY_EMPTY}.
     * The cause carries the missing {@code agentName} and the currently
     * registered set so callers (e.g. RemoteAgentTool / ToolExecutor) can
     * render a useful diagnostic.</p>
     */
    public static final class InProcessA2aRegistryEmptyException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public static final String ERROR_CODE = "LINGS-S08";

        private final String agentName;
        private final java.util.Set<String> available;

        InProcessA2aRegistryEmptyException(String agentName, java.util.Set<String> available,
                                            String message) {
            super(message);
            this.agentName = agentName;
            this.available = available == null
                ? Collections.<String>emptySet()
                : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(available));
        }

        public String getErrorCode() {
            return ERROR_CODE;
        }

        public String getAgentName() {
            return agentName;
        }

        public java.util.Set<String> getAvailable() {
            return available;
        }
    }
}
