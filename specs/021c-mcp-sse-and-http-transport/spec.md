# Story #021c `mcp-sse-and-http-transport` — Spec

> **Status**: Draft 2026-09-23
> **Source**: dsh v1.5.37 §6.5 (2.1) `SseMcpServerConnection` 差异段(L4821-4835)+ §6.5 (2.1) `McpServerConnectionFactory` switch(L4601-4614)+ §15.9 ErrorCode 编码约定(L7211-7220)
> **Closes gap**:Story #021a(`stdio` 完成)+ #021b(`McpTransport` 协调者 + `McpToolAdapter` 完成)→ 当前 factory 在 `SSE / STREAMABLE_HTTP` 分支**仍抛 `McpTransportException(LINGS-M01, "...not implemented")`**;**两类生产 MCP server 接不进**:
>   (1) **远程 SSE MCP server**(典型部署:`https://mcp.example.com/sse`,反向代理后端守护进程,适合 SaaS 共享 / 跨网络部署)—— 走 `GET {url}` 长连接 + Server-Sent Events
>   (2) **HTTP streamable MCP server**(典型部署:同子网 HTTP API,适合内网无状态部署)—— 走 `POST {url}/initialize` + `POST {url}/tools/list` + `POST {url}/tools/call` 无状态
>
> 后果:#021b 的 `McpTransport.connect()` 在 yml 配 `transport: sse` 或 `transport: streamable-http` 时**直接抛 LINGS-M01**,Agent 用户启动后 0 个 MCP tool 注入 `ToolRegistry`;**MCP 集成链断在第二环**(stdio 能用,sse/http 仍空白)。

---

## WHY

dsh §6.5 (2) L4454-4551 的 `McpTransport` 是 MCP server ↔ LingShu Agent 的总装组件,工厂 `McpServerConnectionFactory.create(cfg)` 必须按 `cfg.getTransport()` 分派到 **3 种 concrete connection** —— stdio / SSE / streamable HTTP。当前 lingshu-core 仓**只有 stdio 实现**,`SseMcpServerConnection` / `StreamableHttpMcpServerConnection` **0 行落地**。这造成 4 个连锁问题:

1. **dsh §6.5 (2.1) v1.5.29 文档契约是 single-source-of-truth,但 SSE/HTTP 代码侧 0 实现** — dsh L4821-4835 `SseMcpServerConnection` 差异说明(3 处:心跳 / 重连 / 长连接)+ L4837-4871 配置 yml 示例 + 启动日志样例,**全部 0 行落地**。Story #021c 实施者打开 IDE 看到 `SseMcpServerConnection` 只有 factory 抛错的 catch 路径,只能照抄 dsh 模板 + 反推 HTTP/SSE 协议
2. **dsh §6.5 (2.1) L4601-4614 factory dispatch 字面是 3 分支全实现** —— `SSE → new SseMcpServerConnection(cfg)` / `STREAMABLE_HTTP → new StreamableHttpMcpServerConnection(cfg)`,当前 `#021a` 落地时**只** `STDIO` 分支 return,`SSE / STREAMABLE_HTTP` 抛 `McpTransportException(LINGS-M01)`,违反 dsh 字面契约
3. **企业典型 MCP 部署走 SSE/HTTP,不是 stdio** —— `npx @modelcontextprotocol/server-github` 在用户本机可走 stdio,但**云端 SaaS MCP 服务**(`https://mcp.example.com/sse`)、**跨网络共享 MCP server**、**反向代理后的 MCP**(`nginx` 后挂多个 mcp 子进程)**只能走 SSE 或 streamable HTTP**;stdio 类部署在云端受限(子进程管理 / 网络隔离)
4. **`McpErrorCodes` 域待扩** —— 当前只有 `LINGS_M01`(factory 拒未实现 transport,Story #021a origin)+ `LINGS_M02`(tool call 未预期异常,Story #021b origin),**HTTP/SSE 域**特定失败(HTTP 5xx / SSE event 解析失败 / HTTP upgrade 超时)**没有专属错误码**,只能 catch-all 转 LINGS-M02,语义错乱 —— Story #021c 引入 `LINGS_M03 = MCP_HTTP_SSE_FAILED`,精确归因

**Story #021c 目标**:落地 `SseMcpServerConnection`(HTTP + SSE EventSource + 周期 GET /health 心跳 + 重建 HttpURLConnection 重连)+ `StreamableHttpMcpServerConnection`(POST tools/* 无状态 HTTP + 周期 ping 心跳 + 退避重连)+ 改写 `McpServerConnectionFactory` SSE/HTTP 分支 return concrete 实例(移除 LINGS-M01 抛点)+ 扩 `McpErrorCodes` 加 `LINGS_M03`。**MCP 集成链第三块砖**(从"stdio 唯一" → "3 类全实现")。

**业务价值**:
- dsh §6.5 (2) + §6.5 (2.1) factory dispatch 字面落地 —— `#021d` 实施者(若后续加自定义 MCP transport)直接 `factory.create(cfg)` 三类都 work
- dsh §0.4 AC-09 "MCP server 多源接入"第 2 步 —— Story #021c 完成后,**远程 SSE MCP server**(`https://mcp.example.com/sse`)+ **内网 streamable HTTP MCP server**(`POST http://internal-mcp:8080/tools/call`)能接进来
- dsh §6.5 (2.1) L4559 提到的"SSE 反向代理超时踢线"生产故障,Story #021c **SSE 全部覆盖**(读 InputStream 阻塞超时 → DISCONNECTED → scheduleReconnect)
- dsh §0.4 AC-09 "MCP server 多源接入"完整收尾 —— 3 类 transport 全部可用,Agent 用户 yml 一行切换(`stdio` / `sse` / `streamable-http`)

---

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类)| yml 写 `transport: sse` + `url: https://mcp.example.com/sse`,启动后 `ToolRegistry` 注册到远程 MCP server 暴露的 N 个 tool;**#021c 完成前用户配 SSE/HTTP = LINGS-M01 启动失败** |
| **SaaS MCP 服务商**(Dave 类)| 远程 MCP server 走 `GET /sse` 长连接 + `POST /tools/call` stateless;`tools/listChanged` 推送由 SSE 触发重拉;**LingShu Agent 持续接 SaaS MCP 不会被反向代理超时踢线**(心跳保活) |
| **运维稳定性关注者**(Eve 类)| 远程 MCP server 突然断网 / 反向代理超时 / SSE 流断 → **Agent 不崩盘**;`ConnectionState.DISCONNECTED → RECONNECTING → CONNECTED` 自动恢复;**指数退避** `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 抗维护窗口 |
| **框架贡献者**(plugin 作者)| 写自定义 MCP transport(如 `WebSocketMcpServerConnection`)→ 实现 `McpServerConnection` 8 方法 + 在 factory 加 switch case,**不改** factory 模板 |
| **CI 工程师**(Charlie 类)| L1/L2 测试 case ≥ 22,`mvn -pl lingshu-core test` 0 fail;`mvn dependency:tree` 0 增量;**fake HTTP/SSE server** 用 JDK 内置 `com.sun.net.httpserver.HttpServer`(per #009c 模式)+ 临时端口绑定,跨平台,JDK 8 兼容 |
| **协议研究者**(Frank 类)| `SseMcpServerConnection` 走 dsh L4821-4835 3 处差异样板 —— 心跳 = `GET /health` 而非 `process.isAlive()` / 重连 = 重建 HttpURLConnection 而非杀子进程 / 长连接 = 手写 SSE parser 收 server push 触发 tools/listChanged 重拉;`StreamableHttpMcpServerConnection` 走无状态 POST request/response,无 SSE reader 线程 |

---

## WHAT

Story #021c 落地 3 个新接口 / 类 + 2 个修改:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `SseMcpServerConnection` | `implements McpServerConnection`(HTTP + SSE EventSource + 心跳 + 重连)| `lingshu-core/src/main/java/ai/lingshu/core/mcp/SseMcpServerConnection.java` | ~290 |
| `StreamableHttpMcpServerConnection` | `implements McpServerConnection`(stateless HTTP POST tools/*)| `lingshu-core/src/main/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnection.java` | ~220 |
| `McpHttpSupport` | utility class(共享 HttpURLConnection + JSON-RPC helper)| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpHttpSupport.java` | ~160 |
| `McpServerConnectionFactory` | 修改:移除 LINGS-M01 throw,SSE / STREAMABLE_HTTP return concrete | `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java` | ±10 / switch 改 |
| `McpErrorCodes` | 修改:加 `LINGS_M03 = MCP_HTTP_SSE_FAILED` | `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpErrorCodes.java` | +5 |
| 测试 | L1 + L2 + L3 集成 + fake HTTP/SSE server fixture | `lingshu-core/src/test/java/ai/lingshu/core/mcp/` | ~900 行 / ≥22 case |

**5 核心文件 = 3 新建 + 2 修改 + 1 测试目录**,1 新 ErrorCode(`LINGS-M03`),`mvn dependency:tree` **0 增量**(SSE/HTTP 用 JDK 1.1 内置 `HttpURLConnection` + 手写 SSE parser,R-13 mitigation (d) 严格遵守)。

### 1. `McpHttpSupport` utility class(dsh §6.5 (2) JSON-RPC over HTTP 共享样板)

