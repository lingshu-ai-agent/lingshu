# Tasks: Story #007 yaml-hot-reload

**Input**: Design documents from `/specs/007-yaml-hot-reload/`
- spec.md(5 User Stories US1—US5 + 9 Edge Cases + FR-001—FR-010 + NFR-001—NFR-009)
- plan.md(9-step implementation order + 3 new + 2 modified core + 5 new test files + 13 L1 + 1 L5 E2E test cases)
- research.md(8 design decisions D-01—D-08 resolved)
- data-model.md(4 entities + state transitions + concurrency model)
- contracts/agent-config-registry.md(8 sections full contract)
- quickstart.md(7 Validation scenarios + AC-06 E2E)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#006 全部 merged(provides AgentFactory / AgentConfig / DefaultAgent / RuntimeSandbox / TenantContext / LinearTurnEngine / CancellationToken)

**Tests**: Required per FR-001—FR-010 + 9 NFR + 9 Edge Cases。13 L1 Unit + 1 L5 E2E = 14 测试用例。

**Constitution**: v1.0 — §1 #9 Plugin 发现 Spring SPI / §1 #11 默认实现位置 / §1 #12 启动时配置校验 / §2 13 依赖锁定(R-13 零新增)/ §4 错误码约定(0 新增 ErrorCode)/ §5 7 层金字塔 / §10 R-03 YAML 热更数据竞争缓解(3 件套全部落地)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3 / US4 / US5)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + capture Story #006 dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #006 dependency baseline: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-006-baseline.txt`
- [ ] T003 Verify current branch is `story-007-yaml-hot-reload` via `git branch --show-current`(已通过 spec/plan workflow 切到该分支)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core test` exits 0(Story #001—#006 tests all green — pre-implementation sanity)

**Checkpoint**: Setup ready — code modifications can begin.

---

## Phase 2: Foundational — `reload` 包 + `ConfigChangeListener` + `AgentConfigRegistry`(US1 + US5 基础 + 所有 US 阻塞依赖)

**Purpose**: Establish the foundational SPI + Registry **before** any user story wiring

**⚠️ CRITICAL**: 后续所有 US(US2 / US3 / US4 / US5)都依赖 `AgentConfigRegistry`,此 phase 必须先完成

- [ ] T005 [P] [US5] Create `ConfigChangeListener` SPI interface in `lingshu-core/src/main/java/ai/lingshu/core/reload/ConfigChangeListener.java`:
  - `public interface ConfigChangeListener`(FR-002)
  - 单方法:`void onConfigChange(AgentConfig previous, AgentConfig next);`
  - 类 Javadoc 明确 4 项约束:(a) **异常隔离** — listener 抛 RuntimeException 不影响 publish 主流程;(b) **禁止 publish 重入** — listener 内部禁止调 `registry.publish`(否则死循环);(c) **Idempotency** — 同一对 prev/next 可能被收多次;(d) **不要在 listener 内 spawn thread**
- [ ] T006 [US1] Create `AgentConfigRegistry` class in `lingshu-core/src/main/java/ai/lingshu/core/reload/AgentConfigRegistry.java`:
  - `@Component public class AgentConfigRegistry`(FR-001)
  - 字段:`private final AtomicReference<AgentConfig> currentRef = new AtomicReference<>();` + `private final CopyOnWriteArrayList<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();`
  - 构造器:`public AgentConfigRegistry(AgentConfig initialConfig)`(constructor-injected)
  - 方法:
    - `public AgentConfig current()` → `return currentRef.get();`(D-01 + NFR-007 lock-free volatile read)
    - `public void publish(AgentConfig next)`:
      - `if (next == null) throw new NullPointerException("next config must not be null");`(null 校验)
      - `final AgentConfig prev = currentRef.getAndSet(next);`
      - `for (ConfigChangeListener l : listeners) { try { l.onConfigChange(prev, next); } catch (Throwable t) { /* ERROR log, continue */ } }`(D-05 异常隔离)
    - `public void publishInitial(AgentConfig initial)` → `if (initial == null) throw NPE; publish(initial);`
    - `public void addListener(ConfigChangeListener listener)` + `public boolean removeListener(ConfigChangeListener listener)`(null 校验)
    - `int listenerCount()`(package-private, test-only)
  - 类 Javadoc 明确「单写多读 lock-free」+「Freeze semantics:turn 入口 `current()` 一次然后用本地变量」+「Listener 异常隔离」
- [ ] T007 Validate compile: `mvn -pl lingshu-core compile` exits 0(ConfigChangeListener + AgentConfigRegistry compile standalone)

**Checkpoint**: 基础 SPI + Registry 就绪 — 所有 User Story 现在可基于 registry 实现。

---

## Phase 3: User Story 1 — AgentConfigRegistry AtomicReference swap 单一权威(P1)🎯 MVP

**Goal**: 验证 `registry.publish(next)` 后 `current()` 立即返回新 cfg,**无中间态**;并发多线程 100% lock-free。

**Independent Test**: `AgentConfigRegistryTest` 5 个 L1 用例全过 — swap_immediateReads / listenerInvoked / listenerThrows_publishContinues / publishNull_throwsNPE / addRemoveListener

### Tests for User Story 1(L1 Unit)

- [ ] T008 [P] [US1] Write `AgentConfigRegistryTest` in `lingshu-core/src/test/java/ai/lingshu/core/reload/AgentConfigRegistryTest.java`:
  - `L1-001`:`#publishCurrentAtomic_swap_immediateReads` — FR-001 + D-01 + NFR-005:
    - `registry.publish(cfg1)` + `registry.current() == cfg1`
    - `registry.publish(cfg2)` + `registry.current() == cfg2`(lock-free 立即可见)
    - 100 线程并发 `current()` 全拿最新 publish 的 cfg(无丢失更新 / 无陈旧读)
  - `L1-002`:`#publishCurrent_listenerInvoked` — FR-001 + US5
    - mock listener `onConfigChange(cfg1, cfg2)` 注册 + `publish(cfg2)` → listener 收到 1 次调用,参数 = (cfg1, cfg2)
  - `L1-003`:`#listenerThrows_publishContinues` — FR-001 + D-05 异常隔离
    - listener 抛 RuntimeException + ok listener 注册 + `publish(cfg2)` → ok listener 仍被调 + 主流程不阻断
  - `L1-004`:`#publishNull_throwsNPE` — FR-001 null 校验
    - `publish(null)` 抛 NullPointerException
  - `L1-005`:`#addRemoveListener` — FR-001 listener 注册
    - `addListener(L1)` + `addListener(L2)` + `publish(cfg2)` → L1 + L2 都收 callback(注册顺序)
    - `removeListener(L1)` + `publish(cfg3)` → 仅 L2 收 callback
