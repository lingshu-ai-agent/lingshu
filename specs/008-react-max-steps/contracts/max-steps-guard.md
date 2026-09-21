# Contract: `MaxStepsExceeded` 发射契约

**Contract ID**: `lingshu.contract.max-steps-guard.v1`
**Feature**: Story #008 react-max-steps
**Created**: 2026-09-21
**Status**: Stable

---

## 1. 契约方

| 角色 | 类/接口 | 文件 |
|---|---|---|
| **Producer**(发射方)| `LinearTurnEngine.runTurn` | `LinearTurnEngine.java` L86-189 |
| **Consumer**(订阅方)| `Agent` UI / 日志 / OTel / 用户实现 `Subscriber<AgentEvent>` | `Agent.java` + 用户代码 |

---

## 2. 触发条件(Producer 侧)

### 2.1 必须同时满足的 4 项条件(AND)

1. **for-loop 自然结束**:无 `break`(`L117` ctx.done() / `L164` no-tool-call)/ `return`(`L128`(cancellation)/ `L148`(waitForLlm cancelled))/ `catch`(`L184`)等提前退出路径
2. **step 终值 == maxSteps**:`for (int step = 1; step <= maxSteps; step++)` 自然 bound 结束,step 此时 == maxSteps + 1(L114 bound 语义)
3. **`last != null`**:最后一次 LLM 响应已赋值(L159 `last = resp`)
4. **`last.getToolCalls() != null && !isEmpty()`**:最后一次响应仍含 tool calls,LLM 未给 final answer

### 2.2 显式**不**触发的场景(OR 任一)

- (a) `ctx.done()` 为 true → break at L117 → 走路径 C,**不**发
- (b) `ctx.cancellation().isCancelled()` 为 true → return at L128 → 走路径 D,**不**发
- (c) `waitForLlm` 因 cancellation 返 null → return at L148 → **不**发
- (d) `resp.getToolCalls()` 为 null 或 empty → break at L164 → 走路径 B,**不**发
- (e) `try` 内抛 RuntimeException → catch at L184 → 走路径 E → **不**发
- (f) `maxSteps == 0` → `AgentFactory.create()` 启动期校验 `LINGS-C02` 抛异常,engine 不被调用 → **不**发

---

## 3. 事件顺序(Producer 侧)

**必须**按以下顺序发射(`assertSame` 验证):

```
1. [本步内] sink.onNext(MaxStepsExceeded(maxSteps, totalUsage))    ← 🆕 Story #008
2. sink.onNext(TurnCompleted(END_TURN, totalUsage))                 ← 既有 L177-180
```

**强约束**(FR-005):
- `MaxStepsExceeded` 必须在 `TurnCompleted` **之前**发射
- 两者之间**禁止**插入任何其他事件(无 `ObservationAppended` / `ReasoningStarted` / `TextDelta` 等)
- `MaxStepsExceeded.totalUsage == TurnCompleted.usage`(同一对象引用,`assertSame` 验证,NFR-002 0 内存分配)

---

## 4. 事件字段契约(Consumer 侧)

### 4.1 `AgentEvent.MaxStepsExceeded`

```java
@Getter
@RequiredArgsConstructor
public static class MaxStepsExceeded extends AgentEvent {
    private final int maxSteps;      // = config.getReactMaxSteps()(turn 启动时刻)
    private final Usage totalUsage;  // = turn 截至触发的累计 Usage(同 TurnCompleted.usage)
}
```

**字段语义**:
- `maxSteps`:不是实际跑了几步(step 总是 == maxSteps),而是**配置上限**。Consumer UI 可显示「已达到 max-steps 上限 50」,语义清晰。
- `totalUsage`:截至 `MaxStepsExceeded` 发射时的累计 token。Consumer 可用此字段做 cost 计量 / 提示用户「本 turn 已消耗 X tokens 因 max-steps 终止」。

**不可变**:Lombok `@Value` 等价(隐式 final + 无 setter),跨线程安全。

