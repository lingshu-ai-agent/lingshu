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
 * Story #019 — L1 unit tests for {@link WriteTool}.
 *
 * <p>Cover AC-019-5 (write + overwrite), AC-019-6 (cap-before-disk), EC-019-2 (directory).
 */
class WriteToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-019-5: writeNewFile_createsFile")
    void writeNewFile_createsFile(@TempDir Path tmp) throws IOException {
        WriteTool t = new WriteTool(new LocalToolProps(200_000, 1_000_000));
        Path target = tmp.resolve("out.txt");

        ToolResult r = t.execute(
            writeCall("Write", "c1", target.toString(), "alpha"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).contains("wrote 5 bytes");
        assertThat(Files.exists(target)).isTrue();
        assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
            .isEqualTo("alpha");
    }

    @Test
    @DisplayName("AC-019-5: writeOverwriteExisting_replacesContent")
    void writeOverwriteExisting_replacesContent(@TempDir Path tmp) throws IOException {
        WriteTool t = new WriteTool(new LocalToolProps(200_000, 1_000_000));
        Path target = tmp.resolve("out.txt");
        Files.write(target, "orig\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = t.execute(
            writeCall("Write", "c1", target.toString(), "replaced"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(new String(Files.readAllBytes(target), StandardCharsets.UTF_8))
            .isEqualTo("replaced");
    }

    @Test
    @DisplayName("AC-019-6: writeOverLimit_returnsErrorAndNoFile")
    void writeOverLimit_returnsErrorAndNoFile(@TempDir Path tmp) {
        // cap = 50 bytes; content = 60_000 bytes
        WriteTool t = new WriteTool(new LocalToolProps(200_000, 50));
        Path target = tmp.resolve("should-not-exist.txt");
        StringBuilder sb = new StringBuilder(60_000);
        for (int i = 0; i < 60_000; i++) {
            sb.append('x');
        }

        ToolResult r = t.execute(
            writeCall("Write", "c1", target.toString(), sb.toString()), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).contains("exceeds max 50 bytes");
        assertThat(r.isError()).isTrue();
        // CRITICAL: file must NOT exist on disk — cap guard runs before any write.
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    @DisplayName("EC-019-2: writeToDirectory_returnsError")
    void writeToDirectory_returnsError(@TempDir Path tmp) throws IOException {
        WriteTool t = new WriteTool(new LocalToolProps(200_000, 1_000_000));
        Path dir = tmp.resolve("subdir");
        Files.createDirectory(dir);

        ToolResult r = t.execute(
            writeCall("Write", "c1", dir.toString(), "content"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("Is a directory:");
        assertThat(r.isError()).isTrue();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ToolCall writeCall(String name, String id, String filePath, String content) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("file_path", filePath);
        input.put("content", content);
        return new ToolCall(id, name, input);
    }

    private static ToolExecutionContext ctx(Path workingDir) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.workingDirectory()).thenReturn(workingDir);
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }
}