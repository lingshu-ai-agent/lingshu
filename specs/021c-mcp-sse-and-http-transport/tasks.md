# Story #021c `mcp-sse-and-http-transport` — Tasks

> **Status**: Draft 2026-09-23
> **Implements**: `specs/021c-mcp-sse-and-http-transport/plan.md`
> **Test budget**: ≥ 42 cases / 17 files(L1 13 + L2 8 + L2+L3 18 + EC 3)
> **Pre-req**: ✅ #021a(mcp-stdio-transport)+ ✅ #021b(mcp-tool-adapter)已合入

---

## T-01 — `McpHttpSupport` utility 共享 HTTP / JSON-RPC 样板

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpHttpSupport.java`(新, ~160 行)

**实现**(对齐 plan.md §2.1):
- `public final class McpHttpSupport { private McpHttpSupport() {} ... }`
- 6 个静态方法:
  - `postJsonRpc(String url, JsonNode body, long timeoutMs)` → `JsonNode`
  - `getJson(String url, long timeoutMs)` → `String`
  - `getJsonNode(String url, long timeoutMs)` → `JsonNode`
  - `buildInitializeParams()` → `ObjectNode`
  - `parseToolList(JsonNode resp)` → `List<McpToolDescriptor>`
  - `parseCallResult(JsonNode resp)` → `McpCallResult`
- 错误处理统一抛 `McpTransportException(McpErrorCodes.LINGS_M03, msg)`
- 共享 `private static final ObjectMapper mapper = new ObjectMapper()`

**Javadoc**(类级,5 段落):
1. **What** — MCP 共享 HTTP 工具
2. **Why here** — 集中 HTTP / JSON-RPC envelope 错误处理
3. **JDK 8 兼容** —— `HttpURLConnection`(JDK 1.1) + Jackson 已锁,0 新依赖
4. **Timeout 语义** —— `setConnectTimeout` / `setReadTimeout` 都设
5. **错误码归因** —— 4xx / 5xx / IOException → `McpTransportException(LINGS_M03, ...)`

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpHttpSupportTest` 4 case 通过(见 T-09)
- AC-021c-deps-1:R-13 dep-tree 0 binary delta

---

## T-02 — `SseMcpServerConnection` 完整实现

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/SseMcpServerConnection.java`(新, ~290 行)

**实现**(对齐 plan.md §2.2):
- `public class SseMcpServerConnection implements McpServerConnection`
- 字段(11 个,见 plan.md §2.2 表):
  - `cfg / hbIntervalMs / hbTimeoutMs / reconnectCapMs`(final)
  - `mapper / state / lastBeat / reconnectAttempts / listeners / closing / cachedTools / hbExecutor`(同 stdio)
  - `sseReader volatile Thread`(新增,daemon SSE 读线程)
- ctor(2 个):
  - `public SseMcpServerConnection(McpServerConfig cfg)` —— 调 4-arg ctor 用 cfg 默认值
  - `public SseMcpServerConnection(McpServerConfig cfg, long hbIntervalMs, long hbTimeoutMs, long reconnectCapMs)` —— 测试覆盖用
- 8 abstract 实现 + 9 私有方法(start / probe / scheduleReconnect / transition / callTool / close / readSseLoop / openSseStream / handleSseEvent)

**关键不变量**(Javadoc 强调):
1. `start()` 抢占守卫(同 stdio)—— 只允许 IDLE / DISCONNECTED / RECONNECTING 状态调 start()
2. **SSE 读线程 daemon** —— `mcp-sse-{cfg.getName()}` 命名,不阻塞 JVM exit
3. **`close()` 提前** —— `sseReader.interrupt()` 唤醒阻塞 `readLine()`
4. `scheduleReconnect()` 抢占守卫:DISCONNECTED → RECONNECTING CAS
5. `transition()` listener 异常隔离:per-listener try/catch
6. `close()` 幂等:`closing` AtomicBoolean 守卫
7. **重连后 `listTools()` 重新拉**(对齐 MCP spec)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- AC-021c-1 + AC-021c-2 + AC-021c-3 + AC-021c-4 + AC-021c-10(见 T-09 ~ T-14 测试)
- JDK 8 grep 自查通过(无 `var` / `List.of` / sealed)

---

## T-03 — `StreamableHttpMcpServerConnection` 完整实现

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnection.java`(新, ~220 行)

