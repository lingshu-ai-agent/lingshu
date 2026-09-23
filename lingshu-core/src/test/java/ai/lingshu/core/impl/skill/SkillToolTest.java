package ai.lingshu.core.impl.skill;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #020a — L1 unit tests for {@link SkillTool} (dsh §6.4 L4279-4329).
 *
 * <p>Cover AC-020a-1 (execute 4 paths), AC-020a-2 (fromMarkdown standard parsing),
 * AC-020a-3 (## title + multi-space trim), AC-020a-4 (inputSchema fixed shape),
 * EC-020a-1 (null / missing input field), EC-020a-2 (empty markdown + # only header).
 */
class SkillToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ─────────────────────────────────────────────────────────────────────
    //  Constructor + getters (T-01 minimum viable)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("SkillTool: ctor sets name/description/content/inputSchema fields")
    void ctor_setsAllFourFields() {
        SkillTool s = new SkillTool("echo", "echo back the input", "default body",
            "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}},\"required\":[\"input\"]}");

        assertThat(s.name()).isEqualTo("echo");
        assertThat(s.description()).isEqualTo("echo back the input");
        assertThat(s.inputSchema().get("properties").get("input").get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("SkillTool: description null falls back to name")
    void ctor_descriptionNull_fallsBackToName() {
        SkillTool s = new SkillTool("echo", null, "body",
            "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        assertThat(s.description()).isEqualTo("echo");
    }

    @Test
    @DisplayName("SkillTool: content null falls back to empty string")
    void ctor_contentNull_fallsBackToEmpty() {
        SkillTool s = new SkillTool("echo", "desc", null,
            "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        // execute with no input → body should just be ""
        ToolResult r = s.execute(call("c1", ""), ctx());
        assertThat(r.getContent()).isEqualTo("");
    }

    @Test
    @DisplayName("SkillTool: null name throws NullPointerException")
    void ctor_nullName_throws() {
        assertThatThrownBy(() -> new SkillTool(null, "d", "c", "{}"))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("SkillTool: invalid JSON schema throws IllegalStateException")
    void ctor_invalidJsonSchema_throws() {
        assertThatThrownBy(() -> new SkillTool("x", "d", "c", "{not-json"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Invalid schema for skill x");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  AC-020a-1: execute 4 paths
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-020a-1: execute with non-empty input appends User input block")
    void execute_withInput_appendsUserInputBlock() {
        SkillTool s = new SkillTool("echo", "d", "default body", SkillTool.FIXED_INPUT_SCHEMA_JSON);

        ToolResult r = s.execute(callWithInput("c1", "hello world"), ctx());

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getToolUseId()).isEqualTo("c1");
        assertThat(r.isError()).isFalse();
        assertThat(r.getContent()).startsWith("default body");
        assertThat(r.getContent()).contains("User input:\nhello world");
    }

    @Test
    @DisplayName("AC-020a-1: execute with empty input omits User input block")
    void execute_withEmptyInput_omitsBlock() {
        SkillTool s = new SkillTool("echo", "d", "default body", SkillTool.FIXED_INPUT_SCHEMA_JSON);

        ToolResult r = s.execute(callWithInput("c1", ""), ctx());

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).isEqualTo("default body");
        assertThat(r.getContent()).doesNotContain("User input:");
    }

    @Test
    @DisplayName("AC-020a-1: execute preserves toolUseId echo")
    void execute_toolUseIdEcho() {
        SkillTool s = new SkillTool("x", "d", "c", SkillTool.FIXED_INPUT_SCHEMA_JSON);
        ToolResult r = s.execute(callWithInput("call-id-42", "hi"), ctx());
        assertThat(r.getToolUseId()).isEqualTo("call-id-42");
    }

    @Test
    @DisplayName("AC-020a-1: execute is success + not error")
    void execute_successAndNotError() {
        SkillTool s = new SkillTool("x", "d", "c", SkillTool.FIXED_INPUT_SCHEMA_JSON);
        ToolResult r = s.execute(callWithInput("c1", "y"), ctx());
        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.isError()).isFalse();
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EC-020a-1: null input / missing input field
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EC-020a-1: execute with null input → empty user input path")
    void execute_nullInput_treatsAsEmpty() {
        SkillTool s = new SkillTool("x", "d", "body", SkillTool.FIXED_INPUT_SCHEMA_JSON);
        ToolCall call = new ToolCall("c1", "x", null);
        ToolResult r = s.execute(call, ctx());
        assertThat(r.getContent()).isEqualTo("body");
    }

    @Test
    @DisplayName("EC-020a-1: execute with input object missing 'input' field → empty user input")
    void execute_missingInputField_treatsAsEmpty() {
        SkillTool s = new SkillTool("x", "d", "body", SkillTool.FIXED_INPUT_SCHEMA_JSON);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("other_field", "value");
        ToolResult r = s.execute(new ToolCall("c1", "x", input), ctx());
        assertThat(r.getContent()).isEqualTo("body");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  AC-020a-2/3 + EC-020a-2: fromMarkdown parsing
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-020a-2: fromMarkdown with '# title' + body → title stripped, body trimmed")
    void fromMarkdown_stripsLeadingHashAndTrimsBody() {
        Skill s = SkillTool.fromMarkdown("commit",
            "# Generate commit message\nUse Conventional Commits format.");

        assertThat(s.name()).isEqualTo("commit");
        assertThat(s.description()).isEqualTo("Generate commit message");
        // execute → content has body, no user input appended
        ToolResult r = s.execute(callWithInput("c1", ""), ctx());
        assertThat(r.getContent()).isEqualTo("Use Conventional Commits format.");
    }

    @Test
    @DisplayName("AC-020a-3: fromMarkdown '## Subtitle' strips leading hashes")
    void fromMarkdown_stripsMultipleLeadingHashes() {
        Skill s = SkillTool.fromMarkdown("x", "## Subtitle\nbody line");
        assertThat(s.description()).isEqualTo("Subtitle");
    }

    @Test
    @DisplayName("AC-020a-3: fromMarkdown trims multiple spaces after hash")
    void fromMarkdown_trimsMultipleSpacesAfterHash() {
        Skill s = SkillTool.fromMarkdown("x", "#    Many spaces\nbody");
        assertThat(s.description()).isEqualTo("Many spaces");
    }

    @Test
    @DisplayName("EC-020a-2: fromMarkdown with empty string → description falls back to name, content empty")
    void fromMarkdown_emptyString_fallsBack() {
        Skill s = SkillTool.fromMarkdown("commit", "");
        assertThat(s.name()).isEqualTo("commit");
        assertThat(s.description()).isEqualTo("commit");
        // execute → body empty
        ToolResult r = s.execute(callWithInput("c1", ""), ctx());
        assertThat(r.getContent()).isEqualTo("");
    }

    @Test
    @DisplayName("EC-020a-2: fromMarkdown with only '# ' → empty first line falls back to name")
    void fromMarkdown_onlyHash_fallsBackToName() {
        Skill s = SkillTool.fromMarkdown("commit", "# ");
        assertThat(s.description()).isEqualTo("commit");
    }

    @Test
    @DisplayName("fromMarkdown: null markdownContent treated as empty")
    void fromMarkdown_nullMarkdown_treatedAsEmpty() {
        Skill s = SkillTool.fromMarkdown("commit", null);
        assertThat(s.description()).isEqualTo("commit");
    }

    @Test
    @DisplayName("fromMarkdown: single line (no body) → content is empty")
    void fromMarkdown_singleLine_contentEmpty() {
        Skill s = SkillTool.fromMarkdown("x", "# Title only");
        ToolResult r = s.execute(callWithInput("c1", ""), ctx());
        assertThat(r.getContent()).isEqualTo("");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  AC-020a-4: inputSchema fixed shape
    // ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-020a-4: fromMarkdown → inputSchema is fixed {input: string} shape")
    void fromMarkdown_inputSchemaIsFixedShape() {
        Skill s = SkillTool.fromMarkdown("commit", "# t\nbody");
        assertThat(s.inputSchema().get("type").asText()).isEqualTo("object");
        assertThat(s.inputSchema().get("properties").get("input").get("type").asText())
            .isEqualTo("string");
        // required field declared
        assertThat(s.inputSchema().get("required").isArray()).isTrue();
        assertThat(s.inputSchema().get("required").get(0).asText()).isEqualTo("input");
    }

    @Test
    @DisplayName("AC-020a-4: full ctor with custom schema → inputSchema reflects that schema verbatim")
    void ctor_customSchema_reflectedVerbatim() {
        SkillTool s = new SkillTool("x", "d", "c",
            "{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        assertThat(s.inputSchema().get("properties").get("input").get("type").asText())
            .isEqualTo("string");
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static ToolCall call(String id, String inputValue) {
        return callWithInput(id, inputValue);
    }

    private static ToolCall callWithInput(String id, String inputValue) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("input", inputValue);
        return new ToolCall(id, "x", input);
    }

    private static ToolExecutionContext ctx() {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.workingDirectory()).thenReturn(Paths.get("."));
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }

    @SuppressWarnings("unused")
    private static Path tmpDir() {
        return Paths.get(System.getProperty("java.io.tmpdir"));
    }
}
