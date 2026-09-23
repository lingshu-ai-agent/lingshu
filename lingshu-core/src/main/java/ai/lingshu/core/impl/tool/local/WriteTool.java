package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Slot 2 built-in Tool — Write content to a file (Story #019, dsh §6.5 (1) extension).
 *
 * <p>Creates the file (and missing parent directories) if it does not exist; overwrites
 * if it does. <b>Byte cap is checked BEFORE any disk write</b> so a too-large payload
 * never reaches the filesystem.
 *
 * <p>Failure paths return {@link ToolResult#error} (dsh §4.10.1 硬规则 2):
 * <ul>
 *   <li>{@code content.length > maxWriteBytes} → {@code "Content size N exceeds max M"}</li>
 *   <li>Path is a directory → {@code "Is a directory: <path>"}</li>
 *   <li>Path traversal → {@code "Path traversal denied: <path>"}</li>
 *   <li>Other IO error → {@code "Write failed: <msg>"}</li>
 * </ul>
 */
@Component("writeTool")
public class WriteTool implements Tool {

    private static final String NAME = "Write";

    private final int maxWriteBytes;

    public WriteTool(LocalToolProps props) {
        this.maxWriteBytes = props.getMaxWriteBytes();
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Write content to a file (creates or overwrites, max "
            + maxWriteBytes + " bytes)";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("file_path").put("type", "string")
            .put("description", "Absolute or working-directory-relative path");
        props.putObject("content").put("type", "string")
            .put("description", "UTF-8 text to write (max " + maxWriteBytes + " bytes)");
        schema.putArray("required").add("file_path").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        if (input == null || input.get("file_path") == null || input.get("content") == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("missing required fields: file_path, content")
                .isError(true)
                .build();
        }
        String filePath = input.get("file_path").asText();
        String content = input.get("content").asText();

        // Guard before disk write — AC-019-6
        if (content.length() > maxWriteBytes) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("Content size " + content.length()
                    + " exceeds max " + maxWriteBytes + " bytes")
                .isError(true)
                .build();
        }

        try {
            Path resolved = ReadTool.resolveSafePath(filePath, ctx);
            if (Files.isDirectory(resolved)) {
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("Is a directory: " + filePath)
                    .isError(true)
                    .build();
            }
            Path parent = resolved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(resolved, content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content("wrote " + content.length() + " bytes to " + filePath)
                .isError(false)
                .build();
        } catch (IllegalArgumentException e) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("Path traversal denied: " + filePath)
                .isError(true)
                .build();
        } catch (IOException e) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("Write failed: " + e.getMessage())
                .isError(true)
                .build();
        }
    }
}
