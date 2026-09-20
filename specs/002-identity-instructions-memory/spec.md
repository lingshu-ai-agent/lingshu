# Feature Specification: Story #002 identity-instructions-memory

**Feature Branch**: `story-002-identity-instructions-memory`

**Created**: 2026-09-20

**Status**: Draft

**Input**: User description: "identity-instructions-memory" (灵枢 LingShu Story #002,dsh §0.4 AC-09)

**Source Design Doc**: `dsh_agent_design.md` v1.5.34 §0.4 AC-09 / §4.5 PromptBuilder / §4.5.1 5 段装配 / §4.12.2 Identity / Instructions / Memory schema / §5.3.1.1 MemorySourceRouter / §5.5 Slot 1 + Slot 7 默认 Provider / §8.1 业务三件套 YAML Schema

**Constitution**: `.specify/memory/constitution.md` v1.0 — §1 JDK 8 兼容 + §2 13 项依赖锁定 + §4 `LINGS-<域><编号>` 错误码 + §10 R-13 Spring AI 误用

**对应 AC**: **AC-09** — 业务配置三件套完整可用(§0.4 L139-147)

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — 业务三件套 YAML 端到端跑通 (Priority: P1)

作为 **Bob(业务配置方)**,我希望在 `application.yml` 写 `agent.identity.*` + `agent.instructions.inline` + `agent.memory.claude-md.path` 三段配置后,跑一个 turn 就能看到 LLM 收到的 system prompt 按 **5 段装配顺序** 正确填充(角色定位 → 指令模板 → 项目 CLAUDE.md → 历史 → 用户消息),这样企业内 AI 编码助手 / 业务 Agent 能在不写 Java 代码的前提下定制人格 / 行为规范 / 项目记忆。

**Why this priority**: 这是 Story #002 的核心交付 —— 业务三件套的"5 段 system prompt 装配顺序"是 LingShu 与通用 Agent 框架最显性的差异点。AC-09 黑盒可断言是验收门槛,**缺它** Story #001 留下的 27 字段默认值就只是数据壳,LLM 拿不到任何业务上下文,**等于 Agent 没业务**,Bob 拿到的 `factory.defaultConfig()` 跑 turn 与 `factory.create(empty cfg)` 跑 turn 行为相同,**无业务价值**。

**Independent Test**: 跑 `mvn -pl lingshu-examples/demo-engineer package && time java -jar demo-engineer-1.0.0.jar "你是做什么的"`,30 秒内拿到首个流式 token,且 token 文本包含 5 段关键标识(`你是 lingshu-engineer` / `Java 后端工程师` / `<./prompts/system-engineer.md 内容>` / `<./CLAUDE.md 内容>` / `<用户输入>`)。

**Acceptance Scenarios**:

1. **Given** yml 配置 `agent.identity.name=lingshu-engineer` + `agent.identity.traits=[严谨,简洁,举反例]` + `agent.instructions.file=./prompts/system-engineer.md` + `agent.memory.claude-md.project=./CLAUDE.md`
   **And** `./CLAUDE.md` + `./prompts/system-engineer.md` 文件存在且非空
   **When** 跑一个 turn
   **Then** LLM 收到的 system message 由 5 段按序拼接:
   - `[ROLE]` 段首行 `你是 lingshu-engineer,Java 后端工程师。`,次行 `人格特质:严谨、简洁、举反例。`,再 `语气:直接不啰嗦。`,再 `输出语言:zh。`(任一字段为空时对应行被跳过,**不**输出空行)
   - `[INSTRUCTIONS]` 段为 `./prompts/system-engineer.md` 全文(模板变量已渲染)
   - `[PROJECT MEMORY]` 段首块为 `./CLAUDE.md` 全文,后接 `── separator ───`,再 `~/.lingshu/CLAUDE.md` 全文(文件不存在则静默跳过,**不**报异常)
   - `[CONVERSATION HISTORY]` 段为空(首轮 turn)
   - `[USER MESSAGE]` 段为本次输入

2. **Given** yml 完全空(只 `spring.application.name=lsh-engineer`)
   **When** 跑一个 turn `prompt="1+1=几"`
   **Then** system message 仅由 default [ROLE](`你是 lingShu-agent`)构成(其余 [INSTRUCTIONS]/[PROJECT MEMORY] 全部因字段为 null 或文件不存在被剔除),[USER MESSAGE] 段为 `1+1=几`,turn 30 秒内完成首个 token 返回 `2`

3. **Given** yml 配置 `agent.identity.role=null` + `agent.identity.tone=null` + `agent.instructions=null`
   **When** 跑 `factory.defaultConfig()` 拿到 `AgentConfig cfg`
   **Then** `cfg.identity = Identity.defaults()`(name="lingShu-agent",其余空),`cfg.instructions = Instructions.empty()`(file=null, inline=null, templateEngine="none", variables={}),`cfg.memory = Memory.defaults()`(claudeMd.enabled=true, project="./CLAUDE.md", user="~/.lingshu/CLAUDE.md", extras=[])
   **And** `cfg` 是不可变 `@Value`(Lombok 编译期检查无 setter)

---

### User Story 2 — 切换 PromptBuilder Provider 按名路由 (Priority: P2)

作为 **Charlie(框架贡献者)**,我希望在写新 PromptBuilder 替代实现时,只需 `@Component implements PromptBuilderProvider` 并返回唯一 `name()`,就能被 yml `agent.prompt.builder` 一行切换,无需改 SlotResolver / AgentFactory / 启动代码 —— 这是 §5.5 多 Provider 模式(🆕 v1.5.28)的承诺落地,**Story #002 是首次按此模式落地默认 + 替代 Provider** 的窗口。

**Why this priority**: 多 Provider 模式是 Story #001 / Story #003 / Story #009 都要复用的基础设施。**Story #001 已经把"模式"立在 SlotRouter 抽象 + 4 个默认 Provider**;Story #002 把"PromptBuilder Router + 4 个 MemorySource Provider"接入,把多 Provider 模式**真正跑通**(两个 Slot × 多个 Provider × 按 name 路由),给 Story #003 LlmProvider 多 Provider 切换铺路。

**Independent Test**: 在 `lingshu-examples/demo-engineer` 加一个 `agent.prompt.builder=identity-only` 的 yml 副本,跑 turn 期望 system prompt 只含 [ROLE] 段([INSTRUCTIONS] [PROJECT MEMORY] 全部空) —— 说明 `IdentityMemorySourceProvider`(name="identity")成功被 Router 选中,其他 3 个 MemorySource 未被加载。

**Acceptance Scenarios**:

1. **Given** classpath 同时注册 `DefaultPromptBuilderProvider`(name="default")+ `IdentityOnlyPromptBuilderProvider`(name="identity-only")+ 4 个 MemorySourceProvider(project-claude-md / user-claude-md / identity / project-tree)
   **And** yml `agent.prompt.builder=identity-only` + `agent.prompt.memory-sources=[identity]`
   **When** Spring 启动
   **Then** 启动日志输出 `[PromptBuilder] resolved 2 provider(s): ... [MemorySource] resolved 4 provider(s): ...`
   **And** `agent.run("hello")` 走完 ReAct 一轮,system prompt 仅含 [ROLE] 段(其它段被剔除)

2. **Given** yml `agent.prompt.builder=non-existent`
   **When** 启动 Agent
   **Then** 启动期 fail-fast,抛 `IllegalArgumentException("Unknown PromptBuilderRouter 'non-existent'. Available: [default, identity-only]")`,JVM 退出 1,错误码 `LINGS-S01 SLOT_NOT_FOUND`

3. **Given** 4 个 MemorySource Provider 同存且全部 priority=0
   **When** Spring 启动 + yml `agent.prompt.memory-sources=[project-tree, identity, project-claude-md, user-claude-md]`
   **Then** SlotResolver 按 `MemorySource::priority` 升序排序后返回 4 个 MemorySource 实例(同名按 priority 选大,其余进 conflict 日志)
   **And** PromptBuilder 在 [PROJECT MEMORY] 段按解析顺序(yml 列表顺序)拼装 4 个 source 的 `load(ctx)` 返回值

---

### User Story 3 — CLAUDE.md 自动发现 + 缺失静默跳过 (Priority: P3)

作为 **Alice(企业 AI 编码助手使用者)**,我希望 `./CLAUDE.md` / `~/.lingshu/CLAUDE.md` / `agent.memory.extras` 列表里的文件**任意**缺失时,Agent **不报错**也不在日志输出 ERROR 级日志,而是静默跳过该 source 把 [PROJECT MEMORY] 段剩下的内容拼上 —— 因为在 CI 容器 / 新克隆的 dev 工作区 **没有** 用户级 CLAUDE.md 是常态,不应当让 Agent 启动失败。

**Why this priority**: 与 P1 / P2 相比是次要约束,但 §8.1.3 文档明确写了"文件不存在则静默跳过",**实施期漏写会让用户在第一次跑就看到 ERROR 日志**,破坏 Story #001 守住 AC-01-1"stderr 零 ERROR"基线。

**Independent Test**: 在测试 JVM 中 `System.setProperty("user.home", "/nonexistent-home")`,跑 `factory.create(defaultConfig())` 不抛异常,且 `DefaultPromptBuilder.build()` 的 system prompt 不含 [PROJECT MEMORY] 段。

**Acceptance Scenarios**:

1. **Given** yml `agent.memory.claude-md.enabled=true` 但 `./CLAUDE.md` 与 `~/.lingshu/CLAUDE.md` 均不存在
   **When** PromptBuilder.build(ctx)
   **Then** [PROJECT MEMORY] 段**完全剔除**(整个段落标题 + 内容都不出现),不抛 `IOException`,stderr 无 ERROR
   **And** 其余 4 段([ROLE] / [INSTRUCTIONS] / [CONVERSATION HISTORY] / [USER MESSAGE])正常拼装

2. **Given** yml `agent.memory.claude-md.enabled=false`(显式禁用)
   **When** PromptBuilder.build(ctx)
   **Then** CLAUDE.md 段全部跳过,即使 `./CLAUDE.md` 存在也不加载

3. **Given** yml `agent.memory.extras=[./docs/team-conventions.md, ./docs/missing.md]`(第二个不存在)
   **When** PromptBuilder.build(ctx)
   **Then** [PROJECT MEMORY] 段仅含 `./docs/team-conventions.md` 全文 + `── separator ───`,`missing.md` 静默跳过

4. **Given** `./CLAUDE.md` 大小 1MB(超出常规 .md)
   **When** PromptBuilder.build(ctx)
   **Then** 整文件全文拼进 [PROJECT MEMORY] 段,**不**做大小截断(由 Story #015 Compactor 处理,本 Story 不动)

---

### User Story 4 — Instructions 模板引擎渲染 (Priority: P3)

作为 **Bob(业务配置方)**,我希望 `agent.instructions.template-engine=mustache` + `agent.instructions.variables={org: lingshu-ai-agent}` 时,文件里的 `{{org}}` 被替换为 `lingshu-ai-agent`,而 `{{identity.name}}` 在运行时被替换为 `cfg.identity.name` 的当前值 —— 这样一份模板可以在不同组织 / 不同 identity 下复用。

**Why this priority**: 这是 Story #002 的"可选增值"功能,不影响 5 段装配正确性(即使 template-engine=none 也能跑通 AC-09)。但 §4.5.1 Javadoc 明确写了 `template-engine=mustache → 替换 {{var}}`,**实施期漏写会让用户配了 mustache 但 `{{var}}` 不被替换**,变成静默 bug。

**Independent Test**: yml 配 `instructions.template-engine=mustache` + `instructions.variables={org: lingshu-ai-agent}`,`./prompts/test.md` 内容含 `team: {{org}}, agent: {{identity.name}}`,跑 turn 期望 LLM 收到的 instructions 段含 `team: lingshu-ai-agent, agent: lingshu-engineer`。

**Acceptance Scenarios**:

1. **Given** yml `agent.instructions.template-engine=mustache` + `agent.instructions.variables={org=lingshu-ai-agent, project=lsh}`
   **And** `./prompts/test.md` 含 `team: {{org}}, project: {{project}}`
   **When** PromptBuilder.build(ctx)
   **Then** [INSTRUCTIONS] 段内容为 `team: lingshu-ai-agent, project: lsh`(双花括号全部替换,**不**残留 `{{}}`)
   **And** 渲染失败(未知变量)→ 抛 `IllegalArgumentException("Unknown mustache variable: {{xxx}}")`,错误码 `LINGS-C02 CONFIG_VALIDATION_FAILED`,stderr 输出 ERROR(只在确实配了模板但变量缺失时才报,不是必报项)

2. **Given** yml `agent.instructions.template-engine=none`(默认)+ `instructions.variables={any=any}`(默认值忽略)
   **And** `./prompts/test.md` 含 `team: {{org}}`
   **When** PromptBuilder.build(ctx)
   **Then** [INSTRUCTIONS] 段内容**原样**保留 `team: {{org}}`(不替换,variables 字段被忽略)

3. **Given** yml `agent.instructions.file=null` + `agent.instructions.inline="raw text"`
   **When** PromptBuilder.build(ctx)
   **Then** [INSTRUCTIONS] 段内容 = `"raw text"`(直接 inline,不走文件读取,template-engine 仍生效)

---

### Edge Cases

- **空集合 vs null**:`cfg.prompt.memorySources=null` 与 `cfg.prompt.memorySources=[]` 等价,均不调 `MemorySourceRouter`;SlotResolver.memorySources() 返 `Collections.emptyList()`,[PROJECT MEMORY] 段完全剔除
- **同 name 多 Provider**(4 个 MemorySource 中 2 个 name 都是 "project-claude-md"):父类 `SlotRouter` 按 `priority()` 选最大,其余进 conflict 日志,**不**抛异常(§5.2 同名竞争规则)
- **`Identity.defaults()` 与 null**:`cfg.identity == null` 时,PromptBuilder 内部用 `Identity.defaults()` 兜底,**不**抛 NPE(§4.5.1 L458)
- **路径越界**:`agent.instructions.file=../../../etc/passwd` —— PromptBuilder **不**做路径沙箱校验(留给 Story #001 后引入的 RuntimeSandbox,本 Story 只读文件,不做 chroot);这是 v1.5 范围,Story #002 不引入新安全语义
- **超长 system prompt**:`./CLAUDE.md` 1MB + extras 4 个 500KB 合计 3MB,PromptBuilder **不**做长度截断(Compactor 域 Story #015 处理,本 Story 不动)
- **空 Identity 全字段**:`Identity.defaults()` 的 `role=null` + `traits=[]` + `tone=null` + `avatar=null` → [ROLE] 段仅含 `你是 lingShu-agent`,其余行被剔除,**不**输出 `人格特质:` / `语气:` 这种空标题

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 系统 **MUST** 在 `AgentConfig` 提供 `Identity` / `Instructions` / `Memory` 三个 `@Value` 嵌套类(§4.12.2),且提供 `Identity.defaults()` / `Instructions.empty()` / `Memory.defaults()` 三个静态工厂方法保证空 yml 时**全部字段非 null**(可空集合用 `Collections.emptyList()` / `Collections.emptyMap()`)
- **FR-002**: 系统 **MUST** 提供 `DefaultPromptBuilder implements PromptBuilder`,按 §4.5.1 完整 5 段装配顺序生成 system prompt([ROLE] → [INSTRUCTIONS] → [PROJECT MEMORY] → [CONVERSATION HISTORY] → [USER MESSAGE]),且 [TOOL SCHEMAS] 通过 `Prompt.tools(List<ToolSpec>)` 字段独立于 system text 传递(不在 system 文本里塞 tool schema)
- **FR-003**: 系统 **MUST** 实现 4 个 `MemorySource` 默认可用 Provider(§4.5 + §5.5 + §8.1.3):`ProjectClaudeMdSourceProvider`(name="project-claude-md",读 `./CLAUDE.md`)+ `UserClaudeMdSourceProvider`(name="user-claude-md",读 `~/.lingshu/CLAUDE.md`)+ `IdentityMemorySourceProvider`(name="identity",把 Identity 字段拼成 [ROLE] 段候选文本)+ `ProjectTreeMemorySourceProvider`(name="project-tree",扫描项目目录)
- **FR-004**: 系统 **MUST** 在 `SlotResolver` 持 `PromptBuilderRouter` + `MemorySourceRouter`(§5.3 + §5.3.1.1),并把 `promptRouter.resolve(name, cfg)` 注入 `AgentFactory` 启动校验链路,新增 2 项 fail-fast:`cfg.prompt != null && cfg.prompt.builder != null` / `cfg.prompt.memorySources` 为 null 时按空列表处理
- **FR-005**: 系统 **MUST** 在用户配置 `agent.instructions.template-engine=mustache` 时,渲染 `instructions.file` 或 `instructions.inline` 中的 `{{var}}` 变量(变量来自 `instructions.variables` + `cfg.identity` 运行时字段);`template-engine=none`(默认)时原样保留
- **FR-006**: 系统 **MUST** 在 `MemorySource.load(TurnContext)` 返回 null 或文件不存在时静默跳过,**不**抛异常,**不**写 ERROR 级日志(§8.1.3 "文件不存在则静默跳过")
- **FR-007**: 系统 **MUST** 支持 yml 切换 `agent.prompt.builder` 按名路由 PromptBuilder Provider;切换 `agent.prompt.memory-sources=[...]` 按 yml 列表顺序拼装 [PROJECT MEMORY] 段(同 slot 多 Provider 模式,§5.5 v1.5.28)
- **FR-008**: 系统 **MUST** 在 `factory.defaultConfig()` 返回的 `cfg.prompt.builder = "default"`(指向 `DefaultPromptBuilderProvider`),`cfg.prompt.memorySources = ["project-claude-md", "user-claude-md"]`(yml 不配则走这两个,Identity / ProjectTree 由用户按需启用,Story #002 不强加)
- **FR-009**: 系统 **MUST** 提供 `lingshu-examples/demo-engineer` 黑盒示例(对齐 §8.1.4 Java 工程师 Agent),1 个 yml + 1 个 `./prompts/system-engineer.md` + 1 个 `./CLAUDE.md`,跑 `mvn package && java -jar` 验证 AC-09 5 段装配正确
- **FR-010**: 系统 **MUST** 在 `instructions.file` 缺失或不可读时回退到 `instructions.inline`;两者都缺失时 [INSTRUCTIONS] 段**完全剔除**(仅余空 system 块由 [ROLE] / [CONVERSATION HISTORY] 拼成,§4.5.1 L471-472)

### Key Entities

- **Identity**(§4.12.2 L1320-1338):Agent 业务身份 / 人格;6 字段(name / role / language / traits / tone / avatar);`defaults()` 静态工厂
- **Instructions**(§4.12.2 L1345-1359):System Prompt 配置;4 字段(file / inline / templateEngine / variables);`empty()` 静态工厂
- **Memory**(§4.12.2 L1366-1378):项目长期记忆;含 `ClaudeMd`(enabled / project / user)+ `extras`(List<Path>);`defaults()` 静态工厂
- **MemorySource**(§4.5 L396-401):静态 / 动态 memory 源 SPI;3 方法(`name()` / `priority()` / `load(ctx)`),`load()` 返回 null 表示该 source 此次无内容
- **PromptBuilder**(§4.5 L391-393):单方法 `build(TurnContext) → Prompt`;Prompt 抽象含 `messages` / `tools` / `hints` 3 件套
- **ToolSpec**(§4.2 message/ToolSpec.java):tool schema 三元组(name / description / inputSchema),由 PromptBuilder 从 ToolRegistry 拉取并组装

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: **`demo-engineer` AC-09 黑盒验证跑通**:`time java -jar demo-engineer-1.0.0.jar "你是做什么的"` 在 30 秒内返回首个 LLM 流式 token,token 文本含 5 段关键标识(角色名 + 角色定位 + ./prompts/system-engineer.md 内容片段 + ./CLAUDE.md 内容片段 + 用户输入回声),stderr 零 ERROR(对齐 Story #001 AC-01-1 NFR 基线)
- **SC-002**: **5 段装配顺序可单元断言**:`DefaultPromptBuilderTest`(JUnit 5 + AssertJ)覆盖 7 场景——(a) 全字段填充;(b) Identity 部分字段为空(仅输出非空行);(c) instructions.file 与 inline 都缺([INSTRUCTIONS] 剔除);(d) CLAUDE.md 两个文件都缺([PROJECT MEMORY] 剔除);(e) extras 列表部分文件缺(只拼存在的);(f) mustache 模板渲染(变量替换 + identity.name 运行时引用);(g) Prompt.tools 字段正确从 ToolRegistry 拉取并序列化 — 全部通过
- **SC-003**: **4 MemorySource Provider 同存 + 按名路由 + 静态工厂兼容**:`MemorySourceRouterTest` 验证 4 Provider 注册后,yml `memory-sources=[identity, project-claude-md, missing-source]` → SlotResolver 解析时抛 `IllegalArgumentException("Unknown MemorySourceRouter 'missing-source'...")`,`memory-sources=[project-claude-md]` → 正常返回 1 个 ProjectClaudeMdSource 实例(对齐 §5.2 同名竞争 + §5.3.1.1 边界表)
- **SC-004**: **`factory.defaultConfig()` 27 字段默认值新增断言**:`AgentConfigDefaultsTest` 扩 4 字段 —— `cfg.identity = Identity.defaults()` + `cfg.instructions = Instructions.empty()` + `cfg.memory = Memory.defaults()` + `cfg.prompt.builder = "default"` + `cfg.prompt.memorySources = ["project-claude-md", "user-claude-md"]`(Story #001 AC-01-2 已覆盖前 14 字段,本 Story 补 13 字段)
- **SC-005**: **R-13 mitigation (d) Spring AI 误用自查**:`mvn dependency:tree -pl lingshu-core -Dverbose` 输出**不**含 banned-dependencies 列表任何条目(§4 + constitution §10 R-13);binary size < 35MB 且相对 Story #001 delta < 10%(本 Story 不引新依赖,变 0)
- **SC-006**: **mvn validate + lingshu-core compile 全过**:`mvn validate -N && mvn -pl lingshu-core -am compile` exit 0,新增 `DefaultPromptBuilder` + 4 MemorySource + 2 Router 文件编译过(8 个新 .java 文件,无 @Component 循环依赖)
- **SC-007**: **PR body 含 R-13 自查 + AC-09 5 段装配输出截图 / 文本** —— `### R-13 dependency:tree 自查` 节贴关键子树 + AC-09 demo-engineer 跑通输出

---

## Assumptions

- **JDK 8 兼容**:DefaultPromptBuilder + 4 MemorySource 全部用 Lombok `@Value` / `Collections.empty*()`,**不用** `record` / `sealed` / `var` / `List.of`(constitution §1 #1)
- **Reactive 选型**:PromptBuilder.build() 是**同步**返回 `Prompt`,**不**走 Reactive Streams(`Prompt` 抽象就是同步;§4.5 L391-393 接口签名明确)
- **Template Engine v1**:mustache 渲染**不**引入 `com.github.spullara.mustache:compiler` 等三方库(增加 transitive 风险),改用 `String.replace("{{var}}", value)` 简单变量替换 + `cfg.identity` 内置 6 字段(穷举替换,见 §4.5.1 L460-469 Javadoc 中 "运行时解析" 语义);复杂 mustache 语法(if/loop/comment)留 v2 Story,本 Story 只覆盖纯变量替换
- **Identity → ROLE 段改由 PromptBuilder 直接拼,不通过 MemorySource**:Identity 是 **运行时业务身份**(每 turn 都参与 5 段装配),MemorySource 是 **静态 / 磁盘 IO 源**(同 §4.5 "会话消息历史不是 MemorySource" 注释);`IdentityMemorySourceProvider`(name="identity")实际是**可选 MemorySource**,在 [PROJECT MEMORY] 段拼一份"Identity 全文快照",供用户把 Identity 暴露给下游 RAG / debug;**默认** 5 段装配中 [ROLE] 段仍由 PromptBuilder 直接拼 `cfg.identity`,**不**经过 MemorySource 路由(避免循环 + 让 [ROLE] 段始终在 [PROJECT MEMORY] 之前)
- **ProjectTreeMemorySource v1**:本期**只**扫顶层目录(深度 1)+ `.md` 文件列表(非内容),把"项目结构概览"拼成一段文本(类似 `ls -R / find`);不递归扫描 + 不读取文件内容(留给 v2);用户启用方式 `memory-sources=[project-tree]`
- **mustache 渲染变量来源**:`instructions.variables`(Map)+ `cfg.identity` 6 字段(name / role / language / traits / tone / avatar);**不**支持 `cfg.memory.claudeMd.project` 这种嵌套路径(v2 再考虑 SpEL / 完整表达式);冲突优先级:identity.* 字段 > variables 同名 key(identity 优先)
- **Identity 字段全部可空**:`Identity.defaults()` 的 `name="lingShu-agent"` 是**唯一非空**字段(其他 5 个全部 null / empty);用户没改 yml 时 [ROLE] 段仅 1 行 `你是 lingShu-agent`
- **PromptBuilder 不可变**:DefaultPromptBuilder 是 Spring 单例,持 `ToolRegistry` 引用(Story #004 落地);Story #002 stub 阶段 `ToolRegistry` 可以传 null,build() 时检测 `tools==null` → 返回空 `List<ToolSpec>`,不影响 5 段装配
- **4 MemorySource 都用 Spring `@Component` + `@AutoConfiguration` + `@Bean(name="memorySourceProvider_<name>")` 多 Provider 模式**(对齐 §5.5 v1.5.28 多 Provider 模式)

---

## Out of Scope(Story #002 不做,留给后续 Story)

- ❌ **Prompt cache**(system / memory / rag 三段 cache key 命中,§14 N11)→ Story #015
- ❌ **Semantic Compactor**(LLM 摘要历史超阈值)→ Story #015
- ❌ **CachingPromptBuilder**(Anthropic cache_control 双层叠加)→ Story #015
- ❌ **Provider.version() 字段** → Story #003
- ❌ **A2A AgentCard 自动生成**(`cfg.identity` → AgentCard JSON,§5.6.8)→ Story #009(本 Story 不引 `lingshu-a2a-server` 模块)
- ❌ **Sub-agent 继承**(父 Agent 启动子 Agent 时 instructions / memory 沿用,§4.5.1 L521 + §6.6.1)→ Story #005 multi-tenant 之后
- ❌ **复杂 mustache 语法**(if/loop/comment,§8.1.2 模板示例)→ v2,本 Story 只覆盖纯变量替换
- ❌ **ProjectTreeMemorySource 递归扫描** → v2,本 Story 只深度 1 + .md 文件名列表
- ❌ **MemorySource hot-reload**(`agent.memory.claude-md` mtime 监听 + 自动 reload,§6.4 Skill 多源发现的设计)→ Story #005 memory-layers
- ❌ **identity 字段持久化到 Session**(当前每 turn 从 cfg 拿,Session 只存 messages)→ v2

---

## 参考章节(dsh v1.5.34 + constitution v1.0)

| 主题 | 来源 |
|---|---|
| 业务三件套 YAML Schema | dsh §8.1.1 / §8.1.2 / §8.1.3 |
| Identity / Instructions / Memory 类型定义 | dsh §4.12.2 L1320-1387 |
| PromptBuilder 5 段装配顺序 + 伪代码 | dsh §4.5.1 L406-519 |
| MemorySource SPI 接口 | dsh §4.5 L396-401 |
| MemorySourceRouter 边界行为表 | dsh §5.3.1.1 L1954-1962 |
| Slot 1 DefaultPromptBuilderProvider stub | dsh §5.5 L2268(stub 状态,本 Story 实装) |
| Slot 7 ProjectClaudeMdSourceProvider stub | dsh §5.5 L2240-2262(stub 状态,本 Story 实装) |
| Slot 7 替代 MemorySource 约定 | dsh §5.5 L2258-2261(identity / project-tree / conversation) |
| SlotResolver 多源列表解析模式 | dsh §5.3 + §5.3.1.1(Story #001 已加 memorySourceRouter 字段引用,本 Story 实装 concrete) |
| 多 Provider 模式 + 唯一 Bean 名约定 | dsh §5.4 + §5.5 v1.5.28 |
| 9 Slot × 默认 Provider 总表 | dsh §5.5 L2266-2278 |
| AC-09 黑盒验收 | dsh §0.4 L139-147 |
| 错误码 `LINGS-C02` / `LINGS-S01` / `LINGS-S05` | constitution §4 + dsh §15 |
| JDK 8 硬约束 | constitution §1 #1 + CLAUDE.md §3 |
| 13 项依赖锁定 + R-13 banned-dependencies | constitution §2 + §10 + dsh §17 |

---

**Spec Author**:Claude Code(根据用户 2026-09-20 会话指令"开始 lingshu 工程的 Story #002",蒸馏 dsh v1.5.34 §0.4 AC-09 + §4.5.1 + §4.12.2 + §8.1 + constitution v1.0)
**Spec Date**:2026-09-20
**Status**:Draft → Specified(待 /speckit-plan 评审)