# Data Model: Story #006 multi-tenant

**Branch**: `story-006-multi-tenant` | **Date**: 2026-09-21
**Purpose**: TenantContext + TenantConfig + TenantConfigProvider 完整数据契约

---

## Entity Diagram

```
┌─────────────────────────────────────────────┐
│ Thread (per-thread isolation)               │
│  ┌───────────────────────────────────────┐  │
│  │ ThreadLocal<Deque<String>>            │  │
│  │  └─ ["alice", "bob"] ← 嵌套栈         │  │
│  │     ↑                                 │  │
│  │     TenantContext.current()           │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
                    │
                    ▼ 快照(snapshot)
            ┌─────────────────────┐
            │ String "alice"      │  ← 跨线程传递用 immutable 拷贝
            └─────────────────────┘
                    │
                    ▼ runWithSnapshot
┌─────────────────────────────────────────────┐
│ 子线程 ThreadLocal<Deque<String>>            │
│  └─ ["alice"] ← 显式还原                  │
└─────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│ TenantConfigProvider SPI (interface)                     │
│  ┌────────────────────────────────────────────────────┐  │
│  │  String name();  int priority();                   │  │
│  │  Optional<TenantConfig> resolve(String tenantId);  │  │
│  │  List<String> listTenantIds();                     │  │
│  └────────────────────────────────────────────────────┘  │
│           △ implements                                  │
│           │                                             │
│  ┌────────┴────────────────────────────────────────┐   │
│  │ @Component YamlTenantConfigProvider              │   │
│  │   name()="yaml" priority()=10                    │   │
│  │   resolve(tid) → map.get(tid)                    │   │
│  │   listTenantIds() → map.keySet()                 │   │
│  └─────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────┐
│ AgentConfig (@Value, Lombok)                      │
│  ├─ ... 27+ existing fields (Story #001—#005)     │
│  └─ TenantsConfig tenants  ← 🆕 Story #006        │
│                                                    │
│  @Value static class TenantsConfig {              │
│    boolean enabled;                                │
│    Map<String, TenantConfig> map;                  │
│    void validate() throws LingsConfigException;    │
│  }                                                 │
└────────────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────┐
│ TenantConfig (@Value, Lombok)                            │
│   String tenantId          ← 必填,匹配 [a-zA-Z0-9_-]{1,64}│
│   Memory memory           ← 嵌套                         │
│   Sandbox sandbox         ← 嵌套                         │
│   Cost cost               ← 嵌套                         │
│                                                           │
│   @Value static class Memory {                           │
│     Path dir;              ← 必填,tenant memory 根目录   │
│   }                                                      │
│                                                           │
│   @Value static class Sandbox {                          │
│     List<String> commandWhitelist;  ← 必填,可空 list    │
│   }                                                      │
│                                                           │
│   @Value static class Cost {                             │
│     long sessionBudgetMicros;  ← 必填,正数              │
│   }                                                      │
└──────────────────────────────────────────────────────────┘
```

---

## Entity Definitions

### Entity 1: `TenantContext`(static utility, no instance)

| Field/Method | Type | Description | Validation |
|---|---|---|---|
| `current()` | `static String` | 返当前 tenantId 或 `null`(栈空) | — |
| `set(String tenantId)` | `static void` | 压栈 set tenant | tenantId 非 null + 匹配 `[a-zA-Z0-9_-]{1,64}` |
| `clear()` | `static void` | 弹栈移除栈顶(等价 `set(null)`)| 栈空时 no-op |
| `snapshot()` | `static String` | 拿当前 tenantId immutable 拷贝(`String` 本身不可变,等同) | current() == null 时返 null |
| `runAs(String, Supplier<T>)` | `static <T> T` | set + try-finally + clear | tenantId 非空 + 校验同上 |
| `runAs(String, Runnable)` | `static void` | 同上 void 版 | 同上 |
| `runWithSnapshot(String, Supplier<T>)` | `static <T> T` | 子线程 set + try-finally + clear | snapshot 非 null |
| **Field** `STACK` | `static final ThreadLocal<Deque<String>>` | per-thread 嵌套栈 | ArrayDeque 实例 |

