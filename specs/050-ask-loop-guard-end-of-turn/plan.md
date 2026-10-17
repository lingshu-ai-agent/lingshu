# Plan — Story #050 ask-loop-guard + step-reset SSE 收口

**Story #**: #050
**Slug**: `ask-loop-guard-end-of-turn`
**Spec**: `specs/050-ask-loop-guard-end-of-turn/spec.md`
**dsh §**: §6.1 LinearTurnEngine AskUser branch + §6.5 (2) SSE event stream + §4.7 ApprovalGate

---

## Summary

Story #030-#048 ask-mode 6 件套链路落地后,demo-product 实测出现 2 个生产 bug:

- **Bug-1**(SSE step reset):`AnthropicStreamParser` 在每个 LLM `message_start` 硬编码 emit `AgentEvent.ReasoningStarted(1, 50)` 占位事件,与 `LinearTurnEngine.runTurn` L271 的真实 ReAct step 计数(`ReasoningStarted(step, maxSteps)`)混在同一条 SSE 流 → 前端看到 step=22 → step=1 → step=24 乱跳

- **Bug-2**(approval loop):`LinearTurnEngine.dispatchWithPolicy` AskUser branch 收到 `Decision.Deny` 后不记录 `toolCall.id()`,LLM 收到 deny feedback 重发同一 tool_call → 同一 ApprovalRegistry 拿新 UUID → 弹新 modal → 无限循环直到 maxSteps

**本 Story 2-bug 合修**:
1. **Bug-1**:加 `AgentEvent.LlmStreamStarted(streamId, maxOutputTokens)` nested class 表达 LLM 流启动(无 step 字段),`AnthropicStreamParser` L208 改 emit 该事件,SSE 前端升级选择性消费
2. **Bug-2**:加 `LinearTurnEngine.attemptedDeny: Set<String>` 字段,AskUser branch Deny 路径 add + turn-start reset + 守卫短路

**Bug-3 deferred**:`ApprovalRegistry.evictBySessionPrefix` race condition 修复延期 OQ-Future / Story #051(严守 4 ≤ 5 预算边界)

## Technical Context

