# Plan: Story #027a `anthropic-tool-protocol-conversion`

> **Spec anchors**: specs/027a-anthropic-tool-protocol-conversion/spec.md
> **Design anchors**: dsh v1.5.43 §6.5 protocol gap(实测发现 2026-09-26)+ §4.6 Tool / §4.10.1 硬规则 2 / §15.4 ErrorCode 域 + constitution v1.0
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed`(constitution §1 第 1 项 + §6 兼容性矩阵)
- **Lombok `@Value` 不可变优先** — `LingsLlmProviderException` 用经典 `final field` + 显式 ctor + `getMessage()` 内嵌 `[LINGS-L0X]` 前缀,避免 `record`
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — LINGS-L01 / LINGS-L02(constitution §4 + spec §1 修正;**L 段 LlmProvider 域启用,自 `#026` 后首次启用新 ErrorCode 域**)
- **性能预算 §14.15.1 不退化** — `buildRequestBody` / `parseResponse` 同步跑,无新增 I/O;`#027a` 不开 L6 性能测试(Story 体量不达 NFR 阈值)
- **`ToolExecutor.dispatch()` 5 步流水线不变** — `AnthropicLlmProvider` 协议层不绕过任何一步(§4.10.1 硬规则 2)
- **0 新 Maven 依赖** — Jackson `ObjectMapper` + `ObjectNode` + `ArrayNode` 已锁 / `ToolSpec` / `Message` 5 子类已存 / `LlmResponse.getToolCalls()` 已存 / `MAPPER.readTree()` 已用(`#001` 落地;`#027a` 第 12 次验证)
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock `HttpServer`**(`com.sun.net.httpserver.HttpServer` JDK built-in,沿用 `#021a`/`#022`/`#023` 模式)— 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` POST,不走 ChatClient 自动执行

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 接口 / 异常类 | 路径 | 角色 |
|---|---|---|
| `LingsLlmProviderException extends RuntimeException` | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LingsLlmProviderException.java` | LlmProvider 协议层错误统一抛出方式,`errorCode` 字段 + `getMessage()` 内嵌 `[LINGS-L0X]` 前缀(对齐 `LingsConfigException` + `LinearTurnEngine.LINGS-C02` 模式)|
| `LlmErrorCodes` 静态常量类 | `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java` | `LINGS_L01 = "LINGS-L01"` / `LINGS_L02 = "LINGS-L02"` 常量集中(对齐 `#022` `ToolErrorCodes` + `#021b` `McpErrorCodes` + `#026` `YamlPlaceholderErrorCodes` 模式)|

### 修改

| 接口 / 类 | 修改 |
|---|---|
| `AnthropicLlmProvider.buildRequestBody(Prompt)` | private method L170-204 改造:序列化 `messages[].content` 为 array of blocks(text / tool_use / tool_result)+ 新增顶层 `tools:[]` 字段;`Message.Assistant.toolCalls` 序列化(本 Story 关键)|
| `AnthropicLlmProvider.parseResponse(String body)` | private method L208-247 改造:解析 `content[]` 多类型 block → `LlmResponse.text` + `LlmResponse.toolCalls`;缺 id/name 抛 `LingsLlmProviderException(LINGS_L01)` |
| `TurnContext.appendAssistant(String text, Usage usage)` | 接口签名扩 `toolCalls` 参数 → `appendAssistant(String text, List<ToolCall> toolCalls, Usage usage)` |
| `DefaultTurnContext.appendAssistant(...)` | 实现对齐,`new Message.Assistant(text, toolCalls, usage)`(`#001` 已存 3-arg ctor)|
| `LinearTurnEngine.L166` | `ctx.appendAssistant(resp.getText(), resp.getUsage())` → `ctx.appendAssistant(resp.getText(), resp.getToolCalls(), resp.getUsage())` |

**关键约束**:
- `#027a` **不修改** `Tool` / `ToolRegistry` / `ToolExecutor` / `Message` 5 子类 / `LlmResponse` 契约 / `Prompt.tools` 契约 — 全部 0 改动
- `AnthropicLlmProvider` 6-arg ctor + `doPost()` HTTP POST + 一次性 readAll 骨架 — **0 改动**(只在 2 个 private method 内做协议转换)

