# Story #048 — Plan

> 实施路径 / 决策点 / 镜像 precedent / R-13 mitigation (d) baseline 镜像 pre-flight / demo-product 人工端到端 7 步清单 + Story #047 共 commit 流程。
>
> **本 Story 与 Story #047 一起 commit**。Story #047 落地 ask-policy routing 修复;Story #048 落地 AskUser hang 安全性。两条 Story 完全正交,但 git history 上合成 1 个 commit(per 用户 2026-10-14 指令)。

---

## 1. 实施阶段总览(6 phase,phase 内串行,phase 之间顺序)

| Phase | 名称 | 文档预加载 | 内容概要 | R-13 节点 |
|---|---|---|---|---|
| 0 | pre-flight baseline 镜像 | md5sum 当前 `mvn -pl lingshu-core dependency:tree` 输出存 `pre.txt` | 第 31 次 PASS 准备 | — |
| 1 | `DefaultTurnContext.appendToolResult` 改语义 | 读 §3.2 实现替换方案 | upsert-by-toolUseId | R-13 mid checkpoint |
| 2 | `TurnContext` Javadoc 更新 | 读 §3.1 注解 | SPI 文档同步(无业务逻辑)| — |
| 3 | `LinearTurnEngine.dispatchWithPolicy` AskUser 分支加 sentinel | 读 §3.3 sentinel 提交,核对 L620-660 + L322-324 + 第 4 关键字段契约 | 阻塞前 commit + main loop 走 upsert | R-13 mid checkpoint |
| 4 | 测试三件套 | `DefaultTurnContextUpsertTest` 3 L1 + `LinearTurnEngineAskUserTest` 追加 4 L2 + `LinearTurnEngineAskUserNoRenderIT` 1 L3 new | AC §4.1 / 4.2 / 4.3 / 4.4 / 4.5 全过 | — |
| 5 | R-13 post-flight baseline 镜像 | `mvn -pl lingshu-core dependency:tree` 输出存 `post.txt`,`md5sum pre.txt post.txt` 对比仅时间戳差异 | **第 31 次 PASS 0 binary delta** |  |
| 6 | Story #047 + #048 合并 git commit | §7 commit message 模板 | 共 commit,PR body 含 R-13 节 | — |

---

## 2. 决策点表(实施期 5 个决策)

