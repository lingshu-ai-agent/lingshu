# Feature Specification: Story #009c a2a-httpjsonrpc-and-remote-tool

**Feature Branch**: `story-009c-a2a-httpjsonrpc-and-remote-tool`
**Created**: 2026-09-22
**Status**: Draft
**Input**: User description: "Story #009c a2a-httpjsonrpc-and-remote-tool —— `lingshu-a2a-client` 模块下 `HttpJsonRpcA2aTransport` + `HttpJsonRpcA2aTransportProvider` + `HttpJsonRpcA2aTransportAutoConfiguration` 「3 件套」,实现 **5 方法完整契约**(对比 #009b 的 fetchCard-only 限定,本 Story 完成 dsh §5.6.3.1 L3018-3130 HttpJsonRpcA2aTransport stub 完整 5 方法实现);+ `RemoteAgentTool`(`@Component implements Tool`,按 `call_<agentName>` 命名转发到 `A2aTransport.submit`)+ `RemoteAgentToolAutoConfiguration`(`@Bean public Tool remoteAgentTool(...)` 接入 ToolRegistry,与 §5.6.1 RemoteAgentToolAutoConfiguration 样板对齐)。**复用** `A2aTransportRouter`(#009a)+ `AgentCardCache`(#009a)+ `InProcessA2aRegistry`(#009b 单元测试 mock 通路)+ `A2aServer`(`GET /.well-known/agent.json` + `POST /rpc` 501 placeholder 由 #009b 升级为 JSON-RPC 2.0 端点)。**0 额外依赖** —— JDK 17 内置 `java.net.http.HttpClient` + `ObjectMapper`(Jackson,已随 Spring Boot BOM 引入)。**LINGS-S08 域细分** —— #009b 用 `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`(InProcessA2aRegistryEmptyException nested class);本 Story 用 `LINGS-S08 A2A_HTTP_RPC_FAILED`(HttpJsonRpcA2aTransport 5 方法抛出的 RPC 失败统一异常)。**不动** `GrpcA2aTransport` / `InProcessA2aTransport` / `RemoteAgentSchemaBuilder`(均留 #009a / #009b / #009d)。锚定 dsh §5.6.3.1 L2995-3172 HttpJsonRpcA2aTransport concrete class + HttpJsonRpcA2aTransportProvider + HttpJsonRpcA2aTransportAutoConfiguration 完整实现 + §5.6.1 RemoteAgentTool / RemoteAgentToolAutoConfiguration + §5.6.3.2 L3174-3320 「3 件套模式」扩展指南。"

