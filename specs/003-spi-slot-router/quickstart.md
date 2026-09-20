# Quickstart: AC-02 + AC-08 Black-Box Validation (Story #003)

**Branch**: `story-003-spi-slot-router` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

> Runnable validation scenarios that prove Story #003 acceptance criteria. End-to-end commands + expected outputs. No implementation code (that's in `tasks.md`).

---

## Prerequisites

- **JDK**: 17+ (Spring Boot 3.2.5 runtime requirement, R-06)
- **Maven**: 3.6.3+
- **Working dir**: `lingshu/` repo root
- **Branch**: `story-003-spi-slot-router` (created by Step 1)
- **No env vars**: All defaults are hard-coded in `application.yml` (per constitution §1 #11)

```bash
# Verify env
mvn -v       # Apache Maven 3.6.3+ / Java 17+
java -version
```

---

## Validation 1: AC-02 + AC-08 Black-Box — `SlotRouterCompatTest`

**Purpose**: Prove 6 compatibility scenarios via `SlotRouter` constructor fail-fast (US1 S2/S3 + US2 S1-S4).

### Step 1.1: Run the dedicated compat test

```bash
cd lingshu-core
mvn test -Dtest=SlotRouterCompatTest
```

### Expected Output

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running ai.lingshu.core.spi.SlotRouterCompatTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0

✓ test_compatible_exactMatch_pass
    LlmProvider provider 'anthropic' v1.0.0 compatible with slot v1.0.0 ✅
✓ test_compatible_minorLags_pass
    LlmProvider provider 'my-llm' v1.5.3 compatible with slot v1.10.0 (minor 5 ≤ 10) ✅
✓ test_incompatible_minorAhead_throwsLINGS-S05
    ProviderInitException: LINGS-S05: LlmProvider provider 'my-llm' v1.10.0 > slot contract v1.5.0 (provider uses APIs not yet declared)
    (hint: bump LlmProvider CONTRACT_VERSION to '1.10.0' or downgrade MyLlmProviderProvider.version() to '1.5.x')
    Caused by: IllegalArgumentException: Provider 'my-llm' minor 10 > slot minor 5
✓ test_incompatible_majorMismatch_throwsLINGS-S05
    ProviderInitException: LINGS-S05: LlmProvider provider 'my-llm' v2.0.0 incompatible with slot contract v1.0.0 (major version mismatch)
    Caused by: IllegalArgumentException: Provider 'my-llm' major 2 != slot major 1
✓ test_nullVersion_throwsLINGS-S05
    ProviderInitException: LINGS-S05: LlmProvider provider 'bad-llm' version() returned null
    (hint: implement version() returning '1.0.0' on your @Component class)
    Caused by: IllegalArgumentException: Provider 'bad-llm' version must not be null
✓ test_invalidSemver_throwsLINGS-S05
    ProviderInitException: LINGS-S05: LlmProvider provider 'bad-llm' version() 'v1' must be MAJOR.MINOR.PATCH (3 dot-separated non-negative integers, no 'v' prefix)
    Caused by: IllegalArgumentException: version 'v1' must have 3 dot-separated segments

[INFO] BUILD SUCCESS
[INFO] Total time: 4.231 s
```

### Pass Criteria

- [x] 6 tests pass
- [x] No `UnsupportedOperationException` (proves `validateProviderVersions` runs in constructor)
- [x] LINGS-S05 error code visible in 4/6 test failure messages
- [x] Cause chain ≥ 2 in failure tests
- [x] `hint` field non-empty in 3/6 failure tests (null version, major mismatch, minor ahead)

---

## Validation 2: AC-02 Black-Box — Startup Log Lists 9 Providers With Version

**Purpose**: Prove spec SC-002 — `AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion`.

### Step 2.1: Run the integration test

```bash
cd lingshu-core
mvn test -Dtest=AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion
```

### Expected Output

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running ai.lingshu.core.impl.runtime.AgentFactoryIntegrationTest

[INFO] [LlmProvider] resolved 1 provider(s) [contract v1.0.0]:
[INFO]   ✓ anthropic v1.0.0 -> AnthropicLlmProviderProvider [priority=10]
[INFO] [ToolExecutor] resolved 1 provider(s) [contract v1.0.0]:
[INFO]   ✓ default v1.0.0 -> DefaultToolExecutorProvider [priority=0]
[INFO] [PermissionPolicy] resolved 1 provider(s) [contract v1.0.0]:
[INFO]   ✓ allow-all v1.0.0 -> AllowAllPermissionPolicyProvider [priority=0]
[INFO] [PromptBuilder] resolved 1 provider(s) [contract v1.0.0]:
[INFO]   ✓ default v1.0.0 -> DefaultPromptBuilderProvider [priority=0]
[INFO] [FlowEngine] resolved 1 provider(s) [contract v1.0.0]:
[INFO]   ✓ linear v1.0.0 -> LinearTurnEngineProvider [priority=0]
[INFO] [MemorySource] resolved 4 provider(s) [contract v1.0.0]:
[INFO]   ✓ project-claude-md v1.0.0 -> ProjectClaudeMdSourceProvider [priority=10]
[INFO]   ✓ user-claude-md v1.0.0 -> UserClaudeMdSourceProvider [priority=20]
[INFO]   ✓ identity v1.0.0 -> IdentityMemorySourceProvider [priority=30]
[INFO]   ✓ project-tree v1.0.0 -> ProjectTreeMemorySourceProvider [priority=40]

[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Pass Criteria

- [x] 9 lines (across 6 Router headers) — one per default Provider
- [x] Each line format: `✓ <name> v<version> -> <ClassSimpleName> [priority=<n>]`
- [x] `[contract v1.0.0]` present in each header line
- [x] Provider order in MemorySource section matches priority asc (10, 20, 30, 40)
- [x] Test passes (AssertJ `containsSubsequence` on captured log)

---

## Validation 3: AC-08 Black-Box — `description()` Output Format

**Purpose**: Prove spec SC-003 — `AgentFactoryDescriptionTest`.

### Step 3.1: Run the description test

```bash
cd lingshu-core
mvn test -Dtest=AgentFactoryDescriptionTest
```

### Expected Output

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running ai.lingshu.core.impl.runtime.AgentFactoryDescriptionTest

AgentFactory v0.1.0-SNAPSHOT for JVM 17.0.10
LlmProvider: anthropic v1.0.0 (priority=10)
ToolExecutor: default v1.0.0 (priority=0)
PermissionPolicy: allow-all v1.0.0 (priority=0)
PromptBuilder: default v1.0.0 (priority=0)
FlowEngine: linear v1.0.0 (priority=0)
MemorySource: project-claude-md v1.0.0 (priority=10)
MemorySource: user-claude-md v1.0.0 (priority=20)
MemorySource: identity v1.0.0 (priority=30)
MemorySource: project-tree v1.0.0 (priority=40)
Turn=0 Session=abc123-def456

[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Pass Criteria

- [x] Output starts with `AgentFactory v<version> for JVM <java.version>`
- [x] 9 Slot lines (5 singleton + 4 MemorySource)
- [x] Format: `<SlotName>: <name> v<version> (priority=<n>)`
- [x] Output ends with `Turn=0 Session=<id>` (32-char hex)
- [x] Test passes

---

## Validation 4: AC-02 / AC-08 Black-Box — Error Code Chain

**Purpose**: Prove spec SC-004 — `ProviderInitExceptionTest`.

### Step 4.1: Run the exception test

```bash
cd lingshu-core
mvn test -Dtest=ProviderInitExceptionTest
```

### Expected Output

```
[INFO] Running ai.lingshu.core.spi.ProviderInitExceptionTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0

✓ test_errorCodeFieldIsLINGS-S05
    assertThat(ex.getErrorCode()).isEqualTo("LINGS-S05") ✅
✓ test_causeChainLengthAtLeast2
    assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class)
    assertThat(ex.getCause().getCause()).isNotNull()  // Optional 3rd level
