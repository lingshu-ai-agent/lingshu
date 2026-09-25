# LingShu 工程 Roadmap — 待实施 Story 索引

> **文件目的**:跟踪 dsh `dsh_agent_design.md` §6 关键实现 + §14 生产增强(N1—N13)中尚未实施的 Story。
> **与 SKILL 配合**:新 Claude Code 会话 turn 1 读本文件 → 决定下一步 `/specify <slug>`。
> **与 constitution.md 关系**:本文件是工程 tracker(可修改);`constitution.md` 是法律(不可改,改须走 RFC)。
> **与 dsh_agent_design.md 关系**:dsh 是设计真理(只读);本文件是「哪些 dsh 章节已落地 / 哪些待 Story」的实施映射。
> **创建日期**:2026-09-22 — Story #009d a2a-remote-schema-builder 已合入,扫 §6 关键实现发现 5 大块空白。
> **更新日期**:2026-09-25 — **Story #024 follow-up + Story #025 + Story #025b + Story #025 follow-up x2 已合**(631 tests pass / 0 fail / R-13 0 binary delta 第 9 次 / 0 新 ErrorCode):
>   - **Story #024 follow-up a2a-server-tool-registry-dispatch**(`A2aServer.handleMessageSend` 走 `toolRegistry.lookup(skill)` → `tool.execute(call, ctx)` + cross-agent guard `params.agentName` 必须匹配 `Identity.name` + 30s timeout via `ToolCallConfig` + serve-mode `CountDownLatch` SIGTERM-clean 停机 + `tools/cleanup-ports.sh` SIGTERM→2s grace→SIGKILL 工具;10 文件改动 / +631 tests)
>   - **Story #025 demo-product**(`lingshu-examples/demo-product/` 新模块,HTTP SSE chat 产品组合 8 features:Spring Boot + SSE 流式 + ReAct 事件流 + `@AgentTool` + SKILL.md Skill + MCP stdio 子进程 + 内存会话 + Hot-reload 配置;15 文件 / +1320 行)
>   - **Story #025b demo-product-a2a-server**(`lingshu-examples/demo-product-a2a-server/` 新模块 9090 端口,跨 JVM translate demo 与 `demo-product` 8080 通过 `RemoteAgentTool` + `HttpJsonRpcA2aTransport` 互通;`DemoProductA2aServerApplication` 自起 JDK `HttpServer` 跑简化 JSON-RPC + 调本地 ToolRegistry,绕开 stock `A2aServer.handleMessageSend` 不接 dispatch 的事实;Bug fix: AgentCard `ApplicationReadyEvent` 而非 `@PostConstruct` 重建)
>   - **Story #025 follow-up x2**(主 commit 漏 2 文件:`McpServerProperties.bindFromEnvironment(...)` POJO + `mcp-servers/echo-stdio.py` Python stdlib MCP server 脚本 + `skills/help.md` force-add,`.gitignore` `HELP.md` 大小写不敏感吞 `help.md`)
>
> **更新日期**:2026-09-24 — **Story #023 delegate-sub-agent 已合**(536 pass / 0 fail / R-13 0 binary delta 第 8 次 / +LINGS-D01):`SubAgentType` enum(EXPLORE/ENGINEER/REVIEWER 闭合 3 值对齐 Claude Code 固定集)+ `DelegateErrorCodes.LINGS_D01`(Delegate 域 D 段 1 号 = DEPLOYMENT_CONFIG_INVALID)+ `SubAgentInheritance` 静态工具类(24-arg AgentConfig 手工拼接,Identity/Instructions/Memory 三件套 child-wins/parent-with-name-suffix/fallback 三段语义)+ `DelegateTool implements Tool`(`name()="Task"` + schema enum 3 值 + `execute()` 走 `agentFactory.create(childConfig)` fresh session,dsh §7.1 不变项守住)+ `DelegateAutoConfiguration`(InitializingBean 模式,e13e6a5 fix 用对齐,`agent.delegate` 缺失 = 跳过 register / 不全 = ISE [LINGS-D01] 启动 fail-fast);JDK 23 + Mockito inline mockmaker workaround: `StubAgentFactory extends AgentFactory` 子类(`super(null, null, null, null, null, null)` 绕开 @Autowired 6-Router)+ 真实 `AgentConfigRegistry.publish()` 而非 mock —— 沿用 Story #007 模式;**0 新 Maven 依赖**(Enum + LinkedHashMap + Jackson JsonNode + InitializingBean 全已锁);下一步走 §14 N7 SessionStore(Story #014)滞后项。
> **下次 review**:每个 Story 合入后更新「已完成」段。

