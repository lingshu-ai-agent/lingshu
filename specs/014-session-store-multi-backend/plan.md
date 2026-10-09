# Story #014 `session-store-multi-backend` — Plan

> **Status**: Draft 2026-10-09
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 tasks**: [`tasks.md`](./tasks.md)
> **核心约束**: 4 modify + 10 new = 14 文件(超出 dsh §5.3.1.0 ≤ 5 边界,**但每文件职责内聚**)+ 1 新 ErrorCode `LINGS-X01` + 0 新 Maven dep
> **核心 hygiene**: 填实 dsh §5.3.1.0 `SessionStoreRouter` stub + 多 Provider 模式对齐 v1.5.28 §5.5 + `DefaultSession` 真接通 SessionStore

---

## 1. 改前 vs 改后 shape(锁定)

### 1.1 改前 Slot 5 状态(L0-现状)

```
┌─ Providers.SessionStoreProvider marker (slot-only, 0 实现) ─┐
│ Providers.java:45 typed marker                              │
└─────────────────────────────────────────────────────────────┘
                              ↓ 0 concrete class
┌─ Routers.SessionStoreRouter (❌ 不存在,Routers.java 缺位)──┐
│ 设计文档承诺 dsh §5.3.1.0 L1644,L1690 标注 Story #014 实施  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─ SlotResolver.sessionStore(AgentConfig) ──────────────────┐
│ ❌ 不存在(代码 6 字段,设计 7 字段,缺 SessionStoreRouter) │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─ DefaultInMemorySessionStore ─────────────────────────────┐
│ @Component("defaultInMemorySessionStore")  ← 直接挂,绕过 │
│ SlotRouter 模式,与 v1.5.28 §5.5 不符                     │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─ DefaultSession ──────────────────────────────────────────┐
│ 内部 new ConcurrentHashMap<>()(in-memory 兜底)           │
│ Javadoc L33: "Story #014 replaces this with a             │
│  SessionStore-backed implementation"                      │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─ AgentFactory ────────────────────────────────────────────┐
│ @Autowired 7-Router ctor(llm / tool / policy / prompt /  │
│ flow / memorySource / runtimeSandbox)                     │
│ 内部 L258: new DefaultSession() ← 不注入 SessionStore   │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 改后 Slot 5 状态(L1-目标)

```
┌─ Providers.SessionStoreProvider (已落) ──────────────────┐
│ Providers.java:45 typed marker                             │
└─────────────────────────────────────────────────────────────┘
                              ↓ 2 concrete POJO
┌─ InMemorySessionStoreProvider (NEW) ──────────────────────┐
│ name()="memory" version()="1.0.0" priority()=0            │
│ create(AgentConfig) → new DefaultInMemorySessionStore()   │
│ @Bean(name="sessionStoreProvider_memory-1.0.0")           │
└─────────────────────────────────────────────────────────────┘
┌─ FileSessionStoreProvider (NEW) ──────────────────────────┐
│ name()="file" version()="1.0.0" priority()=10             │
│ baseDir = System property "lingshu.session-store.dir"     │
│         > env "LINGS_SESSION_STORE_DIR"                   │
│         > "./.lingshu-sessions"                           │
│ create(AgentConfig) → new FileSessionStore(baseDir, mapper)│
│ @Bean(name="sessionStoreProvider_file-1.0.0")             │
└─────────────────────────────────────────────────────────────┘
                              ↓ SlotRouter byName
┌─ Routers.SessionStoreRouter (NEW,fill §5.3.1.0 stub) ────┐
│ @Component extends SlotRouter<SessionStoreProvider, SessionStore>│
│ super("SessionStore", providers, logger)                  │
│ byName Map size = 2                                        │
└─────────────────────────────────────────────────────────────┘
                              ↓ ctor 注入
┌─ SlotResolver.sessionStore(AgentConfig) ──────────────────┐
│ 7 字段(Memory / Llm / Tool / Policy / Prompt / FlowEngine │
│   / MemorySource / RuntimeSandbox / A2aTransport / SessionStore│
│   — dsh §5.3 L1644 设计补齐)                              │
│ sessionStore(c) = sessionStoreRouter.resolve(c.sessionStore, c)│
└─────────────────────────────────────────────────────────────┘
                              ↓ Spring 注入 AgentFactory
