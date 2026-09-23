# Story #019 `built-in-tools` — Plan

> **Status**: Draft 2026-09-23
> **Implements**: `specs/019-built-in-tools/spec.md`
> **Reference**: dsh v1.5.37 §6.5 (1) L4427-4452 (内置 Tool 样板)+ §4.6 L540-546 (Tool 接口)+ §4.7 L682-697 (Sandbox fs/process)+ §5.4 L2018-2068 (plugin AutoConfiguration 编写约定)+ §5.5 L2077-2085 (🆕 v1.5.28 多 Provider 模式)+ §10.1 L6303-6332 (13 项依赖锁定)

---

## 接口设计

### 1. `AgentConfig.ToolsConfig`(嵌套 @Value)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(追加)

```java
/**
 * Story #019 — Built-in local tools enable toggle.
 *
 * <p>4 built-in {@link ai.lingshu.core.slot.Tool}s (Read / Write / Edit / Bash)
 * are wired by {@code LocalToolsAutoConfiguration} at startup. Disable via
 * {@code agent.tools.enabled: false} (e.g. when shipping a fully remote A2A agent).
 */
@Value
public static class ToolsConfig {
    /** Toggle local tool registration; default {@code true} for zero-config Story #001 AC-01-2. */
    boolean enabled;

    /** Read file size cap; default {@code 200_000} bytes (200KB). */
    int maxReadBytes;

    /** Write file size cap; default {@code 1_000_000} bytes (1MB). */
    int maxWriteBytes;

    /** Zero-config default — all 4 tools enabled, conservative byte caps. */
    public static ToolsConfig defaults() {
        return new ToolsConfig(true, 200_000, 1_000_000);
    }

    /**
     * Validate byte caps > 0; aggregate all failures into a single
     * {@link LingsConfigException} so the user sees every problem in one shot.
     *
     * @throws LingsConfigException with code {@code "C02"} when any byte cap is &le; 0
     */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (maxReadBytes <= 0) {
            errors.add("agent.tools.max-read-bytes must be > 0 (got " + maxReadBytes + ")");
        }
        if (maxWriteBytes <= 0) {
            errors.add("agent.tools.max-write-bytes must be > 0 (got " + maxWriteBytes + ")");
        }
        if (!errors.isEmpty()) {
            throw new LingsConfigException("C02",
                "agent.tools config validation failed:\n  - " + String.join("\n  - ", errors));
        }
    }
}
```

**AgentConfig 顶层修改**:
- 新增 `ToolsConfig tools;` 字段(默认 `ToolsConfig.defaults()`)
- `AgentConfig.defaults()`(若存在)填充 `ToolsConfig.defaults()`

### 2. `LocalToolProps`(@Value 不可变 props)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolProps.java`(新)

```java
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.runtime.AgentConfig;
import lombok.Value;

/**
 * Immutable 2-field config for the 4 built-in local Tools
 * (Read / Write / Edit / Bash).
 *
 * <p>Built from {@link AgentConfig.ToolsConfig} by
 * {@link LocalToolsAutoConfiguration} at startup.
 */
@Value
public class LocalToolProps {
    int maxReadBytes;
    int maxWriteBytes;

    /** 从 cfg 构造;cfg.tools 为 null 时回退 defaults()。 */
    public static LocalToolProps from(AgentConfig cfg) {
        AgentConfig.ToolsConfig tc = cfg.getTools();
        if (tc == null) {
            tc = AgentConfig.ToolsConfig.defaults();
        }
        return new LocalToolProps(tc.getMaxReadBytes(), tc.getMaxWriteBytes());
    }
}
```

### 3. `ReadTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/ReadTool.java`(新)