```java
package ai.lingshu.core.mcp;

/**
 * MCP 共享 HTTP 工具 (Story #021c, dsh §6.5 (2))。
 *
 * <p>职责 —— 把 "HttpURLConnection + JSON-RPC 2.0 envelope" 共享样板集中
 * (SseMcpServerConnection + StreamableHttpMcpServerConnection 都用),避免两边重复 ~80 行。
 *
 * <p><b>主要方法</b>(6 个):
 * <ol>
 *   <li>{@code postJsonRpc(String url, JsonNode body, long timeoutMs)} —
 *       POST application/json,读 response body,返 JsonNode
 *       (抛 {@link McpTransportException} with {@code LINGS-M03} on 5xx / IO error);</li>
 *   <li>{@code getJson(String url, long timeoutMs)} — GET 读 body(JsonNode 或 text);</li>
 *   <li>{@code buildInitializeParams()} — 同 stdio(2024-11-05 protocolVersion);</li>
 *   <li>{@code parseInitializeResult(JsonNode)} — 抽 protocolVersion 验证;</li>
 *   <li>{@code parseToolList(JsonNode)} — 同 stdio(resp.result.tools → List<McpToolDescriptor>);</li>
 *   <li>{@code parseCallResult(JsonNode)} — 同 stdio(resp.result.{content, isError} → McpCallResult)。</li>
 * </ol>
 *
 * <p><b>JDK 8 兼容</b> — 用 JDK 1.1 内置 {@link java.net.HttpURLConnection};
 * 不引 {@code java.net.http.HttpClient}(JDK 11+,将破 §0 L39 编译目标约束) / OkHttp / 任何
 * HTTP 客户端依赖。SSE 流读用 {@link java.io.BufferedReader#readLine()},手写
 * SSE event parser(无第三方依赖)。
 */
public final class McpHttpSupport {
    private McpHttpSupport() {}
    // ... 6 个静态方法 ...
}
```

### 2. `SseMcpServerConnection` 完整实现(dsh §6.5 (2.1) L4821-4835)

**字段**(对齐 stdio 模板 + 新增 SSE 字段):

| 字段 | 类型 | 初始值 | 用途 |
|---|---|---|---|
| `cfg` | `final McpServerConfig` | required | 配置(url 必填) |
| `hbIntervalMs / hbTimeoutMs / reconnectCapMs` | `final long` | cfg 默认 / 测试覆盖 | 同 stdio |
| `mapper` | `final ObjectMapper` | `new ObjectMapper()` | JSON parse |
| `state` | `final AtomicReference<ConnectionState>` | `IDLE` | 状态机 |
| `lastBeat` | `final AtomicLong` | 0L | 最近心跳成功 epoch ms |
| `reconnectAttempts` | `final AtomicInteger` | 0 | 退避计数 |
| `listeners` | `final CopyOnWriteArrayList<Consumer<ConnectionState>>` | empty | listener 多订阅 |
| `closing` | `final AtomicBoolean` | false | close 守卫 |
| `cachedTools` | `final List<McpToolDescriptor>` | empty | 缓存 tools/list |
| `hbExecutor` | `ScheduledExecutorService`(懒初始化)| null | daemon 心跳调度 |
| `sseReader` | `volatile Thread` | null | SSE event 流读线程(daemon) |

**`start()` 5 步**(对齐 stdio L4689-4722 + 适配 HTTP):
1. `HTTP POST {cfg.url}/initialize` body=`{protocolVersion, capabilities, clientInfo}` → 等 `result.protocolVersion` 响应
2. `HTTP POST {cfg.url}/notifications/initialized` 通知 server 已 ready(无 id)
3. `HTTP POST {cfg.url}/tools/list` → 解析 `result.tools` 缓存 `cachedTools`
4. 启动 SSE 读线程 `sseReader = new Thread(this::readSseLoop, "mcp-sse-" + cfg.getName())` daemon
5. 切 `ConnectionState.CONNECTED` + 重置 `reconnectAttempts=0` + 启 daemon 心跳(`hbExecutor`)

**`probe()` 心跳**(dsh L4823):
- `HTTP GET {cfg.url}/health` → 期望 200;超时或 5xx → DISCONNECTED → scheduleReconnect
- 或 `HTTP POST {cfg.url}` body=`{method:"ping"}` → 期望 `result.{}=` 空对象;超时或 5xx → DISCONNECTED → scheduleReconnect(优先 GET /health,失败降级 ping)

**`readSseLoop()` SSE event parser**(dsh L4825):
```java
private void readSseLoop() {
    try (InputStream raw = openSseStream();  // GET {cfg.url} Accept: text/event-stream
         BufferedReader reader = new BufferedReader(new InputStreamReader(raw, UTF_8))) {
        String line;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null && !closing.get()) {
            if (line.isEmpty()) {
                // 事件边界 —— 派发累积的 data
                if (data.length() > 0) {
                    handleSseEvent(data.toString());
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            } else if (line.startsWith(":")) {
                // 注释行 —— 忽略(keep-alive)
            } else if (line.startsWith("event:")) {
                // 事件类型 —— 暂不分支(默认 event)
            } else if (line.startsWith("retry:")) {
                // 重试间隔 —— 暂忽略(用我们自己的 backoff)
            }
        }
        // 流断 → DISCONNECTED
        transition(DISCONNECTED);
        scheduleReconnect();
    } catch (IOException e) {
        if (!closing.get()) {
            log.warn("[MCP:{}] SSE stream broken: {}", cfg.getName(), e.toString());
            transition(DISCONNECTED);
            scheduleReconnect();
        }
    }
}
```

