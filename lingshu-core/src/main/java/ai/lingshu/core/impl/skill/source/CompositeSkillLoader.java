package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.impl.skill.SkillSourceProperties;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Story #020b — orchestrates one-shot discovery across all configured
 * {@link SkillSource} instances. Called from {@code SkillAutoConfiguration.afterPropertiesSet()}
 * at Spring startup.
 *
 * <p><b>Pipeline:</b>
 * <ol>
 *   <li>Iterate {@link SkillSourceProperties#getSources()} in configured order</li>
 *   <li>For each {@link SkillSourceProperties.SourceEntry}: ask
 *       {@code SkillSourceRouter} to construct the {@link SkillSource}</li>
 *   <li>Call {@link SkillSource#discover()} — wrap each call in try/catch so one bad
 *       source does not block others (dsh §10 R-09 mitigation philosophy)</li>
 *   <li>Merge into a {@code Map<String, Skill>} using
 *       {@code ConcurrentHashMap.putIfAbsent} semantics — first-registered wins. The
 *       Phase 2 {@code @Component}-typed Skills (Story #020a) are merged in
 *       {@code SkillAutoConfiguration} <i>after</i> this loader runs, so {@code @Component}
 *       Skills can override Phase 1 SKILL.md entries when names collide.</li>
 * </ol>
 *
 * <p><b>Why the loader returns a Map, not a List:</b> the registry needs O(1) lookup by
 * name (CLI {@code /commit}, LLM function-call dispatch). Returning a Map here lets
 * {@code SkillAutoConfiguration} merge it with the {@code Map<String, Skill>} of
 * {@code @Component} beans via simple {@code putIfAbsent}.
 *
 * <p><b>Why no async / parallel scan:</b> discovery runs once at startup, the total
 * skill count is small (tens, not thousands), and the loader is invoked from a
 * synchronous {@code InitializingBean} hook. Sequential keeps the code
 * deterministic and log-order grep-friendly.
 */
@Component
public class CompositeSkillLoader {

    private static final Logger LOG = LoggerFactory.getLogger(CompositeSkillLoader.class);

    private final SkillSourceRouter router;

    public CompositeSkillLoader(SkillSourceRouter router) {
        this.router = router;
    }

    /**
     * Discover Skills from every configured {@link SkillSourceProperties.SourceEntry}.
     *
     * <p><b>Error handling:</b> per source, if router resolution fails (unknown type)
     * the whole call short-circuits with {@link IllegalArgumentException} — that means
     * the YAML misconfigured and silent-skip would hide the bug. Per source, if
     * {@code discover()} throws {@link IOException} the loader logs and continues with
     * the other sources.
     *
     * @param props the bound {@link SkillSourceProperties} (from {@code SkillAutoConfiguration})
     * @return immutable ordered map of {@code Skill.name()} → {@link Skill}, possibly empty
     */
    public Map<String, Skill> loadAll(SkillSourceProperties props) {
        Map<String, Skill> merged = new LinkedHashMap<String, Skill>();

        if (props == null || props.getSources() == null || props.getSources().isEmpty()) {
            LOG.info("[CompositeSkillLoader] no Skill sources configured — Phase 1 yields 0 skills");
            return merged;
        }

        int totalConfigured = props.getSources().size();
        int totalDiscovered = 0;
        for (SkillSourceProperties.SourceEntry entry : props.getSources()) {
            SkillSource source;
            try {
                source = router.resolve(entry.getType(), entry.getLocation());
            } catch (IllegalArgumentException e) {
                // Unknown type — propagate (YAML misconfig; silent-skip would hide the bug).
                throw e;
            }

            List<Skill> skills;
            try {
                skills = source.discover();
            } catch (IOException e) {
                LOG.warn("[CompositeSkillLoader] source type='{}' location='{}' failed: {}"
                        + " — skipping this source, continuing with others",
                    entry.getType(), entry.getLocation(), e.getMessage());
                continue;
            }

            int added = 0;
            for (Skill s : skills) {
                Skill prev = merged.putIfAbsent(s.name(), s);
                if (prev == null) {
                    added++;
                } else {
                    LOG.debug("[CompositeSkillLoader] duplicate skill name '{}' from source {}"
                            + " — first-wins (kept {} from earlier source)",
                        s.name(), entry.getType(), prev.getClass().getSimpleName());
                }
            }
            totalDiscovered += added;
            LOG.info("[CompositeSkillLoader] source type='{}' location='{}' discovered {} skill(s)",
                entry.getType(), entry.getLocation(), added);
        }
        LOG.info("[CompositeSkillLoader] Phase 1 complete: {} configured source(s), {} unique skill(s) total",
            totalConfigured, totalDiscovered);
        return merged;
    }

    /**
     * Merge Phase 1 (SKILL.md sources) and Phase 2 ({@code @Component}-typed Skills)
     * into one Map for {@code SkillAutoConfiguration} to register with {@link
     * ai.lingshu.core.slot.ToolRegistry}.
     *
     * <p><b>Phase 1 wins on name collision</b> (file-based Skills override hardcoded
     * {@code @Component} Skills when both define the same name). The user can therefore
     * drop a same-named {@code SKILL.md} into a configured source directory to override
     * a built-in {@code @Component} Skill without recompiling. This matches
     * {@link ai.lingshu.core.slot.ToolRegistry#register(Tool)} semantics, which uses
     * {@code putIfAbsent} so the first registration wins — and Phase 1 is registered
     * first in {@code SkillAutoConfiguration.afterPropertiesSet()}.
     *
     * <p>Iteration order is therefore Phase 1 first, Phase 2 second; Phase 2 entries
     * are dropped via {@code putIfAbsent} on collision (logged at INFO for visibility).
     *
     * @param phase1Skills discovered SKILL.md-derived Skills (already deduplicated by name)
     * @param phase2Skills {@code @Component}-typed Skills from Spring DI
     * @return merged ordered map: Phase 1 keys in discovery order, Phase 2 keys appended
     *         (no overwrites)
     */
    public Map<String, Skill> mergePhases(Map<String, Skill> phase1Skills,
                                          Map<String, Skill> phase2Skills) {
        Map<String, Skill> merged = new LinkedHashMap<String, Skill>();
        if (phase1Skills != null) {
            merged.putAll(phase1Skills);
        }
        if (phase2Skills != null) {
            for (Map.Entry<String, Skill> e : phase2Skills.entrySet()) {
                Skill prev = merged.putIfAbsent(e.getKey(), e.getValue());
                if (prev != null) {
                    LOG.info("[CompositeSkillLoader] Phase 2 skill '{}' DROPPED — Phase 1 (file-based)"
                            + " skill of the same name wins; user-overridable via SKILL.md",
                        e.getKey());
                }
            }
        }
        return merged;
    }

    /**
     * Convenience: log-friendly list of names in insertion order. Used by tests and
     * the {@code SkillAutoConfiguration} summary log.
     */
    static List<String> namesOf(Map<String, Skill> skills) {
        List<String> out = new ArrayList<String>(skills.size());
        out.addAll(skills.keySet());
        return out;
    }
}
