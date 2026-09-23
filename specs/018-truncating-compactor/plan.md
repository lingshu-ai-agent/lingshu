# Story #018 `truncating-compactor` — Plan

> **Status**: Draft 2026-09-22
> **Implements**: `specs/018-truncating-compactor/spec.md`
> **Reference**: dsh v1.5.37 §6.2 L3813-3891 (TruncatingCompactor v1) + §5.5 L2122-2141 (Slot 2 Compactor stub)

---

## 接口设计

### 1. `AgentConfig.CompactorConfig`(嵌套 @Value)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(追加,不动现有字段)

```java
@Value
public static class CompactorConfig {
    /** Token 阈值;超此值则 shouldCompact() 返回 true。默认 100_000(dsh §14.15.1 单 turn ≤ 100K)。*/
    int maxPromptTokens;
    /** 单 ToolResult 字节阈值;超此值原地截断。默认 50_000(50KB)。*/
    int maxToolResultBytes;
    /** 滑动窗口:保留最近 N 个 assistant message。默认 20。*/
    int keepRecentTurns;

    /** 零配置默认(空 yml 启动)。 */
    public static CompactorConfig defaults() {
        return new CompactorConfig(100_000, 50_000, 20);
    }

    /**
     * 启动期校验 — 任一字段 ≤ 0 抛 {@link LingsConfigException} (LINGS-C02)。
     * 失败聚合到 {@link AgentFactory#create} 的集中校验里(dsh §7.1.2 T1)。
     */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (maxPromptTokens <= 0) {
            errors.add("agent.compactor.max-prompt-tokens must be > 0 (got " + maxPromptTokens + ")");
        }
        if (maxToolResultBytes <= 0) {
            errors.add("agent.compactor.max-tool-result-bytes must be > 0 (got " + maxToolResultBytes + ")");
        }
        if (keepRecentTurns <= 0) {
            errors.add("agent.compactor.keep-recent-turns must be > 0 (got " + keepRecentTurns + ")");
        }
        if (!errors.isEmpty()) {
            throw new LingsConfigException("C02",
                "agent.compactor config validation failed:\n  - " + String.join("\n  - ", errors));
        }
    }
}
```

**AgentConfig 顶层修改**:
- 新增 `CompactorConfig compactorConfig;` 字段(默认 `CompactorConfig.defaults()`)
- 现有 `String compactor;` 字段**不动**(向后兼容,yml `compactor: truncating` 仍工作)
- `AgentConfig.defaults()` 加 `CompactorConfig.defaults()` 填充

**Why**:`String compactor` 是 name(走 Router),`CompactorConfig` 是配置(走 Provider `create()`),两者职责不同 —— name 走 `cfg.getCompactor()`,config 走 `cfg.getCompactorConfig()`。dsh §4.12.2 + §6.2 + §5.5 一致。

### 2. `CompactorProps`(@Value 不可变 props)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/CompactorProps.java`(新)

```java
package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.runtime.AgentConfig;
import lombok.Value;

/**
 * Immutable 3-field config for {@link TruncatingCompactor}.
 *
 * <p>Built from {@link AgentConfig.CompactorConfig} by
 * {@link TruncatingCompactorProvider#create(AgentConfig)} — the Provider is the
 * <em>only</em> place where {@link AgentConfig} gets translated to {@link CompactorProps},
 * keeping the Slot core (Compactor interface) free of any config type (dsh §5.5).
 */
@Value
public class CompactorProps {
    int maxPromptTokens;
    int maxToolResultBytes;
    int keepRecentTurns;

    /** 从 cfg 构造;cfg.compactorConfig 为 null 时回退 defaults()(向后兼容旧 yml)。 */
    public static CompactorProps from(AgentConfig cfg) {
        AgentConfig.CompactorConfig cc = cfg.getCompactorConfig();
        if (cc == null) {
            cc = AgentConfig.CompactorConfig.defaults();
        }
        return new CompactorProps(
            cc.getMaxPromptTokens(),
            cc.getMaxToolResultBytes(),
            cc.getKeepRecentTurns());
    }
}
```

**Why**:dsh §6.2 L3832-3836 `TruncatingCompactor(CompactorProps props)` 构造函数签名引用 `CompactorProps`,必须有这个类;`from(AgentConfig)` 工厂把 Config 类型隔离在 Provider 边界,Slot core 不污染(`Compactor` 接口 / `CompactorProvider` SPI 都不引用 `CompactorProps`)。

### 3. `TruncatingCompactor`(@Component implements Compactor)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/TruncatingCompactor.java`(新)

