# Contracts: Story #009c a2a-httpjsonrpc-and-remote-tool

**Story**: #009c
**Branch**: `story-009c-a2a-httpjsonrpc-and-remote-tool`
**Created**: 2026-09-22

---

## 1. Contract Scope(契约范围)

本 Story 涉及 4 类契约:

| # | 契约 ID | 标题 | 影响范围 | 状态 |
|---|---|---|---|---|
| 1 | `lingshu.contract.http-jsonrpc-a2a-transport.v1` | `HttpJsonRpcA2aTransport` 5 方法契约 | `lingshu-a2a-client` 模块 | 🆕 新增 |
| 2 | `lingshu.contract.remote-agent-tool.v1` | `RemoteAgentTool` Tool 契约 | `lingshu-a2a-client` 模块 | 🆕 新增 |
| 3 | `lingshu.contract.a2a-server-jsonrpc-dispatcher.v1` | `A2aServer` `POST /rpc` JSON-RPC 2.0 契约 | `lingshu-a2a-server` 模块 | 🆕 新增 |
| 4 | `lingshu.contract.error-code-s08-subdivision.v1` | LINGS-S08 域细分(与 #009b 共用同号) | `lingshu-core` 模块 | 🆕 新增(子码细分) |

**接口契约影响**(dsh §5.6.3 + §5.6.1 + §4.6):
- `A2aTransport` 5 方法契约**不变**
- `Tool` 4 方法契约**不变**
- `A2aServer` `POST /rpc` 端点协议**升级**:501 placeholder → JSON-RPC 2.0 dispatcher(echo + status response)

---

## 2. Contract #1: `lingshu.contract.http-jsonrpc-a2a-transport.v1`

### 2.1 契约 ID 与命名空间

`lingshu.contract.http-jsonrpc-a2a-transport.v1` —— 版本号 `v1` 与 `A2aTransport` 接口 `@ContractVersionRef` 锁定的 `1.0.0` 对齐(MAJOR 一致保证兼容)。

### 2.2 契约内容(5 方法)

#### 2.2.1 `fetchCard(agentName: String) → Map<String, Object>`

**协议契约**(HTTP GET `/agentName` 不存在 —— A2A v1.0 spec §2.1 固定路径):
- 请求:`GET <httpBaseUrl>/.well-known/agent.json`(agentName 不在 URL 里 —— `httpBaseUrl` 已隐含 agent identity)
- 响应:200 + JSON `{"name":"<X>","description":"<Y>","version":"<Z>","skills":[...]}` + `Cache-Control: public, max-age=60`
- 错误:5xx / IOException / timeout → 抛 `HttpJsonRpcException("LINGS-S08 A2A_HTTP_RPC_FAILED", ...)`

**实现契约**:
1. 调 `cardCache.get(agentName)` 命中免 HTTP(并发读 `ConcurrentHashMap`)
2. miss 走 HTTP GET(详见上)
3. 成功 → `json.readValue(body, Map.class)` + `cardCache.put(agentName, map)`
4. 失败 → `cardCache.putNegative(agentName)` + 抛 `HttpJsonRpcException`

#### 2.2.2 `submit(agentName, skill, inputJson) → ToolResult`

**协议契约**(JSON-RPC 2.0 over HTTP POST `/rpc`):
- 请求 body:
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<UUID>",
    "method": "message/send",
    "params": {
      "agentName": "<X>",
      "skill": "<Y>",
      "inputJson": "<Z>"
    }
  }
  ```
- 响应 body(成功):
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<UUID>",
    "result": {
      "status": "COMPLETED",
      "taskId": "<T>",
      "resultJson": "<R>"
    }
  }
  ```
