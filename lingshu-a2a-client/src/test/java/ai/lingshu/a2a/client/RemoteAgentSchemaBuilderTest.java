package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 unit tests — {@link RemoteAgentSchemaBuilder} (Story #009d, ≥ 6 cases
 * per data-model.md + spec.md AC-1.1 / AC-1.3 / AC-1.6 / AC-1.7 / AC-4.1).
 *
 * <p>Covers the happy-path card scanning + ToolSpec generation, edge cases
 * EC-1—EC-7 from spec.md, and the {@code describeSpecs} helper.</p>
 */
class RemoteAgentSchemaBuilderTest {

    private RemoteAgentSchemaBuilder builder;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        json = new ObjectMapper();
        builder = new RemoteAgentSchemaBuilder(json);
    }

    // ─── TC-RASB-1: happy path — 2 cards × 2 skills → 4 ToolSpecs sorted ──

    @Test
    @DisplayName("TC-RASB-1: buildToolSpecs_happyPath_returns4ToolSpecsSortedByName")
    void testBuildToolSpecsHappyPath() {
        List<Map<String, Object>> cards = Arrays.asList(
            card("alice", "Alice agent", Arrays.asList(
                skill("echo", "Echo back input", null),
                skill("greet", "Greet user", null)
            )),
            card("bob", "Bob agent", Arrays.asList(
                skill("search", "Search docs", null),
                skill("summarize", "Summarize text", null)
            ))
        );

        List<ToolSpec> specs = builder.buildToolSpecs(cards);

        assertThat(specs).hasSize(4);
        // AC-1.7 — sort by name ascending
        assertThat(specs.get(0).getName()).isEqualTo("call_alice_echo");
        assertThat(specs.get(1).getName()).isEqualTo("call_alice_greet");
        assertThat(specs.get(2).getName()).isEqualTo("call_bob_search");
        assertThat(specs.get(3).getName()).isEqualTo("call_bob_summarize");
        // AC-1.4 — description contains " (via <agent>: ...)"
        assertThat(specs.get(0).getDescription())
            .contains("Echo back input")
            .contains("(via alice: Alice agent)");
    }

    // ─── TC-RASB-2: empty list → empty result, no NPE ────────────────────

    @Test
    @DisplayName("TC-RASB-2: buildToolSpecs_emptyList_returnsEmptyList")
    void testBuildToolSpecsEmptyList() {
        assertThat(builder.buildToolSpecs(Collections.<Map<String, Object>>emptyList()))
            .isEmpty();
    }

    // ─── TC-RASB-3: null input → empty list, no NPE ──────────────────────

    @Test
    @DisplayName("TC-RASB-3: buildToolSpecs_nullInput_returnsEmptyList")
    void testBuildToolSpecsNullInput() {
        assertThat(builder.buildToolSpecs(null)).isEmpty();
    }

    // ─── TC-RASB-4: card missing 'name' → silently skipped ───────────────

    @Test
    @DisplayName("TC-RASB-4: buildToolSpecs_cardMissingName_isSilentlySkipped")
    void testBuildToolSpecsCardMissingName() {
        Map<String, Object> nameless = new HashMap<String, Object>();
        nameless.put("skills", Arrays.asList(skill("echo", "Echo", null)));
        List<ToolSpec> specs = builder.buildToolSpecs(
            Collections.singletonList(nameless));
        assertThat(specs).isEmpty();
    }

    // ─── TC-RASB-5: skill missing 'id' → skipped, others preserved ───────

    @Test
    @DisplayName("TC-RASB-5: buildToolSpecs_skillMissingId_isSilentlySkipped")
    void testBuildToolSpecsSkillMissingId() {
        Map<String, Object> noIdSkill = new HashMap<String, Object>();
        noIdSkill.put("description", "no id");
        Map<String, Object> card = card("alice", "Alice",
            Arrays.asList(noIdSkill, skill("good", "Has id", null)));

        List<ToolSpec> specs = builder.buildToolSpecs(Collections.singletonList(card));

        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).getName()).isEqualTo("call_alice_good");
    }

    // ─── TC-RASB-6: skill inputSchema passthrough (EC-7 happy variant) ───

    @Test
    @DisplayName("TC-RASB-6: buildToolSpecs_skillWithInputSchema_passesThrough")
    void testBuildToolSpecsWithInputSchema() {
        ObjectNode schema = json.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode xNode = props.putObject("x");
        xNode.put("type", "string");
        schema.putArray("required").add("x");

        Map<String, Object> card = card("alice", "Alice",
            Collections.singletonList(skill("echo", "Echo", schema)));
        List<ToolSpec> specs = builder.buildToolSpecs(Collections.singletonList(card));

        assertThat(specs).hasSize(1);
        JsonNode inputSchema = specs.get(0).getInputSchema();
        assertThat(inputSchema.path("type").asText()).isEqualTo("object");
        assertThat(inputSchema.path("properties").path("x").path("type").asText())
            .isEqualTo("string");
    }

    // ─── TC-RASB-7: skill without inputSchema → fallback ObjectNode ──────

    @Test
    @DisplayName("TC-RASB-7: buildToolSpecs_skillWithoutInputSchema_usesPermissiveFallback")
    void testBuildToolSpecsFallbackSchema() {
        Map<String, Object> card = card("alice", "Alice",
            Collections.singletonList(skill("echo", "Echo", null)));
        List<ToolSpec> specs = builder.buildToolSpecs(Collections.singletonList(card));

        assertThat(specs).hasSize(1);
        JsonNode schema = specs.get(0).getInputSchema();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isTrue();
    }

    // ─── TC-RASB-8: cards.skills not a list → skipped (EC-4) ──────────────

    @Test
    @DisplayName("TC-RASB-8: buildToolSpecs_skillsNotList_skipsCard")
    void testBuildToolSpecsSkillsNotList() {
        Map<String, Object> bad = new HashMap<String, Object>();
        bad.put("name", "broken");
        bad.put("skills", "this should be a list, not a string");

        List<ToolSpec> specs = builder.buildToolSpecs(Collections.singletonList(bad));
        assertThat(specs).isEmpty();
    }

    // ─── TC-RASB-9: describeSpecs formatting ─────────────────────────────

    @Test
    @DisplayName("TC-RASB-9: describeSpecs_formatsHeaderAndBurrows")
    void testDescribeSpecsFormats() {
        Map<String, Object> card = card("alice", "Alice agent",
            Collections.singletonList(skill("echo", "Echo back input", null)));
        List<ToolSpec> specs = builder.buildToolSpecs(Collections.singletonList(card));

        String desc = builder.describeSpecs(specs);
        assertThat(desc).startsWith("[1 tools]\n");
        assertThat(desc).contains("- call_alice_echo:");
        assertThat(desc).contains("Echo back input");
        assertThat(desc).contains("(via alice: Alice agent)");
    }

    @Test
    @DisplayName("TC-RASB-10: describeSpecs_emptyList_returnsParenEmpty")
    void testDescribeSpecsEmpty() {
        assertThat(builder.describeSpecs(null)).isEqualTo("(empty)");
        assertThat(builder.describeSpecs(Collections.<ToolSpec>emptyList())).isEqualTo("(empty)");
    }

    // ─── TC-RASB-11: null ObjectMapper in ctor → IllegalArgumentException ─

    @Test
    @DisplayName("TC-RASB-11: ctor_nullObjectMapper_throws")
    void testCtorNullObjectMapperThrows() {
        assertThatThrownBy(() -> new RemoteAgentSchemaBuilder(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("json");
    }

    // ─── TC-RASB-12: returned list is unmodifiable (NFR-002) ─────────────

    @Test
    @DisplayName("TC-RASB-12: buildToolSpecs_returnsUnmodifiableList")
    void testBuildToolSpecs_returnsUnmodifiable() {
        Map<String, Object> card = card("alice", "Alice",
            Collections.singletonList(skill("echo", "Echo", null)));
        List<ToolSpec> specs = builder.buildToolSpecs(Collections.singletonList(card));
        assertThatThrownBy(() -> specs.add(
            new ToolSpec("x", "y", json.createObjectNode())))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> card(String name, String description, List<Map<String, Object>> skills) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("name", name);
        m.put("description", description);
        m.put("skills", new ArrayList<Map<String, Object>>(skills));
        return m;
    }

    private static Map<String, Object> skill(String id, String description, Object inputSchema) {
        Map<String, Object> m = new HashMap<String, Object>();
        m.put("id", id);
        m.put("description", description);
        if (inputSchema != null) {
            m.put("inputSchema", inputSchema);
        }
        return m;
    }
}