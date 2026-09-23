# Quickstart: Story #009c a2a-httpjsonrpc-and-remote-tool

**Story**: #009c
**Branch**: `story-009c-a2a-httpjsonrpc-and-remote-tool`
**Created**: 2026-09-22

> 7 个验证场景,覆盖 spec.md 4 User Stories + 14 Edge Cases + AC-10 关联。

---

## 验证场景总览

| # | 场景 | US | 类型 | 验证方式 |
|---|---|---|---|---|
| **VS-1** | HttpJsonRpcA2aTransport 5 方法 happy path | US-1 | L1 Unit | HttpJsonRpcA2aTransportTest |
| **VS-2** | HttpJsonRpcA2aTransport LINGS-S08 异常路径 | US-1 + EC-4/5/6/7 | L1 Unit | HttpJsonRpcA2aTransportTest |
| **VS-3** | HttpJsonRpcA2aTransportProvider name/version/priority | US-2 | L1 Unit | HttpJsonRpcA2aTransportProviderTest |
| **VS-4** | RemoteAgentTool execute happy path | US-3 | L1 Unit | RemoteAgentToolTest |
| **VS-5** | RemoteAgentTool execute transport 抛 LINGS-S08 → ToolResult.toolError | US-3 + EC-11 | L1 Unit | RemoteAgentToolTest |
| **VS-6** | 3 Provider 同存(grpc + in-process + http-jsonrpc)+ Tool remoteAgentTool 注入 | US-4 + AC-10 | L2 Slice | HttpJsonRpcA2aTransportAutoConfigurationTest |
| **VS-7** | A2aServer POST /rpc JSON-RPC 2.0 echo + method-not-found | I-09 | L2 Slice | A2aServerRpcEndpointTest |
| **VS-8** | R-13 mitigation (d) 0 binary delta | NFR-001 + NFR-009 | R-13 mirror | `mvn dependency:tree` diff |

---

## VS-1: HttpJsonRpcA2aTransport 5 方法 happy path

**目标**:验证 `HttpJsonRpcA2aTransport` 5 方法在 remote agent 正常响应时按 JSON-RPC 2.0 协议工作

**前置**(JDK 17 `HttpServer` mock 模拟 remote agent):
```java
HttpServer mockAgent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
mockAgent.createContext("/.well-known/agent.json", ex -> {
    byte[] body = "{\"name\":\"alice\",\"description\":\"Remote Alice\",\"version\":\"1.0.0\",\"skills\":[\"echo\"]}".getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(body); }
});
mockAgent.createContext("/rpc", ex -> {
    byte[] reqBody = ex.getRequestBody().readAllBytes();
    JsonNode req = mapper.readTree(reqBody);
    String id = req.path("id").asText();
    String method = req.path("method").asText();
    ObjectNode result = mapper.createObjectNode();
    if (method.equals("message/send")) {
        result.put("status", "COMPLETED");
        result.put("taskId", "task-1");
        result.put("resultJson", "{\"echo\":\"hi\"}");
    } else if (method.equals("tasks/get")) {
        result.put("status", "COMPLETED");
        result.put("taskId", "task-1");
        result.put("resultJson", "{}");
    } else if (method.equals("tasks/cancel")) {
        result.put("acknowledged", true);
    }
    ObjectNode resp = mapper.createObjectNode();
    resp.put("jsonrpc", "2.0"); resp.put("id", id); resp.set("result", result);
    byte[] body = resp.toString().getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "application/json");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream os = ex.getResponseBody()) { os.write(body); }
});
mockAgent.start();
String httpBaseUrl = "http://127.0.0.1:" + mockAgent.getAddress().getPort();

AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));
HttpJsonRpcA2aTransport transport = new HttpJsonRpcA2aTransport(
    httpBaseUrl, new ObjectMapper(), cache, Duration.ofSeconds(5));
```

