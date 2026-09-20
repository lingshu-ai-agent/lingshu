# Data Model: Story #003 spi-slot-router

**Branch**: `story-003-spi-slot-router` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md) | **Research**: [research.md](./research.md)

> Phase 1 — entities, fields, relationships, validation rules. Implementation-layer types (no public API contracts beyond what's already in `SlotProvider` / `SlotRouter` SPI).

---

## Entity Catalog

Story #003 introduces **3 new types** + modifies **3 existing types**:

| Entity | Kind | Location | Purpose |
|---|---|---|---|
| `Version` | New utility class | `ai.lingshu.core.spi.Version` | semver parse / compat check / format |
| `ProviderInitException` | New exception | `ai.lingshu.core.spi.ProviderInitException` | LINGS-S05 启动期错误码载体 |
| `ContractVersionRef` | New annotation | `ai.lingshu.core.spi.ContractVersionRef` | 标记 Slot 接口的契约版本字段(辅助反射) |
| `SlotProvider` | **Modified** interface | `ai.lingshu.core.spi.SlotProvider` | + `String version()` 方法 |
| `SlotRouter` | **Modified** abstract class | `ai.lingshu.core.spi.SlotRouter` | + 构造期校验 + `describe()` |
| 9 Slot interfaces | **Modified** | `ai.lingshu.core.slot.*` | + `String CONTRACT_VERSION = "1.0.0"` 常量 |

Plus 9 default Provider stubs upgraded(`version()="1.0.0"` + 真实 `create()` body)。

---

## Entity 1: `Version` (ai.lingshu.core.spi.Version)

### Purpose
semver 解析 / 兼容性比较 / 格式化工具,被 `SlotRouter` 构造期 + `resolve()` 二次校验调用,被 9 Router 共用。

### Structure

```java
public final class Version {
    private Version() {}  // 工具类,禁止实例化

    /** Parse "MAJOR.MINOR.PATCH" → int[3]. Throws IAE on bad format. */
    public static int[] parse(String version);

    /** Provider version ↔ Slot contract version compatibility check. */
    public static boolean isCompatible(String providerVer, String slotContractVer);

    /** int[3] → "MAJOR.MINOR.PATCH". */
    public static String format(int[] version);

    /** Validate single segment is non-negative integer (no leading zeros beyond 0). */
    private static int validateSegment(String segment, int index, String full);
}
```

### Fields
- **None** (pure utility, no instance state)

### Methods

| Method | Signature | Behavior | Throws |
|---|---|---|---|
| `parse(String)` | `public static int[3] parse(String version)` | Split by `.`, validate 3 segments, parse to int[3] | `IllegalArgumentException` if null / empty / non-3-segment / non-numeric / negative / leading-zero (except "0") |
| `isCompatible(String, String)` | `public static boolean isCompatible(String providerVer, String slotContractVer)` | Compare major / minor / patch per D-03 rules | `IllegalArgumentException` if either arg fails parse() |
| `format(int[3])` | `public static String format(int[] version)` | Join with `.`, return "MAJOR.MINOR.PATCH" | `IllegalArgumentException` if length != 3 or negative |
| `validateSegment(String, int, String)` | `private static int validateSegment(...)` | Parse single segment + zero-check | `IllegalArgumentException` (internal only) |

### Validation Rules

| Rule | Reason | Source |
|---|---|---|
| `version != null` | null version = 程序员忘记实现,不是合法值 | D-01 |
| `version.length() > 0` | 空字符串 = 程序员返回 `""`,bug | D-01 |
| `version.split("\\.").length == 3` | semver v1 严格 3 段 | D-01 |
| Each segment matches `^[0-9]+$` | 无负数 / 无小数 / 无字母 | D-01 |
| `Integer.parseInt(seg) >= 0` | semver 不允许负数 | D-01 |
| `Integer.parseInt(seg) <= Integer.MAX_VALUE` | 防 overflow(实际不会触发,只是兜底) | best practice |
| Leading zeros (`"01"`) 拒绝 | semver 不允许前导零(除 "0" 本身) | D-01 |

### State Transitions

- **None** (stateless utility)

### Relationships

- **Calls**: `SlotRouter` 父类构造期 + `SlotRouter.resolve()` 二次校验 + 单元测试
- **Called by**: 单元测试 `VersionTest`(~10 场景)

---

## Entity 2: `ProviderInitException` (ai.lingshu.core.spi.ProviderInitException)

### Purpose
启动期 Provider 校验失败的统一异常类型,带 `errorCode="LINGS-S05"` 字段 + cause chain + 可选 `hint`(人话建议)。

### Structure

```java
public class ProviderInitException extends IllegalStateException {
    private final String errorCode;     // "LINGS-S05"
    private final String hint;          // optional human-readable suggestion

    public ProviderInitException(String message, Throwable cause, String hint);

    @Override public String getMessage();  // "LINGS-S05: <message> (hint: <hint>)"

    public String getErrorCode();   // returns "LINGS-S05"
    public String getHint();
}
```

### Fields

| Field | Type | Visibility | Mutable | Description |
|---|---|---|---|---|
| `errorCode` | `String` | `private final` | No | Always `"LINGS-S05"` for v1(可扩展后续 ErrorCode) |
| `hint` | `String` | `private final` | No | Human-readable suggestion(e.g. `"implement version() returning '1.0.0' on your @Component class"`);**可空**(null = no hint) |

### Constructors

| Constructor | Use case |
|---|---|
| `ProviderInitException(String message, Throwable cause, String hint)` | Single constructor for v1;LINGS-S05 only |

### Methods

| Method | Returns | Description |
|---|---|---|
| `getErrorCode()` | `String` | `"LINGS-S05"` |
| `getHint()` | `String` | hint 字段(null-safe,return `""` if null) |
| `getMessage()`(override) | `String` | `"LINGS-S05: <message>"` + `" (hint: <hint>)"` if hint non-empty |
| `getCause()`(inherited) | `Throwable` | cause chain (length ≥ 2: ProviderInitException → IllegalArgumentException → ...) |

### Validation Rules
- `message != null` (required)
- `cause != null` (required, ensures cause chain ≥ 2)
- `hint` may be null or empty

### State Transitions
- **None** (immutable after construction)

### Relationships
- **Thrown by**: `SlotRouter` 构造期(`Version.parse` 失败 / `isCompatible` 失败)+ `SlotRouter.resolve()` 二次校验
- **Caught by**: Spring `ApplicationContext` 启动失败处理(默认行为:stderr ERROR + JVM exit 1)
- **Tested by**: `ProviderInitExceptionTest`(SC-004 黑盒)

---

## Entity 3: `ContractVersionRef` (ai.lingshu.core.spi.ContractVersionRef) [INTERNAL MARKER]

### Purpose
**辅助 SlotRouter 反射读取 Slot 接口的 CONTRACT_VERSION 字段**(D-02 反射方案需要标识符)。

### Structure

```java
/** Marker annotation — marks the Slot interface field that holds the contract version. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ContractVersionRef {}
```

### Why annotation?
- **不是强约束**:9 Slot 接口可以**不**标 `@ContractVersionRef`,`SlotRouter` 用反射 fallback `T.class.getField("CONTRACT_VERSION")` 也能读
- **是辅助约束**:标了 `@ContractVersionRef` 后,IDE 编译期能识别"这是契约版本字段"(静态分析);`SlotRouter` 反射时优先读 `@ContractVersionRef` 标记的字段
- **未来兼容**:如果未来 Slot 接口用 `contractVersion()` 方法声明版本,`@ContractVersionRef` 可改为 `@ContractVersionMethod`,升级路径平滑

### Validation Rules
- `@Retention(RUNTIME)` 必须 —— `SlotRouter` 构造期要反射读
- `@Target(FIELD)` 必须 —— 当前只支持字段,不支持方法

### State Transitions
- **None** (compile-time annotation)

### Relationships
- **Marks**: 9 Slot 接口的 `public static final String CONTRACT_VERSION = "1.0.0"` 字段
- **Read by**: `SlotRouter` 构造期(优先读 `@ContractVersionRef` 标注字段,fallback `getField("CONTRACT_VERSION")`)

---

## Entity 4: `SlotProvider<T>` (MODIFIED interface)

### Purpose
**所有 Slot Provider 的父接口**;v1.0.0 时方法 = name() + priority() + create(config);Story #003 **+version()**。

### Modified Method

```java
/** Contract version (semver MAJOR.MINOR.PATCH). Must equal Slot's CONTRACT_VERSION
 *  major, and Provider minor <= Slot minor (backward-compat within major).
 *  See Version.isCompatible(). */
String version();
```

### Updated Javadoc
- Class-level Javadoc 增 `version()` 字段说明
- 段 4 "Three pieces of metadata" → "**Four** pieces of metadata"

### Relationships (unchanged)
- **Extended by**: `Providers.LlmProviderProvider` / `ToolExecutorProvider` / ... / `A2aTransportProvider` (9 typed)
- **Implemented by**: 9 default Provider classes + user plugins

### Validation
- **Runtime enforced by**: `SlotRouter` 构造期(`Version.parse(p.version())` + `Version.isCompatible(...)`)
- **Compile-time**: 无(Java interface 不允许 abstract method 实现检查,只能运行时校验)

### Migration Impact
- **Breaking change**: 现有 9 默认 Provider stub **必须** 加 `version()` 实现 —— 这是 Story #003 的核心改动之一(D-07)
- **External impact**: 用户 plugin 实现的 Provider 必须加 `version()` —— 这是 v1 → v1.1 的 SemVer 破坏性变更,但 lingshu 还在 v0.1.0-SNAPSHOT,**不**承诺 backward compat

---

## Entity 5: `SlotRouter<P, T>` (MODIFIED abstract class)

### Purpose
9 Slot Router 的父类;构造期收集 Provider + 同名竞争 + 日志;`resolve(name, cfg)` 按名取实例;**Story #003 +版本校验 + describe()**。

### New Field

```java
private final String slotContractVersion;  // 反射读自 T.class.getField("CONTRACT_VERSION")
```

### Modified Constructor

```java
protected SlotRouter(List<P> providers, String typeName, Logger log) {
    // Step 1: read T.class.getField("CONTRACT_VERSION") via reflection
    this.slotContractVersion = readContractVersion();

    // Step 2: validate each provider's version() (NEW)
    validateProviderVersions(providers);

    // Step 3: existing byName map + conflict handling (UNCHANGED)
    ...

    // Step 4: log with version info (MODIFIED format)
    log.info("[{}] resolved {} provider(s) [contract v{}]:", typeName, winners.size(), slotContractVersion);
    for (Map.Entry<String, P> e : winners.entrySet()) {
        ...
        log.info("  ✓ {} v{} -> {} [priority={}]{}",
            e.getKey(),
            e.getValue().version(),         // NEW: show version
            e.getValue().getClass().getSimpleName(),
            e.getValue().priority(),
            conflictInfo);
    }
}
```

### New Methods

```java
/** Validate every Provider's version() against Slot contract version. */
private void validateProviderVersions(List<P> providers);

/** Read T.class.getField("CONTRACT_VERSION") via reflection. */
private String readContractVersion();

/** Return N lines describing all registered Providers (sync, read-only). */
public List<String> describe();
```

### Modified Method

```java
/** Resolve + 二次校验版本 (防 yml hot-reload 指向未注册 Provider). */
public T resolve(String name, AgentConfig config) {
    P p = byName.get(name);
    if (p == null) {
        throw new IllegalArgumentException(...);  // 原有 LINGS-S01
    }
    // NEW: 二次校验 (D-04 rationale)
    Version.isCompatible(p.version(), slotContractVersion);  // throws ProviderInitException on fail
    return p.create(config);
}
```

### Validation Rules (NEW)

| Check | Failure | Error Code |
|---|---|---|
| `T.class.getField("CONTRACT_VERSION")` 存在 | 反射失败 | LINGS-S05 |
| `CONTRACT_VERSION` 是 `String` 且非空 | 字段类型错 / 值空 | LINGS-S05 |
| 每个 Provider `version()` 非 null | null | LINGS-S05 |
| 每个 Provider `version()` 合法 semver | 格式错 | LINGS-S05 |
| 每个 Provider `version()` 兼容 Slot 契约 | 不兼容 | LINGS-S05 |

### State Transitions
- **None** (immutable after construction)

### Relationships (modified)
- **Reads**: `SlotProvider.version()` (new)+ `T.class.getField("CONTRACT_VERSION")` (new)
- **Calls**: `Version.parse()` / `Version.isCompatible()` (new)+ `ProviderInitException` constructor (new)
- **Called by**: 9 Router 子类(构造器调 `super(providers, typeName, log)`)+ AgentFactory (不变)

---

## Entity 6: 9 Slot interfaces (MODIFIED)

### List
1. `LlmProvider` (Slot 1)
2. `ToolExecutor` (Slot 2 runtime)
3. `PermissionPolicy` (Slot 3)
4. `SessionStore` (Slot 5)
5. `Compactor` (Slot 6)
6. `PromptBuilder` (Slot 7)
7. `MemorySource` (Slot 4 sub-slot)
8. `FlowEngine` (Slot 8)
9. `A2aTransport` (Slot 9)

Plus non-Provider-backed Slot interfaces (no version check needed but contract version declared for documentation):
10. `Tool` (Slot 2 product)
11. `RuntimeSandbox` (Slot 3 product)
12. `Skill` (Slot 4 product)
13. `SkillSource` (Slot 4 source)

### Modified Field (per interface)

```java
public interface LlmProvider {  // 同 9 个 Slot interface 一致
    /** Contract version (semver MAJOR.MINOR.PATCH). Provider.version() must be
     *  compatible per Version.isCompatible(). Bump on breaking API changes. */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    // ... existing methods unchanged
}
```

### Validation
- **Compile-time**: `@ContractVersionRef` 字段必须有 `String` 类型 + `public static final` + 值非空(无强约束,fallback 反射 `getField("CONTRACT_VERSION")`)
- **Runtime**: `SlotRouter` 构造期反射读

### Migration Impact
- **Breaking**: 无(只是新增字段,不影响现有实现类)
- **External**: 用户 plugin 实现的 Slot 接口 **不需要** 声明 CONTRACT_VERSION(只有 lingshu-core 自己的 Slot 接口声明);用户的 Provider.version() 必须与 lingshu-core 的对应 Slot 契约版本兼容

---

## Entity 7: 9 Default Provider classes (MODIFIED)

### List

| Class | File | Current `create()` | Story #003 `create()` |
|---|---|---|---|
| `AnthropicLlmProviderFactory` | `impl/llm/AnthropicLlmProviderProvider.java` | `throw new UnsupportedOperationException("TODO: Story #003")` | `return new AnthropicLlmProvider(config.getLlm().getApiKey())` |
| `DefaultToolExecutorProvider` | `impl/tool/DefaultToolExecutorProvider.java` | 同上 | `return new DefaultToolExecutor()` |
| `AllowAllPermissionPolicyProvider` | `impl/permission/AllowAllPermissionPolicyProvider.java` | 同上 | `return new AllowAllPermissionPolicy()` |
| `FileSessionStoreProvider` | (Slot 5 stub)| 同上 | `return new FileSessionStore()`(Story #014 实装前的 stub) |
| `TruncatingCompactorProvider` | (Slot 6 stub)| 同上 | `return new TruncatingCompactor(config.getCompactor().getMaxTokens())` |
| `DefaultPromptBuilderProvider` | `impl/prompt/DefaultPromptBuilderProvider.java` | 已实装(Story #002) | **不变** |
| `ProjectClaudeMdSourceProvider` 等 4 个 | `impl/memory/*SourceProvider.java` | 已实装(Story #002) | **不变** |
| `LinearTurnEngineProvider` | `impl/flow/LinearTurnEngineProvider.java` | `throw new UnsupportedOperationException("TODO: Story #001")` | `return new LinearTurnEngine()` |
| `HttpJsonRpcA2aTransportProvider` | (Slot 9 stub) | 同上 | `return new HttpJsonRpcA2aTransport(config.getA2a())`(Story #009 实装前的 stub) |

### Modified Method (per Provider)

```java
@Override public String version() { return "1.0.0"; }

// create() body 改 "throw new UnsupportedOperationException" → "return new XxxImpl(...)"
```

### Validation
- **Compile-time**: `version()` 必须 override 父接口(否则编译错)
- **Runtime**: `SlotRouter` 构造期 `Version.isCompatible("1.0.0", "1.0.0")` → true(精确匹配)

### Migration Impact
- **Internal only**: 不影响用户 plugin(用户 plugin 走自己的 `version()` 返回值,只要兼容即可)
- **9 Provider 改 1 个 PR**:便于 review + 减少 PR 数量

---

## Relationships Diagram

```
┌──────────────────────────────────────────────────────────────────┐
│                    Spring ApplicationContext                      │
│                                                                   │
│  ┌────────────┐         ┌──────────────────────────────────┐    │
│  │ AgentFactory│────────▶│ Routers.LlmProviderRouter       │    │
│  │            │         │   extends SlotRouter<P, T>       │    │
│  │ @Component │         │     - byName Map<String, P>      │    │
│  │            │         │     - slotContractVersion        │    │
│  │ 7 校验     │         │     + validateProviderVersions()│    │
│  │ + describe()│         │     + describe()                 │    │
│  └────────────┘         │     + resolve(name, cfg)         │    │
│         │               └──────────────────────────────────┘    │
│         │                          │                              │
│         │                          ▼                              │
│         │               ┌──────────────────────────────────┐    │
│         │               │ Providers.LlmProviderProvider    │    │
│         │               │   extends SlotProvider<LlmProvider>│   │
│         │               │     + name() / priority() /       │    │
│         │               │     + version() = "1.0.0"  (NEW)  │    │
│         │               │     + create(AgentConfig)         │    │
│         │               └──────────────────────────────────┘    │
│         │                          │                              │
│         │                          ▼                              │
│         │               ┌──────────────────────────────────┐    │
│         │               │ LlmProvider  (interface)          │    │
│         │               │   @ContractVersionRef             │    │
│         │               │   String CONTRACT_VERSION = "1.0"│    │
│         │               └──────────────────────────────────┘    │
│         │                                                        │
│         │  ┌──────────────┐    ┌────────────────────────────┐   │
│         └─▶│ Version      │    │ ProviderInitException      │   │
│            │ parse()      │    │   extends IllegalStateEx   │   │
│            │ isCompatible │◀──▶│   errorCode = "LINGS-S05"  │   │
│            │ format()     │    │   hint (optional)          │   │
│            └──────────────┘    └────────────────────────────┘   │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

---

## Schema Versioning Strategy

| Version | When | What changes |
|---|---|---|
| 1.0.0 | Story #003 落地 | Initial contract: name + priority + version + create |
| 1.1.0 | Future minor bump (e.g., Provider 加 `capabilities()` 字段) | Story #XXX 改 `SlotProvider` 接口 + 9 Slot 接口 + bump CONTRACT_VERSION |
| 2.0.0 | Future major bump (e.g., Slot 拆分 / 重命名) | Major breaking change,所有 Provider 必须升 major version 才能继续兼容 |

**Policy**: 用户 plugin 在 major version 升级时**必须**显式 bump `version()` 返回值;lingshu-core 启动期 fail-fast(US2 Scenario 3)。

---

## Validation Summary

- **Compile-time**: `SlotProvider` 接口 + 9 Slot 接口 `@ContractVersionRef` 字段类型检查
- **Runtime**: `SlotRouter` 构造期 5 项校验(null / empty / semver format / compatible major / compatible minor)
- **Test-time**: `VersionTest`(10 场景)+ `SlotRouterCompatTest`(6 场景)+ `ProviderInitExceptionTest`(4 场景)+ `AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion`(启动日志 9 行)

---

## Out of Scope (Data Model 层)

- ❌ **Provider 持久化 version 字段**(运行时不变,启动期已校验,**不**写 DB)
- ❌ **Provider version 演进迁移工具**(自动改 Provider 源码)
- ❌ **跨 Slot 的 version 协调表**(每个 Slot 独立维护 version)
- ❌ **动态 version()**(Provider.version() 是常量,不是 Git SHA)
