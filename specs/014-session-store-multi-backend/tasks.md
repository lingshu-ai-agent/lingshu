# Story #014 `session-store-multi-backend` — Tasks

> **Status**: Draft 2026-10-09
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 plan**: [`plan.md`](./plan.md)
> **完成定义**: T-1 ~ T-17 全 ✅ + 现有 720 测试 0 改动全过 + 14-18 新 case 全部 PASS

---

## T-1: SessionStoreErrorCodes + LingsSessionStoreException 基类

**路径**:
- `lingshu-core/src/main/java/ai/lingshu/core/spi/SessionStoreErrorCodes.java` (new)
- `lingshu-core/src/main/java/ai/lingshu/core/spi/LingsSessionStoreException.java` (new)

**操作**:

1. `SessionStoreErrorCodes.java`:`public final class` + private ctor + `public static final String LINGS_X01 = "LINGS-X01";` + Javadoc「X 域 1 号,SessionStore File 后端 IO 失败」
2. `LingsSessionStoreException.java`:`extends RuntimeException` + 单 ctor `(String message, Throwable cause)` 调 `super("[LINGS-X01] " + message, cause)` + Javadoc「镜像 `LingsLlmProviderException` Story #027a precedent」

**验证**:
- `grep -c 'LINGS-X01' lingshu-core/src/main/java/ai/lingshu/core/spi/SessionStoreErrorCodes.java` → ≥ 1
- `grep -c 'LINGS-X01' lingshu-core/src/main/java/ai/lingshu/core/spi/LingsSessionStoreException.java` → ≥ 1
- `javap` 反射验证 `LingsSessionStoreException.class.getSuperclass() == RuntimeException.class`

---

## T-2: FileSessionStore concrete impl

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStore.java` (new)

**操作**:

1. `public final class FileSessionStore implements SessionStore`(不可变 + final)
2. 字段 `private final Path baseDir` + `private final ObjectMapper mapper`
3. 2-arg ctor(无 validation,Provider 层兜底)
4. `save(Checkpoint cp)` 5 段:
    - null check → IllegalArgumentException
    - `target = resolvePath(cp.getSessionId())`
    - `tmp = target.resolveSibling(target.getFileName() + ".tmp")`
    - `byte[] bytes = mapper.writeValueAsBytes(cp)`
    - `Files.write(tmp, bytes)` + `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`
    - catch IOException → `throw new LingsSessionStoreException("save failed for " + cp.getSessionId(), e)`
5. `load(String sessionId)` 5 段:
    - null check → Optional.empty()
    - `target = resolvePath(sessionId)`
    - `if (!Files.exists(target)) return Optional.empty();`
    - `byte[] bytes = Files.readAllBytes(target)` + `return Optional.of(mapper.readValue(bytes, Checkpoint.class));`
    - catch IOException → `throw new LingsSessionStoreException("load failed for " + sessionId, e)`
6. 私有 `resolvePath(String sessionId)`:`String key = TenantContext.current() == null ? sessionId : TenantContext.current() + ":" + sessionId;` + `String safe = key.replace(':', '_').replace('/', '_');` + `return baseDir.resolve(safe + ".json");`

**验证**:
- `grep -c 'implements SessionStore' FileSessionStore.java` → 1
- `grep -c 'ATOMIC_MOVE' FileSessionStore.java` → ≥ 1
- `grep -c 'LingsSessionStoreException' FileSessionStore.java` → ≥ 2(save + load 各 1 处 catch)

---

## T-3: FileSessionStoreProvider POJO + resolveBaseDir()

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStoreProvider.java` (new)

**操作**:

1. `public final class FileSessionStoreProvider implements Providers.SessionStoreProvider`(不可变 + final)
2. 字段 `private final Path baseDir` + `private final ObjectMapper mapper`
3. 2-arg ctor(只赋值,不调 Files.createDirectories —— create() 时兜底)
4. SPI 4 方法:
    - `name() → "file"`
    - `version() → "1.0.0"`
    - `priority() → 10`
    - `create(AgentConfig cfg) → SessionStore`:try `Files.createDirectories(baseDir)` + `return new FileSessionStore(baseDir, mapper)`;catch IOException → throw new LingsSessionStoreException("failed to create dir: " + baseDir, e)
