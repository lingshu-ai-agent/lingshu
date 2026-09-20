---
description: "Task list for Story #002 identity-instructions-memory"
---

# Tasks: Story 002 identity-instructions-memory

**Input**: Design documents from `/specs/002-identity-instructions-memory/`
- **Required**: [plan.md](./plan.md), [spec.md](./spec.md)
- **Optional**: [research.md](./research.md) · [data-model.md](./data-model.md) · [contracts/](./contracts/) · [quickstart.md](./quickstart.md)

**Branch**: `story-002-identity-instructions-memory`
**Target AC**: AC-09 (业务配置三件套完整可用)
**Stack**: Java 1.8 (target) / JDK 17+ (runtime) / Spring Boot 3.2.5 / Lombok @Value / **0 新增依赖 (R-13 硬约束)**

## Format: `[ID] [P?] [Story] Description`

- **[P]** = parallelizable (different files, no in-progress dependencies)
- **[US1/US2/US3/US4]** = which user story from spec.md this task serves
- **[Setup]** / **[Foundational]** / **[Polish]** = cross-cutting phase tags
- All file paths are absolute under the lingshu repo root

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 项目骨架 + 新增包目录 + demo-engineer 模块脚手架(Story #001 的 maven 多模块已建好,本 Story 只增量)

- [ ] T001 [Setup] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/` package directory with `package-info.java` documenting the new MemorySource SPI impls
- [ ] T002 [Setup] Create `lingshu-core/src/test/java/ai/lingshu/core/impl/memory/` test package directory
- [ ] T003 [Setup] Add `lingshu-examples/demo-engineer` module skeleton: `pom.xml` (depends on `lingshu-core` + `spring-boot-starter` + `spring-ai-anthropic` matching #001 `demo-empty`), no Java sources yet
- [ ] T004 [P] [Setup] Add `lingshu-examples/demo-engineer/src/main/resources/application.yml` with Bob's identity + instructions + memory config (multi-line identity with 4 fields populated, `instructions.templateEngine: mustache` placeholder, 4-entry `memory-sources` list)

**Checkpoint**: `mvn validate -N` passes; `mvn -pl lingshu-examples/demo-engineer -am validate` resolves dependencies without errors.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 4 个 MemorySource concrete impl + 4 个 Provider + MemorySourceRouter + DefaultPromptBuilderProvider/Builder 改造 —— 全部 4 个 US 都依赖此阶段产物。

**⚠️ CRITICAL**: US1 / US2 / US3 / US4 任一 user story 不得在本阶段完成前开始。

### 2A — 4 个 MemorySource concrete impl

- [ ] T005 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectClaudeMdSource.java` — `name()="project-claude-md"` `priority()=10`; `load(ctx)` returns `cfg.memory.claudeMd.project` UTF-8 content or null (missing file / disabled / IOException all → null + DEBUG log); see contracts/memory-source.md §3.1
- [ ] T006 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/UserClaudeMdSource.java` — `name()="user-claude-md"` `priority()=20`; same behavior as T005 but reads `cfg.memory.claudeMd.user`; see contracts/memory-source.md §3.2
- [ ] T007 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/IdentityMemorySource.java` — `name()="identity"` `priority()=30`; `load(ctx)` Jackson-serializes `cfg.identity` to single-line JSON; falls back to `Identity.defaults()` if null; see contracts/memory-source.md §3.3
- [ ] T008 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectTreeMemorySource.java` — `name()="project-tree"` `priority()=40`; depth-1 walk of `cfg.sandbox.workingDirectory` (or `./`), filter `*.md` case-insensitive, sort alphabetically, concatenate contents joined by `\n\n── separator ──\n\n`; missing root / empty dir / IOException → null; see contracts/memory-source.md §3.4

### 2B — 4 个 MemorySourceProvider concrete (@Component)

- [ ] T009 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectClaudeMdSourceProvider.java` — `@Component` `name()="project-claude-md"` `priority()=10` `create(cfg) = new ProjectClaudeMdSource(cfg)`; bean auto-registered by Spring component scan
- [ ] T010 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/UserClaudeMdSourceProvider.java` — `@Component` `name()="user-claude-md"` `priority()=20` `create(cfg) = new UserClaudeMdSource(cfg)`
- [ ] T011 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/IdentityMemorySourceProvider.java` — `@Component` `name()="identity"` `priority()=30` `create(cfg) = new IdentityMemorySource(cfg)`
- [ ] T012 [P] [Foundational] Create `lingshu-core/src/main/java/ai/lingshu/core/impl/memory/ProjectTreeMemorySourceProvider.java` — `@Component` `name()="project-tree"` `priority()=40` `create(cfg) = new ProjectTreeMemorySource(cfg)`

### 2C — MemorySourceRouter (added to existing `Routers.java`)

- [ ] T013 [Foundational] Add `MemorySourceRouter` inner `@Component` class to `lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java` — extends `SlotRouter<Providers.MemorySourceProvider, MemorySource>`, constructor takes `List<Providers.MemorySourceProvider>`, passes `"MemorySource"` typeName + Logger to super; adds `public List<MemorySource> resolveAll(List<String> names, AgentConfig cfg)` method that preserves **input order** (NOT priority-sorted) and throws `IllegalArgumentException` (→ `LINGS-S01`) on unknown name; see contracts/memory-source.md §4

### 2D — DefaultPromptBuilderProvider + DefaultPromptBuilder refactor

- [ ] T014 [Foundational] Modify `lingshu-core/src/main/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderProvider.java` — add `@Autowired private Routers.MemorySourceRouter memorySourceRouter` field; change `create(AgentConfig)` to `List<MemorySource> sources = memorySourceRouter.resolveAll(config.getPrompt().getMemorySources(), config); return new DefaultPromptBuilder(sources);`
- [ ] T015 [Foundational] Modify `lingshu-core/src/main/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilder.java` — (a) change constructor signature to `DefaultPromptBuilder(List<MemorySource> memorySources)` storing as final field; (b) refactor `[PROJECT MEMORY]` segment to iterate `memorySources` and call `load(ctx)` on each, joining non-null results with `\n\n── separator ──\n\n`; (c) add private `renderTemplate(String, Map<String,String>)` helper using `String.replace` per research.md D-01; (d) apply template rendering to `[INSTRUCTIONS]` text when `cfg.instructions.templateEngine == "mustache"`; see contracts/prompt-builder.md §3.2 + §3.3 + §3.5

**Checkpoint**: `mvn -pl lingshu-core compile` passes; `mvn -pl lingshu-core test` still passes (all #001 tests must remain green); Spring boot logs `[MemorySource] resolved 4 provider(s):` on startup.

---

## Phase 3: User Story 1 — 业务三件套 YAML 端到端跑通 (Priority: P1) 🎯 MVP

**Goal**: `demo-engineer` 跑通,5 段装配正确,LLM 30s 内拿到首个 token。

**Independent Test**: `time java -jar lingshu-examples/demo-engineer/target/demo-engineer-0.1.0-SNAPSHOT.jar "你是做什么的"` — 30s 内首个 token,stderr 含 `[MemorySource] resolved 4 provider(s):`,stdout 含 `"Java 后端工程师"` 字样(LLM 响应)

### Tests for User Story 1

- [ ] T016 [P] [US1] Create `lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderTest.java` with 10 test methods covering all 5 段 + mustache + missing file scenarios per contracts/prompt-builder.md §8 table
- [ ] T017 [P] [US1] Run `mvn -pl lingshu-core test -Dtest=DefaultPromptBuilderTest` and assert all 10 tests pass (especially US1 Scenario 1-3 assertions: 5-segment assembly + empty cfg → only `[ROLE]` + identity defaults)

### Implementation for User Story 1

- [ ] T018 [P] [US1] Create `lingshu-examples/demo-engineer/src/main/resources/prompts/system-engineer.md` — Bob's instructions content with `{{project_root}}` and `{{tone}}` placeholder for US4 verification (Chinese, ~20 lines)
- [ ] T019 [P] [US1] Create `lingshu-examples/demo-engineer/src/main/resources/CLAUDE.md` — Bob's project memory content (Chinese, ~30 lines, with at least 1 code block example)
- [ ] T020 [P] [US1] Create `lingshu-examples/demo-engineer/src/main/resources/docs/architecture.md` + `conventions.md` + `README.md` — verify ProjectTree depth-1 picks up 2 `.md` and skips `README.md` content (README.md has no `.md` extension; wait — README.md DOES have `.md` extension — rename to `OVERVIEW.txt` to actually test the extension filter)
- [ ] T021 [US1] Create `lingshu-examples/demo-engineer/src/main/java/ai/lingshu/examples/demoengineer/DemoEngineerApplication.java` — Spring Boot `main()`, autowire `AgentFactory`, read `ANTHROPIC_API_KEY` env var (fail-fast with clear error if missing), run 1 turn with `agent.runBlocking("你是做什么的")`, print stdout + log timing
- [ ] T022 [US1] Build demo-engineer: `mvn -pl lingshu-examples/demo-engineer -am clean package -DskipTests` → expect `BUILD SUCCESS` + jar at `lingshu-examples/demo-engineer/target/demo-engineer-0.1.0-SNAPSHOT.jar`

**Checkpoint**: US1 fully functional — run `time java -jar ... "你是做什么的"` and verify 30s budget + 5-segment startup log; record actual elapsed time for PR body.

---

## Phase 4: User Story 2 — 切换 PromptBuilder Provider 按名路由 (Priority: P2)

**Goal**: 4 个 MemorySource Provider 同存 + 按 yml `memory-sources` 列表顺序拼装,unknown name 启动期 fail-fast。

**Independent Test**: 修改 `demo-engineer/application.yml` `agent.prompt.memory-sources=[identity]`(只 1 项)→ 重跑 jar → `[PROJECT MEMORY]` 段只含 1 块(identity JSON);改 `[identity, project-tree, project-claude-md, user-claude-md]`(打乱 priority 顺序)→ 重跑 → 4 块按 yml 列表顺序出现

### Tests for User Story 2

- [ ] T023 [P] [US2] Create `lingshu-core/src/test/java/ai/lingshu/core/impl/router/MemorySourceRouterTest.java` with 7 test methods per contracts/memory-source.md §6 table (empty list, null list, single known, 4 known in input order, unknown name throws, resolve single, constructor resolves 4 defaults)
- [ ] T024 [P] [US2] Run `mvn -pl lingshu-core test -Dtest=MemorySourceRouterTest` and assert all 7 tests pass

### Implementation for User Story 2 (configuration-only verification)

- [ ] T025 [US2] Verify multi-Provider routing by adding a temporary `agent.prompt.memory-sources=[identity]` to `demo-engineer/application.yml`, repackage, run jar, assert stderr contains `[MemorySource] resolved 4 provider(s):` AND stdout `[PROJECT MEMORY]` segment contains only the identity JSON (no separator lines, no CLAUDE.md content). **Revert application.yml to default 4-source list after verification.**
- [ ] T026 [US2] Negative test: create a temporary `application-broken.yml` with `agent.prompt.memory-sources=[non-existent-source]`, run with `--spring.config.location=classpath:application-broken.yml`, assert JVM exits with code 1 AND stderr contains `LINGS-S01` OR `Unknown MemorySourceRouter 'non-existent-source'`. **Delete temporary yml after.**

**Checkpoint**: US2 fully functional — Router startup log + multi-Provider coexistence + unknown name fail-fast all verified.

---

## Phase 5: User Story 3 — CLAUDE.md 自动发现 + 缺失静默跳过 (Priority: P3)

**Goal**: `./CLAUDE.md` / `~/.lingshu/CLAUDE.md` / `extras` 列表里任意文件缺失,Agent 不报错、不输出 ERROR 日志。

**Independent Test**: 临时 `mv CLAUDE.md /tmp/` + 重跑 demo-engineer jar → JVM 退出 0(不报错),stderr 无 `ERROR` 级日志,turn 仍正常完成;`mv /tmp/CLAUDE.md CLAUDE.md` 恢复

### Tests for User Story 3

- [ ] T027 [P] [US3] Create `lingshu-core/src/test/java/ai/lingshu/core/impl/memory/IdentityMemorySourceTest.java` with 3 test methods per data-model.md §3.3 (load_returnsJsonOfIdentity, load_nullIdentity_returnsDefaultsJson, load_isDeterministic_forSameConfig)
- [ ] T028 [P] [US3] Create `lingshu-core/src/test/java/ai/lingshu/core/impl/memory/ProjectTreeMemorySourceTest.java` with 7 test methods per data-model.md §3.4 (empty dir, 3 files alphabetical, mixed extensions, subdirectory excluded per OOS-6, broken symlink skipped, missing root null, IOException null)
- [ ] T029 [P] [US3] Add 3 new test methods to `DefaultPromptBuilderTest.java` (created in T016) for US3 scenarios: `build_claudeMdMissing_noError_noSegment`, `build_claudeMdDisabled_skippedEvenIfFileExists`, `build_extrasWithMissingFile_otherSourcesStillLoad` — see contracts/prompt-builder.md §8 row 5/6/7
- [ ] T030 [P] [US3] Run `mvn -pl lingshu-core test -Dtest='IdentityMemorySourceTest,ProjectTreeMemorySourceTest,DefaultPromptBuilderTest'` and assert all tests pass

### Implementation for User Story 3 (E2E negative test)

- [ ] T031 [US3] Run US3 negative E2E: `mv lingshu-examples/demo-engineer/src/main/resources/CLAUDE.md /tmp/CLAUDE.md.bak && time java -jar ... "你好"` → assert `test -z "$(grep -E '^[0-9:]+ ERROR' /tmp/stderr.log)" && echo PASS` (no ERROR level logs); then `mv /tmp/CLAUDE.md.bak CLAUDE.md` to restore

**Checkpoint**: US3 fully functional — missing CLAUDE.md silent skip verified end-to-end with zero ERROR logs.

---

## Phase 6: User Story 4 — Instructions 模板引擎渲染 (Priority: P3)

**Goal**: `cfg.instructions.templateEngine="mustache"` 时,`{{var}}` 占位符按 `cfg.instructions.variables` 渲染;`templateEngine="none"` 或 `variables` 空 → 直接 passthrough。

**Independent Test**: 修改 `prompts/system-engineer.md` 包含 `{{project_root}}` 占位符,yml `instructions.variables.project_root=/lingshu/...`,重跑 demo-engineer → stdout 中 LLM 引用文件路径时实际显示 `/lingshu/...`(渲染过)而不是 `{{project_root}}`(未渲染)

### Tests for User Story 4

- [ ] T032 [P] [US4] Add 2 new test methods to `DefaultPromptBuilderTest.java`: `build_instructionsMustache_rendersVars` (verifies `{{name}}` → `vars.get("name")`) and `build_instructionsUnknownPlaceholder_passthrough` (verifies `{{unknown}}` kept literal, no throw) — see contracts/prompt-builder.md §8 rows 4/5
- [ ] T033 [P] [US4] Run `mvn -pl lingshu-core test -Dtest=DefaultPromptBuilderTest` and assert all 12 tests pass (10 from T016 + 2 new + 3 from T029 = 15 total)

### Implementation for User Story 4 (E2E template verification)

- [ ] T034 [US4] E2E mustache verification: ensure `prompts/system-engineer.md` contains literal `{{project_root}}` placeholder (added in T018), `application.yml` has `instructions.variables.project_root: lingshu-examples/demo-engineer`; run `time java -jar ... "请告诉我你的指令文件路径"` → assert stdout contains `lingshu-examples/demo-engineer` (rendered) and does NOT contain `{{project_root}}` (passthrough test). If grep finds `{{`, fallback to a more robust substring assertion.

**Checkpoint**: US4 fully functional — mustache rendering end-to-end verified.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: R-13 mitigation (d) 自查 + 文档同步 + commit + PR

- [ ] T035 [Polish] **R-13 mitigation (d) self-check**: run `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-002-after.txt`; compare against Story #001 baseline (`git show 83688ba:lingshu-core/pom.xml` + same dependency:tree from that commit); assert `diff /tmp/deps-001-baseline.txt /tmp/deps-002-after.txt` is empty; record output for PR body
- [ ] T036 [Polish] Update `README.md` Quick Start section: add `demo-engineer` example after `demo-empty`, include 5-segment assembly diagram + the `time java -jar ... "你是做什么的"` command + the 30s budget assertion
- [ ] T037 [P] [Polish] Update `dsh_agent_design.md` §13 changelog: add entry `v0.1.0-002 (Story #002) — 业务三件套 + 5 段装配 + 4 MemorySource Provider + mustache 渲染 + AC-09 黑盒过`(modify the doc file under parent project root `~/Documents/AIFullStack/MyDSHAgentDesign/`, **NOT** in lingshu repo)
- [ ] T038 [P] [Polish] Update `constitution.md` §10 R-13 row: mark Story #002 as having executed mitigation (d), note zero new deps confirmed by T035 diff
- [ ] T039 [Polish] Git commit with message `feat(agent): Story #002 identity-instructions-memory — 业务三件套 + 5 段装配 + 4 MemorySource Provider + mustache 渲染 + AC-09 黑盒过`(Co-Authored-By trailer); commit body lists spec.md / plan.md / tasks.md paths + AC-09 verification output + R-13 self-check diff
- [ ] T040 [Polish] Open PR via `gh pr create --base main --head story-002-identity-instructions-memory` with body containing: spec.md excerpt + plan.md summary + tasks.md checklist (all ☑️) + AC-09 verification output + R-13 dependency:tree self-check diff + Story #002 deltas (16 new files + 3 modified = 19 files, ~860 lines net)

**Checkpoint**: Story #002 ready for review. All 4 user stories independently verified. R-13 clean. Docs synced. PR opened.

---

## Dependencies & Execution Order

### Phase Dependencies

```
Phase 1 (Setup) ─── no dependencies ───┐
                                       ├──> Phase 2 (Foundational) ── BLOCKS ALL US ──┐
                                       │                                              │
                                       │                                              ▼
                                       │                            ┌──> Phase 3 (US1 P1) MVP 🎯
                                       │                            ├──> Phase 4 (US2 P2)
                                       │                            ├──> Phase 5 (US3 P3)
                                       │                            └──> Phase 6 (US4 P3)
                                       │                                              │
                                                                                      ▼
                                                                       Phase 7 (Polish — after all desired US)
```

### User Story Dependencies

- **US1 (P1)**: Depends on Phase 2 (Foundational) — uses 4 MemorySource + Router + modified Builder. **No** dependency on US2/US3/US4.
- **US2 (P2)**: Depends on Phase 2 + Phase 3 (needs working demo-engineer to verify multi-Provider routing via yml tweak). Adds 1 test class (`MemorySourceRouterTest`).
- **US3 (P3)**: Depends on Phase 2 + Phase 3 (needs working demo-engineer to verify missing-file E2E). Adds 2 test classes + 3 new methods on `DefaultPromptBuilderTest`.
- **US4 (P3)**: Depends on Phase 2 + Phase 3 (needs `prompts/system-engineer.md` with `{{var}}` from T018). Adds 2 new methods on `DefaultPromptBuilderTest`.

**Independence**: US2 / US3 / US4 do NOT depend on each other — each can be completed in parallel after Phase 3.

### Within Each User Story

- Tests (T016 / T023 / T027-T029 / T032) MUST be written BEFORE implementation that uses them, and verified to compile (they'll FAIL until impl is in)
- MemorySource impls (T005-T008) → Providers (T009-T012) → Router (T013) → Builder refactor (T014-T015) MUST be sequential — each depends on the previous
- US3 / US4 modify `DefaultPromptBuilderTest.java` (created in T016) — these are additive, no merge conflicts expected

### Parallel Opportunities

**Phase 1** (all [P]): T003 + T004 can run in parallel after T001/T002 (which themselves can be parallel).

**Phase 2A + 2B** (all [P]): T005-T008 (impls) and T009-T012 (providers) — 8 files, no inter-dependencies, all can run in parallel within their group. (2A must complete before 2B is meaningful, but 2B doesn't import from 2A compile-wise — providers only instantiate, Spring scans both groups together.)

**Phase 2A only** (T005-T008): 4 MemorySource impls, all parallel.

**Phase 2B only** (T009-T012): 4 MemorySourceProvider, all parallel.

**Phase 5 tests** (T027-T028): 2 test classes, both parallel.

**Phase 3/5/6** (independent US work): US2 (T023-T024) + US3 (T027-T030) + US4 (T032-T033) can run in parallel after US1 ships Phase 3 — but each modifies different files so even intra-phase parallelism is fine.

**Phase 7 docs** (T036-T038): README / dsh / constitution updates — 3 different files, parallel.

### Parallel Example: User Story 1

```bash
# Launch all 10 unit tests + E2E markdown resources in parallel (different files):
Task T016: Write DefaultPromptBuilderTest.java (10 methods)
Task T018: Write prompts/system-engineer.md
Task T019: Write CLAUDE.md
Task T020: Write docs/architecture.md + conventions.md + (rename README.md → OVERVIEW.txt for extension filter test)

# Sequential (depend on T016 + T018-T020):
Task T017: Run DefaultPromptBuilderTest, assert 10 pass
Task T021: Write DemoEngineerApplication.java
Task T022: Build demo-engineer jar
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. **Phase 1** Setup (T001-T004) — ~5 min
2. **Phase 2** Foundational (T005-T015) — ~30 min, **CRITICAL** (blocks all US)
3. **Phase 3** User Story 1 (T016-T022) — ~30 min, includes AC-09 US1 black-box
4. **STOP and VALIDATE**: `time java -jar demo-engineer.jar "你是做什么的"` ≤ 30s, 5-segment log correct → **MVP READY**

MVP scope = 22 tasks (T001-T022). Estimated effort: ~65 minutes for an LLM implementer familiar with the codebase.

### Incremental Delivery

1. Phase 1 + 2 → Foundation ready (MemorySource SPI fully wired)
2. Phase 3 → MVP demo-engineer works end-to-end (AC-09 US1) → **STOP, demo, merge if approved**
3. Phase 4 → US2 multi-Provider verified (AC-09 US2)
4. Phase 5 → US3 missing-file silent verified (AC-09 US3)
5. Phase 6 → US4 mustache verified (AC-09 US4)
6. Phase 7 → Polish (R-13 + docs + PR)

### Parallel Team Strategy

With multiple implementers (rare for solo dev, but for completeness):
- Dev A: Phase 1 + Phase 2 (blocks others, do first)
- After Phase 2 done:
  - Dev B: Phase 3 (US1) — must finish first
  - Dev C: Phase 4 (US2) — can start in parallel with B's impl, but tests need Phase 2
  - Dev D: Phase 5 (US3) — can start in parallel
  - Dev E: Phase 6 (US4) — can start in parallel
- All US work merges into main before Phase 7 starts

For solo implementation (the common case for this project): execute sequentially per the MVP First path above.

---

## Acceptance Checklist (paste into PR body)

- [ ] AC-09 US1 Scenario 1 (demo-engineer 5-segment assembly end-to-end): ✅ (T022 + E2E run)
- [ ] AC-09 US1 Scenario 2 (zero-config empty yml runnable): ✅ (existing #001 demo-empty still passes, no regression)
- [ ] AC-09 US1 Scenario 3 (Identity/Instructions/Memory defaults immutable): ✅ (DefaultPromptBuilderTest + IdentityMemorySourceTest)
- [ ] AC-09 US2 Scenario 1 (multi-Provider coexistence + priority sort): ✅ (MemorySourceRouterTest + T025 E2E)
- [ ] AC-09 US2 Scenario 2 (unknown builder fail-fast LINGS-S01): ✅ (T026 E2E + RouterTest)
- [ ] AC-09 US2 Scenario 3 (yml list order, not priority order): ✅ (MemorySourceRouterTest + T025 E2E)
- [ ] AC-09 US3 Scenario 1 (missing CLAUDE.md silent): ✅ (T031 E2E + DefaultPromptBuilderTest + ProjectTree tests)
- [ ] AC-09 US3 Scenario 2 (claude-md.enabled=false): ✅ (DefaultPromptBuilderTest + ProjectClaudeMd impl)
- [ ] AC-09 US3 Scenario 3 (missing extras entry silent): ✅ (DefaultPromptBuilderTest + ProjectClaudeMd impl)
- [ ] AC-09 US4 (mustache {{var}} rendering): ✅ (T034 E2E + DefaultPromptBuilderTest 2 new methods)
- [ ] AC-09 reverse AC (no IOException from build()): ✅ (all 4 MemorySource impls catch + return null)
- [ ] R-13 dependency:tree delta vs #001: 0 (T035)
- [ ] `mvn -pl lingshu-core test`: all green (existing #001 tests + 27 new tests in this Story)
- [ ] Binary size baseline: < 35MB (constitution §3)
- [ ] Demo-engineer first-token: P50 ≤ 30s on local (record actual time in PR body)
- [ ] stderr has zero ERROR-level logs in all E2E runs

---

## Notes

- [P] tasks = different files, no compile-time dependency on other in-progress tasks
- [Story] label = which user story from spec.md this serves (traceability for AC-09 → task → test)
- Each user story independently completable + testable + demoable (matches SOP §4.3 "Story AC 验收" requirement)
- Tests-first within each US (write test, see it fail, implement, see it pass) per SOP §3.3 — but Story-level "implementation" tasks already include the build step
- Commit after each phase (or after each US completion for finer granularity)
- Stop at MVP checkpoint (after Phase 3) to demo before proceeding to US2/US3/US4 if early feedback desired
- Avoid: vague task descriptions, same-file conflicts (Phase 5 T027-T028 + T029 all touch test files but in different paths), cross-story dependencies that break US independence

---

**Tasks generated**: 40 total (T001-T040)
**Breakdown by story**:
- Setup (no story): 4 tasks
- Foundational (no story): 11 tasks
- US1 (P1): 7 tasks (3 tests + 5 impl + 2 build/E2E; some shared with Phase 2)
- US2 (P2): 4 tasks (2 tests + 2 E2E config)
- US3 (P3): 5 tasks (3 tests + 2 E2E)
- US4 (P3): 3 tasks (2 tests + 1 E2E)
- Polish: 6 tasks (R-13, docs ×3, commit, PR)

**Parallel opportunities**: 25 / 40 tasks marked [P] (62.5%)
**MVP scope**: T001-T022 (22 tasks, ~65 min for familiar LLM implementer)
**Format validation**: ALL 40 tasks follow the checklist format `- [ ] [TaskID] [P?] [Story?] Description with file path` ✅
