package ai.lingshu.core.impl.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020b — L1 binding test for {@link SkillSourceProperties}.
 *
 * <p>AC-020b-3: yml structure {@code agent.skills.sources[0].type} + {@code .location}
 * binds into {@link SkillSourceProperties#getSources()} list entries.
 *
 * <p>EC-020b-1: when yml omits {@code agent.skills} entirely, the defaults
 * ({@code enabled=true}, {@code hotReload=false}, {@code sources=[]}) apply — no NPE.
 *
 * <p>Why direct {@link StandardEnvironment} + {@link MapPropertySource} instead of
 * {@code Binder}/{@code @SpringBootTest}: lingshu-core's compile classpath
 * includes only {@code spring-context} (not {@code spring-boot}), per R-13
 * dependency lock. {@code Binder} lives in {@code spring-boot-context.properties}
 * which is unavailable. The {@link SkillSourceProperties#bindFromEnvironment} static
 * factory reads properties directly via {@link org.springframework.core.env.Environment#getProperty}.
 */
class SkillSourcePropertiesTest {

    @Test
    @DisplayName("AC-020b-3: yml-style nested map binds into SkillSourceProperties")
    void bindFromYaml() {
        Map<String, Object> props = new HashMap<>();
        props.put("agent.skills.enabled", "true");
        props.put("agent.skills.hot-reload", "false");
        props.put("agent.skills.sources[0].type", "classpath");
        props.put("agent.skills.sources[0].location", "classpath:skills/agent-builtin/");
        props.put("agent.skills.sources[1].type", "directory");
        props.put("agent.skills.sources[1].location", "./skills/");

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        SkillSourceProperties bound = SkillSourceProperties.bindFromEnvironment(env);

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.isHotReload()).isFalse();
        assertThat(bound.getSources()).hasSize(2);
        assertThat(bound.getSources().get(0).getType()).isEqualTo("classpath");
        assertThat(bound.getSources().get(0).getLocation()).isEqualTo("classpath:skills/agent-builtin/");
        assertThat(bound.getSources().get(1).getType()).isEqualTo("directory");
        assertThat(bound.getSources().get(1).getLocation()).isEqualTo("./skills/");
    }

    @Test
    @DisplayName("EC-020b-1: defaults apply when agent.skills is entirely absent")
    void defaultEmptySources() {
        StandardEnvironment env = new StandardEnvironment();

        SkillSourceProperties bound = SkillSourceProperties.bindFromEnvironment(env);

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.isHotReload()).isFalse();
        assertThat(bound.getSources()).isEmpty();
    }

    @Test
    @DisplayName("AC-020b-3: hotReload=true binds as boolean true (not 'true' String)")
    void hotReload_trueBindsAsBoolean() {
        Map<String, Object> props = new HashMap<>();
        props.put("agent.skills.hot-reload", "true");
        props.put("agent.skills.sources[0].type", "directory");
        props.put("agent.skills.sources[0].location", "/tmp/skills");

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        SkillSourceProperties bound = SkillSourceProperties.bindFromEnvironment(env);

        assertThat(bound.isHotReload()).isTrue();
        assertThat(bound.getSources()).hasSize(1);
        assertThat(bound.getSources().get(0).getType()).isEqualTo("directory");
    }

    @Test
    @DisplayName("AC-020b-3: enabled=false binds correctly")
    void enabled_falseBinds() {
        Map<String, Object> props = new HashMap<>();
        props.put("agent.skills.enabled", "false");

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));

        SkillSourceProperties bound = SkillSourceProperties.bindFromEnvironment(env);

        assertThat(bound.isEnabled()).isFalse();
    }
}
