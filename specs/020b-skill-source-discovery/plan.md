# Story #020b `skill-source-discovery` — Plan

> **Status**: Draft 2026-09-23
> **Implements**: `specs/020b-skill-source-discovery/spec.md`
> **Source**: dsh v1.5.37 §6.4 L4066-4273(Skill 多源自动发现)
> **Pre-req**: Story #020a ✅ skill-foundation(SkillTool + fromMarkdown + ToolRegistry Skill 索引 + SkillAutoConfiguration @Component 注册)

---

## §1 范围与非范围

### In-Scope(7 核心文件 + 1 修改 + 1 测试目录)

| 文件 | 行为 |
|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/slot/SkillSource.java`(新)| Skill 源 SPI interface(4 方法)|
| `lingshu-core/src/main/java/ai/lingshu/core/slot/SkillSourceProvider.java`(新)| Skill 源 Provider SPI interface(2 方法)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillSourceProperties.java`(新)| `@ConfigurationProperties("agent.skills")` 绑定类 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/ClasspathSkillSource.java`(新)| Provider(`@Component`) + package-private Source 共存文件 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/DirectorySkillSource.java`(新)| Provider(`@Component`) + package-private Source 共存文件 |
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillSourceRouter.java`(新)| `@Component` 路由表(`List<SkillSourceProvider>` → `Map<String, SkillSourceProvider>`)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/CompositeSkillLoader.java`(新)| `@Component` 聚合器(discover + dedup + 单 source fail-soft)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/SkillAutoConfiguration.java`(修改)| 追加 discover 流程(`loader.discover(props)` → `toolRegistry.register` 循环)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/skill/source/`(新)| ≥ 16 测试 case(L1+L2+L3)+ 5 EC |

### Out-of-Scope(显式 deferred)

- `GitSkillSource` / `S3SkillSource` / `HttpSkillSource` → plugin 作者扩展
- Skill hot-reload(WatchService 监听 mtime)— §14.8 / OQ-Future
- 多层级目录递归扫描 — 单层约定
- `/xxx` CLI 拦截 → #020c
- `CompositeSkillLoader.discover(AgentConfig cfg)` 重载 → 后续 Story
- `Skill` interface 新方法(用户别名 / 权限标记)— Skill v1.1 RFC

---

## §2 接口契约锚点(dsh §6.4 L4066-4273)

### 2.1 `SkillSource` interface(dsh §6.4 L4080-4086)

```java
package ai.lingshu.core.slot;

import ai.lingshu.core.spi.ContractVersionRef;
import java.io.IOException;
import java.util.List;

/**
 * 单一 Skill 源。type 决定加载器实现,由 SkillSourceProvider SPI 路由。
 * v1 内置两种:"classpath"(随 jar 发布)+ "directory"(本地/挂载目录);
 * 后期可扩 "git" / "s3" —— 实现 SkillSourceProvider 即可。
 *
 * <p><b>🆕 Story #020b — Skill source SPI</b>:the SPI that backs
 * {@code CompositeSkillLoader.discover()}. All v1 sources (classpath / directory)
 * implement this interface; future sources (git / s3 / http) just add another
 * {@code SkillSourceProvider @Component} bean.
 *
 * <p><b>Concurrency:</b> {@link #discover()} is called once at startup
 * (by {@code SkillAutoConfiguration.afterPropertiesSet()}). Implementations are
 * NOT required to be thread-safe — the call site runs single-threaded.
 *
 * <p><b>Error semantics:</b> {@link #discover()} may throw {@link IOException}
 * for filesystem / classpath read errors. {@code CompositeSkillLoader} catches
 * and logs the failure (per source) — one bad source does not block others
 * (dsh §10 R-09 mitigation philosophy).
 */
public interface SkillSource {

    @ContractVersionRef String CONTRACT_VERSION = "1.0.0";

    /** Source type key (e.g. {@code "classpath"}, {@code "directory"}). Must be unique across Providers. */
    String type();

    /** Original location string as configured (e.g. {@code "classpath:skills/foo/"}). */
    String location();

    /**
     * Discover all Skills from this source.
     *
     * <p>For v1 sources:
     * <ul>
     *   <li>ClasspathSkillSource: scan {@code classpath*:prefix/*/SKILL.md}, parse each file as Markdown.</li>
     *   <li>DirectorySkillSource: scan {@code <dir>/*/SKILL.md}, parse each file as Markdown.</li>
     * </ul>
     *
     * @return List of Skills (possibly empty), in directory-scan order.
     * @throws IOException if the underlying storage cannot be read.
     */
    List<Skill> discover() throws IOException;

    /**
     * Whether this source supports mtime watching (留 §14.8 hot-reload 钩子).
     * v1: {@code ClasspathSkillSource.watchable() = false} (jar 不可改),
     * {@code DirectorySkillSource.watchable() = true} (本地可改).
     */
    default boolean watchable() { return false; }
}
```

