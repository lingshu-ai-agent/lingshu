# Data Model: Story #008 react-max-steps

**Feature**: Story #008 react-max-steps
**Created**: 2026-09-21
**Status**: Complete(本 Story **0 新增**数据模型)

---

## 0. 结论

**本 Story 0 新增数据模型 / 0 新增字段 / 0 新增枚举值 / 0 新增 type alias**。

所有相关数据模型(`AgentEvent.MaxStepsExceeded` / `StopReason` / `Usage` / `AgentConfig.reactMaxSteps`)已在 Story #001—#003 + dsh §1.5.3 v1.5.6 changelog 落地,本 Story 仅修改 `LinearTurnEngine.runTurn` **行为**(发射时机与路径分支判定),不改任何数据结构。

---

## 1. 既有数据模型(本 Story 复用,不改)

### 1.1 `AgentEvent.MaxStepsExceeded`(类)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/event/AgentEvent.java` L107-110

```java
@Getter
@RequiredArgsConstructor
public static class MaxStepsExceeded extends AgentEvent {
    private final int maxSteps;
    private final Usage totalUsage;
}
```

**字段语义**:
- `int maxSteps` —— 该 turn 的上限(`config.getReactMaxSteps()`),**不**是实际触发的 step 数(step 总是 == maxSteps,见 dsh §6.1 L3619-3621)
- `Usage totalUsage` —— 该 turn 截至 MaxStepsExceeded 触发时的累计 token 用量(同 `TurnCompleted.usage`,引用语义)

**不可变**:`final` 字段 + Lombok `@Value` 等价(无 setter,无修改入口)

**线程安全**:对象不可变,可跨线程安全传递(尽管本 Story 单线程 turn)

---

### 1.2 `StopReason`(enum)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/message/StopReason.java` L8-21

```java
public enum StopReason {
    END_TURN,    // Model returned end_turn / stop — natural finish
    TOOL_USE,    // Model emitted tool calls — engine must dispatch then loop
    MAX_TOKENS,  // Hit the configured max_tokens limit before finishing
    COMPACTED,   // Compactor truncated the history mid-turn
    CANCELLED,   // User pressed Ctrl+C / engine.markDone() / timeout cascaded
    ERROR        // Unhandled exception surfaced to sink
}
```

**本 Story 影响**:**0 改动**(不变更 enum,不新增 `MAX_STEPS`)

**理由**:Story #005 `CancellationToken` 状态机(`CANCELLED` vs `END_TURN`)+ Story #010 OTel metric(`turn.stop_reason` 标签字段)均依赖 6 值 enum,新增值破坏 SemVer 兼容;dsh §1.5.3 设计明示 max-steps 触发场景 `TurnCompleted.reason = END_TURN`。

---

### 1.3 `Usage`(值对象,Lombok @Value)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/message/Usage.java`(Story #001 既有)

**字段**:`final long inputTokens` + `final long outputTokens`(Lombok `@Value` 不可变)

**关键方法**:
- `static Usage zero()` —— 零初始值
- `Usage plus(Usage other)` —— 不可变累加(`new Usage(inputTokens + other.inputTokens, ...)`)

**本 Story 使用**:`totalUsage = totalUsage.plus(resp.getUsage())`(`LinearTurnEngine.java` L158)→ 在 `MaxStepsExceeded` + `TurnCompleted` 双重发射时复用同一对象引用。

---

### 1.4 `AgentConfig.reactMaxSteps`(字段)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(具体字段位置待 grep)

**类型**:`int`

**默认**:`50`

**特殊值**:`0` = 不限(被 `AgentFactory.create()` 启动期校验 `LINGS-C02` 抛 IllegalArgumentException 拒绝)

**本 Story 使用**:`int maxSteps = ctx.config().getReactMaxSteps()`(`LinearTurnEngine.java` L89)

---

## 2. 状态机(本 Story 不改 turn 主流程,只新增 1 个守卫标志)

### `LinearTurnEngine.runTurn` 终止路径(5 条)

