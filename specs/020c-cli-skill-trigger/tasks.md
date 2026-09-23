# Story #020c `cli-skill-trigger` — Tasks

> **Status**: Draft 2026-09-23
> **Implements**: `specs/020c-cli-skill-trigger/plan.md`
> **Test budget**: ≥ 22 cases / 2 files(L1 14 + L2 8)

---

## T-01 — `Agent.continueWithUserMessageBlocking` 接口扩展 + `DefaultAgent` 实现

**文件**:
- `lingshu-core/src/main/java/ai/lingshu/core/runtime/Agent.java`(修改,+8 行)
- `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java`(修改,+30 行)

**实现**(对齐 plan.md §2.2):

`Agent.java` 接口加 1 方法(其他 5 方法不变):
```java
/**
 * 🆕 Story #020c — Sync wrapper for {@link #continueWithUserMessage}.
 * ...
 */
RunResult continueWithUserMessageBlocking(String content);
```

`DefaultAgent.java` 实现(镜像 `runBlocking`):
```java
@Override
public RunResult continueWithUserMessageBlocking(String content) {
    AtomicReference<RunResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    continueWithUserMessage(content).subscribe(new Subscriber<AgentEvent>() {
        @Override public void onSubscribe(Subscription sub) { sub.request(Long.MAX_VALUE); }
        @Override public void onNext(AgentEvent event) {
            // terminal TurnCompleted 由 LinearTurnEngine 内部 finalize 写入 result
        }
        @Override public void onError(Throwable t) {
            latch.countDown();
        }
        @Override public void onComplete() {
            latch.countDown();
        }
    });
    try {
        latch.await(config().getTurnTimeoutSeconds(), TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
    RunResult r = result.get();
    if (r == null) {
        throw new LingsCoreException("LINGS-X99", "turn timeout / no terminal event");
    }
    return r;
}
```

**imports**(`DefaultAgent.java` 新增):
- `java.util.concurrent.CountDownLatch`
- `java.util.concurrent.TimeUnit`
- `java.util.concurrent.atomic.AtomicReference`
- `org.reactivestreams.Subscriber`
- `org.reactivestreams.Subscription`
- `ai.lingshu.core.event.AgentEvent`
- `ai.lingshu.core.exception.LingsCoreException`

**Javadoc**(类级方法,Javadoc):
- "Sync wrapper for `continueWithUserMessage`" — 与 `runBlocking` 对齐
- 5 步说明(镜像 `runBlocking` 1-5 段)
- 引用 dsh §6.4 L4266-4267

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `DefaultAgentContinueWithUserMessageBlockingTest`(2 case)全过 — AC-020c-镜像(后续 T-03 覆盖)
- 现有 Story #001 / #018 测试不回归

---

## T-02 — `SkillCommandDispatcher` 主类 + 14 L1 单测

**文件**:
- `lingshu-cli/src/main/java/ai/lingshu/cli/SkillCommandDispatcher.java`(新,~140 行)
- `lingshu-cli/src/test/java/ai/lingshu/cli/SkillCommandDispatcherTest.java`(新 L1 部分,~150 行 / 14 case)

**实现**(对齐 plan.md §2.1):
- `@Component public class SkillCommandDispatcher`
- 字段:`ToolRegistry toolRegistry` + `ToolExecutor toolExecutor` + `ObjectMapper objectMapper`
- 三段职责 11 方法:
  - 识别:`parse(String) → ParsedCommand` + `isSkillCommand(String) → boolean`
  - 执行:`handleUserInput(String, Agent) → RunResult`
  - 展示:`printSkillList(PrintStream)` + `listSkillNames() → List<String>` + `listSkills() → List<SkillInfo>`
- 嵌套类:
  - `public static final class ParsedCommand { final String name; final String args; }`
  - `public static final class SkillInfo { final String name; final String description; }`
- 私有 helper:`indexOfWhitespace(String) → int` + `sortedSkillNames() → List<String>` + `sortedNames(Set<String>) → List<String>` + `truncate(String, int) → String`

