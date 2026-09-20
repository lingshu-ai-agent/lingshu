# Tasks: Story #003 spi-slot-router

**Input**: Design documents from `/specs/003-spi-slot-router/`
- spec.md (3 User Stories: US1 P1 / US2 P1 / US3 P2)
- plan.md (15-step implementation order + 4 new files + 28 modified files)
- research.md (10 decisions D-01—D-10)
- data-model.md (7 entities)
- contracts/slot-version-compat.md (SPI contract)
- contracts/wiring-diagram.md (AgentFactory wiring)
- quickstart.md (10 validation steps)

**Prerequisites**: spec.md + plan.md (required);research.md + data-model.md + contracts/ + quickstart.md (all completed)

**Tests**: Required per spec FR-001—FR-010 (AC-02 + AC-08 black-box). 4 new test classes + 1 modified existing.

**Constitution**: v1.0 — §1 #1 JDK 8 / §2 13 依赖锁定(R-13 零新增)/ §4 LINGS-S05 新增 / §10 R-04 缓解 + R-13 mitigation (d)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Include exact file paths in descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Verify environment + capture Story #002 baseline for R-13 diff

- [ ] T001 Verify JDK 17+ + Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #002 dependency baseline: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-002-baseline.txt`
- [ ] T003 Confirm feature branch `story-003-spi-slot-router` checked out (`git branch --show-current`)

**Checkpoint**: Setup ready — foundational SPI types can be implemented.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 3 new SPI support types — MUST complete before ANY user story implementation. All blocks US1/US2/US3.

- [ ] T004 Create `Version` utility class with static methods `parse(String) → int[3]`, `isCompatible(String, String) → boolean`, `format(int[3]) → String` in `lingshu-core/src/main/java/ai/lingshu/core/spi/Version.java` (per data-model.md Entity 1 + contracts/slot-version-compat.md §4)
- [ ] T005 Create `ProviderInitException` extending `IllegalStateException` with `errorCode="LINGS-S05"` + `hint` field + cause chain ≥ 2 in `lingshu-core/src/main/java/ai/lingshu/core/spi/ProviderInitException.java` (per data-model.md Entity 2)
- [ ] T006 [P] Create `ContractVersionRef` annotation `@Retention(RUNTIME) @Target(FIELD)` in `lingshu-core/src/main/java/ai/lingshu/core/spi/ContractVersionRef.java` (per data-model.md Entity 3)
- [ ] T007 Validate compile: `mvn -pl lingshu-core compile` exit 0 (3 new files compile standalone)

**Checkpoint**: Foundation ready — user story implementation can now begin.

---

## Phase 3: User Story 1 + User Story 2 (P1) — Provider.version() + SlotRouter 兼容性校验 + 启动期版本自描述 + fail-fast

**Goal**: Modify `SlotProvider` (+version()) + 13 Slot interfaces (+CONTRACT_VERSION) + `SlotRouter` (构造期校验 + describe() + resolve() 二次校验) + 9 default Providers (+version()="1.0.0" + 真实 create() body) + `AgentFactory` (+description()).

**Independent Test**: `SlotRouterCompatTest` 6 scenarios (AC-02/08 黑盒) + `AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion` (9 行启动日志)

> **NOTE**: US1 + US2 share implementation code (both require SlotRouter validation + version() field). Per plan.md implementation order step 2-6, these MUST be implemented together — separate implementation would create compile errors.

### Tests for US1 + US2 (Tests required per spec FR-003 + AC-02/08)

> **NOTE: Write these tests FIRST, ensure they FAIL before implementation**

- [ ] T008 [P] [US1] Write `VersionTest` (13 scenarios: parse 3 正常 + 7 异常 + isCompatible 6 组合 + format 3) in `lingshu-core/src/test/java/ai/lingshu/core/spi/VersionTest.java`
- [ ] T009 [P] [US2] Write `SlotRouterCompatTest` (6 scenarios: exact match / minor lag / minor ahead / major mismatch / null / invalid semver) in `lingshu-core/src/test/java/ai/lingshu/core/spi/SlotRouterCompatTest.java`
- [ ] T010 [P] [US2] Write `ProviderInitExceptionTest` (4 scenarios: errorCode 字段 / cause chain ≥ 2 / hint 非空 / stderr ERROR log via logback ListAppender) in `lingshu-core/src/test/java/ai/lingshu/core/spi/ProviderInitExceptionTest.java`
- [ ] T011 Validate tests fail: `mvn test -Dtest=VersionTest,SlotRouterCompatTest,ProviderInitExceptionTest` exits with compile errors or test failures (pre-implementation)

