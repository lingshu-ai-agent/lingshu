# lingshu-engineer 架构概览(被 project-tree MemorySource 加载)

> 这是 `extras` 列表里的 `architecture.md`,验证 ProjectTreeMemorySource 能深度 1 扫到 `.md` 文件。

## 1. 9 个 Slot 一览

| # | Slot | 接口 | 默认实现 |
|---|---|---|---|
| 1 | LLM | `LlmProvider` | Anthropic(Story #003)|
| 2 | Tool | `Tool` + `ToolExecutor` | 默认 Executor(Story #004)|
| 3 | Sandbox | `Sandbox` | JVM 内 chroot(Story #001)|
| 4 | Skill | `SkillSource` + `Skill` | classpath + directory 双源(Story #002)|
| 5 | SessionStore | `SessionStore` | memory(Story #014)|
| 6 | Compactor | `Compactor` | 无(Story #015)|
| 7 | PromptBuilder | `PromptBuilder` | 5 段装配(Story #002)|
| 8 | FlowEngine | `FlowEngine` | LinearTurnEngine = ReAct Loop(Story #001)|
| 9 | A2aTransport | `A2aTransport` | LocalAgentCardGenerator(Story #009)|

## 2. 数据流

```
User Input
  ↓
TurnContext (含 cfg + session + userInput)
  ↓
PromptBuilder.build(ctx)
  ↓
[ ROLE ] → [ INSTRUCTIONS ] → [ PROJECT MEMORY ] → [ HISTORY ] → [ USER MSG ]
  ↓
Prompt (messages + tools + hints)
  ↓
FlowEngine.run() (ReAct loop)
  ↓
LlmProvider.stream() / ToolExecutor.dispatch() / ...
  ↓
Assistant message + ToolResults → 循环至 END_TURN / MAX_STEPS
```

## 3. 配置加载顺序

`application.yml` → `AgentConfig.@Builder` → `AgentConfigDefaults.defaults()` 兜底 → `AgentFactory.create(cfg)` → 7 Router 解析 → `Agent.runBlocking()`。