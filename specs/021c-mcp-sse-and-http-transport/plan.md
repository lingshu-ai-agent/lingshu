# Story #021c `mcp-sse-and-http-transport` — Plan

> **Status**: Draft 2026-09-23
> **Implements**: `specs/021c-mcp-sse-and-http-transport/spec.md`
> **Source**: dsh v1.5.37 §6.5 (2.1) `SseMcpServerConnection` 差异段(L4821-4835)+ §6.5 (2.1) `McpServerConnectionFactory` switch(L4601-4614)+ §15.9 编码约定(L7211-7220)+ §4.10.1 硬规则 2(ToolExecutor 5 步流水线)
> **Pre-req**: ✅ Story #021a(`McpServerConnection` interface 8 方法 + `ConnectionState` 6 态 + `StdioMcpServerConnection` 完整实现)已合入 —— #021c 直接复用 interface + state enum + 退避公式 + listener 异常隔离模式
> **Pre-req**: ✅ Story #021b(`McpTransport` 协调者 + `McpToolAdapter`)已合入 —— #021c factory dispatch 全实现后,`McpTransport.connect(List<McpServerConfig>, ToolRegistry)` 三类 transport 都能注册 tool 到 `ToolRegistry`

---

## §1 范围与非范围

### In-Scope(5 核心文件 + 1 测试目录)

