# Story #052 — Tasks

> 24 steps 跨 5 phases + Phase 0 pre-flight + 12 AC ↔ task mapping + R-13 第 34 次 PASS checklist。
>
> **任务编排模式**: 参考 Story #048 task list(closed precedent — `DefaultTurnContext.appendToolResult` upsert-by-toolUseId)的 phase / step / AC mapping 3 件套;差异 = Story #052 多 1 个 **Phase 0 instrumentation 阶段**(0 行 prod 改动,纯 DEBUG 日志用于验证 hypothesis A vs B vs A+B)。
>
> **合并提交**: Phase 0 + Phase 1 + Phase 2 + 文档同步 1 个 git commit(避免 instrumentation 在 main branch 滞留)。

---

## Phase 0 — Pre-flight baseline 镜像(本 Story 启动前)

- [ ] T-0.1 读 Story #051 实施 commit(若已合) → git log --oneline -5 看 merge 顺序;确认 working tree 干净
- [ ] T-0.2 md5 当前 `lingshu-core/pom.xml`:`md5sum /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu/lingshu-core/pom.xml > /tmp/pom-052-pre.md5`
- [ ] T-0.3 跑 baseline:`cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core dependency:tree > /tmp/dtree-052-pre.txt 2>&1 && md5sum /tmp/dtree-052-pre.txt | tee /tmp/dtree-052-pre.md5`
- [ ] T-0.4 `git status` 确认 working tree 干净(否则 commit / stash 历史变更)
- [ ] T-0.5 Read `LinearTurnEngine.java` L484-546(`dispatchParallel` 全方法段 — 主循环 + 边界)+ L555-609(`waitForTool` 段 — Javadoc 契约基线)+ L478-480(LLM-return order FR-003 注释)
- [ ] T-0.6 Read `AnthropicLlmProvider.java` `parseResponse` 段(Phase 0 hypothesis B 验证入口)
- [ ] T-0.7 Read `DefaultTurnContext.java` `appendAssistant` + `appendToolResult` 段(Story #048 upsert-by-toolUseId 行为)
- [ ] T-0.8 Read `LlmErrorCodes.java`(L 段 11 条 + reserved 占位状态,L04 待定)
- [ ] T-0.9 Read `specs/052-dispatch-parallel-protocol-safety/spec.md` + `plan.md` + 本 `tasks.md` 全文,hold mental model
- [ ] T-0.10 启动期邀请 user review spec/plan/tasks 三件套(若 user 在线 → 等 user 确认;若非在线 → 直接进入 Phase 1)
- [ ] T-0.11 demo-product 启动 + 浏览器复现 11 步并行 tool_call 场景(基线 trace 备查,作为 Phase 0 instrumentation 对比基线)

---

## Phase 1 — Instrumentation 诊断(0 行 prod 改动)

### Phase 1a — `LinearTurnEngine.java` 加 DEBUG 日志

- [ ] T-1a.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`:
  - `dispatchParallel` L498-512 提交前:加 `LOG.debug("dispatchParallel submit-before idx={} callId={} futureDone={}", i, calls.get(i).getId(), futures[i].isDone())`
  - L515-528 主循环入口:加 `LOG.debug("dispatchParallel before-wait idx={} callId={} cancelled={}", i, expectedId, ctx.cancellation().isCancelled())`
  - L529-543 waitForTool 调用前后:加 `LOG.debug("dispatchParallel after-wait idx={} callId={} toolUseId={} content.len={}", i, expectedId, results[i] != null ? results[i].getToolUseId() : "NULL", results[i] != null ? results[i].getContent().length() : -1)`
- [ ] T-1a.2 类级 Javadoc 加 `🆕 Story #052 Phase 0 instrumentation — 验证 hypothesis A vs B` 段

### Phase 1b — `AnthropicLlmProvider.java` 加 DEBUG 日志

- [ ] T-1b.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java`:
  - `parseResponse` 入口(全部 tool_use blocks parse 完后):加 `LOG.debug("AnthropicLlmProvider.parseResponse tool_use_count={} firstId={} lastId={}", toolCalls.size(), firstId, lastId)`

### Phase 1c — `DefaultTurnContext.java` 加 DEBUG 日志

- [ ] T-1c.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java`:
  - `appendAssistant` 入口:加 `LOG.debug("appendAssistant before history.size={} after text.len={} toolCalls.size={}", history.size() - 1, text.length(), toolCalls.size())`
  - `appendToolResult` 入口:加 `LOG.debug("appendToolResult before history.size={} after toolUseId={} isError={}", history.size() - 1, result.getToolUseId(), result.isError())`
- [ ] T-1c.2 mid checkpoint:`cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core compile -q` 通过

### Phase 1d — 复现 + 决策

- [ ] T-1d.1 启动 demo-product,跑同一 query"写一首关于秋天的古诗七言四联写到 poem.txt"触发 11 步并行 tool_call 场景
- [ ] T-1d.2 收集 log4j / slf4j 输出,过滤 DEBUG 日志
- [ ] T-1d.3 **决策**(D-1):
  - 若 `AnthropicLlmProvider.parseResponse` DEBUG 显示 `tool_use_count=2` → **Hypothesis B 不成立**,锁 A
  - 若 `AnthropicLlmProvider.parseResponse` DEBUG 显示 `tool_use_count=1` → **Hypothesis B 成立**,需追加 fix Anthropic parser(本 Story scope 扩大)
  - 若 `dispatchParallel` DEBUG 显示 `after-wait` log 含 `toolUseId=NULL` 或 `content.len=-1` → **Hypothesis A 成立**,需追加 fix dispatchParallel 主循环边界
  - 若两者都成立 → **Hypothesis A+B 双层**,需两处 fix
- [ ] T-1d.4 **决策**(D-2):根据 D-1 锁定 hypothesis,决定是否新增 `LINGS-L04` ErrorCode(若锁 A → 大概率需要新 ErrorCode 区分边界 sentinel 与普通 cancelled;若锁 B → 0 新 ErrorCode)

---

## Phase 2 — 实施边界 sentinel(假设 Phase 1 锁 A)

### Phase 2a — `LinearTurnEngine.dispatchParallel` 主循环边界 sentinel

- [ ] T-2a.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`:
  - **删除** Phase 1a 添加的所有 DEBUG 日志(防 ship 进 main branch)
  - L515-544 主循环加 boundary sentinel:
    - `r == null` → `r = dispatchCancelledResult(expectedId, "[" + LlmErrorCodes.LINGS_L04 + "] tool dispatch returned null")` + `LOG.warn(...)`
    - `!Objects.equals(expectedId, r.getToolUseId())` → `r = dispatchCancelledResult(expectedId, "[" + LlmErrorCodes.LINGS_L04 + "] tool dispatch returned wrong toolUseId: " + r.getToolUseId())` + `LOG.warn(...)`
  - 参见 spec.md §3.2 Before/After 完整代码块
- [ ] T-2a.2 类级 Javadoc `🆕 Story #052` marker 替换 Phase 0 instrumentation 注释 → 改为 `🆕 Story #052 — boundary sentinel: results[i] MUST be non-null with matching toolUseId regardless of cancel/timeout/race path`

### Phase 2b — `dispatchCancelledResult` private 静态 helper

- [ ] T-2b.1 Edit `LinearTurnEngine.java`:加 `private static ToolResult dispatchCancelledResult(String toolUseId, String content)` helper(参见 spec.md §3.3 完整定义)
- [ ] T-2b.2 删除 3 个 inline `ToolResult.builder()...` 重复(cancel-before-wait / catch / cancel-by-shutdown 各 1 个),统一替换为 `dispatchCancelledResult(...)` 调用
- [ ] T-2b.3 验证 `dispatchCancelledResult` Javadoc 标注 `🆕 Story #052`

### Phase 2c — `waitForTool` Javadoc 契约加强

- [ ] T-2c.1 Edit `LinearTurnEngine.java` `waitForTool` 方法:
  - 加 Javadoc 契约: `🆕 Story #052 — contract hardening: this method MUST return a non-null ToolResult whose toolUseId matches the toolUseId argument, regardless of which exit path is taken (success / cancel / timeout / interrupt / exception). The caller relies on this invariant to maintain Anthropic protocol safety in dispatchParallel.`
  - **不**改 `waitForTool` 实现(Story #049 reverse AC 守护)
- [ ] T-2c.2 mid checkpoint:`mvn -pl lingshu-core compile -q` 通过
- [ ] T-2c.3 mid checkpoint:既有 `LinearTurnEngineCancellationTest`(Story #005)+ `LinearTurnEngineMaxStepsTest`(Story #008)+ `LinearTurnEngineAskUserTest`(Story #030 + #041 + #048)+ `LinearTurnEngineCompactionTest`(Story #045)+ `LinearTurnEngineSessionStoreTest`(Story #046)+ `DefaultAgentLiveStreamingTest`(Story #049)全部 0 改动全过

### Phase 2d — `LlmErrorCodes.LINGS_L04` 新增(若 Phase 1 D-2 决定)

- [ ] T-2d.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java`:
  - 加 `public static final String LINGS_L04 = "LINGS-L04";` 常量(参见 spec.md §3.5)
  - Javadoc 标注 `🆕 Story #052 — parallel tool dispatch protocol safety sentinel`
- [ ] T-2d.2 mid checkpoint:`mvn -pl lingshu-core compile -q` 通过

### Phase 2e — `LinearTurnEngine` 类级 Javadoc 同步

- [ ] T-2e.1 Edit `LinearTurnEngine.java` 类级 Javadoc:
  - `🆕 Story #052 — boundary sentinel protocol safety` 段(spec.md §3.2 + §3.3 + §3.4 汇总)

---

## Phase 3 — 测试三件套(2 文件 / 7 net new case)

### Phase 3a — `LinearTurnEngineDispatchParallelProtocolTest.java`(new, 6 L2)

- [ ] T-3a.1 Create `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineDispatchParallelProtocolTest.java`:
  - **AC-052-01** `parallelTwoCalls_bothResultsPersisted_protocolConsistent`:mock `LlmProvider` 返回 2 个并行 `bash_safe` tool_use(`call_00` + `call_01`),跑完整 turn;断言 history 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00), tool_result(call_01)]`(`Assistant.toolCalls.size() == ToolResult_blocks.size() == 2`)
  - **AC-052-02** `parallelOneCallRace_oneSentinelPersisted`:mock 2 个并行 tool_use,但 mock `DefaultToolExecutor.dispatch` 在第 2 个 call 上抛 `RuntimeException("synthetic race")`;断言 history 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, OK), tool_result(call_01, ERROR, content="[LINGS-L04] ...", isError=true)]` —— **无 orphan**
  - **AC-052-03** `parallelAskUser_sentinelStillUpserted`:mock `AskUserPermissionPolicy` 命中;mock `DefaultApprovalGate.ask(...)` 返回 Allow;跑完整 turn;断言 sentinel 被 upsert 替换(镜像 Story #048);即 history 末尾 `[assistant(tool_use[id=X, id=Y]), tool_result(call_00, OK=real result), tool_result(call_01, OK=real result)]`
  - **AC-052-04** `parallelCancelBeforeWait_sentinelPersisted`:mock `CancellationToken.fire()` 在 `supplyAsync` 提交后 + `waitForTool` 之前触发;跑完整 turn;断言 history 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, ERROR="cancelled before tool dispatch"), tool_result(call_01, ERROR="cancelled before tool dispatch")]`
  - **AC-052-05** `parallelTimeout_sentinelPersisted`:mock `DefaultToolExecutor.dispatch` 第 1 个 call 跑 6s,`tool-timeout: 5s`;跑完整 turn;断言 history 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, ERROR="tool timeout after 5s"), tool_result(call_01, OK=real result)]`
  - **AC-052-06** `parallelAllCancelled_protocolConsistent`:mock `CancellationToken.fire()` 在第 1 个 call 主循环入口触发;跑完整 turn;断言 history 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00, ERROR="cancelled ..."), tool_result(call_01, ERROR="cancelled ...")]`

### Phase 3b — `LinearTurnEngineProtocolInvariantIT.java`(new, 1 L3)

- [ ] T-3b.1 Create `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineProtocolInvariantIT.java`:
  - **AC-052-07** `e2e_parallelDispatch_historyAnthropicProtocolInvariant`:L3 集成 mock Anthropic HttpServer 接 POST + 验证 request body `messages[]`;跑完整 turn 含 2 个并行 `bash_safe` tool_call;turn 完成后模拟"下一轮 prompt"(再次调 `promptBuilder.build(ctx)`),断言构造出的 `Prompt.getMessages()` 末尾 `[assistant(tool_use[id=call_00, id=call_01]), tool_result(call_00), tool_result(call_01)]` —— **无 orphan tool_result**,**无** unmatched tool_use

### Phase 3c — 全量回归

- [ ] T-3c.1 `cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core test -q` 全通过(预期 786+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing — CLAUDE.md §11 已记录)
- [ ] T-3c.2 `mvn -pl lingshu-core verify` 0 fail / 0 error
- [ ] T-3c.3 反向 AC(R-RAC-1/2/3/4)验证:既有用例 0 改动全过(参见 spec.md §4.4)
- [ ] T-3c.4 R-13 mid checkpoint:`mvn -pl lingshu-core dependency:tree` 输出与 pre-flight 完全一致(pre.txt == post.txt 仅 timestamp)

---

## Phase 4 — R-13 post-flight baseline 镜像 + 文档同步 + 合并 commit

- [ ] T-4.1 跑 baseline:`mvn -pl lingshu-core dependency:tree > /tmp/dtree-052-post.txt 2>&1 && md5sum /tmp/dtree-052-post.txt | tee /tmp/dtree-052-post.md5`
- [ ] T-4.2 `diff /tmp/dtree-052-pre.txt /tmp/dtree-052-post.txt` — 仅时间戳差异
- [ ] T-4.3 确认 `banned-dependencies` enforcer Rule 0: passed(`mvn -pl lingshu-core enforcer:enforce` 或 build 自动触发)
- [ ] T-4.4 文档同步落地:
  - [ ] T-4.4.1 README.md「更新日期」段加 🆕 v1.5.62 Story #052 顶部 blockquote + 「核心特性」段补 🛡️ dispatchParallel 边界 sentinel bullet + 「Story 路线图」段追加 #052 retrospective(`dispatchParallel` 主循环边界 sentinel + `dispatchCancelledResult` helper + `waitForTool` Javadoc 契约 + `LINGS-L04` 新 ErrorCode + 6 L2 + 1 L3 + 2 文件 / R-13 0 binary delta 第 34 次 PASS / 累计 52 个 Story)
  - [ ] T-4.4.2 specs/ROADMAP.md 段一「✅ 已完成」加 #052 行(2026-10-18,786 pass / 0 fail / 2 MCP heartbeat flake pre-existing / R-13 0 binary delta 第 34 次 PASS / +LINGS-L04(若 D-2 决定)) + 段五「🎯 实施节奏」累计计数 51 → **52 个 Story**
  - [ ] T-4.4.3 constitution.md §10 R-13 缓解 Story 列表补 `#052` 行**第 34 次** R-13 mitigation (d) PASS 0 binary delta + 累计计数 32 → **33 个 Story**
  - [ ] T-4.4.4 dsh_agent_design.md §0 v1.5.61 → v1.5.62 + §13 changelog 新增 v1.5.62 行(具体行号待实施后定)
  - [ ] T-4.4.5 dsh_agent_design.md §6.1 `LinearTurnEngine.dispatchParallel` 段(若存在 L484-546 注释 block)补 `🆕 v1.5.62 Story #052` marker,简要描述 boundary sentinel pattern
  - [ ] T-4.4.6 dsh_agent_design.md §15.3 L 段 ErrorCode 编码约定:若 D-2 决定新增 `LINGS-L04 PARALLEL_DISPATCH_RACE`,在 L04 行加 `🆕 v1.5.62 Story #052` 标注(L05-L18 编号不变,0 移位)
