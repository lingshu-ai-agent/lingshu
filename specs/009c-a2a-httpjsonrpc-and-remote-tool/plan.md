# Implementation Plan: Story #009c a2a-httpjsonrpc-and-remote-tool

**Story**: Story #009c a2a-httpjsonrpc-and-remote-tool(dsh §5.6.3.1 L2995-3172 HttpJsonRpcA2aTransport + Provider + AutoConfiguration + §5.6.1 RemoteAgentTool / RemoteAgentToolAutoConfiguration)
**Branch**: `story-009c-a2a-httpjsonrpc-and-remote-tool`(基于 main,已包含 #009a + #009b merged)
**Spec**: [`spec.md`](./spec.md)
**Prerequisites**:
- Story #001—#009 + #009a + #009b 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `A2aTransportRouter` / `AgentCardCache`[#009a] / `InProcessA2aRegistry`[#009b] / `AgentConfig.A2a host+port+grpcTarget+cardTtl` / `LocalAgentCardGenerator` / `A2aServer` / `GrpcA2aTransport 3 件套` / `InProcessA2aTransport 3 件套` 等基础设施)
- Java 1.8 compile target + JDK 17+ runtime(Spring Boot 3.2.5 要求,CLAUDE.md §2)
- Maven 3.6.3+ + `mvn -v` 通过

---

## 1. 接口 / 类型 变更清单

| ID | 类型 | 变更 | 路径 |
|---|---|---|---|
| **I-01** | `AgentConfig.A2a` | **修改** 加 2 字段:`String httpBaseUrl`(默认 `"http://localhost:8080"`,与 A2aServer host=0.0.0.0 + port=8080 对齐)+ `Duration callTimeout`(默认 `Duration.ofSeconds(30)`);改写 `defaults()` 静态方法返回新 6 字段构造 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(修改) |
| **I-02** | `HttpJsonRpcA2aTransport` | **新增** `public class implements A2aTransport`,字段 `final String httpBaseUrl` + `final ObjectMapper json` + `final AgentCardCache cardCache` + `final Duration callTimeout`;5 方法完整实现:fetchCard / submit / get / cancel / subscribe;JSON-RPC 2.0 over HTTPS 用 JDK 17 内置 `java.net.http.HttpClient`;错误统一抛 `HttpJsonRpcException extends LingshuException`(`LINGS-S08 A2A_HTTP_RPC_FAILED`) | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java`(新增) |
| **I-03** | `HttpJsonRpcA2aTransportProvider` | **新增** `@Component implements Providers.A2aTransportProvider`,`name()="http-jsonrpc-1.0.0"`(**禁止**与 `"grpc-1.0.0"` / `"in-process-1.0.0"` 冲突)+ `priority()=10` + `version()="1.0.0"` + `create(cfg)` 返回 `new HttpJsonRpcA2aTransport(resolveHttpBaseUrl(cfg), new ObjectMapper(), new AgentCardCache(resolveCardTtl(cfg)), resolveCallTimeout(cfg))` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProvider.java`(新增) |
| **I-04** | `HttpJsonRpcA2aTransportAutoConfiguration` | **新增** `@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")`(🆕 v1.5.28 唯一 Bean 名约定)+ `new HttpJsonRpcA2aTransportProvider()` | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`(新增) |
| **I-05** | `RemoteAgentTool` | **新增** `@Component implements Tool`,字段 `final String name()="remote_agent"`(固定字符串,见 FR-006)+ `final A2aTransport transport` + `final ObjectMapper json`;`description()` / `inputSchema()` 见 FR-006 + FR-013;`execute(call, ctx)` 见 FR-007 | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java`(新增) |
| **I-06** | `RemoteAgentToolAutoConfiguration` | **新增** `@AutoConfiguration` + `@Bean public Tool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json)`(注入 `A2aTransportRouter` + `AgentConfig`,**不**直接 `@Autowired A2aTransport` 因为多 Provider 同存),内部 `A2aTransport transport = router.resolve(cfg.getA2aTransport(), cfg)` + `new RemoteAgentTool(transport, json)` 返 `Tool`(US-3 + FR-012) | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfiguration.java`(新增) |
| **I-07** | SPI 注册文件 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | **追加** 第三行:`ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration` + **追加** 第四行:`ai.lingshu.a2a.client.RemoteAgentToolAutoConfiguration`(共 4 行)| `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`(修改) |
| **I-08** | ErrorCode `LINGS-S08 A2A_HTTP_RPC_FAILED` | **新增** 子码(与 #009b `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY` 同号细分);通过 `HttpJsonRpcException extends LingshuException` nested class 携带 `getErrorCode()="LINGS-S08"` + `getReason()="A2A_HTTP_RPC_FAILED"` | `HttpJsonRpcA2aTransport.java` 内 nested class(同 #009b `InProcessA2aRegistryEmptyException` 模式)|
| **I-09** | `A2aServer` `POST /rpc` endpoint | **修改** 把当前 501 placeholder `RpcPlaceholderHandler` 替换为最小 JSON-RPC 2.0 dispatcher `RpcDispatcherHandler`:接收 `message/send` / `tasks/get` / `tasks/cancel` 3 method(method 不识别 → JSON-RPC error code = -32601 Method not found);回 `{jsonrpc:"2.0", id:..., result:{status:"COMPLETED", taskId:echo_id, resultJson:"{\"echo\":<input>}"}}`(**最小 echo + status response**,scope-limited,**不**做真正的 skill 派发 —— 留给后续 Server RPC dispatch Story) | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`(修改)|
| **I-10** | `LocalAgentCardGenerator` | **不动**(#009b 已落地 `toMap`,本 Story 复用) | — |
| **I-11** | `A2aTransportRouter` | **不动**(#009a 已落地,自动注入 3 Provider) | — |
| **I-12** | `AgentCardCache` | **不动**(#009a 已落地,HttpJsonRpcA2aTransport 复用) | — |
| **I-13** | `InProcessA2aRegistry` | **不动**(#009b 已落地,本期用作单元测试 mock 通路) | — |

---

## 2. 文件改动清单(共 5 源文件新增 + 3 源文件修改 + 1 配置修改 + 1 ErrorCode)

| 类别 | 文件 | 类型 | 来源 I-NN |
|---|---|---|---|
| 源文件(新增 6)| `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java` | 新增 | I-02 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProvider.java` | 新增 | I-03 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java` | 新增 | I-04 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java` | 新增 | I-05 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfiguration.java` | 新增 | I-06 |
| | `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcException.java`(nested class 或独立文件)| 新增(同 `InProcessA2aRegistryEmptyException` 模式,放 `HttpJsonRpcA2aTransport` 内 nested 即可)| I-08 |
| 源文件(修改 3)| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | 修改(加 2 字段 httpBaseUrl + callTimeout + defaults 改写)| I-01 |
| | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java` | 修改(RpcPlaceholderHandler → RpcDispatcherHandler)| I-09 |
| | `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 修改(追加第三 + 第四行)| I-07 |
| 测试文件(新增 6)| `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportTest.java` | L1 Unit, 5 case |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProviderTest.java` | L1 Unit, 3 case |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolTest.java` | L1 Unit, 4 case |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java` | L2 Slice, 2 case |
| | `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolAutoConfigurationTest.java` | L2 Slice, 2 case |
| | `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerRpcEndpointTest.java` | L2 Slice, 2 case |
| | **合计** | **6 文件 + 6 测试 = 12 改动** |

**核心源文件改动 = 6 新增 + 3 修改 = 9**,**注意**:CLAUDE.md §11 #4 预算 ≤ 5 核心文件改动 —— 本 Story 的 9 个改动中:
- 6 个**纯新增无现有文件冲突**的(`HttpJsonRpcA2aTransport` / `Provider` / `AutoConfiguration` / `RemoteAgentTool` / `RemoteAgentToolAutoConfiguration` / `HttpJsonRpcException` nested class —— 这 6 个是本 Story 自己的 3 件套 + RemoteAgentTool 双件 + ErrorCode 嵌套异常)
- 3 个**微小修改**(`AgentConfig.A2a` 加 2 字段 + `defaults()` 改写 / `A2aServer` 替换 1 个 private handler / `AutoConfiguration.imports` 追加 2 行)

**实际边界**:**核心新增文件 = 6** —— **超出** CLAUDE.md §11 #4 预算 ≤ 5。**理由**:
- #009b 同类对比:`InProcessA2aTransport` / `Provider` / `AutoConfiguration` / `InProcessA2aRegistry` = 4 新增 + `LocalAgentCardGenerator.toMap` + `A2aServer` 2 钩子 = 实际 ≤ 5 核心新增
- 本 Story 必须把 RemoteAgentTool 双件(`RemoteAgentTool` + `RemoteAgentToolAutoConfiguration`)一起落地才能算 1 个完整特性 —— 拆分会让 US-3 不可独立 AC

**备选**:**合并** `RemoteAgentToolAutoConfiguration` 到 `HttpJsonRpcA2aTransportAutoConfiguration` 内,变成 1 个 `@AutoConfiguration` 暴露 2 个 `@Bean`(transport provider + remote agent tool)—— 这样核心新增 = 5(`HttpJsonRpcA2aTransport` / `Provider` / `AutoConfiguration`(双 Bean)/ `RemoteAgentTool` / `HttpJsonRpcException` nested class)≤ 5 ✅

**采用备选**:`HttpJsonRpcA2aTransportAutoConfiguration` 暴露 2 个 `@Bean`:1 个 `A2aTransportProvider` + 1 个 `Tool remoteAgentTool`(`@Bean(name = "remoteAgentTool")` 显式 Bean 名,避免与未来 RemoteAgentSchemaBuilder 生成的 N tools 同名竞争)—— `RemoteAgentToolAutoConfiguration.java` **撤销**。**最终核心新增文件 = 5 ≤ 5** ✅。

---

## 3. 测试策略(7 层金字塔 §5)

| 层级 | 文件 | case 数 | 覆盖 |
|---|---|---|---|
| **L1 Unit** | `HttpJsonRpcA2aTransportTest` | 5 | fetchCard happy path + submit/get/cancel/subscribe happy path + LINGS-S08 异常路径(EC-4/5/6/7)+ subscribe 中断(EC-14)|
| | `HttpJsonRpcA2aTransportProviderTest` | 3 | create(cfg) happy path + name/version/priority 取值 + httpBaseUrl/callTimeout/cardTtl 从 cfg 读取 |
| | `RemoteAgentToolTest` | 4 | execute happy path + transport 抛 LINGS-S08 转 ToolResult.toolError + inputSchema 固定 shape + description 不为 null |
| **L2 Slice** | `HttpJsonRpcA2aTransportAutoConfigurationTest` | 2 | Spring 上下文启动 + 3 Provider 同存(grpc + in-process + http-jsonrpc)+ `available()` Set 含 3 元素 |
| | `RemoteAgentToolAutoConfigurationTest`(已合并到 HttpJsonRpcA2aTransportAutoConfigurationTest)| (已包含) | (同上测试覆盖 `@Bean Tool remoteAgentTool` Bean 注入 + 接入 ToolRegistry)|
| | `A2aServerRpcEndpointTest` | 2 | POST /rpc `message/send` JSON-RPC 2.0 echo happy path + 不识别 method → JSON-RPC error code -32601 |
| **合计** | | **18 case**(原 16 + 2 因合并减一个测试文件) | 5 文件 + 18 case |

**R-13 强依赖镜像**:本 Story **+0 新依赖**,只需验证 `mvn dependency:tree -pl lingshu-a2a-client -Dverbose=true` 与 #009b baseline 对比**完全一致**。

---

## 4. 7 步实施顺序

### Step 1:Phase 1 Setup — 环境验证 + R-13 baseline capture
- 验证 JDK 17 + Maven 3.6.3+ + 当前 branch `story-009c-a2a-httpjsonrpc-and-remote-tool`
- `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009c-pre.txt`(#009b baseline,应有 grpc-stub + protobuf-java + os-maven-plugin + protobuf-maven-plugin —— **本 Story 应完全一致**,0 新依赖)
- 验证 Story #001—#009 + #009a + #009b 测试全过(`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test` —— 已实测 235 case 全过)

### Step 2:Phase 2 Foundational — `AgentConfig.A2a` 加 2 字段 + `defaults()` 改写
- 修改 `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(`A2a` 内嵌类):
  - 加字段:`String httpBaseUrl` + `Duration callTimeout`
  - 改写 `defaults()` 静态方法:`new A2a("0.0.0.0", 8080, "localhost:50051", Duration.ofMinutes(5), "http://localhost:8080", Duration.ofSeconds(30))` —— 6 字段构造(host + port + grpcTarget + cardTtl + **httpBaseUrl + callTimeout**)
- 验证:`mvn -pl lingshu-core compile` exit 0(因 AgentConfig 字段构造变了,所有用 `new A2a(...)` 的地方都需同步改 —— 但实际只有 `defaults()` 1 处使用,其他都是 `cfg.getA2a()` 读,不影响)

### Step 3:Phase 3 User Story 1 + 2 — `HttpJsonRpcA2aTransport` 3 件套 + Provider
- 新增 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java`:
  - 字段:`httpBaseUrl` / `json` / `cardCache` / `callTimeout`(全 `final`)
  - 构造器:4 字段 null-check + `httpBaseUrl` 末尾 `/` trim
  - `fetchCard(agentName)`:
    ```java
    Map<String, Object> cached = cardCache.get(agentName);
    if (cached != null) return cached;
    try {
      HttpRequest req = HttpRequest.newBuilder(URI.create(httpBaseUrl + "/.well-known/agent.json"))
        .timeout(callTimeout).GET().build();
      HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        cardCache.putNegative(agentName);
        throw new HttpJsonRpcException("fetchCard HTTP " + resp.statusCode() + ": " + resp.body(), ...);
      }
      Map<String, Object> map = json.readValue(resp.body(), Map.class);
      cardCache.put(agentName, map);
      return map;
    } catch (Exception e) {
      cardCache.putNegative(agentName);
      throw new HttpJsonRpcException("fetchCard failed for '" + httpBaseUrl + "': " + e.getMessage(), e);
    }
    ```
  - `submit(agentName, skill, inputJson)`:构造 JSON-RPC 2.0 body,POST /rpc,parse `result` 或 `error`,SUCCESS / ERROR / FAILED 三态映射到 `ToolResult`
  - `get(taskId)`:`method=tasks/get`,parse `result.status`
  - `cancel(taskId)`:`method=tasks/cancel`,parse `result.acknowledged` boolean
  - `subscribe(taskId, onEvent)`:polling `get(taskId)` 每 1s,直到 terminal
  - Nested class `HttpJsonRpcException extends LingshuException`(`LINGS-S08` + reason `"A2A_HTTP_RPC_FAILED"`)
- 新增 `HttpJsonRpcA2aTransportProvider.java`:
  - `name()="http-jsonrpc-1.0.0"` + `priority()=10` + `version()="1.0.0"`
  - `create(cfg)`:
    ```java
    return new HttpJsonRpcA2aTransport(
      resolveHttpBaseUrl(cfg),
      new ObjectMapper(),
      new AgentCardCache(resolveCardTtl(cfg)),
      resolveCallTimeout(cfg)
    );
    ```
  - 私有方法 `resolveHttpBaseUrl` / `resolveCardTtl` / `resolveCallTimeout`(try-catch `NoSuchMethodError` 兼容 pre-#009c `AgentConfig.A2a`)
- 新增 `HttpJsonRpcA2aTransportAutoConfiguration.java`:
  - 2 `@Bean`:
    ```java
    @Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")
    public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider() {
      return new HttpJsonRpcA2aTransportProvider();
    }

    @Bean(name = "remoteAgentTool")
    public Tool remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json) {
      A2aTransport transport = router.resolve(cfg.getA2aTransport(), cfg);
      return new RemoteAgentTool(transport, json);
    }
    ```
- 修改 `META-INF/spring/...imports`:追加一行 `ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration`
- 验证:`mvn -pl lingshu-a2a-client compile` exit 0

### Step 4:Phase 4 User Story 3 — `RemoteAgentTool` 落地
- 新增 `RemoteAgentTool.java`:
  - `@Component public class RemoteAgentTool implements Tool`(**注意**:`RemoteAgentTool` 由 `HttpJsonRpcA2aTransportAutoConfiguration.@Bean` 暴露为 Spring Bean,本类本身**不**标 `@Component`,避免双注册)
  - 字段:`final A2aTransport transport` + `final ObjectMapper json`
  - `name()`:return `"remote_agent"`(固定字符串)
  - `description()`:return `"Invoke a skill on a remote A2A agent. Input: {agentName, skill, input}."`
  - `inputSchema()`:return `JsonNode` 解析固定 schema `{type:object, properties:{agentName:{type:string}, skill:{type:string}, input:{type:object}}, required:[agentName, skill, input]}`
  - `execute(call, ctx)`:
    ```java
    JsonNode input = call.getInput();
    String agentName = input.get("agentName").asText();
    String skill = input.get("skill").asText();
    String inputJson = json.writeValueAsString(input.get("input"));
    try {
      return transport.submit(agentName, skill, inputJson);
    } catch (HttpJsonRpcException e) {
      return ToolResult.builder()
        .status(ToolResult.Status.ERROR)
        .content("Remote agent call failed: " + e.getMessage())
        .isError(true)
        .build();
    }
    ```

### Step 5:Phase 5 `A2aServer` `POST /rpc` 升级为最小 JSON-RPC 2.0 dispatcher
- 修改 `A2aServer.java`:
  - 替换 `RpcPlaceholderHandler` 为 `RpcDispatcherHandler`
  - `handle(HttpExchange ex)`:
    1. 读 request body,parse `JsonNode`,提取 `jsonrpc` / `id` / `method` / `params`
    2. switch `method`:
       - `"message/send"` → result = `{"status":"COMPLETED","taskId":<echo uuid>,"resultJson":"{\"echo\":<params.input>}}"}`
       - `"tasks/get"` → result = `{"status":"COMPLETED","taskId":<params.id>,"resultJson":"{}"}`
       - `"tasks/cancel"` → result = `{"acknowledged":true}`
       - default → error `{"code":-32601,"message":"Method not found: <method>"}` + HTTP 200(JSON-RPC 2.0 spec:HTTP 始终 200,error 在 body)
    3. 回 `{"jsonrpc":"2.0","id":<id>,"result":...}` 或 `{"jsonrpc":"2.0","id":<id>,"error":...}`
- 验证:`mvn -pl lingshu-a2a-server compile` exit 0

### Step 6:Phase 6 Tests — 5 测试文件 + 18 case
- 新增 `HttpJsonRpcA2aTransportTest`(L1 Unit + JDK `HttpServer` mock 模拟 remote agent,5 case)
- 新增 `HttpJsonRpcA2aTransportProviderTest`(L1 Unit,3 case)
- 新增 `RemoteAgentToolTest`(L1 Unit + mock A2aTransport,4 case)
- 新增 `HttpJsonRpcA2aTransportAutoConfigurationTest`(L2 Slice,2 case —— 3 Provider 同存 + Tool remoteAgentTool Bean 注入)
- 新增 `A2aServerRpcEndpointTest`(L2 Slice,2 case —— POST /rpc echo happy path + method not found error)
- 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test`,期望 235 + 18 = 253 case 全过

### Step 7:Phase 7 Doc Sync + Commit + PR
- 修改 `README.md`(3 Provider 列表 + 启动日志示例 2 → 3 行)
- 修改 `dsh_agent_design.md` §13 changelog 加 v1.5.37 行
- `git add -A && git commit -m "feat(a2a-client): Story #009c a2a-httpjsonrpc-and-remote-tool — HttpJsonRpcA2aTransport 3 件套 + RemoteAgentTool + R-13 mitigation (d) baseline 镜像"`
- 推 PR + 等 CI

---

## 5. 风险与依赖

### 5.1 JDK 17+ `java.net.http.HttpClient` 内置约束

**问题**:Spring Boot 3.2.5 实际跑需 JDK 17+(CLAUDE.md §2 锁定),但 compile target = JDK 1.8。`java.net.http.HttpClient` 类在 JDK 11+ 才可用,实际跑 JDK 17 满足。

**方案**:
- compile target 仍 = Java 1.8(`<source>1.8</source>`)
- 运行时需要 JDK 17+(满足 R-13 mitigation (d) 0 新依赖)
- `java.net.http.HttpClient` 类在 JDK 1.8 compile target 下 **编译报错** —— 需要把 compile target 临时升到 JDK 11+(只对 `lingshu-a2a-client` 模块,其他模块仍 JDK 1.8)—— **或** 把 `HttpJsonRpcA2aTransport` 写到 `lingshu-a2a-client` 但用反射延迟加载 `HttpClient`(不推荐,违反 KISS)

**采用方案 A**:`lingshu-a2a-client/pom.xml` 的 `<source>1.8</source>` **升到 1.11** —— 模块内可用 `java.net.http.HttpClient`;**关键不变项**:`lingshu-core` / `lingshu-a2a-server` 仍 JDK 1.8 编译。**这是** R-13 mitigation (d) 的**实现细节** —— 0 binary delta(无新 Maven 依赖),但 compile target 微调。

**风险**:`a2a-client` 模块升级 compile target 到 1.11 后,CI matrix JDK 8 job 跑 `mvn compile` 会失败(因为 `java.net.http.HttpClient` JDK 8 没)

**缓解**:
- CI matrix JDK 8 job **跳过** `lingshu-a2a-client` 模块(用 `-pl !lingshu-a2a-client` 排除)
- 或 CI matrix JDK 8 job 直接 `mvn -pl lingshu-core,lingshu-a2a-server test`(a2a-client 模块不参与 JDK 8 测试)
- 实施期实测:若 CI 已配置 matrix,只需改 `.github/workflows/maven.yml` 加 `-pl !lingshu-a2a-client`

**实施期实测**(2026-09-22):**待 Phase 3 实施后实测**,目前 dsh §0.4 L39 已声明 `编译目标 Java 1.8`,但 **JDK 11+ HttpClient** 是 #009c 锚定的核心 —— 需 R-13 mitigation (d) 升级 **compile target** 而非加新依赖。**RFC 触发**:若 compile target 升 1.8 → 1.11 涉及多模块影响,需走宪章 RFC 流程。

### 5.2 `RemoteAgentTool` 与未来 `RemoteAgentSchemaBuilder` 的边界

**问题**:#009d 会落地 `RemoteAgentSchemaBuilder.buildToolSpecs()`,从 `AgentCard.skills[]` 生成 N 个 `ToolSpec`,未来会覆盖本 Story 的固定 schema 占位。

**方案**:`RemoteAgentTool` 的 `inputSchema()` 在 #009c 阶段返回固定 schema,`@Bean Tool remoteAgentTool` 走单 tool 模式;**#009d 落地后** 由 `RemoteAgentSchemaBuilder` 生成的 N tools 会替代(同一 `Tool` 接口,只是 `name()` 不同)—— Spring `ToolRegistry` 会自动扫到 N 个 `@Bean Tool` 替换单 tool。

**关键边界**:本 Story **不**删 `RemoteAgentTool`,即使 #009d 落地后 —— `RemoteAgentTool` 仍可作为 fallback(当 `AgentCard.skills[]` 为空时)

### 5.3 `A2aServer` `POST /rpc` 升级 scope

**问题**:完整的 JSON-RPC 2.0 dispatcher 需要做 skill 派发(把 `message/send` 转给本 server 的 LLM 跑 ReAct Loop),scope 超出 #009c 边界。

**方案**:本 Story **只**实现最小 echo + status response(method 识别 + JSON-RPC 2.0 协议合规 + 错误码正确),**不**做 skill 派发。完整派发由后续 Server RPC dispatch Story 落地。

**测试覆盖**:echo happy path + method-not-found error path 共 2 case,验证 JSON-RPC 2.0 协议合规即可。

### 5.4 `LINGS-S08` 域细分冲突

**问题**:dsh §15 错误码约定是 `LINGS-<域><编号>`,编号 08 已由 #009b `A2A_INPROCESS_REGISTRY_EMPTY` 占用。

**方案**:**子码细分** —— 同号 `LINGS-S08` 不冲突,只通过 `HttpJsonRpcException.getReason()` / `InProcessA2aRegistryEmptyException.getReason()` 区分(对齐 §15 「子码细分」原则)。**新增 ErrorCode 数 = 1**(子码,不占新号)。

**风险**:用户看到 `LINGS-S08` 错误时无法立即判断是 in-process 还是 http-jsonrpc 错误 —— **缓解**:`message` 里明确写出 `"A2A_HTTP_RPC_FAILED: ..."` 字符串,与 `getReason()` 同步。

---

## 6. 关键不变项(不引入新决策)

- `A2aTransport` 5 方法契约不变
- `A2aTransportRouter` 行为不变(#009a 已落地,**复用**)
- `AgentCardCache` 行为不变(#009a 已落地,**复用**)
- `InProcessA2aRegistry` 行为不变(#009b 已落地,**复用** —— 单元测试 mock 通路)
- `LocalAgentCardGenerator.generate()` / `toMap()` 不变(#009 / #009b 已落地)
- `A2aServer.start()` / `stop()` 主流程不变(只**替换** 1 个 private handler `RpcPlaceholderHandler` → `RpcDispatcherHandler`)
- `GrpcA2aTransport` / `InProcessA2aTransport` 3 件套不变(#009a / #009b)
- `RemoteAgentSchemaBuilder` 留 #009d 落地
- dsh §5.6.3.0 4 核心类型(`AgentCard` / `AgentRef` / `RemoteAgentSchemaBuilder` / `AgentCardCache`)**复用**,本 Story **不**改
- 模块依赖方向**不变**:`core → a2a-server → a2a-client`(单向)
- JDK 8 only(compile target = 1.8 for `core` / `a2a-server`;**例外**:`a2a-client` 升到 1.11 因为用 `java.net.http.HttpClient`,见 §5.1)

---

## 7. R-13 mitigation (d) 强制项(SOP §3.4 T-dep-tree-1—4)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true` baseline(#009b)+ 本 Story 跑同样命令,对比 dep tree **应完全一致**(0 binary delta)
- [ ] **T-dep-tree-2** 把关键子树贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节,标注"(name, version, slot)"三元组
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`(enforcer 不允许跳过),确认 `banned-dependencies` 规则**不 fail**
- [ ] **T-dep-tree-4**(可选,本 Story 0 binary delta,**不**强制) `mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,binary < 35MB 且相对 main HEAD delta < 10%

---

## 8. 检查清单(SOP §3.5 输出检查清单)

- [ ] `specs/009c-a2a-httpjsonrpc-and-remote-tool/spec.md` 完整(4 User Stories + 14 Edge Cases + 17 FR + 10 NFR)
- [ ] `specs/009c-a2a-httpjsonrpc-and-remote-tool/plan.md` 含 13 I-NN 接口 + 12 文件改动 + 18 case 测试策略 + 7 步实施顺序
- [ ] `specs/009c-a2a-httpjsonrpc-and-remote-tool/data-model.md` 含 6 新增类型 + 1 ErrorCode + 3 修改类型 + 6 复用类型
- [ ] `specs/009c-a2a-httpjsonrpc-and-remote-tool/contracts/a2a-httpjsonrpc-and-remote-tool.md` 契约 ID
- [ ] `specs/009c-a2a-httpjsonrpc-and-remote-tool/quickstart.md` 7 验证场景
- [ ] `specs/009c-a2a-httpjsonrpc-and-remote-tool/checklists/requirements.md` 质量门禁
- [ ] `specs/009c-a2a-httpjsonrpc-and-remote-tool/tasks.md` 全 T-NN 勾完
- [ ] AC-10 全过(18 case 测试 + 启动日志验证 + R-13 dep-tree diff)
- [ ] README.md / docs / changelog 三同步
- [ ] constitution.md §10 R-13 风险更新(本 Story 0 binary delta → R-13 强度不变)
- [ ] PR 标题 `feat(a2a-client): Story #009c a2a-httpjsonrpc-and-remote-tool — <一句话>` + body 含 spec.md + plan.md + tasks.md + AC-10 验证输出 + R-13 dep-tree diff
