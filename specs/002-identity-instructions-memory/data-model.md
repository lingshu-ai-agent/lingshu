# Data Model: Story #002 identity-instructions-memory

**Branch**: `story-002-identity-instructions-memory` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

> Phase 1 — Entity catalog for the 8 types Story #002 touches. Existing types (`AgentConfig` etc.) noted with **changes from #001**; new types described in full.

---

## 1. Entity Map

```
+──────────────────────┐         +──────────────────────┐
│  AgentConfig         │ owns    │  Identity            │ [STRUCTURAL] → [ROLE] segment
│  (existing, +5 def.) │ ──────► │  Instructions        │ [STRUCTURAL] → [INSTRUCTIONS] segment
│                      │         │  Memory              │ [STRUCTURAL] → owns ClaudeMd + extras
│                      │         │  Prompt (extended)   │ [STRUCTURAL] → builder + memorySources
+──────────────────────┘         +──────────────────────┘
         │
         │ config.prompt.memorySources = ["identity","project-tree",...]
         ▼
+──────────────────────┐         +──────────────────────┐
│  MemorySource        │ SPI     │  MemorySourceProvider│ typed Provider marker
│  (existing)          │ ◄────── │  (existing)          │
│  name()/priority()   │         │                      │
│  load(ctx)→String    │         │                      │
+──────────────────────┘         +──────────────────────┘
         ▲
         │ 4 concrete impls
         │
+──────────────────────┐ +──────────────────────┐ +──────────────────────┐ +──────────────────────┐
│ ProjectClaudeMd      │ │ UserClaudeMd         │ │ Identity             │ │ ProjectTree          │
│ Source (NEW)         │ │ Source (NEW)         │ │ Source (NEW)         │ │ Source (NEW)         │
│ name="project-       │ │ name="user-          │ │ name="identity"      │ │ name="project-tree"  │
│  claude-md" prio=10  │ │  claude-md" prio=20  │ │ prio=30              │ │ prio=40              │
└──────────────────────┘ └──────────────────────┘ └──────────────────────┘ └──────────────────────┘
         │
         │ injected via
         ▼
+──────────────────────┐         +──────────────────────┐
│  MemorySourceRouter  │ NEW     │  PromptBuilder       │ existing — extended with
│  extends SlotRouter  │ ──────► │  (DefaultPromptBuilder) memorySources field
└──────────────────────┘         └──────────────────────┘
                                          ▲
                                          │ created by
                                          │
                                 +──────────────────────┐
                                 │ DefaultPromptBuilder │
                                 │ Provider (modified)  │ adds @Autowired MemorySourceRouter
                                 └──────────────────────┘
```

---

## 2. Existing Entities (modified by Story #002)

### 2.1 `AgentConfig` (dsh §4.12.2; existing from #001)

**Change**: `defaults()` factory method adds / confirms **5 default values** that #001 may have left null:

| Field | Type | #001 default | #002 default | Rationale |
|---|---|---|---|---|
| `flowEngine` | `String` | null | `"linear"` | Must match the only registered `FlowEngineProvider`; null would fail #001 AC-01-1 |
| `toolExecutor` | `String` | null | `"default"` | Must match the only registered `ToolExecutorProvider`; null would fail validation |
| `prompt.builder` | `String` | null | `"default"` | Must match the only registered `PromptBuilderProvider` |
| `prompt.memorySources` | `List<String>` | null | `["identity","project-claude-md","user-claude-md","project-tree"]` | All 4 default MemorySource Provider names — zero-config means "all sources active" |
| `prompt.topK` | `Integer` | null | `0` | RAG topK=0 means "no RAG"; placeholder for Story #015 |

