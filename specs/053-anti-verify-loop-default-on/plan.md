# Story #053 Plan — Anti-Verify-Loop Default-On(default flip)

**Story #**: #053
**Slug**: `anti-verify-loop-default-on`
**Spec**: [`spec.md`](./spec.md)
**修复者**: Claude Code(2026-10-18 用户反馈「system prompt 不够啊」,default flip false → true)

---

## 1. 实施阶段总览(6 Phase)

| Phase | 名称 | 时长估计 | 关键交付 |
|---|---|---|---|
| **Phase 0** | Pre-flight(实施前自查)| ~10 min | git status clean + R-13 dep-tree baseline md5sum |
| **Phase 1** | prod 默认 flip 1 行 | ~5 min | `AgentConfig.Instructions.empty()` 最后实参 `false → true` |
| **Phase 2** | 新 test 文件 2 个 | ~30 min | `DefaultPromptBuilderDefaultOnTest` 4-5 L1 + `DefaultPromptBuilderYamlOptOutIT` 1-2 L2 |
| **Phase 3** | demo-product yml 注释更新 | ~5 min | `application.yml` 加 `# anti-verify-loop: false` opt-out 注释 |
| **Phase 4** | fixture 影响面排查 + 修复 | ~30-60 min | 跑全 suite → grep 字节级 prompt 断言 → 显式 `.antiVerifyLoop(false)` 或 `.contains(CLAUSE)` 兜底 |
| **Phase 5** | AC 黑盒验证 + R-13 baseline 镜像 | ~20 min | 全 6 AC pass + dep-tree 0 binary delta |
| **Phase 6** | 文档同步(dsh / CLAUDE.md / SKILL / constitution §10) | ~30 min | dsh v1.5.64 changelog + CLAUDE.md v1.3.61 + SKILL v1.0.29 + constitution R-13 标记 |

**总时长估计**:~2-3 小时(纯 default flip + fixture 排查占大头)

---

## 2. Phase 0 — Pre-flight(实施前自查)

**目的**:守住 R-13 mitigation (d) baseline mirror 流程,确保 baseline md5sum 在实施前干净,实施后 diff = 0 binary delta。

**步骤**:

```bash
# 1. git status clean
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
git status -sb
# 期望: only ?? .claude/ + ?? .specify/* (本会话 new spec dir 不应污染)
#        working tree clean, 否则 stash / commit 干净

# 2. 当前 main commit hash(实施起点)
git rev-parse HEAD
# 期望: 6a1d61b (Story #052 PR #87 已合)

# 3. R-13 dep-tree baseline md5sum
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-core-deps-pre-053.txt 2>&1
md5sum /tmp/lingshu-core-deps-pre-053.txt
# 记录此 md5sum,Phase 5 比对
```

