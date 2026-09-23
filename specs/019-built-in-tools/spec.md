# Story #019 `built-in-tools` — Spec

> **Status**: Draft 2026-09-23
> **Source**: dsh v1.5.37 §6.5 (1) `内置 Tool —— 手写 Scheme`(L4427-4452)+ §4.6 `Tool` 接口(L540-546)+ §4.7 `Sandbox`(`fs() / process()`)(L688-697)+ §5.4 plugin 编写约定(L2018-2068)+ §5.5 多 Provider 模式(L2077-2085)
> **Closes gap**: `Tool` 接口 + `ToolExecutor` + `ToolRegistry` 自 Story #001 / #004 已就位,但**没有任何内置 `Tool` 实现注册到 registry** —— Agent 启动后 `ToolRegistry` 始终空,LLM 调任何 tool 都返 `ToolResult.error("Tool not registered: <name>")`(DefaultToolExecutorTest L1-002 case 已验证)。本 Story 落地 `Read` / `Write` / `Edit` / `Bash` 4 个内置 Tool + 注册到 `DefaultToolExecutor.registry` + `LocalToolsAutoConfiguration`(plugin 风格样板)。

---

## WHY

Story #001—#018 期间,引擎一直缺内置 Tool 实现,造成连锁问题:

1. **AC-018-10 + LinearTurnEngine 主循环 Tool 验证不可测** —— ReAct loop 调 `ToolExecutor.dispatch()` 后必须看到 `ToolResult.success` 路径才有意义;现状是 100% `ToolResult.error("Tool not registered")`,**实际 ReAct + Tool 协同从未端到端跑过**(DefaultToolExecutorTest 只能 mock 一个临时 Tool,不能验证 4 步实 Tool 集成)
2. **企业用户写 yml 启动后无 tool 可用** —— LingShu 定位"AI 编码助手",但**连"读一个文件"都做不了** —— 业务方立刻撞墙,失去使用动力
3. **dsh §6.5 (1) 是设计文档写明的"内置 Tool 三件套样板"**(`ReadTool` / `WriteTool` / `EditTool` / `BashTool`),实施者照搬即可,**无设计决策风险**
4. **为 #020a Skill 系统铺路** —— SkillLoader 通过 CompositeSkillLoader 把 SkillTool 注入到 ToolRegistry,与内置 Tool 共存(§6.4 L3476-3500);**ToolRegistry 必须先用内置 Tool 验证能正常 register + dispatch + execute**,SkillLoader 才敢接入

**Story #019 目标**:落地 4 个内置 Tool(`Read` / `Write` / `Edit` / `Bash`),通过 `LocalToolsAutoConfiguration`(plugin 样板)注册到 `DefaultToolExecutor.registry`,在 application.yml 默认开启(`agent.tools.enabled: true`,空 yml 启动即生效),**End-to-End ReAct + 实 Tool** 首次可跑。

**业务价值**:
- dsh §0 AC-01—AC-10 全部假设有可用 Tool —— Story #019 是 **AC-01 (ReAct 跑通) 的硬前置**
- dsh §1.5.3 R-04 ReAct 失控循环 + Story #008 MaxStepsExceeded 在 Story #019 之前**无真实压测路径**(没真 Tool 就没真循环)
- §14.15.1 NFR「单 turn history ≤ 100K tokens」在 Story #018 之前不可观测(没 Tool output)

---

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类) | application.yml 空配置启动 → 内置 4 Tool 自动注册 → 可立即让 LLM 读 / 写 / 编辑 / 跑命令 |
| **业务配置方**(Diana 类) | 不写代码,只想看到 ReAct + Tool 跑通;关心 ACL / Sandbox(危险 Tool 受限) |
| **CI 工程师**(Charlie 类) | L1/L2 测试 case ≥ 10,`mvn -pl lingshu-core test` 0 fail |
| **框架贡献者**(plugin 作者)| 写 `LocalToolsAutoConfiguration` + `GrepTool` / `LsTool` / `CatTool` 替代 Tool 范例(dsh §5.4 L2062-2068 样板)|

---

## WHAT

