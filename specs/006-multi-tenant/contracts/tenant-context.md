# Contract: TenantContext + TenantConfigProvider

**Branch**: `story-006-multi-tenant` | **Date**: 2026-09-21
**Purpose**: 完整契约 — 接口签名 / 不变量 / 错误码 / 版本兼容

---

## 1. `TenantContext` 契约(static utility)

### 1.1 方法签名

```java
package ai.lingshu.core.tenant;

public final class TenantContext {

    // ── Current state ─────────────────────────────────────────
    /** 返当前栈顶 tenantId,栈空返 null。 */
    public static String current();

    /** 压栈 set tenant。tenantId 非 null + 匹配 [a-zA-Z0-9_-]{1,64}。 */
    public static void set(String tenantId);

    /** 弹栈移除栈顶(等价 set(null))。栈空时 no-op。 */
    public static void clear();

    // ── Cross-thread transfer ─────────────────────────────────
    /** 拿当前 tenantId immutable 拷贝。current() == null 时返 null。 */
    public static String snapshot();

    /** 子线程 runWithSnapshot: set + try-finally + clear。snapshot 非 null。 */
    public static <T> T runWithSnapshot(String snapshot, Supplier<T> work);

    // ── Block scope (try-finally 兜底) ────────────────────────
    /** 包裹 supplier: set("alice") + supplier.get() + clear()。 */
    public static <T> T runAs(String tenantId, Supplier<T> work);

    /** 包裹 runnable: set("alice") + runnable.run() + clear()。 */
    public static void runAs(String tenantId, Runnable work);

    // ── Internal state ────────────────────────────────────────
    /** per-thread 嵌套栈。 */
    private static final ThreadLocal<Deque<String>> STACK = ...;
}
```

### 1.2 不变量

| # | 不变量 | 说明 |
|---|---|---|
| I-1 | `STACK.get().isEmpty() ⟺ current() == null` | 栈空等价无租户 |
| I-2 | 嵌套 `runAs(a, () -> runAs(b, ...))` 不污染外层 | 内层结束弹栈后,外层回调仍看到 a |
| I-3 | `runAs` 内 Runnable 抛 RuntimeException 也触发 clear | try-finally 兜底(R-02 (a)) |
| I-4 | 子线程默认 `current() == null` | ThreadLocal 跨线程不传递 — 必须 `runWithSnapshot` 显式还原 |
| I-5 | `snapshot()` 返 immutable 拷贝 | `String` 本身不可变,等同引用拷贝 |
| I-6 | tenantId 校验失败立即抛异常,不污染栈 | 校验失败时不调 `STACK.set(...)`,栈状态不变 |

### 1.3 异常

| 异常 | 触发条件 |
|---|---|
| `IllegalArgumentException` | tenantId 为 null / 空字符串 / 不匹配 `[a-zA-Z0-9_-]{1,64}` |
| `IllegalArgumentException`(runWithSnapshot)| snapshot 为 null(等同"无租户上下文",不允许显式传 null)|

### 1.4 性能契约

| 方法 | 性能开销 |
|---|---|
| `current()` | O(1) — 1 次 ThreadLocal.get + 1 次 peekLast |
| `set(String)` | O(1) amortized — 1 次 ThreadLocal.set + 1 次 addLast(ArrayDeque 扩容 16 → 32 → ...)|
| `clear()` | O(1) — 1 次 ThreadLocal.set + 1 次 pollLast |
| `runAs(null, noop)` | 纳秒级 — 总计 4 次 ThreadLocal 访问 + 2 次 Deque 操作 |
| `snapshot()` | O(1) — 1 次 ThreadLocal.get + 1 次 peekLast |
| `runWithSnapshot(snap, noop)` | 同 runAs |

### 1.5 线程安全

- **类内无共享状态**(static `ThreadLocal<Deque<String>>` 是 per-thread)
- **无 synchronized / CAS / Atomic** — 单线程访问 Deque 无并发场景
- **多线程并发** 不同线程互不影响(per-thread 隔离)
- **同线程多 runAs 嵌套** 顺序栈式保存(I-2)

