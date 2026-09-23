# Story #020a `skill-foundation` — Tasks

> **Status**: Draft 2026-09-23
> **Implements**: `specs/020a-skill-foundation/plan.md`
> **Test budget**: ≥ 23 cases / 6 files

---

## T-01 — `SkillTool` 类 + `fromMarkdown` 静态工厂

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillTool.java`(新, ~80 行)

**实现**(对齐 dsh §6.4 L4279-4329 + plan.md §2.2):
- `public class SkillTool implements Skill` — **不**用 Lombok `@Value`(ctor 内做 JSON 解析 + `Objects.requireNonNull` 校验,需自定义 ctor body)
- 4 个 final 字段:`name` / `description` / `content` / `inputSchema`
- 构造器:
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
- `name()` / `description()` / `inputSchema()` 3 个 getter 直返 final
- `execute(ToolCall call, ToolExecutionContext ctx)`:
  - 取 `call.getInput()`
  - 提取 `input.input` 字段(String,缺省空)
  - 拼 `content + (userInput.isEmpty() ? "" : "\n\nUser input:\n" + userInput)`
  - 返 `ToolResult.builder().status(SUCCESS).toolUseId(call.getId()).content(body).isError(false).build()`
- `public static Skill fromMarkdown(String name, String markdownContent)`:
  ```java
  String body = markdownContent == null ? "" : markdownContent;
  String[] lines = body.split("\\R", 2);
  String first = lines[0].replaceFirst("^#+\\s*", "").trim();
  String description = first.isEmpty() ? name : first;
  String remaining = lines.length > 1 ? lines[1].trim() : "";
  String schema = "{ \"type\": \"object\", \"properties\": { \"input\": { \"type\": \"string\" } }, \"required\": [\"input\"] }";
  return new SkillTool(name, description, remaining, schema);
  ```

**imports**:`com.fasterxml.jackson.databind.JsonNode` / `com.fasterxml.jackson.databind.ObjectMapper` / `ai.lingshu.core.message.ToolCall` / `ai.lingshu.core.message.ToolResult` / `ai.lingshu.core.slot.Skill` / `ai.lingshu.core.slot.ToolExecutionContext` / `java.io.IOException` / `java.util.Objects`

**Javadoc**:类级 Javadoc 写明 4 行:
1. Skill 默认实现(SKILL.md → Skill 工厂)
2. inputSchema 固定 `{ "input": string }`
3. `execute` 把 `content + user input` 包成 `ToolResult.success`,调用方(CLI / dispatcher)决定下一步(`continueWithUserMessage` 留给 #020c)
4. 与 `@Component Skill` 路径对照 — `SkillTool` 适合"内容驱动 / 频繁迭代"(SKILL.md 改免编译),`@Component` 适合"逻辑驱动"(调外部 API)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `SkillToolTest`(13 case)全过 — AC-020a-1/2/3/4 + EC-020a-1/2

---

## T-02 — `CommitSkill` @Component 内置示例

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/CommitSkill.java`(新, ~50 行)

**实现**(对齐 dsh §6.4 L4380-4409 + plan.md §2.3):
- `@Component("commitSkill") public class CommitSkill implements Skill`
- `name() = "commit"`(常量 String)
- `description() = "按 Conventional Commits 风格生成 commit message"`
- `inputSchema`:ctor 解析常量字符串,缓存为 final 字段
  ```java
  private static final String INPUT_SCHEMA_JSON =
      "{ \"type\": \"object\", \"properties\": { \"input\": { \"type\": \"string\" } }, \"required\": [\"input\"] }";
  private final JsonNode inputSchema;

  public CommitSkill() {
      try {
          this.inputSchema = new ObjectMapper().readTree(INPUT_SCHEMA_JSON);
      } catch (IOException e) {
          throw new IllegalStateException("commit skill schema invalid", e);
      }
  }

  @Override public JsonNode inputSchema() { return inputSchema; }
  ```
- `execute(ToolCall call, ToolExecutionContext ctx)`:
  - 取 `input.input` 字段(String,缺省空)
  - 拼"按 Conventional Commits 风格生成 commit message:\n..." 提示正文
  - diff 非空时追加 `"\n\nStaged diff:\n```\n<diff>\n```"`
  - 返 `ToolResult.builder().status(SUCCESS).toolUseId(call.getId()).content(body).isError(false).build()`

