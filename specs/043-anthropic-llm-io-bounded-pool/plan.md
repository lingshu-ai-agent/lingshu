# Story #043 `anthropic-llm-io-bounded-pool` — Plan

> **Status**: Draft 2026-10-06
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 tasks**: [`tasks.md`](./tasks.md)
> **核心约束**: 1 modify (AnthropicLlmProvider.java) + 1 new test file + 0 new ErrorCode + 0 new Maven dep —— 严格 ≤ 5 文件边界
> **核心 hygiene**: 镜像 `ToolExecutorConfig.agentToolPool` 100% shape,根除项目里唯一一处 `newCachedThreadPool` 生产代码

---

## 1. 改前 vs 改后 shape(锁定)

### 1.1 改前(L74-78 旧代码)

```java
private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
    Thread t = new Thread(r, "anthropic-llm-io");
    t.setDaemon(true);
    return t;
});
```

- `maximumPoolSize = Integer.MAX_VALUE`(JDK `Executors.java:232` 默认)
- 线程名固定 `anthropic-llm-io`(无 N 后缀)
- 无 `CallerRunsPolicy` 兜底
- 队列无界

### 1.2 改后(L78-131 新代码,已落盘)

```java
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

- `corePoolSize = availableProcessors() * 2` —— I/O-bound 经验值(dsh §14.7)
- `maxPoolSize = corePoolSize * 2` —— burst tolerance
- `keepAliveTime = 60s` —— excess threads reaped after 1 min idle
- `workQueue = LinkedBlockingQueue(256)` —— 队列上限 256 任务
- `rejectedExecutionHandler = CallerRunsPolicy` —— 兜底让 LinearTurnEngine 主线程执行,back-pressure
- 线程名 `anthropic-llm-io-N`(N 自增,per-instance 计数)
- `daemon = true` —— JVM 退出不阻塞

### 1.3 形状对齐验证

`ToolExecutorConfig.java:46-72` vs `AnthropicLlmProvider.buildBoundedIoExecutor()`:

| 字段 | ToolExecutorConfig | AnthropicLlmProvider (Story #043) |
|---|---|---|
| corePoolSize | `cores * 2` | `cores * 2` ✓ |
| maxPoolSize | `corePool * 2` | `corePool * 2` ✓ |
| keepAliveSeconds | `60L` | `60L` ✓ |
| queueCapacity | `256` | `256` ✓ |
| Queue 类型 | `LinkedBlockingQueue<Runnable>` | `LinkedBlockingQueue<Runnable>` ✓ |
| RejectedHandler | `CallerRunsPolicy` | `CallerRunsPolicy` ✓ |
| Thread name | `lingshu-tool-N` | `anthropic-llm-io-N` (per-instance) |
| daemon | `true` | `true` ✓ |

**唯一差异**: 线程名前缀(`lingshu-tool-N` vs `anthropic-llm-io-N`)+ per-instance 计数(`ToolExecutorConfig` 用 `@Bean` 单例 / `AnthropicLlmProvider.ioExecutor` 是 per-Agent `final` 字段,每个 Agent 一份独立计数器)。

---

## 2. 接口契约(0 SPI 改动)

### 2.1 公开方法签名锁定

| 方法 | 签名 | 改动 |
|---|---|---|
| `LlmProvider.stream(Prompt, TurnContext, Subscriber<AgentEvent>) → CompletableFuture<LlmResponse>` | SPI | 不变 ✓ |
| `AnthropicLlmProvider` 6-arg ctor | (baseUrl, apiKey, anthropicVersion, model, maxTokens, temperature) | 不变 ✓ |
| `AnthropicLlmProvider.doPostStream` 私有方法 | (Prompt, TurnContext, Subscriber) → void | 不变 ✓ |
| `AnthropicLlmProvider.ioExecutor` 字段类型 | `ExecutorService` | 不变 ✓(字段类型 + SPI 不变,只换底层实现) |

### 2.2 行为不变性

| 场景 | 改前 | 改后 |
|---|---|---|
| 单 ReAct step 1 个 LLM 调用 | OK(1 任务在飞) | OK(1 任务在飞) — 完全等价 |
| 单 turn 10 steps × 60s SSE | 10 任务串行飞 | 10 任务串行飞 — 完全等价 |
| 极端 256+ 任务同时堆 | 改前:无界堆积 + OOM 风险 | 改后:`CallerRunsPolicy` 让主线程兜底,慢不丢 |

---

## 3. 文件改动(严格 ≤ 5 文件)

### 3.1 已落盘(2026-10-06)

| # | 路径 | 改动 | 行数 |
|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` | modify:5 import + `ioExecutor` 字段替换 + `buildBoundedIoExecutor()` 静态方法 + 类级 Javadoc | +60 / -5 |

### 3.2 本 plan 期间补

| # | 路径 | 改动 | 行数 |
|---|---|---|---|
| 2 | `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderBoundedPoolTest.java` | new L1 unit test,5-6 case | ~110 |

