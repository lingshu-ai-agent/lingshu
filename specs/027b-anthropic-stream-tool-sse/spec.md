# Story #027b `anthropic-stream-tool-sse` — Spec

> **Status**: Draft 2026-09-30
> **Source**: dsh v1.5.44 §6.5 protocol gap(接续 #027a 协议层 Tool 修复,2026-09-26 实测发现 `AnthropicLlmProvider.stream()` 方法虽取名 `stream` 但实际是非流式 POST + 一次性 `readAll`,**未真正走 SSE `text/event-stream` 流式路径**) + §4.6 Tool / §4.10.1 硬规则 2 / §15.4 ErrorCode 域 + constitution v1.0 + #027a `anthropic-tool-protocol-conversion`(前置)
> **前置依赖**:`#027a` `anthropic-tool-protocol-conversion`(非流式 POST + 一次性 readAll 协议转换已合 — `buildRequestBody` 顶层 `tools:[]` + `messages[].content` array of blocks + `parseResponse` 解析 `tool_use` block → `ToolCall`)+ `#024` `tool-schemas-integration`(`Prompt.tools` 真填 `modelVisibleSpecs()`)+ `#020a/b/c` Skill + `#021b/c` MCP + `#022` `@AgentTool` + `#009c/d/e` `RemoteAgentTool` —— **7 个 Story 已合**;**`#027b` 无 Story 前置**,可独立实施
> **同 Story 拆解**:`#027a` 已合(本期不实施,OQ-Future 阶段回看);`#027b` 落 SSE 流式 `text/event-stream` + `input_json_delta` 拼接 buffer + ToolCall/text block 交错状态机,复用 #027a `LINGS-L01 / LINGS-L02` 协议层 ErrorCode

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.44 §6.5(协议层) + §13 changelog v1.5.44 行 13 节 + §6.5 (1.5) 字面落地(`Story 路由` 表第 2 行 #027b)
- **实测发现**:2026-09-30 审 `AnthropicLlmProvider.java` 时发现 `stream(Prompt, TurnContext, Subscriber<AgentEvent>)` 方法虽签名像流式,**但内部仍是** `doPost(url, requestBody)` + `readAll(InputStream)` + `parseResponse(body, sink)`,**只发一次 `AgentEvent.TextDelta(text)` 在响应全部 readAll 完之后**(L397-399)。**LLM 流式首 token 性能预算 P50 ≤ 1.5s / P99 ≤ 3.0s**(constitution §3 NFR)在非流式路径上**完全失效** —— 用户在大模型长 prompt(>1K tokens)首 token 延迟 = 模型推理总时间 + 协议 RTT,而不是模型推理首 token 时间
- **协议约束**:Anthropic `/v1/messages` 流式响应是 Server-Sent Events(`text/event-stream`),事件类型 `message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop` 6 段;**`tool_use` 块的 `input` 字段是流式分段到达**(`input_json_delta.partial_json` 一个 JSON 字符串片段 → 拼接直到 `content_block_stop` 才 `MAPPER.readTree()`);**多 content block 交错**(text + tool_use + text + tool_use + ...),按 `index` 区分
- **对应风险**: **R-09**(transitive 依赖污染)+ **R-13** mitigation (d)(R-13 第 13 次 PASS 强制)+ §3 NFR 流式首 token 性能预算不达标
- **涉及 ErrorCode**:**0 新 ErrorCode**(本期复用 #027a `LINGS-L01 LLM_PROTOCOL_TOOL_USE_INVALID` / `LINGS-L02 LLM_PROTOCOL_TOOL_RESULT_INVALID` —— SSE 协议层解析 tool_use block 缺 id/name 仍走 L01);`LlmErrorCodes.LINGS_L03` 预留为 reserved(§14 N6 graceful shutdown 后续启用,本期不抛)

---

## 1. WHY(为什么做这个 Story)

**核心问题**:LingShu `AnthropicLlmProvider.stream()`(SLOT 1 LlmProvider 默认实现)表面看像流式,**实际是非流式 POST + 一次性 readAll**(L102-109 + L113-155 + L362-419)。这导致 3 个具体 gap:

1. **首 token 延迟不达标** —— constitution §3 NFR「LLM 流式首 token P50 ≤ 1.5s / P99 ≤ 3.0s」在非流式路径上完全失效。Anthropic API 在非流式模式下响应 = 模型生成完整 response 后才一次性返回,**首 token 延迟 = 完整生成时间 + 协议 RTT**,与 NFR 定义(首 token 响应时间)语义不匹配。LLM 长 prompt(>1K tokens)+ 长响应(>500 tokens)时,用户可见延迟 5-10s 远超 NFR
2. **流式 UX 不可用** —— `AgentEvent.TextDelta` 是 ReAct loop 的核心流式反馈载体(参考 `lingshu-examples/demo-product/` ChatController 的 SSE 流式响应,#025 落地),非流式 `stream()` **只发一次 TextDelta 在响应末尾**(L397-399),前端 UI 看不到打字机效果,用户体验降级为「白屏 → 整段出现」
3. **Tool 协作不可观察** —— ReAct Action 阶段 LLM 决定调 N 个 tool(`dispatchParallel` 一次发出 N 个 `ToolCall`),非流式响应里 LLM 一边 tool_use 一边 reasoning text 都被打包成一次性响应,**调用方看不到「reasoning → tool_use → final answer」过程**;后续 §14.10 N10 audit log 落地时,`text_delta` / `tool_use` block 顺序入账是基础设施前提

**业务后果**(当前 #027a 合入后状态):
- Demo-product(`#025`)+ Demo-product-a2a-server(`#025b`)的 SSE 流式响应只在 HTTP 协议上是 SSE,LLM 内部仍是黑盒
- `lingshu-examples/demo-product/` ChatController 收到的 LLM 响应是一次性大块,前端打字机效果是 mock 出来的(按 chunk 切分一次性响应,不是真流式)
- `LinearTurnEngine.L171-187` ReAct Action 阶段 LLM 调 tool 是「一次性看到所有 tool_use + 最终 text」,**LLM reasoning 思考过程对用户不可见**(典型 10 step ReAct loop,用户只能看到 10 次完整 LLM 响应,看不到「这个 tool_use 是因为前面观察到什么」)

**Story #027b 业务价值**:
- 修通 Anthropic `/v1/messages` 协议层的真 SSE 流式(LLM → Provider:`text/event-stream` accept / Provider → LLM:每 token 增量 `text_delta` + `input_json_delta`),LLM 流式首 token 满足 constitution §3 NFR(LLM 真首 token 时间而非 TTP + 网络 RTT)
- 流式 `AgentEvent.TextDelta` 持续发射(`message_start` 触发 `ReasoningStarted` + 每 `content_block_delta.text_delta` 触发 `TextDelta` + `content_block_start(type=tool_use)` 触发 `ToolStarted` + `message_stop` 收尾 + `message_delta.stop_reason` 解析 `StopReason`),ReAct loop 全过程对前端可见
- `input_json_delta` 拼接 buffer 状态机:每 tool_use 块独立 buffer,直到 `content_block_stop` 才 `MAPPER.readTree()` 一次,与 Anthropic 协议层硬约束对齐;多 tool_use block 交错支持(ReAct 一次发 N 个并行 tool_call 路径)
- 接续 #027a:`buildRequestBody` + `parseResponse` 的协议转换原样复用,只把 `parseResponse` 升级为 `parseStream` 增量解析,**`#027b` 与 `#027a` 同仓同文件演进**,不另起新 Provider

**关键不变项**:
- `Tool` interface 5 方法 / `ToolRegistry` interface 8 方法 / `ToolExecutor.dispatch()` 5 步流水线(`§4.10.1` 硬规则 2) / `Message` 5 子类(`User` / `Assistant` / `System` / `ToolUse` / `ToolResult`) / `Prompt.tools` 契约 / `LlmResponse` 5 字段(`text` / `toolCalls` / `stopReason` / `usage` / `errorCode`) / `Usage` 2 字段 / `StopReason` enum — **全部 0 改动**
- `AnthropicLlmProvider` 6-arg ctor / `buildRequestBody(Prompt)`(#027a 改造后)/ 顶层 `tools:[]` 字段 + `messages[].content` array of blocks 三类 — **0 改动**
- `LinearTurnEngine` ReAct 主循环结构(L161-187 / L171-174 finish branch / L177 dispatchParallel / L181 appendToolResult / L184 done()) + L166 真传 toolCalls(#027a 改造后)— **0 改动**
- `TurnContext.appendAssistant(text, toolCalls, usage)`(#027a 改造后)— **0 改动**
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(`§4.10.1` 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` 流式,**不走 ChatClient 自动执行**
- **0 新 Maven 依赖**(`MAPPER` Jackson 已锁 / `ToolSpec` / `Message.Assistant.toolCalls` / `ToolCall` 3 字段 / `AgentEvent.TextDelta` + `ReasoningStarted` + `ToolStarted` 已存 `#001` + `#008` / `HttpURLConnection.setChunkedStreamingMode` JDK 内置 / `InputStreamReader` + `BufferedReader` JDK 内置 / Anthropic SSE 协议手写 parser 与 #021c `SseMcpServerConnection` 同款手写模式)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 给 Agent 配 `agent.llm.name=anthropic` + `agent.llm.stream=true`(默认 true),**LLM 真流式首 token 满足 NFR P50 ≤ 1.5s**(constitution §3)+ `AgentEvent.TextDelta` 持续发射给前端 UI 打字机效果;`buildRequestBody`/`parseResponse` 协议转换与 #027a 一致,业务侧无感知 |
| **Agent 框架贡献者 / 插件作者(Bob 类)** | `LlmProvider.stream` 协作者可通过 `Subscriber<AgentEvent> sink` 收到完整 ReAct loop 事件流(`ReasoningStarted` / `TextDelta` / `ToolStarted` / `ToolCompleted` / `TurnCompleted` + #027a 已存的 `MaxStepsExceeded` / `ObservationAppended`);plugin 写 `LlmProviderProvider` 可基于 `AnthropicStreamParser` 拼装自定义 SSE 协议(复用状态机)|
| **运维稳定性关注者(Eve 类)** | 真 SSE 流式后,前端 UI 看到 `ReasoningStarted(step, maxSteps)` + 持续 `TextDelta` + `ToolStarted(toolCallId, name)` + `ToolCompleted(result)`,**完整 ReAct loop 状态可观察**;`ToolStarted` / `ToolCompleted` 在 §14.10 N10 audit log 路径直接复用(埋点已就位) |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `AnthropicStreamParser` 6 类 SSE event(`message_start` / `content_block_start` × text + tool_use / `content_block_delta` × text_delta + input_json_delta / `content_block_stop` / `message_delta` / `message_stop`) + 多 block 交错状态机(`Map<Integer, ToolCall.Builder>` 维护)+ `input_json_delta` 拼接直到 `content_block_stop` 才 parse;L2 slice 用 mock HTTP server(`com.sun.net.httpserver.HttpServer` JDK built-in)发 SSE 流,**真实读 InputStream 逐行解析**而非 readAll;**R-13 mitigation (d) baseline 镜像** pre/post `mvn -pl lingshu-core dependency:tree` 仅时间戳差异,0 binary delta 第 13 次验证 |
| **Demo-product 集成方(Diana 类)** | `lingshu-examples/demo-product/` ChatController(#025)SSE 流式响应从「LLM 响应一次性到达后按 chunk 切分」(mock 流式)升级为「LLM 真正每 token 到达即发射」(真流式);前端打字机效果不再需要 mock;**用户体验质变** |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**(SSE 流式路径,`POST https://api.anthropic.com/v1/messages` + `Accept: text/event-stream` + InputStream 逐行 readLine):

1. **`AnthropicLlmProvider.stream(Prompt, TurnContext, Subscriber<AgentEvent>)`** 改造:
   - 新增 `Accept: text/event-stream` 请求头
   - 把 `doPost(url, requestBody)`(一次性 readAll)替换为 `doPostStream(url, requestBody, sink)`:开 `HttpURLConnection` + 拿 InputStream + 按行 `\n` 切分 SSE event(每 event 是 `event: <type>\ndata: <json>\n\n` 三段格式,空行分隔)
   - 把 `parseResponse(body, sink)`(一次性 readTree 整个 body)替换为 `AnthropicStreamParser.parse(InputStream, sink)`:每读一行解析一个 SSE event,推到 `AnthropicStreamParser.feed(...)` 状态机
   - 状态机:`message_start` → emit `ReasoningStarted(step, maxSteps)` + init usage(关键);`content_block_start(index, type=text)` → `textBlocks.put(index, new StringBuilder())`;`content_block_start(index, type=tool_use, id, name)` → 防御缺 id/name 抛 `LINGS-L01`(`#027a` 已落)+ `toolBlocks.put(index, new ToolCall.Builder(id, name))` + emit `ToolStarted(id, name)`;`content_block_delta(index, delta.type=text_delta, text)` → `textBlocks.get(index).append(text)` + emit `TextDelta(text)`;`content_block_delta(index, delta.type=input_json_delta, partial_json)` → `toolBlocks.get(index).appendInput(partial_json)`;`content_block_stop(index)` → finalize: text 块 move 到 `textBuf` 拼接,tool_use 块 `MAPPER.readTree(toolBlocks.get(index).inputBuf.toString())` 构造 `ToolCall(id, name, input)` 加进 `List<ToolCall>`;`message_delta(delta.stop_reason)` → set `StopReason`(`tool_use` / `end_turn` / `max_tokens`);`message_stop` → 构造 `LlmResponse(text, toolCalls, reason, usage)` 返回
   - `Map<Integer, StringBuilder>` text blocks + `Map<Integer, ToolCall.Builder>` tool blocks 双 Map 交错状态机,处理 ReAct 多并行 tool_use + interleaved text 场景

2. **`AnthropicStreamEvent`** 新增:
   - 单个 SSE event 的不可变表示:`String type`(如 `message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop`)+ `JsonNode data`(MAPPER.readTree 后的 JSON 树)
   - 静态工厂 `AnthropicStreamEvent.parse(String rawSseLine)`:接收 SSE 协议 raw 多行字符串(以空行分隔的多行 `event:` + `data:` 段),拆 `event:` 行拿 type + 累 `data:` 行拿 raw JSON + 空行收尾 → 构造 `AnthropicStreamEvent`
   - JDK 8 兼容(Lombok `@Value` 不可变 + 静态工厂)

3. **`AnthropicStreamParser`** 新增:
   - 状态机封装:`Map<Integer, StringBuilder> textBlocks` + `Map<Integer, ToolCall.Builder> toolBlocks` + `StringBuilder textBuf` + `List<ToolCall> toolCalls` + `StopReason stopReason` + `Usage usage` 6 字段
   - `void feed(AnthropicStreamEvent event, Subscriber<AgentEvent> sink)` 单 event 推方法(每次推一个 event 走 if-else 分支更新状态 + 必要时 emit)
   - `LlmResponse finish()` 收尾方法(`message_stop` 后调,返回 `LlmResponse`)
   - 防御:`content_block_start(type=tool_use)` 缺 `id`/`name` 抛 `LingsLlmProviderException(LINGS_L01)`(复用 #027a 错误处理)+ `content_block_stop` 时 `tool_use` 块 `partial_json` 拼接后 `MAPPER.readTree()` 失败抛 RuntimeException(异常路径走 `doPostStream` catch-all 转 `LlmResponse.error`)
   - JDK 8 兼容(Lombok `@Value` 不可变 + 普通 Java 类)

4. **`LlmErrorCodes` 扩展**:
   - 新增 `LINGS_L03 = "LINGS-L03"` reserved 常量(本期**不抛**,预留给 §14 N6 graceful shutdown 后续启用 SSE 流式断流场景)
   - 注释引用 dsh §15.4 域字母 L 编号表(reserved 占位说明)

5. **`AnthropicStreamTestSupport`** 新增(测试 fixture):
   - 启动 JDK `com.sun.net.httpserver.HttpServer` + `ExecutorService.newCachedThreadPool` 后台线程发送 SSE 流
   - `static void startSseServer(int port, List<String> sseEvents, Consumer<String> requestBodyCapture)`:在 `port` 端口起 server,收到 POST 后 capture request body,按 `sseEvents` 列表逐 event 写 `event: <type>\ndata: <json>\n\n`(每 event 之间 `Thread.sleep(10)` 让 client 有时间读)+ 写完后 `connection.close()`
   - `static int findFreePort()` helper:用 `ServerSocket(0)` 拿空闲端口

**新配置参数**(`AgentConfig` 当前无需变更;Anthropic provider 已有 `baseUrl` / `apiKey` / `anthropicVersion` / `model` / `maxTokens` / `temperature` 6 字段):

- `AgentConfig.Llm.anthropicStreamEnabled` Boolean 字段新增**(可选,默认 true)** —— 关闭后 fallback 到 #027a 一次性 readAll 路径(供 unit test / demo 场景用)
- 配置位置:`agent.llm.anthropic.stream: false`(YAML),`@ConfigurationProperties("agent.llm") Llm.anthropicStreamEnabled = true`(Java config)

**用户会看到的错误码**:

| ErrorCode | 抛出位置 | 触发条件 | 用户响应 |
|---|---|---|---|
| **LINGS-L01 `LLM_PROTOCOL_TOOL_USE_INVALID`** | `AnthropicStreamParser.feed` 处理 `content_block_start(type=tool_use)` 时 | SSE 流里 `tool_use` block 缺 `id` 或 `name`(Anthropic API 异常响应 / 协议违规)| 检查 LLM 输出 / Anthropic API 行为(同 #027a)|
| **LINGS-L02 `LLM_PROTOCOL_TOOL_RESULT_INVALID`** | 本期**不抛**(SSE 是 LLM 响应流,不含 tool_result 块;tool_result 块在 request 路径,走 #027a `buildRequestBody`) | N/A | N/A |
| **`LINGS-L03` reserved**(本期**不抛**) | §14 N6 graceful shutdown 后续启用 | SSE 流中途断流 / 连接 reset / timeout | 检查网络 / Anthropic API status(后续 Story)|

**关键不变量**(不变项):
- `Tool` interface / `ToolRegistry` interface / `ToolExecutor.dispatch()` 5 步流水线 / `Message` 5 子类 / `Prompt.tools` 契约 / `LlmResponse` 5 字段契约 / `Usage` 2 字段契约 / `StopReason` enum / `ToolCall` 3 字段契约 — **全部 0 改动**
- `AnthropicLlmProvider` 6-arg ctor + `buildRequestBody(Prompt)`(#027a 改造后)+ 顶层 `tools:[]` 字段 + `messages[].content` array of blocks 三类 — **0 改动**
- `LinearTurnEngine` ReAct 主循环结构 + L166 真传 toolCalls(#027a 改造后)— 0 改动
- `TurnContext.appendAssistant(text, toolCalls, usage)`(#027a 改造后)— 0 改动
- `AgentEvent` 12 个子类 + `Subscriber<AgentEvent>` 契约 — **0 改动**(只新增 `TextDelta` / `ReasoningStarted` / `ToolStarted` 流式发射,**契约已存**于 `#001` + `#008`)
- dsh §15.4 域字母 L 编号表:`LINGS-L03` 标记为「reserved」(本期不抛,§14 N6 后续启用);L01 / L02 编号不动
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(`§4.10.1` 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` 流式,**不走 ChatClient 自动执行**

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — `stream()` 方法添加 `Accept: text/event-stream` 请求头

**Given** 一个完整 `Prompt` + `AnthropicLlmProvider` 配置(`baseUrl=http://localhost:<port>` + `apiKey=test`)
**When** `provider.stream(prompt, ctx, sink)` 被调用
**Then** 底层 `HttpURLConnection` 请求头包含 `"Accept": "text/event-stream"`(L121-127 区域)
**断言方式**:L2 slice,启动 `AnthropicStreamTestSupport` mock server,`requestBodyCapture` 回调里捕获 `request.getHeaders().getFirst("Accept")`,断言非空且 equals `"text/event-stream"`

### AC-NN-2 — `message_start` 事件触发 `ReasoningStarted` + init usage

**Given** Mock server 发送 SSE 流:
```
event: message_start
data: {"type":"message_start","message":{"id":"msg-1","type":"message","role":"assistant","content":[],"model":"claude-3-5-sonnet","usage":{"input_tokens":10,"output_tokens":0}}}

```
**When** `AnthropicStreamParser.feed(event, sink)` 处理 `message_start` event
**Then** `sink.onNext(new AgentEvent.ReasoningStarted(step, maxSteps))` 被调一次(此处 `step=1` / `maxSteps=50` 来自 `LinearTurnEngine` 注入 ctx,或 default 1/50)+ `usage` 初始化为 `Usage(10, 0)`
**断言方式**:L1 Unit 测试,`sink = mock(Subscriber.class)` + `verify(sink).onNext(any(AgentEvent.ReasoningStarted.class))` + parser 内部 `getUsage().getInputTokens() == 10`

### AC-NN-3 — text block 多个 `text_delta` 累积 + 每 delta 触发 `TextDelta`

**Given** Mock server 发送 SSE 流(单 content block index=0 type=text):
```
event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":" world"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

```
**When** `AnthropicStreamParser.feed(...)` 依次处理 4 个 event
**Then** `sink.onNext(new AgentEvent.TextDelta("Hello"))` + `sink.onNext(new AgentEvent.TextDelta(" world"))` 各被调一次(2 次 total,顺序匹配)+ 内部累积 `textBuf == "Hello world"`
**断言方式**:L1 Unit 测试,`InOrder` 验证 `sink.onNext(...)` 调用顺序 + parser 内部 `getText() == "Hello world"`

### AC-NN-4 — tool_use block 多个 `input_json_delta` 拼接 + `content_block_stop` 时 parse 完整 input

**Given** Mock server 发送 SSE 流(单 content block index=1 type=tool_use,input 分 3 段到达):
```
event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"bash","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"cmd\":"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"ls "}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"/tmp\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

```
**When** `AnthropicStreamParser.feed(...)` 依次处理 5 个 event
**Then** 内部累积 `inputBuf == "{\"cmd\":\"ls /tmp\"}"` + `content_block_stop` 时 `MAPPER.readTree(inputBuf)` 成功 + 构造 `ToolCall(id="toolu_1", name="bash", input={"cmd":"ls /tmp"})` 加进 `toolCalls` 列表 + 同时 `sink.onNext(new AgentEvent.ToolStarted("toolu_1", "bash"))` 在 `content_block_start` 时已发
**断言方式**:L1 Unit 测试,`parser.finish().getToolCalls()` 返回 list 长度 1 + `getToolCalls().get(0).getName() == "bash"` + `getToolCalls().get(0).getInput().get("cmd").asText() == "ls /tmp"`

### AC-NN-5 — text block 与 tool_use block 交错状态机

**Given** Mock server 发送 SSE 流(多 content block 交错:index=0 text → index=1 tool_use → index=2 text → index=3 tool_use,ReAct 一次发 2 个并行 tool_call + 中间 reasoning text):
```
event: content_block_start ... index=0 ... type=text
event: content_block_delta ... index=0 ... text_delta="Let me "
event: content_block_delta ... index=0 ... text_delta="check..."
event: content_block_stop ... index=0
event: content_block_start ... index=1 ... type=tool_use ... id="toolu_1" ... name="bash"
event: content_block_delta ... index=1 ... input_json_delta="{\"cmd\":\"ls\"}"
event: content_block_stop ... index=1
event: content_block_start ... index=2 ... type=text
event: content_block_delta ... index=2 ... text_delta="Also "
event: content_block_stop ... index=2
event: content_block_start ... index=3 ... type=tool_use ... id="toolu_2" ... name="read_file"
event: content_block_delta ... index=3 ... input_json_delta="{\"path\":\"/tmp/a.txt\"}"
event: content_block_stop ... index=3
event: message_delta ... stop_reason="tool_use"
event: message_stop
```
**When** `AnthropicStreamParser.feed(...)` 依次处理 14 个 event
**Then** `parser.finish().getText() == "Let me check...Also "`(text 块按 index 顺序累积)+ `parser.finish().getToolCalls()` 返回 list 长度 2(`[ToolCall(toolu_1, bash, {"cmd":"ls"}), ToolCall(toolu_2, read_file, {"path":"/tmp/a.txt"})]`)+ `parser.finish().getStopReason() == StopReason.TOOL_USE`
**断言方式**:L1 Unit 测试,断言 `getText()` / `getToolCalls()` 长度 2 + 每个 `ToolCall` 字段 + `getStopReason()`

### AC-NN-6 — SSE 解析防御性校验 tool_use 缺 id/name 抛 LINGS-L01

**Given** Mock server 发送 SSE 流(故意缺 `id` 字段):
```
event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","name":"bash","input":{}}}

```
**When** `AnthropicStreamParser.feed(event, sink)` 处理
**Then** 抛 `LingsLlmProviderException`,`exception.getMessage()` 含 `[LINGS-L01]`(对齐 #027a 嵌入 message 模式)
**断言方式**:L1 Unit 测试,`assertThatThrownBy(() -> parser.feed(badEvent, sink)).isInstanceOf(LingsLlmProviderException.class).hasMessageContaining("LINGS-L01")`

### AC-NN-7 — 端到端真 SSE 流式(LLM mock server 边发边读)

**Given** 一个完整 `Agent` 配置(`AnthropicLlmProvider` + Mock HTTP server 跑 2 thread(`HttpServer` 自带 executor)+ 后台 thread 按 `Thread.sleep(20)` 间隔发 SSE event + `ToolRegistry` 含 1 个 mock `BashTool implements Tool` `name="bash"`)+ `LinearTurnEngine` + `ToolExecutor` + `TurnContext`
**When** `agent.runBlocking("列出 /tmp 文件")`
**Then** 走通完整链路:`buildRequestBody` 含 `tools:[{name:"bash",...}]` + `Accept: text/event-stream` → Mock HTTP 流式发 AC-NN-3 + AC-NN-4 + AC-NN-5 完整事件序列(中间穿插 Thread.sleep 让 client 边发边读)→ `AnthropicStreamParser` 边读边发 `TextDelta` / `ToolStarted` → 最后 `message_stop` 时构造 `LlmResponse(toolCalls=[bash])` → `LinearTurnEngine.L177` `dispatchParallel(toolCalls)` → `ToolExecutor.dispatch(bash)` 5 步流水线 → `BashTool.execute()` → `ToolResult.success` → L181 `appendToolResult` → 下次 loop send 第二个 Anthropic request 含 `tool_result` block → mock 流式发 text 响应 → 自然 break
**断言方式**:L2 slice,Mock HTTP server 后台 thread 边发,client `provider.stream` 在第一个 `TextDelta` 到达时立即 `sink.onNext` 发射(不是 readAll 完才发射)+ `AnthropicStreamParser` state machine 处理多 block 交错 + `BashTool.execute()` 调用 1 次 + 最终 `LlmResponse.getToolCalls()` 非空 + `ctx.history()` 最后一条 `Message.Assistant.toolCalls` 非空(同 #027a AC-NN-7 守卫)+ 真流式首 token 延迟 < 100ms(`MessageStart` 后 `content_block_delta.text_delta` 间隔)< 500ms(`MAPPER.readTree()` 单次开销)

### AC-NN-8 — `message_stop` 时 `finish()` 返回完整 `LlmResponse`

**Given** 一个已 feed `message_start` + 多个 `content_block_*` + `message_delta(stop_reason="end_turn")` 但**未** feed `message_stop` 的 parser 实例
**When** `parser.finish()` 被调(在 #027b 集成路径,这是错误时机)
**Then** 抛 `IllegalStateException("AnthropicStreamParser.finish() called before message_stop")`(防御性守卫)
**断言方式**:L1 Unit 测试,`assertThatThrownBy(() -> parser.finish()).isInstanceOf(IllegalStateException.class).hasMessageContaining("message_stop")`

### AC-NN-9 — R-13 mitigation (d) 依赖零增量

**Given** Story #027b 引入 `AnthropicStreamEvent` + `AnthropicStreamParser` + `AnthropicLlmProvider.doPostStream` 流式分支 + `LlmErrorCodes.LINGS_L03` reserved + `AnthropicStreamTestSupport` 测试 fixture
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#027a` post-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;`banned-dependencies` enforcer 不 fail
**断言方式**:对照 `specs/027a-anthropic-tool-protocol-conversion/` PR body 末尾的 `### R-13 dependency:tree 自查` 节(diff 只允许 timestamp + 时间戳差异)

### AC-NN-deps-1(R-13 mitigation (d) — 强制)

**Given** 当前 Story 引入 / 修改依赖
**When** 跑 `mvn dependency:tree -pl lingshu-core -Dverbose`
**Then** 输出中**必须不包含** `banned-dependencies` 列表的任何条目,关键子树(>= 3 层的 `spring-ai-*` / `com.fasterxml.jackson.*` 版本冲突对)贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节

### AC-NN-deps-2(R-13 mitigation (d) — 强制)

**Given** Story #027b 引入 `HttpURLConnection.setChunkedStreamingMode` + 手写 SSE parser + `Map<Integer, ...>` 状态机
**When** `mvn -pl lingshu-core verify` 跑 enforcer
**Then** `banned-dependencies` 规则**必须在 build 阶段 fail**(若依赖没碰,run 配置 `enforcer.skip=true` 显式跳过 + 在 PR body 说明)

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| 真 streaming HTTP/2 + chunked transfer encoding(用 `HttpClient.sendAsync` 异步流式 API)| `#027b` 边界 = JDK 8 + `HttpURLConnection` 流式输入(已存 `#001` 6-arg ctor + `ioExecutor`);切 `HttpClient` 需 JDK 11+ 且 `#021b/c` SSE 模式同款手写 parser 已验证,无需换 |
| 多 `LlmProvider` 并存(Anthropic + OpenAI + Gemini 流式同时跑)| `#027b` 只 Anthropic SSE;OpenAI / Gemini 流式拆 OQ-Future(用 #027a + #027b 协议层样板)|
| `Anthropic prompt caching`(`cache_control: {type: "ephemeral"}` blocks)| OQ-Future,留 `Message` 后续扩展 cache 字段时再补 |
| `Anthropic extended thinking`(`thinking: {type: "enabled", budget_tokens: N}`)| OQ-Future,留 `LlmRequest` / `Prompt` 后续扩展 thinking 字段时再补 — 当前 `Message` 注释已知 OQ Future 字段扩展点 |
| 多模态(`type: "image"` / `type: "document"` blocks) | `#001` Message.User.content 是 String(非 MultimodalContent),不支持图片 / 文档 — OQ-Future 留 `Message` multimodal 字段扩展 Story |
| 缓存 `input_json_delta` 拼接 buffer 直到 `message_stop` 才 `MAPPER.readTree()`(全局 buffer 而非 per-block buffer)| `#027b` 用 `Map<Integer, ToolCall.Builder>` per-block buffer,`content_block_stop` 时 per-block 立即 parse,避免 buffer 膨胀(`#027b` 边界 = 多并行 tool_use block 交错支持)|
| 自定义 `LlmStreamSubscriber` 抽象层 | `Subscriber<AgentEvent>` JDK 8 标准 Reactive Streams 已存 `#001`;`#027b` 直接用现有契约,无新接口 |
| `AnthropicSseClient` 独立类(替代 `HttpURLConnection`)| `#027b` 边界 = 在 `AnthropicLlmProvider` 内 `doPostStream` 流式分支,不抽独立类 — 与 #027a `doPost`/`parseResponse` 同仓同文件演进 |
| 替代 `BufferedReader.readLine()` 改用 `Scanner` / `StreamTokenizer` | `BufferedReader` JDK 内置 + 高性能 + `#021c` `SseMcpServerConnection` 同款模式,`#027b` 沿用 |
| `LlmProvider` interface 改造(加 `subscribe` 抽象方法)| `#001` `stream(Prompt, TurnContext, Subscriber)` 已是 Reactive Streams 契约(返回 `CompletableFuture<LlmResponse>` + `sink` 入参);`#027b` 只改 Anthropic 实现层,接口契约**0 改动** |
| SSE event 重连 / retry / circuit breaker(连接断了自动重连)| OQ-Future 留 §14.2 RetryPolicy + §14.3 CircuitBreaker 后续 Story;`#027b` 失败走 `doPostStream` catch-all 转 `LlmResponse.error` |
| `LINGS-L03` 实际抛 ErrorCode | `#027b` 仅 reserved 占位;实际抛点留 §14 N6 graceful shutdown 后续 Story |
| `LINGS-L04+` 任何 ErrorCode | `#027b` 不开新段号 |
| 流式 SSE 单独 `LlmProvider` 子类(`AnthropicStreamingLlmProvider`)| 违反 §11.4 边界 — `#027a` 反模式条目明确「流式与非流式协议不并行两套 AnthropicLlmProvider 实现」;`#027b` 与 `#027a` 同文件加 `doPostStream` 分支 |
| `LingsLlmProviderException` 复用 `LingsConfigException`(`#026` 引入)| `#027a` 已落独立异常类;`#027b` 复用,域 LlmProvider 不变 |

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#001` zero-config-bootstrap — `AnthropicLlmProvider` 6-arg ctor + `doPost()` POST 骨架 + `Subscriber<AgentEvent>` sink + `AgentEvent.TextDelta` + `ReasoningStarted` + `ToolStarted` 已存(`#001` 落地)
  - `#004` tool-parallel-dispatch — `LinearTurnEngine.dispatchParallel` + `ToolExecutor.dispatch()` 5 步流水线
  - `#008` react-max-steps — `AgentEvent.ReasoningStarted(int step, int maxSteps)` 已存 + `MaxStepsExceeded` 事件
  - `#019` built-in-tools — `Read / Write / Edit / Bash` 4 个手写 `Tool` 实现(AC-NN-7 端到端测试用 `BashTool` mock)
  - `#020a` skill-foundation — `ToolRegistry.modelVisibleSpecs()` `List<ToolSpec>` 契约
  - `#021b` mcp-tool-adapter — `ToolRegistry.register / unregister` SPI
  - `#021c` mcp-sse-and-http-transport — `SseMcpServerConnection` 手写 SSE parser 样板(`BufferedReader.readLine()` + `\n\n` 分隔 + `event:` / `data:` 双行解析)
  - `#022` spring-ai-annotation-tool — `@AgentTool` 注解 + `SpringAiToolAdapter`(3 条 Tool Scheme 来源之一)
  - `#024` tool-schemas-integration — `DefaultPromptBuilder` 注入 `ToolRegistry` → `Prompt.tools` 真填 `modelVisibleSpecs()`
  - **`#027a` anthropic-tool-protocol-conversion** — `buildRequestBody` 协议转换(顶层 `tools:[]` + `messages[].content` array of blocks)+ `parseResponse` 解析 `content[].tool_use` block → `ToolCall` + `TurnContext.appendAssistant` 5-arg 签名 + `LinearTurnEngine.L166` 真传 toolCalls + `LlmErrorCodes.LINGS_L01 / LINGS_L02` + `LingsLlmProviderException` —— **`#027b` 强依赖,`#027a` 已合**
- **后续 Story(本 Story 是其前置)**:
  - `#027b follow-up` — 真 LLM 流式断流 / 重连 / circuit breaker(§14.2 RetryPolicy + §14.3 CircuitBreaker)
  - §14.10 N10 audit-log — `AuditLogger` 记录 `text_delta` / `tool_use` block / `tool_result` block 入账(每条产生事件事件埋点,#027b 提供完整 ReAct 事件流)
  - §14 N6 graceful-shutdown — 流式 SSE 连接中断 / timeout 场景 `LINGS-L03` ErrorCode 启用

---

**Spec writer**: Claude Code
**Spec date**: 2026-09-30
**Spec version**: v0.1 Draft