**R-13 mitigation (d) 路径**:第 35 次(Story #052 第 35 次 PASS 0 binary delta)→ Story #053 第 36 次。

---

## 3. Phase 1 — prod 默认 flip 1 行

**目的**:`AgentConfig.Instructions.empty()` 工厂方法最后实参 `false → true`。

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`

**改动**(1 行):

```java
// Before (Story #051):
public static Instructions empty() {
    return new Instructions(null, null, "none", Collections.emptyMap(), false);
    //                                                                         ^^^^^
}

// After (Story #053):
public static Instructions empty() {
    return new Instructions(null, null, "none", Collections.emptyMap(), true);
    //                                                                         ^^^^^
    // 🆕 v1.5.64 Story #053 — default-on. Opt-out via yml agent.instructions.anti-verify-loop: false
}
```

**关键**:
- 字段顺序 0 改动(Lombok `@Value` + `@AllArgsConstructor` 5-arg ctor 签名不变)
- 公开方法签名 0 改动(只工厂返回值翻转)
- 不动 `DefaultPromptBuilder` 任何代码(Story #051 已落 if 守卫 `ins.isAntiVerifyLoop() && sys.length() > 0` 完全 work,默认值翻转后自动走 append path)

---

## 4. Phase 2 — 新 test 文件 2 个

### 4.1 `DefaultPromptBuilderDefaultOnTest`(L1 unit,~80 行)

**目的**:验证 `Instructions.empty()` 默认 `antiVerifyLoop=true` + `DefaultPromptBuilder` 默认 append clause + 显式 false 跳过 clause。

**位置**:`lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderDefaultOnTest.java`

**case 设计**(5 L1):

| Test | 描述 | 期望 |
|---|---|---|
| `empty_returnsAntiVerifyLoopTrue` | `Instructions.empty().isAntiVerifyLoop() == true` | AC-053-01 |
| `emptyBuild_appendsClauseByDefault` | `DefaultPromptBuilder.build(ctx with empty ins)` → Prompt.system text 含 `ANTI_VERIFY_LOOP_CLAUSE` | AC-053-02 |
| `explicitFalseBuild_skipsClause` | `DefaultPromptBuilder.build(ctx with ins=false)` → Prompt.system text 不含 clause | (反向 AC) |
| `explicitTrueBuild_appendsClause` | `DefaultPromptBuilder.build(ctx with ins=true)` → 同 `emptyBuild` | (回归 AC,与 empty 等价) |
| `emptyBuild_appendsClauseEvenWhenRoleEmpty` | 边界:ROLE 段为空(空 Identity)但 `sys.length() > 0` 因 clause append → clause 仍 append | (边界 AC) |

**fixture 模式**(镜像 Story #051 `DefaultPromptBuilderAntiVerifyLoopTest`):

```java
@Test
public void empty_returnsAntiVerifyLoopTrue() {
    AgentConfig.Instructions ins = AgentConfig.Instructions.empty();
    assertThat(ins.isAntiVerifyLoop()).isTrue();
}

@Test
public void emptyBuild_appendsClauseByDefault() {
    AgentConfig cfg = AgentConfig.builder()
        .instructions(AgentConfig.Instructions.empty())
        .llm(AgentConfig.Llm.defaults())
        .build();
    TurnContext ctx = mockCtx(cfg);
    Prompt prompt = builder.build(ctx);
    assertThat(prompt.messages()).isNotEmpty();
    String systemText = ((Message.System) prompt.messages().get(0)).text();
    assertThat(systemText).contains(DefaultPromptBuilder.ANTI_VERIFY_LOOP_CLAUSE);
}
```

### 4.2 `DefaultPromptBuilderYamlOptOutIT`(L2 IT,~60 行)

**目的**:验证 yml `agent.instructions.anti-verify-loop: false` 真 binding 到 opt-out。

**位置**:`lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderYamlOptOutIT.java`

**case 设计**(2 L2):

| Test | 描述 | 期望 |
|---|---|---|
| `yamlFalseBinding_skipsClause` | 启动 mini Spring context + 加载 yml + `agent.instructions.anti-verify-loop: false` + 调 `agentFactory.create(cfg)` → Prompt 不含 clause | AC-053-03 |
| `yamlDefaultBinding_appendsClause` | 同上但 yml **不写** `anti-verify-loop` key → 走 `empty()` 默认 → 含 clause | (AC-053-03 反向) |

**fixture 模式**(镜像 Story #051 `DefaultPromptBuilderAntiVerifyLoopYamlIT.yamlTrueBinding_appliesClause`):

```java
@SpringJUnitConfig
@Import(AgentConfig.class)
public class DefaultPromptBuilderYamlOptOutIT {
    @Test
    public void yamlFalseBinding_skipsClause(@Autowired AgentFactory factory) throws Exception {
        Path yaml = tmp.resolve("application.yml");
        Files.write(yaml, ("agent:\n  instructions:\n    anti-verify-loop: false\n").getBytes());
        AgentConfig cfg = AgentFactory.loadYamlAndValidate(yaml);
        assertThat(cfg.getInstructions().isAntiVerifyLoop()).isFalse();
        // ... build Prompt + assert system text 不含 CLAUSE
    }
}
```

---

## 5. Phase 3 — demo-product yml 注释更新

**目的**:明确告诉业务方「默认开启,想关必须显式 opt-out」。

**文件**:`lingshu-examples/demo-product/src/main/resources/application.yml`

**改动**(注释):

```yaml
agent:
  # ... 既有配置 ...
  instructions:
    inline: ""                              # 业务方写自定义 INSTRUCTIONS(优先级高于 anti-verify-loop 条款)
    # anti-verify-loop: false               # 🆕 v1.5.64 Story #053 — opt-out 反验证循环条款(默认已开启)
    template-engine: none
```

**关键**:注释明确「默认已开启」+「显式 false opt-out 退路」。

---

## 6. Phase 4 — fixture 影响面排查 + 修复

**目的**:44 fixture 文件中可能有字节级 prompt 文本断言,默认值翻转后 Prompt text 多一段 clause 会 fail。

**步骤**:

```bash
# 1. 列出全部调用 Instructions.empty() 的 fixture
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
grep -rln "Instructions.empty()" lingshu-core/src/test/ lingshu-examples/ lingshu-cli/src/test/ 2>/dev/null
# 预期 ~44 个文件

# 2. 对每个 fixture 查字节级 prompt 文本断言
for f in $(grep -rln "Instructions.empty()" lingshu-core/src/test/ lingshu-examples/ lingshu-cli/src/test/ 2>/dev/null); do
    if grep -q "isEqualTo.*Prompt\|messages().get(0).text().*isEqualTo\|system.*isEqualTo" "$f"; then
        echo "POTENTIAL FAIL: $f"
    fi
done
# 列出潜在 fail fixture(预估 ≤ 10)

# 3. 对每个潜在 fail fixture,二选一修复:
#    (a) 显式 opt-out 维持原预期:
cfg = AgentConfig.builder()
    .instructions(new Instructions(null, null, "none", Collections.emptyMap(), false))
    .build();
#    (b) 在字节级断言前加 .contains(CLAUSE):
assertThat(prompt.messages().get(0).text())
    .contains("原预期字符串")
    .contains(DefaultPromptBuilder.ANTI_VERIFY_LOOP_CLAUSE);
```

**修复策略决策表**:

| 场景 | 修复策略 |
|---|---|
| fixture 测 `Prompt.messages[0]` 内容语义(不是字节级断言) | (b) `.contains(CLAUSE)` 兜底 —— 既保留原断言语义,又确认新行为 |
| fixture 测 `Prompt.messages[0]` 字节级完全等于固定字符串 | (a) 显式 `new Instructions(..., false)` opt-out —— 维持原字节级断言 |
| fixture 测 prompt 段数 / 5-segment 顺序 | (a) 显式 opt-out —— 段数不变(clause 仍属 INSTRUCTIONS 段内,不增加段数) |

**预估修复文件数**:≤ 10 个(若超出则说明 Story #051 落地时已有 prompt 字节级断言,需要重审而不是简单 .contains 兜底 —— 此时停下走 RFC)

---

## 7. Phase 5 — AC 黑盒验证 + R-13 baseline 镜像

**目的**:跑全 6 AC + 全部历史 suite 0 fail + R-13 dep-tree 0 binary delta 第 36 次 PASS。

**步骤**:

```bash
# 1. 跑新 test 文件
mvn test -Dtest=DefaultPromptBuilderDefaultOnTest
# 期望: 5 L1 全过

mvn test -Dtest=DefaultPromptBuilderYamlOptOutIT
# 期望: 2 L2 全过

# 2. 跑全 lingshu-core test suite(AC-053-04 RAC-1)
mvn -pl lingshu-core test
# 期望: 全过 / 0 fail(已用 Phase 4 fixture 修复字节级断言)

# 3. R-13 baseline mirror(AC-053-06)
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-core-deps-post-053.txt 2>&1
md5sum /tmp/lingshu-core-deps-post-053.txt
diff /tmp/lingshu-core-deps-pre-053.txt /tmp/lingshu-core-deps-post-053.txt
# 期望: md5sum diff = 0 binary delta(纯 boolean 默认值翻转,无新依赖引入)

# 4. banned-dependencies enforcer Rule 0 检查
mvn -pl lingshu-core verify -DskipTests=true
# 期望: enforcer 0 报错
```

**AC 通过判定**:
- AC-053-01 ~ AC-053-03 + AC-053-05:`mvn test -Dtest=...` 单文件跑通
- AC-053-04 RAC-1:`mvn -pl lingshu-core test` 全过 + Phase 4 fixture 修复 0 fail
- AC-053-06:md5sum diff = 时间戳 only

---

## 8. Phase 6 — 文档同步

**目的**:dsh v1.5.64 + CLAUDE.md v1.3.61 + SKILL v1.0.29 + constitution §10 R-13 同步。

**改动列表**:

| 文档 | 改动 |
|---|---|
| `dsh_agent_design.md` | §0 标题 v1.5.63 → v1.5.64 + §13 changelog 新增 v1.5.64 行 + §4.5.2 PromptBuilder 段 `antiVerifyLoop` 行补 🆕 v1.5.64 Story #053 marker(default-on) |
| `CLAUDE.md` | 版本号 v1.3.60 → v1.3.61 + 「Last updated」+ 「对应设计文档」v1.5.63 → v1.5.64 + 「对应 SKILL」v1.0.28 → v1.0.29 |
| `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` | 版本号 v1.0.28 → v1.0.29 + anti-verify-loop default-on flag 同步 |
| `constitution.md` §10 R-13 | 缓解 Story 列表补 `#053` 行**第 36 次** PASS 0 binary delta + 累计计数 34 → **35 个 Story** |
| `specs/ROADMAP.md` | 段一 ✅ 已完成加 #053 行(2026-10-18,xxx pass / 0 fail / R-13 0 binary delta 第 36 次 PASS / 0 新 ErrorCode) |
| `README.md` | 「更新日期」段加 🆕 v1.5.64 Story #053 顶部 blockquote + 「核心特性」段补 🆕 反验证循环默认开启 bullet + 「Story 路线图」段追加 #053 retrospective |

**关键不变项**(全部文档):
- dsh §4.5.1 [INSTRUCTIONS] 段装配(Story #002 落地层)0 改动
- dsh §4.5.2 PromptBuilder opt-in flag(Story #051 落地层)措辞修订 `opt-in` → `default-on + opt-out`
- 53 fixture 文件(本 Story 翻转不影响 ctor 签名,但 fixture 字节级断言已修)0 业务逻辑改动

---

## 9. 风险点与回滚方案

| 风险 | 概率 | 影响 | 回滚方案 |
|---|---|---|---|
| 44 fixture 中字节级 prompt 断言 fail 超预期(>10 个) | 低 | 中(Phase 4 时长膨胀)| 停下走 RFC,不在本期硬撑 |
| Lombok `@Value` 5-arg ctor 自动重新生成,导致 fixture 编译失败 | 极低(只翻默认值不动签名)| 高 | git revert 本 Story commit |
| 业务方 yml 已显式 `anti-verify-loop: true` 路径语义变化 | 极低(显式 true 仍 opt-on)| 低 | 无需回滚 |
| 业务方 yml 已显式 `anti-verify-loop: false` 路径语义变化 | 0(显式 false 仍 opt-out,本 Story flip 涉及 `empty()` 默认值)| 无 | 无需回滚 |
| `default-on` 与业务方 custom inline INSTRUCTIONS 互斥 | 低(append 到 inline 之后,不覆盖)| 低 | 无需回滚,append 顺序见 Story #051 §WHAT 2 |
| ANTI_VERIFY_LOOP_CLAUSE 文案在某些 LLM 反而触发"如何避免 verification loop"元思考 | 极低(Story #051 实证 Sonnet 4 主动遵守)| 中 | OQ-#053-1 RFC:加 `anti-verify-loop-file` 自定义文案 |

---

## 10. 决策点表(实施期需确认)

| # | 决策点 | 默认 | 备选 |
|---|---|---|---|
| **D1** | 是否在 `Instructions.empty()` Javadoc 加 🆕 v1.5.64 Story #053 marker | 是(镜像 Story #051 风格)| 否(简洁风格)|
| **D2** | Phase 4 fixture 修复策略(a 显式 opt-out vs b .contains 兜底) | 按 §6 决策表 | 统一 (a) 或 (b) |
| **D3** | demo-product yml 注释是否加完整「默认已开启」段落 | 是(注释块)| 否(单行)|
| **D4** | Phase 6 文档同步是否同步推送 `~/.claude/skills/` 本地拷贝 | 是(Story #050 前例)| 否(只 sync mirror)|
| **D5** | 是否把 Story #051 spec §OQ-Future OQ-#051-3 改写到 Story #053 OQ-Future | 是(闭环)| 否(保留历史)|

---

## 11. 关键不变项镜像(从 spec.md §关键不变项 复制)

- `DefaultPromptBuilder.build(ctx)` 公开方法签名 **不变**
- `DefaultPromptBuilder` 5-segment 顺序 **不变**
- `DefaultPromptBuilder.ANTI_VERIFY_LOOP_CLAUSE` 静态常量 **不变**
- `DefaultPromptBuilder` INSTRUCTIONS 段 append 守卫 **不变**
- `AgentConfig` 顶层 27 字段 **不变**
- `AgentConfig.Instructions` Lombok `@AllArgsConstructor` 5-arg ctor 签名 **不变**
- `LinearTurnEngine` / `DefaultAgent` / `DefaultToolExecutor` **不变**
- `ToolExecutor.dispatch()` 5 步流水线 **不变**(§4.10.1 硬规则 2)
- 9 Slot SPI 体系 **不变**
- JDK 8 兼容 **守住**
- 0 新 Maven 依赖
- 0 新 ErrorCode

---

## 12. 与 Story #051 + #052 边界

| Story | 改动 | 与 #053 关系 |
|---|---|---|
| **#051 duplicate-tool-call-detector** | 引入 `ANTI_VERIFY_LOOP_CLAUSE` + `antiVerifyLoop` 字段 + opt-in 默认 false | #053 是 #051 的 default-flip 收口,#051 落地是 #053 前提 |
| **#052 dispatch-parallel-protocol-safety** | `LinearTurnEngine.dispatchParallel` 边界 sentinel (LINGS-L04) | #053 与 #052 完全无交集(不同 Layer — #052 engine 层,#053 prompt 层)|
| **#050 ask-loop-guard** | `attemptedDeny: Set<String>` 防 LLM 重发同 toolUseId | #053 与 #050 完全无交集(#050 防同 toolUseId 重发,#053 防 verification loop — 完全不同行为模式)|

**#053 唯一前置依赖**:Story #051 已落地(2026-10-17 PR #86,commit ae2214e)→ ✅ 满足。

---

## 13. 修复者签名

Claude Code(根据用户 2026-10-18 会话反馈「但是如果不主动在用户Prompt上加限制还是会重复,说明 system prompt 不够啊」+ Story #051 3-task SSE trace 实证 opt-in 模式下 LLM 默认 verification loop + 用户确认选项「默认 true, opt-out 退路」+ Story #052 PR #87 已合(commit 6a1d61b)+ 本 Story #053 spec/plan/tasks 三件套落地)