### 4.2 `TurnCompleted.reason`

**Max-steps 触发场景**:`reason = END_TURN`(既有 L177-179 行为,不改)

**Consumer 解读**:
- `MaxStepsExceeded` 出现 → UI 提示「达到 max-steps 上限」
- `TurnCompleted(reason=END_TURN)` 紧跟其后 → 终止语义
- 两者组合语义:**正常 END_TURN,但被 max-steps 强杀**(非 cancelled,非 error)

---

## 5. 错误语义

### 5.1 不抛异常

`LinearTurnEngine.runTurn` 在 max-steps 触发场景**不**抛异常,**不**进入 `catch` 分支(US2-AS1 验证 catch 仅在 exception catch 时触发)。

### 5.2 与 `StopReason.ERROR` 的关系

`MaxStepsExceeded` 与 `TurnCompleted(reason=END_TURN)` 组合**不**是 ERROR —— max-steps 是「正常终止的子情形」,不是错误。

**Consumer 反例**(❌ 错误实现):
```java
events.stream()
    .filter(e -> e instanceof TurnCompleted)
    .map(e -> ((TurnCompleted) e).getReason())
    .filter(r -> r == ERROR)
    .forEach(r -> log.error("Turn failed!"));
```
**正确实现**(✅):
```java
boolean maxStepsHit = events.stream()
    .anyMatch(e -> e instanceof MaxStepsExceeded);
events.stream()
    .filter(e -> e instanceof TurnCompleted)
    .map(e -> ((TurnCompleted) e).getReason())
    .forEach(r -> log.info("Turn ended: reason={}, maxStepsHit={}", r, maxStepsHit));
```

---

## 6. 性能契约

| 指标 | 预算 | 验证 |
|---|---|---|
| `MaxStepsExceeded` 发射开销 | **< 1μs / turn**(单对象构造 + 1 次 `sink.onNext`)| US1-AS1 单 turn 11 个事件全部发射 < 1ms |
| 内存分配 | **0 拷贝**(`totalUsage` 引用语义,NFR-002)| `assertSame(mxe.totalUsage, tc.usage)` 验证 |
| 与 `TurnCompleted` 之间间隔 | **0**(同 `sink.onNext` 链,无 IO / 无 await)| `CapturingSubscriber.events()` 顺序断言 |

---

## 7. 版本契约(SemVer)

