# Tasks: Story #008 react-max-steps

**Input**: Design documents from `/specs/008-react-max-steps/`
- spec.md(3 User Stories US1—US3 + 9 Edge Cases + FR-001—FR-010 + NFR-001—NFR-005)
- plan.md(7 步实施顺序 + 2 文件改动 + 11 L1 Unit + 0 L5 E2E = 11 测试用例)
- research.md(0 设计决策需研究 — 全部来源 dsh §6.1 L3619-3621 + §1.5.3 + AgentEvent/StopReason 既有契约)
- data-model.md(0 新增数据模型 — `MaxStepsExceeded` / `StopReason` / `Usage` 全部既有)
- contracts/max-steps-guard.md(`MaxStepsExceeded` 发射契约 + 顺序保证 + 5 路径分支)
- quickstart.md(7 验证场景 — US1-AS1 / US1-AS2 / US1-AS3 / US2-AS1 / US2-AS2 / US3-AS3 / EC-7)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#007 全部 merged(provides AgentFactory / AgentConfig.reactMaxSteps / LinearTurnEngine / CancellationToken / TenantContext / AgentConfigRegistry)

**Tests**: Required per FR-001—FR-010 + 5 NFR + 9 Edge Cases。**11 L1 Unit** = 0 L5 E2E。