```java
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Slot 2 built-in Tool — Read a file from disk (dsh §6.5 (1) L4427-4452 sample).
 *
 * <p>Reads file content as UTF-8 string. If file size > {@code maxReadBytes},
 * truncates the content and appends a marker {@code \n...[truncated, original N bytes]}.
 *
 * <p>Failure paths return {@link ToolResult#error} (dsh §4.10.1 硬规则 2):
 * <ul>
 *   <li>File not found → {@code "File not found: <path>"}</li>
 *   <li>Path is a directory → {@code "Is a directory: <path>"}</li>
 *   <li>Path traversal {@code ..} or absolute path outside working dir → {@code "Path traversal denied: <path>"}</li>
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
        String filePath = call.getInput().get("file_path").asText();
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
            byte[] bytes = Files.readAllBytes(resolved);
            String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
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
        } catch (java.io.IOException e) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("Read failed: " + e.getMessage())
                .isError(true)
                .build();
        }
    }

    /**
     * Resolve file_path against ctx.workingDirectory() and reject {@code ..} segments
     * or absolute paths outside the working dir (sandbox-lite — true chroot is Story #016).
     */
    private Path resolveSafePath(String filePath, ToolExecutionContext ctx) {
        Path wd = ctx.workingDirectory();
        Path candidate = Paths.get(filePath).isAbsolute()
            ? Paths.get(filePath)
            : wd.resolve(filePath).normalize();
        if (!candidate.startsWith(wd)) {
            throw new IllegalArgumentException("path escapes working dir: " + filePath);
        }
        return candidate;
    }
}
```

### 4. `WriteTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/WriteTool.java`(新)

```java
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * Slot 2 built-in Tool — Write content to a file (dsh §6.5 (1) sample extension).
 *
 * <p>Creates the file if it does not exist; overwrites if it does.
 * Rejects content > {@code maxWriteBytes} before any disk write.
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
        props.putObject("file_path").put("type", "string");
        props.putObject("content").put("type", "string");
        schema.putArray("required").add("file_path").add("content");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        String filePath = call.getInput().get("file_path").asText();
        String content = call.getInput().get("content").asText();
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
            Path resolved = resolveSafePath(filePath, ctx);
            if (Files.isDirectory(resolved)) {
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("Is a directory: " + filePath)
                    .isError(true)
                    .build();
            }
            Files.createDirectories(resolved.getParent() != null
                ? resolved.getParent() : ctx.workingDirectory());
            Files.write(resolved, content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content("wrote " + content.length() + " bytes to " + filePath)
                .isError(false)
                .build();
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.getId(), "Path traversal denied: " + filePath);
        } catch (java.io.IOException e) {
            return ToolResult.error(call.getId(), "Write failed: " + e.getMessage());
        }
    }

    /** Reuse ReadTool's path validation — duplicated to keep Tool classes self-contained. */
    private Path resolveSafePath(String filePath, ToolExecutionContext ctx) {
        Path wd = ctx.workingDirectory();
        Path candidate = Paths.get(filePath).isAbsolute()
            ? Paths.get(filePath)
            : wd.resolve(filePath).normalize();
        if (!candidate.startsWith(wd)) {
            throw new IllegalArgumentException("path escapes working dir: " + filePath);
        }
        return candidate;
    }
}
```

### 5. `EditTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/EditTool.java`(新)

```java
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Slot 2 built-in Tool — Edit a file by exact-string replacement.
 *
 * <p>Strict semantics (Claude Code compat):
 * <ul>
 *   <li>{@code old_string} must match exactly once — 0 or N>1 matches return
 *       {@link ToolResult#error} (fail-fast to avoid LLM-induced mass edits)</li>
 *   <li>{@code old_string} == {@code new_string} is a no-op error</li>
 * </ul>
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
        props.putObject("old_string").put("type", "string");
        props.putObject("new_string").put("type", "string");
        schema.putArray("required").add("file_path").add("old_string").add("new_string");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        String filePath = call.getInput().get("file_path").asText();
        String oldStr = call.getInput().get("old_string").asText();
        String newStr = call.getInput().get("new_string").asText();
        if (oldStr.equals(newStr)) {
            return ToolResult.error(call.getId(),
                "No-op edit: old_string equals new_string");
        }
        try {
            Path resolved = resolveSafePath(filePath, ctx);
            String content = new String(Files.readAllBytes(resolved),
                java.nio.charset.StandardCharsets.UTF_8);
            int firstIdx = content.indexOf(oldStr);
            if (firstIdx < 0) {
                return ToolResult.error(call.getId(),
                    "old_string not found in " + filePath);
            }
            int lastIdx = content.lastIndexOf(oldStr);
            if (firstIdx != lastIdx) {
                return ToolResult.error(call.getId(),
                    "old_string matches " + (content.split(java.util.regex.Pattern.quote(oldStr), -1).length - 1)
                    + " times in " + filePath + " — must match exactly once");
            }
            String updated = content.substring(0, firstIdx)
                + newStr
                + content.substring(firstIdx + oldStr.length());
            Files.write(resolved, updated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return ToolResult.success(call.getId(), "replaced 1 occurrence in " + filePath);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.getId(), "Path traversal denied: " + filePath);
        } catch (java.io.IOException e) {
            return ToolResult.error(call.getId(), "Edit failed: " + e.getMessage());
        }
    }

    private Path resolveSafePath(String filePath, ToolExecutionContext ctx) {
        Path wd = ctx.workingDirectory();
        Path candidate = Paths.get(filePath).isAbsolute()
            ? Paths.get(filePath)
            : wd.resolve(filePath).normalize();
        if (!candidate.startsWith(wd)) {
            throw new IllegalArgumentException("path escapes working dir: " + filePath);
        }
        return candidate;
    }
}
```

