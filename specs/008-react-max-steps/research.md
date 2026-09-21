# Research: Story #008 react-max-steps

**Feature**: Story #008 react-max-steps
**Created**: 2026-09-21
**Status**: Complete

---

## 0. 设计决策研究结论

本 Story 所有设计决策**已锁定**(来源 dsh §6.1 L3619-3621 + §1.5.3 v1.5.6 changelog + `AgentEvent.MaxStepsExceeded` 类定义已存在 + `StopReason` enum 已固化)。

**无新增设计决策需验证**。本文件仅记录 3 项**确认/核对**结论:

---

## 1. D-01 决策:发射路径选择(路径 A 单点)

### 来源

dsh §6.1 L3619-3621:
```java
// [ReAct] maxSteps 显式守卫:防 pathological 循环浪费 token
if (step > maxSteps) {
    sink.onNext(new AgentEvent.MaxStepsExceeded(
        maxSteps, ctx.session().totalUsage()));
}
```

dsh §1.5.3 v1.5.6 changelog L6537:
> 新增 3 个 `AgentEvent` 子类:`ReasoningStarted(step, maxSteps)` / `ObservationAppended(step, n)` / `MaxStepsExceeded(maxSteps, totalUsage)`

### 决策

**发射路径**:**唯一**在 `LinearTurnEngine.runTurn` for-loop 因 step == maxSteps 自然 bound 结束 + 最后一次 LLM 响应仍含 tool calls 时,发 `MaxStepsExceeded`。

### 替代方案对比

