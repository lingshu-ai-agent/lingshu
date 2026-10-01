# Story #031 `permission-policy-pattern-matching` — Data Model(Pattern Grammar + 5 类别 + 3 段决策)

> **范围**:`PermissionPolicy` SPI 三形式 pattern 通配 + `Tool.sourceCategory()` 默认方法 + 5 个内置 category 契约
> **配套 spec**:[spec.md](spec.md)(Story 整体),[plan.md](plan.md)(文件 / 接口设计),[tasks.md](tasks.md)(P1-P6 任务)

---

## 1. Pattern Grammar(3 类形式)

### 1.1 形式表

| 形式 | 语法 | 匹配规则 | 优先级 | 示例 | 用途 |
|---|---|---|---|---|---|
| **Form 1**(通配) | `*` | 字面 equals `"*"` → 全 Allow / 全 Deny | 最高 | `allow-list: ["*"]` | 一行放开所有 tool |
| **Form 2**(类别前缀) | `<category>:*` | `pattern.endsWith(":*")` + `prefix.equals(toolCategory)` | 中 | `allow-list: [mcp:*, skill:*]` | 按来源类别批量允许 |
| **Form 3**(精确名) | `<exact-name>` | `pattern.equals(toolName)` | 最低 | `allow-list: [read_file, write_file]` | 单 tool 精确控制(back-compat with Story #029)|

### 1.2 形式 2 的边界规则

| pattern | 是否合法 | 说明 |
|---|---|---|
| `mcp:*` | ✅ | 合法 category 前缀 |
| `skill:*` | ✅ | 合法 category 前缀 |
| `local:*` | ✅ | 合法(虽然本地 tool 是默认类别,但 pattern 仍可显式匹配) |
| `a2a:*` | ✅ | 合法(A2A RemoteAgent 类别) |
| `delegate:*` | ✅ | 合法(Delegate 子 Agent 类别) |
| `custom:*` | ✅ | 合法(plugin 自定义 category,见 §5.5) |
| `:*` | ❌ | 空 category(无意义)→ 永远 false |
| `*:*` | ❌ | 不识别(不是 Form 1 也不是合法 Form 2)→ 走 Form 3 字面 equals 永远 false(因为 `*:*` ≠ toolName)|
| `mcp:foo:*` | ❌ | 多层前缀不展开 → 走 Form 3 字面 equals 永远 false |

### 1.3 优先级语义

- **同一列表内**:OR 关系,**第一个命中即返回**(`for` 循环短路)
- **deny-list 与 allow-list 之间**:`StrictPermissionPolicy.check()` 先扫 deny-list(命中 → Deny),再扫 allow-list(命中 → Allow / 否则 → Deny)
- **deny 总是胜过 allow**:deny-list 命中优先级高于 allow-list 任何 pattern

---

## 2. 5 个内置 Source Category

### 2.1 Category 对照表

| Category | 来源 Tool 实现 | 配置 | dsh 引用 |
|---|---|---|---|
| `local` | 默认(本地手写 `Tool` 实现,不 override `sourceCategory()`)| `Tool` interface `default String sourceCategory() { return "local"; }` | §4.6 |
| `mcp` | `McpToolAdapter`(`@Override public String sourceCategory() { return "mcp"; }`)| MCP server 通过 `McpTransport` 暴露的工具 | §6.5 (2) |
| `skill` | `SkillTool`(`@Override public String sourceCategory() { return "skill"; }`)| SKILL.md 文件经 `SkillLoader` 加载 | §6.4 |
| `a2a` | `RemoteAgentTool`(`@Override public String sourceCategory() { return "a2a"; }`)| 远程 A2A agent 经 `A2aTransport` 暴露的 skill | §5.6.3 |
| `delegate` | `DelegateTool`(`@Override public String sourceCategory() { return "delegate"; }`)| `Task("explore" / "engineer" / "reviewer")` 子 Agent | §6.6 |

### 2.2 Plugin 扩展

plugin 作者可自定义 category:

```java
@Component
public class RagTool implements Tool {
    @Override public String name() { return "rag_search"; }
    @Override public String description() { return "RAG search over docs"; }
    @Override public String sourceCategory() { return "rag"; }  // ← 自定义
    // ...
}
```

yml 中即可用 `rag:*` 模式批量控制:
```yaml
tools:
  allow-list: [mcp:*, skill:*, rag:*]   # 包含自定义 category
```

---

## 3. `Tool` SPI 扩展

### 3.1 新增 default method

```java
package ai.lingshu.core.slot;

public interface Tool {
    String name();
    String description();
    String inputSchema();
    ToolResult execute(ToolCall call, ToolExecutionContext ctx);

    /**
     * 🆕 Story #031 — source category for permission policy pattern matching.
     *
     * <p>Five reserved categories (case-sensitive): {@code local / mcp / skill / a2a / delegate}.
     * Plugin authors may override to use a custom string (e.g., {@code "rag"}, {@code "vector-db"}).
     *
     * <p>Default: {@code "local"} (any Tool that doesn't explicitly override is treated as local).
     *
     * <p>Used by {@link PermissionPolicy} implementations (e.g., {@code StrictPermissionPolicy})
     * to resolve {@code "<category>:*"} patterns in {@code tools.allow-list} / {@code tools.deny-list}.
     *
     * <p>dsh reference: §4.6 Tool SPI + §4.7 PermissionPolicy + §5.5 Slot 4 design intent.
     */
    default String sourceCategory() {
        return "local";
    }
}
```

### 3.2 Back-compat 保证

- **零侵入 SPI 扩展**:`default method` 自动被所有现有 `Tool` 实现继承
- **现有 5 个 Tool 实现 override**:`McpToolAdapter` / `SkillTool` / `RemoteAgentTool` / `DelegateTool` / (本地手写 `ReadTool` 等不 override,默认 `"local"`)
- **Story #020a / #020b / #020c / #009c / #023 的所有现有 Tool 测试 0 回归**

---

## 4. `StrictPermissionPolicy` 3 段决策对照表

### 4.1 决策路径

| 顺序 | 检查 | 命中条件 | 决策 | reason 格式 |
|---|---|---|---|---|
| 1 | **deny-list 命中** | `∃ pattern ∈ denyList: PermissionPatterns.matches(toolName, toolCategory, pattern)` | `Deny` | `[LINGS-P01] Tool '<name>' matches deny pattern '<pattern>'` |
| 2 | **allow-list 空** | `allowList.isEmpty()` | `Allow` | `default policy: allow (no allow-list)` |
| 3 | **allow-list 命中** | `∃ pattern ∈ allowList: PermissionPatterns.matches(...)` | `Allow` | `strict policy: allow (matches pattern '<pattern>')` |
| 4 | **无任何命中** | 上 3 段全 false | `Deny` | `[LINGS-P01] Tool '<name>' not in allow-list (category=<cat>)` |

### 4.2 决策伪代码

```java
public Decision check(ToolCall call, ToolExecutionContext ctx) {
    String toolName = call.getName();
    String toolCategory = nameToCategory.getOrDefault(toolName, "local");
    List<String> allowList = tools.getAllowList();
    List<String> denyList = tools.getDenyList();

    // Path 1: deny-list first (deny wins over allow)
    if (denyList != null) {
        for (String pattern : denyList) {
            if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
                return new Decision.Deny(
                    "[LINGS-P01] Tool '" + toolName
                        + "' matches deny pattern '" + pattern + "'");
            }
        }
    }

    // Path 2: empty allow-list → default-allow (zero-config + back-compat)
    if (allowList == null || allowList.isEmpty()) {
        return new Decision.Allow("default policy: allow (no allow-list)");
    }

    // Path 3: first pattern match in allow-list wins
    for (String pattern : allowList) {
        if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
            return new Decision.Allow(
                "strict policy: allow (matches pattern '" + pattern + "')");
        }
    }

    // Path 4: no pattern matched → Deny with category context
    return new Decision.Deny(
        "[LINGS-P01] Tool '" + toolName
            + "' not in allow-list (category=" + toolCategory + ")");
}
```

### 4.3 yml 端到端示例

```yaml
agent:
  permission-policy: strict        # 启用 StrictPermissionPolicy
  tools:
    allow-list:
      - "*"                         # Form 1: 全开(可单独一行)
    # 或:
    allow-list:
      - mcp:*                       # Form 2: MCP 工具全开
      - skill:*                     #        Skill 工具全开
      - a2a:*                       #        A2A 远程工具全开
      - delegate:*                  #        Delegate 子 Agent 全开
      - read_file                   # Form 3: 单 tool 精确控制(覆盖默认)
    deny-list:
      - danger_*                    # 任何 category,tool name 前缀为 danger_ 一律 deny
```

---

## 5. `StrictPermissionPolicyProvider` 启动期构造

### 5.1 关键决策:**NOT @Component**

`StrictPermissionPolicy` 是 **value object**,不是 Spring bean:
- `@Component` 会让 Spring reflection instantiate 无参 ctor,但本类**故意不提供**无参 ctor(`ToolsConfig` 必传)
- Provider 模式:启动期由 `StrictPermissionPolicyProvider.create(AgentConfig)` 显式 new,注入 `ToolsConfig` + `nameToCategory`
- §5.5 多 Provider 模式:`@Bean(name = "permissionPolicyProvider_strict")` 唯一 Bean 名约定

### 5.2 `create()` 实现

```java
@Component
public class StrictPermissionPolicyProvider implements PermissionPolicyProvider {
    private final ToolRegistry toolRegistry;

    @Autowired
    public StrictPermissionPolicyProvider(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public PermissionPolicy create(AgentConfig cfg) {
        Map<String, String> nameToCategory = new HashMap<>();
        for (Tool t : toolRegistry.findAll()) {
            nameToCategory.put(t.name(), t.sourceCategory());
        }
        return new StrictPermissionPolicy(cfg.getTools(), nameToCategory);
    }
}
```

### 5.3 关键时序

1. **Spring startup**:`ToolRegistry` Bean 创建,所有 `@Component Tool` 实现(包括 `McpToolAdapter` / `SkillTool` / `RemoteAgentTool` / `DelegateTool` + 本地)注册进 `ToolRegistry.findAll()`
2. **PermissionPolicyRouter resolve**:`@Autowired PermissionPolicyRouter` 启动期扫描所有 `PermissionPolicyProvider` Bean,按 `name()` 收 `Map<String, PermissionPolicyProvider>`
3. **AgentFactory.create(cfg)**:调 `router.resolve("strict", cfg)` → `StrictPermissionPolicyProvider.create(cfg)` → 构造 `nameToCategory` from `ToolRegistry.findAll()` → `new StrictPermissionPolicy(cfg.getTools(), nameToCategory)`
4. **Turn 运行**:`ToolExecutor.dispatch()` 第 1 步调 `permissionPolicy.check(call, ctx)` → `StrictPermissionPolicy.check()` 走 3 段决策

---

## 6. R-13 mitigation (d):0 Binary Delta 证据

### 6.1 复用清单(全 JDK 8 standard + 已锁 13 项依赖表内)

| Story #031 新增 | 复用 JDK 8 / 已锁依赖 | 新 binary 引入 |
|---|---|---|
| `PermissionPatterns.matches()` | `String.equals` / `String.endsWith` / `String.substring` | **0** |
| `StrictPermissionPolicy.check()` 3 段决策 | `ArrayList` / `HashMap` / `Collections.emptyMap()` | **0** |
| `Tool.sourceCategory()` default method | interface default(JDK 8 standard) | **0** |
| `StrictPermissionPolicyProvider.@Autowired ToolRegistry` | Spring `@Component` + `@Autowired`(已锁) | **0** |
| 5 个 Tool override(`@Override sourceCategory()`)| Lombok `@Getter` / `@ToString`(已锁)| **0** |

### 6.2 强制验证流程

```bash
# 预提交快照
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-031-pre.txt

# 提交 Story #031 代码

# 后提交快照 + diff
mvn -pl lingshu-core dependency:tree -Dverbose > /tmp/lingshu-dep-tree-031-post.txt
diff <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-029-post.txt | sort) \
     <(grep -v "^\[INFO\]    " /tmp/lingshu-dep-tree-031-post.txt | sort)

# 预期:0 新 Maven 坐标(R-13 第 16 次 PASS)
```

---

## 7. Test Fixture 对照(21 case 跨 7 文件)

| 文件 | 层级 | Case 数 | 覆盖 |
|---|---:|---:|---|
| `PermissionPatternsTest` | L1 | 8 | 3 类 pattern 6 case + 2 边界(null pattern / empty pattern)|
| `ToolSourceCategoryTest` | L1 | 5 | 5 个 Tool override + default method |
| `StrictPermissionPolicyPatternTest` | L1 | 6 | 5 pattern case + 1 back-compat 字面 equals |
| `StrictPermissionPolicyReasonTest` | L1 | 1 | reason 字符串含 pattern 信息 |
| `AgentFactoryPatternMatchingIT` | L2 | 1 | `AgentFactory.create()` 真接 `nameToCategory` |
| `DemoProductPermissionWildcardIT` | L3 | 1 | yml `allow-list: ["*"]` 一行解决 12 tool |
| `DemoProductPermissionCategoryPatternIT` | L3 | 6 | 6 case inline 验证精细 pattern 控制 |
| **合计** | | **21** | AC-NN-1 ~ AC-NN-9 全覆盖 |

> Story #029 现有 `StrictPermissionPolicyTest` 5 case 字面 equals 路径 0 回归(back-compat)

---

## 8. 不变项 / Back-compat 保证

| 不变项 | 状态 |
|---|---|
| `PermissionPolicy` SPI 公开方法签名 | 不变(`check(ToolCall, ToolExecutionContext) → Decision`) |
| `Decision` 3 子类(`Allow` / `Deny` / `AskUser`) | 不变 |
| `Tool` SPI 公开方法 | 不变(只加 1 个 `default` 方法,自动 back-compat) |
| `ToolExecutor.dispatch()` 5 步流水线 | 不变(§4.10.1 硬规则 2 守住)|
| `AgentConfig.ToolsConfig.allowList` / `denyList` 字段 | 不变(只改语义,字段类型 `List<String>` 不变)|
| `AgentFactory` SPI(@Autowired 6-Router ctor)| 不动 |
| §4.7 PermissionPolicy 整体架构 | 不变 |
| AuditLogger / Cost 域 | 完全兼容 |
| 9 Slot 体系 | 不变 |
| 24 字段 AgentConfig schema | 不变 |
| JDK 8 兼容 | `String.equals` / `String.endsWith` / `String.substring` / `HashMap` / `Collections.emptyMap()` 全 JDK 8 standard |
| 0 新 Maven 依赖 | ✅ |
| 0 新 ErrorCode | ✅(复用 Story #029 `LINGS-P01`)|
| Story #029 `StrictPermissionPolicyTest` 5 case | ✅ 0 回归 |
| R-13 baseline 镜像 | ✅ 第 16 次 PASS 0 binary delta |

---

**Last updated**: 2026-10-01
**Version**: 1.0 (Story #031 实施完成同期发布)