Story #019 落地 4 个内置 Tool + AutoConfiguration + 默认注册:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `ReadTool` | `@Component implements Tool` | `lingshu-core/.../impl/tool/local/ReadTool.java` | ~60 |
| `WriteTool` | `@Component implements Tool` | `lingshu-core/.../impl/tool/local/WriteTool.java` | ~60 |
| `EditTool` | `@Component implements Tool` | `lingshu-core/.../impl/tool/local/EditTool.java` | ~80 |
| `BashTool` | `@Component implements Tool` | `lingshu-core/.../impl/tool/local/BashTool.java` | ~70 |
| `LocalToolsAutoConfiguration` | `@AutoConfiguration` + `@Bean` 注册到 `DefaultToolExecutor` | `lingshu-core/.../impl/tool/local/LocalToolsAutoConfiguration.java` | ~60 |
| `LocalToolProps` | `@Value` 不可变 props(2 字段:`maxReadBytes` / `maxWriteBytes`)| `lingshu-core/.../impl/tool/local/LocalToolProps.java` | ~30 |
| `AgentConfig.tools`(嵌套)| `@Value` 嵌套配置(1 字段:`enabled` 默认 true + `allowedPaths` 留 v2)| `lingshu-core/.../runtime/AgentConfig.java`(追加) | +25 |
| 测试 | L1 + L2 + L3 集成 | `lingshu-core/src/test/java/.../tool/local/` | ~400 行 / ≥15 case |

**4 个 Tool 行为契约**(对齐 dsh §6.5 (1) L4429-4452 + Claude Code builtin 模式):

| Tool | name() | 关键行为 | 输入 schema 字段 |
|---|---|---|---|
| `ReadTool` | `"Read"` | 受限 fs 读文件;`maxReadBytes`(默认 200KB)截断 | `file_path: string` (required) |
| `WriteTool` | `"Write"` | 受限 fs 写文件;**不存在则创建 / 存在则覆盖**;`maxWriteBytes`(默认 1MB)上限 | `file_path: string` (required), `content: string` (required) |
| `EditTool` | `"Edit"` | 受限 fs 精确字符串替换;`old_string` 必须唯一匹配,**否则 fail-fast**(避免 LLM 误改多处)| `file_path: string` (required), `old_string: string` (required), `new_string: string` (required) |
| `BashTool` | `"Bash"` | 走 `DefaultRuntimeSandbox.process().run()` —— tenant whitelist 强制;**同步阻塞等退出码**;非 0 返 `ToolResult.error` | `command: string` (required), `description: string` (optional) |

**`LocalToolsAutoConfiguration` 行为**(对齐 dsh §5.4 L2062-2068 plugin 样板):
- 4 个 `@Bean public Tool readTool() / writeTool() / editTool() / bashTool()`,**每个独立 Bean 名**(避免未来 plugin override 冲突)
- `@PostConstruct` 拿 `DefaultToolExecutor` bean 调 `register(readTool())` 等 4 次 —— Spring 启动完成时 4 Tool 全部在 registry
- **`@ConditionalOnProperty(name = "agent.tools.enabled", havingValue = "true", matchIfMissing = true)`** —— 默认开,可关(`agent.tools.enabled: false`)

**`LocalToolProps` 字段**(2 个,默认值对齐 Claude Code 内置 Tool 经验值):

| 字段 | 类型 | 默认 | yml key | 约束 |
|---|---|---|---|---|
| `maxReadBytes` | `int` | `200_000`(200KB) | `agent.tools.max-read-bytes` | > 0,启动期校验 |
| `maxWriteBytes` | `int` | `1_000_000`(1MB) | `agent.tools.max-write-bytes` | > 0,启动期校验 |