- [ ] T009 Validate L1 tests pass: `mvn test -Dtest=AgentConfigRegistryTest` exits 0(5 cases green)

**Checkpoint**: User Story 1 fully functional and testable independently — Registry 是整个 Story 的 **MVP** 核心。

---

## Phase 4: User Story 5 — Listener 注册 + 关键事件通知(P3,可选增强)

**Goal**: 业务插件可订阅 "config changed" 事件(后续 Story #009 / #016 plugin 实施期会用)— 本 Story 仅给 1-2 stub listener。

**Independent Test**: `ConfigChangeListenerTest` 2 个 L1 用例全过 — listenerRegistered_receivesCallback / listenerThrowsIsolated_continues

### Tests for User Story 5(L1 Unit)

- [ ] T010 [P] [US5] Write `ConfigChangeListenerTest` in `lingshu-core/src/test/java/ai/lingshu/core/reload/ConfigChangeListenerTest.java`:
  - `L1-006`:`#listenerRegistered_receivesCallback` — US5 S1 + FR-002:
    - `registry.addListener(l)` + `publish(cfg2)` → `l.onConfigChange(cfg1, cfg2)` 被调 1 次
    - 验证参数 previous == cfg1,next == cfg2
  - `L1-007`:`#listenerThrowsIsolated_continues` — US5 S1 + D-05:
    - l1 抛 RuntimeException + l2 ok + `publish(cfg2)` → l2.onConfigChange 仍被调(顺序 + 隔离 + ERROR 日志)

### Implementation for User Story 5(本 Story 无新增 impl)

> **注**:US5 实现已在 Phase 2 `ConfigChangeListener` 接口 + `AgentConfigRegistry.addListener / removeListener` 完成,Phase 4 仅 L1 测试覆盖。

- [ ] T011 Validate L1 tests pass: `mvn test -Dtest=ConfigChangeListenerTest` exits 0(2 cases green)

**Checkpoint**: US5 listener 机制 verified — 后续 Story #009 / #016 plugin 可直接 `implements ConfigChangeListener` 注册。

---

## Phase 5: User Story 2 + User Story 4 — YamlWatcher + validateOrThrow 复用(P1 + P2 合并)

**Goal**: yml mtime 变 → load + validate(`validateOrThrow` 复用) → publish;失败回退保留旧 cfg。

**Independent Test**: `YamlWatcherTest` 5 个 L1 用例全过 — mtimeChange_triggersPublishAfterValidation / mtimeUnchanged_skipsPublish / invalidYaml_keepsOldConfig / validationFail_keepsOldConfig / missingFile_warnsAndRetries

### Implementation for User Story 4(`loadYamlAndValidate` 复用,US2 依赖项)

- [ ] T012 [US4] Modify `AgentFactory` to add `loadYamlAndValidate(Path ymlPath)` method in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java`:
  - 新增 public 方法:`public AgentConfig loadYamlAndValidate(Path ymlPath) throws IOException`(FR-006 + D-04 复用)
  - 实现步骤:
    1. `byte[] bytes = Files.readAllBytes(ymlPath);`
    2. `AgentConfigProps props = new Yaml().loadAs(new ByteArrayInputStream(bytes), AgentConfigProps.class);`
    3. `AgentConfig next = props.toAgentConfig();`(复用 Story #001 既有 `AgentConfigProps.toAgentConfig()`)
    4. `validateOrThrow(next);`(复用 Story #001 既有 27+ 字段校验)
    5. `return next;`
  - 异常处理:`LingsConfigException("C02")`(validate 失败)/ `YAMLException`(SnakeYAML 解析失败)/ `IOException`(文件读失败)— 让 YamlWatcher.poll() catch
  - 既有 `validateOrThrow` / `create(cfg)` / 其他方法**不**改

### Implementation for User Story 2(YamlWatcher)

- [ ] T013 [US2] Create `YamlWatcher` class in `lingshu-core/src/main/java/ai/lingshu/core/reload/YamlWatcher.java`:
  - `@Component public class YamlWatcher`(FR-003 + D-02)
  - 字段:`private final Path ymlPath;` + `private final AgentConfigRegistry registry;` + `private final AgentFactory factory;` + `private final Yaml yaml = new Yaml();` + `private final long pollIntervalSeconds;` + `private final ScheduledExecutorService scheduler;` + `private final AtomicBoolean running = new AtomicBoolean(false);` + `private volatile long lastSeen;`
  - 2 个构造器:
    - Public:`YamlWatcher(@Value("${spring.config.location:application.yml}") String ymlPath, AgentConfigRegistry registry, AgentFactory factory)` → `this(Paths.get(ymlPath), registry, factory, 5L);`
    - Package-private(test-only):`YamlWatcher(Path ymlPath, AgentConfigRegistry registry, AgentFactory factory, long pollIntervalSeconds)`(D-08 可配置)
  - 构造器内:`scheduler = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "yaml-watcher"); t.setDaemon(true); return t; });`
  - 方法:
    - `@PostConstruct public void start()`:
      - `if (!running.compareAndSet(false, true)) return;`(防重复启动)
      - 初始化 `lastSeen`:`try { lastSeen = Files.getLastModifiedTime(ymlPath).toMillis(); } catch (NoSuchFileException e) { lastSeen = 0L; } catch (IOException e) { /* WARN log */ lastSeen = 0L; }`
      - `scheduler.scheduleWithFixedDelay(this::pollSafe, pollIntervalSeconds, pollIntervalSeconds, TimeUnit.SECONDS);`
    - `@PreDestroy public void stop()`:`running.set(false); scheduler.shutdownNow(); awaitTermination(5s);`
    - `private void pollSafe()`:`try { poll(); } catch (Throwable t) { /* ERROR log, watcher 不终止 */ }`
    - `synchronized void poll()`(package-private,test-only):
      - `if (!running.get()) return;`
      - `long current = Files.getLastModifiedTime(ymlPath).toMillis();`(catch `NoSuchFileException` → WARN + return;catch `IOException` → ERROR + return)
      - `if (current <= lastSeen) return;`(无变化,skip)
      - `AgentConfig next; try { next = factory.loadYamlAndValidate(ymlPath); } catch (Exception e) { ERROR log + return; /* lastSeen unchanged */ }`
      - `registry.publish(next); lastSeen = current; INFO log "config reloaded: model=X provider=Y max-steps=Z"`
  - 类 Javadoc 明确「单实例假设」+「无异常传播(pollSafe 兜底)」+「lastSeen 仅 success 更新」+「JDK 8 only(不用 WatchService)」

### Tests for User Story 2 + 4(L1 Unit)

- [ ] T014 [P] [US2] Write `YamlWatcherTest` in `lingshu-core/src/test/java/ai/lingshu/core/reload/YamlWatcherTest.java`:
  - **测试 Setup helper**:`YamlWatcherTestHelper` 创建 tmp dir + 写初始 yml + 构造 registry + factory + watcher(用 package-private 构造器注入 pollIntervalSeconds=0 或 1 + spy/poll 直接调)
  - `L1-008`:`#pollMtimeChange_triggersPublishAfterValidation` — US2 S2 + FR-003 + FR-006:
    - tmp dir + 初始 yml `sandbox.command-whitelist=[ls, cat]`
    - publish cfg1 后 watcher 启动
    - 修改 yml(append `git`) + `Files.setLastModifiedTime(d + "1s")`
    - `watcher.poll()`(直接调,避免 5s 等待)
    - 断言 `registry.current().getSandbox().getCommandWhitelist().contains("git")` + `lastSeen` 更新
  - `L1-009`:`#pollMtimeUnchanged_skipsPublish` — US2 S1:
    - yml 未改 + `poll()` → registry 仍持 cfg1 + lastSeen 未变(无副作用)
  - `L1-010`:`#pollInvalidYaml_keepsOldConfig` — US2 S3:
    - yml 写 broken content(`malformed: :: invalid yaml ::`)
    - `poll()` → registry 仍持 cfg1 + lastSeen 未更新(rollback)+ ERROR 日志
  - `L1-011`:`#pollValidationFail_keepsOldConfig` — US4 S1:
    - yml 缺 `agent.llm.provider` 字段
    - `poll()` → 抛 `LingsConfigException("C02")` → registry 仍持 cfg1 + ERROR 日志含 "C02" + 字段路径
  - `L1-012`:`#pollMissingFile_warnsAndRetries` — Edge Case:
    - yml 文件删除 + `poll()` → WARN 日志 + 不更新 lastSeen + 不替换 registry(等文件回来)