**`handleSseEvent(String data)`** —— 解析 JSON;若 `method == "notifications/tools/list_changed"` → 重拉 tools/list(`McpHttpSupport.postJsonRpc(url + "/tools/list", empty)` → 更新 `cachedTools` + listener 触发 McpTransport 重 register)。

**`scheduleReconnect()` 退避** —— 复用 stdio 公式 `delayMs = Math.min(reconnectCapMs, (1L << Math.min(attempt - 1, 6)) * 1000L)`,attempts 累加,cap 60s,**无限**重试。

**`close()`** —— `closing.set(true)` + `hbExecutor.shutdownNow()` + `sseReader.interrupt()` + 关闭 SSE InputStream + `transition(FAILED)`。**幂等**(多次 close 安全)。

**关键不变量**(对齐 stdio):
1. `start()` 抢占守卫:只允许 IDLE / DISCONNECTED / RECONNECTING 状态调 start()(否则 return)— 防止重入导致多 SSE 连接
2. **SSE 读线程独立于心跳线程** —— SSE reader 是 blocking IO 线程(`readLine()` 阻塞),不能与 `hbExecutor` 复用;用 `Thread` 而非 `ScheduledExecutorService`(后者适合周期性,不适合长阻塞)
3. **listener 异常隔离** —— `transition()` 同 stdio 实现 per-listener try/catch
4. **close() 幂等** —— `closing` AtomicBoolean 守卫

### 3. `StreamableHttpMcpServerConnection` 完整实现(dsh §6.5 (2.1) 简化模板)

**字段**(比 SSE 简单,**无 SSE reader 线程**):

| 字段 | 类型 | 初始值 | 用途 |
|---|---|---|---|
| `cfg` | `final McpServerConfig` | required | 配置(url 必填) |
| `hbIntervalMs / hbTimeoutMs / reconnectCapMs` | `final long` | cfg 默认 | 同 stdio |
| `mapper` | `final ObjectMapper` | `new ObjectMapper()` | JSON parse |
| `state / lastBeat / reconnectAttempts / listeners / closing / cachedTools / hbExecutor` | 同 stdio | | |

**`start()` 5 步**(对齐 stdio + 改 HTTP):
1. `HTTP POST {cfg.url}/initialize` body=`{protocolVersion, capabilities, clientInfo}` → 等 `result.protocolVersion` 响应
2. `HTTP POST {cfg.url}/notifications/initialized` 通知 server 已 ready
3. `HTTP POST {cfg.url}/tools/list` → 解析 `result.tools` 缓存 `cachedTools`
4. 切 `ConnectionState.CONNECTED` + 重置 `reconnectAttempts=0`
5. 启 daemon 心跳(`hbExecutor.scheduleAtFixedRate(this::probe, hbIntervalMs, hbIntervalMs, ms)`)

**`probe()` 心跳**(dsh L4559 简化):
- `HTTP GET {cfg.url}/health` → 期望 200;超时或 5xx → DISCONNECTED → scheduleReconnect
- 优先 GET /health(轻量),失败降级 ping POST

**`callTool(toolName, input)`** —— 非 CONNECTED 返 error(同 stdio);否则 `HTTP POST {cfg.url}/tools/call` body=`{name, arguments}` → 解析 `result.{content, isError}` → `McpCallResult`(调用 `McpHttpSupport.postJsonRpc`)。

**`scheduleReconnect()` / `close()` / listener 模式** —— **完全复用 stdio 模式**,**无 SSE reader** —— 重连时 HTTP 是 stateless,无需清理额外资源。

**关键不变量**:
1. `start()` 抢占守卫 + listener 异常隔离 + close() 幂等 同 stdio
2. **无 SSE reader** —— `close()` 不 interrupt SSE 线程(因为没有);只需关 `hbExecutor` + 切 FAILED
3. **重连是 stateless** —— `scheduleReconnect()` 直接重发 initialize / tools/list,无需清理 HTTP 连接(`HttpURLConnection` 每次 new + close,无 keep-alive)

### 4. `McpServerConnectionFactory` 改写(dsh §6.5 (2.1) L4601-4614 字面落地)

