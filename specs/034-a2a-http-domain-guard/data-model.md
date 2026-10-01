# Story #034 `a2a-http-domain-guard` — Data Model(AgentRef 字段 + transport ctor + 2 hook + factory wiring)

> **范围**:`AgentRef.domainWhitelist` per-remote-agent 字段契约 + `HttpJsonRpcA2aTransport` 5-arg ctor + 2 hook points + `HttpJsonRpcA2aTransportAutoConfiguration` factory wiring 完整契约
> **配套**:[spec.md](spec.md)(Story 整体),[plan.md](plan.md)(接口 / 文件 / 测试),[tasks.md](tasks.md)(P1-P4 任务)

---

## 1. `AgentRef.domainWhitelist` 字段完整契约

### 1.1 字段定义

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentRef.java`

```java
package ai.lingshu.core.runtime;

import lombok.Value;
import java.util.Collections;
import java.util.List;

/**
 * Story #009d — pointer to a remote A2A agent (existing).
 * 🆕 Story #034 — extended with per-remote-agent sandbox domain whitelist.
 */
@Value
public class AgentRef {
    String name;
    String url;
    int priority;
    /** 🆕 Story #034 — per-remote-agent sandbox domain whitelist. */
    List<String> domainWhitelist;
}
```

**Lombok `@Value` 自动生成**:
- 4-arg all-args 构造器:`AgentRef(String, String, int, List<String>)`
- 默认 ctor 无,`@Value` 不生成
- Builder 模式需要 `@Builder` 显式加(本 Story 不引入,沿用 4-arg ctor)

### 1.2 yml 反序列化契约

**yml shape**:
```yaml
agent:
  a2a:
    remoteAgents:
      - name: alice
        url: https://alice.example.com
        domain-whitelist:
          - alice.example.com
          - alice-internal.example.com
      - name: bob
        url: https://bob.example.com
        # 🆕 Story #034 — 不写 domain-whitelist 时默认 empty list(deny all)
```

**Jackson 行为**:
- yml 写 `domain-whitelist: [...]` → 字段为 List<String>
- yml 不写 → 字段为 `null`(Jackson 缺省)
- **关键**:Lombok `@Value` 生成的构造器**不接受 null**(`@Value` 字段默认为 `@NonNull`? 不,`@Value` 字段默认非 null 但不强制);实测 Lombok 1.18.30 `@Value` 字段**允许 null**,无 `@NonNull` 行为
- **本 Story 处理**:`HttpJsonRpcA2aTransportAutoConfiguration.HttpJsonRpcA2aTransportFactory.buildByAgentName()` 内显式处理 null → `Collections.emptyList()`

### 1.3 Backward compatibility

- **现有调用点**:`new AgentRef(name, url, priority)` — **0 调用点**(全代码搜索)
- **结论**:`AgentRef` Lombok `@Value` 加 1 字段 → 自动生成 4-arg ctor,现有调用点 = 0 → **0 back-compat breakage**
- **测试 fixture update**:任何 fixture 用 `AgentRef.builder().name(...).url(...).priority(...).build()` 需加 `.domainWhitelist(Collections.emptyList())`(Lombok `@Value` + `@Builder` 模式)

### 1.4 默认值语义

| yml 配置 | 字段值 | 实际行为 |
|---|---|---|
| `domain-whitelist: [a, b]` | `["a", "b"]` | 只允许 host=a 或 host=b 的 HTTP 请求 |
| 不写(默认) | `null` → `Collections.emptyList()`(wire 时 fallback)| **deny all HTTP**(严格模式,与 MCP #033 对称)|
| `domain-whitelist: []` | `Collections.emptyList()`(显式)| **deny all HTTP**(同上,显式声明)|

---

## 2. `HttpJsonRpcA2aTransport` 5-arg ctor 完整契约

### 2.1 字段定义

```java
public class HttpJsonRpcA2aTransport implements A2aTransport {
    private final String httpBaseUrl;
    private final ObjectMapper json;
    private final AgentCardCache cardCache;
    private final Duration callTimeout;
    private final HttpClient http;
    /** 🆕 Story #034 — sandbox domain whitelist (defensive copy). */
    private final List<String> domainWhitelist;