**新增 + 修改严格遵循 dsh §6.5 protocol gap 字面落地**,不引入新接口契约,与 `#026` `PlaceholderResolver` + `YamlPlaceholderErrorCodes` 模式对齐(同属「实现细节层」+「ErrorCode 常量集中」样板)

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LingsLlmProviderException.java` | 新增 | ~35 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java` | 新增 | ~25 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` | modify | +60(原 L170-204 + L208-247 改造)+ 5(新 import)|
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java` | modify | +3(接口签名扩 1 参数)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java` | modify | +2(实现对齐)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | modify | +1(L166 真传 toolCalls)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderTest.java` | 新增 / 扩展 | +400(8 case 覆盖 AC-NN-1—AC-NN-6 + AC-NN-7 mock HTTP server)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineTest.java` | modify | +30(1 case 覆盖 AC-NN-7 L166 真传 toolCalls 端到端)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultTurnContextTest.java` | modify | +20(1 case 覆盖 toolCalls 字段透传)|

**2 新增 + 4 modify + 2 test 扩展**,合计 **8 文件改动**,符合 §11.4 Story 边界 ≤ 5 核心文件(`AnthropicLlmProvider` / `TurnContext` / `DefaultTurnContext` / `LinearTurnEngine` 4 个核心修改 + `LingsLlmProviderException` / `LlmErrorCodes` 2 个新增),与 ROADMAP 段二 #027a 行「4 文件 + 1 ErrorCode 常量」对齐

---

## 3. 实现顺序

> **原则**:依赖方向 core 内部:`LlmErrorCodes` 常量 → `LingsLlmProviderException` 异常类 → `AnthropicLlmProvider.buildRequestBody` / `parseResponse` 协议转换(用常量 + 异常)→ `TurnContext` 接口签名扩 → `DefaultTurnContext` 实现对齐 → `LinearTurnEngine.L166` 真传 toolCalls → 测试

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `LlmErrorCodes` + `LingsLlmProviderException` | 无 | 2 文件可编译 |
| 2 | `AnthropicLlmProvider.buildRequestBody` + `parseResponse` 协议转换 | `LlmErrorCodes` + `LingsLlmProviderException` + `Message.ToolUse/ToolResult` | 1 文件 modify + L1 测试 6 case(AC-NN-1—AC-NN-6)|
| 3 | `TurnContext` 接口 + `DefaultTurnContext` 实现 | 无 | 2 文件 modify + L1 测试 1 case(签名对齐)|
| 4 | `LinearTurnEngine.L166` 真传 toolCalls | `TurnContext` 新签名 | 1 文件 modify + L1 测试 1 case(toolCalls 透传)|
| 5 | L2 slice 端到端(AC-NN-7)| 全部 | `AnthropicLlmProviderTest` + `LinearTurnEngineTest` 扩展 mock HTTP server 跑完整 ReAct Action `dispatchParallel` 真发并行 tool_call |

