# Story #020c `cli-skill-trigger` — Plan

> **Status**: Draft 2026-09-23
> **Implements**: `specs/020c-cli-skill-trigger/spec.md`
> **Source**: dsh v1.5.37 §6.4 L3970-4420 + L4039-4042 `handleUserInput` + L4266-4267 `SkillTool.execute` 内容作 User message 喂回 turn
> **Pre-req**:
> - Story #020a ✅ skill-foundation(`SkillTool` + `fromMarkdown` + `@Component CommitSkill` + `ToolRegistry` Skill 索引 + `SkillAutoConfiguration`)
> - Story #020b ✅ skill-source-discovery(`SkillSource` SPI + 2 v1 impls + `CompositeSkillLoader` + `SkillAutoConfiguration` Phase 1 discover)

---

## §1 范围与非范围

### In-Scope(4 文件 = 1 新建 + 1 新建接口扩展 + 1 修改 + 1 修改 + 2 新建测试)

| 文件 | 行为 |
|---|---|
| `lingshu-cli/src/main/java/ai/lingshu/cli/SkillCommandDispatcher.java`(新)| CLI `/xxx` 拦截核心(3 段职责:识别 / 执行 / 展示) |
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/Agent.java`(修改,+1 方法)| 接口扩展 `RunResult continueWithUserMessageBlocking(String content)` |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/runtime/DefaultAgent.java`(修改)| `continueWithUserMessageBlocking` 实现(镜像 `runBlocking`) |
| `lingshu-cli/src/main/java/ai/lingshu/cli/CliRunner.java`(修改)| `doRun`/`doResume` 前置 `/xxx` 拦截 + `--list-skills` banner dump |
| `lingshu-cli/src/main/java/ai/lingshu/cli/Args.java`(修改)| `+boolean printSkills` 字段 |
| `lingshu-cli/src/main/java/ai/lingshu/cli/ArgsParser.java`(修改)| 解析 `--list-skills` boolean flag |
| `lingshu-cli/src/test/java/ai/lingshu/cli/SkillCommandDispatcherTest.java`(新)| L1+L2 ≥ 10 case |
| `lingshu-cli/src/test/java/ai/lingshu/cli/CliRunnerSkillTriggerTest.java`(新)| L2 集成 ≥ 2 case(real `AgentFactory` + stub `FlowEngine`)|

**总计**:1 新建核心类 + 1 接口扩展 + 2 实现层修改(Agent/default + CliRunner + Args + ArgsParser 共 4 处小改)+ 2 新建测试 = **8 文件**(核心 5 + 测试 2 + 文档 1 已经在 spec.md)

### Out-of-Scope(显式 deferred)

- REPL / 交互模式(`> /xxx arg` 持续输入)— 留 Story #017b(OQ-Future)
- Bash completion / man page — 留 Story #017b(OQ-Future)
- `/xxx` 命名空间嵌套 / 别名 `/c` → `commit` — dsh §6.4 L4030;留 Skill v1.1 RFC
- PermissionTier(用户专属触发)— dsh §6.4 L4032;留 Skill v1.1 RFC
- DangerLevel → §4.7 审批门联动 — dsh §6.4 L4033;留 Skill v1.1 RFC
- Skill hot-reload(directory 监听)— 留 §14.8 / Story #007 N8
- A2A `/xxx` REST 端点 — 留 A2A / Story #009e(OQ-Future)
- `Skill.execute` 直调(必须走 `ToolExecutor.dispatch`)— 违反 §4.10.1 硬规则 2,**本 Story 必须**走
- PromptBuilder 集成 `modelVisibleSpecs()` — 留 OQ-5
- `Skill` interface 新方法(仍为 marker)— 留 Skill v1.1 RFC
- Sub-agent 内 `/xxx` 触发 — 留 #023 delegate-sub-agent

---

## §2 接口契约锚点(dsh §6.4 L3970-4420 + §4.10.1)

### 2.1 `SkillCommandDispatcher` 主类(dsh §6.4 L4039-4042 + L4266-4267)

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

