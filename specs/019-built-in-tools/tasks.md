# Story #019 `built-in-tools` — Tasks

> **Status**: Draft 2026-09-23
> **Implements**: `specs/019-built-in-tools/plan.md`
> **Test budget**: 40 cases / 10 files

---

## T-01 — `AgentConfig.ToolsConfig` 嵌套配置

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(修改)

**操作**:
1. 在 `// ── v1.5.5 business config trio ──` 段后 / `ClaudeMd` 类之前,新增 `@Value public static class ToolsConfig { ... }`,3 字段 `enabled` / `maxReadBytes` / `maxWriteBytes` + `defaults()` 工厂 + `validate()` 方法
2. `AgentConfig` 顶层新增 `ToolsConfig tools;` 字段(L68 `compactorConfig` 之后)
3. `AgentConfig.defaults()`(检查是否存在,如不存在仅在 `AgentConfigDefaults.java` 中)填充 `ToolsConfig.defaults()`(向后兼容空 yml)
4. `import java.util.ArrayList;`(已存在)

**DoD**: `mvn -pl lingshu-core compile` 通过;`AgentConfigToolsValidationTest.invalidMaxReadBytes_throwsLingsC02` 2 case 可写(AC-019-13)。

---

## T-02 — `LocalToolProps`(@Value 不可变 props)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolProps.java`(新)

**实现**:
- `@Value` 不可变类,2 字段(`maxReadBytes` / `maxWriteBytes`)
- 静态工厂 `from(AgentConfig cfg)`:读 `cfg.getTools()`,若 null 走 `defaults()` 再构造
- Javadoc 明确「Slot core 不引用本类,仅 AutoConfiguration 边界翻译」

**DoD**: `mvn -pl lingshu-core compile` 通过。

---

## T-03 — `ReadTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/ReadTool.java`(新)

**实现**(对齐 dsh §6.5 (1) L4427-4452 + plan.md §3):
- `@Component("readTool") public class ReadTool implements Tool`
- 构造器 `ReadTool(LocalToolProps props)`:final 字段 `maxReadBytes`
- `name() = "Read"` + `description()` 包含 maxReadBytes 信息
- `inputSchema()`:`{type: object, properties: {file_path: {type: string}}, required: [file_path]}`
- `execute(ToolCall, ToolExecutionContext)`:
  - `call.getInput().get("file_path").asText()` 取路径
  - `resolveSafePath(path, ctx)`:相对路径以 `ctx.workingDirectory()` 为根,`.normalize()`,**`..` 段或逃出 workingDir 抛 IllegalArgumentException**
  - `Files.isDirectory(resolved)` → `ToolResult.error("Is a directory: ...")`(EC-019-1)
  - `Files.readAllBytes` → utf8 → 截断 + 末尾 marker `"\n...[truncated, original N bytes]"`
  - `IllegalArgumentException` → `ToolResult.error("Path traversal denied: ...")`(AC-019-4)
  - `IOException` → `ToolResult.error("Read failed: <msg>")`

**DoD**: `mvn -pl lingshu-core compile` 通过;`ReadToolTest` 6 case 全过(AC-019-3 / AC-019-4 + EC-019-1)。

---

## T-04 — `WriteTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/WriteTool.java`(新)

**实现**(对齐 plan.md §4):
- `@Component("writeTool") public class WriteTool implements Tool`
- 构造器 `WriteTool(LocalToolProps props)`:final 字段 `maxWriteBytes`
- `name() = "Write"` + description 含 maxWriteBytes 信息
- `inputSchema()`:`{file_path: string, content: string}` 两者 required
- `execute(...)`:
  - **优先 guard**:content.length() > maxWriteBytes → `ToolResult.error(...)`,**不写盘**(AC-019-6)
  - `resolveSafePath` 同 ReadTool
  - `Files.isDirectory` → error(EC-019-2)
  - `Files.createDirectories(parent)` + `Files.write(path, bytes, CREATE | TRUNCATE_EXISTING)`
  - 返 `ToolResult.success(call.getId(), "wrote N bytes to <path>")`
  - 异常路径同 ReadTool

**DoD**: `mvn -pl lingshu-core compile` 通过;`WriteToolTest` 4 case 全过(AC-019-5 / AC-019-6 + EC-019-2)。

---

## T-05 — `EditTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/EditTool.java`(新)