✓ test_hintFieldNonEmptyOnVersionMismatch
    assertThat(ex.getHint()).contains("implement version()") ✅
✓ test_stderrErrorLogCaptured
    logback ListAppender captured:
      ERROR [LlmProviderRouter] LINGS-S05: LlmProvider provider 'bad-llm' v2.0.0 incompatible with slot contract v1.0.0
      (hint: bump LlmProvider CONTRACT_VERSION to '2.0.0' or downgrade MyLlmProviderProvider.version() to '1.x.x') ✅
```

### Pass Criteria

- [x] 4 tests pass
- [x] `errorCode()` returns `"LINGS-S05"`
- [x] Cause chain ≥ 2 levels
- [x] `hint()` contains actionable text
- [x] Logback ERROR-level entry captured

---

## Validation 5: SC-005 — Version Utility Unit Tests

**Purpose**: Prove spec SC-005 — `VersionTest` covers 10 scenarios.

### Step 5.1: Run the Version test

```bash
cd lingshu-core
mvn test -Dtest=VersionTest
```

### Expected Output

```
[INFO] Running ai.lingshu.core.spi.VersionTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0

✓ test_parse_validThreeSegment_returnsInt3
✓ test_parse_standardVersions_returnCorrectIntArray
✓ test_parse_withLargeNumbers_returnsCorrectIntArray
✓ test_parse_null_throwsIAE
✓ test_parse_empty_throwsIAE
✓ test_parse_vPrefix_throwsIAE
✓ test_parse_twoSegments_throwsIAE
✓ test_parse_fourSegments_throwsIAE
✓ test_parse_preRelease_throwsIAE
✓ test_parse_nonNumeric_throwsIAE
✓ test_isCompatible_exactMatch_returnsTrue
✓ test_isCompatible_minorLags_returnsTrue
✓ test_isCompatible_minorAhead_returnsFalse
✓ test_isCompatible_majorMismatch_returnsFalse
✓ test_isCompatible_patchComparison
✓ test_format_intArray_returnsSemverString
```

### Pass Criteria

- [x] 13+ tests pass (10+ scenarios)
- [x] All 7 exception cases throw `IllegalArgumentException` with descriptive message

---

## Validation 6: SC-006 — R-13 dependency:tree Self-Check

**Purpose**: Prove spec SC-006 — zero new dependencies (R-13 mitigation (d) hard constraint).

### Step 6.1: Run dependency:tree

```bash
cd lingshu
mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-003.txt
diff /tmp/deps-002-baseline.txt /tmp/deps-003.txt
```

### Expected Output

```
# /tmp/deps-002-baseline.txt (Story #002)
[INFO] ai.lingshu:lingshu-core:jar:0.1.0-SNAPSHOT
[INFO] +- ai.lingshu:lingshu-core:jar:0.1.0-SNAPSHOT
[INFO] |  +- org.springframework.boot:spring-boot-starter:jar:3.2.5
[INFO] |  +- org.projectlombok:lombok:jar:1.18.30
[INFO] |  +- org.reactivestreams:reactivestreams:jar:1.0.4
[INFO] |  +- com.fasterxml.jackson.core:jackson-databind:jar:2.15.4
[INFO] |  +- io.opentelemetry:opentelemetry-api:jar:1.32.0
[INFO] |  +- org.springframework.ai:spring-ai-bom:pom:1.0.0-M6
... (unchanged)