| 改动 | SemVer | Story |
|---|---|---|
| **新增** `AgentEvent.MaxStepsExceeded` 事件类型 | MINOR(v1.0.0 之前不算,Story #001 既有,本 Story **0 新增**)| (已存在 `AgentEvent.java` L107-110)|
| **新增** `MaxStepsExceeded` 发射路径 | MINOR(默认实现行为补全,**不**改 SPI)| 🆕 Story #008 |
| **改** `StopReason` enum | **MAJOR**(break Story #005 / Story #010 / OTel tag)| ❌ 本 Story **不**改 |
| **改** `TurnCompleted` 字段 | **MAJOR** | ❌ 本 Story **不**改 |
| **改** `LinearTurnEngine` 既有 4 终止路径语义 | MAJOR(用户可能依赖 break/cancel 路径)| ❌ 本 Story **不**改 |

**SemVer 影响**:MINOR 增量(本 Story 在 v1.0.0 之前的 development cycle,**不**破坏既有契约)。

---

## 8. 测试契约

### 8.1 黑盒测试矩阵

| 场景 | 输入 | 期望事件序列 |
|---|---|---|
| `US1-AS1` | `reactMaxSteps=3` + 3 tool-call | `RS TC OA RS TC OA RS TC OA MaxStepsExceeded TurnCompleted`(11)|
| `US1-AS2` | `reactMaxSteps=5` + 3 tool-call + 1 END_TURN | `RS TC OA RS TC OA RS TC OA RS TurnCompleted`(10,无 MaxStepsExceeded)|
| `US1-AS3` | `reactMaxSteps=1` + 1 tool-call | `RS TC OA MaxStepsExceeded TurnCompleted`(5)|
| `US1-AS4` | `reactMaxSteps=0` | `AgentFactory.create()` 抛 IllegalArgumentException |
| `US2-AS1` | `reactMaxSteps=2` + LLM 第 1 步抛 RuntimeException | `RS ErrorEvent TurnCompleted(ERROR)`(3)|
| `US2-AS2` | `reactMaxSteps=3` + 3 tool-call(tool 异常)| `RS TC OA RS TC OA RS TC OA MaxStepsExceeded TurnCompleted`(11,异常路径不影响 step 计数)|
| `US3-AS1` | 反射 `MaxStepsExceeded.class` | 字段 `int maxSteps` + `Usage totalUsage` + `@Getter` |
| `US3-AS2` | `StopReason.values()` | 6 值,无 `MAX_STEPS` |
| `US3-AS3` | 同 US1-AS1 + 检查末 2 个事件 | `MaxStepsExceeded → TurnCompleted` + `assertSame(usage)` |
| `EC-5` | `reactMaxSteps=10` + cancellation 在第 5 步 | `5×(RS+TC+OA)` + 1 `TurnCompleted(CANCELLED)`(无 MaxStepsExceeded)|
| `EC-7` | `reactMaxSteps=3` + 2 tool-call + 1 END_TURN(第 3 步)| `RS TC OA RS TC OA RS TurnCompleted`(8,step 3 break,**不**是自然 bound)|

### 8.2 测试命令

```bash
mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest
# 期望:11 / 11 green
```

### 8.3 回归测试

```bash
mvn -pl lingshu-core test
# 期望:Story #001—#008 全部 case 全绿
```

---

## 9. 已知限制

### 9.1 `reactMaxSteps=0` 不可用

dsh §0 + §8.1 规定 `reactMaxSteps=0` 表示「不限」,但 `AgentFactory.create()` 启动期校验 `getReactMaxSteps() <= 0` **拒绝**(L233-235)。

**当前行为**:`reactMaxSteps=0` 抛 `LINGS-C02 CONFIG_VALIDATION_FAILED`。

**未来 Story 计划**:若需要「不限」语义,需:
1. 改 `AgentFactory.create()` L233-235 校验(`<= 0` → `< 0`)
2. 改 `LinearTurnEngine.runTurn` for-loop bound(`step <= maxSteps` → `step <= Integer.MAX_VALUE` 或 `while(true)` + 显式 break)
3. 同步 `MaxStepsExceeded` 守卫条件(`step == maxSteps` → never hit when maxSteps=Integer.MAX_VALUE)
4. 改文档 + AC

**本 Story 不涉及**:保持与 Story #001 既有校验一致(FR-006 复用 L233-235)。

### 9.2 `maxSteps=1` 边界

`reactMaxSteps=1` + LLM 返 tool call → 第 1 步 `ObservationAppended` 之后发 `MaxStepsExceeded(1)`,**不**回 loop head(US1-AS3 验证)。这是预期行为:**1 步就走完了**,没有「思考 1 步 + 再思考 1 步」的概念。

---

## 10. 完成标志

- [x] Producer / Consumer 角色清晰(`LinearTurnEngine.runTurn` 发射,`Agent` + 用户 `Subscriber` 订阅)
- [x] 触发条件 4 项 AND + 6 项显式 OR 不触发
- [x] 事件顺序:`MaxStepsExceeded` → `TurnCompleted`,强约束 FR-005
- [x] 字段语义:`maxSteps` 是配置上限 + `totalUsage` 是累计 token
- [x] 错误语义:**不**抛异常,**不**是 ERROR,组合语义「正常 END_TURN + max-steps 强杀」
- [x] 性能契约:< 1μs / 0 拷贝 / 0 间隔
- [x] SemVer:MINOR 增量,0 MAJOR 破坏
- [x] 测试契约:11 个 L1 Unit case 全覆盖 5 路径分支 + 反射 + 边界

**契约稳定**,进入实施 Phase。