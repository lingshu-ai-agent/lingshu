# Story #027b `anthropic-stream-tool-sse` — Data Model(状态机 + SSE event 映射)

> **范围**:Anthropic `/v1/messages` SSE 长连接 6 段事件 → LingShu `AgentEvent` / `LlmResponse` 映射契约
> **配套 spec**:[spec.md](spec.md)(Story 整体),[plan.md](plan.md)(文件 / 接口设计),[tasks.md](tasks.md)(P1-P5 任务)

---

## 1. Anthropic `/v1/messages` SSE 协议

### 1.1 请求头(必填 3 项)

| Header | 值 | 用途 |
|---|---|---|
| `Accept` | `text/event-stream` | **关键**(Story #027b 修复);非流式 `application/json` → 全响应等 |
| `anthropic-version` | `2023-06-01` | API version |
| `Authorization` | `Bearer <apiKey>` | OAuth bearer token |
| `content-type` | `application/json` | request body |

### 1.2 响应体流(6 类 event,顺序固定)

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_xxx","type":"message","role":"assistant","usage":{"input_tokens":N,"output_tokens":0}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"world"}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start   ← 多 content block 交错(text → tool_use)
data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu_1","name":"read_file"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path"}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":":\"/tmp/y\"}"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":M}}

event: message_stop
data: {"type":"message_stop"}
```

每段 event 由 3 行组成(空行结束):
```
event: <type>
data: <json>
\n
```

---

## 2. LingShu `AnthropicStreamParser` 状态机

### 2.1 内部字段

```java
class AnthropicStreamParser {
    private final Usage usage;                       // init from message_start
    private final List<TextBlockBuffer> textBlocks;  // Map<Integer, StringBuilder> 累积 text
    private final List<ToolBlockBuffer> toolBlocks;  // Map<Integer, {id, name, partialJsonBuilder}>
    private final List<ToolCall> toolCalls;          // content_block_stop 时 append
    private final StringBuilder finalText;           // text 总累积
    private String stopReason;                       // from message_delta
    private boolean finished;                        // message_stop 后置 true
}
```

### 2.2 状态机对照表(6 类 event → parser action)

| SSE event.type | parser.feed() 动作 | 立即发射 AgentEvent? | 更新 parser 字段 |
|---|---|---|---|
| `message_start` | init `usage.input_tokens` from `message.usage.input_tokens` | ✅ `ReasoningStarted(step=1, maxSteps=50)` | `usage` |
| `content_block_start` (text) | textBlocks[index] = new TextBlockBuffer() | ❌ | textBlocks |
| `content_block_start` (tool_use) | toolBlocks[index] = {id, name, partialJsonBuilder:""} <br>**L01 防御**:id 为 null/空 → 抛 `LingsLlmProviderException("[LINGS-L01] tool_use.id missing")` | ✅ `ToolStarted(toolCallId=id, name=name)` | toolBlocks |
| `content_block_delta` (text_delta) | textBlocks[index].append(delta.text) | ✅ `TextDelta(text)` per delta | textBlocks |
| `content_block_delta` (input_json_delta) | toolBlocks[index].partialJsonBuilder.append(delta.partial_json) | ❌(仅累积,直到 stop 才解析) | toolBlocks |
| `content_block_stop` (text) | finalText.append(textBlocks[index].toString()) | ❌ | finalText |
| `content_block_stop` (tool_use) | `ObjectNode input = MAPPER.readTree(toolBlocks[index].partialJson)` <br>`toolCalls.add(new ToolCall(id, name, input))` | ❌ | toolCalls |
| `message_delta` | update `stopReason` from `delta.stop_reason` + `usage.output_tokens` | ❌ | stopReason, usage |
| `message_stop` | `finished = true` | ❌(套件顶层 `TurnCompleted(reason, usage)` 由 engine 发射) | finished |

### 2.3 关键不变量

- **顺序保证**:`message_start` 必为第一个 event,`message_stop` 必为最后一个 event;中间 `content_block_start/stop/delta` 按 `index` 严格升序,且同一 `index` 必满足 `start` → 0+ `delta` → `stop`
- **finished 不可逆**:`finished` 在 `message_stop` 后置 true,`finish()` 校验 true 否则抛 ISE(AC-NN-8)
- **partial_json 完整性**:`toolBlocks[index].partialJsonBuilder.toString()` 在 `content_block_stop` 时必为合法 JSON,否则 `MAPPER.readTree()` 抛 `JsonProcessingException`(转 `LingsLlmProviderException("[LINGS-L05] malformed partial_json")`)
- **multi-block 交错**:支持 `index 0 = text` → `index 1 = tool_use` → `index 2 = text` → `index 3 = tool_use` 任意交错;`toolCalls` 按 stop 顺序 append,与 `index` 升序无关

---

## 3. AnthropicStreamEvent 不可变事件类

### 3.1 类定义

```java
@Value
public class AnthropicStreamEvent {
    String type;       // 6 类 event 类型字面量
    JsonNode data;     // "data: {...}" 行 JSON
    
    public static AnthropicStreamEvent parse(String rawSseBlock) {
        // 解析 3 行 SSE 块(event: / data: / \n)
        // 1 行 type,1 行 data,1 空行
        // 抛 IllegalArgumentException if 数据不合法
    }
}
```

### 3.2 parse 行为细节

```java
String block = """
    event: message_start
    data: {"type":"message_start","message":{"id":"msg_x",...}}
    
    """;  // 末尾空行

