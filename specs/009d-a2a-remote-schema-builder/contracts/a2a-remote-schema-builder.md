# Contracts: Story #009d a2a-remote-schema-builder

**Story**: Story #009d a2a-remote-schema-builder
**Spec**: [`spec.md`](./spec.md)
**Plan**: [`plan.md`](./plan.md)

> 本节定义 Story #009d 涉及的契约 ID —— 新增 1 + 影响 1 + 修改 0 = 2 个契约锚点。

---

## 契约清单

| 契约 ID | 类型 | 标题 | 来源 |
|---|---|---|---|
| **AC-RASB-001** | 新增 | `RemoteAgentSchemaBuilder` class contract | 本 Story #009d 新增 |
| **AC-RASB-002** | 影响 | `RemoteAgentTool` constructor contract(扩展)| 本 Story #009d 影响 |

---

## AC-RASB-001:`RemoteAgentSchemaBuilder` class contract(新增)

### 1. 全限定名
- `ai.lingshu.a2a.client.RemoteAgentSchemaBuilder`

### 2. 注解
- `@Component`

### 3. 字段(1 个,全 `final`)

| 字段 | 类型 | 可见性 | 修饰符 |
|---|---|---|---|
| `json` | `com.fasterxml.jackson.databind.ObjectMapper` | `private` | `final` |

### 4. 构造器(1 个,public)

```java
public RemoteAgentSchemaBuilder(ObjectMapper json)
```

**契约**:
- `json == null` → 抛 `IllegalArgumentException("json must not be null")`
- 字段赋值后不可变(无 setter)

### 5. 方法(2 个 public)

#### 5.1 `buildToolSpecs(List<Map<String, Object>> cards) → List<ToolSpec>`

**签名**:
```java
public List<ToolSpec> buildToolSpecs(List<Map<String, Object>> cards)
```

**契约**:
- 输入 `cards == null` → 返 `Collections.emptyList()`(不抛 NPE,EC-1)
- 输入 `cards.isEmpty()` → 返 `Collections.emptyList()`(EC-2)
- 对每张 card:
  - `card.get("name")` 为 null/empty → 跳过该 card(EC-3)
  - `card.get("skills")` 非 `List<?>` 实例 → 跳过该 card(EC-3 + EC-4)
  - 对每个 skill:
    - `skill.get("id")` 为 null/empty → 跳过该 skill(EC-7)
    - 构造 `ToolSpec`:
      - `name = "call_" + agentName + "_" + skillId`
      - `description = formatDescription(skillDesc, agentName, agentDesc)`(EC-5 + EC-6 美化)
      - `inputSchema = resolveInputSchema(skill)`(AC-1.6)
  - 整张 card 解析抛 `RuntimeException` → try-catch 吞错 + WARN 日志(EC-4)
- 输出 `List<ToolSpec>` 按 `name.compareTo(...)` 字典序升序排序(AC-1.7)
- 输出 `Collections.unmodifiableList(...)` 包装(AC-1.8 + NFR-002)
- **不**抛异常给上层(纯辅助函数)

#### 5.2 `describeSpecs(List<ToolSpec> specs) → String`

**签名**:
```java
public String describeSpecs(List<ToolSpec> specs)
```

**契约**:
- 输入 `specs == null` 或 `specs.isEmpty()` → 返 `"(empty)"`
- 否则:返 `String` 形如 `"[<N> tools]\n  - <name1>: <desc80>\n  - <name2>: <desc80>\n  ..."`,`N = specs.size()`,`desc80 = truncate(description, 80)`
- 描述字段截断 80 字符,超过部分用 `"..."` 后缀

### 6. 私有 helper(4 个,private static 或 private)

| 方法 | 签名 | 行为 |
|---|---|---|
| `asString` | `private static String asString(Object o)` | 类型检查 + 转 String;非 String → `null` |
| `truncate` | `private static String truncate(String s, int max)` | `s.length() > max` → `s.substring(0, max - 3) + "..."`;否则 `s`;`s == null` → `""` |
| `formatDescription` | `private static String formatDescription(String skillDesc, String agentName, String agentDesc)` | 空 skillDesc 替换 `"[no description]"`;空 agentDesc 替换 `"[no description]"`;返回 `"<skillDesc> (via <agentName>: <agentDesc>)"` |
| `resolveInputSchema` | `private JsonNode resolveInputSchema(Map<String, Object> skill)` | 优先 `skill.get("inputSchema")` 转 `JsonNode`(Jackson `convertValue`);fallback `{type:object, additionalProperties:true}` ObjectNode |

### 7. 依赖导入

```java
import ai.lingshu.core.message.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
```

### 8. 线程安全

- 所有字段 `final`,无 mutable 状态
- `buildToolSpecs(...)` 是 pure function(同输入同输出)
- **不**起线程、不持有 A2aTransport / AgentCardCache / Spring Bean 引用
- Spring `@Component` 单例安全

---

## AC-RASB-002:`RemoteAgentTool` constructor contract(影响)

### 1. 全限定名
- `ai.lingshu.a2a.client.RemoteAgentTool`(不变)

### 2. 字段(2 → 3)

| 旧字段 | 新字段 |
|---|---|
| `private final A2aTransport transport;` | 不变 |
| `private final ObjectMapper json;` | 不变 |
| — | `private final RemoteAgentSchemaBuilder schemaBuilder;`(nullable)|

### 3. 构造器(1 → 2,向后兼容)

#### 3.1 旧构造器(保留,向后兼容 #009c)

```java
public RemoteAgentTool(A2aTransport transport, ObjectMapper json)
```

