# LingShu 工程 Roadmap — 待实施 Story 索引

> **文件目的**:跟踪 dsh `dsh_agent_design.md` §6 关键实现 + §14 生产增强(N1—N13)中尚未实施的 Story。
> **与 SKILL 配合**:新 Claude Code 会话 turn 1 读本文件 → 决定下一步 `/specify <slug>`。
> **与 constitution.md 关系**:本文件是工程 tracker(可修改);`constitution.md` 是法律(不可改,改须走 RFC)。
> **与 dsh_agent_design.md 关系**:dsh 是设计真理(只读);本文件是「哪些 dsh 章节已落地 / 哪些待 Story」的实施映射。
> **创建日期**:2026-09-22 — Story #009d a2a-remote-schema-builder 已合入,扫 §6 关键实现发现 5 大块空白。
> **下次 review**:每个 Story 合入后更新「已完成」段。

---

## ✅ 已完成(Story #001—#009 + #009a/b/c/d + #017)

详见 `README.md` 「Story 路线图」段 + `dsh_agent_design.md §13` changelog。共 14 个 PR 合入:

| Story | slug | 状态 |
|---|---|---|
| #001 | zero-config-bootstrap | ✅ 合 |
| #002 | identity-instructions-memory | ✅ 合 |
| #003 | spi-slot-router | ✅ 合 |
| #004 | tool-parallel-dispatch | ✅ 合 |
| #005 | cancellation-token | ✅ 合 |
| #006 | multi-tenant(§14 N9) | ✅ 合 |
| #007 | yaml-hot-reload(§14 N8) | ✅ 合 |
| #008 | react-max-steps | ✅ 合 |
| #009 | a2a-agent-card | ✅ 合 |
| #009a | a2a-grpc-transport | ✅ 合 |
| #009b | a2a-inprocess-transport | ✅ 合 |
| #009c | a2a-httpjsonrpc-and-remote-tool | ✅ 合 |
| #009d | a2a-remote-schema-builder | ✅ 合 |
| #017 | cli-entrypoint | ✅ 合 |

---

## 🟡 §6 关键实现 待补(优先级 P0,核心引擎拼图)

dsh §6 关键实现章节(L3499-5152)中,**4/6 主章节有未落地子模块**。本段列出待 Story,按依赖顺序排列。

### 编号约定

- 复用 #NNN 编号空间,**不**新开子序列
- #018—#023 为本批新增;后续 §6 待补若需更多 Story,顺延 #024—
- 编号规则:`#NNN-<slug>` 与现有 Story 路径(`specs/001-zero-config-bootstrap/` 等)对齐

### 提议 Story 列表(8 个,按依赖顺序)

