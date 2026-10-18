# Story #052 — dispatch-parallel-protocol-safety

> **Status**: spec ready(2026-10-18)
> **Slug**: `dispatch-parallel-protocol-safety`
> **Spec dir**: `specs/052-dispatch-parallel-protocol-safety/`
> **Predecessor**: Story #004(`LinearTurnEngine.dispatchParallel` 全并发 tool dispatch)+ Story #027a(`AnthropicLlmProvider.buildRequestBody` tool_use/tool_result pairing)+ Story #030(ask-mode AskUser 分支)+ Story #048(ask-user-sentinel upsert-by-toolUseId)
> **Diagnosis scope**: 0 行代码改动;Phase 0 验证性 instrumentation 决定 hypothesis 锁定 + fix 落点

---

## 1. WHY — 故障复现 + 根因 + 影响面

### 1.1 现象(2026-10-17 实测, demo-product SSE trace)

demo-product 配置 `agent.permission-policy: default` + `tools.bash: false`(关掉 built-in Bash,只留 demo-product 自定义 `bash_safe`)+ `tool-parallelism: 4`,用户在浏览器发"写一首关于秋天的古诗七言四联写到 poem.txt"。

观察 SSE trace:
- 9 个 ReAct steps 全部是 LLM `bash_safe` 调同一个 `/var/folders/.../lingshu-sessions/_default` 路径(典型 verification loop,与本 Story **无关** —— memory `feedback-engine-guard-vs-prompt-fix.md` 已覆盖)
- 第 11 步 LLM **同时**发出 **2 个** 并行 `bash_safe` tool_call:
  - `call_00_JhMlBmI5uMuJ7rbpbcuu0774`
  - `call_01_zPmxBHTM32uOscBQoIlx3651`
