# Tasks: Story #009b a2a-inprocess-transport

**Input**: Design documents from `/specs/009b-a2a-inprocess-transport/`
- spec.md(4 User Stories US1—US4 + 12 Edge Cases + FR-001—FR-015 + NFR-001—NFR-010)
- plan.md(9 源文件改动 + 5 测试文件 + 20 case + 7 步实施顺序 + R-13 mitigation (d) 5 步)
- data-model.md(4 新增类型 + 1 ErrorCode + 3 修改类型 + 5 复用类型)
- contracts/a2a-inprocess-transport.md(4 契约 ID;2 新增 + 2 影响 + 0 修改)
- quickstart.md(7 验证场景 — AC-10 关联 + US1—US4 + EC-1—EC-12 + R-13 mitigation (d))
- checklists/requirements.md(12 章节质量门禁)

**Prerequisites**:
- spec.md ✅(本目录)
- plan.md ✅(本目录)
- Story #001—#009 + #009a 全部 merged(提供 `SlotRouter<P, T>` / `Providers.A2aTransportProvider` / `A2aTransportRouter`[#009a] / `AgentCardCache`[#009a] / `AgentConfig.A2a host+port+grpcTarget+cardTtl` / `LocalAgentCardGenerator` / `A2aServer` 等基础设施)

**Tests**: Required per FR-001—FR-015 + 10 NFR + 12 Edge Cases。**20 case** = 14 L1 Unit + 6 L2 Slice。

**Constitution**: v1.0 — §1 #8 Slot 选用 / §1 #9 Plugin 发现 / §1 #11 默认实现位置 / §2 13 依赖锁定(R-13 **+0 新依赖**)/ §4 错误码约定(1 新增 ErrorCode LINGS-S08)/ §5 7 层金字塔(20 case 覆盖)/ §10 R-13 强度最弱(0 binary delta)

---

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel(different files, no dependencies)
- **[Story]**: Which user story this task belongs to(US1 / US2 / US3 / US4)
- Include exact file paths in descriptions

---

## Phase 1: Setup(Shared Infrastructure)

**Purpose**: Verify environment + capture Story #009a dep baseline for R-13 diff

