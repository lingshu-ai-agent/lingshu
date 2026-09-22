# Tasks: Story #009c a2a-httpjsonrpc-and-remote-tool

**Input**: Design documents from `/specs/009c-a2a-httpjsonrpc-and-remote-tool/`
- spec.md(4 User Stories US1—US4 + 14 Edge Cases + FR-001—FR-017 + NFR-001—NFR-010)
- plan.md(13 I-NN 接口变更 + 5 新增 + 3 修改 + 6 测试 + 18 case + 7 步实施顺序 + R-13 mitigation (d) 5 步)
- data-model.md(5 新增类型 + 1 ErrorCode 子码 + 3 修改类型 + 6 复用类型)
- contracts/a2a-httpjsonrpc-and-remote-tool.md(4 契约 ID;3 新增 + 1 影响 + 0 修改)
- quickstart.md(8 验证场景 — AC-10 关联 + US1—US4 + EC-1—EC-14 + R-13 mitigation (d))
- checklists/requirements.md(12 章节质量门禁)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#009 + #009a + #009b 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `A2aTransportRouter`[#009a] / `AgentCardCache`[#009a] / `InProcessA2aRegistry`[#009b] / `AgentConfig.A2a host+port+grpcTarget+cardTtl`[#009a] / `LocalAgentCardGenerator`[#009] / `A2aServer`[#009] / `GrpcA2aTransport 3 件套`[#009a] / `InProcessA2aTransport 3 件套`[#009b] / `Tool` / `ToolExecutor` / `ToolResult` 等基础设施)

**Tests**: Required per FR-001—FR-017 + 10 NFR + 14 Edge Cases。**18 case** = 14 L1 Unit + 4 L2 Slice。

**Constitution**: v1.0 — §1 #8 Slot 选用 / §1 #9 Plugin 发现 / §1 #11 默认实现位置 / §2 13 依赖锁定(R-13 **+0 新依赖**)/ §4 错误码约定(1 新增子码 ErrorCode LINGS-S08 A2A_HTTP_RPC_FAILED,与 #009b A2A_INPROCESS_REGISTRY_EMPTY 同号细分)/ §5 7 层金字塔(18 case 覆盖)/ §10 R-13 强度最弱(0 binary delta)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3 / US4)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + capture Story #009b dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 a2a-client 1.11 / 其他 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #009b dependency baseline: `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009c-pre.txt`(期望含 #009a 已落地的 grpc-stub + protobuf-java + os-maven-plugin + protobuf-maven-plugin + #009b 0 新增,**本 Story 应完全一致**)
- [ ] T003 Verify current branch is `story-009c-a2a-httpjsonrpc-and-remote-tool` via `git branch --show-current`(从 main 拉新分支 `git checkout -b story-009c-a2a-httpjsonrpc-and-remote-tool` 已在前面 turn 完成)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test` exits 0(Story #001—#009 + #009a + #009b tests all green — pre-implementation sanity,已在前面 turn 实测 235 case 全过)

**Checkpoint**: Setup ready — code modifications can begin。

---

## Phase 2: Foundational — `AgentConfig.A2a` 加 2 字段 + `defaults()` 改写(US1 + US2 基础)

**Purpose**: Establish HTTP-JSON-RPC configuration **before** any HttpJsonRpcA2aTransport wiring

**⚠️ CRITICAL**: 后续所有 US(US1 / US2 / US3 / US4 + EC-1—EC-14)都依赖 `AgentConfig.A2a.httpBaseUrl` + `callTimeout` 字段,此 phase 必须先完成

- [ ] T005 [P0] [US1+US2] Modify `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`:
  - 在 `A2a` 内嵌类(L278—L297)追加 2 字段:
    ```java
    /** Story #009c: HTTP-JSON-RPC server base URL; default {@code "http://localhost:8080"} (aligns with A2aServer host=0.0.0.0 + port=8080). */
    String httpBaseUrl;
    /** Story #009c: HTTP client call timeout; default {@code Duration.ofSeconds(30)}. */
    java.time.Duration callTimeout;
    ```
  - 改写 `defaults()` 静态方法(L294-296)返 `new A2a("0.0.0.0", 8080, "localhost:50051", java.time.Duration.ofMinutes(5), "http://localhost:8080", java.time.Duration.ofSeconds(30))` —— 6 字段构造(host + port + grpcTarget + cardTtl + **httpBaseUrl + callTimeout**)
  - 字段顺序与 §1.7(§5.6.3.1 文档)对齐:host → port → grpcTarget → cardTtl → httpBaseUrl → callTimeout
  - 关键不变项:其他模块**不**修改 — `AgentConfig.A2a` 用 `@Value` Lombok 自动生成构造器,字段顺序变化只影响 `defaults()` 1 处使用,**不**影响外部 `cfg.getA2a()` 读访问

- [ ] T006 [P0] [US1+US2] Modify `lingshu-a2a-client/pom.xml`:
  - 在 `<properties>` 或 `<build><plugins>` 段覆盖 compile target 到 **1.11**(仅本模块,其他模块继承父 POM 的 1.8):
    ```xml
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-compiler-plugin</artifactId>
          <configuration>
            <source>1.11</source>
            <target>1.11</target>
          </configuration>
        </plugin>
      </plugins>
    </build>
    ```
  - 上方加 Javadoc 注释:`Module compile target override: #009c requires java.net.http.HttpClient (JDK 11+). Only lingshu-a2a-client module; lingshu-core / lingshu-a2a-server still JDK 1.8. See plan.md §5.1.`

