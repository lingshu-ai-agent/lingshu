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

**命名规范**:`LINGS-<域字母><2 位数字>`(共 8 域,编号 01—99):

| 域字母 | 域 | Story #001 涉及 |
|---|---|---|
| `C` | Config(配置)| **LINGS-C02 / C03**(yml 缺字段 / 类型不符) |
| `S` | Slot(SPI)| **LINGS-S01 / S05**(name 不在 Router / Provider init 失败) |
| `L` | LLM | (Story #003)|
| `T` | Tool | (Story #004 起)|
| `X` | Sandbox | (Story #001 后)|
| `R` | ReAct | (Story #008)|
| `A` | Audit | (Story #016)|
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
| **R-13** | **Spring AI starter 误用(transitive 污染 + binary 膨胀)** | **2×3=6** | (a) **只**引 `spring-ai-core` + 实际用 provider starter,不用 `spring-ai-spring-boot-starter` 全家桶;(b) `banned-dependencies` enforcer build 阶段 fail;(c) binary size baseline < 35MB,CI delta > 10% fail;(d) Story #001 / #003 / #009 实施者**必须**先 `mvn dependency:tree` 自查 + 贴关键子树到 PR body | Charlie | v1.0 GA |

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