**操作**:
```java
// fetchCard
Map<String, Object> card = transport.fetchCard("alice");
assertThat(card.get("name")).isEqualTo("alice");
assertThat(card.get("skills")).isEqualTo(Arrays.asList("echo"));

// submit
ToolResult submitResult = transport.submit("alice", "echo", "{\"msg\":\"hi\"}");
assertThat(submitResult.isError()).isFalse();
assertThat(submitResult.getContent()).contains("echo");

// get
ToolResult getResult = transport.get("task-1");
assertThat(getResult.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);

// cancel
boolean cancelAck = transport.cancel("task-1");
assertThat(cancelAck).isTrue();

// subscribe
List<Map<String, Object>> events = new ArrayList<>();
transport.subscribe("task-1", events::add);
// subscribe 是 polling,会 poll 1-2 次直到 terminal state
Thread.sleep(1500); // 等 polling
assertThat(events).isNotEmpty();
```

**期望**:
- 5 方法全部 happy path 通过
- `cardCache.get("alice")` 命中免 HTTP(第二次 `fetchCard` 应走 cache)

**测试文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportTest.java#testFetchCardHappyPath` + `testSubmit/Get/Cancel/SubscribeHappyPath`

---

## VS-2: HttpJsonRpcA2aTransport LINGS-S08 异常路径

**目标**:验证 HTTP 5xx / IOException / timeout / JSON-RPC error → `HttpJsonRpcException`(`LINGS-S08 A2A_HTTP_RPC_FAILED`)

**前置**(JDK 17 `HttpServer` mock 模拟各种失败):
```java
// 1) HTTP 503
HttpServer errorAgent503 = startMockAgent(503, "{}");
// 2) HTTP timeout(callTimeout=100ms,server sleep 500ms)
HttpServer slowAgent = startMockAgent(200, "...", 500);
// 3) JSON-RPC error
HttpServer jsonRpcErrorAgent = startJsonRpcErrorMock(-32601, "Method not found");
```

**操作**:
```java
// 1) HTTP 503
HttpJsonRpcA2aTransport t1 = new HttpJsonRpcA2aTransport(
    "http://127.0.0.1:" + errorAgent503.getAddress().getPort(), new ObjectMapper(),
    new AgentCardCache(Duration.ofMinutes(5)), Duration.ofSeconds(5));
assertThatThrownBy(() -> t1.fetchCard("alice"))
    .isInstanceOf(HttpJsonRpcException.class)
    .hasMessageContaining("[LINGS-S08 A2A_HTTP_RPC_FAILED]")
    .hasMessageContaining("fetchCard HTTP 503");

// 2) timeout
HttpJsonRpcA2aTransport t2 = new HttpJsonRpcA2aTransport(
    "http://127.0.0.1:" + slowAgent.getAddress().getPort(), new ObjectMapper(),
    new AgentCardCache(Duration.ofMinutes(5)), Duration.ofMillis(100));
assertThatThrownBy(() -> t2.fetchCard("alice"))
    .isInstanceOf(HttpJsonRpcException.class)
    .hasMessageContaining("[LINGS-S08 A2A_HTTP_RPC_FAILED]")
    .hasMessageContaining("timeout");

// 3) JSON-RPC error
HttpJsonRpcA2aTransport t3 = new HttpJsonRpcA2aTransport(
    "http://127.0.0.1:" + jsonRpcErrorAgent.getAddress().getPort(), new ObjectMapper(),
    new AgentCardCache(Duration.ofMinutes(5)), Duration.ofSeconds(5));
assertThatThrownBy(() -> t3.submit("alice", "echo", "{}"))
    .isInstanceOf(HttpJsonRpcException.class)
    .hasMessageContaining("[LINGS-S08 A2A_HTTP_RPC_FAILED]")
    .hasMessageContaining("Method not found");
```

**期望**:
- 3 种异常路径全部抛 `HttpJsonRpcException`
- message 含 `[LINGS-S08 A2A_HTTP_RPC_FAILED]` 前缀
- `getErrorCode()="LINGS-S08"` + `getReason()="A2A_HTTP_RPC_FAILED"`

**测试文件**:`HttpJsonRpcA2aTransportTest#testFetchCardHttp5xx` + `testFetchCardTimeout` + `testSubmitJsonRpcError`

---

## VS-3: HttpJsonRpcA2aTransportProvider name/version/priority

