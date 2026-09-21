# Checklist: Story #009 a2a-agent-card Requirements Quality

**Feature**: Story #009 a2a-agent-card
**Created**: 2026-09-21
**Status**: Pre-implementation review

---

## 1. Requirement Quality Gate(需求质量门)

### 1.1 Spec 完整性

- [x] **WHY** 清晰:3 个 User Story 每个都有「Why this priority」段,解释业务驱动(AC-10 服务端缺失 + A2A 对称架构服务端半边空白)
- [x] **WHO** 明确:3 类 Persona(Alice 业务使用者 / Bob 业务配置方 / Charlie 框架贡献者)+ 10 个 Edge Cases
- [x] **WHAT** 可测:11 个 Acceptance Scenarios + 10 个 Edge Cases,每个都有 Given/When/Then
- [x] **反向 AC**(US3 占位 501 + EC-8 gzip 忽略 + EC-9 HTTPS connection refused)**显式**说明
- [x] **FR-001—FR-013** 13 项功能需求 + **NFR-001—NFR-008** 8 项非功能需求
- [x] **数据模型** `data-model.md`:6 新增类型(AgentCard + 4 nested type + AgentConfig.A2a)+ 2 ErrorCode
- [x] **接口契约** `contracts/agent-card-http-api.md` + `contracts/a2a-server-lifecycle.md`:HTTP API 契约 + Spring Bean 生命周期契约完整

### 1.2 Constitution 合规性

| 宪法条款 | 合规性 | 备注 |
|---|---|---|
| §1 #5 Skill 与 Tool 边界 | ✅ 0 破坏 | `AgentCard` 不属于 Skill/Tool,独立数据载体 |
| §1 #9 Plugin 发现 | ✅ 0 破坏 | `@AutoConfiguration + @Bean` 模式与 §5.5 plugin 样板对齐 |
| §1 #11 默认实现位置 | ✅ 0 破坏 | 落地 `lingshu-a2a-server`,不污染 `lingshu-core` |
| §1 #12 启动时配置校验 | ✅ 0 破坏 | `LocalAgentCardGenerator.generate()` 启动期校验 Identity.name |
| §2 13 项依赖锁定 | ✅ 0 新增 | `com.sun.net.httpserver.HttpServer` JDK 内置;Jackson 已传递依赖 |
| §3 NFR baseline | ✅ 0 破坏 | P99 ≤ 100ms / 冷启动 ≤ 5s / 13 项依赖锁定 |
| §4 错误码约定 | ✅ 2 新增(ErrorCode ≤ 3)| `LINGS-S06 A2A_SERVER_START_FAILED` + `LINGS-T02 A2A_CARD_INVALID_CONFIG` |
| §5 7 层金字塔 | ✅ 完整覆盖 | L1 Unit 12 + L2 Slice 2 + L5 E2E 1 = 15 case |
| §8 Glossary | ✅ 待补 | `AgentCard` 术语需在后续 Story 补入(本 Story 不动 §16)|
| §10 R-13 额外依赖风险 | ✅ 已缓解 | 0 新增 Maven 依赖,标记为「已缓解」(33% → 100%) |
| §11 #4 Story 边界 | ⚠️ 11 文件 > 5 | 主源 5 文件 + 测试 5 文件 + SPI 注册 1 文件 + AgentConfig 改动 1 文件 = 11;**已在 plan.md §2 决策理由详细论证可接受性** |

### 1.3 JDK 8 硬约束(CLAUDE.md §3)

- [x] **不**用 `record`(Lombok `@Value` 替代)
- [x] **不**用 `sealed` / `permits`(Lombok `@Value @Builder`)
- [x] **不**用 `var` 关键字(显式类型)
- [x] **不**用 `List.of(...)` / `Map.of(...)` / `Set.of(...)`(`Arrays.asList(...)` + `Collections.emptyList()`)
- [x] **不**用 Pattern matching for switch(`instanceof` + cast)
- [x] **不**用 Text blocks(普通 string concatenation)

### 1.4 Spring Boot SPI 边界(CLAUDE.md §11 #5)

- [x] **不**绕过 Spring Boot SPI:新增 `A2aServer` Bean 走 `@AutoConfiguration + @Bean` + `META-INF/spring/...AutoConfiguration.imports`
- [x] **不**硬编码 bean 实例化:全部走 Spring 容器管理
- [x] **不**用 `@AutoService`(那是 Google auto-service 库的注解,与 §5.7 SPI 决策冲突)

### 1.5 Story #001—#008 已 merged 契约不变

- [x] `LinearTurnEngine` / `MaxStepsExceeded` / `reactMaxSteps` 不动
- [x] `CancellationToken` 三层贯通不动
- [x] `TenantContext` ThreadLocal 不动
- [x] `AgentConfigRegistry` AtomicReference 不动
- [x] `YamlWatcher` 60s 轮询 + mtime 比对不动
- [x] 9 Slot 接口(`A2aTransport` stub)不动

### 1.6 R-13 mitigation (d) 自查(SOP §3.2 + §3.4 强制)

- [x] `mvn -pl lingshu-a2a-server dependency:tree` 实施前 + 实施后 diff = 0
- [x] PR body 末尾必有 `### R-13 dependency:tree 自查` 节
- [x] **不**新增 Maven 依赖(JDK HttpServer 替代 spring-boot-starter-web)
- [x] JDK 17 模块系统限制通过 `maven-surefire-plugin argLine` 解决,生产 `lingshu-cli` 启动脚本 export `MAVEN_OPTS`