- [ ] T-4.5 demo-product 人工端到端 7 步清单(plan.md §5):
  - [ ] T-4.5.1 `mvn -pl lingshu-examples/demo-product spring-boot:run`(启动成功,控制台 `resolved 1 flow engine provider(s)`,无 warn)
  - [ ] T-4.5.2 浏览器打开 `http://localhost:8080/`,确认 HTML 加载 + WebSocket/SSE ready
  - [ ] T-4.5.3 配 `application.yml`:`permission-policy: default` + `tool-parallelism: 4`
  - [ ] T-4.5.4 浏览器输入 "写一首关于秋天的古诗七言四联写到 poem.txt" 触发 11 步并行 tool_call 路径
  - [ ] T-4.5.5 **观察 SSE trace**:第 11 步并行 tool_call(`call_00` + `call_01`)的 2 套 ToolStarted/ToolCompleted/ObservationAppended 事件都应出现
  - [ ] T-4.5.6 **关键**:turn 完成后,**不** reload 浏览器,**继续** 发下一句 "刚才那首诗写得如何"
  - [ ] T-4.5.7 验证 console log:第二 turn 启动时,**没有** `tool_use_id call_00_... found in tool_result blocks` 报错
- [ ] T-4.6 `git status` 检查所有 spec/prod/test/docs 改动
- [ ] T-4.7 `git add specs/052-dispatch-parallel-protocol-safety/ lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineDispatchParallelProtocolTest.java lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineProtocolInvariantIT.java README.md specs/ROADMAP.md constitution.md dsh_agent_design.md`
- [ ] T-4.8 `git commit -m "$(cat <<'EOF'
  feat(agent): Story #052 dispatch-parallel-protocol-safety

  Phase 0 instrumentation (DEBUG logs in dispatchParallel main loop +
  AnthropicLlmProvider.parseResponse + DefaultTurnContext.appendAssistant/
  appendToolResult) locks root-cause hypothesis A (race in dispatchParallel
  boundary causes null/wrong-toolUseId results[i] which violates Anthropic
  tool_use_id ↔ tool_result pairing invariant).

  Phase 1 boundary sentinel in dispatchParallel main loop:
  results[i] MUST be non-null with matching toolUseId regardless of cancel/
  timeout/race path. Prevents Anthropic HTTP 400 "tool_use_id X found in
  tool_result blocks: Each tool_result block must have a corresponding
  tool_use block in the previous message" when LLM emits parallel tool_use
  calls. dispatchCancelledResult private helper + waitForTool Javadoc
  contract hardening (implementation unchanged).

  (Conditional) LINGS-L04 PARALLEL_DISPATCH_RACE ErrorCode in
  LlmErrorCodes — L 段 4 号, sentinel content prefix marker,
  mirrors Story #027a L01/L02 pattern. L05-L18 numbering unchanged.

  Test sum: 7 net new cases (6 L2 + 1 L3 IT) across 2 files
  (LinearTurnEngineDispatchParallelProtocolTest + LinearTurnEngineProtocolInvariantIT).

  R-13 mitigation (d) baseline mirror: 34th pass, 0 binary delta.

  🤖 Generated with [Claude Code](https://claude.com/claude-code)

  Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
  EOF
  )"`