**Source Design Doc**: `dsh_agent_design.md` v1.5.36
- §5.6.3 L2395-2480(`A2aTransport` SPI 接口契约 —— 已落地 `lingshu-core/slot/A2aTransport.java`,5 方法 `fetchCard` / `submit` / `get` / `cancel` / `subscribe`)
- §5.6.3.1 L2995-3172(`HttpJsonRpcA2aTransport` concrete class + `HttpJsonRpcA2aTransportProvider` concrete Provider + `HttpJsonRpcA2aTransportAutoConfiguration` —— **本 Story 主要锚定**)
- §5.6.1 L2346-2390(`RemoteAgentTool` + `RemoteAgentToolAutoConfiguration` + 9 个对照表 + `RemoteAgentSchemaBuilder` 接入 ToolRegistry)
- §5.6.3.2 L3174-3320(Grpc / InProcess 「3 件套模式」扩展指南 —— #009b 已落地 InProcess,本 Story 落地 HttpJsonRpc)
- §5.6.4 L3322-3360 SPI 槽位总表(Slot 9 行更新:增加 HttpJsonRpcA2aTransportProvider 状态行,从 #009b 的 2 Provider 升级到 3 Provider)
- §5.6.3.0 L2482-2992(`AgentCard` + `AgentRef` + `RemoteAgentSchemaBuilder` + `AgentCardCache` —— #009a 落地 4 个类型,**复用**)
- §4.10.1 硬规则 2(`ToolExecutor.dispatch()` 5 步流水线 —— `RemoteAgentTool` 接入后走 `dispatch()`,不绕过沙箱/权限/checkpoint)
- §4.7 `PermissionPolicy` / §14 NFR 基线
- §15 LINGS-<域><编号> 错误码约定(S 域已扩到 LINGS-S07 / LINGS-S08,本 Story 用 LINGS-S08 A2A_HTTP_RPC_FAILED 子码 —— **与 #009b A2A_INPROCESS_REGISTRY_EMPTY 子码共存**)
- §10.1 锁定 13 项依赖表(R-13 mitigation (d) 强度最弱:0 binary delta 强制)
- §17 R-13 / R-14 风险登记

---

## 1. Summary

本 Story 是 Story #009 A2A 客户端子系列的第 3 块 —— 在 #009a(GrpcA2aTransport 3 件套 + AgentCardCache + A2aTransportRouter + R-13 mitigation (d) grpc +5MB)+ #009b(InProcessA2aTransport 3 件套 + InProcessA2aRegistry 单例 + R-13 mitigation (d) 0 binary delta)落地后,**补齐第 3 个 Provider HttpJsonRpcA2aTransport + 默认 Provider**(零额外依赖,`java.net.http.HttpClient` + Jackson `ObjectMapper` 已随 Spring Boot BOM 引入)+ **`RemoteAgentTool`** —— 把 A2A 客户端**完整**接入到主 Agent 的 ReAct Loop,使得主 Agent 在 ReAct 循环中能像调用普通 Tool 一样调用 remote agent 的 skill。

**5 方法契约完整落地** —— 对比 #009b 的 InProcessA2aTransport 仅 `fetchCard` 落地(其他 4 方法抛 `UnsupportedOperationException`),本 Story 的 HttpJsonRpcA2aTransport **完整**实现 `submit` / `get` / `cancel` / `subscribe` —— 这是 dsh §5.6.3.1 L3018-3130 stub 的核心承诺(JSON-RPC 2.0 over HTTPS)。`subscribe` 走 **polling tasks/get** 占位实现(每 1s poll 一次)—— JDK 17 HttpClient 不内置 SSE EventSource(JDK 21+ 才内置),v1.0+ 接入 OkHttp EventSource 真正 SSE 推送。

**LINGS-S08 域细分** —— `#009b` 与 `#009c` 共用 `LINGS-S08`(S 域 / Slot-SPI / 编号 08),但通过**子码**区分:
- `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`(由 `InProcessA2aRegistryEmptyException` nested class 携带,#009b 落地)
- `LINGS-S08 A2A_HTTP_RPC_FAILED`(由 `HttpJsonRpcA2aTransport` 5 方法抛出时携带 `#009c 落地`)

错误码前缀同号(`LINGS-S08`)是因为同属 **Slot 9 A2A 客户端** 域;子码通过 nested exception class name 区分(`InProcessA2aRegistryEmptyException` vs `HttpJsonRpcException`)。**新增 ErrorCode 数 = 1**(子码,不占新号)。

**R-13 mitigation (d) 强度最弱** —— 与 #009b 同级(0 binary delta),`java.net.http.HttpClient` JDK 17 内置,`ObjectMapper` Jackson 由 Spring Boot BOM 引入。`mvn dependency:tree -pl lingshu-a2a-client` 应与 #009b baseline **完全一致**。

---

## 2. User Stories

### US-1: HttpJsonRpcA2aTransport 5 方法完整契约(as A2A client library consumer)

**As** LingShu framework contributor integrating with external A2A-compatible services,
**I want** `HttpJsonRpcA2aTransport` to implement all 5 methods of `A2aTransport` via JSON-RPC 2.0 over HTTPS,
**So that** LingShu agents can `fetchCard` / `submit` / `get` / `cancel` / `subscribe` against any A2A-spec-compliant agent without needing a separate `A2aTransport` implementation per remote protocol.

**Acceptance Criteria**:
- `AC-1.1` `HttpJsonRpcA2aTransport.fetchCard(agentName)` returns `Map<String, Object>`(与 InProcess 一致的契约),内部走 `cardCache.get(agentName)` 命中免 HTTP,miss 走 `http.send(HttpRequest GET /.well-known/agent.json)` + Jackson `ObjectMapper.readValue(body, Map.class)` + `cardCache.put(agentName, map)`(VS-1)
- `AC-1.2` `HttpJsonRpcA2aTransport.submit(agentName, skill, inputJson)` returns `ToolResult`(SUCCESS / ERROR / CANCELLED 三态),内部走 `http.send(HttpRequest POST /rpc with JSON-RPC 2.0 body)` + parse `result` 字段 → `ToolResult.toolSuccess(json)` 或 `ToolResult.toolError(json)`;HTTP 5xx 或 JSON-RPC `error` 字段 → 抛 `LINGS-S08 A2A_HTTP_RPC_FAILED`(VS-2)
- `AC-1.3` `HttpJsonRpcA2aTransport.get(taskId)` returns `ToolResult`,内部走 `JSON-RPC method=tasks/get params={id: taskId}`(VS-3)
- `AC-1.4` `HttpJsonRpcA2aTransport.cancel(taskId)` returns `boolean`,内部走 `JSON-RPC method=tasks/cancel params={id: taskId}` + 解析 `acknowledged` 字段(VS-3)
- `AC-1.5` `HttpJsonRpcA2aTransport.subscribe(taskId, onEvent)` 走 polling tasks/get 占位实现 —— 每 1s poll 一次,直到 task 状态为 terminal(COMPLETED / FAILED / CANCELED),每次 poll 结果作为 `Map<String, Object>` 投递给 `onEvent` consumer(VS-4)
- `AC-1.6` 所有 5 方法在 HTTP 5xx / IOException / timeout 时统一抛 `LingshuException("LINGS-S08 A2A_HTTP_RPC_FAILED", ...)` —— **不**直接抛 IOException 给调用方(US-1 + FR-014)
- `AC-1.7` `submit` / `get` 在 JSON-RPC `error` 字段非空时抛 `LINGS-S08 A2A_HTTP_RPC_FAILED` + `error.message` 进 message(US-1 + FR-015)

---

### US-2: HttpJsonRpcA2aTransportProvider + AutoConfiguration(as Spring Boot consumer)

**As** LingShu user configuring the engine via `application.yml`,
**I want** to set `agent.a2aTransport: http-jsonrpc-1.0.0` and have the engine automatically wire up the `HttpJsonRpcA2aTransport`,
**So that** I don't need to manually construct any `A2aTransport` instance or register any Spring Bean.

**Acceptance Criteria**:
- `AC-2.1` `HttpJsonRpcA2aTransportProvider` 实现 `Providers.A2aTransportProvider` 4 方法接口:`name()="http-jsonrpc-1.0.0"`(**禁止**与 `"grpc-1.0.0"` / `"in-process-1.0.0"` 冲突)+ `priority()=10` + `version()="1.0.0"` + `create(cfg)` 返回 `new HttpJsonRpcA2aTransport(httpBaseUrl, jackson, new AgentCardCache(cfg.getA2a().getCardTtl()), callTimeout)`(US-2 + FR-003 + FR-005)
- `AC-2.2` `create(cfg)` 读取 `cfg.getA2a().getHttpBaseUrl()` 作为 `httpBaseUrl`;null/empty → 抛 `LingsConfigException` (LINGS-C02) 失败快(`A2aServer` 启动时同理);默认值 = `http://localhost:8080`(与 #009 `A2aServer` 默认 host=0.0.0.0 + port=8080 对齐)(FR-003 + FR-008)
- `AC-2.3` `create(cfg)` 读取 `cfg.getA2a().getCallTimeout()` 作为 `callTimeout`;null → fallback `Duration.ofSeconds(30)`(FR-008 + NFR-003)
- `AC-2.4` `HttpJsonRpcA2aTransportAutoConfiguration` `@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")`(🆕 v1.5.28 唯一 Bean 名约定 + 与 §5.5 multi-Provider 模式对齐);`new HttpJsonRpcA2aTransportProvider()` 实例化(US-2 + FR-003)
- `AC-2.5` SPI 注册:`META-INF/spring/...imports` 追加第三行(不覆盖 #009a / #009b 已落地两行):
  ```
  ai.lingshu.a2a.client.GrpcA2aTransportAutoConfiguration           ← #009a 已落地
  ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration     ← #009b 已落地
  ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration    ← #009c 追加
  ```
- `AC-2.6` 启动日志含 `resolved 3 provider(s)` + 3 行 ✓ 列表(grpc + in-process + http-jsonrpc)(VS-6)

---

### US-3: RemoteAgentTool 接入 ToolRegistry(as ReAct Loop consumer)

**As** LingShu agent running ReAct Loop with `agent.a2aTransport: http-jsonrpc-1.0.0`,
**I want** to invoke remote agent skills as if they were local Tools (e.g., `call_alice-coding.echo`),
**So that** the LLM can seamlessly chain local tools and remote agent skills without knowing the difference.

**Acceptance Criteria**:
- `AC-3.1` `RemoteAgentTool` 是 `@Component implements Tool`(`name()=固定前缀 "call_"`, `description()="..."`, `inputSchema()={...}`, `execute(call, ctx)` 内部按 `call_<agentName>.<skill>` 解析并转发给 `A2aTransport.submit`)(FR-006 + FR-007)
- `AC-3.2` `RemoteAgentTool` 持有 `A2aTransport transport`(由 `RemoteAgentToolAutoConfiguration.@Bean` 注入);**构造期校验** transport 非 null(FR-006)
- `AC-3.3` `RemoteAgentTool.execute(call, ctx)`:
  1. 解析 `call.getName()` → `agentName`(按 `call_<agentName>` 拆分,若 `agentName` 部分含 `.` 则 `.` 之前的部分为 agentName,`.` 之后为 skillHint;否则整个 `<agentName>` 段为 agentName,**只支持 agentName 不含 `.` 的简化形式** —— 完整 skill 路径在 `call.getInput()` 的 JSON args 里)
  2. 解析 `call.getInput()` → `Map<String, Object>` inputArgs(Jackson `convertValue`)
  3. 从 `ctx` 拿 `tenantId`(Story #006 multi-tenant 兼容);无则空
  4. 调 `transport.submit(agentName, skillHint, jsonRpcInput)` → `ToolResult`;若 transport 抛 `LINGS-S08 A2A_HTTP_RPC_FAILED` → 返回 `ToolResult.toolError(message)`(不绕过 ToolExecutor 的 ToolResult 链路)(FR-006 + FR-009)
- `AC-3.4` `RemoteAgentTool.inputSchema()` 返回固定 schema(本期由 `RemoteAgentToolAutoConfiguration` 启动期生成:`AgentCard.skills[]` 静态填的占位 schema)—— **完整 dynamic schema 由 Story #009d RemoteAgentSchemaBuilder 落地后接管**(FR-006 + FR-013)
- `AC-3.5` `RemoteAgentToolAutoConfiguration` `@AutoConfiguration` + `@Bean public Tool remoteAgentTool(A2aTransport transport)` —— 接入 ToolRegistry(由 Spring Boot 自动扫 `@Bean Tool` 进 ToolRegistry,**不**走 `Providers.XxxProvider` SPI,因为 Tool 本身不在 9 Slot 列表 —— 详见 dsh §5.5「plugin AutoConfiguration 编写约定」+ dsh §6.5 (1) ReadTool 样板)(US-3 + FR-006)

---

### US-4: 3 Provider 同存 + A2aTransportRouter 升级(as framework contributor)

**As** LingShu framework contributor maintaining the multi-Provider A2a infrastructure,
**I want** `A2aTransportRouter` to accept 3 coexisting providers (grpc + in-process + http-jsonrpc),
**So that** users can switch providers via YAML without rebuilding classpath.

**Acceptance Criteria**:
- `AC-4.1` `A2aTransportRouter` 不需改任何代码(#009a 已落地,`List<Providers.A2aTransportProvider>` 自动注入),`HttpJsonRpcA2aTransportProvider` 自动被扫到(VS-6)
- `AC-4.2` `router.resolve("http-jsonrpc-1.0.0", cfg)` 返回 `HttpJsonRpcA2aTransport` 实例(US-4 + FR-003)
- `AC-4.3` `router.resolve("unknown", cfg)` 抛 `IllegalArgumentException` 含 `Unknown A2aTransportRouter 'unknown'. Available: ...`(沿用 #009a 行为,US-4 + EC-2)
- `AC-4.4` 启动日志从 #009b 的 `resolved 2 provider(s)` 升级到 `resolved 3 provider(s)`,新增第 3 行 `✓ http-jsonrpc-1.0.0 v1.0.0 -> HttpJsonRpcA2aTransportProvider [priority=10]`(US-4 + VS-6)

---

## 3. Functional Requirements

| ID | 描述 |
|---|---|
| **FR-001** | `HttpJsonRpcA2aTransport.fetchCard(agentName)` 在 `cardCache` miss 时,HTTP GET `<httpBaseUrl>/.well-known/agent.json`,parse response body 为 `Map<String, Object>`(Jackson `ObjectMapper.readValue(body, Map.class)`),put 进 `cardCache`(TTL=`cfg.getA2a().getCardTtl()`)。HTTP 5xx / IOException / timeout → 抛 `LingshuException("LINGS-S08 A2A_HTTP_RPC_FAILED", message="fetchCard failed for '<httpBaseUrl>/.well-known/agent.json': HTTP <code> / <error>", cause=IOException)`(AC-1.1 + AC-1.6) |
| **FR-002** | `HttpJsonRpcA2aTransport.submit(agentName, skill, inputJson)` 构造 JSON-RPC 2.0 body `{"jsonrpc":"2.0","id":"<UUID>","method":"message/send","params":{"agentName":"<X>","skill":"<Y>","inputJson":"<Z>"}}`,HTTP POST `<httpBaseUrl>/rpc`,parse response `{"result":{...}}` 或 `{"error":{...}}`(Jackson `ObjectMapper.readTree` + 提取 `result` 或 `error`)。`result` 非 null → `ToolResult.toolSuccess(json.writeValueAsString(result))`;`error` 非 null → 抛 `LingshuException("LINGS-S08 A2A_HTTP_RPC_FAILED", message="submit failed: <error.message>")`(AC-1.2 + AC-1.7) |
| **FR-003** | `HttpJsonRpcA2aTransport.get(taskId)` 构造 JSON-RPC body `{"jsonrpc":"2.0","id":"<UUID>","method":"tasks/get","params":{"id":"<taskId>"}}`,HTTP POST `/rpc`,parse `result.status`(COMPLETED/FAILED/CANCELED/RUNNING/PENDING)。FAILED/CANCELED → `ToolResult.toolError(<result.error 或 status>)`;COMPLETED/RUNNING/PENDING → `ToolResult.toolSuccess(json.writeValueAsString(result.resultJson || "{}"))`(AC-1.3 + AC-1.7) |
| **FR-004** | `HttpJsonRpcA2aTransport.cancel(taskId)` 构造 JSON-RPC body `{"jsonrpc":"2.0","id":"<UUID>","method":"tasks/cancel","params":{"id":"<taskId>"}}`,HTTP POST `/rpc`,parse `result.acknowledged` 返 `boolean`;HTTP 5xx → 返 `false`(AC-1.4) |
| **FR-005** | `HttpJsonRpcA2aTransport.subscribe(taskId, onEvent)` 走 polling 占位实现:每 1s 调一次 `get(taskId)`,直到 `ToolResult.status` 表示 terminal(FAILED/CANCELED 视 onEvent 投递 + return;COMPLETED 视 onEvent 投递 + return;RUNNING/PENDING 继续 poll)。`Thread.sleep(1000L)` 用 `InterruptedException` 退出(AC-1.5) |
| **FR-006** | `RemoteAgentTool` 命名约定:`Tool.name()` = 固定字符串 `"remote_agent"`(LLM 看到的 tool name);`Tool.description()` = `"Invoke a skill on a remote A2A agent. Input: {\"agentName\":\"<X>\", \"skill\":\"<Y>\", \"input\": {...}}."`;`Tool.inputSchema()` 由 #009c 阶段返回固定 schema(简化版),#009d `RemoteAgentSchemaBuilder` 落地后接管为 dynamic schema。**注意**:`call_<agentName>.<skill>` 命名约定仅作为**人类可读**约定,实际 Tool.name 固定 `"remote_agent"`,agentName + skill 由 input JSON 传。LLM 看到的是单 tool `remote_agent`,input JSON 里指定 agentName + skill。**理由**:ToolRegistry 单 tool 比 N tool 简单,且与 §6.5 (1) ReadTool 等单 tool 模式一致。**Revisit 触发**:未来 Tool spec 支持 `oneOf` + nested union(OpenAI 2024+ 已部分支持)时再迁移到 N tool 模式 |
| **FR-007** | `RemoteAgentTool.execute(call, ctx)`:解析 `call.getInput()` → `Map<String, Object>` → 读 `agentName` / `skill` / `input` 三个字段 → 构造 `inputJson = json.writeValueAsString(input)` → 调 `transport.submit(agentName, skill, inputJson)`;transport 抛异常 → 返回 `ToolResult.toolError("Remote agent call failed: <message>")`(不重抛,ToolResult 链路优先);成功 → 透传 `ToolResult`(FR-006 + AC-3.3 + AC-3.4) |
| **FR-008** | `HttpJsonRpcA2aTransportProvider.create(cfg)`:读 `cfg.getA2a().getHttpBaseUrl()`(null/empty → fallback `http://localhost:8080` 与 #009 A2aServer 默认对齐;若需自定义,改 yaml),`cfg.getA2a().getCallTimeout()`(null → `Duration.ofSeconds(30)`),`cfg.getA2a().getCardTtl()`(null → `Duration.ofMinutes(5)`)—— 三字段都用 try-catch `NoSuchMethodError` 兼容 pre-#009c 的 `AgentConfig.A2a`(FR-002 + FR-008) |
| **FR-009** | `HttpJsonRpcA2aTransport` 构造器:`HttpJsonRpcA2aTransport(String httpBaseUrl, ObjectMapper json, AgentCardCache cardCache, Duration callTimeout)`,4 字段都 `final` 且 `null`-check 抛 `IllegalArgumentException`;`httpBaseUrl` 末尾 `/` 自动 trim 防止 `<base>//.well-known/...` 双 slash(FR-001 + FR-002) |
| **FR-010** | `HttpJsonRpcA2aTransport.subscribe` 内 `Thread.sleep(1000L)` 检测 `Thread.interrupted()`,若 true → `Thread.currentThread().interrupt()` + `onEvent` 不再投递 + return(US-1 + AC-1.5,与 #009c 边界对齐) |
| **FR-011** | `HttpJsonRpcA2aTransport` 复用 #009a `AgentCardCache`(L1 positive + negative cache + lazy eviction),**不**重复造缓存轮子(FR-001) |
| **FR-012** | `RemoteAgentToolAutoConfiguration.@Bean public Tool remoteAgentTool(A2aTransport transport)` —— `transport` 由 `A2aTransportRouter.resolve(cfg.getA2aTransport(), cfg)` 注入(读 `cfg.getA2aTransport()` 拿 name,**注意**:`A2aTransportRouter` 在 Spring 容器内,这里要在 `RemoteAgentToolAutoConfiguration` 内手动 `router.resolve(name, cfg)` 才能拿到 instance,**不**直接 `@Autowired A2aTransport` 因为多 Provider 同存时取哪个不定)(FR-007 + AC-3.5) |
| **FR-013** | `RemoteAgentTool` 的 `inputSchema()` 在 #009c 阶段返回固定 schema:```{"type":"object","properties":{"agentName":{"type":"string","description":"Target remote agent Identity.name"},"skill":{"type":"string","description":"Skill id to invoke"},"input":{"type":"object","description":"JSON args matching the skill's input schema"}},"required":["agentName","skill","input"]}```(FR-006 + FR-007 + AC-3.4) |
| **FR-014** | 5 方法失败统一抛 `LingshuException("LINGS-S08", "A2A_HTTP_RPC_FAILED", message, hint, cause)`,**不**抛裸 IOException / InterruptedException 给 caller(US-1 + AC-1.6) |
| **FR-015** | JSON-RPC `error` 字段非空时:`error.code` / `error.message` 都进 `LingshuException` message,hint = "check remote agent's logs / verify agentName and skill match the AgentCard"(US-1 + AC-1.7) |
| **FR-016** | `LINGS-S08 A2A_HTTP_RPC_FAILED` 作为子码落地 —— 与 #009b `LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY` 同号,但通过 `HttpJsonRpcException extends LingshuException` nested class 的 `getErrorCode()="LINGS-S08"` + `getReason()="A2A_HTTP_RPC_FAILED"` 区分(§15 错误码域细分约定) |
| **FR-017** | `RemoteAgentTool` 的 `description()` 含 escape 字符安全:Javadoc 注释里不允许出现 `*/` 提前关闭注释;测试用 `assertThat(tool.description()).doesNotContain("\n")` 不强约束(只关心是否能被模型读取) |

---

## 4. Non-Functional Requirements

| ID | 描述 |
|---|---|
| **NFR-001** | 0 额外依赖(`java.net.http.HttpClient` JDK 17 内置 + Jackson `ObjectMapper` 由 Spring Boot BOM 引入)—— R-13 mitigation (d) 强度最弱 |
| **NFR-002** | `HttpJsonRpcA2aTransport` 所有字段 `final` + 构造期 null-check,线程安全(无 mutable 状态);`AgentCardCache` / `HttpClient` 自身线程安全 |
| **NFR-003** | `fetchCard` 命中 cache 时 latency < 1ms(纯 ConcurrentHashMap 读);miss 时 latency < `callTimeout`(默认 30s 上限) |
| **NFR-004** | `subscribe` polling 走 daemon thread 或 caller thread(本 Story 选 caller thread 简化);**不**起后台线程(避免资源泄露) |
| **NFR-005** | 测试覆盖 L1 Unit 5 文件 + L2 Slice 1 文件 = 6 测试文件 + ≥ 16 case(`HttpJsonRpcA2aTransportTest` 5 + `HttpJsonRpcA2aTransportProviderTest` 3 + `RemoteAgentToolTest` 4 + `HttpJsonRpcA2aTransportAutoConfigurationTest` 2 + `RemoteAgentToolAutoConfigurationTest` 2 + `A2aServerRpcEndpointTest` 2 = 18 case) |
| **NFR-006** | JDK 8 兼容:不用 `var` / `record` / `sealed` / `List.of` / pattern matching;用 `Arrays.asList` / `Collections.unmodifiableMap` / `LinkedHashMap` |
| **NFR-007** | `RemoteAgentTool` 走 `ToolExecutor.dispatch()` 5 步流水线(权限 → registry lookup → timeout → sandbox → execute → checkpoint)—— §4.10.1 硬规则 2 强制,**不**绕 `ToolRegistry` |
| **NFR-008** | dsh §5.6.3.0 4 核心类型(`AgentCard` / `AgentRef` / `RemoteAgentSchemaBuilder` / `AgentCardCache`)复用,#009c **不**改;只 `RemoteAgentTool` 接入 `ToolRegistry`(`@Bean Tool` 模式)—— #009d 落地 `RemoteAgentSchemaBuilder.buildToolSpecs()` 后由 Spring 自然接管 |
| **NFR-009** | R-13 mitigation (d) baseline 镜像:`mvn dependency:tree -pl lingshu-a2a-client -Dverbose=true` 与 #009b baseline 完全一致(0 binary delta);enforcer `banned-dependencies` 规则不 fail |
| **NFR-010** | `RemoteAgentTool` 接 `ToolExecutor.dispatch` 后,**不**破坏 §4.7 `PermissionPolicy.check()` —— LLM 调用 `remote_agent` 时仍需经权限检查(默认 `StrictPermissionPolicy` 拒远程工具调用,符合 §4.7 边界);`Permissions.REMOTE_AGENT` 角色由后续 Story 落地 |

---

## 5. Edge Cases

| ID | 描述 |
|---|---|
| **EC-1** | `cfg.getA2a().getHttpBaseUrl()` = null + `cfg.getA2a().getCallTimeout()` = null + `cfg.getA2a().getCardTtl()` = null(空 yml 启动) → fallback 三字段默认值;**不**抛异常(对齐 #009 / #009a / #009b 空 yml 启动行为) |
| **EC-2** | `httpBaseUrl` = `""`(空字符串) → `create(cfg)` 抛 `LingsConfigException("LINGS-C02", "agent.a2a.httpBaseUrl must not be empty")` 失败快(#009a 同样失败快模式) |
| **EC-3** | `httpBaseUrl` = `"ftp://..."`(协议错) → **不**立刻校验,推到运行时 `HttpClient` 抛 `IllegalArgumentException` → 转 `LINGS-S08 A2A_HTTP_RPC_FAILED`(EC-3 + FR-014) |
| **EC-4** | HTTP 5xx 响应(503 / 502 / 500) → `fetchCard` / `submit` / `get` 抛 `LINGS-S08 A2A_HTTP_RPC_FAILED` + `cardCache.putNegative(agentName)`(对齐 #009a `GrpcA2aTransport` 对 NOT_FOUND/UNAVAILABLE/DEADLINE_EXCEEDED 的负缓存策略) |
| **EC-5** | HTTP timeout(超过 `callTimeout`) → `HttpClient.send` 抛 `HttpTimeoutException` → 转 `LINGS-S08 A2A_HTTP_RPC_FAILED`(EC-5 + FR-014) |
| **EC-6** | HTTP 404(`/.well-known/agent.json` 找不到) → `fetchCard` 抛 `LINGS-S08 A2A_HTTP_RPC_FAILED` + `cardCache.putNegative(agentName)` |
| **EC-7** | JSON parse error(响应 body 不是合法 JSON) → Jackson `JsonProcessingException` → 转 `LINGS-S08 A2A_HTTP_RPC_FAILED` |
| **EC-8** | `cancel(taskId)` 时 remote agent 已经完成 → JSON-RPC `error.code = -32001`(TaskNotFound 等约定的 error code) → `cancel` 返回 `false`(不抛异常,因为 cancel 本身 best-effort) |
| **EC-9** | `subscribe` polling 1000 次后仍非 terminal(实际生产中 long-running agent) → 本期无上限,持续 poll 直到 `InterruptedException` 或 caller 线程结束;**未来**接入 SSE 后改为 stream 推送 |
| **EC-10** | `RemoteAgentTool` 接 `ToolExecutor.dispatch` 时 `RemoteAgentToolAutoConfiguration` 拿不到 `A2aTransportRouter`(Spring 启动失败) → ApplicationContext 启动失败,**不**局部 try-catch 吞错(对齐 §4.10.1 硬规则 fail-fast) |
| **EC-11** | `RemoteAgentTool.execute` 时 transport 抛 `LINGS-S08 A2A_HTTP_RPC_FAILED` → 返回 `ToolResult.toolError(message)`,**不**重抛(`ToolResult` 链路优先);但 `ToolExecutor.dispatch` 内部会 catch 异常并转 `ToolResult.toolError`,**双层兜底** |
| **EC-12** | 同 #009b:多线程同时调用 `RemoteAgentTool.execute` → `HttpClient` 自身线程安全(`java.net.http.HttpClient` 文档明确 thread-safe)+ `A2aTransport` 字段 final → 安全 |
| **EC-13** | `A2aServer.start()` 时 `cfg.getIdentity().getName()` = null/empty → #009 已抛 `LINGS-T02`,`RemoteAgentTool` 永远不会被 trigger(因为没 server 可调用);**不在本 Story 范围** |
| **EC-14** | `subscribe` caller 线程被 `InterruptedException` 中断 → `Thread.currentThread().interrupt()` + return,不重抛(FR-010) |

---

## 6. Out of Scope(本 Story **不**做)

- **`GrpcA2aTransport` 改造** —— #009a 已落地,**不动**(dsh §5.6.3.2 L3192-3215 GrpcA2aTransport stub 完整)
- **`InProcessA2aTransport` submit/get/cancel/subscribe 完整 5 方法** —— #009b 限定 fetchCard-only,**不**扩 submit 等(downgrade 路径:**未来** in-process dispatcher Story 落地,#009d 不动)
- **`RemoteAgentSchemaBuilder.buildToolSpecs()` dynamic schema** —— #009d 落地,#009c 用固定 input schema 占位
- **SSE 推送 `subscribe`** —— JDK 17 HttpClient 不内置 SSE EventSource(只用 polling 占位实现)
- **`RemoteAgentTool` 走 `Permissions.REMOTE_AGENT` 角色** —— 由后续 Story 扩 `PermissionPolicy` 时落地,#009c 走默认 strict policy
- **A2aServer `POST /rpc` 端点升级为 JSON-RPC 2.0 dispatcher** —— 留作 **Story #009c.5 升级**(本 Story 范围内,**轻量级**地把 `/rpc` placeholder 替换为最小 JSON-RPC 2.0 dispatcher 实现,接收 `message/send` / `tasks/get` / `tasks/cancel` 3 method,回 echo + status response,**不**做真正的 skill 派发;留 **Story #017+ 或后续 Server RPC dispatch Story** 做完整派发)
- **新 Maven 依赖** —— R-13 mitigation (d) 强度最弱,0 binary delta
- **`AgentConfig.A2a` 加 `httpBaseUrl` / `callTimeout` 字段** —— 由本 Story **必须**扩展(`HttpJsonRpcA2aTransportProvider.create(cfg)` 读 `cfg.getA2a().getHttpBaseUrl()`);最小修改 = 加 2 字段 + `defaults()` 改写
- **`A2aServer` 跨域 CORS / TLS / 鉴权头** —— 本 Story 不动(由部署层 Ingress / Nginx / Envoy 统一处理)

---

## 7. Constitution Check(宪章 v1.0)

| 宪章节 | 条款 | 本 Story 合规情况 |
|---|---|---|
| §1 项目原则 | #8 Slot 选用(Slot 9 A2aTransport 已落地) | ✅ 复用 #009a + #009b 既有 Slot |
| | #9 Plugin 发现(@Component + @AutoConfiguration) | ✅ `HttpJsonRpcA2aTransportProvider` + `RemoteAgentTool` 都 `@Component` |
| | #11 默认实现位置(Slot 9 默认 Provider = HttpJsonRpcA2aTransportProvider) | ✅ dsh §5.6.4 SPI 槽位总表 Slot 9 行已锁定 |
| §2 13 依赖锁定 | R-13 mitigation (d) 强度最弱 | ✅ 0 新依赖(JDK 17 HttpClient + Jackson 都已有) |
| §4 错误码约定 | LINGS-<域><编号> 域细分 | ✅ `LINGS-S08 A2A_HTTP_RPC_FAILED`(子码,与 #009b `A2A_INPROCESS_REGISTRY_EMPTY` 同号细分) |
| §5 7 层金字塔 | 单元 / Slice / 集成 | ✅ L1 Unit 5 文件 + L2 Slice 1 文件 = 6 文件 ≥ 16 case |
| §6 兼容性矩阵 | JDK 8 编译 + JDK 17 跑 | ✅ `java.net.http.HttpClient` 需 JDK 11+(实际跑 JDK 17+,CLAUDE.md §2 锁定) |
| §7 LTS 政策 | JDK 17/21 LTS | ✅ JDK 17+ runtime |
| §8 Glossary | A2A 术语一致 | ✅ `A2aTransport` / `AgentCard` / `RemoteAgentTool` 与 dsh §16 一致 |
| §9 Review 节奏 | PR review + CI | ✅ Story 完成 + PR + CI 全过后 merge |
| §10 风险登记 | R-13(13 依赖锁) + R-14(A2A 协议兼容) | ✅ R-13 强度最弱 + R-14 by §5.6.3.2 L3174-3320 「3 件套模式」兼容未来变体 |

---

## 8. Success Criteria(完成定义)

1. ✅ `spec.md` / `plan.md` / `tasks.md` / `data-model.md` / `quickstart.md` / `contracts/` / `checklists/` 三件套 + 4 配套 artifact 全部齐备
2. ✅ `HttpJsonRpcA2aTransport` 5 方法完整实现 + LINGS-S08 域细分 + `RemoteAgentTool` + 2 AutoConfiguration + SPI imports 三件套
3. ✅ L1 Unit + L2 Slice 全部 case 通过(≥ 16 case 0 fail / 0 error / 0 skipped)
4. ✅ `mvn dependency:tree -pl lingshu-a2a-client` 与 #009b baseline 完全一致(R-13 mitigation (d) 强度最弱 0 binary delta)
5. ✅ `A2aServer` 启动日志 `resolved 3 provider(s)` + 3 行 ✓ 列表
6. ✅ `RemoteAgentTool` 接入 `ToolExecutor.dispatch()` 5 步流水线(权限 → registry lookup → timeout → sandbox → execute → checkpoint)
7. ✅ dsh §13 changelog 加 v1.5.37 行 + README 更新 3 Provider 列表
8. ✅ PR title `feat(a2a-client): Story #009c a2a-httpjsonrpc-and-remote-tool — ...` + body 末尾 `### R-13 dependency:tree 自查` 节

---

## 9. Open Questions / Future Stories

| ID | 问题 | 落地 Story |
|---|---|---|
| OQ-1 | `RemoteAgentTool` 是否拆成 N tool(call_alice + call_bob)而非单 tool `remote_agent`? | **Revisit trigger** OpenAI / Anthropic tool spec 支持 `oneOf` + nested union;**当前**选单 tool 简化版 |
| OQ-2 | `subscribe` polling 占位是否升级为 SSE? | Story #009c.5+ 升级用 OkHttp EventSource(若引入 OkHttp 则破 R-13)或 JDK 21+ HttpClient 内置 SSE |
| OQ-3 | `A2aServer` `POST /rpc` JSON-RPC dispatcher 完整 skill 派发? | **Story #009c.5 轻量级落地最小 dispatcher**(本 Story 范围内,scope-limited),**完整** skill 派发由后续 Server RPC dispatch Story |
| OQ-4 | `RemoteAgentTool` 接 `Permissions.REMOTE_AGENT` 角色 + `PermissionPolicy.check()` 默认策略? | 后续 `PermissionPolicy` 扩展 Story |

---

## 10. References

- **设计文档**: `~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md` v1.5.36
- **SpecKit SOP**: `~/Documents/AIFullStack/MyDSHAgentDesign/speckit_operator_prompt.md` v1.18
- **SKILL**: `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` v1.0.21
- **dsh §5.6.3.1 L2995-3172**: HttpJsonRpcA2aTransport + Provider + AutoConfiguration 完整实现
- **dsh §5.6.3.2 L3174-3320**: 3 件套模式扩展指南(Grpc/InProcess 样板)
- **dsh §5.6.1 L2346-2390**: RemoteAgentTool + RemoteAgentToolAutoConfiguration 样板
- **dsh §4.10.1 硬规则 2**: ToolExecutor.dispatch() 5 步流水线
- **dsh §15**: LINGS-<域><编号> 错误码约定
- **dsh §17**: R-13 / R-14 风险登记
- **前序 Story PR**:
  - #009a GrpcA2aTransport: PR #20 (merged 064ce3a)
  - #009b InProcessA2aTransport: PR #21 (merged 42888c3)
- **A2A v1.0 spec §2.1**: `GET /.well-known/agent.json` 固定路径
- **JSON-RPC 2.0 spec**: https://www.jsonrpc.org/specification
