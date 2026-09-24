# LingShu 工程 Roadmap — 待实施 Story 索引

> **文件目的**:跟踪 dsh `dsh_agent_design.md` §6 关键实现 + §14 生产增强(N1—N13)中尚未实施的 Story。
> **与 SKILL 配合**:新 Claude Code 会话 turn 1 读本文件 → 决定下一步 `/specify <slug>`。
> **与 constitution.md 关系**:本文件是工程 tracker(可修改);`constitution.md` 是法律(不可改,改须走 RFC)。
> **与 dsh_agent_design.md 关系**:dsh 是设计真理(只读);本文件是「哪些 dsh 章节已落地 / 哪些待 Story」的实施映射。
> **创建日期**:2026-09-22 — Story #009d a2a-remote-schema-builder 已合入,扫 §6 关键实现发现 5 大块空白。
> **更新日期**:2026-09-24 — **Story #022 spring-ai-annotation-tool 已合**(513 pass / 0 fail / R-13 0 binary delta / +LINGS-T08):`@AgentTool` 注解 + `SpringAiToolAdapter`(JSON Schema 自动生成 + reflection invoke + catch-all 转 LINGS-T08)+ `AgentToolScanner`(`ApplicationContextAware` 启动期扫 `getBeansWithAnnotation(Component.class)` 自动注册)+ `JsonArgsConverter`(primitive/String 类型映射,缺字段 primitive 抛 IAE 走 LINGS-T08)+ `ToolErrorCodes.LINGS_T08`(reflection failure 错码,工具域 T 段 8 号);**复用 spring-ai `@Tool` 注解信息但不依赖 spring-ai 自动执行**(dsh §4.10.1 硬规则 2 守住);下一步 **#023 delegate-sub-agent**(强依赖 #009e 已满足,可开窗)。
> **下次 review**:每个 Story 合入后更新「已完成」段。

---

## ✅ 已完成(Story #001—#009 + #009a/b/c/d + #017 + #018 + #019 + #020a + #020b + #020c)

详见 `README.md` 「Story 路线图」段 + `dsh_agent_design.md §13` changelog。共 19 个 PR 合入:

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
| #018 | truncating-compactor | ✅ 合 |
| #019 | built-in-tools | ✅ 合 |
| #020a | skill-foundation | ✅ 合(2026-09-23,PR #28) |
| #020b | skill-source-discovery | ✅ 合(2026-09-23,PR #29) |
| #020c | cli-skill-trigger | ✅ 合(2026-09-23,PR #31) |
| #021a | mcp-stdio-transport | ✅ 合(2026-09-23) |
| #021b | mcp-tool-adapter | ✅ 合(2026-09-23,440 pass / 0 fail / R-13 0 binary delta / +LINGS-M02) |
| #021c | mcp-sse-and-http-transport | ✅ 合(2026-09-23,481 pass / 0 fail / R-13 0 binary delta / +LINGS-M03) |
| #009e | a2a-remote-tool-wiring | ✅ 合(2026-09-24,333 pass / 0 fail / R-13 0 binary delta / 0 ErrorCode;`RemoteAgentToolAutoConfiguration` 独立 + `RemoteAgentToolLifecycle` SmartLifecycle 显式 register/unregister + 3 transport 共用 wiring) |
| #022 | spring-ai-annotation-tool | ✅ 合(2026-09-24,513 pass / 0 fail / R-13 0 binary delta / +LINGS-T08;`@AgentTool` 注解 + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` + `ToolErrorCodes.LINGS_T08`) |

---

## 🟡 §6 关键实现 待补(优先级 P0,核心引擎拼图)

dsh §6 关键实现章节(L3499-5152)中,**4/6 主章节有未落地子模块**。本段列出待 Story,按依赖顺序排列。

### 编号约定

- 复用 #NNN 编号空间,**不**新开子序列
- #018—#023 为本批新增;A2A Client 系列 #009a—#009d 已合,#009e 是 dsh §5.6.2 实施期发现的 wiring gap(**实测发现,不是设计意图**,2026-09-24 加)
- 后续 §6 待补若需更多 Story,顺延 #024—
- 编号规则:`#NNN-<slug>` 与现有 Story 路径(`specs/001-zero-config-bootstrap/` 等)对齐

### 提议 Story 列表(9 个,按依赖顺序)

