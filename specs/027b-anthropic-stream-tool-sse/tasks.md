# Tasks: Story #027b `anthropic-stream-tool-sse`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 5 核心文件改动 + 1 ErrorCode reserved + 2 测试文件 + 1 测试 fixture ≈ 8 文件 `< 15` 上限)
>
> **实施顺序严格按 plan §3**:`AnthropicStreamEvent` 基础类型 → `LlmErrorCodes.LINGS_L03` reserved → `AnthropicStreamParser` 状态机 → `AnthropicLlmProvider.doPostStream` 流式分支 → `stream()` 切换 → 测试 fixture → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#027b` 复用 Jackson + `MAPPER` + `Subscriber<AgentEvent>` + `BufferedReader` + `HttpURLConnection.setChunkedStreamingMode` 已存 + JDK 内置 0 新二进制,需验证 0 binary delta 第 13 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(llm): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(3 新增 + 2 修改)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamEvent.java` 新增不可变事件类 —— `public final class AnthropicStreamEvent` + Lombok `@Value`(字段 final:`String type` + `JsonNode data`)+ 静态工厂 `public static AnthropicStreamEvent parse(String rawSseBlock)`(拆 `event:` 行拿 type + 累 `data:` 行拿 raw JSON + 空行收尾;`MAPPER.readTree(rawJson)` 转 JsonNode)+ 类级 Javadoc 引用 dsh §6.5 SSE 协议 6 类事件清单(`message_start` / `content_block_start` / `content_block_delta` / `content_block_stop` / `message_delta` / `message_stop`)(预估 30min)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java` 加 reserved 常量 —— `public static final String LINGS_L03 = "LINGS-L03";` + 行内注释 `// reserved for §14 N6 graceful shutdown,2026-09-30 #027b spec 锁定,本期不抛`(预估 5min)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicStreamParser.java` 新增状态机 —— `public final class AnthropicStreamParser` + Lombok `@Value`(6 字段 final:`Map<Integer, StringBuilder> textBlocks` + `Map<Integer, ToolCall.Builder> toolBlocks` + `StringBuilder textBuf` + `List<ToolCall> toolCalls` + `StopReason stopReason` + `Usage usage`)+ `public void feed(AnthropicStreamEvent event, Subscriber<AgentEvent> sink)`(6 类 event if-else 分支:`message_start` → emit `ReasoningStarted(1, 50)` + init usage;`content_block_start(index, type=text)` → textBlocks.put;`content_block_start(index, type=tool_use, id, name)` → 防御缺 id/name 抛 `LingsLlmProviderException(LINGS_L01)`(#027a 已落异常类)+ toolBlocks.put + emit `ToolStarted(id, name)`;`content_block_delta(index, delta.type=text_delta, text)` → textBlocks.append + emit `TextDelta(text)`;`content_block_delta(index, delta.type=input_json_delta, partial_json)` → toolBlocks.appendInput;`content_block_stop(index)` → finalize per-block(`MAPPER.readTree(toolBlocks.get(index).inputBuf)` → 构造 `ToolCall`);`message_delta(stop_reason)` → set `StopReason`;`message_stop` → set stopReason = END_TURN)+ `public LlmResponse finish()`(message_stop 后调,返回 `new LlmResponse(text, toolCalls, stopReason, usage)`)+ 防御 `finish()` 未 message_stop 时抛 `IllegalStateException("AnthropicStreamParser.finish() called before message_stop")`(预估 240min,spec §4 AC-NN-2—AC-NN-6 + AC-NN-8)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` 加 `doPostStream` 流式分支 —— (1) `stream()` 方法体替换 `doPost(url, requestBody)` + `parseResponse(body, sink)` 为 `doPostStream(url, requestBody, sink)`;(2) `doPostStream(url, requestBody, sink)` 新 method:`HttpURLConnection conn` + 加 `conn.setRequestProperty("Accept", "text/event-stream")` + `conn.setReadTimeout(READ_TIMEOUT_MS)` + `BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))` + 循环 `String line = r.readLine()`(空行分隔 SSE event 块)+ 累积 `rawSseBlock` 字符串 + 空行触发 `AnthropicStreamEvent event = AnthropicStreamEvent.parse(rawSseBlock)` + `parser.feed(event, sink)` + 重置 `rawSseBlock = ""`;(3) `message_stop` 时 `parser.finish()` 返回 `LlmResponse`;(4) 保留 `parseResponse(body, sink)` 不删(作为 fallback 路径 + 测试 helper);(5) catch-all 失败转 `LlmResponse.error(...)`(对齐 #027a 错误路径);(预估 240min,spec §4 AC-NN-1 + AC-NN-7 端到端)

> **P1 总耗时**:~515 min(~8.6h)

---

## P2:测试(3 新增 + 13 case)