- 响应 body(失败):
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<UUID>",
    "error": {
      "code": -32601,
      "message": "Method not found"
    }
  }
  ```

**实现契约**:
1. `success` → `ToolResult.toolSuccess(json.writeValueAsString(result.resultJson))`
2. `error` → 抛 `HttpJsonRpcException("LINGS-S08 A2A_HTTP_RPC_FAILED", message="submit failed: " + error.message)`
3. HTTP 5xx → 抛 `HttpJsonRpcException("LINGS-S08 A2A_HTTP_RPC_FAILED", message="submit HTTP <code>: <body>")`

#### 2.2.3 `get(taskId) → ToolResult`

**协议契约**(JSON-RPC 2.0):
- 请求 body:
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<UUID>",
    "method": "tasks/get",
    "params": { "id": "<taskId>" }
  }
  ```
- 响应 body:同 2.2.2,但 `method=tasks/get`

**实现契约**:
1. parse `result.status`:
   - `"FAILED"` / `"CANCELED"` → `ToolResult.toolError(<result.error || status>)`
   - `"COMPLETED"` / `"RUNNING"` / `"PENDING"` → `ToolResult.toolSuccess(<result.resultJson || "{}">)`

#### 2.2.4 `cancel(taskId) → boolean`

**协议契约**(JSON-RPC 2.0):
- 请求 body:
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<UUID>",
    "method": "tasks/cancel",
    "params": { "id": "<taskId>" }
  }
  ```
- 响应 body:
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<UUID>",
    "result": { "acknowledged": <boolean> }
  }
  ```

**实现契约**:
1. success → return `result.acknowledged.booleanValue()`
2. `error` → return `false`
3. HTTP 5xx → return `false`

#### 2.2.5 `subscribe(taskId, onEvent) → void`

**协议契约**(polling 实现 —— dsh §5.6.3.1 L3076-3092 stub):
- 每 1s 调一次 `get(taskId)`,直到 terminal state
- 每次 poll 投递 `Map<String, Object>` 给 `onEvent` consumer

**实现契约**:
1. 循环:`Map<String, Object> event = (Map<String, Object>) json.readValue(json.writeValueAsString(getResult), Map.class); onEvent.accept(event);`
2. 如果 `getResult.status` = `COMPLETED` / `FAILED` / `CANCELED` → return
3. `Thread.sleep(1000L)` 检测 `Thread.interrupted()` → `Thread.currentThread().interrupt()` + return

### 2.3 错误契约

| 触发条件 | 错误码 | message 模板 | hint |
|---|---|---|---|
| HTTP 5xx(503/502/500)| `LINGS-S08 A2A_HTTP_RPC_FAILED` | `<method> HTTP <code>: <body>` | check remote agent's logs |
| HTTP timeout(超过 `callTimeout`)| `LINGS-S08 A2A_HTTP_RPC_FAILED` | `<method> timeout after <duration>: <message>` | check network connectivity |
| HTTP 4xx(404 / 405)| `LINGS-S08 A2A_HTTP_RPC_FAILED` | `<method> HTTP <code>: <body>` | verify <httpBaseUrl> matches the remote agent's bind URL |
| JSON parse error | `LINGS-S08 A2A_HTTP_RPC_FAILED` | `<method> JSON parse failed: <message>` | verify response body is valid JSON-RPC 2.0 |
| JSON-RPC `error` 字段非空 | `LINGS-S08 A2A_HTTP_RPC_FAILED` | `<method> failed: <error.message>` | check remote agent's logs / verify agentName and skill match the AgentCard |

### 2.4 关键不变项

- `A2aTransport` 5 方法契约不变 —— HttpJsonRpcA2aTransport 是 A2aTransport 的具体实现,与 GrpcA2aTransport / InProcessA2aTransport 平级
- `AgentCardCache` 5 方法契约不变
- `HttpClient` JDK 17 内置 thread-safe
- `ObjectMapper` Jackson thread-safe

---

## 3. Contract #2: `lingshu.contract.remote-agent-tool.v1`

### 3.1 契约 ID 与命名空间

`lingshu.contract.remote-agent-tool.v1` —— 版本号 `v1` 与 `Tool` 接口 `@ContractVersionRef` 锁定的 `1.0.0` 对齐。

### 3.2 契约内容(4 方法)

#### 3.2.1 `name() → String`

固定 `"remote_agent"` —— LLM 看到的 tool name。

#### 3.2.2 `description() → String`

