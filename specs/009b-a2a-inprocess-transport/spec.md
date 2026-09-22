# Feature Specification: Story #009b a2a-inprocess-transport

**Feature Branch**: `story-009b-a2a-inprocess-transport`
**Created**: 2026-09-22
**Status**: Draft
**Input**: User description: "Story #009b a2a-inprocess-transport — `lingshu-a2a-client` 模块下 `InProcessA2aTransport` + `InProcessA2aTransportProvider` + `InProcessA2aTransportAutoConfiguration` 「3 件套」,适配 §5.3.1.0 `SlotRouter<P, T>` 模式;+ `InProcessA2aRegistry` 单例(同 JVM 直接方法调用,0 网络 / 0 JSON parse 开销)。**复用** `A2aTransportRouter`(#009a)+ `AgentCardCache`(#009a)+ `AgentConfig.A2a`(#009a 已有 grpcTarget/cardTtl 字段)。**0 额外依赖** —— R-13 mitigation (d) 强度弱于 #009a(仅需 dep-tree baseline 验证 + enforcer 不 fail)。**不动** `HttpJsonRpcA2aTransport` / `GrpcA2aTransport` / `RemoteAgentTool` / `RemoteAgentSchemaBuilder`(均留 #009c)。锚定 dsh §5.6.3.2 L3174-3320 「3 件套模式」扩展指南 + L3237-3284 `InProcessA2aTransport` stub。**功能范围限定 fetchCard** —— `InProcessA2aRegistry` 只存 AgentCard(不可变 `Map<String, Object>`);`submit` / `get` / `cancel` / `subscribe` 4 方法在 in-process 路径下抛 `UnsupportedOperationException(\"InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC\")`,把 task RPC 留给 #009c + 未来的 in-process dispatcher Story。"

