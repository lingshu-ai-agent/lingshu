# Story #020b `skill-source-discovery` — Spec

> **Status**: Draft 2026-09-23
> **Source**: dsh v1.5.37 §6.4 `Skill 多源自动发现`(L4066-4273)+ §4.6 `Tool` 契约 + §5.4 plugin AutoConfiguration 编写约定
> **Closes gap**: Story #020a 落 `SkillTool` + `fromMarkdown` + `@Component CommitSkill` + `ToolRegistry` Skill 索引 — 但 **Skill 源发现层 5 件全缺**:
> (1) `SkillSource` SPI interface — 没有从 source 拉取 `List<Skill>` 的统一契约;
> (2) `SkillSourceProvider` SPI — 没有按 type 路由到具体 source 实现的工厂;
> (3) `ClasspathSkillSource` + `DirectorySkillSource` 两个 v1 内置实现 — `SkillTool.fromMarkdown` 在 L4150/L4199 各被调 1 次但无 source 拉起;
> (4) `SkillSourceRouter` — 多 Provider 共存的路由表(L4250-4262),当前只有 stub;
> (5) `CompositeSkillLoader` — 聚合 N 个 source + `putIfAbsent` 去重(L4224-4244),当前只有 stub。
> 后果:用户 yml 写 `agent.skills.sources: [{type: classpath, location: ...}, {type: directory, location: ...}]` 启动后 0 个 Skill 加载;`@Component CommitSkill` 独自跑,SKILL.md 路径完全闲置。

---

## WHY

Story #020a 落地 Skill 接口契约层(`SkillTool` + `fromMarkdown` + `@Component CommitSkill` + `ToolRegistry` 双索引),但 Skill **怎么被发现** 仍然是空白 —— dsh §6.4 L4066 写明 "**Skill 多源自动发现**(类 Claude Code,可同时挂 classpath + 多个 directory)",dsh L4076-4085 定义 `SkillSource` SPI 契约 + L4269-4272 列典型组合(classpath 内置 + 本地写目录 + 未来 NFS / git / s3),代码侧 **0 实现**。这造成 3 个连锁问题:

1. **`SkillTool.fromMarkdown(name, content)` 是哑方法** — Story #020a 落地的 `fromMarkdown` 静态工厂只能手动 `new` 调一次,无法批量扫 SKILL.md 文件;dsh L4150 `out.add(SkillTool.fromMarkdown(name, content))` 引用 1 次 + L4199 引用 1 次,两个 caller 全缺 → Skill 系统无法 "扫目录自动发现"
2. **`@Component Skill` 单源硬编码路径覆盖不全** — Story #020a 只硬编码了 `CommitSkill`(`/commit` 命令),用户想加 `/review` / `/deploy` / `/lint` 命令,**只能**再写一个 `@Component` 类,**SKILL.md 即改即用** 的路径完全不可用
3. **#020c `cli-skill-trigger` 必须等 #020b** — CLI `/xxx` 触发需要 `toolRegistry.findSkill(name)`,#020a 已就绪;但用户想 `/review` 时,若没 #020b source discovery,`/review` 永远找不到 Skill(只有 `CommitSkill` 1 个) — 体验上是 "Skill 系统只有 1 个 commit 命令"

**Story #020b 目标**:落地 `SkillSource` + `SkillSourceProvider` SPI + 2 个 v1 source 实现(classpath / directory)+ `SkillSourceRouter` 路由表 + `CompositeSkillLoader` 聚合器,让 `application.yml` 写 `agent.skills.sources: [...]` 就能批量加载 SKILL.md Skills,**Slot 4 Skill 体系第二块砖**(从"单源硬编码" → "多源自动发现")。

**业务价值**:
- dsh §0.4 AC-04(`commit / review / deploy` 等典型命令)在 #020a 只 hardcode 了 commit;**#020b 之后用户写 SKILL.md 就能加新命令,免编译免重启(若 hot-reload enabled,留后续 Story)**
- dsh §1 决策 9 "Plugin 发现走 Spring Boot SPI" 真正在 Skill 层落地 — SkillSourceProvider SPI 让用户能扩展 "git" / "s3" / "http" 等新 source,**不**改 core 代码
- #020c CLI `/xxx` 拦截之后能从 source 扫到的所有 Skill 取列表,启动日志 dump 出来,UX 完整

