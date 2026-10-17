# Story #051 — Anti-Verification-Loop Prompt Clause — Implementation Tasks

> **Story #**: 051
> **Slug**: `duplicate-tool-call-detector` (filename preserved from pre-Option-A scope; **实际语义 = anti-verification-loop prompt clause**,详见 spec.md §WHY)
> **Spec**: [`spec.md`](./spec.md)
> **Plan**: [`plan.md`](./plan.md)
> **类型**: Prompt-revision Story(用户实测后重新分类,2026-10-16)
> **对应设计文档**: `dsh_agent_design.md` v1.5.62 → v1.5.63
> **对应 SKILL**: `lingshu-spec-driven-dev` v1.0.28

---

## ⚠️ 范围切换记录(scope pivot)

| Story 状态 | 设计 | tasks.md 行数 |
|---|---|---|
| **pre-Option-A (2026-10-16 上半日)** | engine-guard:`LoopDetectionGuard` + `ConcurrentHashMap` + `(toolName, primaryResource)` 复合 key + SHA-256 fallback + LINGS-X02 + 16 net new AC + 4 prod 文件 + `agent.loop-detection.mode/window` yml | **原 ~530 行(本文件 PRE-PIVOT 状态)** ❌ 全部撤销 |
| **Option A (2026-10-16 用户选定)** | prompt-engineering:`DefaultPromptBuilder` INSTRUCTIONS 段 append 反验证循环条款 + `agent.instructions.anti-verify-loop: boolean` yml 开关 | ✅ **当前 scope** |

**撤销清单**(pre-Option-A → 全部不实施):
- ❌ `LoopDetectionGuard.java` ~140 行 — 撤销
- ❌ `AgentEvent.LoopDetected` nested class — 撤销
- ❌ `AgentErrorCodes.LINGS_X02` — 撤销
- ❌ `AgentConfig.LoopDetectionConfig` — 撤销
- ❌ `agent.loop-detection.mode/window` yml key — 撤销
- ❌ `DefaultToolExecutor` step 2.5 hook — 撤销
- ❌ `LinearTurnEngine` runTurn head reset — 撤销
- ❌ 16 net new AC 中的 RAC(AC-051-09—AC-051-16)— 撤销,改 7 AC(AC-051-01—AC-051-07)

---

## 阶段总览

| 阶段 | 任务数 | 触发条件 | 关键产出 |
|---|---|---|---|
| **Phase 0** Pre-flight | 7 | 用户「实施吧」 | R-13 baseline md5sum 锁定 + 821 tests baseline PASS |
| **Phase 1** `AgentConfig.Instructions` 4→5 field | 3 | Phase 0 PASS | `antiVerifyLoop: boolean` 字段加 + Lombok `@AllArgsConstructor` auto-regen + `Instructions.empty()` 5 实参 |
| **Phase 2** `DefaultPromptBuilder` INSTRUCTIONS append | 2 | Phase 1 PASS | `ANTI_VERIFY_LOOP_CLAUSE` 常量 + `if (ins.isAntiVerifyLoop())` 守卫段 |
| **Phase 3** demo-product `application.yml` 注释 | 1 | Phase 2 PASS | `# agent.instructions.anti-verify-loop: true` 注释示例 |
| **Phase 4** New test file `DefaultPromptBuilderAntiVerifyLoopTest` | 5 | Phase 3 PASS | 4 L1 case 覆盖 on/off/default/边界 |
| **Phase 5** 53 fixture mechanical sync | 4 | Phase 4 PASS | Lombok `@AllArgsConstructor` 5-arg ctor auto-regen + 53 fixture 末尾 `.antiVerifyLoop(false)` 追加 |
| **Phase 6** R-13 post-flight | 2 | Phase 5 PASS | `mvn -pl lingshu-core dependency:tree` md5sum diff 仅时间戳差异 = 0 binary delta 第 34 次 PASS |
| **Phase 7** Validation(RAC + comprehensive suite) | 3 | Phase 6 PASS | AC-051-07 RAC 全过 + 821 + 4 = 825 tests PASS / 0 fail |
| **总任务数** | **27 task** | — | — |

---

## Phase 0 — Pre-flight 强制执行(7 子任务)

