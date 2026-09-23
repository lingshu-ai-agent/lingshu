package ai.lingshu.cli;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.AgentConfig;
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
 * Story #017 — {@code config} subcommand unit tests. 2 cases covering:
 * <ul>
 *   <li>default behavior: doConfig() prints the short "Effective Config" summary</li>
 *   <li>missing YAML → {@link LingsCliException} LINGS-Z02</li>
 * </ul>
 *
 * <p>{@code --print-effective} (full JSON) is verified via {@code doctor} (the same
 * Jackson writer path); the default short summary is the primary code path covered here.
 */
class ConfigHandlerTest {

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
    void config_withDefaultPrints_shortSummary(@TempDir Path tmp) throws IOException {
        Path yml = tmp.resolve("app.yml");
        Files.write(yml, "agent:\n  llm:\n    provider: anthropic\n    model: t\n  sandbox:\n    policy: default\n".getBytes("UTF-8"));

        Args args = new Args(Subcommand.CONFIG, yml, null, null, null, false, false, false);
        runner.doConfig(args);

        // Assert against the YAML-loaded cfg, not AgentConfigDefaults — the user wrote
        // model: t in the YAML, so the effective config must reflect that. Defaults is
        // only the empty-yaml baseline.
        AgentConfig cfg = factory.loadYamlAndValidate(yml);
        String stdout = outBuf.toString("UTF-8");
        assertThat(stdout)
            .contains("=== LingShu Effective Config ===")
            .contains("flowEngine:")
            .contains("llm.provider: " + cfg.getLlm().getProvider())
            .contains("llm.model: " + cfg.getLlm().getModel())
            .contains("react.maxSteps: " + cfg.getReactMaxSteps())
            .contains("(use --print-effective for full JSON)");
    }

    @Test
    void config_withMissingYaml_throwsLingsZ02(@TempDir Path tmp) {
        Path missing = tmp.resolve("nope.yml");
        Args args = new Args(Subcommand.CONFIG, missing, null, null, null, false, false, false);

        assertThatThrownBy(() -> runner.doConfig(args))
            .isInstanceOf(LingsCliException.class)
            .satisfies(e -> assertThat(((LingsCliException) e).getErrorCode()).isEqualTo("LINGS-Z02"));
    }
}
