# Story #052 — Plan

> 实施路径 / 决策点 / 镜像 precedent / R-13 mitigation (d) baseline 镜像 pre-flight / demo-product 人工端到端 7 步清单 + Phase 0 instrumentation(诊断) + Phase 1 边界 sentinel(修复) **合并提交**。

---

## 1. 实施阶段总览(2 phase,phase 0 → phase 1 顺序;阶段内串行;commit 阶段合并)

| Phase | 名称 | 文档预加载 | 内容概要 | R-13 节点 |
|---|---|---|---|---|
| 0 | instrumentation 诊断(0 行 prod 改动) | 读 §3.1 验证步骤 | LinearTurnEngine / AnthropicLlmProvider / DefaultTurnContext 3 文件加 DEBUG 日志 + 跑 demo-product 11 步并行 tool_call 场景 + log4j 输出过滤定位 hypothesis | — |
| 1 | 实施边界 sentinel(假设 Phase 0 锁 A) | 读 §3.2 / §3.3 / §3.4 / §3.5 | dispatchParallel 主循环 L515-544 边界 sentinel + dispatchCancelledResult private helper + waitForTool Javadoc 契约加强 + (条件)LlmErrorCodes.LINGS_L04 新增 | R-13 mid checkpoint |
| 2 | 测试三件套 | AC §4.1 / §4.2 / §4.3 | `LinearTurnEngineDispatchParallelProtocolTest` 6 L2 + `LinearTurnEngineProtocolInvariantIT` 1 L3 | — |
| 3 | R-13 post-flight baseline 镜像 | `mvn -pl lingshu-core dependency:tree` 输出存 `post.txt`,`md5sum pre.txt post.txt` 对比仅时间戳差异 | **第 34 次 PASS 0 binary delta** | — |
| 4 | Phase 0 + Phase 1 + Phase 2 合并 git commit | §7 commit message 模板 | 1 个 commit 同时覆盖 Phase 0 instrumentation + Phase 1 sentinel + Phase 2 测试 | — |

**为什么合并 commit**:Phase 0 instrumentation 是验证性临时 DEBUG 日志,**不应** ship 进 main branch;Phase 0 + Phase 1 必须在同一 commit 里一起落地,避免 instrumentation 单独 PR 滞留。

---

## 2. 决策点表(实施期 5 个决策)

