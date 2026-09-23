package ai.lingshu.core.impl.tool.local;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — L1 unit tests for the four built-in local Tools' {@link Tool#name()}
 * and {@link Tool#description()} contracts.
 *
 * <p>Names are stable identifiers used in {@code application.yml} {@code tool.name}
 * lookups, model-driven {@code ToolSpec.name} emissions, and registry keys in
 * {@link ai.lingshu.core.impl.tool.DefaultToolExecutor}. Descriptions are surfaced
 * to the LLM via the system prompt so it can decide when to call each tool.
 */
class LocalToolNamesTest {

    @Test
    @DisplayName("AC-019-4: readTool_nameIsRead_descriptionMentionsByteCap")
    void readTool_nameIsRead_descriptionMentionsByteCap() {
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));

        assertThat(t.name()).isEqualTo("Read");
        assertThat(t.description()).contains("Read a file from disk");
        assertThat(t.description()).contains("200000");
    }

    @Test
    @DisplayName("AC-019-5: writeTool_nameIsWrite_descriptionMentionsByteCap")
    void writeTool_nameIsWrite_descriptionMentionsByteCap() {
        WriteTool t = new WriteTool(new LocalToolProps(200_000, 1_000_000));

        assertThat(t.name()).isEqualTo("Write");
        assertThat(t.description()).contains("Write content to a file");
        assertThat(t.description()).contains("1000000");
    }

    @Test
    @DisplayName("AC-019-5: editTool_nameIsEdit_descriptionMentionsExactMatch")
    void editTool_nameIsEdit_descriptionMentionsExactMatch() {
        EditTool t = new EditTool();

        assertThat(t.name()).isEqualTo("Edit");
        assertThat(t.description()).contains("Edit a file by exact-string replacement");
        assertThat(t.description()).contains("exactly once");
    }

    @Test
    @DisplayName("AC-019-5: bashTool_nameIsBash_descriptionMentionsWhitelist")
    void bashTool_nameIsBash_descriptionMentionsWhitelist() {
        BashTool t = new BashTool();

        assertThat(t.name()).isEqualTo("Bash");
        assertThat(t.description()).contains("Run a whitelisted shell command");
        assertThat(t.description()).contains("whitelist");
    }
}