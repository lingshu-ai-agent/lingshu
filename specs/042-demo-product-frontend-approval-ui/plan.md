# Story #042 `demo-product-frontend-approval-ui` — Plan

> **Status**: Draft 2026-10-04
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 tasks**: [`tasks.md`](./tasks.md)
> **核心约束**: 纯前端 demo 改动,0 后端 Java 改动,0 新 Maven 依赖,0 新 ErrorCode,严格 ≤ 5 文件边界

---

## 1. 接口契约

### 1.1 后端契约(锁定,不改动)

```http
POST /api/approvals/{sessionId}/{approvalId}
Content-Type: application/json

{ "decision": "allow" | "deny", "reason": "<optional string>" }
```

(Story #030 + #041 已稳定,见 `ChatController.deliverApproval()` L140-175)

### 1.2 SSE 事件契约(锁定,不改动)

```
event: approval
data: {"type":"approval","approvalId":"<uuid>","ask":{"prompt":"...","options":[...],"defaultOption":"allow"}}
```

(`AgentEventMapper.toJson()` L72-80 已稳定)

### 1.3 前端契约(本 Story 新增)

`handleEvent()` 收到 `approval` 事件 → 事件流面板行内嵌两个按钮:

```js
ev.innerHTML = '<span>approval ' + data.approvalId.slice(0,8) + '</span>' +
               '<button class="btn-allow" data-aid="' + data.approvalId + '">Allow</button>' +
               '<button class="btn-deny"  data-aid="' + data.approvalId + '">Deny</button>' +
               '<input class="reason" placeholder="reason (optional)">';
```

按钮 onclick → `fetch(POST /api/approvals/{sid}/{aid}, {decision, reason})` → 按钮 disable。

---

## 2. 文件改动

### 2.1 `lingshu-examples/demo-product/src/main/resources/static/app.js`

**当前位置**: L89-115 `handleEvent()` 方法,仅通用打印。

**新增分支**: 在 `else if (eventName === 'compacted')` 后插入:

```js
} else if (eventName === 'approval') {
  var askPrompt = (data.ask && data.ask.prompt) ? data.ask.prompt : '(no prompt)';
  var aid = data.approvalId;
  ev.innerHTML = '';                                          // clear default JSON dump
  ev.appendChild(document.createTextNode('approval ' + aid.slice(0, 8) + ' — ' + askPrompt));
  var reasonInput = document.createElement('input');
  reasonInput.type = 'text';
  reasonInput.className = 'reason';
  reasonInput.placeholder = 'reason (optional)';
  ev.appendChild(reasonInput);
  var allowBtn = document.createElement('button');
  allowBtn.className = 'btn-allow';
  allowBtn.textContent = 'Allow';
  allowBtn.onclick = function () { postDecision(aid, 'allow', reasonInput.value, ev); };
  ev.appendChild(allowBtn);
  var denyBtn = document.createElement('button');
  denyBtn.className = 'btn-deny';
  denyBtn.textContent = 'Deny';
  denyBtn.onclick = function () { postDecision(aid, 'deny', reasonInput.value, ev); };
  ev.appendChild(denyBtn);
}
```

**新增辅助函数** (放在 `appendMsg` 之后):

```js
function postDecision(approvalId, decision, reason, rowEl) {
  var body = JSON.stringify({ decision: decision, reason: reason || '' });
  fetch('/api/approvals/' + sessionId + '/' + approvalId, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body
  }).then(function (r) {
    // Disable both buttons + inputs in this row
    var buttons = rowEl.querySelectorAll('button, input');
    buttons.forEach(function (b) { b.disabled = true; });
    // Visual feedback
    rowEl.querySelectorAll('button').forEach(function (b) {
      b.textContent = decision === 'allow' ? '✓ Allowed' : '✗ Denied';
    });
    if (!r.ok) {
      // 404 etc — backend already cleaned up registry; show soft error
      var err = document.createElement('span');
      err.className = 'approval-err';
      err.textContent = ' [approval expired (HTTP ' + r.status + ')]';
      rowEl.appendChild(err);
    }
  }).catch(function (err) {
    var e = document.createElement('span');
    e.className = 'approval-err';
    e.textContent = ' [network error: ' + err + ']';
    rowEl.appendChild(e);
  });
}
```

**变更后行数**: ~ 125 → ~ 165(+40 行)

### 2.2 `lingshu-examples/demo-product/src/index.html`

**当前位置**: L11-12 `.button { ... }` 通用按钮样式 + L26 `.ev.approval { border-left-color: #db61a2; }`

**新增样式**: 在 `.ev.approval { ... }` 后插入:

```css
.ev.approval { padding: 6px 8px; }
.ev.approval .reason { width: 200px; padding: 2px 6px; margin-right: 6px; background: #0d1117; color: #e6edf3; border: 1px solid #30363d; border-radius: 3px; font-size: 11px; }
.ev.approval .btn-allow { background: #238636; color: #fff; border: 1px solid #2ea043; margin-right: 4px; }
.ev.approval .btn-allow:hover { background: #2ea043; }
.ev.approval .btn-allow:disabled { background: #1a4d2a; cursor: not-allowed; }
.ev.approval .btn-deny  { background: #da3633; color: #fff; border: 1px solid #f85149; }
.ev.approval .btn-deny:hover  { background: #f85149; }
.ev.approval .btn-deny:disabled  { background: #4d1a1a; cursor: not-allowed; }
.ev.approval .approval-err { color: #f85149; font-size: 10px; margin-left: 6px; }
```

**变更后行数**: ~ 30 → ~ 40(+10 行)

---

## 3. 测试策略

### 3.1 自动化测试

**0 新 JUnit 用例**。前端 vanilla JS 改动不引入 L1/L2/L3 黑盒:

- **后端契约不变** — Story #030 + #041 已落 23 + 9 = 32 case + 1 L3 demo IT 全部不动,0 回归
- **前端逻辑简单** — `postDecision()` 是单 fetch POST + DOM disable,无复杂状态机,JS 单元测试在 demo-product 项目**未启用**(无 npm / 无 Jest 配置)
- **测试策略 = 人工端到端**:
  1. 启动 demo-product(`mvn spring-boot:run`)
  2. 浏览器打开 `http://localhost:8080`
  3. 发 prompt「请创建一个 demo.txt」(触发 write_file → ask-list 命中)
  4. 验证 events 面板 `approval` 行有 `[Allow]` / `[Deny]` 两个按钮 + reason 输入框
  5. 点 `[Allow]` → SSE 流恢复 → 后续 tool-result + turn.completed
  6. 重启会话,点 `[Deny]` → SSE 流恢复 + ToolResult.error + turn.completed
  7. 双击按钮 → 第二个 click 应被 disable 守卫(console.log 应只有一次 POST)

### 3.2 反向验证

- `grep -n 'type:"approval"' demo-product/src/main/java/.../AgentEventMapper.java` —— 字段契约不变
- `grep -n 'deliverApproval' demo-product/src/main/java/.../ChatController.java` —— 端点契约不变
- Story #030 + #041 已落测试 0 回归:`mvn -pl lingshu-examples/demo-product test` 全过
- 现有 `text` / `turn.completed` / `compacted` 事件分支代码路径不变(RAC-7)

---

## 4. 实施顺序

1. **T-1**: `index.html` 加 `.btn-allow` / `.btn-deny` / `.reason` / `.approval-err` 4 段 CSS(8 行)
2. **T-2**: `app.js` `handleEvent()` 增 `approval` 分支 + `postDecision()` 辅助函数(~40 行)
3. **T-3**: 人工端到端验证(7 步清单 §3.1)
4. **T-4**: `mvn -pl lingshu-examples/demo-product compile` 确保无静态资源破坏
5. **T-5**: R-13 mitigation (d) 自查 — 0 新 Maven 依赖,`mvn dependency:tree` pre/post diff 仅时间戳
6. **T-6**: PR + 合入 + dsh §13 changelog + `constitution.md` §10 R-13 缓解 Story 列表 + README.md 顶部更新日期 + SPECs/ROADMAP.md 段一 ✅ 已完成 + 段二 🟡 待补 #042 划掉

---

## 5. 关键不变项 + RAC 兜底

- `ChatController.deliverApproval` 端点契约不变 → 后端协议稳定,前端实现可独立演化
- `AgentEventMapper` ApprovalRequired 映射不变 → SSE event payload 稳定
- Story #030 23 case + Story #041 9 case 黑盒 0 改动 → 后端逻辑稳定,Story #042 仅 UX 层补完
- demo-product `permission-policy: ask` + `tools.ask-list` yml 配置不变 → 用户无须改任何 yml

---

## 6. 文件清单

| 路径 | 改动 |
|---|---|
| `lingshu-examples/demo-product/src/main/resources/static/app.js` | modify +40 行 |
| `lingshu-examples/demo-product/src/main/resources/static/index.html` | modify +10 行 |

**严格 ≤ 5 文件边界**(实际 2 文件,留 3 文件 buffer 应对未来 fix)。