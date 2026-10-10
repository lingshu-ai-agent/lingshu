# Story #045 — compactor-turn-trigger

**Branch**: `045-compactor-turn-trigger` | **Date**: 2026-10-10 | **Spec**: [link to self]
**Input**: 灵枢 LingShu 项目遗留项补完 — Story #018 SPI 已落地,turn 内嵌 `compactor.shouldCompact(ctx)` + `compactor.compact(ctx)` 调用一直未接线(实际代码 0 引用)。

> **dsh 引用规约**:`~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md` v1.5.57

---

## WHY(为什么)

**问题**:dsh §6.1 L3630-3631 规定 `LinearTurnEngine.runTurn()` 主循环在 `promptBuilder.build()` → `llmProvider.stream()` 之间必须先判 `compactor.shouldCompact(prompt)` → true 时调 `compactor.compact(ctx)`,以确保 history 超 NFR 阈值(单 turn ≤ 100K token,见 §10)前自动压缩,避免 LLM 上下文溢出。

**现状(Story #018 落盘后)**:`Compactor` SPI / `CompactorProps` / `TruncatingCompactor` / `TruncatingCompactorProvider` / `CompactorAutoConfiguration` / `Routers.CompactorRouter` 全部已合**(2026-09-22 commit)**,但 **LinearTurnEngine 6 个 final 字段无 `compactor`**, `LinearTurnEngineProvider` 6 个 final 字段无 `compactorRouter`,`AgentFactory` 8-Router ctor 第 8 个空参处是 `null // compactor` 占位 —— **`compactor.shouldCompact(prompt)` + `compactor.compact(ctx)` 0 调用**。换言之:`TruncatingCompactor` 类已写好,但运行时永远不会被触发,history 增长无压缩,长 session OOM 风险。

**根因**:Story #018 spec/plan L11(原文)**错误地声明** "LinearTurnEngine 主循环不变 (dsh §6.1 L3630-3631 已有 `compactor.shouldCompact(prompt)` + `compactor.compact(ctx)` 调用)"——但 commit 时未真实接线,plan 与代码 drift。**Story #045 是 Story #018 的"接线补丁"**,不替换 SPI 也不改 Provider,只补 `LinearTurnEngine` 主循环的调用 + Provider 的 Router 注入 + Factory 的 ctor 扩参 + yml 端到端打通。

**业务影响**:
- 长 session 上下文无压缩 → 100K token 上限硬截断(LlmProvider 抛 LINGS-L07 `LLM_CONTEXT_OVERFLOW`,**用户体验 = 静默失败**)
- dsh §10 NFR row 4「单 turn history ≤ 100K tokens」**未在代码层 enforce**,仅停留在文档承诺
- Story #018 spec 计划承诺的 AC(`TruncatingCompactor` 边界字符数压缩)在生产链路 0 落地

---

## WHO(谁会受益)

| Persona | 受益方式 |
|---|---|
| **企业业务方(yml 配置者)** | 自动压缩 turn 内 history,长 session 不再 100K 截断;`agent.compactor: truncating` 一行 yml 启用 |
| **Agent 框架贡献者** | `Compactor` SPI 半落地补完,可扩展 `SummaryCompactor` / `EmbeddingCompactor` 等替代实现,`@Component implements CompactorProvider` 即可(v1.5.28 §5.5 多 Provider 模式样板已成熟) |
| **demo-product demo 用户** | 演示场景长对话跑通后能完整收尾,不再 30 turn 后 100K 截断崩 demo |

---

## WHAT(做什么 — 6 文件 scope)

| 文件 | 改动类型 | 摘要 |
|---|---|---|
| `lingshu-core/.../flow/LinearTurnEngine.java` | modify | 7-arg ctor 新增 `Compactor compactor` 字段;`runTurn()` L186 `promptBuilder.build(ctx)` 之前加 `compactor.shouldCompact(prompt)` 短路 + `compactor.compact(ctx)` 调用;保留 6-arg ctor back-compat |
| `lingshu-core/.../flow/LinearTurnEngineProvider.java` | modify | 7 final 字段 + new 7-arg ctor 注入 `CompactorRouter`;`create(AgentConfig)` 调 `compactorRouter.resolve(cfg, compactor -> compactor.name)` 拿到 `Compactor` 实例传给 engine;保留 6-arg ctor back-compat |
| `lingshu-core/.../runtime/AgentFactory.java` | modify | 9-Router ctor 新增 `LlmProviderRouter` 后/前位置插入 `CompactorRouter` 字段;`create()` 调 `linearTurnEngineProvider.create(cfg, flowRouter, ...)` 9-router 路径;legacy 8/7/6-Router ctor 链加 `compactorRouter=null` 委托 9-Router ctor |
| `lingshu-core/.../compaction/NullCompactor.java` | new | sentinel 模式 `implements Compactor`,`shouldCompact()` 永远 false,`compact()` 是 no-op;保证 `compactor` 字段非 null,`compactor.shouldCompact()` 调用永远走短路返回 false,0 性能损耗(单 boolean check) |
| `lingshu-core/.../test/.../LinearTurnEngineCompactionTest.java` | new | L1 unit,验证 `runTurn()` 主循环在 `promptBuilder.build()` 之前调 `compactor.shouldCompact(prompt)` + true 时调 `compactor.compact(ctx)` 5 段 |
| `lingshu-core/.../test/.../LinearTurnEngineProviderCompactionTest.java` | new | L1 unit,验证 `LinearTurnEngineProvider.create(cfg)` 调 `compactorRouter.resolve(cfg, ...)` 拿 compactor 实例 |
| `lingshu-core/.../test/.../AgentFactoryYamlCompactorIT.java` | new | L2 integration,`agent.compactor: truncating` yml 端到端,trigger compactor + history 长度 ≤ CompactorProps.threshold |

**Scope 边界**:**≤ 5 核心文件改动**(LinearTurnEngine + LinearTurnEngineProvider + AgentFactory + NullCompactor + LinearTurnEngineProviderCompactionTest 算 5) + 2 测试文件;**0 新 ErrorCode**;**0 新 Maven 依赖**。

### 接口设计(LinearTurnEngine 7-arg ctor)

```java
// lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngine.java
public class LinearTurnEngine implements FlowEngine {
    private final PromptBuilder promptBuilder;
    private final LlmProvider llmProvider;
    private final ToolExecutor toolExecutor;
    private final PermissionPolicy permissionPolicy;
    private final ExecutorService toolPool;
    private final ApprovalRegistry approvalRegistry;
    private final Compactor compactor;   // 🆕 Story #045

    /** Story #045 — 7-arg primary ctor. Production path. */
    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider,
                            ToolExecutor toolExecutor, PermissionPolicy permissionPolicy,
                            ExecutorService toolPool, ApprovalRegistry approvalRegistry,
                            Compactor compactor) {
        if (promptBuilder == null) throw new IllegalArgumentException("promptBuilder must not be null");
        if (llmProvider == null) throw new IllegalArgumentException("llmProvider must not be null");
        if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor must not be null");
        if (permissionPolicy == null) throw new IllegalArgumentException("permissionPolicy must not be null");
        if (toolPool == null) throw new IllegalArgumentException("toolPool must not be null");
        if (compactor == null) throw new IllegalArgumentException("compactor must not be null");
        this.promptBuilder = promptBuilder;
        this.llmProvider = llmProvider;
        this.toolExecutor = toolExecutor;
        this.permissionPolicy = permissionPolicy;
        this.toolPool = toolPool;
        this.approvalRegistry = approvalRegistry;
        this.compactor = compactor;   // 🆕
    }

    /** Story #030 back-compat — 6-arg delegates with compactor = NullCompactor.INSTANCE. */
    public LinearTurnEngine(PromptBuilder promptBuilder, LlmProvider llmProvider,
                            ToolExecutor toolExecutor, PermissionPolicy permissionPolicy,
                            ExecutorService toolPool, ApprovalRegistry approvalRegistry) {
        this(promptBuilder, llmProvider, toolExecutor, permissionPolicy, toolPool,
             approvalRegistry, NullCompactor.INSTANCE);
    }
```

### 主循环 hook(L186 之前)

```java
// runTurn() L186 `Prompt prompt = promptBuilder.build(ctx);` 之前插入:
if (compactor.shouldCompact(prompt)) {       // 🆕 Story #045 — dsh §6.1 L3630-3631
    LOG.info("step {}: compactor triggered — compacting history (current messages={})",
        step, ctx.history().size());
    sink.onNext(new AgentEvent.CompactionTriggered(step, ctx.history().size()));
    compactor.compact(ctx);
    sink.onNext(new AgentEvent.CompactionCompleted(step, ctx.history().size()));
}
Prompt prompt = promptBuilder.build(ctx);
```

**⚠️ 等等**:上面的 hook 在 `promptBuilder.build(ctx)` 之前调 `shouldCompact(prompt)`,但 `prompt` 还没 build —— **逻辑颠倒**。正确顺序应是:`prompt = promptBuilder.build(ctx)` → `if (compactor.shouldCompact(prompt))` → `compactor.compact(ctx)` → `llmProvider.stream(prompt, ctx, sink)`。**修订**:

```java
Prompt prompt = promptBuilder.build(ctx);
LOG.debug("step {}: prompt built — messages={}, tools={}",
    step, prompt.getMessages().size(), prompt.getTools().size());

// 🆕 Story #045 (dsh §6.1 L3630-3631) — Compactor turn trigger:
// history 超阈值 → 压缩 ctx.history() → 重 build prompt(否则 prompt 还引用旧 history)
if (compactor.shouldCompact(prompt)) {
    LOG.info("step {}: compactor triggered — compacting history (current messages={})",
        step, ctx.history().size());
    sink.onNext(new AgentEvent.CompactionTriggered(step, ctx.history().size()));
    compactor.compact(ctx);
    prompt = promptBuilder.build(ctx);   // 重 build 拿压缩后的 history
    sink.onNext(new AgentEvent.CompactionCompleted(step, ctx.history().size()));
}

// Stream the LLM call; future completes with the final structured response.
CompletableFuture<LlmResponse> fut = llmProvider.stream(prompt, ctx, sink);
```

### NullCompactor sentinel

```java
// lingshu-core/src/main/java/ai/lingshu/core/impl/compaction/NullCompactor.java
/**
 * 🆕 Story #045 — Compactor sentinel / fallback. {@code shouldCompact} 永远 false,
 * {@code compact} no-op. 保证 {@code LinearTurnEngine.compactor} 字段非 null,
 * {@code compactor.shouldCompact()} 单 boolean check,0 性能损耗。
 *
 * <p>Production path:LinearTurnEngineProvider.create() 必传入 yml 配的
 * {@code TruncatingCompactor}(Story #018 已合)。NullCompactor INSTANCE 仅用于
 * 旧 back-compat ctor + 测试 fixture(Story #001-#044 旧 fixture 0 改动仍能跑)。
 */
public final class NullCompactor implements Compactor {
    public static final NullCompactor INSTANCE = new NullCompactor();

    private NullCompactor() {}

    @Override
    public boolean shouldCompact(Prompt prompt) {
        return false;
    }

    @Override
    public void compact(TurnContext ctx) {
        // no-op
    }
}
```

### Provider 7-arg ctor

```java
// lingshu-core/src/main/java/ai/lingshu/core/impl/flow/LinearTurnEngineProvider.java
public class LinearTurnEngineProvider implements FlowEngineProvider {
    private final PromptBuilderRouter promptBuilderRouter;
    private final LlmProviderRouter llmProviderRouter;
    private final ToolExecutorRouter toolExecutorRouter;
    private final PermissionPolicyRouter permissionPolicyRouter;
    private final ExecutorService agentToolPool;
    private final ApprovalRegistry approvalRegistry;
    private final CompactorRouter compactorRouter;   // 🆕 Story #045

    public LinearTurnEngineProvider(PromptBuilderRouter promptBuilderRouter,
                                     LlmProviderRouter llmProviderRouter,
                                     ToolExecutorRouter toolExecutorRouter,
                                     PermissionPolicyRouter permissionPolicyRouter,
                                     ExecutorService agentToolPool,
                                     @Autowired(required = false) @Nullable ApprovalRegistry approvalRegistry,
                                     CompactorRouter compactorRouter) {   // 🆕
        // ... 5 null 检查 + 1 nullable approvalRegistry ...
        this.compactorRouter = compactorRouter;
    }

    /** Story #045 back-compat — 6-arg delegates with compactorRouter = null(then LinearTurnEngine ctor 走 6-arg)。*/
    public LinearTurnEngineProvider(PromptBuilderRouter p, LlmProviderRouter l,
                                     ToolExecutorRouter t, PermissionPolicyRouter pp,
                                     ExecutorService pool,
                                     @Nullable ApprovalRegistry approvalRegistry) {
        this(p, l, t, pp, pool, approvalRegistry, null);
    }

    @Override
    public FlowEngine create(AgentConfig cfg) {
        Compactor compactor = compactorRouter != null
            ? compactorRouter.resolve(cfg, name -> name)   // 🆕
            : NullCompactor.INSTANCE;
        return new LinearTurnEngine(
            promptBuilderRouter.resolve(cfg, name -> name),
            llmProviderRouter.resolve(cfg, name -> name),
            toolExecutorRouter.resolve(cfg, name -> name),
            permissionPolicyRouter.resolve(cfg, name -> name),
            agentToolPool,
            approvalRegistry,
            compactor);
    }
}
```

> ⚠️ **`@Nullable` import**:Story #030 已有 precedent(`ApprovalRegistry` optional),本 Story 复用同 pattern。

### AgentFactory 9-Router ctor

```java
// lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java
@Component
public class AgentFactory {
    // ... 既有 8 Router 字段 ...
    private final CompactorRouter compactorRouter;   // 🆕 Story #045

    @Autowired
    public AgentFactory(
            LlmProviderRouter llmProviderRouter,
            ToolExecutorRouter toolExecutorRouter,
            PermissionPolicyRouter permissionPolicyRouter,
            PromptBuilderRouter promptBuilderRouter,
            FlowEngineRouter flowEngineRouter,
            MemorySourceRouter memorySourceRouter,
            RuntimeSandboxRouter runtimeSandboxRouter,
            SessionStoreRouter sessionStoreRouter,    // 🆕 Story #014
            CompactorRouter compactorRouter) {        // 🆕 Story #045
        // ... 9 init ...
    }

    /** Story #045 back-compat — 8-Router ctor delegates with compactorRouter = null. */
    public AgentFactory(LlmProviderRouter llm, ToolExecutorRouter te,
                         PermissionPolicyRouter pp, PromptBuilderRouter pb,
                         FlowEngineRouter fe, MemorySourceRouter ms,
                         RuntimeSandboxRouter rsb, SessionStoreRouter ss) {
        this(llm, te, pp, pb, fe, ms, rsb, ss, null);
    }

    /** Story #014 back-compat — 7-Router ctor delegates with sessionStoreRouter + compactorRouter = null. */
    public AgentFactory(LlmProviderRouter llm, ToolExecutorRouter te,
                         PermissionPolicyRouter pp, PromptBuilderRouter pb,
                         FlowEngineRouter fe, MemorySourceRouter ms,
                         RuntimeSandboxRouter rsb) {
        this(llm, te, pp, pb, fe, ms, rsb, null, null);
    }

    /** Story #028 back-compat — 6-Router ctor delegates with runtimeSandboxRouter + sessionStoreRouter + compactorRouter = null. */
    public AgentFactory(LlmProviderRouter llm, ToolExecutorRouter te,
                         PermissionPolicyRouter pp, PromptBuilderRouter pb,
                         FlowEngineRouter fe, MemorySourceRouter ms) {
        this(llm, te, pp, pb, fe, ms, null, null, null);
    }
```

---

## 反向 AC(对 #018 已合 AC 的校验)

| #018 AC | #045 反向 AC | 状态 |
|---|---|---|
| AC-018-1: `TruncatingCompactor` 边界字符数压缩逻辑正确 | AC-045-RAC-1: 既有 `TruncatingCompactorTest` + `TruncatingCompactorProviderTest` + `CompactorBlackboxIT` 0 改动全过 | ✅ 0 改动回归 |
| AC-018-2: `CompactorProps.threshold` yml 接线 | AC-045-RAC-2: `CompactorAutoConfigurationTest` 0 改动全过 | ✅ |
| AC-018-3: yml `agent.compactor: truncating` 启用 | AC-045-RAC-3: `CompactorRouter` 多 Provider 模式(`"compactorProvider_truncating"` Bean 名)0 改动 | ✅ |
| AC-018-4: `CompactorRouter` byName 路由 | AC-045-RAC-4: `Routers.CompactorRouter` 行为不变(本 Story 只通过它 resolve,不直接 new) | ✅ |

---

## Acceptance Criteria(AC-045)

### 主线(Story #045 必须新过)

- **AC-045-1**:`LinearTurnEngine` 7-arg ctor 接受 `Compactor` 字段;`compactor` 为 null 抛 `IllegalArgumentException`("compactor must not be null")。
- **AC-045-2**:`LinearTurnEngine.runTurn()` L186 `promptBuilder.build(ctx)` 之后调 `compactor.shouldCompact(prompt)`;true 时调 `compactor.compact(ctx)`,**然后** `prompt = promptBuilder.build(ctx)` 重 build(因 history 已压缩,prompt 旧引用过期)。
- **AC-045-3**:`LinearTurnEngineProvider` 7-arg ctor 注入 `CompactorRouter`;`create(AgentConfig)` 调 `compactorRouter.resolve(cfg, name -> name)` 拿 `Compactor` 实例。
- **AC-045-4**:`AgentFactory` 9-Router ctor 加 `CompactorRouter` 字段;legacy 8/7/6-Router ctor 委托链加 `compactorRouter=null`。
- **AC-045-5**:`NullCompactor.INSTANCE.shouldCompact(anyPrompt)` 永远 false;`compact(anyCtx)` no-op(单 boolean check,0 性能损耗)。

### 集成(L2 integration)

- **AC-045-6**:`AgentFactoryYamlCompactorIT` 跑通 `agent.compactor: truncating` yml 端到端:构造 11 turn(> threshold),`LinearTurnEngine.runTurn()` 触发 compactor → `ctx.history()` 长度 ≤ `CompactorProps.threshold`。
- **AC-045-7**:`LinearTurnEngineCompactionTest` 5 段 L1 验证:shouldCompact=false no-op / shouldCompact=true 调 compact / compact 抛异常 propagate / yml 无 `compactor` 字段 fallback NullCompactor / 6-arg ctor 仍能实例化。

### R-13 + 0 regression

- **AC-045-8**:`mvn -pl lingshu-core dependency:tree` pre/post md5sum 相同(纯 JDK 8 + Lombok 已锁 13 项依赖 0 新 binary 引入),**第 29 次 R-13 mitigation (d) PASS 0 binary delta**;既有 720 个测试 0 改动全过(707 旧 + 13 #044 新 case)。

### Edge Cases(EC-045)

- **EC-045-1**:shouldCompact=false → 主循环跳过 compactor.compact(),prompt 直接传给 llmProvider.stream()。
- **EC-045-2**:compact 抛 RuntimeException → 沿 LLM 调用异常路径冒泡,runTurn 外层 catch 触发 ERROR + TurnCompleted(STOP_REASON=ERROR),**不**吞异常(权限路径收紧,不能因 compaction 失败让 LLM 静默收到满请求)。
- **EC-045-3**:yml 无 `agent.compactor` 字段 → 默认 `CompactorConfig` 走 TruncatingCompactorProvider(via §5.5 多 Provider name-based resolve,yml 缺省 fall back to 唯一默认 Provider,`@Autowired(required = false) @Nullable` 不适用,本 Story 假设 `CompactorRouter` 必非 null——见 OQ-EC-3)。

### Open Questions(OQ-045)

- **OQ-EC-3**:`CompactorRouter` 是否真的始终非 null?目前 9 Slot 中只有 `ApprovalRegistry` 用 `@Autowired(required = false) @Nullable` 标记 optional;其他 8 个 Router 都是必注入。如果 yml 完全没 `agent.compactor` 字段且 `CompactorAutoConfiguration` 没被扫到,`CompactorRouter` 注入会失败。本 Story **保守采用** "必注入" 假设(沿用 v1.5.28 §5.5 默认 `@Configuration` 模式),不引入新的 `@Nullable` precedent。

---

## ErrorCode

- **新 ErrorCode**:**0**
- **复用**:`LINGS-C02 CONFIG_VALIDATION_FAILED`(复用 Story #001/018 既有,`compactor` ctor null 校验)、`LINGS-Z01 COMPACTION_FAILED`(Story #018 预留占位,本 Story 仍不抛——若 `compact(ctx)` 抛 RuntimeException,**不**转换为 ErrorCode,直接沿外层异常路径传播)。

---

## Definition of Done(DoD)

- [ ] 7 文件改动落地(spec.md 已落地,代码改动由 Step 3 实施)
- [ ] AC-045-1 ~ AC-045-8 全过(L1 unit + L2 integration)
- [ ] 反向 AC-045-RAC-1 ~ RAC-4 全过(Story #018 既有 0 改动)
- [ ] EC-045-1 ~ EC-045-3 全覆盖
- [ ] `mvn -pl lingshu-core test` 0 fail(720 旧 case 0 改动 + 7 新 case)
- [ ] `mvn -pl lingshu-core dependency:tree` 第 29 次 R-13 mitigation (d) PASS 0 binary delta
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 `#045` 行
- [ ] `specs/ROADMAP.md` 段一 ✅ 已完成加 #045 行;段二 🟡 待补 #045 划掉
- [ ] README.md 「更新日期」段加 🆕 Story #045 顶部 blockquote + 「核心特性」段补 🆕 Compactor turn wiring 已上线 bullet + 「Story 路线图」段追加 #045 retrospective
- [ ] `dsh_agent_design.md` §13 changelog 加 v1.5.58 行,§6.1 L3630-3631 改"Compactor wiring 已落代码"+ §15 ErrorCode 域字母表 0 改
- [ ] CLAUDE.md v1.3.53 → v1.3.54,SKILL mirror 同步

---

## 关键不变项(必须 0 改动守住)

| 不变项 | 守住方式 |
|---|---|
| `Compactor` SPI 接口契约 | 只新增 1 个 `NullCompactor` 实现,`Compactor` / `CompactorProvider` 接口签名 0 改 |
| `CompactorRouter` 行为 | 沿用 Story #018 已合的 byName 路由,本 Story 只通过它 resolve,不直接 new |
| `CompactorProps` + `TruncatingCompactor` 实现 | Story #018 落地 0 改 |
| `CompactorAutoConfiguration` 注册路径 | v1.5.28 §5.5 多 Provider 模式(`@Configuration` + 2 `@Bean` Bean 名 `compactorProvider_truncating-1.0.0`),0 改 |
| `AgentConfig` 不可变契约 | `CompactorConfig` 字段 0 改(Story #018 已合 `compactor.threshold` 等字段) |
| `LlmProvider` / `ToolExecutor` / `PermissionPolicy` SPI | 0 改 |
| `LinearTurnEngine.runTurn()` 主循环结构 | **改 1 处**:L186 之后插 compactor 短路 + 重 build prompt;L168 cancel check + L184 ReasoningStarted + L191 stream() + L217 appendAssistant 0 改 |
| `ToolExecutor.dispatch()` 5 步流水线 | 0 改(§4.10.1 硬规则 2) |
| `Message` 4 子类契约 | 0 改 |
| `AgentFactory` ctor 链 back-compat | 6 → 7 → 8 → 9 链 4 层委托,既有 Story #001-#044 旧 fixture 0 改 |
| 9 Slot 体系 | 仍 9 个 Slot(Compactor 是 Slot 6,本来就在 §5.6.4 总表) |
| JDK 8 兼容 | 纯 `@Nullable` + Lombok @Value + JDK 8 standard 集合 + `try/finally` 已锁 |

---

**Last updated**: 2026-10-10
**Branch**: `045-compactor-turn-trigger`
**Status**: Draft(待用户评审 + 审批后才进 Step 3 实施)