---

## 2. `TenantConfig` 契约(immutable @Value)

### 2.1 字段签名

```java
package ai.lingshu.core.tenant;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.List;

@Value
@Builder
public class TenantConfig {
    /** 租户 ID,匹配 [a-zA-Z0-9_-]{1,64} */
    String tenantId;

    /** tenant memory 配置 */
    Memory memory;

    /** tenant sandbox 配置 */
    Sandbox sandbox;

    /** tenant cost 配置 */
    Cost cost;

    @Value
    @Builder
    public static class Memory {
        /** tenant memory 根目录,绝对路径 */
        Path dir;
    }

    @Value
    @Builder
    public static class Sandbox {
        /** 允许执行的命令名(如 ["ls", "cat"]),空 list 表示全拒 */
        List<String> commandWhitelist;
    }

    @Value
    @Builder
    public static class Cost {
        /** 单 session budget(微美元),1 USD = 1_000_000 micros,正数 */
        long sessionBudgetMicros;
    }
}
```

### 2.2 不变量

| # | 不变量 | 说明 |
|---|---|---|
| I-1 | 所有字段 final(Lombok @Value)| 不可变,无 setter |
| I-2 | `commandWhitelist` 是 immutable list | 构造时 `Collections.unmodifiableList(new ArrayList<>(list))` 深拷贝 |
| I-3 | `tenantId` 必填 | null / 空 / 不匹配正则 → `IllegalArgumentException` |
| I-4 | `memory.dir` 必填 | null → `IllegalArgumentException` |
| I-5 | `cost.sessionBudgetMicros` 必填 > 0 | <= 0 → `IllegalArgumentException` |

### 2.3 异常

| 异常 | 触发条件 |
|---|---|
| `IllegalArgumentException`(构造)| tenantId / memory / sandbox / cost 任一字段 null 或校验失败 |

---

## 3. `TenantConfigProvider` 契约(SPI interface)

### 3.1 接口签名

```java
package ai.lingshu.core.tenant;

import ai.lingshu.core.spi.ContractVersionRef;

import java.util.List;
import java.util.Optional;

/**
 * SPI — multi-tenant config provider (dsh §14.9).
 *
 * <p>Multiple providers may coexist (dsh §5.28 multi-Provider mode).
 * Each provider's {@link #name()} must be unique across the JVM.
 * Higher {@link #priority()} wins when multiple providers resolve the
 * same tenantId.
 */
public interface TenantConfigProvider {

    /** 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /** Stable identifier — must be unique across all registered providers. */
    String name();

    /** Higher priority wins when multiple providers resolve the same tenantId. */
    int priority();

    /**
     * Resolve the TenantConfig for the given tenantId.
     *
     * @return Optional.of(config) if tenant exists in this provider's source;
     *         Optional.empty() otherwise (caller may try next-priority provider)
     */
    Optional<TenantConfig> resolve(String tenantId);

    /**
     * List all tenantIds known to this provider.
     *
     * <p>Used for ops introspection (description endpoint) and for the
     * agent.tenants map size validation at startup.
     */
    List<String> listTenantIds();
}
```

### 3.2 不变量

| # | 不变量 | 说明 |
|---|---|---|
| I-1 | `name()` 全 JVM 唯一 | 重复 → `BeanDefinitionOverrideException`(Spring Boot 2.1+)|
| I-2 | `resolve(tid)` 幂等 | 同 tid 多次调返同一配置对象 |
| I-3 | `resolve(tid)` 不存在返 `Optional.empty()` | 不抛异常,允许上层 fallback |
| I-4 | `listTenantIds()` 返不可变 list | 防外部 mutation |

### 3.3 多 Provider 路由