**imports**:
- `ai.lingshu.core.message.ToolCall`, `ToolResult`
- `ai.lingshu.core.runtime.Agent`, `RunResult`
- `ai.lingshu.core.slot.Skill`, `ToolExecutionContext`, `ToolExecutor`, `ToolRegistry`
- `com.fasterxml.jackson.databind.ObjectMapper`, `ObjectNode`
- `org.springframework.beans.factory.annotation.Autowired`
- `org.springframework.stereotype.Component`
- `java.io.PrintStream`
- `java.util.ArrayList`, `Collections`, `List`, `Set`, `TreeSet`

**Javadoc**(类级,4 段落):
1. Story #020c 锚定(CLI /xxx 拦截核心,dsh §6.4 L4039-4042 + L4266-4267)
2. 三段职责(识别 / 执行 / 展示)
3. **Why ToolExecutor.dispatch not Skill.execute**(硬规则 2,§4.10.1 5 步流水线不能绕过)
4. **Why ObjectMapper injected not static**(测试可注入 stub)

**测试类 `SkillCommandDispatcherTest`**(L1 14 case,无 Spring ctx,mock ToolRegistry):
- `isBeanRegistered_`(mini ctx 起 → Bean 已就绪)— AC-020c-1
- `parse_*`(5 case:slash+args / onlyCommand / emptyAfterTrim / noSlash / nullSafe)— AC-020c-2
- `parse_onlySlash` — EC-020c-2
- `listSkillNames_sortsAndDedups` + `listSkillNames_empty` — AC-020c-4
- `listSkills_returnsNameAndDescription` + `listSkills_empty` — AC-020c-5
- `printSkillList_emptyRegistry` + `printSkillList_populatedRegistry` + `printSkillList_sortsByName` — AC-020c-6
- `printSkillList_truncatesLongDescription` — EC-020c-1
- `handleUserInput_invalidPrefixThrowsZ01` + `handleUserInput_emptyThrowsZ01` — AC-020c-9

