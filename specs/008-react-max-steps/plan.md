# Implementation Plan: Story #008 react-max-steps

**Feature**: Story #008 react-max-steps — `LinearTurnEngine` ReAct loop 上限守卫 + `MaxStepsExceeded` 事件发射(AC-07)
**Branch**: `story-008-react-max-steps`
**Spec**: [spec.md](./spec.md)
**Created**: 2026-09-21
**Constitution**: `.specify/memory/constitution.md` v1.0

---

## 1. 架构概览

### 当前状态(Story #001—#007 已 merged)

```
[FlowEngine]
   └── LinearTurnEngine (default, §6.1)
       └── runTurn(ctx, sink)
           ├── ctx.done() 检查        (L115-118)
           ├── cancellation 检查      (L124-129)
           ├── ReasoningStarted(step, maxSteps)  (L131)
           ├── llmProvider.stream() + waitForLlm  (L138-154)
           ├── Finish branch (no tool calls → break)  (L162-165)
           ├── dispatchParallel(toolCalls)  (L168)
           └── ObservationAppended(step, results.length)  (L174)
       └── for-loop 结束 → TurnCompleted(reason, totalUsage)  (L177-180)
       └── catch RuntimeException → ErrorEvent + TurnCompleted(ERROR)  (L184-188)
```

**已知 Bug**: §6.1 L3619-3621 设计的 `if (step > maxSteps) sink.onNext(MaxStepsExceeded(...))` 在当前 for-loop bound(`step <= maxSteps`)下**永远不会触发**。事件类 `MaxStepsExceeded`(`AgentEvent.java` L107-110)定义已存在但**无任何发射路径**。

### 目标架构(本 Story)

```
[FlowEngine]
   └── LinearTurnEngine.runTurn  (本 Story 修改 L177-180)
       └── for-loop 结束(自然 bound / break / done() / cancel / exception 共 5 路径)
           ├── 路径 A: 自然 bound(step > maxSteps) + last 含 tool calls
           │   └── 【本 Story 新增】sink.onNext(MaxStepsExceeded(maxSteps, totalUsage))  ← 在 TurnCompleted 前
           │   └── TurnCompleted(END_TURN, totalUsage)
           ├── 路径 B: 自然 bound + last 无 tool calls(已 break)
           │   └── TurnCompleted(reason, totalUsage)(已实现 L177-180,不动)
           ├── 路径 C: break at L117(ctx.done())
           │   └── TurnCompleted(END_TURN, totalUsage)(已实现)
           ├── 路径 D: return at L128(cancellation)
           │   └── TurnCompleted(CANCELLED, totalUsage)(已实现)
           └── 路径 E: catch RuntimeException
               └── ErrorEvent + TurnCompleted(ERROR, totalUsage)(已实现)
```

### 关键决策

| # | 决策 | 理由 |
|---|---|---|
| D-01 | `MaxStepsExceeded` 仅在路径 A 发射(自然 bound + last 含 tool calls)| FR-003 严格约束;其他路径(cancel/done/error/无 tool calls break)均**不**应发,避免误判 |
| D-02 | `TurnCompleted.reason` 仍为 `END_TURN`,**不**新增 `StopReason.MAX_STEPS`| FR-004 + Story #005 cancellation 状态机向后兼容 + Story #010 OTel metric 标签字段不变 |
| D-03 | `MaxStepsExceeded.totalUsage` 与 `TurnCompleted.usage` 同一对象引用(`Usage` `@Value` 不可变)| FR-002 + NFR-002(0 内存分配);非 snapshot copy |
| D-04 | 修改点仅 `LinearTurnEngine.java` L177-180(1 个分支新增 1 行 + 1 个 if 包裹)| Story 边界 ≤ 5 文件改动(实际 1 文件)+ ≤ 3 ErrorCode(0 新增);最小变更 |
| D-05 | 不动 `AgentEvent.MaxStepsExceeded` 类 / `StopReason` enum / `AgentConfig.reactMaxSteps` 字段 / `AgentFactory.create()` 校验| FR-006/FR-007 + 既有 Story #001/#002/#003 已固化的契约 |

---

## 2. 文件改动清单

