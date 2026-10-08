# Story #034 `a2a-http-domain-guard` — Plan(接口 + 文件 + 测试策略)

> **范围**:`AgentRef.domainWhitelist` per-remote-agent 字段 + `HttpJsonRpcA2aTransport` ctor +1 参数 + 2 hook point(checkOrThrow in `fetchCard` + `jsonRpcCall`)+ `HttpJsonRpcA2aTransportAutoConfiguration` wiring(unique baseUrl → 1 transport + union whitelist)
> **配套 spec**:[spec.md](spec.md)(Story 整体),[tasks.md](tasks.md)(P1-P4 任务)

---

## 1. 实施策略总览

| 维度 | 决策 | 备注 |
|---|---|---|
| 路径 | **Path B + 复用 MCP #033 helper**(用户 2026-10-01 确认) | A2A transport 保留 JDK `HttpClient` 不变,**仅加** check-only 钩子 |
| 钩子位置 | 每个 A2A HTTP 请求**前** | `fetchCard` 1 + `jsonRpcCall` 1 = **2 hook point** |
| 错误语义 | 复用 `AccessDeniedException[LINGS-S01]` | Story #028 已落 + MCP #033 已复用,A2A 复用第三次(Story #028 + #032 + #033 + #034 同码) |
| 注入方式 | per-`AgentRef.domainWhitelist`(yml `agent.a2a.remote-agents[*].domain-whitelist`) | unique baseUrl wire 时 union 同 URL 的 AgentRef whitelist |
| SPI 改动 | 0 | `A2aTransport` SPI / `Tool` SPI / `RemoteAgentTool` 接口契约 全部 0 改动(只实现层扩展)|
| ErrorCode | 0 新增 | 复用 `LINGS-S01` |
| Maven 依赖 | 0 新增 | R-13 mitigation (d) PASS(第 19 次)|
| 类名 rename | 0 | `McpHttpSupport` 不 rename,留 OQ-Future refactor |

---

## 2. 文件 / 接口设计

### 2.1 `AgentRef.domainWhitelist` 字段(modify,1 字段新增)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentRef.java`

**改动**:在 `priority` 字段之后新增:

```java
/**
 * 🆕 Story #034 — per-remote-agent sandbox domain whitelist.
 * Empty list = deny ALL outgoing HTTP from this agent's transport (strict mode,
 * mirror MCP #033 default). Case-sensitive exact host match (mirrors
 * {@link ai.lingshu.core.slot.AccessDeniedException}[LINGS-S01] semantics from
 * Story #028 and {@code McpHttpSupport.checkOrThrow} from Story #033).
 *
 * <p>yml example:
 * <pre>{@code
 * agent:
 *   a2a:
 *     remoteAgents:
 *       - name: alice
 *         url: https://alice.example.com
 *         domain-whitelist: [alice.example.com, alice-internal.example.com]
 * }</pre>
 *
 * <p><b>Default</b>: empty list (immutable). When yml omits the field, Jackson
 * binds null → constructor maps to {@link Collections#emptyList()} → deny all
 * outgoing HTTP (matches {@code McpServerConfig.domainWhitelist} default from
 * Story #033).
 */
List<String> domainWhitelist;
```

**所需 import**:无新增(`List` 已 import)。

**位置**:`priority` 字段后,作为新一节 ── Story #034 ── 标注。