**实现**(对齐 plan.md §5):
- `@Component("editTool") public class EditTool implements Tool`
- `name() = "Edit"` + description 说明「old_string 必须唯一匹配」
- `inputSchema()`:`{file_path, old_string, new_string}` 三者 required
- `execute(...)`:
  - **优先 guard**:oldStr.equals(newStr) → `ToolResult.error("No-op edit: ...")`(EC-019-3)
  - `resolveSafePath` + `Files.readAllBytes`
  - `firstIdx = content.indexOf(oldStr)`:
    - `firstIdx < 0` → `ToolResult.error("old_string not found ...")`(AC-019-8)
    - `lastIdx = content.lastIndexOf(oldStr)`;`firstIdx != lastIdx` → `ToolResult.error("old_string matches N times in ...")`(AC-019-8)
  - 替换 + `Files.write` + 返 `ToolResult.success(call.getId(), "replaced 1 occurrence in <path>")`(AC-019-7)

**DoD**: `mvn -pl lingshu-core compile` 通过;`EditToolTest` 5 case 全过(AC-019-7 / AC-019-8 + EC-019-3)。

---

## T-06 — `BashTool`(@Component implements Tool)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/BashTool.java`(新)

**实现**(对齐 plan.md §6):
- `@Component("bashTool") public class BashTool implements Tool`
- `name() = "Bash"` + description「whitelisted shell command」
- `inputSchema()`:`{command: string (required), description: string (optional)}`
- `execute(...)`:
  - empty command → `ToolResult.error("command must not be empty")`(EC-019-4)
  - **`processRunner` 由 LocalToolsAutoConfiguration 注入**(非 ctx 字段)
  - `processRunner.run(command, [], ctx.workingDirectory())` 走 tenant whitelist
  - `p.waitFor(timeoutSec, TimeUnit.SECONDS)` —— `timeoutSec = ctx.callConfig().getTimeoutSeconds()` 默认 30
  - 未完成 → `p.destroyForcibly()` + `ToolResult.error("Command timed out ...")`
  - exit 0 → `ToolResult.success(stdout)`(AC-019-9)
  - exit ≠ 0 → `ToolResult.error("exit N\n[stderr]\n<stderr>")`(AC-019-9)
  - IOException / InterruptedException → 翻译为 `ToolResult.error` / `.CANCELLED`
- **关键不变项**:BashTool **不直接 import `DefaultRuntimeSandbox`**,只依赖 `RuntimeSandbox.ProcessRunner` 接口(dsh §4.7 L695-697)—— 可测试性 + 不污染 Slot core

**DoD**: `mvn -pl lingshu-core compile` 通过;`BashToolTest` 5 case 全过(AC-019-9 / AC-019-10 + EC-019-4)。

---

## T-07 — `LocalToolsAutoConfiguration`(@Configuration + 注册)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfiguration.java`(新)

**实现**(对齐 plan.md §7):
- `@Configuration` + `@ConditionalOnProperty(name = "agent.tools.enabled", havingValue = "true", matchIfMissing = true)`
- 构造器收 `ObjectProvider<DefaultToolExecutor>` + `ObjectProvider<RuntimeSandbox>` + `AgentConfig`(走 `LocalToolProps.from`)
- 4 个 `@Bean(name = "readTool"/"writeTool"/"editTool"/"bashTool")` 各自独立 Bean 名(避免 plugin override 冲突)
- `BashTool` 构造时调 `setProcessRunner(sandboxProvider.getIfAvailable().process())`(优雅降级)
- `@PostConstruct registerTools()`:拿 `DefaultToolExecutor` bean 调 4 次 `register(tool)` + INFO 日志
- EC-019-6:`executorProvider.getIfAvailable() == null` → WARN 日志 + 跳过注册,不抛异常

**DoD**: `mvn -pl lingshu-core compile` 通过;`LocalToolsAutoConfigurationTest` 3 case 全过(AC-019-11 / AC-019-12 + EC-019-6)。

---

## T-08 — L1 单元测试(Names / Schemas / Exceptions / Validation)

**文件 1**:`LocalToolNamesTest.java`(新,4 case)
- `read_name_description`
- `write_name_description`
- `edit_name_description`
- `bash_name_description`

**文件 2**:`LocalToolSchemasTest.java`(新,4 case)
- 4 Tool 各 1 case:断言 `inputSchema().has("type")` + `get("required")` 非空数组

