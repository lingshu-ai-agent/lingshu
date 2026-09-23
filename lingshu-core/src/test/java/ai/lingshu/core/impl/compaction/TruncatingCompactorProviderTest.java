package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.Compactor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #018 — L1 unit tests for {@link TruncatingCompactorProvider} (AC-018-5, AC-018-6).
 */
class TruncatingCompactorProviderTest {

    @Test
    @DisplayName("AC-018-5: name_priority_version_areStable")
    void name_priority_version_areStable() {
        TruncatingCompactorProvider p = new TruncatingCompactorProvider();
        assertThat(p.name()).isEqualTo("truncating");
        assertThat(p.priority()).isEqualTo(0);
        assertThat(p.version()).isEqualTo(Compactor.CONTRACT_VERSION);
    }

    @Test
    @DisplayName("AC-018-6: create_withDefaults_returnsTruncatingCompactor")
    void create_withDefaults_returnsTruncatingCompactor() {
        TruncatingCompactorProvider p = new TruncatingCompactorProvider();
        AgentConfig cfg = AgentConfigDefaults.defaults();

        Compactor c = p.create(cfg);
        assertThat(c).isInstanceOf(TruncatingCompactor.class);
    }

    @Test
    @DisplayName("AC-018-6b: create_withCustomConfig_usesCfgValues")
    void create_withCustomConfig_usesCfgValues() {
        TruncatingCompactorProvider p = new TruncatingCompactorProvider();
        AgentConfig base = AgentConfigDefaults.defaults();
        AgentConfig custom = withCompactorConfig(base, new AgentConfig.CompactorConfig(50, 25, 5));

        Compactor c = p.create(custom);
        // Behaviour: shouldCompact returns true when the prompt exceeds 50 tokens.
        // We can't reach into TruncatingCompactor's private fields, but we can verify
        // by exercising it with a Prompt sized above / below the threshold.
        TruncatingCompactor tc = (TruncatingCompactor) c;
        assertThat(tc.shouldCompact(promptOf(400))).isTrue();  // 400 chars / 4 = 100 tokens > 50
        assertThat(tc.shouldCompact(promptOf(100))).isFalse(); // 100 chars / 4 = 25 tokens ≤ 50
    }

    @Test
    @DisplayName("EC-018-7: create_nullConfig_throws")
    void create_nullConfig_throws() {
        TruncatingCompactorProvider p = new TruncatingCompactorProvider();
        assertThatThrownBy(() -> p.create(null))
            .isInstanceOf(IllegalArgumentException.class);
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
            cc,
            base.getTools()  // tools (Story #019) — pass-through
        );
    }

    private static ai.lingshu.core.message.Prompt promptOf(int totalChars) {
        java.util.List<ai.lingshu.core.message.Message> msgs = new java.util.ArrayList<>();
        StringBuilder b = new StringBuilder(totalChars);
        for (int i = 0; i < totalChars; i++) b.append('a');
        msgs.add(new ai.lingshu.core.message.Message.System(b.toString(), "role"));
        return ai.lingshu.core.message.Prompt.builder()
            .messages(msgs)
            .tools(java.util.Collections.<ai.lingshu.core.message.ToolSpec>emptyList())
            .hints(new ai.lingshu.core.message.ModelHints(null, null, null))
            .build();
    }
}