**Javadoc**:类级 Javadoc:
1. /commit Skill 内置示例
2. 选 `@Component("commitSkill")` Bean 名(**不是** "commit")—— Bean 名 = 容器 ID 与 Tool name 解耦,避免未来 SKILL.md 路径同名 Bean 冲突
3. 与 SkillTool.fromMarkdown 对照表(来源 / 热加载 / 适合 / 配置 / 推荐)5 行
4. 选型决策 3 条:改 skill 行为 → SkillTool / 改 skill 实现逻辑调外部 API → @Component / 同名 Skill 同存走 `putIfAbsent` 先注册者优先(预留 #020b CompositeSkillLoader)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `CommitSkillTest`(3 case)全过 — AC-020a-5

---

## T-03 — `ToolRegistry` 接口 +4 方法

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java`(修改, +30 行)

**操作**:
1. 添加 `import ai.lingshu.core.message.ToolSpec;` / `import java.util.List;` / `import java.util.Set;`
2. 在 `Collection<String> names()` 方法后新增 4 方法:
   ```java
   /**
    * 给 PromptBuilder:所有 Tool 都暴露 schema,Skill 也包含 —— 模型可自动调
    * (dsh §6.4 L3989)。按 name 字典序排序,便于 PromptBuilder prompt cache 命中。
    */
   List<ToolSpec> modelVisibleSpecs();

   /**
    * 给 CLI:用户 /xxx 时查这里(也用于命令行补全 / 错误提示)。返 null 表示未找到,
    * 调用方自行处理"Unknown command: /xxx" 提示(对齐 dsh §6.4 L3998)。
    */
   Skill findSkill(String name);

   /**
    * 给 CLI:列出所有可用的 /xxx 命令(对齐 dsh §6.4 L4001)。
    */
   Set<String> skillNames();

   /**
    * 给 ToolDispatcher 强契约层:找不到抛 IllegalArgumentException(对齐 dsh §6.4 L4007-4008)。
    * 与 lookup() 的差别:lookup 返 null 走 ToolExecutor.dispatch 的 ToolNotFoundException 翻译路径;
    *                   findByName 直接抛 IAE 给上层 registry API 调用方(典型用法:CLI dispatcher)。
    */
   Tool findByName(String name);
   ```
3. **不**改现有 3 方法(register / lookup / names)— 向后兼容 #001 / #019 测试

**DoD**:
- `mvn -pl lingshu-core compile` 通过(必须 DefaultToolRegistry 同步改,否则接口未实现)
- `ToolRegistryContractTest.interfaceHasFourNewMethods`(1 case)全过 — AC-020a-6

---

## T-04 — `DefaultToolRegistry` 实现 +4 方法 + Skill 索引

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolRegistry.java`(修改, +50 行)

**操作**:
1. 添加 `import ai.lingshu.core.message.ToolSpec;` / `import ai.lingshu.core.slot.Skill;` / `import java.util.ArrayList;` / `import java.util.Comparator;` / `import java.util.List;` / `import java.util.Set;`(部分已有)
2. 添加 final 字段:
   ```java
   private final Map<String, Skill> skillsByName = new ConcurrentHashMap<>();
   ```
3. 修改 `register(Tool tool)`:在 `registry.putIfAbsent` 之后,加 `if (tool instanceof Skill)` 分流:
   ```java
   if (tool instanceof Skill) {
       Skill skill = (Skill) tool;
       Skill priorSkill = skillsByName.putIfAbsent(skill.name(), skill);
       if (priorSkill != null && priorSkill != skill) {
           LOG.warn("Duplicate skill registration: name={} prior={} new={}",
               skill.name(), priorSkill.getClass().getSimpleName(), skill.getClass().getSimpleName());
       }
   }
   ```
4. 添加 4 方法实现(对照 plan.md §2.5 完整代码)
5. 更新类级 Javadoc:加 1 行 "🆕 Story #020a — Skill 索引(skillsByName)双索引结构,所有 register 同步双写"

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `DefaultToolRegistrySkillTest`(5 case)全过 — AC-020a-7/8/9/10
- 现有 `#001 / #003 / #019` 测试**不**回归(register 行为兼容)

---

## T-05 — `SkillAutoConfiguration` 注册样板

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillAutoConfiguration.java`(新, ~60 行)

**实现**(对齐 plan.md §2.6):
- `@Configuration public class SkillAutoConfiguration implements InitializingBean`
- 常量 `PROP_ENABLED = "agent.skills.enabled"`
- 构造器 `@Autowired public SkillAutoConfiguration(ToolRegistry, @Lazy Map<String, Skill>, Environment)`
- `afterPropertiesSet()`:
  - `boolean enabled = environment.getProperty(PROP_ENABLED, Boolean.class, Boolean.TRUE);`
  - `!enabled` → INFO log + return
  - 遍历 `skills.entrySet()`,调 `toolRegistry.register(skill)`,累加 `registered`
  - INFO log `"Skills ready — N skill(s) registered: [name1, name2, ...]"`(按字典序排)

**Javadoc**(类级,镜像 `LocalToolsAutoConfiguration` 模板):
1. Story #020a — Wire `@Component Skill` Beans(当前仅 CommitSkill)
2. 复用 #019 模板理由:`@Configuration + InitializingBean + Environment` 避开 `javax.annotation.PostConstruct` 依赖(对齐 Story #009 R-13 mitigation philosophy)
3. `@Lazy` on `Map<String, Skill>` 解决 bean-cycle
4. `agent.skills.enabled` 默认 true
5. 无 Skill-specific wiring(对比 `BashTool.setProcessRunner`):纯注册即可

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `SkillAutoConfigurationTest`(3 case)全过 — AC-020a-11 + EC-020a-4

---

## T-06 — `SkillRegistryE2ETest` L3 集成测试

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/skill/SkillRegistryE2ETest.java`(新, ~80 行)

**实现**:
- `@SpringBootTest(classes = SkillAutoConfiguration.class)` 或 `@ExtendWith(SpringExtension.class) + @ContextConfiguration`
- mini Spring ctx 启动 → 拿 `ToolRegistry` bean + 4 内置 Tool(Read/Write/Edit/Bash) + `CommitSkill` 自动注册
- `toolRegistry.modelVisibleSpecs()`:
  - size = 5(4 Tool + 1 Skill)
  - 含 `"commit"` name + `"Read"` name + 其它 3
  - 排序:按 name 字典序(`"Bash" < "Edit" < "Read" < "Write" < "commit"` 字典序?)
  - 每个 `ToolSpec` 的 `name/description/inputSchema` 字段非 null

**mock 选项**:为避免依赖 `RuntimeSandbox` 等大型 Bean,用 mini ctx(`@ContextConfiguration(classes = {ToolRegistry.class, SkillAutoConfiguration.class, CommitSkill.class, ReadTool.class, ...})` 显式列出)

**DoD**:
- `mvn -pl lingshu-core test -Dtest=SkillRegistryE2ETest` 1 case 全过 — AC-020a-12
- 集成 ctx 启动无 error(`NoSuchBeanDefinitionException` / `BeanInstantiationException` 视为 fail)

---

## T-07 — `CommitSkillVsSkillToolTest` 共存竞争验证

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/skill/CommitSkillVsSkillToolTest.java`(新, ~40 行)

**实现**:
- 手工 `new DefaultToolRegistry()`(不依赖 Spring)
- 先 `register(new CommitSkill())` 再 `register(SkillTool.fromMarkdown("commit", "# test\nbody"))`:
  - `findByName("commit")` 应返 `CommitSkill`(先注册者优先)
  - `skillNames().size() == 1`(后者被 putIfAbsent 忽略)
- 反过来:先 `SkillTool` 再 `CommitSkill`:
  - `findByName("commit")` 应返 `SkillTool` 实例
  - `skillNames().size() == 1`

**DoD**:
- `mvn -pl lingshu-core test -Dtest=CommitSkillVsSkillToolTest` 1 case 全过 — EC-020a-3

---

## T-08 — 全量回归 + R-13 dependency:tree 自查

**操作**:
1. `mvn -pl lingshu-core clean test` — 全模块 L1/L2/L3 测试必须全过(Story #001—#019 + #020a 所有 case)
2. `mvn -pl lingshu-core dependency:tree -DincludeScope=runtime > /tmp/deps-post-020a.txt` 与 baseline(Story #019 pre 镜像)对比:
   ```bash
   diff /tmp/deps-baseline-019.txt /tmp/deps-post-020a.txt
   # 预期:仅时间戳差异,0 binary delta
   ```
3. **0 新 Maven coordinates** 必须在 PR body 末尾 `### R-13 dependency:tree 自查` 节明列

**DoD**:
- `mvn test` 全过(预计 200+ case,新增 23 case 后约 220+)
- `dependency:tree` diff 0 binary delta
- 若任一失败 → 修最小集,**不**跳 case / **不**关 AC

---

## T-09 — commit + push + PR body

**commit 消息**(沿用 CLAUDE.md §9 约定):
```
feat(core): Story #020a skill-foundation — SkillTool + CommitSkill + ToolRegistry 4 方法

- SkillTool concrete class (dsh §6.4 L4279-4329) + fromMarkdown 静态工厂
- CommitSkill @Component 内置示例 + Conventional Commits 风格模板
- ToolRegistry 接口 +4 方法 (modelVisibleSpecs / findSkill / skillNames / findByName)
- DefaultToolRegistry 实现 Skill 双索引 + 字典序排序 + findByName throw IAE
- SkillAutoConfiguration 注册样板 (复用 LocalToolsAutoConfiguration 模板)
- 23 测试 case 全过 (L1 13 + L2 8 + L3 2), 0 新 Maven coordinates (R-13)
- dsh §13 changelog v1.5.38 单独 PR 同步
```

**PR body 模板**(沿用 Story #018 / #019):
```markdown
## Story #020a `skill-foundation`

参考:`specs/020a-skill-foundation/{spec,plan,tasks}.md`(本 PR)

### 范围
落地 Skill 系统第一块砖 —— SkillTool concrete class + fromMarkdown 静态工厂 + @Component CommitSkill + ToolRegistry 4 个新方法 + SkillAutoConfiguration 注册样板。

### 关键变更
- **5 核心文件**: SkillTool + CommitSkill + SkillAutoConfiguration(新)+ ToolRegistry + DefaultToolRegistry(修改)
- **23 测试 case**(L1 13 + L2 8 + L3 2)全过
- **0 新 ErrorCode**(findByName 抛 IAE 是 API 契约错误,非业务异常)
- **dsh §13 changelog v1.5.38 单独 PR 同步**(沿用 Story #018 / #019 模式)

### AC 验证摘要
| AC | 状态 |
|---|---|
| AC-020a-1: SkillTool execute 4 路径 | ✅ |
| AC-020a-2: fromMarkdown 解析 | ✅ |
| AC-020a-3: fromMarkdown ## 标题 + 多空格 trim | ✅ |
| AC-020a-4: inputSchema 固定 shape | ✅ |
| AC-020a-5: CommitSkill name/description/execute | ✅ |
| AC-020a-6: ToolRegistry 接口 4 方法 | ✅ |
| AC-020a-7: skillsByName 索引 + lookup/findSkill/findByName | ✅ |
| AC-020a-8: modelVisibleSpecs 含 Skill + Tool 字典序 | ✅ |
| AC-020a-9: findSkill null vs findByName IAE | ✅ |
| AC-020a-10: ConcurrentHashMap 并发注册线程安全 | ✅ |
| AC-020a-11: SkillAutoConfiguration enabled/disabled | ✅ |
| AC-020a-12: L3 E2E 5 spec | ✅ |
| EC-020a-1: null input + 缺 input 字段 | ✅ |
| EC-020a-2: 空 markdown + 首行只有 # | ✅ |
| EC-020a-3: 同名 CommitSkill vs SkillTool 共存 | ✅ |
| EC-020a-4: 手工 new SkillTool 可注册 | ✅ |

### R-13 dependency:tree 自查
- baseline: `mvn -pl lingshu-core dependency:tree -DincludeScope=runtime`(Story #019 合并后镜像)
- post: 同命令(Story #020a 合并前)
- diff: **仅时间戳差异,0 binary delta**(验证 `mvn validate` + `mvn compile` + `mvn test` 全过)
- 13 项依赖未增 → R-13 维持 PASS
```

**DoD**:
- commit message + PR body 完整
- `git push` 到 `story/020a-skill-foundation` 分支
- PR body 含 `### R-13 dependency:tree 自查` 节(CLAUDE.md §11 #6 强制项)

---

## 任务依赖图

```
T-01 (SkillTool + 13 L1 case)
  ↓
T-02 (CommitSkill + 3 L1 case) ─ 依赖 T-01(inputSchema 一致 + execute 模式一致)
  ↓
T-03 (ToolRegistry 接口 4 方法 + 1 L2 契约 case)
  ↓
T-04 (DefaultToolRegistry 实现 + 4 L2 case) ─ 依赖 T-03(必须接口先就位)
  ↓
T-05 (SkillAutoConfiguration + 3 L2 case) ─ 依赖 T-04(DefaultToolRegistry 必须支持 Skill register)
  ↓
T-06 (SkillRegistryE2ETest L3) ─ 依赖 T-04 + T-05
  ↓
T-07 (CommitSkillVsSkillToolTest 共存) ─ 依赖 T-01 + T-02 + T-04
  ↓
T-08 (全量回归 + R-13 自查) ─ 依赖 T-01—T-07 全部
  ↓
T-09 (commit + push + PR body) ─ 依赖 T-08
```

---

**Last updated**: 2026-09-23
**Tasks author**: Claude Code
**Reviewer**: 待 PR review
