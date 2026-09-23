# Story #020a `skill-foundation` — Plan

> **Status**: Draft 2026-09-23
> **Implements**: `specs/020a-skill-foundation/spec.md`
> **Source**: dsh v1.5.37 §6.4 L3970-4420(Skill 完整设计)
> **Pre-req**: Story #019 ✅ built-in-tools(4 内置 Tool 已落 + `LocalToolsAutoConfiguration` 模板可复用)

---

## §1 范围与非范围

### In-Scope(5 核心文件 + 1 测试目录)

| 文件 | 行为 |
|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillTool.java`(新)| Skill 默认实现 + `fromMarkdown` 静态工厂 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/CommitSkill.java`(新)| `@Component` 硬编码 Skill 示例 |
| `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java`(修改)| 接口 +4 方法 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolRegistry.java`(修改)| 实现 4 方法 + Skill 索引 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillAutoConfiguration.java`(新)| 注册所有 `@Component Skill` Bean |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/skill/`(新)| ≥12 测试 case(L1 + L2 + L3)|

### Out-of-Scope(显式 deferred)

- `SkillSource` / `SkillSourceProvider` / `ClasspathSkillSource` / `DirectorySkillSource` → #020b
- `CompositeSkillLoader` + `SkillSourceRouter` → #020b
- `/xxx` CLI 拦截 + `handleUserInput` → #020c
- `Skill` interface 新方法(用户别名 / 权限标记 / 危险等级)→ Skill v1.1 RFC

---

## §2 接口契约锚点(dsh §6.4 L3970-4010)

### 2.1 `Skill` interface(已存在,本 Story 不改)

```java
package ai.lingshu.core.slot;
public interface Skill extends Tool {
    @ContractVersionRef String CONTRACT_VERSION = "1.0.0";
}
```

**当前状态**:已存在 `ai/lingshu/core/slot/Skill.java`,Javadoc 已写明 4 点差异 + 3 条未来扩展空间,**与 dsh §6.4 L4034 完全一致**。本 Story 不动此文件。

### 2.2 `SkillTool` 行为契约(dsh §6.4 L4279-4329)

**字段**(4 个 final):
- `String name` — 构造时传入
- `String description` — 构造时传入(由 `fromMarkdown` 从首行提取)
- `String content` — 构造时传入(由 `fromMarkdown` 从剩余正文提取)
- `JsonNode inputSchema` — 构造时从 `jsonSchema` 字符串解析

**构造器**:
```java
public SkillTool(String name, String description, String content, String jsonSchema) {
    this.name = Objects.requireNonNull(name, "name");
    this.description = description == null ? name : description;
    this.content = content == null ? "" : content;
    try {
        this.inputSchema = new ObjectMapper().readTree(jsonSchema);
    } catch (IOException e) {
        throw new IllegalStateException("Invalid schema for skill " + name, e);
    }
}
```

**4 个 getter**:`name() / description() / inputSchema()` 直返 final;`execute(call, ctx)` 见 spec §1.1。

**`fromMarkdown(name, content)` 静态工厂**:
```java
public static Skill fromMarkdown(String name, String markdownContent) {
    Objects.requireNonNull(name, "name");
    String body = markdownContent == null ? "" : markdownContent;
    String[] lines = body.split("\\R", 2);
    String first = lines[0].replaceFirst("^#+\\s*", "").trim();
    String description = first.isEmpty() ? name : first;
    String remaining = lines.length > 1 ? lines[1].trim() : "";
    String schema = "{ \"type\": \"object\", \"properties\": { \"input\": { \"type\": \"string\" } }, \"required\": [\"input\"] }";
    return new SkillTool(name, description, remaining, schema);
}
```

### 2.3 `CommitSkill` 行为契约(dsh §6.4 L4380-4409)

```java
@Component("commitSkill")
public class CommitSkill implements Skill {
    @Override public String name()        { return "commit"; }
    @Override public String description() { return "按 Conventional Commits 风格生成 commit message"; }

    private static final String INPUT_SCHEMA_JSON =
        "{ \"type\": \"object\", \"properties\": { \"input\": { \"type\": \"string\" } }, \"required\": [\"input\"] }";
    private final JsonNode inputSchema;   // ctor 解析,避免每次调用都 parse