- [ ] T007 [P0] 验证 Phase 2: `mvn -pl lingshu-core,lingshu-a2a-client compile` exit 0(compile target 微调成功 + AgentConfig 字段扩展不破坏现有编译)

**Checkpoint**: Phase 2 ready — `AgentConfig.A2a` 6 字段可用,`lingshu-a2a-client` 编译目标 1.11。

---

## Phase 3: User Story 1 + 2 — `HttpJsonRpcA2aTransport` 3 件套 + Provider(完整 5 方法)

**Purpose**: Implement 3-piece mode (Transport class + Provider class + AutoConfiguration) with full 5-method JSON-RPC 2.0 contract

- [ ] T008 [P0] [US1] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java`:
  - `public class HttpJsonRpcA2aTransport implements A2aTransport`
  - 字段(全 `final`):
    ```java
    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcA2aTransport.class);
    private final String httpBaseUrl;      // 末尾 / 已 trim
    private final ObjectMapper json;        // Jackson(已随 Spring Boot BOM 引入)
    private final AgentCardCache cardCache; // #009a 复用
    private final Duration callTimeout;
    private final HttpClient http;          // JDK 17 内置 java.net.http.HttpClient
    ```
  - 构造器:`HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json, AgentCardCache cardCache, Duration callTimeout)`
    - 4 字段 null-check 抛 `IllegalArgumentException`(`Objects.requireNonNull`)
    - `httpBaseUrl` 末尾 `/` trim:`httpBaseUrl.endsWith("/") ? httpBaseUrl.substring(0, httpBaseUrl.length() - 1) : httpBaseUrl`
    - `http = HttpClient.newBuilder().connectTimeout(callTimeout).build();`(JDK 17 内置,线程安全)
  - `fetchCard(agentName)`:
    1. `Map<String, Object> cached = cardCache.get(agentName);`
    2. `if (cached != null) { log.debug("fetchCard({}) cache hit", agentName); return cached; }`
    3. `try { HttpRequest req = HttpRequest.newBuilder(URI.create(httpBaseUrl + "/.well-known/agent.json")).timeout(callTimeout).GET().build(); HttpResponse<String> resp = http.send(req, BodyHandlers.ofString()); if (resp.statusCode() != 200) { cardCache.putNegative(agentName); throw new HttpJsonRpcException("fetchCard HTTP " + resp.statusCode() + ": " + resp.body(), ...); } Map<String, Object> map = json.readValue(resp.body(), Map.class); cardCache.put(agentName, map); return map; } catch (Exception e) { cardCache.putNegative(agentName); throw new HttpJsonRpcException("fetchCard failed for '" + httpBaseUrl + "': " + e.getMessage(), e); }`
  - `submit(agentName, skill, inputJson)`:
    1. 构造 body `{"jsonrpc":"2.0","id":"<UUID>","method":"message/send","params":{"agentName":"<X>","skill":"<Y>","inputJson":"<Z>"}}`(Jackson `ObjectNode`)
    2. POST `/rpc`,parse response:有 `result` → `ToolResult.toolSuccess(json.writeValueAsString(result))`;有 `error` → 抛 `HttpJsonRpcException("submit failed: <error.message>", ...)`(FR-015)
  - `get(taskId)`:
    1. 构造 body `{"jsonrpc":"2.0","id":"<UUID>","method":"tasks/get","params":{"id":"<taskId>"}}`
    2. POST `/rpc`,parse `result.status`(COMPLETED/FAILED/CANCELED/RUNNING/PENDING)→ 对应 `ToolResult`
  - `cancel(taskId)`:
    1. 构造 body `{"jsonrpc":"2.0","id":"<UUID>","method":"tasks/cancel","params":{"id":"<taskId>"}}`
    2. POST `/rpc`,parse `result.acknowledged` 返 `boolean`;HTTP 5xx → 返 `false`(EC-8 best-effort)
  - `subscribe(taskId, onEvent)`:
    1. `while (!Thread.interrupted()) { Thread.sleep(1000L); ToolResult tr = get(taskId); onEvent.accept(parseMap(tr)); String status = tr.getStatus().name(); if (status.equals("COMPLETED") || status.equals("FAILED") || status.equals("CANCELED")) return; }`(FR-005 + FR-010 + EC-14)
  - Nested class `HttpJsonRpcException extends LingshuException`:
    ```java
    public static class HttpJsonRpcException extends LingshuException {
      public HttpJsonRpcException(String message, String hint) {
        super("LINGS-S08", "A2A_HTTP_RPC_FAILED", message, hint, null);
      }
      public HttpJsonRpcException(String message, String hint, Throwable cause) {
        super("LINGS-S08", "A2A_HTTP_RPC_FAILED", message, hint, cause);
      }
    }
    ```
  - Javadoc 完整描述 dsh §5.6.3.1 L2995-3172 锚定 + 5 方法契约 + JSON-RPC 2.0 + LINGS-S08 子码 + 与 #009a/#009b 3 件套的差异(5 方法完整 vs grpc fetchCard-only vs in-process fetchCard-only)

- [ ] T009 [P0] [US1+US2] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProvider.java`:
  - `@Component public class HttpJsonRpcA2aTransportProvider implements Providers.A2aTransportProvider`
  - `name()`:return `"http-jsonrpc-1.0.0"`(Javadoc:禁止与 `"grpc-1.0.0"` / `"in-process-1.0.0"` 冲突)
  - `priority()`:return 10
  - `version()`:return `"1.0.0"`
  - `create(cfg)`:
    ```java
    String httpBaseUrl = resolveHttpBaseUrl(cfg);
    Duration cardTtl = resolveCardTtl(cfg);
    Duration callTimeout = resolveCallTimeout(cfg);
    log.info("[A2aTransport] creating HttpJsonRpcA2aTransport: httpBaseUrl={} cardTtl={} callTimeout={}", httpBaseUrl, cardTtl, callTimeout);
    return new HttpJsonRpcA2aTransport(httpBaseUrl, new ObjectMapper(), new AgentCardCache(cardTtl), callTimeout);
    ```
  - 私有方法 `resolveHttpBaseUrl(cfg)`:try-catch `NoSuchMethodError` 兼容 pre-#009c `AgentConfig.A2a`(直接读 `cfg.getA2a().getHttpBaseUrl()`,fallback `http://localhost:8080`)
  - 私有方法 `resolveCardTtl(cfg)`:同 #009b 模式,fallback `Duration.ofMinutes(5)`
  - 私有方法 `resolveCallTimeout(cfg)`:fallback `Duration.ofSeconds(30)`