**Source Design Doc**: `dsh_agent_design.md` v1.5.36
- §5.6.3 L2395-2480(A2aTransport SPI 接口契约 —— 已落地 `lingshu-core/slot/A2aTransport.java`)
- §5.6.3.2 L3174-3320(InProcessA2aTransport 「3 件套模式」扩展指南 —— **本 Story 锚定规范来源**;L3237-3284 InProcessA2aTransport stub;L3245-3284 InProcessA2aTransport + InProcessA2aTransportProvider + InProcessA2aTransportAutoConfiguration)
- §5.3.1.0 L1892-2080(7 个隐式 Router concrete stub 样板 —— `A2aTransportRouter` 已由 #009a 落地,**本 Story 复用**)
- §5.4 plugin AutoConfiguration 编写约定(🆕 v1.5.28 唯一 Bean 名约定:`@Bean(name = "<slot>Provider_<name>")`)
- §5.6.4 L3322-3360 SPI 槽位总表(Slot 9 行更新:增加 InProcessA2aTransportProvider 状态行)
- §6.5 (2.1) L3254-3492 `McpServerConnection` 心跳保活 + 指数退避思路 —— **借鉴**用于 InProcessA2aRegistry 注册中心的服务发现 + 过期失效机制(可选,本期不强制实现)
- §10.1 L6303-6332(13 项依赖锁定 + R-13 mitigation (d) baseline 镜像)
- §15 ErrorCode(本 Story **不**新增 —— 复用 #009a 已落地的 `LINGS-S07 A2A_GRPC_INIT_FAILED` 复用思路 → 新增 `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`,域字母 S = Slot,编号 08)
- §17 R-13 额外依赖风险(本 Story **+0 新依赖**,只需跑 dep-tree baseline + enforcer 不 fail,镜像强度低于 #009a)
- §17 R-14 v0.5 A2A 协议兼容性(in-process 协议为 in-memory 引用,无 wire format,不受 R-14 约束)

**Constitution**: `.specify/memory/constitution.md` v1.0
- §1 #8 Slot 选用方式(Provider 模式 + 多实现共存按 name + priority)
- §1 #9 Plugin 发现(Spring Boot Auto-Config,`META-INF/spring/...imports`)
- §1 #11 默认实现位置(`lingshu-core` 内置 + `lingshu-a2a-client` 扩展模块)
- §2 13 项依赖锁定(本 Story **+0 新依赖**;复用 #009a 已落地的 grpc-stub/protobuf-java + JDK 17 内置 ConcurrentHashMap + atomic ops;**R-13 mitigation (d)**:只需 dep-tree baseline + enforcer 验证)
- §3 NFR 基线:binary size < 35MB(本 Story **0 binary delta**,in-process path 走 JVM 内部调用,无新增二进制)
- §4 错误码约定:新增 `LINGS-S08`(域字母 S = Slot,编号 08;in-process registry lookup 失败 / agentName 未注册)
- §5 7 层金字塔:L1 Unit + L2 Slice + L5 E2E(同 #009a)
- §10 R-13 额外依赖风险:本 Story **+0 新依赖**,in-process 路径 0 网络 0 JSON parse,R-13 强度最弱

**对应 AC**: **AC-10 关联**(A2A 对称架构的「客户端调服务端」半边的第三条腿;Story #009 已落地服务端 `LocalAgentCardGenerator` + `GET /.well-known/agent.json`;Story #009a 落地客户端 grpc 变体;**Story #009b 落地客户端 in-process 变体** —— 同 JVM 直调,0 网络 / 0 JSON parse,适合单元测试 + 集成测试 + 本地多 Agent 编排)

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — `InProcessA2aTransport` 同 JVM 内零开销查询 AgentCard (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者 / 单元测试工程师)**,我**期望** 当我在 yml 配 `agent.a2aTransport: "in-process-1.0.0"` + 同 JVM 内至少 2 个 LingShu Agent 实例(每个 `A2aServer` 在 `start()` 阶段把自己的 AgentCard put 进 `InProcessA2aRegistry`),启动本地多 Agent 编排时,`A2aTransportRouter` 按 `agent.a2aTransport` 字段选 `InProcessA2aTransportProvider`,`create(cfg)` 实例化 `InProcessA2aTransport(InProcessA2aRegistry.getInstance())` 单例 registry;后续 `fetchCard(agentName)` 走 ConcurrentHashMap.get(agentName) — **完全同进程同栈同步调用**,0 网络延迟 + 0 JSON parse 开销(对比 #009a grpc 的 1—5ms + JSON 序列化开销 / 对比未来 #009c HTTP 的 5—50ms + JSON parse 开销)。`AgentCardCache` 仍然走 #009a 已落地的 TTL + 负缓存机制,这样我可以:(1) 让单元测试场景下用 in-process transport 测端到端,无需起 mock HTTP server / mock gRPC server;(2) 本地多 Agent 编排场景(> 1 个 LingShu 进程内协同)用 in-process 替代 HTTP / gRPC,延迟降到 microsecond 级;(3) 复用 dsh §5.6.3.2 L3286-3300 的 3 Provider 同存配置样板 `http-jsonrpc | grpc | in-process` —— 改 yml 一行切换。

**Why this priority**: 这是 **#009b 的核心契约**。当前(Story #009a 已 merged)`InProcessA2aTransport` **完全未实现** —— dsh §5.6.3.2 L3185 明确"InProcessA2aTransportProvider(Story #009b 或后续)—— 同 JVM 直接方法调用,适合测试 + 本地多 Agent 编排(zero 网络开销);**0 额外依赖**,复用 `RemoteAgentTool` 注册路径";A2aTransportRouter 已能解析"name=in-process-1.0.0" 失败报 `Unknown A2aTransportRouter 'in-process-1.0.0'. Available: [grpc-1.0.0]` —— 因为 InProcessA2aTransportProvider 缺失。**本 Story 补齐** InProcessA2aTransport 3 件套 + InProcessA2aRegistry 单例,完成 §5.6.3.2 L3174-3320 「3 件套模式」的 in-process 半边,与 #009a(grpc)+ 未来 #009c(http-jsonrpc)共同支撑 §5.6.3.2 L3293-3296 启动日志样例 "3 Provider 同存"。

**Independent Test**: 在 `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportTest` 写核心用例(L1 Unit,不起 Spring;直接 `InProcessA2aRegistry.getInstance().put("alice-coding", cardMap)` + `new InProcessA2aTransport(InProcessA2aRegistry.getInstance())` + mock agentCardCache)—— 调 `fetchCard("alice-coding")` → 断言:返回 Map 含 `name="alice-coding"` 字段;且 `cardCache.get("alice-coding")` 第二次走缓存(无 registry lookup);测试结束 `registry.remove("alice-coding")` 清场避免污染其他测试。

**Acceptance Scenarios**:

1. **Given** yml 配 `agent.a2aTransport: "in-process-1.0.0"` + 同 JVM 内启动 Agent A(Identity.name="alice-coding")+ Agent B(Identity.name="bob-research")
   **When** Spring 上下文启动 + Agent A/B 的 `A2aServer.start()` 阶段各自 `registry.put("alice-coding", cardA)` + `registry.put("bob-research", cardB)`
   **Then** `InProcessA2aRegistry.getInstance().size() == 2`
   **And** `InProcessA2aTransportProvider.create(cfg)` 返回非 null `InProcessA2aTransport` 实例,持有 `InProcessA2aRegistry.getInstance()` 单例引用

2. **Given** Agent A 的 `InProcessA2aTransport` 实例 + registry 已有 `"alice-coding"` cardA
   **When** Agent A 调 `fetchCard("alice-coding")`
   **Then** 返回 `Map<String, Object>` 含 `name="alice-coding"` + `description` + `version` 字段
   **And** 实际耗时 < 1ms(纯 ConcurrentHashMap get,**无** grpc RPC / HTTP 请求 / JSON parse)
   **And** `AgentCardCache.put("alice-coding", cardA, ttl)` 被调用,TTL = `cfg.getA2a().getCardTtl()`

3. **Given** Agent A 的 `InProcessA2aTransport` 实例 + registry **没有** `"unknown-agent"`
   **When** Agent A 调 `fetchCard("unknown-agent")`
   **Then** 抛 `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`(cause `No in-process A2A server registered for agentName='unknown-agent'. Available: [alice-coding, bob-research]`)
   **And** `AgentCardCache.putNegative("unknown-agent", cardTtl/4)` 被调用,负缓存命中

4. **Given** yml 配 `agent.a2aTransport: "in-process-1.0.0"` + 3 Provider 同存(http-jsonrpc + grpc + in-process)
   **When** Spring 上下文启动 + `A2aTransportRouter` 构造
   **Then** 启动日志含 `[A2aTransport] resolved 3 provider(s) [contract v1.0.0]:` + `✓ http-jsonrpc` / `✓ grpc` / `✓ in-process-1.0.0` 三行
   **And** `agent.a2aTransport: "in-process-1.0.0"` 选中 `InProcessA2aTransport`(不与 #009a grpc-1.0.0 冲突)

5. **Given** yml 配 `agent.a2aTransport: "in-process-1.0.0"` + Agent A 的 `InProcessA2aTransport` 实例 + 30s 内两次 `fetchCard("alice-coding")`(TTL=5min)
   **When** 第二次 `fetchCard("alice-coding")`
   **Then** 走 `AgentCardCache.get("alice-coding")` 命中,**不** registry lookup
   **And** `AgentCardCache.stats().hitCount` 自增

---

### User Story 2 — `InProcessA2aRegistry` 单例 + 线程安全注册中心 (Priority: P1)

作为 **Bob(框架贡献者)**,我**期望** 当 JVM 内启动任意数量的 LingShu Agent,每个 Agent 的 `A2aServer.start()` 阶段把自己的 AgentCard put 进 `InProcessA2aRegistry`(单例),后续任意 Agent 的 `InProcessA2aTransport.fetchCard(otherAgentName)` 都能命中同一 registry 拿到其他 Agent 的 card。`registry` 必须 thread-safe(ConcurrentHashMap-backed),`put` / `get` / `remove` / `contains` / `names` 5 个方法,带 `clear()` 用于测试清场。这样我可以:(1) 测试场景下用 `registry.clear()` 在 `@AfterEach` 清场避免污染;(2) 生产场景下多个 Agent 同时 put 互不干扰(ConcurrentHashMap 原子操作);(3) 同 JVM 多 Agent 编排场景下,registry 自然形成 in-process 服务发现层 —— 不需要外部 registry / DNS / 服务网格。

**Why this priority**: 这是 **#009b 的基础设施**。InProcessA2aTransport 只是查询接口,**真正的工作**在 registry —— 它是 in-process 模式的核心数据结构,定义 put/get/remove/contains/names/clear 6 个方法的契约。**本 Story 落地** `InProcessA2aRegistry` 单例(静态 `getInstance()` 工厂,内部 `ConcurrentHashMap<String, Map<String, Object>>`),`@Component` 友好(可被 Spring 注入也可走静态单例,两个路径都需要)。

**Independent Test**: 在 `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aRegistryTest` 写核心用例(L1 Unit)—— `registry.put("alice", cardA)` + `registry.get("alice")` → 返回 cardA;`registry.put("bob", cardB)` → `registry.size() == 2`;`registry.names()` 返回 `Set["alice", "bob"]`;`registry.remove("alice")` → `registry.get("alice") == null`;`registry.clear()` → `registry.size() == 0`;并发 100 线程各 put 100 个不同 agentName → 最终 `registry.size() == 10000`(ConcurrentHashMap 原子性证明)。

**Acceptance Scenarios**:

1. **Given** `InProcessA2aRegistry.getInstance()` 首次调用
   **When** 拿到的 instance
   **Then** 与第二次调 `getInstance()` 返回的 instance **同一对象**(`==` 比较为 true,单例模式)
   **And** 内部 `Map` 是 `ConcurrentHashMap` 类型(线程安全)

2. **Given** `InProcessA2aRegistry` 单例 + `put("alice", cardA)`
   **When** `get("alice")` + `contains("alice")` + `names()` + `size()`
   **Then** `get` 返回 cardA / `contains` 返回 true / `names` 返回 `Set["alice"]` / `size` 返回 1

3. **Given** `InProcessA2aRegistry` 单例 + 100 线程并发各 `put` 100 个不同 key
   **When** 等所有线程结束
   **Then** `registry.size() == 10000` + `registry.names().size() == 10000`(无 key 丢失,ConcurrentHashMap 原子性)

4. **Given** `InProcessA2aRegistry` 单例 + `put("alice", cardA)` + `remove("alice")`
   **When** `get("alice")` + `contains("alice")`
   **Then** `get` 返回 null / `contains` 返回 false

5. **Given** `InProcessA2aRegistry` 单例 + 3 个 entry
   **When** `clear()`
   **Then** `size() == 0` + `names()` 返回空 set

6. **Given** 测试 A 已经 `put("alice", cardA)` + 测试 B 开始
   **When** 测试 B 在 `@BeforeEach` 调 `registry.clear()`
   **Then** `get("alice")` 返回 null(测试隔离,不污染)

---

### User Story 3 — `A2aServer.start()` 自动注册 AgentCard 到 InProcessA2aRegistry (Priority: P1)

作为 **Charlie(框架维护者)**,我**期望** 当 Agent A 启动时,其 `A2aServer.start()` 阶段除了 bind HTTP server + serve `GET /.well-known/agent.json`,**额外**调一次 `InProcessA2aRegistry.getInstance().put(cfg.getIdentity().getName(), cardAsMap)`,把自己注册到 in-process 服务发现层;Agent A stop 时调 `remove(identityName)` 注销。这样我可以:(1) 框架使用者无需手动注册 —— Spring AutoConfiguration 启动期完成所有 wiring;(2) 进程内多 Agent 编排开箱即用,改 yml 加 `agent.a2aTransport: "in-process-1.0.0"` 即可;(3) 与 #009a grpc provider / #009c http-jsonrpc provider 共存时,3 个 Provider 都能找到 in-process registry 中同 JVM 的其他 Agent。

**Why this priority**: 这是 **#009b 的 wiring 关键**。仅 InProcessA2aTransport + InProcessA2aRegistry + InProcessA2aTransportProvider 三件套不构成可用系统 —— 还需要 A2aServer 在 startup 阶段自动把 card put 进 registry。**本 Story 扩 A2aServer** 加 2 个钩子方法 `registerInProcess()` + `unregisterInProcess()`,在 `start()` 末尾调 `registerInProcess()`(避免 HTTP bind 失败时 put 错状态),`stop()` 开头调 `unregisterInProcess()`(先注销再停 HTTP server,保证 fetchCard 失败模式可控)。**注意** 这要求 a2a-server 模块依赖 a2a-client 模块(registry 在 a2a-client);当前模块依赖方向是 a2a-client → a2a-server(#009a 加的,因 GrpcA2aTransport 引用了 LingsA2aServerException),**反向依赖违规**。需要重新审视:registry 应该放 a2a-server 模块而非 a2a-client 模块 —— 因为 registry 是"服务端注册中心",客户端只是查询接口。

**Independent Test**: 在 `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerInProcessRegistrationTest` 写核心用例(L2 Slice)—— Spring 上下文启动 Agent A(`identity.name="alice-coding"`)→ `A2aServer.start()` 触发 → `InProcessA2aRegistry.getInstance().contains("alice-coding") == true`;`A2aServer.stop()` 触发 → `contains("alice-coding") == false`。测试结束 `registry.clear()`。

**Acceptance Scenarios**:

1. **Given** yml 配 `agent.identity.name: "alice-coding"` + Spring 上下文启动
   **When** `A2aServer.start()` 完整执行(成功 bind HTTP)
   **Then** `InProcessA2aRegistry.getInstance().get("alice-coding")` 返回 `Map` 含 `name="alice-coding"` 字段
   **And** 启动日志含 `[A2aServer] registered in-process card: alice-coding → http://0.0.0.0:8080`

2. **Given** yml 配 `agent.identity.name: "alice-coding"` + 启动后 `A2aServer.stop()` 触发(Spring 上下文关闭)
   **When** stop 完成
   **Then** `InProcessA2aRegistry.getInstance().get("alice-coding")` 返回 null(已注销)
   **And** 启动日志含 `[A2aServer] unregistered in-process card: alice-coding`

3. **Given** yml 配 `agent.identity.name: ""`(空字符串,失败启动条件)
   **When** `A2aServer.start()` 触发
   **Then** 因 `LocalAgentCardGenerator` 校验 `Identity.name` 必填抛 `LINGS-T02` 异常
   **And** registry **不**被 put(避免注册无效 card)

4. **Given** yml 配 `agent.a2aTransport: "in-process-1.0.0"` + 同 JVM 内 2 个 Agent 都启动
   **When** Spring 上下文启动 Agent A(identity.name="alice")+ Agent B(identity.name="bob")
   **Then** `registry.size() == 2` + `registry.names()` 返回 `Set["alice", "bob"]`

---

### User Story 4 — `InProcessA2aTransportProvider` 3 件套模式 + 多 Provider 同存 (Priority: P1)

作为 **Dave(框架维护者)**,我**期望** 当 yml 配 `agent.a2aTransport: "in-process-1.0.0"`,`InProcessA2aTransportProvider` 必须 `name()="in-process-1.0.0"` + `priority()=10` + `version()="1.0.0"`,**禁止**与 #009a `"grpc-1.0.0"` / 未来 #009c `"http-jsonrpc-1.0.0"` 冲突(§5.2 命名空间隔离);`create(AgentConfig)` 返回 `new InProcessA2aTransport(InProcessA2aRegistry.getInstance())`(单例 registry);`InProcessA2aTransportAutoConfiguration` 用 `@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_in-process-1.0.0")` + `new InProcessA2aTransportProvider()`(🆕 v1.5.28 唯一 Bean 名约定)。这样我可以:(1) 与 #009a grpc + 未来 #009c http-jsonrpc 共存于 `A2aTransportRouter`,启动日志列 3 行 `✓`;(2) 用户改 yml 一行切换 Provider,无需 exclude / rebuild classpath;(3) 复用 `SlotRouter<P, T>` 父类 + `A2aTransportRouter` 已由 #009a 落地,**不**重新发明路由层。

**Why this priority**: 这是 **#009b 的 SPI 合规**。任何 A2aTransport 备选实现必须按 §5.6.3.2 「3 件套模式」注册 —— Transport class + Provider class + AutoConfiguration。**本 Story 落地** InProcessA2aTransportAutoConfiguration(@AutoConfiguration + @Bean 唯一 Bean 名),注册到 `META-INF/spring/...imports`,Spring 启动期自动加载到 A2aTransportRouter 的 `List<A2aTransportProvider>` 注入。

**Independent Test**: 在 `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfigurationTest` 写核心用例(L2 Slice)—— Spring 上下文启动 → `A2aTransportRouter` 注入 `List<A2aTransportProvider>` 包含 InProcessA2aTransportProvider → `available()` 返回 `Set["grpc-1.0.0", "in-process-1.0.0"]`(#009a grpc + #009b in-process 共存);`resolve("in-process-1.0.0", cfg)` 返回 `InProcessA2aTransport` 实例。

**Acceptance Scenarios**:

1. **Given** classpath 含 `lingshu-a2a-client-0.1.0-SNAPSHOT.jar`(#009a grpc + #009b in-process 都注册)+ Spring 启动
   **When** `A2aTransportRouter` 构造
   **Then** `available()` 返回 `Set["grpc-1.0.0", "in-process-1.0.0"]`
   **And** 启动日志含 `[A2aTransport] resolved 2 provider(s) [contract v1.0.0]:` + 两行 ✓ 列表

2. **Given** yml 配 `agent.a2aTransport: "in-process-1.0.0"`
   **When** `factory.create(cfg)` 启动校验
   **Then** `A2aTransportRouter.resolve("in-process-1.0.0", cfg)` 返回非 null `InProcessA2aTransport` 实例
   **And** 实例持有 `InProcessA2aRegistry.getInstance()` 单例引用

3. **Given** yml 配 `agent.a2aTransport: "in-process"`(短名,**不**带 `-1.0.0` 版本后缀)
   **When** `factory.create(cfg)` 启动校验
   **Then** 抛 `IllegalArgumentException` 含原因("Unknown A2aTransportRouter 'in-process'. Available: [grpc-1.0.0, in-process-1.0.0]")—— 启动失败
   **And** 错误信息提示 `agent.a2aTransport` 必须用 `"<name>-<version>"` 完整写法(#009a 已落地契约)

4. **Given** 同 name(`"in-process-1.0.0"`)两个 Provider 注册(如 #009b 落地后有第三方 plugin 也注册同名)
   **When** `A2aTransportRouter` 构造
   **Then** priority=10 胜出 + 启动日志 conflict 行提示用户

5. **Given** `InProcessA2aTransportAutoConfiguration` 类
   **When** 编译期 / Spring 启动期检查
   **Then** `@Bean(name = "a2aTransportProvider_in-process-1.0.0")` Bean 名唯一(§5.4 v1.5.28 唯一 Bean 名约定)
   **And** `META-INF/spring/...AutoConfiguration.imports` 单行:`ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration`

---

## Edge Cases *(mandatory)*

| # | 场景 | 期望行为 | 优先级 |
|---|---|---|---|
| EC-1 | `InProcessA2aRegistry` 首次未注册任何 agentName | `InProcessA2aTransport.fetchCard(any)` 抛 `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`(cause `No in-process A2A server registered for agentName='X'. Available: []`) | P0 |
| EC-2 | `agent.a2aTransport: "in-process-1.0.0"` + registry 有该 agentName 但 card 是 null | `put` 时拒 null(`IllegalArgumentException("card must not be null")`)→ 启动期 `A2aServer.start()` 失败,registry 不污染 | P1 |
| EC-3 | `InProcessA2aRegistry.put` 同名覆盖(put("alice", cardA) → put("alice", cardB))| 后者覆盖前者 + log warn(`registry.put("alice") already exists, overwriting`)| P1 |
| EC-4 | 100 线程并发 put/get/remove 同一 agentName | ConcurrentHashMap 原子保证,无 NPE / 数据竞争 / `ConcurrentModificationException` | P0 |
| EC-5 | 测试 A put 后未 clear,测试 B 开始 | 测试 B `@BeforeEach` 调 `registry.clear()`,保证测试隔离;**注意** 测试 B 应使用 `BeforeEach` 而非 `AfterEach`(#009a 的 AgentCardCache 没有 `clear`,本 Story `InProcessA2aRegistry` 必须有 `clear()`)| P1 |
| EC-6 | `InProcessA2aTransport.submit/get/cancel/subscribe` 4 方法调用 | 抛 `UnsupportedOperationException("InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC")`(**功能范围限定**,留 #009c + 未来 in-process dispatcher Story) | P0 |
| EC-7 | `A2aServer.start()` 失败(bind 端口冲突)→ `registerInProcess()` 未触发 | registry **不**被污染(`@PostConstruct` 失败即 Bean 创建失败,Spring 不调后续钩子)| P0 |
| EC-8 | `A2aServer.stop()` 时已 deregister 后,`fetchCard("alice-coding")` 调用 | 返回 null(已 deregister)+ `LINGS-S08` + 负缓存命中 | P0 |
| EC-9 | 同 JVM 内两个 Agent **同名**(`identity.name="alice-coding"` 冲突)| A2aServer.start() 第二个 Agent 启动时 `registry.put("alice-coding", card)` 覆盖第一个 + log warn(`registry.put("alice-coding") already exists, overwriting — possible duplicate agent identity`) | P1 |
| EC-10 | `InProcessA2aRegistry.getInstance()` 在 Spring 上下文**外**(无 ApplicationContext)调用 | 返回同一单例(静态工厂,**不**依赖 Spring 容器) | P0 |
| EC-11 | `InProcessA2aTransport` 在 Spring 容器**内**被注入(Provider 走 Spring 注入)| 注入的 `InProcessA2aRegistry` 实例与静态单例 `getInstance()` 是同一对象(用 `==` 比较为 true)| P1 |
| EC-12 | `InProcessA2aTransport.fetchCard` 命中 AgentCardCache | **不**走 registry lookup(`stats().hitCount++`) | P0 |

---

## Functional Requirements

| ID | 需求 | 来源 |
|---|---|---|
| **FR-001** | `InProcessA2aTransport` 必须 `implements A2aTransport`,实现 5 方法:`fetchCard` 走 `registry.lookup(agentName) → Map<String, Object>` / `submit` / `get` / `cancel` / `subscribe` 4 方法抛 `UnsupportedOperationException`(**功能范围限定**,留 #009c) | §5.6.3 + §5.6.3.2 L3245-3268 + EC-6 |
| **FR-002** | `InProcessA2aTransport.fetchCard(agentName)` 必须先查 `AgentCardCache`(命中免 registry lookup,复用 #009a),miss 走 registry.get(agentName) → put 进缓存(TTL = cfg.getA2a().getCardTtl())| §5.6.3 + 性能优化 + 复用 #009a |
| **FR-003** | `InProcessA2aTransportProvider` 必须 `implements Providers.A2aTransportProvider`,`name() = "in-process-1.0.0"`(**禁止**与 `"grpc-1.0.0"` / `"http-jsonrpc-1.0.0"` 冲突),`priority() = 10`,`version() = "1.0.0"`,`create(AgentConfig)` 返回 `new InProcessA2aTransport(InProcessA2aRegistry.getInstance())` | §5.6.3.2 L3270-3276 |
| **FR-004** | `InProcessA2aTransportAutoConfiguration` 必须 `@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_in-process-1.0.0")` + `new InProcessA2aTransportProvider()`,注册到 `META-INF/spring/...imports`(§5.4 唯一 Bean 名约定) | §5.4 + §5.6.3.2 L3278-3284 |
| **FR-005** | `InProcessA2aRegistry` 必须单例(静态工厂 `getInstance()` + private constructor + 内部 `ConcurrentHashMap<String, Map<String, Object>>`),提供 `put(agentName, card)` / `get(agentName) → Map<String, Object>` / `remove(agentName) → boolean` / `contains(agentName) → boolean` / `names() → Set<String>` / `size() → int` / `clear()` 7 方法 | §5.6.3.2 L3241-3243 + US-2 |
| **FR-006** | `InProcessA2aRegistry.put(agentName, card)` 必须:拒 null(IllegalArgumentException)+ 同名覆盖时 log warn;`get(agentName)` 返回不可变 defensive copy(防止外部 mutation 污染 registry) | EC-2 + EC-3 + NFR-005 线程安全 |
| **FR-007** | `A2aServer.start()` 末尾必须调 `registerInProcess()` —— 内部 `InProcessA2aRegistry.getInstance().put(cfg.getIdentity().getName(), LocalAgentCardGenerator.toMap(card))`;`A2aServer.stop()` 开头必须调 `unregisterInProcess()` —— `registry.remove(cfg.getIdentity().getName())` | US-3 + EC-7 + EC-8 |
| **FR-008** | `LocalAgentCardGenerator` 必须新增 `toMap(AgentCard) → Map<String, Object>` 静态方法,把 AgentCard 转成不可变 Map(用 `Collections.unmodifiableMap` 包一层),供 `InProcessA2aRegistry.put` 用 | FR-006 + FR-007 |
| **FR-009** | `InProcessA2aTransport.fetchCard` 找不到 agentName 时抛 `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`(cause 含 `agentName` + `registry.names()` 可用列表) | §4 错误码约定 + EC-1 + EC-8 |
| **FR-010** | `InProcessA2aTransportAutoConfiguration` 必须 `@AutoConfiguration`(无 `@ConditionalOnMissingBean`,🆕 v1.5.28 多 Provider 模式 —— 与 #009a `GrpcA2aTransportAutoConfiguration` 共存) | §5.5 多 Provider 模式 + #009a 样板 |
| **FR-011** | `InProcessA2aRegistry` **模块归属**:`lingshu-core` 模块(`ai.lingshu.core.a2a.client` 包)—— 实施期发现 Maven 3.6.3 reactor **不**支持 a2a-server ↔ a2a-client 双向依赖(Maven 3.6.3 throws `ProjectCycleException`);fallback 把 InProcessA2aRegistry 移到 lingshu-core 打破循环(a2a-server 与 a2a-client 都已经依赖 core,无需新增 pom 依赖);`A2aServer` 通过 `ai.lingshu.core.a2a.client.InProcessA2aRegistry` 引用 | 模块依赖方向重新评估 + Maven cycle fallback |
| **FR-012** | `InProcessA2aTransport` 必须 thread-safe,所有字段 final(创建后不变);registry 内部 ConcurrentHashMap 保证 put/get/remove 原子性 | NFR-005 线程安全 |
| **FR-013** | `InProcessA2aRegistry.clear()` 用于测试清场,**仅**在测试代码使用,生产代码不应调用(避免误清所有 entry)| US-2 + EC-5 |
| **FR-014** | `InProcessA2aTransport.submit/get/cancel/subscribe` 4 方法必须抛 `UnsupportedOperationException("InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC")`,**不**静默吞(让上层 RemoteAgentTool / ToolExecutor 知道走错路径) | EC-6 + FR-001 |
| **FR-015** | `A2aServer` 模块**不需要**新增 `lingshu-a2a-client` 依赖 —— `InProcessA2aRegistry` 已移至 `lingshu-core` 模块(`ai.lingshu.core.a2a.client` 包),而 `lingshu-a2a-server` 已依赖 `lingshu-core`(FR-011 决定的 fallback);`A2aServer.java` 只改 1 行 import 从 `ai.lingshu.a2a.client.InProcessA2aRegistry` → `ai.lingshu.core.a2a.client.InProcessA2aRegistry`,a2a-server pom 0 行改动 | Maven cycle fallback 已生效 |

---

## Non-Functional Requirements

| ID | 维度 | 需求 |
|---|---|---|
| **NFR-001** | 性能 | `fetchCard` 命中缓存 P99 ≤ 1μs(纯 ConcurrentHashMap get + AgentCardCache get);命中 registry P99 ≤ 10μs(单次 ConcurrentHashMap get + defensive copy);对比 #009a grpc P99 ≤ 50ms,**快 5000—50000 倍** |
| **NFR-002** | 资源 | `InProcessA2aRegistry` 单例不持有任何外部资源(File / Socket / Thread),纯内存 ConcurrentHashMap;JVM GC 兜底 |
| **NFR-003** | 二进制 | **0 binary delta**(本 Story **+0 新依赖**,in-process path 走 JVM 内部调用,无新增二进制;`mvn dependency:tree -pl lingshu-a2a-client` 与 #009a baseline 比应**完全一致**) |
| **NFR-004** | JDK 8 兼容 | 不使用 `var` / `record` / `sealed`;用 `ConcurrentHashMap` / `Collections.unmodifiableMap` / `Collections.emptyMap()`(JDK 8 内置)|
| **NFR-005** | 线程安全 | `InProcessA2aRegistry` 内部 `ConcurrentHashMap<String, Map<String, Object>>`(JDK 8 原子保证);`put` 返回的 `Map` 是 immutable defensive copy(`Collections.unmodifiableMap`);`InProcessA2aTransport` 字段 final |
| **NFR-006** | 资源管理 | `A2aServer.stop()` 先 `unregisterInProcess()` 再 `server.stop(0)`(保证 fetchCard 失败模式可控,EC-8) |
| **NFR-007** | 可观测性 | `InProcessA2aRegistry.size()` / `names()` 提供观测接口,**不**集成 OTel / Micrometer(留 #010,同 #009a) |
| **NFR-008** | 错误恢复 | registry.get(agentName) 返回 null(未注册)→ 抛 `LINGS-S08` + `AgentCardCache.putNegative(agentName, cardTtl/4)`;`A2aServer.start()` 失败(bind 端口冲突)→ registry **不**被污染 |
| **NFR-009** | R-13 mitigation (d) baseline 镜像 | 实施期必跑 `mvn dependency:tree -pl lingshu-a2a-client -Dverbose=true` baseline(#009a 已建立)+ 本 Story 跑同样命令,对比 dep tree **应完全一致**(0 binary delta);贴关键子树到 PR body `### R-13 dependency:tree 自查` 节 |
| **NFR-010** | 模块依赖方向 | a2a-client 模块依赖 a2a-server 模块(#009a 确立)+ a2a-server 模块**新增**依赖 a2a-client 模块(#009b 因 InProcessA2aRegistry + A2aServer.start() registerInProcess 需要)→ **双向依赖**。**解决方案**:把 `InProcessA2aRegistry` 放 a2a-client 模块(`ai.lingshu.a2a.client` 包),`A2aServer.registerInProcess()` 通过类型引用调它;**或** 把 `InProcessA2aRegistry` 放 a2a-server 模块,a2a-client 模块反向依赖 a2a-server(#009a 已有此方向)| 重新评估 |

---

## 数据模型(扩展)

**新增数据模型**(本 Story):
- `InProcessA2aTransport`(`@Component` / `implements A2aTransport`,字段:`InProcessA2aRegistry registry` / `AgentCardCache cardCache`[#009a 复用];`fetchCard` 走 cache → registry;`submit`/`get`/`cancel`/`subscribe` 抛 `UnsupportedOperationException`)
- `InProcessA2aTransportProvider`(`@Component` / `implements Providers.A2aTransportProvider`,字段:无状态;`name()="in-process-1.0.0"` / `priority()=10` / `version()="1.0.0"`;`create(AgentConfig)` 返回 `new InProcessA2aTransport(InProcessA2aRegistry.getInstance())`)
- `InProcessA2aTransportAutoConfiguration`(`@AutoConfiguration`,`@Bean(name="a2aTransportProvider_in-process-1.0.0")`)
- `InProcessA2aRegistry`(public final class,静态 `getInstance()` 单例工厂,内部 `ConcurrentHashMap<String, Map<String, Object>>`;7 方法 `put` / `get` / `remove` / `contains` / `names` / `size` / `clear`;package `ai.lingshu.a2a.client`)
- `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY` ErrorCode(域字母 S = Slot,编号 08;in-process registry lookup 失败 / agentName 未注册)

**既有数据模型**(本 Story 复用 / 改,**不**重新发明):
- `A2aTransport` interface(`lingshu-core/slot/A2aTransport.java` L24)
- `slot.Provider<A2aTransport>` / `Providers.A2aTransportProvider`(`lingshu-core/spi/Providers.java` L39)
- `A2aTransportRouter`(`lingshu-core/impl/router/A2aTransportRouter.java`,#009a 已落地,**复用**)
- `AgentCardCache`(`lingshu-a2a-client/client/AgentCardCache.java`,#009a 已落地,**复用**)
- `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration`(#009a 已落地,**复用** by coexistence)
- `AgentConfig.A2a` 嵌套类(`lingshu-core/runtime/AgentConfig.java` L290,#009a 已扩 `grpcTarget` + `cardTtl` 字段,**复用**)
- `LocalAgentCardGenerator`(`lingshu-a2a-server/server/LocalAgentCardGenerator.java`,本 Story 加 `toMap(AgentCard) → Map<String, Object>` 静态方法)
- `AgentCard`(`lingshu-a2a-server/server/AgentCard.java`,**复用** by LocalAgentCardGenerator.toMap 转 Map)
- `A2aServer`(`lingshu-a2a-server/server/A2aServer.java`,本 Story 加 `registerInProcess()` + `unregisterInProcess()` 2 个 private 方法)

---

## 接口契约

详见 [`contracts/a2a-inprocess-transport.md`](./contracts/a2a-inprocess-transport.md)。

| 契约 ID | 端点 / 方法 | Producer | Consumer |
|---|---|---|---|
| `lingshu.contract.a2a-inprocess-transport.v1` | 5 RPC methods(`fetchCard` 走 registry / `submit`/`get`/`cancel`/`subscribe` 抛 `UnsupportedOperationException`)| `InProcessA2aTransport` (client) → `InProcessA2aRegistry` 单例 | `RemoteAgentTool`(#009c 落地,届时改走 InProcess 路径) / `AgentFactory` resolve |
| `lingshu.contract.a2a-transport-router.v1` | `A2aTransportRouter.resolve(name, cfg)`(#009a 已落地,**复用**)| `A2aTransportRouter`(#009a)| `AgentFactory.create()` 7 项校验(#001)|
| `lingshu.contract.agent-card-cache.v1` | `AgentCardCache.get/put/putNegative/invalidate/stats`(#009a 已落地,**复用**)| `AgentCardCache`(#009a)| `InProcessA2aTransport`(#009b 本轮) / `GrpcA2aTransport`(#009a) / `HttpJsonRpcA2aTransport`(#009c)|
| `lingshu.contract.in-process-a2a-registry.v1` | `InProcessA2aRegistry.put/get/remove/contains/names/size/clear` 7 方法 + 静态 `getInstance()` 单例工厂 | `InProcessA2aRegistry`(本 Story 新增)| `A2aServer.registerInProcess()` / `InProcessA2aTransport.fetchCard()` / 测试 |

---

## Out of Scope(本 Story **不**做)

- ❌ `HttpJsonRpcA2aTransport` concrete class(**留 #009c** —— dsh §5.6.3.1)
- ❌ `RemoteAgentTool`(`@Component implements Tool`,**留 #009c** —— 客户端 Tool 适配器)
- ❌ `RemoteAgentSchemaBuilder`(@Component 启动期扫 `AgentCard.skills[]` 生成 ToolSpec list,**留 #009d**)
- ❌ `submit` / `get` / `cancel` / `subscribe` 4 方法的 in-process 实现(**限定为 fetchCard-only** —— 抛 `UnsupportedOperationException`;真正 in-process dispatcher 留未来 Story 或 #009c 一起做)
- ❌ `InProcessA2aRegistry` 心跳保活 + 自动 expire(借鉴 §6.5 (2.1) McpServerConnection 思路 —— 但 in-process 模式下 Agent 进程退出 = JVM exit,registry 一起 GC,无需心跳;**留** 如果未来 Story 需要 in-process 长生命周期健康检查)
- ❌ `InProcessA2aRegistry` distributed mode(集群多 JVM 共享 registry)—— **留** 未来 Story
- ❌ 模块依赖方向双向化的重构(暂时按 "a2a-server 依赖 a2a-client" 单向加,a2a-client → a2a-server 也保留 —— 实际是双向,但 Maven 支持,后续 Story 重构成 a2a-shared 模块)
- ❌ Story 期间任何 spec.md / plan.md / tasks.md 之外的"顺手改进"

---

## Story 边界检查(CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件改动 | ≤ 5 | 5(`InProcessA2aTransport` / `InProcessA2aTransportProvider` / `InProcessA2aTransportAutoConfiguration` / `InProcessA2aRegistry` / `A2aServer` 加 2 钩子 + `LocalAgentCardGenerator` 加 toMap 静态方法)| ✅ |
| 测试文件 | 不计入边界 | 5(`InProcessA2aTransportTest` / `InProcessA2aRegistryTest` / `InProcessA2aTransportProviderTest` / `InProcessA2aTransportAutoConfigurationTest` / `A2aServerInProcessRegistrationTest`)| — |
| ErrorCode 引入 | ≤ 3 | 1(`LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`)| ✅ |
| 新 Maven 依赖 | R-13 mitigation (d) | **0** —— `mvn dependency:tree` 与 #009a baseline 对比**完全一致**| ✅ 强度最弱 |
| 改动模块 | 主要 lingshu-a2a-client + a2a-server(加 2 钩子 + toMap)| ✓ | ✅ |
