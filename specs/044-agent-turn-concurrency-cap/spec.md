# Story #044 `agent-turn-concurrency-cap` — Spec

> **Status**: Draft 2026-10-06
> **Source**: 设计文档 `dsh_agent_design.md` §10 NFR row 4 + Story #043 显式 forward reference + Story #043 tasks T-5.2 「`dsh_agent_design.md` §10 NFR 表 "并发 turn 数" 行加 footnote → Story #044 处理 docs/code 兑现」
> **前置依赖**: 无(纯 docs/code 兑现 + AgentConfig 字段新增)
> **本 Story 体量**: 2 modify(AgentConfig.java + AgentConfigDefaults.java + AgentFactory.java 视为一组)+ 1 new test file + 0 new ErrorCode + 0 new Maven dep —— 严格 ≤ 5 文件边界内

---

## 状态

[x] Draft  [ ] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` §10 NFR row 4 L7140(`最大并发 turn 数 | 默认 16(可配 ... ) | agent.turns.in_flight gauge | 超过排队,排队深度 ≤ 32(Story #043 单池已有界 cores*4 + 256 queue,Story #044 docs/code 兑现 maxConcurrentTurns 顶层闸门)`)
- **Story #043 显式 forward reference**(已合,2026-10-06):
    - `specs/043-anthropic-llm-io-bounded-pool/spec.md:51` —— 「`AgentConfig` 不可变契约不变(0 字段新增 —— Story #044 才加 `maxConcurrentTurns`)」
    - `specs/043-anthropic-llm-io-bounded-pool/plan.md:172` —— 「`AgentConfig` 不可变契约不变(0 字段新增 —— Story #044 才加 `maxConcurrentTurns`)✓」
    - `specs/043-anthropic-llm-io-bounded-pool/tasks.md:141` —— 「[ ] `dsh_agent_design.md` §10 NFR 表 "并发 turn 数" 行加 footnote → Story #044 处理 docs/code 兑现」
    - `lingshu-core/src/main/java/ai/lingshu/core/impl/llm/AnthropicLlmProvider.java:97` —— 「leaving a latent OOM vector if concurrent-turn scheduling is added before Story #044's top-level `maxConcurrentTurns` gate」
    - `specs/043-anthropic-llm-io-bounded-pool/CLAUDE.md:226`(Story #043 commit message)—— 「**Story #044 准备** —— 单池已有界,Story #044 加 `maxConcurrentTurns` 顶层闸门时,N 并发 turn × 单池上限 = 仍有界,**不会爆**」
- **业务后果**(Story #043 落地后):
    - 单池有界(`cores*4 + 256` 任务)已满足
    - 但**仍无顶层 turn 并发闸门** —— dsh §10 NFR 行声明的「默认 16,排队 ≤ 32」目前是**纯文档**承诺,代码层面 `AgentConfig` 没有相关字段
    - 未来若加并发 turn scheduler(Story #046+),需要 `AgentConfig` 暴露这 2 个字段
- **对应风险**: **R-13**(无新依赖守住)+ docs/code 一致性(dsh §10 NFR row 4 数字基线目前没兑现)
- **涉及 ErrorCode**: **0 新 ErrorCode**(纯字段 + 字段/字段 validation 复用现有 `LINGS-C02`)

---

## 1. WHY(为什么做这个 Story)

**核心问题**: dsh §10 NFR row 4 声明「最大并发 turn 数 = 默认 16,排队 ≤ 32」,但 `AgentConfig` 当前 schema 不暴露任何对应字段:

1. **docs/code 不一致** —— 设计文档承诺的数字基线,代码层面无对应 `AgentConfig` 字段
2. **Story #043 forward reference 未兑现** —— Story #043 已合但显式声明「Story #044 才加 maxConcurrentTurns」,本 Story 是 cleanup forward reference
3. **未来并发 turn 调度无配置入口** —— dsh §5.6/§6/§14.xx 等多处暗示并发 turn scheduler 是未来 Story 方向,本 Story 先把字段 + 字段 validation + YAML 字段上架,future scheduler Story 只需消费字段即可

**Story #044 业务价值**:

- **字段上架** —— `AgentConfig` 扩 2 字段 `maxConcurrentTurns`(默认 16)+ `maxConcurrentQueueDepth`(默认 32),`@Value` + `@Builder` 不可变契约扩展(24 → 26 字段 final)
- **字段 validation** —— `AgentConfig.validate()` 复用现有 `LINGS-C02` 路径,> 0 才合法,聚合所有错误消息(对齐 `CompactorConfig.validate()` precedent Story #018)
- **YAML 接线** —— `AgentFactory.toAgentConfig()` 加 2 行 kebab-case(camelCase top-level)+ `intOr(...)` 读取,空 yml 启动默认 16 + 32
- **docs/code 一致** —— dsh §10 NFR row 4 footnote 「Story #044 已合」,constitution §10 R-13 缓解 Story 列表补 `#044` 行,CLAUDE.md 同步 v1.3.51 → v1.3.52

**关键不变项**(本 Story 严格限定):

- `Agent.runBlocking()` / `continueWithUserMessageBlocking()` 公开签名不变(Story #044 **不**引入 turn scheduler —— 那是 Story #046+ 范畴)
- `AgentFactory` SPI 不变(@Autowired 7-Router ctor 不动,只 `toAgentConfig()` 内部加 2 行 `intOr()` 读取)
- `LinearTurnEngine` 公开签名不变(本 Story 不触碰 ReAct 循环)
- `AgentFactory` 6 关键 boot invariants(§4.12.2 / `create()` 方法)不变 —— **不**新增 invariants
- `LlmProvider` SPI + `Tool` SPI + `PromptBuilder` SPI 等其他 8 Slot 不变
- §4.7 PermissionPolicy / AuditLogger / Cost 域完全兼容
- 9 Slot 体系不变
- 24 → 26 字段 AgentConfig schema 扩展(2 个字段新增)
- JDK 8 兼容(`Semaphore` + `ConcurrentLinkedQueue` + `AtomicInteger` 全 JDK 8 内置,no `var` / `List.of` / sealed / records)
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **设计文档 reviewer** | dsh §10 NFR row 4 数字基线(默认 16,排队 ≤ 32)真在 AgentConfig schema 兑现,docs/code 一致 |
| **Story #046+ 实施者(并发 turn scheduler)** | 拿 `cfg.getMaxConcurrentTurns()` + `cfg.getMaxConcurrentQueueDepth()` 即可,无需 per-implementation config 而定 |
| **Agent 运维 / 生产部署** | yml 加 `agent.maxConcurrentTurns: 32` 即可调高闸门,无需改 Java 代码 |
| **新用户 / demo-product / demo-empty 跑者** | 空 yml 启动,默认值 16 + 32 自动生效,无需任何配置 |
| **Story #043 加重班** | forward reference 兑现,故事线完整 |

---

## 3. WHAT(交付什么 — 用户视角)

### 3.1 用户可见行为

**空 yml 默认值**:
- 改前:`AgentConfig` 无 `maxConcurrentTurns` / `maxConcurrentQueueDepth` 字段,dsh §10 NFR row 4 数字基线无兑现
- 改后:yml `agent.maxConcurrentTurns: 16` + `agent.maxConcurrentQueueDepth: 32` 自动 fallback(空 yml 走 `AgentConfigDefaults.defaults()` 给默认值),future turn scheduler Story 消费即可

**YAML 接线**:
- 改前:yml 加 `agent.maxConcurrentTurns: 32` 被 `loadYamlAndValidate` 静默忽略(`intOr()` 不报 unknown key)
- 改后:`agent.maxConcurrentTurns: 32` 真生效,`AgentConfig.getMaxConcurrentTurns() == 32`

**字段 validation**:
- 改前:无 (字段不存在)
- 改后:`AgentConfig.validate()` 内追加 `if (maxConcurrentTurns <= 0)` + `if (maxConcurrentQueueDepth <= 0)` 检查,失败 throw `LingsConfigException("C02", "...")` 复用 Story #001 + Story #018 precedent

### 3.2 API / SPI 改动

**0 SPI 改动**:
- `LlmProvider.stream()` 签名不变
- `Tool` SPI 不变
- `Agent.runBlocking()` / `continueWithUserMessageBlocking()` 签名不变
- `AgentFactory.create(AgentConfig)` 签名不变
- `AgentFactory.loadYamlAndValidate(Path)` 签名不变

**AgentConfig 不可变契约扩展**:
- 新增 2 字段 `int maxConcurrentTurns` + `int maxConcurrentQueueDepth`
- `@Value` Lombok 自动 final 注入,字段数 24 → 26
- 新增 `validate()` 内追加 2 行 `if (... <= 0)` 检查,复用现有 `LINGS-C02` 路径

**AgentConfigDefaults 扩展**:
- `defaults()` 加 2 参数 `16, 32`

**AgentFactory 扩展**:
- `toAgentConfig()` 加 2 行 `int maxConcurrentTurns = intOr(agent, "maxConcurrentTurns", 16);` + `int maxConcurrentQueueDepth = intOr(agent, "maxConcurrentQueueDepth", 32);`
- `new AgentConfig(...)` 构造器参数追加 2 实参

### 3.3 行为不变性

| 场景 | 改前 | 改后 |
|---|---|---|
| 空 yml 启动 demo-product / demo-empty | OK(`AgentConfigDefaults.defaults()` 走默认值)| OK(默认值 16 + 32,future scheduler Story 才有 observable 效果)|
| yml 加 `agent.maxConcurrentTurns: 32` | 静默忽略 | 真生效 `cfg.getMaxConcurrentTurns() == 32` |
| yml 加 `agent.maxConcurrentTurns: 0` | (无字段)| `AgentConfig.validate()` 抛 `LINGS-C02` |
| yml 加 `agent.maxConcurrentQueueDepth: 0` | (无字段)| `AgentConfig.validate()` 抛 `LINGS-C02` |
| yml 加 `agent.maxConcurrentTurns: -1` | (无字段)| `AgentConfig.validate()` 抛 `LINGS-C02` |
| 现有 700+ 测试 fixture | 全过 | **不动**(forward reference 兑现,无 behavior 改动)|
| `AgentFactory.create(AgentConfig)` 7 项 boot invariants | 全过 | **不动**(本 Story **不**新增 invariants)|

---

## 4. HOW(交付什么 — 实现视角)

### 4.1 改文件清单(严格 ≤ 5 文件)

| # | 文件 | 类型 | 行数(预估) |
|---|---|---|---|
| 1 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | modify | +30 / -2(扩 2 字段 + `validate()` 2 段检查 + 类级 Javadoc)|
| 2 | `lingshu-core/src/main/java/ai/lingshu/core/impl/config/AgentConfigDefaults.java` | modify | +2 / -0(`defaults()` 加 2 实参)|
| 3 | `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/AgentFactory.java` | modify | +4 / -0(`toAgentConfig()` 加 2 行 `intOr()` + `new AgentConfig(...)` 末尾加 2 实参)|
| 4 | `lingshu-core/src/test/java/ai/lingshu/core/runtime/AgentConfigConcurrencyCapValidationTest.java` | new | ~80(L1 unit:5-7 case)|
| 5 | `lingshu-core/src/test/java/ai/lingshu/core/impl/runtime/AgentFactoryYamlConcurrencyCapIT.java` | new | ~50(L2 IT:3-4 case,镜像 `AgentFactoryYamlPermissionPolicyIT` Story #029 precedent)|

**严格 ≤ 5 文件边界内** ✓(实际 5 文件 Java + 3 文件 markdown = 8 文件,但** Java 改动仅 5 文件,markdown 是 SpecKit SOP 流程制品**)

### 4.2 实现关键点

**(1)** `AgentConfig.java` 字段扩:

```java
// 改前 L65: 已有 reactMaxSteps
int reactMaxSteps;

/** 🆕 Story #044 — top-level cap on simultaneously running turns (dsh §10 NFR row 4).
 *  Default {@code 16}. Future turn-scheduler (Story #046+) consumes this to bound
 *  global concurrency. {@code 0} or negative is rejected by {@link #validate()}. */
int maxConcurrentTurns;

/** 🆕 Story #044 — bounded queue depth for excess turns waiting on a slot
 *  (dsh §10 NFR row 4). Default {@code 32}. Excess turns beyond {@code maxConcurrent +
 *  maxConcurrentQueueDepth} are rejected (future scheduler contract). */
int maxConcurrentQueueDepth;
```

新增 `validate()` 方法(对齐 `CompactorConfig.validate()` precedent):

```java
public void validate() {
    List<String> errors = new ArrayList<>();
    if (reactMaxSteps <= 0) {
        errors.add("agent.reactMaxSteps must be > 0 (got " + reactMaxSteps + ")");
    }
    // 🆕 Story #044 — concurrency cap validation
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
```

**注意**:`AgentConfig` 当前**没有** `validate()` 方法(只有 `ToolsConfig` / `CompactorConfig` / `TenantsConfig` 子配置类有),Story #044 新增顶层 `validate()` 是新模式 —— 镜像 Story #018 `CompactorConfig.validate()` precedent。

**(2)** `AgentConfigDefaults.java` 扩展:

```java
return new AgentConfig(
    // ... 既有 24 参数 ...
    50,      // reactMaxSteps
    16,      // 🆕 Story #044 — maxConcurrentTurns (dsh §10 NFR row 4 default 16)
    32);     // 🆕 Story #044 — maxConcurrentQueueDepth (dsh §10 NFR row 4 queue ≤ 32)
```

**(3)** `AgentFactory.java` 扩展(`toAgentConfig` 内部):

```java
int reactMaxSteps = intOr(agent, "reactMaxSteps", 50);
// 🆕 Story #044 — top-level turn concurrency cap (dsh §10 NFR row 4)
int maxConcurrentTurns = intOr(agent, "maxConcurrentTurns", 16);
int maxConcurrentQueueDepth = intOr(agent, "maxConcurrentQueueDepth", 32);
```

`new AgentConfig(...)` 构造器末尾追加 2 实参。

**(4)** 新测试 `AgentConfigConcurrencyCapValidationTest`(~80 行,5-7 L1 case,镜像 `AgentConfigCompactorValidationTest` Story #018 precedent):

- `defaults_passValidation` —— `new AgentConfig(...16, 32...).validate()` 不抛
- `positiveCustomValues_passValidation` —— `new AgentConfig(...32, 64...).validate()` 不抛
- `zeroMaxConcurrentTurns_throwsLingsC02` —— `maxConcurrentTurns=0` 抛 `LingsConfigException` code `C02` 含 `maxConcurrentTurns`
- `negativeMaxConcurrentTurns_throwsLingsC02` —— `maxConcurrentTurns=-1` 抛 `C02`
- `zeroMaxConcurrentQueueDepth_throwsLingsC02` —— `maxConcurrentQueueDepth=0` 抛 `C02`
- `allFieldsZero_aggregatesAllErrors` —— 聚合错误消息含 `maxConcurrentTurns` + `maxConcurrentQueueDepth`
- `AgentConfigDefaults_passValidation` —— `AgentConfigDefaults.defaults().validate()` 不抛(整 schema 端到端)

**(5)** 新测试 `AgentFactoryYamlConcurrencyCapIT`(~50 行,3-4 L2 IT,镜像 `AgentFactoryYamlPermissionPolicyIT` Story #029 precedent):

- `yamlDefault_parses16And32` —— 空 yml 启动 → `cfg.getMaxConcurrentTurns() == 16 && cfg.getMaxConcurrentQueueDepth() == 32`
- `yamlCustomMaxTurns_parsesCustom` —— yml `agent.maxConcurrentTurns: 64` → `cfg.getMaxConcurrentTurns() == 64`
- `yamlCustomQueueDepth_parsesCustom` —— yml `agent.maxConcurrentQueueDepth: 128` → `cfg.getMaxConcurrentQueueDepth() == 128`
- `yamlBothCustom_parsesBoth` —— 2 字段都 custom,validate() 不抛

### 4.3 风险与回退

- **回退成本极低** —— 仅 AgentConfig 扩 2 字段 + AgentConfigDefaults 加 2 实参 + AgentFactory 加 2 行 `intOr()`,无 SPI 改动,回滚 `git revert <commit>` 即可
- **无运行时回归** —— forward reference 兑现,无 behavior 改动,future scheduler Story 才能消费字段
- **现有 fixture 兼容性** —— `@Value` Lombok 自动 final 注入,**所有现有 `new AgentConfig(...)` 调用必须同步更新** —— 影响面包括:`AgentConfigDefaults` (本 Story 改)+ `AgentFactory.toAgentConfig()` (本 Story 改)+ 任何 stub 写法 (需全文 grep)

---

## 5. AC(Acceptance Criteria — 黑盒可验证)

### AC-NN-deps-1: R-13 守住(0 新 Maven 依赖)
- `mvn -pl lingshu-core dependency:tree` pre/post diff:**0 binary delta**(本 Story 改 3 个 Java source + 加 2 个 Java test,**0 新 binary 引入**)
- `banned-dependencies` enforcer Rule 0: passed
- `R-13 mitigation (d) baseline 镜像` PASS 第 27 次

### AC-NN-pool-1: AgentConfig 字段 + validate
- `new AgentConfig(...)` 26 字段 final(24 旧 + 2 新)PASS
- `AgentConfig.validate()` happy path `defaults().validate()` 不抛
- `validate()` 边界 case:zero/negative 任何 1 字段抛 `LingsConfigException("C02", ...)` 含字段名

### AC-NN-name-1: YAML 接线 default + custom
- `loadYamlAndValidate(empty.yml)` → `cfg.getMaxConcurrentTurns() == 16 && cfg.getMaxConcurrentQueueDepth() == 32`
- `loadYamlAndValidate(yml_with_maxConcurrentTurns_64)` → `cfg.getMaxConcurrentTurns() == 64`
- `loadYamlAndValidate(yml_with_both_custom)` → 2 字段都 custom,validate() 不抛

### AC-NN-1: 回归 — 现有 AgentConfig 测试 0 改动全绿
- `mvn -pl lingshu-core test -Dtest='AgentConfig*'` 全 PASS
- `AgentConfigCompactorValidationTest` / `AgentConfigAskListTest` / `AgentConfigMcp*Test` **0 改动全过**(forward ref 兑现,无 behavior 改动)

### AC-NN-2: 全量测试套件 0 新增 failure
- `mvn -pl lingshu-core test`:existing 707 pass + 7-11 新 case,failure 数 0,error 数 = 2 MCP heartbeat flake pre-existing(CLAUDE.md 已记,与本 Story 无关)
- `banned-dependencies` enforcer build 阶段 fail(R-13 mitigation (d)):passed

---

## 6. Story 完成 Checklist(实施时)

- [ ] spec.md ✅ (本文件)
- [ ] plan.md(创建 `specs/044-agent-turn-concurrency-cap/plan.md` —— 镜像 Story #043 格式,简化为 3 modify + 2 new test file)
- [ ] tasks.md(创建 `specs/044-agent-turn-concurrency-cap/tasks.md` —— 依赖排序:1)AgentConfig.java 加字段 + validate() 2)AgentConfigDefaults.java 加默认值 3)AgentFactory.java 加 YAML 解析 4)L1 unit test 5)L2 IT 7)R-13 自查)
- [ ] 分支 `feat/agent-turn-concurrency-cap`
- [ ] 3 file modify + 2 file new test
- [ ] 5-7 L1 test case + 3-4 L2 IT case 全部 PASS
- [ ] `mvn -pl lingshu-core dependency:tree` 0 binary delta
- [ ] 现有 707 测试 0 改动全绿
- [ ] commit message:`feat(agent-config): Story #044 agent-turn-concurrency-cap — 兑现 dsh §10 NFR row 4 docs/code + AgentConfig 扩 2 字段 + R-13 0 binary delta`
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节
- [ ] `constitution.md` §10 R-13 缓解 Story 列表补 `#044` 行(第 27 次 PASS 0 binary delta)
- [ ] `dsh_agent_design.md` §13 changelog 新增 v1.5.57 行(本 Story 完成)
- [ ] `dsh_agent_design.md` §10 NFR row 4 footnote「Story #044 已合」兑现(原 Story #043 forward ref 兑现 marker)
- [ ] `specs/ROADMAP.md` 段一 ✅ 已完成加 #044 行
- [ ] `CLAUDE.md` 同步 v1.3.51 → v1.3.52(本 Story 文档同步)

---

## 7. 关键不变项再确认(Story 边界守住)

- `Agent.runBlocking()` / `continueWithUserMessageBlocking()` 公开签名不变(Story #044 **不**引入 turn scheduler)✓
- `AgentFactory` SPI 不变(@Autowired 7-Router ctor 不动,只 `toAgentConfig()` 内部加 2 行 `intOr()` 读取)✓
- `LinearTurnEngine` 公开签名不变(本 Story 不触碰 ReAct 循环)✓
- `AgentFactory` 6 关键 boot invariants 不变(本 Story **不**新增 invariants)✓
- `LlmProvider` SPI + `Tool` SPI + `PromptBuilder` SPI 等其他 8 Slot 不变 ✓
- §4.7 PermissionPolicy / AuditLogger / Cost 域完全兼容 ✓
- 9 Slot 体系不变 ✓
- 24 → 26 字段 AgentConfig schema 扩展(2 个字段新增)✓
- JDK 8 兼容(纯 Lombok `@Value` + `int` 字段 + `List<String> errors` 聚合 + `throw new LingsConfigException`,全 JDK 8 / 已锁 13 项依赖表内 0 新 binary 引入)✓
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2 守住)✓
- ReAct Loop 自实现不变(§4.10.1 硬规则 1 守住)✓

---

**Story #044 完成预估**: ≤ 3 modify + ≤ 2 new test file + ≤ 11 case AC 黑盒 + R-13 0 binary delta —— 1 个 PR 合入
**累计 Story 合入**(完成后): 43 → **44**
**R-13 mitigation (d) PASS 计数**:本 Story = **第 27 次** 0 binary delta
**对应设计文档**: `dsh_agent_design.md` v1.5.57(完成后)
**对应 SpecKit SOP**: `speckit_operator_prompt.md` v1.18(不变)
**对应 SKILL**: `lingshu-spec-driven-dev` v1.0.21(不变)
**对应 Prompt 速查**: `lingshu_spec_prompts.md` v1.0.16(不变)