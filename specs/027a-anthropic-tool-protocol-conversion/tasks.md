# Tasks: Story #027a `anthropic-tool-protocol-conversion`

> **每个任务 = 一个 commit**;每完成一组相关任务提一个 PR(本 Story 一 PR 即可,因单 PR 边界 = 4 核心文件改动 + 2 ErrorCode 常量 + 2 测试文件 ≈ 8 文件 `< 15` 上限)
>
> **实施顺序严格按 plan §3**:`LlmErrorCodes` 常量 → `LingsLlmProviderException` 异常类 → `AnthropicLlmProvider` 协议转换 → `TurnContext` 接口签名扩 → `DefaultTurnContext` 实现对齐 → `LinearTurnEngine.L166` 真传 toolCalls → 测试 → AC 验证 → 文档同步
>
> **🟡 R-13 mitigation (d) 强制**:`T-dep-tree-*` 4 项必跑(`#027a` 复用 Jackson + `MAPPER` + `Message.Assistant.toolCalls` 已存 0 新二进制,需验证 0 binary delta 第 12 次)
>
> **🟢 提交风格**:每 T 独立 commit;格式 `feat(llm): T-NN <一句话>`,Co-Authored-By Claude 标记

---

## P1:接口与核心实现(2 新增 + 4 修改)

- [ ] **T01** `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LlmErrorCodes.java` 新增常量类 —— `public static final String LINGS_L01 = "LINGS-L01";` / `public static final String LINGS_L02 = "LINGS-L02";` + 类级 Javadoc 引用 dsh §15.4 + 域字母 L(LlmProvider)启用说明(预估 10min)
- [ ] **T02** `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/LingsLlmProviderException.java` 新增异常类 —— `public class LingsLlmProviderException extends RuntimeException` 4 field:errorCode + cause 可选 + ctor `(String errorCode, String message)` + `getMessage()` 内嵌 `[LINGS-L0X]` 前缀(对齐 `LingsConfigException` + `LinearTurnEngine.LINGS-C02` 嵌入 message 模式,让 `assertThatThrownBy().hasMessageContaining("LINGS-L0X")` 工作)(预估 30min)
- [ ] **T03** `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java` 协议层改造 —— (1) `buildRequestBody(Prompt)` 新增顶层 `tools:[]` 字段(从 `Prompt.tools` 映射 `ToolSpec` → `{name, description, input_schema}`)+ `messages[].content` 序列化为 array of blocks(User → `[{type:"text",text}]` / Assistant text+toolCalls 空 → `[{type:"text",text}]` / Assistant text+toolCalls 非空 → `[{type:"text",text},{type:"tool_use",id,name,input},...]` / Assistant toolCalls 空 text 非空 → `[{type:"text",text}]` / ToolResult → `[{type:"tool_result",tool_use_id,content,is_error}]`)+ 防御缺 id/name 抛 `LingsLlmProviderException(LINGS_L01)` + 防御 ToolResult 缺 toolUseId/content 抛 `LINGS_L02`;(2) `parseResponse(String body)` 解析 `content[]` 多类型 block(text → 累积 / tool_use → 抽 id+name+input → `ToolCall` / 防御缺 id/name 抛 `LINGS_L01`)(预估 240min,spec §4 AC-NN-1—AC-NN-6)
- [ ] **T04** `lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java` 接口签名扩 —— `void appendAssistant(String text, Usage usage)` → `void appendAssistant(String text, List<ToolCall> toolCalls, Usage usage)`(预估 10min)
- [ ] **T05** `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java` 实现对齐 —— `appendAssistant(text, toolCalls, usage)` 实现 `new Message.Assistant(text, toolCalls, usage)`(`#001` 已存 3-arg ctor + `@Value` 不可变)(预估 15min)
- [ ] **T06** `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` L166 修 —— `ctx.appendAssistant(resp.getText(), resp.getUsage())` → `ctx.appendAssistant(resp.getText(), resp.getToolCalls(), resp.getUsage())`(预估 10min)

> **P1 总耗时**:~315 min(~5.3h)

---

## P2:测试(2 新增 / 扩展 + 14 case)

