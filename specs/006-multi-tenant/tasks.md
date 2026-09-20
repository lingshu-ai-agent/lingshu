# Tasks: Story #006 multi-tenant

**Input**: Design documents from `/specs/006-multi-tenant/`
- spec.md(6 User Stories US1—US6 + 14 Edge Cases + FR-001—FR-016 + NFR-001—NFR-009)
- plan.md(11-step implementation order + 6 new + 4-8 modified files + 8 new test files + 19 L1 + 1 L5 E2E test cases)
- research.md(8 design decisions D-01—D-08 resolved)
- data-model.md(6 entities + state transitions + validation rules)
- contracts/tenant-context.md(11 sections full contract)
- quickstart.md(6 Validation scenarios)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#005 全部 merged(provides AgentFactory / AgentConfig / RuntimeSandbox / MemorySource / SessionStore / CostTracker / CancellationToken / LinearTurnEngine / DefaultTurnContext)

**Tests**: Required per FR-001—FR-016 + 9 NFR + 14 Edge Cases。19 L1 Unit + 1 L5 E2E = 20 测试用例。

**Constitution**: v1.0 — §1 #9 Plugin 发现 Spring SPI / §1 #11 默认实现位置 / §1 #12 启动时配置校验 / §2 13 依赖锁定(R-13 零新增)/ §4 错误码约定(0 新增 ErrorCode)/ §5 7 层金字塔 / §10 R-02 多租户 ThreadLocal 泄漏缓解

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3 / US4 / US5 / US6)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + create branch + capture Story #005 dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #005 dependency baseline: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-005-baseline.txt`
- [ ] T003 Create + checkout feature branch `story-006-multi-tenant` from main: ✅ **DONE**(已完成)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core test` exits 0(Story #005 tests all green — pre-implementation sanity)

**Checkpoint**: Setup ready — code modifications can begin.

---

## Phase 2: Foundational — TenantContext Utility + TenantConfig + AgentConfig.tenants(US1 基础 + US6 数据结构)

**Purpose**: Establish the foundational utility classes + config types **before** any user story wiring

- [ ] T005 [US1] Create `TenantContext` utility in `lingshu-core/src/main/java/ai/lingshu/core/tenant/TenantContext.java`:
  - `public final class TenantContext`(non-instantiable, private constructor)
  - `private static final ThreadLocal<Deque<String>> STACK = new ThreadLocal<>()`(FR-001 + D-01 Deque 嵌套栈)
  - Static methods:
    - `public static String current()` → `Deque<String> s = STACK.get(); return s == null || s.isEmpty() ? null : s.peekLast();`
    - `public static void set(String tenantId)`:
      - Validate `tenantId.matches("[a-zA-Z0-9_-]{1,64}")` else `IllegalArgumentException`(FR-001)
      - `Deque<String> s = STACK.get(); if (s == null) { s = new ArrayDeque<>(); STACK.set(s); } s.addLast(tenantId);`
    - `public static void clear()` → `Deque<String> s = STACK.get(); if (s != null && !s.isEmpty()) s.pollLast();`(NFR-005 + I-6 栈空 no-op)
    - `public static String snapshot()` → `return current();`(String 不可变,等同拷贝)
    - `public static <T> T runAs(String tenantId, Supplier<T> work)`:
      - `set(tenantId); try { return work.get(); } finally { clear(); }`(FR-005 try-finally 兜底)
    - `public static void runAs(String tenantId, Runnable work)`:
      - `runAs(tenantId, () -> { work.run(); return null; });`(void 版)
    - `public static <T> T runWithSnapshot(String snapshot, Supplier<T> work)`:
      - `if (snapshot == null) throw new IllegalArgumentException("snapshot must not be null");`
      - `return runAs(snapshot, work);`(FR-006 + D-02 跨线程显式传递)
  - 类 Javadoc 明确「**不**用 InheritableThreadLocal — 跨线程必须用 snapshot + runWithSnapshot 显式传递」(FR-015 + R-02 (c))
- [ ] T006 [US6] Create `TenantConfig` immutable config in `lingshu-core/src/main/java/ai/lingshu/core/tenant/TenantConfig.java`:
  - `@Value @Builder public class TenantConfig`
  - Fields: `String tenantId`, `Memory memory`, `Sandbox sandbox`, `Cost cost`(FR-007)
  - 嵌套 `@Value @Builder public static class Memory { Path dir; }`
  - 嵌套 `@Value @Builder public static class Sandbox { List<String> commandWhitelist; }`(构造时 `Collections.unmodifiableList(new ArrayList<>(...))` 深拷贝)
  - 嵌套 `@Value @Builder public static class Cost { long sessionBudgetMicros; }`
  - `@Builder(toBuilder = true)` 允许深拷贝构造(测试用)
- [ ] T007 [US6] Modify `AgentConfig` in `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`:
  - 加 import: `ai.lingshu.core.tenant.TenantConfig` + `java.util.HashMap` + `java.util.LinkedHashMap`
  - 加 field: `TenantsConfig tenants`(FR-009)
  - 加 nested: `@Value @Builder public static class TenantsConfig { boolean enabled; Map<String, TenantConfig> map; public void validate() { ... } public static TenantsConfig defaults() { return new TenantsConfig(false, Collections.emptyMap()); } }`
  - `TenantsConfig.validate()` 方法实现 4 检查项(FR-010):
    - `map.size() > 1000` → 累加错误 "tenants.map.size() = N exceeds limit 1000"
    - 每 entry: tenantId 格式校验 + tenantConfig 非 null + key == value.tenantId + memory.dir 非 null + sandbox.commandWhitelist 非 null + cost.sessionBudgetMicros > 0
    - 任一错误累加到 `List<String> errors`
    - 最终 `if (!errors.isEmpty()) throw new LingsConfigException("C02", "tenants config validation failed:\n  - " + String.join("\n  - ", errors));`
  - 既有 27+ 字段**不**改(向后兼容 — Story #001—#005 测试不挂)
- [ ] T008 [P] [US6] Modify `AgentConfigDefaults` in `lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java`:
  - 加 `cfg.getTenants()` 默认值调用:`TenantsConfig.defaults()`(FR-009 + US6 S3 单租户 fallback)
  - 既有 27+ 字段 defaults **不**改
- [ ] T009 Validate compile: `mvn -pl lingshu-core compile` exits 0(TenantContext + TenantConfig + AgentConfig.tenants compile standalone)

**Checkpoint**: Foundational utility + config ready — user stories can now wire.

---

## Phase 3: User Story 1 — TenantContext 基础 ThreadLocal 切换(US1)

**Goal**: 实现 `TenantContext.runAs(...)` / `runWithSnapshot(...)` + 嵌套栈 + try-finally 兜底 + cross-thread snapshot(US1 S1-S4 + FR-001/005/006/015)

**Independent Test**: `TenantContextTest` 6 个 L1 用例全过 — current/set/clear/runAs basic/nested/exception cleanup/runWithSnapshot

### Tests for User Story 1(L1 Unit)

- [ ] T010 [P] [US1] Write `TenantContextTest` in `lingshu-core/src/test/java/ai/lingshu/core/tenant/TenantContextTest.java`:
  - `L1-001`:`#current_initialState_isNull` — FR-001(未 set 时 current() == null)
  - `L1-002`:`#set_thenCurrent_returnsSetValue` — FR-001(set("alice") + current() == "alice")
  - `L1-003`:`#runAs_basicBlock_clearsAfterCallback` — FR-005 + I-3(runAs 结束 current() == null)
  - `L1-004`:`#runAs_nested_innerDoesNotPolluteOuter` — I-2(嵌套栈式保存)
  - `L1-005`:`#runAs_cleanupOnException` — FR-005 + R-02 (a)(异常后 current() == null)
  - `L1-006`:`#runWithSnapshot_crossThreadRestore` — FR-006 + D-02(snapshot + 子线程 runWithSnapshot)
  - 加 1 个 Edge Case 测试:`#set_invalidTenantId_throwsIAE` — tenantId 包含 `:` 或空字符串抛 IllegalArgumentException
