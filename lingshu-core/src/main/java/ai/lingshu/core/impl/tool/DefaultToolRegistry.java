package ai.lingshu.core.impl.tool;

import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>{@link ai.lingshu.core.impl.tool.local.LocalToolsAutoConfiguration} — registers the
 *       four built-in Tools (Read / Write / Edit / Bash) at startup.</li>
 *   <li>{@link ai.lingshu.core.impl.skill.SkillAutoConfiguration} (🆕 Story #020a) —
 *       registers {@code @Component}-typed Skills (e.g. {@code CommitSkill}) at startup.</li>
 *   <li>{@link DefaultToolExecutorProvider} — reads from it when constructing
 *       per-turn {@link DefaultToolExecutor} instances.</li>
 *   <li>Future MCP / Spring AI Tool adapters — also register here.</li>
 * </ul>
 *
 * <p><b>🆕 Story #020a — Skill double-index:</b> every {@code register} call now
 * writes to TWO maps in lock-step:
 * <ul>
 *   <li>{@code registry} — all Tools, indexed by name; consumed by {@link #lookup},
 *       {@link #findByName}, {@link #names}.</li>
 *   <li>{@code skillsByName} — only {@link Skill}-typed Tools, indexed by name; consumed
 *       by {@link #findSkill}, {@link #skillNames}, and indirectly by {@link #modelVisibleSpecs}
 *       (which iterates the full {@code registry} but the Skills show up identically).</li>
 * </ul>
 *
 * <p>Both indices use {@code putIfAbsent} so that first-registration wins (Story #020b's
 * {@code CompositeSkillLoader} relies on the same first-wins semantic for {@code @Component}
 * Skills vs SKILL.md Skills with the same name).
 *
 * <p>Why {@code @Component} (not {@code @Bean} via AutoConfiguration): the
 * registry is the lowest-level Slot 2 helper and is referenced from multiple
 * packages ({@code impl.tool}, {@code impl.tool.local}, {@code impl.skill}, future
 * {@code impl.mcp}). Plain {@code @Component} keeps it discoverable without
 * a dedicated {@code @AutoConfiguration} class.
 */
@Component
public class DefaultToolRegistry implements ToolRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private final Map<String, Tool> registry = new ConcurrentHashMap<>();
    // 🆕 Story #020a — parallel Skill index; written lock-step with `registry`
    private final Map<String, Skill> skillsByName = new ConcurrentHashMap<>();

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
        // 🆕 Story #020a — Skill分流:同步双写skillsByName,保持first-wins语义
        if (tool instanceof Skill) {
            Skill skill = (Skill) tool;
            Skill priorSkill = skillsByName.putIfAbsent(skill.name(), skill);
            if (priorSkill != null && priorSkill != skill) {
                LOG.warn("Duplicate skill registration: name={} prior={} new={}",
                    skill.name(), priorSkill.getClass().getSimpleName(), skill.getClass().getSimpleName());
            }
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

    // ─────────────────────────────────────────────────────────────────────
    //  🆕 Story #020a — Skill-aware extensions
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public List<ToolSpec> modelVisibleSpecs() {
        List<ToolSpec> specs = new ArrayList<>(registry.size());
        for (Tool t : registry.values()) {
            specs.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        // 字典序排序 —— 稳定输出便于 PromptBuilder prompt cache 命中(对齐 #009d 设计哲学)
        Collections.sort(specs, new Comparator<ToolSpec>() {
            @Override public int compare(ToolSpec a, ToolSpec b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return specs;
    }

    @Override
    public Skill findSkill(String name) {
        return skillsByName.get(name);
    }

    @Override
    public Set<String> skillNames() {
        return Collections.unmodifiableSet(skillsByName.keySet());
    }

    @Override
    public Tool findByName(String name) {
        Tool t = registry.get(name);
        if (t == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return t;
    }

    /**
     * 🆕 Story #021b — Remove a tool by name; mirrors {@link #register(Tool)}.
     *
     * <p><b>No-op semantics</b> — removing a name that was never registered returns
     * {@code false} without throwing. This keeps the MCP {@code onConnectionStateChange}
     * listener body simple (plan §7 R-021b-02): it does not need to track prior
     * registration state across reconnects.
     *
     * <p><b>Skill dual-index cleanup</b> — if the removed tool was a {@link Skill},
     * the parallel {@code skillsByName} map is also cleared so {@link #findSkill}
     * returns {@code null} after this call.
     *
     * <p><b>Thread-safe</b> — {@link ConcurrentHashMap#remove(Object)} is atomic;
     * the {@code instanceof Skill} check is best-effort race-free: a concurrent
     * {@code register(Skill)} between {@code registry.remove} and
     * {@code skillsByName.remove} is acceptable — the registration will win and
     * {@code findSkill} will return the new tool. {@link #register} uses
     * {@code putIfAbsent} so first-write-wins (Story #020a semantics).
     */
    @Override
    public boolean unregister(String name) {
        if (name == null) {
            return false;
        }
        Tool removed = registry.remove(name);
        if (removed == null) {
            return false;
        }
        if (removed instanceof Skill) {
            skillsByName.remove(name);
        }
        LOG.info("Unregistered tool: name={} class={}",
            name, removed.getClass().getSimpleName());
        return true;
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
