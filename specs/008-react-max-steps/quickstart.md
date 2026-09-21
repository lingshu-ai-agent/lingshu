# Quickstart: Story #008 react-max-steps

**Feature**: Story #008 react-max-steps — AC-07 黑盒验证
**Created**: 2026-09-21

---

## 1. 30 秒验证(US1-AS1 主路径)

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
git checkout story-008-react-max-steps
mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest#maxSteps3_llmAlwaysToolCall_emitsMaxStepsExceeded_after3rdStep
```

**期望输出**:
```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

**事件序列断言**(11 个事件):
```
RS(1,3) → TC → OA → RS(2,3) → TC → OA → RS(3,3) → TC → OA → MaxStepsExceeded(3) → TurnCompleted(END_TURN)
```

---

## 2. 全量 AC-07 黑盒(11 case / 1 文件)

```bash
mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest
```

**期望**:`Tests run: 11, Failures: 0, Errors: 0, Skipped: 0`(11 case 全绿)

---

## 3. 全量回归(Story #001—#008)

```bash
mvn -pl lingshu-core test
```

**期望**:`Tests run: <sum of all stories>, Failures: 0, Errors: 0, Skipped: 0`(Story #001—#008 全部 case 全绿,**无** regression)

---

## 4. R-13 dependency:tree 自查

```bash
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-008-after.txt
diff /tmp/deps-007-baseline.txt /tmp/deps-008-after.txt
```

**期望**:仅 `[INFO] Total time` 时间戳差异,**0 新增依赖**

---

## 5. 7 个验证场景(US1—US3 + EC)

### 5.1 场景 1:US1-AS1(主路径 AC-07)

**Input**:`reactMaxSteps=3` + LLM 每次只返 tool call(永远不 END_TURN)

**期望事件序列**(`CapturingSubscriber.events()`):
```
1. ReasoningStarted(step=1, maxSteps=3)
2. ToolCompleted(toolResult)
3. ObservationAppended(step=1, n=1)
4. ReasoningStarted(step=2, maxSteps=3)
5. ToolCompleted(toolResult)
6. ObservationAppended(step=2, n=1)
7. ReasoningStarted(step=3, maxSteps=3)
8. ToolCompleted(toolResult)
9. ObservationAppended(step=3, n=1)
10. MaxStepsExceeded(maxSteps=3, totalUsage)
11. TurnCompleted(reason=END_TURN, usage=totalUsage)
```

**断言**:
- `events.size() == 11`
- `events.get(9)` instanceof `MaxStepsExceeded` + `getMaxSteps() == 3`
- `events.get(10)` instanceof `TurnCompleted` + `getReason() == END_TURN`
- `assertSame(((MaxStepsExceeded) events.get(9)).getTotalUsage(), ((TurnCompleted) events.get(10)).getUsage())`(同一对象引用)

---

### 5.2 场景 2:US1-AS2(自然 END_TURN,未触上限)

**Input**:`reactMaxSteps=5` + LLM 第 1-3 步返 tool call + 第 4 步 END_TURN

**期望事件序列**(10 个事件):
```
1-9. 同 US1-AS1 前 9 个(RS×3 + TC×3 + OA×3)
10. TurnCompleted(reason=END_TURN)
```

**断言**:
- `events.size() == 10`
- 无 `MaxStepsExceeded` 事件(`events.stream().noneMatch(e -> e instanceof MaxStepsExceeded)`)

---

### 5.3 场景 3:US1-AS3(maxSteps=1 极小值)

**Input**:`reactMaxSteps=1` + LLM 返 tool call(无 END_TURN)

**期望事件序列**(5 个事件):
```
1. ReasoningStarted(step=1, maxSteps=1)
2. ToolCompleted
3. ObservationAppended(step=1, n=1)
4. MaxStepsExceeded(maxSteps=1)
5. TurnCompleted(reason=END_TURN)
```

**断言**:
- `events.size() == 5`
- 末 2 个事件:`MaxStepsExceeded(1)` → `TurnCompleted(END_TURN)`

---

### 5.4 场景 4:US2-AS1(异常路径,**不**发 MaxStepsExceeded)

**Input**:`reactMaxSteps=2` + 自写 `ThrowingLlmProvider` 第 1 步抛 RuntimeException

**期望事件序列**(3 个事件):
```
1. ReasoningStarted(step=1, maxSteps=2)
2. ErrorEvent(throwable)
3. TurnCompleted(reason=ERROR)
```

**断言**:
- `events.size() == 3`
- 无 `MaxStepsExceeded` 事件
- `TurnCompleted.reason == ERROR`

---

### 5.5 场景 5:US2-AS2(异常不影响 step 计数,末步仍触 MaxStepsExceeded)

**Input**:`reactMaxSteps=3` + 3 tool-call(tool 抛 RuntimeException,被 ToolExecutor 翻译为 ToolResult.error)

**期望事件序列**(同 US1-AS1,11 个事件):
- 异常在 ToolExecutor 层翻译为 `ToolResult.isError=true`,**不**抛回 engine
- step 计数继续
- 末步触发 `MaxStepsExceeded`

---

### 5.6 场景 6:US3-AS3(顺序保证 + 引用语义)

**Input**:同 US1-AS1

**断言**:
```java
List<AgentEvent> events = sink.events();
AgentEvent secondLast = events.get(events.size() - 2);
AgentEvent last = events.get(events.size() - 1);
assertThat(secondLast).isInstanceOf(MaxStepsExceeded.class);
assertThat(last).isInstanceOf(TurnCompleted.class);
assertThat(((MaxStepsExceeded) secondLast).getMaxSteps()).isEqualTo(3);
assertThat(((TurnCompleted) last).getReason()).isEqualTo(StopReason.END_TURN);
assertSame(((MaxStepsExceeded) secondLast).getTotalUsage(),
           ((TurnCompleted) last).getUsage());
```

---

### 5.7 场景 7:EC-7(step==maxSteps 但 last 无 tool calls → 不发)

**Input**:`reactMaxSteps=3` + 2 tool-call + 1 END_TURN(第 3 步 LLM 返 no-tool-call)

**期望事件序列**(8 个事件):
```
1-6. RS(1,3) + TC + OA + RS(2,3) + TC + OA
7. RS(3,3)(进入 for-loop 第 3 次迭代)
8. TurnCompleted(reason=END_TURN)(break at L162-165)
```

**关键**:
- 第 3 步进入循环 + LLM 返 no-tool-call → break at L162-165 → `step == 3` 但 for-loop **未**自然 bound → `maxStepsHit = false`(L174 守卫在 break 之后,不执行)
- `MaxStepsExceeded` **不**发射
- 末事件是 `TurnCompleted(END_TURN)`,size=8

**断言**:
- `events.size() == 8`
- `events.get(7)` instanceof `TurnCompleted` + `getReason() == END_TURN`
- 无 `MaxStepsExceeded` 事件

---

## 6. CI 集成(可选)

### 6.1 GitHub Actions matrix(预期)

`lingshu` 仓 `.github/workflows/maven.yml` 已有 JDK 17 / JDK 21 matrix。

**本 Story 期望 CI green**:
```yaml
strategy:
  matrix:
    java: [17, 21]
steps:
  - run: mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest
  - run: mvn -pl lingshu-core test  # 全量回归
```

### 6.2 JDK 8 兼容性二次确认

编译目标 `<source>1.8</source>`,**不**使用:
- ❌ `var`(本 Story 用 `int step` + `boolean maxStepsHit`,显式类型)
- ❌ `record`(Lombok `@Value` 等价,既有)
- ❌ `List.of(...)`(`Collections.emptyList()`,既有)
- ❌ `sealed` / `permits`(既有 class 模式)

**期望**:`mvn -pl lingshu-core compile` green on JDK 17 + 21。

---

## 7. 端到端 demo(可选,smoke test)

若需要在真实 JVM 跑 AC-07 demo(非必需,本 Story L1 Unit 已覆盖),可写 `MaxStepsGuardIT.java`:

```java
@SpringBootTest
class MaxStepsGuardIT {
    @Autowired AgentFactory factory;
    @Autowired AgentConfigRegistry registry;

    @Test
    @DisplayName("AC-07 E2E: reactMaxSteps=3 → MaxStepsExceeded after 3 steps")
    void ac07_maxSteps3_emitsMaxStepsExceeded() {
        AgentConfig cfg = registry.current().withReactMaxSteps(3);
        Agent agent = factory.create(cfg);
        // Mock LLM that always returns tool calls (use TestConfiguration override)
        // ... (omitted, see Story #008 for full IT if needed)
    }
}
```

**本 Story 不要求 E2E IT**(L1 Unit 已覆盖 5 路径分支 + 11 case),IT 推迟到 Story #010(OTel observability 阶段做端到端 metric 验证)。

---

## 8. 完成标志

- [x] 30 秒验证(US1-AS1)命令 + 期望输出
- [x] 全量 AC-07 黑盒(11 case)
- [x] 全量回归(Story #001—#008)
- [x] R-13 dep-tree diff = 0
- [x] 7 个验证场景(US1—US3 + EC-5 + EC-7)
- [x] CI 集成(JDK 17/21 matrix)
- [x] JDK 8 兼容二次确认(无 var / record / List.of / sealed)
- [x] E2E demo(可选,推迟到 Story #010)

**Quickstart 完整**,Story #008 实施可启动。