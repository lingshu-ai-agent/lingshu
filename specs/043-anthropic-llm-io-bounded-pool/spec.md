# Story #043 `anthropic-llm-io-bounded-pool` — Spec

> **Status**: Draft 2026-10-05
> **Source**: 用户代码评审反馈(2026-10-05 会话「这个线程池线程上限好像是 `Integer.MAX_VALUE`,那岂不是说可以建立无限个 Session,如果产品侧不做限制会内存溢出吧」)+ 同会话诊断 `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java:74`
> **前置依赖**: 无(纯 hygiene / 防御性 fix)
> **本 Story 体量**: 1 modify(AnthropicLlmProvider.java)+ 1 new test file + 0 new ErrorCode + 0 new Maven dep —— 严格 ≤ 5 文件边界内

---

## 状态

[x] Draft  [ ] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` §17 R-13 mitigation (d)(无新 Maven 依赖)+ §10 NFR 并发 turn 数 + §4.10.1 硬规则 2(ToolExecutor 5 步流水线)
- **实测发现**(2026-10-05 用户代码评审):
    - `AnthropicLlmProvider.java:74-78` `ioExecutor = Executors.newCachedThreadPool(r → Thread("anthropic-llm-io", daemon=true))` — JDK `Executors.newCachedThreadPool` 内部 `maximumPoolSize = Integer.MAX_VALUE`(JDK src `java/util/concurrent/Executors.java:232` `Integer.MAX_VALUE`)
    - `ToolExecutorConfig.java:62-69` 是项目里另一个 LLM 风格池样板,使用正经 `ThreadPoolExecutor(corePool*2, maxPool*2, 60s, LinkedBlockingQueue(256), CallerRunsPolicy)` + `lingshu-tool-N` 线程名前缀 + daemon
    - **`ioExecutor` 是 `lingshu-core/src/main/java/` 唯一一处 `newCachedThreadPool`** —— 另外 3 处在 `src/test/`(fixture)
- **业务后果**(当前状态):
    - 现状单 turn 内**只有 1 个任务在飞**(`LinearTurnEngine.java:176` 每 ReAct step 调一次 `stream()`,然后 `waitForLlm` 200ms 轮询串行等结果)— 单 turn 至多 1 个 ioExecutor 线程 × 60s SSE 超时,**今天不会爆**
    - 但 **`Integer.MAX_VALUE` 上限 + 每 Agent 新建一份** + 全局**无 `maxConcurrentTurns` 闸门** = 未来若加并发 turn scheduling,每个并发 turn 都一个无界池,**N × `Integer.MAX_VALUE` 理论上限**,数千 turn 即 OOM
    - 与 `ToolExecutorConfig` hygiene 不一致 —— 项目唯一一处"无界池 in production"
- **对应风险**: **R-13**(无新依赖守住)+ hygiene 一致性
- **涉及 ErrorCode**: **0 新 ErrorCode**(纯池 sizing,业务异常仍走现有 LINGS-L0X 系列)

---

## 1. WHY(为什么做这个 Story)

**核心问题**: `AnthropicLlmProvider.ioExecutor` 用 `newCachedThreadPool`,单池 thread 上限 `Integer.MAX_VALUE`:

1. **未来并发 turn 隐患** — `AgentConfig.maxConcurrentTurns` 闸门 Story(#044)落地后,每个 turn 都新建一个无界池,N 并发 turn = N 个无界池,理论上限 N × `Integer.MAX_VALUE` ≈ OOM 数千 turn 即触发
2. **Hygiene 不一致** — 项目里另一个池样板(`ToolExecutorConfig`)是有界 + CallerRunsPolicy 兜底,就 `ioExecutor` 一家挂"无界"
3. **Back-pressure 缺失** — 极端情况下 1 个 turn 内堆积任务,**未来** 若 ReAct 改为多发协作(目前是串行),没有 caller-runs 兜底会直接抛 `RejectedExecutionException`

**Story #043 业务价值**:

- **单池有界** — 镜像 `ToolExecutorConfig`:`ThreadPoolExecutor(corePool=availableProcessors()*2, maxPool=corePool*2, keepAlive=60s, LinkedBlockingQueue(256), CallerRunsPolicy)` —— 单池上限 = `maxPool + queue = cores*4 + 256` 任务
- **Back-pressure** — `CallerRunsPolicy` 在队列满时让 LinearTurnEngine 主线程兜底执行,慢任务而非丢任务
- **Hygiene 一致** — 线程名前缀从 `anthropic-llm-io`(单名)改为 `anthropic-llm-io-N`(N 自增,与 `lingshu-tool-N` 风格对齐,jstack 看得清)
- **JSON friendliness** — `daemon = true` 保留,JVM 自起不会因池线程未关而 hang

**关键不变项**(本 Story 严格限定):

- `LlmProvider` SPI 不变(只是 `AnthropicLlmProvider` 实现层改池 sizing,`stream()` 公开签名 `Actions(reason)+Actions(reason)+Subscriber<...> → CompletableFuture<LlmResponse>` 0 改动)
- `LinearTurnEngine` 公开签名不变(只是兜底时改走主线程执行,行为等价)
- `AgentConfig` 不可变契约不变(0 字段新增 —— Story #044 才加 `maxConcurrentTurns`)
- `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)
- 现有 `AnthropicLlmProvider` 23 测试 case + 现有 fixture streaming + mock HTTP server 全 0 改动
- §4.7 PermissionPolicy / AuditLogger / Cost 域完全兼容
- 9 Slot 体系不变
- 24 字段 AgentConfig schema 不变
- JDK 8 兼容(`ThreadPoolExecutor` + `LinkedBlockingQueue` + `AtomicInteger` + `ThreadFactory` 全部 JDK 8 内置,no `var` / `List.of` / sealed / records)
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **Agent 运维 / 生产部署** | 池上限提升 / jstack 看 `anthropic-llm-io-N` 线程名直接数 in-flight Anthropic HTTP 调用 |
| **Story #044 实施者** | Story #044 加 `maxConcurrentTurns` 闸门时,单池已是有界,乘起来不会爆 |
| **设计文档 reviewer** | 看到 §10 NFR 并发 turn 数(默认 16)真在生产路径上有兜底,docs/code 一致性提升 |
| **新用户** | 跑 demo-product / demo-empty 不再因无界池意外 hang |