| D | 决策 | 选项 | 推荐 | 实施期可调整 |
|---|---|---|---|---|
| D-1 | upsert 锁实现:`DefaultSession` 暴露 `appendLock()` 方法 vs `synchronized (history)` 公开访问 | (a) `appendLock()` getter on `DefaultSession` impl-only(类比 `#028 runtimeSandbox()` exposing pattern)<br>(b) `DefaultSession.historyMut()` 返回 mutable list under lock | (a) `appendLock()` getter — 与现有 `DefaultSession.compact()` / `append()` 同一 `synchronized (history)` 锁路径对齐,JDK 8 模式不变 | ✗ — 锁定 |
| D-2 | upsert 范围 vs scan-from-end 优化 | (a) 从 history 末尾扫到上一个 assistant(全当前位置扫描)<br>(b) 维护 `lastAssistantIdx` 缓存(sync lock 下)| (a) 从 history 末尾扫 — 简单、与现有 `compact()` / `append()` 不引入新字段、Story #018 compactor 已用类似 pattern、O(N) 但 N 通常小 | ✗ — 锁定 |
| D-3 | sentinel content 格式 | (a) `"[PENDING_APPROVAL] Awaiting user decision for tool: <name>"`<br>(b) `"[PENDING_APPROVAL] "<br>(c) JSON-encoded 详细元数据 | (a) 自描述 + 可读性强 + 与 LLM 兼容(message history 反序列化时 JSON-safe)| ✗ — 锁定 |
| D-4 | sentinel 是否 `isError=true` | (a) `isError=false`(pending 不是 error)<br>(b) `isError=true`(Anthropic 可能按"require 后续 tool_result"|(a) 与 Anthropic "tool_result with isError=true 等价 tool failure" 语义对齐,正确 | ✗ — 锁定 |
| D-5 | sentinel 在 `dispatchWithPolicy` 内部提交 vs 加到 main loop L319 之前 | (a) `dispatchWithPolicy` AskUser 分支内,sentinel 紧贴 blocked 之前<br>(b) main loop 检测 AskUser,批 commit sentinels 后再 dispatch | (a) 单条 AskUser 局部性、可读性高、与 Story #041 extracted `DefaultApprovalGate` Single Responsibility 一致。cl=4 时,sentinel 在 cl 内部并发提交,这才是正确的并行模式;(b) 批 commit 反而更复杂,cl=4 时 4 个 AskUser 都必须等同一轮 sentinel commit 后再 dispatch | ✗ — 锁定 |

---

## 3. precedent 镜像表(Story #045 / #046 pattern)

| Story 期间模式 | 当前 Story #048 镜像 |
|---|---|
| Story #045 `#046 ctor` 加 SessionStore final 参数 + `trySave(TurnContext)` 守卫 | 镜像进 sentinel 设计:加 sentinel commit + main loop 自然 upsert |
| Story #046 `Message.@JsonTypeInfo` 多态 round-trip + `Checkpoint.@Value` 4-arg ctor | 镜像进 `Message.ToolResult` 复用:现有 3 字段 ctor 0 改动 |
| Story #045 `CompactorRouter extends SlotRouter` | 镜像进 `DefaultTurnContext.appendToolResult` 单一方法加分支 |
| Story #046 `NullObject pattern` 保护 fixture | 镜像进 sentinel 自描述:`[PENDING_APPROVAL]` 标记本身是 fixture-friendly |
| Story #041 ApprovalGate SPI 抽离(Plan B)| 镜像进 **Story #048 不抽 SPI**,纯在 `DefaultTurnContext` + `LinearTurnEngine` 单文件收敛 — sentinel 与 upsert 是 model-layer detail,不是 Slot 契约,不应上升 SPI |

---

## 4. R-13 mitigation (d) baseline 镜像 pre-flight

### 4.1 步骤

```bash
# (1) 当前 pre-flight baseline(Story #047 实施完前)
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
mvn -pl lingshu-core dependency:tree > /tmp/dtree-048-pre.txt 2>&1
md5sum /tmp/dtree-048-pre.txt | tee /tmp/dtree-048-pre.md5

# (2) 实施 Story #048(Phase 1-4 完成)

# (3) post-flight baseline
mvn -pl lingshu-core dependency:tree > /tmp/dtree-048-post.txt 2>&1
md5sum /tmp/dtree-048-post.txt | tee /tmp/dtree-048-post.md5

# (4) 对比 — 仅时间戳差异
diff /tmp/dtree-048-pre.txt /tmp/dtree-048-post.txt
# 预期输出:仅 timestamp line 差异,无 binary 删除 / 新增
```

### 4.2 baseline 通过判据

- ✅ `pre.txt == post.txt` 除 timestamp
- ✅ `banned-dependencies` enforcer Rule 0: passed
- ✅ Story #047 + Story #048 合并累计 PASS 第 31 次 0 binary delta

### 4.3 失败兜底

- 如果 diff 显示新 binary delta → **立即停手**,查 `pom.xml` 是否失误新增 dep,或 spring-ai-bom 升级 → 撤回 + 重跑 baseline
- 第 30 次 PASS Story #046 baseline = `bb6627104920b580322931b7b33a0806`(re-meta 参考)

---

## 5. demo-product 人工端到端 7 步清单(Story #047+Story #048 实施完成后)