AnthropicStreamEvent ev = AnthropicStreamEvent.parse(block);
// ev.type() == "message_start"
// ev.data().get("type").asText() == "message_start"
```

| 输入场景 | parse 行为 |
|---|---|
| 完整 3 行(event + data + 空行) | 返回 `AnthropicStreamEvent(type, data)` |
| 只有 `data:` 行 + 空行(Anthropic `event:` 缺) | 用 `data.type` 推断 type |
| 空块 / 多余空白 / 注释行 | 返回 `null`(提示调用方跳过) |
| 多个连续 SSE event(未拆分) | 只解析第一段,返回该段 |

---

## 4. `LlmResponse` 装配(`AnthropicStreamParser.finish()`)

`finish()` 在 `finished == true` 后返回不可变 `LlmResponse`:

```java
LlmResponse resp = new LlmResponse(
    finalText.toString(),          // getText()
    toolCalls,                     // getToolCalls()
    StopReason.valueOf(stopReason),// getStopReason()(END_TURN / TOOL_USE / MAX_TOKENS / STOP_SEQUENCE)
    usage,                         // getUsage()
    null,                          // getError()
    null                           // getMetadata()
);
```

`finish()` 校验:
- `finished == true` 否则 `IllegalStateException("finish() called before message_stop")`(AC-NN-8)
- `stopReason != null`(可能 `message_delta` 未到但 `thread null`,fallback `StopReason.END_TURN`)

---

## 5. ToolCall 装配(`content_block_stop` for tool_use)

```java
ToolBlockBuffer buf = toolBlocks.get(index);
ObjectNode input = (ObjectNode) MAPPER.readTree(buf.partialJsonBuilder.toString());
ToolCall call = new ToolCall(buf.id, buf.name, input);
toolCalls.add(call);
```

例:tool_use block index=1,`id="tu_1"`,`name="read_file"`,`partial_json="{\"path\":\"/tmp/y\"}"`:
```java
ToolCall call = new ToolCall("tu_1", "read_file", ObjectNode("path", "/tmp/y"));
// call.getId() == "tu_1"
// call.getName() == "read_file"
// call.getInput().path("path").asText() == "/tmp/y"
```

---

## 6. 错误码契约(§15.3 L 段)

| ErrorCode | 触发条件 | 抛出位置 | 是否 Story #027b 引入 |
|---|---|---|---|
| `LINGS-L01` | `content_block_start(tool_use)` 但 `id == null` 或空 | `AnthropicStreamParser.feed()` | ❌ #027a 已引入 |
| `LINGS-L02` | (保留 for §14 N6 graceful shutdown 相关) | — | ❌ #027a 已引入 |
| `LINGS-L03` | **reserved** 占位(本期不抛) | — | ✅ #027b 序号占位 |
| `LINGS-L05` | HTTP 4xx/5xx 响应 + 解析后 `partial_json` 格式错 | `AnthropicLlmProvider.doPostStream` 错误路径 + parser | ❌ #027a 邻号复用 |

> 注:L05 在 #027a 文档里被列为"§15.3 L 段 +2 移位后的 L05 = LLM_RESPONSE_MALFORMED"(原 L04 +2)。**本期首次实抛**(之前仅占位)。

---

## 7. JDK 8 兼容性约束

| 不能用 | 替代 | 原因 |
|---|---|---|
| `var` | `ObjectNode input = (ObjectNode) MAPPER.readTree(...)` | JDK 10+ |
| `record` | `@Value` Lombok | JDK 14+ |
| `sealed` / `permits` | plain class | JDK 17+ |
| `List.of(...)` | `Arrays.asList(...)` / `Collections.emptyList()` | JDK 9+ |
| `Stream<T>` JDK 9+ 特性 | `for` loop / `while` | JDK 9+ |
| `HttpClient` (JDK 11+ built-in) | `HttpURLConnection` | `lingshu-core` 严格 JDK 1.8 |

**0 新 Maven 依赖**(`HttpURLConnection` + `BufferedReader` + Jackson + `com.sun.net.httpserver.HttpServer` 已锁 13 项依赖表内)。

---

## 8. 与 LinearTurnEngine 集成

`AnthropicLlmProvider.stream()` 公开方法签名不变(`stream(Prompt, TurnContext, Subscriber<AgentEvent>) → CompletableFuture<LlmResponse>`),由 #027a 已落地:
- `LinearTurnEngine.L166` 真传 `resp.getToolCalls()` 给 history assistant message
- 工具调用走 `ToolExecutor.dispatch()` 5 步流水线不变
- `Subscriber<AgentEvent>` 由 `LinearTurnEngine` 实现,在 ReAct Action 阶段 `sink.onNext(ReasoningStarted / TextDelta / ToolStarted)` 同步推进

**`AnthropicStreamParser` 内部每 event 立即 `sink.onNext(...)`**,**这是真流式的关键**:用户感知首 token 延迟 = 模型推理首 token 时间 + 网络 RTT,不再 = 完整响应生成时间。

---

## 9. 性能对照(NFR P50 ≤ 1.5s / P99 ≤ 3.0s)

| 场景 | #027a(POST+readAll) | #027b(真 SSE) |
|---|---|---|
| 长 prompt(>1K tokens)+ 长响应(>500 tokens)首 token | 5-10s(等完整响应) | **0.5-1.5s**(模型首 token + 3 event 网络 RTT) |
| 多 content block 交错(text + tool_use × 2)首 token | 8-15s | **1-2s**(message_start 立即到) |
| 网络断流(无 message_stop) | 错误抛 ISE 后整体失败 | parser.markFinished=false + finish() 抛 ISE,但**已收到的 partial events 已发射**(用户看到 ReasoningStarted + 部分 TextDelta) |
| 模型推理慢(20s+)首 token | 总延迟 20s+ | **同样 20s+(模型决定,协议无关)** |

---

**Last updated**: 2026-09-30
**Version**: 1.0 (Story #027b 实施完成同期发布)