**Constitution**: v1.0 — §1 #7 编排可扩展(`FlowEngine` 默认实现修改)/ §1 #11 默认实现位置(lingshu-core 内置)/ §1 #12 启动时配置校验(复用 L233-235)/ §2 13 依赖锁定(R-13 零新增)/ §4 错误码约定(0 新增 ErrorCode)/ §5 7 层金字塔(L1 Unit 11 case)/ §10 R-04 ReAct 失控循环缓解(3 件套全部落地)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + capture Story #007 dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #007 dependency baseline: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-007-baseline.txt`
- [ ] T003 Verify current branch is `story-008-react-max-steps` via `git branch --show-current`(已通过 spec/plan workflow 切到该分支)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core test` exits 0(Story #001—#007 tests all green — pre-implementation sanity)

**Checkpoint**: Setup ready — code modifications can begin.

---

## Phase 2: Foundational — 修改 `LinearTurnEngine.runTurn` for-loop 守卫(US1 + US2 + US3 基础 + 所有 AS 阻塞依赖)

**Purpose**: Establish the `maxStepsHit` flag + `MaxStepsExceeded` emission **before** any test wiring

**⚠️ CRITICAL**: 后续所有 US(US1 / US2 / US3 + EC-5/EC-7)都依赖 `LinearTurnEngine.runTurn` 正确发射 `MaxStepsExceeded`,此 phase 必须先完成

- [ ] T005 [P0] [US1] Modify `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` L113-180:
  - L113 `try {` 之前声明 `boolean maxStepsHit = false;`(FR-001 守卫标志)
  - L174 `sink.onNext(new AgentEvent.ObservationAppended(step, results.length));` **之后** / L175 `}` 之前,新增:
    ```java
    // 🆕 Story #008 (FR-001/FR-003) — max-steps 守卫:仅当 for-loop 因 step == maxSteps
    // 自然结束(无 break / cancellation / done / exception 触发)时,标记守卫触发。
    // L177 之后根据 maxStepsHit + last.getToolCalls() 联合判定发 MaxStepsExceeded。
    if (step == maxSteps) {
        maxStepsHit = true;
    }
    ```
  - L177 `StopReason reason = ...` 之前 / for-loop `}` 之后,新增:
    ```java
    // 🆕 Story #008 (FR-001/FR-003) — max-steps 守卫发射:仅当 (a) for-loop 自然 bound 结束
    // (无 break / cancel / done / exception)+ (b) 最后一次 LLM 响应仍含 tool calls 时发射。
    // StopReason 仍为 END_TURN(FR-004),totalUsage 同一对象引用(FR-002 + NFR-002)。
    if (maxStepsHit
        && last != null
        && last.getToolCalls() != null
        && !last.getToolCalls().isEmpty()) {
        sink.onNext(new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage));
    }
    ```
  - Javadoc 在 LinearTurnEngine class-level 注释补:
    ```
    * <p>Story #008 (FR-001): ReAct loop 上限守卫 —— for-loop 因 step == maxSteps 自然结束且
    * 最后一次响应仍含 tool calls 时,发射 {@link AgentEvent.MaxStepsExceeded} 结构化事件,
    * 然后 {@link AgentEvent.TurnCompleted} 正常 {@code END_TURN} 终止。
    ```

**Checkpoint**: LinearTurnEngine.runTurn 守卫编译过 + 不破坏现有功能 —— `mvn -pl lingshu-core compile`

---

## Phase 3: Tests — `MaxStepsGuardTest` L1 Unit(US1 + US2 + US3 + EC-5/EC-7)

**Purpose**: Black-box verify AC-07 max-steps guard + 5 路径分支全覆盖

- [ ] T006 [P0] [US1] Create `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/MaxStepsGuardTest.java`:
  - 文件头 import:`AgentEvent` / `LinearTurnEngine` / `EchoLlmProvider` / `RecordingPromptBuilder` / `CapturingSubscriber` / `DefaultToolExecutor` / `AllowAllPermissionPolicy` / `DefaultSession` / `DefaultTurnContext` / `LlmResponse` / `StopReason` / `ToolCall` / `Usage` / `AgentConfig` / `TurnContext` / `Paths` / `Collections` / `Arrays` / `ExecutorService` / `Executors` / `TimeUnit` / `BeforeEach` / `AfterEach` / `DisplayName` / `Test` / `assertThat`
  - 复用 `LinearTurnEngineToolDispatchTest` 的 `defaultConfig(int parallelism, int timeoutSec)` 工厂方法模式 + `newTurn(cfg)` helper
  - **核心 fixture**:`new LinearTurnEngine(new RecordingPromptBuilder(), llm, toolExecutor, new AllowAllPermissionPolicy(), pool)`,同 Story #004 测试样板
  - **9 个测试方法**(每个 `@Test @DisplayName("...")`):
    - `US1-AS1`: `maxSteps3_llmAlwaysToolCall_emitsMaxStepsExceeded_after3rdStep`
      - 输入:`reactMaxSteps=3` + `EchoLlmProvider(3 个 tool-call response + 0 END_TURN)`(EchoLlmProvider 会因 script exhausted 抛 — 需测**只**3 步)
      - 期望:11 个事件,顺序 `RS(1,3) → TC → OA → RS(2,3) → TC → OA → RS(3,3) → TC → OA → MaxStepsExceeded(3) → TurnCompleted(END_TURN)`,`assertSame(usage)` 验证末 2 个事件 usage 引用相同
    - `US1-AS2`: `maxSteps5_llmEndTurnAfter3Steps_noMaxStepsExceeded`
      - 输入:`reactMaxSteps=5` + 3 tool-call + 1 END_TURN
      - 期望:10 个事件,无 MaxStepsExceeded
    - `US1-AS3`: `maxSteps1_llmToolCall_emitsMaxStepsExceeded_after1stStep`
      - 输入:`reactMaxSteps=1` + 1 tool-call(无 END_TURN)
      - 期望:5 个事件 `RS(1,1) → TC → OA → MaxStepsExceeded(1) → TurnCompleted(END_TURN)`
    - `US1-AS4`: `maxSteps0_factoryValidateThrows_LingsC02_neverEnterEngine`
      - 输入:`reactMaxSteps=0` + `AgentFactory.create(cfg)`(复用 Story #001 既有 factory)
      - 期望:抛 `IllegalArgumentException` 含 "config.reactMaxSteps must be > 0"
    - `US2-AS1`: `maxSteps2_llmThrowsFirstStep_errorPathNoMaxStepsExceeded`
      - 输入:`reactMaxSteps=2` + 脚本化 LLM 第 1 步抛 RuntimeException(自写 `ThrowingLlmProvider` fixture,**不**复用 EchoLlmProvider)
      - 期望:3 个事件 `RS(1,2) → ErrorEvent → TurnCompleted(ERROR)`,无 MaxStepsExceeded
    - `US2-AS2`: `maxSteps3_toolExceptionMidPath_stepCountContinues_maxStepsHitFinally`
      - 输入:`reactMaxSteps=3` + 3 tool-call + tool 抛 RuntimeException(用 `ThrowTool extends Tool` fixture,ToolResult.error 翻译)
      - 期望:11 个事件,顺序同 US1-AS1(异常已翻译为 ToolResult.error,step 计数继续,末步仍触发 MaxStepsExceeded)
    - `US3-AS3`: `maxSteps3_eventOrder_maxStepsBeforeTurnCompleted_usageRefSame`
      - 输入:同 US1-AS1
      - 期望:末 2 个事件 `MaxStepsExceeded(3, totalUsage)` → `TurnCompleted(END_TURN, totalUsage)` + `assertSame(mxe.totalUsage, tc.usage)`
    - `EC-5`: `maxSteps10_cancellationMidPath_noMaxStepsExceeded`
      - 输入:`reactMaxSteps=10` + `DefaultTurnContext.cancellation().cancel()` 在第 5 步循环外
      - 期望:`5×(RS+TC+OA)` + 1 `TurnCompleted(CANCELLED)`,无 MaxStepsExceeded
    - `EC-7`: `maxSteps3_llmEndTurnAtLastStep_naturalEndTurn_noMaxStepsExceeded`
      - 输入:`reactMaxSteps=3` + 2 tool-call + 1 END_TURN(第 3 步)
      - 期望:第 3 步 `RS(3,3)` 后 break(因 LLM 无 tool calls)+ 1 `TurnCompleted(END_TURN)` = 8 个事件,**不**发 MaxStepsExceeded(step 3 break,**不**是自然 bound)

**Checkpoint**: `mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest` 9/9 green

- [ ] T007 [P0] [US3] Add reflection tests to `MaxStepsGuardTest.java`(合并,不另建文件)— 2 case:
  - `US3-AS1`: `maxStepsExceeded_eventFields_intAndUsage` — 反射 `AgentEvent.MaxStepsExceeded.class.getDeclaredFields()` 断言字段 `int maxSteps` + `Usage totalUsage` + Lombok `@Getter` 生成的 getter(`getMaxSteps()` 返回 int,`getTotalUsage()` 返回 `Usage`)
  - `US3-AS2`: `stopReason_enumHasNoMaxStepsValue` — `StopReason.values()` 断言长度 == 6,值集合 = `{END_TURN, TOOL_USE, MAX_TOKENS, COMPACTED, CANCELLED, ERROR}`,无 `MAX_STEPS`

**Checkpoint**: `MaxStepsGuardTest` 共 **11 case** 9 + 2

---

## Phase 4: Validate(US1—US3 AC 黑盒 + 全量回归 + R-13 自查)

**Purpose**: Black-box AC-07 validation + no regression + R-13 dep-tree diff = 0

- [ ] T008 [P0] [US1] Run AC-07 black-box test: `mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest` exits 0(11/11 green,贴输出到 PR body)
- [ ] T009 [P0] Run full regression: `mvn -pl lingshu-core test` exits 0(Story #001—#008 全部 case 全绿,贴 summary 行)
- [ ] T010 [P0] R-13 dependency:tree 自查:
  ```bash
  mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-008-after.txt
  diff /tmp/deps-007-baseline.txt /tmp/deps-008-after.txt
  # 期望:仅 [INFO] Total time 时间戳差异,0 新依赖
  ```
- [ ] T011 [P0] Verify no new ErrorCode introduced:
  ```bash
  git diff main -- '*.java' | grep -E "LINGS-[A-Z][0-9]+" | sort -u
  # 期望:仅复用 LINGS-C02 / C03(可能 0 行),0 新增
  ```

**Checkpoint**: AC-07 黑盒通过 + 0 regression + 0 新依赖 + 0 新 ErrorCode

---

## Phase 5: Docs Sync(README + dsh changelog + constitution)

**Purpose**: 同步用户文档 + 设计文档,constitution 不改

- [ ] T012 [P0] Update `README.md`:
  - L408-463 之后新增 `### Story #008 react-max-steps(...)` 章节(类比 Story #007 模板)
  - 核心交付 4 条:`maxStepsHit` 守卫 + `MaxStepsExceeded(maxSteps, totalUsage)` 事件 + 顺序保证(MaxStepsExceeded → TurnCompleted)+ TurnCompleted.reason=END_TURN(不引入新 StopReason)
  - 关键不变量 5 条:for-loop bound 自然结束 + last 含 tool calls 才发 / StopReason 不变 / totalUsage 引用语义 / 5 路径分支正确性(路径 A 发,其他不发)/ 0 新增 ErrorCode
  - 测试覆盖:`MaxStepsGuardTest`(11 case / 1 文件)
  - 跑测命令:`mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest`
  - 测试总覆盖更新:Story #007 22 case / 5 文件 → Story #008 加 11 case → **总 33 case / 6 文件**(如 README 顶部有总统计,同步更新)
  - 文档站新增 `docs/concepts/react-loop.md` 链接 + 入口
