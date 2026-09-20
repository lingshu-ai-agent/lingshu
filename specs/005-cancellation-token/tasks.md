# Tasks: Story #005 cancellation-token

**Input**: Design documents from `/specs/005-cancellation-token/`
- spec.md(4 User Stories US1/US2/US3-deferred/US4 + 9 Edge Cases + FR-001—FR-014 + NFR-001—NFR-008)
- plan.md(10-step implementation order + 1 new + 6 modified files + 3 new test files + 13 test cases L1+L2+L2P+E2E)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #004 tool-parallel-dispatch **merged**(82bb846 — provides LinearTurnEngine with dispatchParallel + ToolExecutorConfig + DefaultToolExecutionContext no-op cancellation stub)

**Tests**: Required per FR-001—FR-014 + NFR-001(AC-04 black-box ≤ 200ms)+ 9 Edge Cases。13 test cases across L1/L2/L2P/E2E。

**Constitution**: v1.0 — §1 #12 启动时校验 / §2 13 依赖锁定(R-13 零新增)/ §4 LINGS-L02/T06/R01 复用(0 新增)/ §5 7 层金字塔 / §10 R-02 + R-13 mitigation (d) dep-tree 自查

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1, US2, US4 — US3 deferred)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + create branch + capture Story #004 dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+ + Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #004 dependency baseline: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-004-baseline.txt`
- [ ] T003 Create + checkout feature branch `story-005-cancellation-token` from main: ✅ **DONE**(已完成)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core test` exits 0(Story #004 tests all green — pre-implementation sanity)

**Checkpoint**: Setup ready — code modifications can begin.

---

## Phase 2: Foundational — CancellationToken Interface + Impl(US2 base)

**Purpose**: Establish the core CancellationToken contract and concrete impl **before** wiring into any layer

- [ ] T005 [US2] Modify `CancellationToken` nested interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutionContext.java`:
  - Add `default void fire() { /* no-op */ }` method(FR-013)
  - Add Javadoc clarifying "fire() is the cooperative cancellation trigger — impls must poll `isCancelled()` or register callback; this is NOT thread.interrupt()" (FR-014)
  - Document idempotency contract: "Multiple fire() invocations are no-ops after first"
- [ ] T006 [P] [US2] Create `CancellationTokens` utility in `lingshu-core/src/main/java/ai/lingshu/core/impl/concurrent/CancellationTokens.java`:
  - `public final class CancellationTokens`(non-instantiable utility)
  - `private static final class SimpleCancellationToken implements CancellationToken`(FR-005 impl)
  - Fields: `private final AtomicBoolean cancelled = new AtomicBoolean(false)` + `private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>()`
  - `isCancelled()` returns `cancelled.get()`
  - `onCancel(Runnable callback)` adds to callbacks list, returns unregister Runnable (`callbacks.remove(callback)`)
  - `fire()` does `if (cancelled.compareAndSet(false, true)) { for (Runnable cb : callbacks) { try { cb.run(); } catch (Exception e) { /* swallow, log */ } } }`(NFR-005 idempotent + Edge Case "callback 抛异常")
  - `public static CancellationToken create()` factory: `return new SimpleCancellationToken()`
- [ ] T007 Validate compile: `mvn -pl lingshu-core compile` exits 0(CancellationToken interface + CancellationTokens utility compile standalone)

**Checkpoint**: CancellationToken contract + impl ready — TurnContext / DefaultTurnContext / DefaultToolExecutionContext can now wire.

---

## Phase 3: Context Layer — TurnContext + DefaultTurnContext + DefaultToolExecutionContext(US2)

**Purpose**: Expose cancellation token through both context layers with **shared identity**(US2 S1)

- [ ] T008 [US2] Modify `TurnContext` interface in `lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java`:
  - Add method: `CancellationToken cancellation();`(FR-001)
  - Add Javadoc: "Returns the per-turn cancellation token — fires on Ctrl-C / engine.markDone / timeout cascade. Shared identity with ToolExecutionContext.cancellation()."
