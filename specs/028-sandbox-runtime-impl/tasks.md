# Tasks: Story #028 `sandbox-runtime-impl`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 11 核心文件新增 + 2 modify + 1 ErrorCode + 5 测试文件 ≈ 19 文件,但严格按 P1—P6 拆分 commit,每个 commit 1-3 文件)
>
> **实施顺序严格按 plan §3**:`AccessDeniedException` 基础异常 → `ProcessRunner` + `NetworkClient` 2 接口 → `ChrootedFileSystem` / `WhitelistedHttpClient` / `ChrootRuntimeSandbox` 3 实现 → `RuntimeSandbox` interface → `SandboxErrorCodes` → `RuntimeSandboxProvider` SPI → `RuntimeSandboxRouter` 隐式 Router → `RuntimeSandboxAutoConfiguration` → `DefaultToolExecutionContext` 改造 → `AgentFactory` 7-Router ctor → 测试 fixture → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#028` 复用 Jackson + Lombok + Spring 已锁 + `ProcessBuilder` / `HashSet` / `FileSystem` / `HashMap` / `BufferedReader` / `HttpURLConnection` 全 JDK 8 built-in 0 新二进制,需验证 0 binary delta 第 14 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(sandbox): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(11 新增 + 2 modify)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/AccessDeniedException.java` 新增不可变异常类 —— `public final class AccessDeniedException extends RuntimeException` + Lombok `@Value`(字段 final:`String message` + `Throwable cause`)+ 2 构造器:`AccessDeniedException(String reason)`(自动加 `[LINGS-S01]` 前缀 → `super("[LINGS-S01] " + reason)`)+ `AccessDeniedException(String reason, Throwable cause)`(同上 + cause)+ 类级 Javadoc 引用 §15.5 ErrorCode 域 S 段 1 号 + §6.3 ChrootRuntimeSandbox(预估 15min)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ProcessRunner.java` 新增 functional interface —— `public interface ProcessRunner` + 单方法 `Process run(String cmd, List<String> args, Path cwd) throws AccessDeniedException, IOException` + Javadoc 覆盖 (1) cmdWhitelist 校验语义 + (2) cwd resolveAgainstRoot 要求 + (3) ProcessBuilder 委托(预估 15min)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/NetworkClient.java` 新增 interface —— `public interface NetworkClient` + 3 方法 `String get(String url) throws AccessDeniedException, IOException` + `String post(String url, String body) throws AccessDeniedException, IOException` + `InputStream getStream(String url) throws AccessDeniedException, IOException` + Javadoc 覆盖 (1) domainWhitelist 校验 + (2) JDK HttpURLConnection 委托(预估 15min)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ChrootedFileSystem.java` 新增 FS 实现 —— `public final class ChrootedFileSystem extends FileSystem` + 字段 `private final FileSystem delegate` + `private final Path rootDir` + `public Path getPath(String first, String... more)` 强制 `delegate.getPath(first, more).toAbsolutePath().normalize()` 后必须 `startsWith(rootDir)`,**否则抛 `AccessDeniedException("Path escapes working dir: " + full)`**;其他 SPI 方法(`provider()` / `readAttributes()` / `newByteChannel()` / etc.)delegate 给 `delegate`(预估 90min,spec §4 AC-NN-2)
- [ ] **T05** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/WhitelistedHttpClient.java` 新增 HTTP 客户端 —— `public final class WhitelistedHttpClient implements NetworkClient` + 字段 `private final Set<String> domainWhitelist`(构造器注入,`new HashSet<>(domainWhitelist)`)+ 3 方法:`get/post` 拆 URL → `URI.create(url).getHost()` → `domainWhitelist.contains(host)` 否则抛 `AccessDeniedException("Domain not whitelisted: " + host)` → 然后 `HttpURLConnection` 发请求;`getStream` 返 `conn.getInputStream()`(预估 90min,spec §4 AC-NN-3)
- [ ] **T06** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ChrootRuntimeSandbox.java` 新增默认实现 —— `public final class ChrootRuntimeSandbox implements RuntimeSandbox` + 注解 `@Component` + `@ConditionalOnProperty(name="agent.sandbox.runtime", havingValue="chroot", matchIfMissing=true)` + 构造器 `ChrootRuntimeSandbox(AgentConfig.Sandbox sandbox, PermissionPolicy permissionPolicy)` 读 5 字段 + 建 `chrootedFs` + `httpClient` + `processRunner`(inner class `ChrootProcessRunner implements ProcessRunner`)+ 4 方法实现:`fs()` 返 `chrootedFs`;`http()` 返 `httpClient`;`process()` 返 `processRunner`;`approval(PermissionPolicy policy, Decision.AskUser ask)` 调 `policy.check(ask)` 返结果(预估 90min,spec §4 AC-NN-4)
- [ ] **T07** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandbox.java` 新增 interface —— `public interface RuntimeSandbox` + 4 方法:`FileSystem fs()` + `NetworkClient http()` + `ProcessRunner process()` + `Decision approval(PermissionPolicy policy, Decision.AskUser ask)` + Javadoc 覆盖 (1) §4.10.1 硬规则 2 流水线串联 + (2) 4 方法契约 + (3) Spring 注入语义(`@Component` + `@ConditionalOnProperty`)(预估 30min)
- [ ] **T08** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/SandboxErrorCodes.java` 新增常量类 —— `public final class SandboxErrorCodes` + `public static final String LINGS_S01 = "LINGS-S01";` + 行内注释 `// SANDBOX_ACCESS_DENIED — see dsh §15.5 S 段 1 号 + §6.3 ChrootRuntimeSandbox,2026-09-30 #028 spec 锁定`(预估 5min)
- [ ] **T09** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxProvider.java` 新增 SPI interface —— `public interface RuntimeSandboxProvider` + 3 方法 `String name()` + `int priority()` + `RuntimeSandbox create(AgentConfig.Sandbox sandbox, PermissionPolicy permissionPolicy)` + Javadoc 对齐 §5.5 多 Provider 模式 + 唯一 name() 约束(预估 15min)
- [ ] **T10** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxRouter.java` 新增隐式 Router —— `public class RuntimeSandboxRouter extends SlotRouter<RuntimeSandboxProvider, RuntimeSandbox>` + 构造器 `public RuntimeSandboxRouter(List<RuntimeSandboxProvider> providers)` + super 传 `"RuntimeSandbox"` + Logger;Javadoc 说明**不在 SlotResolver 字段里,由 AgentFactory 直接 `@Autowired`**;无 override,直接复用 SlotRouter 默认 `resolve(name, cfg)`(预估 20min)
- [ ] **T11** `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxAutoConfiguration.java` 新增 AutoConfiguration —— `public class RuntimeSandboxAutoConfiguration` + 注解 `@AutoConfiguration` + `@Bean(name="runtimeSandboxProvider_chroot-1.0.0") public RuntimeSandboxProvider runtimeSandboxProviderChroot()` 返匿名 inner class(name="chroot" + priority=10 + create 返 `new ChrootRuntimeSandbox(sandbox, permissionPolicy)`);对齐 §5.5 多 Provider 模式(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)(预估 15min)
- [ ] **T12** `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java` modify —— (1) 删 `PassThroughHttp` inner class L118-134 17 行整段;(2) 构造器签名改 `DefaultToolExecutionContext(TurnContext turnCtx, RuntimeSandbox runtimeSandbox)` + 字段 `private final RuntimeSandbox runtimeSandbox;`;(3) `fs()` 改 `return runtimeSandbox.fs()`;(4) `http()` 改 `return runtimeSandbox.http()`;(5) `approval(...)` 改 `return runtimeSandbox.approval(permissionPolicy, ask)`;(6) 删 import `java.nio.file.FileSystems`(不再用 `FileSystems.getDefault()`);(7) 加 3 import `ai.lingshu.core.sandbox.RuntimeSandbox` + `PermissionPolicy` + `Decision`(预估 30min,spec §4 AC-NN-5 + AC-NN-6 + AC-NN-7)
- [ ] **T13** `lingshu-core/src/main/java/ai/lingshu/core/factory/AgentFactory.java` modify —— (1) 6-Router ctor 改 7-Router ctor:加 `RuntimeSandboxRouter runtimeSandboxRouter` 字段(L3676 附近)+ `@Autowired` ctor 参数列表加 1 项;(2) `create()` 内 7 项校验新增:`RuntimeSandbox runtimeSandbox = runtimeSandboxRouter.resolve(cfg.getSandbox().getRuntime(), cfg)`;(3) `buildContext(ctx)` 调新构造器 `new DefaultToolExecutionContext(turnCtx, runtimeSandbox)`;(4) 加 import `ai.lingshu.core.sandbox.RuntimeSandbox` + `RuntimeSandboxRouter`(预估 30min,spec §4 AC-NN-9 0 回归 + AC-NN-5 7-Router ctor)

