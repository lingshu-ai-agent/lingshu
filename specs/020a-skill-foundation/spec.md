# Story #020a `skill-foundation` — Spec

> **Status**: Draft 2026-09-23
> **Source**: dsh v1.5.37 §6.4 `Skill —— Tool 的约定性 marker`(L3970-4420) + §4.6 `Tool` / `ToolRegistry` 契约(L530-560)+ §4.7 §4.10.1 5 步 dispatch pipeline(L688-697)
> **Closes gap**: `Skill` interface 自 Story #003 已落地为 marker(`ai.lingshu.core.slot.Skill extends Tool`),但 **5 件全缺**:
> (1) `SkillTool` 实现类 — 没有从 SKILL.md 文本构造 Skill 的工厂;
> (2) `fromMarkdown(name, content)` 静态工厂 — 文档引用 2 处(L3587 / L3636)但实现缺;
> (3) `@Component implements Skill` 内置示例(`CommitSkill`)— 缺硬编码 Skill 路径样板;
> (4) `ToolRegistry` 第 2 张索引(`skillsByName`)— 当前 `lookup()` 返普通 Tool,不能区分 Skill;
> (5) `ToolRegistry.modelVisibleSpecs() / findSkill() / skillNames() / findByName()` 4 个方法 — 文档定义但接口未实现。
> 后果:Agent 启动后 `ToolRegistry.findSkill(name)` 永远返 null,CLI `/xxx` 无法触发,PromptBuilder 装配 `Skill` schema 也没入口。

---

## WHY

Story #019 `built-in-tools` 把 4 个内置 `Tool`(`Read` / `Write` / `Edit` / `Bash`)落盘后,引擎侧的 `ToolRegistry` **仍然把 Skill 和 Tool 一视同仁** —— 没有第 2 张 `skillsByName` 索引,没有 `modelVisibleSpecs()` 区分 Skills,没有 `findSkill()` 给 CLI `/xxx` 拦截。这造成 4 个连锁问题:

1. **dsh §6.4 文档契约是 single-source-of-truth,但代码侧缺实现** — `findSkill(name)` 在 dsh L3998 已定义,但 `ToolRegistry.java` 当前接口只有 `register / lookup / names` 3 方法,**Skill 标记形同虚设**(`SkillTool fromMarkdown` 在 dsh L3587 / L3636 各引用 1 次但无 class 定义)
2. **#020b `#020c` 必须等 #020a** — `#020b skill-source-discovery` 要写 `ClasspathSkillSource` + `DirectorySkillSource` + `CompositeSkillLoader`,后者 `discover()` 返回 `List<Skill>`,必须调 `SkillTool.fromMarkdown(name, content)` 把 SKILL.md 转成 Skill;**没有 SkillTool,源发现层毫无意义**。`#020c cli-skill-trigger` 要在 CLI 拦截 `/xxx` 后调 `toolRegistry.findSkill(skillName)`,**没有 findSkill,CLI 拦截无解析目标**
3. **PromptBuilder 装配 Skill schema 缺入口** — dsh §4.5.1 `[TOOL SCHEMAS]` 段要把所有 Tool 的 schema 喂给 LLM,Skill 与 Tool 共享 `inputSchema()` 契约,PromptBuilder 调 `toolRegistry.modelVisibleSpecs()` 拿全部 `ToolSpec`(含 Skill);**当前接口缺,PromptBuilder 重构时会再次卡住**
4. **`@Component implements Skill` 硬编码路径无对照示例** — 用户写自定义 Skill 时(`GrepSkill` / `LintSkill` / `DocSkill`),找不到 `CommitSkill` 这种"硬编码 Skill"样板,只能照 dsh §6.4 L4381-4409 的伪代码自己实现,**没有 concrete class 锚点**

**Story #020a 目标**:落地 `SkillTool` + `fromMarkdown` 静态工厂 + `@Component CommitSkill` 硬编码 Skill + `ToolRegistry` 4 个新方法(`modelVisibleSpecs / findSkill / skillNames / findByName`),为 #020b 源发现层 / #020c CLI 触发层铺平接口,**Slot 4 Skill 体系第一块砖**。