| D | 决策 | 选项 | 推荐 | 实施期可调整 |
|---|---|---|---|---|
| D-1 | Phase 0 hypothesis 锁定 | (a) 锁 A — dispatchParallel 主循环边界 sentinel<br>(b) 锁 B — Anthropic parser 修补<br>(c) 锁 A+B 双层 | (a) 锁 A 为大概率(spec.md §1.2 证据链 SSE 事件缺失);但 (c) 需 Phase 0 实测 | ✓(Phase 0 决定)|
| D-2 | 新增 ErrorCode `LINGS-L04` vs 复用现有 ErrorCode | (a) 新增 `LINGS-L04 PARALLEL_DISPATCH_RACE`(L 段 4 号,镜像 #027a L01/L02)<br>(b) 复用现有 `LINGS-L05 LLM_RESPONSE_MALFORMED`(语义不对,拒)<br>(c) 不加 ErrorCode,只用 sentinel content 文本 | (a) 新增 `LINGS-L04`(语义对齐 boundary sentinel 场景,与 L01/L02 同 L 段同 pattern) | ✗ — 锁定 |
| D-3 | 边界 sentinel content 格式 | (a) `"[" + LINGS_L04 + "] tool dispatch returned null"`<br>(b) `"[" + LINGS_L04 + "] tool dispatch returned wrong toolUseId: <actual>"`<br>(c) `"cancelled before tool dispatch"`<br>(d) `"cancelled: <cause>"` | (a)/(b) 区分边界 sentinel vs 普通 cancelled;让 LLM 看到 `[LINGS-L04]` 前缀时识别这是 protocol mismatch 兜底而非 user-initiated cancel | ✗ — 锁定 |
| D-4 | waitForTool 返回 null 处理 | (a) throw IllegalStateException 抛上层 catch(性能差且破坏契约)<br>(b) 主循环边界检测 `r == null` 兜底 | (b) 主循环兜底,不破坏 waitForTool 契约(Javadoc 契约加强即可) | ✗ — 锁定 |
| D-5 | toolUseId mismatch 比较方式 | (a) `expectedId.equals(r.getToolUseId())`(NPE 风险若 r.toolUseId null)<br>(b) `!Objects.equals(expectedId, r.getToolUseId())`(JDK 8 已锁,安全) | (b) JDK 8 `java.util.Objects.equals` 已锁 0 新 binary | ✗ — 锁定 |

---

## 3. precedent 镜像表(Story #030 / #041 / #048 / #049 / #051 pattern)

| Story 期间模式 | 当前 Story #052 镜像 |
|---|---|
| Story #030 ask-mode `Decision.AskUser` 阻塞-解阻塞 + 兜底 | **镜像进 dispatchParallel 主循环边界 sentinel** —— `waitForTool` 返回 null / toolUseId mismatch 时,兜底 sentinel `ToolResult.error("[LINGS-L04] ...")`,保证 `results[i]` 永不为 null + toolUseId 永不为 null |
| Story #048 ask-user-sentinel upsert-by-toolUseId | 镜像进 boundary sentinel 关键字段契约:`status = ERROR` / `isError = true` / `toolUseId = calls.get(i).getId()` / `content = "[LINGS-L04] tool dispatch returned null/wrong toolUseId"` |
| Story #049 `runBlocking()` 100% 同步路径保留 reverse AC | 镜像进 `waitForTool` 不动实现只加契约 Javadoc 文档化(spec.md §3.4),既有 `LinearTurnEngineCancellationTest` 等 6 个测试文件 0 改动全过 |
| Story #051 pre-Option-A engine-guard 计划被用户撤销 | 镜像进 **本 Story 不属 LLM 行为问题(verification loop / retry loop),而是 protocol invariant 漏 commit** —— 区别于 #051,本 Story 走 Option A engine 边界 sentinel 是**正确**的;memory `feedback-engine-guard-vs-prompt-fix.md` 仅覆盖 LLM 行为问题,不覆盖协议层 invariant |
| Story #030 `LINGS-P02 PERMISSION_APPROVAL_TIMEOUT` ErrorCode 引入 | 镜像进 `LINGS-L04 PARALLEL_DISPATCH_RACE` 新增模式 —— `LlmErrorCodes` 加 1 行常量,sentinel content 字符串前缀嵌 ErrorCode |

---

## 4. R-13 mitigation (d) baseline 镜像 pre-flight

### 4.1 步骤

```bash
# (1) 当前 pre-flight baseline(Story #051 实施完后)
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
mvn -pl lingshu-core dependency:tree > /tmp/dtree-052-pre.txt 2>&1
md5sum /tmp/dtree-052-pre.txt | tee /tmp/dtree-052-pre.md5

# (2) 实施 Story #052(Phase 0 + Phase 1 + Phase 2 完成)

# (3) post-flight baseline
mvn -pl lingshu-core dependency:tree > /tmp/dtree-052-post.txt 2>&1
md5sum /tmp/dtree-052-post.txt | tee /tmp/dtree-052-post.md5

# (4) 对比 — 仅时间戳差异
diff /tmp/dtree-052-pre.txt /tmp/dtree-052-post.txt
# 预期输出:仅 timestamp line 差异,无 binary 删除 / 新增
```

### 4.2 baseline 通过判据

- ✅ `pre.txt == post.txt` 除 timestamp
- ✅ `banned-dependencies` enforcer Rule 0: passed
- ✅ Story #052 累计 PASS 第 34 次 0 binary delta

### 4.3 失败兜底

- 如果 diff 显示新 binary delta → **立即停手**,查 `pom.xml` 是否失误新增 dep,或 spring-ai-bom 升级 → 撤回 + 重跑 baseline
- 第 33 次 PASS Story #051 baseline = 0 binary delta(re-meta 参考)

---

## 5. demo-product 人工端到端 7 步清单(Story #052 实施完成后)

(Spec 纯 dev-side 部署,forked user 建议跑一遍 demo-product 闭环验 Health 11 步并行 tool_call 场景。)

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | `mvn -pl lingshu-examples/demo-product spring-boot:run` | 启动成功,控制台 `resolved 1 flow engine provider(s)`,无 warn |
| 2 | 浏览器打开 `http://localhost:8080/`,确认 HTML 加载 + WebSocket/SSE ready | index.html 加载,EventSource ready |
| 3 | 配 `application.yml`:`permission-policy: default` + `tool-parallelism: 4`(原配)| yml 启动成功 |
| 4 | 在聊天输入 "写一首关于秋天的古诗七言四联写到 poem.txt" | SSE events 显示 9 个 bash_safe 步骤 + 第 11 步并行 tool_call 路径 |
| 5 | **观察 SSE trace**:第 11 步并行 tool_call(`call_00` + `call_01`)的 2 套 ToolStarted/ToolCompleted/ObservationAppended 事件都应出现 | SSE 事件流不再失踪 `call_00`(对比 Story #051 实施前 trace,`call_00` 整个生命周期失踪) |
| 6 | **关键**:turn 完成后,**不** reload 浏览器,**继续** 发下一句 "刚才那首诗写得如何"| 下一轮 Anthropic POST **不** 应报 HTTP 400「orphan tool_result」,正常返回 LLM 响应 |
| 7 | 验证 console log:第二 turn 启动时,**没有** `tool_use_id call_00_... found in tool_result blocks` 报错 | ✅ PASS = Story #052 落地 |

如有任何步骤异常,**立即停手**,回 Phase 0-1 排查。

---

## 6. Phase 0 + Phase 1 + Phase 2 合并 commit 的 git 工作流

```
# 实施完成 Story #052 后:
git status                                           # 检查所有改动
git add specs/052-dispatch-parallel-protocol-safety/ \   # Phase 0 spec/plan/tasks 三件套
    lingshu-core/src/main/java/.../                  # Phase 1 prod 文件改动
    lingshu-core/src/test/java/.../                  # Phase 2 test 文件改动
    README.md \                                      # Story #052 更新日期 + 路线图条目
    specs/ROADMAP.md \                               # 段一/段五 同步
    constitution.md \                                # §10 R-13 缓解 Story 列表补 #052 行
    dsh_agent_design.md \                            # §0 + §13 changelog v1.5.62 → v1.5.63 待实施后实际行号定
git commit -m "feat(agent): Story #052 ..."
# 注:**不** push,等用户指令
```

PR body 模板:
```markdown
## Story
- Story #052: dispatch-parallel-protocol-safety (Option A boundary sentinel)

## Test plan
- [ ] mvn -pl lingshu-core test — 786+ tests pass / 0 fail(7 new + 779 existing)
- [ ] mvn -pl lingshu-core dependency:tree baseline mirror

### R-13 dependency:tree 自查
pre.md5 = <hash>
post.md5 = <hash>
diff = 仅时间戳差异 = 0 binary delta 第 34 次 PASS
```

---

## 7.1 阶段路径决策(实施期每个 phase 入口)

### Phase 0 — instrumentation 诊断(0 行 prod 改动,纯 DEBUG 日志)

**入口条件**:Story #051 已合,Story #052 启动。

**操作**:
1. 读 §3.1 验证步骤(spec.md L111-127)
2. 改 `LinearTurnEngine.java`:dispatchParallel L498-512 提交前 + L515-528 主循环入口 + L529-543 waitForTool 调用前后 + L530-543 catch 块,加 DEBUG 日志(`LOG.debug("dispatchParallel before-wait idx={} callId={} futureDone={}", ...)`+ after-wait)
3. 改 `AnthropicLlmProvider.java`:parseResponse 入口加 DEBUG 日志(`LOG.debug("AnthropicLlmProvider.parseResponse tool_use_count={} firstId={}", ...)`)
4. 改 `DefaultTurnContext.java`:appendAssistant + appendToolResult 加 DEBUG 日志(打印 history.size() 变化前/后 + Assistant.toolCalls.size() / ToolResult_blocks.size())
5. 跑 demo-product 11 步并行 tool_call 场景("写一首关于秋天的古诗七言四联写到 poem.txt"),log4j 输出过滤看 DEBUG 日志
6. **决策**(D-1):
   - 若 `AnthropicLlmProvider.parseResponse` DEBUG 显示 parsed_count=2 → **Hypothesis B 不成立**,锁 A
   - 若 parsed_count=1 → **锁 B**,需追加 fix Anthropic parser(spec.md §3.5 不属于本 Phase 1 范围)
   - 若 dispatchParallel DEBUG 显示 `results[i]` 边界有 null / toolUseId null → **Hypothesis A 成立**,锁 A
   - 若两者都成立 → **锁 A+B 双层**,需两处 fix(Phase 1 scope expand)
7. **回退 Phase 0 instrumentation**(Edit 删 DEBUG 日志,**不** git commit 提交)—— 留着 Phase 1 实施时一起 commit

**期望产出**:Phase 0 instrumentation 跑完,DEBUG 日志分析报告(锁 A / B / A+B)+ 临时 DEBUG 日志保留在 working tree,**未** commit。

**R-13 mid checkpoint**: `mvn -pl lingshu-core compile` 通过(DEBUG 日志不引入 binary 改动)。

### Phase 1 — 实施边界 sentinel(假设 Phase 0 锁 A)

**入口条件**:Phase 0 instrumentation 验证 hypothesis A 成立。

**操作**:
1. 读 §3.2(spec.md L159-195),核对边界 sentinel Before/After 完整代码块
2. 改 `LinearTurnEngine.java`:
   - 删除 Phase 0 临时 DEBUG 日志(防止 ship 进 main)
   - 主循环 L515-544 加 boundary sentinel:`r == null` → `dispatchCancelledResult(expectedId, "[" + LINGS_L04 + "] tool dispatch returned null")`
   - 主循环加 toolUseId mismatch 检查:`!Objects.equals(expectedId, r.getToolUseId())` → `dispatchCancelledResult(expectedId, "[" + LINGS_L04 + "] tool dispatch returned wrong toolUseId: " + r.getToolUseId())`
3. 改 `LinearTurnEngine.java` 加 `dispatchCancelledResult` private static 静态 helper(spec.md §3.3 L205-214)
4. 改 `LinearTurnEngine.java` `waitForTool` 加 Javadoc 契约加强(spec.md §3.4 L231-241),实现**不**改
5. (若 Phase 0 决定需要新 ErrorCode)改 `LlmErrorCodes.java` 加 `LINGS_L04 = "LINGS-L04"` 常量(spec.md §3.5 L246-254)
6. 同步更新 `LinearTurnEngine.dispatchParallel` 类级 Javadoc `🆕 Story #052` marker

**期望产出**:2 个 prod 文件改动(`LinearTurnEngine.java` 主循环 + helper + waitForTool Javadoc;可选 `LlmErrorCodes.java` 加常量)+ 类级 Javadoc 增 `Story #052 🆕 boundary sentinel protocol safety` 段。

**R-13 mid checkpoint**: `mvn -pl lingshu-core compile` 通过。

### Phase 2 — 测试三件套

**操作**:
1. 新建 `LinearTurnEngineDispatchParallelProtocolTest.java`,6 L2(AC-052-01 至 AC-052-06)
   - mock `LlmProvider` 返回 N 个并行 tool_use(2-4 个,AC-052-01 happy path / AC-052-02 race condition 1 tool 抛 RuntimeException / AC-052-03 AskUser mode / AC-052-04 cancel-before-wait / AC-052-05 timeout 1 tool / AC-052-06 全 cancelled)
2. 新建 `LinearTurnEngineProtocolInvariantIT.java`,1 L3 IT(AC-052-07)mock Anthropic HttpServer 接 POST + 验证 request body messages[] 无 orphan tool_result
3. 跑 `mvn -pl lingshu-core test`,verify 全部 786+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing(CLAUDE.md 已记录)
4. 跑 `mvn -pl lingshu-core verify`,verify 编译 + tests

**期望产出**:7 net new 测试 case 落地(6 L2 + 1 L3)。

### Phase 3 — R-13 post-flight baseline 镜像

**操作**:按 §4 步骤。

**期望产出**:`pre.md5 == post.md5` 仅时间戳差异,第 34 次 PASS 0 binary delta。

### Phase 4 — git commit(Phase 0 instrumentation 已回退,只 Phase 1 + Phase 2 + spec 三件套)

**操作**:按 §6 git 工作流。

**期望产出**:1 commit 同时覆盖 Phase 1 sentinel + Phase 2 测试 + spec/plan/tasks + README.md / specs/ROADMAP.md / constitution.md / dsh_agent_design.md 文档同步,commit message 含 R-13 标记。

---

## 7.2 exit criteria

- [ ] Phase 0 instrumentation 跑完 + 锁定 hypothesis A
- [ ] Phase 1 + Phase 2 全部完成
- [ ] 12 AC 全部 PASS(7 业务 AC + 5 工程基线)
- [ ] R-13 baseline PASS 第 34 次 0 binary delta
- [ ] `mvn -pl lingshu-core verify` 0 fail / 0 error
- [ ] Phase 0 + Phase 1 + Phase 2 合并 commit 完成
- [ ] README.md / specs/ROADMAP.md / constitution.md / dsh_agent_design.md 同步
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节
- [ ] demo-product 人工端到端 7 步清单全部 PASS

---

## 8. budget guards(CLAUDE.md §11 #4)

| 项 | 预算 | 状态 |
|---|---|---|
| 核心文件改动(Phase 1)| ≤ 5 | **2-3**(`LinearTurnEngine.java` + 可选 `LlmErrorCodes.java`)| ✅ |
| ErrorCode 引入 | ≤ 3 | **0-1**(可选 `LINGS-L04`,复用 #027a L 段模式)| ✅ |
| Maven 依赖新增 | 0 | **0** | ✅ |
| spec 总行数 | ≤ 1500 | spec.md ~369 + plan.md ~280 + tasks.md ~380 = ~1029 | ✅ |

---

## 9. References

- spec.md(WHO/WHY/WHAT/AC 四段式)
- tasks.md(24 steps 跨 4 phases + Phase 0 pre-flight + 12 AC ↔ task mapping)
- dsh §6.1 L484-546(`LinearTurnEngine.dispatchParallel` 主循环)
- dsh §6.1 L515-528(主循环 cancel-before-wait + waitForTool 边界)
- dsh §6.1 L529-543(catch RuntimeException 兜底)
- dsh §6.1 L478-480(LLM-return order FR-003 契约)
- dsh §15.3 L 段编号(L01-L18 reserved 占位)
- memory `feedback-engine-guard-vs-prompt-fix.md`(LLM 行为问题 ≠ engine guard;本 Story 是 protocol invariant 漏 commit,不属于 LLM 行为)
- Story #048 ask-user-sentinel precedent(sentinel 关键字段契约:`status = ERROR` / `isError = true` / `toolUseId` 配对)
- Story #030 `LINGS-P02` 引入 + Story #027a `LINGS-L01/L02` 引入(ErrorCode 新增 precedent)
- Story #049 `runBlocking()` reverse AC 模式(`waitForTool` 不动实现只加 Javadoc 契约文档化)
- CLAUDE.md §11 #4/#6(Story 边界 / dep-tree 自查)