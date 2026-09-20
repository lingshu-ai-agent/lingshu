package ai.lingshu.core.impl.memory;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #002 US3 — IdentityMemorySource contract tests.
 * See data-model.md §3.3 + contracts/memory-source.md §3.3.
 */
class IdentityMemorySourceTest {

    @Test
    @DisplayName("load_returnsJsonOfIdentity_allFieldsPresent")
    void load_returnsJsonOfIdentity_allFieldsPresent() throws Exception {
        AgentConfig.Identity id = new AgentConfig.Identity(
            "alice", "测试工程师", "zh",
            Arrays.asList("细致", "耐心"), "温和", null);
        AgentConfig cfg = replaceIdentity(AgentConfigDefaults.defaults(), id);
        IdentityMemorySource src = new IdentityMemorySource(cfg);

        String json = src.load(null);
        assertThat(json).isNotNull();

        JsonNode node = new ObjectMapper().readTree(json);
        assertThat(node.get("name").asText()).isEqualTo("alice");
        assertThat(node.get("role").asText()).isEqualTo("测试工程师");
        assertThat(node.get("language").asText()).isEqualTo("zh");
        assertThat(node.get("tone").asText()).isEqualTo("温和");
        assertThat(node.get("traits").isArray()).isTrue();
        assertThat(node.get("traits").size()).isEqualTo(2);
        assertThat(node.get("traits").get(0).asText()).isEqualTo("细致");
        assertThat(node.get("traits").get(1).asText()).isEqualTo("耐心");
    }

    @Test
    @DisplayName("load_defaultsIdentity_whenConfigIdentityIsNull")
    void load_defaultsIdentity_whenConfigIdentityIsNull() {
        // Build a config with identity=null (impossible via defaults() but defensive)
        AgentConfig cfg = withIdentity(AgentConfigDefaults.defaults(), null);
        IdentityMemorySource src = new IdentityMemorySource(cfg);

        String json = src.load(null);
        assertThat(json).isNotNull();
        assertThat(json).contains("lingShu-agent"); // Identity.defaults() name
    }

    @Test
    @DisplayName("load_isDeterministic_forSameConfig")
    void load_isDeterministic_forSameConfig() {
        AgentConfig cfg = AgentConfigDefaults.defaults();
        IdentityMemorySource src = new IdentityMemorySource(cfg);

        String first = src.load(null);
        String second = src.load(null);
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("name_andPriority_areCorrect")
    void name_andPriority_areCorrect() {
        IdentityMemorySource src = new IdentityMemorySource(AgentConfigDefaults.defaults());
        assertThat(src.name()).isEqualTo("identity");
        assertThat(src.priority()).isEqualTo(30);
    }

    // ── helpers (Lombok @Value is immutable so we rebuild config) ──

    private static AgentConfig replaceIdentity(AgentConfig base, AgentConfig.Identity id) {
        return withIdentity(base, id);
    }

    private static AgentConfig withIdentity(AgentConfig base, AgentConfig.Identity id) {
        return new AgentConfig(
            base.getFlowEngine(), base.getLlm(), base.getPrompt(), base.getToolExecutor(),
            base.getSandbox(), base.getCompactor(), base.getSessionStore(),
            base.getDelegate(), base.getMcp(), base.getSkills(),
            base.getToolParallelism(), base.getToolTimeoutSeconds(),
            base.getApprovalTimeoutSeconds(), base.getTurnTimeoutSeconds(),
            base.getLlmTimeoutSeconds(), base.getReactMaxSteps(),
            id, base.getInstructions(), base.getMemory(),
            base.getA2aTransport(),
            base.getTenants());
    }

    /** Unused — ensures TurnContext import is recognized. */
    @SuppressWarnings("unused")
    private void touchTurnContext(TurnContext ctx) { /* no-op */ }
}
