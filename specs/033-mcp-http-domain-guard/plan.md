# Story #033 `mcp-http-domain-guard` — Plan(接口 + 文件 + 测试策略)

> **范围**:`McpHttpSupport.checkOrThrow(url, whitelist)` check-only 守卫契约 + `McpServerConfig.domainWhitelist` per-server 字段 + 12 hook point 集成清单(SSE 7 + Streamable HTTP 5)+ `McpTransportAutoConfiguration` fallback 语义
> **配套 spec**:[spec.md](spec.md)(Story 整体),[tasks.md](tasks.md)(P1-P6 任务),[quickstart.md](quickstart.md)(30 min 跑通),[data-model.md](data-model.md)

---

## 1. 实施策略总览

| 维度 | 决策 | 备注 |
|---|---|---|
| 路径 | **Path B + Mitigation 1**(2026-10-01 用户确认) | MCP 3 transport 保留 raw `HttpURLConnection`,**仅加** check-only 钩子 |
| 钩子位置 | 每个 MCP HTTP 请求**前** | 7 (SSE) + 5 (Streamable HTTP) = 12 hook point |
| 错误语义 | 复用 `AccessDeniedException[LINGS-S01]` | Story #028 已落,与 WebFetchTool (Story #032) 共享 ErrorCode |
| 注入方式 | per-`McpServerConfig.domainWhitelist` + fallback 到 sandbox | fallback 语义见 spec §3.4 |
| SPI 改动 | 0 | `McpServerConnection` / `ConnectionState` / `McpToolAdapter` 全部 0 改动 |
| ErrorCode | 0 新增 | 复用 `LINGS-S01` |
| Maven 依赖 | 0 新增 | R-13 mitigation (d) PASS(第 18 次)|

---

## 2. 文件 / 接口设计

### 2.1 `McpHttpSupport.checkOrThrow()` (modify,~30 行新增)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpHttpSupport.java`

**新增内容**(放在现有静态 helper 之后,如 `getJsonNode` 之后):

```java
// ── Story #033 — Domain guard hook for MCP HTTP transports ────────────

/**
 * Check-only domain guard (Path B + Mitigation 1). Extracts the URL host and
 * verifies it against {@code whitelist} (case-sensitive exact match, mirroring
 * {@link WhitelistedHttpClient#check} semantics from Story #028).
 *
 * <p><b>Caller pattern</b> — invoke immediately before any MCP HTTP request
 * (raw {@link HttpURLConnection#openConnection} or via {@link #postJsonRpc}/
 * {@link #getJson}/{@link #postNotification}). Throws {@link AccessDeniedException}
 * with {@code [LINGS-S01]} prefix if the host is not whitelisted.
 *
 * <p><b>Why static + List, not full {@link WhitelistedHttpClient}</b> — MCP HTTP
 * transports own their connection lifecycle (5-step handshake, exponential-backoff
 * reconnect, SSE long-lived stream). Path A (Story #032 WebFetchTool) delegates
 * to {@code ctx.http().get(url)} which routes through {@link WhitelistedHttpClient}.
 * Path B (this Story) keeps the raw JDK {@link HttpURLConnection} flow unchanged —
 * only adds a check-only hook before each request, minimising risk to SSE streaming,
 * MCP handshake, and JSON-RPC framing.
 *
 * @param url       full HTTP/HTTPS URL to validate
 * @param whitelist case-sensitive host list; null/empty → every host denied
 * @throws AccessDeniedException with {@code [LINGS-S01]} prefix if host missing
 */
public static void checkOrThrow(String url, List<String> whitelist) {
    if (url == null || url.isEmpty()) {
        throw new AccessDeniedException("URL must not be null/empty");
    }
    String host;
    try {
        host = URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
        throw new AccessDeniedException("Malformed URL: " + url);
    }
    if (host == null || host.isEmpty()) {
        throw new AccessDeniedException("URL has no host: " + url);
    }
    if (whitelist == null || whitelist.isEmpty() || !whitelist.contains(host)) {
        throw new AccessDeniedException("Domain not whitelisted: " + host);
    }
}
```