# /tmp/deps-003.txt (Story #003 — expect zero diff)
$ diff /tmp/deps-002-baseline.txt /tmp/deps-003.txt
# (no output — identical)
```

### Pass Criteria

- [x] `diff` output is empty
- [x] No `com.github.zafarkhaja:jsemver` or other semver library
- [x] No banned-dependencies (`banned-dependencies` enforcer passes)

---

## Validation 7: SC-007 — Full Test Suite Pass

**Purpose**: Prove spec SC-007 — `mvn -pl lingshu-core test` exits 0.

### Step 7.1: Run full test suite

```bash
cd lingshu
mvn -pl lingshu-core test
```

### Expected Output

```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running ai.lingshu.core.spi.VersionTest             ✅ 13 tests
[INFO] Running ai.lingshu.core.spi.SlotRouterCompatTest    ✅  6 tests
[INFO] Running ai.lingshu.core.spi.ProviderInitExceptionTest ✅  4 tests
[INFO] Running ai.lingshu.core.impl.runtime.AgentFactoryDescriptionTest ✅  5 tests
[INFO] Running ai.lingshu.core.impl.runtime.AgentFactoryIntegrationTest ✅ N+1 tests (added startup_listsAllProvidersWithVersion)
[INFO] Running ai.lingshu.core.impl.router.RoutersTest    ✅  N tests (unchanged)
[INFO] Running ai.lingshu.core.impl.memory.IdentityMemorySourceTest ✅ N tests
[INFO] Running ai.lingshu.core.impl.memory.ProjectTreeMemorySourceTest ✅ N tests
[INFO] Running ai.lingshu.core.impl.prompt.DefaultPromptBuilderTest ✅ N tests
[INFO] Tests run: X+Y, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Pass Criteria

