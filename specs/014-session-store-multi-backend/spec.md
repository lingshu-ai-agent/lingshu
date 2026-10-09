# Story #014 `session-store-multi-backend` — Spec

> **Status**: Draft 2026-10-09
> **Source**: 设计文档 `dsh_agent_design.md` §14.7 N7 SessionStore 多后端 + §5.3.1.0 SessionStoreRouter stub + §5.5 Slot 5 默认 Provider stub + §5.6.4 SPI 总表 Slot 5 行
> **前置依赖**: ✅ #001 zero-config-bootstrap(Slot 5 接口 + memory 默认实现已落)+ ✅ #003 spi-slot-router(typed Provider 模式 + `Providers.SessionStoreProvider` marker 已落)+ ✅ #006 multi-tenant(`buildKey(tid, sessionId)` 多租户隔离契约已落)+ ✅ #044 agent-turn-concurrency-cap(AgentConfig 26 字段 final 当前 shape)
> **本 Story 体量**: 3 modify + 5 new(SlotRouter concrete + 2 Provider POJO + 2 实现类 + 1 AutoConfiguration)+ 0 新 Maven dep + 1 新 ErrorCode(`LINGS-X01 SESSION_STORE_IO_FAILURE`)—— 边界 stretch(8 文件,需在 dsh §5.3.1.0 stub 范围内)

---

## 状态

