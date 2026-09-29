# dsh §6.5 (1.5) 子节补漏 snippet — LlmProvider ↔ Tool 协议转换层(实测发现 2026-09-26)

> **本文档用途**:`dsh_agent_design.md` §6.5 新增子节内容。dsh 在 `~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md`(**只读**),Story #027a 实施者(用户)手动粘贴本 snippet 到 §6.5 (1) 和 §6.5 (2) 之间。
> **建议插入位置**:`dsh §6.5 (1)`(`ReadTool` / `WriteTool` / `EditTool` / `BashTool` hand-written)与 `§6.5 (2)`(`McpTransport` + `McpToolAdapter`)之间,作为「**Tool 体系与 LlmProvider 协议层之间的转换桥梁**」契约。
> **行数预估**:~150 行 + 1 协议对照表 + 1 协议字段字典。
> **关联 Story**:#027a `anthropic-tool-protocol-conversion`(本期非流式)+ #027b `anthropic-stream-tool-sse`(新窗口流式)

---

## §6.5 (1.5) LlmProvider ↔ Tool 协议转换层(实测发现 2026-09-26)

### 问题

`§6.5 (1)—(3)` 完整定义了 LingShu 的 3 条 Tool Scheme 来源(hand-written JSON Schema / MCP server / `@AgentTool` 反射),`§6.4` `ToolRegistry.modelVisibleSpecs()` 暴露 `List<ToolSpec>`(`#020a` + `#024` 落地),`§6.4` `Prompt.tools` 字段由 `DefaultPromptBuilder` 灌入(`#024` 落地),ReAct Action 阶段 `LinearTurnEngine.dispatchParallel(resp.getToolCalls(), ctx, sink)`(`#004` 落地)已能并行 dispatch,`ToolExecutor.dispatch()` 5 步流水线(`§4.10.1` 硬规则 2)能正确串入 `PermissionPolicy` / `TimeoutWrap` / `SandboxApply` / `Checkpoint`。

**整条 Tool 链路从 LLM 视角 → ToolExecutor → Tool 实现全栈就绪,仅 LlmProvider 协议层一处断裂**。

实测(`#027a` Story 起源,2026-09-26 审 `AnthropicLlmProvider.java`)发现 **4 段链路 gap**:

1. **`AnthropicLlmProvider.buildRequestBody(Prompt)`** — L170-204 实现 `messages[]` 序列化时,只走 `User` / `Assistant.text` / `System` 三类 message;遇到 `Message.Assistant.toolCalls` 跳过(`// Message.ToolUse / Message.ToolResult — skipped in Story #001.`);`Prompt.tools` 字段(`#024` 落地后 List<`ToolSpec`> 非空)也没翻译成 Anthropic 顶层 `tools: [{name, description, input_schema}]`。
2. **`AnthropicLlmProvider.parseResponse(String body)`** — L208-247 解析 Anthropic 响应时,只抽 `content[0].text`;遇到 `type=tool_use` block 跳过(`// Tool calls — Story #001 demo has no tools; Story #009 will parse content[] tool_use blocks.`)。**结果**:`LlmResponse.getToolCalls()` 永远返回 `Collections.emptyList()`,ReAct Action 阶段根本不进。
3. **`TurnContext.appendAssistant(String text, Usage usage)`** — `DefaultTurnContext.L101` 硬编码 `new AssistantMessage(text, emptyList(), usage)`,**toolCalls 参数从签名层缺**。
4. **`LinearTurnEngine.L166`** — `ctx.appendAssistant(resp.getText(), resp.getUsage())` 只传 text+usage,**不传 `resp.getToolCalls()`**。

**业务后果**:Agent 完全无 Tool 能力 — 即使 yml 配了 10 个 MCP server + 5 个 `@AgentTool` 注解 + 4 个手写 Tool + 3 个 RemoteAgentTool,LLM 看到 prompt 里 `tools: []`,ReAct 永远只走「text → text」,Tool 子系统死代码。`#020a/b/c` Skill 注册的 Tool + `#025` demo-product + `#025b` demo-product-a2a-server 全部对 LLM 不可见。