### 6. `BashTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/BashTool.java`(新)

```java
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
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * Slot 2 built-in Tool — Run a shell command via the tenant-aware
 * {@link RuntimeSandbox#process()} (dsh §4.7 + DefaultRuntimeSandbox L123-174).
 *
 * <p>Synchronous blocking; waits up to {@code callConfig.timeoutSeconds} (default
 * {@code toolTimeoutSeconds} from AgentConfig) for process completion.
 *
 * <p>Exit 0 → {@code ToolResult.success(stdout)}. Non-zero → {@code ToolResult.error}
 * with exit code + stderr (mirrors DefaultToolExecutorTest FR-007).
 */
@Component("bashTool")
public class BashTool implements Tool {

    private static final String NAME = "Bash";

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
        props.putObject("description").put("type", "string");
        schema.putArray("required").add("command");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        String command = call.getInput().get("command").asText();
        if (command == null || command.trim().isEmpty()) {
            return ToolResult.error(call.getId(), "command must not be empty");
        }
        // BashTool only supports a single command string (no args splitting).
        // Tenant whitelist check + ProcessBuilder happen inside RuntimeSandbox.process().
        RuntimeSandbox.ProcessRunner runner = ctx.approval() != null
            ? null /* not the right surface — see plan.md §6 */ : null;
        try {
            // dsh §4.7 — RuntimeSandbox is reachable via AgentFactory, but ToolExecutionContext
            // does NOT expose it (sandbox only enters via ctx.fs / ctx.http / ctx.cancellation).
            // For Story #019 we walk up: tool receives ctx; AgentFactory wires sandbox separately.
            // We resolve via ToolExecutionContext.session().checkpoint() meta? No — that's per-turn state.
            // Cleanest: BashTool gets the ProcessRunner injected at construction time.
            // (Plan §6 — see LocalToolsAutoConfiguration wiring.)
            ProcessRunner pr = processRunner;
            Process p = pr.run(command, Collections.emptyList(), ctx.workingDirectory());
            int timeoutSec = ctx.callConfig().getTimeoutSeconds();
            boolean finished = p.waitFor(timeoutSec > 0 ? timeoutSec : 30,
                TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return ToolResult.error(call.getId(),
                    "Command timed out after " + timeoutSec + "s");
            }
            String stdout = read(p.getInputStream());
            String stderr = read(p.getErrorStream());
            int exit = p.exitValue();
            if (exit != 0) {
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("exit " + exit + (stderr.isEmpty() ? "" : "\n[stderr]\n" + stderr))
                    .isError(true)
                    .build();
            }
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content(stdout + (stderr.isEmpty() ? "" : "\n[stderr]\n" + stderr))
                .isError(false)
                .build();
        } catch (java.io.IOException e) {
            // Tenant-aware whitelist throws PermissionDeniedException which is
            // RuntimeException — caught by DefaultToolExecutor outer wrapper.
            return ToolResult.error(call.getId(),
                "Command failed: " + e.getMessage());
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

    private static String read(java.io.InputStream is) throws java.io.IOException {
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

    // Injected by LocalToolsAutoConfiguration — DefaultRuntimeSandbox.process() runner.
    // Why not via ToolExecutionContext? — dsh §4.6 ToolExecutionContext only exposes
    // fs / http / cancellation / approval / callConfig — process() is not part of
    // the per-call envelope (it's a Sandbox-level capability). Injection here keeps
    // BashTool testable with a mock ProcessRunner.
    private RuntimeSandbox.ProcessRunner processRunner;

    void setProcessRunner(RuntimeSandbox.ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }
}
```

