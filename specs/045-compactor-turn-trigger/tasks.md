# Tasks: Story #045 — compactor-turn-trigger

**Input**: Design documents from `/specs/045-compactor-turn-trigger/`
**Prerequisites**: spec.md ✅ / plan.md ✅
**Tests**: 13 case total(7 L1 + 4 L1 + 1 L2 + 1 L2 RAC trigger)

**Organization**: Tasks grouped by Phase — Setup → Implementation → Tests → Docs → Validation.

**Format**: `[ID] [P?] [Story] Description`
- **[P]** = can run in parallel(different files, no dependencies)
- **[Story]** = maps to AC-045-NN

---

## Phase 1: Setup(No core file changes)

**Purpose**: Sentinel fallback class + AgentEvent event types(2 independent files, fully parallel)

- [ ] **T-01** [P] [AC-045-5] Create `NullCompactor.java` in `lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/`
  - 15 行:`public final class NullCompactor implements Compactor` + `INSTANCE` singleton + `shouldCompact()` returns false + `compact()` no-op
  - `package ai.lingshu.core.impl.compaction;`
  - 依赖:`ai.lingshu.core.slot.Compactor`(已存在)/ `ai.lingshu.core.message.Prompt`(已存在)/ `ai.lingshu.core.runtime.TurnContext`(已存在)
  - **AC-045-5** lock
  - 提交:`feat(slot-6): NullCompactor sentinel for back-compat ctor`

- [ ] **T-02** [P] [AC-045-2] Add `CompactionTriggered` + `CompactionCompleted` nested classes to `AgentEvent.java`
  - 40 行 net add:2 个 `public static final class extends AgentEvent`(2-arg ctor) + Kind enum 加 2 个新值 `COMPACTION_TRIGGERED` / `COMPACTION_COMPLETED`
  - 镜像 Story #030 `ApprovalRequired(approvalId, sessionId, Decision)` precedent
  - 依赖:`AgentEvent`(已存在)
  - **AC-045-2** emit 事件 lock
  - 提交:`feat(event): AgentEvent.CompactionTriggered / CompactionCompleted`

---

## Phase 2: Core Wiring(3 修改文件,顺序严格,串行)

**Purpose**: LinearTurnEngine / LinearTurnEngineProvider / AgentFactory 三个 ctor 改动必须按依赖顺序串行 —— AgentFactory 注入 Router → Provider resolve → Engine 拿 instance。

### Step 2A: Engine ctor 改动(基础)

