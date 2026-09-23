package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.slot.SkillSource;
import ai.lingshu.core.slot.SkillSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Story #020b — startup-time index of all {@link SkillSourceProvider} beans, keyed by
 * {@link SkillSourceProvider#type()}.
 *
 * <p><b>Role:</b> the router turns {@code agent.skills.sources[N].type} → a {@link SkillSource}
 * instance. It does NOT itself discover skills — it only resolves the factory. Discovery
 * is the source's job (called from {@code CompositeSkillLoader}).
 *
 * <p><b>Why a custom router (not the {@code SlotRouter<P, T>} base):</b>
 * {@link SkillSourceProvider} is a sub-SPI of Slot 4 with only 2 methods
 * ({@code type()} + {@code create(location)}) — it does NOT extend
 * {@code SlotProvider} because the parent interface requires
 * {@code version() / name() / priority() / create(AgentConfig)} which is the wrong shape
 * for a stateless location-string factory. Reusing {@code SlotRouter} would force
 * fake methods (always-pass {@code version()}, dummy {@code priority()}) and would
 * invent an AgentConfig dependency Skill sources do not need.
 *
 * <p><b>Conflict policy:</b> two providers with the same {@code type()} at startup is a
 * configuration bug. The router logs a WARN and keeps the first-registered one
 * (LinkedHashMap deterministic) — this mirrors the §5.5 "unique Bean name convention"
 * philosophy (last-wins is for Slots with priority; sub-SPIs without priority go
 * first-wins to match LinkedHashMap insertion order).
 *
 * <p><b>Bean wiring:</b> {@code @Component} so Spring auto-discovers the router and
 * injects {@code List<SkillSourceProvider>}. {@code CompositeSkillLoader} consumes it
 * via constructor injection.
 */
@Component
public class SkillSourceRouter {

    private static final Logger LOG = LoggerFactory.getLogger(SkillSourceRouter.class);

    private final Map<String, SkillSourceProvider> byType;

    /**
     * Spring-injected list of all {@link SkillSourceProvider} beans.
     *
     * <p>Iteration order follows Spring's bean-discovery order — typically the classpath
     * scan order, which for tests is the source-file order of {@code @Component}-annotated
     * classes. We rely on this only for deterministic conflict-winner choice; the index
     * itself does not depend on order.
     */
    public SkillSourceRouter(List<SkillSourceProvider> providers) {
        Map<String, SkillSourceProvider> map = new LinkedHashMap<String, SkillSourceProvider>();
        for (SkillSourceProvider p : providers) {
            SkillSourceProvider prev = map.get(p.type());
            if (prev != null) {
                LOG.warn("[SkillSourceRouter] duplicate type '{}' — keeping first-registered {}"
                        + " (later {} ignored)",
                    p.type(),
                    prev.getClass().getSimpleName(),
                    p.getClass().getSimpleName());
                continue;
            }
            map.put(p.type(), p);
        }
        this.byType = Collections.unmodifiableMap(map);
        LOG.info("[SkillSourceRouter] resolved {} SkillSourceProvider(s): {}",
            byType.size(), byType.keySet());
    }

    /**
     * Resolve a Provider by type key and build a {@link SkillSource} for the given
     * location string.
     *
     * @param type     the YAML {@code agent.skills.sources[].type} value
     * @param location the YAML {@code agent.skills.sources[].location} value
     * @return a fresh {@link SkillSource} (not yet discovered)
     * @throws IllegalArgumentException if no Provider is registered for {@code type}
     */
    public SkillSource resolve(String type, String location) {
        SkillSourceProvider p = byType.get(type);
        if (p == null) {
            throw new IllegalArgumentException(
                "Unknown SkillSource type '" + type + "'. Available: " + byType.keySet());
        }
        return p.create(location);
    }

    /** All registered routing keys — useful for diagnostics. */
    public Set<String> available() {
        return byType.keySet();
    }

    /** Test-only accessor for direct map access; never used in production code paths. */
    Map<String, SkillSourceProvider> providersByType() {
        return byType;
    }
}
