# Feature Specification: Story #009a a2a-grpc-transport

**Feature Branch**: `story-009a-a2a-grpc-transport`
**Created**: 2026-09-22
**Status**: Draft
**Input**: User description: "Story #009a a2a-grpc-transport — `lingshu-a2a-client` 模块下 `GrpcA2aTransport` + `GrpcA2aTransportProvider` + `GrpcA2aTransportAutoConfiguration` 「3 件套」,适配 §5.3.1.0 `SlotRouter<P, T>` 模式;+ `A2aTransportRouter` concrete stub;+ `AgentCardCache` 简版 TTL + 负缓存;+ `AgentConfig.A2a` 扩 `grpcTarget` / `cardTtl` 字段。**唯一** Maven 新依赖 = `io.grpc:grpc-stub` + `com.google.protobuf:protobuf-java`(grpc-java + protobuf 链路,R-13 mitigation (d) 强制 +5MB 二进制约束);不动 `HttpJsonRpcA2aTransport` / `RemoteAgentTool` / `RemoteAgentSchemaBuilder`(均留后续 Story)。锚定 dsh §5.6.3.2 L3174-3320 「3 件套模式」扩展指南"

**Source Design Doc**: `dsh_agent_design.md` v1.5.36
- §5.6.3 L2395-2480(A2aTransport SPI 接口契约 —— 已落地在 `lingshu-core/A2aTransport.java`)
- §5.6.3.2 L3174-3320(GrpcA2aTransport / InProcessA2aTransport 「3 件套模式」扩展指南 —— **本 Story 锚定规范来源**)
- §5.3.1.0 L1892-2080(7 个隐式 Router concrete stub 样板 —— `MemorySourceRouter` 已落地,剩 `A2aTransportRouter` 等 6 个)
- §5.4 plugin AutoConfiguration 编写约定(🆕 v1.5.28 唯一 Bean 名约定: `@Bean(name = "<slot>Provider_<name>")`)
- §5.6.4 L3322-3360 SPI 槽位总表(Slot 9 行更新)
- §10.1 L6303-6332(13 项依赖锁定 + R-13 mitigation (d) 强依赖镜像)
- §15 ErrorCode(本 Story 新增 `LINGS-S07 A2A_GRPC_INIT_FAILED`,来源:grpc channel init / ManagedChannelBuilder.forTarget() 解析失败)
- §17 R-13 额外依赖风险 + R-14 v0.5 A2A 协议兼容性

**Constitution**: `.specify/memory/constitution.md` v1.0
- §1 #8 Slot 选用方式(Provider 模式 + 多实现共存按 name + priority)
- §1 #9 Plugin 发现(Spring Boot Auto-Config,`META-INF/spring/...imports`)
- §1 #11 默认实现位置(`lingshu-core` 内置 + `lingshu-a2a-client` 扩展模块)
- §2 13 项依赖锁定(本 Story **+2 新依赖**: `io.grpc:grpc-stub` + `com.google.protobuf:protobuf-java`;**R-13 mitigation (d)** 强制: dep-tree baseline + post-diff + PR body)
- §3 NFR 基线:binary size < 35MB(R-13 mitigation (d));grpc-java 引入约 +5MB,总包仍须 < 35MB baseline
- §4 错误码约定:新增 `LINGS-S07`(域字母 S = Slot,编号 07)
- §5 7 层金字塔:L1 Unit + L1 Unit + L2 Slice + L5 E2E
- §10 R-13 额外依赖风险:本 Story **+2 新依赖**,grpc-java **+5MB binary**,镜像必须执行(`mvn dependency:tree` 自查 + 贴关键子树到 PR body)

