# Story #031 `permission-policy-pattern-matching` — Quickstart(30 min)

> **面向 Alice(Java 后端开发,用过 Spring Boot,首次接触 LingShu)**
> **目标**:30 分钟内跑通"PermissionPolicy 三形式 pattern 通配(`*` / `<exact>` / `<category>:*`)+ `Tool.sourceCategory()` 默认方法"链路
> **前置**:`#029` 已合(StrictPermissionPolicy 落地 + `LINGS-P01` + yml `tools.allow-list` / `tools.deny-list` binding)+ JDK 8+ + Maven 3.6.3+

---

## 0. 学完能做什么

- 理解 LingShu §4.7 PermissionPolicy SPI 的"3 段决策 + pattern 通配"扩展
- 看懂 `PermissionPatterns.matches(toolName, toolCategory, pattern)` 3 类 pattern 形式(`*` 通配 / `<category>:*` 类别前缀 / `<exact-name>` 字面 equals)
- 看懂 `Tool.sourceCategory()` 默认方法 + 5 个内置 category(`local` / `mcp` / `skill` / `a2a` / `delegate`)
- 看懂 `StrictPermissionPolicy.check()` 3 段决策路径(deny-list 优先 / allow-list 空全开 / allow-list 命中 OR 否则 deny)
- 看懂 `StrictPermissionPolicyProvider.create(AgentConfig)` 启动期构造 `nameToCategory` Map,为什么不能用 `@Component`
- 跑一次 L1 unit + L2 IT + L3 黑盒,验证 21 case 全过 + R-13 0 binary delta 第 16 次

---

## 1. 5 步跑通(每步 5 min)

### 步骤 1(5 min):克隆主仓 + 看 Permission 域顶层 layout

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
ls lingshu-core/src/main/java/ai/lingshu/core/impl/permission/
```

**预期看到 6 个 Permission 域文件**:
```
AllowAllPermissionPolicy.java            ← Story #029 默认(yolo)实现
AllowAllPermissionPolicyProvider.java    ← 默认 Provider
PermissionPatterns.java                  ← 🆕 Story #031 3 类 pattern 静态工具
StrictPermissionPolicy.java              ← 🆕 Story #031 升级:3 段决策 pattern matching
StrictPermissionPolicyProvider.java      ← 🆕 Story #031 注入 ToolRegistry 构建 nameToCategory
PermissionPolicyAutoConfiguration.java   ← Spring @Configuration 装配
... + ai/lingshu/core/permission/PermissionErrorCodes.java(LINGS-P01 常量)
```

### 步骤 2(5 min):跑 L1 单元测试,验证 PermissionPatterns 3 类 pattern

```bash
mvn -pl lingshu-core test -Dtest=PermissionPatternsTest
```

**预期看到 6 个 PASS(AC-NN-1 形式 pattern + 2 边界 case)**:
```
✅ case 1: matches("read_file", "local", "*") → true
✅ case 2: matches("read_file", "local", "read_file") → true(字面 equals)
✅ case 3: matches("read_file", "local", "write_file") → false(字面 mismatch)
✅ case 4: matches("echo", "mcp", "mcp:*") → true(category 命中)
✅ case 5: matches("read_file", "local", "mcp:*") → false(category 不匹配)
✅ case 6: matches("agent", "skill", "skill:*") → true(skill category 命中)
✅ 边界: matches("any", "local", null) → IllegalArgumentException
✅ 边界: matches("any", "local", "") → false(空 pattern 不算 "*" 也不算 category)
```

每个 case 直接调 `PermissionPatterns.matches(toolName, toolCategory, pattern)` 三参静态方法,断言 `boolean` 返回值。

### 步骤 3(5 min):跑 L1 单元测试,验证 StrictPermissionPolicy 3 段决策

```bash
mvn -pl lingshu-core test -Dtest=StrictPermissionPolicyPatternTest
```

**预期看到 5 case pattern + 1 case back-compat(AC-NN-3 + AC-NN-4 + AC-NN-5)**:
```
✅ case 1: allow-list: [mcp:*, skill:*, read_file] + check(ToolCall("echo")) → Allow (matches pattern 'mcp:*')
✅ case 2: check(ToolCall("agent")) → Allow (matches pattern 'skill:*')
✅ case 3: check(ToolCall("read_file")) → Allow (matches pattern 'read_file')
✅ case 4: deny-list: [danger_*] + check(ToolCall("danger_tool")) → Deny + reason "[LINGS-P01] Tool 'danger_tool' matches deny pattern 'danger_*'"
✅ case 5: check(ToolCall("write_file")) → Deny + reason "[LINGS-P01] Tool 'write_file' not in allow-list (category=local)"
✅ case 6(back-compat): Story #029 字面 equals 5 case 全过 0 回归(StrictPermissionPolicyTest 复用)
```

每个 case 都构造 `StrictPermissionPolicy(toolsConfig, nameToCategory)`,然后调 `policy.check(call, ctx)` 拿 `Decision`,断言 subclass + reason 字符串含期望 pattern。

### 步骤 4(5 min):看 PermissionPatterns 核心 ~30 行

```bash
sed -n '/public static boolean matches/,/^    }/p' \
  lingshu-core/src/main/java/ai/lingshu/core/impl/permission/PermissionPatterns.java