- [ ] T015 Validate L1 tests pass: `mvn test -Dtest=YamlWatcherTest` exits 0(5 cases green)

**Checkpoint**: YamlWatcher + validateOrThrow 复用 verified — US2 + US4 完整机制可工作。

---

## Phase 6: User Story 3 — DefaultAgent 旧 turn 冻结语义(P1, AC-06 freeze 核心)

**Goal**: T1 启动拿 cfg1,中途 `registry.publish(cfg2)`,T1 全程 cfg 引用未变;Java 引用语义 + final 字段自然冻结。

**Independent Test**: `InFlightFreezeTest` 1 个 L1 + Mockito 用例全过 — `finalFieldDefiesMidTurnConfigSwap`

### Tests for User Story 3(L1 + Mockito)

- [ ] T016 [P] [US3] Write `InFlightFreezeTest` in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/InFlightFreezeTest.java`:
  - `L1-013`:`#finalFieldDefiesMidTurnConfigSwap` — US3 + FR-004 + AC-06 freeze 核心:
    - 创建 registry + publish cfg1
    - 创建 DefaultAgent + Mockito spy
    - 启动 `agent.run(input)`(turn T1)
    - T1 跑到 step 1 时 mock `registry.publish(cfg2)`
    - T1 跑完,断言 `agent.getEffectiveCfg() == cfg1`(`assertSame`,引用相等)
    - 启动 T2,断言 `T2.getEffectiveCfg() == cfg2`

