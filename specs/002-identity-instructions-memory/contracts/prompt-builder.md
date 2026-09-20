# Contract: PromptBuilder SPI

**Package**: `ai.lingshu.core.slot.PromptBuilder`
**Source**: dsh §4.5 / §4.5.1
**Stability**: SPI — public interface, breaking changes require RFC

---

## 1. Interface (existing — unchanged by Story #002)

```java
package ai.lingshu.core.slot;

import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.message.Prompt;

public interface PromptBuilder {
    Prompt build(TurnContext ctx);
}
```

**Single method contract**: `build(TurnContext)` returns a fully-assembled `Prompt` ready to send to the `LlmProvider`.

---

## 2. 5-Segment Assembly Order (Hard Contract)

`DefaultPromptBuilder.build(ctx)` MUST produce a `Prompt` whose `messages` list is structured as follows:

| Index | Segment | Source | Skippable? |
|---|---|---|---|
| 0 (optional) | `[ROLE]` | `cfg.identity` | Only skipped if `cfg.identity == null` AND `Identity.defaults()` returns null (impossible — defaults always populated) |
| 1 (optional) | `[INSTRUCTIONS]` | `cfg.instructions.file` (if exists) → fallback to `cfg.instructions.inline` → after `{{var}}` rendering if `templateEngine="mustache"` | Skipped if both `file` (missing/null) AND `inline` (null/blank) |
| 2 (optional) | `[PROJECT MEMORY]` | Concatenation of `memorySources[i].load(ctx)` results, joined with `\n\n── separator ──\n\n` between non-null entries | Skipped if all sources return `null` |
| 3..N-2 | `[CONVERSATION HISTORY]` | `ctx.session().history()` (every `Message` in order) | Skipped if history is empty |
| N-1 | `[USER MESSAGE]` | `ctx.userInput()` (current turn's input) | NEVER skipped — `Message.User("")` if null |

Plus a separate `Prompt.tools` field (existing from #001; unchanged):
- A `List<ToolSpec>` — empty in Story #002 (no tools registered yet; Story #004 wires tools)
- Always present (never null) — `Collections.emptyList()` if no tools

And a `Prompt.hints` field:
- A `ModelHints` built from `cfg.llm.model`, `cfg.llm.temperature`, `cfg.llm.maxTokens`

---

## 3. Segment Formatting (Hard Contract — MUST match exactly)

### 3.1 `[ROLE]`

Lines are emitted in this exact order, each separated by **exactly one blank line** (`\n\n`) from the previous segment:

1. `你是 {name}{, role}。` — `role` part omitted entirely if `null` or blank
2. `输出语言:{language}` — skipped if `null` or blank
3. `人格特质:{traits joined by 、}` — skipped if empty list
4. `语气:{tone}` — skipped if `null` or blank

**Example** (Bob's `demo-engineer` yml):
```
你是 lingshu-engineer,Java 后端工程师。

人格特质:严谨、简洁、举反例。

语气:直接不啰嗦。

输出语言:zh。
```

If `role` and `tone` and `language` are all blank: only line 1 is emitted (no blank lines between segments of the same segment).

### 3.2 `[INSTRUCTIONS]`

Raw text content from `instructions.file` (if exists) OR `instructions.inline`. After `{{var}}` rendering if `instructions.templateEngine == "mustache"`.

**No header** — the content is raw text; the LLM is expected to understand from context that this is the instructions block.

**Example** (Bob's `prompts/system-engineer.md`):
```
# 角色定位
你是灵枢工程的 Java 后端工程师。

# 行为准则
- 编写代码前先阅读相关模块的现有实现
- 优先复用项目内的 helper / utility
- ...
```

After `{{var}}` rendering (if any variables defined):
- `{{var}}` literal text replaced with `vars.get("var")`
- Unknown placeholders left as-is (do NOT throw — failure mode is "LLM sees literal `{{unknown}}`", which is more debuggable than an exception)

### 3.3 `[PROJECT MEMORY]`

Concatenation of all `memorySources[i].load(ctx)` results (in the order returned by `MemorySourceRouter.resolveAll(names, cfg)`). Each non-null entry separated by:

```
\n\n── separator ──\n\n
```

If all sources return null → entire segment is omitted (no "[PROJECT MEMORY]" header, no blank lines).

**Example** (Bob's `demo-engineer`, all 4 sources populated):
```
# Project CLAUDE.md (from ./CLAUDE.md)

── separator ──

# User CLAUDE.md (from ~/.lingshu/CLAUDE.md)

── separator ──

{"name":"lingshu-engineer","role":"Java 后端工程师","language":"zh","traits":["严谨","简洁","举反例"],"tone":"直接不啰嗦"}

── separator ──

[contents of ./docs/architecture.md]

── separator ──

[contents of ./README.md]
```

### 3.4 `[CONVERSATION HISTORY]`

Each `Message` in `ctx.session().history()` is appended as-is to the `messages` list (NOT as text in the system message — these become separate `messages` entries between the system message and the new user input).

**Duplication note**: If `ctx.session().history()` already contains the current turn's user input (added by `Agent.run` before calling `build`), it will appear here AND as the [USER MESSAGE] below. This redundancy is intentional and accepted by dsh §6.1 `LinearTurnEngine`.

### 3.5 `[USER MESSAGE]`

`Message.User(ctx.userInput() == null ? "" : ctx.userInput())` — always present as the **last** entry in the messages list.

---

## 4. Empty Segment Handling (Hard Contract)

Empty / null segments are **silently skipped**. The system message text is built by concatenating non-empty segments with `\n\n` between them. Empty segments produce no header, no blank lines, no "[EMPTY]" markers.

This means: if `cfg.identity == Identity.defaults()` and all other segments are empty, the system message contains **only**:
```
你是 lingShu-agent。
```

No "[ROLE]", "[INSTRUCTIONS]" etc. labels. The LLM is expected to interpret position + content semantics, not explicit headers.

---

## 5. `Prompt.tools` and `Prompt.hints` (Hard Contract)

```java
List<ToolSpec> toolSpecs = Collections.emptyList();  // Story #002: no tools yet
ModelHints hints = new ModelHints(
    cfg.getLlm().getModel(),
    cfg.getLlm().getTemperature(),
    cfg.getLlm().getMaxTokens());
```

These are **not** part of the system message text — they are separate fields on `Prompt` consumed by `LlmProvider` (Story #003).

---

## 6. Thread-Safety Contract

`DefaultPromptBuilder` instances are constructed **once per `Agent`** by `AgentFactory.create(cfg)` (via `DefaultPromptBuilderProvider.create(cfg)`). The instance is held by the `Agent` for the entire turn (T1 → T4 per dsh §7.1.2).

**Implication**: `build(ctx)` may be called multiple times in the same turn (e.g. after compaction re-reads history). The implementation MUST be re-entrant: no mutable state across calls. All 4 `MemorySource` instances are also immutable (constructed once with `cfg`).

---

## 7. Error Behavior Contract

`build(ctx)` MUST NOT throw for these recoverable scenarios (per spec US3):
- `cfg.memory.claudeMd.project` file does not exist → source returns null → segment omitted
- `cfg.memory.claudeMd.user` file does not exist → source returns null → segment omitted
- `cfg.memory.extras[i]` file does not exist → that one source skipped, others continue
- `cfg.instructions.file` does not exist → fall back to `cfg.instructions.inline`; if both unavailable, segment omitted
- `cfg.instructions.templateEngine == "mustache"` but `{{var}}` not found in content → leave literal as-is

`build(ctx)` MUST throw `IllegalArgumentException` (→ `LINGS-S01`) if:
- Any name in `cfg.prompt.memorySources` does not match a registered `MemorySourceProvider.name()`
- This is enforced by `MemorySourceRouter.resolveAll(...)` which is called from `DefaultPromptBuilderProvider.create(cfg)` — failing fast at Agent construction time, NOT inside `build()`

`build(ctx)` MUST NOT throw `IOException` (any underlying file read failure is caught and treated as "source returns null").

---

## 8. Test Anchors (for `DefaultPromptBuilderTest`)

| Scenario | Input | Expected Output (substring assertions) |
|---|---|---|
| Empty config | `cfg = defaults()` + history=[] + userInput="1+1=几" | System message contains `"你是 lingShu-agent"` + no other segment; messages length = 2 (system + user) |
| Identity only | `cfg.identity = Identity.defaults().with(name="bob")` | System message contains `"你是 bob"` |
| Instructions mustache | `cfg.instructions.inline = "Hi {{name}}"` + `variables = {name: "Alice"}` | System message contains `"Hi Alice"` and does NOT contain `"{{name}}"` |
| CLAUDE.md missing | `cfg.memory.claudeMd.project = /nonexistent` | [PROJECT MEMORY] segment absent; no exception |
| All 4 MemorySources populated | 4 sources return non-null strings | System message contains all 4 strings + 3 separator lines |
| 2 MemorySources null, 2 populated | mixed | Only 1 separator line (between the 2 non-null entries) |