---

## 3. WHAT(交付什么 — 用户视角)

### 3.1 用户可见行为

**jstack / 线程 dump 自带命名**:
- 改前:1 个 `anthropic-llm-io` 线程名(所有任务共享)
- 改后:N 个 `anthropic-llm-io-1` / `anthropic-llm-io-2` / ... 线程名(每个任务可被独立追踪)

**极端 burst 场景行为**:
- 改前:queue 无界,任务堆积 → 内存增长
- 改后:`LinkedBlockingQueue(256)` 满 → `CallerRunsPolicy` 让 LinearTurnEngine 主线程兜底执行 → 慢任务而非丢任务(back-pressure 信号)

### 3.2 API / SPI 改动

**0 SPI 改动**:
- `LlmProvider.stream(...)` 签名不变
- `LlmProvider.cancel()` 签名不变(如有)
- `CompletableFuture<LlmResponse>` 返回类型不变
- `AnthropicLlmProvider` 6-arg ctor 不变(同 Story #027a)

**1 实现层改动**:
- `AnthropicLlmProvider.ioExecutor` 字段初始化表达式改写
- 线程名前缀改 `anthropic-llm-io-N`(N 自增)

### 3.3 行为不变性

| 场景 | 改前 | 改后 |
|---|---|---|
| 单 ReAct step 1 个 LLM 调用 | OK(1 任务在飞) | OK(1 任务在飞) |
| 单 ReAct step 1 个 LLM 调用 + 60s SSE | 大 ✅ 与改前一致(1 任务 × 60s) |
| 极端 256+ 任务同时堆到 `ioExecutor` | 改前堆积 + OOM 风险 | 改后 CallerRunsPolicy 兜底,慢任务不丢 |

---

## 4. HOW(交付什么 — 实现视角)

### 4.1 改文件清单(严格 ≤ 5 文件)

| # | 文件 | 类型 | 行数(预估) |
|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` | modify | +15 / -5(替换 ioExecutor 初始化 + 字段改 final 之前不能变) |
| 2 | `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderBoundedPoolTest.java` | new | ~80(L1 unit:5-7 case) |

**严格 ≤ 5 文件边界内** ✓

### 4.2 实现关键点

**(1)** `AnthropicLlmProvider.java` 字段替换为:

```java
// 改前 L74-78:
private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "anthropic-llm-io");
    t.setDaemon(true);
    return t;
});

// 改后:
private final ExecutorService ioExecutor = buildBoundedIoExecutor();

