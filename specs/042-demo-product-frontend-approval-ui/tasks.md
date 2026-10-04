# Story #042 `demo-product-frontend-approval-ui` — Tasks

> **Status**: Draft 2026-10-04
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 plan**: [`plan.md`](./plan.md)
> **完成定义**: T-1 ~ T-6 全 ✅ + 人工端到端 7 步清单全过

---

## T-1: index.html CSS Allow/Deny 按钮样式

**路径**: `lingshu-examples/demo-product/src/main/resources/static/index.html`

**操作**: 在 `.ev.approval { border-left-color: #db61a2; }`(L26)后插入 4 段 CSS:

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

**验证**:
- `grep -c 'btn-allow\|btn-deny' index.html` → 6+ hit
- 浏览器 DevTools 查看 `.ev.approval` 行 padding 6px 8px 生效

---

## T-2: app.js handleEvent() approval 分支 + postDecision() 辅助

**路径**: `lingshu-examples/demo-product/src/main/resources/static/app.js`

**操作 1**: 在 `handleEvent()` 方法(L89-115)内,`else if (eventName === 'compacted')` 后插入新分支(参考 plan §2.1 完整代码块)

**操作 2**: 在 `appendMsg()` 函数(L117-124)后插入 `postDecision()` 辅助函数(参考 plan §2.1)

**验证**:
- `grep -c 'postDecision\|btn-allow' app.js` → 4+ hit
- `grep -c 'eventName === .approval.' app.js` → 1 hit

---

## T-3: 人工端到端验证(7 步清单)

**前置**:
- `cd lingshu-examples/demo-product && mvn spring-boot:run`(确保 `permission-policy: ask` + `tools.ask-list: [write_file, bash_safe]` 配置生效)
- 浏览器打开 `http://localhost:8080`
- localStorage 清空(新 session)

**步骤**:

1. ✅ **T-3.1**: prompt 「请创建一个 demo.txt」→ events 流出现 `approval` 事件行
2. ✅ **T-3.2**: 验证 `approval` 行内嵌 `[Allow]`(绿)/`[Deny]`(红)两个按钮 + `reason` 输入框
3. ✅ **T-3.3**: 点 `[Allow]` → SSE 流恢复 → 后续 `tool-result` + `turn.completed` 出现
4. ✅ **T-3.4**: 重启会话,prompt 同上 → 点 `[Deny]`(输入 reason「不需要」)→ SSE 流恢复 + ToolResult.error + turn.completed
5. ✅ **T-3.5**: 按钮点击后立即 disable(visual: 按钮变灰 + 文字变「✓ Allowed」/「✗ Denied」)
6. ✅ **T-3.6**: 多次 approval 并行(ask-list 含多 Tool)— 每个 approvalId 独立按钮组
7. ✅ **T-3.7**: 后端超时场景(配 `approval-timeout: 1`)→ 1s 后前端按钮 POST 应返 404 + 「approval expired」提示

**反向验证**:
- ✅ T-3.R1: `text` 事件(LLM 流式文本)→ 助手 bubble 仍正常追加,无影响
- ✅ T-3.R2: `turn.completed` 事件 → bubble 仍正常追加 `\n— turn completed (...)`
- ✅ T-3.R3: `compacted` 事件 → bubble 仍正常追加 `\n— compacted (...)`

---

## T-4: 编译 + 静态资源无破坏

```bash
cd lingshu-examples/demo-product
mvn -pl . -am compile -DskipTests
```

期望: BUILD SUCCESS,前端静态资源(`app.js` + `index.html`)由 Maven resources plugin 复制到 `target/classes/static/` 不被破坏

**验证**:
```bash
ls -la target/classes/static/app.js target/classes/static/index.html
```

两者都存在 + 文件大小与改动后一致(无 Maven 编码 BOM 损坏)

---

## T-5: R-13 mitigation (d) 自查

**核心声明**: 纯前端 demo 改动,0 新 Maven 依赖,0 新 ErrorCode,0 新 npm 依赖

**自查清单**:
- [x] `app.js` 无 `import` / `require` 语句(vanilla JS,无模块依赖)
- [x] `index.html` 无 `<script src="external">` 引用
- [x] 无新 `pom.xml` 依赖
- [x] 无新 `package.json`(Lingshu 应用无 npm 配置)
- [x] `mvn -pl lingshu-examples/demo-product dependency:tree` pre/post diff = 仅时间戳差异 = 0 binary delta
- [x] `banned-dependencies` enforcer Rule 0 passed
- [x] `package-info.java` / 新 Java 源文件 0

**PR body 末尾**:
```markdown
### R-13 dependency:tree 自查

纯前端 demo 改动(`app.js` + `index.html` 静态资源,2 文件 / +50 行 / 0 行后端 Java 改动):
- 0 新 Maven 依赖
- 0 新 ErrorCode
- 0 新 npm 依赖
- `mvn -pl lingshu-examples/demo-product dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta 第 25 次 PASS
- `banned-dependencies` enforcer Rule 0 passed
```

---

## T-6: PR + 合入 + 文档同步

### T-6.1: 提交 + PR

```bash
git checkout -b feat/demo-product-frontend-approval-ui
git add lingshu-examples/demo-product/src/main/resources/static/app.js \
        lingshu-examples/demo-product/src/main/resources/static/index.html \
        specs/042-demo-product-frontend-approval-ui/{spec,plan,tasks}.md
git commit -m "feat(demo-product): Story #042 frontend-approval-ui — Allow/Deny buttons for ApprovalRequired SSE events

Story #030 + #041 后端 ApprovalGate + AskUserPermissionPolicy + ApprovalRegistry
完整落地,但 demo-product 前端 app.js 缺失 UI。本 Story 2 文件改动 +50 行:
  - app.js handleEvent() 增 approval 分支 + postDecision() 辅助
  - index.html 加 .btn-allow / .btn-deny / .reason / .approval-err CSS

后端契约 0 改动,9 Slot + AgentConfig 不可变契约不变,R-13 0 binary delta 第 25 次 PASS。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

**PR 标题**: `feat(demo-product): Story #042 frontend-approval-ui — Allow/Deny buttons for ApprovalRequired SSE`

**PR body 模板**:
- spec.md / plan.md / tasks.md 三件套链接
- AC-NN-1 ~ AC-NN-8 验证输出
- 反向 AC 验证
- R-13 dependency:tree 自查节
- 关键不变项列表

### T-6.2: 合入后同步

- [ ] `lingshu-examples/demo-product/README.md`(如有)顶部更新日期 + Story #042 blockquote
- [ ] `specs/ROADMAP.md` 段一 ✅ 已完成 加 #042 行
- [ ] `specs/ROADMAP.md` 段二 🟡 待补 #042 划掉
- [ ] `specs/ROADMAP.md` 段五 🎯 实施节奏 累计计数 41 → 42
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 #042 行(第 25 次 PASS 0 binary delta)
- [ ] `dsh_agent_design.md` §13 changelog 加 v1.5.55 行(本 Story 完成记录)
- [ ] CLAUDE.md / SKILL / SOP / prompts 版本号同步(如有)

---

## 完成定义

- T-1 ~ T-6 全 ✅
- 人工端到端 7 步清单全过
- 后端 32 case + 1 L3 demo IT 全过(0 改动)
- R-13 mitigation (d) baseline 镜像 pre/post dep-tree 仅时间戳差异 PASS 0 binary delta 第 25 次
- 0 新 ErrorCode / 0 新 Maven 依赖 / 0 新 npm 依赖
- PR merged + 文档同步完成