- [ ] T010 [P0] [US2+US3] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`:
  - `@AutoConfiguration public class HttpJsonRpcA2aTransportAutoConfiguration`
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
  - Javadoc 说明双 @Bean 设计理由(规避 CLAUDE.md §11 #4 ≤ 5 核心文件预算 —— RemoteAgentToolAutoConfiguration 已合并到本 AutoConfiguration 的 2 个 @Bean 中)

- [ ] T011 [P0] [US2+US3] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentTool.java`:
  - `public class RemoteAgentTool implements Tool`(本类**不**标 `@Component`,由 `HttpJsonRpcA2aTransportAutoConfiguration.@Bean` 暴露)
  - 字段(全 `final`):
    ```java
    private final A2aTransport transport;
    private final ObjectMapper json;
    ```
  - 构造器:`RemoteAgentTool(A2aTransport transport, ObjectMapper json) { Objects.requireNonNull(transport, "transport"); Objects.requireNonNull(json, "json"); this.transport = transport; this.json = json; }`
  - `name()`:return `"remote_agent"`(固定字符串)
  - `description()`:return `"Invoke a skill on a remote A2A agent. Input: {\"agentName\":\"<X>\", \"skill\":\"<Y>\", \"input\": {...}}."`
  - `inputSchema()`:返固定 schema(FR-013)—— 静态 `ObjectNode`:`{"type":"object","properties":{"agentName":{"type":"string","description":"Target remote agent Identity.name"},"skill":{"type":"string","description":"Skill id to invoke"},"input":{"type":"object","description":"JSON args matching the skill's input schema"}},"required":["agentName","skill","input"]}`
  - `execute(call, ctx)`:
    1. `JsonNode input = call.getInput();`
    2. `String agentName = input.get("agentName").asText(); String skill = input.get("skill").asText(); JsonNode inputArgs = input.get("input");`
    3. `String inputJson = json.writeValueAsString(inputArgs);`
    4. `try { return transport.submit(agentName, skill, inputJson); } catch (HttpJsonRpcException e) { return ToolResult.builder().status(ToolResult.Status.ERROR).content("Remote agent call failed: " + e.getMessage()).isError(true).build(); }`(FR-007 + EC-11 双层兜底)
  - Javadoc 标注:`ToolRegistry` 由 Spring Boot 自动扫 `@Bean Tool` 注册;`name()="remote_agent"` 固定,agentName + skill 由 input JSON 传