**Invariants**:
1. **栈式保存**:嵌套 runAs 不污染外层(US1 S4)
2. **try-finally 兜底**:即使 Runnable 抛 RuntimeException 也触发 clear(R-02 (a))
3. **跨线程不传递**:子线程默认 `current() == null`,必须用 `runWithSnapshot` 显式还原(FR-006 + R-02 (c))
4. **tenantId 校验**:null / 空字符串 / 不匹配 `[a-zA-Z0-9_-]{1,64}` 抛 `IllegalArgumentException`

### Entity 2: `TenantConfig`(immutable, Lombok `@Value`)

| Field | Type | Description | Required | Validation |
|---|---|---|---|---|
| `tenantId` | `String` | 租户 ID | ✅ | 匹配 `[a-zA-Z0-9_-]{1,64}` |
| `memory` | `TenantConfig.Memory` | 内存配置 | ✅ | dir 非 null + 绝对路径 |
| `sandbox` | `TenantConfig.Sandbox` | sandbox 配置 | ✅ | commandWhitelist 非 null |
| `cost` | `TenantConfig.Cost` | 成本配置 | ✅ | sessionBudgetMicros > 0 |

#### Entity 2.1: `TenantConfig.Memory`

| Field | Type | Description | Validation |
|---|---|---|---|
| `dir` | `Path` | tenant memory 根目录 | 非 null + `Files.isDirectory(dir) \|\| dir 不存在但可创建` |

#### Entity 2.2: `TenantConfig.Sandbox`

| Field | Type | Description | Validation |
|---|---|---|---|
| `commandWhitelist` | `List<String>` | 允许执行的命令(如 `["ls", "cat"]`)| 非 null(Lombok `@Value` 默认空 list) |

#### Entity 2.3: `TenantConfig.Cost`

| Field | Type | Description | Validation |
|---|---|---|---|
| `sessionBudgetMicros` | `long` | 单 session budget(微美元)| > 0 |

**Invariants**:
- Lombok `@Value` 不可变(无 setter,所有字段 final)
- `Map<String, TenantConfig>` 内部 List 引用深拷贝(防外部 mutation)

### Entity 3: `TenantConfigProvider`(SPI interface)

| Method | Return | Description |
|---|---|---|
| `name()` | `String` | Provider 标识(如 `"yaml"` / `"db"` / `"vault"`)|
| `priority()` | `int` | 优先级(多 Provider 共存时 priority 高胜出)|
| `resolve(String tenantId)` | `Optional<TenantConfig>` | 拿指定 tenant 配置;不存在返 `Optional.empty()` |
| `listTenantIds()` | `List<String>` | 列出所有已配置 tenantId(运维 / 健康检查用)|

**Contract version**: `@ContractVersionRef String CONTRACT_VERSION = "1.0.0"`(对齐 dsh §5.28 SPI 标准)

### Entity 4: `YamlTenantConfigProvider`(@Component, 默认实现)

| Field/Method | Type | Description |
|---|---|---|
| Field: `configs` | `Map<String, TenantConfig>` | 从 `AgentConfig.tenants.map` 注入 |
| `name()` | `String` | 返 `"yaml"` |
| `priority()` | `int` | 返 `10` |
| `resolve(tid)` | `Optional<TenantConfig>` | `Optional.ofNullable(configs.get(tid))` |
| `listTenantIds()` | `List<String>` | `new ArrayList<>(configs.keySet())` |

### Entity 5: `TenantsConfig`(nested in AgentConfig, Lombok `@Value`)

| Field | Type | Description | Validation |
|---|---|---|---|
| `enabled` | `boolean` | 多租户模式开关 | `true` if `map.size() > 0` else `false` |
| `map` | `Map<String, TenantConfig>` | tenantId → TenantConfig 映射 | 启动期 `validate()` 校验每个 TenantConfig 字段 |

**Method**:
- `validate()`:遍历 map 每个 TenantConfig → 检查 tenantId 匹配正则 + 每 tenant 必填字段齐 + `map.size() <= 1000`;缺字段抛 `LingsConfigException("C02", ...)` 含字段路径

### Entity 6: `AgentConfig`(modify, Lombok `@Value`)

**修改**:**只**新增字段 `TenantsConfig tenants`,既有 27+ 字段**不**改(向后兼容)

| Field | Type | Description |
|---|---|---|
| ... (27+ 既有字段) | | 不变 |
| `tenants` 🆕 | `TenantsConfig` | 多租户配置;`TenantsConfig.defaults()` 返 `new TenantsConfig(false, Collections.emptyMap())` |

---

## State Transitions

`TenantContext` 栈状态机:

```
┌─ 初始 ─┐
│ stack=[]│
│ current=null│
└────┬────┘
     │ runAs("alice", supplier)
     ▼
┌─ set alice ──────┐
│ stack=["alice"]  │
│ current="alice"  │
└────┬─────────────┘
     │ supplier.run() 内部调 runAs("bob", innerSupplier)
     ▼
┌─ set bob ─────────┐
│ stack=["alice","bob"]│
│ current="bob"     │
└────┬───────────────┘
     │ innerSupplier 抛 RuntimeException
     ▼
┌─ clear bob ───────┐
│ stack=["alice"]   │
│ current="alice"   │
└────┬──────────────┘
     │ supplier.run() 抛异常
     ▼
┌─ clear alice ────┐
│ stack=[]         │
│ current=null     │
└──────────────────┘
```

**关键不变量**:`stack.size() == 0 ⟺ current() == null`(栈空等价无租户)

---

## Validation Rules

| Rule | Where | Effect on failure |
|---|---|---|
| tenantId 匹配 `[a-zA-Z0-9_-]{1,64}` | `TenantContext.set` / `runAs` / 启动期 `TenantsConfig.validate` | `IllegalArgumentException` / `LINGS-C02` |
| `TenantsConfig.map.size() <= 1000` | 启动期 `TenantsConfig.validate` | `LINGS-C02 CONFIG_VALIDATION_FAILED` |
| 每个 TenantConfig.tenantId 唯一 | 启动期 `TenantsConfig.validate` | `LINGS-C02 CONFIG_VALIDATION_FAILED`(duplicate key) |
| 每 TenantConfig 必填字段齐 | 启动期 `TenantsConfig.validate` | `LINGS-C02` + 字段路径列表 |
| yml 配 tenants 但 LinearTurnEngine.runTurn 时 TenantContext.current() == null | 运行时 FR-011 | `LINGS-C02 CONFIG_VALIDATION_FAILED` "tenants configured but turn started without TenantContext.runAs" |
| tenant 不存在 | `TenantConfigProvider.resolve(ghost)` | 返 `Optional.empty()` + 日志 `WARN tenant config not found: ghost` |

---

## Relationships

```
AgentConfig 1..1 ──contains──> TenantsConfig 1..* ──contains──> Map<String, TenantConfig>
                                                                     │
                                                                     ▼
                                                                  TenantConfig
                                                                     │
                                                                     ├─ Memory (1..1)
                                                                     ├─ Sandbox (1..1)
                                                                     └─ Cost (1..1)

TenantConfigProvider 1..* ──resolves──> TenantConfig  (N:1, 多 Provider 可解析同一 tenant,priority 高胜出)
TenantContext (per-thread) 1..1 ──references──> String tenantId
RuntimeSandbox / CostTracker / SessionStore ──reads──> TenantContext.current()
```

---

## Lifecycle

| Phase | TenantContext | TenantConfig | AgentConfig.tenants |
|---|---|---|---|
| Spring 启动 | (未触碰) | (未触碰) | Yaml 解析 → AgentConfigProps.bind → TenantsConfig 构造 |
| AgentFactory.create(cfg) | (未触碰) | (未触碰) | `cfg.getTenants().validate()` → 启动期校验 |
| 业务入口 `runAs("alice", supplier)` | stack push "alice" | (未触碰) | (未触碰) |
| Turn N `agent.runBlocking(...)` | current() == "alice" | `provider.resolve("alice")` → TenantConfig.alice | (未触碰) |
| Tool 调用 → sandbox.process.run() | current() snapshot("alice") → 解析 whitelist | (未触碰) | (未触碰) |
| supplier 抛异常 OR 正常返回 | stack pop → try-finally clear | (未触碰) | (未触碰) |
| JVM shutdown | (GC 回收) | (GC 回收) | (AgentFactory 单例销毁) |

---

## Future Scope (Out of Story #006)

- `TenantsConfig` 加 `version` / `revision` 字段(Story #007 hot-reload 范畴)
- `TenantConfigProvider` 替代实现:`DatabaseTenantConfigProvider` / `VaultTenantConfigProvider`(Story 后续,类似 §5.5 多 Provider 样板)
- `TenantConfig.Memory` 加 `backupEnabled` / `retentionDays` 字段(Story 后续)
- `TenantConfig.Cost` 加 `dailyBudgetMicros` / `monthlyBudgetMicros`(Story #012 cost-budget 范畴)
