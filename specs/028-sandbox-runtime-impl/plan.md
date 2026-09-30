# Plan: Story #028 `sandbox-runtime-impl`

> **Spec anchors**: specs/028-sandbox-runtime-impl/spec.md
> **Design anchors**: dsh v1.5.46 §6.3 ChrootRuntimeSandbox(L3901-3976 模板)+ §4.7 PermissionPolicy + §4.10.1 硬规则 2(ToolExecutor 5 步流水线 sandbox 步)+ §15.5 ErrorCode 域 + §5.3.1.0 隐式 Router 样板(Slot 3 Sandbox 走隐式 Router 模式)+ §5.5 多 Provider 模式(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`,v1.5.28)+ §13 changelog v1.5.46 行 + constitution v1.0 + ROADMAP §6 主链漏项补救(2026-09-30 加 #028 行)
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `Set.of` / `record` / `sealed`(constitution §1 第 1 项 + §6 兼容性矩阵);本 Story `WhitelistedHttpClient` / `ChrootRuntimeSandbox` 用 `new HashSet<>()` + `Arrays.asList(...)` 替代 `Set.of(...)` / `List.of(...)`
- **Lombok `@Value` 不可变优先** — `AccessDeniedException`(`reason` + `cause` final)/ `ProcessRunner` functional interface 单方法 / `RuntimeSandboxProvider` interface 3 方法;`ChrootRuntimeSandbox` / `ChrootedFileSystem` / `WhitelistedHttpClient` 因持有可变状态(workingDirectory / cmdWhitelist 等)用 `@Component` 不用 `@Value`
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — 本期 **1 新抛 ErrorCode** `LINGS-S01 SANDBOX_ACCESS_DENIED`(S = Sandbox 域 1 号,**新 ErrorCode 域启用**,对齐 `#023 LINGS-D01` + `#022 LINGS-T08` + `#027b LINGS-L03` reserved precedent);错误码嵌入 `AccessDeniedException` message 模式 `"[LINGS-S01] " + reason`
- **性能预算 §14.15.1 不退化** — sandbox 真实现开销 ≤ 1ms/tool-call(prefix 校验 + Set.contains + HashMap lookup);turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化
- **`ToolExecutor.dispatch()` 5 步流水线不变** — 本 Story 只把"sandbox"步从空跑变真实现(§4.10.1 硬规则 2 第 4 步);`PermissionPolicy.check()` §4.7 → `ToolRegistry.lookup(name)` → `TimeoutWrap` → **`SandboxApply(fs / http / process)` 本 Story 真实现** → `tool.execute()` → `Checkpoint` 5 步流水线其余 4 步 0 改动
- **0 新 Maven 依赖** — `ProcessBuilder` + `HashSet` + `FileSystem` + `HashMap` + `BufferedReader` + `HttpURLConnection` 全 JDK 8 standard + Jackson 已锁 + `List.of` / `Set.of` 禁用(`new HashSet<>` + `Collections.unmodifiableSet`);**`com.sun.net.httpserver.HttpServer`** 测试 fixture 已用(`#027a`/`#027b` precedent)
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock `HttpServer`**(`#027a`/`#027b`/`#021c`/`#022`/`#023` 模式)— 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue)
- **`AccessDeniedException extends RuntimeException`**(checked 不变)— 不破坏现有 catch 链路(§4.10.1 5 步流水线 sandbox 步 catch 转 `ToolResult.error` 不需要 throws 签名)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),sandbox 真实现通过 `DefaultToolExecutionContext` 触发,**不走 ChatClient 自动执行**

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 接口 / 异常类 | 路径 | 角色 |
|---|---|---|
| `RuntimeSandbox` interface | `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandbox.java` | Slot 3 Sandbox 隐式 Router SPI:`FileSystem fs()` / `NetworkClient http()` / `ProcessRunner process()` / `Decision approval(PermissionPolicy, Decision.AskUser)`;Javadoc 覆盖 (1) §4.10.1 硬规则 2 流水线串联 + (2) 4 方法契约 + (3) Spring 注入语义(`@Component` + `@ConditionalOnProperty(name="agent.sandbox.runtime", havingValue="chroot", matchIfMissing=true)`)|
| `AccessDeniedException`(`@Value` 不可变)| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/AccessDeniedException.java` | `extends RuntimeException`,2 构造器:`AccessDeniedException(String reason)` + `AccessDeniedException(String reason, Throwable cause)`;reason 默认前缀 `"[LINGS-S01] " + reason`(调用方传纯 reason,构造器自动加前缀,避免 4 抛点重复写前缀)|
| `ProcessRunner` functional interface | `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ProcessRunner.java` | 单方法 `Process run(String cmd, List<String> args, Path cwd) throws AccessDeniedException, IOException`;Javadoc 覆盖 (1) cmdWhitelist 校验语义 + (2) cwd resolveAgainstRoot 要求 + (3) ProcessBuilder 委托 |
| `NetworkClient` interface | `lingshu-core/src/main/java/ai/lingshu/core/sandbox/NetworkClient.java` | `String get(String url)` + `String post(String url, String body)` + `InputStream getStream(String url)` 3 方法;**throws** `AccessDeniedException`(domainWhitelist 不含)+ `IOException`(网络层)|
| `ChrootRuntimeSandbox implements RuntimeSandbox`(`@Component` + `@ConditionalOnProperty`)| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ChrootRuntimeSandbox.java` | 默认 chroot 实现;构造器 `ChrootRuntimeSandbox(AgentConfig.Sandbox sandbox, PermissionPolicy permissionPolicy)`;4 方法实现:`fs()` 返 `new ChrootedFileSystem(FileSystems.getDefault(), sandbox.getWorkingDirectory())`;`http()` 返 `new WhitelistedHttpClient(sandbox.getDomainWhitelist())`;`process()` 返 `new ChrootProcessRunner(sandbox.getCommandWhitelist(), fs)`(inner class);`approval(...)` 调 `permissionPolicy.check(ask)` 返结果 |
| `ChrootedFileSystem extends FileSystem`(`final class`)| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ChrootedFileSystem.java` | JDK FileSystem SPI 实现;`getPath(first, more)` 强制 `delegate.getPath(first, more).toAbsolutePath().normalize()` 后必须以 `rootDir` 前缀打头,**否则抛 `AccessDeniedException`**;其他 FileSystem SPI 方法(`provider()` / `readAttributes()` / `newByteChannel()` / etc.)委托给 delegate(`FileSystems.getDefault()`)|
| `WhitelistedHttpClient implements NetworkClient`(`final class`)| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/WhitelistedHttpClient.java` | domainWhitelist 校验 HTTP 客户端;`get/post` 拆 URL → `URI.create(url).getHost()` → `domainWhitelist.contains(host)` 否则抛 `AccessDeniedException("Domain not whitelisted: " + host)`;内部 JDK `HttpURLConnection` 发请求(R-13 0 binary delta);`getStream` 返 `conn.getInputStream()` |
| `RuntimeSandboxProvider` interface | `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxProvider.java` | Slot 3 Provider SPI:`String name()` + `int priority()` + `RuntimeSandbox create(AgentConfig.Sandbox sandbox, PermissionPolicy permissionPolicy)`;对齐 §5.5 多 Provider 模式 |
| `RuntimeSandboxRouter extends SlotRouter<RuntimeSandboxProvider, RuntimeSandbox>` | `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxRouter.java` | Slot 3 隐式 Router,对齐 §5.3.1.0 隐式 Router 样板;**不**在 SlotResolver 字段里,由 `AgentFactory` 直接 `@Autowired`;super 传 `"RuntimeSandbox"` + Logger;`resolve(String name, AgentConfig cfg)` 走 `SlotRouter` 默认 `resolve(name, cfg)` 父类逻辑 |
| `RuntimeSandboxAutoConfiguration`(`@AutoConfiguration`)| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxAutoConfiguration.java` | Slot 3 默认 Provider 注册;`@Bean(name="runtimeSandboxProvider_chroot-1.0.0") public RuntimeSandboxProvider runtimeSandboxProviderChroot()` 返匿名 inner class(name="chroot" + priority=10 + create 返 `new ChrootRuntimeSandbox(sandbox, permissionPolicy)`);对齐 §5.5 多 Provider 模式(plain `@Bean(name=...)` 不带 `@ConditionalOnMissingBean`)|
| `SandboxErrorCodes` 常量类 | `lingshu-core/src/main/java/ai/lingshu/core/sandbox/SandboxErrorCodes.java` | `public static final String LINGS_S01 = "LINGS-S01";` + 行内注释引用 §15.5 ErrorCode 域 S 段 1 号 + §6.3 ChrootRuntimeSandbox |

### 修改

| 接口 / 类 | 修改 |
|---|---|
| `DefaultToolExecutionContext` | (1) 构造器签名 `DefaultToolExecutionContext(TurnContext turnCtx, RuntimeSandbox runtimeSandbox)` +1 字段 `private final RuntimeSandbox runtimeSandbox;`;(2) `fs()` 改 `return runtimeSandbox.fs()` 替换 L61 `FileSystems.getDefault()`;(3) `http()` 改 `return runtimeSandbox.http()` 替换 L64 `new PassThroughHttp()`;(4) `approval(...)` 改 `return runtimeSandbox.approval(permissionPolicy, ask)` 替换 L70-76 短 deny stub;(5) **删** `PassThroughHttp` inner class L118-134 17 行整段;(7) 加 `import ai.lingshu.core.sandbox.RuntimeSandbox` + `PermissionPolicy` + `Decision` |
| `AgentFactory` | 6-Router ctor 改 7-Router ctor:加 `RuntimeSandboxRouter runtimeSandboxRouter` 字段(L3676 附近)+ `@Autowired` ctor 参数列表加 1 项;`create()` 内 7 项校验新增 `RuntimeSandbox runtimeSandbox = runtimeSandboxRouter.resolve(cfg.getSandbox().getRuntime(), cfg)`;`DefaultAgent.buildContext(ctx)` 调 `new DefaultToolExecutionContext(turnCtx, runtimeSandbox)` |

**关键约束**:
- `#028` **不修改** `Tool` / `ToolRegistry` / `ToolExecutor` 接口契约 — **全部 0 改动**(只是 `DefaultToolExecutionContext` 实现接通 RuntimeSandbox)
- `#028` **不修改** `PermissionPolicy` interface / `Decision` 4 子类 / `ToolCallConfig` 4 字段 — **0 改动**
- `#028` **不修改** `ToolExecutionContext` interface 6 方法契约(`workingDirectory()` / `fs()` / `http()` / `approval()` / `cancellation()` / `callConfig()`)— **0 改动**
- `#028` **不修改** `Agent` 4 final 字段(T1→T4 不变)
- `#028` **不修改** `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类(§0.5.46 refactor 后)/ `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- `#028` **不修改** 9 Slot 顶层体系(Slot 3 Sandbox 走**隐式 Router** 模式,不入顶层 Slot)
- `#028` **不修改** 24 字段 `AgentConfig` schema(Sandbox 5 字段已在 `#025 follow-up` 落地)
- `#028` **不修改** `AccessDeniedException extends RuntimeException`(checked 不变,避免侵入现有 catch 链路)

