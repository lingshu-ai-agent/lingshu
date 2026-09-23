package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Slot 2 built-in Tool — Run a shell command (Story #019, dsh §6.5 (1) extension).
 *
 * <p>Delegates to {@link RuntimeSandbox.ProcessRunner} (injected by
 * {@link LocalToolsAutoConfiguration} at construction time) which enforces the
 * tenant-aware command whitelist — see
 * {@link ai.lingshu.core.impl.sandbox.DefaultRuntimeSandbox}.
 *
 * <p>Synchronous blocking — waits up to {@code ctx.callConfig().timeoutSeconds()}
 * (default {@code toolTimeoutSeconds} from {@code AgentConfig}, fallback 30s) for
 * process completion, then destroys forcibly on timeout.
 *
 * <p>Exit 0 → {@code ToolResult.success(stdout)}. Non-zero → {@code ToolResult.error}
 * with exit code + stderr appended under a {@code [stderr]} header (mirrors FR-007
 * pattern in DefaultToolExecutorTest). The {@code RuntimeSandbox.ProcessRunner} throws
 * {@code ToolException.PermissionDeniedException} for non-whitelisted commands, which
 * the outer {@link ai.lingshu.core.impl.tool.DefaultToolExecutor#dispatch} catch
 * translates to {@code ToolResult.error("Permission denied: ...")} — see
 * {@code AC-019-10} and the BashToolTest L2 case.
 */
@Component("bashTool")
public class BashTool implements Tool {

    private static final String NAME = "Bash";

    private RuntimeSandbox.ProcessRunner processRunner;

    public BashTool() {
        // processRunner is wired by LocalToolsAutoConfiguration after construction.
        // If not wired (test / custom bootstrap), execute() returns ToolResult.error
        // instead of NPE — defensive default keeps the tool callable.
    }

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Run a whitelisted shell command; exit 0 → stdout, non-zero → error";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = new ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("command").put("type", "string")
            .put("description", "Shell command (must be in tenant whitelist)");
        props.putObject("description").put("type", "string")
            .put("description", "Optional human-readable description for the LLM");
        schema.putArray("required").add("command");
        return schema;
    }

    /**
     * Wire the tenant-aware process runner — package-private setter invoked by
     * {@link LocalToolsAutoConfiguration}. Tests inject a mock directly.
     */
    void setProcessRunner(RuntimeSandbox.ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        if (processRunner == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("BashTool.processRunner not wired — LocalToolsAutoConfiguration missing?")
                .isError(true)
                .build();
        }

        JsonNode input = call.getInput();
        if (input == null || input.get("command") == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("missing required field: command")
                .isError(true)
                .build();
        }
        String command = input.get("command").asText();
        if (command == null || command.trim().isEmpty()) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("command must not be empty")
                .isError(true)
                .build();
        }

        try {
            Process p = processRunner.run(command, Collections.emptyList(), ctx.workingDirectory());
            int timeoutSec = ctx.callConfig().getTimeoutSeconds();
            if (timeoutSec <= 0) {
                timeoutSec = 30;
            }
            boolean finished = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("Command timed out after " + timeoutSec + "s")
                    .isError(true)
                    .build();
            }
            String stdout = readStream(p.getInputStream());
            String stderr = readStream(p.getErrorStream());
            int exit = p.exitValue();
            if (exit != 0) {
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("exit " + exit
                        + (stderr.isEmpty() ? "" : "\n[stderr]\n" + stderr))
                    .isError(true)
                    .build();
            }
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content(stdout + (stderr.isEmpty() ? "" : "\n[stderr]\n" + stderr))
                .isError(false)
                .build();
        } catch (IOException e) {
            // ToolException.PermissionDeniedException from RuntimeSandbox.process() is
            // a RuntimeException subclass — caught by DefaultToolExecutor outer wrapper,
            // NOT here. Other IOExceptions land here.
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("Command failed: " + e.getMessage())
                .isError(true)
                .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.builder()
                .status(ToolResult.Status.CANCELLED)
                .toolUseId(call.getId())
                .content("Command interrupted")
                .isError(true)
                .build();
        }
    }

    private static String readStream(InputStream is) throws IOException {
        try (BufferedReader r = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        }
    }
}