**业务价值**:
- dsh §0.4 AC-01(ReAct 跑通)在 Story #019 通了实 Tool,但 **Skill = 0** —— 企业用户连 `/help` / `/commit` 这种内置命令都没有
- dsh §1 决策 5 "Skill 与 Tool 共用接口,运行时行为完全一致;Skill 既能被模型自动调用,也能被用户通过 /xxx 显式触发" **当前是 half-done**(接口有但实现 0)
- Story #020a 是 #020b / #020c 的硬前置,**不开 #020a 主链就完全卡住**

---

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类)| 在 IDE 里能看到 `@Component public class CommitSkill implements Skill` 这个完整样板,作为写自定义 Skill 的起点 |
| **业务配置方**(Diana 类)| 不写代码,但想用 `/commit` / `/review` 这种命令 — 至少 `/commit`(`CommitSkill`)Story #020a 落地后能跑 |
| **CLI 用户**(Eve 类)| `mvn exec:java` 启动 CLI,输入 `/commit` 调 `CommitSkill.execute` → 拿到 Conventional Commits 风格 commit message 模板 — 这是 #020c 的事,但 #020a 必须先把 Skill 体系铺好 |
| **CI 工程师**(Charlie 类)| L1/L2 测试 case ≥ 10,`mvn -pl lingshu-core test` 0 fail;`mvn dependency:tree` 0 新增 |
| **框架贡献者**(plugin 作者)| 写 `@Component public class MyDomainSkill implements Skill` 时,直接抄 `CommitSkill` 样板,不需看 dsh 散文 |

---

## WHAT

Story #020a 落地 4 件新接口 / 类 + 1 件新 AutoConfiguration + 1 个接口增强:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `SkillTool` | `class implements Skill` | `lingshu-core/.../impl/skill/SkillTool.java` | ~80 |
| `CommitSkill` | `@Component implements Skill` | `lingshu-core/.../impl/skill/CommitSkill.java` | ~50 |
| `ToolRegistry` | interface +4 方法 | `lingshu-core/.../slot/ToolRegistry.java`(修改) | +30 |
| `DefaultToolRegistry` | impl +4 方法 | `lingshu-core/.../impl/tool/DefaultToolRegistry.java`(修改) | +50 |
| `SkillAutoConfiguration` | `@Configuration implements InitializingBean` | `lingshu-core/.../impl/skill/SkillAutoConfiguration.java` | ~60 |
| 测试 | L1 + L2 + L3 集成 | `lingshu-core/src/test/java/.../skill/` | ~350 行 / ≥10 case |

**5 核心文件 = 4 新建 + 1 修改 + 1 新建 AC**(总数 6 文件,核心文件 5),0 ErrorCode,`mvn dependency:tree` 0 增量。

### 1. `SkillTool` 行为契约(对齐 dsh §6.4 L4279-4329)

```java
public class SkillTool implements Skill {
    private final String name;
    private final String description;
    private final String content;       // SKILL.md 去掉第一行后的正文
    private final JsonNode inputSchema; // 固定 { "input": string }

    public SkillTool(String name, String description, String content, String jsonSchema) {
        // ...
        this.inputSchema = new ObjectMapper().readTree(jsonSchema);
    }
    // name() / description() / inputSchema() 三 getter 直接返 final 字段

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        String userInput = (input != null && input.hasNonNull("input"))
            ? input.get("input").asText() : "";
        String body = content + (userInput.isEmpty() ? "" : "\n\nUser input:\n" + userInput);
        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId(call.getId())
            .content(body)
            .isError(false)
            .build();
    }

    public static Skill fromMarkdown(String name, String markdownContent) {
        // SKILL.md 第一行 "# title" 提取为 description
        // 剩余正文作为 content
        // inputSchema 固定 { "input": string }
    }
}
```

**关键约束**(对齐 dsh L4311-4327):
- `fromMarkdown(name, content)` 第 1 步:`content.split("\\R", 2)` 取首行
- 首行去 leading `#+\\s*`(`#` / `##` / `###` 都行)+ `trim()` → `description`
- 首行若 trim 后为空(`#` 或 `#   `)→ fallback `description = name`
- 剩余正文(`length > 1 ? lines[1].trim() : ""`) → `content`
- `inputSchema` 固定字符串,被 `SkillTool` ctor 解析成 `JsonNode`
- **不**用 Lombok `@Value` —— 因为 ctor 内要做 schema 解析(IO 异常需要包成 `IllegalStateException`),不符合 Lombok `@Value` 不可变简洁 ctor 模式