- [ ] **T07** `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/LingsLlmProviderExceptionTest.java` 新增 —— 1 case(`LINGS_L01` ctor + getMessage 含 `[LINGS-L01]` 前缀)(预估 30min)
- [ ] **T08** `lingshu-core/src/test/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderTest.java` 新增 —— 8 case(AC-NN-1 tools:[] 顶层 + AC-NN-2 messages[].content blocks 5 类 + AC-NN-3 parseResponse tool_use + AC-NN-4 防御 L01 parseResponse + AC-NN-5 防御 L01 buildRequestBody + AC-NN-6 防御 L02 buildRequestBody + 2 helper `buildPromptWithTools` / `buildMockResponse` + mock HTTP server `com.sun.net.httpserver.HttpServer` 端到端 AC-NN-7)(预估 360min,涵盖 L1 + L2 slice)
- [ ] **T09** `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/DefaultTurnContextTest.java` 新增 —— 1 case(`appendAssistant(text, toolCalls, usage)` 真存 `Message.Assistant.toolCalls` 字段,断言 `ctx.history().get(N).getToolCalls() == toolCalls`)(预估 45min)
- [ ] **T10** `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineTest.java` 扩展 —— 1 case(`L166` 真传 `resp.getToolCalls()` 给 `ctx.appendAssistant`,断言 `ctx.history()` 最后一条 `Assistant.toolCalls` 非空)(预估 60min)
- [ ] **T11** `lingshu-core/src/test/java/ai/lingshu/core/integration/AnthropicToolReActIT.java` 新增 L2 slice —— 2 case(端到端 Agent.runBlocking 走通 AC-NN-7 完整链路:mock HTTP server 返回 AC-NN-3 JSON → `parseResponse` 返 `LlmResponse(toolCalls)` → `LinearTurnEngine.dispatchParallel` → `ToolExecutor.dispatch` → mock `BashTool.execute` → `ToolResult.success` → 下次 loop 含 `tool_result` block → mock 返 text 响应 → 自然 break + 多 Tool 并行 tool_use blocks 一次发 2 个 ToolCall 测试)(预估 240min)

> **P2 总耗时**:~735 min(~12.3h)

---

## P3:AC 黑盒验证(必跑,RC 卡点)

- [ ] **T-validate-AC-NN-1** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicLlmProviderTest#toolsTopLevelTranslation` 验证 `buildRequestBody` 顶层 `tools:[]` 正确翻译(预估 15min)
- [ ] **T-validate-AC-NN-2** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicLlmProviderTest#messagesContentBlocks` 验证 `messages[].content` array of blocks(text / tool_use / tool_result 5 类)(预估 15min)
- [ ] **T-validate-AC-NN-3** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicLlmProviderTest#parseResponseToolUse` 验证 `parseResponse` 解析 `content[].tool_use` → `ToolCall`(预估 15min)
- [ ] **T-validate-AC-NN-4** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicLlmProviderTest#parseResponseToolUseMissingIdThrowsL01` 验证防御性校验缺 id 抛 LINGS-L01(预估 10min)
- [ ] **T-validate-AC-NN-5** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicLlmProviderTest#buildRequestBodyToolCallMissingIdThrowsL01` 验证 buildRequestBody ToolCall 缺 id 抛 LINGS-L01(预估 10min)
- [ ] **T-validate-AC-NN-6** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicLlmProviderTest#buildRequestBodyToolResultMissingIdThrowsL02` 验证 buildRequestBody ToolResult 缺 toolUseId 抛 LINGS-L02(预估 10min)
- [ ] **T-validate-AC-NN-7** 跑 `mvn -pl lingshu-core test -Dtest=AnthropicToolReActIT` 验证端到端 ReAct Action `dispatchParallel` 真跑(含 mock HTTP server)(预估 30min)
- [ ] **T-validate-AC-NN-8** 跑 `mvn -pl lingshu-core test` 全模块无 fail,新增 14 case 全过(预估 30min)

> **P3 总耗时**:~135 min

---

## P4:依赖与构建(R-13 mitigation (d) 强制)

- [ ] **T-dep-tree-1** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-027a-pre.txt`(预提交快照,预估 10min)
- [ ] **T-dep-tree-2** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-027a-post.txt` + `diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-026-post.txt | sort) <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-027a-post.txt | sort)` 确认 0 新 Maven 坐标(预估 15min)
- [ ] **T-dep-tree-3** 跑 `mvn -pl lingshu-core verify`(enforcer **不允许跳过** — R-13 mitigation (d) 强制),确认 `banned-dependencies` 规则不 fail(预估 15min)
- [ ] **T-dep-tree-4**(可选)`mvn -pl lingshu-examples/demo-empty package` 后 `ls -lh target/*.jar`,验证 binary < 35MB 且相对 main HEAD delta < 10%(预估 10min)

> **P4 总耗时**:~50 min
> **PR body 末尾必有 `### R-13 dependency:tree 自查` 节**,贴 T-dep-tree-2 输出 + (name, version, slot) 三元组表

---

## P5:文档同步(提交完成闭环)

