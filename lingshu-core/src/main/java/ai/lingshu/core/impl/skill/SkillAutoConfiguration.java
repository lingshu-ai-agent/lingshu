package ai.lingshu.core.impl.skill;

import ai.lingshu.core.impl.skill.source.CompositeSkillLoader;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Story #020a → #020b — Wire both {@code @Component}-typed {@link Skill} beans
 * <b>and</b> Phase-1 SKILL.md-derived Skills into the {@link ToolRegistry} (dsh §6.4).
 *
 * <p><b>Two-phase registration order</b> (dsh §6.4 + §10 R-09 mitigation philosophy):
 * <ol>
 *   <li><b>Phase 1</b> — {@link CompositeSkillLoader#loadAll} scans every configured
 *       {@link SkillSource} (classpath / directory / future {@code git}/{@code s3}) and
 *       produces a {@code Map<String, Skill>}.</li>
 *   <li><b>Phase 2</b> — Spring-injected {@code Map<String, Skill>} of {@code @Component}
 *       beans is layered on top via {@link CompositeSkillLoader#mergePhases}. Phase 2 wins
 *       on name collision (hardcoded {@code @Component} Skills override file-based
 *       {@code SKILL.md} entries — Story #020a design intent).</li>
 *   <li><b>Registration</b> — iterate the merged map in order, register each with the
 *       shared {@link ToolRegistry}. The registry's {@code register} method handles the
 *       {@code instanceof Skill} check internally (Story #020a T-04) and updates both
 *       {@code registry} and {@code skillsByName} indices.</li>
 * </ol>
 *
 * <p><b>Why this class exists separately from
 * {@link ai.lingshu.core.impl.tool.local.LocalToolsAutoConfiguration}:</b> that
 * configuration auto-wires {@code Map<String, Tool>} (all Tool beans) — including any
 * {@link Skill}-typed ones — but it does NOT signal the parallel Skill index. Splitting
 * the registration into two configurations keeps each one Single-Responsibility.
 *
 * <p>Reuses the same template as {@code LocalToolsAutoConfiguration}:
 * <ul>
 *   <li>{@code @Configuration} + {@link InitializingBean} — avoids
 *       {@code javax.annotation.PostConstruct} import per Story #009 R-13 mitigation
 *       philosophy.</li>
 *   <li>Toggle via {@link #PROP_ENABLED} {@code agent.skills.enabled}, default {@code true}.</li>
 *   <li>{@link Lazy} on the {@code Map<String, Skill>} injection to break the
 *       bean-cycle (this {@code @Configuration} ↔ Skill {@code @Component}s).</li>
 * </ul>
 *
 * <p><b>Why no Skill-specific wiring (cf. {@code BashTool.setProcessRunner}):</b>
 * Skills have no external dependencies — they are pure {@code (call, ctx) → ToolResult}
 * transformations. Pure registration is sufficient.
 */
@Configuration
public class SkillAutoConfiguration implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(SkillAutoConfiguration.class);

    /** Property key — set to {@code false} in {@code application.yml} to disable. */
    public static final String PROP_ENABLED = "agent.skills.enabled";

    private final ToolRegistry toolRegistry;
    private final Map<String, Skill> skills;
    private final Environment environment;
    private final CompositeSkillLoader loader;

    /**
     * Constructor injection of all dependencies.
     *
     * <p><b>{@code @Lazy} on the {@link Map} parameter:</b> Spring's eager bean-creation
     * order could create a cycle ({@code SkillAutoConfiguration} ↔ {@code commitSkill}).
     * The {@code @Lazy} proxy defers actual lookup until {@link #afterPropertiesSet()}
     * iterates it — by that point all Skills have been instantiated.
     */
    @Autowired
    public SkillAutoConfiguration(
            ToolRegistry toolRegistry,
            @Lazy Map<String, Skill> skills,
            Environment environment,
            CompositeSkillLoader loader) {
        this.toolRegistry = toolRegistry;
        this.skills = skills;
        this.environment = environment;
        this.loader = loader;
    }

    @Override
    public void afterPropertiesSet() {
        boolean enabled = environment.getProperty(PROP_ENABLED, Boolean.class, Boolean.TRUE);
        if (!enabled) {
            LOG.info("Skills disabled via {}={} — Skills constructed but NOT registered",
                PROP_ENABLED, enabled);
            return;
        }

        // Phase 1 — SKILL.md-derived Skills from configured sources
        SkillSourceProperties props = SkillSourceProperties.bindFromEnvironment(environment);
        Map<String, Skill> phase1 = loader.loadAll(props);

        // Phase 2 — @Component-typed Skills (Story #020a)
        Map<String, Skill> phase2 = skills;

        // Merge — Phase 2 wins on collision
        Map<String, Skill> merged = loader.mergePhases(phase1, phase2);

        int registered = 0;
        for (Map.Entry<String, Skill> e : merged.entrySet()) {
            Skill skill = e.getValue();
            toolRegistry.register(skill);
            registered++;
            LOG.debug("Registered skill: key={} skillName={} class={}",
                e.getKey(), skill.name(), skill.getClass().getSimpleName());
        }

        // Sorted name list for the INFO log line — stable ordering aids log grep.
        List<String> sorted = new ArrayList<String>();
        for (Skill s : merged.values()) sorted.add(s.name());
        Collections.sort(sorted);
        LOG.info("Skills ready — {} skill(s) registered ({} from sources, {} from @Component): {}",
            registered, phase1.size(), phase2.size(), sorted);
    }
}
