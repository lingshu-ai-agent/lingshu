# Story #023 `delegate-sub-agent` — Spec

> **Status**: Draft 2026-09-24
> **Source**: dsh v1.5.40 §6.6 L5030-5113(`SubAgentType` enum + `DelegateTool` + `loadConfigs()` + `@PostConstruct validate()`)+ §6.6.1 L5116-5149(Sub-agent 字段级继承 `inheritFromParent(parent, child, type)`) + §4.10.1 硬规则 2 + §15 域字母编码约定 + constitution v1.0
> **前置依赖**:`AgentFactory.create(AgentConfig)` 已落(`#001`)+ `Agent.runBlocking(String)` 已落(`#001`)+ `AgentConfig.Delegate` + `AgentConfig.TypeConfig` 嵌套类型已就位(`#001` baseline,8 字段雏形)+ `Session` 已就位(`#001`)+ `Tool` interface 4 方法契约已稳(`#003`)+ `ToolRegistry.register(Tool)` 已稳(`#019`/`#020a`/`#021b`)+ `InitializingBean` 模式已对齐(`e13e6a5` fix)+ `LocalToolsAutoConfiguration` 样板已就位(`#019`)。**依赖 `#009e` RemoteAgentTool wiring 修复已完成(避免内部 patch 绕路)**

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.40 §6.6 L5030-5113「DelegateTool —— 枚举 + yml 注册」 + §6.6.1 L5116-5149「Sub-agent 继承策略(v1.5.5 升级)」
- **对应 AC**: dsh §0.4 AC-04(默认 ReAct 闭环中 LLM 看到 tool schema,调 tool,ToolExecutor 处理)— Sub-agent 是 LLM 视角下可调度的 meta-Tool,**LLM 通过 `Task` ToolName + input.subagent_type + input.prompt 三字段调度子 Agent**
- **对应风险**: R-13(`spring-ai-bom` 误用 / binary 膨胀)Mitigation (d) 第 8 次自查 + Story #023 不引新 Maven 依赖
- **涉及 ErrorCode**: **LINGS-D01 `DELEGATE_CONFIG_INVALID`**(dsh §15 域字母 D=Delegate 子 Agent 域 第 1 号;#023 新增)

---

## 1. WHY(为什么做这个 Story)

dsh §6.6 L5030-5113 字面给出 `DelegateTool` 完整骨架 + §6.6.1 L5116-5149 字段级继承策略(`inheritFromParent`),**目前 0 源码**:

- **`SubAgentType` enum 0 实现** — dsh §6.6 L5033-5052 给完整 `EXPLORE / ENGINEER / REVIEWER` 3 值 enum + `configKey` / `promptFile` + `fromKey(String)` / `allKeys()` + `key()` / `promptFile()` 字面落地,**0 源码**。
- **`DelegateTool implements Tool` 0 实现** — dsh §6.6 L5054-5113 给 `@Component DelegateTool(AgentFactory, DelegateProps)` 完整 ctor + `loadConfigs(props)` 内部 `Map<SubAgentType, AgentConfig>` 装载 + `@PostConstruct validate()` enum-key/yaml-key 一致性校验 + `name()="Task"` + `execute(call, ctx)` 内部 `SubAgentType.fromKey(input.subagent_type)` + `agentFactory.create(childConfig, ctx.session().fork(...))` + `AgentCollectors.collectBlocking(child.run(prompt), timeout)` + `ToolResult.success(call.id, result.finalText)` ~ 60 行,**0 源码**。
- **`AgentConfig.Delegate` + `AgentConfig.TypeConfig` 已就位** — `@codebase` `AgentConfig.java` L104-116 已存在 `Delegate(promptsDir, types: Map<String, TypeConfig>)` + `TypeConfig(llm, tools, sandbox, systemPromptFile)` 两个 `@Value` 嵌套类型(Story #001 baseline,8 字段雏形)。**#023 直接复用,0 改动**。
- **`inheritFromParent` 字段级合并 0 实现** — dsh §6.6.1 L5131-5146 给 `AgentConfig.toBuilder().identity(...).instructions(...).memory(...).build()` 链式合并 + 默认回退 `Identity.defaults()` / `Instructions.empty()` / `Memory.defaults()`,**0 源码**。当前 `AgentConfig` 是 `@Value`(Lombok 不可变,**未启用** `@Builder(toBuilder=true)`),`toBuilder()` 不可用 —— #023 写 `SubAgentInheritance.inheritFromParent(parent, child, type)` 手工拼接 28 字段 `new AgentConfig(...)`(走 `@Value` 自动生成的全参构造器)。
- **`AgentCollectors` 0 实现** — dsh §6.6 L5107 引用 `AgentCollectors.collectBlocking(child.run(prompt), timeout)`,但 `@codebase` 0 匹配(`DefaultAgent.runBlocking(String)` 已存在,直接复用 + `done.await(timeout, SECONDS)` 由 `child.config().getTurnTimeoutSeconds()` 自动走通,**不**新建 `AgentCollectors` 类 —— dsh 引用是 design-time 简化表达,实现侧走 `Agent.runBlocking` 已足够)。

**Story #023 业务价值**:

- 用户在 `application.yml` 写 `agent.delegate.types: { explore: {...}, engineer: {...}, reviewer: {...} }`,启动后 LLM 可通过 `Task(subagent_type="explore", prompt="...")` 调用子 Agent
- 父 Agent config(Identity / Instructions / Memory / Skills / Sandbox)自动继承给子 Agent —— **无需**每个 sub-agent 重复声明 `./CLAUDE.md` 或 system prompt(dsh §6.6.1 核心价值)
- 子 Agent 走 `AgentFactory.create(childConfig)` 独立 turn,失败 / 取消信号通过 `ToolExecutionContext.cancellation()` 自动级联(子 Agent turn 超时由 `turnTimeoutSeconds` 控制)
- 与 `#019` 4 个 hand-written tool + `#022` @AgentTool 反射 tool 并列,LLM 视角下都是 `Tool.name()` 注册项,**统一调度路径**

**关键不变项**:

- `AgentConfig` 嵌套 `Delegate` + `TypeConfig` 8 字段**0 改动**(已存在,直接复用)
- `AgentFactory.create(AgentConfig)` 单参入口**0 改动**(子 Agent 走 fresh session,符合 dsh §7.1 不变项)
- `Agent.runBlocking(String)` 同步收集模式**0 改动**(替代 `AgentCollectors.collectBlocking`)
- `Tool` interface 4 方法 + `ToolRegistry.register(Tool)` SPI **0 改动**
- `ToolExecutor.dispatch()` 5 步流水线 **0 改动**(`DelegateTool.execute()` 在第 5 步被调,不绕过任何一步)
- 0 新 Maven 依赖 —— `SubAgentType` 用 `Collections.emptySet()` / `EnumMap` / `Arrays.stream` 走 JDK 8 内置;`DelegateTool` 用 Jackson `ObjectNode` / `JsonNode` 已锁;`SubAgentInheritance` 用 Lombok `@Value` 已锁

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 AI 工程师(Alice 类)** | 在 `application.yml` 配 `delegate.types.explore.llm.provider: claude-haiku` + `delegate.types.explore.system-prompt-file: ./prompts/explore.md`,LLM 自动获得 `Task` tool + 3 子 Agent 调度能力,**无需**写 Java 代码 |
| **框架贡献者 / 插件作者(Bob 类)** | 写自定义 `SubAgentType` 时,扩展 `DelegateAutoConfiguration.delegateTool(AgentFactory, AgentConfig.Delegate)` 即可;不需要碰 9 Router / AgentFactory / Agent 三件套(dsh §7.1.4 扩展边界) |
| **运维稳定性关注者(Eve 类)** | 子 Agent turn 超时由 `turnTimeoutSeconds` 控制(`DefaultAgent.runBlocking` 内 `done.await(timeout, SECONDS)` 强制 200ms 余量);父 Agent turn 取消通过 `ToolExecutionContext.cancellation()` 级联(子 Agent 内 FlowEngine 复用同一 `CancellationToken.fire()` 路径);**不**绕过 `PermissionPolicy.check()` / SandboxApply / Checkpoint 任一步(§4.10.1 硬规则 2) |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `SubAgentType` enum 不变量 + `SubAgentInheritance` 字段级合并矩阵;L2 slice 用真实 `AgentFactory` + `DefaultToolRegistry` + `DelegateTool` 跑 execute → factory.create → child.runBlocking 全链路;L3 集成测 LLM 视角下 `Task` tool schema 完整 + 3 种 sub-agent_type 调度 + LINGS-D01 fail-fast;`mvn dependency:tree` 0 增量(R-13 mitigation (d) 第 8 次验证) |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**:

- 用户在 `application.yml` 配 `agent.delegate.types.<key>`(key ∈ `explore / engineer / reviewer`,枚举强约束),启动后 `DelegateAutoConfiguration` 在 `afterPropertiesSet()` 阶段:
  1. 读 `agent.delegate.prompts-dir`(默认 `./prompts/subagents/`)
  2. 校验 `delegate.types` 包含全部 3 个 `SubAgentType.configKey`(缺一抛 `LINGS-D01`,JVM exit 1)
  3. 构造 `DelegateTool` 并 `toolRegistry.register(delegateTool)` —— LLM 看到 tool name `"Task"` + inputSchema `{ subagent_type: string enum, prompt: string }`
- LLM 在 ReAct Action 阶段发 `tool_use(name="Task", input={"subagent_type": "explore", "prompt": "搜索代码库中..."})` → `ToolExecutor.dispatch()` → 5 步流水线 → `DelegateTool.execute()` → `SubAgentType.fromKey("explore")` → `agentFactory.create(childConfig)`(fresh session,符合 §7.1 不变项)+ `child.runBlocking(prompt)` → `ToolResult.success(call.id, childResult.getFinalText())` → LLM 看到子 Agent 输出
- 子 Agent config 继承规则(dsh §6.6.1 完全替换语义):
  - `child.identity` 显式指定 → 完全替换 + **不再**附加 `(Sub-agent: explore)` 后缀
  - `child.identity` 未指定 + `parent.identity` 存在 → 用 `parent.identity.toBuilder().name(parent.identity.name + " (Sub-agent: explore)").build()`,其余字段 `role / traits / tone / language / avatar` 完全沿用 parent
  - `child.identity` 未指定 + `parent.identity` 也未指定 → 回退 `Identity.defaults()`(`name="lingShu-agent"`,避免 NPE)
  - `instructions` / `memory` 同款"完全替换"语义,未指定时沿用 parent,parent 也未指定时回退 `Instructions.empty()` / `Memory.defaults()`

**新配置参数**:

`AgentConfig.Delegate` 已存在,字段语义在 #023 里**首次被消费**:

| 字段 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `promptsDir` | 否 | `./prompts/subagents/` | 子 Agent system prompt md 文件所在目录;`DelegateTool.loadTypeConfigs()` 按 `promptsDir + "/" + type.promptFile()` 拼绝对路径 |
| `types: Map<String, TypeConfig>` | 是 | — | key 必须是 `SubAgentType.configKey` 枚举全集(`explore / engineer / reviewer`),缺一抛 `LINGS-D01` |
| `TypeConfig.llm` | 否 | 沿用 parent | 子 Agent 用的 LLM Provider / model,缺省时走 parent LLM |
| `TypeConfig.tools` | 否 | `[]` | 子 Agent 可见的 tool 名列表(`toolRegistry.findByName(name)` 过滤);空 = 子 Agent 不调任何 tool |
| `TypeConfig.sandbox` | 否 | 沿用 parent | 子 Agent 沙箱策略;缺省时走 parent sandbox |
| `TypeConfig.systemPromptFile` | 否 | `promptsDir/<subagent_type>.md` | 子 Agent system prompt 文件;文件不存在时 `DelegateTool.loadTypeConfigs()` 抛 `LINGS-D01` |

**用户会看到的错误码**:

| ErrorCode | 抛出位置 | 触发条件 | 用户响应 |
|---|---|---|---|
| **LINGS-D01 `DELEGATE_CONFIG_INVALID`** | `DelegateTool.loadConfigs()` 内部或 `validate()` post-construct | (a) `delegate.types` 缺 `SubAgentType.configKey` 全集任一项;(c) `delegate.types.<key>.systemPromptFile` 路径不存在 / 不可读 | 检查 `agent.delegate.types` 是否包含全部 3 个 enum key;检查 `promptsDir/<type>.md` 文件是否存在 |

**关键不变量**(不变项):

- `AgentConfig` 嵌套 `Delegate` + `TypeConfig` 8 字段**0 改动**
- `AgentFactory.create(AgentConfig)` 单参入口**0 改动**(`DelegateTool.execute()` 走 fresh session 路径,符合 §7.1 不变项)
- `Agent.runBlocking(String)` + `DefaultAgent` 模板**0 改动**
- `Tool` interface 4 方法 + `ToolRegistry.register(Tool)` SPI **0 改动**
- `ToolExecutor.dispatch()` 5 步流水线 **0 改动**
- dsh §4.10.1 硬规则 1(ReAct 自实现)+ 硬规则 2(Spring AI 不走 `ChatClient.prompt().call()` 自动工具执行)+ 硬规则 3(Provider 显式映射)全部不变
- LINGS-D01 是**新增** ErrorCode;constitution §4 域字母表新增 `D = Delegate`(8 域变 9 域)
- LINGS-C01—C99 / LINGS-S01—S99 / LINGS-T01—T99 等已用编号全部不动
- 0 新 Maven 依赖

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — 完整 yml 配置 → DelegateTool 注册 + LLM 视角可见

**Given** `application.yml` 含完整 3 个 sub-agent type 配置:
```yaml
agent:
  delegate:
    prompts-dir: ./prompts/subagents/
    types:
      explore:
        llm: { provider: anthropic, model: claude-3-5-haiku-latest }
        tools: [Read]
        sandbox: { policy: strict, runtime: chroot, working-directory: /tmp, command-whitelist: [ls, cat] }
        system-prompt-file: ./prompts/subagents/explore.md
      engineer:
        llm: { provider: anthropic, model: claude-3-5-sonnet-latest }
        tools: [Read, Write, Edit, Bash]
        sandbox: { policy: strict, runtime: chroot, working-directory: /tmp, command-whitelist: [ls, cat, npm] }
        system-prompt-file: ./prompts/subagents/engineer.md
      reviewer:
        llm: { provider: anthropic, model: claude-3-5-sonnet-latest }
        tools: [Read]
        sandbox: { policy: strict, runtime: chroot, working-directory: /tmp, command-whitelist: [ls, cat] }
        system-prompt-file: ./prompts/subagents/reviewer.md
```
**When** Spring 容器刷新完毕 + `DelegateAutoConfiguration.afterPropertiesSet()` 触发
**Then** `ToolRegistry.lookup("Task") != null` 且 `lookup("Task") instanceof DelegateTool` 且 `ToolRegistry.names()` 集合包含 `"Task"`
**断言方式**:L2 Slice 测试用 `AnnotationConfigApplicationContext` + 手装 `DelegateAutoConfiguration` + `DefaultToolRegistry` 跑 register path

### AC-NN-2 — DelegateTool.execute() happy path(完整链路)

**Given** AC-NN-1 场景,`DelegateTool` 已注册,`agentFactory.create(childConfig)` 真实返回 child Agent,且 mock `LlmProvider` 返回固定文本 `"explored-result"`
**When** `ToolExecutor.dispatch(new ToolCall("id-1", "Task", objectMapper.readTree("{\"subagent_type\":\"explore\",\"prompt\":\"find TODO\"}")), ctx)` 被调用
**Then** 返回 `ToolResult.success("id-1", "explored-result")`,且子 Agent 真实被 `agentFactory.create(childConfig)` 创建 1 次 + `child.runBlocking("find TODO")` 被调 1 次 + ctx.session().id() 不传到 child session(child 是 fresh session)
**断言方式**:L2 Slice 测试用 mock `AgentFactory` + spy `DefaultAgent` + mock `LlmProvider` 验证 child create 1 次 + runBlocking 1 次 + input prompt 完整传到

### AC-NN-3 — 字段级继承 identity(完全替换 + 后缀追加)

**Given** parent AgentConfig 含 `identity.name="main-agent"`;子 AgentConfig.identity = null;type=EXPLORE
**When** `SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.EXPLORE)` 返回 inherited config
**Then** `inherited.identity.name == "main-agent (Sub-agent: explore)"` + `inherited.identity.role / traits / tone / language / avatar` 完全沿用 parent(字段级 deep-copy)
**断言方式**:L1 Unit 测试覆盖 6 个 identity 字段 + 3 种枚举类型(EXPLORE / ENGINEER / REVIEWER 各 1 case)+ child-identity 显式指定时**不**附加后缀

### AC-NN-4 — 字段级继承 instructions + memory(完全替换语义)

**Given** parent AgentConfig 含 `instructions.file=Paths.get("./prompts/system.md")` + `memory.claudeMd.project=Paths.get("./CLAUDE.md")`;子 AgentConfig 不指定 instructions / memory
**When** `SubAgentInheritance.inheritFromParent(parent, child, type)` 返回 inherited config
**Then** `inherited.instructions.file` 与 parent 相等 + `inherited.memory.claudeMd.project` 与 parent 相等(完全沿用,不是 deep-merge)
**断言方式**:L1 Unit 测试覆盖 instructions 4 字段(file / inline / templateEngine / variables)+ memory 2 字段(claudeMd / extras)+ child 显式指定时完全替换

### AC-NN-5 — 默认回退:parent 也无 identity 时回退 Identity.defaults()

**Given** parent AgentConfig.identity = null(默认);child AgentConfig.identity = null;type=ENGINEER
**When** `SubAgentInheritance.inheritFromParent(parent, child, SubAgentType.ENGINEER)` 返回 inherited config
**Then** `inherited.identity` 不为 null + `inherited.identity.name == "lingShu-agent"`(`Identity.defaults()` 默认值),**不**抛 NPE
**断言方式**:L1 Unit 测试覆盖 3 种默认回退场景:identity / instructions / memory 三件套 parent 均为 null 时,回退 `Identity.defaults()` + `Instructions.empty()` + `Memory.defaults()`

### AC-NN-6 — LINGS-D01 fail-fast(types 缺关键 key)

**Given** `application.yml` 中 `delegate.types` 只配了 `explore` 和 `engineer`,缺 `reviewer`
**When** Spring 启动 + `DelegateAutoConfiguration.afterPropertiesSet()` 触发
**Then** 抛 `IllegalStateException` 且 message 含 `"LINGS-D01"` + `"missing subagent_type: [reviewer]"`,JVM 启动失败 exit code 1
**断言方式**:L2 Slice 测试故意构造 `delegate.types` 缺 key,断言 `IllegalStateException` message 含 LINGS-D01 + missing key 列表

### AC-NN-7 — R-13 mitigation (d) 依赖零增量

**Given** Story #023 引入 `SubAgentType` + `DelegateTool` + `SubAgentInheritance` + `DelegateErrorCodes` + `DelegateAutoConfiguration` 5 文件
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#022` pre-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;关键是 `spring-ai-bom:1.0.0-M6` 仍为 transitive,**不**引入 `spring-ai-spring-boot-starter` 或 banned 列表任一条
**断言方式**:对照 `specs/022-spring-ai-annotation-tool/` PR body 末尾的 `### R-13 dependency:tree 自查` 节

### AC-NN-deps-1(R-13 mitigation (d) — 强制)

**Given** 当前 Story 引入 / 修改依赖
**When** 跑 `mvn dependency:tree -pl lingshu-core -Dverbose`
**Then** 输出中**必须不包含** `banned-dependencies` 列表(见 dsh §17 R-13 (b))的任何条目,关键子树(>= 3 层的 `spring-ai-*` / `com.knuddels:*` / `io.netty:*` / `com.fasterxml.jackson.*` 版本冲突对)贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节

### AC-NN-deps-2(R-13 mitigation (d) — 强制)

**Given** Story #023 引入 Lombok + Jackson + Spring core 横向依赖
**When** `mvn -pl lingshu-core verify` 跑 enforcer
**Then** `banned-dependencies` 规则**必须在 build 阶段 fail**(若依赖没碰,run 配置 `enforcer.skip=true` 显式跳过 + 在 PR body 说明)

**banned-dependencies 列表**(同 #022):
- `org.springframework.ai:spring-ai-spring-boot-starter`(全家桶)
- `org.springframework.ai:spring-ai-vector-store-*`
- `org.springframework.ai:spring-ai-etl-*`
- `com.knuddels:jtokkit`
- `io.netty:netty-all` 版本冲突对
- `com.fasterxml.jackson.*` 主版本号不一致

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| 修改 `AgentConfig` 加 `@Builder(toBuilder=true)` | dsh §6.6.1 L5131 `child.toBuilder()` 引用是 design-time 表达,实际 `AgentConfig` 是 `@Value`(Lombok 不可变,`@Builder` 未启用);`#023` 走 `SubAgentInheritance.inheritFromParent(...)` 手工拼接 28 字段,**0 改动** `AgentConfig` |
| 修改 `AgentFactory` 加 `create(AgentConfig, Session)` 重载 | dsh §6.6 L5105 引用 `factory.create(childConfig, ctx.session().fork(...))` 是 design-time 表达,实际 `AgentFactory.create(AgentConfig)` 单参入口;`#023` 走 `agentFactory.create(childConfig)` fresh session 路径,符合 dsh §7.1 不变项「单 turn 单 Agent」,**0 改动** `AgentFactory` |
| 新建 `AgentCollectors` 类 | dsh §6.6 L5107 引用是 design-time 表达,`#023` 直接复用 `Agent.runBlocking(String)`(同步收集 + `done.await(turnTimeoutSeconds, SECONDS)` 强制超时),**0 新类** |
| `SubAgentType` 允许用户自定义 | dsh §6.6 enum 是闭合设计意图(3 内置 + 启动期一致性校验),用户自定义会破坏 `validate()` 强约束;OQ-Future — 用户想要自定义 type 时开 RFC,改 enum + prompts-dir |
| `DelegateTool` 接受 user-defined `SubAgentType` via `name()` | dsh §6.6 `SubAgentType.fromKey(...)` throw `IllegalArgumentException` if unknown — 严格枚举语义;用户拼错 subagent_type 走 LLM error message 重试 |
| `SubAgentInheritance` 字段级 deep-merge | dsh §6.6.1 L5149 明确「**合并是"完全替换"语义**,不是字段级 deep-merge」;`#023` 完全替换,简化心智,需要精细控制的用户在 TypeConfig 里完整声明 |
| `DelegateTool` 子 Agent turn 内 Skill `/xxx` 拦截 | 子 Agent 复用 `Agent.runBlocking` 自动走 `DefaultAgent.continueWithUserMessageBlocking`(#020c 路径),**不**新增子 Agent Skill 拦截 |
| `DelegateTool` 子 Agent ctx propagation | dsh §6.6 line 5105 `ctx.session().fork()` 是 design-time 表达;`#023` 子 Agent 走 fresh session + fresh ctx,**不**传父 ctx 任何字段(sandbox / tools / skills 由 childConfig 自身决定) |
| 启动期对 `SubAgentType` key 唯一性预校验 | dsh §6.6 `validate()` 校验 enum-key == yaml-key 集合相等性,`#023` 复用同款模式 |
| `DelegateAutoConfiguration` 自动监听 Bean 生命周期(unregister on destroy) | `#022` 同款判断 —— `@Component` Bean 与 JVM 同生命周期,无需 unregister;**故意不引入 SmartLifecycle** |
| Agent-level 功能开关(`agent.delegate.enabled: true/false`) | 默认全开;`agent.delegate` 配置存在即启用,缺失即不创建 `DelegateTool`(`DelegateAutoConfiguration` 用 `AgentConfig.delegate != null` 守卫);无需额外 enabled flag |
| 子 Agent turn 内的 metrics / trace | 由 `Agent.runBlocking` 内嵌 OTel 路径统一处理(§14.1 N1 Story),`#023` 不重复实现 |
| 测试覆盖用 SpringBootTest(`@SpringBootTest`) 启动整个上下文 | dsh §4.10.1 硬规则 2 + `#007` 经验 —— 测试用裸 `AnnotationConfigApplicationContext` + 手装 `DelegateAutoConfiguration` + `DefaultToolRegistry` + mock `AgentFactory` 即可,规避 Mockito 5.x + JDK 23 inline-mock 兼容 issue |

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#001` zero-config-bootstrap — `AgentFactory.create(AgentConfig)` + `Agent.runBlocking(String)` + `AgentConfig.Delegate` + `AgentConfig.TypeConfig` 8 字段雏形 + `Session` interface + `Tool` interface 4 方法契约
  - `#003` spi-slot-router — `ToolRegistry.register(Tool)` SPI + `Tool` interface 契约
  - `#009e` a2a-remote-tool-wiring — `RemoteAgentTool` wiring 修复已完成(`DelegateTool` 不绕路,直接 `ToolRegistry.register`)
  - `#019` built-in-tools — `LocalToolsAutoConfiguration` `@Configuration implements InitializingBean` 注册模式样板(`#023` 复用同款)
  - `#020a` skill-foundation — `ToolRegistry.unregister` SPI 扩展(虽 `#023` 不调,留兼容)
  - `#021b` mcp-tool-adapter — `ToolRegistry.unregister(String)` SPI 扩展(同款)
  - `#022` spring-ai-annotation-tool — `ToolErrorCodes` 常量类样板 + L1 测试 case 模板
  - `e13e6a5` fix-initializingbean-postconstruct — `InitializingBean` 模式统一(`DelegateAutoConfiguration` 用同款)
- **后续 Story(本 Story 是其前置)**:
  - §14 N7 SessionStore 多后端(`#014`) — 子 Agent 走 fresh session,需 SessionStore 持久化父-子对话历史(已落地 memory 后端)
  - §14 N10 N10 audit-log(`#016`) — `AuditLogger` SPI 落地时,`DelegateTool.execute()` 内 emit `SubAgentSpawned(type, parentSessionId)` + `SubAgentCompleted(type, childSessionId, finalText)` 事件
  - OQ-Future: 子 Agent 并发调度(目前串行) / 子 Agent 子-子 Agent 嵌套 / 子 Agent LLM Stream 中途 cancel

---

**Spec writer**: Claude Code
**Spec date**: 2026-09-24
**Spec version**: v0.1 Draft