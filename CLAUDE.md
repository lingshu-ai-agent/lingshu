# CLAUDE.md — 灵枢 LingShu 项目 Claude Code 引导

> 给 Claude Code 的项目级上下文。**每个新 Claude 会话 turn 1 自动加载。**
>
> **配套文档**(按需加载,不重复):
> - 设计文档:[`dsh_agent_design.md`](./dsh_agent_design.md) v1.5.32 / 6447 行 / 项目真理(v1.5.34 §7.1 新增子节 —— 明确「AgentFactory 是 Spring 单例 Bean(无状态,持 7 Router),Agent 是 factory 的产品(prototype-like,带 session/config/engine 状态,每次 create 一份,Spring 不持有引用)」 — 5 维度对比论证为什么 Agent 不能是 Spring 单例(多轮 session 隔离 / 子 Agent 共享 / A2A 多 RemoteAgent / 测试 Mock / Spring Bean 语义);Agent 生命周期时序图(Spring startup → T0 loadYaml → T1 create 7 项校验 + flowRouter.resolve → T2 run ReAct → T3 流关闭 → T4 GC → T5 session 持久化);扩展点矩阵(7 Router × 9 Slot —— 用户扩展 Provider 不扩展 Router / AgentFactory / Agent 三件套);反模式 `AgentHolder` 5 个失败场景(session 污染 / config 漂移 / 子 Agent 反模式 / A2A 不可行 / 违反 §4.1 不变项);§7.1.6 一句话总结 + 精读章节更新;**纯文档改动**,代码逻辑零改动;7039 → ~7250 行(+约 210);v1.5.33 §5.6.3.0 新增四个核心类型完整定义(`AgentCard` Lombok @Data + 4 nested type / `AgentRef` + `AgentRefBuilder` 装配器 priority 去重排序 / `RemoteAgentSchemaBuilder` @Component 启动期扫 `AgentCard.skills[]` 动态生成 `ToolSpec` list / `AgentCardCache` @Component TTL 缓存 + 负缓存短 TTL=ttl/4 + FIFO evict maxEntries=1000 + `Stats` inner class 命中率指标 + `invalidate()` 显式失效为 §14.8 hot-reload 预留钩子) — 6447 → 7039 行(+592);(`Skill` 接口定义 + `SkillTool.fromMarkdown` 静态工厂 + `@Component implements Skill` 硬编码对照示例) — **问题** v1.5.31 §6.4 把 Skill 多源自动发现 + SkillLoader + SkillTool 链路讲清楚,但"Skill 是什么"含糊 —— 全文反复写 "Skill extends Tool" 但读者翻 §4.6 也找不到 `Skill` 接口定义,只能从 §6.4 `SkillTool implements Skill` 反推;且 `SkillTool.fromMarkdown(name, content)` 静态工厂在 ClasspathSkillSource (L3587) + DirectorySkillSource (L3636) 各被引用一次但无人定义;且只展示「`SKILL.md` → `SkillTool`」文件加载路径,缺「硬编码 `@Component`」对照路径 —— 实施者写内置命令时不知道有第二条路;**根因** §6.4 v1.5.0 写时只把 Skill 当成 Tool 的子集用,缺接口契约 + 工厂方法 + 两种实现路径对照三件套;v1.5.18 后 Tool 体系大改(§6.5 三种 Scheme 来源 + §4.6 ToolExecutor 接口定义补全),Skill 这一支没同步补全契约层;**补丁** (1) **§6.4 L3476-3500 新增 `Skill` 接口** —— `extends Tool`,现阶段零额外方法,Javadoc 明确「Skill 是 Tool 的约定性 marker」+ 与普通 Tool 的 4 点差异(注册进 2 张表 / schema 暴露给模型 / CLI `/xxx` 拦截 / §6.4 全节围绕)+ 3 条未来扩展空间(用户别名 `/c` → `commit` / 权限标记 只能用户触发 / 危险等级 联动 §4.7 审批门);(2) **§6.4 L3783-3790 `SkillTool` 加 `fromMarkdown(name, markdownContent)` 静态工厂** —— SKILL.md 第一行 `# title` 去 leading `#` 提取为 `description`,剩余正文作为 `content`,inputSchema 固定 `{ "input": string }`(与 /xxx <arg> 调用习惯对齐);Javadoc 标注被 ClasspathSkillSource (L3587) + DirectorySkillSource (L3636) 调用;(3) **§6.4 L3810-3852 新增 `@Component CommitSkill implements Skill` 硬编码对照示例** —— 不依赖 SKILL.md 文件,适合"硬编码"内置命令(本例 `/commit` 按 Conventional Commits 风格生成 commit message);附 SkillTool vs @Component 对照表 6 行(来源 / 热加载 / 适合 / 配置 / 推荐)+ 选型决策 3 条(改 skill 行为 → SkillTool / 改 skill 实现逻辑 调外部 API → @Component / 同名 Skill 同时存在走 `putIfAbsent` 先注册者优先);(4) **关键不变项** —— `Tool` 接口 / `SkillLoader` / `CompositeSkillLoader` 行为 / `ToolRegistry` 注册路径 / `ToolExecutor` 5 步流水线 / §4.7 PermissionPolicy / AuditLogger / Cost 域 全部不变;**纯文档改动**,代码逻辑零改动;6395 → 6447 行(+52);**修复者**:Claude Code(根据用户 2026-09-17 会话反馈,用户问「§6.4 Skill 这一节是不是补充一个 Skill 的代码示例会更好的理解啊」,确认 §6.4 缺 `Skill` 接口定义 + `fromMarkdown` 工厂 + 硬编码 `@Component Skill` 对照示例,要求补)v1.5.31 §5.6.3 L2429-2444 `RemoteAgentToolProvider` 误用 `@AutoService(ToolProvider.class)` 修复 —— 降级为普通 `Tool` 模式 — **问题** v1.5.30 §5.6.3 草图用 `@AutoService(ToolProvider.class)` 标注 `RemoteAgentToolProvider`(L2429-2444 注释写「复用 §6.5 Tool 注册路径」),但 §5.7 v1.5.21 明确选择 Spring Boot SPI 而**不**选 Java SPI / OSGi / ClassLoader 隔离,`@AutoService` 是 Google auto-service 库的注解生成 Java SPI 的 `META-INF/services/` 文件 —— 与 §5.7 SPI 决策直接冲突;且 §5.5 / §6.5 全部 Tool / MemorySource / SkillSource 都用 `@Component` 或 `@AutoConfiguration` + `@Bean` 模式注册,**没有任何 ToolProvider 抽象**;`ToolProvider` 接口本身在文档中也未定义(grep 整篇只有 2 处引用,都在 L2430-2431)——「引用了不存在的契约」三重 bug;**根因** v1.5.4 §5.6 引入 A2A 时,`RemoteAgentTool` 曾短暂被设计为 `ToolProvider` 抽象(Slot 10 候选),后续 v1.5.30 §5.6.3 落地时降级为普通 Tool 模式但代码块没同步改 ——留下 `@AutoService` + 不存在的 `ToolProvider` 接口双重历史 bug;**补丁** (1) **§5.6.3 L2429-2444 改写**:移除 `RemoteAgentToolProvider` + `@AutoService` 模式,改写为标准 `@Component public class RemoteAgentTool implements Tool` + `RemoteAgentToolAutoConfiguration`(`@AutoConfiguration` + `@Bean public Tool remoteAgentTool()`,与 §5.5 plugin 非 Slot 类型 Bean 样板对齐);`RemoteAgentTool.execute(call, ctx)` 内部按 `call_<agentName>` 名字 parse 出 agentName + 转发给 `A2aTransport.submit()`;inputSchema 启动期扫 `AgentCard.skills[]` 动态生成(JSON Schema via `RemoteAgentSchemaBuilder`);(2) **§5.6.1 L2346 对照表 `Tool 发现机制` 单元格修正**:原版写「`@AutoService` 启动期静态注册」(Java SPI 措辞)→ 改「Spring `@Component` / `@AutoConfiguration` + `@Bean` 启动期静态注册」(与 §6.5 (1) ReadTool / §6.5 (2) McpToolAdapter / §5.5 plugin 非 Slot 类型 Bean 样板对齐);(3) **§0 L1 + §13 + CLAUDE.md / SKILL / SOP / prompts 版本号同步**;(4) **关键不变项** —— `A2aTransport` 5 方法契约不变 / Slot 9 SPI 不变(仍 9 个 Slot)/ A2aTransportRouter 行为不变 / RemoteAgentTool 内部不变(只看 A2aTransport 接口)/ ToolExecutor 5 步流水线不变 / §4.7 PermissionPolicy / AuditLogger / Cost 域 兼容;**纯文档改动**,代码逻辑零改动;6377 → 6395 行(+18);**修复者**:Claude Code(根据用户 2026-09-17 会话反馈,用户问「`@AutoService(ToolProvider.class)` 是自定义注解还是哪个库的注解啊」+「给出的示例里面为啥会有 `@AutoService(ToolProvider.class)`」,确认 §5.6.3 误用 Java SPI 注解 + `ToolProvider` 接口未定义,与 §5.7 决策冲突,要求降级为普通 Tool 模式)v1.5.30 §5.6.3.1 新增 `HttpJsonRpcA2aTransport` concrete class + `HttpJsonRpcA2aTransportProvider` concrete Provider 完整示例 + §5.6.3.2 备选 `GrpcA2aTransport` / `InProcessA2aTransport` 「3 件套模式」扩展指南 — §5.6.3 L2395-2451 草图用匿名 inner class 形态写 `HttpJsonRpcA2aTransportAutoConfiguration` 的 `@Bean` 方法,但 §5.6.4 L2465 SPI 总表 Slot 9 行的「默认 Provider」字段已经写 `HttpJsonRpcA2aTransportProvider` —— 命名不一致;且 `HttpJsonRpcA2aTransport` 自身也只列名未给 class 定义,用户合理怀疑它是不是 `A2aTransport` 的子接口;**根因** v1.5.4 §5.6 引入 A2A 时,3 个 A2aTransport 变体是平级概念,设计意图是「`A2aTransport` SPI + 3 concrete class 平级实现」,但 §5.6.3 草图偷懒用匿名 inner class 写 Provider + 缺 concrete Transport class 定义;**补丁** (1) **§5.6.3.1 新增子节**(~190 行):设计澄清(`HttpJsonRpcA2aTransport` 不是 `A2aTransport` 子接口是 concrete class,GrpcA2aTransport / InProcessA2aTransport 同理,KISS + 3 变体数量小,扳机条件 HTTP 变体 ≥ 5 才加 HttpBasedA2aTransport 子接口) + `HttpJsonRpcA2aTransport` concrete class 完整定义(implements A2aTransport 5 方法:fetchCard / submit / get / cancel / subscribe polling 占位,用 JDK 17 内置 java.net.http.HttpClient 0 额外依赖) + `HttpJsonRpcA2aTransportProvider` concrete class(name="http-jsonrpc" + priority=10) + AutoConfiguration 改写(@Bean name 唯一);(2) **§5.6.3.2 新增子节**(~150 行):「3 件套模式」明确(concrete Transport + concrete Provider + AutoConfiguration)+ `GrpcA2aTransport` stub(grpc-java + protobuf,subscribe 用 grpc streaming 更高效,体积 +5MB R-13 mitigation (d) 镜像必执行)+ `InProcessA2aTransport` stub(同 JVM 直接方法调用,InProcessA2aRegistry 单例,0 额外依赖)+ application.yml 3 Provider 同存配置示例 + 启动日志样例 + 实施期检查清单 6 条 + 关键不变项(A2aTransport 5 方法契约不变 / A2aTransportRouter 行为不变 / RemoteAgentTool 内部完全不变 / §4.7 PermissionPolicy 等兼容);(3) §0 L1 + §13 同步;(4) CLAUDE.md v1.3.22 → v1.3.23;6049 → 6377 行(+328);v1.5.29 §6.5 (2.1) 新增 `McpServerConnection` 实现示例 (心跳保活 + 指数退避重连) + §6.5 (2) `McpTransport` 同步改写 — §6.5 (2) 假设 MCP server「连上就永远连着」,生产环境 MCP server 子进程可能被 OOM 杀、stdio 僵死、SSE 反向代理超时踢线 —— Agent 进程会因 MCP server 抖动连锁崩盘,且当前文档缺 single-source-of-truth 的心跳 / 重连机制样板,Story #009 实施者只能反推 §4.10.1 错误处理边界自己设计;**根因** §6.5 (2) 原版 `McpServerConnection.start(cfg)` 是一次性同步连接 stub,没引入状态机 / 心跳 / 重连概念;v1.5.x 早期把 MCP 当「远程 Tool 注册中心」轻量集成,没考虑 24×7 长生命周期运维需求;到 v1.5.28 多 Provider 模式 + 9 Slot 体系成熟,**MCP 的「长连接」属性被放大** —— 必须补完整的生命周期管理;**补丁** (1) **§6.5 (2.1) 新增子节**(~270 行):(a) **`McpServerConnection` 接口** `extends AutoCloseable`,8 个方法(name / state / lastHeartbeatAt / listTools / callTool / onStateChange / start / close),Javadoc 明确「非 CONNECTED 状态 callTool 直接返 error 不抛异常」「重连后 listTools 重新拉不复用旧 cache」;(b) **`ConnectionState` enum** —— `IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED` 6 态;(c) **`McpServerConnectionFactory`** —— 按 `cfg.transport()` 分派 stdio / SSE / streamable HTTP 三实现;(d) **`StdioMcpServerConnection` 完整实现**(~180 行)—— `AtomicReference<ConnectionState>` + `AtomicInteger reconnectAttempts` + `CopyOnWriteArrayList<Consumer<...>>` + daemon `ScheduledExecutorService`;`start()` 5 步(拉子进程 → initialize → initialized → tools/list → 切 CONNECTED + 启心跳);`probe()` 双探活(`process.isAlive()` + MCP `ping` 请求等回包,timeout=hbTimeoutMs);`scheduleReconnect()` 走 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 指数退避,失败**无限**重试;(e) **`SseMcpServerConnection` 差异说明段** —— 3 处差异(心跳 = `GET /health` 而非 process.isAlive;重连 = 重建 `HttpClient` 而非杀子进程;长连接 = `SseEventSource` 收 server push 触发 tools/listChanged 重拉);(f) **§6.5 (2) `McpTransport` 同步改写** —— `connect()` 不再直接调 `McpServerConnection.start(cfg)`,改 `McpServerConnectionFactory.create(cfg)` + `onStateChange(listener)` + 异步 `conn.start()`;新增 `onConnectionStateChange()` 私有方法处理 `CONNECTED → register / DISCONNECTED → unregister`;(g) **配置 `application.yml` 示例 + 启动日志样例** —— github server 被 OOM 杀后重连,日志展示 tools 从 7 → 8(MCP server 升级后新增 tool 自动可见);(2) **JDK 8 兼容** —— `AtomicReference` / `AtomicInteger` / `CopyOnWriteArrayList` + `Collections.emptyList()`,**不用** `List.of` / `var` / sealed / records,与 §0 L39 硬约束对齐;(3) **关键不变项** —— `McpToolAdapter` / `ToolExecutor.dispatch()` / `PermissionPolicy.check()` 完全不变,MCP 断流在 `ToolResult` 层只表现为「error 替代 success」,**不会绕过沙箱 / 权限 / checkpoint 任何一步**,与 §4.10.1 硬规则 2 完全兼容;(4) §13 加本条目 + §0 L1 标题版本号同步 `v1.5.28 → v1.5.29`;(5) CLAUDE.md 版本号同步 `1.3.21 → 1.3.22`;**纯文档改动**,代码逻辑零改动;5709 → 6049 行(+340);v1.5.28 §5.5 改「多 Provider 模式」样板 + §5.4 同步改「唯一 Bean 名约定」+ §5.6 Slot 9 stub 同步 — v1.5.27 §5.5 用 `@ConditionalOnMissingBean` 强制「部署期二选一」,同一 Slot 最多 1 个 `XxxProvider` Bean 注册到 Spring 容器 → 用户切换 Provider 必须改 classpath / exclude / 改 Bean 名;但 §5.3.1.0 `SlotRouter<P, T>` 父类**一直是多 Provider 友好** —— 构造器收 `List<P> providers`,启动期按 `name()` 收 `Map<String, P>`,`resolve(name, cfg)` 按 name 选 → List<P> **被设计为 size=N**,而 v1.5.27 `@ConditionalOnMissingBean` 把它阉割到 size=1;v1.5.28 取消 `@ConditionalOnMissingBean` 改 plain `@Bean(name = "<slot>Provider_<name>")` + 唯一 `name()` —— 多 Provider 模式全开,Router List<P> size=N 实际生效,`agent.<slot>.name` 按名路由无需 exclude / rebuild classpath;对应 §5.4 plugin AutoConfiguration 编写约定也同步改「唯一 Bean 名约定」;5639 → 5709 行(+70);纯文档改动,代码逻辑零改动;v1.5.27 §4.6 ToolExecutor 接口定义补全 — 标题「Tool 与 ToolExecutor」但 §4.6 缺 `ToolExecutor` 本体,Story 实施者只能从 §4.10.1 / §5.5 / §6.5 散落引用反推;**补丁** 在 §4.6 `Skill extends Tool` 之后 / `ToolExecutionContext` 之前插入 `ToolExecutor` 接口完整定义 —— 单方法 `dispatch(ToolCall call, ToolExecutionContext ctx) → ToolResult`;Javadoc 覆盖 (1) 调用契约 + ReAct Action 阶段每个 `LlmResponse.getToolCalls()` 元素**必须**走此方法(不得直调 `tool.execute()`);(2) §4.10.1 硬规则 2 强制要求 —— ToolExecutor 内部统一串入 5 步流水线 `PermissionPolicy.check() §4.7 → ToolRegistry.lookup(name) → TimeoutWrap → SandboxApply(fs / http / process) §4.7 → tool.execute() → Checkpoint`,任何一步绕过 = 沙箱 / 权限 / 取消 / 超时全失效,Spring AI `ChatClient.tools().call()` 自动执行**禁止**使用;(3) ToolExecutor 与 Tool 接口解耦 —— ToolExecutor 不 import Tool 内部细节,只看 `ToolCall(name + args JSON)` + `ToolExecutionContext`,Tool 实现可手写(§6.5 (1))/ MCP server 暴露(§6.5 (2))/ Spring AI `@Tool` 注解生成仅 schema(§6.5 (3))—— ToolExecutor 一视同仁;(4) Provider 可插拔 —— 默认 `DefaultToolExecutorProvider`(stub §5.5 L2121)同步串行 dispatch;替代实现 `ParallelToolExecutorProvider`(并发)/ `ObservabilityToolExecutorProvider`(metric / trace),`name()` 走该实现标识("default" / "parallel" / "observability"),`priority()` ≥ 10 胜过默认 `priority=0`,**禁止与默认 `name()` 冲突**;(5) `@throws` 完整标注 —— `PermissionDeniedException`(§4.7)/ `ToolNotFoundException` / `TimeoutException`(`callConfig.timeoutSeconds`)/ `CancellationException`(Ctrl+C / FlowEngine markDone / 超时联动)4 类异常;**效果** Story 实施者打开 §4.6 即可看到完整 Slot 5 接口契约,无需散落反推;§4.10.1 硬规则 2 引用 `ToolExecutor.dispatch()` 现在有 single-source-of-truth 锚点;5590 → 5639 行(+49);纯文档补全,代码逻辑零改动;v1.5.26 §5.3.1.0 补齐 `FlowEngineRouter` 第 7 个隐式 Router concrete stub(extends `SlotRouter<FlowEngineProvider, FlowEngine>`,super 传 `"FlowEngine"` + Logger,Javadoc 说明**不在 SlotResolver 字段里,由 AgentFactory 直接 `@Autowired`** + 默认 `LinearTurnEngineProvider`(§6.1 L2530)+ 替代 `GoogleAdkFlowEngineProvider`(§4.11.2)/ `AlibabaGraphFlowEngineProvider`(§4.11.3));§5.3.1 标题计数 8 → 9 Router + §5.3.1.0 标题 6 → 7 隐式 Router + §5.3.1.0 总表加「注入位置」新列 + Slot 编号对齐 §5.6.4 SPI 总表(1—9);§5.6.4 SPI 总表新增「Router stub 位置」列 9 行 —— 7 行指 §5.3.1.0 + 1 行指 §5.3.1.1 + 1 行指 §5.3.1.2,Slot 8 行强调 `FlowEngineRouter` 注入 `AgentFactory` 而**不在 SlotResolver**;根因 v1.5.18 SlotResolver 屏蔽 Router 数 6 → 8 时只补 SlotResolver 内部 2 Router(MemorySource + A2aTransport),`AgentFactory.flowRouter` 是 v1.5.18 之前就已存在的字段(L3676),v1.5.23 §5.3.1.0 新增 6 隐式 Router 时漏了 `FlowEngineRouter`;**效果** Story #001 实施者打开 IDE 时,AgentFactory 启动校验所需 3 Router(`PermissionPolicyRouter` + `ToolExecutorRouter` + `FlowEngineRouter`)全部有完整 stub;v1.5.25 §5.4 末新增「plugin AutoConfiguration 编写约定(双 `@ConditionalOnMissingBean` 模式,避免双胜出)」子段 — 明确 plugin 自己的 `XxxProvider` Bean **也必须**加 `@ConditionalOnMissingBean(XxxProvider.class)`,与 §5.5 默认 AutoConfiguration 对称;Spring `@ConditionalOnMissingBean` 检查整个 `BeanFactory` 而非"当前 `@Configuration` 类内其他 `@Bean` 方法",**跨 AutoConfiguration 类的 Bean 可见性**取决于加载顺序,plugin 漏标会导致启动期 `BeanDefinitionOverrideException`(Spring Boot 2.1+ `spring.main.allow-bean-definition-overriding=false` 默认);附 (1) plugin 样例代码 `RagAutoConfiguration` 标 `@ConditionalOnMissingBean`;(2) 4 行 4 列加载顺序矩阵(plugin A 先 / plugin B 先 / 默认 / 漏标 四种情形)—— 一眼看清"漏标 = 启动失败";(3) 应急路径(第一时间检查 plugin 的 `@Bean` 是否漏标,**不要**开 `spring.main.allow-bean-definition-overriding=true` 掩盖问题);(4) 区分"plugin 注册 `XxxProvider` Bean(需双标)" vs "plugin 注册 `Tool` / `MemorySource` / `SkillSource` 等非 Slot 类型 Bean(按需创建,无需 `@ConditionalOnMissingBean`)" + `LocalToolsAutoConfiguration` 多 Tool 样例;(5) 传递依赖规则 + cross-ref §5.5 / §5.3 / §5.2;Story #009 / #016 实施期及未来 plugin 贡献者直接按 §5.4 子段样板写 plugin 即可;纯文档补全,代码逻辑零改动;v1.5.24 §5.5「默认实现的注册约定」子节扩展 — 原版只给 `DefaultPromptBuilderProvider` 1 个 `@AutoConfiguration` 模板,§5.6.4 SPI 总表 9 Slot × 默认 Provider 中只 3/9 有完整 stub(`DefaultPromptBuilderProvider` Slot 1 / `LinearTurnEngineProvider` Slot 8 / `HttpJsonRpcA2aTransportProvider` Slot 9),剩 6/9 默认 Provider 只列名未给 AutoConfiguration 样板 —— 补齐 Slot 2—7 共 6 个新 AutoConfiguration stub(`TruncatingCompactorProvider` / `AnthropicLlmProviderFactory` / `StrictPermissionPolicyProvider` / `DefaultToolExecutorProvider` / `FileSessionStoreProvider` / `ProjectClaudeMdSourceProvider`),共享 `@AutoConfiguration` + `@Bean @ConditionalOnMissingBean(<X>Provider.class)` + 匿名 inner class 模板,`create()` body 抛 `UnsupportedOperationException` 留给对应 Story 实施期填;附 (1) 9 Slot × 默认 Provider ↔ `create()` 返回类型 ↔ Story ↔ stub 位置 总表(L2118-2128 9 行),§5.6.4 与 §5.5 双向 cross-ref;(2) 10 个替代实现(`OpenAi / Gemini / DeepSeek` 3 个 LlmProvider / `Memory / Redis / Jdbc` 3 个 SessionStore / `Identity / ProjectTree / Conversation` 3 个 MemorySource / `Summary` 1 个 Compactor)追加约定 —— 模式同默认实现,`name()` 严格命名空间隔离;纯文档补全,代码逻辑零改动;v1.5.23 §5.3.1.0 新增「6 个隐式 Router concrete 类」子节 — 补齐 v1.5.18 SlotResolver 屏蔽 8 Router 时,§5.3.1.1/§5.3.1.2 只给了 2 Router 显式 stub(`MemorySourceRouter` + `A2aTransportRouter`),剩 6 个(`PromptBuilderRouter` / `LlmProviderRouter` / `ToolExecutorRouter` / `PermissionPolicyRouter` / `SessionStoreRouter` / `CompactorRouter`)只列名未给 concrete 定义;单 Java 文件给 6 Router 完整 stub,共享 `package` + `import`,附 6 Router ↔ Slot ↔ `<P, T>` ↔ Story 总表 + 5 行边界约束表 + 与 MemorySourceRouter 关键差异说明 + 实施期顺序建议(Story #001 → #002 → #003 → #014 → #015);v1.5.22 §10.1 锁定合计数显式化 + CLAUDE.md §11.6 历史 drift 修正;v1.5.21 §5.7 新增「插件机制选型决策(SPI vs ClassLoader 隔离)」子节 — 一站式回答"为什么 LingShu 用 Spring Boot SPI 而不是 OSGi / Pf4j / 自定义 ClassLoader 隔离";含决策结论 + 8 维机制对比表 + LingShu 7 个决策点场景契合度 + 主动放弃能力表 + 重新评估触发条件与演进路径;v1.5.20 §5.3.1 新增子节,补齐 v1.5.18 引入的 2 个 Router(`MemorySourceRouter` + `A2aTransportRouter`)concrete 类 stub + 通用模板 + 边界行为表;v1.5.19 §6.1 LinearTurnEngine + Provider 加类级 Javadoc;v1.5.18 §5.3 SlotResolver 屏蔽 Router 数 6 → 8;v1.5.17 §5.1 typed-Provider 列表补 `A2aTransportProvider` 行;v1.5.16 §5.1 L1463 orphan fence opener 误吞修复;v1.5.15 §4.11 Java 代码块补 closing fence;v1.5.14 §4.5.1 [TOOL SCHEMAS] 措辞修订 + 明确分层;v1.5.13 PromptBuilder API 对齐 .tools 字段;v1.5.12 删 stale Slot N;v1.5.11 Slot 命名对齐;v1.5.10 修 §0.1;v1.5.9 增 §4.5.1 [TOOL SCHEMAS] 段)
> - SpecKit SOP:[`speckit_operator_prompt.md`](./speckit_operator_prompt.md) v1.15 / 859 行 / 操作手册(R-13 mitigation (d) 镜像 + 依赖同步到 dsh v1.5.31 §5.6.3 L2429-2444 RemoteAgentTool 降级为普通 Tool 模式)
> - Prompt 速查:[`lingshu_spec_prompts.md`](./lingshu_spec_prompts.md) v1.0.14 / 368 行 / 新窗口 Prompt 模板(同步 dsh v1.5.31 §5.6.3 L2429-2444 RemoteAgentTool 降级为普通 Tool 模式)
> - SKILL:`~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` v1.0.21 / 192 行 / 自动触发(R-13 dep-tree 自查链路已纳入 + 同步 dsh v1.5.33 §5.6.3.0 四个核心类型 + v1.5.34 §7.1 Agent 生命周期与扩展边界 + v1.5.32 §6.4 Skill 三个契约锚点补全不变)

---

## 1. 项目身份

- **名称**:灵枢 LingShu Agent Engine(代号 DSH Agent)
- **定位**:JDK 8+ Java Agent 引擎,Spring Boot SPI,ReAct Loop,**9 个可插拔 Slot**
- **版本**:v0.1.0-SNAPSHOT(开发中,设计文档锁定 v1.5.6,见 §13)
- **设计灵感**:Apache DSH / Dubbo SPI 风格
- **目标用户**:企业内 AI 编码助手 / 业务配置方(只写 YAML) / 框架贡献者

---

## 2. 技术栈锁定(JDK 8 only)

| 项 | 版本 / 说明 |
|---|---|
| **编译目标** | Java 1.8(`<source>1.8</source>`)|
| **运行 JRE** | JDK 8 / 11 / 17 / 21 LTS(Spring Boot 3.2.5 实际跑需 JDK 17+)|
| **JVM 厂商** | Temurin / Zulu / Alibaba Dragonwell / IBM Semeru |
| **Spring Boot** | 3.2.5(BOM 引入)|
| **Spring AI** | `1.0.0-M6`(BOM 引入,v1.5.7 起)— **只**用于 LLM 协议转换 + `@Tool` Schema 生成,见 §11 #8 |
| **Lombok** | 1.18.30(配置类**全部** `@Value` 不可变风格)|
| **OpenTelemetry** | 1.32.x(**不跨 1.x → 2.x**,API 不兼容)|
| **Reactive Streams** | `org.reactivestreams:reactive-streams:1.0.4`(JDK 8 没内置)|
| **构建** | Maven 3.6.3+ 多模块(父 POM + 5 子模块)|
| **CI** | GitHub Actions matrix:ubuntu + JDK 8 / 17 / 21 |

---

## 3. JDK 8 硬约束(避坑清单)

- ❌ `record`(JDK 14+)
- ❌ `sealed` / `permits`(JDK 17+)
- ❌ `var` 关键字(JDK 10+)
- ❌ `List.of(...)`(JDK 9+)→ 用 `Arrays.asList(...)` 或 `Collections.unmodifiableList(...)`
- ❌ Pattern matching for switch(JDK 17+)
- ❌ Text blocks `"""..."""`(JDK 15+)
- ✅ 用 Lombok `@Value` / `@Builder` / `@NonNull` 替代 records
- ✅ 用 `org.reactivestreams:reactive-streams:1.0.4` 而非 JDK 9+ `Flow` API

---

## 4. 文件地图(本会话可见的资产)

| 用途 | 绝对路径 | 说明 |
|---|---|---|
| 设计文档 | `~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md` | **项目真理**,4768 行,**只读** |
| SpecKit SOP | `~/Documents/AIFullStack/MyDSHAgentDesign/speckit_operator_prompt.md` | SpecKit 操作手册,820 行 |
| Prompt 速查 | `~/Documents/AIFullStack/MyDSHAgentDesign/lingshu_spec_prompts.md` | 新窗口 Prompt 模板,368 行 |
| SKILL | `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` | Claude Code 自动触发 |
| SKILL mirror | `~/Documents/AIFullStack/MyDSHAgentDesign/.claude/skills/lingshu-spec-driven-dev/SKILL.md` | 拷贝版,跨机器用 |
| 主仓根 | `~/code/lingshu/`(或 clone 后的仓根)| 有 `pom.xml` / `lingshu-core/` / ... |

---

## 5. 仓目录结构(Maven 多模块,5 个 — 速查)

> **以 `dsh_agent_design.md §10` 为准**;本节只列顶层模块边界,Claude `ls` 能发现的不写。

```
lingshu/                                  ← 主仓根
├── pom.xml                               ← 父 POM(Spring Boot 3.2.5 父继承)
├── lingshu-core/                         ← 核心:9 Slot 接口 + AgentConfig + 默认实现
├── lingshu-a2a-client/                   ← A2A 客户端(§5.6,可独立打包)
├── lingshu-a2a-server/                   ← A2A 服务端 + AgentCard 生成
├── lingshu-examples/                     ← 教学示例(≤ 10 个,各 ≤ 100 行,见 dsh §10.2)
└── lingshu-cli/                          ← CLI 入口(`mvn exec:java`,见 dsh §10.3)
```

**包路径**:`ai.lingshu.core.*`(lingshu-core)/ `ai.lingshu.a2a.{client,server}.*`
**模块依赖方向**:core 不依赖 a2a-*;examples/cli 依赖 core + a2a-*;**严禁反向依赖**

**每个 Story 默认改动模块**(速查):

| Story | 主要改哪个模块 |
|---|---|
| #001 zero-config-bootstrap | lingshu-core + lingshu-examples |
| #002 identity-instructions-memory | lingshu-core(`PromptBuilder` 默认实现)|
| #003 spi-slot-router | lingshu-core(`SlotRouter` 接口)|
| #009 a2a-agent-card | lingshu-a2a-server(主)+ lingshu-core(接入 `A2aTransport`)|
| #016 audit-log | lingshu-core(`AuditLogger` SPI)|
| 其它 Story | 主要 lingshu-core;详见 dsh §3.1 SOP |

---

## 6. 9 个 Slot 接口(dsh §4 — 核心骨架)

| # | Slot | 接口 | 默认实现 |
|---|---|---|---|
| 1 | LLM | `LlmProvider` | Anthropic LlmProvider(Story #003)|
| 2 | Tool | `Tool` + `ToolExecutor` | 默认 Executor(Story #004)|
| 3 | Sandbox | `Sandbox` | JVM 内 chroot(Story #001 后)|
| 4 | Skill | `SkillSource` + `Skill` | classpath + directory 双源(Story #002)|
| 5 | SessionStore | `SessionStore` | memory(Story #014)|
| 6 | Compactor | `Compactor` | 无(Story #015)|
| 7 | PromptBuilder | `PromptBuilder` | 5 段装配(Story #002)|
| 8 | FlowEngine | `FlowEngine` | LinearTurnEngine = ReAct Loop(Story #001)|
| 9 | A2aTransport | `A2aTransport` | LocalAgentCardGenerator(Story #009)|

**包命名**:`ai.lingshu.core.*`

---

## 7. 核心约定(读 CLAUDE.md 时必看)

- **配置类**:全部 `@Value` + `@Builder`,**无 setter
- **错误码命名**:`LINGS-<域><编号>`,域字母 = `C/S/L/T/X/R/A/Z`(详见 dsh §15)
- **不可变性优先**:能 `@Value` 就不用 `@Data`
- **常量**:`SCREAMING_SNAKE_CASE`
- **业务三件套**:Identity / Instructions / Memory(详见 dsh §8.1)
- **零配置原则**:空 application.yml 必须能启动,所有 27 字段有默认值
- **5 段 Prompt**:`[ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY] / [CONVERSATION HISTORY] / [USER MESSAGE]`

---

## 8. 常用命令

```bash
# 环境检查
mvn -v                                       # Maven 3.6.3+ / JDK 1.8.0_xxx+

# POM 语法校验(不下依赖)
mvn validate -N

# 编译 + 测试
mvn -pl lingshu-core -am compile
mvn -pl lingshu-core test

# 全模块编译
mvn -DskipTests=true install

# 跑某个 Story 的 AC 测试
mvn test -Dtest=AC_NN_ClassName

# 性能压测(Story #010 后才有)
k6 run perf/load/<scenario>.js
```

---

## 9. Git workflow

```bash
# 提交约定
feat(agent): Story #NNN <slug> — <一句话>
docs(design): vX.Y.Z — <一句话>
fix(slot-<N>): <一句话>
chore: <一句话>

# 不允许的提交
× 直接 commit 到 main
× 跨多个 Story 的"feat:" commit
× 没跑 AC 的 PR
× 把 SOP / Prompt 速查 / SKILL push 到 lingshu 仓
```

---

## 10. 性能预算(dsh §14.15.1,实施时对齐)

| 指标 | 目标 |
|---|---|
| LLM 流式首 token | P50 ≤ 1.5s / P99 ≤ 3.0s |
| turn 完成(10 steps)| P50 ≤ 30s / P99 ≤ 60s |
| Tool 调用 | P99 ≤ toolTimeoutSec |
| 单 turn history | ≤ 100K tokens |
| 冷启动 | ≤ 30s(空 yml)|
| 并发 turn 数 | 默认 16,排队 ≤ 32 |

---

## 11. 硬约束(违反即 reject)

1. **不 push SOP 到 lingshu 仓**(`speckit_operator_prompt.md` / `lingshu_spec_prompts.md` 是本地流程制品)
2. **不省略 AC 黑盒验证**(每个 Story 必须跑对应的 AC-NN 才能合)
3. **不跨 Story 改 constitution**(改宪章必须走 RFC 流程,先开 issue + 评审)
4. **Story 边界**:≤ 5 个核心文件改动,≤ 3 个 ErrorCode 引入(超过就拆)
5. **不绕过 Spring Boot SPI**(新增 Slot 必须走 Provider + SlotRouter,**不要硬编码**)
6. **不引入额外依赖**(dsh §10.1 已锁 13 项[含 `spring-ai-bom`,v1.5.7 引入],新依赖需 RFC + `dependency:tree` CI 卡点 + **`banned-dependencies` enforcer build 阶段 fail**(见 dsh §17 R-13);任何 Story 实施者必须按 SOP §3.2 AC-NN-deps-* + §3.4 T-dep-tree-* 流程自查后提交,**PR body 末尾**必须有 `### R-13 dependency:tree 自查` 节)
7. **ReAct Loop 必须自实现**(不得用 Spring AI `ChatClient.prompt().call()` 自动工具执行;核心循环 ~ 数十行,完整掌握 Agent 工作机制,保留定制循环行为的空间 — dsh §4.10.1 硬规则 1)
8. **Spring AI 只用两件事**:① LLM Provider 协议转换(OpenAI / Anthropic / Gemini / DeepSeek / Qwen / Kimi 等格式差异) ② `@Tool` 注解 JSON Schema 生成。**必须禁用** Spring AI 自动 tool 执行 — 会绕过 ToolExecutor 的沙箱/权限/checkpoint,导致 tool 被调两次(dsh §4.10.1 硬规则 2)
9. **Provider 必须显式映射**:多 `ChatModel` 并存时 Bean 类型相同,必须维护 `Map<String, ChatModel> providerMap` 显式查找;不得靠 Spring 容器扫 Bean 类型区分(dsh §4.10.1 硬规则 3)

---

## 12. 常用反问(避免无脑实现)

- 用户说"实现 X" → 先问"对应哪个 Story?或对应哪条 AC?"
- 用户说"改 Y" → 先确认 Y 是否在已有 Story 范围,避免 Scope creep
- 用户说"加依赖 Z" → 先查 dsh §10.1 锁定表
- 用户说"用 record/sealed/var" → 提醒 JDK 8 约束
- 用户说"push SOP" → 提醒归档边界

---

## 13. 项目节奏(KPI)

- **Story 完成率**:每周 / 每月闭合几个 Story
- **平均 AC 通过率**:第一遍跑过的比例(目标 ≥ 80%)
- **宪章稳定性**:`constitution.md` 一个月改几次(目标 0 次,改必须 RFC)
- **Risk Register 缓解率**:R-XX 中已缓解的比例(目标 ≥ 60% by v1.0 GA)

---

**Last updated**: 2026-09-24
**Version**: 1.3.36(v1.3.36 Story #022 spring-ai-annotation-tool 文档同步 — dsh v1.5.40 → v1.5.41(§0 标题 v1.5.40 → v1.5.41 + §6.5 (3) L4873-4980 `@AgentTool` 注解 + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` 完整定义 + §15 域字母加 T 段 8 号空位 = LINGS-T08 反射调用失败 ErrorCode + §13 changelog 新增 v1.5.41 行 13 节记录 Story #022 完成:`@AgentTool` annotation(`@Retention(RUNTIME)` + `@Target(METHOD)` + `name()` + `description()` + `capabilities()` 默认空 + `returnDirect()` 默认 false) + `SpringAiToolAdapter` implements Tool(JSON Schema 启动期 from reflection Method + `execute()` reflection invoke + 业务异常 catch-all 转 `[LINGS-T08]` ErrorCode ToolResult.error)/ `AgentToolScanner` implements `ApplicationContextAware` 启动期扫 `ctx.getBeansWithAnnotation(Component.class).values()` 反射找 `@AgentTool` method 调 `toolRegistry.register(new SpringAiToolAdapter(...))` 完整 LLM 视角可发现 + `JsonArgsConverter.convert(ObjectNode, Parameter[])`(primitive + String 类型映射 / 缺字段 primitive 抛 IAE / boxed 传 null / 复杂类型抛 IAE 走 LINGS-T08)+ `ToolErrorCodes.LINGS_T08` 常量类 + 32 new cases 跨 4 测试文件(13 L1 `JsonArgsConverterTest` + 10 L1/L2 `SpringAiToolAdapterTest` + 5 L2 `AgentToolScannerTest` + 4 L2/L3 `AgentToolIntegrationTest`)/ 1 ErrorCode LINGS-T08 / 513 tests pass / R-13 mitigation (d) baseline 镜像 pre/post dep-tree 仅时间戳差异 PASS 0 binary delta(复用 spring-ai-bom 1.0.0-M6 已在 13 项依赖表内 / Jackson + Lombok 已锁 0 新依赖);**复用 spring-ai `@Tool` 注解信息但不依赖 spring-ai 自动执行**(dsh §4.10.1 硬规则 2 守住 —— Spring AI `ChatClient.tools().call()` 自动执行**禁止**使用)+ specs/ROADMAP.md 段一 ✅ 已完成加 #022 行(2026-09-24,513 pass / 0 fail / R-13 0 binary delta / +LINGS-T08)+ 段二 🟡 待补 #022 划掉 + 段五 🎯 实施节奏 next = #023 delegate-sub-agent(强依赖 #009e 已满足)+「对应设计文档」版本号 v1.5.40 → v1.5.41;**关键不变项** —— `Tool` 接口契约不变(只新增 Tool 实现)/ `ToolRegistry` SPI 不变(#020a 已落地)/ `ToolExecutor` 5 步流水线不变(§4.10.1 硬规则 2)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 / 0 新 Maven 依赖 / 1 新 ErrorCode LINGS-T08(工具域 T 段 8 号空位)/ R-13 mitigation (d) baseline 镜像 PASS 0 binary delta);v1.3.35(v1.3.35 Story #009e a2a-remote-tool-wiring 文档同步 — dsh v1.5.39 → v1.5.40(§0 标题 v1.5.39 → v1.5.40 + §13 changelog 新增 v1.5.40 行 13 节记录 Story #009e 完成 + §5.6.3.1 `HttpJsonRpcA2aTransportAutoConfiguration` stub 同步(v1.5.37 #009c 加的 `remoteAgentTool` + `remoteAgentSchemaBuilder` 2 `@Bean` **已迁出**)+ §5.6.3.2 Grpc/InProcess stub 段 cross-ref 加「🆕 v1.5.40 Story #009e 修复要点」blockquote(3 transport 共享独立 `RemoteAgentToolAutoConfiguration` + `RemoteAgentToolLifecycle` SmartLifecycle 显式 register/unregister,y切换 transport 无需改 Spring config)+ §5.6.4 SPI 总表 Slot 9 行同步 Slot 9 = 🆕 v0.5 + v1.5.37(`RemoteAgentTool`)+ 🆕 v1.5.40 wiring 独立 — `RemoteAgentToolAutoConfiguration` 独立 `@AutoConfiguration` + `RemoteAgentToolLifecycle` 显式 `toolRegistry.register/unregister` + specs/ROADMAP.md 段一 ✅ 已完成加 #009e 行(2026-09-24,333 pass / 0 fail / R-13 0 binary delta / 0 ErrorCode)+ 段二 🟡 待补 #009e 划掉 + 段五 🎯 实施节奏 next = #022 / #023 并行 + 「对应设计文档」版本号 v1.5.39 → v1.5.40)+ **关键不变项** — `RemoteAgentTool` 类**不**改(2/3/5 参构造器全部保留,#009c/#009d 测试 0 regression)/ `RemoteAgentSchemaBuilder` 类**不**改(#009d 已落地)/ `A2aTransportRouter` 行为**不**改(#009a 已落地)/ `A2aTransport` 5 方法契约**不**改/ `ToolRegistry` SPI **不**改(#020a 已落地)/ `ToolExecutor` 5 步流水线**不**改(§4.10.1 硬规则 2)/ §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容/ 0 新 Maven 依赖 / 0 新 ErrorCode / R-13 mitigation (d) baseline 镜像 PASS 0 binary delta;**Story #009e 内容** — 抽 `RemoteAgentToolAutoConfiguration` 独立 `@AutoConfiguration`(从 `HttpJsonRpcA2aTransportAutoConfiguration` 删 2 `@Bean` `remoteAgentTool` + `remoteAgentSchemaBuilder` 搬过去)+ 加 `RemoteAgentToolLifecycle implements SmartLifecycle`(`start()` 调 `toolRegistry.register(remoteAgentTool)` `running` flag 幂等保护 + `stop()` 调 `toolRegistry.unregister("remote_agent")` + `isAutoStartup() = true` + `getPhase() = Integer.MAX_VALUE - 1024` 同 `McpTransportLifecycle`)+ `HttpJsonRpcA2aTransportAutoConfiguration` 简化(只保留 `a2aTransportProvider_http-jsonrpc-1.0.0` 1 个 Bean)+ `GrpcA2aTransportAutoConfiguration` / `InProcessA2aTransportAutoConfiguration` 不变(transport Bean 单 Bean 模式)+ SPI 加载顺序 `RemoteAgentToolAutoConfiguration` 行放第一(transport 三行之前,Router 解析时 transport Bean 已就位)+ 14 net new case(5 L1 `RemoteAgentToolAutoConfigurationTest` + 4 L2 `RemoteAgentToolLifecycleTest` + 5 L3 `RemoteAgentTransportWiringIT` - 2 删 `HttpJsonRpcA2aTransportAutoConfigurationTest` 旧 case = 12 净)+ 直接 wiring 不走 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue,沿用 Story #007 模式)+ 关键 R-19 (分值 9) RemoteAgentTool wiring gap 本 Story **全部缓解**;**预** Story #022 / #023 之前必做避免后续 patcharound(原计划周期 3 个 Story 排到的 + #009e 实测发现);0 行代码改动(纯文档同步 **预** 真正的实施 commit 由 Story #009e 实施者出);v1.3.34 —(v1.3.34 Story #021c mcp-sse-and-http-transport 文档同步 — README.md 「核心特性」段合并 #021a stdio + #021b tool-adapter + #021c 3 transport 为单 bullet(`McpServerConnection` interface 8 方法 + 6 态状态机 + 3 concrete 实现 stdio/SSE/STREAMABLE_HTTP + `McpServerConnectionFactory.create(cfg.transport())` 静态分派 + `McpHttpSupport` 共享样板 + SSE 手写 parser + streamable HTTP 无状态 POST + 共享指数退避 1s→60s(cap) + 新错误域 M(01/02/03)) +「Story 路线图」段追加 #021c retrospective(`McpHttpSupport` 6 静态方法 + 5 个新 production 文件 + 2 fixture + 1 helper + 10 个新测试文件 + Factory dispatch 改写删 2 旧 case 加 5 新 case + 累计 481 tests 0 fail / R-13 0 binary delta / MCP 3 transport 全上线)/ specs/ROADMAP.md 段一 ✅ 已完成 + 段二 🟡 待补 #021c 划掉 + 段五 🎯 实施节奏 next = #022 / #023 并行 + 统计 6 已合 / 2 待补 / +80 新 case(=481);v1.3.33 Story #021a mcp-stdio-transport 文档同步 — dsh v1.5.37 → v1.5.38(§0 标题 v1.5.37 → v1.5.38 + §13 changelog 新增 v1.5.38 行 13 节记录 Story #021a 完成:`McpTransportType` enum 放 runtime 避免循环依赖 + `AgentConfig.ServerConfig` 扩 5 字段 transport/url/heartbeat*3/reconnectCapMs 向后兼容 + `McpServerConfig` POJO + `ConnectionState` 6 态 + `McpServerConnection` interface 8 方法 + `McpToolDescriptor` / `McpCallResult` / `McpTransportException` 3 最小类型 + `McpServerConnectionFactory` 静态分派(SSE/HTTP 抛 LINGS-M01) + `StdioMcpServerConnection` 完整实现 ~480 行(AtomicReference + daemon ScheduledExecutorService + 5 步 start + 双探活 probe + 指数退避 1s→60s(cap) 无限重试 + per-listener try/catch 异常隔离 + close 优雅停机) + §15.9 MCP 域 LINGS-M01 MCP_CONNECT_FAILED / §15.9 ErrorCode 编码约定 → §15.10 顺延 + §15 域字母加 `M=MCP` / 10 核心 Java 源文件 + 1 modify(AgentConfig.ServerConfig) 边界 stretch 接受(每个文件职责内聚 / 0 共享修改 / R-13 0 binary delta 兜底)/ 36 new cases 跨 12 测试文件 / 1 ErrorCode / 401 tests pass / R-13 mitigation (d) baseline 镜像 pre/post dep-tree 仅时间戳差异 PASS 0 binary delta(JDK 8 内置 ProcessBuilder + BufferedReader + Jackson 已锁 13 项依赖 0 新增))+ README.md 「Story 路线图」段追加 #021a retrospective(`McpServerConnection` interface 8 方法契约 + `ConnectionState` 6 态 + `StdioMcpServerConnection` 完整实现 + `McpServerConnectionFactory` 静态分派 + LINGS-M01 + R-13 0 binary delta)/ specs/ROADMAP.md MCP 支链 A 1/3 完成 🎉(`#021a` ✅ 36 tests 0 fail / R-13 0 binary delta)/ 下一步 = #021b mcp-tool-adapter(McpTransport + McpToolDescriptor + McpCallResult + McpToolAdapter + register/unregister 钩子 + LINGS-M02);v1.3.32 — README.md 「Story 路线图」段追加 #020c retrospective(`SkillCommandDispatcher` 3 段职责 + Agent.continueWithUserMessageBlocking 同步版 + CliRunner /xxx 拦截 + --list-skills banner + Args/ArgsParser flag + R-13 0 binary delta)/ specs/ROADMAP.md 主链 3/3 完成 🎉(Story #020a + #020b + #020c 全部 ✅)/ 下一步 = #021a mcp-stdio-transport;v1.3.31( Story #009d a2a-remote-schema-builder 文档同步 — dsh v1.5.36 → v1.5.37(§0 标题 v1.5.36 → v1.5.37 + §13 changelog 新增 v1.5.37 行 13 节记录 Story #009d 完成:`RemoteAgentSchemaBuilder` 新增 / `AgentRef` 新增放 core / `RemoteAgentTool` 5-arg ctor + description() 3-分支 logic(BASE / HINT / 列表截断)/ `AgentConfig.A2a` 扩 2 字段 `remoteAgents` + `descriptionSkillLimit` / `HttpJsonRpcA2aTransportAutoConfiguration` 加 `@Bean(name="remoteAgentSchemaBuilder")` + 12 测试同步 site / 15 文件改动 / 17 new cases / 0 ErrorCode / 321 tests pass / R-13 mitigation (d) baseline 镜像 pre/post dep-tree 仅时间戳差异 PASS 0 binary delta;OQ-1/OQ-2/OQ-5 仍 OQ-Future)+ CLAUDE.md 对应设计文档版本号引用同步(L218 `dsh_agent_design.md v1.5.36` → `v1.5.37`)+ SKILL.md 同步 v1.0.23 → v1.0.24 + constitution §10 R-13 标记 Story #009d 已缓解 / 0 行代码改动(纯文档同步);v1.3.30 Story #008 react-max-steps 文档同步 — dsh v1.5.35 → v1.5.36(§0 标题 v1.5.35 → v1.5.36 + §0.4 版本 blockquote 预本条 + §6.1 L3611-3686 代码块同步为改后版本 `for (int step = 1; step <= maxSteps; step++)` + 4 条件 AND 守卫发射 + last/totalUsage 跟踪 + `TurnCompleted(last.getStopReason(), totalUsage)` 收口 + §13 表格新增 v1.5.36 行记录 Story #008 完成)+ CLAUDE.md 对应设计文档版本号引用同步(L218 `dsh_agent_design.md v1.5.35` → `v1.5.36`)/ 0 行代码改动(纯文档同步);v1.3.29 Story #007 yaml-hot-reload 文档同步 — dsh v1.5.34 → v1.5.35(§0 标题 + §0.4 版本 blockquote 预本条 + §13 表格新增 v1.5.35 行记录 Story #007 完成)+ CLAUDE.md 对应设计文档版本号引用同步(L217 `dsh_agent_design.md v1.5.34` → `v1.5.35`)/ 0 行代码改动(纯文档同步);v1.3.28 文档一致性同步(SOP v1.17 → v1.18 / prompts v1.0.15 → v1.0.16 / 速览文件 v1.0 → v1.1 / SKILL.md 2 处 4768 → 7250) — v1.3.27 §0 + §13 末尾「对应 SpecKit SOP」/「对应 Prompt 速查」版本号引用 drift(SOP 文件实际 v1.18 但 CLAUDE.md 引用 v1.16 / prompts 文件实际 v1.0.16 但 CLAUDE.md 引用 v1.0.15);`新窗口流程速览(灵枢 Java引擎正式开工).md` 头加 Version 1.1 + 适用前提;dsh v1.5.14 → v1.5.34 / 7250 行;SOP v1.3 → v1.18 / prompts v1.0.2 → v1.0.16;反模式表「Context 爆炸 4768 行」→「7250 行,逼近单次上限」;constitution §3 → §4(错误码约定在 §4,§3 是 NFR 基线);删除末尾残留对话「需要我现在就帮你起...」;**SKILL.md** L24 Harness 硬限制 4768 → 7250 + L169 反模式表 4768 → 7250;**工具完成** `tools/img-pipeline/` 本地不入仓(`doc/公众号系列/*.md` 8 篇 69 块 → 69 PNG + 8 `.wechat.md`,7.2MB;沿用单贡献者 SOP);**关键不变项** — dsh v1.5.34 / v1.5.33 §5.6.3.0 / v1.5.32 §6.4 / v1.5.31 §5.6.3 / 全部 0 行代码改动(纯文档同步 + 工具完成);v1.3.27(依赖同步 — dsh v1.5.33 → v1.5.34(v1.5.34 §7.1 新增子节 —— 明确「AgentFactory 是 Spring 单例 Bean(无状态,持 7 Router),Agent 是 factory 的产品(prototype-like,带 session/config/engine 状态,每次 create 一份,Spring 不持有引用)」 —— **问题** 有读者疑惑「既然 AgentFactory 是 @Component,为什么不把主 Agent 也做成 Spring 单例,直接 @Autowired Agent 拿」 —— **根因** 混淆"基础设施 Bean"与"运行时执行实例"两类对象;**5 维度对比论证** —— (1) 多轮对话 session 隔离(Spring 单例 = 进程一份,session 污染 vs factory 每次 create 独立 session 互不干扰);(2) 子 Agent 共享(主 Agent 单例字段被多个子 Agent 共享引用,父-子状态污染 vs 子 Agent 走同一 factory 独立 config/engine);(3) A2A 多 RemoteAgent(每个 remote agent URL / skill 列表不同塞不进单例 vs §5.6.3.0 AgentCard / AgentRef 本来就是每个 remote agent 独立 config 设计的);(4) 测试 Mock(@MockBean Agent 让所有 @Autowired Agent 拿 mock 包括本该真实的子 Agent vs Mock factory 子 Agent 仍真实);(5) Spring Bean 语义(Spring Bean 默认无状态基础设施,Agent 持 4 个 final 字段运行时才能给值启动期根本塞不进 vs factory 持 7 Router 启动期就绪,Agent final 字段运行时 create() 传入);**Agent 生命周期时序图** —— Spring startup → T0 user code loadYaml + 编辑 cfg → T1 factory.create(cfg)7 项校验 + flowRouter.resolve → T2 agent.run ReAct 循环 → T3 流关闭 / TERM/MAX_STEPS/CANCEL → T4 Agent 实例 GC(无外部引用时 JVM 自处理无需 destroy 钩子)→ T5 session 持久化(按需);**关键不变量**(§4.1 + §7 决策 12 共同保障)—— (1) AgentFactory 整个 JVM 一份(@Component 单例)Agent N 份(每次 create 一份 user code 持有引用);(2) Agent 4 个 final 字段(config/session/engine/toolPool)在 T1 时刻确定 T1→T4 期间不变 §4.1 注释"AgentFactory.create() 构造一次,整个 turn 内不变";(3) Agent 不持有任何 Spring 引用 销毁无需通知 Spring;(4) 单 turn 单 Agent 多 turn 必须多次 create;**扩展点矩阵** —— 7 Router(AgentFactory 直接 @Autowired FlowEngineRouter + SlotResolver 持 6 Router)× 9 Slot,用户扩展 Provider 不扩展 Router / AgentFactory / Agent 三件套,YAML 里 agent.<slot>:<name> 改 Router 解析目标不需要动 Spring 配置;**正确扩展样板** —— @Component public class ParallelTurnEngineProvider implements FlowEngineProvider { name()="parallel" priority()=10 create(cfg)=new ParallelTurnEngine(cfg) };用户拿 Agent 入口永远是 factory.create(...)内部自动选 Provider;**反模式 AgentHolder** —— @Component 包出"单例 Agent"5 个失败场景:(1) session 污染(首次调用的 session 被锁定多用户并发请求全混在一起 §4.7 PermissionPolicy 决策完全失效);(2) config 漂移(用户每次想加 tool 换 model 都不能 AgentHolder 单例创建时已锁死违反 §8 核心原则);(3) 子 Agent 反模式(强制共享 cached 字段污染 或 再开 cached_for_subagent 回归 factory 模式完全多余);(4) A2A 不可行(一个 cached 装不下 N 个 remote agent URL);(5) 违反 §4.1 不变项(把 turn-scoped 升到 process-scoped);**如果真的只有一个永远不变的主 Agent**(简单 CLI 脚本)—— 直接 new AgentFactory(...) + factory.create(cfg)连 Spring 都不用 Spring 单例问题自然消解;**修复者** Claude Code(根据用户 2026-09-17 会话反馈,用户问「AgentFactory 是 @Component,主 Agent 该不该 @Autowired Agent 注入 Bean 容器」,确认设计文档隐含 factory 模式但未显式说明,要求补 §7.1);**§7.1.6 一句话总结** —— AgentFactory 是 Spring Bean(singleton 无状态持 7 Router),Agent 是 factory 的产品(prototype-like 带 session/config/engine 状态每次 create 一份 Spring 不持有引用),用户扩展 9 Slot 的 Provider 不扩展 Agent 本身,拿 Agent 永远走 factory.create(config);**纯文档改动**,代码逻辑零改动;7039 → ~7250 行(+约 210);SKILL v1.0.20 → v1.0.21 / SOP v1.16 → v1.17 / prompts v1.0.14 → v1.0.15 同步(SOP/prompts 本地);v1.5.33 §5.6.3.0 不变;v1.5.33 §5.6.3.0 新增四个核心类型完整定义 —— `AgentCard` Lombok @Data + 4 nested type / `AgentRef` + `AgentRefBuilder` 装配器 priority 去重排序 / `RemoteAgentSchemaBuilder` @Component 启动期扫 `AgentCard.skills[]` 动态生成 `ToolSpec` list / `AgentCardCache` @Component TTL 缓存 + 负缓存 + FIFO evict + 命中率指标 — **问题** v1.5.4 §5.6 引入 A2A 时 4 个核心类型先实现后文档,§5.6.3 L2395-2480 草图引用了但只给名字未给完整定义,**类型契约不在 single-source-of-truth**;**根因** §5.6.3 草图聚焦"3 个新接口 + RemoteAgentTool + AutoConfiguration"简洁契约视图,故意省略"数据类型 + 基础设施 helper"细节层;v1.5.30 §5.6.3.1 补 concrete class 时也只补了 HttpJsonRpcA2aTransport,4 个核心类型未补;**补丁** §5.6.3.0 新增子节(L2482-2992,~510 行),按"**数据 → 引用 → 构建器 → 缓存**"顺序补全:`AgentCard`(Lombok @Data + 4 nested type + 字段语义 Javadoc + `isValid()` 校验)/ `AgentRef`(Lombok @Data + `AgentRefBuilder` 装配器 priority 去重 + 按 priority desc 排序)/ `RemoteAgentSchemaBuilder`(@Component `buildToolSpecs(refs, cards)` 启动期扫 `AgentCard.skills[]` 动态生成 `ToolSpec` list,按 (agentName, skillId) 排序稳定 prompt cache 命中)/ `AgentCardCache`(@Component TTL 缓存 + 负缓存 + FIFO evict + 命中率指标);**关键不变项** —— `A2aTransport` 5 方法契约 / Slot 9 SPI / `A2aTransportRouter` / `RemoteAgentTool` 内部 / `ToolExecutor` 5 步流水线 / §4.7 PermissionPolicy / AuditLogger / Cost 域 全部不变 —— **4 个类型都是"实现细节层",不引入新接口契约**;**纯文档改动**,代码逻辑零改动;6447 → 7039 行(+592);SKILL v1.0.20 → v1.0.21 / SOP v1.16 → v1.17 / prompts v1.0.14 → v1.0.15 同步;v1.5.32 §6.4 Skill 三个契约锚点补全不变;§6.4 Skill 三个契约锚点补全 —— `Skill` 接口定义 + `SkillTool.fromMarkdown` 静态工厂 + `@Component implements Skill` 硬编码对照示例 — **问题** v1.5.31 §6.4 全文写 "Skill extends Tool" 但 §4.6 无 `Skill` 接口定义 + `SkillTool.fromMarkdown(name, content)` 在 L3587/L3636 引用未定义 + 只展示 SKILL.md 文件加载路径缺硬编码 `@Component` 对照路径三重 gap;**根因** §6.4 v1.5.0 写时把 Skill 当 Tool 子集用,缺接口契约 + 工厂方法 + 两种路径对照三件套,Skill 这一支没跟着 v1.5.18 §4.6 ToolExecutor 同步补全;**补丁** §6.4 L3476-3500 新增 `Skill` 接口(`extends Tool` + 4 点差异 + 3 条未来扩展空间)/ §6.4 L3783-3790 `SkillTool` 加 `fromMarkdown(name, markdownContent)` 静态工厂(SKILL.md 第一行 # title 提取 description,inputSchema 固定 `{ "input": string }`)/ §6.4 L3810-3852 新增 `@Component CommitSkill implements Skill` 硬编码对照示例(Conventional Commits 风格 commit message)+ SkillTool vs @Component 对照表 6 行 + 选型决策 3 条;**关键不变项** —— `Tool` 接口 / `SkillLoader` / `CompositeSkillLoader` 行为 / `ToolRegistry` 注册路径 / `ToolExecutor` 5 步流水线 / §4.7 PermissionPolicy / AuditLogger / Cost 域 全部不变;6395 → 6447 行(+52);SKILL v1.0.20 → v1.0.21 / SOP v1.15 → v1.16 / prompts v1.0.14 → v1.0.15 同步;v1.5.31 §5.6.3 L2429-2444 `RemoteAgentToolProvider` 误用 `@AutoService(ToolProvider.class)` 修复 + v1.5.30 §5.6.3.1/§5.6.3.2 不变v1.5.31(§5.6.3 L2429-2444 `RemoteAgentToolProvider` 误用 `@AutoService(ToolProvider.class)` 修复 —— 降级为普通 `Tool` 模式 — **问题** v1.5.30 §5.6.3 用 `@AutoService(ToolProvider.class)` 标注 `RemoteAgentToolProvider` 与 §5.7 SPI 决策(选 Spring Boot SPI 不选 Java SPI)冲突 + §5.5 / §6.5 全部 Tool 用 `@Component` 或 `@AutoConfiguration` + `@Bean` 注册无 `ToolProvider` 抽象 + `ToolProvider` 接口未定义(三重 bug);**根因** v1.5.4 §5.6 引入 A2A 时 RemoteAgentTool 曾短暂被设计为 ToolProvider 抽象,后续降级为普通 Tool 但代码块没同步改;**补丁** §5.6.3 L2429-2444 改写为 `@Component RemoteAgentTool implements Tool` + `RemoteAgentToolAutoConfiguration`(`@Bean public Tool remoteAgentTool()`,与 §5.5 plugin 非 Slot 类型 Bean 样板对齐);§5.6.1 L2346 对照表 Tool 发现机制从「`@AutoService` 启动期静态注册」改「Spring `@Component` / `@AutoConfiguration` + `@Bean` 启动期静态注册」;**关键不变项** —— A2aTransport 5 方法契约不变 / Slot 9 SPI 不变 / A2aTransportRouter 行为不变 / RemoteAgentTool 内部不变(只看 A2aTransport 接口)/ ToolExecutor 5 步流水线不变 / §4.7 PermissionPolicy / AuditLogger / Cost 域 兼容;6377 → 6395 行(+18);SKILL v1.0.19 → v1.0.20 / SOP v1.14 → v1.15 / prompts v1.0.13 → v1.0.14 同步;v1.5.30 §5.6.3.1 + §5.6.3.2 不变v1.5.30 §5.6.3.1 新增 `HttpJsonRpcA2aTransport` concrete class + `HttpJsonRpcA2aTransportProvider` concrete Provider 完整示例 + §5.6.3.2 备选 `GrpcA2aTransport` / `InProcessA2aTransport` 「3 件套模式」扩展指南 — 澄清 `HttpJsonRpcA2aTransport` 是 concrete class 不是 `A2aTransport` 子接口 + 命名不一致修复(匿名 inner class → named class)+ Grpc/InProcess 未来怎么加的 3 件套样板 + application.yml 配置示例 + 实施期检查清单 6 条;6049 → 6377 行(+328);SKILL v1.0.18 → v1.0.19 / SOP v1.13 → v1.14 / prompts v1.0.12 → v1.0.13 同步;v1.5.29 §6.5 (2.1) 新增 `McpServerConnection` 实现示例 (心跳保活 + 指数退避重连) + §6.5 (2) `McpTransport` 同步改写 — §6.5 (2) 假设 MCP server「连上就永远连着」,生产环境 MCP server 子进程可能被 OOM 杀、stdio 僵死、SSE 反向代理超时踢线 —— Agent 进程会因 MCP server 抖动连锁崩盘,且当前文档缺 single-source-of-truth 的心跳 / 重连机制样板,Story #009 实施者只能反推 §4.10.1 错误处理边界自己设计;**根因** §6.5 (2) 原版 `McpServerConnection.start(cfg)` 是一次性同步连接 stub,没引入状态机 / 心跳 / 重连概念;v1.5.x 早期把 MCP 当「远程 Tool 注册中心」轻量集成,没考虑 24×7 长生命周期运维需求;到 v1.5.28 多 Provider 模式 + 9 Slot 体系成熟,**MCP 的「长连接」属性被放大** —— 必须补完整的生命周期管理;**补丁** (1) **§6.5 (2.1) 新增子节**(~270 行):`McpServerConnection` 接口(`extends AutoCloseable`,8 个方法)+ `ConnectionState` enum 6 态 + `McpServerConnectionFactory` 按 transport 分派 + `StdioMcpServerConnection` 完整实现(`AtomicReference` + daemon `ScheduledExecutorService` + 5 步 start + 双探活 probe + 指数退避 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` + 无限重试) + `SseMcpServerConnection` 差异段 + `McpTransport` 同步改写(`McpServerConnectionFactory.create(cfg)` + `onStateChange(listener)` + `onConnectionStateChange()` 私有方法)+ `application.yml` 配置示例 + 启动日志样例;(2) **JDK 8 兼容** —— `AtomicReference` / `Collections.emptyList()`,不用 `List.of` / `var` / sealed;(3) **关键不变项** —— `McpToolAdapter` / `ToolExecutor.dispatch()` / `PermissionPolicy.check()` 完全不变,MCP 断流在 `ToolResult` 层只表现为「error 替代 success」,**不会绕过沙箱 / 权限 / checkpoint 任何一步**,与 §4.10.1 硬规则 2 完全兼容;5709 → 6049 行(+340);SKILL v1.0.17 → v1.0.18 / SOP v1.12 → v1.13 / prompts v1.0.11 → v1.0.12 同步;v1.5.28 §5.5 改「多 Provider 模式」样板 + §5.4 同步改「唯一 Bean 名约定」+ §5.6 Slot 9 `HttpJsonRpcA2aTransportProvider` stub 同步 — **问题** v1.5.27 §5.5 用 `@ConditionalOnMissingBean` 强制「部署期二选一」,同一 Slot 最多 1 个 `XxxProvider` Bean 注册到 Spring 容器 → 用户切换 Provider 必须改 classpath / exclude / 改 Bean 名;但 §5.3.1.0 `SlotRouter<P, T>` 父类**一直是多 Provider 友好** —— 构造器收 `List<P> providers`,启动期按 `name()` 收 `Map<String, P>`,`resolve(name, cfg)` 按 name 选 → List<P> **被设计为 size=N**,而 v1.5.27 `@ConditionalOnMissingBean` 把它阉割到 size=1,**多 Provider 能力框架自身不用**;**根因** v1.5.24 §5.5 引入 6 默认 Provider stub 时直接复用 v1.5.0 `DefaultPromptBuilderProvider` 模板的 `@ConditionalOnMissingBean`,当时设计意图是「防止用户覆盖默认」,但代价是阉割 §5.3.1.0 Router 的多 Provider 能力;v1.5.25 §5.4 双 `@ConditionalOnMissingBean` 模式进一步固化单 Provider 假设(plugin 之间也互斥);到 v1.5.27 §4.6 ToolExecutor + §5.5 默认 6 Provider stub + §5.3.1.0 7 Router 体系成熟,**单 Provider 假设与 Router 多 Provider 设计目标的张力被放大** —— 用户需要「同 Slot 多 Provider 共存 + 按 name 路由」的能力;**补丁** (1) **§5.5 改「多 Provider 模式」样板**:头部设计原则 blockquote 改写,说明 v1.5.28 起默认Provider 用 plain `@Bean(name = "<slot>Provider_<name>")` 而**不再用 `@ConditionalOnMissingBean`**;**所有 6 个默认 Provider stub 改写** —— Slot 1—7 全部 `@Bean(name = "...")` 显式 Bean 名,每段注释补「`name()` 必须唯一(§5.2 同名竞争)」;(2) **§5.5 头部新增「用户切换示例」blockquote** —— `application.yml` 写 `agent.<slot>.name: <provider-name>` 切换 Provider + 启动日志样例 `resolved N provider(s)` 列出全部 N;(3) **§5.5 9-Slot 总表加「Bean 名」列** —— 9 行「Bean 名(🆕 v1.5.28)」字段,如 `promptBuilderProvider_default` / `llmProviderProvider_anthropic` / `flowEngineProvider_linear` / `a2aTransportProvider_http-jsonrpc`,Story 实施者写 `@Bean(name = "...")` 直接抄;Slot 8 / Slot 9 标 🆕 v1.5.28 建议同步改名;(4) **§5.5「替代实现追加约定」段改写** —— 加 `OpenAiLlmProviderProvider` 完整样板(Bean 名 `llmProviderProvider_openai` + name "openai")+ 用户配置示例 + 启动日志样例(4 个 LlmProvider 共存);(5) **§5.6 Slot 9 `HttpJsonRpcA2aTransportProvider` stub 同步改多 Provider 模式** —— `@Bean(name = "a2aTransportProvider_http-jsonrpc")`;(6) **§5.4 plugin AutoConfiguration 编写约定改写** —— 双 `@ConditionalOnMissingBean` 模式 → 唯一 Bean 名约定:🆕 v1.5.28 起 plugin `@Bean` 必须显式 `name = "<slot>Provider_<pluginName>"`,禁止复用默认 Bean 名;🗑️ v1.5.25 双 `@ConditionalOnMissingBean` 模式加载顺序矩阵已废弃,但 `BeanDefinitionOverrideException` 应急路径不变;**效果** Story 实施者写 `@Bean` 时统一规范为 `@Bean(name = "<slot>Provider_<name>")`,无需 `@ConditionalOnMissingBean`;同 Slot 多 Provider 共存由 §5.2 SlotRouter 按 name 路由,`agent.<slot>.name` 改 yaml 即可切换 Provider;§5.3.1.0 Router 多 Provider 能力终于被框架自身利用,**List<P> size=N 实际生效**;5639 → 5709 行(+70));SKILL v1.0.16 → v1.0.17 / SOP v1.11 → v1.12 / prompts v1.0.10 → v1.0.11 同步;§11.6 历史 drift 修正(上轮 v1.3.15)不变))
**对应设计文档**: `dsh_agent_design.md` v1.5.41
**对应 SpecKit SOP**: `speckit_operator_prompt.md` v1.18
**对应 SKILL**: `lingshu-spec-driven-dev` v1.0.21
**对应 Prompt 速查**: `lingshu_spec_prompts.md` v1.0.16