    public CommitSkill() {
        try {
            this.inputSchema = new ObjectMapper().readTree(INPUT_SCHEMA_JSON);
        } catch (IOException e) {
            throw new IllegalStateException("commit skill schema invalid", e);
        }
    }

    @Override public JsonNode inputSchema() { return inputSchema; }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        String diff = (input != null && input.hasNonNull("input"))
            ? input.get("input").asText() : "";
        String body = "按 Conventional Commits 风格生成 commit message:\n"
            + "- 格式:<type>(<scope>): <subject>\n"
            + "- type:feat / fix / docs / refactor / test / chore\n"
            + "- subject 不超过 50 字符,祈使语气\n"
            + "- body 72 字符换行,说明 what + why(不写 how)"
            + (diff.isEmpty() ? "" : "\n\nStaged diff:\n```\n" + diff + "\n```");
        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId(call.getId())
            .content(body)
            .isError(false)
            .build();
    }
}
```

### 2.4 `ToolRegistry` 接口增强

```java
package ai.lingshu.core.slot;

import ai.lingshu.core.message.ToolSpec;
import java.util.List;
import java.util.Set;

public interface ToolRegistry {
    @ContractVersionRef String CONTRACT_VERSION = "1.0.0";

    // 已有 3 方法(Story #003 / #019 落)
    void register(Tool tool);
    Tool lookup(String name);
    Collection<String> names();

    // 🆕 Story #020a 新增 4 方法
    /** 给 PromptBuilder:所有 Tool 都暴露 schema,Skill 也包含 —— 模型可自动调。 */
    List<ToolSpec> modelVisibleSpecs();

    /** 给 CLI:用户 /xxx 时查这里(也用于命令行补全 / 错误提示)。返 null 表示未找到。 */
    Skill findSkill(String name);

    /** 给 CLI:列出所有可用的 /xxx 命令。 */
    Set<String> skillNames();