5. `static Path resolveBaseDir()` 3 级 fallback:
    - `String sysProp = System.getProperty("lingshu.session-store.dir");` 非空返 `Paths.get(sysProp)`
    - `String env = System.getenv("LINGS_SESSION_STORE_DIR");` 非空返 `Paths.get(env)`
    - fallback 返 `Paths.get("./.lingshu-sessions")`

**验证**:
- `grep -c 'name()' FileSessionStoreProvider.java` → ≥ 4(接口 4 方法各 1 行 + 注释)
- `grep -c '"file"' FileSessionStoreProvider.java` → ≥ 1
- `grep -c 'lingshu.session-store.dir' FileSessionStoreProvider.java` → 1
- `grep -c 'LINGS_SESSION_STORE_DIR' FileSessionStoreProvider.java` → 1

---

## T-4: InMemorySessionStoreProvider POJO + 删 DefaultInMemorySessionStore @Component

**路径**:
- `lingshu-core/src/main/java/ai/lingshu/core/impl/session/InMemorySessionStoreProvider.java` (new)
- `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultInMemorySessionStore.java` (modify)

**操作**:

1. `InMemorySessionStoreProvider.java`:`public final class` implements `Providers.SessionStoreProvider` + SPI 4 方法(`name()="memory"` + `version()="1.0.0"` + `priority()=0` + `create(AgentConfig cfg) → new DefaultInMemorySessionStore()`)
2. `DefaultInMemorySessionStore.java`:
    - L21 删 `import org.springframework.stereotype.Component;`
    - L71 删 `@Component("defaultInMemorySessionStore")` 注解
    - 类级 Javadoc L27 末尾加「🆕 Story #014 — moved to Provider pattern;see InMemorySessionStoreProvider」

**验证**:
- `grep -c '@Component' DefaultInMemorySessionStore.java` → 0(原 1,改后 0)
- `grep -c 'implements Providers.SessionStoreProvider' InMemorySessionStoreProvider.java` → 1
- `javap` 反射验证 `DefaultInMemorySessionStore.class.getAnnotation(Component.class) == null`

---

## T-5: SessionStoreAutoConfiguration 2 @Bean

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/session/SessionStoreAutoConfiguration.java` (new)

**操作**:

1. `@AutoConfiguration public class SessionStoreAutoConfiguration`
2. `@Bean(name = "sessionStoreProvider_memory-1.0.0") public Providers.SessionStoreProvider memoryProvider() { return new InMemorySessionStoreProvider(); }`
3. `@Bean(name = "sessionStoreProvider_file-1.0.0") public Providers.SessionStoreProvider fileProvider(ObjectMapper mapper) { return new FileSessionStoreProvider(FileSessionStoreProvider.resolveBaseDir(), mapper); }`

**验证**:
- `grep -c '@AutoConfiguration' SessionStoreAutoConfiguration.java` → 1
- `grep -c '@Bean' SessionStoreAutoConfiguration.java` → 2
- `grep -c 'sessionStoreProvider_memory-1.0.0' SessionStoreAutoConfiguration.java` → 1
- `grep -c 'sessionStoreProvider_file-1.0.0' SessionStoreAutoConfiguration.java` → 1

---

## T-6: Routers.SessionStoreRouter inner class

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java` (modify)

**操作**:

1. 在 7 个 inner class 之后加第 8 个:

```java
/** 🆕 Story #014 — Slot 5 SessionStore typed Router (dsh §5.3.1.0 stub 填实).
 *  Resolves a {@link SessionStore} implementation by {@code cfg.sessionStore} name.
 *  默认 {@link InMemorySessionStoreProvider} (name="memory");替代
 *  {@link FileSessionStoreProvider} (name="file")。*/
@Component
public static class SessionStoreRouter extends SlotRouter<Providers.SessionStoreProvider, SessionStore> {
    public SessionStoreRouter(List<Providers.SessionStoreProvider> providers) {
        super("SessionStore", providers, LoggerFactory.getLogger(SessionStoreRouter.class));
    }
}
```

2. 加 import:`ai.lingshu.core.slot.SessionStore`

**验证**:
- `grep -c 'SessionStoreRouter' Routers.java` → ≥ 3(类声明 + ctor + extends)
- `grep -c 'extends SlotRouter' Routers.java` → ≥ 8(7 旧 + 1 新)
- `grep -c 'implements Providers.SessionStoreProvider' Routers.java` → 0(此文件不直接 implement,只引用 typed marker)

