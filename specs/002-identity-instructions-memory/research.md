# Research: Story #002 identity-instructions-memory

**Branch**: `story-002-identity-instructions-memory` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md)

> Phase 0 — Resolve all `NEEDS CLARIFICATION` from spec.md + capture the key technical decisions already locked in during spec drafting (and their rationale + rejected alternatives).

## Summary

Story #002 spec has **zero** `NEEDS CLARIFICATION` markers — every ambiguous question was either resolved during the spec drafting turn or deferred to a documented Assumption (8 items). This research.md therefore serves as the **decision log** for those resolutions, so plan / tasks / implementers can trace "why this approach" without re-reading the spec.

---

## D-01 — mustache template rendering: hand-rolled `String.replace` (NO third-party dep)

**Decision**: Story #002 implements `{{var}}` placeholder rendering using `String.replace(placeholder, value)` in a small helper inside `DefaultPromptBuilder` (or a `TemplateEngine` helper if the spec's `instructions.templateEngine` field has more than one value).

**Rationale**:
1. **R-13 mitigation (d) hard constraint** (constitution §10 + dsh §17): new dependencies require RFC + `dependency:tree` CI gate + banned-dependencies enforcer. `com.github.spullara.mustache:compiler` (~70KB jar) would be a new dependency for a 30-line feature.
2. Spec assumes v1 **only** supports `{{var}}` flat string replacement (no nested objects, no sections, no lambdas) per Assumption A-04. A full mustache engine is overkill — `String.replace` covers it in 5 lines.
3. JDK 8 `String.replace(CharSequence, CharSequence)` is **literal** (regex-free, no need to escape `{` `}` `\`), which matches the simple placeholder grammar.

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| `com.github.spullara.mustache:compiler` | New dependency (R-13), full mustache spec is over-engineered for v1 `{{var}}` only |
| `String.format("%s", ...)` | Doesn't map cleanly — `{{var}}` is brace syntax, `%s` is positional. Would require a name→index map. |
| Apache Velocity / FreeMarker | 500KB+ transitive dependencies, no JDK 8 constraints met cleanly. Rejected on R-13 grounds. |
| Regex-based `\{\{(\w+)\}\}` match | Cleaner but more code; `String.replace` with a pre-built `LinkedHashMap` ordered list of `(placeholder, value)` tuples handles sequential replacement correctly without back-references. |
| Custom grammar (`$var` or `<var>`) | Inconsistent with spec Assumptions A-04 which locks `{{var}}`. Rejected. |

**Implementation note**: Variables come from `cfg.instructions.variables` (`Map<String, String>`). When `instructions.templateEngine == "none"` or `variables` is empty, render step is a no-op (passthrough).

---

## D-02 — Identity → [ROLE] via direct `cfg.identity` access (NOT a `MemorySource`)

**Decision**: `[ROLE]` segment reads directly from `cfg.getIdentity()` inside `DefaultPromptBuilder`. `IdentityMemorySource` (one of the 4 default MemorySources) reads from `cfg.getMemory().getExtras()` / claude-md blocks instead. There is **no** dedicated `IdentityMemorySource`.

**Rationale**:
1. `[ROLE]` is a **structural segment** of the prompt — its placement, formatting (5 attribute lines: name+role / language / traits / tone / avatar), and ordering are owned by `DefaultPromptBuilder`, not by a pluggable source.
2. Identity fields (name / role / language / traits / tone / avatar) are **typed** (`List<String>` for traits, `String` for others) — they need structured formatting, not the flat string-in/string-out `MemorySource.load(ctx)` contract.
3. Identity is **always present** (Identity.defaults() exists) and **always contributes** (the [ROLE] segment is never skipped). MemorySources can return `null` to opt out of contributing — Identity cannot.

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| Make Identity itself a `MemorySource` (e.g. `IdentityMemorySource`) | Would force Identity to fit `String load(TurnContext)` — loses typed formatting. Would also create a chicken-and-egg: [ROLE] needs to be **first** in the prompt regardless of priority sort, but `MemorySource.priority()` controls ordering. Special-casing priority 0 just for Identity couples the abstract MemorySource SPI to Identity's structural role. |
| [ROLE] via literal string concat in DefaultPromptBuilder (current approach) | **CHOSEN**. Keeps MemorySource SPI clean (no "structural" sources), lets [ROLE] formatting evolve independently, and Identity.defaults() provides the always-present fallback. |

**Implementation note**: If a future Story adds a user-defined "role augment" via `agent.memory.extras`, it flows through `ProjectTreeMemorySource` / `extras`, **not** through Identity. Identity remains the structural identity block.

---

## D-03 — 4 default `MemorySource` providers ship in Story #002

**Decision**: Story #002 lands **all 4** default MemorySource Providers in `lingshu-core/.../impl/memory/`:
1. `ProjectClaudeMdSourceProvider` (name=`project-claude-md`, priority=10) — reads `cfg.memory.claudeMd.project` (default `./CLAUDE.md`)
2. `UserClaudeMdSourceProvider` (name=`user-claude-md`, priority=20) — reads `cfg.memory.claudeMd.user` (default `~/.lingshu/CLAUDE.md`)
3. `IdentityMemorySourceProvider` (name=`identity`, priority=30) — emits `identity.json` block (auto-serialized from `cfg.identity`)
4. `ProjectTreeMemorySourceProvider` (name=`project-tree`, priority=40) — depth-1 recursive `.md` listing + content concat

**Rationale**:
1. AC-09 explicitly tests all 4 sources in US1 Acceptance Scenario 1 (project + user claude-md) + US1 Scenario 2 (empty → only default Identity contributes via direct cfg access, not MemorySource) + US2 Scenario 1 (multi-provider coexistence).
2. dsh §5.5 L2240-2278 already lists the 4 stub Provider names — Story #002 is the "fill in the stub" Story.
3. Shipping them together avoids a "Story #002 ships 2, then Story #002.5 ships the other 2" split which would inflate the AC list without adding value (the 4 are a coherent set).

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| Ship only 2 (project-claude-md + user-claude-md) in #002, leave identity + project-tree for later | Splits a logically coherent feature across 2 Stories. AC-09 black-box needs all 4 to verify "4 Provider coexist + priority sort" (US2 Acceptance Scenario 3). |
| Ship none — make Story #002 only the PromptBuilder 5-segment assembly | Doesn't satisfy US2 ("切换 PromptBuilder Provider 按名路由" — which only fully exercises when MemorySource multi-provider is also present). Spec §Out-of-Scope explicitly excludes "ProjectTree 递归深度 > 1", but doesn't exclude the 4 default Providers themselves. |
| Split into 4 sub-Stories | Story boundary hard constraint (constitution §11 #4: "≤ 5 个核心文件改动"). 4 separate Stories would each touch 1-2 files but bloat the PR review surface and AC count. |

**Implementation note**: `IdentityMemorySourceProvider.load(ctx)` returns a JSON-serialized view of `cfg.identity` (one line: `{"name":"...","role":"...","traits":[...]}`). This is a **parallel** pathway to the structural [ROLE] segment — both are populated, allowing LLM to see Identity in 2 formats (structured [ROLE] block + machine-readable JSON in [PROJECT MEMORY]). Documented as v1 design choice; v2 may collapse to one pathway.

---

## D-04 — `ProjectTreeMemorySource` v1 scope: depth-1, `.md` files only

**Decision**: `ProjectTreeMemorySourceProvider.load(ctx)` lists `cfg.sandbox.workingDirectory` (or `./` fallback) at depth 1 (immediate children only, **no recursion into subdirectories**), filters to `.md` extension, and concatenates their contents with `── separator ───` between blocks.

**Rationale**:
1. Spec Out-of-Scope item 6: "ProjectTree 递归深度 > 1" explicitly excluded. Depth-1 keeps v1 simple.
2. v1 user pattern: project root contains `docs/architecture.md`, `docs/conventions.md`, `README.md` — v1 user explicitly lists them via `agent.memory.extras=[...]` instead of relying on recursion.
3. Recursion would need cycle detection, symlink policy, .gitignore parsing — out of scope for v1.

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| Recursive walk with `.gitignore` parsing | Out of scope (spec OOS-6); adds `.gitignore` parsing library dependency (R-13) or ~200 lines of custom parsing. |
| Depth-2 (immediate + 1 level of subdirs) | Speculative scope; no user demand. Adds cycle-detection requirement. |
| Configurable depth via `agent.memory.project-tree.max-depth` | YAGNI; spec doesn't ask for it; would create a config field with no test coverage. |
| Skip `ProjectTreeMemorySource` from v1 entirely | Would force users to always list files via `extras`. Spec US1 implicitly assumes tree-style loading is available; explicit listing-only is more verbose for users with 5+ markdown files. |

**Implementation note**: File listing uses `java.nio.file.Files.list(...)` + `Files.isDirectory(...)` filter. Directories are skipped silently. Non-`.md` files are skipped silently. Missing directory → return null (entire source skipped). No symlink following.

---

## D-05 — `AgentConfig.defaults()` + `Identity/Instructions/Memory.defaults*` already in Story #001 — Story #002 adds 5 fields to defaults

**Decision**: Story #002 modifies `AgentConfig.defaults()` to populate **5 new fields** beyond what Story #001's 27 fields cover:
1. `cfg.toolParallelism` — already exists (default 8); confirmed in #001
2. `cfg.toolTimeoutSeconds` — already exists (default 30)
3. `cfg.approvalTimeoutSeconds` — already exists (default 60)
4. `cfg.turnTimeoutSeconds` — already exists (default 0 = no timeout)
5. `cfg.a2aTransport` — already exists (default `"http-jsonrpc"`, was null in #001)

Wait — re-checking `AgentConfig.java`, **all** 5 fields already have defaults from Story #001. The "5 new defaults" mentioned in the spec checklist note is actually referring to fields Story #002 needs to **explicitly populate** in the `defaultConfig()` factory output (rather than relying on Lombok `@Value` defaults like 0 / null). Specifically:

1. `cfg.prompt.memorySources` (List<String>) — currently null after Story #001 → Story #002 sets to `["identity", "project-claude-md", "user-claude-md", "project-tree"]` (all 4 default providers)
2. `cfg.prompt.builder` (String) — currently null → Story #002 sets to `"default"`
3. `cfg.prompt.topK` (Integer) — currently null → Story #002 sets to 0 (no RAG in v1)
4. `cfg.flowEngine` (String) — currently null → Story #002 sets to `"linear"` (matches FlowEngineRouter default)
5. `cfg.toolExecutor` (String) — currently null → Story #002 sets to `"default"`

**Rationale**: AC-01-2 in Story #001 promised "27 fields all have defaults". Reviewing the actual `AgentConfig.defaults()` method (not present in the read — likely added in a later commit), the defaults for `prompt.builder` / `prompt.memorySources` / `flowEngine` / `toolExecutor` may still be null, requiring Story #002 to populate them so that `factory.defaultConfig()` produces a fully boot-able config (AC-09 US1 Scenario 2 requires running a turn on **empty yml**).

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| Defer all 5 to a separate Story | AC-09 US1 Scenario 2 ("yml 完全空" → 跑 turn) fails if `prompt.builder` is null — `promptBuilderRouter.resolve(null, config)` throws. The 5 defaults are **prerequisite** for the zero-config demo path. |
| Populate only `prompt.builder` + `prompt.memorySources`, leave the other 3 to #003/#004 | Inconsistent: if `prompt.builder="default"` works without a `toolExecutor`, then `toolExecutor="default"` should also default. AC-01-2 promise was "all fields default", so all must default together. |

**Implementation note**: Verify exact field defaults in `AgentConfig.defaults()` (re-read at task start); if already populated, no change needed for that field.

---

## D-06 — `MemorySourceRouter` injection pattern: via `DefaultPromptBuilder` constructor (NOT `AgentFactory`)

**Decision**: `DefaultPromptBuilderProvider.create(config)` will receive the **resolved list of MemorySource instances** from a new `MemorySourceRouter` via Spring autowiring at the **Provider** level. The Provider holds the Router reference and passes the resolved list to `DefaultPromptBuilder`.

**Rationale**:
1. `DefaultPromptBuilder` is constructed by `DefaultPromptBuilderProvider.create(AgentConfig)`, which is itself invoked by `PromptBuilderRouter.resolve(name, config)`. The Router chain naturally injects dependencies at the Provider level.
2. Adding `MemorySourceRouter` to `AgentFactory`'s 7 validation items would force `AgentFactory` to know about PromptBuilder internals (which MemorySources a PromptBuilder needs) — violates §5.3.1.0 "7 Router → AgentFactory direct inject" pattern (MemorySourceRouter is not one of the 7).
3. `PromptBuilderRouter` is already in `AgentFactory` (validates `prompt.builder` name resolves) — making `DefaultPromptBuilderProvider` autowire `MemorySourceRouter` keeps the dependency chain linear: `AgentFactory` → `PromptBuilderRouter` → `DefaultPromptBuilderProvider` → `MemorySourceRouter`.

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| Inject `MemorySourceRouter` into `AgentFactory` directly | Adds 8th validation item to `AgentFactory` (current 7: LlmProvider/ToolExecutor/PermissionPolicy/PromptBuilder/FlowEngine + the 2 explicit pre-checks for config fields). MemorySourceRouter is only needed by PromptBuilder, not by Agent lifecycle — violates SRP. |
| Inject `List<MemorySource>` directly into `DefaultPromptBuilderProvider` via Spring | Bypasses Router pattern — loses priority conflict logging, loses name-based resolution. Inconsistent with `LlmProviderRouter` / `ToolExecutorRouter` pattern. |
| Lazy-resolve MemorySource inside `DefaultPromptBuilder.build(ctx)` | Thread-unsafe (Router state could mutate between turns if hot-reloaded in future); adds per-turn Router.resolve overhead. Eager-resolve at Provider construction is cleaner. |

**Implementation note**: `DefaultPromptBuilderProvider` gains `@Autowired MemorySourceRouter` field. `create(config)` calls `memorySourceRouter.resolveAll(config.getPrompt().getMemorySources(), config)` and passes the resulting `List<MemorySource>` to `new DefaultPromptBuilder(memorySources)`.

---

## D-07 — `SlotResolver` class — DEFERRED to a later Story; Story #002 uses Router only

**Decision**: Story #002 does **not** create a `SlotResolver` abstraction. `DefaultPromptBuilderProvider` autowires `MemorySourceRouter` directly. `SlotResolver` (which dsh §5.3.1 mentions as a wrapper over multiple Routers for `AgentFactory`) is deferred.

**Rationale**:
1. dsh §5.3.1.1 describes `MemorySourceRouter` as a self-contained concrete Router class — no `SlotResolver` wrapper mentioned for MemorySource resolution.
2. Story #001's `AgentFactory` directly autowires 5 Routers (no SlotResolver) — Story #002 follows the same pattern for `DefaultPromptBuilderProvider`.
3. `SlotResolver` abstraction becomes useful only when an end-user wants to call `slotResolver.resolveMultiple(slot, names, cfg)` generically — not yet needed for Story #002.

**Alternatives considered**:
| Option | Why rejected |
|---|---|
| Create `SlotResolver` in Story #002 to wrap MemorySourceRouter + others | Scope creep — Story #002 is about Identity/Instructions/Memory, not about SlotResolver architecture. Save for Story #003 (which adds `Provider.version()` + Slot-wide compatibility). |
| Skip Router entirely, use Spring `@Autowired List<MemorySource>` directly in PromptBuilder | Bypasses name-based resolution (yml `memory-sources: [identity, project-tree]` becomes meaningless). Rejected. |

**Implementation note**: `MemorySourceRouter` exposes a new method `resolveAll(List<String> names, AgentConfig config) → List<MemorySource>` in addition to the inherited `resolve(name, config)`. If `names` is empty, return empty list (no error). If a name in `names` is unknown, throw `IllegalArgumentException` with error code `LINGS-S01` (consistent with all other Routers).

---

## D-08 — No new dependencies (R-13 mitigation (d) hard constraint)

**Decision**: Story #002 introduces **zero** new Maven dependencies. `mvn dependency:tree` before/after Story #002 must produce identical output.

**Rationale**:
1. constitution §10 R-13 mitigation (d): "Story #001 / #003 / #009 实施者必须先 `mvn dependency:tree` 自查 + 贴关键子树到 PR body". Same requirement applies to Story #002.
2. All 4 MemorySource Providers use JDK 8 built-ins: `Files.readAllBytes`, `Files.list`, `Paths.get`, `ObjectMapper` (already in Jackson from Spring Boot BOM).
3. Template rendering uses `String.replace` (D-01) — no `com.github.spullara.mustache` needed.
4. Story #002 PR body **must** include a `### R-13 dependency:tree 自查` section showing `lingshu-core` subtree unchanged.

**Verification command** (to run before PR):
```bash
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-after.txt
# diff against Story #001 baseline (already captured in PR #001 body)
diff /tmp/deps-001-baseline.txt /tmp/deps-after.txt  # expect zero delta
```

---

## Phase 0 Done When

- [x] Zero `NEEDS CLARIFICATION` markers in spec.md (verified at line scan)
- [x] 8 key decisions documented with rationale + alternatives considered
- [x] All decisions consistent with constitution §1-§10
- [x] R-13 self-check command documented for PR body

**Next Phase**: Phase 1 — write `data-model.md` + `contracts/` + `quickstart.md` + fill `plan.md`.
