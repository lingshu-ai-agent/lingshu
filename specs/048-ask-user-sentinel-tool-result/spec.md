# Story #048 — ask-user-sentinel-tool-result

> **Status**: spec ready(2026-10-14)
> **Slug**: `ask-user-sentinel-tool-result`
> **Spec dir**: `specs/048-ask-user-sentinel-tool-result/`
> **Predecessor**: Story #030(ask-policy wiring)+ Story #041(ApprovalGate SPI 抽离)+ Story #047(ask-policy end-to-end wiring — **与本 Story 一起 commit,不互相 commit**)
> **Implementation pairing**: 与 Story #047 顺序无关,但 git commit 上两 Story 必须**一起提交**(用户 2026-10-14 明确指令)

---

## 1. WHY — 故障复现 + 根因 + 影响面

### 1.1 现象(2026-10-14 实测, demo-product 浏览器复现)

demo-product 配置 `permission-policy: ask` + `tools.ask-list: [bash_safe]` + `tool-parallelism: 4`,用户在浏览器发 "list current directory"。
观察:浏览器 SSE 显示 `ApprovalRequired` event,但 Allow/Deny 按钮**没及时渲染**(demo-product 前端 Lazy-load,或者 React 状态被 user 操作冲掉),前端实际从未发 `POST /api/approvals`。
但**聊天窗口用户接着发了第二句话** —— 此时服务端按 ReAct 流程跑第二轮 turn,`AnthropicLlmProvider.buildRequestBody` 序列化整个 history 给 Anthropic,Anthropic HTTP 400:

```
messages.22: tool_use ids were found without tool_result blocks immediately after:
call_00_lrAJHuIkPN1JnQfLPwkx0300
```

### 1.2 根因(4 层叠加,本 Story 只修 #3)