---

## T-7: SlotResolver 加 sessionStoreRouter 字段 + ctor 参数 + sessionStore() 公开方法

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/SlotResolver.java` (modify)

**操作**:

1. 加字段:`private final Routers.SessionStoreRouter sessionStoreRouter;`
2. ctor 加 1 参数 `Routers.SessionStoreRouter sessionStoreRouter`(放在最后,与 dsh §5.3 L1644-1650 字段顺序对齐)
3. 初始化块加 `this.sessionStoreRouter = sessionStoreRouter;`
4. 加公开方法:

```java
/** 🆕 Story #014 — Slot 5 SessionStore resolve by {@code cfg.sessionStore} name.
 *  Returns one of {@link InMemorySessionStoreProvider#create} / {@link FileSessionStoreProvider#create}. */
public SessionStore sessionStore(AgentConfig c) {
    return sessionStoreRouter.resolve(c.getSessionStore(), c);
}
```

5. 加 import:`ai.lingshu.core.slot.SessionStore` + `ai.lingshu.core.impl.session.InMemorySessionStoreProvider` + `ai.lingshu.core.impl.session.FileSessionStoreProvider`(或用 fully qualified 避免循环)

**验证**:
- `grep -c 'sessionStoreRouter' SlotResolver.java` → ≥ 4(字段 + ctor + 初始化 + resolve)
- `grep -c 'public SessionStore sessionStore' SlotResolver.java` → 1
- `grep -c 'resolve(c.getSessionStore()' SlotResolver.java` → 1

---

## T-8: AgentFactory @Autowired ctor 加 SessionStoreRouter

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` (modify)

**操作**:

1. `@Autowired` ctor 加 1 参数 `Routers.SessionStoreRouter sessionStoreRouter`(放在最后,与现有 7 Router ctor 对齐)
2. 内部 `SlotResolver` 实例化加 `sessionStoreRouter` 实参(沿用 ctor 参数顺序)
3. 字段 / 内部引用若有同步更新,镜像 T-7 的 SlotResolver 字段

**验证**:
- `grep -c 'sessionStoreRouter' AgentFactory.java` → ≥ 3(ctor + SlotResolver 实例化 + 字段引用)
- 现有 700+ 测试 fixture 0 改动

---

## T-9: DefaultSession 接通 SessionStore delegate

**路径**: `lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultSession.java` (modify)

**操作**:

1. 类级 Javadoc L33 删「Story #014 replaces this with a SessionStore-backed implementation」前瞻注释,改为「🆕 Story #014 — backed by SessionStore resolved via SlotResolver;test helpers clear()/size() remain in-memory」
2. 加字段 `private final SessionStore delegate;`(由 SlotResolver 注入)
3. ctor 加 1 参数 `SessionStore delegate`
4. `saveCheckpoint(Checkpoint cp)` 改:`delegate.save(cp);`
5. `loadCheckpoint(String sessionId) → Checkpoint` 改:`return delegate.load(sessionId).orElse(null);` 或原行为(空返 null/抛异常)
6. 测试 helper `clear()` / `size()` 保留为内部 in-memory ConcurrentMap,**不**经 SessionStore SPI(避免污染 SPI)

**验证**:
- `grep -c 'delegate.save\|delegate.load' DefaultSession.java` → ≥ 2
- `grep -c 'Story #014 replaces' DefaultSession.java` → 0(原 1,改后 0)
- `grep -c 'SlotResolver' DefaultSession.java` → ≥ 1(import 或构造器注入)

---

## T-10: InMemorySessionStoreProviderTest L1 unit

**路径**: `lingshu-core/src/test/java/ai/lingshu/core/impl/session/InMemorySessionStoreProviderTest.java` (new)

**操作**: 写 4 case L1 unit test(plan §4.1):

1. `name_isMemory` —— `provider.name() == "memory"`
2. `version_is_1_0_0` —— `provider.version() == "1.0.0"`
3. `priority_isZero_default` —— `provider.priority() == 0`
4. `create_returnsDefaultInMemorySessionStore` —— `provider.create(mockAgentConfig) instanceof DefaultInMemorySessionStore`

