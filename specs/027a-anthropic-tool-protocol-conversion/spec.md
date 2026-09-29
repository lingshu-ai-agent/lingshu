# Story #027a `anthropic-tool-protocol-conversion` — Spec

> **Status**: Draft 2026-09-29
> **Source**: dsh v1.5.43 §6.5 protocol gap(实测发现 2026-09-26,`AnthropicLlmProvider.buildRequestBody` 显式 skip `Message.ToolUse` / `Message.ToolResult`,`parseResponse` 永返 `Collections.emptyList()` 给 `ToolCall`) + §4.6 `Tool` / §4.10.1 硬规则 2 / §15.4 ErrorCode 域 + constitution v1.0
> **前置依赖**:`#020a` Skill(`Prompt.tools` 字段契约)+ `#021b` MCP(`ToolRegistry.register / unregister`)+ `#022` `@AgentTool`(3 条 Tool Scheme 来源之一)+ `#024` `tool-schemas-integration`(`DefaultPromptBuilder` 注入 `ToolRegistry` → `Prompt.tools = toolRegistry.modelVisibleSpecs()`)— **4 个 Story 已合,`Prompt.tools` + `ToolRegistry.modelVisibleSpecs()` + ReAct Action `dispatchParallel` 全栈就绪**;**#027a 无 Story 前置,可独立实施**。
> **同 Story 拆解**:`#027a`(本期,非流式 POST + 一次性 readAll)+ **`#027b`(本期不实施,OQ-Future 阶段)**:SSE 流式 `text/event-stream` + `input_json_delta` buffer 拼接 + ToolCall/text block 交错状态机

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.43 §6.5(协议层)— Anthropic `/v1/messages` 协议:`messages[].content` 是 array of blocks(`text` / `tool_use` / `tool_result`),顶层有 `tools: [{name, description, input_schema}]`,`system` 字段独立。**当前 `#001` 实施的 `AnthropicLlmProvider.buildRequestBody` 显式 skip `Message.ToolUse` / `Message.ToolResult`(L170-204),`parseResponse` 永返 `Collections.emptyList()` 给 `ToolCall`(L208-247)**。
- **实测发现**:2026-09-26 审 `AnthropicLlmProvider.java` 时发现 4 段链路全断 — (1) `DefaultPromptBuilder.build` 已填 `Prompt.tools`(Story #024 已落地),(2) `AnthropicLlmProvider.buildRequestBody` 不读 `Prompt.tools`、(3) `parseResponse` 不解析 `content[]` 中的 `tool_use` block、(4) `DefaultTurnContext.appendAssistant` 硬编码 `<ToolCall>emptyList()` + `LinearTurnEngine.L166` 不传 `resp.getToolCalls()`,导致 ReAct Action `dispatchParallel` 永远拿空 list,LLM 视角看不到任何 Tool(4 个 Read/Write/Edit/Bash + N 个 MCP + N 个 @AgentTool)。
- **对应风险**: **OQ-7**(协议层 Tool 全链路不工作,LLM 看到 0 tool)+ R-13 mitigation (d) 强制自查
- **涉及 ErrorCode**: **LINGS-L01 `LLM_PROTOCOL_TOOL_USE_INVALID`**(`tool_use` 块缺 `id` / `name` / `input`,**L 段 1 号** LlmProvider 域启用,自 `#026` 后首次启用新 ErrorCode 域)+ **LINGS-L02 `LLM_PROTOCOL_TOOL_RESULT_INVALID`**(`tool_result` 缺 `tool_use_id` / `content`)

---

## 1. WHY(为什么做这个 Story)

**核心问题**:LingShu 已落地 4 条 Tool Scheme 来源(hand-written `#019` / MCP `#021b/c` / `@AgentTool` `#022` / `RemoteAgentTool` `#009c/d/e`),`ToolRegistry.modelVisibleSpecs()` 已能吐出所有 model-visible tool(`#024` 落地),`DefaultPromptBuilder.build` 已把 toolSpecs 灌进 `Prompt.tools`(`#024` 落地),ReAct Action `LinearTurnEngine.dispatchParallel(resp.getToolCalls(), ctx, sink)`(`#004` 落地)已能并行 dispatch,`ToolExecutor.dispatch()` 5 步流水线(`§4.10.1` 硬规则 2)能正确串入 `PermissionPolicy` / `TimeoutWrap` / `SandboxApply` / `Checkpoint`。**整条 Tool 链路从 LLM 视角 → ToolExecutor → Tool 实现全栈就绪,仅 Anthropic 协议层一处断裂**。

**4 段链路 gap 详述**:

1. **`AnthropicLlmProvider.buildRequestBody(Prompt)`** — L170-204 实现 `messages[]` 序列化时,只走 `User` / `Assistant.text` / `System` 三类 message;遇到 `Message.Assistant.toolCalls` 跳过(`// Message.ToolUse / Message.ToolResult — skipped in Story #001.`);`Prompt.tools` 字段(`#024` 落地后 List<`ToolSpec`> 非空)也没翻译成 Anthropic 顶层 `tools: [{name, description, input_schema}]`。**结果**:LLM 收到的请求 `tools: []`(空数组或字段缺),prompt `[TOOL SCHEMAS]` 段也是空 — LLM 看不到任何 tool,无法发起 tool_use。

2. **`AnthropicLlmProvider.parseResponse(String body)`** — L208-247 解析 Anthropic 响应时,只抽 `content[0].text`;遇到 `type=tool_use` block 跳过(`// Tool calls — Story #001 demo has no tools; Story #009 will parse content[] tool_use blocks.`)。**结果**:`LlmResponse.getToolCalls()` 永远返回 `Collections.emptyList()`(`#001` 显式 `new ArrayList<>()` 后 unmodifiable),`LinearTurnEngine.L166` 检测到 toolCalls 空 → 自然 break,ReAct Action 阶段根本不进。

3. **`TurnContext.appendAssistant(String text, Usage usage)`** — `DefaultTurnContext.L101` 硬编码 `new AssistantMessage(text, emptyList(), usage)`,**toolCalls 参数从签名层缺**。**结果**:即使 #1/#2 修好,append 进去的 Assistant message 也没 toolCalls,下一轮 LLM 看不到上一轮自己发起的 tool_use → 多轮 Tool 协作彻底断。

4. **`LinearTurnEngine.L166`** — `ctx.appendAssistant(resp.getText(), resp.getUsage())` 只传 text+usage,**不传 `resp.getToolCalls()`**。**结果**:即使 #3 修好签名,这行也不传 → toolCalls 仍为空。

**业务后果**:
- Agent 完全无 Tool 能力 — 即使 yml 配了 10 个 MCP server + 5 个 `@AgentTool` 注解 + 4 个手写 Tool + 3 个 RemoteAgentTool,LLM 看到 prompt 里 `tools: []`,ReAct 永远只走"text → text",Tool 子系统死代码
- Demo-product(`#025`)+ Demo-product-a2a-server(`#025b`)+ `#020a/b/c` Skill 注册的 Tool,**当前全部对 LLM 不可见**
- `ReAct Loop` 退化为普通 chat loop,产品价值链最关键的差异化能力完全失效

**Story #027a 业务价值**:
- 修通 Anthropic `/v1/messages` 协议层的 Tool 双向转换(LLM → Provider:发 `tools:[]` + `tool_result` block / Provider → LLM:回 `tool_use` block),ReAct Action 阶段真发并行 tool_call,`ToolExecutor.dispatch()` 5 步流水线真串入,**Tool 子系统从"死代码"复活为"业务差异化核心能力"**
- 与 dsh §6.5 protocol gap 章节补漏:`#027a` 落非流式路径(简单 `POST` + 一次性 `readAll`),`#027b` 落流式路径(`text/event-stream` + `input_json_delta` buffer)— 拆 Story 各守 §11.4 边界 ≤ 5 文件 ≤ 3 ErrorCode
- LlmProvider 域 **L 段 ErrorCode 启用**(`LINGS-L01` / `LINGS-L02`),为后续 `LINGS-L03`(流式断流 / SSE 解析失败)+ `LINGS-L04`(流式 SSE buffer 拼接超时)等预留段号

**关键不变项**:
- `Tool` interface 5 方法 / `ToolRegistry` interface 8 方法 / `ToolExecutor.dispatch()` 5 步流水线(`§4.10.1` 硬规则 2) / `Message` 5 子类(`User` / `Assistant` / `System` / `ToolUse` / `ToolResult`) / `Prompt.tools` 契约 — **全部 0 改动**
- `AnthropicLlmProvider` 6-arg ctor + `doPost()` HTTP POST + 一次性 readAll 骨架 **0 改动**(只在 `buildRequestBody` / `parseResponse` 两个 private method 内做协议转换,**Spring AI `ChatClient.tools().call()` 仍禁止使用**)
- `LinearTurnEngine.L161-187` ReAct 主循环结构 / `TurnContext` 接口其他方法 / `LlmResponse.getToolCalls()` 已存 `List<ToolCall>`(`#001` 已落空 list 占位契约,`#027a` 真填非空)— 0 改动
- **0 新 Maven 依赖**(`MAPPER` = Jackson `ObjectMapper` 已锁 / `ToolSpec` 已存 / `Message.Assistant.toolCalls` 已存 / `Message.ToolUse` / `Message.ToolResult` 已存 `Message.java` L73-97)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 给 Agent 配 `agent.llm.name=anthropic` 后,**Tool 立即可见可调**(无需切到 mock / OpenAI 调试)— 业务方法用 `@AgentTool` 注解 / MCP server 暴露的 tool / 手写 Tool 全部在 ReAct 循环中可用 |
| **Agent 框架贡献者 / 插件作者(Bob 类)** | 写 `Tool implements Xxx` 或 `@AgentTool` 注解方法,**协议层不再屏蔽** — LLM 通过 `[TOOL SCHEMAS]` 段看到 tool,ReAct Action 阶段真发 `tool_use`,走 `ToolExecutor.dispatch()` 5 步流水线,沙箱 / 权限 / checkpoint 全链路生效 |
| **运维稳定性关注者(Eve 类)** | Anthropic 协议错误走 `LINGS-L01`(`tool_use` 缺 id/name) / `LINGS-L02`(`tool_result` 缺 tool_use_id)`ToolResult.error`,**LLM 看到 error message 后可重试或换策略**;不会绕过 `PermissionPolicy.check()` / `SandboxApply` / `Checkpoint` 任一步(§4.10.1 硬规则 2);**多轮 Tool 协作状态可观察**(`#020` audit log 路径已就绪,Tool call 记录埋点可用) |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `buildRequestBody` `tools:[]` 顶层 + `messages[].content` blocks 三类(text / tool_use / tool_result);L2 slice 跑完整链路 Agent.runBlocking → ReAct Action → Anthropic API mock → `ToolExecutor.dispatch` → 反射调 `@AgentTool` → `ToolResult.success`;**R-13 mitigation (d) baseline 镜像** pre/post `mvn -pl lingshu-core dependency:tree` 仅时间戳差异,0 binary delta 第 12 次验证 |
| **A2A 集成方(Diana 类)** | `RemoteAgentTool`(`#009c/d/e`)接入的 remote agent skill,在主 agent LLM 视角下**通过协议层修复也能正确看到 + 调** — `#024` 已落 `modelVisibleSpecs()` 暴露 remote tool,**协议层不修复则 LLM 看不到**,`#027a` 解锁 A2A cross-JVM Tool 协作闭环 |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**(非流式路径,`POST https://api.anthropic.com/v1/messages` + 一次性 readAll):

1. **`AnthropicLlmProvider.buildRequestBody(Prompt)`** 改造:
   - 新增 `tools: [{name, description, input_schema}]` 顶层字段 —— 从 `Prompt.tools`(`List<ToolSpec>`)逐个映射,`input_schema` 直接复用 `ToolSpec.inputSchema()`(已存 Jackson `ObjectNode`)
   - `messages[]` 序列化时:**每条 message 的 `content` 字段从单一 string 改为 array of blocks**:
     - `Message.User` → `[{type: "text", text: "<content>"}]`
     - `Message.Assistant`(`text` 字段非空)+ `toolCalls` 空 → `[{type: "text", text: "<text>"}]`
     - `Message.Assistant`(`toolCalls` 非空)+ `text` 空 → `[{type: "tool_use", id: "<id>", name: "<name>", input: <argsJsonNode>}, ...]`
     - `Message.Assistant`(`text` 非空 + `toolCalls` 非空)→ `[{type: "text", text: "<text>"}, {type: "tool_use", ...}, ...]`(text block 在前,tool_use 块在后)
     - `Message.ToolResult` → `[{type: "tool_result", tool_use_id: "<id>", content: "<content>", is_error: <isError>}]`
     - 防御:`tool_use` 块缺 `id` 或 `name` → 抛 `LingsLlmProviderException(LINGS_L01)`(`#027a` 不消费 ToolResult 内部,ToolResult 来源是 `#020a` Skill/MCP/@AgentTool 反射,自身有校验)
2. **`AnthropicLlmProvider.parseResponse(String body)`** 改造:
   - 解析响应 `content[]` array(可能含多类型 block):
     - `type=text` → 累积到 `text` 字段(尾部 concat)
     - `type=tool_use` → 抽 `id` + `name` + `input` → 构造 `ToolCall(id, name, args)` 加进 `List<ToolCall>`
     - 防御:`tool_use` 块缺 `id` 或 `name` 或 `input` → 抛 `LingsLlmProviderException(LINGS_L01)`
   - 响应 `stop_reason=tool_use` → 触发 ReAct Action 阶段(`LinearTurnEngine` 检测 `resp.getToolCalls() != empty` 走 `dispatchParallel`)

3. **`TurnContext` 接口 + `DefaultTurnContext` 实现** 改造:
   - `appendAssistant` 签名扩 `toolCalls` 参数:`void appendAssistant(String text, List<ToolCall> toolCalls, Usage usage);`
   - `DefaultTurnContext.appendAssistant(text, toolCalls, usage)` 实现:`new Message.Assistant(text, toolCalls, usage)`(`#001` 已存 3-arg ctor + `@Value` 不可变)

4. **`LinearTurnEngine` L166** 改造:
   - `ctx.appendAssistant(resp.getText(), resp.getToolCalls(), resp.getUsage())` 替换原 `ctx.appendAssistant(resp.getText(), resp.getUsage())`

**新配置参数**(`AgentConfig` 当前无需变更;Anthropic provider 已有 `baseUrl` / `apiKey` / `anthropicVersion` / `model` / `maxTokens` / `temperature` 6 字段):

无新增。Tool 列表完全由 `ToolRegistry.modelVisibleSpecs()` 注入(`#024` 落地),用户在 `application.yml` 配 `agent.tools.read.enabled=true` / `agent.tools.write.enabled=true` 等(`#019` 样板)控制可见性。

**用户会看到的错误码**:

| ErrorCode | 抛出位置 | 触发条件 | 用户响应 |
|---|---|---|---|
| **LINGS-L01 `LLM_PROTOCOL_TOOL_USE_INVALID`** | `AnthropicLlmProvider.buildRequestBody` 序列化 `Message.Assistant.toolCalls` 时 / `parseResponse` 解析 `content[].tool_use` 时 | `tool_use` 块缺 `id` 或 `name`(`buildRequestBody` 出消息时 ToolCall 字段不全 / `parseResponse` 遇 Anthropic 异常响应) | 检查 LLM 输出 / ToolCall 来源(`@AgentTool` 反射失败路径已用 LINGS-T08,本码是协议层防御)|
| **LINGS-L02 `LLM_PROTOCOL_TOOL_RESULT_INVALID`** | `AnthropicLlmProvider.buildRequestBody` 序列化 `Message.ToolResult` 时 | `tool_result` 块缺 `tool_use_id` 或 `content`(ToolResult 来源是 `#020a` Skill/MCP 反馈,本码是协议层防御) | 检查 Tool 反馈链路 / MCP server 输出 / Skill 实现 |

**`LingsLlmProviderException`** 新增(`#027a` 单 ErrorCode 异常类,RuntimeException 子类,`errorCode` 字段)— 协议层错误的统一抛出方式,**避免污染 `Message` / `ToolResult` 的 immutable contract**(`ToolResult` 不可变,无 `errorCode` 字段;LINGS-T01-T08 已用 `[LINGS-TXX]` 字符串嵌入 content 模式,`#027a` 沿用同款 — `LingsLlmProviderException` 内 `getMessage()` 已带 `[LINGS-L0X]` 前缀,抛出时被 `AnthropicLlmProvider.doPost()` catch-all 转 `LlmResponse.error(errorCode, message)`(`#001` 已存 error path)).

**关键不变量**(不变项):
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 / `Message` 5 子类 / `Prompt.tools` 契约 / `LlmResponse.getToolCalls()` 契约 / `LlmResponse.getStopReason()` 契约 — **全部 0 改动**
- `AnthropicLlmProvider` 6-arg ctor + `doPost()` HTTP POST + 一次性 readAll 骨架 — **0 改动**
- `LinearTurnEngine` ReAct 主循环结构(L161-187 / L171-174 finish branch / L177 dispatchParallel / L181 appendToolResult / L184 done())— 0 改动
- `Spring AI ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` POST,不走 ChatClient 自动执行
- dsh §15.4 域字母 L 启用:**L 段 1/2 号 = LINGS-L01 / LINGS-L02**;L03+ 留 `#027b` + 后续 Story 顺延

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — `buildRequestBody` 顶层 `tools:[]` 正确翻译

**Given** 一个 `Prompt` 内含 3 个 `ToolSpec`(`read_file` / `write_file` / `@AgentTool` 注解方法 `createOrder`),每个 `ToolSpec.inputSchema()` 是合法 JSON Schema
**When** `AnthropicLlmProvider.buildRequestBody(prompt)`
**Then** 输出 JSON 顶层有 `"tools": [...]` 数组,长度 3,每元素 `{name, description, input_schema}` 3 字段,`name` 严格匹配 `ToolSpec.name()`,`input_schema` 内容**逐字段**与 `ToolSpec.inputSchema()` 一致(断言用 Jackson `MAPPER.readTree()` 后 `assertThat(...).isEqualTo(...)`)
**断言方式**:L1 Unit 测试,直接调 private `buildRequestBody`(反射或 package-private 提升)

### AC-NN-2 — `messages[].content` array of blocks(text + tool_use + tool_result 三类)

**Given** 一个 `Prompt` 内 conversation history 含 5 条 message:
- `User(text="列出 /tmp 下的所有文件")`
- `Assistant(text="", toolCalls=[ToolCall(id="call-1", name="bash", args={"cmd":"ls /tmp"})])`
- `ToolResult(toolUseId="call-1", content="a.txt\nb.txt\n", isError=false)`
- `Assistant(text="以下是文件列表")`
- `User(text="把 a.txt 复制到 /tmp/c.txt")`

**When** `AnthropicLlmProvider.buildRequestBody(prompt)`
**Then** 输出 JSON `messages[]` 长度 5,每条 `content` 字段类型与 block 分布:
- msg[0].content = `[{type:"text", text:"列出 /tmp 下的所有文件"}]`
- msg[1].content = `[{type:"tool_use", id:"call-1", name:"bash", input:{"cmd":"ls /tmp"}}]`
- msg[2].content = `[{type:"tool_result", tool_use_id:"call-1", content:"a.txt\nb.txt\n", is_error:false}]`
- msg[3].content = `[{type:"text", text:"以下是文件列表"}]`
- msg[4].content = `[{type:"text", text:"把 a.txt 复制到 /tmp/c.txt"}]`

**断言方式**:L1 Unit 测试,逐字段 `assertThat(MAPPER.readTree(actual).get("messages").get(i).get("content")).isEqualTo(expected)` 5 条

### AC-NN-3 — `parseResponse` 解析 `content[].tool_use` block → `ToolCall` 列表

**Given** Anthropic API mock 返回 JSON 响应:
```json
{
  "id": "msg-1", "type": "message", "role": "assistant",
  "content": [
    {"type": "text", "text": "I'll list files now"},
    {"type": "tool_use", "id": "toolu_1", "name": "bash", "input": {"cmd": "ls /tmp"}}
  ],
  "stop_reason": "tool_use", "usage": {"input_tokens": 10, "output_tokens": 5}
}
```
**When** `AnthropicLlmProvider.parseResponse(bodyJson)`
**Then** 返回 `LlmResponse(text="I'll list files now", toolCalls=[ToolCall(id="toolu_1", name="bash", args={"cmd":"ls /tmp"})], stopReason=TOOL_USE, usage=Usage(10, 5))`
**断言方式**:L1 Unit 测试,逐字段断言 `text` / `toolCalls[0].id` / `toolCalls[0].name` / `toolCalls[0].args` / `stopReason`

### AC-NN-4 — `parseResponse` 防御性校验缺 id/name 抛 LINGS-L01

**Given** Anthropic API mock 返回 JSON 响应:
```json
{
  "content": [{"type": "tool_use", "name": "bash", "input": {}}],
  "stop_reason": "tool_use"
}
```
(故意缺 `id` 字段)
**When** `AnthropicLlmProvider.parseResponse(bodyJson)`
**Then** 抛 `LingsLlmProviderException`,`exception.getMessage()` 含 `[LINGS-L01]`(§15.4 ErrorCode 编码约束,沿用 `LINGS-C02` 嵌入 message 模式),**且 `parseResponse` 不返任何残缺 `LlmResponse`**
**断言方式**:L1 Unit 测试,`assertThatThrownBy(() -> provider.parseResponse(badJson)).isInstanceOf(LingsLlmProviderException.class).hasMessageContaining("LINGS-L01")`

### AC-NN-5 — `buildRequestBody` 序列化 `Message.Assistant.toolCalls` 缺 id/name 抛 LINGS-L01

**Given** 一个 `Prompt` 内 conversation history 含 1 条 message:
- `Assistant(text="", toolCalls=[ToolCall(id="", name="bash", args={"cmd":"ls /tmp"})])`
(故意 `ToolCall.id=""` 空字符串)
**When** `AnthropicLlmProvider.buildRequestBody(prompt)`
**Then** 抛 `LingsLlmProviderException`,`exception.getMessage()` 含 `[LINGS-L01]`
**断言方式**:L1 Unit 测试,`assertThatThrownBy(() -> provider.buildRequestBody(badPrompt)).isInstanceOf(LingsLlmProviderException.class).hasMessageContaining("LINGS-L01")`

### AC-NN-6 — `buildRequestBody` 序列化 `Message.ToolResult` 缺 toolUseId/content 抛 LINGS-L02

**Given** 一个 `Prompt` 内 conversation history 含 1 条 message:
- `ToolResult(toolUseId="", content="ok", isError=false)`
(故意 `toolUseId=""` 空字符串)
**When** `AnthropicLlmProvider.buildRequestBody(prompt)`
**Then** 抛 `LingsLlmProviderException`,`exception.getMessage()` 含 `[LINGS-L02]`
**断言方式**:L1 Unit 测试,同上模式

### AC-NN-7 — 端到端 ReAct Action `dispatchParallel` 真跑(L2 slice)

**Given** 一个完整 `Agent` 配置(`AnthropicLlmProvider` + Mock HTTP server 返回 AC-NN-3 JSON + `ToolRegistry` 含 1 个 mock `BashTool implements Tool`,`name="bash"`)+ `LinearTurnEngine` + `ToolExecutor` + `TurnContext`
**When** `agent.runBlocking("列出 /tmp 文件")`
**Then** 走通完整链路:`buildRequestBody` 含 `tools:[{name:"bash",...}]` → Mock HTTP 返 AC-NN-3 响应 → `parseResponse` 返 `LlmResponse(toolCalls=[bash])` → `LinearTurnEngine.L166` 调 `ctx.appendAssistant(text, toolCalls, usage)` → L171 finish branch 检测 toolCalls 非空 → L177 `dispatchParallel(toolCalls, ctx, sink)` → `ToolExecutor.dispatch(bash)` 5 步流水线 → `BashTool.execute()` → `ToolResult.success` → L181 `appendToolResult` → 下次 loop send 第二个 Anthropic request 含 `tool_result` block → mock 返 text 响应 → 自然 break
**断言方式**:L2 slice,Mock HTTP server(`com.sun.net.httpserver.HttpServer` JDK built-in) + `StubAgentFactory`(沿用 `#023` 模式绕开 6-Router ctor)+ 真实 `AgentConfigRegistry.publish` + AssertJ 断言 BashTool 调用 1 次 + `ctx.appendAssistant` toolCalls 非空 + 最终 text 响应被 LLM stream 到 caller

### AC-NN-8 — R-13 mitigation (d) 依赖零增量

**Given** Story #027a 引入 `LingsLlmProviderException` + `AnthropicLlmProvider` 协议层改造 + `TurnContext` 签名扩 + `LinearTurnEngine.L166` 修
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#026` pre-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;`banned-dependencies` enforcer 不 fail
**断言方式**:对照 `specs/026-yaml-placeholder-resolution/` PR body 末尾的 `### R-13 dependency:tree 自查` 节

### AC-NN-deps-1(R-13 mitigation (d) — 强制)

**Given** 当前 Story 引入 / 修改依赖
**When** 跑 `mvn dependency:tree -pl lingshu-core -Dverbose`
**Then** 输出中**必须不包含** `banned-dependencies` 列表的任何条目,关键子树(>= 3 层的 `spring-ai-*` / `com.fasterxml.jackson.*` 版本冲突对)贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节

### AC-NN-deps-2(R-13 mitigation (d) — 强制)

**Given** Story #027a 引入 Jackson `ObjectNode` / `ArrayNode` 协议序列化等
**When** `mvn -pl lingshu-core verify` 跑 enforcer
**Then** `banned-dependencies` 规则**必须在 build 阶段 fail**(若依赖没碰,run 配置 `enforcer.skip=true` 显式跳过 + 在 PR body 说明)

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| SSE 流式(`text/event-stream`)改造 | `#027a` 边界 = 非流式 POST + 一次性 readAll;流式拆给 `#027b`(`#027a` 实施期开新窗口) — 否则会破 §11.4 Story 边界 ≤ 5 文件约束 |
| 多 Tool 并行的 Anthropic 协议优化(并行 tool_use 一次发出)| dsh §6.5 协议层支持单 message 内多 tool_use blocks(`#027a` 已实现),但 LLM 决策由 provider 决定,`#027a` 不改 LLM 行为 — 留给 OQ-Future 性能优化 |
| `Message.Assistant.toolCalls` 为空 + text 非空时省略 `text` block | 严格按 dsh §6.5 协议层:即使 text 短也发 `{type:"text", text:""}` 空 block(Anthropic 协议允许) — 不做隐式省略,避免协议侧歧义 |
| `Message.Assistant.text=""` + `toolCalls=[]` 时整条 message 丢弃 | 防御性兜底:`#027a` 仍发 `[{type:"text", text:""}]` 空 content(Anthropic 协议必填 content 字段) — OQ-Future 可加 provider 优化 |
| `ToolResult.isError=true` 时 content 用 `error` 字段而非 `content` | 严格按 dsh §6.5:Anthropic 协议 `is_error` 是 boolean flag,`content` 字段照常 — 不做字段重命名 |
| `Message.System` 复用单 message(`system: "..."` 顶层字段)vs 多 message 形式 | `#001` 已用 Anthropic `system` 顶层字段;`#027a` 不改 — 留给 OQ-Future |
| `usage` 字段逐项映射(input_tokens / output_tokens / cache_creation_input_tokens / cache_read_input_tokens)| `#001` `Usage` 类只有 input/output 2 字段;`#027a` 严格 1:1 映射 — cache tokens 留给后续 cache 控制 Story |
| 重试 / 熔断 / 超时(`HTTP` retry / `circuit breaker`) | `ToolExecutor.dispatch()` 第 3 步 `TimeoutWrap` 统一管;`HttpURLConnection.setReadTimeout(60s)` 由 `#001` 已配;`#027a` **不**重复实现 — 与 §4.10.1 §14.2 RetryPolicy 兼容 |
| `Anthropic prompt caching`(`cache_control: {type: "ephemeral"}` blocks)| OQ-Future,留 `Message` 后续扩展 cache 字段时再补 |
| `Anthropic extended thinking`(`thinking: {type: "enabled", budget_tokens: N}`)| OQ-Future,留 `LlmRequest` / `Prompt` 后续扩展 thinking 字段时再补 |
| 多模态(`type: "image"` / `type: "document"` blocks) | `#001` `Message.User.content` 是 String(非 MultimodalContent),不支持图片 / 文档 — OQ-Future 留 `Message` multimodal 字段扩展 Story |
| Schema 生成走 `jackson-module-jsonSchema` | `#022` `SpringAiToolAdapter` 走简化路径(inputSchema 反射生成);`AnthropicLlmProvider` 不读 schema 内部,只透传 `ToolSpec.inputSchema()` — 不引入新 Schema 生成依赖 |
| Agent-level `agent.llm.anthropic.tools.enabled: true/false` 总开关 | 默认全开;`Prompt.tools` 由 `#024` `modelVisibleSpecs()` 决定,`@AgentTool` / MCP / 手写 Tool 各自 `enabled` flag 控制可见性(`#019` 已有) — config field 是 over-engineering |
| `LINGS-L03+` 任何 ErrorCode | `#027a` 仅 L01 + L02;`#027b` 留 L03+(流式断流 / SSE 解析失败 / buffer 拼接超时) |
| `LingsLlmProviderException` 复用 `LingsConfigException`(`#026` 引入) | `LingsConfigException` 域是 Config(YAML 解析 / 校验);`#027a` 错误域是 LlmProvider(协议序列化),**域不同需独立异常类** — 但**沿用** ErrorCode 嵌入 message 模式(`[LINGS-L0X]` 前缀),保持 AssertJ `hasMessageContaining` 链一致 |

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#001` zero-config-bootstrap — `AnthropicLlmProvider` 6-arg ctor + `doPost()` POST 骨架 + `Message` 5 子类 + `Usage` + `LlmResponse.getToolCalls()` 占位空 list
  - `#004` tool-parallel-dispatch — `LinearTurnEngine.dispatchParallel` + `ToolExecutor.dispatch()` 5 步流水线
  - `#019` built-in-tools — `Read / Write / Edit / Bash` 4 个手写 `Tool` 实现(AC-NN-7 端到端测试用 `BashTool` mock)
  - `#020a` skill-foundation — `ToolRegistry.modelVisibleSpecs()` `List<ToolSpec>` 契约
  - `#021b` mcp-tool-adapter — `ToolRegistry.register / unregister` SPI
  - `#022` spring-ai-annotation-tool — `@AgentTool` 注解 + `SpringAiToolAdapter`(3 条 Tool Scheme 来源之一)
  - `#024` tool-schemas-integration — `DefaultPromptBuilder` 注入 `ToolRegistry` → `Prompt.tools` 真填 `modelVisibleSpecs()`
- **后续 Story(本 Story 是其前置)**:
  - `#027b` anthropic-stream-tool-sse — 接续 `#027a`,SSE 流式 `text/event-stream` + `input_json_delta` buffer + ToolCall/text block 交错状态机(本期不实施,OQ-Future 阶段)
  - `#024 follow-up 后续` — `agent.llm.anthropic.thinking.enabled` / `cache_control` 等 Anthropic-specific 配置(`#027a` 留接口空间,OQ-Future 评估)
  - §14.10 N10 audit-log — `AuditLogger` 记录 `tool_use` / `tool_result` block + `usage` 字段

---

**Spec writer**: Claude Code
**Spec date**: 2026-09-29
**Spec version**: v0.1 Draft
