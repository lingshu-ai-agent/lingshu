# Quickstart: Story #002 — AC-09 Black-Box Validation

**Branch**: `story-002-identity-instructions-memory` | **Date**: 2026-09-20
**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

> Runnable validation guide for AC-09. Black-box = demo-engineer E2E + 5 unit test scenarios. **All commands assume `cwd = <lingshu repo root>`.**

---

## Prerequisites

- JDK 17+ on `PATH` (JDK 8 binary target; Spring Boot 3.2.5 + Spring AI 1.x need JDK 17+ runtime — R-06)
- Maven 3.6.3+ on `PATH`
- `ANTHROPIC_API_KEY` env var set (read from `~/.zshrc` or pass explicitly)

Verify:
```bash
mvn -v | head -3
echo "key length: ${#ANTHROPIC_API_KEY}"
```

---

## 1. L5 E2E Black-Box: `demo-engineer` (AC-09 US1 Scenario 1)

### 1.1 Build the example JAR

```bash
mvn -pl lingshu-examples/demo-engineer -am clean package -DskipTests
```

Expected: `BUILD SUCCESS` in ~60s. Output jar: `lingshu-examples/demo-engineer/target/demo-engineer-0.1.0-SNAPSHOT.jar`.

### 1.2 Inspect the example layout

```
lingshu-examples/demo-engineer/
├── pom.xml                                         # depends on lingshu-core + spring-boot-starter
├── src/main/resources/
│   ├── application.yml                             # identity + instructions + memory config
│   ├── prompts/system-engineer.md                  # [INSTRUCTIONS] source
│   └── CLAUDE.md                                   # [PROJECT MEMORY] source (project-claude-md)
└── src/main/java/ai/lingshu/examples/demoengineer/
    └── DemoEngineerApplication.java                # Spring Boot main + runs 1 turn
```

### 1.3 Run a turn + assert 5-segment assembly

```bash
time java -jar lingshu-examples/demo-engineer/target/demo-engineer-0.1.0-SNAPSHOT.jar "你是做什么的"
```

**Expected behavior** (within 30s budget per AC-09 spec):
- First token returned (the LLM starts streaming)
- Stderr shows:
  ```
  [LlmProvider] resolved 1 provider(s):
    ✓ anthropic -> AnthropicLlmProvider [priority=10]
  [PromptBuilder] resolved 1 provider(s):
    ✓ default -> DefaultPromptBuilderProvider [priority=0]
  [MemorySource] resolved 4 provider(s):
    ✓ project-claude-md -> ProjectClaudeMdSourceProvider [priority=10]
    ✓ user-claude-md    -> UserClaudeMdSourceProvider    [priority=20]
    ✓ identity          -> IdentityMemorySourceProvider  [priority=30]
    ✓ project-tree      -> ProjectTreeMemorySourceProvider [priority=40]
  AgentFactory.create: sessionId=<uuid> flowEngine=linear llm=anthropic/claude-3-5-sonnet-latest
  ```
- Stdout shows the LLM's response to "你是做什么的" (some Chinese text describing itself as a Java backend engineer)

**Hard assertions** (validate via `grep` in a follow-up script):
```bash
java -jar ... "1+1=几" 2>/tmp/stderr.log | head -1 > /tmp/stdout.log

# AC-09 US1 Acceptance Scenario 1 checks
grep -q "你是 lingshu-engineer" /tmp/stderr.log           # FAIL if absent
grep -q "Java 后端工程师"   /tmp/stderr.log               # FAIL if absent
grep -q "项目级长期记忆"     /tmp/stderr.log               # FAIL if CLAUDE.md not loaded (optional)
grep -qE '\[MemorySource\] resolved 4 provider\(s\)' /tmp/stderr.log  # FAIL if Router not registered
```

### 1.4 Negative test: missing CLAUDE.md should NOT error

```bash
mv lingshu-examples/demo-engineer/src/main/resources/CLAUDE.md /tmp/CLAUDE.md.bak

time java -jar lingshu-examples/demo-engineer/target/demo-engineer-0.1.0-SNAPSHOT.jar "你好"

# Check stderr has NO ERROR line
test -z "$(grep -E '^[^ ]*ERROR' /tmp/stderr.log)" && echo "PASS: no ERROR" || echo "FAIL: ERROR detected"

mv /tmp/CLAUDE.md.bak lingshu-examples/demo-engineer/src/main/resources/CLAUDE.md
```

---

## 2. L5 E2E Black-Box: Zero-config path (AC-09 US1 Scenario 2)

### 2.1 Run with empty yml

