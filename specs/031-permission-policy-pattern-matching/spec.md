# Story #031 `permission-policy-pattern-matching` — Spec

> **Status**: Draft 2026-09-30
> **Source**: dsh v1.5.47 §4.7 PermissionPolicy + §5.5 Slot 4 `StrictPermissionPolicy` 实实现落地 + ROADMAP §6 主链 #029 follow-up #1(2026-09-30 demo yml 补全 12 个 Tool 发现 pattern matching 缺位,2026-09-30 用户反馈 + AskUserQuestion 选项 1「补全 demo yml + 加 wildcard Story(推荐)」)
> **前置依赖**:`#001` + `#003` + `#004` + `#005` + `#019` + `#022` + `#028` + **`#029` permission-policy-impl**(2026-09-30 已合)—— `StrictPermissionPolicy` 真接通 + `LINGS-P01 PERMISSION_TOOL_NOT_ALLOWED` + `ToolsConfig.allowList` / `denyList` 字段 + `AgentConfig.permissionPolicy` 顶层字段 + `PermissionPolicyRouter` 多 Provider 模式 + demo yml 切 strict —— **9 个 Story 已合**
> **同 Story 拆解**:无。本 Story 是 Story #029 的 pattern-matching 增强,`StrictPermissionPolicy.check()` 接口 0 改动,但内部 3 决策路径从 `List.contains` strict-equals 升级为 pattern matching(`*` 通配 + `<category>:*` 类别前缀)。`Tool` interface 加 1 个 default 方法 `sourceCategory()`(默认 `"local"`),5 个 Tool 实现 override(`McpToolAdapter` / `SkillTool` / `RemoteAgentTool` / `DelegateTool` / 其它可选)。`StrictPermissionPolicy` 持有 `Map<String, String> nameToCategory`(由 `ToolRegistry` 在 `AgentFactory.create()` 时扫描填充)

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**: `dsh_agent_design.md` v1.5.47 §4.7 PermissionPolicy + §5.5 Slot 4 `StrictPermissionPolicyProvider` + §15.4 ErrorCode 域 P 段(`LINGS-P01` 已启用,无需新增)
- **实测发现**:Story #029 合入后(2026-09-30)立即跑 demo-product,user 反馈 yml allow-list 12 个 Tool 必须逐个枚举(MCP `echo` / `timestamp` + Skills `agent` / `help` / `clear` / `compact` + A2A `remote_agent` + Delegate `Task` + 4 个本地),**漏配任一即被 strict deny**(实测日志展示 `Decision.Deny: [LINGS-P01] Tool 'echo' not in allow-list`)
- **业务后果**(当前状态):
  - demo-product 启动时 `@Component Tools` = 4(`read_file` / `write_file` / `list_dir` / `bash_safe`);`McpToolAdapter` 启动期 register = 2(`echo` / `timestamp`);`SkillTool` SKILL.md 加载 = 4(`agent` / `help` / `clear` / `compact`);`RemoteAgentTool` A2A wiring = 1(`remote_agent`);`DelegateTool` = 1(`Task`)—— **合计 12 个 Tool 实例**
  - 现行 `StrictPermissionPolicy.check()` 用 `List.contains` strict equals,**任何 Tool 必须在 allow-list 字面命中**;MCP server 升级新增 tool / 新增 SKILL.md / 新增 RemoteAgent → user 必须手动改 yml,否则 LLM 调新 Tool 立即 `LINGS-P01`
  - `ToolsConfig.allowList` 静态 12 行 list 是 **brittle config**,新增 tool 类别(MCP server / Skill / RemoteAgent)必须改 yml;**与 §8 核心原则「零配置 + 业务配置方只写 YAML」冲突** —— user 不应该知道 Tool 来源分类
  - dsh §5.5 L2168-2189 `StrictPermissionPolicyProvider` design intent 提到「集成 c.getSandbox().getCommandWhitelist() + domainWhitelist 默认白名单」—— 类似思路:**Permission 应该按 Tool 来源类别白名单,而不是逐 Tool 枚举**
