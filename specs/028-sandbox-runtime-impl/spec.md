# Story #028 `sandbox-runtime-impl` — Spec

> **Status**: Draft 2026-09-30
> **Source**: dsh v1.5.46 §6.3 ChrootRuntimeSandbox(L3901-3976 接口模板就位但 0 实施 — `DefaultToolExecutionContext.http()/fs()/approval()` 3 stub 全抛 / 全 deny / 默认 FS,`PassThroughHttp` inner class L118-134 17 行抛 `UnsupportedOperationException`)+ §4.7 PermissionPolicy + §4.10.1 硬规则 2(ToolExecutor 5 步流水线"sandbox"步当前空跑,本 Story 真实现)+ §15.5 ErrorCode 域 + §5.3.1.0 SlotRouter 隐式 Router 样板(Slot 3 Sandbox 子接口走隐式 Router 模式)+ §5.5 多 Provider 模式(v1.5.28 plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)+ §13 changelog v1.5.46 行 + constitution v1.0 + ROADMAP §6 主链漏项补救(2026-09-30 加 #028 行)
> **前置依赖**:`#001` zero-config-bootstrap(`Agent` 4 final 字段 + `DefaultAgent.buildContext` 冻结语义)+ `#003` spi-slot-router(`SlotRouter<P,T>` 父类 + 多 Provider 模式)+ `#004` tool-parallel-dispatch(`ToolExecutor.dispatch()` 5 步流水线 + `DefaultToolExecutionContext` 当前 stub 集)+ `#005` cancellation-token(CancellationToken 三层贯通,`DefaultToolExecutionContext.cancellation()` 已通)+ `#007` yaml-hot-reload(`AgentConfigRegistry` AtomicReference + hot-reload 期间 cfg 冻结)+ `#025 follow-up` demo-product-sandbox-wiring(`agent.sandbox:` 5 字段接通 YAML,本 Story 真接通执行) —— **6 个 Story 已合**
> **同 Story 拆解**:无。sandbox runtime 是 §6.3 主章节唯一未落地子模块,本 Story 一次性把 4 接口 + 1 默认实现 + 1 Router + 1 AutoConfiguration + 1 ErrorCode 域启用全部落地

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.46 §6.3 ChrootRuntimeSandbox(L3901-3976 完整接口 + 实现模板)+ §4.7 PermissionPolicy(`PermissionPolicy.check()` + `Decision.AskUser/Deny/Allow`)+ §4.10.1 硬规则 2(ToolExecutor 5 步流水线 sandbox 步)+ §15.5 ErrorCode 域字母 + §15.5 S 段 reserved(本期启用 S01)+ §5.3.1.0 隐式 Router 样板 + §5.5 多 Provider 模式 + §13 changelog v1.5.46 行
- **实测发现**:2026-09-30 审 `DefaultToolExecutionContext.java` L118-134 `PassThroughHttp` inner class 时发现:`DefaultToolExecutionContext.http()` 返 `new PassThroughHttp()` 实例,3 个方法全抛 `UnsupportedOperationException("HTTP tool calls are wired in Story #016 (sandbox) — currently unsupported")` —— 但 ROADMAP 段三 §14 N1—N13 表中 **Story #016 = audit-log**(`LINGS-A01—A99` 域待启用),**不是 sandbox**。`fs()` 返 `FileSystems.getDefault()`(无 chroot)+ `approval()` 返匿名 `ApprovalGate` 全 deny(`Decision.Deny("AskUser approval flow is wired in Story #005 follow-up")`)。整个 §6.3 sandbox 5 字段(policy / runtime / workingDirectory / commandWhitelist / domainWhitelist)配置层就位(`#025 follow-up`),但执行层 0 落地
- **业务后果**(当前状态):
  - `agent.sandbox.command-whitelist: [ls, cat]` 配置后,**任何** Tool 调 `process.execute("git")` **不**被拒(SandboxApply 步直接空跑 — ToolExecutor 5 步流水线 sandbox 步当前 return null)
  - `agent.sandbox.domain-whitelist: [api.openai.com]` 配置后,**任何** Tool 调 `http.get("https://evil.com")` **不**被拒
  - `agent.sandbox.working-directory: /tmp/work` 配置后,**任何** Tool 调 `fs.read("/etc/passwd")` **不**被拒
  - 运维人员改 `application.yml` 加白名单后,**必须重启 Agent 进程**才生效 — 但 §14.8 N8 hot-reload 已经具备 cfg 切换能力,sandbox 没接到是 §6 主链漏项
  - 多租户隔离(§14.9 N9 / AC-05)的"sandbox whitelist 各生效"承诺落空 — Tenant Alice / Bob 配了不同 sandbox,但执行时全走默认(无拒绝)
- **对应风险**:**R-04**(sandbox bypass / privilege escalation — 分值 8)+ **R-13** mitigation (d)(R-13 第 14 次 PASS 强制)+ §15.5 ErrorCode 域 S(Sandbox)段 1 号启用
- **涉及 ErrorCode**:**1 新 ErrorCode** —— `LINGS-S01 SANDBOX_ACCESS_DENIED`(Sandbox 域 S 段 1 号,**新 ErrorCode 域启用**,对齐 `#023 LINGS-D01` + `#022 LINGS-T08` + `#027b LINGS-L03` reserved precedent)

---

## 1. WHY(为什么做这个 Story)

**核心问题**:LingShu §6.3 ChrootRuntimeSandbox 设计模板在 dsh §6.3 L3901-3976 已完整给出,但 0 实施 —— `DefaultToolExecutionContext` 4 个 stub(`http()` / `fs()` / `approval()` / `process()`(L3917 模板里有但当前 ctx 接口也没暴露))当前全部拒绝或绕过。

具体 4 个 gap:

1. **`http()` 全抛 `UnsupportedOperationException`** —— `PassThroughHttp` inner class L118-134 17 行 3 个方法全抛,任何 Tool 调 `ctx.http().get(url)` 都拿到 `UnsupportedOperationException("...wired in Story #016 (sandbox)...")`。这意味着 §5 LlmProvider 调用任何含 HTTP 的 tool(目前没有,但 #025 demo-product + 后续 Story 加 HTTP tool 后会触发)全部报错
2. **`fs()` 返默认 FS,无 chroot 隔离** —— L61 `return FileSystems.getDefault();` 直接返 JDK 默认 FS,**任何** Tool 调 `ctx.fs().getPath("/etc/passwd")` 真能读 `/etc/passwd`,`agent.sandbox.working-directory` 配置无效
3. **`approval()` 返匿名 ApprovalGate 全 deny** —— L70-76 `Decision.Deny("AskUser approval flow is wired in Story #005 follow-up")`,`PermissionPolicy.check()` 串入 ToolExecutor 5 步流水线当前空跑(整个 step 直接返 `Decision.Deny` 不走 §4.7 PermissionPolicy 真逻辑)
4. **`process()` 接口根本没在 ToolExecutionContext 暴露** —— `DefaultToolExecutionContext` implements `ToolExecutionContext` 但没有 `process()` 方法(对照 dsh §6.3 L3927 `RuntimeSandbox.process()` 模板存在)

**业务后果**:
- Demo-product(`#025`)+ Demo-product-a2a-server(`#025b`)接通了 `agent.sandbox:` 5 字段,但 sandbox 实际**不生效** —— `command-whitelist` / `domain-whitelist` 配置是装饰品
- §14.9 多租户隔离的"各租户 sandbox whitelist 独立"承诺完全失效(`AC-05` 部分通过但 sandbox 部分 0 落地)
- §14.10 N10 audit-log 设计时 sandbox 事件源是空集,本 Story 合入后 `LINGS-S01` 可接入审计流(已在 ROADMAP §14 N10 cross-ref 登记)
- §15.5 ErrorCode 域 `S`(Sandbox)空缺,新错误码无域可入

**Story #028 业务价值**:
- 把 dsh §6.3 L3901-3976 模板真落地:`RuntimeSandbox` interface + `AccessDeniedException` + `ProcessRunner` + `ChrootRuntimeSandbox` concrete impl + `ChrootedFileSystem` extends FileSystem + `WhitelistedHttpClient implements NetworkClient`
- `DefaultToolExecutionContext.http()/fs()/approval()` 3 stub 真接通 sandbox 5 字段(workingDirectory / commandWhitelist / domainWhitelist)+ 删 `PassThroughHttp` 17 行 inner class(L118-134 整段)
- ToolExecutor 5 步流水线"sandbox"步当前空跑变真实现(§4.10.1 硬规则 2 真守住)
- §15.5 ErrorCode 域 S 启用,LINGS-S01 = SANDBOX_ACCESS_DENIED 落地
- §14.9 多租户隔离 + AC-05 部分承诺真生效

**关键不变项**:
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 **整体** 不变(只是"sandbox"步从空跑变真实现)
- `Agent` 4 final 字段(T1→T4 不变)/ `AgentFactory.create()` 7 项校验 不变(只是多注入 1 个 Router)
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类(§0.5.46 refactor 后)/ `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- `PermissionPolicy` interface / `Decision` 4 子类 / `ToolCallConfig` 4 字段 — **0 改动**
- `ToolExecutionContext` interface 6 方法(`workingDirectory()` / `fs()` / `http()` / `approval()` / `cancellation()` / `callConfig()`)— **0 改动**(只是 default 实现真接通 RuntimeSandbox)
- `AccessDeniedException extends RuntimeException`(§15.5 S 段新异常类)— 不破坏现有 catch 链路(Sandbox 段独立)
- 9 Slot 体系不变(Slot 3 Sandbox 是隐式 Router,**不**作顶层 Slot)
- 24 字段 `AgentConfig` schema 不变(`Sandbox` 5 字段已在 `#025 follow-up` 落地)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),sandbox 真实现通过 `DefaultToolExecutionContext` 触发,**不走 ChatClient 自动执行**
- **0 新 Maven 依赖**(`ProcessBuilder` + `HashSet` + `FileSystem` + `HashMap` + `BufferedReader` 全 JDK 8 standard + Jackson 已锁)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 给 Agent 配 `agent.sandbox.command-whitelist: [ls, cat, git]` + `agent.sandbox.domain-whitelist: [api.openai.com, *.anthropic.com]` + `agent.sandbox.working-directory: ${user.dir}/work`,**Tool 调 git / 调 anthropic 真生效**;调 `rm -rf /` / `wget https://evil.com` / `cat /etc/passwd` 真拒绝抛 `LINGS-S01` |
| **多租户平台搭建者** | Tenant Alice 配 `command-whitelist: [ls]`,Tenant Bob 配 `command-whitelist: [ls, git, kubectl]` —— 各生效,Alice 调 `kubectl` 真拒绝(`AC-05` 真隔离) |
| **运维稳定性关注者(Eve 类)** | `application.yml` 加 `command-whitelist: [git]` 后,**不重启 Agent** 下一个 turn 生效(§14.8 N8 hot-reload 已合,sandbox 本 Story 合入后端到端通);`LINGS-S01` 拒绝事件可接 §14.10 N10 audit-log(已在 ROADMAP cross-ref 登记) |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `ChrootedFileSystem.getPath` prefix 校验 + `WhitelistedHttpClient` domainWhitelist `Set.contains` + `cmdWhitelist` Set.contains + `AccessDeniedException` 抛出 + `DefaultToolExecutionContext` 4 stub 委派真接通;L3 黑盒跑 `agent.runBlocking("调 git")` 真发 → `git --version` 真执行 + `agent.runBlocking("调 rm")` → `LINGS-S01` 抛错 |
| **框架贡献者 / plugin 作者(Bob 类)** | `RuntimeSandboxProvider` SPI 接口对齐 v1.5.28 多 Provider 模式(§5.5)—— 未来可加 `DockerRuntimeSandboxProvider` / `LandlockRuntimeSandboxProvider` 等替代实现,`agent.sandbox.runtime: docker` 切换无需改 classpath |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**:

1. **`RuntimeSandbox` interface**(新,Slot 3 子接口):
   - `FileSystem fs()` —— 工作目录限定的 chroot FS
   - `NetworkClient http()` —— domainWhitelist 校验的 HTTP 客户端
   - `ProcessRunner process()` —— cmdWhitelist 校验的进程执行器
   - `Decision approval(PermissionPolicy policy, Decision.AskUser ask)` —— §4.7 PermissionPolicy.check() 5 步流水线 sandbox 步真实现

2. **`AccessDeniedException extends RuntimeException`**(新,LINGS-S01 子类):
   - 构造器 `AccessDeniedException(String reason)` + `AccessDeniedException(String reason, Throwable cause)`
   - 抛出位置:`ChrootedFileSystem.getPath` prefix 校验失败 / `WhitelistedHttpClient` domainWhitelist 不含 / `cmdWhitelist` 不含 cmd
   - **错误码嵌入 message**:`"[LINGS-S01] " + reason`(对齐 #023 LINGS-D01 + #022 LINGS-T08 模式)

3. **`ProcessRunner` functional interface**(新):
   - 单方法 `Process run(String cmd, List<String> args, Path cwd) throws AccessDeniedException, IOException`
   - 默认实现:`ChrootRuntimeSandbox.runProcess(cmd, args, cwd)` —— cmdWhitelist 校验 + cwd resolveAgainstRoot + `new ProcessBuilder(cmd).command(args).directory(realCwd.toFile()).redirectErrorStream(true).start()`

4. **`ChrootRuntimeSandbox implements RuntimeSandbox`**(新,默认实现):
   - `@Component` + `@ConditionalOnProperty(name="agent.sandbox.runtime", havingValue="chroot", matchIfMissing=true)`
   - 构造器 `ChrootRuntimeSandbox(SandboxProps props)` —— 读 5 字段(policy / runtime / workingDirectory / commandWhitelist / domainWhitelist)+ 建 chrootedFs + httpClient
   - 实现 4 接口方法:`fs() / http() / process() / approval(...)`

5. **`ChrootedFileSystem extends FileSystem`**(新):
   - `getPath(String first, String... more)` —— 强制 `delegate.getPath(first, more).toAbsolutePath().normalize()` 后必须以 `root` 前缀打头,**否则抛 `AccessDeniedException("Path escapes working dir: " + full)`**
   - 其他方法委托给 delegate(FileSystem SPI 大量方法,本 Story 只覆盖 getPath,其它 delegate 给 JDK 默认实现)

6. **`WhitelistedHttpClient implements NetworkClient`**(新):
   - `get(String url)` —— 解析 host + `domainWhitelist.contains(host)` 否则抛 `AccessDeniedException("Domain not whitelisted: " + host)` + 然后真发 HTTP
   - `post(String url, String body)` 同上
   - `getStream(String url)` 同上
   - 内部用 JDK `HttpURLConnection` 发请求(JDK 8 内置,R-13 0 binary delta)

7. **`RuntimeSandboxProvider` interface**(新,Slot 3 Provider):
   - `String name()` —— `"chroot"`(默认)+ 替代实现 `"docker"` / `"landlock"` 等
   - `int priority()` —— 默认 10
   - `RuntimeSandbox create(AgentConfig.Sandbox sandbox, PermissionPolicy permissionPolicy)` —— 建 RuntimeSandbox 实例

8. **`RuntimeSandboxRouter extends SlotRouter<RuntimeSandboxProvider, RuntimeSandbox>`**(新,Slot 3 隐式 Router):
   - 对齐 §5.3.1.0 隐式 Router 模式 —— extends SlotRouter,super 传 `"RuntimeSandbox"` + Logger
   - **不**在 SlotResolver 字段里,由 `AgentFactory` 直接 `@Autowired`
   - 默认 `ChrootRuntimeSandboxProvider`(name="chroot"+ priority=10)

9. **`RuntimeSandboxAutoConfiguration`**(新,Slot 3 默认 Provider 注册):
   - `@AutoConfiguration`
   - `@Bean(name="runtimeSandboxProvider_chroot-1.0.0") public RuntimeSandboxProvider runtimeSandboxProviderChroot()` —— 返 `new RuntimeSandboxProvider() { String name() { return "chroot"; } int priority() { return 10; } RuntimeSandbox create(Sandbox sandbox, PermissionPolicy policy) { return new ChrootRuntimeSandbox(sandbox, policy); } }`
   - 多 Provider 模式对齐 v1.5.28 §5.5(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)

10. **`SandboxErrorCodes` 常量类**(新):
    - `public static final String LINGS_S01 = "LINGS-S01";`(SANDBOX_ACCESS_DENIED)
    - 注释引用 §15.5 ErrorCode 域 S 段 1 号 + §6.3 ChrootRuntimeSandbox + 对齐 #023 LINGS-D01 模式

11. **`DefaultToolExecutionContext` 改造**(modify):
    - 删除 `PassThroughHttp` inner class L118-134 17 行(整段)
    - `http()` 改 `return runtimeSandbox.http()` —— 由 `RuntimeSandboxRouter.resolve(cfg.getSandbox().getRuntime())` 注入 `RuntimeSandbox` 实例
    - `fs()` 改 `return runtimeSandbox.fs()` —— 替换 L61 `FileSystems.getDefault()`
    - `approval()` 改 `return runtimeSandbox.approval(permissionPolicy, ask)` —— 替换 L70-76 短 deny stub
    - 构造器签名 +1 字段 `private final RuntimeSandbox runtimeSandbox;` + 改 `DefaultToolExecutionContext(TurnContext turnCtx, RuntimeSandbox runtimeSandbox)`
    - 调用方更新:`LinearTurnEngine.dispatchWithPolicy` 新构造器注入

12. **`AgentFactory` 注入 RuntimeSandboxRouter**(modify):
    - 6-Router ctor 改 7-Router ctor:加 `RuntimeSandboxRouter runtimeSandboxRouter`
    - `create()` 内 `RuntimeSandbox runtimeSandbox = runtimeSandboxRouter.resolve(cfg.getSandbox().getRuntime(), cfg)` 7 项校验新增

**新配置参数**:`AgentConfig` 当前无需变更(Sandbox 5 字段已在 #025 follow-up 落地)

**用户会看到的错误码**:

| ErrorCode | 抛出位置 | 触发条件 | 用户响应 |
|---|---|---|---|
| **`LINGS-S01` `SANDBOX_ACCESS_DENIED`** | `AccessDeniedException` 子类抛点 4 处:`ChrootedFileSystem.getPath` prefix 校验失败 / `WhitelistedHttpClient.get/post/getStream` domainWhitelist 不含 / `ChrootRuntimeSandbox.runProcess` cmdWhitelist 不含 / `RuntimeSandbox.approval` PermissionPolicy AskUser deny | (a) Tool 调 `fs.getPath("/etc/passwd")` 但 sandbox.workingDirectory=`${user.dir}/work`;(b) Tool 调 `http.get("https://evil.com")` 但 sandbox.domainWhitelist=`[api.openai.com]`;(c) Tool 调 `process.execute("git")` 但 sandbox.commandWhitelist=`[ls, cat]`;(d) Tool 触发 §4.7 AskUser 但 policy = Deny | (a) 改 workingDirectory 或加 fs 路径前缀校验(下个 Story);(b) 加 `*.evil.com` 到 domainWhitelist;(c) 加 `git` 到 commandWhitelist;(d) 改 policy 为 Allow 或 AskUser |

**关键不变量**(不变项):
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 **整体不变**(只是"sandbox"步从空跑变真实现)
- `Agent` 4 final 字段 / `AgentFactory.create()` 校验模式 不变
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类(§0.5.46 refactor 后) / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **0 改动**
- `PermissionPolicy` interface / `Decision` 4 子类 / `ToolCallConfig` 4 字段 — **0 改动**
- `ToolExecutionContext` interface 6 方法契约 — **0 改动**(只 default 实现真接通)
- 9 Slot 体系不变(Slot 3 Sandbox 是隐式 Router,**不**作顶层 Slot)
- 24 字段 `AgentConfig` schema 不变
- `AccessDeniedException extends RuntimeException` —— 不破坏现有 catch 链路(Sandbox 段独立,§4.10.1 5 步流水线 sandbox 步 catch 转 ToolResult.error 即可)
- JDK 8 兼容(`ProcessBuilder` / `HashSet` / `FileSystem` / `HashMap` / `BufferedReader` 全 JDK 8 standard + Jackson 已锁)
- §15.4 ErrorCode 域字母表新增 `S = Sandbox` 域段(本 Story 启用 S01)
- dsh §15.4 ErrorCode 域字母 9 → 10 域(原 `C/S/L/T/X/R/A/Z/D` 9 域加 `S` = 10 域,**注意**:`S` 不在原 8 域表中,实际新增 `S` 域段让总段数 9 → 10)

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — `RuntimeSandbox` interface 契约就位

**Given** 完整项目源码 + `mvn compile` 通过
**When** grep `ai.lingshu.core.sandbox.RuntimeSandbox`
**Then** 找到 4 方法接口定义:`FileSystem fs()` / `NetworkClient http()` / `ProcessRunner process()` / `Decision approval(PermissionPolicy policy, Decision.AskUser ask)`,**且** Javadoc 完整覆盖 (1) 调用契约 + (2) §4.10.1 硬规则 2 流水线串联
**断言方式**:L1 Unit,`RuntimeSandbox.class.getMethods()` reflection 验证 4 方法签名 + Javadoc 非空

### AC-NN-2 — `ChrootedFileSystem.getPath` prefix 校验真拒绝越权

**Given** `Sandbox.workingDirectory = ${user.dir}/work`,`fs = new ChrootedFileSystem(FileSystems.getDefault(), rootDir)`
**When** `fs.getPath("/etc/passwd")`(rootDir 在 `${user.dir}/work`,`/etc/passwd` 不以 rootDir 打头)
**Then** 抛 `AccessDeniedException`,`exception.getMessage()` 含 `[LINGS-S01]` + `"Path escapes working dir: /etc/passwd"`
**断言方式**:L1 Unit,`assertThatThrownBy(() -> fs.getPath("/etc/passwd")).isInstanceOf(AccessDeniedException.class).hasMessageContaining("LINGS-S01")`

### AC-NN-3 — `WhitelistedHttpClient` domainWhitelist 真拒绝越权

**Given** `Sandbox.domainWhitelist = [api.openai.com]`,`http = new WhitelistedHttpClient(Set.of("api.openai.com"))`
**When** `http.get("https://evil.com/x")`
**Then** 抛 `AccessDeniedException`,`exception.getMessage()` 含 `[LINGS-S01]` + `"Domain not whitelisted: evil.com"`
**When** `http.get("https://api.openai.com/v1/models")`(白名单内)
**Then** 真发 HTTP(200 / 404 / 等具体 response,**不**抛 AccessDeniedException)
**断言方式**:L1 Unit + L3 黑盒(用 mock HTTP server 返回 200 验证真发请求 path),`verify(httpClient, times(1)).get("https://api.openai.com/v1/models")`

### AC-NN-4 — `ChrootRuntimeSandbox.runProcess` cmdWhitelist 真拒绝越权

**Given** `Sandbox.commandWhitelist = [ls, cat]`,`sandbox = new ChrootRuntimeSandbox(sandboxProps)`
**When** `sandbox.process().run("rm", List.of("-rf", "/"), Path.of("/tmp"))`
**Then** 抛 `AccessDeniedException`,`exception.getMessage()` 含 `[LINGS-S01]` + `"Command not whitelisted: rm"`
**When** `sandbox.process().run("ls", List.of("-la"), Path.of("/tmp"))`(白名单内)
**Then** 真发 ProcessBuilder.start() 返回 Process 实例
**断言方式**:L1 Unit mock `ProcessBuilder`(避免真 fork 子进程)+ `verify(processBuilder).command("ls", "-la").directory(...)` 真启动

### AC-NN-5 — `DefaultToolExecutionContext.http()/fs()/approval()` 真接通 RuntimeSandbox

**Given** 完整 Agent 装配(`LinearTurnEngine` + `ToolExecutor` + `RuntimeSandbox` + `PermissionPolicy`) + 1 个 mock `Tool`
**When** 模拟 Tool 调 `ctx.http().get("https://evil.com")`(domainWhitelist 不含)
**Then** 抛 `AccessDeniedException` 含 `[LINGS-S01]`(不再是 `UnsupportedOperationException`)
**When** 模拟 Tool 调 `ctx.fs().getPath("/etc/passwd")`(workingDirectory 不含)
**Then** 抛 `AccessDeniedException` 含 `[LINGS-S01]`
**When** 模拟 Tool 触发 `ctx.approval().ask(new Decision.AskUser("是否执行 rm"))`(PermissionPolicy=Deny)
**Then** 返 `Decision.Deny` 含 `LINGS-S01` ErrorCode 嵌入 message
**断言方式**:L3 黑盒 + L1 Unit,`DefaultToolExecutionContext` 构造器新签名注入真 sandbox 后 3 stub 真接通

### AC-NN-6 — `PassThroughHttp` inner class 17 行已删

**Given** `DefaultToolExecutionContext.java` 源码
**When** grep `PassThroughHttp`
**Then** 0 匹配(原 L118-134 整段已删)
**断言方式**:L1 Unit `Files.readString(Paths.get("DefaultToolExecutionContext.java")).contains("PassThroughHttp")` = false

### AC-NN-7 — `SandboxErrorCodes.LINGS_S01` ErrorCode 嵌入 message 模式

**Given** `ai.lingshu.core.sandbox.SandboxErrorCodes` 常量类
**When** `LINGS_S01 = "LINGS-S01"` + `SandboxErrorCodes.LINGS_S01`
**Then** 4 处 `AccessDeniedException` 抛点(`ChrootedFileSystem.getPath` / `WhitelistedHttpClient.get/post/getStream` / `ChrootRuntimeSandbox.runProcess` + `RuntimeSandbox.approval`)抛出的 exception `getMessage()` 都含 `[LINGS-S01]` 前缀
**断言方式**:L1 Unit 4 抛点各 1 case 验证 `assertThatThrownBy(...).isInstanceOf(AccessDeniedException.class).hasMessageContaining("LINGS-S01")`

### AC-NN-8 — R-13 mitigation (d) 依赖零增量

**Given** Story #028 引入 `RuntimeSandbox` + `AccessDeniedException` + `ProcessRunner` + `ChrootRuntimeSandbox` + `ChrootedFileSystem` + `WhitelistedHttpClient` + `RuntimeSandboxProvider` + `RuntimeSandboxRouter` + `RuntimeSandboxAutoConfiguration` + `DefaultToolExecutionContext` 改造 + `SandboxErrorCodes`
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#025 follow-up` post-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;`banned-dependencies` enforcer 不 fail
**断言方式**:对照 `specs/025-follow-up-demo-product-sandbox-wiring/` PR body 末尾的 `### R-13 dependency:tree 自查` 节(diff 只允许 timestamp + 时间戳差异)
**预期 R-13 第 14 次 PASS 0 binary delta**

### AC-NN-9 — 全 587 测试 0 回归

**Given** Story #028 改造 `DefaultToolExecutionContext` 构造器签名 + 删 `PassThroughHttp` inner class
**When** 跑 `mvn -pl lingshu-core test`
**Then** 587 pre test + #028 新 case 全过(0 fail / 0 error / 0 skipped);`LinearTurnEngine.dispatchWithPolicy` 调用方更新 0 回归
**断言方式**:`mvn -pl lingshu-core test` 全模块无 fail

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| 真 OS-level chroot(`ProcessBuilder` 真 `chroot(2)` syscall)| #028 JVM 内 chroot(只 FS 路径前缀校验 + cmd/domain whitelist),与 dsh §6.3 L3917-3953 模板对齐;真 OS-level chroot 留 v2 + 替代 RuntimeSandbox 实现 |
| Docker / Landlock / gVisor / Firecracker 替代 RuntimeSandbox 实现 | #028 只落 `chroot` 默认实现,其他实现留 OQ-Future;但 `RuntimeSandboxProvider` SPI 已就位,plugin 可后续加 |
| `Policy.check()` 5 步流水线其他 4 步(权限 → registry lookup → timeout → execute → checkpoint)的"sandbox"步当前空跑部分 | #028 只填 sandbox 步空跑 → 真实现;其他 4 步流水线由 #004 已落(sandbox 步是 §4.7 第 4 步,§4.10.1 硬规则 2 流水线其余 4 步 0 改动) |
| `ToolExecutionContext` interface 加 `process()` 公开方法 | dsh §6.3 模板里 `RuntimeSandbox.process()` 返回 `ProcessRunner` 是 RuntimeSandbox 自己的 method,不是 ToolExecutionContext 的 method;Tool 通过 `ctx.process()` 还是 `sandbox.process()` 调,**不**作 ToolExecutionContext 公开接口扩展,避免破坏 6 方法契约 |
| 真 streaming HTTP retry / circuit breaker / graceful shutdown 套 sandbox | OQ-Future,留 §14.2 RetryPolicy + §14.3 CircuitBreaker + §14.6 graceful shutdown 后续 Story |
| 删 §6.3 L3901-3976 模板代码块 | #028 是实现 §6.3 模板,不删设计文档 |
| Sandbox policy = "strict" / "permissive" 多档 | #028 只落 default policy(`StrictPermissionPolicyProvider`);多档 policy OQ-Future |
| 多租户 sandbox 路由(§14.9 TenantContext ThreadLocal + sandbox 白名单分) | #028 让 sandbox 5 字段生效,**不**做 tenant 路由(tenant 路由已在 #006 多租户实现 §14.9,sandbox 字段本 Story 合入后 §14.9 sandbox 部分自然生效) |
| `SandboxProps` 单独 POJO(替代 AgentConfig.Sandbox 5 字段)| #028 复用 `AgentConfig.Sandbox` 现有 `@Value` 5 字段,**不**抽独立 props 类 |
| `LINGS-S02+` ErrorCode | #028 只开 S01;S02+ 留后续 Story |
| `AccessDeniedException` 变 `checked exception` | RuntimeException 子类,**不**变 checked(避免侵入现有 catch 链路 — §4.10.1 5 步流水线 sandbox 步 catch 转 ToolResult.error 不需要 throws 签名) |

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#001` zero-config-bootstrap — `Agent` 4 final 字段 + `DefaultAgent.buildContext` 冻结语义
  - `#003` spi-slot-router — `SlotRouter<P,T>` 父类 + 多 Provider 模式(对齐 v1.5.28 §5.5)+ 隐式 Router `@Autowired` AgentFactory 模式(对齐 §5.3.1.0)
  - `#004` tool-parallel-dispatch — `ToolExecutor.dispatch()` 5 步流水线 + `DefaultToolExecutionContext` 当前 stub 集
  - `#005` cancellation-token — `CancellationToken` 三层贯通,`DefaultToolExecutionContext.cancellation()` 已通(本 Story 不动 cancellation)
  - `#007` yaml-hot-reload — `AgentConfigRegistry` AtomicReference + hot-reload 期间 cfg 冻结(本 Story 让 sandbox 5 字段 hot-reload 真生效)
  - **`#025 follow-up` demo-product-sandbox-wiring** — `DemoProductApplication.readSandbox(env)` 接通 YAML 5 字段 + `mergeConfig()` 签名 +1 AgentConfig.Sandbox + `agentConfig(env)` @Bean 真吃 YAML —— **强依赖**,本 Story 让 sandbox 5 字段**真生效**
- **后续 Story(本 Story 是其前置)**:
  - `#016` audit-log(§14.10 N10)—— sandbox deny 事件(`LINGS-S01`)接入审计流(已在 ROADMAP §14 N10 cross-ref 登记)
  - §14.2 RetryPolicy + §14.3 CircuitBreaker — sandbox deny 后 retry / circuit breaker 触发
  - §14.6 graceful shutdown — sandbox 真生效后,shutdown 钩子释放 sandbox 资源(chrooted FS close 等)
  - §14.9 TenantContext ThreadLocal 多租户 sandbox 路由 — sandbox 字段 Tenant 隔离细粒度增强(AC-05 sandbox 部分真生效)

---

**Spec writer**: Claude Code
**Spec date**: 2026-09-30
**Spec version**: v0.1 Draft