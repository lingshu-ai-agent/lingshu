# Story #020b `skill-source-discovery` — Tasks

> **Status**: Draft 2026-09-23
> **Implements**: `specs/020b-skill-source-discovery/plan.md`
> **Test budget**: ≥ 23 cases / 8 files(L1 5 + L2 17 + L3 1)

---

## T-01 — `SkillSource` + `SkillSourceProvider` SPI 接口

**文件**:
- `lingshu-core/src/main/java/ai/lingshu/core/slot/SkillSource.java`(新, ~50 行)
- `lingshu-core/src/main/java/ai/lingshu/core/slot/SkillSourceProvider.java`(新, ~40 行)

**实现**(对齐 plan.md §2.1 + §2.2):

`SkillSource.java`:
- `public interface SkillSource`
- `@ContractVersionRef String CONTRACT_VERSION = "1.0.0";`
- `String type();`
- `String location();`
- `List<Skill> discover() throws IOException;`
- `default boolean watchable() { return false; }`

`SkillSourceProvider.java`:
- `public interface SkillSourceProvider`
- `@ContractVersionRef String CONTRACT_VERSION = "1.0.0";`
- `String type();`
- `SkillSource create(String location);`

**imports**:
- SkillSource: `ai.lingshu.core.spi.ContractVersionRef`, `java.io.IOException`, `java.util.List`
- SkillSourceProvider: `ai.lingshu.core.spi.ContractVersionRef`

**Javadoc**(对齐 dsh L4066-4097):
- SkillSource:类级 Javadoc 写明 4 段落(1. 源发现概念 / 2. v1 内置 2 种 + 未来扩展 / 3. `discover()` 并发不要求 / 4. IOException 单 source 失败由 CompositeSkillLoader 兜底)
- SkillSourceProvider:类级 Javadoc 写明 4 段落(1. Factory SPI / 2. type 路由到 Provider / 3. Spring Boot SPI 自动发现 / 4. Bean 名约定 `<type>SkillSourceProvider`)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `SkillSourceContractTest.interfaceHasFourMethods`(1 case)全过 — AC-020b-1
- `SkillSourceProviderContractTest.interfaceHasTwoMethods`(1 case)全过 — AC-020b-2

---

## T-02 — `SkillSourceProperties` 配置类

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillSourceProperties.java`(新, ~70 行)

**实现**(对齐 plan.md §2.3):
- `@ConfigurationProperties("agent.skills")`
- `public class SkillSourceProperties`
- 字段:
  - `private boolean enabled = true;`(Story #020a 对齐)
  - `private boolean hotReload = false;`(占位,OQ-Future)
  - `private List<SourceEntry> sources = new ArrayList<>();`
- 嵌套类 `public static class SourceEntry`:
  - `private String type;`
  - `private String location;`
  - 手写 getter/setter(2 个字段不值得用 Lombok)
- 主类 getter/setter 全手写

**imports**:
- `org.springframework.boot.context.properties.ConfigurationProperties`
- `java.util.ArrayList`
- `java.util.List`

**Javadoc**(类级,4 段落):
1. `@ConfigurationProperties("agent.skills")` 绑定 yml 结构(示例 yaml 块)
2. **Why a separate class** — 不复用 `AgentConfig.Skills`(runtime immutable vs Spring mutable POJO 解耦)
3. **Why @ConfigurationProperties** — indexed list binding 比 `environment.getProperty("agent.skills.sources[0].type")` 干净
4. **Activation** — 通过 `@EnableConfigurationProperties(SkillSourceProperties.class)` on `SkillAutoConfiguration`

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `SkillSourcePropertiesTest.bindFromYaml`(1 case)全过 — AC-020b-3
- `SkillSourcePropertiesTest.defaultEmptySources`(1 case)全过 — EC-020b-1

---

## T-03 — `ClasspathSkillSource` Provider + Source

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/ClasspathSkillSource.java`(新, ~120 行)
**Fixture**:`lingshu-core/src/test/resources/skills/test-fixtures/{commit,review,deploy}/SKILL.md`(3 个 markdown)

**实现**(对齐 plan.md §2.4):
- `public class ClasspathSkillSourceProvider implements SkillSourceProvider`:
  - `@Component`
  - `type() = "classpath"`
  - `create(location)`:
    - `String prefix = location.startsWith("classpath:") ? location.substring("classpath:".length()) : location;`
    - `return new ClasspathSkillSource(prefix);`
