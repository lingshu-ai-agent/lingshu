# Story #049 — ask live streaming from `DefaultAgent.run()`

> Single-source spec for **Story #049 — agent-run-live-streaming**.
> 4 sections:WHO / WHY / WHAT / AC。
> 16 AC ↔ 3 test files + 1 engineering AC。
> **0 行 prod 行为变化(只换实现路径)+ 1 prod 文件 modify + 2 test 文件 new。**

---

## 1. WHO(谁会碰这个 Story + 谁验证)

| 角色 | 关注点 |
|---|---|
| **Story #049 spec 作者** | 本 spec 写完后等用户拍板 → 进入 Phase 1 实施 |
| **Story #049 实施者**(下一步 = Claude Code) | 跑 Phase 0 pre-flight → Phase 1 prod modify → Phase 2 tests → Phase 3 R-13 + commit |
| **demo-product 浏览器侧用户**(实测) | `mvn spring-boot:run` + 浏览器问"list current dir"等 → 期望 SSE token-by-token 实时弹出(不是 turn 跑完一把全弹)|
| **dsh §7.1 reviewer** | 守住「`AgentFactory.create()` 仍是 Agent 唯一入口 + `ReAct Loop` 主循环 ≠ 接入路径」不变 |
| **CLI 集成者**(未来) | Story #020c `SkillCommandDispatcher` 后续接入,验证 reactive path 兼容(本期不直接验,只留 fixture 兼容)|
| **CLAUDE.md §11 硬约束守护** | 不引入新 Maven 依赖 + 不跨 Story 改 constitution + Reactive Streams spec §2.2 single-subscriber + §3.9 non-positive request 守住 |

---

## 2. WHY(为什么做 + 不做的代价)

### 2.1 实测现象(2026-10-XX demo-product 浏览器)

用户配 `application.yml` `permission-policy: ask` 跑 demo-product,在浏览器问"list current directory using bash tool":

- **左侧对话窗**:Agent 最终回复整段弹出(几秒后一次性显示)
- **右侧 Event Stream**:SSE events 也一次性整组弹出(`ApprovalRequired` → `TextDelta`(完整文本) → `TurnCompleted` 连续)
- **看不到 token-by-token 流式输出**(用户期望:每个 token 实时滚动)
- **阻塞链路表现**:`AskUserPermissionPolicy.check()` 阻塞 → AskUser 解阻塞后 turn 一次性跑完 → SSE dump

实测发现:**AnthropicLlmProvider Story #027b 已落地 SSE 真流式**(每个 `text_delta` 实时发 `AgentEvent.TextDelta`),但下游 `DefaultAgent.run()` **把流式事件全部缓冲到内存 List,turn 跑完才一并 replay 给 subscriber** —— streaming 在 engine ↔ agent 边界被**抹平**。

### 2.2 根因(`DefaultAgent.run()` 同步 drain 模式)

`lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java` L111-124 当代实现:

```java
@Override
public Publisher<AgentEvent> run(String userInput) {
    TurnContext ctx = buildContext(userInput);
    List<AgentEvent> buffer = new ArrayList<>();
    CountDownLatch done = new CountDownLatch(1);
    Subscriber<AgentEvent> tap = new Subscriber<AgentEvent>() {
        @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
        @Override public void onNext(AgentEvent e) { buffer.add(e); }
        @Override public void onError(Throwable t) { /* surfaced via ErrorEvent in buffer */ }
        @Override public void onComplete() { done.countDown(); }
    };
    engine.runTurn(ctx, tap);   // ← 同步调用,引擎跑完才返回
    return new BufferedPublisher(buffer);  // ← reactor: 返回一个 buffer replay 包装
}
```

**问题**:
1. `engine.runTurn(ctx, tap)` **同步调用**至 turn 完成,所有事件塞进 `buffer`
2. 返回 `BufferedPublisher`,subscriber subscribe 时遍历 buffer 一次性回放 → **subscriber 看不到事件真实顺序**
3. ChatController L237 `agent.run(prompt).subscribe(...)` subscribe 时 turn 已**完全跑完**,SSE events 连续 emit 无 I/O 间隔
4. Story #027b 真流式被 **DefaultAgent 边界抹平**,NFR P50 ≤ 1.5s 失去 UX 意义