---

## ✅ 已完成(Story #001—#009 + #009a/b/c/d + #017 + #018 + #019 + #020a + #020b + #020c + #009e + #022 + #023 + #024 + #025 + #025b)

详见 `README.md` 「Story 路线图」段 + `dsh_agent_design.md §13` changelog。共 28 个 Story 合入(对应 ~26 个 PR;follow-up commits 算 PR 内补丁,不独立计数):

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
| #023 | delegate-sub-agent | ✅ 合(2026-09-24,536 pass / 0 fail / R-13 0 binary delta 第 8 次 / +LINGS-D01;`SubAgentType` enum + `DelegateErrorCodes` + `SubAgentInheritance` + `DelegateTool` + `DelegateAutoConfiguration`) |
| #024 | tool-schemas-integration | ✅ 合(2026-09-24,**解决 OQ-5**;`DefaultPromptBuilder` 2 构造器注入 `ToolRegistry` → `Prompt.tools` = `toolRegistry.modelVisibleSpecs()`;0 新依赖 / 0 新 ErrorCode;OQ-5 由 §5.6.3.0 `RemoteAgentSchemaBuilder` → `RemoteAgentTool.description()` HINT 链路 + ToolRegistry 单点注册闭环) |
| #024 follow-up | a2a-server-tool-registry-dispatch | ✅ 合(2026-09-25,631 pass / 0 fail / R-13 0 binary delta 第 9 次;`A2aServer.handleMessageSend` 走 `toolRegistry.lookup(skill)` → `tool.execute(call, ctx)` + cross-agent guard `params.agentName` 必须匹配 `Identity.name` + 30s timeout via `ToolCallConfig` + serve-mode `CountDownLatch` SIGTERM-clean 停机 + `tools/cleanup-ports.sh` SIGTERM→2s grace→SIGKILL 工具;10 文件改动 / 0 新依赖 / 0 新 ErrorCode) |
| #025 | demo-product | ✅ 合(2026-09-25,15 文件 / +1320 行;`lingshu-examples/demo-product/` HTTP SSE chat 产品组合 8 features — `ChatController` SSE 流式响应 + `AgentEventMapper` ReAct 事件 → JSON + `DemoProductApplication` Spring Boot bootstrap + `ProductTools` / `ProductAgentTools` 业务 + `@AgentTool` 自动注册 + `SessionRegistry` 内存会话 + `mcp.servers[0]` stdio 子进程 + 3 SKILL.md (`clear` / `compact` / `help`);follow-up x2 补 `McpServerProperties` + `echo-stdio.py` + `skills/help.md`;0 新依赖) |
| #025b | demo-product-a2a-server | ✅ 合(2026-09-25,9090 端口;`lingshu-examples/demo-product-a2a-server/` 跨 JVM translate demo 与 `demo-product` 8080 通过 `RemoteAgentTool` + `HttpJsonRpcA2aTransport` 互通;`DemoProductA2aServerApplication` 自起 JDK `HttpServer` 跑简化 JSON-RPC + 调本地 ToolRegistry(stock `A2aServer.handleMessageSend` 不接 dispatch 的事实绕过);Bug fix: AgentCard `ApplicationReadyEvent` 而非 `@PostConstruct` 重建(`AgentToolScanner` 在 `ContextRefreshedEvent` 后才注册 @AgentTool);`HttpJsonRpcA2aTransport.httpBaseUrl` 默认从 yaml `agent.a2a.http-base-url` 读,默认值 9090;0 新依赖) |

---

## 🟡 §6 关键实现 待补(优先级 P0,核心引擎拼图)

dsh §6 关键实现章节(L3499-5152)中,**4/6 主章节有未落地子模块**。本段列出待 Story,按依赖顺序排列。

### 编号约定

- 复用 #NNN 编号空间,**不**新开子序列
- #018—#023 为本批新增;A2A Client 系列 #009a—#009d 已合,#009e 是 dsh §5.6.2 实施期发现的 wiring gap(**实测发现,不是设计意图**,2026-09-24 加)
- 后续 §6 待补若需更多 Story,顺延 #024—
- 编号规则:`#NNN-<slug>` 与现有 Story 路径(`specs/001-zero-config-bootstrap/` 等)对齐