| 序 | Story # | slug | dsh § | 范围 | 文件数 | ErrorCode | 依赖 |
|---:|---|---|---|---|---:|---|---|
| 1 | ~~**#020a**~~ | ~~`skill-foundation`~~ | §6.4 核心 | ~~`SkillTool` + `fromMarkdown` 静态工厂 + `@Component CommitSkill` + `ToolRegistry`(modelVisibleSpecs / findSkill / skillNames / findByName)~~ | ~~5~~ | ~~0~~ | **✅ 已合**(PR #28,2026-09-23) |
| 2 | ~~**#020b**~~ | ~~`skill-source-discovery`~~ | ~~§6.4 多源~~ | ~~`SkillSource` + `SkillSourceProvider` 接口 + `ClasspathSkillSource` + `DirectorySkillSource` + `CompositeSkillLoader`(putIfAbsent)+ `SkillSourceRouter` + `SkillSourceProperties`(R-13 兼容 Environment 静态工厂)~~ | ~~8~~ | ~~0~~ | **✅ 已合**(PR #29,2026-09-23) |
| 3 | ~~**#020c**~~ | ~~`cli-skill-trigger`~~ | ~~§6.4 CLI~~ | ~~`SkillCommandDispatcher`(@Component)+ `Agent.continueWithUserMessageBlocking` 同步版 + `CliRunner.doRun`/`doResume` 前置 `/xxx` 拦截 + `--list-skills` 启动 banner + `CliRunner.doDoctor` 末尾追加 banner + `Args.isPrintSkills` + `ArgsParser --list-skills` flag~~ | ~~5 + 2(core Agent 扩展)~~ | ~~0~~ | **✅ 已合**(PR #31,2026-09-23,主链 3/3 完成 🎉) |
| 4 | ~~**#021a**~~ | ~~`mcp-stdio-transport`~~ | ~~§6.5 (2.1)~~ | ~~`McpServerConnection` interface + `ConnectionState` enum 6 态 + `McpServerConnectionFactory`(仅 stdio 分支,SSE/HTTP 抛 LINGS-M01)+ `StdioMcpServerConnection`(daemon 心跳 + 1s→60s 指数退避 + 无限重试)+ `McpServerConfig`~~ | ~~7~~ | ~~0~~ | **✅ 已合**(2026-09-23,36 tests 0 fail / R-13 0 binary delta) |
| 5 | ~~**#021b**~~ | ~~`mcp-tool-adapter`~~ | ~~§6.5 (2)~~ | ~~`McpTransport`(listener 模式)+ `McpToolDescriptor` + `McpCallResult` + `McpToolAdapter` + register/unregister 钩子 + `ToolRegistry.unregister()` SPI 扩展 + SmartLifecycle 启动期 wireup~~ | ~~5 + 3(ToolRegistry SPI 修改 + DefaultToolRegistry 实现 + McpTestSupport sysprop 转发)~~ | ~~LINGS-M02 (tools/call failed)~~ | **✅ 已合**(2026-09-23,440 tests 0 fail / R-13 0 binary delta) |
| 6 | ~~**#021c**~~ | ~~`mcp-sse-and-http-transport`~~ | ~~§6.5 (2.1)~~ | ~~`McpHttpSupport` utility(HTTP / JSON-RPC 样板)+ `SseMcpServerConnection`(JDK HttpURLConnection 长连接 + 手写 SSE parser + `GET /health` 心跳 + 重建 HttpURLConnection 重连)+ `StreamableHttpMcpServerConnection`(无状态 HTTP POST tools/* + `GET /health` 心跳)+ `McpServerConnectionFactory` factory dispatch 改写(移除 LINGS-M01 抛点,3 分支全实现)+ `McpErrorCodes` 扩 `LINGS_M03` + `TestMcpHttpServer` / `TestMcpSseServer` fixtures(sysprop 控制 push/close/malformed)~~ | ~~5 + 5(2 新 fixtures)~~ | ~~LINGS-M03 (HTTP upgrade / SSE event format)~~ | **✅ 已合**(2026-09-23,481 tests 0 fail / R-13 0 binary delta) |
| 6.5 | ~~**#009e**~~ | ~~`a2a-remote-tool-wiring`~~ | ~~§5.6.2 wiring(实测发现,2026-09-24)~~ | ~~(1) 抽 `RemoteAgentToolAutoConfiguration` 独立于 transport(2) 加 `RemoteAgentToolLifecycle implements SmartLifecycle` 显式 register/unregister(3) 3 transport AutoConfig 各自只保留 `a2aTransportProvider_<name>`(4) 1 L3 IT 覆盖 3 transport × 全链路~~ | ~~5~~ | ~~0~~ | **✅ 已合**(2026-09-24,`RemoteAgentToolAutoConfiguration` 独立 + `RemoteAgentToolLifecycle` SmartLifecycle + 3 transport 共用 wiring + 333 tests pass / 0 fail / R-13 0 binary delta / 0 ErrorCode) |
| 7 | ~~**#022**~~ | ~~`spring-ai-annotation-tool`~~ | ~~§6.5 (3)~~ | ~~`@AgentTool` 注解(复用 spring-ai `@Tool` 因 spring-ai-bom 已锁)+ `SpringAiToolAdapter` + `AgentToolScanner`(`ApplicationContextAware`)+ `JsonArgsConverter`~~ | ~~5~~ | ~~LINGS-T08 (反射调用失败,实际错码 T 段 8 号空位)~~ | **✅ 已合**(2026-09-24,513 tests pass / 0 fail / R-13 0 binary delta / +LINGS-T08 / `ToolErrorCodes` 常量类) |
| 8 | **#023** | `delegate-sub-agent` | §6.6 + §6.6.1 | `SubAgentType` enum + `DelegateTool` + `DelegateProps` + `TypeConfig` + §6.6.1 字段级继承(`AgentConfig.toBuilder()` 合并 identity / instructions / memory) | 5–6 | LINGS-D01 (sub-agent 配置缺失) | 依赖 AgentFactory + Agent 已就位 + **#009e 先打平 RemoteAgentTool 的 wiring** |