- [ ] T-4.9 `git status` 确认 commit 成功,working tree 干净

---

## AC ↔ Task 映射(12 AC ↔ 4 phases)

| AC | Phase | Task | 关联 reference |
|---|---|---|---|
| AC-052-01 | Phase 3a | T-3a.1 | spec.md §4.1(happy path)|
| AC-052-02 | Phase 3a | T-3a.1 | spec.md §4.1(race condition 1 tool 抛 RuntimeException)|
| AC-052-03 | Phase 3a | T-3a.1 | spec.md §4.1(AskUser mode + sentinel upsert)|
| AC-052-04 | Phase 3a | T-3a.1 | spec.md §4.1(cancel-before-wait)|
| AC-052-05 | Phase 3a | T-3a.1 | spec.md §4.1(timeout 1 tool)|
| AC-052-06 | Phase 3a | T-3a.1 | spec.md §4.1(全 cancelled)|
| AC-052-07 | Phase 3b | T-3b.1 | spec.md §4.2(L3 IT Anthropic protocol invariant)|
| AC-052-08 | Phase 4 | T-4.1 / T-4.2 / T-4.3 | spec.md §4.3(R-13 baseline 第 34 次 PASS)|
| AC-052-09 | Phase 2d | T-2d.1 | spec.md §4.3(0 或 1 新 ErrorCode)|
| AC-052-10 | Phase 4 | T-4.1 / T-4.2 | spec.md §4.3(0 新 Maven dep)|
| AC-052-11 | Phase 3c | T-3c.1 / T-3c.2 | spec.md §4.3(786+ tests pass)|
| AC-052-12 | Phase 3c | T-3c.3 | spec.md §4.3(反向 AC 适配性)|

