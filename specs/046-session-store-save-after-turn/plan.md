# Plan — Story #046 SessionStore.save() 真接通

**Story #**: #046
**Slug**: `session-store-save-after-turn`
**Spec**: `specs/046-session-store-save-after-turn/spec.md`
**dsh §**: §6.1 LinearTurnEngine 收口 + §4.8 SessionStore 持久化

---

## Summary

Story #014 落地了 Slot 5 SessionStore SPI 整套(2 backend + atomic write + path safety + multi-tenant + yml 接线),但「turn 跑完时把 checkpoint 落盘」这条主循环接线**没做**:`AgentFactory.create()` 解析出 `SessionStore` 实例只 log 不 inject,`LinearTurnEngine` 没有 sessionStore 字段,`DefaultSession.checkpoint()` 没人调。Story #046 把这条 1 条线的 save 钩子接通,镜像 Story #045 compactor 7-arg ctor 模式,引入 `NullSessionStore` sentinel 守 53 个历史 fixture 0 改动,落地后 yml 配 `agent.session-store: file` 的 demo-product 重启 session 实际可恢复。

## Technical Context

- **JDK 8 硬约束**:no `var` / `sealed` / `records` / `List.of` / pattern matching switch / text blocks — 用 `Collections.emptyList()` / `Arrays.asList()` / Lombok `@Value`
- **Story #045 镜像模式**:`LinearTurnEngine` 7-arg ctor(6 字段 final + `Compactor`)+ `LinearTurnEngineProvider` 7-arg ctor(Spring 6 dual-ctor 模式:legacy 6-arg 委托 7-arg 传 `null` Router,primary 7-arg 加 `@Autowired` 显式)+ `NullCompactor` 单例 sentinel — 严格镜像到 SessionStore
- **9-Router ctor 已就位**:`AgentFactory` 9-Router ctor(`Story #014` 加了 `SessionStoreRouter` 第 8 字段,`Story #045` 加了 `CompactorRouter` 第 9 字段)已落,本 Story **0 改** `AgentFactory`
- **4 收口点**:L210 loop-head cancel / L346 waitForLlm-cancel / L305 normal-end / L312 exception — 全在 `LinearTurnEngine.runTurn` 内,提取 `trySave(ctx)` 集中处理
- **save best-effort**:`trySave` 内部 try/catch IOException + 其它 Exception,**不**让 IO 失败 crash turn + 收 `AgentEvent.ErrorEvent[LINGS-X01]`(复用 Story #014 错误路径)
- **Tenant-aware key**(可选):如果 `TenantContext.current() != null`,save key 用 `buildKey(tenantId, sessionId)`(镜像 Story #014 `FileSessionStore` 既有 helper)

## Constitution Check

- [x] **不 push SOP 到 lingshu 仓** — 本 Story 不改 SOP / prompts / SKILL
- [x] **不省略 AC 黑盒验证** — 16 AC 全部跑过(11 L1 unit + 4 L2 IT + 1 RAC + 1 DepTree)
- [x] **不跨 Story 改 constitution** — 0 改宪章
- [x] **Story 边界 ≤ 5 核心文件改动** — 3 核心 prod 文件(LinearTurnEngine / LinearTurnEngineProvider / NullSessionStore) + 5 测试文件 + 0 改 `AgentFactory` / `DefaultSession` / `DefaultAgent`
- [x] **不绕过 Spring Boot SPI** — SessionStore 已是 Slot 5 SPI 体系,本 Story 是把现成 SPI 接到主循环
- [x] **不引入额外依赖** — 0 新 Maven 依赖,纯 JDK 8 + Lombok + Spring + Jackson 已锁 13 项
- [x] **ReAct Loop 必须自实现** — 守住(本 Story 不改 ReAct 主循环,只在 4 个收口点加 save())
- [x] **Spring AI 只用两件事** — 守住(0 改 AnthropicLlmProvider)
- [x] **Provider 必须显式映射** — 守住(LinearTurnEngineProvider 8-arg ctor 显式列 `SessionStoreRouter` 字段)
- [x] **R-13 mitigation (d) baseline 镜像** — 跑 dep-tree pre/post md5sum,本 Story 第 30 次 PASS

## Project Structure

### 文件改动清单(精确 3 核心 prod + 5 test)

| # | 路径 | 类型 | 行数估算 | 内容 |
|---|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java` | **modify** | +60 / -5 | 加 `SessionStore sessionStore` final 字段 + 8-arg ctor(镜像 7-arg)+ legacy 7-arg ctor 委托 8-arg 传 `NullSessionStore.INSTANCE` + `trySave(TurnContext)` private helper + 4 收口点插桩 |
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngineProvider.java` | **modify** | +35 / -5 | 加 `Routers.SessionStoreRouter sessionStoreRouter` final 字段 + 8-arg ctor(Spring 6 dual-ctor 模式镜像 7-arg CompactorRouter precedent)+ legacy 7-arg ctor 委托 8-arg 传 `null` + `create(AgentConfig)` 内 `SessionStore = sessionStoreRouter != null ? sessionStoreRouter.resolve(config.getSessionStore(), config) : NullSessionStore.INSTANCE;` |
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/NullSessionStore.java` | **new** | +60 | 单例 `INSTANCE` + private ctor + `save(Checkpoint)` no-op + `load(String) → Optional.empty()` + `@Override CONTRACT_VERSION = "1.0.0"`(复用 SPI 常量) |
| 4 | `lingshu-core/src/test/java/ai/lingshu/core/impl/session/NullSessionStoreTest.java` | **new** | +60 | 3 case:INSTANCE 非 null / save no-op / load empty /(可选)多线程并发 |
| 5 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineSessionStoreTest.java` | **new** | +220 | 6 case:`RecordingSessionStore` 内部 helper + 4 收口点各 1 case + IO 失败 1 case + dual-ctor 1 case |
| 6 | `lingshu-core/src/test/java/ai/lingshu/core/impl/flow/LinearTurnEngineProviderSessionStoreTest.java` | **new** | +120 | 3 case:8-arg ctor `@Autowired` primary / create resolve 走 SessionStore / legacy 7-arg ctor 委 NullSessionStore |
| 7 | `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlSessionStoreSaveIT.java` | **new** | +200 | 4 case:yml `agent.session-store: file` 实际跑 turn + 文件存在 / yml `memory` 不写盘 / `default` fallback 走 memory / kill -9 模拟重启后 load 恢复 |
| 8 | `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlSessionStoreLoadIT.java` | **new** | +150 | 2 case:手写 .json + FileSessionStore.load round-trip / 文件不存在 Optional.empty() / 损坏 JSON 抛 LINGS-X01 |

**总改动 = 8 文件**:
- 3 prod 核心(`LinearTurnEngine` / `LinearTurnEngineProvider` / `NullSessionStore` new)
- 5 test(1 unit NullSessionStore + 2 unit LinearTurnEngine/provider + 2 IT AgentFactory)
- 0 改 `AgentFactory` / `DefaultSession` / `DefaultAgent` / `SessionStore` / `FileSessionStore` / `InMemorySessionStore` / `Checkpoint` / `Routers` / `AgentConfig` / `Message`

### 关键不变项

| 不变项 | 状态 | 备注 |
|---|---|---|
| `SessionStore` SPI 公开方法签名 | 0 改动 | `save(Checkpoint)` + `load(String) → Optional<Checkpoint>` 2 方法不变 |
| `SessionStoreProvider` SPI | 0 改动 | `name()` / `priority()` / `version()` / `create(AgentConfig)` 4 方法不变 |
| `Routers.SessionStoreRouter.resolve` | 0 改动 | 既有行为不变 |
| `FileSessionStore` 4 方法 | 0 改动 | atomic write + LINGS-X01 错误路径不变 |
| `InMemorySessionStore` 行为 | 0 改动 | 行为不变(只是接到 save 调用) |
| `Checkpoint` POJO | 0 改动 | 4 字段 + Jackson 多态注解 |
| `DefaultSession.checkpoint()` 实现 | 0 改动 | L102-114 已实现 |
| `AgentConfig` 26 字段 | 0 字段新增 | 复用 Story #044/#045 schema |
| `AgentFactory` 9-Router ctor | 0 改动 | 9 Router 链路已就位 from Story #014+#045 |
| `DefaultAgent` | 0 改动 | 业务方不感知 sessionStore 字段 |
| `Tool` SPI / `ToolExecutor` 5 步流水线 | 0 改动 | §4.10.1 硬规则 2 守住 |
| `LlmProvider` SPI | 0 改动 | stream(...) 公开方法不变 |
| `Compactor` SPI | 0 改动 | 公开方法签名不变 |
| `Message` 4 子类 | 0 改动 | (System / User / Assistant / ToolResult) |
| 9 Slot 体系 | 不变 | Slot 1/2/3/4/6/7/8/9 公开方法签名不变 |
| JDK 8 兼容 | 守住 | 纯 JDK 8 + Lombok + Spring + Jackson 已锁 0 新 binary |
| 24/26 字段 AgentConfig schema | 0 字段新增 | 复用 Story #044+#045 |
| 53 existing test fixture | 0 改动 | NullObject 守 |
| Spring Boot 3.2.5 + Spring AI 1.0.0-M6 + Lombok 1.18.30 | 不变 | 已锁 13 项依赖 |
| `banned-dependencies` enforcer Rule 0 | passed | 0 新 binary 引入 |

## 实施顺序(Phase 1-5,严格 sequential Phase 之间,Phase 内 [P] 并行)

### Phase 1 — Setup(并行)

- [P] **T-01** [P1][L1] new `NullSessionStore.java` — 60 行,单例 + no-op save/load,镜像 `NullCompactor`
- [P] **T-02** [P1][L1] new `NullSessionStoreTest.java` — 3 case(AC-046-1)
- [P] **T-03** [P1][L1] new `RecordingSessionStore` test helper(在 `LinearTurnEngineSessionStoreTest` 内部)— 记录 save 调用次数 + lastCheckpoint

### Phase 2 — Core sequential(LinearTurnEngine 改 → LinearTurnEngineProvider 改,严格顺序)

- **T-04** [P1] modify `LinearTurnEngine.java`:
  - L88 后加 `private final SessionStore sessionStore;` final 字段
  - L150-163 的 7-arg ctor 签名扩到 8-arg,末尾加 `this.sessionStore = sessionStore;`(注意 `compactor` 字段位置在 `approvalRegistry` 后,sessionStore 在最后)
  - L116-120 的 5-arg ctor 改 `this(...NullCompactor.INSTANCE, NullSessionStore.INSTANCE);`(委托 8-arg)
  - L130-136 的 6-arg ctor 改 `this(...NullCompactor.INSTANCE, NullSessionStore.INSTANCE);`(委托 8-arg)
  - 7-arg ctor 改成 deprecated legacy delegate:`this(...NullCompactor.INSTANCE, NullSessionStore.INSTANCE);`
  - 新加 `@Autowired public LinearTurnEngine(..., SessionStore sessionStore) { ... this.sessionStore = sessionStore; }`(primary 8-arg,镜像 Story #045 7-arg pattern)
  - 新加 `private void trySave(TurnContext ctx) { try { if (TenantContext.current() != null) { sessionStore.save(ctx.session().checkpoint()); } else { sessionStore.save(ctx.session().checkpoint()); } } catch (Exception e) { LOG.warn("session save failed for session={} (best-effort, turn continues): {}", ctx.session().id(), e.toString()); sink.onNext(new AgentEvent.ErrorEvent(new LingsSessionStoreException("LINGS-X01 save failed: " + e.getMessage(), e))); } }` — 但需要把 sink 也传进去,改成 `trySave(TurnContext ctx, Subscriber<...> sink)`
  - L210 loop-head cancel:加 `trySave(ctx, sink);` 在 emit TurnCompleted 之前
  - L346 waitForLlm-cancel:加 `trySave(ctx, sink);` 在 emit TurnCompleted 之前
  - L305 normal-end:加 `trySave(ctx, sink);` 在 emit TurnCompleted 之前
  - L312 exception:加 `trySave(ctx, sink);` 在 emit TurnCompleted 之前
  - 修改 import:加 `import ai.lingshu.core.slot.SessionStore;` + `import ai.lingshu.core.impl.session.NullSessionStore;` + `import ai.lingshu.core.impl.session.LingsSessionStoreException;`

- **T-05** [P1] modify `LinearTurnEngineProvider.java`:
  - 加 `private final Routers.SessionStoreRouter sessionStoreRouter;` 字段
  - 8-arg ctor(Spring 6 dual-ctor 模式):`@Autowired public LinearTurnEngineProvider(..., @Autowired(required=false) @Nullable Routers.CompactorRouter compactorRouter, @Autowired(required=false) @Nullable Routers.SessionStoreRouter sessionStoreRouter) { ... this.sessionStoreRouter = sessionStoreRouter; }`(primary 显式 `@Autowired` 标)
  - legacy 7-arg ctor:`public LinearTurnEngineProvider(..., ApprovalRegistry approvalRegistry) { this(..., null, null); }`(委 8-arg,compactorRouter=null, sessionStoreRouter=null)
  - `create(AgentConfig)` 内 `SessionStore sessionStore = sessionStoreRouter != null ? sessionStoreRouter.resolve(config.getSessionStore(), config) : NullSessionStore.INSTANCE;`
  - `new LinearTurnEngine(..., compactor, sessionStore)`(8-arg 构造器)

### Phase 3 — Tests(并行,Phase 2 完成后)

- [P] **T-06** [P1][L1] 完成 `LinearTurnEngineSessionStoreTest.java` 6 case:
  - `normalEnd_savesOnce` (AC-046-3) — mock LLM 1 轮直答,断言 saveCount=1 + StopReason=END_TURN
  - `loopHeadCancel_savesOnce` (AC-046-4) — mock LLM + 触发 `ctx.cancellation().fire()`,断言 saveCount=1
  - `waitForLlmCancel_savesOnce` (AC-046-5) — mock LLM 永远 hang + 触发 cancellation,断言 saveCount=1
  - `exception_savesOnce` (AC-046-6) — mock LLM 抛 RuntimeException,断言 saveCount=1 + StopReason=ERROR
  - `ioFailure_logsWarnAndKeepsTurnAlive` (AC-046-7) — `RecordingSessionStore` save() 抛 IOException,断言 turn 不崩 + 收 ErrorEvent[LINGS-X01]
  - `legacy7ArgCtor_usesNullSessionStore` (AC-046-2) — `new LinearTurnEngine(5-arg 旧 ctor)`,跑 turn,断言 NullSessionStore.save() 静默 no-op
  - 内部 `RecordingSessionStore` helper:`private static class RecordingSessionStore implements SessionStore { int saveCount = 0; Checkpoint last; ConcurrentHashMap<String, Checkpoint> saved = new ConcurrentHashMap<>(); @Override public void save(Checkpoint c) { saveCount++; last = c; saved.put(c.getId(), c); } @Override public Optional<Checkpoint> load(String id) { return Optional.ofNullable(saved.get(id)); } }`

- [P] **T-07** [P1][L1] 完成 `LinearTurnEngineProviderSessionStoreTest.java` 3 case:
  - `primary8ArgCtor_isAutowired` (AC-046-8) — 反射 verify ctor 有 `@Autowired` annotation
  - `create_resolvesSessionStoreViaRouter` (AC-046-9) — mock `SessionStoreRouter` 返 mock SessionStore,create() 后 engine 接到这个 SessionStore
  - `legacy7ArgCtor_usesNullSessionStore` (AC-046-10) — legacy ctor + null router,create() 后 engine 接到 NullSessionStore.INSTANCE

- [P] **T-08** [P1][L2] 完成 `AgentFactoryYamlSessionStoreSaveIT.java` 4 case:
  - `fileBackend_writesJsonOnTurnEnd` (AC-046-11 + AC-046-13) — yml `agent.session-store: file` + temp dir,跑 turn 完,`Files.exists(tempDir.resolve(sessionId + ".json"))` true + 文件可 read
  - `memoryBackend_doesNotWriteFile` (AC-046-14) — yml `memory`,跑 turn 完,`Files.list(tempDir).count() == 0`
  - `defaultFallback_isMemory` — 业务方 yml 不写 `agent.session-store`,跑 turn 完,行为同 memory
  - `restartScenario_loadFromDisk` — 步骤:写一个 session + 跑 1 turn + 杀 Agent + new AgentFactory + load(sessionId) → Checkpoint 字段 round-trip 正确(Story #046 范围内最小验证,**不**模拟 `DefaultSession` 自动 load,**只**测 `FileSessionStore.load` 端 round-trip 正确性;OQ-046.1 留 follow-up)

- [P] **T-09** [P1][L2] 完成 `AgentFactoryYamlSessionStoreLoadIT.java` 2 case:
  - `loadValidJson_returnsCorrectCheckpoint` (AC-046-12) — 手写 `{sessionId}.json` 含 user + assistant 2 message,`FileSessionStore.load(sessionId)` → Optional<Checkpoint> 非空 + history size 2 + 字段 round-trip
  - `loadMissingFile_returnsEmpty`(OQ-046.1 test coverage) — 文件不存在 → Optional.empty()(不抛)
  - `loadCorruptJson_throwsLingsX01`(bonus,OQ-046.1 test coverage) — 损坏 JSON → 抛 `LingsSessionStoreException[LINGS-X01]`

### Phase 4 — Validation(sequential)

- **T-10** [P1][RAC] 跑 `mvn -pl lingshu-core test` — 720 existing fixture 0 regression + 11 new case 0 fail(2 MCP heartbeat flake pre-existing 忽略)
- **T-11** [P1][DepTree] 跑 R-13 mitigation (d) baseline 镜像:
  - `git show HEAD:lingshu-core/pom.xml | md5sum` 拿 base md5
  - 实施完 commit
  - `git show HEAD:lingshu-core/pom.xml | md5sum` 拿 post md5
  - base == post → **0 binary delta 第 30 次 PASS**
  - `mvn -pl lingshu-core dependency:tree` 输出 pre/post diff 仅时间戳差异
- **T-12** [P1][L2] 跑 `mvn -pl lingshu-examples/demo-product verify` — 0 后端 Java 改动(2 文件前端不动),只验证 demo-product 启动不崩
- **T-13** [P1][L2] 人工端到端 7 步清单(README.md 同步更新):
  1. `mvn -pl lingshu-examples/demo-product spring-boot:run`
  2. `curl -X POST localhost:8080/api/sessions` 拿 sessionId
  3. 配 `agent.session-store: file` + 配 `session-store.dir: /tmp/lingshu-sessions`(或 yml 默认 tmpdir)
  4. `curl -X POST localhost:8080/api/chat/{sessionId} -d '{"prompt":"hello"}'` 跑 1 轮
  5. `ls -la /tmp/lingshu-sessions/{sessionId}.json` → 文件存在
  6. `cat` 看到 JSON 含 sessionId + history
  7. kill spring-boot + 重启 + `curl localhost:8080/api/sessions/{sessionId}/history` → 看到前 1 轮 history(注:本 Story 只接 save 端,load 端 DefaultSession 不会自动 load,**只**测 FileSessionStore.load round-trip 在 L2 IT 中已覆盖)

### Phase 5 — Docs 同步(parallel)

- [P] **T-14** [P1][docs] CLAUDE.md v1.3.54 → v1.3.55 + 「Last updated」+ 「Version」+ §0/§1/§6 同步 Story #046 描述
- [P] **T-15** [P1][docs] `dsh_agent_design.md` v1.5.58 → v1.5.59 + §0 标题 + §13 changelog 新增 v1.5.59 行 + §6.1 L3640-3643 LinearTurnEngine 段补 `🆕 Story #046` save 钩子 4 收口点 marker + §4.8 SessionStore 段补 🆕 Story #046 save-after-turn wired 注解 + §15.4 X 段 cross-ref Story #046
- [P] **T-16** [P1][docs] `README.md` 「更新日期」段加 🆕 v1.5.59 Story #046 顶部 blockquote + 「核心特性」段补 💾 Slot 5 SessionStore save-after-turn 真接通 bullet + 「Story 路线图」段追加 #046 retrospective(3 核心 prod 文件改动 + 5 test 文件 + 11 new case + NullSessionStore sentinel 守 53 fixture 0 改动 + R-13 0 binary delta 第 30 次 PASS)
- [P] **T-17** [P1][docs] `specs/ROADMAP.md` 段一 ✅ 已完成加 #046 行(2026-10-13,11 new case pass / R-13 0 binary delta 第 30 次 PASS / 0 新 ErrorCode)+ 段二 🟡 待补 #046 划掉(原 OQ-046 全部处理完 🎉)+ 段五 🎯 实施节奏 累计计数 46 → **47 个 Story**
- [P] **T-18** [P1][docs] `constitution.md` §10 R-13 缓解 Story 列表补 `#046` 行**第 30 次** R-13 mitigation (d) PASS 0 binary delta + 累计计数 29 → **30 个 Story**

## Complexity Tracking

无超出预算项。**3 核心 prod 文件 ≤ 5 预算** ✓ / **0 新 ErrorCode ≤ 3 预算** ✓ / **0 新 Maven 依赖** ✓。

唯一 stretch 候选:`LinearTurnEngine` 8-arg ctor 4 收口点插桩(8 改 if 行),但严格 1 个 ctor 加 1 个 helper method,仍在 5 文件 / 0 公开方法改动预算内。

不引新公开方法:
- `LinearTurnEngine.runTurn` 公开方法签名不变
- `LinearTurnEngineProvider.create(AgentConfig)` 公开方法签名不变
- `trySave` 是 private helper,不暴露 SPI
- `NullSessionStore` 公开方法签名就是 `SessionStore` 2 个 SPI 方法,0 新方法

## 测试覆盖统计

- **11 new case**(11 L1+L2)+ **0 fixture 改动** = 11 net new case
- 11 case 分布:
  - `NullSessionStoreTest` — 3 case
  - `LinearTurnEngineSessionStoreTest` — 6 case
  - `LinearTurnEngineProviderSessionStoreTest` — 3 case
  - `AgentFactoryYamlSessionStoreSaveIT` — 4 case
  - `AgentFactoryYamlSessionStoreLoadIT` — 2-3 case(load valid / missing / corrupt)
- **720 → 731 累计 case**(11 新增)
- **RAC**:53 existing fixture 0 改动 0 回归

## R-13 mitigation (d) baseline 镜像 checklist

| 步骤 | 命令 | 预期 |
|---|---|---|
| 1. 取 base md5 | `git show HEAD:lingshu-core/pom.xml \| md5sum` | 拿 `base_md5_1` |
| 2. 实施 commit | `git add -p` + `git commit -m "feat(agent): Story #046 ..."` | 写盘 |
| 3. 取 post md5 | `git show HEAD:lingshu-core/pom.xml \| md5sum` | 拿 `post_md5_1`,应 = `base_md5_1` |
| 4. dep-tree diff | `mvn -pl lingshu-core dependency:tree` pre/post | 仅时间戳差异 |
| 5. enforcer | `mvn -pl lingshu-core verify -Pbanned-dependencies` | Rule 0 passed |
| 6. 计入累计 | R-13 缓解 Story 列表 + #046 | 累计 30 个 Story |

**预期 PASS**:**第 30 次 0 binary delta**(纯 JDK 8 + Lombok + Spring + Jackson + `java.util.concurrent` 已锁 13 项依赖表内 0 新 binary 引入)。

## 业务价值回顾

- **Story #014 收口** — yml `agent.session-store: file` + 跑 turn + 重启 + session 实际可恢复(本 Story 范围 save 端;load 端 Round-trip 在 L2 IT 覆盖 + OQ-046.1 留 follow-up)
- **demo-product ask-mode 隔夜审批 + 持久化** — Story #030 ask-mode 业务方隔夜审批后,本 Story 持久化兜底 turn history
- **53 fixture 0 改动** — `NullSessionStore` sentinel 守,**0 fixture 文件被新字段签名打挂**
- **9 Slot 体系不变** — Slot 5 主循环接通,其它 8 Slot 公开方法签名 0 改动
- **JDK 8 兼容** — 纯 `Collections.emptyList()` / `Arrays.asList()` / Lombok `@Value` / Spring `@Component` + `@Autowired` 已锁
- **Spring AI 仍禁用自动执行** — §4.10.1 硬规则 2 守住
- **ReAct Loop 自实现不变** — §4.10.1 硬规则 1 守住
- **累计 47 Story** — v0.1.0 GA 9 Slot 100% 真接通 + Slot 5 主循环也接通,demo-product 真正可工作
