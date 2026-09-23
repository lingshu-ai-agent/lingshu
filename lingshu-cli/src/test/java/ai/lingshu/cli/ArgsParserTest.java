package ai.lingshu.cli;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #017 — {@link ArgsParser} unit tests. 9 cases covering:
 * <ul>
 *   <li>happy path: each of 5 subcommands parses a representative flag set</li>
 *   <li>missing required flag → {@link LingsCliException}</li>
 *   <li>empty argv → {@link LingsCliException}</li>
 *   <li>unknown subcommand → {@link LingsCliException}</li>
 *   <li>both {@code --key value} and {@code --key=value} forms supported</li>
 *   <li>🆕 Story #020c — {@code --list-skills} flag parses cleanly and bypasses
 *       {@code --prompt}/{@code --session} requirements for {@code run}/{@code resume}</li>
 * </ul>
 *
 * <p>All assertions verify errorCode is {@code LINGS-Z01} (per dsh §15) and the exit code
 * is 2 (CLI arg class, per {@link LingsCliException#exitCodeFor}).
 */
class ArgsParserTest {

    // ── happy paths (5 cases) ────────────────────────────────────────────

    @Test
    void parse_withRunSubcommand_extractsPromptAndConfig() {
        Args args = ArgsParser.parse(new String[]{
            "run", "--config", "app.yml", "--prompt", "hello world"});

        assertThat(args.getSubcommand()).isEqualTo(Subcommand.RUN);
        assertThat(args.getConfigPath()).isEqualTo(Paths.get("app.yml"));
        assertThat(args.getPrompt()).isEqualTo("hello world");
        assertThat(args.getSessionId()).isNull();
        assertThat(args.getPort()).isEqualTo(ArgsParser.DEFAULT_PORT);
        assertThat(args.isPrintEffective()).isFalse();
        assertThat(args.isPrintSchema()).isFalse();
        assertThat(args.isPrintSkills()).isFalse();
    }

    @Test
    void parse_withServeAcceptsPortFlag() {
        Args args = ArgsParser.parse(new String[]{"serve", "--port", "18099"});

        assertThat(args.getSubcommand()).isEqualTo(Subcommand.SERVE);
        assertThat(args.getPort()).isEqualTo(18099);
    }

    @Test
    void parse_withDoctorAcceptsPrintSchemaFlag() {
        Args args = ArgsParser.parse(new String[]{"doctor", "--print-schema"});

        assertThat(args.getSubcommand()).isEqualTo(Subcommand.DOCTOR);
        assertThat(args.isPrintSchema()).isTrue();
    }

    @Test
    void parse_withMissingConfigFlag_defaultsToApplicationYml() {
        Args args = ArgsParser.parse(new String[]{"run", "--prompt", "hi"});

        assertThat(args.getConfigPath()).isEqualTo(Paths.get(ArgsParser.DEFAULT_CONFIG));
        assertThat(args.getPrompt()).isEqualTo("hi");
    }

    @Test
    void parse_withShortFlagForm_supported() {
        // --key=value (no space) — supported per ArgsParser.parseFlags
        Args args = ArgsParser.parse(new String[]{
            "run", "--config=app.yml", "--prompt=say hi"});

        assertThat(args.getConfigPath()).isEqualTo(Paths.get("app.yml"));
        assertThat(args.getPrompt()).isEqualTo("say hi");
    }

    // ── error cases (3 cases) ────────────────────────────────────────────

    @Test
    void parse_withEmptyArgs_throwsLingsZ01() {
        assertThatThrownBy(() -> ArgsParser.parse(new String[]{}))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> {
                LingsCliException lce = (LingsCliException) e;
                assertThat(lce.getErrorCode()).isEqualTo("LINGS-Z01");
                assertThat(lce.getExitCode()).isEqualTo(2);
                assertThat(lce.getMessage()).contains("no subcommand given");
            });
    }

    @Test
    void parse_withUnknownSubcommand_throwsLingsZ01() {
        assertThatThrownBy(() -> ArgsParser.parse(new String[]{"frobnicate", "--prompt", "x"}))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> {
                LingsCliException lce = (LingsCliException) e;
                assertThat(lce.getErrorCode()).isEqualTo("LINGS-Z01");
                assertThat(lce.getMessage()).contains("unknown subcommand");
            });
    }

    @Test
    void parse_withResumeRequiresSessionId() {
        // EC-L5-2: resume requires both --session and --prompt; missing session → LINGS-Z01
        assertThatThrownBy(() -> ArgsParser.parse(new String[]{"resume", "--prompt", "x"}))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> {
                LingsCliException lce = (LingsCliException) e;
                assertThat(lce.getErrorCode()).isEqualTo("LINGS-Z01");
                assertThat(lce.getMessage()).contains("--session");
            });
    }

    // ── 🆕 Story #020c: --list-skills flag ──────────────────────────────

    @Test
    void parse_withListSkillsFlag_parsesAndBypassesPromptRequirement() {
        // 🆕 AC-020c-AC: `lingshu run --list-skills` (no --prompt) must parse cleanly
        // so the Skill banner can be printed without invoking the Agent.
        Args args = ArgsParser.parse(new String[]{"run", "--list-skills"});

        assertThat(args.getSubcommand()).isEqualTo(Subcommand.RUN);
        assertThat(args.isPrintSkills()).isTrue();
        assertThat(args.getPrompt()).isNull();
    }

    @Test
    void parse_resumeWithListSkillsFlag_bypassesSessionAndPromptRequirement() {
        // 🆕 AC-020c-AC: `lingshu resume --list-skills` (no --session, no --prompt)
        // also parses cleanly.
        Args args = ArgsParser.parse(new String[]{"resume", "--list-skills"});

        assertThat(args.getSubcommand()).isEqualTo(Subcommand.RESUME);
        assertThat(args.isPrintSkills()).isTrue();
        assertThat(args.getSessionId()).isNull();
        assertThat(args.getPrompt()).isNull();
    }
}
