# Story #049 — Tasks

> 24 steps 跨 4 phases + Phase 0 pre-flight + 16 AC ↔ task mapping + R-13 第 32 次 PASS checklist。
>
> **任务编排模式**: 参考 Story #048 task list(closed precedent)的 phase / step / AC mapping 3 件套。

---

## Phase 0 — Pre-flight baseline 镜像(本 Story 启动前)

- [ ] T-0.1 读 Story #048 实施 commit(若未合)/ 当前 working tree 状态(L580-665 `LinearTurnEngine.dispatchWithPolicy` 段已被 #048 修改)
- [ ] T-0.2 md5 当前 `lingshu-core/pom.xml`:`md5sum /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu/lingshu-core/pom.xml > /tmp/pom-049-pre.md5`
- [ ] T-0.3 跑 baseline:`cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core dependency:tree > /tmp/dtree-049-pre.txt 2>&1 && md5sum /tmp/dtree-049-pre.txt | tee /tmp/dtree-049-pre.md5`
- [ ] T-0.4 `git status` 确认 working tree 干净(否则 commit / stash 历史变更)
- [ ] T-0.5 Read `DefaultAgent.java` L107-175 + L275-297(re-confirm 实施基线 + `BufferedPublisher` 内部)
- [ ] T-0.6 Read `Agent.java` SPI(确认公开方法签名 `Publisher<AgentEvent> run(String)` 0 改动目标)
- [ ] T-0.7 Read `ChatController.java` L220-270(SSE 消费路径,本期不动)
- [ ] T-0.8 Read `LinearTurnEngine.java` 公开 `runTurn(ctx, sub)` 签名(本期不动 engine)
- [ ] T-0.9 Read `specs/049-agent-run-live-streaming/spec.md` + `plan.md` + 本 `tasks.md` 全文,hold mental model
- [ ] T-0.10 启动期邀请 user review spec/plan/tasks 三件套(若 user 在线 → 等 user 确认;若非在线 → 直接进入 Phase 1)

---

## Phase 1 — `DefaultAgent.run()` 替换 + `LiveRunPublisher` inner class

- [ ] T-1.1 Edit `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java`:
  - 顶部新增 5 import:`java.util.concurrent.ExecutorService` + `Executors` + `ThreadFactory` + `java.util.concurrent.atomic.AtomicBoolean` + `AtomicLong` + `AtomicReference` + `java.util.concurrent.locks.LockSupport`
  - 新增 `private final ExecutorService liveExecutor` 字段(初始化 `Executors.newCachedThreadPool(daemonFactory)`)
  - 新增 `private static ThreadFactory daemonFactory(String prefix)` 私有静态方法(`return new ThreadFactory() { private final AtomicInteger i = new AtomicInteger(0); @Override public Thread newThread(Runnable r) { Thread t = new Thread(r, prefix + "-" + i.incrementAndGet()); t.setDaemon(true); return t; } };`)
  - 替换 `run(String)` 方法体:从 in-line drain 改 `return new LiveRunPublisher(liveExecutor, buildContext(userInput));`
  - 新增 `private static final class LiveRunPublisher implements Publisher<AgentEvent>, Subscription` 完整实现(8 条关键约束):
    - 字段:`ExecutorService executor` + `TurnContext ctx` + `AtomicBoolean subscribed = new AtomicBoolean(false)` + `AtomicReference<Subscriber<? super AgentEvent>> sub = new AtomicReference<>()` + `AtomicLong requested = new AtomicLong(0)` + `volatile boolean cancelled`
    - `subscribe(Subscriber s)` 方法:`if (s == null) throw new NPE; if (!subscribed.compareAndSet(false, true)) { /* 二次 subscribe */ try { s.onSubscribe(NEVER); s.onError(new IllegalStateException("Single subscriber only ... reactive §2.2")); } catch (Throwable t) {} return; } sub.set(s); s.onSubscribe(this); if (!cancelled) executor.execute(() -> engine.runTurn(ctx, subscriberBridge));`
    - `request(long n)` 方法:Reactive §3.9 guard(`if (n <= 0) { sub.get().onError(new IllegalArgumentException("non-positive request §3.9")); return; }` + `requested.addAndGet(n)` + 唤醒 parkingThread)
    - `cancel()` 方法:`cancelled = true; if (sub.get() != null) LockSupport.unpark(parkingThread)`
    - subscriber bridge:`onNext(e)` 在 requested > 0 时调 `requested.decrementAndGet()` 后 `sub.get().onNext(e)`,否则 `LockSupport.parkNanos(50ms)` 等(1s timeout 触发 cancel)
    - `onError(t)` `onComplete()` from engine 同样通过 sub.get() 投递
    - 关闭类级:Javadoc 严禁 break reactive spec + 严禁 swallow Throwable
  - 类级 Javadoc 加 `🆕 Story #049 — DefaultAgent.run() 实时流式(Plan A)` 段