```
                    ┌─────────────────────────────────────┐
                    │  for (int step = 1; step <= maxSteps; step++) │
                    └────────────────┬────────────────────┘
                                     │
            ┌────────────────────────┼────────────────────────┐
            │                        │                        │
            ▼                        ▼                        ▼
   ┌─────────────────┐   ┌─────────────────────┐   ┌─────────────────┐
   │ 路径 A: 自然 bound │   │ 路径 B: break 无 tool │   │ 路径 C: break ctx.done │
   │ step == maxSteps │   │ (step < maxSteps)    │   │ (step < maxSteps) │
   │ + last 含 toolCalls│  │                      │   │                  │
   └────────┬────────┘   └──────────┬──────────┘   └────────┬────────┘
            │                       │                       │
            ▼                       ▼                       ▼
   ┌─────────────────┐   ┌─────────────────────┐   ┌─────────────────┐
   │ maxStepsHit=true │   │ maxStepsHit=false   │   │ maxStepsHit=false │
   │ last 含 toolCalls│   │                      │   │                  │
   └────────┬────────┘   └──────────┬──────────┘   └────────┬────────┘
            │                       │                       │
            ▼                       │                       │
   ┌─────────────────┐              │                       │
   │ sink.onNext(     │              │                       │
   │ MaxStepsExceeded │              │                       │
   │ ) 🆕             │              │                       │
   └────────┬────────┘              │                       │
            │                       │                       │
            └───────────────────────┴───────────────────────┘
                                     │
                                     ▼
                          ┌─────────────────────┐
                          │ sink.onNext(         │
                          │ TurnCompleted(       │
                          │ reason=END_TURN,     │
                          │ totalUsage))         │
                          └─────────────────────┘

   路径 D: cancellation return → 直接 TurnCompleted(CANCELLED)(不进 maxStepsHit 分支)
   路径 E: catch RuntimeException → ErrorEvent + TurnCompleted(ERROR)(不进 maxStepsHit 分支)
```

**关键状态**:
- `boolean maxStepsHit = false` —— 🆕 本 Story 新增局部变量,在 for-loop 自然结束分支(L174 之后)被步骤置 `true`(仅当 `step == maxSteps`)
- `LlmResponse last` —— 既有局部变量(L96 + L159),记录最后一次 LLM 响应;`last.getToolCalls()` 判定是否仍需继续
- `Usage totalUsage` —— 既有局部变量(L97 + L158),记录累计 token 用量;`MaxStepsExceeded` 与 `TurnCompleted` 复用同一引用

### 守卫判定逻辑(本 Story 核心)

```java
boolean maxStepsHit = false;   // 🆕
for (int step = 1; step <= maxSteps; step++) {
    if (ctx.done()) break;                       // 路径 C
    if (ctx.cancellation().isCancelled()) { ... return; }  // 路径 D
    ...
    if (resp.getToolCalls().isEmpty()) break;    // 路径 B
    ...
    // 🆕 Story #008 — 仅当 step == maxSteps 且 for-loop 没因 break 退出时,守卫触发
    if (step == maxSteps) {
        maxStepsHit = true;
    }
}
// 🆕 Story #008 — 守卫 + last 联合判定
if (maxStepsHit
    && last != null
    && last.getToolCalls() != null
    && !last.getToolCalls().isEmpty()) {
    sink.onNext(new AgentEvent.MaxStepsExceeded(maxSteps, totalUsage));
}
sink.onNext(new AgentEvent.TurnCompleted(reason, totalUsage));   // 既有 L177-180
```

**判定矩阵**:

| 路径 | step 终值 | break 类型 | `maxStepsHit` | `last.getToolCalls()` | 发 `MaxStepsExceeded`? |
|---|---|---|---|---|---|
| **A**(自然 bound + last 含 tool calls)| `maxSteps` | 无 break | `true` | 非空 | ✅ **是** |
| **A'**(自然 bound + last 无 tool calls)¹ | `maxSteps` | break at L162-165(`if isEmpty`)| `false` | 空 | ❌ 否 |
| **B**(break 无 tool calls)| `< maxSteps` | break at L162-165 | `false` | 空 | ❌ 否 |
| **C**(break ctx.done)| `< maxSteps` | break at L115-118 | `false` | 任意 | ❌ 否 |
| **D**(cancellation return)| 任意 | return at L128 | (不进守卫)| 任意 | ❌ 否 |
| **E**(exception catch)| 任意 | catch at L184 | (不进守卫)| (cancelled)| ❌ 否 |