- [ ] T012 [P0] [US2+US3] Modify `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
  - **追加**第三行(不覆盖 #009a / #009b 已落地两行):`ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration`
  - 文件末必须有 trailing newline

- [ ] T013 [P0] 验证 Phase 3: `mvn -pl lingshu-a2a-client compile` exit 0 + `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test`(已有 235 case 不应 regress)

**Checkpoint**: Phase 3 ready — `HttpJsonRpcA2aTransport` 3 件套编译过 + `RemoteAgentTool` 编译过 + 已有测试不 regress。

---

## Phase 4: User Story 4 — `A2aServer` `POST /rpc` 升级为最小 JSON-RPC 2.0 dispatcher

**Purpose**: Replace RpcPlaceholderHandler(501)with RpcDispatcherHandler(JSON-RPC 2.0 spec-compliant)

- [ ] T014 [P0] [US4] Modify `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`:
  - 找到现有 `RpcPlaceholderHandler`(private class 实现 `HttpHandler`),整体替换为 `RpcDispatcherHandler`:
    ```java
    private static class RpcDispatcherHandler implements HttpHandler {
      private static final ObjectMapper JSON = new ObjectMapper();

      @Override
      public void handle(HttpExchange ex) throws IOException {
        try {
          byte[] bodyBytes = ex.getRequestBody().readAllBytes();
          JsonNode req = JSON.readTree(bodyBytes);
          String jsonrpc = req.path("jsonrpc").asText("2.0");
          String id = req.path("id").asText("");
          String method = req.path("method").asText("");
          JsonNode params = req.path("params");

          ObjectNode resp = JSON.createObjectNode();
          resp.put("jsonrpc", jsonrpc);
          resp.put("id", id);

          switch (method) {
            case "message/send":
              // echo + status response
              ObjectNode result = JSON.createObjectNode();
              result.put("status", "COMPLETED");
              result.put("taskId", UUID.randomUUID().toString());
              result.put("resultJson", "{\"echo\":" + params.toString() + "}");
              resp.set("result", result);
              break;
            case "tasks/get":
              ObjectNode getResult = JSON.createObjectNode();
              getResult.put("status", "COMPLETED");
              getResult.put("taskId", params.path("id").asText(""));
              getResult.put("resultJson", "{}");
              resp.set("result", getResult);
              break;
            case "tasks/cancel":
              ObjectNode cancelResult = JSON.createObjectNode();
              cancelResult.put("acknowledged", true);
              resp.set("result", cancelResult);
              break;
            default:
              ObjectNode error = JSON.createObjectNode();
              error.put("code", -32601);
              error.put("message", "Method not found: " + method);
              resp.set("error", error);
              break;
          }

          byte[] out = JSON.writeValueAsBytes(resp);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, out.length);
          ex.getResponseBody().write(out);
          ex.getResponseBody().close();
        } catch (Exception e) {
          byte[] err = ("{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32700,\"message\":\"Parse error: " + e.getMessage().replace("\"", "\\\"") + "\"}}").getBytes(StandardCharsets.UTF_8);
          ex.getResponseHeaders().add("Content-Type", "application/json");
          ex.sendResponseHeaders(200, err.length);
          ex.getResponseBody().write(err);
          ex.getResponseBody().close();
        }
      }
    }
    ```
  - 替换 `RpcPlaceholderHandler()` 实例化(若有)为 `new RpcDispatcherHandler()`
  - Javadoc 标注:`scope-limited echo + status response —— **不**做真正的 skill 派发;留后续 Server RPC dispatch Story 完整派发`

- [ ] T015 [P0] 验证 Phase 4: `mvn -pl lingshu-a2a-server compile` exit 0 + 已有测试不 regress(若 A2aServer 启动测试会跑 POST /rpc 路径,验证不会失败)

**Checkpoint**: Phase 4 ready — `POST /rpc` 端点升级为最小 JSON-RPC 2.0 dispatcher,3 method 识别 + 错误码 -32601 + echo response。

---

## Phase 5: Tests — 5 测试文件 + 18 case

**Purpose**: L1 Unit + L2 Slice 验证所有 US + EC

- [ ] T016 [P0] [US1] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportTest.java`:
  - L1 Unit + JDK 内置 `com.sun.net.httpserver.HttpServer` mock 模拟 remote agent,5 case:
    1. `testFetchCardHappyPath` — mock server 返回 `{"name":"alice",...}`,fetchCard 返 Map + 进 cache(VS-1 + US-1 AC-1.1)
    2. `testFetchCardHitsCache` — 第二次 fetchCard 走 cache 不发 HTTP(VS-1 + NFR-001)
    3. `testFetchCardMissThrowsLingsS08` — mock server 返 503,fetchCard 抛 LINGS-S08 + 负缓存(VS-2 + EC-4 + EC-6)
    4. `testSubmitGetCancel` — submit/get/cancel 3 method happy path(JSON-RPC 2.0 body 验证 + response parse)(VS-1)
    5. `testSubscribePolling` — subscribe 每 1s poll 一次,mock server 第 2 次返 COMPLETED,onEvent 收到 2 次 + subscribe return(VS-1 + AC-1.5 + EC-14)
  - `@BeforeEach` 起 `HttpServer`(`http://127.0.0.1:0`)+ `@AfterEach server.stop(0)`

