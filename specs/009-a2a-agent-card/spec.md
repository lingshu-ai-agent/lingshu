# Feature Specification: Story #009 a2a-agent-card

**Feature Branch**: `story-009-a2a-agent-card`
**Created**: 2026-09-21
**Status**: Draft
**Input**: User description: "Story #009 a2a-agent-card — `lingshu-a2a-server` 模块下 `LocalAgentCardGenerator` + 内置 HTTP server(基于 JDK `com.sun.net.httpserver.HttpServer` 0 新依赖)+ `GET /.well-known/agent.json` 返回 `name`/`description`/`version`,直接来源于 `cfg.getIdentity()`,无需额外 yml;AC-10 黑盒验证(AC-10, dsh §0.4 L149-153 + §5.6.2 四层架构 + §5.6.8 LocalAgentCardGenerator)"

**Source Design Doc**: `dsh_agent_design.md` v1.5.36
- §0.4 AC-10 L149-153(A2A AgentCard 自动生成 — `GET /.well-known/agent.json` 返回含 `name`/`description`/`version` 的 AgentCard,**且**直接来源于 `cfg.getIdentity()`,无需额外 yml;§5.6.8 LocalAgentCardGenerator 验证)
- §5.6 L2333-2394(A2A 协议设计 + 四层架构 + A2aServer `lingshu serve --a2a` 子命令 → `GET /.well-known/agent.json` 暴露本地 Agent)
- §5.6.3.0 L2482-2992(四个核心类型完整定义 — 本 Story 仅落地 `AgentCard` 数据类型,**不**涉及 `AgentRef` / `RemoteAgentSchemaBuilder` / `AgentCardCache`)
- §5.6.4 SPI 总表(Slot 9 `A2aTransport` + 默认 Provider `HttpJsonRpcA2aTransportProvider` —— 本 Story **不**实现 Transport,留 stub 给 #009b)
- §5.6.8 LocalAgentCardGenerator(将 `cfg.getIdentity()` 映射为 `AgentCard` JSON)
- §15 ErrorCode(本 Story 新增 `LINGS-S06 A2A_SERVER_START_FAILED`,来源:HTTP server 启动期 `BindException`;`LINGS-T02 A2A_CARD_INVALID_CONFIG`,来源:`Identity` 字段缺失校验)
- §16 Glossary(`AgentCard` / `A2A` / `LocalAgentCardGenerator`)
- §17 Risk Register(R-13 额外依赖风险 —— 本 Story **0 新增 Maven 依赖**;JDK 内置 `com.sun.net.httpserver.HttpServer`;**R-13 mitigation (d)** 满足)

**Constitution**: `.specify/memory/constitution.md` v1.0
- §1 #5 Skill 与 Tool 边界(`AgentCard` 不属于 Tool/Skill,独立数据载体 —— 本 Story 新增 `AgentCard` 类型不影响 Skill/Tool 体系)
- §1 #9 Plugin 发现(Spring Boot Auto-Config —— 本 Story 用 `@Component` + `@AutoConfiguration` + `@Bean` 模式,与 §5.5 plugin 非 Slot 类型 Bean 样板对齐)
- §1 #11 默认实现位置(`lingshu-a2a-server` 独立模块 —— 本 Story 落地在此模块,不污染 `lingshu-core`)
- §1 #12 启动时配置校验(`LocalAgentCardGenerator.generate()` 启动期调一次,校验 `Identity.name` 非空,缺则抛 `LINGS-T02`)
- §2 13 项依赖锁定(**R-13 严格遵守** —— 本 Story 0 新增 Maven 依赖;`com.sun.net.httpserver.HttpServer` JDK 内置;Jackson 已传递依赖)
- §3 NFR baseline:HTTP server 冷启动 ≤ 30s,AgentCard 响应 P99 ≤ 100ms(本地内存,无 IO)
- §4 错误码约定:新增 `LINGS-S06 A2A_SERVER_START_FAILED` + `LINGS-T02 A2A_CARD_INVALID_CONFIG`(**2 ErrorCode**,符合 Story 边界 ≤ 3)
- §5 7 层金字塔:L1 Unit(`LocalAgentCardGeneratorTest`)+ L1 Unit(`AgentCardTest`)+ L2 Slice(embedded HttpServer 集成)+ L5 E2E(AC-10 black-box `curl`)
- §8 Glossary:`AgentCard` 新增
- §10 R-13 额外依赖风险:本 Story **0 新增依赖**,标记为「已缓解」

**对应 AC**: **AC-10**(A2A AgentCard 自动生成 §0.4 L149-153)—— yml 配 `agent.identity.*` + HTTP `GET /.well-known/agent.json` 返回 `AgentCard.name`/`description`/`version`,**且**直接来源于 `cfg.getIdentity()`,无需额外 yml 字段;§5.6.8 LocalAgentCardGenerator 验证。

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — `GET /.well-known/agent.json` 返回含 `name`/`description`/`version` 的 AgentCard (Priority: P1)

作为 **Alice(企业 AI 编码助手使用者)**,我**期望** 当我启用 `lingshu-a2a-server` 模块 + 在 yml 配 `agent.identity.name: "my-coding-agent"`,HTTP 请求 `GET http://localhost:8080/.well-known/agent.json` 立即返回一份 JSON `AgentCard`,**只**包含 `name` / `description` / `version` 三个必填字段(其他字段从 yml 透传,缺省值由 `Identity.defaults()` 给),**无需** 我在 yml 里额外写 `a2a.*` 字段。这样我可以:(1) 让远端 A2A client(其它 LingShu 实例 / Google A2A SDK)直接发现我的 Agent 能力;(2) `curl` 命令立即验证服务端配置;(3) 配合 Story #009b HttpJsonRpcA2aTransport,实现「LingShu 既能调别人也能被别人调」的对称架构。

**Why this priority**: 这是 **AC-10 的核心契约**。当前(Story #001—#008 已 merged)`lingshu-a2a-server` 模块**仅有** `pom.xml` 占位文件,**0 Java 源文件**,`LocalAgentCardGenerator` / A2a HTTP server 全部未实现 —— 用户启用模块后 `lingshu serve --a2a` 子命令无法启动,`/.well-known/agent.json` 返回 404,A2A 对称架构的「服务端暴露」半边完全缺失。**本 Story 落地** LocalAgentCardGenerator + JDK 内置 HttpServer(0 新依赖),填补 v0.5 A2A 设计 §5.6.8 服务端空白。

**Independent Test**: 在 `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/LocalAgentCardGeneratorTest` 写核心用例(L1 Unit,不起 Spring)—— 配置 `AgentConfig.Identity("my-coding-agent", "AI 编码助手", ...)` → 调 `LocalAgentCardGenerator.generate(cfg)` → 断言:`new AgentCard(name="my-coding-agent", description="AI 编码助手", version="0.1.0", skills=Collections.emptyList(), capabilities=AgentCapabilities.empty())`(skills/capabilities 默认空 list,本 Story **不**涉及 skill 发现,留 #009c)。

**Acceptance Scenarios**:

1. **Given** 启用 `lingshu-a2a-server` 模块 + yml 配 `agent.identity.name: "alice-coding"` + `agent.identity.role: "AI 编码助手"` + 启动 HTTP server
   **When** `curl http://localhost:8080/.well-known/agent.json`
   **Then** 返回 HTTP 200 + `Content-Type: application/json` + JSON body 含 `name="alice-coding"` + `description="AI 编码助手"` + `version="0.1.0"`
   **And** **无** `a2a.*` / `agent-card.*` 等额外 yml 字段 —— AgentCard **完全** 从 `cfg.getIdentity()` 派生
   **And** 响应时间 P99 ≤ 100ms(本地内存生成,无 IO)

2. **Given** yml 未配 `agent.identity.*`(零配置启动)
   **When** `curl http://localhost:8080/.well-known/agent.json`
   **Then** 返回 HTTP 200 + JSON body 含 `name="lingShu-agent"` + `description=null`(Identity.defaults() role 字段为 null)+ `version="0.1.0"`
   **And** `description` 字段为 `null` 时 JSON 输出 `null` 字面量(Jackson 默认行为),**不**抛 500

3. **Given** yml 配 `agent.identity.name: ""`(空字符串,违反 P1-AS4 校验)
   **When** 启动 HTTP server(`A2aServerAutoConfiguration.@PostConstruct`)
   **Then** 启动失败抛 `LINGS-T02 A2A_CARD_INVALID_CONFIG`(`Identity.name must not be blank`)
   **And** Spring 上下文启动失败,`lingshu serve --a2a` 进程退出非 0

4. **Given** 端口 8080 已被占用(本机另一进程占用)
   **When** 启动 HTTP server
   **Then** 启动失败抛 `LINGS-S06 A2A_SERVER_START_FAILED`(cause `BindException: Address already in use`)
   **And** 错误信息含 port + BindException cause,运维人员可立即定位

---

### User Story 2 — HTTP server 优雅启停(@PostConstruct / @PreDestroy)与端口可配 (Priority: P1)

作为 **Bob(业务配置方)**,我**期望** 当我在 yml 配 `lingshu.a2a.server.port: 9090`(或通过命令行 `--a2a.port=9090`)+ 启动 Agent,HTTP server 在 Spring 上下文就绪**后**(`@PostConstruct`)开始监听 9090,在 Spring 上下文关闭**前**(`@PreDestroy`)优雅停止(等当前 in-flight 请求完成 + 释放端口);如果我不配端口,默认走 8080。这样我可以:(1) 与现有 8080 服务共存不冲突;(2) K8s readiness probe 通过 HTTP 200 验证 A2A 服务就绪;(3) SIGTERM 触发 `@PreDestroy` 不丢 in-flight 请求。

**Why this priority**: 这是 **AC-10 的运维基线**。当前(Story #001—#008 已 merged)A2A HTTP server 完全未实现,**无法**谈优雅启停 / 端口可配 / K8s 集成 —— 这是从「模块占位」到「生产可用」的关键跃迁。**本 Story 落地** Spring `@Component` Bean 生命周期管理 + 端口可配(`a2a.server.port` 字段 + 默认 8080),与 Story #013 healthcheck/graceful-shutdown 共享 `@PreDestroy` 模板。

**Independent Test**: 在 `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerLifecycleTest` 写核心用例(L1 Unit,直接 new `A2aServer(cfg, port=0)` 拿到 OS 分配端口)—— 调 `start()` → 验证 `http://127.0.0.1:<port>/.well-known/agent.json` 返回 200 → 调 `stop()` → 验证端口已释放(重新 bind 同一端口不抛 `BindException`)+ **无** in-flight 请求被中断。

**Acceptance Scenarios**:

1. **Given** yml 配 `a2a.server.port: 9090` + 默认 Identity
   **When** Spring 上下文启动
   **Then** `A2aServer.@PostConstruct start()` 完成后 `http://127.0.0.1:9090/.well-known/agent.json` 返回 HTTP 200
   **And** 启动日志含 `[A2aServer] listening on http://0.0.0.0:9090` 一行

2. **Given** yml 未配 `a2a.server.port`(默认 8080)
   **When** Spring 上下文启动
   **Then** `http://127.0.0.1:8080/.well-known/agent.json` 返回 HTTP 200(零配置启动,与 Story #001 零配置原则对齐)

3. **Given** HTTP server 已启动 + 收到 SIGTERM / `SpringApplication.exit()`
   **When** `A2aServer.@PreDestroy stop()` 执行
   **Then** 当前 in-flight 请求正常返回 200(HttpServer 内部 `stop(0)` = 立即停止 + 不等待 in-flight;v0.5 KISS 不做 graceful drain,留 #013)
   **And** 端口释放(进程重启后 bind 同一端口不抛 `BindException`)
   **And** 日志含 `[A2aServer] stopped on http://0.0.0.0:<port>` 一行

4. **Given** yml 配 `a2a.server.port: 0`(OS 自动分配端口,主要用于测试)
   **When** Spring 上下文启动
   **Then** `A2aServer` 启动成功,真实端口写入日志 `[A2aServer] listening on http://0.0.0.0:<actual-port>`
   **And** 测试可通过 `A2aServer.getActualPort()` 拿到端口(测试 helper API,生产代码不依赖)

---

### User Story 3 — `POST /rpc` JSON-RPC 2.0 占位 + 其他路径 404 (Priority: P2)

作为 **Charlie(框架贡献者)**,我**期望** 当远端 A2A client `POST http://localhost:8080/rpc` 发 JSON-RPC 2.0 `message/send` 请求,本 Story HTTP server 返回 501 Not Implemented + JSON body `{"error":"not implemented","method":"message/send"}`(明确告诉 client「端点存在但尚未实现」),而非 404(避免 client 误以为 server 不在线);其他路径(如 `/foo` / `/bar`)返 404。这样我可以:(1) 本 Story 落地 A2A 对称架构的「端点骨架」,未来 #009b `HttpJsonRpcA2aTransport` 只需替换 `POST /rpc` handler,**不**改 server 框架;(2) 端点契约清晰,运维 / client 集成不会因 404 误判;(3) US3 是**软** AC-10 验证(AC-10 主路径只验 `GET /.well-known/agent.json`,US3 是「端点骨架就位」检查项)。

**Why this priority**: 这是 **AC-10 的端点骨架契约**。当前(Story #001—#008 已 merged)HTTP server 完全未实现,**US3 仅 P2** 而非 P1,因为本 Story **核心** 是 `GET /.well-known/agent.json` 服务端暴露,`POST /rpc` 实现留给 #009b(交由 `HttpJsonRpcA2aTransport` + 服务端 `message/send` handler 一起落地)。**本 Story** 仅占位 POST /rpc 返 501 让端点契约清晰,**不**做 JSON-RPC 业务处理。

**Independent Test**: 在 `A2aServerLifecycleTest` 加用例 —— 启动 server → `curl -X POST http://127.0.0.1:<port>/rpc -d '{"jsonrpc":"2.0","id":"1","method":"message/send","params":{}}'` → 断言 HTTP 501 + body `{"error":"not implemented","method":"message/send"}`;`curl http://127.0.0.1:<port>/foo` → 断言 HTTP 404 + body `{"error":"not found","path":"/foo"}`。

**Acceptance Scenarios**:

1. **Given** HTTP server 已启动
   **When** `curl -X POST http://127.0.0.1:<port>/rpc -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":"1","method":"message/send","params":{}}'`
   **Then** 返回 HTTP 501 + `Content-Type: application/json` + body `{"error":"not implemented","method":"message/send"}`
   **And** **不**抛 500(明确告诉 client 端点存在但未实现,留 #009b)

2. **Given** HTTP server 已启动
   **When** `curl http://127.0.0.1:<port>/foo`(未知路径)
   **Then** 返回 HTTP 404 + body `{"error":"not found","path":"/foo"}`

3. **Given** HTTP server 已启动
   **When** `curl -X OPTIONS http://127.0.0.1:<port>/.well-known/agent.json`(CORS 预检)
   **Then** 返回 HTTP 405 Method Not Allowed + body `{"error":"method not allowed","method":"OPTIONS"}`(GET-only 端点)

---

## Edge Cases *(mandatory)*

| # | 场景 | 期望行为 | 优先级 |
|---|---|---|---|
| EC-1 | `Identity.name` 为 `null`(用户从未设置 + Identity.defaults() 总是给 "lingShu-agent",理论上不会 null,但空字符串绕过)| 启动期校验抛 `LINGS-T02 A2A_CARD_INVALID_CONFIG` | P0 |
| EC-2 | `Identity.role` 为 `null`(合法空 —— 用户可不写 role)| AgentCard JSON `description` 字段为 `null`,**不**抛异常 | P1 |
| EC-3 | `Identity.traits` 为 `null` 或空 list | AgentCard JSON `traits` 字段为 `null` / `[]`,**不**抛异常 | P1 |
| EC-4 | yml 配 `a2a.server.port: -1`(无效端口)| 启动期校验抛 `LINGS-S06 A2A_SERVER_START_FAILED`(port must be 0—65535)| P0 |
| EC-5 | yml 配 `a2a.server.port: 65536`(越界)| 启动期校验抛 `LINGS-S06 A2A_SERVER_START_FAILED` | P0 |
| EC-6 | HTTP server 启动后 `kill -9`(非优雅停止)| JVM 退出,OS 释放端口;下次启动 bind 同一端口不抛 `BindException`(OS 视角已完成)| P1 |
| EC-7 | HTTP server 启动后立即收到 SIGTERM | `@PreDestroy stop()` 正常执行,端口释放,日志输出 stopped 行 | P1 |
| EC-8 | 远端 client 用 `Accept-Encoding: gzip` 请求 | 本 Story **不** 支持 gzip(简化),server 忽略 `Accept-Encoding` 直接返原始 JSON;HTTP 200 正常返回 | P2 |
| EC-9 | 远端 client 用 HTTPS 请求(本 server 是 HTTP)| client 收到 connection refused(TLS handshake 失败),server 进程不受影响 | P1 |
| EC-10 | HTTP server 启动时 hostname 解析失败(极少见,如 `/etc/hosts` 配置错误)| `@PostConstruct` 抛 `LINGS-S06 A2A_SERVER_START_FAILED`(cause `UnknownHostException`)| P1 |

---

## Functional Requirements

| ID | 需求 | 来源 |
|---|---|---|
| **FR-001** | `LocalAgentCardGenerator.generate(AgentConfig cfg)` 必须返回 `AgentCard`,`name` 字段 = `cfg.getIdentity().getName()`(空则抛 `LINGS-T02`)| §0.4 AC-10 + §5.6.8 |
| **FR-002** | `LocalAgentCardGenerator.generate()` 必须返回 `AgentCard`,`description` 字段 = `cfg.getIdentity().getRole()`(`null` 允许,JSON 序列化为 `null` 字面量)| §5.6.8 |
| **FR-003** | `LocalAgentCardGenerator.generate()` 必须返回 `AgentCard`,`version` 字段 = `"0.1.0"`(硬编码,来自 `AgentFactory.PROJECT_VERSION`,与 Story #001 §0 L1 版本号对齐)| §0 L1 + §5.6.8 |
| **FR-004** | `LocalAgentCardGenerator.generate()` 必须返回 `AgentCard`,`skills` 字段 = `Collections.emptyList()`(本 Story **不**涉及 skill 发现,留 #009c)| §5.6.8(本 Story 最小集)|
| **FR-005** | `LocalAgentCardGenerator.generate()` 必须返回 `AgentCard`,`capabilities` 字段 = `AgentCapabilities.empty()`(`streaming=false` / `pushNotifications=false` / `stateTransitionHistory=false`)| §5.6.8(本 Story 最小集)|
| **FR-006** | `AgentCard.toJson()` 必须用 Jackson 序列化为标准 A2A v1.0 spec JSON 格式(`name` / `description` / `version` / `skills` / `capabilities` / `defaultInputModes` / `defaultOutputModes` / `provider` / `documentationUrl` / `iconUrl`)| §5.6.3.0 AgentCard 类定义 |
| **FR-007** | `A2aServer.start()` 必须使用 JDK 内置 `com.sun.net.httpserver.HttpServer`,监听 `a2a.server.port`(默认 8080)| §5.6.2 + R-13 mitigation (d) |
| **FR-008** | `A2aServer.start()` 必须注册 2 个 handler:`GET /.well-known/agent.json` → `LocalAgentCardGenerator.generate()` 输出 JSON;`POST /rpc` → 501 not-implemented 占位 | §5.6 + US3 |
| **FR-009** | `A2aServer.stop()` 必须在 `@PreDestroy` 触发,调 `HttpServer.stop(0)`(立即停止,不等待 in-flight;graceful drain 留给 #013)| §13 N6 graceful shutdown |
| **FR-010** | `A2aServer` Bean 必须由 `A2aServerAutoConfiguration` 创建,带 `@PostConstruct start()` + `@PreDestroy stop()` 生命周期 | §5.5 plugin 样板 |
| **FR-011** | `A2aServer.start()` 失败必须抛 `LINGS-S06 A2A_SERVER_START_FAILED`,cause chain 至少包含 `BindException` 或 `IllegalArgumentException` 等 root cause | §4 错误码约定 |
| **FR-012** | `AgentConfig.A2a` 新增字段 `server.port`(默认 8080,Integer 类型,允许 0—65535)| §0.4 AC-10 |
| **FR-013** | `A2aServer.getActualPort()` 必须返回 OS 实际绑定端口(port=0 时 OS 分配,测试 helper API)| 测试 helper,生产代码不依赖 |

---

## Non-Functional Requirements

| ID | 维度 | 需求 |
|---|---|---|
| **NFR-001** | 性能 | `GET /.well-known/agent.json` P99 ≤ 100ms(本地内存生成,无 IO) |
| **NFR-002** | 性能 | HTTP server 冷启动 ≤ 5s(`HttpServer.start()` 是同步阻塞,Lingshu 整体冷启动 30s 内) |
| **NFR-003** | 内存 | `LocalAgentCardGenerator` 单例无状态,`AgentCard` 实例生成后立即 GC;HTTP server 线程池默认 0 = 系统自动分配 |
| **NFR-004** | JDK 8 兼容 | 不使用 `var` / `record` / `sealed` / `List.of` / `Map.of` / `Set.of`;`HttpServer.create()` + `HttpHandler` + `Collections.emptyList()` |
| **NFR-005** | R-13 依赖 | **0 新增** Maven 依赖;`com.sun.net.httpserver.HttpServer` JDK 内置;Jackson 已有(lingshu-core 传递依赖)|
| **NFR-006** | 安全 | HTTP server 监听 `0.0.0.0`(默认,允许容器 / K8s 外部访问);**不**做 auth(本 Story 服务端最小集,auth 留 #009b)|
| **NFR-007** | 可观测性 | 启动日志 + 停止日志(INFO 级);**不**集成 OTel / Micrometer(留 #010)|
| **NFR-008** | 错误恢复 | BindException 端口占用 → `LINGS-S06` 立即失败,不重试(运维人员介入);HTTP server 运行时异常 → 返 500 + JSON body(不挂进程)|

---

## 数据模型(扩展)

详见 [`data-model.md`](./data-model.md)。

**新增数据模型**(本 Story):
- `AgentCard`(Lombok `@Data`,字段:name/description/version/skills[]/capabilities/defaultInputModes[]/defaultOutputModes[]/provider/documentationUrl/iconUrl,详见 §5.6.3.0 L2492-2563)
- `AgentCard.AgentSkill`(Lombok `@Value`,字段:id/name/description/inputSchema/outputSchema/inputModes[]/outputModes[],L2567-2587)
- `AgentCard.AgentCapabilities`(Lombok `@Value`,字段:streaming/pushNotifications/stateTransitionHistory,L2591-2604)
- `AgentCard.AgentProvider`(Lombok `@Value`,字段:organization/url,L26XX)
- `AgentCard.SecurityScheme`(Lombok `@Value`,字段:type/scheme/in/OpenIdConnect/...)
- `AgentConfig.A2a`(Lombok `@Value`,字段:server.host/server.port,新增 `server` 嵌套对象,默认 port=8080)
- `LINGS-S06 A2A_SERVER_START_FAILED` ErrorCode(域字母 S = Slot,编号 06;BindException 绑定失败)
- `LINGS-T02 A2A_CARD_INVALID_CONFIG` ErrorCode(域字母 T = Tool,A2A 子域;`Identity.name` 空字符串校验)

**既有数据模型**(本 Story 复用,不改):
- `AgentConfig.Identity`(L140-152)
- `AgentConfig.Llm` / `AgentConfig.Prompt` / `AgentConfig.Memory` / ...

---

## 接口契约

详见 [`contracts/agent-card-http-api.md`](./contracts/agent-card-http-api.md) + [`contracts/a2a-server-lifecycle.md`](./contracts/a2a-server-lifecycle.md)。

| 契约 ID | 端点 / 方法 | Producer | Consumer |
|---|---|---|---|
| `lingshu.contract.agent-card-http-api.v1` | `GET /.well-known/agent.json` | `LocalAgentCardGenerator` + `A2aServer` | 远端 A2A client(其他 LingShu 实例 / Google A2A SDK / curl)|
| `lingshu.contract.a2a-server-lifecycle.v1` | `@PostConstruct start()` / `@PreDestroy stop()` | `A2aServer` | Spring 容器(`A2aServerAutoConfiguration`)|

---

## Out of Scope(本 Story **不**做)

- ❌ `HttpJsonRpcA2aTransport` concrete class(留 **Story #009b** —— client 侧实现)
- ❌ `RemoteAgentTool`(`@Component implements Tool` 模式,留 **#009b**)
- ❌ `AgentCardCache`(TTL 缓存 + 负缓存,留 **#009b** —— client 侧发现优化)
- ❌ `RemoteAgentSchemaBuilder`(扫 `AgentCard.skills[]` 生成 `ToolSpec` list,留 **#009c** —— client 侧 schema)
- ❌ `A2aTransportRouter` concrete stub(留 **#009b** —— 配 Slot 9 default Provider 时一起)
- ❌ Bearer / OAuth / mTLS auth(留 **#009b** —— client + server 一起做)
- ❌ SSE 流式 `message/stream`(留 **#009b**)
- ❌ Push Notification webhook(留 **v1.5+**)
- ❌ OTel / Micrometer 集成(留 **#010**)
- ❌ Graceful drain(stop 等待 in-flight,留 **#013**)
- ❌ HTTPS / TLS(留 **v1.5+** —— 用 Nginx / Envoy sidecar 反向代理)

---

## Story 边界检查(CLAUDE.md §11 #4)

- ✅ 5 文件改动 ≤ 5
- ✅ 2 ErrorCode 引入 ≤ 3
- ✅ 1 个核心实现模块 + 2 个测试文件 + pom.xml 改动(scope 紧凑)
- ✅ 0 新增 Maven 依赖(R-13 mitigation (d) 严格遵守)