```java
package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Slot 6 默认实现(dsh §6.2 L3813-3889)— ToolResult 截断 + 滑动窗口保留最近 N turn。
 *
 * <p>两阶段契约:
 * <ol>
 *   <li>{@link #shouldCompact(Prompt)} — 粗估 token(`chars / 4`),> maxPromptTokens 返回 true</li>
 *   <li>{@link #compact(TurnContext)} — 副作用地缩减 {@code ctx.session().history()};**幂等**</li>
 * </ol>
 *
 * <p>副作用:history 是同 Session 共享的可变 List,Story #018 v1 走 {@code synchronized (session)}
 * 块(同 Session 多 turn 串行,跨 Session 并行无影响)。dsh §6.2 L3891 备注 v2 改不可变 Session + CoW。
 */
@Component
public class TruncatingCompactor implements Compactor {

    private static final Logger log = LoggerFactory.getLogger(TruncatingCompactor.class);

    private static final String TOOL_RESULT_TRUNCATION_MARKER = "\n...[truncated, original %d bytes]";
    private static final String SYSTEM_AUTHOR = "compactor";

    private final int maxPromptTokens;
    private final int maxToolResultBytes;
    private final int keepRecentTurns;

    public TruncatingCompactor(CompactorProps props) {
        this.maxPromptTokens    = props.getMaxPromptTokens();
        this.maxToolResultBytes = props.getMaxToolResultBytes();
        this.keepRecentTurns    = props.getKeepRecentTurns();
    }

    @Override
    public boolean shouldCompact(Prompt prompt) {
        return estimateTokens(prompt) > maxPromptTokens;
    }

    @Override
    public void compact(TurnContext ctx) {
        Session session = ctx.session();
        // 🆕 dsh §6.2 L3891 副作用 → v1 synchronized (session) 兜底并发
        synchronized (session) {
            List<Message> history = session.history();
            if (history == null || history.isEmpty()) {
                return; // EC-018-1 空 history noop
            }

            int toolResultTruncated = truncateOverlongToolResults(history);
            int messagesCompacted = applySlidingWindow(history);

            if (toolResultTruncated > 0 || messagesCompacted > 0) {
                log.info("compactor[truncating]: truncated {} tool result(s); sliding-window removed {} message(s); "
                        + "history size: {} → {}",
                    toolResultTruncated, messagesCompacted,
                    history.size() + messagesCompacted, history.size());
            }
        }
    }

    /**
     * Step 1:原地截断超长 ToolResult。
     * @return 截断条数(0 表示无需动作)
     */
    private int truncateOverlongToolResults(List<Message> history) {
        int count = 0;
        for (int i = 0; i < history.size(); i++) {
            Message m = history.get(i);
            if (!(m instanceof Message.ToolResult)) continue;
            Message.ToolResult tr = (Message.ToolResult) m;
            String content = tr.getContent();
            if (content == null || content.length() <= maxToolResultBytes) continue;
            String truncated = content.substring(0, maxToolResultBytes)
                + String.format(TOOL_RESULT_TRUNCATION_MARKER, content.length());
            history.set(i, new Message.ToolResult(
                tr.getToolUseId(),
                truncated,
                tr.isError()));
            count++;
        }
        return count;
    }

    /**
     * Step 2:滑动窗口 —— 砍掉超出 keepRecentTurns 的旧 turn,在 [0..cutIndex) 末尾追加 system 提示。
     * @return 被砍掉的 message 数(0 表示 noop)
     */
    private int applySlidingWindow(List<Message> history) {
        int assistantCount = 0;
        int cutIndex = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof Message.Assistant) {
                assistantCount++;
                if (assistantCount > keepRecentTurns) {
                    cutIndex = i + 1;
                    break;
                }
            }
        }
        if (cutIndex == 0) {
            return 0; // EC-018-2 / 不够 keepRecentTurns 个 assistant message
        }
        int removed = history.size() - cutIndex;
        List<Message> kept = new ArrayList<>(history.subList(0, cutIndex));
        kept.add(new Message.System(
            "[Earlier turns compacted. " + removed + " messages removed.]",
            SYSTEM_AUTHOR));
        history.clear();
        history.addAll(kept);
        return removed;
    }

    /**
     * 粗估 token:总字符数 / 4(中位估值,dsh §6.2 L3882-3887)。
     * 不区分中英,工具 schema 也算。
     */
    private int estimateTokens(Prompt prompt) {
        int chars = 0;
        for (Message m : prompt.getMessages()) {
            chars += m.toString().length();
        }
        for (ToolSpec t : prompt.getTools()) {
            chars += t.toString().length();
        }
        return chars / 4;
    }
}
```

