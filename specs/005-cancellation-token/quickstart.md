# Quickstart: Story #005 cancellation-token — Validation Steps

**Purpose**: 10-step black-box verification of AC-04 + Story #005 functional requirements. Each step is independently runnable.

---

## Setup

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu

# Confirm you're on the feature branch
git branch --show-current   # → story-005-cancellation-token

# Verify clean baseline
mvn -pl lingshu-core test -q   # all tests green BEFORE Story #005 changes (Story #001—#004)
```

---

## Step 1 — Confirm CancellationToken interface compiles (T007)

```bash
mvn -pl lingshu-core compile
echo "exit: $?"   # → 0

# Verify new file exists
ls lingshu-core/src/main/java/ai/lingshu/core/impl/concurrent/
# → CancellationTokens.java

# Verify interface change
grep -n "default void fire" lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutionContext.java
# → :73-78 area shows CancellationToken with new fire() default method
```

**Expected**: Exit 0; `CancellationTokens.java` exists; `default void fire()` present in nested interface.

---

## Step 2 — Confirm TurnContext + DefaultTurnContext + DefaultToolExecutionContext wired (T011)

```bash
mvn -pl lingshu-core compile
echo "exit: $?"   # → 0

# Verify interface methods
grep -n "CancellationToken cancellation" lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java
# → present

# Verify DefaultTurnContext.createWithBroadcast factory
grep -n "createWithBroadcast" lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java
# → present

# Verify DefaultToolExecutionContext returns shared token (NOT no-op)
grep -n "return turnCtx.cancellation" lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java
# → present
```

**Expected**: All 3 grep hits present.

---

## Step 3 — Run L1 Unit Tests (T014)

```bash
mvn test -Dtest='CancellationTokensTest,CancellationTokenSharingTest'
echo "exit: $?"   # → 0

# Verify 9 cases green
mvn test -Dtest='CancellationTokensTest' -Dsurefire.useFile=false 2>&1 | grep -E "Tests run:"
# → Tests run: 6
mvn test -Dtest='CancellationTokenSharingTest' -Dsurefire.useFile=false 2>&1 | grep -E "Tests run:"
# → Tests run: 3
```

**Expected**: 9 cases green (CancellationTokensTest × 6 + CancellationTokenSharingTest × 3).

---

## Step 4 — Confirm LinearTurnEngine cancellation wired (T017)

```bash
mvn -pl lingshu-core compile
echo "exit: $?"   # → 0

# Verify 3 wiring points
grep -nE "ctx.cancellation\(\)\.isCancelled|200, MILLISECONDS|tool cancelled by shutdown" \
  lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java
# → 3+ hits (loop head check, 200ms timeout fallback, dispatchParallel poll)
```

**Expected**: 3 wiring points found.

---

## Step 5 — Confirm AgentFactory broadcast + JVM hook wired (T020)

```bash
mvn -pl lingshu-core compile
echo "exit: $?"   # → 0

# Verify broadcastRegistry + shutdown hook
grep -nE "broadcastRegistry|addShutdownHook|lingshu-shutdown-cancel|broadcastCancel" \
  lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java
# → 4+ hits

# Verify DefaultAgent.buildContext uses createWithBroadcast
grep -n "createWithBroadcast" lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java
# → present
```

**Expected**: All wiring confirmed.

---

## Step 6 — Run AC-04 Black-Box Test (T023 — KEY VALIDATION)

```bash
mvn test -Dtest='LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms' \
  -Dsurefire.useFile=false
echo "exit: $?"   # → 0

# Verify wall-clock assertion passed
mvn test -Dtest='LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms' \
  -Dsurefire.useFile=false 2>&1 | grep -E "wall-clock|200ms|CANCELLED"
# → shows wall-clock measurement + TurnCompleted(stopReason=CANCELLED, ...)
```

**Expected**: Test passes; wall-clock ≤ 200ms; `RunResult.stopReason == StopReason.CANCELLED`.

**If flaky on CI**: increase threshold to 250ms (per plan.md R-02 mitigation).

---

## Step 7 — Run AgentFactory Broadcast Tests (T023)

```bash
mvn test -Dtest='AgentFactoryBroadcastCancelTest'
echo "exit: $?"   # → 0