`"Invoke a skill on a remote A2A agent. Input: {agentName, skill, input}."` —— 简短,模型能理解。

#### 3.2.3 `inputSchema() → JsonNode`

固定 schema(JSON Schema draft 2020-12):
```json
{
  "type": "object",
  "properties": {
    "agentName": {
      "type": "string",
      "description": "Target remote agent Identity.name"
    },
    "skill": {
      "type": "string",
      "description": "Skill id to invoke"
    },
    "input": {
      "type": "object",
      "description": "JSON args matching the skill's input schema"
    }
  },
  "required": ["agentName", "skill", "input"]
}
```

**注意**:#009d `RemoteAgentSchemaBuilder` 落地后,本契约会被 N tools 替代(`call_<agentName>` 各一 tool,每个 `inputSchema()` 由 `AgentCard.skills[]` 生成);本期是 single-tool 占位实现。

#### 3.2.4 `execute(call, ctx) → ToolResult`

**实现契约**:
1. `JsonNode input = call.getInput()`
2. `String agentName = input.get("agentName").asText()`(null → `IllegalArgumentException` 由 ToolExecutor 捕获转 `ToolResult.toolError`)
3. `String skill = input.get("skill").asText()`
4. `String inputJson = json.writeValueAsString(input.get("input"))`
5. try `transport.submit(agentName, skill, inputJson)` → return result
6. catch `HttpJsonRpcException` → return `ToolResult.toolError("Remote agent call failed: " + e.getMessage())`
7. catch `RuntimeException` → return `ToolResult.toolError("Remote agent call failed: " + e.getMessage())`(兜底)

### 3.3 接入契约

`RemoteAgentToolAutoConfiguration.@Bean public Tool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json)`:
- `transport = router.resolve(cfg.getA2aTransport(), cfg)` —— 读 `cfg.getA2aTransport()` 拿 name(如 `"http-jsonrpc-1.0.0"` / `"grpc-1.0.0"` / `"in-process-1.0.0"`)
- 返 `new RemoteAgentTool(transport, json)`

**为什么 @Bean 而不是 @Component**:直接 `@Component RemoteAgentTool` 会让 Spring 不知道 `transport` 怎么注入(没有 `A2aTransport` 类型 Bean,因为多 Provider 同存时取哪个不定)—— 必须手动 `router.resolve(name, cfg)` 才能拿到 instance。

### 3.4 错误契约

| 触发条件 | ToolResult status | isError | content |
|---|---|---|---|
| transport 抛 `HttpJsonRpcException` | ERROR | true | `"Remote agent call failed: <message>"` |
| transport 抛 `RuntimeException`(其他)| ERROR | true | `"Remote agent call failed: <message>"` |
| `input.get("agentName")` 缺失 / null | ERROR | true | `"agentName is required"` |
| `input.get("skill")` 缺失 / null | ERROR | true | `"skill is required"` |
| transport 返回 SUCCESS | SUCCESS | false | `<result>` |

### 3.5 关键不变项

- `Tool` 4 方法契约不变
- `ToolExecutor.dispatch()` 5 步流水线不变 —— `RemoteAgentTool` 接入后仍走权限检查(默认 `StrictPermissionPolicy` 拒远程工具调用,符合 §4.7 边界)
- `ToolRegistry` 注册路径不变(`@Bean Tool` 自动扫)

---

## 4. Contract #3: `lingshu.contract.a2a-server-jsonrpc-dispatcher.v1`

### 4.1 契约 ID 与命名空间

`lingshu.contract.a2a-server-jsonrpc-dispatcher.v1` —— 版本号 `v1` 与 #009 `A2aServer.start()` / `stop()` 生命周期契约对齐。

### 4.2 契约内容(`POST /rpc` 端点协议)

#### 4.2.1 HTTP 请求

- Method: `POST`
- Path: `/rpc`
- Headers: `Content-Type: application/json`
- Body: JSON-RPC 2.0 envelope `{"jsonrpc":"2.0","id":<string|number|null>,"method":<string>,"params":<object>}`

#### 4.2.2 HTTP 响应