**关键 Why**:
- 副作用(history 原地修改) → `synchronized (session)` 兜底并发(dsh §6.2 L3891)
- 截断 + sliding window **2 个独立 step**(可单独 noop,任一为空不影响另一个)
- `Message.ToolResult` + `Message.System` 用 dsh §4.1 nested class 形态,`toolUseId` / `isError` 保留
- 估算 token 不区分中英 / 不调 tokenizer,`chars / 4` 中位估值(dsh §6.2 L3882-3887 既有)
- `ToolSpec` 在 Prompt 上,`estimateTokens` 一起算进

### 4. `TruncatingCompactorProvider`(@Component implements Providers.CompactorProvider)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/TruncatingCompactorProvider.java`(新)

```java
package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Slot 6 默认 Provider — name "truncating", Story #018。
 *
 * <p>替代 dsh §5.5 L2126-2139 的 {@code UnsupportedOperationException("TODO: Story #015")} stub。
 * 注册时 Spring bean name = {@code "compactorProvider_truncating"} (§5.4 唯一 Bean 名约定,🆕 v1.5.28)。
 */
@Component("compactorProvider_truncating")
public class TruncatingCompactorProvider implements Providers.CompactorProvider {

    @Override public String name()     { return "truncating"; }

    @Override public int    priority() { return 0; }

    /** 对齐 Compactor CONTRACT_VERSION = "1.0.0"(Story #003 spi-slot-router)。 */
    @Override public String version()  { return "1.0.0"; }

    @Override
    public Compactor create(AgentConfig config) {
        return new TruncatingCompactor(CompactorProps.from(config));
    }
}
```

**Why**:Provider 是 Adapter 边界,把 `AgentConfig.CompactorConfig` 翻译成 `CompactorProps`(Slot core 不依赖 Config 类型)。

### 5. `CompactorRouter`(追加到 Routers.java)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/router/Routers.java`(追加,L60 之后)

```java
@Component
public static class CompactorRouter
        extends SlotRouter<Providers.CompactorProvider, Compactor> {
    public CompactorRouter(List<Providers.CompactorProvider> providers) {
        super(providers, "Compactor", LoggerFactory.getLogger(CompactorRouter.class));
    }
    @Override protected Class<Compactor> getSlotInterface() { return Compactor.class; }
}
```

**Why**:补齐 dsh §5.3.1.0 L1854-1859 既有 `CompactorRouter` concrete stub 定义。`SlotResolver` 已经有 `compactorRouter` 字段(dsh §5.3.1 L1649),只是 Router bean 缺失。