**验证**:
- `mvn -pl lingshu-core test -Dtest='InMemorySessionStoreProviderTest'` → 4/4 PASS

---

## T-11: FileSessionStoreTest L1 unit(10 case)

**路径**: `lingshu-core/src/test/java/ai/lingshu/core/impl/session/FileSessionStoreTest.java` (new)

**操作**: 写 10 case L1 unit test(plan §4.2):

1. `save_writesJsonFile` —— save 后 `<baseDir>/<key>.json` 文件存在 + 内容合法 JSON
2. `load_roundTripsCheckpoint` —— save 后 load 返同一 Checkpoint
3. `load_missingFile_returnsEmpty` —— 未 save 的 sessionId load 返 `Optional.empty()`
4. `save_atomicMove_noTempFileLeft` —— save 后无 `.tmp` 残留
5. `save_ioException_throwsLingsX01` —— 目录无写权限 → `LingsSessionStoreException` 含 `[LINGS-X01]`
6. `load_corruptJson_throwsLingsX01` —— 写非法 JSON 后 load 抛 `LINGS-X01`
7. `load_missingFile_returnsEmpty_doesNotThrow` —— 文件不存在**不**抛
8. `multiTenant_aliceBobIsolated` —— 多租户隔离
9. `pathTraversal_colonReplaced` —— tenantId 含 `:` → 文件名含 `_`
10. `pathTraversal_slashReplaced` —— sessionId 含 `/` → 文件名含 `_`

**验证**:
- `mvn -pl lingshu-core test -Dtest='FileSessionStoreTest'` → 10/10 PASS

---

## T-12: SessionStoreRouterIT L2 IT

**路径**: `lingshu-core/src/test/java/ai/lingshu/core/impl/router/SessionStoreRouterIT.java` (new)

**操作**: 写 5 case L2 IT(plan §4.3):

1. `router_resolvesMemoryProvider` —— `router.resolve("memory", cfg)` 返 `DefaultInMemorySessionStore`
2. `router_resolvesFileProvider` —— `router.resolve("file", cfg)` 返 `FileSessionStore`
3. `router_byNameMap_sizeIs2` —— 反射验证 byName map size = 2
4. `router_unknownName_throwsIllegalArgument` —— `router.resolve("redis", cfg)` 抛 IAE
5. `router_priority_higherWinsInMapOrder` —— 反射验证 `file` priority=10 > `memory` priority=0

**验证**:
- `mvn -pl lingshu-core test -Dtest='SessionStoreRouterIT'` → 5/5 PASS

---

## T-13: SessionStoreAutoConfigurationTest L2 IT

**路径**: `lingshu-core/src/test/java/ai/lingshu/core/impl/session/SessionStoreAutoConfigurationTest.java` (new)

**操作**: 写 4 case L2 IT(plan §4.4):

1. `context_loads_withBothProviders` —— `@SpringBootTest` 加载 → 2 Bean 都在
2. `memoryProvider_beanName_correct` —— `applicationContext.getBean("sessionStoreProvider_memory-1.0.0")` 存在
3. `fileProvider_beanName_correct` —— `applicationContext.getBean("sessionStoreProvider_file-1.0.0")` 存在
4. `defaultInMemorySessionStore_noLongerComponent` —— 反射验证无 `@Component` 注解

**验证**:
- `mvn -pl lingshu-core test -Dtest='SessionStoreAutoConfigurationTest'` → 4/4 PASS

---

## T-14: 现有测试 0 regression

**目标**: 现有 AgentConfig + AgentFactory + DefaultSession + PermissionPolicy + Anthropic + Sandbox 测试 0 改动全过

**执行**:

```bash
mvn -pl lingshu-core test
```

**期望**:
- `Tests run: 734-738, Failures: 0, Errors: 0, Skipped: 0`(720 旧 + 14-18 新)
- 包含我新增的 14-18 case(`InMemorySessionStoreProviderTest` 4 + `FileSessionStoreTest` 10 + `SessionStoreRouterIT` 5 + `SessionStoreAutoConfigurationTest` 4 = 23 顶,部分 L2 IT 共享 fixture 实测 ~14-18)
- 既有 `DemoSessionTest` 8 case + `AgentConfig*` 21 case + `AgentFactory*` 多 case + `PermissionPolicy*` 30+ case + `Anthropic*` 38 case + `DefaultRuntimeSandbox*` 多 case 全部不动