`BufferedPublisher` L275-296 注释明白写「engine ran synchronously」。

### 2.3 不做的代价

| 不做本期 Story | 实际业务后果 |
|---|---|
| demo-product 浏览器 SSE 流式 UX 失效 | 用户无法看到 token-by-token 输出,**等同于传统 request-response**,失去 LingShu 卖点(对比 Claude Code / ChatGPT streaming)|
| dsh §14.15.1 NFR「LLM stream first token P50 ≤ 1.5s / P99 ≤ 3.0s」失效 | 数字达标但 UX 不可见;性能预算与用户体验脱节 |
| Story #020c CLI 集成后续接入 reactive 不兼容 | CLI 必须 own 一个 polling loop(Story #005b 已知 deferred),风险叠加 |
| Story #009a A2A / Story #009d RemoteAgentTool 远程 reactive 接入后也无流式 | 多 Agent 协同场景全部 buffer-replay,**违反 Reactive Streams spec 期待**|

### 2.4 已考虑替代方案(Plan B / Plan C 不采纳)

| Plan | 思路 | 否决理由 |
|---|---|---|
| **A** | hot lazy `Publisher` + executor 提交 `engine.runTurn` + AtomicBoolean single-subscriber 守卫 | **采纳**(见 §3 WHAT)|
| **B** | 同步 drain + 在 emit 时 await `subscriber` TCP buffer | Race condition + 主线程阻塞 + 无法并发 subscriber,反 Reactive Streams 原则 |
| **C** | 让 ChatController 直接绕过 DefaultAgent,调底层 engine | 拆穿 dsh §7.1 factory 模式 + 公共 `Agent.run()` API 形同虚设 |

---

## 3. WHAT(做什么 — Plan A 详细设计)

### 3.1 设计意图

把 `DefaultAgent.run()` 从「同步 drain + replay」改为 **Reactive Streams 正确语义的 hot lazy Publisher**:subscribe 时才把 `engine.runTurn(ctx, subscriber)` 提交到独立 executor 跑,**事件沿原始 I/O 路径实时 sink 到 subscriber**(不做缓冲),subscriber 当场 onNext → ChatController 当场 `emitter.send(SSE)` → 浏览器实时滚动。

**核心 3 件套**:
1. **hot lazy Publisher** —— `run()` 立即 return `Publisher<AgentEvent>`,不启动引擎;subscribe 才提交
2. **executor offload** —— `engine.runTurn` 提交到独立 `ExecutorService`,主调用线程不被阻
3. **AtomicBoolean single-subscriber 守卫** —— 第二次 subscribe → `IllegalStateException`,对应 Reactive Streams spec §2.2

### 3.2 改动范围(3 文件)

| 文件 | 角色 | 改动 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java` | **唯一 prod 改动** | `run()` body 替换为 hot lazy Publisher + 新增 `LiveRunPublisher` private static final inner class(替代 `BufferedPublisher` 不删——`runBlocking` 仍在用同一 buffer drain 模式)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultAgentLiveStreamingTest.java` | **new L1 + L2 黑盒** | 7 case 验证 hot lazy Publisher 行为(timing / single-subscriber / executor offload / error propagation / cancellation / back-pressure / multiple-call thread safety)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultAgentRunBlockingUnchangedTest.java` | **new L1 兼容回归** | 4 case 验证 `runBlocking` / `continueWithUserMessageBlocking` / `continueWithUserMessage` 行为不变(0 改动)|

**0 改动**:`Agent.java` SPI / `FlowEngine` SPI / `Subscriber<AgentEvent>` 接口 / `ChatController` / `runBlocking` / `continueWithUserMessage` / 任何 `engine.*` API。

### 3.3 `DefaultAgent.run()` 新版 contract(Plan A 具体实现要点)

```java
// 伪代码示意(Lines ~111-124 替换)
private final ExecutorService liveExecutor =
    Executors.newCachedThreadPool(daemonFactory("lingshu-agent-live"));

@Override
public Publisher<AgentEvent> run(String userInput) {
    final TurnContext ctx = buildContext(userInput);
    return new LiveRunPublisher(liveExecutor, ctx);
}

