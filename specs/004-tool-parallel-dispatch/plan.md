# Implementation Plan: Story #004 tool-parallel-dispatch

**Feature Branch**: `story-004-tool-parallel-dispatch`

**Spec**: [`spec.md`](./spec.md) — 3 User Stories (US1 P1 / US2 P1 / US3 P1) + 9 Edge Cases + 10 FR + 5 NFR

**Constitution**: v1.0 — §1 #7 编排可扩展 + #11 默认实现位置 / §2 13 依赖锁定 / §4 LINGS-T02/T04/C02 复用 / §5 7 层金字塔(L1+L2+L5 涉及) / §10 R-13 mitigation (d) dep-tree 自查

**Source Design Doc**: `dsh_agent_design.md` v1.5.34 §6.1 LinearTurnEngine(dispatchParallel L3672-3709)/ §4.6 ToolExecutor 5-step pipeline / §4.7 PermissionPolicy / §14.7 共享线程池 / §15 LINGS-T02 / T04 / C02

**Tests Required**: per FR-001—FR-010 + NFR-001(AC-03 加速比 ≥ 3.0×)+ 9 Edge Cases。3 new test classes(其中 1 个 mock LlmProvider)。

---

## Architecture Sketch

```
┌─ AgentFactory.create(cfg) ───────────────────────────────────────┐
│                                                                  │
│  7-Router resolve (Story #001+#003)                              │
│  ┌──────────────┬──────────────┬──────────────┬───────────────┐  │
│  │ PromptBuilder│ LlmProvider  │ ToolExecutor │PermissionPolicy│  │
│  │ Router.resolve│ Router.resolve│ Router.resolve│ Router.resolve│ │
│  └──────┬───────┴──────┬───────┴──────┬───────┴───────┬───────┘  │
│         │              │              │              │           │
│         v              v              v              v           │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ LinearTurnEngine(pb, llm, te, policy, toolPool)  🆕 +3   │   │
│  │   ├── promptBuilder(final)                              │   │
│  │   ├── llmProvider(final)                                │   │
│  │   ├── toolExecutor(final) 🆕 Story #004                 │   │
│  │   ├── permissionPolicy(final) 🆕 Story #004             │   │
│  │   └── toolPool(ExecutorService, final) 🆕 Story #004    │   │
│  └──────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────┘

┌─ LinearTurnEngine.runTurn ────────────────────────────────────────┐
│   while (!ctx.done()) {                                           │
│       step++                                                      │
│       ReasoningStarted(step, maxSteps)                           │
│       prompt = promptBuilder.build(ctx)                          │
│       resp = llmProvider.stream(prompt, ctx, sink)               │
│       if (resp.toolCalls.isEmpty()) → Finish(TurnCompleted)      │
│       results = dispatchParallel(resp.toolCalls, ctx, sink)  🆕  │
│       for (r in results) ctx.appendToolResult(r)                 │
│       ObservationAppended(step, results.length)                  │
│   }                                                               │
│                                                                   │
│   🆕 dispatchParallel(calls, ctx):                               │
│     sem = (parallelism > 0) ? new Semaphore(parallelism) : null  │
│     futures = calls.map { call ->                                │
│       CompletableFuture.supplyAsync({                            │
│         sem?.acquireUninterruptibly()                             │
│         try dispatchWithPolicy(call, ctx, sink) ToolCompleted    │
│         finally sem?.release()                                   │
│       }, toolPool)                                               │
│     }                                                             │
│     results = futures.map { future.get(timeout) }                │
│     return results                                                │
│                                                                   │
│   🆕 dispatchWithPolicy(call, ctx):                              │
│     d = permissionPolicy.check(call, ctx)                        │
│     return switch(d) { Allow→te.dispatch / Deny→error            │
│                        AskUser→error "Story #005" }              │
└───────────────────────────────────────────────────────────────────┘
```

---

## File-Level Changes

### Modified Files (5)

