package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — L1 unit tests for {@link LocalToolProps}.
 *
 * <p>Verifies the byte-cap plumbing through {@link LocalToolProps#from(AgentConfig)}:
 * happy path, defaults fallback when {@code AgentConfig.tools == null}, and the
 * two {@code AgentConfig.ToolsConfig} branches.
 */
class LocalToolPropsTest {

    @Test
    @DisplayName("AC-019-13: from_fullConfig_capturesByteCaps")
    void from_fullConfig_capturesByteCaps() {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        AgentConfig.ToolsConfig tc = new AgentConfig.ToolsConfig(true, 50_000, 500_000);
        AgentConfig withTools = withToolsConfig(cfg, tc);

        LocalToolProps props = LocalToolProps.from(withTools);

        assertThat(props.getMaxReadBytes()).isEqualTo(50_000);
        assertThat(props.getMaxWriteBytes()).isEqualTo(500_000);
    }

    @Test
    @DisplayName("EC-019-4: from_nullToolsConfig_fallsBackToDefaults")
    void from_nullToolsConfig_fallsBackToDefaults() {
        // cfg.tools is null (deliberately omit from a hand-built AgentConfig).
        AgentConfig cfg = AgentConfigDefaults.defaults();
        // Sanity: defaults() returns non-null.
        assertThat(cfg.getTools()).isNotNull();

        // Build a config with cfg.tools = null by passing null via the constructor.
        AgentConfig withNullTools = withToolsConfig(cfg, null);
        assertThat(withNullTools.getTools()).isNull();

        LocalToolProps props = LocalToolProps.from(withNullTools);

        // Falls back to ToolsConfig.defaults() values.
        assertThat(props.getMaxReadBytes()).isEqualTo(200_000);
        assertThat(props.getMaxWriteBytes()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("AC-019-14: from_disabledConfig_stillProducesProps")
    void from_disabledConfig_stillProducesProps() {
        // The byte caps apply whether or not tools are enabled — disabled just
        // skips the engine registry registration.
        AgentConfig cfg = withToolsConfig(AgentConfigDefaults.defaults(),
            new AgentConfig.ToolsConfig(false, 123, 789));

        LocalToolProps props = LocalToolProps.from(cfg);

        assertThat(props.getMaxReadBytes()).isEqualTo(123);
        assertThat(props.getMaxWriteBytes()).isEqualTo(789);
    }

    @Test
    @DisplayName("EC-019-5: from_keepsBothFieldsIndependent")
    void from_keepsBothFieldsIndependent() {
        // Edge: read cap 1, write cap 1_000_000_000 — verify both fields are stored
        // independently (no implicit conversion, no shared state).
        AgentConfig cfg = withToolsConfig(AgentConfigDefaults.defaults(),
            new AgentConfig.ToolsConfig(true, 1, 1_000_000_000));

        LocalToolProps props = LocalToolProps.from(cfg);

        assertThat(props.getMaxReadBytes()).isEqualTo(1);
        assertThat(props.getMaxWriteBytes()).isEqualTo(1_000_000_000);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Build a copy of {@code base} with the given {@code tools} (nullable). */
    private static AgentConfig withToolsConfig(AgentConfig base, AgentConfig.ToolsConfig tools) {
        return new AgentConfig(
            base.getFlowEngine(), base.getLlm(), base.getPrompt(), base.getToolExecutor(),
            base.getSandbox(), base.getCompactor(), base.getSessionStore(),
            base.getDelegate(), base.getMcp(), base.getSkills(),
            base.getToolParallelism(), base.getToolTimeoutSeconds(),
            base.getApprovalTimeoutSeconds(), base.getTurnTimeoutSeconds(),
            base.getLlmTimeoutSeconds(), base.getReactMaxSteps(),
            base.getIdentity(), base.getInstructions(), base.getMemory(),
            base.getA2aTransport(), base.getTenants(), base.getA2a(),
            base.getCompactorConfig(),
            tools);
    }
}