**当前状态**:本 Story 新建。**`Skill` interface 不动**(对齐 Story #020a 不变项)。

### 2.2 `SkillSourceProvider` interface(dsh §6.4 L4088-4097)

```java
package ai.lingshu.core.slot;

import ai.lingshu.core.spi.ContractVersionRef;

/**
 * Factory SPI for {@link SkillSource} — Spring Boot auto-config style.
 *
 * <p>Each Provider declares a unique {@link #type()} key (e.g. {@code "classpath"}).
 * {@code SkillSourceRouter} collects all Providers at startup, indexed by {@code type()}.
 * At config time, {@code CompositeSkillLoader} calls {@link SkillSourceRouter#resolve(String, String)}
 * → {@code Provider.create(location)} → {@code SkillSource} instance.
 *
 * <p><b>🆕 Story #020b</b>: v1 ships two Providers — {@code ClasspathSkillSourceProvider}
 * and {@code DirectorySkillSourceProvider}. Users add {@code git} / {@code s3} types
 * by writing a new {@code @Component implements SkillSourceProvider} — no core code changes.
 *
 * <p><b>Bean name convention</b> (Story #020a philosophy + dsh §5.4 plugin AutoConfiguration):
 * use {@code @Component} (NOT {@code @Bean}); Bean name = {@code "<type>SkillSourceProvider"}
 * (e.g. {@code "classpathSkillSourceProvider"}, {@code "directorySkillSourceProvider"}).
 * Spring auto-discovers Providers; {@code SkillSourceRouter} constructor injects
 * {@code List<SkillSourceProvider>} and indexes by {@code type()}.
 */
public interface SkillSourceProvider {

    @ContractVersionRef String CONTRACT_VERSION = "1.0.0";

    /** Source type key (e.g. {@code "classpath"}, {@code "directory"}). Must be unique. */
    String type();

    /**
     * Build a {@link SkillSource} for the given location string.
     *
     * <p>Implementation parses {@code location} according to its own convention
     * (e.g. {@code ClasspathSkillSourceProvider} strips {@code "classpath:"} prefix;
     * {@code DirectorySkillSourceProvider} treats location as filesystem path).
     *
     * @param location the location string from {@code agent.skills.sources[].location}
     * @return a new {@link SkillSource} instance (not yet discovered)
     */
    SkillSource create(String location);
}
```

### 2.3 `SkillSourceProperties` 配置类

```java
package ai.lingshu.core.impl.skill;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * @ConfigurationProperties for {@code agent.skills.*} (Story #020b).
 *
 * <p>Binds the following YAML structure:
 * <pre>
 * agent:
 *   skills:
 *     enabled: true              # default true (mirrors #020a agent.skills.enabled)
 *     hotReload: false           # OQ-Future — field reserved, no WatchService in v1
 *     sources:
 *       - type: classpath
 *         location: classpath:skills/agent-builtin/
 *       - type: directory
 *         location: ./skills/
 * </pre>
 *
 * <p><b>Why a separate class (not reusing {@code AgentConfig.Skills}):</b>
 * {@code AgentConfig.Skills} is the runtime immutable config (Lombok @Value) consumed
 * by the engine. Spring property binding needs a separate, mutable POJO with setters
 * (or constructor binding via @ConstructorBinding) — coupling them would pollute
 * {@code AgentConfig} with Spring concerns. Keeping them separate is cleaner
 * (cf. {@code AgentConfig.ToolsConfig} vs the implied {@code ToolsConfigProperties}
 * — same pattern).
 *
 * <p><b>Why @ConfigurationProperties not @Value injection:</b> indexed list binding
 * ({@code sources[0].type}) is cleanest via @ConfigurationProperties + nested POJO.
 * Manual {@code environment.getProperty("agent.skills.sources[0].type")} works
 * but is fragile (hard-coded index, no schema validation).
 *
 * <p><b>Activation:</b> via {@code @EnableConfigurationProperties(SkillSourceProperties.class)}
 * on {@code SkillAutoConfiguration}.
 */
@ConfigurationProperties("agent.skills")
public class SkillSourceProperties {

    /** Global toggle. Default {@code true}. Mirrors {@code agent.skills.enabled} (Story #020a). */
    private boolean enabled = true;

    /** Hot-reload toggle (OQ-Future). Default {@code false}. No WatchService wired in v1. */
    private boolean hotReload = false;

    /** Ordered list of Skill sources. Empty list = no Skills from sources. */
    private List<SourceEntry> sources = new ArrayList<>();

    public static class SourceEntry {
        private String type;
        private String location;
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isHotReload() { return hotReload; }
    public void setHotReload(boolean hotReload) { this.hotReload = hotReload; }
    public List<SourceEntry> getSources() { return sources; }
    public void setSources(List<SourceEntry> sources) { this.sources = sources; }
}
```

**关键约束**:
- `enabled` 字段与 Story #020a `agent.skills.enabled`(由 `Environment.getProperty` 读取)**双源**:`Environment` 优先(Spring 标准 yml > env),properties 字段作为 fallback(供 `@ConfigurationProperties` 注入的代码路径使用)
- `SourceEntry` 用 Lombok `@Setter @Getter` 太重(本字段只 2 个)— 改用手写 setter/getter,**不**用 `@Value`(因为 @Value 是 immutable,与 setter 矛盾)
- `hotReload` 字段占位,本 Story 不接 WatchService
- 通过 `@EnableConfigurationProperties` 启用 — 放 SkillAutoConfiguration 上

### 2.4 `ClasspathSkillSource` 实现(dsh §6.4 L4114-4155)

```java
package ai.lingshu.core.impl.skill;

import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.SkillSource;
import ai.lingshu.core.slot.SkillSourceProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Classpath-based Skill source — scans {@code classpath*:prefix/*/SKILL.md}.
 *
 * <p>Usage (in {@code application.yml}):
 * <pre>
 * agent:
 *   skills:
 *     sources:
 *       - type: classpath
 *         location: classpath:skills/agent-builtin/
 * </pre>
 *
 * <p><b>Why {@code classpath*}:</b> with multiple jars on the classpath
 * (user jar + plugin jars), {@code classpath:} only scans the first match.
 * {@code classpath*:} aggregates across all jars — important for plugin skills
 * (cf. dsh §5.7 SPI decision).
 *
 * <p><b>Why single-level glob (no recursion):</b> skill name resolution uses
 * the parent directory name. Recursive scan would create ambiguity
 * (e.g. {@code skills/commit/v2/SKILL.md} vs {@code skills/commit/SKILL.md}).
 *
 * <p><b>Edge case:</b> prefix should NOT end with {@code /*} — the {@code *}
 * is appended automatically by the glob.
 *
 * <p><b>File-read JDK 8 compatibility:</b> {@code InputStream.readAllBytes()}
 * is JDK 9+. We loop-read manually to stay JDK 8.
 */
@Component
public class ClasspathSkillSourceProvider implements SkillSourceProvider {

    @Override public String type() { return "classpath"; }

    @Override
    public SkillSource create(String location) {
        // 兼容 "classpath:skills/foo/" 与 "skills/foo/" 两种写法
        String prefix = location.startsWith("classpath:")
            ? location.substring("classpath:".length()) : location;
        return new ClasspathSkillSource(prefix);
    }
}

class ClasspathSkillSource implements SkillSource {

    private final String classpathPrefix;
    private final PathMatchingResourcePatternResolver resolver =
        new PathMatchingResourcePatternResolver();

    public ClasspathSkillSource(String prefix) {
        this.classpathPrefix = prefix;
    }

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
            String content = readUtf8(r);
            out.add(SkillTool.fromMarkdown(name, content));
        }
        return out;
    }

    private static String readUtf8(Resource r) throws IOException {
        // JDK 8 兼容 — 自己写循环,避免 InputStream.readAllBytes() (JDK 9+)
        java.io.InputStream in = r.getInputStream();
        try {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
```

**关键约束**:
- Source 是 package-private(`class ClasspathSkillSource`),Provider 是 `public class ClasspathSkillSourceProvider`(对齐 dsh L4128 / L4114)
- `@Component` 在 Provider 上,Spring 扫描注册;Source 由 Provider `create()` 实例化(无 @Component)
- `readUtf8` 自己写循环 — **JDK 8 兼容**(`InputStream.readAllBytes()` 是 JDK 9+)
- `classpath*:prefix/*/SKILL.md` 只扫一层目录;递归扫描留给未来

### 2.5 `DirectorySkillSource` 实现(dsh §6.4 L4170-4206)

```java
package ai.lingshu.core.impl.skill;

import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.SkillSource;
import ai.lingshu.core.slot.SkillSourceProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Directory-based Skill source — scans {@code <dir>/*/SKILL.md}.
 *
 * <p>Usage (in {@code application.yml}):
 * <pre>
 * agent:
 *   skills:
 *     sources:
 *       - type: directory
 *         location: ./skills/
 *       - type: directory
 *         location: /mnt/team-skills/
 * </pre>
 *
 * <p><b>Path normalization in constructor:</b> {@code dir.toAbsolutePath().normalize()}
 * prevents accidental escape via {@code ../} (cf. RuntimeSandbox chroot philosophy).
 * A skill authored at {@code ./skills/../etc/foo} resolves to {@code /etc/foo} — but
 * still scoped to filesystem layout; the directory scan does NOT enforce sandbox.
 *
 * <p><b>Silent skip for missing dir:</b> if the directory does not exist
 * ({@code !Files.isDirectory(dir)}), return empty list — do not throw.
 * Misconfigured yml should not break startup. (cf. dsh §10 R-09 mitigation).
 *
 * <p><b>Hot-reload marker:</b> {@link #watchable()} returns {@code true}.
 * Actual {@code WatchService} wiring is deferred to §14.8 / future Story.
 */
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

    private DirectorySkillSource(Path d) {
        // toAbsolutePath + normalize — 防止 yml 写 "../" 越权
        this.dir = d.toAbsolutePath().normalize();
    }

    @Override public String type()     { return "directory"; }
    @Override public String location() { return dir.toString(); }
    @Override public boolean watchable() { return true; }    // 配合 §14.8 hot-reload 钩子

    @Override
    public List<Skill> discover() throws IOException {
        if (!Files.isDirectory(dir)) return Collections.emptyList();
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
- Source 是 package-private,Provider 是 public @Component(同上)
- `Files.readAllBytes` JDK 8 支持 OK
- 目录不存在 → 静默返空 list + SkillAutoConfiguration 层 log warn(避免每个 source 都打 warn)
- `Path.toAbsolutePath().normalize()` 防 `..` 越权,但**不**做 sandbox 强制(sandbox 是 RuntimeSandbox 责任)

### 2.6 `SkillSourceRouter` 路由表(dsh §6.4 L4249-4262)

```java
package ai.lingshu.core.impl.skill;

import ai.lingshu.core.slot.SkillSource;
import ai.lingshu.core.slot.SkillSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Routes {@code type} → {@link SkillSourceProvider} (dsh §6.4 L4249-4262).
 *
 * <p>Constructed at startup with the full {@code List<SkillSourceProvider>}
 * (Spring auto-discovers all {@code @Component implements SkillSourceProvider} beans).
 * Indexed by {@link SkillSourceProvider#type()}.
 *
 * <p><b>Multi-Provider philosophy</b> (dsh §5.3.1.0 SlotRouter template +
 * v1.5.28 §5.5 唯一 Bean 名约定): v1 ships 2 Providers ({@code classpath},
 * {@code directory}). Plugin authors add new Providers ({@code git}, {@code s3})
 * without modifying core.
 *
 * <p><b>Fail-fast on unknown type:</b> {@link #resolve(String, String)} throws
 * {@link IllegalStateException} when yml references an unknown type — preventing
 * silent no-op when user typos {@code agent.skills.sources[0].type: clssspath}.
 *
 * <p><b>Duplicate type:</b> if two Providers declare the same {@code type()},
 * the second {@code Map.put} silently overrides the first. Spring's
 * {@code BeanDefinitionOverrideException} would have caught this at bean
 * registration time if Bean names collided; if only the {@code type()} collides
 * (different Bean names), Router silently keeps the last-registered Provider.
 * INFO log lists all keys for debuggability.
 */
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

    /**
     * @throws IllegalStateException if no Provider matches the given type.
     */
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

### 2.7 `CompositeSkillLoader` 聚合器(dsh §6.4 L4224-4244)

```java
package ai.lingshu.core.impl.skill;

import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.SkillSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates multiple {@link SkillSource}s and returns the deduplicated
 * Skill list (dsh §6.4 L4224-4244).
 *
 * <p><b>Dedup rule (per source iteration order):</b> {@code putIfAbsent} —
 * first-discovered Skill wins. If yml lists classpath BEFORE directory and both
 * contain a {@code commit/SKILL.md}, the classpath Skill wins.
 *
 * <p><b>Single-source failure tolerance (dsh §10 R-09):</b> if one source's
 * {@code discover()} throws {@link IOException}, log warning and continue
 * with remaining sources. A misconfigured directory must NOT block all Skills.
 *
 * <p><b>Return type:</b> {@code List<Skill>} in iteration order (LinkedHashMap
 * preserves insertion order). Stable output → {@code ToolRegistry.modelVisibleSpecs()}
 * → PromptBuilder prompt cache hits (cf. Story #009d design philosophy).
 *
 * <p><b>API divergence from dsh L4229:</b> the dsh reference shows
 * {@code discover(AgentConfig cfg)}. This Story uses {@code discover(SkillSourceProperties props)}
 * because {@code AgentConfig} is the runtime immutable — not available at Spring
 * startup. {@code SkillSourceProperties} is the Spring-bound config — available
 * via @ConfigurationProperties injection. A future Story may add an overload
 * for runtime re-discovery (e.g. hot-reload).
 */
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
     *
     * @param props bound {@code agent.skills.*} configuration (null-safe → empty list)
     * @return list of Skills, deduplicated by name (first-wins)
     */
    public List<Skill> discover(SkillSourceProperties props) {
        if (props == null || props.getSources() == null || props.getSources().isEmpty()) {
            LOG.debug("CompositeSkillLoader.discover — no sources configured, returning empty");
            return Collections.emptyList();
        }
        Map<String, Skill> byName = new LinkedHashMap<>();
        for (SkillSourceProperties.SourceEntry src : props.getSources()) {
            SkillSource resolved;
            try {
                resolved = router.resolve(src.getType(), src.getLocation());
            } catch (IllegalStateException e) {
                LOG.warn("SkillSource.resolve failed: type={} location={} — {}",
                    src.getType(), src.getLocation(), e.getMessage());
                continue;
            }
            List<Skill> skills;
            try {
                skills = resolved.discover();
            } catch (IOException e) {
                LOG.warn("SkillSource.discover failed: type={} location={} — {}",
                    src.getType(), src.getLocation(), e.toString());
                continue;
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
            result.size(), props.getSources().size(), sortedNames(result));
        return result;
    }

    private static List<String> sortedNames(List<Skill> skills) {
        List<String> names = new ArrayList<>();
        for (Skill s : skills) names.add(s.name());
        Collections.sort(names);
        return names;
    }
}
```

**关键约束**:
- `discover(props)` 取代 dsh L4229 的 `discover(AgentConfig)` — Spring 配置优先(runtime 重载是后续 Story)
- 单 source IOException → `continue`(不阻塞其他 source)
- `LinkedHashMap` 保留发现顺序 → 输出稳定 → prompt cache 命中
- `SkillSourceRouter.resolve` 抛 IllegalStateException 也走 `continue`(允许部分 yml 错配仍能加载部分 Skills)

### 2.8 `SkillAutoConfiguration` 修改(对齐 dsh §6.4 + Story #020a 模板)

```java
package ai.lingshu.core.impl.skill;

import ai.lingshu.core.slot.Skill;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 🆕 Story #020b — extend {@code @Component} Skill registration with
 * Skill-source discovery.
 *
 * <p><b>Two-phase registration (per Story #020a EC-020a-3 first-wins):</b>
 * <ol>
 *   <li><b>Phase 1: source discover.</b> Call {@code loader.discover(props)}
 *       → register each into {@link ToolRegistry}. Order: yml-defined source order,
 *       so classpath-before-directory means classpath SKILL.md wins for same-name Skills.</li>
 *   <li><b>Phase 2: @Component Skills.</b> Register all Spring-managed
 *       {@code Skill} beans. {@code ToolRegistry.register} uses
 *       {@code ConcurrentHashMap.putIfAbsent} — Phase 2 Skills beat Phase 1
 *       (so {@code @Component CommitSkill} wins over
 *       {@code classpath:skills/commit/SKILL.md}).</li>
 * </ol>
 *
 * <p><b>Why this order (not reversed):</b> dsh §6.4 L4378 says "file 覆盖 code" —
 * users typically want to override a built-in {@code @Component} skill with a
 * local SKILL.md. The putIfAbsent semantic (Phase 1 first) gives the OPPOSITE
 * behavior. Therefore we run source discover FIRST, @Component SECOND — making
 * code override file (the desired default). To get file-overrides-code, users
 * add {@code @ConditionalOnMissingBean(Skill.class)} to their @Component (left
 * to user; not in v1 scope).
 *
 * <p><b>Why add {@code @EnableConfigurationProperties}:</b> activates
 * {@link SkillSourceProperties} binding from {@code agent.skills.*} yml.
 */
@Configuration
@EnableConfigurationProperties(SkillSourceProperties.class)
public class SkillAutoConfiguration implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(SkillAutoConfiguration.class);

    public static final String PROP_ENABLED = "agent.skills.enabled";

    private final ToolRegistry toolRegistry;
    private final Map<String, Skill> skills;             // Story #020a — @Component Skills
    private final SkillSourceProperties props;           // 🆕 Story #020b
    private final CompositeSkillLoader loader;           // 🆕 Story #020b
    private final Environment environment;

    @Autowired
    public SkillAutoConfiguration(
            ToolRegistry toolRegistry,
            @Lazy Map<String, Skill> skills,
            SkillSourceProperties props,
            CompositeSkillLoader loader,
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

        // 🆕 Story #020b — Phase 1: discover Skills from configured sources
        List<Skill> discovered = loader.discover(props);
        for (Skill s : discovered) {
            toolRegistry.register(s);
        }
        if (!discovered.isEmpty()) {
            LOG.info("Discovered {} skill(s) from sources: {}",
                discovered.size(), sortedNames(discovered));
        }

        // Story #020a — Phase 2: register all @Component Skill beans
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
        LOG.info("Skills ready — {} component skill(s) registered: {}",
            registered, sorted);
    }

    private static List<String> sortedNames(List<Skill> skills) {
        List<String> names = new ArrayList<>();
        for (Skill s : skills) names.add(s.name());
        Collections.sort(names);
        return names;
    }
}
```

**关键约束**:
- **顺序固定**:Phase 1 (sources) → Phase 2 (@Component)— 让 `@Component` 胜出同名 source Skill
- `@Lazy` on `Map<String, Skill>` 解决 bean-cycle(对齐 Story #020a L82-95 rationale)
- `@EnableConfigurationProperties(SkillSourceProperties.class)` 启用配置绑定

---

## §3 测试策略(16+ case + 5 EC,L1+L2+L3)

### 3.1 L1 单元测试(单类行为)

**`SkillSourceContractTest`**(接口契约反射验证):
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `interfaceHasFourMethods` | AC-020b-1 | 反射验证 4 方法存在 + return type |

**`SkillSourceProviderContractTest`**(接口契约):
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `interfaceHasTwoMethods` | AC-020b-2 | 反射验证 type() + create(String) |

**`SkillSourcePropertiesTest`**(绑定类):
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `bindFromYaml` | AC-020b-3 | `ApplicationContextRunner` 注入 yml → props.getSources() 读出 |
| `defaultEmptySources` | EC-020b-1 | yml 完全没写 → getSources() 空 list,无 NPE |

### 3.2 L2 slice 测试(单 Slot + 真实依赖)

**`ClasspathSkillSourceTest`**:
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `providerCreate_normalizesLocation` | AC-020b-4 | create("classpath:foo/") 与 create("foo/") 等价 |
| `discover_scansClasspathFixtures` | AC-020b-5 | 在 src/test/resources/skills/test-fixtures/ 放 3 个 SKILL.md,扫出 3 个 Skill |
| `discover_noMatches_returnsEmpty` | EC-020b-2 | 找不到匹配 resource → 空 list,不抛 IOException |

**`DirectorySkillSourceTest`**(@TempDir):
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `providerCreate_normalizesToAbsolutePath` | AC-020b-6 | create("/abs/path") → dir = /abs/path(已 toAbsolutePath)|
| `discover_emptyDir_returnsEmpty` | AC-020b-7 | @TempDir 空 → [] |
| `discover_nonExistentDir_returnsEmpty` | AC-020b-7 | @TempDir/<不存在的子目录> → [] |
| `discover_populatedDir_returnsSkills` | AC-020b-7 | @TempDir/code-review/SKILL.md → 1 个 Skill |
| `discover_skillNameFromDirectoryEntry` | EC-020b-3 | Skill.name() = directory entry 名("code-review")而非文件名 |

**`SkillSourceRouterTest`**:
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `resolve_knownType` | AC-020b-8 | resolve("classpath", "...") + resolve("directory", "...") |
| `resolve_unknownType_throws` | AC-020b-8 | resolve("unknown", "...") 抛 IllegalStateException |
| `springCtxResolvesAllRegisteredProviders` | AC-020b-9 | mini ctx 启动 → 含 2 个 Provider |
| `duplicateType_secondProviderWins_silentOverride` | EC-020b-4 | 手动构造 Router,put 2 个同 type → 后者胜,无异常 |

**`CompositeSkillLoaderTest`**:
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `discover_dedupByPutIfAbsent_firstWins` | AC-020b-10 | 2 个 source 各贡献同名 Skill → first 胜 |
| `discover_emptySources_returnsEmpty` | AC-020b-11 | props.getSources().isEmpty() → [] |
| `discover_nullProps_returnsEmpty` | AC-020b-11 | props = null → [] |
| `discover_singleSourceIOException_continuesWithOthers` | AC-020b-12 | mock IOException → 该 source 跳过,其他 source 继续 |
| `discover_allSourcesFail_returnsEmpty` | EC-020b-5 | 全部 source 失败 → [] |

**`SkillAutoConfigurationDiscoverTest`**(mini ctx 集成):
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `directorySourcePlusComponentSkill_coexist` | AC-020b-13 | yml 1 directory + @Component CommitSkill → skillNames() 包含两者 |
| `componentSkillBeatsSkillTool` | AC-020b-14 | yml 1 classpath source(`commit/SKILL.md`)+ @Component CommitSkill → CommitSkill 胜出 |
| `disabled_doesNotDiscoverOrRegister` | AC-020b-15 | `agent.skills.enabled=false` → skillNames() 空 |

### 3.3 L3 集成测试

**`SkillSourceDiscoveryE2ETest`**:
| 测试方法 | AC 编号 | 描述 |
|---|---|---|
| `modelVisibleSpecs_containsDiscoveredAndBuiltinTools` | AC-020b-16 | `AnnotationConfigApplicationContext` + @TempDir → ToolRegistry.modelVisibleSpecs() 含 source Skill + @Component CommitSkill + 4 内置 Tool,size ≥ 7,字典序排 |

### 3.4 测试预算

| 层级 | case 数 | 文件 |
|---|---:|---|
| L1 Unit | 5 | SkillSourceContractTest + SkillSourceProviderContractTest + SkillSourcePropertiesTest |
| L2 Slice | 17 | ClasspathSkillSourceTest(3)+ DirectorySkillSourceTest(5)+ SkillSourceRouterTest(4)+ CompositeSkillLoaderTest(5)+ SkillAutoConfigurationDiscoverTest(3)|
| L3 Component | 1 | SkillSourceDiscoveryE2ETest |
| **合计** | **≥ 23** | 8 测试类 |

(spec.md 估算 16 + 5 EC = 21,plan.md 加 detail 后 23,**实际 ≥ 23**)

---

## §4 实施顺序(T-NN)

参见 `tasks.md`。简述:

1. **T-01** `SkillSource` interface + `SkillSourceProvider` interface + 2 L1 契约 case
2. **T-02** `SkillSourceProperties` 配置类 + 2 L1 case(`ApplicationContextRunner`)
3. **T-03** `ClasspathSkillSourceProvider` + `ClasspathSkillSource` + fixture + 3 L2 case
4. **T-04** `DirectorySkillSourceProvider` + `DirectorySkillSource` + 5 L2 case(@TempDir)
5. **T-05** `SkillSourceRouter` + 4 L2 case(mini ctx)
6. **T-06** `CompositeSkillLoader` + 5 L2 case(mock IOException)
7. **T-07** `SkillAutoConfiguration` 修改(追加 discover 流程)+ 3 L2 case(mini ctx + @TempDir)
8. **T-08** `SkillSourceDiscoveryE2ETest` L3 集成 + R-13 dep-tree 自查
9. **T-09** 全量回归 + commit + push + PR body

---

## §5 风险与回滚

| 风险 | 概率 | 缓解 |
|---|---|---|
| `classpath*:prefix/*/SKILL.md` 在多层 jar 下扫不到 | 低 | PathMatchingResourcePatternResolver 标准用法 |
| `Path.toAbsolutePath().normalize()` 防 `..` 越权但 sandbox 仍可写 | 中 | 本 Story 范围仅扫目录,sandbox 强制是 RuntimeSandbox 责任 |
| `SkillSourceProperties.@ConfigurationProperties` 与 AgentConfig.Skills 重复 → 概念混淆 | 中 | Javadoc 明示两者关系(Spring binding vs runtime immutable) |
| `SkillAutoConfiguration` Phase 1 vs Phase 2 顺序争议(谁胜出同名 Skill)| 中 | 显式选 Phase 2 `@Component` 胜出(dsh §6.4 L4378 显式建议 file overrides code,但本 Story 用 putIfAbsent 反过来 — Javadoc 解释) |
| R-13 mitigation (d): 引入新 Maven 坐标 | **0 风险** | 全部 Spring/Lombok/Jackson 已锁,无需新依赖(`PathMatchingResourcePatternResolver` 是 spring-core 已含) |

**回滚方案**:`git revert <merge-commit>` + 删除 `ai.lingshu.core.slot.SkillSource`/`SkillSourceProvider` 接口 + `ai.lingshu.core.impl.skill.source` 包 + `SkillAutoConfiguration` 回滚到 #020a 版本。`ToolRegistry` / `Skill` / `SkillTool` / `CommitSkill` 全部不动 → 不影响 #020a 测试。

---

## §6 关键不变项(对照 Story #020a 模板)

- `Skill` interface 不变(Story #020a 不变项)— 仍为 marker
- `SkillTool` 不变 — 完全复用 #020a `fromMarkdown`
- `CommitSkill` 不变 — 完全复用 #020a
- `ToolRegistry` interface 不变 — 仅 `register()` 被调用,无新方法
- `DefaultToolRegistry` 不变 — 仅被注入更多 `register()` 调用
- `Tool` interface / `ToolCall` / `ToolSpec` / `ToolException` 全部不变
- `RuntimeSandbox` / `ProcessRunner` 不变(本 Story 不走 dispatch 流水线)
- 9 Slot SPI 全部不变(本 Story 不新增 Slot,**但**新增 Slot 4 子 SPI:SkillSource + SkillSourceProvider — 子 SPI 与 Slot 概念不同,Skill 仍是 Slot 4 主接口)
- dsh §10.1 锁定 13 项依赖,**0 增量**
- `ToolExecutor` 5 步流水线不变
- §4.10.1 硬规则 1/2/3 不涉及(本 Story 不调 Spring AI / 不调 dispatch)
- `SkillAutoConfiguration` 行为**扩展**,不破坏 — Phase 2 与 #020a 行为相同,Phase 1 是新增

---

**Last updated**: 2026-09-23
**Plan author**: Claude Code
**Reviewer**: 待 PR review