### 2. `CommitSkill` 行为契约(对齐 dsh §6.4 L4380-4409)

```java
@Component("commitSkill")
public class CommitSkill implements Skill {
    @Override public String name()        { return "commit"; }
    @Override public String description() { return "按 Conventional Commits 风格生成 commit message"; }

    @Override
    public JsonNode inputSchema() {
        // 与 SkillTool.fromMarkdown 一致 —— { "input": string }
        // 走 ObjectMapper 直接构造,避免对 JsonSchema helper 的依赖
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        // 取 input.input 字段(staged diff 或空)
        // 拼"按 Conventional Commits 风格生成 commit message:..."提示正文
        // 返 ToolResult.success(call.getId(), body)
    }
}
```

**关键约束**:
- `name() = "commit"` — 与 `SkillTool` 路径(从 `skills/commit/SKILL.md` 来)同名 → 默认配置下 `CompositeSkillLoader.putIfAbsent` **先发现者优先**(per dsh L4378),CommitSkill 因为是 `@Component` 启动期先注册,**胜出**
- 用户若想 SKILL.md 覆盖 CommitSkill → 在 yml 把 skills source 排到更前,或者给 CommitSkill 加 `@ConditionalOnMissingBean(Skill.class)`(本 Story 不加,留给用户自己选择)
- `inputSchema` 与 `SkillTool.fromMarkdown` 完全一致 —— 同一 Skill 名应有一致 schema,否则 CLI `/xxx <arg>` 与模型 FunctionCalling 走的契约不同会出 bug
- 选 `@Component("commitSkill")` Bean 名(**不是** "commit")—— 避免与未来 SkillTool 路径(也是 `"commit"`)Bean 名冲突(`@Component` Bean 名 = 容器内 ID,Tool name = LLM/CLI 看到的标识,两者独立)

### 3. `ToolRegistry` 接口增强(对齐 dsh §6.4 L3973-4010)

新增 4 方法,**不删**现有 `register / lookup / names`(向后兼容):

```java
public interface ToolRegistry {
    // 已有 3 方法(Story #003 / #019 落):
    void register(Tool tool);
    Tool lookup(String name);
    Collection<String> names();

    // 新增 4 方法(Story #020a 落):
    /** 给 PromptBuilder:所有 Tool 都暴露 schema,Skill 也包含 —— 模型可自动调 */
    List<ToolSpec> modelVisibleSpecs();

    /** 给 CLI:用户 /xxx 时查这里(也用于命令行补全 / 错误提示) */
    Skill findSkill(String name);

    /** 给 CLI:列出所有可用的 /xxx 命令 */
    Set<String> skillNames();

    /**
     * 给 ToolDispatcher:模型 / 用户触发的 tool call 都查这里。
     * 与 lookup() 的差别:lookup 返 null(findSkill 内部走 null 检查),
     *                  findByName 抛 IllegalArgumentException(findSkill 的强契约版)。
     */
    Tool findByName(String name);
}
```

**关键约束**:
- `modelVisibleSpecs()` 内部走 `allTools`(不只是 Skill)—— Skill 与 Tool 共 schema 暴露给模型(对齐 dsh L3985-3988 Javadoc)
- `findSkill(name)` 返 null(让调用方做"找不到 vs 找到"分支,典型 CLI 错误提示用法)
- `findByName(name)` 找不到时抛 `IllegalArgumentException("Unknown tool: <name>")`(对齐 dsh L4007-4008),区别于 `lookup(name)` 的 null 语义 — `lookup` 给 `ToolExecutor.dispatch` 走(它内部 `null → ToolNotFoundException`),`findByName` 给未来 dispatcher 强契约层走
- `skillNames()` 返 `Set<String>`(dsh L4001),与 `names()` 返 `Collection<String>` 不冲突 — 两者服务不同调用方

### 4. `DefaultToolRegistry` 实现增强(对齐 dsh §6.4 L3974-4010)

