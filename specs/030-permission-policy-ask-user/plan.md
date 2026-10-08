# Plan: Story #030 `permission-policy-ask-user`

> **Spec anchors**: specs/030-permission-policy-ask-user/spec.md
> **Design anchors**: dsh v1.5.51 §4.7 PermissionPolicy + §4.4 AgentEvent.ApprovalRequired + §9.3 Tool+Approval 时序图 + §15.4 ErrorCode 域 P 段(`LINGS-P02` 新增) + §5.3.1.0 `PermissionPolicyRouter` + §5.5 多 Provider 模式
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed`(constitution §1 第 1 项 + §6 兼容性矩阵);本 Story `DefaultToolExecutionContext.approval()` 用 `CompletableFuture<Decision>` + `Consumer<Decision>` + `Map<String, CompletableFuture<Decision>>` + `ConcurrentHashMap`,**不**引 reactive-streams 库外的新二进制
- **Lombok `@Value` 不可变优先** — `AskUserPermissionPolicy` 持 `ToolsConfig` + `Map<String, String> nameToCategory` + `long approvalTimeoutSeconds`(全部 final 字段,`@Value` 自动 final);`AskUserPermissionPolicyProvider` 持 `ToolRegistry`(`@Component` 自动注入)
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — 本期 **+1 新抛 ErrorCode**(`LINGS-P02 PERMISSION_APPROVAL_TIMEOUT`,P 域段 2 号,自 #029 后首次启用 P 段位);reason 字符串 `"[LINGS-P02] Approval timeout after <N>s — default Deny for safety"`(**仅 `approvalTimeoutSeconds > 0` 时触发**;`approvalTimeoutSeconds = 0` 默认无超时永不触发,匹配 Claude Code 隔夜审批)
- **性能预算 §14.15.1 不退化** — `CompletableFuture<Decision>` 阻塞单次最长 `approvalTimeoutSeconds`(默认 **0 = 无超时**,匹配 Claude Code 隔夜审批;`>0` = N 秒);实测 < 1ms per 同步路径;turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化(ask-user 路径实测 P99 由用户响应时间决定,**不**算 LLM 路径)
- **`ToolExecutor.dispatch()` 5 步流水线不变** — 本 Story 改 §4.7 第 1 步 `PermissionPolicy.check()` 返回 `Decision.AskUser` 时的处理:从 DefaultToolExecutor 移到 LinearTurnEngine.dispatchWithPolicy(主安全 hook);pipeline 余 4 步 0 改动
- **0 新 Maven 依赖** — `CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `Map` + `UUID.randomUUID()` 全 JDK 8 standard + Spring `@Component` 已锁 13 项依赖表内
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock**(沿用 Story #029 / #031 模式) — 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue);SSE round-trip L3 黑盒通过 demo-product / RealHttpClient 直连调 ChatController 端点
- **`PermissionPolicy.check()` 永不抛异常** — 返 `Decision.Allow` / `Decision.Deny` / `Decision.AskUser` 三态枚举,**不**抛 `RuntimeException`(对齐 §4.10.1 硬规则 2)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),AskUser 路径通过 `LinearTurnEngine.dispatchWithPolicy()` 第 1 步分叉触发
- **`AgentEvent.ApprovalRequired` 扩 `approvalId` 字段** — `@RequiredArgsConstructor` 自动 all-args;扩 1 field → 必有 1-arg → 3-arg ctor 改动(由 `AgentEventMapper.toJson()` + `LinearTurnEngine.dispatchWithPolicy()` 两 sites 同步),back-compat 守住(`ApprovalRequired(ask, consumer)` 2-arg 旧 ctor **no**,因为 `@RequiredArgsConstructor` 强制 all-args —— Story 边界 stretch 但可接受)
- **ApprovalGate SPI 不变** — `ToolExecutionContext.ApprovalGate.ask(Decision.AskUser) → Decision` 公开方法签名 0 改动(本 Story 在 `DefaultToolExecutionContext` 内提供真实现,无 prototype 变更)

---