### 1.7 拆分判断(Out-of-Scope 显式声明)

- [x] HttpJsonRpcA2aTransport concrete 留 **Story #009b**
- [x] RemoteAgentTool 留 **Story #009b**
- [x] AgentCardCache 留 **Story #009b**
- [x] RemoteAgentSchemaBuilder 留 **Story #009c**
- [x] A2aTransportRouter concrete 留 **Story #009b**
- [x] Bearer auth 留 **Story #009b**
- [x] SSE 流式 message/stream 留 **Story #009b**
- [x] Push Notification webhook 留 **v1.5+**
- [x] OTel 集成留 **Story #010**
- [x] Graceful drain 留 **Story #013**
- [x] HTTPS / TLS 留 **v1.5+**

### 1.8 关键不变项(plan.md §7)

- [x] 9 Slot 接口不变
- [x] SlotRouter / SlotProvider 体系不变(本 Story **不**实现 `A2aTransportRouter`,留 #009b)
- [x] AgentConfig.Identity / .Llm / .Prompt / .Memory / .Tenant 不变(仅**新增** `A2a` 嵌套类)
- [x] Story #001—#008 已 merged 契约不变
- [x] 13 项 Maven 依赖不变(R-13 0 新增)

### 1.9 反模式避免(plan.md §8)

- [x] 不引入 spring-boot-starter-web / Tomcat / Jetty / Netty(用 JDK HttpServer)
- [x] 不在 `A2aServer` 内做 JSON-RPC 业务处理(留 #009b)
- [x] 不实现 Bearer auth / graceful drain(留 #009b / #013)
- [x] 不用 spring `RestController`(用 `@AutoConfiguration + @Bean`)
- [x] 不注册多个 port(只 1 个 HTTP server)

---

## 2. Implementation Readiness Gate(实施就绪门)

- [x] spec.md ✅(本目录,394 行)
- [x] plan.md ✅(本目录,~330 行)
- [x] data-model.md ✅(本目录,~220 行)
- [x] research.md ✅(本目录,~270 行)
- [x] contracts/agent-card-http-api.md ✅(本目录,~190 行)
- [x] contracts/a2a-server-lifecycle.md ✅(本目录,~180 行)
- [x] quickstart.md ✅(本目录,~210 行)
- [x] tasks.md ⏳(待生成)

**进入实施阶段前必备**:
- [ ] `tasks.md` 生成 + Phase 1—7 全 T-NN 勾完
- [ ] `mvn validate` 通过
- [ ] `mvn -pl lingshu-a2a-server compile` 通过
- [ ] `mvn -pl lingshu-a2a-server test` 通过(15 case)
- [ ] `mvn -pl lingshu-a2a-server verify -Dtest=A2aServerIT` 通过(AC-10 black-box)
- [ ] R-13 dependency:tree diff 0 行
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节

---

## 3. Definition of Done(完成定义)

- [ ] 5 个主源文件编译过(`AgentCard` / `LocalAgentCardGenerator` / `A2aServer` / `A2aServerAutoConfiguration` / `LingsA2aServerException`)
- [ ] `AgentConfig.A2a` 嵌套类改动编译过
- [ ] `mvn -pl lingshu-a2a-server test` 15 case 全过
- [ ] `mvn -pl lingshu-a2a-server verify -Dtest=A2aServerIT` AC-10 black-box 过
- [ ] R-13 dependency:tree diff 0 行
- [ ] README.md 加 A2A 服务端示例
- [ ] `constitution.md` §10 R-13 标记为「已缓解」
- [ ] PR `feat(agent): Story #009 a2a-agent-card — LocalAgentCardGenerator + JDK HttpServer (AC-10)`
- [ ] PR body 贴 spec.md + plan.md + tasks.md + AC-10 黑盒验证输出 + R-13 自查报告

---

## 4. Open Questions(悬而未决,需在 PR review 阶段回答)

| # | 问题 | 备选答案 | 推荐 |
|---|---|---|---|
| Q-1 | Story #009b 拆分粒度? | (a) 整个 client 侧(RemoteAgentTool + HttpJsonRpcA2aTransport + AgentCardCache + A2aTransportRouter 一次合)| (b) 分 #009b(client core)+ #009c(schema) |
| Q-2 | `a2a.server.host` 是否需要支持 yml 嵌套? | (a) 支持(`a2a.server.host`)| (b) 仅硬编码 `0.0.0.0`,**不**暴露 yml 字段 |
| Q-3 | `Cache-Control: max-age=60` 是否合理? | (a) 60s(本 Story 默认)| (b) 5min(与 §5.6.4 默认 cardTtl 对齐)|

**默认采纳**:
- Q-1:备选 (b) 拆分(降低 #009b 风险,本 Story 已经 11 文件,再扩 client 侧会更超边界)
- Q-2:备选 (a) 支持(与 `a2a.server.port` 风格一致,给本地调试 127.0.0.1 留口)
- Q-3:备选 (a) 60s(本 Story 服务端最小集,5min 缓存对单实例没意义;后续 #009b 加 AgentCardCache 客户端缓存时再考虑 5min)