```

**预期看到 3 段**:
```java
// (a) Form 1: literal "*"
if ("*".equals(pattern)) {
    return true;
}

// (b) Form 2: "<category>:*" → endsWith(":*") + substring + equals(toolCategory)
if (pattern.endsWith(":*")) {
    String prefix = pattern.substring(0, pattern.length() - 2);
    if (prefix.isEmpty()) {
        return false;  // 拒绝 ":*"(无 category)
    }
    return prefix.equals(tc);
}

// (c) Form 3: strict-equals (back-compat with Story #029)
return pattern.equals(tn);
```

> 📌 关键设计:**纯 JDK 8 String ops**(无 regex / 无 glob 库),Story #031 的 R-13 mitigation (d) 强制要求 0 binary delta

### 步骤 5(5 min):看 5 个 Tool override 对照表

| Tool 实现 | 类 | `sourceCategory()` 返回 | 引用 dsh |
|---|---|---|---|
| 默认(本地手写 Tool) | `Tool` interface default method | `"local"` | §4.6 |
| MCP 暴露 | `McpToolAdapter` | `"mcp"` | §6.5 (2) |
| Skill 文件 | `SkillTool` | `"skill"` | §6.4 |
| A2A RemoteAgent | `RemoteAgentTool` | `"a2a"` | §5.6.3 |
| Delegate 子 Agent | `DelegateTool` | `"delegate"` | §6.6 |
| Plugin 自定义 | 任意 `class MyTool implements Tool` | `default "local"`(可 override) | §5.5 |

> 📌 关键设计:`sourceCategory()` 是 `Tool` interface 的 **default method**(零侵入 SPI 扩展,back-compat 100%)。Story #031 之前所有 Tool 实现 0 改动,自动继承 `"local"` 类别。

---

## 2. 进阶 30 min:扩展到你的自定义 PermissionPolicy

### 场景:Role-Based 策略(基于 yml `roles` 配置)

**Role-Based 决策** —— yml 配 `roles: { admin: ["*"], user: [read_file, list_dir], guest: [] }`,根据 user role 决定 effective allow-list。

**做法**:
1. 新建 `RoleBasedPermissionPolicy implements PermissionPolicy`(纯 POJO,无 `@Component`,与 `StrictPermissionPolicy` 同款)
2. 构造器收 `AgentConfig.ToolsConfig tools` + `Map<String, String> nameToCategory` + `String userRole` + `Map<String, List<String>> roleToEffectiveAllowList`
3. `check()` 3 段决策复用 `PermissionPatterns.matches()` —— 只换 effective allow-list 来源
4. 新建 `RoleBasedPermissionPolicyProvider implements PermissionPolicyProvider`(`name="role-based"` + `priority=20`)
5. `RoleBasedPermissionPolicyAutoConfiguration` 加 `@Bean(name = "permissionPolicyProvider_role-based")`(§5.5 多 Provider 模式 + 唯一 Bean 名约定)
6. yml `permission-policy: role-based` 切到你的 Provider

### 验证你的实现

```bash
# 1. L1 单元(复用 PermissionPatternsTest 12 case + 你的 RoleBased 测试)
mvn -pl lingshu-core test -Dtest=RoleBasedPermissionPolicyTest