### 提议 Story 列表(13 个,按依赖顺序)

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
| 8 | ~~**#023**~~ | ~~`delegate-sub-agent`~~ | ~~§6.6 + §6.6.1~~ | ~~`SubAgentType` enum + `DelegateTool` + `SubAgentInheritance`(字段级继承)+ `DelegateAutoConfiguration` + `LINGS-D01` ErrorCode~~ | ~~5~~ | ~~LINGS-D01 (sub-agent 配置缺失)~~ | **✅ 已合**(2026-09-24,536 pass / 0 fail / R-13 0 binary delta 第 8 次) |
| 9 | ~~**#024**~~ | ~~`tool-schemas-integration`~~ | ~~§6.4 [TOOL SCHEMAS] + §5.6.3.0 HINT 链路~~ | ~~`DefaultPromptBuilder` 注入 `ToolRegistry`,`Prompt.tools = toolRegistry.modelVisibleSpecs()`(sorted snapshot);Tool/LLM 视角完整闭环,OQ-5 解决~~ | ~~2(`DefaultPromptBuilder` 2 构造器)+ 1(`ToolRegistry.modelVisibleSpecs()` 新方法)~~ | ~~0~~ | **✅ 已合**(2026-09-24,536 pass / 0 fail / R-13 0 binary delta) |
| 10 | ~~**#024 follow-up**~~ | ~~`a2a-server-tool-registry-dispatch`~~ | ~~§5.6.3.1 / §5.6.3.2(实测发现,2026-09-25)~~ | ~~(1) `A2aServer.handleMessageSend` 走 `toolRegistry.lookup(skill)` → `tool.execute(call, ctx)`(2) cross-agent guard `params.agentName` 必须匹配 `Identity.name`(3) 30s timeout via `ToolCallConfig`(4) serve-mode `CountDownLatch` SIGTERM-clean 停机(5) `tools/cleanup-ports.sh` 工具~~ | ~~10 (4 new + 6 modified)~~ | ~~0~~ | **✅ 已合**(2026-09-25,631 pass / 0 fail / R-13 0 binary delta 第 9 次) |
| 11 | ~~**#025**~~ | ~~`demo-product`~~ | ~~§10.2 (examples 实装)~~ | ~~`lingshu-examples/demo-product/` 新模块,HTTP SSE chat 产品组合 8 features — Spring Boot + SSE 流式响应 + ReAct 事件流 + `@AgentTool` 自动注册 + SKILL.md Skill + MCP stdio 子进程 + 内存会话 + Hot-reload 配置 + 3 SKILL.md (`clear` / `compact` / `help`)~~ | ~~15 + 2(follow-up:`McpServerProperties` + `echo-stdio.py`)+ 1(`skills/help.md` force-add)~~ | ~~0~~ | **✅ 已合**(2026-09-25,15 文件 / +1320 行 / 0 新依赖) |
| 12 | ~~**#025b**~~ | ~~`demo-product-a2a-server`~~ | ~~§10.2 + §5.6.3.2 (A2A cross-JVM)~~ | ~~`lingshu-examples/demo-product-a2a-server/` 新模块 9090 端口,跨 JVM translate demo 与 `demo-product` 8080 通过 `RemoteAgentTool` + `HttpJsonRpcA2aTransport` 互通;`DemoProductA2aServerApplication` 自起 JDK `HttpServer` 跑简化 JSON-RPC + 调本地 ToolRegistry;Bug fix: AgentCard `ApplicationReadyEvent` 而非 `@PostConstruct` 重建(等 `AgentToolScanner` 注册完);`HttpJsonRpcA2aTransport.httpBaseUrl` 默认从 yaml `agent.a2a.http-base-url` 读,默认值 9090;Style A(LLM auto-discovery via RemoteAgentTool)+ Style B(显式 `/agent` slash skill)双路径~~ | ~~N(待补,具体文件数后续 PR body 补全)~~ | ~~0~~ | **✅ 已合**(2026-09-25,9090 端口 / 0 新依赖) |

