# Story #044 `agent-turn-concurrency-cap` — Plan

> **Status**: Draft 2026-10-06
> **关联 spec**: [`spec.md`](./spec.md)
> **关联 tasks**: [`tasks.md`](./tasks.md)
> **核心约束**: 3 modify (AgentConfig.java + AgentConfigDefaults.java + AgentFactory.java) + 2 new test files + 0 new ErrorCode + 0 new Maven dep —— 严格 ≤ 5 文件边界
> **核心 hygiene**: 兑现 dsh §10 NFR row 4 `默认 16, 排队 ≤ 32` docs/code 一致性 + Story #043 显式 forward reference 兑现

---

## 1. 改前 vs 改后 shape(锁定)

### 1.1 改前 AgentConfig 字段(L65 — 24 字段 final)

```java
@Value
public class AgentConfig {
    String flowEngine;
    Llm llm;
    Prompt prompt;
    String toolExecutor;
    Sandbox sandbox;
    String compactor;
    String sessionStore;
    Delegate delegate;
    Mcp mcp;
    Skills skills;
    int toolParallelism;
    int toolTimeoutSeconds;
    int approvalTimeoutSeconds;
    int turnTimeoutSeconds;
    int llmTimeoutSeconds;
    int reactMaxSteps;
    Identity identity;
    Instructions instructions;
    Memory memory;
    String a2aTransport;
    TenantsConfig tenants;
    A2a a2a;
    CompactorConfig compactorConfig;
    ToolsConfig tools;
    String permissionPolicy;
    // 总计 24 字段 final
}
```

- **缺** dsh §10 NFR row 4 承诺的 `maxConcurrentTurns` / `maxConcurrentQueueDepth` 字段
- AgentConfig 顶层**无** `validate()` 方法(只有 `ToolsConfig` / `CompactorConfig` / `TenantsConfig` 子配置类有)

### 1.2 改后 AgentConfig 字段(L67-68 新增 + L82-94 `validate()` 新增)

```java
@Value
public class AgentConfig {
    String flowEngine;
    Llm llm;
    Prompt prompt;
    String toolExecutor;
    Sandbox sandbox;
    String compactor;
    String sessionStore;
    Delegate delegate;
    Mcp mcp;
    Skills skills;
    int toolParallelism;
    int toolTimeoutSeconds;
    int approvalTimeoutSeconds;
    int turnTimeoutSeconds;
    int llmTimeoutSeconds;
    int reactMaxSteps;
    Identity identity;
    Instructions instructions;
    Memory memory;
    String a2aTransport;
    TenantsConfig tenants;
    A2a a2a;
    CompactorConfig compactorConfig;
    ToolsConfig tools;
    String permissionPolicy;
    /** 🆕 Story #044 — top-level cap on simultaneously running turns (dsh §10 NFR row 4).
     *  Default {@code 16}. Future turn-scheduler (Story #046+) consumes this. */
    int maxConcurrentTurns;
    /** 🆕 Story #044 — bounded queue depth for excess turns waiting on a slot
     *  (dsh §10 NFR row 4). Default {@code 32}. */
    int maxConcurrentQueueDepth;
    // 总计 26 字段 final

    /** 🆕 Story #044 — top-level validate (aligns CompactorConfig.validate() Story #018).
     *  Aggregates all errors into a single LingsConfigException with code {@code "C02"}. */
    public void validate() {
        List<String> errors = new ArrayList<>();
        if (reactMaxSteps <= 0) {
            errors.add("agent.reactMaxSteps must be > 0 (got " + reactMaxSteps + ")");
        }
        if (maxConcurrentTurns <= 0) {
            errors.add("agent.maxConcurrentTurns must be > 0 (got " + maxConcurrentTurns + ")");
        }
        if (maxConcurrentQueueDepth <= 0) {
            errors.add("agent.maxConcurrentQueueDepth must be > 0 (got " + maxConcurrentQueueDepth + ")");
        }
        if (!errors.isEmpty()) {
            throw new LingsConfigException("C02",
                "agent config validation failed:\n  - " + String.join("\n  - ", errors));
        }
    }
}
```

### 1.3 AgentConfigDefaults 改后 `defaults()`(L73-74 新增 2 实参)

```java
public static AgentConfig defaults() {
    return new AgentConfig(
        // ... 既有 24 参数 ...
        50,      // reactMaxSteps
        16,      // 🆕 Story #044 — maxConcurrentTurns (dsh §10 NFR row 4 default 16)
        32);     // 🆕 Story #044 — maxConcurrentQueueDepth (dsh §10 NFR row 4 queue ≤ 32)
}
```

### 1.4 AgentFactory 改后 `toAgentConfig`(L569-572 新增 2 行 `intOr()` + 末尾追加 2 实参)

```java
int reactMaxSteps = intOr(agent, "reactMaxSteps", 50);
// 🆕 Story #044 — top-level turn concurrency cap (dsh §10 NFR row 4)
int maxConcurrentTurns = intOr(agent, "maxConcurrentTurns", 16);
int maxConcurrentQueueDepth = intOr(agent, "maxConcurrentQueueDepth", 32);

return new AgentConfig(
    // ... 既有 24 参数 ...
    AgentConfig.ToolsConfig.defaults(),      // tools (Story #019)
    DEFAULT_NAME,                            // permissionPolicy (Story #029)
    maxConcurrentTurns,                      // 🆕 Story #044
    maxConcurrentQueueDepth);                // 🆕 Story #044
```