- [ ] T017 [P0] [US2] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportProviderTest.java`:
  - L1 Unit, 3 case:
    1. `testNameVersionPriority` — `name()="http-jsonrpc-1.0.0"` + `priority()=10` + `version()="1.0.0"`(VS-3 + FR-003)
    2. `testCreateHappyPath` — `create(cfg)` 返 HttpJsonRpcA2aTransport 实例,持有 httpBaseUrl = "http://localhost:8080" + callTimeout = 30s + cardTtl = 5min(US-2 AC-2.1 + AC-2.2 + AC-2.3)
    3. `testCreateReadsCustomConfig` — yml `agent.a2a.httpBaseUrl=http://example.com:9090` + `agent.a2a.callTimeout=60s` → create 返回的 HttpJsonRpcA2aTransport 持有相应字段(FR-008)

- [ ] T018 [P0] [US3] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/RemoteAgentToolTest.java`:
  - L1 Unit + mock A2aTransport(Mockito),4 case:
    1. `testExecuteHappyPath` — execute 输入 `{agentName:"alice", skill:"echo", input:{x:1}}` → transport.submit("alice", "echo", `{"x":1}`) 调 1 次 + 透传 ToolResult(VS-4 + FR-007)
    2. `testExecuteTransportThrowsLingsS08` — transport.submit 抛 LINGS-S08 → execute 返回 ToolResult.toolError("Remote agent call failed: ...")(VS-5 + EC-11)
    3. `testInputSchemaFixedShape` — inputSchema() 返 JsonNode 含 type/properties/required 3 字段,properties 含 agentName/skill/input(FR-013)
    4. `testDescriptionNotBlank` — description() 非 null 非空(FR-006)

- [ ] T019 [P0] [US2+US3+US4] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java`:
  - L2 Slice(Spring `@SpringBootTest`),3 case:
    1. `testMultiProviderCoexistence` — `router.available()` 包含 `"grpc-1.0.0"` + `"in-process-1.0.0"` + `"http-jsonrpc-1.0.0"`(VS-6 + US-4 AC-4.1 + AC-4.4)
    2. `testResolveHttpJsonRpc` — `router.resolve("http-jsonrpc-1.0.0", cfg)` 返 HttpJsonRpcA2aTransport(US-4 AC-4.2)
    3. `testRemoteAgentToolBeanWiring` — Spring 容器有 `Tool remoteAgentTool` Bean,`toolRegistry.lookup("remote_agent") != null`(US-3 AC-3.5 + NFR-007)
  - classes = `{GrpcA2aTransportAutoConfiguration.class, InProcessA2aTransportAutoConfiguration.class, HttpJsonRpcA2aTransportAutoConfiguration.class, A2aTransportRouter.class, ToolRegistry.class}`