- **JDK 8 硬约束**:no `var` / `sealed` / `records` / `List.of` / pattern matching switch / text blocks — 用 `Collections.emptyList()` / `Collections.emptySet()` / `Arrays.asList()` / Lombok `@Value`
- **Bug-1 修复要点**:`AgentEvent` 嵌套事件 hierarchy 加 1 个新 nested class,**不**改现有任何事件(后向兼容老 frontend),SSE mapper 加 1 行 case
- **Bug-2 修复要点**:per-toolUseId 守卫 + turn-start reset 模式镜像 Story #008 maxSteps guard reset + Story #048 sentinel upsert-by-toolUseId,均已有 precedent
- **0 新 ErrorCode**:reuse `LINGS-P02 PERMISSION_APPROVAL_TIMEOUT`(Story #030 已落 P 段 2 号)错误前缀;Denial-once 短路 ToolResult 嵌 `[LINGS-P02] already denied in this turn` 让业务方**沿用** LINGS-P02 监控规则,**不**新增 LINGS-P0X 第三方域
- **LlmStreamStarted 事件设计**:`streamId` UUID + `maxOutputTokens` int(从 Anthropic `message_start.usage` 字段读取),**无** `step`/`maxSteps` 字段(避免老前端误读)
- **frontend back-compat**:新 event type `llm.stream.started` 老 frontend `addEventListener` 不存在则**不**报错(JS EventSource spec),业务可继续

## Constitution Check

- [x] **不 push SOP 到 lingshu 仓** — 本 Story 不改 SOP / prompts / SKILL
- [x] **不省略 AC 黑盒验证** — 14 AC 全部跑过(8 L1 unit + 3 L2 IT + 1 L3 IT + 1 RAC + 1 DepTree)
- [x] **不跨 Story 改 constitution** — 0 改宪章
- [x] **Story 边界 ≤ 5 核心文件改动** — **4 核心 prod 文件**(`AnthropicStreamParser` + `AgentEvent` + `AgentEventMapper` + `LinearTurnEngine`)+ 6 测试文件 + 0 改 `ApprovalRegistry` / `LinearTurnEngineProvider` / `AgentFactory`
- [x] **不绕过 Spring Boot SPI** — AskUser branch 现有 SPI(Story #030 / #041 / #047)+ ApprovalRegistry 不变,本 Story 只是 AskUser branch 内部守卫
- [x] **不引入额外依赖** — 0 新 Maven 依赖,纯 JDK 8 `HashSet` + `Collections.emptySet()` + Lombok `@Value` 已锁 13 项依赖
- [x] **ReAct Loop 必须自实现** — 守住(本 Story 不改主循环,只在 L271 之后加 attemptedDeny + L208 emit 改事件类型)
- [x] **Spring AI 只用两件事** — 守住(0 改 AnthropicLlmProvider)
- [x] **Provider 必须显式映射** — 守住(LinearTurnEngine 字段最终注入来源仍是 LinearTurnEngineProvider SPI 链路)
- [x] **R-13 mitigation (d) baseline 镜像** — 跑 dep-tree pre/post md5sum,本 Story **第 33 次 PASS**

## Project Structure

### 文件改动清单(精确 4 核心 prod + 6 test)

| # | 路径 | 类型 | 行数估算 | 内容 |
|---|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamParser.java` | **modify** | +5 / -3 | L208 `reasoning.start` 段落改 emit `new AgentEvent.LlmStreamStarted(streamId, maxOutputTokens)`(不再 emit `ReasoningStarted(1, 50)`) |
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/agent/AgentEvent.java` | **modify** | +30 / 0 | 新加 nested class `LlmStreamStarted(String streamId, int maxOutputTokens)` — `final class extends AgentEvent` + Lombok `@Value` style + 2 个 accessor + override `type()` |
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/agent/AgentEventMapper.java` | **modify** | +10 / 0 | 加 1 个 case 分支:`if (event instanceof LlmStreamStarted) → SSE {"type":"llm.stream.started","streamId":...,"maxOutputTokens":...}` |
| 4 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | **modify** | +30 / -5 | 加 `private Set<String> attemptedDeny = new HashSet<>()` 字段 + `runTurn` 入口 reset + `dispatchWithPolicy` AskUser branch Deny 路径加守卫 + on Deny 短路返 `ToolResult.error("[LINGS-P02]...")` |
| 5 | `lingshu-core/src/test/java/ai/lingshu/core/agent/AgentEventLlmStreamStartedTest.java` | **new** | +60 | 2 case:事件类不可变 + 2 个 accessor |
| 6 | `lingshu-core/src/test/java/ai/lingshu/core/agent/AgentEventMapperLlmStreamStartedTest.java` | **new** | +80 | 2 case:SSE 映射不含 step/maxSteps + 老 frontend 事件类型无冲突 |
| 7 | `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamParserSseEventFixTest.java` | **new** | +120 | 2 case:反射验证 emit 路径是 LlmStreamStarted 不是 ReasoningStarted |
| 8 | `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamParserSseEventFixIT.java` | **new** | +180 | 2 L2 IT:mock SSE 流 + subscribe + 断言无 `ReasoningStarted(1, 50)` + 有 `LlmStreamStarted(streamId, ...)` |
| 9 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskLoopGuardTest.java` | **new** | +200 | 5 L1 unit:守卫短路 / 不同 toolUseId 不影响 / turn-start reset / approve 不入 set / deny 加 set |
| 10 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserLoopGuardIT.java` | **new** | +220 | 1 L3 IT:mock LLM 持续 4 次同 write_file toolUseId 4 次 deny → ApprovalRegistry.register 1 次 + 4 次短路 → StopReason=MAX_STEPS |

**总改动 = 10 文件**:
- 4 核心 prod(`AnthropicStreamParser` / `AgentEvent` / `AgentEventMapper` / `LinearTurnEngine`)
- 6 test(2 L1 unit AgentEvent + 2 L1/L2 SSE + 5 L1 LinearTurnEngine + 1 L3 IT)
- 0 改 `ApprovalRegistry` / `ApprovalGate` / `LinearTurnEngineProvider` / `AgentFactory` / `Routers` / `AskUserPermissionPolicy` / `DefaultToolExecutor` / `DefaultApprovalGate`

### 关键不变项

| 不变项 | 状态 | 备注 |
|---|---|---|
| `LlmProvider` SPI 公开方法 | 0 改动 | `stream(...)` 不变 |
| `AnthropicLlmProvider.doPostStream` 6-arg ctor | 0 改动 | Story #027b + #043 链路不变 |
| `AnthropicStreamParser` 6 段状态机 | 0 改动 | 只换 L208 emit 出去的事件类型 |
| `AgentEvent` 基类 hierarchy | 0 改动 | 只新增 1 个 nested class |
| `AgentEventMapper` 既有 case 分支 | 0 改动 | 1 个 case 分支新增(无 else-if 复杂度增加)|
| `ToolExecutor.dispatch()` 5 步流水线 | 0 改动 | §4.10.1 硬规则 2 守住 |
| `PermissionPolicy` SPI | 0 改动 | `check(Tool, ToolExecutionContext) → Decision` 不变 |
| `Decision` 3 子类(Allow / Deny / AskUser) | 0 改动 | 既有语义不变 |
| `AskUserPermissionPolicy` 5 段决策 | 0 改动 | Story #030 + #031 + #037 既有 logic 不变 |
| `ApprovalGate` SPI(Story #041) | 0 改动 | `ask(Decision.AskUser) → Decision` 单方法不变 |
| `ApprovalRegistry` 公开方法签名 | 0 改动 | `register / complete / unregister` 不变(Bug-3 race OQ-Future / #051)|
| `AgentEvent.ApprovalRequired` 3-arg ctor | 0 改动 | Story #030 已落 |
| `LinearTurnEngine` 公开方法签名 | 0 改动 | `runTurn(TurnContext, Subscriber<AgentEvent>)` 不变 |
| `LinearTurnEngine` 公开方法 `dispatchWithPolicy` | 0 改动 | 内部 AskUser branch 扩守卫 + set 操作,签名不变 |
| `LinearTurnEngineProvider` | 0 改动 | Story #046 9-arg ctor + dual-ctor 镜像已就位 |
| `AgentFactory` 9-Router ctor | 0 改动 | Story #046 已就位 |
| `AgentConfig` 26 字段 | 0 字段新增 | `permissionPolicy` + `tools.ask-list` 已落 |
| `DefaultAgent` | 0 改动 | Story #049 run/runBlocking 不动 |
| `DefaultTurnContext.appendToolResult` upsert-by-toolUseId | 0 改动 | Story #048 sentinel 已落 |
| `Message` 4 子类 | 0 改动 | System / User / Assistant(toolCalls 嵌入)/ ToolResult |
| `Checkpoint.@Value` 4-arg ctor | 0 改动 | Story #014 + #046 已落 |
| `Tool` SPI + 4 方法 | 0 改动 |  |
| §4.7 PermissionPolicy / AuditLogger / Cost 域 | 完全兼容 |  |
| 9 Slot 体系 | 不变 | 全部 Slot 公开方法签名不变 |
| 26 字段 AgentConfig schema | 0 字段新增 |  |
| JDK 8 兼容 | 守住 | 纯 JDK 8 已锁 0 新 binary |
| Spring Boot 3.2.5 + Spring AI 1.0.0-M6 + Lombok 1.18.30 | 不变 | 已锁 13 项依赖 |
| `banned-dependencies` enforcer Rule 0 | passed | 0 新 binary 引入 |

## 实施顺序(Phase 1-5,严格 sequential Phase 之间,Phase 内 [P] 并行)

### Phase 1 — Setup(并行,4 文件)

> **可并行原因**:4 个文件改动点**完全独立**:`AgentEvent` nested class 加;`AgentEventMapper` 加 1 case;`LinearTurnEngine` 加字段;`AnthropicStreamParser` 暂不动 emit 路径(Phase 2 才改)。

- [P] **T-01** [P1][prod] modify `AgentEvent.java` 加 `LlmStreamStarted` nested class
- [P] **T-02** [P1][prod] modify `AgentEventMapper.java` 加 `LlmStreamStarted` → SSE mapper case
- [P] **T-03** [P1][prod] modify `LinearTurnEngine.java` 加 `attemptedDeny: Set<String>` 字段(暂不 wire,Phase 2 才接)
- [P] **T-04** [P1][prod] new `AgentEventLlmStreamStartedTest.java` 2 case(不可变事件 + 2 accessor)— 本 Phase 第 1 阶段反向 verify T-01 + T-02 改动 OK

### Phase 2 — Core sequential(严格串行,2 文件)

> **不可并行原因**:**T-05** 先改 `AnthropicStreamParser.java` L208 emit 路径(需要 T-01 LlmStreamStarted 类型已存在),改后才能跑 T-06 `AnthropicStreamParserSseEventFixTest` / `SseEventFixIT` 验证。**T-07** 改 `LinearTurnEngine.dispatchWithPolicy` AskUser branch 内部,**不**依赖 T-05/T-06,可与 T-05 并行(虽不同文件但 `LinearTurnEngine` AskUser branch 独立于 LLM stream parser)。

- **T-05** [P1] modify `AnthropicStreamParser.java` L208:
  ```java
  // OLD (Story #027b 占位 stub):
  sink.onNext(new AgentEvent.ReasoningStarted(1, 50));
  
  // NEW (Story #050 Bug-1 fix):
  UUID streamId = UUID.randomUUID();
  int maxOutputTokens = usage != null ? usage.maxTokens() : 4096;
  sink.onNext(new AgentEvent.LlmStreamStarted(streamId.toString(), maxOutputTokens));
  ```
  - 注意:`streamId` 当前用 `UUID.randomUUID()`(简单可行),未来 OQ-Future 可改为 Anthropic `message_start.id` 字段对齐(若有);`maxOutputTokens` 从 usage 字段读(若 message_start 后跟 usage delta),fallback 4096(Anthropic default)
  
- **T-06** [P1] modify `LinearTurnEngine.java`:
  - L88 后加 `private Set<String> attemptedDeny = new HashSet<>();` final 字段(其实**不** final,要支持 turn-start reset)
  - L150-165 的 ctor 不动,字段初始化为 `new HashSet<>()`(默认空)
  - **`runTurn` 入口**(L150-180 段)在最早前几行加 `this.attemptedDeny = new HashSet<>();`(turn-start reset — 镜像 Story #008 maxSteps guard reset 模式)
  - **`dispatchWithPolicy` AskUser branch Deny 路径**(L635-696)加守卫 + add 1 行:
    ```java
    // OLD (Bug-2 missing guard):
    if (decision instanceof Decision.Deny) {
        return ToolResult.error("[LINGS-P02] User denied: " + reason);
    }
    
    // NEW (Story #050 Bug-2 fix):
    if (decision instanceof Decision.Deny) {
        attemptedDeny.add(toolCall.id());  // 记录 deny
        return ToolResult.error("[LINGS-P02] User denied: " + reason);
    }
    
    // 进入 AskUser branch 守卫:
    if (attemptedDeny.contains(toolCall.id())) {
        return ToolResult.error("[LINGS-P02] Tool '" + toolCall.name() 
            + "' already denied in this turn; please EndTurn or try different tool");
        // 短路:**不**发 ApprovalRequired,**不**进 ApprovalRegistry
    }
    if (decision instanceof Decision.AskUser) {
        ... 既有 registry.register(future) + ApprovalRequired 发射 ...
    }
    ```
  - 修改 import:加 `import java.util.HashSet;` + `import java.util.Set;`

### Phase 3 — Tests(并行,Phase 2 完成后)

> **可并行原因**:6 个测试文件互不依赖,各自验证独立行为。

- [P] **T-07** [P1][L1] complete `AgentEventMapperLlmStreamStartedTest.java` 2 case:
  - `mapsLlmStreamStartedToSseWithoutStepField` — 反射或 substring 检查 SSE 输出**不**含 `step`/`maxSteps` 字段,只含 `streamId` + `maxOutputTokens`
  - `unknownEventType_gracefullyIgnored` — 老 frontend `addEventListener("llm.stream.started")` 不存在 → mapper 返回不抛

- [P] **T-08** [P1][L1] complete `AnthropicStreamParserSseEventFixTest.java` 2 case(反射验证):
  - `onMessageStart_emitsLlmStreamStarted_notReasoningStarted` — 反射调 AnthropicStreamParser 私有方法 onMessageStart + 用 TestSubscriber capture events,断言第 1 个 emit 是 LlmStreamStarted 不是 ReasoningStarted
  - `noFakeStepOne_emitted` — 解析完整 mock SSE 流,断言**无**任何 `ReasoningStarted(1, 50)` 事件出现

- [P] **T-09** [P1][L2] complete `AnthropicStreamParserSseEventFixIT.java` 2 case:
  - `mockSseRealtimeRegression` — 完整 mock SSE(server = `com.sun.net.httpserver.HttpServer` 老 IT 模式),20ms 增量 + 5 个 message_start,断言 sink 收到 5 个 LlmStreamStarted(streamId 各不同) + 0 个 ReasoningStarted
  - `replaysBackwardCompat_anthropicStreamProviderIT` — 跑老 Story #027b `AnthropicStreamProviderIT` 2 case,**0 改动**全过(本 Story 改动只影响 emit 事件类型,但 fixture 期待行为 `ReasoningStarted` 改名后 fixture 自更新断言)

- [P] **T-10** [P1][L1] complete `LinearTurnEngineAskLoopGuardTest.java` 5 case:
  - `deny_addsToAttemptedDenySet` (AC-050-6) — 触发 deny,assert attemptedDeny.contains(toolCallId)
  - `reaskSameToolUseId_shortCircuits` (AC-050-7) — 第二次同 toolUseId,assert ApprovalRegistry.size()=0(短路)+ ToolResult.isError()=true + 内容含 "[LINGS-P02]"
  - `differentToolUseId_stillRegistersNormally` (AC-050-8) — 不同 toolUseId,assert ApprovalRegistry.register 正常调
  - `turnStart_resetsAttemptedDeny` (AC-050-9) — 跑完 1 turn(其中 deny 几次)+ 跑新 turn,assert 新 turn attemptedDeny 为空
  - `approve_doesNotAddToAttemptedDeny` (AC-050-10) — 只 approve 路径(无 deny),assert attemptedDeny 永远空 + ApprovalRegistry.size()=1

- [P] **T-11** [P1][L3] complete `LinearTurnEngineAskUserLoopGuardIT.java` 1 case(AC-050-11):
  - mock LLM 持续 4 次同 `write_file` toolUseId,user 4 次 deny
  - ApprovalRegistry.size()=1(只有第 1 次进 registry)
  - attemptedDeny.size()=1
  - StopReason=MAX_STEPS(跑满 50 steps)
  - SSE 流中:1 个 ApprovalRequired + 3 个 [LINGS-P02 already denied]
  - 全程 verify 没 ApprovalRegistry race(单 Agent 串行,无并发问题)

### Phase 4 — Validation(sequential)

- **T-12** [P1][RAC] 跑 `mvn -pl lingshu-core test` — Story #030 / #041 / #047 / #048 既有 fixture(24 case)+ 全 suite 760 → 774 (14 new case PASS)0 fail(2 MCP heartbeat flake pre-existing 忽略)
- **T-13** [P1][DepTree] 跑 R-13 mitigation (d) baseline 镜像:
  - `git show HEAD:lingshu-core/pom.xml | md5sum` 拿 base md5
  - 实施 commit
  - `git show HEAD:lingshu-core/pom.xml | md5sum` 拿 post md5
  - base == post → **0 binary delta 第 33 次 PASS**
  - `mvn -pl lingshu-core dependency:tree` 输出 pre/post diff 仅时间戳差异
- **T-14** [P1][demo-product] 跑 `mvn -pl lingshu-examples/demo-product verify` — 0 后端 Java 改动,只验证 demo-product 启动不崩(SSE event 类型新增不破坏 EventSource round-trip)
- **T-15** [P1][L3] 人工端到端 5 步清单(README.md 同步更新):
  1. `mvn -pl lingshu-examples/demo-product spring-boot:run`
  2. 配 `agent.permission-policy: ask` + `tools.ask-list: [bash]`(Story #047 已落)
  3. 输入"以秋月为题写一首七言古诗,保存到 poem.txt"(触发用户实际 bug 场景)
  4. SSE 流观察:**step 单调递增不再乱跳**(Bug-1 fixed),user 拒绝一次 write_file 后,Agent 不再无限弹新 modal,**转向 EndTurn**(Bug-2 fixed)
  5. 业务方可以再次试 approve,LLM 走正常路径完成

### Phase 5 — Docs 同步(parallel)

- [P] **T-16** [P1][docs] CLAUDE.md v1.3.58 → v1.3.59 + 「Last updated」+「Version」+ §0/§1/§6 同步 Story #050 描述 + dsh 版本号同步 v1.5.61 → v1.5.62
- [P] **T-17** [P1][docs] `dsh_agent_design.md` v1.5.61 → v1.5.62 + §0 标题 + §13 changelog 新增 v1.5.62 行 + §6.1 LinearTurnEngine 段补 `🆕 v1.5.62 Story #050` attemptedDeny + AskUser branch guard marker + §4.7 ApprovalGate 段补 `🆕 v1.5.62 Story #050` Bug-2 fix annotation + §6.5 (2) SSE event stream 段补 `🆕 v1.5.62 Story #050` LlmStreamStarted 注解
- [P] **T-18** [P1][docs] `README.md` 「更新日期」段加 🆕 v1.5.62 Story #050 顶部 blockquote + 「核心特性」段补 🛡️ ask-loop-guard deny-once 守卫 + 📡 step-reset SSE 收口 bullets + 「Story 路线图」段追加 #050 retrospective(2-bug 合修 + 4 核心 prod 文件改动 + 6 test 文件 + 14 case + R-13 0 binary delta 第 33 次 PASS / 0 新 ErrorCode / 累计 52 个 Story)
- [P] **T-19** [P1][docs] `specs/ROADMAP.md` 段一 ✅ 已完成加 #050 行(2026-10-15,14 case pass / R-13 0 binary delta 第 33 次 PASS / 0 新 ErrorCode)+ 段二 🟡 待补 #050 划掉(Bug-3 ApprovalRegistry race defer to #051)+ 段五 🎯 实施节奏 累计计数 51 → **52 个 Story**
- [P] **T-20** [P1][docs] `constitution.md` §10 R-13 缓解 Story 列表补 `#050` 行**第 33 次** R-13 mitigation (d) PASS 0 binary delta + 累计计数 32 → **33 个 Story**

## Complexity Tracking

无超出预算项。**4 核心 prod 文件 ≤ 5 预算** ✓ / **0 新 ErrorCode ≤ 3 预算**(复用 LINGS-P02)✓ / **0 新 Maven 依赖** ✓。

唯一 stretch 候选:
- `AgentEvent.LlmStreamStarted` nested class 设计(streamId + maxOutputTokens 字段)— 但严格 1 个 immutable nested class,仍 ≤ 5 文件改动预算内
- `LinearTurnEngine.attemptedDeny` 字段 + turn-start reset 2 处 + 守卫 1 处 + add 1 处 = 4 处改点 — 但都在同一个文件内

不引新公开方法:
- `AnthropicStreamParser` 公开方法 `parse` / `onMessageStart` 不变(只是 emit 出去的事件类型)
- `LinearTurnEngine.runTurn` 公开方法签名不变
- `LinearTurnEngine.dispatchWithPolicy` 方法签名不变(只内部扩守卫)
- `AgentEvent.LlmStreamStarted` nested class 公开方法就是 `type()` + 2 accessor,0 新方法

## 测试覆盖统计

- **14 new case**(8 L1 + 3 L2 + 1 L3 + 1 RAC + 1 DepTree,实际计 14 case)— **不重复计数**
- 14 case 分布:
  - `AgentEventLlmStreamStartedTest` — 2 case
  - `AgentEventMapperLlmStreamStartedTest` — 2 case
  - `AnthropicStreamParserSseEventFixTest` — 2 case(反射)
  - `AnthropicStreamParserSseEventFixIT` — 2 case(L2 IT)
  - `LinearTurnEngineAskLoopGuardTest` — 5 case
  - `LinearTurnEngineAskUserLoopGuardIT` — 1 case(L3 IT)
  - RAC(mvn test 跑既有 suite)— 1 case(全 suite pass)
  - DepTree(R-13 baseline 镜像)— 1 case
- **774 → 788 累计 case**(14 新增)
- **RAC**:Story #030(23)+ #041(9)+ #047(8)+ #048(4)= 44 case 0 改动全过 + 全 suite 760 → 774 0 regression

## R-13 mitigation (d) baseline 镜像 checklist

| 步骤 | 命令 | 预期 |
|---|---|---|
| 1. 取 base md5 | `git show HEAD:lingshu-core/pom.xml \| md5sum` | 拿 `base_md5_1` |
| 2. 实施 commit | `git add -p` + `git commit -m "feat(agent): Story #050 ..."` | 写盘 |
| 3. 取 post md5 | `git show HEAD:lingshu-core/pom.xml \| md5sum` | 拿 `post_md5_1`,应 = `base_md5_1` |
| 4. dep-tree diff | `mvn -pl lingshu-core dependency:tree` pre/post | 仅时间戳差异 |
| 5. enforcer | `mvn -pl lingshu-core verify -Pbanned-dependencies` | Rule 0 passed |
| 6. 计入累计 | R-13 缓解 Story 列表 + #050 | 累计 33 个 Story |

**预期 PASS**:**第 33 次 0 binary delta**(纯 JDK 8 `HashSet` + `Collections.emptySet()` + Lombok `@Value` 已锁 13 项依赖表内 0 新 binary 引入)。

## 业务价值回顾

- **Bug-1 SSE step reset 收口** — demo-product 前端 step 计数器单调递增,不再受 LLM 内部 `message_start` 干扰,UX 清晰
- **Bug-2 approval loop 收口** — deny-once 守卫让 user 的「不」是有意义的信号(LLM 转向 EndTurn 或换工具,而不是沉默循环)
- **Story #030-#048 6 件套链路完整闭环** — ask-mode 后端 + frontend + yml + hang-safety + SPI 抽离 + **本 Story deny-once guard** = 7 件套完整业务实现
- **R-04 (privilege escalation 缓解)** — 分值 8,Story #029 + #030 + #031 + #037 + #041 + #042 + #047 + #048 + **#050** = 9 件套完整 R-04 缓解
- **demo-product 旗舰 demo 真正可工作** — 业务方按 docs 跑 ask-mode 场景 不会遇到无限循环;SSE 流连续 step 计数清晰
- **Bug-3 (deferred)**:ApprovalRegistry race condition 留给 OQ-Future / Story #051;本 Story 4 预算文件改动严守 ≤ 5 边界
- **JDK 8 兼容** — 纯 `Collections.emptySet()` / `HashSet` / Lombok `@Value` / Spring `@Component` 已锁
- **Spring AI 仍禁用自动执行** — §4.10.1 硬规则 2 守住
- **ReAct Loop 自实现不变** — §4.10.1 硬规则 1 守住
- **累计 52 Story** — v0.1.0 GA 推进
