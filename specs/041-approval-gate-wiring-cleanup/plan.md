# Plan: Story #041 `approval-gate-wiring-cleanup`

> **Spec anchors**: specs/041-approval-gate-wiring-cleanup/spec.md
> **Design anchors**: dsh v1.5.53 §4.7 PermissionPolicy + §4.4 AgentEvent.ApprovalRequired + §9.3 Tool+Approval 时序图 + §15.4 ErrorCode 域 P 段(`LINGS-P02` 复用,**0 新 ErrorCode**)
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)
> **Story 体量**:~8 核心 modify + 1 new test file + 6 case + 0 new ErrorCode

---

## 1. 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed`(constitution §1 第 1 项 + §6 兼容性矩阵);本 Story `DefaultApprovalGate` 用 `CompletableFuture<Decision>` + `Consumer<Decision>` + `Map<String, Consumer<Decision>>` + `ConcurrentHashMap` + `AtomicBoolean` + `UUID.randomUUID()`,**不**引 reactive-streams 库外新二进制
- **Lombok `@Value` 不可变优先** — `DefaultApprovalGate` 持 `TurnContext` + `CancellationToken` + `long timeoutSec` 3 个 final 字段(无 `@Value` 必要,手写 `private final`)
- **0 新 ErrorCode** — 复用 `LINGS-P02`(Story #030 引入,timeout 触发 + sink null + cancel 3 路径)
- **性能预算 §14.15.1 不退化** — `CompletableFuture<Decision>` 阻塞单次最长 `approvalTimeoutSeconds`(默认 **0 = 无超时**);逻辑下沉到 `DefaultApprovalGate` 不变路径长度,turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化
- **`ToolExecutor.dispatch()` 5 步流水线不变** — `LinearTurnEngine.dispatchWithPolicy` 第 1 步 `PermissionPolicy.check()` 返 `Decision.AskUser` 时改为调 `toolCtx.approval().ask()`,语义不变
- **`ToolExecutionContext.ApprovalGate` SPI 不变** — 公开方法签名 `ask(AskUser) → Decision` 0 改动(只 `DefaultToolExecutionContext` 默认实现真接通)
- **0 新 Maven 依赖** — `UUID.randomUUID()` + `CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `AtomicBoolean` JDK 8 standard + Spring `@Component` + Lombok `@Value` 已锁 13 项依赖表内
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock**(沿用 Story #030 模式) — 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue)
- **`PermissionPolicy.check()` 永不抛异常** — 返 `Decision.Allow` / `Deny` / `AskUser` 三态,**不**抛 `RuntimeException`(对齐 §4.10.1 硬规则 2)
- **`ApprovalRegistry` 单 approvalId key 不变** — 留 OQ-Future 扩展 `(sessionId, approvalId)` 复合 key;本 Story 不动 API

---

## 2. 文件清单(核心改动 = 5 modify + 1 new + 4 modify-by-ripple-effect)

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java` | modify — `approval()` 重写为 `new DefaultApprovalGate(turnCtx, cancellation(), timeoutSec)` + 新增内嵌 `DefaultApprovalGate` 静态 class(~90 行,封装 Story #030 inline L467-559 逻辑)| +80 行(`DefaultApprovalGate` static class ~90 行 - 旧 stub ~10 行 = +80 行)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | modify — `dispatchWithPolicy()` AskUser 分支从 inline L467-559(~50 行)下沉为 `toolCtx.approval().ask(ask)`(~15 行)| -35 行(净减) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutor.java` | modify — **0 改动**(Story #030 已删 stub) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/ApprovalRegistry.java` | modify — **0 改动**(API 不变) |
| `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutionContext.java` | modify — JavaDoc 改写(`ApprovalGate` interface 保留,JavaDoc 引用 `DefaultApprovalGate` 真实现)| ~0 行(纯注释)|
| `lingshu-core/src/main/java/ai/lingshu/core/decision/Decision.java` | modify — JavaDoc 改写(`AskUser` 注释引用 `DefaultApprovalGate` 真实现)| ~0 行(纯注释)|
| `lingshu-core/src/main/java/ai/lingshu/core/permission/PermissionErrorCodes.java` | modify — JavaDoc 改写(`LINGS-P02` 注释引用 `DefaultApprovalGate`)| ~0 行(纯注释)|
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | modify — JavaDoc 改写(`approvalTimeoutSeconds` 注释引用 `DefaultApprovalGate`)| ~0 行(纯注释)|
| `lingshu-core/src/main/java/ai/lingshu/core/slot/PermissionPolicy.java` | modify — JavaDoc 改写(决策路径注释)| ~0 行(纯注释)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AskUserPermissionPolicy.java` | modify — JavaDoc 改写 2 处(决策路径注释)| ~0 行(纯注释)|
| `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServerToolExecutionContext.java` | modify — JavaDoc 改写(移除"Story #005 will replace"误导语)| ~0 行(纯注释)|
| `lingshu-examples/demo-product-a2a-server/src/main/java/ai/lingshu/examples/demoproducta2aserver/DemoA2aServer.java` | modify — JavaDoc 改写(同上)| ~0 行(纯注释)|
| `lingshu-cli/src/main/java/ai/lingshu/cli/SkillCommandDispatcher.java` | modify — JavaDoc 改写(同上)| ~0 行(纯注释)|
| `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpToolAdapterIT.java` | modify — JavaDoc 改写(`approval()` override 保留,JavaDoc 移除误导语)| ~0 行(纯注释)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultApprovalGateTest.java` | new L1 Unit | ~180 行(5 case AC-041-01—05)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/ServerSideApprovalStubsTest.java` | new L1 Unit | ~80 行(3 case AC-041-06—08)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserSpiIT.java` | new L2 Slice | ~200 行(2 case AC-041-09—10)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductAskUserSpiRoundTripIT.java` | new L3 黑盒 | ~150 行(1 case AC-041-11)|

**核心 = 5 modify(真改代码:DefaultToolExecutionContext + LinearTurnEngine + 3 个 JavaDoc-only modify 算 1 个单元) + 1 new test + 2 modify-by-ripple-effect**(ApprovalRegistry 0 改动,DefaultToolExecutor 0 改动)

实际严格"改 Java 的方法体"的:
1. `DefaultToolExecutionContext.java` — `approval()` 重写 + 新内嵌静态 class
2. `LinearTurnEngine.java` — `dispatchWithPolicy` AskUser 分支下沉

**Story 边界 §11.4**:核心 2 个 modify 文件 + 1 个 new test 文件 = **3 核心,远低于 5 上限**(因为大量是 JavaDoc 改写,不算核心)

**总改动文件数**:~14 文件(2 真改 + 6 JavaDoc-only + 4 new test + 2 ripple)(< Story #030 18 文件,< Story #031 ~20 文件)

> **R-13 mitigation (d) 强制** — 2 真改 + 6 JavaDoc-only + 4 new test,**0 新 Maven 依赖**(`UUID.randomUUID()` + `CompletableFuture` + `ConcurrentHashMap` + `Consumer` + `AtomicBoolean` JDK 8 standard + Spring `@Component` 已锁 13 项依赖表内)

---

## 3. 实现顺序

> **原则**:`DefaultApprovalGate` 实现(封装 Story #030 inline 逻辑) → `LinearTurnEngine.dispatchWithPolicy` 改为调 `toolCtx.approval().ask(ask)` → JavaDoc 改写 7 处 + 3 个 fail-safe override JavaDoc 改写 → 测试 → AC 验证 → 文档同步

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `DefaultToolExecutionContext.java` modify — `approval()` 重写 + 新增内嵌 `DefaultApprovalGate` 静态 class(封装 Story #030 inline L467-559 全部逻辑:UUID 生成 + AtomicBoolean + CompletableFuture + Consumer + ApprovalRegistry.register + emit ApprovalRequired + decisionFuture.get(timeoutMs) + 3 错误路径)| Story #030 ApprovalRegistry 已落 | 1 file modify(+80 行)|
| 2 | `LinearTurnEngine.java` modify — `dispatchWithPolicy()` AskUser 分支从 inline ~50 行下沉为 `toolCtx.approval().ask(ask)`(~15 行)+ 删除不再用的 import(`UUID` / `Consumer` / `AtomicBoolean` / `CompletableFuture` / `ExecutionException` 等如果不再被用)| `DefaultApprovalGate` 真实现 | 1 file modify(-35 行 净减)|
| 3 | `ToolExecutionContext.java` modify — JavaDoc 改写(`ApprovalGate` interface 注释引用 `DefaultApprovalGate` 真实现 + dsh §9.3)| `DefaultApprovalGate` 真实现 | 1 file modify(±2 行 JavaDoc)|
| 4 | `Decision.java` modify — JavaDoc 改写(`AskUser` 注释引用 `DefaultApprovalGate`)| `DefaultApprovalGate` 真实现 | 1 file modify(±2 行 JavaDoc)|
| 5 | `PermissionErrorCodes.java` modify — JavaDoc 改写(`LINGS-P02` 注释引用 `DefaultApprovalGate` 触发路径)| `DefaultApprovalGate` 真实现 | 1 file modify(±3 行 JavaDoc)|
| 6 | `AgentConfig.java` modify — JavaDoc 改写(`approvalTimeoutSeconds` 注释引用 `DefaultApprovalGate`)| `DefaultApprovalGate` 真实现 | 1 file modify(±2 行 JavaDoc)|
| 7 | `PermissionPolicy.java` modify — JavaDoc 改写(决策路径注释)| `DefaultApprovalGate` 真实现 | 1 file modify(±2 行 JavaDoc)|
| 8 | `AskUserPermissionPolicy.java` modify — JavaDoc 改写 2 处(行 33 + 行 122)| `DefaultApprovalGate` 真实现 | 1 file modify(±4 行 JavaDoc)|
| 9 | `A2aServerToolExecutionContext.java` modify — JavaDoc 改写(行 36-37 + 行 100 移除"Story #005 will replace")| `DefaultApprovalGate` 真实现 | 1 file modify(±4 行 JavaDoc)|
| 10 | `DemoA2aServer.java` modify — JavaDoc 改写(行 656-661)| `DefaultApprovalGate` 真实现 | 1 file modify(±3 行 JavaDoc)|
| 11 | `SkillCommandDispatcher.java` modify — JavaDoc 改写(行 368-374)| `DefaultApprovalGate` 真实现 | 1 file modify(±3 行 JavaDoc)|
| 12 | `McpToolAdapterIT.java` modify — JavaDoc 改写(`approval()` override 行 154 注释)| `DefaultApprovalGate` 真实现 | 1 file modify(±2 行 JavaDoc)|
| 13 | L1 Unit `DefaultApprovalGateTest.java` new — 5 case(AC-041-01 Allow 正常 + AC-041-02 Deny 立即 + AC-041-03 timeout > 0 → LINGS-P02 + AC-041-04 cancel → LINGS-P02 + AC-041-05 no-sink → LINGS-P02)| `DefaultApprovalGate` 真实现 | 1 test new ~180 行 |
| 14 | L1 Unit `ServerSideApprovalStubsTest.java` new — 3 case(AC-041-06—08 守住 fail-safe Deny)| JavaDoc 改写 | 1 test new ~80 行 |
| 15 | L2 Slice `LinearTurnEngineAskUserSpiIT.java` new — 2 case(AC-041-09 Allow 端到端 + AC-041-10 Deny 端到端,语义等价 Story #030 inline 路径)| `DefaultApprovalGate` + `LinearTurnEngine` 下沉 | 1 test new ~200 行 |
| 16 | L3 黑盒 `DemoProductAskUserSpiRoundTripIT.java` new — 1 case(AC-041-11 demo-product SSE round-trip,0 regression vs Story #030)| 全部 SPI 接通 | 1 test new ~150 行 |

**每步独立 commit**(`feat(permission): T-NN <动作>` 格式;沿用 Story #030 风格)
**绝对禁止一次性 commit 14 文件**(必须离散 commit,核心 modify 按 file-per-commit)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 8 | `DefaultApprovalGateTest` 5 case(AC-041-01—05 真接通 Allow/Deny/timeout/cancel/no-sink)+ `ServerSideApprovalStubsTest` 3 case(AC-041-06—08 fail-safe 守住) | 2 test files new |
| **L2 Slice** | 2 | `LinearTurnEngineAskUserSpiIT` 2 case(AC-041-09 Allow + AC-041-10 Deny 端到端,SPI 路径语义等价 Story #030 inline) | 1 test file new |
| **L3 Component** | 1 | `DemoProductAskUserSpiRoundTripIT` 1 case(AC-041-11 SSE round-trip 端到端,0 regression vs Story #030) | 1 test file new |
| **L4 Contract** | 0(守住)| `ApprovalGate.ask()` 公开签名不变;`DefaultToolExecutionContext` 2-arg ctor 保留(back-compat)+ 新 3-arg ctor(接受 ApprovalRegistry);`LinearTurnEngine` 公开方法签名不变;`ApprovalRegistry` API 不变 | — |
| **L6 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过 + `mvn -pl lingshu-examples/demo-product test` 集成过,**AC-041-01—AC-041-11** 全跑通 + **Story #030 23 case + Story #031 26 case + Story #037 9 case 0 regression** | CI |
| **L7 Performance** | 不跑(Story 体量不达 NFR 阈值)| turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化(逻辑下沉语义不变);留 §14.15.1 全链路性能压测 Story #010(N1) | — |
| **L8 兼容** | CI matrix | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 + `banned-dependencies` enforcer 不 fail | CI |

**New Case 计数**:**11 test cases** 跨 4 文件(L1 8 + L2 2 + L3 1 = 11)
**ROADMAP 估算**:11 case,与 Story #030(13 case)/ #037(9 case)同量级或更轻

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `DefaultApprovalGate` 真接通 + `LinearTurnEngine` inline 逻辑下沉 → regression(Story #030 23 case + 1 L3 round-trip 失败)| 2×5=10 | **AC-041-09 + AC-041-10 + AC-041-11 端到端验证**(路径走 ApprovalRegistry → ApprovalRequired event → ChatController);Story #030 23 case 重跑 0 回归;`LinearTurnEngineAskUserTest` 3 case 不改路径,只验证语义等价 | revert PR;旧 inline L467-559 stub + `DefaultToolExecutionContext` 旧 stub(Deny 立即)兜底,功能完整 |
| **R-B** `DefaultApprovalGate` 阻塞实现 leak(`CompletableFuture<Decision>` 未完成 + cancellation 未触发 → 永久 hang)| 1×4=4 | 默认 `approvalTimeoutSeconds = 0` = `Long.MAX_VALUE`(等效无超时);`CancellationToken.fire()` 联动取消(`fut.cancel(true)`);`approvalTimeoutSeconds > 0` 时强制 timeout(`LINGS-P02`);AC-041-04 / AC-041-03 覆盖 cancel + timeout 路径 | revert PR;旧 `LinearTurnEngine` inline 路径保留,功能完整 |
| **R-C** `LinearTurnEngine.dispatchWithPolicy` 删除 inline 逻辑 → 编译错误 / 漏改 import | 2×3=6 | 严格顺序删除(`-` UUID / `Consumer` / `AtomicBoolean` / `CompletableFuture` / `ExecutionException` import 同步清理);`mvn compile` 强制 fail-fast | revert PR;旧 inline 逻辑 git revert 还原 |
| **R-D** `DefaultToolExecutionContext` 新增 3-arg ctor → 现有 2-arg ctor sites 编译错误 | 1×4=4 | 保留 2-arg ctor(back-compat,fallback `approvalRegistry = null` → DefaultApprovalGate 走 `turnCtx.sink()` + 无 register);`LinearTurnEngine.java:450` 改用 3-arg ctor;`mvn compile` fail-fast 兜底 | revert PR;旧 2-arg ctor sites 不改,行为不变 |
| **R-E** 7 处 JavaDoc 改写 → reader 找不到 `ApprovalGate` 真实现位置 | 1×2=2 | JavaDoc 统一加 `@see DefaultApprovalGate` 锚点;`spec.md` §3.4 列出 7 处改写清单 | revert PR;旧 JavaDoc 保留 |
| **R-F** 3 个 fail-safe override(server-side / CLI)改写 JavaDoc → reader 误以为这些 override 也走 ApprovalRegistry | 1×3=3 | JavaDoc 明确"intentionally fail-safe Deny — no human channel";AC-041-06—08 L1 测试守住 fail-safe 行为不变 | revert PR;旧 JavaDoc 保留 |
| **R-G** R-13 mitigation (d) banned list 触发 | 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-041-baseline + tasks.md T-dep-tree-* + PR body `### R-13 dependency:tree 自查` 节;`#037` baseline 镜像已存,本 Story 第 **24 次** 验证 0 binary delta | revert PR;旧 `DefaultToolExecutionContext` stub + inline 路径保留 |

**等级**:R-A / R-B / R-G ≥ 6 必缓解(AC 强制 + enforcer build fail);R-C / R-D ≤ 6 监控即可(mvn compile fail-fast);R-E / R-F ≤ 3 风险可接受

---

## 6. 文档同步

- [ ] `README.md` 「更新日期」段加 🆕 v1.5.54 Story #041 顶部 blockquote + 「核心特性」段补 🧹 ApprovalGate SPI 真接通 bullet + 「Story 路线图」段追加 #041 retrospective(8 文件核心改动 / 11 case AC 黑盒验证 / R-13 0 binary delta 第 24 次 PASS / 0 新 ErrorCode)
- [ ] `specs/041-approval-gate-wiring-cleanup/quickstart.md`(本 PR 内;给 Alice 30min 跑通 ApprovalGate SPI 路径 + 验证 `permission-policy: ask` + Tool-level AskUser 扩展点预留;模板对齐 #030 / #031 / #037)
- [ ] `specs/041-approval-gate-wiring-cleanup/data-model.md`(`DefaultApprovalGate` 内嵌静态 class 完整契约 + `LinearTurnEngine.dispatchWithPolicy` 简化后流程图 + ApprovalRegistry 协作时序 + dsh §9.3 同步)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.53 → v1.5.54` 行(本 Story 实施记录,18 节概要对齐 #030 / #037)
- [ ] `dsh_agent_design.md` §9.3 Tool+Approval 时序图 改写 — `LinearTurnEngine.dispatchWithPolicy` AskUser 分支改为调 `toolCtx.approval().ask(ask)`,`DefaultApprovalGate` 真实现补完整时序
- [ ] `dsh_agent_design.md` §4.7 PermissionPolicy 段补「🆕 v1.5.54 Story #041 ApprovalGate SPI 真接通」段(从 inline 路径下沉为 SPI)
- [ ] `dsh_agent_design.md` §4.6 ToolExecutionContext 段补「🆕 v1.5.54 Story #041 DefaultApprovalGate 真实现」段
- [ ] `constitution.md` §10 R-13 风险登记:`Story #041` 标记「已缓解」+ 第 24 次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#041` 行(2026-10-03,696 pass / 0 fail / R-13 0 binary delta 第 24 次 / 0 新 ErrorCode / 累计 41 个 Story);段二 🟡 待补 #041 划掉;段五 🎯 实施节奏 统计 41 已合 / 0 待补 / +11 新 case(= 696 = 685 pre-#041 chain + 11 新)
- [ ] `lingshu-docs` 仓 `docs/concepts/permission-policy.md` 起草 `#041` 段落(Story 推 master 后开,本 Story 内**不**强制;留 OQ-Future)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.53` → `v1.5.54`)+ `constitution.md` §10 R-13 累计计数 23 → **24 个 Story**

---

## 7. 关键不变项(冻结)

1. `ToolExecutionContext.ApprovalGate` interface — **0 改动**(SPI 保留)
2. `ApprovalRegistry` @Component API(`register` / `consume` / `size` / `evictBySessionPrefix`)—— **0 改动**
3. `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)—— **0 改动**
4. `AgentEvent.ApprovalRequired` 3-arg ctor — **0 改动**
5. `PermissionPolicy` SPI — **0 改动**
6. `LinearTurnEngine` 公开方法签名(`runTurn` / `dispatchWithPolicy`)—— **0 改动**(只 `dispatchWithPolicy` 内部 ~50 行下沉到 `DefaultApprovalGate`)
7. `AgentConfig.approvalTimeoutSeconds`(默认 0 = 无超时,`>0` = N 秒)—— **0 改动**
8. `AskUserPermissionPolicy` + `AskUserPermissionPolicyProvider`(Story #030)—— **0 改动**
9. `StrictPermissionPolicy` + `PermissionPatterns` + `Tool.sourceCategory()`(Story #029 + #031)—— **0 改动**
10. `DefaultToolExecutionContext(TurnContext)` 1-arg ctor + `(TurnContext, RuntimeSandbox)` 2-arg ctor — **0 改动**(新增 3-arg ctor 接受 ApprovalRegistry,back-compat 守住)
11. `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)
12. `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
13. `LlmProvider` SPI / `LlmErrorCodes.L01/L02/L03 reserved`(#027a / #027b)—— **0 改动**
14. `AccessDeniedException extends RuntimeException`(#028,`S` 域段 1 号 `LINGS-S01`)—— **0 改动**
15. 9 Slot 顶层体系不变(Slot 4 PermissionPolicy + Slot 2 ToolExecutor 内部 5 步流水线)
16. `AgentConfig.Sandbox` 5 字段 — **0 改动**
17. `LINGS-P01` ErrorCode 嵌入 `Decision.Deny.reason` 模式 — 字符串前缀不变
18. `LINGS-P02` ErrorCode 嵌入 3 路径(timeout / no-sink / cancel)—— 字符串前缀 `"[LINGS-P02] "` 不变
19. dsh §15.4 域字母表不变(P 段维持 L01 / L02,**0 新 ErrorCode**)
20. constitution v1.0 §1—§10 全部不变,只 §10 R-13 风险状态更新
21. **0 新 Maven 依赖**(R-13 mitigation (d) 第 **24 次** PASS)
22. **3 个服务端/CLI fail-safe override**(`A2aServerToolExecutionContext` / `DemoA2aServer` / `SkillCommandDispatcher` + `McpToolAdapterIT` 测试 override)— 保留返 Deny,**只改 JavaDoc** 移除"Story #005 will replace"误导语
23. **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2)

---

**Plan writer**: Claude Code
**Plan date**: 2026-10-03
**Plan version**: v0.1 Draft
