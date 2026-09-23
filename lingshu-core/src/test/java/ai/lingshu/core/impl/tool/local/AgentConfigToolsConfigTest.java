package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.exception.LingsConfigException;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #019 — L1 unit tests for {@link AgentConfig.ToolsConfig} defaults + validation.
 *
 * <p>Mirrors {@code CompactorConfigTest} patterns: defaults factory + validate() with
 * aggregated error reporting. AC-019-1 (enabled default true), AC-019-2 (defaults
 * present), and AC-019-3 (errors aggregated + {@code "C02"} code).
 */
class AgentConfigToolsConfigTest {

    @Test
    @DisplayName("AC-019-1: defaults_enabledIsTrue")
    void defaults_enabledIsTrue() {
        AgentConfig.ToolsConfig tc = AgentConfig.ToolsConfig.defaults();

        assertThat(tc.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("AC-019-2: defaults_byteCapsAreConservative")
    void defaults_byteCapsAreConservative() {
        AgentConfig.ToolsConfig tc = AgentConfig.ToolsConfig.defaults();

        // Defaults are tuned to zero-config Story #001 AC-01-2: empty yml must boot.
        // Conservative caps protect the engine from runaway LLM-generated content.
        assertThat(tc.getMaxReadBytes()).isEqualTo(200_000);     // 200KB
        assertThat(tc.getMaxWriteBytes()).isEqualTo(1_000_000);  // 1MB
    }

    @Test
    @DisplayName("AC-019-3: validate_positiveCaps_succeeds")
    void validate_positiveCaps_succeeds() {
        AgentConfig.ToolsConfig tc = new AgentConfig.ToolsConfig(true, 1024, 2048);

        assertThatCode(tc::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EC-019-2: validate_negativeCap_aggregatesAndThrowsC02")
    void validate_negativeCap_aggregatesAndThrowsC02() {
        AgentConfig.ToolsConfig tc = new AgentConfig.ToolsConfig(true, 0, 0);

        // LingsConfigException carries the structured code in getCode() — the
        // message only contains the human-readable per-field diagnostics.
        // Both must be checked separately.
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("agent.tools.max-read-bytes")
            .hasMessageContaining("agent.tools.max-write-bytes")
            .satisfies(thrown -> assertThat(((LingsConfigException) thrown).getCode())
                .isEqualTo("C02"));
    }
}