| # | 文件 | 改动类型 | 行数 | 说明 |
|---|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | 修改 | +5 / 0 | L177 之前新增 `if (last != null && last.getToolCalls() != null && !last.getToolCalls().isEmpty())` + `sink.onNext(new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage))` |
| 2 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/MaxStepsGuardTest.java` | 新增 | +200 / 0 | US1 4 AS + US2 2 AS + US3 3 AS = 9 个 L1 Unit + AgentEvent / StopReason 反射 2 个 = 11 case |
| **合计** | **2 文件** | — | **+205 / 0** | ≤ Story 边界(5 文件 / 0 ErrorCode / 1 main + 1 test)|

**Story 边界检查**(CLAUDE.md §11 #4):
- ✅ 2 文件改动 ≤ 5
- ✅ 0 ErrorCode 引入 ≤ 3
- ✅ 1 个核心实现改动 + 1 个测试文件,scope 极小

---

## 3. 接口契约

### 现有契约(不动)

```java
// AgentEvent.java L107-110(不动)
@Getter
@RequiredArgsConstructor
public static class MaxStepsExceeded extends AgentEvent {
    private final int maxSteps;
    private final Usage totalUsage;
}

// StopReason.java L8-21(不动)
public enum StopReason {
    END_TURN, TOOL_USE, MAX_TOKENS, COMPACTED, CANCELLED, ERROR
}

// AgentConfig.reactMaxSteps(不动,默认 50,0 = 不限)
```

### 修改后契约(`LinearTurnEngine.runTurn`)

```java
// LinearTurnEngine.java L177-180 改写
StopReason reason = (last != null && last.getStopReason() != null)
    ? last.getStopReason()
    : StopReason.END_TURN;

// 🆕 Story #008 (FR-001/FR-003) — max-steps 守卫:仅当 for-loop 因 step > maxSteps 自然结束
// (无 break / cancel / done / exception 触发)且最后一次 LLM 响应仍含 tool calls(未自然 END_TURN)时,
// 发 MaxStepsExceeded 结构化事件。事件位置必须在 TurnCompleted 之前(FR-005),
// TurnCompleted.reason 仍为 END_TURN(FR-004),totalUsage 同一对象引用(FR-002)。
boolean hitMaxSteps = (step > maxSteps)   // for-loop 因 bound 自然结束,无 break / exception
    && last != null
    && last.getToolCalls() != null
    && !last.getToolCalls().isEmpty();
if (hitMaxSteps) {
    sink.onNext(new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage));
}

sink.onNext(new AgentEvent.TurnCompleted(reason, totalUsage));
```

**关键变化**:
1. L177 之前记录 `step` 在循环外的最终值(把 `for` loop 的 `int step` 改成外提变量,这样 break 后也能读 step 当前值)—— **或者** 在 `for (int step = 1; step <= maxSteps; step++)` 后面 L176 处置一个 `boolean reachedBound = (last != null && last.getToolCalls() != null && !last.getToolCalls().isEmpty() && step > maxSteps);`(注意 step 在循环结束时 == maxSteps+1)
2. 实际 for-loop 结束后 `step == maxSteps + 1`,所以判定 `step > maxSteps` 成立
3. 但 break 出来的 step 是当前 step(可能小于 maxSteps),需要区分

**最简实现**(单点改动,清晰可读):

```java
// 改写 L114 的 for-loop 为「计算 maxSteps 实际是否触及」前置变量
boolean maxStepsHit = false;   // 🆕 Story #008 — 守卫标志
for (int step = 1; step <= maxSteps; step++) {
    if (ctx.done()) break;
    if (ctx.cancellation().isCancelled()) { ... return; }
    sink.onNext(new AgentEvent.ReasoningStarted(step, maxSteps));
    ...
    LlmResponse resp = waitForLlm(...);
    ...
    last = resp;
    if (resp.getToolCalls() == null || resp.getToolCalls().isEmpty()) {
        break;   // 路径 B: 自然 END_TURN
    }
    ...
    // 路径 A 判断:for-loop 自然结束 = 没 break,且 last 含 tool calls
    // = step == maxSteps + 1(for-loop bound)且 last 不为 null 且 last 含 tool calls
    if (step == maxSteps) {
        maxStepsHit = true;
    }
}