| 替代方案 | 否决理由 |
|---|---|
| (a) 在 `ToolStarted` 后置守卫(每步检查)| 与 for-loop bound 重复且不可达,无法触发 |
| (b) 在 `dispatchParallel` 内检查 step | 跨职责耦合,且与 dsh §6.1 既有设计冲突 |
| (c) 在 `LinearTurnEngine` 内部 catch 处发 | 异常路径**不**应发 MaxStepsExceeded(异常优先于上限)|
| (d) 新增 `StopReason.MAX_STEPS` enum 值 | 引入 SemVer 破坏(Story #005 cancellation 状态机 + Story #010 OTel metric 依赖 enum),且 §1.5.3 设计明示 `TurnCompleted.status=END_TURN` |

**结论**: 路径 A 单点(`for-loop 自然结束 + last 含 tool calls`),StopReason 不改。

### 验证

- `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` 类已存在(`AgentEvent.java` L107-110,Lombok `@Getter` + `@RequiredArgsConstructor`),字段 `int maxSteps` + `Usage totalUsage`
- `StopReason` enum 已固化 6 值(`StopReason.java` L8-21),无 `MAX_STEPS`
- dsh §6.1 mermaid 时序图 L3539-3541 显式「opt hit maxSteps cap → MaxStepsExceeded then markDone」(与路径 A 语义对齐)

---

## 2. D-02 决策:守卫标志命名(`maxStepsHit` boolean)

### 决策

**变量名**:`boolean maxStepsHit`(Story #008 新增 LinearTurnEngine 局部变量,在 `try {` 之前声明)

### 命名依据

| 候选 | 否决理由 |
|---|---|
| `hitMaxSteps` | 语义同 `maxStepsHit`,但前缀 `hit` 更口语;选 `maxStepsHit` 与 dsh §6.1 L3619 注释风格一致 |
| `reachedMaxSteps` | 偏叙述,代码扫描更冗长 |
| `maxStepsExceeded` | 与 `AgentEvent.MaxStepsExceeded` 同名,易混淆(变量 vs 事件)|
| `capHit` | 缩写不直观,需注释解释 |

**结论**: `maxStepsHit` 命名 —— 局部变量,与 `MaxStepsExceeded` 事件类命名**明确区分**(前者 boolean 状态,后者事件类名)

### 验证

- 变量作用域:`runTurn` 方法内 `try {` 之前到 for-loop 之后,共 1 处声明 + 1 处赋值 + 1 处读取(3 处引用)
- 线程安全:boolean 局部变量,无并发问题(runTurn 单线程执行)

---

## 3. D-03 决策:Usage 引用语义(非拷贝)

### 决策

**`MaxStepsExceeded.totalUsage` 与 `TurnCompleted.usage` 同一对象引用**

### 依据

- `Usage` 是 Lombok `@Value` 不可变(隐式 final 字段 + 全构造器 + 无 setter),引用语义 = 值语义
- dsh §6.1 L3621 既有代码 `ctx.session().totalUsage()` 直接传引用,**未**做 snapshot copy
- `Usage.totalUsage = totalUsage.plus(resp.getUsage())` 每次累加产生**新**对象(L158 `totalUsage = totalUsage.plus(...)`),所以 `totalUsage` 在 turn 内是**逐步替换**的局部变量;`MaxStepsExceeded` 与 `TurnCompleted` 拿到的是**同一时刻**的 `totalUsage` 引用 —— 引用语义保证二者一致

### 验证

- `Usage` 类定义(dsh §4.4 + 既有 `Usage.java` 实现)使用 Lombok `@Value`,字段 `inputTokens` / `outputTokens` 全 `final`
- `Usage.plus(Usage other)` 返回 `new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens)` —— 不可变累加
- 单元测试 `US3-AS3` 用 `assertSame(mxe.getTotalUsage(), tc.getUsage())` 验证引用相等

### 替代方案

| 替代 | 否决理由 |
|---|---|
| (a) snapshot copy(深拷贝)| `Usage` 不可变,深拷贝无意义且增加 GC 压力(NFR-002)|
| (b) `Usage` 加 `withTokens()` 链式方法 | 引入新 API,违反 §0 "0 新增 API" 边界(本 Story 仅修改实现,不引入新接口)|
| (c) 改 `MaxStepsExceeded` 字段类型(如只传 token 计数)| 失去与 `Usage` 复用,违反既有契约 |

**结论**: 引用语义,0 内存分配,`assertSame` 验证。

---

## 4. 已有契约确认(无需改动)

| 契约 | 文件 | 行号 | 状态 |
|---|---|---|---|
| `AgentEvent.MaxStepsExceeded` 类 | `AgentEvent.java` | L107-110 | ✅ 已存在,字段 `int maxSteps` + `Usage totalUsage`,Lombok `@Getter` + `@RequiredArgsConstructor` |
| `AgentEvent.MaxStepsExceeded` 构造函数 | 同上 | L107 | ✅ public 构造,本 Story 直接 `new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` |
| `StopReason` enum | `StopReason.java` | L8-21 | ✅ 已固化 6 值:END_TURN / TOOL_USE / MAX_TOKENS / COMPACTED / CANCELLED / ERROR,无 MAX_STEPS |
| `AgentConfig.reactMaxSteps` 字段 | `AgentConfig.java` | (具体行待查) | ✅ 字段已存在,默认 50,0 = 不限 |
| `AgentFactory.create()` 校验 | `AgentFactory.java` | L233-235 | ✅ `getReactMaxSteps() <= 0` 抛 IllegalArgumentException("config.reactMaxSteps must be > 0, got " + ...),LINGS-C02 错误码 |
| `LinearTurnEngine.runTurn` 主循环 | `LinearTurnEngine.java` | L86-189 | 🆕 本 Story 修改 L113-180 |

---

## 5. 测试 fixture 复用确认

| Fixture | 文件 | 来源 Story | 本 Story 复用 |
|---|---|---|---|
| `CapturingSubscriber` | `support/CapturingSubscriber.java` | Story #004 | ✅ 复用,无需新增 |
| `EchoLlmProvider` | `support/EchoLlmProvider.java` | Story #004 | ✅ 复用(US1-AS1/AS2/AS3 + US2-AS2 + US3-AS3 + EC-7)|
| `RecordingPromptBuilder` | `support/RecordingPromptBuilder.java` | Story #004 | ✅ 复用,无需新增 |
| `SleepTool` | `support/SleepTool.java` | Story #004 | ✅ 复用(US1-AS1 / AS2 / AS3 + EC-7)|
| `SlowLlmProvider` | `support/SlowLlmProvider.java` | Story #005 | ❌ 不复用(本 Story 不测延迟)|
| `DefaultToolExecutor` | `DefaultToolExecutor.java` | Story #004 | ✅ 复用 |
| `AllowAllPermissionPolicy` | `AllowAllPermissionPolicy.java` | Story #001 | ✅ 复用 |
| `DefaultSession` / `DefaultTurnContext` | `DefaultSession.java` / `DefaultTurnContext.java` | Story #001 | ✅ 复用 |
| `AgentConfig` 工厂方法 | `LinearTurnEngineToolDispatchTest.defaultConfig(...)` | Story #004 | ✅ 复用样板 |

**新增 fixture**:
- `ThrowingLlmProvider`(US2-AS1 用)—— 1 个类,~20 行,**或**用 Mockito `@Mock LlmProvider` 抛 RuntimeException(Mock 复杂度低)
- `ThrowTool`(US2-AS2 用)—— 1 个类,~15 行,继承 `Tool` 接口实现 `execute()` 抛 RuntimeException(测试 ToolExecutor 翻译为 ToolResult.error 流程)

**总计**: 2 个小 fixture,无需大量 mock 框架

---

## 6. R-13 dep-tree 自查预期

### Story #007 baseline(`/tmp/deps-007-baseline.txt`)

dsh §10.1 L6303-6332 锁 13 项依赖 + spring-ai-bom(v1.5.7 引入)。Story #007 baseline:
- spring-boot-dependencies 3.2.5
- lombok 1.18.30
- reactive-streams 1.0.4
- jackson(在 spring-boot 内)
- junit 5 + assertj + mockito + awaitility(测试范围)
- (其它 spring-boot 子项)

### 本 Story 预期 diff

```
$ diff /tmp/deps-007-baseline.txt /tmp/deps-008-after.txt
# 期望:仅 [INFO] Total time 时间戳差异
# 0 新增依赖
```

**依据**: 本 Story 修改 `LinearTurnEngine.java`(纯逻辑改动,无新 import) + 新增 `MaxStepsGuardTest.java`(测试范围,仅 `org.junit.jupiter.api.*` + `org.assertj.core.api.Assertions` + 复用既有 fixture)。

---

## 7. 兼容性矩阵(向后兼容 Story #001—#007)

| Story | 涉及 API | 本 Story 影响 |
|---|---|---|
| #001 | `AgentFactory.create()` `LINGS-C02` 校验 | 0 改动(L233-235 复用)|
| #002 | `AgentConfig.reactMaxSteps` 字段 | 0 改动(本 Story 仅读)|
| #003 | `FlowEngine` SPI + `LinearTurnEngine` Provider | 0 改动(默认实现行为补全)|
| #004 | `dispatchParallel` + `ToolExecutor.dispatch` | 0 改动(Action 阶段不受影响)|
| #005 | `CancellationToken` + `TurnCompleted(CANCELLED)` | 0 改动(US2-AS1 验证 ERROR 路径)|
| #006 | `TenantContext.runAs(...)` 隔离 | 0 改动(单租户测试模式)|
| #007 | `AgentConfigRegistry` 冻结语义 | 0 改动(EC-9 在飞 turn `reactMaxSteps` 引用不变)|
| **#008** | **本 Story** | **🆕** `MaxStepsExceeded` 事件 + `maxStepsHit` 守卫 |

**向后兼容**: 所有 Story #001—#007 既有测试(`mvn -pl lingshu-core test`)应**全绿**,**无** regression。本 Story 在既有 `LinearTurnEngine` 内部增加 1 个 if 守卫,**不**改既有 5 个终止路径(break / cancellation / done / exception / TurnCompleted)的语义。

---

## 8. 风险登记更新(本 Story 缓解)

### R-04 ReAct 失控循环(本 Story 缓解)

| 缓解项 | 状态 | 落地 |
|---|---|---|
| (a) for-loop 上限 `step <= maxSteps` | ✅ Story #001 既有 | `LinearTurnEngine.java` L114 |
| (b) `MaxStepsExceeded` 结构化事件 | 🆕 **本 Story 落地** | `LinearTurnEngine.java` L177 之前新增 |
| (c) turn 正常 `done()`(StopReason=END_TURN) | 🆕 **本 Story 落地** | `LinearTurnEngine.java` L177 `StopReason reason = ...` 仍为 END_TURN |

**缓解率**: R-04 由 33% → 100%(3 件套全部落地)

### 新增风险

**无新增 R-XX**(本 Story 在既有 R-04 框架内补全缓解,bind score < 6 不入 dsh §17)

---

## 9. 完成标志

- [x] D-01 路径 A 单点发射 —— 已确认(dsh §6.1 L3619-3621 + §1.5.3 + mermaid L3539-3541 三方对齐)
- [x] D-02 命名 `maxStepsHit` —— 已选(避开与 `MaxStepsExceeded` 事件类名冲突)
- [x] D-03 引用语义(0 拷贝)—— 已选(NFR-002 + `Usage` 不可变)
- [x] 已有契约确认 —— 6 项既有契约 0 改动
- [x] fixture 复用确认 —— 8 项 Story #004—#005 fixture 复用 + 2 个新 fixture(ThrowingLlmProvider / ThrowTool)
- [x] R-13 dep-tree 预期 diff = 0 —— 已确认(无新 import)
- [x] 兼容性矩阵 —— 7 项 Story 0 影响 + 1 项本 Story 新增
- [x] R-04 缓解率 33% → 100%

**研究完成**,进入实施 Phase。