**Note on `processRunner` injection**:Per dsh §4.6 L621-678 `ToolExecutionContext` does **not** expose `RuntimeSandbox.process()` (process() is a sandbox-level capability, not a per-call envelope). BashTool needs `ProcessRunner` to enforce the tenant whitelist — LocalToolsAutoConfiguration injects it via package-private setter. Tests inject a mock.

### 7. `LocalToolsAutoConfiguration`(@AutoConfiguration + 注册)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfiguration.java`(新)

```java
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.sandbox.DefaultRuntimeSandbox;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Built-in local Tools registration (dsh §5.4 L2018-2068 plugin 样板).
 *
 * <p>4 {@link Tool} beans: readTool / writeTool / editTool / bashTool.
 * After construction, register them with the single {@link DefaultToolExecutor}
 * bean so {@code ToolRegistry.lookup("Read")} etc. succeeds.
 *
 * <p>Disable entirely via {@code agent.tools.enabled: false}.
 *
 * <p>为什么用 {@code @Configuration} 而非 {@code @AutoConfiguration}:{@link DefaultToolExecutor}
 * is a {@code @Component} (not part of Spring Boot autoconfig), so we follow its lifecycle
 * — registering tools from a plain {@code @Configuration} is sufficient. Marking this
 * {@code @AutoConfiguration} would require excluding it to disable (extra step for users).
 */
@Configuration
@ConditionalOnProperty(name = "agent.tools.enabled", havingValue = "true", matchIfMissing = true)
public class LocalToolsAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(LocalToolsAutoConfiguration.class);

    private final ObjectProvider<DefaultToolExecutor> executorProvider;
    private final ObjectProvider<RuntimeSandbox> sandboxProvider;
    private final LocalToolProps props;

    public LocalToolsAutoConfiguration(
            ObjectProvider<DefaultToolExecutor> executorProvider,
            ObjectProvider<RuntimeSandbox> sandboxProvider,
            AgentConfig cfg) {
        this.executorProvider = executorProvider;
        this.sandboxProvider = sandboxProvider;
        this.props = LocalToolProps.from(cfg);
    }

    @Bean(name = "readTool")
    public Tool readTool() {
        return new ReadTool(props);
    }

    @Bean(name = "writeTool")
    public Tool writeTool() {
        return new WriteTool(props);
    }

    @Bean(name = "editTool")
    public Tool editTool() {
        return new EditTool(props);
    }

    @Bean(name = "bashTool")
    public Tool bashTool() {
        BashTool tool = new BashTool();
        RuntimeSandbox sb = sandboxProvider.getIfAvailable();
        if (sb != null) {
            tool.setProcessRunner(sb.process());
        }
        return tool;
    }

    /**
     * Register all 4 tools with the {@link DefaultToolExecutor} after Spring finishes
     * constructing beans. Uses {@link ObjectProvider} to gracefully degrade when the
     * executor is absent (EC-019-6 — e.g. user wires a custom ToolExecutor).
     */
    @PostConstruct
    public void registerTools() {
        DefaultToolExecutor exec = executorProvider.getIfAvailable();
        if (exec == null) {
            LOG.warn("[local-tools] DefaultToolExecutor bean not available — "
                + "4 built-in tools not registered");
            return;
        }
        List<Tool> tools = List.of(readTool(), writeTool(), editTool(), bashTool());
        for (Tool t : tools) {
            exec.register(t);
        }
        LOG.info("[local-tools] registered 4 built-in tools: "
            + "Read / Write / Edit / Bash (maxReadBytes={}, maxWriteBytes={})",
            props.getMaxReadBytes(), props.getMaxWriteBytes());
    }
}
```

**Why `@Configuration` instead of `@AutoConfiguration`**:DefaultToolExecutor is a `@Component`, not Spring Boot autoconfig. Using `@Configuration` + `@ConditionalOnProperty` keeps the toggle simple — `agent.tools.enabled: false` disables cleanly. Following dsh §5.4 plugin 样板 too literally would require `LocalToolsAutoConfiguration` to be listed in `META-INF/spring/...AutoConfiguration.imports`, which is fine but adds a step for users who want to disable it.

### 8. 测试矩阵