| 场景 | 行为 |
|---|---|
| 1 个 Provider,resolve(tid) 返 config | 用该 config |
| 1 个 Provider,resolve(tid) 返 empty | `LINGS-S01 SLOT_NOT_FOUND`(tenant 不存在)|
| N 个 Provider,tid 在 P1+P2 都命中 | `priority` 高者胜出(P1)|
| N 个 Provider,priority 相同 | 启动期 fail-fast `BeanDefinitionOverrideException` |
| N 个 Provider,全部 resolve(tid) 返 empty | `LINGS-S01 SLOT_NOT_FOUND` |

### 3.4 默认实现 `YamlTenantConfigProvider`

```java
package ai.lingshu.core.impl.tenant;

@Component("tenantConfigProvider_yaml")
public class YamlTenantConfigProvider implements TenantConfigProvider {

    private final Map<String, TenantConfig> configs;

    @Autowired
    public YamlTenantConfigProvider(AgentConfig config) {
        this.configs = config.getTenants() != null
            ? config.getTenants().getMap()
            : Collections.emptyMap();
    }

    @Override public String name() { return "yaml"; }
    @Override public int priority() { return 10; }

    @Override
    public Optional<TenantConfig> resolve(String tenantId) {
        return Optional.ofNullable(configs.get(tenantId));
    }

    @Override
    public List<String> listTenantIds() {
        return Collections.unmodifiableList(new ArrayList<>(configs.keySet()));
    }
}
```

---

## 4. `TenantsConfig` 契约(nested in AgentConfig)

### 4.1 字段签名

```java
@Value
@Builder
public static class TenantsConfig {
    /** 多租户模式开关:true if map.size() > 0 */
    boolean enabled;

    /** tenantId → TenantConfig 映射 */
    Map<String, TenantConfig> map;

    /**
     * 启动期校验 — fail-fast on misconfig (FR-010).
     *
     * @throws LingsConfigException("C02", ...) if any field invalid
     */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (map.size() > 1000) {
            errors.add("tenants.map.size() = " + map.size() + " exceeds limit 1000");
        }
        for (Map.Entry<String, TenantConfig> e : map.entrySet()) {
            String tid = e.getKey();
            TenantConfig tc = e.getValue();
            if (!tid.matches("[a-zA-Z0-9_-]{1,64}")) {
                errors.add("tenants." + tid + ": invalid tenantId format");
            }
            if (tc == null) {
                errors.add("tenants." + tid + ": config is null");
            } else if (!tid.equals(tc.getTenantId())) {
                errors.add("tenants." + tid + ": key/value tenantId mismatch");
            } else {
                if (tc.getMemory() == null || tc.getMemory().getDir() == null) {
                    errors.add("tenants." + tid + ".memory.dir is required");
                }
                if (tc.getSandbox() == null || tc.getSandbox().getCommandWhitelist() == null) {
                    errors.add("tenants." + tid + ".sandbox.command-whitelist is required");
                }
                if (tc.getCost() == null || tc.getCost().getSessionBudgetMicros() <= 0) {
                    errors.add("tenants." + tid + ".cost.session-budget-micros must be > 0");
                }
            }
        }
        if (!errors.isEmpty()) {
            throw new LingsConfigException("C02",
                "tenants config validation failed:\n  - " + String.join("\n  - ", errors));
        }
    }

    public static TenantsConfig defaults() {
        return new TenantsConfig(false, Collections.emptyMap());
    }
}
```

### 4.2 不变量

| # | 不变量 | 说明 |
|---|---|---|
| I-1 | `map.size() <= 1000` | 防 DoS |
| I-2 | key == value.tenantId | 防 key/value 错位 |
| I-3 | 每 tenant 必填字段齐 | memory.dir / sandbox.commandWhitelist / cost.sessionBudgetMicros |

### 4.3 异常

| 异常 | 触发条件 |
|---|---|
| `LingsConfigException("C02", ...)` | validate() 失败,message 列出所有错误字段路径 |

---

## 5. `AgentConfig.tenants` 字段契约(modify)

### 5.1 修改内容

