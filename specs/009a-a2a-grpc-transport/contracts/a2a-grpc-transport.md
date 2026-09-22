# Contracts: Story #009a a2a-grpc-transport

**Story**: Story #009a(锚定 dsh §5.6.3.2 L3174-3320)
**Contracts**: 3 个 — gRPC transport + Router + Cache

---

## Contract A1: `lingshu.contract.a2a-grpc-transport.v1` — gRPC Transport

### A1.1 服务定义(protobuf idl `a2a.proto`)

```protobuf
syntax = "proto3";
package ai.lingshu.a2a.v1;

option java_multiple_files = true;
option java_package = "ai.lingshu.a2a.v1";
option java_outer_classname = "A2aProto";

service A2aService {
  rpc GetCard  (AgentName)     returns (Card);
  rpc Submit   (SubmitRequest) returns (Task);
  rpc GetTask  (TaskId)        returns (Task);
  rpc Cancel   (TaskId)        returns (CancelAck);
  rpc Subscribe(TaskId)        returns (stream TaskEvent);
}

message AgentName     { string name = 1; }
message SubmitRequest { string agent_name = 1; string skill = 2; string input_json = 3; }
message TaskId        { string id = 1; }
message Card {
  string name = 1;
  string description = 2;
  string version = 3;
  repeated string skills = 4;
}
message Task {
  string id = 1;
  string status = 2;          // PENDING / RUNNING / COMPLETED / FAILED / CANCELED
  string result_json = 3;     // matches ToolResult output
  string error = 4;
}
message TaskEvent {
  string task_id = 1;
  string event_type = 2;      // PROGRESS / LOG / PARTIAL_RESULT
  string payload_json = 3;
}
message CancelAck { bool accepted = 1; }
```

### A1.2 Producer(服务端)契约(本 Story **不**实现,留后续)