- Status: 始终 200(JSON-RPC 2.0 spec —— errors in body,**不**用 HTTP 状态码表示错误)
- Headers: `Content-Type: application/json; charset=utf-8`
- Body(成功):
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<echo from request>",
    "result": { ... }
  }
  ```
- Body(失败):
  ```json
  {
    "jsonrpc": "2.0",
    "id": "<echo from request or null>",
    "error": {
      "code": <int>,
      "message": "<string>"
    }
  }
  ```

#### 4.2.3 支持的 method

| method | params | result |
|---|---|---|
| `message/send` | `{"agentName":"<X>","skill":"<Y>","inputJson":"<Z>"}` | `{"status":"COMPLETED","taskId":"<UUID>","resultJson":"{\"echo\":<params.input>}"}` |
| `tasks/get` | `{"id":"<taskId>"}` | `{"status":"COMPLETED","taskId":"<taskId>","resultJson":"{}"}` |
| `tasks/cancel` | `{"id":"<taskId>"}` | `{"acknowledged":true}` |
| **其他** | — | `error.code = -32601` + `error.message = "Method not found: <method>"` |

**JSON-RPC 2.0 标准错误码**:

| code | message | 触发场景 |
|---|---|---|
| `-32700` | Parse error | 请求 body 不是合法 JSON |
| `-32600` | Invalid Request | 缺 `jsonrpc` 字段 / `method` 字段 |
| `-32601` | Method not found | 不支持的 method |
| `-32602` | Invalid params | params 字段缺失必填 |
| `-32603` | Internal error | handler 内部异常 |

### 4.3 错误契约

| 触发条件 | JSON-RPC error code | message |
|---|---|---|
| 请求 body 不是合法 JSON | -32700 | `"Parse error: <message>"` |
| method 字段为空 | -32600 | `"Invalid Request: method field is required"` |
| method 不在白名单 | -32601 | `"Method not found: <method>"` |
| handler 内部异常(IOException 等)| -32603 | `"Internal error: <message>"` |

### 4.4 关键不变项

- `A2aServer.start()` / `stop()` 生命周期不变
- `GET /.well-known/agent.json` 端点不变
- 注册路径 `server.createContext("/rpc", new RpcDispatcherHandler())` 替换 1 个 private handler

---

## 5. Contract #4: `lingshu.contract.error-code-s08-subdivision.v1`

### 5.1 契约 ID 与命名空间

`lingshu.contract.error-code-s08-subdivision.v1` —— LINGS-S08 域细分约定。

### 5.2 契约内容

#### 5.2.1 LINGS-S08 子码表

| 子码 | nested exception | 触发场景 | Story |
|---|---|---|---|
| `A2A_INPROCESS_REGISTRY_EMPTY` | `InProcessA2aRegistryEmptyException` | InProcessA2aTransport.fetchCard 时 registry miss | #009b |
| `A2A_HTTP_RPC_FAILED` | `HttpJsonRpcException` | HttpJsonRpcA2aTransport 5 方法 HTTP / JSON-RPC 失败 | #009c |

#### 5.2.2 子码细分原则(dsh §15 「域细分」)

- 同号 `LINGS-S08` 不冲突 —— 因为同属 **Slot 9 A2A 客户端** 域
- 通过 `getReason()` 字符串区分(对齐 `LingsA2aServerException.getErrorCode()` + `getHint()` 模式)
- `getMessage()` 统一返回 `[LINGS-S08 <reason>] <message>` 格式
- 用户通过 `getReason()` 判断是 in-process 还是 http-jsonrpc 错误

#### 5.2.3 错误码命名约束

- **新号不冲突**:`LINGS-S07`(#009a A2A_SERVER_START_FAILED 子码)+ `LINGS-S08`(#009b / #009c 域细分)+ `LINGS-T02`(#009 A2A_CARD_INVALID_CONFIG)+ `LINGS-S06`(#009 A2A_SERVER_START_FAILED)→ S 域已用 06 / 07 / 08,**下一号 S09 留给未来** Grpc 错误码细分
- **跨域不冲突**:T 域(LLM / Tool)+ S 域(Slot)+ C 域(Config)+ L 域(Lifecycle)等独立编号

### 5.3 错误契约

```java
public class HttpJsonRpcException extends RuntimeException {
    private final String errorCode = "LINGS-S08";
    private final String reason = "A2A_HTTP_RPC_FAILED";

