# Story #049 — Plan

> 实施路径 / 决策点 / 镜像 precedent / R-13 mitigation (d) baseline 镜像 pre-flight / demo-product 人工端到端 7 步清单。
>
> **本期 vs. Story #001 时 `BufferedPublisher` 的关系**:`BufferedPublisher` 不删,`runBlocking` 仍用它(`runBlocking` 是 Story #001 同步路径的合法实现);**新增** `LiveRunPublisher` 给 `run()` 用。两条路径并存,**不**改造现有 fixture。

---

## 1. 实施阶段总览(4 phase,phase 内串行)

| Phase | 名称 | 内容概要 | R-13 节点 |
|---|---|---|---|
| 0 | pre-flight baseline 镜像 | md5sum 当前 `mvn -pl lingshu-core dependency:tree` 存 pre.txt | — |
| 1 | `DefaultAgent.run()` 替换 + `LiveRunPublisher` inner class | 唯一 prod 改动;`runBlocking` + `BufferedPublisher` 不动 | R-13 mid checkpoint:compile pass |
| 2 | 测试二件套(7 + 4 = 11 net new case) | `DefaultAgentLiveStreamingTest` 7 L1+L2 + `DefaultAgentRunBlockingUnchangedTest` 4 L1+L2 | — |
| 3 | R-13 post-flight + git commit | post.txt 与 pre.txt diff 仅时间戳 + commit message 模板 | R-13 mid checkpoint:32nd PASS 0 binary delta |

---

## 2. 决策点表(实施期 5 个决策)