**契约**(不变):
- `transport == null` → 抛 `IllegalArgumentException("transport must not be null")`
- `json == null` → 抛 `IllegalArgumentException("json must not be null")`
- `this.schemaBuilder = null`(隐式)

#### 3.2 新构造器(本 Story 扩展)

```java
public RemoteAgentTool(A2aTransport transport, ObjectMapper json, RemoteAgentSchemaBuilder schemaBuilder)
```

**契约**:
- `transport == null` → 抛 `IllegalArgumentException("transport must not be null")`
- `json == null` → 抛 `IllegalArgumentException("json must not be null")`
- `schemaBuilder` 允许 null(等同 2 参构造器)
- 内部 `this.transport = transport; this.json = json; this.schemaBuilder = schemaBuilder;`

### 4. 方法(4 个,1 个改写 + 3 个不变)

#### 4.1 `description()`(改写)

**新契约**:
```java
@Override
public String description() {
    if (schemaBuilder == null) {
        return BASE_DESCRIPTION;
    }
    try {
        return BASE_DESCRIPTION + SCHEMA_BUILDER_HINT;
    } catch (RuntimeException e) {
        log.warn("[RemoteAgentTool] schemaBuilder.buildToolSpecs threw, falling back: {}", e.getMessage());
        return BASE_DESCRIPTION;
    }
}
```

其中:
- `BASE_DESCRIPTION = "Invoke a skill on a remote A2A agent. Input: {\"agentName\":\"<X>\", \"skill\":\"<Y>\", \"input\": {...}}."`(沿用 #009c 固定字串,集中常量)
- `SCHEMA_BUILDER_HINT = " (RemoteAgentSchemaBuilder wired — N-tool schemas pending)"`(dev/scope 标注,plan.md §5.1)

**dev/scope deviation**:`spec.md AC-2.2 / AC-2.3` 描述的完整 skills 列表拼接留 OQ-Future(plan.md §5.1),本期 description 只加 hint,不真拼 skills 列表。

#### 4.2 `name()`(不变)

```java
@Override
public String name() { return "remote_agent"; }
```

#### 4.3 `inputSchema()`(不变)

返回 #009c 固定 schema `{type:object, properties:{agentName, skill, input}, required:[agentName, skill, input]}`(FR-013 + 关键不变项 #1)。

#### 4.4 `execute(ToolCall, ToolExecutionContext)`(不变)

解析 input JSON + 调 `transport.submit(...)` + 异常 → `ToolResult.toolError`(沿用 #009c 行为,关键不变项 #1)。

### 5. 依赖导入(新增)

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

(如已引入,不重复)

### 6. 常量(新增 2 个,private static final)

```java
private static final String BASE_DESCRIPTION = "...";
private static final String SCHEMA_BUILDER_HINT = "...";
```

### 7. 字段(新增 1 个,private final)

```java
private static final Logger log = LoggerFactory.getLogger(RemoteAgentTool.class);
private final RemoteAgentSchemaBuilder schemaBuilder;
```

### 8. 向后兼容保证

- 2 参构造器 `RemoteAgentTool(A2aTransport, ObjectMapper)` **保留** → #009c 已有 4 个 `RemoteAgentToolTest` case + `HttpJsonRpcA2aTransportAutoConfigurationTest.testRemoteAgentToolBeanWiring` 全部不破坏
- `name()` / `inputSchema()` / `execute()` **不变**(关键不变项 #1)
- 2 参构造器实例 description 与 #009c 完全一致(因 `schemaBuilder == null` → 返 BASE_DESCRIPTION)

---

## 影响检查清单

| 现有类型 | 受影响方法/字段 | 影响 |
|---|---|---|
| `RemoteAgentTool` 2 参构造器 | 行为 | 不变(向后兼容)|
| `RemoteAgentTool.name()` | 行为 | 不变 |
| `RemoteAgentTool.inputSchema()` | 行为 | 不变 |
| `RemoteAgentTool.execute()` | 行为 | 不变 |
| `RemoteAgentTool.description()` | 行为 | 改写(2 参构造器实例不变 + 3 参构造器实例加 hint)|
| `RemoteAgentTool` 字段 | 字段 | +1 nullable |
| `HttpJsonRpcA2aTransportAutoConfiguration` | Bean 数量 | +1 `@Bean remoteAgentSchemaBuilder` |
| `HttpJsonRpcA2aTransportAutoConfiguration.remoteAgentTool` | Bean 签名 | 3 参 → 4 参 |
| SPI imports 文件 | 行数 | 不变 |
| `ToolSpec` 类型 | 行为 | 不变(复用)|
| `AgentCard` / `AgentSkill` | 字段 | 不变 |
| `AgentConfig.A2a` | 字段 | 不变 |

---

## 不引入契约(关键不变项)

| 不变项 | 说明 |
|---|---|
| `A2aTransport` 5 方法契约 | 不变 |
| `A2aTransportRouter` 行为 | 不变 |
| `AgentCardCache` 行为 | 不变 |
| `InProcessA2aRegistry` 行为 | 不变 |
| `ToolSpec` DTO 字段 | 不变(关键不变项 #2)|
| `RemoteAgentTool.name()` / `inputSchema()` / `execute()` | 不变(关键不变项 #1)|
| SPI imports 文件行数 | 不变 |

---

## 总结

**新增 1 契约 + 影响 1 契约 + 0 修改契约 = 2 契约 ID**

**关键不变项**:
- `RemoteAgentTool` 的 `name()` / `inputSchema()` / `execute()` **不变**(关键不变项 #1)
- 2 参构造器 `RemoteAgentTool(A2aTransport, ObjectMapper)` **保留**(向后兼容)
- `ToolSpec` 不新建(关键不变项 #2)
- SPI imports 文件行数不变