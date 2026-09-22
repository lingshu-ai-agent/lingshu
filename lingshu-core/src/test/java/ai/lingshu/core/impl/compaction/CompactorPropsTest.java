package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #018 — L1 unit tests for {@link CompactorProps} (Adapter layer).
 *
 * <p>Three concerns:
 * <ul>
 *   <li>Default constructor maps the defaults() factory</li>
 *   <li>{@code from(cfg)} honours custom {@link AgentConfig.CompactorConfig}</li>
 *   <li>{@code from(cfg)} falls back to {@code defaults()} when {@code cfg.compactorConfig} is null
 *       (back-compat with pre-Story-#018 callers)</li>
 * </ul>
 */
class CompactorPropsTest {

    @Test
    @DisplayName("from_defaults_returnsDefaultValues")
    void from_defaults_returnsDefaultValues() {
        AgentConfig cfg = AgentConfigDefaults.defaults();

        CompactorProps p = CompactorProps.from(cfg);

        assertThat(p.getMaxPromptTokens()).isEqualTo(AgentConfig.CompactorConfig.defaults().getMaxPromptTokens());
        assertThat(p.getMaxToolResultBytes()).isEqualTo(AgentConfig.CompactorConfig.defaults().getMaxToolResultBytes());
        assertThat(p.getKeepRecentTurns()).isEqualTo(AgentConfig.CompactorConfig.defaults().getKeepRecentTurns());
    }

    @Test
    @DisplayName("from_customConfig_returnsCustomValues")
    void from_customConfig_returnsCustomValues() {
        AgentConfig base = AgentConfigDefaults.defaults();
        AgentConfig custom = withCompactorConfig(base, new AgentConfig.CompactorConfig(12345, 6789, 7));

        CompactorProps p = CompactorProps.from(custom);

        assertThat(p.getMaxPromptTokens()).isEqualTo(12345);
        assertThat(p.getMaxToolResultBytes()).isEqualTo(6789);
        assertThat(p.getKeepRecentTurns()).isEqualTo(7);
    }

    @Test
    @DisplayName("from_nullCompactorConfig_fallsBackToDefaults")
    void from_nullCompactorConfig_fallsBackToDefaults() {
        AgentConfig base = AgentConfigDefaults.defaults();
        AgentConfig withNull = withCompactorConfig(base, null);

        CompactorProps p = CompactorProps.from(withNull);

        assertThat(p.getMaxPromptTokens()).isEqualTo(100_000);
        assertThat(p.getMaxToolResultBytes()).isEqualTo(50_000);
        assertThat(p.getKeepRecentTurns()).isEqualTo(20);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static AgentConfig withCompactorConfig(AgentConfig base, AgentConfig.CompactorConfig cc) {
        return new AgentConfig(
            base.getFlowEngine(), base.getLlm(), base.getPrompt(), base.getToolExecutor(),
            base.getSandbox(), base.getCompactor(), base.getSessionStore(),
            base.getDelegate(), base.getMcp(), base.getSkills(),
            base.getToolParallelism(), base.getToolTimeoutSeconds(),
            base.getApprovalTimeoutSeconds(), base.getTurnTimeoutSeconds(),
            base.getLlmTimeoutSeconds(), base.getReactMaxSteps(),
            base.getIdentity(), base.getInstructions(), base.getMemory(),
            base.getA2aTransport(),
            base.getTenants(),
            base.getA2a(),
            cc);
    }
}
