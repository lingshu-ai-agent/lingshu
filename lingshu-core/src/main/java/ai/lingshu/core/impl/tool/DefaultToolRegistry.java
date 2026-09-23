package ai.lingshu.core.impl.tool;

import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link ToolRegistry} — a process-wide, thread-safe tool registry.
 *
 * <p>Backed by a {@link ConcurrentHashMap}; supports the
 * "first-write-wins + duplicate-warn" semantics that the prior
 * {@code DefaultToolExecutor#register} provided.
 *
 * <p>Spring registration: {@code @Component} (auto-scanned). The single instance
 * is shared across:
 * <ul>
 *   <li>{@link LocalToolsAutoConfiguration} — registers the four built-in
 *       Tools (Read / Write / Edit / Bash) at startup.</li>
 *   <li>{@link DefaultToolExecutorProvider} — reads from it when constructing
 *       per-turn {@link DefaultToolExecutor} instances.</li>
 *   <li>Future MCP / Spring AI Tool adapters — also register here.</li>
 * </ul>
 *
 * <p>Why {@code @Component} (not {@code @Bean} via AutoConfiguration): the
 * registry is the lowest-level Slot 2 helper and is referenced from multiple
 * packages ({@code impl.tool}, {@code impl.tool.local}, future
 * {@code impl.mcp}). Plain {@code @Component} keeps it discoverable without
 * a dedicated {@code @AutoConfiguration} class.
 */
@Component
public class DefaultToolRegistry implements ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private final Map<String, Tool> registry = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool must not be null");
        }
        Tool prior = registry.putIfAbsent(tool.name(), tool);
        if (prior != null && prior != tool) {
            LOG.warn("Duplicate tool registration: name={} prior={} new={}",
                tool.name(), prior.getClass().getSimpleName(), tool.getClass().getSimpleName());
        }
    }

    @Override
    public Tool lookup(String name) {
        return registry.get(name);
    }

    @Override
    public Collection<String> names() {
        return Collections.unmodifiableCollection(registry.keySet());
    }

    // ── test-only access ─────────────────────────────────────────────────

    /**
     * Reflective accessor for the raw registry map — used by
     * {@code LocalToolsAutoConfigurationTest} to assert on the registered
     * tools without exposing the map publicly to production callers.
     */
    public Map<String, Tool> asMap() {
        return Collections.unmodifiableMap(registry);
    }
}