| T# | 描述 | 命令 / 输出 | 阻塞 |
|---|---|---|---|
| T-00.1 | 锁定本地 commit hash baseline | `git log --oneline -1` → 记录 `dbd0615` 为 pre-#051 baseline | ❌ |
| T-00.2 | 锁定 `lingshu-core` 依赖树 baseline md5sum | `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-core-deps-pre-#051.txt && md5sum /tmp/lingshu-core-deps-pre-#051.txt` | ❌ |
| T-00.3 | 锁定 `lingshu-examples/demo-product` 依赖树 baseline md5sum | `mvn -pl lingshu-examples/demo-product dependency:tree -Dverbose > /tmp/demo-product-deps-pre-#051.txt && md5sum /tmp/demo-product-deps-pre-#051.txt` | ❌ |
| T-00.4 | 锁定 821 tests pre-#051 baseline | `mvn -pl lingshu-core test -q 2>&1 | tail -5` → 期望 `Tests run: 821, Failures: 0, Errors: 0, Skipped: 0` | ❌ |
| T-00.5 | `banned-dependencies` enforcer Rule 0 baseline | `mvn -pl lingshu-core verify -Dbanned-dependencies.check=true` → Rule 0 passed | ❌ |
| T-00.6 | 检查 53 fixture 文件 `.maxConcurrentQueueDepth(32)`(Story #044 后状态) | `grep -rL "maxConcurrentQueueDepth(32)" lingshu-core/src/test/java/` → 期望 0 文件(53 fixture 全部 26 字段对齐) | ❌ |
| T-00.7 | 检查 Story #050 反向 AC baseline 通过 | `mvn test -Dtest=LinearTurnEngineAskDenyGuardTest,DemoProductAgentEventMapperLlmStreamStartedTest` → 期望 0 fail | ❌ |

**Phase 0 全部 PASS → 进 Phase 1**

---

## Phase 1 — `AgentConfig.Instructions` 4→5 field(3 子任务)

> **镜像 Story #044 Phase 1(AgentConfig 顶层扩 2 字段)** precedent — `@Value` + `@AllArgsConstructor` Lombok auto-regen

| T# | 文件 | 描述 | 行数(估) | 依赖 | 阻塞 |
|---|---|---|---|---|---|
| T-051-01 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | `Instructions` inner class 4 字段 → 5 字段,加 `boolean antiVerifyLoop` 字段(`@Value` Lombok 自动生成 `isAntiVerifyLoop()` getter)+ `Instructions.empty()` 工厂方法 4 实参 → 5 实参(末尾 `false`) | +5 | Phase 0 | T-051-02 |
| T-051-02 | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | 验证 Lombok `@Builder` 自动生成 `.antiVerifyLoop(boolean)` builder method(无需手写代码,只编译验证) | 0 | T-051-01 | T-051-03 |
| T-051-03 | (本 Phase 编译验证) | `mvn -pl lingshu-core compile` → 期望 0 编译错误(此时其它 53 fixture 会因 4 → 5 实参 ctor 失败,**预期内**,在 Phase 5 修) | — | T-051-02 | Phase 2 |

**关键代码段**(T-051-01):

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

**注**:Phase 1 后 `mvn -pl lingshu-core test` 会**编译失败** 53 fixture 文件 —— 这是预期内的,Lombok `@AllArgsConstructor` 5-arg ctor auto-regen 后老 fixture 还在用 4-arg ctor,**Phase 5 统一 mechanical sync 解决**。

---

## Phase 2 — `DefaultPromptBuilder` INSTRUCTIONS append(2 子任务)

> **镜像 Story #002 Phase(DefaultPromptBuilder 5 段装配)** precedent — 不动 5-segment 顺序,只追加 INSTRUCTIONS 段末尾内容

| T# | 文件 | 描述 | 行数(估) | 依赖 | 阻塞 |
|---|---|---|---|---|---|
| T-051-04 | `lingshu-core/src/main/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilder.java` | 新增 `private static final String ANTI_VERIFY_LOOP_CLAUSE` 常量(~150 中文字符,~6 行 INSTRUCTIONS 文本),包含 `read_file`/`list_dir`/`write_file` 措辞;Javadoc 说明常量意图(anti-verification-loop 守则) | +10 | Phase 1 | T-051-05 |
| T-051-05 | `lingshu-core/src/main/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilder.java` | 在 `build()` INSTRUCTIONS 段装配段(原 L114-122 `if (!isBlank(insText))` 守卫后)新增 `if (ins.isAntiVerifyLoop() && sys.length() > 0) { sys.append("\n\n").append(ANTI_VERIFY_LOOP_CLAUSE); }` 守卫段 | +5 | T-051-04 | Phase 3 |

**关键代码段**(T-051-05):

```java
// 原 L114-122 INSTRUCTIONS 段装配逻辑不变
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

**ANTI_VERIFY_LOOP_CLAUSE 常量内容**(T-051-04):
```
## 反验证循环守则(Anti-Verification-Loop)

工具调用返回成功的结果,表示该调用已生效。不要对同一目标资源反复触发验证性的只读调用(如 read_file -> list_dir -> read_file 循环验证同一文件内容)。
当写操作成功后,信任结果并推进任务,而不是反复读取/列举同一路径来确认它是否真的写对了。
如需确认结果,可在同一次响应内合并多个工具调用,而不是分多轮重复触发相同目标的相同只读工具。
```

**关键不变项**(守住 §4.10.1 硬规则):
- 5-segment 顺序不变:`[ROLE] -> [INSTRUCTIONS] -> [PROJECT MEMORY] -> [CONVERSATION HISTORY] -> [USER MESSAGE]`
- `Prompt.messages` 结构不变(反验证循环条款作为 system message 文本内容,不是新增 segment)
- `DefaultPromptBuilder.build(ctx)` 公开方法签名不变

---

## Phase 3 — demo-product `application.yml` 注释(1 子任务)

| T# | 文件 | 描述 | 行数(估) | 依赖 | 阻塞 |
|---|---|---|---|---|---|
| T-051-06 | `lingshu-examples/demo-product/src/main/resources/application.yml` | 在 `agent.instructions.inline` 配置段后追加 `# anti-verify-loop: true` 注释行(注释明确 opt-in + 默认关闭 + 与 inline INSTRUCTIONS 叠加而非互斥) | +1 | Phase 2 | Phase 4 |

**关键注释**(T-051-06):
```yaml
agent:
  instructions:
    inline: ""                              # 业务方写自定义 INSTRUCTIONS(优先级高于 anti-verify-loop 条款)
    # anti-verify-loop: true                # 🆕 v1.5.63 Story #051 — LLM 验证循环守则,默认关闭
    template-engine: none
```

**注**:注释示例**不**真的打开开关(默认 `false`),只注释说明,业务方手写 uncomment 即可 opt-in。

---

## Phase 4 — New test file `DefaultPromptBuilderAntiVerifyLoopTest`(5 子任务)

> **镜像 Story #002 Phase(Story #002 落地时 DefaultPromptBuilder 单元测试 precedent)** + **Story #050 `LinearTurnEngineAskDenyGuardTest` AC-050-04/05/06 命名风格**

| T# | 文件 | 描述 | 行数(估) | 依赖 | 阻塞 |
|---|---|---|---|---|---|
| T-051-07 | `lingshu-core/src/test/java/ai/lingshu/core/impl/prompt/DefaultPromptBuilderAntiVerifyLoopTest.java` | New L1 unit test file,类级 Javadoc 引用 Story #051 spec + 4 case 设计意图(AC-051-01/02/03/04) | +30 | Phase 3 | T-051-08 |
| T-051-08 | (同文件) | AC-051-01 case:`defaultFlag_instructSegmentUnchanged()` — `Instructions.empty()` false 默认 → 构建出的 `Prompt.messages` 内 system text 完全等价于 Story #002 既有输出(逐字符比对 fixture,mirror Story #024 `DefaultPromptBuilderBackCompatTest` precedent) | +40 | T-051-07 | T-051-09 |
| T-051-09 | (同文件) | AC-051-02 case:`enabledFlag_appendsClauseToInstructSegment()` — `Instructions.builder().antiVerifyLoop(true).build()` → system text 含 `ANTI_VERIFY_LOOP_CLAUSE` 常量全部内容 + 位于 INSTRUCTIONS 段末尾(USER MESSAGE 之前) | +40 | T-051-08 | T-051-10 |
| T-051-10 | (同文件) | AC-051-03 case:`nullInstructions_handlesNull()` — null Instructions cfg 走 yolo back-compat + 不抛 NPE(`DefaultPromptBuilder` 老路径支持) | +30 | T-051-09 | T-051-11 |
| T-051-11 | (同文件) | AC-051-04 case:`clauseDoesNotBreakFiveSegmentOrder()` — `antiVerifyLoop=true` 5 段顺序仍为 `[ROLE] -> [INSTRUCTIONS(+clause) -> PROJECT MEMORY -> CONVERSATION HISTORY -> USER MESSAGE]` | +30 | T-051-10 | Phase 5 |

**关键不变项**:
- 测试文件**只**用 JDK 8 standard + Mockito + Jackson + Lombok 已锁工具,**不**引新依赖
- 4 case 100% 黑盒:`Prompt.messages` 内容比对,不动 mock `LlmProvider` 内部

---

## Phase 5 — 53 fixture mechanical sync(4 子任务)

> **镜像 Story #044 Phase(53 fixture 文件 `.maxConcurrentQueueDepth(32)` 末尾追加)** precedent — Lombok `@AllArgsConstructor` 24 → 26 参数签名 auto-regen 后,53 fixture 文件 mechanical sync

| T# | 文件 | 描述 | 行数(估) | 依赖 | 阻塞 |
|---|---|---|---|---|---|
| T-051-12 | (53 fixture 文件) | `lingshu-core/src/test/java/` 下 grep 出 53 fixture 文件,**全部** `AgentConfig.builder()...permissionPolicy("...")` 末尾机械追加 `.antiVerifyLoop(false)`(默认 false = 100% back-compat = Story #002 / #024 既有 fixture 0 业务逻辑改动只参数补齐) | +0/文件 = 53 行净增 | Phase 4 | T-051-13 |
| T-051-13 | (本 Phase 编译验证) | `mvn -pl lingshu-core test-compile` → 期望 0 编译错误(Lombok 5-arg ctor + 53 fixture 全部 5 实参对齐) | — | T-051-12 | T-051-14 |
| T-051-14 | (本 Phase 测试验证) | `mvn -pl lingshu-core test -q 2>&1 | tail -5` → 期望 `Tests run: 825, Failures: 0, Errors: 0, Skipped: 0`(821 旧 + 4 新 = 825) | — | T-051-13 | T-051-15 |
| T-051-15 | (YAML integration test 加 AC-051-03 e2e) | `lingshu-examples/demo-product/src/test/java/.../DefaultPromptBuilderYamlAntiVerifyLoopIT.java` — yml `agent.instructions.anti-verify-loop: true` 真 binding 到 `cfg.getInstructions().isAntiVerifyLoop()`(L2 IT,镜像 Story #029 `AgentFactoryYamlPermissionPolicyIT` precedent) | +60 | T-051-14 | Phase 6 |

**关键不变项**(53 fixture 文件 0 业务逻辑改动):
- 仅末尾追加 `.antiVerifyLoop(false)` 1 字段
- 既有 26 字段 + `permissionPolicy(...)` + `flowEngine("...")` + `llm(...)` 配置**不**改
- 默认 `false` = 100% back-compat,Story #002-#050 既有 fixture 行为 0 改动

---

## Phase 6 — R-13 post-flight(2 子任务)

> **镜像 Story #050 Phase 6(R-13 baseline 镜像)** precedent — 第 34 次 PASS 0 binary delta

| T# | 文件 | 描述 | 行数(估) | 依赖 | 阻塞 |
|---|---|---|---|---|---|
| T-051-16 | (本 Phase R-13 baseline 镜像) | `mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-core-deps-post-#051.txt && md5sum /tmp/lingshu-core-deps-post-#051.txt` → 与 Phase 0 T-00.2 pre md5sum diff **仅时间戳差异** = **0 binary delta 第 34 次 PASS** | — | Phase 5 | T-051-17 |
| T-051-17 | (本 Phase R-13 demo-product 镜像) | `mvn -pl lingshu-examples/demo-product dependency:tree -Dverbose > /tmp/demo-product-deps-post-#051.txt && md5sum /tmp/demo-product-deps-post-#051.txt` → 与 Phase 0 T-00.3 pre md5sum diff **仅时间戳差异** = **0 binary delta 第 34 次 PASS** | — | T-051-16 | Phase 7 |

**关键不变项**:
- 纯 JDK 8 `StringBuilder.append` + `String.contains` + `boolean` primitive + Lombok `@Value` 已锁
- 0 新 Maven 依赖 = 0 新 binary 引入
- 0 新 ErrorCode = 域字母表 11 域不变

---

## Phase 7 — Validation: AC-051-07 RAC + comprehensive suite(3 子任务)

| T# | 文件 | 描述 | 行数(估) | 依赖 | 阻塞 |
|---|---|---|---|---|---|
| T-051-18 | (本 Phase RAC 验证) | `mvn test -Dtest=LinearTurnEngineAskDenyGuardTest,DemoProductAgentEventMapperLlmStreamStartedTest` → AC-051-07 RAC 期望 0 fail(Story #050 ask-mode + Bug-1 SSE 修复链路 0 改动全过) | — | Phase 6 | T-051-19 |
| T-051-19 | (本 Phase comprehensive suite) | `mvn -pl lingshu-core test -q 2>&1 | tail -5` → 期望 `Tests run: 825, Failures: 0, Errors: 0, Skipped: 0` + 2 MCP heartbeat flake pre-existing(与本 Story 无关,CLAUDE.md 已记录) | — | T-051-18 | T-051-20 |
| T-051-20 | (本 Phase AC black-box 验收) | 逐条 AC-051-01 ~ AC-051-07 + AC-051-07 RAC 验证完成,记录在 PR body 末尾的 `### AC 黑盒验证` 节 | — | T-051-19 | (Story #051 close) |

---

## 7 AC ↔ Task mapping 表

| AC# | 描述 | 验证 task | 期望 |
|---|---|---|---|
| **AC-051-01** | `antiVerifyLoop=false` 默认行为 100% back-compat | T-051-08 + T-051-19 | `Prompt.messages` system text 完全等价于 Story #002 / #024 既有 fixture 输出 |
| **AC-051-02** | `antiVerifyLoop=true` → INSTRUCTIONS 段末尾追加反验证循环条款 | T-051-09 + T-051-19 | system text 含 `ANTI_VERIFY_LOOP_CLAUSE` 常量全部内容 + 位于 INSTRUCTIONS 段末尾 |
| **AC-051-03** | yml `agent.instructions.anti-verify-loop: true` 真 binding 到 `cfg.getInstructions().isAntiVerifyLoop()` | T-051-15(新增 L2 IT)| 启动 demo-product yml + 断言 `cfg.getInstructions().isAntiVerifyLoop() == true` |
| **AC-051-04** | 新 test 文件 4 L1 case 覆盖 on/off/default/边界 | T-051-08/09/10/11 + T-051-19 | `mvn test -Dtest=DefaultPromptBuilderAntiVerifyLoopTest` 0 fail |
| **AC-051-05** | 53 fixture 文件 mechanical sync 0 业务逻辑改动 | T-051-12 + T-051-14 | grep `.antiVerifyLoop(false)` 在 53 fixture 文件末尾 + `mvn -pl lingshu-core test` 全过 |
| **AC-051-06** | R-13 mitigation (d) baseline mirror 0 binary delta | T-051-16 + T-051-17 | pre/post `mvn -pl lingshu-core dependency:tree -Dverbose` md5sum diff 仅时间戳差异 = 0 new binary |
| **AC-051-07 (RAC)** | Story #050 fixture 0 改动全过 | T-051-18 | `LinearTurnEngineAskDenyGuardTest` + `DemoProductAgentEventMapperLlmStreamStartedTest` 0 fail |

**总计**:7 AC / 6 net new + 1 RAC,跨 2 个新测试文件(1 L1 unit `DefaultPromptBuilderAntiVerifyLoopTest` + 1 L2 IT `DefaultPromptBuilderYamlAntiVerifyLoopIT`)+ 53 fixture mechanical sync。

---

## Story budget 守卫(CLAUDE.md §11)

| 项 | 预算 | 实际 | 守卫 task |
|---|---|---|---|
| 核心 prod 文件改动 | ≤ 5 | **2**(`DefaultPromptBuilder` modify + `AgentConfig` modify) | T-051-01/04/05 |
| 新 ErrorCode | ≤ 3 | **0** | T-051-16/17(无 LINGS-X02) |
| 新 Maven 依赖 | 0 | **0** | T-051-16/17(md5sum 仅时间戳差异) |
| 新 Java 文件 | ≤ 5(若 Story 需要的话) | **2**(1 L1 `DefaultPromptBuilderAntiVerifyLoopTest` + 1 L2 `DefaultPromptBuilderYamlAntiVerifyLoopIT`)| T-051-07/15 |
| 改动 JDK 8 兼容性 | 守住 | ✅(`StringBuilder.append` + `boolean` + Lombok `@Value` 全 JDK 8 内置) | T-051-03/13 |
| Story #050 / #030 / #041 / #046 / #049 fixture 0 回归 | 守住 | ✅ AC-051-07 RAC | T-051-18 |

---

## R-13 mitigation (d) baseline 镜像 第 34 次 PASS checklist

> **镜像 Story #050 Phase 6 R-13 baseline 镜像** precedent — 第 33 次 PASS 第 34 次接续

| 阶段 | 命令 | md5sum diff 期望 |
|---|---|---|
| **T-00.2 pre** | `mvn -pl lingshu-core dependency:tree -Dverbose` | (记录 pre md5sum)|
| **T-00.3 pre** | `mvn -pl lingshu-examples/demo-product dependency:tree -Dverbose` | (记录 pre md5sum)|
| **T-051-16 post** | (同 pre)| 仅时间戳差异 = **0 binary delta** |
| **T-051-17 post** | (同 pre)| 仅时间戳差异 = **0 binary delta** |

**第 34 次 R-13 (d) PASS** = 纯 JDK 8 `StringBuilder.append` + `String.contains` + `boolean` primitive + Lombok `@Value` 已锁,**0 新 binary 引入**(对照 `banned-dependencies` enforcer Rule 0)。

---

## OQ-Future(本期**不**做,留后续 Story #052+)

- **OQ-#051-1**:若业务方反馈「anti-verify-loop 条款措辞不够强」 → 加 `agent.instructions.anti-verify-loop-file: path/to/clause.md` 走 mustache 模板替换
- **OQ-#051-2**:若 LLM 升级后条款失效 → 加 `agent.instructions.anti-verify-loop-model-aware: {claude-3-5: "soft", claude-sonnet-4: "strict"}`
- **OQ-#051-3**:若业务方要 engine 兜底(防止 LLM 不听条款) → 重启 Story #051 Option B(verification-loop-only guard,read-only tools + same path)
- **OQ-#051-4**:`Tool.dedupKey()` SPI 留给 Story #052+

---

## 修复者

Claude Code(根据用户 2026-10-16 反馈「之前一直往同一个文件一直写,是 LLM 本身的问题不是 Agent 逻辑的问题,本质上可以通过调整提示词或者更换模型解决」+ 33-step SSE trace 实证 + 用户单字符「A」选定 Option A,触发本 Story scope pivot from engine-guard → prompt-revision)

---

## Story close 流程

完成 Phase 7 后,Story #051 close 流程:
1. **代码 commit**:`git add` 53 fixture + 2 prod + 2 new test + yml 注释 + `git commit -m "feat(agent): Story #051 anti-verify-loop — DefaultPromptBuilder INSTRUCTIONS 段 append 反验证循环条款 + agent.instructions.anti-verify-loop yml 开关 (第 34 次 R-13 (d) PASS 0 binary delta)"`
2. **PR 创建**:`gh pr create --title "Story #051 anti-verify-loop ..." --body` 末尾必须含 `### AC 黑盒验证` + `### R-13 dependency:tree 自查` 双节
3. **dsh 文档同步**(`v1.5.62 → v1.5.63`):
   - §0 标题版本号同步
   - §13 changelog 新增 v1.5.63 行
   - §4.5.1 [INSTRUCTIONS] 段补 `ANTI_VERIFY_LOOP_CLAUSE` 段说明(Story #002 装配层落地)
   - §15 ErrorCode 表不变(0 新 ErrorCode)
4. **CLAUDE.md / SKILL / SOP / prompts 版本号同步**:`v1.3.59 → v1.3.60` + `v1.0.28 → v1.0.29`
5. **README.md / specs/ROADMAP.md** 同步:
   - README 「更新日期」段加 🆕 v1.5.63 Story #051 顶部 blockquote
   - README 「核心特性」段补 🚫 anti-verify-loop prompt clause bullet
   - README 「Story 路线图」段追加 #051 retrospective
   - specs/ROADMAP.md 段一 ✅ 已完成加 #051 行
   - specs/ROADMAP.md 段二 🟡 待补 #051 划掉
   - specs/ROADMAP.md 段五 🎯 实施节奏 累计计数 52 → **53 个 Story**
6. **constitution §10 R-13 缓解 Story 列表补 `#051` 行 第 34 次 R-13 (d) PASS 0 binary delta** + 累计计数 33 → **34 个 Story**