**目标**:验证 Provider 4 方法(name / priority / version / create)+ httpBaseUrl/callTimeout/cardTtl 从 cfg 读取

**前置**:
```java
AgentConfig cfg = AgentConfig.builder()
    .a2a(AgentConfig.A2a.defaults()
        .withHttpBaseUrl("http://custom-host:9999")
        .withCallTimeout(Duration.ofSeconds(60))
        .withCardTtl(Duration.ofMinutes(10)))
    .build();
```

**注意**:`AgentConfig.A2a` 当前是 `@Value`,Builder 模式不一定支持 `withXxx()` —— 实施期实测可能需要 `new A2a(...)` 显式构造

**操作**:
```java
HttpJsonRpcA2aTransportProvider provider = new HttpJsonRpcA2aTransportProvider();
assertThat(provider.name()).isEqualTo("http-jsonrpc-1.0.0");
assertThat(provider.priority()).isEqualTo(10);
assertThat(provider.version()).isEqualTo("1.0.0");

A2aTransport transport = provider.create(cfg);
HttpJsonRpcA2aTransport httpTransport = (HttpJsonRpcA2aTransport) transport;
// 通过 reflection 或 package-private getter 验证 httpBaseUrl="http://custom-host:9999"
// callTimeout=60s, cardTtl=10min
```

**期望**:
- `name()` 返回 `"http-jsonrpc-1.0.0"`(禁止与 `"grpc-1.0.0"` / `"in-process-1.0.0"` 冲突)
- `priority() = 10`
- `version() = "1.0.0"`
- `create(cfg)` 返回 `HttpJsonRpcA2aTransport`,字段从 cfg 读取

**测试文件**:`HttpJsonRpcA2aTransportProviderTest#testNameVersionPriority` + `testHttpBaseUrlFromConfig`

---

## VS-4: RemoteAgentTool execute happy path

**目标**:验证 `RemoteAgentTool.execute(call, ctx)` 解析 input + 转发到 `transport.submit` + 透传 ToolResult

**前置**(mock A2aTransport):
```java
A2aTransport mockTransport = mock(A2aTransport.class);
when(mockTransport.submit(eq("alice"), eq("echo"), eq("{\"msg\":\"hi\"}")))
    .thenReturn(ToolResult.builder()
        .status(ToolResult.Status.SUCCESS)
        .content("{\"echo\":\"hi\"}")
        .isError(false)
        .build());

RemoteAgentTool tool = new RemoteAgentTool(mockTransport, new ObjectMapper());
JsonNode input = new ObjectMapper().createObjectNode()
    .put("agentName", "alice")
    .put("skill", "echo")
    .set("input", new ObjectMapper().createObjectNode().put("msg", "hi"));
ToolCall call = new ToolCall("call-1", "remote_agent", input);
```

**操作**:
```java
ToolResult result = tool.execute(call, mock(ToolExecutionContext.class));
assertThat(result.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
assertThat(result.getContent()).isEqualTo("{\"echo\":\"hi\"}");
verify(mockTransport).submit("alice", "echo", "{\"msg\":\"hi\"}");
```

**期望**:
- 解析 input → `agentName="alice"` + `skill="echo"` + `input={msg:"hi"}`
- 转发 `transport.submit("alice", "echo", "{\"msg\":\"hi\"}")`
- 透传 ToolResult(SUCCESS + content)

**测试文件**:`RemoteAgentToolTest#testExecuteHappyPath`

---

## VS-5: RemoteAgentTool execute transport 抛 LINGS-S08 → ToolResult.toolError

**目标**:验证 transport 抛 `HttpJsonRpcException` → 返回 `ToolResult.toolError`(**不**重抛,ToolResult 链路优先)

**前置**:
```java
A2aTransport mockTransport = mock(A2aTransport.class);
when(mockTransport.submit(anyString(), anyString(), anyString()))
    .thenThrow(new HttpJsonRpcException("submit failed: HTTP 503", new IOException()));

RemoteAgentTool tool = new RemoteAgentTool(mockTransport, new ObjectMapper());
JsonNode input = new ObjectMapper().createObjectNode()
    .put("agentName", "alice").put("skill", "echo");
ToolCall call = new ToolCall("call-1", "remote_agent", input);
```

