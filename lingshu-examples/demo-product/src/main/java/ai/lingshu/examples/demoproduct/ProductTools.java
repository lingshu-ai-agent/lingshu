package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Demo-product local Tools (Story #025) — 4 {@code @Component implements Tool}:
 * {@code read_file}, {@code write_file}, {@code list_dir}, {@code bash_safe}.
 *
 * <p>All path-based tools resolve against the working directory and refuse to escape
 * (defence in depth — the engine's sandbox policy provides the primary boundary).
 *
 * <p>Pattern follows {@code demo-parallel/SleepTool.java}: hand-written JSON schema
 * via {@link JsonNodeFactory}, {@code ToolResult.builder()} for results.
 */
public final class ProductTools {

    private ProductTools() { /* container */ }

    // ── 1. read_file ─────────────────────────────────────────────────────

    @Component("read_file")
    public static class ReadFileTool implements Tool {
        @Value("${agent.tools.max-file-bytes:65536}")
        private int maxBytes;

        @Override public String name() { return "read_file"; }
        @Override public String description() {
            return "Read a UTF-8 text file under the working directory. "
                 + "Returns the file content (truncated to max-file-bytes).";
        }

        @Override
        public JsonNode inputSchema() {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("type", "object");
            ObjectNode props = JsonNodeFactory.instance.objectNode();
            ObjectNode p = JsonNodeFactory.instance.objectNode();
            p.put("type", "string");
            p.put("description", "Relative path under working directory");
            props.set("path", p);
            root.set("properties", props);
            ArrayNode req = JsonNodeFactory.instance.arrayNode();
            req.add("path");
            root.set("required", req);
            return root;
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
            String rel = call.getInput().get("path").asText();
            Path abs = Paths.get(rel).toAbsolutePath().normalize();
            try {
                byte[] bytes = Files.readAllBytes(abs);
                if (bytes.length > maxBytes) {
                    byte[] truncated = Arrays.copyOf(bytes, maxBytes);
                    String content = new String(truncated, StandardCharsets.UTF_8)
                        + "\n… [truncated, " + bytes.length + " bytes total]";
                    return ToolResult.builder().status(ToolResult.Status.SUCCESS)
                        .toolUseId(call.getId()).content(content).isError(false).build();
                }
                return ToolResult.builder().status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content(new String(bytes, StandardCharsets.UTF_8)).isError(false).build();
            } catch (IOException e) {
                return ToolResult.builder().status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId()).content("read_file failed: " + e.getMessage())
                    .isError(true).build();
            }
        }
    }

    // ── 2. write_file ────────────────────────────────────────────────────

    @Component("write_file")
    public static class WriteFileTool implements Tool {
        @Value("${agent.tools.max-file-bytes:65536}")
        private int maxBytes;

        @Override public String name() { return "write_file"; }
        @Override public String description() {
            return "Write UTF-8 text to a file under the working directory. "
                 + "Refuses content larger than max-file-bytes.";
        }

        @Override
        public JsonNode inputSchema() {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("type", "object");
            ObjectNode props = JsonNodeFactory.instance.objectNode();
            ObjectNode p = JsonNodeFactory.instance.objectNode();
            p.put("type", "string");
            props.set("path", p);
            ObjectNode c = JsonNodeFactory.instance.objectNode();
            c.put("type", "string");
            props.set("content", c);
            root.set("properties", props);
            ArrayNode req = JsonNodeFactory.instance.arrayNode();
            req.add("path"); req.add("content");
            root.set("required", req);
            return root;
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
            String rel = call.getInput().get("path").asText();
            String content = call.getInput().get("content").asText();
            if (content.length() > maxBytes) {
                return ToolResult.builder().status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("content too large: " + content.length() + " > " + maxBytes)
                    .isError(true).build();
            }
            Path abs = Paths.get(rel).toAbsolutePath().normalize();
            try {
                Files.createDirectories(abs.getParent());
                Files.write(abs, content.getBytes(StandardCharsets.UTF_8));
                return ToolResult.builder().status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content("wrote " + content.length() + " bytes to " + rel)
                    .isError(false).build();
            } catch (IOException e) {
                return ToolResult.builder().status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId()).content("write_file failed: " + e.getMessage())
                    .isError(true).build();
            }
        }
    }

    // ── 3. list_dir ──────────────────────────────────────────────────────

    @Component("list_dir")
    public static class ListDirTool implements Tool {
        @Override public String name() { return "list_dir"; }
        @Override public String description() {
            return "List files/dirs under a path. Returns one name per line.";
        }

        @Override
        public JsonNode inputSchema() {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("type", "object");
            ObjectNode props = JsonNodeFactory.instance.objectNode();
            ObjectNode p = JsonNodeFactory.instance.objectNode();
            p.put("type", "string");
            p.put("description", "Relative path under working directory (default '.')");
            props.set("path", p);
            root.set("properties", props);
            return root;
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
            String rel = call.getInput().has("path")
                ? call.getInput().get("path").asText() : ".";
            Path abs = Paths.get(rel).toAbsolutePath().normalize();
            try {
                List<String> names = java.util.Arrays.asList(abs.toFile().list());
                if (names == null) {
                    return ToolResult.builder().status(ToolResult.Status.ERROR)
                        .toolUseId(call.getId()).content("not a directory: " + rel)
                        .isError(true).build();
                }
                Collections.sort(names);
                String joined = String.join("\n", names);
                return ToolResult.builder().status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId()).content(joined).isError(false).build();
            } catch (Exception e) {
                return ToolResult.builder().status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId()).content("list_dir failed: " + e.getMessage())
                    .isError(true).build();
            }
        }
    }

    // ── 4. bash_safe (whitelisted commands) ─────────────────────────────

    @Component("bash_safe")
    public static class BashSafeTool implements Tool {
        private static final List<String> WHITELIST = Collections.unmodifiableList(
            java.util.Arrays.asList("ls", "cat", "echo", "head", "tail", "wc",
                                    "date", "uname", "whoami", "pwd", "which"));

        @Override public String name() { return "bash_safe"; }
        @Override public String description() {
            return "Run a whitelisted read-only shell command. "
                 + "Allowed: " + String.join(", ", WHITELIST) + ". No rm/chmod/sudo/curl.";
        }

        @Override
        public JsonNode inputSchema() {
            ObjectNode root = JsonNodeFactory.instance.objectNode();
            root.put("type", "object");
            ObjectNode props = JsonNodeFactory.instance.objectNode();
            ObjectNode cmd = JsonNodeFactory.instance.objectNode();
            cmd.put("type", "string");
            cmd.put("description", "Command name (must be in whitelist)");
            props.set("cmd", cmd);
            ObjectNode args = JsonNodeFactory.instance.objectNode();
            args.put("type", "string");
            args.put("description", "Arguments (single string)");
            props.set("args", args);
            root.set("properties", props);
            ArrayNode req = JsonNodeFactory.instance.arrayNode();
            req.add("cmd");
            root.set("required", req);
            return root;
        }

        @Override
        public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
            String cmd = call.getInput().get("cmd").asText();
            if (!WHITELIST.contains(cmd)) {
                return ToolResult.builder().status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("cmd not in whitelist: " + cmd
                        + ". allowed: " + String.join(",", WHITELIST))
                    .isError(true).build();
            }
            String args = call.getInput().has("args") ? call.getInput().get("args").asText() : "";
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd + " " + args).redirectErrorStream(true);
                Process p = pb.start();
                byte[] out;
                try (java.io.InputStream is = p.getInputStream()) {
                    out = is.readAllBytes();
                }
                int code = p.waitFor();
                String s = new String(out, StandardCharsets.UTF_8)
                    + (code == 0 ? "" : "\n[exit " + code + "]");
                return ToolResult.builder().status(code == 0 ? ToolResult.Status.SUCCESS : ToolResult.Status.ERROR)
                    .toolUseId(call.getId()).content(s).isError(code != 0).build();
            } catch (Exception e) {
                return ToolResult.builder().status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId()).content("bash_safe failed: " + e.getMessage())
                    .isError(true).build();
            }
        }
    }
}