```bash
mkdir -p /tmp/lingshu-empty/src/main/resources
cat > /tmp/lingshu-empty/src/main/resources/application.yml <<'EOF'
spring:
  application:
    name: lsh-empty
# no agent.identity, no agent.instructions, no agent.memory blocks
EOF

# Use demo-empty from Story #001 (already builds empty-config path)
time java -jar lingshu-examples/demo-empty/target/demo-empty-0.1.0-SNAPSHOT.jar "1+1=几"
```

**Expected behavior** (within 30s budget):
- LLM returns `"2"` (or similar — exact text depends on model)
- Stderr shows all 5 Routers resolved
- System message contains only `"你是 lingShu-agent"` (defaults from `Identity.defaults()`)

---

## 3. L1 Unit Tests

Run the full lingshu-core test suite:
```bash
mvn -pl lingshu-core test
```

### 3.1 `MemorySourceRouterTest` (new — covers AC-09 US2 Scenario 3 + edge cases)

```bash
mvn -pl lingshu-core test -Dtest=MemorySourceRouterTest
```

**Test methods** (anchored to spec contracts/memory-source.md §6):

| Method | AC anchor | What it verifies |
|---|---|---|
| `resolveAll_emptyList_returnsEmpty` | spec D-07 | `names = []` → `[]` (no error) |
| `resolveAll_nullList_returnsEmpty` | edge case | `names = null` → `[]` (defensive) |
| `resolveAll_singleKnownName_returnsOne` | spec US2 Scenario 3 | `["identity"]` → 1-element list |
| `resolveAll_fourKnownNames_preservesInputOrder` | spec US2 Scenario 3 | yml order (not priority order) is authoritative |
| `resolveAll_unknownName_throws` | spec US2 Scenario 2 | `IllegalArgumentException` with available names in message |
| `resolve_knownName_returnsCorrectProvider` | basic | `resolve("identity", cfg)` returns `IdentityMemorySource` |
| `constructor_resolvesAllFourDefaults` | dsh §5.3.1.0 startup log | 4 Providers registered, startup log format |

### 3.2 `DefaultPromptBuilderTest` (new — covers AC-09 US1 + US3)

```bash
mvn -pl lingshu-core test -Dtest=DefaultPromptBuilderTest
```

**Test methods** (anchored to spec contracts/prompt-builder.md §8):

| Method | AC anchor | What it verifies |
|---|---|---|
| `build_emptyConfig_onlyRoleSegment` | spec US1 Scenario 2 | Empty cfg → system message contains only `"你是 lingShu-agent"` |
| `build_identityOnly_roleSegmentFormatted` | spec US1 Scenario 1 | Full identity → all 4 [ROLE] lines in correct order with `、` separator |
| `build_instructionsMustache_rendersVars` | spec FR-005 + US1 Scenario 1 | `{{name}}` → `vars.get("name")` |
| `build_instructionsUnknownPlaceholder_passthrough` | edge case | `{{unknown}}` kept literal |
| `build_claudeMdMissing_noError_noSegment` | spec US3 Scenario 1 | Missing file → silent skip, no ERROR log |
| `build_claudeMdDisabled_skippedEvenIfFileExists` | spec US3 Scenario 2 | `enabled=false` → skip |
| `build_fourMemorySourcesJoinedWithSeparators` | spec US1 Scenario 1 | 4 non-null → 3 separators between them |
| `build_twoMemorySourcesNull_remainingJoined` | edge case | Only 1 separator (between the 2 non-null) |
| `build_userMessageAlwaysPresent_evenIfNullInput` | hard contract | `Message.User("")` is always the last entry |
| `build_sessionHistoryInOrderBetweenSystemAndUser` | hard contract | messages[0]=system, messages[1..N-2]=history, messages[N-1]=user |

### 3.3 `IdentityMemorySourceTest` (new)

```bash
mvn -pl lingshu-core test -Dtest=IdentityMemorySourceTest
```

**Test methods**:

| Method | What it verifies |
|---|---|
| `load_returnsJsonOfIdentity` | Output is valid JSON containing all 6 fields |
| `load_nullIdentity_returnsDefaultsJson` | Falls back to `Identity.defaults()` (defensive) |
| `load_isDeterministic_forSameConfig` | Calling twice returns identical strings (cache key test) |

### 3.4 `ProjectTreeMemorySourceTest` (new)

```bash
mvn -pl lingshu-core test -Dtest=ProjectTreeMemorySourceTest
```

**Test methods**:

| Method | What it verifies |
|---|---|
| `load_emptyDir_returnsNull` | No `.md` files → null |
| `load_threeMdFiles_concatenatedInAlphabeticalOrder` | Files sorted alphabetically, joined with separator |
| `load_mixedExtensions_onlyMdIncluded` | `.txt` / `.java` skipped |
| `load_subdirectoryMd_notIncluded` | Spec OOS-6: depth=1 only, subdir `.md` not picked up |
| `load_brokenSymlink_skipped` | `Files.isRegularFile` filters broken links |
| `load_missingRootDir_returnsNull` | No exception |
| `load_ioExceptionDuringList_returnsNull` | Caught silently |

### 3.5 Run all 4 test classes

```bash
mvn -pl lingshu-core test -Dtest='MemorySourceRouterTest,DefaultPromptBuilderTest,IdentityMemorySourceTest,ProjectTreeMemorySourceTest'
```

Expected: `Tests run: 25, Failures: 0, Errors: 0` (approximate — adjust based on actual test method counts).

---

## 4. L2 Slice Test: Wiring Verification

```bash
mvn -pl lingshu-core test -Dtest=AgentFactoryIntegrationTest
```

**Verify** (added by #001, should still pass):
- Spring context loads
- All 5 Routers + 1 FlowEngineRouter autowired with 1+ Providers each
- `agentFactory.create(defaultConfig())` succeeds (no `IllegalArgumentException`)

---

## 5. R-13 Mitigation (d) Self-Check

**MANDATORY** before opening the PR. Adds zero new dependencies.

```bash
# Capture baseline from Story #001 (assume already in git history; if not, build #001 HEAD first)
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-002.txt

# Compare against Story #001 baseline (commit 83688ba or whichever is the latest #001 commit)
git show 83688ba:lingshu-core/pom.xml > /tmp/pom-001.xml
# (Run `mvn install` of #001 to get baseline; or use `git checkout 83688ba` in a worktree)

# Expected diff: zero lines
diff /tmp/deps-001-baseline.txt /tmp/deps-002.txt
```

**Paste the diff (likely empty) into PR body** under:
```
### R-13 dependency:tree 自查

(Section per constitution §10 R-13 + SOP §3.2 + §3.4)

\`\`\`
[output of mvn dependency:tree -pl lingshu-core -Dverbose=true]
\`\`\`

Delta vs Story #001 baseline: 0 dependencies added
```

---

## 6. Acceptance Checklist (paste into PR body)

```
- [ ] AC-09 US1 Scenario 1 (demo-engineer 5-segment assembly): ✅
- [ ] AC-09 US1 Scenario 2 (zero-config empty yml): ✅
- [ ] AC-09 US1 Scenario 3 (Identity/Instructions/Memory defaults): ✅
- [ ] AC-09 US2 Scenario 1 (multi-Provider coexistence): ✅
- [ ] AC-09 US2 Scenario 2 (unknown builder fail-fast): ✅
- [ ] AC-09 US2 Scenario 3 (priority sort + yml order): ✅
- [ ] AC-09 US3 Scenario 1 (missing CLAUDE.md silent): ✅
- [ ] AC-09 US3 Scenario 2 (claude-md.enabled=false): ✅
- [ ] AC-09 US3 Scenario 3 (missing extras entry silent): ✅
- [ ] AC-09 US4 (mustache {{var}} rendering): ✅
- [ ] AC-09 reverse AC: no I/O exception propagates from build(): ✅
- [ ] R-13 dependency:tree delta: 0 (zero new deps)
- [ ] mvn -pl lingshu-core test: all green
- [ ] Binary size baseline: < 35MB (constitution §3)
```

---

## 7. Failure Triage Guide

| Symptom | Likely cause | Fix |
|---|---|---|
| `IllegalArgumentException: Unknown PromptBuilderRouter 'X'` at startup | YML typo in `agent.prompt.builder` | Fix yml to one of: `default`, `identity-only` (if implemented), or add a Provider for `X` |
| `IllegalArgumentException: Unknown MemorySourceRouter 'X'` at startup | YML typo in `agent.prompt.memory-sources` | Fix yml entry to one of: `identity`, `project-claude-md`, `user-claude-md`, `project-tree` |
| `[PROJECT MEMORY]` segment missing entirely | All 4 sources returned null | Check `./CLAUDE.md` exists + `~/.lingshu/CLAUDE.md` exists + identity defaults applied |
| LLM response ignores Identity / Instructions | System message too short / malformed | Run `DefaultPromptBuilderTest` to verify 5-segment assembly output |
| `IOException` in stderr | A `MemorySource` forgot to catch | Check `load(ctx)` implementation; all 4 catch + return null |
| `WARNING: An illegal reflective access operation has occurred` | Java 9+ module warning, not a #002 bug | Ignore (Spring Boot 3.x + JDK 17 known issue) |
| Build failure: `package ai.lingshu.core.spi does not exist` | Missing import in new file | Add `import ai.lingshu.core.spi.Providers;` |
