# Story #048 — Tasks

> 24 steps 跨 6 phases + Phase 0 pre-flight + 13 AC ↔ task mapping + R-13 第 31 次 PASS checklist。
>
> **任务编排模式**: 参考 Story #046 task list(closed precedent)的 phase / step / AC mapping 3 件套。

---

## Phase 0 — Pre-flight baseline 镜像(本 Story 启动前)

- [ ] T-0.1 读 Story #047 实施 commit(若已合)/ in-flight worktree 状态(`/Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu/lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` 等 3 prod 文件已被 #047 修改)
- [ ] T-0.2 md5 当前 `lingshu-core/pom.xml`:`md5sum /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu/lingshu-core/pom.xml > /tmp/pom-048-pre.md5`
- [ ] T-0.3 跑 baseline:`cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core dependency:tree > /tmp/dtree-048-pre.txt 2>&1 && md5sum /tmp/dtree-048-pre.txt | tee /tmp/dtree-048-pre.md5`
- [ ] T-0.4 `git status` 确认 working tree 干净(否则 commit / stash 历史变更)
- [ ] T-0.5 Read `LinearTurnEngine.java` L300-L365、L580-L665 完整段(re-confirm 实施基线)
- [ ] T-0.6 Read `DefaultTurnContext.java` L160-L215(确认 import + 类级 Javadoc)
- [ ] T-0.7 Read `TurnContext.java`(确认 SPI 公共方法签名)
- [ ] T-0.8 Read `DefaultSession.java`(确认 append + compact 锁模式 + appendLock 暴露策略)
- [ ] T-0.9 Read `specs/048-ask-user-sentinel-tool-result/spec.md` + `plan.md` + 本 `tasks.md` 全文,hold mental model
- [ ] T-0.10 启动期邀请 user review spec/plan/tasks 三件套(若 user 在线 → 等 user 确认;若非在线 → 直接进入 Phase 1)

---

## Phase 1 — `DefaultTurnContext.appendToolResult` upsert-by-toolUseId

- [ ] T-1.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultSession.java`:
  - 暴露 `public Object appendLock() { return history; }`(impl-only getter,类似 Story #028 `runtimeSandbox()` exposing pattern)
  - Javadoc 加 `🆕 Story #048` 段
- [ ] T-1.2 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java`:
  - `appendToolResult` 方法体替换为 upsert-by-toolUseId 实现(参见 plan.md §7.1 Phase 1)
  - 类级 Javadoc 加 `🆕 Story #048` upsert-by-toolUseId 段
- [ ] T-1.3 mid checkpoint:`cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core compile -q` 通过
- [ ] T-1.4 mid checkpoint:既有 `DefaultTurnContextTest` 5 case 0 改动全过(`mvn -pl lingshu-core test -Dtest='DefaultTurnContextTest' -q` 单独跑)

---

## Phase 2 — `TurnContext.appendToolResult` Javadoc 更新

- [ ] T-2.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java`:
  - L75 旧 1 行 Javadoc `/** Append a tool result message to history. */` 替换为完整 upsert-by-toolUseId 注解(参见 spec §3.1)
- [ ] T-2.2 checkpoint:`mvn -pl lingshu-core compile -q` 通过

---

## Phase 3 — `LinearTurnEngine.dispatchWithPolicy` AskUser 分支加 sentinel

- [ ] T-3.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`:
  - L620 `if (d instanceof Decision.AskUser)` 块起始:在 `Decision resolved = toolCtx.approval().ask(...)` 之前插入 sentinel 构造 + `ctx.appendToolResult(sentinel)`(参见 plan.md §7.1 Phase 3)
  - 类级 Javadoc 加 `🆕 Story #048 — AskUser hang safety (sentinel + upsert)` 段
- [ ] T-3.2 验证 sentinel 4 字段契约:
  - `toolUseId = call.getId()` ✅
  - `status = OK` ✅
  - `isError = false` ✅
  - `content = "[PENDING_APPROVAL] Awaiting user decision for tool: " + call.getName()` ✅
