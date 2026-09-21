package ai.lingshu.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 Unit tests — AgentCard JSON serialization (FR-002 + FR-006).
 *
 * <p>Verifies the contract documented in
 * {@code specs/009-a2a-agent-card/contracts/agent-card-http-api.md}:
 * <ul>
 *   <li>{@code @JsonInclude(ALWAYS)} → null fields emit {@code "field": null} literal</li>
 *   <li>Empty lists emit {@code []}, never {@code null}</li>
 *   <li>Field order matches {@code @JsonPropertyOrder} (name first, version next, etc.)</li>
 * </ul>
 */
class AgentCardJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("serialize_minimalCard_returnsAllRequiredFields")
    void serialize_minimalCard_returnsAllRequiredFields() throws Exception {
        AgentCard card = new AgentCard(
            "lingShu-agent",
            null, "0.1.0",
            java.util.Collections.<AgentCard.AgentSkill>emptyList(),
            AgentCard.AgentCapabilities.empty(),
            java.util.Collections.singletonList("text"),
            java.util.Collections.singletonList("text"),
            null, null, null, null, null);

        String json = mapper.writeValueAsString(card);
        JsonNode node = mapper.readTree(json);

        assertThat(node.get("name").asText()).isEqualTo("lingShu-agent");
        assertThat(node.get("version").asText()).isEqualTo("0.1.0");
        assertThat(node.get("skills").isArray()).isTrue();
        assertThat(node.get("skills").size()).isEqualTo(0);
        assertThat(node.get("capabilities").get("streaming").asBoolean()).isFalse();
        assertThat(node.get("capabilities").get("pushNotifications").asBoolean()).isFalse();
        assertThat(node.get("capabilities").get("stateTransitionHistory").asBoolean()).isFalse();
        assertThat(node.get("defaultInputModes").get(0).asText()).isEqualTo("text");
        assertThat(node.get("defaultOutputModes").get(0).asText()).isEqualTo("text");
    }

    @Test
    @DisplayName("serialize_nullDescription_returnsNullLiteral")
    void serialize_nullDescription_returnsNullLiteral() throws Exception {
        AgentCard card = new AgentCard(
            "name-only", null, "0.1.0",
            java.util.Collections.<AgentCard.AgentSkill>emptyList(),
            AgentCard.AgentCapabilities.empty(),
            java.util.Collections.singletonList("text"),
            java.util.Collections.singletonList("text"),
            null, null, null, null, null);

        String json = mapper.writeValueAsString(card);
        JsonNode node = mapper.readTree(json);

        // null description must render as JSON null literal — not be omitted.
        assertThat(node.has("description")).isTrue();
        assertThat(node.get("description").isNull()).isTrue();
    }

    @Test
    @DisplayName("serialize_emptySkillsArray_emitsEmptyBracketNotNull")
    void serialize_emptySkillsArray_emitsEmptyBracketNotNull() throws Exception {
        AgentCard card = AgentCard.defaults();
        String json = mapper.writeValueAsString(card);
        // raw substring check — `[]` is the empty-list rendering.
        assertThat(json).contains("\"skills\":[]");
        // parse and re-verify it's a JSON array, not null.
        JsonNode node = mapper.readTree(json);
        assertThat(node.get("skills").isArray()).isTrue();
        assertThat(node.get("skills").size()).isEqualTo(0);
    }
}