| 文件 | 行为 | 行数预算 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpHttpSupport.java`(新)| utility 共享 `HttpURLConnection` + JSON-RPC envelope 模板 | ~160 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/SseMcpServerConnection.java`(新)| HTTP + SSE EventSource + 周期 GET /health 心跳 + 重建 HttpURLConnection 重连 + 退避无限重试 + daemon SSE reader 线程 | ~290 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnection.java`(新)| stateless HTTP POST tools/* + 周期 GET /health 心跳 + 退避无限重试(无 SSE reader) | ~220 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java`(修改)| switch 移除 LINGS-M01 throw,SSE / STREAMABLE_HTTP return concrete 实例 | ±10 / switch 改 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpErrorCodes.java`(修改)| 加常量 `LINGS_M03 = "LINGS-M03"`(`MCP_HTTP_SSE_FAILED`)| +5 |
| `lingshu-core/src/test/java/ai/lingshu/core/mcp/`(新)| 17 测试文件 + 2 fixture | ~900 / ≥42 case |

### Out-of-Scope(显式 deferred)

- **OAuth / Bearer auth 完整实现** —— `McpServerConfig` 当前无 `authToken` 字段;Story #021c **不扩字段**,auth 留给后续 Story
- **SSE EventSource 第三方库依赖** —— dsh L4825 提到 `SseEventSource`,但 OkHttp / LaunchDarkly EventSource 任何引入都会破 R-13 mitigation (d);#021c 用**手写 SSE parser**(`BufferedReader.readLine()` 解析 `data:` / 空行事件边界),0 新依赖
- **gRPC / WebSocket MCP transport** —— MCP spec 2024-11-05 只定义 stdio / SSE / streamable HTTP 三类 transport;gRPC / WebSocket 不在 spec 范围
- **修改 `McpTransport` 协调者** —— #021b 已落地,#021c factory dispatch 全实现后 `McpTransport.connect()` 三类 transport 都能 register tool,**不改** 任何 McpTransport 字段
- **修改 `McpToolAdapter`** —— #021b 已落地,转发 `transport.callTool(serverName, remoteToolName, input)` 不变
- **修改 `StdioMcpServerConnection`** —— stdio transport 不变
- **改 `McpServerConnection` interface 8 方法契约** —— #021a 已锁定
- **改 `McpServerConfig` 添加 auth / tls 字段** —— 字段加在 `AgentConfig.ServerConfig` + `McpServerConfig` 同步扩,**#021c 不扩字段**,留给后续 Story
- **修改 `McpTransport` / `McpToolAdapter`** —— factory 全实现即可,#021c 不动消费方
- **MCP spec Content-Length framing** —— StdioMcpServerConnection 当前用简化 line-delimited JSON(每行 1 JSON),#021a spec 已经偏离 spec-compliant;SSE/HTTP 用 HTTP wire protocol,本身有 Content-Length,**不需要**手动 framing

---

## §2 接口契约锚点(dsh §6.5 (2.1))

### 2.1 `McpHttpSupport` utility class(dsh §6.5 (2) JSON-RPC over HTTP 共享样板)

**6 个静态方法**:

| 方法 | 签名 | 用途 | 行数 |
|---|---|---|---|
| `postJsonRpc` | `static JsonNode postJsonRpc(String url, JsonNode body, long timeoutMs)` | POST application/json,读 response body,返 JsonNode | ~35 |
| `getJson` | `static String getJson(String url, long timeoutMs)` | GET 读 body(text) | ~25 |
| `getJsonNode` | `static JsonNode getJsonNode(String url, long timeoutMs)` | GET 读 body(JsonNode) | ~25 |
| `buildInitializeParams` | `static ObjectNode buildInitializeParams()` | `{protocolVersion:"2024-11-05", capabilities:{}, clientInfo:{name:"lingshu-agent", version:"1.0"}}` | ~10 |
| `parseToolList` | `static List<McpToolDescriptor> parseToolList(JsonNode resp)` | 解析 `resp.result.tools` → `List<McpToolDescriptor>` | ~15 |
| `parseCallResult` | `static McpCallResult parseCallResult(JsonNode resp)` | 解析 `resp.result.{content, isError}` → `McpCallResult` | ~10 |

**Lombok 注解**:
- 无(utility class 用 private ctor + static methods)

**Javadoc**(类级,5 段落):
1. **What** — MCP 共享 HTTP 工具(避免 SseMcpServerConnection + StreamableHttpMcpServerConnection 重复样板)
2. **Why here** — 集中 HTTP / JSON-RPC envelope 错误处理(转 `McpTransportException(LINGS-M03)`)
3. **JDK 8 兼容** —— 用 JDK 1.1 内置 `HttpURLConnection` + Jackson 已锁,无 `java.net.http.HttpClient`(JDK 11+)依赖
4. **Timeout 语义** —— `setConnectTimeout` / `setReadTimeout` 都设;任一超 → 抛 `McpTransportException(LINGS_M03, "HTTP timeout: ...")`
5. **错误码归因** —— 4xx → `McpTransportException(LINGS_M03, "HTTP <code>: <body>")` / 5xx → `McpTransportException(LINGS_M03, "HTTP <code>: <body>")` / IOException → `McpTransportException(LINGS_M03, "HTTP IO: <msg>")`

**`postJsonRpc` 实现**(对齐 dsh L4678-4708 stdio sendAndAwait 模板):
```java
public static JsonNode postJsonRpc(String url, JsonNode body, long timeoutMs) {
    if (url == null || url.isEmpty()) {
        throw new IllegalArgumentException("url must not be empty");
    }
    HttpURLConnection conn = null;
    try {
        conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        conn.setReadTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(mapper.writeValueAsBytes(body));
            os.flush();
        }
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300)
            ? conn.getInputStream()
            : conn.getErrorStream();
        byte[] raw = readAllBytes(is);
        if (code < 200 || code >= 300) {
            throw new McpTransportException("LINGS-M03",
                "HTTP " + code + ": " + truncate(raw, 200));
        }
        return mapper.readTree(raw);
    } catch (IOException e) {
        throw new McpTransportException("LINGS-M03",
            "HTTP IO: " + e.getMessage(), e);
    } finally {
        if (conn != null) {
            conn.disconnect();
        }
    }
}
```

### 2.2 `SseMcpServerConnection` 完整实现(dsh §6.5 (2.1) L4821-4835)

**字段**(10 个,见 spec.md §2 表)

**私有方法**(对齐 dsh 模板 + SSE 扩展):

| 方法 | 语义 | 行数 |
|---|---|---|
| `start()` | 5 步 start + 抢占守卫 + catch Exception → scheduleReconnect | ~50 |
| `probe()` | `HTTP GET /health` → 200 = OK, 否则 DISCONNECTED + scheduleReconnect | ~20 |
| `scheduleReconnect()` | `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 退避调度 | ~20 |
| `transition(ConnectionState)` | state.set(next) + for-each listener(异常隔离)| ~10 |
| `callTool(name, input)` | 非 CONNECTED 返 error;否则 `McpHttpSupport.postJsonRpc(url+"/tools/call", {name, arguments}, hbTimeoutMs)` + parseCallResult | ~20 |
| `close()` | closing.set(true) + hb.shutdownNow + sseReader.interrupt + transition(FAILED) | ~15 |
| `readSseLoop()` | daemon 线程循环读 SSE stream;parse event → handleSseEvent;流断 / IOException → DISCONNECTED + scheduleReconnect | ~50 |
| `openSseStream()` | `HttpURLConnection GET {cfg.url}` Accept: text/event-stream → InputStream | ~20 |
| `handleSseEvent(String data)` | parse JSON;若 `method == "notifications/tools/list_changed"` → 重发 tools/list + 更新 cachedTools | ~25 |