- [ ] T011 Validate L1 tests fail-then-pass pattern: 先跑 `mvn test -Dtest=TenantContextTest` 期望 **FAIL**(类未实现),实现 T005 后再跑期望 **PASS**

**Checkpoint**: TenantContext 基础机制 + 嵌套 + 异常 + 跨线程 全部 verified — 后续 4 维隔离可安全基于此构建。

---

## Phase 4: User Story 6 — TenantConfigProvider SPI + YamlTenantConfigProvider + AgentFactory 启动期校验(US6)

**Goal**: 实现 SPI + 默认实现 + 启动期 fail-fast 校验 + runTurn 入口 tenant 上下文校验(US6 S1-S3 + FR-008/009/010/011/016)

**Independent Test**: `TenantConfigProviderTest` 3 个 L1 + `TenantConfigValidationTest` 2 个 L1 全过

### Tests for User Story 6(L1 Unit)

- [ ] T012 [P] [US6] Write `TenantConfigProviderTest` in `lingshu-core/src/test/java/ai/lingshu/core/tenant/TenantConfigProviderTest.java`:
  - `L1-007`:`#resolve_existingTenant_returnsConfig` — FR-008 + I-2(resolve("alice") 返 Optional.of(alice-config))
  - `L1-008`:`#resolve_missingTenant_returnsEmpty` — I-3(resolve("ghost") 返 Optional.empty())
  - `L1-009`:`#listTenantIds_returnsAllConfigured` — listTenantIds() == ["alice", "bob"]