### 1.5 字段对齐验证

`dsh_agent_design.md` §10 NFR row 4 vs `AgentConfig.@Value` 字段 vs YAML key:

| 项 | dsh §10 NFR row 4 | `AgentConfig.java` 字段 | YAML key(`AgentFactory.toAgentConfig`) |
|---|---|---|---|
| 最大并发 turn 数 | 默认 16(可配 `agent.factory.max-turns`)| `int maxConcurrentTurns` 默认 16 | `agent.maxConcurrentTurns`(camelCase top-level,镜像 `agent.reactMaxSteps` precedent)|
| 排队深度 | ≤ 32(可配 ...)| `int maxConcurrentQueueDepth` 默认 32 | `agent.maxConcurrentQueueDepth`(camelCase top-level)|

**dsh 文档 vs 代码轻微 drift**:
- dsh §10 NFR row 4 写「`agent.factory.max-turns`」(暗示嵌套 factory 命名空间),本 Story 镜像现有 precedent `agent.reactMaxSteps` 改 top-level camelCase
- dsh §10 NFR row 4 footnote 兑现 marker 同步加「本 Story 改 top-level camelCase 与既有 precedent 对齐」

---

## 2. 接口契约(0 SPI 改动)

### 2.1 公开方法签名锁定

| 方法 | 签名 | 改动 |
|---|---|---|
| `Agent.runBlocking(String) → RunResult` | SPI | 不变 ✓ |
| `Agent.continueWithUserMessageBlocking(String) → RunResult` | SPI | 不变 ✓ |
| `AgentFactory.create(AgentConfig) → Agent` | SPI | 不变 ✓(本 Story **不**新增 boot invariants)|
| `AgentFactory.loadYamlAndValidate(Path) → AgentConfig` | SPI | 不变 ✓(`toAgentConfig()` 内部扩 2 字段)|
| `AgentFactory` 6 关键 boot invariants(L42-51 javadoc)| 内部 | 不变 ✓ |
| `AgentConfig.@Value` Lombok 自动 final | 内部 | 24 → 26 字段(final 自动注入)|
| `AgentConfig.validate()` | 内部 | **新增** —— 复用现有 `LINGS-C02` 路径 |

### 2.2 行为不变性

| 场景 | 改前 | 改后 |
|---|---|---|
| 空 yml 启动 demo-product / demo-empty | OK | OK(默认值 16 + 32 自动 fallback)|
| yml 加 `agent.maxConcurrentTurns: 32` | 静默忽略 | 真生效 `cfg.getMaxConcurrentTurns() == 32` |
| yml 加 `agent.maxConcurrentTurns: 0` | (无字段)| `validate()` 抛 `LINGS-C02` |
| 现有 700+ 测试 fixture 全过 | ✓ | ✓(本 Story 不动任何 fixture)|
| 现有 `AgentFactory.create()` 7 项 boot invariants | ✓ | ✓(不变)|

---

## 3. 文件改动(严格 ≤ 5 文件)

### 3.1 已落盘(2026-10-06 — 本 Story 实施期)