**文件 3**:`LocalToolExceptionsTest.java`(新,5 case)
- 4 Tool 各 1 case 走 `DefaultToolExecutor.dispatch` 异常翻译为 `ToolResult.error`(EC-019-5)
- 1 case EC-019-6:`LocalToolsAutoConfiguration` 在 `DefaultToolExecutor` 缺失时优雅降级

**文件 4**:`AgentConfigToolsValidationTest.java`(新,3 case)
- `defaults_toolsConfig_has3Fields`(AC-019-13 基础)
- `invalidMaxReadBytes_throwsLingsC02`(AC-019-13)
- `invalidMaxWriteBytes_throwsLingsC02`(AC-019-13)

**DoD**: 16 case 全过。

---

## T-09 — L1 单元测试(4 Tool 各自测试)

**文件 1**:`ReadToolTest.java`(新,6 case)
- `readExistingFile_returnsContent`(AC-019-3)—— `@TempDir` + 写文件 + `ReadTool.execute`
- `readOverLimit_truncatesAndAppendsMarker`(AC-019-3)—— 写 60000 bytes 文件 + `maxReadBytes=50000`
- `readNonExistent_returnsError`(AC-019-4)
- `readPathTraversal_returnsError`(AC-019-4)—— `file_path="../../etc/passwd"`
- `readDirectory_returnsError`(EC-019-1)
- `readEmptyFile_returnsEmptyString`(回归)

**文件 2**:`WriteToolTest.java`(新,4 case)
- `writeNewFile_createsFile`(AC-019-5)—— `@TempDir` + 写后断言文件落盘
- `writeOverwriteExisting_replacesContent`(AC-019-5)
- `writeOverLimit_returnsErrorAndNoFile`(AC-019-6)—— 写 60000 bytes + maxWriteBytes=50000,断言文件**未**创建
- `writeToDirectory_returnsError`(EC-019-2)

**文件 3**:`EditToolTest.java`(新,5 case)
- `editSingleMatch_replacesAndReturnsSuccess`(AC-019-7)
- `editNoMatch_returnsError`(AC-019-8)
- `editMultipleMatch_returnsError`(AC-019-8)
- `editNoOp_returnsError`(EC-019-3)
- `editPathTraversal_returnsError`(回归)

**文件 4**:`BashToolTest.java`(新,5 case L2 Slice)
- `runWhitelistedCommand_returnsSuccess`(AC-019-9)—— mock `ProcessRunner` 返 exit 0 + stdout="hello\n"
- `runNonZeroExit_returnsError`(AC-019-9)—— mock 返 exit 1 + stderr="oops"
- `runNotWhitelistedCommand_returnsPermissionDenied`(AC-019-10)—— mock `ProcessRunner.run` 抛 `ToolException.PermissionDeniedException` + `DefaultToolExecutor.dispatch` 翻译
- `runEmptyCommand_returnsError`(EC-019-4)
- `runTimeout_returnsErrorAndDestroysProcess`(回归)—— `waitFor` 返 false → destroyForcibly + error

**DoD**: 20 case 全过。

---

## T-10 — L2 + L3 集成测试(AutoConfiguration + E2E)

**文件 1**:`LocalToolsAutoConfigurationTest.java`(新,3 case L2)
- `startup_registers4ToolsToDefaultToolExecutor`(AC-019-11)—— 起 mini Spring `ApplicationContext`(`AnnotationConfigApplicationContext` with `@ComponentScan("ai.lingshu.core")` 排除 cmd-line 配置),拿 `DefaultToolExecutor` bean + 反射读 `registry` Map(或加 package-private getter),断言含 4 key
- `disabled_doesNotRegister`(AC-019-12)—— 设 `agent.tools.enabled=false` 启动 → registry 为空
- `noDefaultToolExecutor_logsWarnAndSkipsRegistration`(EC-019-6)—— mock `ObjectProvider.getIfAvailable()` 返 null,断言不抛异常

**文件 2**:`LocalToolsE2ETest.java`(新,1 case L3 集成)
- `linearTurnEngineWithReadTool_runsRealToolAndCompletes`(AC-019-14)—— 起 mini ctx 装 `@TempDir` + 真实 ReadTool + 真实 `DefaultToolExecutor`(register ReadTool)+ Stub `LlmProvider` 返 1 个 `ToolCall(name="Read", args={file_path=<tempfile>})` + 真实 `LinearTurnEngine.runTurn` + `CapturingSubscriber`,断言收到 `ToolStarted` + `ToolCompleted` + `TurnCompleted` 事件链;读出的内容能在 `ToolResult.content` 找到
- 复用 `SlowLlmProvider` / `RecordingPromptBuilder` / `CapturingSubscriber`(已存在 in `LinearTurnEngineE2ESmokeTest` 周边)