| 序 | Story # | slug | dsh § | 范围 | 文件数 | ErrorCode | 依赖 |
|---:|---|---|---|---|---:|---|---|
| 1 | **#018** | `truncating-compactor` | §6.2 | `TruncatingCompactor` 实现(ToolResult 截断 + 滑动窗口)+ `CompactorProvider` SPI + `CompactorProps` + `CompactorRouter` concrete stub | 4 | LINGS-C02 / LINGS-Z01 | — |
| 2 | **#019** | `built-in-tools` | §6.5 (1) | `ReadTool` / `WriteTool` / `EditTool` / `BashTool` + `LocalToolsAutoConfiguration` | 5–6 | LINGS-T01 | — |
| 3 | **#020a** | `skill-foundation` | §6.4 核心 | `SkillTool` + `fromMarkdown` 静态工厂 + `@Component CommitSkill` + `ToolRegistry`(modelVisibleSpecs / findSkill / skillNames / findByName) | 5 | 0 | 依赖 #019 验证 Tool 接口 |
| 4 | **#020b** | `skill-source-discovery` | §6.4 多源 | `SkillSource` + `SkillSourceProvider` 接口 + `ClasspathSkillSource` + `DirectorySkillSource` + `CompositeSkillLoader`(putIfAbsent)+ `SkillSourceRouter` | 5–6 | 0 | 依赖 #020a |
| 5 | **#020c** | `cli-skill-trigger` | §6.4 CLI | CLI `/xxx` 拦截 + `handleUserInput` + Skill 列表自动补全 + 启动日志 dump skills | 4 | 0 | 依赖 #020a(可选,#020b 不阻塞)|
| 6 | **#021a** | `mcp-stdio-transport` | §6.5 (2.1) | `McpServerConnection` interface + `ConnectionState` enum 6 态 + `McpServerConnectionFactory` + `StdioMcpServerConnection`(daemon 心跳 + 1s→60s 指数退避 + 无限重试)+ `McpServerConfig` | 5 | LINGS-M01 (connect failed)| — |
| 7 | **#021b** | `mcp-tool-adapter` | §6.5 (2) | `McpTransport`(listener 模式)+ `McpToolDescriptor` + `McpCallResult` + `McpToolAdapter` + register/unregister 钩子 | 5 | LINGS-M02 (tools/call failed) | 依赖 #021a |
| 8 | **#022** | `spring-ai-annotation-tool` | §6.5 (3) | `@AgentTool` 注解(复用 spring-ai `@Tool` 因 spring-ai-bom 已锁)+ `SpringAiToolAdapter` + `AgentToolScanner`(`ApplicationContextAware`)+ `JsonArgsConverter` | 5 | LINGS-T02 (反射调用失败) | 依赖 spring-ai-bom |
| 9 | **#023** | `delegate-sub-agent` | §6.6 + §6.6.1 | `SubAgentType` enum + `DelegateTool` + `DelegateProps` + `TypeConfig` + §6.6.1 字段级继承(`AgentConfig.toBuilder()` 合并 identity / instructions / memory) | 5–6 | LINGS-D01 (sub-agent 配置缺失) | 依赖 AgentFactory + Agent 已就位 |

**统计**:9 个 Story / ~43 个新文件 / ~7 个新 ErrorCode / 预计 +500–700 个测试 case。

### 实施顺序建议(支持并行)

```
主链(必须顺序): #018 → #019 → #020a → #020b → #020c
并行支链(与主链无依赖):
  支链 A: #021a → #021b     (MCP,可与 #020 系列并行)
  支链 B: #022               (Spring AI,可与 #020 / #021 并行)
  支链 C: #023               (Sub-agent,可与 #020 / #021 / #022 并行)
```

**R-13 mitigation (d) 假设**:复用 Spring AI `@Tool`(已在 13 项依赖表内)+ 复用 JDK 17+ `java.net.http.HttpClient`(SSE)/ JDK 8 `ProcessBuilder`(stdio)/ 复用 Jackson + Lombok(已锁)。**预计 0 新依赖**(MCP stdio 用 JDK 内置 `ProcessBuilder` 即可,无需 `jackson-module-jsonSchema` 等额外包)。

### 选 Story 的入口

新会话 turn 1 操作:
```bash
# 1. 读本文件 + CLAUDE.md §3-5 + dsh §6.x 对应章节(每 Story 锚定 1 个 § 子节)
# 2. 用户驱动决定下一个 Story(本文件 P0 顺序作默认建议)
specify /specify "018-truncating-compactor"     # → specs/018-truncating-compactor/spec.md
specify /plan                                    # → specs/018-truncating-compactor/plan.md
specify /tasks                                   # → specs/018-truncating-compactor/tasks.md
# 4. 按 tasks.md 实施 → validate → PR body 贴 spec.md + plan.md + tasks.md + AC 验证输出
```

---

## ⏸ §14 生产增强 滞后(P2 优先级,等 §6 核心补完后再讨论)

