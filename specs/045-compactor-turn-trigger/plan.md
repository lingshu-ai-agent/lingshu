# Implementation Plan: Story #045 — compactor-turn-trigger

**Branch**: `045-compactor-turn-trigger` | **Date**: 2026-10-10 | **Spec**: [spec.md](./spec.md)

**Input**: Story #018 SPI 半落地遗留项 — `TruncatingCompactor` 已写但 `LinearTurnEngine` 主循环未真实接线,本 Story 补 7-arg ctor + Provider Router 注入 + AgentFactory 9-Router ctor + yml 端到端打通。

---

## Summary

`LinearTurnEngine.runTurn()` 主循环在每次 `promptBuilder.build(ctx)` 之后、`llmProvider.stream(prompt, ctx, sink)` 之前,先调 `compactor.shouldCompact(prompt)`,true 时调 `compactor.compact(ctx)`(实际压缩 `ctx.history()`)+ **重新 `promptBuilder.build(ctx)` 拿压缩后的 history**(因 prompt 旧引用已过期),然后再调 `llmProvider.stream()`。

`LinearTurnEngineProvider` 7-arg ctor 新增 `CompactorRouter` 字段;`create(AgentConfig)` 调 `compactorRouter.resolve(cfg, name -> name)` 拿 `Compactor` 实例。

`AgentFactory` 9-Router ctor 新增 `CompactorRouter` 字段;legacy 6/7/8-Router ctor 链保留 back-compat 委托。

`NullCompactor` sentinel — `shouldCompact()` 永远 false + `compact()` no-op,保证 `LinearTurnEngine.compactor` 字段非 null(避免 NPE)。

---

## Technical Context

| 项 | 值 |
|---|---|
| Language / Version | Java 1.8(`<source>1.8</source>`,已锁) |
| Primary Dependencies | Spring Boot 3.2.5 / Lombok 1.18.30 / Jackson / JUnit 5 / AssertJ / Mockito(已锁,**0 新增**) |
| Storage | N/A |
| Testing | JUnit 5 + AssertJ + Mockito(JDK 23 + Mockito 5.x inline mockmaker 已 lock-down 配套)** |
| Target Platform | JDK 8 / 11 / 17 / 21 LTS |
| Project Type | library(`lingshu-core`) |
| Performance Goals | dsh §10 NFR row 4 「单 turn history ≤ 100K tokens」必须由代码 enforce |
| Constraints | ≤ 5 核心文件改动(本 Story 3 + NullCompactor 1 + tests 1);≤ 3 ErrorCode(0 新);**0 新 Maven 依赖** |
| Scale/Scope | dsh §10 NFR 全量 turn(s)上完成后语义不变 + 1 个新 SPI 调用 |

---

## Constitution Check

✅ **所有硬约束满足**:
- CLAUDE.md §11 硬约束 #4 Story 边界:本 Story 5 文件改动(LinearTurnEngine / LinearTurnEngineProvider / AgentFactory + 1 sentinel + 1 test)≤ 5,3 ErrorCode 引入 = 0 ≤ 3 — **PASS**
- CLAUDE.md §11 硬约束 #5 不绕过 SPI:走 `CompactorRouter.resolve()` byName 路由,**禁止**硬编码 `new TruncatingCompactor()` —— **PASS**
- CLAUDE.md §11 硬约束 #6 不引入额外依赖:13 项已锁 0 新增,Pre-commit `banned-dependencies` enforcer Rule 0 PASS —— **PASS**
- CLAUDE.md §11 硬约束 #7 ReAct Loop 自实现:`LinearTurnEngine.runTurn()` 主循环自实现(Story #001 起),compactor 插入 L186 是 ReAct 内嵌,不走 Spring AI `ChatClient.tools().call()` —— **PASS**
- CLAUDE.md §11 硬约束 #9 Provider 显式映射:`compactorRouter.resolve(cfg, name -> name)` byName,沿用 v1.5.28 §5.5 多 Provider 模式 —— **PASS**
- Story 边界 ≤ 5 文件:✅ 满足
- 0 新 ErrorCode:✅ 满足
- 0 新 Maven 依赖:✅ 满足
- JDK 8 兼容(无 `var` / `List.of` / sealed / records):✅ 满足

---

## Project Structure

### 本 Story 改动范围