/**
 * Story #020c — CLI /xxx intercept layer (dsh §6.4 L4039-4042 + L4266-4267).
 *
 * <p>负责把用户输入 "/xxx arg1 arg2 ..." 翻译成 ToolCall + Skill.execute + 包装成 User message 喂回 turn。
 * 不直接 import Skill 业务实现 —— 只看 {@link ToolRegistry} + {@link ToolExecutor} + {@link Agent}
 * (全部是 SPI 边界,与 §5.7 Spring Boot SPI 决策一致)。
 *
 * <p><b>三段职责</b>:
 * <ol>
 *   <li><b>识别</b>:{@link #isSkillCommand(String)} 判断 raw 是否是 "/xxx" 形式且 xxx 是已注册 Skill
 *       (也用于 {@link #parse(String)} 给 CliRunner 决定走 dispatch vs LLM 路径)</li>
 *   <li><b>执行</b>:{@link #handleUserInput(String, Agent)} 拦 Skill,build {@link ToolCall} →
 *       {@link ToolExecutor#dispatch(ToolCall, ToolExecutionContext)} 走 5 步流水线 →
 *       wrap result as User message → {@link Agent#continueWithUserMessageBlocking(String)} →
 *       返 {@link RunResult}</li>
 *   <li><b>展示</b>:{@link #printSkillList(PrintStream)} 启动 banner dump 所有 Skill(name + description)
 *       + {@link #listSkillNames()} / {@link #listSkills()} 给后续 REPL tab-completion / docs 用</li>
 * </ol>
 *
 * <p><b>Why {@link ToolExecutor#dispatch} not {@link Skill#execute}:</b> dsh §4.10.1 硬规则 2
 * 强制 ToolExecutor 5 步流水线(权限 / 超时 / 沙箱 / checkpoint)不能绕过;直调 Skill.execute 等于绕过
 * 沙箱 — 拒绝(CLAUDE.md §11 #7 + §11 #8 双重护栏)。
 *
 * <p><b>Why ObjectMapper injected not static:</b> 测试可注入 stub(构造器 package-private 重载);生产
 * {@code new ObjectMapper()} 无 Spring 容器依赖,降低测试复杂度。
 */
@Component
public class SkillCommandDispatcher {

    private static final String SLASH_PREFIX = "/";

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    @Autowired
    public SkillCommandDispatcher(ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this(toolRegistry, toolExecutor, new ObjectMapper());
    }