> 服务端实现留给服务端 Story(本 Story 仅客户端,服务端在 Story #009 或后续 remote service)。

| RPC | 请求 | 响应 | 错误码 |
|---|---|---|---|
| `GetCard` | `AgentName{name="alice"}` | `Card{name="alice", description="...", version="0.1.0", skills=["code", "review"]}` | `NOT_FOUND`(agent 不存在)/ `INTERNAL`(server error)|
| `Submit` | `SubmitRequest{agent_name="alice", skill="code", input_json="{\"prompt\":\"...\"}"}` | `Task{id="task-1", status="COMPLETED", result_json="{\"output\":\"...\"}", error=""}` | `INVALID_ARGUMENT`(skill 不存在)/ `INTERNAL` |
| `GetTask` | `TaskId{id="task-1"}` | `Task{id="task-1", status="RUNNING", result_json="", error=""}` | `NOT_FOUND` |
| `Cancel` | `TaskId{id="task-1"}` | `CancelAck{accepted=true}` | `NOT_FOUND` |
| `Subscribe` | `TaskId{id="task-1"}` | `stream<TaskEvent{task_id, event_type, payload_json}>` | `NOT_FOUND` / `CANCELLED` |

### A1.3 Consumer(客户端)契约 = `GrpcA2aTransport` 实现

| 接口方法 | RPC 调用 | 返回类型 | 异常 |
|---|---|---|---|
| `fetchCard(agentName)` | `blockingStub.getCard(AgentName.newBuilder().setName(agentName).build())` | `Map<String, Object>` | `RuntimeException`(grpc UNAVAILABLE/DEADLINE_EXCEEDED → 负缓存)|
| `submit(agentName, skill, inputJson)` | `blockingStub.submit(SubmitRequest.newBuilder()...build())` | `ToolResult`(success 或 error)| `ToolResult.error(...)`(grpc error 不抛)|
| `get(taskId)` | `blockingStub.getTask(TaskId.newBuilder().setId(taskId).build())` | `ToolResult` | `ToolResult.error(...)` |
| `cancel(taskId)` | `blockingStub.cancel(TaskId.newBuilder().setId(taskId).build()).getAccepted()` | `boolean` | `false`(grpc error)|
| `subscribe(taskId, onEvent)` | `asyncStub.subscribe(...)` + `StreamObserver<TaskEvent>` | `void`(异步触发 `onEvent.accept(payload)`)| `log.error` |

### A1.4 状态码映射

| grpc `Status.Code` | 客户端处理 |
|---|---|
| `OK` | 正常处理 |
| `UNAVAILABLE` | `fetchCard` → 负缓存 + RuntimeException;`submit`/`get` → `ToolResult.error` |
| `DEADLINE_EXCEEDED` | 同上 + log warn(可能需要 retry,**不**自动 retry 留 #011)|
| `NOT_FOUND` | `fetchCard` → 负缓存 + RuntimeException(agent 不存在)|
| `INVALID_ARGUMENT` | `submit` → `ToolResult.error`(skill 不存在)|
| `CANCELLED` | `subscribe` → `onError`(in-flight cancel)|
| 其他 | `log.error` + 包装 RuntimeException |

### A1.5 版本兼容

- protobuf wire format 向前兼容(新字段标 `[deprecated = true]` 或加新 tag)
- service 方法**不**可移除(否则 wire 不兼容);新增方法**可**以,旧 client 忽略
- Card.skills 类型 `repeated string` → 后续若需 `repeated Skill` 必须加新字段 `repeated Skill skills_struct = 5;`,保留 `repeated string skills = 4;`(Story #009d)

---

## Contract A2: `lingshu.contract.a2a-transport-router.v1` — Router

### A2.1 接口签名

```java
@Component
public class A2aTransportRouter extends SlotRouter<Providers.A2aTransportProvider, A2aTransport> {
    public A2aTransportRouter(List<Providers.A2aTransportProvider> providers);
    @Override public A2aTransport resolve(String name, AgentConfig config);   // 继承自 SlotRouter
    @Override public Set<String> available();                                // 继承自 SlotRouter
    @Override public List<String> describe();                                 // 继承自 SlotRouter
    @Override protected Class<A2aTransport> getSlotInterface();
}
```

### A2.2 resolve() 契约

| 输入 | 输出 | 异常 |
|---|---|---|
| `name = "grpc-1.0.0"`(已注册) | `GrpcA2aTransport` 实例 | — |
| `name = "unknown"`(未注册)| — | `IllegalArgumentException`(LINGS-S01)|
| `name = "grpc-1.0.0"` 但 Provider.version() 与 Slot contract v1.0.0 不兼容 | — | `ProviderInitException`(LINGS-S05)|

### A2.3 启动日志契约(继承自父类)

```
[A2aTransport] resolved N provider(s) [contract v1.0.0]:
  ✓ grpc-1.0.0 v1.0.0 -> GrpcA2aTransportProvider [priority=10]
  ✓ http-jsonrpc-1.0.0 v1.0.0 -> HttpJsonRpcA2aTransportProvider [priority=10]  (Story #009c 落地后)
  ✓ in-process-1.0.0 v1.0.0 -> InProcessA2aTransportProvider [priority=10]      (Story #009b 落地后)
```

---

## Contract A3: `lingshu.contract.agent-card-cache.v1` — Cache

### A3.1 接口签名

```java
public final class AgentCardCache {
    public AgentCardCache(Duration cacheTtl);
    public Map<String, Object> get(String agentName);
    public void put(String agentName, Map<String, Object> card);
    public void putNegative(String agentName);
    public void invalidate(String agentName);
    public Stats stats();

    @Getter public static final class Stats {
        private final long hits, misses, negatives;
        public double hitRatio();
    }
}
```

### A3.2 方法契约

#### `get(agentName) → Map<String, Object>`

| 输入 | 输出 | 副作用 |
|---|---|---|
| 已 put,未过期 | card map | `stats.hits++` |
| 未 put | null | `stats.misses++` |
| putNegative,未过期 | null | `stats.negatives++` |
| 已过期(lazy)| null(并 remove)| `stats.misses++` |
| agentName 为 null / 空 | `IllegalArgumentException` | — |

#### `put(agentName, card)`

| 输入 | 输出 | 副作用 |
|---|---|---|
| agentName 非空,card 非 null | — | `cache[agentName] = CacheEntry(card, now + cacheTtl)` |
| agentName 为 null / 空 | `IllegalArgumentException` | — |
| card 为 null | `IllegalArgumentException`(用 putNegative 替代)| — |

#### `putNegative(agentName)`

| 输入 | 输出 | 副作用 |
|---|---|---|
| agentName 非空 | — | `cache[agentName] = CacheEntry(null, now + negativeCacheTtl)` |
| agentName 为 null / 空 | `IllegalArgumentException` | — |

#### `invalidate(agentName)`

| 输入 | 输出 | 副作用 |
|---|---|---|
| agentName 任意 | — | `cache.remove(agentName)`(幂等) |

#### `stats() → Stats`

| 输入 | 输出 | 副作用 |
|---|---|---|
| — | `Stats(hits, misses, negatives)`(immutable snapshot) | — |

#### `Stats.hitRatio() → double`

| 输入 | 输出 | 边界 |
|---|---|---|
| — | `hits / (hits + misses + negatives)` | `0.0` if total == 0(避免除零)|

### A3.3 构造器契约

| 输入 | 输出 | 异常 |
|---|---|---|
| `cacheTtl` 非 null 且 > 0 | `AgentCardCache` 实例 | — |
| `cacheTtl` 为 null / 0 / 负 | — | `IllegalArgumentException`(启动期 fail-fast)|

### A3.4 线程安全契约

- 所有方法 thread-safe(`ConcurrentHashMap` + `AtomicLong`)
- `get()` 多线程并发安全(lazy eviction `cache.remove(key, value)` CAS-safe)
- `put()` 多线程并发安全(ConcurrentHashMap put)
- `stats()` 返回 immutable `Stats` snapshot(读时一致性)

---

## 跨契约依赖图

```
AgentFactory.create(cfg)
  ↓ cfg.getA2aTransport()
A2aTransportRouter.resolve(name, cfg)         [Contract A2]
  ↓ provider.create(cfg)
GrpcA2aTransportProvider.create(cfg)          [Contract A1 + A3]
  ↓ new ManagedChannel(...)
GrpcA2aTransport.<init>(channel, cache)       [Contract A1 + A3]
  ↓
GrpcA2aTransport.fetchCard(agentName)
  ├→ AgentCardCache.get(agentName)            [Contract A3]
  │    └─ 命中 / miss / 负缓存
  ├→ A2aServiceBlockingStub.getCard(...)      [Contract A1]
  └→ AgentCardCache.put(...) or putNegative(...)
```

---

## 兼容性矩阵

| Contract | 版本 | Producer | Consumer | 兼容 |
|---|---|---|---|---|
| A1 gRPC Transport | v1(本 Story)| `GrpcA2aTransport` 客户端 | 远端 grpc server(后续)| ✅ protobuf wire 向前兼容 |
| A2 Router | v1(本 Story)| `A2aTransportRouter` | `AgentFactory` | ✅ 复用 `SlotRouter<P, T>` 父类契约 |
| A3 Cache | v1(本 Story)| `AgentCardCache` | `GrpcA2aTransport`(本 Story)+ `HttpJsonRpcA2aTransport`(#009c)| ✅ API 独立,不耦合 Transport 类型 |

**关键不变项**:3 个 Contract 都不破坏 Story #001—#009 已落地的接口(`A2aTransport` 5 方法契约不变 / `Providers.A2aTransportProvider` typed Provider 不变 / `SlotRouter<P, T>` 父类行为不变 / `AgentConfig.A2a` 已有字段不变)