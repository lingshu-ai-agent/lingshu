package ai.lingshu.cli;

import ai.lingshu.core.impl.runtime.AgentFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #017 — {@code resume} subcommand unit tests. 2 cases covering:
 * <ul>
 *   <li>memory-only continuation: doResume() prints "resuming memory session:" + final text</li>
 *   <li>missing YAML → {@link LingsCliException} LINGS-Z02</li>
 * </ul>
 *
 * <p>Story #014 will replace the in-memory stub with FileSessionStore / Redis / JDBC.
 */
class ResumeHandlerTest {

    private AgentFactory factory;
    private ByteArrayOutputStream outBuf;
    private CliRunner runner;

    @BeforeEach
    void setUp() {
        factory = TestSupport.buildFactory();
        outBuf = new ByteArrayOutputStream();
        runner = new CliRunner(factory, new PrintStream(outBuf), new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void resume_withMemorySession_continueWithUserMessage(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, "agent:\n  llm:\n    provider: anthropic\n    model: t\n  sandbox:\n    policy: default\n".getBytes("UTF-8"));

        Args args = new Args(Subcommand.RESUME, yml, "continue", "sess-abc", null, false, false);
        runner.doResume(args);

        String stdout = outBuf.toString("UTF-8");
        assertThat(stdout)
            .contains("[LINGS-Z99] resuming memory session: sess-abc")
            .contains("in-memory only")
            .contains("[LINGS-Z99] turns=0");
    }

    @Test
    void resume_withMissingYaml_throwsLingsZ02(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.yml");
        Args args = new Args(Subcommand.RESUME, missing, "x", "s1", null, false, false);

        assertThatThrownBy(() -> runner.doResume(args))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> assertThat(((LingsCliException) e).getErrorCode()).isEqualTo("LINGS-Z02"));
    }
}