# 2. L2 IT(your Provider 与 strict / default 共存,Router 按 name 路由)
mvn -pl lingshu-core test -Dtest=PermissionPolicyRouterRoleBasedIT

# 3. R-13 mitigation (d) baseline 镜像
mvn -pl lingshu-core dependency:tree > /tmp/post.txt
diff /tmp/pre.txt /tmp/post.txt | grep -v "lastUpdated" | wc -l
# 预期:0(只有时间戳差异,0 binary delta)
```

---

## 3. 故障排查

### Symptom:`StrictPermissionPolicy.check()` 永远 Allow,deny-list 无效

**Root cause**:yml key 拼错。检查:
   - `permission-policy: strict` 顶层 key 是否正确?(若 `name=default` 走 AllowAllPolicy)
   - `tools.deny-list: [...]` 是否在 yml 第二级缩进?(不是 `agent.tools.deny-list`)
   - `deny-list` 元素是否被 `PermissionPolicy` 默认空 list bypass?(若缺 `:` 整个 list 不绑)

### Symptom:`sourceCategory() == "local"` 即使 Tool 是 McpToolAdapter

**Root cause**:`ToolRegistry.findAll()` 没被调,或 `nameToCategory` Map 漏填充。检查:
   - `StrictPermissionPolicyProvider` 是否 `@Autowired ToolRegistry`?
   - `ToolRegistry.findAll()` 是否在 `DefaultToolRegistry` 有 `@Override`?
   - 测试是否手工 new `StrictPermissionPolicy(tools)`(走 1-arg ctor,空 `nameToCategory`)?

### Symptom:R-13 baseline 镜像有 binary delta

**Root cause**:引入了新 Maven 依赖或第三方 pattern 库。检查:
   - `pom.xml` 是否手加了 `org.apache.commons:commons-lang3` / `com.github.f4b6a3:uuid-creator` / `org.springframework:spring-regex`?
   - Story #031 强制纯 JDK 8 String ops(`String.equals` / `String.endsWith` / `String.substring`)—— 不需要 regex 不需要 glob
   - 误用 `var` / `List.of` / sealed / records 等 JDK 9+ API 编译失败(不是 binary delta,是编译失败)

### Symptom:`category=local` 出现在 reason 中,即使 Tool 是 McpToolAdapter

**Root cause**:`@Component` 误标到 `StrictPermissionPolicy`。检查:
   - `StrictPermissionPolicy` 类级别**没有** `@Component` 注解?
   - Spring 直接 instantiate `StrictPermissionPolicy` 无参 ctor 失败,但 fallback 拿不到 `nameToCategory`?
   - 正确路径:`StrictPermissionPolicyProvider.create(AgentConfig)` 显式 new `StrictPermissionPolicy(tools, nameToCategory)`,`nameToCategory` 由 `ToolRegistry.findAll()` 填充

---

## 4. 下一步

- 📖 详细数据模型(pattern grammar 形式表 + 5 类 category + 3 段决策对照):[data-model.md](data-model.md)
- 📖 Story spec 全集(WHO/WHAT/AC/反向 AC):[spec.md](spec.md)
- 📖 Implementation tasks(P1—P6):[tasks.md](tasks.md)
- 📖 Plan 详细(接口 / 文件 / 测试策略):[plan.md](plan.md)

---

**Last updated**: 2026-10-01
**Version**: 1.0 (Story #031 实施完成同期发布)