```text
lingshu/
├── lingshu-core/
│   ├── src/main/java/ai/lingshu/core/
│   │   ├── impl/
│   │   │   ├── flow/
│   │   │   │   ├── LinearTurnEngine.java          # MODIFY: 7-arg ctor + runTurn L186 hook
│   │   │   │   └── LinearTurnEngineProvider.java   # MODIFY: 7-arg ctor + compactorRouter.resolve()
│   │   │   ├── compaction/
│   │   │   │   └── NullCompactor.java             # NEW: sentinel fallback
│   │   │   └── runtime/
│   │   │       └── AgentFactory.java              # MODIFY: 9-Router ctor + 6/7/8 back-compat 委托链
│   │   └── event/
│   │       └── AgentEvent.java                    # MODIFY: CompactionTriggered / CompactionCompleted 2-arg ctor
│   └── src/test/java/ai/lingshu/core/
│       ├── impl/flow/
│       │   └── LinearTurnEngineCompactionTest.java        # NEW: 7 L1 unit
│       └── impl/runtime/
│           ├── LinearTurnEngineProviderCompactionTest.java # NEW: 4 L1 unit
│           └── AgentFactoryYamlCompactorIT.java           # NEW: 1 L2 integration
├── specs/045-compactor-turn-trigger/
│   ├── spec.md           # ✅ 已写
│   ├── plan.md           # ✅ 本文件
│   └── tasks.md          # ⏳ 由 Step 3 实施者创建
└── (其他模块 0 改动)
```

---

## 文件改动清单(5 核心 + 2 测试)

### 文件 #1:`LinearTurnEngine.java`(MODIFY,核心)

**当前 6 个 final 字段**:promptBuilder / llmProvider / toolExecutor / permissionPolicy / toolPool / approvalRegistry
**改后 7 个 final 字段**:+ `Compactor compactor`(新增)

**当前 6-arg ctor**:`(PromptBuilder, LlmProvider, ToolExecutor, PermissionPolicy, ExecutorService)` → **改 back-compat**,委托 7-arg ctor with `NullCompactor.INSTANCE`
**当前 7-arg ctor**(`Story #030` 加的,含 ApprovalRegistry):`(PromptBuilder, LlmProvider, ToolExecutor, PermissionPolicy, ExecutorService, ApprovalRegistry)` → **改 back-compat**,委托 new 7-arg ctor(以 `compactor` 为第 7 参数)
**新 7-arg ctor**(本 Story):`(PromptBuilder, LlmProvider, ToolExecutor, PermissionPolicy, ExecutorService, ApprovalRegistry, Compactor)` → **primary ctor**

> ⚠️ **命名冲突**:本 Story 的新 7-arg ctor 与 Story #030 加的 6-arg ctor `LinearTurnEngine(..., ApprovalRegistry)` 参数数量相同(都是 7 个)。需要修改 Story #030 6-arg ctor **降级为 back-compat** 委托新 7-arg ctor(`compactor=NullCompactor.INSTANCE`)。这是 SPI back-compat 行为变更,需要 §4.1 文档声明 "since v1.5.58 the 6-arg ctor delegates to 7-arg with NullCompactor"。

**主循环 hook(L186 之后)**:
```java
Prompt prompt = promptBuilder.build(ctx);
LOG.debug("step {}: prompt built — messages={}, tools={}",
    step, prompt.getMessages().size(), prompt.getTools().size());

// 🆕 Story #045 (dsh §6.1 L3630-3631) — Compactor turn trigger.
if (compactor.shouldCompact(prompt)) {
    LOG.info("step {}: compactor triggered — compacting history (current messages={})",
        step, ctx.history().size());
    sink.onNext(new AgentEvent.CompactionTriggered(step, ctx.history().size()));
    compactor.compact(ctx);
    // 重新 build prompt:旧 prompt.messages 引用旧 history,压缩后必须重建
    prompt = promptBuilder.build(ctx);
    sink.onNext(new AgentEvent.CompactionCompleted(step, ctx.history().size()));
}

// Stream the LLM call; future completes with the final structured response.
CompletableFuture<LlmResponse> fut = llmProvider.stream(prompt, ctx, sink);
```