---

## budget guards(运行时检查)

| 项 | 触发条件 | 触发后兜底 |
|---|---|---|
| 核心文件改动超过 5 | `git diff --name-only main HEAD \| wc -l` > 5 | 立即停手,review spec.md §2 |
| 新 Maven 依赖引入 | `git diff main...HEAD -- '**/pom.xml' \| grep -E '^\+<' \| grep -v '^\+\+\+'` non-empty | 立即停手,撤回 + 重跑 baseline |
| 新 ErrorCode 引入超过 1 | `git diff main...HEAD \| grep -E '^\+.*LINGS-[A-Z]+[0-9]' \| grep -v '^\+\+\+'` count > 1 | 立即停手,核对 spec.md §3.5 应只 L04 |
| Phase 0 DEBUG 日志 ship 进 main | `git diff main...HEAD -- LinearTurnEngine.java AnthropicLlmProvider.java DefaultTurnContext.java` 含 `LOG.debug("dispatchParallel\|parseResponse\|appendAssistant\|appendToolResult` 残留 | 立即停手,核对 Phase 1 task-1 删除所有 Phase 0 DEBUG 日志 |
| LlmErrorCodes 编号冲突 | L05-L18 reserved 占位有移位 | 立即停手,核对 spec.md §3.5「0 移位」 |
| `Message.Assistant` 字段变更 | `git diff main...HEAD -- Message.java` 触及 | 立即停手,核对 spec.md §5 不变项 |
| `waitForTool` 实现改动 | `git diff main...HEAD -- LinearTurnEngine.java` 含 `waitForTool` 私有方法体变更(仅 Javadoc 允许) | 立即停手,核对 spec.md §3.4「不**改** `waitForTool` 实现」 |
| boundary sentinel content 误改 | sentinel text 不含 `[LINGS-L04]` 前缀 | 立即停手,核对 spec.md §3.2 第 4 字段契约 |