private static final class LiveRunPublisher implements Publisher<AgentEvent>, Subscription {
    private final ExecutorService executor;
    private final TurnContext ctx;
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicReference<Subscriber<? super AgentEvent>> sub = new AtomicReference<>();
    private final AtomicLong requested = new AtomicLong(0);
    private volatile boolean cancelled;

    // subscribe() — 第一次合法,第二次抛 IllegalStateException(reactive §2.2)
    public void subscribe(Subscriber<? super AgentEvent> s) {
        if (s == null) throw new NullPointerException("subscriber");
        if (!subscribed.compareAndSet(false, true)) {
            // 第二次 subscribe → IllegalStateException 立即 onError 路径
            ...
        }
        sub.set(s);
        s.onSubscribe(this);
        if (!cancelled) executor.execute(() -> engine.runTurn(ctx, this));
    }

    // request(n) — 累计请求,>=1 才向 emittingSub 投递(reactive §3.9 guard)
    // cancel()    — cancelled=true,engine 下一轮 isCancelled() 返 true → break loop
    // engine 调 onNext / onError / onComplete 时 通过 sub.get() 投递
}
```

**关键约束**(8 条):
1. **惰性** —— `run()` 返回 Publisher 时**不**调 engine,订阅才调
2. **executor 隔离** —— `engine.runTurn` 不在主调用线程跑(避免阻塞 demo-product Tomcat)
3. **daemon** —— executor 线程 `daemon=true`,JVM exit 不阻
4. **single-subscriber** —— `AtomicBoolean subscribed` + `compareAndSet(false, true)` 守卫(Reactive Streams §2.2)
5. **request count guard** —— `requested > 0` 才向下游 emit(Reactive Streams §3.9 non-positive request 检查)
6. **onError 串行** —— emit onNext / onError 时严格 synchronously(Reactive Streams §2.7 serial)
7. **cancel → engine** —— SSE disconnect 调 `subscription.cancel()`,设 `cancelled=true`,`engine.runTurn` 内 `ctx.isCancelled()` 返 true → 提早 break ReAct loop(对接 Story #005 AC-04)
8. **runBlocking 不动** —— `runBlocking` 仍走原有 in-line drain + buffer 模式(复用 `BufferedPublisher`),与 reactive path 不混

### 3.4 back-pressure 模型

- ChatController Tomcat SSE `SseEmitter` 默认 unbounded buffer
- sub slow 时:Tomcat TCP buffer 满 → `emitter.send(...)` 抛 IOException → ChatController L252-254 `emitter.completeWithError(e)` → onError 传回 reactive chain
- reactive sub path:`requested` 计数慢增 → executor 线程 `engine.runTurn` 内 `subscriber.onNext(...)` 因 `requested <= 0` 改为 `LockSupport.park` 等 sub 增(req 进制)
- 失败兜底:`LockSupport.parkNanos` timeout 1s → 视为 sub 已挂断 → 调 `Subscription.cancel()` → engine break

### 3.5 cancellation 链路(防 SSE leak)

`emitter.onCompletion` + `emitter.onTimeout` Spring event:
1. 浏览器关闭 → SSE 长连接断 → Tomcat 触发 `emitter.complete()`
2. ChatController 间接:本期不直接接(避免改动 demo-product)
3. **测试验证路径**:`LiveRunPublisher.subscription.cancel()` → `cancelled=true` → engine `ctx.isCancelled()` 返 true → ReAct break → engine runTurn 返 → executor 线程 `engineDone` → executor 释放 thread(不阻)

生产 SSE 关断到 Reactive cancel 的 Spring adapter bridge **留 OQ-Future**(本期不做)。

### 3.6 关键不变项(守住项)

| 项 | 状态 |
|---|---|
| `Agent.run(String)` 公开 SPI 签名 | **不变**(`Publisher<AgentEvent>` return)|
| `Agent.runBlocking(String)` 行为 | **不变**(继续 in-line drain)|
| `Agent.continueWithUserMessage(String)` 行为 | **不变**(仍 append + run)|
| `FlowEngine.runTurn(ctx, sub)` 签名 | **不变**(本期**不**改 engine)|
| `ChatController` 任何一行 | **不变**(本期**不**动 demo-product prod)|
| 9 Slot 体系 / 26 字段 AgentConfig schema | **不变** |
| Reactive Streams spec §1-3 合规 | **必须守住**(§2.2 / §2.7 / §3.9)|
| Story #005 AC-04 cancellation 200ms budget | **继续守住**(本期不引新 timer)|
| Story #043 bounded pool hygiene pattern | **不引**(executor 我们用 cachedThreadPool + daemon,不引 bounded pool;CachedThreadPool daemon 是 JDK 已有能力,不需自有 bound — demo-product 单 turn 1 long-lived thread,无并发爆发场景)|
| dsh §7.1 factory 模式 | **守住**(全部走 `Agent.run` 公开 API,不暴露 engine)|
| JDK 8 兼容(`AtomicBoolean.compareAndSet` + `AtomicLong` + `AtomicReference` + `LockSupport.parkNanos` + `Executors.newCachedThreadPool` + `ThreadFactory` daemon)| ✅ 全部 JDK 8 standard |

### 3.7 新 ErrorCode / 新 Maven 依赖

- **0 新 ErrorCode**(本期**不**抛业务异常,只 Reactive Streams spec 已规定的 `IllegalStateException` / `NullPointerException`)
- **0 新 Maven 依赖**(`AtomicBoolean` + `AtomicLong` + `AtomicReference` + `LockSupport` + `ExecutorService` + `ThreadFactory` 全部 JDK 8 内置,`Publisher` / `Subscriber` / `Subscription` 已在 `lingshu-core/pom.xml` `org.reactivestreams:reactive-streams:1.0.4` 锁内 — 13 项依赖表无新增)

### 3.8 Story 边界(`CLAUDE.md §11 #4`)