- [ ] T001 Verify JDK 17+(实际跑需 JDK 17,编译目标 1.8)+ Maven 3.6.3+ via `mvn -v` + `java -version`
- [ ] T002 Capture Story #009a dependency baseline: `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009b-pre.txt`(期望含 #009a 已落地的 grpc-stub + protobuf-java + os-maven-plugin + protobuf-maven-plugin,**本 Story 应完全一致**)
- [ ] T003 Verify current branch is `story-009b-a2a-inprocess-transport` via `git branch --show-current`(从 main 拉新分支 `git checkout -b story-009b-a2a-inprocess-transport` 已在前面 turn 完成)
- [ ] T004 Validate baseline: `mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client test` exits 0(Story #001—#009 + #009a tests all green — pre-implementation sanity,已在前面 turn 实测 192 + 23 case 全过)

**Checkpoint**: Setup ready — code modifications can begin。

---

## Phase 2: Foundational — `InProcessA2aRegistry` 单例 + `LocalAgentCardGenerator.toMap` + `A2aServer` 2 钩子(US2 + US3 基础 + 所有 AS 阻塞依赖)

**Purpose**: Establish in-process infrastructure **before** any InProcessA2aTransport wiring

**⚠️ CRITICAL**: 后续所有 US(US1 / US2 / US3 / US4 + EC-1—EC-12)都依赖 `InProcessA2aRegistry` 单例 + `LocalAgentCardGenerator.toMap` + `A2aServer.registerInProcess`/`unregisterInProcess` 钩子,此 phase 必须先完成

- [ ] T005 [P0] [US2] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aRegistry.java`:
  - `public final class InProcessA2aRegistry`
  - 静态字段:`private static final InProcessA2aRegistry INSTANCE = new InProcessA2aRegistry()`
  - 私有构造器:`private InProcessA2aRegistry() {}`
  - 静态工厂:`public static InProcessA2aRegistry getInstance() { return INSTANCE; }`
  - 内部字段:`private final ConcurrentHashMap<String, Map<String, Object>> store = new ConcurrentHashMap<>();`
  - 7 方法 `put` / `get` / `remove` / `contains` / `names` / `size` / `clear`(Javadoc 完整描述每个方法的语义 + 线程安全保证 + null/边界处理)
  - `put(agentName, card)`:`Objects.requireNonNull(agentName, "agentName"); Objects.requireNonNull(card, "card"); if (store.containsKey(agentName)) LOG.warn("registry.put({}) already exists, overwriting", agentName); store.put(agentName, card);`
  - `get(agentName)`:miss 返 null;hit 返 `Collections.unmodifiableMap(new LinkedHashMap<>(store.get(agentName)))`(defensive copy)
  - `names()`:返 `Collections.unmodifiableSet(store.keySet())`(JDK 8 keySet 包一层)
  - 其他方法直接 delegate 到 ConcurrentHashMap

- [ ] T006 [P0] [US3] Modify `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/LocalAgentCardGenerator.java`:
  - 在 `toJson(AgentCard)` 静态方法之后追加 `toMap(AgentCard) → Map<String, Object>` 静态方法
  - 用 LinkedHashMap 手动构造(11 字段:name / description / version / skills / capabilities / defaultInputModes / defaultOutputModes / securitySchemes / security / provider / documentationUrl / iconUrl)
  - 返回 `Collections.unmodifiableMap(map)`
  - Javadoc 标注被 `A2aServer.registerInProcess()` 调用
  - 拒 null:throw `IllegalArgumentException("card must not be null")`

- [ ] T007 [P0] [US3] Modify `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java`:
  - 加 import `ai.lingshu.core.a2a.client.InProcessA2aRegistry`(注:registry 已从 a2a-client 移到 lingshu-core,Fallback 见 spec.md FR-011)
  - 加 2 个 private 方法 `registerInProcess()` + `unregisterInProcess()`(详见 data-model.md MD-02)
  - `registerInProcess()`:读 `cfg.getIdentity().getName()`,null/empty 直接 return;调 `InProcessA2aRegistry.getInstance().put(name, LocalAgentCardGenerator.toMap(cardRef.get()))` + log info `[A2aServer] registered in-process card: {} -> http://{}:{}`
  - `unregisterInProcess()`:读 `cfg.getIdentity().getName()`,null/empty 直接 return;调 `registry.remove(name)` + log info `[A2aServer] unregistered in-process card: {}`
  - `start()` 末尾(L137 `LOG.info("[A2aServer] listening on...")` 之前)插入 `registerInProcess();`
  - `stop()` 开头(`if (server == null) return;` 之后,`int port = actualPort;` 之前)插入 `unregisterInProcess();`

- [ ] T008 [P0] [US3] Modify `lingshu-a2a-server/pom.xml`:
  - 在 `<dependencies>` 内追加 `<dependency><groupId>ai.lingshu</groupId><artifactId>lingshu-a2a-client</artifactId><version>${revision}</version></dependency>`
  - 上方加 Javadoc 注释:`Module dependency direction reversal: #009b requires a2a-server → a2a-client (A2aServer.registerInProcess uses InProcessA2aRegistry). Maven 3.6.3+ reactor handles bidirectional deps; if Cycle error occurs, fallback: move InProcessA2aRegistry to lingshu-core module.`

- [ ] T009 [P0] 验证 Phase 2: `mvn -pl lingshu-core,lingshu-a2a-server,lingshu-a2a-client compile` exit 0(双向依赖 Maven 解析成功)

**Checkpoint**: Phase 2 ready — `InProcessA2aRegistry` 单例可调,`A2aServer.start()` 自动 register,`A2aServer.stop()` 自动 unregister。

---

## Phase 3: User Story 1 + 4 — `InProcessA2aTransport` 3 件套 + Provider

**Purpose**: Implement 3-piece mode (Transport class + Provider class + AutoConfiguration)

- [ ] T010 [P0] [US1] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransport.java`:
  - `@Component public class InProcessA2aTransport implements A2aTransport`
  - 字段:`private final InProcessA2aRegistry registry;` + `private final AgentCardCache cardCache;` + `private static final Logger log = LoggerFactory.getLogger(InProcessA2aTransport.class);`
  - 构造器:`public InProcessA2aTransport(InProcessA2aRegistry registry, AgentCardCache cardCache) { this.registry = registry; this.cardCache = cardCache; }`
  - `fetchCard(agentName)`:
    1. `Map<String, Object> cached = cardCache.get(agentName);`
    2. `if (cached != null) { log.debug("fetchCard({}) cache hit", agentName); return cached; }`
    3. `Map<String, Object> card = registry.get(agentName);`
    4. `if (card == null) { cardCache.putNegative(agentName); throw new LingshuException("LINGS-S08", "No in-process A2A server registered for agentName='" + agentName + "'. Available: " + registry.names(), "Ensure the remote Agent has been started (its A2aServer.start() calls registry.put()) or change 'agent.a2aTransport' to 'grpc-1.0.0' / 'http-jsonrpc-1.0.0' for cross-JVM transport"); }`
    5. `cardCache.put(agentName, card);` + return card
  - `submit/get/cancel/subscribe` 4 方法:`throw new UnsupportedOperationException("InProcess transport is fetchCard-only in #009b; use http-jsonrpc for task RPC");`
  - Javadoc 完整描述 dsh §5.6.3.2 L3245-3268 锚定 + 功能范围限定说明 + 与 #009a GrpcA2aTransport 的对比

- [ ] T011 [P0] [US1] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportProvider.java`:
  - `@Component public class InProcessA2aTransportProvider implements Providers.A2aTransportProvider`
  - `name()`:return `"in-process-1.0.0"`(Javadoc:禁止与 `"grpc-1.0.0"` / 未来 `"http-jsonrpc-1.0.0"` 冲突)
  - `priority()`:return 10
  - `version()`:return `"1.0.0"`
  - `create(cfg)`:
    ```java
    Duration cardTtl = resolveCardTtl(cfg);
    log.info("[A2aTransport] creating InProcessA2aTransport: registry=InProcessA2aRegistry.getInstance() cardTtl={}", cardTtl);
    return new InProcessA2aTransport(InProcessA2aRegistry.getInstance(), new AgentCardCache(cardTtl));
    ```
  - 私有方法 `resolveCardTtl(cfg)`:读 `cfg.getA2a().getCardTtl()`,null 时 fallback `Duration.ofMinutes(5)`(兼容 #009a 之前的 AgentConfig.A2a)

- [ ] T012 [P0] [US4] Create `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfiguration.java`:
  - `@AutoConfiguration public class InProcessA2aTransportAutoConfiguration`
  - 单方法:`@Bean(name = "a2aTransportProvider_in-process-1.0.0") public A2aTransportProvider inProcessA2aTransportProvider() { return new InProcessA2aTransportProvider(); }`

- [ ] T013 [P0] [US4] Modify `lingshu-a2a-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:
  - **追加**第二行(不覆盖第一行):`ai.lingshu.a2a.client.InProcessA2aTransportAutoConfiguration`
  - 文件末必须有 trailing newline

- [ ] T014 [P0] 验证 Phase 3: `mvn -pl lingshu-a2a-client compile` exit 0 + `mvn -pl lingshu-a2a-client,lingshu-a2a-server test`(已有 192 + 23 case 不应 regress)

**Checkpoint**: Phase 3 ready — `InProcessA2aTransport` 3 件套编译过 + 已有测试不 regress。

---

## Phase 4: Tests — 5 测试文件 + 20 case

**Purpose**: L1 Unit + L2 Slice 验证所有 US + EC

- [ ] T015 [P0] [US2] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aRegistryTest.java`:
  - L1 Unit, 6 case:
    1. `testSingletonIdentity` — `getInstance() == getInstance()`(EC-10)
    2. `testPutGetContains` — put 1 个 + get + contains + size(US-2 AC-2)
    3. `testRemoveAndClear` — remove 返 true / 不存在返 false + clear 清空(US-2 AC-4 + AC-5 + EC-5)
    4. `testNamesImmutable` — names() 返回不可变 Set,外部 mutation 抛 UnsupportedOperationException(NFR-005)
    5. `testConcurrentPut10000` — 100 线程 × 100 key,最终 size 10000(EC-4 + NFR-005)
    6. `testNullCardRejected` — put("alice", null) 抛 IllegalArgumentException(EC-2 + FR-006)
  - `@BeforeEach clear()` 保证测试隔离

- [ ] T016 [P0] [US1] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportTest.java`:
  - L1 Unit, 5 case:
    1. `testFetchCardHappyPath` — registry put + fetchCard 返 Map(VS-1 + US-1 AC-2)
    2. `testFetchCardHitsCache` — 第二次 fetchCard 走 cache(VS-2 + EC-12 + NFR-001)
    3. `testFetchCardMissThrowsLingsS08` — registry 无 entry 抛 LINGS-S08 + 负缓存(VS-3 + EC-1 + EC-3 + US-1 AC-3)
    4. `testSubmitGetCancelSubscribeUnsupported` — 4 方法都抛 UnsupportedOperationException(EC-6 + FR-014)
    5. `testCardCachePutOnMiss` — miss 走 registry + put 进 cache + 第二次走 cache(NFR-001 + US-1 AC-4)
  - `@BeforeEach clear()` + `@BeforeEach new AgentCardCache(Duration.ofMinutes(5))`

- [ ] T017 [P0] [US4] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportProviderTest.java`:
  - L1 Unit, 3 case:
    1. `testCreateHappyPath` — `create(cfg)` 返 InProcessA2aTransport 实例,持有 InProcessA2aRegistry.getInstance() 单例引用(US-4 AC-2 + FR-003)
    2. `testNameVersionPriority` — `name()="in-process-1.0.0"` + `priority()=10` + `version()="1.0.0"`(FR-003)
    3. `testCardTtlFromConfig` — `cfg.getA2a().getCardTtl()=Duration.ofMinutes(10)` 时 create 返回的 InProcessA2aTransport 持有 cardTtl=10min 的 AgentCardCache(FR-003 + FR-008)

- [ ] T018 [P0] [US4] Create `lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/InProcessA2aTransportAutoConfigurationTest.java`:
  - L2 Slice(Spring `@SpringBootTest`),3 case:
    1. `testMultiProviderCoexistence` — `router.available()` 包含 `"grpc-1.0.0"` + `"in-process-1.0.0"`(VS-6 + US-4 AC-1)
    2. `testResolveInProcess` — `router.resolve("in-process-1.0.0", cfg)` 返 InProcessA2aTransport(US-4 AC-2)
    3. `testUnknownNameThrows` — `router.resolve("unknown", cfg)` 抛 IllegalArgumentException 含 `Unknown A2aTransportRouter 'unknown'. Available: ...`(US-4 AC-3)
  - classes = `{GrpcA2aTransportAutoConfiguration.class, InProcessA2aTransportAutoConfiguration.class, A2aTransportRouter.class}`

- [ ] T019 [P0] [US3] Create `lingshu-a2a-server/src/test/java/ai/lingshu/a2a/server/A2aServerInProcessRegistrationTest.java`:
  - L2 Slice(Spring `@SpringBootTest`),3 case:
    1. `testStartRegistersAndStopUnregisters` — start → `registry.contains(identity.name) == true` + `get` 返 Map;stop → `contains == false`(VS-5 + US-3 AC-1 + AC-2 + EC-7 + EC-8)
    2. `testEmptyIdentityNameStartFails` — yml `identity.name=""` → start 抛 `LINGS-T02` + registry 不污染(US-3 AC-3)
    3. `testRegistryPutOverwrites` — 同 identity.name 启动 2 次 A2aServer → 后者覆盖前者 + log warn(EC-9)
  - `@BeforeEach clear()` + `@SpringBootTest` + `@TestPropertySource` 配 `agent.identity.name=test-alice-coding` + `agent.a2a.port=0`

- [ ] T020 [P0] 验证 Phase 4: `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core test` 期望 192 + 23 + 20 = 235 case 全过

**Checkpoint**: Phase 4 ready — 所有 20 case 测试通过 + 已有测试不 regress。

---

## Phase 5: AC-10 关联验证 + 启动日志验证

**Purpose**: End-to-end verification of A2A client 3-Provider coexistence (grpc-1.0.0 + in-process-1.0.0)

- [ ] T021 [P1] [US4] 启动日志验证:`mvn -pl lingshu-examples exec:java -Dexec.mainClass="ai.lingshu.examples.Main" -Dexec.args="--config src/main/resources/application.yml"`(可选,若有 demo)
  - 期望日志包含:`[A2aTransport] resolved 2 provider(s) [contract v1.0.0]:` + `✓ grpc-1.0.0 v1.0.0 -> GrpcA2aTransportProvider [priority=10]` + `✓ in-process-1.0.0 v1.0.0 -> InProcessA2aTransportProvider [priority=10]`
  - 若无 demo,跳过此 task(本 Story 不强制)

- [ ] T022 [P1] [US1] 单元测试场景下 in-process < 1ms 验证(`InProcessA2aTransportTest` 加 1 个 `@Timeout(value = 100, unit = TimeUnit.MILLISECONDS)` 测试,确保 fetchCard 在 100ms 内完成 —— in-process 应 < 1ms 但给 100ms 宽松边界)

**Checkpoint**: Phase 5 ready — 启动日志 + 性能验证通过(若有 demo)。

---

## Phase 6: R-13 mitigation (d) baseline 镜像

**Purpose**: Verify 0 binary delta + enforcer pass

- [ ] T023 [P0] 跑 `mvn -pl lingshu-a2a-client dependency:tree -Dverbose=true > /tmp/deps-009b-post.txt`
- [ ] T024 [P0] 跑 `diff /tmp/deps-009b-pre.txt /tmp/deps-009b-post.txt`,期望**无输出**(完全一致)
- [ ] T025 [P0] 跑 `mvn -pl lingshu-a2a-client,lingshu-a2a-server,lingshu-core verify`,期望 BUILD SUCCESS + banned-dependencies 规则不 fail

**Checkpoint**: Phase 6 ready — R-13 mitigation (d) baseline 镜像通过。

---

## Phase 7: Doc Sync + Commit + PR

**Purpose**: Sync docs + commit + PR + CI

- [ ] T026 [P1] Modify `README.md`(若有 §5.6 段)加 `in-process-1.0.0` 一行 + 启动日志示例更新(2 → 2 providers)
- [ ] T027 [P1] Modify `dsh_agent_design.md` §13 changelog 加 v1.5.37 行:Story #009b 完成 + InProcessA2aTransport 3 件套 + 0 binary delta + InProcessA2aRegistry 单例
- [ ] T028 [P1] Modify `constitution.md` §10 R-13 风险状态:本 Story 0 binary delta → R-13 强度不变;新增 R-14 备注:in-process 协议为 in-memory 引用,不受 R-14 A2A 协议兼容性约束
- [ ] T029 [P0] `git add -A && git commit -m "feat(a2a-client): Story #009b a2a-inprocess-transport — InProcessA2aTransport 3 件套 + InProcessA2aRegistry 单例 + R-13 mitigation (d) baseline 镜像"`
  - Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
- [ ] T030 [P0] `git push origin story-009b-a2a-inprocess-transport`
- [ ] T031 [P0] `gh pr create --title "feat(a2a-client): Story #009b a2a-inprocess-transport — InProcessA2aTransport 3 件套 + InProcessA2aRegistry 单例 + 0 binary delta" --body "..."`(贴 spec.md + plan.md + tasks.md + AC-10 验证输出 + R-13 dep-tree diff 到 PR body)
- [ ] T032 [P0] 等 CI 全过后(若需要)手动 merge(用户授权后)

**Checkpoint**: Phase 7 ready — PR 合并 + 文档同步 + Story #009b 完成。

---

## 关键不变项(不引入新决策)

- `A2aTransport` 5 方法契约不变
- `A2aTransportRouter` 行为不变(#009a 已落地)
- `AgentCardCache` 行为不变(#009a 已落地)
- `AgentConfig.A2a` 字段不变(#009a 已扩 grpcTarget/cardTtl)
- `SlotRouter<P, T>` 父类不变
- `GrpcA2aTransport` / `GrpcA2aTransportProvider` / `GrpcA2aTransportAutoConfiguration` 不变(#009a)
- `LocalAgentCardGenerator.generate()` 不变(只**新增** `toMap()` 静态方法)
- `A2aServer.start()` / `stop()` 主流程不变(只**追加** 2 钩子)
- JDK 8 only:不用 `var` / `record` / `sealed`,用 `Collections.unmodifiableMap` + `ConcurrentHashMap` + `LinkedHashMap`

---

## Story 边界检查(CLAUDE.md §11 #4)

| 维度 | 预算 | 实际 | 状态 |
|---|---|---|---|
| 核心文件改动 | ≤ 5 | 4 新增(`InProcessA2aTransport` / `Provider` / `AutoConfiguration` / `Registry`)| ✅ |
| 核心文件修改 | 不计入边界 | 3 类(`A2aServer` 加 2 钩子 + `LocalAgentCardGenerator` 加 1 方法 + `pom.xml` 加 1 依赖 + `imports` 追加 1 行 + 可能 `ErrorCodes` 加 1 常量)| 边界内(微小改动)|
| 测试文件 | 不计入边界 | 5(20 case)| — |
| ErrorCode 引入 | ≤ 3 | 1(`LINGS-S08 A2A_INPROCESS_REGISTRY_EMPTY`)| ✅ |
| 新 Maven 依赖 | R-13 mitigation (d) | **0** | ✅ 强度最弱 |
| 改动模块 | 主要 lingshu-a2a-client + a2a-server(加 2 钩子 + toMap) | ✓ | ✅ |

---

## 依赖图

```
Phase 1 (Setup)
   ↓
Phase 2 (Foundational) ─── 阻塞所有 US
   ↓
Phase 3 (US1 + US4 — 3 件套 + Provider) ── 可与 Phase 4 部分并行
   ↓
Phase 4 (Tests — 5 文件 + 20 case) ── 依赖 Phase 3
   ↓
Phase 5 (AC-10 验证 + 启动日志)
   ↓
Phase 6 (R-13 mitigation (d) baseline 镜像)
   ↓
Phase 7 (Doc Sync + Commit + PR)
```

---

## 任务数统计

| Phase | 任务数 | 关键产出 |
|---|---|---|
| Phase 1 Setup | 4 | R-13 baseline 镜像 + 环境验证 |
| Phase 2 Foundational | 5 | InProcessA2aRegistry + LocalAgentCardGenerator.toMap + A2aServer 2 钩子 + pom.xml |
| Phase 3 US1 + US4 | 5 | InProcessA2aTransport 3 件套 + Provider + AutoConfiguration + imports |
| Phase 4 Tests | 6 | 5 测试文件 + 20 case |
| Phase 5 AC-10 | 2 | 启动日志 + 性能验证 |
| Phase 6 R-13 | 3 | 0 binary delta 验证 + enforcer |
| Phase 7 Doc + PR | 7 | 文档同步 + commit + push + PR + merge |
| **合计** | **32 tasks** | — |

---

## 总结

**全部 32 task 勾完** 即 Story #009b 完成 ✅
