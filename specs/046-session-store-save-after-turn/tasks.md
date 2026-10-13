# Story #046 Tasks — `SessionStore.save()` After-Turn Wired

> Spec/Plan/Tasks 三件套之第三件。本 Story 关闭 Story #014 的 save-after-call 死代码缺口 —— `DefaultAgent` 注入 `SessionStore` + `LinearTurnEngine` turn 完成 4 收口点全部 `session.checkpoint() + sessionStore.save(...)`。
>
> **设计哲学**:镜像 Story #045 compactor-turn-wiring 整套模式(`LinearTurnEngine` 7-arg ctor + `LinearTurnEngineProvider` 7-arg ctor + Spring 6 dual-ctor `@Autowired` 模式 + `NullObject` sentinel 保护历史 fixture),**不发明新模式**。
>
> **任务组织**:5 阶段共 **18 个任务**(T-01 ~ T-18),Phase 1/3/5 `[P]` 标记并行(同 Phase 内文件互不依赖),Phase 2/4 严格串行(每步是下一步的前置)。

---

## Phase 0 — Pre-flight 检查(开始前必跑)

| ID | 任务 | 期望输出 | 状态 |
|---|---|---|---|
| T-00.1 | `git status` 干净(worktree 无未提交改动) | `nothing to commit, working tree clean` | ⏳ |
| T-00.2 | `git log -1 --oneline` 当前 commit | 确认 commit hash(后续 R-13 baseline 比对锚点)| ⏳ |
| T-00.3 | `mvn -pl lingshu-core -am compile -q` 基线编译通过 | BUILD SUCCESS | ⏳ |
| T-00.4 | `mvn -pl lingshu-examples/demo-product test -q` 基线测试通过 | 全部通过(包含 Story #045 12 case)| ⏳ |
| T-00.5 | baseline 镜像:`git show HEAD:lingshu-core/pom.xml \| md5sum` | 记录 md5 值作为 R-13 (d) baseline | ⏳ |
| T-00.6 | dsh 头部版本号 = `v1.5.58`(Story #045 落地后最新)| 与 CLAUDE.md 「对应设计文档」字段对齐 | ⏳ |

> **Why Phase 0**:Story 实施必须从干净 baseline 开始;baseline 镜像锚点是 R-13 mitigation (d) **第 30 次 PASS** 验证的前置(pre/post `git show HEAD:lingshu-core/pom.xml | md5sum` 必须与 baseline 一致)。

---

## Phase 1 — Setup(并行,3 文件)

> **可并行原因**:3 个文件互不依赖,可同时编辑。

### T-01 [P] `NullSessionStore.java` 新建

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/sessionstore/NullSessionStore.java`(新目录,新文件)

**镜像**:`lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/NullCompactor.java`(Story #045 已落)

**契约**(与 `Compactor` 平行):
- `public final class NullSessionStore implements SessionStore`
- `public static final NullSessionStore INSTANCE = new NullSessionStore();`
- `private NullSessionStore() {}`(private ctor 防外部 new)
- `@Override public void save(Checkpoint c) { /* no-op */ }`
- `@Override public Optional<Checkpoint> load(String id) { return Optional.empty(); }`

**字段**:
- 不持有任何字段(纯 singleton)
- 不需要 `@Component`(避免 Spring 反射 new 它)

**Why needed**:`LinearTurnEngine` 8-arg ctor 第 8 参数 `SessionStore sessionStore` **必须非 null**(Story #045 dual-ctor 模式要求 primary ctor 字段 final 注入)。当 `SessionStoreRouter` 不可用(`@Autowired(required=false)` + `null`),Provider 传 `NullSessionStore.INSTANCE` 给 engine,save/load 退化为 no-op。

**AC mapping**:AC-046-11(NullSessionStore 契约 + INSTANCE 字段)

**预估**:38 行

---

### T-02 [P] `NullSessionStoreTest.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/sessionstore/NullSessionStoreTest.java`

**镜像**:`NullCompactorTest.java`(Story #045 已落)

**3 case L1**:
1. `instance_isSingleton` —— `NullSessionStore.INSTANCE == NullSessionStore.INSTANCE`(同一引用)
2. `save_isNoOp_doesNotThrow` —— 调 `save(new Checkpoint(...))` 不抛异常
3. `load_returnsEmpty` —— 调 `load("any-id")` 返 `Optional.empty()`

**Why needed**:验证 NullObject 模式的 3 段基础契约;为 T-04 dual-ctor 单元测试提供支撑 fixture。

**AC mapping**:AC-046-11

**预估**:58 行

---

### T-03 [P] `RecordingSessionStore.java` 新建(test-only fixture)

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/sessionstore/RecordingSessionStore.java`

**职责**:测试 fixture,记录所有 `save(Checkpoint)` 调用到 `List<Checkpoint> saves` 字段,供 assertion 用。

**契约**:
- `public final class RecordingSessionStore implements SessionStore`
- `public final List<Checkpoint> saves = new ArrayList<>();`(public 字段简化测试)
- `@Override public void save(Checkpoint c) { saves.add(c); }`
- `@Override public Optional<Checkpoint> load(String c) { return Optional.empty(); }`(本期 unused,OQ-046.1)

**Why needed**:T-06 `LinearTurnEngineSessionStoreTest` 7 case L1 全部需要断言 `save()` 调用次数 + 参数 capture;无 RecordingSessionStore → 需 story 每次 new File + 临时目录 → 慢 + 难调试 + 不必要 I/O 开销。

**AC mapping**:AC-046-2 / AC-046-4 / AC-046-5 / AC-046-7 / AC-046-8 / AC-046-9

**预估**:30 行

---

## Phase 2 — Core(严格串行,2 文件)

> **不可并行原因**:T-05(`LinearTurnEngineProvider`)直接 wire-up T-04(`LinearTurnEngine`)的 8-arg ctor;若 T-05 先做则编译失败。

### T-04 `LinearTurnEngine.java` 修改

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java`

**改动点 7 处**:

1. **字段新增**(L59 L107 之间):
   ```java
   /** Story #046 — turn 完成 → checkpoint + flush 守卫。**
   private final SessionStore sessionStore;
   ```

2. **5-arg legacy ctor**(L116-120)不改 —— Spring 不直接 new 它,只给旧测试用。

3. **6-arg ctor**(L130-136)委托 7-arg ctor,加 1 行:
   ```java
   public LinearTurnEngine(..., Routers.CompactorRouter compactorRouter) {  // Story #045 7-arg
       this(..., null, NullSessionStore.INSTANCE);  // 委托 8-arg,SessionStoreRouter=null,save=NullSessionStore no-op
   }
   ```
   **注意**:本 ctor 不是 Spring 直接 wire(Story #045 已确认),只供 `LinearTurnEngineProvider` 内部委托 + 旧测试用。

4. **7-arg ctor**(L145-163)扩 8-arg:
   ```java
   public LinearTurnEngine(
       AgentConfig config, DefaultSession defaultSession, ToolExecutor toolExecutor,
       PromptBuilder promptBuilder, ApprovalRegistry approvalRegistry,
       // Story #045:
       Routers.CompactorRouter compactorRouter,
       // Story #046:
       SessionStore sessionStore) {  // 🆕 Story #046
       super(...);
       this.config = config;
       // ... 现有 6 字段赋值 ...
       this.compassionRouter = compactorRouter;  // Story #045
       this.sessionStore = sessionStore;  // 🆕 Story #046
   }
   ```

5. **`trySave()` private helper 方法**(新增,在 runTurn 之前):
   ```java
   /** Story #046 — 在 4 个收口点统一调,异常仅 fail-隔离不抛。*/
   private void trySave() {
       try {
           Checkpoint checkpoint = defaultSession.checkpoint();
           sessionStore.save(checkpoint);
           LOG.debug("Story #046 saved checkpoint for session={}", defaultSession.id());
       } catch (RuntimeException e) {
           // Story #046 — save 失败不能让 turn 失败(already done partial work);
           // 仅 LOG.warn,不重抛,等同 Story #029 LinearTurnEngine.drainFail 的策略
           LOG.warn("Story #046 failed to save session={} checkpoint: {}",
               defaultSession.id(), e.getMessage());
       }
   }
   ```

6. **4 个收口点**(L210 / L305 / L312 / L346)各加 `trySave();`:
   - **L210**(loop-head cancel) —— `for (int step ...) { if (Thread.currentThread().isInterrupted()) { trySave(); sink.onNext(new AgentEvent.TurnCompleted(StopReason.CANCELLED, totalUsage)); return; }`
   - **L305**(normal end) —— `sink.onNext(new AgentEvent.TurnCompleted(reason, totalUsage)); trySave(); return;`
   - **L312**(exception) —— `catch (Exception e) { sink.onNext(new AgentEvent.TurnCompleted(StopReason.ERROR, totalUsage)); trySave(); throw e; }`
   - **L346**(waitForLlm cancel) —— 在 waitForLlm 抛 CancellationException 时,收口路径加 `trySave();` 在 `sink.onNext(...)` 之前

7. **import 新增**:`import ai.lingshu.core.impl.sessionstore.NullSessionStore;`(放在 phase 1 已有 import 之后)

**AC mapping**:AC-046-1 / AC-046-2 / AC-046-4 / AC-046-5 / AC-046-7 / AC-046-8 / AC-046-9 / AC-046-11

**Story budget 累计**:LambdaTurnEngine.java 当前 582 行 → 估计 +25 行(8 字段 + 1 helper 14 行 + 4 处插入 + import + javadoc)→ ~607 行。

**预估**:+25 行

---

### T-05 `LinearTurnEngineProvider.java` 修改

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngineProvider.java`

**改动点 3 处**:

1. **字段新增**(类级,与其他 Spring 7 Router 字段并列):
   ```java
   /** Story #046 — SessionStoreRouter @Autowired(required=false) optional,无 Router 时 fallback NullSessionStore。**
   private final Routers.SessionStoreRouter sessionStoreRouter;
   ```

2. **legacy 7-arg ctor 扩 8-arg 委托**(Story #045 现存):
   ```java
   public LinearTurnEngineProvider(...Routers.CompactorRouter compactorRouter) {  // Story #045 7-arg
       this(..., null);  // 🆕 Story #046 第 8 参 SessionStoreRouter=null
   }
   ```

3. **primary 7-arg ctor 扩 8-arg**(Story #045 现存,加 1 参 + `@Autowired` 标记):
   ```java
   @Autowired
   public LinearTurnEngineProvider(
       ...,
       // Story #045:
       @Autowired(required=false) @Nullable Routers.CompactorRouter compactorRouter,
       // 🆕 Story #046:
       @Autowired(required=false) @Nullable Routers.SessionStoreRouter sessionStoreRouter) {
       // ...
       this.sessionStoreRouter = sessionStoreRouter;
   }
   ```

4. **`create(AgentConfig)` 方法**(Story #045 现存,加 SessionStore resolve):
   ```java
   @Override
   public FlowEngine create(AgentConfig config) {
       Compactor compactor = compactorRouter != null
           ? compactorRouter.resolve(config.getCompactor(), config)
           : NullCompactor.INSTANCE;
       // 🆕 Story #046:
       SessionStore sessionStore = sessionStoreRouter != null
           ? sessionStoreRouter.resolve(config.getSessionStore(), config)
           : NullSessionStore.INSTANCE;
       return new LinearTurnEngine(
           config, defaultSession(...), toolExecutor(...)...,
           compactorRouter, compactor, sessionStoreRouter, sessionStore);
       // ↑ 8-arg ctor 调,SessionStoreRouter 传 engine 内部当前不用(留 OQ-046.1)
       // 实际只需要传 resolve 后的 SessionStore 给 engine
   }
   ```
   **设计取舍**:`sessionStoreRouter` 是否也传给 engine?本期 **不传**(保留字段供 OQ-046.1 follow-up 在 engine 内部做 reload 检查)。engine 当前只看 `SessionStore` 实例。

5. **import 新增**:`import ai.lingshu.core.impl.sessionstore.NullSessionStore;`(与 T-04 import 对齐)

**AC mapping**:AC-046-2 / AC-046-3 / AC-046-11

**Story budget 累计**:LinearTurnEngineProvider.java 当前 142 行 → +18 行(字段 + 2 ctor 扩参 + 4 行 resolve + javadoc)→ ~160 行。

**预估**:+18 行

---

## Phase 3 — Tests(并行,4 文件)

> **可并行原因**:4 文件互不依赖(只读 Phase 1/2 已落盘的 `NullSessionStore` / `RecordingSessionStore` / `LinearTurnEngine` / `LinearTurnEngineProvider`),可同时编辑。

### T-06 [P] `LinearTurnEngineSessionStoreTest.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineSessionStoreTest.java`

**6 case L1**(基于 `RecordingSessionStore` + `MockLlmProvider` + 真实 `LinearTurnEngine` 跑 mock turn):

1. `normalEnd_savesOnce` —— 跑一个 user message → llm text → sink 收 `TurnCompleted(reason=STOP)` → `recording.saves.size() == 1` + save 内容 = `defaultSession.checkpoint()`
2. `cancelLlm_cb_step_savesOnce` —— step 1 中断(thread interrupt)→ `recording.saves.size() == 1` + reason = `CANCELLED`
3. `exceptionInLlm_savesOnce` —— mock llm 抛 RuntimeException → `recording.saves.size() == 1` + reason = `ERROR` + 异常继续抛(不被吞)
4. `waitForLlmCancel_savesOnce` —— `waitForLlm` 中 `Thread.currentThread().isInterrupted()` 触发 → `recording.saves.size() == 1` + reason = `CANCELLED`
5. `saveThrows_saveDoesNotBreakTurn` —— `RecordingSessionStore` 改造为抛 RuntimeException + `LinearTurnEngine.runTurn()` 仍正常返回 + sink 仍收 `TurnCompleted(reason=STOP)`(守 save failure 不能 break turn)
6. `nullSessionStore_savesAreNoOp` —— 8-arg ctor 传 `NullSessionStore.INSTANCE` + 跑 turn → 不抛 + 不写 disk(隐式)

**镜像 fixture**:`MaxStepsGuardTest` + `CompactionTest`(Story #045 已落)+ `LinearTurnEngineTest`(Story #001 已落)。

**AC mapping**:AC-046-4 / AC-046-5 / AC-046-7 / AC-046-8 / AC-046-9 / AC-046-10

**预估**:~280 行

---

### T-07 [P] `LinearTurnEngineProviderSessionStoreTest.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineProviderSessionStoreTest.java`

**3 case L1**:

1. `dualCtor_legacyCtorDelegatesToPrimary_withNullSessionStoreRouter` —— `new LinearTurnEngineProvider(...approvalRegistry, compactorRouter)`(7-arg) → 内部 8 字段 `sessionStoreRouter == null`
2. `create_resolvesSessionStoreViaRouter` —— `Mock SessionStoreRouter` 返 `RecordingSessionStore` → `provider.create(cfg)` 返回的 engine 内部 `sessionStore` 字段 == `RecordingSessionStore`(反射验证)
3. `create_fallsBackToNullSessionStore_whenRouterNull` —— 7-arg legacy ctor 实例 + `provider.create(cfg)` → engine 内部 `sessionStore instanceof NullSessionStore`(反射验证)

**镜像 fixture**:`LinearTurnEngineProviderCompactionTest`(Story #045 已落)。

**AC mapping**:AC-046-2 / AC-046-3 / AC-046-11

**预估**:~110 行

---

### T-08 [P] `AgentFactoryYamlSessionStoreSaveIT.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlSessionStoreSaveIT.java`

**3 case L2**(真 `AgentFactory.create()` + 跑完整 turn + 验磁盘文件):

1. `yamlDefaultMemory_sessionStore_inMemoryNoFile` —— yml `agent.session-store: memory` + 跑 turn → `<temp>/lingshu-sessions/<tenant>_<session>.json` 不存在 + `RecordingSessionStore.saves.size() == 1`
2. `yamlFile_sessionStore_writesToDisk` —— yml `agent.session-store: file` + `LINGSHU_SESSION_DIR=<temp>/lingshu-sessions` + 跑 turn → 文件**真存在** + 内容是 valid `Checkpoint` JSON(Jackson 反序列化验证)
3. `yamlFile_sessionLoadAfterCreate_returnsCheckpoint` —— case 2 后 `agent2 = factory.create(cfg)`(同 sessionId)→ 跑空 prompt(只 LLM 1 step)+ `DefaultSession.history` 长度 ≥ 1 + save 二次

**镜像 fixture**:`AgentFactoryYamlCompactorIT`(Story #045 已落)+ `AgentFactoryYamlConcurrencyCapIT`(Story #044 已落)。

**Why L2**:必须真跑完整 turn(不能只 mock turn boundary),验证磁盘文件真实落盘 —— 直接打 Story #014 / Story #045 「e2e 跑完整 turn 后断言磁盘文件存在,不能只验 router.resolve()」硬规则。

**AC mapping**:AC-046-12 / AC-046-13 / AC-046-14

**预估**:~180 行

---

### T-09 [P] `AgentFactoryYamlSessionStoreLoadIT.java` 新建

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlSessionStoreLoadIT.java`

**2 case L2**(跨进程 checkpoint load round-trip):
1. `file_loadAfterFileWritten_returnsMatchingCheckpoint` —— T-08 case 2 写文件 → `factory2 = AgentFactory.create(cfg)`(同 sessionId)→ `provider.create(cfg).load(sessionId)` 返 `Optional<Checkpoint>` + 内容 == T-08 case 2 save 的 Checkpoint
2. `file_loadUnknownSession_returnsEmpty` —— `factory.load("nonexistent-session-id")` 返 `Optional.empty()` + 不抛

**镜像 fixture**:T-08 共享 helper class(`AgentFactoryTestSupport`)。

**AC mapping**:AC-046-13(共享)/ AC-046-6(OQ-046.1 follow-up 守卫)

**预估**:~120 行

---

## Phase 4 — Validation(严格串行,4 步骤)

> **不可并行原因**:每步是下一步的前置(RAC 必须全过后才跑 DepTree,DepTree 通过后才跑 demo-product 验证)。

### T-10 RAC 全量回归测试

**任务**:`mvn -pl lingshu-core test`

**期望**:`full suite 720 + 11 net new case = 731 tests pass / 0 fail / 2 MCP heartbeat flake pre-existing`

**Why sequential first**:DepTree baseline 校验前要确保全部测试 0 fail,否则 baseline 比对无意义。

**AC mapping**:AC-046-15(RAC)

---

### T-11 R-13 mitigation (d) baseline 镜像校验

**任务**:
```bash
# pre check
git show HEAD:lingshu-core/pom.xml | md5sum
# expect: <baseline-md5>  (T-00.5 记录值)

# 实施 + commit 后
git show HEAD:lingshu-core/pom.xml | md5sum
# expect: <baseline-md5>  (相同 = 0 binary delta)
```

**为什么 `pom.xml` 而不是 `dependency:tree`**:Story #045 / #043 / #014 等已落 precedent —— `mvn dependency:tree` 输出含时间戳,pre/post diff 即使 0 binary delta 也会有 timestamp 噪音;`git show <commit>:lingshu-core/pom.xml | md5sum` 是 git-级权威 baseline,pre/post 必须 byte-identical。

**Why sequential after RAC**:DepTree baseline 是 R-13 mitigation (d) **第 30 次 PASS** 的最终证据,任何 pom.xml 改动(包括 version bump / scope / dep)都 fail 这条路。

**AC mapping**:AC-046-16(DepTree 0 binary delta 第 30 次 PASS)

---

### T-12 demo-product 端到端验证

**任务**:
```bash
mvn -pl lingshu-examples/demo-product spring-boot:run
# 等待 startup (~10s)
# 浏览器侧操作:
# 2.1 POST /api/sessions → 拿 sessionId
# 2.2 POST /api/chat SSE 流 → 1 条 user message "hi" → 收 SSE → TurnCompleted
# 2.3 ls $LINGSHU_SESSION_DIR/lingshu-sessions/<tenant>_<session>.json
#     expect: 文件存在 + 非 0 字节 + JSON parseable
```

**Why**:T-9 e2e 验证 demo-product 是真的「落地可达」,不是仅 lingshu-core 内自洽。镜像 Story #042(frontend UI 闭环验证 precedent)。

**Expected**:文件存在 + size > 500B + Jackson 反序列化 `Checkpoint` 无异常 + `history` size ≥ 2(user + assistant)。

---

### T-13 人工端到端 7 步清单(README.md 同步)

**任务**:更新 README.md 的 demo-product 章节,新增 7 步验证清单:
1. `mvn -pl lingshu-examples/demo-product spring-boot:run`
2. curl 浏览器确认 UI 加载
3. yml 配 `agent.session-store: file` + `LINGSHU_SESSION_DIR=/tmp/lingshu-test`
4. 浏览器 chat → "list current directory"
5. SSE events 区看 `TurnCompleted`
6. `ls /tmp/lingshu-test/lingshu-sessions/_default_<sessionId>.json` 验证文件存在
7. `jq .history` 反序列化验证 JSON 完整

**镜像**:Story #042 7 步清单 precedent。

---

## Phase 5 — Docs(并行,5 文件)

> **可并行原因**:5 文件互不依赖(各文档独立)。

### T-14 [P] `CLAUDE.md` 同步 v1.3.54 → v1.3.55

**任务**:在「Last updated」段加 🆕 v1.5.59 Story #046 顶部 blockquote,补 🆕 SessionStore.save() turn 完结联动 bullet + 「Story 路线图」段追加 #046 retrospective(NullSessionStore + LinearTurnEngine 8-arg ctor + LinearTurnEngineProvider 8-arg ctor + Spring 6 dual-ctor @Autowired 模式 + trySave helper 4 收口点 + 18 net new case AC 黑盒验证 / 3 prod 文件改动 + 5 test 文件 / R-13 0 binary delta 第 30 次 PASS / 0 新 ErrorCode / 累计 47 个 Story)。

**镜像**:Story #045 v1.3.54 → v1.3.55 precedent。

---

### T-15 [P] `dsh_agent_design.md` 同步 v1.5.58 → v1.5.59

**任务**:
- §0 标题 `v1.5.58` → `v1.5.59`
- §13 changelog 新增 v1.5.59 行 ~12 节记录 Story #046 完成:
  - §6.1 LinearTurnEngine 段补 save 4 收口点 `🆕 v1.5.59 Story #046` marker
  - §5.6.4 SPI 总表 Slot 5 SessionStore row 补「save 4 收口点接入」字段
- 「对应设计文档」版本号 v1.5.58 → v1.5.59

**Why**:大版本同步(dsh → v1.5.59),保持 single-source-of-truth。

---

### T-16 [P] `README.md` 同步

**任务**:
- 「更新日期」段加 🆕 v1.5.59 Story #046 顶部 blockquote
- 「核心特性」段补 💾 SessionStore.save() turn 完结联动 bullet(turn 完成 4 收口点统一 flush,跨进程可恢复)
- 「Story 路线图」段追加 #046 retrospective(3 prod 文件 + 5 test 文件 + 11 case AC 黑盒验证 + demo-product 端到端 7 步清单 / R-13 0 binary delta 第 30 次 PASS / 0 新 ErrorCode / 累计 47 个 Story)

---

### T-17 [P] `specs/ROADMAP.md` 同步

**任务**:
- 段一 ✅ 已完成加 #046 行(2026-10-13,full suite pass / 0 fail / 2 MCP heartbeat flake pre-existing / R-13 0 binary delta 第 30 次 PASS / 0 新 ErrorCode / 累计 47 个 Story)
- 段二 🟡 待补 #046 划掉(§6 关键实现 主链 + 全部并行支链 100% ✅)
- 段五 🎯 实施节奏 next = 下一 Story + 累计计数 46 → **47 个 Story**

---

### T-18 [P] `constitution.md` §10 R-13 缓解 Story 列表同步

**任务**:`constitution.md` §10 R-13 缓解 Story 列表补 `#046` 行 **第 30 次** R-13 mitigation (d) PASS 0 binary delta + 累计计数 29 → **30 个 Story**(Story #014 = #1 28,Story #015 = #2 28 等,Story #046 = #30 30)。

**Why milestone**:**第 30 个 0 binary delta PASS** 是个值得记住的 milestone(实施成本纪律证明)。

---

## 任务依赖图

```
Phase 0 (T-00.1 ~ T-00.6,sequential)
   │
   ▼
Phase 1 (T-01 ~ T-03,[P] parallel)
   │
   ▼
Phase 2 (T-04 → T-05,strict sequential)
   │  编译依赖:T-05 must run after T-04
   ▼
Phase 3 (T-06 ~ T-09,[P] parallel)
   │  编译依赖:依赖 Phase 2 的 LinearTurnEngine / LinearTurnEngineProvider 已落盘
   ▼
Phase 4 (T-10 → T-11 → T-12 → T-13,strict sequential)
   │  验证依赖:RAC 通过 → DepTree 通过 → demo-product 验证 → 人工清单
   ▼
Phase 5 (T-14 ~ T-18,[P] parallel)
```

---

## 任务总览(18 任务 + 6 Phase 0 = 24 步骤)

| 阶段 | 任务数 | 串行/并行 | 文件改动 |
|---|---|---|---|
| Phase 0 | 6 | strict sequential | 0(只验证) |
| Phase 1 | 3 | [P] parallel | 3 new files |
| Phase 2 | 2 | strict sequential | 2 modify |
| Phase 3 | 4 | [P] parallel | 4 new files |
| Phase 4 | 4 | strict sequential | 0(只验证) |
| Phase 5 | 5 | [P] parallel | 5 doc files modify |
| **合计** | **24** | - | **3 new + 7 modify = 10 file delta**(3 prod + 5 test + 5 docs - 3 prod shared modify - 2 prod modify = 9 prod/test + 5 docs) |

**注意**:Phase 0 6 步骤为 pre-flight 检查,不计入 18 任务;核心实施 = 18 任务(3 + 2 + 4 + 4 + 5)。

---

## AC ↔ Task 映射表

| AC | 任务 |
|---|---|
| AC-046-1(turn 完成 → save())| T-04(L210 / L305 / L312 / L346 收口点) |
| AC-046-2(dual-ctor Spring 6 + dual)| T-04 / T-05 |
| AC-046-3(Provider resolve sessionStore)| T-05 / T-07 |
| AC-046-4(normal-end save())| T-06 case 1 |
| AC-046-5(cancel save())| T-06 case 2 |
| AC-046-6(load 端连通)| T-09(本期为 OQ-046.1 follow-up) |
| AC-046-7(exception save())| T-06 case 3 |
| AC-046-8(waitForLlm cancel save())| T-06 case 4 |
| AC-046-9(save throw 不崩 turn)| T-06 case 5 |
| AC-046-10(NullSessionStore no-op)| T-02 / T-06 case 6 |
| AC-046-11(NullSessionStore 契约)| T-01 / T-02 / T-07 case 3 |
| AC-046-12(yml memory 不写盘)| T-08 case 1 |
| AC-046-13(yml file 写盘 + 跨进程读)| T-08 case 2 / T-09 case 1 |
| AC-046-14(yml file 二次写)| T-08 case 3 |
| AC-046-15(RAC 全量回归)| T-10 |
| AC-046-16(DepTree 0 binary delta)| T-11 |

---

## Story 预算守护

| 项 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件改动(Phase 1 + 2)| ≤ 5 | 3(2 modify + 1 new NullSessionStore + Phase 1 含 2 test helper)| ⚠️ Phase 1 含 2 test helper 不计入 prod |
| 测试文件(Phase 3)| 不限 | 4 new(11 case L1/L2)| ✅ |
| ErrorCode 引入 | ≤ 3 | 0(复用 LINGS-X01)| ✅ |
| 新 Maven 依赖 | 0 | 0(R-13 (d) baseline 镜像第 30 次 PASS 守卫)| ✅ |
| dsh 大版本同步 | 必须 | v1.5.58 → v1.5.59 | ✅ |

> **核心文件改动真实只有 3**:LinearTurnEngine.java modify + LinearTurnEngineProvider.java modify + NullSessionStore.java new。Phase 1 的 NullSessionStoreTest + RecordingSessionStore 不计入 prod(测试 fixture)。

---

## R-13 mitigation (d) baseline 镜像 checklist(第 30 次 PASS 守卫)

- [ ] **pre-baseline**:T-00.5 记录 `git show HEAD:lingshu-core/pom.xml | md5sum` = `<baseline-md5>`
- [ ] **post-implementation**:T-11 跑 `git show HEAD:lingshu-core/pom.xml | md5sum` = `<baseline-md5>`(与 pre 相同)
- [ ] **Diff check**:`git diff HEAD~1 HEAD -- lingshu-core/pom.xml` = NO changes
- [ ] **Why pom.xml 而不是 dependency:tree**:`mvn dependency:tree` 输出含时间戳噪音;`pom.xml` 是 git-级权威 baseline(byte-identical)
- [ ] **累计计数**:第 30 次 0 binary delta PASS,constitution §10 R-13 缓解 Story 列表 = **30 个**

---

## 业务价值回顾

| 维度 | 价值 |
|---|---|
| **业务闭环** | 用户配 `agent.session-store: file` 后,turn 完成 → 磁盘落盘 → 跨进程可恢复(本 Story 完成 save 端,OQ-046.1 follow-up 完成 load 端)|
| **生产 9 Slot** | Slot 5 SessionStore SPI 从「yml 接线 + Provider 可解析」升级到「save/load 真生产可达」,**第 9 Slot 100% 真生产就绪** |
| **0 binary delta** | 纯 JDK 8 `try/catch` + `Lombok @Value` + Spring `@Autowired(required=false)` + `Optional.empty()` 已锁 13 项依赖表内,R-13 (d) 第 30 次 PASS |
| **Pattern 复用** | 完整镜像 Story #045 compactor-turn-wiring 模式 —— `LinearTurnEngine` 7-arg → 8-arg + Provider 7-arg → 8-arg + Spring 6 dual-ctor `@Autowired` + `NullObject` sentinel,后续 #047+ 加新 SPI 时直接复用 |
| **demo-product 旗舰** | 用户在 demo-product 浏览器实测 → 磁盘文件落盘 → 可截屏;README.md 7 步验证清单 |

---

## 实施节奏预估

> **Skip time estimates** (per project rule)。Focus on what needs to be done, not how long it might take.

---

**修复者**:Claude Code(根据用户 2026-10-13 会话反馈「Story #014 SessionStore.save() 是死代码,起 Story #046 收尾」+「现在就起 Story #046」,触发本 Story spec/plan/tasks 落地)

**后续**:Phase 0 → Phase 1 → ... → Phase 5 顺序执行;每个 Phase 完成 → 状态 ⏳ → ✅。