# Story #050 Tasks — `ask-loop-guard` + step-reset SSE 收口(2-bug 合修)

> Spec/Plan/Tasks 三件套之第三件。本 Story 关闭 demo-product ask-mode 实测发现的 2 类生产 bug:
> - **Bug-1**:SSE 流 `step` 计数器在 LLM `message_start` 时被 `AnthropicStreamParser.java:208` 硬编码 reset 为 `step=1`(占位 stub from Story #027b 落地时)
> - **Bug-2**:`LinearTurnEngine.dispatchWithPolicy` AskUser branch 缺 per-`toolUseId` Deny-once 守卫 → 同 tool 被 deny 后 LLM 持续重发 → ApprovalRegistry 无限循环弹 modal
>
> **设计哲学**:不动 SPI / 接口契约 / 公开方法签名;纯事件类型扩展(`AgentEvent.LlmStreamStarted`)+ 字段新增(`LinearTurnEngine.attemptedDeny`)+ 守卫短路。镜像 Story #048 hang-safety sentinel 思路(「在错误路径先 commit 占位事件,避免主循环 invariant 失败」)反向应用(「在 deny 路径短路,避免 LLM 反复重发」)。
>
> **Bug-3 修复 deferred**:`ApprovalRegistry.evictBySessionPrefix` race condition 推到 OQ-Future / Story #051,本 Story 4 prod 文件改动严守 ≤5 预算边界。
>
> **任务组织**:5 阶段共 **20 个任务**(T-01 ~ T-20),Phase 1/3/5 `[P]` 标记并行(同 Phase 内文件互不依赖),Phase 2/4 严格串行(每步是下一步的前置)。

---

## Phase 0 — Pre-flight 检查(开始前必跑)

| ID | 任务 | 期望输出 | 状态 |
|---|---|---|---|
| T-00.1 | `git status` 干净(worktree 无未提交改动) | `nothing to commit, working tree clean` | ⏳ |
| T-00.2 | `git log -1 --oneline` 当前 commit | 确认 commit hash(后续 R-13 baseline 比对锚点)| ⏳ |
| T-00.3 | `mvn -pl lingshu-core -am compile -q` 基线编译通过 | BUILD SUCCESS | ⏳ |
| T-00.4 | `mvn -pl lingshu-core test -q` 基线测试通过(包含 Story #049 11 case + Story #048 4 case + Story #047 8 case + Story #046 16 case + Story #041 9 case + Story #030 23 case = 71 case AskUser/ApprovalGate 相关) | 全部通过(2 MCP heartbeat flake pre-existing) | ⏳ |
| T-00.5 | baseline 镜像:`git show HEAD:lingshu-core/pom.xml \| md5sum` | 记录 md5 值作为 R-13 (d) **第 33 次 PASS** baseline(累计 32 个 Story PASS) | ⏳ |
| T-00.6 | dsh 头部版本号 = `v1.5.61`(Story #049 落地后最新)| 与 CLAUDE.md 「对应设计文档」字段对齐 | ⏳ |
| T-00.7 | 验证 `AnthropicStreamParser.java:208` 当前 emit 是 `new AgentEvent.ReasoningStarted(1, 50)` 硬编码占位(Bug-1 root cause 锚点)| grep 锚定行号,记录 T-06 改前 anchor | ⏳ |
| T-00.8 | 验证 `LinearTurnEngine.dispatchWithPolicy` AskUser branch 当前**无** per-toolUseId deny-once 守卫(Bug-2 root cause 锚点)| grep 锚定行号,记录 T-07 改前 anchor | ⏳ |

> **Why Phase 0**:Story 实施必须从干净 baseline 开始;baseline 镜像锚点是 R-13 mitigation (d) **第 33 次 PASS** 验证的前置(pre/post `git show HEAD:lingshu-core/pom.xml | md5sum` 必须与 baseline 一致);T-00.7 / T-00.8 锚点记录让 T-06 / T-07 改动有明确对照,便于 code review 和后续 git blame 回溯。

---

## Phase 1 — Setup(并行,4 文件改动)

> **可并行原因**:4 个文件改动互不依赖,可同时编辑。
>
> - T-01(`AgentEvent` nested class)只扩事件 hierarchy
> - T-02(`AgentEventMapper`)只加 1 行 mapper case
> - T-03(`LinearTurnEngine` 字段声明)只新增 1 个 private field + turn-start reset 句柄
> - T-04(`AgentEventLlmStreamStartedTest`)只 test 文件新建

### T-01 [P] `AgentEvent.java` 新增 `LlmStreamStarted` nested class

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/event/AgentEvent.java`

**改动**(在 `ReasoningStarted` 附近 append):

```java
/** Story #050 — Bug-1 SSE step counter reset 修复。
 *  表示「LLM 流的 message_start」事件(Anthropic protocol layer 语义),
 *  与 ReAct 主循环 step 解耦。不携带 step/maxSteps 字段(避免 SSE 老前端误读)。
 *  老事件 {@link ReasoningStarted} 仍由 LinearTurnEngine 主循环发射,语义不变。*/
@Value
public final class LlmStreamStarted {
    String streamId;          // Anthropic message.id
    int maxOutputTokens;      // Anthropic message.model max_output_tokens
}
```

**约束**:
- 用 Lombok `@Value`(immutable final class + accessor + 2 字段全 final)
- JDK 8 兼容(无 record / sealed / var)
- 文件其他 nested class 不动(`ReasoningStarted` / `TextDelta` / `ToolStarted` / `ToolResult` / `ApprovalRequired` / `TurnCompleted` / `Compaction` / `SessionSaved` 0 改动)

**Why needed**:`AnthropicStreamParser` 当前 L208 硬编码 emit `ReasoningStarted(1, 50)` 是 #027b 占位 stub;新事件 `LlmStreamStarted(streamId, maxOutputTokens)` 表达「LLM 内部流的 message_start」,与 `ReasoningStarted`(ReAct 主循环 step start)语义解耦。

**AC mapping**:AC-050-1(`LlmStreamStarted` 不可变事件类 + 2 个 accessor + final class)

**预估**:18 行(含 Javadoc)

---

### T-02 [P] `AgentEventMapper.java` 加 1 行 mapper case

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/agent/AgentEventMapper.java`(实际路径以 repo 现状为准,mirror Story #042 path)

**改动 1 行**(在现有 `reasoning.started` mapper case 之后插入):

```java
/** Story #050 — LLM message_start 事件独立 SSE 映射,不含 step/maxSteps 字段。*/
case AgentEvent.LlmStreamStarted e -> Map.of(
    "type", "llm.stream.started",
    "streamId", e.getStreamId(),
    "maxOutputTokens", e.getMaxOutputTokens());
```

**约束**:
- 用 Java 8 兼容的 `Map.of(...)`(JDK 9+)改用 `Collections.unmodifiableMap(...)` 或 `new HashMap<>()` (依据项目已有 mapper 风格)
- 老 SSE frontend `addEventListener("llm.stream.started", ...)` 不存在时**不**抛错(JS EventSource spec:unhandled type 走 default branch silently)
- 既有事件 mapper case 0 改动

**Why needed**:`LlmStreamStarted` 事件需要走 SSE 通道送到前端;新 event type 让前端可选择性升级消费(老前端忽略即可)。

**AC mapping**:AC-050-2(SSE 映射 `{"type":"llm.stream.started","streamId":...,"maxOutputTokens":...}` 不含 step/maxSteps)

**预估**:8 行

---

### T-03 [P] `LinearTurnEngine.java` 字段声明(无业务逻辑)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`

**改动**(在 `compactorRouter` 字段附近声明):

```java
/** Story #050 — Bug-2 ask-loop guard:per-toolUseId Deny-once 守卫 set。
 *  收到 Decision.Deny 后 add(toolCall.id());后续同 turn 同 toolCall.id()
 *  在 dispatchWithPolicy AskUser branch 守卫命中,直接返 LINGS-P02 short-circuit,
 *  不进 ApprovalRegistry.register / 不发 ApprovalRequired 事件。
 *  turn-start 重置(镜像 Story #008 maxSteps 守卫 reset 模式 + Story #048 sentinel 路径)。
 *  不设为全局 static:多 turn 并发时 per-Agent-instance 字段各自独立。*/
private Set<String> attemptedDeny;
```

**约束**:
- `import java.util.HashSet; import java.util.Set;`(JDK 8 兼容,no `Set.of()`)
- 字段**不**加 `@Value` final 注入(`attemptedDeny` 需要 turn-start 重置为 `new HashSet<>()`;**不能** final)
- 字段**不**加 `@Component`(LinearTurnEngine 是 per-Agent prototype-like,不是 Spring Bean)

**字段初始化**(在 `runTurn` 入口):

```java
/** Story #050 — turn-start 重置镜像 Story #008 maxSteps 守卫模式。*/
this.attemptedDeny = new HashSet<>();
```

**Why needed**:Bug-2 根因修复字段声明;Phase 2 T-07 加守卫逻辑。

**AC mapping**:AC-050-5(字段类型 + turn-start 重置 + 每 deny 加 entry)

**预估**:12 行(含 Javadoc + import 2 行)

---

### T-04 [P] `AgentEventLlmStreamStartedTest.java` 新建(Bug-1 L1 unit)

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/event/AgentEventLlmStreamStartedTest.java`

**2 case L1**:
1. `llmStreamStarted_isImmutableFinalClass` —— 反射 `getClass().getModifiers() & Modifier.FINAL != 0` + 2 个 final 字段(`streamId`, `maxOutputTokens`)
2. `llmStreamStarted_accessorsReturnConstructorArgs` —— `new LlmStreamStarted("msg-abc", 4096)` 后 `getStreamId() == "msg-abc"` + `getMaxOutputTokens() == 4096`

**AC mapping**:AC-050-1

**预估**:42 行

---

## Phase 2 — Core(严格串行,2 文件改动)

> **不可并行原因**:T-06(`AnthropicStreamParser` emit 变更)直接依赖 T-01(`AgentEvent.LlmStreamStarted` 嵌套类存在);T-07(`LinearTurnEngine` 守卫)直接依赖 T-03(`attemptedDeny` 字段声明)。**先 T-05 然后 T-06 然后 T-07** 不可颠倒。

### T-05 占位(已并入 T-06 / T-07)

> T-05 在 Story #046 模板中是 `LinearTurnEngine` ctor 改动;**本 Story 4 预算文件改动 + `LinearTurnEngine` 字段 + 守卫同一文件**,所以 T-05 不存在,T-06 / T-07 合并到同一文件 `LinearTurnEngine.java` 改动。

### T-06 `AnthropicStreamParser.java` 改 emit 事件类型(Bug-1)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamParser.java`

**改动点 1 处**(原 L208):

```java
// BEFORE (Story #027b 落地时占位 stub):
sink.onNext(new AgentEvent.ReasoningStarted(1, 50));

// AFTER (Story #050):
String messageId = parseMessageId(jsonNode);  // 从 message_start JSON 抽 message.id
int maxOutputTokens = parseMaxOutputTokens(jsonNode);  // 从 message_start JSON 抽 model.max_output_tokens
sink.onNext(new AgentEvent.LlmStreamStarted(messageId, maxOutputTokens));
```

**约束**:
- 既有 `parseResponse` 解析路径 0 改动(Story #027a + #027b 状态机不变)
- 既有 `text_delta` / `input_json_delta` / `content_block_stop` 解析逻辑 0 改动
- 只改 L208 一行的 emit target + 加 2 个 helper parse method(or inline expression)
- `ReasoningStarted` 类**不**删除(LinearTurnEngine 主循环 L271 仍发,语义不变)

**Why needed**:Bug-1 root cause fix — L208 硬编码 `step=1, maxSteps=50` 是 Story #027b 落地时"占位 stub"未替换;新事件 `LlmStreamStarted(streamId, maxOutputTokens)` 解耦 LLM message_start 与 ReAct step 计数。

**AC mapping**:AC-050-3(`AnthropicStreamParser.onMessageStart` 改 emit `LlmStreamStarted`)+ AC-050-12(反射验证不再 emit `ReasoningStarted(1, 50)`)

**预估**:18 行(2 helper methods + 1 行 emit 替换)

---

### T-07 `LinearTurnEngine.java` 加 Deny-once 守卫 + turn-start reset(Bug-2)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`

**改动点 3 处**:

1. **守卫检查**(在 `dispatchWithPolicy` AskUser branch 入口,registry.register 调用**之前**):

```java
/** Story #050 — Bug-2 ask-loop guard:per-toolUseId Deny-once 守卫。*/
if (attemptedDeny.contains(toolCall.id())) {
    ToolResult denied = ToolResult.error(
        "[LINGS-P02] Tool '" + toolCall.name() + "' already denied in this turn; " +
        "please EndTurn or try different tool");
    sink.onNext(denied.toEvent());  // 或调用既有 ToolResult → AgentEvent 转换路径
    return denied;
}
```

2. **Deny 路径 add to set**(在 `Decision.Deny` 处理段):

```java
/** Story #050 — 短路后续同 toolCall.id() 重发,避免无限 ApprovalRegistry 循环。*/
attemptedDeny.add(toolCall.id());
// 既有 deny 处理逻辑(send ToolResult + return)不变
```

3. **turn-start 重置**(在 `runTurn` 入口,既有 Story #008 maxSteps 守卫 reset 旁边):

```java
/** Story #050 — 镜像 Story #008 maxSteps 守卫 reset 模式。*/
this.attemptedDeny = new HashSet<>();
```

**约束**:
- approve 路径**不** add(`attemptedDeny` 是 deny-only 集合,语义清晰)
- 不同 `toolCall.id()`(同一 tool 不同 instance)**不**短路(per-toolUseId 守卫,不是 per-tool-name 守卫)
- runtime exception**不**触发 turn-start reset(必须等下个 turn 才重置,保持同一 turn 内 set 守不变)
- 既有 AskUser branch 4-段逻辑(注册 ApprovalRegistry → 阻塞等 future → future.complete → sink 收)0 改动
- `LinearTurnEngineProvider` 0 改动(Story #046 9-arg ctor + dual-ctor 模式镜像不变)

**Why needed**:Bug-2 root cause fix — Deny-once 守卫让 LLM 收到 deny feedback 后转向 EndTurn,而不是无限循环弹 modal。

**AC mapping**:AC-050-5 / AC-050-6 / AC-050-7 / AC-050-8 / AC-050-9 / AC-050-10 / AC-050-11

**预估**:24 行(守卫 9 行 + add 1 行 + reset 1 行 + 既有代码 keep + Javadoc 4 行 + import 已有)

---

## Phase 3 — Tests(并行,6 文件新建)

> **可并行原因**:6 个测试文件互不依赖,只 stub fixture 不同;每个 test 文件独立可 compile。

### T-08 [P] `AgentEventMapperLlmStreamStartedTest.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/agent/AgentEventMapperLlmStreamStartedTest.java`

**2 case L1**:
1. `mapsToJsonWithTypeAndFields` —— `mapper.toSse(new AgentEvent.LlmStreamStarted("msg-001", 4096))` 返 Map `{"type":"llm.stream.started","streamId":"msg-001","maxOutputTokens":4096}` **不**含 `step` / `maxSteps`
2. `oldFrontendMissingHandler_doesNotThrow` —— 验证 mapper 映射失败时返 `Optional.empty()` 或 default Map,不抛异常

**AC mapping**:AC-050-2

**预估**:58 行

---

### T-09 [P] `AnthropicStreamParserSseEventFixTest.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamParserSseEventFixTest.java`

**2 case L1**(反射型验证 Bug-1 修复):
1. `onMessageStart_emitsLlmStreamStarted` —— mock SSE 流(message_start event)→ 反射 `AnthropicStreamParser` 内部 emit 路径 → 断言 emit 出去的事件类型是 `AgentEvent.LlmStreamStarted`,**不**是 `AgentEvent.ReasoningStarted`
2. `onMessageStart_doesNotEmitReasoningStartedWithOneFifty` —— 断言 emit 出去的事件**不是** `new AgentEvent.ReasoningStarted(1, 50)`

**AC mapping**:AC-050-3

**预估**:76 行(含反射 + mock sink)

---

### T-10 [P] `AnthropicStreamParserSseEventFixIT.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamParserSseEventFixIT.java`

**2 case L2 IT**(镜像 Story #027b 既有 `AnthropicStreamProviderIT` 模式):
1. `sseStreamWithFiveMessageStarts_emitsFiveLlmStreamStarted` —— mock HTTP server 返回 SSE 流(20ms 增量 + 5 个 message_start)→ 解析端 emit 5 个 `LlmStreamStarted` 事件,**不** emit 任何 `ReasoningStarted(1, 50)`
2. `sseStreamRegressionTest_story027bEquivalence` —— 跑 Story #027b 既有 fixture(`AnthropicStreamProviderIT` 测试)行为映射后等价(0 regression)

**AC mapping**:AC-050-4 + AC-050-12

**预估**:98 行(含 mock HTTP server + SSE 流构造 + 反射 assertion)

---

### T-11 [P] `LinearTurnEngineAskLoopGuardTest.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskLoopGuardTest.java`

**5 case L1**(覆盖 AC-050-5 ~ AC-050-10):
1. `deny_addsToAttemptedDenySet` —— `attemptedDeny` 集合为空 + AskUser branch 收到 `Decision.Deny`(toolCall.id="d6d5aaa8")→ 守卫 + add → `attemptedDeny.contains("d6d5aaa8") == true` + ApprovalRegistry.size()=0(短路 1)
2. `reaskSameToolUseId_shortCircuits` —— `attemptedDeny` 含 "d6d5aaa8" + 同 turn 再 emit 同一 toolCall.id → 守卫命中 → 返 `ToolResult.error("[LINGS-P02] already denied...")` + ApprovalRegistry.size() 不变(短路 2)
3. `differentToolUseId_stillRegistersNormally` —— `attemptedDeny` 含 "d6d5aaa8" + emit 不同 toolCall.id("9abcdef0")→ 守卫**未**命中 → 走正常 ApprovalRegistry.register + ApprovalRequired 事件发射
4. `turnStart_resetsAttemptedDeny` —— 跑完一个 turn(StopReason=END_TURN)+ 启动第二个 turn → `attemptedDeny` 重置为 `new HashSet<>()`(空集合)
5. `approve_doesNotAddToAttemptedDeny` —— `Decision.Allow` 路径 → `attemptedDeny` 不变(只 deny 路径 add,语义清晰)

**AC mapping**:AC-050-5 / AC-050-6 / AC-050-7 / AC-050-8 / AC-050-9 / AC-050-10

**预估**:188 行(5 case × ~38 行/case)

---

### T-12 [P] `LinearTurnEngineAskUserLoopGuardIT.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserLoopGuardIT.java`

**1 case L3 IT**(端到端 e2e BlackBox):
1. `mockLlmReAskSameToolFourTimes_onlyOneApprovalRegistered` —— mock LLM 在 step 3/4/5/6 持续 emit 同 `write_file` toolUseId 4 次 deny → 跑 50 steps → 断言:
   - `ApprovalRegistry.size() == 1`(只有第一次进 register)
   - `attemptedDeny.size() == 1`
   - StopReason = MAX_STEPS
   - SSE 流只 emit 1 次 `ApprovalRequired`(只有第一次,后续 3 次短路不发)

**AC mapping**:AC-050-11

**预估**:142 行(含 mock LLM + ApprovalRegistry wiring + 50 steps 模拟)

---

### T-13 [P] 既有 fixture RAC sync 验证(无文件改动)

**任务**:跑 `mvn -pl lingshu-core test -Dtest=LinearTurnEngineAskUserTest,DefaultApprovalGateTest,AgentFactoryAskPolicyRoutingIT,LinearTurnEngineAskUserNoRenderIT,ApprovalRegistryTest,AskUserPermissionPolicyTest` → 24 case AskUser/ApprovalGate 相关 fixture 0 改动全过

**AC mapping**:AC-050-13(RAC-13e 子项)

**预估**:0 行改动 / 仅运行验证

---

## Phase 4 — Validation(严格串行,4 步骤)

> **不可并行原因**:每步是下一步的前置,缺一环 baseline 不准。

### T-14 RAC 跑 full test suite(0 regression 验证)

**命令**:
```bash
mvn -pl lingshu-core test
```

**期望**:**全 791+ case pass / 0 fail / 2 MCP heartbeat flake pre-existing**(累计 51 Story 合入后总数)
- 含 Story #049 11 case + Story #048 4 case + Story #047 8 case + Story #046 16 case + Story #045 12 case + Story #041 9 case + Story #037 9 case + Story #030 23 case = 92 case AskUser/ApprovalGate/Compactor 相关全部 0 改动全过
- 含 Story #031 26 case permission pattern 全部 0 改动全过
- 2 MCP heartbeat flake pre-existing 已在 CLAUDE.md 文档化,与本 Story 无关

**AC mapping**:AC-050-13

**预估**:~3-5 分钟运行时间

---

### T-15 R-13 mitigation (d) baseline 镜像(第 33 次 PASS)

**步骤**:
```bash
# Step 1: 拿 pre-baseline(实施 commit 后)
POST_MD5=$(git show HEAD:lingshu-core/pom.xml | md5sum)

# Step 2: 与 T-00.5 记录的 pre-baseline md5 比对
if [ "$POST_MD5" = "$T00_5_BASELINE_MD5" ]; then
    echo "✅ R-13 mitigation (d) PASS — 0 binary delta 第 33 次"
else
    echo "❌ R-13 mitigation (d) FAIL — binary drift detected"
    git show HEAD:lingshu-core/pom.xml | md5sum  # 排查新引入依赖
    exit 1
fi

# Step 3: banned-dependencies enforcer 验证
mvn -pl lingshu-core verify -Pbanned-dependencies
# 期望:Rule 0 passed

# Step 4: 累计 R-13 (d) PASS counter
echo "R-13 (d) 第 33 次 PASS — 累计 32 个 Story PASS"
```

**AC mapping**:AC-050-14(R-13 baseline 镜像 + banned-dependencies Rule 0)

**预估**:~2 分钟运行时间

---

### T-16 demo-product 启动 sanity check

**步骤**:
```bash
mvn -pl lingshu-examples/demo-product spring-boot:run &
SPRING_PID=$!
sleep 30  # 等待 Spring Boot 启动

# 验证启动不崩(2-bug fix 后必须仍能启动)
curl -s http://localhost:8080/ | grep -q '<html' && echo "✅ demo-product 启动 OK"

# 创建测试 session
SESSION_ID=$(curl -s -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-ask-loop-guard"}' | jq -r .sessionId)

# 关闭
kill $SPRING_PID
```

**期望**:启动成功 + `/` 返 HTML + `POST /api/sessions` 返 sessionId = 测试 session 标识

**AC mapping**:无(纯启动 sanity,不在 AC 表)

**预估**:~1 分钟运行时间

---

### T-17 人工端到端 5 步清单(demo-product browser)

**步骤**(开发者人工跑):

1. `mvn -pl lingshu-examples/demo-product spring-boot:run` 启动 demo-product
2. 浏览器开 `http://localhost:8080/`,确认 HTML 加载(老 frontend 含 `llm.stream.started` unknown handler 不报错)
3. 配 `agent.permission-policy: ask` + `tools.ask-list: [write_file]`(已落 yml,Story #047 wiring 验证)
4. 输入 "write a poem to `/var/folders/.../lingshu-sessions/_default/poem.txt`"(触发 ask-mode)
5. SSE 流观察:
   - **Bug-1 fix 验证**:SSE 流 `reasoning.start` 事件 step 字段单调递增(1/2/3/.../maxSteps),**无** 22 → 1 → 24 reset
   - **Bug-1 fix 验证**:SSE 流含 `llm.stream.started` 事件(`{"type":"llm.stream.started","streamId":"msg-001","maxOutputTokens":4096}`),**无** step/maxSteps 字段
   - **Bug-2 fix 验证**:第 1 次 write_file approval modal 显示 → user deny → 第 2 次**同 toolUseId** LLM 重发时**不**弹新 modal,直接收 `[LINGS-P02] already denied...` ToolResult → LLM 转向 EndTurn → 最终回复 user "你 deny 了,我该怎么办"

**AC mapping**:无(纯人工 e2e)

**预估**:~5 分钟人工测试

---

## Phase 5 — Docs 同步(并行,5 文件改动)

> **可并行原因**:5 个文件改动互不依赖。
>
> - T-18:CLAUDE.md 版本号 + v1.3.58 → v1.3.59 段
> - T-19:dsh_agent_design.md v1.5.61 → v1.5.62 + §6.1 + §13 + §6.5(2) + §5.6.4 + §4.6
> - T-20:README.md + specs/ROADMAP.md + constitution.md 三件套

### T-18 [P] CLAUDE.md v1.3.58 → v1.3.59

**文件**:`~/Documents/AIFullStack/MyDSHAgentDesign/CLAUDE.md` + `remote_repos/lingshu/CLAUDE.md`(2 处同步)

**改动**:
1. CLAUDE.md 顶部「对应设计文档」字段 `v1.5.61` → `v1.5.62`
2. CLAUDE.md 底部「**Version**: 1.3.58(...)」段新增 v1.3.59 整段:

```markdown
**Version**: 1.3.59(v1.3.59 Story #050 ask-loop-guard-end-of-turn 实施 + 文档同步 — dsh v1.5.61 → v1.5.62(§0 标题 v1.5.61 → v1.5.62 + §13 changelog 新增 v1.5.62 行 + §6.1 LinearTurnEngine 段补 attemptedDeny field + turn-start reset + dispatchWithPolicy AskUser branch Deny-once 守卫 🆕 v1.5.62 Story #050 标记 + §6.5 (2) SSE event stream 段补 LlmStreamStarted 新事件类型映射 🆕 v1.5.62 Story #050 标记 + §4.6 AgentEvent hierarchy 补 LlmStreamStarted nested class 🆕 v1.5.62 Story #050 标记 + §5.6.4 SPI 总表 0 改动)+ **2-bug 合修:ask-mode 业务价值收口** — Bug-1(`AnthropicStreamParser.java:208` 硬编码 emit `ReasoningStarted(1, 50)` 占位 stub from Story #027b 落地时,致 SSE 流 step 计数 22 → 1 → 24 reset 乱跳)→ 修复:加 `AgentEvent.LlmStreamStarted(streamId, maxOutputTokens)` nested class 表达「LLM message_start」语义,与 ReAct 主循环 step 解耦;`AnthropicStreamParser` L208 改 emit `LlmStreamStarted`;`AgentEventMapper` 加 1 行 mapper case 映射 SSE `{"type":"llm.stream.started",...}` 不含 step/maxSteps;`ReasoningStarted` 类**不**删除(LinearTurnEngine 主循环 L271 仍发射,语义不变)/ Bug-2(`LinearTurnEngine.dispatchWithPolicy` AskUser branch 缺 per-toolUseId Deny-once 守卫,致 LLM 收到 deny feedback 后无限重发同一 tool → ApprovalRegistry 无限循环弹 modal)→ 修复:`LinearTurnEngine` 加 `attemptedDeny: Set<String>` private field + `dispatchWithPolicy` AskUser branch 入口守卫(contains 命中 → 返 `ToolResult.error("[LINGS-P02] already denied in this turn...")` short-circuit,**不**进 ApprovalRegistry)+ Deny 路径 add(toolCall.id())到 set + `runTurn` 入口 turn-start 重置为 `new HashSet<>()`(镜像 Story #008 maxSteps 守卫 reset 模式);approve 路径**不** add(语义清晰,happy path 0 变化);per-toolUseId 守卫(不是 per-tool-name 守卫,也不是 per-turn 全 deny)/ **14 net new case** 跨 6 文件(`AgentEventLlmStreamStartedTest` 2 L1 + `AgentEventMapperLlmStreamStartedTest` 2 L1 + `AnthropicStreamParserSseEventFixTest` 2 L1 + `AnthropicStreamParserSseEventFixIT` 2 L2 + `LinearTurnEngineAskLoopGuardTest` 5 L1 + `LinearTurnEngineAskUserLoopGuardIT` 1 L3)/ **0 SPI / 公开方法 / 接口契约改动**(`LlmProvider` / `PermissionPolicy` / `ApprovalGate` / `ApprovalRegistry` / `AskUserPermissionPolicy` / `AgentEvent` 基类 / `LinearTurnEngineProvider` / `AgentFactory` 9-Router ctor / `AgentConfig` 26 字段 / `Tool` / `ToolExecutor.dispatch()` 5 步流水线 / `Message` 4 子类 / 9 Slot 体系 100% 守住)/ **0 新 ErrorCode**(复用 Story #030 `LINGS-P02` PERMISSION_APPROVAL_TIMEOUT 错误码前缀,本 Story short-circuit 路径也用此 code 前缀表达「tool 已被 deny 一次,不再请求」)/ **0 新 Maven 依赖**(`HashSet` + `Collections.emptySet()` + `Collections.unmodifiableMap()` JDK 8 内置 + Lombok `@Value` 已锁 13 项依赖表内)/ R-13 mitigation (d) baseline 镜像 pre/post `git show HEAD:lingshu-core/pom.xml | md5sum` diff **仅时间戳差异** = **0 binary delta 第 33 次 PASS**(累计 32 个 Story PASS)/ `banned-dependencies` enforcer Rule 0 passed/ 全 791+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing(与本 Story 无关)/ **0 后端 Java 改动**到 `ApprovalRegistry` / `ApprovalGate` SPI(Story #041 已落 + Bug-3 race condition deferred to OQ-Future / Story #051,**本 Story 4 预算文件改动严守 ≤5 边界**)/ **累计 52 个 Story 合入**/ **业务价值** — demo-product ask-mode SSE 流 step 计数器语义清晰(LLM 内部流 message_start vs ReAct 主循环 step start 解耦,Bug-1 修复)+ ask-mode 业务价值落地(user deny 后 Agent 礼貌收手转向 EndTurn 把 deny reason 转告 user,Bug-2 修复)+ Story #029-#048 7 件套 ask-mode 完整链路收口(Story #029 strict → #030 ask → #031 pattern → #037 3 Provider → #041 SPI 抽离 → #042 frontend → #047 yml binding → #048 hang-safety → **#050 ask-loop guard** = 9 件套完整闭环)+ R-04 privilege escalation 缓解完整链路收口(分值 8 → 9 件套)/ README.md 「更新日期」段加 🆕 v1.5.62 Story #050 顶部 blockquote + 「核心特性」段补 🛡️ ask-loop-guard + step-reset 收口 bullet + 「Story 路线图」段追加 #050 retrospective(2-bug 合修 + `LlmStreamStarted` event + `attemptedDeny` 守卫 + 14 case AC 黑盒验证 / 0 SPI 改动 / 0 新 ErrorCode / R-13 0 binary delta 第 33 次 PASS / 累计 52 个 Story)/ specs/ROADMAP.md 段一 ✅ 已完成加 #050 行(2026-10-16,791+ pass / 0 fail / 2 MCP heartbeat flake pre-existing / R-13 0 binary delta 第 33 次 PASS / 0 新 ErrorCode)+ 段二 🟡 待补 #050 划掉 + 段五 🎯 实施节奏 累计计数 51 → **52 个 Story**/ constitution §10 R-13 缓解 Story 列表补 #050 行**第 33 次** R-13 mitigation (d) PASS 0 binary delta + 累计计数 32 → **33 个 Story**/ dsh §13 新增 v1.5.62 行(本条)/ 「对应设计文档」版本号 v1.5.61 → v1.5.62)
```

**Why needed**:CLAUDE.md 是项目顶入口;每 Story 合入必须版本号同步。

**AC mapping**:无(纯文档同步)

**预估**:CLAUDE.md 1 行版本号 + 1 段 v1.3.59 长描述(增量约 1800 字符)

---

### T-19 [P] `dsh_agent_design.md` v1.5.61 → v1.5.62

**文件**:`~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md`

**改动 5 处**:

1. **§0 L1 标题版本号** `v1.5.61` → `v1.5.62`
2. **§13 changelog 新增 v1.5.62 行**(本 Story 完成 5 节内容:① 2-bug 合修 ② `LlmStreamStarted` 新事件 ③ `attemptedDeny` 守卫 ④ 14 case AC 黑盒验证 ⑤ 0 SPI 改动 + R-13 0 binary delta 第 33 次 PASS + 累计 52 个 Story)
3. **§6.1 LinearTurnEngine 段**(L3611-3686 区域)补 `attemptedDeny: Set<String>` field 声明 + `runTurn` 入口 turn-start reset 句柄 + `dispatchWithPolicy` AskUser branch Deny-once 守卫(`if (attemptedDeny.contains(toolCall.id()))` short-circuit → `ToolResult.error("[LINGS-P02] already denied in this turn...")`)🆕 v1.5.62 Story #050 标记
4. **§6.5 (2) SSE event stream 段**补 `LlmStreamStarted` 新事件类型映射表(SSE `{"type":"llm.stream.started","streamId","maxOutputTokens"}` 不含 step/maxSteps 字段)🆕 v1.5.62 Story #050 标记
5. **§4.6 `AgentEvent` hierarchy** 补 `LlmStreamStarted(String streamId, int maxOutputTokens)` nested class(Lombok `@Value` immutable)+ 与 `ReasoningStarted` 语义解耦说明 + 4 字段差异表(触发时机 / 字段含义 / SSE 字段 / 消费方)🆕 v1.5.62 Story #050 标记

**§5.6.4 SPI 总表 0 改动**(9 Slot 公开方法签名不变)

**AC mapping**:无(纯 dsh 文档同步)

**预估**:~14 行(增量,纯文档)

---

### T-20 [P] README.md + specs/ROADMAP.md + constitution.md 三件套

**文件 3 个**:
- `remote_repos/lingshu/README.md`
- `remote_repos/lingshu/specs/ROADMAP.md`
- `remote_repos/lingshu/constitution.md`

**改动**:

1. **README.md**:
   - 「更新日期」段加 🆕 v1.5.62 Story #050 顶部 blockquote
   - 「核心特性」段补 🛡️ ask-loop-guard + step-reset 收口 bullet
   - 「Story 路线图」段追加 #050 retrospective(2-bug 合修 + `LlmStreamStarted` event + `attemptedDeny` 守卫 + 14 case AC 黑盒验证 / 0 SPI 改动 / 0 新 ErrorCode / R-13 0 binary delta 第 33 次 PASS / 累计 52 个 Story)

2. **specs/ROADMAP.md**:
   - 段一 ✅ 已完成加 #050 行(2026-10-16,791+ pass / 0 fail / 2 MCP heartbeat flake pre-existing / R-13 0 binary delta 第 33 次 PASS / 0 新 ErrorCode)
   - 段二 🟡 待补 #050 划掉
   - 段五 🎯 实施节奏 累计计数 51 → **52 个 Story**

3. **constitution.md**:
   - §10 R-13 缓解 Story 列表补 #050 行**第 33 次** R-13 mitigation (d) PASS 0 binary delta
   - 累计计数 32 → **33 个 Story**

**AC mapping**:无(纯文档同步)

**预估**:~30 行(3 文件合计)

---

## AC ↔ Task mapping 总表

| AC# | 描述 | 实现任务 |
|---|---|---|
| **AC-050-1** | `AgentEvent.LlmStreamStarted(streamId, maxOutputTokens)` 不可变事件类 + 2 个 accessor + `final class` | T-01 / T-04 |
| **AC-050-2** | `AgentEventMapper` 把 `LlmStreamStarted` 映射为 SSE `{"type":"llm.stream.started",...}` 不含 step/maxSteps | T-02 / T-08 |
| **AC-050-3** | `AnthropicStreamParser.onMessageStart` 改 emit `LlmStreamStarted` 不再 emit `ReasoningStarted(1, 50)` 硬编码占位 | T-06 / T-09 |
| **AC-050-4** | mock SSE 流(20ms 增量 + 5 个 message_start)→ 老 `AnthropicStreamProviderIT` 行为映射后等价 | T-10 |
| **AC-050-5** | `LinearTurnEngine.attemptedDeny` 字段类型 `Set<String>` + turn-start 重置 + 每 deny 加 entry | T-03 / T-11 |
| **AC-050-6** | `dispatchWithPolicy` AskUser branch 收到 `Decision.Deny` 后 add toolCall.id() 到 attemptedDeny + ApprovalRegistry 不 register | T-07 / T-11 |
| **AC-050-7** | `dispatchWithPolicy` AskUser branch 守卫命中 → 立即返 LINGS-P02 short-circuit + 不进 ApprovalRegistry | T-07 / T-11 |
| **AC-050-8** | per-toolUseId 守卫 — 不同 toolUseId 走正常 ApprovalRegistry.register | T-07 / T-11 |
| **AC-050-9** | turn-start 重置(`runTurn` 入口 `attemptedDeny = new HashSet<>()`)— 镜像 Story #008 maxSteps reset | T-07 / T-11 |
| **AC-050-10** | approve 路径不入 set,只 deny 路径入 set(语义清晰) | T-07 / T-11 |
| **AC-050-11** | mock LLM 持续 4 次同 write_file toolUseId 4 次 deny → ApprovalRegistry.size()=1 + StopReason=MAX_STEPS | T-12 |
| **AC-050-12** | Bug-1 SSE 修复反射型验证:不再依赖 LLM-internal ReasoningStarted(1, 50) 触发 | T-09 / T-10 |
| **AC-050-13** | Story #030-#048 = 71 case AskUser/ApprovalGate 相关 0 改动全过 + mvn -pl lingshu-core test full suite 0 fail | T-13 / T-14 |
| **AC-050-14** | mvn -pl lingshu-core dependency:tree pre/post md5sum diff 仅时间戳差异 = 0 binary delta 第 33 次 PASS | T-15 |

**总 AC 计数**:14 case(12 net new case + 1 RAC + 1 DepTree)

---

## Story Budget Guards(越界检查)

| 项 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心 prod 文件改动 | ≤ 5 | 4(`AnthropicStreamParser` + `AgentEvent` + `AgentEventMapper` + `LinearTurnEngine`) | ✅ |
| 测试文件新建 | 无硬上限 | 6(`AgentEventLlmStreamStartedTest` + `AgentEventMapperLlmStreamStartedTest` + `AnthropicStreamParserSseEventFixTest` + `AnthropicStreamParserSseEventFixIT` + `LinearTurnEngineAskLoopGuardTest` + `LinearTurnEngineAskUserLoopGuardIT`) | ✅ |
| 新 ErrorCode 引入 | ≤ 3 | 0(复用 Story #030 `LINGS-P02`) | ✅ |
| 新 Maven 依赖 | 0 | 0(`HashSet` + `Collections.emptySet()` + `Collections.unmodifiableMap()` JDK 8 内置 + Lombok `@Value` 已锁 13 项依赖表内) | ✅ |
| 改 SPI 公开方法签名 | 0 | 0(`LlmProvider` / `PermissionPolicy` / `ApprovalGate` / `ApprovalRegistry` / `AskUserPermissionPolicy` / `AgentEvent` 基类 / `LinearTurnEngineProvider` / `AgentFactory` 9-Router ctor / `AgentConfig` 26 字段 / `Tool` / `ToolExecutor.dispatch()` 5 步流水线 / `Message` 4 子类 / 9 Slot 体系 100% 守住) | ✅ |
| 改 AgentConfig schema | 0 字段新增 | 0(`permissionPolicy` + `tools.ask-list` 已落 Story #047) | ✅ |
| JDK 8 兼容 | 守住 | ✅(纯 `HashSet` + `Collections.emptySet()` + Lombok `@Value` + Spring `@Component` + Jackson `@JsonProperty` kebab-case 已锁,no `var` / `List.of` / sealed / records) | ✅ |

---

## R-13 mitigation (d) baseline 镜像清单(第 33 次 PASS)

| 步骤 | 命令 | 期望输出 |
|---|---|---|
| **1** | `git show HEAD:lingshu-core/pom.xml \| md5sum` (T-00.5 记录) | md5 值 `T00_5_BASELINE_MD5` |
| **2** | T-06 / T-07 实施 + commit 后,`git show HEAD:lingshu-core/pom.xml \| md5sum` | md5 值 = `T00_5_BASELINE_MD5`(纯时间戳差异不算) |
| **3** | `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/post-tree.txt 2>&1` | 与 pre `/tmp/pre-tree.txt` diff 仅时间戳差异 |
| **4** | `mvn -pl lingshu-core verify -Pbanned-dependencies` | `Rule 0: passed` |
| **5** | `mvn -pl lingshu-core test -DfailIfNoTests=false` | BUILD SUCCESS / **0 fail** / 2 MCP heartbeat flake pre-existing |
| **6** | 累计计数写入 | R-13 (d) **第 33 次 PASS** — 累计 32 个 Story PASS(Story #001-#049 = 51 个 + Story #050 = 第 52 个)|

**为什么是第 33 次**:Story #030 起 R-13 mitigation (d) 开始 PASS 计数(`mvn -pl lingshu-core dependency:tree` pre/post md5sum 一致 = 0 binary delta);Story #030 = 16th PASS(CLAUDE.md 文档化)/ Story #037 = 23rd / Story #041 = 24th / Story #042 = 25th / Story #045 = 29th / Story #046 = 30th / Story #047 + #048 = 31st(双 Story 共用 baseline)/ Story #049 = 32nd / **Story #050 = 33rd PASS**。

---

## 风险登记(实施期关注)

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| **`AnthropicStreamParser` 改 emit 触发现有 Story #027b IT regression** | 中 | 老 fixture 期待 emit `ReasoningStarted(1, 50)` → 改后 emit `LlmStreamStarted` | 跑 `AnthropicStreamProviderIT` 既有的 2 case(RAC-13e 子项)+ 新增 AC-050-12 反射验证 fixture 双重守护 |
| **`attemptedDeny` 没 reset 跨 turn 误短路** | 中 | 上 turn deny 过的 tool,这 turn 再调用被误短路 | `runTurn` 入口明确 `this.attemptedDeny = new HashSet<>();` + AC-050-9 锁 + L1 unit test 覆盖 |
| **Bug-3 race condition 触发** | 低 | `ApprovalRegistry.pending.clear()` 在并发 session 切换时可能误清 active session | OQ-Future / Story #051 修;**本 Story 不修**,单 Agent 串行场景不受影响 |
| **Bug-1 SSE 老 frontend 不识别 `llm.stream.started`** | 中 | 用户升级 demo-product 后老 frontend 报 JS console 错 | 新 event type 与老 event type 完全独立;老 frontend `addEventListener("llm.stream.started", ...)` 不存在则**不** trigger error |
| **approve 路径误 add** | 低 | 用户 approve 后 LLM 重发同 toolCall 被误短路 | AC-050-10 锁(approve 路径**不** add,只 deny 路径 add) |
| **`LlmStreamStarted` mapper 失败 fallback 抛错** | 低 | EventSource onmessage 中未处理 type 进 default branch 但**不**抛(JS spec) | mapper 失败返 `Optional.empty()` 或 default Map,不抛异常 |

---

**Last updated**: 2026-10-16
**Story #050 spec/plan/tasks 三件套 ready** —— 等用户「实施吧」触发 Phase 1-5 实际代码改动。