**Validation rules** (already in #001, unchanged):
- `config != null`
- `config.reactMaxSteps > 0`
- `config.llmTimeoutSeconds > 0`
- All 5 Slot Router names (`flowEngine` / `llm.provider` / `toolExecutor` / `sandbox.policy` / `prompt.builder`) must be non-null + non-empty

**Immutability**: `@Value` Lombok — all fields final, no setters. AC-09 US1 Acceptance Scenario 3 asserts this.

---

### 2.2 `AgentConfig.Identity` (existing from #001)

**No field changes.** Story #002 only reads it.

| Field | Type | Default (`defaults()`) | Validation |
|---|---|---|---|
| `name` | `String` | `"lingShu-agent"` | Non-null, non-empty (asserted in #001) |
| `role` | `String` | `null` | Optional — empty line in [ROLE] when null |
| `language` | `String` | `"auto"` | Optional — "输出语言:..." line when present |
| `traits` | `List<String>` | `[]` | Empty → "人格特质:" line skipped |
| `tone` | `String` | `null` | Optional — "语气:..." line when present |
| `avatar` | `String` | `null` | Out of v1 prompt scope (reserved for Story #009 AgentCard) |

**State transitions**: None. Immutable.

---

### 2.3 `AgentConfig.Instructions` (existing from #001)

**No field changes.** Story #002 only reads + applies `templateEngine` rendering.

| Field | Type | Default (`empty()`) | Validation |
|---|---|---|---|
| `file` | `Path` | `null` | If present, must exist OR silently fall back to `inline` (current DefaultPromptBuilder behavior) |
| `inline` | `String` | `null` | If both `file` and `inline` are null, [INSTRUCTIONS] segment is empty |
| `templateEngine` | `String` | `"none"` | Only `"none"` and `"mustache"` (alias for `{{var}}` flat) supported in v1 |
| `variables` | `Map<String,String>` | `{}` | Empty → no rendering even if `templateEngine="mustache"` |

**State transitions**: None. Immutable.

---

### 2.4 `AgentConfig.Memory` (existing from #001)

**No field changes.** Story #002 only reads.

| Field | Type | Default (`defaults()`) | Validation |
|---|---|---|---|
| `claudeMd.enabled` | `boolean` | `true` | `false` → both ClaudeMd sources skipped |
| `claudeMd.project` | `Path` | `./CLAUDE.md` | File may not exist → source skipped silently |
| `claudeMd.user` | `Path` | `~/.lingshu/CLAUDE.md` | File may not exist → source skipped silently |
| `extras` | `List<String>` (paths) | `[]` | Each path independently checked for existence |

---

### 2.5 `AgentConfig.Prompt` (existing from #001, **+1 field**)

**Change**: No new fields; Story #002 confirms default values (see §2.1).

| Field | Type | Default | Validation |
|---|---|---|---|
| `builder` | `String` | `"default"` | Must match a registered `PromptBuilderProvider.name()` |
| `memorySources` | `List<String>` | `["identity","project-claude-md","user-claude-md","project-tree"]` | Each entry must match a registered `MemorySourceProvider.name()`; unknown → fail-fast `LINGS-S01` |
| `topK` | `Integer` | `0` | Out of v1 scope (RAG placeholder) |

---

## 3. New Entities

### 3.1 `ProjectClaudeMdSource` (MemorySource impl)

**Location**: `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectClaudeMdSource.java`

| Attribute | Value |
|---|---|
| `name()` | `"project-claude-md"` |
| `priority()` | `10` |
| Source path | `cfg.memory.claudeMd.project` (default `./CLAUDE.md`) |
| Output format | `String` — raw UTF-8 file content, OR `null` if `cfg.memory.claudeMd.enabled == false` OR file does not exist OR read throws IOException |
| Errors | None propagated — silently returns `null` |
| Behavior on missing file | Return `null` (filtered out by PromptBuilder); stderr stays at INFO level (no ERROR) |
| Symlinks | Followed (`Files.exists` + `Files.readAllBytes` both follow by default) |

**Validation**: None (file existence is checked at runtime, not startup).

---

### 3.2 `UserClaudeMdSource` (MemorySource impl)

**Location**: `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/UserClaudeMdSource.java`

| Attribute | Value |
|---|---|
| `name()` | `"user-claude-md"` |
| `priority()` | `20` |
| Source path | `cfg.memory.claudeMd.user` (default `~/.lingshu/CLAUDE.md`) |
| Output format | Same as `ProjectClaudeMdSource` — UTF-8 content or `null` |

**Validation**: Same as §3.1.

**Difference from `ProjectClaudeMdSource`**: Only the name and priority differ. Could theoretically be one class parameterized by path, but spec explicitly lists 4 separate Providers (US2 Scenario 1 counts them) — keeping them separate matches spec wording and allows per-source config in future Stories.

---

### 3.3 `IdentityMemorySource` (MemorySource impl)

**Location**: `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/IdentityMemorySource.java`

| Attribute | Value |
|---|---|
| `name()` | `"identity"` |
| `priority()` | `30` |
| Source data | `cfg.identity` (full Identity nested object) |
| Output format | Single-line JSON via `Jackson ObjectMapper.writeValueAsString(cfg.identity)` — e.g. `{"name":"lingShu-agent","language":"auto","traits":[]}` |
| Output on null Identity | `cfg.identity` cannot be null because `Identity.defaults()` always populates; but if somehow null, output `{}` (empty JSON object) |
| Purpose | Parallel pathway to structural [ROLE] segment — LLM sees Identity in 2 formats (structured [ROLE] block + machine-readable JSON in [PROJECT MEMORY]) |

**Validation**: None (Identity is always populated).

---

### 3.4 `ProjectTreeMemorySource` (MemorySource impl)

**Location**: `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectTreeMemorySource.java`

| Attribute | Value |
|---|---|
| `name()` | `"project-tree"` |
| `priority()` | `40` |
| Root directory | `cfg.sandbox.workingDirectory` (or `./` if null) |
| Depth | **1** (immediate children only — no recursion, per spec OOS-6) |
| File filter | `*.md` extension only (case-insensitive) |
| Directory filter | Skipped silently |
| Symlink filter | Not followed (uses `Files.list` which yields symlinks as-is; `Files.isDirectory(symlink)` returns false for broken symlinks → skipped) |
| Output format | Concatenated UTF-8 file contents separated by `\n\n── separator ──\n\n` between files |
| Output on empty directory | Return `null` (no files → source has nothing to contribute) |
| Output on missing root | Return `null` |
| Sort order | Alphabetical by filename (stable for cache key) |

**Validation**: None (all checks at runtime).

**v2 scope marker**: A code comment marks the depth-1 walk as v1-limited, with a TODO pointing to dsh spec OOS-6 for the future depth-N extension.

---

### 3.5 `ProjectClaudeMdSourceProvider` (Providers.MemorySourceProvider impl)

**Location**: `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectClaudeMdSourceProvider.java`

| Attribute | Value |
|---|---|
| `name()` | `"project-claude-md"` |
| `priority()` | `10` |
| `create(AgentConfig)` | `new ProjectClaudeMdSource(cfg)` — Provider holds no Spring state; passes cfg to impl via constructor |

**Registration**: `@Component` (auto-discovered by Spring component scan in `ai.lingshu.core.impl.memory` package).

**Bean name**: `memorySourceProvider_project-claude-md` (per constitution §11 #5 + dsh §5.5 v1.5.28 unique-name convention).

---

### 3.6 `UserClaudeMdSourceProvider`

| Attribute | Value |
|---|---|
| `name()` | `"user-claude-md"` |
| `priority()` | `20` |
| `create(AgentConfig)` | `new UserClaudeMdSource(cfg)` |
| Bean name | `memorySourceProvider_user-claude-md` |

---

### 3.7 `IdentityMemorySourceProvider`

| Attribute | Value |
|---|---|
| `name()` | `"identity"` |
| `priority()` | `30` |
| `create(AgentConfig)` | `new IdentityMemorySource(cfg)` |
| Bean name | `memorySourceProvider_identity` |

---

### 3.8 `ProjectTreeMemorySourceProvider`

| Attribute | Value |
|---|---|
| `name()` | `"project-tree"` |
| `priority()` | `40` |
| `create(AgentConfig)` | `new ProjectTreeMemorySource(cfg)` |
| Bean name | `memorySourceProvider_project-tree` |

---

## 4. New Router

### 3.9 `MemorySourceRouter` (NEW concrete Router)

**Location**: `lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java` (added as 6th inner `@Component`)

| Attribute | Value |
|---|---|
| Type parameters | `<Providers.MemorySourceProvider, MemorySource>` |
| Constructor injection | `List<Providers.MemorySourceProvider>` (Spring auto-injects all 4 default Providers) |
| Startup log | `[MemorySource] resolved 4 provider(s):` followed by `✓ identity -> IdentityMemorySourceProvider [priority=30]` etc. (4 lines) |
| Inherited `resolve(name, cfg)` | Single-source lookup, throws `IllegalArgumentException` if unknown (→ `LINGS-S01`) |
| **New** `resolveAll(List<String> names, AgentConfig cfg) → List<MemorySource>` | Ordered by input `names` list order (NOT by priority — priority is for **conflict resolution**, yml order is for **prompt assembly order**) |

**Critical design choice**: `resolveAll` does NOT sort by priority. The yml `memory-sources: [a, b, c]` order is **authoritative** for prompt assembly order. Priority is used internally by `SlotRouter` constructor only (to pick winners when multiple Providers share a `name()`). This matches spec US2 Acceptance Scenario 3 wording: "PromptBuilder 在 [PROJECT MEMORY] 段按解析顺序(yml 列表顺序)拼装".

**Injected into**: `DefaultPromptBuilderProvider` (NOT `AgentFactory` — see D-06).

---

## 5. Modified Provider

### 3.10 `DefaultPromptBuilderProvider` (modified)

**Change**: Add `@Autowired MemorySourceRouter memorySourceRouter` field. `create(config)` now:

```java
public PromptBuilder create(AgentConfig config) {
    List<MemorySource> sources = memorySourceRouter.resolveAll(
        config.getPrompt().getMemorySources(), config);
    return new DefaultPromptBuilder(sources);   // new constructor signature
}
```

**Constructor**: `DefaultPromptBuilder(List<MemorySource> memorySources)` — stores sources as final field; no longer reads `cfg.memory.claudeMd` directly (that responsibility moves to `ProjectClaudeMdSource` / `UserClaudeMdSource`).

---

## 6. Modified Builder

### 3.11 `DefaultPromptBuilder` (modified — existing from #001)

**Change**: 5-segment assembly now iterates `memorySources` list for [PROJECT MEMORY] segment instead of hardcoded `cfg.memory.claudeMd.project/user/extras` reads.

| Segment | Before (#001) | After (#002) |
|---|---|---|
| [ROLE] | Reads `cfg.identity` | **Unchanged** — still reads `cfg.identity` directly |
| [INSTRUCTIONS] | Reads `cfg.instructions` (file or inline) | **Unchanged** + **new**: applies `{{var}}` rendering if `instructions.templateEngine == "mustache"` (or `"none"` skips) |
| [PROJECT MEMORY] | Reads `cfg.memory.claudeMd.project/user` + `cfg.memory.extras` directly | **NEW**: iterates `this.memorySources` list, calls `load(ctx)` on each, joins non-null results with `\n\n── separator ──\n\n` |
| [CONVERSATION HISTORY] | Reads `ctx.session.history()` | **Unchanged** |
| [USER MESSAGE] | Reads `ctx.userInput()` | **Unchanged** |

**Template rendering helper** (new):
```java
private static String renderTemplate(String input, Map<String, String> vars) {
    if (input == null || vars == null || vars.isEmpty()) return input;
    // LinkedHashMap iteration order = declaration order — prevents later replacement
    // from clobbering earlier placeholders if values contain {{...}} substrings.
    String result = input;
    for (Map.Entry<String, String> e : vars.entrySet()) {
        result = result.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
    }
    return result;
}
```

---

## 7. Entity Relationship Summary

```
AgentFactory (existing, #001)
    │
    ├── @Autowired PromptBuilderRouter (existing, #001)
    │       │
    │       └── PromptBuilderRouter.resolve("default", cfg)
    │               │
    │               └── DefaultPromptBuilderProvider.create(cfg) [MODIFIED]
    │                       │
    │                       ├── @Autowired MemorySourceRouter [NEW]
    │                       │       │
    │                       │       └── resolveAll(cfg.prompt.memorySources, cfg)
    │                       │               │
    │                       │               └── List<MemorySource> = [IdentityMS, ProjectClaudeMdMS, ...]
    │                       │
    │                       └── new DefaultPromptBuilder(memorySources) [MODIFIED]
    │                               │
    │                               └── build(ctx) iterates memorySources for [PROJECT MEMORY] segment
```

**Total Story #002 delta**:
- **4** new concrete `MemorySource` classes
- **4** new `MemorySourceProvider` classes (one per concrete source)
- **1** new concrete `MemorySourceRouter` (added to existing `Routers.java`)
- **1** modified `DefaultPromptBuilderProvider` (added `@Autowired MemorySourceRouter`)
- **1** modified `DefaultPromptBuilder` (constructor change + segment refactor + template render)
- **0** changes to `AgentConfig` (defaults confirmed but already correct from #001)
- **0** changes to `AgentFactory` (no new Router in its 7-item validation list)
- **1** new example: `lingshu-examples/demo-engineer` (AC-09 black-box)
- **2** new test classes: `MemorySourceRouterTest`, `DefaultPromptBuilderTest`
