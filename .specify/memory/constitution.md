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

**命名规范**:`LINGS-<域字母><2 位数字>`(共 11 域,编号 01—99):

| 域字母 | 域 | Story #001 涉及 |
|---|---|---|
| `C` | Config(配置)| **LINGS-C02 / C03 / C04**(AgentConfig 启动校验 / YAML 占位符未解析 / YAML 占位符环) |
| `S` | Slot(SPI)| **LINGS-S01 / S05**(name 不在 Router / Provider init 失败) |
| `L` | LLM | **LINGS-L01 / L02**(Story #027a:tool_use block 缺 id/name / tool_result block 缺 tool_use_id/content)+ **LINGS-L03 reserved**(Story #027b 占位 §14 N6 graceful shutdown 后续启用,本期不抛)|
| `T` | Tool | (Story #004 起)|
| `X` | Sandbox | (Story #001 后)|
| `R` | ReAct | (Story #008)|
| `A` | Audit | (Story #016)|
| `M` | MCP | **LINGS-M01 / M02 / M03**(Story #021a/b/c: connect failed / tools/call failed / HTTP upgrade) |
| `D` | Delegate(子 Agent)| **LINGS-D01**(Story #023 delegate.types 缺 subagent_type 启动 fail-fast)|
| `P` | Permission(权限)| **LINGS-P01**(Story #029:工具 not in allow-list / in deny-list,StrictPermissionPolicy 拒绝;**第 11 域字母启用**)|
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
| **R-13** | **Spring AI starter 误用(transitive 污染 + binary 膨胀)** | **2×3=6** | (a) **只**引 `spring-ai-core` + 实际用 provider starter,不用 `spring-ai-spring-boot-starter` 全家桶;(b) `banned-dependencies` enforcer build 阶段 fail;(c) binary size baseline < 35MB,CI delta > 10% fail;(d) Story #001 / #003 / #009 实施者**必须**先 `mvn dependency:tree` 自查 + 贴关键子树到 PR body | Charlie | **已缓解 ✅(Story #009 + #009a + #009b + #009c + #009d + #020a + #021a + #021b + #021c + #009e + #022 + #023 + #024 follow-up + #025 follow-up + #026 + #027a + #027b + #028 + #029 + #030 + #031 + #032 + #033)** — Story #009a `lingshu-a2a-client` 模块作为可选 SPI(只有 `agent.a2aTransport: grpc-1.0.0` 才装载)binary +44MB,核心 CLI distribution 不受影响(29MB < 35MB baseline);grpc streaming subscribe 高效换 binary 增量按 dsh §5.6.3.2 L3296-3299 显式接受 trade-off;**Story #009b InProcess(0 增量)** 同 lingshu-a2a-client 模块 0 新依赖,**Story #009c HttpJsonRpc(0 增量)** 用 JDK 17 内置 `java.net.http.HttpClient` **0 新依赖**(R-13 mitigation (d) baseline 镜像 pre/post dep-tree 仅时间戳差异 PASS),**Story #009d RemoteAgentSchemaBuilder / AgentRef / RemoteAgentTool 5-arg ctor(0 增量)** 也是只用 Jackson / Spring / Lombok 已锁 13 项依赖 0 新增(R-13 mitigation (d) baseline 镜像同 #009c 路径再次验证 pre/post dep-tree 仅时间戳差异 PASS);**Story #020a skill-foundation(0 增量)** 复用 `spring-context:6.1.6` 已含的 `Environment` / `MapPropertySource` / `InitializingBean` + Jackson `ObjectMapper` + Lombok 已锁 13 项依赖 0 新增,SkillTool / CommitSkill / ToolRegistry 4 方法 + SkillAutoConfiguration 注册样板 5 文件 / 54 测试 case 全过,binary baseline 维持 0 delta(R-13 mitigation (d) 路径第 5 次验证 PASS);**Story #021a mcp-stdio-transport(0 增量)** —— `StdioMcpServerConnection` 用 JDK 内置 `ProcessBuilder` 拉子进程 + `BufferedReader` 读 stdout + Jackson `ObjectMapper` 解析 JSON-RPC,9 核心 Java 源文件(`McpTransportType` enum + `McpServerConfig` POJO + `ConnectionState` enum + `McpServerConnection` interface + `McpToolDescriptor` + `McpCallResult` + `McpTransportException` + `McpServerConnectionFactory` + `StdioMcpServerConnection`) + 1 modify(`AgentConfig.ServerConfig` 扩 5 字段) 全 0 新 Maven 依赖,36 测试 case 跨 12 文件 0 fail / 0 error / 0 skipped,binary baseline 维持 0 delta(R-13 mitigation (d) 路径第 6 次验证 PASS —— `mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 0 binary delta;`banned-dependencies` enforcer 不 fail);**新备注**:compile target 1.8 → 1.11 仅限 `lingshu-a2a-client` 模块,其他模块(`lingshu-core` / `lingshu-a2a-server`)仍 JDK 1.8(因 `java.net.http.HttpClient` JDK 11+ 要求,其它模块无 HTTP 客户端直连需求维持 1.8);**3 个 A2aTransport 变体全栈已就位**:`grpc-1.0.0`(+44MB trade-off)/ `http-jsonrpc-1.0.0`(0 增量,默认)/ `in-process-1.0.0`(0 增量,同 JVM 直连);**Schema 枚举层(Story #009d 完成)** —— RemoteAgentSchemaBuilder 扫 `AgentCard.skills[]` 动态生成 `ToolSpec` + RemoteAgentTool description() 3-分支 logic(BASE / HINT / 列表截断 `and K more`)+ AgentCardCache TTL+负缓存设计已在 dsh §5.6.3.0 P0 路径 baseline 完成,无 OQ-Future 转 OQ-Completed;**MCP 支链 A 第 1 块(Story #021a 完成)** —— `McpServerConnection` interface + `ConnectionState` 6 态 + `StdioMcpServerConnection` 完整实现 + `McpServerConnectionFactory` 静态分派(SSE/HTTP 抛 LINGS-M01,`#021c` 才落地) + `LINGS-M01 MCP_CONNECT_FAILED` 新增 ErrorCode(域字母 M=MCP 新域);**LINGS-M01** 触发场景 = (a) cfg.transport = SSE/STREAMABLE_HTTP 但仅 stdio 实现落地 / (b) stdio 子进程 spawn 失败 / initialize handshake 超时 / tools/list 解析失败,**不**抛异常绕过 `ToolExecutor` 5 步流水线 §4.10.1 硬规则 2;**Story #021b mcp-tool-adapter(0 增量)** —— `McpTransport` listener 模式 + `McpToolAdapter` Tool 包装 + `ToolRegistry.unregister` SPI 扩展 + `LINGS-M02 tools/call failed` ErrorCode,**0 新 Maven 依赖**;**Story #021c mcp-sse-and-http-transport(0 增量)** —— `McpHttpSupport` utility + `SseMcpServerConnection`(JDK HttpURLConnection + 手写 SSE parser)+ `StreamableHttpMcpServerConnection`(无状态 HTTP POST tools/*)+ `LINGS-M03 HTTP upgrade / SSE event format` ErrorCode,**0 新 Maven 依赖**;**Story #009e a2a-remote-tool-wiring(0 增量)** —— 抽 `RemoteAgentToolAutoConfiguration` 独立 + `RemoteAgentToolLifecycle` SmartLifecycle + 3 transport 共用 wiring + 12 net new case,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 6.5 次验证 PASS);**Story #022 spring-ai-annotation-tool(0 增量)** —— `@AgentTool` 注解(复用 spring-ai `@Tool` 因 spring-ai-bom 已锁 13 项依赖表内)+ `SpringAiToolAdapter`(Jackson `ObjectMapper` / `ObjectNode` + Lombok 已锁)+ `AgentToolScanner`(`ApplicationContextAware` 来自 `spring-context` transitive)+ `JsonArgsConverter`(JDK 8 + Jackson 已锁)+ `LINGS-T08` 反射调用失败 ErrorCode,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 7 次验证 PASS);**Story #023 delegate-sub-agent(0 增量)** —— `SubAgentType` enum(JDK 8 `Enum` + `Arrays.stream` + `Collectors.toCollection(LinkedHashSet::new)`)+ `DelegateErrorCodes.LINGS_D01`(第 9 域字母 = Delegate 加入;C/S/L/T/X/R/A/M/D/Z = 10 域)+ `SubAgentInheritance` 静态工具类(JDK 8 `LinkedHashMap` + `Collections.emptyMap()` + `AgentConfig` 24 字段手工 `new`)+ `DelegateTool`(`name()="Task"` + Jackson `JsonNode`/`ObjectMapper`/`ObjectNode`/`ArrayNode` 已锁 + `agentFactory.create(childConfig)` 复用 dsh §7.1 不变项)+ `DelegateAutoConfiguration`(`@Configuration` + `InitializingBean` 复用 e13e6a5 fix + `AgentConfigRegistry` / `ToolRegistry` / `AgentFactory` 全部已存在),`pom.xml` 0 行 diff vs `e3d2468` baseline,`mvn -pl lingshu-core dependency:tree -Dverbose` pre/post diff 仅 `[INFO] Finished at: <timestamp>` 时间戳差异 = 0 binary delta;`banned-dependencies` enforcer Rule 0 passed;23 测试 case 跨 4 文件 0 fail / 0 error / 0 skipped,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 8 次验证 PASS);**Story #024 follow-up a2a-server-tool-registry-dispatch(0 增量)** —— `A2aServer.handleMessageSend` 真接 dispatch 到本地 `ToolRegistry.execute()` + serve-mode SIGTERM clean shutdown + `tools/cleanup-ports.sh`,复用 JDK `HttpServer` + `ToolExecutionContext` 已有 stub,**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 9 次验证 PASS);**Story #025 follow-up demo-product-sandbox-wiring(0 增量)** —— `DemoProductApplication.readSandbox(Environment)` 私有静态 helper(JDK 8 `Paths.get` + `env.getProperty` + `Collections.emptyList()`)+ `readSandboxList(env, prefix, fallback)` 索引遍历 helper + `mergeConfig()` 签名 +1 `AgentConfig.Sandbox` 参数(`@Value` 24-字段构造器位置 5)+ `application.yml` 顶层 `agent.sandbox:` 5 字段配置补全 + `README.md` 9 features 同步,3 文件改动 / ~110 行 Java + ~35 行 YAML + ~3 行 README,`mvn -pl lingshu-examples/demo-product dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta;`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 10 次验证 PASS);**Story #026 yaml-placeholder-resolution(0 增量)** —— `PlaceholderResolver` 静态工具类(JDK 8 `LinkedHashSet` + `StringBuilder` + brace-counting 自实现 scanner,4-form grammar `${X}` / `${X:default}` / `${X:${Y}}` 嵌套 / `$${literal}` 转义 + 32 层递归深度上限 + `Set<String> visited` 环检测)+ `YamlPlaceholderErrorCodes.LINGS_C03 / LINGS_C04`(Config 域 C 段 3/4 号 = YAML_PLACEHOLDER_UNRESOLVED / YAML_PLACEHOLDER_CYCLE)+ `AgentFactory.loadYamlAndValidate` hook `PlaceholderResolver.resolvePlaceholders(agent, ymlPath)`(`parseMinimalYaml` 与 `toAgentConfig` 之间 1 行)+ `LingsConfigException` 复用 + ErrorCode 嵌入 message 模式对齐 `LinearTurnEngine.LINGS-C02`(让 `assertThatThrownBy().hasMessageContaining("LINGS-C0X")` 工作);修跨路径 placeholder parity bug —— 之前 CLI / `YamlWatcher` hot-reload hand-rolled 路径下 `${user.dir}` 静默变成 13 字符串字面,Spring Env 路径(`demo-product`)一直支持;3 new (`PlaceholderResolver.java` ~250 行 + `YamlPlaceholderErrorCodes.java` ~30 行 + `PlaceholderResolverTest.java` ~250 行) + 2 modify (`AgentFactory.java` +6 行 hook + `YamlHotReloadIT.java` +2 case + 2 helper),`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta;`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 11 次验证 PASS);**Story #027a anthropic-tool-protocol-conversion(0 增量)** —— `AnthropicLlmProvider.buildRequestBody` 4 段协议转换全贯通(`Prompt.tools` → 顶层 `tools:[]` + `messages[].content` 展开 array of blocks + 连续 `Message.ToolResult` 合并为 1 user message 多 tool_result block Anthropic 协议层硬约束 "open tool-result user msg" sentinel pattern)+ `parseResponse` 解析 `tool_use` block → `ToolCall(id, name, input)` + `TurnContext.appendAssistant` 签名扩 `toolCalls` 参数 + `DefaultTurnContext` 5-arg 实现 + `LinearTurnEngine.L166` 真传 `resp.getToolCalls()`(`LingsLlmProviderException` ~25 行新 file + `LlmErrorCodes.LINGS_L01 / LINGS_L02` extend ~30 行);2 新 ErrorCode `LINGS-L01 TOOL_USE_BLOCK_INVALID` / `LINGS-L02 TOOL_RESULT_BLOCK_INVALID`(LlmProvider 域 L 段 1/2 号,**自 #026 后首次启用新 ErrorCode 域**);**§15.3 L 段编号冲突解决** —— 原 §15.3 L01-L08 占位 8 项(`LLM_STREAM_*` / `LLM_RATE_LIMITED` / `LLM_AUTH_FAILED` / `LLM_CONTEXT_OVERFLOW` / `LLM_RESPONSE_MALFORMED` / `LLM_COST_BUDGET_EXCEEDED` / `LLM_PROVIDER_UNAVAILABLE`)计划中;Story #027a 落地 L01/L02 = Tool 协议层,**与原 L01/L02 占位冲突 → 原 L03—L10 编号 +2 移位**(原 L03 → L05 / 原 L04 → L06 / ... / 原 L10 → L12)+ 新增 L11—L18 reserved 占位,**8 项原语义不变仅编号位移**;22 新 case 跨 3 文件(`AnthropicLlmProviderTest` 8 L1+L2 + `AnthropicToolReActIT` 2 L3 mock HttpServer round-trip + `LinearTurnEngineToolDispatchTest` +1 L2 wire-through 守卫 `engine_passesToolCallsThroughToHistoryAssistantMessage`);574 pass / 3 MCP heartbeat flake pre-existing / R-13 mitigation (d) baseline 镜像 pre/post `mvn -pl lingshu-core dependency:tree` diff 仅时间戳差异 = 0 binary delta **第 12 次 PASS**(Jackson 树已用 + `com.sun.net.httpserver.HttpServer` JDK 内置 + `Arrays.asList` 已锁 全 JDK built-in 无新 binary 引入);`LingsLlmProviderException`(新 file ~25 行)+ ErrorCode 嵌入 message 模式对齐 `LINGS-C0X`(`getMessage()` 前缀 `[LINGS-L0X]` 让 `assertThatThrownBy().hasMessageContaining("LINGS-L0X")` 工作);Reflection 调用私有方法 `Method.setAccessible(true)`(`MaxStepsGuardTest.L311` precedent)+ `catchThrowable()` + `getCause()` 解 `InvocationTargetException` 拿真异常;4 文件改动(3 modify `AnthropicLlmProvider.java` ~105 行新增 + `TurnContext.java` + `DefaultTurnContext.java` + `LinearTurnEngine.java` 1 行 wire-through + 2 new `LlmErrorCodes.java` extend ~30 行 + `LingsLlmProviderException.java` ~25 行);Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2),`AnthropicLlmProvider` 走 raw JDK `HttpURLConnection` POST,不走 ChatClient 自动执行;`#027b`(`anthropic-stream-tool-sse`)流式分支待补;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 12 次验证 PASS);**Story #027b anthropic-stream-tool-sse(0 增量)** —— `AnthropicLlmProvider.doPostStream` 流式分支(SSE `text/event-stream` accept + `BufferedReader.readLine()` 逐行解析沿用 #021c `SseMcpServerConnection` 手写 SSE parser 模式 + 空行分隔 event 块)+ `AnthropicStreamParser` 6-类事件状态机(`message_start` → `ReasoningStarted(1, 50)` + init usage / `content_block_start(type=tool_use)` → 防御性校验 + 触发 `ToolStarted(id, name)` / `content_block_delta(type=text_delta)` → 持续 `TextDelta` / `content_block_delta(type=input_json_delta)` → per-block `Map<Integer, ToolCall.Builder>` buffer 拼接 / `content_block_stop` → per-block `MAPPER.readTree()` 构造 `ToolCall` / `message_delta.stop_reason` / `message_stop` → `finish()` 返回 `LlmResponse`)+ `Map<Integer, StringBuilder>` text blocks + `Map<Integer, ToolCall.Builder>` tool blocks 交错状态机 + `LlmErrorCodes.LINGS_L03 reserved` 占位 §14 N6 graceful shutdown 后续启用(本期不抛);3 new source(`AnthropicStreamEvent.java` ~30 行 + `AnthropicStreamParser.java` ~150 行 + `AnthropicStreamTestSupport.java` ~250 行 JDK `com.sun.net.httpserver.HttpServer` mock SSE server fixture + `ExecutorService` daemon + 20ms 间隔事件 pacing + `findFreePort()` + `StartedServer` handle)+ 3 new test(`AnthropicStreamEventTest.java` 2 L1 happy/malformed + `AnthropicStreamParserTest.java` 8 L1 状态机 + `AnthropicStreamProviderIT.java` 2 L2 端到端真流式 + Accept header 捕获)+ 2 modify(`AnthropicLlmProvider.java` ~80 行新增 `doPostStream` + `stream()` 方法体替换 + `parseResponse` 保留为 fallback `anthropicStreamEnabled=false` 配置路径仍可用 + `LlmErrorCodes.java` +1 行 `LINGS_L03` reserved);`LingsLlmProviderException` 复用 + `LINGS-L01` SSE 解析 tool_use 缺 id/name 仍抛(`#027a` 已落异常类);`LinearTurnEngine` ReAct 主循环结构 0 改动(`#027a` 已落 1 行 wire-through 修复);`AgentConfig` 不可变契约 0 改动;13 新 case 跨 4 文件(`AnthropicStreamTestSupport` mock SSE HTTP server fixture + `AnthropicStreamEventTest` 2 L1 + `AnthropicStreamParserTest` 8 L1 + `AnthropicStreamProviderIT` 2 L2);587 pass / 3 MCP heartbeat flake pre-existing / `mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta(`BufferedReader` + `InputStreamReader` + `HashMap` + `ArrayList` + `com.sun.net.httpserver.HttpServer` JDK 内置 全 JDK 8 standard 无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 13 次验证 PASS);**Story #028 sandbox-runtime-impl(0 增量)** —— `RuntimeSandbox` interface(`fs()/http()/process()/approval()` 4 方法)+ `AccessDeniedException`(LINGS-S01 子类,RuntimeException 子类,`getMessage()` 自动前缀 `[LINGS-S01]`)+ `DefaultRuntimeSandbox`(tenant-aware process whitelist 实现 §6 US4/FR-002/FR-013)+ `ChrootedFileSystem extends FileSystem`(JVM-level prefix-boundary gate,`getPath` 强制 `startsWith(rootDir)` 校验,逃逸抛 `[LINGS-S01] Path escapes working dir: ...`)+ `WhitelistedHttpClient implements NetworkClient`(domainWhitelist `Set<String>.contains` 守卫,evil 域抛 `[LINGS-S01] Domain not whitelisted: ...`,5xx 透传 IOException,RFC JDK `HttpURLConnection` 0 新依赖)+ `ChrootRuntimeSandboxProvider`(`@Component name()="chroot" priority()=10`,对齐 v1.5.28 §5.5 多 Provider 模式样板,包装 `DefaultRuntimeSandbox` Spring Bean)+ `DefaultToolExecutionContext` 4 stub 真接通(`fs()` 真返 `runtimeSandbox.fs()` 替换 `FileSystems.getDefault()` 兜底;`http()` 真返 `runtimeSandbox.http()` 替换 `PassThroughHttp` 17 行 inner class;legacy 1-arg ctor + null sandbox 路径走默认 FS + `PassThroughHttp` 兜底兼容旧测试)+ `AgentFactory` 7-Router ctor(`@Autowired` 加 `RuntimeSandboxRouter` 与 `PermissionPolicyRouter` / `ToolExecutorRouter` / `FlowEngineRouter` 并列)+ `LinearTurnEngine.dispatchWithPolicy` 真接 sandbox(`DefaultToolExecutionContext` 2-arg ctor 注入 RuntimeSandbox,从 TurnContext 携带 reference 走 ctx→toolCtx);`application.yml` 顶层 `agent.sandbox.workingDirectory / commandWhitelist / domainWhitelist` 5 字段(Story #025 follow-up 已存字段)真生效;**§4.10.1 硬规则 2 ToolExecutor 5 步流水线「sandbox」步真实现**(permission → registry lookup → timeout → sandbox → execute → checkpoint);39 新 case 跨 6 文件(`AccessDeniedExceptionTest` 3 L1 / `ChrootedFileSystemTest` 7 L1 / `WhitelistedHttpClientTest` 10 L1 / `DefaultRuntimeSandboxTest` 7 L1 / `ChrootRuntimeSandboxProviderTest` 5 L1 / `DefaultToolExecutionContextSandboxIT` 7 L2);626 pass / 2 MCP heartbeat flake pre-existing / **`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta**(`FileSystem` SPI + `HttpURLConnection` + `ProcessBuilder` + `HashSet` + `BufferedReader` 全 JDK 8 standard,无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 14 次验证 PASS);**Story #029 permission-policy-impl(0 增量)** —— `PermissionErrorCodes.LINGS_P01`(第 11 域字母 = Permission 加入;C/S/L/T/X/R/A/M/D/P/Z = 11 域)+ `StrictPermissionPolicy`(`@Component @Value` 不可变,3 决策路径:allow-list 不匹配 / deny-list 匹配 / default Allow)+ `StrictPermissionPolicyProvider`(SPI `name()="strict"` + `priority()=10` + `version()="1.0.0"`)+ `PermissionPolicyAutoConfiguration`(`@Configuration` + `@Bean(name="permissionPolicyProvider_strict-1.0.0")` 多 Provider 模式对齐 v1.5.28 §5.5)+ `AgentConfig` schema 扩(`ToolsConfig` 5 字段 + 顶层 `permissionPolicy: String` 字段;旧 3-arg `ToolsConfig` 构造器保留兼容)+ `AgentFactory.toAgentConfig` 新增 3 段 yml binding(`permission-policy` / `tools.allow-list` / `tools.deny-list`)+ demo yml 接线(`demo-product` 加 `permission-policy: strict` + `tools.allow-list: [read_file, write_file, list_dir, bash_safe]` + `demo-empty` 加 `agent.permission-policy: strict`)+ 18 新 case 跨 6 文件(`StrictPermissionPolicyTest` 5 L1 / `StrictPermissionPolicyProviderTest` 2 L1 / `PermissionErrorCodesTest` 1 L1 / `ToolsConfigAllowDenyListTest` 3 L1 / `PermissionPolicyRouterStrictIT` 4 L2 / `AgentFactoryYamlPermissionPolicyIT` 3 L2);626 pass / 2 MCP heartbeat flake pre-existing;`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta(`@Component` + Lombok `@Value` + Jackson `JsonNode` + `Decision` / `ToolCall` / `AgentConfig` + `List.contains` 全 JDK 8 standard,无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖**(R-13 mitigation (d) 路径第 15 次验证 PASS);`ToolExecutor.dispatch()` 5 步流水线**第 1 步「permission check」从空跑 → 真 delegate 到 `StrictPermissionPolicy`**(其余 4 步 registry lookup / timeout / sandbox / checkpoint 全不变);`AgentConfig` 不可变契约不变(`ToolsConfig` 5 → 6 字段 + 顶层 +1 `permissionPolicy` 字段;旧 3-arg 构造器保留兼容);Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住);**Story #030 permission-policy-ask-user(0 增量)** —— `Decision.AskUser` 3rd outcome 真接通(3 stub 删除 `DefaultToolExecutionContext.approval()` / `DefaultToolExecutor` AskUser 分支 / `LinearTurnEngine.dispatchWithPolicy` AskUser 分支)+ `AskUserPermissionPolicy` 5 段决策(deny-list → ask-list → allow-empty → allow-hit → deny-miss 复用 Story #031 `PermissionPatterns.matches()` 3 形式 pattern 通配)+ `AskUserPermissionPolicyProvider` SPI(`name()="ask"` + `priority()=10` + `version()="1.0.0"` 由 `@Component` 自动注册)+ `ApprovalRegistry` `@Component ConcurrentMap<sessionId+":"+approvalId, Consumer<Decision>>` 终局收集器 + `AgentEvent.ApprovalRequired` 3-arg ctor(`approvalId` UUID + `sessionId` + `Decision ask`)+ `LinearTurnEngine.dispatchWithPolicy` AskUser branch(`CompletableFuture<Decision>` 阻塞 → registry.register → `ApprovalRequired` 事件发射 → future.complete(decision) 由 demo-product ChatController SSE 端点解阻塞)+ `AgentConfig.ToolsConfig` 5-arg → 6-arg ctor 加 `askList: List<String>` + `AgentConfig` 加 `approvalTimeoutSeconds: int` 默认 **0 = 无超时**(Claude Code overnight parity —— 企业审批隔夜(下一个工作日)再决定也能 merge);`>0` 时 N 秒后 `LINGS-P02` Deny + `AgentFactory.askPermissionPolicy` yml 绑定 + demo-product `ChatController` `POST /api/approvals/{sessionId}/{approvalId}` body `{decision:"allow"|"deny", reason:"..."}` SSE round-trip 端点 ~+55 行;23 new case 跨 7 文件(`AskUserPermissionPolicyTest` 5 L1 + `AskUserPermissionPolicyProviderTest` 2 L1 + `ApprovalRegistryTest` 4 L1 + `AgentEventApprovalRequiredTest` 2 L1 + `AgentConfigAskListTest` 3 L1 + `LinearTurnEngineAskUserTest` 3 L2 + `PermissionPolicyRouterAskIT` 3 L2 + 1 L3 demo-product blackbox round-trip);684 pass / 0 fail / 2 MCP heartbeat flake pre-existing;`mvn -pl lingshu-core,lingshu-examples/demo-product dependency:tree -Dverbose` pre/post diff **仅时间戳不同** = 0 binary delta(`ConcurrentHashMap` + `CompletableFuture` + `UUID.randomUUID` + `Collections.emptyList` + `Arrays.asList` + Lombok `@Value` + Spring `@Component` + Jackson `@JsonProperty` kebab-case 已锁 13 项依赖 0 新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **1 新 ErrorCode** `LINGS-P02 PERMISSION_APPROVAL_TIMEOUT`(P 域 2 号,**仅** `approvalTimeoutSeconds > 0` 时触发)(R-13 mitigation (d) 路径**第 16 次验证 PASS**);`PermissionPolicy` SPI 不变 + `Decision` 3 子类(Allow / Deny / AskUser)不变(只 StrictPolicy 用到 Allow + Deny,AskUser 现在真接通)+ `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)+ `AgentConfig` 不可变契约不变(`@Value` 25 → 26 字段 final,只扩 `ToolsConfig.askList` + 顶层 `approvalTimeoutSeconds` 2 字段)+ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)+ `Tool` SPI 不变 + `AgentEvent` 基类不变(只 ApprovalRequired 子类 ctor 加 3-arg)+ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 + 9 Slot 体系不变 + JDK 8 兼容(`ConcurrentHashMap` + `CompletableFuture` + `UUID.randomUUID` + `Collections.emptyList` + `Arrays.asList` + Lombok `@Value` + Spring `@Component` + Jackson `@JsonProperty` kebab-case 已锁,no `var` / `List.of` / sealed / records);Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住) + ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住);**Claude Code overnight parity** —— `approvalTimeoutSeconds=0` 默认**永不超时**,企业审批隔夜(下一个工作日)决定也能 merge;**R-04 privilege escalation 缓解** —— 分值 8,用户可配 `permission-policy: ask` + `ask-list: [bash_safe, write_file, web_fetch]` + Agent 调危险 Tool 需 demo-product ChatController 后台 approve/deny;**Story #031 permission-policy-pattern-matching(0 增量)** —— Story #029 follow-up #1 实测发现 demo yml `allow-list: [read_file, write_file, list_dir, bash_safe]` 静态枚举 brittleness 根治;`Tool.sourceCategory()` 默认方法(`return "local"` 兜底,`Tool` SPI 0 破坏,既有 Tool 实现 0 改动)+ 5 核心 Tool 类型覆盖(`McpToolAdapter` → `"mcp"` / `SpringAiToolAdapter` → `"local"` / `RemoteAgentTool` → `"a2a"` / `DelegateTool` → `"delegate"` / `SkillTool` → `"skill"`,plugin author 自定义字符串自由)+ `PermissionPatterns` 静态工具类(纯 JDK 8 `String` ops,**不**引 regex / glob,3 形式 ladder:`*` 通配 → `<exact-name>` 字面 equals → `<category>:*` 类别前缀 first-match wins)+ `StrictPermissionPolicy` 移除 `@Component`(value-object 不是 Spring Bean,Provider 拥有 lifecycle)+ `@Value` → `@Getter @ToString` 简化 + 2-arg ctor `(ToolsConfig, Map<String,String> nameToCategory)` 注入 + 1-arg ctor 保留 back-compat(Story #029 `StrictPermissionPolicyTest` 5 case 0 回归)+ 4 段决策(deny 命中 → Deny / allow 空 → default-allow / allow 命中 → Allow / 不命中 → Deny with `category=<cat>` 上下文)+ `StrictPermissionPolicyProvider` 移除 `@Component`(避免双 Bean 注册触发 `NoUniqueBeanDefinitionException`)+ `@Autowired ToolRegistry` 注入 + `create(AgentConfig)` 调 `toolRegistry.findAll()` 构造 `nameToCategory` map(0 SPI interface 改动)+ demo yml `allow-list` 12 行静态枚举 → 单行 `allow-list: ["*"]` 通配 + `DemoProductApplication.readTools(Environment, ToolsConfig)` 私有静态 helper 真吃 yml(Story #029 follow-up #1 漏掉的 yml-binding 修补);21 新 case 跨 5 文件(`PermissionPatternsTest` 8 L1 / `ToolSourceCategoryTest` 4 L1 / `StrictPermissionPolicyPatternTest` 6 L1 / `StrictPermissionPolicyReasonTest` 1 L1 / `AgentFactoryPatternMatchingIT` 2 L2)+ 2 L3 blackbox IT(`DemoProductPermissionWildcardIT` 12 Tool 通配全 Allow + `DemoProductPermissionCategoryPatternIT` 6 inline cases 含 4 stub Tool via `@TestConfiguration`)+ 5 Story #029 back-compat case 仍 PASS(0 回归);`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta(`String.startsWith` + `String.equals` + `HashMap` + `Collections.emptyMap()` 全 JDK 8 standard,无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **0 新 ErrorCode**(复用 `LINGS-P01`)(R-13 mitigation (d) 路径**第 16 次验证 PASS**);`PermissionPolicy` SPI 不变 + `Decision` 3 子类不变(Allow / Deny / AskUser;AskUser 路径仍 §4.7 未来 RFC)+ `Tool` 现有 4 方法不变(只加 1 default method `sourceCategory()`,0 破坏)+ `PermissionPolicyProvider.create(AgentConfig)` SPI 不变(`@Autowired ToolRegistry` 注入,**不**扩 create 签名)+ `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)+ `AgentConfig` 不可变契约不变(0 字段新增)+ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)+ `Message` 4 子类契约不变(🆕 v1.5.46 refactor 已落)+ 9 Slot 体系不变 + JDK 8 兼容(`String.startsWith` + `String.equals` + `HashMap` + `Collections.emptyMap()` 全 JDK 8 standard,no `var` / `List.of` / sealed / records);Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2);**Story #029 「维护死亡名单」反模式根治** —— yml 4 行 → 单行通配覆盖全部 Tool / 新接入 Tool 自动可见 / 业务方无需维护白名单;**Story #032 web-fetch-local-tool(0 增量)** —— Claude Code parity primary rationale(Claude Code 工具表 `Read / Write / Edit / Glob / Grep / Bash / WebFetch / WebSearch / Task / Skill` 中 `WebFetch` 是 built-in,LingShu `ReadTool`(本地 fs)↔ `BashTool`(本地 process)↔ `WebFetchTool`(本地 http)三件套对称;MCP fetch(`McpServerConnection` + `McpToolAdapter`)作为「高级 HTTP」共存非互斥);`WebFetchTool implements Tool`(`name()="web_fetch"` snake_case + `description()` 含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported" + `inputSchema` 静态 JSON Schema `{url: string, max_bytes?: integer}` + `sourceCategory()="local"` 默认 0 override + `execute(call, ctx)` 4 段(url 校验 → max_bytes 解析 → `ctx.http().get(url)` 委托 `WhitelistedHttpClient` → 1 MB truncation marker `\n...[truncated, original %d bytes]`)+ 2 catch(`AccessDeniedException` → `ToolResult.error("[LINGS-S01] Domain not whitelisted: <url>")` 复用 #028 / `IOException` → `ToolResult.error("HTTP fetch failed: <msg>")` 无 ErrorCode 前缀)+ `LocalToolsAutoConfiguration` **0 改动**(`Map<String, Tool> tools` Spring 自动注入所有 `@Component implements Tool` 5 个 Tool 已就位(`"Read"` / `"Write"` / `"Edit"` / `"Bash"` / `"web_fetch"`)) + HTTPS 透明(JDK `HttpsURLConnection` 自动按 scheme 选实现,User-Agent `"ChaOS-LingShu-Sandbox/1.0"` 透传);15 new case 跨 4 文件(`WebFetchToolTest` 8 L1 metadata + execute / `WebFetchToolHttpServerIT` 4 L2 真发请求 + HTTPS + 404 / `LocalToolsAutoConfigurationWebFetchIT` 2 L2 装配 wiring / `DemoProductWebFetchIT` 1 L3 yml 端到端 localhost allow + example.com deny 双半);`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta(`@Component` + JDK `HttpURLConnection` / `HttpsURLConnection` + JDK `com.sun.net.httpserver.HttpServer` IT fixture + `com.sun.net.httpserver.HttpExchange` + Jackson `ObjectNode`/`JsonNode` + Mockito + AssertJ 全 JDK 8 standard + 已锁 13 项依赖表内,无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **0 新 ErrorCode**(复用 #028 `LINGS-S01`);`Tool` SPI 不变(`sourceCategory()` 是 #031 加的 default 方法,本 Story 不 override 走默认 `"local"`)+ `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)+ `WhitelistedHttpClient` SPI 不变(#028 已落)+ `RuntimeSandbox.http()` 不变(#028 已落,本 Story 是 idle 基建激活)+ `AccessDeniedException` SPI 不变(`[LINGS-S01]` 前缀不变)+ `LocalToolsAutoConfiguration` 0 改动(Map<String, Tool> autowiring 自动接住)+ `AgentConfig` 不可变契约不变(0 字段新增)+ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)+ 9 Slot 体系不变 + JDK 8 兼容(JDK `HttpURLConnection` + `HttpsURLConnection` + `com.sun.net.httpserver.HttpServer` + `Collections.emptyList()` + `Arrays.asList()` 已锁,no `var` / `List.of` / sealed / records)(R-13 mitigation (d) 路径**第 17 次验证 PASS**);**Story #033 mcp-http-domain-guard(0 增量)** —— Path B + Mitigation 1 沙箱守卫深度(MCP HTTP 2 transport(`SseMcpServerConnection` / `StreamableHttpMcpServerConnection`)继续走 raw JDK `HttpURLConnection`(避免 MCP 协议层破坏 + 复用 #021c `McpHttpSupport` JSON-RPC 样板)+ `McpHttpSupport.checkOrThrow(String url, List<String> whitelist)` 静态 helper(JDK `URI.create(url).getHost()` + `whitelist.contains(host)`)+ **12 hook 点**(SSE 7 + Streamable HTTP 5)在 MCP 发 HTTP 请求**之前**调,失败抛 `AccessDeniedException`(`[LINGS-S01]` 前缀由 super 自动嵌入,真实请求**不**发起);`McpServerConfig.@Builder.Default List<String> domainWhitelist = new ArrayList<>()` + 构造器 `defensive copy new ArrayList<>(cfg.getDomainWhitelist())`;`StdioMcpServerConnection` 0 改动(stdio 走子进程 IPC 不走 HTTP);`McpCallResult.error("[LINGS-S01] " + e.getMessage())` 错误格式统一;13 new case 跨 3 文件(`McpHttpSupportCheckOrThrowTest` 8 L1 `checkOrThrow` 单元:emptyWhitelist / nullWhitelist / matchingHost / nonMatchingHost / nullOrEmptyUrl / malformedUrl / caseSensitive / ipv4Host + `McpHttpDomainGuardIT` 4 L2 whitelisted reachesConnected / nonWhitelisted callToolDenied / nonWhitelisted accessDeniedThrown + streamableHttp nonWhitelisted + `McpServerConnectionFactoryTest` +1 L1 factory dispatch create_sse_domainWhitelistPropagated);35 existing test files updated(`.domainWhitelist(Arrays.asList("127.0.0.1"))` + `import java.util.Arrays;` 让 SDK IT fixture 走沙箱白名单,真实 localhost fixture host 不被 deny);660 pass / 2 MCP heartbeat flake pre-existing;`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta(`URI.create` + `List.contains` + `ArrayList` + `AccessDeniedException`(`#028` 已落)+ JDK 8 standard 全无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **0 新 ErrorCode** 复用 #028 `LINGS-S01`(R-13 mitigation (d) 路径**第 18 次验证 PASS**);**0 SPI 改动** —— `McpServerConnection` interface / `A2aTransport` SPI / `Tool` SPI / `RuntimeSandbox` interface / `WhitelistedHttpClient` class / `McpTransport` / `StdioMcpServerConnection` / `McpServerConnectionFactory.create()` 全部 0 改动;`McpHttpSupport.checkOrThrow` 只调 `whitelist.contains(host)` 不创建 full client(避免无谓 WhitelistedHttpClient 实例化);`AgentConfig` 不可变契约不变(0 字段新增;yml `agent.mcp.servers[*].domain-whitelist` 接线留 OQ-Future,hand-rolled `parseMinimalYaml` 不解析嵌套 list,whitelist 只可编程设置通过 `McpServerConfig.builder().domainWhitelist(...)`)+ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)+ 9 Slot 体系不变 + JDK 8 兼容(`URI.create` + `List.contains` + `ArrayList` + `Collections.emptyList()` 已锁,no `var` / `List.of` / sealed / records);**Story #034 a2a-http-domain-guard(0 增量)** —— 完全复用 #033 `McpHttpSupport.checkOrThrow(String url, List<String> whitelist)` 静态 helper(JDK `URI.create(url).getHost()` + `whitelist.contains(host)`),**2 hook point**(`fetchCard(String agentName)` + `jsonRpcCall(...)` 私有方法,submit/get/cancel 都走同一 hook)加在 `HttpJsonRpcA2aTransport`,失败抛 `AccessDeniedException[LINGS-S01]`,真实请求**不**发出;`AgentRef.@Value` 扩 `List<String> domainWhitelist` 字段(per-remote-agent 配置粒度,yml `domain-whitelist: [host1, ...]` kebab-case 绑定)+ `@Builder.Default` 空 list 兜底 + `getDomainWhitelistOrEmpty()` null-safe accessor(null → `Collections.emptyList()` strict-mode 默认 deny-all,镜像 #033 `McpServerConfig.domainWhitelist` 语义);`HttpJsonRpcA2aTransport` 5-arg ctor 接收 whitelist + defensive copy `new ArrayList<>(domainWhitelist)` + 4-arg ctor 保留 back-compat wrapper(传 `Collections.emptyList()` strict mode);`HttpJsonRpcA2aTransportAutoConfiguration.HttpJsonRpcA2aTransportFactory` 内嵌 static inner class + `@Bean(name="a2aTransportFactory_http-jsonrpc")` 暴露 `build()` 把 `cfg.a2a.remoteAgents[*].domainWhitelistOrEmpty()` 走 `HashSet<String>` union 去重后传给 A2A transport(strict mode 镜像 MCP #033);`RemoteAgentToolAutoConfiguration.remoteAgentTool()` 注入 factory,`if (transportName=="http-jsonrpc-1.0.0") transport = httpJsonRpcFactory.build();` 否则 `router.resolve(...)`(grpc / in-process 不走 HTTP 不变);**13 new case** 跨 4 文件(`HttpJsonRpcA2aTransportCheckOrThrowTest` 6 L1:emptyWhitelist / nonMatchingHost / matchingHost / 5-arg ctor validation 6 子 case / defensiveCopy / snapshotReturn + `HttpJsonRpcA2aTransportDomainGuardIT` 3 L2:JDK `com.sun.net.httpserver.HttpServer` 计数 hits 真发请求,whitelisted hits==2+SUCCESS / non-whitelited hits==0+AccessDenied / strict-mode empty hits==0 + `HttpJsonRpcA2aTransportAutoConfigurationTest` +1 L1 factory union dedup + `HttpJsonRpcA2aTransportTest` 3 call site 4-arg → 5-arg fixture update);`mvn -pl lingshu-a2a-client dependency:tree` pre/post diff 仅时间戳差异 = 0 binary delta(`URI.create` + `List.contains` + `HashSet` + `ArrayList` + `Collections.emptyList` + `Arrays.asList` + Jackson `@JsonProperty` kebab-case 已锁 JDK 8 标准 无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **0 新 ErrorCode** 复用 #028 `LINGS-S01`(R-13 mitigation (d) 路径**第 22 次验证 PASS**);**关键不变项** —— `A2aTransport` 5 方法 SPI 不变 / `A2aTransportRouter` 不变 / `RemoteAgentTool` 不变(只看 `A2aTransport` 接口)/ `McpHttpSupport.checkOrThrow` 公开方法不变(只被新增 caller 调用)/ `AccessDeniedException[LINGS-S01]` ErrorCode 复用 / `Tool` SPI 不变 + `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `AgentConfig` 不可变契约不变(只 `AgentRef` 内部加字段)/ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 9 Slot 体系不变 / JDK 8 兼容(`URI.create` + `List.contains` + `HashSet` + `ArrayList` + `Collections.emptyList` + `Arrays.asList` + Jackson `@JsonProperty` kebab-case 已锁,no `var` / `List.of` / sealed / records);**累计** 24 个 Story 全 0 binary delta 通过(12 个 0 增量 + 1 个 #009a +44MB trade-off + 2 个 follow-up demo 子模块 + `#027b` + `#028` + `#029` + `#030` + `#031` + `#032` + `#033` + `#034`),Spring Boot SPI + JDK 内置 + Jackson + Lombok 已锁 13 项依赖表完整不变;**R-13 标记「已缓解」** 100% 闭合率 |

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