### Implementation for US1 + US2

- [ ] T012 [US1] Add `String version()` abstract method + Javadoc to `SlotProvider` interface in `lingshu-core/src/main/java/ai/lingshu/core/spi/SlotProvider.java` (per FR-001 + contracts/slot-version-compat.md §2)
- [ ] T013 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `LlmProvider` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/LlmProvider.java`
- [ ] T014 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `ToolExecutor` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolExecutor.java`
- [ ] T015 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `PermissionPolicy` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/PermissionPolicy.java`
- [ ] T016 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `SessionStore` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/SessionStore.java`
- [ ] T017 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `Compactor` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/Compactor.java`
- [ ] T018 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `PromptBuilder` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/PromptBuilder.java`
- [ ] T019 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `MemorySource` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/MemorySource.java`
- [ ] T020 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `FlowEngine` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/FlowEngine.java`
- [ ] T021 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `A2aTransport` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/A2aTransport.java`
- [ ] T022 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `Tool` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/Tool.java`
- [ ] T023 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `RuntimeSandbox` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/RuntimeSandbox.java`
- [ ] T024 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `Skill` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/Skill.java`
- [ ] T025 [P] [US1] Add `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"` to `SkillSource` interface in `lingshu-core/src/main/java/ai/lingshu/core/slot/SkillSource.java`
- [ ] T026 [US2] Modify `SlotRouter` abstract class: add `slotContractVersion` field + `readContractVersion()` (反射 `T.class.getField("CONTRACT_VERSION")`) + `validateProviderVersions(List<P>)` (调 `Version.parse` + `Version.isCompatible`,失败抛 `ProviderInitException`) + 改造 constructor log 格式为 `[contract v{}]` + 新增 `resolve()` 二次校验 + 新增 `public List<String> describe()` 方法 in `lingshu-core/src/main/java/ai/lingshu/core/spi/SlotRouter.java` (per data-model.md Entity 5 + contracts/slot-version-compat.md §3)
- [ ] T027 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `AnthropicLlmProviderProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProviderProvider.java` (create() 已实装)
- [ ] T028 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `DefaultToolExecutorProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolExecutorProvider.java` (create() body 升级 — 如为 stub 改真实 `return new DefaultToolExecutor()`)
- [ ] T029 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `AllowAllPermissionPolicyProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/permission/AllowAllPermissionPolicyProvider.java` (create() body 升级)
- [ ] T030 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `LinearTurnEngineProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngineProvider.java` (create() body 升级)
- [ ] T031 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `DefaultPromptBuilderProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderProvider.java` (create() 不变 — 已实装 in #002)
- [ ] T032 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `ProjectClaudeMdSourceProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectClaudeMdSourceProvider.java` (create() 不变)
- [ ] T033 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `UserClaudeMdSourceProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/UserClaudeMdSourceProvider.java` (create() 不变)
- [ ] T034 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `IdentityMemorySourceProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/IdentityMemorySourceProvider.java` (create() 不变)
- [ ] T035 [P] [US1] Add `@Override public String version() { return "1.0.0"; }` to `ProjectTreeMemorySourceProvider` in `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectTreeMemorySourceProvider.java` (create() 不变)
- [ ] T036 Validate compile: `mvn -pl lingshu-core compile` exit 0 (13 Slot 接口 + 9 Provider + SlotRouter 全部对齐)

**Checkpoint**: US1 + US2 implementation complete — tests should now pass.

- [ ] T037 [US2] Validate tests pass: `mvn test -Dtest=VersionTest,SlotRouterCompatTest,ProviderInitExceptionTest` all green

---

## Phase 4: User Story 3 (P2) — `AgentFactory.description()` Self-Describe

**Goal**: Add `AgentFactory.description()` synchronous read-only method aggregating 9 Router outputs + header + footer.

**Independent Test**: `AgentFactoryDescriptionTest` 5 tests verifying description() output format (9 Slot lines + header + footer + 字段顺序 + 不可变).

### Tests for US3

- [ ] T038 [P] [US3] Write `AgentFactoryDescriptionTest` (5 tests: 含 9 行 / 头部 `AgentFactory v<X> for JVM <v>` / 末尾 `Turn=0 Session=<id>` / 字段顺序固定 / 返回值不可变) in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryDescriptionTest.java`
- [ ] T039 [P] [US1] Add `startup_listsAllProvidersWithVersion` method to existing `AgentFactoryIntegrationTest` (capture logback ListAppender, assert 9 lines `✓ [<SlotName>] <name> v1.0.0 (priority=<n>)`) — 创建新 test class `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryIntegrationTest.java` (currently does not exist; see Plan §7.2)
- [ ] T040 Validate tests fail: `mvn test -Dtest=AgentFactoryDescriptionTest,AgentFactoryIntegrationTest` exits with compile error (pre-implementation)

### Implementation for US3

- [ ] T041 [US3] Add `public String description()` method to `AgentFactory` (aggregates 6 Router describe() + header `AgentFactory v0.1.0-SNAPSHOT for JVM <version>` + footer `Turn=0 Session=<sessionId>`) in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` (per data-model.md §4.8 + contracts/slot-version-compat.md §3.4)
- [ ] T042 Validate tests pass: `mvn test -Dtest=AgentFactoryDescriptionTest,AgentFactoryIntegrationTest` all green

**Checkpoint**: US3 implementation complete — description() 输出符合契约格式。

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: End-to-end validation + R-13 mitigation (d) 自查 + 文档同步 + Git PR

- [ ] T043 [P] Run full test suite: `mvn -pl lingshu-core test` exit 0 (verifies no regression in Story #001 / #002 tests)
- [ ] T044 [P] R-13 dep:tree 自查: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-003.txt` + `diff /tmp/deps-002-baseline.txt /tmp/deps-003.txt` 期望空输出
- [ ] T045 Manual FAIL-FAST demo (quickstart.md Validation 8): 临时加 `BadVersionLlmProviderProvider.java` (version="2.0.0") → 跑 demo-empty → 验证 stderr ERROR + LINGS-S05 + JVM exit 1 → 删除临时文件
- [ ] T046 [P] Update `constitution.md` §4 错误码表 加 LINGS-S05 行 + §10 风险登记 更新 R-04 缓解状态 (本 Story 是 R-04 缓解前置)
- [ ] T047 [P] Update `README.md` 替换 Story #003+ Fibonacci 占位为实际说明 (1-2 段)
- [ ] T048 [P] Update `dsh_agent_design.md` §13 changelog 加 Story #003 完成条目 (PR 合入后)
- [ ] T049 Git commit: `feat(agent): Story #003 spi-slot-router — Provider.version() + SlotRouter 兼容性校验 + 9 默认 Provider 升级` (per CLAUDE.md §9 提交约定)
- [ ] T050 Push branch + create PR with body: spec.md + plan.md + tasks.md + AC-02/08 验证输出 + R-13 dep:tree 自查节 + 6 场景测试输出 (per CLAUDE.md §9 + quickstart.md Validation 10)

**Checkpoint**: Story #003 is COMPLETE — all 10 validations in quickstart.md pass + PR ready for review.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup — **BLOCKS** all user stories
- **US1+US2 (Phase 3)**: Depends on Foundational completion
- **US3 (Phase 4)**: Depends on Phase 3 completion (AgentFactory.description() needs Routers to work)
- **Polish (Phase 5)**: Depends on all user stories complete

### Within Each Phase

- **Phase 2**: T004 must precede T005/T006 (Version utility defines parse errors that ProviderInitException wraps). T005/T006 can run in parallel after T004.
- **Phase 3**: T012 (SlotProvider.version()) MUST precede T013-T025 (13 Slot interfaces). T013-T025 (13 Slot interfaces) MUST precede T026 (SlotRouter — uses reflection). T026 must precede T027-T035 (9 Provider version()) to ensure compile. T027-T035 can run in parallel.
- **Phase 4**: T038-T040 (tests) MUST precede T041-T042 (impl).
- **Phase 5**: T043/T044/T045 (验证) MUST precede T046-T050 (sync + PR).

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2. No dependency on US2/US3.
- **US2 (P1)**: Shares code with US1 (same SlotRouter validation). **NOT independently implementable** — combined into Phase 3.
- **US3 (P2)**: Depends on Phase 3 (US3's `description()` calls Router.describe() which is added in US1+US2).

### Parallel Opportunities

- Phase 2: T005/T006 (in parallel after T004)
- Phase 3: T013-T025 (13 Slot interfaces — in parallel); T027-T035 (9 Provider version() — in parallel)
- Phase 5: T043/T044/T046/T047/T048 (in parallel after T045)

---

## Parallel Example: User Story 1 + 2 (Phase 3)

```bash
# Launch all 13 Slot interface updates in parallel (after T012):
Task: "T013 [P] [US1] Add CONTRACT_VERSION to LlmProvider.java"
Task: "T014 [P] [US1] Add CONTRACT_VERSION to ToolExecutor.java"
Task: "T015 [P] [US1] Add CONTRACT_VERSION to PermissionPolicy.java"
# ... (13 total)

# Launch all 9 Provider version() implementations in parallel (after T026):
Task: "T027 [P] [US1] Add version() to AnthropicLlmProviderProvider"
Task: "T028 [P] [US1] Add version() to DefaultToolExecutorProvider"
# ... (9 total)
```

---

## Implementation Strategy

### MVP First (User Story 1 + 2 Only)

1. Complete Phase 1: Setup (T001-T003)
2. Complete Phase 2: Foundational (T004-T007) — **CRITICAL blocks all stories**
3. Complete Phase 3: US1+US2 (T008-T037) — both P1 stories land together
4. **STOP and VALIDATE**: Run `mvn -pl lingshu-core test` + check 9 default Providers log with version
5. Demo if ready: Story #003 P1 scope is functional

### Incremental Delivery

1. Setup + Foundational → Foundation ready (3 new types compile)
2. Phase 3 (US1+US2 P1) → version() field + SlotRouter 校验 + 启动日志全工作
3. Phase 4 (US3 P2) → description() 自描述输出
4. Phase 5 → 验证 + 文档 + PR

### Suggested Commit Boundaries

- **Commit 1**: Phase 2 (3 new types) — `feat(agent): Story #003 — Version + ProviderInitException + ContractVersionRef`
- **Commit 2**: Phase 3 partial (SlotProvider.version() + 13 Slot interfaces) — `feat(agent): Story #003 — SlotProvider.version() + 13 Slot CONTRACT_VERSION`
- **Commit 3**: Phase 3 partial (SlotRouter 校验 + 9 Provider version()) — `feat(agent): Story #003 — SlotRouter 校验 + 9 默认 Provider 升级`
- **Commit 4**: Phase 4 (AgentFactory.description() + tests) — `feat(agent): Story #003 — AgentFactory.description() 自描述`
- **Commit 5**: Phase 5 polish + PR

---

## Notes

- **Phase 3 US1+US2 合并原因**:SlotRouter 修改 (T026) 同时承载 US1 的 `version()` 字段读取 + US2 的兼容性校验逻辑;拆开会导致中间编译态不可用
- **T013-T025 13 个 Slot 接口** 是机械复制 — 13 个文件各加 5 行 = `@ContractVersionRef String CONTRACT_VERSION = "1.0.0";` + import
- **T027-T035 9 个 Provider version()** 是机械复制 — 每个文件加 1 行 `@Override public String version() { return "1.0.0"; }`
- **R-13 自查节** 是 PR body 必含节(对应 dsh §17 R-13 mitigation (d) hard constraint)
- **Story #003 不实现 A2A 协议协商**(留给 Story #009) — Provider.version() 字段是 single-source-of-truth,Story #009 消费
- **本 Story 故意超出 §11 #4 "≤ 5 文件改动"约束**(32 文件) — 因 13 Slot 接口 + 9 Provider stub 是 Story #001 + #002 已定模板的延续,详见 plan.md Constitution Check 备注,**不**触发 RFC

---

## Done When

- [ ] All T001—T050 boxes checked
- [ ] `mvn -pl lingshu-core test` exit 0
- [ ] `mvn -pl lingshu-core dependency:tree` diff with #002 baseline = 0
- [ ] quickstart.md 10 validations 全过
- [ ] PR body 含 spec.md + plan.md + tasks.md + AC-02/08 验证输出 + R-13 自查节
- [ ] PR merged to `main`

**Total: 50 tasks across 5 phases**
- Phase 1 (Setup): 3 tasks
- Phase 2 (Foundational): 4 tasks
- Phase 3 (US1+US2 P1): 30 tasks
- Phase 4 (US3 P2): 5 tasks
- Phase 5 (Polish): 8 tasks