**测试用 ctor**(可调参数):
```java
public SseMcpServerConnection(McpServerConfig cfg) {
    this(cfg, -1L, -1L, -1L);
}
public SseMcpServerConnection(McpServerConfig cfg, long hbIntervalMs, long hbTimeoutMs, long reconnectCapMs) {
    // ... 对齐 stdio 模式
}
```

**SSE 读线程独立于 `hbExecutor`** —— SSE reader 是 blocking IO 线程(`readLine()` 阻塞),不能与 `hbExecutor` 复用;用 `Thread` 而非 `ScheduledExecutorService`(后者适合周期性,不适合长阻塞)。

**关键不变量**(Javadoc 强调):
1. `start()` 抢占守卫:只允许 IDLE / DISCONNECTED / RECONNECTING 状态调 start()(否则 return)— 防止重入导致多 SSE 连接
2. **SSE 读线程 daemon** —— 不阻塞 JVM exit
3. **`close()` 提前** —— SSE reader 阻塞 `readLine()`,必须 `sseReader.interrupt()` 唤醒
4. `scheduleReconnect()` 抢占守卫:DISCONNECTED → RECONNECTING 用 CAS;已经是 RECONNECTING 不重复调度
5. `transition()` listener 异常隔离:try/catch per listener
6. `close()` 幂等:`closing` AtomicBoolean 守卫
7. **重连后 `listTools()` 重新拉** —— 与 stdio 对齐 dsh L4826

### 2.3 `StreamableHttpMcpServerConnection` 完整实现(dsh §6.5 (2.1) 简化模板)

**字段**(比 SSE 简单,**无 SSE reader**):

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

**私有方法**(对齐 stdio + HTTP 适配):

| 方法 | 语义 | 行数 |
|---|---|---|
| `start()` | 5 步 start(无 SSE reader) + 抢占守卫 + catch Exception → scheduleReconnect | ~40 |
| `probe()` | `HTTP GET /health` → 200 = OK, 否则 DISCONNECTED + scheduleReconnect | ~15 |
| `scheduleReconnect()` | 退避调度(同 SSE) | ~20 |
| `transition(ConnectionState)` | state.set(next) + for-each listener(异常隔离)| ~10 |
| `callTool(name, input)` | 非 CONNECTED 返 error;否则 `McpHttpSupport.postJsonRpc(url+"/tools/call", {name, arguments}, hbTimeoutMs)` + parseCallResult | ~20 |
| `close()` | closing.set(true) + hb.shutdownNow + transition(FAILED) | ~10 |

**关键不变量**(对齐 stdio,无 SSE 字段):
1. `start()` 抢占守卫 + listener 异常隔离 + close() 幂等 同 stdio
2. **无 SSE reader** —— `close()` 不 interrupt SSE 线程(因为没有);只需关 `hbExecutor` + 切 FAILED
3. **重连是 stateless** —— `scheduleReconnect()` 直接重发 initialize / tools/list,无需清理 HTTP 连接(`HttpURLConnection` 每次 new + close,无 keep-alive)

### 2.4 `McpServerConnectionFactory` 改写(dsh §6.5 (2.1) L4601-4614 字面落地)

