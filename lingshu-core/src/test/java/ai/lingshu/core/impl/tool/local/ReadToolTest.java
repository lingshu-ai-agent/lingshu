package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #019 — L1 unit tests for {@link ReadTool}.
 *
 * <p>Cover AC-019-3 (read + cap truncation), AC-019-4 (not found + path traversal),
 * EC-019-1 (directory guard), plus the empty-file regression.
 */
class ReadToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-019-3: readExistingFile_returnsContent")
    void readExistingFile_returnsContent(@TempDir Path tmp) throws IOException {
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));
        Path file = tmp.resolve("hello.txt");
        Files.write(file, "hello world\n".getBytes(StandardCharsets.UTF_8));

        ToolResult r = t.execute(call("Read", "c1", "file_path", file.toString()), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).isEqualTo("hello world\n");
        assertThat(r.isError()).isFalse();
    }

    @Test
    @DisplayName("AC-019-3: readOverLimit_truncatesAndAppendsMarker")
    void readOverLimit_truncatesAndAppendsMarker(@TempDir Path tmp) throws IOException {
        // cap = 50 bytes; payload = 60_000 bytes
        ReadTool t = new ReadTool(new LocalToolProps(50, 1_000_000));
        byte[] big = new byte[60_000];
        for (int i = 0; i < big.length; i++) {
            big[i] = 'x';
        }
        Path file = tmp.resolve("big.bin");
        Files.write(file, big);

        ToolResult r = t.execute(call("Read", "c1", "file_path", file.toString()), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        // First 50 bytes preserved
        assertThat(r.getContent().length()).isEqualTo(50 + "\n...[truncated, original 60000 bytes]".length());
        assertThat(r.getContent()).endsWith("...[truncated, original 60000 bytes]");
    }

    @Test
    @DisplayName("AC-019-4: readNonExistent_returnsError")
    void readNonExistent_returnsError(@TempDir Path tmp) {
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));
        Path missing = tmp.resolve("does-not-exist.txt");

        ToolResult r = t.execute(call("Read", "c1", "file_path", missing.toString()), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("Read failed:");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("AC-019-4: readPathTraversal_returnsError")
    void readPathTraversal_returnsError(@TempDir Path tmp) {
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));

        // "../etc/passwd" from working dir = tmp escapes outside tmp
        ToolResult r = t.execute(call("Read", "c1", "file_path", "../etc/passwd"), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("Path traversal denied:");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("EC-019-1: readDirectory_returnsError")
    void readDirectory_returnsError(@TempDir Path tmp) throws IOException {
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));
        Path dir = tmp.resolve("subdir");
        Files.createDirectory(dir);

        ToolResult r = t.execute(call("Read", "c1", "file_path", dir.toString()), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).startsWith("Is a directory:");
        assertThat(r.isError()).isTrue();
    }

    @Test
    @DisplayName("regression: readEmptyFile_returnsEmptyString")
    void readEmptyFile_returnsEmptyString(@TempDir Path tmp) throws IOException {
        ReadTool t = new ReadTool(new LocalToolProps(200_000, 1_000_000));
        Path file = tmp.resolve("empty.txt");
        Files.write(file, new byte[0]);

        ToolResult r = t.execute(call("Read", "c1", "file_path", file.toString()), ctx(tmp));

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).isEqualTo("");
        assertThat(r.isError()).isFalse();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static ToolCall call(String name, String id, String key, String value) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put(key, value);
        return new ToolCall(id, name, input);
    }

    private static ToolExecutionContext ctx(Path workingDir) {
        ToolExecutionContext c = mock(ToolExecutionContext.class);
        when(c.workingDirectory()).thenReturn(workingDir);
        when(c.callConfig()).thenReturn(new ToolCallConfig(30, 0, 0));
        return c;
    }

    @SuppressWarnings("unused")
    private static String randomId() {
        return UUID.randomUUID().toString();
    }

    @SuppressWarnings("unused")
    private static JsonNode missingFieldInput() {
        return MAPPER.createObjectNode();
    }
}