- [ ] T013 [P] [US6] Write `TenantConfigValidationTest` in `lingshu-core/src/test/java/ai/lingshu/core/tenant/TenantConfigValidationTest.java`:
  - `L1-018`:`#missingCostField_agentFactoryCreate_failsFast` — FR-010 + US6 S1(yml 缺 cost.sessionBudgetMicros → AgentFactory.create() 抛 LingsConfigException("C02") + 字段路径)
  - `L1-019`:`#tooManyTenants_agentFactoryCreate_failsFast` — FR-010(1001 tenants → 启动失败)
  - 加 1 个 Edge Case 测试:`#emptyTenants_singleTenantMode_succeeds` — NFR-009(yml 不配 tenants → 启动成功,tenants == null)

### Implementation for User Story 6

- [ ] T014 [US6] Create `TenantConfigProvider` SPI in `lingshu-core/src/main/java/ai/lingshu/core/tenant/TenantConfigProvider.java`:
  - `public interface TenantConfigProvider`(FR-008 + D-04 Provider 注入模式)
  - `@ContractVersionRef String CONTRACT_VERSION = "1.0.0";`(对齐 §5.3 SPI 标准)
  - `String name(); int priority(); Optional<TenantConfig> resolve(String tenantId); List<String> listTenantIds();`
  - 类 Javadoc:多 Provider 共存(§5.28 模式)+ 同 `name()` 冲突 `BeanDefinitionOverrideException` + `priority()` 高者胜出
- [ ] T015 [US6] Create `YamlTenantConfigProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/tenant/YamlTenantConfigProvider.java`:
  - `@Component("tenantConfigProvider_yaml") public class YamlTenantConfigProvider implements TenantConfigProvider`
  - 字段:`private final Map<String, TenantConfig> configs;`
  - 构造器:`@Autowired public YamlTenantConfigProvider(AgentConfig config) { this.configs = config.getTenants() != null ? config.getTenants().getMap() : Collections.emptyMap(); }`
  - `name()` → `"yaml"`,`priority()` → `10`
  - `resolve(tid)` → `Optional.ofNullable(configs.get(tid))`
  - `listTenantIds()` → `Collections.unmodifiableList(new ArrayList<>(configs.keySet()))`
