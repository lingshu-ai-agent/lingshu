# CLAUDE.md — 灵枢 LingShu 项目 Claude Code 引导

> 给 Claude Code 的项目级上下文。**每个新 Claude 会话 turn 1 自动加载。**
>
> **配套文档**(按需加载,不重复):
> - 设计文档:[`dsh_agent_design.md`](./dsh_agent_design.md) v1.5.30 / 6377 行 / 项目真理(v1.5.30 §5.6.3.1 新增 `HttpJsonRpcA2aTransport` concrete class + `HttpJsonRpcA2aTransportProvider` concrete Provider 完整示例 + §5.6.3.2 备选 `GrpcA2aTransport` / `InProcessA2aTransport` 「3 件套模式」扩展指南 — §5.6.3 L2395-2451 草图用匿名 inner class 形态写 `HttpJsonRpcA2aTransportAutoConfiguration` 的 `@Bean` 方法,但 §5.6.4 L2465 SPI 总表 Slot 9 行的「默认 Provider」字段已经写 `HttpJsonRpcA2aTransportProvider` —— 命名不一致;且 `HttpJsonRpcA2aTransport` 自身也只列名未给 class 定义,用户合理怀疑它是不是 `A2aTransport` 的子接口;**根因** v1.5.4 §5.6 引入 A2A 时,3 个 A2aTransport 变体是平级概念,设计意图是「`A2aTransport` SPI + 3 concrete class 平级实现」,但 §5.6.3 草图偷懒用匿名 inner class 写 Provider + 缺 concrete Transport class 定义;**补丁** (1) **§5.6.3.1 新增子节**(~190 行):设计澄清(`HttpJsonRpcA2aTransport` 不是 `A2aTransport` 子接口是 concrete class,GrpcA2aTransport / InProcessA2aTransport 同理,KISS + 3 变体数量小,扳机条件 HTTP 变体 ≥ 5 才加 HttpBasedA2aTransport 子接口) + `HttpJsonRpcA2aTransport` concrete class 完整定义(implements A2aTransport 5 方法:fetchCard / submit / get / cancel / subscribe polling 占位,用 JDK 17 内置 java.net.http.HttpClient 0 额外依赖) + `HttpJsonRpcA2aTransportProvider` concrete class(name="http-jsonrpc" + priority=10) + AutoConfiguration 改写(@Bean name 唯一);(2) **§5.6.3.2 新增子节**(~150 行):「3 件套模式」明确(concrete Transport + concrete Provider + AutoConfiguration)+ `GrpcA2aTransport` stub(grpc-java + protobuf,subscribe 用 grpc streaming 更高效,体积 +5MB R-13 mitigation (d) 镜像必执行)+ `InProcessA2aTransport` stub(同 JVM 直接方法调用,InProcessA2aRegistry 单例,0 额外依赖)+ application.yml 3 Provider 同存配置示例 + 启动日志样例 + 实施期检查清单 6 条 + 关键不变项(A2aTransport 5 方法契约不变 / A2aTransportRouter 行为不变 / RemoteAgentTool 内部完全不变 / §4.7 PermissionPolicy 等兼容);(3) §0 L1 + §13 同步;(4) CLAUDE.md v1.3.22 → v1.3.23;6049 → 6377 行(+328);v1.5.29 §6.5 (2.1) 新增 `McpServerConnection` 实现示例 (心跳保活 + 指数退避重连) + §6.5 (2) `McpTransport` 同步改写 — §6.5 (2) 假设 MCP server「连上就永远连着」,生产环境 MCP server 子进程可能被 OOM 杀、stdio 僵死、SSE 反向代理超时踢线 —— Agent 进程会因 MCP server 抖动连锁崩盘,且当前文档缺 single-source-of-truth 的心跳 / 重连机制样板,Story #009 实施者只能反推 §4.10.1 错误处理边界自己设计;**根因** §6.5 (2) 原版 `McpServerConnection.start(cfg)` 是一次性同步连接 stub,没引入状态机 / 心跳 / 重连概念;v1.5.x 早期把 MCP 当「远程 Tool 注册中心」轻量集成,没考虑 24×7 长生命周期运维需求;到 v1.5.28 多 Provider 模式 + 9 Slot 体系成熟,**MCP 的「长连接」属性被放大** —— 必须补完整的生命周期管理;**补丁** (1) **§6.5 (2.1) 新增子节**(~270 行):(a) **`McpServerConnection` 接口** `extends AutoCloseable`,8 个方法(name / state / lastHeartbeatAt / listTools / callTool / onStateChange / start / close),Javadoc 明确「非 CONNECTED 状态 callTool 直接返 error 不抛异常」「重连后 listTools 重新拉不复用旧 cache」;(b) **`ConnectionState` enum** —— `IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED` 6 态;(c) **`McpServerConnectionFactory`** —— 按 `cfg.transport()` 分派 stdio / SSE / streamable HTTP 三实现;(d) **`StdioMcpServerConnection` 完整实现**(~180 行)—— `AtomicReference<ConnectionState>` + `AtomicInteger reconnectAttempts` + `CopyOnWriteArrayList<Consumer<...>>` + daemon `ScheduledExecutorService`;`start()` 5 步(拉子进程 → initialize → initialized → tools/list → 切 CONNECTED + 启心跳);`probe()` 双探活(`process.isAlive()` + MCP `ping` 请求等回包,timeout=hbTimeoutMs);`scheduleReconnect()` 走 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 指数退避,失败**无限**重试;(e) **`SseMcpServerConnection` 差异说明段** —— 3 处差异(心跳 = `GET /health` 而非 process.isAlive;重连 = 重建 `HttpClient` 而非杀子进程;长连接 = `SseEventSource` 收 server push 触发 tools/listChanged 重拉);(f) **§6.5 (2) `McpTransport` 同步改写** —— `connect()` 不再直接调 `McpServerConnection.start(cfg)`,改 `McpServerConnectionFactory.create(cfg)` + `onStateChange(listener)` + 异步 `conn.start()`;新增 `onConnectionStateChange()` 私有方法处理 `CONNECTED → register / DISCONNECTED → unregister`;(g) **配置 `application.yml` 示例 + 启动日志样例** —— github server 被 OOM 杀后重连,日志展示 tools 从 7 → 8(MCP server 升级后新增 tool 自动可见);(2) **JDK 8 兼容** —— `AtomicReference` / `AtomicInteger` / `CopyOnWriteArrayList` + `Collections.emptyList()`,**不用** `List.of` / `var` / sealed / records,与 §0 L39 硬约束对齐;(3) **关键不变项** —— `McpToolAdapter` / `ToolExecutor.dispatch()` / `PermissionPolicy.check()` 完全不变,MCP 断流在 `ToolResult` 层只表现为「error 替代 success」,**不会绕过沙箱 / 权限 / checkpoint 任何一步**,与 §4.10.1 硬规则 2 完全兼容;(4) §13 加本条目 + §0 L1 标题版本号同步 `v1.5.28 → v1.5.29`;(5) CLAUDE.md 版本号同步 `1.3.21 → 1.3.22`;**纯文档改动**,代码逻辑零改动;5709 → 6049 行(+340);v1.5.28 §5.5 改「多 Provider 模式」样板 + §5.4 同步改「唯一 Bean 名约定」+ §5.6 Slot 9 stub 同步 — v1.5.27 §5.5 用 `@ConditionalOnMissingBean` 强制「部署期二选一」,同一 Slot 最多 1 个 `XxxProvider` Bean 注册到 Spring 容器 → 用户切换 Provider 必须改 classpath / exclude / 改 Bean 名;但 §5.3.1.0 `SlotRouter<P, T>` 父类**一直是多 Provider 友好** —— 构造器收 `List<P> providers`,启动期按 `name()` 收 `Map<String, P>`,`resolve(name, cfg)` 按 name 选 → List<P> **被设计为 size=N**,而 v1.5.27 `@ConditionalOnMissingBean` 把它阉割到 size=1;v1.5.28 取消 `@ConditionalOnMissingBean` 改 plain `@Bean(name = "<slot>Provider_<name>")` + 唯一 `name()` —— 多 Provider 模式全开,Router List<P> size=N 实际生效,`agent.<slot>.name` 按名路由无需 exclude / rebuild classpath;对应 §5.4 plugin AutoConfiguration 编写约定也同步改「唯一 Bean 名约定」;5639 → 5709 行(+70);纯文档改动,代码逻辑零改动;v1.5.27 §4.6 ToolExecutor 接口定义补全 — 标题「Tool 与 ToolExecutor」但 §4.6 缺 `ToolExecutor` 本体,Story 实施者只能从 §4.10.1 / §5.5 / §6.5 散落引用反推;**补丁** 在 §4.6 `Skill extends Tool` 之后 / `ToolExecutionContext` 之前插入 `ToolExecutor` 接口完整定义 —— 单方法 `dispatch(ToolCall call, ToolExecutionContext ctx) → ToolResult`;Javadoc 覆盖 (1) 调用契约 + ReAct Action 阶段每个 `LlmResponse.getToolCalls()` 元素**必须**走此方法(不得直调 `tool.execute()`);(2) §4.10.1 硬规则 2 强制要求 —— ToolExecutor 内部统一串入 5 步流水线 `PermissionPolicy.check() §4.7 → ToolRegistry.lookup(name) → TimeoutWrap → SandboxApply(fs / http / process) §4.7 → tool.execute() → Checkpoint`,任何一步绕过 = 沙箱 / 权限 / 取消 / 超时全失效,Spring AI `ChatClient.tools().call()` 自动执行**禁止**使用;(3) ToolExecutor 与 Tool 接口解耦 —— ToolExecutor 不 import Tool 内部细节,只看 `ToolCall(name + args JSON)` + `ToolExecutionContext`,Tool 实现可手写(§6.5 (1))/ MCP server 暴露(§6.5 (2))/ Spring AI `@Tool` 注解生成仅 schema(§6.5 (3))—— ToolExecutor 一视同仁;(4) Provider 可插拔 —— 默认 `DefaultToolExecutorProvider`(stub §5.5 L2121)同步串行 dispatch;替代实现 `ParallelToolExecutorProvider`(并发)/ `ObservabilityToolExecutorProvider`(metric / trace),`name()` 走该实现标识("default" / "parallel" / "observability"),`priority()` ≥ 10 胜过默认 `priority=0`,**禁止与默认 `name()` 冲突**;(5) `@throws` 完整标注 —— `PermissionDeniedException`(§4.7)/ `ToolNotFoundException` / `TimeoutException`(`callConfig.timeoutSeconds`)/ `CancellationException`(Ctrl+C / FlowEngine markDone / 超时联动)4 类异常;**效果** Story 实施者打开 §4.6 即可看到完整 Slot 5 接口契约,无需散落反推;§4.10.1 硬规则 2 引用 `ToolExecutor.dispatch()` 现在有 single-source-of-truth 锚点;5590 → 5639 行(+49);纯文档补全,代码逻辑零改动;v1.5.26 §5.3.1.0 补齐 `FlowEngineRouter` 第 7 个隐式 Router concrete stub(extends `SlotRouter<FlowEngineProvider, FlowEngine>`,super 传 `"FlowEngine"` + Logger,Javadoc 说明**不在 SlotResolver 字段里,由 AgentFactory 直接 `@Autowired`** + 默认 `LinearTurnEngineProvider`(§6.1 L2530)+ 替代 `GoogleAdkFlowEngineProvider`(§4.11.2)/ `AlibabaGraphFlowEngineProvider`(§4.11.3));§5.3.1 标题计数 8 → 9 Router + §5.3.1.0 标题 6 → 7 隐式 Router + §5.3.1.0 总表加「注入位置」新列 + Slot 编号对齐 §5.6.4 SPI 总表(1—9);§5.6.4 SPI 总表新增「Router stub 位置」列 9 行 —— 7 行指 §5.3.1.0 + 1 行指 §5.3.1.1 + 1 行指 §5.3.1.2,Slot 8 行强调 `FlowEngineRouter` 注入 `AgentFactory` 而**不在 SlotResolver**;根因 v1.5.18 SlotResolver 屏蔽 Router 数 6 → 8 时只补 SlotResolver 内部 2 Router(MemorySource + A2aTransport),`AgentFactory.flowRouter` 是 v1.5.18 之前就已存在的字段(L3676),v1.5.23 §5.3.1.0 新增 6 隐式 Router 时漏了 `FlowEngineRouter`;**效果** Story #001 实施者打开 IDE 时,AgentFactory 启动校验所需 3 Router(`PermissionPolicyRouter` + `ToolExecutorRouter` + `FlowEngineRouter`)全部有完整 stub;v1.5.25 §5.4 末新增「plugin AutoConfiguration 编写约定(双 `@ConditionalOnMissingBean` 模式,避免双胜出)」子段 — 明确 plugin 自己的 `XxxProvider` Bean **也必须**加 `@ConditionalOnMissingBean(XxxProvider.class)`,与 §5.5 默认 AutoConfiguration 对称;Spring `@ConditionalOnMissingBean` 检查整个 `BeanFactory` 而非"当前 `@Configuration` 类内其他 `@Bean` 方法",**跨 AutoConfiguration 类的 Bean 可见性**取决于加载顺序,plugin 漏标会导致启动期 `BeanDefinitionOverrideException`(Spring Boot 2.1+ `spring.main.allow-bean-definition-overriding=false` 默认);附 (1) plugin 样例代码 `RagAutoConfiguration` 标 `@ConditionalOnMissingBean`;(2) 4 行 4 列加载顺序矩阵(plugin A 先 / plugin B 先 / 默认 / 漏标 四种情形)—— 一眼看清"漏标 = 启动失败";(3) 应急路径(第一时间检查 plugin 的 `@Bean` 是否漏标,**不要**开 `spring.main.allow-bean-definition-overriding=true` 掩盖问题);(4) 区分"plugin 注册 `XxxProvider` Bean(需双标)" vs "plugin 注册 `Tool` / `MemorySource` / `SkillSource` 等非 Slot 类型 Bean(按需创建,无需 `@ConditionalOnMissingBean`)" + `LocalToolsAutoConfiguration` 多 Tool 样例;(5) 传递依赖规则 + cross-ref §5.5 / §5.3 / §5.2;Story #009 / #016 实施期及未来 plugin 贡献者直接按 §5.4 子段样板写 plugin 即可;纯文档补全,代码逻辑零改动;v1.5.24 §5.5「默认实现的注册约定」子节扩展 — 原版只给 `DefaultPromptBuilderProvider` 1 个 `@AutoConfiguration` 模板,§5.6.4 SPI 总表 9 Slot × 默认 Provider 中只 3/9 有完整 stub(`DefaultPromptBuilderProvider` Slot 1 / `LinearTurnEngineProvider` Slot 8 / `HttpJsonRpcA2aTransportProvider` Slot 9),剩 6/9 默认 Provider 只列名未给 AutoConfiguration 样板 —— 补齐 Slot 2—7 共 6 个新 AutoConfiguration stub(`TruncatingCompactorProvider` / `AnthropicLlmProviderFactory` / `StrictPermissionPolicyProvider` / `DefaultToolExecutorProvider` / `FileSessionStoreProvider` / `ProjectClaudeMdSourceProvider`),共享 `@AutoConfiguration` + `@Bean @ConditionalOnMissingBean(<X>Provider.class)` + 匿名 inner class 模板,`create()` body 抛 `UnsupportedOperationException` 留给对应 Story 实施期填;附 (1) 9 Slot × 默认 Provider ↔ `create()` 返回类型 ↔ Story ↔ stub 位置 总表(L2118-2128 9 行),§5.6.4 与 §5.5 双向 cross-ref;(2) 10 个替代实现(`OpenAi / Gemini / DeepSeek` 3 个 LlmProvider / `Memory / Redis / Jdbc` 3 个 SessionStore / `Identity / ProjectTree / Conversation` 3 个 MemorySource / `Summary` 1 个 Compactor)追加约定 —— 模式同默认实现,`name()` 严格命名空间隔离;纯文档补全,代码逻辑零改动;v1.5.23 §5.3.1.0 新增「6 个隐式 Router concrete 类」子节 — 补齐 v1.5.18 SlotResolver 屏蔽 8 Router 时,§5.3.1.1/§5.3.1.2 只给了 2 Router 显式 stub(`MemorySourceRouter` + `A2aTransportRouter`),剩 6 个(`PromptBuilderRouter` / `LlmProviderRouter` / `ToolExecutorRouter` / `PermissionPolicyRouter` / `SessionStoreRouter` / `CompactorRouter`)只列名未给 concrete 定义;单 Java 文件给 6 Router 完整 stub,共享 `package` + `import`,附 6 Router ↔ Slot ↔ `<P, T>` ↔ Story 总表 + 5 行边界约束表 + 与 MemorySourceRouter 关键差异说明 + 实施期顺序建议(Story #001 → #002 → #003 → #014 → #015);v1.5.22 §10.1 锁定合计数显式化 + CLAUDE.md §11.6 历史 drift 修正;v1.5.21 §5.7 新增「插件机制选型决策(SPI vs ClassLoader 隔离)」子节 — 一站式回答"为什么 LingShu 用 Spring Boot SPI 而不是 OSGi / Pf4j / 自定义 ClassLoader 隔离";含决策结论 + 8 维机制对比表 + LingShu 7 个决策点场景契合度 + 主动放弃能力表 + 重新评估触发条件与演进路径;v1.5.20 §5.3.1 新增子节,补齐 v1.5.18 引入的 2 个 Router(`MemorySourceRouter` + `A2aTransportRouter`)concrete 类 stub + 通用模板 + 边界行为表;v1.5.19 §6.1 LinearTurnEngine + Provider 加类级 Javadoc;v1.5.18 §5.3 SlotResolver 屏蔽 Router 数 6 → 8;v1.5.17 §5.1 typed-Provider 列表补 `A2aTransportProvider` 行;v1.5.16 §5.1 L1463 orphan fence opener 误吞修复;v1.5.15 §4.11 Java 代码块补 closing fence;v1.5.14 §4.5.1 [TOOL SCHEMAS] 措辞修订 + 明确分层;v1.5.13 PromptBuilder API 对齐 .tools 字段;v1.5.12 删 stale Slot N;v1.5.11 Slot 命名对齐;v1.5.10 修 §0.1;v1.5.9 增 §4.5.1 [TOOL SCHEMAS] 段)
> - SpecKit SOP:[`speckit_operator_prompt.md`](./speckit_operator_prompt.md) v1.14 / 859 行 / 操作手册(R-13 mitigation (d) 镜像 + 依赖同步到 dsh v1.5.30 §5.6.3.1 HttpJsonRpcA2aTransport concrete class + §5.6.3.2 备选 3 件套模式扩展指南)
> - Prompt 速查:[`lingshu_spec_prompts.md`](./lingshu_spec_prompts.md) v1.0.13 / 368 行 / 新窗口 Prompt 模板(同步 dsh v1.5.30 §5.6.3.1 HttpJsonRpcA2aTransport concrete class + §5.6.3.2 备选 3 件套模式扩展指南)
> - SKILL:`~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` v1.0.19 / 192 行 / 自动触发(R-13 dep-tree 自查链路已纳入 + 同步 dsh v1.5.30 §5.6.3.1 HttpJsonRpcA2aTransport concrete class + §5.6.3.2 备选 3 件套模式扩展指南)

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

**Last updated**: 2026-09-16
**Version**: 1.3.23(依赖同步 — dsh v1.5.29 → v1.5.30(§5.6.3.1 新增 `HttpJsonRpcA2aTransport` concrete class + `HttpJsonRpcA2aTransportProvider` concrete Provider 完整示例 + §5.6.3.2 备选 `GrpcA2aTransport` / `InProcessA2aTransport` 「3 件套模式」扩展指南 — 澄清 `HttpJsonRpcA2aTransport` 是 concrete class 不是 `A2aTransport` 子接口 + 命名不一致修复(匿名 inner class → named class)+ Grpc/InProcess 未来怎么加的 3 件套样板 + application.yml 配置示例 + 实施期检查清单 6 条;6049 → 6377 行(+328);SKILL v1.0.18 → v1.0.19 / SOP v1.13 → v1.14 / prompts v1.0.12 → v1.0.13 同步;v1.5.29 §6.5 (2.1) 新增 `McpServerConnection` 实现示例 (心跳保活 + 指数退避重连) + §6.5 (2) `McpTransport` 同步改写 — §6.5 (2) 假设 MCP server「连上就永远连着」,生产环境 MCP server 子进程可能被 OOM 杀、stdio 僵死、SSE 反向代理超时踢线 —— Agent 进程会因 MCP server 抖动连锁崩盘,且当前文档缺 single-source-of-truth 的心跳 / 重连机制样板,Story #009 实施者只能反推 §4.10.1 错误处理边界自己设计;**根因** §6.5 (2) 原版 `McpServerConnection.start(cfg)` 是一次性同步连接 stub,没引入状态机 / 心跳 / 重连概念;v1.5.x 早期把 MCP 当「远程 Tool 注册中心」轻量集成,没考虑 24×7 长生命周期运维需求;到 v1.5.28 多 Provider 模式 + 9 Slot 体系成熟,**MCP 的「长连接」属性被放大** —— 必须补完整的生命周期管理;**补丁** (1) **§6.5 (2.1) 新增子节**(~270 行):`McpServerConnection` 接口(`extends AutoCloseable`,8 个方法)+ `ConnectionState` enum 6 态 + `McpServerConnectionFactory` 按 transport 分派 + `StdioMcpServerConnection` 完整实现(`AtomicReference` + daemon `ScheduledExecutorService` + 5 步 start + 双探活 probe + 指数退避 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` + 无限重试) + `SseMcpServerConnection` 差异段 + `McpTransport` 同步改写(`McpServerConnectionFactory.create(cfg)` + `onStateChange(listener)` + `onConnectionStateChange()` 私有方法)+ `application.yml` 配置示例 + 启动日志样例;(2) **JDK 8 兼容** —— `AtomicReference` / `Collections.emptyList()`,不用 `List.of` / `var` / sealed;(3) **关键不变项** —— `McpToolAdapter` / `ToolExecutor.dispatch()` / `PermissionPolicy.check()` 完全不变,MCP 断流在 `ToolResult` 层只表现为「error 替代 success」,**不会绕过沙箱 / 权限 / checkpoint 任何一步**,与 §4.10.1 硬规则 2 完全兼容;5709 → 6049 行(+340);SKILL v1.0.17 → v1.0.18 / SOP v1.12 → v1.13 / prompts v1.0.11 → v1.0.12 同步;v1.5.28 §5.5 改「多 Provider 模式」样板 + §5.4 同步改「唯一 Bean 名约定」+ §5.6 Slot 9 `HttpJsonRpcA2aTransportProvider` stub 同步 — **问题** v1.5.27 §5.5 用 `@ConditionalOnMissingBean` 强制「部署期二选一」,同一 Slot 最多 1 个 `XxxProvider` Bean 注册到 Spring 容器 → 用户切换 Provider 必须改 classpath / exclude / 改 Bean 名;但 §5.3.1.0 `SlotRouter<P, T>` 父类**一直是多 Provider 友好** —— 构造器收 `List<P> providers`,启动期按 `name()` 收 `Map<String, P>`,`resolve(name, cfg)` 按 name 选 → List<P> **被设计为 size=N**,而 v1.5.27 `@ConditionalOnMissingBean` 把它阉割到 size=1,**多 Provider 能力框架自身不用**;**根因** v1.5.24 §5.5 引入 6 默认 Provider stub 时直接复用 v1.5.0 `DefaultPromptBuilderProvider` 模板的 `@ConditionalOnMissingBean`,当时设计意图是「防止用户覆盖默认」,但代价是阉割 §5.3.1.0 Router 的多 Provider 能力;v1.5.25 §5.4 双 `@ConditionalOnMissingBean` 模式进一步固化单 Provider 假设(plugin 之间也互斥);到 v1.5.27 §4.6 ToolExecutor + §5.5 默认 6 Provider stub + §5.3.1.0 7 Router 体系成熟,**单 Provider 假设与 Router 多 Provider 设计目标的张力被放大** —— 用户需要「同 Slot 多 Provider 共存 + 按 name 路由」的能力;**补丁** (1) **§5.5 改「多 Provider 模式」样板**:头部设计原则 blockquote 改写,说明 v1.5.28 起默认Provider 用 plain `@Bean(name = "<slot>Provider_<name>")` 而**不再用 `@ConditionalOnMissingBean`**;**所有 6 个默认 Provider stub 改写** —— Slot 1—7 全部 `@Bean(name = "...")` 显式 Bean 名,每段注释补「`name()` 必须唯一(§5.2 同名竞争)」;(2) **§5.5 头部新增「用户切换示例」blockquote** —— `application.yml` 写 `agent.<slot>.name: <provider-name>` 切换 Provider + 启动日志样例 `resolved N provider(s)` 列出全部 N;(3) **§5.5 9-Slot 总表加「Bean 名」列** —— 9 行「Bean 名(🆕 v1.5.28)」字段,如 `promptBuilderProvider_default` / `llmProviderProvider_anthropic` / `flowEngineProvider_linear` / `a2aTransportProvider_http-jsonrpc`,Story 实施者写 `@Bean(name = "...")` 直接抄;Slot 8 / Slot 9 标 🆕 v1.5.28 建议同步改名;(4) **§5.5「替代实现追加约定」段改写** —— 加 `OpenAiLlmProviderProvider` 完整样板(Bean 名 `llmProviderProvider_openai` + name "openai")+ 用户配置示例 + 启动日志样例(4 个 LlmProvider 共存);(5) **§5.6 Slot 9 `HttpJsonRpcA2aTransportProvider` stub 同步改多 Provider 模式** —— `@Bean(name = "a2aTransportProvider_http-jsonrpc")`;(6) **§5.4 plugin AutoConfiguration 编写约定改写** —— 双 `@ConditionalOnMissingBean` 模式 → 唯一 Bean 名约定:🆕 v1.5.28 起 plugin `@Bean` 必须显式 `name = "<slot>Provider_<pluginName>"`,禁止复用默认 Bean 名;🗑️ v1.5.25 双 `@ConditionalOnMissingBean` 模式加载顺序矩阵已废弃,但 `BeanDefinitionOverrideException` 应急路径不变;**效果** Story 实施者写 `@Bean` 时统一规范为 `@Bean(name = "<slot>Provider_<name>")`,无需 `@ConditionalOnMissingBean`;同 Slot 多 Provider 共存由 §5.2 SlotRouter 按 name 路由,`agent.<slot>.name` 改 yaml 即可切换 Provider;§5.3.1.0 Router 多 Provider 能力终于被框架自身利用,**List<P> size=N 实际生效**;5639 → 5709 行(+70));SKILL v1.0.16 → v1.0.17 / SOP v1.11 → v1.12 / prompts v1.0.10 → v1.0.11 同步;§11.6 历史 drift 修正(上轮 v1.3.15)不变)
**对应设计文档**: `dsh_agent_design.md` v1.5.30
**对应 SpecKit SOP**: `speckit_operator_prompt.md` v1.14
**对应 SKILL**: `lingshu-spec-driven-dev` v1.0.19
**对应 Prompt 速查**: `lingshu_spec_prompts.md` v1.0.13