**测试模式**(对齐 Story #020a `SkillToolTest` / Story #017 `*HandlerTest`):
- mock `ToolRegistry`(Mockito:`findSkill(name) → Skill`, `skillNames() → Set<String>`)
- mock `ToolExecutor`(`dispatch(call, ctx) → ToolResult`)
- mock `Agent`(`continueWithUserMessageBlocking(content) → RunResult`)
- 直接 `new SkillCommandDispatcher(registry, executor, new ObjectMapper())` 调方法
- assertThrows / assertEquals / assertTrue / assertFalse
- 用 ByteArrayOutputStream 捕获 `printSkillList` 输出

**DoD**:
- `mvn -pl lingshu-cli -am compile` 通过
- `SkillCommandDispatcherTest`(L1 14 case)全过
- 现有 Story #017 既有测试不回归

---

## T-03 — `SkillCommandDispatcher` L2 子组(8 case,mock ToolExecutor / Agent)

**文件**:`lingshu-cli/src/test/java/ai/lingshu/cli/SkillCommandDispatcherTest.java`(追加 L2 部分,~150 行 / 8 case)

**实现**(对齐 plan.md §3.2):
- L2 子组 8 case(继续 `SkillCommandDispatcherTest` 类):
  - `isSkillCommand_whenRegistered_returnsTrue` + `isSkillCommand_whenNotRegistered_returnsFalse` + `isSkillCommand_noSlash_returnsFalse` + `isSkillCommand_nullSafe` + `isSkillCommand_argsIgnored` — AC-020c-3
  - `handleUserInput_happyPath_executesAndContinues`(mock executor 返 success + mock agent 返固定 RunResult,验证调用链)— AC-020c-7
  - `handleUserInput_unknownThrowsLingsS05` — AC-020c-8
  - `handleUserInput_skillReturnsError_throwsLingsT02`(mock executor 返 `ToolResult.isError()=true`)— EC-020c-3

**测试模式**:
- `Mockito.mock(ToolRegistry.class)` 真实 Mock(避免 spying)
- `Mockito.when(toolRegistry.findSkill(name)).thenReturn(skill)` 显式 stub
- `Mockito.verify(toolExecutor).dispatch(call, ctx)` 验证调用链
- `Mockito.verify(agent).continueWithUserMessageBlocking(content)` 验证 wrap as User message 路径
- `assertThrows(LingsCliException.class, () -> dispatcher.handleUserInput(...))`

**DoD**:
- `mvn -pl lingshu-cli test -Dtest=SkillCommandDispatcherTest` 22 case 全过(L1 14 + L2 8)
- Story #020a / #020b L1 测试不回归

---

## T-04 — `Args.isPrintSkills` + `ArgsParser` 解析 `--list-skills`

**文件**:
- `lingshu-cli/src/main/java/ai/lingshu/cli/Args.java`(修改,+1 字段)
- `lingshu-cli/src/main/java/ai/lingshu/cli/ArgsParser.java`(修改,+2 行)
- `lingshu-cli/src/test/java/ai/lingshu/cli/ArgsParserTest.java`(追加 1 case,可选)

**实现**(对齐 plan.md §2.4):

`Args.java` 加 1 字段(@Value 自动生成 ctor + getter):
```java
/** 🆕 Story #020c — `lingshu {run|resume} --list-skills` 仅打印 skills 不调 Agent */
boolean printSkills;
```

`ArgsParser.parse()` 末尾加 2 行:
```java
boolean printEffective = flags.containsKey("--print-effective");
boolean printSchema = flags.containsKey("--print-schema");
boolean printSkills = flags.containsKey("--list-skills");   // 🆕 Story #020c

return new Args(sub, configPath, prompt, sessionId, port, printEffective, printSchema, printSkills);
```

**约束**:
- `@Value` Lombok 自动增 ctor 位置(`printSkills` 必须最后)— 其他 7 字段顺序不变
- `--list-skills` 是 boolean flag(对齐 `--print-effective` / `--print-schema` 实现)
- 不改 `validate()` — `--list-skills` 不要求 `--prompt`(单独跑命令,如 `lingshu run --list-skills`)

**DoD**:
- `mvn -pl lingshu-cli -am compile` 通过
- 现有 `ArgsParserTest`(已有 case)不回归
- 加 1 case:`argsParser_parsesListSkillsFlag`(可选,L1 单元测试)

---

## T-05 — `CliRunner` 修改(doRun / doResume / doDoctor 加 `/xxx` 拦截 + `--list-skills`)+ 集成测试

**文件**:
- `lingshu-cli/src/main/java/ai/lingshu/cli/CliRunner.java`(修改,+30 行)
- `lingshu-cli/src/test/java/ai/lingshu/cli/CliRunnerSkillTriggerTest.java`(新,~150 行 / 4 case)

**`CliRunner` 改动**(对齐 plan.md §2.3):
1. 加 `private final SkillCommandDispatcher skillDispatcher;` 字段
2. ctor 注入(`@Autowired`)
3. test-only ctor `(factory, skillDispatcher, out, err)` 加 1 参数
4. `doRun(Args)` 加 `/xxx` 拦截 + `--list-skills` banner:
   - `if (args.isPrintSkills()) { skillDispatcher.printSkillList(out); return; }`(在 `loadYamlOrThrow` 之后,`factory.create(cfg)` 之前)
   - `if (skillDispatcher.isSkillCommand(args.getPrompt())) { ... return; }`(在 `factory.create(cfg)` 之后,`runBlocking` 之前)
5. `doResume(Args)` 同样加 `if (args.isPrintSkills())` + `if (skillDispatcher.isSkillCommand(...))`
6. `doDoctor(Args)` 末尾追加 `skillDispatcher.printSkillList(out)`

**`CliRunnerSkillTriggerTest`**(集成层 4 case,real `AgentFactory` + stub `FlowEngine`):
- `doRun_withSlashPrompt_dispatchesSkill` — yml 配 `CommitSkill` + `--prompt "/commit fix bug"` → CliRunner 走 skillDispatcher 分支,**不**调 `agent.runBlocking`;stdout 含 SkillTool.execute 返的 content
- `doRun_withRegularPrompt_goesThroughLlm` — `--prompt "hello"` 走原 LLM path(stub FlowEngine 返 RunResult)
- `doRun_withListSkills_dumpsSkillListAndReturns` — `--list-skills` + 不带 `--prompt` → stdout 含 skill banner,**不** create Agent
- `doDoctor_appendsSkillListAtEnd` — `lingshu doctor` stdout 末尾含 "[LINGS-Z99] Available commands (...)"

**测试模式**(对齐 `RunHandlerTest` / `DoctorHandlerTest` 模板):
- `AnnotationConfigApplicationContext` 镜像 Story #019 / #020a 的 mini ctx 模式
- 在测试 ctx 里 stub `FlowEngineProvider`(避免真模型调用)+ real `DefaultToolRegistry` + real `SkillCommandDispatcher`
- 调 `cliRunner.doRun(args)` 直接(不通过 `run(ApplicationArguments)`)
- 捕获 stdout 用 `ByteArrayOutputStream`,断言 `result.contains("Available commands")`

**DoD**:
- `mvn -pl lingshu-cli -am compile` 通过
- `CliRunnerSkillTriggerTest`(4 case)全过 — AC-020c-10 + 2 extra
- 现有 `RunHandlerTest` / `DoctorHandlerTest` / `ResumeHandlerTest` **不**回归

---

## T-06 — 全量回归 + R-13 dep-tree 自查 + commit + push + PR body

**全量回归**:
```bash
mvn -pl lingshu-core,lingshu-cli clean test
```
- Story #001—#019 + #020a + #020b 全部既有 case 必须全过
- Story #020c 新增 ≥ 22 case 全过
- 预计总 ~273 case 全绿

**R-13 dependency:tree 自查**:
```bash
mvn -pl lingshu-cli dependency:tree -DincludeScope=runtime > /tmp/deps-post-020c.txt
diff /tmp/deps-baseline-020b.txt /tmp/deps-post-020c.txt
# 预期:仅时间戳差异,0 binary delta
# (ObjectMapper 走 jackson-databind 已锁,CountDownLatch/AtomicReference JDK 8 内置,LingsCliException 已存在)
```

**commit 消息**(沿用 CLAUDE.md §9 约定):
```
feat(cli): Story #020c cli-skill-trigger — SkillCommandDispatcher + /xxx intercept + skill banner

- SkillCommandDispatcher @Component (CLI /xxx intercept, dsh §6.4 L4039-4042 + L4266-4267)
  - 3 段职责:isSkillCommand/parse + handleUserInput + printSkillList/listSkillNames/listSkills
  - 走 ToolExecutor.dispatch(5 步流水线不能绕过 §4.10.1 硬规则 2)
  - LINGS-S05 / Z01 / T02 复用 Story #001 / #017 / #020a,0 新 ErrorCode
- Agent.continueWithUserMessageBlocking 同步版(镜像 runBlocking)
- DefaultAgent 实现(AtomicReference + CountDownLatch + Subscriber<AgentEvent>)
- CliRunner.doRun/doResume 前置 /xxx 拦截 + --list-skills banner
- CliRunner.doDoctor 末尾追加 skill banner
- Args.isPrintSkills + ArgsParser 解析 --list-skills flag
- 22 测试 case 全过(L1 14 + L2 8),0 新 Maven coordinates (R-13)
- dsh §13 changelog v1.5.40 单独 PR 同步
```

**PR body 模板**(沿用 #020a / #020b):
```markdown
## Story #020c `cli-skill-trigger`

参考:`specs/020c-cli-skill-trigger/{spec,plan,tasks}.md`(本 PR)

### 范围
落地 Skill 系统第三块砖 —— CLI /xxx 拦截核心 SkillCommandDispatcher + Agent.continueWithUserMessageBlocking 同步版 + CliRunner.doRun / doResume 前置 /xxx 拦截 + --list-skills 启动 banner + CliRunner.doDoctor 末尾追加 banner。

### 关键变更
- **1 新核心文件** SkillCommandDispatcher(@Component lingshu-cli,3 段职责识别/执行/展示)
- **1 接口扩展** Agent.continueWithUserMessageBlocking(+ DefaultAgent 实现)
- **3 CLI 改动** CliRunner + Args + ArgsParser
- **22 测试 case**(L1 14 + L2 8)全过
- **0 新 ErrorCode**(LINGS-S05/Z01/T02 复用 #001/#017/#020a)

### AC 验证摘要
| AC | 状态 |
|---|---|
| AC-020c-1: SkillCommandDispatcher 是 @Component | ✅ |
| AC-020c-2: parse 5 场景(slash+args / onlyCommand / empty / noSlash / null) | ✅ |
| AC-020c-3: isSkillCommand 5 场景(registered / notRegistered / noSlash / null / argsIgnored) | ✅ |
| AC-020c-4: listSkillNames 排序 + dedup | ✅ |
| AC-020c-5: listSkills 返 name + description | ✅ |
| AC-020c-6: printSkillList 3 场景(empty / populated / sorted) | ✅ |
| AC-020c-7: handleUserInput happy path 完整调用链 | ✅ |
| AC-020c-8: handleUserInput unknown skill 抛 S05 | ✅ |
| AC-020c-9: handleUserInput invalid prefix 抛 Z01 | ✅ |
| AC-020c-10: CliRunner.doRun /xxx 拦截 vs LLM path | ✅ |
| EC-020c-1: printSkillList 截断 80 字符 | ✅ |
| EC-020c-2: parse("/") 只有 slash | ✅ |
| EC-020c-3: handleUserInput Skill 执行 error 抛 T02 | ✅ |

### R-13 dependency:tree 自查
- baseline: `mvn -pl lingshu-cli dependency:tree -DincludeScope=runtime`(Story #020b 合并后镜像)
- post: 同命令(Story #020c 合并前)
- diff: **仅时间戳差异,0 binary delta**(`ObjectMapper` jackson-databind 已锁,`CountDownLatch`/`AtomicReference` JDK 8 内置,`LingsCliException` 已存在)
- 13 项依赖未增 → R-13 维持 PASS
```

**DoD**:
- commit message + PR body 完整
- `git push` 到 `story/020c-cli-skill-trigger` 分支
- PR body 含 `### R-13 dependency:tree 自查` 节(CLAUDE.md §11 #6 强制项)
- 创建分支前 `git checkout -b story/020c-cli-skill-trigger`(对齐 #020a / #020b 模式)
- dsh v1.5.40 单独 PR 同步(沿用 #020a / #020b 模式)
- ROADMAP.md `✅ 已完成` 表追加 `#020c`
- CLAUDE.md 版本号同步(`1.3.33 → 1.3.34`,沿用 `v1.3.x` 与 dsh `v1.5.x` 配套)
- README.md Story 路线图追加 + 简短 retrospective

---

## 任务依赖图

```
T-01 (Agent.continueWithUserMessageBlocking + DefaultAgent)
  ↓
T-02 (SkillCommandDispatcher 主类 + 14 L1 单测) ─ 依赖 Agent 接口新方法就位
  ↓
T-03 (SkillCommandDispatcher L2 子组 8 case) ─ 依赖 T-02(主类已写)+ mock ToolExecutor / Agent
  ↓
T-04 (Args.isPrintSkills + ArgsParser --list-skills) ─ 无依赖(独立小改)
  ↓
T-05 (CliRunner 修改 + 集成测试) ─ 依赖 T-02 + T-03 + T-04(dispatcher + flag 都就位)
  ↓
T-06 (全量回归 + R-13 dep-tree 自查 + commit + push + PR body) ─ 依赖 T-01—T-05 全部
```

---

**Last updated**: 2026-09-23
**Tasks author**: Claude Code
**Reviewer**: 待 PR review