- [ ] T020 [P0] [US4] Create `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerRpcEndpointTest.java`:
  - L2 Slice(Spring `@SpringBootTest` + `agent.a2a.port=0`),2 case:
    1. `testPostRpcMessageSendEcho` — POST `/rpc` body `{"jsonrpc":"2.0","id":"1","method":"message/send","params":{"input":"hello"}}` → 200 + response `result.taskId` 非空 + `result.status="COMPLETED"`(VS-7 + I-09)
    2. `testPostRpcUnknownMethodError` — POST `/rpc` body `{"jsonrpc":"2.0","id":"2","method":"unknown"}` → 200 + response `error.code=-32601` + `error.message="Method not found: unknown"`(VS-7 + I-09)
  - 用 JDK `HttpClient` 发 POST + parse response

- [ ] T021 [P0] 验证 Phase 5: `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test` 期望 235 + 18 = 253 case 全过

**Checkpoint**: Phase 5 ready — 所有 18 case 测试通过 + 已有测试不 regress。

---

## Phase 6: AC-10 关联验证 + 启动日志验证

**Purpose**: End-to-end verification of A2A client 3-Provider coexistence (grpc-1.0.0 + in-process-1.0.0 + http-jsonrpc-1.0.0)

- [ ] T022 [P1] [US4] 启动日志验证:`mvn -pl lingshu-examples exec:java -Dexec.mainClass="ai.lingshu.examples.Main" -Dexec.args="--config src/main/resources/application.yml"`(可选,若有 demo)
  - 期望日志包含:`[A2aTransport] resolved 3 provider(s) [contract v1.0.0]:` + `✓ grpc-1.0.0 v1.0.0 -> GrpcA2aTransportProvider [priority=10]` + `✓ in-process-1.0.0 v1.0.0 -> InProcessA2aTransportProvider [priority=10]` + `✓ http-jsonrpc-1.0.0 v1.0.0 -> HttpJsonRpcA2aTransportProvider [priority=10]`
  - 若无 demo,跳过此 task(本 Story 不强制)