### 根因

`Story #001 zero-config-bootstrap` 实施期,Anthropic provider 只走 text-only 路径(`#001` 注释明确「demo has no tools」),`#009` A2A 落地时计划在 `parseResponse` 加 `tool_use` 解析但**实际未做**(`Message.ToolUse` / `ToolResult` 5 子类在 `#001` 已存但空跑)。

`#019` / `#020a` / `#021b/c` / `#022` / `#024` 5 个 Story 全部聚焦「Tool 子系统」(`Tool` interface / `ToolRegistry` / `SpringAiToolAdapter` / `@AgentTool` 注解 / `McpToolAdapter`),**协议层(LlmProvider ↔ Anthropic / OpenAI / Gemini wire format)未同步**。`#024` `tool-schemas-integration` 落 `Prompt.tools` 字段后,**LLM 视角最后一段(协议转换)空白**被放大。

`#009a/b/c/d/e` A2A 5 个 Story 走 RemoteAgentTool 路径,**底层仍走 `AnthropicLlmProvider.parseResponse` 解析 LLM 响应**,A2A 路径同样卡在这一段。

### 协议字段对照表(LingShu ↔ Anthropic `/v1/messages`)

| LingShu 内部 | Anthropic 字段 | 位置 | 备注 |
|---|---|---|---|
| `Prompt.tools[i]` | `tools[i]` | 顶层 array | `{name, description, input_schema}` 3 字段;`input_schema` 严格透传 `ToolSpec.inputSchema()` Jackson `ObjectNode` |
| `Message.User.content` | `content: [{type:"text", text:"<content>"}]` | `messages[]` | 1 user msg → 1 element array |
| `Message.Assistant.text` + `toolCalls=[]` | `content: [{type:"text", text:"<text>"}]` | `messages[]` | text-only path |
| `Message.Assistant.text` + `toolCalls=[...]` | `content: [{type:"text", text:"<text>"}, {type:"tool_use", id, name, input}, ...]` | `messages[]` | text block 在前,tool_use 块在后;`input` 是 JSON object(`ToolCall.args` 直接 `MAPPER.valueToTree`)|
| `Message.Assistant.text=""` + `toolCalls=[...]` | `content: [{type:"tool_use", id, name, input}, ...]` | `messages[]` | 纯 tool_use list,无 text block |
| `Message.ToolResult` | `content: [{type:"tool_result", tool_use_id, content, is_error}]` | `messages[]` | `content` 是 string(Anthropic 协议允许);`is_error` 是 boolean flag |
| `Message.System.content` | `system: "<content>"` | 顶层 string | `#001` 已用 Anthropic `system` 顶层字段,`#027a` 不改 |
| Anthropic 响应 `content[i].type="text"` | `LlmResponse.text`(尾部 concat) | 解析时 | 多 text block 累积 |
| Anthropic 响应 `content[i].type="tool_use"` | `LlmResponse.toolCalls[i] = ToolCall(id, name, args)` | 解析时 | `args` 是 `ToolCall.args` Jackson `ObjectNode` |
| Anthropic 响应 `stop_reason="tool_use"` | `LlmResponse.stopReason = StopReason.TOOL_USE` | 解析时 | 触发 ReAct Action 阶段 |
| Anthropic 响应 `usage.input_tokens` | `Usage.inputTokens` | 解析时 | `#001` 已落 2 字段 |
| Anthropic 响应 `usage.output_tokens` | `Usage.outputTokens` | 解析时 | cache tokens(`cache_creation_input_tokens` / `cache_read_input_tokens`)留给 OQ-Future |

### 协议字段字典(OpenAI / Gemini 占位)