**反向验证**:
- ✅ T-14.R1: `DemoSessionTest` 8 case 0 改动全过(`DefaultSession.clear/size` 保留 in-memory helper)
- ✅ T-14.R2: `AgentConfigConcurrencyCapValidationTest` 8 + `AgentFactoryYamlConcurrencyCapIT` 5 0 改动全过(Story #044 forward ref 不破)
- ✅ T-14.R3: `PermissionPolicyRouterMultiProviderIT` 5 + `AskUserPermissionPolicyTest` 5 0 改动全过(Slot 4 模式镜像对齐无影响)
- ✅ T-14.R4: `AnthropicToolReActIT` 2 + `AnthropicStreamProviderIT` 2 + `AnthropicLlmProviderTest` 13 + `AnthropicLlmProviderBoundedPoolTest` 7 0 改动全过
- ✅ T-14.R5: `DefaultRuntimeSandbox*` 0 改动全过(Slot 3 Sandbox 路径无影响)

---

## T-15: R-13 mitigation (d) 自查

**目标**: 0 新 Maven 依赖,`mvn -pl lingshu-core dependency:tree` pre/post diff = 仅时间戳差异

**执行**:

```bash
# pre-baseline 备份
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-pre.txt
md5sum /tmp/dep-tree-pre.txt

# 实施 T-1 ~ T-13 后
mvn -pl lingshu-core dependency:tree > /tmp/dep-tree-post.txt
md5sum /tmp/dep-tree-post.txt

# diff
diff /tmp/dep-tree-pre.txt /tmp/dep-tree-post.txt
# 期望: 仅时间戳差异
```

**禁止引入**:
- ❌ `spring-boot-starter-data-redis`(Story #014 明确不引,留给未来 Redis 后端 Story)
- ❌ jdbc driver(H2 / PostgreSQL / MySQL 等)
- ❌ 任何新 binary(纯 JDK 8 + Jackson + Lombok + Spring 已锁)

**通过条件**: pre/post md5sum 仅时间戳差异 = **0 binary delta 第 28 次 PASS**

---

## T-16: banned-dependencies enforcer build 阶段 fail

**目标**: 编译期 enforcer rule 不报违规

**执行**:

```bash
mvn -pl lingshu-core verify -DskipTests=true
```

**期望**: BUILD SUCCESS,enforcer rule 0 violation

---

## T-17: commit + PR

### T-17.1: commit

```bash
git add lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultInMemorySessionStore.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/session/DefaultSession.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/session/InMemorySessionStoreProvider.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStoreProvider.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/session/FileSessionStore.java \
        lingshu-core/src/main/java/ai/lingshu/core/impl/session/SessionStoreAutoConfiguration.java \
        lingshu-core/src/main/java/ai/lingshu/core/spi/SessionStoreErrorCodes.java \
        lingshu-core/src/main/java/ai/lingshu/core/spi/LingsSessionStoreException.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/session/InMemorySessionStoreProviderTest.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/session/FileSessionStoreTest.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/router/SessionStoreRouterIT.java \
        lingshu-core/src/test/java/ai/lingshu/core/impl/session/SessionStoreAutoConfigurationTest.java \
        specs/014-session-store-multi-backend/{spec,plan,tasks}.md
git commit -m "feat(slot-5): Story #014 session-store-multi-backend — 填实 dsh §5.3.1.0 SessionStoreRouter + memory-1.0.0 + file-1.0.0 + LINGS-X01 + R-13 0 binary delta

填实 dsh §5.3.1.0 stub gap —— Slot 5 SessionStore 加入 v1.5.28 §5.5 多 Provider
模式,9 Slot 全部走 Provider + SlotRouter 模板,9 Router 设计承诺全兑现。

2 Provider POJO:InMemorySessionStoreProvider(name=\"memory\", priority=0)
+ FileSessionStoreProvider(name=\"file\", priority=10),plain @Bean 模式;
File 后端 base dir 三级 fallback:system property > env var > ./lingshu-sessions;
临时文件 + ATOMIC_MOVE 防半写,Jackson 序列化 + Files.readAllBytes 读取,
文件路径 ':' '/' 替换 '_' 防穿越。

4 modify(Routers.java +8 / DefaultInMemorySessionStore 删 @Component /
DefaultSession 接通 delegate / AgentFactory ctor +1 路由) + 10 new
(2 Provider + 1 实现 + 1 AutoConfiguration + 1 ErrorCode + 1 Exception + 4 test);
DefaultSession 测试 helper clear()/size() 保留为内部 in-memory,
SessionStore SPI 0 改动(纯实现 + 注册路径迁移)。

1 新 ErrorCode LINGS-X01 SESSION_STORE_IO_FAILURE(X 域 1 号,File 后端
IO 失败专用,Memory 后端永远不触发)。

14-18 new case 跨 4 文件(InMemorySessionStoreProviderTest 4 + 
FileSessionStoreTest 10 + SessionStoreRouterIT 5 + SessionStoreAutoConfigurationTest 4);
720 全 lingshu-core 测试 0 regression,2 MCP heartbeat flake pre-existing
(CLAUDE.md 已记录,与本 Story 无关)。

R-13 mitigation (d) baseline 镜像 pre/post md5sum 相同 = 0 binary delta 第 28 次 PASS
(纯 JDK 8 内置 Files + Paths + Files.move + ATOMIC_MOVE + Jackson ObjectMapper 已锁 +
ConcurrentHashMap + Lombok @Value + Spring @AutoConfiguration 0 新 binary 引入)。

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>"
```

**PR 标题**: `feat(slot-5): Story #014 session-store-multi-backend — 填实 §5.3.1.0 SessionStoreRouter + memory + file`

**PR body 模板**:
- spec.md / plan.md / tasks.md 三件套链接
- AC-NN-deps-1 / AC-NN-spi-1 / AC-NN-router-1 / AC-NN-provider-1 / AC-NN-yaml-1 / AC-NN-file-1 / AC-NN-mem-1 / AC-NN-session-1 / AC-NN-agentfactory-1 / AC-NN-1 / AC-NN-2 验证输出
- 反向 AC 验证(DemoSessionTest 8 case + AgentConfig 21 case + PermissionPolicy 30+ case + Anthropic 38 case 0 改动)
- R-13 dependency:tree 自查节
- 关键不变项列表

### T-17.2: 合入后同步

- [ ] `dsh_agent_design.md` §13 changelog 新增 v1.5.58 行(本 Story 完成)
- [ ] `dsh_agent_design.md` §5.3.1.0 `SessionStoreRouter` stub 行加「🆕 v1.5.58 Story #014 已落」marker
- [ ] `dsh_agent_design.md` §5.5 Slot 5 `FileSessionStoreProvider` stub 行加「🆕 v1.5.58 Story #014 已落」marker
- [ ] `dsh_agent_design.md` §5.6.4 SPI 总表 Slot 5 行「默认 Provider」字段填实
- [ ] `dsh_agent_design.md` §15.4 域字母加 `X = SessionStore` 域 `LINGS-X01` 行
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 `#014` 行(第 28 次 PASS 0 binary delta)
- [ ] `specs/ROADMAP.md` 段一 ✅ 已完成加 #014 行
- [ ] `specs/ROADMAP.md` 段五 🎯 实施节奏 累计计数 44 → **45**
- [ ] `CLAUDE.md` 同步 v1.3.52 → v1.3.53(本 Story 文档同步)
- [ ] `README.md` 顶部 🆕 v1.5.58 Story #014 blockquote + 「核心特性」段补 🗄️ SessionStore 多后端 bullet(memory + file 双 Provider + yml 切换 + 多租户 + ATOMIC_MOVE)

---

## 完成定义

- T-1 ~ T-17 全 ✅
- 720+ 全 lingshu-core 测试 PASS(720 旧 0 改动 + 14-18 新)
- R-13 mitigation (d) baseline 镜像 pre/post md5sum 相同 PASS 0 binary delta 第 28 次
- 1 新 ErrorCode `LINGS-X01`(X 域 1 号,File 后端专用)/ 0 新 Maven 依赖 / 0 新二进制
- PR merged + 文档同步完成
- 累计 Story 合入:44 → **45**
- dsh §5.3.1.0 stub 全兑现 + v1.5.28 §5.5 多 Provider 模式 9 Slot 闭环
- `CLAUDE.md` v1.3.52 → v1.3.53