**统计**:9 个 Story / 8 已合(#020a + #020b + #020c + #021a + #021b + #021c + #009e + #022)/ 1 待补(#023)/ ~51 个新文件 / ~7 个新 ErrorCode / 实际 +575 测试 case(401 → 440 → 481 → 333[#009e 拆分到 lingshu-a2a-client]→ 513[lingshu-core 全模块,含 #022 +32 case]累加 / +32 由 #022 贡献;注:全局测试数是各模块独立运行汇总,非单一累加);#022 实际贡献 +32 case(L1 13 `JsonArgsConverterTest` + L1/L2 10 `SpringAiToolAdapterTest` + L2 5 `AgentToolScannerTest` + L2/L3 4 `AgentToolIntegrationTest` = 32)/ +1 ErrorCode LINGS-T08(工具域 T 段 8 号空位)。

### 实施顺序建议(支持并行)

```
主链(必须顺序): ✅ #020a → ✅ #020b → ✅ #020c   (Skill 3 件套 ✅ 主链收官)
并行支链(与主链无依赖):
  支链 A: ✅ #021a → ✅ #021b → ✅ #021c   (MCP,3 件全合 🎉 — stdio / tool-adapter / SSE+HTTP 全上线)
  支链 A0(2026-09-24 增): #009e → #022 → #023
    ├ #009e a2a-remote-tool-wiring  ── 必须先打平(wiring 修复,#023 内部依赖)
    ├ #022 spring-ai-annotation-tool  ── 可与 #009e 并行(无依赖)
    └ #023 delegate-sub-agent          ── 依赖 #009e(避免内部 patch 绕 RemoteAgentTool)
  支链 B: ✅ #022                       (Spring AI `@AgentTool`,已合 🎉)
  支链 C: #023                       (Sub-agent,依赖 #009e)
```

**R-13 mitigation (d) 假设**:复用 Spring AI `@Tool`(已在 13 项依赖表内)+ 复用 JDK 17+ `java.net.http.HttpClient`(SSE/HTTP)/ JDK 8 `ProcessBuilder`(stdio)/ 复用 Jackson + Lombok(已锁)。**预计 0 新依赖**(MCP stdio 用 JDK 内置 `ProcessBuilder` 即可,无需 `jackson-module-jsonSchema` 等额外包)。

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

1. **本周(2026-09-22 周)**:Story #009d + Story #018 文档同步已完成(本文件 + 配套 4 件套 dsync)
2. **2026-09-23 起**:Story #019 + #020a + #020b + #020c + #021a + #021b + #021c 已合,Skill 系统主链收官,MCP 支链 A 3/3 完成 🎉
3. **2026-09-24**:Story #009e a2a-remote-tool-wiring 已合(独立 `RemoteAgentToolAutoConfiguration` + `RemoteAgentToolLifecycle` SmartLifecycle + 3 transport 共用 wiring + 333 tests 0 fail / R-13 0 binary delta)
4. **2026-09-24**:Story #022 spring-ai-annotation-tool 已合(`@AgentTool` + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` + `LINGS-T08` + 513 tests pass / 0 fail / R-13 0 binary delta)
5. **2026-09-24 起**:下一步 **#023 delegate-sub-agent**(强依赖 #009e 已满足,可开窗)
6. **每个 Story 合入后**:更新本文件「✅ 已完成」表 + dsh §13 changelog + `constitution.md` §10 R-XX 缓解率 + README.md Story 路线图
7. **每月 1 号**:review 本文件,确认 P0 → P2 升级 / 滞后顺序调整