**DoD**: 4 case 全过。

---

## T-11 — 验证 + R-13 自查

**操作**:
1. 跑 `mvn -pl lingshu-core -am test` —— 全部 case(既有 252 + Story #019 新增 40 = 292)全过,0 fail / 0 error / 0 skipped
2. 跑 `mvn -pl lingshu-core dependency:tree -DincludeScope=runtime > /tmp/deps-pre.txt`
3. 跑 `mvn -pl lingshu-core compile` 后再跑 `dependency:tree` 拿 `/tmp/deps-post.txt`
4. `diff /tmp/deps-pre.txt /tmp/deps-post.txt` —— 应当**仅时间戳差异**,0 binary delta
5. 跑 `mvn -pl lingshu-core verify` 完整 verify(包括 `banned-dependencies` enforcer)
6. 准备 PR body,末尾追加 `### R-13 dependency:tree 自查` 节,贴 pre/post diff 输出

**DoD**: 所有命令 0 失败;PR body 含 R-13 节。

---

## T-12 — 文档同步(README + dsh v1.5.38 + ROADMAP.md)

**操作**:
1. **README.md** —— 测试计数 + ≥40;「## ⚡ 30 秒上手」加 Tool 段(3 行示例,展示 Read 调用);新增「Story #019 narrative section」(~30 行,4 Tool 介绍 + 自动注册 + tenant whitelist BashTool)
2. **dsh_agent_design.md v1.5.38 同步**(单独 PR,沿用 Story #018 模式):
   - §0 L1 标题版本号 v1.5.37 → v1.5.38
   - §0.4 版本 blockquote 预本条
   - §6.5 (1) L4427-4452 标注「Story #019 实施完成」一行
   - §13 changelog 表新增 v1.5.38 行(模仿 v1.5.36 Story #008 行的写法)
3. **ROADMAP.md 段一**「✅ 已完成」表追加 `#019 built-in-tools` 行

**DoD**: 3 文件全改 + 各自 `git diff` 校验。

---

## T-13 — PR 准备 + 提交

**操作**:
1. `git checkout -b story/019-built-in-tools`(从 main 拉分支)
2. `git add specs/019-built-in-tools/ lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/ lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/ README.md specs/ROADMAP.md`
3. `git commit -m "feat(core): Story #019 built-in-tools — Read/Write/Edit/Bash + AutoConfig + 40 tests (AC-019-1—AC-019-14)"`
4. `git push origin story/019-built-in-tools`
5. `gh pr create --base main --title "feat(core): Story #019 built-in-tools — 4 内置 Tool + AutoConfig + 40 tests" --body "$(cat <<'EOF'
## Summary
- ReadTool / WriteTool / EditTool / BashTool 4 个内置 Tool 实现(dsh §6.5 (1))
- LocalToolsAutoConfiguration Spring 启动期自动注册到 DefaultToolExecutor.registry
- LocalToolProps @Value 不可变 props + from(AgentConfig) 工厂
- AgentConfig.ToolsConfig 嵌套(enabled + maxReadBytes + maxWriteBytes)
- BashTool 走 DefaultRuntimeSandbox.process() 复用 tenant whitelist
- 40 测试 case / 10 文件(L1 + L2 slice + L3 E2E ReAct 集成)

## Test plan
- [ ] mvn -pl lingshu-core -am test 全绿
- [ ] mvn -pl lingshu-core dependency:tree pre/post diff 0 binary delta
- [ ] mvn -pl lingshu-core verify (含 banned-dependencies enforcer) 不 fail

### R-13 dependency:tree 自查
\`\`\`
diff /tmp/deps-pre.txt /tmp/deps-post.txt
\`\`\`
(实测时贴实际输出)
EOF
)"`

**DoD**: PR 创建成功 + URL 可访问;dsh v1.5.38 单独 PR 在另一分支并行提。

---

**Last updated**: 2026-09-23
**Task author**: Claude Code (per user 2026-09-22 conversation)