**对应 AC**: **AC-10 关联**(A2A 对称架构的「客户端调服务端」半边;Story #009 已落地服务端 `LocalAgentCardGenerator` + `GET /.well-known/agent.json`,本 Story 落地客户端 `GrpcA2aTransport` + `A2aTransportRouter` 路由 + `AgentCardCache` 缓存)

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — `GrpcA2aTransport` 客户端通过 gRPC 调用远程 Agent (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我**期望** 当我在 yml 配 `agent.a2aTransport: "grpc-1.0.0"` + `agent.a2a.grpcTarget: "localhost:50051"`,启动 Agent + 发起跨 Agent 调用时,`A2aTransportRouter` 按 `agent.a2aTransport` 字段选 `GrpcA2aTransportProvider`,`create(cfg)` 用 `ManagedChannelBuilder.forTarget("localhost:50051").usePlaintext().build()` 建连 + 实例化 `GrpcA2aTransport` + `AgentCardCache(cardTtl=5min)`;后续 `fetchCard(agentName)` / `submit(agentName, skill, inputJson)` / `get(taskId)` / `cancel(taskId)` / `subscribe(taskId, onEvent)` 5 个方法都走 grpc-java blocking stub + protobuf binary 序列化。这样我可以:(1) 让本地 LingShu Agent 通过 gRPC 调其他 LingShu 实例(高频小消息 < 1KB,HTTP+JSON 的 JSON parse 开销相对大,gRPC 更高效);(2) 复用 dsh §5.6.3.2 L3294 的 3 Provider 同存配置样板 `http-jsonrpc | grpc | in-process`;(3) gRPC streaming 比 HttpJsonRpcA2aTransport 的 polling 占位更高效(`subscribe()` 用 grpc streaming 而非 1s polling)。

**Why this priority**: 这是 **#009a 的核心契约**。当前(Story #001—#009 已 merged)`A2aTransportRouter` **完全未实现** —— `Routers.java` L31-122 只含 6 个 Router(`LlmProviderRouter` / `ToolExecutorRouter` / `PermissionPolicyRouter` / `PromptBuilderRouter` / `FlowEngineRouter` / `MemorySourceRouter`),Slot 9 的 `A2aTransportRouter` 缺失,Agent 启动期 SlotResolver 屏蔽到 `A2aTransportRouter` 字段为空 → `factory.create(cfg)` 第 7 项校验 `A2aTransport` 字段为空会抛 `LINGS-Z01` 不变量违反。**本 Story 补齐** A2aTransportRouter concrete stub + GrpcA2aTransport 3 件套 + AgentCardCache 简版 + AgentConfig.A2a 扩展,完成 §5.6.3.2 L3174-3320 「3 件套模式」的 grpc 半边。

**Independent Test**: 在 `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportTest` 写核心用例(L1 Unit,不起 Spring;直接 new `GrpcA2aTransport(channel, cardCache)` + mock grpc stub)—— 调 `fetchCard("remote-coding-agent")` → 断言:返回 `Map<String, Object>` 含 `name="remote-coding-agent"` + `description="..."` + `version="..."`,且 `cardCache.get("remote-coding-agent")` 第二次走缓存(无 RPC)。

**Acceptance Scenarios**:

1. **Given** yml 配 `agent.a2aTransport: "grpc-1.0.0"` + `agent.a2a.grpcTarget: "localhost:50051"` + 启动 Agent
   **When** Spring 上下文启动 + `A2aTransportRouter` 注入 `List<A2aTransportProvider>`
   **Then** 启动日志含 `[A2aTransport] resolved N provider(s) [contract v1.0.0]:` + `✓ grpc-1.0.0 v1.0.0 -> GrpcA2aTransportProvider [priority=10]`
   **And** `A2aTransportRouter.resolve("grpc-1.0.0", cfg)` 返回非 null `GrpcA2aTransport` 实例,`channel` 已建连 `localhost:50051`

2. **Given** yml 配 `agent.a2aTransport: "unknown"`(不存在的 transport)
   **When** `factory.create(cfg)` 启动校验
   **Then** 抛 `IllegalArgumentException` 含原因("Unknown A2aTransportRouter 'unknown'. Available: [] / [grpc-1.0.0]")—— 启动失败,进程退出非 0
   **And** 错误信息提示 `agent.a2aTransport` 可选值列表

3. **Given** yml 配 `agent.a2a.grpcTarget: "invalid-host-format:99999"`(无效 grpc target)
   **When** `GrpcA2aTransportProvider.create(cfg)` 实例化
   **Then** 抛 `LINGS-S07 A2A_GRPC_INIT_FAILED`(cause `ManagedChannelBuilder.forTarget()` 解析失败 / `IllegalArgumentException`)
   **And** 错误信息含 `grpcTarget` 字段值 + 启动期 fail-fast,不在运行时挂进程

4. **Given** GrpcA2aTransport 已建连 + 第一次 `fetchCard("alice-coding")`
   **When** 调 `fetchCard("alice-coding")`
   **Then** 返回 `Map<String, Object>` 含 `name` / `description` / `version` 字段(grpc 阻塞 stub 同步返回)
   **And** `AgentCardCache.put("alice-coding", card)` 被调用,TTL = `cfg.getA2a().getCardTtl()`

5. **Given** GrpcA2aTransport 已建连 + 30s 内两次 `fetchCard("alice-coding")`(TTL=5min)
   **When** 第二次 `fetchCard("alice-coding")`
   **Then** 走 `AgentCardCache.get("alice-coding")` 命中,**不**发 grpc RPC
   **And** `AgentCardCache.stats().hitCount` 自增

---

### User Story 2 — `AgentCardCache` 简版 TTL + 负缓存 (Priority: P1)

作为 **Bob(框架贡献者)**,我**期望** 当 `GrpcA2aTransport.fetchCard(agentName)` 调用时,`AgentCardCache` 优先查内存 map(命中免 RPC);未命中 → 走 grpc RPC → 成功 put 进缓存(TTL = `cfg.getA2a().getCardTtl()`);失败 put 进负缓存(短 TTL = `cardTtl / 4`,避免短时间内反复打挂的 remote agent)。`stats()` 提供命中 / 负命中 / 命中率指标,便于 #010 OTel 集成。这样我可以:(1) 避免高频 fetch 重复打 grpc(高频小消息场景 < 1s/pop,gRPC latency 1—5ms,但 1000 次/turn 也吃不消);(2) 负缓存让对端挂掉时不拖垮本地 Agent(避免 5min 内反复打挂的 remote);(3) 命中率指标是后续 observability 的基础。

**Why this priority**: 这是 **#009a 的性能基础**。当前(Story #001—#009 已 merged)零缓存实现,远程 fetchCard 直接穿透,高频场景下 latency + load 都吃不消。**本 Story 落地** `AgentCardCache` 简版(ConcurrentHashMap + TTL + 负缓存 + stats),不引入 Caffeine / Guava Cache(避免新依赖 + R-13 mitigation),JDK `ConcurrentHashMap` + `ScheduledExecutorService` 兜底。

**Independent Test**: 在 `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/AgentCardCacheTest` 写核心用例(L1 Unit)—— `cache.put("alice", card, Duration.ofMinutes(5))` → `cache.get("alice")` 立即命中 → 模拟时间前进 5min → `cache.get("alice")` 返回 null(过期);`cache.putNegative("bob", Duration.ofSeconds(30))` → 30s 内 `cache.get("bob")` 返 null(负缓存命中)。

**Acceptance Scenarios**:

1. **Given** `AgentCardCache(cacheTtl=5min, negativeCacheTtl=75s)`
   **When** `put("alice", card, 5min)` → `get("alice")`
   **Then** 立即返回 `card`,`stats().hitCount == 1`

2. **Given** `AgentCardCache(cacheTtl=5min, negativeCacheTtl=75s)` + put("alice", card, 5min) 后 5min+1s
   **When** `get("alice")`
   **Then** 返回 null(过期),`stats().missCount` 自增;`cache` 内部清理过期 entry(lazy eviction 即可)

3. **Given** `AgentCardCache` + `putNegative("bob", 75s)` 后 30s
   **When** `get("bob")`
   **Then** 返回 null(负缓存命中),**不**发 grpc RPC;`stats().negativeHitCount` 自增

4. **Given** `AgentCardCache` + `putNegative("bob", 75s)` 后 76s(过期)
   **When** `get("bob")`
   **Then** 返回 null(过期清除),下一次 `fetchCard` 会真发 grpc RPC 重新尝试

5. **Given** `AgentCardCache.stats()`
   **When** 调 `stats().hitRatio()`
   **Then** 返回 `hits / (hits + misses + negatives)`,0—1 范围;为 0 时返回 0.0(避免除零异常)

---

### User Story 3 — `A2aTransportRouter` 按 `cfg.getA2aTransport()` 选 Provider + 多 Provider 同存启动日志 (Priority: P1)

作为 **Charlie(框架维护者)**,我**期望** 当 yml 配 `agent.a2aTransport: "grpc-1.0.0"`(后续 #009b 加 `in-process-1.0.0`,#009c 加 `http-jsonrpc-1.0.0` 时多个 Provider 同存),`A2aTransportRouter` 按 `cfg.getA2aTransport()` 字符串查 `byName` map → `provider.create(cfg)` 返回 `A2aTransport` 实例;启动日志列出全部 N 个 Provider 同存(§5.5 多 Provider 模式样板)。这样我可以:(1) 用户改 yml 一行 `agent.a2aTransport` 切换 Provider(grpc / http-jsonrpc / in-process 三选一);(2) 启动日志清晰展示当前所有可用 Provider + 同名冲突(priority 决胜);(3) 复用 `SlotRouter<P, T>` 父类(`lingshu-core/spi/SlotRouter.java`),不重新发明轮子。

**Why this priority**: 这是 **#009a 的路由基础**。当前(Story #001—#009 已 merged)A2aTransportRouter **完全缺失** —— `Routers.java` 6 个 Router 都已落地,剩 `A2aTransportRouter` 一格空缺。**本 Story 落地** Slot 9 Router concrete stub(extends `SlotRouter<Providers.A2aTransportProvider, A2aTransport>`),`@Component` + `@Autowired List<A2aTransportProvider>` + `super(providers, "A2aTransport", LoggerFactory.getLogger(...))`,与 §5.3.1.0 6 个 Router 完全对齐样板。

**Independent Test**: 在 `lingshu-core/src/test/java/ai/lingshu/core/impl/router/A2aTransportRouterTest` 写核心用例(L1 Unit)—— mock 2 个 `A2aTransportProvider`(`name="grpc-1.0.0"` priority=10 + "grpc-2.0.0" priority=20) → `new A2aTransportRouter(List.of(p1, p2))` → 调 `resolve("grpc-2.0.0", cfg)` 断言返回 p2.create(cfg) 实例;同名冲突 `resolve("grpc-1.0.0", cfg)` 走 priority 大的胜出(priority=20)。

**Acceptance Scenarios**:

1. **Given** Spring 容器注入 1 个 `GrpcA2aTransportProvider`(name="grpc-1.0.0", priority=10)
   **When** `new A2aTransportRouter(List.of(provider))` 构造
   **Then** 启动日志含 `[A2aTransport] resolved 1 provider(s) [contract v1.0.0]:` + `✓ grpc-1.0.0 v1.0.0 -> GrpcA2aTransportProvider [priority=10]`
   **And** `available()` 返回 `Set["grpc-1.0.0"]`

2. **Given** Spring 容器注入 2 个 `A2aTransportProvider`(grpc-1.0.0 priority=10 + grpc-2.0.0 priority=20)
   **When** `new A2aTransportRouter(List.of(p1, p2))` 构造
   **Then** 启动日志含 `resolved 2 provider(s)` + 两行 ✓ 列表 + `grpc-1.0.0` 行末尾 `(overrode 0 lower-priority impl(s))` + `grpc-2.0.0` 同样(两者 name 不冲突)
   **And** `available()` 返回 `Set["grpc-1.0.0", "grpc-2.0.0"]`

3. **Given** 2 个同 name A2aTransportProvider(grpc-1.0.0 priority=10 + grpc-1.0.0 priority=20)
   **When** `new A2aTransportRouter(List.of(p1, p2))` 构造
   **Then** priority=20 胜出,p1 进 `conflicts` map
   **And** 启动日志胜出行末尾 `(overrode 1 lower-priority impl(s): GrpcA2aTransportProvider_v1)`

4. **Given** yml 配 `agent.a2aTransport: "grpc-1.0.0"`
   **When** `A2aTransportRouter.resolve("grpc-1.0.0", cfg)`
   **Then** 返回 `GrpcA2aTransportProvider.create(cfg)` 实例(grpc channel 已建连 `cfg.getA2a().getGrpcTarget()`)
   **And** 实例不为 null,`instance instanceof GrpcA2aTransport`

5. **Given** yml 配 `agent.a2aTransport: "unknown"`
   **When** `A2aTransportRouter.resolve("unknown", cfg)`
   **Then** 抛 `IllegalArgumentException`(LINGS-S01)含 `Unknown A2aTransportRouter 'unknown'. Available: [grpc-1.0.0]`

---

## Edge Cases *(mandatory)*

| # | 场景 | 期望行为 | 优先级 |
|---|---|---|---|
| EC-1 | `cfg.getA2a().getGrpcTarget()` 为 null | `GrpcA2aTransportProvider.create()` 抛 `LINGS-S07 A2A_GRPC_INIT_FAILED`(cause `IllegalArgumentException("grpcTarget must not be null")`)| P0 |
| EC-2 | `cfg.getA2a().getGrpcTarget()` 格式非法(如 `"foo bar baz"`) | `ManagedChannelBuilder.forTarget()` 抛 `IllegalArgumentException` → 包成 `LINGS-S07` | P0 |
| EC-3 | `cfg.getA2a().getCardTtl()` 为 null | 走默认值 `Duration.ofMinutes(5)`(`AgentCardCache` 内部 fallback)| P1 |
| EC-4 | `cfg.getA2a().getCardTtl()` ≤ 0(负数 / 零)| 启动期校验抛 `LINGS-S07`(cardTtl must be > 0)| P1 |
| EC-5 | 远端 grpc server 关闭(channel 已建但 server 不通)| 第一次 fetchCard 抛 `StatusRuntimeException`(UNAVAILABLE)→ 包成 `LINGS-S07` + 启动期失败(fail-fast)| P0 |
| EC-6 | 第二次 fetchCard 在缓存 TTL 内(相同 agentName)| 走 cache,**不**发 grpc;`stats().hitCount++` | P1 |
| EC-7 | 负缓存命中(对端挂掉后 30s 内再次 fetchCard)| 返回 null,不重试 grpc(避免反复打挂的 remote)| P1 |
| EC-8 | grpc server 响应超时(> 5s)| 抛 `StatusRuntimeException(DEADLINE_EXCEEDED)` → 包成 `LINGS-S07` + Agent 端 ToolExecutor 5 步流水线返 `ToolResult.error`(`ToolException` 超时,CLAUDE.md §11 #2)| P1 |
| EC-9 | `ManagedChannel.shutdown()` 未调 + Agent 进程退出 | `channel` 资源泄漏(`@PreDestroy` 调 `channel.shutdown().awaitTermination(5, SECONDS)`)| P1 |
| EC-10 | 同一进程内启动两个 Agent(都配 `agent.a2a.grpcTarget: "localhost:50051"`)| 共享 `ManagedChannel`(单例 Bean),**不**重复建连 | P2 |

---

## Functional Requirements

| ID | 需求 | 来源 |
|---|---|---|
| **FR-001** | `GrpcA2aTransport` 必须 `implements A2aTransport`,实现 5 方法(`fetchCard` / `submit` / `get` / `cancel` / `subscribe`),用 grpc-java `ManagedChannel` + generated `A2aServiceGrpc.A2aServiceBlockingStub` | §5.6.3 + §5.6.3.2 L3194-3214 |
| **FR-002** | `GrpcA2aTransportProvider` 必须 `implements Providers.A2aTransportProvider`,`name() = "grpc"`,`priority() = 10`,`version() = "1.0.0"`,`create(AgentConfig)` 返回 `new GrpcA2aTransport(channel, cardCache)` | §5.6.3.2 L3216-3227 |
| **FR-003** | `GrpcA2aTransportAutoConfiguration` 必须 `@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_grpc")` + `new GrpcA2aTransportProvider()`,注册到 `META-INF/spring/...AutoConfiguration.imports` | §5.4 唯一 Bean 名约定 + §5.6.3.2 L3229-3235 |
| **FR-004** | `A2aTransportRouter` 必须 `@Component extends SlotRouter<Providers.A2aTransportProvider, A2aTransport>`,`@Autowired List<Providers.A2aTransportProvider>` + `super(providers, "A2aTransport", LoggerFactory.getLogger(A2aTransportRouter.class))` | §5.3.1.0 + §5.2 |
| **FR-005** | `A2aTransportRouter.resolve(name, cfg)` 必须按 `cfg.getA2aTransport()` 字段查 `byName` map,无匹配抛 `IllegalArgumentException`(LINGS-S01)| §5.2 + §5.3 |
| **FR-006** | `AgentCardCache` 必须 thread-safe (ConcurrentHashMap-based),提供 `get(agentName) → Map<String, Object>` / `put(agentName, card, ttl)` / `putNegative(agentName, ttl)` / `invalidate(agentName)` / `stats() → Stats` 5 方法 | §5.6.3.2 L3224 |
| **FR-007** | `AgentCardCache` 负缓存 TTL = `cacheTtl / 4`(75s for 5min),过期 lazy eviction,不引入 Caffeine / Guava Cache | JDK 8 兼容 + R-13 mitigation (d) |
| **FR-008** | `AgentCardCache` 启动期由 `GrpcA2aTransportProvider.create(cfg)` 用 `new AgentCardCache(cfg.getA2a().getCardTtl())` 构造,生命周期 = GrpcA2aTransport | §5.6.3.2 L3224 |
| **FR-009** | `AgentConfig.A2a` 嵌套类**新增** 2 字段:`grpcTarget`(String, 默认 `"localhost:50051"`)+ `cardTtl`(Duration, 默认 `Duration.ofMinutes(5)`);`A2a.defaults()` 同步更新 | Story 增量 |
| **FR-010** | `AgentConfig.A2a` 启动期校验:grpcTarget 非空 + cardTtl > 0,失败抛 `LINGS-S07 A2A_GRPC_INIT_FAILED` | §4 错误码约定 |
| **FR-011** | `GrpcA2aTransport` 必须实现 `@PreDestroy close()` 调 `channel.shutdown().awaitTermination(5, SECONDS)`,防 channel 资源泄漏 | §5.6 lifecycle |
| **FR-012** | `GrpcA2aTransport.fetchCard(agentName)` 必须先查 `AgentCardCache`(命中免 RPC),miss 走 grpc RPC + put 进缓存(TTL = cfg.getA2a().getCardTtl())| §5.6.3 + 性能优化 |
| **FR-013** | `GrpcA2aTransport.subscribe(taskId, onEvent)` 必须用 grpc streaming(`A2aServiceGrpc.newStub().subscribe(...)` + `StreamObserver`),非 polling | §5.6.3.2 L3210-3213 |
| **FR-014** | `lingshu-a2a-client/pom.xml` 必须新增 2 依赖:`io.grpc:grpc-stub`(version 1.55.x)+ `com.google.protobuf:protobuf-java`(version 3.22.x),传递依赖 grpc-core / netty / guava(grpc 自身传递)| §10.1 + R-13 mitigation (d) |
| **FR-015** | `lingshu-a2a-client/src/main/proto/a2a.proto` 定义 5 个 RPC:`GetCard(AgentName) → Card` / `Submit(SubmitRequest) → Task` / `GetTask(TaskId) → Task` / `Cancel(TaskId) → CancelAck` / `Subscribe(TaskId) → stream TaskEvent` | §5.6.3.2 + protobuf idl 设计 |

---

## Non-Functional Requirements

| ID | 维度 | 需求 |
|---|---|---|
| **NFR-001** | 性能 | `fetchCard` 命中缓存 P99 ≤ 1ms(纯 ConcurrentHashMap get);miss RPC P99 ≤ 50ms(localhost gRPC) |
| **NFR-002** | 性能 | gRPC channel 复用:同一 `grpcTarget` 的 GrpcA2aTransport 共享 `ManagedChannel` 单例(避免每 Agent 重建连) |
| **NFR-003** | 二进制 | `lingshu-a2a-client.jar` 增加 **+5MB**(grpc-stub + protobuf-java + 传递依赖),总 binary < 35MB baseline(R-13 mitigation (d));`mvn dependency:tree` 自查贴 PR body |
| **NFR-004** | JDK 8 兼容 | 不使用 `var` / `record` / `sealed`;`grpc-java` 自身支持 JDK 8;protobuf-generated code 用 `com.google.protobuf.GeneratedMessageLite`(JDK 8 兼容)|
| **NFR-005** | 线程安全 | `AgentCardCache` 必须 thread-safe(ConcurrentHashMap + AtomicLong 计数);`GrpcA2aTransport` 字段 final(创建后不变);`A2aTransportRouter` immutable(初始化后 byName map 不可变)|
| **NFR-006** | 资源管理 | `GrpcA2aTransport.@PreDestroy close()` 必须调 `channel.shutdown().awaitTermination(5, SECONDS)`,JVM shutdown hook 兜底(`Runtime.getRuntime().addShutdownHook(...)`)|
| **NFR-007** | 可观测性 | `AgentCardCache.stats()` 提供 hits / misses / negatives / hitRatio 4 指标,**不**集成 OTel / Micrometer(留 #010)|
| **NFR-008** | 错误恢复 | gRPC channel 建连失败 → `LINGS-S07` 立即失败不重试(运维介入);`fetchCard` RPC 失败 → 负缓存 + `LINGS-S07`,ToolExecutor 5 步流水线返 `ToolResult.error` |
| **NFR-009** | R-13 强依赖镜像 | 实施期必跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true` baseline + post-diff,贴关键子树到 PR body `### R-13 dependency:tree 自查` 节 |

---

## 数据模型(扩展)

**新增数据模型**(本 Story):
- `GrpcA2aTransport`(`@Component` / `implements A2aTransport`,字段:`ManagedChannel channel` / `A2aServiceGrpc.A2aServiceBlockingStub stub` / `AgentCardCache cardCache`)
- `GrpcA2aTransportProvider`(`@Component` / `implements Providers.A2aTransportProvider`,字段:无状态;`name()="grpc"` / `priority()=10` / `version()="1.0.0"`)
- `GrpcA2aTransportAutoConfiguration`(`@AutoConfiguration`,`@Bean(name="a2aTransportProvider_grpc")`)
- `A2aTransportRouter`(`@Component extends SlotRouter<Providers.A2aTransportProvider, A2aTransport>`)
- `AgentCardCache`(`@Component`,字段:`ConcurrentHashMap<String, CacheEntry>` + `AtomicLong hits` / `misses` / `negatives`;nested `CacheEntry{ value: Map, expireAt: long }`)
- `A2aTransport` Slot 9 Router concrete stub(在 `Routers.java` 内 **不**新增,改在 `impl/router/A2aTransportRouter.java` 独立 —— 因 #009a 是 Router stub 第一个独立文件先例)
- `AgentConfig.A2a` 嵌套类扩展 2 字段:`grpcTarget: String` + `cardTtl: Duration`
- `LINGS-S07 A2A_GRPC_INIT_FAILED` ErrorCode(域字母 S = Slot,编号 07;grpc channel init / ManagedChannelBuilder.forTarget() 解析失败 / gRPC RPC runtime exception)
- protobuf idl `src/main/proto/a2a.proto`(5 RPC + 4 message: `Card` / `Task` / `TaskEvent` / `SubmitRequest` / `TaskId` / `CancelAck`)+ maven-protobuf-plugin 生成 Java stub(target/ 不入仓)

**既有数据模型**(本 Story 复用,不改):
- `A2aTransport` interface(`lingshu-core/A2aTransport.java` L24)
- `slot.Provider<A2aTransport>` / `Providers.A2aTransportProvider`(`lingshu-core/spi/Providers.java` L39)
- `AgentConfig.A2a` 嵌套类(`lingshu-core/runtime/AgentConfig.java` L290 已有 host + port)
- `AgentConfig.a2aTransport`(顶层 String 字段,L62,`@Value` Lombok 生成 getter)

---

## 接口契约

详见 [`contracts/a2a-grpc-transport.md`](./contracts/a2a-grpc-transport.md)。

| 契约 ID | 端点 / 方法 | Producer | Consumer |
|---|---|---|---|
| `lingshu.contract.a2a-grpc-transport.v1` | 5 RPC methods (`GetCard` / `Submit` / `GetTask` / `Cancel` / `Subscribe` stream)| `GrpcA2aTransport` (client) → remote grpc server | `RemoteAgentTool` (#009c 落地) / `AgentFactory` resolve |
| `lingshu.contract.a2a-transport-router.v1` | `A2aTransportRouter.resolve(name, cfg)` | `A2aTransportRouter` | `AgentFactory` (`AgentFactory.create()` 7 项校验)|
| `lingshu.contract.agent-card-cache.v1` | `AgentCardCache.get/put/putNegative/invalidate/stats` | `AgentCardCache` | `GrpcA2aTransport` (#009a 本轮) / `HttpJsonRpcA2aTransport` (#009c 后续)|

---

## Out of Scope(本 Story **不**做)

- ❌ `HttpJsonRpcA2aTransport` concrete class(**留 #009c** —— dsh §5.6.3.1 + §5.6.3.2 L2380-2381 的 "默认 Provider" 后续 Story 落地;**注意** 本 Story 落地后 `agent.a2aTransport` 默认值需同步从 `"http-jsonrpc"` 改为 `"grpc"` 或 `"grpc-1.0.0"`,否则启动失败)
- ❌ `RemoteAgentTool`(`@Component implements Tool`,**留 #009c** —— 客户端 Tool 适配器,把 `call_<agentName>` 转发给 `A2aTransport.submit`)
- ❌ `RemoteAgentSchemaBuilder`(@Component 启动期扫 `AgentCard.skills[]` 生成 ToolSpec list,**留 #009d**)
- ❌ `InProcessA2aTransport`(**留 #009b** —— 同 JVM 直接方法调用,InProcessA2aRegistry 单例,0 额外依赖)
- ❌ `mTLS / TLS` gRPC channel(本 Story `usePlaintext()`,TLS 由部署层 Envoy / Istio 统一处理 —— dsh §5.6.3.2 L3222)
- ❌ gRPC 拦截器 / auth metadata / OAuth / Bearer token(留 v1.5+)
- ❌ `ConnectionBackoff` / `RetryPolicy` / `CircuitBreaker`(留 #011)
- ❌ OTel / Micrometer 集成(留 #010,本 Story 仅提供 `AgentCardCache.stats()` 钩子)
- ❌ Push Notification webhook(留 v1.5+)
- ❌ Story 期间任何 spec.md / plan.md / tasks.md 之外的"顺手改进"

---

## Story 边界检查(CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件改动 | ≤ 5 | 5(GrpcA2aTransport / GrpcA2aTransportProvider / GrpcA2aTransportAutoConfiguration / A2aTransportRouter / AgentCardCache)| ✅ |
| 测试文件 | 不计入边界 | 5(GrpcA2aTransportTest / GrpcA2aTransportProviderTest / AgentCardCacheTest / A2aTransportRouterTest / GrpcA2aEndToEndIT)| — |
| ErrorCode 引入 | ≤ 3 | 1(`LINGS-S07 A2A_GRPC_INIT_FAILED`)| ✅ |
| 新 Maven 依赖 | R-13 mitigation (d) | 2(`io.grpc:grpc-stub` + `com.google.protobuf:protobuf-java`,+5MB)| ⚠️ R-13 强制 |
| 改动模块 | 主要 lingshu-a2a-client + lingshu-core(A2aTransportRouter + AgentConfig.A2a)| ✓ | ✅ |