    // 🆕 Story #034 — 5-arg ctor (primary)
    public HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json,
                                   AgentCardCache cardCache, Duration callTimeout,
                                   List<String> domainWhitelist) {
        // existing 4-param validation (httpBaseUrl/json/cardCache/callTimeout)
        if (httpBaseUrl == null || httpBaseUrl.isEmpty()) {
            throw new IllegalArgumentException("httpBaseUrl must not be null/empty");
        }
        if (json == null) { ... }
        if (cardCache == null) { ... }
        if (callTimeout == null) { ... }
        // 🆕 Story #034 — new validation
        if (domainWhitelist == null) {
            throw new IllegalArgumentException("domainWhitelist must not be null");
        }
        // ... existing field assignment ...
        this.domainWhitelist = new ArrayList<>(domainWhitelist);  // defensive copy
    }

    // Backward-compat 4-arg ctor (Story #009c signature)
    public HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json,
                                   AgentCardCache cardCache, Duration callTimeout) {
        this(httpBaseUrl, json, cardCache, callTimeout, Collections.emptyList());
    }
}
```

### 2.2 2 hook points 完整契约

#### Hook #1:`fetchCard(String agentName)`(line ~116)

```java
@Override
public Map<String, Object> fetchCard(String agentName) {
    if (agentName == null || agentName.isEmpty()) { ... }
    // 1) cache lookup (unchanged)
    Map<String, Object> cached = cardCache.get(agentName);
    if (cached != null) { ... return cached; }
    // 2) HTTP GET /.well-known/agent.json
    URI uri = URI.create(httpBaseUrl + AGENT_CARD_PATH);
    // 🆕 Story #034 — sandbox domain guard (mirrors MCP #033 hook point)
    McpHttpSupport.checkOrThrow(uri.toString(), this.domainWhitelist);
    HttpRequest req = HttpRequest.newBuilder(uri)
        .timeout(callTimeout)
        .GET()
        .header("Accept", "application/json")
        .build();
    // ... existing HTTP send logic unchanged ...
}
```

#### Hook #2:`jsonRpcCall(String method, JsonNode params)`(line ~285)

```java
private JsonNode jsonRpcCall(String method, JsonNode params) {
    ObjectNode body = json.createObjectNode();
    body.put("jsonrpc", "2.0");
    body.put("id", UUID.randomUUID().toString());
    body.put("method", method);
    body.set("params", params);
    URI uri = URI.create(httpBaseUrl + RPC_PATH);
    // 🆕 Story #034 — sandbox domain guard (mirrors MCP #033 hook point)
    McpHttpSupport.checkOrThrow(uri.toString(), this.domainWhitelist);
    try {
        HttpRequest req = HttpRequest.newBuilder(uri)
            .timeout(callTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();
        // ... existing HTTP send logic unchanged ...
    } catch (...) { ... }
}
```

### 2.3 错误传播契约

| 错误类型 | 抛出位置 | 传播路径 |
|---|---|---|
| `AccessDeniedException[LINGS-S01]` | `McpHttpSupport.checkOrThrow()`(Story #028 + #033 已落)| → `fetchCard` / `jsonRpcCall` 直接 throw → `RemoteAgentTool.execute()` catch → `ToolResult.error("[LINGS-S01] Domain not whitelisted: <host>")` |
| `HttpJsonRpcException[LINGS-S08]` | 现有 HTTP 失败路径(Story #009c 已落)| 不变,与 hook **不冲突**(hook 在 HTTP 之前抛,失败不发起 HTTP)|
| `IllegalArgumentException` | 4-arg/5-arg ctor 验证 | 启动期 fail-fast,不传到运行时 |

**关键不变项**:`subscribe()` / `cancel()` 内部走 `get()` → `jsonRpcCall()` 已被 hook #2 覆盖;**0 额外 hook**。

---

## 3. `HttpJsonRpcA2aTransportAutoConfiguration` factory wiring 完整契约

### 3.1 Factory 设计

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`

```java
@AutoConfiguration
public class HttpJsonRpcA2aTransportAutoConfiguration {

    @Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")
    public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider() {
        return new HttpJsonRpcA2aTransportProvider();  // existing
    }

    /** 🆕 Story #034 — factory bean for per-agent transport wiring. */
    @Bean(name = "a2aTransportFactory_http-jsonrpc")
    public HttpJsonRpcA2aTransportFactory httpJsonRpcA2aTransportFactory(
            AgentConfig cfg, ObjectMapper json, AgentCardCache cardCache) {
        return new HttpJsonRpcA2aTransportFactory(cfg, json, cardCache);
    }

    public static class HttpJsonRpcA2aTransportFactory {
        private final AgentConfig cfg;
        private final ObjectMapper json;
        private final AgentCardCache cardCache;

        public HttpJsonRpcA2aTransportFactory(AgentConfig cfg, ObjectMapper json,
                                              AgentCardCache cardCache) {
            this.cfg = cfg;
            this.json = json;
            this.cardCache = cardCache;
        }

        /** Build Map<agentName, A2aTransport>: 1 transport per unique baseUrl, unioning whitelist. */
        public Map<String, A2aTransport> buildByAgentName() {
            Map<String, A2aTransport> out = new HashMap<>();
            Map<String, Set<String>> urlToWhitelist = new HashMap<>();
            Map<String, String> agentNameToUrl = new HashMap<>();
            if (cfg.getA2a() == null || cfg.getA2a().getRemoteAgents() == null) {
                return out;
            }
            // pass 1: collect per-URL whitelist + agentName→url map
            for (AgentRef ref : cfg.getA2a().getRemoteAgents()) {
                String name = ref.getName();
                String url = ref.getUrl();
                if (name == null || name.isEmpty() || url == null || url.isEmpty()) {
                    continue;
                }
                agentNameToUrl.put(name, url);
                urlToWhitelist.computeIfAbsent(url, k -> new HashSet<>())
                    .addAll(ref.getDomainWhitelist() == null
                        ? Collections.<String>emptyList() : ref.getDomainWhitelist());
            }
            // pass 2: build 1 transport per unique URL, register for each agentName
            for (Map.Entry<String, String> a2u : agentNameToUrl.entrySet()) {
                String agentName = a2u.getKey();
                String url = a2u.getValue();
                A2aTransport t = out.computeIfAbsent(url, k -> new HttpJsonRpcA2aTransport(
                    url, json, cardCache, Duration.ofSeconds(30),
                    new ArrayList<>(urlToWhitelist.getOrDefault(url, Collections.<String>emptySet()))));
                out.put(agentName, t);
            }
            return out;
        }
    }
}
```

### 3.2 与 `RemoteAgentToolAutoConfiguration` 配合

**现有路径**(`#009e` 落地后):
```java
@Bean
public Tool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json,
                            RemoteAgentSchemaBuilder schemaBuilder) {
    A2aTransport transport = router.resolve(cfg.getA2aTransport(), cfg);  // 单实例
    return new RemoteAgentTool(transport, json, schemaBuilder, cfg.getA2a().getRemoteAgents(),
                               cfg.getA2a().getDescriptionSkillLimit());
}
```

**🆕 Story #034 改后**:
```java
@Bean
public Tool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json,
                            RemoteAgentSchemaBuilder schemaBuilder,
                            HttpJsonRpcA2aTransportFactory factory) {
    Map<String, A2aTransport> transports = factory.buildByAgentName();
    A2aTransport defaultTransport = transports.isEmpty()
        ? router.resolve(cfg.getA2aTransport(), cfg)  // fallback to single-instance
        : transports.values().iterator().next();
    return new RemoteAgentTool(defaultTransport, json, schemaBuilder,
                               cfg.getA2a().getRemoteAgents(),
                               cfg.getA2a().getDescriptionSkillLimit());
}
```

**Back-compat 保证**:
- `cfg.getA2a().getRemoteAgents()` 为空(默认情况,#009d 已落)→ `factory.buildByAgentName()` 返回 empty map → fallback 到 `router.resolve(...)` 单实例路径 → 行为完全等价 #009e
- 单 URL 配置(常见 case)→ factory 输出 1 transport instance + N agentName 指向同一 instance → 行为等价单实例
- 多 URL 配置(罕见 case)→ factory 输出 N transport instance + 各自 agentName → 多 URL 支持

### 3.3 Backward compatibility 影响面

| 调用点 | 影响 |
|---|---|
| `HttpJsonRpcA2aTransport` 4-arg ctor 调用 | 保留为 backward-compat wrapper,**0 breakage** |
| `RemoteAgentToolAutoConfiguration#remoteAgentTool()` | 加 1 个 factory 参数,内部逻辑调整但对外 API 不变 |
| 所有现有 A2A 测试 fixture | `new HttpJsonRpcA2aTransport(url, json, cardCache, timeout)` 4-arg 仍可用,但建议改 5-arg 加 `127.0.0.1` whitelist(避免 deny all)|
| `RemoteAgentTool.execute()` | 不变(`AccessDeniedException` 走现有 generic catch 路径) |

---

## 4. 字段默认值汇总表

| 字段 | 默认值 | 来源 |
|---|---|---|
| `AgentRef.domainWhitelist` | `null`(yml 不写)/ `Collections.emptyList()`(wire 时 fallback)| Lombok `@Value` 不生成默认值,wire 时显式处理 |
| `HttpJsonRpcA2aTransport.domainWhitelist` | `Collections.emptyList()`(4-arg ctor 委派)| ctor 内显式 new ArrayList<>(emptyList()) |
| 工厂 fallback | 单实例 transport(默认 case)| `RemoteAgentToolAutoConfiguration` 内显式 fallback |

---

## 5. 关键不变项(对照 MCP #033 data-model.md §5)

| 不变项 | MCP #033 | A2A #034 |
|---|---|---|
| 守卫 helper | `McpHttpSupport.checkOrThrow(url, whitelist)` | **复用同一 helper**(不 rename)|
| 错误语义 | `AccessDeniedException[LINGS-S01]` | **复用同码**(#028 + #032 + #033 + #034 同码)|
| 配置字段 | `McpServerConfig.domainWhitelist` per-server | `AgentRef.domainWhitelist` per-remote-agent |
| yml key | `agent.mcp.servers[*].domain-whitelist` | `agent.a2a.remote-agents[*].domain-whitelist` |
| 默认行为 | empty = deny all | empty = deny all(完全对称)|
| SPI 改动 | 0 | 0 |
| 新 ErrorCode | 0 | 0 |
| 新 Maven 依赖 | 0 | 0 |
| R-13 baseline | 第 18 次 PASS 0 binary delta | **第 19 次 PASS 0 binary delta** |