**实现**(对齐 plan.md §2.3):
- `public class StreamableHttpMcpServerConnection implements McpServerConnection`
- 字段(10 个,见 plan.md §2.3 表):
  - `cfg / hbIntervalMs / hbTimeoutMs / reconnectCapMs`(final)
  - `mapper / state / lastBeat / reconnectAttempts / listeners / closing / cachedTools / hbExecutor`(同 stdio)
  - **无** `sseReader` 字段(无 SSE)
- ctor(2 个,同 SSE 模式)
- 8 abstract 实现 + 6 私有方法(start / probe / scheduleReconnect / transition / callTool / close)

**关键不变量**:
1. `start()` 抢占守卫 + listener 异常隔离 + close() 幂等 同 stdio
2. **无 SSE reader** —— `close()` 不 interrupt SSE 线程
3. **重连是 stateless** —— 每次 HttpURLConnection new + close

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- AC-021c-5 + AC-021c-6 + AC-021c-7 + AC-021c-11(见 T-13 ~ T-15, T-19 测试)
- JDK 8 grep 自查通过

---

## T-04 — `McpServerConnectionFactory` 改写(移除 LINGS-M01 throw)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java`(修改, ±10 行)

**修改**(对齐 plan.md §2.4):
- switch case `SSE` / `STREAMABLE_HTTP` 改为:
  ```java
  case SSE:
      return new SseMcpServerConnection(cfg);
  case STREAMABLE_HTTP:
      return new StreamableHttpMcpServerConnection(cfg);
  ```