### 2.2 `HttpJsonRpcA2aTransport` ctor +1 参数 + 2 hook(modify,~25 行新增)

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java`

**改动**:

1. **新增 import**:
   ```java
   import ai.lingshu.core.mcp.McpHttpSupport;
   ```
   (注:`AccessDeniedException` + `URI` + `ArrayList` 已 import)

2. **新增 final field** + 5-arg ctor + 保留 4-arg backward-compat ctor:
   ```java
   private final List<String> domainWhitelist;

   /** 🆕 Story #034 — 5-arg ctor with domain whitelist. */
   public HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json,
                                  AgentCardCache cardCache, Duration callTimeout,
                                  List<String> domainWhitelist) {
       // ... existing 4-param validation unchanged ...
       if (domainWhitelist == null) {
           throw new IllegalArgumentException("domainWhitelist must not be null");
       }
       this.httpBaseUrl = ...;  // existing
       this.json = json;
       this.cardCache = cardCache;
       this.callTimeout = callTimeout;
       this.http = HttpClient.newBuilder().connectTimeout(callTimeout).build();
       this.domainWhitelist = new ArrayList<>(domainWhitelist);  // defensive copy
   }

   /** Backward-compat 4-arg ctor (Story #009c signature); defaults to deny-all whitelist. */
   public HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json,
                                  AgentCardCache cardCache, Duration callTimeout) {
       this(httpBaseUrl, json, cardCache, callTimeout, Collections.emptyList());
   }
   ```

3. **Hook #1** in `fetchCard(String agentName)`(替换原 `URI uri = ...; HttpRequest req = ...` 段):
   ```java
   URI uri = URI.create(httpBaseUrl + AGENT_CARD_PATH);
   // 🆕 Story #034 — sandbox domain guard (mirrors MCP #033 hook point)
   McpHttpSupport.checkOrThrow(uri.toString(), this.domainWhitelist);
   HttpRequest req = HttpRequest.newBuilder(uri)
       .timeout(callTimeout)
       .GET()
       ...
   ```

4. **Hook #2** in `jsonRpcCall(String method, JsonNode params)`(替换原 `URI uri = ...; HttpRequest req = ...` 段):
   ```java
   URI uri = URI.create(httpBaseUrl + RPC_PATH);
   // 🆕 Story #034 — sandbox domain guard (mirrors MCP #033 hook point)
   McpHttpSupport.checkOrThrow(uri.toString(), this.domainWhitelist);
   HttpRequest req = HttpRequest.newBuilder(uri)
       .timeout(callTimeout)
       ...
   ```

**位置**:hook #1 放在 `fetchCard()` 第 116 行 `URI.create(...)` 之后;hook #2 放在 `jsonRpcCall()` 第 285 行 `URI.create(...)` 之后。

**注**:`subscribe()` 内部走 `get()` → `jsonRpcCall()`,已被 hook #2 覆盖,**0 额外 hook**;`cancel()` 同理(`jsonRpcCall("tasks/cancel", ...)`)。

### 2.3 `HttpJsonRpcA2aTransportAutoConfiguration` wiring(modify,~40 行新增)

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`

**改动**:加 `@Bean(name = "a2aTransportFactory_http-jsonrpc")` 方法,接受 `AgentConfig`,按 unique baseUrl 分组 + union whitelist:

```java
@Bean(name = "a2aTransportFactory_http-jsonrpc")
public HttpJsonRpcA2aTransportFactory httpJsonRpcA2aTransportFactory(
        AgentConfig cfg, ObjectMapper json, AgentCardCache cardCache) {
    return new HttpJsonRpcA2aTransportFactory(cfg, json, cardCache);
}

public static class HttpJsonRpcA2aTransportFactory {
    private final AgentConfig cfg;
    private final ObjectMapper json;
    private final AgentCardCache cardCache;
    // ... ctor + getters ...

    /** Build one transport per unique baseUrl, unioning whitelist across AgentRefs. */
    public Map<String, A2aTransport> buildByAgentName() {
        Map<String, A2aTransport> out = new HashMap<>();
        Map<String, List<String>> urlToWhitelist = new HashMap<>();
        Map<String, String> agentNameToUrl = new HashMap<>();
        if (cfg.getA2a() == null || cfg.getA2a().getRemoteAgents() == null) {
            return out;
        }
        for (AgentRef ref : cfg.getA2a().getRemoteAgents()) {
            String name = ref.getName();
            String url = ref.getUrl();
            if (name == null || name.isEmpty() || url == null || url.isEmpty()) {
                continue;
            }
            agentNameToUrl.put(name, url);
            urlToWhitelist.computeIfAbsent(url, k -> new ArrayList<>())
                .addAll(ref.getDomainWhitelist() == null
                    ? Collections.emptyList() : ref.getDomainWhitelist());
        }
        for (Map.Entry<String, List<String>> e : urlToWhitelist.entrySet()) {
            String url = e.getKey();
            List<String> merged = new ArrayList<>(new HashSet<>(e.getValue()));
            HttpJsonRpcA2aTransport t = new HttpJsonRpcA2aTransport(
                url, json, cardCache, Duration.ofSeconds(30), merged);
            for (Map.Entry<String, String> a2u : agentNameToUrl.entrySet()) {
                if (url.equals(a2u.getValue())) {
                    out.put(a2u.getKey(), t);
                }
            }
        }
        return out;
    }
}
```