- [ ] T009 [US2] Modify `DefaultTurnContext` in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java`:
  - Add field: `private final CancellationToken cancellation;`
  - Add constructor overload: `public DefaultTurnContext(Session session, AgentConfig config, Subscriber<? super AgentEvent> sink, String userInput, CancellationToken cancellation)`
  - Existing constructor delegates to new one with `CancellationTokens.create()` for back-compat
  - Add static factory: `public static DefaultTurnContext createWithBroadcast(Session session, AgentConfig config, Subscriber<? super AgentEvent> sink, String userInput)`:
    - `CancellationToken token = CancellationTokens.create();`
    - `AgentFactory.registerCancellation(token);`  ← 🆕 Story #005 auto-register
    - `return new DefaultTurnContext(session, config, sink, userInput, token);`
  - Implement `cancellation()` → `return cancellation`
- [ ] T010 [US2] Modify `DefaultToolExecutionContext` in `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java`:
  - Replace `cancellation()` method body: `return turnCtx.cancellation();` instead of new anonymous no-op(FR-004)
  - Remove the no-op `Runnable onCancel(...)` stub(FR-004)
  - Add Javadoc: "Delegates to TurnContext — token identity is shared, so Ctrl-C propagates to in-flight Tool calls"
- [ ] T011 Validate compile: `mvn -pl lingshu-core compile` exits 0(TurnContext + DefaultTurnContext + DefaultToolExecutionContext wired)

**Checkpoint**: Context layer ready — LinearTurnEngine + AgentFactory can now wire.

---

## Phase 4: L1 Unit Tests — CancellationToken Contract(US2 + Edge Cases)

**Purpose**: Verify CancellationTokens impl + context sharing BEFORE engine integration

- [ ] T012 [P] [US2] Write `CancellationTokensTest` in `lingshu-core/src/test/java/ai/lingshu/core/impl/concurrent/CancellationTokensTest.java`:
  - `L1-001`: `#create_initialState_isCancelledFalse` — FR-005
  - `L1-002`: `#fire_setsCancelledTrue` — FR-005+FR-013
  - `L1-003`: `#fire_isIdempotent_secondFireNoOp` — NFR-005
  - `L1-004`: `#onCancel_callbackFiredOnFire` — FR-005
  - `L1-005`: `#onCancel_unregisterRunnable_removesCallback` — FR-005
  - `L1-006`: `#onCancel_callbackThrows_doesNotPropagateToOtherCallbacks` — Edge Case "回调抛异常"
- [ ] T013 [P] [US2] Write `CancellationTokenSharingTest` in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/CancellationTokenSharingTest.java`:
  - `L1-007`: `#toolExecutionContext_sharesSameTokenAsTurnContext` — US2 S1(FR-004)
  - `L1-008`: `#firePropagatesFromTurnContextToToolExecutionContext` — US2 S2
  - `L1-009`: `#onCancel_registeredViaToolExecutionContext_firesOnTurnContextFire` — US2 S3
- [ ] T014 Validate L1 tests pass: `mvn test -Dtest='CancellationTokensTest,CancellationTokenSharingTest'` exits 0(9 cases green)

**Checkpoint**: CancellationToken + context sharing verified — engine integration safe.

---

## Phase 5: LinearTurnEngine — Cancellation Polling + 200ms Timeout(US1)

**Purpose**: Core engine change — FlowEngine layer cancellation check + AC-04 200ms short timeout fallback