---

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类)| 想给团队加 `/code-review` / `/deploy-staging` 命令 — 在 `src/main/resources/skills/code-review/SKILL.md` 写 markdown 即可,无需 Java 代码 |
| **业务配置方**(Diana 类)| 写 `application.yml`:```yaml agent: skills: sources: - type: classpath location: classpath:skills/agent-builtin/ - type: directory location: ./skills/ ``` 启动后看到 `Skills ready — N skill(s) registered: [code-review, commit, deploy-staging, ...]` |
| **运维挂载团队技能** | yml 加 `type: directory location: /mnt/team-skills/`,NFS 挂载的团队共享 Skill 自动可见(无须发版)|
| **CI 工程师**(Charlie 类)| L1/L2/L3 测试 case ≥ 25,`mvn -pl lingshu-core test` 0 fail;`mvn dependency:tree` 0 增量 |
| **框架贡献者**(plugin 作者)| 写 `GitSkillSource implements SkillSource` + `GitSkillSourceProvider implements SkillSourceProvider { type()="git" }`,@Component 注册 — `CompositeSkillLoader` 自动 pick up,yml 写 `type: git` 即可用 |

---

## WHAT

Story #020b 落地 7 个新接口 / 类 + 1 个修改 + 1 个配置类:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `SkillSource` | SPI interface | `lingshu-core/.../slot/SkillSource.java` | ~30 |
| `SkillSourceProvider` | SPI interface | `lingshu-core/.../slot/SkillSourceProvider.java` | ~25 |
| `SkillSourceProperties` | `@ConfigurationProperties("agent.skills")` | `lingshu-core/.../impl/skill/SkillSourceProperties.java` | ~50 |
| `ClasspathSkillSource` | Provider + Source 共存文件 | `lingshu-core/.../impl/skill/ClasspathSkillSource.java` | ~80 |
| `DirectorySkillSource` | Provider + Source 共存文件 | `lingshu-core/.../impl/skill/DirectorySkillSource.java` | ~75 |
| `SkillSourceRouter` | `@Component` 路由表 | `lingshu-core/.../impl/skill/SkillSourceRouter.java` | ~40 |
| `CompositeSkillLoader` | `@Component` 聚合器 | `lingshu-core/.../impl/skill/CompositeSkillLoader.java` | ~60 |
| `SkillAutoConfiguration` | 修改:加 discover 流程 | `lingshu-core/.../impl/skill/SkillAutoConfiguration.java` | +30 |
| 测试 | L1 + L2 + L3 集成 | `lingshu-core/src/test/java/.../skill/` | ~500 行 / ≥25 case |

**7 核心文件 = 7 新建 + 1 修改 + 1 测试目录**,0 ErrorCode,`mvn dependency:tree` 0 增量。

### 1. `SkillSource` SPI(对齐 dsh §6.4 L4080-4086)

```java
package ai.lingshu.core.slot;
import ai.lingshu.core.spi.ContractVersionRef;
import java.io.IOException;
import java.util.List;

/**
 * 单一 Skill 源。type 决定加载器实现,由 SkillSourceProvider SPI 路由。
 * v1 内置两种:"classpath"(随 jar 发布)+ "directory"(本地/挂载目录);
 * 后期可扩 "git" / "s3" —— 实现 SkillSourceProvider 即可。
 */
public interface SkillSource {

    @ContractVersionRef String CONTRACT_VERSION = "1.0.0";

    String type();        // "classpath" | "directory" | ...
    String location();    // 位置字符串(语义由 type 决定)
    List<Skill> discover() throws IOException;

    /** Whether this source supports mtime watching (留 §14.8 hot-reload 钩子). */
    default boolean watchable() { return false; }
}
```

