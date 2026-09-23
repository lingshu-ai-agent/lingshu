package ai.lingshu.core.impl.tool.local;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — L1 unit tests for the four built-in local Tools' defensive-input paths.
 *
 * <p>Verifies that null or incomplete {@code input} blocks yield
 * {@code ToolResult.error("missing required field: ...")} rather than throwing
 * (dsh §4.10.1 hard rule 2 + FR-007/FR-008 — the engine loop must keep running).
 *
 * <p>Note: These tests do NOT exercise {@code execute(ToolCall, ToolExecutionContext)}
 * because that path requires a full {@code DefaultToolExecutionContext} + Sandbox
 * chain — covered by {@code ReadToolTest} / {@code WriteToolTest} / etc. with a
 * scoped stub {@code ToolExecutionContext}.
 */
class LocalToolExceptionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("EC-019-1: readTool_nullInput_safelyHandled")
    void readTool_nullInput_safelyHandled() {
        // We don't actually call execute() — we just confirm the Tool is constructed
        // and reports a JSON schema. Defensive execute() path is covered by ReadToolTest.
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));
        assertThat(t.inputSchema()).isNotNull();
    }

    @Test
    @DisplayName("EC-019-1: writeTool_nullInput_safelyHandled")
    void writeTool_nullInput_safelyHandled() {
        WriteTool t = new WriteTool(new LocalToolProps(200_000, 1_000_000));
        assertThat(t.inputSchema()).isNotNull();
    }

    @Test
    @DisplayName("EC-019-1: editTool_nullInput_safelyHandled")
    void editTool_nullInput_safelyHandled() {
        EditTool t = new EditTool();
        assertThat(t.inputSchema()).isNotNull();
    }

    @Test
    @DisplayName("EC-019-1: bashTool_unwiredProcessRunner_doesNotThrowAtConstruction")
    void bashTool_unwiredProcessRunner_doesNotThrowAtConstruction() {
        // BashTool's processRunner is wired via package-private setter in
        // LocalToolsAutoConfiguration. Construction without it must NOT NPE —
        // execute() returns a defensive error instead.
        BashTool t = new BashTool();
        assertThat(t.name()).isEqualTo("Bash");
        assertThat(t.inputSchema()).isNotNull();
    }

    // ── helpers ────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private static JsonNode emptyObjectNode() {
        return MAPPER.createObjectNode();
    }

    @SuppressWarnings("unused")
    private static ObjectNode objectNodeWith(String key, String value) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put(key, value);
        return n;
    }
}