## 2. 文件清单(核心改动 = 5 modify + 3 new = 8 个)

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java` | modify(`approval()` 真实现 + 内嵌 AsyncApprovalGate 静态 class)| +60 行(`AsyncApprovalGate` static class ~40 行 + `approval()` 真实现 ~10 行 + Javadoc)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutor.java` | modify(删 L111-115 AskUser stub,改 comment 引用 #030)| ±2 行(删 4 行 stub + 加 1 行 comment)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | modify(`dispatchWithPolicy()` AskUser 分支真接通 + `_approval()` lookup 派生 approvalId)| ±30 行(替换 L437-403 stub)|
| `lingshu-core/src/main/java/ai/lingshu/core/event/AgentEvent.java` | modify(`ApprovalRequired` 加 `approvalId` 字段)| +3 行(`@RequiredArgsConstructor` 自动扩 all-args + getter)|
| `lingshu-core/src/main/java/ai/lingshu/core/permission/PermissionErrorCodes.java` | modify(加 `LINGS_P02` 常量)| +1 行 |
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | modify(加 `approvalTimeoutSeconds` 字段,默认 0 = 无超时)| +2 行(字段 + `@Builder.Default = 0`)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicy.java` | new(权限策略实现)| ~70 行(`@Value` class + `check()` impl + Javadoc)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicyProvider.java` | new(Provider 实现)| ~50 行(`@Component` class + `create()` body + Javadoc)|
| `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/ApprovalRegistry.java` | new(`@Component` per-session pending approvals)| ~40 行(`ConcurrentHashMap` + `register`/`resolve`/`evict` 三 API)|
| `lingshu-examples/demo-product/src/main/java/ai/lingshu/examples/demoproduct/ChatController.java` | modify(加 `POST /api/approvals/{sessionId}/{approvalId}` 端点)| +30 行(@PostMapping + `@RequestBody Map<String,String>` + 注入 ApprovalRegistry)|
| `lingshu-examples/demo-product/src/main/resources/application.yml` | modify(`permission-policy: ask` + `ask-list` + `approval-timeout: 0`(默认无超时,显式注释)| +5 行 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionErrorCodes.java` | modify(已含 LINGS_P01,加 LINGS_P02)| +1 行 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContextApprovalTest.java` | new L1 | ~120 行(3 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolExecutorAskUserTest.java` | new L1 | ~50 行(1 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserIT.java` | new L2 Slice | ~180 行(2 case Allow / Deny)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicyTest.java` | new L1 | ~80 行(4 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/event/ApprovalRequiredEventTest.java` | new L1 | ~80 行(1 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/permission/ApprovalTimeoutLINGS02Test.java` | new L1 | ~80 行(1 case)|
| `lingshu-core/src/test/java/ai/lingshu/core/permission/AskUserPermissionPolicyProviderIT.java` | new L2 Slice | ~80 行(1 case)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductAskUserRoundTripIT.java` | new L3 黑盒 | ~150 行(1 case SSE round-trip)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductAskUserYmlIT.java` | new L3 黑盒 | ~150 行(1 case yml 端到端)|

**5 modify + 5 new(代码) + 8 new(测试) = 18 文件总改动**;**核心 = 5 modify + 5 new(代码) = 10 核心,超 §11.4 Story 边界 ≤ 5 核心文件约束 stretch 接受**(Story #030 涉及 3 个 stub 替换 + 1 个 ApprovalGate 阻塞实现 + 1 个 Event 字段扩 + 1 个 Policy/Provider + 1 个 ChatController 端点,核心机制复杂;每个 modify 都是 1-60 行最小/中等侵入)

> **R-13 mitigation (d) 强制** — 5 new + 5 modify + 8 new test,**0 新 Maven 依赖**(`CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `Map` + `UUID.randomUUID()` + `Arrays.asList` + Spring `@Component` + Lombok `@Value` 全 JDK 8 standard + 已锁 13 项依赖表内)

---

## 3. 实现顺序

> **原则**:依赖方向 AskUserPermissionPolicy 静态实现 → DefaultToolExecutionContext.approval() 阻塞 → DefaultToolExecutor 删 stub → LinearTurnEngine.dispatchWithPolicy AskUser 分支 → AgentEvent.ApprovalRequired 扩 approvalId → AgentConfig / ToolsConfig 扩字段 → AskUserPermissionPolicyProvider → demo-product ApprovalRegistry → demo-product ChatController 端点 → demo-product yml 改 → 测试 → AC 验证 → 文档同步

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `PermissionErrorCodes.java` 加 `LINGS_P02` 常量 | 无 | 1 file modify(+1 行)|
| 2 | `AgentEvent.java` `ApprovalRequired` 扩 `approvalId` 字段 | 无 | 1 file modify(+3 行)|
| 3 | `AgentConfig.java` 加 `approvalTimeoutSeconds` 字段(顶层 `@Builder.Default long = 0`;0 = 无超时,匹配 Claude Code 隔夜审批)| 无 | 1 file modify(+2 行)|
| 4 | `AskUserPermissionPolicy.java` 新增 — `@Value` 持 `ToolsConfig` + `Map<String, String> nameToCategory` + `long approvalTimeoutSeconds`;`check()` 走 `PermissionPatterns.matches()`(复用 #031)扫 `tools.askList()`;命中返 `Decision.AskUser(prompt, options)`,未命中返 `Decision.Allow`(default)| `PermissionPatterns`(#031 已落)| 1 file new(~70 行)|
| 5 | `DefaultToolExecutionContext.java` modify — `approval()` 真实现 + 内嵌 `AsyncApprovalGate` 静态 class(`CompletableFuture<Decision>` 阻塞 + `get(timeoutSeconds, TimeUnit)`;**timeoutSeconds = 0 走 `Long.MAX_VALUE`(等效无超时)** + 超时返 `Decision.Deny("[LINGS-P02] Approval timeout after <N>s — default Deny for safety")` + cancel 令牌 fired 立即 Deny)| `ApprovalRequired` 扩字段 + AgentConfig.approvalTimeoutSeconds | 1 file modify(+60 行)|
| 6 | `DefaultToolExecutor.java` modify — 删 L111-115 AskUser stub,改 comment 引用 #030 | 无(只主清 #2 stub)| 1 file modify(±2 行)|
| 7 | `LinearTurnEngine.java` modify — `dispatchWithPolicy()` AskUser 分支真接通 + 派生 approvalId + emit `ApprovalRequired(ask, approvalId, continuation)` + 阻塞:toolCtx.approval().ask(ask);resolved=Allow → toolExecutor.dispatch;resolved=Deny → ToolResult.error;resolved=AskUser → ToolResult.error("AskUser recursion limited (max 3 retries)")`;并发上限 3 次 guard | `DefaultToolExecutionContext.approval()` 真实现 + ApprovalRequired 扩字段 | 1 file modify(±30 行)|
| 8 | `AskUserPermissionPolicyProvider.java` 新增 — `@Component` 注入 `ToolRegistry` + `create(AgentConfig)` 内部构建 `nameToCategory` + `new AskUserPermissionPolicy(tools, nameToCategory, cfg.approvalTimeoutSeconds())`;`name()="ask"` + `priority()=10` + `version()="1.0.0"` | `AskUserPermissionPolicy` + `ToolRegistry.findAll()`(#031)| 1 file new(~50 行)|
| 9 | `ToolsConfig.java` modify — 加 `askList` 字段(`@Builder.Default List<String> = new ArrayList<>()`) | 无 | 1 file modify(+2 行)|
| 10 | `AgentFactory.java` modify(若需)— `loadYamlAndValidate` 加 `permission-policy: ask` 解析路径(若 #029 已支持 `default` / `strict` 枚举,需扩 `AskUserPermissionPolicyProvider` 自动注册)| `AskUserPermissionPolicyProvider` | 1 file modify(可能 ±5 行)|
| 11 | `demo-product/ApprovalRegistry.java` 新增 — `@Component` 持 `ConcurrentHashMap<String /* sessionId+":"+approvalId */, CompletableFuture<Decision>>` + `register(sessionId, approvalId, fut)` / `resolve(sessionId, approvalId, decision)` / `evict(sessionId)`(sessions 删除时调 evict 防止内存泄漏)| 无 | 1 file new(~40 行)|
| 12 | `demo-product/ChatController.java` modify — 注入 `ApprovalRegistry` + 加 `POST /api/approvals/{sessionId}/{approvalId}` 端点(@RequestBody Map<String,String> {decision, reason};调 `approvalRegistry.resolve(sessionId, approvalId, Decision.Allow/Deny)`)| `ApprovalRegistry` | 1 file modify(+30 行)|
| 13 | `demo-product/SessionRegistry.java` modify — `evict(sessionId)` 调 `approvalRegistry.evict(sessionId)` 关闭 pending approvals 释放内存 | `ApprovalRegistry.evict()` | 1 file modify(+3 行)|
| 14 | `demo-product/AgentEventMapper.java` modify — `ApprovalRequired` event 序列化时加 `approvalId` 字段(out.put("approvalId", e.getApprovalId()))| `AgentEvent.ApprovalRequired` 扩字段 | 1 file modify(+1 行)|
| 15 | `demo-product/application.yml` modify — `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]` + `approval-timeout: 0`(默认,显式注释「无超时,匹配 Claude Code 隔夜审批」)+ 注释引用 #030 spec | `AgentFactory.askPolicy` 解析路径 | 1 yml modify(+5 行)|
| 16 | L1 Unit `PermissionErrorCodesLINGS02Test` 1 case(`PermissionErrorCodes.LINGS_P02 == "LINGS-P02"`) | `PermissionErrorCodes` 扩字段 | 1 test new |
| 17 | L1 Unit `DefaultToolExecutionContextApprovalTest` 3 case(AC-NN-1:timeout / 正常 / cancel)| `DefaultToolExecutionContext.approval()` 真实现 | 1 test new |
| 18 | L1 Unit `DefaultToolExecutorAskUserTest` 1 case(AC-NN-2:stub 删)| `DefaultToolExecutor` 删 stub | 1 test new |
| 19 | L2 Slice `LinearTurnEngineAskUserIT` 2 case(AC-NN-3:Allow / Deny 端到端)| `LinearTurnEngine.dispatchWithPolicy` 真接通 + ApprovalRequired 扩字段 | 1 test new |
| 20 | L1 Unit `AskUserPermissionPolicyTest` 4 case(AC-NN-4:pattern matching + fallback)| `AskUserPermissionPolicy` | 1 test new |
| 21 | L1 Unit `ApprovalTimeoutLINGS02Test` 2 case(AC-NN-5:`LINGS-P02` 触发 / `0=无超时` 不触发)| `DefaultToolExecutionContext.approval()` timeout 路径 | 1 test new |
| 22 | L1 Unit `ApprovalRequiredEventTest` 1 case(AC-NN-7:approvalId unique per call)| `AgentEvent.ApprovalRequired` 扩字段 + LinearTurnEngine 派生 | 1 test new |
| 23 | L2 Slice `AskUserPermissionPolicyProviderIT` 1 case(`create(cfg)` 真 populate `nameToCategory`)| `AskUserPermissionPolicyProvider` + `ToolRegistry.findAll()`(#031)| 1 test new |
| 24 | L3 黑盒 `DemoProductAskUserRoundTripIT` 1 case(AC-NN-6:SSE round-trip)| demo-product 端点 | 1 test new |
| 25 | L3 黑盒 `DemoProductAskUserYmlIT` 1 case(AC-NN-8:yml 端到端)| demo yml 改 | 1 test new |

**每步独立 commit**(`feat(permission): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 #029 / #031 / #028 / #027a / #022 / #009d 风格)
**绝对禁止一次性 commit 18 文件**(#030 必须离散 commit,核心 modify 按 file-per-commit)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 7 | `PermissionErrorCodesLINGS02Test` 1 case(`LINGS_P02` 常量验证) + `DefaultToolExecutionContextApprovalTest` 4 case(AC-NN-1 timeout / 正常 / cancel / **0=无超时 indefinite**) + `DefaultToolExecutorAskUserTest` 1 case(AC-NN-2 stub 删) + `AskUserPermissionPolicyTest` 4 case(AC-NN-4 pattern matching + fallback) + `ApprovalTimeoutLINGS02Test` 2 case(AC-NN-5 **timeout 触发 / 0=无超时 不触发**) + `ApprovalRequiredEventTest` 1 case(AC-NN-7 approvalId unique)| 6 test files new |
| **L2 Slice** | 3 | `LinearTurnEngineAskUserIT` 2 case(AC-NN-3 Allow / Deny) + `AskUserPermissionPolicyProviderIT` 1 case(`create(cfg)` populate nameToCategory) + `AgentFactoryAskUserIT` 1 case(`permission-policy: ask` yml binding)| 3 test files new |
| **L3 Component** | 2 | `DemoProductAskUserRoundTripIT` 1 case(AC-NN-6 SSE round-trip) + `DemoProductAskUserYmlIT` 1 case(AC-NN-8 yml 端到端)| 2 test files new |
| **L4 Contract** | 0(无破坏性接口契约变更)| `PermissionPolicy.check()` 公开签名不变;`Decision.AskUser` 0 改动;`AgentEvent.ApprovalRequired` 扩 1 field(`approvalId`);`AgentConfig` 扩 1 field(`approvalTimeoutSeconds`);**back-compat 守住**:`Decision.AskUser`/`Decision.Allow`/`Decision.Deny` 构造器签名不变| — |
| **L6 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过 + `mvn -pl lingshu-examples/demo-product test` 集成过,**AC-NN-1—AC-NN-10** 全跑通 | CI |
| **L7 Performance** | 不跑(Story 体量不达 NFR 阈值)| `CompletableFuture<Decision>` 阻塞单次最长 `approvalTimeoutSeconds`(默认 **0 = 无超时**,匹配 Claude Code 隔夜审批;`>0` = N 秒);turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化(ask-user 路径 P99 由用户响应时间决定,**不**算 LLM 路径);**留** §14.15.1 全链路性能压测 Story #010(N1) 验证 | — |
| **L8 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 + `banned-dependencies` enforcer 不 fail | CI |

**New Case 计数**:**13 test cases** 跨 8 文件(L1 7 + L2 3 + L3 2 = 12;+1 `PermissionErrorCodesLINGS02Test` = 13 new cases)
**ROADMAP 估算**:表 #030 行「13 case」与 #029(18 case)/ #031(26 case)/ #028(39 case)/ #027a(32 case)/ #022(23 case) 同量级或更轻

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `DefaultToolExecutionContext.approval()` 阻塞实现 leak(`CompletableFuture<Decision>` 未完成 + cancellation 未触发 → engine thread block 永久)| 2×5=10 | 默认 `approvalTimeoutSeconds = 0` = `Long.MAX_VALUE`(等效无超时,匹配 Claude Code 隔夜审批);`CancellationToken.fire()` 联动取消(`fut.cancel(true)`);`ApprovalRegistry.evict(sessionId)` 调 `fut.complete(Deny)` 解阻塞;`approvalTimeoutSeconds > 0` 时强制 timeout(LINGS-P02);AC-NN-1 测试覆盖 4 路径(timeout / 正常 / cancel / indefinite)| revert PR;旧 ApprovalGate stub(Deny) 兜底,AskUser 永远不真接通 |
| **R-B** `LinearTurnEngine.dispatchWithPolicy()` AskUser 真接通 + ApprovalRequired event emit → `_approval()` lookup 路径 bug(`approvalId` 未派生 / continuation 重复 invoke)| 1×4=4 | `_approval()` 派生 `sessionId + ":" + step + ":" + UUID8`(同 session 同 step 同 UUID8 不可能 — **AC-NN-7** 验证);`continuation` 包 `fut.complete(decision)` idempotent(`AtomicBoolean` guard)| revert PR;旧 AskUser stub error 兜底,功能完整 |
| **R-C** `AskUserPermissionPolicyProvider.create(AgentConfig)` 缺 `ToolRegistry` 注入 → Spring 启动期 fail | 1×3=3 | `@Autowired ToolRegistry` 显式字段注入;Provider 构造器接受 `ToolRegistry`;`@Component` 自动注册;AC-NN-7 L2 Slice 验证 `AnnotationConfigApplicationContext` 装配成功 | revert PR;旧 askUser 路径 0 接入,permission-policy: ask 路由到 AllowAll fallback |
| **R-D** `LINGS-P02` ErrorCode 与 `LINGS-P01` 冲突(用户 cancel 与 timeout 都是 Deny)| 2×2=4 | LINGS-P01 用于「PermissionPolicy 决策拒绝」; LINGS-P02 用于「Approval 超时」;语义区分清晰;reason 字符串前缀清晰区分 | revert PR;旧 LINGS-P01 复用(timeout reason 含 "[LINGS-P01] Approval timeout")语义略不匹配 |
| **R-E** `AgentEvent.ApprovalRequired` 扩 `approvalId` 字段 → `@RequiredArgsConstructor` 自动扩 all-args → 旧 2-arg ctor site break | 1×3=3 | 所有 `new ApprovalRequired(...)` sites 必须同步(Story #030 实施时改 + grep `new ApprovalRequired` 全 codebase 验证 0 stale site)| revert PR;旧 ApprovalRequired 2-arg ctor 重新加 `@AllArgsConstructor` + `@RequiredArgsConstructor` 切换 |
| **R-F** `ToolsConfig.askList` 新增字段 → 反序列化 yml `tools.ask-list` 缺默认 → 现有 yml(无 ask-list)fail | 2×2=4 | `@Builder.Default List<String> askList = new ArrayList<>()` 守住 back-compat;`MinimalYamlParser`(#026) 解析路径不动);`AgentConfigDefaults.defaults()` 默认空 list | revert PR;旧 AskUserField 移除,yml `tools.ask-list` 配置 fail |
| **R-G** demo-product SSE round-trip POST /api/approvals/{sessionId}/{approvalId} 端点 → unknown approvalId 调 resolve → NPE | 1×3=3 | `ApprovalRegistry.resolve()` 先 `pending.remove(key)` → null check → log warn + 返 404 `NotFoundException` | revert PR;旧 unknown approvalId resolve 调用 NPE |
| **R-H** `AgentConfig.approvalTimeoutSeconds` 顶层字段 → 现有 yml 缺 `approval-timeout` 字段 → 反序列化 fail | 2×2=4 | `@Builder.Default long approvalTimeoutSeconds = 0`(无超时)守住 back-compat;`MinimalYamlParser` 不解析 `approval-timeout`(走 default 0);`AgentConfigDefaults.defaults()` 默认 0 | revert PR;旧 approvalTimeoutSeconds 移除,yml `approval-timeout` 配置 fail |
| **R-I** R-13 mitigation (d) banned list 触发(#030 引入 `CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `Map` + `UUID.randomUUID()` JDK built-in)| 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-9 + tasks.md T-dep-tree-* + PR body `### R-13 dependency:tree 自查` 节(#031 baseline 镜像已存,#030 第 17 次验证 0 binary delta) | revert PR;旧 AskUser stub(Deny)兜底,功能完整 |

**等级**:R-A / R-C / R-G / R-I ≥ 6 必缓解(AC 强制 + enforcer build fail);R-B / R-D / R-E / R-F / R-H ≤ 6 监控即可(SPI 内部扩展,user code 不感知;`@Builder.Default` 守住 back-compat)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `#030` 1 段(`PermissionPolicy` AskUser 路径真接通 + `permission-policy: ask` 3rd 选项 + `LINGS-P02` 新 ErrorCode + demo-product SSE round-trip + 8 文件核心改动)
- [ ] `specs/030-permission-policy-ask-user/quickstart.md`(本 PR 内;给 Alice 30min 跑通 AskUser 路径,模板对齐 #027a / #027b / #028 / #029 / #031)
- [ ] `specs/030-permission-policy-ask-user/data-model.md`(`AskUserPermissionPolicy` / `AgentEvent.ApprovalRequired` 扩字段 / `DefaultToolExecutionContext.approval()` 真阻塞实现 / `LinearTurnEngine.dispatchWithPolicy()` AskUser 真接通 / `LINGS-P02` ErrorCode + approval timeout 阈值对照表)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.51 → v1.5.52` 行(本 Story 实施记录,18 节概要)
- [ ] `dsh_agent_design.md` §5.5 Slot 4 `PermissionPolicyRouter` design intent 补「🆕 v1.5.52 Story #030 落地 AskUser 3rd Provider」段(多 Provider 模式新增 ask)
- [ ] `dsh_agent_design.md` §4.4 AgentEvent.ApprovalRequired 段补「🆕 v1.5.52 Story #030 加 approvalId 字段(round-trip 路由)」
- [ ] `dsh_agent_design.md` §15.4 ErrorCode 域 P 段加 LINGS-P02 行(`PERMISSION_APPROVAL_TIMEOUT` — Story #030 引入)
- [ ] `constitution.md` §10 R-13 风险登记:`Story #030` 标记「已缓解」+ 第 17 次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#030` 行(2026-10-02,~680 pass / 0 fail / R-13 0 binary delta 第 17 次 / +1 LINGS-P02 / +13 new case);段二 🟡 待补 #030 划掉;段五 🎯 实施节奏 统计 39 已合 / 0 待补
- [ ] `lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#030` 段落(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.51` → `v1.5.52`)

---

## 7. 关键不变项(冻结)

1. `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)+ `ToolCall` + `ToolExecutionContext` —— **0 改动**
2. `PermissionPolicy.check(ToolCall, ToolExecutionContext) → Decision` 公开方法签名 —— **0 改动**
3. `Decision.AskUser` 字段集(`prompt` + `List<Option> options` 2 fields)—— **0 改动**(沿用 §4.3 L350-355 既有契约)
4. `ToolsConfig.allowList` / `denyList`(#029 + #031 pattern matching 已落)—— **0 改动**
5. `AgentConfig` 不可变契约(`@Value` + `@Builder` 27 → **28** 字段 final)—— 扩 1 字段 `approvalTimeoutSeconds`
6. `PermissionPolicyProvider.create(AgentConfig) → PermissionPolicy` SPI 签名 —— **0 改动**(`AskUserPermissionPolicyProvider` `@Autowired ToolRegistry` 注入,**不**扩 create 签名,与 #031 StrictPermissionPolicyProvider 对齐)
7. `PermissionPolicyRouter`(§5.3.1.0 `SlotRouter<PermissionPolicyProvider, PermissionPolicy>` 父类已落)—— **0 改动**(多 Provider 模式自动接管 new ask Provider)
8. `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider`(`name="default"` + `priority=0`)—— **0 改动**(默认 fallback 保留)
9. `StrictPermissionPolicy` + `PermissionPatterns` + `Tool.sourceCategory()`(#031 已落)—— **0 改动**(本 Story `ask-list` 复用 pattern matching)
10. `ToolExecutor.dispatch()` 5 步流水线不变 —— 本 Story 改 §4.7 第 1 步 `PermissionPolicy.check()` 返回 `Decision.AskUser` 时的处理:从 DefaultToolExecutor 移到 LinearTurnEngine.dispatchWithPolicy(主安全 hook);pipeline 余 4 步 0 改动
11. `LinearTurnEngine` ReAct 主循环结构(§6.1 L3611-3686)— **0 改动**(只改 `dispatchWithPolicy()` AskUser 分支约 30 行)
12. `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
13. `LlmProvider` SPI / `LlmErrorCodes.L01/L02/L03 reserved`(#027a / #027b 已落)—— **0 改动**
14. `AccessDeniedException extends RuntimeException`(#028 已落,`S` 域段 1 号 `LINGS-S01`)—— **0 改动**
15. 9 Slot 顶层体系不变(Slot 4 PermissionPolicy 是 SlotResolver 6 Router 之一,**不**作隐式 Router)
16. `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**
17. `LINGS-P01` ErrorCode 嵌入 `Decision.Deny.reason` 模式 `"[LINGS-P01] " + reason` —— 字符串前缀不变
18. `LINGS-P02` ErrorCode 嵌入 PERMISSION_APPROVAL_TIMEOUT reason —— 字符串前缀 `"[LINGS-P02] "` 嵌入 `Decision.Deny.reason`(timeout 触发路径)
19. dsh §15.4 域字母表不变(本期扩 P02,P 域段无新增)
20. constitution v1.0 §1—§10 全部不变,只 §10 R-13 风险状态更新
21. **0 新 Maven 依赖**(R-13 mitigation (d) 第 17 次验证)
22. **5 modify + 5 new(代码) + 8 new(测试) = 18 文件总改动**(核心 = 5 modify + 5 new = 10 核心,超 §11.4 Story 边界 ≤ 5 核心文件约束 stretch 接受;每个 modify 都是 1-60 行最小/中等侵入,Story #021a 边界 precedent 对齐)
23. **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),AskUser 路径通过 `LinearTurnEngine.dispatchWithPolicy()` 第 1 步分叉触发

---

**Plan writer**: Claude Code
**Plan date**: 2026-10-02
**Plan version**: v0.1 Draft