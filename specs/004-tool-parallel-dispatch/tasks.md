# Tasks: Story #004 tool-parallel-dispatch

**Input**: Design documents from `/specs/004-tool-parallel-dispatch/`
- spec.md (3 User Stories US1/US2/US3 — all P1; 9 Edge Cases; FR-001—FR-010; NFR-001—NFR-005)
- plan.md (10-step implementation order + 5 modified files + 4 new test files + 16 test cases across L1/L2/L2P/E2E)

**Prerequisites**:
- spec.md ✅ (this directory)
- plan.md ✅ (this directory)
- Story #003 spi-slot-router **merged** (6faaef4 — provides SlotRouter with version compat, Routers ready, contract-version pattern)

**Tests**: Required per spec FR-001—FR-010 + NFR-001 (AC-03 black-box, ≥ 3.0× speedup). 16 test cases across L1/L2/L2P/E2E.

**Constitution**: v1.0 — §1 #7 编排可扩展 / §2 13 依赖锁定(R-13 零新增)/ §4 LINGS-T02/T04/C02 复用(0 新增)/ §10 R-13 mitigation (d) dep-tree 自查

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify environment + create branch + capture Story #003 dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+ + Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #003 dependency baseline: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-003-baseline.txt`
- [ ] T003 Create + checkout feature branch `story-004-tool-parallel-dispatch` from main (or from story-003 if not yet merged): `git checkout -b story-004-tool-parallel-dispatch`
- [ ] T004 Validate baseline: `mvn -pl lingshu-core test` exits 0 (Story #003 tests all green — pre-implementation sanity)

**Checkpoint**: Setup ready — code modifications can begin.

---

## Phase 2: Foundational — Test Fixtures (No Production Code Yet)

**Purpose**: Create test-only support classes (EchoLlmProvider + SleepTool) — these are **test fixtures**, not production code. They enable the L2/L2P tests to be written in parallel with the L1 tests.

- [ ] T005 [P] Create `EchoLlmProvider` test fixture in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/EchoLlmProvider.java`:
  - `class EchoLlmProvider implements LlmProvider`
  - Constructor `EchoLlmProvider(List<LlmResponse> scriptedResponses)` — pre-programmed multi-turn response sequence
  - `stream(prompt, ctx, sink)` returns `CompletableFuture<LlmResponse>` that resolves to the next scripted response (pop from head)
  - `name() = "echo-test"`, `description() = "..."`, `inputSchema() = NullNode`, `execute(...)` is never called
- [ ] T006 [P] Create `SleepTool` test fixture in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/SleepTool.java`:
  - `class SleepTool implements Tool`
  - Constructor `SleepTool(String name, long sleepMillis)` — sleep duration is configurable per tool instance
  - `execute(call, ctx)` does `Thread.sleep(sleepMillis)` then returns `ToolResult.success(call.getId(), "OK")`
  - `name() / description() / inputSchema()` from constructor
- [ ] T007 Validate test fixtures compile: `mvn -pl lingshu-core test-compile` exits 0 (EchoLlmProvider + SleepTool compile standalone)

**Checkpoint**: Test fixtures ready — production code modifications can begin in parallel with test writing.

---

## Phase 3: DefaultToolExecutor Translation (FR-007 + FR-008)

**Purpose**: Translate `ToolException` (and other RuntimeExceptions) into `ToolResult.error` so the LinearTurnEngine doesn't have to try-catch around every `toolExecutor.dispatch()` call. This is a **behavior preservation** change — existing Step 1+2 still work, just with an outer wrapper.

- [ ] T008 [US1] Modify `DefaultToolExecutor.dispatch()` in `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutor.java`:
  - Wrap existing body in try-catch
  - `catch (ToolException e)` → return `ToolResult.builder().status(ERROR).toolUseId(call.getId()).content(e.getMessage()).isError(true).build()`
  - `catch (RuntimeException e)` → return `ToolResult.builder().status(ERROR).toolUseId(call.getId()).content("tool error: " + e.getMessage()).isError(true).build()`
  - Remove `throw` of `ToolException.PermissionDeniedException` and `ToolNotFoundException` (now returned as ToolResult.error)
  - Update Javadoc: "FR-007/FR-008: Exceptions are translated to ToolResult.error, allowing the engine loop to continue past failures"
- [ ] T009 Validate compile: `mvn -pl lingshu-core compile` exits 0 (DefaultToolExecutor translates cleanly)

**Checkpoint**: DefaultToolExecutor now returns ToolResult.error for failures instead of throwing — engine integration can begin.

---

## Phase 4: L1 Unit Tests — DefaultToolExecutor Translation (FR-007 + FR-008)

**Purpose**: Write L1 tests for the translation logic BEFORE the engine integration, so we can verify behavior isolation.

> **NOTE**: Write tests FIRST per TDD style. T009 ensures DefaultToolExecutor compiles; T010—T015 verify behavior.

- [ ] T010 [P] [US1] Write `DefaultToolExecutorTest#dispatch_success_returnsOriginalResult` in `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolExecutorTest.java` (L1-004 regression — existing Step 1+2 behavior unchanged)
- [ ] T011 [P] [US1] Write `DefaultToolExecutorTest#dispatch_permissionDenied_returnsErrorResult` (L1-001, FR-007) — mock PermissionPolicy → Decision.Deny, assert ToolResult{status=ERROR, content="Permission denied: ..."}
- [ ] T012 [P] [US1] Write `DefaultToolExecutorTest#dispatch_toolNotFound_returnsErrorResult` (L1-002, FR-007) — no Tool registered for call.getName(), assert ToolResult{status=ERROR, content="Tool not registered: ..."}
- [ ] T013 [P] [US1] Write `DefaultToolExecutorTest#dispatch_permissionAskUser_returnsErrorResult` (L1-005, Edge Case "AskUser") — PermissionPolicy returns Decision.AskUser, assert ToolResult{status=ERROR, content="AskUser approval flow..."}
- [ ] T014 [P] [US1] Write `DefaultToolExecutorTest#dispatch_unexpectedException_returnsErrorResult` (L1-003, FR-008) — Tool.execute() throws NullPointerException, assert ToolResult{status=ERROR, content="tool error: ..."}
- [ ] T015 Validate L1 tests pass: `mvn test -Dtest=DefaultToolExecutorTest` exits 0 (6 cases green)

**Checkpoint**: DefaultToolExecutor translation verified — engine integration safe.

---

## Phase 5: ToolExecutorConfig — agentToolPool Bean (FR-006 + NFR-003)

**Purpose**: Provide the shared `ExecutorService` for `dispatchParallel` to submit CompletableFutures to. Single Spring `@Configuration` class, lives alongside `AgentConfigDefaults`.

- [ ] T016 [US2] Create `ToolExecutorConfig` in `lingshu-core/src/main/java/ai/lingshu/core/impl/config/ToolExecutorConfig.java`:
  - `@Configuration public class ToolExecutorConfig`
  - `@Bean(name = "agentToolPool") public ExecutorService agentToolPool()`
  - Use `ThreadFactoryBuilder` pattern: `new ThreadFactory() { ... Thread-{N} lingshu-tool-{N} daemon=true }`
  - `new ThreadPoolExecutor(corePoolSize=Runtime.getRuntime().availableProcessors()*2, maxPoolSize=corePoolSize*2, keepAliveTime=60s, queue=new LinkedBlockingQueue<>(256), threadFactory, rejectedExecutionHandler=CallerRunsPolicy)`
  - Add `@PreDestroy` method `agentToolPool.shutdown()` for graceful shutdown
- [ ] T017 Validate compile: `mvn -pl lingshu-core compile` exits 0 (ToolExecutorConfig + bean registration OK)

**Checkpoint**: Shared thread pool ready — LinearTurnEngine can take it as a final field.

---

## Phase 6: LinearTurnEngine — Field + dispatchParallel + dispatchWithPolicy (FR-001 + FR-002 + FR-003 + FR-004 + FR-009)

**Purpose**: The core engine change — add 3 final fields, dispatchParallel method, and replace the hard stop in runTurn.

- [ ] T018 [US1+US2] Modify `LinearTurnEngine` in `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`:
  - Add 3 final fields: `private final ToolExecutor toolExecutor;` `private final PermissionPolicy permissionPolicy;` `private final ExecutorService toolPool;`
  - Update constructor signature: `public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider, ToolExecutor toolExecutor, PermissionPolicy permissionPolicy, ExecutorService toolPool)`
  - Add `if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor must not be null");` (same for permissionPolicy, toolPool)
  - In `runTurn`, replace the hard-stop block (current L98-100) with: `ToolResult[] results = dispatchParallel(resp.getToolCalls(), ctx, sink);` then loop and appendToolResult
- [ ] T019 [US2] Add `private ToolResult[] dispatchParallel(List<ToolCall> calls, TurnContext ctx, Subscriber<? super AgentEvent> sink)` method:
  - `int parallelism = ctx.config().getToolParallelism();`
  - `int timeoutSec = ctx.config().getToolTimeoutSeconds();`
  - `Semaphore sem = (parallelism > 0) ? new Semaphore(parallelism) : null;`
  - Build `CompletableFuture<ToolResult>[] futures` via `CompletableFuture.supplyAsync(supplier, toolPool)`
  - Each supplier: `sem?.acquireUninterruptibly()` → `ToolResult r = dispatchWithPolicy(call, ctx, sink)` → `sink.onNext(new AgentEvent.ToolCompleted(r))` → return r → finally `sem?.release()`
  - Collect `results[i] = futures[i].get(timeoutSec, TimeUnit.SECONDS)` with TimeoutException/InterruptedException/ExecutionException catch → `ToolResult.error(call.getId(), message)`
  - Return results array preserving original order
- [ ] T020 [US1+US3] Add `private ToolResult dispatchWithPolicy(ToolCall call, TurnContext ctx, Subscriber<? super AgentEvent> sink)` method:
  - `Decision d = permissionPolicy.check(call, ctx);`
  - `instanceof Decision.Allow` → return `toolExecutor.dispatch(call, ctx)`
  - `instanceof Decision.Deny` → return `ToolResult.error(call.getId(), ((Decision.Deny) d).getReason())`
  - `instanceof Decision.AskUser` → return `ToolResult.error(call.getId(), "AskUser approval flow is wired in Story #005 follow-up")`
  - Add proper `import` for `java.util.concurrent.Semaphore` / `CompletableFuture` / `ExecutionException` / `InterruptedException` / `TimeoutException` / `TimeUnit`
- [ ] T021 Validate compile: `mvn -pl lingshu-core compile` exits 0 (LinearTurnEngine with 3 new fields + 2 new methods compiles)

**Checkpoint**: LinearTurnEngine core changes complete — Provider injection can now wire everything together.

---

## Phase 7: LinearTurnEngineProvider — Router Injection (FR-005)

**Purpose**: Update Provider to resolve ToolExecutor + PermissionPolicy from Routers and inject ExecutorService.

- [ ] T022 [US3] Modify `LinearTurnEngineProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngineProvider.java`:
  - Add 3 new final fields: `private final Routers.ToolExecutorRouter toolRouter;` `private final Routers.PermissionPolicyRouter policyRouter;` `private final ExecutorService toolPool;`
  - Update constructor signature: `public LinearTurnEngineProvider(PromptBuilderRouter, LlmProviderRouter, ToolExecutorRouter, PermissionPolicyRouter, @Qualifier("agentToolPool") ExecutorService toolPool)`
  - Update `create(AgentConfig cfg)` to call `toolRouter.resolve(config.getToolExecutor(), config)` + `policyRouter.resolve(config.getSandbox().getPolicy(), config)` and pass 5 args to LinearTurnEngine constructor
- [ ] T023 Validate compile: `mvn -pl lingshu-core compile` exits 0 (LinearTurnEngineProvider wired with 3 new dependencies)

**Checkpoint**: Wiring complete — full Spring boot path can now resolve all 5 deps.

---

## Phase 8: L2 Slice Tests — LinearTurnEngine Sequential Dispatch (US1)

**Purpose**: Verify the engine now truly executes tool calls, with results in original order, errors translated, and provider fields wired correctly.

- [ ] T024 [P] [US1] Write `LinearTurnEngineToolDispatchTest#sequentialToolDispatch_toolResultsInOrder` (L2-001, US1 S1) in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineToolDispatchTest.java`:
  - Setup: 2 `SleepTool(name="read_a", sleep=10)` + `SleepTool(name="read_b", sleep=10)` registered in DefaultToolExecutor
  - EchoLlmProvider scripted: `[LlmResponse(toolCalls=[call_a, call_b], text=""), LlmResponse(toolCalls=[], text="done", stopReason=END_TURN)]`
  - Assert: `agent.session().history()` contains 4 Messages in order User / Assistant(tool_calls) / ToolResult / Assistant(END_TURN)
- [ ] T025 [P] [US1] Write `LinearTurnEngineToolDispatchTest#toolError_translatedToToolResult_turnContinues` (L2-002, US1 S2):
  - 1 mock tool that throws `ToolException.PermissionDeniedException` (mock PermissionPolicy → Decision.Deny)
  - Assert: history has `ToolResult{status=ERROR}`, no RuntimeException leaks out, turn continues
- [ ] T026 [P] [US1] Write `LinearTurnEngineToolDispatchTest#toolNotFound_translatedToToolResult_turnContinues` (L2-003, US1 S3):
  - EchoLlmProvider returns call with name="nonexistent_tool" (not registered)
  - Assert: history has `ToolResult{status=ERROR, content="Tool not registered: nonexistent_tool"}`, turn continues
- [ ] T027 [P] [US1] Write `LinearTurnEngineToolDispatchTest#noToolCalls_emitsTurnCompletedImmediately` (L2-004 regression — Story #001 demo-empty):
  - EchoLlmProvider returns `[LlmResponse(toolCalls=[], text="hello")]`
  - Assert: history = User / Assistant(END_TURN), no tool execution, wall-clock < 100ms
- [ ] T028 [P] [US3] Write `LinearTurnEngineProviderTest#create_returnsEngineWithAllFieldsWired` (L2-005, US3 S1) in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineProviderTest.java`:
  - Construct `LinearTurnEngineProvider(mock promptRouter, mock llmRouter, mock toolRouter, mock policyRouter, mock ExecutorService)`
  - Call `provider.create(defaultConfig)`
  - Reflect `LinearTurnEngine.class.getDeclaredFields()` — assert `promptBuilder`, `llmProvider`, `toolExecutor`, `permissionPolicy`, `toolPool` all non-null
- [ ] T029 Validate L2 tests pass: `mvn test -Dtest='LinearTurnEngineToolDispatchTest,LinearTurnEngineProviderTest'` exits 0 (6 cases green)

**Checkpoint**: Sequential dispatch verified — parallel tests can now build on this baseline.

---

## Phase 9: L2P Parallel Tests — AC-03 Black-Box Validation (US2)

**Purpose**: The AC-03 verification — 4 tools × 1s sleep × parallelism=4 must wall-clock ≤ 1.3s with ≥ 3.0× speedup.

- [ ] T030 [P] [US2] Write `LinearTurnEngineParallelDispatchTest#blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s` (L2P-001, **AC-03 黑盒主路径**) in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineParallelDispatchTest.java`:
  - Setup: `AgentConfig` with `toolParallelism=4` + 4 `SleepTool(name="read_a/b/c/d", sleepMillis=1000)` registered
  - Use `CountDownLatch startLatch = new CountDownLatch(4)` inside SleepTool to synchronize all 4 tool starts before wall-clock measurement
  - EchoLlmProvider: `[LlmResponse(toolCalls=[a, b, c, d], text=""), LlmResponse(toolCalls=[], text="done", stopReason=END_TURN)]`
  - Assert: `agent.runBlocking(...)` wall-clock ≤ 1.3s (vs serial baseline ≈ 4.0s), history has 4 ToolResults in order, all SUCCESS status
- [ ] T031 [P] [US2] Write `LinearTurnEngineParallelDispatchTest#parallelism1_wallClock_about4s_serial` (L2P-002, US2 S2):
  - Same setup but `toolParallelism=1`
  - Assert: wall-clock ≈ 4.0s (allow 3.5—4.5s for jitter), ToolCompleted events emitted in LLM-return order
- [ ] T032 [P] [US2] Write `LinearTurnEngineParallelDispatchTest#parallelismMinus1_unbounded_allConcurrent` (L2P-003, US2 S3):
  - 8 `SleepTool` × 1s, `toolParallelism=-1`
  - Assert: wall-clock ≈ 1.0s (all concurrent), no Semaphore used
- [ ] T033 [P] [US2] Write `LinearTurnEngineParallelDispatchTest#mixedSuccessAndFailure_resultsPreserveOriginalOrder` (L2P-004, US2 S4):
  - 3 success tools + 1 throws NullPointerException (via mock Tool.execute)
  - Assert: history has 4 ToolResults in LLM-return order, 3 SUCCESS + 1 ERROR at the failure position
- [ ] T034 Validate AC-03 black-box: `mvn test -Dtest=LinearTurnEngineParallelDispatchTest` exits 0, **wall-clock assertion passes**

**Checkpoint**: AC-03 black-box verified — Story #004 acceptance criterion satisfied.

---

## Phase 10: E2E Smoke + Full Test Run + R-13 Self-Check

**Purpose**: End-to-end smoke + ensure no regression in Story #001—#003 tests + capture R-13 evidence.

- [ ] T035 Write quick E2E smoke test in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryIntegrationTest.java` or new file:
  - `agentFactory.create(defaultConfig).runBlocking("test")` — EchoLlmProvider immediately returns END_TURN
  - Assert: RunResult.finalText="test", wall-clock < 200ms, no errors
- [ ] T036 Run full test suite: `mvn -pl lingshu-core test` exits 0 (Story #001 + #002 + #003 + #004 all green)
- [ ] T037 Run R-13 dep-tree check: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-004-after.txt`
  - `diff /tmp/deps-003-baseline.txt /tmp/deps-004-after.txt` → **0 new dependencies**
  - If diff shows new transitive deps → STOP + investigate + RFC + remove
- [ ] T038 Capture AC-03 verification output: `mvn test -Dtest=LinearTurnEngineParallelDispatchTest -Dsurefire.useFile=false` — copy paste result into PR body
- [ ] T039 Commit changes: `git add ... && git commit -m "feat(agent): Story #004 tool-parallel-dispatch — LinearTurnEngine.dispatchParallel + ToolExecutor/PermissionPolicy 注入 (AC-03)"`

**Checkpoint**: Story #004 complete — ready for PR.

---

## Phase 11: PR + Merge

- [ ] T040 Push branch: `git push origin story-004-tool-parallel-dispatch`
- [ ] T041 Open PR with body template:
  - Summary (3 bullets)
  - AC-03 black-box output (pasted from T038)
  - Test plan checklist (all 16 cases green)
  - **R-13 dependency:tree 自查** section (paste diff + key tree)
  - Critical invariants (none violated)
- [ ] T042 After PR review + merge: sync README.md / docs / dsh changelog §13 / constitution §10 (R-13 status update if any)

---

## Test Count Summary

| Layer | Count | Files |
|---|---|---|
| L1 Unit (DefaultToolExecutor) | 6 | `DefaultToolExecutorTest.java` |
| L2 Slice (LinearTurnEngine sequential) | 4 | `LinearTurnEngineToolDispatchTest.java` |
| L2 Slice (Provider wiring) | 1 | `LinearTurnEngineProviderTest.java` |
| L2 Slice (Parallel — AC-03) | 4 | `LinearTurnEngineParallelDispatchTest.java` |
| E2E Smoke | 1 | (extends AgentFactoryIntegrationTest) |
| **Total** | **16** | **4 test classes** |

---

## Definition of Done (Story #004 complete)

- [x] spec.md / plan.md / tasks.md 三件套齐全
- [ ] 5 modified files + 4 new test files committed
- [ ] `mvn -pl lingshu-core test` 全绿(16 测试用例)
- [ ] AC-03 黑盒验证通过(parallelism=4, 4 个 1s tool, wall-clock ≤ 1.3s)
- [ ] `mvn dependency:tree` 自查:0 新依赖
- [ ] 关键不变项全部保留(Tool / ToolExecutor / PermissionPolicy / Decision 子类树 / AgentConfig / AgentEvent)
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] Branch `story-004-tool-parallel-dispatch` pushed + PR opened

---

## Anti-Patterns to Avoid (CLAUDE.md §11 + §12)

- ❌ 直接调 `tool.execute()` 绕过 `toolExecutor.dispatch()`(违反 dsh §4.10.1 硬规则 2)
- ❌ 用 Spring AI `ChatClient.tools().call()` 自动执行(同规则)
- ❌ 引 `ExecutorCompletionService` / `ForkJoinPool` 等额外依赖(constitution §2 R-13)
- ❌ 修改 `Tool` / `ToolExecutor` / `PermissionPolicy` / `Decision` 接口签名(关键不变项)
- ❌ 在 `dispatchParallel` 内用 `Future.get()` **不**传 timeout(无界等待,违反 FR-007 / NFR-001)
- ❌ 把 tool pool 写成 static 单例(Spring 生命周期管理失效,违反 dsh §6.1 + §7.1.3)
- ❌ 用 `var` / `List.of` / sealed interface(JDK 8 约束 CLAUDE.md §3)
- ❌ 在 `runTurn` 直接 throw RuntimeException 终止 turn(违反 FR-007 + US1 S2 错误翻译要求)
- ❌ 把 Story #004 跨到 ApprovalGate 完整实现(Scope creep,属 Story #005 范畴)