### Implementation for User Story 3(DefaultAgent 修改 + AgentFactory.create 重载)

- [ ] T017 [US1] Modify `AgentFactory` to add `create(cfg, registry)` overload in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java`:
  - 新增 public 重载:`public Agent create(AgentConfig cfg, AgentConfigRegistry registry)`(FR-005 + D-04)
  - 实现:`validateOrThrow(cfg); return new DefaultAgent(cfg, registry, /* other args */);`
  - 既有 `public Agent create(AgentConfig cfg)` 加 `@Deprecated`:
    - 注释:`@deprecated since Story #007 — prefer create(cfg, registry) for explicit registry;kept for backward compat with Story #001—#006.`
    - 实现内部:`return create(cfg, applicationContext.getBean(AgentConfigRegistry.class));`(从 Spring Context 拿默认 registry Bean)
    - 需加 import `org.springframework.context.ApplicationContext` + `@Autowired` 字段注入 ApplicationContext
- [ ] T018 [US3] Modify `DefaultAgent` to inject `AgentConfigRegistry` + freeze at `run()` entry in `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java`:
  - 字段新增:`private final AgentConfigRegistry registry;` + `private AgentConfig effectiveCfg;`(volatile 或 final 视实现)
  - 构造器签名变更:`public DefaultAgent(AgentConfig cfg, AgentConfigRegistry registry, /* 其他既有 args */)`(FR-004)
  - `run(String input)` 入口新增冻结点:`AgentConfig effectiveCfg = registry.current();`(D-03 + NFR-007 引用语义自然冻结)
  - 后续整个 turn 用 `effectiveCfg` 替代 `cfg` 参数(`effectiveCfg` 是本地 final 变量,Java 引用语义保证不会被 `registry.publish` 影响)
  - 加 debug hook `public AgentConfig getEffectiveCfg() { return effectiveCfg; }` for E2E 验证
  - 既有 ReAct 循环 / cancellation / tenant context 检查**不**改
