# Contract: MemorySource SPI

**Package**: `ai.lingshu.core.slot.MemorySource`
**Source**: dsh §4.5 + §5.3.1.1
**Stability**: SPI — public interface, breaking changes require RFC

---

## 1. Interface (existing — unchanged by Story #002)

```java
package ai.lingshu.core.slot;

import ai.lingshu.core.runtime.TurnContext;

public interface MemorySource {
    String name();
    int priority();
    String load(TurnContext ctx);
}
```

---

## 2. Method Contracts

### 2.1 `name()`

**Return**: Stable identifier string. Used for:
- yml configuration (`agent.prompt.memory-sources: [identity, project-tree]`)
- Router registration lookup (`MemorySourceRouter.resolve(name, cfg)`)
- Startup log identification

**Constraints**:
- MUST be unique across all `MemorySourceProvider` instances (dsh §5.2 same-name conflict rule)
- MUST be lowercase + kebab-case (e.g. `"project-claude-md"`, NOT `"ProjectClaudeMd"`)
- MUST NOT be empty
- MUST be stable across versions (changing it is a breaking change)

**Default Providers** (Story #002 lands all 4):
| Provider class | `name()` | `priority()` |
|---|---|---|
| `IdentityMemorySourceProvider` | `"identity"` | `30` |
| `ProjectClaudeMdSourceProvider` | `"project-claude-md"` | `10` |
| `UserClaudeMdSourceProvider` | `"user-claude-md"` | `20` |
| `ProjectTreeMemorySourceProvider` | `"project-tree"` | `40` |

### 2.2 `priority()`

**Return**: `int` — sort key for **same-name conflict resolution** in `SlotRouter` constructor. Larger value wins.

**Constraints**:
- Default implementations MUST use unique names, so priority is effectively irrelevant for #002's 4 defaults (no conflicts)
- Third-party Providers MAY use lower priorities (e.g. `priority() = 0`) and rely on the framework defaults winning
- MUST NOT be negative in defaults (per common Java convention); may be negative in alternative Providers if intentional
- Larger = wins; ties resolved by Spring injection order (unstable — third-party Providers should avoid ties)

### 2.3 `load(TurnContext ctx)`

**Return**: 
- `String` — the source's text contribution to `[PROJECT MEMORY]` segment
- `null` — the source has nothing to contribute this turn; `PromptBuilder` filters out null entries

**Constraints**:
- MUST be re-entrant: `load(ctx)` may be called multiple times in the same turn (e.g. after compaction)
- MUST NOT mutate `ctx.session().history()` (read-only contract per interface Javadoc)
- MUST NOT throw `IOException` for missing files — return `null` instead
- MUST NOT throw `RuntimeException` for missing config fields — return `null` instead
- MUST log at DEBUG level (not INFO/WARN/ERROR) when skipping — keeps AC-01-1 "stderr 零 ERROR" promise
- Output MUST be deterministic for the same `(ctx, cfg)` pair — required for prompt cache (Story #015)
- Output length MUST be < 1MB per source (hard cap; longer output is truncated with a `[truncated]` marker)

---

## 3. Source-Specific Behavior Contracts

### 3.1 `ProjectClaudeMdSource`

```java
String load(TurnContext ctx) {
    AgentConfig cfg = ctx.config();
    if (!cfg.getMemory().getClaudeMd().isEnabled()) return null;
    Path p = cfg.getMemory().getClaudeMd().getProject();
    return readFileIfExists(p);  // returns null on IOException or !exists
}
```

**Key behaviors**:
- Honors `cfg.memory.claudeMd.enabled == false` → return null
- File missing → return null (NOT exception)
- File present but empty → return null
- Symlinks followed
- Encoding: UTF-8 strict (invalid bytes → throw `MalformedInputException` → caught → return null + DEBUG log)

### 3.2 `UserClaudeMdSource`

Identical to §3.1 but reads `cfg.memory.claudeMd.user` instead. No additional behavior.

### 3.3 `IdentityMemorySource`

```java
String load(TurnContext ctx) {
    AgentConfig.Identity id = ctx.config().getIdentity();
    if (id == null) id = AgentConfig.Identity.defaults();
    try {
        return objectMapper.writeValueAsString(id);
    } catch (JsonProcessingException e) {
        LOG.warn("IdentityMemorySource: JSON serialization failed, falling back to toString", e);
        return id.toString();
    }
}
```

**Output example**:
```json
{"name":"lingshu-engineer","role":"Java 后端工程师","language":"zh","traits":["严谨","简洁","举反例"],"tone":"直接不啰嗦","avatar":null}
```

**Key behaviors**:
- Always returns non-null (Identity is always populated)
- Uses Jackson `ObjectMapper` already on classpath via Spring Boot BOM (no new dep)
- Falls back to `toString()` on serialization failure (defensive — should never trigger)

### 3.4 `ProjectTreeMemorySource`

```java
String load(TurnContext ctx) {
    Path root = ctx.config().getSandbox().getWorkingDirectory();
    if (root == null) root = Paths.get(".");
    if (!Files.isDirectory(root)) return null;
    List<Path> mdFiles;
    try (Stream<Path> stream = Files.list(root)) {
        mdFiles = stream
            .filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".md"))
            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
            .collect(Collectors.toList());
    } catch (IOException e) {
        return null;
    }
    if (mdFiles.isEmpty()) return null;
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < mdFiles.size(); i++) {
        if (i > 0) sb.append("\n\n── separator ──\n\n");
        sb.append(readFileIfExists(mdFiles.get(i)));
    }
    return sb.toString();
}
```

**Key behaviors**:
- Depth = 1 (immediate children only) — spec OOS-6
- File filter: `*.md` case-insensitive
- Sorted alphabetically by filename (deterministic for cache key)
- Symlinks: treated as regular files; broken symlinks → skipped via `Files.isRegularFile`
- Missing root directory → return null
- I/O error during list → return null

---

## 4. Router Contract: `MemorySourceRouter`

**Location**: `lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java` (added as 6th inner `@Component` class).

```java
@Component
public static class MemorySourceRouter
        extends SlotRouter<Providers.MemorySourceProvider, MemorySource> {
    public MemorySourceRouter(List<Providers.MemorySourceProvider> providers) {
        super(providers, "MemorySource", LoggerFactory.getLogger(MemorySourceRouter.class));
    }

    /** Resolve multiple sources by name, in input order (NOT priority-sorted). */
    public List<MemorySource> resolveAll(List<String> names, AgentConfig cfg) {
        if (names == null || names.isEmpty()) return Collections.emptyList();
        List<MemorySource> result = new ArrayList<>(names.size());
        for (String name : names) {
            result.add(resolve(name, cfg));  // throws on unknown name
        }
        return result;
    }
}
```

### 4.1 Startup Log Contract

On Spring boot, logs at INFO level:
```
[MemorySource] resolved 4 provider(s):
  ✓ project-claude-md -> ProjectClaudeMdSourceProvider [priority=10]
  ✓ user-claude-md -> UserClaudeMdSourceProvider [priority=20]
  ✓ identity -> IdentityMemorySourceProvider [priority=30]
  ✓ project-tree -> ProjectTreeMemorySourceProvider [priority=40]
```

### 4.2 Error Contract

```java
public List<MemorySource> resolveAll(List<String> names, AgentConfig cfg) {
    // ... throws IllegalArgumentException("Unknown MemorySourceRouter 'X'. Available: [...]")
    //         if X not in byName.keySet()
}
```

**Caller responsibility**: `DefaultPromptBuilderProvider.create(cfg)` catches this and re-throws with error code prefix `LINGS-S01 SLOT_NOT_FOUND` (via `IllegalArgumentException` → Spring's default exception handler logs at ERROR).

### 4.3 Thread-Safety Contract

The Router's `byName` map is built once in the constructor and never mutated. `resolve(name, cfg)` is a pure read. Safe for concurrent calls across multiple turns / sessions.

---

## 5. Lifecycle Contract

| Phase | Action |
|---|---|
| Spring boot | `MemorySourceRouter` constructor runs, builds `byName` map, logs startup summary |
| `AgentFactory.create(cfg)` → `PromptBuilderRouter.resolve("default", cfg)` | `DefaultPromptBuilderProvider.create(cfg)` runs, autowires `MemorySourceRouter`, calls `resolveAll(cfg.prompt.memorySources, cfg)` |
| Agent creation | `DefaultPromptBuilder(List<MemorySource>)` stores the resolved list as final field |
| Each turn's `build(ctx)` call | Iterates `memorySources`, calls `load(ctx)` on each, joins results |
| Agent disposal | No teardown — `DefaultPromptBuilder` becomes eligible for GC when Agent is GC'd |

---

## 6. Test Anchors (for `MemorySourceRouterTest`)

| Scenario | Setup | Expected |
|---|---|---|
| Empty list | `names = []` | Return `[]`; no error |
| Single known name | `names = ["identity"]` | Return 1-element list |
| 4 known names in order | `names = ["identity", "project-tree", "project-claude-md", "user-claude-md"]` | Return 4-element list **in input order** (not priority order) |
| Unknown name | `names = ["identity", "does-not-exist"]` | Throw `IllegalArgumentException` with message containing `"does-not-exist"` and available names |
| Null list | `names = null` | Return `[]`; no error |
| Duplicate names | `names = ["identity", "identity"]` | Return 2-element list with same instance twice (caller's responsibility to dedupe if undesired) |