**操作**:
```java
ToolResult result = tool.execute(call, mock(ToolExecutionContext.class));
assertThat(result.getStatus()).isEqualTo(ToolResult.Status.ERROR);
assertThat(result.isError()).isTrue();
assertThat(result.getContent()).contains("Remote agent call failed");
assertThat(result.getContent()).contains("HTTP 503");
```

**期望**:
- `ToolResult.status = ERROR`
- `isError = true`
- content 含 `"Remote agent call failed: <message>"`

**测试文件**:`RemoteAgentToolTest#testExecuteTransportException`

---

## VS-6: 3 Provider 同存 + Tool remoteAgentTool 注入

**目标**:验证 `A2aTransportRouter` 注入 3 Provider + 启动日志列 3 行 ✓ + `Tool remoteAgentTool` Bean 注入 ToolRegistry

**前置**(L2 Slice):
```java
@SpringBootTest(classes = {
    GrpcA2aTransportAutoConfiguration.class,
    InProcessA2aTransportAutoConfiguration.class,
    HttpJsonRpcA2aTransportAutoConfiguration.class,
    A2aTransportRouter.class,
    AgentConfig.class
})
@TestPropertySource(properties = {
    "agent.identity.name=test-bob-coding",
    "agent.a2a.host=127.0.0.1",
    "agent.a2a.port=0",
    "agent.a2aTransport=http-jsonrpc-1.0.0"
})
class HttpJsonRpcA2aTransportAutoConfigurationTest {
    @Autowired A2aTransportRouter router;
    @Autowired(required = false) Tool remoteAgentTool;
    @Autowired ObjectMapper json;
}
```

**操作**:
```java
@Test
void testMultiProviderCoexistence() {
    Set<String> available = router.available();
    assertThat(available).containsExactlyInAnyOrder(
        "grpc-1.0.0", "in-process-1.0.0", "http-jsonrpc-1.0.0");
}

@Test
void testResolveHttpJsonRpc() {
    A2aTransport transport = router.resolve("http-jsonrpc-1.0.0", null);
    assertThat(transport).isInstanceOf(HttpJsonRpcA2aTransport.class);
}

@Test
void testRemoteAgentToolBeanInjected() {
    assertThat(remoteAgentTool).isNotNull();
    assertThat(remoteAgentTool.name()).isEqualTo("remote_agent");
}
```

**期望**:
- 启动日志含 `resolved 3 provider(s) [contract v1.0.0]:` + 3 行 ✓ 列表
- `available()` 集合 3 元素
- `resolve("http-jsonrpc-1.0.0", cfg)` 返回 `HttpJsonRpcA2aTransport`
- `Tool remoteAgentTool` Bean 注入成功,name = `"remote_agent"`

**测试文件**:`HttpJsonRpcA2aTransportAutoConfigurationTest#testMultiProviderCoexistence` + `testResolveHttpJsonRpc` + `testRemoteAgentToolBeanInjected`

---

## VS-7: A2aServer POST /rpc JSON-RPC 2.0 echo + method-not-found

**目标**:验证 `RpcDispatcherHandler` 按 JSON-RPC 2.0 协议返回 echo + 错误码 -32601

**前置**(L2 Slice,启动 A2aServer):
```java
@SpringBootTest(classes = {AgentConfig.class, A2aServer.class, A2aServerAutoConfiguration.class})
@TestPropertySource(properties = {
    "agent.identity.name=test-alice-coding",
    "agent.a2a.host=127.0.0.1",
    "agent.a2a.port=0"
})
class A2aServerRpcEndpointTest {
    @Autowired A2aServer a2aServer;
    String httpBaseUrl;

    @BeforeEach
    void setup() {
        httpBaseUrl = "http://127.0.0.1:" + a2aServer.getActualPort();
    }
}
```