private static ExecutorService buildBoundedIoExecutor() {
    final int corePoolSize = Runtime.getRuntime().availableProcessors() * 2;
    final int maxPoolSize = corePoolSize * 2;
    final long keepAliveSeconds = 60L;
    final int queueCapacity = 256;
    final AtomicInteger threadSeq = new AtomicInteger(0);
    ThreadFactory ioThreadFactory = new ThreadFactory() {
        @Override public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "anthropic-llm-io-" + threadSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    };
    return new ThreadPoolExecutor(
        corePoolSize,
        maxPoolSize,
        keepAliveSeconds,
        TimeUnit.SECONDS,
        new LinkedBlockingQueue<Runnable>(queueCapacity),
        ioThreadFactory,
        new ThreadPoolExecutor.CallerRunsPolicy());
}
```

镜像 `ToolExecutorConfig.java:62-69` 100% 同 shape。

**(2)** 新 import:`java.util.concurrent.{LinkedBlockingQueue, ThreadFactory, ThreadPoolExecutor, TimeUnit}` + `java.util.concurrent.atomic.AtomicInteger`(已有 import 仅去 `Executors`,去 `ExecutorService` 保留)

**(3)** 新测试 `AnthropicLlmProviderBoundedPoolTest`(~80 行,5-7 L1 case):

- `poolSize_isBounded_corePoolSizeIsAvailableProcessorsTimes2` — 反射读 `ioExecutor` 字段,cast `ThreadPoolExecutor`,assert `corePoolSize == availableProcessors() * 2`
- `poolSize_maxPoolSizeIsCoreTimes2` — 同上 `maxPoolSize`
- `queue_isBounded_LinkedBlockingQueueOf256` — 同上 cast `BlockingQueue` / `LinkedBlockingQueue.remainingCapacity() <= 256`
- `rejectedHandler_isCallerRunsPolicy` — 同上 cast `ThreadPoolExecutor.RejectedExecutionHandler`,assert `instanceof CallerRunsPolicy`
- `threadName_prefix_isAnthropicLlmIo_N` — submit 一个 Runnable 到 `ioExecutor`,反射读 active thread name,assert `startsWith("anthropic-llm-io-")`
- `daemon_threads_doNotBlockJvmExit` — submit + 等启动,`Thread.currentThread().getThreadGroup() != t.getThreadGroup()` 检查 daemon flag
- `stream_call_executesOnAnthropicIoExecutor_threadNameMatches` — 调 `stream()` 并反查 SSE log 抓取的 `Thread.currentThread().getName()`(可借助 `doPostStream` 私有方法 log) → 实际不可见 thread 名(私有 log 不外露),改用反射触发 submit 后查 active thread pool 线程数 ≥ 1

**实际 5-6 case 足够**,最后一个 case 受 ioExecutor 私有,改成"反射验证 field 类型是 ThreadPoolExecutor" 即足够覆盖

### 4.3 风险与回退

- **回退成本极低** — 仅 `AnthropicLlmProvider` 字段初始化逻辑变更,无 API 改动,回滚 `git revert <commit>` 即可
- **无运行时回归** — 单 turn 1 任务在飞的现状下,有界池 vs 无界池行为等价(`CallerRunsPolicy` 在队列未满时不被触发)
- **回归测试** — 现有 23 case `AnthropicTest` 系列 + 现有 fixture streaming 全部不需改动

---

## 5. AC(Acceptance Criteria — 黑盒可验证)

### AC-NN-deps-1: R-13 守住(0 新 Maven 依赖)
- `mvn -pl lingshu-core dependency:tree` pre/post diff:**0 binary delta**(JDK 8 内置 + `ToolExecutorConfig` 已用同一组类型,无新 binary 引入)
- `banned-dependencies` enforcer Rule 0: passed

### AC-NN-pool-1: ioExecutor 字段为 ThreadPoolExecutor 类型
- 反射 `AnthropicLlmProvider.class.getDeclaredField("ioExecutor").get(anInstance)` → `instanceof ThreadPoolExecutor`
- `getCorePoolSize() == availableProcessors() * 2`
- `getMaximumPoolSize() == (availableProcessors() * 2) * 2`
- `getKeepAliveTime(TimeUnit.SECONDS) == 60`
- `getQueue() instanceof LinkedBlockingQueue`
- `((LinkedBlockingQueue<?>) getQueue()).remainingCapacity() + size() == 256`(初始满)
- `getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy`

### AC-NN-name-1: 线程名前缀 anthropic-llm-io-N
- 反射构造 `AnthropicLlmProvider(...)`,submit 一个 `Runnable.run() { latch.countDown(); }`,`latch.await(5s)`
- `Thread.getAllStackTraces().keySet()` 找到名字 `startsWith("anthropic-llm-io-")` 且 `daemon=true` 的 thread 至少 1 个

### AC-NN-1: 回归 — 现有 AnthropicLlmProvider 测试 0 改动全绿
- `mvn -pl lingshu-core test -Dtest='AnthropicLlmProviderTest,AnthropicStreamProviderIT,AnthropicToolReActIT,AnthropicStreamTestSupport'` 全 PASS
- 23 existing case + 13 stream case + 2 tool-reAct case + fixture 全部 0 改动

### AC-NN-2: 全量测试套件 0 新增 failure
- `mvn -pl lingshu-core test`:existing pass 数 + 5(本 Story 新 case),failure 数 0,error 数 = 2 MCP heartbeat flake pre-existing(CLAUDE.md 已记,与本 Story 无关)
- `banned-dependencies` enforcer build 阶段 fail(R-13 mitigation (d)):passed

---

## 6. Story 完成 Checklist(实施时)

- [ ] spec.md ✅ (本文件)
- [ ] plan.md(创建 `specs/043-anthropic-llm-io-bounded-pool/plan.md` —— 镜像 Story #042 格式,简化为 1 file modify + 1 file new)
- [ ] tasks.md(创建 `specs/043-anthropic-llm-io-bounded-pool/tasks.md` —— 依赖排序:1)改 AnthropicLlmProvider 2)加 test 4)R-13 自查)
- [ ] 分支 `fix/anthropic-llm-io-bounded-pool`(PR-69 编号预占)
- [ ] 1 file modify + 1 file new
- [ ] 5-6 L1 test case 全部 PASS
- [ ] `mvn -pl lingshu-core dependency:tree` 0 binary delta
- [ ] 现有 23+ 测试 0 改动全绿
- [ ] commit message:`feat(llm): Story #043 anthropic-llm-io-bounded-pool — 镜像 ToolExecutorConfig 有界池 + CallerRunsPolicy 兜底 + R-13 0 binary delta`
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 `#043` 行
- [ ] `dsh_agent_design.md` §13 changelog 新增 v1.5.56 行(本 Story 完成)
- [ ] `dsh_agent_design.md` §10 NFR 表 "并发 turn 数" 行加 footnote → Story #044 处理 docs/code 兑现
- [ ] `CLAUDE.md` 同步 v1.3.50 → v1.3.51(本 Story 文档同步)