- [ ] **T-doc-1** `README.md` 顶部加 `#027a` 1 段(协议层 Tool 修复,Anthropic provider 真发 `tools:[]` + 解析 `tool_use` block 闭环)(预估 15min)
- [ ] **T-doc-2** `specs/027a-anthropic-tool-protocol-conversion/quickstart.md` 起草 Alice 30min 教程(对齐 `#009d` / `#022` 模板)(预估 30min)
- [ ] **T-doc-3** `specs/027a-anthropic-tool-protocol-conversion/data-model.md` 起草(`AnthropicLlmProvider.buildRequestBody` / `parseResponse` 协议转换对照表 + `LINGS-L01` / `LINGS-L02` ErrorCode 域表 + `Message.Assistant.toolCalls` 序列化规则表)(预估 30min)
- [ ] **T-doc-4** `dsh_agent_design.md` §13 changelog 加 `v1.5.43 → v1.5.44` 行(本 Story 实施记录)(预估 5min)
- [ ] **T-doc-5** `constitution.md` §4 域字母表加 `L = LlmProvider 域 LINGS-L01/L02` 行 + §10 R-13 风险登记:`Story #027a` 标记「已缓解」+ 第 12 次 0 binary delta 验证结果(预估 10min)
- [ ] **T-doc-6** `ROADMAP.md` 段一 ✅ 已完成表加 `#027a` 行(预估 2min)
- [ ] **T-doc-7** `CLAUDE.md` 对应设计文档版本号引用同步(`v1.5.43` → `v1.5.44`)(预估 2min)
- [ ] **T-doc-8**(可选)`lingshu-docs` 仓 `docs/concepts/llm-protocol-tools.md` 起草(Story 推 master 后开)(预估 60min)

> **P5 总耗时**:~155 min

---

## P6:PR + 合入(闭环)

- [ ] **T-PR-1** `git add -A && git commit -m "feat(llm): Story #027a anthropic-tool-protocol-conversion — buildRequestBody tools:[] + parseResponse tool_use + LINGS-L01/L02"`(Co-Authored-By Claude 标记)(预估 5min)
- [ ] **T-PR-2** `gh pr create --base main --head story-027a-anthropic-tool-protocol-conversion --title "feat(llm): Story #027a anthropic-tool-protocol-conversion — buildRequestBody tools:[] + parseResponse tool_use + LINGS-L01/L02" --body "$(cat /tmp/pr-body-027a.md)"`(PR body 模板贴 spec.md + plan.md + tasks.md 摘要 + AC 验证输出 + R-13 dep-tree 自查)(预估 10min)
- [ ] **T-PR-3** 等 CI 绿 + review approve,`gh pr merge --squash --auto`(预估 5min)
- [ ] **T-PR-4** 合入后 `git pull` + 触发 docs 同步任务 T-doc-1—T-doc-8(预估 155min)

---

## 总耗时估算

| 阶段 | 时间 | 说明 |
|---|---|---|
| P1(实现)| ~315min | 2 新增 + 4 modify |
| P2(测试)| ~735min | 2 新增 / 扩展 + 14 case |
| P3(AC 验证)| ~135min | 8 验证跑 |
| P4(dep-tree)| ~50min | R-13 强制 |
| P5(文档)| ~155min | 8 同步项 |
| P6(PR)| ~175min | commit + PR + merge + docs |
| **合计** | **~26.1 h** | 与 `#022` / `#009d` / `#021b` 同量级 |

---

## 强制不变项检查清单(PR review 时必勾)

- [ ] `Tool` interface **0 改动**(`git diff lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java` 应为空)
- [ ] `ToolRegistry` interface **0 改动**(`#027a` 不引新方法)
- [ ] `ToolExecutor.dispatch()` 5 步流水线 **0 改动**
- [ ] `Message` 5 子类 **0 改动**(`#027a` 复用 `Message.Assistant` / `Message.ToolUse` / `Message.ToolResult` 已存契约)
- [ ] `Prompt.tools` 契约 **0 改动**(`#024` 落地,`#027a` 只读不写)
- [ ] `LlmResponse.getToolCalls()` 契约 **0 改动**(`#001` 已存空 list 占位,`#027a` 真填非空)
- [ ] `LinearTurnEngine` ReAct 主循环结构 **0 改动**(只 L166 1 行真传 toolCalls)
- [ ] `AnthropicLlmProvider` 6-arg ctor + `doPost()` HTTP POST + 一次性 readAll 骨架 **0 改动**(只在 2 private method 内做协议转换)
- [ ] dsh §15.4 域字母 L01 / L02 **编号不动**;L03+ 留 `#027b` + 后续 Story 顺延
- [ ] `mvn -pl lingshu-core dependency:tree` 0 新 Maven 坐标(banned list 强制)
- [ ] `mvn -pl lingshu-core verify` enforcer **不 fail**(跑 `banned-dependencies`)
- [ ] 14 test case 全过,`grep "BUILD FAIL" /tmp/mvn-test.log || echo PASS`
- [ ] PR body 末尾有 `### R-13 dependency:tree 自查` 节(T-dep-tree-2 输出贴上)
- [ ] dsh §13 changelog + constitution §4 + §10 R-13 缓解 + ROADMAP 段一已合表 四件套同步
- [ ] **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`AnthropicLlmProvider` 用 raw JDK `HttpURLConnection` POST

---

**Tasks writer**: Claude Code
**Tasks date**: 2026-09-29
**Tasks version**: v0.1 Draft
**Story #027a slug**: `anthropic-tool-protocol-conversion`
**对应 spec**: `specs/027a-anthropic-tool-protocol-conversion/spec.md`
**对应 plan**: `specs/027a-anthropic-tool-protocol-conversion/plan.md`
