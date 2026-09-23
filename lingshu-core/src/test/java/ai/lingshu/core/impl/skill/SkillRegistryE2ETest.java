package ai.lingshu.core.impl.skill;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020a — L3 integration test for the Skill registry wiring.
 *
 * <p>AC-020a-12: when Spring boots, the shared {@link DefaultToolRegistry} bean has both
 * the {@link CommitSkill} {@code @Component} registered AND the registry surfaces the
 * Skill in {@code modelVisibleSpecs()} sorted by name.
 *
 * <p>Uses raw {@link AnnotationConfigApplicationContext} (not {@code @SpringBootTest})
 * because {@code spring-boot-test} / {@code spring-test} are not on the classpath — the
 * dep tree is kept minimal per R-13 mitigation (d). The integration under test is
 * "Spring wires the bean → afterPropertiesSet → registry has the Skill".
 */
class SkillRegistryE2ETest {

    private static AnnotationConfigApplicationContext context;

    @AfterAll
    static void closeContext() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    private static synchronized AnnotationConfigApplicationContext bootContext() {
        if (context == null) {
            // Force agent.skills.enabled=true so the AfterPropertiesSet registration runs
            AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
            ConfigurableEnvironment env = ctx.getEnvironment();
            MutablePropertySources sources = env.getPropertySources();
            sources.addFirst(new MapPropertySource("SkillE2EOverride",
                Collections.<String, Object>singletonMap(
                    SkillAutoConfiguration.PROP_ENABLED, "true")));
            ctx.register(MinimalSkillContext.class);
            ctx.refresh();
            context = ctx;
        }
        return context;
    }

    @Test
    @DisplayName("AC-020a-12: L3 Spring wiring → registry has CommitSkill visible in modelVisibleSpecs")
    void springWiring_registrySurfacesCommitSkill() {
        AnnotationConfigApplicationContext ctx = bootContext();
        ToolRegistry registry = ctx.getBean(ToolRegistry.class);

        List<ToolSpec> specs = registry.modelVisibleSpecs();

        // Single Skill wired via @Component
        assertThat(specs).hasSize(1);

        ToolSpec commitSpec = specs.get(0);
        assertThat(commitSpec.getName()).isEqualTo("commit");
        assertThat(commitSpec.getDescription()).contains("Conventional Commits");
        assertThat(commitSpec.getInputSchema()).isNotNull();
        assertThat(commitSpec.getInputSchema().get("properties").get("input").get("type").asText())
            .isEqualTo("string");

        // Skill is reachable by findSkill / findByName
        assertThat(registry.findSkill("commit")).isNotNull();
        assertThat(registry.skillNames()).containsExactly("commit");
    }

    /**
     * Minimal Spring config that mirrors what {@code SkillAutoConfiguration} expects:
     * a {@link ToolRegistry} bean, a {@link CommitSkill} {@code @Component}, and
     * {@link SkillAutoConfiguration} itself.
     *
     * <p>{@code SkillAutoConfiguration} constructor injects
     * {@code Map<String, Skill>}; Spring's component scan finds {@link CommitSkill} and
     * collects it under bean name {@code commitSkill} per the {@code @Component("commitSkill")}
     * declaration.
     */
    @Configuration
    @ComponentScan(basePackageClasses = CommitSkill.class)
    @Import(SkillAutoConfiguration.class)
    static class MinimalSkillContext {

        /**
         * Expose a {@link DefaultToolRegistry} bean so {@link SkillAutoConfiguration}
         * can register into it. Same as the production wiring path.
         */
        @Bean
        public ToolRegistry toolRegistry() {
            return new DefaultToolRegistry();
        }
    }
}