- SSE 事件流只见 `call_01` 的 `ToolStarted` + `ToolCompleted` + `ObservationAppended` 事件;**`call_00` 整个生命周期失踪**(ToolStarted / ToolCompleted / ObservationAppended 三个事件**都没有**)。
- Session 持久化正常(Story #046 trySave 生效,`b2e86bb5-1636-4cba-8559-83d698075d92.json` 1735 字节落地)。
- **下一轮 Anthropic POST 直接 HTTP 400**:
  ```
  messages.27.content.0: tool_use_id call_00_JhMlBmI5uMuJ7rbpbcuu0774 found in tool_result blocks.
  Each tool_result block must have a corresponding tool_use block in the previous message.
  ```

### 1.2 根因(3 层叠加,**本 Story 收口**)

**Anthropic API 协议层硬约束(不变项)**:
- 每个 `Message.Assistant.tool_use[id=X]` block **必须**在 history 后续 user message 里有匹配 `Message.ToolResult(toolUseId=X)` block
- 否则下一轮 Anthropic POST 报 HTTP 400「orphan tool_result」(`messages.X.content.Y: tool_use_id X found in tool_result blocks. Each tool_result block must have a corresponding tool_use block in the previous message`)
- Anthropic 协议允许多 tool_use 并行(同 1 个 assistant message 内多个 `tool_use` blocks),期望 engine 配对处理后 history 里 `Assistant.toolCalls.size() == ToolResult_blocks.size()`,**`toolUseId` 按顺序一一对应**

**Hypothesis A — `LinearTurnEngine.dispatchParallel` 协议漏 commit**(可能性最高,**待 Phase 0 验证**):
- `dispatchParallel` L484-546 在 `tool-parallelism: 4` 模式下用 `CompletableFuture.supplyAsync` + `Semaphore` 调度 N 并发 tool,LLM-return 顺序写 `results[]`
- L515-528 主循环:`for i in [0..N) { if cancelled before wait, results[i] = CANCELLED ToolResult; continue; } waitForTool(...) }` —— **但** `waitForTool` 返回 CANCELLED ToolResult 的路径(L555-609)**可能**在某些 race condition 下不返回(`futures[i].cancel(true)` + 紧接 `futures[i].get(thisSlice, ms)` 在 `CancellationException` 已经被异步传完之后的 window 内返回 `null`)
- 假设 `results[i]` 在 race condition 下**未被赋值**,Java 数组默认值 `null` → `ctx.appendToolResult((ToolResult)null)` 抛 NPE 或被静默吞掉 → `Assistant(tool_use[id=call_00, id=call_01])` 已写 history,但只有 `tool_result(call_01)` 落地 → **orphan tool_result(call_00)**
- **或者**:`waitForTool` 返回的 `ToolResult` 字段不完整(如 `toolUseId` 字段为 null 或与 `call.getId()` 不一致),导致 Anthropic parser 不能配对

**Hypothesis B — `AnthropicLlmProvider` parser 漏 parse**(待 Phase 0 验证):
- LLM 在 message 26 返回 2 个并行 `tool_use` block(`call_00` + `call_01`),`AnthropicLlmProvider.parseResponse` 只 parse 1 个 → `resp.getToolCalls().size() == 1` → L344 `ctx.appendAssistant(text, [call_01], usage)` → history 含 `Assistant(tool_use[id=call_01])` —— 但下一轮 prompt 拼回去时,**实际** history 含 `ToolResult(call_00)`(因为 SSE 落地了 call_00 的 ToolResult event — 但 message 26 只含 call_01 tool_use → orphan call_00 tool_result)
- **不成立的关键证据**:trace 显示 SSE 事件流只有 call_01,**没有** call_00 的任何 ToolStarted / ToolCompleted event —— 即 call_00 在 engine 内部就已经被吞了,**没有** ToolResult 落地。所以 protocol mismatch 来自 dispatchParallel 而非 Anthropic parser

**Hypothesis C — `DefaultTurnContext.appendToolResult` upsert-by-toolUseId 与并发 race**(待 Phase 0 验证):
- Story #048 引入 upsert-by-toolUseId 语义(L101-128):扫描 history tail 找同 toolUseId 的 ToolResult in-place 替换;否则 append
- 当 2 个并发 tool_call 几乎同时落地 result,主循环 L322-324 单线程顺序 `ctx.appendToolResult(results[i])`,正常应该不会 race
- **但**:`dispatchWithPolicy` AskUser 分支 L681-687 已经先 commit 1 个 sentinel `ToolResult`(同 toolUseId),然后 `toolCtx.approval().ask(...)` 阻塞;**当 2 个并行 tool 走 AskUser 时**,2 个 sentinel 都先 commit,后续 upsert 替换
- LLM 这一步是 `bash_safe` 不走 ask-mode(yml `permission-policy: default` 走 `AllowAllPermissionPolicy`),所以这条路径**不**适用于本 trace —— **Hypothesis C 不成立**

**实际锁定 hypothesis** —— 见 §1.3,大概率是 A 或 B 或 A+B 双层;Phase 0 instrumentation 决定。

### 1.3 影响面

- 任何 `tool-parallelism > 1` + LLM 在同一 turn 发 ≥ 2 个并行 tool_call 的场景都会撞(LLM 行为,**无法**消除,Anthropic 协议允许多并行 tool_call)
- 下一轮 Anthropic POST 100% HTTP 400,user 必须 reload 才恢复(reload 后**不会**重起 session 之前已失传)
- **R-04 privilege escalation / 协议完整性 缓解链路**:Story #027a(Anthropic 协议层)+ Story #030(ask-mode)+ Story #048(ask-user-sentinel)都已落,但 `dispatchParallel` 这一段协议配对保护**不在**覆盖范围内 —— 本 Story 收口

### 1.4 选型:为什么是 Fix A(Bug 3 fix pattern — 边界 sentinel)

| 选项 | 评 | 选 |
|---|---|---|
| A. **`dispatchParallel` 主循环 `results[i] = waitForTool(...)` 边界 sentinel**(推荐) | 主循环 `for i` 入口 + 出口都加 sentinel `ToolResult.error("[LINGS-XXX] dispatch interrupted: <reason>")`,**保证** `results[i]` 永不为 null + toolUseId 永不为 null + 永不为空 ToolResult。Phase 0 验证 hypothesis A 落地 | ✓ |
| B. **Anthropic parser robustness**(如果 Phase 0 锁定 B) | `AnthropicLlmProvider.parseResponse` 在解析 `tool_use` blocks 时如果 `id` 缺失,补 UUID;如果数量 mismatch,补 sentinel tool_use,确保 `Assistant.toolCalls.size() == 后续 ToolResult_blocks.size()` | (Phase 0 决定) |
| C. **`DefaultTurnContext.appendToolResult` 校验 toolUseId** | append 入口 `if (toolUseId == null || toolUseId.isEmpty()) throw new IllegalArgumentException(...)` —— 兜底,但不能阻止 protocol mismatch,因为 `Assistant(tool_use[id=call_00])` 已先 commit | ✗(太晚) |
| D. **整体 `dispatchParallel` 改顺序 dispatch** | 牺牲性能换 protocol safety,plan 复杂度高,scope 大 | ✗ |
| E. **新增 ErrorCode `LINGS-L04 PARALLEL_DISPATCH_RACE`**(如果新 sentinel 需要) | 复用现有 `ToolResult.error(...)` pattern,content 嵌 `[LINGS-L04]` 字符串前缀,镜像 Story #027a `LINGS-L01/L02` 模式 | ✓(Phase 0 决定) |

**Option A 实现要点**(对应 §3,若 Phase 0 锁定):
1. **`LinearTurnEngine.dispatchParallel` 主循环 L515-544 收口**:每个 `results[i]` 赋值路径(waitForTool / cancel-before-wait / catch RuntimeException)**强制覆盖**,无 null leak;`waitForTool` 返回 null 路径(`ToolResult = null`)补 throw IllegalStateException 转换。
2. **toolUseId 完整性校验**:每个 fallback ToolResult 必须**显式** set `toolUseId(calls.get(i).getId())`,不复用 LLM parse 出来的 `call.getId()` —— 防 Hypothesis A 第二种 case(toolUseId mismatch)。
3. **`Append-result 顺序**:LLM-return order 严格保留(L478-480 dsh §6.1 FR-003 契约),**不**按 completion order 重排 —— 但 Anthropic 协议要求 tool_use_id 按对应配对,所以只要 results[i].toolUseId 配 calls[i].getId(),顺序自由(Anthropic 不依赖顺序只依赖 id)。
4. **新增 ErrorCode(若 Phase 0 决定需要)**:`LINGS-L04 PARALLEL_DISPATCH_RACE`(LlmProvider 域 L 段 4 号,镜像 #027a `LINGS-L01/L02` 模式),用于 `results[i]` 兜底 sentinel 的 content 前缀。

**为什么 Option A 而不是 D**:D 牺牲并发性能且 scope 太大;A 是 minimal fix,只加固主循环边界 + 加 sentinel 兜底,scope 限于 `LinearTurnEngine.dispatchParallel` 单方法,不动其他路径。

---

## 2. WHO — 文件改动矩阵(budget: ≤ 5 核心文件改动,CLAUDE.md §11 #4)

**Phase 0(诊断 — **0 行 prod 改动**,纯 instrumentation)**:

| # | 文件 | 类型 | 改动 | 行数估计 |
|---|---|---|---|---|
| 0-1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | modify | `dispatchParallel` L484-546 + 主循环 L515-528 加 DEBUG 日志(`LOG.debug("dispatchParallel before-wait idx={} callId={} futureDone={}", ...)`)+ `LOG.debug("dispatchParallel after-wait idx={} callId={} toolUseId={} content.len={}", ...)` | +12 行 |
| 0-2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` | modify | `parseResponse` 入口加 DEBUG 日志(`LOG.debug("AnthropicLlmProvider.parseResponse tool_use_count={} firstId={}", ...)`) | +3 行 |
| 0-3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java` | modify | `appendAssistant` + `appendToolResult` 加 DEBUG 日志(打印 history.size() 变化 + toolCalls.size()/ToolResult.size()) | +6 行 |

**Phase 1(实施 — 假设 Phase 0 锁定 Option A)**:

| # | 文件 | 类型 | 改动 | 行数估计 |
|---|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | modify | `dispatchParallel` 主循环 L515-544 边界 sentinel(每个 results[i] 兜底)+ `waitForTool` 返回 null / toolUseId null 检查 + IllegalStateException 转换 | +35 / -8 行 |
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java` | modify | 加 `LINGS_L04 = "LINGS-L04"` 常量(若 Phase 0 决定需要新 ErrorCode)| +3 行 |
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | modify | `dispatchParallel` 兜底 sentinel content 嵌 `[LINGS-L04]` 前缀(若需要)| +5 行 |
| 4 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineDispatchParallelProtocolTest.java` | new | 6 L2:parallelTwoCalls_bothResultsPersisted / parallelOneCallRace_oneSentinelPersisted / parallelAskUser_sentinelStillUpserted / parallelCancelBeforeWait_sentinelPersisted / parallelTimeout_sentinelPersisted / parallelAllCancelled_protocolConsistent | ~250 行 |
| 5 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineProtocolInvariantIT.java` | new | 1 L3:完整 turn 跑 2 个并行 tool_call,断言 history 末尾 Assistant.toolCalls.size() == ToolResult_blocks.size() + 每个 toolUseId 都配对 | ~120 行 |

**prod 文件 = 2(LinearTurnEngine.java + LlmErrorCodes.java)/ test 文件 = 2 new / 总 = 4**。预算内。

**Phase 0 instrumentation 不计入 prod 改动**(纯 DEBUG 日志,实施期 ship 前回退)。

**R-13 mitigation (d) baseline 镜像** — `mvn -pl lingshu-core dependency:tree` pre/post diff 应仅时间戳差异 = **0 binary delta 第 34 次 PASS**(纯 JDK 8 `IllegalStateException` + `ToolResult.builder()` + Lombok `@Value` + SLF4J `LOG.warn` + 字符串 prefix `[LINGS-L04]` 已锁 13 项依赖表内,no new binary;`Thread.currentThread().interrupt()` + `CompletableFuture.supplyAsync` 已锁;**若**需要 debug log 用 SLF4J 已锁 0 新依赖)。

---

## 3. WHAT — 实现契约详情

### 3.1 Phase 0 验证步骤(spec ready 阶段只规划,实施期跑)

**目的**:确认 hypothesis A vs B vs A+B,**避免**实施期发现根因不在预期位置导致 patcharound。

**步骤**:
1. **Instrument LinearTurnEngine.java**:dispatchParallel L498-512 提交前 + L515-528 主循环入口 + L529-543 waitForTool 调用前后 + L530-543 catch 块,加 DEBUG 日志打印 `idx` / `callId` / `future.isDone()` / `results[idx]` 状态。
2. **Instrument AnthropicLlmProvider.parseResponse**:parse 完所有 `tool_use` blocks 后,DEBUG 日志打印 `parsed_count` + `first_id` + `last_id`。
3. **Instrument DefaultTurnContext.appendAssistant + appendToolResult**:DEBUG 日志打印 history.size() 变化前/后 + Assistant.toolCalls.size() / ToolResult_blocks.size()。
4. **复现 demo-product 11 步并行 tool_call 场景**:跑同一 query"写一首关于秋天的古诗七言四联写到 poem.txt"。
5. **log4j / slf4j 输出过滤**:看 DEBUG 日志,定位 orphan 产生的精确 line。
6. **决策**:
   - 若 `AnthropicLlmProvider.parseResponse` DEBUG 显示 parsed_count=2(2 个 tool_use 都 parse 成功)→ **Hypothesis B 不成立**,锁 A
   - 若 `AnthropicLlmProvider.parseResponse` DEBUG 显示 parsed_count=1(只 1 个 tool_use 被 parse)→ **Hypothesis B 成立**,需追加 fix Anthropic parser
   - 若 `dispatchParallel` DEBUG 显示 `results[i]` 边界有 null / toolUseId null → **Hypothesis A 成立**,需追加 fix dispatchParallel 主循环边界
   - 若两者都成立 → **Hypothesis A+B 双层**,需两处 fix
7. **回退 Phase 0 instrumentation**(git revert 或 Edit 删 DEBUG 日志),保留 LLM_ERROR_CODES.LINGS_L04 常量(若需要)。

### 3.2 `LinearTurnEngine.dispatchParallel` 主循环边界 sentinel(假设 Phase 0 锁 A)

**Before**(Story #005 改后,当前):
```java
ToolResult[] results = new ToolResult[calls.size()];
for (int i = 0; i < calls.size(); i++) {
    if (ctx.cancellation().isCancelled()) {
        futures[i].cancel(true);
        results[i] = ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId(calls.get(i).getId())
            .content("cancelled before tool dispatch")
            .isError(true)
            .build();
        continue;
    }
    try {
        results[i] = waitForTool(futures[i], ctx, timeoutSec, calls.get(i).getId());
    } catch (RuntimeException cancelHandled) {
        results[i] = ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId(calls.get(i).getId())
            .content("cancelled: " + cancelHandled.getMessage())
            .isError(true)
            .build();
    }
}
return results;
```

**After**(Story #052 改后):
```java
ToolResult[] results = new ToolResult[calls.size()];
for (int i = 0; i < calls.size(); i++) {
    final ToolCall currentCall = calls.get(i);
    final String expectedId = currentCall.getId();
    if (ctx.cancellation().isCancelled()) {
        futures[i].cancel(true);
        results[i] = dispatchCancelledResult(expectedId, "cancelled before tool dispatch");
        continue;
    }
    try {
        ToolResult r = waitForTool(futures[i], ctx, timeoutSec, expectedId);
        // 🆕 Story #052 — boundary sentinel: results[i] MUST be non-null with
        // matching toolUseId. If waitForTool returned null (programmer error in
        // a future SPI impl) or toolUseId mismatch (race in upstream), substitute
        // a sentinel ToolResult.error so the protocol invariant
        //   Assistant(tool_use[id=X]) + tool_result(id=X)
        // holds and the next-turn Anthropic POST doesn't 400.
        if (r == null) {
            LOG.warn("dispatchParallel: waitForTool returned null for callId={} — substituting sentinel",
                expectedId);
            r = dispatchCancelledResult(expectedId,
                "[" + LlmErrorCodes.LINGS_L04 + "] tool dispatch returned null");
        } else if (!expectedId.equals(r.getToolUseId())) {
            LOG.warn("dispatchParallel: toolUseId mismatch expected={} actual={} — substituting sentinel",
                expectedId, r.getToolUseId());
            r = dispatchCancelledResult(expectedId,
                "[" + LlmErrorCodes.LINGS_L04 + "] tool dispatch returned wrong toolUseId: " + r.getToolUseId());
        }
        results[i] = r;
    } catch (RuntimeException cancelHandled) {
        results[i] = dispatchCancelledResult(expectedId,
            "cancelled: " + cancelHandled.getMessage());
    }
}
return results;
```

**Key invariants**:
- 每个 `results[i]` 永不为 null
- 每个 `results[i].getToolUseId() == calls.get(i).getId()`
- 每个 `results[i]` 的 status 都是有效 enum(OK / ERROR)
- LLM-return order 保留(L478-480 dsh §6.1 FR-003)

### 3.3 `dispatchCancelledResult` helper(新增 private 静态方法)

```java
private static ToolResult dispatchCancelledResult(String toolUseId, String content) {
    return ToolResult.builder()
        .status(ToolResult.Status.ERROR)
        .toolUseId(toolUseId)
        .content(content)
        .isError(true)
        .build();
}
```

**理由**:消除 3 个 inline `ToolResult.builder()...` 重复(原 cancel-before-wait / catch / cancel-by-shutdown 各 1 个),收口 sentinel content 字段约束。

### 3.4 `waitForTool` 返回值契约加强

**Before**(Story #005):
```java
private ToolResult waitForTool(CompletableFuture<ToolResult> fut, TurnContext ctx,
                               int timeoutSec, String toolUseId) {
    ...
}
```
返回值:`ToolResult`(永不 null,所有 cancel / timeout / exception 路径都返回 `ToolResult.error(...)`)

**After**(Story #052 改后,加契约文档):
```java
/**
 * 🆕 Story #052 — contract hardening: this method MUST return a non-null
 * {@link ToolResult} whose {@code toolUseId} matches the {@code toolUseId}
 * argument, regardless of which exit path is taken (success / cancel /
 * timeout / interrupt / exception). The caller relies on this invariant to
 * maintain Anthropic protocol safety in {@link #dispatchParallel}.
 */
private ToolResult waitForTool(...) {
    ...  // 现有实现 + Javadoc
}
```

**不**改 `waitForTool` 实现本身,只补 Javadoc 契约 —— 因为 3 个新文件改动不预算,只 Javadoc + dispatchParallel 主循环边界 sentinel。

### 3.5 `LlmErrorCodes.LINGS_L04` 新增(若 Phase 0 决定)

```java
// 🆕 Story #052 — parallel tool dispatch protocol safety sentinel
public static final String LINGS_L04 = "LINGS-L04";
// LlmProvider 域 L 段 4 号;PARALLEL_DISPATCH_RACE — results[i] 边界 null / toolUseId mismatch
//   兜底 sentinel content 前缀,与 LINGS-L01/L02(Story #027a)同模式
//   唯一使用场景:LinearTurnEngine.dispatchParallel 主循环边界兜底
//   不属于协议层错(Anthropic 抛 400 才属协议层),属于 engine 内部 invariant 兜底
```

**L 段当前占用情况**(dsh §15.3):
- L01 `TOOL_USE_BLOCK_INVALID`(Story #027a)
- L02 `TOOL_RESULT_BLOCK_INVALID`(Story #027a)
- L03 reserved(Story #027b,留 §14 N6 graceful shutdown)
- **L04 新增**(Story #052)
- L05-L18 reserved(Story #027a + 后续 RFC)

**0 移位**(L05-L18 编号不变)。

---

## 4. AC — 验收标准(7 项跨 2 测试文件)

### 4.1 `LinearTurnEngineDispatchParallelProtocolTest` — 6 L2

| AC | 名称 | 断言 |
|---|---|---|
| AC-052-01 | parallelTwoCalls_bothResultsPersisted_protocolConsistent | mock `LlmProvider` 返回 2 个并行 `bash_safe` tool_use(`call_00` + `call_01`),跑完整 turn;断言 history 末尾 [assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00), tool_result(call_01)](`Assistant.toolCalls.size() == ToolResult_blocks.size() == 2`)|
| AC-052-02 | parallelOneCallRace_oneSentinelPersisted | mock `LlmProvider` 返回 2 个并行 tool_use,但 mock `DefaultToolExecutor.dispatch` 在第 2 个 call 上抛 `RuntimeException("synthetic race")`;断言 history 末尾 [assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, OK), tool_result(call_01, ERROR, content="[LINGS-L04] ...", isError=true)] —— **无 orphan** |
| AC-052-03 | parallelAskUser_sentinelStillUpserted | mock `AskUserPermissionPolicy` 命中;mock `DefaultApprovalGate.ask(...)` 返回 Allow;跑完整 turn;断言 history 末尾 [assistant(tool_use[id=X, id=Y]), tool_result(call_00, OK=real result), tool_result(call_01, OK=real result)] —— **sentinel 被 upsert 替换**(镜像 Story #048)|
| AC-052-04 | parallelCancelBeforeWait_sentinelPersisted | mock `CancellationToken.fire()` 在 `supplyAsync` 提交后 + `waitForTool` 之前触发;跑完整 turn;断言 history 末尾 [assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, ERROR="cancelled before tool dispatch"), tool_result(call_01, ERROR="cancelled before tool dispatch")] |
| AC-052-05 | parallelTimeout_sentinelPersisted | mock `DefaultToolExecutor.dispatch` 第 1 个 call 跑 6s,`tool-timeout: 5s`;跑完整 turn;断言 history 末尾 [assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, ERROR="tool timeout after 5s"), tool_result(call_01, OK=real result)] —— **timeout 不影响并行 dispatch 协议完整性** |
| AC-052-06 | parallelAllCancelled_protocolConsistent | mock `CancellationToken.fire()` 在第 1 个 call 主循环入口触发;跑完整 turn;断言 history 末尾 [assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, ERROR="cancelled ..."), tool_result(call_01, ERROR="cancelled ...")] —— **`results[i]` 兜底 sentinel 永不为 null** |

### 4.2 `LinearTurnEngineProtocolInvariantIT.java` — 1 L3

| AC | 名称 | 断言 |
|---|---|---|
| AC-052-07 | e2e_parallelDispatch_historyAnthropicProtocolInvariant | L3 集成:mock Anthropic HttpServer 接 POST + 验证 request body 的 messages[];跑完整 turn 含 2 个并行 bash_safe tool_call;turn 完成后模拟"下一轮 prompt"(再次调 `promptBuilder.build(ctx)`),断言构造出的 `Prompt.getMessages()` 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00), tool_result(call_01)]` —— **无 orphan tool_result**,**无** unmatched tool_use |

### 4.3 工程基线 AC

| AC | 名称 | 断言 |
|---|---|---|
| AC-052-08 | banned_dependencies_clean | `mvn -pl lingshu-core dependency:tree` pre/post md5sum diff 仅时间戳差异(R-13 mitigation (d) baseline 镜像第 34 次 PASS)|
| AC-052-09 | zero_new_error_code_or_lings_l04 | 若 Phase 0 锁 A 且需要新 ErrorCode → +LINGS-L04;否则 0 新 ErrorCode |
| AC-052-10 | zero_new_maven_dependency | 0 新 Maven dep;`IllegalStateException` + `ToolResult.builder()` + SLF4J `LOG.warn` + 字符串 prefix 已锁 13 项依赖表内 |
| AC-052-11 | full_test_suite_no_regression | `mvn -pl lingshu-core test` 全部 780+ tests pass / 0 fail(累计测试基数由 #049 落地结果定;若 #049 改动后基数变化,以 #049 commit 后的基线为准)|
| AC-052-12 | reverse_ac_dispatchParallel_invariant | 既有 `LinearTurnEngineCancellationTest`(Story #005 18 case)+ `LinearTurnEngineMaxStepsTest`(Story #008)+ `LinearTurnEngineAskUserTest`(Story #030 + #041 + #048)+ `LinearTurnEngineCompactionTest`(Story #045)+ `LinearTurnEngineSessionStoreTest`(Story #046)+ `DefaultAgentLiveStreamingTest`(Story #049)全部 0 改动全过 |

### 4.4 Reverse AC 适配性(risk 防护)

- **R-RAC-1** 反例:边界 sentinel 加在主循环出口,但 `waitForTool` 内部抛 Throwable(非 RuntimeException)→ 漏掉 catch → Javadoc 契约 §3.4 要求 `waitForTool` 永返回非 null ToolResult,异常全部 catch 转 sentinel
- **R-RAC-2** 反例:toolUseId mismatch 但 toolUseId 字段为 null → 主循环 L530-543 `expectedId.equals(r.getToolUseId())` NPE → **若** r.getToolUseId() null,改 `!Objects.equals(expectedId, r.getToolUseId())`(JDK 8 `java.util.Objects.equals`,已锁);AC-050-02 + R-RAC-1 守住
- **R-RAC-3** 反例:`results[i]` 兜底 sentinel 写入但 LLM-return order 错乱 → dsh §6.1 FR-003 守住(L478-480 JavaDoc 显式说"preserves LLM-return order, not completion order"),boundary sentinel 只是 `content` 字段兜底,**不**改 results[i] 对应 calls[i] 的位置
- **R-RAC-4** 反例:parallel dispatch 全部 cancelled,history 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, ERROR), tool_result(call_01, ERROR)]` —— 下一轮 Anthropic POST 报 "tool_use id=X found without tool_result" **不成立**,因为 ERROR tool_result 也是 valid tool_result block(Anthropic 协议层不区分 OK/ERROR),AC-050-06 守住

---

## 5. 关键不变项(Key Invariants — 不动契约)

| 不变项 | Story 中状态 |
|---|---|
| `LlmProvider` SPI | **0 改动**(除非 Phase 0 锁 B 才改 `AnthropicLlmProvider.parseResponse`)|
| `AnthropicLlmProvider.parseResponse` 契约 | **0 改动**(除非 Phase 0 锁 B)|
| `ToolExecutor.dispatch()` 5 步流水线 | **0 改动**(§4.10.1 硬规则 2)|
| `Message` 4 子类契约 | **0 改动**(System / User / Assistant / ToolResult)|
| `Message.Assistant` 4-arg ctor + `toolCalls` 嵌入模式 | **0 改动**(Story #027a)|
| `Message.ToolResult` 3-arg ctor | **0 改动**|
| `LinearTurnEngine.runTurn` 公开方法签名 | **0 改动**|
| `LinearTurnEngine.dispatchParallel` 公开方法签名 | **0 改动**(只主循环内部加 boundary sentinel)|
| `LinearTurnEngine.waitForTool` 实现 | **0 改动**(只 Javadoc 契约文档化)|
| `LinearTurnEngine.dispatchWithPolicy` | **0 改动**|
| `LinearTurnEngine.trySave` 收口点 | **0 改动**(Story #046)|
| `ToolResult.builder()` API | **0 改动**(复用)|
| `PermissionPolicy.check()` AskUser 5 段决策 | **0 改动**(Story #030)|
| `DefaultTurnContext.appendToolResult` upsert-by-toolUseId | **0 改动**(Story #048)|
| `DefaultApprovalGate` SPI | **0 改动**(Story #041)|
| `ApprovalRegistry` @Component | **0 改动**(Story #030)|
| `LlmErrorCodes` 已有 11 条 | **0 移位**(L05-L18 编号不变;只 L04 新增,若需要)|
| `AgentConfig` 不可变契约 | **0 改动**(`tool-parallelism` 字段 0 改动)|
| `AgentFactory` SPI | **0 改动**(@Autowired 9-Router ctor 不动)|
| 9 Slot 体系 | **0 改动**|
| JDK 8 兼容 | **保持**(`IllegalStateException` + `Objects.equals` + `ToolResult.builder()` + SLF4J 已锁 13 项依赖表内,no `var` / `List.of` / sealed / records)|

**Spring AI `ChatClient.tools().call()` 仍禁止** — §4.10.1 硬规则 2 守住。

**ReAct Loop 自实现不变** — §4.10.1 硬规则 1 守住。

**LLM verification loop 行为不属本 Story** —— memory `feedback-engine-guard-vs-prompt-fix.md` 显式覆盖 verification loop 类行为;本 Story 只修 protocol mismatch,不修 LLM 重复调同工具的语义行为。

---

## 6. 风险 / Mitigation

| 风险 | 严重度 | 触发条件 | Mitigation |
|---|---|---|---|
| Phase 0 instrumentation 误删 DEBUG 日志 ship 进 main | 2(低)| 实施期忘删 | tasks.md Phase 1 task-1 显式 list "删除所有 Phase 0 DEBUG 日志";git diff pre-commit 必查 |
| Hypothesis B 同时成立导致单修 A 仍残留 | 4(中)| `AnthropicLlmProvider.parseResponse` 漏 parse 部分 tool_use | Phase 0 instrumentation 显式区分 hypothesis;若 A+B 双层则 Phase 1 同步修 |
| Boundary sentinel content 误导 LLM(显示 "[LINGS-L04]" 字眼)| 1(低)| LLM 把 sentinel 当 task 结果 | content 文本明确 `[" + LINGS_L04 + "] tool dispatch returned null`;LLM 端 `isError=true` 视觉强标记 |
| `waitForTool` 改后行为破坏(若实施期发现真需改 waitForTool)| 3(中)| Phase 0 发现 waitForTool 内部某路径返回 null | boundary sentinel 在调用方兜底,waitForTool 不动 |
| 性能影响(boundary sentinel 加 logs + equals check)| 1(低)| 主循环每个 call 多 2-3 次 cheap 操作 | `LOG.warn` 仅异常路径触发;`equals` O(string) 微不足道 |
| LlmErrorCodes.LINGS_L04 命名冲突(若 v0.5.x 后期定义过)| 2(低)| RFC 文档化未跟上 | 实施前 grep dsh §15.3 L 段 + 实施期 mirror `LlmErrorCodes.LINGS_L01/L02/L03` 命名风格 |

---

## 7. 提交约定

**Phase 0 + Phase 1 一起 git commit**(用户拍板「实施吧」后,2 阶段合并提交避免 instrumentation 在 main branch 滞留)。

**commit message 模板**:
```
feat(agent): Story #052 dispatch-parallel-protocol-safety

Phase 0 — instrumentation logs to lock root-cause hypothesis A vs B.
Phase 1 — boundary sentinel in dispatchParallel main loop: results[i]
MUST be non-null with matching toolUseId regardless of cancel/timeout/
race path. Prevents Anthropic HTTP 400 "tool_use_id X found in tool_result
blocks: Each tool_result block must have a corresponding tool_use block
in the previous message" when LLM emits parallel tool_use calls.

R-13 mitigation (d) baseline mirror: 34th pass, 0 binary delta.
Test sum: 7XX+ tests pass / 0 fail.
```

**PR body 必须含** `### R-13 dependency:tree 自查` 节(per CLAUDE.md §11 #6)。

---

## 8. 关联参考

- **Story #004**(`LinearTurnEngine.dispatchParallel` 全并发 tool dispatch,Semaphore-bound + LLM-return order)
- **Story #005**(CancellationToken + 200ms polling + `waitForTool` 边界)
- **Story #008**(`react-max-steps` 守卫发射 — 不与本 Story 冲突,边界独立)
- **Story #027a**(`AnthropicLlmProvider.buildRequestBody` tool_use/tool_result pairing — Phase 0 hypothesis B 检查点)
- **Story #027b**(AnthropicLlmProvider SSE 流式 — 不与本 Story 冲突)
- **Story #030**(ask-mode AskUser 5 段决策 — AC-050-03 检查点)
- **Story #048**(ask-user-sentinel + `appendToolResult` upsert-by-toolUseId — AC-050-03 检查点 + 镜像 pattern)
- **dsh §4.10.1** 硬规则 1(ReAct Loop 自实现)+ 硬规则 2(Spring AI `ChatClient.tools().call()` 禁止)
- **dsh §6.1** LinearTurnEngine + FR-003(LLM-return order preservation)
- **dsh §15.3** L 段 ErrorCode 编码约定
- **anthropic-sdk-protocol** `tool_use` / `tool_result` pairing constraint
- **CLAUDE.md** §11 #4 Story 边界、`§11 #6` dep-tree 自查、`§11 #2` AC 黑盒验证