- [ ] T023 [P1] [US1] 单元测试场景下 http-jsonrpc fetchCard latency 验证(`HttpJsonRpcA2aTransportTest` 加 1 个 `@Timeout(value = 5, unit = TimeUnit.SECONDS)` 测试,确保 fetchCard 在 mock server 场景下 5s 内完成)

**Checkpoint**: Phase 6 ready — 启动日志 + 性能验证通过(若有 demo)。

---

## Phase 7: R-13 mitigation (d) baseline 镜像

**Purpose**: Verify 0 binary delta + enforcer pass

- [ ] T024 [P0] 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009c-post.txt`
- [ ] T025 [P0] 跑 `diff /tmp/deps-009c-pre.txt /tmp/deps-009c-post.txt`,期望**无输出**(完全一致)
- [ ] T026 [P0] 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`,期望 BUILD SUCCESS + banned-dependencies 规则不 fail
- [ ] T027 [P0] (可选,本 Story 0 binary delta) 验证 CI matrix JDK 8 job 是否需要跳过 a2a-client(若 `.github/workflows/maven.yml` 用 `-pl lingshu-core,lingshu-a2a-server` 则无需改;若用 `-pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client` 则需加 `-pl !lingshu-a2a-client`)

**Checkpoint**: Phase 7 ready — R-13 mitigation (d) baseline 镜像通过。

---

## Phase 8: Doc Sync + Commit + PR

**Purpose**: Sync docs + commit + PR + CI

- [ ] T028 [P1] Modify `README.md`(若有 §5.6 段)加 `http-jsonrpc-1.0.0` 一行 + 启动日志示例更新(2 → 3 providers)
- [ ] T029 [P1] Modify `dsh_agent_design.md` §13 changelog 加 v1.5.37 行:Story #009c 完成 + HttpJsonRpcA2aTransport 3 件套 + RemoteAgentTool + 0 binary delta + compile target 1.11(a2a-client)
- [ ] T030 [P1] Modify `constitution.md` §10 R-13 风险状态:本 Story 0 binary delta → R-13 强度不变;新增 R-XX 备注:compile target 升级仅限 a2a-client 模块,其他模块仍 JDK 1.8
- [ ] T031 [P0] `git add -A && git commit -m "feat(a2a-client): Story #009c a2a-httpjsonrpc-and-remote-tool — HttpJsonRpcA2aTransport 5 方法完整 3 件套 + RemoteAgentTool + LINGS-S08 子码 + R-13 mitigation (d) baseline 镜像"`
  - Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
- [ ] T032 [P0] `git push origin story-009c-a2a-httpjsonrpc-and-remote-tool`
- [ ] T033 [P0] `gh pr create --title "feat(a2a-client): Story #009c a2a-httpjsonrpc-and-remote-tool — HttpJsonRpcA2aTransport 5 方法完整 3 件套 + RemoteAgentTool + LINGS-S08 子码 + 0 binary delta" --body "..."`(贴 spec.md + plan.md + tasks.md + AC-10 验证输出 + R-13 dep-tree diff 到 PR body)
- [ ] T034 [P0] 等 CI 全过后(若需要)手动 merge(用户授权后)

**Checkpoint**: Phase 8 ready — PR 合并 + 文档同步 + Story #009c 完成。

---

## 关键不变项(不引入新决策)