**新增 + 修改严格遵循 dsh §6.3 ChrootRuntimeSandbox L3901-3976 字面落地**,不引入新接口契约(只新增 `RuntimeSandbox` / `AccessDeniedException` / `ProcessRunner` / `NetworkClient` 4 个新契约 + `RuntimeSandboxProvider` / `RuntimeSandboxRouter` 2 个 SPI)

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandbox.java` | 新增 | ~80(4 方法 interface + Javadoc 覆盖 §4.10.1 硬规则 2 + Spring 注入语义)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/AccessDeniedException.java` | 新增 | ~25(`@Value` 不可变 + 2 构造器 + 自动 `[LINGS-S01]` 前缀)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ProcessRunner.java` | 新增 | ~25(functional interface 单方法 + Javadoc)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/NetworkClient.java` | 新增 | ~50(3 方法 interface + `throws AccessDeniedException, IOException`)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ChrootRuntimeSandbox.java` | 新增 | ~120(`@Component` + `@ConditionalOnProperty` + 4 方法实现 + `ChrootProcessRunner` inner class)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/ChrootedFileSystem.java` | 新增 | ~150(extends FileSystem + `getPath` prefix 校验 + 其他 SPI delegate 给 JDK 默认实现)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/WhitelistedHttpClient.java` | 新增 | ~120(`HttpURLConnection` 发请求 + domainWhitelist 校验 + `get/post/getStream` 3 方法)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxProvider.java` | 新增 | ~40(3 方法 interface + Javadoc 对齐 §5.5)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxRouter.java` | 新增 | ~25(extends `SlotRouter<RuntimeSandboxProvider, RuntimeSandbox>` + super 传 `"RuntimeSandbox"` + Logger)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/RuntimeSandboxAutoConfiguration.java` | 新增 | ~50(`@AutoConfiguration` + `@Bean(name="runtimeSandboxProvider_chroot-1.0.0")` 匿名 inner class)|
| `lingshu-core/src/main/java/ai/lingshu/core/sandbox/SandboxErrorCodes.java` | 新增 | ~15(`LINGS_S01` 常量 + 注释引用 §15.5 S 段 1 号)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java` | modify | 删 `PassThroughHttp` inner class L118-134 17 行 + 构造器 +1 `RuntimeSandbox` 字段 + 3 stub 委派接通 + 3 新 import = ~30 行 modify|
| `lingshu-core/src/main/java/ai/lingshu/core/factory/AgentFactory.java` | modify | +1 `RuntimeSandboxRouter` 字段(L3676 附近)+ 7-Router ctor + `create()` 7 项校验新增 + `buildContext` 调新构造器 = ~15 行 modify|

**11 新增 + 2 modify(必需)**,合计 **13 文件改动**,与 spec §3 WHAT 12 项交付物对齐(11 source + 1 父 ctor + 1 默认 ctx 修改;spec 列 12 项第 12 项「`AgentFactory` 注入 `RuntimeSandboxRouter`」+ 第 11 项「`DefaultToolExecutionContext` 改造」+ 11 source files)

> **R-13 mitigation (d) 强制** — 11 new files + 2 modify,**0 新 Maven 依赖**(Jackson + Lombok + Spring 已锁 + ProcessBuilder / HashSet / FileSystem / HashMap / BufferedReader / HttpURLConnection 全 JDK 8 standard)

---

## 3. 实现顺序

> **原则**:依赖方向 core 内部 `AccessDeniedException` 基础异常 → `ProcessRunner` + `NetworkClient` 2 接口 → `ChrootedFileSystem` / `WhitelistedHttpClient` / `ChrootRuntimeSandbox` 3 实现 → `RuntimeSandboxProvider` SPI → `RuntimeSandboxRouter` 隐式 Router → `RuntimeSandboxAutoConfiguration` 注册 → `SandboxErrorCodes` 常量 → `DefaultToolExecutionContext` 改造 → `AgentFactory` 7-Router ctor → 测试 fixture → 测试 → AC 验证 → 文档同步

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `AccessDeniedException`(`@Value` 不可变)+ 2 构造器 + 自动 `[LINGS-S01]` 前缀 | 无 | 1 文件可编译 |
| 2 | `ProcessRunner` functional interface + `NetworkClient` 3 方法 interface + Javadoc | `AccessDeniedException` | 2 文件可编译 |
| 3 | `ChrootedFileSystem extends FileSystem` + `getPath` prefix 校验 + 其他 SPI delegate 给 JDK 默认实现 | `AccessDeniedException` | 1 文件可编译 |
| 4 | `WhitelistedHttpClient implements NetworkClient` + `HttpURLConnection` 发请求 + domainWhitelist 校验 | `AccessDeniedException` + `NetworkClient` | 1 文件可编译 |
| 5 | `ChrootRuntimeSandbox implements RuntimeSandbox` + `@Component` + `@ConditionalOnProperty` + 4 方法实现 + `ChrootProcessRunner` inner class | `AccessDeniedException` + `ProcessRunner` + `ChrootedFileSystem` + `WhitelistedHttpClient` + `PermissionPolicy` + `Decision` | 1 文件可编译 |
| 6 | `RuntimeSandbox` interface + 4 方法契约 + Javadoc | `FileSystem` + `NetworkClient` + `ProcessRunner` + `PermissionPolicy` + `Decision` | 1 文件可编译 |
| 7 | `SandboxErrorCodes` 常量类 + `LINGS_S01` + 注释 | 无 | 1 文件可编译 |
| 8 | `RuntimeSandboxProvider` interface + 3 方法 + Javadoc 对齐 §5.5 | `RuntimeSandbox` + `AgentConfig.Sandbox` + `PermissionPolicy` | 1 文件可编译 |
| 9 | `RuntimeSandboxRouter extends SlotRouter<RuntimeSandboxProvider, RuntimeSandbox>` + super 传 `"RuntimeSandbox"` + Logger | `RuntimeSandbox` + `RuntimeSandboxProvider` + `SlotRouter<P,T>`(#003 已落) | 1 文件可编译 |
| 10 | `RuntimeSandboxAutoConfiguration`(`@AutoConfiguration`)+ `@Bean(name="runtimeSandboxProvider_chroot-1.0.0")` 匿名 inner class | `RuntimeSandboxProvider` + `ChrootRuntimeSandbox` | 1 文件可编译 |
| 11 | `DefaultToolExecutionContext` modify — 删 `PassThroughHttp` L118-134 17 行 + 构造器 +1 `RuntimeSandbox` 字段 + 3 stub 委派接通 + 3 新 import | `RuntimeSandbox` + `AccessDeniedException` + `PermissionPolicy` + `Decision` | 1 文件 modify |
| 12 | `AgentFactory` modify — 6-Router ctor → 7-Router ctor + `create()` 7 项校验新增 + `buildContext` 调新构造器 | `RuntimeSandboxRouter` + `DefaultToolExecutionContext` | 1 文件 modify |
| 13 | `SandboxTestSupport`(测试 fixture,裸 Mockito 静态方法)| 无 | 1 test fixture |
| 14 | L1 Unit 测试 `AccessDeniedExceptionTest` 2 case(`@Value` 自动加 `[LINGS-S01]` 前缀验证 + `cause` ctor 验证)| `AccessDeniedException` | 1 test 文件 |
| 15 | L1 Unit 测试 `ChrootedFileSystemTest` 3 case(AC-NN-2 prefix 校验真拒绝越权 + 合法路径通过 + prefix 边界 `rootDir` 本身)| `ChrootedFileSystem` | 1 test 文件 |
| 16 | L1 Unit 测试 `WhitelistedHttpClientTest` 4 case(AC-NN-3 domainWhitelist 真拒绝越权 + 白名单内真发 HTTP(用 mock `HttpServer`)+ host 解析 + `getStream` 返回 `InputStream`)| `WhitelistedHttpClient` | 1 test 文件 |
| 17 | L1 Unit 测试 `ChrootRuntimeSandboxTest` 4 case(AC-NN-4 cmdWhitelist 真拒绝越权 + 白名单内真发 ProcessBuilder mock + 4 接口委派接通 + `@ConditionalOnProperty` 验证)| `ChrootRuntimeSandbox` + mock `ProcessBuilder` | 1 test 文件 |
| 18 | L3 黑盒 `DefaultToolExecutionContextSandboxIT` 4 case(AC-NN-5 完整 Agent 装配真接通 sandbox 3 stub + AC-NN-6 `PassThroughHttp` 已删 grep 验证 + AC-NN-7 4 抛点 stub 委派含 `[LINGS-S01]` 验证 + AC-NN-9 0 回归 — 复用 #004 mock Tool)| 全部 | 1 IT 文件 |

**每步独立 commit**(`feat(sandbox): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 `#027a` / `#022` / `#009d` 风格)
**绝对禁止一次性 commit 13+ 文件**(`#028` 必须离散 commit)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 13 | `AccessDeniedExceptionTest` 2 case(`@Value` 自动 `[LINGS-S01]` 前缀 + `cause` ctor) + `ChrootedFileSystemTest` 3 case(AC-NN-2 prefix 校验真拒绝越权 + 合法路径通过 + prefix 边界) + `WhitelistedHttpClientTest` 4 case(AC-NN-3 domainWhitelist 真拒绝 + 白名单内真发 + host 解析 + `getStream` 返回 InputStream) + `ChrootRuntimeSandboxTest` 4 case(AC-NN-4 cmdWhitelist 真拒绝 + 白名单内真发 ProcessBuilder mock + 4 接口委派接通 + `@ConditionalOnProperty` 验证)| 4 test 文件 |
| **L2 Slice** | —(并入 L3)| 无需独立 L2,L3 已覆盖「`DefaultToolExecutionContext` + `RuntimeSandbox` + `ToolExecutor` + mock Tool 多 Bean 协作」 | — |
| **L3 Component** | 4 | `DefaultToolExecutionContextSandboxIT` 4 case(AC-NN-5 完整 Agent 装配真接通 sandbox 3 stub + AC-NN-6 `PassThroughHttp` 已删 grep 验证 + AC-NN-7 4 抛点 stub 委派含 `[LINGS-S01]` 验证 + AC-NN-9 0 回归 — 复用 #004 mock Tool + `AnnotationConfigApplicationContext` 装配 7-Router AgentFactory)| 1 IT 文件 |
| **L4 Contract** | 0(无接口契约变更)| `#028` 新增 `RuntimeSandbox` / `AccessDeniedException` / `ProcessRunner` / `NetworkClient` 4 个**新**契约;`Tool` / `ToolRegistry` / `ToolExecutor` / `ToolExecutionContext` 0 改动;`PermissionPolicy` 0 改动 — **无破坏性签名变更**;但 `DefaultToolExecutionContext` 构造器签名 +1 `RuntimeSandbox` 参数(破坏性)— `LinearTurnEngine.dispatchWithPolicy` 调用方必须更新(1 行 wire-through 修复),AC-NN-9 0 回归覆盖 | — |
| **L5 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过,**AC-NN-1—AC-NN-9 + AC-NN-deps-1 + AC-NN-deps-2** 全跑通 | CI |
| **L6 Performance** | 不跑(Story 体量不达 NFR 阈值)| `#028` sandbox 真实现开销 ≤ 1ms/tool-call(prefix 校验 + Set.contains + HashMap lookup)— §14.15.1 性能预算不退化;**留** §14.15.1 全链路性能压测 Story #010(N1) 验证 | — |
| **L7 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 + `banned-dependencies` enforcer 不 fail | CI |

**New Case 计数**:**17 test cases** 跨 5 文件(L1 13 + L3 4 = 17)
**ROADMAP 估算**:表 #028 行「13 文件 + 1 ErrorCode」 → 17 cases 与 `#027a`(22 cases)/ `#027b`(13 cases)/ `#022`(32 cases) 同量级

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `ChrootedFileSystem.getPath` prefix 校验绕过(SymbolicLink 跨 rootDir)| 2×3=6 | AC-NN-2 显式覆盖 `/etc/passwd` 越权 + 合法路径通过 + prefix 边界;`toAbsolutePath().normalize()` 后必须 startsWith(`rootDir`);JDK FileSystem SPI 不暴露 symlink resolve(走 `delegate.getPath` 后 JDK 默认 symlink 处理在本 Story 内不可控,留 OQ-Future)| revert PR;旧 `FileSystems.getDefault()` 兜底,功能无安全 |
| **R-B** `WhitelistedHttpClient` host 解析绕过(IP literal / IPv6 / port 隐含)| 2×2=4 | AC-NN-3 显式覆盖 `evil.com` 越权 + 白名单内真发;host 解析用 `URI.create(url).getHost()`;**不**处理 IP literal / IPv6(留 OQ-Future);wildcard domain(`*.example.com`)支持不在 #028 范围,后续 Story | revert PR;旧 `PassThroughHttp` 抛 `UnsupportedOperationException` 兜底,功能无安全 |
| **R-C** `ChrootRuntimeSandbox.runProcess` cmdWhitelist 校验绕过(`cmd` 路径攻击如 `/bin/rm` vs `rm`)| 1×3=3 | AC-NN-4 显式覆盖 `rm` 越权 + `ls` 通过;cmdWhitelist 严格 `equals`(不 `endsWith` / 不 `contains`);`ProcessBuilder.command(cmd, args)` 用 cmd 字符串直接调用,**不**resolve 路径 | revert PR;旧 `PassThroughHttp` 抛 `UnsupportedOperationException` 兜底,功能无安全 |
| **R-D** `AgentFactory` 6-Router → 7-Router ctor 改造打破现有 wiring(`LinearTurnEngine.dispatchWithPolicy` 调用 `new DefaultToolExecutionContext(turnCtx)` 旧路径)| 2×3=6 | AC-NN-9 0 回归覆盖 `mvn -pl lingshu-core test` 587 pre test + #028 新 case 全过;`LinearTurnEngine.dispatchWithPolicy` 调用方 1 行 wire-through 修复(同步更新 ctor 调用);AC-NN-5 L3 黑盒端到端验证 | revert PR;旧 6-Router ctor 兜底,功能完整 |
| **R-E** `AccessDeniedException` checked vs unchecked 选型破坏现有 catch 链路 | 1×3=3 | `extends RuntimeException`(unchecked)对齐 §4.10.1 5 步流水线 sandbox 步 catch 转 ToolResult.error(不需要 throws 签名);AC-NN-7 4 抛点验证 stub 委派含 `[LINGS-S01]` + ToolExecutor sandbox 步 catch 转 ToolResult error 不破坏 | revert PR;旧 PassThroughHttp 抛 UnsupportedOperationException 兜底,功能完整 |
| **R-F** R-13 mitigation (d) banned list 触发(`#028` 引入 `ProcessBuilder` + `FileSystem` + `HttpURLConnection` 等 JDK built-in)| 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-8 + tasks.md T-dep-tree-1—T-dep-tree-4 + PR body `### R-13 dependency:tree 自查` 节(`#025 follow-up` baseline 镜像已存,`#028` 第 14 次验证 0 binary delta) | revert PR;旧 PassThroughHttp + FileSystems.getDefault() + 短 deny stub 兜底,功能无安全 |
| **R-G** `PermissionPolicy.check(AskUser)` 串入 sandbox 步后误触发现有 583 测试 fail(部分 Tool `execute` 路径触发 ApprovalGate AskUser 但 policy = StrictPermissionPolicyProvider 默认 Deny)| 2×3=6 | AC-NN-5 L3 黑盒显式 mock Tool 触发 AskUser + 验证 Decision.Deny 含 LINGS-S01;AC-NN-9 0 回归 587 pre test 全过;`PermissionPolicy` 0 改动(只串入 sandbox 步) | revert PR;旧 sandbox 步空跑兜底,功能完整 |

**等级**:R-A / R-B / R-C / R-D / R-E ≤ 6 监控即可;R-F / R-G ≥ 6 必缓解(AC-NN-8 + AC-NN-9 强制 + enforcer build fail)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `#028` 1 段(Sandbox runtime 真实现,`ChrootRuntimeSandbox` + `ChrootedFileSystem` + `WhitelistedHttpClient` 落地 + `LINGS-S01 SANDBOX_ACCESS_DENIED` 启用 + §6.3 ChrootRuntimeSandbox 模板真接通)
- [ ] `specs/028-sandbox-runtime-impl/quickstart.md`(本 PR 内;给 Alice 30min 跑通 hello world sandbox,模板对齐 `#027a`/`#027b`)
- [ ] `specs/028-sandbox-runtime-impl/data-model.md`(`RuntimeSandbox` / `AccessDeniedException` / `ProcessRunner` / `NetworkClient` / `ChrootRuntimeSandbox` / `ChrootedFileSystem` / `WhitelistedHttpClient` / `RuntimeSandboxProvider` / `RuntimeSandboxRouter` 9 核心类型对照表 + `LINGS-S01` ErrorCode 域表 + sandbox 5 字段 × 4 抛点映射表,模板对齐 `#027a`/`#027b`)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.46 → v1.5.47` 行(本 Story 实施记录)
- [ ] `dsh_agent_design.md` §15.4 ErrorCode 域字母表加 `S = Sandbox 域 LINGS-S01` 行(新域段启用,域字母 9 → 10)+ §15.5 S 段序号表 1 号 `LINGS-S01 SANDBOX_ACCESS_DENIED`
- [ ] `constitution.md` §4 域字母表加 `S = Sandbox` 行 + `LINGS-S01 SANDBOX_ACCESS_DENIED` + §10 R-13 风险登记:`Story #028` 标记「已缓解」+ 第 14 次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#028` 行(2026-09-30,587 pass / 0 fail / R-13 0 binary delta 第 14 次 / +LINGS-S01 / +17 case)
- [ ] `ROADMAP.md` §15.4 ErrorCode 域表同步 `LINGS-S01` + `S = Sandbox` 域
- [ ] `lingshu-docs` 仓 `docs/concepts/sandbox-runtime.md` 起草 `#028` 段落(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.46` → `v1.5.47`)

---

## 7. 关键不变项(冻结)

1. `Tool` interface 5 方法 + `ToolRegistry` interface 8 方法 + `ToolExecutor.dispatch()` 5 流水线(§4.10.1 硬规则 2 整体不变,**只是"sandbox"步从空跑变真实现**)— **0 改动**
2. `ToolExecutionContext` interface 6 方法契约(`workingDirectory()` / `fs()` / `http()` / `approval()` / `cancellation()` / `callConfig()`)— **0 改动**(只 default 实现 `DefaultToolExecutionContext` 真接通 RuntimeSandbox)
3. `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Transform`)+ `ToolCallConfig` 4 字段 — **0 改动**
4. `Agent` 4 final 字段(`config` / `session` / `engine` / `toolPool`)T1→T4 不变 + `AgentFactory.create()` 校验模式不变 — **0 改动**
5. `LinearTurnEngine` ReAct 主循环结构 + `Message` 4 子类(`System` / `User` / `Assistant` / `ToolResult`,§0.5.46 refactor 后 `ToolUse` 已删)+ `Prompt` 5 段契约 + `LlmResponse` 5 字段契约 — **全部 0 改动**
6. `LlmProvider` SPI / `LlmErrorCodes.L01/L02/L03 reserved`(§0.5.46 L02 落地 + #027b L03 reserved)+ `AnthropicLlmProvider` 6-arg ctor + `buildRequestBody` 协议转换 + `parseResponse` fallback — **0 改动**
7. `AccessDeniedException extends RuntimeException`(checked 不变,避免侵入现有 catch 链路)
8. 9 Slot 顶层体系不变(Slot 3 Sandbox 是**隐式 Router**,**不**作顶层 Slot;RuntimeSandboxRouter 由 AgentFactory 直接 `@Autowired`,不在 SlotResolver 字段里)
9. 24 字段 `AgentConfig` schema 不变(Sandbox 5 字段已在 `#025 follow-up` 落地)
10. `RuntimeSandbox` / `AccessDeniedException` / `ProcessRunner` / `NetworkClient` / `ChrootRuntimeSandbox` / `ChrootedFileSystem` / `WhitelistedHttpClient` / `RuntimeSandboxProvider` / `RuntimeSandboxRouter` / `RuntimeSandboxAutoConfiguration` / `SandboxErrorCodes` 11 文件都是**新增**(不修改任何现有契约,只新增契约 + 默认实现)
11. `DefaultToolExecutionContext` 构造器签名破坏性修改 +1 `RuntimeSandbox` 参数(`LinearTurnEngine.dispatchWithPolicy` 1 行 wire-through 修复)
12. `AgentFactory` 6-Router ctor → 7-Router ctor 破坏性修改(`@Autowired` ctor 参数列表加 1 项 + `create()` 7 项校验新增)
13. dsh §15.4 域字母表新增 `S = Sandbox` 域段(本 Story 启用 S01,域字母表 9 → 10:`C/S/L/T/X/R/A/Z/D` 9 + `S` = 10);S01 = `SANDBOX_ACCESS_DENIED`;S02+ reserved 占位(留后续 Story)
14. constitution v1.0 §1—§9 全部不变,只 §4 S 域段启用 + §10 R-13 风险状态更新
15. **0 新 Maven 依赖**(R-13 mitigation (d) 第 14 次验证)
16. **13 处核心修改 + 11 处核心新增** = **24 文件总改动**(13 文件清单已列,实际 #028 主要改动文件 13 个:11 new + 2 modify;但 AgentFactory 7-Router ctor 影响所有 ctor 调用方测试代码 = 整体 commit 影响 +11 文件测试 fixture / pre-existing test 引用 — 但这些测试代码本身不修改,只是 Spring 注入路径变更)— 符合 §11.4 Story 边界 ≤ 5 核心文件 + ≤ 3 ErrorCode(本 Story 1 新抛 ErrorCode,严格 ≤ 3)
17. **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),sandbox 真实现通过 `DefaultToolExecutionContext` 触发,**不走 ChatClient 自动执行**

---

**Plan writer**: Claude Code
**Plan date**: 2026-09-30
**Plan version**: v0.1 Draft