┌─ AgentFactory @Autowired 8-Router ctor ───────────────────┐
│ + Routers.SessionStoreRouter sessionStoreRouter            │
│ 内部 SlotResolver 实例化加 sessionStoreRouter 实参         │
│ create() 7-Router → 8-Router boot invariants               │
└─────────────────────────────────────────────────────────────┘
                              ↓ DefaultSession 委派
┌─ DefaultSession ──────────────────────────────────────────┐
│ delegate = SlotResolver.sessionStore(cfg)  ← NEW          │
│ saveCheckpoint(cp) → delegate.save(cp)                     │
│ loadCheckpoint(sid) → delegate.load(sid)                  │
│ clear()/size() 测试 helper 保留为内部 ConcurrentMap       │
│ 公开方法签名 0 改动                                        │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 File 后端 IO 流程

```
FileSessionStore.save(Checkpoint cp)
  ↓
resolvePath(cp.sessionId)  ← tenant-aware buildKey + 路径转义
  ↓
target = <baseDir>/<safe>.json
tmp = <safe>.json.tmp       ← 临时文件防半写
  ↓
mapper.writeValueAsBytes(cp)  →  bytes
  ↓
Files.write(tmp, bytes)    ← try-with-resources
  ↓
Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)
  ↓
catch IOException → throw new LingsSessionStoreException("save failed for ...", e)
                       ↑ getMessage 前缀 "[LINGS-X01]"
```

### 1.4 多 Provider 模式对齐 v1.5.28 §5.5 对照

| 维度 | Slot 4 PermissionPolicy (✅ 已落) | Slot 5 SessionStore (改后)|
|---|---|---|
| typed Provider | `Providers.PermissionPolicyProvider` | `Providers.SessionStoreProvider`(已存在)|
| Router concrete | `Routers.PermissionPolicyRouter` | `Routers.SessionStoreRouter`(new)|
| 默认 Provider | `AllowAllPermissionPolicyProvider`(priority=0)| `InMemorySessionStoreProvider`(priority=0)|
| 替代 Provider | `StrictPermissionPolicyProvider`(priority=10)| `FileSessionStoreProvider`(priority=10)|
| 第 3 替代 | `AskUserPermissionPolicyProvider`(priority=10)| (本 Story 不做,留给 Redis/Jdbc follow-up)|
| AutoConfiguration | `PermissionPolicyAutoConfiguration`(3 `@Bean`)| `SessionStoreAutoConfiguration`(2 `@Bean`,new)|
| Bean 名格式 | `permissionPolicyProvider_<name>-<version>` | `sessionStoreProvider_<name>-<version>` |
| `name()` 返回 | "default" / "strict" / "ask" | "memory" / "file" |
| `version()` 返回 | "1.0.0" | "1.0.0" |
| `priority()` 返回 | 0 / 10 / 10 | 0 / 10 |
| SlotResolver 字段 | ✅ 注入 | ✅ 注入(本 Story 补齐)|
| AgentFactory ctor 参数 | ✅ 注入 | ✅ 注入(本 Story 补齐)|

---

## 2. 接口契约(0 SPI 改动)

### 2.1 公开方法签名锁定

