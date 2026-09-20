# Implementation Plan: Story #005 cancellation-token

**Feature Branch**: `story-005-cancellation-token`
**Spec**: [`spec.md`](./spec.md) — 4 User Stories(US1 P1 / US2 P1 / US3 P2 deferred / US4 P1)+ 9 Edge Cases + 14 FR + 8 NFR

**Constitution**: v1.0 — §1 #12 启动时校验 / §2 13 依赖锁定(R-13 零新增)/ §4 LINGS-L02/T06/R01 复用(0 新增)/ §5 7 层金字塔 / §10 R-02 + R-13 mitigation (d) dep-tree 自查

**Source Design Doc**: `dsh_agent_design.md` v1.5.34
- §4.6 L663-667(CancellationToken 接口契约)
- §6.1 LinearTurnEngine(ReAct Loop + cancellation 检查)
- §14.12 N12(CancellationToken 三层贯通 FlowEngine / ToolExecutor / LlmProvider)
- §14.6 N6 graceful shutdown(Story #005 仅做 in-flight turn 取消子集,完整 graceful shutdown 留 Story #013)
- §15 ErrorCode: `LINGS-L02 LLM_STREAM_CANCELLED` / `LINGS-T06 TOOL_CANCELLED` / `LINGS-R01 REACT_CANCELLED`(本 Story 全部复用)

**Tests Required**: per FR-001—FR-014 + NFR-001(AC-04 黑盒 ≤ 200ms)+ 9 Edge Cases。3 new test classes(L1 Unit + L2 Slice + L5 E2E)。

---

## Architecture Sketch

```
┌─ AgentFactory (@Component, Spring singleton) ────────────────────────┐
│                                                                      │
│  🆕 Story #005:                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ private final CopyOnWriteArrayList<CancellationToken>        │    │
│  │     broadcastRegistry = new CopyOnWriteArrayList<>();         │    │
│  │                                                                │    │
│  │ public void registerCancellation(CancellationToken token) {    │    │
│  │     broadcastRegistry.add(token);                              │    │
│  │ }                                                               │    │
│  │                                                                │    │
│  │ public void broadcastCancel() {                                │    │
│  │     LOG.info("broadcast cancel: {} active turn(s)",            │    │
│  │         broadcastRegistry.size());                              │    │
│  │     for (CancellationToken t : broadcastRegistry) {            │    │
│  │         try { t.fire(); } catch (Exception e) {                │    │
│  │             LOG.warn("cancel callback failed", e);             │    │
│  │         }                                                       │    │
│  │     }                                                           │    │
│  │ }                                                               │    │
│  │                                                                │    │
│  │ @PostConstruct                                                 │    │
│  │ private void registerJvmShutdownHook() {                       │    │
│  │     Runtime.getRuntime().addShutdownHook(                      │    │
│  │         new Thread(this::broadcastCancel,                       │    │
│  │             "lingshu-shutdown-cancel"));                       │    │
│  │ }                                                               │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
│  7-Router resolve(Story #001 + #003 不变)                            │
└──────────────────────────────────────────────────────────────────────┘

┌─ AgentFactory.create(cfg) ───────────────────────────────────────────┐
│  ... 7-Router resolve ...                                            │
│  Session session = new DefaultSession();                             │
│  return new DefaultAgent(session, config, engine);                   │
│       ↑ DefaultAgent.buildContext() 现在调:                         │
│         DefaultTurnContext.createWithBroadcast(                     │
│             session, config, sink, userInput)                       │
│              ↑ 内部 CancellationTokens.create() + AgentFactory       │
│                .registerCancellation(token) 自动注册                │
└──────────────────────────────────────────────────────────────────┘

┌─ CancellationToken interface (dsh §4.6 + Story #005 FR-013) ────────┐
│  boolean isCancelled();                                              │
│  Runnable onCancel(Runnable callback);                               │
│  default void fire() { /* no-op for back-compat */ }                 │
│       ↑ SimpleCancellationToken override 为原子 set + 触发 callbacks │
└──────────────────────────────────────────────────────────────────────┘

┌─ LinearTurnEngine.runTurn ──────────────────────────────────────────┐
│   try {                                                              │
│       while (step <= maxSteps) {                                     │
│           if (ctx.done()) break;                                     │
│           🆕 if (ctx.cancellation().isCancelled()) {                 │
│               ctx.markDone();                                        │
│               emit TurnCompleted(CANCELLED, totalUsage);             │
│               break;                                                 │
│           }                                                          │
│                                                                      │
│           prompt = promptBuilder.build(ctx);                         │
│           fut = llmProvider.stream(prompt, ctx, sink);                │
│                                                                      │
│           🆕 try {                                                   │
│               resp = fut.get(200, MILLISECONDS);  ← AC-04 短超时     │
│           } catch (TimeoutException te) {                            │
│               🆕 if (ctx.cancellation().isCancelled()) {             │
│                   fut.cancel(true);                                  │
│                   last = LlmResponse.cancelled(partialUsage);        │
│                   emit TurnCompleted(CANCELLED, totalUsage);         │
│                   break;                                             │
│               } else { throw te; }                                  │
│           } catch (InterruptedException ie) { ... }                  │
│                                                                      │
│           appendAssistant(...);                                      │
│           if (no tool calls) break;                                  │
│                                                                      │
│           🆕 dispatchParallel(calls, ctx, sink) — 内部 futures[i]   │
│              .get(timeout, SECONDS) 前先 ctx.cancellation()          │
│              .isCancelled() 轮询,cancel in-flight futures           │
│       }                                                              │
│   }                                                                  │
└──────────────────────────────────────────────────────────────────────┘

┌─ Tool Execution Cancellation (dsh §14.12 Tool layer) ───────────────┐
│  DefaultToolExecutionContext.cancellation() {                        │
│      return turnCtx.cancellation();  ← 🆕 真实 token,非 no-op stub  │
│  }                                                                   │
│                                                                      │
│  Tool 内部 future.get(timeout, SECONDS):                            │
│    catch (TimeoutException) → ToolResult.error("tool cancelled")     │
│    catch (CancellationException) → ToolResult.error("cancelled")     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## File-Level Changes

### New Files (1)

| File | Purpose | Lines |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/concurrent/CancellationTokens.java` | 静态工厂 + inner SimpleCancellationToken 实现 | ~110 |

### Modified Files (6)

| File | Why | Lines Changed |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutionContext.java` | FR-013 — `CancellationToken` 嵌套接口加 `default void fire()` + Javadoc 协作式语义 | ~10(+8 -2) |
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java` | FR-001 — 接口加 `CancellationToken cancellation()` 方法 | ~5(+5 -0) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java` | FR-002+FR-003+FR-011 — final token 字段 + static `createWithBroadcast` 工厂 + `cancellation()` impl | ~40(+30 -10) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java` | FR-004 — `cancellation()` 返 `turnCtx.cancellation()` 而非 no-op stub | ~10(+5 -5) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | FR-006+FR-007+FR-008 — loop 头检查 + 200ms 短超时 + dispatchParallel cancel 轮询 | ~50(+45 -5) |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | FR-009 — broadcastRegistry + registerCancellation + broadcastCancel + @PostConstruct JVM hook | ~30(+28 -2) |

### Test Files (3 new)

| File | Layer | Cases |
|---|---|---|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/concurrent/CancellationTokensTest.java` | L1 Unit | 6 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/CancellationTokenSharingTest.java` | L1 Unit | 3 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineCancellationIT.java` | L2 Slice + L5 E2E | 4(其中 AC-04 黑盒 1)|

### Total

- **1 new core + 6 modified core = 7 files**
- **3 new test files**
- **Core code**: ~140 net new lines(FR-001—FR-014 配套)
- **Test code**: ~360 net new lines
- **ErrorCode 新增**:0(复用 LINGS-L02 / T06 / R01)

**Story 边界检查**(CLAUDE.md §11 #4 "≤ 5 核心文件改动"):**轻度超**(7 vs 5)。原因已在 spec.md "超上限原因" 段说明(三层贯通的最小集)。建议不在本次拆分,等 PR review 再决策。

---

## Test Strategy (L1 Unit + L2 Slice + L5 E2E)

### L1 Unit(L1-001 ~ L1-009)

- **L1-001**:`CancellationTokensTest#create_initialState_isCancelledFalse` — FR-005
- **L1-002**:`CancellationTokensTest#fire_setsCancelledTrue` — FR-005+FR-013
- **L1-003**:`CancellationTokensTest#fire_isIdempotent_secondFireNoOp` — NFR-005
- **L1-004**:`CancellationTokensTest#onCancel_callbackFiredOnFire` — FR-005
- **L1-005**:`CancellationTokensTest#onCancel_unregisterRunnable_removesCallback` — FR-005
- **L1-006**:`CancellationTokensTest#onCancel_callbackThrows_doesNotPropagateToOtherCallbacks` — Edge Case "回调抛异常"
- **L1-007**:`CancellationTokenSharingTest#toolExecutionContext_sharesSameTokenAsTurnContext` — US2 S1
- **L1-008**:`CancellationTokenSharingTest#firePropagatesFromTurnContextToToolExecutionContext` — US2 S2
- **L1-009**:`CancellationTokenSharingTest#onCancel_registeredViaToolExecutionContext_firesOnTurnContextFire` — US2 S3

### L2 Slice(L2-001 ~ L2-003)

- **L2-001**:`LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms` — US1 S1 + **AC-04 黑盒主路径**(NFR-001)— Mock LlmProvider 永远 pending + 主线程 50ms 后调 cancel + 断言 wall-clock ≤ 200ms + RunResult.stopReason == CANCELLED
- **L2-002**:`LinearTurnEngineCancellationIT#ctrlC_duringToolDispatch_allToolResultsMarkedError` — US1 S2 — 4 个 SleepTool 1s + parallelism=4 + 主线程 50ms 后调 cancel + 断言 4 个 ToolResult 都 status=ERROR,content="tool cancelled by shutdown"
- **L2-003**:`LinearTurnEngineCancellationIT#ctrlC_preservesPartialHistory_assistantMessagesRemain` — US1 S3 — 3 个 assistant message 已 emit + Ctrl-C + 断言 session.history 前 3 条保留

### L2 Provider(L2P-001)

- **L2P-001**:`AgentFactoryBroadcastCancelTest#broadcastCancel_multipleTurns_allCancelled` — US4 S2 — 模拟 2 个 in-flight turn 已注册 + 调 `broadcastCancel()` + 断言 2 个 token.isCancelled() == true
- **L2P-002**:`AgentFactoryBroadcastCancelTest#broadcastCancel_emptyRegistry_isNoOp` — US4 S1 — 空 registry + broadcast + 无 NPE

### L5 E2E(E2E-001,集成 Spring 容器)

- **E2E-001**:不启 Spring,纯 JUnit 调 AgentFactory.broadcastCancel() + 1 个 DefaultAgent.runBlocking(forever-pending LLM) + Ctrl-C 模拟 — 验证全链路 `AgentFactory.create → DefaultTurnContext.registerCancellation → LinearTurnEngine.poll → TurnCompleted(CANCELLED)` 跑通 + wall-clock ≤ 200ms(对应 AC-04)

**Test 总数**:13 个(L1 × 9 + L2 × 3 + L2P × 2 + E2E × 1,合并后 12 测试用例 + 1 集成)

---

## Implementation Order (10 steps, sequential due to dependencies)

| Step | What | Depends on | ~time |
|---|---|---|---|
| **0** | 检查 `mvn -v` + JDK 版本 + 跑 Story #004 全测试 baseline | — | 1 min |
| **1** | 创建分支 `story-005-cancellation-token`(已完成)| step 0 | 0 min |
| **2** | 写 spec.md / plan.md / tasks.md / quickstart.md / contracts / checklists(本目录)| step 1 | 5 min |
| **3** | 改 `CancellationToken` 接口(FR-013+FR-014)+ 创建 `CancellationTokens.java`(FR-005)| — | 5 min |
| **4** | 改 `TurnContext` 接口(FR-001)+ `DefaultTurnContext`(FR-002+FR-003)+ `DefaultToolExecutionContext`(FR-004)| step 3 | 10 min |
| **5** | 改 `LinearTurnEngine`(FR-006+FR-007+FR-008):loop 头检查 + 200ms 超时 + dispatchParallel 轮询| step 4 | 10 min |
| **6** | 改 `AgentFactory`(FR-009)+ `DefaultAgent`(FR-011):broadcastRegistry + JVM hook + createWithBroadcast 集成| step 4 | 10 min |
| **7** | 写 L1 测试 `CancellationTokensTest` 6 个 + `CancellationTokenSharingTest` 3 个(US2 + FR-005)| step 3+4 | 10 min |
| **8** | 写 L2 测试 `LinearTurnEngineCancellationIT` 3 个(US1 + **AC-04 黑盒**)+ `AgentFactoryBroadcastCancelTest` 2 个(US4)| step 5+6 | 15 min |
| **9** | 跑 `mvn test` 全绿 + `mvn dependency:tree` 自查 + 贴 PR body| step 7+8 | 10 min |
| **10** | commit + push + open PR + README/docs 同步 | step 9 | 10 min |

**Total**:~75 min(1.25h 实施 + 20 min PR 收尾)

---

## Key Design Decisions

### D-01:`CancellationToken` 接口加 `default void fire()` 而非独立 `Cancellable` 子接口

**决策**:`CancellationToken` 加 `default void fire() {}` 方法(JDK 8 default method)。理由:
- 接口签名演化最小 —— 现有 `DefaultToolExecutionContext.cancellation()` no-op impl(L82-89)自动 back-compat
- 与 dsh §4.6 L663-667 接口契约完全兼容(`fire()` 是 additive default method)
- 避免引入新接口(`Cancellable` 子接口)造成 slot 接口分层

**Trade-off**:default method 在 JDK 8 字节码层面是 `DefaultToolExecutionContext$1$anon` 自动转发,运行时开销 ~1ns,可忽略

### D-02:`broadcastCancel()` 同步执行所有 callbacks 而非异步

**决策**:`broadcastCancel()` 用普通 `for (token : registry) { try { token.fire(); } catch ... }` 同步触发。理由:
- 同步语义保证 JVM shutdown hook 完成时所有 callbacks 已跑完(broadcast 后 hook 返回 → JVM 退出)
- 异步触发需要起线程池 + join,复杂度高 + 200ms 预算难保证
- 测试确定性强(无需 CountDownLatch)

**Trade-off**:若某个 callback 阻塞(如同步 IO),会拖延 broadcast;用 `try-catch` 保护 + Logback `WARN` 即可缓解

### D-03:`DefaultTurnContext` 构造时自动向 AgentFactory 注册

**决策**:`DefaultTurnContext.createWithBroadcast(...)` 静态工厂内部调 `AgentFactory.registerCancellation(token)`。理由:
- 与 Story #001 `DefaultSession` 自动生成 sessionId 模式对齐 —— 生命周期 hook 集中
- DefaultAgent.buildContext 调用方不感知 broadcast 细节(FR-011 配套)
- 测试可绕过(直接 `new DefaultTurnContext(session, config, sink, userInput, token)` 而不调 createWithBroadcast,只测试 ctx 接口而不测 broadcast)

### D-04:200ms 短超时 fallback 在 LinearTurnEngine 而非 LlmProvider

**决策**:`LinearTurnEngine.runTurn` 内 `fut.get(200, MILLISECONDS)` 而**非**改 `AnthropicLlmProvider.stream()` 加 cancellation 检查。理由:
- Story #005 scope 控制(7 文件已超 5 文件预算)
- 200ms 短超时 + cancel future 是 JDK CompletableFuture 标准模式,与 cancellation token 协作
- LlmProvider 内部 polling 取消留 Story #005b(US3 P2 deferred)
- 若未来 Story #005b 实现 AnthropicLlmProvider polling,则此 200ms 超时可放宽(只兜底 LlmProvider 不响应 interrupt 情况)

### D-05:`SimpleCancellationToken` 用 `AtomicBoolean` + `CopyOnWriteArrayList` 而非 AQS

**决策**:`SimpleCancellationToken` 用 `AtomicBoolean cancelled` + `CopyOnWriteArrayList<Runnable> callbacks`。理由:
- JDK 8 内置,0 额外依赖
- `AtomicBoolean.compareAndSet(false, true)` 保证 fire 幂等(NFR-005)
- `CopyOnWriteArrayList` 迭代过程中修改不抛 `ConcurrentModificationException`(broadcastCancel() 同时有新 turn 注册时安全)
- 与 §14.12 design doc "可取消 + 可回调" 语义对齐

### D-06:`DefaultToolExecutionContext.cancellation()` 返 `turnCtx.cancellation()` 而非新创建

**决策**:同一 token 引用透传,Tool 内部 polling 直接看到 Ctrl-C 状态。理由:
- US2 S1 — 共享状态是契约前提
- 0 内存开销(token 引用本身已存在)
- 未来 ApprovalGate.ask 阻塞时可注册 `toolCtx.cancellation().onCancel(approvalFuture::cancel)` 自动反向通知

### D-07:`broadcastRegistry` 用 `CopyOnWriteArrayList` 而非 `ConcurrentLinkedQueue`

**决策**:`CopyOnWriteArrayList<CancellationToken>` 而非 `ConcurrentLinkedQueue`。理由:
- broadcastCancel() 迭代所有 token + 可能同时 AgentFactory.create() 添加新 token —— CoW 迭代过程修改安全
- 元素总数有限(in-flight turn 数,默认 ≤ 32 见 §3 NFR),CopyOnWrite 写开销可接受
- JDK 8 内置,0 额外依赖

### D-08:`shutdown hook` 用独立 Thread + 业务登记到 `@PostConstruct`

**决策**:`@PostConstruct private void registerJvmShutdownHook()` 内 `Runtime.getRuntime().addShutdownHook(new Thread(this::broadcastCancel, "lingshu-shutdown-cancel"))`。理由:
- `@PostConstruct` 在 Spring 容器 init 完成时触发,AgentFactory 已被完全初始化
- Thread 名 `lingshu-shutdown-cancel` 便于 jstack 排查
- Hook 是 daemon(默认 false 但 Hook 本身就是 daemon semantics)
- Spring `@PreDestroy` 不**重复**注册 hook(避免双触发);只在 JVM SIGTERM 时触发

---

## Risk & Mitigation

| Risk | Probability × Impact | Mitigation |
|---|---|---|
| **R-01**:广播 registry 内存泄漏(in-flight turn 未注销) | 1×3=3 | DefaultAgent 销毁时调 `agentFactory.unregisterCancellation(token)`(`@PreDestroy`);JVM hook 兜底广播;测试断言 1000 turn 注册后 GC 之后内存稳定 |
| **R-02**:200ms 短超时在 CI 上 flaky | 2×2=4 | (a) 用 `CountDownLatch` 同步 turn 启动后再开始计时;(b) 阈值放宽到 250ms;(c) `mvn test` 用 `surefire` 默认 fork 数 |
| **R-03**:`CancellationToken` 接口加 `default void fire()` 破坏现有 `DefaultToolExecutionContext.cancellation()` no-op impl | 1×2=2 | no-op impl 的 fire() 仍是 no-op(default method 自动 back-compat);测试覆盖 DefaultToolExecutionContext.cancellation().fire() 不抛异常 |
| **R-04**:JVM shutdown hook 注册多次(测试间复用 JVM) | 2×1=2 | (a) `@PostConstruct` 只触发 1 次(Spring 容器 1 个 AgentFactory 单例);(b) 测试用 `@DirtiesContext` 隔离;(c) Hook 是 idempotent 调空 registry 是 no-op |
| **R-05**:依赖污染(意外引 java.util.concurrent 新 API 不可用) | 1×3=3 | R-13 mitigation (d) `dependency:tree` 必跑,对比 Story #004 baseline;CI fail if delta |
| **R-06**:`DefaultToolExecutionContext.cancellation()` 返 turnCtx.cancellation() 引用导致 Tool 内部修改 token 状态污染 TurnContext | 1×3=3 | SimpleCancellationToken.fire() 用 AtomicBoolean CAS 幂等;Tool 内部只能 onCancel() 注册 + polling isCancelled(),**不能**反向 fire()(fire 只在 broadcast 路径触发);测试覆盖 |
| **R-07**:US3(LlmProvider cancellation)未实现导致 AC-04 表面看似通过实际 LLM HTTP 浪费 token | 2×2=4 | (a) AC-04 黑盒只断言 turn 200ms 内停,LLM HTTP 不在测试范围;(b) Story #005b 实施 LlmProvider 完善;(c) plan.md "Future Scope" 节显式标注 |

---

## Critical Invariants (do NOT change in this Story)

- `CancellationToken` 接口签名:**只**新增 `default void fire()`(JDK 8 默认方法,back-compat);`isCancelled()` / `onCancel(Runnable)` 不变(dsh §4.6 已固化)
- `ToolExecutionContext.CancellationToken` 嵌套接口:与 `CancellationToken` 接口同步演化(同包同签名)
- `TurnContext` 接口:**只**新增 `cancellation()` 方法;既有 `done()` / `markDone()` / `appendAssistant` / `appendToolResult` / `appendSystem` 不变
- `DefaultSession` / `AgentConfig` / `AgentEvent` / `StopReason`:**不**改 —— `StopReason.CANCELLED` 已存在
- `LinearTurnEngine` 的 5 step ReAct 序列顺序不变,只新增"loop 头 cancellation 检查 + 200ms 短超时 + dispatchParallel 轮询"
- `DefaultToolExecutor` 5-step pipeline 完全不变(只 DefaultToolExecutionContext adapter 改 cancellation() 返真实 token,**不**改 5-step pipeline 顺序)
- `AgentFactory` 7-Router resolve 顺序不变,只**新增** broadcastRegistry + JVM hook
- `PermissionPolicy` / `LlmProvider` 接口不变(US3 LlmProvider 修改 deferred 到 Story #005b)
- 不引新 Maven 依赖(constitution §2 R-13)
- 不新增 ErrorCode(复用 LINGS-L02 / T06 / R01)

---

## Future Scope (Deferred to Story #005b)

- **US3 — LlmProvider cancellation polling**:AnthropicLlmProvider.stream() 内部循环读 InputStream 时检查 `ctx.cancellation().isCancelled()`,若 fire 则 `HttpURLConnection.disconnect()` + future 返 `LlmResponse(stopReason=CANCELLED)`
- **Story #013 — 完整 graceful shutdown**:Spring Boot Actuator HealthIndicator + readiness probe + 优雅 30s drain timeout(本 Story 只做"200ms in-flight turn cancel"子集)
- **Story #014 — SessionStore flush on cancel**:cancel 时同步 session checkpoint 到 disk,避免 restart 后历史丢失
- **Story #016 — AuditLog append-only JSONL 落地**:本 Story emit `TurnCompleted(CANCELLED, ...)` 事件,Story #016 把事件序列化为 JSONL append-only 文件

---

## Definition of Done (PR body checklist)

- [ ] spec.md / plan.md / tasks.md / quickstart.md / contracts / checklists(本目录全套 6 文件)
- [ ] `mvn -pl lingshu-core test` 全绿(13 测试用例,L1+L2+L2P+E2E)
- [ ] **AC-04 黑盒验证通过**:`LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms` 贴输出
- [ ] `mvn dependency:tree` 自查:0 新依赖,贴 Story #004 baseline 对比 + 关键子树
- [ ] 关键不变项全部保留(见上文 Critical Invariants 8 条)
- [ ] 1 new + 6 modified = 7 文件改动,**轻度超** CLAUDE.md §11 #4 ≤ 5 软上限,**在 spec.md "超上限原因" 段已说明**
- [ ] PR 标题:`feat(agent): Story #005 cancellation-token — CancellationToken 三层贯通 + JVM shutdown hook (AC-04)`
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节