if (maxStepsHit) {   // 🆕 Story #008 (FR-001/FR-003)
    sink.onNext(new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage));
}

sink.onNext(new AgentEvent.TurnCompleted(reason, totalUsage));
```

**判定逻辑 5 条路径分支**:
- 路径 A(step == maxSteps + last 含 tool calls)→ `maxStepsHit = true`(L176 末置)
- 路径 B(step < maxSteps + break at 无 tool call)→ `maxStepsHit = false`(break 时 step 未到 maxSteps)
- 路径 C(break at ctx.done)→ `maxStepsHit = false`(可能 step 未到 maxSteps)
- 路径 D(cancellation return)→ 不进 maxStepsHit 分支(已 return)
- 路径 E(exception catch)→ 不进 maxStepsHit 分支(已走 catch)

---

## 4. 测试策略

### 4.1 测试层次(§5 7 层金字塔)

| 层 | 测试文件 | 用例数 | 优先级 |
|---|---|---|---|
| L1 Unit | `MaxStepsGuardTest` | 9 case | P0(US1 4 AS + US2 2 AS + US3 3 AS)|
| L1 Unit(refl)| `MaxStepsExceededReflectionTest` | 2 case | P0(US3 AS1 + AS2)|
| L2 Slice | 同 `MaxStepsGuardTest`(已在 L2 范畴,无需单独 E2E)| 0 case | — |
| L5 E2E | `MaxStepsGuardIT`(可选,smoke test)| 0 case | 暂不写(本 Story P1 内核改动已由 L1 覆盖)|

**总测试用例**:**9 + 2 = 11 case**(Story #007 22 case → Story #008 11 case,**单 Story**+#008 加 11 case)

### 4.2 测试 fixture 复用(Story #004 既有)

- `CapturingSubscriber`(`flow/support/CapturingSubscriber.java`)—— 事件捕获
- `EchoLlmProvider`(`flow/support/EchoLlmProvider.java`)—— 脚本化 LLM 响应(可复用,无需新 fixture)
- `RecordingPromptBuilder`(`flow/support/RecordingPromptBuilder.java`)—— 占位 PromptBuilder
- `SleepTool`(`flow/support/SleepTool.java`)—— 占位 Tool,可改用 `RecordingTool` 或 mock
- `LinearTurnEngineToolDispatchTest.defaultConfig(...)` —— AgentConfig 工厂(可直接复用)

**0 新增 fixture**(最大复用 Story #004 已建测试基础设施)

### 4.3 关键测试用例

| ID | 测试名 | 输入 | 期望事件序列 |
|---|---|---|---|
| US1-AS1 | `maxSteps3_llmAlwaysToolCall_emitsMaxStepsExceeded_after3rdStep` | `reactMaxSteps=3` + 3 次 tool-call LLM + 无 END_TURN | `3×(RS+TC+OA)` + 1 `MaxStepsExceeded(3)` + 1 `TurnCompleted(END_TURN)` = 11 events |
| US1-AS2 | `maxSteps5_llmEndTurnAfter3Steps_noMaxStepsExceeded` | `reactMaxSteps=5` + 3 次 tool-call + 第 4 次 END_TURN | `3×(RS+TC+OA)` + 1 `TurnCompleted(END_TURN)` = 10 events(无 MaxStepsExceeded)|
| US1-AS3 | `maxSteps1_llmToolCall_emitsMaxStepsExceeded_after1stStep` | `reactMaxSteps=1` + 1 次 tool-call | `1×(RS+TC+OA)` + 1 `MaxStepsExceeded(1)` + 1 `TurnCompleted(END_TURN)` = 5 events |
| US1-AS4 | `maxSteps0_factoryValidateThrows_LingsC02_neverEnterEngine` | `reactMaxSteps=0` | `AgentFactory.create()` 抛 `IllegalArgumentException`("config.reactMaxSteps must be > 0")|
| US2-AS1 | `maxSteps2_llmThrowsFirstStep_errorPathNoMaxStepsExceeded` | `reactMaxSteps=2` + 第 1 步 LLM 抛 RuntimeException | `1×RS` + `ErrorEvent` + `TurnCompleted(ERROR)` = 3 events(无 MaxStepsExceeded)|
| US2-AS2 | `maxSteps3_toolExceptionMidPath_stepCountContinues_maxStepsHitFinally` | `reactMaxSteps=3` + 3 次 tool-call(tool 异常 → ToolResult.error)+ 无 END_TURN | `3×(RS+TC+OA)` + 1 `MaxStepsExceeded(3)` + 1 `TurnCompleted(END_TURN)` |
| US3-AS1 | `maxStepsExceeded_eventFields_intAndUsage` | 反射 `AgentEvent.MaxStepsExceeded.class` | 字段 `maxSteps:int` + `totalUsage:Usage` + `@Getter` 生成 |
| US3-AS2 | `stopReason_enumHasNoMaxStepsValue` | 反射 `StopReason.values()` | 6 值:`END_TURN/TOOL_USE/MAX_TOKENS/COMPAACTED/CANCELLED/ERROR`,无 `MAX_STEPS` |
| US3-AS3 | `maxSteps3_eventOrder_maxStepsBeforeTurnCompleted` | `reactMaxSteps=3` + 3 次 tool-call | 检查末 2 个事件:`MaxStepsExceeded(3, totalUsage)` → `TurnCompleted(END_TURN, totalUsage)` + `usage` 引用相同(`assertSame`)|
| EC-5 | `maxSteps10_cancellationMidPath_noMaxStepsExceeded` | `reactMaxSteps=10` + ctx.cancellation().cancel() 在第 5 步前 | `5×(RS+TC+OA)` + 1 `TurnCompleted(CANCELLED)`(无 MaxStepsExceeded)|
| EC-7 | `maxSteps3_llmEndTurnAtLastStep_naturalEndTurn_noMaxStepsExceeded` | `reactMaxSteps=3` + 第 1-2 步 tool-call + 第 3 步 END_TURN | `2×(RS+TC+OA)` + 1 `RS(3,3)`(step 3 进入)+ 1 `TurnCompleted(END_TURN)` = 8 events(无 MaxStepsExceeded,step 3 末 break)|

---

## 5. 实施顺序(9 步)

> **Step -1**: 已跳过(Maven skeleton 早在 Story #001 init 完成)

### Phase 1: Setup(2 步)

1. **S-01** 验证环境:`mvn -v` + `java -version`(JDK 17+)+ `git branch --show-current`(确认在 `story-008-react-max-steps`)
2. **S-02** 捕获 Story #007 dep baseline:`mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-007-baseline.txt`

### Phase 2: Foundational — 修改 LinearTurnEngine(2 步)

3. **F-01** [P0] 在 `LinearTurnEngine.java` L114 改 `for (int step = 1; step <= maxSteps; step++)` 主体内,L174 `ObservationAppended` 之后 / L176 `}` 之前,新增 `if (step == maxSteps) maxStepsHit = true;`
4. **F-02** [P0] 在 `LinearTurnEngine.java` L177-180 之前新增 `boolean maxStepsHit = false;`(for-loop 之前)+ `if (maxStepsHit) { sink.onNext(new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)); }`(for-loop 之后 / TurnCompleted 之前)

### Phase 3: Tests(3 步)

5. **T-01** [P0] 新建 `MaxStepsGuardTest.java` —— 9 case(US1 AS1-AS4 + US2 AS1-AS2 + EC-5/EC-7 + US3 AS3)
6. **T-02** [P0] 新建 `MaxStepsExceededReflectionTest.java` —— 2 case(US3 AS1 + AS2),或合并入 `MaxStepsGuardTest`(单文件 ≥ 11 case)
7. **T-03** [P0] 跑 `mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest` 全绿

### Phase 4: Validate(2 步)

8. **V-01** [P0] 跑全量回归 `mvn -pl lingshu-core test`(Story #001—#008 全部 case 全绿,无 regression)
9. **V-02** [P0] R-13 自查:`mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-008-after.txt` + `diff /tmp/deps-007-baseline.txt /tmp/deps-008-after.txt` → 仅 `[INFO] Total time` 时间戳差异,**0 新增依赖**

### Phase 5: Docs Sync(2 步)

10. **D-01** [P0] `README.md` Story #008 章节新增(类比 Story #007 L408-463)+ 测试覆盖数 `22 case / 5 文件 → 22 + 11 = 33 case / 6 文件`
11. **D-02** [P0] `dsh_agent_design.md` §13 changelog 表格新增 v1.5.36 行(Story #008 完成,2 文件 / 11 case / 0 新增依赖)
12. **D-03** [P0] `constitution.md` **不**改(本 Story 在宪法 §1/#7 编排可扩展 + §2/13 依赖锁定 + §4 错误码约定 + §5 7 层金字塔已有规则下,**0 新增**宪法条款)

### Phase 6: PR(1 步)

13. **PR-01** [P0] 提交 `feat(agent): Story #008 react-max-steps — guard fix + MaxStepsExceeded emission (AC-07)` + PR body 含 spec.md / plan.md / tasks.md 链接 + AC-07 黑盒验证输出(`mvn test` 11/11 green)+ R-13 dep-tree diff = 0