| # | 路径 | 改动 | 行数 |
|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | modify:扩 2 字段 + 类级 Javadoc + `validate()` 新增 | +28 / -2 |
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java` | modify:`defaults()` 末尾加 2 实参 | +2 / -0 |
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | modify:`toAgentConfig()` 加 2 行 `intOr()` + `new AgentConfig(...)` 末尾加 2 实参 | +4 / -0 |
| 4 | `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigConcurrencyCapValidationTest.java` | new L1 unit test | ~80 |
| 5 | `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlConcurrencyCapIT.java` | new L2 IT | ~50 |

**严格 ≤ 5 文件边界**(实际 5 文件,留 0 文件 buffer)。

---

## 4. 测试策略

### 4.1 新增 L1 unit test(`AgentConfigConcurrencyCapValidationTest`)

镜像 `AgentConfigCompactorValidationTest` Story #018 precedent:

| Case | 验证 |
|---|---|
| `defaults_passValidation` | `AgentConfigDefaults.defaults().validate()` 不抛 |
| `positiveCustomValues_passValidation` | `new AgentConfig(...32, 64...).validate()` 不抛 |
| `zeroMaxConcurrentTurns_throwsLingsC02` | `maxConcurrentTurns=0` 抛 `C02` 含 `maxConcurrentTurns` |
| `negativeMaxConcurrentTurns_throwsLingsC02` | `maxConcurrentTurns=-1` 抛 `C02` |
| `zeroMaxConcurrentQueueDepth_throwsLingsC02` | `maxConcurrentQueueDepth=0` 抛 `C02` |
| `negativeMaxConcurrentQueueDepth_throwsLingsC02` | `maxConcurrentQueueDepth=-1` 抛 `C02` |
| `allFieldsZero_aggregatesAllErrors` | 聚合错误消息含 `maxConcurrentTurns` + `maxConcurrentQueueDepth` |
| `AgentConfigDefaults_passValidation` | 整 schema 端到端 `AgentConfigDefaults.defaults().validate()` 不抛 |

### 4.2 新增 L2 IT(`AgentFactoryYamlConcurrencyCapIT`)

镜像 `AgentFactoryYamlPermissionPolicyIT` Story #029 precedent:

| Case | 验证 |
|---|---|
| `yamlDefault_parses16And32` | 空 yml → `cfg.getMaxConcurrentTurns() == 16 && cfg.getMaxConcurrentQueueDepth() == 32` |
| `yamlCustomMaxTurns_parsesCustom` | yml `maxConcurrentTurns: 64` → `cfg.getMaxConcurrentTurns() == 64` |
| `yamlCustomQueueDepth_parsesCustom` | yml `maxConcurrentQueueDepth: 128` → `cfg.getMaxConcurrentQueueDepth() == 128` |
| `yamlBothCustom_parsesBoth` | 2 字段都 custom,validate() 不抛 |
| `yamlZeroMaxTurns_throwsC02OnValidate` | yml `maxConcurrentTurns: 0` → `validate()` 抛 `C02` |

### 4.3 反向验证(0 回归)

- `mvn -pl lingshu-core test -Dtest='AgentConfig*'` 全部 PASS,0 改动(`AgentConfigCompactorValidationTest` / `AgentConfigAskListTest` / `AgentConfigMcp*Test` 全部不动)
- `mvn -pl lingshu-core test` 全量:707 旧 pass + 11 新 case,0 failure,0 新 flake

### 4.4 L3 / E2E IT 不需要

- 本 Story 是**纯字段 + 字段 validation + YAML 接线** docs/code 兑现,无新增行为
- 字段消费端(future turn scheduler Story #046+)才需要 L3 IT
- L1 unit test + L2 IT + 现有 L2/L3 IT 不回归 = 充分验证

---

## 5. 实施顺序

1. **T-1**: `AgentConfig.java` 扩 2 字段 + 类级 Javadoc + `validate()` 新增
2. **T-2**: `AgentConfigDefaults.java` `defaults()` 末尾加 2 实参
3. **T-3**: `AgentFactory.java` `toAgentConfig()` 加 2 行 `intOr()` + `new AgentConfig(...)` 末尾加 2 实参
4. **T-4**: 写 `AgentConfigConcurrencyCapValidationTest.java` L1 unit(7-8 case)
5. **T-5**: 写 `AgentFactoryYamlConcurrencyCapIT.java` L2 IT(4-5 case)
6. **T-6**: `mvn -pl lingshu-core test` 全 PASS,无 regression(707 + 11 new = 718)
7. **T-7**: `mvn -pl lingshu-core dependency:tree` pre/post diff = 仅时间戳差异 = 0 binary delta(第 27 次 PASS)
8. **T-8**: `banned-dependencies` enforcer build 阶段 fail(R-13 mitigation (d)):passed
9. **T-9**: commit + docs 同步(dsh §13 + dsh §10 NFR row 4 footnote + constitution §10 + ROADMAP + CLAUDE.md)

---

## 6. 关键不变项 + RAC 兜底

- `Agent.runBlocking()` / `continueWithUserMessageBlocking()` 公开签名不变 ✓
- `AgentFactory` SPI 不变(@Autowired 7-Router ctor 不动,只 `toAgentConfig()` 内部加 2 行 `intOr()` 读取)✓
- `LinearTurnEngine` 公开签名不变(本 Story 不触碰 ReAct 循环)✓
- `AgentFactory` 6 关键 boot invariants 不变(本 Story **不**新增 invariants)✓
- `LlmProvider` SPI + `Tool` SPI + `PromptBuilder` SPI 等其他 8 Slot 不变 ✓
- `Message` 4 子类 + 字段不变(本 Story 不触碰)✓
- `Prompt` + `ToolSpec` + `ToolRegistry.modelVisibleSpecs()` 不变 ✓
- `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2 守住)✓
- `Tool` SPI 不变 + `LinearTurnEngine` 公开签名不变 ✓
- §4.7 PermissionPolicy / AuditLogger / Cost 域 完全兼容 ✓
- 9 Slot 体系不变 ✓
- 24 → 26 字段 AgentConfig schema 扩展(2 个字段新增)✓
- JDK 8 兼容(纯 Lombok `@Value` + `int` 字段 + `List<String> errors` 聚合 + `throw new LingsConfigException`,全 JDK 8 / 已锁 13 项依赖表内 0 新 binary 引入,no `var` / `List.of` / sealed / records)✓
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)✓
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)✓

---

## 7. 文件清单

| 路径 | 改动 |
|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | modify +28 / -2 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java` | modify +2 / -0 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | modify +4 / -0 |
| `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigConcurrencyCapValidationTest.java` | new ~80 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlConcurrencyCapIT.java` | new ~50 |

**严格 ≤ 5 文件边界**(实际 5 文件,留 0 文件 buffer)。