package ai.lingshu.core.impl.skill;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.slot.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020a — L2 wiring test for {@link SkillAutoConfiguration} (dsh §6.4).
 *
 * <p>Verifies AC-020a-11 (enabled=true registers all Skill beans; enabled=false
 * skips) and EC-020a-4 (manual SkillTool registration via the same config works).
 *
 * <p>Why instantiate manually (mirror {@link ai.lingshu.core.impl.tool.local.LocalToolsAutoConfigurationTest}):
 * no Spring context boot needed — tests the smallest path that exercises register logic.
 */
class SkillAutoConfigurationTest {

    @Test
    @DisplayName("AC-020a-11: enabled=true registers all Skill beans in the shared registry")
    void enabled_registersSkills() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        CommitSkill commit = new CommitSkill();
        Skill echo = SkillTool.fromMarkdown("echo", "# echo back\nEcho the user input.");
        Map<String, Skill> beans = new LinkedHashMap<>();
        beans.put("commitSkill", commit);
        beans.put("echoSkill", echo);
        Environment env = enabledEnv();

        SkillAutoConfiguration cfg = new SkillAutoConfiguration(registry, beans, env);
        cfg.afterPropertiesSet();

        // Both registered, both visible as Skills
        assertThat(registry.skillNames()).containsExactlyInAnyOrder("commit", "echo");
        assertThat(registry.findSkill("commit")).isSameAs(commit);
        assertThat(registry.findSkill("echo")).isSameAs(echo);
    }

    @Test
    @DisplayName("AC-020a-11: enabled=false short-circuits — Skills not registered")
    void disabled_doesNotRegister() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        CommitSkill commit = new CommitSkill();
        Map<String, Skill> beans = new LinkedHashMap<>();
        beans.put("commitSkill", commit);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("SkillTestOverride",
            Collections.<String, Object>singletonMap(
                SkillAutoConfiguration.PROP_ENABLED, "false")));

        SkillAutoConfiguration cfg = new SkillAutoConfiguration(registry, beans, env);
        cfg.afterPropertiesSet();

        // Skills exist but were never registered
        assertThat(registry.skillNames()).isEmpty();
        assertThat(registry.findSkill("commit")).isNull();
        // The Skill bean itself is unaffected (just not registered)
        assertThat(commit.name()).isEqualTo("commit");
    }

    @Test
    @DisplayName("AC-020a-11: empty skill map → enabled=true yields zero registrations, no error")
    void enabled_emptySkillMap_yieldsZeroRegistrations() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        Map<String, Skill> beans = new LinkedHashMap<>();
        Environment env = enabledEnv();

        SkillAutoConfiguration cfg = new SkillAutoConfiguration(registry, beans, env);
        cfg.afterPropertiesSet();

        assertThat(registry.skillNames()).isEmpty();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    @DisplayName("EC-020a-4: SkillAutoConfiguration handles manual SkillTool.fromMarkdown beans")
    void enabled_handlesManualSkillToolBeans() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        Skill s = SkillTool.fromMarkdown("greet", "# Greet the user\nSay hello.");
        Map<String, Skill> beans = new LinkedHashMap<>();
        beans.put("greetSkill", s);
        Environment env = enabledEnv();

        SkillAutoConfiguration cfg = new SkillAutoConfiguration(registry, beans, env);
        cfg.afterPropertiesSet();

        assertThat(registry.skillNames()).containsExactly("greet");
        assertThat(registry.findSkill("greet")).isSameAs(s);
    }

    @Test
    @DisplayName("AC-020a-11: enabled=true default → property not set, registration proceeds")
    void enabled_defaultTrue_registers() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        CommitSkill commit = new CommitSkill();
        Map<String, Skill> beans = new LinkedHashMap<>();
        beans.put("commitSkill", commit);
        // Plain environment — no property overrides; default behavior is enabled=true
        Environment env = new StandardEnvironment();

        SkillAutoConfiguration cfg = new SkillAutoConfiguration(registry, beans, env);
        cfg.afterPropertiesSet();

        assertThat(registry.skillNames()).containsExactly("commit");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * Hermetic env with {@link SkillAutoConfiguration#PROP_ENABLED} forced to true at the
     * highest priority — does not mutate {@link System#getProperties()}.
     */
    private static Environment enabledEnv() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> overrides = Collections.<String, Object>singletonMap(
            SkillAutoConfiguration.PROP_ENABLED, "true");
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("SkillTestOverride", overrides));
        return env;
    }
}
