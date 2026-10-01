# Story #033 `mcp-http-domain-guard` — Data Model(`checkOrThrow` 3 段 + 12 hook + fallback 语义)

> **范围**:`McpHttpSupport.checkOrThrow(url, whitelist)` 静态守卫 3 段契约 + `McpServerConfig.domainWhitelist` per-server 字段 + `AgentConfig.ServerConfig.domain-whitelist` yml 字段 + `McpTransportAutoConfiguration` fallback 语义 + 12 hook points 集成清单(SSE 7 + Streamable HTTP 5)
> **配套**:[spec.md](spec.md)(Story 整体),[plan.md](plan.md)(接口 / 文件 / 测试),[tasks.md](tasks.md)(P1-P6 任务),[quickstart.md](quickstart.md)(30 min 跑通)

---

## 1. `McpHttpSupport.checkOrThrow()` 静态守卫完整契约

### 1.1 方法签名

```java
package ai.lingshu.core.mcp;

import ai.lingshu.core.slot.AccessDeniedException;  // Story #028
import java.net.URI;                                 // JDK 1.1

public final class McpHttpSupport {
    // ... existing 8 public static helpers(postJsonRpc/getJson/getJsonNode/postNotification/wrapJsonRpc/buildInitializeParams/parseToolList/parseCallResult)...

    /**
     * Story #033 — Domain guard hook for MCP HTTP transports (Path B + Mitigation 1).
     *
     * <p>Check-only hook: extracts the URL host and verifies against
     * {@code whitelist} (case-sensitive exact match, mirroring
     * {@link WhitelistedHttpClient#check} semantics from Story #028).
     *
     * <p><b>Caller pattern</b> — invoke immediately before any MCP HTTP request
     * (raw {@link java.net.HttpURLConnection#openConnection} or via
     * {@link #postJsonRpc}/{@link #getJson}/{@link #postNotification}).
     * Failure throws {@link AccessDeniedException} carrying {@code [LINGS-S01]}
     * — same ErrorCode as {@link WhitelistedHttpClient} (Story #028) and
     * {@link ai.lingshu.core.impl.tool.local.WebFetchTool} (Story #032).
     *
     * <p><b>Why static helper, not full {@link WhitelistedHttpClient}</b> —
     * MCP HTTP transports own their connection lifecycle (5-step handshake,
     * exponential-backoff reconnect, SSE long-lived stream). Path A
     * (Story #032 WebFetchTool) delegates to {@code ctx.http().get(url)} which
     * routes through {@link WhitelistedHttpClient}. Path B (this Story) keeps
     * the raw JDK {@link java.net.HttpURLConnection} flow unchanged — only
     * adds a check-only hook before each request, minimising risk to SSE
     * streaming / MCP handshake / JSON-RPC framing.
     *
     * @param url full HTTP/HTTPS URL to validate; null/empty → throw
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
}
```

### 1.2 3 段决策流程图

```
checkOrThrow(url, whitelist)
       │
       ▼
  url == null or empty?
       │
       ├── YES → throw AccessDeniedException("[LINGS-S01] URL must not be null/empty")
       │
       ▼ NO
  URI.create(url).getHost()
       │
       ├── IllegalArgumentException → throw "[LINGS-S01] Malformed URL: <url>"
       │
       ▼ OK
  host = "<host>"  (e.g. "api.example.com" / "127.0.0.1" / "evil.com")
       │
       ▼
  host == null or empty?
       │
       ├── YES → throw "[LINGS-S01] URL has no host: <url>"
       │
       ▼ NO
  whitelist == null or whitelist.empty?
       │
       ├── YES → throw "[LINGS-S01] Domain not whitelisted: <host>"  ← 防御性默认
       │
       ▼ NO
  whitelist.contains(host)?
       │
       ├── NO → throw "[LINGS-S01] Domain not whitelisted: <host>"
       │
       ▼ YES
  return silently  ← 通过
```

