package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #019 — L1 unit tests for {@link EditTool}.
 *
 * <p>Cover AC-019-7 (single-match happy), AC-019-8 (zero + multi match fail-fast),
 * EC-019-3 (no-op), plus the path-traversal regression.
 */
class EditToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-019-7: editSingleMatch_replacesAndReturnsSuccess")
    void editSingleMatch_replacesAndReturnsSuccess(@TempDir Path tmp) throws IOException {
        EditTool t = new EditTool();
        Path file = tmp.resolve("foo.txt");
        Files.write(file, "hello world\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = t.execute(
            editCall("Edit", "c1", file.toString(), "world", "earth"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).contains("replaced 1 occurrence");
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            .isEqualTo("hello earth\n");
    }

    @Test
    @DisplayName("AC-019-8: editNoMatch_returnsError")
    void editNoMatch_returnsError(@TempDir Path tmp) throws IOException {
        EditTool t = new EditTool();
        Path file = tmp.resolve("foo.txt");
        Files.write(file, "hello world\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = t.execute(
            editCall("Edit", "c1", file.toString(), "MISSING", "earth"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("old_string not found");
        assertThat(r.isError()).isTrue();
        // File unchanged
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            .isEqualTo("hello world\n");
    }

    @Test
    @DisplayName("AC-019-8: editMultipleMatch_returnsError")
    void editMultipleMatch_returnsError(@TempDir Path tmp) throws IOException {
        EditTool t = new EditTool();
        Path file = tmp.resolve("foo.txt");
        // "x" appears 5 times — EditTool must reject ambiguous edits.
        Files.write(file, "xaxbxcxdx\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = t.execute(
            editCall("Edit", "c1", file.toString(), "x", "y"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).contains("matches 5 times");
        assertThat(r.isError()).isTrue();
        // File unchanged
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            .isEqualTo("xaxbxcxdx\n");
    }

    @Test
    @DisplayName("EC-019-3: editNoOp_returnsError")
    void editNoOp_returnsError(@TempDir Path tmp) throws IOException {
        EditTool t = new EditTool();
        Path file = tmp.resolve("foo.txt");
        Files.write(file, "hello world\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = t.execute(
            editCall("Edit", "c1", file.toString(), "world", "world"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("No-op edit");
        assertThat(r.isError()).isTrue();
        // File unchanged
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
            .isEqualTo("hello world\n");
    }

    @Test
    @DisplayName("regression: editPathTraversal_returnsError")
    void editPathTraversal_returnsError(@TempDir Path tmp) {
        EditTool t = new EditTool();

        ToolResult r = t.execute(
            editCall("Edit", "c1", "../etc/passwd", "root", "wheel"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("Path traversal denied:");
        assertThat(r.isError()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ToolCall editCall(String name, String id, String filePath,
                                     String oldStr, String newStr) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("file_path", filePath);
        input.put("old_string", oldStr);
        input.put("new_string", newStr);
        return new ToolCall(id, name, input);
    }

    private static ToolExecutionContext ctx(Path workingDir) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.workingDirectory()).thenReturn(workingDir);
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }
}