### 6. `SlotResolver` 集成

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/SlotResolver.java`(检查是否已 `@Autowired CompactorRouter`,若否追加)

dsh §5.3.1 L1649 `SlotResolver` ctor 已声明 `CompactorRouter c` 字段,Story #018 验证 Spring 自动 wire 即可,**不动代码**;若实际项目 `SlotResolver` 没这字段则追加 1 行 `@Autowired` + 1 行 `this.compactorRouter = c;`。

### 7. 测试矩阵

| 文件 | case 数 | 类型 | 覆盖 AC |
|---|---:|---|---|
| `TruncatingCompactorTest.java` | 8 | L1 Unit | AC-018-1 / AC-018-2 / AC-018-3 / AC-018-4 + EC-018-1/2/3/4 |
| `TruncatingCompactorProviderTest.java` | 4 | L1 Unit | AC-018-5 / AC-018-6 |
| `RoutersStartupTest.java`(扩)| 1(追加)| L2 Slice | AC-018-7 |
| `AgentConfigCompactorValidationTest.java` | 4 | L1 Unit | AC-018-8 / AC-018-9(2 case) |
| `LinearTurnEngineCompactionTest.java` | 1 | L2 Slice | AC-018-10 |
| **合计** | **18** | — | — |

---

## 文件改动清单(5 核心 + 5 测试 = 10 文件,**严格 ≤5 核心边界**)

| 类型 | 路径 | 行数 | 新增 / 修改 |
|---|---|---:|---|
| 核心 | `lingshu-core/.../runtime/AgentConfig.java` | +40 | 修改(追加 `CompactorConfig` 嵌套 + `compactorConfig` 字段)|
| 核心 | `lingshu-core/.../impl/compaction/CompactorProps.java` | ~30 | 新增 |
| 核心 | `lingshu-core/.../impl/compaction/TruncatingCompactor.java` | ~110 | 新增 |
| 核心 | `lingshu-core/.../impl/compaction/TruncatingCompactorProvider.java` | ~25 | 新增 |
| 核心 | `lingshu-core/.../impl/router/Routers.java` | +10 | 修改(追加 `CompactorRouter` 静态类)|
| 测试 | `lingshu-core/.../compaction/TruncatingCompactorTest.java` | ~280 | 新增 |
| 测试 | `lingshu-core/.../compaction/TruncatingCompactorProviderTest.java` | ~80 | 新增 |
| 测试 | `lingshu-core/.../compaction/CompactorPropsTest.java` | ~40 | 新增 |
| 测试 | `lingshu-core/.../router/RoutersStartupTest.java`(扩)| +30 | 修改(追加 `CompactorRouter` 1 case)|
| 测试 | `lingshu-core/.../runtime/AgentConfigCompactorValidationTest.java` | ~90 | 新增 |
| 测试 | `lingshu-core/.../flow/LinearTurnEngineCompactionTest.java` | ~150 | 新增 |

**Story 边界 = 5 核心文件改动(满足 CLAUDE.md §11 #4 ≤5)**,18 测试 case(> 10)。

---

## 关键不变项(回归保护)

- **`Compactor` 接口不变**(2 方法契约 `shouldCompact` + `compact` 不动)
- **`Providers.CompactorProvider` 不变**(Story #003 spi-slot-router 已落地)
- **`SlotRouter<P, T>` 父类不变** —— Story #018 只是新增 1 个 concrete 子类
- **`SlotResolver.compactorRouter` 字段已存在**(dsh §5.3.1 L1649) —— Story #018 不动
- **`AgentConfig.compactor`(String 字段)不变** —— 新增 `compactorConfig` 嵌套共存
- **`LinearTurnEngine` 主循环不变**(dsh §6.1 L3630-3631 已有 `compactor.shouldCompact(prompt)` + `compactor.compact(ctx)` 调用)
- **0 新 Maven 依赖**(13 项锁定不变,R-13 mitigation (d))
- **0 新 `AgentEvent` 嵌套类 / 0 新 `StopReason` enum 值 / 0 新 ErrorCode**

---

## 与既有 Story 的边界交叉

| 既有 Story | 影响 |
|---|---|
| #001 zero-config-bootstrap | Story #018 沿用 `@Component` 默认装载;空 yml 启动 → `CompactorConfig.defaults()` |
| #002 identity-instructions-memory | 无影响(identity / instructions 与 compaction 无关)|
| #003 spi-slot-router | **直接依赖**:`Providers.CompactorProvider` 已存在;`SlotRouter` 父类已实现 `version()` 校验 |
| #004 tool-parallel-dispatch | 无影响(parallel dispatch 与 compaction 正交)|
| #005 cancellation-token | **关注点**:Compactor `synchronized (session)` 是否会被 CancellationToken 中断?答案:不会被(CancellationToken 是协作式,不抛 InterruptedException;`synchronized` 块完成即释放锁)|
| #006 multi-tenant | 无影响(每 tenant 有独立 session,compactor 实例 per-turn 重建)|
| #007 yaml-hot-reload | **关注点**:CompactorProps 是 `@Value` 不可变 + 每次 `create()` 新建;in-flight turn 冻结 cfg(Story #007 已落地),compactor 实例不受热更影响 |
| #008 react-max-steps | **直接相关**:`MaxStepsExceeded` 事件与 Compactor 无交互;两者都是 turn 边界保护机制 |
| #009—#009d A2A 系列 | 无影响(A2A 与 compaction 正交)|

---

## 实施顺序(T-NN 见 tasks.md)

1. **T-01** `AgentConfig.CompactorConfig` 嵌套 + 顶层 `compactorConfig` 字段 + `validate()` + `defaults()`
2. **T-02** `CompactorProps`(@Value + `from(AgentConfig)` 工厂)
3. **T-03** `TruncatingCompactor`(@Component + 2 步压缩 + `synchronized (session)` 兜底)
4. **T-04** `TruncatingCompactorProvider`(@Component + 3 字段 metadata + `create` 工厂)
5. **T-05** `Routers.java` 追加 `CompactorRouter` 静态类
6. **T-06** `SlotResolver` 验证 `@Autowired CompactorRouter` 已就位(若缺补 1 行)
7. **T-07** 5 个测试文件(TruncatingCompactorTest / TruncatingCompactorProviderTest / CompactorPropsTest / RoutersStartupTest 扩 / AgentConfigCompactorValidationTest / LinearTurnEngineCompactionTest)
8. **T-08** `mvn -pl lingshu-core test` 全过 + `mvn dependency:tree` 自查
9. **T-09** 文档同步(README / dsh v1.5.38 / constitution §10 R-04 / ROADMAP.md 段一)

---

**Last updated**: 2026-09-22
**Plan author**: Claude Code (per user 2026-09-22 conversation)