| LingShu 内部 | OpenAI Chat Completions | Gemini `generateContent` |
|---|---|---|
| `Prompt.tools[i]` | `tools[i].function` + `tools[i].type="function"` | `tools[i].functionDeclarations[i]` |
| `Message.User.content` | `messages[i].content: "<content>"`(`-` role=user) | `contents[i].parts[i].text` |
| `Message.Assistant.toolCalls` | `messages[i].tool_calls[i]` | `contents[i].parts[i].functionCall` |
| `Message.ToolResult` | `messages[i].role="tool"` + `messages[i].tool_call_id` + `messages[i].content` | `contents[i].parts[i].functionResponse` |
| Anthropic `tools:[...]` 顶层 | OpenAI `tools:[...]` 顶层 | Gemini `tools:[...]` 顶层 |
| Anthropic `tool_use_id` ↔ `tool_result.tool_use_id` | OpenAI `tool_call.id` ↔ `tool.tool_call_id` | Gemini `functionCall.id` ↔ `functionResponse.id` |

> OpenAI / Gemini 协议转换层 OQ-Future — 本节仅占位,具体 `OpenAiLlmProvider` / `GeminiLlmProvider` 实现等独立 Story 启动时再补。

### 补丁契约(Story #027a + #027b 实施期严格遵守)

`AnthropicLlmProvider` 协议层改动严格限制在 **2 private method + 1 接口签名扩**:

| 改动 | 文件 | Story |
|---|---|---|
| `buildRequestBody(Prompt)` 序列化 `messages[].content` 为 array of blocks + 顶层 `tools:[]` 字段 | `AnthropicLlmProvider.java` modify | `#027a` |
| `parseResponse(String body)` 解析 `content[]` 多类型 block → `LlmResponse.text` + `LlmResponse.toolCalls` | `AnthropicLlmProvider.java` modify | `#027a` |
| 防御性校验缺 `id` / `name` 抛 `LingsLlmProviderException(LINGS_L01)` / 缺 `toolUseId` 抛 `LINGS_L02` | `AnthropicLlmProvider.java` modify + `LlmErrorCodes.java` new | `#027a` |
| `TurnContext.appendAssistant(String, Usage)` → `(String, List<ToolCall>, Usage)` 签名扩 1 参数 | `TurnContext.java` modify | `#027a` |
| `DefaultTurnContext.appendAssistant(...)` 实现对齐 | `DefaultTurnContext.java` modify | `#027a` |
| `LinearTurnEngine.L166` 真传 `resp.getToolCalls()` | `LinearTurnEngine.java` modify | `#027a` |
| `AnthropicLlmProvider` 改非流式 POST 为 SSE 流式 + `AnthropicStreamEvent` / `AnthropicStreamParser` + `input_json_delta` buffer | `AnthropicLlmProvider.java` modify + 2 new files | `#027b`(新窗口) |

### 关键不变项

- `Tool` interface 5 方法 / `ToolRegistry` interface 8 方法 / `ToolExecutor.dispatch()` 5 步流水线(`§4.10.1` 硬规则 2) / `Message` 5 子类 / `Prompt.tools` 契约 — **全部 0 改动**
- `AnthropicLlmProvider` 6-arg ctor + `doPost()` HTTP POST + 一次性 readAll 骨架(`#001` 落地)— **0 改动**(只在 2 private method 内做协议转换)
- `LinearTurnEngine` ReAct 主循环结构(L161-187 / L171-174 finish branch / L177 dispatchParallel / L181 appendToolResult / L184 done())— 0 改动(只 L166 1 行真传 toolCalls)
- dsh §15.4 域字母 L 启用:**L 段 1/2 号 = LINGS-L01 `LLM_PROTOCOL_TOOL_USE_INVALID` / LINGS-L02 `LLM_PROTOCOL_TOOL_RESULT_INVALID`**;L03+ 留 `#027b` + 后续 Story 顺延
- **0 新 Maven 依赖**(`MAPPER` = Jackson `ObjectMapper` 已锁 / `ToolSpec` / `Message.Assistant.toolCalls` / `Message.ToolUse` / `Message.ToolResult` 已存)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` POST,不走 ChatClient 自动执行
- R-13 mitigation (d) baseline 镜像 pre/post `mvn -pl lingshu-core dependency:tree` 仅时间戳差异,**0 binary delta 第 12 次 PASS**

### Story 路由

| Story | 范围 | ErrorCode | 依赖 |
|---|---|---|---|
| **#027a `anthropic-tool-protocol-conversion`**(本期)| 4 文件 modify + 2 文件 new(`LingsLlmProviderException` + `LlmErrorCodes`)+ 2 测试文件扩展 / 14 test case / 非流式 POST + 一次性 readAll | LINGS-L01 / LINGS-L02 | 无前置(`#024` 已合)|
| **#027b `anthropic-stream-tool-sse`**(新窗口)| 5 文件 modify + 2 文件 new(`AnthropicStreamEvent` + `AnthropicStreamParser`)+ 1 fixture / SSE 流式 + `input_json_delta` buffer + ToolCall/text block 交错状态机 | 0(复用 L01 / L02) | `#027a` |
| OpenAI 协议转换(OQ-Future)| `OpenAiLlmProvider.buildRequestBody` / `parseResponse` 真填 `tools:[]` + `tool_calls` array / `tool` role / `tool_call_id` 关联 | TBD(预留 L04+ 或新域)| `#027a` 协议层样板 |
| Gemini 协议转换(OQ-Future)| `GeminiLlmProvider.buildRequestBody` / `parseResponse` 真填 `functionDeclarations` + `functionCall` / `functionResponse` parts | TBD | `#027a` 协议层样板 |