```java
@Value
public class AgentConfig {
    // ... 27+ existing fields unchanged ...

    /** 🆕 Story #006 — multi-tenant config map; defaults to empty (single-tenant mode). */
    TenantsConfig tenants;

    // ... nested classes ...

    @Value
    @Builder
    public static class TenantsConfig {
        boolean enabled;
        Map<String, TenantConfig> map;
        public void validate() { ... }
        public static TenantsConfig defaults() { ... }
    }
}
```

### 5.2 向后兼容

- 既有 27+ 字段**不**改
- 新增字段 `tenants` 是 nullable(`TenantsConfig.defaults()` 返 `enabled=false, map={}`)
- yml 不配 `agent.tenants` → `tenants == null` → 单租户模式(US6 S3 + NFR-009)
- yml 配 `agent.tenants: {}`(空 map)→ `enabled=false` → 单租户模式
- yml 配 `agent.tenants.alice: {...}` → `enabled=true, map={alice: {...}}` → 多租户模式

### 5.3 YAML 绑定示例

```yaml
agent:
  # ... 27+ existing fields ...
  tenants:
    alice:
      memory:
        dir: /var/lib/lingshu/tenants/alice/memory
      sandbox:
        command-whitelist: [ls, cat, echo]
      cost:
        session-budget-micros: 10000000  # USD 10
    bob:
      memory:
        dir: /var/lib/lingshu/tenants/bob/memory
      sandbox:
        command-whitelist: [ls, cat, git, npm]
      cost:
        session-budget-micros: 100000000  # USD 100
```

---

## 6. `AgentFactory.create(cfg)` 启动期校验契约(modify)

### 6.1 启动期校验三件套(FR-010)

```java
public Agent create(AgentConfig config) {
    validate(config);  // 既有 7 项校验(Story #001)
    validateTenants(config);  // 🆕 Story #006 — 三件套校验
    // ... resolve slots ...
}

private static void validateTenants(AgentConfig config) {
    if (config.getTenants() == null) return;  // 单租户模式,跳过
    config.getTenants().validate();  // C02 校验(§4.3)
}
```

### 6.2 校验细节

| 检查项 | 失败抛 |
|---|---|
| `config.getTenants() != null && map.size() > 1000` | `LINGS-C02 CONFIG_VALIDATION_FAILED` "tenants.map.size() = N exceeds limit 1000" |
| `tenants.<tid>` 任一 TenantConfig 字段缺失 | `LINGS-C02` "tenants.<tid>.<field> is required" |
| tenantId 格式不匹配正则 | `LINGS-C02` "tenants.<tid>: invalid tenantId format" |
| key != value.tenantId | `LINGS-C02` "tenants.<tid>: key/value tenantId mismatch" |

### 6.3 运行时校验(FR-011)

```java
// LinearTurnEngine.runTurn 入口
public void runTurn(TurnContext ctx, Subscriber<? super AgentEvent> sink) {
    // 🆕 Story #006 — tenants 启用时必须 TenantContext 上下文
    if (ctx.config().getTenants() != null
        && ctx.config().getTenants().isEnabled()
        && TenantContext.current() == null) {
        throw new LingsConfigException("C02",
            "tenants configured but turn started without TenantContext.runAs — " +
            "wrap call site in TenantContext.runAs(\"<tenantId>\", () -> ...)");
    }
    // ... existing ReAct loop ...
}
```

---

## 7. 跨线程传递契约

### 7.1 显式 snapshot + runWithSnapshot 模式

```java
// 主线程
TenantContext.runAs("alice", () -> {
    // 1. 业务逻辑 → 提交到 ExecutorService
    String snapshot = TenantContext.snapshot();  // "alice"
    executor.submit(() -> {
        // 2. 子线程 → 显式还原
        TenantContext.runWithSnapshot(snapshot, () -> {
            // 当前线程的 TenantContext.current() == "alice"
            doWork();
        });
    });
});
```

### 7.2 错误示范(应避免)

```java
// ❌ 错误:子线程直接读 current()(无显式传递)
executor.submit(() -> {
    String tid = TenantContext.current();  // 永远 null
    // ...
});

// ❌ 错误:用 InheritableThreadLocal(本项目主动放弃)
private static final InheritableThreadLocal<String> ITL = new InheritableThreadLocal<>();
// 不推荐 — 隐蔽污染风险(R-02 (c))
```