- `class ClasspathSkillSource implements SkillSource`:
  - 字段:`String classpathPrefix` + `PathMatchingResourcePatternResolver resolver`(ctor 内 new)
  - `type() = "classpath"`
  - `location() = "classpath:" + classpathPrefix`
  - `watchable() = false`
  - `discover()`:
    - `String pattern = "classpath*:" + classpathPrefix + "*/SKILL.md";`
    - `Resource[] md = resolver.getResources(pattern);`
    - 遍历 md,URL 解析(d对齐 dsh L4144-4148) → name + content → `SkillTool.fromMarkdown(name, content)`
    - 返 `List<Skill>`
  - `private static String readUtf8(Resource r)` — **JDK 8 兼容**手写循环(避免 `InputStream.readAllBytes()` JDK 9+)

**Fixture 内容**(3 个 SKILL.md):
```
# Commit
按 Conventional Commits 风格生成 commit message。
feat / fix / docs / refactor / test / chore + subject ≤ 50 字符。

# Review
按团队 code review checklist 检查 PR。

# Deploy
部署到 staging 环境,kubectl apply -k overlays/staging。
```

**imports**:
- `ai.lingshu.core.slot.Skill`, `SkillSource`, `SkillSourceProvider`
- `org.springframework.core.io.Resource`
- `org.springframework.core.io.support.PathMatchingResourcePatternResolver`
- `org.springframework.stereotype.Component`
- `java.io.IOException`, `java.nio.charset.StandardCharsets`
- `java.util.ArrayList`, `java.util.List`

**Javadoc**(类级,Provider + Source 各 1 个):
- Provider Javadoc:`@Component` 自动发现 + `classpath:` 前缀兼容 + dsh §6.4 L4114-4155 reference
- Source Javadoc:`classpath*:` 多 jar 通配 + 单层 glob 约束 + JDK 8 readUtf8 rationale + watchable=false 理由

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `ClasspathSkillSourceTest`(3 case)全过 — AC-020b-4/5 + EC-020b-2
- 现有 #020a 测试**不**回归(SkillTool / CommitSkill / ToolRegistry 不变)

---

## T-04 — `DirectorySkillSource` Provider + Source

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/DirectorySkillSource.java`(新, ~100 行)

**实现**(对齐 plan.md §2.5):
- `public class DirectorySkillSourceProvider implements SkillSourceProvider`:
  - `@Component`
  - `type() = "directory"`
  - `create(location) = new DirectorySkillSource(Paths.get(location));`
- `class DirectorySkillSource implements SkillSource`:
  - 字段:`Path dir`(private ctor,`d.toAbsolutePath().normalize()` 防 `..` 越权)
  - `type() = "directory"`
  - `location() = dir.toString()`
  - `watchable() = true`(留 §14.8 hot-reload 钩子)
  - `discover()`:
    - `if (!Files.isDirectory(dir)) return Collections.emptyList();`(静默跳过)
    - `try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir))`
    - 遍历 entry,`if (Files.isRegularFile(entry.resolve("SKILL.md")))` → name + content → `SkillTool.fromMarkdown(...)`
    - 返 `List<Skill>`

**imports**:
- `ai.lingshu.core.slot.Skill`, `SkillSource`, `SkillSourceProvider`
- `org.springframework.stereotype.Component`
- `java.io.IOException`, `java.nio.charset.StandardCharsets`
- `java.nio.file.DirectoryStream`, `Files`, `Path`, `Paths`
- `java.util.ArrayList`, `java.util.Collections`, `java.util.List`

**Javadoc**:
- Provider Javadoc:`@Component` + yml 用法示例 + dsh §6.4 L4170-4206 reference
- Source Javadoc:Path normalize 防越权 + 目录不存在静默 + watchable=true 留钩子

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `DirectorySkillSourceTest`(5 case)全过 — AC-020b-6/7 + EC-020b-3
- 现有测试不回归

---

## T-05 — `SkillSourceRouter` 路由表

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillSourceRouter.java`(新, ~50 行)

**实现**(对齐 plan.md §2.6):
- `@Component public class SkillSourceRouter`
- `private static final Logger LOG`
- 字段:`private final Map<String, SkillSourceProvider> byType;`
- `@Autowired public SkillSourceRouter(List<SkillSourceProvider> all)`:
  - `byType = new HashMap<>()`
  - 遍历 all,`byType.put(p.type(), p)`
  - INFO log `"SkillSourceRouter ready — N provider(s) registered: [type1, type2, ...]"`
- `public SkillSource resolve(String type, String location)`:
  - `SkillSourceProvider p = byType.get(type);`
  - `if (p == null) throw new IllegalStateException("Unknown SkillSource type: " + type + ". Available: " + byType.keySet());`
  - `return p.create(location);`