    /**
     * 给 ToolDispatcher 强契约层:找不到抛 IllegalArgumentException。
     * 与 lookup() 的差别:lookup 返 null,findByName 抛 IAE。
     */
    Tool findByName(String name);
}
```

### 2.5 `DefaultToolRegistry` 实现增强

```java
@Component
public class DefaultToolRegistry implements ToolRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private final Map<String, Tool> registry = new ConcurrentHashMap<>();
    // 🆕 Story #020a — Skill 索引
    private final Map<String, Skill> skillsByName = new ConcurrentHashMap<>();

    @Override
    public void register(Tool tool) {
        if (tool == null) throw new IllegalArgumentException("tool must not be null");
        Tool prior = registry.putIfAbsent(tool.name(), tool);
        if (prior != null && prior != tool) {
            LOG.warn("Duplicate tool registration: name={} prior={} new={}",
                tool.name(), prior.getClass().getSimpleName(), tool.getClass().getSimpleName());
        }
        // 🆕 Skill 索引分流
        if (tool instanceof Skill) {
            Skill skill = (Skill) tool;
            Skill priorSkill = skillsByName.putIfAbsent(skill.name(), skill);
            if (priorSkill != null && priorSkill != skill) {
                LOG.warn("Duplicate skill registration: name={} prior={} new={}",
                    skill.name(), priorSkill.getClass().getSimpleName(), skill.getClass().getSimpleName());
            }
        }
    }

    @Override public Tool lookup(String name) { return registry.get(name); }
    @Override public Collection<String> names() {
        return Collections.unmodifiableCollection(registry.keySet());
    }

    // 🆕 4 个新方法
    @Override
    public List<ToolSpec> modelVisibleSpecs() {
        List<ToolSpec> specs = new ArrayList<>(registry.size());
        for (Tool t : registry.values()) {
            specs.add(new ToolSpec(t.name(), t.description(), t.inputSchema()));
        }
        // 按 name 字典序排序 —— 稳定输出,便于 PromptBuilder prompt cache 命中(对齐 #009d)
        Collections.sort(specs, new Comparator<ToolSpec>() {
            @Override public int compare(ToolSpec a, ToolSpec b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return specs;
    }

    @Override public Skill findSkill(String name) { return skillsByName.get(name); }

    @Override public Set<String> skillNames() {
        return Collections.unmodifiableSet(skillsByName.keySet());
    }

    @Override public Tool findByName(String name) {
        Tool t = registry.get(name);
        if (t == null) throw new IllegalArgumentException("Unknown tool: " + name);
        return t;
    }

    // 已有 asMap() 不变
    public Map<String, Tool> asMap() {
        return Collections.unmodifiableMap(registry);
    }
}
```

### 2.6 `SkillAutoConfiguration` 模板(对齐 #019 `LocalToolsAutoConfiguration`)

```java
package ai.lingshu.core.impl.skill;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Lazy;

import java.util.*;

/**
 * Story #020a — Wire the @Component Skill beans (currently just {@link CommitSkill})
 * into the {@link ToolRegistry}.
 *
 * <p>Reuses the same template as Story #019 {@link LocalToolsAutoConfiguration}:
 * <ul>
 *   <li>{@code @Configuration} + {@link InitializingBean} (avoids {@code javax.annotation.PostConstruct}
 *       import per Story #009 R-13 mitigation philosophy)</li>
 *   <li>Toggle via {@link #PROP_ENABLED} {@code agent.skills.enabled}, default true</li>
 *   <li>{@link Lazy} on the {@code Map<String, Skill>} injection to break the
 *       bean-cycle (this class ↔ Skill @Components)</li>
 * </ul>
 *
 * <p>No Skill-specific wiring (cf. BashTool.setProcessRunner): Skills have no
 * external dependencies, just registration.
 */
@Configuration
public class SkillAutoConfiguration implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(SkillAutoConfiguration.class);

    /** Property key — set to {@code false} in {@code application.yml} to disable. */
    public static final String PROP_ENABLED = "agent.skills.enabled";

    private final ToolRegistry toolRegistry;
    private final Map<String, Skill> skills;
    private final Environment environment;

    @Autowired
    public SkillAutoConfiguration(
            ToolRegistry toolRegistry,
            @Lazy Map<String, Skill> skills,
            Environment environment) {
        this.toolRegistry = toolRegistry;
        this.skills = skills;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean enabled = environment.getProperty(PROP_ENABLED, Boolean.class, Boolean.TRUE);
        if (!enabled) {
            LOG.info("Skills disabled via {}={} — Skills constructed but NOT registered",
                PROP_ENABLED, enabled);
            return;
        }
        int registered = 0;
        for (Map.Entry<String, Skill> e : skills.entrySet()) {
            toolRegistry.register(e.getValue());
            registered++;
            LOG.debug("Registered skill: beanName={} skillName={} class={}",
                e.getKey(), e.getValue().name(), e.getValue().getClass().getSimpleName());
        }
        List<String> sorted = new ArrayList<>();
        for (Skill s : skills.values()) sorted.add(s.name());
        Collections.sort(sorted);
        LOG.info("Skills ready — {} skill(s) registered: {}",
            registered, sorted);
    }
}
```

---

## §3 测试策略(12+ case,L1+L2+L3)

### 3.1 L1 单元测试(单类行为)

**`SkillToolTest`**(`lingshu-core/src/test/java/ai/lingshu/core/impl/skill/SkillToolTest.java`):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `name_description_inputSchema_returnConstructorArgs` | AC-020a-4 partial | 4 getter 直返 final 字段(显式 ctor 路径) |
| `execute_userInputEmpty_returnsContent` | AC-020a-1 | `call.getInput().get("input").asText()` 空 → 返纯 content |
| `execute_userInputProvided_appendsUserInput` | AC-020a-1 | non-empty input → content + `\n\nUser input:\n<input>` |
| `execute_nullInput_returnsContentOnly` | EC-020a-1 | null input → content 不变 |
| `execute_inputFieldMissing_returnsContentOnly` | EC-020a-1 | input JSON 没 `"input"` 字段 → content 不变 |
| `fromMarkdown_parsesTitle` | AC-020a-2 | `# Title\nbody` → description=Title, body=body |
| `fromMarkdown_firstLineFallbackToName` | AC-020a-2 | `#\nbody` 首行空 → description=name |
| `fromMarkdown_bodyEmpty` | AC-020a-2 | `# Title`(无 body) → body="" |
| `fromMarkdown_hashLevels` | AC-020a-3 | `## Sub Title\nbody` → description="Sub Title" |
| `fromMarkdown_trimsWhitespace` | AC-020a-3 | `#  Title`(多空格) → description="Title" |
| `fromMarkdown_emptyMarkdown` | EC-020a-2 | "" → description=name, content="" |
| `fromMarkdown_firstLineHashOnly` | EC-020a-2 | `#\nbody` → description=name |
| `inputSchema_alwaysReturnsFixedShape` | AC-020a-4 | schema 字段 + required["input"] |

**`CommitSkillTest`**(`lingshu-core/src/test/java/ai/lingshu/core/impl/skill/CommitSkillTest.java`):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `nameDescription_returnsExpected` | AC-020a-5 | `name()="commit"`,`description()` 含 "Conventional Commits" |
| `execute_emptyInput_returnsTemplateOnly` | AC-020a-5 | 无 staged diff → 返模板正文(无 diff 块) |
| `execute_withDiffInput_appendsStagedDiffBlock` | AC-020a-5 | 有 diff → 末尾追加 `Staged diff:\n```\n<diff>\n```` |

### 3.2 L2 slice 测试(单 Slot + 真实 `DefaultToolRegistry`)

**`ToolRegistryContractTest`**(接口契约反射验证):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `interfaceHasFourNewMethods` | AC-020a-6 | 反射验证 `modelVisibleSpecs / findSkill / skillNames / findByName` 方法存在 + return type 正确 |

**`DefaultToolRegistrySkillTest`**(`lingshu-core/src/test/java/ai/lingshu/core/impl/tool/DefaultToolRegistrySkillTest.java`):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `registerCommitSkill_populatesSkillsIndex` | AC-020a-7 | 注册 `CommitSkill` 后 `findSkill("commit")` 返 `CommitSkill`,`skillNames()` 含 `"commit"`,`findByName("commit")` 返同实例,`lookup("commit")` 也返同实例 |
| `modelVisibleSpecs_containsBothSkillAndTool_sortedByName` | AC-020a-8 | 注册 1 普通 Tool + 1 Skill → `modelVisibleSpecs()` size=2 + 按 name 字典序 |
| `findSkill_unknownReturnsNull` | AC-020a-9 | `findSkill("nonexistent")` 返 null 不抛 |
| `findByName_unknownThrowsIAE` | AC-020a-9 | `findByName("nonexistent")` 抛 `IllegalArgumentException` |
| `concurrentRegisterIsThreadSafe` | AC-020a-10 | 16 线程并发注册 200 Skill + 200 Tool → 最终 size 正确 + 无 duplicate-warn |

**`SkillAutoConfigurationTest`**(起 mini `ApplicationContext`):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `enabledByDefault_registersCommitSkill` | AC-020a-11 | 空 yml → `ToolRegistry.findSkill("commit")` 非 null |
| `disabled_doesNotRegister` | AC-020a-11 | `agent.skills.enabled=false` → `findSkill("commit")` 返 null(但 Bean 仍构造)|
| `manualNewSkillTool_canBeRegistered` | EC-020a-4 | 外部代码 `new SkillTool(...)` + `registry.register(...)` 正常工作 |

### 3.3 L3 集成测试

**`SkillRegistryE2ETest`**(`lingshu-core/src/test/java/ai/lingshu/core/impl/skill/SkillRegistryE2ETest.java`):

| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `modelVisibleSpecs_feedsAllToolsAndSkillsToPromptBuilder` | AC-020a-12 | 起 mini Spring ctx,`ToolRegistry.modelVisibleSpecs()` 拿 List<ToolSpec>,校验 size=5(4 Tool + 1 Skill)+ 各自 name/description/schema 字段正确 |

**`CommitSkillVsSkillToolTest`**(共存竞争验证):

| 测试方法 | EC 编号 | 描述 |
|---|---|---|
| `coexistence_firstRegisterWins` | EC-020a-3 | 手工 `register(CommitSkill)` + `register(SkillTool("commit", ...))` 顺序 → `findByName` 返先注册者;反过来再跑一次 |

### 3.4 测试预算

| 层级 | case 数 | 文件 |
|---|---:|---|
| L1 Unit | 13 | `SkillToolTest` + `CommitSkillTest` |
| L2 Slice | 8 | `ToolRegistryContractTest` + `DefaultToolRegistrySkillTest` + `SkillAutoConfigurationTest` |
| L3 Component | 2 | `SkillRegistryE2ETest` + `CommitSkillVsSkillToolTest` |
| **合计** | **≥ 23**(spec.md 估算 ≥ 12 略保守,**实际 ≥ 23**)| 6 测试类 |

---

## §4 实施顺序(T-NN)

参见 `tasks.md`。简述:

1. **T-01** `SkillTool` 类 + 9 L1 case
2. **T-02** `CommitSkill` @Component + 3 L1 case
3. **T-03** `ToolRegistry` 接口 +4 方法 + 1 L2 契约 case
4. **T-04** `DefaultToolRegistry` 实现 +4 方法 + 4 L2 case
5. **T-05** `SkillAutoConfiguration` + 3 L2 slice case
6. **T-06** `SkillRegistryE2ETest` + 1 L3 case + R-13 dependency:tree 自查
7. **T-07** `CommitSkillVsSkillToolTest` 共存 + 1 L2 case
8. **T-08** 跑全量 `mvn -pl lingshu-core test`,验证所有 AC + 无回归
9. **T-09** commit + push + PR body

---

## §5 风险与回滚

| 风险 | 概率 | 缓解 |
|---|---|---|
| `ToolRegistry` 接口加方法 → 现有 `DefaultToolRegistry` impl 编译失败 | 低(只增不删) | 接口改动在 T-03,实现跟进在 T-04,中间不 commit,本地 IDE 验证 |
| `ConcurrentHashMap` 实例分流错误 → Skill 普通 Tool 错乱 | 低 | 9 个测试覆盖(AC-020a-7/8/9/10) |
| `@Component` Skill 与未来 `SkillTool` 同名冲突 → 用户搞不清胜出顺序 | 中(已知 #020b 风险) | EC-020a-3 显式验证先注册者优先,Javadoc 说明 +020b CompositeSkillLoader.putIfAbsent 文档 |
| `Bean` 循环依赖(`SkillAutoConfiguration ↔ Skill @Component`)| 低 | `@Lazy` on `Map<String, Skill>` 注入(对齐 #019 模板)|
| R-13 mitigation (d): 引入新 Maven 坐标 | **0 风险** | 全部 `ai.lingshu.core` + Spring + Lombok + Jackson 已锁,无需新依赖 |

**回滚方案**:`git revert <merge-commit>` + 删除 `ai.lingshu.core.impl.skill` 包 + `git revert` `ToolRegistry.java` 接口加方法。`Skill` interface 不动 → 不影响后续 #020b / #020c。

---

## §6 关键不变项(对照 Story #019 模板)

- `Tool` interface 不变(§4.6)
- `Skill` interface 不变(Story #003 已落,本 Story 不动)
- `ToolRegistry` 接口**只加不删**(向后兼容 Story #019 测试)
- `DefaultToolRegistry.register(Tool)` 行为不变(只是新增 `if (tool instanceof Skill)` 分流)
- `ToolResult` 接口不变(走 `@Builder` 不变模式)
- `ToolCall` / `ToolSpec` / `ToolException` 全部不变
- `RuntimeSandbox` / `ProcessRunner` 不变(本 Story 不涉及 fs / process 调用)
- 9 Slot SPI 全部不变(本 Story 不新增 Slot,不新增 Provider)
- dsh §10.1 锁定 13 项依赖,**0 增量**
- `ToolExecutor` 5 步流水线不变(本 Story 不走 dispatch 流水线,只走 ToolRegistry)
- §4.10.1 硬规则 1/2/3 不涉及(本 Story 不调 Spring AI / 不调 dispatch)

---

**Last updated**: 2026-09-23
**Plan author**: Claude Code
**Reviewer**: 待 PR review