- [ ] **T-03** [AC-045-1 / AC-045-2] Modify `LinearTurnEngine.java`
  - 新增 import `ai.lingshu.core.slot.Compactor`
  - 新增 final 字段 `private final Compactor compactor;`(7 个 final 字段)
  - 新增 7-arg ctor(primary):`(PromptBuilder, LlmProvider, ToolExecutor, PermissionPolicy, ExecutorService, ApprovalRegistry, Compactor)` 7 null 检查(ApprovalRegistry nullable)
  - 旧 6-arg ctor(`(..., ExecutorService)` Story #001)→ 改 back-compat 委托 7-arg ctor with `ApprovalRegistry=null, compactor=NullCompactor.INSTANCE`
  - 旧 7-arg ctor(`(..., ApprovalRegistry)` Story #030)→ 改 back-compat 委托 new 7-arg ctor with `compactor=NullCompactor.INSTANCE`
  - `runTurn()` L186 之后插 compactor 短路 + 重 build prompt(plan.md §文件 #1 ### L186 hook 段)
  - 依赖:T-01(NullCompactor)
  - **AC-045-1 / AC-045-2** lock
  - 提交:`feat(flow): LinearTurnEngine 7-arg ctor + runTurn compactor trigger`

### Step 2B: Provider 改 Router resolve(中间层)

- [ ] **T-04** [AC-045-3] Modify `LinearTurnEngineProvider.java`
  - 新增 import `ai.lingshu.core.slot.Compactor` + `ai.lingshu.core.impl.compaction.NullCompactor` + `ai.lingshu.core.impl.router.Routers.CompactorRouter`
  - 新增 final 字段 `private final CompactorRouter compactorRouter;`(7 个 final 字段)
  - 新增 7-arg ctor(primary):`(PromptBuilderRouter, LlmProviderRouter, ToolExecutorRouter, PermissionPolicyRouter, ExecutorService, @Nullable ApprovalRegistry, CompactorRouter)` 7 null 检查(approvalRegistry nullable)
  - 旧 6-arg ctor(`(..., @Nullable ApprovalRegistry)` Story #030)→ 改 back-compat 委托 7-arg ctor with `compactorRouter=null`
  - `create(AgentConfig)` body 改:`Compactor compactor = compactorRouter != null ? compactorRouter.resolve(cfg, name -> name) : NullCompactor.INSTANCE;` 然后调 `new LinearTurnEngine(..., compactor)` 7-arg ctor
  - 依赖:T-03(LinearTurnEngine 7-arg ctor)
  - **AC-045-3** lock
  - 提交:`feat(flow): LinearTurnEngineProvider 7-arg ctor + compactorRouter.resolve()`

### Step 2C: AgentFactory 9-Router ctor(顶层)

- [ ] **T-05** [AC-045-4] Modify `AgentFactory.java`
  - 新增 import `ai.lingshu.core.impl.router.Routers.CompactorRouter`
  - 新增 final 字段 `private final CompactorRouter compactorRouter;`(9 Router)
  - 新增 9-Router ctor(primary):`(LlmProviderRouter, ToolExecutorRouter, PermissionPolicyRouter, PromptBuilderRouter, FlowEngineRouter, MemorySourceRouter, RuntimeSandboxRouter, SessionStoreRouter, CompactorRouter)` 9 null 检查
  - 旧 8-Router ctor(Story #014 含 SessionStoreRouter)→ 改 back-compat 委托 9-Router ctor with `compactorRouter=null`
  - 旧 7-Router ctor(Story #014 无 SessionStoreRouter)→ 改 back-compat 委托 8-Router ctor with `sessionStoreRouter=null`
  - 旧 6-Router ctor(Story #028 无 RuntimeSandboxRouter / SessionStoreRouter)→ 改 back-compat 委托 7-Router ctor with `runtimeSandboxRouter=null, sessionStoreRouter=null`
  - L658 占位 `null // compactor` → 改为实际 Spring 注入
  - 依赖:T-04(Provider 改)
  - **AC-045-4** lock
  - 提交:`feat(factory): AgentFactory 9-Router ctor + compactorRouter`

---

## Phase 3: Tests(3 测试文件,可部分并行)

**Purpose**: L1 unit tests + L2 integration test

### Step 3A: L1 unit tests(完全并行,无依赖)

- [ ] **T-06** [P] [AC-045-2 / AC-045-1 / EC-045-1 / EC-045-2 / AC-045-5] Create `LinearTurnEngineCompactionTest.java` in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/`
  - 7 case L1:`shouldCompactFalse_skipsCompactor` / `shouldCompactTrue_invokesCompactor` / `shouldCompactTrue_rebuildsPrompt` / `compactThrows_propagatesUp` / `nullCompactor_skippedNoOp` / `legacy6ArgCtor_backCompat` / `nullCompactorCtor_throwsIAE`
  - 用 Mockito spy `Compactor` mock + 真实 `LinearTurnEngine` fixture
  - 依赖:T-03(LinearTurnEngine 7-arg)
  - 提交:`test(flow): LinearTurnEngineCompactionTest 7 case L1`

- [ ] **T-07** [P] [AC-045-3 / EC-045-3] Create `LinearTurnEngineProviderCompactionTest.java` in `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/`
  - 4 case L1:`create_injectsCompactorFromRouter` / `legacy6ArgCtor_backCompat_withNullRouter` / `create_returns7ArgLinearTurnEngine` / `create_usesNullCompactorWhenRouterNull`
  - 用 Mockito mock `CompactorRouter.resolve(...)` 返 `TruncatingCompactor` 实例
  - 依赖:T-04(Provider 改)
  - 提交:`test(flow): LinearTurnEngineProviderCompactionTest 4 case L1`

### Step 3B: L2 integration test(单测,需全 Spring context)

- [ ] **T-08** [AC-045-6 / AC-045-RAC-1/2/3/4] Create `AgentFactoryYamlCompactorIT.java` in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/`
  - 1 case L2:`ymlTruncating_compactorTriggered_historyShrinks`
  - `@SpringBootTest` + yml `agent.compactor: truncating` + 构造 11 turn
  - 验证:`ctx.history()` 长度 ≤ `CompactorProps.threshold`
  - 反向 AC:`TruncatingCompactor` + `CompactorAutoConfiguration` + `CompactorRouter` 0 改动全过(已有 18 case 跑一遍)
  - 依赖:T-05(AgentFactory 改) + 既有 `Compactor` 系列 fixture
  - 提交:`test(integration): AgentFactoryYamlCompactorIT 1 case L2`

---

## Phase 4: Validation(必须 AC-002 + R-13 双重通过)

- [ ] **T-09** [AC-045-8 / AC-045-RAC-1/2/3/4] Run `mvn -pl lingshu-core test`
  - 验证:707 旧 case + 13 #044 case + 7 + 4 + 1 = **732 total, 0 fail / 0 regression**
  - 验证:`TruncatingCompactorTest` + `TruncatingCompactorProviderTest` + `CompactorAutoConfigurationTest` + `CompactorRouterMultiProviderIT` + `CompactorBlackboxIT` 既有 18+ case 0 改动全过(反向 AC-045-RAC-1/2/3/4)
  - 验证:既有 53 fixture 文件 `AgentConfig.builder().flowEngine("...").llm(...)...permissionPolicy("...").compactor(...)` 0 改动全过(53 fixture 文件不变,本 Story 0 新 AgentConfig 字段)
  - 依赖:T-08

- [ ] **T-10** [AC-045-8] Run R-13 mitigation (d) baseline 镜像
  - `cp /path/to/baseline.jar /tmp/baseline-pre-#045.jar`(pre-story baseline)
  - `mvn -pl lingshu-core dependency:tree > /tmp/deps-pre-#045.txt`
  - 实施 T-01 ~ T-08
  - `mvn -pl lingshu-core dependency:tree > /tmp/deps-post-#045.txt`
  - `diff /tmp/deps-pre-#045.txt /tmp/deps-post-#045.txt` → 仅时间戳差异
  - 累计计数 28 → **29 个 Story R-13 mitigation (d) PASS 0 binary delta**
  - 依赖:T-09

- [ ] **T-11** [AC-045-RAC-1/2/3/4] 反向 AC 验证 — Story #018 已合 case 0 改动全过
  - `mvn -pl lingshu-core test -Dtest=TruncatingCompactorTest` → 全过
  - `mvn -pl lingshu-core test -Dtest=TruncatingCompactorProviderTest` → 全过
  - `mvn -pl lingshu-core test -Dtest=CompactorAutoConfigurationTest` → 全过
  - `mvn -pl lingshu-core test -Dtest=CompactorRouterMultiProviderIT` → 全过
  - `mvn -pl lingshu-core test -Dtest=CompactorBlackboxIT` → 全过
  - 依赖:T-09(被包含)

---

## Phase 5: Documentation Sync(并入合入 commit)

- [ ] **T-12** [DoD] `README.md` 同步
  - 「更新日期」段加 🆕 v1.5.58 Story #045 顶部 blockquote
  - 「核心特性」段补 🆕 Compactor turn wiring 已上线 bullet
  - 「Story 路线图」段追加 #045 retrospective(7 文件改动 / 13 case AC 黑盒验证 / R-13 0 binary delta 第 29 次 PASS / 累计 45 → **46 个 Story 合入**)
  - 依赖:T-10

- [ ] **T-13** [DoD] `specs/ROADMAP.md` 同步
  - 段一 ✅ 已完成加 #045 行(2026-10-10,732 pass / 0 fail / R-13 0 binary delta 第 29 次 PASS / 0 新 ErrorCode)
  - 段二 🟡 待补 #045 划掉
  - 段五 🎯 实施节奏 🎉 收口 + 统计 45 已合 / 0 待补 / +13 新 case(= 732 = 720 pre-#045 chain + 12 新 net new)
  - 依赖:T-10

- [ ] **T-14** [DoD] `constitution.md` §10 R-13 缓解 Story 列表补 #045 行
  - **第 29 次** R-13 mitigation (d) PASS 0 binary delta
  - 累计计数 28 → **29 个 Story**
  - 依赖:T-10

- [ ] **T-15** [DoD] `dsh_agent_design.md` 同步
  - §13 changelog 加 v1.5.58 行,记录 Story #045 完成(7 文件改动 / 13 case / R-13 0 binary delta 第 29 次 PASS / 累计 46 个 Story)
  - §6.1 L3630-3631 改"Compactor wiring 已落代码" + 标注"🆕 Story #045 v1.5.58 落地"
  - §15 ErrorCode 域字母表 0 改
  - 「对应设计文档」版本号 v1.5.57 → v1.5.58
  - 依赖:T-10

- [ ] **T-16** [DoD] CLAUDE.md 同步 + SKILL mirror
  - CLAUDE.md v1.3.53 → v1.3.54,文末加 Story #045 retrospective paragraph
  - `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` 镜像版本号 v1.0.21 → v1.0.22
  - 依赖:T-15

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (T-01, T-02)**:No dependencies, can run in parallel
- **Phase 2 (T-03 → T-04 → T-05)**:Strict sequential(LinearTurnEngine → Provider → AgentFactory)
- **Phase 3 (T-06, T-07, T-08)**:Phase 2 完成,可部分并行
- **Phase 4 (T-09 → T-10 → T-11)**:Phase 3 完成,顺序依赖
- **Phase 5 (T-12, T-13, T-14, T-15, T-16)**:Phase 4 完成,可部分并行

### Parallel Opportunities

- T-01 / T-02(different files, no dependencies)→ **并行**
- T-06 / T-07(different files, no dependencies)→ **并行**
- T-12 / T-13 / T-14(different files)→ **并行**
- T-15 / T-16(sequential, T-16 depends on T-15)

### Within Each Phase

- 强顺序约束:Phase 2 三个 ctor 改动必须按 T-03 → T-04 → T-05 顺序,否则 ctor 链断裂编译失败

---

## Implementation Strategy

### MVP Path(Story #045 一次性合)

1. Phase 1:Setup(T-01 + T-02 并行,~20 分钟)
2. Phase 2:Core(T-03 → T-04 → T-05 顺序,~55 分钟)
3. Phase 3:Tests(T-06 + T-07 并行,~50 分钟 + T-08 ~15 分钟)
4. Phase 4:Validation(T-09 → T-10 → T-11,~10 分钟)
5. Phase 5:Docs(T-12-T16,~30 分钟)
7. **PR + 合入**

**总时长估算**:~3 小时净实施 + 30 分钟 review + 30 分钟 merge

---

## 实施守住的 5 个关键不变项

1. **`Compactor` / `CompactorProvider` SPI 接口契约** 0 改(只新增 1 个 `NullCompactor` 实现)
2. **`Routers.CompactorRouter` 行为** 0 改(Story #018 已合,本 Story 只通过它 resolve)
3. **`AgentConfig` + `CompactorConfig` 不可变契约** 0 字段新增(Story #018 已合)
4. **`AgentFactory` ctor 链 back-compat** 6 → 7 → 8 → 9 4 层委托(既有 Story #001-#044 旧 fixture 0 改)
5. **JDK 8 兼容**(`@Nullable` + Lombok @Value + JDK 8 standard 已锁,no `var` / `List.of` / sealed / records)

---

**Last updated**: 2026-10-10
**Branch**: `045-compactor-turn-trigger`
**Status**: Draft(待用户评审 + 审批后才进 Step 3 实施)