- [ ] T-1.2 mid checkpoint:`cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core compile -q` 通过
- [ ] T-1.3 验证 `runBlocking` / `continueWithUserMessage` / `continueWithUserMessageBlocking` 三方法体未改动(`git diff --stat DefaultAgent.java` 变更只在 +70 行 `LiveRunPublisher` 区,不在原方法体内)
- [ ] T-1.4 mid checkpoint:既有 `DefaultAgent` 相关 fixtures 0 改动全过(`mvn -pl lingshu-core test -Dtest='DefaultAgent*Test' -q` 单独跑现有 case)

---

## Phase 2 — 测试二件套

### Phase 2a — `DefaultAgentLiveStreamingTest.java`(new,7 L1+L2 case)

- [ ] T-2a.1 Create `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultAgentLiveStreamingTest.java`:
  - AC-049-01 `run_returnsHotLazyPublisher_noEngineInvocationUntilSubscribe`(spy engine + mock factory;run() 后 engine.recordInvocations == 0;subscribe 后 == 1)
  - AC-049-02 `subscribe_submittedToExecutor_mainThreadNotBlocked`(spy Thread.currentThread in engine;assertion subscribe 后 `Thread.currentThread().getName() != "main"` OR `!= engine-recordThread`)
  - AC-049-03 `onNext_emittedAsEngineProduces_noBuffering`(mock engine emit 5 `TextDelta` with 50ms 间隔 stop-watch in subscriber.onNext;assertion 5 events timespan >= 200ms ≥ 250ms 范围(50ms × 5 ± 误差),**不** 一把全弹)
  - AC-049-04 `secondSubscribe_throwsIllegalStateException`(assertj assertThatThrownBy secondSubscribe isInstanceOf IllegalStateException)
  - AC-049-05 `subscriptionCancel_propagatesToEngineCtxIsCancelled`(subscribe + cancel;wait 1ms;assert engine.spyIsCancelled == true)
  - AC-049-06 `backpressure_zeroRequest_emissionBlocksUntilRequested`(mock subscriber.request(0);assertion onNext 没收到;再 request(1) → 1 onNext 收到)
  - AC-049-07 `engineEmitsError_propagatesToSubscriberOnError`(mock engine onError;assertion subscriber onError 收到 + message 同)

### Phase 2b — `DefaultAgentRunBlockingUnchangedTest.java`(new,4 L1+L2 case — 反向 AC)

- [ ] T-2b.1 Create `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultAgentRunBlockingUnchangedTest.java`:
  - AC-049-08 `runBlocking_returnsCompletedRunResult_unchanged`(mock LinearTurnEngine + Agent;runBlocking 同步返 RunResult;assertion finalText + reason + usage 与 v1.5.59 fixture 字节级一致)
  - AC-049-09 `continueWithUserMessage_appendsUserMessage_doesNotSwitchToReactive`(spy session + spy run;assertion session.append 1 次 + run 1 次,不切 reactive path)
  - AC-049-10 `runBlocking_doesNotInvolveLiveExecutor`(mock liveExecutor;runBlocking 后 assert spy no task executed)
  - AC-049-11 `multipleRunBlockingCallsConcurrently_eachGetsFreshRunResult`(2 线程并发;assertion 2 RunResult 内容独立,不交插)