### 1.3 错误文案契约

| 输入 | 抛出 | 错误文案 |
|---|---|---|
| `url = null` | `AccessDeniedException` | `[LINGS-S01] URL must not be null/empty` |
| `url = ""` | `AccessDeniedException` | `[LINGS-S01] URL must not be null/empty` |
| `url = "not-a-url"`(无 scheme)| `AccessDeniedException` | `[LINGS-S01] Malformed URL: not-a-url` |
| `url = "https://example.com/path"` `host = "example.com"` `whitelist = []` | `AccessDeniedException` | `[LINGS-S01] Domain not whitelisted: example.com` |
| `url = "https://evil.com/path"` `host = "evil.com"` `whitelist = ["api.example.com"]` | `AccessDeniedException` | `[LINGS-S01] Domain not whitelisted: evil.com` |
| `url = "https://api.example.com/path"` `host = "api.example.com"` `whitelist = ["api.example.com"]` | (无 throw) | —— |

---

## 2. `McpServerConfig.domainWhitelist` per-server 字段

### 2.1 字段签名

```java
@Value @Builder @Jacksonized
public class McpServerConfig {
    // ... existing 9 fields 0 改动
    String name;
    McpTransportType transport;
    String command;
    @Builder.Default List<String> args = new ArrayList<>();
    @Builder.Default Map<String, String> env = new HashMap<>();
    String url;
    @Builder.Default long heartbeatIntervalMs = 30_000L;
    @Builder.Default long heartbeatTimeoutMs = 10_000L;
    @Builder.Default long reconnectCapMs = 60_000L;

    /** Story #033 — domain guard whitelist for MCP HTTP requests. */
    @Builder.Default
    List<String> domainWhitelist = new ArrayList<>();
}
```

**契约**:
- 默认 `new ArrayList<>()`(空 list,**非** null)→ `checkOrThrow` 拒绝所有 host(防御性默认)
- **每 server 独立配置**(per-`McpServerConfig` 粒度)
- 不可变(`@Value` Lombok final)
- yml 字段名 `domain-whitelist`(Jackson `@JsonProperty` kebab-case 已锁,见 plan §2.5)
- **back-compat**:旧 `@Builder` 调用 0 字段 → 默认 `emptyList`,Story #021a/#021b/#021c 测试 0 改(只要 cfg 不走 auto-config 注入)

### 2.2 三种 yml 形式

```yaml
agent:
  mcp:
    servers:
      # Form 1: per-server explicit(优先级最高)
      - name: my-remote
        transport: streamable
        url: https://api.example.com/mcp
        domain-whitelist:
          - api.example.com

      # Form 2: per-server 空 → fallback 到 agent.sandbox.domain-whitelist
      - name: other-remote
        transport: streamable
        url: https://other.example.com/mcp
        # domain-whitelist 留空 → 自动 fallback 到全局 sandbox

      # Form 3: stdio transport(无 HTTP,domain-whitelist 字段 0 意义)
      - name: github
        transport: stdio
        command: mcp-github
```

---

## 3. `AgentConfig.ServerConfig.domain-whitelist` yml 绑定

### 3.1 字段签名

```java
// AgentConfig.java 内部
public static class ServerConfig {
    // ... existing 8 fields 0 改动
    String name;
    @JsonProperty("transport") McpTransportType transport;
    String command;
    @JsonProperty("args") @Builder.Default List<String> args = new ArrayList<>();
    @JsonProperty("env") @Builder.Default Map<String, String> env = new HashMap<>();
    @JsonProperty("url") String url;
    @JsonProperty("heartbeat-interval-ms") @Builder.Default long heartbeatIntervalMs = 30_000L;
    @JsonProperty("heartbeat-timeout-ms") @Builder.Default long heartbeatTimeoutMs = 10_000L;
    @JsonProperty("reconnect-cap-ms") @Builder.Default long reconnectCapMs = 60_000L;

    /** Story #033 — per-MCP-server domain guard whitelist. */
    @JsonProperty("domain-whitelist")
    @Builder.Default
    List<String> domainWhitelist = new ArrayList<>();
}
```

