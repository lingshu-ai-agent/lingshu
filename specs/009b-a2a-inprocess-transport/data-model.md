# Data Model: Story #009b a2a-inprocess-transport

**Story**: #009b
**Branch**: `story-009b-a2a-inprocess-transport`
**Created**: 2026-09-22

---

## 1. 新增类型(4)

### DM-01 `InProcessA2aRegistry` — 单例注册中心

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aRegistry.java`
**Visibility**: `public final`
**Javadoc 摘要**:

> 单例模式的 in-process A2A server 注册中心 —— 同 JVM 内的所有 LingShu Agent 通过 `InProcessA2aRegistry.getInstance()` 拿到同一实例,把自己的 AgentCard put 进去;其他 Agent 通过 `InProcessA2aTransport.fetchCard(agentName)` 走 `registry.get(agentName)` 拿到对应 card。**不**走网络,zero overhead。

**字段**:

| 字段 | 类型 | 可见性 | 说明 |
|---|---|---|---|
| `INSTANCE` | `static final InProcessA2aRegistry` | private | 单例实例,类加载时初始化 |
| `store` | `final ConcurrentHashMap<String, Map<String, Object>>` | private | 内部存储,ConcurrentHashMap 保证线程安全 |

**构造器**: `private InProcessA2aRegistry()` —— 禁止外部 `new`,只能 `getInstance()`

**方法**(7 个 + 1 静态工厂):

| 方法 | 签名 | 说明 |
|---|---|---|
| `getInstance()` | `public static InProcessA2aRegistry` | 单例工厂;**不**走 Spring 容器 |
| `put` | `public void put(String agentName, Map<String, Object> card)` | 拒 null(IllegalArgumentException)+ 同名覆盖 log warn |
| `get` | `public Map<String, Object> get(String agentName)` | 返回不可变 defensive copy(`Collections.unmodifiableMap`);miss 返 null |
| `remove` | `public boolean remove(String agentName)` | 存在并删除返 true,否则返 false |
| `contains` | `public boolean contains(String agentName)` | 包含检查 |
| `names` | `public Set<String> names()` | 返回不可变 Set(`Collections.unmodifiableSet`) |
| `size` | `public int size()` | entry 数 |
| `clear` | `public void clear()` | 清空所有 entry;**仅**用于测试清场 |

**线程安全**:ConcurrentHashMap 原子保证 + `Map` 用 `Collections.unmodifiableMap` 包一层防外部 mutation

---

### DM-02 `InProcessA2aTransport` — in-process 客户端

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransport.java`
**Visibility**: `public class`
**Annotation**: `@Component`
**Interface**: `implements A2aTransport`

**字段**:

| 字段 | 类型 | 可见性 | final? | 说明 |
|---|---|---|---|---|
| `registry` | `final InProcessA2aRegistry` | private | yes | 单例 registry 引用 |
| `cardCache` | `final AgentCardCache` | private | yes | #009a 复用,TTL = cfg.getA2a().getCardTtl() |
| `log` | `static final Logger` | private | yes | slf4j |

**构造器**:
```java
public InProcessA2aTransport(InProcessA2aRegistry registry, AgentCardCache cardCache) {
    this.registry = registry;
    this.cardCache = cardCache;
}
```

**方法**(5 个,实现 A2aTransport 接口):

| 方法 | 签名 | 行为 |
|---|---|---|
| `fetchCard` | `public Map<String, Object> fetchCard(String agentName)` | 1) 查 `cardCache.get(agentName)`;命中返 Map;2) miss 走 `registry.get(agentName)`;非 null → `cardCache.put(agentName, map, cardTtl)` 返 Map;null → `cardCache.putNegative(agentName, cardTtl/4)` + 抛 `LINGS-S08` |
| `submit` | `public ToolResult submit(String agentName, String skill, String inputJson)` | 抛 `UnsupportedOperationException("InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC")` |
| `get` | `public ToolResult get(String taskId)` | 同上抛 UnsupportedOperationException |
| `cancel` | `public boolean cancel(String taskId)` | 同上抛 UnsupportedOperationException |
| `subscribe` | `public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent)` | 同上抛 UnsupportedOperationException |

**Javadoc 摘要**:

> dsh §5.6.3.2 L3245-3268 的 `InProcessA2aTransport` stub 落地 —— 实现 A2aTransport 5 方法,但**功能范围限定 fetchCard**(`submit`/`get`/`cancel`/`subscribe` 抛 UnsupportedOperationException,把 task RPC 留给 #009c + 未来 in-process dispatcher Story)。

---

### DM-03 `InProcessA2aTransportProvider` — Provider 工厂

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportProvider.java`
**Visibility**: `public class`
**Annotation**: `@Component`
**Interface**: `implements Providers.A2aTransportProvider`

**字段**: 无状态

**方法**(4 个,实现 Providers.A2aTransportProvider 接口):

| 方法 | 签名 | 返回值 |
|---|---|---|
| `name` | `public String name()` | `"in-process-1.0.0"`(**禁止**与 `"grpc-1.0.0"` / 未来 `"http-jsonrpc-1.0.0"` 冲突)|
| `priority` | `public int priority()` | `10` |
| `version` | `public String version()` | `"1.0.0"` |
| `create` | `public A2aTransport create(AgentConfig cfg)` | `new InProcessA2aTransport(InProcessA2aRegistry.getInstance(), new AgentCardCache(resolveCardTtl(cfg)))` |

**私有方法**:

| 方法 | 签名 | 说明 |
|---|---|---|
| `resolveCardTtl` | `private Duration resolveCardTtl(AgentConfig cfg)` | 读 `cfg.getA2a().getCardTtl()`,null 时 fallback `Duration.ofMinutes(5)`(同 #009a 兼容旧 AgentConfig.A2a) |

---

### DM-04 `InProcessA2aTransportAutoConfiguration` — Spring Boot SPI 注册

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfiguration.java`
**Visibility**: `public class`
**Annotation**: `@AutoConfiguration`

**方法**(1 个 `@Bean`):

| 方法 | 签名 | 返回值 |
|---|---|---|
| `inProcessA2aTransportProvider` | `@Bean(name = "a2aTransportProvider_in-process-1.0.0")` | `public A2aTransportProvider inProcessA2aTransportProvider()` → `new InProcessA2aTransportProvider()` |

**SPI 注册**:`lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 追加第二行:
```
ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration    ← #009a 已落地
ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration    ← #009b 追加
```

---

## 2. 新增 ErrorCode(1 个)

### EC-01 `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`

**域**:S = Slot
**编号**:08(#009a LINGS-S07 后续)
**触发条件**:`InProcessA2aTransport.fetchCard(agentName)` 时 registry 没有该 agentName entry

**message 模板**:
```
No in-process A2A server registered for agentName='X'. Available: [alice-coding, bob-research]
```

**Actionable 建议**:
```
Ensure the remote Agent has been started (its A2aServer.start() calls registry.put())
or change 'agent.a2aTransport' to 'grpc-1.0.0' / 'http-jsonrpc-1.0.0' for cross-JVM transport
```

**代码位置**:`InProcessA2aTransport.java` 内 inline 抛 `LingshuException` 或 `RuntimeException` 含此 message(**不**新建独立 ErrorCode 类,沿用 #009a 的 LingsA2aServerException 模式 —— 或继承 `ai.lingshu.core.error.LingshuException` 基类)

---

## 3. 修改类型(3 个)

### MD-01 `LocalAgentCardGenerator` — 加 `toMap` 静态方法

**Package**: `ai.lingshu.a2a.server`
**File**: `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java`
**变更**: 在 L91 `toJson(AgentCard)` 之后追加静态方法

**新方法签名**:
```java
public static Map<String, Object> toMap(AgentCard card);
```

**实现**(方案 B - 手动构造 Map):
```java
public static Map<String, Object> toMap(AgentCard card) {
    if (card == null) throw new IllegalArgumentException("card must not be null");
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", card.getName());
    map.put("description", card.getDescription());
    map.put("version", card.getVersion());
    map.put("skills", card.getSkills());             // List<AgentSkill>
    map.put("capabilities", card.getCapabilities()); // AgentCapabilities
    map.put("defaultInputModes", card.getDefaultInputModes());     // List<String>
    map.put("defaultOutputModes", card.getDefaultOutputModes());   // List<String>
    map.put("securitySchemes", card.getSecuritySchemes()); // Object,可能 null
    map.put("security", card.getSecurity());                     // Object,可能 null
    map.put("provider", card.getProvider());         // AgentProvider,可能 null
    map.put("documentationUrl", card.getDocumentationUrl());
    map.put("iconUrl", card.getIconUrl());
    return Collections.unmodifiableMap(map);
}
```

**Javadoc 摘要**:

> 把 `AgentCard` 转成不可变 `Map<String, Object>` —— 供 `InProcessA2aRegistry.put` 存 in-process card;**不可变**防止外部 mutation 污染 registry;采用 LinkedHashMap 保持字段顺序与 AgentCard `@JsonPropertyOrder` 对齐。

---

### MD-02 `A2aServer` — 加 2 个 private 钩子方法

**Package**: `ai.lingshu.a2a.server`
**File**: `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`
**变更**: 在 `start()` L138 末尾前追加 `registerInProcess()` 调;`stop()` L145 开头追加 `unregisterInProcess()` 调;新增 2 个 private 方法

**新 private 方法 1**: `registerInProcess()`
```java
/**
 * Register this server's AgentCard into the in-process registry so peer agents
 * can discover us without going through the network. Called from start() AFTER
 * the HTTP bind succeeds (so failed bind doesn't pollute the registry).
 */
private void registerInProcess() {
    String identityName = cfg.getIdentity() != null ? cfg.getIdentity().getName() : null;
    if (identityName == null || identityName.trim().isEmpty()) {
        return; // identity.name empty → LocalAgentCardGenerator already threw LINGS-T02, never reached here
    }
    AgentCard card = cardRef.get();
    InProcessA2aRegistry.getInstance().put(identityName, LocalAgentCardGenerator.toMap(card));
    LOG.info("[A2aServer] registered in-process card: {} -> http://{}:{}",
        identityName,
        cfg.getA2a() != null ? cfg.getA2a().getHost() : "0.0.0.0",
        actualPort);
}
```

**新 private 方法 2**: `unregisterInProcess()`
```java
/**
 * Remove this server's entry from the in-process registry. Called from stop()
 * BEFORE server.stop(0) so fetchCard failures stay coherent.
 */
private void unregisterInProcess() {
    String identityName = cfg.getIdentity() != null ? cfg.getIdentity().getName() : null;
    if (identityName == null || identityName.trim().isEmpty()) {
        return;
    }
    InProcessA2aRegistry.getInstance().remove(identityName);
    LOG.info("[A2aServer] unregistered in-process card: {}", identityName);
}
```

**start() 末尾(L137 `LOG.info("[A2aServer] listening on...")` 之前)插入**:
```java
registerInProcess();
```

**stop() 开头(L147 `if (server == null)` 之后,`int port = actualPort` 之前)插入**:
```java
unregisterInProcess();
```

**新 import**:
```java
import ai.lingshu.core.a2a.client.InProcessA2aRegistry;
```

---

### MD-03 `lingshu-a2a-server/pom.xml` — **零改动**(Fallback 生效)

**原计划**(已废弃):在 `<dependencies>` 内追加 `<dependency>` 块打破 #009a 单向。

**实际结果**:零改动 —— `InProcessA2aRegistry` 已按 fallback 移至 `lingshu-core`(`ai.lingshu.core.a2a.client` 包);`lingshu-a2a-server` 已依赖 `lingshu-core`(FR-011 / spec.md §Fallback 说明),所以 `A2aServer.registerInProcess()` 直接 import `ai.lingshu.core.a2a.client.InProcessA2aRegistry` 即可,无需新增 pom 依赖。

**实施期注记**:曾尝试在 `a2a-server/pom.xml` 加 `<dependency>lingshu-a2a-client</dependency>`,但 `mvn compile` 报 `ProjectCycleException`(Maven 3.6.3 reactor **不**支持 a2a-server ↔ a2a-client 双向依赖) → 立即 fallback 把 InProcessA2aRegistry 从 `lingshu-a2a-client` 移至 `lingshu-core`,`a2a-server/pom.xml` 不再需要改动。

---

## 4. 复用类型(#009a 已落地,**不**改)

### RT-01 `A2aTransport` interface
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/slot/A2aTransport.java`
- 5 方法契约不变

### RT-02 `A2aTransportRouter`
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`
- extends `SlotRouter<Providers.A2aTransportProvider, A2aTransport>`
- #009b 的 `InProcessA2aTransportProvider` 自动被 `List<Providers.A2aTransportProvider>` 注入,**不**改 Router 自身

### RT-03 `AgentCardCache`
- 路径:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/AgentCardCache.java`
- 5 方法 `get` / `put` / `putNegative` / `invalidate` / `stats`
- **复用**:`InProcessA2aTransport.fetchCard` 命中缓存免 registry lookup

### RT-04 `AgentConfig.A2a`
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`
- 字段:`host` + `port`(#009) + `grpcTarget`(#009a) + `cardTtl`(#009a)
- **复用**:`InProcessA2aTransportProvider.create(cfg)` 读 `cfg.getA2a().getCardTtl()`

### RT-05 `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration`
- 路径:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/Grpc*.java`
- #009a 已落地,**复用** by coexistence(`A2aTransportRouter` 注入 2 Provider)