- [ ] T016 [US6] Modify `AgentFactory.create` in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java`:
  - 在 `validate(config)` 之后,**新增** `validateTenants(config)` 静态方法调用(FR-010):
    - `if (config.getTenants() != null) config.getTenants().validate();`
    - 单租户模式(tenants == null)跳过,不影响 Story #001—#005(NFR-009)
  - 既有 `validate(config)` 7 项校验**不**改(Story #001—#005 测试不挂)
  - 加 import: `ai.lingshu.core.slot.exception.LingsConfigException` 或已有 exception type(查现有项目 exception 设计;若没有 LingsConfigException 则直接抛 `IllegalStateException` 带 "C02" 前缀,plan.md D-07 允许此退化)
- [ ] T017 [US6] Modify `LinearTurnEngine.runTurn` in `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`:
  - 在 `runTurn` 方法体**最开头**(在 cancellation 检查之后,ReAct 循环之前)新增(FR-011 + D-08):
    ```java
    if (ctx.config().getTenants() != null
        && ctx.config().getTenants().isEnabled()
        && TenantContext.current() == null) {
        throw new IllegalStateException(
            "C02 CONFIG_VALIDATION_FAILED: tenants configured but turn started without TenantContext.runAs — wrap call site in TenantContext.runAs(\"<tenantId>\", () -> ...)");
    }
    ```
  - 加 import: `ai.lingshu.core.tenant.TenantContext`
  - 既有 ReAct 循环结构**不**改
- [ ] T018 Validate tests pass: `mvn test -Dtest='TenantConfigProviderTest,TenantConfigValidationTest'` exits 0(5 cases green)

**Checkpoint**: SPI + 默认实现 + 启动期校验 + runTurn 入口校验 全部 verified — 4 维隔离可在 SPI 基础上构建。

---

## Phase 5: User Story 2 — Memory 路径按租户分流(US2)

**Goal**: per-tenant AgentConfig.sandbox.workingDirectory → ProjectClaudeMdSource 读各 tenant 自己的 CLAUDE.md(US2 S1-S3 + FR-003 + AC-05)

**Independent Test**: `MemoryPathIsolationTest` 2 个 L1 用例全过 — alice 读 alice CLAUDE.md,bob 读 bob CLAUDE.md,**互不可见**

### Tests for User Story 2(L1 Unit)

- [ ] T019 [P] [US2] Write `MemoryPathIsolationTest` in `lingshu-core/src/test/java/ai/lingshu/core/tenant/MemoryPathIsolationTest.java`:
  - `L1-010`:`#perTenantWorkingDirectory_differentClaudeMd` — US2 + FR-003 + AC-05:
    - 临时目录 `./target/test-tenant-isolation/alice/` + `./target/test-tenant-isolation/bob/`
    - alice dir 下写 `CLAUDE.md` 内容 `secret-alice`;bob dir 下写 `CLAUDE.md` 内容 `secret-bob`
    - 构造 2 个 `AgentConfig`(各 sandbox.workingDirectory 指向 alice/bob dir)
    - 实例化 2 个 `ProjectClaudeMdSource`(各持有 cfg)
    - 调 `source.load(ctx)` 各返 `secret-alice` / `secret-bob`
  - `L1-011`:`#aliceSource_doesNotSeeBobData` — US2 零交叉(alice source 永远读 alice dir,**不**读 bob dir)

### Implementation for User Story 2

