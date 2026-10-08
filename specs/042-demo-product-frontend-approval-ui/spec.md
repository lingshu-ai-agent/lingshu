# Story #042 `demo-product-frontend-approval-ui` — Spec

> **Status**: Draft 2026-10-04
> **Source**: dsh v1.5.54 §4.4 AgentEvent.ApprovalRequired + §4.7 PermissionPolicy + §9.3 Tool+Approval 时序图 + CLAUDE.md §13 v1.3.49 标注「Story #030 + #041 后端 ApprovalGate 已落,demo-product SSE 链路 ApprovalRequired 真接通,但前端 `app.js` 仅在 events 面板打印原始 JSON,无 Allow/Deny 操作 UI」
> **前置依赖**: `#030` permission-policy-ask-user(2026-10-02 已合,ChatController `POST /api/approvals/{sessionId}/{approvalId}` 端点已落,1 L3 demo blackbox 已 PASS)+ `#041` approval-gate-wiring-cleanup(2026-10-03 已合,ApprovalGate SPI 抽离 + DefaultApprovalGate private static final inner class + 2 latent bug 修复 + 9 case AC 黑盒 + R-13 0 binary delta 第 24 次 PASS)
> **本 Story 体量**: 2 modify(app.js + index.html)+ 0 new test file + 0 new ErrorCode + 0 new Maven dep —— 严格 ≤ 5 文件边界内

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.54 §4.4 + §4.7 + §9.3
- **实测发现**(2026-10-04 用户反馈「查看 demo-product 后端是否支持审批 round-trip,前端无 UI」):
  - `ChatController.java` L140-175 `deliverApproval` 端点已落(Story #030 + #041 实施)
  - `AgentEventMapper.java` L72-80 `ApprovalRequired` → SSE `{type:"approval", approvalId:"<uuid>", ask:{...}}` 映射已落
  - `app.js` L89-115 `handleEvent()` 通用打印逻辑 `ev.textContent = eventName + '  ' + JSON.stringify(data)` —— **对 `approval` 事件仅显示原始 JSON,无任何 UI 操作**
  - `index.html` L26 已有 `.ev.approval { border-left-color: #db61a2; }` 配色 —— **无 button 元素**
- **业务后果**(当前状态):
  - 用户启动 demo-product 配 `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]` 后
  - Agent 调 `bash_safe` 时 SSE 流发 `approval` 事件到前端
  - 前端**仅显示** `approval  {"approvalId":"...","ask":{...}}` 原始 JSON 文本,**无任何 accept/decline 入口**
  - 用户必须打开 DevTools 手动 `fetch('/api/approvals/...', {method:'POST', body:'{"decision":"allow"}'})` 才能 approve
  - approval 事件后 turn 永久 hang(LinearTurnEngine 阻塞)直到用户手动 fetch,体验**完全残缺**
  - Story #041 已落 1 L3 demo wiring IT(`DemoProductAskUserSpiWiringIT`),**测试代码直接 fetch 模拟前端** —— 真实前端 UI 仍缺失
- **对应风险**: **R-04**(privilege escalation — 分值 8,部分缓解 — 后端可工作但演示入口缺失,无法形成「可见可用的安全审批」端到端体验)
- **涉及 ErrorCode**: **0 新 ErrorCode**(复用现有错误码体系)

---

## 1. WHY(为什么做这个 Story)

**核心问题**: Story #030 + #041 后端 ApprovalGate 完整落地(`Decision.AskUser` 3rd outcome 真接通 + ApprovalRegistry 终局收集器 + ChatController SSE round-trip + ApprovalGate SPI 抽离 + 2 latent bug 修复),但 demo-product 前端 `app.js` 缺失 UI:

1. SSE `approval` 事件仅显示原始 JSON,无 Allow/Deny 操作按钮
2. 即使看到事件,用户**必须**打开 DevTools 手动 fetch 才能 approve
3. Story #041 验收需要 demo-product L3 round-trip(已 PASS),但**真实用户可见的 UI 仍残缺**
4. demo-product 作为 LingShu 完整 demo 范例,前端残缺会被新用户误判「ask-mode 还没完工」

**Story #042 业务价值**:

- **Allow/Deny 按钮 UI** —— `app.js` SSE 事件处理器扩 `else if (eventName === 'approval')` 分支,事件流面板行内动态插入两个按钮
- **Allow/Deny → fetch POST** —— 点击触发 `fetch('/api/approvals/' + sessionId + '/' + approvalId, {method:'POST', headers:{'Content-Type':'application/json'}, body: JSON.stringify({decision:'allow'|'deny', reason: optional})})`,后端 SSE 流恢复,events 面板后续事件继续追加
- **按钮 disable 防双击** —— 一次点击后 disable 两个按钮,文字变为「已 Allow」/「已 Deny」,防止重复 POST(ApprovalRegistry.consume 已做幂等但前端 UX 更友好)
- **多 approval 串行** —— 每个 approvalId 独立按钮组(SSE 流的事件天然区分,无共享状态)
- **deny reason 输入** —— 可选 prompt 输入框,Den y 时携带 reason 字段,后端 `Decision.Deny(reason)` 嵌入

**关键不变项**:

- `ChatController.java` `deliverApproval` 端点契约 — **0 改动**(Story #030 + #041 已稳定,body `{decision:"allow"|"deny", reason:"..."}` 协议锁定)
- `AgentEventMapper.java` ApprovalRequired 映射 — **0 改动**(`{type:"approval", approvalId, ask}` 字段稳定)
- `ApprovalRegistry` + `Decision` + `AgentEvent.ApprovalRequired` — **0 改动**(纯后端,本 Story 触及不到)
- 后端 `permission-policy: ask` + `tools.ask-list` yml 配置 — **0 改动**
- 现有 L1/L2/L3 测试用例 — **0 改动**(Story #030 23 case + Story #041 9 case 全部不动)
- 9 Slot 顶层不变 / `AgentConfig` 不可变契约不变 / JDK 8 不变(本 Story 是前端 vanilla JS,不动后端 Java)
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **demo-product 用户(直接交互)** | 点 Allow / Deny 按钮调 write_file / bash_safe,无需打开 DevTools,完整体验 ask-mode 流程 |
| **Story #030 / #041 实施者 / reviewer** | 跑 demo-product 端到端 L3 round-trip,前端 UI 完整,人工验证路径真实可走 |
| **新用户 / 社区审稿者** | demo-product 是 LingShu 完整 demo 范例,Story #042 后 UI 全,看完 demo 即可理解 ask-mode 工作机制 |
| **企业部署 PoC 决策者** | 配 `permission-policy: ask` 后可现场给非工程背景决策者演示「Tool 调需人工审批」安全机制 |

---

## 3. WHAT(交付什么 — 用户视角)

### 3.1 用户可见行为

1. 用户发 prompt「请帮我创建一个 demo.txt」→ SSE 流推送 tool-start(`write_file`)→ ApprovalRequired 事件(`type:"approval"`)
2. events 面板 `approval` 行**内嵌**两个按钮:**[Allow]** 绿色 / **[Deny]** 红色
3. 用户点 **[Allow]** → fetch POST → 后端解阻塞 → SSE 流继续推 tool-result → turn.completed
4. 或用户点 **[Deny]** → fetch POST body 含 reason「不需要这个文件」 → SSE 流继续推 tool-result.error → turn.completed
5. 按钮点击后立即 disable,文字变「已 Allow」/「已 Deny」

### 3.2 用户不可见行为(内部契约)

- 后端协议完全沿用 Story #030 + #041 已落契约
- 前端零新依赖(vanilla JS,无 npm)
- 前端零新 Maven 依赖(纯静态资源)

---

## 4. ACCEPTANCE CRITERIA(验收标准)

**AC-NN-1**: SSE `approval` 事件触达前端后,事件流面板**必须**显示 `[Allow]` / `[Deny]` 两个按钮 —— 视觉验证(L1)

**AC-NN-2**: 点 `[Allow]` 按钮 → `POST /api/approvals/{sessionId}/{approvalId}` body `{decision:"allow"}` Content-Type `application/json` → 后端 SSE 流恢复 → LLM 继续 dispatch → tool-result 事件 + turn.completed(L2)

**AC-NN-3**: 点 `[Deny]` 按钮 → `POST /api/approvals/{sessionId}/{approvalId}` body `{decision:"deny", reason:"<reason>"}` → 后端 SSE 流恢复 → LLM 收 `Decision.Deny(reason)` → ToolResult.error 带 reason → turn.completed(L2)

**AC-NN-4**: 按钮点击后**立即 disable** 两个按钮 + 文字变更,防止重复 POST(L1)

**AC-NN-5**: 多个 approval 事件**并行**触发时(LLM 多次 AskUser,ask-list 含多个 Tool),每个 approvalId **独立按钮组**,互不干扰(L2)

**AC-NN-6**: 后端 ApprovalRegistry 已过期(approval-timeout > 0 触发 + LLM 已收到 `[LINGS-P02]` Deny)场景下,前端按钮仍显示但 POST 返 404,前端优雅处理「approval 已过期」(L2 软失败)

**AC-NN-7**: 现有 `text` / `turn.completed` / `compacted` / `error` / `tool-start` / `tool-progress` / `tool-done` / `reasoning-start` / `observation` / `max-steps` 事件处理**完全不变**(RAC,无回归)

**AC-NN-8**: ChatController `deliverApproval` 端点契约**不变**(Story #030 + #041 锁定,body shape 不动,RAC)

### 反向 AC(不应做)

- **RAC-1**: 不引入新 Maven 依赖(R-13 mitigation (d) 强制)
- **RAC-2**: 不改 ChatController / AgentEventMapper / ApprovalRegistry / LinearTurnEngine(后端稳定)
- **RAC-3**: 不引入新 npm 依赖(vanilla JS 路线,沿用 Story #025)
- **RAC-4**: 不改 `application.yml` `permission-policy` / `tools.ask-list` 配置(已稳定)
- **RAC-5**: 不改 L1/L2/L3 任何测试 fixture(Story #030 + #041 已落测试 0 回归)

---

## 5. 文件清单

| 路径 | 改动 | 行数 |
|---|---|---|
| `lingshu-examples/demo-product/src/main/resources/static/app.js` | modify — `handleEvent()` 增 `else if (eventName === 'approval')` 分支 | ~+40 |
| `lingshu-examples/demo-product/src/main/resources/static/index.html` | modify — `<style>` 加 `.btn-allow` / `.btn-deny` 样式 | ~+10 |

**总计 2 文件改动,0 新文件,严格在 ≤ 5 文件 Story 边界内。**

---

## 6. 风险与缓解

| 风险 | 分值 | 缓解 |
|---|---|---|
| **R-04**(privilege escalation)| 8 | Story #042 完成后端到端可见 demo,大企业部署 PoC 可演示「Tool 调需人工审批」机制(Story #030 + #041 已落 32 + 9 = 41 case,Story #042 完成 100% 端到端可演示) |
| **R-13**(新依赖引入)| 8 | 0 新 Maven 依赖,0 新 npm 依赖,前端 vanilla JS 路线 |
| **R-19**(A2A wiring gap)| 9 | 不相关(后端 wiring Story #009e 已落) |

---

## 7. 验收要素映射到 R-13 mitigation (d)

**前端改动,无新 Maven 依赖,无新 ErrorCode**:
- `mvn dependency:tree` pre/post diff = 仅时间戳差异 = **0 binary delta 第 25 次 PASS** 预期
- `banned-dependencies` enforcer Rule 0 passed
- PR body 末尾 `### R-13 dependency:tree 自查` 节声明「纯前端 demo 改动,无新 binary 引入」

---

**修复者**: Claude Code(根据用户 2026-10-04 会话反馈「查看 demo-product 后端是否支持审批 round-trip,前端无 UI」+「需要补充 UI」+「开新 Story 吧」,触发本 Story 实施)