- **核心文件改动**:1 prod + 2 test = **3 文件**(≤5 上限)✅
- **新 ErrorCode 引入**:0(≤3 上限)✅
- **新 Maven 依赖**:0(0 上限)✅
- **spec 总行数**:`spec.md` 本文件 ~250 行(上界) + `plan.md` ~250 行 + `tasks.md` ~380 行 ≈ **~880 行**(≤1500 上限)✅

---

## 4. AC(验收 — 16 AC 跨 3 test 文件)

### 4.1 `DefaultAgentLiveStreamingTest.java`(new,7 L1+L2 case)

| AC | 名称 | 验收法 |
|---|---|---|
| **AC-049-01** | `run_returnsHotLazyPublisher_noEngineInvocationUntilSubscribe` | mock engine.recordInvocations;调 `run()` 后 unmocked engine 计数器 == 0;`subscribe(...)` 后 == 1(证明惰性)|
| **AC-049-02** | `subscribe_submittedToExecutor_mainThreadNotBlocked` | mock engine.recordThreadName + mock `liveExecutor`;`subscribe(...)` 后 `Thread.currentThread().getName()` ≠ `engineRunTurnThread`(证明 offload)|
| **AC-049-03** | `onNext_emittedAsEngineProduces_noBuffering` | mock engine 立即 emit 5 个 `AgentEvent.TextDelta`;用 `Stopwatch` + 自定义 `Subscriber.onNext` 记录 timestamp 间隔;**预期** 5 个事件 timestamp 沿 engine 模拟的 50ms 间隔(不一把全弹)|
| **AC-049-04** | `secondSubscribe_throwsIllegalStateException` | 第一次 subscribe OK;第二次 subscribe → `assertThatThrownBy(...).isInstanceOf(IllegalStateException.class)`(Reactive Streams §2.2)|
| **AC-049-05** | `subscriptionCancel_propagatesToEngineCtxIsCancelled` | subscribe + 立即 cancel + 启 engine mocks 100 step ReAct;1ms 后断言 `ctx.isCancelled() == true`(对接 Story #005)|
| **AC-049-06** | `backpressure_zeroRequest_emissionBlocksUntilRequested` | mock subscriber `request(0)` 后 engine 跑第一个 step;断言:**onNext 没收到**;再 `request(1)` → onNext 收到 1 个(Reactive Streams §3.9 non-positive)|
| **AC-049-07** | `engineEmitsError_propagatesToSubscriberOnError` | mock engine 第一 step `onError(new IllegalStateException("boom"))`;断言 subscriber 收到 `onError` + `e.getMessage().equals("boom")`(Reactive Streams §2.7 串行 onError)|

### 4.2 `DefaultAgentRunBlockingUnchangedTest.java`(new,4 L1+L2 case — 反向 AC)

| AC | 名称 | 验收法 |
|---|---|---|
| **AC-049-08** | `runBlocking_returnsCompletedRunResult_unchanged` | 用已有 `LinearTurnEngine` 单步 mock 配 Agent,`runBlocking(...)` 同步返回 `RunResult`;**断言** final text + usage + reason 与 v1.5.59 (Story #046) 完全一致(用 fixture-snapshot 验证)|
| **AC-049-09** | `continueWithUserMessage_appendsUserMessage_doesNotSwitchToReactive` | mock session.append + spy `run`;`continueWithUserMessage("hello")` 后断言:**session.append 被调** 1 次 + **`run` 被调** 1 次(仍走 buffer path 不走 reactive)|
| **AC-049-10** | `runBlocking_doesNotInvolveLiveExecutor` | 在 `liveExecutor` 注入一个 spy;`runBlocking(...)` 后断言 **0 task** 在 spy 上执行(同步路径不污染异步池)|
| **AC-049-11** | `multipleRunBlockingCallsConcurrently_eachGetsFreshRunResult` | 2 线程并行各调 1 次 `runBlocking`;**断言** 2 个 `RunResult` 内容不同(各自 ctx)且不交错 |

### 4.3 Engineering baseline(infra + 集成 AC,5 case)

| AC | 名称 | 验收法 |
|---|---|---|
| **AC-049-12** | `reactiveStreams_existing761tests_stillPass` | `mvn -pl lingshu-core test` 全 761+ tests 全过(2 MCP heartbeat flake pre-existing)|
| **AC-049-13** | `dependencyTree_prePostBaselineMirror_0BinaryDelta` | `mvn -pl lingshu-core dependency:tree > pre.txt` → Phase 1-3 实施 → `> post.txt`;`md5sum pre` 与 `post` 仅时间戳差异 → **R-13 mitigation (d) 第 32 次 PASS 0 binary delta** |
| **AC-049-14** | `bannedDependencies_enforcer_rule0Passed` | `mvn -pl lingshu-core enforcer:enforce` → `Rule 0: banned-dependencies` passed(dsh §17)|
| **AC-049-15** | `chatControllerInDemoProduct_unchanged_zeroLineModified` | git diff 显示 demo-product 只有 spec 加号 + **`ChatController.java` 行号 hash 不变**(AC 反向性证明)|
| **AC-049-16** | `reactiveStreamsSpec_3_articles_compliance` | unit test 验证 §2.2 / §2.7 / §3.9 三条由 7 case 覆盖(AC-04 / AC-05 / AC-06 / AC-07 分别覆盖一条)|

---

## 5. References & appendix

### 5.1 Story 链位置

```
#017 ──► #026 ──► #027a ──► #027b ──► #043 ──► #045/#046 ──► #047/#048 ──► #049(current)
                  LLM 协议层  SSE 真流式   有界池   compactor/sav   ask end2end   修 live streaming
                                                after-turn      + sentinel      Defaul boundary
```

#049 是 #027b 真流式的下游补丁 —— 上游通了,中间还被 `DefaultAgent.run()` 同步 drain 抹平,**必须修**才能让 Story #027b 的 13 case 真信号到达浏览器。

### 5.2 反向引用

- `dsh_agent_design.md` §6.1 LinearTurnEngine + DefaultAgent 段(L610-680 / L1790-1850) — 本 Story 必读
- `dsh_agent_design.md` §7.1 AgentFactory + Agent 生命周期 — 「Agent 是 factory 产品 + 公开 API 不暴露 engine」守住
- `dsh_agent_design.md` §14.15.1 NFR 表「LLM stream first token P50 ≤ 1.5s」— 本期 UX 兑现
- `CLAUDE.md` §11 #4(boundary ≤ 5 + ErrorCode ≤ 3)+ §11 #6(0 新 Maven 依赖)+ §11 #7(ReAct Loop 自实现)+ §11 #8(Spring AI ChatClient 禁用)
- `reactive-streams-1.0.4` spec §1.3 / §2.2 / §2.7 / §3.9 — Plan A 守住
- Story #005 AC-04 cancellation 200ms budget — 本期对接
- Story #001 `BufferedPublisher`(同期 imple pattern,本期用 `LiveRunPublisher` 替代)
- Story #043 bounded pool hygiene — 本期**不**镜像(executor daemon cachedThreadPool)

### 5.3 budget guards(运行时检查)

| 项 | 触发条件 | 触发后兜底 |
|---|---|---|
| 核心文件改动超过 5 | `git diff --name-only main HEAD | wc -l` > 5 且不含 #049 + ChatController 计数 | 立即停手,review spec §3.6 |
| ChatController 行号变更 | `git diff --stat main HEAD -- ChatController.java` non-empty | 立即停手,核对 spec §3.6 (本期不动 demo-product)|
| RunBlocking 行为变化 | `DefaultAgentRunBlockingUnchangedTest.java` 4 case fail | 立即停手,核对 spec §3.6 (runBlocking 不动)|
| Reactive Streams spec violation | `assertj` 验证 §2.7 串行 / §3.9 non-positive 失败 | 立即停手,核对 spec §3.3 关键约束 |
| 新 Maven 依赖引入 | dep-tree diff 新增 binary | 立即停手,撤回 + 重跑 baseline(0 binary delta 守住)|
| LiveRunPublisher executor 用错线程池 | thread name 不是 `lingshu-agent-live-N` daemon | 立即停手,核对 ThreadFactory daemon=true |
| publish order 错误 | 第一 event 比 first TextDelta 早(LlmResponse 应该先到)| 立即停手,核对 engine emission order |

### 5.4 应急路径

如 Phase 1 any mid checkpoint 失败:
- 立即停手
- 重新读 spec §3.3 `LiveRunPublisher` 关键约束(8 条)
- 不进入 Phase 2 tests

如 Phase 2 任何 AC 失败:
- 立即停手
- 检查 fixture 是否按 spec §3.3 描述
- 检查 Reactive Streams spec §2.2 / §2.7 / §3.9 三条
- 不进入 Phase 3

如 Phase 3 R-13 baseline diff 不止时间戳:
- 立即停手
- 检查 `pom.xml` 是否失误新增 dep
- revert + 重审 plan §7.1 D-1/D-2/D-3 决策

### 5.5 配套文档同步(实施后)

- `README.md` 「更新日期」段加 🆕 v1.5.60 Story #049 顶部 blockquote
- `README.md` 「核心特性」段补 📡 `DefaultAgent.run()` 实时流式已上线 bullet
- `specs/ROADMAP.md` 段一 ✅ 已完成加 #049 行 + 段二 🟡 待补 #049 划掉 + 段五 🎯 实施节奏累计 51 → 52 个 Story
- `constitution.md` §10 R-13 缓解 Story 列表补 `#049` 行 **第 32 次** PASS 0 binary delta + 累计计数 31 → **32 个 Story**
- `dsh_agent_design.md` §13 changelog 新增 v1.5.60 行(本期 Story 完整内容 11 节)+ §6.1 LinearTurnEngine + §7.1 Agent 生命周期子段补 `🆕 v1.5.61 Story #049` marker
- `dsh_agent_design.md` §6.1 L1850 段补 LiveRunPublisher 引用 marker
- CLAUDE.md v1.3.57 → v1.3.58 + 「对应设计文档」版本号 v1.5.60 → v1.5.61

---

**Last updated**: 2026-10-15
**Version**: 1.0.0(Story #049 spec first cut)
**对应设计文档**: `dsh_agent_design.md` v1.5.60
**对应 SpecKit SOP**: `speckit_operator_prompt.md` v1.18
**对应 SKILL**: `lingshu-spec-driven-dev` v1.0.28
**对应 Prompt 速查**: `lingshu_spec_prompts.md` v1.0.16
