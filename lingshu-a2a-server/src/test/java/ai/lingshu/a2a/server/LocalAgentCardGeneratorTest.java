package ai.lingshu.a2a.server;

import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit tests — {@link LocalAgentCardGenerator} (US1-AS1 / AS2 / AS3 + EC-1).
 *
 * <p>Source-of-truth: {@code specs/009-a2a-agent-card/contracts/agent-card-http-api.md}.
 */
class LocalAgentCardGeneratorTest {

    /** Build a minimal AgentConfig with an explicit Identity for tests. */
    private static AgentConfig configWithIdentity(AgentConfig.Identity id) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("default", "noop",
                java.nio.file.Paths.get("."), Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            "default", "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            id,
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            "default",                        // a2aTransport
            null,                              // tenants
            AgentConfig.A2a.defaults());       // a2a
    }

    @Test
    @DisplayName("generate_withIdentityName_returnsAgentCardWithName")
    void generate_withIdentityName_returnsAgentCardWithName() {
        AgentCard card = LocalAgentCardGenerator.generate(
            configWithIdentity(new AgentConfig.Identity("alice-coding", null, "auto",
                Collections.<String>emptyList(), null, null)));

        assertThat(card.getName()).isEqualTo("alice-coding");
        assertThat(card.getDescription()).isNull();
        assertThat(card.getVersion()).isEqualTo("0.1.0");
        assertThat(card.getSkills()).isEmpty();
        assertThat(card.getCapabilities().isStreaming()).isFalse();
        assertThat(card.getCapabilities().isPushNotifications()).isFalse();
        assertThat(card.getCapabilities().isStateTransitionHistory()).isFalse();
        assertThat(card.getDefaultInputModes()).containsExactly("text");
        assertThat(card.getDefaultOutputModes()).containsExactly("text");
    }

    @Test
    @DisplayName("generate_defaultIdentity_returnsLingShuAgent")
    void generate_defaultIdentity_returnsLingShuAgent() {
        AgentCard card = LocalAgentCardGenerator.generate(
            configWithIdentity(AgentConfig.Identity.defaults()));

        assertThat(card.getName()).isEqualTo("lingShu-agent");
        assertThat(card.getDescription()).isNull();
        assertThat(card.getVersion()).isEqualTo("0.1.0");
    }

    @Test
    @DisplayName("generate_identityWithRole_setsDescription")
    void generate_identityWithRole_setsDescription() {
        AgentCard card = LocalAgentCardGenerator.generate(
            configWithIdentity(new AgentConfig.Identity("alice-coding", "AI 编码助手", "auto",
                Collections.<String>emptyList(), null, null)));

        assertThat(card.getName()).isEqualTo("alice-coding");
        assertThat(card.getDescription()).isEqualTo("AI 编码助手");
    }

    @Test
    @DisplayName("generate_blankIdentityName_throwsLingsT02")
    void generate_blankIdentityName_throwsLingsT02() {
        AgentConfig cfg = configWithIdentity(
            new AgentConfig.Identity("", null, "auto",
                Collections.<String>emptyList(), null, null));

        assertThatThrownBy(() -> LocalAgentCardGenerator.generate(cfg))
            .isInstanceOf(LingsA2aServerException.class)
            .hasMessageContaining("LINGS-T02")
            .hasMessageContaining("identity.name");
    }

    @Test
    @DisplayName("generate_whitespaceOnlyIdentityName_throwsLingsT02")
    void generate_whitespaceOnlyIdentityName_throwsLingsT02() {
        AgentConfig cfg = configWithIdentity(
            new AgentConfig.Identity("   ", null, "auto",
                Collections.<String>emptyList(), null, null));

        assertThatThrownBy(() -> LocalAgentCardGenerator.generate(cfg))
            .isInstanceOf(LingsA2aServerException.class)
            .hasMessageContaining("LINGS-T02");
    }

    @Test
    @DisplayName("generate_toJson_returnsValidJson")
    void generate_toJson_returnsValidJson() throws Exception {
        AgentCard card = LocalAgentCardGenerator.generate(
            configWithIdentity(new AgentConfig.Identity("alice-coding", "role", "auto",
                Collections.<String>emptyList(), null, null)));

        String json = LocalAgentCardGenerator.toJson(card);
        assertThat(json).startsWith("{").endsWith("}");
        assertThat(json).contains("\"name\":\"alice-coding\"");
        assertThat(json).contains("\"description\":\"role\"");
        assertThat(json).contains("\"version\":\"0.1.0\"");
    }
}