- [ ] T020 [US2] 修改**说明**:本 Story **不**修改 `ProjectClaudeMdSource` 源码 — 当前实现已经读 `cfg.sandbox.workingDirectory/CLAUDE.md`(Story #002 已固化),per-tenant workingDirectory 已天然实现 file-level isolation
- [ ] T021 Validate L1 tests pass: `mvn test -Dtest=MemoryPathIsolationTest` exits 0(2 cases green)

**Checkpoint**: Memory path isolation verified — **0 行源码改动**(仅测试 + 设计意图文档)

---

## Phase 6: User Story 3 — Cost budget 按租户独立计数(US3)

**Goal**: per-tenant `CostTracker` 按 tenantId 分桶累加 + 超 budget 抛异常(US3 S1-S3 + FR-012 + AC-05)

**Independent Test**: `CostBudgetIsolationTest` 2 个 L1 用例全过

### Tests for User Story 3(L1 Unit)

- [ ] T022 [P] [US3] Write `CostBudgetIsolationTest` in `lingshu-core/src/test/java/ai/lingshu/core/tenant/CostBudgetIsolationTest.java`:
  - `L1-012`:`#aliceBudgetUsed_doesNotAffectBobBudget` — US3 S1:
    - 创建 `TenantAwareCostTracker(alice-budget=10_000_000, bob-budget=100_000_000)`
    - `TenantContext.runAs("alice", () -> tracker.accumulate(usage=5_000_000))`
    - `TenantContext.runAs("bob", () -> tracker.accumulate(usage=3_000_000))`
    - 断言 `tracker.usedFor("alice") == 5_000_000` + `tracker.usedFor("bob") == 3_000_000`
  - `L1-013`:`#aliceExceedsBudget_throwsException` — US3 S2:
    - alice 用到 5_000_000 后再 attempt 8_000_000(总 13 > 10)
    - 抛 `CostBudgetExceededException("alice", budget=10_000_000, used=5_000_000, attempted=8_000_000)`
    - bob 不受影响

### Implementation for User Story 3

- [ ] T023 [US3] Create `TenantAwareCostTracker` in `lingshu-core/src/main/java/ai/lingshu/core/impl/cost/TenantAwareCostTracker.java`(新 package):
  - `public final class TenantAwareCostTracker`(FR-012 + D-04 Provider 注入模式)
  - 字段:`private final ConcurrentHashMap<String, LongAdder> usedByTenant = new ConcurrentHashMap<>();` + `private final Map<String, Long> budgets;`
  - 构造器:`@Autowired public TenantAwareCostTracker(TenantConfigProvider provider) { this.budgets = ... extract budgets from provider.listTenantIds() ... }`
  - 方法:`public void accumulate(Usage usage)`:
    - `String tid = TenantContext.current(); if (tid == null) { /* fallback global */ return; }`
    - `long used = usedByTenant.computeIfAbsent(tid, k -> new LongAdder()).longValue() + usage.getTotalMicros();`
    - `Long budget = budgets.get(tid); if (budget != null && used > budget) throw new CostBudgetExceededException(tid, budget, used - usage.getTotalMicros(), usage.getTotalMicros());`
  - 方法:`public long usedFor(String tenantId) { LongAdder a = usedByTenant.get(tenantId); return a == null ? 0 : a.longValue(); }`
  - **注**:若现有项目无 CostBudgetExceededException,新建简单 exception class(JDK 内置 + Lombok 无依赖)
- [ ] T024 Validate L1 tests pass: `mvn test -Dtest=CostBudgetIsolationTest` exits 0(2 cases green)

**Checkpoint**: Cost budget isolation verified — alice/bob 独立计数 + 越界 fail-fast。

---

## Phase 7: User Story 4 — Sandbox whitelist 按租户独立(US4)

**Goal**: `RuntimeSandbox.process.run(cmd, ...)` 内部 snapshot tenant + 调 TenantConfigProvider 解析 whitelist(US4 S1-S3 + FR-002/013 + AC-05)

**Independent Test**: `SandboxWhitelistIsolationTest` 2 个 L1 用例全过

### Tests for User Story 4(L1 Unit)

- [ ] T025 [P] [US4] Write `SandboxWhitelistIsolationTest` in `lingshu-core/src/test/java/ai/lingshu/core/tenant/SandboxWhitelistIsolationTest.java`:
  - `L1-014`:`#aliceWhitelistRejectsGit_bobWhitelistAllowsGit` — US4 S1 + AC-05:
    - 配 yml `agent.tenants.{alice,bob}` 各有不同 commandWhitelist
    - alice 调 sandbox.process.run("git", args, cwd) 抛 `AccessDeniedException`(alice whitelist 无 git)
    - bob 调同一 `["git", "status"]` 成功(allow + process 启动)
  - `L1-016`:`#noTenantContext_fallsBackToGlobalWhitelist` — US4 S2:
    - TenantContext.current() == null
    - 调 sandbox.process.run 走 yml 全局 `agent.sandbox.commandWhitelist` + 日志 warn

### Implementation for User Story 4

- [ ] T026 [US4] Modify `RuntimeSandbox` 实现(找 Story #001 已有的 concrete class 如 `ChrootRuntimeSandbox` 或 stub;若只有接口无 impl,新建 `DefaultRuntimeSandbox` in `lingshu-core/src/main/java/ai/lingshu/core/impl/sandbox/DefaultRuntimeSandbox.java`):
  - `public class DefaultRuntimeSandbox implements RuntimeSandbox`
  - 字段:`private final TenantConfigProvider tenantConfigProvider; private final AgentConfig fallbackConfig;`
  - 构造器:`@Autowired public DefaultRuntimeSandbox(TenantConfigProvider p, AgentConfig c) { this.tenantConfigProvider = p; this.fallbackConfig = c; }`
  - `process()` 返回的 inner class 内部 `run(cmd, args, cwd)`:
    - `String tid = TenantContext.current();`
    - `List<String> whitelist = (tid != null) ? tenantConfigProvider.resolve(tid).map(c -> c.getSandbox().getCommandWhitelist()).orElse(fallbackConfig.getSandbox().getCommandWhitelist()) : fallbackConfig.getSandbox().getCommandWhitelist();`
    - `if (!whitelist.contains(cmd)) throw new AccessDeniedException("command '" + cmd + "' not in tenant '" + tid + "' whitelist " + whitelist);`
    - 后续 process.start()(FR-002 + FR-013 + D-04)
- [ ] T027 Validate L1 tests pass: `mvn test -Dtest=SandboxWhitelistIsolationTest` exits 0(2 cases green)

**Checkpoint**: Sandbox whitelist isolation verified — alice/bob 各生效 + 无 tenant 走全局。

---

## Phase 8: User Story 5 — Session key 按租户前缀(US5, P2)

**Goal**: `InMemorySessionStore.put/get` key 加 tenantId 前缀(US5 S1-S3 + FR-004 + AC-05)

**Independent Test**: `SessionKeyIsolationTest` 2 个 L1 用例全过

### Tests for User Story 5(L1 Unit)

- [ ] T028 [P] [US5] Write `SessionKeyIsolationTest` in `lingshu-core/src/test/java/ai/lingshu/core/tenant/SessionKeyIsolationTest.java`:
  - `L1-016`:`#aliceAndBobSameSessionId_dontCollide` — US5 S1 + AC-05:
    - `TenantContext.runAs("alice", () -> store.put("sess-1", stateA));`
    - `TenantContext.runAs("bob", () -> store.put("sess-1", stateB));`
    - `TenantContext.runAs("alice", () -> assertThat(store.get("sess-1")).isEqualTo(stateA));`
    - `TenantContext.runAs("bob", () -> assertThat(store.get("sess-1")).isEqualTo(stateB));`
  - `L1-017`:`#noTenantContext_sessionIdIsGlobal` — US5 S3:
    - TenantContext.current() == null 时 put/get 用 sessionId 本身

### Implementation for User Story 5

- [ ] T029 [US5] Modify `InMemorySessionStore`(若已存在,查 Story #005 之前的实现;若无,新建 `DefaultInMemorySessionStore` in `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultInMemorySessionStore.java`):
  - 实现 `SessionStore` 接口(FR-004 + D-05 冒号分隔):
    - `save(Checkpoint c)`: `String key = buildKey(c.getSessionId()); map.put(key, c);`
    - `Optional<Checkpoint> load(String sessionId)`: `return Optional.ofNullable(map.get(buildKey(sessionId)));`
  - 私有方法:`private String buildKey(String sessionId) { String tid = TenantContext.current(); return tid == null ? sessionId : tid + ":" + sessionId; }`(冒号分隔)
- [ ] T030 Validate L1 tests pass: `mvn test -Dtest=SessionKeyIsolationTest` exits 0(2 cases green)

**Checkpoint**: Session key isolation verified — alice/bob 同 sessionId 不撞号。

---

## Phase 9: AC-05 黑盒 E2E 集成(TenantIsolationIT)

**Purpose**: 整合 6 个 US + 4 维隔离 + AC-05 完整黑盒验证

- [ ] T031 [P] Write `TenantIsolationIT` in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/TenantIsolationIT.java`:
  - `@SpringBootTest` + `@ActiveProfiles("test-multitenant")` + `application-test-multitenant.yml` 配 2 tenants
  - `E2E-001`:`#aliceAndBobIsolated_across4Dims` — **AC-05 黑盒主路径**(SC-002):
    1. **Setup**:Spring 启动 + yml 配 `agent.tenants.{alice,bob}` 各有独立 memory dir / cost budget / sandbox whitelist
    2. **Action**:
       - `TenantContext.runAs("alice", () -> { /* turn 1: 写 alice memory + 消耗 alice cost + alice sandbox 跑 git 拒 */ })`
       - `TenantContext.runAs("bob", () -> { /* turn 2: 写 bob memory + 消耗 bob cost + bob sandbox 跑 git 成功 */ })`
    3. **Assertions**:
       - alice dir 包含 alice-secret,**不**包含 bob-secret
       - bob dir 包含 bob-secret,**不**包含 alice-secret
       - alice cost counter == alice 用量,bob cost counter == bob 用量(互不影响)
       - alice sandbox `git` 抛 `AccessDeniedException`(alice whitelist 无 git)
       - bob sandbox `git` 成功
       - alice session key == `alice:sess-123`,bob session key == `bob:sess-123`
- [ ] T032 Validate L5 E2E passes: `mvn test -Dtest=TenantIsolationIT` exits 0(E2E-001 green + wall-clock < 5s)

**Checkpoint**: AC-05 black-box verified — Story #006 acceptance criterion satisfied。

---

## Phase 10: Full Test Suite + R-13 Self-Check + 回归测试

**Purpose**: 全部 19 + 1 = 20 测试用例 green + 无回归 + 0 新增依赖

- [ ] T033 Run full test suite: `mvn -pl lingshu-core test` exits 0(Story #001—#005 19 用例 + Story #006 20 用例 = 39 全 green)
- [ ] T034 Run R-13 dep-tree check: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-006-after.txt`
  - `diff /tmp/deps-005-baseline.txt /tmp/deps-006-after.txt` → **0 new dependencies**
  - If diff shows new transitive deps → STOP + investigate + RFC + remove
- [ ] T035 Capture AC-05 verification output: `mvn test -Dtest=TenantIsolationIT -Dsurefire.useFile=false` — copy paste assertion results into PR body
- [ ] T036 Capture R-13 dep-tree diff: paste diff (or "no diff") into PR body `### R-13 dependency:tree 自查` section
- [ ] T037 Commit changes: `git add ... && git commit -m "feat(agent): Story #006 multi-tenant — TenantContext ThreadLocal + 配置/Session/Sandbox/Cost 四维隔离 (AC-05)"`

**Checkpoint**: Story #006 implementation complete — ready for PR.

---

## Phase 11: PR + Merge + 文档同步

- [ ] T038 Push branch: `git push origin story-006-multi-tenant`
- [ ] T039 Open PR with body template:
  - Summary(3 bullets: 4 维隔离 + TenantContext + SPI)
  - AC-05 black-box output(pasted from T035)
  - Test plan checklist(all 20 cases green)
  - **R-13 dependency:tree 自查** section(paste diff from T036)
  - Critical invariants(none violated)
  - "Story boundary: 6 new + 4-8 modified = 10-14 files" note justifying the slight exceedance
- [ ] T040 After PR review + merge: sync README.md(lingshu-cli 示例加 multi-tenant demo)/ docs / dsh changelog §13(加 v1.5.x 条目)/ constitution §10(R-02 缓解状态 — 标记 "已落地:Story #006 try-finally + snapshot+runWithSnapshot + 主动放弃 InheritableThreadLocal")

---

## Test Count Summary

| Layer | Count | Files |
|---|---|---|
| L1 Unit(TenantContext)| 7 | `TenantContextTest.java`(含 Edge Case)|
| L1 Unit(TenantConfigProvider)| 3 | `TenantConfigProviderTest.java` |
| L1 Unit(Memory path isolation)| 2 | `MemoryPathIsolationTest.java` |
| L1 Unit(Cost budget isolation)| 2 | `CostBudgetIsolationTest.java` |
| L1 Unit(Sandbox whitelist isolation)| 2 | `SandboxWhitelistIsolationTest.java` |
| L1 Unit(Session key isolation)| 2 | `SessionKeyIsolationTest.java` |
| L1 Unit(TenantConfig validation)| 3 | `TenantConfigValidationTest.java`(含 Edge Case)|
| L5 E2E(AC-05 black-box)| 1 | `TenantIsolationIT.java` |
| **Total** | **22** | **8 test classes** |

注:Edge Case 加测覆盖 tenantId 格式校验 + 单租户 fallback,**+2 用例**(22 vs plan.md 估算的 20)。

---

## Definition of Done(Story #006 complete)

- [x] spec.md / plan.md / tasks.md / quickstart.md / contracts / checklists 6 件套齐全(本目录)
- [ ] 6 new + 4-8 modified = **10-14 文件改动**已提交(轻度超 CLAUDE.md §11 #4 ≤ 5 软上限,plan.md 已说明)
- [ ] `mvn -pl lingshu-core test` 全绿(22 测试用例)
- [ ] **AC-05 黑盒验证通过**(`TenantIsolationIT#aliceAndBobIsolated_across4Dims` 4 维隔离全验证,贴输出)
- [ ] `mvn dependency:tree` 自查:0 新依赖
- [ ] 关键不变项全部保留(CancellationToken / TurnContext / MemorySource / SessionStore 接口签名 / AgentConfig 27+ 字段 / 5-step pipeline)
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] Branch `story-006-multi-tenant` pushed + PR opened

---

## Anti-Patterns to Avoid(CLAUDE.md §11 + §12)

- ❌ 直接修改 `SessionStore` / `MemorySource` / `RuntimeSandbox` 接口签名(破坏 Story #014 / Story #002 集成)
- ❌ 用 `InheritableThreadLocal` 跨线程传递 tenant(违反 dsh §17 R-02 mitigation (c) + FR-015)
- ❌ 把 `TenantContext` 改成 instance 方法(必须是 static utility — 无状态)
- ❌ 引 `Reactor` / `RxJava` / `Guava` / `TransmittableThreadLocal` 等额外依赖(constitution §2 R-13)
- ❌ 用 `var` / `List.of` / sealed interface / record(JDK 8 约束 CLAUDE.md §3)
- ❌ 把 yml 配 tenants 时的"漏 runAs"用 WARN 日志兜底 — 必须 throw(FR-011 启动期 fail-fast)
- ❌ `TenantContext.runAs` 不 try-finally 包裹回调(R-02 (a) 显式约束)
- ❌ 把 Session 二级分组(按 user_id 而非 tenantId)拉进 Story #006(Scope creep,留 Story #014)
- ❌ 把 HTTP WebFilter X-Tenant-Id header 入口拉进 Story #006(lingshu 仓暂无 HTTP 模块,留 Story 后续)
- ❌ 把 `${tenant}` 占位符路径模板拉进 Story #006(per-tenant workingDirectory 已天然实现,占位符降级 future scope)
- ❌ 新增 ErrorCode(0 新增 — NFR-004 显式约束,复用 LINGS-C02/C03/S01/Z01)
- ❌ `TenantsConfig.validate()` 漏校验 key == value.tenantId 一致性(Edge Case "key/value tenantId mismatch")
- ❌ `TenantContext` 跨线程直接 `current()` 返值不报错(必须显式 `runWithSnapshot` — 安全降级)