**严格 ≤ 5 文件边界**(实际 2 文件,留 3 文件 buffer 应对未来 fix)。

---

## 4. 测试策略

### 4.1 新增 L1 unit test(`AnthropicLlmProviderBoundedPoolTest`)

5-6 反射型 case(无外部依赖,无 mock HTTP):

| Case | 验证 |
|---|---|
| `poolType_isThreadPoolExecutor` | 反射 `ioExecutor` 字段类型 = `ThreadPoolExecutor`(不是 `ThreadPoolExecutor` 的子类包装) |
| `corePoolSize_isAvailableProcessorsTimes2` | `((ThreadPoolExecutor) ioExecutor).getCorePoolSize() == cores * 2` |
| `maxPoolSize_isCoreTimes2` | `getMaximumPoolSize() == (cores * 2) * 2` |
| `keepAliveTime_is60Seconds` | `getKeepAliveTime(SECONDS) == 60` |
| `queue_isLinkedBlockingQueueOf256` | `getQueue() instanceof LinkedBlockingQueue` + `remainingCapacity() + size() == 256` |
| `rejectedHandler_isCallerRunsPolicy` | `getRejectedExecutionHandler() instanceof CallerRunsPolicy` |
| `threadName_prefix_isAnthropicLlmIo_N` | submit Runnable → `Thread.getAllStackTraces()` 找到名字 `startsWith("anthropic-llm-io-")` + `daemon=true` |
| `daemon_threads_doNotBlockJvmExit` | submit + 等启动 + 验证 `t.isDaemon() == true` |

**反射技巧**: `Field.setAccessible(true)` 读 private final 字段(沿用 Story #027a `AnthropicLlmProviderTest` precedent)。

### 4.2 反向验证(0 回归)

- `mvn -pl lingshu-core test -Dtest='AnthropicLlmProviderTest,AnthropicStreamProviderIT,AnthropicToolReActIT,AnthropicStreamTestSupport'` 23+13+2 case 全 PASS,0 改动
- `mvn -pl lingshu-core test` 全量:现有 pass 数 + 6(本 Story 新 case),failure 0,error = 2 MCP heartbeat flake pre-existing(CLAUDE.md 已记,与本 Story 无关)

### 4.3 L2 / L3 IT 不需要

- 本 Story 是实现层 hygiene fix,**无业务行为变化**(单 turn 1 任务在飞现状下有界 vs 无界行为等价)
- L1 unit test + 现有 L2/L3 IT 不回归 = 充分验证

---

## 5. 实施顺序

1. **T-1**: 已完成 —— `AnthropicLlmProvider.java` modify(2026-10-06 落盘)
2. **T-2**: 写 `AnthropicLlmProviderBoundedPoolTest.java` L1 unit(5-6 case)
3. **T-3**: `mvn -pl lingshu-core test -Dtest='Anthropic*'` 全 PASS,无 regression
4. **T-4**: `mvn -pl lingshu-core dependency:tree` pre/post diff = 仅时间戳差异 = 0 binary delta(第 26 次 PASS)
5. **T-5**: `banned-dependencies` enforcer build 阶段 fail(R-13 mitigation (d)):passed
6. **T-6**: commit + PR + dsh §13 changelog(v1.5.56 行 19 节)+ `constitution.md` §10 R-13 缓解 Story 列表 + README.md 顶部更新日期 + SPECs/ROADMAP.md 段一 ✅ 已完成加 #043 + 段二 🟡 待补 #043 划掉 + 段五 🎯 实施节奏累计 42 → 43 + CLAUDE.md 同步 v1.3.50 → v1.3.51

---

## 6. 关键不变项 + RAC 兜底

- `LlmProvider` SPI 不变 —— 公开方法签名 0 改动 ✓
- `Message` 5 子类 + 字段不变 —— 本 Story 不触碰 ✓
- `Prompt` + `ToolSpec` + `ToolRegistry.modelVisibleSpecs()` 不变 —— 本 Story 不触碰 ✓
- `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2 守住)✓
- `Tool` SPI 不变 + `LinearTurnEngine` 公开签名不变 —— 本 Story 不触碰 ✓
- `AgentConfig` 不可变契约不变(0 字段新增 —— Story #044 才加 `maxConcurrentTurns`)✓
- `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)✓
- §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 ✓
- 9 Slot 体系不变 ✓
- 24 字段 AgentConfig schema 不变 ✓
- JDK 8 兼容(`ThreadPoolExecutor` + `LinkedBlockingQueue` + `AtomicInteger` + `ThreadFactory` + `CallerRunsPolicy` 全 JDK 8 内置,no `var` / `List.of` / sealed / records)✓
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)✓
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)✓

---

## 7. 文件清单

| 路径 | 改动 |
|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` | modify +60 / -5 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderBoundedPoolTest.java` | new ~110 |

**严格 ≤ 5 文件边界**(实际 2 文件,留 3 文件 buffer)。
