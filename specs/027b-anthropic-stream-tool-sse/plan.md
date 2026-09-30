# Plan: Story #027b `anthropic-stream-tool-sse`

> **Spec anchors**: specs/027b-anthropic-stream-tool-sse/spec.md
> **Design anchors**: dsh v1.5.44 §6.5 protocol gap(接续 #027a)+ §6.5 (1.5) `Story 路由` 表第 2 行 + §4.6 Tool / §4.10.1 硬规则 2 / §15.4 ErrorCode 域 / §3 NFR LLM 流式首 token P50 ≤ 1.5s + constitution v1.0
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed`(constitution §1 第 1 项 + §6 兼容性矩阵)
- **Lombok `@Value` 不可变优先** — `AnthropicStreamEvent` + `AnthropicStreamParser` 用 Lombok `@Value`(字段 final + getter 自动 + all-arg ctor 自动)
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — 本期 **0 新抛 ErrorCode**(`LINGS-L01 / LINGS-L02` 复用 #027a)+ `LINGS-L03` reserved 占位(constitution §4 + spec §1 修正;**L 段 LlmProvider 域启用,L03 标 reserved 不抛**)
- **性能预算 §14.15.1 不退化 + 达标** — 流式首 token P50 ≤ 1.5s / P99 ≤ 3.0s(§3 NFR)+ turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化;`#027b` 真 SSE 流式,首 token = LLM 推理首 token 时间 + 协议 RTT(≤ 100ms),**修复 #027a 非流式路径 NFR 不达标**
- **`ToolExecutor.dispatch()` 5 步流水线不变** — `AnthropicLlmProvider` 协议层不绕过任何一步(§4.10.1 硬规则 2)
- **0 新 Maven 依赖** — Jackson `ObjectMapper` / `ObjectNode` / `ArrayNode` 已锁 / `BufferedReader` + `HttpURLConnection.setChunkedStreamingMode` JDK 内置 / `ExecutorService` + `ScheduledExecutorService` JDK 内置 / `Subscriber` reactive-streams 已锁 / `Map<Integer, ...>` JDK 内置;**`com.sun.net.httpserver.HttpServer` 测试 fixture 已用**(R-13 第 13 次验证)
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock `HttpServer`**(`com.sun.net.httpserver.HttpServer` JDK built-in + `ExecutorService` 后台线程发 SSE 流,沿用 `#027a` / `#021c` / `#022` / `#023` 模式)— 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` 流式,**不走 ChatClient 自动执行**

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 接口 / 异常类 | 路径 | 角色 |
|---|---|---|
| `AnthropicStreamEvent`(`@Value` 不可变)| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamEvent.java` | 单个 SSE event 的不可变表示:`String type`(`message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop`)+ `JsonNode data`(MAPPER.readTree 后的 JSON 树);静态工厂 `parse(String rawSseBlock)` 接收 SSE 协议 raw 块(以空行分隔) |
| `AnthropicStreamParser`(`@Value` 不可变 + 内部 mutable Map 字段)| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamParser.java` | SSE 状态机:`Map<Integer, StringBuilder> textBlocks` + `Map<Integer, ToolCall.Builder> toolBlocks` + `StringBuilder textBuf` + `List<ToolCall> toolCalls` + `StopReason stopReason` + `Usage usage`;`feed(AnthropicStreamEvent, Subscriber)` 单 event 推方法 + `finish()` 收尾返回 `LlmResponse` |
| `AnthropicStreamTestSupport`(普通 Java 类,测试 fixture)| `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamTestSupport.java` | 启动 mock `HttpServer`(`com.sun.net.httpserver.HttpServer` JDK built-in)+ 后台 `ExecutorService` 按 `Thread.sleep(20ms)` 间隔发 SSE event + capture request body;`startSseServer(int port, List<String> sseEvents, Consumer<String>)` + `findFreePort()` helper |

### 修改

| 接口 / 类 | 修改 |
|---|---|
| `AnthropicLlmProvider.stream(Prompt, TurnContext, Subscriber)` | L92-109 改造:`Accept: text/event-stream` 请求头 + 把 `doPost(url, requestBody)` + `readAll` 替换为 `doPostStream(url, requestBody, sink)`(新 method,行 ~50);`doPostStream` 内部 `BufferedReader.readLine()` 逐行解析 + 累积每 SSE event 块(`event:` / `data:` / 空行三段)+ 推 `AnthropicStreamParser.feed(...)` + 完成后 `parser.finish()` 返回 `LlmResponse` |
| `LlmErrorCodes` 常量类 | 加 `LINGS_L03 = "LINGS-L03"` reserved 常量(注释标 `[reserved for §14 N6 graceful shutdown,本期不抛]`) |

**关键约束**:
- `#027b` **不修改** `Tool` / `ToolRegistry` / `ToolExecutor` / `Message` 5 子类 / `LlmResponse` 契约 / `Prompt.tools` 契约 / `AgentEvent` 12 子类契约 / `Subscriber<AgentEvent>` Reactive Streams 契约 — **全部 0 改动**
- `#027b` **不修改** `LinearTurnEngine` ReAct 主循环结构(只复用 `#027a` L166 真传 toolCalls + `stream` 返回的 `LlmResponse`)+ `TurnContext.appendAssistant` 5-arg 签名(`#027a` 已落)— **0 改动**
- `#027b` **不修改** `AnthropicLlmProvider.buildRequestBody(Prompt)`(`#027a` 已落 4 段协议转换) — **0 改动**
- `#027b` **不修改** `AnthropicLlmProvider.parseResponse(String body, Subscriber)`(`#027a` 已落) — **保留作为 fallback 路径**(`AgentConfig.Llm.anthropicStreamEnabled = false` 时走 #027a 路径,确保回退路径可用)

**新增 + 修改严格遵循 dsh §6.5 protocol gap 字面落地**,不引入新接口契约,与 `#027a` `LlmErrorCodes` + `LingsLlmProviderException` 模式对齐

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamEvent.java` | 新增 | ~50 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamParser.java` | 新增 | ~180 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` | modify | +80(原 L92-109 改造 + 新 `doPostStream` 方法 + `Accept` header + 5 helper)+ 3(新 import)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java` | modify | +2(`LINGS_L03` reserved 常量 + 注释)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamTestSupport.java` | 新增 | ~120 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamParserTest.java` | 新增 | ~400(8 case 覆盖 AC-NN-2—AC-NN-6 + AC-NN-8 + 2 helper `buildEvent` / `buildMessageStartEvent`)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamProviderIT.java` | 新增 | ~250(L2 slice 端到端 AC-NN-1 + AC-NN-7,真流式 mock HTTP server)|
| `lingshu-core/src/main/java/ai/lingshu/core/config/AgentConfig.java` | modify(可选)| +1(`AgentConfig.Llm.anthropicStreamEnabled` Boolean 字段,默认 `true`,YAML `agent.llm.anthropic.stream: false` 时 fallback 到 #027a 路径)|

**3 新增 + 2 modify(必需)+ 2 test 新增 + 1 modify(可选 AgentConfig)**,合计 **5-8 文件改动**,与 ROADMAP 段二 #027b 行「5 文件 modify + 2 文件 new + 1 fixture」对齐(必需路径 5 文件:1 modify `AnthropicProvider` + 1 modify `LlmErrorCodes` + 1 new `AnthropicStreamEvent` + 1 new `AnthropicStreamParser` + 1 new test fixture)

> **可选 modify AgentConfig.Llm.anthropicStreamEnabled** — 若放弃 fallback 路径(`#027b` 一律走 SSE),`AgentConfig.java` 不改;实施期决定;若要保留 fallback,#027b 改 `stream()` 方法入口 `if (anthropicStreamEnabled) doPostStream(...) else doPost(...)`(沿用 #027a 路径)

---

## 3. 实现顺序

> **原则**:依赖方向 core 内部 `AnthropicStreamEvent` 基础类型 → `AnthropicStreamParser` 状态机 → `LlmErrorCodes.LINGS_L03` reserved → `AnthropicLlmProvider.doPostStream` 集成 → 测试 fixture → 测试 → AC 验证 → 文档同步

| 序 | 任务 | 依赖 | 输出 |
|---|---|---|---|
| 1 | `AnthropicStreamEvent`(`@Value` 不可变)+ `parse(String rawSseBlock)` 静态工厂 | 无 | 1 文件可编译 |
| 2 | `LlmErrorCodes.LINGS_L03` reserved | 无 | 1 文件 modify |
| 3 | `AnthropicStreamParser`(`@Value` 不可变 + 内部 mutable Map 字段)+ `feed(event, sink)` + `finish()` + 6 类 event if-else 分支 + tool_use 缺 id/name 抛 `LINGS-L01` 防御 + `Map<Integer, ...>` 状态机 | `AnthropicStreamEvent` + `LlmErrorCodes.LINGS_L01` + `LingsLlmProviderException`(#027a 已落)| 1 文件可编译 |
| 4 | `AnthropicLlmProvider.doPostStream(url, requestBody, sink)` 方法 + `Accept: text/event-stream` header + `BufferedReader.readLine()` 逐行 + `AnthropicStreamParser.feed()` + `parser.finish()` 返回 `LlmResponse` | `AnthropicStreamEvent` + `AnthropicStreamParser` + `Subscriber<AgentEvent>` 契约(#001 已存) | 1 文件 modify |
| 5 | `AnthropicLlmProvider.stream()` 方法替换 `doPost(...) + parseResponse(...)` 为 `doPostStream(...)`(保留 `parseResponse` 作为 fallback)| `doPostStream` | 1 文件 modify(同 file #4)|
| 6 | `AnthropicStreamTestSupport`(mock `HttpServer` + 后台线程发 SSE 流 + capture request body)| 无 | 1 test fixture |
| 7 | L1 Unit 测试 `AnthropicStreamParserTest` 8 case(AC-NN-2—AC-NN-6 + AC-NN-8)| `AnthropicStreamParser` | 1 test 文件 |
| 8 | L2 slice 端到端 `AnthropicStreamProviderIT` 2 case(AC-NN-1 真 `Accept` header + AC-NN-7 端到端真流式 mock HTTP server)| 全部 | 1 IT 文件 |

**每步独立 commit**(`feat(llm): T-NN <动作>` 格式;首 commit 是 stub,后续补实现 — 沿用 `#027a` / `#022` / `#009d` 风格)
**绝对禁止一次性 commit 5+ 文件**(`#027a` 经验离散 commit)

---

## 4. 测试策略(§14.15.7 + dsh §14.15.7 7 层金字塔)

| 层 | 用例数 | 覆盖 | 文件 |
|---|---|---|---|
| **L1 Unit** | 10 | `AnthropicStreamParserTest` 8 case(AC-NN-2 message_start `ReasoningStarted` + usage init + AC-NN-3 text_delta 累积 + emit `TextDelta` + AC-NN-4 input_json_delta 拼接 + tool_use `ToolCall` + AC-NN-5 多 block 交错 `Map<Integer, ...>` + AC-NN-6 防御 L01 + AC-NN-8 finish() message_stop 守卫 + 2 helper `buildEvent(type, dataJson)` / `buildMessageStartEvent(inputTokens)`)+ `AnthropicStreamEventTest` 2 case(parse SSE 块 1 happy / 1 malformed)| `AnthropicStreamParserTest` / `AnthropicStreamEventTest` |
| **L2 Slice** | 3 | `AnthropicStreamProviderIT` 3 case(AC-NN-1 mock HTTP server 验 `Accept` header + AC-NN-7 端到端真流式 mock HTTP server 边发边读 + AC-NN-9 R-13 dep-tree 0 binary delta)| `AnthropicStreamProviderIT` |
| **L3 Component** | —(并入 L2)| 无需独立 L3,L2 已覆盖「`AnthropicLlmProvider` + `LinearTurnEngine` + `ToolExecutor` + `ToolRegistry` + mock BashTool 多 Bean 协作」 | — |
| **L4 Contract** | 0(无接口契约变更)| `#027b` 改 `AnthropicLlmProvider.stream()` 方法体,但**接口签名不变**(`#001` 已落 `CompletableFuture<LlmResponse> stream(Prompt, TurnContext, Subscriber<AgentEvent>)`);`Subscriber<AgentEvent>` Reactive Streams 契约 0 改动;`AgentEvent` 12 子类契约 0 改动 — **无破坏性签名变更**,**无 L4 兼容性测试** | — |
| **L5 E2E / Smoke** | 含入 AC 验证 | `mvn -pl lingshu-core test` 全过,**AC-NN-1—AC-NN-9 + AC-NN-deps-1—AC-NN-deps-2** 全跑通 | CI |
| **L6 Performance** | 不跑(Story 体量不达 NFR 阈值)| `#027b` 真 SSE 流式 + LLM 首 token 时间测量(< 100ms)是**功能性验证**;§3 NFR LLM 流式首 token P50 ≤ 1.5s 是**端到端**指标(需真 LLM API),`#027b` 单元 / slice 测试无法验证 — 留 §14.15.1 全链路性能压测 Story #010(N1) 验证 | — |
| **L7 兼容** | CI matrix 跑 | `mvn -pl lingshu-core verify` 在 JDK 8/17/21 全过 | CI |

**New Case 计数**:**13 test cases** 跨 3 文件(L1 10 + L2 3 = 13)
**ROADMAP 估算**:表第 16 行「5 文件 + 0 ErrorCode」→ 13 cases 与 `#027a` 同 Story 量级(22 cases,#027b 边界稍紧)

---

## 5. 风险与回滚

| 风险 | 概率×影响 | 缓解 | 回滚 |
|---|---|---|---|
| **R-A** SSE event 协议层状态机跨 block 漂移(`Map<Integer, ...>` key 错乱导致 text 与 tool_use 串)| 1×3=3 | AC-NN-5 多 block 交错测试用例(4 个 block 交错 text + tool_use + text + tool_use)显式覆盖;每个 `content_block_start` / `content_block_delta` / `content_block_stop` 都按 `index` key 操作 Map;`finish()` 前清理 Map(GC 自动) | revert PR;旧 `AnthropicLlmProvider.stream()` 仍走 readAll,功能完整 |
| **R-B** `input_json_delta` 拼接 buffer 直到 `content_block_stop` 才 `MAPPER.readTree()`,**单 block buffer 膨胀**(LLM 一次发超长 JSON input > 1MB 路径)| 2×2=4 | AC-NN-4 显式覆盖 3 段拼接;`content_block_stop` 时 per-block 立即 parse,`#027b` 边界 = per-block 立即 parse 而非全局 buffer | revert PR;旧 `parseResponse` 仍一次性 readTree |
| **R-C** `HttpURLConnection` 流式输入需要 `setChunkedStreamingMode` 0(无 Content-Length 头才能流式) | 2×2=4 | `#027a` 已用 `setFixedLengthStreamingMode(payload.length)`(output 侧,POST body 已知长度 OK);SSE 响应是 server push 无 Content-Length,**改用 `conn.getInputStream()` + `BufferedReader.readLine()` 逐行** 即可,JDK 内置 + 无配置变更 | revert PR;旧 `doPost` 仍可 readAll |
| **R-D** 防御性校验 tool_use 缺 id/name 抛 LINGS-L01 与 #027a 不一致(抛点不同)| 1×2=2 | `#027b` 与 #027a 同 `LingsLlmProviderException` + `LINGS_L01`;抛点不同(`#027a` 在 `parseResponse`,`#027b` 在 `AnthropicStreamParser.feed`),但**错误信息格式一致**(`getMessage()` 含 `[LINGS-L01]` 前缀 + 描述);AC-NN-6 + #027a AC-NN-5 覆盖一致性 | N/A |
| **R-E** `LINGS-L03` reserved 占位但 spec 说「0 新 ErrorCode」,未来 §14 N6 graceful shutdown 启用 L03 时会被误以为 #027b 启用 | 1×1=1 | `LlmErrorCodes.LINGS_L03` 注释明确 `[reserved for §14 N6 graceful shutdown,本期不抛,2026-09-30 #027b spec 锁定]`;§14 N6 Story 实施者打开 IDE 一眼可见 | N/A |
| **R-F** R-13 mitigation (d) banned list 触发(`#027b` 引入 `Map<Integer, ...>` 大量并发状态 + SSE parser)| 2×3=6(R-13 mitigation (d))| **R-13 mitigation (d) 强制项** — AC-NN-deps-1 + AC-NN-deps-2 + tasks.md T-dep-tree-1—T-dep-tree-4 + PR body `### R-13 dependency:tree 自查` 节(`#027a` 已验证 0 binary delta,`#027b` 第 13 次验证) | revert PR;旧 `AnthropicLlmProvider.stream()` 仍走 readAll,功能完整 |

**等级**:R-A / R-B / R-C / R-D ≤ 4 监控即可;R-E / R-F ≥ 6 必缓解(AC-NN-deps 强制 + enforcer build fail)

---

## 6. 文档同步

- [ ] `README.md` 顶部加 `#027b` 1 段(SSE 流式 Tool 修复,Anthropic provider 真发 `text/event-stream` + 持续 `AgentEvent.TextDelta` 发射)
- [ ] `specs/027b-anthropic-stream-tool-sse/quickstart.md`(本 PR 内;给 Alice 30min 跑通 hello world,模板对齐 `#027a`)
- [ ] `specs/027b-anthropic-stream-tool-sse/data-model.md`(`AnthropicStreamParser` 状态机对照表 + 6 类 SSE event → AgentEvent 映射表 + `LINGS-L01 / LINGS-L03` ErrorCode 域表,模板对齐 `#027a`)
- [ ] `dsh_agent_design.md` §13 changelog 加 `v1.5.44 → v1.5.45` 行(本 Story 实施记录)
- [ ] `constitution.md` §4 域字母表 `L = LlmProvider 域 LINGS-L01/L02` 行补 `L03 reserved`(本期不抛)+ §10 R-13 风险登记:`Story #027b` 标记「已缓解」+ 第 13 次 0 binary delta 验证结果
- [ ] `ROADMAP.md` 段一 ✅ 已完成表加 `#027b` 行
- [ ] `ROADMAP.md` §15.4 ErrorCode 域表同步 `LINGS-L01 / LINGS-L02`(已存)+ `LINGS-L03 reserved`(本期不抛)
- [ ] `lingshu-docs` 仓 `docs/concepts/llm-protocol-tools.md` 起草 `#027b` 段落(Story 推 master 后开)
- [ ] `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.44` → `v1.5.45`)

---

## 7. 关键不变项(冻结)

1. `Tool` interface 5 方法 + `ToolRegistry` interface 8 方法(7 + `#021b` 的 unregister)+ `ToolExecutor.dispatch()` 5 流水线(**§4.10.1 硬规则 2**)全部 0 改动
2. `#027a` 落地的 `AnthropicLlmProvider` 6-arg ctor + `buildRequestBody(Prompt)` 协议转换(顶层 `tools:[]` + `messages[].content` array of blocks)+ `parseResponse(String body, Subscriber)` — **0 改动**(只保留 `parseResponse` 作为 fallback 路径;`stream()` 方法体内部切换 `doPostStream` vs `doPost`)
3. `#001` 落地的 `Message` 5 子类(`User` / `Assistant` / `System` / `ToolUse` / `ToolResult`)+ `LlmResponse` 5 字段(`text` / `toolCalls` / `stopReason` / `usage` / `errorCode`)+ `Prompt` 5 段(`system` / `instructions` / `tools` / `messages` / `modelParams`)— `0 改动`
4. `#001` 落地的 `Usage` 2 字段(`inputTokens` / `outputTokens`)— `0 改动`(cache tokens 留给 OQ-Future)
5. `#027a` 落地的 `TurnContext.appendAssistant(text, toolCalls, usage)` 5-arg 签名 + `DefaultTurnContext` 5-arg 实现 — `0 改动`
6. `#004` + `#027a` 落地的 `LinearTurnEngine` ReAct 主循环结构(L161-187 / L171-174 finish branch / L177 dispatchParallel / L181 appendToolResult / L184 done()) + L166 真传 toolCalls — `0 改动`
7. `#019` 落地的 `Read / Write / Edit / Bash` 4 个 hand-written Tool 实现 + `LocalToolsAutoConfiguration.afterPropertiesSet()` 注册路径 — `0 改动`
8. `#020a` 落地的 `ToolRegistry.modelVisibleSpecs()` + `findSkill` / `skillNames` / `findByName` 4 个查询方法 — `0 改动`
9. `#022` 落地的 `@AgentTool` 注解 + `SpringAiToolAdapter` + `AgentToolScanner` + `JsonArgsConverter` + `ToolErrorCodes.LINGS_T08` — `0 改动`
10. `#024` 落地的 `DefaultPromptBuilder` 2 构造器注入 `ToolRegistry` → `Prompt.tools = toolRegistry.modelVisibleSpecs()` — `0 改动`
11. `#001` 落地的 `Subscriber<AgentEvent>` Reactive Streams 契约 + `AgentEvent` 12 子类(`TextDelta` / `ToolStarted` / `ToolProgress` / `ToolCompleted` / `TurnCompleted` / `ApprovalRequired` / `Compacted` / `ErrorEvent` / `ReasoningStarted` / `ObservationAppended` / `MaxStepsExceeded` / `MessageAppended`) — `0 改动`(`#027b` 直接用现有契约)
12. dsh §6.5 protocol gap(实测发现 2026-09-26)字面落地,**0 新接口契约**(只在 `AnthropicLlmProvider.stream()` 方法体内加 `doPostStream` 分支 + 2 new 实现类 `AnthropicStreamEvent` / `AnthropicStreamParser` + 1 ErrorCode reserved)
13. constitution v1.0 §1—§9 全部不变,只 §4 L03 reserved 注释 + §10 R-13 风险状态更新
14. dsh §15.4 域字母 L 编号表:新增 `LINGS-L03 = "LINGS-L03"` reserved 占位(`§14 N6 graceful shutdown 后续启用,本期不抛`),**L01 / L02 编号不动**(复用 #027a)
15. **0 新 Maven 依赖**(R-13 mitigation (d) 第 13 次验证)
16. **3 处核心修改 + 3 处核心新增**:`AnthropicLlmProvider` 流式分支 / `LlmErrorCodes.LINGS_L03` reserved / `AgentConfig.Llm.anthropicStreamEnabled`(可选)/ `AnthropicStreamEvent` / `AnthropicStreamParser` / `AnthropicStreamTestSupport`(测试 fixture)— 符合 §11.4 Story 边界 ≤ 5 核心文件 ≤ 3 ErrorCode(0 新抛 + 1 reserved 占位)
17. **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` 流式

---

**Plan writer**: Claude Code
**Plan date**: 2026-09-30
**Plan version**: v0.1 Draft