**与现有 `RemoteAgentToolAutoConfiguration#remoteAgentTool()` 配合**:
- 现有 `remoteAgentTool()` 直接 new `HttpJsonRpcA2aTransport` 单实例(默认 fallback 到 `cfg.getA2a().getHttpBaseUrl()`)
- 🆕 Story #034 改为:从 factory 拿 `Map<String, A2aTransport>`,根据 `agentName` 选 transport;**单 URL 时降级到单实例**(back-compat,行为不变)

**所需 import**:新增 `AgentConfig` + `AgentRef` + `A2aTransport` + `HashMap` + `HashSet` + `ArrayList`(已有)。

### 2.4 现有 A2A 测试 fixture update(预计 5—8 文件)

mirror MCP #033 「35 existing test files updated」 pattern,所有 IT fixture 走 `127.0.0.1` localhost 需要手动加 whitelist:

```java
// before
HttpJsonRpcA2aTransport t = new HttpJsonRpcA2aTransport(
    "http://127.0.0.1:8080", json, cardCache, Duration.ofSeconds(30));

// after
HttpJsonRpcA2aTransport t = new HttpJsonRpcA2aTransport(
    "http://127.0.0.1:8080", json, cardCache, Duration.ofSeconds(30),
    Arrays.asList("127.0.0.1"));  // 🆕 Story #034 — fixture whitelist
```

**涉及文件**(预估):
- `HttpJsonRpcA2aTransportTest.java`
- `HttpJsonRpcA2aTransportProviderTest.java`
- `HttpJsonRpcA2aTransportAutoConfigurationTest.java`
- `RemoteAgentToolTest.java`
- `RemoteAgentToolLifecycleTest.java`
- `RemoteAgentToolAutoConfigurationTest.java`
- `RemoteAgentSchemaBuilderTest.java`(可能涉及 `fetchCard` 路径)
- `RemoteAgentTransportWiringIT.java`(IT fixture)

**总改动**:预计 5—8 files × 1—2 lines = ~10 行 Java 改动 + 5—8 个 `import java.util.Arrays;`。

---

## 3. 测试策略

### 3.1 L1 单元(`HttpJsonRpcA2aTransportCheckOrThrowTest`,6 case)

| ID | Case | 验证点 |
|---|---|---|
| AC-1.1 | `whitelistedHost_noThrow` | ctor with whitelist + fetchCard whitelisted URL → 不抛 |
| AC-1.2 | `nonWhitelistedHost_throws[LINGS-S01]` | fetchCard non-whitelisted → AccessDeniedException |
| AC-1.3 | `emptyWhitelist_deniesAll` | ctor with `[]` → 任何 host 抛 |
| AC-1.4 | `nullUrl_throws` | null URL → IllegalArgumentException |
| AC-1.5 | `malformedUrl_throws` | "not-a-url" → AccessDeniedException |
| AC-1.6 | `ipv4Host_extractedCorrectly` | "127.0.0.1" 正确提取 |

### 3.2 L2 集成(`HttpJsonRpcA2aTransportDomainGuardIT`,3 case)