- [ ] T019 Validate L1 tests pass: `mvn test -Dtest=InFlightFreezeTest` exits 0(1 case green)

**Checkpoint**: User Story 3 fully functional — AC-06 freeze 语义 verified。

---

## Phase 7: AC-06 黑盒 E2E 集成(YamlHotReloadIT)

**Purpose**: 整合 US1+US2+US3 + AC-06 完整黑盒验证

- [ ] T020 [P] Write `YamlHotReloadIT` in `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/YamlHotReloadIT.java`:
  - `@SpringBootTest` + `@ActiveProfiles("test-yaml-reload")` + `application-test-yaml-reload.yml` 配初始 sandbox whitelist=`[ls, cat, echo]`
  - `E2E-001`:`#ac06YamlHotReload_noInterrupt_oldTurnFrozen_nextTurnSeesNewConfig` — **AC-06 黑盒主路径**(SC-002):
    1. **Setup**:Spring 启动 + yml 配 `sandbox.command-whitelist=[ls, cat, echo]`
    2. **Action**:
       - 启动 T1 = `agent.runBlocking("[run 'ls /tmp']")`(`ls` 在旧 whitelist 里)
       - Sleep 100ms 等 T1 进 ReAct step 0
       - Modify yml:append `git` + `Files.setLastModifiedTime(d + "1s")`
       - Sleep 6s 等 watcher poll(默认 5s + buffer)
       - **Do NOT** cancel T1;just verify T1 still running with cfg1(debug hook:`agent.getEffectiveCfg() == cfg1`)
       - 启动 T2 = `agent.runBlocking("[run 'git status']")`
       - Wait T1 done:断言 T1 内部 sandbox 调用历史**不**包含 `git`(仅含 `ls`)
       - Wait T2 done:断言 T2 内部 sandbox 调用历史**包含** `git status` 且**成功**
    3. **Expected output**:
       ```
       T1 completed: tool calls = [ls /tmp], cfg ref = cfg1 (frozen)
       Watcher: reloaded config, lastSeen updated, publish(cfg2)
       T2 completed: tool calls = [git status], cfg ref = cfg2 (new)
       ```
- [ ] T021 Validate L5 E2E passes: `mvn test -Dtest=YamlHotReloadIT` exits 0(E2E-001 green + wall-clock < 15s)

**Checkpoint**: AC-06 black-box verified — Story #007 acceptance criterion satisfied。

---

## Phase 8: Full Test Suite + R-13 Self-Check + 回归测试

**Purpose**: 全部 13 + 1 = 14 测试用例 green + 无回归 + 0 新增依赖

