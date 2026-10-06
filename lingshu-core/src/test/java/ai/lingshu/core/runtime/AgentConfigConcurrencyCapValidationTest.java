package ai.lingshu.core.runtime;

import ai.lingshu.core.exception.LingsConfigException;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #044 — L1 unit tests for the new top-level {@link AgentConfig#validate()}
 * method (covers AC-044-1 / AC-044-2).
 *
 * <p>Mirrors {@code AgentConfigCompactorValidationTest} Story #018 precedent:
 * surfaces all validation errors as a single aggregated
 * {@link LingsConfigException} carrying the established {@code "C02"} code
 * (reused across Story #001 + #018 + #006 + #029).
 *
 * <p>Each {@code new AgentConfig(...)} call uses the {@code permissionPolicy}
 * + {@code maxConcurrentTurns} + {@code maxConcurrentQueueDepth} fields as
 * the new {@code @AllArgsConstructor} tail, mirroring the constructor
 * declaration order in {@link AgentConfig}.
 */
class AgentConfigConcurrencyCapValidationTest {

    /** Smallest-possible custom AgentConfig with positive cap values — should pass. */
    private static AgentConfig validCustomConfig() {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        return new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            defaults.getMcp(),
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            defaults.getReactMaxSteps(),
            defaults.getIdentity(),
            defaults.getInstructions(),
            defaults.getMemory(),
            defaults.getA2aTransport(),
            defaults.getTenants(),
            defaults.getA2a(),
            defaults.getCompactorConfig(),
            defaults.getTools(),
            defaults.getPermissionPolicy(),
            32,    // 🆕 Story #044 — maxConcurrentTurns (positive custom)
            64);   // 🆕 Story #044 — maxConcurrentQueueDepth (positive custom)
    }

    @Test
    @DisplayName("AC-044-1: defaults_passValidation")
    void defaults_passValidation() {
        assertThatCode(() -> AgentConfigDefaults.defaults().validate())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-044-1: positiveCustomValues_passValidation")
    void positiveCustomValues_passValidation() {
        assertThatCode(() -> validCustomConfig().validate())
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EC-044-2: zeroMaxConcurrentTurns_throwsLingsC02")
    void zeroMaxConcurrentTurns_throwsLingsC02() {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        AgentConfig bad = new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            defaults.getMcp(),
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            defaults.getReactMaxSteps(),
            defaults.getIdentity(),
            defaults.getInstructions(),
            defaults.getMemory(),
            defaults.getA2aTransport(),
            defaults.getTenants(),
            defaults.getA2a(),
            defaults.getCompactorConfig(),
            defaults.getTools(),
            defaults.getPermissionPolicy(),
            0,     // 🆕 Story #044 — maxConcurrentTurns = 0 (INVALID)
            defaults.getMaxConcurrentQueueDepth());
        assertThatThrownBy(bad::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("maxConcurrentTurns");
    }

    @Test
    @DisplayName("EC-044-2: negativeMaxConcurrentTurns_throwsLingsC02")
    void negativeMaxConcurrentTurns_throwsLingsC02() {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        AgentConfig bad = new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            defaults.getMcp(),
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            defaults.getReactMaxSteps(),
            defaults.getIdentity(),
            defaults.getInstructions(),
            defaults.getMemory(),
            defaults.getA2aTransport(),
            defaults.getTenants(),
            defaults.getA2a(),
            defaults.getCompactorConfig(),
            defaults.getTools(),
            defaults.getPermissionPolicy(),
            -1,    // 🆕 Story #044 — maxConcurrentTurns = -1 (INVALID)
            defaults.getMaxConcurrentQueueDepth());
        assertThatThrownBy(bad::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("maxConcurrentTurns");
    }

    @Test
    @DisplayName("EC-044-2: zeroMaxConcurrentQueueDepth_throwsLingsC02")
    void zeroMaxConcurrentQueueDepth_throwsLingsC02() {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        AgentConfig bad = new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            defaults.getMcp(),
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            defaults.getReactMaxSteps(),
            defaults.getIdentity(),
            defaults.getInstructions(),
            defaults.getMemory(),
            defaults.getA2aTransport(),
            defaults.getTenants(),
            defaults.getA2a(),
            defaults.getCompactorConfig(),
            defaults.getTools(),
            defaults.getPermissionPolicy(),
            defaults.getMaxConcurrentTurns(),
            0);    // 🆕 Story #044 — maxConcurrentQueueDepth = 0 (INVALID)
        assertThatThrownBy(bad::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("maxConcurrentQueueDepth");
    }

    @Test
    @DisplayName("EC-044-2: negativeMaxConcurrentQueueDepth_throwsLingsC02")
    void negativeMaxConcurrentQueueDepth_throwsLingsC02() {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        AgentConfig bad = new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            defaults.getMcp(),
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            defaults.getReactMaxSteps(),
            defaults.getIdentity(),
            defaults.getInstructions(),
            defaults.getMemory(),
            defaults.getA2aTransport(),
            defaults.getTenants(),
            defaults.getA2a(),
            defaults.getCompactorConfig(),
            defaults.getTools(),
            defaults.getPermissionPolicy(),
            defaults.getMaxConcurrentTurns(),
            -1);   // 🆕 Story #044 — maxConcurrentQueueDepth = -1 (INVALID)
        assertThatThrownBy(bad::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("maxConcurrentQueueDepth");
    }

    @Test
    @DisplayName("EC-044-2: allFieldsZero_aggregatesAllErrors")
    void allFieldsZero_aggregatesAllErrors() {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        AgentConfig bad = new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            defaults.getMcp(),
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            0,      // reactMaxSteps = 0 (INVALID, triggers reactMaxSteps error too)
            defaults.getIdentity(),
            defaults.getInstructions(),
            defaults.getMemory(),
            defaults.getA2aTransport(),
            defaults.getTenants(),
            defaults.getA2a(),
            defaults.getCompactorConfig(),
            defaults.getTools(),
            defaults.getPermissionPolicy(),
            0,      // 🆕 Story #044 — maxConcurrentTurns = 0 (INVALID)
            0);     // 🆕 Story #044 — maxConcurrentQueueDepth = 0 (INVALID)
        assertThatThrownBy(bad::validate)
            .isInstanceOf(LingsConfigException.class)
            .satisfies(t -> assertThat(((LingsConfigException) t).getCode()).isEqualTo("C02"))
            .hasMessageContaining("reactMaxSteps")
            .hasMessageContaining("maxConcurrentTurns")
            .hasMessageContaining("maxConcurrentQueueDepth");
    }

    @Test
    @DisplayName("AC-044-1b: AgentConfigDefaults_passValidation")
    void AgentConfigDefaults_passValidation() {
        assertThatCode(() -> AgentConfigDefaults.defaults().validate())
            .doesNotThrowAnyException();
    }
}