```java
@Component
public class DefaultToolRegistry implements ToolRegistry {

    private final Map<String, Tool> registry = new ConcurrentHashMap<>();
    // 🆕 Story #020a — Skill 索引(双索引结构)
    private final Map<String, Skill> skillsByName = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        // 已有 putIfAbsent + duplicate-warn 逻辑保持不变
        // 🆕 若 tool instanceof Skill,同步塞进 skillsByName
        if (tool instanceof Skill) {
            Skill priorSkill = skillsByName.putIfAbsent(tool.name(), (Skill) tool);
            if (priorSkill != null && priorSkill != tool) {
                LOG.warn("Duplicate skill registration: name={} prior={} new={}",
                    tool.name(), priorSkill.getClass().getSimpleName(), tool.getClass().getSimpleName());
            }
        }
    }

    // 🆕 4 个新方法
    @Override public List<ToolSpec> modelVisibleSpecs() {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool t : registry.values()) {
            specs.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        // 按 name 字典序排序 —— 稳定输出,便于 PromptBuilder prompt cache 命中
        Collections.sort(specs, new Comparator<ToolSpec>() {
            @Override public int compare(ToolSpec a, ToolSpec b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return specs;
    }

    @Override public Skill findSkill(String name) { return skillsByName.get(name); }
    @Override public Set<String> skillNames() { return Collections.unmodifiableSet(skillsByName.keySet()); }

    @Override public Tool findByName(String name) {
        Tool t = registry.get(name);
        if (t == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return t;
    }

    // 已有 asMap() 不变 + asSkillsMap() 测试用访问器(可选,本 Story 暂不加)
}
```