- [x] All tests pass (existing Story #001 / #002 tests + new Story #003 tests)
- [x] No `@Component` circular dependency
- [x] No `NoSuchMethodError` (proves `version()` method resolution works)

---

## Validation 8: Live Demo — Manual FAIL-FAST Verification

**Purpose**: End-to-end manual sanity check (NOT in CI but useful for PR reviewers).

### Step 8.1: Create a "bad" Provider temporarily

```bash
cd lingshu-core/src/main/java/ai/lingshu/core/impl/llm
cat > BadVersionLlmProviderProvider.java << 'EOF'
package ai.lingshu.core.impl.llm;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

@Component
public class BadVersionLlmProviderProvider implements Providers.LlmProviderProvider {
    @Override public String name() { return "bad-version-llm"; }
    @Override public int priority() { return 100; }
    @Override public String version() { return "2.0.0"; }  // WRONG MAJOR
    @Override public LlmProvider create(AgentConfig config) {
        return new AnthropicLlmProvider("dummy");
    }
}
EOF
```

### Step 8.2: Run demo-empty with the bad provider

```bash
cd lingshu
mvn -pl lingshu-examples/demo-empty -am package -DskipTests
java -cp lingshu-examples/demo-empty/target/demo-empty-1.0.0.jar \
     -Dloader.main=ai.lingshu.examples.demoempty.DemoEmptyApplication \
     org.springframework.boot.loader.launch.PropertiesLauncher
```

### Expected Output

```
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/

:: Spring Boot ::               (v3.2.5)

... Spring banner ...

[LlmProvider] resolved 2 provider(s) [contract v1.0.0]:
  ✓ anthropic v1.0.0 -> AnthropicLlmProviderProvider [priority=10]
  ✓ bad-version-llm v2.0.0 -> BadVersionLlmProviderProvider [priority=100]
... (other 5 Routers log normally) ...

... (AgentFactory autowired) ...

***************************
APPLICATION FAILED TO START
***************************

Description:

Parameter 0 of constructor in ai.lingshu.core.impl.runtime.AgentFactory required a single bean, but 2 were found:

   ... (this is the WRONG error — should be ProviderInitException, not bean ambiguity)

# NOTE: This step needs adjustment — the actual flow may differ
# depending on Spring's bean resolution order. The correct behavior
# is that validateProviderVersions() throws ProviderInitException
# DURING BadVersionLlmProviderProvider's @Component construction,
# which Spring catches BEFORE AgentFactory is autowired.
```

**Alternative Expected Output (correct flow)**:

```
... Spring banner ...

[LlmProvider] resolved 2 provider(s) [contract v1.0.0]:
  ✓ anthropic v1.0.0 -> AnthropicLlmProviderProvider [priority=10]
  ✓ bad-version-llm v2.0.0 -> BadVersionLlmProviderProvider [priority=100]

ERROR [LlmProviderRouter] LINGS-S05: LlmProvider provider 'bad-version-llm' v2.0.0 incompatible with slot contract v1.0.0 (major version mismatch)
(hint: bump LlmProvider CONTRACT_VERSION to '2.0.0' or downgrade BadVersionLlmProviderProvider.version() to '1.x.x')
Caused by: IllegalArgumentException: Provider 'bad-version-llm' major 2 != slot major 1

***************************
APPLICATION FAILED TO START
***************************

Action:

Fix your Provider's version() to return a compatible semver.

Process finished with exit code 1
```

### Step 8.3: Clean up

```bash
rm lingshu-core/src/main/java/ai/lingshu/core/impl/llm/BadVersionLlmProviderProvider.java
```

### Pass Criteria

- [x] `ProviderInitException` thrown during Spring startup (NOT at runtime)
- [x] JVM exits with code 1
- [x] LINGS-S05 visible in stderr
- [x] `hint` field provides actionable guidance
- [x] Cause chain ≥ 2

---

## Validation 9: Live Demo — `description()` Self-Describe

**Purpose**: Verify `factory.create(cfg).description()` outputs 9 Slot lines.

### Step 9.1: Add a temporary println in demo-empty

```bash
# After agent creation, add:
System.out.println(agent.description());
```

Or use a one-off shell script:

```bash
cd lingshu
cat > /tmp/DescribeAgent.java << 'EOF'
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.AgentConfig;

public class DescribeAgent {
    public static void main(String[] args) {
        AgentFactory factory = new AgentFactory(/* autowired manually or via Spring */);
        AgentConfig cfg = AgentConfig.defaults();
        System.out.println(factory.create(cfg).description());
    }
}
EOF
```

### Expected Output

```
AgentFactory v0.1.0-SNAPSHOT for JVM 17.0.10
  LlmProvider: anthropic v1.0.0 (priority=10)
  ToolExecutor: default v1.0.0 (priority=0)
  PermissionPolicy: allow-all v1.0.0 (priority=0)
  PromptBuilder: default v1.0.0 (priority=0)
  FlowEngine: linear v1.0.0 (priority=0)
  MemorySource: project-claude-md v1.0.0 (priority=10)
  MemorySource: user-claude-md v1.0.0 (priority=20)
  MemorySource: identity v1.0.0 (priority=30)
  MemorySource: project-tree v1.0.0 (priority=40)
Turn=0 Session=<32-char-hex>
```

### Pass Criteria

- [x] Output contains all 9 Slot lines
- [x] Format matches exactly
- [x] Order matches 9 Slot × default Provider mapping

---

## Validation 10: PR Body Checklist (Required Sections)

**Purpose**: Verify PR body has all required sections (SC-008).

### Step 10.1: PR body template

```markdown
## Summary
Story #003 spi-slot-router — Provider.version() + SlotRouter 兼容性校验 (AC-02/08)

## Changes
- 🆕 Version utility class (parse / isCompatible / format)
- 🆕 ProviderInitException (LINGS-S05)
- 🆕 ContractVersionRef annotation
- Modified SlotProvider: + version() method
- Modified SlotRouter: 构造期校验 + describe()
- 9 Slot interfaces: + CONTRACT_VERSION constant
- 9 default Providers: + version()="1.0.0" + 真实 create() body
- AgentFactory: + description() method

## Test plan
- [x] VersionTest 13 tests pass
- [x] SlotRouterCompatTest 6 scenarios (AC-02/08)
- [x] ProviderInitExceptionTest 4 scenarios (LINGS-S05)
- [x] AgentFactoryDescriptionTest 5 tests (description format)
- [x] AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion (9 行)
- [x] mvn -pl lingshu-core test 全部 exit 0

### R-13 dependency:tree 自查
[粘贴 `mvn -pl lingshu-core dependency:tree -Dverbose=true` 完整输出]
[确认 diff = 0 与 Story #002 baseline]

### AC-02 / AC-08 黑盒输出
[粘贴 SlotRouterCompatTest 6 场景输出 + startup log 9 行 + description() 输出]
```

### Pass Criteria

- [x] All 4 sections present (Summary / Changes / Test plan / R-13)
- [x] R-13 section includes full `dependency:tree` output
- [x] AC section includes 3 black-box test outputs (compat test / startup log / description)

---

## Summary of Validation Steps

| # | Validation | AC Covered | SC Covered | Pass Criteria |
|---|---|---|---|---|
| 1 | SlotRouterCompatTest 6 场景 | AC-02 + AC-08 | SC-001 | 6 tests pass |
| 2 | Startup log 9 行 | AC-02 | SC-002 | logback ListAppender captures 9 lines |
| 3 | description() format | AC-08 | SC-003 | 9 lines + header/footer |
| 4 | ProviderInitException chain | AC-02 + AC-08 | SC-004 | 4 tests pass |
| 5 | Version utility | AC-02 + AC-08 | SC-005 | 13 tests pass |
| 6 | dependency:tree | AC-02 + AC-08 | SC-006 | diff = 0 |
| 7 | Full test suite | AC-02 + AC-08 | SC-007 | exit 0 |
| 8 | Manual FAIL-FAST demo | AC-02 | (demo) | JVM exit 1 + LINGS-S05 |
| 9 | description() live | AC-08 | (demo) | 9 lines visible |
| 10 | PR body checklist | AC-02 + AC-08 | SC-008 | 4 sections present |

**Story #003 is COMPLETE when all 10 validations pass.**

---

**Quickstart Author**:Claude Code(基于 spec.md + contracts/slot-version-compat.md + data-model.md)
**Quickstart Date**:2026-09-20
**Next Step**:`/speckit-tasks` 生成 tasks.md
