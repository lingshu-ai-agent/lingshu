# Story #027b `anthropic-stream-tool-sse` — Quickstart(30 min)

> **面向 Alice(Java 后端开发,用过 Spring Boot,首次接触 LingShu)**
> **目标**:30 分钟内跑通"AnthropicLlmProvider 真 SSE 流式 + Tool 协议层 + input_json_delta 拼接"链路
> **前置**:`#027a` 已合(Anthropic 协议层 Tool 转换)+ JDK 11+(Anthropic `/v1/messages` SDK 推荐)+ Maven 3.6.3+

---

## 0. 学完能做什么

- 理解 LingShu Slot 1(LlmProvider)的 stream() / generate() 两条路径
- 看懂 `AnthropicLlmProvider.doPostStream` 怎么走 raw JDK `HttpURLConnection` + `Accept: text/event-stream`(不靠 Spring AI `ChatClient`)
- 看懂 `AnthropicStreamParser` 6 段状态机怎么吃 6 种 SSE event(`message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop`)
- 看懂 `input_json_delta.partial_json` 拼接 + `content_block_stop` 时机解析 `ObjectNode`
- 跑一次 mock SSE server 自验,验证首 token P50 ≤ 1.5s NFR

---

## 1. 5 步跑通(每步 5 min)

### 步骤 1(5 min):克隆主仓 + 看 lingshu-core 顶层 layout

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
ls lingshu-core/src/main/java/ai/lingshu/core/impl/llm/ | head -20
```

**预期看到 9 个 LLM 域文件**:
```
AnthropicLlmProvider.java        ← Stream() / generate() 两个公开方法
AnthropicStreamEvent.java        ← 不可变 SSE 事件(type + JsonNode)
AnthropicStreamParser.java        ← 6 段状态机
LlmErrorCodes.java               ← LINGS-L01/L02 实抛 + LINGS-L03 reserved
LingsLlmProviderException.java   ← [LINGS-L0X] 前缀 ErrorCode 嵌入
... (其余 4 个为 #027a 协议层)
```

### 步骤 2(5 min):跑 L1 单元测试,验证 SSE parser 状态机

```bash
mvn -pl lingshu-core test -Dtest=AnthropicStreamParserTest
```

**预期看到 6 个 PASS**:
```
✅ AC-NN-2: messageStartEmitsReasoningStartedAndInitUsage
✅ AC-NN-3: textDeltaAccumulatesAndEmitsPerDelta
✅ AC-NN-4: inputJsonDeltaConcatenatesAndParsesAtStop
✅ AC-NN-5: multiBlockInterleavedStateMachine
✅ AC-NN-6: missingToolUseIdThrowsL01
✅ AC-NN-8: finishBeforeMessageStopThrowsIllegalState
```

每个 case 都喂 1 段手填的 `AnthropicStreamEvent`(type/title/jsonData, 字符串 → JsonNode)进 `AnthropicStreamParser.feed()`,断言 sink 收到对应 `AgentEvent` 或 final `LlmResponse`。

### 步骤 3(5 min):跑 L2 集成测试,验证真 SSE 长连接

```bash
mvn -pl lingshu-core test -Dtest=AnthropicStreamProviderIT
```

**预期看到 2 个 PASS**:
```
✅ AC-NN-1: acceptHeaderIsTextEventStream      (验证 HttpURLConnection 发送 Accept 头)
✅ AC-NN-7: endToEndSseStreaming_mockServerEmitsIncrementally
                                                (验证 20ms 增量发射 → sink 收 ReasoningStarted + ToolStarted)
```

`AnthropicStreamProviderIT` 启动 JDK 内置 `com.sun.net.httpserver.HttpServer` mock 服务,按 20 ms 间隔写 SSE event 流;然后调 `provider.stream(prompt, ctx, sink)` 拿 `CompleturingFuture<LlmResponse>`,断言:
- future.get(5s) 不超时
- 返回的 `LlmResponse.getToolCalls().get(0).getId() == "tu_1"`
- sink.events() 收 1 个 `ReasoningStarted` + 1 个 `ToolStarted`(无 `TextDelta` 因为响应只有 tool_use)

### 步骤 4(5 min):看 `doPostStream` 核心 80 行

```bash
sed -n '/doPostStream/,/^    }/p' \
  lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java \
  | head -80
```

**预期看到 3 段**:
```java
// (a) HttpURLConnection + Accept 头
HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + "/v1/messages").openConnection();
conn.setRequestMethod("POST");
conn.setRequestProperty("Accept", "text/event-stream");  // ← 关键
conn.setRequestProperty("anthropic-version", apiVersion);
conn.setRequestProperty("Authorization", "Bearer " + apiKey);
conn.setDoOutput(true);

// (b) SSE 长连接读取
BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
AnthropicStreamEvent event;
while ((event = AnthropicStreamEvent.parse(reader)) != null) {
    parser.feed(event, sink);  // ← 关键:每 event 立即发射 AgentEvent
}

