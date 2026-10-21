# Story #053 — Anti-Verify-Loop Default-On(default flip false → true)

**Story #**: #053
**Slug**: `anti-verify-loop-default-on`
**dsh §**: §4.5.1 [INSTRUCTIONS] 段装配(Story #002 落地层)+ §4.5.2 PromptBuilder opt-in flag(Story #051 落地层)
**类型**: Prompt-revision Story(spec flip — default opt-in → default-on + opt-out)
**预算**: 1 prod 文件改动 / 0 ErrorCode / 0 新 Maven 依赖
**挡板**: 用户 2026-10-18 实测发现「opt-in 默认 false = system prompt 不够」 —— LLM 默认行为反复 read 验证同文件 / read → list_dir → read 循环,只有显式 yml `agent.instructions.anti-verify-loop: true` 才能压制 → **prompt 必须默认开**,业务方想关必须显式 opt-out

---

## WHY(根因 + 业务价值 + 范围切换记录)

### 问题(用户 2026-10-18 实测)

Story #051 落地后,`DefaultPromptBuilder.ANTI_VERIFY_LOOP_CLAUSE` 默认 append 条件 = `ins.isAntiVerifyLoop() == true`,**默认 false**。即:

| 配置 | 反验证循环条款是否生效 |
|---|---|
| `application.yml` 不写 / 空 | ❌ 不生效(LLM 默认反复 read 验证同文件) |
| `agent.instructions.anti-verify-loop: true` | ✅ 生效(LLM 收敛到 write-once + verify-once) |
| `agent.instructions.anti-verify-loop: false` | ❌ 不生效(显式 opt-out) |

**用户原话(2026-10-18)**:
> "但是如果不主动在用户Prompt上加限制还是会重复,说明 system prompt 不够啊"

**核心关切**:
1. **system prompt 是默认应当防御的护栏** —— 不能把「避免 LLM 反复 read 验证」当成 opt-in feature;99% 业务方根本不知道有这条 yml,他们的 LLM 仍在 verification loop
2. **Story #051 的 opt-in 是设计缺陷** —— opt-in 默认关闭 = 等于默认让用户踩坑
3. **业务价值不对等** —— 0 false-positive(协议层 guard 会伤合法 append/edit-by-position,但 prompt clause 0 副作用)+ 显著减少 SSE trace 步数 / token cost / 体感延迟 → **应当默认开**
4. **opt-out 退路仍需保留** —— 极少数业务方(例如 LLM evaluation harness 故意制造 verification loop 测 anti-loop 机制 / 调试场景)需要 opt-out

### pre-Option-A(Story #051 原方案)

```java
public static Instructions empty() {
    return new Instructions(null, null, "none", Collections.emptyMap(), false);
    //  ^^^^^ 默认 false = opt-in
}
```

**Story #051 §WHAT 1 选项当时的权衡**(2026-10-16 用户已选 Option A,但当时「opt-in 默认 false」是基于「避免 false-positive 风险」考量 —— 但 prompt clause 本身 0 false-positive 风险,这一考量只对 engine guard 适用,prompt clause 不该继承同一默认)。

### Option A(default-on + opt-out,本 Story 选定)

```java
public static Instructions empty() {
    return new Instructions(null, null, "none", Collections.emptyMap(), true);
    //  ^^^^^ 默认 true = default-on
}
```

yml 显式 opt-out:
```yaml
agent:
  instructions:
    anti-verify-loop: false   # 显式关闭反验证循环条款(默认已开启)
```

### 为何 default-on 而非 engine guard

| 维度 | engine guard(Story #051 pre-Option-A 撤销方案) | default-on prompt clause(本 Story) |
|---|---|---|
| 误伤风险 | 高 —— 合法 append/edit-by-position 也会被拦截 | 0 —— LLM 自己理解条款 |
| 实现成本 | 4 prod + 16 AC + 1 ErrorCode | **1 prod 文件 1 行修改** + 0 新 ErrorCode + 1 yml 注释 |
| 行为可逆 | yml 关闭 | yml `anti-verify-loop: false` opt-out(本 Story 新增) |
| 副作用 | false-positive 风险让业务方调 yml 改成 `mode: off` | 0 副作用 + opt-out 退路 |
| 默认行为 | LLM 默认反复 verify(本 bug) | **LLM 默认收敛**(修复本 bug) |

### 业务价值

- **默认行为修复**:LLM 默认收敛到 write-once + verify-once,SSE trace 步数从 33 步降到 2-3 步(Story #051 实证)
- **0 业务方配置成本**:99% 业务方不需要知道这条 yml,out-of-the-box 享受修复
- **opt-out 退路保留**:极少数 evaluation / debug 场景可显式 `false`
- **不引入 engine 复杂度**:纯 prompt 层调整,不动 `LinearTurnEngine` / `ToolExecutor` / `DefaultAgent` 任何 engine guard 路径
- **Story #051 vs #053 边界清晰** —— Story #051 = 引入 `ANTI_VERIFY_LOOP_CLAUSE` + `antiVerifyLoop` 字段 + opt-in 默认 false / Story #053 = **默认 flip** `false → true` + opt-out yml 注释,**新增 0 行** 纯参数翻转

---

## WHO(参与者 + 边界)

| 角色 | 改动范围 | 行为 |
|---|---|---|
| **`AgentConfig.Instructions.empty()`** | modify(1 行修改) | 默认值 `false` → `true`(`ANTI_VERIFY_LOOP_CLAUSE` 默认 append 到 INSTRUCTIONS 段) |
| **demo-product `application.yml`** | modify(注释示例) | 加 `# anti-verify-loop: false` 注释说明 opt-out 退路(默认开启) |
| **`DefaultPromptBuilder`** | **0 改动**(pure default flip,不动 if 守卫) | `ins.isAntiVerifyLoop() && sys.length() > 0` 守卫**不变**,只读 ins field 决定是否 append |
| **44 fixture 文件** | mechanical sync(0 业务逻辑改动,只 default 值变化) | Lombok `@AllArgsConstructor` 5-arg ctor 签名 0 改动,只 `Instructions.empty()` 工厂方法返回值变化,fixture 无需调 .antiVerifyLoop(...) 仍 OK |
| **1 new test 文件 `DefaultPromptBuilderDefaultOnTest`** | new(4-5 L1 unit test) | 验证 default-on 行为 + 显式 true / 显式 false / 边界 |

**不改动**:
- `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` —— 完全不动 engine guard 路径
- `LoopDetectionGuard` / `LoopDetectionMode` enum / `AgentLoopDetectionConfig` / `AgentErrorCodes.LINGS_X02` —— 全部不涉及(Story #051 已撤销)
- `agent.loop-detection.mode/window` yml key —— 不涉及
- `agent.instructions` 顶层字段 —— 0 字段新增,只改 `empty()` 默认值
- Story #051 的 `ANTI_VERIFY_LOOP_CLAUSE` 静态常量 —— 完全不变
- Story #051 的 `DefaultPromptBuilder.build()` INSTRUCTIONS 段 append 守卫 —— 完全不变

---

## WHAT(具体改动 3 件套)

### 1. `AgentConfig.Instructions.empty()` 默认 flip 1 行

```java
@Value
public static class Instructions {
    Path file;
    String inline;
    String templateEngine;
    Map<String, String> variables;
    boolean antiVerifyLoop;   // 🆕 v1.5.63 Story #051 → default-on in Story #053

    public static Instructions empty() {
        return new Instructions(null, null, "none", Collections.emptyMap(), true);
        //                                                                        ^^^^^
        // 🆕 v1.5.64 Story #053 — default-on. Opt-out via yml agent.instructions.anti-verify-loop: false
        // (was false in Story #051 — opt-in default; flipped per 2026-10-18 user feedback
        //  "system prompt 不够啊" — LLM 默认反复 verify 同文件; opt-in 默认 false 等于默认让用户踩坑)
    }
}
```

**Lombok `@Value` 影响**:
- `@AllArgsConstructor` 5-arg ctor 签名 **不变**(只 `empty()` 工厂方法返回值翻转,字段顺序 0 改动)
- `@Builder` 自动生成 `.antiVerifyLoop(boolean)` builder method 签名 **不变**
- `equals` / `hashCode` / `toString` 自动包含新字段签名 **不变**
- **44 fixture 文件 mechanical sync = 0 改动**:所有 fixture 调 `Instructions.empty()` / `new Instructions(...)` 显式 5-arg ctor 不受影响,因为字段形状 0 改动,只工厂返回值默认值翻转;**fixture 中显式传 `false` 的位置(若有)** = 业务方显式 opt-out 场景,**保留语义 0 改动**

**业务方 0 行为变化(显式传值)** —— 业务方已显式 `Instructions.builder().antiVerifyLoop(true).build()` 的 yml 配置 0 行为变化(已 opt-in 现在仍是 opt-on)。**业务方未配置(走 empty())** —— 行为从「不 append clause」变为「append clause」= 修复 LLM 默认 verification loop。

### 2. demo-product `application.yml` 注释示例(opt-out 退路说明)

```yaml
agent:
  # ... 既有配置 ...
  instructions:
    inline: ""                              # 业务方写自定义 INSTRUCTIONS(优先级高于 anti-verify-loop 条款)
    # anti-verify-loop: false               # 🆕 v1.5.64 Story #053 — opt-out 反验证循环条款(默认已开启)
    template-engine: none
```

注释明确:**默认已开启**,业务方想关必须显式 opt-out。

### 3. `DefaultPromptBuilderDefaultOnTest` 新文件覆盖 default-on 行为(不改动 prod `DefaultPromptBuilder.java`)

**不动 `DefaultPromptBuilder` body**,只在 test 覆盖 Story #051 已落的 if 守卫(默认行为翻转后 = true 走 append path)。具体 case 见 §AC-053-04 / AC-053-05。

### yml key 命名 + 解析契约(不变)

| yml key | 解析路径 | 类型 | 默认 |
|---|---|---|---|
| `agent.instructions.anti-verify-loop` | `cfg.getInstructions().isAntiVerifyLoop()` | `boolean` | **`true`**(Story #053 flip,Story #051 旧 default `false`) |

Jackson `@JsonProperty("anti-verify-loop")` kebab-case 绑定逻辑完全不变(Story #051 已落)。

---

## 反模式(明确不做)

1. ❌ **新增 ErrorCode** —— Story #051 已 0 新 ErrorCode;default flip 不引入新错误路径
2. ❌ **新增 Maven 依赖** —— 纯字段默认值翻转 0 binary 变化
3. ❌ **改 `DefaultPromptBuilder` body** —— if 守卫 `ins.isAntiVerifyLoop() && sys.length() > 0` 完全不变,只读 ins field 决定是否 append,默认值翻转后 = 默认走 append path
4. ❌ **新增 yml 字段** —— 复用 Story #051 已落的 `agent.instructions.anti-verify-loop`,只翻默认值
5. ❌ **改 `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor`** —— engine guard 路径完全不动,Story #051 pre-Option-A 4 文件 engine-guard 计划保持撤销
6. ❌ **新增 `loop-detection.mode` yml key** —— 不涉及 engine guard 路径
7. ❌ **改 `ANTI_VERIFY_LOOP_CLAUSE` 文案** —— 文案是 Story #051 实证有效(3 task SSE trace 收敛),不动

---

## AC 黑盒验证(6 项)

| AC # | 描述 | 验证方式 | 期望 |
|---|---|---|---|
| **AC-053-01** | `AgentConfig.Instructions.empty()` 默认 `antiVerifyLoop=true` | L1 unit test:`DefaultPromptBuilderDefaultOnTest.empty_returnsAntiVerifyLoopTrue()` | `Instructions.empty().isAntiVerifyLoop() == true`(Story #051 是 `false`,本 Story flip) |
| **AC-053-02** | `DefaultPromptBuilder` 在 `empty()` 默认 INSTRUCTIONS 段末尾追加反验证循环条款 | L1 unit test:`DefaultPromptBuilderDefaultOnTest.emptyBuild_appendsClauseByDefault()` | 调 `agentFactory.create(cfg with Instructions.empty())` + 构造 Prompt + system text 含 `ANTI_VERIFY_LOOP_CLAUSE` 常量全部内容 + 位于 INSTRUCTIONS 段末尾 |
| **AC-053-03** | yml `agent.instructions.anti-verify-loop: false` 真 binding 到 opt-out | L2 IT:`DefaultPromptBuilderYamlOptOutIT.yamlFalseBinding_skipsClause()` | 启动 demo-product yml + `agent.instructions.anti-verify-loop: false` + 调 `agentFactory.create(cfg)` + 断言 `cfg.getInstructions().isAntiVerifyLoop() == false` + Prompt system text **不**含 clause |
| **AC-053-04** | Story #002—#050 全部历史 fixture 0 行为回归 | full lingshu-core test suite 0 fail | 跑 `mvn -pl lingshu-core test` + 全部历史 fixture `Instructions.empty()` 工厂返回值翻转后,**fixture 内 `Prompt.messages` 内容含 `ANTI_VERIFY_LOOP_CLAUSE`** — 这是新 fixture 显式断言(若 fixture 用 `PromptEqualsTo(原预期)` 字节级断言会 fail,需人工核对) |
| **AC-053-05** | 新 test 文件 4-5 L1 case 覆盖 default-on / 显式 true / 显式 false / 边界 | 测试文件存在 + case 全过 | `mvn test -Dtest=DefaultPromptBuilderDefaultOnTest` 0 fail |
| **AC-053-06** | R-13 mitigation (d) baseline mirror 0 binary delta | pre/post `mvn -pl lingshu-core dependency:tree` md5sum diff 仅时间戳差异 | 0 新 binary 引入(纯 Java boolean 默认值翻转,无新依赖) |

**总计**:6 AC / 5 net new + 1 RAC(AC-053-04),跨 2 个新测试文件(1 L1 unit + 1 L2 IT)+ 0 fixture 改动(纯 default flip 不改 ctor 签名)。

### RAC-1 fixture 影响面分析

**关键不变量**:**`Instructions.empty()` 工厂返回值变化不传播到 fixture `new Instructions(...)` 显式 5-arg ctor 调用**

| fixture 模式 | 影响 |
|---|---|
| `cfg = AgentConfig.builder().instructions(Instructions.empty()).build()` | **受影响** —— 走 empty 工厂 = 现在 `antiVerifyLoop=true` 默认 append clause,Prompt text 多一段 clause,fixture 中若有 `assertThat(prompt.messages[0].text).isEqualTo(expected)` 字节级断言会 fail |
| `cfg = AgentConfig.builder().instructions(new Instructions(null, null, "none", Collections.emptyMap(), false)).build()` | **不受影响** —— 显式 false 仍 opt-out |
| `cfg = AgentConfig.builder().instructions(new Instructions(null, null, "none", Collections.emptyMap(), true)).build()` | **不受影响** —— 显式 true 仍 opt-on |
| yml `agent.instructions.anti-verify-loop: true` 显式 | **不受影响** —— yml key 已落 Story #051 |
| yml `agent.instructions.anti-verify-loop: false` 显式 | **不受影响** —— yml key 已落 Story #051,本 Story 仍工作(AC-053-03 验证) |

**fixture 排查步骤(Phase 4 实施期)**:
1. `grep -rln "Instructions.empty()" lingshu-core/src/test/ lingshu-examples/ lingshu-cli/src/test/ 2>/dev/null` 列出全部 fixture
2. 对每个 fixture 查 Prompt 文本断言:`grep -A 5 "system" <file> | grep "isEqualTo\|assertThat"` 看是否有字节级 prompt 文本断言
3. 若有字节级断言:加 `.contains(ANTI_VERIFY_LOOP_CLAUSE)` 或修改 fixture 显式 `new Instructions(..., false)` 维持原预期
4. 实际 Story #051 落地后 `Instructions.empty()` 已被 44 fixture 引用,但 Lombok `@Value` 5-arg ctor 签名 0 改动,Story #051 期间 fixture 0 改动 —— **本 Story 翻转默认值风险面 = 44 fixture 中调用 `Instructions.empty()` 且有字节级 prompt 断言的子集**(预估 ≤ 10 个文件需要补 `.contains` 或 `.antiVerifyLoop(false)` 显式 opt-out)

**fixture mitigation 模板**:
```java
// 在 prompt 文本断言前加 contain 兜底,或显式 opt-out:
cfg = AgentConfig.builder()
    .instructions(Instructions.empty().toBuilder().antiVerifyLoop(false).build())
    // 或 .instructions(new Instructions(null, null, "none", Collections.emptyMap(), false))
    .build();
```

---

## 关键不变项(回归保护)

| 项 | 不变内容 |
|---|---|
| `DefaultPromptBuilder.build(ctx)` 公开方法签名 | **不变**(纯 default flip,prod body 0 改动) |
| `DefaultPromptBuilder` 5-segment 顺序 | **不变**(ROLE → INSTRUCTIONS → PROJECT MEMORY → CONVERSATION HISTORY → USER MESSAGE) |
| `DefaultPromptBuilder.ANTI_VERIFY_LOOP_CLAUSE` 静态常量 | **不变**(Story #051 实证有效文案) |
| `DefaultPromptBuilder` INSTRUCTIONS 段 append 守卫 | **不变**(`if (ins.isAntiVerifyLoop() && sys.length() > 0) { sys.append("\n\n").append(ANTI_VERIFY_LOOP_CLAUSE); }`) |
| `AgentConfig` 顶层 27 字段 | **不变**(0 顶层字段新增) |
| `AgentConfig.Instructions` 公开方法(Lombok @Value 自动生成) | **不变**(只 `empty()` 工厂返回值翻转) |
| `AgentConfig.Instructions` Lombok `@AllArgsConstructor` 5-arg ctor 签名 | **不变**(字段顺序 + 类型 0 改动) |
| `PermissionPolicy` / `Decision` / `ApprovalRegistry` | **不变**(Story #030 / #041 / #050 链路 0 改动) |
| `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` | **不变**(engine guard 路径完全不动) |
| `ToolExecutor.dispatch()` 5 步流水线 | **不变**(§4.10.1 硬规则 2) |
| 9 Slot SPI 体系 | **不变**(0 Slot 改动,`antiVerifyLoop` 是 prompt 装饰,非 Slot) |
| JDK 8 兼容 | **守住**(`boolean` primitive + Lombok `@Value` 已锁) |
| 0 新 Maven 依赖 | pre/post dep-tree md5sum diff = 0 binary delta |
| 0 新 ErrorCode | Story #051 已 0 新 ErrorCode;default flip 不引入新错误路径 |

---

## Story budget 守卫(CLAUDE.md §11)

| 项 | 预算 | 实际 |
|---|---|---|
| 核心 prod 文件改动 | ≤ 5 | **1**(`AgentConfig.Instructions.empty()` 1 行翻转) |
| 新 ErrorCode | ≤ 3 | **0** |
| 新 Maven 依赖 | 0 | **0** |
| 新 Java 文件 | ≤ 5(若 Story 需要的话) | **1**(`DefaultPromptBuilderDefaultOnTest.java`)+ **1**(`DefaultPromptBuilderYamlOptOutIT.java`) |
| 改动 JDK 8 兼容性 | 守住 | ✅(`boolean` primitive + Lombok `@Value` 全 JDK 8 内置) |
| Story #002—#050 fixture 0 回归(RAC-1) | 守住 | ✅ AC-053-04 RAC + Phase 4 fixture 排查流程 |

---

## 后续 Story 衔接(OQ-Future)

- **OQ-#053-1**:若业务方反馈「anti-verify-loop 条款措辞在 [某模型 / 某场景] 不够强」 → 加 `agent.instructions.anti-verify-loop-file: path/to/clause.md` 走 mustache 模板替换,本期**不**做
- **OQ-#053-2**:若 LLM 升级后条款失效 → 加 `agent.instructions.anti-verify-loop-model-aware: {claude-3-5: "soft", claude-sonnet-4: "strict"}`,本期**不**做
- **OQ-#053-3**:若 LLM 进化出新形态 behavior loop(如 retry loop / write-read-write-read ...) → 增 `DefaultPromptBuilder.ANTI_RETRY_LOOP_CLAUSE` / `ANTI_WRITE_READ_LOOP_CLAUSE` 多个 clause opt-in flags,本期**不**做
- **OQ-#053-4**:Story #051 §OQ-3 重启(verification-loop-only engine guard 防 LLM 完全不听条款)→ 需要新 RFC 论证真实必要性,本期**不**做

---

## 修复者

Claude Code(根据用户 2026-10-18 会话反馈「但是如果不主动在用户Prompt上加限制还是会重复,说明 system prompt 不够啊」+ Story #051 3-task SSE trace 实证 opt-in 模式下 LLM 默认 verification loop + 用户确认选项「默认 true, opt-out 退路」,触发本 Story default flip false → true + opt-out 退路落地)