| 文件 | case 数 | 类型 | 覆盖 AC |
|---|---:|---|---|
| `LocalToolNamesTest.java` | 4 | L1 Unit | AC-019-1 |
| `LocalToolSchemasTest.java` | 4 | L1 Unit | AC-019-2 |
| `ReadToolTest.java` | 6 | L1 Unit | AC-019-3 / AC-019-4 + EC-019-1 |
| `WriteToolTest.java` | 4 | L1 Unit | AC-019-5 / AC-019-6 + EC-019-2 |
| `EditToolTest.java` | 5 | L1 Unit | AC-019-7 / AC-019-8 + EC-019-3 |
| `BashToolTest.java` | 5 | L2 Slice(mocks RuntimeSandbox.ProcessRunner)| AC-019-9 / AC-019-10 + EC-019-4 |
| `LocalToolExceptionsTest.java` | 5 | L1 Unit | EC-019-5 + EC-019-6 兜底 |
| `LocalToolsAutoConfigurationTest.java` | 3 | L2 Slice(起 mini Spring ctx)| AC-019-11 / AC-019-12 + EC-019-6 |
| `AgentConfigToolsValidationTest.java` | 3 | L1 Unit | AC-019-13 |
| `LocalToolsE2ETest.java` | 1 | L3 集成(起 mini ctx + 真实 ReadTool + 真实 FileSystem)| AC-019-14 |
| **合计** | **40** | — | — |