- [ ] **T05** `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamTestSupport.java` 新增测试 fixture —— `public class AnthropicStreamTestSupport` 静态方法 `startSseServer(int port, List<String> sseEvents, Consumer<String> requestBodyCapture)`(起 `com.sun.net.httpserver.HttpServer` + 后台 `ExecutorService.newCachedThreadPool` 线程跑 `HttpHandler` 收 POST request capture body + 按 `sseEvents` 列表写 `event: <type>\ndata: <json>\n\n` 流 + 每 event 间 `Thread.sleep(20)` + 写完 `connection.close()`)+ `findFreePort()` helper(`new ServerSocket(0).getLocalPort()`)+ `stopServer(HttpServer)` helper 优雅停机(预估 60min)
- [ ] **T06** `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamEventTest.java` 新增 —— 2 case(parse SSE 块 1 happy 路径 + 1 malformed 路径返回 null 或抛 RuntimeException)(预估 30min)
- [ ] **T07** `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamParserTest.java` 新增 —— 8 case(AC-NN-2 message_start → `ReasoningStarted` + usage init + AC-NN-3 text_delta 累积 + emit `TextDelta` ×2 + AC-NN-4 input_json_delta 拼接 3 段 + tool_use `ToolCall` 完整 + AC-NN-5 多 block 交错 4 个 block(text + tool_use + text + tool_use)→ 2 toolCalls + 文本累积 + AC-NN-6 防御 L01 抛 `LINGS-L01` + AC-NN-8 finish() 未 message_stop 抛 `IllegalStateException` + 2 helper `buildEvent(type, dataJson)` / `buildMessageStartEvent(inputTokens)`)(预估 360min,涵盖 L1 unit)
- [ ] **T08** `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicStreamProviderIT.java` 新增 L2 slice —— 3 case(AC-NN-1 mock HTTP server 验 `Accept: text/event-stream` header + AC-NN-7 端到端真流式 mock HTTP server 边发边读(中间 `Thread.sleep(20)`)+ mock BashTool execute + ReAct Action `dispatchParallel` 真发并行 tool_call + AC-NN-9 R-13 dep-tree 0 binary delta 验证)(预估 240min)

