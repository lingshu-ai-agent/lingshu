# Quickstart: Story #004 tool-parallel-dispatch — Validation Steps

**Purpose**: 10-step black-box verification of AC-03 + Story #004 functional requirements. Each step is independently runnable.

---

## Setup

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu

# Confirm you're on the feature branch
git branch --show-current   # → story-004-tool-parallel-dispatch

# Verify clean baseline
mvn -pl lingshu-core test -q   # all tests green BEFORE Story #004 changes
```

---

## Step 1 — Confirm LlmProvider mock + Tool mock compile (T007)

```bash
mvn -pl lingshu-core test-compile
echo "exit: $?"   # → 0
ls lingshu-core/src/test/java/ai/lingshu/core/impl/flow/support/
# → EchoLlmProvider.java  SleepTool.java
```

**Expected**: Exit 0; both fixture classes listed.

---

## Step 2 — DefaultToolExecutor translation unit tests (T015)

```bash
mvn test -Dtest=DefaultToolExecutorTest
```

**Expected**: 6 cases green:
- `dispatch_success_returnsOriginalResult` (regression)
- `dispatch_permissionDenied_returnsErrorResult`
- `dispatch_toolNotFound_returnsErrorResult`
- `dispatch_permissionAskUser_returnsErrorResult`
- `dispatch_unexpectedException_returnsErrorResult`

---

## Step 3 — Verify DefaultToolExecutor throws become ToolResult.error (FR-007/FR-008)

```bash
mvn test -Dtest=DefaultToolExecutorTest#dispatch_permissionDenied_returnsErrorResult -Dsurefire.useFile=false
```

**Expected output**:
```
[ERROR] Permission denied: <reason>  → ToolResult{status=ERROR, toolUseId=call-1, content="Permission denied: <reason>", isError=true}
```

---

## Step 4 — agentToolPool bean registers (T017 + NFR-003)

```bash
mvn -pl lingshu-core test -Dtest=ToolExecutorConfigTest -Dsurefire.useFile=false 2>&1 | grep -E "(agentToolPool|lingshu-tool-|daemon)" | head -20
```

**Expected**:
- Pool size = `availableProcessors() * 2`
- Thread names start with `lingshu-tool-`
- `daemon=true` set on every thread

---

## Step 5 — LinearTurnEngine sequential tool dispatch (T029)

```bash
mvn test -Dtest=LinearTurnEngineToolDispatchTest
```

**Expected**: 4 cases green:
- `sequentialToolDispatch_toolResultsInOrder`
- `toolError_translatedToToolResult_turnContinues`
- `toolNotFound_translatedToToolResult_turnContinues`
- `noToolCalls_emitsTurnCompletedImmediately`

---

## Step 6 — LinearTurnEngineProvider final fields wired (T029)

```bash
mvn test -Dtest=LinearTurnEngineProviderTest -Dsurefire.useFile=false
```

**Expected output**:
```
Engine fields: promptBuilder=<non-null>, llmProvider=<non-null>, toolExecutor=<non-null>, permissionPolicy=<non-null>, toolPool=<non-null>
```

---

## Step 7 — **AC-03 Black-Box Validation** (T034) — THE MAIN GOAL

```bash
mvn test -Dtest=LinearTurnEngineParallelDispatchTest#blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s -Dsurefire.useFile=false
```

**Expected output (key line)**:
```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] Total time: ~1.2s

LinearTurnEngineParallelDispatchTest.blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s PASSED
```

**Manual cross-check**:
- Serial baseline (parallelism=1): `mvn test -Dtest=LinearTurnEngineParallelDispatchTest#parallelism1_wallClock_about4s_serial` → ~4.0s
- Parallel (parallelism=4): ≤ 1.3s
- **Speedup ratio: ≥ 3.0×** ✅

---

## Step 8 — All parallel dispatch tests pass (T034)

```bash
mvn test -Dtest=LinearTurnEngineParallelDispatchTest -Dsurefire.useFile=false
```

**Expected**: 4 cases green:
- `blackBox_4tools_1sEach_parallelism4_wallClock_under1_3s` ← **AC-03**
- `parallelism1_wallClock_about4s_serial`
- `parallelismMinus1_unbounded_allConcurrent`
- `mixedSuccessAndFailure_resultsPreserveOriginalOrder`

---

## Step 9 — Full Story #004 regression (T036)

```bash
mvn -pl lingshu-core test
```

**Expected**: All Story #001 + #002 + #003 + #004 tests green (16 + previous stories' counts). Zero failures.

---

## Step 10 — R-13 dependency:tree self-check (T037)

```bash
# Capture Story #003 baseline (taken BEFORE Story #004 changes)
cat /tmp/deps-003-baseline.txt > /tmp/baseline-snapshot.txt
wc -l /tmp/baseline-snapshot.txt   # record line count

# Run Story #004 dependency:tree
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-004-after.txt

# Diff
diff /tmp/baseline-snapshot.txt /tmp/deps-004-after.txt
echo "diff exit: $?"   # → 0 (no diff = 0 new deps)

# Confirm key transitive deps unchanged
grep -E "(spring-context|jackson-databind|junit-jupiter|assertj|mockito)" /tmp/deps-004-after.txt | head -10
```

**Expected**:
- `diff` exit 0 (zero new dependencies)
- Key deps unchanged from Story #003 baseline
- No `spring-ai-` related deps (R-13 mitigation (a) — Story #001 follow-up)

---

## Validation Matrix

| Step | AC / FR / NFR | Test Class | Time |
|---|---|---|---|
| 1 | T007 | compile check | 30s |
| 2 | FR-007/FR-008 | DefaultToolExecutorTest | 5s |
| 3 | FR-007 | DefaultToolExecutorTest (single) | 5s |
| 4 | FR-006 / NFR-003 | ToolExecutorConfigTest | 5s |
| 5 | US1 + FR-001 | LinearTurnEngineToolDispatchTest | 10s |
| 6 | US3 / FR-005 | LinearTurnEngineProviderTest | 5s |
| **7** | **AC-03 黑盒** | **LinearTurnEngineParallelDispatchTest** | **~5s** |
| 8 | US2 + FR-002/FR-003 | LinearTurnEngineParallelDispatchTest | ~30s |
| 9 | All Stories | full suite | ~60s |
| 10 | R-13 mitigation (d) | dep-tree diff | ~10s |

**Total validation time**: ~3 minutes

---

## Pass Criteria

- All 10 steps pass
- AC-03 wall-clock ≤ 1.3s confirmed (Step 7)
- Speedup ratio ≥ 3.0× confirmed (Step 7 vs Step 7b)
- `mvn dependency:tree` diff = 0 (Step 10)
- No `Lint:` warnings or `deprecated:` notices in LinearTurnEngine

**Story #004 sign-off**: ready for PR after all 10 steps pass.
