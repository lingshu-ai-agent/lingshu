# Refactor — 删除死代码 `Message.ToolUse`(v1.5.46)

> **类型**:Refactor(非 Story,超 §11.4 Story 边界 — 0 新功能,纯 Java 文件内类型清理)
> **来源**:2026-09-30 用户反馈 `Message.ToolUse` 是否从未被用过,确认 dead code 后要求删除
> **修复者**:Claude Code

## WHY

`Message.ToolUse`(`Message.java` L73-84)从未被生产代码 `new` 出来过 —— 历史是 #020a `Skill` / #020c `SkillCommandDispatcher` 早期设计的"独立 `Message.ToolUse` subtype 表示一次 tool_call" 路径。后 #024 / #027a 协议转换层落地后,实际生产链路改走 "`Message.Assistant.toolCalls` 嵌入模式",`Message.ToolUse` 自此变成 0 引用死代码,只剩 `TruncatingCompactor.messageCharLen` 一个 `instanceof` 分支 + 5 处 JavaDoc `@link` 引用。

## WHAT

直接删除 `Message.ToolUse` nested class + 相关 dead references:

| # | 文件 | 改动 |
|---|---|---|
| 1 | `lingshu-core/.../message/Message.java` | 删 `ToolUse` nested class + `import com.fasterxml.jackson.databind.JsonNode`;class-level JavaDoc「Five kinds」→「Four kinds (🆕 v1.5.46 — ToolUse removed as dead code)」+ 「Note: Tool-call requests are carried inside Assistant.toolCalls」段落;`role()` Javadoc「5 类」→「4 类」 |
| 2 | `lingshu-core/.../impl/compaction/TruncatingCompactor.java` | 删 `messageCharLen()` 中 `instanceof Message.ToolUse` 分支;class-level JavaDoc「assistant+tool_use+tool_result triples」→「Assistant messages + their trailing ToolResult blocks」;`applySlidingWindow` method Javadoc 同步 |
| 3 | `lingshu-core/.../impl/llm/AnthropicLlmProvider.java` | L383 注释「Message.ToolUse is not stored in session history」→「tool_use blocks live on Message.Assistant.toolCalls (handled in the Assistant branch above), not as separate Message.ToolUse entries — see Message.java class-level JavaDoc.」 |
| 4 | `lingshu-core/.../impl/llm/LingsLlmProviderException.java` | class-level JavaDoc「a Message.ToolUse is missing id/name」→「an Assistant message with toolCalls is missing id/name on one of its embedded ToolCall entries」 |
| 5 | `lingshu-core/.../impl/llm/LlmErrorCodes.java` | JavaDoc 2 处 `@link ai.lingshu.core.message.Message.ToolUse` → `@link ai.lingshu.core.message.Message.Assistant#toolCalls`;语义描述同步 |

## 反向 AC

- ❌ **不**删 `Message.Assistant.toolCalls` 字段(生产代码仍使用,#024/#027a 嵌入模式)
- ❌ **不**删 `Message.Assistant` 5-arg 构造器(用户 wire-through 测试可能用)
- ❌ **不**改 `LlmResponse.toolCalls` 契约(#027a 已落)
- ❌ **不**改 `LinearTurnEngine` 公开方法签名
- ❌ **不**改 `TruncatingCompactor` 算法(只删 unreachable 分支)

## 验证

`mvn -pl lingshu-core test`:583 pass / 0 fail / 2 pre-existing MCP heartbeat flake(`StdioMcpServerConnectionHeartbeatTest.probe_pingHangs_transitionsToDisconnected` + `probe_doubleCheck_bothRequired` 已在 CLAUDE.md 文档化,与本 refactor 无关)。

**0 测试 case 改动** —— 所有现有测试 0 regression(因为本来就是 dead code)。

## 关键不变项

- `Tool` SPI 不变
- `Message` 5 → **4 子类**(System / User / Assistant / ToolResult)
- `Message.Assistant.toolCalls` 嵌入契约不变
- `LlmResponse.toolCalls` 不变
- `LinearTurnEngine` 公开方法签名不变
- `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2)
- `AgentConfig` 不可变契约不变
- `AgentFactory` SPI 不变(@Autowired 6-Router ctor 不动)
- §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容
- 9 Slot 体系不变
- 24 字段 AgentConfig schema 不变
- JDK 8 兼容(`Collections.emptyList()` / `Arrays.asList()` / Jackson 已锁,no `var` / `List.of` / sealed / records)
- 0 新 Maven 依赖 / 0 新 ErrorCode / **`mvn -pl lingshu-core dependency:tree` 0 binary delta**