---

## 8. 4 维隔离契约

| 维度 | 隔离点 | 实现 |
|---|---|---|
| **配置(Configuration)** | `AgentConfig` per-tenant | `TenantConfigProvider.resolve(tid)` 拿 TenantConfig |
| **Memory path** | per-tenant `Sandbox.workingDirectory` | ProjectClaudeMdSource / ProjectTreeMemorySource 读 `cfg.sandbox.workingDirectory` |
| **Session key** | SessionStore key 加 tenantId 前缀 | `key = tid + ":" + sessionId`(D-05 冒号分隔)|
| **Sandbox whitelist** | per-tenant `commandWhitelist` | `RuntimeSandbox.process.run(cmd, ...)` 内部 `provider.resolve(tid).sandbox().commandWhitelist()` |
| **Cost budget** | per-tenant `sessionBudgetMicros` | `CostTracker.accumulate(usage)` 按 tid 分桶 |

---

## 9. 错误码契约(NFR-004 0 新增)

| 异常类型 | 错误码 | 触发场景 |
|---|---|---|
| `LingsConfigException` | `LINGS-C02 CONFIG_VALIDATION_FAILED` | tenants 字段缺失 / 非法 / 超限 / turn 入口无 TenantContext |
| `LingsConfigException` | `LINGS-C03 CONFIG_TYPE_MISMATCH` | yml 字段类型不符(如 cost.sessionBudgetMicros: "abc") |
| `LingsSlotException` | `LINGS-S01 SLOT_NOT_FOUND` | TenantConfigProvider.resolve(tid) 全 empty(tid 不存在)|
| `LingsInternalException` | `LINGS-Z01 INTERNAL_PANIC` | tenants map 存在但 TenantConfig 为 null(invariant 违例)|

**0 新增 ErrorCode**(NFR-004) — tenant 是横切关注点,跨域复用体现设计意图。

---

## 10. 版本兼容契约

| 接口 | 版本(Story #006)| 是否可演进 |
|---|---|---|
| `TenantContext` | 1.0.0(隐式,首次发布)| static 方法签名演化需走 RFC,新增 static 方法 back-compat(同包新方法)|
| `TenantConfig` | 1.0.0(Lombok @Value 字段 final)| 字段新增 = 破坏性变更,需 RFC |
| `TenantConfigProvider` | 1.0.0(`@ContractVersionRef`)| 同 SPI 多 Provider 模式,新增 default 方法 back-compat |
| `YamlTenantConfigProvider` | 1.0.0 | 同 §5.5 多 Provider 样板,name="yaml" 固定 |
| `TenantsConfig` | 1.0.0 | 同 AgentConfig 嵌套模式,字段新增 = 破坏性变更 |
| `AgentConfig.tenants` | 1.0.0 | 新增字段 back-compat(yaml 不配 = null = 单租户模式)|

---

## 11. 测试契约

| 测试类 | 测试层 | 用例数 | 覆盖 FR |
|---|---|---|---|
| `TenantContextTest` | L1 Unit | 6 | FR-001 / FR-005 / FR-006 / FR-015 |
| `TenantConfigProviderTest` | L1 Unit | 3 | FR-008 |
| `MemoryPathIsolationTest` | L1 Unit | 2 | FR-003 + AC-05 |
| `CostBudgetIsolationTest` | L1 Unit | 2 | FR-012 + AC-05 |
| `SandboxWhitelistIsolationTest` | L1 Unit | 2 | FR-002 / FR-013 + AC-05 |
| `SessionKeyIsolationTest` | L1 Unit | 2 | FR-004 + AC-05 |
| `TenantConfigValidationTest` | L1 Unit | 2 | FR-010 / FR-016 |
| `TenantIsolationIT` | L5 E2E | 1 | AC-05 黑盒主路径 |
| **Total** | | **20** | 16 FR + 9 NFR + AC-05 |
