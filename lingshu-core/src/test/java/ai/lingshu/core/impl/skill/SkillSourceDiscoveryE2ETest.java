package ai.lingshu.core.impl.skill;

import ai.lingshu.core.impl.skill.source.ClasspathSkillSourceProvider;
import ai.lingshu.core.impl.skill.source.DirectorySkillSourceProvider;
import ai.lingshu.core.impl.skill.source.SkillSourceRouter;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.slot.ToolRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020b — L3 end-to-end integration test for the Skill source discovery wiring.
 *
 * <p>AC-020b-6: a real Spring context boots with both v1 {@code SkillSourceProvider}
 * beans registered; {@code SkillAutoConfiguration.afterPropertiesSet()} runs Phase 1
 * (SKILL.md scan) and Phase 2 ({@code @Component} Skills); the shared {@link
 * DefaultToolRegistry} surfaces both phases' Skills.
 *
 * <p>Mirrors {@link SkillRegistryE2ETest}'s pattern — uses
 * {@link AnnotationConfigApplicationContext} (not {@code @SpringBootTest}) because
 * {@code spring-boot-test} / {@code spring-test} are not on the classpath per R-13
 * mitigation (d).
 */
class SkillSourceDiscoveryE2ETest {

    private static AnnotationConfigApplicationContext context;

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    /**
     * Boot a fresh context with the given property overrides applied at the highest
     * priority. Each test gets its own context (close + re-create) — contexts are not
     * cached because the property overrides vary between tests.
     */
    private static synchronized AnnotationConfigApplicationContext bootContext(
            Map<String, Object> overrides) {
        if (context != null) {
            context.close();
            context = null;
        }
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ConfigurableEnvironment env = ctx.getEnvironment();
        MutablePropertySources sources = env.getPropertySources();
        if (!overrides.isEmpty()) {
            sources.addFirst(new MapPropertySource("Story020bE2E", overrides));
        }
        ctx.register(MinimalSkillSourceContext.class);
        ctx.refresh();
        context = ctx;
        return ctx;
    }

    @Test
    @DisplayName("AC-020b-6: L3 Spring boot wires Phase 1 (SKILL.md) + Phase 2 (@Component) into registry")
    void phase1AndPhase2BothRegister() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("agent.skills.sources[0].type", "classpath");
        props.put("agent.skills.sources[0].location", "classpath:skills/agent-builtin/");
        AnnotationConfigApplicationContext ctx = bootContext(props);

        ToolRegistry registry = ctx.getBean(ToolRegistry.class);
        List<ToolSpec> specs = registry.modelVisibleSpecs();

        // 3 SKILL.md Skills (commit/review/docs) + 1 @Component Skill (CommitSkill).
        // "commit" appears in BOTH phases → Phase 2 wins → "commit" appears once.
        // Total: review + docs (Phase-1-only) + commit (Phase 2) = 3 unique names.
        Set<String> names = new HashSet<String>();
        for (ToolSpec s : specs) names.add(s.getName());
        assertThat(names).containsExactlyInAnyOrder("commit", "review", "docs");
        assertThat(specs).hasSize(3);
    }

    @Test
    @DisplayName("AC-020b-6: Phase 1 (SKILL.md) wins on name collision — registry uses putIfAbsent first-wins")
    void phase1WinsOnCollisionInRegistry() {
        Map<String, Object> props = new LinkedHashMap<String, Object>();
        props.put("agent.skills.sources[0].type", "classpath");
        props.put("agent.skills.sources[0].location", "classpath:skills/agent-builtin/");
        AnnotationConfigApplicationContext ctx = bootContext(props);

        ToolRegistry registry = ctx.getBean(ToolRegistry.class);

        // "commit" exists in both Phase 1 (commit/SKILL.md → SkillTool) and Phase 2
        // (CommitSkill @Component). The ToolRegistry's putIfAbsent semantics mean the
        // FIRST registered wins — and Phase 1 registers before Phase 2 — so the
        // SKILL.md-derived SkillTool is what the registry surfaces.
        // (This is intentional: file-based Skills take precedence over hardcoded
        //  @Component Skills when both define the same name, so users can override
        //  built-in Skills by dropping a same-named SKILL.md file.)
        assertThat(registry.findSkill("commit"))
            .isInstanceOf(SkillTool.class);
    }

    @Test
    @DisplayName("AC-020b-6: no agent.skills.sources → Phase 1 empty, Phase 2 @Component Skills still register")
    void noSourcesConfigPhase1EmptyPhase2StillRegisters() {
        // Plain boot — no sources[] keys at all
        AnnotationConfigApplicationContext ctx = bootContext(Collections.<String, Object>emptyMap());

        ToolRegistry registry = ctx.getBean(ToolRegistry.class);

        // Only CommitSkill (Phase 2) — no SKILL.md Skills
        assertThat(registry.skillNames()).containsExactly("commit");
    }

    /**
     * Minimal Spring config:
     * <ul>
     *   <li>{@link DefaultToolRegistry} bean (shared by all Skills)</li>
     *   <li>Explicit {@code @Bean}s for both v1 {@link SkillSourceProvider} beans
     *       (component-scan would also find them, but explicit beans are deterministic
     *       and document the dependency)</li>
     *   <li>{@link SkillSourceRouter} + {@link SkillAutoConfiguration}</li>
     * </ul>
     */
    @Configuration
    @ComponentScan(basePackageClasses = CommitSkill.class)
    @Import({SkillAutoConfiguration.class, SkillSourceRouter.class})
    static class MinimalSkillSourceContext {

        @Bean
        public ToolRegistry toolRegistry() {
            return new DefaultToolRegistry();
        }

        @Bean
        public ClasspathSkillSourceProvider classpathSkillSourceProvider() {
            return new ClasspathSkillSourceProvider();
        }

        @Bean
        public DirectorySkillSourceProvider directorySkillSourceProvider() {
            return new DirectorySkillSourceProvider();
        }
    }
}