- [ ] T022 Run full test suite: `mvn -pl lingshu-core test` exits 0(Story #001—#006 41 用例 + Story #007 14 用例 = 55 全 green)
- [ ] T023 Run R-13 dep-tree check: `mvn -pl lingshu-core dependency:tree -Dverbose=true > /tmp/deps-007-after.txt`
  - `diff /tmp/deps-006-baseline.txt /tmp/deps-007-after.txt` → **0 new dependencies**
  - If diff shows new transitive deps → STOP + investigate + RFC + remove
- [ ] T024 Capture AC-06 verification output: `mvn test -Dtest=YamlHotReloadIT -Dsurefire.useFile=false` — copy paste assertion results into PR body
- [ ] T025 Capture R-13 dep-tree diff: paste diff (or "no diff") into PR body `### R-13 dependency:tree 自查` section
- [ ] T026 Commit changes: `git add ... && git commit -m "feat(agent): Story #007 yaml-hot-reload — AgentConfigRegistry AtomicReference + YamlWatcher file poll + DefaultAgent freeze (AC-06)"`

**Checkpoint**: Story #007 implementation complete — ready for PR.

---

## Phase 9: PR + Merge + 文档同步

- [ ] T027 Push branch: `git push origin story-007-yaml-hot-reload`
- [ ] T028 Open PR with body template:
  - Summary(3 bullets: AtomicReference 单写多读 + YamlWatcher 5s poll + DefaultAgent freeze)
  - AC-06 black-box output(pasted from T024)
  - Test plan checklist(all 14 cases green)
  - **R-13 dependency:tree 自查** section(paste diff from T025)
  - Critical invariants(9 Slot 接口 / CancellationToken / AgentConfig 27+ 字段 / ReAct 循环 / TenantContext 全部不变)
  - "Story boundary: 3 new + 2 modified = 5 files" note(正好打平 CLAUDE.md §11 #4)
- [ ] T029 After PR review + merge: sync README.md(lingshu-cli 示例加 hot-reload demo)/ docs / dsh changelog §13(加 v1.5.x 条目)/ constitution §10(R-03 缓解状态 — 标记 "已落地:Story #007 AtomicReference + Java 引用 freeze + validateOrThrow rollback")

---

## Test Count Summary

| Layer | Count | Files |
|---|---|---|
| L1 Unit(Registry) | 5 | `AgentConfigRegistryTest.java` |
| L1 Unit(Listener) | 2 | `ConfigChangeListenerTest.java` |
| L1 Unit(Watcher) | 5 | `YamlWatcherTest.java` |
| L1 + Mockito(Freeze) | 1 | `InFlightFreezeTest.java` |
| L5 E2E(AC-06 black-box) | 1 | `YamlHotReloadIT.java` |
| **Total** | **14** | **5 test classes** |

---

## Definition of Done(Story #007 complete)

- [x] spec.md / plan.md / tasks.md / quickstart.md / contracts / checklists 6 件套齐全(本目录)
- [ ] 3 new + 2 modified = **5 文件改动**已提交(正好打平 CLAUDE.md §11 #4 ≤ 5 软上限)
- [ ] `mvn -pl lingshu-core test` 全绿(14 测试用例)
- [ ] **AC-06 黑盒验证通过**(`YamlHotReloadIT#ac06YamlHotReload_noInterrupt_oldTurnFrozen_nextTurnSeesNewConfig`,贴输出)
- [ ] `mvn dependency:tree` 自查:0 新依赖
- [ ] 关键不变项全部保留(9 Slot 接口签名 / CancellationToken / AgentConfig 27+ 字段 / ReAct 循环 / TenantContext / validateOrThrow)
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] Branch `story-007-yaml-hot-reload` pushed + PR opened

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - BLOCKS all user stories
- **User Stories (Phase 3-6)**: All depend on Foundational phase completion
  - US1 (P1) → US5 (P3) → US2+US4 (P1+P2) → US3 (P1) — priority + dependency order
- **Polish (Phase 7-9)**: Depends on all user stories being complete

### User Story Dependencies

- **US1 (P1)**: Can start after Foundational (Phase 2) - No dependencies on other stories
- **US5 (P3)**: Can start after US1 (Phase 3) - Depends on AgentConfigRegistry.addListener infrastructure
- **US2 + US4 (P1 + P2)**: Can start after US1 - Depends on AgentConfigRegistry.publish + AgentFactory.validateOrThrow
- **US3 (P1)**: Can start after US1 + AgentFactory.create overload - Depends on registry injection path

### Within Each User Story

- Tests (if included) MUST be written and FAIL before implementation(US1 test first T008, impl is already in T006)
- Models before services(Registry → Watcher → DefaultAgent)
- Core implementation before integration(US1+US2+US3 → AC-06 E2E)
- Story complete before moving to next priority

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel
- All Foundational tasks marked [P] can run in parallel (within Phase 2):T005 [P] + T006 sequential(ConfigChangeListener first, Registry depends on it)
- Once Foundational phase completes:
  - US1 tests T008 [P] can run in parallel with US5 test T010 [P]
  - US2 test T014 [P] can run in parallel with US3 test T016 [P]
  - E2E T020 [P] can run in parallel with individual unit tests
- All tests for a user story marked [P] can run in parallel

---

## Implementation Strategy

### MVP First(User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational(T005 ConfigChangeListener + T006 AgentConfigRegistry)
3. Complete Phase 3: User Story 1(T008-T009 tests + validation)
4. **STOP and VALIDATE**: Test US1 independently — `AgentConfigRegistryTest` 5 cases green
5. Deploy/demo if ready(MVP — Registry 单写多读 lock-free 单独可用)

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add US1(Registry)→ Test independently → Deploy/Demo(MVP!)
3. Add US5(Listener)→ Test independently → 后续 Story 实施期可用
4. Add US2+US4(Watcher + validateOrThrow)→ Test independently → 自动 reload 链路通
5. Add US3(DefaultAgent freeze)→ Test independently → AC-06 freeze 语义达成
6. Add AC-06 E2E → Test independently → Story #007 整体可 PR
7. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together(T005 + T006)
2. Once Foundational done:
   - Developer A: US1 tests(T008)+ US5 tests(T010)+ US1 validation
   - Developer B: US4 AgentFactory.loadYamlAndValidate(T012)+ US2 YamlWatcher impl(T013)+ US2 tests(T014)
   - Developer C: US3 AgentFactory.create overload(T017)+ DefaultAgent freeze(T018)+ InFlightFreezeTest(T016)
3. E2E T020 + validation T021 + R-13 + commit T022-T026 由一人统一收尾

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing(US1 T008 written before T009 validation;US2 T014 written before T015)
- Commit after each task or logical group(per task T026 + per story checkpoint)
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence

---

## Anti-Patterns to Avoid(CLAUDE.md §11 + §12)

- ❌ 用 `WatchService`(JDK NIO,平台兼容问题)
- ❌ 让 `DefaultAgent.run` 在中途 `registry.current()`(违反 freeze)
- ❌ Listener 内调 `registry.publish`(重入死循环)
- ❌ Listener 异常冒泡到 publish 主流程(异常应隔离)
- ❌ 用 `ConcurrentHashMap` 装 1 个 key(过度设计)
- ❌ publish 失败 → 自动 rollback(违反语义)
- ❌ 在 DefaultAgent 持 `AtomicReference<AgentConfig>` field(局部变量 freeze 更简洁)
- ❌ watcher 用 `synchronized` 锁所有路径(watchdog 阻塞 scheduler 自身)
- ❌ 5s 间隔硬编码不可配(NFS 降频需求)
- ❌ 引第三方 `io.methvin.watcher`(R-13 mitigation (d) 违反)
- ❌ 把 `agent.reload.disabled` yml binding 拉进 Story #007(留 Story 后续)
- ❌ 用 `var` / `List.of` / sealed interface / record(JDK 8 约束 CLAUDE.md §3)
- ❌ 新增 ErrorCode(0 新增 — NFR-004 显式约束,复用 LINGS-C02/C03/Z01)
- ❌ 改 9 Slot 接口 / CancellationToken / AgentConfig 27+ 字段 / ReAct 循环(关键不变项)
- ❌ `@AutoService` Java SPI 风格注册 listener(§5.7 决策 — 用 Spring `@Component`)
- ❌ InheritableThreadLocal 跨线程传递 config(无适用场景,registry 注入即可)
- ❌ `AgentConfig.snapshot()` 显式方法(@Value 已天然 immutable,冗余)
- ❌ Watcher 失败回退自动 rollback publish(只 skip 当前次,5s 后重试)