**改前**(Story #021a L41-52):
```java
switch (t) {
    case STDIO:
        return new StdioMcpServerConnection(cfg);
    case SSE:
    case STREAMABLE_HTTP:
        throw new McpTransportException("LINGS-M01",
            "MCP transport " + t + " is not implemented yet (Story #021a only supports STDIO; "
                + "SSE / STREAMABLE_HTTP land in Story #021c)");
    default:
        throw new IllegalStateException("Unhandled MCP transport: " + t);
}
```

**改后**:
```java
switch (t) {
    case STDIO:
        return new StdioMcpServerConnection(cfg);
    case SSE:
        return new SseMcpServerConnection(cfg);
    case STREAMABLE_HTTP:
        return new StreamableHttpMcpServerConnection(cfg);
    default:
        throw new IllegalStateException("Unhandled MCP transport: " + t);
}
```

**Javadoc 改写** —— 头部说明改为「3 transport 全实现;加新 transport 加 case 即可」;移除「Story #021a only supports STDIO」说明。

### 2.5 `McpErrorCodes` 扩字段(Story #021c 引入新错误码)

**改前**(Story #021b L37-41):
```java
public static final String LINGS_M01 = "LINGS-M01";  // factory 拒未实现 transport
public static final String LINGS_M02 = "LINGS-M02";  // tool call 未预期异常
```

**改后**:
```java
public static final String LINGS_M01 = "LINGS-M01";  // factory 拒未实现 transport(已废 — #021c 后全实现,保留向后兼容)
public static final String LINGS_M02 = "LINGS-M02";  // tool call 未预期异常

/** 🆕 Story #021c — HTTP / SSE 域失败(HTTP 5xx / SSE event 解析失败 / HTTP upgrade 超时 / connection refused)。 */
public static final String LINGS_M03 = "LINGS-M03";
```

**保留 LINGS_M01** —— 即使 #021c 后 factory 不再抛,仍是 dsh §15.9 MCP 域保留错误码(向后兼容 #021a 测试 + 未来 plugin 可能再触发)。

---

## §3 关键技术决策

### 3.1 `HttpURLConnection` 而非 `java.net.http.HttpClient`(JDK 8 兼容)

**问题**:
- dsh §0 L39 编译目标约束 = JDK 1.8(`<source>1.8</source>`)
- `java.net.http.HttpClient` 是 JDK 11+(`module java.net.http`)
- Story #009c `lingshu-a2a-client` 单独 bump compile target 到 1.11(per CLAUDE.md §2 tech stack)
- Story #021c 是 `lingshu-core` 模块,改 compile target 影响**所有**消费方(`lingshu-a2a-server` / `lingshu-examples` / `lingshu-cli`)

**方案**:
- 用 `java.net.HttpURLConnection`(JDK 1.1 内置,跨 JDK 8/11/17/21 兼容)
- 0 新依赖(R-13 mitigation (d) 严格遵守)
- 缺点:API 较繁琐(`setRequestMethod` / `setDoOutput` / `getResponseCode` / `getInputStream`);需要手动处理 chunked encoding
- 优点:零兼容性问题,与 #021a stdio 用 JDK 8 `ProcessBuilder` 风格一致

**为什么不 bump lingshu-core compile target**:
- 影响面太大(4 个子模块继承父 POM)
- `HttpURLConnection` 已足够覆盖 MCP HTTP 需求(GET / POST / 长连接 SSE 只需 keep `BufferedReader` open)
- 实施期复杂度可控(~160 行 McpHttpSupport + ~290 行 SSE + ~220 行 Streamable HTTP)

### 3.2 手写 SSE parser 而非 EventSource 库

**问题**:
- dsh L4825 `SseEventSource` 是抽象接口,JDK 8/11/17/21 无标准实现
- 第三方 SSE 库(`com.launchdarkly:okhttp-eventsource` / `org.springframework:spring-webflux` 等)都引入新依赖,R-13 锁不允许
- MCP SSE 是简化协议(每行 `data: <json>` + 空行事件边界),**不需要**完整 EventSource spec(无 last-event-id 重连 / retry 指令等高级特性)

**方案**:
- 用 `HttpURLConnection` open `GET {url}` with `Accept: text/event-stream`
- `BufferedReader.readLine()` 循环读行
- 解析规则:
  - 空行 → 事件边界,派发累积的 `data`
  - `data: <text>` → 累积到当前事件 `data` buffer
  - `:` 开头 → 注释行,忽略(常用于 ping/keep-alive)
  - `event: <type>` → 暂不分支(MCP 只用默认 event)
  - `retry: <ms>` → 暂忽略(用我们自己的 backoff)
- 简单 ~50 行实现,**0 依赖**

**为什么不引 OkHttp EventSource**:
- R-13 mitigation (d) 0 binary delta 严格遵守
- OkHttp EventSource 自带 retry / last-event-id / OAuth,我们用不到
- 简单 MCP SSE 协议手写 parser 已经够用

### 3.3 SSE reader 线程独立于 `hbExecutor`

**问题**:
- `hbExecutor` 是 `ScheduledExecutorService`,适合周期任务
- SSE reader 是 blocking IO(`readLine()` 阻塞至 stream close)
- 共享 hbExecutor 会阻塞后续心跳调度

**方案**:
- SSE reader 用 `Thread`(daemon)直接启动,**不**走 `ScheduledExecutorService`
- 启动顺序:`start()` 5 步 → initialize / initialized / tools/list → 启动 SSE reader → 切 CONNECTED → 启 hbExecutor 心跳
- `close()`:`sseReader.interrupt()`(虽然 `readLine()` 阻塞不响应 interrupt,但 stream close 时自然退出)+ `hbExecutor.shutdownNow()` + 切 FAILED

**为什么不引 `Executors.newCachedThreadPool` 复用**:
- 增加线程池管理复杂度(关闭顺序 / shutdownNow / awaitTermination)
- 单 SSE reader 单 Thread 简单清晰
- daemon thread 不阻塞 JVM exit

### 3.4 listener 异常隔离用 per-listener try/catch(同 stdio)

**问题**:状态变化时,多个 listener 注册;若 listener 1 抛异常,listeners 2/3/4 应该仍收到通知。

**方案**(对齐 stdio + dsh L4777-4784):
```java
private void transition(ConnectionState next) {
    ConnectionState prev = state.getAndSet(next);
    if (prev != next) {
        for (Consumer<ConnectionState> l : listeners) {
            try {
                l.accept(next);
            } catch (Exception e) {
                LOG.warn("[MCP:{}] listener threw: {}", cfg.getName(), e.toString());
            }
        }
    }
}
```

**测试验证**:AC-021c-2 + T-10,3 case 覆盖(正常 / 单 listener 抛异常 / 多 listener 顺序)。

### 3.5 退避公式复用 stdio(`1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)`)

**方案**(对齐 stdio + dsh L4728-4742):
```java
private void scheduleReconnect() {
    int attempt = reconnectAttempts.incrementAndGet();
    long delayMs = Math.min(reconnectCapMs, (1L << Math.min(attempt - 1, 6)) * 1000L);
    if (hbExecutor == null || hbExecutor.isShutdown()) {
        hbExecutor = Executors.newSingleThreadScheduledExecutor(daemonTf);
    }
    reconnectFuture = hbExecutor.schedule(this::start, delayMs, TimeUnit.MILLISECONDS);
}
```

**关键差异**(SSE vs Streamable HTTP):
- SSE:重连前需要先关闭旧 SSE InputStream + interrupt reader 线程
- Streamable HTTP:stateless,无需清理(每次 HttpURLConnection new + close)

### 3.6 SSE 流断(反向代理超时踢线)走 reconnect

**实现**(对齐 dsh L4824 差异段):
```java
private void readSseLoop() {
    try (InputStream raw = openSseStream();
         BufferedReader reader = new BufferedReader(new InputStreamReader(raw, UTF_8))) {
        String line;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null && !closing.get()) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    handleSseEvent(data.toString());
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            }
            // 忽略 : 注释 / event: 类型 / retry: 指令
        }
        // 流断 → DISCONNECTED + reconnect
        if (!closing.get()) {
            LOG.warn("[MCP:{}] SSE stream closed by server", cfg.getName());
            transition(ConnectionState.DISCONNECTED);
            scheduleReconnect();
        }
    } catch (IOException e) {
        if (!closing.get()) {
            LOG.warn("[MCP:{}] SSE stream broken: {}", cfg.getName(), e.toString());
            transition(ConnectionState.DISCONNECTED);
            scheduleReconnect();
        }
    }
}
```

**测试验证**:AC-021c-4 EC-021c-4(`SseMcpServerConnectionReconnectTest.sseStreamBroken_reconnects`)。

### 3.7 `McpErrorCodes.LINGS_M03` 精确归因 HTTP / SSE 失败

**方案**(对齐 dsh §15.9 L7211-7220):
- `McpHttpSupport.postJsonRpc` 4xx / 5xx / IOException → 抛 `McpTransportException("LINGS-M03", "<msg>")`(精确归因,不再 catch-all 转 LINGS_M02)
- `SseMcpServerConnection.readSseLoop` IOException → 状态机 DISCONNECTED,**不抛**(state 变化 + reconnect 自然恢复)
- `McpToolAdapter.execute()`(#021b)catch `McpTransportException` 转 ToolResult.error,**仍**走 §4.10.1 硬规则 2(不绕过沙箱 / 权限 / checkpoint)

**保留 LINGS_M01** —— `#021c` 后 factory 不再抛,但 dsh §15.9 MCP 域保留向后兼容 + 未来 plugin 可能再触发。

---

## §4 文件布局(5 文件 + 1 测试目录)

```
lingshu-core/src/main/java/ai/lingshu/core/
├── mcp/                                              ← 🆕 新增 3 文件 / 改写 1 文件
│   ├── McpServerConfig.java                          ← #021a 不变
│   ├── ConnectionState.java                          ← #021a 不变
│   ├── McpServerConnection.java                      ← #021a 不变
│   ├── McpServerConnectionFactory.java               ← 🆕 #021c 改写 switch(移除 LINGS-M01 throw)
│   ├── StdioMcpServerConnection.java                 ← #021a 不变
│   ├── SseMcpServerConnection.java                   ← 🆕 #021c 新增(290 行)
│   ├── StreamableHttpMcpServerConnection.java        ← 🆕 #021c 新增(220 行)
│   ├── McpHttpSupport.java                           ← 🆕 #021c 新增(160 行 utility)
│   ├── McpToolDescriptor.java                        ← #021a 不变
│   ├── McpCallResult.java                            ← #021a 不变
│   └── McpTransportException.java                    ← #021a 不变
└── impl/mcp/
    ├── McpTransport.java                             ← #021b 不变
    ├── McpTransportLifecycle.java                    ← #021b 不变
    ├── McpTransportAutoConfiguration.java            ← #021b 不变
    └── McpErrorCodes.java                            ← 🆕 #021c 扩 +LINGS_M03
```

```
lingshu-core/src/test/java/ai/lingshu/core/
├── mcp/                                              ← 🆕 测试目录(17 文件,含 2 fixture)
│   ├── McpHttpSupportTest.java                       ← 🆕 L1+L2,4 case
│   ├── SseMcpServerConnectionStartTest.java          ← 🆕 L2+L3,5 case
│   ├── SseMcpServerConnectionSseTestListenerTest.java ← 🆕 L2+L3,3 case
│   ├── SseMcpServerConnectionHeartbeatTest.java      ← 🆕 L2+L3,4 case
│   ├── SseMcpServerConnectionReconnectTest.java      ← 🆕 L2+L3,3 case
│   ├── SseMcpServerConnectionCloseTest.java          ← 🆕 L2,2 case
│   ├── SseMcpServerConnectionCallToolNotConnectedTest.java  ← 🆕 EC,1 case
│   ├── SseMcpServerConnectionStartErrorTest.java     ← 🆕 EC,1 case
│   ├── StreamableHttpMcpServerConnectionStartTest.java       ← 🆕 L2+L3,4 case
│   ├── StreamableHttpMcpServerConnectionHeartbeatTest.java   ← 🆕 L2+L3,3 case
│   ├── StreamableHttpMcpServerConnectionReconnectTest.java   ← 🆕 L2+L3,3 case
│   ├── StreamableHttpMcpServerConnectionCloseTest.java       ← 🆕 L2,2 case
│   ├── StreamableHttpMcpServerConnectionCallToolNotConnectedTest.java ← 🆕 EC,1 case
│   ├── StreamableHttpMcpServerConnectionStartErrorTest.java  ← 🆕 EC,1 case
│   ├── McpServerConnectionFactoryTest.java           ← #021a 4 case + #021c 加 2 case = 6 case
│   ├── McpErrorCodesTest.java                        ← 🆕 L1,3 case
│   └── fixture/
│       ├── TestMcpHttpServer.java                    ← 🆕 fake HTTP server(用 com.sun.net.httpserver.HttpServer)
│       └── TestMcpSseServer.java                     ← 🆕 fake SSE server(扩展 TestMcpHttpServer 加 SSE handler)
└── runtime/                                          ← #021a 不变
```

**总计**:3 新文件 + 2 修改 + 17 测试文件(含 2 fixture)/ ~670 行生产代码 / ~900 行测试代码

---

## §5 测试策略(7 层金字塔对齐 dsh §14.15.7)

| 层 | 类型 | 范围 | case 数 |
|---|---|---|---|
| **L1** | Unit | enum 字面值 / POJO 默认值 / 反序列化 / factory dispatch 字面 | 13 |
| **L2** | Integration(单 class,无 Spring)| `McpHttpSupport` HTTP 行为 / `McpErrorCodes` 常量 / factory 改写 | 8 |
| **L2+L3** | Hybrid(fake HTTP/SSE server)| `SseMcpServerConnection` 5 步 + SSE event + 心跳 + 重连 + close / `StreamableHttpMcpServerConnection` 5 步 + 心跳 + 重连 + close | 18 |
| **EC** | Edge case | HTTP 5xx / SSE 流断 / SSE event 格式异常 / close during CONNECTING / missing url | 3 |
| **总计** | | | **42 case** |

**fixture 设计**(`TestMcpHttpServer.java`):

```java
package ai.lingshu.core.mcp.fixture;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake MCP HTTP server (Story #021c) — 用 JDK 内置 HttpServer 模拟 MCP server.
 *
 * <p>支持 endpoint:
 * <ul>
 *   <li>POST /initialize — 回 {protocolVersion, serverInfo, capabilities}</li>
 *   <li>POST /notifications/initialized — 忽略</li>
 *   <li>POST /tools/list — 回 {tools:[{name, description, inputSchema}]}</li>
 *   <li>POST /tools/call — 回 {content:"fake-result", isError:false}</li>
 *   <li>GET /health — 回 200 "OK"</li>
 * </ul>
 *
 * <p>可调行为(系统属性):
 * <ul>
 *   <li>{@code dontReplyHealth} — /health 不响应(模拟死 server)</li>
 *   <li>{@code exitAfter} — N 次请求后关闭(模拟 OOM)</li>
 * </ul>
 */
public class TestMcpHttpServer {
    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        // ... 5 endpoint handlers ...
        server.start();
        System.out.println("PORT=" + server.getAddress().getPort());  // 测试 fixture 解析
    }
}
```

**fixture 设计**(`TestMcpSseServer.java`):

扩展 `TestMcpHttpServer`,加 SSE handler:
- `GET /sse` — `Content-Type: text/event-stream`,周期性推 `data: {"method":"notifications/tools/list_changed"}\n\n` 或 `data: {"method":"foo"}\n\n` 触发重拉

**测试 fixture 启动方式**(对齐 #009c 模式):
```java
Process p = new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"),
    "ai.lingshu.core.mcp.fixture.TestMcpHttpServer").start();
BufferedReader portReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
String portLine = portReader.readLine();  // "PORT=12345"
int port = Integer.parseInt(portLine.split("=")[1]);
String url = "http://localhost:" + port;
```

**fake vs real MCP server**:**fake** —— 真 SSE/HTTP MCP server 需要远程 URL,CI 环境难复现;fake 用 JDK 内置 HttpServer,纯 Java,跨平台,JDK 8 兼容。

**R-13 自查链路**(SOP §3.2 AC-NN-deps-* + §3.4 T-dep-tree-*):
- AC-021c-deps-1:pre `mvn -pl lingshu-core dependency:tree` baseline → 实施 → post `dependency:tree` → diff 仅时间戳差异 → 0 binary delta
- PR body 末尾 `### R-13 dependency:tree 自查` 节

---

## §6 性能预算(dsh §14.15.1)

| 指标 | 目标 | Story #021c 验证方式 |
|---|---|---|
| `factory.create(cfg)` 阻塞时间 | < 10ms | 测试用例`McpServerConnectionFactoryTest.create_sse_dispatchFast`(无 I/O,纯 new) |
| `start()` 异步非阻塞 | 立即返回(HTTP 后续走 fake server)| `SseMcpServerConnectionStartTest.start_async_nonBlocking` |
| SSE reader 线程 daemon | 不阻塞 JVM exit | `SseMcpServerConnectionCloseTest.close_daemonThreadTerminated` |
| 心跳调度精度 | ±100ms(`hbIntervalMs=1000` 时)| `SseMcpServerConnectionHeartbeatTest.probe_intervalAccuracy` |
| 重连退避序列准确性 | ±50ms(`hbIntervalMs=50`)| `SseMcpServerConnectionReconnectTest.backoffSequence` |
| HTTP 5xx 错误归因延迟 | < 1ms(立即抛 LINGS-M03)| `McpHttpSupportTest.postJsonRpc_http5xx_throwsM03` |

**不引入性能测试**(Story #021c 范围是**功能契约**而非性能优化);性能预算 dsh §14.15.1 由 #021b + 后续 §14 N1 OpenTelemetry Story 覆盖。

---

## §7 关键不变量(Story 边界守门员)

1. **ToolExecutor 5 步流水线不变** —— dsh §4.10.1 硬规则 2,Story #021c 不改 ToolExecutor / ToolRegistry / PermissionPolicy / AuditLogger
2. **Skill 系统不变** —— Story #020a + #020b + #020c 已合,Story #021c 不动 Skill 任何类
3. **MCP stdio 不变** —— `#021a` StdioMcpServerConnection 0 修改,SSE/HTTP 是新增 transport 类
4. **`McpServerConnection` 8 方法契约不变** —— `#021a` 锁定,SSE/HTTP 只是新 concrete 实现,不改 interface
5. **`McpTransport`(#021b) + `McpToolAdapter`(#021b) 不变** —— factory dispatch 全实现后,`McpTransport.connect(List, ToolRegistry)` 三类 transport 都能 register tool,**不改** 任何 McpTransport / McpToolAdapter 字段
6. **JDK 8 兼容** —— 用 `HttpURLConnection`(JDK 1.1)+ 手写 SSE parser,**不用** `java.net.http.HttpClient`(JDK 11+) / OkHttp / 任何第三方库;`AtomicReference` / `AtomicInteger` / `AtomicLong` / `AtomicBoolean` / `CopyOnWriteArrayList` + `Collections.emptyList()`,**不使用** `record / sealed / var / List.of / TextBlock`
7. **errors 包单点识别** —— Story #021c 引入新错误码 `LINGS-M03`(HTTP/SSE 域),不是 `L`(LLM)` 也不是 `T`(Tool)`;`LINGS-M03 = MCP_HTTP_SSE_FAILED`
8. **保留 LINGS-M01** —— 即使 factory 不再抛,仍是 dsh §15.9 MCP 域保留错误码,向后兼容 `#021a` 测试
9. **start() / scheduleReconnect() 抢占守卫** —— 防止重入导致多 SSE 连接 / 多 HTTP initialize;CAS / state 检查保证线程安全
10. **listener 异常隔离** —— per-listener try/catch,单 listener 抛异常不影响其他
11. **close() 幂等** —— `closing` AtomicBoolean 守卫 + hb.shutdownNow + sseReader.interrupt + transition(FAILED),多次调用安全
12. **SSE reader daemon** —— 不阻塞 JVM exit;`mcp-sse-{cfg.getName()}` 线程名(对齐 stdio `mcp-hb-{name}` 模式)
13. **重连后 `listTools()` 重新拉** —— 与 stdio 对齐 dsh L4826(对齐 MCP spec)
14. **不扩 `McpServerConfig` 字段** —— `authToken` / TLS 留给后续 Story

---

## §8 关联文档

- dsh v1.5.37 §6.5 (2.1) L4821-4835 —— `SseMcpServerConnection` 差异说明段(主参考)
- dsh v1.5.37 §6.5 (2.1) L4601-4614 —— `McpServerConnectionFactory` switch 字面落地
- dsh v1.5.37 §6.5 (2.1) L4837-4871 —— `application.yml` 配置 + 启动日志样例
- dsh v1.5.37 §6.5 (2) L4454-4551 —— `McpTransport` 调用契约(#021b 消费)
- dsh v1.5.37 §4.10.1 硬规则 2 —— ToolExecutor 5 步流水线(不变量)
- dsh v1.5.37 §15.9 L7211-7220 —— ErrorCode 编码约定(`M01 MCP_CONNECT_FAILED` + `M03 MCP_HTTP_SSE_FAILED`)
- dsh v1.5.37 §14.15.7 —— 7 层测试金字塔(L1+L2+L3 分布)
- dsh §0 L39 —— JDK 8 编译目标硬约束(决定 `HttpURLConnection` 而非 `java.net.http.HttpClient`)
- Story #021a mcp-stdio-transport —— `McpServerConnection` interface + `ConnectionState` 6 态 + `StdioMcpServerConnection` 完整实现
- Story #021b mcp-tool-adapter —— `McpTransport` 协调者 + `McpToolAdapter`,`#021c` 直接消费
- Story #009c a2a-httpjsonrpc-and-remote-tool —— `com.sun.net.httpserver.HttpServer` 测试 fixture 样板参考
- Story #020a skill-foundation —— `Skill` interface + `ToolRegistry` 双索引模式
- Story #020c cli-skill-trigger —— `--list-skills` banner 模式(日后 `--list-mcp` 可复用相同模式)
- ROADMAP §6.5 (2.1) 提议 Story 列表第 6 行(`#021c mcp-sse-and-http-transport`)
- SOP v1.21 §3.2 AC-NN-deps-* + §3.4 T-dep-tree-*(R-13 自查链路)