**imports**:
- `ai.lingshu.core.slot.SkillSource`, `SkillSourceProvider`
- `org.slf4j.Logger`, `LoggerFactory`
- `org.springframework.beans.factory.annotation.Autowired`
- `org.springframework.stereotype.Component`
- `java.util.HashMap`, `java.util.List`, `java.util.Map`

**Javadoc**(类级):
1. Routes type → Provider(dsh §6.4 L4249-4262 reference)
2. Multi-Provider 哲学(对齐 §5.3.1.0 SlotRouter template + v1.5.28 唯一 Bean 名约定)
3. **Fail-fast on unknown type**(避免 yml typo 静默 no-op)
4. **Duplicate type**(后注册覆盖前注册,silent override)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `SkillSourceRouterTest`(4 case)全过 — AC-020b-8/9 + EC-020b-4

---

## T-06 — `CompositeSkillLoader` 聚合器

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/skill/CompositeSkillLoader.java`(新, ~85 行)

**实现**(对齐 plan.md §2.7):
- `@Component public class CompositeSkillLoader`
- `private static final Logger LOG`
- 字段:`private final SkillSourceRouter router;`
- `@Autowired public CompositeSkillLoader(SkillSourceRouter router)`
- `public List<Skill> discover(SkillSourceProperties props)`:
  - null / empty sources → 返 `Collections.emptyList()` + DEBUG log
  - `Map<String, Skill> byName = new LinkedHashMap<>();`(保留插入顺序)
  - 遍历 `props.getSources()`:
    - `SkillSource resolved = router.resolve(src.getType(), src.getLocation());` — 包 try/catch IllegalStateException → warn + continue
    - `List<Skill> skills = resolved.discover();` — 包 try/catch IOException → warn + continue
    - 遍历 skills,`byName.putIfAbsent(s.name(), s)` — 已有时 warn "Duplicate skill across sources"
  - `List<Skill> result = new ArrayList<>(byName.values());`
  - INFO log `"CompositeSkillLoader.discover — N skill(s) loaded from M source(s): [name1, ...]"`
  - 返 result
- `private static List<String> sortedNames(List<Skill> skills)`(字典序)

**imports**:
- `ai.lingshu.core.slot.Skill`, `SkillSource`
- `org.slf4j.Logger`, `LoggerFactory`
- `org.springframework.beans.factory.annotation.Autowired`
- `org.springframework.stereotype.Component`
- `java.io.IOException`
- `java.util.ArrayList`, `java.util.Collections`, `java.util.LinkedHashMap`, `java.util.List`, `java.util.Map`

**Javadoc**(类级):
1. Aggregates SkillSources + putIfAbsent dedup(dsh §6.4 L4224-4244 reference)
2. Dedup rule(先发现者优先,yml 顺序敏感)
3. Single-source fail tolerance(IOException → continue,dsh §10 R-09 mitigation)
4. **API divergence from dsh**: `discover(SkillSourceProperties)` vs dsh `discover(AgentConfig)` 解释(Spring 启动期 vs runtime immutable)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `CompositeSkillLoaderTest`(5 case)全过 — AC-020b-10/11/12 + EC-020b-5

---

## T-07 — `SkillAutoConfiguration` 修改 + 集成测试

**文件**:
- `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillAutoConfiguration.java`(修改, +30 行)
- `lingshu-core/src/test/java/ai/lingshu/core/impl/skill/SkillAutoConfigurationDiscoverTest.java`(新, ~120 行)

**`SkillAutoConfiguration` 修改**(对齐 plan.md §2.8):
1. 加 `@EnableConfigurationProperties(SkillSourceProperties.class)`
2. 构造器参数加 `SkillSourceProperties props` + `CompositeSkillLoader loader`
3. 字段加 `private final SkillSourceProperties props;` + `private final CompositeSkillLoader loader;`
4. `afterPropertiesSet()` 在原 Phase 2 (`@Component` 注册)**之前**插入 Phase 1:
   ```java
   // Phase 1: source discover
   List<Skill> discovered = loader.discover(props);
   for (Skill s : discovered) {
       toolRegistry.register(s);
   }
   if (!discovered.isEmpty()) {
       LOG.info("Discovered {} skill(s) from sources: {}", discovered.size(), sortedNames(discovered));
   }
   ```
5. 加 `private static List<String> sortedNames(List<Skill> skills)` helper
6. 原 Phase 2 不变

**`SkillAutoConfigurationDiscoverTest.java`** 实现:
- 3 L2 case:
  - `directorySourcePlusComponentSkill_coexist` — yml 1 directory source(@TempDir 1 SKILL.md)+ @Component CommitSkill → skillNames() 含两者
  - `componentSkillBeatsSkillTool` — yml 1 classpath source(`commit/SKILL.md`)+ @Component CommitSkill → CommitSkill 胜
  - `disabled_doesNotDiscoverOrRegister` — `agent.skills.enabled=false` → skillNames() 空
- 用 `AnnotationConfigApplicationContext` + `@ImportAutoConfiguration` 或 `@EnableConfigurationProperties` + 显式 Bean 注册 + `MapPropertySource.addFirst("agent.skills.enabled", "false")`(对齐 Story #020a `SkillAutoConfigurationTest` 模板)

**DoD**:
- `mvn -pl lingshu-core compile` 通过
- `SkillAutoConfigurationDiscoverTest`(3 case)全过 — AC-020b-13/14/15
- 现有 Story #020a `SkillAutoConfigurationTest`(3 case)**不**回归(行为扩展而非破坏)

---

## T-08 — L3 集成测试 + R-13 dep-tree 自查

**文件**:`lingshu-core/src/test/java/ai/lingshu/core/impl/skill/SkillSourceDiscoveryE2ETest.java`(新, ~80 行)

**实现**:
- `@SpringBootTest`(无 spring-boot-test → 用 `AnnotationConfigApplicationContext` 镜像 Story #020a `SkillRegistryE2ETest` 模板)
- mini ctx 启动 → 注入 `ToolRegistry` + `CommitSkill` + 4 内置 Tool + `DirectorySkillSourceProvider`(向 @TempDir 注册)
- yml:`agent.skills.sources[0].type=directory`,`agent.skills.sources[0].location=<tempDir>`
- @TempDir 写 2 个 SKILL.md:`commit` / `review`(commit 是与 CommitSkill 同名)
- 调 `toolRegistry.modelVisibleSpecs()`:
  - size ≥ 7(2 source Skill + 1 @Component CommitSkill + 4 内置 Tool)— 实际可能 8(因为 source `commit` 被 @Component 覆盖,只有 1 个 commit 在 registry)
  - 字典序排
  - 含 `commit` + `review` + `Read`/`Write`/`Edit`/`Bash` 5 个 name

**R-13 dependency:tree 自查**:
```bash
mvn -pl lingshu-core dependency:tree -DincludeScope=runtime > /tmp/deps-post-020b.txt
diff /tmp/deps-baseline-020a.txt /tmp/deps-post-020b.txt
# 预期:仅时间戳差异,0 binary delta(PathMatchingResourcePatternResolver 是 spring-core 已含)
```

**DoD**:
- `mvn -pl lingshu-core test -Dtest=SkillSourceDiscoveryE2ETest` 1 case 全过 — AC-020b-16
- `dependency:tree` diff 0 binary delta

---

## T-09 — 全量回归 + commit + push + PR body

**全量回归**:
```bash
mvn -pl lingshu-core clean test
```
- Story #001—#019 + #020a 所有 case 必须全过
- Story #020b 新增 ≥ 23 case 全过
- 预计总 ~250 case

**commit 消息**(沿用 CLAUDE.md §9 约定):
```
feat(core): Story #020b skill-source-discovery — SkillSource SPI + 2 source impls + CompositeSkillLoader