- **对应风险**:**R-04**(privilege escalation — 分值 8)+ **R-13** mitigation (d)(R-13 第 16 次 PASS 强制)
- **涉及 ErrorCode**:**0 新 ErrorCode**(复用 Story #029 `LINGS-P01`)

---

## 1. WHY(为什么做这个 Story)

**核心问题**:Story #029 的 `StrictPermissionPolicy.check()` 用 strict equals 匹配 `ToolsConfig.allowList`,但实际生产场景 Tool 来源多元(本地 / MCP / Skill / A2A / Delegate),且 MCP server 升级会动态新增 tool、SKILL.md 可热加载、RemoteAgent 可远程注册 —— **静态枚举 allow-list 与动态 Tool 来源不匹配**。

具体 5 个 gap:

1. **静态枚举 brittleness** —— demo-product 12 个 Tool 必须 12 行 yml;新增 1 个 MCP tool 就要改 yml,否则立即 `LINGS-P01`
2. **类别语义缺失** —— user 想表达「允许所有 MCP tool」「允许所有 Skill」时,**无 pattern 语法支持**;只能逐个列举名字,失去语义聚合
3. **类别元数据缺失** —— `Tool` interface 只有 `name()` / `description()` / `inputSchema()` / `execute()`,**无 `sourceCategory()` 字段**;`StrictPermissionPolicy` 无数据源判断「这个 tool 是 MCP 还是 Skill」
4. **`*` 通配符缺失** —— user 想「允许所有 tool」时只能列出所有 12 个,**无 `*` 通配**;`List.contains("*")` 永 false
5. **demo yml 维护成本** —— 实测补 12 个 Tool 类别注释已经 30 行,Tool 来源越多 yml 越长

**业务后果**:
- 用户**实际**无法用 `permission-policy: strict` 真保护 demo-product(MCP / Skill / A2A / Delegate 4 类 Tool 必须全列,任何遗漏立即 LINGS-P01)
- §14.10 N10 audit-log 设计时 Permission 拒绝事件高频(`LINGS-P01` 在 demo 环境每分钟触发 1-2 次),实际是 yml 漏配,**非**真安全策略
- Story #021c MCP 3 transport + Story #020a/b/c Skill 多源 + Story #009e A2A wiring + Story #023 Delegate 4 件套 — 这些动态 Tool 来源让静态 yml 永远滞后
- §5.5 设计意图「集成 c.getSandbox().getCommandWhitelist() + domainWhitelist 默认白名单」隐含**类别白名单**语义,但当前 `StrictPermissionPolicy` 字面 equals 无法表达

**Story #031 业务价值**:
- `Tool` interface 加 `default String sourceCategory() { return "local"; }` —— 零侵入 default method,5 个 Tool 实现 override(`McpToolAdapter` → `"mcp"` / `SkillTool` → `"skill"` / `RemoteAgentTool` → `"a2a"` / `DelegateTool` → `"delegate"`)
- `StrictPermissionPolicy.check()` 升级为 pattern matching,支持 3 类 pattern:
  - `*` —— 匹配任何 Tool
  - `<name>` —— 字面 equals(现有行为,back-compat)
  - `<category>:*` —— 匹配指定类别所有 Tool(如 `mcp:*` / `skill:*` / `a2a:*` / `delegate:*`)
- `PermissionPatterns` 静态工具类封装 pattern 匹配逻辑 + 单测
- demo yml 改用 pattern:`allow-list: ["*"]`(最简)或 `allow-list: [mcp:*, skill:*, a2a:*, delegate:*, read_file]`(精细)
- §5.5 类别白名单语义真落地;Story #029 yml 静态枚举 brittleness 缓解
- `ToolsConfig.validate()` 不扩,pattern 字符串不做格式校验(留 OQ-Future §6.5)
- `LINGS-P01` ErrorCode 复用,reason 字符串调整含 pattern 信息(`"Tool 'echo' matches deny pattern 'danger_*'"`)

**关键不变项**:
- `PermissionPolicy` interface + `Decision` 4 子类(`Allow` / `Deny` / `AskUser` / `Option`)—— **0 改动**
- `Tool` interface — 加 **1 default method** `sourceCategory()`,默认 `"local"`(零侵入,所有现有 Tool 实现 0 改动即可)
- `StrictPermissionPolicy.check()` 公开方法签名不变(`Decision check(ToolCall, ToolExecutionContext)`),内部实现升级
- `ToolsConfig.allowList` / `denyList` 字段不变,yaml 解析不变(只是字符串从「字面 Tool 名」升级为「pattern 表达式」,**back-compat 守住**:旧 yml 写 `read_file` 仍字面 equals)
- `PermissionPolicyRouter` / `PermissionPolicyAutoConfiguration` / `StrictPermissionPolicyProvider` / `PermissionErrorCodes.LINGS_P01` — **0 改动**
- `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider` — **0 改动**(默认 fallback 仍 AllowAll)
- `AgentConfig` schema 0 改动;`ToolsConfig` schema 0 改动
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变
- `LINGS-P01` ErrorCode 嵌入 message 模式 `"[LINGS-P01] " + reason` 不变(reason 字符串内容升级含 pattern 信息)
- JDK 8 兼容(`Map<String, String>` + `HashMap` + `Collections.emptyMap()` + `String.equals` + `String.startsWith` + `String.endsWith` + `String.substring` 全 JDK 8 standard,**不**引 regex 库 / glob 库 / commons-lang)
- **0 新 Maven 依赖**(`Map` + `HashMap` + `String` JDK 内置)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 给 Agent 配 `allow-list: ["*"]` 一行解决(MCP / Skill / A2A / Delegate 全部自动允许);或精细配 `allow-list: [mcp:*, skill:*, read_file, list_dir]`(Skill 全过 + 2 个本地读类 tool);user **不**需要知道 12 个 Tool 实例名 |
| **多租户平台搭建者** | Tenant Alice 配 `allow-list: [mcp:echo-stdio:*, read_file]` —— 限定只能用 echo-stdio MCP server + 读文件;**未来 MCP server 升级新增 tool 自动过**(零 yml 改动) |
| **运维稳定性关注者(Eve 类)** | yml 改 `allow-list` 后 hot-reload 立即生效(§14.8 N8 已合,本 Story 让 pattern 字符串 hot-reload 真生效);MCP server 升级 / 新增 SKILL.md / RemoteAgent 动态注册 —— **不重启 Agent** 自动遵循 yml pattern |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `PermissionPatterns.matches()` 6 case(`*` 匹配 / `<name>` exact 匹配 / `<category>:*` 匹配 + 不匹配 + 未知 category / 多 pattern 任意命中) + L1 测试覆盖 `StrictPermissionPolicy.check()` 5 case(pattern allow + pattern deny + 类别 pattern + 默认 fallback) + L2 端到端 `PermissionPolicyRouter.resolve("strict", cfg)` 走 pattern 命中 |
| **框架贡献者 / plugin 作者(Bob 类)** | 自定义 Tool 实现只需 `default String sourceCategory() { return "myplugin"; }` 1 行即可让自己的 tool 走 `myplugin:*` pattern;Plugin 命名空间与 5 内置类别(`local` / `mcp` / `skill` / `a2a` / `delegate`)**不冲突**,plugin author 自定义字符串 |
| **安全审计员(Diana 类)** | 配置 `deny-list: ["*dangerous_*", "rm_rf"]` —— 通配 + 字面混合;`LINGS-P01` reason 含具体 pattern 字符串,审计 log 可查「匹配了哪个 pattern」 |

---

## 3. WHAT(交付什么 — 用户视角)

**新行为**:

1. **`Tool` interface 加 1 default method**(`sourceCategory()`):
   ```java
   public interface Tool {
       String CONTRACT_VERSION = "1.0.0";
       String name();
       String description();
       JsonNode inputSchema();
       ToolResult execute(ToolCall call, ToolExecutionContext ctx);

       /**
        * Source category for {@link ai.lingshu.core.permission.PermissionPolicy}
        * category-prefix pattern matching (e.g. "mcp:*" / "skill:*" / "a2a:*").
        *
        * <p>Default "local" — hand-written {@code @Component} Tools don't need to override.
        * Override in adapters (MCP / Skill / A2A / Delegate) to expose the source.
        *
        * <p>Plugin authors may use custom strings (e.g. "myplugin") and define matching
        * patterns in their config.
        */
       default String sourceCategory() { return "local"; }
   }
   ```

2. **5 个 Tool 实现 override `sourceCategory()`**(modify):
   - `McpToolAdapter` → `"mcp"`(在 `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/` package)
   - `SkillTool` → `"skill"`(在 `lingshu-core/src/main/java/ai/lingshu/core/impl/skill/` package;`fromMarkdown` 静态工厂创建实例时设置 category)
   - `RemoteAgentTool` → `"a2a"`(在 `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/` package)
   - `DelegateTool` → `"delegate"`(在 `lingshu-core/src/main/java/ai/lingshu/core/agent/` package)
   - 其它默认 `"local"`(无需 override)

3. **`PermissionPatterns` 静态工具类**(新):
   ```java
   public final class PermissionPatterns {
       private PermissionPatterns() {}

       /**
        * Match a tool name + category against a pattern.
        *
        * <p>3 pattern forms:
        * <ul>
        *   <li>{@code "*"} — matches everything</li>
        *   <li>{@code "<category>:*"} — matches if toolCategory == category</li>
        *   <li>{@code "<exact>"} — exact match against toolName</li>
        * </ul>
        *
        * <p>No regex / glob library — pure JDK 8 String ops.
        */
       public static boolean matches(String toolName, String toolCategory, String pattern) {
           if ("*".equals(pattern)) return true;
           if (pattern.endsWith(":*")) {
               String cat = pattern.substring(0, pattern.length() - 2);
               return cat.equals(toolCategory);
           }
           return pattern.equals(toolName);
       }
   }
   ```

4. **`StrictPermissionPolicy.check()` 升级为 pattern matching**(modify):
   ```java
   public Decision check(ToolCall call, ToolExecutionContext ctx) {
       String toolName = call.name();
       String toolCategory = nameToCategory.getOrDefault(toolName, "local");

       // (a) deny-list wins (highest priority)
       for (String pattern : tools.getDenyList()) {
           if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
               return new Decision.Deny("[LINGS-P01] Tool '" + toolName
                   + "' matches deny pattern '" + pattern + "'");
           }
       }

       // (b) allow-list
       List<String> allowList = tools.getAllowList();
       if (allowList.isEmpty()) {
           return new Decision.Allow("default policy: allow (no allow-list)");
       }
       for (String pattern : allowList) {
           if (PermissionPatterns.matches(toolName, toolCategory, pattern)) {
               return new Decision.Allow("strict policy: allow (matches pattern '" + pattern + "')");
           }
       }

       // (c) allow-list non-empty + no match → deny
       return new Decision.Deny("[LINGS-P01] Tool '" + toolName
           + "' not in allow-list (category=" + toolCategory + ")");
   }
   ```

5. **`StrictPermissionPolicy` 持有 `Map<String, String> nameToCategory`**(modify):
   - 构造器签名扩 `Map<String, String> nameToCategory`(由 `AgentFactory.create()` 扫描 `ToolRegistry` 填充)
   - `StrictPermissionPolicyProvider.create(AgentConfig cfg, ToolRegistry registry)` 扩签名 → 新签名需要 `PermissionPolicyProvider` interface 扩 1 参数
   - **alternative**:Provider 构造时 `@Autowired ToolRegistry` 注入,`create(cfg)` 内部 `registry.findAll().forEach(t -> map.put(t.name(), t.sourceCategory()))` —— **零 interface 改动**,back-compat 最好

6. **`AgentFactory` 真接 category index**(modify):
   - 在 `create(cfg)` 路径上,构造 `StrictPermissionPolicy` 时填充 `nameToCategory`:
     ```java
     Map<String, String> nameToCategory = new HashMap<>();
     for (Tool t : toolRegistry.findAll()) {
         nameToCategory.put(t.name(), t.sourceCategory());
     }
     ```
   - `nameToCategory` snapshot 在 `create(cfg)` 时拍下,`Agent` 4 final 字段期间不变(对齐 §4.1 不变项)
   - `ToolRegistry.findAll()` SPI 扩 —— 需查现有 API 是否已存在;若否,新增 1 method 返回 `Collection<Tool>`

7. **`demo-product` yml 改用 pattern**(modify):
   ```yaml
   allow-list:
     # Catch-all pattern: all Tools pass (本地 + MCP + Skill + A2A + Delegate)
     # 替代 Story #029 静态枚举 12 行(MCP / Skill / A2A / Delegate 4 类新增 tool 自动允许)
     - "*"
   ```
   - 或精细版本:
   ```yaml
   allow-list:
     - mcp:*       # 所有 MCP 工具(包括未来新增的 MCP server)
     - skill:*     # 所有 Skill(包括未来新增的 SKILL.md)
     - a2a:*       # 所有 RemoteAgent
     - delegate:*  # 所有 Delegate sub-agent
     - read_file   # 仅本地读类 tool(精细控制)
   ```

8. **`demo-empty` yml 不动**(保持 `permission-policy: strict` + 无 tool 配置 — 演示 deny 路径)

**新配置参数**:0(无新增 AgentConfig 字段,沿用 `ToolsConfig.allowList` / `denyList` 现有 2 字段,只是字符串语义从「字面 Tool 名」升级为「pattern 表达式」)

**用户会看到的错误码**:复用 Story #029 `LINGS-P01`,reason 字符串升级:

| ErrorCode | 触发条件 | reason 字符串样例 |
|---|---|---|
| **`LINGS-P01`** | yml `permission-policy: strict` + `deny-list: [danger_*]` + 调 `danger_tool` | `"Tool 'danger_tool' matches deny pattern 'danger_*'"` |
| **`LINGS-P01`** | yml `permission-policy: strict` + `allow-list: [read_file]` + 调 `write_file`(无 pattern 命中) | `"Tool 'write_file' not in allow-list (category=local)"` |
| **`LINGS-P01`** | yml `permission-policy: strict` + `allow-list: [mcp:*]` + 调 `read_file` | `"Tool 'read_file' not in allow-list (category=local)"` |

**关键不变量**(不变项):
- `PermissionPolicy` interface + `Decision` 4 子类 — **0 改动**
- `PermissionPolicy.check(ToolCall, ToolExecutionContext) → Decision` 公开方法签名 — **0 改动**
- `ToolsConfig` 字段集(`enabled` / `allowList` / `denyList` / `maxReadBytes` / `maxWriteBytes` 5 字段)— **0 改动**
- `ToolsConfig.defaults()` 行为 — **0 改动**(默认空 allow-list + 空 deny-list → default allow)
- `AgentConfig.permissionPolicy` 顶层字段 — **0 改动**
- `PermissionPolicyRouter` / `PermissionPolicyAutoConfiguration` / `StrictPermissionPolicyProvider` 公开 SPI — **0 改动**
- `AllowAllPermissionPolicy` + `AllowAllPermissionPolicyProvider` — **0 改动**
- `Tool` interface 公开方法集 — **扩 1 default method**(`sourceCategory()`),**不**改现有 4 方法
- 5 个 Tool 实现(`McpToolAdapter` / `SkillTool` / `RemoteAgentTool` / `DelegateTool` / 其它)— **override 1 default method**(`sourceCategory()`),**不**改现有 4 方法
- `LinearTurnEngine` / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变
- `AgentConfig` 不可变契约不变(0 字段新增)
- `LINGS-P01` ErrorCode 嵌入 message 模式 `"[LINGS-P01] " + reason` 不变
- JDK 8 兼容(`Map` / `HashMap` / `Collections.emptyMap()` / `String.equals` / `String.startsWith` / `String.endsWith` / `String.substring` / `String.contains` 全 JDK 8 standard,**不**引 regex 库 / glob 库)
- §15.4 ErrorCode 域字母表不变(本期复用 P01)
- `mvn -pl lingshu-core dependency:tree` 0 binary delta(R-13 mitigation (d) 第 16 次 PASS)

---

## 4. Acceptance Criteria(AC-NN,黑盒可断言)

### AC-NN-1 — `PermissionPatterns.matches()` 3 类 pattern

**Given** `PermissionPatterns` 静态工具类
**When** 测以下 6 case:
1. `matches("read_file", "local", "*")` → `true`
2. `matches("read_file", "local", "read_file")` → `true`
3. `matches("read_file", "local", "write_file")` → `false`
4. `matches("echo", "mcp", "mcp:*")` → `true`
5. `matches("read_file", "local", "mcp:*")` → `false`(category 不匹配)
6. `matches("agent", "skill", "skill:*")` → `true`

**Then** 6 case 全过
**断言方式**:L1 Unit `PermissionPatternsTest` 6 case

### AC-NN-2 — `Tool.sourceCategory()` 5 个实现正确返回

**Given** 5 个 Tool 实现:
- `McpToolAdapter` 包装 `echo`(echo-stdio MCP server)
- `SkillTool`(`fromMarkdown("agent", "...md")`)
- `RemoteAgentTool`(TOOL_NAME = "remote_agent")
- `DelegateTool`(TOOL_NAME = "Task")
- 自定义 `@Component public class MyLocalTool implements Tool`(无 override)

**When** 调用 `tool.sourceCategory()`
**Then** 依次返回 `"mcp"` / `"skill"` / `"a2a"` / `"delegate"` / `"local"`
**断言方式**:L1 Unit `ToolSourceCategoryTest` 5 case(每个 Tool 实例手动 new,调用 sourceCategory() 验证)

### AC-NN-3 — `StrictPermissionPolicy.check()` pattern matching 路径

**Given** `AgentConfig.ToolsConfig` `tools = new ToolsConfig(true, Arrays.asList("mcp:*", "skill:*", "read_file"), Arrays.asList("danger_*"), 200_000, 1_000_000)` + `nameToCategory = {"echo" → "mcp", "agent" → "skill", "read_file" → "local", "danger_tool" → "local", "write_file" → "local"}`
**When** 测以下 5 case:
1. `policy.check(ToolCall("echo"), ctx)` → `Decision.Allow`(matches `mcp:*`)
2. `policy.check(ToolCall("agent"), ctx)` → `Decision.Allow`(matches `skill:*`)
3. `policy.check(ToolCall("read_file"), ctx)` → `Decision.Allow`(matches `read_file` exact)
4. `policy.check(ToolCall("danger_tool"), ctx)` → `Decision.Deny`(matches `danger_*` deny pattern)
5. `policy.check(ToolCall("write_file"), ctx)` → `Decision.Deny`(不在 allow-list)

**Then** 5 case 全过;`reason` 字符串包含 pattern 信息(case 4 含 `"matches deny pattern 'danger_*'"`,case 5 含 `"not in allow-list (category=local)"`)
**断言方式**:L1 Unit `StrictPermissionPolicyPatternTest` 5 case(extend Story #029 `StrictPermissionPolicyTest`,加 pattern 路径)

### AC-NN-4 — `StrictPermissionPolicy.check()` pattern + exact 混合匹配

**Given** `allow-list: [read_file, "mcp:*"]`
**When** 调 `echo`(category=mcp) → matches `mcp:*` → Allow;调 `read_file` → matches exact → Allow;调 `list_dir` → 不 match → Deny
**Then** 3 case 全过
**断言方式**:L1 Unit 3 case

### AC-NN-5 — `StrictPermissionPolicy` back-compat 字面 equals

**Given** Story #029 现有 yml `allow-list: [read_file, write_file, list_dir, bash_safe]`(无 pattern)
**When** `policy.check(ToolCall("read_file"), ctx)` → `Decision.Allow`;`policy.check(ToolCall("unknown_tool"), ctx)` → `Decision.Deny`
**Then** 字面 equals 行为与 Story #029 完全一致(0 回归)
**断言方式**:Story #029 现有 5 case L1 测试 0 回归 + 升级版 `StrictPermissionPolicyTest` 用同样的字面 allow-list

### AC-NN-6 — `AgentFactory.create()` 真接 nameToCategory

**Given** 完整 Spring 上下文:`ToolRegistry` 注册了 5 个 Tool(2 个 McpToolAdapter + 2 个 SkillTool + 1 个本地)
**When** `AgentFactory.create(cfg)` 真构造 `StrictPermissionPolicy`
**Then** `nameToCategory` 包含 5 个 entry,key=tool.name(),value=tool.sourceCategory();`nameToCategory` snapshot 在 `create()` 时拍下,Agent 4 final 字段期间不变
**断言方式**:L2 Slice `AgentFactoryPatternMatchingIT` 1 case(`AnnotationConfigApplicationContext` 装配 + 调 `factory.create(cfg)` + 通过反射读 `StrictPermissionPolicy.nameToCategory` 字段验证)

### AC-NN-7 — demo-product yml `allow-list: ["*"]` 一行解决

**Given** demo-product `application.yml` 改 `allow-list: ["*"]`
**When** Spring Boot 启动 demo-product
**Then** 12 个 Tool(4 本地 + 2 MCP + 4 Skill + 1 A2A + 1 Delegate)全部 `Decision.Allow`;`StrictPermissionPolicy.check(any tool)` 全过
**断言方式**:L3 黑盒 `DemoProductPermissionWildcardIT` 1 case(启动 demo-product + 模拟调所有 12 个 Tool 名 → 全 Allow)

### AC-NN-8 — demo-product yml `allow-list: [mcp:*, skill:*, read_file]` 精细控制

**Given** demo-product `application.yml` 改 `allow-list: [mcp:*, skill:*, read_file]`
**When** 模拟调 `echo`(mcp) → Allow;调 `agent`(skill) → Allow;调 `read_file`(local) → Allow;调 `write_file`(local) → Deny;调 `remote_agent`(a2a) → Deny;调 `Task`(delegate) → Deny
**Then** 6 case 全过
**断言方式**:L3 黑盒 `DemoProductPermissionCategoryPatternIT` 1 case(6 case inline 验证)

### AC-NN-9 — `LINGS-P01` reason 字符串含 pattern 信息

**Given** `permission-policy: strict` + `deny-list: [danger_*]`
**When** 调 `danger_tool`
**Then** `Decision.Deny.reason` 含 `"[LINGS-P01] Tool 'danger_tool' matches deny pattern 'danger_*'"` —— 用户可读 pattern 字符串
**断言方式**:L1 Unit `StrictPermissionPolicyReasonTest` 1 case(AssertJ `hasMessageContaining` 验证)

### AC-NN-10 — R-13 mitigation (d) 依赖零增量

**Given** Story #031 引入 `PermissionPatterns` + `Tool.sourceCategory()` default method + 5 override + `AgentFactory` 扩 nameToCategory 填充 + demo yml 改 pattern
**When** 跑 `mvn -pl lingshu-core dependency:tree -Dverbose`
**Then** 输出与 Story `#029` post-commit 镜像对比,**只能**有 timestamp 差异,无新增 Maven 坐标;`banned-dependencies` enforcer 不 fail
**断言方式**:对照 `specs/029-permission-policy-impl/` PR body 末尾的 `### R-13 dependency:tree 自查` 节
**预期 R-13 第 16 次 PASS 0 binary delta**

### AC-NN-11 — 全 600+ 测试 0 回归

**Given** Story #031 升级 `StrictPermissionPolicy.check()` 内部实现 + `Tool` interface 加 default method + 5 个 Tool override + `AgentFactory` 扩构造 + demo yml 改 pattern
**When** 跑 `mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test`
**Then** Story #029 现有 18 case + Story #031 新 case + 626 pre test 全过(0 fail / 0 error / 0 skipped);Story #029 back-compat 测试(字面 equals)全过;`AllowAllPermissionPolicyProvider` 行为不变
**断言方式**:`mvn -pl lingshu-core test` + `mvn -pl lingshu-examples/demo-product test` 全模块无 fail

---

## 5. 反向 AC(明确不做什么)

| ❌ 不做 | Why |
|---|---|
| 完整 glob 语法(`*` / `[abc]` / `?` / `**`)| KISS,本期只支持 `*` 通配(单层) + `<category>:*` 类别前缀 + 字面 equals;完整 glob 留 OQ-Future §6.5 |
| Regex pattern(`.matches(regex)`)| 不引 regex 库风险,JDK 自带 regex 性能堪忧 + 容易 ReDoS;纯 String ops 已够 |
| 反向 pattern(`^mcp:*` 排除)| KISS,本期只正向匹配;反向用 deny-list 表达 |
| 嵌套 pattern(`mcp:server1:*`)| KISS,本期只到 category 粒度;server 粒度留未来 Story(若 RemoteAgent 数量增加) |
| 多级 category(`a2a:translator:*`)| KISS,plugin author 自定义字符串可实现 `a2a-translator:*` 之类,无需框架支持嵌套 |
| `ToolsConfig.allowList` / `denyList` yml 解析 schema 校验(pattern 格式合法性)| KISS,只做语义匹配,不做格式校验;用户写错 pattern 仅不命中 → Deny 路径,无副作用 |
| `Tool.sourceCategory()` 改为 abstract method 强制 override | default method 零侵入,现有 5+ 个 Tool 实现 0 改动;abstract 会破所有现有实现 |
| `PermissionPolicyProvider.create(AgentConfig cfg)` 扩签名 | 通过 `@Autowired ToolRegistry` 注入 Provider,Provider.create() 内部查 ToolRegistry,**0 interface 改动** |
| `Decision.AskUser` 路径真接通(用户交互 UI)| OQ-Future,留后续 Story(如 `#030 permission-policy-ask-user`)|
| 多个 category 匹配(`mcp,skill:*` 跨类别)| KISS,本期只单 category 匹配;多 category 用 `mcp:*` + `skill:*` 多行表达 |
| 优先级排序(pattern 之间优先级)| 顺序扫描,deny-list 总是 wins(最高优先级),allow-list 按列表顺序 |
| 类别命名空间规范化(强制 5 类)| plugin author 可任意自定义字符串,框架只识别 `local` / `mcp` / `skill` / `a2a` / `delegate` 5 类默认;plugin 自定义 `myplugin:*` 通过 `nameToCategory` 索引自动支持 |
| `LINGS-P02+` ErrorCode 启用 | 本期复用 P01,P02+ 留后续 Story(如 `LINGS-P02 PERMISSION_PATTERN_INVALID` 等)|
| `PermissionPolicyProvider` 多版本并存(strict-1.0.0 + strict-2.0.0)| §5.2 多版本兼容已由 `SlotRouter.version()` 处理,本期只落 strict-1.0.0 升级 |
| 删 `AllowAllPermissionPolicyProvider` | **保留**(`name="default"` + `priority=0`),作为默认 fallback + back-compat(§11 #3 宪章稳定性约束)|

---

## 6. 与其他 Story 的依赖

- **前置 Story**:
  - `#001` zero-config-bootstrap — `Agent` 4 final 字段 + `DefaultAgent.buildContext` 冻结语义
  - `#003` spi-slot-router — `SlotRouter<P,T>` 父类 + 多 Provider 模式 + `PermissionPolicyRouter`
  - `#004` tool-parallel-dispatch — `ToolExecutor.dispatch()` 5 步流水线
  - `#019` built-in-tools — `AgentConfig.ToolsConfig` 5 字段(`allowList` / `denyList` 已落)
  - `#020a/b/c` Skill 3 件套 — `SkillTool` 需 override `sourceCategory()`
  - `#021b/c` MCP tool-adapter — `McpToolAdapter` 需 override `sourceCategory()`
  - `#022` spring-ai-annotation-tool — `SpringAiToolAdapter` 默认 `"local"`(无需 override,因为 @AgentTool 是本地方法)
  - `#023` delegate-sub-agent — `DelegateTool` 需 override `sourceCategory()`
  - `#009e` a2a-remote-tool-wiring — `RemoteAgentTool` 需 override `sourceCategory()`
  - `#025` demo-product — yml 改 pattern
  - **`#028` sandbox-runtime-impl** — `SandboxErrorCodes` precedent + `AccessDeniedException` 模式
  - **`#029` permission-policy-impl** — `StrictPermissionPolicy` 真接通 + `LINGS-P01` + `ToolsConfig.allowList` / `denyList` + `AgentConfig.permissionPolicy` + `PermissionPolicyRouter` 多 Provider 模式 + demo yml 切 strict(本期是 #029 的 pattern-matching 增强)
- **后续 Story(本 Story 是其前置)**:
  - `#030` permission-policy-ask-user(`AskUser` outcome 路径真接通)—— pattern matching 是 ask-user 的基础(ask-user 也按 pattern 决定是否问用户)
  - `#029 follow-up` permission-policy-cli-debug(CLI `--list-permissions` 启动 banner 列出 pattern 解析结果)
  - §14.10 N10 audit-log — Permission 拒绝事件 `LINGS-P01` reason 含 pattern 信息,审计 log 可查「匹配哪个 pattern 拒绝」
  - §14.2 RetryPolicy + §14.3 CircuitBreaker — Permission deny 后 retry / circuit breaker 触发
  - §14.9 TenantContext 多租户 — allow-list / deny-list Tenant 隔离可叠加 pattern(per-tenant pattern override)
  - OQ-Future 完整 glob 语法 / regex / server 粒度 / 反向 pattern —— 视用户实际诉求触发

---

**Spec writer**: Claude Code
**Spec date**: 2026-09-30
**Spec version**: v0.1 Draft