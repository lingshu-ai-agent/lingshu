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

/**
 * Slot 2 built-in Tool — Read a file from disk (dsh §6.5 (1) L4427-4452 sample, Story #019).
 *
 * <p>Reads file content as UTF-8. If {@code bytes.length > maxReadBytes} the content is
 * truncated and a marker {@code \n...[truncated, original N bytes]} is appended so the
 * LLM knows the result was clipped.
 *
 * <p><b>Sandbox-lite path resolution:</b> relative {@code file_path} is resolved against
 * {@code ctx.workingDirectory()}; the result is {@code .normalize()}'d and rejected with
 * {@code IllegalArgumentException} (translated to {@code ToolResult.error} by the outer
 * try/catch) if it escapes the working directory via {@code ..} segments. True chroot is
 * deferred to Story #016 (see {@link ai.lingshu.core.impl.sandbox.DefaultRuntimeSandbox#fs()}).
 *
 * <p>All failure paths return {@link ToolResult#error} (dsh §4.10.1 硬规则 2):
 * <ul>
 *   <li>File not found → {@code "Read failed: <msg>"}</li>
 *   <li>Path is a directory → {@code "Is a directory: <path>"}</li>
 *   <li>Path traversal → {@code "Path traversal denied: <path>"}</li>
 *   <li>Other IO error → {@code "Read failed: <msg>"}</li>
 * </ul>
 */
@Component("readTool")
public class ReadTool implements Tool {

    private static final String NAME = "Read";
    private static final String TRUNCATION_MARKER = "\n...[truncated, original %d bytes]";

    private final int maxReadBytes;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReadTool(LocalToolProps props) {
        this.maxReadBytes = props.getMaxReadBytes();
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Read a file from disk (UTF-8 text, max " + maxReadBytes + " bytes)";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("file_path").put("type", "string")
            .put("description", "Absolute or working-directory-relative path");
        schema.putArray("required").add("file_path");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        if (input == null || input.get("file_path") == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("missing required field: file_path")
                .isError(true)
                .build();
        }
        String filePath = input.get("file_path").asText();
        try {
            Path resolved = resolveSafePath(filePath, ctx);
            if (Files.isDirectory(resolved)) {
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("Is a directory: " + filePath)
                    .isError(true)
                    .build();
            }
            // (above block is the directory guard; placeholders below)
            byte[] bytes = Files.readAllBytes(resolved);
            String content = new String(bytes, StandardCharsets.UTF_8);
            if (bytes.length > maxReadBytes) {
                content = content.substring(0, maxReadBytes)
                    + String.format(TRUNCATION_MARKER, bytes.length);
            }
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content(content)
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
                .content("Read failed: " + e.getMessage())
                .isError(true)
                .build();
        }
    }

    /**
     * Resolve {@code filePath} against {@code ctx.workingDirectory()} and reject any path
     * that escapes it via {@code ..} segments or absolute-path out-of-tree references.
     * Symlink-following is intentionally not handled here (chroot is a follow-up Story).
     */
    static Path resolveSafePath(String filePath, ToolExecutionContext ctx) {
        Path wd = ctx.workingDirectory();
        Path candidate = Paths.get(filePath).isAbsolute()
            ? Paths.get(filePath).normalize()
            : wd.resolve(filePath).normalize();
        if (!candidate.startsWith(wd)) {
            throw new IllegalArgumentException("path escapes working dir: " + filePath);
        }
        return candidate;
    }
}