---

## R-13 mitigation (d) baseline mirror checklist(第 34 次 PASS)

```bash
# Phase 4 实施后:
[ "$(cat /tmp/dtree-052-pre.md5 | awk '{print $1}')" = "$(cat /tmp/dtree-052-post.md5 | awk '{print $1}')" ] || diff /tmp/dtree-052-pre.txt /tmp/dtree-052-post.txt
# 预期:hash 不同(因 timestamp)且 diff 仅时间戳 line 差异
```

**判定**:
- ✅ = 仅有 timestamp 行差异,binary tree 完全相同 → **第 34 次 PASS 0 binary delta**
- ✗ = 新增 dependency tree 行 → 立即 abort + revert + 重写 plan

**pre.md5 hash 记录**(`/tmp/dtree-052-pre.md5` 内容)。
**post.md5 hash 记录**(`/tmp/dtree-052-post.md5` 内容)。
**commit message 应包含**: `R-13 mitigation (d) baseline mirror: 34th pass, 0 binary delta.`

---

## 24 steps 总览

| Phase | Steps 数量 | Task 范围 |
|---|---|---|
| Phase 0 pre-flight | 11 | T-0.1 ~ T-0.11 |
| Phase 1 instrumentation | 8 | T-1a.1-T-1a.2 + T-1b.1 + T-1c.1-T-1c.2 + T-1d.1-T-1d.4 |
| Phase 2 implementation | 11 | T-2a.1-T-2a.2 + T-2b.1-T-2b.3 + T-2c.1-T-2c.3 + T-2d.1-T-2d.2 + T-2e.1 |
| Phase 3 tests | 7 | T-3a.1 + T-3b.1 + T-3c.1-T-3c.4 |
| Phase 4 baseline + docs + commit | 14 | T-4.1 ~ T-4.9 + 5 个 documentation sync |
| **总** | **51** | 24 high-level steps + sub-steps |

