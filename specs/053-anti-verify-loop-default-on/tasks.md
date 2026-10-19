# Story #053 Tasks — Anti-Verify-Loop Default-On(default flip)

**Story #**: #053
**Slug**: `anti-verify-loop-default-on`
**Spec**: [`spec.md`](./spec.md)
**Plan**: [`plan.md`](./plan.md)
**修复者**: Claude Code(2026-10-18)

---

## Task 编号约定

`<Phase>-<Step>` 编号,如 `1-2` = Phase 1 Step 2。

## AC ↔ Task Mapping(总览)

| AC | Task | 验证 |
|---|---|---|
| AC-053-01 | T2-2(`empty_returnsAntiVerifyLoopTrue`)| `mvn test -Dtest=DefaultPromptBuilderDefaultOnTest#empty_returnsAntiVerifyLoopTrue` |
| AC-053-02 | T2-3(`emptyBuild_appendsClauseByDefault`)| 同上 + Prompt.system text `.contains(CLAUSE)` |
| AC-053-03 | T2-7(`yamlFalseBinding_skipsClause`)| `mvn test -Dtest=DefaultPromptBuilderYamlOptOutIT#yamlFalseBinding_skipsClause` |
| AC-053-04 RAC-1 | T4-1 ~ T4-4(fixture 影响面排查)| `mvn -pl lingshu-core test` 全过 |
| AC-053-05 | T2-1 ~ T2-5(5 L1 case 全过)| `mvn test -Dtest=DefaultPromptBuilderDefaultOnTest` 0 fail |
| AC-053-06 | T5-3(R-13 dep-tree md5sum diff)| baseline mirror 0 binary delta |

---

## Phase 0 — Pre-flight

### T0-1. git status clean 检查

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
git status -sb
git rev-parse HEAD
# 期望: HEAD = 6a1d61b (Story #052 PR #87)
#       working tree clean except ?? .claude/ + ?? .specify/*
```

**期望输出**:`main...origin/main` ahead/behind 都 0 + working tree clean (modulo untracked .claude/.specify)。

### T0-2. R-13 dep-tree baseline md5sum

```bash
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-core-deps-pre-053.txt 2>&1
md5sum /tmp/lingshu-core-deps-pre-053.txt
# 记录此 md5sum(Phase 5 T5-3 比对)
```

**期望**:输出 1 行 md5sum + 文件路径,记录到 plan.md §6 Phase 4 fixture 修复策略决策表下方。

---

## Phase 1 — prod 默认 flip 1 行

### T1-1. 改 `AgentConfig.Instructions.empty()` 1 行

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`

**步骤**:
1. Read 文件确认 line 380-385 区域
2. Edit `new Instructions(null, null, "none", Collections.emptyMap(), false)` → `new Instructions(null, null, "none", Collections.emptyMap(), true)`
3. 加注释 `// 🆕 v1.5.64 Story #053 — default-on. Opt-out via yml agent.instructions.anti-verify-loop: false`
4. 改字段注释 `// 🆕 v1.5.63 Story #051 — LLM verification-loop prompt guard` → 加 `// (default flipped to true in v1.5.64 Story #053)`

**验证**:
```bash
grep -A 8 "public static class Instructions {" lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java
# 期望: antiVerifyLoop 字段 + empty() 工厂方法最后实参 = true
```

### T1-2. 编译验证(Lombok `@Value` 不破)

```bash
mvn -pl lingshu-core compile
# 期望: BUILD SUCCESS(只翻默认值,字段顺序 0 改动,Lombok 自动 final 注入 0 编译错)
```

**期望**:BUILD SUCCESS。Lombok `@Value` + `@AllArgsConstructor` 5-arg ctor 签名 0 改动 → 编译 0 错。

---

## Phase 2 — 新 test 文件 2 个

### T2-1. 新文件 `DefaultPromptBuilderDefaultOnTest`(5 L1)

**位置**:`lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderDefaultOnTest.java`

**5 case 列表**:

| # | Method | 验证 | AC |
|---|---|---|---|
| 1 | `empty_returnsAntiVerifyLoopTrue` | `Instructions.empty().isAntiVerifyLoop() == true` | AC-053-01 |
| 2 | `emptyBuild_appendsClauseByDefault` | `DefaultPromptBuilder.build(ctx with empty ins)` → Prompt.system text `.contains(ANTI_VERIFY_LOOP_CLAUSE)` | AC-053-02 |
| 3 | `explicitFalseBuild_skipsClause` | `DefaultPromptBuilder.build(ctx with ins=false)` → Prompt.system text **NOT** contains CLAUSE | (反向 AC) |
| 4 | `explicitTrueBuild_appendsClause` | `DefaultPromptBuilder.build(ctx with ins=true)` → 同 case 2 | (回归 AC) |
| 5 | `emptyBuild_appendsClauseEvenWhenRoleEmpty` | 边界:空 Identity(ROLE 段空)+ sys.length() > 0 因 clause append → clause 仍 append | (边界 AC) |

**fixture 镜像参考**:Story #051 `DefaultPromptBuilderAntiVerifyLoopTest`(参考其 `defaultFlag_instructSegmentUnchanged` / `enabledFlag_appendsClauseToInstructSegment` 风格)

**关键 mock 模式**(镜像 Story #051):
```java
private DefaultPromptBuilder builder = new DefaultPromptBuilder(Collections.emptyList(), null);

private TurnContext mockCtx(AgentConfig cfg) {
    // mock session history + user input
    // (具体模式参考 Story #051 测试文件)
}

@Test
public void empty_returnsAntiVerifyLoopTrue() {
    AgentConfig.Instructions ins = AgentConfig.Instructions.empty();
    assertThat(ins.isAntiVerifyLoop()).isTrue();
}
```

### T2-2. 跑 case 1 验证 AC-053-01

```bash
mvn test -Dtest=DefaultPromptBuilderDefaultOnTest#empty_returnsAntiVerifyLoopTrue
```

**期望**:1 L1 PASS。

### T2-3. 跑 case 2 验证 AC-053-02

```bash
mvn test -Dtest=DefaultPromptBuilderDefaultOnTest#emptyBuild_appendsClauseByDefault
```

**期望**:1 L1 PASS。

### T2-4. 跑 case 3+4 验证反向 AC

```bash
mvn test -Dtest=DefaultPromptBuilderDefaultOnTest#explicitFalseBuild_skipsClause+explicitTrueBuild_appendsClause
```

**期望**:2 L1 PASS。

### T2-5. 跑 case 5 验证边界 AC

```bash
mvn test -Dtest=DefaultPromptBuilderDefaultOnTest#emptyBuild_appendsClauseEvenWhenRoleEmpty
```

**期望**:1 L1 PASS。

### T2-6. 跑全 `DefaultPromptBuilderDefaultOnTest` 验证 AC-053-05

```bash
mvn test -Dtest=DefaultPromptBuilderDefaultOnTest
```

**期望**:5 L1 全过 / 0 fail。

### T2-7. 新文件 `DefaultPromptBuilderYamlOptOutIT`(2 L2)

**位置**:`lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderYamlOptOutIT.java`

**2 case 列表**:

| # | Method | 验证 | AC |
|---|---|---|---|
| 1 | `yamlFalseBinding_skipsClause` | mini Spring context + yml `agent.instructions.anti-verify-loop: false` + `agentFactory.create(cfg)` → Prompt 不含 clause | AC-053-03 |
| 2 | `yamlDefaultBinding_appendsClause` | mini Spring context + yml **不写** `anti-verify-loop` key → 走 `empty()` 默认 → 含 clause | (AC-053-03 反向) |

**fixture 镜像参考**:Story #051 `DefaultPromptBuilderAntiVerifyLoopYamlIT`(参考其 `yamlTrueBinding_appliesClause` 风格)

### T2-8. 跑全 `DefaultPromptBuilderYamlOptOutIT` 验证 AC-053-03

```bash
mvn test -Dtest=DefaultPromptBuilderYamlOptOutIT
```

**期望**:2 L2 全过 / 0 fail。

---

## Phase 3 — demo-product yml 注释更新

### T3-1. demo-product `application.yml` 加 opt-out 注释

**文件**:`lingshu-examples/demo-product/src/main/resources/application.yml`

**步骤**:
1. Read 文件确认 `instructions:` 段
2. 在 `template-engine: none` 上方加 `# anti-verify-loop: false               # 🆕 v1.5.64 Story #053 — opt-out 反验证循环条款(默认已开启)`

**验证**:
```bash
grep -B 1 -A 4 "instructions:" lingshu-examples/demo-product/src/main/resources/application.yml
# 期望: 含 anti-verify-loop 注释行
```

---

## Phase 4 — fixture 影响面排查 + 修复

### T4-1. 列出全部 `Instructions.empty()` fixture

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
grep -rln "Instructions.empty()" lingshu-core/src/test/ lingshu-examples/ lingshu-cli/src/test/ 2>/dev/null
# 期望: ~44 个文件
```

### T4-2. 列出潜在 fail fixture(字节级 prompt 断言)

```bash
for f in $(grep -rln "Instructions.empty()" lingshu-core/src/test/ lingshu-examples/ lingshu-cli/src/test/ 2>/dev/null); do
    if grep -q "isEqualTo.*Prompt\|messages().get(0).text().*isEqualTo\|system.*isEqualTo" "$f"; then
        echo "POTENTIAL FAIL: $f"
    fi
done
# 期望: ≤ 10 个文件列出
```

### T4-3. 跑全 `mvn -pl lingshu-core test` 看实际 fail

```bash
mvn -pl lingshu-core test 2>&1 | tee /tmp/lingshu-core-test-pre-fix.txt | tail -200
# 期望: 列出 fail test 文件名(若 0 fail 则 T4-4 跳过)
```

### T4-4. 修复 fail fixture(按 plan.md §6 决策表)

**对每个 fail fixture 二选一**:
- (a) 显式 opt-out 维持原字节级断言:
  ```java
  cfg = AgentConfig.builder()
      .instructions(new Instructions(null, null, "none", Collections.emptyMap(), false))
      .build();
  ```
- (b) `.contains(CLAUSE)` 兜底:
  ```java
  assertThat(prompt.messages().get(0).text())
      .contains("原预期字符串")
      .contains(DefaultPromptBuilder.ANTI_VERIFY_LOOP_CLAUSE);
  ```

**验证**:
```bash
mvn -pl lingshu-core test 2>&1 | tail -50
# 期望: 全过 / 0 fail
```

### T4-5. 跑全 `lingshu-examples` test suite(AC-053-04 RAC-1 跨模块)

```bash
mvn -pl lingshu-examples -am test 2>&1 | tail -100
# 期望: 全过 / 0 fail(demo-product 等模块 0 回归)
```

---

## Phase 5 — AC 黑盒验证 + R-13 baseline 镜像

### T5-1. AC-053-01 ~ AC-053-03 + AC-053-05(单文件跑通)

```bash
mvn test -Dtest=DefaultPromptBuilderDefaultOnTest
mvn test -Dtest=DefaultPromptBuilderYamlOptOutIT
# 期望: 7 net new case 全过
```

### T5-2. AC-053-04 RAC-1(全 lingshu-core suite)

```bash
mvn -pl lingshu-core test
# 期望: 全过 / 0 fail(已用 T4-4 修复)
```

### T5-3. AC-053-06 R-13 dep-tree baseline mirror

```bash
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-core-deps-post-053.txt 2>&1
md5sum /tmp/lingshu-core-deps-post-053.txt
diff /tmp/lingshu-core-deps-pre-053.txt /tmp/lingshu-core-deps-post-053.txt
# 期望: md5sum diff = 0 binary delta(纯 boolean 默认值翻转,无新依赖引入)
# 第 36 次 R-13 mitigation (d) PASS
```

### T5-4. banned-dependencies enforcer Rule 0 检查

```bash
mvn -pl lingshu-core verify -DskipTests=true 2>&1 | grep -i "enforcer\|banned\|dependency" | head -20
# 期望: enforcer 0 报错(纯 boolean 默认值翻转,0 新依赖引入)
```

### T5-5. 跨模块 full test(全 5 子模块)

```bash
mvn test 2>&1 | tee /tmp/full-test-pre-pr.txt | tail -50
# 期望: 全模块全过 / 0 fail(MCP 2 heartbeat flake pre-existing 不计)
```

**总测试数预期**:`前一轮(Story #052)总数 + 7 net new case` ≈ 800+ tests pass / 0 fail / 2 MCP heartbeat flake pre-existing。

---

## Phase 6 — 文档同步

### T6-1. dsh v1.5.63 → v1.5.64

**文件**:`~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md`(主仓外,与 remote_repos/lingshu 同级)

**步骤**:
1. §0 标题 v1.5.63 → v1.5.64
2. §13 changelog 新增 v1.5.64 行(本 Story 完成)
3. §4.5.2 PromptBuilder 段 `antiVerifyLoop` 行补 `🆕 v1.5.64 Story #053` marker(default-on + opt-out 退路)
4. 同步本地仓内 `remote_repos/lingshu/dsh_agent_design.md`(若仓内也有)
5. 备份旧版到 `MyDSHAgentDesign/.dsh-drift-backup/v1.5.63.pre-053-sync.YYYYMMDD-HHMMSS`

**验证**:
```bash
grep "v1.5.6[34]" ~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md | head -3
# 期望: 含 v1.5.64 行
```

### T6-2. CLAUDE.md v1.3.60 → v1.3.61

**文件**:`~/Documents/AIFullStack/MyDSHAgentDesign/CLAUDE.md` + 仓内 `remote_repos/lingshu/CLAUDE.md`(双份)

**步骤**:
1. 版本号 v1.3.60 → v1.3.61
2. 「Last updated」加 2026-10-18 段
3. 「对应设计文档」v1.5.63 → v1.5.64
4. 「对应 SKILL」v1.0.28 → v1.0.29
5. 末尾 changelog 加 v1.3.61 行(本 Story #053 完成)

**验证**:
```bash
grep "v1.3.6[01]" ~/Documents/AIFullStack/MyDSHAgentDesign/CLAUDE.md | head -3
# 期望: 含 v1.3.61 行
```

### T6-3. SKILL v1.0.28 → v1.0.29

**文件**:`~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` + 本地 mirror `~/Documents/AIFullStack/MyDSHAgentDesign/.claude/skills/lingshu-spec-driven-dev/SKILL.md`(双份)

**步骤**:
1. 版本号 v1.0.28 → v1.0.29
2. R-13 dep-tree 自查链路 + Story 列表补 #053 行
3. Story #051 + Story #053 关系说明段(Story #051 opt-in 引入 → Story #053 default-on 收口)

### T6-4. constitution §10 R-13 列表

**文件**:`MyDSHAgentDesign/constitution.md`(本地)

**步骤**:
1. §10 R-13 缓解 Story 列表补 `#053` 行**第 36 次** PASS 0 binary delta
2. 累计计数 34 → **35 个 Story**

### T6-5. specs/ROADMAP.md

**文件**:`remote_repos/lingshu/specs/ROADMAP.md`

**步骤**:
1. 段一 ✅ 已完成加 #053 行(2026-10-18,xxx pass / 0 fail / R-13 0 binary delta 第 36 次 PASS / 0 新 ErrorCode)
2. 段五 🎯 实施节奏累计 52 → **53 个 Story**

### T6-6. README.md

**文件**:`remote_repos/lingshu/README.md`

**步骤**:
1. 「更新日期」段加 🆕 v1.5.64 Story #053 顶部 blockquote
2. 「核心特性」段补 🆕 反验证循环默认开启 bullet
3. 「Story 路线图」段追加 #053 retrospective(`Instructions.empty()` 默认 flip + opt-out yml 退路 + 7 net new case AC 黑盒验证 / R-13 0 binary delta 第 36 次 PASS / 0 新 ErrorCode / 累计 53 个 Story)

---

## Phase 7 — 提交 + PR

### T7-1. git add + commit

```bash
cd /Users/lineng/Documents/AIFullStack/MyDSHAgentDesign/remote_repos/lingshu
git add specs/053-anti-verify-loop-default-on/
git add lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java
git add lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderDefaultOnTest.java
git add lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderYamlOptOutIT.java
git add lingshu-examples/demo-product/src/main/resources/application.yml
# 修复的 fixture(若 T4-4 触发了)
# git add <fixed-fixture-files>

git status
git diff --cached --stat

git commit -m "$(cat <<'EOF'
feat(agent): Story #053 anti-verify-loop-default-on — Instructions.empty() antiVerifyLoop 默认 false → true + opt-out 退路 + 7 net new case AC 黑盒验证 / R-13 0 binary delta 第 36 次 PASS

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
EOF
)"
```

### T7-2. push + 开 PR

```bash
git push origin feat/053-anti-verify-loop-default-on
gh pr create --title "Story #053 anti-verify-loop-default-on" --body "$(cat <<'EOF'
## Summary

- 🆕 Story #053 spec/plan/tasks 三件套落地
- `AgentConfig.Instructions.empty()` 默认 `antiVerifyLoop` 字段 `false → true`(Story #051 opt-in → Story #053 default-on)
- yml `agent.instructions.anti-verify-loop: false` 显式 opt-out 退路
- 7 net new case(`DefaultPromptBuilderDefaultOnTest` 5 L1 + `DefaultPromptBuilderYamlOptOutIT` 2 L2)
- 全模块全过 / 0 fail / 2 MCP heartbeat flake pre-existing

## Test plan

- [x] AC-053-01 `Instructions.empty()` 默认 `antiVerifyLoop=true`
- [x] AC-053-02 `DefaultPromptBuilder` 默认 append clause
- [x] AC-053-03 yml `anti-verify-loop: false` opt-out binding
- [x] AC-053-04 RAC-1 全 lingshu-core suite 0 回归
- [x] AC-053-05 5 L1 case 全过
- [x] AC-053-06 R-13 dep-tree 0 binary delta 第 36 次 PASS

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

### T7-3. PR body 末尾补 R-13 dependency:tree 自查节

```markdown
### R-13 dependency:tree 自查

pre/post `mvn -pl lingshu-core dependency:tree -Dverbose` md5sum diff:
- pre: <md5sum-from-T0-2>
- post: <md5sum-from-T5-3>
- diff: 仅时间戳差异 = 0 binary delta
- 累计: 第 36 次 R-13 mitigation (d) PASS 0 binary delta
- banned-dependencies enforcer Rule 0: passed
```

---

## Story budget guards 复述(CLAUDE.md §11)

| 项 | 预算 | 实际 |
|---|---|---|
| 核心 prod 文件改动 | ≤ 5 | **1**(`AgentConfig.Instructions.empty()` 1 行翻转)|
| 新 ErrorCode | ≤ 3 | **0** |
| 新 Maven 依赖 | 0 | **0** |
| 新 Java 文件 | ≤ 5 | **2**(`DefaultPromptBuilderDefaultOnTest` + `DefaultPromptBuilderYamlOptOutIT`)|

---

## 修复者签名

Claude Code(根据用户 2026-10-18 会话反馈 + Story #051 实证 + Story #052 PR #87 已合 + 用户确认选项「默认 true, opt-out 退路」,触发本 Story #053 spec/plan/tasks 三件套落地,等用户「实施吧」触发 Phase 1-7 实际代码改动 + AC 黑盒验证 + R-13 baseline 镜像第 36 次 PASS)
