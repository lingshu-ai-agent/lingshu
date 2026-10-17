# Story #050 — ask-loop-guard + step-reset SSE 收口(2-bug 合修)

**Story #**: #050
**Slug**: `ask-loop-guard-end-of-turn`
**dsh §**: §6.1 LinearTurnEngine AskUser branch + §6.5 (2) SSE event stream + §4.7 ApprovalGate
**类型**: Bug-fix / 收口 Story(close 2 production bug + 1 OQ-Future deferred)
**预算**: 4 核心 prod 文件改动 / ≤ 2 ErrorCode 复用 / 0 新 Maven 依赖
**前置依赖**: Story #030(`Decision.AskUser` 3rd outcome)+ Story #041(`ApprovalGate` SPI 抽离)+ Story #042(frontend Allow/Deny UI)+ Story #047(yml `permission-policy: ask` 真接通)+ Story #048(hang-safety `ToolResult` sentinel)+ Story #027b(SSE 真流式接通)
**挡板**:Story #030-#048 ask-mode 6 件套链路已落地,**实测 demo-product 出现 2 类生产 bug**(2026-10-15 用户反馈)— 本 Story 收口

---

## WHY(根因 + 业务价值)

### 问题(2 个独立 production bug)

用户 2026-10-15 在 demo-product 实测 ask-mode 跑场景"以秋月为题写一首七言古诗,保存到 `/var/folders/.../lingshu-sessions/_default/poem.txt`",SSE 流展示两个独立 bug:

**Bug-1 — Step counter reset**:
```
reasoning.start {"type":"reasoning.start","step":22,"maxSteps":50}  ← LinearTurnEngine 主循环 L271
           ↓ (LLM 调 write_file → ApprovalRequired → user approve)
reasoning.start {"type":"reasoning.start","step":1,"maxSteps":50}   ← AnthropicStreamParser L208(硬编码 step=1)
           ↓
reasoning.start {"type":"reasoning.start","step":24,"maxSteps":50}  ← LinearTurnEngine 主循环 L271(继续)
           ↓
...持续重复直到 maxSteps...
```

step 计数从 22 跳到 1 又跳回 24,**SSE 用户看到 step 反复 reset**,以为 Agent 失控。

**Bug-2 — Approval loop on `write_file`**:
write_file 实际已成功(124 bytes 落到磁盘),但 Agent 继续请求 approval:
```
tool.start write_file approval d6d5aaa8  ← ApprovalRegistry UUID
       (user clicks Deny)
tool.result...  ← "[LINGS-P01] Tool not in allowlist" 或 "user denied"
       ↓ (LLM 再次尝试 write_file)
tool.start write_file approval 6dae2e4b  ← 新 UUID,新 modal
       (user clicks Deny)
...持续循环直到 maxSteps...
```

**根因**:**Denial 没有「per-turn no-reask」守卫** — LLM 收到 deny feedback 后继续重发同一 `write_file` tool_call → 每次进 ApprovalRegistry 拿新 UUID → 弹新 modal → user deny → LLM 再尝试 → 无限循环。

### 根因深度分析

**Bug-1 根因**:`AnthropicStreamParser.java:208` 在解析 `message_start` 时硬编码 `new AgentEvent.ReasoningStarted(1, 50)`。这是 #027b 落地时的"占位 stub" — 当时 LLM 流式 event 还没有"轮次开始"的语义,临时写一个看起来合理的 step=1 让前端有数据展示。但实现错了,**应该用一个新的 `AgentEvent.LlmStreamStarted` 子类**表达"LLM 流的 message_start"语义(`step` 不参与 reasoning 主循环计算),而不是复用 `ReasoningStarted`(该事件表示 LinearTurnEngine 主循环每 step 的开始,有 step 计数器)。

**Bug-2 根因**:`LinearTurnEngine.dispatchWithPolicy` AskUser branch(L635-696)在 `Decision.Deny` 路径上:
1. 返回 `ToolResult.error("[LINGS-P02] User denied: ...")` 给 LLM
2. **不记录**该 `toolCall.id()` 为 "本 turn 已 deny"
3. LLM 收到 ToolResult 后,在下一步的 ReAct Action 阶段**重新尝试**同一 tool(因为它是写文件这种必需完成的 user request)
4. 再次进 AskUser branch
5. ApprovalRegistry 拿新 UUID(每次注册是新的 future)
6. **无限循环**直到 maxSteps=50 强制结束