- 移除 `throw new McpTransportException("LINGS-M01", "...not implemented in #021a...")`
- 保留 default case `IllegalStateException`
- Javadoc 头部说明改写(去掉「#021a only supports STDIO」)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpServerConnectionFactoryTest` 6 case 通过(原 #021a 4 case + #021c 加 SSE / STREAMABLE_HTTP 2 case)
- AC-021c-8

---

## T-05 — `McpErrorCodes` 扩 `LINGS_M03` 常量

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpErrorCodes.java`(修改, +5 行)

**修改**(对齐 plan.md §2.5):
- 加常量:
  ```java
  /** 🆕 Story #021c — HTTP / SSE 域失败(HTTP 5xx / SSE event 解析失败 / HTTP upgrade 超时 / connection refused)。 */
  public static final String LINGS_M03 = "LINGS-M03";
  ```
- 类级 Javadoc 加 `LINGS_M03` 段说明
- `Per-code map` 列表加 bullet point

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `McpErrorCodesTest` 3 case 通过(见 T-17)
- AC-021c-9

---

## T-06 — `TestMcpHttpServer` fake HTTP server fixture

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/fixture/TestMcpHttpServer.java`(新, ~200 行)

**实现**:
- `public class TestMcpHttpServer { public static void main(String[] args) throws IOException { ... } }`
- 用 JDK 内置 `com.sun.net.httpserver.HttpServer`
- 5 endpoint handlers:
  - `POST /initialize` → `{protocolVersion:"2024-11-05", serverInfo:{name:"test", version:"1.0"}, capabilities:{}}`
  - `POST /notifications/initialized` → 204(忽略)
  - `POST /tools/list` → `{tools:[{name:"echo", description:"echo input", inputSchema:{type:"object", properties:{input:{type:"string"}}}}]}`
  - `POST /tools/call` → `{content:"fake-result", isError:false}`
  - `GET /health` → 200 "OK"
- 系统属性可调:
  - `dontReplyHealth` — /health 不响应
  - `exitAfter=N` — N 次请求后 System.exit(模拟 OOM)
  - `delayMs=N` — 每个 handler 延迟 N ms(模拟 timeout)
- 启动时输出 `PORT=<port>` 给测试 fixture 解析
- 端口 = 0(自动分配)

**Javadoc**(类级,3 段落):
1. **What** — Fake MCP HTTP server fixture
2. **JDK 8 兼容** —— `com.sun.net.httpserver.HttpServer` 是 JDK 内置
3. **fixture 启动方式** —— `ProcessBuilder` 拉子进程

**DoD**:
- 手动启动 `java -cp target/test-classes:... ai.lingshu.core.mcp.fixture.TestMcpHttpServer` + 写 `POST /initialize` → 验证回包正确
- AC-021c-1 + AC-021c-5 测试用到此 fixture

---

## T-07 — `TestMcpSseServer` fake SSE server fixture

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/fixture/TestMcpSseServer.java`(新, ~120 行)

**实现**:
- 扩展 `TestMcpHttpServer` 模式 + 加 SSE handler
- 额外 endpoint:
  - `GET /sse` → `Content-Type: text/event-stream`,周期性推 SSE events:
    - `data: {"method":"notifications/tools/list_changed"}\n\n`(每 200ms 推一次,触发重拉)
    - `data: {"method":"foo"}\n\n`(普通 data,验证 parser 容错)
- 关闭 SSE stream: 系统属性 `closeSseAfter=N` — N ms 后关闭 stream(模拟反向代理超时踢线)

**DoD**:
- 手动启动 + 写 `GET /sse` Accept: text/event-stream → 验证收到 SSE events
- AC-021c-2 + AC-021c-4 EC-021c-4 测试用到此 fixture

---

## T-08 — `McpErrorCodesTest` L1 3 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/mcp/McpErrorCodesTest.java`(新, ~50 行)

**测试方法**:

| 测试 | 输入 | 断言 |
|---|---|---|
| `constants_threeValuesPresent` | enum / class.getDeclaredFields() | LINGS_M01 / LINGS_M02 / LINGS_M03 三个常量值存在 |
| `constant_values_followNamingConvention` | 三常量 string value | 全部以 `"LINGS-M"` 开头 + 2 位数字 |
| `utility_class_ctorThrowsAssertionError` | `McpErrorCodes.class.newInstance()` | 抛 `AssertionError` |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=McpErrorCodesTest` 3 case 全过
- AC-021c-9

---

## T-09 — `McpHttpSupportTest` L1 + L2 4 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpSupportTest.java`(新, ~120 行)

**测试方法**(用 JDK 内置 `com.sun.net.httpserver.HttpServer` 作为本地 HTTP server):

| 测试 | 流程 | 断言 |
|---|---|---|
| `postJsonRpc_happy_returnsParsedJsonNode` | 本地 server 回 200 + JSON body | `result` JsonNode 字段对齐 |
| `postJsonRpc_http5xx_throwsM03` | 本地 server 回 503 | 抛 `McpTransportException`,`getCode()=="LINGS-M03"` |
| `postJsonRpc_http4xx_throwsM03` | 本地 server 回 400 | 同上 |
| `postJsonRpc_connectionRefused_throwsM03` | 关闭本地 server,客户端调 | 抛 `McpTransportException(LINGS-M03, "HTTP IO: ...")` |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=McpHttpSupportTest` 4 case 全过
- T-01 验证

---

## T-10 — `SseMcpServerConnectionStartTest` L2 + L3 5 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionStartTest.java`(新, ~250 行)

**测试方法**(用 `TestMcpHttpServer` + `TestMcpSseServer` fixture, hbIntervalMs=100, hbTimeoutMs=500, reconnectCapMs=200):

| 测试 | 流程 | 断言 |
|---|---|---|
| `start_fakeServer_5stepsReachesConnected` | 启动 fixture → 创建 connection → start() → 等 1s | `state()==CONNECTED && listTools().size()==1 && lastHeartbeatAt() 距 now < 1s` |
| `start_invalidUrl_immediateFailureSchedulesReconnect` | cfg.url=`http://localhost:1`(connection refused) | state `RECONNECTING`(非 IDLE) |
| `start_missingUrl_illegalArgument` | cfg.url=null | start() 抛 IllegalArgumentException 或 state=RECONNECTING |
| `start_alreadyConnected_noop` | 调 start() 第二次 | state 仍 `CONNECTED`,无新 SSE 连接 |
| `start_duringReconnecting_noop` | start() 后立即 scheduleReconnect → 再 start() | state 仍 `RECONNECTING`(不抢) |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=SseMcpServerConnectionStartTest` 5 case 全过
- AC-021c-1

---

## T-11 — `SseMcpServerConnectionSseTestListenerTest` L2 + L3 3 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionSseTestListenerTest.java`(新, ~180 行)

**测试方法**(用 `TestMcpSseServer` fixture,周期性推 `tools/listChanged`):

| 测试 | 流程 | 断言 |
|---|---|---|
| `toolsListChanged_relistTools` | start() → 等 fixture 推 SSE event `tools/listChanged` → 触发重拉 | `cachedTools` 更新(从 1 tool → 2 tools)|
| `malformedEvent_continuesReading` | fixture 推 SSE event 非 JSON `data: not-json-{{{` | SSE reader 继续读下一个 event,无未捕获异常 |
| `eventAfterClose_ignored` | close() → fixture 推 SSE event | event 不被 handle(closing 守卫)|

**DoD**:
- `mvn -pl lingshu-core test -Dtest=SseMcpServerConnectionSseTestListenerTest` 3 case 全过
- AC-021c-2 + EC-021c-3

---

## T-12 — `SseMcpServerConnectionHeartbeatTest` L2 + L3 4 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionHeartbeatTest.java`(新, ~150 行)

**测试方法**(用 `TestMcpHttpServer` fixture, hbIntervalMs=100, hbTimeoutMs=500):

| 测试 | 流程 | 断言 |
|---|---|---|
| `probe_healthOk_stateStable` | fixture + hbIntervalMs=100 | 等 5 次 heartbeat 后 state==CONNECTED,`lastHeartbeatAt` 持续更新 |
| `probe_health5xx_transitionsToDisconnected` | fixture `dontReplyHealth=true`(返 500)→ start() | state==DISCONNECTED 然后 RECONNECTING |
| `probe_healthTimeout_transitionsToDisconnected` | fixture `delayMs=2000` 模拟 hang → hbTimeoutMs=200 | 等 1s,state==DISCONNECTED |
| `probe_doubleCheck_healthRequired` | fixture 偶尔返 503 → hbIntervalMs=100 | 50% 健康时 state 仍可能 DISCONNECTED(双探活)|

**DoD**:
- `mvn -pl lingshu-core test -Dtest=SseMcpServerConnectionHeartbeatTest` 4 case 全过
- AC-021c-3

---

## T-13 — `SseMcpServerConnectionReconnectTest` L2 + L3 3 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionReconnectTest.java`(新, ~150 行)

**测试方法**(用 hbIntervalMs=50, reconnectCapMs=300):

| 测试 | 流程 | 断言 |
|---|---|---|
| `backoff_sequence_1s_2s_4s_8s_16s_32s_60sCap` | start() → 模拟 SSE stream broken → 记录每次 reconnect 间隔 | 序列 = [50, 100, 200, 300(cap), 300, 300, 300] |
| `reconnect_unbounded_noMaxAttempts` | start() → 模拟 broken 5 次 | 第 5 次 reconnect 仍发生(无限重试) |
| `reconnect_success_resetsAttempts` | start() → broken → reconnect 成功 → broken → reconnect | reconnectAttempts 第二次从 1 起算(归零) |
| `sseStreamBroken_reconnects`(EC)| fixture 关闭 SSE stream → SSE reader 收到 null | transition DISCONNECTED + scheduleReconnect 触发 |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=SseMcpServerConnectionReconnectTest` 4 case 全过
- AC-021c-4 + EC-021c-4

---

## T-14 — `SseMcpServerConnectionCloseTest` L2 2 case + `CallToolNotConnectedTest` EC 1 case + `StartErrorTest` EC 1 case

**文件**(3 新建):
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionCloseTest.java`(新, ~100 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionCallToolNotConnectedTest.java`(新, ~60 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcpServerConnectionStartErrorTest.java`(新, ~80 行)

**测试方法**:

| 测试 | 流程 | 断言 |
|---|---|---|
| `CloseTest.close_transitionsToFailedIdempotent` | close() → close() 第二次 | state==FAILED,无 exception |
| `CloseTest.close_daemonThreadTerminated` | close() 后 SSE reader + hb 线程状态 | `hb.isShutdown()==true && sseReader.isAlive()==false` |
| `CallToolNotConnectedTest.callTool_returnsErrorNotThrows` | start 失败 → state==DISCONNECTED → callTool | `result.isError()==true && 不抛异常` |
| `StartErrorTest.invalidUrl_schedulesReconnect` | cfg.url=`http://localhost:1` → start() | state==RECONNECTING,日志含 "start failed" |
| `StartErrorTest.missingUrl_schedulesReconnect` | cfg.url=null → start() | state==RECONNECTING 或 IllegalArgumentException |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=SseMcpServerConnectionCloseTest,SseMcpServerConnectionCallToolNotConnectedTest,SseMcpServerConnectionStartErrorTest` 5 case 全过
- AC-021c-10 + EC-021c-1 + EC-021c-2 + EC-021c-6

---

## T-15 — `StreamableHttpMcpServerConnectionStartTest` L2 + L3 4 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionStartTest.java`(新, ~220 行)

**测试方法**(用 `TestMcpHttpServer` fixture, hbIntervalMs=100, hbTimeoutMs=500):

| 测试 | 流程 | 断言 |
|---|---|---|
| `start_fakeServer_5stepsReachesConnected` | 启动 fixture → 创建 connection → start() → 等 1s | `state()==CONNECTED && listTools().size()==1` |
| `start_invalidUrl_immediateFailureSchedulesReconnect` | cfg.url=`http://localhost:1` | state `RECONNECTING` |
| `start_missingUrl_schedulesReconnect` | cfg.url=null | state=RECONNECTING |
| `start_alreadyConnected_noop` | 调 start() 第二次 | state 仍 `CONNECTED` |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StreamableHttpMcpServerConnectionStartTest` 4 case 全过
- AC-021c-5

---

## T-16 — `StreamableHttpMcpServerConnectionHeartbeatTest` L2 + L3 3 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionHeartbeatTest.java`(新, ~130 行)

**测试方法**:

| 测试 | 流程 | 断言 |
|---|---|---|
| `probe_healthOk_stateStable` | fixture + hbIntervalMs=100 | state==CONNECTED,`lastHeartbeatAt` 持续更新 |
| `probe_health5xx_transitionsToDisconnected` | fixture `dontReplyHealth=true` | state==DISCONNECTED 然后 RECONNECTING |
| `probe_healthTimeout_transitionsToDisconnected` | fixture `delayMs=2000` + hbTimeoutMs=200 | state==DISCONNECTED |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StreamableHttpMcpServerConnectionHeartbeatTest` 3 case 全过
- AC-021c-6

---

## T-17 — `StreamableHttpMcpServerConnectionReconnectTest` L2 + L3 3 case

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionReconnectTest.java`(新, ~130 行)

**测试方法**:

| 测试 | 流程 | 断言 |
|---|---|---|
| `backoff_sequence_1s_2s_4s_8s_16s_32s_60sCap` | 模拟断网 → 记录每次 reconnect 间隔 | 序列 = [50, 100, 200, 300(cap), 300, 300, 300] |
| `reconnect_unbounded_noMaxAttempts` | 模拟断网 5 次 | 第 5 次 reconnect 仍发生 |
| `reconnect_success_resetsAttempts` | 断网 → reconnect 成功 → 断网 → reconnect | reconnectAttempts 第二次从 1 起算 |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StreamableHttpMcpServerConnectionReconnectTest` 3 case 全过
- AC-021c-7

---

## T-18 — `StreamableHttpMcpServerConnectionCloseTest` L2 2 case + `CallToolNotConnectedTest` EC 1 case + `StartErrorTest` EC 1 case + `closeDuringConnecting`(EC-7)1 case

**文件**(3 新建):
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionCloseTest.java`(新, ~100 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionCallToolNotConnectedTest.java`(新, ~60 行)
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnectionStartErrorTest.java`(新, ~80 行)

**测试方法**:

| 测试 | 流程 | 断言 |
|---|---|---|
| `CloseTest.close_transitionsToFailedIdempotent` | close() → close() 第二次 | state==FAILED,无 exception |
| `CloseTest.close_daemonThreadTerminated` | close() 后 hb 线程状态 | `hb.isShutdown()==true` |
| `CallToolNotConnectedTest.callTool_returnsErrorNotThrows` | start 失败 → callTool | `result.isError()==true` |
| `StartErrorTest.invalidUrl_schedulesReconnect` | cfg.url=`http://localhost:1` → start() | state==RECONNECTING |
| `CloseTest.closeDuringConnecting_succeeds`(EC-7)| start() 进行中(initialize handshake 未完成)→ close() | closing.set(true) + 切 FAILED;start() 后续路径检查 closing flag → return |

**DoD**:
- `mvn -pl lingshu-core test -Dtest=StreamableHttpMcpServerConnectionCloseTest,StreamableHttpMcpServerConnectionCallToolNotConnectedTest,StreamableHttpMcpServerConnectionStartErrorTest` 5 case 全过
- AC-021c-11 + EC-021c-2 + EC-021c-7

---

## T-19 — `McpServerConnectionFactoryTest` L2 6 case(#021a 4 + #021c 2)

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/mcp/McpServerConnectionFactoryTest.java`(修改, +2 case)

**修改**:
- 保留 #021a 4 case:
  - `create_stdio_dispatchReturnsStdioConnection`
  - `create_sse_throwsM01`(**改写**:`create_sse_dispatchReturnsSseConnection`)
  - `create_streamableHttp_throwsM01`(**改写**:`create_streamableHttp_dispatchReturnsStreamableHttpConnection`)
  - `create_null_throwsIllegalStateException`
- 加 2 case:
  - `create_sse_dispatchReturnsSseConnection` — cfg(transport=SSE) → 返回 `SseMcpServerConnection` 实例,`state()==IDLE`
  - `create_streamableHttp_dispatchReturnsStreamableHttpConnection` — cfg(transport=STREAMABLE_HTTP) → 返回 `StreamableHttpMcpServerConnection` 实例,`state()==IDLE`

**DoD**:
- `mvn -pl lingshu-core test -Dtest=McpServerConnectionFactoryTest` 6 case 全过
- AC-021c-8

---

## T-20 — R-13 dep-tree 自查 + JDK 8 兼容 grep 自查 + 全模块编译 + 全量测试

**执行命令**(对齐 SOP §3.4):

```bash
# R-13 dep-tree pre 截图
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-pre.txt

# (实施期间 T-01 ~ T-19 后)
mvn -pl lingshu-core compile  # 编译过
mvn -pl lingshu-core test     # 全量测试过
mvn install -N               # 父 POM 校验过

# R-13 dep-tree post 截图
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-post.txt

# diff(应仅时间戳差异)
diff /tmp/dep-tree-pre.txt /tmp/dep-tree-post.txt
# 预期输出:仅时间戳行差异(8 行左右),0 binary delta

# JDK 8 兼容 grep 自查
grep -rE "\bvar\b|\bList\.of\b|\bsealed\b|record\s+\w+|Pattern matching|Text blocks" \
  lingshu-core/src/main/java/ai/lingshu/core/mcp/ \
  lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpErrorCodes.java \
  lingshu-core/src/test/java/ai/lingshu/core/mcp/ \
  2>&1
# 预期输出:空(无 JDK 9+ / 14+ / 15+ 特性)
```

**DoD**:
- `mvn -pl lingshu-core test` 全部 case 通过(L1 13 + L2 8 + L2+L3 18 + EC 3 = **42 case**)
- `mvn install -N` 通过
- `diff /tmp/dep-tree-pre.txt /tmp/dep-tree-post.txt` 仅时间戳差异
- JDK 8 grep 无命中

---

## T-21 — 文档同步(PR body 必备 + README + ROADMAP + dsh §15.10 + CLAUDE.md)

**修改**(PR body 内,**不**在 code commit):

- PR title:`feat(core): Story #021c mcp-sse-and-http-transport — SseMcpServerConnection + StreamableHttpMcpServerConnection + factory dispatch 全实现 + LINGS-M03`
- PR body 模板:
  - **Summary**:5 句话
  - **Why**:dsh §6.5 (2.1) + #021a/#021b 前置
  - **What**:5 文件 / 1 测试目录 / 1 新 ErrorCode
  - **AC-021c-NN 验证**:贴 `mvn -pl lingshu-core test` 输出
  - **R-13 dependency:tree 自查**:贴 pre/post diff(见 T-20)
  - **Test count**:42 case
  - **JDK 8 兼容**:grep 自查结果

**commits 后文档同步**(独立 `docs(sync)` commit):

- `README.md` 加 "📡 MCP server 3 类 transport (stdio / SSE / streamable HTTP) 已上线" 核心特性 bullet + Story #021c 完整 retrospective
- `specs/ROADMAP.md` 「✅ 已完成」加 #021c 行 + 「🟡 待补」#021c 划掉 + 「🎯 实施节奏建议」next = #022 / #023
- `dsh_agent_design.md` §15.9 加 `LINGS-M03 MCP_HTTP_SSE_FAILED` 子码段
- `CLAUDE.md` Last updated 2026-09-23 + Version 1.3.34(Story #021c 同步条目)

**DoD**:
- PR body 含 R-13 自查节 + AC 验证输出
- docs(sync) commit 在 PR merge 后单独提交
- dsh §15.9 + README + ROADMAP + CLAUDE.md 4 件齐

---

## 任务依赖图

```
T-01 (McpHttpSupport utility)
   ↓
T-02 (SseMcpServerConnection 完整实现)
   ↓
T-03 (StreamableHttpMcpServerConnection 完整实现)
   ↓
T-04 (McpServerConnectionFactory 改写)
   ↓
T-05 (McpErrorCodes 扩 LINGS_M03)
   ↓
T-06 (TestMcpHttpServer fixture)
   ↓
T-07 (TestMcpSseServer fixture)
   ↓
T-08 (McpErrorCodesTest L1)
   ↓
T-09 (McpHttpSupportTest L1+L2)
   ↓
T-10 (Sse StartTest L2+L3)
   ↓
T-11 (Sse SseTestListenerTest L2+L3)
   ↓
T-12 (Sse HeartbeatTest L2+L3)
   ↓
T-13 (Sse ReconnectTest L2+L3)
   ↓
T-14 (Sse Close + CallToolNotConnected + StartError 测试)
   ↓
T-15 (StreamableHttp StartTest)
   ↓
T-16 (StreamableHttp HeartbeatTest)
   ↓
T-17 (StreamableHttp ReconnectTest)
   ↓
T-18 (StreamableHttp Close + CallToolNotConnected + StartError + closeDuringConnecting 测试)
   ↓
T-19 (McpServerConnectionFactoryTest 改写 + 2 case)
   ↓
T-20 (R-13 自查 + 全量测试)
   ↓
T-21 (文档同步)
```

**实施顺序**:T-01 → T-02 → T-03 → T-04 → T-05(编译过)→ T-06 → T-07(测试 fixture)→ T-08 → T-09 → T-10 → T-11 → T-12 → T-13 → T-14 → T-15 → T-16 → T-17 → T-18 → T-19(测试过)→ T-20(R-13 自查 + 全量)→ T-21(文档同步)

---

## 测试 case 计数(checklist)

| Task | 测试文件 | case 数 |
|---|---|---:|
| T-08 | McpErrorCodesTest | 3 |
| T-09 | McpHttpSupportTest | 4 |
| T-10 | SseMcpServerConnectionStartTest | 5 |
| T-11 | SseMcpServerConnectionSseTestListenerTest | 3 |
| T-12 | SseMcpServerConnectionHeartbeatTest | 4 |
| T-13 | SseMcpServerConnectionReconnectTest | 4 |
| T-14 | SseMcpServerConnectionCloseTest | 2 |
| T-14 | SseMcpServerConnectionCallToolNotConnectedTest | 1 |
| T-14 | SseMcpServerConnectionStartErrorTest | 2 |
| T-15 | StreamableHttpMcpServerConnectionStartTest | 4 |
| T-16 | StreamableHttpMcpServerConnectionHeartbeatTest | 3 |
| T-17 | StreamableHttpMcpServerConnectionReconnectTest | 3 |
| T-18 | StreamableHttpMcpServerConnectionCloseTest | 2 |
| T-18 | StreamableHttpMcpServerConnectionCallToolNotConnectedTest | 1 |
| T-18 | StreamableHttpMcpServerConnectionStartErrorTest | 1 |
| T-18 | StreamableHttpMcpServerConnectionCloseTest(EC-7 closeDuringConnecting)| 1 |
| T-19 | McpServerConnectionFactoryTest(#021a 4 + #021c 2)| 6 |
| **T-01** | **(生产代码,被 T-09 测试覆盖)** | 0 |
| **T-02** | **(生产代码,被 T-10 ~ T-14 测试覆盖)** | 0 |
| **T-03** | **(生产代码,被 T-15 ~ T-18 测试覆盖)** | 0 |
| **T-04** | **(生产代码,被 T-19 测试覆盖)** | 0 |
| **T-05** | **(生产代码,被 T-08 测试覆盖)** | 0 |
| **T-06** | **(fixture,无独立 test,被 T-10 ~ T-18 使用)** | 0 |
| **T-07** | **(fixture,无独立 test,被 T-11, T-13 使用)** | 0 |
| **Total** | | **49** |

**实际 case 数 49**(与 spec.md 估计 ≥42 case 略多;**最小** 42,**目标** 49,可加 case 见各 T-NN「可能扩展」段)

---

## 关联文档

- `specs/021c-mcp-sse-and-http-transport/spec.md`(WHY / WHO / WHAT / AC / EC)
- `specs/021c-mcp-sse-and-http-transport/plan.md`(HOW / 接口契约 / 文件布局 / 测试策略)
- dsh v1.5.37 §6.5 (2.1) L4821-4835(`SseMcpServerConnection` 差异说明段)
- dsh v1.5.37 §6.5 (2.1) L4601-4614(`McpServerConnectionFactory` switch 字面落地)
- dsh v1.5.37 §6.5 (2.1) L4837-4871(`application.yml` 配置 + 启动日志样例)
- dsh v1.5.37 §15.9 L7211-7220(ErrorCode 编码约定)
- dsh v1.5.37 §4.10.1 硬规则 2(ToolExecutor 5 步流水线)
- dsh v1.5.37 §14.15.7(7 层测试金字塔)
- dsh §0 L39 —— JDK 8 编译目标硬约束(决定 `HttpURLConnection` 而非 `java.net.http.HttpClient`)
- Story #021a mcp-stdio-transport(`McpServerConnection` interface + `ConnectionState` 6 态 + `StdioMcpServerConnection` 完整实现)
- Story #021b mcp-tool-adapter(`McpTransport` 协调者 + `McpToolAdapter`)
- Story #009c a2a-httpjsonrpc-and-remote-tool(`com.sun.net.httpserver.HttpServer` 测试 fixture 样板)
- Story #020a skill-foundation(`Skill` interface + `ToolRegistry` 双索引模式)
- Story #020c cli-skill-trigger(`--list-skills` banner 模式)
- ROADMAP §6.5 (2.1) 提议 Story 列表第 6 行(`#021c mcp-sse-and-http-transport`)
- SOP v1.21 §3.2 + §3.4(R-13 自查链路)