// (c) 解析后的 LlmResponse 返 CompletableFuture
return CompletableFuture.completedFuture(parser.finish());
```

### 步骤 5(5 min):看 6 类 SSE event → AgentEvent 映射表

| SSE event | `AnthropicStreamParser.feed()` 行为 | 发射的 AgentEvent |
|---|---|---|
| `message_start` | init `Usage(input_tokens)` + emit | `ReasoningStarted(step=1, maxSteps=50)` |
| `content_block_start` (text) | init text block buffer | — |
| `content_block_start` (tool_use) | init tool block buffer | `ToolStarted(toolCallId, name)` |
| `content_block_delta` (text_delta) | 累积到 text buffer + emit | `TextDelta(text)` |
| `content_block_delta` (input_json_delta) | 累积 `partial_json` 到 StringBuilder | — |
| `content_block_stop` | 解析累积的 `partial_json` 为 `ObjectNode` + append to `toolCalls` | — |
| `message_delta` | update `output_tokens` + `stop_reason` | — |
| `message_stop` | mark `finished = true` | — |

> 📌 关键设计:`text_delta` **每 delta 立即**发射 `TextDelta`(用户感知流式首 token),`input_json_delta` **不**发射事件(仅累积 `partial_json`,直到 `content_block_stop` 才一次性解析 `MAPPER.readTree()`)

---

## 2. 进阶 30 min:扩展到你的自定义 LlmProvider

### 场景:OpenAI SSE 流式 Provider

**OpenAI `/v1/chat/completions` 流式协议与 Anthropic 90% 同**(都走 `text/event-stream`),差异:
- 续 `data: [DONE]` 标记结束(Anthropic `message_stop`)
- 续 事件名是 `data:`(Anthropic `event:` 行)
- 续 Tool call 协议是 `delta.tool_calls[i].function.arguments`(Anthropic `input_json_delta.partial_json`)

**做法**:
1. 新建 `OpenAiStreamParser`(extends `AnthropicStreamParser` 或平级 class)改 1 个 method `parseDelta(JsonNode) → List<ToolBlockDelta>`
2. `OpenAiLlmProvider.doPostStream` 走与 Anthropic 同款 `HttpURLConnection` + `setRequestProperty("Accept", "text/event-stream")`
3. `OpenAiLlmProviderProvider implements LlmProviderFactory`(`name="openai"` + `priority=10`)+ `OpenAiLlmProviderProviderAutoConfiguration @AutoConfiguration` `+ @Bean public Tool openAiLlmProviderTool()` —— 第 9 Slot 体系的标准扩展(对齐 §5.5「多 Provider 模式」样板)

### 验证你的实现

```bash
# 1. L1 单元(你写 parser 的状态机测试)
mvn -pl lingshu-core test -Dtest=OpenAiStreamParserTest

# 2. L2 IT(JDK 内置 mock SSE server 复用 AnthropicStreamTestSupport)
mvn -pl lingshu-core test -Dtest=OpenAiStreamProviderIT

# 3. R-13 mitigation (d) baseline 镜像
mvn -pl lingshu-core dependency:tree > /tmp/post.txt
diff /tmp/pre.txt /tmp/post.txt | grep -v "\\.lastUpdated" | wc -l
# 预期:0(只有时间戳差异,0 binary delta)
```

---

## 3. 故障排查

### Symptom:首 token 延迟仍 5s+

**Root cause**:`stream()` 走了非流式路径。检查:
   - `agent.llm.name=anthropic` 在 yaml 配了吗?(若 `name=default` 走 mock 路径)
   - `agent.llm.apiKey` 设了吗?(若缺,provider 抛 LINGS-L05)
   - `agent.llm.stream=true` 设了吗?(默认 true 但 yml 没显式可能默认回 false)

### Symptom:`LINGS-L01 tool_use.id missing`

**Root cause**:Anthropic 协议层 §15.3 L01 实抛触发。检查:
   - mock SSE server 的 `content_block_start` 事件是否带 `id` 字段(且非空)
   - 真实的 Anthropic API 响应是否正常(curl 一次确认)

### Symptom:`IllegalStateException: finish() called before message_stop`

**Root cause**:parser.feed 序列没发完整 6 类 event。检查:
   - mock SSE server 写完整序列:`message_start` + N × `content_block_start/delta/stop` + `message_delta` + `message_stop`
   - 真实 API 响应网络断流时(无 `message_stop`),parser 永远 `finished=false`,`finish()` 抛 ISE

### Symptom:R-13 baseline 镜像有 binary delta

**Root cause**:引入了新 Maven 依赖。检查:
   - `pom.xml` 是否手加了 `org.springframework.web.reactive` / `okhttp` / `okio` 等
   - JDK 8 内置 `HttpURLConnection` + `BufferedReader` + Jackson 已锁 13 项依赖,**不**需要任何新 binary
   - 误用 `var` / `List.of` / sealed / records 等 JDK 9+ API 编译失败(不是 binary delta,是编译失败)

---

## 4. 下一步

- 📖 详细数据模型(状态机对照表 + 6 类 SSE event 完整 JSON 样例):[data-model.md](data-model.md)
- 📖 Story spec 全集(WHO/WHAT/AC/反向 AC):[spec.md](spec.md)
- 📖 Implementation tasks(P1—P5):[tasks.md](tasks.md)
- 📖 Plan 详细(接口 / 文件 / 测试策略):[plan.md](plan.md)

---

**Last updated**: 2026-09-30
**Version**: 1.0 (Story #027b 实施完成同期发布)