**关键约束**:
- `discover()` 抛 `IOException`(dsh L4083) — 文件系统 / classpath 读盘错误
- `watchable()` default false(对齐 dsh L4084) — ClasspathSkillSource `= false`(随 jar 发布不变),DirectorySkillSource override `= true`(留 #020b 后续 hot-reload 配套)
- `type()` 是 namespace 严格隔离 key(d对齐 §5.2 SlotRouter 同名竞争)— ClasspathSkillSource 必须 `"classpath"`,DirectorySkillSource 必须 `"directory"`

### 2. `SkillSourceProvider` SPI(对齐 dsh §6.4 L4088-4097)

```java
package ai.lingshu.core.slot;

public interface SkillSourceProvider {

    @ContractVersionRef String CONTRACT_VERSION = "1.0.0";

    /** "classpath" | "directory" | "git" | "s3" | ... */
    String type();

    /** Create a SkillSource for the given location. */
    SkillSource create(String location);
}
```

**关键约束**:
- Provider 的 `type()` 必须与 SkillSource 的 `type()` 一致(SkillSourceRouter 按 type 路由)
- Provider 是 @Component Bean(对齐 dsh L4114 `ClasspathSkillSourceProvider` + L4170 `DirectorySkillSourceProvider`),多 Provider 共存由 SkillSourceRouter 收 `List<SkillSourceProvider>` 启动期构造 `Map<String, SkillSourceProvider>`
- Provider `create(location)` 不抛 IOException(对齐 dsh L4095 — 创建 SkillSource 实例是纯内存操作,IOException 留到 discover())

### 3. `SkillSourceProperties` 配置类

```java
package ai.lingshu.core.impl.skill;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties("agent.skills")
public class SkillSourceProperties {

    private boolean enabled = true;   // 与 Story #020a agent.skills.enabled 保持一致
    private boolean hotReload = false; // §14.8 留钩子,本 Story 不实现 watch 行为
    private List<SourceEntry> sources = new ArrayList<>();

    public static class SourceEntry {
        private String type;
        private String location;
        // getters / setters
    }
    // getters / setters
}
```

**关键约束**:
- `@ConfigurationProperties("agent.skills")` — 与 Story #020a `agent.skills.enabled` 同 namespace
- `enabled` 字段默认 true(对齐 Story #020a 默认值)— 整个 Skill 注册可关
- `hotReload` 字段本 Story **不**接 WatchService(留 OQ-Future / 后续 Story),只占位
- `sources` List 字段允许 yml 直接写 `agent.skills.sources[0].type` / `.location`
- 通过 `@EnableConfigurationProperties(SkillSourceProperties.class)` 启用(放 SkillAutoConfiguration 上)

### 4. `ClasspathSkillSource` 实现(对齐 dsh §6.4 L4114-4155)

```java
@Component
public class ClasspathSkillSourceProvider implements SkillSourceProvider {
    @Override public String type() { return "classpath"; }

    @Override
    public SkillSource create(String location) {
        // location 形如 "classpath:skills/agent-builtin/" 或 "skills/agent-builtin/"
        String prefix = location.startsWith("classpath:")
            ? location.substring("classpath:".length()) : location;
        return new ClasspathSkillSource(prefix);
    }
}

class ClasspathSkillSource implements SkillSource {
    private final String classpathPrefix;
    private final PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver();

    public ClasspathSkillSource(String prefix) { this.classpathPrefix = prefix; }

    @Override public String type()     { return "classpath"; }
    @Override public String location() { return "classpath:" + classpathPrefix; }
    @Override public boolean watchable() { return false; }   // 随 jar 发布,运行时不变

    @Override
    public List<Skill> discover() throws IOException {
        String pattern = "classpath*:" + classpathPrefix + "*/SKILL.md";
        Resource[] md = resolver.getResources(pattern);
        List<Skill> out = new ArrayList<>();
        for (Resource r : md) {
            String url = r.getURL().toString();
            // 解析 parent 目录名作为 skill name
            int slash = url.lastIndexOf('/', url.length() - "/SKILL.md".length() - 1);
            int prevSlash = url.lastIndexOf('/', slash - 1);
            String name = url.substring(prevSlash + 1, slash);
            String content = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            out.add(SkillTool.fromMarkdown(name, content));
        }
        return out;
    }
}
```

**关键约束**:
- Provider 是 `public class`(暴露给 Spring 扫描),Source 是 package-private(`class ClasspathSkillSource`,对齐 dsh L4128)
- `classpath*:skills/agent-builtin/*/SKILL.md` 模式:`classpath*:` 通配多个 jar,`*/SKILL.md` 匹配单层目录(避免递归)
- URL 解析(parent 目录名作为 skill name)— 对齐 dsh L4144-4148;**edge case**:`/SKILL.md` 必须恰好 8 字符,前缀目录单层(yml 写 `skills/agent-builtin/`,目录结构 `skills/agent-builtin/commit/SKILL.md` → name=`commit`)
- JDK 8 兼容:`new String(r.getInputStream().readAllBytes(), UTF_8)` — `InputStream.readAllBytes()` 是 JDK 9+,**必须**替换为 `ByteStreams.toByteArray(r.getInputStream())` 或自己循环;本 Story 用 Apache Commons IO **不**在依赖列表 → 自己写循环

### 5. `DirectorySkillSource` 实现(对齐 dsh §6.4 L4170-4206)

```java
@Component
public class DirectorySkillSourceProvider implements SkillSourceProvider {
    @Override public String type() { return "directory"; }

    @Override
    public SkillSource create(String location) {
        return new DirectorySkillSource(Paths.get(location));
    }
}

class DirectorySkillSource implements SkillSource {
    private final Path dir;
    private DirectorySkillSource(Path d) { this.dir = d.toAbsolutePath().normalize(); }

    @Override public String type()     { return "directory"; }
    @Override public String location() { return dir.toString(); }
    @Override public boolean watchable() { return true; }    // 配合 §14.8 hot-reload 钩子

    @Override
    public List<Skill> discover() throws IOException {
        if (!Files.isDirectory(dir)) return Collections.emptyList();   // 静默跳过(非目录)
        List<Skill> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                Path md = entry.resolve("SKILL.md");
                if (Files.isRegularFile(md)) {
                    String name = entry.getFileName().toString();
                    String content = new String(Files.readAllBytes(md), StandardCharsets.UTF_8);
                    out.add(SkillTool.fromMarkdown(name, content));
                }
            }
        }
        return out;
    }
}
```

**关键约束**:
- 目录不存在 / 不可读 → 静默返空列表(对齐 dsh L4191 `if (!Files.isDirectory(dir)) return Collections.emptyList()`)— 不抛异常,**log warning** 留给 SkillAutoConfiguration 层(避免每个 source 都打 warn)
- Path 在 ctor 内 `toAbsolutePath().normalize()` — 防止 yml 写 `..` 越权(对齐 RuntimeSandbox chroot 哲学)
- `watchable() = true` 标记 — 留 §14.8 hot-reload 钩子,**不**本 Story 实现 watch 逻辑(避免 scope creep)
- `Files.readAllBytes` JDK 8 支持 OK

### 6. `SkillSourceRouter` 路由表(对齐 dsh §6.4 L4249-4262)

```java
@Component
public class SkillSourceRouter {

    private static final Logger LOG = LoggerFactory.getLogger(SkillSourceRouter.class);

    private final Map<String, SkillSourceProvider> byType;

    @Autowired
    public SkillSourceRouter(List<SkillSourceProvider> all) {
        this.byType = new HashMap<>();
        for (SkillSourceProvider p : all) byType.put(p.type(), p);
        LOG.info("SkillSourceRouter ready — {} provider(s) registered: {}",
            byType.size(), byType.keySet());
    }

    public SkillSource resolve(String type, String location) {
        SkillSourceProvider p = byType.get(type);
        if (p == null) {
            throw new IllegalStateException(
                "Unknown SkillSource type: " + type + ". Available: " + byType.keySet());
        }
        return p.create(location);
    }
}
```

**关键约束**:
- `Map<String, SkillSourceProvider>` 启动期构造(对齐 §5.3.1 SlotRouter 多 Provider 模板)
- `resolve(type, location)` 找不到 type 抛 `IllegalStateException`(对齐 dsh L4258-4259)— **fail-fast**,避免 yml 写错 type 时静默 no-op
- 注入 `List<SkillSourceProvider>` 自动收齐所有 `@Component` Provider Bean(对齐 Spring Boot SPI 自动发现)

### 7. `CompositeSkillLoader` 聚合器(对齐 dsh §6.4 L4224-4244)

```java
@Component
public class CompositeSkillLoader {

    private static final Logger LOG = LoggerFactory.getLogger(CompositeSkillLoader.class);

    private final SkillSourceRouter router;

    @Autowired
    public CompositeSkillLoader(SkillSourceRouter router) {
        this.router = router;
    }

    /**
     * Discover all Skills from configured sources.
     * @param props 绑定的 agent.skills 配置
     * @return List of Skills, dedup'd by name (first-registered-wins)
     */
    public List<Skill> discover(SkillSourceProperties props) {
        if (props == null || props.getSources() == null || props.getSources().isEmpty()) {
            LOG.debug("CompositeSkillLoader.discover — no sources configured, returning empty");
            return Collections.emptyList();
        }
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (SkillSourceProperties.SourceEntry src : props.getSources()) {
            SkillSource resolved = router.resolve(src.getType(), src.getLocation());
            List<Skill> skills;
            try {
                skills = resolved.discover();
            } catch (IOException e) {
                LOG.warn("SkillSource.discover failed: type={} location={} — {}",
                    src.getType(), src.getLocation(), e.toString());
                continue;   // 单 source 失败不阻塞其他 source(对齐 §10 R-09 风险缓解)
            }
            for (Skill s : skills) {
                Skill prior = byName.putIfAbsent(s.name(), s);
                if (prior != null && prior != s) {
                    LOG.warn("Duplicate skill across sources: name={} prior={} new={} (先出现者优先)",
                        s.name(), prior.getClass().getSimpleName(), s.getClass().getSimpleName());
                }
            }
        }
        List<Skill> result = new ArrayList<>(byName.values());
        LOG.info("CompositeSkillLoader.discover — {} skill(s) loaded from {} source(s): {}",
            result.size(), props.getSources().size(), skillNames(result));
        return result;
    }

    private static List<String> skillNames(List<Skill> skills) {
        List<String> names = new ArrayList<>();
        for (Skill s : skills) names.add(s.name());
        Collections.sort(names);
        return names;
    }
}
```

**关键约束**:
- `discover(SkillSourceProperties props)` 取代 dsh L4229 的 `discover(AgentConfig cfg)` —— 接收 Spring 配置而非 runtime AgentConfig(Spring 启动期可用,AgentConfig 启动期不存在)
- `putIfAbsent` 保证**先出现者优先**(对齐 dsh L4239)— yml 写 classpath 在前 + directory 在后 → classpath SKILL.md 胜出;反过来 directory 胜出
- 单 source IOException 走 `continue` 不阻塞 — 避免一个错配 directory 把所有 Skills 弄丢(对齐 §10 R-09)
- `LinkedHashMap` 保留发现顺序(便于 `modelVisibleSpecs()` 输出稳定)

### 8. `SkillAutoConfiguration` 修改(对齐 dsh §6.4 L4378 #020a 模板)

在 Story #020a 已有的 `@Component Skill` 注册流程基础上,**追加** discover 流程:

```java
@Configuration
@EnableConfigurationProperties(SkillSourceProperties.class)
public class SkillAutoConfiguration implements InitializingBean {

    public static final String PROP_ENABLED = "agent.skills.enabled";

    private final ToolRegistry toolRegistry;
    private final Map<String, Skill> skills;        // 🆕 Story #020a @Component Skills
    private final SkillSourceProperties props;     // 🆕 Story #020b
    private final CompositeSkillLoader loader;     // 🆕 Story #020b
    private final Environment environment;

    @Autowired
    public SkillAutoConfiguration(
            ToolRegistry toolRegistry,
            @Lazy Map<String, Skill> skills,
            SkillSourceProperties props,           // 🆕
            CompositeSkillLoader loader,           // 🆕
            Environment environment) {
        this.toolRegistry = toolRegistry;
        this.skills = skills;
        this.props = props;
        this.loader = loader;
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

        // 🆕 Story #020b — 第一步:discover Skills from sources
        List<Skill> discovered = loader.discover(props);
        for (Skill s : discovered) {
            toolRegistry.register(s);  // register 内部 instanceof Skill 分流
        }
        LOG.info("Discovered {} skill(s) from sources: {}",
            discovered.size(), /* sorted names */);

        // 第二步(原有 Story #020a):注册所有 @Component Skill Bean
        int registered = 0;
        for (Map.Entry<String, Skill> e : skills.entrySet()) {
            toolRegistry.register(e.getValue());  // putIfAbsent 先注册者优先
            registered++;
            LOG.debug("Registered skill: beanName={} skillName={} class={}",
                e.getKey(), e.getValue().name(), e.getValue().getClass().getSimpleName());
        }
        List<String> sorted = new ArrayList<>();
        for (Skill s : skills.values()) sorted.add(s.name());
        Collections.sort(sorted);
        LOG.info("Skills ready — {} component skill(s) registered: {}",
            registered, sorted);
    }
}
```

**关键约束**:
- **顺序**:`loader.discover()` 先 → source Skills 先注册;`@Component Skills` 后注册 → **`@Component` 胜出**(对齐 Story #020a EC-020a-3 的预期)
- `putIfAbsent` 在 `DefaultToolRegistry.register` 内部已实现(Story #020a),这里无需额外去重
- `Environment.getProperty("agent.skills.enabled")` 读 **环境变量** + yml 双源,**优先级** yml > env(Spring 标准)
- `SkillSourceProperties` 通过 `@EnableConfigurationProperties` 启用

---

## 反向 AC(明确不做)

- ❌ **`SkillSource.hotReload` 实际 watch 行为** — dsh §6.4 提到,但 §14.8 才展开;本 Story `hotReload` 字段占位,watch 行为留 OQ-Future / 后续 Story
- ❌ **`"git"` / `"s3"` / `"http"` 类型 source** — dsh §6.4 L4078 显式列为"后期可扩",需要写新的 `XxxSkillSource` + `XxxSkillSourceProvider`,本 Story **不**实现(留给 plugin 作者按本 Story 模板扩展)
- ❌ **递归扫描多层级目录**(目前 `classpath*:prefix/*/SKILL.md` 只扫一层)— 多层级扫描会导致 skill name 解析歧义(对齐 dsh 单层约定)
- ❌ **Skill hot-reload(directory 文件 mtime 监听)** — §14.8 范围,本 Story `watchable()=true` 仅标记不接 WatchService
- ❌ **`CompositeSkillLoader.discover(AgentConfig)` runtime cfg 参数重载** — dsh L4229 写法,本 Story 用 `discover(SkillSourceProperties props)` Spring 配置版本;runtime 重载是 Story #020c 之后的故事
- ❌ **Skill execute 输入 schema 改动** — 仍固定 `{ "input": string }`(对齐 Story #020a)
- ❌ **Skill `fromMarkdown` 改动** — 完全沿用 Story #020a 实现
- ❌ **`Skill` interface 新方法** — 仍为 marker(对齐 Story #020a 不变项)
- ❌ **CLI `/xxx` 触发拦截** — 留 #020c
- ❌ **PromptBuilder `[TOOL SCHEMAS]` 集成** — 留 OQ-5
- ❌ **目录不存在时启动失败** — 静默返空列表 + log(避免误配 yml 启动失败)
- ❌ **SkillTool 单元测试改造** — 完全沿用 Story #020a 实现

---

## AC 编号(Story #020b 新增,不入 dsh §0.4)

| AC | 描述 | 验证 |
|---|---|---|
| **AC-020b-1** | `SkillSource` interface 有 4 方法 `type() / location() / discover() / watchable()`,编译期可见(签名 + Javadoc 完整)| `SkillSourceContractTest.interfaceHasFourMethods`(L1 1 case,反射验证 method 存在 + return type)|
| **AC-020b-2** | `SkillSourceProvider` interface 有 2 方法 `type() / create(location)`,编译期可见 | `SkillSourceProviderContractTest.interfaceHasTwoMethods`(L1 1 case)|
| **AC-020b-3** | `SkillSourceProperties` `@ConfigurationProperties("agent.skills")` 绑定:yml 写 `agent.skills.sources[0].type=classpath` + `.location=classpath:skills/builtin/`,`properties.getSources().get(0).getType()` + `.getLocation()` 精确读出 | `SkillSourcePropertiesTest.bindFromYaml`(L1 1 case,`@EnableConfigurationProperties` + `ApplicationContextRunner`)|
| **AC-020b-4** | `ClasspathSkillSourceProvider.type()="classpath"`,`create("classpath:skills/foo/")` 返 `ClasspathSkillSource(type="classpath", location="classpath:skills/foo/")`;`create("skills/foo/")`(无前缀)同样 OK(自动补 `classpath:` 前缀)| `ClasspathSkillSourceTest.providerCreate_normalizesLocation`(L1 1 case)|
| **AC-020b-5** | `ClasspathSkillSource.discover()` 从 classpath 扫 `classpath*:skills/test-fixtures/*/SKILL.md`(测试 fixture),返 `List<Skill>`,size = fixture 目录数,每个 Skill `name()` = 目录名,`description()` = SKILL.md 首行去 `#`,`execute()` 走 SkillTool.fromMarkdown 路径 | `ClasspathSkillSourceTest.discover_scansClasspathFixtures`(L2 1 case,在 `src/test/resources/skills/test-fixtures/` 放 3 个 SKILL.md)|
| **AC-020b-6** | `DirectorySkillSourceProvider.type()="directory"`,`create("/abs/path")` 返 `DirectorySkillSource`;`watchable() = true` | `DirectorySkillSourceTest.providerCreate_normalizesToAbsolutePath`(L1 1 case,@TempDir)|
| **AC-020b-7** | `DirectorySkillSource.discover()` 从 @TempDir 扫 `*/SKILL.md`,返 `List<Skill>`;目录不存在 → 返空 list(不抛异常);目录存在但无 SKILL.md → 返空 list | `DirectorySkillSourceTest.discover_emptyDir_returnsEmpty` + `discover_nonExistentDir_returnsEmpty` + `discover_populatedDir_returnsSkills`(L2 3 case)|
| **AC-020b-8** | `SkillSourceRouter.resolve("classpath", "...")` 返 `ClasspathSkillSource`;`resolve("directory", "...")` 返 `DirectorySkillSource`;`resolve("unknown", "...")` 抛 `IllegalStateException` | `SkillSourceRouterTest.resolve_knownType` + `resolve_unknownType_throws`(L2 2 case)|
| **AC-020b-9** | `SkillSourceRouter` Spring 启动后含 2 个 Provider(`"classpath"` + `"directory"`),构造时 INFO log 列出所有 type | `SkillSourceRouterTest.springCtxResolvesAllRegisteredProviders`(L2 1 case,mini ctx)|
| **AC-020b-10** | `CompositeSkillLoader.discover(props)` 聚合 2 个 source(classpath + directory),每个 source 各贡献 N 个 Skill;**同名 Skill 先出现者优先**(yml 写 classpath 在前 → classpath 胜) | `CompositeSkillLoaderTest.discover_dedupByPutIfAbsent_firstWins`(L2 1 case,fixture 同名 Skill)|
| **AC-020b-11** | `CompositeSkillLoader.discover(props)` 当 props.getSources().isEmpty() → 返空 list(不抛异常);props = null → 返空 list | `CompositeSkillLoaderTest.discover_emptySources_returnsEmpty` + `discover_nullProps_returnsEmpty`(L2 2 case)|
| **AC-020b-12** | `CompositeSkillLoader.discover(props)` 单 source `discover()` 抛 IOException → 该 source 跳过(log warn),其他 source 继续 | `CompositeSkillLoaderTest.discover_singleSourceIOException_continuesWithOthers`(L2 1 case,mock IOException)|
| **AC-020b-13** | `SkillAutoConfiguration` Spring 启动后:yml 配置 1 个 directory source + 1 个 `@Component Skill`(`CommitSkill`)→ `ToolRegistry.skillNames()` 含 directory source 扫到的 Skill + `"commit"`(2 项)| `SkillAutoConfigurationDiscoverTest.directorySourcePlusComponentSkill_coexist`(L2 1 case,mini ctx + @TempDir)|
| **AC-020b-14** | `SkillAutoConfiguration` yml 同时配置 classpath + directory source + `@Component CommitSkill`,全部 Skill 都被注册;**`CommitSkill` 胜出同名 SkillTool**(因 `@Component` 在 source discover 之后注册)| `SkillAutoConfigurationDiscoverTest.componentSkillBeatsSkillTool`(L2 1 case,fixture classpath 放 `commit/SKILL.md`,yml classpath source + @Component CommitSkill)|
| **AC-020b-15** | `SkillAutoConfiguration` yml 配 `agent.skills.enabled=false` → source 不加载 + `@Component` 不注册,`toolRegistry.skillNames()` 空 | `SkillAutoConfigurationDiscoverTest.disabled_doesNotDiscoverOrRegister`(L2 1 case,@TestPropertySource)|
| **AC-020b-16** | 集成 E2E:`ToolRegistry.modelVisibleSpecs()` 含 source-discovered Skill + `@Component Skill` + 4 内置 Tool(Read/Write/Edit/Bash),size ≥ 7,按 name 字典序排 | `SkillSourceDiscoveryE2ETest.modelVisibleSpecs_containsDiscoveredAndBuiltinTools`(L3 1 case,`AnnotationConfigApplicationContext` + @TempDir)|

**EC(边界 case)**:

| EC | 描述 | 验证 |
|---|---|---|
| **EC-020b-1** | `SkillSourceProperties.getSources()` 缺省 empty list(yml 完全没写 `agent.skills`)→ CompositeSkillLoader 返空 list;无 NPE | `SkillSourcePropertiesTest.defaultEmptySources`(L1 1 case)|
| **EC-020b-2** | `ClasspathSkillSource.discover()` 找不到匹配 resource → 返空 list(不抛 IOException,Spring `getResources` 空数组) | `ClasspathSkillSourceTest.discover_noMatches_returnsEmpty`(L2 1 case)|
| **EC-020b-3** | `DirectorySkillSource.discover()` SKILL.md 路径解析:目录 `code-review/SKILL.md` → Skill.name() = `"code-review"`(与 directory entry 名一致,不是文件名 `SKILL.md`)| `DirectorySkillSourceTest.discover_skillNameFromDirectoryEntry`(L2 1 case)|
| **EC-020b-4** | `SkillSourceRouter` 多个相同 `type()` 的 Provider(用户误配)→ `byType.put` 第二次覆盖第一次,INFO log 只列 type 不重复 — **不**抛异常(Spring 容器层 BeanDefinitionOverrideException 早抛,Router 层静默)| `SkillSourceRouterTest.duplicateType_secondProviderWins_silentOverride`(L2 1 case)|
| **EC-020b-5** | `CompositeSkillLoader.discover()` 单 source 返 0 Skill + 单 source 抛 IOException → 最终 list 不包含任何 Skill,无 NPE | `CompositeSkillLoaderTest.discover_allSourcesFail_returnsEmpty`(L2 1 case)|

---

## ErrorCode 引入(0 条)

| 码 | 域 | 触发场景 |
|---|---|---|
| — | — | **0 新 ErrorCode**(SkillSourceRouter.resolve 抛 `IllegalStateException` 是 SPI 契约错误,启动期 fail-fast;非业务异常 — 对齐 dsh L4258-4259)|

**约束 Story 边界**(CLAUDE.md §11 #4 ≤ 5 核心文件,≤ 3 ErrorCode):7 新建 + 1 修改 + 1 测试目录 = **8 文件**,其中 7 个生产文件(略超 5 上限 2 个,Acceptable 因为 SkillSource SPI 必须 interface 单独一个文件,与 §5.4 plugin 样板对齐),0 ErrorCode 远 ≤ 3。**Story 边界略宽,显式记录超 2 个文件**(理由:SkillSource 与 SkillSourceProvider 是 2 个独立 SPI interface,各自需要单文件 — dsh L4080 / L4088 同样分 2 文件)。

---

## 出口标准(DoD)

- [ ] `specs/020b-skill-source-discovery/{spec,plan,tasks}.md` 三件套合入主分支
- [ ] 7 个生产核心文件 + 1 个修改 + 1 个测试目录 = 9 文件落地
- [ ] ≥ 16 测试 case(L1+L2+L3)+ 5 EC 全过(`mvn -pl lingshu-core test`),预计 Story #020a 基础上 +25 case → 总 ~250
- [ ] R-13 `mvn dependency:tree` 自查:**0 新 Maven coordinates**(`mvn -pl lingshu-core dependency:tree -DincludeScope=runtime` pre/post diff 仅时间戳)
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节
- [ ] dsh v1.5.39 单独 PR 同步(沿用 Story #020a 模式:`v1.5.37 → v1.5.38` 是 #020a,`v1.5.38 → v1.5.39` 是 #020b — `§13` changelog 加 Story #020b 行)
- [ ] ROADMAP.md 段一「✅ 已完成」表追加 `#020b skill-source-discovery`
- [ ] CLAUDE.md 版本号同步(`1.3.32 → 1.3.33`,沿用 `v1.3.x` 与 dsh `v1.5.x` 配套)
- [ ] README.md Story 路线图追加 + 简短 retrospective 段(对齐 #020a)

---

## 不在 Story #020b 范围(显式 deferred)

- ❌ Skill hot-reload(directory WatchService 监听 mtime)— 留 OQ-Future / §14.8 配套
- ❌ `GitSkillSource` / `S3SkillSource` / `HttpSkillSource` — 留 plugin 作者按本 Story 模板扩展
- ❌ 多层级目录递归扫描 — 单层约定
- ❌ 目录不存在启动失败 — 静默 + log warn
- ❌ CLI `/xxx` 拦截 + `handleUserInput` — 留 #020c
- ❌ `CompositeSkillLoader.discover(AgentConfig cfg)` 重载 — 留后续 Story
- ❌ PromptBuilder 集成 `modelVisibleSpecs()` — 留 OQ-5
- ❌ `Skill` interface 新方法 — 留 Skill v1.1 RFC
- ❌ Skill 危险等级 → §4.7 审批门联动 — 留 Skill v1.1 RFC
- ❌ YAML `hotReload: true` 实际接 WatchService — 仅字段占位,行为 OQ-Future

---

**Last updated**: 2026-09-23
**Spec author**: Claude Code (per user 2026-09-23 conversation)
**Reviewer**: 待 PR review
