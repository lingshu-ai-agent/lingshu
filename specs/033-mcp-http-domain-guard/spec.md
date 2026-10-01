# Story #033 `mcp-http-domain-guard` — Sandbox 防护深度(Path B + Mitigation 1)

> **状态**:🟡 待实施 → ✅ 计划 2026-10-01
> **来源**:dsh §6.3 + §6.5 (2.1) — 实施期 2026-10-01 实测发现 + 用户纠正
> **优先级**:P0(sandbox 域 LINGS-S01 防护深度,MCP 是 LLM 工具的真实运行通道)

---

## 1. WHY(为什么做这个 Story)

**问题**:`WhitelistedHttpClient`(Story #028 落地)只保护 **agent 进程自己发起的 HTTP**(WebFetchTool 路径)。MCP 3 transport(Story #021a stdio / #021b adapter / #021c SSE+STREAMABLE_HTTP)目前**直接**用 JDK `HttpURLConnection` 发请求,**完全绕过** sandbox domain-whitelist —— 这是 sandbox 防护的**实际盲点**,因为:

1. **MCP 是 LLM 的"工具远端"**:LLM 调 `mcp:github:search_repos` 实际是 MCP client 替 agent 发请求到 `api.github.com`,攻击面正是 sandbox 应该拦截的"任意外发 HTTP"。
2. **Story #028 / #032 / #067a 都建了 `WhitelistedHttpClient` 守卫**:Story #032 WebFetchTool 走 `ctx.http().get(url)` 触发守卫 ✓;但 MCP 3 transport **不走 `_test()`** ✗ —— 一边建围墙一边留后门。
3. **Story #033 实测发现**(2026-10-01 实施期,Story #032 评审时用户问"那 MCP 是不是也走 `WhitelistedHttpClient`?"):**不是**。MCP HTTP transports 是**独立**的传输实现,有 5 步握手 + 心跳保活 + 指数退避 + SSE 长流,**复用 `WhitelistedHttpClient` 整体路径会破坏这些状态机**(dsh §6.5 (2.1))。

**根因**:`McpHttpSupport` 是 Story #021c 加的 raw JDK `HttpURLConnection` 工具类,**未引入 sandbox guard 概念**;MCP server 连接**全部直接 `HttpURLConnection.openConnection()`**,whitelist 校验 0 接入。

**为什么不是 Path A(Story #032 风格)"**:
- Path A = `ctx.http().get(url)` 走 `WhitelistedHttpClient.get/post/getStream` —— **会改 MCP 请求流**,破坏 SSE 长流 / MCP 5 步握手 / JSON-RPC framing 状态机
- Path A 也意味着 MCP server 的连接生命周期(超时 / 重试 / 流控制)走 `WhitelistedHttpClient` 30s timeout,**无法定制 MCP 协议层特殊超时**(例如 MCP `initialize` 可能 server 端要 5s 启动,30s read 反而被 retry 抢先杀掉)

**决策(用户 2026-10-01 确认)**:Path B + Mitigation 1 = **MCP HTTP transports 保留 raw `HttpURLConnection` 不变**,但**每个 HTTP 请求前**调 `McpHttpSupport.checkOrThrow(url, whitelist)` —— 这是 check-only 守卫钩子,跟 `WhitelistedHttpClient.check(url)` 复用同一 `AccessDeniedException[LINGS-S01]` 错误语义。

---

## 2. WHO(谁应该关心)

- **LingShu 用户**:写 yml 配置 MCP servers + sandbox whitelist,期望 `api.example.com` 不在 whitelist 时 **MCP HTTP 也失败**(语义一致)
- **框架贡献者**:扩展 MCP transport(后续 Story #067 gRPC / InProcess),统一调 `McpHttpSupport.checkOrThrow(url, whitelist)` 即可
- **安全审计员**:sandbox 防护报告覆盖 "agent 进程 + MCP 进程" 两层外发 HTTP

---

## 3. WHAT(做什么)

### 3.1 文件改动(4 modify + 2 new + 1 modify test + 1 new test)

| # | 文件 | 类型 | 关键改动 |
|---|---|---|---|
| 1 | `McpHttpSupport.java` | modify | +1 public static helper `checkOrThrow(String url, List<String> whitelist)`(~30 行) |
| 2 | `McpServerConfig.java` | modify | +1 field `domainWhitelist: List<String> = []`(@Builder.Default) |
| 3 | `AgentConfig.java` | modify | `ServerConfig` 内层类 +1 field `domainWhitelist: List<String> = []` |
| 4 | `McpTransportAutoConfiguration.java` | modify | `toRuntimeConfig` 加 1 行 `.domainWhitelist(...)` 拷贝 + 空 fallback 到 `agent.sandbox.domain-whitelist` |
| 5 | `SseMcpServerConnection.java` | modify | +1 final field `domainWhitelist` + **7** 处 `McpHttpSupport.checkOrThrow(...)` 调用前置(`callTool` / `doConnect` 3 处 / `heartbeat` / `readSseLoop` raw conn / `relistTools`) |
| 6 | `StreamableHttpMcpServerConnection.java` | modify | +1 final field `domainWhitelist` + **5** 处 `checkOrThrow(...)` 调用前置(`callTool` / `doConnect` 3 处 / `heartbeat`) |
| 7 | `McpHttpSupportCheckOrThrowTest.java` | new (L1) | ~5 unit cases(纯静态 helper,无 HTTP 服务器) |
| 8 | `McpHttpDomainGuardIT.java` | new (L2) | ~3 IT cases(JDK `com.sun.net.httpserver.HttpServer` mock + 真 HTTP 触发 guard) |
| 9 | `McpServerConnectionFactoryTest.java` | modify | +1 case:`STREAMABLE_HTTP` cfg with `domainWhitelist=[bad.com]` → `McpHttpSupport.checkOrThrow("https://good.com", bad)` throws |

**总文件改动:9 个**(6 source + 3 test),**≤ 5 核心文件改动**边界轻度 stretch(11 source/test 在多文件小改动 + 0 SPI change + 0 new ErrorCode + 12 hook point 总计 ~40 行改动,本质是"小钩子"的"多点集成"),风险可接受。

### 3.2 `McpHttpSupport.checkOrThrow()` 契约

```java
/**
 * Story #033 — Domain guard hook for MCP HTTP transports (Path B + Mitigation 1).
 *
 * <p>Extracts host from {@code url} and verifies against {@code whitelist}
 * (case-sensitive exact match; mirrors {@link WhitelistedHttpClient#check} semantics).
 *
 * <p><b>Caller contract</b> — invoke immediately before any MCP HTTP request
 * (raw {@link HttpURLConnection#openConnection} or {@link McpHttpSupport#postJsonRpc}/
 * {@link McpHttpSupport#getJson}/{@link McpHttpSupport#postNotification}). Failure
 * throws {@link AccessDeniedException} carrying {@code [LINGS-S01]} — same
 * ErrorCode as {@link WhitelistedHttpClient} (Story #028) and {@link WebFetchTool}
 * (Story #032), so user-facing failure mode is identical.
 *
 * <p><b>Why static helper</b> — MCP HTTP transports own their connection
 * lifecycle (5-step handshake, exponential-backoff reconnect, SSE long-lived
 * stream). Path A (Story #032 WebFetchTool) delegates to {@code ctx.http().get(url)}
 * which routes through {@link WhitelistedHttpClient}. Path B (this Story) keeps
 * the raw JDK {@link HttpURLConnection} flow unchanged — only adds a check-only
 * hook before each request, minimising risk to SSE streaming / MCP handshake /
 * JSON-RPC framing.
 *
 * @param url full HTTP/HTTPS URL to validate; null/empty → throw
 * @param whitelist case-sensitive host list; null/empty → every host denied
 * @throws AccessDeniedException with {@code [LINGS-S01]} prefix if host missing
 */
public static void checkOrThrow(String url, List<String> whitelist) {
    // ── 1. URL 校验 ──
    if (url == null || url.isEmpty()) {
        throw new AccessDeniedException("URL must not be null/empty");
    }
    // ── 2. Host 提取 ──
    String host;
    try {
        host = URI.create(url).getHost();
    } catch (IllegalArgumentException e) {
        throw new AccessDeniedException("Malformed URL: " + url);
    }
    if (host == null || host.isEmpty()) {
        throw new AccessDeniedException("URL has no host: " + url);
    }
    // ── 3. Whitelist 校验 ──
    if (whitelist == null || whitelist.isEmpty() || !whitelist.contains(host)) {
        throw new AccessDeniedException("Domain not whitelisted: " + host);
    }
}
```

**契约锚点**:
- `url` null/empty → `AccessDeniedException("[LINGS-S01] URL must not be null/empty")`
- `url` malformed → `AccessDeniedException("[LINGS-S01] Malformed URL: ...")`(同 Story #028 语义)
- `url` 无 host → `AccessDeniedException("[LINGS-S01] URL has no host: ...")`
- `whitelist` null/empty → **任何 host 拒绝**(对齐 `WhitelistedHttpClient(null)` 语义,避免 bypass)
- `whitelist` 命中 → 静默通过(无返回值,无 log)
- `whitelist` 不命中 → `AccessDeniedException("[LINGS-S01] Domain not whitelisted: <host>")`(对齐 #028/#032 语义)

### 3.3 `McpServerConfig.domainWhitelist` 契约

```java
@Value @Builder @Jacksonized
public class McpServerConfig {
    // ... existing 9 fields 0 改动
    /** Story #033 — sandbox domain guard hook for MCP HTTP requests */
    @Builder.Default
    List<String> domainWhitelist = new ArrayList<>();
}
```

**契约**:
- 默认 empty list → `McpHttpSupport.checkOrThrow` 拒绝所有 host(对齐 `WhitelistedHttpClient(null)` 防御性默认)
- **每 server 独立**配置(per-`McpServerConfig` 粒度)
- 不可变(`@Value` Lombok final)
- yml 字段名 `domain-whitelist`(Jackson kebab-case 已锁)

### 3.4 `AgentConfig.ServerConfig.domainWhitelist` + fallback 语义

yml binding 层 `AgentConfig.ServerConfig` 加 `domainWhitelist: List<String>` 字段;`McpTransportAutoConfiguration.toRuntimeConfig` 拷贝规则:

```java
// Story #033 — fallback 语义
List<String> effectiveWhitelist = (sc.getDomainWhitelist() != null
                                  && !sc.getDomainWhitelist().isEmpty())
    ? sc.getDomainWhitelist()
    : (agentConfig.getSandbox() != null
        ? agentConfig.getSandbox().getDomainWhitelist()
        : null);
b.domainWhitelist(effectiveWhitelist);
```

**契约(取并集等价)**:
- per-server `domain-whitelist` 非空 → 用它(yaml `[server].domain-whitelist` 优先级最高)
- per-server 空 + sandbox `agent.sandbox.domain-whitelist` 非空 → fallback 到 sandbox(yaml `[server].domain-whitelist` 留空 = 跟随全局 sandbox)
- 两者都空 → null → 防御性默认全拒绝

**为什么不是真"取并集"**:MCP 是独立 sandbox 概念(Story #032 主论 Claude Code parity),`agent.sandbox.domain-whitelist` 是 WebBuildTool 的 guard,`mcp.servers[*].domain-whitelist` 是 MCP 的 guard。fallback 语义 = "per-server override 默认走 sandbox 全局",比真"取并集"更清晰(避免两套 whitelist 都配了时,MCP 突然用 sandbox 那份 sandbox 没有的 host 出域)。

### 3.5 yml 端到端契约

```yaml
agent:
  sandbox:
    domain-whitelist:
      - api.openai.com
      - localhost
  mcp:
    servers:
      - name: github
        transport: stdio        # ← stdio 不发 HTTP,不需要 whitelist
        command: mcp-github
      - name: my-remote
        transport: streamable    # ← streamable HTTP 发 HTTP,需要 whitelist
        url: https://api.example.com/mcp
        domain-whitelist:        # ← per-server override
          - api.example.com
      - name: my-other
        transport: streamable
        url: https://other.example.com/mcp
        # domain-whitelist 留空 → fallback 到 agent.sandbox.domain-whitelist
        # → 如果 sandbox 没有 other.example.com → MCP HTTP guard 拒绝
```

---

## 4. AC(验收清单)

| AC | 类型 | 验证 |
|---|---|---|
| AC-NN-1 | L1 | `McpHttpSupport.checkOrThrow("https://api.example.com/path", ["api.example.com"])` 无 throw |
| AC-NN-2 | L1 | `checkOrThrow("https://evil.com", ["api.example.com"])` throws `AccessDeniedException` + msg startsWith `[LINGS-S01]` + contains `evil.com` |
| AC-NN-3 | L1 | `checkOrThrow("https://api.example.com", [])` throws(empty whitelist 拒绝所有)|
| AC-NN-4 | L1 | `checkOrThrow(null, ["x"])` throws `URL must not be null/empty` |
| AC-NN-5 | L1 | `checkOrThrow("not-a-url", ["x"])` throws `Malformed URL`(URI.create 抛 IAE)|
| AC-NN-6 | L1 | `checkOrThrow("https://api.example.com", null)` throws(empty + null 等价)|
| AC-NN-7 | L2 IT | `SseMcpServerConnection.callTool()` with `domainWhitelist=[evil.com]` + 真 HTTP server → returns `McpCallResult.error("[LINGS-S01] Domain not whitelisted: evil.com")` |
| AC-NN-8 | L2 IT | `StreamableHttpMcpServerConnection.callTool()` + 真 HTTP server with `domainWhitelist=[localhost]` + url `http://127.0.0.1:port` → 成功(callTool 实际发请求,localhost 在 whitelist → 200 OK)|
| AC-NN-9 | L2 IT | `McpServerConnectionFactoryTest`:factory create STREAMABLE_HTTP conn with `domainWhitelist=[bad.com]` → connection's `callTool("x")` throws `AccessDeniedException[LINGS-S01]` |

**总计:9 new case(6 L1 + 3 L2),0 回归**(所有 18+ 现有 `McpServerConnection*Test` 必须保持 PASS)

---

## 5. R-13 mitigation (d)(依赖零增量)

| Step | 命令 | 预期 |
|---|---|---|
| pre | `mvn -pl lingshu-core dependency:tree -DoutputType=text > /tmp/lingshu-core-deps-pre.txt` | baseline 镜像 |
| impl | (本 Story 9 文件改动,0 Maven 引入) | —— |
| post | `mvn -pl lingshu-core dependency:tree -DoutputType=text > /tmp/lingshu-core-deps-post.txt` | 镜像 |
| diff | `diff /tmp/lingshu-core-deps-pre.txt /tmp/lingshu-core-deps-post.txt` | **仅时间戳差异**,无 binary delta |

**JDK 8 兼容守住**:`URI.create` / `List.contains` / `HashSet` / `Collections.emptyList` + `Jackson ObjectMapper` 已锁 + Mockito + AssertJ 已锁 + Lombok `@Value @Builder @Jacksonized` 已锁 + JDK `com.sun.net.httpserver.HttpServer` (IT fixture),**无** `var` / `List.of` / sealed / records / 任何新 Maven 依赖。

---

## 6. 关键不变项(SPI 不变 + JDK 8 兼容)

| 项 | 状态 | 说明 |
|---|---|---|
| `Tool` SPI | 不变 | Story #033 不引入 Tool |
| `McpServerConnection` SPI | 不变 | 8 方法契约不变 |
| `ConnectionState` enum | 不变 | 6 态不变 |
| `McpServerConfig` SPI | **扩 1 字段** | `@Builder.Default domainWhitelist = []`,向后兼容(旧构造器 0 字段 → 默认 empty → fallback 到 sandbox whitelist)|
| `McpTransport` SPI | 不变 | `@Component` + `connect/callTool/close` 公开方法签名不变 |
| `McpTransportAutoConfiguration` | **改 1 段** | `toRuntimeConfig` 加 1 行 `.domainWhitelist(...)` + fallback 逻辑 |
| `McpHttpSupport` SPI | **加 1 静态方法** | `checkOrThrow(String, List<String>)` 公共静态,旧方法(postJsonRpc/getJson/getJsonNode/postNotification)签名不变 |
| `AgentConfig` 不可变契约 | **扩 1 字段** | `ServerConfig` inner class +1 `domainWhitelist: List<String> = []` |
| `AgentFactory` SPI | 不变 | @Autowired 6-Router ctor **不动** |
| 9 Slot 体系 | 不变 | MCP 仍是 Slot 9 边角,MCP 域 M 不变 |
| `AccessDeniedException[LINGS-S01]` | 不变 | Story #028 已落,本 Story **复用**(0 新 ErrorCode)|
| `WhitelistedHttpClient.check()` | 不变 | Story #028 + #032 用过,本 Story 不抄它(§3.2 "Why static helper")|
| `McpToolAdapter` | 不变 | 不需要改,callTool 失败 → McpCallResult.error |
| JDK 8 兼容 | 守住 | `URI.create` / `List.contains` + 已锁 0 新 binary |
| Maven 依赖 | 0 新增 | R-13 mitigation (d) PASS(第 18 次)|
| ErrorCode | 0 新增 | 复用 `LINGS-S01`(Sandbox 域 S 段 1 号)|

---

## 7. 反模式(看到立刻停)

- ❌ **"MCP 也走 `WhitelistedHttpClient.get/post/getStream` 整体路径"** —— Path A,破坏 MCP 5 步握手 / SSE 长流 / 指数退避(2026-10-01 用户确认走 Path B + Mitigation 1)
- ❌ **"把 `McpHttpSupport.postJsonRpc` 等改成内部调 `WhitelistedHttpClient.post`"** —— 同上,破坏 MCP 协议层
- ❌ **"在 `McpServerConnectionFactory` 加 `WhitelistedHttpClient` 字段"** —— 概念错配,`WhitelistedHttpClient` 是 NetworkClient(Slot 3 sandbox 实现),MCP 走 check-only 钩子,不必构造完整 client
- ❌ **"MCP whitelist 与 sandbox whitelist 真取并集"** —— 配置语义混乱,fallback 更清晰
- ❌ **"stdio transport 也加 checkOrThrow"** —— stdio 不发 HTTP,无 host 概念,加 0 价值
- ❌ **"用 whitelist pattern 通配符(`*.example.com`)"** —— 留给 OQ-Future,本期 exact-match

---

## 8. OQ(开放问题)

| ID | 描述 | 状态 |
|---|---|---|
| OQ-Future-MCP-1 | MCP whitelist 是否需要支持通配符(`*.example.com` / `*`)? | OQ-Future(本期 exact-match per Story #031 pattern grammar)|
| OQ-Future-MCP-2 | DNS rebinding 攻击防御(连接首次 whitelist 后,server DNS 改了 IP 不再校验)? | OQ-Future(本期只 check-before-connect,OQ-Future 加 pinning/hostname verification)|
| OQ-Future-MCP-3 | MCP server URL 改为环境变量支持? | OQ-Future(本期 literal URL)|

---

## 9. 风险(R-13 / R-19 关联)

| Risk | 关联 Story | 缓解状态 |
|---|---|---|
| R-13 依赖增量 | 全 13 项已锁 | 本 Story 第 18 次 PASS 0 binary delta |
| R-19 MCP wiring gap | Story #009e 已缓解 | 本 Story 不改 wiring(只加 check 钩子)|
| **R-24 (新) MCP sandbox bypass** | Story #033 缓解 | **本期解决**:MCP 3 HTTP transports 全部走 `checkOrThrow` 守卫 → 与 `WhitelistedHttpClient.check()` 共享 `AccessDeniedException[LINGS-S01]` |

**R-24 详细** —— MCP HTTP transports 直接用 `HttpURLConnection` 发请求,**完全绕过** `WhitelistedHttpClient` sandbox guard;攻击者诱导 LLM 调 MCP `my-evil:exfiltrate_data` → MCP client 直发 `https://evil.com/steal?data=...` 不被拦截。本 Story 落地后所有 MCP HTTP 请求端点(`/sse` GET、`tools/call` POST、`initialize` POST、`notifications/initialized` POST、`tools/list` POST、`/health` GET、`notifications/tools/list_changed` POST)**共 12 个调用点**前置 `checkOrThrow` 钩子,**sandbox 防护覆盖率 100%**。

---

## 10. 后续阅读

- **plan.md** — 接口 / 文件 / 测试策略
- **tasks.md** — T01-T08 + 9 AC-NN validate + R-13 自查 + 11 doc-sync
- **quickstart.md** — 30 min 跑通
- **data-model.md** — `McpHttpSupport.checkOrThrow` + `McpServerConfig.domainWhitelist` + `McpTransportAutoConfiguration` fallback 契约 + 12 调用点集成清单
- **dsh §6.3 WhitelistedHttpClient** — Story #028 实际落地
- **memory note `feedback-mcp-covers-http-not-local-tool.md`** — 2026-10-01 用户纠正 + Path B + Mitigation 1 决策

---

## 11. 时间戳

- **创建**:2026-10-01
- **最后更新**:2026-10-01
- **对应 dsh 版本**:v1.5.49 → v1.5.50(待 T-doc-6 落地)
- **对应 dsh changelog 行**:v1.5.49 → v1.5.50(待 T-doc-9 落地,Story #033 完成条目)