1. **Anthropic API 协议层硬约束**:每个 `tool_use` block **必须**紧跟一个 `tool_result` block,否则协议报错。这是**协议层**,不可改。
2. **`LinearTurnEngine.runTurn` L308 `appendAssistant`** 把 LLM 返回的 assistant message(含 `tool_use` blocks)**先**写入 history,**后** dispatch tool。
3. **`dispatchWithPolicy` AskUser 分支(L620-660)** 在 `toolCtx.approval().ask(...)` 上**阻塞**,等候 user 决议。
   - 当 `approvalTimeoutSeconds = 0`(Story #030 默认值 — Claude Code overnight parity)→ **永不超时**
   - 前端没渲染按钮或没发 POST → `CompletableFuture<Decision>` 永不 complete
   - history 留下 unmatched `tool_use` blocks,L308 已落地,无法回退
4. **前端渲染问题**(Story #042 部分覆盖 + 实际还有 reload / network 抖动路径未覆盖) → 不在本 Story 范围。

**`tool-parallelism: 4` 是否根因**: **不是**,只是放大器。cl=1 也会撞,只是单 step 卡死。cl=4 时,如果 4 个并行 tool 里有 1 个走 AskUser,**该 step** 整组 dispatch 都会卡在 `CompletableFuture.allOf`,其余 tool 也被覆写。

### 1.3 影响面

- 任何 ask-mode + `approvalTimeoutSeconds=0`(默认)+ 用户在 turn 内未及时决定的场景,都会把 history 拖入不一致状态。
- next turn 直接 HTTP 400,user 必须 reload 才恢复(reload 重起 session 之前已失传)。
- **R-04 privilege escalation 缓解链路完整闭环 Story #042 demo-product UI 完成后仍受 Story #030 埋的这个 hang bug 影响**。Story #030 release notes 已自陈:「前端 SSE 同意变 permanent hang 风险」,本 Story 收口。

### 1.4 选型:为什么是 Sentinel + Upsert(option D)

| 选项 | 评 | 选 |
|---|---|---|
| A. UI 修(按钮必渲染) | 不解决 reload / 网络抖动场景;且 user 已实测 reload 路径会复现 | ✗ |
| B. `appendAssistant` 时不写 `tool_use` blocks | 破坏 Anthropic API round-trip(同 turn 必须 echo `tool_use`),后患无穷 | ✗ |
| C. AskUser 后**回滚** assistant message | history 不一致的事后弥补,但 assistant message 可能已有 text content;并发 race 复杂 | ✗ |
| D. **AskUser 前 commit 一个 sentinel tool_result,后续 main loop 走 upsert 覆盖** | 协议层始终一致;race 在 main loop 单线程顺序写入,无并发问题 | ✓ |
| E. 把 `approvalTimeoutSeconds` 默认改成 30s | 破坏 Story #030 overnight parity 的设计意图;且 user 决定"过了 30s 仍断开了,审批也许还在犹豫"还是丢了 | ✗ |

**Option D 实现要点**(对应 §3):
1. **`DefaultTurnContext.appendToolResult`** 语义从「append-by-position」改成「upsert-by-toolUseId」:扫 `history` 末尾到上一个 `Message.Assistant` 之间,若已有同 `toolUseId` 的 `Message.ToolResult` 则 in-place 替换;否则 append 到末尾。
2. **`LinearTurnEngine.dispatchWithPolicy` AskUser 分支**(L620-660)在 `toolCtx.approval().ask(...)` 阻塞之前,先 `ctx.appendToolResult(sentinel)` 写入一个标记为 `[PENDING_APPROVAL]` 的 sentinel,锁住 `toolUseId` 占位。允许 → `toolExecutor.dispatch` → main loop L322-324 已有的 `ctx.appendToolResult(realResult)` 自然 upsert 覆盖。拒绝 → `ToolResult.error(reason)` 同样 upsert 覆盖。timeout → `[LINGS-P02]` 错误 ToolResult upsert 覆盖。
3. `Session` SPI / `DefaultSession.append` 不变 — upsert 语义收敛在 `DefaultTurnContext.appendToolResult` 单点。`History` 用同一把 `synchronized (history)` lock 与现有 append 安全共存。

**为什么 `DefaultTurnContext` 而不是 `DefaultSession.append`**:`Session` SPI 是公共接口(Story #014 + #046 复用,`SessionStore` 也是 Java side abstract),加 upsert 写公共接口会污染 narrative;`DefaultTurnContext` 是 engine 侧私有的 history-mutation 入口(L188 / L202 / L213),upsert 在这里收敛 — 变更面最小、保留 `Message` 4 子类契约不变、`DefaultSession.append` 不动。

---

## 2. WHO — 文件改动矩阵(budget: ≤ 5 核心文件改动,CLAUDE.md §11 #4)

| # | 文件 | 类型 | 改动 | 行数估计 |
|---|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java` | modify | `appendToolResult` Javadoc 更新语义说明(upsert-by-toolUseId)| +12 行 |
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java` | modify | `appendToolResult` 实现增加 upsert-by-toolUseId 替换逻辑 | +20 / -3 行 |
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | modify | `dispatchWithPolicy` AskUser 分支加 sentinel 提交(阻塞前),sentinel 内容为 `[PENDING_APPROVAL] Awaiting user decision...` | +18 / -2 行 |
| 4 | `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultTurnContextUpsertTest.java` | new | 3 L1:append-same-id-replaces / append-different-id-appends / append-multiple-same-id-keeps-last | ~80 行 |
| 5 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserTest.java` | modify | 追加 4 L2:allow-upsert / deny-upsert / timeout-upsert / never-resolve-sentinel-protocol-layer-consistent | +120 / -0 行 |
| 6 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserNoRenderIT.java` | new | 1 L3:AskUser 永不 complete 时 history 仍 protocol-consistent(无 Anthropic HTTP 400 触发)| ~90 行 |

**prod 文件 = 3 / test 文件 = 3(2 new + 1 modify)/ 总 = 6**。预算内(剔除 TurnContext Javadoc 单语义注解不计入 实质改动,prod 实质 = 2:`DefaultTurnContext.java` + `LinearTurnEngine.java`)。

**R-13 mitigation (d) baseline 镜像** — `mvn -pl lingshu-core dependency:tree` pre/post diff 应仅时间戳差异 = **0 binary delta 第 31 次 PASS**(纯 JDK 8 `Message.ToolResult(toolUseId, content, isError)` 复用 + Java `Collections.reverseOrder` + `ListIterator` 已锁 + `synchronized (history)` 已锁 + Lombok `@Value` + Spring `@Component` + `ToolResult.builder()` 复用,no new binary)。

---

## 3. WHAT — 实现契约详情

### 3.1 `TurnContext.appendToolResult` 语义变更

**Before**(Story #001 起一直):
> Append a tool result message to history.

**After**(Story #048 改后):
> Append a tool result message to history, with upsert-by-`toolUseId` semantics:
> if a {@link Message.ToolResult} with the same {@code toolUseId} already
> exists between the most recent {@link Message.Assistant} (inclusive of it)
> and the end of the history tail, replace it in place; otherwise append at the
> end. Replacement is safe because Anthropic protocol requires unique
> {@code tool_use.id} within a single assistant turn — the upsert preserves the
> position required for Anthropic's tool_use/tool_result pairing.

> Scope of upsert is bounded by the most recent assistant message: stale
> tool results from prior turns are never touched.

### 3.2 `DefaultTurnContext.appendToolResult` 实现替换方案

```java
@Override
public void appendToolResult(ToolResult result) {
    if (session instanceof DefaultSession) {
        Message.ToolResult tr = new Message.ToolResult(result.getToolUseId(),
            result.getContent(), result.isError());
        DefaultSession ds = (DefaultSession) session;
        // 🆕 Story #048 — upsert-by-toolUseId semantics.
        // Scan history tail (from end) up to the most recent Assistant
        // message. If a ToolResult with the same toolUseId is found, replace
        // in place. Otherwise append at end.
        synchronized (ds.appendLock()) {
            List<Message> hist = ds.historyMut();
            int lastAssistantIdx = -1;
            for (int i = hist.size() - 1; i >= 0; i--) {
                if (hist.get(i) instanceof Message.Assistant) { lastAssistantIdx = i; break; }
            }
            int searchStart = lastAssistantIdx;
            for (int i = hist.size() - 1; i > searchStart; i--) {
                Message m = hist.get(i);
                if (m instanceof Message.ToolResult
                    && ((Message.ToolResult) m).getToolUseId().equals(tr.getToolUseId())) {
                    hist.set(i, tr);  // upsert
                    return;
                }
            }
            hist.add(tr);  // append
        }
    }
}
```

**API 变更细节**(具体实现期可微调,但语义对):
- `DefaultSession` 暴露一个 `appendLock()` 给 impl-only 加锁(类似 `#028 runtimeSandbox()` 的 exposing pattern),**不**改 `Session` 公共 SPI。
- 历史扫描在 `synchronized (history)` 同一个锁下进行,与现有 `append(Message)` / `compact(List)` 不竞争。
- `Message.ToolResult.getToolUseId()` getter 由现有 Lombok `@Value` 自动生成,不需要新方法。

### 3.3 `LinearTurnEngine.dispatchWithPolicy` AskUser 分支 sentinel 提交

变更位置:L620-660 整个 `if (d instanceof Decision.AskUser)` 块。

**Before**(Story #041 改后):
```java
if (d instanceof Decision.AskUser) {
    Decision resolved = toolCtx.approval().ask((Decision.AskUser) d);
    if (resolved instanceof Decision.Allow) {
        return toolExecutor.dispatch(call, toolCtx);
    }
    if (resolved instanceof Decision.Deny) {
        return ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId(call.getId())
            .content(((Decision.Deny) resolved).getReason())
            ...
    }
    ...   // recursion guard
}
```

**After**(Story #048 在阻塞前 commit sentinel):
```java
if (d instanceof Decision.AskUser) {
    // 🆕 Story #048 — sentinel commit: keep history protocol-consistent
    // even when approval never resolves (frontend never renders the button,
    // or shutdown hook fires mid-flight). Anthropic requires every tool_use
    // block to be matched by a tool_result block; without the sentinel,
    // long-hanging turns poison the history for the next request.
    ToolResult sentinel = ToolResult.builder()
        .status(ToolResult.Status.OK)
        .toolUseId(call.getId())
        .content("[PENDING_APPROVAL] Awaiting user decision for tool: "
                 + call.getName())
        .isError(false)
        .build();
    ctx.appendToolResult(sentinel);  // upsert-friendly; main loop will replace

    Decision resolved = toolCtx.approval().ask((Decision.AskUser) d);
    if (resolved instanceof Decision.Allow) {
        return toolExecutor.dispatch(call, toolCtx);  // main loop upsert
    }
    if (resolved instanceof Decision.Deny) {
        return ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .toolUseId(call.getId())  // same id → upsert replaces sentinel
            .content(((Decision.Deny) resolved).getReason())
            .isError(true)
            .build();
    }
    // resolved instanceof Decision.AskUser — recursion cap (dsh §9.3)
    return ToolResult.builder()
        .status(ToolResult.Status.ERROR)
        .toolUseId(call.getId())  // same id → upsert replaces sentinel
        .content("[" + PermissionErrorCodes.LINGS_P02 + "] AskUser recursion limited (max 3 retries)")
        .isError(true)
        .build();
}
```

**Key 字段契约**:
- `toolUseId = call.getId()` — 必须与 dispatchParallel 等价
- `status = OK` — sentinel 不是 error,是 pending
- `isError = false` — Anthropic 不看作失败
- `content = "[PENDING_APPROVAL] Awaiting user decision for tool: <name>"` — 对 LLM 透明可读,且未来 SessionStore 回放时 `load(checkpoint)` 也认得此标记做审计

**main loop L322-324 不变**(`for (int i = 0; i < results.length; i++) { ctx.appendToolResult(results[i]); }`),后续 upsert 由 #3.1 实现的语义自然生效。

### 3.4 Sentinel 与并发

- Story #004 `dispatchParallel` 用 `CompletableFuture.supplyAsync` + `Semaphore` 调度 N 并发 tool
- 每个并发分支独立 `dispatchWithPolicy` → 各自 commit sentinel(同一 `toolUseId` 互不冲突,Anthropic 要求 single turn 内 `tool_use.id` 全局唯一)
- main loop L322-324 单线程顺序 `ctx.appendToolResult(results[i])` → 各自 upsert
- `synchronized (history)` 同一把锁,与 `dispatchParallel` 并发写无 race

---

## 4. AC — 验收标准(13 项跨 3 测试文件)

### 4.1 `DefaultTurnContextUpsertTest` — 3 L1

| AC | 名称 | 断言 |
|---|---|---|
| AC-048-01 | upsert_sameToolUseId_replacesExisting | 创建 history 含 [A, TR(id=X, "a"), B],调用 `ctx.appendToolResult(TR(id=X, "b"))`,断言 history == [A, TR(id=X, "b"), B](in-place 替换,长度不变,位置不变) |
| AC-048-02 | upsert_differentToolUseId_appendsNormally | 创建 history 含 [A, TR(id=X, "a")],调用 `ctx.appendToolResult(TR(id=Y, "b"))`,断言 history == [A, TR(id=X, "a"), TR(id=Y, "b")](append 不变)|
| AC-048-03 | upsert_scopedToMostRecentAssistantMessage | 创建 history 含 [TR(id=X, "old")**(跨 assistant turn), A, TR(id=X, "first")],调用 `ctx.appendToolResult(TR(id=X, "second"))`,断言 history == [..., A, TR(id=X, "second")](**只** replace 当前 turn 的 TR,不动 prior turn 的)|

### 4.2 `LinearTurnEngineAskUserTest` — 4 L2(追加到现有 3 L2 后)

| AC | 名称 | 断言 |
|---|---|---|
| AC-048-04 | askUser_allow_resolvesToRealResult_upsertsSentinel | mock `ApprovalRegistry` + 让 `DefaultApprovalGate.ask(...)` 返回 Allow;跑完整 turn;断言 history 末尾 [assistant(tool_use[id=X]), tool_result(content=real, id=X)](**没有** sentinel;**没有** 2 条 TR;upsert 生效)|
| AC-048-05 | askUser_deny_resolvesToDenyResult_upsertsSentinel | 同上但 `Decision.Deny(reason="user said no")`;断言 history 末尾 [assistant(tool_use[id=X]), tool_result(content=[LINGS-P02] user said no or just the reason, isError=true, id=X)] |
| AC-048-06 | askUser_timeout_resolvesToLingsP02_upsertsSentinel | 同上但 mock ask(...) timeout 抛 `[LINGS-P02]`;断言 history 末尾 [assistant(tool_use[id=X]), tool_result(content=[LINGS-P02], isError=true, id=X)] |
| AC-048-07 | askUser_neverResolved_turnStillCompletes_historyProtocolConsistent | mock ask(...) 永远阻塞(用 `CountDownLatch` 不 release,测试 case 在 500ms 内跑完 turn 然后 cancel);断言 turn 完成后 history 末尾 [assistant(tool_use[id=X]), tool_result(content=[PENDING_APPROVAL] Awaiting user decision..., isError=false, id=X)] — **protocol-consistent**(无 unmatched tool_use)|

### 4.3 `LinearTurnEngineAskUserNoRenderIT.java` — 1 L3(新文件)

| AC | 名称 | 断言 |
|---|---|---|
| AC-048-08 | e2e_frontendNeverRendersButton_historyConsistentNoAnthropic400 | L3 集成(mock Anthropic HttpServer 接 POST + 验证 request body 的 messages[],无 unmapped tool_use),跑完整 turn;mock ask(...) 永不 resolve(`CompletableFuture` never complete);turn 超时完成后,断言 mock Anthropic 收到的下一轮(如果有)request body 的 messages[] 仍 protocol-consistent(每个 tool_use 都有匹配 tool_result),**绝无** `messages.X: tool_use ids were found without tool_result blocks` 触发 |

### 4.4 工程基线 AC

| AC | 名称 | 断言 |
|---|---|---|
| AC-048-09 | banned_dependencies_clean | `mvn -pl lingshu-core dependency:tree` pre/post md5sum diff 仅时间戳差异(R-13 mitigation (d) baseline 镜像第 31 次 PASS)|
| AC-048-10 | zero_new_error_code | 不引入新 `LINGS-*` ErrorCode;复用 `LINGS-P02`(Story #030 已落)|
| AC-048-11 | zero_new_maven_dependency | 0 新 Maven dep;`Message.ToolResult` + `synchronized` + Lombok `@Value` + `ToolResult.builder()` 全部已锁 |
| AC-048-12 | full_test_suite_no_regression | `mvn -pl lingshu-core test` 全部 761+ tests pass / 0 fail(累计测试基数由 #047 落地结果定;若 #047 改动后基数变化,以 #047 commit 后的基线为准)|
| AC-048-13 | reverse_ac_askUser_path_invariant | 既有 3 L2 `LinearTurnEngineAskUserTest`(Story #030 + #041)0 改动全过;`AskUserPermissionPolicyTest` 5 L1、`ApprovalRegistryTest` 4 L1 0 改动全过 |

### 4.5 Reverse AC 适配性(risk 防护)

- **R-RAC-1** 反例:replacement 边界误判 → 历史 assistant 的 tool_result 也被替换了 → AC-048-03 守住(prior-turn TR 不被覆盖)
- **R-RAC-2** 反例:Allow 之后 tool 抛异常 → `[LINGS-Txx]` ToolResult 仍 upsert(sentinel → 真实 error),无重复写 — §1.3 影响面不扩大
- **R-RAC-3** 反例:并发 dispatchParallel + AskUser + sentinel clashing → `toolUseId` 单 assistant-turn 唯一(Anthropic 约束),clash 不会发生 — 12 测试 case 路径覆盖

---

## 5. 关键不变项(Key Invariants — 不动契约)

| 不变项 | Story 中状态 |
|---|---|
| `Session` SPI | **0 改动**(`id` / `history` / `fork` / `checkpoint` / `compact` 公共方法签名不变;upsert 在 `DefaultTurnContext` 单点收敛)|
| `DefaultSession.append(Message)` | **0 改动**(append 语义保留 — ScopedRoleTest 等既有调用点 0 影响)|
| `Message` 4 子类契约 | **0 改动**(System / User / Assistant / ToolResult 字段不变;Story #041 已删 `ToolUse` 死代码)|
| `Message.ToolResult(toolUseId, content, isError)` ctor | **0 改动**(Lombok `@Value` 3 字段;getter 自动生成供 DefaultTurnContext 用)|
| `LinearTurnEngine.runTurn` 公开方法签名 | **0 改动**(只 `dispatchWithPolicy` 内部 askUser 分支加 sentinel commit)|
| `LinearTurnEngine.trySave` 收口点 | **0 改动**(Story #046 已落)|
| `approvalTimeoutSeconds=0` 默认 = overnight parity | **0 改动**(Story #030 默认值保留;**不**为「修 hang」破坏 overnight parity)|
| `LINGS-P02 PERMISSION_APPROVAL_TIMEOUT` | **复用** — Story #048 0 新 ErrorCode |
| `Message.Assistant` 4-arg ctor + `toolCalls` 嵌入模式 | **0 改动**(Story #027a 已落)|
| `ToolExecutor.dispatch()` 5 步流水线 | **0 改动**(§4.10.1 硬规则 2)|
| `PermissionPolicy.check()` AskUser 5 段决策 | **0 改动**(`AskUserPermissionPolicy` 实现层 0 修改;`DefaultApprovalGate.ask()` 0 修改)|
| `ToolRegistry` + `ToolExecutor` + 9 Slot SPI | **0 改动** |
| `DefaultAgent` / `AgentFactory` SPI | **0 改动**(`AgentFactory.create()` 入口不变)|
| `ToolResult.builder()` API | **0 改动**(复用 Story #027a 已存在的 builder)|
| JDK 8 兼容 | **保持**(`List<Message>` + `Message.ToolResult` 字段 + `synchronized (history)` 全 JDK 8 内置,no `var` / `List.of` / sealed / records)|

**Spring AI `ChatClient.tools().call()` 仍禁止** — §4.10.1 硬规则 2 守住。

**ReAct Loop 自实现不变** — §4.10.1 硬规则 1 守住。

---

## 6. 风险 / Mitigation

| 风险 | 严重度 | 触发条件 | Mitigation |
|---|---|---|---|
| `Message.ToolResult` upsert 跨 turn 误覆盖 prior-turn TR | 5(高)| assistant 与 TR 不同 toolUseId 时位置搜索逻辑错 | AC-048-03 显式断言 prior-turn TR 不变 |
| 并发 dispatchParallel 与 main loop L322-324 竞争 upsert 与 sentinel | 3(中)| 多 AskUser 并发 + history 锁未对齐 | `synchronized (ds.appendLock())` 与现有 append/compact 同一锁 |
| Sentinel content 误导 LLM(显示 "PENDING" 字眼)| 1(低)| LLM 把 "[PENDING_APPROVAL]" 当 task 描述解码 | content 文本明确 `Awaiting user decision for tool: <name>`,LLM 端不透传(`isError=false` + `tool_use_id` 仍指向真实 tool)|
| Sentinel 写入 vs `trySave(ctx)` 时序 | 2(低)| Story #046 落 `trySave` 后,如果 sentinel 没被覆盖 → 下次启动 history 含 sentinel | sentinel content 是 self-describing,后续 ApprovalRegistry 失效时人工可识别;**不**为 sentinel 另加清理机制 |
| 反向回退:Story #030 user 把 default 改回 small int(30s 等)→ sentinel 写得频繁 | 1(低)| 用户主动改 yml | 不主动改,Story #030 设计意图保留;user 自负责 |

---

## 7. 提交约定

**与 Story #047 一起 git commit**(用户 2026-10-14 明确指令)。

**Story #047 + #048 合并提交 commit message 模板**:

```
feat(agent): Story #047 #048 ask-policy end-to-end wiring + AskUser sentinel

Story #047 — Bug A (AgentFactory typo) + Bug B (demo-product ChatController bypasses yml).
Makes ask-mode trigger end-to-end through @Value binding + correct top-level
Slot 4 field name.

Story #048 — AskUser hang safety: sentinel tool_result commit before blocking
on CompletableFuture<Decision>; appendToolResult gains upsert-by-toolUseId
semantics. Prevents Anthropic HTTP 400 when frontend never renders Allow/Deny.

R-13 mitigation (d) baseline mirror: 31st pass, 0 binary delta.
Test sum: 7XX+ tests pass / 0 fail.
```

**PR body 必须含** `### R-13 dependency:tree 自查` 节(per CLAUDE.md §11 #6)。

---

## 8. 关联参考

- **Story #030**(ask-policy wiring)+ Story #041(ApprovalGate SPI)+ Story #042(frontend UI)+ Story #043 + Story #045 + Story #046 + Story #047 = **完整 ask-mode 缓解链**
- **dsh §4.10.1** 硬规则 1(ReAct Loop 自实现)+ 硬规则 2(Spring AI `ChatClient.tools().call()` 禁止)
- **dsh §9.3** AskUser recursion limit(max 3 retries)
- **anthropic-sdk-protocol** `tool_use` / `tool_result` pairing constraint
- **CLAUDE.md** §11 #4 Story 边界、`§11 #6` dep-tree 自查、`§11 #2` AC 黑盒验证