mvn test -Dtest='AgentFactoryBroadcastCancelTest' -Dsurefire.useFile=false 2>&1 | grep -E "Tests run:"
# → Tests run: 3
```

**Expected**: 3 cases green.

---

## Step 8 — Run Full Test Suite — No Regression (T025)

```bash
mvn -pl lingshu-core test -q
echo "exit: $?"   # → 0

# Get total case count
mvn -pl lingshu-core test -Dsurefire.useFile=false 2>&1 | grep -E "Tests run:" | tail -1
# → Tests run: 29 (Story #001—#004: 16 cases + Story #005: 13 cases)
```

**Expected**: Exit 0; **all 29 tests pass** (no regression in Story #001—#004).

---

## Step 9 — R-13 Dependency Tree Self-Check (T026)

```bash
# Capture Story #005 final state
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-005-after.txt

# Diff against Story #004 baseline
diff /tmp/deps-004-baseline.txt /tmp/deps-005-after.txt
echo "diff exit: $?"   # → 0 (zero differences)

# Verify key subtrees unchanged
grep -E "spring-ai-core|opentelemetry-api|reactive-streams" /tmp/deps-005-after.txt | head -5
# → same as Story #004 baseline
```

**Expected**: `diff` exit 0; 0 new dependencies; key subtrees unchanged.

---

## Step 10 — Capture Outputs for PR Body (T027 + T028)

```bash
# AC-04 wall-clock output
mvn test -Dtest='LinearTurnEngineCancellationIT#ctrlC_stopsInflightTurn_under200ms' \
  -Dsurefire.useFile=false 2>&1 > /tmp/ac04-output.txt

# Total test summary
mvn -pl lingshu-core test -Dsurefire.useFile=false 2>&1 | grep -E "Tests run:" > /tmp/test-summary.txt

# dep-tree for PR body R-13 section
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree.txt

echo "Files captured for PR body"
ls -la /tmp/ac04-output.txt /tmp/test-summary.txt /tmp/dep-tree.txt
```

**Expected**: 3 output files captured, ready to paste into PR body.

---

## Done — Commit + Push + Open PR

```bash
git add specs/005-cancellation-token/ \
        lingshu-core/src/main/java/ai/lingshu/core/impl/concurrent/CancellationTokens.java \
        lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutionContext.java \
        lingshu-core/src/main/java/ai/lingshu/core/runtime/TurnContext.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultTurnContext.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutionContext.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/concurrent/CancellationTokensTest.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/CancellationTokenSharingTest.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineCancellationIT.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryBroadcastCancelTest.java

git commit -m "feat(agent): Story #005 cancellation-token — CancellationToken 三层贯通 + JVM shutdown hook (AC-04)"

git push origin story-005-cancellation-token
gh pr create --title "feat(agent): Story #005 cancellation-token — 三层贯通 + JVM shutdown hook (AC-04)" \
             --body "$(cat /tmp/pr-body.md)" --base main
```

**Expected**: Branch pushed, PR opened with full AC-04 evidence + R-13 dep-tree diff.

---

## Troubleshooting

### Symptom: Test "wall-clock > 200ms" intermittently fails

- Check CI load — try `-T 1C` to limit Maven parallelism
- Increase threshold: change `200` to `250` in T015 modification + T021 test assertion
- Add `CountDownLatch` sync inside EchoLlmProvider.stream() to ensure turn started before cancel

### Symptom: "CancellationToken.fire() not found"

- Verify T005 + T013 (default method) landed — `grep "default void fire" ToolExecutionContext.java`
- Verify SimpleCancellationToken override `fire()` exists — `grep "void fire" CancellationTokens.java`

### Symptom: NPE on `agentFactory.broadcastCancel()` empty registry

- Verify T018 implemented CopyOnWriteArrayList (not null ArrayList)
- The `for (token : broadcastRegistry)` is no-op for empty list — if NPE, you forgot the field initializer

### Symptom: LlmProvider.stream() doesn't honor cancellation (LLM keeps running)

- This is **expected** for Story #005 — deferred to Story #005b per plan.md D-04
- AC-04 black-box still passes (turn stops in 200ms even though LLM HTTP may continue briefly in background)

---

**Last updated**: 2026-09-20
**Story**: #005 cancellation-token