    /** Test-only constructor — allows ObjectMapper stub for JSON construction. */
    SkillCommandDispatcher(ToolRegistry toolRegistry, ToolExecutor toolExecutor,
                           ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    // ─── 1. 识别 ─────────────────────────────────────────────────────────

    /**
     * Parse {@code /xxx arg1 arg2 ...} → {@link ParsedCommand}(name + args)。
     * <p>不抛异常 — 空 raw / 无 `/` 前缀 / 只有 `/` → 返 {@code ParsedCommand("", "")} 让
     * {@link #isSkillCommand} / {@link #handleUserInput} 走 fallback 路径。
     *
     * <p><b>为什么要"不抛":</b>{@code parse} 调用点既包括 CliRunner(决定 dispatch vs LLM),
     * 也包括 AgentFactory 启动 banner(dump 全部 Skill);前者需要 fail-fast {@link LingsCliException}
     * 后者只是展示。parse 自身返空 ParsedCommand 让调用方按上下文决定下一步。
     */
    public ParsedCommand parse(String raw) {
        if (raw == null) return new ParsedCommand("", "");
        String trimmed = raw.trim();
        if (!trimmed.startsWith(SLASH_PREFIX)) return new ParsedCommand("", "");
        String withoutSlash = trimmed.substring(SLASH_PREFIX.length());
        int ws = indexOfWhitespace(withoutSlash);
        if (ws < 0) return new ParsedCommand(withoutSlash, "");
        return new ParsedCommand(
            withoutSlash.substring(0, ws),
            withoutSlash.substring(ws + 1).trim());
    }

    /**
     * 是否 "/xxx" 形式 + xxx 是已注册 Skill 名?
     * <p>空 raw / 不以 "/" 开头 / xxx 不在 registry → false。
     * "/xxx" + xxx 已注册 Skill → true(无论 xxx 后是否跟 args)。
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
     * <p><b>5 步流水线</b>:
     * <ol>
     *   <li>{@code parse(raw) → name + args}</li>
     *   <li>{@code toolRegistry.findSkill(name)} — 不存在 → 抛 {@link LingsCliException}
     *       ({@code LINGS-S05} 复用 #001 S 域)+ 错误消息含 "Available: [...]" 提示</li>
     *   <li>build {@code ToolCall({ "input": args })} — 固定 schema { "input": string }
     *       对齐 {@code SkillTool.FIXED_INPUT_SCHEMA_JSON} / {@code CommitSkill.inputSchema()}
     *       (Story #020a 不变项)</li>
     *   <li>{@code ToolExecutor.dispatch(call, ctx)} 走 §4.10.1 5 步流水线(权限 / 超时 / 沙箱 /
     *       checkpoint <b>不能绕过</b>)</li>
     *   <li>取 {@code result.getContent()} 作 User message →
     *       {@code agent.continueWithUserMessageBlocking(content)} →
     *       返 {@code RunResult}(continuation turn 的 finalText + usage + stopReason)</li>
     * </ol>
     *
     * @throws LingsCliException {@code LINGS-Z01} 如果 raw 为空或不以 "/" 开头
     * @throws LingsCliException {@code LINGS-S05} 如果 skill 不存在(对齐 dsh §15 S 域 = Slot 错)
     * @throws LingsCliException {@code LINGS-T02} 如果 Skill.execute 返 {@code ToolResult.isError()=true}
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
            .tenantId("default")
            .build();
        ToolResult result = toolExecutor.dispatch(call, ctx);
        if (result.isError()) {
            throw new LingsCliException("LINGS-T02",
                "Skill execution failed: " + result.getContent(),
                "check skill input / system logs");
        }
        // Wrap result.content as User message → continueWithUserMessageBlocking → 1 个 turn
        return agent.continueWithUserMessageBlocking(result.getContent());
    }

    // ─── 3. 展示 ─────────────────────────────────────────────────────────

    /**
     * 启动 banner dump skills — 给 {@code CliRunner.doRun}(仅当 {@code --list-skills}) /
     * {@code CliRunner.doDoctor} 调。
     *
     * <p>输出格式:
     * <pre>
     * [LINGS-Z99] Available commands (N):
     *   /commit   — 按 Conventional Commits 风格生成 commit message
     *   /review   — 按团队 code review checklist 检查 PR
     *   /deploy   — 部署到 staging 环境
     * </pre>
     *
     * <p><b>{@code description} 截断 80 字符</b>(对齐 banner 美观,避免超长 description 撑爆屏幕);
     * <b>按 name 字典序</b>对齐 {@code ToolRegistry.modelVisibleSpecs()}(Story #020a)的稳定排序哲学。
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
     * 列出所有 Skill 名(供自动补全 / 错误提示) — sorted List。
     */
    public List<String> listSkillNames() {
        List<String> out = new ArrayList<String>(toolRegistry.skillNames());
        Collections.sort(out);
        return out;
    }

    /**
     * 列出所有 Skill(name + description)— 供 future REPL tab-completion / docs。
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

    /** Sorted view of {@code toolRegistry.skillNames()} — used in LINGS-S05 hint message. */
    private List<String> sortedSkillNames() {
        List<String> out = new ArrayList<String>(toolRegistry.skillNames());
        Collections.sort(out);
        return out;
    }

    private static List<String> sortedNames(Set<String> names) {
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
- `isSkillCommand` / `parse` **不抛异常**(让调用方按上下文决定 unknown 怎么处理 — CliRunner 派发 vs AgentFactory banner dump)
- `handleUserInput` 走 `ToolExecutor.dispatch(call, ctx)` —— **不能**直接 `skill.execute(call, ctx)`(绕开沙箱/权限/超时 5 步流水线,**违反 §4.10.1 硬规则 2**)
- `ToolCall.input` schema 固定 `{ "input": string }` — 对齐 `SkillTool.FIXED_INPUT_SCHEMA_JSON` / `CommitSkill.inputSchema()`(Story #020a 不变项)
- `args.trim()` 把 `/commit fix login bug` 的 args 合成 `"fix login bug"`(单字符串,因 inputSchema 只有一个 string 字段)
- `LINGS-S05` Slot 域(对齐 §15 S 域 = SPI / Slot 错)— 复用 #001 S05,**0 新 ErrorCode**
- `printSkillList` 输出 `[LINGS-Z99]` 前缀 — 对齐 Story #017 `CliRunner.doRun` / `doConfig` 输出风格
- `description` 截断 80 字符 — banner 排版美观

### 2.2 `Agent` 接口扩展(Story #020c 加)

```java
package ai.lingshu.core.runtime;

import ai.lingshu.core.event.AgentEvent;
import org.reactivestreams.Publisher;

/**
 * 🆕 Story #020c — add {@link #continueWithUserMessageBlocking(String)} sync wrapper.
 * (剩余 4 方法 #001 + #018 已落,不变)
 */
public interface Agent {

    /** Session backing this agent. */
    Session session();

    /** Immutable config snapshot for the turn(s) this agent will run. */
    AgentConfig config();

    /** Reactive run. */
    Publisher<AgentEvent> run(String userInput);

    /** Sync convenience for {@link #run} — collects to {@link RunResult}. */
    RunResult runBlocking(String userInput);

    /** Inject content as synthetic User message and continue the turn (reactive). */
    Publisher<AgentEvent> continueWithUserMessage(String content);

    /**
     * 🆕 Story #020c — Sync wrapper for {@link #continueWithUserMessage}.
     * <p>For CLI /xxx dispatch (dsh §6.4 L4266-4267): Skill tool result is wrapped as
     * a synthetic User message, fed to this method, and {@link RunResult} returned.
     * <p>Implementation mirrors {@link #runBlocking}: subscribe to the reactive
     * {@code Publisher}, collect events to {@code RunResult} until terminal
     * {@code TurnCompleted}, blocking on {@code CountDownLatch} with
     * {@code turnTimeoutSeconds}.
     */
    RunResult continueWithUserMessageBlocking(String content);
}
```

**`DefaultAgent` 实现**(镜像 `runBlocking` 模式):
```java
@Override
public RunResult continueWithUserMessageBlocking(String content) {
    // 复用 continueWithUserMessage(content) 反应式路径
    // 镜像 runBlocking 的 CountDownLatch + AtomicReference 收集模式
    AtomicReference<RunResult> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    continueWithUserMessage(content).subscribe(new Subscriber<AgentEvent>() {
        @Override public void onSubscribe(Subscription sub) { sub.request(Long.MAX_VALUE); }
        @Override public void onNext(AgentEvent event) {
            // terminal TurnCompleted set result via LinearTurnEngine 内部 finalization
        }
        @Override public void onError(Throwable t) { latch.countDown(); }
        @Override public onComplete() { latch.countDown(); }
    });
    try { latch.await(config().getTurnTimeoutSeconds(), TimeUnit.SECONDS); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    RunResult r = result.get();
    if (r == null) {
        throw new LingsCoreException("LINGS-X99", "turn timeout / no terminal event");
    }
    return r;
}
```

**约束**:
- `Agent` 接口 + 1 方法,**向后兼容** — 已有 `DefaultAgent` 实现被扩展,**无 default**(避免 interface default 污染其他实现类)
- 镜像 `runBlocking` 的 4-piece 模板:`AtomicReference<RunResult>` + `CountDownLatch` + `Subscriber<AgentEvent>` + `latch.await(timeout)`
- 复用 `continueWithUserMessage(content)` 反应式路径,**不**重写 turn 逻辑(避免与 Story #001 / #018 的 `runBlocking` 漂移)

**注意**:这个方法加在 `Agent` interface 是 **接口扩展**,CLAUDE.md §11 #3 "不跨 Story 改宪章" 是指 `constitution.md`,**不**包括演进中的 interface;`Agent` 在 #001 时已留有演进空间(类似 `runBlocking` 是 #001 同步版,`continueWithUserMessageBlocking` 是 #020c 同步版,**模式完全对称**)。

### 2.3 `CliRunner` 修改(对齐 dsh §6.4 L4039 `/xxx` 拦截)

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

    /** Test-only constructor — allows stdout / stderr capture + dispatcher stub. */
    CliRunner(AgentFactory factory, SkillCommandDispatcher skillDispatcher,
              PrintStream out, PrintStream err) {
        this.factory = factory;
        this.skillDispatcher = skillDispatcher;
        this.out = out;
        this.err = err;
    }

    // ── 🆕 Story #020c — doRun modification ───────────────────────────────

    void doRun(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);

        // 🆕 --list-skills: 只打印 skills 不调 Agent(对齐 doctor 输出风格)
        if (args.isPrintSkills()) {
            skillDispatcher.printSkillList(out);
            return;
        }

        Agent agent = factory.create(cfg);

        // 🆕 /xxx 拦截(if --prompt 以 "/" 开头且 xxx 是已注册 Skill)
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

        // 原有 path — 走 LLM(Story #001 + #017 + #019 行为完全不变)
        RunResult result = agent.runBlocking(args.getPrompt());
        out.println(result.getFinalText());
        out.println();
        out.println("[LINGS-Z99] turns=" + result.getTurns()
            + " usage=" + result.getTotalUsage()
            + " stopReason=" + result.getStopReason()
            + " elapsedMs=" + result.getElapsedMillis());
    }

    // ── 🆕 Story #020c — doResume 同 doRun 逻辑 ───────────────────────
    void doResume(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);
        // ... memory session continuation 标识不变 ...
        Agent agent = factory.create(cfg);
        // 🆕 --list-skills 也支持
        if (args.isPrintSkills()) {
            skillDispatcher.printSkillList(out);
            return;
        }
        // 🆕 /xxx 拦截(同 doRun)
        if (skillDispatcher.isSkillCommand(args.getPrompt())) {
            RunResult result = skillDispatcher.handleUserInput(args.getPrompt(), agent);
            out.println(result.getFinalText());
            out.println();
            out.println("[LINGS-Z99] skill-trigger turns=" + result.getTurns() /* ... */);
            return;
        }
        RunResult result = agent.runBlocking(args.getPrompt());
        // ... 原样 ...
    }

    // ── 🆕 Story #020c — doDoctor 末尾追加 skills ───────────────────────
    void doDoctor(Args args) {
        // ... 现有 doctor 输出(对齐 #017) ...
        // 🆕 末尾追加 skills 列表
        out.println();
        skillDispatcher.printSkillList(out);
    }
}
```

**关键约束**:
- `/xxx` 拦截**早于 LLM** — `isSkillCommand` 检查 + `handleUserInput` 直接调 Skill + wrap as User message,**跳过 LLM**,**省 1 个 model call + N 秒延迟**
- `args.isPrintSkills()` 对应 `--list-skills` flag — 单独跑 `lingshu run --list-skills` 只打印 skills 不调 Agent
- `agent.continueWithUserMessageBlocking(...)` 是 `Agent` 新方法(Story #020c 加)— 同步版的 `continueWithUserMessage(content)`,返 `RunResult`
- `doResume` 同样加 `/xxx` 拦截(同 doRun 逻辑)— 语义对齐 doRun
- `doServe` 不动 — A2A server 模式无 CLI prompt,`/xxx` 在 REST 端点另外实现(留未来)
- `doDoctor` 追加 skills banner(对齐 `#017` 既有 doctor 输出风格)
- `dispatch()` switch 不动 — 仅在 RUN / RESUME 分支内做 `/xxx` 拦截

### 2.4 `Args.isPrintSkills` 字段扩展(对齐 Story #020c `--list-skills` flag)

```java
@Value
public class Args {
    Subcommand subcommand;
    Path configPath;
    String prompt;
    String sessionId;
    Integer port;
    boolean printEffective;
    boolean printSchema;
    /** 🆕 Story #020c — `lingshu {run|resume} --list-skills` 仅打印 skills 不调 Agent */
    boolean printSkills;
}
```

**`ArgsParser.parse`** 修改 2 行:
```java
boolean printEffective = flags.containsKey("--print-effective");
boolean printSchema = flags.containsKey("--print-schema");
// 🆕 Story #020c
boolean printSkills = flags.containsKey("--list-skills");

return new Args(sub, configPath, prompt, sessionId, port, printEffective, printSchema, printSkills);
```

**关键约束**:
- `--list-skills` 是 boolean flag(无 value),与 `--print-effective` / `--print-schema` 同模式(对齐 Story #017 `parseFlags` 逻辑)
- `Args` 不可变(@Value)— 在 builder 末尾追加 1 个字段(对齐 Lombok @Value 自动增 8th 字段)

### 2.5 `LingsCliException` 错误码映射(0 新增)

复用现有 LINGS-S05 / LINGS-Z01 / LINGS-T02,不加新 ErrorCode(对齐 dsh §15 域字母):
| 错误码 | 域 | 复用源 | 触发场景 |
|---|---|---|---|
| `LINGS-S05` | S(Slot / SPI) | Story #001 | `handleUserInput` skill 不存在 |
| `LINGS-Z01` | Z(CLI) | Story #017 | `handleUserInput` raw 为空或不以 "/" 开头 |
| `LINGS-T02` | T(Tool) | Story #020a | Skill execute 返 `ToolResult.isError()=true` |

`LingsCliException.exitCodeFor(code)` switch 不动 — S05 / Z01 已有处理,T02 fallback 到 default 1(可接受)。

---

## §3 测试策略(≥ 22 case,L1 + L2 + L3)

### 3.1 L1 单元测试(单类行为 — 无 Spring ctx)

**`SkillCommandDispatcherTest`**(`@Component` Spring 注入 + parse + listSkillNames + listSkills + printSkillList + handleUserInput happy/invalid path):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `isBeanRegistered_` | AC-020c-1 | mini ctx 起 → Bean 已就绪,`getBean(SkillCommandDispatcher.class)` 非 null |
| `parse_withSlashAndArgs` | AC-020c-2 | `parse("/commit fix login")` → `name="commit", args="fix login"` |
| `parse_onlyCommand` | AC-020c-2 | `parse("/commit")` → `name="commit", args=""` |
| `parse_emptyAfterTrim` | AC-020c-2 | `parse("/  ")` → `name="", args=""`(空 → 不抛)|
| `parse_noSlash` | AC-020c-2 | `parse("commit")` → `name="", args=""`(无 `/` → 不抛)|
| `parse_nullSafe` | AC-020c-2 | `parse(null)` → `name="", args=""`(null → 不抛)|
| `parse_onlySlash` | EC-020c-2 | `parse("/")` → `name="", args=""`(parse 不抛)|
| `listSkillNames_sortsAndDedups` | AC-020c-4 | 含 3 Skill → 返 sorted list,空 registry → 空 list |
| `listSkills_returnsNameAndDescription` | AC-020c-5 | 含 3 Skill → 返 3 个 `SkillInfo`(name + description)|
| `printSkillList_emptyRegistry` | AC-020c-6 | stdout 含 "Available commands: (none registered...)"|
| `printSkillList_populatedRegistry` | AC-020c-6 | stdout 含 "[LINGS-Z99] Available commands (N):" + "  /<name>   — <desc>" |
| `printSkillList_sortsByName` | AC-020c-6 | 3 个 Skill 字典序输出 |
| `printSkillList_truncatesLongDescription` | EC-020c-1 | description 100 字符 → 输出 ≤ 80 字符 + "..." |
| `handleUserInput_invalidPrefixThrowsZ01` | AC-020c-9 | `handleUserInput("hello", mockAgent)` 抛 `LingsCliException("LINGS-Z01", ...)` |
| `handleUserInput_emptyThrowsZ01` | AC-020c-9 | `handleUserInput("", mockAgent)` 抛 `LingsCliException("LINGS-Z01", ...)` |

### 3.2 L2 slice 测试(单 Slot + 真实依赖 / mock executor)

**`SkillCommandDispatcherTest`** L2 子组:

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `isSkillCommand_whenRegistered_returnsTrue` | AC-020c-3 | registry 含 "commit" → `isSkillCommand("/commit")` = true |
| `isSkillCommand_whenNotRegistered_returnsFalse` | AC-020c-3 | registry 不含 "xxx" → `isSkillCommand("/xxx")` = false |
| `isSkillCommand_noSlash_returnsFalse` | AC-020c-3 | `isSkillCommand("hello")` = false |
| `isSkillCommand_nullSafe` | AC-020c-3 | `isSkillCommand(null)` = false |
| `isSkillCommand_argsIgnored` | AC-020c-3 | `isSkillCommand("/review anything")` 只看 name,args 不影响 |
| `handleUserInput_happyPath_executesAndContinues` | AC-020c-7 | mock `ToolExecutor` 返 success `ToolResult` + mock `Agent` 返固定 `RunResult` → 调用链完整,`ToolCall.input.input` = args |
| `handleUserInput_unknownThrowsLingsS05` | AC-020c-8 | `handleUserInput("/nonexistent", mockAgent)` 抛 `LingsCliException("LINGS-S05", ...)`,message 含 "Unknown skill command" |
| `handleUserInput_skillReturnsError_throwsLingsT02` | EC-020c-3 | mock `ToolExecutor.dispatch` 返 `ToolResult.isError()=true` → 抛 `LingsCliException("LINGS-T02", ...)` |

**`CliRunnerSkillTriggerTest`**(集成 — real `AgentFactory` + stub `FlowEngine`):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `doRun_withSlashPrompt_dispatchesSkill` | AC-020c-10 | yml 配 CommitSkill + `--prompt "/commit fix bug"` → CliRunner.doRun 走 `skillDispatcher.handleUserInput`,**不**调 `agent.runBlocking`;stdout 含 SkillTool.execute 返的 content |
| `doRun_withRegularPrompt_goesThroughLlm` | AC-020c-10 | `--prompt "hello"` 走原 LLM path(stub FlowEngine 返固定 RunResult) |
| `doRun_withListSkills_dumpsSkillListAndReturns` | — extra | `--list-skills` + 不带 `--prompt` → stdout 含 skill banner,**不** create Agent |
| `doDoctor_appendsSkillListAtEnd` | — extra | `lingshu doctor` stdout 末尾含 "[LINGS-Z99] Available commands (...)" |

### 3.3 L3 集成测试(0 个 — 跨 CLI+core 集成在 `CliRunnerSkillTriggerTest` 覆盖)

### 3.4 测试预算

| 层级 | case 数 | 文件 |
|---|---:|---|
| L1 Unit | 14 | SkillCommandDispatcherTest(L1 子组 14 case)|
| L2 Slice | 8 | SkillCommandDispatcherTest(L2 子组 4)+ CliRunnerSkillTriggerTest(L2 集成 3)|
| L3 Component | 0 | (无 — 集成 E2E 已被 CliRunnerSkillTriggerTest 覆盖)|
| **合计** | **≥ 22** | 2 测试类 |

(spec.md 估算 ~12,plan.md 加 detail 后 22,**实际 ≥ 22**)

---

## §4 实施顺序(T-NN)

参见 `tasks.md`。简述:

1. **T-01** `Agent.continueWithUserMessageBlocking` 接口 + `DefaultAgent` 实现 + 2 单测(镜像 `runBlocking`)
2. **T-02** `SkillCommandDispatcher` 主类(含 `ParsedCommand` / `SkillInfo` 嵌套类)+ 14 L1 单测
3. **T-03** `SkillCommandDispatcher` L2 子组(8 case,引入 mock `ToolExecutor` / mock `Agent`)
4. **T-04** `Args` 加 `printSkills` 字段 + `ArgsParser` 解析 `--list-skills` + 1 单测
5. **T-05** `CliRunner` 修改(doRun / doResume / doDoctor 加 `/xxx` 拦截 + `--list-skills` banner)+ 2 集成测试(`CliRunnerSkillTriggerTest`)
6. **T-06** 全量回归 + commit + push + PR body(含 R-13 dependency:tree 自查)

---

## §5 风险与回滚

| 风险 | 概率 | 缓解 |
|---|---|---|
| `Agent.continueWithUserMessageBlocking` 接口扩展破坏其他 Agent 实现 | **0** | lingshu-core 只有 `DefaultAgent` 一个 Agent 实现,扩展即生效 |
| `CliRunner.doRun` 加 `/xxx` 拦截导致原有 `lingshu run --prompt "..."` 测试回归 | **极低** | `isSkillCommand` 只有 raw 以 "/" 开头且 name 在 registry 才 true;原 `"hello world"` 测试不受影响 |
| `printSkillList` 用 `[LINGS-Z99]` 前缀被误读为 ErrorCode | **极低** | LINGS-Z99 = generic info code,Story #017 已用同样模式(`out.println("[LINGS-Z99] ...")`,无歧义)|
| `LingsCliException.exitCodeFor` switch 没覆盖 T02 → 默认 exit code 1 | 中 | T02 是 Skill execute 错,fallback exit 1 可接受(S05 / Z01 不需 T02 单独映射);后续 Story 可加 |
| `ObjectMapper` 注入 vs `new ObjectMapper()` 性能开销 | **0** | ObjectMapper 创建一次性,只在 `handleUserInput` 路径(单次调用)|
| 用户用 `lingshu run --prompt "/xxx"` 触发 Skill 但 agent 已跑过一次 turn → `continueWithUserMessageBlocking` 语义混淆 | 低 | Story #020c 严格意义"首次触发"用 `continueWithUserMessageBlocking`,`doRun` 是新 Agent 单 turn,**语义清晰** |
| `toolDispatcher.handleUserInput` 5 步流水线 bypass(直调 `skill.execute`)风险 | **0** | plan §2.1 强约束 + Javadoc 显式禁止 + 单元测试 mock ToolExecutor 验证调用 |
| R-13 mitigation (d): 引入新 Maven 坐标 | **0 风险** | `ObjectMapper` 已是 jackson-databind 已锁(`ToolCall.builder().input(node)` 在 #020a 已使用),`CounterLatch` / `AtomicReference` 已是 JDK 8 内置;**0 新依赖** |

**回滚方案**:`git revert <merge-commit>` + 删除 `SkillCommandDispatcher.java` + `Agent` 移除 `continueWithUserMessageBlocking` + `DefaultAgent` 移除实现 + `CliRunner` 回滚到 #017 版本 + `Args` / `ArgsParser` 移除 `printSkills` 字段 / 解析。`Skill` / `SkillTool` / `CommitSkill` / `ToolRegistry` / `ToolExecutor` 全部不动 → 不影响 #020a / #020b 测试。

---

## §6 关键不变项(对照 Story #020a / #020b 模板)

- `Skill` interface 不变(Story #020a / #020b 不变项)— 仍为 marker,零方法
- `SkillTool` 不变 — 完全复用 #020a `fromMarkdown` + `FIXED_INPUT_SCHEMA_JSON`
- `CommitSkill` 不变 — 完全复用 #020a
- `SkillSource` / `SkillSourceProvider` / `ClasspathSkillSource` / `DirectorySkillSource` 不变 — 完全复用 #020b
- `CompositeSkillLoader` / `SkillSourceRouter` / `SkillSourceProperties` / `SkillAutoConfiguration` 不变
- `ToolRegistry` interface 不变(Story #020a 4 方法 — `modelVisibleSpecs / findSkill / skillNames / findByName` — 本 Story 全部复用,**0 新增方法**)
- `DefaultToolRegistry` 不变 — 仍是 putIfAbsent 双索引(`registry` + `skillsByName`)
- `Tool` / `ToolCall` / `ToolResult` / `ToolSpec` 不变
- `ToolExecutor` 5 步流水线不变(**核心不变项** — `SkillCommandDispatcher.handleUserInput` 走 dispatch,**不**直调 `skill.execute`)
- `LingsCliException` 不变 — 复用现有 LINGS-S05 / Z01 / T02,**0 新 ErrorCode**
- `datasource` / `LinearTurnEngine` / `PromptBuilder` 全部不变
- `RuntimeSandbox` / `ProcessRunner` 不变
- 9 Slot SPI 全部不变(本 Story 不新增 Slot,也不修改任何 Slot 接口)
- dsh §10.1 锁定 13 项依赖,**0 增量**
- §4.10.1 硬规则 1/2/3 不涉及(本 Story 不调 Spring AI / 走 dispatch)
- `CliRunner` 行为**扩展**,不破坏 — 原 `doRun` / `doDoctor` / `doConfig` / `doServe` / `doResume` 主干完全不变,仅在 `doRun` / `doResume` 前置加 `/xxx` 拦截 + `--list-skills` banner;Story #017 / #019 既有测试不受影响

---

**Last updated**: 2026-09-23
**Plan author**: Claude Code
**Reviewer**: 待 PR review