¹ **A'路径**:step == maxSteps 且陆姆 LLM 在最后一次返 no-tool-call + `if (step == maxSteps) maxStepsHit = true` 在 L174 之后执行 — **但** break 在 L162-165 提前退出 for-loop,**所以** `maxStepsHit` 永远是 `false`(L162-165 的 break 在 L174 的 `if (step == maxSteps)` 之前)

**结论**:L174 `if (step == maxSteps) maxStepsHit = true;` 在 break 之后,只有 for-loop 自然结束(无 break)才会执行,所以 maxStepsHit 严格只对应「路径 A」。

---

## 3. 并发模型(无并发,单线程 turn)

### 局部变量线程安全性

| 变量 | 类型 | 作用域 | 并发安全 |
|---|---|---|---|
| `maxStepsHit` | `boolean` | `runTurn` 局部 | ✅ 单线程 turn,无并发 |
| `last` | `LlmResponse` | `runTurn` 局部 | ✅ 同上 |
| `totalUsage` | `Usage` | `runTurn` 局部 | ✅ 同上(`@Value` 不可变,引用赋值非共享)|

**Story #007 兼容**:`AgentConfigRegistry` 冻结 `AgentConfig.reactMaxSteps` 引用 → 在飞 turn 的 `maxSteps` 是 startup 时刻快照,即使中途 `registry.publish(newCfg)` 也不影响 —— Story #008 EC-9 自然兼容。

---

## 4. 兼容性矩阵

| 既有契约 | 本 Story 影响 | 测试 |
|---|---|---|
| `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` 字段 | **0 改动**(US3-AS1 反射验证)| `MaxStepsGuardTest.US3-AS1` |
| `StopReason` enum 值 | **0 改动**(US3-AS2 反射验证)| `MaxStepsGuardTest.US3-AS2` |
| `Usage` 不可变 + `plus()` | **0 改动**(US3-AS3 引用验证)| `MaxStepsGuardTest.US3-AS3` |
| `AgentConfig.reactMaxSteps` 字段 | **0 改动** | (既有用例覆盖) |
| `AgentFactory.create()` 启动期校验 | **0 改动**(L233-235 复用)| `MaxStepsGuardTest.US1-AS4` |
| `LinearTurnEngine.runTurn` 既有 4 终止路径 | **0 改动** | `MaxStepsGuardTest.EC-5` + 全量回归 |

---

## 5. 数据流图(本 Story 1 处新增)

```
LinearTurnEngine.runTurn
│
├─ [入口] maxSteps = config.getReactMaxSteps()       (L89,既有)
├─ [入口] maxStepsHit = false                         (L113 之前,🆕)
├─ [入口] totalUsage = Usage.zero()                   (L97,既有)
├─ [入口] last = null                                  (L96,既有)
│
├─ for-loop(step=1..maxSteps)
│  ├─ ReasoningStarted(step, maxSteps)                (L131,既有)
│  ├─ llmProvider.stream(prompt, ctx, sink)            (L138,既有)
│  ├─ last = resp                                      (L159,既有)
│  ├─ if (resp.getToolCalls().isEmpty()) break         (L162-165,既有 → 路径 B)
│  ├─ dispatchParallel(...)                            (L168,既有)
│  ├─ ObservationAppended(step, results.length)        (L174,既有)
│  └─ 🆕 if (step == maxSteps) maxStepsHit = true      (L174 之后,新)
│
├─ [for-loop 后] 判定
│  └─ 🆕 if (maxStepsHit && last.getToolCalls() 非空)
│     └─ sink.onNext(MaxStepsExceeded(maxSteps, totalUsage))   (🆕)
│
└─ sink.onNext(TurnCompleted(reason, totalUsage))      (L177-180,既有)
   reason = (last.getStopReason() != null) ? last.getStopReason() : END_TURN
```

---

## 6. 完成标志

- [x] 0 新增数据模型
- [x] 0 新增字段
- [x] 0 新增枚举值
- [x] 4 项既有数据模型(`MaxStepsExceeded` / `StopReason` / `Usage` / `AgentConfig.reactMaxSteps`)**0 改动**
- [x] 状态机清晰:5 路径分支 + 1 个守卫标志 `maxStepsHit`
- [x] 数据流图:1 处新增(`maxStepsHit` 声明 + L174 置位 + L177 读取)
- [x] 并发模型:无并发(单线程 turn)
- [x] 兼容性:6 项既有契约 0 影响

**数据模型分析完成**。