    public HttpJsonRpcException(String message, Throwable cause) {
        super(message, cause);
    }

    public String getErrorCode() { return errorCode; }
    public String getReason() { return reason; }

    @Override
    public String getMessage() {
        return "[" + errorCode + " " + reason + "] " + super.getMessage();
    }
}
```

### 5.4 关键不变项

- `LingshuException` 基类契约不变(若有)
- `LingsA2aServerException` 契约不变(#009 已落地)
- `InProcessA2aRegistryEmptyException` 契约不变(#009b 已落地)
- 错误码 LINGS-<域><编号> 命名约定不变

---

## 6. 契约兼容性矩阵

| 契约 | 现有版本 | 新版本 | MAJOR 是否变 | 兼容策略 |
|---|---|---|---|---|
| `lingshu.contract.a2a-transport.v1` | v1(#009a) | v1(不变)| 否 | HttpJsonRpcA2aTransport 实现 v1 契约 |
| `lingshu.contract.agent-card-cache.v1` | v1(#009a) | v1(不变)| 否 | HttpJsonRpcA2aTransport 复用 v1 契约 |
| `lingshu.contract.in-process-a2a-registry.v1` | v1(#009b) | v1(不变)| 否 | 单元测试 mock 通路用 |
| `lingshu.contract.tool.v1` | v1(#009a) | v1(不变)| 否 | RemoteAgentTool implements Tool |
| `lingshu.contract.tool-executor.v1` | v1(#009a) | v1(不变)| 否 | RemoteAgentTool 接入 ToolExecutor 5 步流水线 |
| `lingshu.contract.a2a-server-lifecycle.v1` | v1(#009) | v1(不变)| 否 | RpcDispatcherHandler 替换 RpcPlaceholderHandler,**不**改 start/stop |
| `lingshu.contract.a2a-server-agent-json.v1` | v1(#009) | v1(不变)| 否 | GET /.well-known/agent.json 不变 |
| `lingshu.contract.error-code-s08-subdivision.v1` | — | v1(新增)| 是(子码细分)| 不破既有契约,新增子码 |

**所有现有契约不变** —— 本 Story 只**新增** `http-jsonrpc-a2a-transport.v1` / `remote-agent-tool.v1` / `a2a-server-jsonrpc-dispatcher.v1` / `error-code-s08-subdivision.v1` 4 个新契约,**不修改**任何现有契约。

---

## 7. 契约测试覆盖

| 契约 | L1 测试文件 | case 数 | 覆盖场景 |
|---|---|---|---|
| `http-jsonrpc-a2a-transport.v1` | `HttpJsonRpcA2aTransportTest` | 5 | fetchCard happy + LINGS-S08 异常;submit / get / cancel / subscribe happy path |
| `http-jsonrpc-a2a-transport.v1` | `HttpJsonRpcA2aTransportProviderTest` | 3 | name/version/priority + httpBaseUrl/callTimeout/cardTtl 读取 |
| `remote-agent-tool.v1` | `RemoteAgentToolTest` | 4 | execute happy + transport 抛 LINGS-S08 转 ToolResult.toolError + inputSchema 固定 + description 不为 null |
| `remote-agent-tool.v1` | `HttpJsonRpcA2aTransportAutoConfigurationTest` | 2 | 3 Provider 同存 + Tool remoteAgentTool Bean 注入 |
| `a2a-server-jsonrpc-dispatcher.v1` | `A2aServerRpcEndpointTest` | 2 | POST /rpc echo + method-not-found error |
| `error-code-s08-subdivision.v1` | 隐式覆盖于上述测试 | (分散) | LINGS-S08 异常构造 / message 格式 / reason 字段 |
| **合计** | 5 文件 | **18 case** | — |
