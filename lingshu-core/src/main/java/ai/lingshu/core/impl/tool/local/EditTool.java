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

/**
 * Slot 2 built-in Tool — Edit a file by exact-string replacement (Story #019).
 *
 * <p><b>Strict semantics</b> (Claude Code compat) — fail-fast on ambiguity:
 * <ul>
 *   <li>{@code old_string} must match exactly once — 0 matches or >1 matches both
 *       return {@link ToolResult#error} (no partial edits, no LLM-induced mass rewrites)</li>
 *   <li>{@code old_string.equals(new_string)} is rejected as a no-op</li>
 * </ul>
 *
 * <p>No regex / global replace in v1 (Story boundary — see specs/019-built-in-tools/spec.md).
 */
@Component("editTool")
public class EditTool implements Tool {

    private static final String NAME = "Edit";

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Edit a file by exact-string replacement (old_string must match exactly once)";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("file_path").put("type", "string");
        props.putObject("old_string").put("type", "string")
            .put("description", "Exact substring to replace (must appear exactly once)");
        props.putObject("new_string").put("type", "string")
            .put("description", "Replacement text");
        schema.putArray("required").add("file_path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        if (input == null
            || input.get("file_path") == null
            || input.get("old_string") == null
            || input.get("new_string") == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("missing required fields: file_path, old_string, new_string")
                .isError(true)
                .build();
        }
        String filePath = input.get("file_path").asText();
        String oldStr = input.get("old_string").asText();
        String newStr = input.get("new_string").asText();

        // Guard 1 — no-op edit (EC-019-3)
        if (oldStr.equals(newStr)) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("No-op edit: old_string equals new_string")
                .isError(true)
                .build();
        }

        try {
            Path resolved = ReadTool.resolveSafePath(filePath, ctx);
            String content = new String(Files.readAllBytes(resolved), StandardCharsets.UTF_8);
            int firstIdx = content.indexOf(oldStr);

            // Guard 2 — not found (AC-019-8)
            if (firstIdx < 0) {
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("old_string not found in " + filePath)
                    .isError(true)
                    .build();
            }

            // Guard 3 — multiple matches (AC-019-8)
            int lastIdx = content.lastIndexOf(oldStr);
            if (firstIdx != lastIdx) {
                int occurrences = countOccurrences(content, oldStr);
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("old_string matches " + occurrences + " times in "
                        + filePath + " — must match exactly once")
                    .isError(true)
                    .build();
            }

            // Happy path — AC-019-7
            String updated = content.substring(0, firstIdx)
                + newStr
                + content.substring(firstIdx + oldStr.length());
            Files.write(resolved, updated.getBytes(StandardCharsets.UTF_8));
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content("replaced 1 occurrence in " + filePath)
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
                .content("Edit failed: " + e.getMessage())
                .isError(true)
                .build();
        }
    }

    /** Count non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
