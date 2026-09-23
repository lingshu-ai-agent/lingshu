package ai.lingshu.core.impl.skill;

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
 * Story #020a — Wire the {@code @Component}-typed {@link Skill} beans into the
 * {@link ToolRegistry} (dsh §6.4).
 *
 * <p><b>Why this class exists separately from
 * {@link ai.lingshu.core.impl.tool.local.LocalToolsAutoConfiguration}:</b> that
 * configuration auto-wires {@code Map<String, Tool>} (all Tool beans) — including any
 * {@link Skill}-typed ones — but it does NOT signal the parallel Skill index. Splitting
 * the registration into two configurations keeps each one Single-Responsibility:
 * {@code LocalToolsAutoConfiguration} handles the four built-in Tools (incl. their
 * {@code BashTool}-specific wiring into {@code RuntimeSandbox}); this class handles the
 * zero-wiring Skills (Skills have no external dependencies, just registration).
 *
 * <p>Reuses the same template as {@code LocalToolsAutoConfiguration}:
 * <ul>
 *   <li>{@code @Configuration} + {@link InitializingBean} — avoids
 *       {@code javax.annotation.PostConstruct} import per Story #009 R-13 mitigation
 *       philosophy (do not pull {@code javax.annotation-api} / Jakarta's annotation-api
 *       just for one lifecycle hook).</li>
 *   <li>Toggle via {@link #PROP_ENABLED} {@code agent.skills.enabled}, default {@code true}.</li>
 *   <li>{@link Lazy} on the {@code Map<String, Skill>} injection to break the
 *       bean-cycle (this {@code @Configuration} ↔ Skill {@code @Component}s).</li>
 * </ul>
 *
 * <p><b>Why no Skill-specific wiring (cf. {@code BashTool.setProcessRunner}):</b>
 * Skills have no external dependencies — they are pure {@code (call, ctx) → ToolResult}
 * transformations. Pure registration is sufficient.
 *
 * <p><b>Bean wiring order:</b>
 * <ol>
 *   <li>Spring creates {@code @Component}-typed Skill beans (e.g. {@link CommitSkill})
 *       as part of component-scanning.</li>
 *   <li>{@link #afterPropertiesSet()} fires after constructor injection; we iterate the
 *       {@code Map<String, Skill>} and register each with the shared
 *       {@link ToolRegistry}. The registry's {@code register} method does the
 *       {@code instanceof Skill} check internally (Story #020a T-04) and updates
 *       both {@code registry} and {@code skillsByName} indices.</li>
 *   <li>From this point on, any caller of {@code ToolRegistry.findSkill("commit")},
 *       {@code toolRegistry.skillNames()}, or {@code toolRegistry.modelVisibleSpecs()}
 *       sees the registered Skills.</li>
 * </ol>
 */
@Configuration
public class SkillAutoConfiguration implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(SkillAutoConfiguration.class);

    /** Property key — set to {@code false} in {@code application.yml} to disable. */
    public static final String PROP_ENABLED = "agent.skills.enabled";

    private final ToolRegistry toolRegistry;
    private final Map<String, Skill> skills;
    private final Environment environment;

    /**
     * Constructor injection of all dependencies. {@link Skill} beans are injected as a
     * {@code Map<String, Skill>} where keys are the {@code @Component} bean names
     * (e.g. {@code "commitSkill"}).
     *
     * <p>The first argument is the SPI-level {@link ToolRegistry} — this keeps the
     * wiring open to any {@code ToolRegistry} implementation (including user-supplied
     * alternatives).
     *
     * <p><b>{@code @Lazy} on the {@link Map} parameter:</b> as with
     * {@code LocalToolsAutoConfiguration}, Spring's eager bean-creation order could
     * create a cycle ({@code SkillAutoConfiguration} ↔ {@code commitSkill}). The
     * {@code @Lazy} proxy defers actual lookup until {@link #afterPropertiesSet()}
     * iterates it — by that point all Skills have been instantiated.
     */
    @Autowired
    public SkillAutoConfiguration(
            ToolRegistry toolRegistry,
            @Lazy Map<String, Skill> skills,
            Environment environment) {
        this.toolRegistry = toolRegistry;
        this.skills = skills;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean enabled = environment.getProperty(PROP_ENABLED, Boolean.class, Boolean.TRUE);
        if (!enabled) {
            LOG.info("Skills disabled via {}={} — Skills constructed but NOT registered",
                PROP_ENABLED, enabled);
            return;
        }

        int registered = 0;
        for (Map.Entry<String, Skill> e : skills.entrySet()) {
            Skill skill = e.getValue();
            toolRegistry.register(skill);  // register internally does instanceof Skill 分流
            registered++;
            LOG.debug("Registered skill: beanName={} skillName={} class={}",
                e.getKey(), skill.name(), skill.getClass().getSimpleName());
        }
        // Sorted name list for the INFO log line — stable ordering aids log grep.
        List<String> sorted = new ArrayList<>();
        for (Skill s : skills.values()) sorted.add(s.name());
        Collections.sort(sorted);
        LOG.info("Skills ready — {} skill(s) registered: {}",
            registered, sorted);
    }
}
