# Data Model: Story #009c a2a-httpjsonrpc-and-remote-tool

**Story**: #009c
**Branch**: `story-009c-a2a-httpjsonrpc-and-remote-tool`
**Created**: 2026-09-22

---

## 1. 新增类型(6)

### DM-01 `HttpJsonRpcA2aTransport` — HTTP+JSON-RPC 2.0 客户端

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java`
**Visibility**: `public class`
**Interface**: `implements A2aTransport`

**字段**:

| 字段 | 类型 | 可见性 | final? | 说明 |
|---|---|---|---|---|
| `httpBaseUrl` | `String` | private | yes | 远程 agent base URL(如 `http://localhost:8080`),trim 末尾 `/` 防双 slash |
| `json` | `ObjectMapper` | private | yes | Jackson JSON 解析器 |
| `cardCache` | `AgentCardCache` | private | yes | #009a 复用,TTL = `cfg.getA2a().getCardTtl()` |
| `callTimeout` | `Duration` | private | yes | 单次 HTTP call timeout,默认 30s |
| `http` | `HttpClient` | private | yes | JDK 17 内置 `java.net.http.HttpClient`,构造时 `newBuilder().connectTimeout(callTimeout).build()` |

**构造器**:
```java
public HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json,
                                AgentCardCache cardCache, Duration callTimeout) {
    if (httpBaseUrl == null || httpBaseUrl.isEmpty()) throw new IllegalArgumentException("httpBaseUrl must not be null/empty");
    if (json == null) throw new IllegalArgumentException("json must not be null");
    if (cardCache == null) throw new IllegalArgumentException("cardCache must not be null");
    if (callTimeout == null) throw new IllegalArgumentException("callTimeout must not be null");
    this.httpBaseUrl = httpBaseUrl.endsWith("/") ? httpBaseUrl.substring(0, httpBaseUrl.length() - 1) : httpBaseUrl;
    this.json = json;
    this.cardCache = cardCache;
    this.callTimeout = callTimeout;
    this.http = HttpClient.newBuilder().connectTimeout(callTimeout).build();
}
```

**方法**(5 个,完整实现 A2aTransport):

| 方法 | 签名 | 行为 |
|---|---|---|
| `fetchCard` | `public Map<String, Object> fetchCard(String agentName)` | 1) `cardCache.get(agentName)` 命中返 Map;2) miss → HTTP GET `<base>/.well-known/agent.json`,parse `Map`,put 进 cache;3) HTTP 5xx / IOException → `cardCache.putNegative(agentName)` + 抛 `HttpJsonRpcException` |
| `submit` | `public ToolResult submit(String agentName, String skill, String inputJson)` | 1) 构造 JSON-RPC 2.0 body `{"jsonrpc":"2.0","id":<UUID>,"method":"message/send","params":{"agentName":"<X>","skill":"<Y>","inputJson":"<Z>"}}`;2) HTTP POST `<base>/rpc`;3) parse `result` → `ToolResult.toolSuccess(json)` 或 `ToolResult.toolError(json)`;4) `error` 非空 → 抛 `HttpJsonRpcException` |
| `get` | `public ToolResult get(String taskId)` | 1) JSON-RPC `method=tasks/get params={id:<taskId>}`;2) parse `result.status` (COMPLETED/FAILED/CANCELED/RUNNING/PENDING);3) FAILED/CANCELED → `toolError`;4) 其他 → `toolSuccess(json)` |
| `cancel` | `public boolean cancel(String taskId)` | 1) JSON-RPC `method=tasks/cancel params={id:<taskId>}`;2) parse `result.acknowledged` boolean;3) HTTP 5xx → `false`;4) `error` → `false` |
| `subscribe` | `public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent)` | 1) polling:每 1s 调一次 `get(taskId)`;2) terminal state (COMPLETED/FAILED/CANCELED) → `onEvent.accept(map)` + return;3) `Thread.sleep(1000L)` 中断 → `Thread.currentThread().interrupt()` + return |