**操作**:
```java
// 1) message/send echo
HttpClient client = HttpClient.newHttpClient();
HttpRequest req1 = HttpRequest.newBuilder(URI.create(httpBaseUrl + "/rpc"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(
        "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\",\"params\":{\"agentName\":\"alice\",\"skill\":\"echo\",\"input\":{\"msg\":\"hi\"}}}"))
    .build();
HttpResponse<String> resp1 = client.send(req1, BodyHandlers.ofString());
assertThat(resp1.statusCode()).isEqualTo(200);
JsonNode body1 = new ObjectMapper().readTree(resp1.body());
assertThat(body1.get("jsonrpc").asText()).isEqualTo("2.0");
assertThat(body1.get("id").asText()).isEqualTo("1");
assertThat(body1.get("result").get("status").asText()).isEqualTo("COMPLETED");
assertThat(body1.get("result").get("resultJson").asText()).contains("hi");

// 2) 不识别 method
HttpRequest req2 = HttpRequest.newBuilder(URI.create(httpBaseUrl + "/rpc"))
    .header("Content-Type", "application/json")
    .POST(HttpRequest.BodyPublishers.ofString(
        "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"unknown/method\",\"params\":{}}"))
    .build();
HttpResponse<String> resp2 = client.send(req2, BodyHandlers.ofString());
assertThat(resp2.statusCode()).isEqualTo(200);
JsonNode body2 = new ObjectMapper().readTree(resp2.body());
assertThat(body2.get("error").get("code").asInt()).isEqualTo(-32601);
assertThat(body2.get("error").get("message").asText()).contains("Method not found");
```

**期望**:
- HTTP 200 + JSON-RPC 2.0 envelope 完整(jsonrpc / id / result)
- `message/send` echo 成功,`result.status="COMPLETED"`,`result.resultJson` 含 `"hi"`
- 不识别 method → `error.code=-32601`,`error.message` 含 `"Method not found"`

**测试文件**:`lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerRpcEndpointTest.java#testMessageSendEcho` + `testMethodNotFound`

---

## VS-8: R-13 mitigation (d) 0 binary delta

**目标**:验证本 Story **+0 新依赖**,`mvn dependency:tree -pl lingshu-a2a-client` 与 #009b baseline **完全一致**

**前置**:
```bash
# 已在 Step 1 抓取 baseline
ls -la /tmp/deps-009c-pre.txt
```

**操作**:
```bash
cd <lingshu 主仓根>
mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009c-post.txt

# 对比
diff /tmp/deps-009c-pre.txt /tmp/deps-009c-post.txt
```

**期望**:`diff` 命令**无输出**(两个文件完全一致)

**enforcer 验证**:
```bash
mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify
```

**期望**:BUILD SUCCESS,enforcer `banned-dependencies` 规则**不 fail**

**关键子树(贴到 PR body `### R-13 dependency:tree 自查` 节)**:
```
ai.lingshu:lingshu-a2a-client:jar:0.1.0-SNAPSHOT
├── io.grpc:grpc-stub:jar:1.55.1:compile              [#009a 已落地,本 Story 不变]
├── com.google.protobuf:protobuf-java:jar:3.22.3:compile  [#009a 已落地,本 Story 不变]
├── ...
[无新增 subtree]
```

**测试方式**:手动跑命令 + 贴输出到 PR body,**不**写自动化测试

---

## 总结

8 个验证场景覆盖:
- ✅ AC-10 关联(US-4 + VS-6)
- ✅ 4 个 User Story(US-1—US-4 各有覆盖)
- ✅ 14 个 Edge Cases 中的 7 个(EC-1 / EC-2 / EC-4 / EC-5 / EC-6 / EC-7 / EC-11)
- ✅ R-13 mitigation (d) baseline 镜像(VS-8)

**未在 L1/L2 覆盖的 Edge Cases**:
- EC-3 / EC-8 / EC-9 / EC-10 / EC-12 / EC-13 / EC-14:由测试代码自然覆盖(在 L1/L2 测试 setup + tearDown + 异常路径中触发)

**测试 case 总数**:18 case(5 HttpJsonRpcA2aTransportTest + 3 HttpJsonRpcA2aTransportProviderTest + 4 RemoteAgentToolTest + 3 HttpJsonRpcA2aTransportAutoConfigurationTest + 2 A2aServerRpcEndpointTest)
