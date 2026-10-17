# Story #051 — Duplicate-Tool-Call Detector — Implementation Plan

> **Story #**: 051
> **Slug**: `duplicate-tool-call-detector`(文件名沿用;**实际 scope = anti-verification-loop prompt clause**,详见 spec.md §WHY 根因)
> **Spec**: [`spec.md`](./spec.md)
> **对应设计文档**: `dsh_agent_design.md` v1.5.62 → v1.5.63(本 Story 实施同步)
> **对应 SKILL**: `lingshu-spec-driven-dev` v1.0.28
> **对应 dsh 章节**: §4.10.1 硬规则 2(ToolExecutor 5 步流水线)/ §4.6 Tool SPI / §4.5.1 INSTRUCTIONS 段装配(Story #002 落地层)/ §6.1 LinearTurnEngine / §15 ErrorCode 编码约定(本 Story **0 新 ErrorCode**)

---

## Summary

**scope pivot from engine-guard → prompt-revision**(用户 2026-10-16 反馈 + 33-step SSE trace 实证,**LLM verification-loop 行为不是 Agent 引擎 bug**;User 在 Option A 与 pre-Option-A 共 4 个选项中选 Option A 单字符「A」)。

**Option A 落地**:`DefaultPromptBuilder.build()` INSTRUCTIONS 段**末尾** append 固定反验证循环条款(~150 中文字符);`AgentConfig.Instructions` inner class 4-field → 5-field `@Value` 加 `antiVerifyLoop: boolean`;yml `agent.instructions.anti-verify-loop: true`(默认 `false` = 不启用)经 Jackson `@JsonProperty` kebab-case 绑定到 `cfg.getInstructions().isAntiVerifyLoop()`;**53 fixture 文件 mechanical sync**(Lombok `@AllArgsConstructor` 4 → 5 参数 ctor 自动 re-regen,fixtures 末尾追加 `.antiVerifyLoop(false)`)。

**业务方 opt-in**:默认关闭,yml `agent.instructions.anti-verify-loop: true` 启用 → 在 INSTRUCTIONS 段末尾 append 固定条款。**引擎不动**(`LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` 0 改动,Story #050 链路完全 unaffected)。

---

## Technical Context

### JDK 8 硬约束(CLAUDE.md §3)

- 用 `StringBuilder.append(String)` 与 `String.contains(CharSequence)`(JDK 8 内置,ANTI_VERIFY_LOOP_CLAUSE 拼接)
- 用 Lombok `@Value` 与 `@AllArgsConstructor` 5 字段 final 自动 re-regen
- 用 Jackson `@JsonProperty` kebab-case ↔ camelCase 字段绑定(镜像 Story #034 `agent.a2a.remoteAgents[*].domain-whitelist` precedent)
- 用 Spring `@ConfigurationProperties` 与 yml 顶层 key 接线
- 用 `@Nullable` 与 `Optional<T>`(Null-safe accessor 镜像 dsh §5.6.1 `getDomainWhitelistOrEmpty()` precedent)
- 不用 `var` / `sealed` / `records` / `List.of(...)` / pattern matching switch / text blocks

### 设计要点

1. **插入位置**:`DefaultPromptBuilder.build()` 的 INSTRUCTIONS 段装配点(L139-147)之后,**append** 固定 `ANTI_VERIFY_LOOP_CLAUSE` 常量(不抢 ROLE/USER MESSAGE 视野)
2. **可空保护**:`sys.length() > 0` 防 INSTRUCTIONS 段为空时孤悬;空 INSTRUCTIONS 配置 + `antiVerifyLoop=true` 时条款仍 append
3. **可配置文本?**:本期**不**做(避免 §15 ErrorCode-like 二次配置膨胀,未来 RFC 需要再 Story 追加 — OQ-#051-1)
4. **5-segment 顺序**:不变(ROLE → INSTRUCTIONS → PROJECT MEMORY → CONVERSATION HISTORY → USER MESSAGE)
5. **Inner class 加字段**:`AgentConfig.Instructions` 4 → 5 字段 + `empty()` 工厂扩 1 实参 `false` + Lombok `@Builder.Default` 默认值
6. **Lombok 自动 re-regen**:`@AllArgsConstructor` 5 参 ctor 自动生成 → 53 fixture 文件 mechanical sync(`.antiVerifyLoop(false)` 末尾追加)
7. **yml key 命名**:`agent.instructions.anti-verify-loop`(kebab-case,yml 惯例)+ Jackson `@JsonProperty` kebab-case ↔ camelCase 字段绑定(`getInstructions().isAntiVerifyLoop()`)
8. **行为可逆**:yml 一行 `anti-verify-loop: false`(或不写)→ 等价于什么都没改(100% back-compat,AC-051-01)
9. **业务方微调**:未来 LLM 升级 / 换模型后条款可能需微调,通过 §4.5.1 INSTRUCTIONS 段 `inline: |` 多行文本 + mustache 模板替换占位变量(Story #002 已落);本 Story **不**做 OQ-#051-1 接口留 future

### 不动存量路径(Story #050 RAC 守护 — AC-051-07)

- Story #050 `attemptedDeny: Set<String>` 与 `dispatchWithPolicy` 守卫 100% 不动(同 toolUseId Deny 后再 ask 短路,deny-once 防 infinite ask-loop)
- `DefaultPromptBuilder` 公开方法签名不变(只 INSTRUCTIONS 段装配逻辑新增 1 段守卫)
- `DefaultPromptBuilder` 5-segment 顺序不变(ROLE → INSTRUCTIONS → PROJECT MEMORY → CONVERSATION HISTORY → USER MESSAGE)
- `Prompt.messages` 结构不变(反验证循环条款作为 system message 文本内容,不是新增 segment)
- `AgentConfig.@Value` 顶层 27 字段不变(0 顶层字段新增,`antiVerifyLoop` 嵌在 `Instructions` inner class)
- `AgentConfig.Instructions` 公开方法与 getter/setter 不变(Lombok `@Value` 自动生成,只 +1 字段 `isAntiVerifyLoop()`)
- `AgentConfig.Instructions.empty()` 行为不变(仍返回空 Instructions)
- `PermissionPolicy` / `Decision` / `ApprovalRegistry` 不变(Story #030 / #041 / #050 链路 0 改动)
- `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` 不变(Story #050 #049 #048 #047 #046 链路 0 改动)
- `ToolExecutor.dispatch()` 5 步流水线不变(§4.10.1 硬规则 2 守住)
- 9 Slot SPI 体系不变(0 Slot 改动,`antiVerifyLoop` 是 prompt 装饰,非 Slot)
- 53 fixture 文件 0 业务逻辑改动只参数补齐(`.antiVerifyLoop(false)` 末尾追加)
- JDK 8 兼容(`StringBuilder.append` 与 `String.contains` 与 `boolean` primitive 与 Lombok `@Value` 与 Spring `@JsonProperty` 已锁)
- 0 新 Maven 依赖 / pre/post dep-tree md5sum diff = 0 binary delta
- 0 新 ErrorCode(pre-Option-A `LINGS-X02 LOOP_DETECTED` 已撤销)

---

## Constitution Check(CLAUDE.md §11 硬约束 9 项)

| 编号 | 硬约束 | 本 Story 是否守住 |
|---|---|---|
| 1 | 不 push SOP 到 lingshu 仓 | 是(spec/plan/tasks 三件套在 specs/051 下) |
| 2 | 不省略 AC 黑盒验证(7 AC 跑过才能合) | 是(spec.md §AC 黑盒验证 7 项 + Phase 7 RAC 验证) |
| 3 | 不跨 Story 改 constitution | 是(0 constitution 字段改动) |
| 4 | Story 边界 ≤ 5 核心文件改动 / ≤ 3 ErrorCode | 是 — 2 prod 与 0 ErrorCode(预算内) |
| 5 | 不绕过 Spring Boot SPI | 是(0 Slot 改动,antiVerifyLoop 是 prompt 装饰) |
| 6 | 不引入额外依赖(R-13 mitigation (d) CI 卡点) | 是(0 新 Maven 依赖,Phase 6 跑 dep-tree pre/post md5sum diff) |
| 7 | ReAct Loop 必须自实现(不得用 Spring AI 自动执行) | 是(0 engine 改动,ReAct Loop 自实现不变) |
| 8 | Spring AI 只用 LLM 协议转换 + Tool Schema 生成 | 是(0 Spring AI 改动) |
| 9 | Provider 必须显式映射(不得靠 Spring 容器扫 Bean 类型) | 是(0 Provider 改动) |

---

## 决策点(实施期面对 5 个分叉)

| 编号 | 决策点 | 选 A(spec 默认) | 选 B(替代) | 当前选 | 决策依据 |
|---|---|---|---|---|---|
| 1 | isAntiVerifyLoop 空配置时的默认值 | false | true 强制启用 | A | opt-in 默认关闭 → yolo back-compat(现有 53 fixture 不需要改行为) |
| 2 | ANTI_VERIFY_LOOP_CLAUSE 文本放在哪 | 硬编码 Java 常量(~150 字符) | 提取到 src/main/resources/ properties 文件 | A | 0 新 binary,纯 Java 常量字符串编译期内联,无 I/O 抖动 |
| 3 | 与 INSTRUCTIONS inline 文本叠加 | append 到 INSTRUCTIONS 末尾(均存在则叠加) | 互斥(antiVerifyLoop=true 时 inline 忽略) | A | 业务方 inline 自定义 INSTRUCTIONS 与系统反验证条款叠加而非互斥 — 反验证条款是对 LLM 的软约束,不应覆盖业务方明示的 INSTRUCTIONS |
| 4 | INSTRUCTIONS 段为空 + flag=true 时 | 仍 append 条款(solo 段) | skip 不 append | A | 启用 flag 即是要给 LLM 看条款,即使业务方没写 inline INSTRUCTIONS 也应 append;`sys.length() > 0` 保护是 §4.5.1 INSTRUCTIONS 段独立装配路径守卫 |
| 5 | yml top-level key camelCase vs kebab-case | kebab `anti-verify-loop` | camelCase `antiVerifyLoop` | A | yml 惯例 kebab-case + Jackson `@JsonProperty` kebab-case ↔ camelCase 绑定(镜像 Story #034 `domain-whitelist` precedent) |

---

## 实施路径(6 Phase)

### Phase 0 — Pre-flight R-13 baseline 镜像(15 min)

**目标**:捕获 Story #050 实施后 baseline `dependency:tree` md5sum,作为本 Story 实施后 0 binary delta 对照基线。

```bash
# 进入仓根
cd /path/to/lingshu

# 1. capture baseline md5sum
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/deptree-pre-051.txt 2>&1
md5sum /tmp/deptree-pre-051.txt
# 期望:仅时间戳差异 / 真实依赖拓扑与 Story #050 实施后完全一致

# 2. capture all 53 fixture files baseline count
find . -name "*.java" -path "*/src/test/*" | xargs grep -l "builder()" | wc -l
# 期望:53(Story #044 #046 实施后 fixture 数量)
```

**预期输出**:`/tmp/deptree-pre-051.txt` md5sum + 53 fixture count。两个值都将作为 Phase 6 R-13 mitigation (d) PASS 判定的对照基线。

### Phase 1 — `AgentConfig.Instructions` 加 `antiVerifyLoop` 字段(15 min)

**目标**:Lombok `@Value` 4 字段 → 5 字段,`empty()` 工厂扩 1 实参。

**单文件改动**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` 内 `Instructions` inner class

```
@Value
public static class Instructions {
    Path file;
    String inline;
    String templateEngine;
    Map<String, String> variables;
    boolean antiVerifyLoop;   // 新增 v1.5.63 Story #051 — opt-in flag

    public static Instructions empty() {
        return new Instructions(null, null, "none", Collections.emptyMap(), false);
        //                                                                         新增 false
    }
}
```

**生效链**:
- Lombok `@AllArgsConstructor` 自动生成 5-arg ctor → 53 fixture 文件 `AgentConfig.builder()...instructions(Instructions.builder()...).build()` 末尾**机械**追加 `.antiVerifyLoop(false)`
- Lombok `@Builder` 自动生成 `.antiVerifyLoop(boolean)` builder method
- Lombok 自动生成 `isAntiVerifyLoop()` getter

**校验**:
- `find . -name "AgentConfig.java" | xargs grep -A 5 "public static class Instructions"` 看 5 字段顺序
- `find . -name "AgentConfig.java" | xargs grep -A 3 "public static Instructions empty"` 看 `false` 末尾实参

### Phase 2 — `DefaultPromptBuilder.build()` INSTRUCTIONS 段 append 条款(20 min)

**目标**:在 INSTRUCTIONS 段装配逻辑后 append 固定 `ANTI_VERIFY_LOOP_CLAUSE` 常量。

**单文件改动**:`lingshu-core/src/main/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilder.java`

```
// 在 build() 方法 L150 之后(L139-147 是 INSTRUCTIONS 段装配完毕的位置)
if (ins.isAntiVerifyLoop() && sys.length() > 0) {
    sys.append("\n\n").append(ANTI_VERIFY_LOOP_CLAUSE);
}
```

**常量定义**(类级常量,top of class):

```
// 新增 v1.5.63 Story #051 — Anti-Verification-Loop clause (opt-in)
private static final String ANTI_VERIFY_LOOP_CLAUSE = "## 反验证循环守则(Anti-Verification-Loop)\n\n"
    + "工具调用返回成功的结果,表示该调用已生效。不要对同一目标资源反复触发验证性的只读调用"
    + "(如 read_file -> list_dir -> read_file 循环验证同一文件内容)。\n"
    + "当写操作成功后,信任结果并推进任务,而不是反复读取/列举同一路径来确认它是否真的写对了。\n"
    + "如需确认结果,可在同一次响应内合并多个工具调用,而不是分多轮重复触发相同目标的相同只读工具。\n";
```

**为什么 append 而非 prepend**:
- 不抢 ROLE / INSTRUCTIONS / PROJECT MEMORY 视野 → 永远在 USER MESSAGE 之前
- 业务方 INSTRUCTIONS inline + 系统反验证条款叠加而非互斥(决策点 3)
- 与 §4.5.1 INSTRUCTIONS 段装配路径对齐(INSTRUCTIONS 内容自然 extension)

**5-segment 顺序守卫**:ROLE → INSTRUCTIONS(clause 可选 append) → PROJECT MEMORY → CONVERSATION HISTORY → USER MESSAGE。

### Phase 3 — demo-product `application.yml` 注释示例(5 min)

**目标**:在 demo-product yml 注释里说明 `anti-verify-loop: true` opt-in 启用开关。

**单文件改动**:`lingshu-examples/demo-product/src/main/resources/application.yml`

```
agent:
  instructions:
    inline: ""                              # 业务方写自定义 INSTRUCTIONS(优先级高于 anti-verify-loop 条款)
    # anti-verify-loop: true                # 新增 v1.5.63 Story #051 — LLM 验证循环守则,默认关闭
    template-engine: none
```

**说明**:
- 注释明确:enable 是 opt-in,关闭是默认
- 业务方 inline INSTRUCTIONS 与 anti-verify-loop 条款**叠加**而非互斥(决策点 3)

**校验**:`grep -A 3 "instructions:" application.yml` 看到注释 `anti-verify-loop: true`。

### Phase 4 — `DefaultPromptBuilderAntiVerifyLoopTest` 新文件(30 min)

**目标**:4 L1 case 覆盖 on / off / default / 边界(Bug-Z 防御性回归 AC-051-01 — 02)。

**新文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderAntiVerifyLoopTest.java`

**4 case 列表**:

| Case | 描述 | AC 锚定 |
|---|---|---|
| `defaultFlag_instructSegmentUnchanged` | `antiVerifyLoop=false`(默认)→ INSTRUCTIONS 段输出 100% 不变 | AC-051-01 |
| `enabledFlag_appendsClauseToInstructSegment` | `antiVerifyLoop=true` → INSTRUCTIONS 末尾含 `ANTI_VERIFY_LOOP_CLAUSE` 全部内容 | AC-051-02 |
| `emptyInstructConfig_antiVerifyLoopTrue_appendsClauseSolo` | INSTRUCTIONS 配置空 + flag=true → 条款作为独立段出现 | AC-051-02 边界 |
| `instructInline_antiVerifyLoopTrue_bothAppear` | INSTRUCTIONS inline + flag=true → 两者均出现(叠加而非互斥) | 决策点 3 |

**测试 assertion 样例**(镜像 Story #002 `DefaultPromptBuilderTest` precedent):

```java
@Test
void enabledFlag_appendsClauseToInstructSegment() {
    AgentConfig.Instructions ins = new AgentConfig.Instructions(
        null, null, "none", Collections.emptyMap(), true /* antiVerifyLoop */);

    Prompt prompt = builder
        .role("test-role")
        .instructions(ins)
        .projectMemory(emptyMemory())
        .conversationHistory(empty())
        .userMessage("write poem.txt")
        .build();

    // AC-051-02: 系统 prompt 含 ANTI_VERIFY_LOOP_CLAUSE 全部内容
    String systemText = prompt.messages().get(0).text();
    assertThat(systemText).contains("反验证循环守则");
    assertThat(systemText).contains("不要对同一目标资源反复触发");
    assertThat(systemText).contains("read_file");
    assertThat(systemText).contains("list_dir");
}
```

### Phase 5 — 53 fixture 文件 mechanical sync(15 min)

**目标**:Lombok `@AllArgsConstructor` 5 参 ctor 自动 re-regen → 所有 `AgentConfig.builder()...instructions(...).build()` 末尾追加 `.antiVerifyLoop(false)`。

**实施 5 步**:

```bash
# 1. 列出全部 builder().instructions() 调用 site
grep -rln "instructions(" --include="*.java" lingshu-core/src/test lingshu-*/*/src/test

# 2. 期望数量:约 5-15 处(绝大部分 fixture 走 AgentConfig.builder() 链式)
#    注意:Lombok @AllArgsConstructor 直接 new 路径也会崩,但绝大部分 fixture 走 builder()

# 3. 手动追加 — 每处 builders 末尾加:
#       .instructions(ins) -> ... .instructions(ins).antiVerifyLoop(false)
#    5 参 path/inline/templateEngine/variables/antiVerifyLoop

# 4. 编译验证
mvn -pl lingshu-core compile test-compile

# 5. 跑历史 fixture 全过(0 regression — AC-051-05 + AC-051-07)
mvn -pl lingshu-core test -Dtest=LinearTurnEngineAskDenyGuardTest
mvn -pl lingshu-core test -Dtest=DefaultPromptBuilderTest
mvn -pl lingshu-core test -Dtest=AgentConfigTest
```

**53 fixture 文件 0 业务逻辑改动**:所有 `.antiVerifyLoop(false)` 末尾追加等价于「LLM 验证循环守则 = 不启用」= AC-051-01 100% back-compat。

**校验**:`find . -name "*.java" -path "*/src/test/*" | xargs grep -l "builder()" | xargs grep -L "antiVerifyLoop(false)" | wc -l` 期望 **0**。

### Phase 6 — R-13 mitigation (d) baseline 镜像 post-flight(10 min)

**目标**:验证 `mvn -pl lingshu-core dependency:tree -Dverbose` pre/post md5sum diff 仅时间戳差异 = 第 34 次 PASS 0 binary delta。

```bash
# 1. capture post md5sum
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/deptree-post-051.txt 2>&1
md5sum /tmp/deptree-post-051.txt

# 2. diff(pre/post 仅时间戳差异)
diff /tmp/deptree-pre-051.txt /tmp/deptree-post-051.txt
# 期望:仅 [INFO] BUILD 之类的时间戳/Eclipse链接/Surefire 时间戳

# 3. 0 binary delta 判定
# 期望:md5sum 相同或仅时间戳差异(无新引入的 dependency artifact)
```

**第 N+1 = 第 34 次 R-13 mitigation (d) baseline mirror PASS**:累计 33 → **34 个 Story**(Story #046 → #047+#048 → #049 → #050 已 33 次 PASS,本 Story 第 34 次)。

### Phase 7 — AC-051-07 RAC + AC 黑盒全面验证(15 min)

**目标**:`mvn -pl lingshu-core test` 全过 + Story #050 链路 0 回归 + AC-051-01—06 全部 7 项 PASS。

```bash
# 1. 全面 AC 黑盒验证
mvn -pl lingshu-core test -Dtest=DefaultPromptBuilderAntiVerifyLoopTest
# 期望:4 new case 0 fail

# 2. 反向 AC — Story #050 链路 0 改动全过
mvn -pl lingshu-core test -Dtest=LinearTurnEngineAskDenyGuardTest
mvn -pl lingshu-core test -Dtest=AnthropicStreamParserLlmStreamStartedTest
mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductAgentEventMapperLlmStreamStartedTest
# 期望:Story #050 全部 fixture 0 fail(AC-051-07)

# 3. 全面回归 — lingshu-core 全 test suite
mvn -pl lingshu-core test
# 期望:全 780+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing(CLAUDE.md 已记)
```

**L2 IT(AC-051-03)**:`DefaultPromptBuilderAntiVerifyLoopYamlIT` 验证 yml `agent.instructions.anti-verify-loop: true` 真 binding 到 `cfg.getInstructions().isAntiVerifyLoop()`(镜像 Story #044 / Story #046 IT precedent)。

---

## 关键不变项 — Phase-1 ↔ Phase-7 守护(回归保护)

| 项 | 不变内容 | Phase 守卫 |
|---|---|---|
| `DefaultPromptBuilder.build(ctx)` 公开方法签名 | 不变 | Phase 7 — `DefaultPromptBuilderTest` 0 改动全过 |
| `DefaultPromptBuilder` 5-segment 顺序 | 不变 | Phase 4 — `enabledFlag_appendsClauseToInstructSegment` 测试检查 USER MESSAGE 在 clause 后 |
| `Prompt.messages` 结构 | 不变 | Phase 4 — 反验证条款作为系统段文本内容 |
| `AgentConfig.@Value` 顶层 27 字段 | 不变 | Phase 1 — `AgentConfigTest` 0 改动全过(链 + 末尾 `.maxConcurrentTurns(16).maxConcurrentQueueDepth(32).antiVerifyLoop(false)`) |
| `AgentConfig.Instructions` 公开方法 | 不变 | Phase 1 — 只 +1 字段 `isAntiVerifyLoop()` |
| `AgentConfig.Instructions.empty()` 行为 | 不变 | Phase 1 — `Instructions.empty().isAntiVerifyLoop() == false` |
| `PermissionPolicy` / `Decision` / `ApprovalRegistry` | 不变 | Phase 7 — Story #030/041/050 链路 0 改动 |
| `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` | 不变 | Phase 7 — 13 个 engine 集成 fixture 0 改动 |
| `ToolExecutor.dispatch()` 5 步流水线 | 不变 | §4.10.1 硬规则 2 守住 |
| 9 Slot SPI 体系 | 不变 | 0 Slot 改动 |
| 53 fixture 文件 | 0 业务逻辑改动只参数补齐 | Phase 5 — 全部 `.antiVerifyLoop(false)` 末尾追加 |
| JDK 8 兼容 | 全 JDK 8 内置 + Lombok @Value | Phase 1 与 Phase 7 — Java 1.8 compile 守住 |
| 0 新 Maven 依赖 | pre/post dep-tree md5sum diff = 0 binary delta | Phase 6 — 第 34 次 R-13 PASS |
| 0 新 ErrorCode | pre-Option-A `LINGS-X02` 已撤销 | Phase 6 — `grep -L "LINGS-X02"` 期望 0 |

---

## Story budget 守卫(CLAUDE.md §11)

| 项 | 预算 | 计划 | 守卫 Phase |
|---|---|---|---|
| 核心 prod 文件改动 | ≤ 5 | 2(AgentConfig modify + DefaultPromptBuilder modify) | Phase 1 + Phase 2 |
| 新 ErrorCode | ≤ 3 | 0 | Phase 6(无 LINGS-X02 引入) |
| 新 Maven 依赖 | 0 | 0 | Phase 6(dep-tree diff) |
| 新 Java 文件 | ≤ 5 | 1(DefaultPromptBuilderAntiVerifyLoopTest.java) | Phase 4 |
| 改动 JDK 8 兼容性 | 守住 | JDK 8 已锁 | Phase 1 + Phase 7 |
| Story #050 fixture 0 回归 | 守住 | AC-051-07 RAC | Phase 7 |

---

## 累计计数(本 Story 实施后)

- **Story 合入计数**:Story #050 = 第 52 个,Story #051 = 第 53 个(51 → 52(Story #050) → 53(Story #051))
- **R-13 mitigation (d) PASS 计数**:第 33 次(Story #050)→ 第 34 次(本 Story)
- **dsh 版本**:v1.5.62(Story #050 后)→ v1.5.63(本 Story 实施同步)
- **CLAUDE.md 版本**:v1.3.59 → v1.3.60

---

## 后续 OQ-Future(本期不做)

- **OQ-#051-1**:若业务方反馈 anti-verify-loop 条款措辞不够强 → 加 `agent.instructions.anti-verify-loop-file: path/to/clause.md` 走 mustache 模板替换,本期**不**做
- **OQ-#051-2**:若 LLM 升级后条款失效 → 加 `agent.instructions.anti-verify-loop-model-aware` map,本期**不**做
- **OQ-#051-3**:若业务方要 engine 兜底(防止 LLM 不听条款)→ 重启 Story #051 Option B(verification-loop-only guard,read-only tools + same path),本期**不**做
- **OQ-#051-4**:`Tool.dedupKey()` SPI 留给 Story #052+,本期**不**做
- **OQ-#051-5**:条款文本可配置化(`agent.instructions.anti-verify-loop-template` yml key),本期**不**做 — 常量硬编码已能覆盖 99% 场景

---

## 修复者

Claude Code(根据用户 2026-10-16 反馈「之前一直往同一个文件一直写,是 LLM 本身的问题不是 Agent 逻辑的问题,本质上可以通过调整提示词或者更换模型解决」+ 33-step SSE trace 实证 + 用户单字符「A」选定 Option A,触发本 Story scope pivot from engine-guard → prompt-revision)