- [ ] T013 [P0] Update `dsh_agent_design.md`:
  - L6537 changelog 表格之后新增一行 `v1.5.36 | 2026-09-21 | **Story #008 react-max-steps**:`LinearTurnEngine.runTurn` 新增 `maxStepsHit` 守卫 + `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` 事件发射(AC-07 黑盒);TurnCompleted.reason 仍为 END_TURN(不引入新 StopReason);0 新增 ErrorCode + 0 新增 Maven 依赖 + 0 新增 Slot SPI;11 L1 Unit 测试;`MaxStepsHit` 标志 + `step == maxSteps` 判定 + `last.getToolCalls() != null && !isEmpty()` 联合守卫,5 路径分支全覆盖(自然 bound + last 含 tool calls 才发);2 文件改动(LinearTurnEngine.java +5 行 + MaxStepsGuardTest.java +200 行);§6.1 L3619-3621 既有设计契约补全落地。
  - §6.1 L3611-3629 代码块同步更新为改后版本(for-loop 之前 `boolean maxStepsHit = false` + L174 之后 `if (step == maxSteps) maxStepsHit = true` + L177 之前 `if (maxStepsHit && last 含 tool calls) sink.onNext(MaxStepsExceeded)`)
- [ ] T014 [P0] Verify `constitution.md` **不**改:
  ```bash
  git diff main -- .specify/memory/constitution.md
  # 期望:no output(0 行变化)
  ```
  本 Story 在宪法 §1/#7 编排可扩展 + §2/13 依赖锁定 + §4 错误码约定 + §5 7 层金字塔已有规则下,**0 新增**宪法条款。

**Checkpoint**: README / dsh / constitution 三同步或验证不改

---

## Phase 6: PR + Merge(AC-07 收尾)

**Purpose**: 提交 + 合入主分支 + 同步归档

- [ ] T015 [P0] Commit changes:
  ```bash
  git add lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java
  git add lingshu-core/src/test/java/ai/lingshu/core/impl/flow/MaxStepsGuardTest.java
  git add specs/008-react-max-steps/   # spec/plan/tasks/research/data-model/contracts/checklists/quickstart 8 件套
  git add README.md
  git add dsh_agent_design.md
  git commit -m "feat(agent): Story #008 react-max-steps — guard fix + MaxStepsExceeded emission (AC-07)

  * LinearTurnEngine.runTurn: maxStepsHit guard + MaxStepsExceeded emission
  * 5 路径分支全覆盖(A 自然 bound + last 含 tool calls 才发)
  * StopReason 仍为 END_TURN(0 新增 enum 值)
  * 11 L1 Unit 测试(MaxStepsGuardTest)
  * 0 新增 ErrorCode / 0 新增 Maven 依赖 / 0 新增 Slot SPI

  Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
  ```
- [ ] T016 [P0] Push branch + open PR:
  ```bash
  git push -u origin story-008-react-max-steps
  gh pr create --base main --title "feat(agent): Story #008 react-max-steps — guard fix + MaxStepsExceeded emission (AC-07)" --body "$(cat <<'EOF'
  ## Summary
  - [x] `LinearTurnEngine.runTurn` 新增 `maxStepsHit` 守卫标志
  - [x] for-loop 因 step == maxSteps 自然结束 + 最后响应仍含 tool calls 时,发射 `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` 事件
  - [x] 事件顺序固定: `MaxStepsExceeded` → `TurnCompleted(END_TURN)`(`assertSame(usage)` 验证引用语义)
  - [x] `StopReason` enum 0 改动(`END_TURN` 复用,Story #005/Story #010 向后兼容)
  - [x] 5 路径分支全覆盖 US1/US2/US3 + EC-5/EC-7 = 11 L1 Unit case 全绿
  - [x] 0 新增 ErrorCode / 0 新增 Maven 依赖 / 0 新增 Slot SPI

  ## Test plan
  - [x] `mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest`(11/11 green)
  - [x] `mvn -pl lingshu-core test`(Story #001—#008 全绿,0 regression)
  - [x] `mvn -pl lingshu-core dependency:tree -Dverbose=true` diff = 0 新依赖

  ## Docs sync
  - [x] README.md Story #008 章节新增(类比 Story #007 模板)
  - [x] dsh_agent_design.md §13 changelog 新增 v1.5.36 行 + §6.1 L3611-3629 代码块同步
  - [x] constitution.md **不**改(0 行变化)

  🤖 Generated with [Claude Code](https://claude.com/claude-code)
  EOF
  )"
  ```
- [ ] T017 [P0] Verify PR CI green + merge to main + 同步归档:
  - 等待 GitHub Actions CI matrix green(ubuntu + JDK 17/21)
  - `gh pr merge --squash --delete-branch`(or merge commit,follow repo convention)
  - 删除 `story-008-react-max-steps` 本地分支(可选)
  - `git checkout main && git pull` 验证合入

**Checkpoint**: Story #008 merged to main,PR 关闭,合入 commit hash 记录在 changelog

---

## Dependencies & Execution Order

### Phase 串行依赖(必须按顺序)

```
T001 → T002 → T003 → T004 → T005 → T006 → T007 → T008 → T009 → T010 → T011 → T012 → T013 → T014 → T015 → T016 → T017
```

### Phase 内可并行

- T006 + T007 可并行(同一文件,**不**并行 — 串行写更安全)
- T012 + T013 可并行(README 与 dsh 文档独立)
- T016 内 `git push` + `gh pr create` 串行

---

## 估算

| Phase | 步数 | 复杂度 |
|---|---|---|
| Phase 1 Setup | 4 | 极低(纯命令)|
| Phase 2 Foundational | 1 | **中(关键改动 — 必须一次写对)** |
| Phase 3 Tests | 2 | 中(11 case,fixture 复用 Story #004)|
| Phase 4 Validate | 4 | 低(命令 + diff)|
| Phase 5 Docs Sync | 3 | 低(模板复用)|
| Phase 6 PR | 3 | 低(模板复用)|
| **合计** | **17 步** | — |

---

## 完成标志(Definition of Done)

- [x] Phase 1—6 全部 17 步 T-NN 全 ✅
- [x] `mvn -pl lingshu-core test` 全绿(Story #001—#008 全部测试)
- [x] `mvn -pl lingshu-core dependency:tree -Dverbose=true` diff = 0 新依赖
- [x] `dsh_agent_design.md` §13 + `README.md` Story #008 章节同步
- [x] `constitution.md` **不**改(0 新增宪法条款)
- [x] PR `feat(agent): Story #008 react-max-steps — guard fix + MaxStepsExceeded emission (AC-07)` merged to main
- [x] AC-07 黑盒通过(`mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest` 11/11 green)