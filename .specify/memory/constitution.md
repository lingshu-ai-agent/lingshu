# 灵枢 LingShu 项目宪法(constitution)

> **法律地位**:本文件是 LingShu 工程的"宪法"。所有 Story / 实施 / PR 必须受其约束。
> **修改门槛**:宪法任何修改必须走 RFC 流程(开 issue + 评审),**Story 实施期不得擅自改宪**。
> **版本**:v1.0 — 2026-09-20 蒸馏
> **输入**:dsh_agent_design.md v1.5.34(项目真理,只读)

---

## §1 项目原则(12 项锁定决策)

> 来源:dsh §1「锁定的设计决策」(L159-174)

| # | 决策 | 落地形式 |
|---|---|---|
| 1 | **JDK 8 兼容**(主要目标)| sealed → `abstract class` / records → Lombok `@Value` / pattern-switch → `instanceof` / **不用 `var`** / `List.of` `Map.of` `Set.of` → `Collections.empty*()` |
| 2 | Reactive 选型 | `org.reactivestreams.Publisher`(JDK 8 标准方案)+ 自写轻量 collector |
| 3 | RuntimeSandbox 强度 | chroot 到 working dir + 命令/域名白名单,JVM 内实现 |
| 4 | Compactor v1 | 仅截断超长 `ToolResult` + 滑动窗口保留最近 N turn;语义摘要留 v2 |
| 5 | Skill 与 Tool 边界 | Skill 与 Tool 共用接口,运行时行为完全一致;Skill 既能被模型自动调用(对模型可见 schema),也能被用户通过 `/xxx` 显式触发 |
| 6 | 子 Agent 注册 | `SubAgentType` 枚举 + `application.yml` 显式声明 + 启动期一致性校验 |
| 7 | 编排可扩展 | 抽出 `FlowEngine` 接口,默认 `LinearTurnEngine`,DAG 引擎作为另一 SPI 实现 |
| 8 | Slot 选用方式 | `Provider` 模式(多实现共存,按 name + priority 选用) |
| 9 | Plugin 发现 | **Spring Boot Auto-Config**(`META-INF/spring/...AutoConfiguration.imports` 一行),**不**选 Java SPI / OSGi / ClassLoader 隔离(§5.7) |
| 10 | 同名 Provider | `priority()` 最大胜出;启动日志列出所有 Provider 与冲突覆盖关系(§5.2) |
| 11 | 默认实现位置 | `lingshu-core` 内置;**用户不引入即零默认**(通过 `@AutoConfiguration` 按需加载) |
| 12 | 配置校验 | **启动时**(不是运行时);`AgentFactory.create()` 集中校验所有 name 与必填项(§7.1.2 T1) |

---

## §2 技术栈锁定(13 项)