- SkillSource / SkillSourceProvider SPI interfaces (dsh §6.4 L4066-4097)
- SkillSourceProperties @ConfigurationProperties for agent.skills binding
- ClasspathSkillSourceProvider + ClasspathSkillSource (classpath*:prefix/*/SKILL.md)
- DirectorySkillSourceProvider + DirectorySkillSource (Path normalize + silent skip)
- SkillSourceRouter (List<Provider> → Map<type,Provider> + fail-fast on unknown)
- CompositeSkillLoader (putIfAbsent dedup + single-source IOException tolerance)
- SkillAutoConfiguration 追加 Phase 1 discover + Phase 2 @Component(后者胜出同名)
- 23 测试 case 全过 (L1 5 + L2 17 + L3 1), 0 新 Maven coordinates (R-13)
- dsh §13 changelog v1.5.39 单独 PR 同步
```

**PR body 模板**(沿用 #020a):
```markdown
## Story #020b `skill-source-discovery`

参考:`specs/020b-skill-source-discovery/{spec,plan,tasks}.md`(本 PR)

### 范围
落地 Skill 系统第二块砖 —— SkillSource SPI + SkillSourceProvider SPI + 2 个 v1 实现(classpath / directory)+ SkillSourceRouter 路由 + CompositeSkillLoader 聚合 + SkillSourceProperties 配置绑定 + SkillAutoConfiguration 追加 Phase 1 discover 流程。

### 关键变更
- **7 核心文件(新)+ 1 修改**: SkillSource + SkillSourceProvider + SkillSourceProperties + ClasspathSkillSource + DirectorySkillSource + SkillSourceRouter + CompositeSkillLoader + SkillAutoConfiguration(改)
- **23 测试 case**(L1 5 + L2 17 + L3 1)全过
- **0 新 ErrorCode**(SkillSourceRouter.resolve 抛 IllegalStateException 是 SPI 契约错误)
- **dsh §13 changelog v1.5.39 单独 PR 同步**(沿用 Story #020a 模式)

### AC 验证摘要
| AC | 状态 |
|---|---|
| AC-020b-1: SkillSource 4 方法 | ✅ |
| AC-020b-2: SkillSourceProvider 2 方法 | ✅ |
| AC-020b-3: SkillSourceProperties yml 绑定 | ✅ |
| AC-020b-4: ClasspathSkillSourceProvider 路径 normalize | ✅ |
| AC-020b-5: ClasspathSkillSource discover 扫 fixture | ✅ |
| AC-020b-6: DirectorySkillSourceProvider 绝对路径 | ✅ |
| AC-020b-7: DirectorySkillSource discover 3 路径 | ✅ |
| AC-020b-8: SkillSourceRouter resolve + 未知 type 抛 | ✅ |
| AC-020b-9: SkillSourceRouter mini ctx 2 Provider | ✅ |
| AC-020b-10: CompositeSkillLoader dedup putIfAbsent | ✅ |
| AC-020b-11: CompositeSkillLoader empty/null props | ✅ |
| AC-020b-12: CompositeSkillLoader 单 source IOException 继续 | ✅ |
| AC-020b-13: SkillAutoConfiguration directory + @Component 共存 | ✅ |
| AC-020b-14: SkillAutoConfiguration @Component 胜 source | ✅ |
| AC-020b-15: SkillAutoConfiguration disabled | ✅ |
| AC-020b-16: L3 E2E modelVisibleSpecs ≥ 7 | ✅ |
| EC-020b-1: SkillSourceProperties 默认空 sources | ✅ |
| EC-020b-2: ClasspathSkillSource 无匹配返空 | ✅ |
| EC-020b-3: DirectorySkillSource name from dir entry | ✅ |
| EC-020b-4: SkillSourceRouter duplicate type silent override | ✅ |
| EC-020b-5: CompositeSkillLoader all sources fail 返空 | ✅ |

### R-13 dependency:tree 自查
- baseline: `mvn -pl lingshu-core dependency:tree -DincludeScope=runtime`(Story #020a 合并后镜像)
- post: 同命令(Story #020b 合并前)
- diff: **仅时间戳差异,0 binary delta**(`PathMatchingResourcePatternResolver` 是 spring-core 已含,无需新依赖)
- 13 项依赖未增 → R-13 维持 PASS
```

**DoD**:
- commit message + PR body 完整
- `git push` 到 `story/020b-skill-source-discovery` 分支
- PR body 含 `### R-13 dependency:tree 自查` 节(CLAUDE.md §11 #6 强制项)
- 创建分支前 `git checkout -b story/020b-skill-source-discovery`(对齐 #020a 模式)

---

## 任务依赖图

```
T-01 (SkillSource + SkillSourceProvider SPI + 2 L1 契约 case)
  ↓
T-02 (SkillSourceProperties + 2 L1 case) ─ 依赖 T-01(Skill interface 已用)
  ↓
T-03 (ClasspathSkillSource + 3 L2 case) ─ 依赖 T-01 + SkillTool.fromMarkdown (#020a)
  ↓
T-04 (DirectorySkillSource + 5 L2 case) ─ 依赖 T-01 + SkillTool.fromMarkdown (#020a)
  ↓
T-05 (SkillSourceRouter + 4 L2 case) ─ 依赖 T-03 + T-04 (Provider list)
  ↓
T-06 (CompositeSkillLoader + 5 L2 case) ─ 依赖 T-05 (Router)
  ↓
T-07 (SkillAutoConfiguration 改 + 3 L2 case) ─ 依赖 T-02 + T-06 (Properties + Loader)
  ↓
T-08 (SkillSourceDiscoveryE2ETest L3 + R-13) ─ 依赖 T-07
  ↓
T-09 (全量回归 + commit + push + PR body) ─ 依赖 T-01—T-08 全部
```

---

**Last updated**: 2026-09-23
**Tasks author**: Claude Code
**Reviewer**: 待 PR review