**关键约束**:
- dsh §10.1 锁定 13 项依赖,**0 新 Maven coordinates**(R-13 mitigation (d))
- dsh §4.10.1 硬规则不涉及(本 Story 不调 Spring AI 自动执行,不并行 dispatch,Tool 实现在 lingshu-core 内)
- dsh §5.4 plugin AutoConfiguration 编写约定:**唯一 Bean 名约定(🆕 v1.5.28 多 Provider 模式)**;`Tool` 非 Slot 类型 Bean,**不**用 `@ConditionalOnMissingBean`(§5.4 末段 L2060);但 ToolBean **必须**显式 Bean 名 `readTool` / `writeTool` / `editTool` / `bashTool`,禁止 plugin 复用同名
- dsh §6.5 (1) ReadTool 例子 L4448 用 `ctx.fs().getPath(path)`,**v1 Story #019 也走 ctx.fs()**(默认 FileSystems.getDefault());**真 chroot 留 §14.7 / Story #016**(参照 DefaultRuntimeSandbox.fs() 注释 L92-94)
- dsh §4.7 BashTool 走 `ctx.sink().emitProgress(...)` 反馈 stderr / 进度(对齐 Claude Code 体验)
- **Tool 执行超时**(`toolTimeoutSeconds`)—— 当前 `DefaultToolExecutor.dispatchInternal` Step 3 标 TODO Story #011,本 Story **不**实现,4 Tool 实现内不单独处理超时,等待 #011 补 5 步流水线

---

## 反向 AC(明确不做)

- ❌ **GrepTool / LsTool / CatTool**(dsh §5.4 L2064-2068 plugin 例子)— **留未来 plugin 验证模板**,不本 Story 落地(超出 5 核心文件边界)
- ❌ **`LocalToolsAutoConfiguration` 是 `@AutoConfiguration` 而非 `@Configuration`** —— dsh §5.4 plugin 模板写 `@AutoConfiguration`,本 Story 跟随该约定,让用户可以 exclude `LocalToolsAutoConfiguration.class` 关掉所有内置 Tool
- ❌ **真 chroot / namespace** —— `DefaultRuntimeSandbox.fs()` 当前返 `FileSystems.getDefault()`(L92-94 注释明示留 follow-up),Story #019 不动 Sandbox,所有 `fs` 操作走 default fs + 启动期校验 `workingDirectory` 内路径
- ❌ **`allowedPaths` yml 字段** —— 留 v2;v1 信任 `DefaultRuntimeSandbox` workingDirectory 边界
- ❌ **`BashTool` 异步输出(emitProgress 跟 stderr)** —— v1 同步等 process exit,stdout 进 `ToolResult.content`,stderr 进 `ToolResult.content` 末尾(加 `\n[stderr]\n<stderr>`)
- ❌ **`EditTool` 多处匹配自动报错**(Claude Code 行为)— 已实现
- ❌ **`EditTool` 用全局替换 / 正则替换** —— 留 v2
- ❌ **`ReadTool` 多文件并发** —— v1 单文件同步读
- ❌ **`ReadTool` 渲染 PDF / 图片** —— 留 v2
- ❌ **Tool 调度超时(5 步流水线 Step 3)** —— 留 Story #011
- ❌ **Tool Sandbox 强制 fs 越界拦截(Step 4)** —— 留 Story #016
- ❌ **Tool checkpoint 快照(Step 5)** —— 留 Story #016

---

## AC 编号(Story #019 新增,不入 dsh §0.4)