### 反模式

- ❌ 把 `tools` 字段塞进 `Message` 内部(协议层字段应在 `Prompt` 顶层 / 响应在 `LlmResponse` 顶层)— `Message` 5 子类不可变契约
- ❌ 把 `tool_calls` / `functionCall` 字段翻译成 `LlmResponse.toolCalls` 之外的字段名(违反 `§4.6 ToolCall` 契约)
- ❌ 在 `AnthropicLlmProvider` 直接调 `tool.execute()` 绕过 `ToolExecutor.dispatch()`(违反 §4.10.1 硬规则 2)— 协议层只做 JSON 转换,dispatch 永远是 `LinearTurnEngine` 责任
- ❌ 流式协议与非流式协议并行两套 `AnthropicLlmProvider` 实现(违反 §11.4 边界)— `#027a` / `#027b` 拆 Story 但同仓同文件演进(`#027b` 在 `#027a` 基础上加 `doPostStream()` 分支)
- ❌ `LingsLlmProviderException` 复用 `LingsConfigException`(`#026` 引入)— 域不同需独立异常类(但**沿用** ErrorCode 嵌入 message 模式)

### 实施期检查清单

- [ ] 读 `AnthropicLlmProvider.buildRequestBody` L170-204 + `parseResponse` L208-247(确认 `Message.ToolUse` / `Message.ToolResult` 跳过注释 + `Collections.emptyList()` 返回)
- [ ] 读 `Message.java` L73-97(`Message.ToolUse` 5 字段 + `Message.ToolResult` 5 字段已就绪)+ `LlmResponse.java`(`getToolCalls() → List<ToolCall>` 已存空 list 占位契约)
- [ ] 读 `DefaultPromptBuilder.java` L183(`.tools(toolSpecs)` 已填,`#024` 落地)
- [ ] 读 `LinearTurnEngine.java` L161-187(ReAct 主循环 4 阶段:Plan → Action → Observation → Finish)+ L166(`ctx.appendAssistant` 当前 2-arg 签名)
- [ ] 实施期严格按 Story #027a plan.md §3 顺序:常量 → 异常类 → 协议转换 → 接口签名扩 → 实现对齐 → ReAct 透传
- [ ] AC-NN-1—AC-NN-7 + AC-NN-deps-1—AC-NN-deps-2 全过
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节必贴

---

**Section writer**: Claude Code(根据用户 2026-09-29 会话反馈,OQ-7 协议层 LLM 视角看不到 Tool 实测发现)
**Section date**: 2026-09-29
**Section version**: v0.1 Draft
**对应 dsh §**: §6.5 (1.5) 新增子节
**对应 Story**: #027a `anthropic-tool-protocol-conversion` + #027b `anthropic-stream-tool-sse`(新窗口)