**改前**(Story #021a):
```java
case SSE:
case STREAMABLE_HTTP:
    throw new McpTransportException("LINGS-M01",
        "MCP transport " + t + " is not implemented yet (Story #021a only supports STDIO; "
            + "SSE / STREAMABLE_HTTP land in Story #021c)");
```

**改后**:
```java
case STDIO:
    return new StdioMcpServerConnection(cfg);
case SSE:
    return new SseMcpServerConnection(cfg);
case STREAMABLE_HTTP:
    return new StreamableHttpMcpServerConnection(cfg);
default:
    throw new IllegalStateException("Unhandled MCP transport: " + t);
```

**Javadoc 改写**:去掉「Story #021a only supports STDIO」说明,改为「3 transport 全实现,加新 transport 加 case 即可」。

### 5. `McpErrorCodes` 扩字段(Story #021c 引入新错误码)

**改前**:
```java
public static final String LINGS_M01 = "LINGS-M01";  // factory 拒未实现 transport
public static final String LINGS_M02 = "LINGS-M02";  // tool call 未预期异常
```

**改后**:
```java
public static final String LINGS_M01 = "LINGS-M01";  // factory 拒未实现 transport(已废 — #021c 后全实现)
public static final String LINGS_M02 = "LINGS-M02";  // tool call 未预期异常
/** 🆕 Story #021c — HTTP / SSE 域失败(HTTP 5xx / SSE event 解析失败 / HTTP upgrade 超时 / connection refused)。 */
public static final String LINGS_M03 = "LINGS-M03";
```

**保留 LINGS_M01** —— 即使 #021c 后 factory 不再抛,仍是 dsh §15.9 MCP 域保留错误码(向后兼容 #021a 测试 + 未来 plugin 可能再触发)。

---

## §0 Acceptance Criteria(AC-021c-NN)

> AC 由 dsh §6.5 (2.1) L4821-4835 + Story #021c WHY 段 4 个问题交叉得出。
> **每个 AC 必须有对应的黑盒测试 case(在 `lingshu-core/src/test/.../mcp/`)**。

### AC-021c-1:`SseMcpServerConnection.start()` 5 步 HTTP + SSE 流程

**AC 内容**:`start()` 严格按 dsh L4689-4722 5 步(适配 HTTP):
1. `HTTP POST {cfg.url}/initialize` body=`{protocolVersion:"2024-11-05", capabilities:{}, clientInfo:{name:"lingshu-agent", version:"1.0"}}` → 等 `result.protocolVersion` 响应
2. `HTTP POST {cfg.url}/notifications/initialized` 通知 server(无 id)
3. `HTTP POST {cfg.url}/tools/list` → 解析 `result.tools` 缓存 `cachedTools`
4. 启动 SSE 读线程 daemon
5. 切 `ConnectionState.CONNECTED` + 重置 `reconnectAttempts=0` + 启 daemon 心跳

**反向 AC**:
- ❌ 跳过 initialize 握手(破坏 MCP 协议)
- ❌ 不启动 SSE reader 线程(server push `tools/listChanged` 收不到)
- ❌ 启心跳前未重置 reconnectAttempts(退避计数永远不归零)

**DoD**:`SseMcpServerConnectionStartTest`(≥ 5 case,见 tasks.md T-09)— 用 fake SSE/HTTP server fixture(JDK 内置 `com.sun.net.httpserver.HttpServer` + SSE handler)

### AC-021c-2:`SseMcpServerConnection` SSE EventSource 触发 tools/listChanged 重拉

**AC 内容**:SSE 读线程 daemon 持续读 `GET {cfg.url}` 长连接;server push `event: message\ndata: {"method":"notifications/tools/list_changed"}` → 解析 → 重发 `POST {cfg.url}/tools/list` → 更新 `cachedTools` → listener 触发 McpTransport 重 register。

**反向 AC**:
- ❌ SSE 流不读(server push 永远漏)
- ❌ 收到 `tools/listChanged` 但不重拉 tools(Agent 永远看 stale tool 列表)
- ❌ SSE 解析只支持 `data:` 行,不支持空行事件边界(SSE spec 必填)

**DoD**:`SseMcpServerConnectionSseTestListenerTest`(≥ 3 case,见 tasks.md T-10)

### AC-021c-3:`SseMcpServerConnection` 心跳保活(GET /health)

**AC 内容**:daemon `hbExecutor` 每 `heartbeatIntervalMs` 探活一次,`HTTP GET {cfg.url}/health` → 期望 200;超时或 5xx → `DISCONNECTED` → `scheduleReconnect`。成功后 `lastHeartbeatAt` 更新。

**反向 AC**:
- ❌ 心跳用错 endpoint(`POST /tools/call` 而不是 `GET /health` 会破坏 server 状态)
- ❌ 心跳不区分超时 / 5xx(超时可能是网络抖动,可降级为 reconnect;5xx 是 server 故障,直接 reconnect)
- ❌ 探活间隔过长(进程死了 30s 后才发现 → 30s 内 LLM 调 MCP 全 error)

**DoD**:`SseMcpServerConnectionHeartbeatTest`(≥ 4 case,见 tasks.md T-11)

### AC-021c-4:`SseMcpServerConnection` 指数退避 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 无限重试

**AC 内容**:`scheduleReconnect()` 调度公式同 stdio `delayMs = Math.min(reconnectCapMs, (1L << Math.min(attempt - 1, 6)) * 1000L)`;`reconnectAttempts.incrementAndGet()` 在每次 schedule;失败**无限**重试。

**反向 AC**:
- ❌ 退避序列错(2s → 4s → ... 漏 1s 起点)
- ❌ 上限超过 reconnectCapMs(60s 之后还在指数上升)
- ❌ 限制重试次数(用户运维场景 MCP server 可能维护几小时,应无限重试)

**DoD**:`SseMcpServerConnectionReconnectTest`(≥ 3 case,见 tasks.md T-12)— 用 `hbIntervalMs=50` 测试 fixture 1s 内验证 7 步序列

### AC-021c-5:`StreamableHttpMcpServerConnection.start()` 5 步 stateless HTTP 流程

**AC 内容**:`start()` 5 步(同 AC-021c-1 但**无 SSE reader**):
1. `HTTP POST {cfg.url}/initialize`
2. `HTTP POST {cfg.url}/notifications/initialized`
3. `HTTP POST {cfg.url}/tools/list` → 缓存 `cachedTools`
4. 切 `ConnectionState.CONNECTED` + 重置 `reconnectAttempts=0`
5. 启 daemon 心跳(`hbExecutor`)

**反向 AC**:
- ❌ 多启 SSE reader 线程(无状态 HTTP 不需要 SSE)
- ❌ 重连时复用 HTTP 连接(无状态 HTTP 应每次 new + close)

**DoD**:`StreamableHttpMcpServerConnectionStartTest`(≥ 4 case,见 tasks.md T-13)

### AC-021c-6:`StreamableHttpMcpServerConnection` 心跳(GET /health)

**AC 内容**:同 AC-021c-3 但无 SSE reader 线程。

**DoD**:`StreamableHttpMcpServerConnectionHeartbeatTest`(≥ 3 case,见 tasks.md T-14)

### AC-021c-7:`StreamableHttpMcpServerConnection` 指数退避无限重试

**AC 内容**:同 AC-021c-4 但无 SSE reader 清理。

**DoD**:`StreamableHttpMcpServerConnectionReconnectTest`(≥ 3 case,见 tasks.md T-15)

### AC-021c-8:`McpServerConnectionFactory` 按 transport 全实现 dispatch

**AC 内容**:`McpServerConnectionFactory.create(cfg)` 按 `cfg.getTransport()` 分派:
- `STDIO` → `new StdioMcpServerConnection(cfg)`
- `SSE` → `new SseMcpServerConnection(cfg)`
- `STREAMABLE_HTTP` → `new StreamableHttpMcpServerConnection(cfg)`

不再抛 `LINGS-M01`。

**反向 AC**:
- ❌ SSE / STREAMABLE_HTTP 仍抛 LINGS-M01(`SseMcpServerConnection` / `StreamableHttpMcpServerConnection` 没接进 factory)
- ❌ 默认 case 返 null(静默失败)

**DoD**:`McpServerConnectionFactoryTest`(≥ 5 case,见 tasks.md T-16)— 覆盖 3 transport + null cfg + null transport

### AC-021c-9:`McpErrorCodes.LINGS_M03` 新增错误码

**AC 内容**:`McpErrorCodes` 加常量 `LINGS_M03 = "LINGS-M03"`(`MCP_HTTP_SSE_FAILED`);SSE/HTTP 失败路径(`McpHttpSupport.postJsonRpc` 抛 `McpTransportException`)用此错误码。

**反向 AC**:
- ❌ SSE/HTTP 失败 catch-all 转 LINGS-M02(不能精确归因)
- ❌ 错误码字符串拼写错(应走 `McpErrorCodes.LINGS_M03` 常量)

**DoD**:`McpErrorCodesTest`(≥ 3 case,见 tasks.md T-17)

### AC-021c-10:`SseMcpServerConnection.close()` 同步进入 FAILED 终态

**AC 内容**:`close()` 同步:
- `closing.set(true)`
- `hbExecutor.shutdownNow()`
- `sseReader.interrupt()`(若 alive)
- 关闭 SSE InputStream(若 alive)
- `transition(FAILED)`

幂等(多次 close 安全)。

**反向 AC**:
- ❌ close 后还启心跳(daemon 线程泄露)
- ❌ SSE reader 不 interrupt(永久阻塞 `readLine()`)
- ❌ 不切 FAILED 状态(McpTransport 不知道连接死了)

**DoD**:`SseMcpServerConnectionCloseTest`(≥ 2 case,见 tasks.md T-18)

### AC-021c-11:`StreamableHttpMcpServerConnection.close()` 同步进入 FAILED 终态

**AC 内容**:同 AC-021c-10 但**无 SSE reader 清理** —— 只关 `hbExecutor` + `transition(FAILED)`。

**DoD**:`StreamableHttpMcpServerConnectionCloseTest`(≥ 2 case,见 tasks.md T-19)

### AC-021c-12:R-13 dep-tree 0 增量

**AC 内容**:`mvn -pl lingshu-core dependency:tree` pre/post 对比,**0 binary delta**(SSE/HTTP 用 JDK 1.1 内置 `HttpURLConnection` + 手写 SSE parser,无需额外 Maven 坐标)。

**反向 AC**:
- ❌ 引入 `org.java-websocket:java-websocket`(WebSocket 不是 MCP transport)
- ❌ 引入 `com.squareup.okhttp:okhttp`(OkHttp 含 SSE 但引入新依赖,R-13 锁)
- ❌ 引入 `com.launchdarkly:okhttp-eventsource`(同上)
- ❌ 引入 `java.net.http.HttpClient` 替代品(三方 polyfill)

**DoD**:`AC-021c-deps-1`(dependency:tree pre/post 截图,在 PR body 末尾)

---

## EC(Edge Cases)

### EC-021c-1:`SseMcpServerConnection` start() 期间 HTTP 500 / connection refused

**触发**:cfg.url 是无效 URL(如 `http://localhost:1` connection refused)→ `HttpURLConnection.getInputStream()` 抛 `IOException`。
**期望**:`catch (Exception e)` → `scheduleReconnect()` → 状态 `RECONNECTING`(非 IDLE);日志 `[MCP:xxx] start failed: ...`。
**DoD**:`SseMcpServerConnectionStartErrorTest`(1 case)

### EC-021c-2:`callTool()` 在非 CONNECTED 状态被调用

**触发**:MCP server 突然断开(LLM 调 MCP tool 的同时),`SseMcpServerConnection.state()` 已变 `DISCONNECTED`。
**期望**:`callTool()` 直接返 `McpCallResult.error("MCP server xxx not connected: DISCONNECTED")`,**不抛异常**。
**DoD**:`SseMcpServerConnectionCallToolNotConnectedTest`(1 case)+ `StreamableHttpMcpServerConnectionCallToolNotConnectedTest`(1 case)

### EC-021c-3:SSE event 数据格式异常(非 JSON)

**触发**:server push `data: not-json-{{{`。
**期望**:`handleSseEvent()` `catch (JsonProcessingException e)` → 打 WARN 日志,**不**中断 SSE 读线程(继续读下一个 event)。
**DoD**:`SseMcpServerConnectionSseTestListenerTest.malformedEvent_continuesReading`(1 case)

### EC-021c-4:SSE 流断(反向代理超时踢线)

**触发**:`BufferedReader.readLine()` 返 null(stream closed by server / proxy)。
**期望**:SSE 读线程退出 → `transition(DISCONNECTED)` → `scheduleReconnect()` → 重建 HttpURLConnection → 重发 initialize + tools/list → 切 CONNECTED。
**DoD**:`SseMcpServerConnectionReconnectTest.sseStreamBroken_reconnects`(1 case)

### EC-021c-5:`McpHttpSupport.postJsonRpc` HTTP 5xx

**触发**:server 返 503 Service Unavailable。
**期望**:抛 `McpTransportException(LINGS_M03, "HTTP 503: ...")` — 精确归因 5xx 状态码。
**DoD**:`McpHttpSupportTest.postJsonRpc_http5xx_throwsM03`(1 case)

### EC-021c-6:`McpServerConnectionFactory` cfg 字段缺失(SSE / STREAMABLE_HTTP 缺 url)

**触发**:yml 写 `transport: sse` 但**忘**写 `url: ...`。
**期望**:`McpHttpSupport.postJsonRpc(null, ...)` 抛 `IllegalArgumentException` → SSE 连接在 start() 阶段 fail → `scheduleReconnect()` → 日志提示。
**DoD**:`SseMcpServerConnectionStartErrorTest.missingUrl_schedulesReconnect`(1 case)

### EC-021c-7:`StreamableHttpMcpServerConnection.close()` 在 CONNECTING 状态被调

**触发**:start() 进行中(initialize handshake 未完成)→ close()。
**期望**:`closing.set(true)` + 切 FAILED;start() 后续路径检查 `closing` flag → return;**不抛异常**。
**DoD**:`StreamableHttpMcpServerConnectionCloseTest.closeDuringConnecting_succeeds`(1 case)

### EC-021c-8:HTTP 超时(`HttpURLConnection.setReadTimeout`)

**触发**:server hang 不响应。
**期望**:读 `InputStream` 抛 `SocketTimeoutException` → 抛 `McpTransportException(LINGS_M03, "HTTP read timeout: ...")`(精确归因)。
**DoD**:`McpHttpSupportTest.postJsonRpc_timeout_throwsM03`(1 case)

---

## 反向 AC(Story 边界守门员)

- ❌ **修改 `McpTransport` 协调者** —— Story #021b 已落地 `#021c` 直接消费 `factory.create(...)` 三类全实现,**不改** McpTransport / McpToolAdapter / ToolExecutor / PermissionPolicy / AuditLogger
- ❌ **修改 `McpToolAdapter`** —— #021b 已落地,转发 `transport.callTool(serverName, remoteToolName, input)` 不变
- ❌ **修改 `StdioMcpServerConnection`** —— #021a 已落地,stdio 是另一 transport;**SSE/HTTP 不动 stdio 任何字段**
- ❌ **改 `McpServerConnection` interface** —— 8 方法契约不变(Story #021a 锁定),SSE/HTTP 只是新 concrete 实现
- ❌ **引入额外 Maven 依赖** —— R-13 dep-tree 0 增量(`HttpURLConnection` 是 JDK 1.1 内置,手写 SSE parser)
- ❌ **用 `java.net.http.HttpClient`(JDK 11+)会破 §0 L39 编译目标约束** —— 必须用 `HttpURLConnection`(JDK 1.1)
- ❌ **引入 WebSocket / gRPC / 任何非 HTTP 协议** —— SSE/HTTP 严格对齐 MCP spec 2024-11-05 transport types
- ❌ **OAuth / Bearer auth 完整实现** —— Story #021c 只放占位(`McpServerConfig` 加 `authToken` 字段,builder 接受但不主动用),OAuth flow 留给后续 Story
- ❌ **修改 `McpServerConfig` 添加 `authToken` 字段** —— 字段加在 `AgentConfig.ServerConfig` + `McpServerConfig` 同步扩,Story #021c **不**扩字段;auth 留给后续 Story

---

## 文件清单(5 文件,1 测试目录)

**新建**(3):
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpHttpSupport.java`
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/SseMcpServerConnection.java`
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnection.java`

**修改**(2):
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java` —— 移除 LINGS-M01 throw,SSE / STREAMABLE_HTTP return concrete
- `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpErrorCodes.java` —— 加 `LINGS_M03` 常量 + Javadoc

**新建测试**(1 目录 + ≥22 case):
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/`:
  - `McpHttpSupportTest.java`(L1+L2,4 case)
  - `SseMcpServerConnectionStartTest.java`(L2+L3,5 case)
  - `SseMcpServerConnectionSseTestListenerTest.java`(L2+L3,3 case)
  - `SseMcpServerConnectionHeartbeatTest.java`(L2+L3,4 case)
  - `SseMcpServerConnectionReconnectTest.java`(L2+L3,3 case)
  - `SseMcpServerConnectionCloseTest.java`(L2,2 case)
  - `SseMcpServerConnectionCallToolNotConnectedTest.java`(EC,1 case)
  - `SseMcpServerConnectionStartErrorTest.java`(EC,1 case)
  - `StreamableHttpMcpServerConnectionStartTest.java`(L2+L3,4 case)
  - `StreamableHttpMcpServerConnectionHeartbeatTest.java`(L2+L3,3 case)
  - `StreamableHttpMcpServerConnectionReconnectTest.java`(L2+L3,3 case)
  - `StreamableHttpMcpServerConnectionCloseTest.java`(L2,2 case)
  - `StreamableHttpMcpServerConnectionCallToolNotConnectedTest.java`(EC,1 case)
  - `StreamableHttpMcpServerConnectionStartErrorTest.java`(EC,1 case)
  - `McpServerConnectionFactoryTest.java`(修改,加 SSE/STREAMABLE_HTTP case;Story #021a 4 case 保留)
  - `McpErrorCodesTest.java`(L1,3 case)
  - fixture: `TestMcpHttpServer.java`(fake HTTP server,JDK 内置 `com.sun.net.httpserver.HttpServer`)
  - fixture: `TestMcpSseServer.java`(fake SSE server,扩展 `TestMcpHttpServer` 加 SSE handler)

**总计**:17 测试文件(含 fixture)/ ≥42 case / ~900 行测试代码 / ~670 行生产代码

---

## 关联文档

- dsh v1.5.37 §6.5 (2.1) L4821-4835(`SseMcpServerConnection` 差异说明段,3 处差异)
- dsh v1.5.37 §6.5 (2.1) L4601-4614(`McpServerConnectionFactory` switch 字面落地)
- dsh v1.5.37 §6.5 (2.1) L4837-4871(`application.yml` 配置 + 启动日志样例)
- dsh v1.5.37 §15.9 L7211-7220(ErrorCode 编码约定 `M01-M99`)
- dsh v1.5.37 §4.10.1 硬规则 2(ToolExecutor 5 步流水线)
- dsh v1.5.37 §14.15.7(7 层测试金字塔)
- Story #021a mcp-stdio-transport(interface 8 方法 + 6 态 + stdio 实现,#021c 复用模板)
- Story #021b mcp-tool-adapter(`McpTransport` 协调者 + `McpToolAdapter`,#021c 直接消费)
- Story #009c a2a-httpjsonrpc-and-remote-tool(`HttpURLConnection` 用法参考 + `com.sun.net.httpserver.HttpServer` 测试 fixture 样板)
- Story #020a skill-foundation(`Skill` interface + `ToolRegistry` 双索引模式)
- Story #020c cli-skill-trigger(`--list-skills` banner 模式,日后 `--list-mcp` 可复用)
- ROADMAP §6.5 (2.1) 提议 Story 列表第 6 行(`#021c mcp-sse-and-http-transport`)
- SOP v1.21 §3.2 AC-NN-deps-* + §3.4 T-dep-tree-*(R-13 自查链路)