---

## 7. 关键不变项再确认(Story 边界守住)

- `LlmProvider` SPI 不变 ✓
- `Message` 5 子类 + 字段不变(本 Story 不触碰)✓
- `Prompt` + `ToolSpec` + `ToolRegistry.modelVisibleSpecs()` 不变(本 Story 不触碰)✓
- `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2 守住)✓
- `Tool` SPI 不变 + `LinearTurnEngine` 公开签名不变(本 Story 不触碰)✓
- `AgentConfig` 不可变契约不变(0 字段新增 —— Story #044 才加)✓
- `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)✓
- §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 ✓
- 9 Slot 体系不变 ✓
- 24 字段 AgentConfig schema 不变 ✓
- JDK 8 兼容(`ThreadPoolExecutor` + `LinkedBlockingQueue` + `AtomicInteger` + `ThreadFactory` + `CallerRunsPolicy` 全 JDK 8 内置,no `var` / `List.of` / sealed / records)✓
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)✓
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)✓

---

**Story #043 完成预估**: ≤ 1 文件 modify + ≤ 1 文件 new + ≤ 6 case AC 黑盒 + R-13 0 binary delta —— 1 个 PR 合入
**累计 Story 合入**(完成后): 42 → **43**
**R-13 mitigation (d) PASS 计数**:本 Story = **第 26 次** 0 binary delta
**对应设计文档**: `dsh_agent_design.md` v1.5.56(完成后)
**对应 SpecKit SOP**: `speckit_operator_prompt.md` v1.18(不变)
**对应 SKILL**: `lingshu-spec-driven-dev` v1.0.21(不变)
**对应 Prompt 速查**: `lingshu_spec_prompts.md` v1.0.16(不变)