- [ ] T-3.3 验证 import:`ToolResult` 已 import;`ToolResult.builder()` 已可用
- [ ] T-3.4 mid checkpoint:`mvn -pl lingshu-core compile -q` 通过
- [ ] T-3.5 mid checkpoint:既有 `LinearTurnEngineAskUserTest` 3 L2(Story #030 + #041)+ `LinearTurnEngineProviderCompactionTest` 4 L1 + `LinearTurnEngineProviderSessionStoreTest` 3 L1 + `LinearTurnEngineE2ESmokeTest` 1 L1 + `LinearTurnEngineProviderTest` 3 L1 0 改动全过

---

## Phase 4 — 测试三件套

### Phase 4a — `DefaultTurnContextUpsertTest.java`(new)

- [ ] T-4a.1 Create `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultTurnContextUpsertTest.java`:
  - AC-048-01 `upsert_sameToolUseId_replacesExisting`(3 段 history[A, TR(X,"a"), B]→ replace in-place)
  - AC-048-02 `upsert_differentToolUseId_appendsNormally`([A, TR(X,"a")] + TR(Y,"b") → append)
  - AC-048-03 `upsert_scopedToMostRecentAssistantMessage`([TR(X,"old"), A, TR(X,"first")] + TR(X,"second") → only replace within current turn, prior TR(X,"old") 0 改动)

### Phase 4b — `LinearTurnEngineAskUserTest.java`(modify)

- [ ] T-4b.1 Read 当前 `LinearTurnEngineAskUserTest.java`(Story #030 + Story #041 已落 3 L2)
- [ ] T-4b.2 Edit `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserTest.java`:
  - AC-048-04 `askUser_allow_resolvesToRealResult_upsertsSentinel`:mock `Decision.Allow`,run turn,断言 history 末尾 `[assistant(tool_use[id=X]), tool_result(content=real_output, id=X)]` + **0 sentinel duplicate**
  - AC-048-05 `askUser_deny_resolvesToDenyResult_upsertsSentinel`:mock `Decision.Deny(reason)`,断言 history 末尾 `[assistant(tool_use[id=X]), tool_result(content=reason, isError=true, id=X)]` + 0 sentinel duplicate
  - AC-048-06 `askUser_timeout_resolvesToLingsP02_upsertsSentinel`:mock ask timeout → `[LINGS-P02]`,断言 history 末尾 `[assistant(tool_use[id=X]), tool_result(content=[LINGS-P02], isError=true, id=X)]`
  - AC-048-07 `askUser_neverResolved_turnStillCompletes_historyProtocolConsistent`:mock `CountDownLatch` 不 release(永 complete),500ms runTurn + cancel,断言 history 末尾 `[assistant(tool_use[id=X]), tool_result(content=[PENDING_APPROVAL] Awaiting user decision..., isError=false, id=X)]`(protocol-consistent)

### Phase 4c — `LinearTurnEngineAskUserNoRenderIT.java`(new)

- [ ] T-4c.1 Create `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineAskUserNoRenderIT.java`:
  - AC-048-08 `e2e_frontendNeverRendersButton_historyConsistentNoAnthropic400`:L3 IT
  - mock Anthropic HttpServer 接 POST + 验证 request body `messages[]` 无 unmatched tool_use
  - mock `ApprovalRegistry` 让 ask 永不 resolve(`CompletableFuture` never complete)
  - runTurn 完成后,断言 mock server 收到的(若有)下一轮 messages[] 仍 protocol-consistent
  - **绝无** `messages.X: tool_use ids were found without tool_result blocks` 触发

### Phase 4d — 全量回归

- [ ] T-4d.1 `cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core test -q` 全通过(预期 761+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing)
- [ ] T-4d.2 `mvn -pl lingshu-core verify` 0 fail / 0 error
- [ ] T-4d.3 反向 AC(R-RAC-1/2/3)验证:既有用例 0 改动全过(参见 spec §4.5)
- [ ] T-4d.4 R-13 mid checkpoint:`mvn -pl lingshu-core dependency:tree` 输出与 pre-flight 完全一致(pre.txt == post.txt 仅 timestamp)

---

## Phase 5 — R-13 post-flight baseline 镜像 + Story #047 共 commit

- [ ] T-5.1 跑 baseline:`mvn -pl lingshu-core dependency:tree > /tmp/dtree-048-post.txt 2>&1 && md5sum /tmp/dtree-048-post.txt | tee /tmp/dtree-048-post.md5`
- [ ] T-5.2 `diff /tmp/dtree-048-pre.txt /tmp/dtree-048-post.txt` — 仅时间戳差异
- [ ] T-5.3 确认 `banned-dependencies` enforcer Rule 0: passed(`mvn -pl lingshu-core enforcer:enforce` 或 build 自动触发)
- [ ] T-5.4 Story #047 + Story #048 文档同步落地:
  - [ ] T-5.4.1 README.md 「更新日期」段 + 「核心特性」段(若 bullet) + 「Story 路线图」段(追加 #047 + #048 retrospective)
  - [ ] T-5.4.2 specs/ROADMAP.md 段一「✅ 已完成」加 #047 + #048 行 + 段二「🟡 待补」划掉 + 段五「🎯 实施节奏」累计计数 +1(若 #047,#048 都合 — 累计 +2)
  - [ ] T-5.4.3 constitution.md §10 R-13 缓解 Story 列表补 `#047 #048` 行(第 31 次) + 累计计数
  - [ ] T-5.4.4 dsh_agent_design.md §0 + §13 changelog 新增 v1.5.60 行(具体行号待实施后定,Story #047/#048 共 1 行)
  - [ ] T-5.4.5 dsh_agent_design.md §6.1 LinearTurnEngine 段补 `🆕 v1.5.60 Story #047 #048` marker
- [ ] T-5.5 `git status` 检查所有 spec/prod/test/docs 改动
- [ ] T-5.6 `git add specs/047-ask-policy-end-to-end-wiring/ specs/048-ask-user-sentinel-tool-result/ lingshu-core/src/main/java/... lingshu-core/src/test/java/... README.md specs/ROADMAP.md constitution.md dsh_agent_design.md`
- [ ] T-5.7 `git commit -m "$(cat <<'EOF'
  feat(agent): Story #047 #048 ask-policy end-to-end + AskUser sentinel

  - Story #047 — Bug A (AgentFactory typo) + Bug B (demo-product
    ChatController bypasses yml). Makes ask-mode trigger end-to-end
    through @Value binding + correct top-level Slot 4 field name.

  - Story #048 — AskUser hang safety: sentinel tool_result commit before
    blocking on CompletableFuture<Decision>; appendToolResult gains
    upsert-by-toolUseId semantics. Prevents Anthropic HTTP 400 when
    frontend never renders Allow/Deny buttons.

  R-13 mitigation (d) baseline mirror: 31st pass, 0 binary delta.

  🤖 Generated with [Claude Code](https://claude.com/claude-code)

  Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
  EOF
  )"`
- [ ] T-5.8 `git status` 确认 commit 成功,working tree 干净

---

## AC ↔ Task 映射(13 AC ↔ 6 phases)

| AC | Phase | Task | 关联 reference |
|---|---|---|---|
| AC-048-01 | Phase 4a | T-4a.1 | spec §4.1 |
| AC-048-02 | Phase 4a | T-4a.1 | spec §4.1 |
| AC-048-03 | Phase 4a | T-4a.1 | spec §4.1(关键:反例 R-RAC-1)|
| AC-048-04 | Phase 4b | T-4b.2 | spec §4.2 |
| AC-048-05 | Phase 4b | T-4b.2 | spec §4.2 |
| AC-048-06 | Phase 4b | T-4b.2 | spec §4.2 |
| AC-048-07 | Phase 4b | T-4b.2 | spec §4.2(关键:7.2 R-RAC-1 防 unmatched)|
| AC-048-08 | Phase 4c | T-4c.1 | spec §4.3(关键:L3 IT)|
| AC-048-09 | Phase 5 | T-5.1 / T-5.2 / T-5.3 | spec §4.4 |
| AC-048-10 | Phase 5 | T-5.3 | spec §4.4(0 新 ErrorCode)|
| AC-048-11 | Phase 5 | T-5.1 / T-5.2 | spec §4.4(0 新 Maven dep)|
| AC-048-12 | Phase 4d | T-4d.1 / T-4d.2 | spec §4.4 |
| AC-048-13 | Phase 4d | T-4d.3 | spec §4.5(反向 AC 适配性)|

---

## budget guards(运行时检查)

| 项 | 触发条件 | 触发后兜底 |
|---|---|---|
| 核心文件改动超过 5 | `git diff --name-only main HEAD | wc -l` > 5 且不含 #047+#048 共有 | 立即停手,review spec §2 |
| 新 Maven 依赖引入 | `git diff main...HEAD -- '**/pom.xml' \| grep -E '^\+<' \| grep -v '^\+\+\+'` non-empty | 立即停手,撤回 + 重跑 baseline |
| 新 ErrorCode 引入 | `git diff main...HEAD \| grep -E '^\+.*LINGS-[A-Z]+[0-9]' \| grep -v '^\+\+\+'` non-empty | 立即停手,核对 spec §5 应复用 LINGS-P02 |
| sentinel content 误改 | sentinel text 含 user-visible 安全敏感关键字 | 立即停手,核对 spec §3.3 第 4 字段契约 |
| `Message.ToolResult` 字段变更 | `git diff main...HEAD -- lingshu-core/src/main/java/ai/lingshu/core/message/Message.java` 触及 | 立即停手,核对 spec §5 不变项 |

---

## R-13 mitigation (d) baseline mirror checklist(第 31 次 PASS)

```bash
# Phase 5 实施后:
[ "$(md5sum /tmp/dtree-048-pre.txt | awk '{print $1}')" = "$(md5sum /tmp/dtree-048-post.txt | awk '{print $1}')" ] || diff /tmp/dtree-048-pre.txt /tmp/dtree-048-post.txt
# 预期:hash 不同(因 timestamp)且 diff 仅时间戳 line 差异
```

**判定**:
- ✅ = 仅有 timestamp 行差异,binary tree 完全相同 → **第 31 次 PASS 0 binary delta**
- ✗ = 新增 dependency tree 行 → 立即 abort + revert + 重写 plan

**pre.md5 hash 记录**(`/tmp/dtree-048-pre.md5` 内容)。
**post.md5 hash 记录**(`/tmp/dtree-048-post.md5` 内容)。
**commit message 应包含**: `R-13 mitigation (d) baseline mirror: 31st pass, 0 binary delta.`

---

## 24 steps 总览

| Phase | Steps 数量 | Task 范围 |
|---|---|---|
| Phase 0 pre-flight | 10 | T-0.1 ~ T-0.10 |
| Phase 1 | 4 | T-1.1 ~ T-1.4 |
| Phase 2 | 2 | T-2.1 ~ T-2.2 |
| Phase 3 | 5 | T-3.1 ~ T-3.5 |
| Phase 4 | 11 | T-4a.1, T-4b.1-T-4b.2, T-4c.1, T-4d.1-T-4d.4 |
| Phase 5 | 14 | T-5.1 ~ T-5.8 + 5 个 documentation sync + pre-flight + commit |
| **总** | **46** | 24 high-level steps + sub-steps |

---

## reference(本 Story 依赖的现有文件)

| 文件 | 路径 | 角色 |
|---|---|---|
| `LinearTurnEngine.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/` | engine 主循环 — Phase 3 改动 |
| `DefaultTurnContext.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/` | TurnContext impl — Phase 1 改动 |
| `DefaultSession.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/` | Session impl — Phase 1 暴露 appendLock |
| `TurnContext.java` | `lingshu-core/src/main/java/ai/lingshu/core/runtime/` | SPI — Phase 2 Javadoc |
| `Message.java` | `lingshu-core/src/main/java/ai/lingshu/core/message/` | 4 子类契约 — 0 改动(只读)|
| `Message.ToolResult` | 同上 nested class | 3 字段 ctor — 0 改动 |
| `ToolResult.java` | `lingshu-core/src/main/java/ai/lingshu/core/message/` | builder API — 复用 |
| `PermissionErrorCodes.LINGS_P02` | `lingshu-core/src/main/java/ai/lingshu/core/permission/` | 已落(Story #030),复用 |
| `LinearTurnEngineAskUserTest.java` | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/` | Phase 4b 追加 |

---

## 应急路径

如 Phase 1-3 任何 mid checkpoint 失败:
- 立即停手
- 重新读 spec.md/plan.md 对应 phase
- 检查 import / 锁策略 / sentinel 字段契约
- 不进入 Phase 4 tests 编写

如 Phase 4 任何 AC 失败:
- 立即停手
- 检查 fixture 是否按 spec §3.1 / §3.3 描述
- 重读 spec §4.5 反例 R-RAC-1/2/3 防护路径
- 不进入 Phase 5

如 Phase 5 R-13 baseline diff 不止时间戳:
- 立即停手
- 检查 `mvn -pl lingshu-core dependency:tree | grep -E '\\+-'` 是否新增 dependency 节点
- revert + 重审 plan D-1/D-2/D-3 决策