| File | Why | Lines Changed |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | FR-001+FR-002+FR-003+FR-004+FR-009 — 加 3 final 字段 + dispatchParallel + dispatchWithPolicy + 改 runTurn | ~70(+50 -20) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngineProvider.java` | FR-005 — 加 3 Router + ExecutorService 注入 + 改 create() | ~25(+20 -5) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutor.java` | FR-007+FR-008 — dispatch() try-catch 翻译 ToolException → ToolResult.error | ~15(+12 -3) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | wiring — 注入 ToolExecutor/PermissionPolicy 进 LinearTurnEngineProvider(create() 路径**不变**,LinearTurnEngineProvider 内部解析) | 0(不需改 factory) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/config/ToolExecutorConfig.java` 🆕 | FR-006 — `@Configuration` 提供 `agentToolPool` Bean(放 lingshu-core/impl/config/ 与 AgentConfigDefaults 同包) | +30 |

### New Files (4)

| File | Purpose | Lines |
|---|---|---|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineToolDispatchTest.java` | US1 + US3 — mock LlmProvider + 2 tools,断言顺序 + 错误翻译 + Provider 字段注入 | ~180 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineParallelDispatchTest.java` | US2 — 4 mock tool × 1s sleep,断言 AC-03 加速比 ≥ 3.0× | ~200 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/EchoLlmProvider.java` | test fixture — mock LlmProvider,可在测试代码里编程指定多轮响应 | ~80 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/SleepTool.java` | test fixture — mock Tool,execute() 内部 sleep N ms | ~50 |

### Total

- **5 modified** + **4 new test files** = 9 files
- **Core code**: ~110 net new lines(50+20+12+30+0 -2-2)
- **Test code**: ~510 net new lines
- **ErrorCode 新增**:0(复用 LINGS-T02 / T04 / C02)

---

## Test Strategy (L1 Unit + L2 Slice + L3 Component + L5 E2E)

### L1 Unit(L1-001 ~ L1-006)

- **L1-001**:`DefaultToolExecutorTest#dispatch_permissionDenied_returnsErrorResult` — FR-007
- **L1-002**:`DefaultToolExecutorTest#dispatch_toolNotFound_returnsErrorResult` — FR-007
- **L1-003**:`DefaultToolExecutorTest#dispatch_unexpectedException_returnsErrorResult` — FR-008
- **L1-004**:`DefaultToolExecutorTest#dispatch_success_returnsOriginalResult` — 回归(Story #001 行为)
- **L1-005**:`DefaultToolExecutorTest#dispatch_permissionAskUser_returnsErrorResult` — Edge Case "PermissionPolicy.AskUser"
- **L1-006**:`ToolExecutorConfigTest#agentToolPool_isDaemonAndHasLingshuPrefix` — FR-006(NFR-003 线程名)

### L2 Slice(L2-001 ~ L2-005)

- **L2-001**:`LinearTurnEngineToolDispatchTest#sequentialToolDispatch_toolResultsInOrder` — US1 S1 — mock LlmProvider 第 1 轮返 2 tool call,第 2 轮 END_TURN,断言 4 条 history 顺序 User / Assistant / ToolResult / Assistant
- **L2-002**:`LinearTurnEngineToolDispatchTest#toolError_translatedToToolResult_turnContinues` — US1 S2 — 1 个 tool 抛 PermissionDeniedException,断言 history 含 ToolResult(status=ERROR),loop 继续
- **L2-003**:`LinearTurnEngineToolDispatchTest#toolNotFound_translatedToToolResult_turnContinues` — US1 S3
- **L2-004**:`LinearTurnEngineToolDispatchTest#noToolCalls_emitsTurnCompletedImmediately` — 回归(Story #001 demo-empty 路径)
- **L2-005**:`LinearTurnEngineProviderTest#create_returnsEngineWithAllFieldsWired` — US3 S1 — 反射读 final 字段断言非 null

### L2 Slice Parallel(L2P-001 ~ L2P-004)

- **L2P-001**:`LinearTurnEngineParallelDispatchTest#blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s` — US2 S1 — **AC-03 黑盒主路径**
- **L2P-002**:`LinearTurnEngineParallelDispatchTest#parallelism1_wallClock_about4s_serial` — US2 S2 — 串行基线
- **L2P-003**:`LinearTurnEngineParallelDispatchTest#parallelismMinus1_unbounded_allConcurrent` — US2 S3 — 不限并发
- **L2P-004**:`LinearTurnEngineParallelDispatchTest#mixedSuccessAndFailure_resultsPreserveOriginalOrder` — US2 S4 — 异常翻译 + 原顺序归集

### L5 E2E(快速烟囱,E2E-001)

- **E2E-001**:不启 Spring,纯 JUnit 调 `agent.runBlocking("test")`,Mock LlmProvider 立即 END_TURN,验证全链路 `AgentFactory → LinearTurnEngine → LlmProvider → TurnCompleted` 跑通 + wall-clock < 200ms

**Test 总数**:16 个(L1 × 6 + L2 × 5 + L2P × 4 + E2E × 1)

---

## Implementation Order (10 steps, sequential due to dependencies)

| Step | What | Depends on | Estimated |
|---|---|---|---|
| **0** | 检查 `mvn -v` + JDK 版本 + 跑 Story #003 全测试 baseline | — | 1 min |
| **1** | 创建分支 `story-004-tool-parallel-dispatch` | step 0 | 30 s |
| **2** | 创建 test fixtures `EchoLlmProvider` + `SleepTool`(test-only) | — | 5 min |
| **3** | 改造 `DefaultToolExecutor.dispatch()` 加 try-catch 翻译 | — | 5 min |
| **4** | 写 L1 测试 `DefaultToolExecutorTest` 6 个,验证 FR-007/FR-008/Edge Cases | step 3 | 10 min |
| **5** | 创建 `ToolExecutorConfig` + `agentToolPool` Bean | — | 5 min |
| **6** | 改造 `LinearTurnEngine`:加 3 final 字段 + dispatchParallel + dispatchWithPolicy | step 3 | 15 min |
| **7** | 改造 `LinearTurnEngineProvider` 加 3 Router + ExecutorService 注入 | step 5 + step 6 | 10 min |
| **8** | 写 L2 测试 `LinearTurnEngineToolDispatchTest` 5 个 + `LinearTurnEngineProviderTest` 1 个 | step 6 + step 7 | 15 min |
| **9** | 写 L2P 测试 `LinearTurnEngineParallelDispatchTest` 4 个(AC-03 黑盒) | step 6 + step 7 | 15 min |
| **10** | 跑 `mvn test` 全绿 + `mvn dependency:tree` 自查 + 贴 PR body | step 4 + 8 + 9 | 10 min |

**Total**: ~95 min(1.5h 实施 + 30 min PR 收尾)

---

## Key Design Decisions

### D-01:`toolPool` 用 Spring `ThreadPoolTaskExecutor` 还是 JDK `ThreadPoolExecutor`

**决策**:**Spring `ThreadPoolTaskExecutor`**(`@Bean ExecutorService agentToolPool()` 返回)。理由:
- Spring `@Configuration` 自然管理生命周期(`@PreDestroy` 自动调 `shutdown()`)
- 与 Spring Boot Actuator 健康检查天然集成(Story #013 接力时无需重构)
- 实现是 JDK 8 `ThreadPoolExecutor` 的薄包装,**不**引新依赖(constitution §2 13 项内)

### D-02:`dispatchParallel` 用 `CompletableFuture.supplyAsync(..., executor)` 还是 `ExecutorService.submit(...)`

**决策**:`CompletableFuture.supplyAsync(supplier, executor)`(dsh §6.1 L3682 同样模式)。理由:
- `Future.get(timeout, unit)` 异常分支更清晰(`TimeoutException` vs `InterruptedException` vs `ExecutionException` 分开 catch)
- 与 dsh §6.1 设计 1:1 对齐
- Spring `ThreadPoolTaskExecutor` 也实现 `Executor` 接口,直接传入即可

### D-03:DefaultToolExecutor 5-step pipeline 的 step 3-5 何时补齐

**决策**:**Story #004 不补**。理由:
- FR-007/FR-008 翻译是 LinearTurnEngine 集成层的责任,**不是** ToolExecutor 5-step 内部的责任
- 沙箱应用(Step 4)+ checkpoint(Step 5)是 Story #011 Retry / Story #016 Audit 范畴,**分散实施**避免 Story #004 scope 膨胀(CLAUDE.md §11 #4 "≤ 5 个核心文件改动")
- 当前 step 1(Policy) + step 2(Registry lookup) 真实工作,step 3/4/5 直接 `tool.execute()` 是已 stub 行为;Story #004 **只**加 try-catch,不动内部顺序

### D-04:permission AskUser 决策的处理

**决策**:**翻译为 `ToolResult.error(id, "AskUser approval flow is wired in Story #005 follow-up")`**。理由:
- 与 `DefaultToolExecutor` 现有 stub 行为**对齐**(L65-68 注释 "AskUser approval flow is wired in Story #005 follow-up")
- Story #005(cancellation-token)+ Story #008(react-max-steps)是 AskUser 完整回路的实施窗口
- Story #004 **不**新增 ApprovalGate 实现,避免与 Story #005 重复

### D-05:测试 fixture `EchoLlmProvider` 放在 `src/test/java/.../support/` 子包

**决策**:`lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/`。理由:
- 与 `LinearTurnEngineToolDispatchTest` / `LinearTurnEngineParallelDispatchTest` 同 src/test,IDE 一眼看到
- `support/` 子包是测试夹具惯例,避免 `*Test.java` 测试命名冲突
- 复用 Story #001 BlackBoxVerificationTest 模式

---

## Risk & Mitigation

| Risk | Probability × Impact | Mitigation |
|---|---|---|
| **R-01**:`agentToolPool` 线程数配置不当导致 OOM | 1×2=2 | 默认 `availableProcessors() * 2`,文档标注调优指南,留 yml override 钩子(Story #013 接力) |
| **R-02**:CompletableFuture 在高并发下泄漏 | 1×2=2 | 所有 future 都 get(timeout) 或 get(),无 fire-and-forget;ThreadPool 是 daemon,JVM 退出时自动 shutdown |
| **R-03**:dispatchParallel 死锁(比如工具 A 持有锁等工具 B) | 2×3=6 | dsh §6.1 Semaphore 控制并发,**不**依赖工具间顺序;Document 警告:"tool 内部不要 fork-join 调自己"(见 FR-010 注释) |
| **R-04**:AC-03 黑盒测试在 CI 上 flaky(wall-clock 阈值 1.3s 太严) | 2×2=4 | (a) 用 `CountDownLatch` 同步 4 个 tool 启动后再开始计时;(b) 阈值放宽到 1.5s(CI + 宿主机抖动);(c) `mvn test` 用 `surefire` 默认 fork 数,避免 JVM 启动慢 |
| **R-05**:依赖 `spring-ai-bom` 间接污染 transitive | 1×3=3 | R-13 mitigation (d) `dependency:tree` 必跑,对比 Story #003 baseline;CI fail if delta |

---

## Critical Invariants (do NOT change in this Story)

- `Tool` 接口 / `ToolExecutor` 接口 / `ToolException` 子类树完全不变(Story #003 已固化)
- `ToolExecutor` 5-step pipeline **不**新增 step,只调整最外层 try-catch
- `AgentConfig` 字段不变;`toolParallelism` / `toolTimeoutSeconds` 字段在 Story #001 已加,Story #004 **只读**
- `PermissionPolicy` 接口 + `Decision` 子类树完全不变(Story #001 已固化)
- `LinearTurnEngine` 的 5 step ReAct 序列顺序不变,只新增"Action 阶段调 toolExecutor"实现
- `AgentEvent.ToolCompleted` 事件签名不变(Story #001 已固化)
- 不引新 Maven 依赖(constitution §2 R-13)
- 不新增 ErrorCode(复用 LINGS-T02 / T04 / C02)

---

## Definition of Done (PR body checklist)

- [ ] spec.md / plan.md / tasks.md 三件套齐全(本目录)
- [ ] `mvn -pl lingshu-core test` 全绿(16 测试用例)
- [ ] AC-03 黑盒验证通过:`LinearTurnEngineParallelDispatchTest#blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s` 贴输出
- [ ] `mvn dependency:tree` 自查:0 新依赖,贴 Story #003 baseline 对比 + 关键子树
- [ ] 关键不变项全部保留(见上文 Critical Invariants)
- [ ] 5 modified + 4 new test files,合计 9 文件改动,未超 Story 边界(CLAUDE.md §11 #4 ≤ 5 核心 + 4 测试)
- [ ] PR 标题:`feat(agent): Story #004 tool-parallel-dispatch — LinearTurnEngine.dispatchParallel + ToolExecutor/PermissionPolicy 注入 (AC-03)`
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
