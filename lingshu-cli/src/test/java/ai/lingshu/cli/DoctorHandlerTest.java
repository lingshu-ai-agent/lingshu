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
 * Story #017 — {@code doctor} subcommand unit tests. 2 cases covering:
 * <ul>
 *   <li>happy path: doDoctor() prints factory.description() + "agent ready" trailer</li>
 *   <li>missing YAML → {@link LingsCliException} LINGS-Z02</li>
 * </ul>
 *
 * <p>The description() output is verified to contain a recognizable token (e.g. "AgentFactory")
 * without asserting the full 9-router dump — that's covered in {@code AgentFactoryDescriptionTest}.
 */
class DoctorHandlerTest {

    private AgentFactory factory;
    private ByteArrayOutputStream outBuf;
    private CliRunner runner;

    @BeforeEach
    void setUp() {
        factory = TestSupport.buildFactory();
        outBuf = new ByteArrayOutputStream();
        runner = new CliRunner(factory,
            new PrintStream(outBuf), new PrintStream(new ByteArrayOutputStream()));
    }

    @Test
    void doctor_withDefaultConfig_printsDescription(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, "agent:\n  llm:\n    provider: anthropic\n    model: t\n  sandbox:\n    policy: default\n".getBytes("UTF-8"));

        Args args = new Args(Subcommand.DOCTOR, yml, null, null, null, false, false);
        runner.doDoctor(args);

        String stdout = outBuf.toString("UTF-8");
        assertThat(stdout)
            .contains("=== LingShu Doctor ===")
            .contains("AgentFactory v0.1.0-SNAPSHOT")
            .contains("LlmProvider: anthropic")
            .contains("agent ready (turns=1)");
    }

    @Test
    void doctor_withMissingYaml_throwsLingsZ02(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.yml");
        Args args = new Args(Subcommand.DOCTOR, missing, null, null, null, false, false);

        assertThatThrownBy(() -> runner.doDoctor(args))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> assertThat(((LingsCliException) e).getErrorCode()).isEqualTo("LINGS-Z02"));
    }
}