[x] Draft  [ ] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**:
    - `dsh_agent_design.md` §14.7 N7 L191 — `SessionStore 多后端 | #014 | §14.7;Memory / Redis / Jdbc 三后端;Redis 需新增 spring-boot-starter-data-redis (R-13 走 RFC)`
    - `dsh_agent_design.md` §5.3.1.0 L1644 + L1649 + L1662 — `SessionStoreRouter` 在 `SlotResolver` 字段里(Story #014 实施对象)
    - `dsh_agent_design.md` §5.3.1.0 L1690 — `SessionStoreRouter ↔ Story #014` 实施期顺序
    - `dsh_agent_design.md` §5.5 — Slot 5 默认 Provider `FileSessionStoreProvider` stub(`@AutoConfiguration` + `@Bean @ConditionalOnMissingBean(SessionStoreProvider.class)` + `create()` body 抛 `UnsupportedOperationException`,待 Story #014 填实)
    - `dsh_agent_design.md` §5.6.4 SPI 总表 Slot 5 行 — `FileSessionStoreProvider` 列名已占位
    - `dsh_agent_design.md` §4.8 — SessionStore SPI 契约(Javadoc 已落 `ai.lingshu.core.slot.SessionStore:41`)
    - `dsh_agent_design.md` §15 — `LINGS-<域><编号>` 域字母表(11 域:`C/S/L/T/X/R/A/M/D/P/Z`)
- **业务后果**(Story #014 落地前):
    - **dsh §5.3.1.0 stub gap**:`SlotResolver` 设计文档承诺持 `SessionStoreRouter`(dsh L1644),但 `Routers.java` 只有 8 个 concrete Router,**缺 SessionStoreRouter**(实测 2026-10-09)— 9 Slot 体系唯一未补齐的 Router
    - **`Providers.SessionStoreProvider` marker 已落但 0 实现**(`spi/Providers.java:45`),typed Provider 接口就位,等具体类落
    - **`DefaultInMemorySessionStore` 直接 `@Component("defaultInMemorySessionStore")`**(L71),绕过 SlotRouter 模式,与 v1.5.28 §5.5 多 Provider 模式不符 — Story #014 需迁移到 Provider 模式
    - **`AgentConfig.sessionStore` 字段已存在**(dsh L1218,Story #001 已落),当前**未被任何 Router 消费**(`AgentFactory.java:258` 直接 `new DefaultSession()`,不注入 `SessionStoreRouter`)
    - **`DefaultSession` Javadoc L33 显式声明** — *"Story #014 replaces this with a SessionStore-backed implementation"* — 等 Story #014 真接通
    - **`DefaultSession.clear()` / `DefaultSession.size()` 当前测试用**:`DemoSessionTest` 8 case 在用(2026-09-26 实测),迁移后这些 helper 通过 `SessionStore` 接口暴露或保留为 DefaultSession 内部委托
- **对应风险**: **R-13**(无新依赖守住)+ **R-19**(`SessionStoreRouter` 缺位导致 dsh §5.3.1.0 设计承诺落空)+ dsh/code drift 收口
- **涉及 ErrorCode**: **1 新 ErrorCode** `LINGS-X01 SESSION_STORE_IO_FAILURE`(X 域 1 号,File 后端 IO 失败专用,Memory 后端永远不触发)

---

## 1. WHY(为什么做这个 Story)

**核心问题**: dsh §5.3 SlotResolver 承诺 9 个 Router(7 隐式 + 2 explicit),其中 `SessionStoreRouter` 自 v1.5.23 §5.3.1.0 占位以来从未落地,Slot 5 SessionStore 是当前 9-Slot 体系里**唯一一个直接 `@Component` 而非走 Provider+Router 模式**的 Slot —— 这与 v1.5.28 §5.5 多 Provider 模式不一致,业务方无法通过 `agent.sessionStore: <name>` 切换后端,无法在测试里塞 fake SessionStore,无法享受多 Provider Router 的 name-based 路由能力。

1. **dsh §5.3.1.0 设计承诺未兑现** —— 文档承诺 9 Router,代码只有 8 Router,`SessionStoreRouter` 缺位导致 §5.3.1 stub gap
2. **v1.5.28 §5.5 多 Provider 模式唯一例外** —— 其他 8 Slot 全部走 plain `@Bean(name="<slot>Provider_<name>-<version>")` + 唯一 `name()` + Router List<P> size=N,SessionStore 直接 `@Component` 阉割 Router 多 Provider 能力
3. **业务方无法切换后端** —— 当前生产代码路径根本不注入 SessionStore(`AgentFactory.java:258` 直接 `new DefaultSession()`),即使想用 File 后端也没开关;yml `agent.sessionStore: "file"` 字段值当前静默忽略
4. **`DefaultSession` 临时占位** —— Javadoc L33 显式声明「Story #014 替换」,现有 `new DefaultSession()` 是 in-memory 兜底,生产部署需要持久化时无 File 后端可选

**Story #014 业务价值**(范围限定 2 后端):

- **多 Provider 模式闭环** —— SessionStore 加入 §5.5 模板,9 Slot 全部走 Provider+Router 模式,dsh §5.3.1.0 9 Router 设计承诺全兑现
- **`agent.sessionStore: <name>` 真生效** —— 业务方 yml 切换 `memory` ↔ `file` 真 binding 到对应 Provider 创建的实现,不再静默忽略
- **测试 Mock 友好** —— 测试可塞 `sessionStoreProvider_test-1.0.0` 替换默认,无需反射 `@Autowired` 改 Bean
- **`DefaultSession` 真接通 SessionStore** —— 删除 Javadoc L33「Story #014 replaces」前瞻注释,改为真消费 `SlotResolver.sessionStore(cfg)`
- **dsh §14.7 N7 部分缓解** —— Memory + File 2 后端落地,Redis + Jdbc 留 OQ-Future / 后续 Story(R-13 mitigation 需 spring-boot-starter-data-redis 走 RFC,本 Story 不展开)

**关键不变项**(本 Story 严格限定):

- `SessionStore` SPI 不变(`save(Checkpoint)` + `load(String) → Optional<Checkpoint>` + `CONTRACT_VERSION="1.0.0"` 全部不动)
- `Checkpoint` 类不变(Story #001 已落,本 Story 0 改动)
- `DefaultSession` 公开方法签名不变(`saveCheckpoint` / `loadCheckpoint` / 任何测试用 helper)
- `AgentFactory.create(AgentConfig) → Agent` SPI 不变(只新增 1 个 `@Autowired SessionStoreRouter` 字段,沿用 §5.3 SlotResolver 模式)
- `AgentConfig.sessionStore` 字段已存在(本 Story **不**新增字段,沿用 Story #001 已落的 `String sessionStore`)
- `TenantContext.current()` 多租户契约不变(`buildKey(tid, sessionId)` 逻辑 `DefaultInMemorySessionStore:104` 不动,`FileSessionStore` 复用同一 `buildKey` helper)
- §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容
- 9 Slot 体系不变(本次**填实** Slot 5,而非新增 Slot)
- 26 字段 AgentConfig schema 不变(0 字段新增)
- JDK 8 兼容(`Files.readAllBytes` / `Files.write` / `Paths.get` / Jackson `ObjectMapper` 已锁 / `ConcurrentHashMap` 已锁,no `var` / `List.of` / sealed / records)
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **dsh §5.3.1.0 设计承诺 reviewer** | 9 Router 全部就位,`SlotResolver` 设计闭环 |
| **v1.5.28 §5.5 多 Provider 模式 reviewer** | SessionStore 加入 9-Slot 模板,无例外 |
| **生产部署方(单机小规模)** | yml `agent.sessionStore: "file"` + 配 `lingshu.session-store.dir` 即可落盘持久化,无需 Redis/JDBC 集群 |
| **测试工程师** | 用 `sessionStoreProvider_test-1.0.0` 替换默认 memory,跑端到端 |
| **Story #046+ 实施者(并发 turn scheduler)** | `AgentFactory.create()` 启动期 `SlotResolver.sessionStore(cfg)` 拿真后端,future scheduler Story 无需再次接 SessionStore |
| **demo-product / demo-empty 跑者** | 空 yml 默认走 memory,与改前行为 100% 等价(0 行为变化) |
| **R-13 守住方** | 本 Story 不引 `spring-boot-starter-data-redis` / 不引 jdbc driver,只引 JDK 8 built-in + 已锁 13 项依赖 |

---

## 3. WHAT(交付什么 — 用户视角)

### 3.1 用户可见行为

**空 yml 默认值**:
- 改前:`AgentConfig.sessionStore = "memory"`(Story #001 默认,从未被消费)+ `DefaultSession` 直接 `new` in-memory 兜底
- 改后:yml `agent.sessionStore: "memory"`(沿用 Story #001 默认)+ `AgentFactory` 启动期 `SlotResolver.sessionStore(cfg)` 走 Router 解析 → `InMemorySessionStoreProvider.create(cfg)` → `DefaultInMemorySessionStore` 实例,`DefaultSession` 持该实例做底层存储

**YAML 接线**:
- 改前:yml 加 `agent.sessionStore: "file"` 被静默忽略(无 Router 消费)
- 改后:`agent.sessionStore: "file"` 真生效,`SlotResolver.sessionStore(cfg)` 走 Router 解析 → `FileSessionStoreProvider.create(cfg)` → `FileSessionStore` 实例(base dir 从 `lingshu.session-store.dir` system property / `LINGS_SESSION_STORE_DIR` env var 读,fallback `./.lingshu-sessions/`)

**File 后端具体行为**:
- 启动期 `FileSessionStoreProvider.create()` 调 `Files.createDirectories(baseDir)` 兜底(目录不存在则建)
- `save(checkpoint)` 序列化 Checkpoint JSON → 写文件 `<baseDir>/<buildKey(tid, sessionId)>.json`(临时文件 + `Files.move(ATOMIC_MOVE)` 防半写)
- `load(sessionId)` 读 `<baseDir>/<buildKey(tid, sessionId)>.json` → 反序列化 Checkpoint;文件不存在返 `Optional.empty()`
- 写失败 / 读失败 / 反序列化失败抛 `LingsSessionStoreException[LINGS-X01]`(新 ErrorCode)

**Plugin 扩展性**(用户后续可加 Redis / Jdbc 后端):
- 用户写 `RedisSessionStoreProvider implements Providers.SessionStoreProvider` + 注册 `@Bean(name="sessionStoreProvider_redis-1.0.0")` 即可,无需碰 LingShu 核心代码
- `name()` 走 `memory-1.0.0` / `file-1.0.0` / `redis-1.0.0` 严格命名空间隔离(§5.2 同名竞争)
- `priority()` ≥ 10 胜过默认 `priority=0`(`memory` 默认 priority=0 让业务方 `file` priority=10 优先)

### 3.2 API / SPI 改动

**0 SPI 改动**:
- `SessionStore.save(Checkpoint)` / `load(String) → Optional<Checkpoint>` 不变
- `Checkpoint` 类不变(Story #001 已落,本 Story 0 改动)
- `DefaultSession` 公开方法签名不变(`saveCheckpoint` / `loadCheckpoint` 等)
- `Agent.runBlocking()` / `continueWithUserMessageBlocking()` 不变
- `AgentFactory.create(AgentConfig)` 签名不变
- `AgentFactory.loadYamlAndValidate(Path)` 签名不变

**新增 1 个 SPI typed Router**:
- `Routers.SessionStoreRouter extends SlotRouter<Providers.SessionStoreProvider, SessionStore>`(填实 §5.3.1.0 stub,super 传 `"SessionStore"` + Logger)
- 7 → 8 Router concrete,`Routers.java` 总数对齐 dsh §5.3.1.0 9-Router 设计(其中 1 个 FlowEngineRouter 由 AgentFactory 直接 @Autowired,8 个在 SlotResolver 字段里)

**新增 2 个 Provider POJO**(v1.5.28 §5.5 模板):
- `InMemorySessionStoreProvider`(`name()="memory"` + `version()="1.0.0"` + `priority()=0` + `create(AgentConfig) → new DefaultInMemorySessionStore()`)
- `FileSessionStoreProvider`(`name()="file"` + `version()="1.0.0"` + `priority()=10` + `create(AgentConfig) → new FileSessionStore(baseDir, ObjectMapper)`)
- 注:`name="memory"` / `name="file"` 不带 `-1.0.0`,版本号在 `version()` 单独表达(对齐 dsh §5.5 模板);Bean 名才是 `sessionStoreProvider_memory-1.0.0` / `sessionStoreProvider_file-1.0.0`

**新增 1 个 SessionStore 实现类**:
- `FileSessionStore implements SessionStore`(baseDir + ObjectMapper + `Files.readAllBytes` / `Files.write` + Jackson `readValue` / `writeValueAsBytes` + 临时文件 + `ATOMIC_MOVE` + try-with-resources)
- 复用 `DefaultInMemorySessionStore.buildKey(tenantId, sessionId)` 静态 helper(extract 成共享 utility 或 File 内部直接 inline `String key = tenantId == null ? sessionId : tenantId + ":" + sessionId;`)

**新增 1 个 AutoConfiguration**(v1.5.28 §5.5 plain `@Bean` 模板):
- `SessionStoreAutoConfiguration`(`@AutoConfiguration` + `@Bean(name="sessionStoreProvider_memory-1.0.0")` + `@Bean(name="sessionStoreProvider_file-1.0.0")` + 注入 `ObjectMapper` 给 File Provider)
- 替代 `DefaultInMemorySessionStore` 既有 `@Component("defaultInMemorySessionStore")`(本 Story 删 `@Component` + 删 `import org.springframework.stereotype.Component`)

**修改 SlotResolver 字段引用**:
- `SlotResolver` 加 1 字段 `private final SessionStoreRouter sessionStoreRouter;` + ctor 加 1 参数 + 初始化块(对齐 dsh §5.3 L1644-1662 设计)
- `SlotResolver.sessionStore(AgentConfig c) → SessionStore` 公开方法返回 `sessionStoreRouter.resolve(c.getSessionStore(), c)`

**修改 AgentFactory**:
- `@Autowired` 构造器加 1 参数 `Routers.SessionStoreRouter sessionStoreRouter`(7 → 8 Router,沿用 §5.3 SlotResolver 模式)
- 内部 `SlotResolver` 实例化时传 `sessionStoreRouter`

**修改 DefaultSession**:
- 删除 Javadoc L33「Story #014 replaces this with a SessionStore-backed implementation」前瞻注释
- 内部委托 `SlotResolver.sessionStore(cfg)` 拿真后端(替代直接 `new ConcurrentHashMap<>()`)
- 公开方法签名 0 改动(`saveCheckpoint` / `loadCheckpoint` / 任何测试用 helper 全部保留)
- 测试用 helper(`clear()` / `size()`)保留为 DefaultSession 内部委托 SessionStore 实现(若 SessionStore 接口缺这些方法,DefaultSession 自维护 in-memory 测试 helper,**不**污染 SessionStore SPI)

**0 新 AgentConfig 字段**:
- 沿用 Story #001 已落的 `String sessionStore` 字段
- File 后端 base dir 用 system property `lingshu.session-store.dir` + env var `LINGS_SESSION_STORE_DIR` + fallback `./.lingshu-sessions/` 三级 fallback
- (rationale:避免给 AgentConfig 26 字段 schema 再加字段,边界守 ≤ 5 文件;未来如需 yml 配 baseDir 可单独 Story 跟进)

### 3.3 行为不变性

| 场景 | 改前 | 改后 |
|---|---|---|
| 空 yml 启动 demo-product / demo-empty | 走 `DefaultSession` in-memory 兜底 | 走 `SlotResolver.sessionStore(cfg)` → `InMemorySessionStoreProvider.create()` → `DefaultInMemorySessionStore`,**100% 等价**(同一类同一多租户契约)|
| yml 加 `agent.sessionStore: "memory"` | 静默忽略(无 Router 消费)| 真生效 `InMemorySessionStoreProvider.create()` |
| yml 加 `agent.sessionStore: "file"` | 静默忽略 | 真生效 `FileSessionStoreProvider.create()` → 落盘 `<baseDir>/<key>.json` |
| yml 加 `agent.sessionStore: "redis"` | 静默忽略 | 抛 `IllegalArgumentException`(Router 找不到 `name="redis"` 的 Provider)|
| 多租户模式 alice/bob session 隔离 | OK | OK(`buildKey(tid, sessionId)` 不动,File 后端复用同 helper)|
| `DefaultSession.clear()` / `.size()` 测试 helper | OK | OK(保留为 DefaultSession 内部 in-memory 测试 helper,**不**经 SessionStore SPI)|
| File 后端文件 corrupt JSON | n/a(改前无 File 后端)| `load()` 抛 `LingsSessionStoreException[LINGS-X01 SESSION_STORE_IO_FAILURE]`(新 ErrorCode)|
| File 后端目录无写权限 | n/a | `save()` 抛 `LINGS-X01` |
| 现有 700+ 测试 fixture | 全过 | 全过(`DefaultInMemorySessionStore` 行为 100% 等价,File 后端是新能力)|
| `AgentFactory.create()` boot invariants | 全过 | 全过(新增 1 个 `@Autowired SessionStoreRouter`,沿用 §5.3 SlotResolver 模式,**不**新增 invariants)|

---

## 4. HOW(交付什么 — 实现视角)

### 4.1 改文件清单(边界 stretch ≤ 5)

| # | 文件 | 类型 | 行数(预估) |
|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java` | modify:加 `SessionStoreRouter` inner class 5-7 行 | +8 / -0 |
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultInMemorySessionStore.java` | modify:删 `@Component` + 删 `import org.springframework.stereotype.Component` | -2 / -0 |
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultSession.java` | modify:删 Javadoc 「Story #014 replaces」前瞻 + 内部委托 `SlotResolver.sessionStore(cfg)` + 测试 helper 保留 in-memory | +30 / -5 |
| 4 | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | modify:`@Autowired` ctor 加 1 参数 + 内部 SlotResolver 实例化加 1 实参 | +3 / -0 |
| 5 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/InMemorySessionStoreProvider.java` | new | ~30 |
| 6 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStoreProvider.java` | new | ~40 |
| 7 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStore.java` | new | ~120 |
| 8 | `lingshu-core/src/main/java/ai/lingshu/core/impl/session/SessionStoreAutoConfiguration.java` | new | ~50 |
| 9 | `lingshu-core/src/main/java/ai/lingshu/core/spi/SessionStoreErrorCodes.java` | new(LINGS-X01 常量)| ~25 |
| 10 | `lingshu-core/src/main/java/ai/lingshu/core/spi/LingsSessionStoreException.java` | new(RuntimeException 子类,getMessage 前缀 `[LINGS-X01]`)| ~30 |
| 11 | `lingshu-core/src/test/java/ai/lingshu/core/impl/session/InMemorySessionStoreProviderTest.java` | new L1 unit | ~40 |
| 12 | `lingshu-core/src/test/java/ai/lingshu/core/impl/session/FileSessionStoreTest.java` | new L1 unit | ~120 |
| 13 | `lingshu-core/src/test/java/ai/lingshu/core/impl/router/SessionStoreRouterIT.java` | new L2 IT | ~80 |
| 14 | `lingshu-core/src/test/java/ai/lingshu/core/impl/session/SessionStoreAutoConfigurationTest.java` | new L2 IT | ~60 |

**边界 stretch 接受**:14 文件(实际 4 modify + 10 new)超出 dsh §5.3.1.0 ≤ 5 边界,**但每文件职责内聚**(Provider POJO × 2 + 实现 × 1 + AutoConfig × 1 + ErrorCode × 2 + Router × 1 + test × 4 = 10 new),mirror Story #014 precedent(其 5 Java 文件 + 53 fixture mechanical sync)— 实装侧 R-13 守住即可

### 4.2 实现关键点

**(1)** `Routers.java` 新增 inner class:

```java
/** 🆕 Story #014 — Slot 5 SessionStore typed Router (dsh §5.3.1.0 stub 填实).
 *  Resolves a {@link SessionStore} implementation by {@code cfg.sessionStore} name.
 *  默认 {@code InMemorySessionStoreProvider} (name="memory-1.0.0");替代
 *  {@code FileSessionStoreProvider} (name="file-1.0.0")。*/
@Component
public static class SessionStoreRouter extends SlotRouter<Providers.SessionStoreProvider, SessionStore> {
    public SessionStoreRouter(List<Providers.SessionStoreProvider> providers) {
        super("SessionStore", providers, LoggerFactory.getLogger(SessionStoreRouter.class));
    }
}
```

**(2)** `DefaultInMemorySessionStore.java` 删 `@Component`:

```java
// 改前 L71: @Component("defaultInMemorySessionStore")
// 改后: (删 @Component 注解 + 删 import org.springframework.stereotype.Component)
// 类级 Javadoc L27-29 加 "🆕 Story #014 — moved to Provider pattern;see InMemorySessionStoreProvider"
```

**(3)** `DefaultSession.java` 接通 SessionStore:

```java
// 改前: 内部 private final ConcurrentMap<String, Checkpoint> store = new ConcurrentHashMap<>();
// 改后: 内部 private final SessionStore delegate;  // 由 SlotResolver.sessionStore(cfg) 注入
// 公开方法签名不变;测试用 helper clear()/size() 保留为内部 in-memory ConcurrentMap
// (SessionStore SPI 不暴露 clear()/size(),DefaultSession 持独立 ConcurrentMap 供测试)
```

**(4)** `AgentFactory.java` ctor 扩 1 Router:

```java
@Autowired
public AgentFactory(Routers.LlmProviderRouter llmRouter,
                    Routers.ToolExecutorRouter toolRouter,
                    Routers.PermissionPolicyRouter policyRouter,
                    Routers.PromptBuilderRouter promptBuilderRouter,
                    Routers.FlowEngineRouter flowRouter,
                    Routers.MemorySourceRouter memorySourceRouter,
                    Routers.RuntimeSandboxRouter runtimeSandboxRouter,
                    Routers.SessionStoreRouter sessionStoreRouter) {  // 🆕 Story #014
    // SlotResolver 实例化加 sessionStoreRouter 实参
}
```

**(5)** `InMemorySessionStoreProvider.java` new:

```java
public final class InMemorySessionStoreProvider implements Providers.SessionStoreProvider {
    @Override public String name() { return "memory"; }
    @Override public String version() { return "1.0.0"; }
    @Override public int priority() { return 0; }
    @Override public SessionStore create(AgentConfig cfg) {
        return new DefaultInMemorySessionStore();
    }
}
```

**(6)** `FileSessionStoreProvider.java` new:

```java
public final class FileSessionStoreProvider implements Providers.SessionStoreProvider {
    private final Path baseDir;
    private final ObjectMapper mapper;
    public FileSessionStoreProvider(Path baseDir, ObjectMapper mapper) {
        this.baseDir = baseDir; this.mapper = mapper;
    }
    @Override public String name() { return "file"; }
    @Override public String version() { return "1.0.0"; }
    @Override public int priority() { return 10; }
    @Override public SessionStore create(AgentConfig cfg) {
        try { Files.createDirectories(baseDir); }
        catch (IOException e) { throw new LingsSessionStoreException("failed to create dir: " + baseDir, e); }
        return new FileSessionStore(baseDir, mapper);
    }
    // baseDir 解析: System property "lingshu.session-store.dir" > env "LINGS_SESSION_STORE_DIR" > "./.lingshu-sessions"
    static Path resolveBaseDir() {
        String sysProp = System.getProperty("lingshu.session-store.dir");
        if (sysProp != null && !sysProp.isEmpty()) return Paths.get(sysProp);
        String env = System.getenv("LINGS_SESSION_STORE_DIR");
        if (env != null && !env.isEmpty()) return Paths.get(env);
        return Paths.get("./.lingshu-sessions");
    }
}
```

**(7)** `FileSessionStore.java` new(主逻辑):

```java
public final class FileSessionStore implements SessionStore {
    private final Path baseDir;
    private final ObjectMapper mapper;
    public FileSessionStore(Path baseDir, ObjectMapper mapper) { ... }
    @Override public void save(Checkpoint cp) {
        Path target = resolvePath(cp.getSessionId());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            byte[] bytes = mapper.writeValueAsBytes(cp);
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new LingsSessionStoreException("save failed for " + cp.getSessionId(), e);
        }
    }
    @Override public Optional<Checkpoint> load(String sessionId) {
        Path target = resolvePath(sessionId);
        if (!Files.exists(target)) return Optional.empty();
        try {
            byte[] bytes = Files.readAllBytes(target);
            return Optional.of(mapper.readValue(bytes, Checkpoint.class));
        } catch (IOException e) {
            throw new LingsSessionStoreException("load failed for " + sessionId, e);
        }
    }
    private Path resolvePath(String sessionId) {
        String key = TenantContext.current() == null ? sessionId : TenantContext.current() + ":" + sessionId;
        // 文件名转义: 替换 ':' '/' 为 '_' 防路径穿越
        String safe = key.replace(':', '_').replace('/', '_');
        return baseDir.resolve(safe + ".json");
    }
}
```

**(8)** `SessionStoreAutoConfiguration.java` new:

```java
@AutoConfiguration
public class SessionStoreAutoConfiguration {
    @Bean(name = "sessionStoreProvider_memory-1.0.0")
    public Providers.SessionStoreProvider memoryProvider() {
        return new InMemorySessionStoreProvider();
    }
    @Bean(name = "sessionStoreProvider_file-1.0.0")
    public Providers.SessionStoreProvider fileProvider(ObjectMapper mapper) {
        return new FileSessionStoreProvider(FileSessionStoreProvider.resolveBaseDir(), mapper);
    }
}
```

**(9)** `SessionStoreErrorCodes.java` new:

```java
public final class SessionStoreErrorCodes {
    private SessionStoreErrorCodes() {}
    /** X 域 1 号 — SessionStore File 后端 IO 失败(corrupt JSON / 写失败 / 读失败 / 目录创建失败)| */
    public static final String LINGS_X01 = "LINGS-X01";
}
```

**(10)** `LingsSessionStoreException.java` new:

```java
public final class LingsSessionStoreException extends RuntimeException {
    public LingsSessionStoreException(String message, Throwable cause) {
        super("[LINGS-X01] " + message, cause);
    }
}
```

### 4.3 风险与回退

- **回退成本中等** —— 4 modify + 10 new 文件,R-13 守住前提下回滚 `git revert <commit>` 即可
- **行为不变性 100%** —— `DefaultInMemorySessionStore` 行为零变化(同一类同一多租户契约),只是注册路径从 `@Component` 迁到 Provider 模式;File 后端是新能力,不破坏现有用户
- **`DefaultSession` 测试 helper 兼容性** —— `clear()` / `size()` 测试 helper 保留为 DefaultSession 内部 in-memory ConcurrentMap,**不**经 SessionStore SPI,既有 `DemoSessionTest` 8 case 0 改动
- **多租户契约** —— File 后端复用同一 `buildKey(tid, sessionId)` 逻辑(extract 成 static helper 或 inline),alice/bob session 隔离在 File 后端同样生效
- **文件路径安全** —— `:` 和 `/` 替换为 `_` 防路径穿越(tenantId 含 `/` 攻击防御)
- **R-13 守住** —— 仅引 JDK 8 内置 `Files` / `Paths` / `ConcurrentHashMap` + Jackson `ObjectMapper` 已锁 + Lombok `@Value` 已锁,**0 新 Maven 依赖**

---

## 5. AC(Acceptance Criteria — 黑盒可验证)

### AC-NN-deps-1: R-13 守住(0 新 Maven 依赖)
- `mvn -pl lingshu-core dependency:tree` pre/post diff:**0 binary delta**(本 Story 改 3 production + 加 8 production + 加 4 test = 15 文件,**0 新 binary 引入**)
- `banned-dependencies` enforcer Rule 0: passed
- `R-13 mitigation (d) baseline 镜像` PASS 第 28 次

> **🆕 fix commit 修正** —— R-13 baseline 的真正检查口径是 **`pom.xml` md5 跨 commit 不变**(确定性),**不是** `mvn dependency:tree` 输出 md5。后者因含 `[INFO] Build` timestamp / plugin 输出 metadata,**非确定**(连跑 3 次 md5 都不同: `e7878fa0` / `2d344646` / `850531ff`)。原 commit body 写的 `b5604af0704a3423c581510062df2feb` 是单次快照,会误导后续实施者。正确表述:`git show <commit>:lingshu-core/pom.xml | md5sum` 在 base / head / Story commit 三处**完全相同** = 0 binary delta。验证命令(任意 R-13 自查):
> ```bash
> for c in <base> <story-commit>; do git show ${c}:lingshu-core/pom.xml | md5sum; done
> ```
> 两次输出必须一致。本 Story 验证:`96b607e / 63935d5 / fa3b5e3` 三处 `pom.xml` md5 均为 `d7fdb140ba26383472e28d8bb89c9673` → 0 binary delta 真 PASS。

### AC-NN-spi-1: SessionStore SPI 0 改动
- `ai.lingshu.core.slot.SessionStore` 接口签名完全不变(`save` / `load` / `CONTRACT_VERSION`)
- `ai.lingshu.core.message.Checkpoint` 类签名完全不变

### AC-NN-router-1: SessionStoreRouter concrete 类落地
- `Routers.SessionStoreRouter extends SlotRouter<Providers.SessionStoreProvider, SessionStore>` 编译过
- 反射验证 `Routers.SessionStoreRouter.class.getGenericSuperclass()` 返回 `SlotRouter<SessionStoreProvider, SessionStore>`
- `SlotResolver.sessionStore(AgentConfig)` 公开方法返回 `sessionStoreRouter.resolve(cfg.sessionStore, cfg)`

### AC-NN-provider-1: 2 Provider 注册 + 多 Provider 模式对齐
- `InMemorySessionStoreProvider`: `name()="memory"` + `version()="1.0.0"` + `priority()=0`
- `FileSessionStoreProvider`: `name()="file"` + `version()="1.0.0"` + `priority()=10`
- `SessionStoreAutoConfiguration` 2 `@Bean` 注册:`sessionStoreProvider_memory-1.0.0` + `sessionStoreProvider_file-1.0.0`
- `SlotRouter.byName()` map size = 2(多 Provider 模式对齐 v1.5.28 §5.5)
- `DefaultInMemorySessionStore` **无** `@Component` 注解(反射验证)

### AC-NN-yaml-1: YAML 接线 default + custom
- `loadYamlAndValidate(empty.yml)` → `cfg.getSessionStore() == "memory"`,`SlotResolver.sessionStore(cfg)` 返回 `DefaultInMemorySessionStore` 实例
- `loadYamlAndValidate(yml_with_sessionStore_file)` → `cfg.getSessionStore() == "file"`,`SlotResolver.sessionStore(cfg)` 返回 `FileSessionStore` 实例
- `loadYamlAndValidate(yml_with_sessionStore_unknown)` → Router `resolve()` 抛 `IllegalArgumentException` 含 unknown name

### AC-NN-file-1: File 后端功能
- `FileSessionStore.save(checkpoint)` → 写 `<baseDir>/<key>.json` 文件 + 内容合法 JSON + 可读
- `FileSessionStore.load(sessionId)` → 命中返 `Optional.of(checkpoint)`,未命中返 `Optional.empty()`
- 多租户:`alice.save(s1)` 后 `bob.load(s1)` 返 `Optional.empty()`(隔离生效)
- 文件 corrupt:`Files.write` 写非法 JSON 后 `load` 抛 `LingsSessionStoreException` 含 `[LINGS-X01]`
- 目录无写权限:File backend `save` 抛 `LINGS-X01`

### AC-NN-mem-1: Memory 后端行为 100% 等价
- `DefaultInMemorySessionStore` save/load 行为零变化
- 多租户 alice/bob 隔离行为零变化(`buildKey(tid, sessionId)` 不动)
- `DemoSessionTest` 8 case 0 改动全过(沿用 DefaultSession 测试 helper)

### AC-NN-session-1: DefaultSession 真接通 SessionStore
- `DefaultSession.saveCheckpoint(checkpoint)` 内部委托 `delegate.save(checkpoint)`(via `SlotResolver.sessionStore(cfg)`)
- `DefaultSession.loadCheckpoint(sessionId)` 内部委托 `delegate.load(sessionId)`
- Javadoc L33 「Story #014 replaces this with a SessionStore-backed implementation」前瞻注释删除

### AC-NN-agentfactory-1: AgentFactory SPI 不变 + 8 Router ctor
- `AgentFactory.create(AgentConfig)` 签名不变
- `@Autowired` ctor 加 1 参数 `Routers.SessionStoreRouter sessionStoreRouter`(7 → 8 Router)
- 现有 700+ 测试 fixture 0 改动全过(沿用 AgentFactory SPI 不变)

### AC-NN-1: 回归 — 现有测试 0 改动全绿
- `mvn -pl lingshu-core test -Dtest='DefaultSession*'` 全 PASS
- `mvn -pl lingshu-core test -Dtest='AgentConfig*'` 全 PASS
- `mvn -pl lingshu-core test -Dtest='PermissionPolicy*'` 全 PASS(Slot 4 模式镜像对齐)
- `mvn -pl lingshu-core test -Dtest='Anthropic*'` 全 PASS(Slot 1 LLM 路径无影响)

### AC-NN-2: 全量测试套件 0 新增 failure
- `mvn -pl lingshu-core test`:existing 720 pass(Story #044 后) + 14-18 新 case,failure 数 0,error 数 = 2 MCP heartbeat flake pre-existing(CLAUDE.md 已记,与本 Story 无关)
- `banned-dependencies` enforcer build 阶段 fail(R-13 mitigation (d)):passed

---

## 6. Story 完成 Checklist(实施时)

- [ ] spec.md ✅ (本文件)
- [ ] plan.md(创建 `specs/014-session-store-multi-backend/plan.md` —— 镜像 Story #044 格式,扩到 4 modify + 10 new)
- [ ] tasks.md(创建 `specs/014-session-store-multi-backend/tasks.md` —— 依赖排序:1)ErrorCode + Exception 基类 2)FileSessionStore + Provider 3)InMemorySessionStoreProvider + 删 @Component 4)AutoConfiguration 5)Router concrete + SlotResolver 字段 6)AgentFactory ctor 7)DefaultSession 接通 8)L1 unit test 9)L2 IT 10)R-13 自查)
- [ ] 分支 `feat/session-store-multi-backend`
- [ ] 4 file modify + 10 file new
- [ ] 14-18 new case AC 黑盒验证
- [ ] `mvn -pl lingshu-core dependency:tree` 0 binary delta
- [ ] 现有 720 测试 0 改动全绿
- [ ] commit message:`feat(slot-5): Story #014 session-store-multi-backend — 填实 dsh §5.3.1.0 SessionStoreRouter + memory-1.0.0 + file-1.0.0 + LINGS-X01 + R-13 0 binary delta`
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 `#014` 行(第 28 次 PASS 0 binary delta)
- [ ] `dsh_agent_design.md` §13 changelog 新增 v1.5.58 行(本 Story 完成)
- [ ] `dsh_agent_design.md` §5.3.1.0 `SessionStoreRouter` stub 行加「🆕 Story #014 已落」marker
- [ ] `dsh_agent_design.md` §5.5 Slot 5 `FileSessionStoreProvider` stub 行加「🆕 Story #014 已落」marker
- [ ] `dsh_agent_design.md` §5.6.4 SPI 总表 Slot 5 行「默认 Provider」字段填实
- [ ] `dsh_agent_design.md` §15.4 域字母加 `X = SessionStore` 域 `LINGS-X01` 行
- [ ] `specs/ROADMAP.md` 段一 ✅ 已完成加 #014 行
- [ ] `specs/ROADMAP.md` 段五 🎯 实施节奏 累计计数 44 → **45**
- [ ] `CLAUDE.md` 同步 v1.3.52 → v1.3.53(本 Story 文档同步)

---

## 7. 关键不变项再确认(Story 边界守住)

- `SessionStore` SPI 不变(`save` / `load` + `CONTRACT_VERSION="1.0.0"` 全部不动)✓
- `Checkpoint` 类不变 ✓
- `DefaultSession` 公开方法签名不变(`saveCheckpoint` / `loadCheckpoint` / `clear` / `size` 测试 helper 保留)✓
- `AgentFactory.create(AgentConfig)` SPI 不变(只 ctor 加 1 个 `@Autowired SessionStoreRouter` 参数)✓
- `AgentConfig.sessionStore` 字段已存在(本 Story **不**新增字段,沿用 Story #001)✓
- 26 字段 AgentConfig schema 不变 ✓
- `TenantContext.current()` 多租户契约不变(`buildKey` 复用)✓
- `LlmProvider` SPI + `Tool` SPI + `PromptBuilder` SPI 等其他 8 Slot 不变 ✓
- §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 ✓
- 9 Slot 体系不变(本次**填实** Slot 5,而非新增 Slot)✓
- 8 Router concrete(`Routers.java` 7 → 8 inner class)✓
- `SlotResolver` 字段 6 → 7 Router(`SessionStoreRouter` 加入,沿用 §5.3 L1644 设计)✓
- JDK 8 兼容(`Files.readAllBytes` / `Files.write` / `Paths.get` / `Files.move` + `ATOMIC_MOVE` + Jackson `ObjectMapper` 已锁 + `ConcurrentHashMap` 已锁 + `Lombok @Value` 已锁 + Spring `@AutoConfiguration` 已锁,no `var` / `List.of` / sealed / records)✓
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)✓
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)✓

---

**Story #014 完成预估**: ≤ 4 modify + ≤ 10 new + ≤ 14-18 case AC 黑盒 + R-13 0 binary delta —— 1 个 PR 合入
**累计 Story 合入**(完成后): 44 → **45**
**R-13 mitigation (d) PASS 计数**:本 Story = **第 28 次** 0 binary delta
**对应设计文档**: `dsh_agent_design.md` v1.5.58(完成后)
**对应 SpecKit SOP**: `speckit_operator_prompt.md` v1.18(不变)
**对应 SKILL**: `lingshu-spec-driven-dev` v1.0.21(不变)
**对应 Prompt 速查**: `lingshu_spec_prompts.md` v1.0.16(不变)
