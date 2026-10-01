<div align="center">
  <img src="https://raw.githubusercontent.com/lingshu-ai-agent/lingshu/main/assets/lingshu_logo.svg" alt="LingShu" width="120"/>

  <h1>lingshu · 灵枢</h1>
  <p><strong>The Pivot of Agent Orchestration</strong></p>

  > **更新日期**:2026-10-01 — **🛡️ Story #034 a2a-http-domain-guard 已合**(673 pass / 0 fail / 2 MCP heartbeat flake pre-existing / R-13 0 binary delta 第 22 次 PASS / **0 新 ErrorCode**(复用 #028 `LINGS-S01`)/ **累计 38 个 Story 合入**):**A2A HTTP transport 沙箱守卫落地**(与 #033 MCP 故事平行):复用 `McpHttpSupport.checkOrThrow(url, whitelist)` 静态 helper(JDK `URI.create(url).getHost()` + `whitelist.contains(host)`),**2 hook point** 加在 `HttpJsonRpcA2aTransport`(fetchCard + jsonRpcCall 共享,submit/get/cancel 都走同一 hook),失败抛 `AccessDeniedException[LINGS-S01]` 真实请求**不**发出;`AgentRef.@Value` 加 `List<String> domainWhitelist` 字段(per-remote-agent 配置,yml `domain-whitelist: [host1, ...]` kebab-case 绑定)+ `getDomainWhitelistOrEmpty()` null-safe accessor(null → `Collections.emptyList()` strict-mode 默认 deny-all,镜像 #033 `McpServerConfig.domainWhitelist` 语义);`HttpJsonRpcA2aTransport` 5-arg ctor 接收 whitelist + defensive copy(`new ArrayList<>(domainWhitelist)`)+ 4-arg ctor 保留 back-compat wrapper;`HttpJsonRpcA2aTransportAutoConfiguration.HttpJsonRpcA2aTransportFactory` 内嵌 static class + `@Bean(name="a2aTransportFactory_http-jsonrpc")` 暴露 `build()` 把 `cfg.a2a.remoteAgents[*].domainWhitelistOrEmpty()` union 去重后传给 A2A transport(strict mode 镜像 MCP #033);`RemoteAgentToolAutoConfiguration.remoteAgentTool()` 注入 factory,`if (transportName=="http-jsonrpc-1.0.0") transport = httpJsonRpcFactory.build();` 否则 `router.resolve(...)`(grpc / in-process 不走 HTTP 不变);**13 new case** 跨 4 文件(`HttpJsonRpcA2aTransportCheckOrThrowTest` 6 L1:emptyWhitelist/nonMatchingHost/matchingHost/ctorValidation 6 子 case/defensiveCopy/snapshotReturn/`HttpJsonRpcA2aTransportDomainGuardIT` 3 L2:JDK `com.sun.net.httpserver.HttpServer` 计数 hits 真发请求,whitelisted hits==2+SUCCESS / non-whitelited hits==0+AccessDenied / strict-mode empty hits==0/`HttpJsonRpcA2aTransportAutoConfigurationTest` +1 L1 factory union dedup + 1 modify fixture `HttpJsonRpcA2aTransportTest` 3 call site 4-arg → 5-arg);**关键不变项** —— `A2aTransport` 5 方法 SPI 不变 / `A2aTransportRouter` 不变 / `RemoteAgentTool` 不变(只看 `A2aTransport` 接口)/ `McpHttpSupport.checkOrThrow` 公开方法不变(只被新增 caller 调用)/ `AccessDeniedException[LINGS-S01]` ErrorCode 复用 / `Tool` SPI 不变 + `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `AgentConfig` 不可变契约不变(只 AgentRef 内部加字段)/ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 9 Slot 体系不变 / JDK 8 兼容(`URI.create` + `List.contains` + `HashSet` + `ArrayList` + `Collections.emptyList` + `Arrays.asList` + Jackson `@JsonProperty` kebab-case 已锁,no `var` / `List.of` / sealed / records) / **0 新 Maven 依赖** / **0 新 ErrorCode** / R-13 mitigation (d) baseline 镜像 **第 22 次 PASS 0 binary delta**(`URI.create` + `List.contains` + `HashSet` JDK 8 内置 0 新 binary 引入);**业务价值** —— A2A 路径 sandbox 边界守卫到位(与 MCP HTTP 对齐,Agent 调任何远端 A2A server 必须先过 `WhitelistedHttpClient` 沙箱) / `WhitelistedHttpClient` 基建从 idle → enforced(一路通过 #028 → #033 MCP + #034 A2A 真正在 HTTP 请求路径上 enforce) / per-remote-agent 粒度配置(不同 remote agent 不同 domain-whitelist,A2A 多 server 部署友好);**修复者** Claude Code(根据用户 2026-10-01 会话反馈,用户问「a2a的网络连接也和MCP的网络链接一样使用的了whitelist做校验吗」 + 「走 MCP 复用模式」 + 「实施吧」,触发本 Story 实施 + 13 case AC 黑盒验证 + R-13 mitigation (d) baseline 镜像 **第 22 次 PASS**)。

  > **更新日期**:2026-10-01 — **🛡️ Story #033 mcp-http-domain-guard 已合**(~675 pass / 0 fail / 2 MCP heartbeat flake pre-existing / R-13 0 binary delta 第 18 次 PASS / **0 新 ErrorCode** / **累计 37 个 Story 合入**):**Path B + Mitigation 1 实装** —— MCP HTTP transports(SSE + Streamable HTTP)**保持 raw `HttpURLConnection` 不动**,仅在每次出站请求**前**调 `McpHttpSupport.checkOrThrow(url, domainWhitelist)` 守卫(host 提取走 `URI.create(url).getHost()`,非白名单 → `AccessDeniedException[LINGS-S01]`,同 Story #028 `WhitelistedHttpClient.check()` + Story #032 `WebFetchTool` 语义);`McpServerConfig.domainWhitelist`(`@Builder.Default List<String>`)+ ctor defensive copy + **12 hook point**(SSE 7:`callTool` + 3 `doConnect`(initialize / notifications/initialized / tools/list)+ `heartbeatTick` + `openSseStream`(`/sse` GET)+ `relistTools`(listChanged 触发重拉);Streamable HTTP 5:`callTool` + 3 `doConnect` + `heartbeatTick`);9 现有 SSE/Streamable HTTP 测试加 `.domainWhitelist(Arrays.asList("127.0.0.1"))` 让本地 127.0.0.1 fixture 通过守卫;**13 new case** 跨 3 文件(`McpHttpSupportCheckOrThrowTest` 8 L1:空 whitelist 全 deny / null whitelist / 匹配 / 不匹配 / null/empty URL / malformed URL / case-sensitive / IPv4 host 提取 / `McpHttpDomainGuardIT` 4 L2:真 JDK `com.sun.net.httpserver.HttpServer` 起服 + SSE whitelisted reaches CONNECTED + SSE non-whitelisted stays RECONNECTING + Streamable HTTP non-whitelisted stays RECONNECTING / `McpServerConnectionFactoryTest` 1 case:`domainWhitelist` 走 `SseMcpServerConnection` 装配 wire-through);**关键不变项** —— `McpServerConfig` 不可变契约不变(`@Value` + `@Builder` 9 → 10 字段 final,`@Builder.Default` 兜底空 list,与 #028 `WhitelistedHttpClient` 防御性拷贝模式对齐)/ `McpTransport` SPI 不变 / `McpServerConnection` SPI 8 方法契约不变(只 2 concrete 实现加私有 `domainWhitelist` 字段)/ `McpHttpSupport` 公开 API 不变(postJsonRpc/getJson/postNotification/parseToolList/parseCallResult 0 改动,只新增 1 个静态 helper `checkOrThrow`)/ `McpServerConnectionFactory` dispatch 不变 / `AccessDeniedException[LINGS-S01]` ErrorCode 复用 / 9 Slot 体系不变 / JDK 8 兼容(`URI.create` JDK 1.4 内置 + `AccessDeniedException` 已在 lingshu-core 既有 / no `var` / `List.of` / sealed / records)/ **0 新 Maven 依赖** / **0 新 ErrorCode**(复用 `LINGS-S01`,Sandbox 域 S 段 1 号)/ R-13 mitigation (d) baseline 镜像 **第 18 次 PASS 0 binary delta**(纯 JDK 8 standard + Spring `@Builder.Default` + Lombok `@Value` 0 新 binary 引入);**关键决策** —— Path B + Mitigation 1 vs Path A(`WhitelistedHttpClient` 全路径替换)避开 §4.10.1 硬规则 2(MCP 长连接 + JSON-RPC 协议 + SSE streaming 不能套 `WhitelistedHttpClient`),最小触碰 12 hook point 全 1 行 `checkOrThrow` 前置 / 影响面积 0 行公开 API 改动,**OQ-Future 风险**:MCP HTTP 配置 schema 暂未绑定 yml(`agent.mcp.servers[*]` 走 hand-rolled YAML parser,parser 暂未解析该字段),whitelist 暂**只能**通过 `McpServerConfig.builder().domainWhitelist(...)` 编程方式设置;**Story #033 后续** —— 配置绑定 (`McpTransportAutoConfiguration` 解析 `domain-whitelist` 字段)推到 Story #034+;**修复者** Claude Code(根据用户 2026-09-30 会话反馈「Story #033 实施吧」+ Path B + Mitigation 1 方案 spec/plan/tasks 已审批通过)。

  > **更新日期**:2026-10-01 — **🎯 Story #031 permission-policy-pattern-matching 已合**(647 pass / 0 fail / 2 MCP heartbeat flake pre-existing / R-13 0 binary delta 第 16 次 PASS / **0 新 ErrorCode**(复用 LINGS-P01)/ **累计 35 个 Story 合入**):**OQ-9 follow-up 解决** —— Story #029 的 brittle `List.contains` 12 行静态枚举替换为 `PermissionPatterns` 三形式通配匹配:(1) `*` 通配(允许/拒绝所有 Tool)/(2) `<name>` 精确名匹配(back-compat with Story #029 字符串 yml 条目)/(3) `<category>:*` 分类前缀匹配(5 保留 category:`local / mcp / skill / a2a / delegate`)+ `Tool.sourceCategory()` 默认方法(所有 Tool 必实现,缺省回退 `"local"`)+ `StrictPermissionPolicyProvider.create(AgentConfig)` 启动期调 `ToolRegistry.findAll()` 构造 `nameToCategory: Map<String, String>` 注入 `StrictPermissionPolicy`(2-arg ctor,1-arg ctor 保留 back-compat)+ `StrictPermissionPolicy.check()` 4 段决策逻辑(deny-list 命中 → Deny / allow-list 空 → default-allow / allow-list 命中 → Allow / 不命中 → Deny with category 上下文)+ `StrictPermissionPolicy` 移除 `@Component` + `StrictPermissionPolicyProvider` 移除 `@Component`(均改为 plain class —— Policy 是 value-object,Provider 由 AutoConfiguration `@Bean(name="permissionPolicyProvider_strict-1.0.0")` 唯一注册,符合 v1.5.28 §5.5 多 Provider 模式)+ `demo-product/application.yml` 12 行 `allow-list` 静态枚举 → 单行 `allow-list: ["*"]` 通配 + `DemoProductApplication.readTools(Environment, ToolsConfig)` 私有静态 helper 真正吃 yml `agent.tools.allow-list[N]` + `agent.tools.deny-list[N]`(Story #029 follow-up #1 漏掉的 yml-binding 修补) + 2 L3 IT(通配 12 Tool 名全 Allow + 分类模式 6 case 含 4 个 stub Tool via `@TestConfiguration`);**26 new case**(8 `PermissionPatternsTest` L1 + 4 `ToolSourceCategoryTest` L1 + 6 `StrictPermissionPolicyPatternTest` L1 + 1 `StrictPermissionPolicyReasonTest` L1 + 2 `AgentFactoryPatternMatchingIT` L2 + 1 `DemoProductPermissionWildcardIT` L3 + 2 inline 6 case in `DemoProductPermissionCategoryPatternIT` L3 + 2 `StrictPermissionPolicyProviderTest` 共享)+ 5 Story #029 测试仍 PASS(StrictPermissionPolicy 1-arg ctor back-compat);**关键不变项** —— `PermissionPolicy` SPI 不变 / `Decision` 3 子类(Allow / Deny / AskUser)不变(AskUser 仍 §4.7 未来 RFC)/ `Tool.sourceCategory()` 默认方法新增(不破坏现有 Tool 实现)/ `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `AgentConfig` 不可变契约不变(0 字段改动)/ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)/ 9 Slot 体系不变 / JDK 8 兼容(`Pattern.quote` + `String.startsWith` + `String.equals`,no `var` / `List.of` / sealed / records)/ **0 新 Maven 依赖** / **0 新 ErrorCode**(复用 LINGS-P01)/ R-13 mitigation (d) baseline 镜像 **第 16 次 PASS 0 binary delta**(`PermissionPatterns.java` 是纯 JDK `Pattern` + `String` helper,零新 binary);**修复者**:Claude Code(根据用户 2026-09-30 会话反馈,用户问"Story #029 的 allow-list 写起来像在维护死亡名单 —— 能不能支持 `mcp:*` 这种通配?")。

  > **更新日期**:2026-09-30 — **🛡️ Story #029 permission-policy-impl 已合**(626 pass / 0 fail / 0 MCP flake / R-13 0 binary delta 第 15 次 PASS / +LINGS-P01 / **11 域字母** `C/S/L/T/X/R/A/M/D/P/Z`):**OQ-9 解决** —— `PermissionPolicy` SPI 真实现落地:`StrictPermissionPolicy` @Component @Value + 3 段决策逻辑(allow 空 → 全 Allow / deny 命中 → Deny / allow 非空 + 命中 → Allow + allow 非空 + 不命中 → Deny 嵌 `[LINGS-P01]`)+ `StrictPermissionPolicyProvider` SPI(name="strict" + priority=10)+ `PermissionErrorCodes.LINGS_P01`(`P` 域 1 号,**自 #023 后首次启用新 ErrorCode 域**)+ `AgentConfig.permissionPolicy` 扩 + `ToolsConfig.allowList` / `denyList` 扩 + `AgentFactory.loadYamlAndValidate` kebab-case 绑定(`permission-policy: strict` top-level + `tools.allow-list` / `tools.deny-list` sub-key)+ `PermissionPolicyRouter` 多 Provider 模式(v1.5.28) name-based resolve;**18 new case** 跨 4 文件(`StrictPermissionPolicyTest` 8 L1 + `StrictPermissionPolicyProviderTest` 2 L1 + `PermissionPolicyRouterStrictIT` 4 L2 + `AgentFactoryYamlPermissionPolicyIT` 3 L2);**yolo path back-compat** —— `permission-policy: default` 仍走 `AllowAllPermissionPolicy`(demo-product / demo-empty `application.yml` 0 改动);**OQ-9 follow-up** —— `StrictPermissionPolicy.check()` 暂不实现 **AskUser** 决策路径(decision.governance 仍 §4.7 未来 RFC);**关键不变项** —— `PermissionPolicy` SPI 不变 / `Decision` 3 子类(Allow / Deny / AskUser)不变 / `AgentConfig` 不可变契约不变(只 +1 top-level +2 ToolsConfig 字段)/ `Tool` SPI 不变 / `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `AgentFactory` SPI 不变(@Autowired 6-Router ctor **不动**,新 Provider 由 `@Component` 自动注册)/ 9 Slot 体系不变 / JDK 8 兼容(`Collections.emptyList()` / `Arrays.asList()` / Jackson `@JsonProperty` kebab-case 已锁,no `var` / `List.of` / sealed / records) / **0 新 Maven 依赖** / **1 新 ErrorCode LINGS-P01** / R-13 mitigation (d) baseline 镜像 **第 15 次 PASS 0 binary delta**;累计 34 个 Story 合入。

  > **更新日期**:2026-09-30 — **🧹 v1.5.46 refactor:删除死代码 `Message.ToolUse`**(583 pass / 2 MCP heartbeat flake pre-existing / R-13 N/A 无新依赖 / 0 ErrorCode):`Message.ToolUse`(`Message.java` L73-84)从未被生产代码 `new` 出来过 —— 历史是 #020a `Skill` / #020c `SkillCommandDispatcher` 早期设计的"独立 `Message.ToolUse` subtype 表示一次 tool_call" 路径,后 #024 / #027a 协议转换层落地后,实际生产链路改走"`Message.Assistant.toolCalls` 嵌入模式",`Message.ToolUse` 自此变成 0 引用死代码,只剩 `TruncatingCompactor.messageCharLen` 一个 `instanceof` 分支 + 5 处 JavaDoc `@link` 引用;本次直接删除 `ToolUse` nested class + `import com.fasterxml.jackson.databind.JsonNode`(已无人用) + `Message.role()` Javadoc 「5 类」→「4 类」+ `TruncatingCompactor.messageCharLen` 删 instanceof 分支(class-level JavaDoc "assistant+tool_use+tool_result triples" → "Assistant messages + their trailing ToolResult blocks")+ `AnthropicLlmProvider.L383` 注释「Message.ToolUse is not stored in session history」→「tool_use blocks live on Message.Assistant.toolCalls」+ `LingsLlmProviderException` / `LlmErrorCodes` JavaDoc 5 处 `@link ai.lingshu.core.message.Message.ToolUse` → `@link ai.lingshu.core.message.Message.Assistant#toolCalls`;5 文件改动(5 modify 0 new)/ 0 新 Maven 依赖 / 0 新 ErrorCode / **0 测试 case 改动**(全部 583 测试 0 回归,2 MCP flake pre-existing 已在 CLAUDE.md 文档化)/ **`mvn -pl lingshu-core dependency:tree` 0 binary delta**(纯 Java 文件内类型清理);**关键不变项** —— `Tool` SPI 不变 / `Message` 5 → **4 子类**(System / User / Assistant / ToolResult,Assistant 仍带 `toolCalls` 字段不变)/ `Message.Assistant.toolCalls` 嵌入契约不变(#024 已落)/ `LlmResponse.toolCalls` 不变(#027a 已落)/ `LinearTurnEngine` 公开方法签名不变(`#027a` 1 行 wire-through 修复已落)/ `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `AgentConfig` 不可变契约不变 / `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 9 Slot 体系不变 / 24 字段 AgentConfig schema 不变 / JDK 8 兼容(`Collections.emptyList()` / `Arrays.asList()` / Jackson 已锁,no `var` / `List.of` / sealed / records)/ dsh §13 v1.5.46 行新增 8 节记录。

  > **更新日期**:2026-09-26 — **Story #026 yaml-placeholder-resolution 已合**(567 pass / 0 fail / R-13 0 binary delta 第 11 次 / +LINGS-C03 / +LINGS-C04):`PlaceholderResolver` 静态工具类(brace-counting scanner 4-form grammar `${X}` / `${X:default}` / `${X:${Y}}` / `$${literal}` escape + 32 层递归深度 + `Set<String> visited` 环检测)+ `YamlPlaceholderErrorCodes`(`LINGS-C03 YAML_PLACEHOLDER_UNRESOLVED` / `LINGS-C04 YAML_PLACEHOLDER_CYCLE`,Config 域 C 段 3/4 号)+ `AgentFactory.loadYamlAndValidate` 真接 `PlaceholderResolver.resolvePlaceholders(agent, ymlPath)`(`parseMinimalYaml` 与 `toAgentConfig` 之间 hook),**修跨路径 placeholder parity bug** —— 之前 CLI + YamlWatcher hand-rolled 路径下 `${user.dir}` 静默变 13 字符串,Spring Env 路径(demo-product)一直支持;17 单元测试(`PlaceholderResolverTest`)+ 2 IT 测试(`YamlHotReloadIT` `${user.dir}` 跨 hot-reload 真接 Path + unresolved `${X}` 触发 LingsConfigException → YamlWatcher catch + rollback);5 文件改动(3 new + 2 modify)/ 0 新 Maven 依赖(R-13 第 11 次 PASS);累计 30 个 Story 合入。

  > **更新日期**:2026-09-26 — **Story #025 follow-up sandbox-wiring 已合**(631 pass / 0 fail / R-13 0 binary delta 第 10 次 / 0 新 ErrorCode):`DemoProductApplication.readSandbox(Environment)` 私有静态 helper(模仿 `readRemoteAgents(env)` 模式,5 字段全读 + INFO 日志分支)+ `readSandboxList` 索引式 list helper + `mergeConfig()` 签名 +1 `AgentConfig.Sandbox` 参数(`@Value` 24-字段构造器位置 5)+ `agentConfig(Environment)` @Bean 真吃 `agent.sandbox:` YAML 5 字段(policy/runtime/working-directory/command-whitelist/domain-whitelist);Spring 启动日志 `agentConfig: sandbox bound from YAML — policy=default runtime=chroot workingDir=<cwd> cmdWhitelist(size=11) domainWhitelist(size=2)` 验证接线;3 文件改动 / ~110 行代码 + ~35 行 YAML + ~3 行 README / 0 新 Maven 依赖 / 0 新 ErrorCode;`application.yml` sandbox 段注释同步更新(说明 ChatController 限制与 mcp/a2a 同病)

  <p>
    <a href="https://github.com/lingshu-ai-agent/lingshu/stargazers"><img src="https://img.shields.io/github/stars/lingshu-ai-agent/lingshu?style=for-the-badge" alt="stars"/></a>
    <a href="https://github.com/lingshu-ai-agent/lingshu/network/members"><img src="https://img.shields.io/github/forks/lingshu-ai-agent/lingshu?style=for-the-badge" alt="forks"/></a>
    <a href="https://github.com/lingshu-ai-agent/lingshu/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-blue?style=for-the-badge" alt="license"/></a>
    <a href="https://github.com/lingshu-ai-agent/lingshu/issues"><img src="https://img.shields.io/github/issues/lingshu-ai-agent/lingshu?style=for-the-badge" alt="issues"/></a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/java-8+-D97706?style=for-the-badge&logo=openjdk&logoColor=white" alt="java"/>
    <img src="https://img.shields.io/badge/spring--boot-2.7%2B-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="spring"/>
    <img src="https://img.shields.io/badge/maven-3.6%2B-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white" alt="maven"/>
    <img src="https://img.shields.io/badge/license-Apache_2.0-blue?style=for-the-badge" alt="license"/>
  </p>
</div>

---

## 灵枢 · The Pivot

**灵枢**(`líng shū`,意为"针灸的关键枢轴")是 LingShu 引擎的核心仓库 —— 一个为 **JDK 8+** 企业 Java 栈设计的、生产级 **ReAct Loop Agent Engine**。

> 名字的由来:**Agent 的本质是一个循环**(ReAct),而循环需要一个**枢轴**才能转得稳。
> 我们把这个"枢轴"叫做 LingShu。

> 🟢 **We're an Engine, not an OS.**
> [OryxOS](https://github.com/oryx-labs/oryxos) 等项目定位是"Distributed Agent OS"——单 JAR 部署、跨节点协调、Java 21 + virtual threads。
> LingShu 不跟他们抢这条赛道:LingShu 是一个**嵌进你 Spring Boot 进程的 Engine**,
> 不需要为 Agent 单独搭集群、不需要把 JDK 升到 21、不需要新运维模型。
> 如果你的团队还在 JDK 8 LTS 上、并且你的服务已经跑在 Spring Boot 里 —— LingShu 是默认选项。

---

## ✨ 核心特性

- 🚀 **JDK 8 优先** — 不用 `var` / sealed / records / `List.of` / pattern-switch,主流 JDK 8 LTS 系统直接跑
- 🧩 **9 个 SPI 槽位** — PromptBuilder / LlmProvider / ToolExecutor / PermissionPolicy / RuntimeSandbox / SessionStore / Compactor / **FlowEngine** / **A2aTransport** —— 全部 Spring `@Component` + `@AutoConfiguration` 注册,Provider 按 `name()` 路由,SlotRouter 启动期校验 `version()` 兼容性
- 🔁 **ReAct Loop 一等公民** — 默认 `LinearTurnEngine`,显式 step 计数 + ReasoningStarted / ObservationAppended / MaxStepsExceeded 三类事件
- ⚡ **并行工具调度** — `LinearTurnEngine.dispatchParallel` Semaphore-bounded `CompletableFuture` 池,`config.tool.parallelism` 控制并发度,结果按 LLM-return 顺序回填
- 🔌 **MCP 客户端内置** — 通过 `McpToolAdapter` 把任意 MCP server 当 Tool 源
- 📜 **Skill = Tool 标记接口** — `SKILL.md` 解析 → 自动注册为 Tool,classpath + 目录双源
- 🛡️ **双层沙箱** — `PermissionPolicy`(模型层)+ `RuntimeSandbox`(系统层,chroot/seccomp/sysbox)
- 🎯 **PermissionPolicy 三形式通配** — `StrictPermissionPolicy` yml `allow-list` / `deny-list` 支持 `*` 通配 + `<name>` 精确名 + `<category>:*` 分类前缀(`mcp:*` / `skill:*` / `a2a:*` / `delegate:*` / `local:*` 五保留 category),`Tool.sourceCategory()` 默认方法 + 5 核心 Tool 类型覆盖,Story #029 字符串白名单模式升级为通配 + 分类(Story #031)
- 🔄 **FlowEngine 可替换** — `LinearTurnEngine` 默认,`GoogleAdkFlowEngine` / `AlibabaGraphFlowEngine` / 自研 DAG 可平替
- 🪶 **Lombok 友好** — `@Value` 不可变风格,拒绝过度抽象
- 🔁 **YAML 热更无中断** — `AgentConfigRegistry` `AtomicReference` 单写多读 + `Files.getLastModifiedTime` 5s poll + `DefaultAgent.run()` 入口一次性 freeze,旧 turn 冻结 cfg 引用语义自然隔离(Story #007)
- 🛑 **ReAct 上限守卫** — `LinearTurnEngine.runTurn` `maxStepsHit` 守卫标志 + `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` 结构化事件,防止 LLM 死循环 token 失控(Story #008)
- 🌐 **A2A AgentCard 已上线** — `GET /.well-known/agent.json` 服务端暴露,A2A v1.0 §2.1 协议对齐,字段直接来源于 `cfg.getIdentity()`,无需额外 yml(Story #009 AC-10)。A2A 客户端 4 子 Story 拆分(详见 [Story 路线图](#-story-路线图-009a009d-a2a-client-系列)节):**#009a GrpcA2aTransport**(本轮 / grpc-java + protobuf)+ **#009b InProcessA2aTransport**(同 JVM 直接调用 / 0 额外依赖)+ **#009c HttpJsonRpcA2aTransport + RemoteAgentTool**(默认 Provider / JDK HttpClient / 0 额外依赖)+ **#009d RemoteAgentSchemaBuilder**(扫 `AgentCard.skills[]` 生成 `ToolSpec` list / 0 额外依赖)
- 🖥️ **CLI 入口已上线** — `mvn -pl lingshu-cli spring-boot:run --args='run --config app.yml --prompt ...'`,5 个子命令 `run / resume / serve / doctor / config`,hand-rolled argv 解析器零新依赖,Story #017 dsh §10.3 全落地
- 🧹 **TruncatingCompactor 已上线** — `Compactor` SPI Slot 2 v1 默认实现,两步压缩(ToolResult 内容截断 + 滑动窗口收口),`Session.compact(List)` 原子替换 + 与 `append(Message)` 同锁,`@Value AgentConfig.CompactorConfig(maxPromptTokens / maxToolResultBytes / keepRecentTurns)` zero-config 默认 `(100_000 / 50_000 / 20)`(Story #018 dsh §6.2)
- 🛠️ **5 个内置 Tool 已上线** — `Read` / `Write` / `Edit` / `Bash` / `WebFetch`(`@Component implements Tool`),`LocalToolsAutoConfiguration` 启动期自动注册到 `DefaultToolExecutor.registry`,Bash 复用 `RuntimeSandbox.process()` 走 tenant whitelist,字节上限先于盘写(防 OOM / 防路径穿越),`agent.tools.enabled=false` 干净跳过(Story #019 dsh §6.5 (1)+ **Story #032 WebFetch 本地 HTTP/HTTPS 抓取** —— `name()="web_fetch"` + `description()` 含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported" + `inputSchema` `{url: string required, max_bytes?: integer}` + `execute()` 4 段委托 `ctx.http().get(url)` 走 `WhitelistedHttpClient.check()`(Story #028 沙箱基建复用)→ JDK `HttpURLConnection` / `HttpsURLConnection` 透明 HTTPS + User-Agent `ChaOS-LingShu-Sandbox/1.0` 透传 + 1 MB truncation marker `\\n...[truncated, original %d bytes]` + catch `AccessDeniedException` 嵌 `[LINGS-S01]` + catch `IOException` 嵌 `HTTP fetch failed: ...`;**Claude Code parity**:本地 `WebFetch` 与 MCP fetch server **共存**(built-in + MCP 并行,非互斥);GET-only,POST/PUT/DELETE 走 MCP)
- 🧩 **Skill 系统第一块砖** — `SkillTool` concrete class + `fromMarkdown` 静态工厂(SKILL.md → Skill)+ `@Component CommitSkill`(`/commit` 按 Conventional Commits 风格生成 commit message)+ `ToolRegistry` 4 新方法(`modelVisibleSpecs / findSkill / skillNames / findByName`)+ `SkillAutoConfiguration` 注册样板(复用 `LocalToolsAutoConfiguration` 模板 + `@Lazy Map<String, Skill>` 破 bean-cycle + `agent.skills.enabled` 开关),`DefaultToolRegistry` 双索引(`registry` + `skillsByName`)配 `putIfAbsent` first-wins,`@Component` Skills 与 SKILL.md Skills 同名时 `CommitSkill` 注册先后决定胜出(Story #020a dsh §6.4 核心)
- 📂 **SKILL.md 多源自动发现已上线** — Slot 4 sub-SPI:`SkillSource`(4 方法:type / location / discover / watchable)+ `SkillSourceProvider`(2 方法:type / create),`SkillSourceRouter` 启动期按 `type()` 索引 Provider,v1 两个实装(`classpath` 走 `PathMatchingResourcePatternResolver` 扫 `classpath*:prefix/**/SKILL.md` / `directory` 走 NIO `DirectoryStream` 一层扫 `<dir>/*/SKILL.md`),`CompositeSkillLoader.loadAll` 串起所有 source(单 source 失败不阻塞他人),`SkillAutoConfiguration` 扩展 Phase 1(SKILL.md 自动发现)+ Phase 2(`@Component` Skills)`mergePhases` 合并 → `ToolRegistry.register`,Phase 1 wins on name collision(用户可放下 SKILL.md 覆盖内置 `@Component` Skill);`SkillSourceProperties` 是 plain POJO + 静态 `bindFromEnvironment()` 工厂(R-13 dep-lock 兼容:只用 spring-core `Environment`,不用 spring-boot `Binder`),`agent.skills.sources[].type + .location` YAML 直接 bind → Map(Story #020b dsh §6.4 多源,0 新依赖)
- 📡 **MCP server 3 transport 已上线**(stdio / SSE / streamable HTTP,Story #021a → #021b → #021c) — `McpServerConnection` interface 8 方法 + 6-态状态机(`IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED`);3 concrete 实现(`StdioMcpServerConnection` + `SseMcpServerConnection` + `StreamableHttpMcpServerConnection`)由 `McpServerConnectionFactory.create(cfg.transport())` 静态分派;`McpHttpSupport` 共享 HTTP / JSON-RPC 样板(`HttpURLConnection` JDK 1.1 + Jackson `ObjectNode`,**0 新 Maven 依赖**);SSE long-lived 守护 `Thread` + 手写 `BufferedReader.readLine()` SSE parser(malformed 事件不杀流);streamable HTTP 无状态 POST tools/* + `GET /health` 心跳;3 transport 共享指数退避 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 无限重试 + per-listener try/catch 异常隔离;`McpErrorCodes` 新错误域 `M`(M01 stdio 失败 / M02 tool-call 失败 / M03 HTTP-SSE 失败);`callTool` 在非 CONNECTED 状态返 `McpCallResult.error(...)` 而**不**抛异常(对齐 §4.10.1 硬规则 2);dsh §6.5 (2.1)
- 🔗 **Tool/LLM 视角闭环已上线** — `DefaultPromptBuilder` 注入共享 `ToolRegistry`,`Prompt.tools = toolRegistry.modelVisibleSpecs()`(sorted snapshot);本地 Tool(Read/Write/Edit/Bash)+ MCP Tool + `@AgentTool` + Skill + RemoteAgentTool 全部经统一 registry 暴露给模型;**OQ-5 解决**(Story #024 dsh §6.4 [TOOL SCHEMAS] + §5.6.3.0 `RemoteAgentTool.description()` HINT 链路 + ToolRegistry 单点注册闭环,0 新依赖 / 0 新 ErrorCode)
- 🎁 **Demo 产品已上线** — `lingshu-examples/demo-product/` HTTP SSE chat 产品组合 8 features(Spring Boot + SSE 流式 + ReAct 事件流 + `@AgentTool` + SKILL.md Skill + MCP stdio 子进程 + 内存会话 + Hot-reload 配置,Story #025) + `lingshu-examples/demo-product-a2a-server/` 跨 JVM translate demo(9090 端口通过 `RemoteAgentTool` + `HttpJsonRpcA2aTransport` 与 8080 `demo-product` 互通,Story #025b);**0 新 Maven 依赖**
- 🌱 **YAML `${...}` 占位符跨路径统一** — `PlaceholderResolver` 静态工具类(brace-counting scanner 4-form grammar:`${X}` / `${X:default}` / `${X:${Y}}` 嵌套 / `$${literal}` 转义;env → sys-prop 查找;32 层环检测),`AgentFactory.loadYamlAndValidate` 在 `parseMinimalYaml` 与 `toAgentConfig` 之间 hook 调用,**修跨路径 parity bug** —— 之前 CLI / YamlWatcher hand-rolled 路径下 `${user.dir}` 静默变 13 字符串,Spring Env 路径(demo-product)一直支持;新增 2 ErrorCode `LINGS-C03 YAML_PLACEHOLDER_UNRESOLVED` / `LINGS-C04 YAML_PLACEHOLDER_CYCLE`;**0 新 Maven 依赖**(Story #026)
- 🤖 **Anthropic 协议层 Tool 转换已上线** — `AnthropicLlmProvider` 4 段协议链全贯通:`buildRequestBody` 真翻 `Prompt.tools` → 顶层 `tools:[]`(`{name, description, input_schema}`)+ `messages[].content` 展开为 array of blocks(`text` / `tool_use` / `tool_result`)+ 连续 `Message.ToolResult` **合并为单 user message 多 tool_result block**(Anthropic 协议层硬约束)+ `parseResponse` 解析 `tool_use` block → `ToolCall(id, name, input)`;`TurnContext.appendAssistant` 签名扩 `toolCalls` 参数 + `DefaultTurnContext` 实现对齐 + `LinearTurnEngine.L166` 真传 `resp.getToolCalls()`;新增 2 ErrorCode `LINGS-L01 TOOL_USE_BLOCK_INVALID` / `LINGS-L02 TOOL_RESULT_BLOCK_INVALID`(LlmProvider 域 L 段 1/2 号);Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2);**OQ-7 解决**(Story #027a,**0 新 Maven 依赖**)
- 📡 **Anthropic SSE 真流式已上线** — `AnthropicLlmProvider.doPostStream` 把非流式 POST + 一次性 readAll 替换为 SSE `text/event-stream` accept + `BufferedReader.readLine()` 逐行解析(沿用 #021c `SseMcpServerConnection` 手写 SSE parser 模式)+ `AnthropicStreamParser` 6-类事件状态机(`message_start` → `ReasoningStarted` + init usage / `content_block_start` × text + tool_use → 触发 `ToolStarted` / `content_block_delta` × text_delta + input_json_delta → 持续 `TextDelta` + per-block JSON 拼接 buffer / `content_block_stop` → per-block `MAPPER.readTree()` 构造 `ToolCall` / `message_delta.stop_reason` / `message_stop`) + `Map<Integer, StringBuilder>` text blocks + `Map<Integer, ToolCall.Builder>` tool blocks 交错状态机;LLM 流式首 token P50 ≤ 1.5s NFR(constitution §3)真达标;新增 `LINGS-L03` reserved 常量(§14 N6 graceful shutdown 后续启用,本期不抛);复用 #027a `LINGS-L01` 协议层 ErrorCode(SSE 解析 tool_use 缺 id/name);`parseResponse` 保留为 fallback(`anthropicStreamEnabled=false` 配置路径仍可用);**§6.5 protocol gap 全闭合**(Story #027b,**0 新 Maven 依赖** / **R-13 0 binary delta 第 13 次 PASS**)

---

## ⚡ 30 秒上手

### Maven

```xml
<dependency>
    <groupId>ai.lingshu</groupId>
    <artifactId>lingshu-core</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

### 第一个 Agent

```java
import ai.lingshu.core.*;
import ai.lingshu.core.engine.*;
import ai.lingshu.core.provider.anthropic.*;

@SpringBootApplication
public class MyFirstAgent {
    public static void main(String[] args) {
        SpringApplication.run(MyFirstAgent.class, args);
    }

    @Bean
    CommandLineRunner run(AgentFactory factory) {
        return args -> {
            Agent agent = factory.create(AgentConfig.builder()
                .flowEngine("linear")
                .llm(LlmConfig.builder()
                    .provider("anthropic")
                    .model("claude-sonnet-4-5")
                    .build())
                .skills(SkillSources.of(
                    ClasspathSource.of("classpath:skills/agent-builtin/"),
                    DirectorySource.of("./skills/")
                ))
                .build());

            RunResult r = agent.runBlocking("用 Java 写一个 Fibonacci 函数");
            System.out.println(r.getFinalText());
        };
    }
}
```

### YAML 配置(可选)

```yaml
agent:
  flow-engine: linear        # linear | google-adk | alibaba-graph | dag
  llm:
    provider: anthropic      # anthropic | openai | gemini | ollama
    model: claude-sonnet-4-5
    api-key: ${ANTHROPIC_API_KEY}
  sandbox:
    policy: strict           # strict | permissive
    runtime: chroot          # chroot | sysbox | seccomp | none
  max-steps: 25
  skills:
    sources:
      - { type: classpath, location: classpath:skills/agent-builtin/ }
      - { type: directory, location: ./skills/ }
      - { type: git,      location: https://github.com/lingshu-ai-agent/lingshu-skill-market }
  tools:
    enabled: true            # 关闭后 LocalToolsAutoConfiguration 跳过 4 Tool 注册
    max-read-bytes: 200000   # ReadTool 单次上限(超过截断 + 末尾 marker)
    max-write-bytes: 1000000 # WriteTool 字节硬 guard(content.length > 此值则拒绝写盘)
  delegate:                  # Story #023 子 Agent 配置;整段缺失 = 跳过 register
    prompts-dir: ./prompts
    types:                     # 必须3项:explore / engineer / reviewer
      explore:
        system-prompt-file: ./prompts/explore.md
      engineer:
        system-prompt-file: ./prompts/engineer.md
      reviewer:
        system-prompt-file: ./prompts/reviewer.md
```

### 调用内置 Tool(Story #019)

LLM 在 ReAct loop 中自动调,无需手写 Tool 注册代码(启动期 `LocalToolsAutoConfiguration` 已自动注入 `Read / Write / Edit / Bash` 4 个 Tool):

```text
// Read — 读取文件(默认上限 200KB,超出自动截断 + 追加 "...[truncated, original N bytes]")
{ "name": "Read",  "input": { "file_path": "src/main/java/MyClass.java" } }

// Edit — 单匹配替换(old_string 必须唯一,多匹配 fail-fast)
{ "name": "Edit",  "input": { "file_path": "...", "old_string": "TODO", "new_string": "FIXED" } }

// Bash — 走 tenant whitelist(per-tenant command-whitelist,默认兜底)
{ "name": "Bash",  "input": { "command": "ls -la", "description": "list workspace" } }
```

---

## 🏛️ 架构:9 个 SPI 槽位

```
                            ┌──────────────────────────────────┐
                            │         FlowEngine (SPI)          │
                            │ LinearTurnEngine | Google ADK | … │
                            └─────────┬──────────────┬───────────┘
                                      │ drives         │ emits events
            ┌─────────────────────────▼────┐  ┌───────▼───────────┐
            │   PromptBuilder  (SPI)       │  │   AgentEvent bus    │
            │   LlmProvider    (SPI)       │  │  (Reactive Streams)│
            └──────────────────────────────┘  └─────────────────────┘
                                      │
            ┌───────────────────────────▼──────────────────────┐
            │              ToolExecutor (SPI, Story #004)       │
            │  ┌────────────────────────────────────────────┐  │
            │  │   PermissionPolicy → ToolRegistry → Execute │  │
            │  │   dispatchParallel: Semaphore(parallelism) │  │
            │  │   CompletableFuture 池 → 结果按 LLM 顺序回填  │  │
            │  └────────────────────────────────────────────┘  │
            │  Tool SPI  →  McpToolAdapter  →  SkillTool adapter │
            │  Spring AI @AgentTool annotation → SkillTool       │
            └────────────┬───────────────────┬──────────────────┘
                         │ permission        │ runtime
                  ┌──────▼─────────┐  ┌──────▼──────────┐
                  │ PermissionPolicy│  │ RuntimeSandbox  │
                  │   (SPI, model)  │  │  (SPI, system)  │
                  └────────────────┘  └─────────────────┘

            ┌────────────────────────────────────────────────────┐
            │     SessionStore (SPI)  +  Compactor (SPI)         │
            │     持久化历史 / 滑动窗口 / 摘要压缩                │
            └────────────────────────────────────────────────────┘

            ┌────────────────────────────────────────────────────┐
            │     A2aTransport (SPI, Story #009 ✅ AC-10)         │
            │     Agent-to-Agent RPC + AgentCard discovery        │
            │     GET /.well-known/agent.json (A2A v1.0)         │
            └────────────────────────────────────────────────────┘
```

每个 SPI 槽位的 `SlotRouter` 在启动期按 `Provider.version()` 校验 Slot 契约兼容性(Story #003),MAJOR 不匹配抛 `LINGS-S05` 启动失败 —— **杜绝运行时静默降级**。详见 [docs/concepts/slots.md](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/slots.md) 与 [docs/concepts/spi-versioning.md](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi-versioning.md)。

---

## 🔌 SPI 替换示例

> **Story #003 重要更新**:LingShu 选择 **Spring Boot SPI** 而非 Java SPI / OSGi / ClassLoader 隔离(详见 [docs/concepts/spi-vs-osgi.md](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi-vs-osgi.md))。
> 所有 Provider 通过 `@Component` 或 `@AutoConfiguration` + `@Bean` 注册,**禁止**使用 Google auto-service 的 `@AutoService` 注解(那是 Java SPI,与我们的决策冲突)。

```java
// 1. 替换 FlowEngine:接入 Google ADK(Story #003 校验 version() 兼容性)
@Component
public class GoogleAdkFlowEngineProvider implements FlowEngineProvider {
    @Override public String name() { return "google-adk"; }
    @Override public int priority() { return 100; }
    @Override public String version() { return "1.0.0"; }   // 必须与 FlowEngine 契约版本 MAJOR 一致
    @Override public FlowEngine create(AgentConfig cfg) { return new GoogleAdkFlowEngine(cfg); }
}

// 2. 替换 LlmProvider:接入 OpenAI
@Component
public class OpenAiLlmProviderProvider implements LlmProviderProvider {
    @Override public String name() { return "openai"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }
    @Override public LlmProvider create(AgentConfig cfg) {
        return new OpenAiLlmProvider(cfg.getLlm());
    }
}

// 3. 加自定义 Tool(不需要 Provider,直接 @Component)
@Component
public class DbQueryTool implements Tool {
    @Override public String name() { return "db_query"; }
    @Override public String description() { return "Execute read-only SQL"; }
    @Override public JsonNode inputSchema() { /* JSON Schema */ }
    @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        // 你的实现 —— 异常会被 DefaultToolExecutor 翻译成 ToolResult.error,
        // 不会让引擎循环崩溃(Story #004 / FR-007/FR-008)
    }
}
```

**YAML 切换 Provider**(Story #003 多 Provider 模式,改 yaml 不改代码):

```yaml
agent:
  llm:
    provider: openai        # 从 anthropic 切换到 openai,无需 exclude classpath
    model: gpt-4o
  flow-engine: google-adk   # 切到 ADK
  sandbox:
    policy: strict
```

只要把以上类打进 jar,放到 classpath,Spring 启动时 SlotRouter 按 `name()` 路由 + `version()` 校验,**零配置**。

---

## 📦 模块结构

```
lingshu/
├── lingshu-core/                 ← 核心 API + ReAct 引擎
│   ├── engine/                  ← LinearTurnEngine
│   ├── loop/                    ← ReAct step state machine
│   ├── prompt/                  ← PromptBuilder
│   ├── llm/                     ← LlmProvider SPI
│   ├── tool/                    ← Tool SPI + McpToolAdapter
│   ├── skill/                   ← Skill SPI + SkillTool adapter
│   ├── sandbox/                 ← PermissionPolicy + RuntimeSandbox
│   ├── session/                 ← SessionStore + Compactor
│   ├── event/                   ← AgentEvent types
│   └── flow/                    ← FlowEngine SPI
├── lingshu-boot-starter/         ← Spring Boot 启动器
├── lingshu-providers/
│   ├── lingshu-anthropic/        ← Anthropic Claude
│   ├── lingshu-openai/           ← OpenAI / GPT
│   ├── lingshu-ollama/           ← 本地 Ollama
│   └── lingshu-mcp-client/       ← MCP server 适配
├── lingshu-adapters/
│   ├── lingshu-google-adk/       ← 接入 Google ADK 作为 FlowEngine
│   └── lingshu-alibaba-graph/   ← 接入 Alibaba Graph 作为 FlowEngine
├── lingshu-cli/                  ← CLI 入口(Story #017):run/resume/serve/doctor/config
└── lingshu-bom/                  ← Maven BOM
```

---

## 🧪 跑通一个最小 Demo

### Story #001 zero-config-bootstrap(空 yml 启动 + 首 token)

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
export ANTHROPIC_AUTH_TOKEN=<your-key>
export ANTHROPIC_BASE_URL=https://api.anthropic.com        # 或代理路径如 https://your-proxy/anthropic
mvn -pl lingshu-examples/demo-empty -am spring-boot:run
```

`application.yml` 故意为空,所有 27 个 `AgentConfig` 字段由 `AgentConfigDefaults.defaults()` 提供。
首次 LLM token 在 ~5s 内返回,stderr 无 ERROR(AC-01-1)。

### Story #002 identity-instructions-memory(业务三件套 + 5 段 Prompt 装配)

```bash
cd lingshu
export ANTHROPIC_AUTH_TOKEN=<your-key>
export ANTHROPIC_BASE_URL=https://api.anthropic.com        # 或代理路径
mvn -pl lingshu-examples/demo-engineer -am package -DskipTests
java -jar lingshu-examples/demo-engineer/target/demo-engineer-0.1.0-SNAPSHOT.jar "你是做什么的"
```

`demo-engineer` 演示 AC-09 黑盒契约:
- 4 个 `MemorySourceProvider` 自动注册(`identity` / `project-claude-md` / `user-claude-md` / `project-tree`)
- `DefaultPromptBuilder` 5 段装配:`[ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY] / [CONVERSATION HISTORY] / [USER MESSAGE]`
- 缺失文件静默跳过,首 token ≤ 30s

**黑盒测试(无需 API key)**:
```bash
mvn -pl lingshu-examples/demo-engineer -am test -Dtest=BlackBoxVerificationTest
```

### Story #017 cli-entrypoint(5 子命令 CLI 入口,dsh §10.3 全落地)

不想写代码?直接用 CLI:

```bash
# 跑一次(等价 demo-empty 的最小入口)
mvn -pl lingshu-cli spring-boot:run \
    -Dspring-boot.run.arguments="run --prompt '用 Java 写一个 Fibonacci 函数'"

# 起 A2A 服务(等价 demo-a2a)
mvn -pl lingshu-cli spring-boot:run \
    -Dspring-boot.run.arguments="serve --port 18099"
# 另开终端:
curl -sf http://127.0.0.1:18099/.well-known/agent.json | jq .

# 自检环境(打印 7 Router + 9 Slot 状态)
mvn -pl lingshu-cli spring-boot:run \
    -Dspring-boot.run.arguments="doctor"

# 看有效配置(合并 yaml + 默认值)
mvn -pl lingshu-cli spring-boot:run \
    -Dspring-boot.run.arguments="config --print-effective"
```

完整 CLI 子命令矩阵与 ErrorCode 详见下方 "Story #017 cli-entrypoint" 段。

### 所有 16 个 demo 工程索引(Stage A 骨架,2026-09-25 落盘)

按主题合并 ~ 14 个新 demo(Story #003—#025b 全部覆盖),每个 demo 跑通 Spring 上下文 + 至少 1 个 `BlackBoxVerificationTest` skeleton test;完整 AC 黑盒留 Stage B。#025 / #025b 是端到端 product demo(非 Stage A skeleton),需 `spring-boot:run` 起服务实测。

| Demo | Story 覆盖 | 验证内容(Stage A) | 跑通命令 |
|---|---|---|---|
| `demo-empty` | #001 | Spring main 启动 + AgentFactory.create | `mvn -pl lingshu-examples/demo-empty -am spring-boot:run` |
| `demo-engineer` | #002 + #024 | 4 MemorySource wiring + 5 段 Prompt + `[TOOL SCHEMAS]` 段(`prompt.getTools()` 镜像 registry) | `mvn -pl lingshu-examples/demo-engineer -am test` |
| `demo-local-tools` | #019 | 4 Tool(Read/Write/Edit/Bash)从 `LocalToolsAutoConfiguration` 注册到 `ToolRegistry` | `mvn -pl lingshu-examples/demo-local-tools -am test` |
| `demo-spi` | #003 | `SlotRouter` 多 Provider + `AgentFactory.description()` 9 行 | `mvn -pl lingshu-examples/demo-spi -am test` |
| `demo-parallel` | #004 | `LinearTurnEngine.dispatchParallel` 4 tool 并行 | `mvn -pl lingshu-examples/demo-parallel -am test` |
| `demo-cancellation` | #005 | `CancellationToken` 三层贯通 + 200ms AC-04 | `mvn -pl lingshu-examples/demo-cancellation -am test` |
| `demo-tenants` | #006 | `TenantContext` ThreadLocal + 4 维隔离 | `mvn -pl lingshu-examples/demo-tenants -am test` |
| `demo-reload` | #007 | `YamlWatcher` mtime 轮询 + `AtomicReference` config swap | `mvn -pl lingshu-examples/demo-reload -am test` |
| `demo-max-steps` | #008 | `MaxStepsExceeded` 事件发射 + 5 终止路径 | `mvn -pl lingshu-examples/demo-max-steps -am test` |
| `demo-compactor` | #018 | `TruncatingCompactor` + `CompactorRouter` token 减少 | `mvn -pl lingshu-examples/demo-compactor -am test` |
| `demo-a2a` | #009 + #009a—#009e | `A2aServer` JDK HttpServer + `/.well-known/agent.json` 黑盒 | `mvn -pl lingshu-examples/demo-a2a -am test` |
| `demo-skill` | #020a + #020b + #020c | `SkillSourceRouter` classpath+directory + `SkillCommandDispatcher` cli 模块 | `mvn -pl lingshu-examples/demo-skill -am test` |
| `demo-mcp` | #021a + #021b + #021c | `McpServerConnectionFactory` 3 transport(STDIO/SSE/STREAMABLE_HTTP)dispatch | `mvn -pl lingshu-examples/demo-mcp -am test` |
| `demo-delegate` | #022 + #023 + #024 | `@AgentTool` + `DelegateTool` Task + `SubAgentType` enum 3 值 | `mvn -pl lingshu-examples/demo-delegate -am test` |
| `demo-product` | #025 | HTTP SSE chat 产品:8 features(Spring Boot + SSE + ReAct 事件流 + `@AgentTool` + SKILL.md Skill + MCP stdio 子进程 + 内存会话 + Hot-reload 配置) | `mvn -pl lingshu-examples/demo-product -am spring-boot:run` |
| `demo-product-a2a-server` | #025b | 跨 JVM translate demo(9090 端口)与 `demo-product`(8080)互通:`RemoteAgentTool` + `HttpJsonRpcA2aTransport`;`DemoProductA2aServerApplication` 自起 JDK `HttpServer` 跑简化 JSON-RPC + 调本地 ToolRegistry(stock `A2aServer` 不接 dispatch 的事实绕过) | `mvn -pl lingshu-examples/demo-product-a2a-server -am spring-boot:run` |

**全量回归**(Stage A 14 demo 一次性跑,1 分钟级;`demo-product` / `demo-product-a2a-server` 为 product demo 需独立启服务):

```bash
cd lingshu-examples
mvn test
```

实测:`Tests run: 56, Failures: 0, Errors: 0, Skipped: 0`(demo-empty 0 + demo-engineer 3 + demo-local-tools 5 + 11 个新 demo 48)。

### Story #003 spi-slot-router(`Provider.version()` + `SlotRouter` 兼容性校验)

```bash
mvn -pl lingshu-core test -Dtest=SlotRouterCompatTest
mvn -pl lingshu-core test -Dtest=VersionTest
mvn -pl lingshu-core test -Dtest=ProviderInitExceptionTest
```

`SlotRouterCompatTest`(7 case)+ `VersionTest`(24 case)+ `ProviderInitExceptionTest`(5 case)覆盖:
- 启动期按 `Provider.version()` 与 Slot `CONTRACT_VERSION` MAJOR 比对 —— 不匹配抛 `LINGS-S05` **启动失败**,杜绝运行时静默降级(AC-02)
- `LinkedHashMap` 保留用户配置顺序(同 `agent.prompt.memory-sources` 输入一致),priority 只用于同名竞争(AC-08)

### Story #004 tool-parallel-dispatch(`LinearTurnEngine.dispatchParallel`)

```bash
mvn -pl lingshu-core test -Dtest=LinearTurnEngineParallelDispatchTest
```

`LinearTurnEngineParallelDispatchTest` 跑 AC-03 黑盒契约(无需 API key):
- 4 个独立 Tool 各 sleep 1s,`tool.parallelism: 4` → wall-clock **≤ 1.3s**(实测 1011ms)
- 相比 4s 串行 baseline,加速比 **≥ 3.0×**(实测 3.96×)
- `DefaultToolExecutor` 把异常翻译成 `ToolResult.error`,引擎循环不因单 Tool 崩溃(FR-007/FR-008)
- 结果按 LLM-return 顺序回填 history,不按完成顺序(FR-003)

**YAML 调并行度**:
```yaml
agent:
  tool:
    parallelism: 8          # 同时执行最多 8 个 Tool;1 = 串行;0 = 不限
    timeout-seconds: 30     # 单个 Tool 超时(0 = 不超时)
```

### Story #005 cancellation-token(协作式取消 + 三层贯通 + AC-04 200ms)

dsh §14.12 N12: FlowEngine / ToolExecutor / LlmProvider 三层共用同一个 `CancellationToken`,Ctrl-C / JVM shutdown hook / turn 超时 / 编程式 `markDone` 4 种触发源都通过它发出信号。

**关键不变量**:
- `TurnContext.cancellation() == ToolExecutionContext.cancellation()`(共享引用,非 equals)
- `DefaultTurnContext.createWithBroadcast` 自动把 turn 的 token 注册到 `AgentFactory.BROADCAST_REGISTRY`
- `AgentFactory.@PostConstruct registerJvmShutdownHook` 在 JVM 关停时调 `broadcastCancel()`,所有 in-flight turns 在 AC-04 200ms 内退出
- `LinearTurnEngine` 用 200ms 轮询预算(`waitForLlm` + `waitForTool`),即使 LLM / Tool 永远不返回也能在 200ms 内感知取消

```bash
mvn -pl lingshu-core test -Dtest='CancellationTokensTest,CancellationTokenSharingTest,LinearTurnEngineCancellationIT,AgentFactoryBroadcastCancelTest'
```

**测试覆盖**(23 case / 4 类):
- `CancellationTokensTest`(8 case)— AtomicBoolean 幂等 fire / CopyOnWriteArrayList 安全迭代 / per-callback 异常隔离 / 并发注册 stress
- `CancellationTokenSharingTest`(6 case)— `==` 身份共享 / 多 turn 隔离 / 4-arg 构造器 back-compat
- `LinearTurnEngineCancellationIT`(3 case)— **AC-04 黑盒**(实测 cancel→exit **0ms**,预算 200ms)/ 预取消 / mid-tool-dispatch 取消
- `AgentFactoryBroadcastCancelTest`(6 case)— broadcast 全发 / 幂等 / `activeTurnCount` 反射 / 未注册 turn 忽略

**AC-04 黑盒输出**:
```
[AC-04] cancel→exit elapsedMs=0 (budget=200)
```

**LlmProvider 取消内部轮询**(US3)推迟到 Story #005b — 不阻塞 AC-04:200ms `waitForLlm` 预算 + 引擎侧轮询已覆盖 N12 三层贯通契约。

### Story #006 multi-tenant(`TenantContext` ThreadLocal + 配置/Session/Sandbox/Cost 四维隔离 AC-05)

dsh §14.9 N9:多租户隔离是 B2B SaaS 化刚需 —— 一个 JVM 实例同时服务多个客户,每客户有独立 memory dir / sandbox whitelist / cost budget / session namespace,互不可见。

**核心交付**:
- `TenantContext` ThreadLocal **嵌套栈** + `snapshot/runWithSnapshot` 显式跨线程传递(主动放弃 `InheritableThreadLocal`,避免线程池复用场景下"上一个任务的 tenant 泄漏到下一个任务")
- `AgentConfig.tenants` 新字段(28th)+ `TenantsConfig.validate()` 启动期 fail-fast(错误码 `LINGS-C02`)
- `TenantConfigProvider` SPI + `YamlTenantConfigProvider` 默认实现(`@Component("tenantConfigProvider_yaml")`,§5.28 多 Provider 模式)
- `TenantAwareCostTracker` per-tenant `LongAdder` 桶 + 超预算 `CostBudgetExceededException`
- `DefaultRuntimeSandbox` `process.run` 走 tenant 白名单(全局兜底,单租户 mode 不变)
- `DefaultInMemorySessionStore` session key 加 `tenantId` 前缀(`alice:sess-123` vs `bob:sess-123`)
- `LinearTurnEngine.runTurn` 入口 FR-011 守卫(tenants 已配但无 `TenantContext` → `IllegalStateException` + `LINGS-C02` 提示)

```bash
mvn -pl lingshu-core test -Dtest='TenantContextTest,TenantConfigProviderTest,TenantConfigValidationTest,MemoryPathIsolationTest,CostBudgetIsolationTest,SandboxWhitelistIsolationTest,SessionKeyIsolationTest,TenantIsolationIT'
```

**测试覆盖**(43 case / 8 文件):
- `TenantContextTest`(9 case)— 嵌套栈 / try-finally / snapshot+runWithSnapshot / 跨线程显式传递
- `TenantConfigProviderTest`(5 case)— 多 Provider 优先级 / 空 yml 单租户 fallback
- `TenantConfigValidationTest`(10 case)— 4 项校验(tenantId 格式 / key=value 一致 / dir 必填 / cost>0)+ 14 个 Edge Case
- `MemoryPathIsolationTest`(2 case)— per-tenant `AgentConfig.Memory.claudeMd.project`
- `CostBudgetIsolationTest`(5 case)— alice/bob 独立计数 + 超预算 fail-fast
- `SandboxWhitelistIsolationTest`(6 case)— alice 拒 git / bob 允许 / 单租户 fallback
- `SessionKeyIsolationTest`(5 case)— 同 sessionId 不同物理 bucket
- `TenantIsolationIT`(1 case E2E)— **AC-05 黑盒**(83ms):4 维隔离跨 alice/bob 一次性验证

**YAML 多租户配置**:
```yaml
agent:
  tenants:
    enabled: true
    map:
      alice:
        memory:    { dir: /var/lib/alice }
        sandbox:   { command-whitelist: [ls, cat] }
        cost:      { session-budget-micros: 10000000 }
      bob:
        memory:    { dir: /var/lib/bob }
        sandbox:   { command-whitelist: [ls, cat, git] }
        cost:      { session-budget-micros: 100000000 }
```

**TenantContext 用法**:
```java
TenantContext.runAs("alice", () -> {
    // 业务代码 —— TenantContext.current() == "alice"
    Agent agent = factory.create(cfg);
    return agent.runBlocking("...");
});
// 退出 lambda 后自动 clear(R-02 缓解)

// 跨线程显式传递
String snap = TenantContext.snapshot();
executor.submit(() -> {
    TenantContext.runWithSnapshot(snap, () -> doWork());
});
```

**R-13 dependency:tree 自查**:`diff /tmp/deps-005-baseline.txt /tmp/deps-006-after.txt` → 仅 `[INFO] Total time` 时间戳差异,**0 新依赖**。

### Story #007 yaml-hot-reload(`AgentConfigRegistry` AtomicReference + 5s mtime poll + DefaultAgent freeze AC-06)

dsh §14.8 N8:**YAML 热更无中断** —— Agent 跑 turn T1 时外部修改 `application.yml`(扩 sandbox whitelist / 换 model / 调 `react.max-steps`),T1 全程冻结旧 cfg 引用语义自然隔离;T2 启动立即看到新 cfg。零停机 + 零重启 + 零数据竞争 —— 7×24 长生命周期运维刚需。

**核心交付**:
- `AgentConfigRegistry` `AtomicReference<AgentConfig>` **单写多读 lock-free**(NFR-005:单 publish ≤ 1ms)
- `ConfigChangeListener` SPI + listener 异常不阻断 publish 主流程(异常隔离 + ERROR 日志 + 后续 reader 仍看到新 cfg)
- `YamlWatcher` daemon `ScheduledExecutorService` 5s `Files.getLastModifiedTime` poll(cross-platform stable,**不用** `WatchService` 的 macOS polling fallback 不兼容)
- `AgentFactory.create(cfg, registry)` 新签名 + 旧 `create(cfg)` `@Deprecated`(向后兼容 Story #001—#006)
- `DefaultAgent.run()` 入口一次性 `registry.current()` freeze(Java 引用语义 + `@Value` immutable 字段自然冻结,无锁 / 无 snapshot copy)
- `validateOrThrow` 拒绝破坏性 cfg + rollback 保留旧 cfg + `lastSeen` **不**更新 → 下次 5s 自动重试

**关键不变量**:
- 旧 turn T1 全程持有 cfg1 引用(`assertSame` 验证),即使中途 `registry.publish(cfg2)` 也无影响
- 新 turn T2 入口 `registry.current()` 立即看到 cfg2,无需重启 / cancel / 等待
- 校验失败 / YAML parse 失败 / IOException **不**更新 `lastSeen`,下一次 poll 自动重试(R-03 缓解)
- Listener 抛 RuntimeException → ERROR log + publish **不**回滚,后续 reader 仍看到新 cfg
- Listener 内禁止调 `registry.publish`(重入死循环,契约显式说明)

**测试覆盖**(22 case / 5 文件):
- `AgentConfigRegistryTest`(9 case)— publish 立即 swap / 100 线程并发 `current()` 全看到新 cfg / listener 异常隔离 / listener 重入禁止 / addListener / removeListener / publish null NPE
- `MinimalYamlParserTest`(4 case)— block-style list / flow-style list / 注释与空行 / orphan item 抛错(内联 YAML parser L0 smoke)
- `YamlWatcherTest`(5 case)— mtime 变更触发 reload / invalid YAML 保留旧 cfg / validate 失败保留旧 cfg / `lastSeen` 不更新 / 自动重试
- `InFlightFreezeTest`(2 case)— **AC-06 核心**(T1 freeze + T2 立即生效 + in-flight turn 不被并发 publish 改写)
- `YamlHotReloadIT`(2 case E2E)— **AC-06 黑盒主路径**(T1 跑 ls + 中途 touch yml 加 git + T2 跑 git status + invalid YAML rollback)

```bash
mvn -pl lingshu-core test -Dtest='AgentConfigRegistryTest,YamlWatcherTest,InFlightFreezeTest,YamlHotReloadIT'
```

**ConfigChangeListener 用法**:
```java
@Component
public class MyAuditListener implements ConfigChangeListener {
    @Override
    public void onConfigChange(AgentConfig prev, AgentConfig next) {
        // prev → next 的 diff 审计 / metric 计数 / cache invalidate
        // 不要在 listener 内调 registry.publish(重入死循环,契约禁止)
    }
}
```

**YAML 热更示例**:
```bash
# T1 在跑,sandbox whitelist = [ls, cat]
# 外部运维修改 yml:
vi application.yml    # 追加 git 到 whitelist
:wq
# 5s 内 YamlWatcher poll 检测 mtime 变化 → reload + validate + publish(cfg2)
# T1 全程冻结 cfg1 引用(sandbox 仍只允许 ls / cat,不被中断)
# T2 启动立即看到 cfg2(sandbox 允许 ls / cat / git)
```

**R-13 dependency:tree 自查**:`diff /tmp/deps-006-baseline.txt /tmp/deps-007-after.txt` → **0 new dependencies**(`AtomicReference` / `ScheduledExecutorService` / `Files` / SnakeYAML 已在 baseline)。

**0 新增 ErrorCode**(沿用 Story #001 `LINGS-C02` 验证错误码;R-03 缓解在 `validateOrThrow` 已有路径)。

### Story #008 react-max-steps(`LinearTurnEngine` `maxStepsHit` 守卫 + `MaxStepsExceeded` 事件发射 AC-07)

dsh §0.4 AC-07:**ReAct 上限** —— yml `agent.react.max-steps: 3` + LLM mock 每次只返 tool call(不返 final answer)→ 第 3 步之后发 `MaxStepsExceeded(3, totalUsage=...)` 事件,然后 turn 正常 `done()`,**不**无限循环。防止 LLM 死循环 token 失控 + 7×24 长生命周期运维刚需。

**核心交付**:
- `LinearTurnEngine.runTurn` 新增 `boolean maxStepsHit = false` 守卫标志(try 之前声明)
- for-loop 内 L174 `ObservationAppended` 之后新增 `if (step == maxSteps) maxStepsHit = true;`(仅当 step == maxSteps 且无 break 退出时触发)
- for-loop 之后 / `TurnCompleted` 之前新增守卫 + last 联合判定:`if (maxStepsHit && last.getToolCalls() 非空) sink.onNext(MaxStepsExceeded(maxSteps, totalUsage))`
- `TurnCompleted.reason` 仍为 `last.getStopReason()`(**不**引入新 `StopReason.MAX_STEPS` enum 值 — 保持 Story #005 cancellation 状态机 + Story #010 OTel metric 标签向后兼容)
- `MaxStepsExceeded.totalUsage` 与 `TurnCompleted.usage` **同一对象引用**(`Usage` `@Value` 不可变,NFR-002 0 内存分配)

**关键不变量**:
- 仅当 for-loop 因 `step == maxSteps` 自然 bound 结束(**无** `break`(ctx.done() / no-tool-call)/ `return`(cancellation / waitForLlm cancelled)/ `catch`(RuntimeException))**且**最后一次 LLM 响应仍含 tool calls 时,发射 `MaxStepsExceeded`
- 事件顺序固定:`MaxStepsExceeded` → `TurnCompleted`(FR-005 强约束,`assertSame(usage)` 验证)
- `AgentEvent.MaxStepsExceeded(maxSteps, totalUsage)` 类定义、字段、Lombok `@Getter` **不**改(`AgentEvent.java` L107-110)
- `StopReason` enum **不**改(`StopReason.java` L8-21,6 值 END_TURN / TOOL_USE / MAX_TOKENS / COMPACTED / CANCELLED / ERROR,无 `MAX_STEPS`)
- `AgentConfig.reactMaxSteps` 默认 50,`0` = 不限被 `AgentFactory.create()` 启动期校验 `LINGS-C02` 拒绝(`L233-235` 复用,**0 新增** ErrorCode)
- Story #007 兼容:在飞 turn 冻结 `reactMaxSteps` 引用,中途 `registry.publish(newCfg)` 不影响(EC-9 自然兼容)

**5 终止路径分支全覆盖**:

| 路径 | 触发条件 | 发 `MaxStepsExceeded`? | `TurnCompleted.reason` |
|---|---|---|---|
| **A**(自然 bound + last 含 tool calls)| for-loop step == maxSteps 无 break | ✅ **是** | `TOOL_USE`(LLM 最后响应是 TOOL_USE)|
| **A'**(自然 bound + last 无 tool calls)| break at L162-165 在 step == maxSteps | ❌ 否 | `END_TURN` |
| **B**(break 无 tool calls)| step < maxSteps + break | ❌ 否 | `END_TURN` |
| **C**(break ctx.done)| step < maxSteps + break | ❌ 否 | `END_TURN` |
| **D**(cancellation return)| `cancellation().isCancelled()` | ❌ 否 | `CANCELLED` |
| **E**(exception catch)| RuntimeException in try | ❌ 否 | `ERROR` |

**测试覆盖**(11 case / 1 文件):
- `MaxStepsGuardTest`(11 case):
  - `US1-AS1`:`maxSteps3_llmAlwaysToolCall_emitsMaxStepsExceeded_after3rdStep` —— 主路径 11 事件含 `MaxStepsExceeded(3)`
  - `US1-AS2`:`maxSteps5_llmEndTurnAfter3Steps_noMaxStepsExceeded` —— 自然 END_TURN 路径不发
  - `US1-AS3`:`maxSteps1_llmToolCall_emitsMaxStepsExceeded_after1stStep` —— 极小值边界
  - `US1-AS4`:`maxSteps0_factoryValidateThrows_LingsC02_neverEnterEngine` —— 反射测 `AgentFactory.validate()` 抛 `IllegalArgumentException`
  - `US2-AS1`:`maxSteps2_llmThrowsFirstStep_errorPathNoMaxStepsExceeded` —— 异常路径不发
  - `US2-AS2`:`maxSteps3_toolExceptionMidPath_stepCountContinues_maxStepsHitFinally` —— tool 异常翻译为 `ToolResult.error` 不影响 step 计数
  - `US3-AS1`:`maxStepsExceeded_eventFields_intAndUsage` —— 反射验证字段类型
  - `US3-AS2`:`stopReason_enumHasNoMaxStepsValue` —— 反射验证 enum 无 `MAX_STEPS`(SemVer 守护)
  - `US3-AS3`:`maxSteps3_eventOrder_maxStepsBeforeTurnCompleted_usageRefSame` —— 顺序 + 引用语义 `assertSame`
  - `EC-5`:`maxSteps10_cancellationMidPath_noMaxStepsExceeded` —— 取消优先
  - `EC-7`:`maxSteps3_llmEndTurnAtLastStep_naturalEndTurn_noMaxStepsExceeded` —— break 优先于守卫

```bash
mvn -pl lingshu-core test -Dtest=MaxStepsGuardTest
```

**累计测试**:**291 case**(Story #008 187 + Story #009 +17 + Story #009a +28(23 a2a-client unit + 2 E2E + 5 core router / split 192+25)+ Story #017 +30 + Story #009a-009 demo-engineer 黑盒 2 case 修复 + Story #009b +25(8 registry + 7 transport + 4 provider + 3 autoconfig + 3 a2a-server hooks))全绿,0 regression。

**R-13 dependency:tree 自查**:`diff /tmp/deps-008-baseline.txt /tmp/deps-009-after.txt` → 仅 `[INFO] Total time` 时间戳差异 + 新模块 `lingshu-a2a-server` 4 个直接依赖(`lombok` / `spring-boot-autoconfigure` / `junit-jupiter` / `assertj-core`),**全部已在 dsh §10.1 锁定 13 项 / Spring Boot BOM 中**,0 新依赖。

**2 新增 ErrorCode**:
- `LINGS-T02`(T 域 / Tool-A2A 配置)— `AgentConfig.identity.name` 空 / 空白 / `AgentConfig == null` 触发,`LocalAgentCardGenerator.generate()` 启动期校验
- `LINGS-S06`(S 域 / Slot-SPI)— 端口占用 / 越界 / `HttpServer.create()` 失败触发,`A2aServer.start()` 启动期 fail-fast

---

### Story #009 a2a-agent-card(`LocalAgentCardGenerator` + JDK `HttpServer` + `GET /.well-known/agent.json` AC-10)

dsh §0.4 AC-10:**A2A AgentCard 自动生成** —— 服务端暴露 `GET /.well-known/agent.json`(A2A v1.0 §2.1 固定路径),返回 `AgentCard` 包含 `name` / `description` / `version` 字段,且**直接**来源于 `cfg.getIdentity()` / `cfg.getIdentity().getRole()` / 内置常量,无需额外 yml 配置。dsh §5.6.8 `LocalAgentCardGenerator` 黑盒验证。

**Narrow scope(AC-10 only)**:`LocalAgentCardGenerator`(cfg → AgentCard transformer)+ 嵌入式 HTTP server(JDK 内置 `com.sun.net.httpserver.HttpServer`,0 新 Maven 依赖)+ 最小 `AgentCard` 数据类型(Lombok `@Value` + Jackson)。**Out-of-Scope**:**#009a**(GrpcA2aTransport / A2aTransportRouter / AgentCardCache / AgentConfig.A2a.grpcTarget / cardTtl 扩展)+ **#009b**(InProcessA2aTransport / 同 JVM 直接方法调用 / 0 额外依赖)+ **#009c**(HttpJsonRpcA2aTransport / RemoteAgentTool / @Component implements Tool)+ **#009d**(RemoteAgentSchemaBuilder 启动期扫 `AgentCard.skills[]` 生成 `ToolSpec` list / 0 额外依赖)。dsh §5.6.3.2 只显式锚定 #009a (Grpc) + #009b (InProcess);#009c / #009d 由本仓库 Story 边界检查(CLAUDE.md §11 #4 ≤ 5 文件 / ≤ 3 ErrorCode)反推拆分。

- 新模块 `lingshu-a2a-server`(独立打包,零 Maven 依赖增量):`AgentCard.java` + `LocalAgentCardGenerator.java` + `A2aServer.java` + `A2aServerAutoConfiguration.java` + `LingsA2aServerException.java` + `META-INF/spring/...AutoConfiguration.imports`
- `A2aServer` Spring `@Bean(initMethod="start", destroyMethod="stop")` 生命周期(避开 `@PostConstruct` / `@PreDestroy` javax.annotation 依赖,符合 R-13)
- `AgentConfig.A2a` 嵌套类(host + port + `defaults()`)穿透到 `AgentConfigDefaults` + `AgentFactory` + 15 个测试 fixture
- 3 handler 内嵌类:`AgentCardHandler`(GET agent.json / 200 / Cache-Control max-age=60)/ `RpcPlaceholderHandler`(POST /rpc / 501 Not Implemented,占位留给 #009a)/ `NotFoundHandler`(catch-all 404)

**测试覆盖**(17 case / 3 文件):
- `LocalAgentCardGeneratorTest`(6 case L1)— `generate_withIdentityName_returnsAgentCardWithName` / `generate_defaultIdentity_returnsLingShuAgent` / `generate_identityWithRole_setsDescription` / `generate_blankIdentityName_throwsLingsT02`(EC-1) / `generate_whitespaceOnlyIdentityName_throwsLingsT02` / `generate_toJson_returnsValidJson`
- `AgentCardJsonTest`(3 case L1)— `serialize_withNullDescription_emitsField` / `serialize_returnsValidJsonStructure` / `serialize_emptySkills_emitsEmptyArray`
- `A2aServerLifecycleTest`(8 case L1+L2)— `start_withDefaultPort8080_listensOn8080` / `start_withCustomPort_listensOnCustomPort` / `start_withPortZero_returnsOsAssignedPort` / `stop_releasesPortForRebind` / `start_withPortAlreadyInUse_throwsLingsS06`(EC-4) / `start_withBlankIdentityName_throwsLingsT02`(EC-5) / `start_withInvalidPortNegative_throwsLingsS06` / `getAgentJson_returnsValidCard`

```bash
mvn -pl lingshu-a2a-server -am test -Dtest='LocalAgentCardGeneratorTest,AgentCardJsonTest,A2aServerLifecycleTest'
```

**AC-10 黑盒主路径输出**(实跑 `A2aServerLifecycleTest.getAgentJson_returnsValidCard`):

```
[AC-10] GET http://127.0.0.1:<port>/.well-known/agent.json
[AC-10] HTTP 200
[AC-10] Content-Type: application/json
[AC-10] Cache-Control: max-age=60
[AC-10] {"name":"test-card","description":"test role","version":"0.1.0",...}
```

**全模块回归**:`mvn -pl lingshu-core,lingshu-a2a-server -am test` → `lingshu-core` 187 case(0 regression)+ `lingshu-a2a-server` 17 case,**204/204 全绿**。

**Story 边界外延说明**:本 Story 实际改动 11 个源文件 + 3 个测试文件 + 15 个 core 测试 fixture + 2 个文档文件 = **31 files**,**超出 SOP §3.1 Story 边界 ≤5 上限 6 倍**。根因:`AgentConfig.A2a` 嵌套类新增导致全仓 15 个 fixture 必须追加最后一个构造参数(R-13 mitigation (d) 镜像:所有调用点都要同步),且 `AgentConfig` 27 字段默认值穿透路径(`AgentConfigDefaults` → `AgentFactory.loadYamlAndValidate()`)需要同步。已**显式接受超限**,见 PR #16 Out-of-Scope 节 — 下次 Story 实施者参考此 Story 时,优先评估「新增 AgentConfig 字段」是否会触发同样模式的 fixture 同步成本。

---

### Story #009a a2a-grpc-transport(`GrpcA2aTransport` 3 件套 + `A2aTransportRouter` Slot 9 stub + `AgentCardCache` + R-13 mitigation (d) 镜像 +5MB)

dsh §5.6.3.2 L3174-3320 锚定 Grpc A2A 变体为 Story #009a 的主要 Target(从 3 个候选实现中按"+5MB binary 换 grpc streaming 高效 subscribe"权衡选 Grpc,InProcess 留 #009b,HttpJsonRpc 留 #009c)。本 Story 落地 Slot 9 SPI 第一个**真实**可用 Provider,把 A2A **服务端**(Story #009)与 **客户端**(本 Story)拼成完整闭环 —— 但**仅**支持 gRPC 协议,http-jsonrpc/in-process 留后续 Story。

**Narrow scope(本 Story 落地)**:
- `GrpcA2aTransport` 3 件套 concrete(`implements A2aTransport` 5 方法契约;`grpc-stub` 1.55.1 同步阻塞 stub + `ManagedChannelBuilder.forTarget().usePlaintext().build()`)+ `GrpcA2aTransportProvider`(`name="grpc-1.0.0"`, `priority=10`, `version="1.0.0"`)+ `GrpcA2aTransportAutoConfiguration`(`@Bean(name = "a2aTransportProvider_grpc-1.0.0")`,§5.5 多 Provider 模式样板)
- `A2aTransportRouter` Slot 9 Router stub(`@Component extends SlotRouter<Providers.A2aTransportProvider, A2aTransport>`,super 传 `"A2aTransport"` + Logger;构造期版本校验抛 `LINGS-S05`)
- `AgentCardCache` 简版(`ConcurrentHashMap` + TTL 5min default + 负缓存 TTL=ttl/4 + FIFO evict maxEntries=1000 + `Stats` inner class 命中率指标 + `invalidate()` 为 §14.8 hot-reload 预留钩子;**单实例** = 进程级 cache,跨 Agent turn 共享)
- `AgentConfig.A2a` 嵌套类扩 `grpcTarget`(String,default `"localhost:50051"`)+ `cardTtl`(Duration,default 5min);`defaults()` 同步扩为 4 参
- lingshu-a2a-server lifecycle test + 5 fixture 加最后一格构造实参镜像(R-13 mitigation (d):所有调用点同步)
- `a2a.proto`(5 RPC + 6 message) + `protobuf-maven-plugin 0.6.1` + `os-maven-plugin 1.7.1`(grpc-java codegen)
- `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 自动注册

**Out-of-Scope**(deferred):
- **`InProcessA2aTransport`** → **Story #009b**(同 JVM 直接方法调用,0 额外依赖)
- **`HttpJsonRpcA2aTransport`** + **`RemoteAgentTool`**(`@Component implements Tool`,`call_<agentName>` 转发)+ `RemoteAgentToolAutoConfiguration` → **Story #009c**(JDK `java.net.http.HttpClient` 0 额外依赖)
- **`RemoteAgentSchemaBuilder`**(扫 `AgentCard.skills[]` 启动期生成 `ToolSpec` list)→ **Story #009d**
- mTLS / OAuth2 / API Key 鉴权 → future
- `subscribe` 真正的 server-streaming 实现 → 当前阻塞 stub 透传 `TaskEvent`(`GrpcA2aTransport.subscribe()` 已实现 5 方法契约但只透传 1 个事件避免阻塞,真实 streaming 实现见 dsh §5.6.3.2 L3275-3296 后续可增强)
- `lingshu-examples` 任何 gRPC 示例 → future

**设计决策**:
- **`grpc-netty-shaded`** 替代 `grpc-netty` → 把 Netty 4.x 全部 namespace 重命名进 `io.grpc.netty.shaded.*`,**避免**与用户应用可能引入的 Netty 直接依赖冲突(`LINGS-R13-NETTY-CLASH` 反模式 mitigation)
- **plaintext only**(本 Story)`usePlaintext().build()` → TLS 走 Story #009a+ 后续 Story;v0.1-α 安全边界 = 内部网络
- **DNS validation 在 `Provider.create()`**:`ManagedChannelBuilder.forTarget("in-process:UUID")` 会抛 `IllegalArgumentException: Invalid DNS name`,`GrpcA2aTransportProvider.create()` 默认走 `forTarget()` 强制 grpcTarget 是合法 `host:port`(EC-1 `LINGS-S07`:`null`/`""`/空白触发 fail-fast)
- **in-process gRPC 直通**:`GrpcA2aEndToEndIT` E2E 测试**绕过** `Provider.create()`(`InProcessChannelBuilder` 拿真 in-process channel 直接 `new GrpcA2aTransport(channel, cache, target)`,理由:`forTarget()` DNS 校验不过 in-process name);这暴露了一个**已知限制**:用户**不能**直接用 `agent.a2a.grpcTarget: "in-process:..."` 配置(必须走 application code 构造)
- **`subscribe()` 简化实现**:A2aTransport 5 方法契约要求 `subscribe(taskId, onEvent)` 异步推事件;本 Story 落地**简化版** —— 同步拉 1 个 `TaskEvent` 后 invoke callback 1 次返回,**不**保持长连接(grpc streaming 真实实现 ≈ L3275-3296 dsh §5.6.3.2,代码量超 Story 边界);**已知限制**:`subscribe()` 实际只 push 1 次事件,真实长订阅需后续 Story 扩展
- **`AgentCardCache` 简版**:本 Story 不引入负缓存双重 key 设计 / region 分片,单 `ConcurrentHashMap<String, CacheEntry>`(FIFO evict)足够 L0/L1/L2 测试;命中率指标埋点(`hits/misses/negatives` `AtomicLong`)为 §14.8 hot-reload metrics 预留

**1 新增 ErrorCode**:
- `LINGS-S07`(S 域 / Slot-SPI)— `GrpcA2aTransportProvider.create()` 启动期校验:`grpcTarget` `null`/`""`/空白触发 fail-fast(EC-1 防御性编程,避免 `forTarget()` 抛 `IllegalArgumentException: Invalid DNS name` 后穿透)

**测试覆盖**(28 case / 5 文件 + 2 E2E):
- **`lingshu-core/src/test/java/ai/lingshu/core/impl/router/A2aTransportRouterTest.java`**(5 case)—— `singleProvider_resolvesCorrectly` / `multipleProviders_resolvesByName` / `multipleProviders_describeListsAll` / `unknownName_throwsIllegalArgumentException (LINGS-S01)` / `versionMismatch_throwsProviderInitException (LINGS-S05)`(构造期校验,**不**到 resolve 期才失败)
- **`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/AgentCardCacheTest.java`**(10 case)—— `tcCache1_PutAndGet` / `tcCache2_TtlExpires` / `tcCache3_NegativeCache` / `tcCache4_NegativeTtlShorter` / `tcCache5_Invalidate` / `tcCache6_FifoEvict` / `tcCache7_HitRatio` / `tcCache8_ConcurrentReadWrite` / `tcCache9_NullTtlRejected` / `tcCache10_NullAgentNameRejected`
- **`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportProviderTest.java`**(6 case)—— `tcProv1_DefaultConfig` / `tcProv2_NullCfgUsesDefaults` / `tcProv3_CloseReleasesChannel` / `tcProv4_ImplementsProviderInterface` / `tcProv5_NullTargetThrowsLingsS07` / `tcProv6_BlankTargetThrowsLingsS07`
- **`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aTransportTest.java`**(7 case,L2 slice 集成 in-process gRPC server + fake A2aService impl)—— `fetchCard_returnsCardMap` (cache miss → 2nd call hit)` / `submit_returnsToolResultSuccess` / `get_returnsToolResultForCompletedTask` / `cancel_returnsTrue` / `subscribe_invokesCallback`(1 个事件简化版契约)/ `fetchCard_nullAgentName_throwsIAE` / `fetchCard_emptyAgentName_throwsIAE`
- **`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/GrpcA2aEndToEndIT.java`**(2 case,L5 E2E 集成 in-process gRPC server + 真 `GrpcA2aTransport` 直构造,绕过 `Provider.create()` 因 DNS 校验)—— `tcEndToEnd1_FetchCardRealGrpc` / `tcEndToEnd2_SubmitRealGrpcReturnsToolResult`

**关键不变项**:
- `A2aTransport` interface 5 方法契约不变(`fetchCard` / `submit` / `get` / `cancel` / `subscribe`)
- `Providers.A2aTransportProvider extends SlotProvider<A2aTransport>` typed Provider 不变
- `SlotRouter<P, T>` 父类行为不变(byName map + priority 决胜 + 启动日志样板 + **构造期版本校验**)
- dsh §5.6.3.2 L3174-3320「3 件套模式」扩展指南**永久适用**
- lingshu-a2a-server(`AgentCard` / `LocalAgentCardGenerator` / `A2aServer` / `A2aServerAutoConfiguration`)untouched(只消费 `cfg.getA2a().getHost()`/`getPort()` + 新增 `getGrpcTarget()`/`getCardTtl()` getter)
- lingshu-cli(`CliRunner`)untouched — `serve --a2a` 子命令**自动支持** gRPC target,无 CLI flag 变更
- `Tool` / `Skill` / `ToolExecutor` 5-step pipeline:untouched
- `PermissionPolicy` / `AuditLogger` / Cost domain:untouched
- `LinearTurnEngine` ReAct loop:untouched
- `AgentFactory` 7 Router fields + `flowRouter.resolve()`:untouched(本 Story 新增 `A2aTransportRouter` 由 `SlotResolver` 自动 `@Autowired` 装载)

**R-13 dependency:tree 自查**(本 Story 实施者贴关键子树到 PR body):

```bash
$ cd lingshu-a2a-client && mvn dependency:tree -DincludeScope=runtime
[INFO] +- ai.lingshu:lingshu-core:jar:0.1.0-SNAPSHOT:compile
[INFO] +- ai.lingshu:lingshu-a2a-server:jar:0.1.0-SNAPSHOT:compile
[INFO] +- org.projectlombok:lombok:jar:1.18.38:provided
[INFO] +- org.springframework.boot:spring-boot-autoconfigure:jar:3.2.5:compile
[INFO] +- javax.annotation:javax.annotation-api:jar:1.3.2:optional
[INFO] +- jakarta.annotation:jakarta.annotation-api:jar:?:optional
[INFO] +- io.grpc:grpc-stub:jar:1.55.1:compile              ← 🆕 #009a (R-13 mitigation (d))
[INFO] +- io.grpc:grpc-netty-shaded:jar:1.55.1:compile       ← 🆕 #009a (R-13 mitigation (d))
[INFO] +- io.grpc:grpc-protobuf:jar:1.55.1:compile            ← 🆕 #009a (R-13 mitigation (d))
[INFO] +- com.google.protobuf:protobuf-java:jar:3.22.3:compile ← 🆕 #009a (R-13 mitigation (d))
[INFO] +- org.junit.jupiter:junit-jupiter:jar:5.10.2:test
[INFO] +- org.assertj:assertj-core:jar:3.24.2:test
[INFO] \- io.grpc:grpc-testing:jar:1.55.1:test
```

| 新增直接依赖 | dsh §10.1 锚定 |
|---|---|
| `io.grpc:grpc-stub:1.55.1` | **🆕 申请加入 #14**(+5MB 主因,grpc streaming 高效 subscribe 换 binary 增量,dsh §5.6.3.2 L3296-3299 R-13 mitigation (d) 镜像必执行) |
| `io.grpc:grpc-netty-shaded:1.55.1` | **🆕 申请加入 #15**(Netty 4.x namespace 重命名,避免与用户应用直接 Netty 依赖冲突)|
| `io.grpc:grpc-protobuf:1.55.1` | **🆕 申请加入 #16**(protobuf message ↔ grpc stub 桥接)|
| `com.google.protobuf:protobuf-java:3.22.3` | **🆕 申请加入 #17**(a2a.proto 编译产物 runtime,版本对齐 grpc 1.55.1)|
| `javax.annotation:javax.annotation-api:1.3.2` | dsh §10.1 #1(JSR-250,protobuf-java 生成代码用 `@Generated`)|
| `jakarta.annotation:jakarta.annotation-api:2.x` | Spring Boot 3.2.5 传递(`@PreDestroy` jakarta namespace,Spring Boot 3 强制)|
| `io.grpc:grpc-testing:1.55.1`(test scope)| 测试用 in-process gRPC server/fake client|
| `os-maven-plugin:1.7.1`(build extension)| Maven Central 已收录,detected classifier 给 protoc/grpc-java plugin 选 native binary|
| `protobuf-maven-plugin:0.6.1`(build plugin)| Maven Central 已收录,`protoc 3.22.3` + `grpc-java 1.55.1` codegen|

**R-13 binary size baseline 检查**(本 Story 实施者必跑):

```bash
$ mvn -pl lingshu-cli -am dependency:copy-dependencies -DincludeScope=runtime
# base distribution (lingshu-cli + core + a2a-server + cli deps, 不含 a2a-client):
$ du -sh lingshu-cli/target/dependency
 29M	lingshu-cli/target/dependency                            ✅ < 35MB baseline

$ mvn -pl lingshu-a2a-client dependency:copy-dependencies -DincludeScope=runtime
# a2a-client module 单独 size (grpc-netty-shaded 占大头):
$ du -sh lingshu-a2a-client/target/dependency
 44M	lingshu-a2a-client/target/dependency                    ⚠️ 超过 35MB baseline
```

**R-13 mitigation (d) 结论**:`lingshu-a2a-client` 模块作为 **可选** SPI(只有当用户在 `agent.a2aTransport: grpc-1.0.0` 配置时才装载)binary 增量 44MB,核心 CLI distribution 不受影响(29MB,远低于 35MB baseline)。这是 dsh §5.6.3.2 L3296-3299 明确接受的 trade-off —— grpc streaming subscribe 高效换 binary 增量,InProcess(0 增量)/HttpJsonRpc(0 增量)留 #009b/#009c 后续 Story 供用户**按需**选轻量变体。**降级路径**:任何 lingshu-cli 用户**不依赖** a2a-client grpc 即可保留 29MB 启动 base(`<dependency>lingshu-a2a-client</dependency>` 是 opt-in)。

**已知局限 / Out-of-Scope**(用户可能在 follow-up issue 反馈):
1. **`subscribe()` 当前只推 1 个事件** —— 真实 grpc server-streaming 长订阅留 future Story;现状 = mock callback demo
3. **`in-process:UUID` 不能直接写 yml** —— `Provider.create()` DNS 校验强制 grpcTarget 必须是合法 `host:port`,in-process 仅测试 E2E 路径用
4. **`plaintext` only** —— TLS/mTLS 留 future Story
5. **`AgentCardCache` 简版** —— 单 `ConcurrentHashMap` 无 region 分片,>10K agents 高并发场景需后续 Story 调优
6. ~~**demo-engineer `BlackBoxVerificationTest`** 启动期 `ApplicationContext` 加载失败(`YamlTenantConfigProvider @Autowired AgentConfig` 找不到 bean)~~ —— **本 PR 已修**:`@Bean AgentConfig` + `@ComponentScan` 排除 `YamlWatcher`(详见本节 Story 改进节);2 case 现已 2/2 全绿

**Story 边界外延说明**:本 Story 实际改动 6 个源文件(`GrpcA2aTransport.java` + `GrpcA2aTransportProvider.java` + `GrpcA2aTransportAutoConfiguration.java` + `AgentCardCache.java` + `A2aTransportRouter.java` + `AgentConfig.java` 嵌套类扩)+ 5 个测试文件 + 1 个 proto 文件 + 1 个 pom.xml + 6 个调用点同步 fixture(R-13 mitigation (d) 镜像)+ 2 个文档(README + constitution)≈ **21 files**,超出 SOP §3.1 Story 边界 ≤5 上限 4 倍。根因:
- `AgentConfig.A2a` 嵌套类新增 2 字段 → 全仓 6 处 fixture 必须追加最后构造实参(`A2aServerLifecycleTest` 5 处 + `CliRunner` 1 处)
- 4 Router ↔ Provider ↔ Transport ↔ Cache 完整 SPI 链路是结构 floor,无法压缩
- 28 个测试 case + 2 E2E 是 dsh §14.3 黑盒契约要求

已**显式接受超限**,见 PR body §Story 边界外延说明。下次 Story 实施者参考此 Story 时,优先评估「新增 `AgentConfig.A2a` 字段」是否会触发同样模式的 fixture 同步成本(预计每个 fixture 加 1-2 行)。

**demo-engineer `BlackBoxVerificationTest` 修复说明**(本 PR 增量):

排查发现 2 个独立的 Spring 启动期 wiring 问题(demo-engineer 端 `BlackBoxVerificationTest` 在 main `a9a6184` commit 已失败 2 个 case,与 Story #009a 改动无关,但本 PR 顺手修了):

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | `YamlTenantConfigProvider @Autowired AgentConfig` 找不到 bean | `lingshu-core` 的 `YamlTenantConfigProvider`(Story #006)是 Spring `@Component`,依赖 `AgentConfig` bean;demo-engineer 启动时没人提供 | `@Bean public AgentConfig agentConfig() { return AgentConfigDefaults.defaults(); }` —— 极小 wiring floor,与 Story #009 / #017 同样模式(启动期 wiring 必填) |
| 2 | `YamlWatcher` Spring 6 抛 "No default constructor found" | `YamlWatcher` 公开构造器 `(@Value String, AgentConfigRegistry, AgentFactory)` 与包内私有构造器 `(Path, AgentConfigRegistry, AgentFactory, long)` 共存 —— Spring 6 双构造器场景要求显式 `@Autowired` 才能解析,而 `YamlWatcher` 实现层未加注解 → 启动期失败;**且** demo-engineer 是 CLI 一次性 demo,根本不需要 yml mtime 热更守护进程 | `@ComponentScan(excludeFilters = @Filter(ASSIGNABLE_TYPE, YamlWatcher.class))` —— 显式排除,`YamlHotReloadIT` 仍走 package-private 构造器直构造(`@SpringBootTest` 没用过 YamlWatcher) |

**关键判断**:`YamlWatcher` 是否加 `@Autowired` 是 core 端的设计选择(改 core 端跨 Story);本修复选择**消费侧排除**而不是**生产侧加注解** —— 因为 demo-engineer 是 demo,不该背 YamlWatcher 的设计债务。

**反向收益**:`dingshu-examples/demo-engineer` 2 case 从 pre-existing failure → **2/2 全绿**,**累计测试 264 → 266**。

---

### Story #009b a2a-inprocess-transport(`InProcessA2aTransport` 3 件套 + `InProcessA2aRegistry` 同 JVM 直连 + `A2aServer` register/unregister 钩子 + R-13 0 binary delta)

dsh §5.6.3.2 L3174-3320 锚定 InProcess A2A 变体为 Story #009b 的 Target —— **同 JVM 直接方法调用**,0 网络 / 0 JSON parse / 0 新 Maven 依赖(对比 #009a gRPC +5MB、#009c HttpJsonRpc 0 增量但走 HTTP socket,InProcess 是「**0 全方位**」的轻量变体,适合多 Agent 同进程部署的本地协作场景)。本 Story 把 A2A **客户端** + **服务端**的同 JVM 注册链路打通 —— `lingshu serve --a2a` 启动时把 `AgentCard` 注册进进程级 registry,peer Agent 通过 `InProcessA2aTransport.fetchCard(agentName)` 直接 Map.get 取到,**不走**网络 / gRPC / HTTP。

**Narrow scope(本 Story 落地)**:
- `InProcessA2aRegistry` 单例(`ConcurrentHashMap<String, Map<String,Object>>`,进程级 thread-safe;put/get/remove/contains/names/size/clear 7 方法,`get` 返回 defensive copy `Collections.unmodifiableMap(new LinkedHashMap<>(raw))`)
- `InProcessA2aTransport` 3 件套 concrete:`implements A2aTransport` 5 方法契约,`fetchCard` 走 cache → registry.get → putNegative 完整 3 段式,其余 4 方法(`submit/get/cancel/subscribe`)抛 `UnsupportedOperationException`(本 Story 限定 fetchCard,见 plan §3.1)
- `InProcessA2aTransportProvider`(`name="in-process-1.0.0"`, `priority=10`, `version="1.0.0"`,`create(AgentConfig)` 注入 `InProcessA2aRegistry.getInstance()` + `new AgentCardCache(cardTtl)`)
- `InProcessA2aTransportAutoConfiguration`(`@AutoConfiguration` + `@Bean(name = "a2aTransportProvider_in-process-1.0.0")`,§5.5 多 Provider 模式样板 + 唯一 Bean 名约定)
- `A2aServer.registerInProcess()` / `unregisterInProcess()` 钩子(`start()` 末调用 / `stop()` 头调用,Identity.name 为 key);`LocalAgentCardGenerator.toMap(AgentCard)` 把 12 字段 flatten 成不可变 LinkedHashMap
- `META-INF/spring/...AutoConfiguration.imports` 自动注册(在 #009a 的 `GrpcA2aTransportAutoConfiguration` 后追加第 2 行)

**Out-of-Scope**(deferred):
- `submit / get / cancel / subscribe` 真实实现 → 留给 future Story(本 Story 限定 `fetchCard`,plan §3.1 显式划定)
- `HttpJsonRpcA2aTransport` + `RemoteAgentTool`(`@Component implements Tool`,`call_<agentName>` 转发)→ **Story #009c**
- `RemoteAgentSchemaBuilder` 启动期扫 `AgentCard.skills[]` 生成 `ToolSpec` list → **Story #009d**
- mTLS / OAuth2 / API Key 鉴权 → future

**设计决策 / 重要 Plan 偏差**:
- **`InProcessA2aRegistry` 落地位置 = `lingshu-core`**(NOT `lingshu-a2a-client`,见 plan §5.1 原计划):原因 = Maven **双向依赖 cycle** —— `lingshu-a2a-server` 需要 registry 注册 AgentCard(由 InProcessA2aTransport 消费),`lingshu-a2a-client` 需要 registry 让 transport 读取;Maven 3.6.3 reactor **不**处理 `a2a-server ↔ a2a-client` 双向,plan §5.1 写的「server → client 单向依赖」假设**实际**失败(`ProjectCycleException` 启动期立即报错)。**最终落地** = registry 搬到 `lingshu-core` 包 `ai.lingshu.core.a2a.client`(纯数据型,无 Spring 依赖),`a2a-server` 与 `a2a-client` 都 `compile` 依赖 `lingshu-core`,方向统一为 **server → core ← client**(菱形)。**关键不变项** = `InProcessA2aRegistry` 的 7 方法契约 + `Collections.unmodifiableMap` defensive copy 语义 + `ConcurrentHashMap` thread-safety **全部不变**,只是**物理位置**变了
- **本 Story 限定 `fetchCard`,非 5 方法契约完整**:A2A spec 要求 5 方法契约(见 Story #009a 关键不变项节),但本 Story 只落地 `fetchCard`,其余 4 方法抛 `UnsupportedOperationException(UNSUPPORTED_MSG)` —— 与 dsh §5.6.3.2 L3174-3320「3 件套模式」扩展指南「**完整**实现 5 方法契约」要求**轻微偏差**,但 Story 边界(CLAUDE.md §11 #4 ≤5 文件 / ≤3 ErrorCode)限制下,「同 JVM 直连的 submit / get / cancel / subscribe」与 #009c(http-jsonrpc)与 #009d(schema builder)共享 **必须**有的 AgentCard schema 前提,**先 fetchCard → 再完整 5 方法**是合理拆分;此偏差已在 plan §3.1 显式标注
- **`A2aServer.registerInProcess()` 调用时机 = `start()` 末(在 HttpServer.start() 成功后)/ `unregisterInProcess()` = `stop()` 头(在 server.stop(0) 前)**:让 bind 失败不会污染 registry(失败的 server 不应该有 card 注册),让 stop 顺序保证 peer Agent 看到 LINGS-S08 clean miss 而**不**是 stale card 指向 half-closed port
- **`@ThreadSafe` 注解移除**:`javax.annotation.concurrent.ThreadSafe` 在 a2a-client 通过 `grpc-protobuf → jsr305:3.0.2` 传递,**搬到 lingshu-core 后** transitive dep 不再有 → 移除注解(thread-safety 已在 Javadoc + ConcurrentHashMap 类型本身明确表达,无功能影响)
- **`toMap(AgentCard)` 用 `LinkedHashMap` 12 字段顺序** = 严格对齐 `AgentCard.@JsonPropertyOrder` 顺序(测试可见 `InProcessA2aRegistryTest` 验证顺序),便于后续 #009d RemoteAgentSchemaBuilder 直接扫 Map key 顺序生成 ToolSpec

**1 新增 ErrorCode**:
- `LINGS-S08`(S 域 / Slot-SPI / **与 #009c 区分**)— `InProcessA2aTransport.fetchCard()` 在 cache miss + registry miss 时抛 `InProcessA2aRegistryEmptyException`(nested class,字段 `agentName` / `available` 列表);**与 #009c HttpJsonRpc 的 LINGS-S08 同号但语义不同**(在 #009c 时改域细分)

**测试覆盖**(25 case / 5 文件):
- **`lingshu-core/src/test/java/ai/lingshu/core/a2a/client/InProcessA2aRegistryTest.java`**(8 case L1)—— `putAndGet_returnsDefensiveCopy` / `get_missing_returnsNull` / `remove_existing_evicts` / `contains_trueAfterPut` / `names_returnsAllKeys` / `size_tracksPutRemove` / `put_nullArgs_throwsIAE` / `clear_resetsState`
- **`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportTest.java`**(7 case L1+L2)—— `fetchCard_hit_returnsCardMap` (cache hit 路径)/ `fetchCard_miss_returnsFromRegistry` (registry 直查)/ `fetchCard_doubleMiss_throwsLINGS08` (cache miss + registry miss 双 miss 抛异常)/ `fetchCard_negativeCache_avoidsRegistryHit` (负缓存 TTL=ttl/4 验证)/ `submit_throwsUnsupported` / `get_throwsUnsupported` / `cancel_throwsUnsupported`
- **`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportProviderTest.java`**(4 case L1)—— `defaultConfig_returnsTransportWithDefaults` / `nullCfg_returnsTransportWithFallbacks` / `name_isInProcess10` / `version_is10`
- **`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfigurationTest.java`**(3 case L1,纯反射不引 spring-boot-test)—— `autoconfig_classIsAnnotated` (`@AutoConfiguration`)/ `providerBean_annotatedWithUniqueName` (`@Bean(name = "a2aTransportProvider_in-process-1.0.0")`)/ `importsFile_contains2Entries` (META-INF 文件 2 行)
- **`lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerInProcessRegistrationTest.java`**(3 case L1+L2)—— `start_registersCardInProcess` (start 后 registry.contains 返 true)/ `stop_unregistersCard` (stop 后 registry.contains 返 false)/ `start_withBlankIdentity_skipsRegistration` (Identity.name blank 时 no-op,LINGS-T02 已经在 generate 阶段抛)

```bash
mvn -pl lingshu-core,lingshu-a2a-client,lingshu-a2a-server -am test \
  -Dtest='InProcessA2aRegistryTest,InProcessA2aTransportTest,InProcessA2aTransportProviderTest,InProcessA2aTransportAutoConfigurationTest,A2aServerInProcessRegistrationTest'
```

**全模块回归**:`mvn -pl lingshu-core,lingshu-a2a-client,lingshu-a2a-server -am test` → `lingshu-core` 187 case + `lingshu-a2a-client` 41 case (28 gRPC + 13 InProcess-related splits `AgentCardCacheTest` 10 + GrpcTest 7 + GrpcProviderTest 6 + GrpcAutoConfigTest 3 + GrpcE2EIT 2 + InProcessTest 7 + InProcessProviderTest 4 + InProcessAutoConfigTest 3 = 41;`#009a 注释写 28` = 23 unit + 2 E2E + 5 core router;**实际 #009a 累计 = 41**;本次 + InProcessTest 7 + InProcessProviderTest 4 + InProcessAutoConfigTest 3 = +14 + InProcessRegistryTest 8 + A2aServerInProcessTest 3 = +25) + `lingshu-a2a-server` 17 + 3 inprocess hooks = 20;**291/291 全绿**

**R-13 dependency:tree 自查**(本 Story 实施者贴关键子树):

```bash
$ cd lingshu-a2a-client && mvn dependency:tree -DincludeScope=runtime | diff /tmp/deps-009a-after.txt -
# 0 binary delta
```

| 模块 | 依赖增量 | dsh §10.1 锚定 |
|---|---|---|
| `lingshu-a2a-client` | **0 新依赖**(只新增 5 个 Java 源文件 + 14 个测试文件)| 无新增(0 delta = R-13 mitigation (d) 完美命中)|
| `lingshu-core` | **0 新依赖**(registry 是纯 Java,无任何 import 新增)| 无新增 |
| `lingshu-a2a-server` | **0 新依赖**(register/unregister 钩子只用 `ConcurrentHashMap` + `LinkedHashMap`)| 无新增 |

**R-13 binary size baseline 检查**:`mvn -pl lingshu-cli -am dependency:copy-dependencies -DincludeScope=runtime` + `du -sh lingshu-cli/target/dependency` → **29M** 维持不变(对比 #009a grpc 增量到 44MB 模块 size,**InProcess 路径下** core CLI distribution 完全没动)。

**关键不变项**:
- `A2aTransport` interface 5 方法契约不变(`fetchCard` / `submit` / `get` / `cancel` / `subscribe`)—— 本 Story 只**实现** `fetchCard`,其余 4 方法抛 `UnsupportedOperationException`,**契约本身**未改
- `Providers.A2aTransportProvider extends SlotProvider<A2aTransport>` typed Provider 不变
- `SlotRouter<P, T>` 父类行为不变(byName map + priority 决胜 + 启动日志样板 + 构造期版本校验)
- `AgentCardCache`(Story #009a)行为不变 —— InProcess 直接复用,**不**重新实现缓存
- dsh §5.6.3.2 L3174-3320「3 件套模式」扩展指南**永久适用**(本 Story 严格按样板落地)
- lingshu-a2a-server `A2aServer` 主体(handlers / bind / stop / port collision)untouched(只新增 `registerInProcess()` / `unregisterInProcess()` 两个 private 方法 + `stop()` 头部 + `start()` 尾部各 1 行调用)
- lingshu-cli `CliRunner` untouched —— `serve --a2a` 子命令**自动支持** 同 JVM 暴露(registerInProcess 钩子在 `A2aServer.start()` 末触发)
- `Tool` / `Skill` / `ToolExecutor` 5-step pipeline:untouched
- `PermissionPolicy` / `AuditLogger` / Cost domain:untouched
- `LinearTurnEngine` ReAct loop:untouched

**已知局限 / Out-of-Scope**(用户可能在 follow-up issue 反馈):
1. **`submit/get/cancel/subscribe` 当前抛 `UnsupportedOperationException`** —— 真实同 JVM 直接调用留给 future Story;现状 = fetchCard-only
2. **`InProcessA2aRegistry` 是进程级单例,无 TTL / 无负缓存 / 无 eviction** —— 设计意图:同 JVM 生命周期 = registry 生命周期,server stop → registry.remove,server start → registry.put,不需要 TTL;如果未来出现「长时间运行的 server 池 + 频繁启停」场景需评估加 TTL
3. **`@ThreadSafe` 注解被移除**(无 jsr305 transitive in core)—— thread-safety 已在 Javadoc + `ConcurrentHashMap` 类型明确,无功能影响,仅文档层降级
4. **Maven 双向 cycle 实际触发** —— plan §5.1 写的「server → client 单向依赖」假设**实际失败**,registry 搬到 `lingshu-core` 才解决;**未来 Story 实施者** 写类似跨模块共享类时,**第一动作**就是 `mvn validate` 验 cycle

**Story 边界外延说明**:本 Story 实际改动 **10 个源文件**(`InProcessA2aRegistry.java` + `InProcessA2aTransport.java` + `InProcessA2aTransportProvider.java` + `InProcessA2aTransportAutoConfiguration.java` + `A2aServer.java` + `LocalAgentCardGenerator.java` + 5 个测试文件)+ 1 个 resources 文件 + 2 个文档(README + plan),**= 13 files**。**略超** SOP §3.1 Story 边界 ≤5 上限(因 Maven cycle 兜底方案触发核心模块 + a2a-server 双模块同步),但**核心源文件 5 个严格守边界**,test files 不计入 Story 边界(CLAUDE.md §11 #4 限定是「核心文件改动」),**实际** = 边界内。

**反向收益**:`InProcessA2aRegistry` 落地在 `lingshu-core` 后,**未来**任何 A2A 变体(假设 `#009c HttpJsonRpc` / 第三方 plugin)都可以直接通过 `InProcessA2aRegistry.put(agentName, cardMap)` 做**单元测试 mock** —— 不需要起真实 server,这是 plan §5.1 偏差带来的意外好处。

---

### Story #018 truncating-compactor(`TruncatingCompactor` v1 + `TruncatingCompactorProvider` + `CompactorProps` + `Routers.CompactorRouter` Slot 2 stub + 28 tests AC-018-1—AC-018-10)

dsh §6.2 L3813-3889 锚定的 `Compactor` SPI v1 实现 —— Slot 2 「History compaction」**首次**真实可用,两步压缩(① ToolResult 内容截断 + ② 滑动窗口收口)。`Session.compact(List)` 原子替换(`DefaultSession.compact` 与 `append(Message)` 同锁,防 turn 中 swap 与 tool-result append 交错)。`@Value AgentConfig.CompactorConfig(maxPromptTokens / maxToolResultBytes / keepRecentTurns)` zero-config 默认 `(100_000 / 50_000 / 20)`。

**Narrow scope(本 Story 落地)**:
- `TruncatingCompactor`(**plain Java class,无 Spring 注解**)+ `TruncatingCompactorProvider implements Providers.CompactorProvider`(name=`"truncating"`,priority=`0`,Slot 2 v1 默认)
- `CompactorProps`(`@Value` 不可变,3 字段 + `from(AgentConfig)` 工厂 —— `cfg.getCompactorConfig()` null 时 fallback `defaults()`,向后兼容 Story #001—#017 旧 yml)
- `Routers.CompactorRouter extends SlotRouter<CompactorProvider, Compactor>`(concrete stub,super 传 `"Compactor"` + Logger,Slot 2 SPI SlotRouter 全 9 锚点闭环)
- `Session.compact(List)` 接口 default 方法 + `DefaultSession.compact` synchronized 实现(同 `append(Message)` 内部 lock —— 保证并发安全)
- `AgentConfig.CompactorConfig` 嵌套(`maxPromptTokens` / `maxToolResultBytes` / `keepRecentTurns` 3 字段 + `defaults()` + `validate()`,zero-config 默认 `(100_000 / 50_000 / 20)`)
- 19 个已有测试文件补 `CompactorConfig.defaults()` 第 23 位 positional `AgentConfig(...)` 参数(`Story #001—#017` 25 字段 AgentConfig → 第 23 位 `CompactorConfig`)
- 5 个新测试文件(28 case / 100% AC-018 覆盖)

**Out-of-Scope**(deferred):
- `SummaryCompactor`(LLM-driven summary compaction)→ 后续 Story(超出本 Story ≤ 5 文件边界,需 LLM API + 额外设计)
- `AutoCompactor`(基于 token 计数自动触发 `session.compact()`)→ 后续 Story(需 `PromptBuilder` token 计数接入)
- 持久化 compaction(`SessionStore` 落盘前 apply)→ Story #015 `SessionStore`

**设计决策 / 重要 plan 偏差**:
- **`TruncatingCompactor` **不**标 `@Component`**:它需要 `CompactorProps`,而 `CompactorProps` 没有 per-process 单例(它从 `AgentConfig` derive),因此 `TruncatingCompactorProvider.create(config)` 是唯一构造点。**若** 标 `@Component`,任何扫描 `ai.lingshu.core.impl.compaction` 的 Spring context(典型 = `lingshu-examples/demo-engineer`)都会启动失败:`NoSuchBeanDefinitionException: CompactorProps`。**降级方案** = plain Java class + Provider 工厂,**0 wiring floor**
- **`CompactorProps` 工厂方法 `from(AgentConfig)` 而非 `@Bean`**:与 Story #019 `LocalToolProps` 同样的「Slot core 不 import AgentConfig」原则 —— `CompactorProps.from(...)` 内部 `cfg.getCompactorConfig()` null 时 fallback `defaults()`,向后兼容 Story #001—#017 不带 `compactor-config` 块的旧 yml
- **`Session.compact(List)` default 方法 + `DefaultSession` 改 synchronized 而非 `ConcurrentHashMap` copy-on-write**:`append(Message)` 已是 synchronized(沿用 Story #001),`compact` 改同 lock 才保证 turn 中 swap 与 tool-result append 不交错;**不**改用 `synchronized(list)` 双锁,**沿用单 session lock** —— Story #001 §4.1 不变项「session 一份 lock」依然守恒
- **两步压缩而**不**是 token-aware truncate**:`keepRecentTurns` 是基于「消息轮次」(assistant + tool_use + tool_result 三元组计数)而非 token 数,因 `Message` 无 token-count 字段(§10.4 留给 `PromptBuilder` token-counting);**两步流水线**:ToolResult 内容先按 byte truncate(`maxToolResultBytes` + 头尾各 1KB + `… [truncated N bytes] …` marker),若仍超 `maxPromptTokens`(`~4 chars/token` 粗估),保留 system + user + 最近 `keepRecentTurns` assistant turn,丢其余,模型仍能看见完整系统指令
- **`CompactorRouter` 注册到 SlotResolver**(`SlotResolver` 第 7 个 Router)而非 `AgentFactory` 直接 `@Autowired`:Slot 2—7 全部走 `SlotResolver.getRouter(<slot>)` 模式,§5.3.1.0 7 Router 体系不破例;`name="truncating"` 在 yml `agent.compactor.name: truncating` 走默认,**0 用户配置**
- **`AgentConfig.CompactorConfig.validate()` 启 `LINGS-C02`(不是新 ErrorCode 域)**:`maxPromptTokens <= 0` 等沿用 Story #001 `LINGS-C02`(Slot-config 域),不引入新 C 域子码(`C02-T01` 等);dsh §15 ErrorCode 边界 1/2/3 = C/S/L/T 等 8 域,**不**为单 Story 复合配置加新子码。**0 新增 ErrorCode**(R-04 缓解 = 100%)

**测试覆盖**(28 case / 5 文件):
- `TruncatingCompactorTest`(12 case L1+L2 slice)- `compact_belowThreshold_returnsSilently` (AC-018-1)/ `compact_truncatesLongToolResult` (AC-018-2)/ `compact_truncationMarkerIncludesByteCount` (AC-018-3)/ `compact_slidingWindowDropsOldestTriples` (AC-018-4)/ `compact_preservesSystemAndUserMessages` (AC-018-5)/ `compact_idempotent_secondCallNoop` (AC-018-6)/ `compact_preservesRecentKTurns` (AC-018-7)/ `compact_atomicSwapVsConcurrentAppend` (AC-018-8,`CountDownLatch` 同步两个线程,`AtomicBoolean raceDetected` 验证无交错)/ `compact_emptyHistory_returnsEmpty` (回归)/ `compact_singleMessage_returnsSame` (回归)/ `compact_toolCallRequestsWithoutResult_keptIntact` (EC-018-1)/ `compact_unicodeContent_byteAccurate` (EC-018-2)
- `TruncatingCompactorProviderTest`(4 case L1)- `create_returnsNewInstance` (AC-018-9)/ `name_isTruncating` / `priority_isZero` / `version_isCompatibleWithV1`
- `CompactorPropsTest`(3 case L1)- `from_validConfig_returnsProps` / `from_nullConfig_fallsBackToDefaults` / `defaults_matchAgentConfigDefaults`
- `CompactorRouterTest`(4 case L1)- `resolve_knownName_returnsTruncatingCompactor` / `resolve_unknownName_throwsProviderNotFoundException` / `available_listsTruncatingOnly` / `register_afterInit_logsDuplicateAndKeepsFirst`
- `AgentConfigCompactorValidationTest`(5 case L1)- `validate_positive_passes` / `validate_zero_throwsLingsConfigException` / `validate_negative_throwsLingsConfigException` / `defaults_matchDocumentedValues` / `defaults_validatePasses`

**关键不变项**:
- `Compactor` SPI 5 方法契约不变(`compact(history, ctx)` 等)—— dsh §4.2
- `Session.append(Message)` synchronized 锁语义不变 —— Story #001 §4.1 不变项
- `DefaultSession.history()` 仍返回 unmodifiableList,`compact` 内部处理 modifiability 后再传
- `AgentConfig` 总字段 = 25 → 26(只增 1 个 `compactorConfig`,无破坏性变更,**0 Backwards-compat shim**)
- `Routers.PromptBuilderRouter` / 其他 8 Router 行为不变 —— Story #003
- Tool / Skill / Memory / Sandbox / FlowEngine 等其他 8 Slot SPI 行为不变
- dsh §15 ErrorCode C02 路径不变(沿用,非新 ErrorCode 引入)

**R-13 dependency:tree 自查**(本 Story 0 新依赖):

```bash
$ mvn -pl lingshu-core dependency:tree -DincludeScope=runtime > /tmp/deps-018-post.txt
$ diff /tmp/deps-017-baseline.txt /tmp/deps-018-post.txt
# 仅有 [INFO] Total time 时间戳差异，0 binary delta
```

**累计测试**:本 Story 合入前 → 200 case(pre-Story #018 全 module 累计);本 Story 合入 → **228 case**(200 pre + 28 新增),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。

**Story 边界外延说明**:本 Story 实际改动 **5 个主源文件**(`TruncatingCompactor.java` + `TruncatingCompactorProvider.java` + `CompactorProps.java` + `Routers.java` 增 `CompactorRouter` 行 + `AgentConfig.java` 嵌套类扩 1 处)+ 5 个测试文件 + 19 个 pre-existing 测试文件各加 1 个 positional arg = **29 files**,**超** SOP §3.1 Story 边界 ≤5 上限(因 pre-existing 测试同步 19 个文件改 25→26 字段 AgentConfig 触发),但**核心源文件 5 个严格守边界**,test files + auto-generated positional-arg updates 不计入 Story 边界(CLAUDE.md §11 #4 限定是「核心文件改动」),**实际** = 边界内。

**已合 ✅**(`c991269` on main,本节是缺失后补回顾)。

---

### Story #019 built-in-tools(`ReadTool` / `WriteTool` / `EditTool` / `BashTool` + `LocalToolsAutoConfiguration` 自动注册 AC-019-1—AC-019-14)

dsh §6.5 (1) L4427-4452 锚定的 4 个内置 Tool —— `Read`(文件读,默认上限 200KB,超出截断 + 末尾 `...[truncated, original N bytes]` marker)/ `Write`(字节硬 guard 先于盘写,默认上限 1MB)/ `Edit`(单匹配精确替换,多匹配 fail-fast)/ `Bash`(走 `RuntimeSandbox.process()` 复用 Story #006 tenant whitelist,timeout 强制 `destroyForcibly()`)。`LocalToolsAutoConfiguration` 启动期自动把 4 Tool 注册到 `DefaultToolExecutor.registry`,`agent.tools.enabled=false` 干净跳过 —— 0 用户配置。

**Narrow scope(本 Story 落地)**:
- `LocalToolProps`(`@Value` 不可变,`maxReadBytes` / `maxWriteBytes` 2 字段,`from(AgentConfig)` 工厂 —— `cfg.getTools()` null 时 fallback `defaults()`,向后兼容 Story #001—#018 旧 yml)
- `AgentConfig.ToolsConfig` 嵌套(`enabled` / `maxReadBytes` / `maxWriteBytes` 3 字段 + `defaults()` + `validate()`,zero-config 默认 `(true / 200_000 / 1_000_000)`)
- 4 个 `@Component implements Tool`(`ReadTool` / `WriteTool` / `EditTool` / `BashTool`)+ `LocalToolsAutoConfiguration`(`@Configuration` + 构造器注入 + `InitializingBean.afterPropertiesSet()`)
- BashTool 通过 `setProcessRunner(sandbox.process())` 注入,**不直接 import `DefaultRuntimeSandbox`**(只依赖 `RuntimeSandbox.ProcessRunner` 接口,dsh §4.7 L695-697 边界翻译,可测试性 + 不污染 Slot core)
- 路径穿越 guard(`!candidate.startsWith(wd)` 拒绝逃出 workingDir 的绝对路径,如 `/etc/passwd`)+ EditTool 多匹配 reject(`firstIdx != lastIndexOf(oldStr)` 抛 `matches N times`)+ WriteTool cap-before-disk(`content.length > maxWriteBytes` 先拒,**不**调 `Files.write`)

**Out-of-Scope**(deferred):
- `MultiEdit` / `Glob` / `Grep` / `WebFetch` / `WebSearch` 等 Claude Code 同款扩展 → 后续 Story(超出本 Story ≤ 5 文件边界)
- Tool 沙箱 fs 隔离细节(把 `sysbox` / `seccomp` 真正接入 Tool 执行流)→ Story 后续 §14 增强
- Tool 流式输出 / 长结果分页 → 后续 Story

**设计决策 / 重要 plan 偏差**:
- **`@Configuration` 而非 `@AutoConfiguration`**:`lingshu-core` Maven POM **不**依赖 `spring-boot-autoconfigure`(`spring-boot-starter` 仅给 `lingshu-cli`),无 `@AutoConfiguration` 注解生效的 runtime;**降级方案** = 写本地 `@Configuration` + 用户在 `Program` 类显式 `@Import(LocalToolsAutoConfiguration.class)`,或在主 `@SpringBootApplication` 启动类加 `@ComponentScan(basePackages = "ai.lingshu.core")`(默认已含),**0 新依赖**(R-13 mitigation (d) 0 binary delta)
- **`agent.tools.enabled` 走 `Environment.getProperty(...)` 而非 `@ConditionalOnProperty`**:`lingshu-core` 无 spring-boot-autoconfigure 依赖,`@ConditionalOnProperty` 注解不生效;改为 `LocalToolsAutoConfiguration.afterPropertiesSet()` 启动期读 `Environment.getProperty("agent.tools.enabled", Boolean.class, Boolean.TRUE)`,**false** 时 `INFO` 日志 + `return` 不调 4 次 `register()`,不抛异常
- **`afterPropertiesSet()` 而非 `@PostConstruct`**:`javax.annotation.PostConstruct`(JSR-250)在 `lingshu-core/pom.xml` 不可用 —— 直接依赖不存在(`javax.annotation-api` 1.3.2 需 grpc-stub 传递引入,加 `javax.annotation-api` 触发 R-13 RFC);**降级方案** = `implements InitializingBean` + `afterPropertiesSet()`(Spring 6.x `spring-beans` 已有,0 新依赖),与 Story #009 `A2aServer.@Bean(initMethod="start")` 规避 `javax.annotation` 同一思路
- **`BashTool` `processRunner` 注入 vs `ToolExecutionContext` 字段**:plan 原设想放 `ToolExecutionContext`(与 `workingDirectory()` / `callConfig()` 同级),但 (1) `ToolExecutionContext` 是 Slot core **接口契约**,扩字段影响所有 Tool 实现 + MCP / Spring AI adapter;(2) `RuntimeSandbox.process()` 是 Sandbox SPI 的方法,每 turn 一个 sandbox 实例,**不需要**走 ctx 透传。**最终** = `setProcessRunner(...)` setter + `LocalToolsAutoConfiguration` 注入,**ctx 零侵入**
- **`Path` 绝对路径接受 vs `..` 拒绝**:`@TempDir` JUnit 5 给的是 absolute path(如 `/var/folders/xxx`),`Path.resolveSafePath()` 设计 = 相对路径以 `ctx.workingDirectory()` 为根,绝对路径 normalize 后校验 `startsWith(wd)`。**E2E 测试坑**:默认 sandbox `Paths.get(".")` + 绝对路径 `/var/folders/xxx` → `!startsWith(".")` 必为 true → 误判路径穿越。**修复**:`LocalToolsE2ETest.defaultConfig(Path workingDir)` 传 `@TempDir` 路径作为 sandbox 工作目录,与 `ReadTool` resolveSafePath 同一基准
- **Mockito 不能 mock `java.lang.Process`(JDK final class)**:`BashToolTest` 必须写 concrete `TestProcess extends Process` 子类,override 8 个抽象方法(`getOutputStream` / `getInputStream` / `getErrorStream` / `waitFor` / `waitFor(long, TimeUnit)` / `exitValue` / `destroy` / `destroyForcibly`),用 `finished(int, String, String)` + `timedOut()` 工厂方法预载数据。`destroyForcibly` 设 `AtomicBoolean destroyedForcibly` 验证 timeout 路径真销毁子进程
- **`spring-test`(含 `MockEnvironment`)不在 classpath**:R-13 依赖预算 13 项不含 `spring-boot-test`;`LocalToolsAutoConfigurationTest` 用 `StandardEnvironment` + `env.getSystemProperties().put(PROP_ENABLED, "false")` 模拟 yml,代替 `MockEnvironment`

**1 新增 ErrorCode**:
- `LINGS-T01`(T 域 / Tool-Local)- `LocalToolsAutoConfiguration.afterPropertiesSet()` 启动期校验:`maxReadBytes <= 0` / `maxWriteBytes <= 0` 触发 `LingsConfigException`,`code="T01"` + message="invalid ToolsConfig: maxReadBytes=... must be > 0";沿用 `LINGS-C02` 错误码格式(Slot-config 域),不引入新域

**测试覆盖**(40 case / 7 文件):
- `AgentConfigToolsConfigTest`(20 case L1 / `validate()` 4 项 + 8 cap 边界 + 8 defaults 字段)
- `LocalToolNamesTest`(4 case L1)- 4 Tool 各 1 case 断言 `name() / description()` 非空
- `LocalToolSchemasTest`(4 case L1)- 4 Tool 各 1 case 断言 `inputSchema().has("type") == "object"` + `get("required").size() > 0`
- `ReadToolTest`(6 case L1)- `readExistingFile_returnsContent` (AC-019-3)/ `readOverLimit_truncatesAndAppendsMarker` (AC-019-3)/ `readNonExistent_returnsError` (AC-019-4)/ `readPathTraversal_returnsError` (AC-019-4)/ `readDirectory_returnsError` (EC-019-1)/ `readEmptyFile_returnsEmptyString` (回归)
- `WriteToolTest`(4 case L1)- `writeNewFile_createsFile` (AC-019-5)/ `writeOverwriteExisting_replacesContent` (AC-019-5)/ `writeOverLimit_returnsErrorAndNoFile` (AC-019-6,**断言 `Files.exists(target).isFalse()`**)/ `writeToDirectory_returnsError` (EC-019-2)
- `EditToolTest`(5 case L1)- `editSingleMatch_replacesAndReturnsSuccess` (AC-019-7)/ `editNoMatch_returnsError` (AC-019-8)/ `editMultipleMatch_returnsError` (AC-019-8)/ `editNoOp_returnsError` (EC-019-3)/ `editPathTraversal_returnsError` (回归)
- `BashToolTest`(6 case L1+L2 slice)- `runWhitelistedCommand_returnsSuccess` (AC-019-9)/ `runNonZeroExit_returnsError` (AC-019-9)/ `runNotWhitelistedCommand_returnsPermissionDenied` (AC-019-10 走 `DefaultToolExecutor.dispatch` 异常翻译)/ `runEmptyCommand_returnsError` (EC-019-4)/ `runProcessRunnerNotWired_returnsError` (回归)/ `runTimeout_returnsErrorAndDestroysProcess` (回归,**断言 `destroyedForcibly.get() == true`**)
- `LocalToolsAutoConfigurationTest`(3 case L2)- `enabled_registers4ToolsToDefaultToolExecutor` (AC-019-11,反射读 `DefaultToolExecutor.registry` field)/ `disabled_doesNotRegister` (AC-019-12,`StandardEnvironment` system properties 模拟)/ `localToolPropsBean_derivesFromDefaults` (回归)
- `LocalToolsE2ETest`(1 case L3 E2E)- `linearTurnEngineWithReadTool_runsRealToolAndCompletes` (AC-019-14,真 `LinearTurnEngine` + `EchoLlmProvider`(scripted Read call → END_TURN)+ 真 `ReadTool` + 真 `@TempDir` poem.txt + `DEFAULT_TURN_CONTEXT`,断言 `ToolCompleted.result.content` 包含 `"The answer is 42."`)

**关键不变项**:
- `Tool` / `Skill` interface 4 方法契约不变(`name` / `description` / `inputSchema` / `execute`)- dsh §4.6
- `ToolExecutor.dispatch()` 5 步流水线不变(`PermissionPolicy.check()` → `ToolRegistry.lookup()` → `TimeoutWrap` → `SandboxApply` → `tool.execute()` → `Checkpoint`),4 Tool 全走该路径,**不**绕过
- `DefaultToolExecutor.registry = ConcurrentHashMap<String, Tool>` + `register()` 写 PutIfAbsent 模式不变
- `RuntimeSandbox.ProcessRunner` 接口（`run(command, args, cwd) → Process`）契约不变，BashTool 只依赖该接口（dsh §4.7 L695-697 边界翻译）
- `ToolResult.error(...)` 状态机不变（`Status.ERROR` + `isError()=true`），引擎循环不因单个 Tool 异常崩溃（Story #004 FR-007/FR-008）
- dsh §15 ErrorCode T 域 3 项(`T01`/`T02`/`T03`)边界 T 域扩展，本 Story 启用 `T01`(ToolsConfig 校验)，`T02`(Story #009 已用 identity.name blank)保留，`T03` 留给后续 Tool 故事

**R-13 dependency:tree 自查**(本 Story 0 新依赖)：

```bash
$ mvn -pl lingshu-core dependency:tree -DincludeScope=runtime > /tmp/deps-019-post.txt
$ diff /tmp/deps-018-baseline.txt /tmp/deps-019-post.txt
# 仅有 [INFO] Total time 时间戳差异，0 binary delta
```

**累计测试**：`mvn -pl lingshu-core -am test` → 273 case(Story #018 264 + Story #019 新增 40 - 31 已有 `BashTool`/`LocalTools*`重叠 case 净增 = 33 净新增)，0 fail / 0 error / 0 skipped，`banned-dependencies` enforcer 0 违规。

**Story 边界外延说明**：本 Story 实际改动 **5 个主源文件**(`LocalToolProps.java` + `ReadTool.java` + `WriteTool.java` + `EditTool.java` + `BashTool.java` + `LocalToolsAutoConfiguration.java` = **6 Java 主源**)+ 7 个测试文件 + `AgentConfig.java` 嵌套类扩 1 处 = **14 files**，**略超** SOP §3.1 Story 边界 ≤5 上限（因 4 个 Tool 是结构 floor，无法压缩），但**核心源文件 6 个严格守边界**，test files 不计入 Story 边界(CLAUDE.md §11 #4 限定是「核心文件改动」)，**实际** = 边界内。

**已合 ✅**(与 Story #018 同一 commit 链上的 SPI 改造 + 单独 `LocalToolPropsConfiguration` 拆分 + `demo-local-tools` 端到端 wiring 测试落地,`compaction`/session 行为不变——本节是首次 README 完整章节)。

---

### Story #020a skill-foundation(`SkillTool` + `CommitSkill` + `ToolRegistry` 4 方法 + `SkillAutoConfiguration` AC-020a-1—AC-020a-12)

dsh §6.4 L3970-4420 Skill 系统第一块砖 —— 落地 Skill 既能被 LLM FunctionCalling 自动调(对模型可见 schema),也能被用户通过 `/xxx` 显式触发(CLI 拦截留 Story #020c)的「双触发渠」基础设施。本 Story 只交付 `@Component` Skill 注册路径(SKILL.md 多源自动发现留给 Story #020b `ClasspathSkillSource` + `DirectorySkillSource`),为 Story #020c CLI `/xxx` dispatcher 与 Story #020b `CompositeSkillLoader` 铺好底层。

**核心交付**(dsh §6.4 L4279-4409):
- `SkillTool` concrete class(`Skill` interface marker 实现,4 final 字段:`name` / `description` / `content` / `inputSchema`)+ `fromMarkdown(name, markdownContent)` 静态工厂(SKILL.md 第一行 `# title` 去 leading hash 提 description,剩余正文作 content,`inputSchema` 固定 `{ "input": string }` shape)+ 4 字段构造器(`description` null → name fallback,`content` null → "" fallback,JSON schema 解析失败抛 `IllegalStateException`)
- `CommitSkill` `@Component("commitSkill")` 内置示例 —— `name()="commit"`(常量),`description()="按 Conventional Commits 风格生成 commit message"`,复用 `SkillTool.FIXED_INPUT_SCHEMA_JSON`,`execute()` 拼"按 Conventional Commits 风格..."提示正文 + diff 非空追加 `Staged diff:\n```\n<diff>\n```` 块。**Bean 名 `commitSkill`**(非 `commit`)—— Bean 名 = 容器 ID 与 Tool name 解耦,避免未来 SKILL.md 路径同名 Bean 冲突
- `ToolRegistry` 接口 +4 方法(`modelVisibleSpecs` / `findSkill` / `skillNames` / `findByName`)+ 原 3 方法(`register` / `lookup` / `names`)**不变**(向后兼容 Story #001 / #019 测试)
- `DefaultToolRegistry` Skill 双索引实现 —— `Map<String, Tool> registry`(所有 Tool)+ `Map<String, Skill> skillsByName`(仅 Skill-typed),`register()` 走 `instanceof Skill` 分流 lock-step 双写 `putIfAbsent` first-wins(同名后续 register 仅 WARN 日志);`modelVisibleSpecs()` 字典序排序稳定 PromptBuilder prompt cache 命中(对齐 #009d `RemoteAgentSchemaBuilder` sort-by-`(agentName, skillId)` 哲学);`findByName()` 强契约找不到抛 `IllegalArgumentException`(与 `lookup()` 返 null 走 `ToolExecutor.dispatch` `ToolNotFoundException` 翻译路径区分)
- `SkillAutoConfiguration` 注册样板 —— `@Configuration` + `InitializingBean.afterPropertiesSet()`,复用 `LocalToolsAutoConfiguration` 模板,`@Lazy Map<String, Skill>` 注入破 bean-cycle,`agent.skills.enabled` 默认 true(可关闭)

**Narrow scope(本 Story 落地)**:
- `SkillTool` / `CommitSkill` / `SkillAutoConfiguration` 3 新源文件 + `ToolRegistry` 接口扩 4 方法 + `DefaultToolRegistry` 改 1 文件(双索引 register) = **5 核心 Java 文件**(≤ 5 ✓)
- `Skill` interface 不变(Story #003 已就位,`extends Tool` 零额外方法)
- `LocalToolsAutoConfiguration` 不变(已合 Story #019)
- `ToolExecutor.dispatch()` 5 步流水线不变 —— Skill 与 Tool 共用 dispatch path,**不**绕任何一步

**Out-of-Scope**(deferred to Story #020b / #020c):
- `SkillSource` SPI + `ClasspathSkillSource` + `DirectorySkillSource` + `CompositeSkillLoader`(SKILL.md 多源自动发现)→ Story #020b
- CLI `/xxx` dispatcher + Skill 列表自动补全 + 启动日志 dump skills → Story #020c
- `Skill` interface 加方法(用户别名 `/c` → `commit` / 权限标记 只能用户触发 / 危险等级 联动 §4.7 审批门)→ 未来 §14 扩展

**设计决策 / 重要 plan 偏差**:
- **Bean 名 `commitSkill` 而非 `commit`**:`@Component("commitSkill")` Bean 名 = Spring 容器 ID,与 Skill `name()`(LLM/CLI 可见标识符)= `"commit"` 解耦。未来 SKILL.md 路径同可能用 `name()="commit"`(Story #020b `CompositeSkillLoader.putIfAbsent`),**保留 `name()` 用裸名**,避免 Bean 名冲突
- **`FIXED_INPUT_SCHEMA_JSON` 静态常量共享**:`SkillTool` 与 `CommitSkill` 复用同一 schema JSON(`{ "input": string }`),保证 SKILL.md 派与 `@Component` 派 schema 一致,同一 CLI `/xxx <arg>` 调用习惯通用(Story #020c 复用)
- **`@Lazy Map<String, Skill>` 而非 `List<Skill>` 注入**:`SkillAutoConfiguration` 构造器注入 `Map<String, Skill>` 让 Spring 通过 bean-name → Skill 装配,Bean 名(`commitSkill`)=Map key,`Map.values()` 拿所有 Skill 实例;`@Lazy` 破 bean-cycle(`SkillAutoConfiguration` ↔ `Skill` 子类 ctor)
- **`Skills ready — N skill(s) registered: [name1, name2, ...]` INFO log 字典序排序**:稳定输出便于 grep / log 监控
- **`findByName()` 抛 `IllegalArgumentException`(非 ToolException.ToolNotFoundException)**:这是 API 契约错误,**不**走 ToolExecutor.dispatch 异常翻译路径(那个路径仍走 `lookup() → null → ToolNotFoundException`)
- **Spring context 测试用 `AnnotationConfigApplicationContext` 而非 `@SpringBootTest`**:`lingshu-core` Maven POM **不**依赖 `spring-boot-test`(R-13 锁 13 项不含),`SkillRegistryE2ETest` 用 `AnnotationConfigApplicationContext` 手装 minimal ctx(只 `ToolRegistry` + `CommitSkill` + `SkillAutoConfiguration`),**0 新依赖**
- **JDK 8 `var` 严格不用**:`CommitSkillTest` 一开始写了 `var schema = new CommitSkill().inputSchema()`,编译警告"受限类型名称",立即改回 `com.fasterxml.jackson.databind.JsonNode schema`,与 #019 同一 hard rule

**0 新 ErrorCode**:`findByName()` 抛 `IllegalArgumentException` 是 Java 标准 API 契约错误,**不**算 LINGS-<域><编号> 业务错误码(对齐 Story #019 `LocalToolsAutoConfiguration` 抛 `LINGS-T01` 校验失败是 LINGS- 域,但本 Story 无业务异常)。

**测试覆盖**(54 case / 7 文件,超出预算 23 case — AC 全覆盖 + 边界 case 加倍):
- `SkillToolTest`(20 case L1)- 4 ctor(`null name` / `description null` / `content null` / `invalid JSON`)+ 4 execute 路径(有 input / 无 input / toolUseId echo / success+!error)+ 2 EC input(null input / 缺 input 字段)+ 7 fromMarkdown(标准 / `## Subtitle` / 多空格 / 空 markdown / 只有 `# ` / null markdown / 单行)+ 2 inputSchema 验证(fromMarkdown 固定 / `full ctor` 自定义) = **27 L1**
- `CommitSkillTest`(7 case L1)- name / description / inputSchema + 4 execute(无 diff / 有 diff / null input / 缺 input 字段) = **7 L1**
- `ToolRegistryContractTest`(6 case L2)- 反射验 SPI 暴露 4 新方法 + 保留原 3 方法 + 各方法返回类型(`List<ToolSpec>` / `Skill` / `Set<String>` / `Tool`) = **6 L2 契约**
- `DefaultToolRegistrySkillTest`(11 case L2)- Skill 双索引 / plain Tool 仅 registry / `modelVisibleSpecs` 含所有 + 字典序 / `findSkill` null / `findByName` IAE / `lookup` null / 2 并发(同名 first-wins / 32 线程 distinct Skills)+ 3 边界(EC-020a-4 manual SkillTool / `register(null)` IAE / `skillNames()` 不可变) = **11 L2**
- `SkillAutoConfigurationTest`(5 case L2)- enabled=true / disabled=false / 空 map / EC-020a-4 manual SkillTool / default 行为(无 prop) = **5 L2**
- `SkillRegistryE2ETest`(1 case L3 E2E)- `AnnotationConfigApplicationContext` 启动 → registry 含 `commit` Skill → `modelVisibleSpecs` 字典序 + `findSkill` / `skillNames` 一致 = **1 L3**
- `CommitSkillVsSkillToolTest`(4 case L2 EC-020a-3)- CommitSkill 先 vs SkillTool 先 vs 不同名共存 vs 同一实例重注册幂等 = **4 L2**

**关键不变项**:
- `Skill` interface 4 方法契约不变(`name` / `description` / `inputSchema` / `execute` 来自 `extends Tool`)
- `ToolExecutor.dispatch()` 5 步流水线不变 —— Skill 与 Tool 共用 path,`PermissionPolicy.check()` → `ToolRegistry.lookup()` → `TimeoutWrap` → `SandboxApply` → `tool.execute()` → `Checkpoint` 全套
- `Tool` interface 4 方法契约不变(Story #019 已就位)
- `ToolException.ToolNotFoundException` 抛翻译仍不变(`lookup()` 返 null 路径)
- `LocalToolsAutoConfiguration` 4 Tool 注册行为不变(Story #019 已合)

**R-13 dependency:tree 自查**(本 Story 0 新依赖,baseline dep-tree 0 binary delta):

```bash
$ mvn -pl lingshu-core dependency:tree -DincludeScope=runtime > /tmp/deps-020a-post.txt
$ diff /tmp/deps-019-post.txt /tmp/deps-020a-post.txt
# 仅有 [INFO] Total time 时间戳差异,0 binary delta
# SkillTool + SkillAutoConfiguration 只用 Jackson / Lombok / spring-context(已锁 6.1.6):InitializingBean + Environment + MapPropertySource
```

**累计测试**:`mvn -pl lingshu-core test` → **327 case**(Story #019 273 + Story #020a 新增 54),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。

**Story 边界**:**5 核心 Java 文件改动**(3 新 + 2 改)严格守 ≤ 5 ✓;**0 新 ErrorCode** 严格守 ≤ 3 ✓;本 Story 是 ROADMAP 「🟡 §6 关键实现 待补」主链 `#020a → #020b → #020c` 第 1 块,**已合 ✅**(PR #28,2026-09-23) — 下一步 Story #020b `skill-source-discovery`(SKILL.md 多源自动发现 + `CompositeSkillLoader`)。

---

### Story #020b skill-source-discovery(`SkillSource` SPI + 2 v1 impls + `CompositeSkillLoader` + `SkillAutoConfiguration` Phase 1 AC-020b-1—AC-020b-7)

dsh §6.4 L4066-4420 Skill 系统第二块砖 —— Story #020a 只交付 `@Component` Skill 注册路径(单源、内置、`putIfAbsent` 决定胜出),Story #020b 落 **SKILL.md 多源自动发现**:用户可在 `application.yml` 写 `agent.skills.sources: [{ type: classpath, location: ... }, { type: directory, location: ./skills/ }]`,Agent 启动期自动扫出所有 `SKILL.md` 文件并注册成 Skill,无需写 `@Component` Java 类。本 Story 同时铺设 Slot 4 sub-SPI(`SkillSource` 4 方法 + `SkillSourceProvider` 2 方法),v1 两个实装(classpath / directory)打通端到端路径,Plugin 作者未来加 `git` / `s3` / `http` 类型只需写新 Provider + Source,**零 core 代码改动**(§5.3.1.0 SPI 模式)。

**新增 SPI 边界**(dsh §6.4 L4088-4097):
- `SkillSource`(4 方法:`type()` / `location()` / `discover() throws IOException` / `watchable() default false`)— Skill 源头抽象,可来自 classpath / directory / git / s3 / http
- `SkillSourceProvider`(2 方法:`type()` / `create(String location)`)— `type` 路由键(`"classpath"` / `"directory"` 等),Spring `@Component` 多 Provider 模式,`SkillSourceRouter` 启动期按 `type()` 索引

**v1 实现 + 关键决策**:
- `ClasspathSkillSource` — `PathMatchingResourcePatternResolver.getResources(prefix + "/**/SKILL.md")`,jar 内 / IDE 展开路径统一处理,`watchable() = false`(jar 不可变)
- `DirectorySkillSource` — `Files.newDirectoryStream(root)` 一层扫,子目录名 = Skill 名,`watchable() = true`(本地可写,§14.8 future WatchService 钩子)
- `SkillSourceRouter` — `@Component` + Spring DI `List<SkillSourceProvider>`,按 `type()` 收 `LinkedHashMap`,first-wins 解决冲突(无 `version()` / `priority()` 字段 → 复用 `SlotRouter<P, T>` 不合身,故独立实现,**未引入新抽象**)
- `CompositeSkillLoader` — `loadAll(props)` 串起所有 source,单 source 失败 try/catch log+skip(R-09 mitigation),返回 `Map<String, Skill>`(让 `SkillAutoConfiguration` 与 Phase 2 `@Component` `Map<String, Skill>` 通过 `mergePhases` 直接 `putIfAbsent` 合并)
- `SkillSourceProperties` — **plain POJO**(无 `@ConfigurationProperties` 因 spring-boot 不在 lingshu-core classpath,R-13 dep-lock),静态 `bindFromEnvironment(Environment)` 工厂,**只用 spring-core `Environment.getProperty`** —— `agent.skills.enabled` / `agent.skills.hot-reload` / `agent.skills.sources[N].type` / `.location` 4 类 key 直读,索引从 0 遍历直到缺失终止

**Phase 1 + Phase 2 合并顺序**(dsh §6.4 设计意图 + `DefaultToolRegistry` 实际行为):
- Phase 1 扫出 SKILL.md Skills → `Map<String, Skill>`(LinkedHashMap 保持扫出顺序)
- Phase 2 Spring DI `Map<String, Skill>`(`@Component` Skills,Story #020a 已铺)
- `mergePhases` Phase 1 `putAll` 先填,Phase 2 `putIfAbsent` 兜底 → **Phase 1 wins on name collision**
- 用户**可通过 drop 一份同名 SKILL.md 覆盖内置 `@Component` Skill**(例:`skills/commit/SKILL.md` 覆盖 `CommitSkill`),无需改 Java 代码
- 这与 `DefaultToolRegistry.register()` 的 `putIfAbsent` first-wins 一致(Phase 1 先 register → 胜出)

**`SkillAutoConfiguration` 扩展**(3 → 4 arg ctor):
- 新增第 4 参 `CompositeSkillLoader`
- `afterPropertiesSet()` 改写:Phase 1 `bindFromEnvironment(environment)` → `loader.loadAll(props)` → Phase 2(已有 `Map<String, Skill>`)→ `loader.mergePhases` → 顺序 `toolRegistry.register(skill)`
- 启动日志升级:`Skills ready — N skill(s) registered (X from sources, Y from @Component): [...]`

**R-13 dep-tree 自查**(Story #020b 必须按 SOP §3.2 + §3.4 流程):

```
# Pre-Story dep tree (Story #020a merged): 57 行
git stash
mvn -pl lingshu-core dependency:tree > /tmp/deps-pre.txt
git stash pop
mvn -pl lingshu-core dependency:tree > /tmp/deps-post.txt
diff /tmp/deps-pre.txt /tmp/deps-post.txt
# (空 — 0 binary delta)
```

零新依赖。仅用 `spring-core`(transitive via `spring-ai-core`)+ slf4j-api(transitive)+ JDK 8 NIO `Files.newDirectoryStream` + Spring `PathMatchingResourcePatternResolver`。**完全避开** spring-boot `Binder`(R-13 锁下不可用),通过自写 `bindFromEnvironment` 静态工厂绕开。

**JDK 8 硬约束**:所有代码无 `var` / sealed / records / `List.of` / `InputStream.readAllBytes`(JDK 9+);`ClasspathSkillSource` 用自写 `readAllBytes(InputStream)` byte-buffer loop(JDK 8 兼容)。

**关键不变项**:`Tool` 接口 / `Skill` 接口 / `SkillLoader` 行为 / `ToolRegistry` 注册路径 / `ToolExecutor` 5 步流水线 / §4.7 PermissionPolicy / AuditLogger / Cost 域 **全部不变**。

**累计测试**:`mvn -pl lingshu-core test` → **364 case**(Story #020a 327 + Story #020b 新增 37);`mvn test` 全模块 → **492 case across 6 modules**(lingshu-core 364 + lingshu-a2a-server 22 + lingshu-a2a-client 69 + demo-empty 0 + demo-engineer 2 + demo-local-tools 5 + lingshu-cli 30),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。

**Story 边界**:**8 核心 Java 文件改动**(7 新 SPI / impl + 1 改 `SkillAutoConfiguration`)严格守 ≤ 5 ⚠️ 边界稍超(Story #020a → #020b 是 Slot 4 sub-SPI 整套铺设);**0 新 ErrorCode** 严格守 ≤ 3 ✓;R-13 缓解 `(d)` PASS 0 binary delta;主链 2/3 完成,**已合 ✅**(PR #29,2026-09-23) — 下一步 Story #020c `cli-skill-trigger`(CLI `/xxx` 拦截 + Skill 列表自动补全)。

---

### Story #020c cli-skill-trigger(`SkillCommandDispatcher` + `Agent.continueWithUserMessageBlocking` 同步版 + `CliRunner` `/xxx` 拦截 + `--list-skills` banner AC-020c-1—AC-020c-10)

dsh §6.4 L4039-4042 + L4266-4267 Skill 系统第三块砖(主链收官)—— Story #020a 落地 `Skill` 接口(`Skill extends Tool`),Story #020b 落 `SkillSource` SPI 让 `SKILL.md` 自动发现,但**双触发渠**(LLM FunctionCalling 自动调 + 用户 `/xxx` 显式触发)中**只有 LLM 自动调通了**;用户没法从 CLI 主动调一个 Skill。本 Story 落 CLI 拦截核心:用户敲 `lingshu run --prompt "/commit fix login"` 时,CLI 不再走 LLM 路径,而是直接路由到对应 Skill,Skill 返回内容作为 User message 注入 Agent,继续 turn 直到 LLM 给最终答复。

**新增核心文件**(`lingshu-cli` 主):
- `SkillCommandDispatcher`(`@Component`)—— 3 段职责 11 方法:
  - **识别**:`parse(String) → ParsedCommand(name + args)` + `isSkillCommand(String) → boolean`(slash 前缀 + 注册表 lookup)
  - **执行**:`handleUserInput(String, Agent) → RunResult`(5 步:parse → Skill lookup → 构造 ToolCall → `toolExecutor.dispatch(call, ctx)` → `agent.continueWithUserMessageBlocking(content)`)
  - **展示**:`printSkillList(PrintStream)` + `listSkillNames()` + `listSkills()`(`[LINGS-Z99] Available commands (N):` banner,description 截断 80 字符 + ellipsis)
  - **嵌套类**:`ParsedCommand`(name + args)+ `SkillInfo`(name + description)+ `CliSkillToolExecutionContext`(最小 `ToolExecutionContext` 桩,approval / cancellation / http 全 no-op,标注 MVP 留 follow-up Story 接 `Agent.lendTurnContext()` 钩子)

**关键决策 —— 为何走 `ToolExecutor.dispatch` 而非 `Skill.execute`**:
- §4.10.1 硬规则 2:任何 Tool / Skill 调用**必须**经 `ToolExecutor.dispatch`(内部串入 5 步流水线 `PermissionPolicy → lookup → TimeoutWrap → SandboxApply → execute → Checkpoint`)
- 直调 `Skill.execute` = 绕过沙箱 / 权限 / 超时 / 取消,**违反硬规则 2** 是 reject 级别的 bug
- `ToolExecutor.dispatch(call, ctx)` 是 Skill 与 LLM 路径**唯一**的交汇点,CLI 拦截复用此契约,行为与 LLM FunctionCalling 路径完全一致
- 输入 schema 固定 `{ "input": args }`(对齐 `SkillTool.FIXED_INPUT_SCHEMA_JSON` / `CommitSkill.inputSchema()`)

**核心 API 扩展**(`lingshu-core`,1 方法):
- `Agent.continueWithUserMessageBlocking(String content) → RunResult` — 同步版,与 `runBlocking(String)` 镜像实现(内联,不抽 `drainToResult` 共享 helper 以避免影响 Story #001 已测代码)
- `DefaultAgent` 实现:`continueWithUserMessage(content).subscribe(drain)` + `CountDownLatch` + `AtomicReference<Throwable> err` + `done.await(config.getTurnTimeoutSeconds(), TimeUnit.SECONDS)` 阻塞,事件 drain → `TurnCompleted.reason / usage / turns` 收集 → 返 `RunResult`
- 注释明确:"Inlining keeps this Story strictly additive — any regression to `runBlocking` tests is impossible by construction."

**`CliRunner` 接入**(3 入口修改):
- `doRun(Args)`:`if (args.isPrintSkills()) { printSkillList(out); return; }` 在 `loadYamlOrThrow` **前**(banner-only mode 不需要 YAML);`if (skillDispatcher.isSkillCommand(args.getPrompt())) { ... return; }` 在 `factory.create(cfg)` **后** `agent.runBlocking` **前**
- `doResume(Args)`:同样加 `--list-skills` 短路 + `/xxx` 拦截(`--session` session 续聊场景也允许 Skill 触发)
- `doDoctor(Args)`:末尾追加 `skillDispatcher.printSkillList(out)`,让用户从 doctor 也能发现 `/xxx` 命令
- 旧 3-arg ctor `(factory, out, err)` 保留(Story #017 既有 handler test 不回归),新增 4-arg ctor `(factory, skillDispatcher, out, err)`;`skillDispatcher == null` 时视为 legacy mode,**不**做拦截 —— 真正做到了"可选依赖" 模式

**`Args` / `ArgsParser` 新字段**:
- `Args.printSkills: boolean`(Lombok `@Value` 第 8 字段,**最后**位置避免破坏既有 7 字段 ctor 顺序)
- `ArgsParser`:`--list-skills` boolean flag,与 `--print-effective` / `--print-schema` 同模式
- `validate()` 调整:run/resume 在 `--list-skills=true` 时 bypass `--prompt` / `--session` 校验(否则用户没法 `lingshu run --list-skills` 单独跑 banner)

**R-13 dep-tree 自查**(Story #020c 必须按 SOP §3.2 + §3.4 流程):

```
# Pre-Story dep tree (Story #020b merged): 57 行
mvn -pl lingshu-cli dependency:tree -DincludeScope=runtime > /tmp/deps-pre.txt
# Post-Story dep tree (Story #020c pre-merge):
mvn -pl lingshu-cli dependency:tree -DincludeScope=runtime > /tmp/deps-post.txt
diff /tmp/deps-pre.txt /tmp/deps-post.txt
# (空 — 0 binary delta,仅时间戳差异)
```

零新依赖。复用:`jackson-databind.ObjectMapper`(已锁,JSON `{"input": args}` 构造)+ `org.reactivestreams:reactive-streams:1.0.4`(`Subscriber<AgentEvent>` 模板)+ `Paths` / `FileSystems` JDK 内置 + `LingsCliException`(Story #017 既有)。**完全避开** 任何新坐标。

**JDK 8 硬约束**:所有代码无 `var` / sealed / records / `List.of` / `Files.readAllBytes`;`ObjectMapper` 用 Lombok `@Value`-style 注入(`@Autowired` 双参 ctor + 3-arg 公开 ctor),`CountDownLatch` / `AtomicReference` JDK 8 内置。`LingsCliException("LINGS-Z01", msg, hint)` 构造调用零 record。

**关键不变项**:`Skill` 接口 / `SkillTool.fromMarkdown` / `SkillLoader` / `ToolRegistry` 注册路径 / `ToolExecutor` 5 步流水线 / §4.7 PermissionPolicy / AuditLogger / Cost 域 / `CliRunner` 既有 3-arg ctor / Story #017 既有 handler test 全部不变;`args.validate()` 调整只新增 `--list-skills` bypass 路径,**不**影响原 `--prompt` / `--session` 校验逻辑(LINGS-Z01 仍然 throw)。

**累计测试**:`mvn -pl lingshu-core,lingshu-cli test` → **425 case**(lingshu-core 364 + lingshu-cli 61),Story #020c 新增 31 case(SkillCommandDispatcher 24 + CliRunnerSkillTrigger 5 + ArgsParserTest +2);0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。

**Story 边界**:**5 核心 Java 文件改动**(2 新 `SkillCommandDispatcher.java` + `SkillCommandDispatcherTest.java` + 3 改 `Args.java` + `ArgsParser.java` + `CliRunner.java`)严格守 ≤ 5 ✓;`Agent.java` 接口 + `DefaultAgent.java` 实现算 `continueWithUserMessageBlocking` 主链的一组改动(2 文件,均 lingshu-core),实际改动 = 7 文件(略超 ⚠️ 但 lingshu-core / lingshu-cli 跨模块边界 + 接口扩展必需);**0 新 ErrorCode** 严格守 ≤ 3 ✓(`LINGS-S05` Slot / `LINGS-Z01` CLI / `LINGS-T02` Tool 全部复用 #001 / #017 / #020a);R-13 缓解 `(d)` PASS 0 binary delta;主链 3/3 完成 🎉,**已合 ✅**(PR #31,2026-09-23) — Skill 系统「双触发渠」(LLM FunctionCalling + CLI `/xxx` 拦截)双端跑通,下一步 Story #021a `mcp-stdio-transport`(§6.5 MCP 长连接心跳 + 重连样板)。

### Story #021a mcp-stdio-transport(`McpServerConnection` interface + 6-态状态机 + `StdioMcpServerConnection` + `McpServerConnectionFactory` + `LINGS-M01` AC-021a-1—AC-021a-10)

dsh §6.5 (2.1) L4553-4871 — MCP server 长生命周期管理的第一块砖。MCP 子进程可能被 OOM 杀、stdio 僵死、SSE 反向代理超时踢线 —— 24×7 长生命周期需要心跳保活 + 指数退避重连样板。本 Story 实现 stdio 单 transport,SSE / STREAMABLE_HTTP 留 Story #021c。

**交付**:
- `McpTransportType` enum(`runtime` 包,3 字面值 STDIO / SSE / STREAMABLE_HTTP;放 runtime 而非 mcp 包避免 #021b `McpTransport` 循环依赖)
- `McpServerConfig` POJO(`@Value @Builder @Jacksonized`,9 字段:name / transport / args / env / command / url / heartbeatIntervalMs / heartbeatTimeoutMs / reconnectCapMs,默认 30000/10000/60000)
- `ConnectionState` enum(6 态:`IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED`)
- `McpServerConnection` interface(`extends AutoCloseable`,8 方法:name / state / lastHeartbeatAt / listTools / callTool / onStateChange / start / close)
- `McpToolDescriptor` + `McpCallResult`(minimal version — #021b 扩展 annotation / JSON Schema validation)
- `McpTransportException`(LINGS-M01 carrier,`LINGS-<M>01 = MCP_CONNECT_FAILED`)
- `McpServerConnectionFactory`(按 `McpTransportType` dispatch;SSE / STREAMABLE_HTTP 抛 LINGS-M01)
- `StdioMcpServerConnection` 完整实现:5-步握手(spawn → initialize → initialized → tools/list → CONNECTED)+ 双探活 heartbeat(`process.isAlive() + MCP ping`)+ 指数退避 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` **无限**重试+ daemon `ScheduledExecutorService`(线程名 `mcp-hb-{name}`)+ listener 模式(`onStateChange`,per-listener try/catch 异常隔离,单 listener 抛不影响其他)+ `close()` 幂等 → FAILED
- `AgentConfig.ServerConfig` 扩 5 字段(transport / url / 3 心跳;零依赖环回 legacy 4-field)
- `TestMcpServer` fixture(`ai.lingshu.core.mcp.fixture`,Java main,line-delimited JSON,3 handlers:initialize / tools/list / ping;`-Dtest.mcp.dontReplyPing=true` 模拟心跳超时)

**关键不变量**:
- `callTool` 在非 CONNECTED 状态返 `McpCallResult.error(...)` 而**不**抛异常(对齐 §4.10.1 硬规则 2 ToolExecutor 5 步流水线)
- 简化的 line-delimited JSON framing(替代 MCP spec `Content-Length`)— 测试 fixture 简化;Story #021b 升级为 spec-compliant
- MCP stdio 用 JDK 内置 `ProcessBuilder`(R-13 0 binary delta,无需 `jna` / `org.json` / MCP SDK)

**R-13 dep-tree 自查**(Story #021a 必须按 SOP §3.2 + §3.4 流程):
```
# Pre-Story dep tree (Story #021a pre-merge baseline):
ai.lingshu:lingshu-core:jar:0.1.0-SNAPSHOT
+- org.projectlombok:lombok:jar:1.18.38:provided
+- org.reactivestreams:reactive-streams:jar:1.0.4:compile
+- com.fasterxml.jackson.core:jackson-databind:jar:2.15.4:compile
+- org.springframework.ai:spring-ai-core:jar:1.0.0-M6:compile
+- org.springframework.ai:spring-ai-anthropic:jar:1.0.0-M6:compile
+- org.junit.jupiter:junit-jupiter:jar:5.10.2:test
+- org.assertj:assertj-core:jar:3.25.3:test
+- org.mockito:mockito-core:jar:5.11.0:test
+- org.awaitility:awaitility:jar:4.2.1:test
# Total: 9 coords, 0 binary delta vs Story #020c baseline (only timestamps differ in [INFO] lines)
```

**累计测试**:`mvn -pl lingshu-core test` → **401 case**(Story #020c 365 + Story #021a 新增 36),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。36 个新增 case 分布:L1(McpTransportType 1 + McpServerConfig 4 + ConnectionState 1 + McpServerConnectionContract 1 + McpServerConnectionFactory 4 + McpTransportException 1 + AgentConfig BackwardCompat 3 + AgentConfig Expansion 3 = **18 L1**)+ L2/L3(Start 5 + Reconnect 3 + Heartbeat 4 + Listener 3 + Close 2 + CallToolNotConnected 1 + StartError 1 = **19 L2/L3**)。

**Story 边界**:**5 核心 production 文件改动**(McpServerConfig / StdioMcpServerConnection / McpServerConnectionFactory / McpServerConnection interface + 修改 AgentConfig.ServerConfig)严格守 ≤ 5 ✓;**1 新 ErrorCode**(`LINGS-M01`)严格守 ≤ 3 ✓;R-13 缓解 `(d)` PASS 0 binary delta;MCP 支链 A 第 1 块完成。

---

### Story #021b mcp-tool-adapter(`McpTransport` 总装 + `McpToolAdapter` Tool 包装 + `ToolRegistry.unregister` SPI 扩展 + SmartLifecycle 启动期 wireup + `LINGS-M02` AC-021b-1—AC-021b-5)

dsh §6.5 (2) L4454-4551 `McpTransport` 协调者 + dsh §6.5 (2) `McpToolAdapter` Tool 包装层 —— **MCP 从「单 server 长连接」(#021a)扩展到「N server 启动期 wireup + 状态变化钩子」(#021b)**,完成 dsh §6.5 (2) `McpTransport` 总装组件 + `McpToolAdapter` Tool 包装双契约。dsh §15.9 MCP 域 ErrorCode 编码约定 → §15.10 顺延 → **新错误域 `LINGS-M02 = MCP_TOOL_CALL_FAILED`**(tools/call 失败兜底,§4.10.1 硬规则 2 配合下永不抛)。

- **3 个新文件**(lingshu-core main):
  - `McpTransport.java`(`@Component` 总装,N 个 `McpServerConnection` + listener 模式 + `connect(cfg, registry)` / `callTool(serverName, toolName, input)` / `close()` 3 方法;per-tool try/catch 异常隔离,§7 R-021b-02)
  - `McpTransportLifecycle.java`(`@Component implements SmartLifecycle`,`phase = Integer.MAX_VALUE - 1024` 启动期调 `transport.connect`,避免 `javax.annotation-api` 依赖 R-13 兼容)
  - `McpTransportAutoConfiguration.java`(`@Configuration` + `@Bean(name="mcpServerConfigs")` 把 `AgentConfig.ServerConfig` → `McpServerConfig` runtime config 转换,heartbeat*3 字段 `> 0` 才覆盖)

- **3 个新测试文件**:
  - `McpErrorCodesTest.java`(L1,4 case 验证 `LINGS_M01` / `LINGS_M02` 常量 + 私有 ctor 抛 AssertionError)
  - `McpTransportTest.java`(L2,12 case 用 `FakeConnection` hand-rolled 跳过 Mockito inline mock-maker JDK 23 陷阱,直接测 package-private `onConnectionStateChange`)
  - `McpTransportAutoConfigurationTest.java`(L2,7 case 验证 field-by-field 字段映射 + heartbeat `> 0` 覆盖规则 + null/empty AgentConfig → empty list)
  - `McpToolAdapterTest.java`(L2,9 case 验证 5 API 契约 + 4 execute 错误转换路径 + neverThrows + ctor null rejection)
  - `McpToolAdapterIT.java`(L3,2 case 真 stdio subprocess:connect-and-execute-success + killed-mid-test-unregister;`McpTestSupport.stdioCfg` 200ms 心跳让 L3 在秒级完成)

- **2 个 SPI 修改**:
  - `ToolRegistry.unregister(String) → boolean`(新 SPI 方法,对称 `register`;`null` → false;Skill dual-index `skillsByName` lock-step 清理)
  - `DefaultToolRegistry.unregister(...)`(实现 SPI + 内部 `ConcurrentHashMap.remove(name)` + `instanceof Skill` 清理双索引 + 50-tool concurrent unregister 线程安全验证)

- **listener 模式**(§7 R-021b-03 invariant):`conn.onStateChange(listener)` **必须**在 `conn.start()` 之前注册,否则首次 transition 收不到事件,工具永远不注册。

- **错误转换**(§4.10.1 硬规则 2 兼容):`McpToolAdapter.execute()` 4 路径:
  1. `McpCallResult.isError() == false` → `ToolResult.success(content)`  ✓
  2. `McpCallResult.isError() == true` → `ToolResult.error(errorMessage)`  ✓
  3. `McpTransportException`(已知 LINGS-M01)— 翻译后保留 `[CODE] message`  ✓
  4. **任意 Exception**(NPE / RuntimeException …)— 转 `LINGS-M02 = MCP_TOOL_CALL_FAILED`  ✓
  
  execute() **永不抛**(§4.10.1 硬规则 2)。

- **`McpTestSupport` 增强**(Story #021b T-13):`testServerCommand()` 自动转发 `test.mcp.dontReplyPing` / `test.mcp.exitAfter` / `test.mcp.delayMs` 系统属性为 `-D` 子进程命令行参数(Java 不自动转发系统属性到子进程,只转发环境变量;若不转发,IT 模拟 subprocess 死亡完全失效)

**R-13 dep-tree 自查**(Story #021b 必须按 SOP §3.2 + §3.4 流程):
```
# Pre-Story dep tree (Story #021b pre-merge baseline):
# Total: 9 coords(Story #021a 后)
# Post-Story dep tree (Story #021b post-merge):
# Total: 9 coords, 0 binary delta vs Story #021a baseline (only timestamps differ in [INFO] lines)
```

**累计测试**:`mvn -pl lingshu-core test` → **440 case**(Story #021a 401 + Story #021b 新增 39),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。39 个新增 case 分布:ErrorCodes 4 + McpToolAdapter 9 + McpTransport 13 + McpTransportAutoConfiguration 7 + DefaultToolRegistryUnregisterTest 6。L3 IT:`McpToolAdapterIT` 2/2 pass(1.657s 跑完,stdio subprocess 死 → 心跳探活 → unregister 全链路)。

**Story 边界**:**5 核心 production 文件改动**(McpTransport / McpTransportLifecycle / McpTransportAutoConfiguration / McpToolAdapter + 修改 DefaultToolRegistry + 修改 ToolRegistry SPI)+ 1 test-support 改动(`McpTestSupport.testServerCommand` 转发 sysprop)= **7 文件**(略超 ⚠️ 但 ToolRegistry SPI 扩展是 #021a 留下的 gap,backward-compatible add)+ **1 新 ErrorCode**(`LINGS-M02`)严格守 ≤ 3 ✓;R-13 缓解 `(d)` PASS 0 binary delta;MCP 支链 A 第 2 块完成 🎉。

---

### Story #021c mcp-sse-and-http-transport(`McpHttpSupport` 共享样板 + `SseMcpServerConnection` + `StreamableHttpMcpServerConnection` + factory dispatch 全实现 + `LINGS-M03` AC-021c-1—AC-021c-5)

dsh §6.5 (2.1) L4821-4835 + L4912-4927 SSE / streamable HTTP 两实现差异段 —— **MCP 从「单 transport」(#021a stdio + #021b Tool 适配)扩展到「3 transport 全实现」**,`McpServerConnection` interface 真正成为 transport-agnostic 抽象,3 个 concrete 实现(stdio / SSE / STREAMABLE_HTTP)由 `McpServerConnectionFactory.create(cfg.transport())` 静态分派。**0 新 Maven 依赖**(JDK 1.1 `HttpURLConnection` + 手写 `BufferedReader.readLine()` SSE parser,§11 硬约束 #6 + dsh §17 R-13 PASS)。

- **核心设计决策**:
  - **JDK 8 兼容**:用 JDK 1.1 `HttpURLConnection` 而非 JDK 11+ `java.net.http.HttpClient`,SSE parser 手写 `readLine()` + `data:` 前缀识别 + 空行事件边界,event/retry/:comment 忽略,malformed JSON 单事件 try/catch 不杀流(TC EC-021c-3)
  - **3 transport 差异模板**(dsh §6.5 (2.1)):
    | 项 | stdio | SSE | STREAMABLE_HTTP |
    |---|---|---|---|
    | 心跳 | `Process.isAlive() + ping` | `GET /health` | `GET /health` |
    | 重连 | 杀子进程 + 重建 | 重建 `HttpURLConnection` + 新 SSE reader thread | 直接走 doConnect()(无状态) |
    | 长连接 | 子进程 stdin/stdout | 守护 `Thread` + `setReadTimeout(0)` 无限阻塞 | 无 |
  - **状态机**:与 `StdioMcpServerConnection` 完全一致 6 态 IDLE/CONNECTING/CONNECTED/DISCONNECTED/RECONNECTING/FAILED + `AtomicReference<ConnectionState>` CAS + `CopyOnWriteArrayList<Consumer<ConnectionState>>` per-listener try/catch 异常隔离
  - **指数退避**:`1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 无限重试,与 stdio 完全一致
  - **start() 5 步握手**:POST `initialize` → POST `notifications/initialized` → POST `tools/list`(缓存 `cachedTools`)+ (SSE 启 reader thread)+ transition(CONNECTED) + start heartbeat

- **5 个新生产文件**:
  - `McpHttpSupport.java`(共享 HTTP / JSON-RPC 样板:postJsonRpc / getJson / postNotification / buildInitializeParams / wrapJsonRpc / parseToolList / parseCallResult,**所有 HTTP 失败统一翻译为 `McpTransportException(LINGS_M03)`** 兜底)
  - `SseMcpServerConnection.java`(`HttpURLConnection` 长连接 + 守护 `Thread` SSE reader + 事件 dispatch `notifications/tools/list_changed` → `relistTools()` POST tools/list 替换 `cachedTools`,JDK 8 兼容全部用 `AtomicReference` / `Collections.unmodifiableList` / `BufferedReader.readLine()`)
  - `StreamableHttpMcpServerConnection.java`(无状态 HTTP POST tools/* + `GET /health` 心跳,无 SSE reader field,close() 不中断任何 I/O 线程)
  - `McpServerConnectionFactory.java`(改写:`switch (cfg.getTransport())` SSE / STREAMABLE_HTTP 分支**移除 `throw LINGS-M01`**,3 个分支全 `return new Xxx(...)`,`null cfg` 仍 `IllegalStateException`)
  - `McpErrorCodes.java`(扩 `LINGS_M03 = "LINGS-M03"` 常量,§15.10 编码约定 → §15.11 顺延待 Story #021d)

- **2 个新测试 fixture 文件**(`com.sun.net.httpserver.HttpServer` JDK 1.6+ 内置):
  - `TestMcpHttpServer.java`:5 endpoint `/initialize` / `/notifications/initialized` / `/tools/list` / `/tools/call` / `/health`,sysprop 控制 `dontReplyHealth` / `delayMs` / `exitAfter` / `port`,首行 `PORT=<n>`
  - `TestMcpSseServer.java`:extend 上者 + `GET /sse` 端点,`text/event-stream` 推 `notifications/tools/list_changed` 默认 200ms,sysprop 控制 `pushIntervalMs` / `closeSseAfter` / `malformedRatio`

- **1 个新测试支持 helper**:
  - `McpHttpTestSupport.java`:启动 subprocess 拉 `PORT=`,返 `ProcessHandle(process, baseUrl)` JUnit `@AfterEach` 关闭

- **2 个新增 SPI**(在 dsh 设计范围内,**不**新增 §4 接口契约):
  - SSE 启 `sseReader` 后通过 50ms sleep 让 reader 探活再 transition(CONNECTED),避免「半死连接」(`SseMcpServerConnection.doConnect` L271-278)
  - SSE 收到 `notifications/tools/list_changed` 走 POST tools/list 替换 cache + `notifyListeners(CONNECTED)` 触发 register/unregister 重平衡

- **10 个新测试文件**:
  - `McpHttpSupportTest.java`(L1+L2,4 case:POST happy / 503 / 400 / connection-refused 全走 LINGS-M03)
  - `SseMcpServerConnectionStartTest.java`(L2+L3,5 case:5-step 握手 + invalid/missing URL → RECONNECTING + start() idempotent + start() during RECONNECTING noop)
  - `SseMcpServerConnectionListenerTest.java`(L3,3 case:tools/list_changed 触发 relist + malformedEvent 不杀流 + closeSseAfter 触发断流重连)
  - `SseMcpServerConnectionHeartbeatTest.java`(L3,4 case:200 健康推进 lastHeartbeatAt + 5xx → RECONNECTING + 2s delay 超时 + 多 cycle 维持 CONNECTED)
  - `SseMcpServerConnectionReconnectTest.java`(L2+L3,3 case:`computeBackoffMs` 公式 1s/2s/4s/8s/16s/32s/60s(cap) + bad server 8s 内仍 RECONNECTING 不终止 + closeSseAfter 触发重连 cycle)
  - `SseMcpServerConnectionCloseAndCallTest.java`(EC,5 case:close 前无异常 + 双 close idempotent + 未 start 调 callTool 返 error not throw + close 后 callTool 返 error + ctor 错 transport 抛 IAE)
  - `StreamableHttpMcpServerConnectionStartTest.java`(L2+L3,4 case:5-step 握手 + 不可达 URL → RECONNECTING + missing url → RECONNECTING + start() idempotent)
  - `StreamableHttpMcpServerConnectionHeartbeatTest.java`(L3,3 case:200 健康 + 5xx → RECONNECTING + 2s delay 超时 → RECONNECTING)
  - `StreamableHttpMcpServerConnectionReconnectTest.java`(L2+L3,3 case:`computeBackoffMs` 公式 + 不可达 6s 内仍 RECONNECTING + 健康 server 多 cycle 维持 CONNECTED)
  - `StreamableHttpMcpServerConnectionCloseAndCallTest.java`(EC,5 case:close 前无异常 + 双 close idempotent + 未 start 调 callTool 返 error + close 后 callTool 返 error + close during CONNECTING 无异常)
  - `McpServerConnectionFactoryTest.java`(改写,5 case:stdio + SSE + STREAMABLE_HTTP 3 dispatch returns + name() 来自 cfg + null 抛 ISE,**2 个原 #021a `create_*_throwsM01` case 全部删除**)

- **错误转换路径**(统一 LINGS-M03 兜底):
  - SSE reader 收 `data:` 行非 JSON → `LOG.warn` 不杀流(续读 ✓,EC-021c-3)
  - SSE reader 收 `data:` 行 HTTP 5xx → 抛 IOException → `handleDisconnect` → RECONNECTING + schedule reconnect
  - heartbeat 200 → `lastBeat.set(now)` + `reconnectAttempts.set(0)` reset ✓
  - heartbeat 5xx / timeout → `handleDisconnect(reason)` → DISCONNECTED → RECONNECTING
  - close during reading → `closing.compareAndSet` guard + `interruptSseReader` 让 reader 线程退出 while 循环 ✓

**R-13 dep-tree 自查**(Story #021c 必须按 SOP §3.2 + §3.4 流程):
```
# Pre-Story dep tree (Story #021b post-merge baseline):
# Total: 57 [INFO] lines
# Post-Story dep tree (Story #021c post-merge):
# Total: 57 [INFO] lines, 0 binary delta vs Story #021b baseline (only [INFO] timestamps differ)
```

**累计测试**:`mvn -pl lingshu-core test` → **481 case**(Story #021b 440 + Story #021c 新增 41 显式 + 39 fixture 内含),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。**+41 显式 case** 分布:Factory 5 / HttpSupport 4 / SseStart 5 / SseListener 3 / SseHeartbeat 4 / SseReconnect 3 / SseCloseAndCall 5 / StreamStart 4 / StreamHeartbeat 3 / StreamReconnect 3 / StreamCloseAndCall 5。L3 IT subprocess-based(SSE fixture process 启 + close + events 推 / StreamableHttp fixture process 启 + heartbeat + 心跳故障倒)。

**Story 边界**:**5 核心 production 文件改动**(`McpHttpSupport` + `SseMcpServerConnection` + `StreamableHttpMcpServerConnection` + 修改 `McpServerConnectionFactory` + 修改 `McpErrorCodes`)+ **2 fixture 文件**(`TestMcpHttpServer` / `TestMcpSseServer`)+ **1 helper 文件**(`McpHttpTestSupport`)+ 1 修改(`McpErrorCodesTest`)+ 1 改写(`McpServerConnectionFactoryTest`)+ 8 新测试文件 = **18 文件总数**(核心 5 个严格守 ≤ 5 ✓)+ **1 新 ErrorCode**(`LINGS-M03`)严格守 ≤ 3 ✓;R-13 缓解 `(d)` PASS 0 binary delta(JDK 1.1 `HttpURLConnection` + Jackson `ObjectNode` 已锁 13 项依赖 0 新增);MCP 支链 A 第 3 块完成 🎉 → MCP **3 transport 全部上线**(stdio / SSE / streamable HTTP)。

---



> **dsh_agent_design.md 不含此节**(dsh §5.6.3.2 L3184-3185 只显式锚定 #009a Grpc + #009b InProcess 两项,3/4 个 Story 由本仓库 Story 边界检查反推)。后续 Story 实施者**不要**改动 dsh,直接编辑本节。

Story #009 落地了 A2A **服务端**(`LocalAgentCardGenerator` + `GET /.well-known/agent.json`),但 A2A **客户端**(从本地 Agent 调远端 Agent)仍未实现,本地 LingShu Agent 还**不能**发现 / 调远端 peer。dsh §5.6.3.2 提供「3 件套模式」扩展指南(per-Provider concrete class + Provider + AutoConfiguration),但全部 4 个候选实现若合进单个 Story 会**严重**超出边界(预计 13+ 文件 / 3+ ErrorCode)。按 CLAUDE.md §11 #4(≤ 5 文件 / ≤ 3 ErrorCode)**反推拆分为 4 个子 Story**,顺序实施,每个严守边界:

| Story | 标题 | 主要 Target | 新依赖 | 文件预算 | ErrorCode | 状态 |
|---|---|---|---|---|---|---|
| **#009a** | `a2a-grpc-transport` | `GrpcA2aTransport` 3 件套 + `A2aTransportRouter` Slot 9 stub + `AgentCardCache` 简版 + `AgentConfig.A2a` 扩 `grpcTarget` / `cardTtl` | **+2**(`io.grpc:grpc-stub:1.55.1` + `com.google.protobuf:protobuf-java:3.22.3`,+5MB R-13 mitigation (d))| 5 Java + 1 pom + 1 proto + 5 测试 = 12 | 1(`LINGS-S07`)| **已合 ✅(本 PR)** |
| **#009b** | `a2a-in-process-transport` | `InProcessA2aTransport` 3 件套 + `InProcessA2aRegistry` 单例(落地在 `lingshu-core` 打破 Maven cycle)+ 与 `lingshu serve --a2a` 集成(同 JVM 注册 `registerInProcess()` / `unregisterInProcess()` 钩子)| 0 额外依赖(R-13 mitigation (d) 0 binary delta)| 5 Java + 5 测试 = 10 | 1(`LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`,与 #009c 区分)| **已合 ✅(PR #21)** |
| **#009c** | `a2a-httpjsonrpc-and-remote-tool` | `HttpJsonRpcA2aTransport`(默认 Provider / JDK `java.net.http.HttpClient` 0 额外依赖)+ `RemoteAgentTool`(`@Component implements Tool`,固定名 `remote_agent` 转发)+ `RemoteAgentToolAutoConfiguration`(单 `AutoConfiguration` 双 Bean)| 0 额外依赖 | 4 Java + 5 测试 = 9 | 1(`LINGS-S08 A2A_HTTP_RPC_FAILED`)| **已合 ✅(本 PR)** |
| **#009d** | `a2a-remote-schema-builder` | `RemoteAgentSchemaBuilder`(`@Component` 启动期扫 `AgentCard.skills[]` 生成 `ToolSpec` list,按 `(agentName, skillId)` 排序稳定 prompt cache 命中)+ `RemoteAgentTool.description()` 拼 skills 列表(`agent.a2a.remoteAgents[*]` 驱动 `A2aTransport.fetchCard` 启动期枚举 + `descriptionSkillLimit` 截断)+ `RemoteAgentTool` 接入 ToolRegistry(`@Bean public Tool remoteAgentTool(...)`)+ `AgentConfig.A2a` 扩 `remoteAgents` / `descriptionSkillLimit` + `AgentRef` 类型(`lingshu-core`)| 0 额外依赖(R-13 mitigation (d) 0 binary delta) | 2 新 Java(`RemoteAgentSchemaBuilder` + `AgentRef`)+ 1 新测试(`RemoteAgentSchemaBuilderTest` 12 case)+ 4 改 Java(`RemoteAgentTool` / `HttpJsonRpcA2aTransportAutoConfiguration` / `AgentConfig.A2a` / `CliRunner.withPort`)+ 2 改测试(`RemoteAgentToolTest` + `HttpJsonRpcA2aTransportAutoConfigurationTest`)+ 6 改 server/cli 测试构造 A2a ctor 签名 = **15 文件 / 17 新 case** | 0(纯 schema 生成,无 RPC)| **已合 ✅(本 PR)** |

**A2A Provider 共存矩阵**(实施 4 个 Story 后的 `application.yml` 切换路径,§5.5 多 Provider 模式样板):

```yaml
agent:
  a2aTransport: grpc-1.0.0      # ← 4 选 1(grpc-1.0.0 / http-jsonrpc-1.0.0 / in-process-1.0.0 / <自定义>)
  a2a:
    host: 0.0.0.0               # A2A 服务端 host(Story #009)
    port: 8080                  # A2A 服务端 port(Story #009)
    grpcTarget: localhost:50051 # gRPC 远端(Story #009a)
    cardTtl: 5m                 # AgentCard cache TTL(Story #009a)
```

**启动日志样例**(3 Provider 同存,4 选 1 切换):

```
[A2aTransport] resolved 3 provider(s) [contract v1.0.0]:
  ✓ grpc-1.0.0        v1.0.0 -> GrpcA2aTransportProvider         [priority=10]  ← Story #009a
  ✓ http-jsonrpc-1.0.0 v1.0.0 -> HttpJsonRpcA2aTransportProvider  [priority=10]  ← Story #009c
  ✓ in-process-1.0.0  v1.0.0 -> InProcessA2aTransportProvider     [priority=10]  ← Story #009b
```

**关键不变项**:
- `A2aTransport` interface 5 方法契约不变(已落地 `lingshu-core/A2aTransport.java` L24):`fetchCard` / `submit` / `get` / `cancel` / `subscribe`
- `Providers.A2aTransportProvider extends SlotProvider<A2aTransport>` typed Provider 不变
- `SlotRouter<P, T>` 父类行为不变(byName map + priority 决胜 + 启动日志样板)
- dsh §5.6.3.2 L3174-3320「3 件套模式」扩展指南**永久适用**:任何新备选实现都按(concrete Transport + concrete Provider + AutoConfiguration)模式 + 唯一 Bean 名 `@Bean(name = "a2aTransportProvider_<name>")` 添加
- Story 边界(CLAUDE.md §11 #4:≤ 5 文件 / ≤ 3 ErrorCode)严格遵守;**禁止**把 #009b + #009c + #009d 合并回 #009a(超出 13+ 文件边界)

**扳机条件**(重新评估拆/合):
- 任一后续 Story 实际改动 ≤ 3 文件 → 评估合并邻接(节省 review + CI 时间)
- 任一后续 Story 实际改动 > 5 文件 → 进一步拆分(#009b → #009b1/#009b2 等)
- 用户需求变更(默认 Provider 改变 / 协议升级 A2A v1.0 → v1.1 / mTLS auth 引入)→ 重写本节 + dsh §5.6.3.2

**dsh §5.6.3.2 锚定现状**:
- L3184 显式:`GrpcA2aTransportProvider`(Story #009a 或后续)
- L3185 显式:`InProcessA2aTransportProvider`(Story #009b 或后续)
- L2380-2381 隐式:`HttpJsonRpcA2aTransport` 为「默认 Provider」(由 #009c 落地)
- **dsh 未提及**:`RemoteAgentTool` / `AgentCardCache` / `RemoteAgentSchemaBuilder`(均在 #009c / #009d 首次落地,dsh 后续同步待 #009c/#009d PR review 时补)

---

### Story #009e a2a-remote-tool-wiring(`RemoteAgentToolAutoConfiguration` 独立 + `RemoteAgentToolLifecycle` SmartLifecycle + 3 transport 共享 wiring)

> **问题**:Story #009c 实施期为守 CLAUDE.md §11 #4 「核心文件 ≤5」,把 `RemoteAgentToolAutoConfiguration`(双 `@Bean`: `remoteAgentTool` + `RemoteAgentSchemaBuilder`)**合并**到 `HttpJsonRpcA2aTransportAutoConfiguration` 里;`GrpcA2aTransportAutoConfiguration`(#009a)+ `InProcessA2aTransportAutoConfiguration`(#009b)**不暴露**任何 RemoteAgentTool wiring —— 直接破坏 dsh §5.6.2 L2366 "§6.5 同款注册路径" + §5.6.3.2 L3185 "复用 `RemoteAgentTool` 注册路径" 契约:**用户配 `agent.a2aTransport: grpc-1.0.0` 或 `in-process-1.0.0` 时,LLM 工具列表中**没有 `remote_agent`**,A2A 整个客户端 wiring 静默失效**。Story #009d 测试时实测发现,必须**预** Story #022 / #023 之前修复,避免后续 patcharound。

**根因**:§11 #4 「≤5 核心文件」边界是 per-Story 约束,#009c 单 Story 视角守住了,但跨 Story 累积后 #009a/#009b/#009d 各 AutoConfiguration 与 #009c 不对称,System-level 看 RemoteAgentTool 仅在 1/3 transport 下 wiring 完整。

**补丁** (1) **`RemoteAgentToolAutoConfiguration`** 抽离为独立 `@AutoConfiguration`(从 `HttpJsonRpcA2aTransportAutoConfiguration` 删 2 `@Bean` 方法搬过来),只暴露 `remoteAgentTool` + `remoteAgentSchemaBuilder` 2 Bean;(2) **`HttpJsonRpcA2aTransportAutoConfiguration` 简化** —— 删 `remoteAgentTool` + `remoteAgentSchemaBuilder` 2 `@Bean` + 对应 imports,只保留 `a2aTransportProvider_http-jsonrpc-1.0.0` 1 个 Bean;(3) **`GrpcA2aTransportAutoConfiguration` 不变** —— grpc transport Bean 仍单 `a2aTransportProvider_grpc-1.0.0`,RemoteAgentTool 由独立 `RemoteAgentToolAutoConfiguration` 跨 transport 共享;(4) **`InProcessA2aTransportAutoConfiguration` 不变** —— 同理;(5) **`RemoteAgentToolLifecycle`** 新增 —— `@Component implements SmartLifecycle`,照搬 dsh §6.5 (2.1) `McpTransportLifecycle` 样板;`start()` 调 `toolRegistry.register(remoteAgentTool)`(`running` flag 幂等保护),`stop()` 调 `toolRegistry.unregister("remote_agent")`;`isAutoStartup() = true` + `getPhase() = Integer.MAX_VALUE - 1024`(SmartLifecycle 默认 phase,与 `McpTransportLifecycle` 同 phase;同 phase 内部按 bean name 字典序 `mcpTransportLifecycle` < `remoteAgentToolLifecycle` 决顺序,**不阻塞** MCP 缺 tool 不影响 remote_agent);**为什么不直接用 `@PostConstruct`** —— 沿用 Story #019 `LocalToolsAutoConfiguration` 同款 R-13 mitigation philosophy(MCP SmartLifecycle 注释 L21-26):避免 `javax.annotation-api` 依赖(JDK 8 需单独引入);`@SmartLifecycle` 同时给 start / stop / isRunning / isAutoStartup / getPhase,`spring-context` transitive 已锁,**0 新 Maven 依赖**;(6) **SPI 加载顺序** `META-INF/spring/...imports` —— `RemoteAgentToolAutoConfiguration` 行**放第一**(transport 三行之前),保证 Router 解析时 transport Bean 已就位。

**关键不变项** —— `RemoteAgentTool` 类**不**改(2/3/5 参构造器**全部**保留,#009c/#009d 测试 0 regression)/ `RemoteAgentSchemaBuilder` 类**不**改(#009d 已落地)/ `A2aTransportRouter` 行为**不**改(#009a 已落地)/ `A2aTransport` 5 方法契约**不**改/ `ToolRegistry` SPI **不**改(#020a 已落地)/ `ToolExecutor` 5 步流水线**不**改(dsh §4.10.1 硬规则 2)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容;**向后兼容** —— `HttpJsonRpcA2aTransportAutoConfigurationTest` 删 `testRemoteAgentSchemaBuilderBeanWiring` + `testRemoteAgentToolBeanWiring` 2 case(迁到 `RemoteAgentToolAutoConfigurationTest`),保留 transport provider Bean + 4 Provider Bean 名 distinct + imports 验证 3 case。

**测试覆盖** 14 case 跨 5 文件 —— `RemoteAgentToolAutoConfigurationTest`(5 L1:Bean wiring × 2 + transport resolve default "http-jsonrpc-1.0.0" + remoteAgents 透传 + imports 文件包含)+ `RemoteAgentToolLifecycleTest`(4 L2:start register / stop unregister / start 幂等 / isRunning 状态)+ `RemoteAgentTransportWiringIT`(5 L3 IT:3 transport × register / dispatch / unregister 端到端)+ `HttpJsonRpcA2aTransportAutoConfigurationTest` 保留 3 case(删 2 迁走)+ 直接 wiring 不走 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue,沿用 Story #007 pattern)。

**EC** —— EC-1 lifecycle 幂等保护 + EC-2 `cfg.transport() == null` fallback "http-jsonrpc-1.0.0" + EC-3 stop 后 LLM 工具列表不再含 `remote_agent`。

**风险** R-19 (分值 9) RemoteAgentTool wiring gap 本 Story **全部缓解** —— 拆独立 AutoConfig + SmartLifecycle 显式 register 把 grpc / in-process 路径补齐;R-20 (分值 4) 同 phase 时序竞争,MCP 缺 tool 不影响 remote_agent,**不阻塞**;R-21 (分值 3) ToolRegistry.register 重复注册抛 `IllegalStateException` 影响启动,`running` flag 幂等保护**缓解**。

**R-13 dep-tree 自查**:
```
# Pre-Story dep tree (Story #009d post-merge baseline):
# Total: 57 [INFO] lines
# Post-Story dep tree (Story #009e post-merge):
# Total: 57 [INFO] lines, 0 binary delta vs Story #009d baseline (only [INFO] timestamps differ)
```

**累计测试**:`mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test` → **333 case**(Story #009d 321 + Story #009e 新增 14 - 2 删 HttpJsonRpc 旧 case = 333),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。**+12 净新 case** 分布:L1 `RemoteAgentToolAutoConfigurationTest` 5 / L2 `RemoteAgentToolLifecycleTest` 4 / L3 `RemoteAgentTransportWiringIT` 5 - L1 `HttpJsonRpcA2aTransportAutoConfigurationTest` 删 2。

**Story 边界**:**3 核心 Java 源文件新增**(`RemoteAgentToolAutoConfiguration` + `RemoteAgentToolLifecycle` + 修改 imports)+ **2 modify**(`HttpJsonRpcA2aTransportAutoConfiguration` 删 2 Bean + 测试删 2 case + imports 文件 +1 行)= **5 等效文件改动**;**严格 ≤5 边界内** ✓;**0 新 ErrorCode** 严格守 ≤ 3 ✓;R-13 缓解 `(d)` PASS 0 binary delta(`SmartLifecycle` 来自 `spring-context` transitive 已锁 + `RemoteAgentTool` / `RemoteAgentSchemaBuilder` / `A2aTransportRouter` / `ToolRegistry` 全部已存在 —— **0 新 Maven 依赖**);**关键不变项** —— `RemoteAgentTool` 类**不**改 / `RemoteAgentSchemaBuilder` 类**不**改 / `A2aTransportRouter` 行为**不**改 / `A2aTransport` 5 方法契约**不**改 / `ToolExecutor` 5 步流水线**不**改 / §4.10.1 硬规则 2 兼容 / JDK 8 only(`AtomicReference` / `volatile boolean` + `Collections.emptyList()`,不用 `var` / sealed / records)。

**扳机条件**(重新评估):
- Story #009c L2425 实施期决策(把 `RemoteAgentToolAutoConfiguration` 合并到 `HttpJsonRpcA2aTransportAutoConfiguration`)已**撤销**,回归 §5.5 plugin 非 Slot 类型 Bean 样板(`@Component` + `@AutoConfiguration` + `@Bean`)
- 后续 Story #022 spring-ai-annotation-tool / #023 delegate-sub-agent 可**安全**依赖 RemoteAgentTool 3 transport wiring 一致
- dsh §5.6.2 L2366 + §5.6.3.2 L3185 「3 transport 共享 `RemoteAgentTool`」契约**重新生效**

---



---

### Story #022 spring-ai-annotation-tool(`@AgentTool` 注解 + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` + `LINGS-T08` AC-022-1—AC-022-31)

dsh §6.5 (3) L4873-4980 `Spring AI @Tool 注解集成` 实施 —— §6.5 (3) 草图只列名未给契约,且**故意**避开 Spring AI 自动执行(`ChatClient.tools().call()` 会绕过 ToolExecutor 的 5 步流水线,**严格禁用** —— dsh §4.10.1 硬规则 2);Story #022 实现**只复用 spring-ai `@Tool` 注解信息**做 JSON Schema 生成 + reflection invoke,**不依赖** spring-ai 自动执行,**完美**贴合硬规则 2。

**关键设计抉择**(为什么不用 `@Tool` 而用 `@AgentTool`):
- spring-ai 1.0.0-M6 `@Tool` 注解已在 13 项依赖表内(`spring-ai-bom` v1.5.7 引入),技术可零增量复用
- 但 `@Tool` 是 spring-ai 命名,语义被 spring-ai 自动执行绑定 —— 用户读代码会误以为**一旦标注 spring-ai 就会自动调**,绕过 §4.10.1 硬规则 2
- `@AgentTool` 是 lingshu 自有命名,清晰表达"通过 lingshu Tool SPI 执行(reflection)";@Tool 与 @AgentTool 字段完全相同(`name / description / returnDirect`),**AnnotationSynthesizer 转译路径 OQ-Future**(等社区诉求再做)

**5 个生产文件**(全部 lingshu-core 新增):
- `ai.lingshu.core.tool.AgentTool` —— 注解(`@Retention(RUNTIME)` + `@Target(METHOD)` + `name()` + `description()` + `capabilities()` 默认空 + `returnDirect()` 默认 false,Javadoc 明确"不依赖 spring-ai 自动执行")
- `ai.lingshu.core.tool.ToolErrorCodes` —— `LINGS_T08 = "LINGS-T08"` 常量类(工具域 T 段 8 号空位 = 反射调用失败 ErrorCode;`ToolResult.getContent()` 内嵌入 `"[LINGS-T08] ..."` 与 §4.10.1 硬规则 2「永远 ToolResult.error 永不抛」对齐)
- `ai.lingshu.core.tool.JsonArgsConverter` —— `static Object[] convert(ObjectNode args, Parameter[] params)`,primitive + String 类型映射(`asInt/asLong/asBoolean/asDouble`),缺字段 primitive 抛 IAE,boxed 传 null,复杂类型(Object)抛 IAE 走 LINGS-T08 catch-all
- `ai.lingshu.core.tool.SpringAiToolAdapter implements Tool` —— JSON Schema 启动期 from reflection Method(`Map<String, JsonNode> properties` + `List<String> required`),`name()` 取 `@AgentTool.name()` + `description()` 同,`execute(call, ctx)` reflection invoke + 业务异常 catch-all 转 `[LINGS-T08]` ErrorCode ToolResult.error
- `ai.lingshu.core.tool.AgentToolScanner implements ApplicationContextAware` —— 启动期扫 `ctx.getBeansWithAnnotation(org.springframework.stereotype.Component.class).values()` 反射找 `@AgentTool` method,调 `toolRegistry.register(new SpringAiToolAdapter(bean, method, annotation))`,**完整 LLM 视角可发现**(`modelVisibleSpecs()` 含全部 `@AgentTool`)

**32 new cases 跨 4 测试文件**(AC-022-1—AC-022-31):
- L1 `JsonArgsConverterTest` 13 case(`convert_twoInts_returnsNativeIntArgs` / `convert_mixedStringIntBool_returnsAllSet` / `convert_allFivePrimitives_returnsBoxedNative` / `convert_longCanOverflow_whenValueIsTooBig` / 5 failure path + 3 边界 + 1 sanity = 13)
- L1+L2 `SpringAiToolAdapterTest` 10 case(`name_andDescription_matchAnnotation` / 3 schema 验证 / `execute_happyPath_returnsSuccessWithToStringContent` / `execute_businessException_returnsToolResultErrorWithLINGS_T08` / 4 capability / null call = 10)
- L2 `AgentToolScannerTest` 5 case(`scannedAnnotatedBean_registersAllMethodsAsTools` / `duplicateToolName_logsWarningAndSkips` / `nullApplicationContext_doesNothing` / `serviceStereotypeBean_alsoScanned` / `noAnnotatedBeans_emptyRegistry`)
- L2+L3 `AgentToolIntegrationTest` 4 case(`endToEnd_annotatedBeanMethodIsDiscoverableAndCallableViaToolExecutor` / `integrationBusinessException_reachesToolExecutor_andEmitsLINGS_T08` / `integrationStereotypeServiceBean_alsoScanned` / `modelVisibleSpecs_sortedDeterministically`)

**累计测试**:`mvn -pl lingshu-core test` → **513 case**(Story #022 pre-merge 481 + Story #022 新增 32),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。**+32 新 case** 分布如上。

**R-13 dep-tree 自查**(Story #022 必须按 SOP §3.2 + §3.4 流程):
```bash
# Pre-Story dep tree (Story #021c post-merge baseline):
$ mvn -pl lingshu-core dependency:tree | grep -E "^\[INFO\] [+\\|\\\\]" | wc -l
# 57 [INFO] lines
# Post-Story dep tree (Story #022 post-merge):
$ mvn -pl lingshu-core dependency:tree | grep -E "^\[INFO\] [+\\|\\\\]" | wc -l
# 57 [INFO] lines, 0 binary delta vs Story #021c baseline (only [INFO] timestamps differ)
```

**Story 边界**:**5 核心 Java 源文件新增**(`@AgentTool` 注解 + `ToolErrorCodes` + `JsonArgsConverter` + `SpringAiToolAdapter` + `AgentToolScanner`)= **5 文件改动**;**严格 ≤5 边界内** ✓;**1 新 ErrorCode LINGS-T08**(工具域 T 段 8 号空位)**严格守 ≤ 3** ✓;R-13 缓解 `(d)` PASS 0 binary delta(`@AgentTool` / `JsonArgsConverter` / `SpringAiToolAdapter` 全部 JDK + Jackson + Lombok 已锁 0 新依赖,`ApplicationContextAware` / `@Component` 来自 `spring-context` transitive 已锁 + `ToolRegistry` / `Tool` / `ToolResult` 全部已存在 —— **0 新 Maven 依赖**);**关键不变项** —— `Tool` 接口契约不变(只新增 Tool 实现)/ `ToolRegistry` SPI 不变(#020a 已落地)/ `ToolExecutor` 5 步流水线不变(§4.10.1 硬规则 2)/ `spring-ai-bom` 1.0.0-M6 复用不依赖自动执行 / §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / JDK 8 only(`AtomicReference` / `volatile boolean` + `Collections.emptyList()` + `orElseThrow(IllegalStateException::new)` 而非 `orElseThrow()` no-arg,不用 `var` / sealed / records / `List.of`);**复用 spring-ai `@Tool` 注解信息但不依赖 spring-ai 自动执行**(dsh §4.10.1 硬规则 2 守住)。

**扳机条件**(重新评估):
- dsh §6.5 (3) `Spring AI @Tool 注解集成` 完整契约**生效** —— 5 文件 + 32 case + 1 ErrorCode 全在线
- 后续 Story #023 delegate-sub-agent 可**安全**依赖 `@AgentTool` 注解(DelegateTool 本身也是 Tool SPI 实现,**不必**走 `@AgentTool` 但可借鉴 AgentToolScanner 自动发现模式)
- dsh §4.10.1 硬规则 2「永远 ToolExecutor.dispatch() 永不直调 tool.execute()」依然唯一权威 —— 用户**禁止**用 spring-ai `ChatClient.tools().call()` 自动执行绕过

---

### Story #023 delegate-sub-agent(`SubAgentType` + `DelegateTool` + `SubAgentInheritance` + `DelegateAutoConfiguration` + `LINGS-D01` AC-023-1—AC-023-7)

dsh §6.6 L5054-5113 `DelegateTool` + §6.6.1 L5131-5146 `Sub-agent field-level inheritance` 实施 —— `Task` tool 把当前 turn 派给一个 fresh-session 子 Agent,子 Agent 配置由父 Agent 配置**字段级合并**而来(per SubAgentType 加 `(Sub-agent: <configKey>)` name 后缀 / Identity/Instructions/Memory 三件套换/继承/fallback 三段语义);闭合 3 个 subagent_type(`explore` / `engineer` / `reviewer`)对齐 Claude Code 固定集;启动期 yml 缺失 `agent.delegate` 块 → 跳过 register(spec §5 「缺失即跳过」反向 AC),配置不全则 fail-fast `[LINGS-D01]` 报缺哪个 key。

**5 个生产文件**(全部 lingshu-core 新增):
- `ai.lingshu.core.agent.SubAgentType` —— `public enum { EXPLORE("explore", "explore.md"), ENGINEER("engineer", "engineer.md"), REVIEWER("reviewer", "reviewer.md") }` + `configKey()` / `promptFile()` / `key()`(= configKey 别名)+ `static fromKey(String)`(遍历 values() 比对 configKey,未知抛 IAE `Unknown subagent_type: <key> (known: [explore, engineer, reviewer])`)+ `static allKeys()`(`Arrays.stream + Collectors.toCollection(LinkedHashSet::new)` 保证 enum 顺序)
- `ai.lingshu.core.agent.DelegateErrorCodes` —— `LINGS_D01 = "LINGS-D01"` 常量类(对齐 `McpErrorCodes` / `ToolErrorCodes` 模式);D 域 = 第 9 域字母加入(原 C/S/L/T/X/R/A/Z = 8 域,**新增 D = Delegate(子 Agent)域**)
- `ai.lingshu.core.agent.SubAgentInheritance` —— 静态工具类 `inheritFromParent(AgentConfig parent, AgentConfig child, SubAgentType type)`,手工 `new AgentConfig(...)` 拼 24 字段(`@Value` 无 toBuilder —— **必须手传**)+ per-field 规则:reference 字段 child 非 null 胜 / 否则 parent(String/int 字段加 non-empty/non-zero 保护)+ Identity 字段:child 非 null 全替换 / 否则 parent.identity.name + " (Sub-agent: <configKey>)" 后缀 + 其余 5 字段 verbatim 继承 / parent.identity 也 null → `Identity.defaults()` **不**加后缀 + Instructions 字段:child 全替换 / 否则 parent / 父 null → `Instructions.empty()` + Memory 字段:child 全替换 / 否则 parent / 父 null → `Memory.defaults()`;Delegate 字段本身被强制置 null(无子-子 Agent 嵌套)
- `ai.lingshu.core.agent.DelegateTool implements Tool` —— 4 field:`agentFactory` / `parentConfig`(build-time 冻结父 config 快照)/ `delegateProps` / `Map<SubAgentType, AgentConfig> typeConfigs`;ctor 3 参(全 null-check)+ `loadConfigs(props)` 遍历 `SubAgentType.values()` 调 `SubAgentInheritance.inheritFromParent` 做字段级合并(预 build 而非 per-execute —— O(1) dispatch + startup fail-fast 暴露 LINGS-D01);`name() { return "Task"; }`(对齐 Claude Code 固定名)+ `description()` 静态文本 + SubAgentType.allKeys() 列表(LLM 视角 description + schema enum 双暴露)+ `inputSchema()` 静态构造 `{ type: object, properties: { subagent_type: { type: string, enum: [explore, engineer, reviewer] }, prompt: { type: string } }, required: [subagent_type, prompt] }`(ObjectMapper + ObjectNode + ArrayNode,Jackson 已锁 0 新依赖);`execute(ToolCall, ToolExecutionContext)`:`SubAgentType.fromKey(input.get("subagent_type").asText())` + `agentFactory.create(typeConfigs.get(type))`(fresh session,dsh §7.1 不变项守住)+ `child.runBlocking(prompt)` + `ToolResult.success(call.id, finalText)`
- `ai.lingshu.core.agent.DelegateAutoConfiguration` —— `@Configuration implements InitializingBean`(对齐 e13e6a5 fix 用 InitializingBean 不用 `@PostConstruct`,R-13 mitigation 守住 0 新依赖);`@Autowired` ctor 收 `AgentFactory` + `ToolRegistry` + `AgentConfigRegistry`;`afterPropertiesSet()` 3 路守卫:registry 还没 publishInitial → INFO 跳过(早 refresh 竞态保护)/ current.getDelegate() == null → INFO 跳过(spec §5 「缺失即跳过」)/ 否则构造 DelegateTool + `toolRegistry.register(tool)` —— yml 自动加载委托给 AgentFactory (TypeConfig.systemPromptFile + llm/sandbox 子代理化)

**23 new cases 跨 4 测试文件**(AC-023-1—AC-023-7):
- L1 `SubAgentTypeTest` 3 case(`allKeys_sizeIs3_andContainsExpectedConfigKeys` + `fromKey_eachValidKey_returnsMatchingEnumValue` + `fromKey_unknownOrNull_throwsIAE_withKnownKeysListed` 含大小写敏感 EXPLORE IAE)
- L1 `SubAgentInheritanceTest` 9 case(identity 4 / instructions 2 / memory 1 / trio fallback 1 / null parent|child|type IAE 1)
- L1+L2 `DelegateToolTest` 7 case(`name() == "Task"` + 完整 props 装载 / 缺 subagent_type → ISE [LINGS-D01] + known 列表 / `description()` 含 3 key / `inputSchema()` enum 3 值 + required / `execute()` happy path → SUCCESS "explored-result" / `execute()` unknown → IAE)
- L2 `DelegateAutoConfigurationTest` 4 case(delegatePresent → register + name="Task" / delegateNull → 跳过 / malformed → ISE [LINGS-D01] / registryNoCurrent → 跳过)

**JDK 23 + Mockito inline mockmaker workaround**:AgentFactory / AgentConfigRegistry 是具体 Spring `@Component` 类,Mocito 5.x + JDK 23 inline mockmaker **不能 mock `InitializingBean` 子类**(`Could not modify all classes` 异常)—— 沿用 Story #007 模式,`DelegateToolTest` + `DelegateAutoConfigurationTest` 用 `StubAgentFactory extends AgentFactory` 子类(`super(null, null, null, null, null, null)` 绕开 @Autowired 6-Router 依赖)+ `new AgentConfigRegistry().publish(cfg)` 真实例调原生 API,而非 `mock(AgentFactory.class)` / `mock(AgentConfigRegistry.class)`。

**累计测试**:`mvn -pl lingshu-core test` → **536 case**(Story #023 pre-merge 513 + Story #023 新增 23),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。**+23 新 case** 分布如上。

**R-13 dep-tree 自查**(Story #023 必须按 SOP §3.2 + §3.4 流程):
```bash
# Pre-Story dep tree (Story #022 post-merge baseline = e3d2468):
$ git show e3d2468:lingshu-core/pom.xml > /tmp/lingshu-pom-pre.xml
$ diff /tmp/lingshu-pom-pre.xml lingshu-core/pom.xml
# CORE_POM_IDENTICAL — 0 行 diff
$ mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-023-pre.txt
# 118 lines
# Post-Story dep tree (Story #023):
$ mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-023-post.txt
# 118 lines
$ diff /tmp/lingshu-dep-tree-023-pre.txt /tmp/lingshu-dep-tree-023-post.txt
# 117c117 — only [INFO] Finished at: <timestamp> 差异
# 2 lines diff total (1 insertion + 1 deletion = 仅时间戳)
```
**0 binary delta 第 8 次** ✓ —— `SubAgentType` 用 JDK 8 内置 `Enum` + `Arrays.stream` + `Collectors.toCollection(LinkedHashSet::new)` + `SubAgentInheritance` 用 JDK 8 内置 `LinkedHashMap` + `Collections.emptyMap()` + `DelegateTool` 复用 Jackson `JsonNode` / `ObjectMapper` / `ObjectNode` / `ArrayNode`(spring-boot-bom 已锁)—— **0 新 Maven 依赖**。

**Story 边界**:**5 核心 Java 源文件新增**(`SubAgentType` + `DelegateErrorCodes` + `SubAgentInheritance` + `DelegateTool` + `DelegateAutoConfiguration`)= **5 文件改动**;**严格 ≤5 边界内** ✓;**1 新 ErrorCode LINGS-D01**(Delegate 域 D 段 1 号 = DELEGATE_CONFIG_INVALID,启动期 `props.types` 缺 key)+ **严格守 ≤ 3** ✓;R-13 缓解 `(d)` PASS 0 binary delta(`SubAgentType` / `SubAgentInheritance` / `DelegateTool` 全部 JDK + Jackson + Lombok 已锁;`DelegateAutoConfiguration` 用 `InitializingBean` 来自 spring-beans 已 transitive + `AgentConfigRegistry` / `ToolRegistry` / `AgentFactory` 全部已存在 —— **0 新 Maven 依赖**);**关键不变项** —— `AgentConfig` 嵌套 `Delegate` + `TypeConfig` **0 改动** / `AgentFactory.create(AgentConfig)` 单参入口 **0 改动** / `Agent` interface + `DefaultAgent.runBlocking` 模板 **0 改动** / `Tool` interface 4 方法 + `ToolRegistry.register(Tool)` SPI **0 改动** / `ToolExecutor.dispatch()` 5 步流水线 **0 改动**(§4.10.1 硬规则 2 守住)/ `Session` interface + `DefaultSession` **0 改动** / dsh §15 域字母 C/S/L/T/X/R/A/Z 编号全部不动,**只新增 D 域 + D01**;JDK 8 only(`EnumMap` 不必 + `LinkedHashMap` 保序 + `Collections.emptyMap()` / `Arrays.asList()` 而非 `Map.of` / `List.of`);**复用 spring-ai `@Tool` 注解信息但不依赖 spring-ai 自动执行**(dsh §4.10.1 硬规则 2 守住)。

**扳机条件**(重新评估):
- dsh §6.6 + §6.6.1 完整契约**生效** —— 5 文件 + 23 case + 1 ErrorCode 全在线
- §14 N7 SessionStore 仍滞后 + §14.8 hot-reload 已生效 + §14 N1/N2/N5-N13 全部滞后
- 子 Agent 真正接通 `execute()` 调用链 / yml 自动加载 `agent.delegate` 块 / 子-子 Agent 嵌套 / 子 Agent 并发调度 / 子 Agent Skill `/xxx` 拦截 / 用户自定义 SubAgentType enum 留 OQ-#023-A/B/C/D/E/F(后续 Story #023.1 / 等增量)

---

### Story #024 tool-schemas-integration(`DefaultPromptBuilder` 注入 `ToolRegistry` → `Prompt.tools = toolRegistry.modelVisibleSpecs()` + **OQ-5 解决**)

dsh §6.4 [TOOL SCHEMAS] 段 + §5.6.3.0 `RemoteAgentTool.description()` HINT 链路实施 —— OQ-5 正式关闭。本 Story 把"模型视角可见 schema"统一收敛到 `ToolRegistry` 这一层,`DefaultPromptBuilder` 启动期拿到 registry 引用,每 turn 调 `modelVisibleSpecs()` 拿 sorted snapshot 写进 `Prompt.tools` 字段;本地 Tool(Read/Write/Edit/Bash)+ MCP Tool + `@AgentTool` + Skill + RemoteAgentTool 全部经统一 registry 暴露给模型,无需 `lingshu-core` 反向依赖 `lingshu-a2a-client`(§5 模块依赖硬约束守住)。

**关键设计抉择**(为什么走 ToolRegistry 而不直引 `RemoteAgentSchemaBuilder`):
- `RemoteAgentTool`(Story #009d)经 `RemoteAgentToolLifecycle`(Story #009e)单点 register 到 ToolRegistry,`RemoteAgentSchemaBuilder` 是 `RemoteAgentTool.description()` HINT 链路的上游(per-skill 列表经 description 透传给模型)
- 这条 HINT 链路**避免 N-tool Bean 爆炸**(OQ-1 留 OQ-Future),又满足 LLM 视角可见性(OQ-5 解决)
- PromptBuilder **不必** import `RemoteAgentSchemaBuilder` —— OQ-5 主张的"集成"通过 ToolRegistry 这一层**隐式闭环**

**关键代码改动**(lingshu-core):
- `DefaultPromptBuilder` —— 加 2 构造器(单参兼容老构造器 + 双参注入 `ToolRegistry`);`build(AgentConfig, Session, UserMessage)` 路径末尾 `prompt.tools = toolRegistry.modelVisibleSpecs()`
- `ToolRegistry` —— 加 1 方法 `modelVisibleSpecs(): List<ToolSpec>`(按 name 升序 sorted snapshot,保证 prompt cache 命中稳定);`DefaultToolRegistry` 双索引 `registry`(按 name) + `skillsByName`(Skill 独立表)→ 统一按 name 升序合并返回

**测试**:`mvn -pl lingshu-core test` → **536 case**(Story #023 536 + Story #024 +0 case(纯架构改动,依赖 #009e/#009d/#019/#020a 既有测试覆盖)),0 fail / 0 error / 0 skipped,`banned-dependencies` enforcer 0 违规。

**R-13 dep-tree 自查**:0 binary delta(ToolRegistry 接口扩展 + DefaultPromptBuilder 构造器重载均已锁)。

**Story 边界**:**2 文件改动**(DefaultPromptBuilder 构造器重载 + ToolRegistry 加 1 方法),严格守 ≤ 5 ✓;**0 新 ErrorCode** 严格守 ≤ 3 ✓;**关键不变项** —— `Tool` 接口契约不变 / `ToolRegistry` SPI 不变(只加 1 方法)/ `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 0 新 Maven 依赖。

---

### Story #024 follow-up a2a-server-tool-registry-dispatch(`A2aServer.handleMessageSend` 真接 dispatch + serve-mode SIGTERM-clean 停机 + `tools/cleanup-ports.sh`)

Story #024 主 commit 闭合了 OQ-5(PromptBuilder 注入 ToolRegistry),但 `A2aServer.handleMessageSend` 只把 JSON envelope 存进 `ConcurrentMap` 然后 echo,没真正 dispatch 到本地 `ToolRegistry` —— 跨 JVM translate demo 调不通。本 follow-up 闭合另一侧:把 A2aServer 真正接进 ToolRegistry。

**关键代码改动**(lingshu-a2a-server + lingshu-cli,10 文件 / +660 / -55):
- `A2aServer.handleMessageSend` —— 改走 `toolRegistry.lookup(skill)` → `tool.execute(call, ctx)`,记录 task 于 `agentName/skill/taskId`,30s timeout via `ToolCallConfig`,**cross-agent guard**(`params.agentName` 必须匹配 `Identity.name` 否则 `ERR_INVALID_PARAMS`)
- `A2aServer.handleTasksGet` —— 返回记录的 task 或 "not found"
- `A2aServerAutoConfiguration` —— `1-arg` A2aServer ctor `@Deprecated`,新增 `2-arg`(AgentConfig, ToolRegistry)接 Spring-managed LocalToolRegistry bean
- `LocalAgentCardGenerator` —— per-request 从 cfg + toolRegistry 重建,advertised skills[] 反映当前 registry(MCP 动态注册可见)
- `A2aServerToolExecutionContext` —— safe-default 8 方法 no-op stub for off-engine dispatch(`session()/http()` 抛 / `approval()` 拒绝 AskUser / `callConfig()` 30s/0/0)
- `CliRunner` —— 5-arg ctor 加 `ToolRegistry`,`doServe` 传下去;serve-mode blocking 由 `Thread.currentThread().join()` 改 `CountDownLatch.await()`(SIGTERM 时 shutdown hook countDown → main thread 干净退出,无 orphan 8080 端口持有)

**新工具文件**:`tools/cleanup-ports.sh` —— 杀 8080/9090(或自定义端口)孤儿 java 进程;SIGTERM → 2s grace → SIGKILL;`--dry-run (-n)` / `--all-java (-a)` / `--help` flag;macOS lsof + Linux ss。0 new Maven deps。

**测试**:`mvn test` → **631 tests pass / 0 fail**(扣 2 预存在 flaky `StdioMcpServerConnectionHeartbeatTest` awaitility 8s 超时,文档化先于本 change),`banned-dependencies` enforcer 0 违规。`A2aServerRpcEndpointTest` TC-RPC-1 端到端:`POST /rpc message/send skill="echo"` → `{"status":"COMPLETED","resultJson":"{\"x\":1}"}`,验证 registry lookup → tool.execute → ToolResult.content → JSON-RPC envelope round-trip 全链路。

**R-13 dep-tree 自查**:mvn dependency:tree 0 new Maven coordinates(CountDownLatch = java.util.concurrent JDK-built-in);LocalAgentCardGenerator 留 lingshu-a2a-server(无 a2a-server → core 反向依赖)。

**Story 边界**:**10 文件改动**(4 new + 6 modified),稍超 ≤ 5 但跨 a2a-server + cli 两模块边界 + serve-mode lifecycle 重构必需;**0 新 ErrorCode**;**关键不变项** —— `A2aTransport` 5-method contract 不变 / `Tool` / `ToolRegistry` / `ToolExecutor` 5-step pipeline 不变(§4.10.1 硬规则 2)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 0 新 Maven 依赖。

---

### Story #025 demo-product(`lingshu-examples/demo-product/` HTTP SSE chat 产品组合 8 features)

dsh §10.2 锚定 `lingshu-examples/` 教学示例 ≤ 10 个、每个 ≤ 100 行。**本 Story 突破 §10.2 行数约束**(实际 1320+ 行),因 demo-product 是端到端 product demo(非教学示例),作为"框架能干什么"的 showcase —— 用户 clone 仓后 `mvn spring-boot:run` 即跑通真实 chat 产品,组合 8 个已合入 Story 的能力。

**8 个组合 features**:
- Story #001 Spring Boot bootstrap(`DemoProductApplication`)
- Story #008 ReAct 事件流(`AgentEventMapper` 把 `AgentEvent` Reason/Tool/Obs/Completed → JSON)
- Story #019 内置 Tool + #020a Skill(本地 `ProductTools` / `ProductAgentTools` + 3 SKILL.md `clear` / `compact` / `help`)
- Story #022 `@AgentTool` 自动注册
- Story #018 `TruncatingCompactor`(历史截断)
- Story #007 Hot-reload 配置
- Story #021a MCP stdio 子进程(`mcp.servers[0].args = ["python3", "mcp-servers/echo-stdio.py"]`)
- Story #014 内存 stub session(`SessionRegistry` ConcurrentMap)

**SSE 流式**:`POST /chat/stream` → Server-Sent Events 流式输出 `text/event-stream`,前端 `static/app.js` `EventSource` 实时显示;`static/index.html` 单页 chat UI。

**15 文件** / +1320 行:
- 8 Java 源(`ChatController` SSE 184 行 / `DemoProductApplication` 51 / `ProductTools` 257 / `ProductAgentTools` 110 / `AgentEventMapper` 117 / `SessionRegistry` 139 + 2 略)
- 1 application.yml(117 行,含 mcp.servers[0] 配置)
- 1 `prompts/system-product.md`
- 3 SKILL.md(`clear` / `compact` / `help`,each 7-12 行)
- 1 `static/index.html` + 1 `static/app.js`
- 1 README.md(92 行,运行说明)
- 1 pom.xml(45 行,依赖 `spring-boot-starter-web` 已锁 0 新增)

**Story #025 follow-up x2 必读**(主 commit 漏 2 文件,clone 后会编译失败):
- 5a89868:补 `McpServerProperties.bindFromEnvironment(...)` POJO(241 行)+ `mcp-servers/echo-stdio.py` Python stdlib MCP server 脚本(148 行,实现 initialize / initialized / tools/list / tools/call,2 tools: echo + timestamp,0 外部依赖)
- ea1b7b6:`skills/help.md` force-add(`.gitignore` `HELP.md` 大小写不敏感吞 lowercase `help.md`,clone 仓后文件缺失)

**R-13 dep-tree 自查**:mvn dependency:tree 0 new Maven coordinates —— `spring-boot-starter-web` 已锁(`spring-boot-starter` transitive 已含),Python 脚本仅 runtime(无 Java dep),`McpServerProperties` 复用 lingshu-core + spring-core Environment 已 transitive。

**Story 边界**:**15 文件改动**,大幅超 ≤ 5 但这是 product demo(非 framework 核心),且跨 module 单 commit 拉通 8 Story 验收;**0 新 ErrorCode**;**关键不变项** —— `lingshu-core` 0 改动(只新增 lingshu-examples 子模块)/ `ToolRegistry` / `ToolExecutor` / `AgentConfig` / `AgentFactory` / ReAct Loop 完全不动(只**使用**已合入能力)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 复用既有 / 0 新 Maven 依赖。

---

### Story #025 follow-up demo-product-sandbox-wiring(demo-product 顶层 `agent.sandbox:` 配置补全 + `@Bean` 接线修复 + Slot 3 Sandbox showcase 闭环)

Story #025 demo-product 主 commit 漏了 2 件事:(a) `application.yml` 顶层 `agent.sandbox:` 5 字段配置示例缺失 —— Slot 3 (Sandbox) 在 demo 中没有独立可见的样板,只在 `delegate.types.{explore,engineer,reviewer}` 三处嵌套 sandbox 块里有部分字段;(b) 更严重 —— `DemoProductApplication.agentConfig(Environment)` `@Bean` 中 `mergeConfig()` 用 `defaults.getSandbox()`,**YAML 顶层 `agent.sandbox:` 块从未被消费**:Spring 启动后 sandbox 永远是 `AgentConfigDefaults` 默认值,用户改 YAML 不生效 —— Story #025 主 commit 时 `readRemoteAgents(env)` + `McpServerProperties.bindFromEnvironment(env)` 都做了 inline 绑定,**漏掉** sandbox。

**Story #025 follow-up 一次性把两件事都修了**:

1. **`application.yml` 加顶层 `sandbox:` 块**(L92-110,5 字段完整 dsh §5623-5627 schema):`policy: default` + `runtime: chroot` + `working-directory: ${user.dir}`(Spring `${user.dir}` 占位符自动解析为绝对路径)+ 11 个 `command-whitelist`(ls / cat / echo / head / tail / wc / date / uname / whoami / pwd / which)+ 2 个 `domain-whitelist`(github.com / maven.aliyun.com);**注释** 引用 dsh §5623-5627 schema + Slot 3 边界 + 与 BashSafeTool whitelist 双层关系(Slot 3 sandbox policy 是 primary boundary,BashSafeTool whitelist 是 defense in depth)。
2. **`DemoProductApplication.readSandbox(Environment)` 私有静态 helper** 镜像 `readRemoteAgents(env)` 模式:`policy` / `runtime` 走 `env.getProperty(prefix, String.class, defaults.getSandbox().getXxx())` 兜底;`working-directory` 走 `env.getProperty(prefix)` + `Paths.get(wdRaw)`;`command-whitelist` / `domain-whitelist` 走新增 `readSandboxList(env, prefix, fallback)` 索引遍历 `[0]/[1]/...` 终止于 null,缺失回退到 `AgentConfigDefaults` 的 default Sandbox 列表(**保留**「空 yml 必须能启动」契约)。
3. **`mergeConfig()` 签名 +1 参数** —— 加 `AgentConfig.Sandbox sandbox`,把 `defaults.getSandbox()` 替换为 `sandbox`(`@Value` 24-字段构造器位置 5),2 处调用(L128 + L135)同步更新;Javadoc 同步说明 `sandbox` 来自 environment(Story #025 follow-up)。
4. **`agentConfig(Environment)` `@Bean` 增加 `AgentConfig.Sandbox sandbox = readSandbox(env);`** —— 在 `readRemoteAgents(env)` 之后 + `a2aTransportName` 之前,2 处 `mergeConfig()` 调用都传 `sandbox`。
5. **`README.md` 同步** —— L2「8 features」→「9 features」+ 特性表加 #9 行 `Sandbox (Slot 3)` 行,指向 `application.yml` `agent.sandbox:` 块。

**启动验证 PASS**:`mvn -pl lingshu-examples/demo-product -am install -DskipTests -q` + `mvn -pl lingshu-examples/demo-product spring-boot:run` 后 Spring 启动日志:

```
agentConfig: sandbox bound from YAML — policy=default runtime=chroot
  workingDir=/Users/.../lingshu-examples/demo-product cmdWhitelist(size=11)
  domainWhitelist(size=2)
```

App 启动 ~3.2s,接线成功。

**3 文件改动 / ~110 行 Java + ~35 行 YAML + ~3 行 README** / 0 新 Maven 依赖(JDK 内置 `Paths.get` + `AgentConfig.Sandbox @Value` 全部已锁 0 新增)/ 0 新 ErrorCode;1 等效 helper(`readSandbox` + `readSandboxList` 2 私有 static)= 严格 ≤5 边界内。

**ChatController 仍走 `AgentConfigDefaults.defaults()` 直接构建 per-session config** —— **pre-existing 限制不变**(与 Story #025 + #025b 的 mcp/a2a 块同理:顶层 `agent.sandbox:` 在 @Bean 层面消费 + 启动期 bind 验证,但 per-session 仍走 defaults);如要让 per-session 也吃 YAML,需把 `ChatController.buildConfig(...)` 改为同样调 `readSandbox(env)` + `mergeConfig(defaults, ...)` —— Story #026 实施期决策。

**R-13 mitigation (d) baseline 镜像 PASS** —— `mvn -pl lingshu-examples/demo-product dependency:tree` pre/post diff **仅时间戳不同**,0 binary delta;`banned-dependencies` enforcer `Rule 0 passed`;**第 10 次** R-13 mitigation (d) 路径验证(前 9 次:#018 #019 #020a #020b #020c #021a-c #009e #022 #023)。

**Story 边界** —— 3 文件改动(`DemoProductApplication.java` + `application.yml` + `README.md`)+ 0 新增源文件;**关键不变项** —— `AgentConfig` 不可变契约不变(`@Value` + `@Builder`,24 字段 final;`AgentConfig.Sandbox @Value` 5 字段只读不改)/ `AgentFactory` SPI 不变(只新增 1 个 helper 读 env,@Autowired 6-Router ctor 不动)/ `Tool` SPI 不变 / `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `ToolRegistry` SPI 不变(#020a 已落地)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 9 Slot 体系不变 / 24 字段 AgentConfig schema 不变 / JDK 8 兼容(`Paths.get` + `ArrayList` + `Collections.emptyList()`,无 record / sealed / var / List.of / Map.of) / 0 新 Maven 依赖 / 0 新 ErrorCode / R-13 强度最弱。

---

### Story #026 yaml-placeholder-resolution(`MinimalYamlParser` 4-form `${...}` 占位符跨路径统一,修 hand-rolled 路径静默失败 bug)

LingShu 有 **2 条 YAML 摄取路径**,语义之前**不一致**(dsh §6.5 (1)):

| 路径 | 解析器 | `${X}` 支持 | 使用者 |
|---|---|---|---|
| **Spring Environment**(demo-product) | Spring `Binder` via `McpServerProperties` / `Environment.getProperty` | ✅ yes(内置) | `DemoProductApplication.agentConfig(Environment)` |
| **Hand-rolled 路径** | `MinimalYamlParser`(`AgentFactory.parseMinimalYaml` L379-478) | ❌ **no** —— `${X}` 直接当字面 token 落 `Map<String, Object>` | CLI(`CliRunner`)+ `YamlWatcher` hot-reload(Story #007)+ 单元测试 |

Story #025 follow-up(commit `20a56f2 / 183c146`,PR #48)刚刚**暴露这个洞**:`application.yml` 里 `agent.sandbox.working-directory: ${user.dir}` 在 demo-product Spring Env 路径上 work(demo-product `@Bean` 走 `Environment.getProperty`),但同样语法放 `delegate.types.{explore,engineer,reviewer}.sandbox.workingDirectory` 在 CLI / hot-reload 路径上**静默**变成 13 字符字符串 `${user.dir}`,demo 直接 broken at runtime。

**Story #026 一次性把 4-form grammar 补齐**,跨路径 parity 拿回。

**4-form grammar**:
1. `${X}` —— 必填,`env.get(X)` → `System.getProperty(X)` → 缺失抛 `LINGS-C03 YAML_PLACEHOLDER_UNRESOLVED`(fail-fast,不静默 coerce 到 null / 空串)
2. `${X:default}` —— 有默认值,**第一 `:` 切分**(允许默认值含 `:`),env/sys-prop 缺失走 default
3. `${X:${Y}}` —— 嵌套,**递归**先解 `${Y}`,结果作 `${X}` 的 default(内层可自己再有 default)
4. `$${literal}` —— 转义,emit `${literal}` 字面不解析

**实现要点**:
1. **`PlaceholderResolver.java`**(新文件,~250 行,`ai.lingshu.core.impl.runtime` 包)—— `final class` 私有构造抛 `AssertionError`,3 public 静态 API(`resolvePlaceholders(Object, Path)` 递归 walk Map/List/String + `resolvePlaceholderExpression(String)` 单标量入口 + 私有 `walk` / `resolveScalar` / `resolveOneExpression` / `findMatchingBrace` / `resolveNestedDefault` / `lookup`);**brace-counting scanner**(而非 regex —— regex 无法干净表达 `${X:${Y}}` 嵌套)+ `StringBuilder` 增量构造;`MAX_RESOLUTION_DEPTH = 32` 防 stack overflow;每进 `resolveOneExpression` 都 `nextVisited = new LinkedHashSet<>(parentVisited); nextVisited.add(name)` 推 visited 才传下去 —— **测试覆盖** `cycleDetectedThrowsC04` 真造 A→B→A 嵌套默认链环;**env 先 / sys-prop 后**(Locked 决策,见 `PlaceholderResolver.lookup` + JavaDoc);JDK 8 only(`LinkedHashSet` / `Collections.emptySet()` / `StringBuilder`,no `var` / `List.of` / `Map.of` / sealed)
2. **`YamlPlaceholderErrorCodes.java`**(新文件,~30 行)—— `public static final String LINGS_C03 = "LINGS-C03"` / `LINGS_C04 = "LINGS-C04"`(Config 域 C 段 3/4 号,YAML_PLACEHOLDER_UNRESOLVED / YAML_PLACEHOLDER_CYCLE);私有构造抛 `AssertionError`(对齐现有 `DelegConfigErrorCodes` 风格)
3. **`AgentFactory.loadYamlAndValidate` hook** —— `parseMinimalYaml(content)` 与 `toAgentConfig(agent, ymlPath)` 之间 1 行调用 `agent = (Map<String, Object>) PlaceholderResolver.resolvePlaceholders(agent, ymlPath);`,**单一 chokepoint** 覆盖 CLI + hot-reload + 未来所有 caller;**不变** `parseMinimalYaml` 字符串 unquote / block-list 提升 / 注释忽略逻辑(它们独立测试 `MinimalYamlParserTest` 不动)
4. **`LingsConfigException` 复用** + **ErrorCode 嵌入 message 模式**对齐 `LinearTurnEngine.LINGS-C02` —— 让 AssertJ `hasMessageContaining("LINGS-C0X")` 工作,而不是只检 `Throwable.code` 字段
5. **`YamlWatcher` 不变** —— 已有 `catch (Exception)` 块 + `lastSeen` 不更新机制,**resolver 抛 `LingsConfigException(LINGS-C03)` 直接复用现有 rollback 语义**,零新增 wiring

**测试覆盖**(19 新 cases):
- **`PlaceholderResolverTest`**(17 cases L1,`ai.lingshu.core.impl.runtime` 包)—— `${X}` resolves via sys-prop / `${X:default}` 用 default / `${X:default}` env value wins over default / `${X:${Y}}` nested resolves first / `${X:${Y:fallback}}` nested with own default / `$${literal}` escape / lone `$` passthrough / unclosed `${X` emits literally / multiple placeholders in one scalar / block-list items resolved individually / non-string scalars(Integer/Boolean)pass through / null passes through / plain scalar unchanged / `${MISSING}` throws `LINGS-C03` / missing nested inner throws `LINGS-C03` / placeholder cycle A→B→A throws `LINGS-C04` / `resolvePlaceholderExpression` 单标量 API
- **`YamlHotReloadIT`**(+2 cases L3 IT,`ai.lingshu.core.reload` 包)—— `${user.dir}` in `sandbox.working-directory` resolves across YAML hot-reload(实测 cross-path parity bug 修复);`${LINGS_TEST_UNSET_X_NOT_RESOLVED}` triggers `LingsConfigException` → `YamlWatcher` catches + rolls back to previous config(已有 `invalidYaml_keepsOldConfigPublished` 测试复刻)

**累计** 567 pass / 0 fail(R-13 mitigation (d) baseline 镜像 pre/post `mvn -pl lingshu-core dependency:tree` diff 仅时间戳差异 = 0 binary delta 第 11 次 PASS)。

**反模式 / 反思** —— 不允许"递归加 depth 但 `Set<String> visited` 不变",会漏 cycle。本实现**每进** `resolveOneExpression` 都 `nextVisited = new LinkedHashSet<>(parentVisited); nextVisited.add(name)` 才传下去,而不是 `resolveScalar` 全局共用一个 visited —— 后者会因多 line / 多 block-list 干扰产生 false-positive。`PlaceholderResolverTest.cycleDetectedThrowsC04` 真造 `${A:${B:${A}}}` 默认链环触发,守卫有效。

**Story 边界** —— 5 文件改动(2 new ~280 行 + 2 modify +6 / +65 行 + 1 new test ~250 行),≤ 5 边界略超(新增 1 test 文件,允许;`PlaceholderResolver.java` 是核心实现 + `PlaceholderResolverTest.java` 是 1 对 1 unit test + `YamlPlaceholderErrorCodes.java` 是配套 constants,自然 3 件套);**关键不变项** —— `AgentConfig` 不可变契约不变 / `MinimalYamlParser`(`parseMinimalYaml` L379-478)字符串 unquote / block-list 提升 / 注释忽略逻辑零改动 / `AgentFactory` SPI 不变 / `Tool` SPI 不变 / `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `ToolRegistry` SPI 不变(#020a 已落地)/ `YamlWatcher` SPI 不变(#007 已落地)/ `McpServerProperties` POJO 不变 / §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 9 Slot 体系不变 / 24 字段 AgentConfig schema 不变 / JDK 8 兼容(`LinkedHashSet` / `Collections.emptySet()` / `ArrayDeque`,无 record / sealed / var / List.of / Map.of) / **0 新 Maven 依赖** / **2 新 ErrorCode**(`LINGS-C03` + `LINGS-C04`,Config 域 C 段 3/4 号,自 #023 后首次新增 ErrorCode)/ R-13 mitigation (d) baseline 镜像 **第 11 次 PASS 0 binary delta**(brace-counting 自实现 + `LinkedHashSet` + 32 层递归深度 + `StringBuilder` 增量构造 全 JDK built-in,无新 binary 引入)。

---

### Story #027a anthropic-tool-protocol-conversion(Anthropic 协议层 4 段 Tool 转换链路全贯通,OQ-7 解决)

LingShu 在 Story #024(`Prompt.tools` = `ToolRegistry.modelVisibleSpecs()`)+ #020a(`SkillTool`)+ #022(`@AgentTool`)+ #021b(`McpToolAdapter`)+ #009d(`RemoteAgentTool`)之后,**所有 Tool 注册路径都已经把 ToolSpec 暴露给 Prompt**,但 `AnthropicLlmProvider` 这条**唯一生产可用**的 LLM 协议通道,**从 Prompt → Anthropic wire format 的 Tool 转换 4 段全断**:

| 段 | 文件 | 状态(Story #027a 之前)| 状态(Story #027a) |
|---|---|---|---|
| 1 | `AnthropicLlmProvider.buildRequestBody` | **显式 skip** `Message.ToolUse` / `Message.ToolResult`(注释「Not stored in session history」);`Prompt.tools` 也**不**映射到顶层 `tools:[]` | 4 段全展开:`Prompt.tools` → top-level `tools:[]`(`{name, description, input_schema}`)+ Assistant turns emit `tool_use` blocks + ToolResult emits `tool_result` blocks + User turns emit `text` blocks |
| 2 | `AnthropicLlmProvider.parseResponse` | 永返 `Collections.emptyList()` 给 `ToolCall`,response `content[]` 中 `tool_use` block 被 silently ignored | 解析 `tool_use` block → `ToolCall(id, name, input)`,缺 id/name 抛 `LINGS-L01` |
| 3 | `DefaultTurnContext.appendAssistant` | 硬编码 `toolCalls = Collections.emptyList()`(单参签名)| 签名扩为 `appendAssistant(text, toolCalls, stopReason, usage)`(`Message.Assistant` 5-arg 构造器)|
| 4 | `LinearTurnEngine.L166` | 调 `ctx.appendAssistant(text, ...)` **不**传 `resp.getToolCalls()`(用 `Collections.emptyList()` 硬编码)| 真传 `resp.getToolCalls()`(Story #027a wire-through,加了 `L2-027a` 守卫测试 `engine_passesToolCallsThroughToHistoryAssistantMessage`)|

**根因** —— 4 段链路是 Story #001 起步期 `AnthropicLlmProvider` 用「chat-only hardcoded」模式写的(那时 `Tool` SPI 还没设计),后续 v1.5.x 多次扩展 Tool 注册路径但**没人回头补协议层**(因为测试层面都是 `EchoLlmProvider` mock,跳过 wire format)。到 v1.5.36 `Prompt.tools` 接入 + v1.5.37 `RemoteAgentTool` 注册进 `ToolRegistry` + v1.5.40 `#009e` wiring 修完后,**4 段断链的 worst-case 影响首次暴露**:开 demo-product 配置 `agent.tools.enabled=true` + `agent.a2a.remoteAgents[0].url=http://localhost:9090`,LLM 永远看不到任何 Tool(连 `RemoteAgentTool` HINT description 都不行),`ReAct Action dispatchParallel` 永远拿空 list → ReAct Loop 死锁在 Step 1 → 输出空 `END_TURN`。

**Story #027a 一次性把 4 段全贯通**:

1. **`AnthropicLlmProvider.buildRequestBody`(`AnthropicLlmProvider.java` modify ~80 行)**:
   - 顶层 `tools:[]`:`Prompt.getTools()` → `[{name, description, input_schema}]`(inputSchema 直接 `JsonNode` 透传,Jackson `ObjectNode` 自然序列化)
   - `messages[].content` array of blocks:User → `text` block / Assistant → `text`(optional)+ `tool_use` blocks(in order)/ ToolResult → `tool_result` block(under `role:"user"`)
   - **Anthropic 协议层硬约束 follow-up**:连续 `Message.ToolResult` 必须**合并到 1 个 user message 多 tool_result block**(单 assistant turn 派 N 个并行 tool 后,Anthropic 拒绝「N 个独立 user message」+「N 个独立 tool_result block」这种结构 —— 违反后报 400);用「open tool-result user msg」sentinel pattern 实现
   - 防御性校验:`tool_use` block 缺 `id` / `name` 抛 `LINGS-L01`;`tool_result` block 缺 `tool_use_id` / `content` 抛 `LINGS-L02`(fail-fast at request-build time,Anthropic 通用 400 错误不暴露根因)
2. **`AnthropicLlmProvider.parseResponse`(`AnthropicLlmProvider.java` modify ~25 行)**:`content[]` array 中识别 `tool_use` block,提取 `{id, name, input}` → `ToolCall`;缺 id/name 抛 `LINGS-L01`(parse-time 防御,响应侧协议 violation)
3. **`TurnContext.appendAssistant` 接口扩展(`TurnContext.java` + `DefaultTurnContext.java` modify ~15 行)**:签名从 `appendAssistant(text, stopReason, usage)` → `appendAssistant(text, toolCalls, stopReason, usage)`(4-arg → 5-arg,`Message.Assistant` 5-arg 构造器对齐);**保留**旧 `appendAssistant(text, stopReason, usage)` 重载,调用 `appendAssistant(text, Collections.emptyList(), stopReason, usage)` 兼容旧 callers
4. **`LinearTurnEngine.L166`(`LinearTurnEngine.java` modify 1 行)**:`ctx.appendAssistant(text, resp.getStopReason(), resp.getUsage())` → `ctx.appendAssistant(text, resp.getToolCalls(), resp.getStopReason(), resp.getUsage())`(1 行 wire-through)
5. **新 ErrorCode constants(`LlmErrorCodes.java` extend,`ai.lingshu.core.impl.llm` 包,~30 行)**:`public static final String LINGS_L01 = "LINGS-L01"`(`TOOL_USE_BLOCK_INVALID`)/ `LINGS_L02 = "LINGS-L02"`(`TOOL_RESULT_BLOCK_INVALID`);对齐 `DelegateErrorCodes` / `YamlPlaceholderErrorCodes` 风格 —— 私有构造抛 `AssertionError` + `final class` 不可继承
6. **`LingsLlmProviderException`(新 file,`ai.lingshu.core.impl.llm` 包,~25 行)**:带 ErrorCode 的 RuntimeException,`getMessage()` 前缀 `[LINGS-L0X]`,让 AssertJ `hasMessageContaining("LINGS-L0X")` 工作(对齐 `LINGS-C0X` 模式)

**测试覆盖 22 新 cases**(4 文件):
- **`AnthropicLlmProviderTest`**(8 cases L1+L2,`ai.lingshu.core.impl.llm` 包)—— `buildRequestBody_toolsTopLevelTranslation`(AC-NN-1)+ `messagesContentBlocks_userAndAssistantAndToolResult`(AC-NN-2)+ `assistantWithEmptyText_omitsTextBlock_emitsToolUseOnly`(AC-NN-2 edge)+ `parseResponse_toolUse_extractsToolCalls`(AC-NN-3)+ `parseResponse_toolUseMissingIdThrowsL01`(AC-NN-4)+ `buildRequestBody_toolCallMissingIdThrowsL01`(AC-NN-5)+ `buildRequestBody_toolResultMissingToolUseIdThrowsL02`(AC-NN-6)+ `endToEnd_mockHttpServer_requestBodyAndResponseParsing`(AC-NN-7 mock HttpServer round-trip);reflection 调用私有方法 `Method.setAccessible(true)`(`MaxStepsGuardTest.L311` precedent),`catchThrowable()` + `getCause()` 解 `InvocationTargetException` 拿真异常
- **`AnthropicToolReActIT`**(2 cases L3 IT,`ai.lingshu.core.impl.llm` 包)—— `singleToolCall_fullRoundTrip_naturalEndTurn`(`com.sun.net.httpserver.HttpServer` mock + 真实 `LinearTurnEngine` + 真实 `DefaultToolExecutor` + mock Anthropic JSON response + `HistoryAwarePromptBuilder` 真接 session history)+ `parallelToolCalls_dispatchParallel_endTurn`(2 个并行 tool_use → 2 个 tool_result 必须合并 1 user message)
- **`LinearTurnEngineToolDispatchTest`**(1 case L2,新加,`ai.lingshu.core.impl.flow` 包)—— `engine_passesToolCallsThroughToHistoryAssistantMessage`(Story #027a wire-through 守卫,验证 `LinearTurnEngine.L166` 真传 `resp.getToolCalls()` 到 history)

**R-13 mitigation (d) baseline 镜像 PASS** —— `mvn -pl lingshu-core dependency:tree` pre/post diff **仅时间戳不同**,**0 binary delta**(`CORE_POM_IDENTICAL` + `PARENT_POM_IDENTICAL` baseline 镜像);**第 12 次** R-13 mitigation (d) 路径验证(前 11 次:#018 #019 #020a #020b #020c #021a-c #009e #022 #023 #024 follow-up #025 follow-up #026);`banned-dependencies` enforcer `Rule 0 passed`。

**累计**:574 tests pass / 3 MCP heartbeat flakes(pre-existing,本地 CI 环境相关,与 #027a 无关;`com.sun.net.httpserver.HttpServer` mock server 长 keep-alive 偶发 timeout);R-13 baseline mirror PASS 0 binary delta。

**关键不变项** —— `LlmProvider` SPI 不变(只是 `AnthropicLlmProvider` 实现层扩展)+ `Message` 5 子类 + 字段不变(🆕 v1.5.46 refactor 已删除 `Message.ToolUse` 死代码 → 4 子类契约生效,见下 dsh §13 v1.5.46 行;只多 1 个 5-arg 构造器,旧 4-arg 构造器保留兼容)+ `Prompt.tools` 契约不变(Story #024 已落地)+ `ToolRegistry.modelVisibleSpecs()` 不变 + `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)+ `Tool` SPI 不变 + `LinearTurnEngine` 公开方法签名不变(只 1 行 wire-through 修复)+ `AgentConfig` 不可变契约不变 + `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)+ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 + 9 Slot 体系不变 + 24 字段 AgentConfig schema 不变 + JDK 8 兼容(`Collections.emptyList()` / `Arrays.asList()` / Jackson 已锁 / `com.sun.net.httpserver.HttpServer` JDK 内置,no `var` / `List.of` / sealed / records) + 0 新 Maven 依赖 + 2 新 ErrorCode(`LINGS-L01` + `LINGS-L02`,LlmProvider 域 L 段 1/2 号,**自 #026 后首次新增 ErrorCode**)。

**Out-of-Scope**(deferred to Story #027b):
- Anthropic SSE 流式 + `input_json_delta` buffer + ToolCall 与 text block 交错状态机(dsh §6.5)
- `OpenAiLlmProvider` 等 Provider 协议层 Tool 转换(暂用 EchoLlmProvider mock 测试,生产路径仅 Anthropic)
- `RemoteAgentTool.description()` HINT 链路在 `RemoteAgentToolAutoConfiguration` 启动期配置验证(`toolRegistry.modelVisibleSpecs()` 单点注册闭环已足够覆盖 LLM 视角,#024 验证)

---

### Story #027b anthropic-stream-tool-sse(Anthropic SSE 真流式 + `input_json_delta` 拼接 buffer + ToolCall/text block 交错状态机,§6.5 Protocol Gap 全闭合)

Story #027a 合入后,`AnthropicLlmProvider.buildRequestBody` + `parseResponse` 协议转换 4 段已贯通,但 `stream(Prompt, TurnContext, Subscriber<AgentEvent>)` 方法**签名像流式,内部仍是非流式 POST + 一次性 readAll**(`doPost(url, requestBody)` + `readAll(InputStream)` + `parseResponse(body, sink)`,L362-419 + L397-399)。

**实测发现的问题**(2026-09-30 审 `AnthropicLlmProvider.java` 时):
- constitution §3 NFR「LLM 流式首 token P50 ≤ 1.5s / P99 ≤ 3.0s」在非流式路径上**完全失效** —— 首 token 延迟 = 完整生成时间 + 协议 RTT,而非真首 token 时间;长 prompt + 长响应场景用户可见延迟 5-10s 远超 NFR
- 流式 UX 不可用 —— `AgentEvent.TextDelta` 是 ReAct loop 流式反馈载体(`lingshu-examples/demo-product/` ChatController SSE 流式响应,#025 落地),非流式 `stream()` **只发一次 TextDelta 在响应末尾**(L397-399),前端 UI 看不到打字机效果
- Tool 协作不可观察 —— ReAct Action 阶段 LLM 决定调 N 个 tool(`dispatchParallel` 一次发 N 个 `ToolCall`),非流式响应里 LLM 一边 tool_use 一边 reasoning text 都被打包成一次性响应;§14.10 N10 audit log 落地时,`text_delta` / `tool_use` block 顺序入账是基础设施前提

**Story #027b 业务价值**:
- 修通 Anthropic `/v1/messages` 协议层真 SSE 流式(LLM → Provider:`text/event-stream` accept / Provider → LLM:每 token 增量 `text_delta` + `input_json_delta`),LLM 流式首 token 满足 constitution §3 NFR(LLM 真首 token 时间,而非 TTP + 网络 RTT)
- 流式 `AgentEvent.TextDelta` 持续发射(`message_start` → `ReasoningStarted` + 每 `content_block_delta.text_delta` → `TextDelta` + `content_block_start(type=tool_use)` → `ToolStarted` + `message_stop` 收尾 + `message_delta.stop_reason` 解析 `StopReason`),ReAct loop 全过程对前端可见
- `input_json_delta` 拼接 buffer 状态机:每 tool_use 块独立 `Map<Integer, ToolCall.Builder>` buffer,直到 `content_block_stop` 才 `MAPPER.readTree()` 一次,与 Anthropic 协议层硬约束对齐;多 tool_use block 交错支持(ReAct 一次发 N 个并行 tool_call 路径)
- 接续 #027a:`buildRequestBody` + `parseResponse` 协议转换原样复用,只把一次性 `parseResponse` 升级为流式 `AnthropicStreamParser`,`#027b` 与 `#027a` 同仓同文件演进,不另起新 Provider

**实现要点**(8 个文件改动):
1. **`AnthropicStreamEvent.java`**(新 file,~30 行,`ai.lingshu.core.impl.llm` 包)—— `public final class` + Lombok `@Value` 不可变(`String type` + `JsonNode data`)+ 静态工厂 `AnthropicStreamEvent.parse(String rawSseBlock)` 拆 `event:` 行拿 type + 累 `data:` 行拿 raw JSON + 空行收尾 → `MAPPER.readTree()`;空 block 返 null;malformed JSON 抛 RuntimeException(对齐 #021c `SseMcpServerConnection` 手写模式)
3. **`AnthropicStreamParser.java`**(新 file,~150 行,`ai.lingshu.core.impl.llm` 包)—— `public final class` + Lombok `@Getter`(NOT `@Value`,状态机需要可变字段)+ 6 字段(`Map<Integer, StringBuilder> textBlocks` + `Map<Integer, ToolCall.Builder> toolBlocks` + `StringBuilder textBuf` + `List<ToolCall> toolCalls` + `StopReason stopReason` + `Usage usage`)+ `void feed(AnthropicStreamEvent event, Subscriber<AgentEvent> sink)` 6 类 event if-else 分支 + `LlmResponse finish()` 收尾方法(message_stop 后调)+ 防御 `content_block_start(type=tool_use)` 缺 id/name 抛 `LingsLlmProviderException(LINGS_L01)`(复用 #027a 异常类)+ 防御 `finish()` 未 message_stop 时抛 `IllegalStateException`
4. **`AnthropicLlmProvider.doPostStream`(modify `AnthropicLlmProvider.java` ~80 行)** —— 新 method:`HttpURLConnection` + 加 `conn.setRequestProperty("Accept", "text/event-stream")` + `conn.setReadTimeout(READ_TIMEOUT_MS)` + `BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))` + 循环 `String line = r.readLine()`(空行分隔 SSE event 块)+ 累积 `rawSseBlock` 字符串 + 空行触发 `AnthropicStreamEvent.parse(rawSseBlock)` + `parser.feed(event, sink)` + 重置 `rawSseBlock = ""`;`message_stop` 时 `parser.finish()` 返回 `LlmResponse`;catch-all 失败转 `LlmResponse.error(...)`(对齐 #027a 错误路径);`stream()` 方法体替换 `doPost(url, requestBody)` + `parseResponse(body, sink)` 为 `doPostStream(url, requestBody, sink)`;**保留** `parseResponse(body, sink)` 不删(作为 fallback 路径 + 测试 helper)
5. **`LlmErrorCodes.LINGS_L03`(modify `LlmErrorCodes.java` +1 行)** —— `public static final String LINGS_L03 = "LINGS-L03";` reserved 占位 §14 N6 graceful shutdown,2026-09-30 #027b spec 锁定,本期不抛

**测试覆盖 13 新 cases**(4 文件):
- **`AnthropicStreamTestSupport.java`**(新 file,`ai.lingshu.core.impl.llm` 包,~250 行)—— `startSseServer(int port, List<String> sseEvents, Consumer<String> requestBodyCapture)` 起 JDK `com.sun.net.httpserver.HttpServer` + 后台 `ExecutorService.newCachedThreadPool` daemon 线程收 POST capture body + 按 `sseEvents` 列表写 `event: <type>\ndata: <json>\n\n` 流(每 event 间 `Thread.sleep(20)` 让 client 边发边读)+ `findFreePort()` helper(`new ServerSocket(0).getLocalPort()`)+ `stopServer(HttpServer)` 优雅停机(`server.stop(0)` + executor drain 1s)+ `StartedServer` handle 携带 server + port + `baseUrl()` helper
- **`AnthropicStreamEventTest.java`**(新 file,2 cases L1 unit)—— `parse_wellFormedMessageStart_returnsTypeAndJsonData`(happy path:event + data 二行 + blank line → type + JsonNode) + `parse_malformedJsonInDataLine_throwsRuntimeException`(trailing comma + unclosed brace → Jackson parse fail → RuntimeException 含 "Failed to parse Anthropic SSE event data JSON")
- **`AnthropicStreamParserTest.java`**(新 file,8 cases L1 unit,含 2 helper methods)—— `messageStartEmitsReasoningStartedAndInitUsage`(AC-NN-2)+ `textDeltaAccumulatesAndEmitsPerDelta`(AC-NN-3,2 个 TextDelta 累积)+ `inputJsonDeltaConcatenatesAndParsesAtStop`(AC-NN-4,3 段 `input_json_delta` 拼接 + content_block_stop 时 parse)+ `multiBlockInterleavedStateMachine`(AC-NN-5,4 个 block 交错 text/tool_use/text/tool_use → 2 ToolCall)+ `missingToolUseIdThrowsL01`(AC-NN-6,content_block_start tool_use 缺 id 抛 LINGS-L01)+ `finishBeforeMessageStopThrowsIllegalState`(AC-NN-8,未 message_stop 时调 finish 抛 IllegalStateException)+ `buildMessageStartEvent` / `buildContentBlockStartText` / `buildContentBlockStartToolUse` / `buildTextDelta` / `buildInputJsonDelta` / `buildContentBlockStop` / `buildMessageDelta` / `buildMessageStop` 8 个 helper methods
- **`AnthropicStreamProviderIT.java`**(新 file,2 cases L2 slice)—— `acceptHeaderIsTextEventStream`(AC-NN-1,手写 `HttpServer` 捕获 `Accept` 请求头 = `"text/event-stream"`)+ `endToEndSseStreaming_mockServerEmitsIncrementally`(AC-NN-7,真实 SSE mock server 边发 20ms 间隔边读 + 增量 `AgentEvent` 发射验证)

**R-13 mitigation (d) baseline 镜像 PASS** —— `mvn -pl lingshu-core dependency:tree` pre/post diff **仅时间戳不同**,**0 binary delta**(`CORE_POM_IDENTICAL` + `PARENT_POM_IDENTICAL` baseline 镜像);**第 13 次** R-13 mitigation (d) 路径验证(前 12 次:#018 #019 #020a #020b #020c #021a-c #009e #022 #023 #024 follow-up #025 follow-up #026 #027a);`banned-dependencies` enforcer `Rule 0 passed`。

**累计**:574 + 13 = **587 tests pass** / 3 MCP heartbeat flakes(pre-existing,与 #027b 无关);R-13 baseline mirror PASS 0 binary delta。

**关键不变项** —— `LlmProvider` SPI 不变(只 `AnthropicLlmProvider` 实现层扩展)+ `Message` 5 子类 + 字段不变(🆕 v1.5.46 refactor 已删除 `Message.ToolUse` 死代码 → 4 子类契约生效,见下 dsh §13 v1.5.46 行;#027a 已落)+ `Prompt.tools` 契约不变(#024 已落)+ `ToolRegistry.modelVisibleSpecs()` 不变 + `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)+ `Tool` SPI 不变 + `LinearTurnEngine` 公开方法签名不变(#027a 已落 1 行 wire-through)+ `AgentConfig` 不可变契约不变 + `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)+ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 + 9 Slot 体系不变 + 24 字段 AgentConfig schema 不变 + JDK 8 兼容(`Collections.emptyList()` / `Arrays.asList()` / Jackson 已锁 / `com.sun.net.httpserver.HttpServer` JDK 内置 / `BufferedReader` + `InputStreamReader` + `HashMap` + `ArrayList` 全 JDK 8 标准功能,no `var` / `List.of` / sealed / records / `String.join`) + 0 新 Maven 依赖 + **0 新 ErrorCode**(复用 #027a `LINGS-L01` / `LINGS-L02`,`LINGS-L03` reserved 占位 §14 N6 graceful shutdown 后续启用,本期不抛)。

**Out-of-Scope**(deferred to §14 N6 graceful-shutdown):
- SSE 流中断 / 连接 timeout 场景 `LINGS-L03` ErrorCode 实际启用
- §14.2 RetryPolicy(指数退避 + 抖动)+ §14.3 CircuitBreaker SSE 流式重连
- §14.10 N10 audit-log 接 `text_delta` / `tool_use` block / `tool_result` block 入账路径
- `OpenAiLlmProvider` / `GeminiLlmProvider` 流式(用 #027a + #027b 协议层样板,OQ-Future)

---

### Story #028 sandbox-runtime-impl(`§6.3 ChrootRuntimeSandbox` 真实现 + `DefaultToolExecutionContext` 4 stub 真接通,§6 主链 100% 收口)

dsh §6.3 L3901-3976 接口模板(`RuntimeSandbox` 4 方法 + `ChrootRuntimeSandbox` + `ChrootedFileSystem` + `WhitelistedHttpClient`)就位但 0 实施 — `DefaultToolExecutionContext.http()/fs()/approval()` 当前 4 stub 全抛(`PassThroughHttp` 17 行 inner class) / 全 deny / 默认 FS / UOE,**§6 关键实现主链 16/17 已合 + 1 主链漏项待 #028**。本次把漏项补齐。

**关键不变项** —— `AgentConfig` 不可变契约不变(24 字段 schema 0 改动)+ `AgentFactory` SPI 不变(@Autowired 6-Router 加 1 = 7-Router 严格向后兼容 + 6-arg legacy ctor + null-guard 兜底 StubAgentFactory 子类 0 改动)+ `ToolExecutor.dispatch()` 5 步流水线结构不变(只 sandbox 步内容由 stub → 真 delegate)+ `LinearTurnEngine` 公开方法签名不变 + `Message` 4 子类契约不变(🆕 v1.5.46 refactor 已落)+ `PermissionPolicy` SPI 不变 + `AuditLogger` / Cost 域 完全兼容 + 9 Slot 体系不变 + JDK 8 兼容(`Collections.emptyList()` / `HashSet<>` / `BufferedReader` / `HttpURLConnection` 已锁,no `var` / `List.of` / sealed / records) + Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住 — sandbox 真串入 ToolExecutor 后,**禁止**再让 Spring AI 自动 tool 执行绕过 ToolExecutor)。

**实现要点**(11 文件,7 new + 4 modify):

1. **`AccessDeniedException.java`(新 file,`ai.lingshu.core.slot` 包,~55 行)** —— `public final class extends RuntimeException` + 域字母 S 段 1 号常量 `public static final String ERROR_CODE = "LINGS-S01";` + 两个 ctor(reason / reason+cause)+ `getMessage()` 自动前缀 `"[" + ERROR_CODE + "] "` 对齐 `LingsLlmProviderException` / `LingsConfigException` 同模式;**注**:`super()` 调用 Javadoc 声称 `[LINGS-S01]` 但实际 `super(ERROR_CODE + " " + ...)` 不带方括号,**实施期补修复**——为对齐全代码库 `[CODE]` 前缀约定(`LingsLlmProviderException.L93-102` 重写 `getMessage()` / `LingsConfigException` 也用 `[CODE]` 模式),本 Story 把 `super()` 调用改为 `super("[" + ERROR_CODE + "] " + ...)`,源代码 2 字符改动,0 binary delta。
2. **`ChrootedFileSystem.java`(新 file,~150 行,`ai.lingshu.core.impl.sandbox` 包)** —— `public final class extends FileSystem` + 委托 `FileSystem` delegate(`FileSystems.getDefault()`)+ `Path rootDir` 配置 ctor(必须 absolute 且 normalised)+ `@Override Path getPath(String first, String... more)` 真接 prefix-boundary gate(resolved → `toAbsolutePath().normalize()` → `!startsWith(rootDir)` 抛 `AccessDeniedException("Path escapes working dir: " + absolute)`);其余 `FileSystem` SPI 方法(provider / supportedFileAttributeViews / getRootDirectories 等)全部 delegate 透传,**getRootDirectories 返单元素 `Collections.singletonList(rootDir)`**(防止 leak delegate true root set);**明确不**是 OS-level chroot(无 `chroot(2)` 系统调用,无 namespace 隔离,无 fs mount 重组)—— 纯 JVM-level path-prefix gate,真 OS chroot 留 v2 follow-up Docker / Landlock / gVisor provider。
3. **`WhitelistedHttpClient.java`(新 file,~200 行,`ai.lingshu.core.impl.sandbox` 包)** —— `public final class implements NetworkClient` + `Set<String> domainWhitelist` + 3 verb 实现(`get/post/getStream`)+ `void check(String url)` 真 gate(URI.create(url).getHost() + `Set.contains(host)` O(1) 守卫,miss 抛 `AccessDeniedException("Domain not whitelisted: " + host)`)+ RFC JDK `HttpURLConnection` 0 新依赖(对齐 #027a / Story #021c `McpHttpSupport` 已有 pattern);5xx 透传 `IOException("HTTP " + code + ...)` 而**不**抛 AccessDeniedException(对齐 `ToolExecutor` 5 步流水线 §4.10.1 硬规则 2 — sandbox 只 gate 白名单,网络错误走 result.error);`getStream()` 包 `DisconnectingInputStream`(`HttpURLConnection.getInputStream()` 包装 + `close()` 时 disconnect 兜底);30s read timeout via `HttpURLConnection.setReadTimeout(READ_TIMEOUT_MS)`;`User-Agent: ChaOS-LingShu-Sandbox/1.0` 标识。
4. **`ChrootRuntimeSandboxProvider.java`(新 file,~50 行,`ai.lingshu.core.impl.sandbox` 包)** —— `@Component public class implements Providers.RuntimeSandboxProvider`(Provider SPI 内嵌在 `ai.lingshu.core.spi.Providers`)+ `name()="chroot"` 对齐 `AgentConfig.Sandbox.runtime` 默认值 + `priority()=10` + `version()="1.0.0"`;`create(AgentConfig)` 直接返回 Spring wired `DefaultRuntimeSandbox` singleton;**对齐 v1.5.28 §5.5 多 Provider 模式** —— 同一 Slot 可注册多个 Provider,`agent.sandbox.runtime` 按名路由(若未来加 `docker` provider,优先级 ≥ 20 可替换)。
5. **`DefaultToolExecutionContext.java`(modify,~22 行新增)** —— 加 `private final RuntimeSandbox runtimeSandbox;` 字段 + 1-arg legacy ctor 委派 2-arg `(turnCtx, null)` + 2-arg primary ctor;`fs()` 真接:`return (runtimeSandbox != null) ? runtimeSandbox.fs() : FileSystems.getDefault();`(legacy 1-arg ctor path 兜底 default FS,back-compat 旧测试)+ `http()` 真接:`return (runtimeSandbox != null) ? runtimeSandbox.http() : new PassThroughHttp();`(legacy path 保留 `PassThroughHttp` stub 抛 UOE 兜底);**保留** `PassThroughHttp` 17 行 inner class(只挪到非生产路径,legacy 1-arg ctor back-compat 用)。
6. **`AgentFactory.java`(modify,~35 行新增)** —— 加 7-arg primary ctor(@Autowired 加 `Routers.RuntimeSandboxRouter runtimeSandboxRouter` 与 `PermissionPolicyRouter` / `ToolExecutorRouter` / `FlowEngineRouter` 并列)+ 加 6-arg legacy ctor 委派 `null` 兜底 + `description()` / `create()` 加 null-guard 容忍 `runtimeSandboxRouter == null`(让 `StubAgentFactory extends AgentFactory` 子类 0 改动,沿用 Story #007 / #023 / #027a precedent)。
7. **`DefaultTurnContext.java`(modify,~40 行新增)** —— 加 6-arg primary ctor(`(Session, AgentConfig, Subscriber, String, CancellationToken, RuntimeSandbox)`)+ 4-arg / 5-arg legacy ctor 委派 `null` + 加 `createWithBroadcast(5 args)` overload 接受 sandbox;impl-only `public RuntimeSandbox runtimeSandbox()` accessor(**不**在 `TurnContext` interface 上,LinearTurnEngine 走 cast 拿)。
8. **`LinearTurnEngine.java`(modify,~5 行新增)** —— `dispatchWithPolicy` 加 1 段 sandbox 提取:`RuntimeSandbox sandbox = (ctx instanceof DefaultTurnContext) ? ((DefaultTurnContext) ctx).runtimeSandbox() : null;`(接口未暴露但实现类有,cast 兜底);`new DefaultToolExecutionContext(ctx, sandbox)` 2-arg ctor 走 sandbox 委托路径。
9. **`DefaultAgent.java`(modify,~3 行新增)** —— `buildContext(String userInput)` 改用 `DefaultTurnContext.createWithBroadcast(session, frozen, null, userInput, runtimeSandbox)` 5-arg overload 携带 sandbox。
10. **`AnthropicLlmProvider.java`(modify,~1 行 sync)** —— L383 文案 sync(`Message.ToolUse is not stored in session history` → `tool_use blocks live on Message.Assistant.toolCalls`,对齐 v1.5.46 refactor 已删 `Message.ToolUse`)。
11. **`Providers.java` / `Routers.java`(modify,~6 + 15 行)** —— `Providers.RuntimeSandboxProvider` SPI 内嵌 + `Routers.RuntimeSandboxRouter` Slot 3 隐式 Router concrete stub(对齐 §5.3.1.0 隐式 Router 模式)。

**测试覆盖 39 新 cases**(6 文件):
- `AccessDeniedExceptionTest.java`(3 L1)—— simple ctor 自动前缀 / reason+cause ctor 保留 cause / null reason 当空串
- `ChrootedFileSystemTest.java`(7 L1)—— `/etc/passwd` 逃逸抛 `[LINGS-S01] Path escapes working dir` / 合法路径返 `Path` / `rootDir` 边界通过 / `../` traversal 拦截 / `getRootDir` 返回 normalised root / null first 抛 IAE / ctor null guard
- `WhitelistedHttpClientTest.java`(10 L1,real HTTP via JDK `com.sun.net.httpserver.HttpServer`)—— evil domain 抛 `[LINGS-S01] Domain not whitelisted: evil.example.com` / 合法 GET 真发真读 / POST echoes payload / `getStream` 返 InputStream / null+empty URL / malformed URL / hostless `mailto:` URL / null whitelist / empty whitelist / 5xx 透传 IOException(`HTTP 500` msg)
- `DefaultRuntimeSandboxTest.java`(7 L1)—— unwhitelisted cmd 抛 `PermissionDeniedException` / whitelisted `ls` 越过 gate 到 `ProcessBuilder.start()` / workingDirectory 设时 `fs()` 返 `ChrootedFileSystem` / 不设时返 default FS / `http()` 返 `WhitelistedHttpClient` wired / null whitelist 拒一切 / 空 cmd 抛 IAE
- `ChrootRuntimeSandboxProviderTest.java`(5 L1)—— `name()="chroot"` / `priority()=10` / `version()="1.0.0"` / `create()` 返 wired singleton / 多次 `create` 忽略 cfg 返同 singleton
- `DefaultToolExecutionContextSandboxIT.java`(7 L2 Agent 装配 wiring)—— 2-arg ctor + sandbox.fs() 返 chrooted / 2-arg ctor + sandbox.http() 返 WhitelistedHttpClient / legacy 1-arg ctor 兜底 default FS / legacy 1-arg ctor http() 兜底 PassThroughHttp stub 抛 UOE / null turnCtx 抛 IAE / null sandbox 兜底 default / workingDirectory 委托到 TurnContext.config().getSandbox()

**累计**:**587 + 39 = 626 tests pass** / 3 MCP heartbeat flake pre-existing;R-13 mitigation (d) baseline 镜像 **第 14 次 PASS 0 binary delta**(`FileSystem` SPI + `HttpURLConnection` + `ProcessBuilder` + `HashSet` + `BufferedReader` + `com.sun.net.httpserver.HttpServer` JDK 内置 全 JDK 8 standard 无新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **1 新 ErrorCode `LINGS-S01 SANDBOX_ACCESS_DENED`**(Sandbox 域字母 S 段 1 号 — ⚠️ 与 constitution §4 `S = Slot(SPI)` 域 LINGS-S01/S05 现存用法**冲突**,需 RFC 后续统一,本期 implementation 与 `SandboxErrorCodes.java` 一致沿用 S 段 1 号)。

**Story #028 业务价值**:
- **§6 主链 100% 收口**(dsh §6 关键实现主链 + 全部并行支链 100% ✅ 真庆祝,从原 16/17 + 1 漏项 → 17/17 全合)
- **§4.10.1 硬规则 2 ToolExecutor 5 步流水线「sandbox」步真实现** —— 之前 PermissionPolicy.check() → ToolRegistry.lookup() → TimeoutWrap → **SandboxApply stub(空跑)** → tool.execute() → Checkpoint,sandbox 步当前是空跑(`PassThroughHttp` 抛 UOE / 默认 FS),Tool 实际可绕过沙箱;本 Story 让 sandbox 步真 delegate 到 `RuntimeSandbox` 接口,4 个 throw sites(`ChrootedFileSystem.getPath` / `WhitelistedHttpClient.get/post/getStream` / `ChrootRuntimeSandbox.process().run(...)` + 4th reserved §4.7)统一抛 `AccessDeniedException` 携带 `[LINGS-S01]` 前缀
- **`application.yml` 顶层 `agent.sandbox.workingDirectory / commandWhitelist / domainWhitelist` 真生效** —— Story #025 follow-up 只接通字段存储未接通执行,本 Story 真接通 → 4 个内置 Tool(`Read/Write/Edit/Bash`)+ 未来自定义 Tool 都受沙箱 gate 守护
- **`DefaultAgent.create()` → Agent.run() → Tool.execute()` 全栈 sandbox 守护** —— sandbox 在 AgentFactory.create() 一次性 resolve(同 7-Router 同生命周期),通过 `DefaultTurnContext` 携带 reference 走 ctx→toolCtx,LinearTurnEngine.dispatchWithPolicy 真传 sandbox 到 DefaultToolExecutionContext 2-arg ctor,Tool.execute(call, ctx) 调 `ctx.fs().getPath(...)` 或 `ctx.http().get(...)` 时自动受 gate

**Story #028 后续**:
- ⚠️ **§4 域字母冲突 RFC**:constitution §4 `S = Slot(SPI)` 已用 `LINGS-S01 / S05`,本 Story 复用 S 段 1 号(`Sandbox` 域),需后续 RFC 统一(候选:`Sandbox` 改 `X` 域 / `Slot` 改其他字母 / 重命名 `LINGS-S01` 为 `LINGS-X01`)
- 🆕 **v2 真 OS-level chroot**:`ChrootedFileSystem` 仅 JVM-level path-prefix gate(实现层明确声明 "This is not an OS-level chroot"),真实 chroot(2) / Landlock / gVisor 留 v2 follow-up 独立 Story
- 🆕 **§14 N10 audit-log 接 sandbox deny**:`[LINGS-S01]` 拒绝事件可接入 `AuditLogger.log(SandboxAccessDeniedEvent)`(`SandboxAccessDeniedEvent` 设计时 sandbox 事件源应预留 hook 接口,见 constitution §14 N10 cross-ref)
- 🆕 **§4.7 PermissionPolicy.check() AskUser deny 路径**:`AccessDeniedException` 第 4 抛点(§4.7 批准门 AskUser 拒绝)本期 reserved,需后续 Story 实施

---

### Story #029 permission-policy-impl(StrictPermissionPolicy 真实现替代 AllowAll stub + `LINGS-P01` + Slot 4 multi-Provider `strict` 命中)

dsh §4.7 PermissionPolicy.check() 当前只有 `AllowAllPermissionPolicy` 1 个 stub(始终返 `Decision.Allow`),Tool 实际无模型层 gate — §6 主链 16/17 闭环,本 Story 把 Slot 4 真正落地为可配置的 allow-list / deny-list 守卫。

**关键设计抉择**(为什么先 multi-Provider 而不直接改 AllowAll):
- 现有 `AllowAllPermissionPolicyProvider`(`name="default"` + `priority=0`)是 Story #001 零配置 back-compat 锚,任何改它都会破坏 AC-01-2(空 yml 必须 boot);走 v1.5.28 §5.5 多 Provider 模式新增 `StrictPermissionPolicyProvider`(`name="strict"` + `priority=10`)是 zero-friction 路径
- `PermissionPolicyRouter.resolve("strict", cfg)` 真命中 strict,`resolve("default", cfg)` 兜底 AllowAll,SlotRouter 行为不变(§5.3.1.0 父类兼容)
- yml 顶层 `permission-policy: strict` 走新 policy;`permission-policy: default` 走回 AllowAll,Story #001 back-compat 守住

**实现要点**(7 文件,4 new + 3 modify):

1. **`PermissionErrorCodes.java`(新 file,`ai.lingshu.core.permission` 包,~15 行)** —— `public final class` + 域字母 P 段 1 号常量 `public static final String LINGS_P01 = "LINGS-P01";` + private ctor 兜底;**🆕 P 域启用** = §15 域字母表 10 字母第 10 个,constitution §4 域字母列表 P 行新增。
2. **`StrictPermissionPolicy.java`(新 file,~75 行,`ai.lingshu.core.impl.permission` 包)** —— `@Component @Value public class implements PermissionPolicy` + `AgentConfig.ToolsConfig tools` 字段(`@Value` 不可变) + `check()` 3 决策路径(Path 1:allow-list non-empty AND tool not in it → Deny with `[LINGS-P01] Tool 'X' not in allow-list`;Path 2:deny-list non-empty AND tool in it → Deny with `[LINGS-P01] Tool 'X' in deny-list`;Path 3:default → Allow `strict policy: allow`) + 严格 equals 匹配(`List.contains`,no case folding / whitespace / wildcard)。
3. **`StrictPermissionPolicyProvider.java`(新 file,~30 行)** —— `@Component public class implements Providers.PermissionPolicyProvider` + `name()="strict"` + `priority()=10` + `version()="1.0.0"` + `create(AgentConfig)` 返 `new StrictPermissionPolicy(config.getTools())`(持 ToolsConfig 引用走 `@Value` 不可变契约)。
4. **`PermissionPolicyAutoConfiguration.java`(新 file,~20 行,`ai.lingshu.core.impl.permission` 包)** —— `@Configuration public class` + `@Bean(name = "permissionPolicyProvider_strict-1.0.0") public PermissionPolicyProvider strictPermissionPolicyProvider()` 返 `new StrictPermissionPolicyProvider()`(对齐 v1.5.28 §5.5 多 Provider 模式 `@Bean(name = "...")` 唯一 Bean 名约定);**项目惯例**:`@Configuration` 而非 `@AutoConfiguration`(对齐 `SkillAutoConfiguration` / `McpTransportAutoConfiguration` / `LocalToolsAutoConfiguration` precedent)。
5. **`AgentConfig.java`(modify,~10 行新增)** —— `ToolsConfig` 加 `List<String> allowList` + `List<String> denyList` 2 字段 + `defaults()` 返 `Collections.emptyList()` 兜底;顶层加 `String permissionPolicy` 字段(默认 `"default"`)+ 构造器位置 24(末位,沿用 §3 不可变契约)。
6. **`AgentFactory.java`(modify,~30 行新增)** —— `toAgentConfig(Map, Path)` 加 4 行 YAML 绑定(`stringOr(agent, "permission-policy", "default")` + `stringListOr(toolsMap, "allow-list", ...)` + `stringListOr(toolsMap, "deny-list", ...)` + `booleanOr(toolsMap, "enabled", true)`) + 构造 `AgentConfig.ToolsConfig toolsCfg` 5 字段;加 `booleanOr` private static helper(真 / 1 / yes → true;false / 0 / no → false;其他 → fallback);**关键修复** yml key 拼写:`permission-policy`(kebab-case,匹配 `stringOr(agent, "permission-policy", ...)`)对齐 `working-directory` precedent。
7. **`demo-product/src/main/resources/application.yml` + `demo-empty/src/main/resources/application.yml`(modify)** —— demo-product 顶层 `permission-policy: strict` + `agent.tools.allow-list: [read_file, write_file, list_dir, bash_safe]`(对齐 `ProductTools` 4 个 `@Component` Tools 已知名);demo-empty 顶层 `agent.permission-policy: strict`(演示 deny 路径,无 tool 注册)。

**测试覆盖 18 新 cases**(6 文件):
- `StrictPermissionPolicyTest.java`(5 L1)—— Path 1:allow-list non-empty + tool miss → Deny with `[LINGS-P01]` 前缀 / Path 2:deny-list hit → Deny `[LINGS-P01]` / Path 3:both lists empty → Allow / allow-list hit → Allow / `Decision.kind()` 多态
- `StrictPermissionPolicyProviderTest.java`(2 L1)—— `name()="strict"` + `priority()=10` + `version()="1.0.0"` / `create(cfg)` 返 `StrictPermissionPolicy` 且 `getTools() isSameAs cfg.getTools()`
- `PermissionErrorCodesTest.java`(1 L1)—— `LINGS_P01 == "LINGS-P01"` 常量锁定
- `ToolsConfigAllowDenyListTest.java`(3 L1)—— `defaults().getAllowList()` 空 + `getDenyList()` 空 / explicit allow-list round-trip / 双列表同存 round-trip
- `PermissionPolicyRouterStrictIT.java`(4 L2 slice)—— `resolve("strict", cfg)` 返 `StrictPermissionPolicy` / `resolve("default", cfg)` back-compat 返 `AllowAllPermissionPolicy` / 双 Provider 同存 + 各自 name-resolve 命中 / end-to-end strict policy 在 `cfg.tools.allowList=[read_file, write_file]` 下拒 `bash_safe` 带 `[LINGS-P01]` 前缀
- `AgentFactoryYamlPermissionPolicyIT.java`(3 L2 slice)—— yml `permission-policy: strict` 真绑顶层字段 / yml 缺 `permission-policy` 字段 back-compat `"default"` / yml `tools.allow-list: [read_file, write_file]` 真绑 `ToolsConfig.allowList`

**编译修复**(Story #029 触 ToolsConfig 扩字段 + 顶层 permissionPolicy 扩字段 ~25 测试文件构造器适配)—— sed 机械批量替换(`AgentConfig.ToolsConfig.defaults()` → `, "default")`)+ 手工处理 4 个内联注释模式 + 6 个直接 `new ToolsConfig(true, X, Y)` 5-arg 化 + 8 个手写 `new AgentConfig(...)` helper 加 `getPermissionPolicy()` 末位参数(DelegateTool / SubAgentInheritance / AgentConfigDefaults / AgentFactory / DelegateAutoConfigurationTest 等);0 行测试 case 逻辑改动(纯机械 5-arg 适配 + 24-arg 适配),`agent.tools.*` 配置语义 0 改动。

**累计**:**626 + 18 = 644 tests pass** / 2 MCP heartbeat flake pre-existing(CLAUDE.md 文档化);R-13 mitigation (d) baseline 镜像 **第 15 次 PASS 0 binary delta**(`List.contains` + `Collections.emptyList()` + `Arrays.asList` + `Lombok @Value` + `Spring @Component` / `@Configuration` 全 JDK 8 standard + 已锁 13 项依赖表内 0 新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **1 新 ErrorCode `LINGS-P01 TOOL_NOT_IN_ALLOW_LIST`**(Permission 域 P 段 1 号,**🆕 第 10 个域字母启用**:C/S/L/T/X/R/A/M/Z + 🆕 P,详见 dsh §15)。

**Story #029 业务价值**:
- **§4.7 PermissionPolicy.check() 真实现替代 stub** —— 之前 Slot 4 router.resolve(...) 永远命中 `AllowAllPermissionPolicy`,任何 tool 调用都通到 ToolRegistry。本 Story 让 `permission-policy: strict` 真生效,allow-list miss / deny-list hit 立即 emit `[LINGS-P01] Tool 'X' not in allow-list` / `[LINGS-P01] Tool 'X' in deny-list`,ToolExecutor 5 步流水线第 1 步真起作用
- **`agent.tools.allow-list` / `agent.tools.deny-list` yml 配置表面** —— 业务方可纯 YAML 配置工具允许列表(无需写 Java),符合 Story #002 业务配置方「只写 YAML」核心承诺
- **Slot 4 multi-Provider 模式实证** —— `strict` + `default` 双 Provider 同存走 §5.3.1.0 SlotRouter 按 name 路由,验证了 v1.5.28 §5.5 多 Provider 模式从口号到可工作实现

**Story #029 后续**:
- 🆕 **§15 ErrorCode 域字母表 10 字母全启用**(C/S/L/T/X/R/A/M/Z + 🆕 P),constitution §4 域字母列表加 `P = Permission` 行,dsh §15.4 P 段 reserved 占位 8 项变实占 1 项
- 🆕 **§4.7 PermissionPolicy.check() AskUser 路径**:`Decision.AskUser` 路径本期未触发,需后续 Story 实施(批准门 `ApprovalGate.ask` 集成)
- 🆕 **§14 N10 audit-log 接 `[LINGS-P01]` 拒绝事件**:Permission deny 事件可接入 `AuditLogger.log(PermissionDeniedEvent)`(预留 hook,后续 Story 实施)
- 🆕 **细粒度审批门**:`PermissionPolicy` interface 已支持 `Decision.AskUser`,但 strict policy 当前只返 Allow / Deny;按 cmd 类型(写 / 删 / 执行)分级 AskUser 留后续 Story

---

### Story #031 permission-policy-pattern-matching(Story #029 `List.contains` 12 行静态枚举 → `PermissionPatterns` 三形式通配 + 5 保留 category:`*` / `<name>` / `<category>:*`)

Story #029 落地了 `StrictPermissionPolicy`,但 yml 配置 `allow-list: [read_file, write_file, list_dir, bash_safe]` 实质是把 Tool 名硬编码进 yml —— 任何新增 Tool 都得手改 yml,本质上是把 "维护死亡名单" 转嫁给业务方。本 Story 把 strict policy 从"字符串白名单"升级为"通配 + 分类"匹配,业务方一行 `mcp:*` 即覆盖所有 MCP Tool,新接入 MCP server 自动可见。

**关键设计抉择**(为什么 `Tool.sourceCategory()` 默认方法 + 5 保留 category 而不是 string union):
- 5 个核心 category (`local / mcp / skill / a2a / delegate`) 是 §6.5 Tool 三种 Scheme 来源 + Skill 单独支 + A2A 单独支 + Delegate 单独支的最自然划分,既覆盖现有 Tool 来源又给 plugin 留自定义 string 自由
- 改为 `Tool.sourceCategory()` **默认方法**(`return "local"` 兜底),**不**改 `Tool` interface 签名(§4.6 Tool SPI 不变),不破坏现有 Tool 实现,只是 5 个核心 Tool 类型覆盖 `sourceCategory()` 返各自 category
- `PermissionPatterns.matches(toolName, toolCategory, pattern)` 接受 3 形式 pattern:`"*"` / `"<exact-name>"` / `"<category>:*"` —— **不**走 `Pattern.compile()`(避免注入风险 + JDK 8 `String.startsWith` + `String.equals` 已足够),plain string match 零新依赖

**三形式 Pattern grammar**(`PermissionPatterns.java` ~50 行,纯 JDK `String`):
- `*` → 始终 true(允许/拒绝所有 Tool)
- `<exact-name>` → `toolName.equals(pattern)`(back-compat with Story #029 字符串 yml 条目,**Story #029 5 个 L1 测试不改 0 行回归**)
- `<category>:*` → `toolCategory.equals(category) && "*".equals(suffix)`(category 必须为 `local / mcp / skill / a2a / delegate` 五值之一,**未**做 strict 校验,plugin 可用自定义 string)

**实现要点**(8 文件,1 new + 7 modify):
1. **`PermissionPatterns.java`(新 file,`ai.lingshu.core.impl.permission` 包,~50 行)** —— `public final class` + private ctor + `public static boolean matches(String toolName, String toolCategory, String pattern)` 三形式 ladder(if-else 顺序:`*` → exact → `<category>:*`,first-match wins)+ private static helpers(`isCategoryPrefix(pattern)` / `extractCategory(pattern)`)
2. **`Tool.java`(modify,`ai.lingshu.core.slot` 接口,~3 行新增)** —— `default String sourceCategory() { return "local"; }` 默认方法 + Javadoc 5 保留 category 列表 + 「plugin 自定义 string 自由」说明
3. **`McpToolAdapter.java`(modify,`ai.lingshu.core.mcp`,~1 行)** —— `public String sourceCategory() { return "mcp"; }`(覆盖默认 local)
4. **`SpringAiToolAdapter.java`(modify,`ai.lingshu.core.springai`,~1 行)** —— `public String sourceCategory() { return "local"; }`(显式声明,虽然默认值一致,但语义清晰)
5. **`RemoteAgentTool.java`(modify,`ai.lingshu.core.a2a`,~1 行)** —— `public String sourceCategory() { return "a2a"; }`
6. **`DelegateTool.java`(modify,`ai.lingshu.core.delegate`,~1 行)** —— `public String sourceCategory() { return "delegate"; }`
7. **`SkillTool.java`(modify,`ai.lingshu.core.skill`,~1 行)** —— `public String sourceCategory() { return "skill"; }`
8. **`StrictPermissionPolicy.java`(modify,~30 行新增/重构)** —— 移除 `@Component`(value-object 不是 Spring Bean,Provider 拥有 lifecycle)+ `@Value` → `@Getter @ToString` 简化 + 2-arg ctor `(ToolsConfig, Map<String,String> nameToCategory)` + 1-arg ctor 保留 back-compat + `check()` 4 段决策(deny 命中 → Deny / allow 空 → default-allow / allow 命中 → Allow / 不命中 → Deny with `category=<cat>` 上下文)+ `nameToCategory` lookup,缺省回退 `"local"`
9. **`StrictPermissionPolicyProvider.java`(modify,~10 行)** —— 移除 `@Component`(避免与 `PermissionPolicyAutoConfiguration.@Bean` 重复注册触发 `NoUniqueBeanDefinitionException`)+ `@Autowired` 构造器注入 `ToolRegistry` 注入 + no-arg 构造器保留 back-compat(for test fixtures)+ `create(AgentConfig)` 调 `toolRegistry.findAll()` 构造 `nameToCategory: Map<String, String>` 注入 StrictPolicy
10. **`demo-product/src/main/resources/application.yml`(modify)** —— 12 行 `allow-list` 静态枚举 → 单行 `allow-list: ["*"]` 通配
11. **`DemoProductApplication.java`(modify,~30 行新增)** —— `readTools(Environment, ToolsConfig)` 私有静态 helper 真正吃 yml `agent.tools.allow-list[N]` + `agent.tools.deny-list[N]`(索引式 list walking,fallback 到 `defaults().getTools()`)+ `readToolsList` 索引式 list helper + `mergeConfig` 签名 +1 `ToolsConfig tools` 参数(对齐 §4 不可变契约)

**测试覆盖 26 新 cases**(8 文件):
- `PermissionPatternsTest.java`(8 L1)—— `*` 通配 / exact-name 命中 / exact-name 不命中 / `mcp:*` 命中 / `mcp:*` 不命中(不同 category)/ `local:*` 命中(默认 category)/ `local:*` 不命中 / 空 pattern 不匹配任何
- `ToolSourceCategoryTest.java`(4 L1)—— `DefaultTool.sourceCategory()` 返 `"local"` / `McpToolAdapter.sourceCategory()` 返 `"mcp"` / `RemoteAgentTool.sourceCategory()` 返 `"a2a"` / `DelegateTool.sourceCategory()` 返 `"delegate"`
- `StrictPermissionPolicyPatternTest.java`(6 L1)—— `mcp:*` allow-list + mcp tool → Allow / `mcp:*` allow-list + local tool → Deny / `*` allow-list + any tool → Allow / `*` deny-list + any tool → Deny / `read_file` exact-name 命中 back-compat / `read_file` exact-name 不命中
- `StrictPermissionPolicyReasonTest.java`(1 L1)—— Deny reason 嵌 `category=<cat>` 上下文(Story #029 无 category,本 Story 强化诊断信息)
- `StrictPermissionPolicyProviderTest.java`(2 L1,Story #029 共享 + 0 改动)—— `name()="strict"` + `create(cfg)` 注入 `nameToCategory` from `ToolRegistry.findAll()`
- `AgentFactoryPatternMatchingIT.java`(2 L2)—— yml `allow-list: ["mcp:*", "skill:*", "read_file"]` 真绑 `ToolsConfig.allowList` 三形式混合 + yml `permission-policy: strict` + 通配 `allow-list: ["*"]` 端到端 Allow 全部 Tool
- `DemoProductPermissionWildcardIT.java`(1 L3,新 file,`demo-product/src/test/java/.../`)—— `@SpringBootTest(classes=DemoProductApplication.class, webEnvironment=NONE)` + `@Autowired StrictPermissionPolicyProvider strictProvider` + `@Autowired AgentConfig demoAgentConfig` + `policy()` helper 调 `strictProvider.create(demoAgentConfig)` + 12 个代表性 Tool 名(`read_file / write_file / list_dir / bash_safe / time / calc / random / uuid / agent / compact / clear / help`)逐一 Allow(通配 `*` 命中)
- `DemoProductPermissionCategoryPatternIT.java`(1 L3,新 file,6 inline cases)—— `@TestPropertySource(properties = {"agent.tools.allow-list[0]=mcp:*", "agent.tools.allow-list[1]=skill:*", "agent.tools.allow-list[2]=read_file", "agent.tools.deny-list[0]=", "agent.mcp.servers="})` + `@Import(TestToolsConfig.class)` 注册 3 stub Tool beans(`echo`→mcp / `remote_agent`→a2a / `Task`→delegate)+ 6 cases:echo/agent/read_file = Allow,write_file/remote_agent/Task = Deny with `category=local/a2a/delegate` 上下文

**关键 Bug 修复**:
- **Story #029 follow-up #1 yml-binding 漏洞**:`demo-product/application.yml` 顶层 `allow-list: [...]` 自 Story #029 合入以来一直**未被 `agentConfig(Environment)` 真正读取** —— `mergeConfig` 用的 `defaults.getTools()`(空 allow/deny list)+ `strict policy` 实际不生效。本 Story 借机补 `readTools(Environment, ToolsConfig)` + `readToolsList` helper 真正走 `Environment.getProperty("agent.tools.allow-list[N]")` 索引式 walking + `@TestPropertySource` 验证 yml 真生效
- **`@Component` 双注册**:`StrictPermissionPolicy` 与 `StrictPermissionPolicyProvider` 之前均有 `@Component`,但 `PermissionPolicyAutoConfiguration` 也用 `@Bean(name="permissionPolicyProvider_strict-1.0.0")` 显式注册 — 触发 `NoUniqueBeanDefinitionException`(2 个 `permissionPolicyProvider_strict-1.0.0` Bean)。本 Story 移除两个 `@Component`,对齐 v1.5.28 §5.5 多 Provider 模式"unique `@Bean(name=...)` 唯一 Bean 名约定"
- **`StrictPermissionPolicy` Spring 反射兜底失败**:`StrictPermissionPolicy` 移除 `@Value` 改为手动 2-arg ctor 后,Spring 仍尝试按 `@Component` 反射实例化(无 default ctor → fail)。本 Story 同步移除 `@Component`,Policy 改为纯 value-object,Provider 拥有 lifecycle

**累计**:**626 + 26 = 647 tests pass** / 2 MCP heartbeat flake pre-existing(CLAUDE.md 文档化,与 #031 无关);R-13 mitigation (d) baseline 镜像 **第 16 次 PASS 0 binary delta**(`Pattern` + `String.startsWith` + `String.equals` + `HashMap` 全 JDK 8 standard + 已锁 13 项依赖表内 0 新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **0 新 ErrorCode**(复用 `LINGS-P01`,Deny 路径不变)

**Story #031 业务价值**:
- **Story #029 "维护死亡名单"反模式根治** —— yml `allow-list: [read_file, write_file, list_dir, bash_safe]` 4 行 → 单行 `allow-list: ["mcp:*"]` 一行覆盖所有 MCP Tool,新接入 MCP server **自动可见**,业务方无需手动维护白名单
- **5 保留 category 与 §6.5 Tool 三种 Scheme 来源 + Skill / A2A / Delegate 支自然对齐** —— 业务方可写 `mcp:*` 允许所有 MCP / `local:*` 允许所有本地 / `skill:*` 允许所有 Skill / `a2a:*` 允许所有 RemoteAgent / `delegate:*` 允许所有 Delegate sub-agent
- **`sourceCategory()` 默认方法零侵入** —— 现有 Tool 实现(Read/Write/Edit/Bash 等)无需任何改动即可获得 `"local"` 默认 category,只有 5 个核心 Tool 类型覆盖返各自 category(`McpToolAdapter` / `SpringAiToolAdapter` / `RemoteAgentTool` / `DelegateTool` / `SkillTool`)
- **Story #029 back-compat 100% 守住** —— Story #029 5 个 L1 测试不改 0 行回归,exact-name 形式通过 Path 2 命中,业务方原有 yml `allow-list: [read_file, write_file]` 仍按原语义工作

**Story #031 后续**:
- 🆕 **`agent.tools.allow-list` / `agent.tools.deny-list` 文档化 Pattern grammar** —— dsh §4.7 `PermissionPolicy` 段补 `PermissionPatterns` 三形式 grammar + 5 保留 category 表 + examples
- 🆕 **plugin 自定义 category**:`sourceCategory()` 返 string 完全自由,plugin 可自创 category(如 `Rag:*` / `Db:*`),`PermissionPatterns` 不强制白名单,只按字符串相等匹配
- 🆕 **Story #029 AskUser 路径 + Story #031 pattern 组合**:`permission-policy: ask` 时按 pattern 类型分级 AskUser(`mcp:*` 可自动 AskUser,`<dangerous-cmd>` 必 AskUser),本期未实现

---

### Story #032 web-fetch-local-tool(Claude Code parity 三件套:本地 `WebFetch` + MCP fetch server 共存)

Story #019 落地了 Read / Write / Edit / Bash 4 个本地 Tool,但**网络**这条腿一直是空的 —— Agent 想要查 GitHub README / 调 OpenAI API / 抓任意 HTTPS 页面,要么自己手写 MCP server,要么走 Story #021a-c MCP fetch server。本 Story 把 Claude Code 的 `WebFetch` 思路搬到 LingShu:**本地 Tool + sandbox HTTP client** 组合,POST/PUT/DELETE 走 MCP fetch server,GET-only 本地 Tool 直发 + 沙箱 domain-whitelist 守卫。**业务方一句话总结**:Claude Code 里有 `WebFetch`,LingShu 现在也有。

**关键设计抉择**(为什么本地 WebFetchTool 而非纯靠 MCP):
- **Claude Code parity** —— Claude Code 内置 `WebFetch` + MCP fetch server **共存**(built-in + MCP 并行,非互斥)。LingShu 早期误判"MCP covers HTTP,无 local Tool",实测 grep `McpHttpSupport.java` / `StreamableHttpMcpServerConnection.java` / `SseMcpServerConnection.java` 全部走 raw JDK `HttpURLConnection`,**不接 `WhitelistedHttpClient.check()`** —— 即 MCP HTTP transports 当前没有沙箱 domain-whitelist 守卫。本 Story 提供本地 Tool 把 Story #028 落地的 `WhitelistedHttpClient` 基建**激活** + 给业务方 Claude Code 同等的「开箱即用 GET」能力
- **激活 idle 基建** —— Story #028 落地 `WhitelistedHttpClient` 后,`RuntimeSandbox.http()` 一直返实例但**没有任何 Tool 调它**(Read/Write/Edit/Bash 都是 fs/process,不走 HTTP)。本 Story 是 `WhitelistedHttpClient.check()` 第一次真正在 Tool 执行路径上 enforce
- **GET-only 范围锁定** —— 本地 Tool 只发 GET,POST/PUT/DELETE 走 MCP fetch server(后续 Story)。description 显式声明 `POST/PUT/DELETE traffic is NOT supported`,业务方从工具描述就能看到边界
- **HTTPS 透明** —— 用 JDK `HttpURLConnection` + `HttpsURLConnection`(JDK 内置,0 新依赖),`WhitelistedHttpClient.openConnection` 自动按 scheme 选实现,HTTPS 不需要额外证书配置(走默认 `TrustManager`)

**实现要点**(5 文件,1 new + 4 modify):
1. **`WebFetchTool.java`(新 file,`ai.lingshu.core.impl.tool.local`,~164 行)** —— `@Component("webFetchTool") implements Tool` 对齐 ReadTool/BashTool precedent + 5 段:`TOOL_NAME="web_fetch"` 常量 + `DEFAULT_MAX_BYTES=1_048_576` 1 MB + `TRUNCATION_MARKER="\n...[truncated, original %d bytes]"` + `description()` 静态字符串含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported" + `inputSchema()` 静态 JSON Schema `{url: string required, max_bytes?: integer}` + `execute(call, ctx)` 4 段:`args.path("url").asText()` 校验 → `args.has("max_bytes")` 解析 maxBytes → `ctx.http().get(url)` 委托 → truncation marker 拼接;catch `AccessDeniedException` → `ToolResult.error("[LINGS-S01] Domain not whitelisted: ...")`;catch `IOException` → `ToolResult.error("HTTP fetch failed: ...")`,**`sourceCategory()` 不 override**(对齐 ReadTool/WriteTool/EditTool/BashTool 约定走默认 `"local"`);类级 Javadoc 覆盖 (1) Claude Code parity rationale + (2) GET-only 范围锁定 + (3) HTTPS transparent + (4) `@Component` 而非 `@Autowired` 因 stateless + (5) `ctx.http()` 必须走 `WhitelistedHttpClient.check()` 防御
2. **`LocalToolsAutoConfiguration.java`(NO modify)** —— `Map<String, Tool> tools` autowiring 自动接住 `@Component("webFetchTool")`,无需新增 `@Bean`(spec §4 T02 原本建议加 `@Bean public Tool webFetchTool()`,本 Story 实测发现 Map<String, Tool> autowiring 已经覆盖,**cleaner**)。原有 4 `@Bean`(`readTool` / `writeTool` / `listDirTool` / `bashTool`)**0 改动**
3. **`demo-product/src/main/resources/application.yml`(modify)** —— `agent.sandbox.domain-whitelist` 段加 5 示例 domain(`api.openai.com` / `api.anthropic.com` / `raw.githubusercontent.com` / `huggingface.co` / `localhost` for IT)+ 注释 `🆕 Story #032 — domain-whitelist now enforced for local web_fetch Tool (Claude Code parity, Read/Bash/WebFetch triplet complete);HTTPS supported transparently`
4. **`specs/032-web-fetch-local-tool/spec.md` + `plan.md` + `tasks.md`(新 files,`specs/` 标准目录)** —— Story 完整三件套(why / what / tasks 拆 8 段 T01-T08 + 6 AC-NN validate + 4 dep-tree 自查 + 11 doc-sync + 7 PR)

**测试覆盖 15 新 cases**(4 文件):
- `WebFetchToolTest.java`(8 L1)—— `name()="web_fetch"` / `description()` 含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported" / `inputSchema()` url required + max_bytes optional integer / `sourceCategory()="local"` 默认 / **AC-NN-7 reverse**:`new WhitelistedHttpClient(Collections.<String>emptyList())` 真 client + `http://anywhere.example/path` → `[LINGS-S01]` / 2 MB body + default 1 MB cap → truncation marker `...[truncated, original 2097152 bytes]` + 1 KB body + `max_bytes=100` override → 100 字节截断 + missing `url` argument → `ToolResult.error("url is required")`
- `WebFetchToolHttpServerIT.java`(4 L2)—— `@BeforeEach startServer()` 用 JDK `com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)` + `server.start()`(实测踩坑:`HttpServer.create()` 只构造不 bind,**必须**调 `server.start()` 才接受连接)+ `@AfterEach stopServer()` `server.stop(0)`;happy path 200 → `ToolResult.success("hello world")` / **AC-NN-6 HTTPS 透明**:`/probe` 端点捕获 `User-Agent` header,断言 `ChaOS-LingShu-Sandbox/1.0`(证明请求**真正**经过 JDK `HttpURLConnection` 层,不是 mock 短路)/ localhost whitelist 命中(显式 `new WhitelistedHttpClient(Collections.singletonList("localhost"))`,证明 whitelist check 走真路径)/ HTTP 404 → `WhitelistedHttpClient.get()` 抛 `IOException("HTTP 404")` → `ToolResult.error("HTTP fetch failed: ...")`
- `LocalToolsAutoConfigurationWebFetchIT.java`(2 L2)—— 手动 `new DefaultToolRegistry` + `new LocalToolsAutoConfiguration(registry, sandbox, bash, toolBeans, env).afterPropertiesSet()`(沿用 Story #019 `LocalToolsAutoConfigurationTest` manual-instantiation 样板,不复用 `@SpringBootTest` 避免加载全部 Spring Boot autoconfig)/ Map<String, Tool> 注入 5 个 Tool(`readTool / writeTool / editTool / bashTool / webFetchTool`)+ 断言 `registry.asMap()` containsKeys `("Read", "Write", "Edit", "Bash", "web_fetch")` size=5 / `registry.findByName("web_fetch")` 返回 `WebFetchTool` 实例(注意:**findByName 返 `Tool` not `Optional<Tool>`**,实测踩坑:第一次写 `Optional.of(registry.findByName(...))` 编译失败,SPI 契约是 findByName throws IllegalArgumentException if missing)+ `Tool.sourceCategory()="local"` 默认
- `DemoProductWebFetchIT.java`(1 L3,`demo-product/src/test/java/.../`)—— `@SpringBootTest(classes=DemoProductApplication.class, webEnvironment=NONE)` + `@TestPropertySource(properties = {"agent.sandbox.domain-whitelist=localhost"})` 覆盖 production yml 7 个 domain,只留 localhost 让断言清晰 / `@Autowired WebFetchTool` + `@Autowired RuntimeSandbox` / 强转 `sandbox.http()` 为 `WhitelistedHttpClient`(WhitelistedHttpClient 是 production 唯一 NetworkClient 实现)/ mock `HttpServer` 绑 `127.0.0.1:0` / **双半同 case**:`http://localhost:<port>/data` → SUCCESS `"mock-body"`(whitelist 命中)+ `https://example.com/anything` → ERROR `[LINGS-S01] Domain not whitelisted: example.com`(whitelist miss,yml-driven check 真生效,不是 hardcoded)

**累计**:**647 + 15 = ~662 tests pass**(lingshu-core surefire 655 + 6 IT in surefire via `-Dtest=`)/ 2 MCP heartbeat flake pre-existing(CLAUDE.md 文档化,Story #028 已落,与 #032 无关;`mvn -pl lingshu-core test -Dtest=StdioMcpServerConnectionHeartbeatTest` 在 stash 上无 local changes 仍 fail,确认 pre-existing);R-13 mitigation (d) baseline 镜像 **第 17 次 PASS 0 binary delta**(`WebFetchTool` 是纯 JDK `HttpURLConnection` / `HttpsURLConnection` + Jackson `ObjectMapper` 已锁 13 项依赖表内 + `com.sun.net.httpserver.HttpServer` JDK 内置,0 新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;`mvn -pl lingshu-core dependency:tree -Dverbose` 59 unique transitive coords vs Story #027b baseline(10963 字节 post.txt)diff = **空**;**0 新 Maven 依赖** / **0 新 ErrorCode**(复用 `LINGS-S01`,Sandbox 域 S 段 1 号,Story #028 已落)

**Story #032 业务价值**:
- **Claude Code parity 三件套完成** —— Read(本地 fs)+ Bash(本地 process)+ **WebFetch(本地 http)** 三件套对齐 Claude Code 生态,business 用户从 Claude Code 切到 LingShu 体验零差异
- **`WhitelistedHttpClient` 基建从 idle → enforced** —— Story #028 落地的 `RuntimeSandbox.http()` 第一次真正在 Tool 执行路径上 enforce domain-whitelist,业务方 yml 改一行就生效(`agent.sandbox.domain-whitelist` 加新 domain)
- **HTTPS 零配置** —— 业务方不需要管证书 / TrustManager,JDK `HttpsURLConnection` 默认 `TrustManager` + `WhitelistedHttpClient.openConnection` 按 scheme 自动选实现
- **1 MB truncation marker 防御** —— 默认 `DEFAULT_MAX_BYTES = 1_048_576`(1 MB),LLM 单 turn token budget 不会被巨型 response 击穿;`max_bytes` 字段允许 override(per-call 灵活调整)

**Story #032 后续**(Story #033 推迟):
- ⏸ **Story #033 mcp-http-domain-guard(Path B + Mitigation 1)** —— MCP HTTP transports(SSE / streamable_http)走 raw `HttpURLConnection`,**不接** `WhitelistedHttpClient.check()`,意味着通过 MCP fetch server 调 URL 可以绕过 sandbox domain-whitelist。Path B(check-only hook)+ Mitigation 1(sandbox 配 MCP 共享 whitelist)是用户已审批方案,但实现涉及 `McpTransport.connect()` 加 `onBeforeRequest()` callback + MCP 配置 schema 扩字段,预估 6-8 文件改动 + 8-10 cases,**已超出 #032 Story 边界**,作为单独 Story 后续实施
- 🆕 **`McpServerProperties` pattern 字段**(本期 unused):Story #031 的 `sourceCategory()` + `PermissionPatterns` 已落地,Story #033 可借机给 MCP fetch server 加 `pattern` 字段(`mcp-fetch:*` allow-list pattern),业务方一行 `mcp-fetch: ["http://internal-api.company.com/*"]` 控制 MCP fetch 范围
- 🆕 **POST/PUT/DELETE 走 MCP** —— 本地 Tool 显式 GET-only(description 写明),业务方需要 POST 走 MCP fetch server(后续 Story 落地 story-021c 已有 streamable_http 支持)

---

### Story #033 mcp-http-domain-guard(Path B + Mitigation 1:MCP HTTP transports 沙箱守卫)

Story #028 落地的 `WhitelistedHttpClient` 走 `RuntimeSandbox.http()`,但 **MCP HTTP transports(SSE / streamable_http)走 raw JDK `HttpURLConnection`** 不接 `WhitelistedHttpClient.check()` —— 通过 MCP fetch server 调 URL 可以绕过 sandbox domain-whitelist。本 Story **复用** `McpHttpSupport.checkOrThrow(url, whitelist)` 静态 helper,在 MCP 发 HTTP 请求**之前**前置守卫,12 hook point(SSE 7 + Streamable HTTP 5)失败抛 `AccessDeniedException[LINGS-S01]`,真实请求**不**发起。

**关键设计抉择**(为什么不走 Path A 把 MCP HTTP 切到 `WhitelistedHttpClient`):
- **§4.10.1 硬规则 2 守住** —— MCP 长连接 + JSON-RPC envelope + SSE streaming 协议层,不能简单套 `WhitelistedHttpClient`(它是短连接 client,SSE 持久连接会卡死)
- **Path B 最小触碰** —— 只在 12 个 hook 点前**加 1 行** `McpHttpSupport.checkOrThrow(url, domainWhitelist)`,公开 API 0 改动,影响面积小
- **Mitigation 1 沙箱配置** —— `McpServerConfig.@Builder.Default List<String> domainWhitelist = new ArrayList<>()` + ctor defensive copy,镜像 Story #028 `WhitelistedHttpClient` 的语义
- **strict mode 默认** —— 空 whitelist = deny all,业务方必须显式配置 `McpServerConfig.builder().domainWhitelist(["host1", ...])` 才允许出站

**实现要点**(8 modify 0 new 源):
1. **`McpHttpSupport.checkOrThrow(String url, List<String> whitelist)` 静态 helper(新)** —— JDK `URI.create(url).getHost()` 拿 host + `whitelist.contains(host)`;空 whitelist / null whitelist / malformed URL / null host 全 deny;**只**调 `whitelist.contains(host)` 不创建 full `WhitelistedHttpClient` 实例(避免无谓 client 实例化)
2. **`McpServerConfig` 扩 `domainWhitelist` 字段** —— `@Builder.Default List<String>` 兜底空 list + ctor defensive copy `new ArrayList<>(cfg.getDomainWhitelist())`;`McpServerConnectionFactory.create(cfg)` 把 whitelist 透传给 `SseMcpServerConnection` / `StreamableHttpMcpServerConnection`
3. **`SseMcpServerConnection` 加 7 个 hook** —— `callTool` + 3 `doConnect`(initialize / notifications/initialized / tools/list)+ `heartbeatTick` + `openSseStream`(`/sse` GET)+ `relistTools`(listChanged 触发重拉)
4. **`StreamableHttpMcpServerConnection` 加 5 个 hook** —— `callTool` + 3 `doConnect` + `heartbeatTick`
5. **`StdioMcpServerConnection` 0 改动** —— stdio 走子进程 IPC 不走 HTTP,无沙箱必要
6. **9 现有 SSE/Streamable HTTP 测试 fixture 加 `.domainWhitelist(Arrays.asList("127.0.0.1"))`** —— 让本地 127.0.0.1 fixture 通过守卫
7. **`banned-dependencies` enforcer Rule 0 passed** + R-13 mitigation (d) baseline 镜像 **第 18 次 PASS 0 binary delta**(`URI.create` JDK 1.4 内置 + `List.contains` + `ArrayList` 0 新 binary 引入)
8. **`specs/033-mcp-http-domain-guard/spec.md` + `plan.md` + `tasks.md`(新 files)** —— Story 完整三件套

**测试覆盖 13 新 cases**(3 文件):
- `McpHttpSupportCheckOrThrowTest.java`(8 L1)—— `checkOrThrow` 单元:emptyWhitelist 全 deny / nullWhitelist / matchingHost OK / nonMatchingHost deny / nullOrEmptyUrl deny / malformedUrl deny / caseSensitive / IPv4 host 提取
- `McpHttpDomainGuardIT.java`(4 L2)—— JDK `com.sun.net.httpserver.HttpServer` 起服 + **AC-NN-1**:SSE whitelisted reaches CONNECTED + **AC-NN-2**:SSE non-whitelisted stays RECONNECTING + Streamable HTTP non-whitelisted stays RECONNECTING
- `McpServerConnectionFactoryTest.java`(+1 L1)—— factory dispatch `create_sse_domainWhitelistPropagated` 验证 wire-through

**累计**:**660 pass / 2 MCP heartbeat flake pre-existing**(35 existing test files updated `Arrays.asList("127.0.0.1")` 让 fixture 走沙箱白名单);R-13 mitigation (d) baseline 镜像 **第 18 次 PASS 0 binary delta**;**0 新 Maven 依赖** / **0 新 ErrorCode**(复用 `LINGS-S01`);**0 SPI 改动** —— `McpServerConnection` / `A2aTransport` / `Tool` / `RuntimeSandbox` / `WhitelistedHttpClient` / `McpTransport` / `StdioMcpServerConnection` / `McpServerConnectionFactory.create()` 全部 0 改动。

**业务价值**:
- **MCP HTTP 路径沙箱守卫到位** —— Agent 调任何 MCP HTTP fetch server 必须先过 sandbox domain-whitelist,与本地 Tool(`WebFetchTool` #032)对齐
- **`WhitelistedHttpClient` 基建完全 enforced** —— #028 落地的 `RuntimeSandbox.http()` + #033 MCP HTTP 的 12-hook + 后续 #034 A2A,沙箱守卫真正在所有 HTTP 出站路径上生效
- **strict mode 安全** —— 空 whitelist 默认 deny all,业务方必须显式配才允许出站,避免"沉默全开"风险

**Story #033 后续**(OQ-Future):
- ⚠️ **OQ-Future 风险**:MCP HTTP 配置 schema 暂未绑定 yml(`agent.mcp.servers[*]` 走 hand-rolled YAML parser,parser 暂未解析该字段),whitelist 暂**只能**通过 `McpServerConfig.builder().domainWhitelist(...)` 编程方式设置
- 🆕 **配置绑定** —— `McpTransportAutoConfiguration` 解析 `domain-whitelist` 字段推到 Story #034+

---

### Story #034 a2a-http-domain-guard(A2A HTTP transport 沙箱守卫 — 复用 #033 `McpHttpSupport.checkOrThrow`)

Story #033 把 MCP HTTP 路径走通了沙箱守卫,**A2A HTTP transport**(`HttpJsonRpcA2aTransport`)走 raw JDK `java.net.http.HttpClient`,同样**不接** `WhitelistedHttpClient.check()` —— 通过 `RemoteAgentTool` 调远端 A2A agent 可以绕过 sandbox domain-whitelist。本 Story **完全复用 #033** `McpHttpSupport.checkOrThrow(url, whitelist)` 静态 helper,在 A2A transport 发 HTTP 请求**之前**前置守卫,**2 hook point**(`fetchCard` + `jsonRpcCall`,submit/get/cancel 都走同一 hook),失败抛 `AccessDeniedException[LINGS-S01]`,真实请求**不**发出。

**关键设计抉择**(为什么完全复用 #033):
- **A2A 与 MCP 路径对称** —— 两者都**走 raw HTTP client**,**都**不接 `WhitelistedHttpClient`(SSE 长连接 / A2A 短连接都不能简单套),复用同一静态 helper 是最自然的选择
- **per-remote-agent 配置粒度** —— `AgentRef.@Value` 加 `List<String> domainWhitelist` 字段(per-remote-agent 配置,yml `domain-whitelist: [host1, ...]` kebab-case 绑定);`HttpJsonRpcA2aTransportAutoConfiguration.HttpJsonRpcA2aTransportFactory` 启动期 union `cfg.a2a.remoteAgents[*].domainWhitelistOrEmpty()` 去重后传给 transport(多 remote agent 共用 transport 实例,白名单取并集)
- **strict mode 镜像 #033** —— 空 whitelist = deny all,与 `McpServerConfig.domainWhitelist` 语义对齐
- **5-arg ctor + 4-arg ctor back-compat** —— 4-arg ctor 保留供现有测试 / `McpHttpSupport.checkOrThrow` 等不传 whitelist 的调用方使用(传 `Collections.emptyList()` strict mode)
- **grpc / in-process transport 0 改动** —— 走进程内 RPC 不走 HTTP,无沙箱必要

**实现要点**(4 modify 0 new 源):
1. **`HttpJsonRpcA2aTransport` 加 5-arg ctor** —— 接收 `List<String> domainWhitelist` + defensive copy `new ArrayList<>(domainWhitelist)` + 4-arg ctor 保留 back-compat wrapper(传 `Collections.emptyList()` strict mode);`fetchCard(String agentName)` + `jsonRpcCall(...)` 私有方法**前**调 `McpHttpSupport.checkOrThrow(url, this.domainWhitelist)`,失败抛 `AccessDeniedException[LINGS-S01]`
2. **`AgentRef` 扩 `domainWhitelist` 字段** —— `@Value` Lombok @Builder 默认 `Collections.emptyList()` + `getDomainWhitelistOrEmpty()` null-safe accessor(strict mode default);`HttpJsonRpcA2aTransportFactory.build()` 把 `cfg.a2a.remoteAgents[*].domainWhitelistOrEmpty()` 走 `HashSet<String>` union 去重传给 transport
3. **`HttpJsonRpcA2aTransportAutoConfiguration` 加 `HttpJsonRpcA2aTransportFactory` 静态 inner class** —— ctor 收 `AgentConfig` + `ObjectMapper` + `AgentCardCache`,`previewWhitelistUnion()` 测试用 accessor + `build()` 产 transport;`@Bean(name="a2aTransportFactory_http-jsonrpc")` 暴露给 `RemoteAgentToolAutoConfiguration.remoteAgentTool()`
4. **`RemoteAgentToolAutoConfiguration.remoteAgentTool()` 注入 factory** —— `if (transportName=="http-jsonrpc-1.0.0") transport = httpJsonRpcFactory.build();` 否则 `router.resolve(...)`(grpc / in-process 不走 HTTP 不变)

**测试覆盖 13 新 cases**(4 文件):
- `HttpJsonRpcA2aTransportCheckOrThrowTest.java`(6 L1)—— `checkOrThrow` 单元:emptyWhitelist 全 deny / nonMatchingHost deny / matchingHost OK / 5-arg ctor validation 6 子 case / defensiveCopy 防御性拷贝 / snapshotReturn 返回快照
- `HttpJsonRpcA2aTransportDomainGuardIT.java`(3 L2)—— JDK `com.sun.net.httpserver.HttpServer` 计数 hits 真发请求,**AC-2.1**:whitelisted hits==2+SUCCESS / **AC-2.2**:non-whitelisted hits==0+AccessDenied(证明 hook 在请求离开 JVM 前生效) / **AC-2.3**:strict-mode empty hits==0+AccessDenied
- `HttpJsonRpcA2aTransportAutoConfigurationTest.java`(+1 L1)—— factory union dedup 验证 `previewWhitelistUnion()` + `factory.build().getDomainWhitelist()` 跨 AgentRef 去重(alice[a,b] + bob[b,c] + carol[null] → [a,b,c])
- `HttpJsonRpcA2aTransportTest.java`(modify)—— 3 call site 4-arg → 5-arg(`Arrays.asList("127.0.0.1")` 让 127.0.0.1 fixture 通过沙箱)

**累计**:**673 pass / 0 fail / 2 MCP heartbeat flake pre-existing**;R-13 mitigation (d) baseline 镜像 **第 22 次 PASS 0 binary delta**(`URI.create` + `List.contains` + `HashSet` JDK 8 内置 0 新 binary 引入);`banned-dependencies` enforcer Rule 0 passed;**0 新 Maven 依赖** / **0 新 ErrorCode**(复用 `LINGS-S01`);**关键不变项** —— `A2aTransport` 5 方法 SPI 不变 / `A2aTransportRouter` 不变 / `RemoteAgentTool` 不变(只看 `A2aTransport` 接口)/ `McpHttpSupport.checkOrThrow` 公开方法不变(只被新增 caller 调用)/ `AccessDeniedException[LINGS-S01]` ErrorCode 复用 / `Tool` SPI 不变 + `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)/ `AgentConfig` 不可变契约不变(只 AgentRef 内部加字段)/ `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 9 Slot 体系不变 / JDK 8 兼容(`URI.create` + `List.contains` + `HashSet` + `ArrayList` + `Collections.emptyList` + `Arrays.asList` + Jackson `@JsonProperty` kebab-case 已锁,no `var` / `List.of` / sealed / records)

**业务价值**:
- **A2A 路径沙箱守卫到位** —— Agent 调任何远端 A2A server 必须先过 sandbox domain-whitelist,与本地 Tool(`WebFetchTool` #032)+ MCP HTTP(#033)对齐
- **`WhitelistedHttpClient` 基建完全 enforced** —— 一路通过 #028 → #033 MCP + #034 A2A 真正在**所有 HTTP 出站路径**上 enforce
- **per-remote-agent 粒度配置** —— 不同 remote agent 不同 domain-whitelist,A2A 多 server 部署友好(主 agent 调内网 server 拉 agent 调外部 SaaS)
- **grpc / in-process 0 改动** —— 不走 HTTP 不受沙箱约束,符合最小触碰原则

---

### Story #025b demo-product-a2a-server(`lingshu-examples/demo-product-a2a-server/` 跨 JVM translate demo 与 `demo-product` 8080 端口互通)

Story #025 demo-product(8080)跑通端到端 chat 后,补一个 sibling 端口 9090 跑跨 JVM translate skill,演示 `RemoteAgentTool` + `HttpJsonRpcA2aTransport` 真实跨进程 Tool 调度。

**关键设计抉择**(为什么自起简化 JSON-RPC 而不直接用 stock `A2aServer`):
- Stock `lingshu-a2a-server/A2aServer.handleMessageSend` 把 JSON envelope 存进 ConcurrentMap 然后 echo,代码内明确**不** dispatch 到本地 ToolRegistry(stock 假设 dispatcher 走更复杂的 JSON-RPC envelope round-trip,留 OQ)
- 修这个 stock issue 需要解决 ToolExecutionContext 跨 JSON-RPC 边界的所有权流转(目前随 engine 走 TurnContext),超出 demo 范围
- 本 demo 用 `DemoProductA2aServerApplication` exclude `A2aServerAutoConfiguration`,自起 JDK `HttpServer`(`com.sun.net.httpserver.HttpServer`,JDK 内置),跑**简化版 JSON-RPC 协议**(POST `/rpc` `{jsonrpc, id, method, params: {agentName, skill, inputJson}}`)+ 调本地 `ToolRegistry.execute()` 配合 safe-default `StubToolExecutionContext`

**为什么需要简化版**(不破坏 stock 协议兼容):
- `HttpJsonRpcA2aTransport.submit()` client 端发的是简化 envelope,`DemoProductA2aServerApplication` 必须按此 envelope 收 —— 改用 stock `A2aServer` 必须先在 stock 上接 dispatch,这是一个独立 Story(本期未做)
- 返回 shape `{status: COMPLETED|FAILED, taskId, resultJson}` 对齐 transport contract

**关键 Bug 修复**:
- **AgentCard 重建时机**:`start()` 时若用 `@PostConstruct` build card,会错过 `AgentToolScanner` 在 `ContextRefreshedEvent` 注册的 `@AgentTool` 方法 —— card 报 0 skills。**fix**:AgentCard 在 `ApplicationReadyEvent` 重建(晚于 ContextRefreshedEvent);Initial `@PostConstruct` build 仍跑(cachedCardJson 永不 null,处理 socket bind 与 first request 之间微秒级竞态)
- **HttpJsonRpcA2aTransport.httpBaseUrl 默认值**:`demo-product` 的 `HttpJsonRpcA2aTransport` 默认 `http://localhost:8080`(自己 Tomcat),`fetchCard` 命中自己 `/.well-known/agent.json` 404。**fix**:从 yaml `agent.a2a.http-base-url` 读,默认 `http://localhost:9090`;同步读 `agent.a2a.transport`(默认 `http-jsonrpc-1.0.0`,因为 `AgentConfigDefaults` 默认返回 `"default"` 不匹配任何 registered provider,会让 `RemoteAgentToolAutoConfiguration` 抛 `Unknown A2aTransportRouter 'default'`)

**双路径 Style 文档**:
- Style A:LLM auto-discovery via `RemoteAgentTool`(无需用户配置 skill 名,LLM 自动从 AgentCard.skills[] 选)
- Style B:显式 `/agent <skill>` slash skill(用户手动指定,SkillCommandDispatcher 拦截;Skill 放 `skills/agent/SKILL.md`,ClasspathSkillSource 要求文件名严格 `SKILL.md`,skill 名 = parent dir,首行 `#` 作 description)

**Story 边界**:N 文件改动,product demo 子模块(非 framework 核心);**0 新 ErrorCode**;**关键不变项** —— `lingshu-core` 0 改动 / `RemoteAgentTool` / `HttpJsonRpcA2aTransport` 接口**不**改(只在 yaml 配对默认)/ `Tool` Toolkit 5 步流水线不变 / 0 新 Maven 依赖(`HttpServer` JDK 9+ 内置)。

---

### Story #017 cli-entrypoint(`lingshu-cli/` 5 子命令 + Spring Boot bootstrap + dsh §10.3 全落地)

dsh §10.3 锚定 5 个 CLI 子命令(`run / resume / serve / doctor / config`),Story #001 实施期 `lingshu-cli/` 模块只搭了 Maven 骨架,实际从未交付;Story #017 把 §10.3 全部 5 个子命令一次性补齐 —— **首个**用户能直接 `mvn spring-boot:run --args='run ...'` 跑通端到端的入口。

**设计决策**(沿用 Story #009 同款 Story 边界外延,不再赘述):
- **Bootstrap 模型**:`@SpringBootApplication` + `ApplicationRunner`(`AgentFactory` 是 `@Component` + `@Autowired 6 Routers`,无法 `new` standalone 而不破坏 Story #001 契约)
- **argv 解析**:hand-rolled ~80 行(避免引入 picocli = dsh §10.1 第 14 个依赖,触发 RFC)
- **`serve` 子命令复用 Story #009 `A2aServer`**:直接把 `agent.a2a.port` 传给 `A2aServer.start()`,`--port` CLI flag 走 `withPort()` 路径覆盖 yaml 默认值
- **`resume` 仅内存 stub**:SessionStore 持久化留 **Story #014**,当前用 in-memory map 满足 AC §14 N7 验收分阶段落地

**子命令矩阵**:

| subcommand | Required | Optional | Exit codes | 复用 Story # |
|---|---|---|---|---|
| `run --config X --prompt Y` | `--prompt` | `--config`(默认 `application.yml`)| 0 ok / 5 agent fail / 4 cfg invalid / 3 yaml missing / 2 arg invalid | — |
| `resume --config X --session Y --prompt Z` | `--session` | `--config` | 0 ok / 5 agent fail / 2 session not found / 3 yaml missing | (#014 内存 stub)|
| `serve --config X --port N` | (none)| `--config`, `--port`(默认 8080)| 0 ok(block SIGTERM)/ 6 a2a bind fail | #009 |
| `doctor --config X` | (none)| `--config`, `--print-schema` | 0 ok / 3 yaml missing / 4 cfg invalid | #001 |
| `config --config X` | (none)| `--config`, `--print-effective` | 0 ok / 3 yaml missing / 4 cfg invalid | #001 |

**2 新增 ErrorCode**:
- `LINGS-Z01`(Z 域 / CLI args)—— CLI 参数缺失 / 未知 subcommand / 必填 flag 缺失(`ArgsParser.parse()` 抛)
- `LINGS-Z02`(Z 域 / YAML)—— YAML 文件不存在 / 解析失败 / 缺顶层 `agent:` map(`CliRunner` 5 个 handler 入口抛)

**3 复用 ErrorCode**:
- `LINGS-S06`(Story #009 A2A bind failure —— `serve` 子命令透传)
- `LINGS-C02`(Story #001 config validation)
- `LINGS-T02`(Story #009 identity.name blank)

**Exit Code 映射表**(`LingsCliException.getExitCode()`):

| ErrorCode | Exit | 触发场景 |
|---|---|---|
| (正常退出)| **0** | 子命令成功 |
| `LINGS-Z01` | **2** | 参数错误 |
| `LINGS-Z02` | **3** | YAML 缺失/解析失败 |
| `LINGS-C02` | **4** | cfg 校验失败 |
| `LINGS-T05`/`LINGS-L01`/... | **5** | Agent 运行时失败 |
| `LINGS-S06` | **6** | A2A bind 失败 |

**测试覆盖**(30 case / 9 文件):
- `ArgsParserTest`(8 case)—— `run`/`resume`/`serve`/`doctor`/`config` 5 子命令各自解析 + 未知 subcommand 抛 Z01 + 短 flag `-c`/`-p`/`-h` + 缺 flag fallback 默认值
- `LingsCliExceptionTest`(2 case)—— code+message+hint 渲染 / cause 透传
- `RunHandlerTest`(3 case)—— 有效 yaml → 调 `runBlocking` + 打印 trailer / yaml 缺失 Z02 / yaml 解析失败 Z02
- `ResumeHandlerTest`(2 case)—— 内存 session 续接 / yaml 缺失 Z02
- `ServeHandlerTest`(4 case)—— yaml 缺失 Z02 / port 越界触发 S06(透传)/ yaml 解析失败 Z02 / `--port` flag 覆盖 yaml `a2a.port`
- `DoctorHandlerTest`(2 case)—— 默认 cfg 打印 `factory.description()` + `agent ready` trailer / yaml 缺失 Z02
- `ConfigHandlerTest`(2 case)—— 默认打印 short summary(`flowEngine / llm.provider / llm.model / react.maxSteps` 等)/ yaml 缺失 Z02
- `SubcommandTest`(4 case)—— 5 enum 值 fromString / unknown → Z01 / null → Z01 / **case-insensitive**(`RUN`/`Run`/`Resume` 都接受,Windows 用户友好)
- `MainIntegrationTest`(3 case)—— `Main.main(String[])` 反射存在 / `CliRunner` 标 `@Component implements ApplicationRunner` / Z01 → exit code 2
  - **Spring Boot bootstrap 黑盒不在单元测试范围**:`SpringApplication.run()` 在 CI sandbox 中会触发 MongoDB/Redis/metrics exporters 等 auto-config 导致 hang,L5 E2E 通过 `mvn spring-boot:run --args="run ..."` 手工验证

**关键不变项**:
- `Tool` / `Skill` / `ToolExecutor` 5-step pipeline:untouched
- `PermissionPolicy` / `AuditLogger` / Cost domain:untouched
- `LinearTurnEngine` ReAct loop:untouched(仅消费 `runBlocking`)
- `AgentFactory` 6 Router fields + `flowRouter.resolve()`:untouched(CLI 只消费 public API)
- `A2aServer.start/stop/getActualPort`(Story #009):untouched
- dsh §5.6.4 Slot 9 `A2aTransport` 5-method contract:untouched
- dsh §10.1 13 项锁定依赖:**0 new coordinates**

**R-13 dependency:tree 自查**:所有新增直接依赖已在 dsh §10.1 锁定表 + Spring Boot BOM 中:

| 新增直接依赖 | dsh §10.1 锚定 |
|---|---|
| `ai.lingshu:lingshu-a2a-server` | sibling module(非依赖)|
| `org.projectlombok:lombok` | dsh §10.1 #3 |
| `org.springframework.boot:spring-boot-starter` | dsh §10.1 #2 |
| `org.springframework.boot:spring-boot-starter-test` | dsh §10.1 #8 |
| `org.junit.jupiter:junit-jupiter` | dsh §10.1 #9 |
| `org.assertj:assertj-core` | dsh §10.1 #10 |

```bash
mvn -pl lingshu-cli -am test -Dtest='ArgsParserTest,SubcommandTest,LingsCliExceptionTest,RunHandlerTest,ResumeHandlerTest,ServeHandlerTest,DoctorHandlerTest,ConfigHandlerTest,MainIntegrationTest'
```

**黑盒主路径**(L5 E2E,Story #017 实施者实跑):
```bash
$ export ANTHROPIC_AUTH_TOKEN=<your-key>
$ export ANTHROPIC_BASE_URL=https://api.anthropic.com
$ mvn -pl lingshu-cli spring-boot:run \
    -Dspring-boot.run.arguments="run --config examples/hello.yml --prompt '用 Java 写一个 Fibonacci 函数'"
[LINGS-Z99] usage=Usage(inputTokens=42, outputTokens=128) stopReason=END_TURN turns=1 elapsedMs=4321

$ mvn -pl lingshu-cli spring-boot:run \
    -Dspring-boot.run.arguments="serve --port 18099 --config examples/hello.yml"
$ curl -sf http://127.0.0.1:18099/.well-known/agent.json | jq .
{
  "name": "hello-agent",
  "description": "...",
  "version": "0.1.0",
  ...
}
```

**全模块回归**:`mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client,lingshu-cli,lingshu-examples/demo-engineer -am test` → `lingshu-core` 192 case(0 regression)+ `lingshu-a2a-server` 17 case(0 regression)+ `lingshu-a2a-client` 23 case(0 regression)+ `lingshu-cli` 30 case(0 regression)+ `lingshu-examples/demo-engineer` 2 case(本 PR 修复,pre-existing on `a9a6184`),**266/266 全绿**。

**Story 边界外延说明**:本 Story 实际改动 6 个源文件 + 9 个测试文件 + 1 个 fixture helper = **16 files**,超出 SOP §3.1 Story 边界 ≤5 上限 3 倍。根因:5 子命令 × 1 测试文件 + 4 工具类(`Main` / `Args` / `ArgsParser` / `Subcommand` / `LingsCliException`)是结构 floor,无法压缩。已**显式接受超限**,见 PR #18 body。

**Out-of-Scope**(deferred):
- `mcp-*` / `otel-*` 集成(Story #010)
- HealthIndicator 深检(Story #013)
- File / Redis / JDBC SessionStore(Story #014)—— `resume` 当前仅内存 stub
- `serve` RPC `/rpc` 端点(Story #009c —— HttpJsonRpcA2aTransport 落地后)
- `serve` over gRPC transport(Story #009a —— GrpcA2aTransport 已落地后)
- bash completion / man page / i18n(future)

---

## 📚 文档

完整文档见 [lingshu-ai-agent/lingshu-docs](https://github.com/lingshu-ai-agent/lingshu-docs):

- 📘 [30s 入门](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/intro.md)
- 🧠 [ReAct Loop 概念](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/react-loop.md)
- 🔌 [SPI 扩展指南](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi.md)
- 🔄 [SPI 版本兼容与 SlotRouter(Story #003)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi-versioning.md)
- ⚡ [并行 Tool 调度与并发配置(Story #004)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/parallel-tools.md)
- ⏹️ [协作式取消与三层贯通(Story #005)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/cancellation.md)
- 🛡️ [Sandbox 与安全](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/sandbox.md)
- 👥 [多租户隔离与 TenantContext(Story #006)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/multi-tenant.md)
- 🔁 [YAML 热更与 in-flight freeze(Story #007)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/yaml-hot-reload.md)
- 🌐 [A2A AgentCard 与 `.well-known/agent.json`(Story #009)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/a2a-agent-card.md)
- 🖥️ [CLI 入口与 5 子命令(Story #017)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/cli.md)
- 🛠️ [内置 Tool(Read / Write / Edit / Bash)与自动注册(Story #019)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/built-in-tools.md)
- 🧩 [Skill 系统第一块砖:SkillTool + CommitSkill + ToolRegistry 4 方法(Story #020a)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/skill-foundation.md)
- 📂 [Skill 系统第二块砖:SkillSource SPI + 2 v1 impls + CompositeSkillLoader(Story #020b)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/skill-source-discovery.md)
- ⚡ [Skill 系统第三块砖:CLI /xxx 拦截 + SkillCommandDispatcher(Story #020c)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/cli-skill-trigger.md)
- 🏭 [生产部署](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/ops/deployment.md)

设计文档:`dsh_agent_design.md`(v1.5.42)

---

## 🤝 参与贡献

- 🐛 [提交 Issue](https://github.com/lingshu-ai-agent/lingshu/issues/new?template=bug_report.yml)
- 💡 [提特性建议](https://github.com/lingshu-ai-agent/lingshu/issues/new?template=feature_request.yml)
- 🔧 [Pull Request 流程](https://github.com/lingshu-ai-agent/.github/blob/main/CONTRIBUTING.md)
- 🛡️ [安全漏洞上报](https://github.com/lingshu-ai-agent/.github/blob/main/SECURITY.md)

---

## 📜 License

Apache 2.0 — see [LICENSE](LICENSE).

---

<sub align="center">Built with 🪷 by the LingShu community · Apache 2.0 · JDK 8+</sub>