**新增 import**:`ai.lingshu.core.slot.Compactor`(已存在,`/ai/lingshu/core/slot/Compactor.java` 已在 Story #018 落盘)

**新增 AgentEvent 事件类**:`CompactionTriggered(step, currentHistorySize)` + `CompactionCompleted(step, currentHistorySize)` —— 镜像 Story #030 `ApprovalRequired(approvalId, sessionId, Decision)` precedent,**新增到 `AgentEvent` sealed 家族**。

### 文件 #2:`LinearTurnEngineProvider.java`(MODIFY)

**当前 6 final 字段** + `agentToolPool` 共享池 → **改 7 final 字段** + `agentToolPool` 共享池
**新 7-arg ctor**:`(PromptBuilderRouter, LlmProviderRouter, ToolExecutorRouter, PermissionPolicyRouter, ExecutorService, @Nullable ApprovalRegistry, CompactorRouter)`
**新 6-arg ctor**(back-compat):`(..., @Nullable ApprovalRegistry)` → 委托 7-arg ctor with `compactorRouter=null`

**`create(AgentConfig)` 改动**:
```java
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
```

**新增 import**:
- `ai.lingshu.core.slot.Compactor`(已有)
- `ai.lingshu.core.impl.compaction.NullCompactor`(本 Story 新增)

### 文件 #3:`AgentFactory.java`(MODIFY,9-Router ctor 链扩展)

**当前 8-Router ctor**:`(LlmProviderRouter, ToolExecutorRouter, PermissionPolicyRouter, PromptBuilderRouter, FlowEngineRouter, MemorySourceRouter, RuntimeSandboxRouter, SessionStoreRouter)`(Story #014)
**新 9-Router ctor**:`(LlmProviderRouter, ToolExecutorRouter, PermissionPolicyRouter, PromptBuilderRouter, FlowEngineRouter, MemorySourceRouter, RuntimeSandboxRouter, SessionStoreRouter, CompactorRouter)`

**Legacy ctor 委托链**:
- **8-Router ctor**(Story #014 back-compat)→ 委托 9-Router ctor with `compactorRouter=null`
- **7-Router ctor**(Story #014 back-compat,无 SessionStoreRouter)→ 委托 8-Router ctor with `sessionStoreRouter=null`
- **6-Router ctor**(Story #028 back-compat,无 RuntimeSandboxRouter / SessionStoreRouter)→ 委托 7-Router ctor with `runtimeSandboxRouter=null, sessionStoreRouter=null`

**create() 路径不动**:`linearTurnEngineProvider.create(cfg, flowRouter, ...)` 已传 `flowRouter`,9-Router ctor 注入 `compactorRouter` 后,`linearTurnEngineProvider.create()` 内部 resolve

**新增 import**:`ai.lingshu.core.impl.router.Routers.CompactorRouter`(已有,Story #018 stub)

### 文件 #4:`NullCompactor.java`(NEW,sentinel)

**15 行** 实现模式:
- `public final class NullCompactor implements Compactor`
- `public static final NullCompactor INSTANCE = new NullCompactor()`
- `private NullCompactor() {}`
- `public boolean shouldCompact(Prompt prompt) { return false; }`
- `public void compact(TurnContext ctx) {}`

**包路径**:`ai.lingshu.core.impl.compaction.NullCompactor`(与 `TruncatingCompactor` 同包)

### 文件 #5:`LinearTurnEngineCompactionTest.java`(NEW,7 L1 unit)

```java
class LinearTurnEngineCompactionTest {
    @Test void shouldCompactFalse_skipsCompactor()           // EC-045-1
    @Test void shouldCompactTrue_invokesCompactor()          // AC-045-2
    @Test void shouldCompactTrue_rebuildsPrompt()            // AC-045-2(b)
    @Test void compactThrows_propagatesUp()                  // EC-045-2
    @Test void nullCompactor_skippedNoOp()                   // AC-045-5
    @Test void legacy6ArgCtor_backCompat()                   // Story #030 back-compat 守住
    @Test void nullCompactorCtor_throwsIAE()                 // AC-045-1
}
```

### 文件 #6:`LinearTurnEngineProviderCompactionTest.java`(NEW,4 L1 unit)

```java
class LinearTurnEngineProviderCompactionTest {
    @Test void create_injectsCompactorFromRouter()         // AC-045-3
    @Test void legacy6ArgCtor_backCompat_withNullRouter()   // back-compat
    @Test void create_returns7ArgLinearTurnEngine()        // 7-arg ctor 路径
    @Test void create_usesNullCompactorWhenRouterNull()     // EC-045-3
}
```

### 文件 #7:`AgentFactoryYamlCompactorIT.java`(NEW,1 L2 integration)

```java
@SpringBootTest
class AgentFactoryYamlCompactorIT {
    @Test void ymlTruncating_compactorTriggered_historyShrinks()   // AC-045-6
}
```

构造 11 turn(> threshold),`LinearTurnEngine.runTurn()` 触发 compactor → `ctx.history()` 长度 ≤ `CompactorProps.threshold`。

### 文件 #8:`AgentEvent.java`(MODIFY,新增 2 个 event 子类)

**新增 2 个 nested class**:
```java
public static final class CompactionTriggered extends AgentEvent {
    private final int step;
    private final int currentHistorySize;
    public CompactionTriggered(int step, int currentHistorySize) {
        super(Kind.COMPACTION_TRIGGERED);
        this.step = step;
        this.currentHistorySize = currentHistorySize;
    }
    public int getStep() { return step; }
    public int getCurrentHistorySize() { return currentHistorySize; }
}

public static final class CompactionCompleted extends AgentEvent {
    private final int step;
    private final int currentHistorySize;
    public CompactionCompleted(int step, int currentHistorySize) {
        super(Kind.COMPACTION_COMPLETED);
        this.step = step;
        this.currentHistorySize = currentHistorySize;
    }
    // ...
}
```

**新增 2 个 Kind enum**:`COMPACTION_TRIGGERED` / `COMPACTION_COMPLETED`

---

## 关键不变项(MUST-PRESERVE)

| 不变项 | 守住方式 |
|---|---|
| `Compactor` / `CompactorProvider` SPI 接口契约 | 0 改;只新增 1 个 `NullCompactor` 实现 |
| `Routers.CompactorRouter` 行为(Story #018 已合)| 0 改;本 Story 只通过它 `resolve` |
| `TruncatingCompactor` / `CompactorProps` 实现 | Story #018 落地 0 改 |
| `CompactorAutoConfiguration` 注册路径 | v1.5.28 §5.5 多 Provider 模式 0 改 |
| `AgentConfig` + `CompactorConfig` 不可变契约 | 0 字段新增(Story #018 已合 `compactor.threshold`) |
| `LlmProvider` / `ToolExecutor` / `PermissionPolicy` / `PromptBuilder` SPI | 0 改 |
| `LinearTurnEngine.runTurn()` 主循环结构 | L168 cancel + L184 ReasoningStarted + L217 appendAssistant 0 改;只 L186 之后插 compactor 短路 + L191 stream 之前 |
| `ToolExecutor.dispatch()` 5 步流水线 | 0 改(§4.10.1 硬规则 2) |
| `Message` 4 子类契约 | 0 改 |
| `AgentFactory` ctor 链 back-compat | 6 → 7 → 8 → 9 4 层委托;既有 Story #001-#044 旧 fixture 0 改 |
| 9 Slot 体系 | 仍 9 个(Compactor 是 Slot 6,本来就在 §5.6.4 总表) |
| JDK 8 兼容 | `@Nullable` + Lombok @Value + JDK 8 standard 已锁 |
| Story #018 已合的既有 53 fixture 文件 | 0 改(`CompactorConfig` 字段没动) |
| Story #030 ApprovalRegistry `@Autowired(required=false) @Nullable` precedent | 复用,新增 `compactorRouter=null` back-compat 走同 pattern |

---

## 实施顺序(Step 3 落地路径)

```text
T-01: NullCompactor.java 新增(15 行,无依赖,5 分钟)
T-02: AgentEvent.java 新增 CompactionTriggered/CompactionCompleted 2 个 nested class + Kind enum(40 行,无依赖,15 分钟)
T-03: LinearTurnEngine.java 改 7-arg ctor + 6-arg back-compat + runTurn L186 hook(60 行,20 分钟)
T-04: LinearTurnEngineProvider.java 改 7-arg ctor + 6-arg back-compat + create() resolve(30 行,15 分钟)
T-05: AgentFactory.java 9-Router ctor + 6/7/8 back-compat 委托链(40 行,20 分钟)
T-06: LinearTurnEngineCompactionTest 7 case L1(150 行,30 分钟)
T-07: LinearTurnEngineProviderCompactionTest 4 case L1(100 行,20 分钟)
T-08: AgentFactoryYamlCompactorIT 1 case L2(80 行,15 分钟)
T-09: mvn -pl lingshu-core test 跑 0 fail / R-13 mitigation (d) baseline mirror 第 29 次 PASS(10 分钟)
T-10: docs 同步(README / ROADMAP / constitution / dsh / CLAUDE.md)(30 分钟)
```

**总 T-Ne**:10 任务 / ~3 小时净实施

---

## Complexity Tracking

**无违反**。所有约束都满足(CLAUDE.md §11 硬约束 1-9 + Story 边界 ≤ 5 文件 + 0 新 ErrorCode + 0 新 Maven 依赖)。

---

**Last updated**: 2026-10-10
**Branch**: `045-compactor-turn-trigger`
**Status**: Draft(待用户评审)