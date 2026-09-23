package ai.lingshu.core.impl.skill.source;

import ai.lingshu.core.impl.skill.SkillSourceProperties;
import ai.lingshu.core.slot.Skill;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #020b — L2 functional test for {@link CompositeSkillLoader}.
 *
 * <p>AC-020b-4: orchestrates discovery across all configured sources; merges Phase 1
 * (SKILL.md) with Phase 2 ({@code @Component}-typed Skills); Phase 2 wins on name
 * collision; one failing source does not block others.
 */
class CompositeSkillLoaderTest {

    @Test
    @DisplayName("AC-020b-4: loadAll discovers Skills from both classpath and directory sources")
    void loadsFromBothSources() throws IOException {
        SkillSourceProperties props = propsWith(
            entry("classpath", "classpath:skills/agent-builtin/"),
            entry("directory", scratchWithSkills("foo", "bar"))
        );

        SkillSourceRouter router = new SkillSourceRouter(Arrays.asList(
            new ClasspathSkillSourceProvider(),
            new DirectorySkillSourceProvider()
        ));
        CompositeSkillLoader loader = new CompositeSkillLoader(router);

        Map<String, Skill> loaded = loader.loadAll(props);

        // 3 from classpath (commit / review / docs) + 2 from directory (foo / bar)
        assertThat(loaded.keySet()).containsExactlyInAnyOrder(
            "commit", "review", "docs", "foo", "bar");
    }

    @Test
    @DisplayName("AC-020b-4: empty sources list returns empty map (no exception)")
    void emptySourcesReturnsEmpty() {
        SkillSourceProperties props = new SkillSourceProperties(); // defaults: no sources
        SkillSourceRouter router = new SkillSourceRouter(Collections.<ai.lingshu.core.slot.SkillSourceProvider>emptyList());
        CompositeSkillLoader loader = new CompositeSkillLoader(router);

        Map<String, Skill> loaded = loader.loadAll(props);
        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("AC-020b-4: one failing source does not block others")
    void failingSourceDoesNotBlockOthers(@TempDir Path tmp) throws IOException {
        SkillSourceProperties props = propsWith(
            entry("directory", "/nonexistent/path/that/does/not/exist"),  // will throw IOException
            entry("directory", scratchWithSkills("alive"))
        );

        SkillSourceRouter router = new SkillSourceRouter(Collections.singletonList(
            new DirectorySkillSourceProvider()
        ));
        CompositeSkillLoader loader = new CompositeSkillLoader(router);

        Map<String, Skill> loaded = loader.loadAll(props);

        // The bad source is logged-and-skipped; the good source still contributes
        assertThat(loaded.keySet()).containsExactly("alive");
    }

    @Test
    @DisplayName("AC-020b-4: unknown source type throws IllegalArgumentException (YAML misconfig)")
    void unknownTypePropagates() {
        SkillSourceProperties props = propsWith(entry("git", "https://example.com/repo"));

        SkillSourceRouter router = new SkillSourceRouter(Collections.<ai.lingshu.core.slot.SkillSourceProvider>emptyList());
        CompositeSkillLoader loader = new CompositeSkillLoader(router);

        assertThatThrownBy(() -> loader.loadAll(props))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown SkillSource type 'git'");
    }

    @Test
    @DisplayName("AC-020b-4: mergePhases keeps Phase 1 (SKILL.md) on collision — first-wins in registry semantics")
    void phaseOneWinsInMergeResult() {
        CompositeSkillLoader loader = new CompositeSkillLoader(
            new SkillSourceRouter(Collections.<ai.lingshu.core.slot.SkillSourceProvider>emptyList()));

        Skill phase1 = stubSkill("commit", "phase-1 desc");
        Skill phase2 = stubSkill("commit", "phase-2 desc");

        Map<String, Skill> p1 = new LinkedHashMap<String, Skill>();
        p1.put("commit", phase1);

        Map<String, Skill> p2 = new LinkedHashMap<String, Skill>();
        p2.put("commit", phase2);

        Map<String, Skill> merged = loader.mergePhases(p1, p2);

        // Phase 1 (SKILL.md) wins — file-based Skills override hardcoded @Component Skills
        // when both define the same name, so users can drop a same-named SKILL.md to
        // override a built-in. This matches the registry's putIfAbsent semantics.
        assertThat(merged.get("commit")).isSameAs(phase1);
    }

    @Test
    @DisplayName("AC-020b-4: mergePhases keeps both Skills when names differ")
    void mergePhasesKeepsDisjointNames() {
        CompositeSkillLoader loader = new CompositeSkillLoader(
            new SkillSourceRouter(Collections.<ai.lingshu.core.slot.SkillSourceProvider>emptyList()));

        Skill a = stubSkill("a", "desc a");
        Skill b = stubSkill("b", "desc b");

        Map<String, Skill> p1 = new HashMap<String, Skill>();
        p1.put("a", a);
        Map<String, Skill> p2 = new HashMap<String, Skill>();
        p2.put("b", b);

        Map<String, Skill> merged = loader.mergePhases(p1, p2);
        assertThat(merged).hasSize(2);
        assertThat(merged.get("a")).isSameAs(a);
        assertThat(merged.get("b")).isSameAs(b);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static SkillSourceProperties propsWith(SkillSourceProperties.SourceEntry... entries) {
        SkillSourceProperties p = new SkillSourceProperties();
        p.setSources(Arrays.asList(entries));
        return p;
    }

    private static SkillSourceProperties.SourceEntry entry(String type, String location) {
        SkillSourceProperties.SourceEntry e = new SkillSourceProperties.SourceEntry();
        e.setType(type);
        e.setLocation(location);
        return e;
    }

    private static String scratchWithSkills(String... names) throws IOException {
        Path tmp = Files.createTempDirectory("csl-test-");
        for (String n : names) {
            Path dir = Files.createDirectories(tmp.resolve(n));
            Files.write(dir.resolve("SKILL.md"),
                ("# " + n + "\n\nbody").getBytes(StandardCharsets.UTF_8));
        }
        return tmp.toString();
    }

    private static Skill stubSkill(String name, String description) {
        return new Skill() {
            @Override public String name() { return name; }
            @Override public String description() { return description; }
            @Override public com.fasterxml.jackson.databind.JsonNode inputSchema() {
                return null;
            }
            @Override public ai.lingshu.core.message.ToolResult execute(
                    ai.lingshu.core.message.ToolCall call, ai.lingshu.core.slot.ToolExecutionContext ctx) {
                return null;
            }
        };
    }
}