**所需 import**:
- `ai.lingshu.core.slot.AccessDeniedException`(Story #028 已落)
- `java.net.URI`(JDK 内置,已用)

**位置**:放在 `McpHttpSupport` 类的末尾,作为新一节 ── Story #033 ── 标注。

### 2.2 `McpServerConfig.domainWhitelist` (modify,1 字段新增)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConfig.java`

**新增内容**(在 `reconnectCapMs` 字段之后):

```java
/**
 * Story #033 — domain guard whitelist for MCP HTTP requests.
 * Empty (default) → {@link McpHttpSupport#checkOrThrow} rejects every host
 * unless {@link McpTransportAutoConfiguration} provides a fallback to
 * {@code agent.sandbox.domain-whitelist}.
 */
@Builder.Default
List<String> domainWhitelist = new ArrayList<>();
```

**back-compat**:旧 `@Builder` 调用 0 改动 → 默认 `new ArrayList<>()`(空 list)。`@Jacksonized` 自动接受 yml 字段 `domain-whitelist`(kebab-case)。

### 2.3 `AgentConfig.ServerConfig.domainWhitelist` (modify,1 字段新增)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`

**新增内容**(在 `ServerConfig` inner class,`reconnectCapMs` 字段之后):

```java
/**
 * Story #033 — per-MCP-server domain guard whitelist.
 * Empty (default) → {@link McpTransportAutoConfiguration} falls back to
 * {@code agent.sandbox.domain-whitelist} (Story #028). Non-empty →
 * explicit per-server override (priority over sandbox fallback).
 */
@JsonProperty("domain-whitelist")
@Builder.Default
List<String> domainWhitelist = new ArrayList<>();
```

**位置**:用 grep 找到 `ServerConfig` inner class 的 `reconnectCapMs` 字段,在其后插入。

### 2.4 `McpTransportAutoConfiguration.toRuntimeConfig` (modify,~6 行新增)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportAutoConfiguration.java`

**改动**:`toRuntimeConfig(AgentConfig.ServerConfig sc)` 末尾插入:

```java
// Story #033 — fallback 语义:per-server override OR sandbox whitelist
List<String> effectiveWhitelist = sc.getDomainWhitelist();
if (effectiveWhitelist == null || effectiveWhitelist.isEmpty()) {
    if (agentConfig != null && agentConfig.getSandbox() != null
        && agentConfig.getSandbox().getDomainWhitelist() != null
        && !agentConfig.getSandbox().getDomainWhitelist().isEmpty()) {
        effectiveWhitelist = agentConfig.getSandbox().getDomainWhitelist();
    }
}
b.domainWhitelist(effectiveWhitelist);
```

**注意**:`toRuntimeConfig` 当前签名 `private McpServerConfig toRuntimeConfig(AgentConfig.ServerConfig sc)` —— 不收 `AgentConfig`。需要改为 `private McpServerConfig toRuntimeConfig(AgentConfig.ServerConfig sc, AgentConfig agentConfig)` 或者把 `agentConfig` 提到外层(类似 `mcpServerConfigs(AgentConfig agentConfig)` 已经持有引用,可以在 lambda 内捕获)。

**推荐方案**:把 `agentConfig` 提到 `mcpServerConfigs` 方法局部,作为 `toRuntimeConfig` 第二参。

### 2.5 `SseMcpServerConnection` 集成 (modify,7 hook points)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/SseMcpServerConnection.java`

**新增字段**(在 `reconnectCapMs` 字段之后):

```java
/** Story #033 — domain guard whitelist (immutable, copied from cfg). */
private final List<String> domainWhitelist;
```

**构造器末尾追加**:

```java
this.domainWhitelist = cfg.getDomainWhitelist() != null
    ? new ArrayList<>(cfg.getDomainWhitelist())
    : new ArrayList<>();
```

**7 hook point 集成**:

| # | 方法 | 行号 | 当前 | 新增 |
|---|---|---|---|---|
| 1 | `callTool()` | ~174 | `McpHttpSupport.postJsonRpc(url + "/tools/call", ...)` | 前置 `McpHttpSupport.checkOrThrow(url, domainWhitelist)` |
| 2 | `doConnect()` | ~241 | `McpHttpSupport.postJsonRpc(base + sep + "initialize", ...)` | 前置 `McpHttpSupport.checkOrThrow(base + sep + "initialize", domainWhitelist)` |
| 3 | `doConnect()` | ~247 | `McpHttpSupport.postNotification(base + sep + "notifications/initialized", ...)` | 前置 `checkOrThrow(base + sep + "notifications/initialized", domainWhitelist)` |
| 4 | `doConnect()` | ~253 | `McpHttpSupport.postJsonRpc(base + sep + "tools/list", ...)` | 前置 `checkOrThrow(base + sep + "tools/list", domainWhitelist)` |
| 5 | `heartbeatTick()` | ~334 | `McpHttpSupport.getJson(healthUrl, ...)` | 前置 `checkOrThrow(healthUrl, domainWhitelist)` |
| 6 | `readSseLoop()` | ~515 | raw `HttpURLConnection.openConnection()` for `/sse` GET | 前置 `checkOrThrow(sseUrl, domainWhitelist)`(sseUrl = `cfg.getUrl() + sep + "sse"`)|
| 7 | `relistTools()` | ~559 | `McpHttpSupport.postJsonRpc(base + sep + "tools/list", ...)` | 前置 `checkOrThrow(base + sep + "tools/list", domainWhitelist)` |

每个 hook 都是 1 行:

```java
McpHttpSupport.checkOrThrow(url, domainWhitelist);
```

### 2.6 `StreamableHttpMcpServerConnection` 集成 (modify,5 hook points)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnection.java`

**新增字段 + 构造器 5 hook points**:同 SSE 模式,5 处:

| # | 方法 | 行号 | 当前 | 新增 |
|---|---|---|---|---|
| 1 | `callTool()` | ~150 | `McpHttpSupport.postJsonRpc(url, ...)` | 前置 `checkOrThrow(url, domainWhitelist)` |
| 2 | `doConnect()` | ~213 | `McpHttpSupport.postJsonRpc(base + sep + "initialize", ...)` | 前置 `checkOrThrow(base + sep + "initialize", domainWhitelist)` |
| 3 | `doConnect()` | ~218 | `McpHttpSupport.postNotification(base + sep + "notifications/initialized", ...)` | 前置 `checkOrThrow(base + sep + "notifications/initialized", domainWhitelist)` |
| 4 | `doConnect()` | ~223 | `McpHttpSupport.postJsonRpc(base + sep + "tools/list", ...)` | 前置 `checkOrThrow(base + sep + "tools/list", domainWhitelist)` |
| 5 | `heartbeatTick()` | ~284 | `McpHttpSupport.getJson(healthUrl, ...)` | 前置 `checkOrThrow(healthUrl, domainWhitelist)` |

**新增字段 + 构造器追加**:同 SSE。

### 2.7 `McpHttpSupportCheckOrThrowTest` (new, L1,~5 cases)

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpSupportCheckOrThrowTest.java`

**测试矩阵**:

```java
@Test public void hitReturnsUrlWhenAllowed()              // AC-NN-1
@Test public void missThrowsAccessDeniedWithLingsS01()     // AC-NN-2
@Test public void emptyWhitelistDeniesAll()                 // AC-NN-3
@Test public void nullUrlThrowsUrlMustNotBeNullEmpty()    // AC-NN-4
@Test public void malformedUrlThrowsMalformedUrl()        // AC-NN-5
@Test public void nullWhitelistDeniesAll()                // AC-NN-6
```

**模式**:JUnit 5 + AssertJ,纯静态 helper,无 HTTP 服务器,极快(~50ms / case)。

### 2.8 `McpHttpDomainGuardIT` (new, L2,~3 cases)

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpDomainGuardIT.java`

**测试矩阵**:

```java
@Test public void sseCallToolDomainMissReturnsLingsS01Error()    // AC-NN-7
@Test public void streamableHttpCallToolDomainHitRealServer()    // AC-NN-8
@Test public void streamableHttpConnectDomainMissThrowsLingsS01() // AC-NN-9
```

**Fixture**:JDK `com.sun.net.httpserver.HttpServer`(`HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)` + `server.start()`)开 mock server 返 200 + JSON-RPC envelope。绑 127.0.0.1 + 内核分端口。

**测试运行命令**:
```bash
mvn -pl lingshu-core test -Dtest=McpHttpSupportCheckOrThrowTest
mvn -pl lingshu-core test -Dtest=McpHttpDomainGuardIT
```

### 2.9 `McpServerConnectionFactoryTest` (modify, +1 case)

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpServerConnectionFactoryTest.java`

**新增 case**:factory create STREAMABLE_HTTP conn with `domainWhitelist=[bad.com]` → connection's `callTool("x")` throws `AccessDeniedException[LINGS-S01]`(覆盖 AC-NN-9 的 factory 层)。

---

## 3. 测试策略

### 3.1 L1(L1 单元,~6 cases)

| Test | Case | 验证 |
|---|---|---|
| `McpHttpSupportCheckOrThrowTest#hitReturnsUrlWhenAllowed` | 1 | `checkOrThrow("https://api.example.com", ["api.example.com"])` 无 throw |
| `McpHttpSupportCheckOrThrowTest#missThrowsAccessDeniedWithLingsS01` | 2 | `checkOrThrow("https://evil.com", ["api.example.com"])` throws `AccessDeniedException` + msg startsWith `[LINGS-S01]` + contains `evil.com` |
| `McpHttpSupportCheckOrThrowTest#emptyWhitelistDeniesAll` | 3 | `checkOrThrow("https://x.com", [])` throws |
| `McpHttpSupportCheckOrThrowTest#nullUrlThrowsUrlMustNotBeNullEmpty` | 4 | `checkOrThrow(null, ["x"])` throws |
| `McpHttpSupportCheckOrThrowTest#malformedUrlThrowsMalformedUrl` | 5 | `checkOrThrow("not-a-url", ["x"])` throws(URI.create 抛 IAE)|
| `McpHttpSupportCheckOrThrowTest#nullWhitelistDeniesAll` | 6 | `checkOrThrow("https://x.com", null)` throws(null + empty 等价)|

### 3.2 L2(L2 真发请求 IT,~3 cases)

| Test | Case | 验证 |
|---|---|---|
| `McpHttpDomainGuardIT#sseCallToolDomainMissReturnsLingsS01Error` | 7 | SSE conn + `domainWhitelist=[evil.com]` + 真 mock server → callTool 返回 `McpCallResult.error("[LINGS-S01] Domain not whitelisted: evil.com")` |
| `McpHttpDomainGuardIT#streamableHttpCallToolDomainHitRealServer` | 8 | StreamableHttp conn + `domainWhitelist=[127.0.0.1]` + 真 mock server → callTool 成功(200 OK + JSON-RPC result)|
| `McpHttpDomainGuardIT#streamableHttpConnectDomainMissThrowsLingsS01` | 9 | StreamableHttp conn + `domainWhitelist=[evil.com]` + 真 mock server 127.0.0.1 → `start()` 失败,`ConnectionState=FAILED` |

### 3.3 L3(L3 黑盒,~0 cases;由 Story #032 demo-product IT 隐式覆盖)

L3 demo-product IT 验证 yml 端到端生效:
```bash
mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductMcpDomainGuardIT
```
本期**不强制**写 L3(可选项,如果时间充裕)。重点放在 L1 + L2 + R-13 自查。

---

## 4. 复用与依赖

### 4.1 复用(无新依赖)

| 复用项 | 来源 |
|---|---|
| `AccessDeniedException[LINGS-S01]` | Story #028 已落 |
| `WhitelistedHttpClient.check()` 语义 | Story #028 已落,**复用**而不是复制(SpecKit 原则 §10 风险登记)|
| JDK `URI.create` / `List.contains` | JDK 1.1 + JDK 8 standard |
| Jackson `@Builder.Default` | Story #021a 已用 |
| Lombok `@Value @Builder` | 已锁 |
| `com.sun.net.httpserver.HttpServer` (IT) | JDK 内置 |
| Mockito + AssertJ | 已锁 |

### 4.2 Maven 依赖

0 新增。

---

## 5. 风险与缓解

| Risk | 影响 | 缓解 |
|---|---|---|
| R-24 MCP sandbox bypass(R-13 子项)| 高 | 本 Story 100% 缓解(12 hook point 覆盖 SSE + Streamable HTTP 全部 HTTP 调用)|
| 性能开销(checkOrThrow 在 hot path)| 低 | `URI.create + getHost + List.contains` ~微秒级,JDK `HttpURLConnection` 调用本身 ~毫秒级,可忽略 |
| 测试断流(现有 18+ MCP 测试需要保持 PASS)| 中 | 现有测试都是 `McpServerConfig.builder().domainWhitelist(...)` 0 字段调用,默认 `emptyList`,但 `McpTransportAutoConfiguration` 现在 fallback 到 sandbox whitelist(可能 sandbox empty → fallback null → 全拒绝)→ 现有测试需要 fallback 到 sandbox whitelist 或者显式 set `domainWhitelist=["localhost", "127.0.0.1"]` |

**现有测试用法的修正**:现有 18+ `McpServerConnection*Test` 都是 `new StdioMcpServerConnection(cfg)` 或 `new SseMcpServerConnection(cfg)` 直接调用,**不走 `McpTransportAutoConfiguration`**,因此 `cfg.domainWhitelist` 默认 empty。**empty → 防御性拒绝**会破坏现有测试。

**解决**:让 `McpHttpSupport.checkOrThrow` 在 whitelist empty 时**仅当 caller 显式提供 `cfg.url` 但 cfg 无 whitelist 时降级为 "no whitelist configured → pass-through"**? 不,这破坏 sandbox 防御性默认。

**真正解决**:**现有测试在 cfg builder 上显式 `.domainWhitelist(["localhost", "127.0.0.1"])`**,或者**让 `WhitelistedHttpClient` 默认包含 `localhost + 127.0.0.1` 用于 IT 测试** —— 沿用 Story #028 默认 `localhost`(测试场景)。

**最佳方案**:**现有测试不动 `McpServerConfig`**,而是在 `McpServerConfig` 构造器内部,如果 `domainWhitelist` 为 empty,**自动 fallback 到 `[localhost, 127.0.0.1]`** 仅用于 IT 场景? 不,这破坏生产环境"防御性默认"语义。

**正确方案**(详 spec §3.4):
- `McpServerConfig.domainWhitelist` 字段默认 `emptyList`(防御性默认)
- 现有 `McpServerConnection*Test` 测试用例**显式设置** `cfg.domainWhitelist = ["localhost", "127.0.0.1"]`(用 builder `.domainWhitelist(...)` 改写)— 18 个测试,**改写量小**

**或者更轻**:让 `McpHttpSupport.checkOrThrow` 在 `whitelist == null` 时**pass-through**(`!whitelist.contains(host)` short-circuit 中加 `null` 不进 `contains`)—— 但这破坏 sandbox 防御性默认。

**决策**:选 spec §3.4 的 **fallback 语义** —— `McpTransportAutoConfiguration` 检测 `cfg.domainWhitelist` empty 时,fallback 到 `agent.sandbox.domain-whitelist`(yml 一般绑定生产可配置);**现有测试不走 auto-config,直接 `new SseMcpServerConnection(cfg)` 时**,`cfg.domainWhitelist` 默认 empty,`checkOrThrow` 会全拒绝。

**简化**:本 Story 让 `McpServerConnection` 在 ctor 时,如果 `cfg.domainWhitelist` 为 empty,**log INFO 警告** "no whitelist configured — all MCP HTTP requests will be denied" —— 测试可以选择 set 或者不 set,但 set 了才能跑得动。

**最终决策**(2026-10-01 评审):现有 18+ 测试**保持 0 改动**,允许它们继续用 empty `domainWhitelist` —— 关键洞察是 **stdio transport 永远不调 `checkOrThrow`**(没有 HTTP),现有 stdio 测试不受影响。**SSE/Streamable HTTP 现有测试**(看 SseMcpServerConnectionStartTest 等)**真发 HTTP 到 localhost:port**,所以**这些测试需要改写 cfg 加 whitelist**。

详细测试改写清单(本 Story T05 必跑):
- `SseMcpServerConnectionStartTest`:`new SseMcpServerConnection(cfg)` 多次 → 加 `.domainWhitelist(["localhost", "127.0.0.1"])`
- `SseMcpServerConnectionHeartbeatTest`:同上
- `SseMcpServerConnectionReconnectTest`:同上
- `SseMcpServerConnectionCloseAndCallTest`:同上
- `SseMcpServerConnectionListenerTest`:同上
- `StreamableHttpMcpServerConnectionStartTest`:同上
- `StreamableHttpMcpServerConnectionHeartbeatTest`:同上
- `StreamableHttpMcpServerConnectionReconnectTest`:同上
- `StreamableHttpMcpServerConnectionCloseAndCallTest`:同上

(stdio 测试 0 改动)

**总计:9 个 SSE/Streamable HTTP 测试改 cfg builder,9 + 9 stdio 测试 0 改动 = 18 测试全 PASS。**

---

## 6. 时间戳

- **创建**:2026-10-01
- **最后更新**:2026-10-01
- **对应 dsh 版本**:v1.5.49 → v1.5.50(待 T-doc-6 落地)