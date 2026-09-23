package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.slot.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #020b — L2 functional test for {@link ClasspathSkillSource}.
 *
 * <p>AC-020b-1: discovers {@code SKILL.md} files under {@code classpath:skills/agent-builtin/}
 * and produces 3 {@link Skill} instances (commit / review / docs).
 *
 * <p>Fixtures live at {@code src/test/resources/skills/agent-builtin/<name>/SKILL.md}
 * (committed alongside the test so they ride the test classpath).
 */
class ClasspathSkillSourceTest {

    @Test
    @DisplayName("AC-020b-1: discovers 3 SKILL.md files under classpath:skills/agent-builtin/")
    void discoversThreeSkills() throws Exception {
        ClasspathSkillSource source = new ClasspathSkillSource("classpath:skills/agent-builtin/");

        List<Skill> skills = source.discover();

        assertThat(skills).hasSize(3);
        Set<String> names = new HashSet<String>();
        for (Skill s : skills) names.add(s.name());
        assertThat(names).containsExactlyInAnyOrder("commit", "review", "docs");
    }

    @Test
    @DisplayName("AC-020b-1: each Skill description equals the first H1 title from SKILL.md")
    void descriptionMatchesFirstH1() throws Exception {
        ClasspathSkillSource source = new ClasspathSkillSource("classpath:skills/agent-builtin/");

        List<Skill> skills = source.discover();

        Skill commit = findByName(skills, "commit");
        assertThat(commit.description()).isEqualTo("commit");

        Skill review = findByName(skills, "review");
        assertThat(review.description()).isEqualTo("review");
    }

    @Test
    @DisplayName("AC-020b-1: type() and location() echo the routing key and configured prefix")
    void typeAndLocationEcho() {
        ClasspathSkillSource source = new ClasspathSkillSource("classpath:skills/agent-builtin/");

        assertThat(source.type()).isEqualTo("classpath");
        assertThat(source.location()).isEqualTo("classpath:skills/agent-builtin/");
        assertThat(source.watchable()).isFalse();
    }

    private static Skill findByName(List<Skill> skills, String name) {
        for (Skill s : skills) {
            if (s.name().equals(name)) return s;
        }
        throw new AssertionError("Skill not found: " + name);
    }
}
