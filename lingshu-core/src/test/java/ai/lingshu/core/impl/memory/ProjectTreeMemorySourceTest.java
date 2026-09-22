package ai.lingshu.core.impl.memory;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #002 US3 — ProjectTreeMemorySource contract tests (depth-1 walk).
 * See data-model.md §3.4 + contracts/memory-source.md §3.4.
 */
class ProjectTreeMemorySourceTest {

    @Test
    @DisplayName("load_emptyDir_returnsNull")
    void load_emptyDir_returnsNull(@TempDir Path tmp) throws IOException {
        AgentConfig cfg = withWorkingDir(AgentConfigDefaults.defaults(), tmp);
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(cfg);

        assertThat(src.load(null)).isNull();
    }

    @Test
    @DisplayName("load_threeMdFiles_concatenatedInAlphabeticalOrder")
    void load_threeMdFiles_concatenatedInAlphabeticalOrder(@TempDir Path tmp) throws IOException {
        Files.write(tmp.resolve("a-third.md"), "third content".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("b-first.md"), "first content".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("c-second.md"), "second content".getBytes(StandardCharsets.UTF_8));

        AgentConfig cfg = withWorkingDir(AgentConfigDefaults.defaults(), tmp);
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(cfg);

        String out = src.load(null);
        assertThat(out).isNotNull();
        // alphabetical order: a-third.md, b-first.md, c-second.md
        int idx1 = out.indexOf("third content");
        int idx2 = out.indexOf("first content");
        int idx3 = out.indexOf("second content");
        assertThat(idx1).isLessThan(idx2);
        assertThat(idx2).isLessThan(idx3);
        assertThat(out).contains("── separator ──");
        // 2 separators between 3 files
        int sepCount = 0;
        int idx = 0;
        while ((idx = out.indexOf("── separator ──", idx)) != -1) { sepCount++; idx++; }
        assertThat(sepCount).isEqualTo(2);
    }

    @Test
    @DisplayName("load_mixedExtensions_onlyMdIncluded")
    void load_mixedExtensions_onlyMdIncluded(@TempDir Path tmp) throws IOException {
        Files.write(tmp.resolve("notes.md"), "MARKDOWN".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("readme.txt"), "TEXT".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("Main.java"), "JAVA".getBytes(StandardCharsets.UTF_8));

        AgentConfig cfg = withWorkingDir(AgentConfigDefaults.defaults(), tmp);
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(cfg);

        String out = src.load(null);
        assertThat(out).contains("MARKDOWN");
        assertThat(out).doesNotContain("TEXT");
        assertThat(out).doesNotContain("JAVA");
    }

    @Test
    @DisplayName("load_uppercaseMdExtension_included")
    void load_uppercaseMdExtension_included(@TempDir Path tmp) throws IOException {
        Files.write(tmp.resolve("README.MD"), "upper".getBytes(StandardCharsets.UTF_8));

        AgentConfig cfg = withWorkingDir(AgentConfigDefaults.defaults(), tmp);
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(cfg);

        String out = src.load(null);
        assertThat(out).contains("upper");
    }

    @Test
    @DisplayName("load_subdirectoryMd_notIncluded_depth1Only")
    void load_subdirectoryMd_notIncluded_depth1Only(@TempDir Path tmp) throws IOException {
        Files.createDirectory(tmp.resolve("docs"));
        Files.write(tmp.resolve("docs/deep.md"), "deep content".getBytes(StandardCharsets.UTF_8));
        Files.write(tmp.resolve("shallow.md"), "shallow content".getBytes(StandardCharsets.UTF_8));

        AgentConfig cfg = withWorkingDir(AgentConfigDefaults.defaults(), tmp);
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(cfg);

        String out = src.load(null);
        assertThat(out).contains("shallow content");
        assertThat(out).doesNotContain("deep content");
    }

    @Test
    @DisplayName("load_missingRootDir_returnsNull")
    void load_missingRootDir_returnsNull() {
        AgentConfig cfg = withWorkingDir(AgentConfigDefaults.defaults(), Paths.get("/nonexistent/path/abc"));
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(cfg);

        assertThat(src.load(null)).isNull();
    }

    @Test
    @DisplayName("load_brokenSymlink_skipped")
    void load_brokenSymlink_skipped(@TempDir Path tmp) throws IOException {
        Files.write(tmp.resolve("good.md"), "good".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(tmp.resolve("broken.md"),
            Paths.get("/nonexistent/target.md"));

        AgentConfig cfg = withWorkingDir(AgentConfigDefaults.defaults(), tmp);
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(cfg);

        String out = src.load(null);
        // Files.isRegularFile on a broken symlink returns false → skipped
        // Only good.md contributes
        assertThat(out).contains("good");
        assertThat(out).doesNotContain("broken content");
    }

    @Test
    @DisplayName("name_andPriority_areCorrect")
    void name_andPriority_areCorrect() {
        ProjectTreeMemorySource src = new ProjectTreeMemorySource(AgentConfigDefaults.defaults());
        assertThat(src.name()).isEqualTo("project-tree");
        assertThat(src.priority()).isEqualTo(40);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private static AgentConfig withWorkingDir(AgentConfig base, Path workingDir) {
        AgentConfig.Sandbox sandbox = new AgentConfig.Sandbox(
            base.getSandbox().getPolicy(),
            base.getSandbox().getRuntime(),
            workingDir,
            base.getSandbox().getCommandWhitelist(),
            base.getSandbox().getDomainWhitelist());
        return new AgentConfig(
            base.getFlowEngine(), base.getLlm(), base.getPrompt(), base.getToolExecutor(),
            sandbox, base.getCompactor(), base.getSessionStore(),
            base.getDelegate(), base.getMcp(), base.getSkills(),
            base.getToolParallelism(), base.getToolTimeoutSeconds(),
            base.getApprovalTimeoutSeconds(), base.getTurnTimeoutSeconds(),
            base.getLlmTimeoutSeconds(), base.getReactMaxSteps(),
            base.getIdentity(), base.getInstructions(), base.getMemory(),
            base.getA2aTransport(),
            base.getTenants(),
            base.getA2a(),                                    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults());     // compactorConfig (Story #018)
    }
}