(Spec 纯 dev-side 部署,forked user 建议跑一遍 demo-product 闭环验 Story #048 sentinel 路径。)

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | `mvn -pl lingshu-examples/demo-product spring-boot:run` | 启动成功,控制台 `resolved 1 flow engine provider(s)`,无 warn |
| 2 | 浏览器打开 `http://localhost:8080/`,确认 HTML 加载 + WebSocket/SSE ready | index.html 加载,EventSource ready |
| 3 | 配 `application.yml`:`permission-policy: ask` + `tools.ask-list: [bash_safe]` + `tool-parallelism: 4` + `approval-timeout-seconds: 0`(Story #030 overnight parity 默认值)| yml 启动成功 |
| 4 | 在聊天输入 "list current directory using bash tool" | SSE events 显示 `ApprovalRequired` event,ApprovalRegistry 注册 entry |
| 5 | **不解 Allow/Deny button**(mock 前端 paused via devtools),等待 5 秒 | Sentinels 写入 history(`[PENDING_APPROVAL]` 4 条 for cl=4)|
| 6 | 在聊天窗口**发第二句** "what about pwd"? | ReAct 第二 turn 启动;Anthropic HttpServer 收到 POST 后,response 200(无 HTTP 400)|
| 7 | 验证 console log:second turn 启动时,**没有** `tool_use ids were found without tool_result blocks` 报错 | ✅ PASS = Story #048 落地 |

如有任何步骤异常,**立即停手**,回 Phase 1-2 排查。

---

## 6. 与 Story #047 共同 commit 的 git 工作流

```
# 实施完成 Story #047 + Story #048 后:
git status                                           # 检查所有改动
git add specs/047-ask-policy-end-to-end-wiring/ \   # Phase 1 spec/plan/tasks 三件套
    specs/048-ask-user-sentinel-tool-result/ \      # Phase 2 spec/plan/tasks 三件套
    lingshu-core/src/main/java/.../                  # Story #047 + #048 prod 文件改动
    lingshu-core/src/test/java/.../                  # Story #047 + #048 test 文件改动
    README.md \                                      # Story #047 + #048 更新日期 + 路线图条目
    specs/ROADMAP.md \                               # 段一/段五 同步
    constitution.md \                                # §10 R-13 缓解 Story 列表补 #047 + #048 行
    dsh_agent_design.md \                            # §0 + §13 changelog v1.5.59 → v1.5.60 待 Story #047+#048 实施后实际行号定
git commit -m "feat(agent): Story #047 #048 ..."
# 注:**不** push,等用户指令
```

PR body 模板:
```markdown
## Stories
- Story #047: ask-policy end-to-end wiring (Bug A + Bug B)
- Story #048: ask-user-sentinel-tool-result (sentinel + upsert-by-toolUseId)

## Test plan
- [ ] mvn -pl lingshu-core test — 761+ tests pass / 0 fail
- [ ] mvn -pl lingshu-core dependency:tree baseline mirror

### R-13 dependency:tree 自查
pre.md5 = <hash>
post.md5 = <hash>
diff = 仅时间戳差异 = 0 binary delta 第 31 次 PASS
```

---

## 7.1 阶段路径决策(实施期每个 phase 入口)

### Phase 1 — `DefaultTurnContext.appendToolResult` upsert-by-toolUseId

**入口条件**: Story #047 实施到 Phase 4(prod 文件 tests 过完),Story #048 启动。

**操作**:
1. 读 §3.2,核对 `Message.ToolResult` 字段(`getToolUseId()` / `getContent()` / `isError()` 三个 getter 由 Lombok `@Value` 生成)
2. 修改 `DefaultTurnContext.appendToolResult`,按 D-1 选 (a) — 暴露 `DefaultSession.appendLock()`(impl-only getter,参考 `runtimeSandbox()` precedent)
3. `DefaultSession.appendLock()` body 返回 `history` 字段(Object,类内可访问)— 或更精确:实现成 `new Object()` field 公开
4. `DefaultTurnContext.appendToolResult` 在该锁下扫描 history 末尾到上一个 assistant,upsert 或 append
5. 同步更新 `DefaultTurnContext.appendToolResult` 类级 Javadoc

**期望产出**:`DefaultTurnContext.appendToolResult` 实现变更 + 类级 Javadoc 说明 upsert 语义。

**R-13 mid checkpoint**: `mvn -pl lingshu-core compile` 通过(不跑 dep-tree,该阶段 0 binary 改动)。

### Phase 2 — `TurnContext.appendToolResult` Javadoc 更新

**操作**:
1. 读 §3.1 注解 new copy
2. 替换 `TurnContext.java` L75 的 Javadoc
3. 无业务逻辑改动

**期望产出**:`TurnContext.appendToolResult` 文档 +18 / 0 行代码。

### Phase 3 — `LinearTurnEngine.dispatchWithPolicy` AskUser 分支加 sentinel

**操作**:
1. 读 §3.3 sentinel 提交代码块
2. 定位 `LinearTurnEngine.java` L620-660 AskUser 分支
3. 在 `Decision resolved = toolCtx.approval().ask(...)` 之前插入 sentinel 构造 + `ctx.appendToolResult(sentinel)`
4. sentinel 构造 import `ToolResult.builder()` 已存在 (`ToolResult` 类已 import — 验证 grep)
5. 验证第 4 关键字段契约:`toolUseId = call.getId()` / `status = OK` / `isError = false` / `content = "[PENDING_APPROVAL] Awaiting user decision for tool: " + call.getName()`

**期望产出**:`LinearTurnEngine.java` L630 前后 +18 行 + 类级 Javadoc 增 `Story #048 🆕 AskUser hang safety` 段。

**R-13 mid checkpoint**: `mvn -pl lingshu-core compile` 通过。

### Phase 4 — 测试三件套

**操作**:
1. 新建 `DefaultTurnContextUpsertTest.java`,3 L1(AC-048-01/02/03)
2. 修改 `LinearTurnEngineAskUserTest.java`(已存在 by Story #030 + Story #041),追加 4 L2 测试(AC-048-04/05/06/07)
3. 新建 `LinearTurnEngineAskUserNoRenderIT.java`,1 L3 IT(AC-048-08 + mock Anthropic HttpServer 接 POST + 验 request body messages[] 无 unmatched tool_use)
4. 跑 `mvn -pl lingshu-core test`,verify 全部 761+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing(CLAUDE.md 已记录)
5. 跑 `mvn -pl lingshu-core verify`,verify 编译 + tests

**期望产出**:6 net new 测试 case 落地。

### Phase 5 — R-13 post-flight baseline 镜像

**操作**:按 §4 步骤。

**期望产出**:`pre.md5 == post.md5` 仅时间戳差异,第 31 次 PASS 0 binary delta。

### Phase 6 — git commit(与 Story #047 合并)

**操作**:按 §6 git 工作流。

**期望产出**:1 commit 同时覆盖 Story #047 + Story #048,commit message 含 R-13 标记。

---

## 7.2 exit criteria

- [ ] Phase 1-5 全部完成
- [ ] 13 AC 全部 PASS
- [ ] R-13 baseline PASS 第 31 次 0 binary delta
- [ ] `mvn -pl lingshu-core verify` 0 fail / 0 error
- [ ] Story #047 + #048 共 commit 完成
- [ ] README.md / specs/ROADMAP.md / constitution.md / dsh_agent_design.md 同步
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节

---

## 8. budget guards(CLAUDE.md §11 #4)

| 项 | 预算 | 状态 |
|---|---|---|
| 核心文件改动 | ≤ 5 | **6**(含 TurnContext Javadoc 单语义注解不计入 实质 = 3)| ⚠ borderline,Javadoc 调整不计实质 |
| ErrorCode 引入 | ≤ 3 | **0**(复用 LINGS-P02 Story #030)| ✅ |
| Maven 依赖新增 | 0 | **0** | ✅ |
| spec 总行数 | ≤ 1500 | spec.md ~200 + plan.md ~300 + tasks.md ~400 = ~900 | ✅ |

---

## 9. References

- spec.md(WHO/WHY/WHAT/AC 四段式)
- tasks.md(24 steps 跨 6 phases + Phase 0 pre-flight + 13 AC ↔ task mapping)
- dsh §6.1 L308(appendAssistant before dispatchParallel)— 根因锚点
- dsh §6.1 L322-324(appendToolResult main loop)— main loop 不变原则
- dsh §4.10.1 硬规则 1+2(ReAct Loop 自实现 / Spring AI 禁止)
- CLAUDE.md §11 #4/#6(Story 边界 / dep-tree 自查)
