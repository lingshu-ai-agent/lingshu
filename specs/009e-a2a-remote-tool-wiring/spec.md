# Feature Specification: Story #009e a2a-remote-tool-wiring

**Feature Branch**: `story-009e-a2a-remote-tool-wiring`
**Created**: 2026-09-24
**Status**: Draft
**Input**: User description: "Story #009e a2a-remote-tool-wiring —— 实测发现 `RemoteAgentTool` 只在 `HttpJsonRpcA2aTransportAutoConfiguration` 暴露 + `ToolRegistry` 注册路径隐式(grpc / in-process transport 下 LLM 视角下根本没有 `remote_agent` Tool),违反 dsh §5.6.2 L2366「§6.5 同款注册路径」契约;**修复** 抽 `RemoteAgentToolAutoConfiguration` 独立于 transport,从 `HttpJsonRpcA2aTransportAutoConfiguration` 拆出 `remoteAgentTool` + `remoteAgentSchemaBuilder` 两个 Bean;加 `RemoteAgentToolLifecycle implements SmartLifecycle` 显式 `toolRegistry.register(remoteAgentTool)` + `stop` 时 `toolRegistry.unregister`;3 transport AutoConfig(`grpc-1.0.0` / `in-process-1.0.0` / `http-jsonrpc-1.0.0`)各自只保留 `a2aTransportProvider_<name>`,**不再**各自暴露 `remoteAgentTool`;加 1 L3 IT 覆盖 3 transport × register/dispatch/unregister 全链路。**WHY** —— 预 #022 / #023 之前打平 wiring gap,避免 #023 delegate-sub-agent 内部再写 patch 绕 RemoteAgentTool 的 bug;**复用** Story #021b `McpTransportLifecycle`(`SmartLifecycle` + 启动期 register / 关闭期 unregister 同款 pattern)+ Story #020a `SkillAutoConfiguration`(`ToolRegistry.register` SPI)样板。**0 额外依赖**,**0 新 ErrorCode**,**纯实现变更**(沿用 #009c / #009d 全部接口契约)。**锚定** dsh §5.6.2 L2366「§6.5 同款注册路径」+ §5.6.3 L2458-2471 `RemoteAgentToolAutoConfiguration` 草图 + §5.6.4 SPI 总表 Slot 9 行 + §6.5 (2) MCP Tool wiring + §6.5 (2.1) `McpTransportLifecycle` 完整实现样板。"

