# Data Model: Story #009a a2a-grpc-transport

**Story**: Story #009a(锚定 dsh §5.6.3.2 L3174-3320)
**Source Design**: `dsh_agent_design.md` v1.5.36
**Prerequisite**: Story #001—#009 merged(提供 `AgentConfig.A2a host+port` + `LocalAgentCardGenerator` + `Providers.A2aTransportProvider` + `SlotRouter<P, T>` 等基础设施)

---

## 新增数据模型(8 项)

### DM-01 `GrpcA2aTransport`(`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransport.java`)

**类型签名**:
```java
@Component
public class GrpcA2aTransport implements A2aTransport {
    private final ManagedChannel channel;
    private final A2aServiceGrpc.A2aServiceBlockingStub blockingStub;
    private final A2aServiceGrpc.A2aServiceStub asyncStub;  // for subscribe
    private final AgentCardCache cardCache;
}
```

**字段语义**:
- `channel`:grpc-java `ManagedChannel`(已建连 `cfg.getA2a().getGrpcTarget()`)
- `blockingStub`:grpc-java generated blocking stub(同步 RPC)
- `asyncStub`:grpc-java generated async stub(`subscribe` 流式 RPC 用)
- `cardCache`:AgentCardCache 实例(从 `cfg.getA2a().getCardTtl()` 构造)

**生命周期**:`@PreDestroy close()` 调 `channel.shutdown().awaitTermination(5, SECONDS)`

---

### DM-02 `GrpcA2aTransportProvider`(`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportProvider.java`)

**类型签名**:
```java
@Component
public class GrpcA2aTransportProvider implements Providers.A2aTransportProvider {
    @Override public String name() { return "grpc-1.0.0"; }
    @Override public int    priority() { return 10; }
    @Override public String version() { return "1.0.0"; }
    @Override public A2aTransport create(AgentConfig cfg);
}
```

**create() 行为契约**:
1. 校验 `cfg.getA2a().getGrpcTarget()` 非空(`null` / `trim().isEmpty()` → 抛 `LINGS-S07`)
2. fallback `cfg.getA2a().getCardTtl()` 为 `null` → `Duration.ofMinutes(5)`
3. `ManagedChannel channel = ManagedChannelBuilder.forTarget(grpcTarget).usePlaintext().build();`
4. `AgentCardCache cache = new AgentCardCache(cardTtl);`
5. 返回 `new GrpcA2aTransport(channel, cache);`

---

### DM-03 `GrpcA2aTransportAutoConfiguration`(`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/GrpcA2aTransportAutoConfiguration.java`)

**类型签名**:
```java
@AutoConfiguration
public class GrpcA2aTransportAutoConfiguration {
    @Bean(name = "a2aTransportProvider_grpc-1.0.0")
        @ConditionalOnMissingBean
        public A2aTransportProvider grpcA2aTransportProvider() {
            return new GrpcA2aTransportProvider();
        }
}
```

**SPI 注册**:`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- 内容:`ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration`(单行)

---

### DM-04 `A2aTransportRouter`(`lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`)

**类型签名**:
```java
@Component
public class A2aTransportRouter extends SlotRouter<Providers.A2aTransportProvider, A2aTransport> {
    public A2aTransportRouter(List<Providers.A2aTransportProvider> providers) {
        super(providers, "A2aTransport", LoggerFactory.getLogger(A2aTransportRouter.class));
    }
    @Override protected Class<A2aTransport> getSlotInterface() { return A2aTransport.class; }
}
```

**resolve() 行为契约**(继承自 `SlotRouter<P, T>`):
1. 查 `byName.get(cfg.getA2aTransport())`(LINGS-S01 unknown name 抛 IllegalArgumentException)
2. 校验 `provider.version()` 与 Slot contract v1.0.0 兼容(LINGS-S05 不兼容)
3. 返回 `provider.create(cfg)` 实例

---

### DM-05 `AgentCardCache`(`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/AgentCardCache.java`)

**类型签名**:
```java
public final class AgentCardCache {
    private final ConcurrentHashMap<String, CacheEntry> cache;
    private final Duration cacheTtl;          // 默认 5 min
    private final Duration negativeCacheTtl;  // cacheTtl / 4 = 75s
    private final AtomicLong hits, misses, negatives;

    public AgentCardCache(Duration cacheTtl) { ... }
    public Map<String, Object> get(String agentName) { ... }
    public void put(String agentName, Map<String, Object> card) { ... }
    public void putNegative(String agentName) { ... }
    public void invalidate(String agentName) { ... }
    public Stats stats() { ... }

    private static final class CacheEntry {
        final Map<String, Object> value;     // null = negative cache entry
        final Instant expireAt;
    }

