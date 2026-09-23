package ai.lingshu.core.impl.skill;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #020a — L1 unit tests for {@link CommitSkill} (dsh §6.4 L4380-4409).
 *
 * <p>Cover AC-020a-5: name/description/execute (with + without diff) + Conventional
 * Commits template content.
 */
class CommitSkillTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-020a-5: CommitSkill name is 'commit'")
    void name_isCommit() {
        assertThat(new CommitSkill().name()).isEqualTo("commit");
    }

    @Test
    @DisplayName("AC-020a-5: CommitSkill description mentions Conventional Commits")
    void description_mentionsConventionalCommits() {
        assertThat(new CommitSkill().description()).contains("Conventional Commits");
    }

    @Test
    @DisplayName("AC-020a-5: CommitSkill inputSchema is {input: string} fixed shape")
    void inputSchema_fixedShape() {
        com.fasterxml.jackson.databind.JsonNode schema = new CommitSkill().inputSchema();
        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").get("input").get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("AC-020a-5: CommitSkill execute without diff → returns Conventional Commits template")
    void execute_withoutDiff_returnsTemplate() {
        CommitSkill s = new CommitSkill();
        ToolResult r = s.execute(call("c1", ""), ctx());

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getToolUseId()).isEqualTo("c1");
        assertThat(r.isError()).isFalse();
        assertThat(r.getContent()).contains("Conventional Commits");
        assertThat(r.getContent()).contains("feat");
        assertThat(r.getContent()).contains("fix");
        // No diff block when input is empty
        assertThat(r.getContent()).doesNotContain("Staged diff:");
    }

    @Test
    @DisplayName("AC-020a-5: CommitSkill execute with diff → appends Staged diff block")
    void execute_withDiff_appendsStagedDiffBlock() {
        CommitSkill s = new CommitSkill();
        ToolResult r = s.execute(call("c1", "diff --git a/foo b/foo\n+hello"), ctx());

        assertThat(r.getContent()).contains("Staged diff:");
        assertThat(r.getContent()).contains("```");
        assertThat(r.getContent()).contains("diff --git a/foo b/foo");
    }

    @Test
    @DisplayName("AC-020a-5: CommitSkill execute with null input → no Staged diff block")
    void execute_nullInput_noStagedDiff() {
        CommitSkill s = new CommitSkill();
        ToolCall call = new ToolCall("c1", "commit", null);
        ToolResult r = s.execute(call, ctx());
        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).doesNotContain("Staged diff:");
    }

    @Test
    @DisplayName("AC-020a-5: CommitSkill execute with input missing 'input' field → no Staged diff block")
    void execute_missingInputField_noStagedDiff() {
        CommitSkill s = new CommitSkill();
        ObjectNode input = MAPPER.createObjectNode();
        input.put("other", "value");
        ToolResult r = s.execute(new ToolCall("c1", "commit", input), ctx());
        assertThat(r.getContent()).doesNotContain("Staged diff:");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ToolCall call(String id, String inputValue) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("input", inputValue);
        return new ToolCall(id, "commit", input);
    }

    private static ToolExecutionContext ctx() {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.workingDirectory()).thenReturn(Paths.get("."));
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }
}