**关键约束**:
- `skillsByName` 用 `ConcurrentHashMap`(不是 `HashMap`)— `LocalToolsAutoConfiguration` 与 `SkillAutoConfiguration` 同时 register 时线程安全
- `modelVisibleSpecs()` 排序字典序 —— 对齐 #009d `RemoteAgentSchemaBuilder` 的"稳定排序 → prompt cache 命中"设计哲学
- 已有 `register(Tool)` 行为**不**变(向后兼容 #001 / #019 测试) — 仅在 tool instanceof Skill 时多塞 1 张表
- `findByName` 抛 `IllegalArgumentException` 是契约级错误,**不**继承 `ToolException`(ToolException 是 §4.10.1 dispatch 流水线内部用,findByName 是 registry API 层)

### 5. `SkillAutoConfiguration` 注册样板(对齐 dsh §5.4 plugin 编写约定)

```java
@Configuration
public class SkillAutoConfiguration implements InitializingBean {

    public static final String PROP_ENABLED = "agent.skills.enabled";

    private final ToolRegistry toolRegistry;
    private final Map<String, Skill> skills;   // 所有 @Component Skill bean
    private final Environment environment;

    public SkillAutoConfiguration(
            ToolRegistry toolRegistry,
            @Lazy Map<String, Skill> skills,
            Environment environment) { /* ... */ }

    @Override
    public void afterPropertiesSet() {
        boolean enabled = environment.getProperty(PROP_ENABLED, Boolean.class, Boolean.TRUE);
        if (!enabled) {
            LOG.info("Skills disabled via {}={} — Skills constructed but NOT registered",
                PROP_ENABLED, enabled);
            return;
        }
        int registered = 0;
        for (Skill skill : skills.values()) {
            toolRegistry.register(skill);  // register 内部 instanceof Skill 分流
            registered++;
            LOG.debug("Registered skill: beanName={} skillName={} class={}",
                /* beanName from key */, skill.name(), skill.getClass().getSimpleName());
        }
        LOG.info("Skills ready — {} skill(s) registered: {}",
            registered, skillNames());
    }
}
```

**关键约束**:
- 复用 #019 `LocalToolsAutoConfiguration` 的 `@Configuration + InitializingBean + Environment` 模板(避免 `javax.annotation.PostConstruct` 依赖,见 #019 L51-59 rationale)
- `Map<String, Skill>` `@Lazy` 注入 —— 同样解决 `SkillAutoConfiguration ↔ Skill @Component` 的循环依赖(对齐 #019 L113-123 rationale)
- `agent.skills.enabled` 默认 true(对齐 `agent.tools.enabled` 默认值),空 yml 自动启用
- **不**调 `setProcessRunner` 之类的 Skill-specific wiring —— Skill 无外部依赖(对比 `BashTool` 需要 `RuntimeSandbox.ProcessRunner`),纯注册即可

---

## 反向 AC(明确不做)

- ❌ **`SkillSource` / `SkillSourceProvider` / `ClasspathSkillSource` / `DirectorySkillSource`** —— 留 #020b skill-source-discovery(超出本 Story 5 核心文件边界)
- ❌ **`CompositeSkillLoader` + `SkillSourceRouter`** —— 留 #020b
- ❌ **`/xxx` CLI 拦截 + `handleUserInput`** —— 留 #020c cli-skill-trigger
- ❌ **`Skill` interface 新方法**(用户别名 / 权限标记 / 危险等级)—— dsh §6.4 L4030-4033 标记"未来扩展",本 Story 仍为 marker interface(零方法)
- ❌ **`Skill` interface 上的 `@ContractVersionRef CONTRACT_VERSION`** — Story #003 已加(现值 "1.0.0"),本 Story 不改
- ❌ **`ToolResult.success(call.getId(), body)` 静态工厂** —— 当前 `ToolResult` 只有 `@Builder`,所有调用点走 `ToolResult.builder().status(SUCCESS).toolUseId(id).content(body).isError(false).build()`,**对齐已有 4 内置 Tool 模式**,不改 `ToolResult` 接口(避免破坏 Story #001 / #019 测试)
- ❌ **`LocalToolsAutoConfiguration` 改造吞 Skills** —— Skill 注册走独立 `SkillAutoConfiguration`(避免 LocalToolsAutoConfiguration 越界承担 Skill 注册责任,违反 SRP)
- ❌ **`@Component Skill` Bean 名 = `"commit"`(不带后缀)** —— 选 `"commitSkill"`(带后缀)避免未来 SKILL.md 路径同名 Bean 冲突
- ❌ **Skill `name()` 与 Bean 名同名校验** —— `Skill` 接口不强制 name == beanName(Bean 名 = 容器 ID,Tool/Skill name = LLM/CLI 标识,两者解耦)
- ❌ **Skill hot-reload(directory 监听)** —— 留 #020b (`DirectorySkillSource.watchable() = true` 配套)
- ❌ **PromptBuilder 集成 `modelVisibleSpecs()`** —— 留 OQ-5(§5.6.3.0 ROADMAP OQ-Future)

---

## AC 编号(Story #020a 新增,不入 dsh §0.4)

| AC | 描述 | 验证 |
|---|---|---|
| **AC-020a-1** | `SkillTool` 4 getter 直返字段;`SkillTool.execute(call, ctx)` 取 `call.getInput().get("input").asText()`,空时退化为 content 原值,非空时拼 `\n\nUser input:\n<input>`,返 `ToolResult.success(...)` | `SkillToolTest.execute_userInputEmpty_returnsContent` + `execute_userInputProvided_appendsUserInput`(L1 2 case)|
| **AC-020a-2** | `SkillTool.fromMarkdown(name, "# Title\nbody content")` 构造 Skill,`name()=name`,`description()="Title"`(去掉 leading `#` + trim),`execute(null, ctx)` 返 `body content`;首行无 `#` 也 OK,首行为空 fallback `description=name` | `SkillToolTest.fromMarkdown_parsesTitle` + `fromMarkdown_firstLineFallbackToName` + `fromMarkdown_bodyEmpty`(L1 3 case)|
| **AC-020a-3** | `SkillTool.fromMarkdown(name, "## Sub Title\nbody")` 构造 Skill,`description()="Sub Title"`(`##` 也被去);`#  Title` 含多空格 trim 后 `"Title"` | `SkillToolTest.fromMarkdown_hashLevels` + `fromMarkdown_trimsWhitespace`(L1 2 case)|
| **AC-020a-4** | `SkillTool.inputSchema()` 永远是 `{ "type": "object", "properties": { "input": { "type": "string" } }, "required": ["input"] }` 解析后的 `JsonNode`,每次调用返新对象(避免共享 mutable state) | `SkillToolTest.inputSchema_alwaysReturnsFixedShape`(L1 1 case)|
| **AC-020a-5** | `CommitSkill implements Skill` Spring 启动后是 Bean,`name()="commit"`,`description()` 含 "Conventional Commits";`execute(call, ctx)` 拼 Conventional Commits 模板(feat / fix / docs / refactor / test / chore + ≤ 50 字符 subject 提示 + 72 字符 body 换行)| `CommitSkillTest.nameDescription_returnsExpected` + `execute_emptyInput_returnsTemplateOnly` + `execute_withDiffInput_appendsStagedDiffBlock`(L1 3 case)|
| **AC-020a-6** | `ToolRegistry` 接口新增 4 方法 `modelVisibleSpecs / findSkill / skillNames / findByName`,编译期可见(签名 + Javadoc 完整) | `ToolRegistryContractTest.interfaceHasFourNewMethods`(L1 1 case,反射验证 method 存在 + return type)|
| **AC-020a-7** | `DefaultToolRegistry` 注册 `CommitSkill` 后,`toolRegistry.findSkill("commit")` 返 `CommitSkill` 实例;`toolRegistry.skillNames()` 包含 `"commit"`;`toolRegistry.findByName("commit")` 返同一实例;`toolRegistry.lookup("commit")` 也返同一实例(向后兼容老 API) | `DefaultToolRegistrySkillTest.registerCommitSkill_populatesSkillsIndex`(L2 1 case)|
| **AC-020a-8** | `DefaultToolRegistry` 注册 1 个普通 `Tool`(非 Skill)+ 1 个 `Skill` 后,`toolRegistry.modelVisibleSpecs()` 返 `List<ToolSpec>`,**size = 2**(Skill + Tool 都包含),按 name 字典序排 | `DefaultToolRegistrySkillTest.modelVisibleSpecs_containsBothSkillAndTool_sortedByName`(L2 1 case)|
| **AC-020a-9** | `DefaultToolRegistry.findSkill("nonexistent")` 返 `null`(不抛异常);`findByName("nonexistent")` 抛 `IllegalArgumentException("Unknown tool: nonexistent")` | `DefaultToolRegistrySkillTest.findSkill_unknownReturnsNull` + `findByName_unknownThrowsIAE`(L2 2 case)|
| **AC-020a-10** | `DefaultToolRegistry.register(Skill)` 与 `register(Tool)` 在并发线程下线程安全(`ConcurrentHashMap` 保证),200 个 Skill + 200 个 Tool 并发注册,**最终 `registry.size() = 400`,`skillsByName.size() = 200`,无 duplicate-warn 日志**(因为每个 name 唯一)| `DefaultToolRegistryConcurrencyTest.concurrentRegisterIsThreadSafe`(L2 1 case,JUnit 5 + `CountDownLatch` 启动 16 线程并发注册)|
| **AC-020a-11** | `SkillAutoConfiguration` Spring 启动后,`DefaultToolRegistry.skillsByName` 含 `CommitSkill`(key=`"commit"`);`agent.skills.enabled=false` yml 启动 → skillsByName 为空(Skills 仍构造但未注册) | `SkillAutoConfigurationTest.enabledByDefault_registersCommitSkill` + `disabled_doesNotRegister`(L2 slice 2 case,起 mini `ApplicationContext`)|
| **AC-020a-12** | 集成 E2E:`ToolRegistry.modelVisibleSpecs()` 装配出来的 `ToolSpec` 喂给一个 mock `LlmProvider`,mock 校验接收到的 spec 含 `"commit"` Skill 的 schema(`{ "input": string }`)+ 4 个内置 Tool(Read/Write/Edit/Bash)schema | `SkillRegistryE2ETest.modelVisibleSpecs_feedsAllToolsAndSkillsToPromptBuilder`(L3 集成)|

**EC(边界 case)**:

| EC | 描述 | 验证 |
|---|---|---|
| **EC-020a-1** | `SkillTool.execute(call, ctx)` `call.getInput()` 为 null 或缺 `input` 字段 → 走空字符串路径,等同"无 user input" | `SkillToolTest.execute_nullInput_returnsContentOnly` + `execute_inputFieldMissing_returnsContentOnly`(L1 2 case)|
| **EC-020a-2** | `SkillTool.fromMarkdown(name, "")` 空字符串 → `description()=name`(空 fallback)+ `content()=""`;`fromMarkdown(name, "#\nbody")` 首行只有 `#` → `description()=name` | `SkillToolTest.fromMarkdown_emptyMarkdown` + `fromMarkdown_firstLineHashOnly`(L1 2 case)|
| **EC-020a-3** | `CommitSkill` 与 `SkillTool` 同名(`"commit"`)同时存在 → `register` 顺序决定胜出(`putIfAbsent` 先注册者优先);`@Component` `SkillAutoConfiguration` 在 `LocalToolsAutoConfiguration` 之后启动 → CommitSkill 胜出 | `CommitSkillVsSkillToolTest.coexistence_firstRegisterWins`(L2 1 case)|
| **EC-020a-4** | `SkillTool` 不是 `@Component`(普通 `new` 构造),可以被外部代码直接 new 后调 `toolRegistry.register(skillTool)` —— 注册路径与 `@Component` 一致 | `SkillAutoConfigurationTest.manualNewSkillTool_canBeRegistered`(L2 1 case)|

---

## ErrorCode 引入(0 条)

| 码 | 域 | 触发场景 |
|---|---|---|
| — | — | **0 新 ErrorCode**(`findByName` 抛 `IllegalArgumentException` 不需 ErrorCode — 是契约层 API 错误,非业务异常;对齐 dsh L4007-4008)|

**约束 Story 边界**(CLAUDE.md §11 #4 ≤ 5 核心文件,≤ 3 ErrorCode):4 新建 + 1 修改 + 1 新建 = **6 文件**,其中 5 个核心文件(SkillTool + CommitSkill + SkillAutoConfiguration + ToolRegistry 修改 + DefaultToolRegistry 修改,测试另计),0 ErrorCode 远 ≤ 3。**Story 边界满足**(略超 5 上限 1 个测试文件,无伤大雅)。

---

## 出口标准(DoD)

- [ ] `specs/020a-skill-foundation/{spec,plan,tasks}.md` 三件套合入主分支
- [ ] 5 个生产核心文件 + 1 个测试目录 = 6 文件落地
- [ ] ≥ 12 测试 case 全过(`mvn -pl lingshu-core test`),其中 ≥ 4 个 L2(L2 slice + 1 L3 集成)
- [ ] R-13 `mvn dependency:tree` 自查:**0 新 Maven coordinates**(`mvn -pl lingshu-core dependency:tree -DincludeScope=runtime` pre/post diff 仅时间戳)
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节
- [ ] dsh v1.5.38 单独 PR 同步(沿用 Story #018 / #019 模式:`v1.5.37 → v1.5.38` + §13 changelog 加 Story #020a 行)
- [ ] ROADMAP.md 段一「✅ 已完成」表追加 `#020a skill-foundation`
- [ ] CLAUDE.md 版本号同步(`1.3.31 → 1.3.32`,沿用 `v1.3.x` 与 dsh `v1.5.x` 配套)

---

## 不在 Story #020a 范围(显式 deferred)

- ❌ `SkillSource` / `SkillSourceProvider` / `ClasspathSkillSource` / `DirectorySkillSource`(留 #020b)
- ❌ `CompositeSkillLoader` + `SkillSourceRouter`(留 #020b)
- ❌ `/xxx` CLI 拦截 + `handleUserInput` + 自动补全 + 启动日志 dump skills(留 #020c)
- ❌ `Skill` interface 新方法(留 Skill v1.1 RFC)
- ❌ PromptBuilder `[TOOL SCHEMAS]` 集成 `modelVisibleSpecs()`(留 OQ-5)
- ❌ Skill hot-reload(directory 监听)(留 #020b 配套 / Story #007 N8 yaml-hot-reload)
- ❌ `Skill` 危险等级 → §4.7 审批门联动(留 Skill v1.1 RFC)
- ❌ 多 Skill 同名合并策略文档化(留 #020b CompositeSkillLoader Javadoc 详写)

---

**Last updated**: 2026-09-23
**Spec author**: Claude Code (per user 2026-09-23 conversation)
**Reviewer**: 待 PR review