    @Getter public static final class Stats {
        private final long hits, misses, negatives;
        public double hitRatio() { ... }
    }
}
```

**字段语义**:
- `cacheTtl`:正向 cache TTL(默认 5 min)
- `negativeCacheTtl`:负 cache TTL(默认 75s = cacheTtl / 4)
- `hits` / `misses` / `negatives`:AtomicLong 计数器(thread-safe)
- `CacheEntry.value`:`null` 表示负 cache entry

---

### DM-06 `AgentConfig.A2a` 扩展(`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` L285-291)

**新增字段**:
```java
@Value
public static class A2a {
    String host;                 // Story #009 — 已有
    Integer port;                // Story #009 — 已有
    // 🆕 Story #009a
    String grpcTarget;           // 默认 "localhost:50051"
    Duration cardTtl;            // 默认 Duration.ofMinutes(5)
}
```

**`defaults()` 更新**:
```java
public static A2a defaults() {
    return new A2a("0.0.0.0", 8080, "localhost:50051", Duration.ofMinutes(5));
}
```

---

### DM-07 `LINGS-S07 A2A_GRPC_INIT_FAILED` ErrorCode

**命名规范**:`LINGS-<域字母><2 位数字>`(域字母 S = Slot,编号 07)

**ErrorCode 字段语义**:
- `errorCode` = `"LINGS-S07"`
- `message` = `"AgentConfig.a2a.grpcTarget must not be null/empty"` 或 `"Failed to initialize gRPC channel for {grpcTarget}: {root cause}"`
- `cause` = `IllegalArgumentException` / `StatusRuntimeException` / `ManagedChannelBuilder` 初始化异常
- `hint` = `"set 'agent.a2a.grpcTarget' in application.yml (e.g. 'localhost:50051'), or check that the remote gRPC server is reachable"`

**抛出点**:
- `GrpcA2aTransportProvider.create()`:`grpcTarget` 为 null / 空字符串(EC-1 + EC-2)
- `GrpcA2aTransport.fetchCard()`:grpc RPC 抛 `StatusRuntimeException` → 包成 RuntimeException(EC-8)
- `GrpcA2aTransport.close()`:`channel.shutdown()` 抛异常(极少见,作为兜底)

---

### DM-08 protobuf idl `a2a.proto`(`lingshu-a2a-client/src/main/proto/a2a.proto`)

**完整内容**:
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
message Card          { string name = 1; string description = 2; string version = 3; repeated string skills = 4; }
message Task          { string id = 1; string status = 2; string result_json = 3; string error = 4; }
message TaskEvent     { string task_id = 1; string event_type = 2; string payload_json = 3; }
message CancelAck     { bool accepted = 1; }
```

**生成 Java stub**(build 产物,不入仓):
- `target/generated-sources/protobuf/java/ai/lingshu/a2a/v1/`:6 message class
- `target/generated-sources/protobuf/grpc-java/ai/lingshu/a2a/v1/A2aServiceGrpc.java`:1 service class

---

## 既有数据模型(本 Story 复用,不改)

| 既有类型 | 路径 | 复用方式 |
|---|---|---|
| `A2aTransport` interface | `lingshu-core/src/main/java/ai/lingshu/core/slot/A2aTransport.java` L24 | `GrpcA2aTransport implements A2aTransport` |
| `Providers.A2aTransportProvider` | `lingshu-core/src/main/java/ai/lingshu/core/spi/Providers.java` L39 | `GrpcA2aTransportProvider implements Providers.A2aTransportProvider` |
| `SlotRouter<P, T>` | `lingshu-core/src/main/java/ai/lingshu/core/spi/SlotRouter.java` | `A2aTransportRouter extends SlotRouter<P, T>` |
| `AgentConfig.A2a` | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` L285-291 | **修改**:扩字段(host/port 不动,加 grpcTarget/cardTtl)|
| `AgentConfig.a2aTransport` | 同上 L62(String 顶层字段)| Router.resolve() 用此字段 |
| `LingsA2aServerException` | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/error/LingsA2aServerException.java` | 复用(errorCode 全局命名空间,Story #009 已落地)|
| `ToolResult` | `lingshu-core/src/main/java/ai/lingshu/core/message/ToolResult.java` | GrpcA2aTransport.submit/get 返 ToolResult |

---

## 数据流图

```
yml: agent.a2aTransport: grpc-1.0.0
                    + agent.a2a.grpcTarget: localhost:50051
                    + agent.a2a.cardTtl: 5m

↓ AgentFactory.create(cfg)

A2aTransportRouter.resolve("grpc-1.0.0", cfg)
  → List<A2aTransportProvider> 注入
  → byName.get("grpc-1.0.0") → GrpcA2aTransportProvider
  → provider.create(cfg)
      → ManagedChannelBuilder.forTarget("localhost:50051").usePlaintext().build()
      → new AgentCardCache(Duration.ofMinutes(5))
      → new GrpcA2aTransport(channel, cache)
  → 返回 GrpcA2aTransport 实例

↓ Agent.run() 调 fetchCard("alice")

GrpcA2aTransport.fetchCard("alice")
  → AgentCardCache.get("alice")  // 命中返 Map,miss 返 null
  → miss → A2aServiceBlockingStub.getCard(AgentName.newBuilder().setName("alice"))
  → grpc server 返 Card message
  → 转 Map<String, Object> + AgentCardCache.put("alice", map)
  → 返回 Map

↓ Agent.run() 调 submit("alice", "skill", "{}")

GrpcA2aTransport.submit(...)
  → A2aServiceBlockingStub.submit(SubmitRequest)
  → grpc server 返 Task message
  → 转 ToolResult.success(task.getResultJson()) 或 ToolResult.error(task.getError())
  → 返回 ToolResult
```

---

## 边界检查(Story 边界 CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 新增类型 | ≤ 5 | 5(GrpcA2aTransport / Provider / AutoConfiguration / A2aTransportRouter / AgentCardCache)| ✅ |
| 修改类型 | 不计入边界 | 1(AgentConfig.A2a 嵌套类扩字段)| — |
| 新增 ErrorCode | ≤ 3 | 1(LINGS-S07)| ✅ |
| 新增 Maven 依赖 | R-13 mitigation (d) | 2(grpc-stub + protobuf-java)+ 1 plugin(protobuf-maven-plugin)| ⚠️ R-13 强依赖镜像 |
| 改动模块 | 主要 lingshu-a2a-client + lingshu-core(Router) | ✅ |
| 测试文件 | 不计入边界 | 5 + 6/4/4/5/2 = 21 case | — |