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
 * Story #017 — {@code run} subcommand unit tests. 3 cases covering:
 * <ul>
 *   <li>happy path: doRun() loads YAML, creates Agent, calls runBlocking, prints the trailer</li>
 *   <li>missing YAML file → {@link LingsCliException} with code LINGS-Z02</li>
 *   <li>invalid YAML → {@link LingsCliException} with code LINGS-Z02</li>
 * </ul>
 *
 * <p>Uses a real {@link AgentFactory} (built via {@link TestSupport#buildFactory()})
 * to sidestep Mockito + JDK 23 issues. The stub FlowEngine returns a deterministic
 * empty RunResult.
 */
class RunHandlerTest {

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
    void run_withValidYaml_printsTrailer(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, "agent:\n  llm:\n    provider: anthropic\n    model: test\n  sandbox:\n    policy: default\n".getBytes("UTF-8"));

        Args args = new Args(Subcommand.RUN, yml, "say hi", null, null, false, false, false);
        runner.doRun(args);

        String stdout = outBuf.toString("UTF-8");
        // Stub FlowEngine emits no events → empty RunResult (turns=0, END_TURN, usage=Usage.zero())
        assertThat(stdout)
            .contains("[LINGS-Z99] turns=0")
            .contains("usage=Usage(inputTokens=0, outputTokens=0)")
            .contains("stopReason=END_TURN")
            .contains("elapsedMs=");
    }

    @Test
    void run_withMissingYaml_throwsLingsZ02(@TempDir Path tmp) {
        Path missing = tmp.resolve("does-not-exist.yml");
        Args args = new Args(Subcommand.RUN, missing, "hi", null, null, false, false, false);

        assertThatThrownBy(() -> runner.doRun(args))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> {
                LingsCliException lce = (LingsCliException) e;
                assertThat(lce.getErrorCode()).isEqualTo("LINGS-Z02");
                assertThat(lce.getExitCode()).isEqualTo(3);
                assertThat(lce.getMessage()).contains("config file not found");
            });
    }

    @Test
    void run_withInvalidYaml_throwsLingsZ02(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("bad.yml");
        Files.write(yml, "[unterminated bracket".getBytes("UTF-8"));
        Args args = new Args(Subcommand.RUN, yml, "hi", null, null, false, false, false);

        assertThatThrownBy(() -> runner.doRun(args))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> assertThat(((LingsCliException) e).getErrorCode()).isEqualTo("LINGS-Z02"));
    }
}
