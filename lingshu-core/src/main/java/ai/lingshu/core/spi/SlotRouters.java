package ai.lingshu.core.spi;

import ai.lingshu.core.runtime.AgentConfig;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared resolver logic for typed {@link SlotProvider}s (dsh §5.2).
 *
 * <p>Concrete Routers (one per Slot) extend this abstract class, supplying the {@code P} /
 * {@code T} pair. The constructor takes the Spring-injected {@code List<P>} and runs the
 * same-name / priority / startup-log dance that all 9 Routers share.
 *
 * <p>Hot path is {@link #resolve(String, AgentConfig)}; startup path is the constructor
 * (logs to the provided {@link Logger}).
 */
public abstract class SlotRouters<P extends SlotProvider<T>, T> {

    private final Map<String, P> byName;

    /**
     * @param providers Spring-injected list of all Providers of this Slot
     * @param typeName  short name for log lines (e.g. {@code "LlmProvider"})
     * @param log       the Logger to receive the startup summary
     */
    protected SlotRouters(List<P> providers, String typeName, Logger log) {
        Map<String, P> winners = new LinkedHashMap<>();
        Map<String, List<P>> conflicts = new LinkedHashMap<>();

        for (P p : providers) {
            P cur = winners.get(p.name());
            if (cur == null) {
                winners.put(p.name(), p);
            } else if (p.priority() > cur.priority()) {
                conflicts.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(cur);
                winners.put(p.name(), p);
            } else {
                conflicts.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
            }
        }
        this.byName = winners;

        log.info("[{}] resolved {} provider(s):", typeName, winners.size());
        for (Map.Entry<String, P> e : winners.entrySet()) {
            List<P> all = conflicts.getOrDefault(e.getKey(), Collections.<P>emptyList());
            String conflictInfo = all.isEmpty()
                ? ""
                : " (overrode " + all.size() + " lower-priority impl(s): "
                  + joinNames(all) + ")";
            log.info("  ✓ {} -> {} [priority={}]{}",
                e.getKey(),
                e.getValue().getClass().getSimpleName(),
                e.getValue().priority(),
                conflictInfo);
        }
    }

    /**
     * Resolve a Provider by name and create the Slot instance.
     *
     * @throws IllegalArgumentException if no Provider with that name is registered
     */
    public T resolve(String name, AgentConfig config) {
        P p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                "Unknown " + getClass().getSimpleName() + " '" + name + "'. Available: " + byName.keySet());
        }
        return p.create(config);
    }

    /** All registered names — useful for diagnostics and {@code /slots} CLI commands. */
    public Set<String> available() { return byName.keySet(); }

    private static String joinNames(List<?> ps) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object p : ps) {
            if (!first) sb.append(", ");
            sb.append(p.getClass().getSimpleName());
            first = false;
        }
        return sb.toString();
    }
}