> **P1 总耗时**:~465 min(~7.75h)

---

## P2:测试(1 fixture + 5 测试文件 + 17 case)

- [ ] **T14** `lingshu-core/src/test/java/ai/lingshu/core/sandbox/SandboxTestSupport.java` 新增测试 fixture —— `public class SandboxTestSupport` 静态方法 `mockHttpServer(int port, ResponseCode, String body)`(起 `com.sun.net.httpserver.HttpServer` + 后台 `ExecutorService` 线程跑 `HttpHandler` 返 mock response)+ `findFreePort()` helper(`new ServerSocket(0).getLocalPort()`)+ `stopServer(HttpServer)` helper + `mockProcessBuilder()`(Mockito 静态 mock ProcessBuilder 返回 mock Process)(预估 30min)
- [ ] **T15** `lingshu-core/src/test/java/ai/lingshu/core/sandbox/AccessDeniedExceptionTest.java` 新增 L1 Unit —— 2 case(`@Value` 自动 `[LINGS-S01]` 前缀验证 — `new AccessDeniedException("Path escapes")` → `getMessage() == "[LINGS-S01] Path escapes"` + `cause` ctor 验证 — `new AccessDeniedException("Domain not whitelisted", ioEx)` → `getCause() == ioEx`)(预估 15min)
- [ ] **T16** `lingshu-core/src/test/java/ai/lingshu/core/sandbox/ChrootedFileSystemTest.java` 新增 L1 Unit —— 3 case(AC-NN-2 `getPath("/etc/passwd")` 越权抛 `AccessDeniedException` 含 `[LINGS-S01]` + `"Path escapes working dir"` + 合法路径 `getPath("/work/file.txt")` 通过返 `Path` + prefix 边界 `getPath(rootDir 本身)` 通过)(预估 60min,涵盖 spec §4 AC-NN-2)
- [ ] **T17** `lingshu-core/src/test/java/ai/lingshu/core/sandbox/WhitelistedHttpClientTest.java` 新增 L1 Unit —— 4 case(AC-NN-3 `get("https://evil.com/x")` 越权抛 `AccessDeniedException` 含 `[LINGS-S01]` + `"Domain not whitelisted: evil.com"` + 白名单内 `get("https://api.openai.com/v1/models")` 真发 mock HTTP server 200 + `getStream` 返 `InputStream` 验证 + host 解析 `URI.create` 验证)(预估 90min,涵盖 spec §4 AC-NN-3)
- [ ] **T18** `lingshu-core/src/test/java/ai/lingshu/core/sandbox/ChrootRuntimeSandboxTest.java` 新增 L1 Unit —— 4 case(AC-NN-4 `process().run("rm", List.of("-rf", "/"), Path.of("/tmp"))` 越权抛 `AccessDeniedException` 含 `[LINGS-S01]` + `"Command not whitelisted: rm"` + 白名单内 `process().run("ls", List.of("-la"), Path.of("/tmp"))` mock ProcessBuilder 真启动 + `fs()` / `http()` / `process()` / `approval()` 4 接口委派接通验证 + `@ConditionalOnProperty` `havingValue="chroot", matchIfMissing=true` 验证)(预估 120min,涵盖 spec §4 AC-NN-4)
- [ ] **T19** `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContextSandboxIT.java` 新增 L3 黑盒 —— 4 case(AC-NN-5 完整 Agent 装配(`AnnotationConfigApplicationContext` 7-Router AgentFactory + LinearTurnEngine + ToolExecutor + RuntimeSandbox + PermissionPolicy + mock Tool)真接通 sandbox 3 stub — Tool 调 `ctx.http().get("https://evil.com")` 抛 `AccessDeniedException` 含 `[LINGS-S01]` + Tool 调 `ctx.fs().getPath("/etc/passwd")` 抛 + Tool 触发 `ctx.approval().ask(new Decision.AskUser("是否执行 rm"))` 返 `Decision.Deny` 含 LINGS-S01 嵌入 message + AC-NN-6 `Files.readString(Paths.get("DefaultToolExecutionContext.java")).contains("PassThroughHttp")` = false + AC-NN-7 4 抛点 stub 委派含 `[LINGS-S01]` 验证 — 复用 #004 mock Tool + AC-NN-9 0 回归 587 pre test 全过)(预估 180min,涵盖 spec §4 AC-NN-5 + AC-NN-6 + AC-NN-7 + AC-NN-9)

> **P2 总耗时**:~495 min(~8.25h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=RuntimeSandbox*ContractTest` 验证 `RuntimeSandbox` interface 4 方法契约 + Javadoc 非空(reflection 验证)(预估 15min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=ChrootedFileSystemTest#getPathEscapesWorkingDirThrowsL01` 验证 `getPath("/etc/passwd")` 越权抛 `AccessDeniedException` 含 `[LINGS-S01]` + `"Path escapes working dir"`(预估 15min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=WhitelistedHttpClientTest#getEvilDomainThrowsL01` + `WhitelistedHttpClientTest#getWhitelistedDomainRealFires` 验证 `get("https://evil.com")` 越权抛 `AccessDeniedException` + 白名单内真发 HTTP(预估 20min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=ChrootRuntimeSandboxTest#processRunUnwhitelistedCmdThrowsL01` 验证 `runProcess("rm", ...)` 越权抛 `AccessDeniedException` + 白名单内 mock ProcessBuilder 真启动(预估 20min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=DefaultToolExecutionContextSandboxIT` 验证完整 Agent 装配真接通 sandbox 3 stub(预估 30min)
- [ ] **T-validate-AC-NN-6** 跑 `grep -r "PassThroughHttp" lingshu-core/src/main/java/ || echo PASS_DELETED` 验证 `PassThroughHttp` inner class 0 匹配(预估 5min)
- [ ] **T-validate-AC-NN-7** 跑 `mvn -pl lingshu-core test -Dtest=DefaultToolExecutionContextSandboxIT#fourStubDelegationContainsL01` 验证 4 抛点 stub 委派含 `[LINGS-S01]`(预估 15min)
- [ ] **T-validate-AC-NN-8** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose` + diff 对比 `#025 follow-up` post-commit baseline 镜像,验证 0 新 Maven 坐标(R-13 第 14 次)(预估 15min)
- [ ] **T-validate-AC-NN-9** 跑 `mvn -pl lingshu-core test` 全模块无 fail,新增 17 case 全过,587 pre test 0 回归(预估 30min)

> **P3 总耗时**:~165 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-028-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-028-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-025-follow-up-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-028-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min
> **PR body 末尾必有 `### R-13 dependency:tree 自查` 节**,贴 T-dep-tree-2 输出 + (name, version, slot) 三元组表

---

## P5:文档同步(提交完成闭环)

- [ ] **T-doc-1** `README.md` 顶部加 `#028` 1 段(Sandbox runtime 真实现,`ChrootRuntimeSandbox` + `ChrootedFileSystem` + `WhitelistedHttpClient` 落地 + `LINGS-S01 SANDBOX_ACCESS_DENIED` 启用 + §6.3 ChrootRuntimeSandbox 模板真接通)(预估 15min)
- [ ] **T-doc-2** `specs/028-sandbox-runtime-impl/quickstart.md` 起草 Alice 30min 教程(对齐 `#027a` / `#027b` 模板:Hello world sandbox 配置 + 越权 Tool 调 真拒绝验证)(预估 30min)
- [ ] **T-doc-3** `specs/028-sandbox-runtime-impl/data-model.md` 起草(9 核心类型对照表 + `LINGS-S01` ErrorCode 域表 + sandbox 5 字段 × 4 抛点映射表)(预估 30min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.46 → v1.5.47` 行(本 Story 实施记录)(预估 5min)
- [ ] **T-doc-5** `dsh_agent_design.md` §15.4 ErrorCode 域字母表加 `S = Sandbox 域 LINGS-S01` 行(新域段启用,域字母 9 → 10)+ §15.5 S 段序号表 1 号 `LINGS-S01 SANDBOX_ACCESS_DENIED`(预估 5min)
- [ ] **T-doc-6** `constitution.md` §4 域字母表加 `S = Sandbox` 行 + `LINGS-S01 SANDBOX_ACCESS_DENIED` + §10 R-13 风险登记:`Story #028` 标记「已缓解」+ 第 14 次 0 binary delta 验证结果(预估 10min)
- [ ] **T-doc-7** `ROADMAP.md` 段一 ✅ 已完成表加 `#028` 行(预估 2min)
- [ ] **T-doc-8** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.46` → `v1.5.47`)(预估 2min)
- [ ] **T-doc-9**(可选)`lingshu-docs` 仓 `docs/concepts/sandbox-runtime.md` 起草 `#028` 段落(Story 推 master 后开)(预估 60min)

> **P5 总耗时**:~160 min

---

## P6:PR + 合入(闭环)

- [ ] **T-PR-1** `git add -A && git commit -m "feat(sandbox): Story #028 sandbox-runtime-impl — ChrootRuntimeSandbox + ChrootedFileSystem + WhitelistedHttpClient + LINGS-S01 SANDBOX_ACCESS_DENIED"`(Co-Authored-By Claude 标记)(预估 5min)
- [ ] **T-PR-2** `gh pr create --base main --head story-028-sandbox-runtime-impl --title "feat(sandbox): Story #028 sandbox-runtime-impl — ChrootRuntimeSandbox + ChrootedFileSystem + WhitelistedHttpClient + LINGS-S01 SANDBOX_ACCESS_DENIED" --body "$(cat /tmp/pr-body-028.md)"`(PR body 模板贴 spec.md + plan.md + tasks.md 摘要 + AC 验证输出 + R-13 dep-tree 自查)(预估 10min)
- [ ] **T-PR-3** 等 CI 绿 + review approve,`gh pr merge --squash --auto`(预估 5min)
- [ ] **T-PR-4** 合入后 `git pull` + 触发 docs 同步任务 T-doc-1—T-doc-9(预估 160min)

---

## 总耗时估算

| 阶段 | 时间 | 说明 |
|---|---|---|
| P1(实现)| ~465min | 11 新增 + 2 modify |
| P2(测试)| ~495min | 1 fixture + 5 测试文件 + 17 case |
| P3(AC 验证)| ~165min | 9 验证跑 |
| P4(dep-tree)| ~50min | R-13 强制 |
| P5(文档)| ~160min | 9 同步项 |
| P6(PR)| ~180min | commit + PR + merge + docs |
| **合计** | **~25 h** | 与 `#027a`(29h)/ `#027b`(29h)/ `#022`(32h) 同量级 |

---

## 强制不变项检查清单(PR review 时必勾)

- [ ] `Tool` interface **0 改动**(`git diff lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java` 应为空)
- [ ] `ToolRegistry` interface **0 改动**(`#028` 不引新方法)
- [ ] `ToolExecutor.dispatch()` 5 步流水线 **0 改动**(只是"sandbox"步从空跑变真实现)
- [ ] `ToolExecutionContext` interface 6 方法契约 **0 改动**(只 default 实现真接通)
- [ ] `PermissionPolicy` interface / `Decision` 4 子类 **0 改动**
- [ ] `Message` 4 子类(`System` / `User` / `Assistant` / `ToolResult`,§0.5.46 refactor 后)**0 改动**
- [ ] `Prompt.tools` 契约 **0 改动**
- [ ] `LlmResponse` 5 字段契约 **0 改动**
- [ ] `AgentEvent` 12 子类契约 **0 改动**
- [ ] `LinearTurnEngine` ReAct 主循环结构 **0 改动**(只 `dispatchWithPolicy` 调用方 1 行 wire-through 修复)
- [ ] `AnthropicLlmProvider` 6-arg ctor + `buildRequestBody` + `parseResponse` fallback **0 改动**(接续 #027a / #027b 已落契约)
- [ ] `AgentConfig` 24 字段 schema **0 改动**(Sandbox 5 字段已在 #025 follow-up 落地)
- [ ] 9 Slot 顶层体系 **0 改动**(Slot 3 Sandbox 走**隐式 Router**,RuntimeSandboxRouter 由 AgentFactory 直接 `@Autowired`,不在 SlotResolver 字段里)
- [ ] `AccessDeniedException extends RuntimeException` **checked 不变**(避免侵入现有 catch 链路)
- [ ] dsh §15.4 域字母表新增 `S = Sandbox` 域段(域字母 9 → 10)
- [ ] `mvn -pl lingshu-core dependency:tree` 0 新 Maven 坐标(banned list 强制)
- [ ] `mvn -pl lingshu-core verify` enforcer **不 fail**(跑 `banned-dependencies`)
- [ ] 17 test case 全过,`grep "BUILD FAIL" /tmp/mvn-test.log || echo PASS`
- [ ] PR body 末尾有 `### R-13 dependency:tree 自查` 节(T-dep-tree-2 输出贴上)
- [ ] dsh §13 changelog + constitution §4 + §10 R-13 缓解 + ROADMAP 段一已合表 四件套同步
- [ ] **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),sandbox 真实现通过 `DefaultToolExecutionContext` 触发,**不走 ChatClient 自动执行**

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-09-30
**Tasks version**: v0.1 Draft
**Story #028 slug**: `sandbox-runtime-impl`
**对应 spec**: `specs/028-sandbox-runtime-impl/spec.md`
**对应 plan**: `specs/028-sandbox-runtime-impl/plan.md`