**契约**:
- yml 字段名 `domain-whitelist`(kebab-case 已锁,Story #021a 沿用)
- 默认 empty list → fallback 到 `agent.sandbox.domain-whitelist`(fallback 由 `McpTransportAutoConfiguration` 实现)
- back-compat:`AgentConfigMcpBackwardCompatTest` 验证旧 yml(无 `domain-whitelist`)仍正确反序列化为 empty

---

## 4. `McpTransportAutoConfiguration.toRuntimeConfig` fallback 语义

### 4.1 fallback 决策流程图

```
toRuntimeConfig(sc, agentConfig)
       │
       ▼
  sc.domainWhitelist non-empty?
       │
       ├── YES → effectiveWhitelist = sc.domainWhitelist  (per-server override 胜出)
       │
       ▼ NO
  agentConfig.sandbox.domainWhitelist non-empty?
       │
       ├── YES → effectiveWhitelist = agentConfig.sandbox.domainWhitelist  (fallback)
       │
       ▼ NO
  effectiveWhitelist = null  (防御性默认 → checkOrThrow 全拒绝)
       │
       ▼
  b.domainWhitelist(effectiveWhitelist)
```

### 4.2 实现代码

```java
private McpServerConfig toRuntimeConfig(AgentConfig.ServerConfig sc, AgentConfig agentConfig) {
    McpServerConfig.McpServerConfigBuilder b = McpServerConfig.builder()
        .name(sc.getName())
        .transport(sc.getTransport() != null ? sc.getTransport() : McpTransportType.STDIO)
        .command(sc.getCommand())
        .args(sc.getArgs() != null ? sc.getArgs() : Collections.<String>emptyList())
        .env(sc.getEnv() != null ? sc.getEnv() : Collections.<String, String>emptyMap())
        .url(sc.getUrl());
    if (sc.getHeartbeatIntervalMs() > 0) b.heartbeatIntervalMs(sc.getHeartbeatIntervalMs());
    if (sc.getHeartbeatTimeoutMs() > 0) b.heartbeatTimeoutMs(sc.getHeartbeatTimeoutMs());
    if (sc.getReconnectCapMs() > 0) b.reconnectCapMs(sc.getReconnectCapMs());

    // ── Story #033 — fallback 语义:per-server override OR sandbox whitelist ──
    List<String> effectiveWhitelist = sc.getDomainWhitelist();
    if (effectiveWhitelist == null || effectiveWhitelist.isEmpty()) {
        if (agentConfig != null && agentConfig.getSandbox() != null
            && agentConfig.getSandbox().getDomainWhitelist() != null
            && !agentConfig.getSandbox().getDomainWhitelist().isEmpty()) {
            effectiveWhitelist = agentConfig.getSandbox().getDomainWhitelist();
        }
    }
    b.domainWhitelist(effectiveWhitelist);

    return b.build();
}
```

**契约锚点**:
- per-server `domain-whitelist` 非空 → 用它(优先级最高)
- per-server 空 + sandbox `agent.sandbox.domain-whitelist` 非空 → fallback
- 两者都空 → `null` → `checkOrThrow` 全拒绝
- **不是真"取并集"** —— per-server 优先级 > sandbox(避免两套 whitelist 都配时,突然出现 sandbox 那份 sandbox 没有的 host)

---

## 5. 12 hook points 集成清单

### 5.1 SSE — 7 hook points

| # | 方法 | URL 形式 | 集成位置(行号) |
|---|---|---|---|
| 1 | `SseMcpServerConnection.callTool()` | `cfg.url + sep + "tools/call"` | callTool body 内,`McpHttpSupport.postJsonRpc(...)` 之前 |
| 2 | `SseMcpServerConnection.doConnect()` step 1 | `cfg.url + sep + "initialize"` | doConnect body 内,`McpHttpSupport.postJsonRpc(...)` 之前 |
| 3 | `SseMcpServerConnection.doConnect()` step 2 | `cfg.url + sep + "notifications/initialized"` | doConnect body 内,`McpHttpSupport.postNotification(...)` 之前 |
| 4 | `SseMcpServerConnection.doConnect()` step 3 | `cfg.url + sep + "tools/list"` | doConnect body 内,`McpHttpSupport.postJsonRpc(...)` 之前 |
| 5 | `SseMcpServerConnection.heartbeatTick()` | `cfg.url + sep + "health"` | heartbeatTick body 内,`McpHttpSupport.getJson(...)` 之前 |
| 6 | `SseMcpServerConnection.readSseLoop()` | `cfg.url + sep + "sse"`(长流 GET)| readSseLoop body 内,raw `HttpURLConnection.openConnection()` 之前 |
| 7 | `SseMcpServerConnection.relistTools()` | `cfg.url + sep + "tools/list"` | relistTools body 内,`McpHttpSupport.postJsonRpc(...)` 之前 |

**每 hook 1 行**:
```java
McpHttpSupport.checkOrThrow(url, domainWhitelist);
```

### 5.2 Streamable HTTP — 5 hook points

| # | 方法 | URL 形式 | 集成位置 |
|---|---|---|---|
| 1 | `StreamableHttpMcpServerConnection.callTool()` | `cfg.url + sep + "tools/call"` | callTool body 内 |
| 2 | `StreamableHttpMcpServerConnection.doConnect()` step 1 | `cfg.url + sep + "initialize"` | doConnect body 内 |
| 3 | `StreamableHttpMcpServerConnection.doConnect()` step 2 | `cfg.url + sep + "notifications/initialized"` | doConnect body 内 |
| 4 | `StreamableHttpMcpServerConnection.doConnect()` step 3 | `cfg.url + sep + "tools/list"` | doConnect body 内 |
| 5 | `StreamableHttpMcpServerConnection.heartbeatTick()` | `cfg.url + sep + "health"` | heartbeatTick body 内 |

### 5.3 Stdio — 0 hook points

**stdio transport 不发 HTTP**(`ProcessBuilder` 拉 subprocess + stdin/stdout JSON-RPC framing),无需 `checkOrThrow` 守卫。**0 改动**。

### 5.4 总覆盖

**100% MCP HTTP 端点拦截**:
- SSE 端点 6 类(`initialize` / `notifications/initialized` / `tools/list` × 2 / `tools/call` / `health` / `sse`)= 7 calls
- Streamable HTTP 端点 5 类(`initialize` / `notifications/initialized` / `tools/list` / `tools/call` / `health`)= 5 calls
- 共 12 calls 全部前置 `checkOrThrow`

---

## 6. 错误码契约(LINGS-S01 复用)

| ErrorCode | 来源 | 触发条件 | 文案 |
|---|---|---|---|
| `LINGS-S01` | Story #028 `AccessDeniedException`(复用)| MCP HTTP 任何调用点 `checkOrThrow` 拒绝 | `[LINGS-S01] Domain not whitelisted: <host>` |
| `LINGS-S01` | 同上(复用)| URL null/empty / malformed / no host | `[LINGS-S01] URL must not be null/empty` / `[LINGS-S01] Malformed URL: <url>` / `[LINGS-S01] URL has no host: <url>` |

**契约**:
- **0 新 ErrorCode**(复用 Story #028 已落的 `LINGS-S01`,Sandbox 域 S 段 1 号)
- 用户视角:`MCP HTTP reject + checkOrThrow reject` 错误文案与 `WebFetchTool reject`(Story #032)+ `WhitelistedHttpClient.reject`(Story #028)**完全一致**,统一沙箱域防御语义

---

## 7. 测试契约矩阵

| AC | 测试类 | case 数 | 验证 |
|---|---|---|---|
| AC-NN-1 | McpHttpSupportCheckOrThrowTest | 1 | hit: `https://api.example.com` + `["api.example.com"]` → no throw |
| AC-NN-2 | McpHttpSupportCheckOrThrowTest | 1 | miss: `https://evil.com` + `["api.example.com"]` → `[LINGS-S01]` + contains `evil.com` |
| AC-NN-3 | McpHttpSupportCheckOrThrowTest | 1 | empty whitelist: `https://x.com` + `[]` → `[LINGS-S01]` |
| AC-NN-4 | McpHttpSupportCheckOrThrowTest | 1 | null url: `null` + `["x"]` → `[LINGS-S01] URL must not be null/empty` |
| AC-NN-5 | McpHttpSupportCheckOrThrowTest | 1 | malformed url: `"not-a-url"` + `["x"]` → `[LINGS-S01] Malformed URL` |
| AC-NN-6 | McpHttpSupportCheckOrThrowTest | 1 | null whitelist: `https://x.com` + `null` → `[LINGS-S01]` |
| AC-NN-7 | McpHttpDomainGuardIT | 1 | SSE + `domainWhitelist=[evil.com]` + 真 mock server → callTool 返回 `[LINGS-S01] Domain not whitelisted: 127.0.0.1` |
| AC-NN-8 | McpHttpDomainGuardIT | 1 | StreamableHttp + `domainWhitelist=[127.0.0.1]` + 真 mock server → callTool 成功 |
| AC-NN-9 | McpHttpDomainGuardIT + McpServerConnectionFactoryTest | 1 | factory create STREAMABLE_HTTP with `domainWhitelist=[bad.com]` → callTool 返回 `[LINGS-S01]` |

**总计:9 new case(6 L1 + 3 L2 + 1 factory)**

---

## 8. 关键不变项(SPI 不变 + JDK 8 兼容)

| 项 | 状态 | 说明 |
|---|---|---|
| `McpServerConnection` SPI | 不变 | 8 方法契约不变 |
| `ConnectionState` enum | 不变 | 6 态不变 |
| `McpTransport` SPI | 不变 | `@Component` + `connect/callTool/close` 公开方法签名不变 |
| `McpServerConfig` SPI | **扩 1 字段** | `@Builder.Default domainWhitelist = []`,**向后兼容**(旧 builder 0 字段)|
| `McpTransportAutoConfiguration` | **改 1 段** | `toRuntimeConfig` 加 1 行 `.domainWhitelist(...)` + fallback 逻辑 |
| `McpHttpSupport` SPI | **加 1 静态方法** | `checkOrThrow(String, List<String>)` 公共静态,旧方法签名不变 |
| `AgentConfig` 不可变契约 | **扩 1 字段** | `ServerConfig` +1 `domain-whitelist: List<String> = []` |
| `AgentFactory` SPI | 不变 | @Autowired 6-Router ctor **不动** |
| 9 Slot 体系 | 不变 | MCP 仍是 Slot 9 |
| `AccessDeniedException[LINGS-S01]` | 不变 | Story #028 已落,本 Story **复用**(0 新 ErrorCode)|
| `WhitelistedHttpClient.check()` | 不变 | Story #028 已落,本 Story 不抄它(spec §1 / §3.2 "Why static helper")|
| `McpToolAdapter` | 不变 | callTool 失败 → McpCallResult.error,不绕过 sandbox |
| JDK 8 兼容 | 守住 | `URI.create` / `List.contains` + 已锁 0 新 binary |
| Maven 依赖 | 0 新增 | R-13 mitigation (d) PASS(第 18 次)|
| ErrorCode | 0 新增 | 复用 `LINGS-S01` |

---

## 9. 时间戳

- **创建**:2026-10-01
- **最后更新**:2026-10-01
- **对应 dsh 版本**:v1.5.49 → v1.5.50(待 T-doc-6 落地)