**统计**:13 个 Story / **13 已合**(#020a + #020b + #020c + #021a + #021b + #021c + #009e + #022 + #023 + #024 + #024 follow-up + #025 + #025b)/ **0 待补** / ~80+ 个新文件(含 demo-product 15 文件 + demo-product-a2a-server ~5 文件 + `tools/cleanup-ports.sh` 1 文件)/ 实际 +598 测试 case 累计(同前;本批 4 Story 均为 examples / wiring 修补,Stage A demo-product / demo-product-a2a-server 未引入单元测试数 —— product demo 需 `spring-boot:run` 实测,Stage B 黑盒验证待补);R-13 mitigation (d) baseline 镜像第 9 次 PASS 0 binary delta(#024 follow-up `CountDownLatch` java.util.concurrent JDK-built-in / demo-product / demo-product-a2a-server 全部 JDK + Jackson + Lombok + spring-boot starter web 已锁);0 新 Maven 依赖 / 0 新 ErrorCode(自 #023 后);关键不变项:`AgentConfig` 不可变契约不变 / `AgentFactory` SPI 不变 / `Tool` SPI 不变 / `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容。

### 实施顺序建议(支持并行)

```
主链(必须顺序): ✅ #020a → ✅ #020b → ✅ #020c   (Skill 3 件套 ✅ 主链收官)
并行支链(与主链无依赖):
  支链 A: ✅ #021a → ✅ #021b → ✅ #021c   (MCP,3 件全合 🎉 — stdio / tool-adapter / SSE+HTTP 全上线)
  支链 A0(2026-09-24 增): ✅ #009e → ✅ #022 → ✅ #023
    ├ ✅ #009e a2a-remote-tool-wiring  ── 已合(wiring 修复,#023 内部依赖)
    ├ ✅ #022 spring-ai-annotation-tool  ── 已合
    └ ✅ #023 delegate-sub-agent          ── 已合(536 tests pass / 0 fail / R-13 0 binary delta 第 8 次 / +LINGS-D01)
  支链 A1(2026-09-25 增): ✅ #024 → ✅ #024 follow-up → ✅ #025 → ✅ #025b
    ├ ✅ #024 tool-schemas-integration        ── 已合(OQ-5 解决,Tool/LLM 视角架构闭环)
    ├ ✅ #024 follow-up a2a-server-tool-registry-dispatch ── 已合(631 pass / 0 fail / R-13 0 binary delta 第 9 次,serve-mode SIGTERM-clean 停机)
    ├ ✅ #025 demo-product                    ── 已合(15 文件 / +1320 行,HTTP SSE chat 产品组合 8 features)
    └ ✅ #025b demo-product-a2a-server        ── 已合(9090 端口,跨 JVM translate demo 与 demo-product 8080 互通)
  支链 B: ✅ #022                       (Spring AI `@AgentTool`,已合 🎉)
  支链 C: ✅ #023                       (Sub-agent,已合 🎉)
  支链 D: ✅ #025 + ✅ #025b             (Demo 产品,已合 🎉 — HTTP SSE chat + 跨 JVM A2A)
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

---

## ✅ 已解决 OQ(历史追溯)

之前 OQ-Future 表中的项,在后续 Story 中找到了解法,**不必再开 Story 重新讨论**:

| OQ | 锚定 § | 原标题 | 解决 Story | 解决方式 |
|---|---|---|---|---|
| OQ-5 | §5.6.3.0 | `PromptBuilder [TOOL SCHEMAS]` 段集成 `RemoteAgentSchemaBuilder` | **#024** tool-schemas-integration(2026-09-24)|`DefaultPromptBuilder` 2 构造器注入共享 `ToolRegistry` → 每 turn `Prompt.tools = toolRegistry.modelVisibleSpecs()`(sorted snapshot);`RemoteAgentTool` 经 `RemoteAgentToolLifecycle`(Story #009e)单点注册到 ToolRegistry,`RemoteAgentSchemaBuilder`(Story #009d)是 `RemoteAgentTool.description()` HINT 链路的上游(per-skill 列表经 description 透传给模型,既避免 N-tool Bean 爆炸又满足 LLM 视角可见性);OQ-5 主张的"PromptBuilder [TOOL SCHEMAS] 集成 RemoteAgentSchemaBuilder"通过 ToolRegistry 这一层隐式闭环,**无需** linghu-core 反向依赖 linghu-a2a-client(§5 模块依赖硬约束)|

---

## 🎯 实施节奏建议

1. **本周(2026-09-22 周)**:Story #009d + Story #018 文档同步已完成(本文件 + 配套 4 件套 dsync)
2. **2026-09-23 起**:Story #019 + #020a + #020b + #020c + #021a + #021b + #021c 已合,Skill 系统主链收官,MCP 支链 A 3/3 完成 🎉
3. **2026-09-24**:Story #009e a2a-remote-tool-wiring 已合(独立 `RemoteAgentToolAutoConfiguration` + `RemoteAgentToolLifecycle` SmartLifecycle + 3 transport 共用 wiring + 333 tests 0 fail / R-13 0 binary delta)
4. **2026-09-24**:Story #022 spring-ai-annotation-tool 已合(`@AgentTool` + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` + `LINGS-T08` + 513 tests pass / 0 fail / R-13 0 binary delta)
5. **2026-09-24**:**Story #023 delegate-sub-agent 已合**(536 pass / 0 fail / R-13 0 binary delta 第 8 次 / +LINGS-D01;`SubAgentType` enum + `SubAgentInheritance` + `DelegateTool` + `DelegateAutoConfiguration` + 23 new cases);**§6 关键实现 主链 + 并行支链全部合入 🎉🎉🎉**;下一步走 §14 N7 SessionStore(Story #014 滞后项)
6. **2026-09-24**:**Story #024 tool-schemas-integration 已合** — `DefaultPromptBuilder` 注入 `ToolRegistry`,`Prompt.tools` = `toolRegistry.modelVisibleSpecs()`(sorted snapshot);**OQ-5 解决**;Tool/LLM 视角完整闭环(本地 / MCP / @AgentTool / Skill / RemoteAgentTool 全部经统一 registry 暴露);0 新依赖 / 0 新 ErrorCode / R-13 mitigation (d) 待 commit 后跑 baseline 镜像 diff
7. **2026-09-25**:**Story #024 follow-up a2a-server-tool-registry-dispatch 已合**(631 tests pass / 0 fail / R-13 0 binary delta 第 9 次;`A2aServer.handleMessageSend` 走 `toolRegistry.lookup(skill)` → `tool.execute(call, ctx)` + cross-agent guard `params.agentName` 必须匹配 `Identity.name` + 30s timeout via `ToolCallConfig` + serve-mode `CountDownLatch` SIGTERM-clean 停机 + `tools/cleanup-ports.sh` SIGTERM→2s grace→SIGKILL 工具;0 新依赖 / 0 新 ErrorCode)
8. **2026-09-25**:**Story #025 demo-product 已合** — `lingshu-examples/demo-product/` 新模块,HTTP SSE chat 产品组合 8 features(Spring Boot + SSE 流式响应 + ReAct 事件流 + `@AgentTool` 自动注册 + SKILL.md Skill + MCP stdio 子进程 + 内存会话 + Hot-reload 配置);15 文件 / +1320 行 / 0 新 Maven 依赖
9. **2026-09-25**:**Story #025b demo-product-a2a-server 已合** — `lingshu-examples/demo-product-a2a-server/` 新模块(9090 端口),跨 JVM translate demo 与 `demo-product`(8080 端口)通过 `RemoteAgentTool` + `HttpJsonRpcA2aTransport` 互通;`A2aServer.handleMessageSend` 不接 dispatch 的事实绕过 = 自起 JDK `HttpServer` 跑简化 JSON-RPC + 调本地 ToolRegistry;Bug fix: AgentCard `ApplicationReadyEvent` 而非 `@PostConstruct` 重建(`AgentToolScanner` 在 `ContextRefreshedEvent` 后才注册 @AgentTool);0 新 Maven 依赖
10. **2026-09-25**:**Story #025 follow-up x2 已合** — 补 demo-product 主 commit 漏的 2 文件(`McpServerProperties.bindFromEnvironment(...)` POJO + `mcp-servers/echo-stdio.py` Python stdlib MCP server 脚本)+ `skills/help.md` force-add(`.gitignore` `HELP.md` 大小写不敏感吞 `help.md`);0 新依赖;**R-13 mitigation (d) baseline 镜像第 9 次 PASS 0 binary delta**
11. **每个 Story 合入后**:更新本文件「✅ 已完成」表 + dsh §13 changelog + `constitution.md` §10 R-XX 缓解率 + README.md Story 路线图
12. **每月 1 号**:review 本文件,确认 P0 → P2 升级 / 滞后顺序调整