> 来源:dsh §10.1 L6303-6332(13 项锁合计,CLAUDE.md §11 #6 引用)

| # | 依赖 | 版本 | 用途 |
|---|---|---|---|
| 1 | **Java 编译目标** | 1.8 | 全仓编译级别(企业 JDK 8 硬约束)|
| 2 | `spring-boot-dependencies` | 3.2.5(BOM)| Spring Boot SPI 必需 |
| 3 | `org.projectlombok:lombok` | 1.18.30 | `@Value` / `@Builder` / JDK 8 兼容最新 LTS |
| 4 | `org.reactivestreams:reactive-streams` | 1.0.4 | JDK 8 标准 Reactive Streams |
| 5 | `com.fasterxml.jackson.core:jackson-databind` | 2.15.x(Spring Boot BOM 管理)| YAML 解析 / AgentCard JSON |
| 6 | `io.opentelemetry:opentelemetry-api` | 1.32.x | §14.1 trace / metrics(1.x → 2.x API 不兼容) |
| 7 | `spring-boot-starter-actuator` | 3.2.5 | §14.5 HealthIndicator |
| 8 | `org.junit.jupiter:junit-jupiter` | 5.10.x | 单元测试 |
| 9 | `org.assertj:assertj-core` | 3.24.x | 流式断言 |
| 10 | `org.mockito:mockito-core` | 5.x | Mock 框架(JDK 21+ Mockito 6 不兼容 JDK 8) |
| 11 | `org.awaitility:awaitility` | 4.2.x | 异步事件断言 |
| 12 | `org.yaml:snakeyaml` | 2.x | application.yml 解析(Spring Boot BOM 管理;注意 snakeyaml 2.x 不再支持 JDK 8 但 Spring Boot 3.2.x 通过 `snakeyaml-engine` 适配) |
| 13 | `org.springframework.ai:spring-ai-bom` | 1.0.0-M6 | §4.10.1 LLM 协议转换 + `@Tool` Schema 生成(**只**用这两件事) |

**新增依赖 RFC**:任何新依赖必须先开 RFC + `dependency:tree` CI 卡点 + `banned-dependencies` enforcer build 阶段 fail(详见 §10 R-13)。

---

## §3 NFR 基线(性能预算 / 容量)

> 来源:dsh §14.15.1 L6977-7005(只抄数字,不引入新阈值)

| 指标 | 目标 |
|---|---|
| LLM 流式首 token | **P50 ≤ 1.5s / P99 ≤ 3.0s** |
| turn 完成(10 steps)| **P50 ≤ 30s / P99 ≤ 60s** |
| Tool 调用 | P99 ≤ `toolTimeoutSec` |
| 单 turn history | ≤ 100K tokens |
| **冷启动到首个 token** | **≤ 30s**(空 yml 场景,验证 AC-01) |
| 并发 turn 数 | 默认 16,排队 ≤ 32 |
| Binary size baseline | < 35MB(R-13 mitigation (d)) |

**NFR 退化禁令**:任何 Story 不得让上述指标退化 > 20%,否则需 RFC + 压测验证(§14.15.7 review)。

---

## §4 错误码约定(LINGS-<域><编号>)

> 来源:dsh §15 全节(L7100-7195)+ §15.9 编码约定(L7184-7193)

**命名规范**:`LINGS-<域字母><2 位数字>`(共 10 域,编号 01—99):

| 域字母 | 域 | Story #001 涉及 |
|---|---|---|
| `C` | Config(配置)| **LINGS-C02 / C03 / C04**(AgentConfig 启动校验 / YAML 占位符未解析 / YAML 占位符环) |
| `S` | Slot(SPI)| **LINGS-S01 / S05**(name 不在 Router / Provider init 失败) |
| `L` | LLM | (Story #003)|
| `T` | Tool | (Story #004 起)|
| `X` | Sandbox | (Story #001 后)|
| `R` | ReAct | (Story #008)|
| `A` | Audit | (Story #016)|
| `M` | MCP | **LINGS-M01 / M02 / M03**(Story #021a/b/c: connect failed / tools/call failed / HTTP upgrade) |
| `D` | Delegate(子 Agent)| **LINGS-D01**(Story #023 delegate.types 缺 subagent_type 启动 fail-fast)|
| `Z` | 其他 | **LINGS-Z01**(不变量违反)|

**约束**:
- 抛出方**必须**带 `errorCode` 字段 + `cause`(cause chain ≥ 2 层)+ 可选 `hint`(人话建议)
- 编号在本域内递增,**删除的不复用**
- 业务层允许自定 `LINGS-<USER>-xxx`,但推荐走 §6 SPI `ErrorCode` 接口而非字符串拼接

---

## §5 测试策略(7 层金字塔)

> 来源:dsh §14.15.7(待 §14 全文精读后补全;CLAUDE.md §11 #2 "不省略 AC 黑盒验证"补充)

| 层级 | 范围 | 覆盖率门槛 |
|---|---|---|
| L1 Unit | 单个类 / 方法 | 新增 Slot 接口 100% / 其他 ≥ 80% |
| L2 Slice | 单 Slot + Mock 依赖 | 1 happy-path + 1 fail-path |
| L3 Component | 多 Slot 集成 | 关键路径 |
| L4 Contract | SPI 兼容 / 跨模块 | 每次改接口必跑 |
| L5 E2E / Smoke | 完整 CLI 跑通 | §0.4 AC-01—AC-10 全过(每夜 + release gate) |
| L6 Performance | k6 + JMeter 压测 | §3 NFR 不退化 |
| L7 兼容 | JDK 8 / 17 / 21 + 多 OS | CI matrix |

**每个 Story 必须自带 validate**:跑完所有 AC-NN 才算完成,否则 PR 不合(SOP §4.3)。

---

## §6 兼容性矩阵

> 来源:dsh §14.15.5(待 §14 精读后补全;CLAUDE.md §2 + R-06 已锁定)

| 项 | 支持范围 |
|---|---|
| Java 编译目标 | 1.8 |
| 运行 JRE | JDK 8 / 11 / 17 / 21 LTS(**Spring Boot 3.2.5 实际跑需 JDK 17+**;R-06) |
| JVM 厂商 | Temurin / Zulu / Alibaba Dragonwell / IBM Semeru |
| Spring Boot | 3.2.5(每年 1 次 minor 升级支持窗口) |
| OS | Linux x86_64 / arm64 + macOS x86_64 / arm64(开发机) + Windows WSL2 |
| OTel | 1.x LTS(**不跨 2.x**) |

**R-06 备注**:LingShu 二进制 target=8,但 Spring Boot 3.2.x + Spring AI 1.x 完整体验需 JDK 17+ runtime。文档须明示。

---

## §7 支持矩阵 / LTS 政策

> 来源:dsh §14.15.6(待 §14 精读后补全)

| 项 | 升级窗口 |
|---|---|
| Spring Boot | 每年 1 次 minor 升级支持 |
| Spring AI | 1.x 内 patch 升级,**不跨 2.x**(API 不兼容) |
| Lombok | 1.18.x patch 升级,minor 升级需全仓 CI 验证 |
| OTel | 1.x patch 升级,**不跨 2.x** |
| JDK | 编译 target 永久锁 1.8;运行 LTS 4 个版本支持窗口 |

---

## §8 Glossary(精简 15 术语)

> 来源:dsh §16 L7197-7225(本工程最常用术语)

| 术语 | 含义 | 首次定义 |
|---|---|---|
| **Slot** | 9 个可插拔扩展点(LlmProvider / Tool / Sandbox / Skill / SessionStore / Compactor / PromptBuilder / FlowEngine / A2aTransport)| §1 / §5.1 |
| **Provider** | Slot 的 SPI 实现,带 `name()` / `priority()` | §5.1 |
| **SlotRouter** | 运行时从 N 个同 Slot Provider 中"按 yml 选 1 个"的策略器 | §5.2 |
| **FlowEngine** | 控制 Agent turn 主循环的编排器(可替换为 ADK / LangGraph4j)| §4.11 |
| **LinearTurnEngine** | FlowEngine 默认实现 = ReAct Loop | §6.1 |
| **ReAct Loop** | Reason+Act 范式,modern function-calling 实现 | §6.1 |
| **A2aTransport** | Slot 9,Agent ↔ Agent 通信协议 | §5.6 |
| **AgentCard** | A2A 协议"名片"(JSON,name / skills / endpoint),从 `cfg.getIdentity()` 自动生成 | §5.6.8 |
| **Session** | 一个 Agent 与一个用户的完整对话上下文,跨 turn 持久化 | §4.12.3 |
| **Turn** | Session 内一次"用户输入 + LLM 反应 + 工具调用 + 完成"原子单元 | §4.12 |
| **Identity** | Agent 业务人设(name / role / language / traits / tone / avatar) | §4.12.2 |
| **CLAUDE.md** | 项目级长期记忆(类似 Claude Code 的项目约定文件) | §4.12.2 |
| **CancellationToken** | 三层贯通(FlowEngine / LlmProvider / ToolExecutor)协作式取消令牌 | §14.12 |
| **Zero-config** | 空 yml 即用所有默认值启动(§8.0 + CLAUDE.md §7 第 5 条)| §8.0 |
| **`@Value`** | Lombok 不可变值对象(JDK 8 约束下 records 替代品)| §4 开头 |

---

## §9 Review 节奏

> 来源:dsh §17 L7254 + §14.15.7 末段

| 触发 | 节奏 |
|---|---|
| Risk Register 月度 review | 每月 1 号 |
| 每个 RC 发布前 | 必走 |
| 每个 GA 发布前 | 必走 |
| Constitution 修改 | RFC 流程(开 issue + 评审) |
| Story AC 验收 | 每个 Story 合入前必走 |

---

## §10 风险登记(只抄 ≥ 6 分)

> 来源:dsh §17 L7235-7248(分值 = 概率 × 影响,≥ 6 必缓解)

| ID | 风险 | 概率×影响 | 缓解措施 | Owner | 触发 |
|---|---|---|---|---|---|
| **R-01** | ReAct 循环在大模型下死循环 | 2×3=6 | `reactMaxSteps` 硬上限(默认 50)+ 触发 R01 + R02 同(tool, args)循环检测 | Charlie | v1.0 GA |
| **R-02** | 多租户 ThreadLocal 泄漏 | 2×3=6 | **已落地 (Story #006)**:TenantContext `try-finally` 兜底 + `Deque` 嵌套栈(替代单值 ThreadLocal)+ `snapshot` + `runWithSnapshot` 显式跨线程传递(主动放弃 `InheritableThreadLocal`,避免线程池复用场景下上一个任务的 tenant 泄漏到下一个任务)+ `tenantId` 正则 `[a-zA-Z0-9_-]{1,64}` 启动期 fail-fast | Charlie | v1.0 GA |
| **R-03** | YAML 热更与 in-flight turn 数据竞争 | 2×3=6 | **已落地 (Story #007)**:(a) `AgentConfigRegistry` `AtomicReference<AgentConfig>` 单写多读 lock-free(单 publish ≤ 1ms);(b) `DefaultAgent.run()` 入口一次性 `registry.current()` freeze — Java 引用语义 + `AgentConfig` `@Value` immutable 字段自然冻结旧 turn 的 cfg 引用;(c) `validateOrThrow` 拒绝破坏性 cfg + rollback 保留旧 cfg + YamlWatcher `lastSeen` 不更新 → 下次 5s 自动重试(R-03 完整 3 件套) | Charlie | v1.0 GA |
| **R-04** | A2A 协议 v0.5 快速演进破坏兼容 | 3×2=6 | `version()` 字段 + Slot 接口兼容性校验 + AgentCard schema 版本 | Alice | v0.5-α |
| **R-06** | **JDK 8 vs Spring Boot 3.2.x + Spring AI 1.x 矛盾(均需 JDK 17+ runtime)** | **3×3=9** | (a) target=8 兼容 JDK 8 JRE;(b) 文档明示 LingShu 完整体验需 JDK 17+;(c) v1.1 决定是否提供 JDK 8 独立运行时 | Alice | v1.0 GA 前 |
| **R-09** | 第三方 Provider transitive 依赖污染 | 2×3=6 | (a) plugin SPI jar `<scope>provided</scope>`;(b) `dependency:tree` CI;(c) `banned-dependencies` enforcer | Alice | v1.0 GA |
| **R-10** | Maven Central 发布权限 / GPG 签名错误 | 1×3=3 | `lingshu-release` GitHub Action + 2FA + 发布 checklist | Charlie | v1.0.0 GA 前 |
| **R-11** | lingshu-docs 站点 404 / CDN 假缓存 | 2×1=2 | `curl -sLI` 验整链路 + Pages 状态监控 | Charlie | 已发生(2026-09-06) |
| **R-13** | **Spring AI starter 误用(transitive 污染 + binary 膨胀)** | **2×3=6** | (a) **只**引 `spring-ai-core` + 实际用 provider starter,不用 `spring-ai-spring-boot-starter` 全家桶;(b) `banned-dependencies` enforcer build 阶段 fail;(c) binary size baseline < 35MB,CI delta > 10% fail;(d) Story #001 / #003 / #009 实施者**必须**先 `mvn dependency:tree` 自查 + 贴关键子树到 PR body | Charlie | **已缓解 ✅(Story #009 + #009a + #009b + #009c + #009d + #020a + #021a + #021b + #021c + #009e + #022 + #023 + #024 follow-up + #025 follow-up)** — Story #009a `lingshu-a2a-client` 模块作为可选 SPI(只有 `agent.a2aTransport: grpc-1.0.0` 才装载)binary +44MB,核心 CLI distribution 不受影响(29MB < 35MB baseline);grpc streaming subscribe 高效换 binary 增量按 dsh §5.6.3.2 L3296-3299 显式接受 trade-off;**Story #009b InProcess(0 增量)** 同 lingshu-a2a-client 模块 0 新依赖,**Story #009c HttpJsonRpc(0 增量)** 用 JDK 17 内置 `java.net.http.HttpClient` **0 新依赖**(R-13 mitigation (d) baseline 镜像 pre/post dep-tree 仅时间戳差异 PASS),**Story #009d RemoteAgentSchemaBuilder / AgentRef / RemoteAgentTool 5-arg ctor(0 增量)** 也是只用 Jackson / Spring / Lombok 已锁 13 项依赖 0 新增(R-13 mitigation (d) baseline 镜像同 #009c 路径再次验证 pre/post dep-tree 仅时间戳差异 PASS);**Story #020a skill-foundation(0 增量)** 复用 `spring-context:6.1.6` 已含的 `Environment` / `MapPropertySource` / `InitializingBean` + Jackson `ObjectMapper` + Lombok 已锁 13 项依赖 0 新增,SkillTool / CommitSkill / ToolRegistry 4 方法 + SkillAutoConfiguration 注册样板 5 文件 / 54 测试 case 全过,binary baseline 维持 0 delta(R-13 mitigation (d) 路径第 5 次验证 PASS);**Story #021a mcp-stdio-transport(0 增量)** —— `StdioMcpServerConnection` 用 JDK 内置 `ProcessBuilder` 拉子进程 + `BufferedReader` 读 stdout + Jackson `ObjectMapper` 解析 JSON-RPC,9 核心 Java 源文件(`McpTransportType` enum + `McpServerConfig` POJO + `ConnectionState` enum + `McpServerConnection` interface + `McpToolDescriptor` + `McpCallResult` + `McpTransportException` + `McpServerConnectionFactory` + `StdioMcpServerConnection`) + 1 modify(`AgentConfig.ServerConfig` 扩 5 字段) 全 0 新 Maven 依赖,36 测试 case 跨 12 文件 0 fail / 0 error / 0 skipped,binary baseline 维持 0 delta(R-13 mitigation (d) 路径第 6 次验证 PASS —— `mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 0 binary delta;`banned-dependencies` enforcer 不 fail);**新备注**:compile target 1.8 → 1.11 仅限 `lingshu-a2a-client` 模块,其他模块(`lingshu-core` / `lingshu-a2a-server`)仍 JDK 1.8(因 `java.net.http.HttpClient` JDK 11+ 要求,其它模块无 HTTP 客户端直连需求维持 1.8);**3 个 A2aTransport 变体全栈已就位**:`grpc-1.0.0`(+44MB trade-off)/ `http-jsonrpc-1.0.0`(0 增量,默认)/ `in-process-1.0.0`(0 增量,同 JVM 直连);**Schema 枚举层(Story #009d 完成)** —— RemoteAgentSchemaBuilder 扫 `AgentCard.skills[]` 动态生成 `ToolSpec` + RemoteAgentTool description() 3-分支 logic(BASE / HINT / 列表截断 `and K more`)+ AgentCardCache TTL+负缓存设计已在 dsh §5.6.3.0 P0 路径 baseline 完成,无 OQ-Future 转 OQ-Completed;**MCP 支链 A 第 1 块(Story #021a 完成)** —— `McpServerConnection` interface + `ConnectionState` 6 态 + `StdioMcpServerConnection` 完整实现 + `McpServerConnectionFactory` 静态分派(SSE/HTTP 抛 LINGS-M01,`#021c` 才落地) + `LINGS-M01 MCP_CONNECT_FAILED` 新增 ErrorCode(域字母 M=MCP 新域);**LINGS-M01** 触发场景 = (a) cfg.transport = SSE/STREAMABLE_HTTP 但仅 stdio 实现落地 / (b) stdio 子进程 spawn 失败 / initialize handshake 超时 / tools/list 解析失败,**不**抛异常绕过 `ToolExecutor` 5 步流水线 §4.10.1 硬规则 2;**Story #021b mcp-tool-adapter(0 增量)** —— `McpTransport` listener 模式 + `McpToolAdapter` Tool 包装 + `ToolRegistry.unregister` SPI 扩展 + `LINGS-M02 tools/call failed` ErrorCode,**0 新 Maven 依赖**;**Story #021c mcp-sse-and-http-transport(0 增量)** —— `McpHttpSupport` utility + `SseMcpServerConnection`(JDK HttpURLConnection + 手写 SSE parser)+ `StreamableHttpMcpServerConnection`(无状态 HTTP POST tools/*)+ `LINGS-M03 HTTP upgrade / SSE event format` ErrorCode,**0 新 Maven 依赖**;**Story #009e a2a-remote-tool-wiring(0 增量)** —— 抽 `RemoteAgentToolAutoConfiguration` 独立 + `RemoteAgentToolLifecycle` SmartLifecycle + 3 transport 共用 wiring + 12 net new case,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 6.5 次验证 PASS);**Story #022 spring-ai-annotation-tool(0 增量)** —— `@AgentTool` 注解(复用 spring-ai `@Tool` 因 spring-ai-bom 已锁 13 项依赖表内)+ `SpringAiToolAdapter`(Jackson `ObjectMapper` / `ObjectNode` + Lombok 已锁)+ `AgentToolScanner`(`ApplicationContextAware` 来自 `spring-context` transitive)+ `JsonArgsConverter`(JDK 8 + Jackson 已锁)+ `LINGS-T08` 反射调用失败 ErrorCode,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 7 次验证 PASS);**Story #023 delegate-sub-agent(0 增量)** —— `SubAgentType` enum(JDK 8 `Enum` + `Arrays.stream` + `Collectors.toCollection(LinkedHashSet::new)`)+ `DelegateErrorCodes.LINGS_D01`(第 9 域字母 = Delegate 加入;C/S/L/T/X/R/A/M/D/Z = 10 域)+ `SubAgentInheritance` 静态工具类(JDK 8 `LinkedHashMap` + `Collections.emptyMap()` + `AgentConfig` 24 字段手工 `new`)+ `DelegateTool`(`name()="Task"` + Jackson `JsonNode`/`ObjectMapper`/`ObjectNode`/`ArrayNode` 已锁 + `agentFactory.create(childConfig)` 复用 dsh §7.1 不变项)+ `DelegateAutoConfiguration`(`@Configuration` + `InitializingBean` 复用 e13e6a5 fix + `AgentConfigRegistry` / `ToolRegistry` / `AgentFactory` 全部已存在),`pom.xml` 0 行 diff vs `e3d2468` baseline,`mvn -pl lingshu-core dependency:tree -Dverbose` pre/post diff 仅 `[INFO] Finished at: <timestamp>` 时间戳差异 = 0 binary delta;`banned-dependencies` enforcer Rule 0 passed;23 测试 case 跨 4 文件 0 fail / 0 error / 0 skipped,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 8 次验证 PASS);**Story #024 follow-up a2a-server-tool-registry-dispatch(0 增量)** —— `A2aServer.handleMessageSend` 真接 dispatch 到本地 `ToolRegistry.execute()` + serve-mode SIGTERM clean shutdown + `tools/cleanup-ports.sh`,复用 JDK `HttpServer` + `ToolExecutionContext` 已有 stub,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 9 次验证 PASS);**Story #025 follow-up demo-product-sandbox-wiring(0 增量)** —— `DemoProductApplication.readSandbox(Environment)` 私有静态 helper(JDK 8 `Paths.get` + `env.getProperty` + `Collections.emptyList()`)+ `readSandboxList(env, prefix, fallback)` 索引遍历 helper + `mergeConfig()` 签名 +1 `AgentConfig.Sandbox` 参数(`@Value` 24-字段构造器位置 5)+ `application.yml` 顶层 `agent.sandbox:` 5 字段配置补全 + `README.md` 9 features 同步,3 文件改动 / ~110 行 Java + ~35 行 YAML + ~3 行 README,`mvn -pl lingshu-examples/demo-product dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta;`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 10 次验证 PASS);**Story #026 yaml-placeholder-resolution(0 增量)** —— `PlaceholderResolver` 静态工具类(JDK 8 `LinkedHashSet` + `StringBuilder` + brace-counting 自实现 scanner,4-form grammar `${X}` / `${X:default}` / `${X:${Y}}` 嵌套 / `$${literal}` 转义 + 32 层递归深度上限 + `Set<String> visited` 环检测)+ `YamlPlaceholderErrorCodes.LINGS_C03 / LINGS_C04`(Config 域 C 段 3/4 号 = YAML_PLACEHOLDER_UNRESOLVED / YAML_PLACEHOLDER_CYCLE)+ `AgentFactory.loadYamlAndValidate` hook `PlaceholderResolver.resolvePlaceholders(agent, ymlPath)`(`parseMinimalYaml` 与 `toAgentConfig` 之间 1 行)+ `LingsConfigException` 复用 + ErrorCode 嵌入 message 模式对齐 `LinearTurnEngine.LINGS-C02`(让 `assertThatThrownBy().hasMessageContaining("LINGS-C0X")` 工作);修跨路径 placeholder parity bug —— 之前 CLI / `YamlWatcher` hot-reload hand-rolled 路径下 `${user.dir}` 静默变成 13 字符串字面,Spring Env 路径(`demo-product`)一直支持;3 new (`PlaceholderResolver.java` ~250 行 + `YamlPlaceholderErrorCodes.java` ~30 行 + `PlaceholderResolverTest.java` ~250 行) + 2 modify (`AgentFactory.java` +6 行 hook + `YamlHotReloadIT.java` +2 case + 2 helper),`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta;`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 11 次验证 PASS);**累计** 16 个 Story 全 0 binary delta 通过(10 个 0 增量 + 1 个 #009a +44MB trade-off + 2 个 follow-up demo 子模块),Spring Boot SPI + JDK 内置 + Jackson + Lombok 已锁 13 项依赖表完整不变;**R-13 标记「已缓解」** 100% 闭合率 |

**等级**:≥ 6 必缓解 / 4-5 有缓解 / ≤ 3 接受风险 + 监控。

---

## 附录:本宪章与 SpecKit 的关系

- SpecKit CLI(`/specify` `/plan` `/tasks`)在 `/plan` 时强制读本宪法,违反任何条款须在 plan.md 显式说明 + 走 RFC
- Story 实施期发现宪章缺失 → 开 RFC,**不得**绕过宪章自行决策
- Story 实施期发现宪章与 dsh 冲突 → dsh 为准,本宪章同步修订(走 RFC)

---

**宪法版本**:v1.0
**蒸馏日期**:2026-09-20
**下次 review**:2026-10-01(月度)
**Owner**:Charlie(项目维护者)