| D | 决策 | 选项 | 推荐 | 实施期可调整 |
|---|---|---|---|---|
| D-1 | executor 选择:`Executors.newCachedThreadPool` (JDK) vs `ForkJoinPool.commonPool()` vs bounded `ThreadPoolExecutor`(Story #043 镜像) | (a) JDK `Executors.newCachedThreadPool(daemon factory)` (b) `ForkJoinPool.commonPool()` (c) Story #043 镜像 bounded pool | **(a) `newCachedThreadPool(daemon)`** —— demo-product 单 turn 1 long-lived thread,无并发爆发,无需 bound 限流;JDK 内置,无需自包装;cachedThreadPool 线程名前缀 `lingshu-agent-live-N` 方便排查;不引 `ThreadPoolExecutor` 新增文件依赖 | ✗ — 锁定 |
| D-2 | `LiveRunPublisher` 位置:`DefaultAgent` private static final inner class vs 单独 file `ai.lingshu.core.impl.runtime.LiveRunPublisher` | (a) private static final inner class (b) 单独 `.java` file in same package | **(a) private static final inner class** —— 与 `BufferedPublisher`(L275-296)对称;不引新文件(Lingshu convention 优先 nested);0 public API surface 增加 | ✗ — 锁定 |
| D-3 | single-subscriber 守卫 抛出方式:在 subscribe() 同步抛 vs 通过 `s.onError` 异步投递 | (a) 同步抛(会绕过 Reactive Streams spec 但实现简单)(b) 通过 onError 投递 IllegalStateException | **(b) 通过 onError 投递** —— Reactive Streams spec §2.2 推荐做法;subscribe 内部 `try { createSubscription; s.onSubscribe(...) } catch (...) { s.onError(...) }` 模式 | ✗ — 锁定 |
| D-4 | cancellation 传播:在 engine 内 `ctx.isCancelled()` 阶段调用 vs `Subscription.cancel()` 触发 `engine.interrupt()` 阻线程 vs 请求级 grant token | (a) `ctx.isCancelled()` polling(Story #005 AC-04 200ms 已落地) (b) `Thread.interrupt()` 配合 (c) request grant token | **(a) `ctx.isCancelled()` polling** —— Story #005 已建 200ms 轮询机制,本 Story 在 `LiveRunPublisher` cache `cancelled` flag;`engine.runTurn` 主循环每次迭代检查 → 200ms 内 SSE disconnect 生效;不动 Story #005 | ✗ — 锁定 |
| D-5 | back-pressure 实现:`AtomicLong requested` vs `LockSupport.parkNanos` vs reactive-streams 1.0.4 `Subscription` 协议实现 | (a) `AtomicLong requested` (b) `LockSupport.parkNanos` (c) 自定义 `Subscription` subclass | **(a) + (b) 组合** —— `AtomicLong requested` 计数(Reactive Streams spec §3.9 compliant);若 requested 0 → `LockSupport.parkNanos(50ms)` wait sub 增 requested;park 1s timeout 视为 sub 挂断调 cancel;完全 JDK 8 standard | ✗ — 锁定 |

---

## 3. precedent 镜像表(Story 模式 mapping)

| Story 期间模式 | 当前 Story #049 镜像 |
|---|---|
| Story #001 `BufferedPublisher` private static final inner class | 镜像进本期 `LiveRunPublisher` 同样 private static final inner class(`DefaultAgent.java` 内部) |
| Story #001 `runBlocking()` in-line drain + `BufferedPublisher` for sync | **不动** —— runBlocking 继续走这条路;reactive path 是新加的 |
| Story #005 `CancellationToken` 200ms polling `ctx.isCancelled()` | 镜像进本期 cancellation 传播:`LiveRunPublisher.cancelled` flag → engine 主循环 polling 检测 |
| Story #043 bounded `ThreadPoolExecutor` + CallerRunsPolicy | **不**镜像 —— 本期用 `Executors.newCachedThreadPool(daemon)` JDK 内置;demo-product 单 turn 1 thread 不需要 bound |
| Story #045 / #046 Spring 6 dual-ctor `@Autowired` 模式 | **不**镜像 —— 本期不动 ctor 不动 AgentFactory |
| Story #048 sentinel `ToolResult` in-place upsert | **不**镜像 —— 本期不动 `TurnContext.appendToolResult` |
| Story #001 + #020a + #020c fixture-compatible | 镜像进 `DefaultAgentRunBlockingUnchangedTest` 4 case 验证 `runBlocking` 行为字节级一致 |

---

## 4. R-13 mitigation (d) baseline 镜像 pre-flight

### 4.1 步骤

```bash
# (1) 当前 pre-flight baseline
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
mvn -pl lingshu-core dependency:tree > /tmp/dtree-049-pre.txt 2>&1
md5sum /tmp/dtree-049-pre.txt | tee /tmp/dtree-049-pre.md5

# (2) 实施 Story #049(Phase 1-2 完成)

# (3) post-flight baseline
mvn -pl lingshu-core dependency:tree > /tmp/dtree-049-post.txt 2>&1
md5sum /tmp/dtree-049-post.txt | tee /tmp/dtree-049-post.md5

# (4) 对比 — 仅时间戳差异
diff /tmp/dtree-049-pre.txt /tmp/dtree-049-post.txt
# 预期输出:仅 timestamp line 差异,无 binary 删除 / 新增
```

### 4.2 baseline 通过判据

- ✅ `pre.txt == post.txt` 除 timestamp
- ✅ `banned-dependencies` enforcer Rule 0: passed
- ✅ Story #049 累计 PASS 第 32 次 0 binary delta(继 Story #047 / Story #048 第 31 次)

### 4.3 失败兜底

- 如果 diff 显示新 binary delta → **立即停手**,查 `pom.xml` 是否失误新增 dep,或 spring-ai-bom 升级 → 撤回 + 重跑 baseline

---

## 5. demo-product 人工端到端 7 步清单

| 步骤 | 操作 | 预期 |
|---|---|---|
| 1 | `mvn -pl lingshu-examples/demo-product spring-boot:run` | 启动成功,控制台 `resolved 1 flow engine provider(s)`,无 warn |
| 2 | 浏览器打开 `http://localhost:8080/`,确认 HTML 加载 + SSE ready | index.html 加载,EventSource ready |
| 3 | 配 `application.yml`:`permission-policy: ask` + `tools.ask-list: [bash]` + `approval-timeout-seconds: 0`(Story #030 overnight parity 默认值)| yml 启动成功 |
| 4 | 在聊天输入 "what is 2+3, just text, no tools" | **关键**:右侧 SSE events 区**实时**看到 `ReasoningStarted` → 多个 `TextDelta`(每 delta 对应 1 个 token)| |
| 5 | 第一 token 出现时间与 LLM 真实首 token 时间差应 ≤ dsh NFR P50(1.5s) | ✅ = browser 实时流式 |
| 6 | 解 Allow/Deny 等待时 sentinel `[PENDING_APPROVAL]` 弹 SSE + 解阻塞后续 TextDelta 实时出现 | ✅ = Story #048 + #049 协同 |
| 7 | 浏览器**关闭** tab 后,SSE 连接断;curl `localhost:8080/api/sessions` 看内存泄漏监控:无 Agent thread 阻 | ✅ = cancellation 传播(间接)|

如有任何步骤异常,**立即停手**,回 Phase 1-2 排查。

---

## 6. git commit 工作流(单独 commit,不与 #047+#048 共)

```
# 实施完成 Story #049 后:
git status                                              # 检查所有改动
git add specs/049-agent-run-live-streaming/             # Phase 1 spec/plan/tasks 三件套
    lingshu-core/src/main/java/.../DefaultAgent.java    # Phase 1 prod 改动
    lingshu-core/src/test/java/.../*2 test files        # Phase 2 tests
    README.md                                           # Story #049 更新日期 + 路线图条目
    specs/ROADMAP.md                                    # 段一/段五 同步
    constitution.md                                     # §10 R-13 缓解 Story 列表补 #049 行
    dsh_agent_design.md                                 # §0 + §13 changelog v1.5.60 → v1.5.61
git commit -m "feat(agent): Story #049 ..."
# 注:**不** push,等用户指令
```

PR body 模板:
```markdown
## Story
- Story #049: agent-run-live-streaming (DefaultAgent.run() hot lazy Publisher)

## Test plan
- [ ] mvn -pl lingshu-core test — 761+ tests pass / 0 fail
- [ ] mvn -pl lingshu-core dependency:tree baseline mirror

### R-13 dependency:tree 自查
pre.md5 = <hash>
post.md5 = <hash>
diff = 仅时间戳差异 = 0 binary delta 第 32 次 PASS
```

---

## 7.1 phase 路径决策(实施期每个 phase 入口)

### Phase 1 — `DefaultAgent.run()` 替换 + `LiveRunPublisher` inner class

**入口条件**: `cd lingshu-core && mvn compile -q` 已通(基线落地)。

**操作**:
1. 读 §3.3 `LiveRunPublisher` 8 条关键约束
2. `mvn -pl lingshu-core compile -q` 现状通过
3. Edit `DefaultAgent.java`:
   - 顶部新增 import:`java.util.concurrent.ExecutorService` + `Executors` + `ThreadFactory` + `atomic` × 3 + `LockSupport`
   - 新增 `liveExecutor` 字段:`private final ExecutorService liveExecutor = Executors.newCachedThreadPool(daemonFactory("lingshu-agent-live"));`
   - 新增 `daemonFactory(String prefix)` 私有方法:`return new ThreadFactory() { ... daemon=true + AtomInteger count ... };`
   - 替换 `run(String)` 方法体:从 in-line drain 改 return `new LiveRunPublisher(liveExecutor, ctx)`
   - 新增 `private static final class LiveRunPublisher implements Publisher<AgentEvent>, Subscription { ... }`(完整 8 约束实现)
   - 类级 Javadoc 加 `🆕 Story #049 — `DefaultAgent.run()` 实时流式(Plan A)` 段

**期望产出**:`DefaultAgent.java` +~70 行,类级 Javadoc +30 行净增;`runBlocking` + `continueWithUserMessageBlocking` + `continueWithUserMessage` 三方法**0 改动**。

**R-13 mid checkpoint**: `mvn -pl lingshu-core compile -q` 通过(不跑 dep-tree,该阶段 0 binary 改动)。

### Phase 2 — 测试二件套

**操作**:
1. `DefaultAgentLiveStreamingTest.java` new — 7 case(AC-049-01—AC-049-07)
2. `DefaultAgentRunBlockingUnchangedTest.java` new — 4 case(AC-049-08—AC-049-11)
3. 跑 `mvn -pl lingshu-core test -Dtest='DefaultAgent*Test' -q` → 11 case 全过
4. 跑全 `mvn -pl lingshu-core test -q` → 761+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing

**期望产出**:11 net new case 落地。

**R-13 mid checkpoint**: `mvn -pl lingshu-core test` 全 761+ tests pass(含 11 新 case)。

### Phase 3 — R-13 post-flight baseline + git commit

**操作**: 按 §4 步骤 + §6 git 工作流。

**期望产出**: `pre.md5 == post.md5` 仅时间戳差异,第 32 次 PASS 0 binary delta + 1 commit。

---

## 7.2 exit criteria

- [ ] Phase 1-3 全部完成
- [ ] 16 AC 全部 PASS
- [ ] R-13 baseline PASS 第 32 次 0 binary delta
- [ ] `mvn -pl lingshu-core verify` 0 fail / 0 error
- [ ] git commit 完成(spec/plan/tasks + 1 prod + 2 test + docs sync)
- [ ] `ChatController` 0 行改动(本期不动 demo-product prod)
- [ ] `runBlocking` + `continueWithUserMessage` 行为 0 改动(4 backward compat test case 守)
- [ ] dsh §7.1 factory 模式守住(全部走 `Agent.run` API)
- [ ] README.md / specs/ROADMAP.md / constitution.md / dsh_agent_design.md 同步
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节

---

## 8. budget guards(CLAUDE.md §11 #4)

| 项 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件改动 | ≤ 5 | **3**(1 prod + 2 test)| ✅ |
| ErrorCode 引入 | ≤ 3 | **0** | ✅ |
| Maven 依赖新增 | 0 | **0**(`AtomicBoolean` + `AtomicLong` + `AtomicReference` + `LockSupport` + `ExecutorService` + `ThreadFactory` 全部 JDK 8 standard + `Publisher` / `Subscriber` / `Subscription` 已锁)| ✅ |
| spec 总行数 | ≤ 1500 | spec.md ~250 + plan.md ~280 + tasks.md ~380 = ~910 | ✅ |

---

## 9. References

- spec.md(WHO/WHY/WHAT/AC 四段式)
- tasks.md(24 steps 跨 3 phases + Phase 0 pre-flight + 16 AC ↔ task mapping)
- dsh §6.1 LinearTurnEngine + DefaultAgent(L1790-1850)— Story 必读
- dsh §7.1 AgentFactory + Agent 生命周期 — 守住
- dsh §14.15.1 NFR「LLM stream first token P50 ≤ 1.5s」— UX 兑现
- CLAUDE.md §11 #4/#6/#7/#8(Story 边界 / dep-tree 自查 / ReAct Loop 自实现 / Spring AI 禁用)
- reactive-streams-1.0.4 spec §1.3 + §2.2 + §2.7 + §3.9 — Plan A 合规性守住
- Story #005 AC-04 cancellation 200ms budget — `ctx.isCancelled()` 复用
- Story #001 `BufferedPublisher`(本期继续用,不删)
- Story #043 bounded pool — 本期**不**镜像
- Story #047 + Story #048 第 31 次 PASS 0 binary delta(本期第 32 次继续)