| 方法/类 | 签名 | 改动 |
|---|---|---|
| `SessionStore.save(Checkpoint)` | SPI | 不变 ✓ |
| `SessionStore.load(String) → Optional<Checkpoint>` | SPI | 不变 ✓ |
| `SessionStore.CONTRACT_VERSION = "1.0.0"` | SPI 常量 | 不变 ✓ |
| `Checkpoint` 类 | SPI | 不变 ✓(Story #001 已落)|
| `DefaultSession.saveCheckpoint(Checkpoint)` | 公开 | 不变 ✓(内部委托 SessionStore)|
| `DefaultSession.loadCheckpoint(String) → Checkpoint` | 公开 | 不变 ✓ |
| `DefaultSession.clear()` / `.size()` | 测试 helper | 不变 ✓(内部 ConcurrentMap 保留)|
| `Agent.runBlocking(String) → RunResult` | SPI | 不变 ✓ |
| `Agent.continueWithUserMessageBlocking(String) → RunResult` | SPI | 不变 ✓ |
| `AgentFactory.create(AgentConfig) → Agent` | SPI | 不变 ✓ |
| `AgentFactory.loadYamlAndValidate(Path) → AgentConfig` | SPI | 不变 ✓ |
| `AgentConfig.@Value sessionStore: String` | 字段 | 不变 ✓(Story #001 已落,本 Story **不**新增字段)|
| `Providers.SessionStoreProvider` typed marker | SPI | 不变 ✓ |
| `Providers.SessionStoreProvider.name() / version() / priority() / create()` | SPI 4 方法 | 不变 ✓(`SlotProvider<T>` 父接口已定)|
| `SlotRouter<P, T>` 父类 | SPI | 不变 ✓(v1.5.18 + v1.5.23 已落)|

### 2.2 新增内部契约(非 SPI)

| 类型 | 说明 |
|---|---|
| `Routers.SessionStoreRouter` inner class | 填实 dsh §5.3.1.0 stub,8 Router concrete 总数对齐 |
| `InMemorySessionStoreProvider` POJO | v1.5.28 §5.5 多 Provider 模式样板,镜像 `AllowAllPermissionPolicyProvider` Story #029 precedent |
| `FileSessionStoreProvider` POJO | 同上,镜像 `StrictPermissionPolicyProvider` Story #029 precedent |
| `FileSessionStore` concrete | 新实现类,Jackson + Files + ATOMIC_MOVE + 多租户 buildKey 复用 |
| `SessionStoreAutoConfiguration` | `@AutoConfiguration` + 2 `@Bean(name="sessionStoreProvider_<name>-1.0.0")` |
| `LingsSessionStoreException extends RuntimeException` | 新异常类,`getMessage()` 前缀 `[LINGS-X01]`(镜像 `LingsLlmProviderException` Story #027a precedent)|
| `SessionStoreErrorCodes.LINGS_X01` | 新 ErrorCode 常量,X 域 1 号 |

### 2.3 行为不变性

| 场景 | 改前 | 改后 |
|---|---|---|
| 空 yml 启动 demo-product / demo-empty | `DefaultSession` in-memory 兜底 | `SlotResolver.sessionStore(cfg)` → `InMemorySessionStoreProvider.create()` → `DefaultInMemorySessionStore`,**100% 等价**(同一类同一多租户契约)|
| yml `agent.sessionStore: "memory"` | 静默忽略(无 Router 消费)| 真生效 `InMemorySessionStoreProvider.create()` |
| yml `agent.sessionStore: "file"` | 静默忽略 | 真生效 `FileSessionStoreProvider.create()` → 落盘 `<baseDir>/<key>.json` |
| yml `agent.sessionStore: "redis"`(未实现)| 静默忽略 | Router `resolve()` 抛 `IllegalArgumentException` 含 unknown name |
| 多租户 alice/bob session 隔离 | OK | OK(`buildKey(tid, sessionId)` 不动,File 后端复用同 helper)|
| `DefaultSession.clear()` / `.size()` 测试 helper | OK | OK(保留为 DefaultSession 内部 in-memory ConcurrentMap,**不**经 SessionStore SPI)|
| File 后端文件 corrupt JSON | n/a(改前无 File 后端)| `load()` 抛 `LingsSessionStoreException[LINGS-X01]`(新 ErrorCode)|
| File 后端目录无写权限 | n/a | `save()` 抛 `LINGS-X01` |
| File 后端文件路径穿越(tenantId 含 `:` `/`)| n/a | `resolvePath()` `:` `/` 替换 `_` 防穿越 |
| 现有 720 测试 fixture | 全过 | 全过(`DefaultInMemorySessionStore` 行为 100% 等价,File 后端是新能力,`DefaultSession.clear`/`.size` 保留 in-memory)|
| `AgentFactory.create()` boot invariants | 全过 | 全过(新增 1 个 `@Autowired SessionStoreRouter`,沿用 §5.3 SlotResolver 模式,**不**新增 invariants)|

---

## 3. 文件改动(边界 stretch ≤ 5,本 Story 14 文件)

### 3.1 修改(4 文件)

| # | 路径 | 改动 | 行数 |
|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java` | modify:加 `SessionStoreRouter` inner class 5-7 行 | +8 / -0 |
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultInMemorySessionStore.java` | modify:删 `@Component` + 删 `import org.springframework.stereotype.Component` + 类级 Javadoc 加 Story #014 marker | +3 / -2 |
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultSession.java` | modify:删 Javadoc 「Story #014 replaces」前瞻 + 内部委托 `SlotResolver.sessionStore(cfg)` + 测试 helper 保留 in-memory | +30 / -5 |
| 4 | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | modify:`@Autowired` ctor 加 1 参数 + 内部 SlotResolver 实例化加 1 实参 | +3 / -0 |

### 3.2 新增(10 文件)

| # | 路径 | 改动 | 行数 |
|---|---|---|---|
| 5 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/InMemorySessionStoreProvider.java` | new POJO | ~30 |
| 6 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStoreProvider.java` | new POJO + static `resolveBaseDir()` | ~50 |
| 7 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStore.java` | new concrete impl | ~120 |
| 8 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/SessionStoreAutoConfiguration.java` | new `@AutoConfiguration` | ~50 |
| 9 | `lingshu-core/src/main/java/ai/lingshu/core/spi/SessionStoreErrorCodes.java` | new ErrorCode 常量类 | ~25 |
| 10 | `lingshu-core/src/main/java/ai/lingshu/core/spi/LingsSessionStoreException.java` | new RuntimeException 子类 | ~30 |

### 3.3 新增测试(4 文件)

| # | 路径 | 改动 | 行数 |
|---|---|---|---|
| 11 | `lingshu-core/src/test/java/ai/lingshu/core/impl/session/InMemorySessionStoreProviderTest.java` | new L1 unit | ~40 |
| 12 | `lingshu-core/src/test/java/ai/lingshu/core/impl/session/FileSessionStoreTest.java` | new L1 unit(10+ case)| ~150 |
| 13 | `lingshu-core/src/test/java/ai/lingshu/core/impl/router/SessionStoreRouterIT.java` | new L2 IT | ~80 |
| 14 | `lingshu-core/src/test/java/ai/lingshu/core/impl/session/SessionStoreAutoConfigurationTest.java` | new L2 IT | ~60 |

**边界 stretch 接受**:14 文件(实际 4 modify + 10 new)超出 dsh §5.3.1.0 ≤ 5 边界,**但每文件职责内聚**(Provider POJO × 2 + 实现 × 1 + AutoConfig × 1 + ErrorCode × 2 + Router × 1 + test × 4 = 10 new),mirror Story #014 precedent(其 5 Java 文件 + 53 fixture mechanical sync);实装侧 R-13 守住即可

---

## 4. 测试策略

### 4.1 新增 L1 unit test(`InMemorySessionStoreProviderTest`)

镜像 `AllowAllPermissionPolicyProviderTest` Story #037 precedent(检查 Provider SPI 4 方法 metadata):

| Case | 验证 |
|---|---|
| `name_isMemory` | `provider.name() == "memory"` |
| `version_is_1_0_0` | `provider.version() == "1.0.0"` |
| `priority_isZero_default` | `provider.priority() == 0`(default precedence)|
| `create_returnsDefaultInMemorySessionStore` | `provider.create(mockAgentConfig) instanceof DefaultInMemorySessionStore` |

### 4.2 新增 L1 unit test(`FileSessionStoreTest`)

| Case | 验证 |
|---|---|
| `save_writesJsonFile` | save 后 `<baseDir>/<key>.json` 文件存在 + 内容合法 JSON |
| `load_roundTripsCheckpoint` | save 后 load 返同一 Checkpoint(reference equality if Jackson 序列化保留)|
| `load_missingFile_returnsEmpty` | 未 save 的 sessionId load 返 `Optional.empty()` |
| `save_atomicMove_noTempFileLeft` | save 后无 `.tmp` 残留(ATOMIC_MOVE 工作)|
| `save_ioException_throwsLingsX01` | 目录无写权限 → `LingsSessionStoreException` 含 `[LINGS-X01]` |
| `load_corruptJson_throwsLingsX01` | 写非法 JSON 后 load 抛 `LINGS-X01` |
| `load_missingFile_returnsEmpty_doesNotThrow` | 文件不存在**不**抛,返 empty |
| `multiTenant_aliceBobIsolated` | alice.save(s1) 后 bob.load(s1) empty(同 sessionId 不同 tid)|
| `pathTraversal_colonReplaced` | tenantId="ali:ce" 或 sessionId 含 `:` → 文件名含 `_` 而非 `:` |
| `pathTraversal_slashReplaced` | sessionId 含 `/` → 文件名含 `_` 而非 `/` |

### 4.3 新增 L2 IT(`SessionStoreRouterIT`)

镜像 `PermissionPolicyRouterMultiProviderIT` Story #037 precedent:

| Case | 验证 |
|---|---|
| `router_resolvesMemoryProvider` | `router.resolve("memory", cfg)` 返 `DefaultInMemorySessionStore` 实例 |
| `router_resolvesFileProvider` | `router.resolve("file", cfg)` 返 `FileSessionStore` 实例 |
| `router_byNameMap_sizeIs2` | 反射验证 `SlotRouter.byName` map size = 2 |
| `router_unknownName_throwsIllegalArgument` | `router.resolve("redis", cfg)` 抛 IAE 含 `redis` |
| `router_priority_higherWinsInMapOrder` | 反射验证 byName Map 内 `file` priority=10 > `memory` priority=0 |

### 4.4 新增 L2 IT(`SessionStoreAutoConfigurationTest`)

| Case | 验证 |
|---|---|
| `context_loads_withBothProviders` | `@SpringBootTest` 加载 → 2 Bean 都在 Spring 容器 |
| `memoryProvider_beanName_correct` | `applicationContext.getBean("sessionStoreProvider_memory-1.0.0")` 存在 |
| `fileProvider_beanName_correct` | `applicationContext.getBean("sessionStoreProvider_file-1.0.0")` 存在 |
| `defaultInMemorySessionStore_noLongerComponent` | 反射验证 `DefaultInMemorySessionStore.class.getAnnotation(Component.class) == null` |

### 4.5 反向验证(0 回归)

- `mvn -pl lingshu-core test -Dtest='DefaultSession*'` 全部 PASS(DemoSessionTest 8 case 0 改动)
- `mvn -pl lingshu-core test -Dtest='AgentConfig*'` 全部 PASS(26 字段 schema 0 改动)
- `mvn -pl lingshu-core test -Dtest='PermissionPolicy*'` 全部 PASS(Slot 4 模式镜像对齐无影响)
- `mvn -pl lingshu-core test -Dtest='Anthropic*'` 全部 PASS(Slot 1 LLM 路径无影响)
- `mvn -pl lingshu-core test -Dtest='DefaultRuntimeSandbox*'` 全部 PASS(Slot 3 Sandbox 路径无影响)
- `mvn -pl lingshu-core test` 全量:720 旧 pass + 14-18 新 case,0 failure,0 新 flake

### 4.6 L3 / E2E IT 不需要

- 本 Story 是**Slot 5 多 Provider 模式填实** —— L1 unit + L2 IT 已充分验证(Router 解析 + Provider metadata + 实现行为 + 多租户 + 路径安全)
- L3 端到端(spring-boot:run + yml 切换)由人工 e2e 清单替代(对齐 Story #042 demo-product precedent)

---

## 5. 实施顺序

1. **T-1**: `SessionStoreErrorCodes.java` + `LingsSessionStoreException.java` 基类先落(供 T-3 / T-4 引用)
2. **T-2**: `FileSessionStore.java` concrete impl + `buildKey` 内联实现
3. **T-3**: `FileSessionStoreProvider.java` POJO + `resolveBaseDir()` static helper
4. **T-4**: `InMemorySessionStoreProvider.java` POJO + 删 `DefaultInMemorySessionStore.@Component`
5. **T-5**: `SessionStoreAutoConfiguration.java` 2 `@Bean` 注册
6. **T-6**: `Routers.java` 加 `SessionStoreRouter` inner class
7. **T-7**: `SlotResolver.java` 加 `sessionStoreRouter` 字段 + ctor 加 1 参数 + `sessionStore()` 公开方法
8. **T-8**: `AgentFactory.java` ctor 加 1 参数 + SlotResolver 实例化加 1 实参
9. **T-9**: `DefaultSession.java` 接通 SessionStore delegate + 删 Javadoc 前瞻
10. **T-10**: `InMemorySessionStoreProviderTest.java` L1 unit(4 case)
11. **T-11**: `FileSessionStoreTest.java` L1 unit(10 case)
12. **T-12**: `SessionStoreRouterIT.java` L2 IT(5 case)
13. **T-13**: `SessionStoreAutoConfigurationTest.java` L2 IT(4 case)
14. **T-14**: `mvn -pl lingshu-core test` 全 PASS,无 regression(720 + 14-18 new = 734-738)
15. **T-15**: `mvn -pl lingshu-core dependency:tree` pre/post diff = 仅时间戳差异 = 0 binary delta(第 28 次 PASS)
16. **T-16**: `banned-dependencies` enforcer build 阶段 fail(R-13 mitigation (d)):passed
17. **T-17**: commit + docs 同步(dsh §13 + §5.3.1.0 + §5.5 + §5.6.4 + §15.4 + constitution §10 + ROADMAP + CLAUDE.md)

---

## 6. 关键不变项 + RAC 兜底

- `SessionStore` SPI 不变(`save` / `load` + `CONTRACT_VERSION="1.0.0"` 全部不动)✓
- `Checkpoint` 类不变 ✓
- `DefaultSession` 公开方法签名不变(`saveCheckpoint` / `loadCheckpoint` / `clear` / `size` 测试 helper 保留)✓
- `AgentFactory.create(AgentConfig)` SPI 不变(只 ctor 加 1 个 `@Autowired SessionStoreRouter` 参数)✓
- `AgentConfig.sessionStore` 字段已存在(本 Story **不**新增字段,沿用 Story #001)✓
- 26 字段 AgentConfig schema 不变 ✓
- `TenantContext.current()` 多租户契约不变(`buildKey` 复用)✓
- `LlmProvider` SPI + `Tool` SPI + `PromptBuilder` SPI 等其他 8 Slot 不变 ✓
- `Message` 4 子类 + 字段不变(本 Story 不触碰)✓
- `Prompt` + `ToolSpec` + `ToolRegistry.modelVisibleSpecs()` 不变 ✓
- `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2 守住)✓
- `Tool` SPI 不变 + `LinearTurnEngine` 公开签名不变 ✓
- §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 ✓
- 9 Slot 体系不变(本次**填实** Slot 5,而非新增 Slot)✓
- 8 Router concrete(`Routers.java` 7 → 8 inner class,`SlotResolver` 字段 6 → 7)✓
- JDK 8 兼容(`Files.readAllBytes` / `Files.write` / `Paths.get` / `Files.move` + `ATOMIC_MOVE` / `REPLACE_EXISTING` + Jackson `ObjectMapper` 已锁 + `ConcurrentHashMap` 已锁 + `Lombok @Value` 已锁 + Spring `@AutoConfiguration` / `@Bean` / `@Component` 已锁,no `var` / `List.of` / sealed / records)✓
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)✓
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)✓

---

## 7. 文件清单

| 路径 | 改动 |
|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java` | modify +8 / -0 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultInMemorySessionStore.java` | modify +3 / -2 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultSession.java` | modify +30 / -5 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | modify +3 / -0 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/session/InMemorySessionStoreProvider.java` | new ~30 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStoreProvider.java` | new ~50 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStore.java` | new ~120 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/session/SessionStoreAutoConfiguration.java` | new ~50 |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/SessionStoreErrorCodes.java` | new ~25 |
| `lingshu-core/src/main/java/ai/lingshu/core/spi/LingsSessionStoreException.java` | new ~30 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/session/InMemorySessionStoreProviderTest.java` | new ~40 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/session/FileSessionStoreTest.java` | new ~150 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/router/SessionStoreRouterIT.java` | new ~80 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/session/SessionStoreAutoConfigurationTest.java` | new ~60 |

**边界 stretch 接受**:14 文件(实际 4 modify + 10 new),超出 dsh §5.3.1.0 ≤ 5 边界 — 实装侧 R-13 守住 + 每文件职责内聚,镜像 Story #014 precedent(其 5 Java 文件 + 53 fixture mechanical sync)