**预算 ~40 case**(Story #018 18 case → Story #019 40 case 因 4 个 Tool × AC + EC 矩阵 + AutoConfiguration + E2E 集成)

---

## 文件改动清单(7 文件:5 核心 + 1 props + 1 AgentConfig 嵌套;测试 10 文件)

| 类型 | 路径 | 行数 | 新增 / 修改 |
|---|---|---:|---|
| 核心 | `lingshu-core/.../impl/tool/local/ReadTool.java` | ~80 | 新增 |
| 核心 | `lingshu-core/.../impl/tool/local/WriteTool.java` | ~80 | 新增 |
| 核心 | `lingshu-core/.../impl/tool/local/EditTool.java` | ~95 | 新增 |
| 核心 | `lingshu-core/.../impl/tool/local/BashTool.java` | ~120 | 新增 |
| 核心 | `lingshu-core/.../impl/tool/local/LocalToolsAutoConfiguration.java` | ~85 | 新增 |
| 核心(props)| `lingshu-core/.../impl/tool/local/LocalToolProps.java` | ~30 | 新增 |
| 核心(config)| `lingshu-core/.../runtime/AgentConfig.java` | +40 | 修改(追加 `ToolsConfig` 嵌套 + `tools` 字段)|
| 测试 | `lingshu-core/.../tool/local/LocalToolNamesTest.java` | ~60 | 新增(4 case)|
| 测试 | `lingshu-core/.../tool/local/LocalToolSchemasTest.java` | ~80 | 新增(4 case)|
| 测试 | `lingshu-core/.../tool/local/ReadToolTest.java` | ~180 | 新增(6 case)|
| 测试 | `lingshu-core/.../tool/local/WriteToolTest.java` | ~140 | 新增(4 case)|
| 测试 | `lingshu-core/.../tool/local/EditToolTest.java` | ~160 | 新增(5 case)|
| 测试 | `lingshu-core/.../tool/local/BashToolTest.java` | ~200 | 新增(5 case)|
| 测试 | `lingshu-core/.../tool/local/LocalToolExceptionsTest.java` | ~100 | 新增(5 case)|
| 测试 | `lingshu-core/.../tool/local/LocalToolsAutoConfigurationTest.java` | ~180 | 新增(3 case)|
| 测试 | `lingshu-core/.../tool/local/AgentConfigToolsValidationTest.java` | ~80 | 新增(3 case)|
| 测试 | `lingshu-core/.../tool/local/LocalToolsE2ETest.java` | ~250 | 新增(1 case)|

**Story 边界 = 5 核心 Tool 文件 + 1 AutoConfiguration + 1 Props = 7 核心文件**(略超 CLAUDE.md §11 #4 ≤ 5,**判定**:ReadTool / WriteTool / EditTool / BashTool 各自独立但共享 LocalToolProps + LocalToolsAutoConfiguration — 实质 1 个独立 props + 1 个集中 registration + 4 Tool 实现,**逻辑聚合 1 个 feature = built-in-tools**;经 spec.md 评估边界判定"可接受",dto/props/config 不计核心)。40 测试 case(> 15)。

---

## 关键不变项(回归保护)

- **`Tool` 接口不变**(4 方法契约 `name / description / inputSchema / execute` 不动)
- **`DefaultToolExecutor` 不变**(5 步 pipeline 不动;新 `register` 调用是 append-only,不影响现有 dispatch 逻辑)
- **`ToolExecutionContext` 不变**(dsh §4.6 L621-678 契约不动;`RuntimeSandbox.process()` 不进入 ctx,改走 BashTool 注入)
- **`DefaultRuntimeSandbox` 不变**(tenant whitelist + ProcessBuilder 逻辑不动;BashTool 通过 `RuntimeSandbox.ProcessRunner` 接口复用)
- **`AgentConfig.tools` 嵌套新增**(旧 27 字段不动,`tools` 默认 `ToolsConfig.defaults()`)
- **`@ContractVersionRef` Tool v1.0.0 不变**
- **0 新 Maven 依赖**(13 项锁定不变,R-13 mitigation (d))
- **0 新 `AgentEvent` 嵌套类 / 0 新 `StopReason` enum 值 / 0 新 ErrorCode**

---

## 与既有 Story 的边界交叉

| 既有 Story | 影响 |
|---|---|
| #001 zero-config-bootstrap | Story #019 沿用 `@Component` + `@ConditionalOnProperty(matchIfMissing=true)` 默认装载;空 yml 启动 → 4 Tool 自动注册 |
| #004 tool-parallel-dispatch | **直接利用**:Story #019 落地的 4 Tool 通过 `DefaultToolExecutor.registry` 走默认 pipeline,parallel dispatch 与之正交 |
| #005 cancellation-token | **关注点**:`BashTool.execute` `p.waitFor(timeout)` 是阻塞 — 协作式取消(用户 Ctrl+C → `ctx.cancellation().isCancelled()` 循环检查)留 Story #011 |
| #006 multi-tenant | **直接利用**:`BashTool` 走 `DefaultRuntimeSandbox.process()` 内置 tenant whitelist,tenant 隔离自动生效 |
| #007 yaml-hot-reload | **关注点**:`ToolsConfig.tools` 字段热更触发 `validateOrThrow` + 启动期 freeze(Story #007 已落地),in-flight turn 冻结 cfg |
| #008 react-max-steps | **关注点**:`MaxStepsExceeded` 与 Tool 调用正交,但 Story #019 让 ReAct + 实 Tool 首次可测 |
| #009—#009d A2A 系列 | **关注点**:A2A 远端 Agent 通过 `RemoteAgentTool` 进入 ToolRegistry;Story #019 内置 4 Tool 与 RemoteAgentTool 共存,SlotRouter `agent.tools.enabled` 影响本地 tool |
| #011 RetryPolicy / #016 Sandbox | **延后依赖**:`Tool` 调度的超时 / fs chroot / checkpoint 留这两个 Story,Story #019 Tool 实现内不重复实现 |

---

## 实施顺序(T-NN 见 tasks.md)

1. **T-01** `AgentConfig.ToolsConfig` 嵌套 + 顶层 `tools` 字段 + `validate()` + `defaults()`
2. **T-02** `LocalToolProps`(@Value + `from(AgentConfig)` 工厂)
3. **T-03** `ReadTool`(@Component + 4 方法 + path resolution)
4. **T-04** `WriteTool`(@Component + maxWriteBytes guard)
5. **T-05** `EditTool`(@Component + exact-match semantics)
6. **T-06** `BashTool`(@Component + ProcessRunner 注入 + exit code 处理)
7. **T-07** `LocalToolsAutoConfiguration`(@Configuration + `@ConditionalOnProperty` + `@PostConstruct` 注册)
8. **T-08** 10 个测试文件(Names / Schemas / 4 Tool Tests / Exceptions / AutoConfig / Validation / E2E)
9. **T-09** `mvn -pl lingshu-core test` 全过 + `mvn dependency:tree` 自查
10. **T-10** 文档同步(README / dsh v1.5.38 / ROADMAP.md 段一)

---

**Last updated**: 2026-09-23
**Plan author**: Claude Code (per user 2026-09-22 conversation)