### Phase 2c — 全量回归

- [ ] T-2c.1 `cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu && mvn -pl lingshu-core test -q` 全通过(预期 761+11 = **772+ tests pass** / 0 fail / 2 MCP heartbeat flake pre-existing)
- [ ] T-2c.2 `mvn -pl lingshu-core verify` 0 fail / 0 error
- [ ] T-2c.3 反向 AC(R-RAC-1/2/3)验证:既有 `LinearTurnEngineE2ESmokeTest` + `LinearTurnEngineAskUserTest` + `LinearTurnEngineProviderCompactionTest` + `LinearTurnEngineProviderSessionStoreTest` + `LinearTurnEngineAskUserNoRenderIT` + `LinearTurnEngineProviderTest` 0 改动全过
- [ ] T-2c.4 R-13 mid checkpoint:`mvn -pl lingshu-core dependency:tree` 输出与 pre-flight 完全一致(pre.txt == post.txt 仅 timestamp)

---

## Phase 3 — R-13 post-flight baseline 镜像 + git commit

- [ ] T-3.1 跑 baseline:`mvn -pl lingshu-core dependency:tree > /tmp/dtree-049-post.txt 2>&1 && md5sum /tmp/dtree-049-post.txt | tee /tmp/dtree-049-post.md5`
- [ ] T-3.2 `diff /tmp/dtree-049-pre.txt /tmp/dtree-049-post.txt` — 仅时间戳差异
- [ ] T-3.3 确认 `banned-dependencies` enforcer Rule 0: passed(`mvn -pl lingshu-core enforcer:enforce` 或 build 自动触发)
- [ ] T-3.4 文档同步落地:
  - [ ] T-3.4.1 README.md 「更新日期」段 + 「核心特性」段(补 📡 `DefaultAgent.run()` 实时流式 bullet)+ 「Story 路线图」段(追加 #049 retrospective)
  - [ ] T-3.4.2 specs/ROADMAP.md 段一「✅ 已完成」加 #049 行 + 段二「🟡 待补」划掉 #049 + 段五「🎯 实施节奏」累计计数 51 → **52 个 Story**(本期 +1)
  - [ ] T-3.4.3 constitution.md §10 R-13 缓解 Story 列表补 `#049` 行(**第 32 次** PASS 0 binary delta)+ 累计计数 31 → **32 个 Story**
  - [ ] T-3.4.4 dsh_agent_design.md §13 changelog 新增 v1.5.61 行(完整 11 节)+ 「对应设计文档」版本号 v1.5.60 → v1.5.61
  - [ ] T-3.4.5 dsh_agent_design.md §6.1 LinearTurnEngine + §7.1 Agent 生命周期子段补 `🆕 v1.5.61 Story #049` marker + §6.1 L1850 段补 `LiveRunPublisher` 引用 marker
  - [ ] T-3.4.6 CLAUDE.md v1.3.57 → v1.3.58 + 「对应设计文档」版本号引用同步
- [ ] T-3.5 `git status` 检查所有 spec/prod/test/docs 改动
- [ ] T-3.6 `git add specs/049-agent-run-live-streaming/ lingshu-core/src/main/java/... lingshu-core/src/test/java/... README.md specs/ROADMAP.md constitution.md dsh_agent_design.md CLAUDE.md`
- [ ] T-3.7 `git commit -m "$(cat <<'EOF'
  feat(agent): Story #049 default-agent-run-live-streaming

  - DefaultAgent.run() replaces buffered sync drain with hot lazy
    Publisher + executor offload. First subscribe() submits
    engine.runTurn to a daemon cachedThreadPool ("lingshu-agent-live-N"),
    events stream live to subscriber instead of being collected
    into a List and replayed after turn completion.

  - LiveRunPublisher private static final inner class enforces
    Reactive Streams 1.0.4 §2.2 (single-subscriber via
    AtomicBoolean.compareAndSet), §2.7 (serial onNext/onError),
    §3.9 (non-positive request via AtomicLong + LockSupport).
    Cancellation propagated via cancelled flag → engine
    ctx.isCancelled() polling (Story #005 AC-04 200ms budget).

  - runBlocking + continueWithUserMessage + continueWithUserMessageBlocking
    unchanged (4 backward-compat AC). BufferedPublisher preserved
    for sync path. ChatController 0 lines modified.

  - 11 net new test case across 2 files (7 LiveStreaming + 4
    RunBlockingUnchanged). Full suite 772+ tests pass.

  R-13 mitigation (d) baseline mirror: 32nd pass, 0 binary delta.

  🤖 Generated with [Claude Code](https://claude.com/claude-code)

  Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
  EOF
  )"`
- [ ] T-3.8 `git status` 确认 commit 成功,working tree 干净

---

## AC ↔ Task 映射(16 AC ↔ 4 phases)

| AC | Phase | Task | 关联 reference |
|---|---|---|---|
| AC-049-01 | Phase 2a | T-2a.1 | spec §4.1(LiveRunPublisher lazy)|
| AC-049-02 | Phase 2a | T-2a.1 | spec §4.1(executor offload)|
| AC-049-03 | Phase 2a | T-2a.1 | spec §4.1(no buffering 关键)|
| AC-049-04 | Phase 2a | T-2a.1 | spec §4.1(§2.2 single-subscriber)|
| AC-049-05 | Phase 2a | T-2a.1 | spec §4.1(cancellation)|
| AC-049-06 | Phase 2a | T-2a.1 | spec §4.1(back-pressure §3.9)|
| AC-049-07 | Phase 2a | T-2a.1 | spec §4.1(error propagation)|
| AC-049-08 | Phase 2b | T-2b.1 | spec §4.2(反向 AC)|
| AC-049-09 | Phase 2b | T-2b.1 | spec §4.2(反向 AC)|
| AC-049-10 | Phase 2b | T-2b.1 | spec §4.2(反向 AC)|
| AC-049-11 | Phase 2b | T-2b.1 | spec §4.2(反向 AC)|
| AC-049-12 | Phase 2c | T-2c.1 | spec §4.3(回归)|
| AC-049-13 | Phase 3 | T-3.1 / T-3.2 | spec §4.3(R-13)|
| AC-049-14 | Phase 3 | T-3.3 | spec §4.3(enforcer)|
| AC-049-15 | Phase 2c | T-2c.3 + T-3.5 | spec §4.3(ChatController 0 改动)|
| AC-049-16 | Phase 2a | T-2a.1 | spec §4.3(reactive spec 合规)|

---

## budget guards(运行时检查)

| 项 | 触发条件 | 触发后兜底 |
|---|---|---|
| 核心文件改动超过 5 | `git diff --name-only main HEAD | wc -l` > 5 且不含 #049 + ChatController + spec + docs | 立即停手,review spec §3.6 |
| ChatController 行号变更 | `git diff --stat main HEAD -- ChatController.java` non-empty | 立即停手,核对 spec §3.6 (本期不动 demo-product)|
| RunBlocking 行为变化 | `DefaultAgentRunBlockingUnchangedTest.java` 4 case fail | 立即停手,核对 spec §3.6 (runBlocking 不动)|
| Reactive Streams spec violation | `assertj` 验证 §2.7 串行 / §3.9 non-positive 失败 | 立即停手,核对 spec §3.3 关键约束 |
| 新 Maven 依赖引入 | dep-tree diff 新增 binary | 立即停手,撤回 + 重跑 baseline(0 binary delta 守住)|
| LiveRunPublisher executor 用错线程池 | thread name 不是 `lingshu-agent-live-N` daemon | 立即停手,核对 ThreadFactory daemon=true |
| publish order 错误 | 第一 event 比 first TextDelta 早(LlmResponse 应该先到)| 立即停手,核对 engine emission order |

---

## R-13 mitigation (d) baseline mirror checklist(第 32 次 PASS)

```bash
# Phase 3 实施后:
[ "$(md5sum /tmp/dtree-049-pre.txt | awk '{print $1}')" = "$(md5sum /tmp/dtree-049-post.txt | awk '{print $1}')" ] || diff /tmp/dtree-049-pre.txt /tmp/dtree-049-post.txt
# 预期:hash 不同(因 timestamp)且 diff 仅时间戳 line 差异
```

**判定**:
- ✅ = 仅有 timestamp 行差异,binary tree 完全相同 → **第 32 次 PASS 0 binary delta**
- ✗ = 新增 dependency tree 行 → 立即 abort + revert + 重写 plan

**pre.md5 hash 记录**(`/tmp/dtree-049-pre.md5` 内容)。
**post.md5 hash 记录**(`/tmp/dtree-049-post.md5` 内容)。
**commit message 应包含**: `R-13 mitigation (d) baseline mirror: 32nd pass, 0 binary delta.`

---

## 24 steps 总览

| Phase | Steps 数量 | Task 范围 |
|---|---|---|
| Phase 0 pre-flight | 10 | T-0.1 ~ T-0.10 |
| Phase 1 | 4 | T-1.1 ~ T-1.4 |
| Phase 2 | 11 | T-2a.1, T-2b.1, T-2c.1-T-2c.4 + sub-steps |
| Phase 3 | 8 | T-3.1 ~ T-3.8 + 4 个 docs sync 子任务 |
| **总** | **33** | 24 high-level steps + sub-steps + 4 docs sync |

---

## reference(本 Story 依赖的现有文件)

| 文件 | 路径 | 角色 |
|---|---|---|
| `DefaultAgent.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/` | Agent impl — Phase 1 唯一 prod 改动 |
| `Agent.java` | `lingshu-core/src/main/java/ai/lingshu/core/runtime/` | SPI 公共接口 — 0 改动(只读)|
| `ChatController.java` | `lingshu-examples/demo-product/src/main/java/...` | SSE 消费 — 0 改动(只读)|
| `LinearTurnEngine.java` | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/` | engine 主循环 — 0 改动(只读)|
| `reactive-streams-1.0.4` | `org.reactivestreams:reactive-streams:1.0.4` | 已锁 13 项依赖表内 — 0 改动 |
| Story #005 `CancellationToken` | `lingshu-core/src/main/java/ai/lingshu/core/runtime/` | ctx.isCancelled() polling — 复用 |
| Story #001 `BufferedPublisher` | `DefaultAgent.java` L275-296 inner class | 保留给 `runBlocking` 用,本期不删 |

---

## 应急路径

如 Phase 1 any mid checkpoint 失败:
- 立即停手
- 重新读 spec.md/plan.md 对应 phase
- 检查 import / ThreadFactory daemon / AtomicBoolean compare-and-set / LockSupport park 1s timeout
- 不进入 Phase 2 tests 编写

如 Phase 2 任何 AC 失败:
- 立即停手
- 检查 fixture 是否按 spec §3.3 / §3.4 描述
- 重读 Reactive Streams 1.0.4 spec §2.2 / §2.7 / §3.9 三条
- 不进入 Phase 3

如 Phase 3 R-13 baseline diff 不止时间戳:
- 立即停手
- 检查 `pom.xml` 是否失误新增 dep,或 spring-ai-bom 升级
- revert + 重审 plan D-1/D-2/D-3/D-4/D-5 决策