- [ ] T015 [US1] Modify `LinearTurnEngine.runTurn` in `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`:
  - **At loop head**(after existing `if (ctx.done()) break`):
    - Add `if (ctx.cancellation().isCancelled()) { ctx.markDone(); sink.onNext(new AgentEvent.TurnCompleted(StopReason.CANCELLED, totalUsage)); LOG.info("cancelled at step={}", step); break; }`(FR-006)
  - **Replace `fut.get()` with 200ms timeout fallback**(replace lines 113-121):
    - Wrap in try with `fut.get(200, MILLISECONDS)` instead of unbounded `fut.get()`(FR-007)
    - `catch (TimeoutException te)` block:
      - First check `if (ctx.cancellation().isCancelled())`: `fut.cancel(true); last = null; sink.onNext(new AgentEvent.TurnCompleted(StopReason.CANCELLED, totalUsage)); break;`
      - Else: rethrow as `RuntimeException("LLM call timed out after 200ms — cancelled? false", te)`(preserves Story #004 behavior for non-cancel timeouts)
    - Keep existing `InterruptedException` / `ExecutionException` catch blocks unchanged
- [ ] T016 [US1] Modify `LinearTurnEngine.dispatchParallel` in `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`:
  - In the `for (int i = 0; i < calls.size(); i++)` result collection loop(after `futures[i] = CompletableFuture.supplyAsync(...)`):
    - Before each `futures[i].get(timeoutSec, ...)`, add: `if (ctx.cancellation().isCancelled()) { futures[i].cancel(true); results[i] = ToolResult.builder().status(ERROR).toolUseId(calls.get(i).getId()).content("tool cancelled by shutdown").isError(true).build(); continue; }`(FR-008 polling)
  - Add new `catch (CancellationException ce)` after `TimeoutException` catch: same `ToolResult.error(id, "tool cancelled: " + ce.getMessage())` shape(FR-008)
- [ ] T017 Validate compile: `mvn -pl lingshu-core compile` exits 0(LinearTurnEngine with 3 cancellation wiring points compiles)

**Checkpoint**: LinearTurnEngine cancellation wired — AgentFactory hook + DefaultAgent wiring remain.

---

## Phase 6: AgentFactory + DefaultAgent — Broadcast Registry + JVM Hook(US4)

**Purpose**: Wire the JVM shutdown hook trigger path so Ctrl-C reaches all in-flight turns

- [ ] T018 [US4] Modify `AgentFactory` in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java`:
  - Add field: `private final CopyOnWriteArrayList<CancellationToken> broadcastRegistry = new CopyOnWriteArrayList<>();`(FR-009)
  - Add method: `public void registerCancellation(CancellationToken token) { broadcastRegistry.add(token); }`(FR-009)
  - Add method: `public void broadcastCancel()`:
    - `LOG.info("broadcast cancel: {} active turn(s)", broadcastRegistry.size());`
    - `for (CancellationToken t : broadcastRegistry) { try { t.fire(); } catch (Exception e) { LOG.warn("cancel callback failed for token", e); } }`(NFR-006 sync + Edge Case "回调抛异常")
  - Add `@PostConstruct private void registerJvmShutdownHook()`:
    - `Runtime.getRuntime().addShutdownHook(new Thread(this::broadcastCancel, "lingshu-shutdown-cancel"));`
    - LOG at registration: `"registered JVM shutdown hook for cancellation broadcast"`
  - Update class Javadoc to mention "AC-04: broadcasts cancellation to all in-flight turns on JVM shutdown"
- [ ] T019 [US4] Modify `DefaultAgent.buildContext` in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java`:
  - Replace `return new DefaultTurnContext(session, config, null, userInput);` with:
    - `return DefaultTurnContext.createWithBroadcast(session, config, null, userInput);`(FR-011)
  - This ensures every turn created via `DefaultAgent.runBlocking(...)` / `DefaultAgent.run(...)` / `DefaultAgent.continueWithUserMessage(...)` auto-registers with AgentFactory broadcast registry
- [ ] T020 Validate compile: `mvn -pl lingshu-core compile` exits 0(AgentFactory + DefaultAgent wired + DefaultTurnContext.createWithBroadcast exists)

**Checkpoint**: Full Story #005 wiring complete — Spring boot path can now resolve + cancel.

---

## Phase 7: L2 Slice Tests — LinearTurnEngine Cancellation(US1)

**Purpose**: Verify engine actually stops on cancel with timing assertion(AC-04 black-box main path)

- [ ] T021 [P] [US1] Write `LinearTurnEngineCancellationIT` in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineCancellationIT.java`:
  - `L2-001`: `#ctrlC_stopsInflightTurn_under200ms` — **AC-04 黑盒主路径**(NFR-001):
    - Setup: `EchoLlmProvider` that returns CompletableFuture that never completes(模拟 Anthropic 长请求)
    - Setup: real `AgentFactory` with `CancellationTokens` + registerCancellation working
    - Action: 主线程 submit Runnable `agent.runBlocking("test")` + 50ms 后 `agentFactory.broadcastCancel()`
    - Assert: `agent.runBlocking(...)` wall-clock ≤ 200ms; `RunResult.stopReason == StopReason.CANCELLED`; session.history 有至少 1 条 assistant message(partial)
  - `L2-002`: `#ctrlC_duringToolDispatch_allToolResultsMarkedError` — US1 S2:
    - Setup: 4 个 `SleepTool(name="read_a/b/c/d", sleepMillis=1000)` + `EchoLlmProvider` 第一次返 4 个 tool call,第二次返 END_TURN + `toolParallelism=4`
    - Action: 主线程 submit + 50ms 后 `agentFactory.broadcastCancel()`
    - Assert: 4 个 ToolResult 都 status=ERROR,content 含 "cancelled"; session.history 完整保留 user + assistant(tool_calls); wall-clock ≤ 250ms
  - `L2-003`: `#ctrlC_preservesPartialHistory_assistantMessagesRemain` — US1 S3:
    - Setup: `EchoLlmProvider` 第一次返 `LlmResponse(text="partial-1", toolCalls=null)` + 第二次返 END_TURN
    - Action: 主线程 submit + 50ms 后 `agentFactory.broadcastCancel()`(实际 turn 已在第二次 stream 后 END_TURN,可能不会触发 cancel — 此 case 调整为测 5 步骤 turn 中途 cancel + 验证前 3 条 assistant message 保留)
- [ ] T022 [P] [US4] Write `AgentFactoryBroadcastCancelTest` in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryBroadcastCancelTest.java`:
  - `L2P-001`: `#broadcastCancel_multipleTurns_allCancelled` — US4 S2
  - `L2P-002`: `#broadcastCancel_emptyRegistry_isNoOp` — US4 S1
  - `L2P-003`: `#registerCancellation_multipleTokens_registryAccumulates` — US4 边界
- [ ] T023 Validate L2 tests pass: `mvn test -Dtest='LinearTurnEngineCancellationIT,AgentFactoryBroadcastCancelTest'` exits 0(6 cases green,**AC-04 wall-clock ≤ 200ms 关键**)

**Checkpoint**: AC-04 black-box verified — Story #005 acceptance criterion satisfied.

---

## Phase 8: L5 E2E + Full Test Run + R-13 Self-Check

**Purpose**: End-to-end smoke + ensure no regression in Story #001—#004 tests + capture R-13 evidence

- [ ] T024 [P] Write quick E2E smoke test in `LinearTurnEngineCancellationIT` (extension) or new `AgentFactoryJvmShutdownHookIT.java`:
  - `E2E-001`: 全链路 `AgentFactory.create → DefaultTurnContext.registerCancellation → LinearTurnEngine.poll → TurnCompleted(CANCELLED)` 跑通 + wall-clock ≤ 200ms
  - 用 mock AgentFactory + real DefaultAgent.runBlocking + verify RunResult.stopReason == CANCELLED
- [ ] T025 Run full test suite: `mvn -pl lingshu-core test` exits 0(Story #001 + #002 + #003 + #004 + #005 all green,共 13 + 16 = 29 cases)
- [ ] T026 Run R-13 dep-tree check: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-005-after.txt`
  - `diff /tmp/deps-004-baseline.txt /tmp/deps-005-after.txt` → **0 new dependencies**
  - If diff shows new transitive deps → STOP + investigate + RFC + remove
- [ ] T027 Capture AC-04 verification output: `mvn test -Dtest=LinearTurnEngineCancellationIT -Dsurefire.useFile=false` — copy paste wall-clock assertion result into PR body
- [ ] T028 Commit changes: `git add ... && git commit -m "feat(agent): Story #005 cancellation-token — CancellationToken 三层贯通 + JVM shutdown hook (AC-04)"`

**Checkpoint**: Story #005 complete — ready for PR.

---

## Phase 9: PR + Merge

- [ ] T029 Push branch: `git push origin story-005-cancellation-token`
- [ ] T030 Open PR with body template:
  - Summary(3 bullets)
  - AC-04 black-box output(pasted from T027)
  - Test plan checklist(all 13 cases green)
  - **R-13 dependency:tree 自查** section(paste diff + key tree)
  - Critical invariants(none violated)
  - "Story boundary: 1 new + 6 modified = 7 files" note justifying the slight exceedance
- [ ] T031 After PR review + merge: sync README.md / docs / dsh changelog §13 / constitution §10(R-02 status update if any)

---

## Test Count Summary

| Layer | Count | Files |
|---|---|---|
| L1 Unit(CancellationTokens)| 6 | `CancellationTokensTest.java` |
| L1 Unit(Context sharing)| 3 | `CancellationTokenSharingTest.java` |
| L2 Slice(Engine cancellation)| 3 | `LinearTurnEngineCancellationIT.java` |
| L2 Provider(Broadcast registry)| 3 | `AgentFactoryBroadcastCancelTest.java` |
| L5 E2E(Smoke)| 1 | `AgentFactoryJvmShutdownHookIT.java` |
| **Total** | **16** | **5 test classes** |

---

## Definition of Done(Story #005 complete)

- [x] spec.md / plan.md / tasks.md / quickstart.md / contracts / checklists 6 件套齐全(本目录)
- [ ] 1 new + 6 modified = **7 文件改动**已提交(轻度超 CLAUDE.md §11 #4 ≤ 5 软上限,plan.md 已说明)
- [ ] `mvn -pl lingshu-core test` 全绿(16 测试用例)
- [ ] **AC-04 黑盒验证通过**(`LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms` wall-clock ≤ 200ms,贴输出)
- [ ] `mvn dependency:tree` 自查:0 新依赖
- [ ] 关键不变项全部保留(CancellationToken 接口签名 / TurnContext 接口 / 5-step pipeline / AgentConfig / StopReason / AgentEvent)
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] Branch `story-005-cancellation-token` pushed + PR opened

---

## Anti-Patterns to Avoid(CLAUDE.md §11 + §12)

- ❌ 直接修改 `CancellationToken` 接口的 `isCancelled()` / `onCancel(...)` 签名(违反 dsh §4.6)
- ❌ 用 `Thread.interrupt()` 替代 cancellation token(违反 dsh §14.12 协作式语义)
- ❌ 把 `broadcastCancel()` 写成异步 fire-and-forget(违反 NFR-006 同步语义,200ms 预算无保证)
- ❌ 引 `Reactor` / `RxJava` / `Guava` 等额外依赖(constitution §2 R-13)
- ❌ 用 `var` / `List.of` / sealed interface(JDK 8 约束 CLAUDE.md §3)
- ❌ 在 `LinearTurnEngine.runTurn` 直接 throw RuntimeException 终止 turn(违反 FR-006 — cancel 必须 emit `TurnCompleted(CANCELLED, ...)`,**不**抛)
- ❌ 把 US3 LlmProvider cancellation 拉进 Story #005(Scope creep,留 Story #005b)
- ❌ 把 Story #005 跨到 SessionStore flush on cancel(Scope creep,留 Story #014)
- ❌ 把 Story #005 跨到完整 graceful shutdown(Scope creep,留 Story #013)
- ❌ 把 `DefaultToolExecutionContext.cancellation()` 改成"返回新 token 而非共享 turnCtx.cancellation()"(违反 US2 共享状态契约)
- ❌ `broadcastCancel()` 不 try-catch 包裹 token.fire()(违反 Edge Case "回调抛异常 不阻断其他")