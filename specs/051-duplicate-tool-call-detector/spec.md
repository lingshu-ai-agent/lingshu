# Story #051 — Anti-Verification-Loop Prompt Clause(LLM 验证循环 prompt 工程修复)

**Story #**: #051
**Slug**: `duplicate-tool-call-detector`(文件名沿用;**实际 scope 已从 engine-guard 缩为 prompt-revision**,详见 §WHY 根因)
**dsh §**: §4.5.1 [INSTRUCTIONS] 段装配(Story #002 落地层)
**类型**: Prompt-revision Story(用户实测后重新分类,2026-10-16)
**预算**: 1 prod 文件改动 / 0 ErrorCode / 0 新 Maven 依赖(原 engine-guard 计划 4 prod + 1 ErrorCode **全部撤销**)
**挡板**: 用户 2026-10-16 33-step SSE trace 实证「反复读同一文件验证结果」是 LLM semantic 行为,**不是** Agent 引擎 bug → 修在 prompt 层而非 engine guard

---

## WHY(根因 + 业务价值 + 范围切换记录)

### 问题(用户 2026-10-16 实测)

demo-product 跑 `mvn spring-boot:run`,prompt:`帮我写一首以秋月为题的七言古诗,存储在 poem.txt`。

**SSE trace 33 reasoning steps** 关键观察:
- step 6:`write_file(path=/tmp/poem.txt, content=106字节)` → **success**
- step 7—14:`read_file(/tmp/poem.txt)` × 多次 + `list_dir(/tmp/...)` × 多次 → 全部 success
- step 15—21:再次 `write_file("/tmp/poem.txt")` → 又一次覆盖写入
- step 22—33:再次 `read_file` / `list_dir` 验证 → session 结束

**关键 = LLM 看到 write_file 成功 → 习惯性地 read_file/list_dir 验证结果是否正确;然后基于"验证结果"再次写入,再次验证**。这是 LLM 端的"verification loop"语义行为,不是 Agent engine 的逻辑 bug。

### 用户原话(2026-10-16)

> "我担心有一个问题,如果就是需要再一个turn反复往同一个文件写入不同的内容呢,比如追加、往不同的索引位置写入不同的内容等等。我现在甚至觉得之前一直往同一个文件一直写,是LLM本身的问题不是Agent逻辑的问题,本质上可以通过调整提示词或者更换模型解决。"

**核心关切**:
1. **agent engine 加 guard 会引入 false-positive** —— 合法 append / edit-by-position 也会被误杀
2. **本质是 LLM 行为问题** —— 应该 prompt 层引导而不是 engine 层强制
3. **业务方可调提示词/换模型** —— Agent engine 不该越俎代庖

### 范围切换记录(scope pivot)

| Story 状态 | 设计 | 状态 |
|---|---|---|
| **pre-Option-A (2026-10-16 上半日)** | engine-guard:`LoopDetectionGuard` + `ConcurrentHashMap` + `(toolName, primaryResource)` 复合 key + SHA-256 fallback + LINGS-X02 + 16 net new AC + 4 prod 文件 + `agent.loop-detection.mode/window` yml | ❌ **已撤销** |
| **Option A (2026-10-16 用户选定)** | prompt-engineering:`DefaultPromptBuilder` INSTRUCTIONS 段 append 反验证循环条款 + `agent.instructions.anti-verify-loop: boolean` yml 开关 | ✅ **当前 scope** |

**保留文件名 `duplicate-tool-call-detector`** 因为 git 历史已用,改 slug 得不偿失;**实际语义** = "anti-verification-loop prompt clause"。

### 为何 Option A 而非 engine guard

| 维度 | engine guard(原计划) | Option A(选定) |
|---|---|---|
| 误伤风险 | 高 —— 合法 append/edit-by-position 也会被拦截 | 0 —— 由 LLM 自己理解条款 |
| 实现成本 | 4 prod + 16 AC + 1 ErrorCode | 1 prod + 1 yml + 1 test 文件 |
| 行为可逆 | 配 yml 关闭 | 配 yml 关闭 + 业务方随时改 INSTRUCTIONS inline 文本 |
| 通用性 | 写死启发式 extractor(`path > command > url > id`) | 通用条款,业务方可微调措辞 |
| R-13 风险 | ConcurrentHashMap + new ResourceLock 类新增 binary 风险 | 0 新 binary(Lombok @Value + String concat 已锁) |
| 误用风险 | false-positive 让业务方调 yml 改成 `mode: off` 反而失去保护 | LLM 不听条款 = 与没加一样(但**零误伤**) |

**结论**:LLM 行为问题用 prompt 工程解决;engine 加 guard 是治标不治本 + 引入 false-positive 风险。

### 业务价值

- **0 误伤**:合法 append / edit-by-position / 多文件批量写 / 不同 ID db 操作 全部不受影响
- **行为可逆**:yml 一行 `anti-verify-loop: false`(或不写) → 等价于什么都没改
- **业务方可调措辞**:未来 LLM 升级 / 换模型后条款可能需微调,通过 `agent.instructions.file: ./verify-loop-policy.md` 走 mustache 模板替换变量
- **Story #050 vs #051 边界清晰** —— Story #050 = per-`toolUseId` deny-once guard(LLM 重发同 id,已 Deny 过再 ask);Story #051 Option A = 提示 LLM 避免「成功 → 反复 verify → 再写」verification loop,**完全不重叠**

---

## WHO(参与者 + 边界)

| 角色 | 改动范围 | 行为 |
|---|---|---|
| **DefaultPromptBuilder** | modify(INSTRUCTIONS 段 append 反验证循环条款) | 启动期读 `cfg.getInstructions().isAntiVerifyLoop()`,若 true 则在 INSTRUCTIONS 段后追加固定条款 |
| **AgentConfig.Instructions** | modify(+1 字段 `antiVerifyLoop`) | 4-field `@Value` → 5-field `@Value`,`empty()` 工厂扩 1 实参 `false`,`AgentConfig.builder()` 加 `.antiVerifyLoop(boolean)` builder method |
| **demo-product application.yml** | modify(注释示例) | 加 `# agent.instructions.anti-verify-loop: true` 注释说明 |
| **DefaultPromptBuilderAntiVerifyLoopTest** | new(4 L1 unit test) | 验证 INSTRUCTIONS 段在 `antiVerifyLoop=true/false/缺省` 三态下的输出 |
| **53 fixture 文件** | mechanical sync(`.antiVerifyLoop(false)` 末尾追加) | Lombok `@AllArgsConstructor` auto-regen 4 → 5 字段 |

**不改动**:
- `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` —— 完全不动 engine guard 路径
- `LoopDetectionGuard` / `LoopDetectionMode` enum / `AgentLoopDetectionConfig` / `AgentErrorCodes.LINGS_X02` —— 全部撤销(用户已选 Option A,engine guard 不再需要)
- `agent.loop-detection.mode/window` yml key —— 撤销

---

## WHAT(具体改动 3 件套)

### 1. `AgentConfig.Instructions` 4-field → 5-field(`+antiVerifyLoop`)

```java
@Value
public static class Instructions {
    Path file;
    String inline;
    String templateEngine;
    Map<String, String> variables;
    boolean antiVerifyLoop;   // 🆕 v1.5.63 Story #051

    public static Instructions empty() {
        return new Instructions(null, null, "none", Collections.emptyMap(), false);
        //                                                                         ^^^^^ 🆕
    }
}
```

**Lombok `@Value` 影响**:
- `@AllArgsConstructor` 自动生成 5-arg ctor → **53 fixture 文件全部 mechanical sync**(末尾加 `.antiVerifyLoop(false)`)
- `@Builder` 自动生成 `.antiVerifyLoop(boolean)` builder method
- `equals` / `hashCode` / `toString` 自动包含新字段

**业务方 0 行为变化** —— 默认 `false`,INSTRUCTIONS 段装配逻辑完全不变(详见 §3)。

### 2. `DefaultPromptBuilder.build()` INSTRUCTIONS 段 append 条款

```java
// ── [INSTRUCTIONS] ──────────────────────────────────────────
String insText = readInstructions(ins);
if (!isBlank(insText)) {
    insText = renderTemplate(insText, ins.getVariables(), ins.getTemplateEngine());
    if (!isBlank(insText)) {
        if (sys.length() > 0) sys.append("\n\n");
        sys.append(insText);
    }
}
// 🆕 v1.5.63 Story #051 — Anti-verification-loop clause (opt-in)
if (ins.isAntiVerifyLoop() && sys.length() > 0) {
    sys.append("\n\n").append(ANTI_VERIFY_LOOP_CLAUSE);
}
```

**`ANTI_VERIFY_LOOP_CLAUSE` 常量**(~150 中文字符,~6 行 INSTRUCTIONS 文本):
```
## 反验证循环守则(Anti-Verification-Loop)

工具调用返回成功的结果,表示该调用已生效。**不要**对同一目标资源反复触发验证性的只读调用(如 read_file → list_dir → read_file 循环验证同一文件内容)。
当写操作成功后,信任结果并推进任务,而不是反复读取/列举同一路径来"确认"它是否真的写对了。
如需确认结果,可在同一次响应内合并多个工具调用,而不是分多轮重复触发相同目标的相同只读工具。
```

**关键设计**:
- **位置**:append 到 INSTRUCTIONS 段末尾(永远在 `[USER MESSAGE]` 之前),不抢 ROLE / USER MESSAGE 的视野
- **可空保护**:`sys.length() > 0` 防 INSTRUCTIONS 段为空时孤悬(虽然技术上仍是 `[ROLE]` 单独一段)
- **空 INSTRUCTIONS 配置 + antiVerifyLoop=true**:`sys` 只有 ROLE 段,条款仍 append,但保证不破坏 5-segment 顺序
- **可配置文本?**:本期**不**做(避免 §15 ErrorCode-like 二次配置膨胀),未来 RFC 需要再 Story 追加

### 3. demo-product `application.yml` 注释示例

```yaml
agent:
  # ... 既有配置 ...
  instructions:
    inline: ""                              # 业务方写自定义 INSTRUCTIONS(优先级高于 anti-verify-loop 条款)
    # anti-verify-loop: true                # 🆕 v1.5.63 Story #051 — LLM 验证循环守则,默认关闭
    template-engine: none
```

注释明确:enable 是 opt-in,关闭是默认,业务方 inline INSTRUCTIONS 与 anti-verify-loop 条款**叠加**而非互斥。

### yml key 命名 + 解析契约

| yml key | 解析路径 | 类型 | 默认 |
|---|---|---|---|
| `agent.instructions.anti-verify-loop` | `cfg.getInstructions().isAntiVerifyLoop()` | `boolean` | `false` |

**Jackson `@JsonProperty` kebab-case 绑定**(镜像 Story #034 `agent.a2a.remote-agents[*].domain-whitelist` precedent):anti-verify-loop JSON 字段 → `antiVerifyLoop` Java 字段。

---

## 反模式(明确不做)

1. ❌ **`LoopDetectionGuard` / `ConcurrentHashMap` per-turn counter** —— 引入 false-positive 风险,治标不治本
2. ❌ **`(toolName, primaryResource)` 复合 key + 启发式 extractor** —— 5 类 priority hard-code(`path > command > url > id`)不通用,业务方换 tool 时易踩坑
3. ❌ **SHA-256(canonical JSON sort) fingerprint fallback** —— 即使 fallback 仍可能撞 key,LLM 行为问题不该用 cryptographic 手段治
4. ❌ **`mode: deny/warn/off` 三态开关** —— 业务方大概率关掉以避免误伤,等于白做
5. ❌ **`LINGS-X02 LOOP_DETECTED` ErrorCode** —— engine 不拦截,自然不抛错
6. ❌ **`Tool.dedupKey()` SPI 留给业务方自己实现** —— OQ-Future Story #052+;本期 prompt 解决足够
7. ❌ **修改 `LinearTurnEngine` / `DefaultToolExecutor` / `DefaultAgent`** —— engine guard 路径完全不动

---

## AC 黑盒验证(7 项)

| AC # | 描述 | 验证方式 | 期望 |
|---|---|---|---|
| **AC-051-01** | `antiVerifyLoop=false` 默认行为 100% back-compat | L1 unit test:`DefaultPromptBuilderAntiVerifyLoopTest.defaultFlag_instructSegmentUnchanged()` | 构建出的 `Prompt.messages` 内 system text 完全等价于 Story #002 / #024 既有输出(逐字符比对 fixture) |
| **AC-051-02** | `antiVerifyLoop=true` → INSTRUCTIONS 段末尾追加反验证循环条款 | L1 unit test:`.enabledFlag_appendsClauseToInstructSegment()` | system text 含 `ANTI_VERIFY_LOOP_CLAUSE` 常量全部内容 + 位于 INSTRUCTIONS 段末尾(USER MESSAGE 之前) |
| **AC-051-03** | yml `agent.instructions.anti-verify-loop: true` 真 binding 到 `cfg.getInstructions().isAntiVerifyLoop()` | L2 IT:`DefaultPromptBuilderAntiVerifyLoopYamlIT.yamlTrueBinding_appliesClause()` | 启动 demo-product yml + 调 `agentFactory.create(cfg)` + 断言 `cfg.getInstructions().isAntiVerifyLoop() == true` |
| **AC-051-04** | 新 test 文件 4 L1 case 覆盖 on/off/default/边界 | 测试文件存在 + 4 case 全过 | `mvn test -Dtest=DefaultPromptBuilderAntiVerifyLoopTest` 0 fail |
| **AC-051-05** | 53 fixture 文件 mechanical sync 0 业务逻辑改动 | grep `.antiVerifyLoop(false)` 在 53 fixture 文件末尾 + `mvn -pl lingshu-core test` 全过 | Lombok `@AllArgsConstructor` 5-arg ctor auto-regen + fixture 0 改动只参数补齐 |
| **AC-051-06** | R-13 mitigation (d) baseline mirror 0 binary delta | pre/post `mvn -pl lingshu-core dependency:tree -Dverbose` md5sum diff 仅时间戳差异 | 0 新 binary 引入(纯 JDK 8 `StringBuilder.append` + `String.contains` + Lombok `@Value` 已锁) |
| **AC-051-07** | 反向 AC(RAC):Story #050 fixture 0 改动全过 | `mvn test -Dtest=LinearTurnEngineAskDenyGuardTest` + `mvn test -Dtest=DemoProductAgentEventMapperLlmStreamStartedTest` 0 fail | Story #050 ask-mode 端到端链路不受本 Story 影响 |

**总计**:7 AC / 6 net new + 1 RAC,跨 2 个新测试文件(1 L1 unit + 1 L2 IT)+ 53 fixture mechanical sync。

---

## 关键不变项(回归保护)

| 项 | 不变内容 |
|---|---|
| `DefaultPromptBuilder.build(ctx)` 公开方法签名 | **不变**(只 INSTRUCTIONS 段装配逻辑微调) |
| `DefaultPromptBuilder` 5-segment 顺序 | **不变**(ROLE → INSTRUCTIONS → PROJECT MEMORY → CONVERSATION HISTORY → USER MESSAGE) |
| `Prompt.messages` 结构 | **不变**(反验证循环条款作为 system message 文本内容,不是新增 segment) |
| `AgentConfig.@Value` 顶层 27 字段 | **不变**(0 顶层字段新增,`antiVerifyLoop` 嵌在 `Instructions` inner class) |
| `AgentConfig.Instructions` 公开方法(Lombok @Value 自动生成) | **不变**(只 +1 字段,getter 自动 `isAntiVerifyLoop()`) |
| `AgentConfig.Instructions.empty()` | 行为不变(仍返回空 Instructions);实现扩 1 实参 `false` |
| `PermissionPolicy` / `Decision` / `ApprovalRegistry` | **不变**(Story #030 / #041 / #050 链路 0 改动) |
| `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` | **不变**(engine guard 路径完全不动) |
| `ToolExecutor.dispatch()` 5 步流水线 | **不变**(§4.10.1 硬规则 2 守住) |
| 9 Slot SPI 体系 | **不变**(0 Slot 改动,`antiVerifyLoop` 是 prompt 装饰,非 Slot) |
| 53 fixture 文件 | 0 业务逻辑改动只参数补齐(`.antiVerifyLoop(false)` 末尾追加) |
| JDK 8 兼容 | `StringBuilder.append` + `String.contains` + `boolean` primitive + Lombok `@Value` + Spring `@JsonProperty` 已锁 |
| 0 新 Maven 依赖 | pre/post dep-tree md5sum diff = 0 binary delta |
| 0 新 ErrorCode | pre-Option-A `LINGS-X02 LOOP_DETECTED` 已撤销;`PermissionErrorCodes.LINGS_P01` / `LINGS_P02` 复用不变 |

---

## Story budget 守卫(CLAUDE.md §11)

| 项 | 预算 | 实际 |
|---|---|---|
| 核心 prod 文件改动 | ≤ 5 | **2**(`DefaultPromptBuilder` modify + `AgentConfig` modify) |
| 新 ErrorCode | ≤ 3 | **0** |
| 新 Maven 依赖 | 0 | **0** |
| 新 Java 文件 | ≤ 5(若 Story 需要的话) | **1**(`DefaultPromptBuilderAntiVerifyLoopTest.java`) |
| 改动 JDK 8 兼容性 | 守住 | ✅(`StringBuilder.append` + `boolean` + Lombok `@Value` 全 JDK 8 内置) |
| Story #050 / #030 / #041 / #046 / #049 fixture 0 回归 | 守住 | ✅ AC-051-07 RAC |

---

## 后续 Story 衔接(OQ-Future)

- **OQ-#051-1**:若业务方反馈「anti-verify-loop 条款措辞不够强」 → 加 `agent.instructions.anti-verify-loop-file: path/to/clause.md` 走 mustache 模板替换,本期**不**做
- **OQ-#051-2**:若 LLM 升级后条款失效 → 加 `agent.instructions.anti-verify-loop-model-aware: {claude-3-5: "soft", claude-sonnet-4: "strict"}`,本期**不**做
- **OQ-#051-3**:若业务方要 engine 兜底(防止 LLM 不听条款) → 重启 Story #051 Option B(verification-loop-only guard,read-only tools + same path),本期**不**做
- **OQ-#051-4**:`Tool.dedupKey()` SPI 留给 Story #052+,本期**不**做

---

## 修复者

Claude Code(根据用户 2026-10-16 反馈「之前一直往同一个文件一直写,是 LLM 本身的问题不是 Agent 逻辑的问题,本质上可以通过调整提示词或者更换模型解决」+ 33-step SSE trace 实证 + 用户单字符「A」选定 Option A,触发本 Story scope pivot from engine-guard → prompt-revision)