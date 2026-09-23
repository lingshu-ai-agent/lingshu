# Story #020c `cli-skill-trigger` — Spec

> **Status**: Draft 2026-09-23
> **Source**: dsh v1.5.37 §6.4 `Skill —— Tool 的约定性 marker`(L3970-4420)+ L3989-4000 `ToolRegistry.skillNames/findSkill` + L4039-4042 `handleUserInput` + L4266-4267 `SkillTool.execute` 把 content 当 User message 喂回 turn
> **Closes gap**: Story #020a / #020b 已铺好 Skill 体系(`SkillTool` + `fromMarkdown` + `@Component CommitSkill` + `ToolRegistry` 4 个 Skill 方法 + 多源 SKILL.md 自动发现),但 **CLI 入口层(`/xxx` 拦截 + handleUserInput + 自动补全 + 启动 banner dump skills)0 实现**:
> (1) **CLI `/xxx` 拦截** —— 当前 `lingshu run --prompt "..."` 走 LLM,无法让用户用 `/commit staged diff` 这种命令直触发 Skill(必须经模型 FunctionCalling 才能调 Skill);
> (2) **`handleUserInput` 方法缺** —— dsh §6.4 L4042 给的 `public void handleUserInput(String raw, Agent agent, TurnContext ctx)` 是 Skill 触发路径的"single-source-of-truth",当前 `lingshu-cli` 没有对应实现;
> (3) **Skill 列表自动补全缺** —— `ToolRegistry.skillNames()` 已就位(Story #020a),但 CLI 没有 "Available: /commit /review /deploy" 这种友好提示,用户不知道有哪些命令;
> (4) **启动 banner dump skills 缺** —— 用户 `mvn spring-boot:run --args="run ..."` 启动后,**只能**从 Spring log 里 grep "Skills ready" 行,看不到 CLI-facing 的 "Available commands: ..."。
> 后果:Story #020a + #020b 的 Skill 系统对企业用户**完全不可见**,只有 IDE 打开 registry.debug() 才看到 —— 用户感知不到 `/commit` / `/review` 这种核心 UX。

---

## WHY

Story #020a 落地 `Skill` interface + `SkillTool.fromMarkdown` + `CommitSkill` + `ToolRegistry.skillNames/findSkill/modelVisibleSpecs/findByName` 4 方法,Story #020b 落地 `SkillSource` SPI + `SkillSourceProvider` SPI + 2 v1 实现(classpath / directory)+ `SkillSourceRouter` + `CompositeSkillLoader` 聚合器 + `SkillSourceProperties` 配置类 + `SkillAutoConfiguration` Phase 1 discover + Phase 2 @Component —— **Skill 数据层完整**。但 Skill **怎么被用户触发** 仍是空白 —— dsh §6.4 L4039 写明 "**CLI 层拦截 `/xxx`**(用户触发路径)",L4042 给的 `handleUserInput(raw, agent, ctx)` 是契约入口;dsh L4266 进一步说 "**整段 content 作为 User message 喂回 turn**(后续被当成 User message 喂回 turn,见 CLI 层 `continueWithUserMessage`)"。代码侧 0 实现,这造成 3 个连锁问题:

1. **dsh §6.4 L4039-4042 文档契约是 single-source-of-truth,但代码侧缺实现** — `handleUserInput` 在 dsh L4042 给出方法签名,`ToolRegistry.skillNames()` 在 L4001 已实现(Story #020a),`ToolRegistry.findSkill(name)` 在 L3998 已实现(Story #020a)—— 三个契约锚点都在,只缺"把它们串成 CLI `/xxx` 拦截链路"的代码
2. **`lingshu run --prompt "/commit ..."` 当前会走 LLM,无法直触发 Skill** — Story #020a 让 Skill 拥有 FunctionCalling schema(对模型可见),Story #020b 让 Skill 从 SKILL.md 自动加载;但 CLI 用户写 `/commit fix bug`,Agent 把 `/commit` 当成普通文本喂 LLM,LLM 再 FunctionCalling → ToolExecutor → SkillTool —— **多此一举**,应该 CLI 层先拦,直接调 SkillTool,wrap result as User message,送 `continueWithUserMessage()`,1 步完成
3. **企业用户的 `/commit` / `/review` 命令"看不见摸不着"** — 当前启动后,Spring log 打 "Skills ready — 1 skill(s) ..."(`SkillAutoConfiguration` #020b Phase 1+2),但 CLI stdout **0 提示**;用户 `lingshu run --prompt "..."` 不知道有哪些 `/xxx` 命令可用 —— CLI 价值打折

**Story #020c 目标**:落地 `SkillCommandDispatcher`(CLI `/xxx` 拦截核心)+ `CliRunner.doRun` 前置 `/xxx` 分支 + `SkillCommandDispatcher.printSkillList()` 启动 banner dump skills,**Slot 4 Skill 体系第三块砖**(从"用户看不见的 Skill" → "用户能 `/xxx` 触发的 Skill")。

**业务价值**:
- dsh §6.4 §0.1 锁定决策 5 "Skill 既能被模型自动调用(对模型可见 schema),也能被用户通过 `/xxx` 显式触发" **完整 half-done** —— `/xxx` 这条路通了
- dsh §1 决策 9 "Plugin 发现走 Spring Boot SPI" 真正在 CLI 层落地 —— 用户写 SKILL.md,启动 banner 看到 `/xxx` 命令,直接可用,**不要 IDE 翻 registry**
- dsh §6.4 L4039-4042 / L4266 文档契约有 single-source-of-truth 代码锚点

---

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类)| `lingshu run --prompt "/commit fix login bug"` 一行触发 Skill,跳过 LLM;`lingush doctor` 看到"Available commands (3): /commit, /review, /deploy" |
| **业务配置方**(Diana 类)| 写 `agent.skills.sources: [{type: directory, location: ./team-skills/}]`,启动后 `lingshu doctor` 自动列出团队所有 `/xxx` 命令,**不需要**读代码 |
| **CLI 用户**(Eve 类)| 启动 CLI 后看到 "Available commands:" 列表;`/xxx` 直触发 Skill,绕开 LLM 省 token + 省 1—3 秒 |
| **CI 工程师**(Charlie 类)| L1/L2 测试 case ≥ 8,`mvn -pl lingshu-cli test` 0 fail;`mvn dependency:tree` 0 增量 |
| **框架贡献者**(plugin 作者)| 写 `@Component public class MySkill implements Skill` 或 SKILL.md 文件 → CLI `/my-skill` 自动可用,无 CLI 侧代码改动 |

---

## WHAT

Story #020c 落地 1 个新核心类 + 1 个修改 + 2 个测试:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `SkillCommandDispatcher` | `@Component` (package-private `class`) | `lingshu-cli/src/main/java/ai/lingshu/cli/SkillCommandDispatcher.java` | ~140 |
| `CliRunner` | 修改:`doRun` 前置 `/xxx` 拦截 + 启动 banner | `lingshu-cli/src/main/java/ai/lingshu/cli/CliRunner.java` | +30 |
| `SkillCommandDispatcherTest` | L1+L2 测试 | `lingshu-cli/src/test/java/ai/lingshu/cli/SkillCommandDispatcherTest.java` | ~200 / ≥ 8 case |
| `CliRunnerSkillTriggerTest` | L2 测试(`/xxx` 拦截集成)| `lingshu-cli/src/test/java/ai/lingshu/cli/CliRunnerSkillTriggerTest.java` | ~120 / ≥ 4 case |

**4 核心文件 = 1 新建 + 1 修改 + 2 新建测试**,0 ErrorCode,`mvn dependency:tree` 0 增量。

### 1. `SkillCommandDispatcher` 主类

```java
package ai.lingshu.cli;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.RunResult;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Story #020c — CLI /xxx intercept layer (dsh §6.4 L4039-4042 + L4266-4267).
 *
 * <p>负责把用户输入 "/xxx arg1 arg2 ..." 翻译成 ToolCall + Skill.execute + 包装成 User message 喂回 turn。
 * 不直接 import Skill 业务实现 —— 只看 ToolRegistry + ToolExecutor + Agent(全部是 SPI 边界)。
 *
 * <p>三段职责:
 * <ol>
 *   <li><b>识别</b>:isSkillCommand(raw) 判断 raw 是否是 "/xxx" 形式,xxx 是已注册 Skill 名(也支持自动补全提示)</li>
 *   <li><b>执行</b>:handleUserInput(raw, agent) 拦 Skill,build ToolCall → ToolExecutor.dispatch(走 5 步流水线) → wrap as User message → agent.continueWithUserMessage() → 拿 RunResult</li>
 *   <li><b>展示</b>:printSkillList(out) 启动 banner dump 所有 Skill(name + description)</li>
 * </ol>
 */
@Component
public class SkillCommandDispatcher {

    private static final String SLASH_PREFIX = "/";

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public SkillCommandDispatcher(ToolRegistry toolRegistry,
                                  ToolExecutor toolExecutor) {
        this(toolRegistry, toolExecutor, new ObjectMapper());
    }

    /** Test-only constructor — allows ObjectMapper stub for JSON construction. */
    SkillCommandDispatcher(ToolRegistry toolRegistry,
                           ToolExecutor toolExecutor,
                           ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    // ─── 1. 识别 ─────────────────────────────────────────────────────────

    /**
     * Parse {@code /xxx arg1 arg2 ...} → {@link ParsedCommand} (name + args).
     *
     * @param raw 用户输入(可空,空 → name="" args="")
     * @return ParsedCommand(name, args);不抛异常(让调用方决定 unknown 怎么处理)
     */
    public ParsedCommand parse(String raw) {
        if (raw == null) {
            return new ParsedCommand("", "");
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith(SLASH_PREFIX)) {
            return new ParsedCommand("", "");
        }
        String withoutSlash = trimmed.substring(SLASH_PREFIX.length());
        int ws = indexOfWhitespace(withoutSlash);
        if (ws < 0) {
            return new ParsedCommand(withoutSlash, "");
        }
        return new ParsedCommand(
            withoutSlash.substring(0, ws),
            withoutSlash.substring(ws + 1).trim());
    }

    /**
     * 是否 "/xxx" 形式 + xxx 是已注册 Skill 名?
     *
     * <p>空 raw / 不以 "/" 开头 / 不在 registry → false;
     * "/xxx" + xxx 已注册 Skill → true。
     */
    public boolean isSkillCommand(String raw) {
        ParsedCommand p = parse(raw);
        if (p.name.isEmpty()) return false;
        return toolRegistry.findSkill(p.name) != null;
    }

    // ─── 2. 执行 ─────────────────────────────────────────────────────────

    /**
     * CLI 层 /xxx 拦截入口(对齐 dsh §6.4 L4042 handleUserInput 契约)。
     *
     * <p>流程:
     * <ol>
     *   <li>parse(raw) → name + args</li>
     *   <li>toolRegistry.findSkill(name) — 不存在 → 抛 LingsCliException(LINGS-S05) + "Available: [...]"</li>
     *   <li>build ToolCall({ "input": args }) — 固定 schema { "input": string },对齐 dsh §6.4 L4267</li>
     *   <li>ToolExecutor.dispatch(call, ctx) — 走 §4.10.1 5 步流水线(权限/超时/沙箱 不能绕过)</li>
     *   <li>取 result.getContent() 当 User message → agent.continueWithUserMessage(content)</li>
     *   <li>返 RunResult 同步收集结果(turns=1, stopReason=END_TURN)</li>
     * </ol>
     *
     * @param raw 用户输入,以 "/" 开头,后续为 skill name + 可选 args
     * @param agent 已 create 好的 Agent(从 factory.create(cfg) 拿)
     * @return RunResult(continuation turn 的 finalText + usage + stopReason)
     * @throws LingsCliException {@code LINGS-S05} 如果 skill 不存在(对齐 dsh §15 S 域)
     * @throws LingsCliException {@code LINGS-Z01} 如果 raw 为空或不以 "/" 开头
     */
    public RunResult handleUserInput(String raw, Agent agent) {
        ParsedCommand p = parse(raw);
        if (p.name.isEmpty()) {
            throw new LingsCliException("LINGS-Z01",
                "empty or invalid /xxx command: '" + raw + "'",
                "type /<skill-name> with optional args (use `lingshu doctor` to list available)");
        }
        Skill skill = toolRegistry.findSkill(p.name);
        if (skill == null) {
            throw new LingsCliException("LINGS-S05",
                "Unknown skill command: /" + p.name,
                "Available: " + sortedSkillNames());
        }
        ObjectNode input = objectMapper.createObjectNode();
        input.put("input", p.args);
        ToolCall call = ToolCall.builder()
            .id("cli-skill-" + System.currentTimeMillis())
            .name(p.name)
            .input(input)
            .build();
        ToolExecutionContext ctx = ToolExecutionContext.builder()
            // 由 LinearTurnEngine 内部完整,这里 CLI 单 shot 模式给 null permissions
            .tenantId("default")
            .build();
        ToolResult result = toolExecutor.dispatch(call, ctx);
        if (result.isError()) {
            throw new LingsCliException("LINGS-T02",
                "Skill execution failed: " + result.getContent(),
                "check skill input / system logs");
        }
        // Wrap result.content as User message → continueWithUserMessage → 1 个 turn
        return agent.continueWithUserMessageBlocking(result.getContent());
    }

    // ─── 3. 展示 ─────────────────────────────────────────────────────────

    /**
     * 启动 banner dump skills —— 给 CliRunner.doRun / doctor 调。
     *
     * <p>格式:
     * <pre>
     * Available commands (N):
     *   /commit   — 按 Conventional Commits 风格生成 commit message
     *   /review   — 按团队 code review checklist 检查 PR
     *   /deploy   — 部署到 staging 环境
     * </pre>
     *
     * <p>按 name 字典序,固定 4-空格缩进,description 截断 ≤ 80 字符。
     */
    public void printSkillList(PrintStream out) {
        Set<String> names = toolRegistry.skillNames();
        if (names.isEmpty()) {
            out.println("[LINGS-Z99] Available commands: (none registered — check agent.skills.* config)");
            return;
        }
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);
        out.println("[LINGS-Z99] Available commands (" + sorted.size() + "):");
        for (String n : sorted) {
            Skill s = toolRegistry.findSkill(n);
            String desc = s == null ? "(no description)" : truncate(s.description(), 80);
            out.println("  /" + n + "   — " + desc);
        }
    }

    /**
     * 列出所有 Skill 名(供自动补全 / 错误提示用)—— 返回 sorted List。
     */
    public List<String> listSkillNames() {
        List<String> out = new ArrayList<String>(toolRegistry.skillNames());
        Collections.sort(out);
        return out;
    }

    /**
     * 列出所有 Skill (name + description) —— 供 future REPL tab-completion / docs。
     */
    public List<SkillInfo> listSkills() {
        Set<String> names = new TreeSet<String>(toolRegistry.skillNames());
        List<SkillInfo> out = new ArrayList<SkillInfo>(names.size());
        for (String n : names) {
            Skill s = toolRegistry.findSkill(n);
            out.add(new SkillInfo(n, s == null ? "" : s.description()));
        }
        return out;
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static List<String> sortedSkillNames(Set<String> names) {
        List<String> out = new ArrayList<String>(names);
        Collections.sort(out);
        return out;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** Parsed /xxx command: skill name + rest as input string. */
    public static final class ParsedCommand {
        public final String name;
        public final String args;
        public ParsedCommand(String name, String args) {
            this.name = name == null ? "" : name;
            this.args = args == null ? "" : args;
        }
    }

    /** Public Skill summary — name + description pair. */
    public static final class SkillInfo {
        public final String name;
        public final String description;
        public SkillInfo(String name, String description) {
            this.name = name;
            this.description = description == null ? "" : description;
        }
    }
}
```

**关键约束**(对齐 dsh §6.4 + §4.10.1):
- `isSkillCommand` / `parse` **不抛异常** —— 让调用方决定 unknown 怎么处理
- `handleUserInput` 走 `ToolExecutor.dispatch(call, ctx)` —— **不能**直接 `skill.execute(call, ctx)`(绕开沙箱/权限/超时 5 步流水线,违反 §4.10.1 硬规则 2)
- `ToolCall.input` schema 固定 `{ "input": string }` —— 对齐 `SkillTool.FIXED_INPUT_SCHEMA_JSON` / `CommitSkill.inputSchema()`(Story #020a 不变项)
- `args.trim()` 把 `/commit fix login bug` 的 args 合成 `"fix login bug"`(单字符串,因 inputSchema 只有一个 string 字段)
- `LINGS-S05` Slot 域(对齐 §15 S 域 = SPI / Slot 错)—— 复用 #001 S05,无新增 ErrorCode
- `printSkillList` 输出 `[LINGS-Z99]` 前缀 —— 对齐 Story #017 既有 CliRunner doRun / doConfig 输出风格
- `ObjectMapper` 只用来构造 `ObjectNode`,不做 JSON 解析 —— 0 性能开销
- `description` 截断 80 字符 —— banner 排版美观,避免超长 description 撑爆屏幕

### 2. `CliRunner` 修改(对齐 dsh §6.4 L4039 `/xxx` 拦截)

```java
@Component
public class CliRunner implements ApplicationRunner {

    private final AgentFactory factory;
    private final SkillCommandDispatcher skillDispatcher;   // 🆕 Story #020c
    private final PrintStream out;
    private final PrintStream err;

    @Autowired
    public CliRunner(AgentFactory factory, SkillCommandDispatcher skillDispatcher) {
        this(factory, skillDispatcher, System.out, System.err);
    }

    CliRunner(AgentFactory factory, SkillCommandDispatcher skillDispatcher,
              PrintStream out, PrintStream err) {
        this.factory = factory;
        this.skillDispatcher = skillDispatcher;
        this.out = out;
        this.err = err;
    }

    // ─── doRun 修改 ───────────────────────────────────────────────────

    void doRun(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);

        // 🆕 Story #020c — 启动 banner dump skills(doctor 也调同方法)
        if (args.isPrintSkills()) {                  // 🆕 flag --list-skills
            skillDispatcher.printSkillList(out);
            return;
        }

        Agent agent = factory.create(cfg);

        // 🆕 Story #020c — /xxx 拦截(if --prompt 以 "/" 开头且 xxx 是已注册 Skill)
        if (skillDispatcher.isSkillCommand(args.getPrompt())) {
            RunResult result = skillDispatcher.handleUserInput(args.getPrompt(), agent);
            out.println(result.getFinalText());
            out.println();
            out.println("[LINGS-Z99] skill-trigger turns=" + result.getTurns()
                + " usage=" + result.getTotalUsage()
                + " stopReason=" + result.getStopReason()
                + " elapsedMs=" + result.getElapsedMillis());
            return;
        }

        // 原有 path — 走 LLM
        RunResult result = agent.runBlocking(args.getPrompt());
        out.println(result.getFinalText());
        out.println();
        out.println("[LINGS-Z99] turns=" + result.getTurns()
            + " usage=" + result.getTotalUsage()
            + " stopReason=" + result.getStopReason()
            + " elapsedMs=" + result.getElapsedMillis());
    }
}
```

**关键约束**:
- **`/xxx` 拦截早于 LLM** —— `isSkillCommand` 检查 + `handleUserInput` 直接调 Skill + wrap as User message,跳过 LLM,**省 1 个 model call + N 秒延迟**
- `args.isPrintSkills()` 对应 `--list-skills` flag —— 单独跑 `lingshu run --list-skills` 只打印 skills 不调 Agent
- `agent.continueWithUserMessageBlocking(...)` 是 `Agent` 新方法(Story #020c 加)—— 同步版的 `continueWithUserMessage(content)`,返 `RunResult`(对齐 #001 runBlocking 风格)
- `doDoctor` 也调 `skillDispatcher.printSkillList(out)` —— 顺便把 skills 列在 doctor 输出末尾(对齐 #017 既有 doctor 输出风格)
- `doServe` 不动 —— A2A server 模式无 CLI prompt,`/xxx` 在 REST 端点另外实现(留未来 / OQ-Future)
- `doResume` 同样加 `/xxx` 拦截(同 doRun 逻辑)

### 3. `Agent.continueWithUserMessageBlocking` 接口扩展(Story #020c 加)

```java
public interface Agent {
    // 已有 3 方法(#001):
    Publisher<AgentEvent> run(String userInput);
    RunResult runBlocking(String userInput);
    Publisher<AgentEvent> continueWithUserMessage(String content);

    // 🆕 Story #020c — 同步版 continueWithUserMessage(对齐 runBlocking 风格)
    /**
     * Sync wrapper for {@link #continueWithUserMessage} — subscribes + collects to RunResult.
     * For CLI /xxx dispatch: Skill tool result is wrapped as synthetic User message,
     * fed to this method, final text printed to console.
     */
    RunResult continueWithUserMessageBlocking(String content);
}
```

**`DefaultAgent` 实现**(镜像 `runBlocking`):
```java
@Override
public RunResult continueWithUserMessageBlocking(String content) {
    AtomicReference<RunResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    continueWithUserMessage(content).subscribe(new Subscriber<AgentEvent>() {
        @Override public void onSubscribe(Subscription sub) { sub.request(Long.MAX_VALUE); }
        @Override public void onNext(AgentEvent event) { /* terminal TurnCompleted sets it */ }
        @Override public void onError(Throwable t) { latch.countDown(); }
        @Override public void onComplete() { latch.countDown(); }
    });
    try { latch.await(turnTimeoutSeconds, TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    return result.get();
}
```

**约束**:
- `Agent` 接口 + 1 方法,**向后兼容** — 已有实现如果只 override 3 方法,新方法 default 抛 `UnsupportedOperationException`(参考 §5.5 多 Provider 模式,默认 stub 给后续 Story 填;但本 Story 是 `DefaultAgent` 加实现,无 default 必要**)
- 实际:本 Story 直接在 `DefaultAgent` 加实现,接口不改 default

**注意**:这个方法加在 `Agent` interface 是 **接口扩展**,CLAUDE.md §11 #3 "不跨 Story 改宪章" + #020c 在主链上,**允许**(对齐 #001 — `runBlocking` 也是后续 Story 加的同步版)

---

## 反向 AC(明确不做)

- ❌ **REPL / 交互模式**(`> /xxx arg` + 持续输入)— 留 Story #017b(OQ-Future)
- ❌ **Bash completion / man page** — 留 Story #017b
- ❌ **`/xxx` 命名空间嵌套 / 别名 `/c` → `commit`** — dsh §6.4 L4030 标记 "未来扩展",本 Story 仍严格匹配 Skill.name()
- ❌ **PermissionTier(用户专属触发)** — dsh §6.4 L4032,留 Skill v1.1 RFC
- ❌ **DangerLevel → §4.7 审批门联动** — dsh §6.4 L4033,留 Skill v1.1 RFC
- ❌ **Skill hot-reload(directory 监听)** — 留 §14.8 / 配套 Story #007
- ❌ **A2A `/xxx` REST 端点** — 留 A2A / Story #009e(OQ-Future)
- ❌ **`Skill.execute` 直接调**(绕 ToolExecutor)—— 违反 §4.10.1 硬规则 2,**本 Story 必须**走 ToolExecutor.dispatch
- ❌ **PromptBuilder 集成 `modelVisibleSpecs()`** — 留 OQ-5
- ❌ **`Skill` interface 新方法** — 仍为 marker
- ❌ **`ToolRegistry.skillNames()` 改 return type** — 已 `Set<String>` 返不需变
- ❌ **`SkillTool` / `CommitSkill` 改动** — #020a 不变项,本 Story 只用
- ❌ **Sub-agent 内 `/xxx` 触发** — 留 #023

---

## AC 编号(Story #020c 新增,不入 dsh §0.4)

| AC | 描述 | 验证 |
|---|---|---|
| **AC-020c-1** | `SkillCommandDispatcher` 是 `@Component`,Spring 启动后 Bean 已就绪,构造器注入 `ToolRegistry` + `ToolExecutor` | `SkillCommandDispatcherTest.isBeanRegistered_(L1 1 case,mini ctx)` |
| **AC-020c-2** | `parse("/commit fix login")` 返 `ParsedCommand(name="commit", args="fix login")`;`parse("/commit")` 返 `name="commit", args=""`;`parse("/  ")` 返 `name="", args=""`(空 → 不抛);`parse("commit")`(无 `/`)返 `name="", args=""` | `SkillCommandDispatcherTest.parse_*`(L1 5 case)|
| **AC-020c-3** | `isSkillCommand("/commit")` 在 ToolRegistry 含 `"commit"` Skill 时返 true;ToolRegistry 含 `"review"` Skill 时 `isSkillCommand("/review xxx")` 返 true;ToolRegistry 空 → `isSkillCommand("/xxx")` 返 false;`isSkillCommand("hello")`(无 `/`)返 false;`isSkillCommand(null)` 返 false | `SkillCommandDispatcherTest.isSkillCommand_*`(L2 5 case,真实 ToolRegistry + Stub Tool)|
| **AC-020c-4** | `listSkillNames()` 返 `List<String>` 按字典序排序,空 → 空 list;registry 含 3 Skill → 返 3 元素 list;**registry 含同名 Skill(duplicate)** → dedup(实际由 putIfAbsent 保证) | `SkillCommandDispatcherTest.listSkillNames_*`(L1 2 case)|
| **AC-020c-5** | `listSkills()` 返 `List<SkillInfo>` 含 name + description,字典序排;空 → 空 list;每个 SkillInfo.description() = `skill.description()` | `SkillCommandDispatcherTest.listSkills_*`(L1 2 case)|
| **AC-020c-6** | `printSkillList(PrintStream)` 空 → stdout 含 "Available commands: (none registered...)";registry 含 1 Skill → stdout 含 "[LINGS-Z99] Available commands (1):" + "  /commit   — <description>";按字典序排 | `SkillCommandDispatcherTest.printSkillList_*`(L1 3 case)|
| **AC-020c-7** | `handleUserInput("/commit fix bug", agent)` 流程:parse → findSkill("commit") → build ToolCall(`{input: "fix bug"}`) → toolExecutor.dispatch → result.content → agent.continueWithUserMessageBlocking → RunResult 返 stdout | `SkillCommandDispatcherTest.handleUserInput_happyPath_executesAndContinues`(L2 1 case,mock ToolExecutor + mock Agent)|
| **AC-020c-8** | `handleUserInput("/nonexistent", agent)` 抛 `LingsCliException("LINGS-S05", "Unknown skill command: /nonexistent", "Available: [...]")`;**不**走到 toolExecutor | `SkillCommandDispatcherTest.handleUserInput_unknownThrowsLingsS05`(L2 1 case)|
| **AC-020c-9** | `handleUserInput("", agent)` 抛 `LingsCliException("LINGS-Z01", "empty or invalid", "type /<skill-name>...")`;**不**走到 toolExecutor;`handleUserInput("hello world", agent)`(无 `/`)同样抛 Z01 | `SkillCommandDispatcherTest.handleUserInput_invalidPrefixThrowsLingsZ01`(L1 2 case)|
| **AC-020c-10** | CliRunner.doRun 当 `--prompt "/commit fix bug"`(commit 已注册)→ 走 `skillDispatcher.handleUserInput` 分支,stdout 含 `SkillTool.execute` 返的 content;**不**走 `agent.runBlocking`;CliRunner.doRun 当 `--prompt "hello"`(无 `/`)→ 走原 LLM path | `CliRunnerSkillTriggerTest.doRun_withSlashPrompt_dispatchesSkill` + `doRun_withRegularPrompt_goesThroughLlm`(L2 2 case,real AgentFactory + Stub FlowEngine)|

**EC(边界 case)**:

| EC | 描述 | 验证 |
|---|---|---|
| **EC-020c-1** | `printSkillList` description 超长(> 80 字符)→ 截断为 ≤ 80 字符 + `"..."`(对齐 banner 美观) | `SkillCommandDispatcherTest.printSkillList_truncatesLongDescription`(L1 1 case)|
| **EC-020c-2** | `parse("/")`(只有 slash)→ `name="", args=""`(parse 不抛,但 isSkillCommand 返 false → handleUserInput 走 Z01 路径)| `SkillCommandDispatcherTest.parse_onlySlash`(L1 1 case)|
| **EC-020c-3** | `handleUserInput` 调 SkillTool.execute 返 `ToolResult.isError()=true` → 抛 `LingsCliException("LINGS-T02", "Skill execution failed: ...", "...")`(对齐 dsh §15 T 域,Tool 错误) | `SkillCommandDispatcherTest.handleUserInput_skillReturnsError_throwsLingsT02`(L2 1 case,mock ToolExecutor 返 error)|

---

## ErrorCode 引入(0 条)

| 码 | 域 | 触发场景 |
|---|---|---|
| — | — | **0 新 ErrorCode**(`LINGS-S05` 复用 #001 Slot 域 + `LINGS-Z01` 复用 #017 CLI 域 + `LINGS-T02` 复用 #020a Skill execute 错)|

**约束 Story 边界**(CLAUDE.md §11 #4 ≤ 5 核心文件,≤ 3 ErrorCode):1 新建(SkillCommandDispatcher)+ 1 修改(CliRunner)+ 2 新建测试 = **4 文件**,1 接口扩展(`Agent.continueWithUserMessageBlocking` + `DefaultAgent` 加实现,**算 1 个修改,等同 CliRunner**)。0 ErrorCode 远 ≤ 3。**Story 边界满足**。

---

## 出口标准(DoD)

- [ ] `specs/020c-cli-skill-trigger/{spec,plan,tasks}.md` 三件套合入主分支
- [ ] 1 个新核心文件 + 1 个修改 + 1 个接口扩展 + 2 个测试目录 = 5 文件落地
- [ ] ≥ 12 测试 case(L1 14 + L2 8 + L3 0)全过(`mvn -pl lingshu-cli test`)
- [ ] 既有 Story #001—#019 + #020a + #020b 测试**不**回归(预计 ~273 case 全绿)
- [ ] R-13 `mvn dependency:tree` 自查:**0 新 Maven coordinates**(`mvn -pl lingshu-cli dependency:tree -DincludeScope=runtime` pre/post diff 仅时间戳)
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节
- [ ] dsh v1.5.39 单独 PR 同步(沿用 Story #020a 模式:`v1.5.37 → v1.5.38` 是 #020a,`v1.5.38 → v1.5.39` 是 #020b,`v1.5.39 → v1.5.40` 是 #020c — `§13` changelog 加 Story #020c 行)
- [ ] ROADMAP.md 段一「✅ 已完成」表追加 `#020c cli-skill-trigger`
- [ ] CLAUDE.md 版本号同步(`1.3.33 → 1.3.34`,沿用 `v1.3.x` 与 dsh `v1.5.x` 配套)
- [ ] README.md Story 路线图追加 + 简短 retrospective 段(对齐 #020a/#020b)

---

## 不在 Story #020c 范围(显式 deferred)

- ❌ REPL / 交互模式(`> /xxx` 持续输入)— 留 Story #017b / OQ-Future
- ❌ Bash completion / man page — 留 Story #017b
- ❌ `/xxx` 命名空间嵌套 / 别名 `/c` → `commit` — 留 Skill v1.1 RFC(dsh §6.4 L4030)
- ❌ PermissionTier(用户专属触发)— 留 Skill v1.1 RFC(dsh §6.4 L4032)
- ❌ DangerLevel → §4.7 审批门联动 — 留 Skill v1.1 RFC(dsh §6.4 L4033)
- ❌ Skill hot-reload(directory 监听)— 留 §14.8 / Story #007 N8 配套
- ❌ A2A `/xxx` REST 端点 — 留 A2A / OQ-Future
- ❌ `Skill.execute` 直接调(必须走 `ToolExecutor.dispatch`) — 违反 §4.10.1 硬规则 2,**本 Story 必须**走
- ❌ PromptBuilder 集成 `modelVisibleSpecs()` — 留 OQ-5
- ❌ `Skill` interface 新方法 — 留 Skill v1.1 RFC
- ❌ Sub-agent 内 `/xxx` 触发 — 留 #023 delegate-sub-agent

---

**Last updated**: 2026-09-23
**Spec author**: Claude Code (per user 2026-09-23 conversation)
**Reviewer**: 待 PR review