- `A2aTransport` 5 方法契约不变
- `A2aTransportRouter` 行为不变(#009a 已落地,**自动**接受 3 Provider 注入)
- `AgentCardCache` 行为不变(#009a 已落地,**复用**)
- `InProcessA2aRegistry` 行为不变(#009b 已落地,**复用** —— 单元测试 mock 通路)
- `AgentConfig.A2a` 字段构造改写(#009c 加 httpBaseUrl + callTimeout,关键不变项是字段**只追加不删除**)
- `SlotRouter<P, T>` 父类不变
- `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration` 不变(#009a)
- `InProcessA2aTransport` / `InProcessA2aTransportProvider` / `InProcessA2aTransportAutoConfiguration` 不变(#009b)
- `LocalAgentCardGenerator.generate()` / `toMap()` 不变(#009 / #009b 已落地)
- `A2aServer.start()` / `stop()` 主流程不变(只**替换** 1 个 private handler `RpcPlaceholderHandler` → `RpcDispatcherHandler`)
- `RemoteAgentSchemaBuilder` 留 #009d 落地
- JDK 8 only:不用 `var` / `record` / `sealed`,用 `Collections.unmodifiableMap` + `ConcurrentHashMap` + `LinkedHashMap` + `Arrays.asList`(compile target = 1.11 仅 `a2a-client` 例外,因为用 `java.net.http.HttpClient`)

---

## Story 边界检查(CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件新增 | ≤ 5 | 4(`HttpJsonRpcA2aTransport` / `Provider` / `AutoConfiguration`(双 Bean)+ `RemoteAgentTool`)+ 1 nested class(`HttpJsonRpcException` 放 `HttpJsonRpcA2aTransport` 内)| ✅ 4 + 1 nested = 5 等价 |
| 核心文件修改 | 不计入边界 | 3 类(`AgentConfig.A2a` 加 2 字段 + `defaults()` 改写 / `A2aServer` 替换 1 个 private handler / `AutoConfiguration.imports` 追加 1 行 / `lingshu-a2a-client/pom.xml` compile target 1.8 → 1.11)| 边界内(微小改动)|
| 测试文件 | 不计入边界 | 5(18 case)| — |
| ErrorCode 引入 | ≤ 3 | 1 子码(`LINGS-S08 A2A_HTTP_RPC_FAILED`,与 #009b `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY` 同号细分)| ✅ |
| 新 Maven 依赖 | R-13 mitigation (d) | **0** | ✅ 强度最弱 |
| 改动模块 | 主要 lingshu-a2a-client(5 新增 + 1 nested)+ a2a-server(1 替换 handler)+ lingshu-core(AgentConfig 加 2 字段) | ✓ | ✅ |
| compile target 微调 | RFC 触发 | a2a-client 1.8 → 1.11(0 binary delta,因 JDK 17 HttpClient) | ⚠️ RFC 触发待 Phase 8 评估 |

---

## 依赖图

```
Phase 1 (Setup)
   ↓
Phase 2 (Foundational — AgentConfig + pom.xml) ─── 阻塞所有 US
   ↓
Phase 3 (US1 + US2 — 3 件套 + RemoteAgentTool) ── 可与 Phase 4 部分并行
   ↓
Phase 4 (US4 — A2aServer /rpc 升级) ── 独立
   ↓
Phase 5 (Tests — 5 文件 + 18 case) ── 依赖 Phase 3 + Phase 4
   ↓
Phase 6 (AC-10 验证 + 启动日志)
   ↓
Phase 7 (R-13 mitigation (d) baseline 镜像)
   ↓
Phase 8 (Doc Sync + Commit + PR)
```

---

## 任务数统计

| Phase | 任务数 | 关键产出 |
|---|---|---|
| Phase 1 Setup | 4 | R-13 baseline 镜像 + 环境验证 |
| Phase 2 Foundational | 3 | AgentConfig.A2a 加 2 字段 + a2a-client pom.xml compile target 1.11 |
| Phase 3 US1 + US2 + US3 | 6 | HttpJsonRpcA2aTransport 3 件套 + RemoteAgentTool + imports |
| Phase 4 US4 | 2 | A2aServer POST /rpc 升级 + JSON-RPC 2.0 dispatcher |
| Phase 5 Tests | 6 | 5 测试文件 + 18 case |
| Phase 6 AC-10 | 2 | 启动日志 + 性能验证 |
| Phase 7 R-13 | 4 | 0 binary delta 验证 + enforcer + CI matrix 评估 |
| Phase 8 Doc + PR | 7 | 文档同步 + commit + push + PR + merge |
| **合计** | **34 tasks** | — |

---

## 总结

**全部 34 task 勾完** 即 Story #009c 完成 ✅