> **P2 总耗时**:~690 min(~11.5h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamProviderIT#acceptHeaderIsTextEventStream` 验证 `stream()` 方法添加 `Accept: text/event-stream` 请求头(预估 15min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamParserTest#messageStartEmitsReasoningStartedAndInitUsage` 验证 `message_start` 事件触发 `ReasoningStarted` + init usage(预估 15min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamParserTest#textDeltaAccumulatesAndEmitsPerDelta` 验证 text block 多 `text_delta` 累积 + 每 delta 触发 `TextDelta`(预估 15min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamParserTest#inputJsonDeltaConcatenatesAndParsesAtStop` 验证 tool_use block 多 `input_json_delta` 拼接 + `content_block_stop` 时 parse 完整 input(预估 15min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamParserTest#multiBlockInterleavedStateMachine` 验证 text block 与 tool_use block 交错状态机(预估 15min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamParserTest#missingToolUseIdThrowsL01` 验证 SSE 解析防御性校验 tool_use 缺 id/name 抛 LINGS-L01(预估 10min)
- [ ] **T-validate-AC-NN-7** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamProviderIT` 验证端到端真 SSE 流式(LLM mock server 边发边读)(预估 30min)
- [ ] **T-validate-AC-NN-8** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicStreamParserTest#finishBeforeMessageStopThrowsIllegalState` 验证 `finish()` message_stop 守卫(预估 10min)
- [ ] **T-validate-AC-NN-9** 跑 `mvn -pl lingshu-core test` 全模块无 fail,新增 13 case 全过(预估 30min)

> **P3 总耗时**:~155 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-027b-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-027b-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-027a-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-027b-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min
> **PR body 末尾必有 `### R-13 dependency:tree 自查` 节**,贴 T-dep-tree-2 输出 + (name, version, slot) 三元组表

---

## P5:文档同步(提交完成闭环)

- [ ] **T-doc-1** `README.md` 顶部加 `#027b` 1 段(SSE 流式 Tool 修复,Anthropic provider 真发 `text/event-stream` + 持续 `AgentEvent.TextDelta` 发射)(预估 15min)
- [ ] **T-doc-2** `specs/027b-anthropic-stream-tool-sse/quickstart.md` 起草 Alice 30min 教程(对齐 `#027a` 模板)(预估 30min)
- [ ] **T-doc-3** `specs/027b-anthropic-stream-tool-sse/data-model.md` 起草(`AnthropicStreamParser` 状态机对照表 + 6 类 SSE event → AgentEvent 映射表 + `LINGS-L01 / LINGS-L03` ErrorCode 域表 + `input_json_delta` 拼接 buffer 规则表)(预估 30min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.44 → v1.5.45` 行(本 Story 实施记录)(预估 5min)
- [ ] **T-doc-5** `constitution.md` §4 域字母表加 `L = LlmProvider 域 LINGS-L01/L02` 行 + `L03 reserved for §14 N6 graceful shutdown` 行 + §10 R-13 风险登记:`Story #027b` 标记「已缓解」+ 第 13 次 0 binary delta 验证结果(预估 10min)
- [ ] **T-doc-6** `ROADMAP.md` 段一 ✅ 已完成表加 `#027b` 行(预估 2min)
- [ ] **T-doc-7** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.44` → `v1.5.45`)(预估 2min)
- [ ] **T-doc-8**(可选)`lingshu-docs` 仓 `docs/concepts/llm-protocol-tools.md` 起草 `#027b` 段落(Story 推 master 后开)(预估 60min)

> **P5 总耗时**:~155 min

---

## P6:PR + 合入(闭环)

- [ ] **T-PR-1** `git add -A && git commit -m "feat(llm): Story #027b anthropic-stream-tool-sse — doPostStream text/event-stream + AnthropicStreamParser state machine + input_json_delta buffer"`(Co-Authored-By Claude 标记)(预估 5min)
- [ ] **T-PR-2** `gh pr create --base main --head story-027b-anthropic-stream-tool-sse --title "feat(llm): Story #027b anthropic-stream-tool-sse — doPostStream text/event-stream + AnthropicStreamParser state machine + input_json_delta buffer" --body "$(cat /tmp/pr-body-027b.md)"`(PR body 模板贴 spec.md + plan.md + tasks.md 摘要 + AC 验证输出 + R-13 dep-tree 自查)(预估 10min)
- [ ] **T-PR-3** 等 CI 绿 + review approve,`gh pr merge --squash --auto`(预估 5min)
- [ ] **T-PR-4** 合入后 `git pull` + 触发 docs 同步任务 T-doc-1—T-doc-8(预估 155min)

---

## 总耗时估算

| 阶段 | 时间 | 说明 |
|---|---|---|
| P1(实现)| ~515min | 3 新增 + 2 modify |
| P2(测试)| ~690min | 3 新增 + 13 case |
| P3(AC 验证)| ~155min | 9 验证跑 |
| P4(dep-tree)| ~50min | R-13 强制 |
| P5(文档)| ~155min | 8 同步项 |
| P6(PR)| ~175min | commit + PR + merge + docs |
| **合计** | **~29 h** | 与 `#027a` / `#022` / `#009d` 同量级 |

---

## 强制不变项检查清单(PR review 时必勾)

- [ ] `Tool` interface **0 改动**(`git diff lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java` 应为空)
- [ ] `ToolRegistry` interface **0 改动**(`#027b` 不引新方法)
- [ ] `ToolExecutor.dispatch()` 5 步流水线 **0 改动**
- [ ] `Message` 5 子类 **0 改动**(`#027b` 复用 `Message.Assistant` / `Message.ToolUse` / `Message.ToolResult` 已存契约)
- [ ] `Prompt.tools` 契约 **0 改动**(`#024` 落地,`#027b` 只读不写)
- [ ] `LlmResponse` 5 字段契约 **0 改动**(`#001` 已存契约,**#027b` 真填 text + toolCalls + stopReason + usage**)
- [ ] `AgentEvent` 12 子类契约 **0 改动**(`#027b` 直接用现有契约,**不引新事件类型**)
- [ ] `LinearTurnEngine` ReAct 主循环结构 **0 改动**(复用 `#027a` L166 真传 toolCalls)
- [ ] `AnthropicLlmProvider.buildRequestBody(Prompt)` **0 改动**(`#027a` 已落 4 段协议转换)
- [ ] `AnthropicLlmProvider.parseResponse(String body, Subscriber)` **保留作为 fallback**(`#027b` 不删,#027a 测试全过 + `anthropicStreamEnabled=false` 配置路径仍可用)
- [ ] `TurnContext.appendAssistant(text, toolCalls, usage)` 5-arg 签名 **0 改动**(`#027a` 已落)
- [ ] `DefaultTurnContext.appendAssistant(...)` 实现 **0 改动**(`#027a` 已落)
- [ ] dsh §15.4 域字母 L01 / L02 编号 **不动**;L03 reserved 占位(`§14 N6 graceful shutdown 后续启用,本期不抛`)
- [ ] `mvn -pl lingshu-core dependency:tree` 0 新 Maven 坐标(banned list 强制)
- [ ] `mvn -pl lingshu-core verify` enforcer **不 fail**(跑 `banned-dependencies`)
- [ ] 13 test case 全过,`grep "BUILD FAIL" /tmp/mvn-test.log || echo PASS`
- [ ] PR body 末尾有 `### R-13 dependency:tree 自查` 节(T-dep-tree-2 输出贴上)
- [ ] dsh §13 changelog + constitution §4 + §10 R-13 缓解 + ROADMAP 段一已合表 四件套同步
- [ ] **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` 流式

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-09-30
**Tasks version**: v0.1 Draft
**Story #027b slug**: `anthropic-stream-tool-sse`
**对应 spec**: `specs/027b-anthropic-stream-tool-sse/spec.md`
**对应 plan**: `specs/027b-anthropic-stream-tool-sse/plan.md**