**每步独立 commit**(`feat(llm): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 `#009d` / `#022` 风格)
**绝对禁止一次性 commit 8 文件**(`#022` 反面教材,`#027a` 严格离散)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 11 | `AnthropicLlmProviderTest` 8 case(AC-NN-1 tools:[] + AC-NN-2 content blocks 5 类 + AC-NN-3 parseResponse tool_use + AC-NN-4 防御 L01 + AC-NN-5 buildRequestBody 缺 id 抛 L01 + AC-NN-6 buildRequestBody 缺 toolUseId 抛 L02 + 2 helper `buildPromptWithTools` / `buildMockResponse`);`DefaultTurnContextTest` 1 case(`appendAssistant` toolCalls 透传);`LinearTurnEngineTest` 1 case(`L166` 真传 toolCalls);`LingsLlmProviderExceptionTest` 1 case(getMessage 含 ErrorCode 前缀)| `AnthropicLlmProviderTest` / `DefaultTurnContextTest` / `LinearTurnEngineTest` / `LingsLlmProviderExceptionTest` |
| **L2 Slice** | 3 | `AnthropicLlmProviderTest` 端到端 3 case(mock HTTP server `com.sun.net.httpserver.HttpServer` JDK built-in:AC-NN-7 真跑 ReAct Action `dispatchParallel` + AC-NN-8 R-13 dep-tree 0 binary delta + 多 Tool 并行 tool_use blocks)| `AnthropicLlmProviderTest`(端到端)|
| **L3 Component** | —(并入 L2)| 无需独立 L3,L2 已覆盖「`AnthropicLlmProvider` + `LinearTurnEngine` + `ToolExecutor` + `ToolRegistry` + mock BashTool 多 Bean 协作」 | — |
| **L4 Contract** | 0(无接口契约变更)| `#027a` 改 `TurnContext.appendAssistant` 签名扩 1 参数(`DefaultTurnContext` + `LinearTurnEngine` 同 PR 内对齐) — **#027a 是破坏性签名变更**,但只有 1 个实现类(`DefaultTurnContext`)+ 1 个调用方(`LinearTurnEngine.L166`)在同仓,外部 plugin 暂未扩展 TurnContext,**无 L4 兼容性测试** | — |
| **L5 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过,**AC-NN-1—AC-NN-7 + AC-NN-deps-1—AC-NN-deps-2** 全跑通 | CI |
| **L6 Performance** | 不跑(Story 体量不达 NFR 阈值)| `buildRequestBody` / `parseResponse` 同步跑,反射开销 < 10ms / 100 messages,**L6 不强制**(给 OQ-Future 留口) | — |
| **L7 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 | CI |

**New Case 计数**:**14 test cases** 跨 4 文件(L1 11 + L2 3 = 14)
**ROADMAP 估算**:表第 15 行「4 文件 + 1 ErrorCode 常量」+ `LlmProvider 域 ≤ 80%` 覆盖率门槛(spec §4 AC-NN + plan §4)→ 14 cases 与 `#022` 同 Story 量级(22 cases,`#027a` 边界更紧)

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** `Message.Assistant.toolCalls` 反序列化边界(`#001` 已存空 list 占位契约,但生产环境可能传 null)| 1×2=2 | `buildRequestBody` 入口 `if (msg.toolCalls() == null) toolCalls = emptyList()` 防御 + `LinearTurnEngine.L166` 入口同样防御 | revert PR;旧 `AnthropicLlmProvider` 仍只走 text-only 路径,功能完整 |
| **R-B** `TurnContext.appendAssistant` 签名破坏性变更 → 第三方 plugin 扩展 `TurnContext` 编译失败 | 2×2=4 | `#027a` 是 Story 实施期扩签名,**`DefaultTurnContext` + `LinearTurnEngine` 同 PR 内对齐**;constitution §1 第 4 项 Story 边界允许「签名扩参数」当约束对齐单 Story 内 — 当前 `#027a` 4 处改动全在 `lingshu-core` 内,无外部 plugin 触碰 `TurnContext` | revert PR;旧 2-arg `appendAssistant` 仍可用,功能完整 |
| **R-C** Anthropic API mock 与真实 API 行为差异(`mock` 不覆盖 `content[].cache_control` / `thinking` 等扩展字段)| 2×2=4 | mock HTTP server 严格按 dsh §6.5 协议层字面实现(`content[]` 数组 + 3 类 block + `stop_reason` + `usage` 4 字段);`#027b` 流式 + cache / thinking 留 OQ-Future | revert PR;旧 `parseResponse` 仍返 text-only,功能完整 |
| **R-D** `Message.ToolResult.content` 字段(`String` vs `List<ContentBlock>` 多模态)| 1×1=1 | `#001` `Message.ToolResult` 是 `String content` 单字段;`#027a` 严格 1:1 映射(Anthropic 协议 `content` 也接受 string) — 多模态留给 OQ-Future `Message` 扩展 Story | N/A |
| **R-E** R-13 mitigation (d) banned list 触发(`#027a` 引入 `ObjectNode` / `ArrayNode` 大量 JSON 序列化)| 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-deps-1 + AC-NN-deps-2 + tasks.md T-dep-tree-1—T-dep-tree-4 + PR body `### R-13 dependency:tree 自查` 节(`#026` 已验证 0 binary delta,`#027a` 第 12 次验证) | revert PR;旧 `AnthropicLlmProvider` 仍只走 text-only 路径,功能完整 |

**等级**:R-A / R-B / R-C / R-D ≤ 4 监控即可;**R-E ≥ 6 必缓解**(L2 测试断言强制 + enforcer build fail)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `#027a` 1 段(协议层 Tool 修复,Anthropic provider 真发 `tools:[]` + 解析 `tool_use` block 闭环)
- [ ] `specs/027a-anthropic-tool-protocol-conversion/quickstart.md`(本 PR 内;给 Alice 30min 跑通 hello world,模板对齐 `#009d` / `#022`)
- [ ] `specs/027a-anthropic-tool-protocol-conversion/data-model.md`(`AnthropicLlmProvider.buildRequestBody` / `parseResponse` 协议转换对照表 + `LINGS-L01` / `LINGS-L02` ErrorCode 域表,模板对齐 `#022`)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.43 → v1.5.44` 行(本 Story 实施记录)
- [x] `dsh_agent_design.md` §6.5 (1.5) 新增 protocol gap 子节 — **已直接写入 dsh**(`~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md` line 4462-4560,99 行,§6.5 (1) 与 §6.5 (2) 之间;含问题描述 + 根因 + 协议字段对照表 12 行 + OpenAI/Gemini 占位 + 补丁契约 7 行 + 关键不变项 11 条 + Story 路由 4 行 + 反模式 5 条 + 实施期检查清单 6 条);dsh 总行数 7250 → 7421(+171 行);同时 §13 changelog 待 Story 合入后补 `v1.5.43 → v1.5.44` 行
- [ ] `constitution.md` §10 R-13 风险登记:`Story #027a` 标记「已缓解」+ 描述本次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#027a` 行
- [ ] `ROADMAP.md` §15.4 ErrorCode 域表同步 `LINGS-L01` / `LINGS-L02`(`constitution §4` + dsh §15.4)
- [ ] `lingshu-docs` 仓 `docs/concepts/llm-protocol-tools.md`(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.43` → `v1.5.44`)

---

## 7. 关键不变项(冻结)

1. `Tool` interface 5 方法 + `ToolRegistry` interface 8 方法(7 + `#021b` 的 unregister)+ `ToolExecutor.dispatch()` 5 流水线(**§4.10.1 硬规则 2**)全部 0 改动
2. `#001` 落地的 `AnthropicLlmProvider` 6-arg ctor + `doPost()` HTTP POST + 一次性 readAll 骨架 — `0 改动`(只在 2 private method 内做协议转换)
3. `#001` 落地的 `Message` 5 子类(`User` / `Assistant` / `System` / `ToolUse` / `ToolResult`)+ `LlmResponse` 5 字段(`text` / `toolCalls` / `stopReason` / `usage` / `errorCode`)+ `Prompt` 5 段(`system` / `instructions` / `tools` / `messages` / `modelParams`)— `0 改动`
4. `#001` 落地的 `Usage` 2 字段(`inputTokens` / `outputTokens`)— `0 改动`(cache tokens 留给 OQ-Future)
5. `#004` 落地的 `LinearTurnEngine` ReAct 主循环结构(L161-187 / L171-174 finish branch / L177 dispatchParallel / L181 appendToolResult / L184 done())— `0 改动`(只 L166 1 行真传 toolCalls)
6. `#019` 落地的 `Read / Write / Edit / Bash` 4 个 hand-written Tool 实现 + `LocalToolsAutoConfiguration.afterPropertiesSet()` 注册路径 — `0 改动`
7. `#020a` 落地的 `ToolRegistry.modelVisibleSpecs()` + `findSkill` / `skillNames` / `findByName` 4 个查询方法 — `0 改动`
8. `#022` 落地的 `@AgentTool` 注解 + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` + `ToolErrorCodes.LINGS_T08` — `0 改动`
9. `#024` 落地的 `DefaultPromptBuilder` 2 构造器注入 `ToolRegistry` → `Prompt.tools = toolRegistry.modelVisibleSpecs()` — `0 改动`
10. dsh §6.5 protocol gap(实测发现 2026-09-26)字面落地,**0 新接口契约**(只在 2 private method 内做协议转换 + 2 个 ErrorCode 常量 + 1 个 Exception 类)
11. constitution v1.0 §1—§9 全部不变,只 §10 R-13 风险状态更新
12. dsh §15.4 域字母 L 编号表:新增 `LINGS-L01 LLM_PROTOCOL_TOOL_USE_INVALID` / `LINGS-L02 LLM_PROTOCOL_TOOL_RESULT_INVALID`,**L03+ 编号不动**(`#027b` 留)
13. **0 新 Maven 依赖**(R-13 mitigation (d) 第 12 次验证)
14. **2 处核心修改 + 2 处核心新增**:`AnthropicLlmProvider` 协议转换 / `TurnContext` 接口签名扩 + `DefaultTurnContext` / `LinearTurnEngine` 对齐 / `LingsLlmProviderException` + `LlmErrorCodes` — 符合 §11.4 Story 边界 ≤ 5 核心文件 ≤ 3 ErrorCode
15. **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` POST

---

**Plan writer**: Claude Code
**Plan date**: 2026-09-29
**Plan version**: v0.1 Draft