---

## 6. 风险与依赖

### Story 依赖

| 前置 Story | 提供什么 | 状态 |
|---|---|---|
| #001 zero-config-bootstrap | `AgentFactory.create()` 启动期校验 `LINGS-C02`(`L233-235`)| ✅ merged |
| #002 identity-instructions-memory | `AgentConfig.reactMaxSteps` 字段(默认 50,0 = 不限)| ✅ merged |
| #003 spi-slot-router | `FlowEngine` SPI + `LinearTurnEngine` 默认实现 | ✅ merged |
| #004 tool-parallel-dispatch | `dispatchParallel` Action 阶段 + test fixtures | ✅ merged |
| #005 cancellation-token | 三层贯通 + `TurnCompleted(CANCELLED)` 路径(本 Story 复用)| ✅ merged |
| #006 multi-tenant | `TenantContext` ThreadLocal(本 Story 复用 US2 AS2 隔离)| ✅ merged |
| #007 yaml-hot-reload | `cfg.snapshot()` freeze 语义(本 Story EC-9 自然兼容)| ✅ merged |

**所有前置 Story #001—#007 已 merged** ✅

### 实现风险

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| R-RT-01: 误发 `MaxStepsExceeded`(路径 B/C/D/E 错发)| 低 | 中(契约破坏)| 5 路径严格分支测试覆盖;判定 `step == maxSteps && last 含 tool calls` 单点 |
| R-RT-02: 漏发 `MaxStepsExceeded`(路径 A 漏)| 中 | 高(AC-07 fail)| US1 AS1 主路径 + 边界 case EC-7 双保险(EC-7 反向验证 step==maxSteps 但 last 无 tool calls **不**发)|
| R-RT-03: 顺序错(MaxStepsExceeded 在 TurnCompleted 之后)| 低 | 中(契约破坏)| US3 AS3 `assertSame(usage)` 验证;FR-005 顺序强约束 |
| R-RT-04: 引入额外依赖 / 改 `StopReason` enum | 极低 | 高(SemVer 破坏)| R-13 dep-tree 自查 + US3 AS2 反向测试 |

### R-04 ReAct 失控循环缓解(本 Story)

| 缓解项 | 落地 |
|---|---|
| (a) for-loop 上限 `step <= maxSteps` | Story #001 既有 |
| (b) `MaxStepsExceeded` 结构化事件 | 🆕 **本 Story** |
| (c) turn 正常 `done()`(StopReason=END_TURN) | 🆕 **本 Story**(路径 A → TurnCompleted(END_TURN))|

---

## 7. 完成标志(Definition of Done)

- [ ] Phase 1—6 全部 13 步 T-NN 全 ✅
- [ ] `mvn -pl lingshu-core test` 全绿(Story #001—#008 全部测试)
- [ ] `mvn -pl lingshu-core dependency:tree -Dverbose=true` diff = 0 新依赖
- [ ] `dsh_agent_design.md` §13 + `README.md` Story #008 章节同步
- [ ] `constitution.md` **不**改(0 新增宪法条款)
- [ ] PR `feat(agent): Story #008 react-max-steps — guard fix + MaxStepsExceeded emission (AC-07)` 提交 + body 完整
- [ ] AC-07 黑盒通过(`mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest` 11/11 green)