| ID | Case | 验证点 |
|---|---|---|
| AC-2.1 | `whitelistedHost_reachesConnected` | mock HttpServer + whitelist → submit 成功 |
| AC-2.2 | `nonWhitelistedHost_callToolDenied[LINGS-S01]` | mock HttpServer + non-whitelist → AccessDeniedException + mock server `getRequestCount() == 0` |
| AC-2.3 | `nonWhitelistedHost_ToolResultError` | RemoteAgentTool.execute → ToolResult.error("[LINGS-S01] ...") |

### 3.3 L1 工厂分派(`HttpJsonRpcA2aTransportAutoConfigurationTest`,+1 case)

| ID | Case | 验证点 |
|---|---|---|
| AC-3.1 | `uniqueUrlPerAgent_createsNTransports` | 2 remoteAgents 不同 URL → factory 输出 2 transport |

### 3.4 R-13 mitigation (d) 验证

`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = **0 binary delta 第 19 次 PASS**(纯 JDK 8 `URI.create` + `List.contains` + `ArrayList` + `Collections.emptyList()` + `AccessDeniedException`(Story #028 已落)+ JDK standard,0 新 binary 引入)。

---

## 4. 关键不变项

| 不变项 | 说明 |
|---|---|
| `A2aTransport` SPI 接口 | 5 方法契约不变,仅 `HttpJsonRpcA2aTransport` 实现层扩展 |
| `Tool` SPI | 不变,`RemoteAgentTool` 实现层扩展(已有 generic Exception catch)|
| `ToolExecutor.dispatch()` | 5 步流水线不变(§4.10.1 硬规则 2) |
| `McpHttpSupport.checkOrThrow()` | **直接 import 复用**,不 rename |
| `AccessDeniedException[LINGS-S01]` | 复用第 4 次(#028 + #032 + #033 + #034 同码) |
| `AgentConfig.A2a` | 0 字段新增;`AgentRef` +1 字段(per-remote-agent 粒度) |
| `AgentFactory` SPI | 不变(@Autowired 6-Router ctor 不动) |
| 9 Slot 体系 | 不变 |
| JDK 8 兼容 | `URI.create` + `List.contains` + `ArrayList` + `Collections.emptyList()` + `HashSet` 已锁,no `var` / `List.of` / sealed / records |

---

## 5. 风险登记

| Risk | 概率 | 影响 | 缓解 |
|---|---|---|---|
| R1: `AgentRef` 加字段 → Jackson yml 反序列化破坏 back-compat | 低 | 高 | 现有 `AgentRef` 字段顺序保持;`domainWhitelist` 加 `@Builder.Default` + `@JsonProperty("domain-whitelist")`,null fallback 到 `Collections.emptyList()`(Lombok `@Value` 构造器 Java 端调用 back-compat) |
| R2: 现有 A2A test fixture 漏更新 → IT 大量 false-positive deny | 中 | 中 | **强制 grep 检查**:实施完成后跑 `mvn -pl lingshu-a2a-client test`,任何失败立即 fix;参考 MCP #033 经验(35 files updated)|
| R3: `HttpJsonRpcA2aTransport` 4-arg ctor 移除 → 测试 breakage | 低 | 中 | **保留** 4-arg ctor 作为 backward-compat wrapper(委派到 5-arg + emptyList),0 test breakage |
| R4: `RemoteAgentToolAutoConfiguration` 重构破坏现有 wiring | 中 | 高 | 实施前先跑 `mvn -pl lingshu-a2a-client test` 拿到 baseline;重构后跑同样命令 diff;`Map<String, A2aTransport>` 单 URL 路径完全等价于现有单实例 |
| R5: union whitelist 顺序依赖 → 同 URL 多 AgentRef whitelist 顺序影响运行时 | 低 | 低 | 用 `HashSet` 去重;`List` 顺序对 `contains` 无影响(只查存在性)|