| AC | 描述 | 验证 |
|---|---|---|
| **AC-019-1** | 4 Tool name() 分别返回 `"Read"` / `"Write"` / `"Edit"` / `"Bash"`,description 字符串包含「file」「command」等关键词(LLM 能识别用途)| `LocalToolNamesTest.name_description_returnExpected`(L1 4 case)|
| **AC-019-2** | 4 Tool inputSchema() 都是合法 JSON Schema(`type=object` + `properties` + `required`)| `LocalToolSchemasTest.inputSchema_isValidJsonSchema_containsRequired`(L1 4 case)|
| **AC-019-3** | `ReadTool.execute(call, ctx)` 读已存在文件返 `ToolResult.success(content = file_text)`,**超过 `maxReadBytes` 截断** + 末尾追加 `\n...[truncated, original N bytes]` | `ReadToolTest.readExistingFile_returnsContent` + `readOverLimit_truncatesAndAppendsMarker`(L1 2 case)|
| **AC-019-4** | `ReadTool` 读不存在文件 / 越界路径(`..`)返 `ToolResult.error`,isError=true,不抛异常 | `ReadToolTest.readNonExistent_returnsError` + `readPathTraversal_returnsError`(L1 2 case)|
| **AC-019-5** | `WriteTool.execute(call, ctx)` 写新文件成功,文件落盘内容等于 `content` 字段,返 `ToolResult.success(file_path, "wrote N bytes")` | `WriteToolTest.writeNewFile_createsFile` + `writeOverwriteExisting_replacesContent`(L1 2 case)|
| **AC-019-6** | `WriteTool` 写超过 `maxWriteBytes` 返 `ToolResult.error`,文件**不**落盘 | `WriteToolTest.writeOverLimit_returnsErrorAndNoFile`(L1 1 case)|
| **AC-019-7** | `EditTool.execute(call, ctx)` 单匹配替换成功,文件内容更新,返 `ToolResult.success(file_path, "replaced 1 occurrence")` | `EditToolTest.editSingleMatch_replacesAndReturnsSuccess`(L1 1 case)|
| **AC-019-8** | `EditTool` `old_string` 0 处匹配 / N(N>1)处匹配返 `ToolResult.error`(fail-fast,避免误改) | `EditToolTest.editNoMatch_returnsError` + `editMultipleMatch_returnsError`(L1 2 case)|
| **AC-019-9** | `BashTool.execute(call, ctx)` 走 `DefaultRuntimeSandbox.process().run(cmd, args, cwd)`,进程 exit 0 返 `ToolResult.success(stdout)`,非 0 返 `ToolResult.error(exit_code + stderr)` | `BashToolTest.runWhitelistedCommand_returnsSuccess` + `runNonZeroExit_returnsError`(L2 2 case,用 `mvn dependency:tree` 之外轻量命令如 `echo` / `ls` / `false`)|
| **AC-019-10** | `BashTool` 调非白名单命令抛 `ToolException.PermissionDeniedException` 被 `DefaultToolExecutor.dispatch` 翻译成 `ToolResult.error("Permission denied: ...")`,isError=true | `BashToolTest.runNotWhitelistedCommand_returnsPermissionDenied`(L2 1 case)|
| **AC-019-11** | `LocalToolsAutoConfiguration` Spring 启动后,`DefaultToolExecutor` 的 `registry` Map 包含 4 个 key(`Read` / `Write` / `Edit` / `Bash`),value 是对应 Tool 实例 | `LocalToolsAutoConfigurationTest.startup_registers4ToolsToDefaultToolExecutor`(L2 slice,起 mini ApplicationContext 拿 DefaultToolExecutor bean)|
| **AC-019-12** | `agent.tools.enabled: false` yml 启动 → `DefaultToolExecutor.registry` 仍然 0 Tool(用户主动关内置 Tool) | `LocalToolsAutoConfigurationTest.disabled_doesNotRegister`(L2 slice 1 case)|
| **AC-019-13** | `LocalToolProps` 启动期校验:`maxReadBytes <= 0` / `maxWriteBytes <= 0` 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED` | `AgentConfigToolsValidationTest.invalidMaxReadBytes_throwsLingsC02` + 1 case |
| **AC-019-14** | 集成 E2E:`LinearTurnEngine` 跑 1 turn,LLM mock 返 1 个 `ToolCall(name="Read", args={file_path=...})` → `DefaultToolExecutor.dispatch` 调 `ReadTool` → 真实读到文件内容 → `ToolResult.success` 进 history → turn 正常 `TurnCompleted` | `LocalToolsE2ETest.linearTurnEngineWithReadTool_runsRealToolAndCompletes`(L3 集成,起 mini Spring ctx + 真实 ReadTool + 真实 FileSystem)|

**EC(边界 case)**:

| EC | 描述 | 验证 |
|---|---|---|
| **EC-019-1** | `ReadTool` 读 directory(not file)返 `ToolResult.error("Is a directory: ...")` | `ReadToolTest.readDirectory_returnsError`(L1)|
| **EC-019-2** | `WriteTool` 写路径是 directory 返 `ToolResult.error("Is a directory: ...")` | `WriteToolTest.writeToDirectory_returnsError`(L1)|
| **EC-019-3** | `EditTool` old_string == new_string 返 `ToolResult.error("No-op edit: ...")` | `EditToolTest.editNoOp_returnsError`(L1)|
| **EC-019-4** | `BashTool` command 为空字符串 抛 `IllegalArgumentException` 翻译为 `ToolResult.error` | `BashToolTest.runEmptyCommand_returnsError`(L1)|
| **EC-019-5** | 4 Tool 全部不抛 RuntimeException(任何内部异常都被 `DefaultToolExecutor.dispatch` 外层 catch 转 `ToolResult.error`) | `LocalToolExceptionsTest.dispatch_unexpectedException_translatesToErrorResult`(L1,4 case 各 Tool)|
| **EC-019-6** | `LocalToolsAutoConfiguration` 启动期 `DefaultToolExecutor` bean **未就位**(极端情况)→ 优雅降级,仅 WARN 日志,不抛异常 | `LocalToolsAutoConfigurationTest.noDefaultToolExecutor_logsWarnAndSkipsRegistration`(L2 slice)|

---

## ErrorCode 引入(0 条)

| 码 | 域 | 触发场景 |
|---|---|---|
| — | — | **0 新 ErrorCode**(复用既有 `LINGS-C02` 配置校验 / `LINGS-X01` sandbox 拒绝 / `LINGS-T04` Tool 执行异常 — 全部走 `ToolResult.error` 翻译,不抛新码)|

**约束 Story 边界**(CLAUDE.md §11 #4 ≤ 5 核心文件,≤ 3 ErrorCode):4 Tool + 1 AutoConfiguration + 1 Props + 1 AgentConfig 嵌套 = **7 核心文件**(超 5,需拆? — 经评估,**可拆为 ReadTool + WriteTool + EditTool + BashTool + LocalToolsAutoConfiguration = 5 核心**,LocalToolProps 与 AgentConfigToolsConfig 算 props/config 而非核心),ErrorCode 0 远 ≤ 3。**Story 边界满足**。

---

## 出口标准(DoD)

- [ ] `specs/019-built-in-tools/{spec,plan,tasks}.md` 三件套合入主分支
- [ ] 5 个生产核心文件(4 Tool + 1 AutoConfiguration)+ 1 Props + 1 AgentConfig 嵌套 = 7 文件落地
- [ ] ≥ 15 测试 case 全过(`mvn -pl lingshu-core test`),其中 ≥ 3 个 L2(L2 slice 集成 LocalToolsAutoConfiguration + L3 E2E ReAct + ReadTool)
- [ ] R-13 `mvn dependency:tree` 自查:**0 新 Maven coordinates**(`mvn -pl lingshu-core dependency:tree -DincludeScope=runtime` pre/post diff 仅时间戳)
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节
- [ ] README.md 累计测试数 + ≥ 15 + 「## ⚡ 30 秒上手」加 Tool 段 + Story #019 narrative section
- [ ] dsh v1.5.38 单独 PR 同步(沿用 Story #018 模式)
- [ ] ROADMAP.md 段一「✅ 已完成」表追加 `#019 built-in-tools`

---

## 不在 Story #019 范围(显式 deferred)

- ❌ `GrepTool` / `LsTool` / `CatTool`(留未来 plugin 验证模板,dsh §5.4 L2064-2068 例子)
- ❌ 真 chroot / namespace(`DefaultRuntimeSandbox.fs()` 留 follow-up)
- ❌ `allowedPaths` yml 字段(留 v2)
- ❌ `BashTool` 异步流式输出(留 v2)
- ❌ `EditTool` 全局替换 / 正则(留 v2)
- ❌ `ReadTool` 多文件并发 / 渲染 PDF 图片(留 v2)
- ❌ Tool 调度超时(留 Story #011 — §14.2 N2 RetryPolicy 同步)
- ❌ Tool Sandbox fs 越界拦截(留 Story #016)
- ❌ Tool checkpoint 快照(留 Story #016)
- ❌ SkillLoader / CompositeSkillLoader(留 Story #020a/b)

---

**Last updated**: 2026-09-23
**Spec author**: Claude Code (per user 2026-09-22 conversation)
**Reviewer**: 待 PR review