dsh §14 N1—N13 生产增强章节(L6594-7126)中,**仅 N8(yaml-hot-reload → #007)+ N9(multi-tenant → #006) + N12(cancellation-token → #005)合入**,余 10 项未做。**整体滞后**,等 §6 关键实现全部补完 + v1.0-α 自测通过后再开 Issue 讨论。

| N | 标题 | 潜在 Story # | 备注 |
|---|---|---|---|
| **N1** | OpenTelemetry trace + metrics | #010 | §14.1;依赖 LlmProvider + ToolExecutor 已稳定 |
| **N2** | RetryPolicy(指数退避 + 抖动)| #011 | §14.2;per-tool SPI 扩展 |
| **N3** | CircuitBreaker(per-tool) | #012 | §14.3;依赖 N2 |
| **N4** | CostBudget(turn + session 级) | #013 | §14.4;Cost 域 §15 ErrorCode 待扩 |
| **N5** | 健康检查(Spring Actuator) | (随主线)| §14.5;spring-boot-starter-actuator 已锁,可顺带做 |
| **N6** | 优雅停机 | (随主线)| §14.6;CancellationToken 已有,串联停机信号即可 |
| **N7** | SessionStore 多后端 | #014 | §14.7;Memory / Redis / Jdbc 三后端;Redis 需新增 `spring-boot-starter-data-redis`(R-13 走 RFC)|
| ~~**N8**~~ | ~~配置热更新~~ | **#007** ✅ | 已合 |
| ~~**N9**~~ | ~~多租户隔离~~ | **#006** ✅ | 已合 |
| **N10** | 审计日志 | #016 | §14.10;AuditLogger SPI;`LINGS-A01—A99` 域待启用 |
| **N11** | Prompt 缓存 | (随 #003)| §14.11;按 skill 排序已部分落地(参见 #009d `RemoteAgentSchemaBuilder`),OpenAI/Anthropic provider-level 缓存待做 |
| ~~**N12**~~ | ~~CancellationToken 贯通~~ | **#005** ✅ | 已合 |
| **N13** | 插件版本治理 | (随主线)| §14.13;`Provider.version()` 字段已就位(#003),Maven enforcer 卡点待补 |

**重新评估触发条件**:
- v1.0-α 完成(§6 全部 Story 合入)后,**每月 1 号**月度 review(R-09 节奏)
- 社区/客户有具体诉求时,可提前插队

---

## 📋 OQ-Future 项(Story 级别,未规划独立 Story)

部分 §6 关键实现章节里出现的 OQ-Future(开放问题)项,**未达 Story 启动门槛**,仅记录在此备查:

| OQ | 锚定 § | 标题 | 状态 | 重启 trigger |
|---|---|---|---|---|
| OQ-1 | §5.6.3 / §6.5 | N-tool Bean 模式(每 skill 1 `Tool` Bean)| 🟡 OQ-Future | OpenAI / Anthropic 2025+ tool spec 广泛支持 `oneOf` + nested union |
| OQ-2 | §5.6.3.0 | `AgentSkill` 加 `inputSchema` / `outputSchema` 字段 | 🟡 OQ-Future | 用户提具体 use case(目前 `additionalProperties: true` fallback 够用) |
| OQ-5 | §5.6.3.0 | `PromptBuilder [TOOL SCHEMAS]` 段集成 `RemoteAgentSchemaBuilder` | 🟡 OQ-Future | PromptBuilder 重构时一并接入(改 core 引擎超出单 Story 边界) |

---

## 🎯 实施节奏建议

1. **本周(2026-09-22 周)**:Story #009d 文档同步已完成(本文件 + 配套 4 件套 dsync)
2. **下周起**:按本 Roadmap 主链顺序 #018 → #019 → #020a → #020b → #020c 推进;并行启动 #021a / #022 / #023 各自 spec.md
3. **每个 Story 合入后**:更新本文件「✅ 已完成」表 + dsh §13 changelog + `constitution.md` §10 R-XX 缓解率 + README.md Story 路线图
4. **每月 1 号**:review 本文件,确认 P0 → P2 升级 / 滞后顺序调整