**没有 per-tool-call once-deny 守卫 → 同一 turn 内同一 tool 被 deny 一次后应被短路**(LLM 应该转向 EndTurn 而不是再次请求同一工具)。

### 业务价值

- **Bug-1 修复** — SSE 流 step 计数器语义清晰,LLM 内部 `message_start` 不应该反映到主循环 step 上;前端 UI 才能正确显示"step 22/50 完成中"而不是乱跳
- **Bug-2 修复** — ask-mode 业务价值落地:用户拒绝一次 tool 后,Agent 必须停止该 tool 的无限重试,转向告诉 user「你 deny 了,我该怎么办」让 user 决策,而不是默默循环到 maxSteps
- **Story #030-#048 6 件套链路收口** — ask-mode 后端 + frontend + yml + hang-safety + SPI 抽离都已落地,**唯一缺口就是 AskUser branch 缺 deny-once 守卫**,本 Story 补齐
- **demo-product 旗舰 demo 完整** — 业务方按 docs 跑 demo loop 不会遇到无限循环,文档示例可截屏
- **R-04 (privilege escalation 缓解)** — 分值 8,从 Story #029 strict → #030 ask → #031 pattern → #041 SPI 抽 → #042 frontend → #047 yml binding → #048 hang-safety → **#050 deny-once guard** = 8 件套完整闭环
- **Bug-3 (deferred)** — `ApprovalRegistry.evictBySessionPrefix` 是 race condition(OQ-Future / Story #051),本 Story **不修**(4 预算文件改动严守 ≤5 边界)

### 为什么不是 RFC / 宪章改 / 跨 Story 改 constitution

- **0 新接口契约**:`ApprovalGate` SPI(Story #041)+ `Decision` 3 子类(Story #030)+ `AgentEvent` 嵌套事件 hierarchy + `LlmProvider` SPI 全部 0 改动
- **0 新 ErrorCode**:复用 Story #030 `LINGS-P02 PERMISSION_APPROVAL_TIMEOUT`(已在 `#030` 落地,AskUser branch timeout 路径专用),本 Story Deny-once 守卫短路路径复用此 error code 前缀表达"tool 已被 deny 一次,不再请求"
- **0 新 Maven 依赖**:纯 JDK 8 + Lombok + Spring + Jackson + `java.util.concurrent` + `java.util.HashSet` 全部已锁
- **≤ 5 核心文件改动**:4 核心 prod(`AnthropicStreamParser` + `AgentEvent` + `AgentEventMapper` + `LinearTurnEngine`),0 改 `ApprovalRegistry` / `ApprovalGate` / `LinearTurnEngineProvider` / `AgentFactory` / `Routers`
- **0 改 AgentConfig schema**:`agent.permission-policy: ask` + `agent.tools.ask-list` yml 接线已落(Story #047),业务方 0 改动
- **< 3 新 ErrorCode** — 复用 LINGS-P02(Permission 域 P 段 2 号,Story #030 已落)

---

## WHO(参与者 + 边界)

| 角色 | 改动范围 | 行为 |
|---|---|---|
| **`AnthropicStreamParser`** | modify(L208 改 emit 事件) | 不再硬编码 emit `ReasoningStarted(1, 50)`,改 emit 新的 `AgentEvent.LlmStreamStarted` 事件 |
| **`AgentEvent`(事件 hierarchy)** | modify(1 个 nested class 新增) | 加 `AgentEvent.LlmStreamStarted(String streamId, int maxOutputTokens)` nested class 表示 "LLM 流启动" |
| **`AgentEventMapper`** | modify(1 行 mapper 加) | 把 `LlmStreamStarted` 映射为 SSE `{"type":"llm.stream.started","streamId":"...","maxOutputTokens":...}` |
| **`LinearTurnEngine`** | modify(扩 3 段:字段 + 守卫 + reset) | 加 `attemptedDeny: Set<String> toolUseId` 字段 + `dispatchWithPolicy` AskUser branch Deny 路径短路守卫 + turn-start 重置 |
| `LinearTurnEngineProvider` | 0 改动 | 镜像 Story #045 dual-ctor 链路已就位 |
| `ApprovalRegistry` | 0 改动 | 既有的 `register / complete / unregister` 不变 |
| `ApprovalGate` SPI | 0 改动 | Story #041 已落 |
| `Decision` 3 子类 | 0 改动 | Allow / Deny / AskUser |
| `AskUserPermissionPolicy` | 0 改动 | Story #030 5 段决策不变 |
| `AgentFactory` | 0 改动 | 9-Router ctor + 接线已就位(from Story #046) |
| `AgentConfig` | 0 改动 | `permissionPolicy` / `tools.ask-list` / `approvalTimeoutSeconds` 已落 |
| `DefaultToolExecutor` / `ToolExecutor` | 0 改动 | §4.10.1 硬规则 2 守住 |
| `LlmProvider` SPI / `Message` 4 子类 | 0 改动 |  |
| `MCP / A2A / MemorySource / Compactor / SessionStore` | 0 改动 | 9 Slot 体系不变 |
| `demo-product ChatController` | 0 改动 | `/api/approvals/{sessionId}/{approvalId}` 端点不变(Story #042 + #047 已落) |

---

## WHAT(用户故事 + 验收)

### User Story 1 — SSE step 计数器单一来源(P1,主线 Bug-1)

**描述**:demo-product SSE 流中 `step`/`maxSteps` 字段只反映 `LinearTurnEngine` 主循环的 ReAct step 计数,不再混入 `AnthropicStreamParser` 在每个 LLM `message_start` 硬编码的 `step=1` 占位事件。前端 UI 看到的是连贯的"step=22/50 → step=23/50 → ..."而不是 22 → 1 → 24 乱跳。

**Why this priority**:SSE 流是 demo-product 的核心 UX,step 乱跳让用户以为 Agent 失控;fix 后语义清晰(LLM 流的 `message_start` vs ReAct step start 是不同事件)。

**Independent Test**:
1. 启动 demo-product,跑 ask-mode 场景(任何 Agent 触发 LLM 调 tool 都行)
2. `curl localhost:8080/api/sessions/{sessionId}/events` 拿 SSE 流
3. 断言:**整个 turn 内** `reasoning.start` 事件的 `step` 字段**单调递增**,不再出现 "step=22 → step=1 → step=24" reset
4. LLM 内部流(`message_start`)通过 `llm.stream.started` 事件另行发射,与 ReAct step 解耦

**Acceptance Scenarios**:

1. **Given** demo-product ask-mode 启动 + Agent 触发 LLM tool call flow **When** 用户跑 1 轮对话 **Then** SSE 流中 `reasoning.start` 事件**只**由 `LinearTurnEngine.runTurn` 主循环 L271 发射,step 字段单调递增(0/1/2/.../maxSteps)
   - **AnthropicStreamParser 不再 emit** `ReasoningStarted` 事件 — Bug-1 root cause fixed

2. **Given** 同上 **When** LLM 每个 `message_start` **Then** SSE 流改 emit `{"type":"llm.stream.started","streamId":"...","maxOutputTokens":N}` 事件,**不**包含 `step` / `maxSteps` 字段
   - 新事件类型独立,前端可选择性消费(老前端忽略即可,since `step` 字段不存在 → 加了 `hasOwnProperty` check)

3. **Given** 老 demo-product frontend(无 `llm.stream.started` handler)— 收到新事件类型 **When** frontend `addEventListener(type, ...)` **Then** 老前端**不报错**(只是不显示 new event),业务可继续工作
   - `AgentEventMapper` 映射失败 fallback 路径要 graceful

4. **Given** AnthropicStreamParser 解析 mock SSE 流(20ms 增量 + 5 个 message_start)— 老 Bug-1 fixture **When** 重新跑 Story #027b 既有 `AnthropicStreamProviderIT` **Then** **0 regression** 全部通过(只事件类型改名,fixture 行为映射后等价)

### User Story 2 — AskUser Deny-once 守卫短路(P1,主线 Bug-2)

**描述**:`LinearTurnEngine` 在 AskUser branch 收到 `Decision.Deny` 后,把 `toolCall.id()` 加入 `attemptedDeny: Set<String>`,后续同一 turn 内同一 `toolCall.id()` 不再发起 ApprovalRequest(`ApprovalRegistry` 不再 register 新 UUID),直接返 `[LINGS-P02] Tool '<n>' already denied in this turn` 给 LLM,迫 LLM 转向 EndTurn 而不是无限循环。

**Why this priority**:这是 ask-mode 业务价值核心 — 用户 deny 后 Agent 必须礼貌收手,而不是沉默循环到 maxSteps;fix 后 deny 是 meaningful signal(LLM 走 EndTurn,把 deny reason 转告 user)。

**Independent Test**:
1. mock LLM 在 step 3 emit `write_file` tool_call → AskUser → user deny
2. mock LLM 在 step 4 再次 emit **同一** `write_file` tool_call(同 toolUseId)→ 应当**不**弹新 approval modal,直接返 short-circuit ToolResult
3. mock LLM 在 step 5 emit **不同** tool_call(`read_file`)→ 应当走正常 AskUser 流程(per-tool 守卫,不是 per-turn 全 deny)
4. 跑到 turn 10 时 LLM 仍 emit `write_file` 同 toolUseId → 永远短路,**不**进 ApprovalRegistry
5. 断言:`ApprovalRegistry.size()` ≤ 1(只有第一次进),`LinearTurnEngine.attemptedDeny.size() == 1` 守不变

**Acceptance Scenarios**:

1. **Given** `attemptedDeny` 集合为空 + AskUser branch 收到 `Decision.Deny`(toolCall.id="d6d5aaa8")**When** 守卫检查 + deny 处理 **Then** `attemptedDeny` 含 `"d6d5aaa8"` + 返 `ToolResult("[PENDING_APPROVAL denied]")` → ApprovalRegistry.register **0 次**(短路)
   - **短路路径不发** `AgentEvent.ApprovalRequired` 事件 — 不再弹前端 modal

2. **Given** `attemptedDeny` 含 `"d6d5aaa8"` + 同 turn step 5 LLM 再 emit `toolCall.id="d6d5aaa8"` **When** AskUser branch 守卫命中 **Then** 立即返 `ToolResult.error("[LINGS-P02] Tool 'write_file' already denied in this turn; please EndTurn or try different tool")` 给 LLM
   - **不**进 ApprovalRegistry,**不**发 ApprovalRequired 事件

3. **Given** step 5 LLM emit `toolCall.id="<不同 id,例如 9abcdef0>"`(**新** tool_call)**When** AskUser branch 守卫**未**命中(attemptedDeny 不含此 id) **Then** 走正常 ApprovalRegistry.register 路径 + 发 ApprovalRequired 事件
   - per-`toolUseId` 守卫,**不**是 per-turn 全 deny

4. **Given** turn 跑完(Step 50/Cancel/Exception/正常 END_TURN 任一)**When** 下个 turn start **Then** `attemptedDeny = new HashSet<>()` 重置,新 turn 各 `toolUseId` 独立
   - 重置时机:**turn-start**(不是 ctor),镜像 Story #008 maxSteps reset 模式

5. **Given** mock LLM 在 step 3/4/5/6 持续 emit 同 `write_file` toolUseId 4 次 **When** 跑 50 steps **Then** ApprovalRegistry.register 调 1 次 + 其余 3 次短路返 LINGS-P02 → ApprovalRegistry.size()=1,attemptedDeny.size()=1
   - 最终 StopReason = MAX_STEPS,但 SSE 流**不再**爆 N 个 ApprovalRequired modal,业务方可观察明确终止信号

### User Story 3 — 反向 AC(RAC):Story #030/041/047/048 既有 fixture 0 改动(P1)

**描述**:Story #030 落地 23 case + Story #041 落地 9 case + Story #047 落地 8 case + Story #048 落地 4 case = 44 个 AskUser/ApprovalGate 相关 fixture,本 Story 改动 `LinearTurnEngine.dispatchWithPolicy` 内部字段和 Deny 路径,**不得**触发任何 fixture 的 regression。

**Why this priority**:Story #030 后端 + Story #041 SPI 抽离 + Story #047 yml binding + Story #048 hang-safety = AskUser 完整 4 件套都已落地且 RAC 4 次全过,本 Story 加 `attemptedDeny` 字段 + Deny-once 守卫必须**不**破坏既有 happy path(LLM 一次 tool 一次 approve 完成)。

**Independent Test**:`mvn -pl lingshu-core test` 全 suite 跑过 0 regression:**特别是** `LinearTurnEngineAskUserTest` 3 case / `DefaultApprovalGateTest` 5 case / `AgentFactoryAskPolicyRoutingIT` 3 case / `LinearTurnEngineAskUserNoRenderIT` 4 case / `ApprovalRegistryTest` 4 case / `AskUserPermissionPolicyTest` 5 case = 24 case 必须全过。

**Acceptance Scenarios**:

1. **Given** LLM 1 次 tool_call → AskUser → user approve(无 deny)**When** 跑 fixture **Then** LLM 收到 `[PENDING_APPROVAL approved]` ToolResult + LinearTurnEngine 正常继续 + Story #030 既有 5 case fixture 0 改动全过
   - approve 路径**不**进入 attemptedDeny(只有 deny 路径才加 set)
   - 这是关键:approve 是「OK 的继续」,deny 才是「不要再来一次」

2. **Given** LLM 连续 2 次 tool_call(2 个不同 toolUseId)都 AskUser → user 各自 approve **When** 跑 fixture **Then** 2 次都走正常路径,**无** attemptedDeny 守卫触发,**无**短电路由
   - per-tool 守卫隔离,not per-approval

3. **Given** Story #048 hang-safety sentinel ToolResult(AskUser 阻塞期间的历史占位)— 老 fixture 走 happy approve **When** 跑 Story #048 fixture **Then** 0 改动全过(本 Story 不动 sentinel 路径)

### User Story 4 — 0 二进制新增 dependency(R-13 mitigation)(P1,强制)

**描述**:本 Story 0 新 Maven 依赖;`mvn -pl lingshu-core dependency:tree` pre/post md5sum 应一致;`banned-dependencies` enforcer Rule 0 passed。

**Why this priority**:硬约束 §11 #6 + R-13 mitigation (d) 第 33 次 PASS(累计 32 个 Story)。

**Independent Test**:Phase 4 T-12 baseline 镜像清单已包含(详见 tasks.md Phase 4)。

**Acceptance Scenarios**:

1. **Given** pre-baseline `git show HEAD:lingshu-core/pom.xml | md5sum` 拿 base md5 **When** 实施 commit 后 **Then** post md5 与 base 相同(= 0 binary delta)
2. **Given** `mvn -pl lingshu-core verify -Pbanned-dependencies` **When** 跑 enforcer **Then** Rule 0 passed

---

## AC(Acceptance Criteria 编号清单)

| AC# | 类型 | 描述 | 测试方法 |
|---|---|---|---|
| **AC-050-1** | L1 unit | `AgentEvent.LlmStreamStarted(streamId, maxOutputTokens)` 不可变事件类 + 2 个 accessor + `final class` | `AgentEventLlmStreamStartedTest` |
| **AC-050-2** | L1 unit | `AgentEventMapper` 把 `LlmStreamStarted` 映射为 SSE `{"type":"llm.stream.started","streamId":...,"maxOutputTokens":...}` **不**含 `step`/`maxSteps` 字段 | `AgentEventMapperLlmStreamStartedTest` |
| **AC-050-3** | L1 unit | `AnthropicStreamParser.onMessageStart` 改 emit `LlmStreamStarted(streamId, maxOutputTokens)` **不**再 emit `ReasoningStarted(1, 50)` 硬编码占位 | `AnthropicStreamParserSseEventFixTest`(反射验证 emit 路径) |
| **AC-050-4** | L2 IT | mock SSE 流(20ms 增量 + 5 个 message_start)— 老 `AnthropicStreamProviderIT` 行为映射后等价:**无** `ReasoningStarted(1, 50)` 触发,**有** `LlmStreamStarted(streamId, ...)` 触发 | `AnthropicStreamParserSseEventFixIT` |
| **AC-050-5** | L1 unit | `LinearTurnEngine.attemptedDeny` 字段类型 `Set<String>` + turn-start 重置为 `new HashSet<>()` + 每 deny 加 entry | `LinearTurnEngineAskLoopGuardTest` |
| **AC-050-6** | L1 unit | `dispatchWithPolicy` AskUser branch 收到 `Decision.Deny` 后 add `toolCall.id()` 到 `attemptedDeny` + ApprovalRegistry**不** register(短路 1) | `LinearTurnEngineAskLoopGuardTest.deny_addsToAttemptedDenySet` |
| **AC-050-7** | L1 unit | `dispatchWithPolicy` AskUser branch 守卫命中(`attemptedDeny.contains(toolCall.id())`)→ 立即返 `ToolResult.error("[LINGS-P02] already denied...")` **不**进 ApprovalRegistry(短路 2) | `LinearTurnEngineAskLoopGuardTest.reaskSameToolUseId_shortCircuits` |
| **AC-050-8** | L1 unit | per-toolUseId 守卫,**不**是 per-turn 全 deny — 不同 toolUseId 走正常 ApprovalRegistry.register | `LinearTurnEngineAskLoopGuardTest.differentToolUseId_stillRegistersNormally` |
| **AC-050-9** | L1 unit | turn-start 重置(`runTurn` 入口 `attemptedDeny = new HashSet<>()`)— 镜像 Story #008 maxSteps 守卫 reset 模式 | `LinearTurnEngineAskLoopGuardTest.turnStart_resetsAttemptedDeny` |
| **AC-050-10** | L1 unit | approve 路径**不**入 set,只 deny 路径入 set(语义清晰,happy path 0 变化) | `LinearTurnEngineAskLoopGuardTest.approve_doesNotAddToAttemptedDeny` |
| **AC-050-11** | L3 IT | mock LLM 持续 4 次同 `write_file` toolUseId 4 次 deny → ApprovalRegistry.register 1 次 + 4 次短路 → ApprovalRegistry.size()=1 + StopReason=MAX_STEPS | `LinearTurnEngineAskUserLoopGuardIT`(跨 BlackBox 端到端) |
| **AC-050-12** | L2 IT | Bug-1 SSE 修复反射型验证:`LinearTurnEngine.runTurn` 内部**不**依赖任何 LLM-internal `ReasoningStarted(1, 50)` 触发;`AnthropicStreamParser` 测试 fixture 中 SSE 流不再含 step=1 占位事件 | `AnthropicStreamParserSseEventFixIT.noFakeStepOneEmitted` |
| **AC-050-13** | RAC | Story #030(23 case)+ #041(9 case)+ #047(8 case)+ #048(4 case)= 44 case **0 改动**全过 + `mvn -pl lingshu-core test` full suite 0 fail(2 MCP heartbeat flake pre-existing 忽略) | `mvn -pl lingshu-core test` 跑过既有 suite |
| **AC-050-14** | DepTree | `mvn -pl lingshu-core dependency:tree` pre/post md5sum diff 仅时间戳差异 = **0 binary delta 第 33 次 PASS**(`banned-dependencies` enforcer Rule 0 passed) | baseline 镜像(`git show HEAD:lingshu-core/pom.xml | md5sum` pre/post 比较)|

**总 AC 计数**:**14 case**(12 net new case + 1 RAC + 1 DepTree)

---

## 关键不变项

| 不变项 | 状态 | 备注 |
|---|---|---|
| `LlmProvider` SPI 公开方法 | 0 改动 | `stream(Prompt, TurnContext, Subscriber<AgentEvent>) → CompletableFuture<LlmResponse>` 不变 |
| `AnthropicLlmProvider.doPostStream` 6-arg ctor | 0 改动 | Story #027b + #043 既有 wiring 不变 |
| `AnthropicStreamParser` 6 段状态机 | 0 改动 | 只换 L208 emit 出去的事件类型(`ReasoningStarted(1, 50)` → `LlmStreamStarted(streamId, maxOutputTokens)`) |
| `AgentEvent` 基类 | 0 改动 | 只新增 1 个 nested class(`LlmStreamStarted`),`ReasoningStarted` / `TextDelta` / `ToolStarted` / `ToolResult` / `ApprovalRequired` / `TurnCompleted` / `Compaction` 等已有事件 0 改动 |
| `AgentEventMapper` SSE 映射 | 0 加 if/else 分支复杂度 | 1 行新增 mapper case + 既有事件类型映射 0 改动 |
| `ToolExecutor.dispatch()` 5 步流水线 | 0 改动 | §4.10.1 硬规则 2 守住 |
| `PermissionPolicy` SPI | 0 改动 | `check(Tool, ToolExecutionContext) → Decision` 公开方法签名不变 |
| `Decision` 3 子类 | 0 改动 | Allow / Deny / AskUser |
| `AskUserPermissionPolicy` 5 段决策 | 0 改动 | Story #030 + #031 pattern 通配 + #037 3 Provider 已落 |
| `ApprovalGate` SPI | 0 改动 | Story #041 单方法 `ask(Decision.AskUser) → Decision` 不变 |
| `ApprovalRegistry` | 0 改动 | Story #030 `register / complete / unregister` 接口不变(Bug-3 race 修复 deferred to OQ-Future / #051) |
| `AgentEvent.ApprovalRequired` 3-arg ctor | 0 改动 | Story #030 已落 |
| `LinearTurnEngine` 公开方法签名 | 0 改动 | `runTurn(TurnContext, Subscriber<AgentEvent>)` 不变;只新增 1 个 private 字段 `attemptedDeny` |
| `LinearTurnEngine` 公开方法 `dispatchWithPolicy` | 0 改动 | 既有 AskUser branch 内部逻辑扩 1 个守卫 + add 1 行 set 操作,**不**改方法签名 |
| `LinearTurnEngineProvider` | 0 改动 | Story #046 9-arg ctor(含 SessionStoreRouter)+ dual-ctor 镜像已就位 |
| `AgentFactory` 9-Router ctor | 0 改动 | Story #046 已就位 |
| `AgentConfig` 26 字段 | 0 字段新增 | `permissionPolicy` + `tools.ask-list` 已落(Story #029 + #030 + #037 + #047)|
| `DefaultAgent` | 0 改动 | Story #049 run/runBlocking 不动 |
| `DefaultTurnContext.appendToolResult` upsert-by-toolUseId | 0 改动 | Story #048 sentinel 已落,本 Story 不动 |
| `Message` 4 子类 | 0 改动 | System / User / Assistant(toolCalls 嵌入)/ ToolResult |
| `Checkpoint.@Value` 4-arg ctor | 0 改动 | Story #014 + #046 已落 |
| `Tool` SPI + 4 方法 | 0 改动 |  |
| §4.7 PermissionPolicy / AuditLogger / Cost 域 | 完全兼容 | 9 Slot 体系不变 |
| 9 Slot 体系 | 不变 | Slot 1/2/3/4/5/6/7/8/9 公开方法签名不变 |
| 26 字段 AgentConfig schema | 0 字段新增 |  |
| JDK 8 兼容 | 守住 | 纯 JDK 8 `HashSet` + `Collections.emptySet()` + `Arrays.asList()` + Lombok `@Value` + Spring `@Component` + Jackson `@JsonProperty` kebab-case 已锁 13 项依赖表内 0 新 binary 引入 |
| Spring AI `ChatClient.tools().call()` 仍**禁止**使用 | 守住 | §4.10.1 硬规则 2 守住 |
| ReAct Loop 自实现 | 守住 | §4.10.1 硬规则 1 守住 |
| `banned-dependencies` enforcer Rule 0 | passed | 0 新 binary 引入 |

---

## 反模式 / 风险 / OQ

### 反模式

- **不要 per-turn 全 deny** — 那是 over-reaction,会让 deny 后 turn 完全 freeze;正确是 per-`toolUseId` set 守卫
- **不要把 attemptedDeny 设为全局 static** — 多 turn 并发会污染;必须 per-Agent-instance 字段 + turn-start reset
- **不要在 approve 路径 add 进 attemptedDeny** — 那会让 approve 后第二次同 tool_call 也短路,语义错误(approve 是 OK 信号,不是禁止信号)
- **不要硬编码 `attemptedDeny.contains("d6d5aaa8")`** — 必须用 `toolCall.id()` 动态
- **不要让 `[LINGS-P02] already denied...` 真的进 ApprovalRegistry** — 短路路径必须**完全**跳过 ApprovalRegistry(包括 `register` 和后续 `complete`)
- **不要在 SSE emit `LlmStreamStarted` 时附 step 字段** — SSE 老前端会误以为是主循环 step 乱跳(Bug-1 复发)
- **不要把 ask-mode Bug-3 修复塞本 Story** — `ApprovalRegistry.evictBySessionPrefix` 是 race condition 修复,OQ-Future / Story #051,**本 Story 不修**(4 预算文件改动严守 ≤5 边界)

### 风险

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| **`attemptedDeny` 没 reset,跨 turn 误短路** | 中 | 用户上 turn deny 过的 tool,这 turn 再调用就被短路(误判) | `runTurn` 入口明确 `this.attemptedDeny = new HashSet<>();` + 镜像 Story #008 maxSteps 守卫 reset 模式 + L1 unit test 覆盖(AC-050-9) |
| **Bug-1 SSE 老前端不识别 `llm.stream.started` event type** | 中 | 用户升级 demo-product 后老前端报 JS console 错 | 新 event type 与老 event type 完全独立,老前端 `addEventListener("llm.stream.started", ...)` 不存在则**不** trigger error,业务可用(本 Story 改动在 backend event emit,frontend 可选择性升级) |
| **per-tool 守卫与 Story #048 sentinel ToolResult 冲突** | 低 | sentinel 已是 `[PENDING_APPROVAL]` 守卫不命中(因 toolUseId 是 dispatchWithPolicy 这次的 id,不是 sentinel 那个) | `[LINGS-P02] already denied...` 仅在 `attemptedDeny.contains(toolCall.id())` 命中时返回,与 sentinel 是 orthogonal concerns |
| **Bug-3 (deferred race condition)** | 低 | `ApprovalRegistry.pending.clear()` 在并发 session 切换时可能误清 active session | OQ-Future / Story #051 修;**本 Story 不修**,业务方单 Agent 串行场景不受影响 |
| **AnthropicStreamParser 改 emit 事件触发现有 Story #027b IT regression** | 中 | 老 fixture 期待 emit `ReasoningStarted(1, 50)`,改后 emit `LlmStreamStarted` | 跑 Story #027b 现有 fixture(RAC-13c 中明确包含),如 regression 则扩 fixture + 加 mapper fallback |
| **approve + 第二次同 toolCall 后被 attemptDeny 短路** | 低 | 用户 approve 后 LLM 重发同一 toolCall 被误短路 | 本 Story 严格只 deny 路径 add,approve 路径**不** add(AC-050-10 锁) |
| **`LlmStreamStarted` 前端未注册 listener 时抛错** | 低 | EventSource onmessage 中未处理 type 会进 default 分支但**不**抛(JS EventSource spec) | mapper 映射失败 fallback 优雅(不抛异常,业务可用) |

### OQ(Open Questions) / Follow-up

- **OQ-050.1(Bug-3 race condition)**:Story #051 接通 — `ApprovalRegistry.evictBySessionPrefix` 修复为 proper session-prefixed eviction(并发 Agent 多 turn 安全),分值 R-XX 中等,**本 Story 不修**(避免预算爆炸)
- **OQ-050.2(per-tool-call TTL)**:当前 `attemptedDeny` 是 end-of-turn reset,跨 turn 同 toolUseId 不同会重新进 AskUser;OQ-Future 加 `attemptedDenyTtlSeconds` 让 user 有"暂时不要这个工具"的语义
- **OQ-050.3(sentinel compatible cleanup)**:Story #048 落地 sentinel `ToolResult` 后如果 user deny,同 toolCall LLM 永远拿不到 `[PENDING_APPROVAL denied]` 的语义(它只看到 sentinel `[PENDING_APPROVAL] Awaiting user decision...`),本 Story 短路路径额外发 1 个 `AgentEvent.DeniedToolResult(toolUseId, reason)` 让 LLM 看到明确的 deny reason — 暂留 OQ-Future,当前 LINGS-P02 已含 reason 字符串够用
- **OQ-050.4(frontend `llm.stream.started` 选择性升级)**:本 Story 仅 backend emit,frontend 不强制升级;OQ-Future 后续 Story 决定是否升级 frontend
- **OQ-050.5(`AnthropicStreamEvent` SSE 解析完整性)**:`AnthropicStreamParser.message_start` event 当前只 emit `LlmStreamStarted`,后续 `content_block_start` / `content_block_delta` 等仍按 Story #027b 既有路径(emit `TextDelta` / `ToolStarted`);OQ-Future 验证 SSE 协议层 event type 与 LingShu `AgentEvent` 嵌套类的 1:1 映射完整性

---

## 实施期顺序(高阶)

1. **Phase 1 — Setup(并行)**:加 `AgentEvent.LlmStreamStarted` nested class + `AgentEventMapper` 1 行映射(Bug-1)+ 加 `LinearTurnEngine.attemptedDeny` 字段(Bug-2)
2. **Phase 2 — Core sequential**:`AnthropicStreamParser` L208 改 emit 事件类型(Bug-1)→ `LinearTurnEngine.dispatchWithPolicy` AskUser branch 加 Deny-once 守卫 + turn-start reset(Bug-2)
3. **Phase 3 — Tests(并行)**:`AgentEventLlmStreamStartedTest` 2 case(Bug-1)+ `AgentEventMapperLlmStreamStartedTest` 2 case(Bug-1)+ `AnthropicStreamParserSseEventFixTest` 2 case(Bug-1)+ `AnthropicStreamParserSseEventFixIT` 2 case(Bug-1)+ `LinearTurnEngineAskLoopGuardTest` 5 case(Bug-2)+ `LinearTurnEngineAskUserLoopGuardIT` 1 case(Bug-2)= 14 case
4. **Phase 4 — Validation**:mvn test 全 suite 跑过 0 regression(RAC-13e)+ mvn -pl lingshu-core dependency:tree baseline 镜像(AC-050-14,R-13 第 33 次 PASS)+ demo-product 启动不崩 + 人工端到端 5 步清单
5. **Phase 5 — Docs 同步**:CLAUDE.md v1.3.58 → v1.3.59 + dsh v1.5.61 → v1.5.62(本 Story 完成 + 累计 52 Story)+ specs/ROADMAP.md 加 #050 行 + README.md 「更新日期」段加 🆕 Story #050 顶部 blockquote + 「核心特性」段补 🛡️ ask-loop-guard + step-reset 收口 bullet + 「Story 路线图」段追加 #050 retrospective + constitution §10 R-13 缓解 Story 列表补 #050 行**第 33 次** R-13 mitigation (d) PASS 0 binary delta