**线程安全**:所有字段 `final`,`HttpClient` JDK 11+ thread-safe,`AgentCardCache` thread-safe。

**Javadoc 摘要**:

> dsh §5.6.3.1 L3018-3130 `HttpJsonRpcA2aTransport` stub 完整 5 方法实现 —— JSON-RPC 2.0 over HTTPS,JDK 17 内置 `java.net.http.HttpClient` + Jackson `ObjectMapper`,**0 额外依赖**(R-13 mitigation (d) 强度最弱);`fetchCard` 命中 `AgentCardCache` 免 HTTP,miss 走 `/.well-known/agent.json`;`submit` / `get` / `cancel` / `subscribe` 走 `POST /rpc` 端点;`subscribe` polling 占位(JDK 17 HttpClient 不内置 SSE);**错误统一抛** `HttpJsonRpcException extends LingshuException`(LINGS-S08 A2A_HTTP_RPC_FAILED)。

---

### DM-02 `HttpJsonRpcException` — LINGS-S08 子码异常(nested class)

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java`(nested class,同 `InProcessA2aRegistryEmptyException` 模式)
**Visibility**: `public static final class extends RuntimeException`

**字段**:

| 字段 | 类型 | 可见性 | final? | 说明 |
|---|---|---|---|---|
| `errorCode` | `String` | private | yes | 固定 `"LINGS-S08"` |
| `reason` | `String` | private | yes | 固定 `"A2A_HTTP_RPC_FAILED"` |

**构造器**:
```java
public HttpJsonRpcException(String message, Throwable cause) {
    super(message, cause);
    this.errorCode = "LINGS-S08";
    this.reason = "A2A_HTTP_RPC_FAILED";
}
```

**getter**:
```java
public String getErrorCode() { return errorCode; }
public String getReason() { return reason; }
```

**Javadoc 摘要**:

> 5 方法 HTTP / JSON-RPC 失败统一抛此异常 —— **域细分** 与 #009b `InProcessA2aRegistryEmptyException`(`LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`)共用 `LINGS-S08` 同号,但通过 `getReason()="A2A_HTTP_RPC_FAILED"` 区分(§15 错误码域细分约定);`getMessage()` 返回 `[LINGS-S08 A2A_HTTP_RPC_FAILED] <message>`(对齐 `LingsA2aServerException.getMessage()` 格式)。

---

### DM-03 `HttpJsonRpcA2aTransportProvider` — Provider 工厂

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProvider.java`
**Visibility**: `public class`
**Annotation**: `@Component`
**Interface**: `implements Providers.A2aTransportProvider`

**字段**: 无状态(`static final NAME = "http-jsonrpc-1.0.0"`)

**方法**(4 个):

| 方法 | 签名 | 返回值 |
|---|---|---|
| `name` | `public String name()` | `"http-jsonrpc-1.0.0"`(**禁止**与 `"grpc-1.0.0"` / `"in-process-1.0.0"` 冲突)|
| `priority` | `public int priority()` | `10` |
| `version` | `public String version()` | `"1.0.0"` |
| `create` | `public A2aTransport create(AgentConfig cfg)` | `new HttpJsonRpcA2aTransport(resolveHttpBaseUrl(cfg), new ObjectMapper(), new AgentCardCache(resolveCardTtl(cfg)), resolveCallTimeout(cfg))` |

**私有方法**:

| 方法 | 签名 | 说明 |
|---|---|---|
| `resolveHttpBaseUrl` | `private String resolveHttpBaseUrl(AgentConfig cfg)` | 读 `cfg.getA2a().getHttpBaseUrl()`,null/empty → fallback `"http://localhost:8080"`(try-catch `NoSuchMethodError` 兼容 pre-#009c `AgentConfig.A2a`) |
| `resolveCardTtl` | `private Duration resolveCardTtl(AgentConfig cfg)` | 读 `cfg.getA2a().getCardTtl()`,null → `Duration.ofMinutes(5)`(同 #009a / #009b) |
| `resolveCallTimeout` | `private Duration resolveCallTimeout(AgentConfig cfg)` | 读 `cfg.getA2a().getCallTimeout()`,null → `Duration.ofSeconds(30)` |

---

### DM-04 `HttpJsonRpcA2aTransportAutoConfiguration` — Spring Boot SPI 注册(双 Bean)

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`
**Visibility**: `public class`
**Annotation**: `@AutoConfiguration`

**方法**(2 个 `@Bean`):

| 方法 | 签名 | 返回值 |
|---|---|---|
| `httpJsonRpcA2aTransportProvider` | `@Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0") public Providers.A2aTransportProvider` | `new HttpJsonRpcA2aTransportProvider()` |
| `remoteAgentTool` | `@Bean(name = "remoteAgentTool") public Tool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json)` | `new RemoteAgentTool(router.resolve(cfg.getA2aTransport(), cfg), json)` |

**SPI 注册**:`lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 追加第三行:
```
ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration           ← #009a 已落地
ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration     ← #009b 已落地
ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration    ← #009c 追加
```

**注意**:`RemoteAgentToolAutoConfiguration` **不**单独建文件 —— 合并到 `HttpJsonRpcA2aTransportAutoConfiguration` 内 `@Bean Tool remoteAgentTool`(理由:核心新增文件数 ≤ 5 边界对齐,详 plan.md §2 备选)

---

### DM-05 `RemoteAgentTool` — Remote Agent Tool 适配

**Package**: `ai.lingshu.a2a.client`
**File**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java`
**Visibility**: `public class`
**Interface**: `implements Tool`

**字段**:

| 字段 | 类型 | 可见性 | final? | 说明 |
|---|---|---|---|---|
| `NAME` | `static final String` | private | yes | 固定 `"remote_agent"`(LLM 看到的 tool name) |
| `transport` | `final A2aTransport` | private | yes | 由 `HttpJsonRpcA2aTransportAutoConfiguration.@Bean` 注入 |
| `json` | `final ObjectMapper` | private | yes | Jackson |

**构造器**:
```java
public RemoteAgentTool(A2aTransport transport, ObjectMapper json) {
    if (transport == null) throw new IllegalArgumentException("transport must not be null");
    if (json == null) throw new IllegalArgumentException("json must not be null");
    this.transport = transport;
    this.json = json;
}
```

**方法**(4 个,实现 Tool):

| 方法 | 签名 | 行为 |
|---|---|---|
| `name` | `public String name()` | `return NAME`(`"remote_agent"` 固定字符串) |
| `description` | `public String description()` | `return "Invoke a skill on a remote A2A agent. Input: {agentName, skill, input}."` |
| `inputSchema` | `public JsonNode inputSchema()` | 固定 schema:`{"type":"object","properties":{"agentName":{"type":"string","description":"Target remote agent Identity.name"},"skill":{"type":"string","description":"Skill id to invoke"},"input":{"type":"object","description":"JSON args matching the skill's input schema"}},"required":["agentName","skill","input"]}` |
| `execute` | `public ToolResult execute(ToolCall call, ToolExecutionContext ctx)` | 1) `JsonNode input = call.getInput()`;2) `String agentName = input.get("agentName").asText()`;3) `String skill = input.get("skill").asText()`;4) `String inputJson = json.writeValueAsString(input.get("input"))`;5) try `transport.submit(agentName, skill, inputJson)` → return `ToolResult`;6) catch `HttpJsonRpcException` → `ToolResult.toolError("Remote agent call failed: " + e.getMessage())` |

**Javadoc 摘要**:

> 把 remote agent 接入 `ToolRegistry` —— LLM 在 ReAct Loop 中看到单 tool `remote_agent`,input JSON 指定 `agentName` / `skill` / `input`,内部转发到 `A2aTransport.submit`(走 #009a / #009b / #009c 任一 Provider)。**注意**:`call_<agentName>.<skill>` 命名约定仅作人类可读参考,实际 `Tool.name()="remote_agent"` 固定 —— LLM 通过 input JSON 选 agent + skill。`#009d RemoteAgentSchemaBuilder` 落地后会接管 dynamic schema 生成(详 plan.md §5.2)。

---

## 2. 新增 ErrorCode(1 个,子码)

### EC-01 `LINGS-S08 A2A_HTTP_RPC_FAILED`

**域**:S = Slot
**编号**:08(#009a LINGS-S07 后续,#009b 用同号作 `A2A_INPROCESS_REGISTRY_EMPTY` 子码)
**触发条件**:`HttpJsonRpcA2aTransport` 5 方法任一抛 `HttpJsonRpcException` 时携带

**message 模板**:
```
fetchCard failed for 'http://localhost:8080': HTTP 503: Service Unavailable
或
submit failed: TaskNotFound - agent 'alice-coding' has no skill 'echo'
或
cancel failed: HTTP 500
```

**Actionable 建议**:
```
check remote agent's logs / verify agentName and skill match the AgentCard / check network connectivity
```

**代码位置**:`HttpJsonRpcA2aTransport.java` 内 nested class `HttpJsonRpcException`(同 #009b `InProcessA2aRegistryEmptyException` 模式)

**LINGS-S08 域细分** —— #009b vs #009c:

| Story | 子码 | nested exception | 触发场景 |
|---|---|---|---|
| #009b | `A2A_INPROCESS_REGISTRY_EMPTY` | `InProcessA2aRegistryEmptyException` | 同 JVM registry miss,无对应 agentName |
| #009c | `A2A_HTTP_RPC_FAILED` | `HttpJsonRpcException` | 跨 JVM HTTP 5xx / IOException / JSON-RPC error / timeout |

两个子码共用 `LINGS-S08` 同号,通过 `getReason()` 字符串区分,符合 dsh §15「域细分」原则。

---

## 3. 修改类型(3 个)

### MD-01 `AgentConfig.A2a` — 加 2 字段 + `defaults()` 改写

**Package**: `ai.lingshu.core.runtime`
**File**: `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`
**变更**: `A2a` 内嵌类加 2 字段,`defaults()` 改写

**字段变更**(由 #009a 4 字段 → #009c 6 字段):

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `host` | `String` | `"0.0.0.0"` | (继承 #009) |
| `port` | `Integer` | `8080` | (继承 #009) |
| `grpcTarget` | `String` | `"localhost:50051"` | (继承 #009a) |
| `cardTtl` | `Duration` | `Duration.ofMinutes(5)` | (继承 #009a) |
| **`httpBaseUrl`** | `String` | `"http://localhost:8080"` | **🆕 #009c** |
| **`callTimeout`** | `Duration` | `Duration.ofSeconds(30)` | **🆕 #009c** |

**`defaults()` 改写**:
```java
public static A2a defaults() {
    return new A2a("0.0.0.0", 8080, "localhost:50051", Duration.ofMinutes(5),
                   "http://localhost:8080", Duration.ofSeconds(30));   // 🆕 httpBaseUrl + callTimeout
}
```

**Javadoc 摘要**:

> #009c 扩展:加 `httpBaseUrl`(默认 `http://localhost:8080`,与 A2aServer host=0.0.0.0 + port=8080 对齐)+ `callTimeout`(默认 30s)。`HttpJsonRpcA2aTransportProvider.create(cfg)` 读这两字段;`try-catch NoSuchMethodError` 兼容 pre-#009c `AgentConfig.A2a`。

---

### MD-02 `A2aServer` — `POST /rpc` 升级为 JSON-RPC 2.0 dispatcher

**Package**: `ai.lingshu.a2a.server`
**File**: `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`
**变更**: 把当前 `RpcPlaceholderHandler`(L268-279,返 501)替换为 `RpcDispatcherHandler`

**新 private class `RpcDispatcherHandler`**:
```java
/**
 * Story #009c — minimal JSON-RPC 2.0 dispatcher for POST /rpc.
 *
 * <p>Receives {@code message/send} / {@code tasks/get} / {@code tasks/cancel} 3 methods
 * (method not recognized → JSON-RPC error code -32601 Method not found).</p>
 *
 * <p>Returns {@code {jsonrpc:"2.0", id:<id>, result:{...}}} on success or
 * {@code {jsonrpc:"2.0", id:<id>, error:{code:-32601, message:"..."}}} on error.
 * HTTP status is always 200 (JSON-RPC 2.0 spec — errors are in body).</p>
 *
 * <p><b>Scope-limited</b>: returns echo + status response (no real skill dispatch).
 * Full skill dispatch is deferred to a future Server RPC dispatch Story.</p>
 */
private final class RpcDispatcherHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange ex) throws IOException {
        // 1) read body
        byte[] reqBytes = ex.getRequestBody().readAllBytes();
        // 2) parse JSON-RPC envelope
        JsonNode root;
        try {
            root = LocalAgentCardGenerator.MAPPER.readTree(reqBytes);
        } catch (JsonProcessingException jpe) {
            sendJsonRpcError(ex, null, -32700, "Parse error: " + jpe.getMessage());
            return;
        }
        String id = root.path("id").asText(null);
        String method = root.path("method").asText("");
        JsonNode params = root.path("params");
        // 3) dispatch
        ObjectNode result = LocalAgentCardGenerator.MAPPER.createObjectNode();
        switch (method) {
            case "message/send":
                result.put("status", "COMPLETED");
                result.put("taskId", UUID.randomUUID().toString());
                ObjectNode echo = LocalAgentCardGenerator.MAPPER.createObjectNode();
                echo.set("echo", params.path("input"));
                result.put("resultJson", LocalAgentCardGenerator.MAPPER.writeValueAsString(echo));
                break;
            case "tasks/get":
                result.put("status", "COMPLETED");
                result.put("taskId", params.path("id").asText());
                result.put("resultJson", "{}");
                break;
            case "tasks/cancel":
                result.put("acknowledged", true);
                break;
            default:
                sendJsonRpcError(ex, id, -32601, "Method not found: " + method);
                return;
        }
        // 4) send response
        ObjectNode resp = LocalAgentCardGenerator.MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.put("id", id);
        resp.set("result", result);
        sendJsonRpcResponse(ex, resp);
    }

    private void sendJsonRpcResponse(HttpExchange ex, JsonNode body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    private void sendJsonRpcError(HttpExchange ex, String id, int code, String message) throws IOException {
        ObjectNode resp = LocalAgentCardGenerator.MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) resp.put("id", id);
        ObjectNode error = LocalAgentCardGenerator.MAPPER.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        resp.set("error", error);
        sendJsonRpcResponse(ex, resp);
    }
}
```

**注册变更**: `server.createContext("/rpc", new RpcDispatcherHandler())`(L124)

---

### MD-03 `META-INF/spring/...imports` — 追加第三行

**Path**: `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
**变更**: 追加第三行
```
ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration           ← #009a 已落地
ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration     ← #009b 已落地
ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration    ← #009c 追加
```

---

## 4. 复用类型(#009a / #009b 已落地,**不**改)

### RT-01 `A2aTransport` interface
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/slot/A2aTransport.java`
- 5 方法契约不变

### RT-02 `A2aTransportRouter`
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/impl/router/A2aTransportRouter.java`
- extends `SlotRouter<Providers.A2aTransportProvider, A2aTransport>`
- #009c 的 `HttpJsonRpcA2aTransportProvider` 自动被 `List<Providers.A2aTransportProvider>` 注入,**不**改 Router 自身

### RT-03 `AgentCardCache`
- 路径:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/AgentCardCache.java`
- 5 方法 `get` / `put` / `putNegative` / `invalidate` / `stats`
- **复用**:`HttpJsonRpcA2aTransport.fetchCard` 命中缓存免 HTTP

### RT-04 `AgentConfig.A2a`(基础 4 字段)
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`
- 字段:`host` + `port`(#009) + `grpcTarget`(#009a) + `cardTtl`(#009a)
- **复用**:本 Story MD-01 **扩展**为 6 字段

### RT-05 `InProcessA2aRegistry`
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/a2a/client/InProcessA2aRegistry.java`
- 单例 + 7 方法
- **复用**:本 Story 单元测试用作 mock 通路(`InProcessA2aTransport.fetchCard` mock)

### RT-06 `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration`
- 路径:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/Grpc*.java`
- #009a 已落地,**复用** by coexistence(`A2aTransportRouter` 注入 3 Provider)

### RT-07 `InProcessA2aTransport` / `InProcessA2aTransportProvider` / `InProcessA2aTransportAutoConfiguration`
- 路径:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcess*.java`
- #009b 已落地,**复用** by coexistence

### RT-08 `LocalAgentCardGenerator` / `A2aServer`(主流程)
- `generate()` / `toJson()` / `toMap()` 不变
- `start()` / `stop()` 主流程不变(只**替换** 1 个 private handler)
- `LocalAgentCardGenerator.MAPPER` 复用(本 Story 新增 `RpcDispatcherHandler` 用 `LocalAgentCardGenerator.MAPPER` 而非新建 ObjectMapper,避免每 handler 1 instance)

### RT-09 `Tool` interface + `ToolExecutor` + `ToolRegistry`
- 路径:`lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java`
- `RemoteAgentTool implements Tool` 接入 `ToolRegistry`(由 Spring `@Bean Tool` 自动扫)
- `ToolExecutor.dispatch()` 5 步流水线不变

---

## 5. 删除 / 替换

### RM-01 `RpcPlaceholderHandler`(被 RpcDispatcherHandler 替换)
- 路径:`lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java` L268-279
- 删除 `private static final class RpcPlaceholderHandler implements HttpHandler`(501 placeholder)
- 新建 `private final class RpcDispatcherHandler implements HttpHandler`(JSON-RPC 2.0 dispatcher)
- 影响范围:仅 `A2aServer.start()` 内 `server.createContext("/rpc", new RpcDispatcherHandler())` 1 行修改

---

## 6. 数据流图

```
[LLM] ToolCall(name="remote_agent", input={agentName:"alice", skill:"echo", input:{msg:"hi"}})
  ↓
[ToolExecutor.dispatch] → 5 步流水线
  ├─ PermissionPolicy.check() → allowed
  ├─ ToolRegistry.lookup("remote_agent") → RemoteAgentTool
  ├─ TimeoutWrap
  ├─ SandboxApply
  ├─ RemoteAgentTool.execute(call, ctx)
  │     ↓
  │   parse call.getInput() → {agentName, skill, input}
  │     ↓
  │   transport.submit("alice", "echo", "{\"msg\":\"hi\"}")
  │     ↓                       ↑ A2aTransportRouter.resolve("http-jsonrpc-1.0.0", cfg) at startup
  │   HttpJsonRpcA2aTransport.submit
  │     ↓
  │   HTTP POST http://localhost:8080/rpc
  │   body: {"jsonrpc":"2.0","id":"<uuid>","method":"message/send","params":{...}}
  │     ↓
  │   A2aServer.RpcDispatcherHandler
  │     ↓ echo response
  │   {"jsonrpc":"2.0","id":"<uuid>","result":{"status":"COMPLETED","taskId":"<uuid>","resultJson":"{\"echo\":{...}}"}}
  │     ↓
  │   parse result → ToolResult.toolSuccess(json)
  │     ↓
  │   return ToolResult
  └─ Checkpoint

[ReAct Loop] receives ToolResult → next LLM message with tool result
```
