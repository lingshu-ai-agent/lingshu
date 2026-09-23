package ai.lingshu.core.runtime;

import ai.lingshu.core.exception.LingsConfigException;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #018 — L1 unit tests for {@link AgentConfig.CompactorConfig#validate()}.
 *
 * <p>Covers AC-018-8 (positive + negative cases) — surfaces errors as {@code LINGS-C02}
 * via the {@code LingsConfigException} facade.
 */
class AgentConfigCompactorValidationTest {

    @Test
    @DisplayName("AC-018-8: defaults_passValidation")
    void defaults_passValidation() {
        assertThatCode(() -> AgentConfig.CompactorConfig.defaults().validate())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-018-8: positiveCustomValues_passValidation")
    void positiveCustomValues_passValidation() {
        AgentConfig.CompactorConfig cc = new AgentConfig.CompactorConfig(50_000, 10_000, 5);
        assertThatCode(cc::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EC-018-9: zeroMaxPromptTokens_throwsLingsC02")
    void zeroMaxPromptTokens_throwsLingsC02() {
        AgentConfig.CompactorConfig cc = new AgentConfig.CompactorConfig(0, 1000, 5);
        assertThatThrownBy(cc::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("max-prompt-tokens");
    }

    @Test
    @DisplayName("EC-018-10: allFieldsZero_aggregatesAllErrors")
    void allFieldsZero_aggregatesAllErrors() {
        AgentConfig.CompactorConfig cc = new AgentConfig.CompactorConfig(0, 0, 0);
        assertThatThrownBy(cc::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("max-prompt-tokens")
            .hasMessageContaining("max-tool-result-bytes")
            .hasMessageContaining("keep-recent-turns");
    }

    // ── smoke: defaults() flows through AgentConfigDefaults ────────────

    @Test
    @DisplayName("AC-018-8b: AgentConfigDefaults_passValidation")
    void AgentConfigDefaults_passValidation() {
        assertThatCode(() -> AgentConfigDefaults.defaults().getCompactorConfig().validate())
            .doesNotThrowAnyException();
    }
}
