# Contracts: Story #009b a2a-inprocess-transport

**Story**: #009b
**Branch**: `story-009b-a2a-inprocess-transport`
**Created**: 2026-09-22

> 4 个契约 ID;在 in-process 路径上,#009b **只**落地 `fetchCard` —— `submit` / `get` / `cancel` / `subscribe` 4 个 A2aTransport 方法在 in-process transport 上抛 `UnsupportedOperationException`,把 task RPC 留给 #009c + 未来 in-process dispatcher Story。

---

## 契约 1: `lingshu.contract.a2a-inprocess-transport.v1`

**Producer**: `InProcessA2aTransport`(`ai.lingshu.a2a.client` 包)
**Consumer**: `RemoteAgentTool`(#009c 落地后)+ 单元测试场景直接调 `fetchCard`
**稳定性**: ✅ v1.0.0 锁定(contract version field 在 `A2aTransport` interface L28)

### 1.1 协议边界

```
┌─────────────────────────────────────────────────────────────────────┐
│ InProcessA2aTransport(agentName)                                    │
│   ├─ 1. cardCache.get(agentName)                                    │
│   │     └─ hit → return immutable Map                               │
│   │     └─ miss → step 2                                             │
│   ├─ 2. registry.get(agentName)                                      │
│   │     └─ non-null → cardCache.put(agentName, map, cardTtl)        │
│   │                  → return immutable Map                         │
│   │     └─ null → cardCache.putNegative(agentName, cardTtl/4)       │
│   │              → throw LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY    │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.2 5 方法契约

| 方法 | 签名 | 行为契约 | 抛出 |
|---|---|---|---|
| `fetchCard` | `Map<String, Object> fetchCard(String agentName)` | 先 cache 后 registry,命中缓存免 registry lookup | `LINGS-S08`(registry miss) |
| `submit` | `ToolResult submit(String agentName, String skill, String inputJson)` | **抛 UnsupportedOperationException** | `UnsupportedOperationException("InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC")` |
| `get` | `ToolResult get(String taskId)` | **抛 UnsupportedOperationException** | 同上 |
| `cancel` | `boolean cancel(String taskId)` | **抛 UnsupportedOperationException** | 同上 |
| `subscribe` | `void subscribe(String taskId, Consumer<Map<String, Object>> onEvent)` | **抛 UnsupportedOperationException** | 同上 |

### 1.3 性能契约

| 路径 | P99 时延 | 备注 |
|---|---|---|
| 命中 `AgentCardCache` | ≤ 1μs | 纯 ConcurrentHashMap get + Map unmodifiable copy |
| 命中 `InProcessA2aRegistry` | ≤ 10μs | 纯 ConcurrentHashMap get + Map unmodifiable copy + AgentCardCache put |
| Miss(抛 LINGS-S08)| 同命中 registry + AgentCardCache putNegative | — |

对比 #009a grpc P99 ≤ 50ms,**快 5000—50000 倍**。

### 1.4 错误契约(LINGS-S08)

```
message: "No in-process A2A server registered for agentName='X'. Available: [alice-coding, bob-research]"
cause: NoSuchElementException(内部用)
actionable: "Ensure the remote Agent has been started (its A2aServer.start() calls registry.put())
             or change 'agent.a2aTransport' to 'grpc-1.0.0' / 'http-jsonrpc-1.0.0' for cross-JVM transport"
```

### 1.5 调用约束

- **thread-safety**:`InProcessA2aTransport` 字段 final,无状态,任意线程并发调用安全
- **lifecycle**:由 `A2aTransportRouter.resolve(name, cfg)` 在 AgentFactory.create() 时构造,生命周期 = Agent
- **reentrancy**:`fetchCard` 可在另一个 `fetchCard` 处理中调用(registry ConcurrentHashMap 原子)

---

## 契约 2: `lingshu.contract.a2a-transport-router.v1`(#009a 复用,**不**改)

**Producer**: `A2aTransportRouter`(`ai.lingshu.core.impl.router`)
**Consumer**: `AgentFactory.create()` 7 项校验(#001)
**稳定性**: ✅ v1.0.0 锁定

### 2.1 协议边界

```
A2aTransportRouter.resolve("in-process-1.0.0", cfg) → InProcessA2aTransport 实例
A2aTransportRouter.resolve("grpc-1.0.0", cfg)       → GrpcA2aTransport 实例(#009a 复用)
A2aTransportRouter.resolve("unknown", cfg)            → throw IllegalArgumentException(LINGS-S01)
```

### 2.2 #009b 影响

- **`A2aTransportRouter` 行为不变** —— #009b 不改 Router 自身
- **`available()` 集合扩大** —— #009b 后 `Set["grpc-1.0.0", "in-process-1.0.0"]`(#009a 已有 grpc,#009b 加 in-process)
- **`resolve("in-process-1.0.0", cfg)` 返回 `InProcessA2aTransport`** —— #009b 新增路径
- **启动日志变化** —— `resolved N provider(s)` N 从 1 → 2,新增 `✓ in-process-1.0.0 v1.0.0 -> InProcessA2aTransportProvider [priority=10]` 行

---

## 契约 3: `lingshu.contract.agent-card-cache.v1`(#009a 复用,**不**改)

**Producer**: `AgentCardCache`(`ai.lingshu.a2a.client`)
**Consumer**: `InProcessA2aTransport`(#009b 本轮)+ `GrpcA2aTransport`(#009a)+ `HttpJsonRpcA2aTransport`(#009c 未来)
**稳定性**: ✅ v1.0.0 锁定

### 3.1 #009b 影响

- **API 不变** —— 5 方法 `get` / `put` / `putNegative` / `invalidate` / `stats` 签名不变
- **多 Consumer 共存** —— `InProcessA2aTransport`(#009b)+ `GrpcA2aTransport`(#009a)各自持一份独立的 `AgentCardCache` 实例(由 Provider.create(cfg) 构造)
- **TTL 配置不变** —— `cfg.getA2a().getCardTtl()`(#009a 字段,**复用**)

---

## 契约 4: `lingshu.contract.in-process-a2a-registry.v1`

**Producer**: `InProcessA2aRegistry`(`ai.lingshu.core.a2a.client` — Fallback 落位,原计划在 `ai.lingshu.a2a.client`,因 Maven 3.6.3 cycle 移至 core;详 spec.md FR-011)
**Consumer**:
- `A2aServer.registerInProcess()`(`ai.lingshu.a2a.server`,**#009b 新增**)
- `A2aServer.unregisterInProcess()`(**#009b 新增**)
- `InProcessA2aTransport.fetchCard()`(**#009b 新增**)
- 测试代码 `InProcessA2aRegistryTest`

**稳定性**: ✅ v1.0.0 锁定

### 4.1 单例契约

```
InProcessA2aRegistry.getInstance() == InProcessA2aRegistry.getInstance()  // 必须 true
```

- 静态工厂 `getInstance()`,`private constructor`
- 类加载时初始化 `INSTANCE = new InProcessA2aRegistry()`
- **不**依赖 Spring 容器(纯静态单例)

### 4.2 7 方法契约

| 方法 | 签名 | 行为 | 抛出 |
|---|---|---|---|
| `put` | `void put(String agentName, Map<String, Object> card)` | `ConcurrentHashMap.put`;拒 null 抛 IAE;同名覆盖 log warn | `IllegalArgumentException("card must not be null")` |
| `get` | `Map<String, Object> get(String agentName)` | `ConcurrentHashMap.get`;miss 返 null;hit 返 `Collections.unmodifiableMap(defensive copy)` | — |
| `remove` | `boolean remove(String agentName)` | `ConcurrentHashMap.remove`;存在并删返 true | — |
| `contains` | `boolean contains(String agentName)` | `ConcurrentHashMap.containsKey` | — |
| `names` | `Set<String> names()` | `ConcurrentHashMap.keySet` → `Collections.unmodifiableSet` | — |
| `size` | `int size()` | `ConcurrentHashMap.size` | — |
| `clear` | `void clear()` | `ConcurrentHashMap.clear`;**仅**测试用 | — |

### 4.3 线程安全契约

- 内部 `ConcurrentHashMap` 保证原子性
- `get` 返回 defensive copy 防外部 mutation
- `put` / `remove` / `clear` 自身线程安全
- 100 线程并发 put 100 个不同 key,最终 `size() == 10000` 必成立

### 4.4 生命周期契约

- `InProcessA2aRegistry` 单例生命周期 = JVM 进程
- 进程退出时 JVM GC 自动回收,无 shutdown hook 需要
- `clear()` **不**在生产代码使用,**仅**测试 `@BeforeEach` 清场

---

## 契约变更追踪

| 契约 ID | #009a 状态 | #009b 状态 | 变更 |
|---|---|---|---|
| `a2a-grpc-transport.v1` | ✅ 落地 | 不变 | — |
| `a2a-transport-router.v1` | ✅ 落地(grpc-1.0.0)| 不变(available 集合扩大 1 → 2)| **影响** |
| `agent-card-cache.v1` | ✅ 落地 | 不变 | **影响**(多 Consumer)|
| `a2a-inprocess-transport.v1` | ❌ 未落地 | ✅ **本 Story 落地** | **新增** |
| `in-process-a2a-registry.v1` | ❌ 未落地 | ✅ **本 Story 落地** | **新增** |

**新引入契约**:2 个(`a2a-inprocess-transport.v1` + `in-process-a2a-registry.v1`)
**修改契约**:0 个
**影响契约**(不改契约但改变行为):2 个(`a2a-transport-router.v1` + `agent-card-cache.v1`)
