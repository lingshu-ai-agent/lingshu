package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.slot.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #020b — L2 functional test for {@link DirectorySkillSource}.
 *
 * <p>AC-020b-2: scans a configured directory one level deep for {@code SKILL.md} files
 * and produces one {@link Skill} per subdirectory that contains one.
 *
 * <p>{@link TempDir} provides an isolated scratch directory per test — JUnit 5 cleans up
 * after the test regardless of pass / fail.
 */
class DirectorySkillSourceTest {

    @Test
    @DisplayName("AC-020b-2: discovers all SKILL.md files one level under the configured dir")
    void discoversAllSkills(@TempDir Path tmp) throws IOException {
        writeSkill(tmp, "alpha", "Alpha skill");
        writeSkill(tmp, "beta", "Beta skill");
        writeSkill(tmp, "gamma", "Gamma skill");

        DirectorySkillSource source = new DirectorySkillSource(tmp.toString());
        List<Skill> skills = source.discover();

        assertThat(skills).hasSize(3);
        Set<String> names = new HashSet<String>();
        for (Skill s : skills) names.add(s.name());
        assertThat(names).containsExactlyInAnyOrder("alpha", "beta", "gamma");
    }

    @Test
    @DisplayName("AC-020b-2: subdirectories without SKILL.md are silently skipped")
    void skipsDirsWithoutSkillFile(@TempDir Path tmp) throws IOException {
        writeSkill(tmp, "alpha", "Alpha");
        Files.createDirectories(tmp.resolve("no-skill-here"));       // missing SKILL.md → skip
        Files.createFile(tmp.resolve("stray.txt"));                  // regular file → skip

        DirectorySkillSource source = new DirectorySkillSource(tmp.toString());
        List<Skill> skills = source.discover();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).name()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("AC-020b-2: empty directory returns empty list (no exception)")
    void emptyDirReturnsEmpty(@TempDir Path tmp) throws IOException {
        DirectorySkillSource source = new DirectorySkillSource(tmp.toString());
        assertThat(source.discover()).isEmpty();
    }

    @Test
    @DisplayName("EC-020b-2: non-existent directory throws IOException")
    void nonExistentDirThrows(@TempDir Path tmp) {
        DirectorySkillSource source = new DirectorySkillSource(tmp.resolve("does-not-exist").toString());
        assertThatThrownBy(source::discover)
            .isInstanceOf(IOException.class)
            .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("AC-020b-2: type=directory, watchable=true (filesystem mutable)")
    void typeAndWatchableEcho() {
        DirectorySkillSource source = new DirectorySkillSource("/tmp/foo");
        assertThat(source.type()).isEqualTo("directory");
        assertThat(source.watchable()).isTrue();
        assertThat(source.location()).isEqualTo("/tmp/foo");
    }

    private static void writeSkill(Path root, String name, String title) throws IOException {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.write(dir.resolve("SKILL.md"),
            ("# " + title + "\n\nBody for " + name + ".").getBytes(StandardCharsets.UTF_8));
    }
}
