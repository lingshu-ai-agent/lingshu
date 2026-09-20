# SPI Contract: Slot Version Compatibility (Story #003)

**Branch**: `story-003-spi-slot-router` | **Date**: 2026-09-20 | **Spec**: [spec.md](../spec.md) | **Research**: [research.md](../research.md) | **Data Model**: [data-model.md](../data-model.md)

> SPI contract for `SlotProvider.version()` + `SlotRouter` 兼容性校验。这是 Story #003 的核心交付,Story #009 A2A `A2aTransport` 协议协商将复用本契约。

---

## §1. Contract Overview

本契约定义 **3 类 SPI 表面**:

| Surface | Type | Modified? | Consumer |
|---|---|---|---|
| `SlotProvider.version()` | New abstract method | **YES** (new) | `SlotRouter` 构造期 + resolve() 二次校验 |
| `SlotRouter` 构造期版本校验 | New behavior | **YES** (modified constructor) | Spring `ApplicationContext` 启动失败处理 |
| `Version` 工具类 | New utility | **YES** (new) | `SlotRouter` 内部 + 单元测试 |

下游契约(由 Story #003 **不**修改,仅引用):
- 9 Slot 接口 + `Tool` / `RuntimeSandbox` / `Skill` / `SkillSource` 加 `CONTRACT_VERSION` 字段(D-02)
- 9 默认 Provider stub 加 `version()` 实现(D-07)

---

## §2. `SlotProvider<T>` Interface Contract

### §2.1 Modified Interface

```java
package ai.lingshu.core.spi;

import ai.lingshu.core.runtime.AgentConfig;

/**
 * Base SPI contract — every Slot has one typed Provider (dsh §5.1).
 *
 * <p>🆕 Story #003: + {@link #version()} field for Slot compatibility check.
 *
 * <p>Four pieces of metadata are required:
 * <ul>
 *   <li>{@link #name()} — stable string for YAML configuration; must be globally unique
 *       within the Slot (dsh §5.5 v1.5.28 "唯一 Bean 名约定")</li>
 *   <li>{@link #priority()} — tie-break for same-name conflicts (dsh §5.2)</li>
 *   <li>{@link #version()} 🆕 — semver MAJOR.MINOR.PATCH, must be compatible with
 *       the Slot's {@code CONTRACT_VERSION} per {@link Version#isCompatible(String, String)}</li>
 *   <li>{@link #create(AgentConfig)} — factory method; implementations are stateless
 *       so the result can be cached / pooled freely</li>
 * </ul>
 */
public interface SlotProvider<T> {

    /** Stable identifier used in {@code application.yml} ({@code agent.<slot>.name: <value>}). */
    String name();

    /** Higher value wins on name conflicts; same name + same priority → registration order. */
    int priority();

    /**
     * 🆕 Story #003 — Contract version (semver MAJOR.MINOR.PATCH).
     *
     * <p>Must equal the corresponding Slot interface's {@code CONTRACT_VERSION} major,
     * and Provider minor must be ≤ Slot minor (backward-compat within major).
     *
     * <p>Validation performed at {@link SlotRouter} construction time (Spring startup):
     * <ul>
     *   <li>Format check: {@link Version#parse(String)} — strict 3-segment semver,
     *       no 'v' prefix, no leading zeros, no pre-release/build metadata</li>
     *   <li>Compat check: {@link Version#isCompatible(String, String)} — see {@link Version}</li>
     * </ul>
     *
     * <p>Failure mode: {@link ProviderInitException} with errorCode {@code "LINGS-S05"},
     * cause chain ≥ 2 (e.g. IllegalArgumentException from Version.parse), optional
     * {@code hint} field for human-readable suggestion.
     *
     * <p>Migration: v1 → v1.1 is a breaking change (all Providers must add {@code version()}
     * method). lingshu-core is v0.1.0-SNAPSHOT so no SemVer promise broken.
     */
    String version();

    /**
     * Build the Slot instance for the given config. Called once per turn by the Router
     * (no caching at this level — implementations should be cheap to instantiate).
     */
    T create(AgentConfig config);
}
```

### §2.2 Compatibility Rules

| Provider version | Slot contract version | Compatible? | Reason |
|---|---|---|---|
| `1.0.0` | `1.0.0` | ✅ | Exact match (highest priority compatible) |
| `1.5.3` | `1.10.0` | ✅ | Same major (1), Provider minor (5) ≤ Slot minor (10) |
| `1.10.0` | `1.5.0` | ❌ | Same major but Provider minor (10) > Slot minor (5) — Provider may use undeclared methods |
| `1.0.0` | `1.0.1` | ✅ | Same major + same minor, Provider patch (0) ≤ Slot patch (1) |
| `1.0.1` | `1.0.0` | ❌ | Same major + same minor, Provider patch (1) > Slot patch (0) — Provider may use undeclared methods |
| `2.0.0` | `1.0.0` | ❌ | Major mismatch — breaking change |
| `1.0.0` | `2.0.0` | ❌ | Major mismatch — Provider too old |
| `null` | `1.0.0` | ❌ | Null version — programmer forgot to implement |
| `"v1"` / `"1.0"` / `"latest"` | `1.0.0` | ❌ | Invalid semver format |

### §2.3 Backward Compatibility (v0.x → v1.x)

| Migration | Required Provider change |
|---|---|
| v0.x Provider → Story #003 | Add `@Override public String version() { return "1.0.0"; }` |
| v0.x Provider → v1.1 future | Add new `version()` value + adapt to new `SlotProvider` methods |
| v0.x Provider → v2.0 future | Major bump version() + rewrite against new Slot API |

---

## §3. `SlotRouter<P, T>` Abstract Class Contract

### §3.1 Modified Class

```java
package ai.lingshu.core.spi;

import ai.lingshu.core.runtime.AgentConfig;
import org.slf4j.Logger;
// ... (existing imports)

/**
 * Shared resolver logic for typed {@link SlotProvider}s (dsh §5.2).
 *
 * <p>🆕 Story #003: constructor now validates {@link SlotProvider#version()}
 * against Slot's {@code CONTRACT_VERSION} (read via reflection from {@code T.class.getField("CONTRACT_VERSION")}).
 * Fails fast at Spring startup with {@link ProviderInitException} (LINGS-S05).
 *
 * <p>🆕 Story #003: new {@link #describe()} method for AgentFactory.description() output.
 *
 * <p>Concrete Routers (one per Slot) extend this abstract class, supplying the {@code P} /
 * {@code T} pair. The constructor takes the Spring-injected {@code List<P>} and runs the
 * same-name / priority / startup-log dance that all 9 Routers share.
 *
 * <p>Hot path is {@link #resolve(String, AgentConfig)} (now with secondary version check);
 * startup path is the constructor (validates versions, then logs).
 */
public abstract class SlotRouter<P extends SlotProvider<T>, T> {

    private final Map<String, P> byName;
    private final String slotContractVersion;  // 🆕 reflected from T.class.getField("CONTRACT_VERSION")

    /**
     * @param providers Spring-injected list of all Providers of this Slot
     * @param typeName  short name for log lines (e.g. {@code "LlmProvider"})
     * @param log       the Logger to receive the startup summary
     * @throws ProviderInitException (LINGS-S05) if any Provider.version() is null,
     *         malformed semver, or incompatible with Slot CONTRACT_VERSION
     */
    protected SlotRouter(List<P> providers, String typeName, Logger log) {
        // 🆕 Step 1: reflect Slot contract version
        this.slotContractVersion = readContractVersion();

        // 🆕 Step 2: validate every Provider.version() (FAIL-FAST on first error)
        validateProviderVersions(providers);

        // Step 3: existing byName map + conflict handling (UNCHANGED)
        Map<String, P> winners = new LinkedHashMap<>();
        Map<String, List<P>> conflicts = new LinkedHashMap<>();
        for (P p : providers) {
            P cur = winners.get(p.name());
            if (cur == null) {
                winners.put(p.name(), p);
            } else if (p.priority() > cur.priority()) {
                conflicts.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(cur);
                winners.put(p.name(), p);
            } else {
                conflicts.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
            }
        }
        this.byName = winners;

        // 🆕 Step 4: log with version info (MODIFIED format)
        log.info("[{}] resolved {} provider(s) [contract v{}]:",
            typeName, winners.size(), slotContractVersion);
        for (Map.Entry<String, P> e : winners.entrySet()) {
            List<P> all = conflicts.getOrDefault(e.getKey(), Collections.<P>emptyList());
            String conflictInfo = all.isEmpty()
                ? ""
                : " (overrode " + all.size() + " lower-priority impl(s): "
                  + joinNames(all) + ")";
            log.info("  ✓ {} v{} -> {} [priority={}]{}",
                e.getKey(),
                e.getValue().version(),         // 🆕
                e.getValue().getClass().getSimpleName(),
                e.getValue().priority(),
                conflictInfo);
        }
    }

    /**
     * Resolve a Provider by name and create the Slot instance.
     *
     * 🆕 Story #003: secondary version check (defends against yml hot-reload
     * pointing to a Provider that wasn't in the original constructor List<P>).
     *
     * @throws IllegalArgumentException if no Provider with that name is registered (LINGS-S01)
     * @throws ProviderInitException if the resolved Provider.version() is incompatible (LINGS-S05)
     */
    public T resolve(String name, AgentConfig config) {
        P p = byName.get(name);
        if (p == null) {
            throw new IllegalArgumentException(
                "Unknown " + getClass().getSimpleName() + " '" + name + "'. Available: " + byName.keySet());
        }
        // 🆕 secondary version check
        Version.isCompatible(p.version(), slotContractVersion);
        return p.create(config);
    }

    /** All registered names — useful for diagnostics and {@code /slots} CLI commands. */
    public Set<String> available() { return byName.keySet(); }

    /**
     * 🆕 Story #003 — Return N lines describing all registered Providers (sync, read-only).
     * Used by {@code AgentFactory.description()} for self-describe output (US3 Scenario 1).
     *
     * <p>Format: {@code "  <name> v<version> (priority=<n>)"} per line.
     *
     * @return immutable list of description lines, never null
     */
    public List<String> describe() {
        List<String> lines = new ArrayList<>(byName.size());
        for (Map.Entry<String, P> e : byName.entrySet()) {
            lines.add(String.format("  %s v%s (priority=%d)",
                e.getKey(),
                e.getValue().version(),
                e.getValue().priority()));
        }
        return Collections.unmodifiableList(lines);
    }

    // 🆕 Story #003 — internal helpers

    /** Reflect {@code T.class.getField("CONTRACT_VERSION")}. Throws ProviderInitException on failure. */
    private String readContractVersion() { ... }

    /** Validate every Provider.version() against slotContractVersion. Throws on first failure. */
    private void validateProviderVersions(List<P> providers) { ... }
}
```

### §3.2 Constructor Contract

**Pre-conditions** (caller responsibility):
- `providers != null`
- `typeName != null && !typeName.isEmpty()`
- `log != null`

**Post-conditions**:
- `byName` is non-null, immutable, contains max one Provider per name (priority winner)
- `slotContractVersion` is non-null, matches semver MAJOR.MINOR.PATCH format
- For each `p in providers`: `Version.isCompatible(p.version(), slotContractVersion) == true`
- Log output: 1 header line + N `✓ name vX.Y.Z -> ClassName [priority=N] (overrode ...)` lines

**Throws**:
- `ProviderInitException` (LINGS-S05) if any provider.version() is null/malformed/incompatible
- `ProviderInitException` (LINGS-S05) if `T.class.getField("CONTRACT_VERSION")` reflection fails

### §3.3 `resolve()` Contract

**Pre-conditions**:
- `name != null`
- `config != null`

**Post-conditions**:
- Returns `p.create(config)` where `p` is the priority winner for `name`

**Throws**:
- `IllegalArgumentException` (LINGS-S01) if `byName.get(name) == null`
- `ProviderInitException` (LINGS-S05) if `Version.isCompatible(p.version(), slotContractVersion) == false`(secondary check)

### §3.4 `describe()` Contract

**Pre-conditions**: 无

**Post-conditions**:
- 返回 `List<String>`,size = `byName.size()`
- 元素格式:`"  <name> v<version> (priority=<n>)"`
- list 是 `Collections.unmodifiableList(...)`

**Throws**: 无(no exception possible in sync read-only path)

---

## §4. `Version` Utility Contract

### §4.1 Class

```java
package ai.lingshu.core.spi;

/**
 * 🆕 Story #003 — semver parser + compatibility checker (dsh §0.4 AC-02/AC-08).
 *
 * <p>Pure utility (no instance state). All methods static + thread-safe.
 *
 * <p>semver v1 simplification (per research.md D-01):
 * <ul>
 *   <li>Strict 3-segment {@code MAJOR.MINOR.PATCH}</li>
 *   <li>No 'v' prefix</li>
 *   <li>No pre-release / build metadata</li>
 *   <li>Non-negative integers</li>
 *   <li>No leading zeros (except "0" itself)</li>
 * </ul>
 *
 * <p>Compatibility rule (research.md D-03):
 * <ul>
 *   <li>Different major → incompatible</li>
 *   <li>Same major + Provider minor ≤ Slot minor → compatible</li>
 *   <li>Same major + same minor + Provider patch ≤ Slot patch → compatible</li>
 *   <li>Otherwise → incompatible</li>
 * </ul>
 */
public final class Version {
    private Version() {}  // utility, no instances

    /** Parse "MAJOR.MINOR.PATCH" → int[3]. */
    public static int[] parse(String version);

    /** Provider version ↔ Slot contract version compatibility check. */
    public static boolean isCompatible(String providerVer, String slotContractVer);

    /** int[3] → "MAJOR.MINOR.PATCH". */
    public static String format(int[] version);
}
```

### §4.2 `parse(String)` Contract

**Pre-conditions**: 无

**Post-conditions**:
- Returns `int[]{major, minor, patch}` where each is non-negative

**Throws**:
- `IllegalArgumentException` if `version == null`
- `IllegalArgumentException` if `version.isEmpty()`
- `IllegalArgumentException` if `version.split("\\.").length != 3`
- `IllegalArgumentException` if any segment matches `[^0-9]`
- `IllegalArgumentException` if any segment is `"00"` / `"01"` (leading zero)
- `IllegalArgumentException` if any segment is negative after parseInt (shouldn't happen with `[0-9]+` regex)

### §4.3 `isCompatible(String, String)` Contract

**Pre-conditions**: 无(但内部调 `parse` 会 fail-fast)

**Post-conditions**:
- Returns `true` if compatible per D-03 rules
- Returns `false` if incompatible

**Throws**:
- `IllegalArgumentException` if either arg fails `parse()`

### §4.4 `format(int[])` Contract

**Pre-conditions**: 无

**Post-conditions**:
- Returns `"MAJOR.MINOR.PATCH"` joined with `"."`

**Throws**:
- `IllegalArgumentException` if `version.length != 3`
- `IllegalArgumentException` if any element < 0

---

## §5. Wire Format / Inter-Module Contract

**No external wire format** — Provider.version() 是 in-process 启动期契约,**不**走 HTTP / gRPC / message queue。

但 Provider.version() 字段会被 2 个下游场景消费:

| Consumer | Story | Use |
|---|---|---|
| `AgentFactory.description()` | Story #003 | 输出 9 行 Slot 自描述 |
| `A2aTransport` 协议协商 | Story #009 (future) | `A2aTransport.submit(...)` 携带 `protocolVersion` 字段,值 = `A2aTransportProvider.version()` |

Story #003 **不**实现 A2A 协议协商,只**预留** Provider.version() 字段供 Story #009 消费。

---

## §6. Failure Modes Summary

| Failure | Detection | Error Code | Cause Chain |
|---|---|---|---|
| `Provider.version()` returns null | `SlotRouter` 构造期 | LINGS-S05 | `ProviderInitException(IAE("version must not be null"), hint="implement version() returning '1.0.0'")` |
| `Provider.version()` returns `"v1"` | `SlotRouter` 构造期 | LINGS-S05 | `ProviderInitException(IAE("version 'v1' must be MAJOR.MINOR.PATCH"), hint="...")` |
| `Provider.version()` returns `"1.0"` (2-segment) | `SlotRouter` 构造期 | LINGS-S05 | `ProviderInitException(IAE("version '1.0' must have 3 dot-separated segments"), hint="...")` |
| `Provider.version()` returns `"1.0.0-RC1"` (pre-release) | `SlotRouter` 构造期 | LINGS-S05 | `ProviderInitException(IAE("version '1.0.0-RC1' contains non-numeric segment '0-RC1'"), hint="...")` |
| Provider major mismatch | `SlotRouter` 构造期 | LINGS-S05 | `ProviderInitException(IAE("LlmProvider provider 'my-llm' v2.0.0 incompatible with slot contract v1.0.0 (major version mismatch)"), hint="...")` |
| Provider minor > Slot minor | `SlotRouter` 构造期 | LINGS-S05 | `ProviderInitException(IAE("LlmProvider provider 'my-llm' v1.10.0 > slot contract v1.5.0 (provider uses APIs not yet declared)"), hint="...")` |
| T.class has no CONTRACT_VERSION field | `SlotRouter` 构造期 | LINGS-S05 | `ProviderInitException(NoSuchFieldException("CONTRACT_VERSION"), hint="add 'String CONTRACT_VERSION = \"1.0.0\";' to your Slot interface")` |
| yml `agent.llm.name=unknown` | `SlotRouter.resolve()` | LINGS-S01 | `IllegalArgumentException("Unknown LlmProviderRouter 'unknown'. Available: [anthropic, openai]")` (unchanged) |
| yml `agent.llm.name=anthropic` 但 anthropic Provider version 不兼容 | `SlotRouter.resolve()` | LINGS-S05 | `ProviderInitException(IAE("..."))` (secondary check) |

---

## §7. Versioning Policy

### §7.1 When to bump `SlotProvider.version()`

| Bump | Trigger | Example |
|---|---|---|
| None | Internal logic change(no API surface change) | `DefaultPromptBuilder` 重构 5 段装配 |
| Patch (1.0.0 → 1.0.1) | Backward-compatible API addition | (rare in v1; mostly reserved for documentation fixes) |
| Minor (1.0.0 → 1.1.0) | Backward-compatible API addition (e.g., `capabilities()` method on Provider) | Story #XXX adds new method to SlotProvider |
| Major (1.x → 2.0.0) | Breaking change (Slot API rename / remove / signature change) | Story #XXX redesigns Slot interface |

### §7.2 When to bump Slot interface's `CONTRACT_VERSION`

**Always** when bumping `SlotProvider.version()`(Slot 和 Provider 契约版本必须同步)。

### §7.3 When to bump `Version` utility's `CONTRACT_VERSION`(self)

Story #003 introduces `Version` utility. v1.0.0 contract:
- Strict 3-segment semver
- Backward-compat within major (D-03 rules)

v1.1 might add:
- Pre-release support (`-RC1`)
- Build metadata support (`+sha.abc`)

v2.0 might add:
- Different compatibility rules
- Operator range (`^1.0.0` / `~1.0.0`)

---

## §8. Test Contract Summary

详见 `quickstart.md` 黑盒验证步骤。

| Test Class | Coverage | Black-box? |
|---|---|---|
| `VersionTest` | 10 场景 (parse 3 正常 + 7 异常 / isCompatible 6 组合 / format 3) | NO (unit) |
| `SlotRouterCompatTest` | 6 场景 (AC-02/08 黑盒) | **YES** (startup fail-fast) |
| `ProviderInitExceptionTest` | 4 场景 (errorCode / cause / hint / stderr) | YES (stderr capture) |
| `AgentFactoryIntegrationTest#startup_listsAllProvidersWithVersion` | 9 行启动日志 | **YES** (stdout/logback capture) |
| `AgentFactoryDescriptionTest` | `factory.create(cfg).description()` 输出格式 | NO (sync method) |

---

## §9. Backward Compatibility Notes

**Breaking for users who have written Provider classes** (e.g., Story #001 / #002 期间的 9 默认 Provider stub):
- 必须加 `version()` 方法返回 `"1.0.0"`
- lingshu-core v0.1.0-SNAPSHOT 不承诺 backward compat(版本 < 1.0)

**Non-breaking for users who only configure YAML** (e.g., `agent.llm.name=anthropic`):
- YAML schema 不变,`version()` 是框架内部契约
- 用户看不到、感知不到

**Compatible with Story #001 / #002**:
- Story #001 落地的 7 Router / 9 默认 Provider stub / AgentFactory 7 校验不变,只**扩展**(`SlotProvider` +1 方法 / `SlotRouter` 构造期 +1 校验)
- Story #002 落地的 `MemorySourceRouter` / 4 MemorySource Provider / `DefaultPromptBuilder` 不变,**只**补 `version()="1.0.0"`

---

**Contract Author**:Claude Code(基于 spec.md + data-model.md + research.md + constitution v1.0)
**Contract Date**:2026-09-20
**Next Step**:`/speckit-tasks` 生成 tasks.md