**Source Design Doc**: `dsh_agent_design.md` v1.5.39
- §5.6.2 L2356-2392(四层架构 + `RemoteAgentTool` 注释「§6.5 同款注册路径」—— 本 Story 主要锚定)
- §5.6.3 L2429-2471(`RemoteAgentTool` + `RemoteAgentToolAutoConfiguration` 草图 —— #009c 实施时**合并**到 `HttpJsonRpcA2aTransportAutoConfiguration`,本 Story 拆出)
- §5.6.3.0 L2482-2992(`RemoteAgentSchemaBuilder` 完整定义 —— 沿用 #009d 不变)
- §5.6.3.1 L2995-3172(HttpJsonRpcA2aTransport concrete class —— #009c 复用不变)
- §5.6.3.2 L3174-3320(GrpcA2aTransport / InProcessA2aTransport「3 件套模式」扩展指南 —— 本 Story 修复 grpc / in-process 缺 RemoteAgentTool 的 wiring gap)
- §5.6.4 SPI 总表 L3322-3334(Slot 9 行「Router stub 位置」列同步更新)
- §6.5 (2) L3500+ MCP 客户端 wiring(`McpTransport.connect` + `ToolRegistry.register` 模式 —— 本 Story 参照)
- §6.5 (2.1) L3396-3664(`McpTransportLifecycle` 完整实现样板 —— 本 Story 复用 SmartLifecycle pattern)
- §5.4 L2018-2075(plugin AutoConfiguration 编写约定 + 唯一 Bean 名约定)
- §5.5 L2075-2332(默认实现注册约定 + 🆕 v1.5.28 多 Provider 模式样板)
- §5.7(插件机制 SPI 决策 —— Spring Boot SPI 不绕)
- §15 LINGS-<域><编号> 错误码约定(**0 新增 ErrorCode** —— 纯 wiring 修复,无 RPC,无新失败路径)
- §10.1 锁定 13 项依赖表(R-13 mitigation (d) 强度最弱:0 binary delta)
- §17 R-13 / R-14 风险登记

---

## 1. Summary

本 Story 是 **A2A 客户端子系列的 wiring 修复**(2026-09-24 实测发现),在 #009a + #009b + #009c + #009d 落地后,#009d 测试发现 **RemoteAgentTool wiring gap** —— 当前 `RemoteAgentTool` 只在 `HttpJsonRpcA2aTransportAutoConfiguration`(`@AutoConfiguration`)内暴露,而 `GrpcA2aTransportAutoConfiguration` + `InProcessA2aTransportAutoConfiguration` 只暴露 `a2aTransportProvider_<name>` 一个 Bean。

**问题表现**:用户配 `application.yml` 写 `agent.a2aTransport: grpc-1.0.0` 或 `agent.a2aTransport: in-process-1.0.0` 时,Agent 进程启动后,**LLM 视角下根本没有 `remote_agent` Tool** —— `ToolRegistry` 内找不到 `name="remote_agent"` 的 Tool(`LocalToolsAutoConfiguration` 只扫 `@Bean Map<String, Tool>`,grpc / in-process 模块根本没人注册 `remoteAgentTool` Bean),违反 dsh §5.6.2 L2366 注释「`RemoteAgentTool` 走 §6.5 同款注册路径」的契约。

**根因**:Story #009c 实施时为了节约 CLAUDE.md §11 #4「核心文件 ≤ 5」Story 边界,选择把 `RemoteAgentToolAutoConfiguration`(草图 §5.6.3 L2458-2471)**合并**进 `HttpJsonRpcA2aTransportAutoConfiguration`(单 `@AutoConfiguration` 暴露 3 Bean:`a2aTransportProvider_http-jsonrpc-1.0.0` + `remoteAgentSchemaBuilder` + `remoteAgentTool`)—— 当时是**有意**的边界妥协,但实际**破坏**了 3 transport 切换场景下 RemoteAgentTool 始终可见的不变项。

**关键设计选择**:
- **抽** `RemoteAgentToolAutoConfiguration` 独立 `@AutoConfiguration`(与 §6.5 (2.1) `McpTransport` 同款 split pattern)—— 该 AutoConfiguration 只负责 `RemoteAgentTool` + `RemoteAgentSchemaBuilder` 两个 Bean,**不**依赖任何具体 transport 实现,只通过 `A2aTransportRouter.resolve(cfg.getA2aTransport(), cfg)` 拿 transport 引用;
- **加** `RemoteAgentToolLifecycle implements SmartLifecycle`(参照 §6.5 (2.1) `McpTransportLifecycle` 完整实现样板)—— `start()` 显式 `toolRegistry.register(remoteAgentTool)` + `stop()` 显式 `toolRegistry.unregister(remoteAgentTool)`,**避免**依赖 `LocalToolsAutoConfiguration` 隐式扫 `@Bean Map<String, Tool>` 的副作用;
- **拆** `HttpJsonRpcA2aTransportAutoConfiguration` —— 删除 `remoteAgentTool` + `remoteAgentSchemaBuilder` 两个 `@Bean` 方法,只保留 `a2aTransportProvider_http-jsonrpc-1.0.0`;
- **grpc + in-process 两个 AutoConfiguration 不动** —— 它们本来就只暴露 `a2aTransportProvider_<name>`,本 Story **不**需要在它们内加 `remoteAgentTool` Bean(因为 Spring 自动扫 `@AutoConfiguration` import,`RemoteAgentToolAutoConfiguration` 启动期就被加载);
- **新增 1 个 L3 集成测试** 覆盖 3 transport × register/dispatch/unregister 全链路(用 `InProcessA2aTransport` 跑单 transport IT,grpc + http-jsonrpc 用 mock server 跑)。

**R-13 mitigation (d) 强度最弱** —— 0 额外依赖,0 binary delta,`mvn dependency:tree -pl lingshu-a2a-client,lingshu-core` 应与 #009d baseline **完全一致**。

**0 新 ErrorCode** —— 纯 wiring 修复,无 RPC,无新失败路径;`ToolRegistry.register` / `unregister` 重复注册抛 `IllegalStateException` 是既有契约(由 `ToolRegistry` SPI 决定),本 Story 不动其行为。

**Story 边界** —— 5 核心 Java 源文件:`RemoteAgentToolAutoConfiguration`(新增,纯 split)+ `RemoteAgentToolLifecycle`(新增,SmartLifecycle)+ `HttpJsonRpcA2aTransportAutoConfiguration`(修改,删 2 @Bean)+ `HttpJsonRpcA2aTransportAutoConfigurationTest`(修改,删 2 测试)+ 1 L3 IT 新增文件。**严格 ≤ 5 边界内**。

---

## 2. User Stories

### US-1: RemoteAgentTool 全 transport 可见(as LingShu user configuring `application.yml`)

**As** LingShu user configuring the engine via `application.yml`,
**I want** to set `agent.a2aTransport: grpc-1.0.0` OR `agent.a2aTransport: in-process-1.0.0` OR `agent.a2aTransport: http-jsonrpc-1.0.0` and have the LLM always see the `remote_agent` tool,
**So that** switching transports doesn't silently break the A2A wiring (dsh §5.6.2 L2366「§6.5 同款注册路径」契约).

**Acceptance Criteria**:
- `AC-1.1` `RemoteAgentToolAutoConfiguration` 是独立 `@AutoConfiguration` 类,`@Bean(name = "remoteAgentTool")` + `@Bean(name = "remoteAgentSchemaBuilder")` 两个 Bean —— **不**在 `HttpJsonRpcA2aTransportAutoConfiguration` 内(从该类拆出)(VS-1 + FR-001 + FR-002)
- `AC-1.2` `RemoteAgentToolAutoConfiguration.remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)` Bean 拉 cfg 注入:从 `cfg.getA2aTransport()` 拿 transportName(默认 `"http-jsonrpc-1.0.0"`,向后兼容 #009c)+ `cfg.getA2a().getRemoteAgents()`(默认 `Collections.emptyList()`)+ `cfg.getA2a().getDescriptionSkillLimit()`(默认 10),内部 `new RemoteAgentTool(transport, json, schemaBuilder, remoteAgents, skillLimit)` 5 参构造(沿用 #009d 行为,本 Story 不变)(VS-1 + FR-003)
- `AC-1.3` `RemoteAgentToolAutoConfiguration.remoteAgentSchemaBuilder(ObjectMapper json)` Bean 返 `new RemoteAgentSchemaBuilder(json)` —— 与 #009d 既有 `@Bean` 行为一致,只是迁到独立类(FR-004)
- `AC-1.4` SPI 注册文件 `META-INF/spring/...imports` 新增 1 行:`ai.lingshu.a2a.client.RemoteAgentToolAutoConfiguration`(插入到 grpc + in-process + http-jsonrpc 3 行**之前**,保证 transport 类 Bean 解析顺序)(FR-005)
- `AC-1.5` 当用户配 `agent.a2aTransport: grpc-1.0.0` → 启动后 `ToolRegistry.findByName("remote_agent")` 非空,`tool.description()` 走 #009d 逻辑(枚举 skills 或 hint)(AC-1.2 + AC-1.3)
- `AC-1.6` 当用户配 `agent.a2aTransport: in-process-1.0.0` → 同 AC-1.5 行为(AC-1.5)
- `AC-1.7` 当用户配 `agent.a2aTransport: http-jsonrpc-1.0.0` → 同 AC-1.5 行为(**向后兼容** #009c / #009d 测试 + 真实用户配置)(AC-1.5 + 关键不变项 #1)

### US-2: SmartLifecycle 显式 register / unregister(as LingShu framework contributor)

**As** LingShu framework contributor evolving the A2A client module,
**I want** the `RemoteAgentTool` to be explicitly registered with `ToolRegistry` on Spring start and unregistered on Spring stop,
**So that** the wiring is observable in lifecycle logs (not hidden in `LocalToolsAutoConfiguration` side effects), and graceful shutdown releases the tool.

**Acceptance Criteria**:
- `AC-2.1` `RemoteAgentToolLifecycle implements SmartLifecycle` 是新 `@Component` 类(与 `McpTransportLifecycle` 同款 pattern,dsh §6.5 (2.1) 样板)(VS-2 + FR-006)
- `AC-2.2` 构造器注入 `(RemoteAgentTool tool, ToolRegistry toolRegistry)` 两个依赖(FR-006)
- `AC-2.3` `start()` 调 `toolRegistry.register(tool)` + 设 `running=true` + 日志 `RemoteAgentToolLifecycle started — remote_agent registered with ToolRegistry`(FR-006 + EC-1)
- `AC-2.4` `stop()` 调 `toolRegistry.unregister(tool.getName())` + 设 `running=false` + 日志 `RemoteAgentToolLifecycle stopped — remote_agent unregistered`(FR-006 + EC-1)
- `AC-2.5` `isRunning()` / `isAutoStartup()` / `getPhase()` 三方法同 `McpTransportLifecycle` 默认值(`getPhase() = Integer.MAX_VALUE - 1024`)(FR-006)
- `AC-2.6` `RemoteAgentTool` Bean **不**显式调 `toolRegistry.register` —— 完全靠 `RemoteAgentToolLifecycle.start()` 完成(避免双注册 IllegalStateException)(FR-006 + 关键不变项 #2)

### US-3: HttpJsonRpcA2aTransportAutoConfiguration 瘦身(as maintainer)

**As** LingShu maintainer reading the source,
**I want** `HttpJsonRpcA2aTransportAutoConfiguration` to contain only transport-specific `@Bean` methods (not the cross-cutting `RemoteAgentTool` + `RemoteAgentSchemaBuilder`),
**So that** each `@AutoConfiguration` is Single-Responsibility, and the file matches the dsh §5.6.3 L2458-2471草图 (RemoteAgentToolAutoConfiguration 独立 AutoConfiguration).

**Acceptance Criteria**:
- `AC-3.1` `HttpJsonRpcA2aTransportAutoConfiguration` 文件保持存在(不删除),只删 2 个 `@Bean` 方法(`remoteAgentSchemaBuilder` + `remoteAgentTool`),只保留 `a2aTransportProvider_http-jsonrpc-1.0.0` Bean(FR-007)
- `AC-3.2` `HttpJsonRpcA2aTransportAutoConfiguration` 的 imports 简化:删 `RemoteAgentSchemaBuilder` import(不再需要)+ 删 `A2aTransportRouter` / `AgentConfig` / `Collections` / `List` / `AgentRef` 中**只**为 `remoteAgentTool` 用的 import(FR-007)
- `AC-3.3` `GrpcA2aTransportAutoConfiguration` + `InProcessA2aTransportAutoConfiguration` 文件**不**改 —— 它们本来就只暴露 `a2aTransportProvider_<name>`,本 Story **不**需要在它们内加 `remoteAgentTool` Bean(因为 `RemoteAgentToolAutoConfiguration` 独立加载,grpc / in-process 模块不需要知道 RemoteAgentTool 存在)(FR-008 + 关键不变项 #3)

### US-4: 单元 + Slice + IT 测试覆盖(as maintainer)

**As** LingShu maintainer evolving the A2A client code,
**I want** L1 Unit tests covering `RemoteAgentToolAutoConfiguration` wiring + L2 Slice tests for `RemoteAgentToolLifecycle.start/stop` + L3 IT covering 3 transports × register/dispatch/unregister end-to-end,
**So that** regressions in the cross-transport wiring are caught at PR time.

**Acceptance Criteria**:
- `AC-4.1` `RemoteAgentToolAutoConfigurationTest` 新增 ≥ 5 case:
  - `testRemoteAgentToolBeanWiring` — Spring 容器有 `RemoteAgentTool` Bean,可被 `A2aTransportRouter` 注入 transport(AC-1.1 + AC-1.2)
  - `testRemoteAgentSchemaBuilderBeanWiring` — Spring 容器有 `RemoteAgentSchemaBuilder` Bean(AC-1.3)
  - `testRemoteAgentToolResolvesTransportFromCfg` — 当 `cfg.getA2aTransport() = "grpc-1.0.0"` 时,`RemoteAgentTool.getTransport()` 返回 GrpcA2aTransport 实例(AC-1.5)
  - `testRemoteAgentToolResolvesTransportDefaultHttpJsonRpc` — `cfg.getA2aTransport() == null` 时 fallback `"http-jsonrpc-1.0.0"`(AC-1.7 + EC-2)
  - `testRemoteAgentToolPropagatesRemoteAgents` — `cfg.getA2a().getRemoteAgents()` 非空时,`RemoteAgentTool.getRemoteAgents()` 包含同样 list(AC-1.2)
  - `testAutoConfigurationImportsIncludesThisClass` — 反射验证 `META-INF/spring/...imports` 文件包含 `RemoteAgentToolAutoConfiguration` 全限定名(AC-1.4)
- `AC-4.2` `RemoteAgentToolLifecycleTest` 新增 ≥ 4 case(直接 wiring,不走 @SpringBootTest,模式同 Story #007):
  - `testStartRegistersToolWithRegistry` — `lifecycle.start()` 后 `toolRegistry.findByName("remote_agent")` 非空(AC-2.3)
  - `testStopUnregistersToolFromRegistry` — `lifecycle.start()` + `lifecycle.stop()` 后 `toolRegistry.findByName("remote_agent")` 返 null 或抛 `ToolNotFoundException`(AC-2.4)
  - `testStartIsIdempotent` — `start()` 调 2 次不抛异常(重复 register 应抛 IllegalStateException **不发生** because `start()` 后 `running=true`,`isRunning` 检查跳过 — 或者 `ToolRegistry.register` 设计支持 putIfAbsent;本 Story 用 `running` flag 幂等)(AC-2.3 + EC-1)
  - `testIsRunningReflectsState` — `start()` 前 false / `start()` 后 true / `stop()` 后 false(AC-2.5)
- `AC-4.3` `HttpJsonRpcA2aTransportAutoConfigurationTest` **删除 2 case**:
  - 删 `testRemoteAgentSchemaBuilderBeanWiring`(原 #009d AC-4.3 —— 现在该 Bean 在 `RemoteAgentToolAutoConfiguration` 内)
  - 删 `testRemoteAgentToolBeanWiring`(原 #009c —— 现在该 Bean 在 `RemoteAgentToolAutoConfiguration` 内)
  - **保留** `testHttpJsonRpcA2aTransportProviderBeanWiring`(transport Bean 仍在该类内)+ 全部 4 个 Provider Bean 名 distinct 检查(FR-009 + AC-3.1)
- `AC-4.4` `RemoteAgentTransportWiringIT` 新增 ≥ 5 L3 case(用 `InProcessA2aTransport` + 真实 `ToolRegistry` + `ToolExecutor` 端到端):
  - `testInProcessTransportRegistersRemoteAgentTool` — `agent.a2aTransport: in-process-1.0.0` 配置下,Spring 启动后 `ToolRegistry.findByName("remote_agent")` 非空(AC-1.6)
  - `testHttpJsonRpcTransportRegistersRemoteAgentTool` — `agent.a2aTransport: http-jsonrpc-1.0.0` 配置下,同 AC-1.7 行为(AC-1.7)
  - `testGrpcTransportRegistersRemoteAgentTool` — `agent.a2aTransport: grpc-1.0.0` 配置下,同 AC-1.5 行为(AC-1.5,**用 mock GrpcServer 端到端**)
  - `testUnregisterOnStop` — `lifecycle.stop()` 后 LLM tool 列表不再含 `remote_agent`(AC-2.4)
  - `testToolDispatchReachesTransport` — `ToolExecutor.dispatch(remote_agent, call)` 走 in-process transport + 验证 submit() 被调(AC-1.6 + §4.10.1 硬规则 2 兼容)
- `AC-4.5` 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`,期望 321 + 5(新增 RemoteAgentToolAutoConfigurationTest) + 4(LifecycleTest) + 5(新 IT) - 2(删 HttpJsonRpc test) - 0(其他回归) = **333 case 全过**(0 fail / 0 error / 0 skipped)(NFR-005)

---

## 3. Functional Requirements

| ID | 描述 |
|---|---|
| **FR-001** | 新增 `RemoteAgentToolAutoConfiguration` 独立 `@AutoConfiguration` 类,`package ai.lingshu.a2a.client`,与 `HttpJsonRpcA2aTransportAutoConfiguration` 同包;类级 Javadoc 说明"dsh §5.6.3 L2458-2471 草图独立类,被 #009c 实施时合并到 transport AutoConfig,本 Story #009e 拆出,3 transport 共享"(AC-1.1) |
| **FR-002** | `RemoteAgentToolAutoConfiguration.remoteAgentTool(A2aTransportRouter router, AgentConfig cfg, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)` `@Bean(name = "remoteAgentTool")`:内部 `transport = router.resolve(transportName, cfg)` + `remoteAgents = cfg.getA2a() != null && cfg.getA2a().getRemoteAgents() != null ? cfg.getA2a().getRemoteAgents() : Collections.emptyList()` + `skillLimit = cfg.getA2a() != null && cfg.getA2a().getDescriptionSkillLimit() > 0 ? cfg.getA2a().getDescriptionSkillLimit() : RemoteAgentTool.DEFAULT_DESCRIPTION_SKILL_LIMIT`(AC-1.2 + FR-003) |
| **FR-003** | `RemoteAgentToolAutoConfiguration.remoteAgentSchemaBuilder(ObjectMapper json)` `@Bean(name = "remoteAgentSchemaBuilder")`:内部 `return new RemoteAgentSchemaBuilder(json)` —— 与 #009d 既有实现一致(AC-1.3) |
| **FR-004** | `transportName` 默认值 `cfg != null && cfg.getA2aTransport() != null ? cfg.getA2aTransport() : "http-jsonrpc-1.0.0"`(向后兼容 #009c / #009d 测试)(AC-1.7) |
| **FR-005** | SPI 注册文件 `META-INF/spring/...imports` 新增 1 行 `ai.lingshu.a2a.client.RemoteAgentToolAutoConfiguration`,插入在 grpc + in-process + http-jsonrpc 3 行**之前**(先注册 RemoteAgentTool,后注册 transport Bean,避免 Spring 解析循环)(AC-1.4) |
| **FR-006** | 新增 `RemoteAgentToolLifecycle implements SmartLifecycle`,`package ai.lingshu.a2a.client`,`@Component`;构造器 `(RemoteAgentTool tool, ToolRegistry toolRegistry)`;`start()` 调 `toolRegistry.register(tool)` + 设 `running=true` + 日志 INFO;`stop()` 调 `toolRegistry.unregister(tool.getName())` + 设 `running=false` + 日志 INFO;`isRunning()` / `isAutoStartup()` / `getPhase()` 三方法同 `McpTransportLifecycle` 默认(`getPhase() = Integer.MAX_VALUE - 1024`)(AC-2.1—AC-2.5) |
| **FR-007** | 修改 `HttpJsonRpcA2aTransportAutoConfiguration`:**删** `@Bean(name = "remoteAgentSchemaBuilder")` 方法(整段删除)+ **删** `@Bean(name = "remoteAgentTool")` 方法(整段删除);**保留** `@Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")` 方法不变(AC-3.1 + AC-3.2) |
| **FR-008** | `GrpcA2aTransportAutoConfiguration` + `InProcessA2aTransportAutoConfiguration` **不**修改(AC-3.3) |
| **FR-009** | `HttpJsonRpcA2aTransportAutoConfigurationTest` 删 `testRemoteAgentSchemaBuilderBeanWiring` + `testRemoteAgentToolBeanWiring` 两个 case;**保留** `testHttpJsonRpcA2aTransportProviderBeanWiring` + 全部 4 个 Provider Bean 名 distinct 检查 + AutoConfiguration imports 文件 3 行验证(#009c L2279 测试不破坏)(AC-4.3) |
| **FR-010** | `RemoteAgentTool` 类本身**不**改 —— 5 参构造器 / `name()` / `inputSchema()` / `execute()` / `description()` 全部不变;只变更 **wiring**(由谁来 new + 谁来 register)(关键不变项 #4) |
| **FR-011** | `RemoteAgentSchemaBuilder` 类本身**不**改 —— 与 #009d 完全一致(关键不变项 #5) |
| **FR-012** | `A2aTransportRouter` 接口 / 行为**不**改 —— 沿用 #009a 已落地实现,`resolve(name, cfg)` 按 `name()` 路由(关键不变项 #6) |
| **FR-013** | `ToolRegistry.register(Tool)` / `ToolRegistry.unregister(String)` SPI 不改 —— 沿用 #020a 已落地 `ToolRegistry` 接口;`register` 重复名抛 `IllegalStateException` 是既有契约,本 Story 不动(关键不变项 #7) |
| **FR-014** | `RemoteAgentToolLifecycle.start()` 幂等 —— `running == true` 时直接 return,不调 `register`(避免双注册 IllegalStateException);`stop()` 同理 —— `running == false` 时直接 return,不调 `unregister`(AC-2.3 + EC-1 + NFR-004) |
| **FR-015** | **JDK 8 兼容** —— 不用 `var` / `record` / `sealed` / `List.of` / pattern matching;用 `Collections.emptyList()` / `Arrays.asList` / `AtomicBoolean` 替代(FR-016 + 关键不变项 #8 + CLAUDE.md §3) |
| **FR-016** | 字段构造无 setter,`RemoteAgentToolLifecycle` 的 `running` 字段用 `volatile boolean` 或 `AtomicBoolean`(线程安全,SmartLifecycle 可能从不同线程调)(NFR-002 + NFR-006) |

---

## 4. Non-Functional Requirements

| ID | 描述 |
|---|---|
| **NFR-001** | 0 额外依赖(`A2aTransportRouter` + `ToolRegistry` + `RemoteAgentTool` + `RemoteAgentSchemaBuilder` 全部已落地,`SmartLifecycle` 来自 spring-context transitive)—— R-13 mitigation (d) 强度最弱 |
| **NFR-002** | `RemoteAgentToolAutoConfiguration` 与 `RemoteAgentToolLifecycle` 都是 stateless / no mutable state;线程安全(Spring 默认单例 Bean,被多线程共享) |
| **NFR-003** | `RemoteAgentToolLifecycle.start()` / `stop()` 调 `ToolRegistry.register` / `unregister` 都是 O(1) —— 内部 `CopyOnWriteArrayList` / `ConcurrentHashMap` 查表;不影响 Spring 启动 latency(性能预算 ≤ 30s 冷启动不变) |
| **NFR-004** | `start()` 幂等保护:`running == true` 时不重复 register;`stop()` 幂等保护:`running == false` 时不重复 unregister + 不抛 NPE;防止 Spring 重启或 graceful shutdown 多次触发时双重操作(AC-2.3 + EC-1) |
| **NFR-005** | 测试覆盖:5 + 4 + 5 = 14 新增 case(AC-4.1 + AC-4.2 + AC-4.4)- 2 删(HttpJsonRpc test AC-4.3)= **净 +12 case** |
| **NFR-006** | **向后兼容** —— `RemoteAgentTool` 2/3/5 参构造器**全部保留**(#009c + #009d 测试 0 regression);`HttpJsonRpcA2aTransportAutoConfiguration` 只删 2 个 `@Bean` 方法,transport Bean 行为不变;`A2aTransport` 5 方法契约不变;`A2aTransportRouter.resolve()` 行为不变 |
| **NFR-007** | JDK 8 兼容:`volatile boolean running` / `AtomicBoolean` / `Collections.emptyList()` / `Arrays.asList`,不用 `var` / `record` / `sealed` / `List.of` / pattern matching |
| **NFR-008** | dsh §4.10.1 硬规则 2 兼容:`RemoteAgentTool` 仍走 `ToolExecutor.dispatch()` 5 步流水线;`ToolRegistry.register` / `unregister` 不改 ToolExecutor 行为 |
| **NFR-009** | L3 IT 用真实 `InProcessA2aTransport` + 真实 `ToolRegistry` + 真实 `ToolExecutor` 端到端(避免 mock 失去 wire-up 验证意义);grpc / http-jsonrpc 用本地 mock server(`com.sun.net.httpserver.HttpServer` / `MockGrpcServer`);**不**用 Mockito 5.x + JDK 23 inline mockmaker(规避 #007 / #008 测试已知兼容性 issue) |
| **NFR-010** | `RemoteAgentTool` Bean 在 `RemoteAgentToolAutoConfiguration` 内**不**调 `toolRegistry.register`(避免双注册)—— 完全靠 `RemoteAgentToolLifecycle.start()` 完成;`Tool` Bean 自身也不知道自己会被 register(无 Spring 依赖) |
| **NFR-011** | R-13 mitigation (d) baseline 镜像必须执行 —— `git stash --include-untracked` pre-#009e baseline + post-#009e `mvn -pl lingshu-a2a-client,lingshu-core dependency:tree` diff **仅时间戳不同**,0 binary delta |

---

## 5. Edge Cases

| ID | 描述 |
|---|---|
| **EC-1** | `RemoteAgentToolLifecycle.start()` 调 2 次(测试或 Spring 异常重启) → 第 2 次 `running == true` 直接 return,不重复 register;同理 `stop()` 调 2 次 → 第 2 次 `running == false` 直接 return(AC-2.3 + FR-014 + NFR-004) |
| **EC-2** | `cfg == null`(理论不会发生,但保留防御) → `RemoteAgentToolAutoConfiguration.remoteAgentTool` 内部 fallback transportName = `"http-jsonrpc-1.0.0"` + remoteAgents = `Collections.emptyList()` + skillLimit = `RemoteAgentTool.DEFAULT_DESCRIPTION_SKILL_LIMIT`(FR-004 + AC-1.7) |
| **EC-3** | `cfg.getA2a() == null`(用户没配 a2a section) → remoteAgents + skillLimit fallback 同 EC-2(AC-1.2 + FR-002) |
| **EC-4** | `cfg.getA2aTransport()` 返回不存在的 transport 名(如 `"unknown-transport"`) → `A2aTransportRouter.resolve("unknown-transport", cfg)` 抛 `IllegalStateException`(沿用 #009a `A2aTransportRouter` 既有契约);Spring 启动期 fail-fast(不在 `RemoteAgentToolAutoConfiguration` 兜底,因为 Router 已有契约) |
| **EC-5** | `cfg.getA2aTransport() == null` → fallback `"http-jsonrpc-1.0.0"`,沿用 #009c 行为(AC-1.7) |
| **EC-6** | `RemoteAgentToolAutoConfiguration` 启动期 Bean 加载顺序在 transport AutoConfig **之前**(SPI 文件 imports 行序决定)→ 保证 transport Provider Bean 已注册,`A2aTransportRouter.resolve(...)` 能找到 Provider;**反之**(transport 还没注册)→ `resolve` 抛 ProviderNotFoundException,Spring 启动失败 —— 这是设计的隐式不变量(FR-005 + AC-1.4) |
| **EC-7** | `RemoteAgentToolLifecycle.stop()` 时 transport 已经关闭(`HttpJsonRpcA2aTransport` 等无 close 状态,但 in-process registry 可能已被显式清空)→ `toolRegistry.unregister` 只删 ToolRegistry 内的 Tool 引用,与 transport 状态**无关**(AC-2.4) |
| **EC-8** | `ToolRegistry.register(remoteAgentTool)` 时 `remote_agent` 名字已存在(用户自定义同名 Tool Bean)→ 抛 `IllegalStateException` 由 `ToolRegistry` SPI 决定;Spring 启动期 fail-fast(NFR-008) |
| **EC-9** | `RemoteAgentToolAutoConfiguration` 在某些模块(如 `lingshu-a2a-server`)不被 import(该模块**只**用 `LocalAgentCardGenerator` 不需要 `RemoteAgentTool`)→ SPI 文件 imports 行按模块隔离,`server` 模块的 `imports` 不含 `RemoteAgentToolAutoConfiguration`,正常(AC-3.3 + 关键不变项 #3) |
| **EC-10** | `RemoteAgentToolLifecycle` 与 `McpTransportLifecycle` 同 phase(`Integer.MAX_VALUE - 1024`),谁先启动由 Bean name 字典序定 → `McpTransportLifecycle`(`mcpTransportLifecycle`)字典序先于 `RemoteAgentToolLifecycle`(`remoteAgentToolLifecycle`),MCP tool 先 register,remote_agent tool 后 register;两个生命周期互不干扰(AC-2.5) |

---

## 6. Out of Scope(本 Story **不**做)

- **N-tool 模式(每 skill 一个 `call_<name>_<skillId>` tool Bean)** —— 留 OQ-1 revisit trigger;当前单 tool 模式维持 #009c / #009d 契约
- **改 AgentSkill 字段加 `inputSchema`/`outputSchema`** —— 留后续 Story
- **PromptBuilder 接入 ToolSpec list** —— 留 Story #018+ 后续 Story
- **启动日志自动打印 ToolSpec 列表** —— 留后续 Story
- **健康检查端点暴露 ToolSpec 列表** —— 留后续 Story
- **A2aTransport.fetchCard 启动期批量调用** —— 不动 #009a / #009b / #009c 既有实现
- **§14.8 hot-reload 触发 `RemoteAgentSchemaBuilder.refresh()`** —— 留 Story #007+ 后续 Story
- **ToolRegistry SPI 扩展(register / unregister / findByName 完整契约)** —— 沿用 #020a 已落地,本 Story 不动
- **§6.5 (2) MCP Tool wiring 改造** —— 本 Story 仅改 A2A wiring,MCP wiring 沿用 #021b `McpTransport.connect` 既有契约
- **新 Maven 依赖** —— R-13 mitigation (d) 强度最弱,0 binary delta
- **新 ErrorCode** —— 0 新 ErrorCode(纯 wiring 修复)
- **`RemoteAgentTool` 自身代码改动** —— 关键不变项,只改 wiring
- **`HttpJsonRpcA2aTransportAutoConfiguration` 删 2 @Bean 之外的其他改动** —— 关键不变项
- **`GrpcA2aTransportAutoConfiguration` / `InProcessA2aTransportAutoConfiguration` 改动** —— 关键不变项
- **`A2aTransportRouter` 改动** —— 关键不变项

---

## 7. Constitution Check(宪章 v1.0)

| 宪章节 | 条款 | 本 Story 合规情况 |
|---|---|---|
| §1 项目原则 | #8 Slot 选用 | ✅ 不增 Slot;`RemoteAgentToolAutoConfiguration` 与 `RemoteAgentToolLifecycle` 都是普通 `@AutoConfiguration` / `@Component`,非 Slot |
| | #9 Plugin 发现 | ✅ `@AutoConfiguration` + SPI imports + `@Bean(name = "...")` 唯一 Bean 名约定(§5.4) |
| | #11 默认实现位置 | ✅ 不动 Slot 9 默认 Provider(`HttpJsonRpcA2aTransportProvider` / `GrpcA2aTransportProvider` / `InProcessA2aTransportProvider` 沿用 #009a / #009b / #009c) |
| §2 13 依赖锁定 | R-13 mitigation (d) 强度最弱 | ✅ 0 新依赖 |
| §4 错误码约定 | LINGS-<域><编号> 域细分 | ✅ **0 新增 ErrorCode** |
| §5 7 层金字塔 | 单元 / Slice / 集成 | ✅ L1 Unit 5 case + L2 Slice 4 case + L3 IT 5 case = 14 新增 + 2 删 = 净 +12 |
| §6 兼容性矩阵 | JDK 8 编译 + JDK 17 跑 | ✅ compile target 不动;`volatile boolean` / `AtomicBoolean` / `Collections.emptyList()` JDK 8 兼容 |
| §7 LTS 政策 | JDK 17/21 LTS | ✅ JDK 17+ runtime |
| §8 Glossary | A2A 术语一致 | ✅ `RemoteAgentTool` / `RemoteAgentSchemaBuilder` / `A2aTransport` / `A2aTransportRouter` / `ToolRegistry` / `SmartLifecycle` 与 dsh §16 一致 |
| §9 Review 节奏 | PR review + CI | ✅ Story 完成 + PR + CI 全过后 merge |
| §10 风险登记 | R-13(13 依赖锁) + R-14(A2A 协议兼容) | ✅ R-13 强度最弱 + R-14 由 §5.6.3.0 / §5.6.3.2「3 件套模式」兼容未来变体 |

---

## 8. Success Criteria(完成定义)

1. ✅ `spec.md` / `plan.md` / `tasks.md` 三件套全部齐备
2. ✅ `RemoteAgentToolAutoConfiguration`(新增独立 @AutoConfiguration)+ `RemoteAgentToolLifecycle`(新增 SmartLifecycle)+ `HttpJsonRpcA2aTransportAutoConfiguration`(删 2 @Bean)+ `RemoteAgentToolAutoConfigurationTest`(新增 5 case)+ `RemoteAgentToolLifecycleTest`(新增 4 case)+ `RemoteAgentTransportWiringIT`(新增 5 L3 case)+ `HttpJsonRpcA2aTransportAutoConfigurationTest`(删 2 case)全部实现
3. ✅ L1 Unit + L2 Slice + L3 IT 全部 case 通过(净 +12 case,321 + 12 = 333 全过 / 0 fail / 0 error / 0 skipped)
4. ✅ `mvn dependency:tree -pl lingshu-a2a-client,lingshu-core` 与 #009d baseline 完全一致(R-13 mitigation (d) 强度最弱 0 binary delta)
5. ✅ dsh §13 changelog 加 v1.5.40 行 + §5.6.3.1 L3165-3172 段加 cross-ref `RemoteAgentToolAutoConfiguration` + §5.6.3.2 L3174-3320 段加 cross-ref + §5.6.4 SPI 总表 Slot 9 行更新 wiring 列
6. ✅ 0 新 Maven 依赖,0 新 ErrorCode
7. ✅ PR title `feat(a2a-client): Story #009e a2a-remote-tool-wiring — ...` + body 末尾 `### R-13 dependency:tree 自查` 节
8. ✅ #009a / #009b / #009c / #009d 全部 321 已有测试 0 regression(只有 HttpJsonRpcA2aTransportAutoConfigurationTest 删 2 case 是有意为之,行为迁到 RemoteAgentToolAutoConfigurationTest)
9. ✅ L3 IT 覆盖 3 transport × register/dispatch/unregister 全链路 — 这是本 Story 的核心 AC,验证 wiring gap 已修复

---

## 9. Open Questions / Future Stories

| ID | 问题 | 落地 Story |
|---|---|---|
| **OQ-1** | 是否把 RemoteAgentTool 从单 tool 拆成 N tool(每 skill 一个 `call_<name>_<skillId>` Bean)? | **Revisit trigger** OpenAI / Anthropic tool spec 2025+ 广泛支持 `oneOf` + nested union;**当前**选单 tool 简化版 |
| **OQ-2** | 是否给 AgentSkill 加 `inputSchema` / `outputSchema` 字段? | **Story #009d+** —— 需要改 on-wire JSON 契约 + Server 端同步 |
| **OQ-3** | 是否需要 ToolSpec → AgentCard 反向生成?(把 LingShu 本地 Tool 暴露为 A2A skill 给远端调) | **未来** Server-side skill 导出 Story |
| **OQ-4** | 是否引入 dsh §5.6.3.0 描述的 `AgentRef` 类型 + `cfg.getA2a().getRemoteAgents()` 配置? | **Story #009d+** —— `AgentRef` 含 `endpoint` / `transportName` / `skillIds[]` / `priority` / `enabled` 5 字段 |
| **OQ-5** | PromptBuilder `build()` 是否接入 `List<ToolSpec>` 注入 `[TOOL SCHEMAS]` 段? | **Story #018+** —— PromptBuilder 集成 A2A Skill schemas |
| **OQ-6** | 启动日志自动打印 `[RemoteAgentSchemaBuilder]` 段 `describeSpecs(...)`? | **Story #018+** —— 启动日志 + 健康检查端点 |
| **OQ-7** | `RemoteAgentToolLifecycle` phase 是否需要调到 `Integer.MAX_VALUE - 2048` 让它在 MCP lifecycle **之前**启动,避免 MCP tool 没 register 时 Agent 已 turn? | **Revisit trigger** 大规模 MCP + A2A 共存场景下出现时序竞争时再调整;**当前**默认 phase 已足够 |

---

## 10. References

- **设计文档**: `~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md` v1.5.39
- **SpecKit SOP**: `~/Documents/AIFullStack/MyDSHAgentDesign/speckit_operator_prompt.md` v1.18
- **SKILL**: `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` v1.0.21
- **dsh §5.6.2 L2356-2392**: 四层架构 + `RemoteAgentTool` 注释「§6.5 同款注册路径」—— 本 Story 主要锚定
- **dsh §5.6.3 L2429-2471**: `RemoteAgentTool` + `RemoteAgentToolAutoConfiguration` 草图(独立 @AutoConfiguration)—— 本 Story 主要锚定
- **dsh §5.6.3.0 L2482-2992**: `RemoteAgentSchemaBuilder` 完整定义(沿用 #009d 不变)
- **dsh §5.6.3.1 L2995-3172**: `HttpJsonRpcA2aTransport` concrete class(#009c 复用不变)
- **dsh §5.6.3.2 L3174-3320**: GrpcA2aTransport / InProcessA2aTransport「3 件套模式」扩展指南 —— 本 Story 修复 grpc / in-process 缺 RemoteAgentTool 的 wiring gap
- **dsh §5.6.4 L3322-3334**: SPI 总表 Slot 9 行 —— 本 Story 更新 wiring 列
- **dsh §6.5 (2)**: MCP 客户端 wiring(`McpTransport.connect` + `ToolRegistry.register` 模式)
- **dsh §6.5 (2.1)**: `McpTransportLifecycle` 完整实现样板(参照 `RemoteAgentToolLifecycle` 同款 pattern)
- **dsh §5.4 L2018-2075**: plugin AutoConfiguration 编写约定 + 唯一 Bean 名约定
- **dsh §5.5 L2075-2332**: 默认实现注册约定 + 🆕 v1.5.28 多 Provider 模式样板
- **dsh §15**: LINGS-<域><编号> 错误码约定(本 Story 0 新增)
- **dsh §17**: R-13 / R-14 风险登记
- **前序 Story PR**:
  - #009a GrpcA2aTransport: PR #20 (merged 064ce3a)
  - #009b InProcessA2aTransport: PR #21 (merged 42888c3)
  - #009c HttpJsonRpcA2aTransport: merged 42f9e91
  - #009d RemoteAgentSchemaBuilder: PR #25 (merged,2026-09-22)
  - #020a Skill foundation: PR #28 (merged 2026-09-23)
  - #020b Skill source discovery: PR #29 (merged 2026-09-23)
  - #021b Mcp tool adapter: PR #33 (merged 2026-09-23, `McpTransportLifecycle` SmartLifecycle 完整样板)
- **Spring Framework SmartLifecycle**: https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/context/SmartLifecycle.html