---

## reference(本 Story 依赖的现有文件)

| 文件 | 路径 | 角色 |
|---|---|---|
| `LinearTurnEngine.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/` | engine 主循环 — Phase 1 instrumentation + Phase 2 边界 sentinel 改动 |
| `AnthropicLlmProvider.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/` | LLM impl — Phase 1 instrumentation(hypothesis B 验证入口)|
| `DefaultTurnContext.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/` | TurnContext impl — Phase 1 instrumentation |
| `LlmErrorCodes.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/` | ErrorCode 常量类 — Phase 2d `LINGS_L04` 新增 |
| `Message.java` | `lingshu-core/src/main/java/ai/lingshu/core/message/` | 4 子类契约 — 0 改动(只读)|
| `Message.Assistant` | 同上 nested class | toolCalls 嵌入模式 — 0 改动 |
| `Message.ToolResult` | 同上 nested class | 3 字段 ctor — 0 改动 |
| `ToolResult.java` | `lingshu-core/src/main/java/ai/lingshu/core/message/` | builder API — 复用 |
| `LinearTurnEngineCancellationTest.java` | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/` | Story #005 reverse AC |
| `LinearTurnEngineMaxStepsTest.java` | 同上 | Story #008 reverse AC |
| `LinearTurnEngineAskUserTest.java` | 同上 | Story #030 + #041 + #048 reverse AC |
| `LinearTurnEngineCompactionTest.java` | 同上 | Story #045 reverse AC |
| `LinearTurnEngineSessionStoreTest.java` | 同上 | Story #046 reverse AC |
| `DefaultAgentLiveStreamingTest.java` | 同上 | Story #049 reverse AC |

---

## 应急路径

如 Phase 1 instrumentation 任何 mid checkpoint 失败:
- 立即停手
- 重新读 spec.md §3.1 验证步骤
- 检查 import / log4j 配置 / DEBUG 日志格式
- 不进入 Phase 2 实施

如 Phase 2 实施任何 mid checkpoint 失败:
- 立即停手
- 重新读 spec.md §3.2 / §3.3 / §3.4 / §3.5 对应 phase
- 检查 boundary sentinel 字段契约 / `Objects.equals` JDK 8 锁 / `dispatchCancelledResult` helper 字段完整
- 不进入 Phase 3 tests 编写

如 Phase 3 任何 AC 失败:
- 立即停手
- 检查 fixture 是否按 spec.md §4.1 / §4.2 描述
- 重读 spec.md §4.4 反例 R-RAC-1/2/3/4 防护路径
- 不进入 Phase 4

如 Phase 4 R-13 baseline diff 不止时间戳:
- 立即停手
- 检查 `mvn -pl lingshu-core dependency:tree | grep -E '\+-'` 是否新增 dependency 节点
- revert + 重审 plan.md §2 D-1/D-2 决策

如 Phase 4 commit hook 失败:
- 立即停手
- 重读 budget guards + 反例 R-RAC-1/2/3/4 防护路径
- 不重 push,等 user 决策