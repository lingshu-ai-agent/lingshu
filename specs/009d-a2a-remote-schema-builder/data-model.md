# Data Model: Story #009d a2a-remote-schema-builder

**Story**: Story #009d a2a-remote-schema-builder
**Spec**: [`spec.md`](./spec.md)
**Plan**: [`plan.md`](./plan.md)

---

## 1. 新增类型(1 个)

### RT-01 `RemoteAgentSchemaBuilder`

**路径**: `lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/RemoteAgentSchemaBuilder.java`
**包名**: `ai.lingshu.a2a.client`
**注解**: `@Component`
**可见性**: `public class`

**字段**(1 个,全 `final`):

| 字段 | 类型 | 用途 |
|---|---|---|
| `json` | `ObjectMapper` | Jackson,用于解析 `skill.inputSchema` 字段 + 构造 fallback `ObjectNode` |

**构造器**(1 个):

```java
public RemoteAgentSchemaBuilder(ObjectMapper json) {
    if (json == null) throw new IllegalArgumentException("json must not be null");
    this.json = json;
}
```

**方法**(2 个 public + 4 个 private helper):

| 方法 | 签名 | 行为 |
|---|---|---|
| `buildToolSpecs` | `public List<ToolSpec> buildToolSpecs(List<Map<String, Object>> cards)` | 遍历 cards,生成 `List<ToolSpec>`(每个 skill 一个 ToolSpec,排序,unmodifiable 包装);`cards == null \|\| cards.isEmpty()` → `Collections.emptyList()`;**不**抛异常(纯辅助函数)|
| `describeSpecs` | `public String describeSpecs(List<ToolSpec> specs)` | 调试辅助:返 `String` 形如 `"[N tools]\n  - <name>: <desc80>\n  ..."`;空 list → `"(empty)"` |
| `asString` | `private static String asString(Object o)` | 类型检查 + 转 String;非 String → `null` |
| `truncate` | `private static String truncate(String s, int max)` | 长度截断 + `"..."` 后缀 |
| `formatDescription` | `private static String formatDescription(String skillDesc, String agentName, String agentDesc)` | EC-5/EC-6 美化:空 skillDesc 替换 `"[no description]"`,空 agentDesc 替换 `"[no description]"` |
| `resolveInputSchema` | `private JsonNode resolveInputSchema(Map<String, Object> skill)` | 优先 `skill.inputSchema` 转 JsonNode;fallback `{type:object, additionalProperties:true}` ObjectNode |

**Javadoc 摘要**:

> 启动期扫 `AgentCard.skills[]` 动态生成 `ToolSpec` 列表(dsh §5.6.3.0 L2729-2833)。每个 `AgentCard` 的每个 `skill` 一个 `ToolSpec`:`name = "call_<agentName>_<skillId>"`,`description = <skill.desc> + " (via <agent>: <agent.desc>)"`,`inputSchema = skill.inputSchema`(fallback `{type:object, additionalProperties:true}`)。返回 `List<ToolSpec>` 按 `ToolSpec.name` 字典序升序排序(便于 prompt cache 命中,§4.5.1)。**不依赖** Spring:本类用 `@Component` 但构造无状态,所有方法都是 pure function。

**线程安全**:所有字段 `final`,无 mutable 状态;`buildToolSpecs(...)` 是 pure function(同输入同输出);**不**起线程、不持有 A2aTransport / AgentCardCache / Spring Bean 引用。

---

## 2. 新增 ErrorCode(0 个)

**0 新增 ErrorCode** —— 纯 schema 生成,无 RPC,无新失败路径。所有异常路径走 try-catch `RuntimeException` 吞错 + WARN 日志(FR-002 + EC-4 + EC-11 + NFR-004)。

---

## 3. 修改类型(2 个)

### RT-02 `RemoteAgentTool`(`lingshu-a2a-client/.../RemoteAgentTool.java`)

**修改类型**:构造器扩展 + description() 改写
**来源**:plan.md I-02

**变更点**:

| 变更 | 旧 | 新 |
|---|---|---|
| 字段 | `final A2aTransport transport` + `final ObjectMapper json` | + `final RemoteAgentSchemaBuilder schemaBuilder`(nullable)|
| 构造器 | 仅 2 参 `RemoteAgentTool(A2aTransport, ObjectMapper)` | + 3 参 `RemoteAgentTool(A2aTransport, ObjectMapper, RemoteAgentSchemaBuilder)`;2 参构造器**保留**(向后兼容)|
| 常量 | 无 | + `BASE_DESCRIPTION`(从 #009c 固定字串提取)+ `SCHEMA_BUILDER_HINT`(dev/scope 标注)|
| `description()` | 返固定字串 `"Invoke a skill on a remote A2A agent. Input: {...}."` | 当 `schemaBuilder == null` → 返 `BASE_DESCRIPTION`;当 `schemaBuilder != null` → 返 `BASE_DESCRIPTION + SCHEMA_BUILDER_HINT`(dev/scope,plan.md §5.1);若 `schemaBuilder.buildToolSpecs(...)` 抛 `RuntimeException` → 返 `BASE_DESCRIPTION` + WARN 日志(EC-11 + NFR-004)|
| `name()` | `"remote_agent"` | **不变**(关键不变项 #1)|
| `inputSchema()` | 固定 schema `{agentName, skill, input}` | **不变**(关键不变项 #1)|
| `execute()` | 解析 input JSON + 调 `transport.submit(...)` | **不变**(关键不变项 #1)|

**向后兼容保证**:
- 2 参构造器 `RemoteAgentTool(A2aTransport, ObjectMapper)` **保留** → #009c 已有 4 个 `RemoteAgentToolTest` case + `HttpJsonRpcA2aTransportAutoConfigurationTest.testRemoteAgentToolBeanWiring` 全部不破坏
- `name()` / `inputSchema()` / `execute()` 不变 → 4 case 中 `testExecuteHappyPath` / `testInputSchemaFixedShape` / `testDescriptionNotBlank` 全部不破坏

### RT-03 `HttpJsonRpcA2aTransportAutoConfiguration`(`lingshu-a2a-client/.../HttpJsonRpcA2aTransportAutoConfiguration.java`)

**修改类型**:增加 1 `@Bean` + 改 1 `@Bean` 签名
**来源**:plan.md I-03

**变更点**:

| 变更 | 旧 | 新 |
|---|---|---|
| `@Bean httpJsonRpcA2aTransportProvider()` | 不变 | **不变** |
| `@Bean remoteAgentTool(...)` 签名 | 3 参 `(A2aTransportRouter, AgentConfig, ObjectMapper)` | 4 参 `(A2aTransportRouter, AgentConfig, ObjectMapper, RemoteAgentSchemaBuilder)` |
| `@Bean remoteAgentTool(...)` body | `new RemoteAgentTool(transport, json)` | `new RemoteAgentTool(transport, json, schemaBuilder)` |
| `@Bean remoteAgentSchemaBuilder(...)` | 无 | 新增:`public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json) { return new RemoteAgentSchemaBuilder(json); }` |
| `import ai.lingshu.a2a.client.RemoteAgentSchemaBuilder` | 无 | 新增 |
| `import ai.lingshu.a2a.client.RemoteAgentTool` | 无 | 新增(2 参构造器复用)|

**SPI 注册文件**:
- `META-INF/spring/...imports` **行数不变**(仍 1 行 `ai.lingshu.a2a.client.HttpJsonRpcA2aTransportAutoConfiguration`)

**向后兼容保证**:
- `httpJsonRpcA2aTransportProvider()` Bean **不变** → `testMultiProviderCoexistence` / `testResolveHttpJsonRpc` 不破坏
- `remoteAgentTool` Bean 名 `"remoteAgentTool"` **不变** → 任何 `@Autowired Tool remoteAgentTool` 仍兼容
- 仅增加 1 个新 `@Bean`(`remoteAgentSchemaBuilder`),不破坏现有 Bean

---

## 4. 复用类型(6 个)

| 类型 | 来源 Story | 复用方式 |
|---|---|---|
| `Tool` interface | Story #003 | `RemoteAgentTool implements Tool`(不变) |
| `ToolSpec` `@Value DTO` | `lingshu-core/.../message/ToolSpec.java` | `RemoteAgentSchemaBuilder.buildToolSpecs` 返 `List<ToolSpec>`,构造用 `new ToolSpec(name, description, inputSchema)`(关键不变项 #2)|
| `ToolCall` | Story #001 | `RemoteAgentTool.execute(call, ctx)` 不变 |
| `ToolResult` | Story #001 | `RemoteAgentTool.execute` 返 `ToolResult`,不变 |
| `A2aTransport` interface | Story #009/ #009a | `RemoteAgentTool.execute` 内 `transport.submit(agentName, skill, inputJson)`,不变 |
| `ObjectMapper` Jackson | Spring Boot BOM | `RemoteAgentSchemaBuilder` 接 `ObjectMapper` + `RemoteAgentTool` 已用 |

---

## 5. 类型关系图

```
                    ┌────────────────────────────┐
                    │  RemoteAgentSchemaBuilder  │ (新增, #009d)
                    │   @Component               │
                    │   pure function            │
                    └─────────────┬──────────────┘
                                  │ buildToolSpecs(cards) → List<ToolSpec>
                                  ▼
   ┌──────────────────┐    ┌──────────────────────┐
   │  ToolSpec        │◄───│   buildToolSpecs     │  (reuse, lingshu-core)
   │  @Value          │    │   describeSpecs      │
   │  name/desc/      │    └──────────────────────┘
   │  inputSchema     │
   └──────────────────┘
                                  ▲
                                  │ @Bean wiring (Spring)
                                  │
                    ┌─────────────┴──────────────┐
                    │  RemoteAgentTool           │ (修改构造器 + description)
                    │   implements Tool        │
                    │   name()="remote_agent"  │
                    │   inputSchema() fixed    │
                    │   description() BASE+    │
                    │     SCHEMA_BUILDER_HINT  │
                    └──────────────────────────┘
                                  │
                                  │ execute(call, ctx)
                                  ▼
                    ┌──────────────────────────┐
                    │   A2aTransport (interface)│ (5 方法契约不变)
                    │   fetchCard/submit/get/  │
                    │   cancel/subscribe       │
                    └──────────────────────────┘
```

---

## 6. 字段构造对照表

| 类型 | 旧字段数 | 新字段数 | 字段变化 |
|---|---|---|---|
| `RemoteAgentSchemaBuilder` | 0 | 1(`final ObjectMapper json`)| 全新 |
| `RemoteAgentTool` | 2(`transport` / `json`)| 3(+ `final RemoteAgentSchemaBuilder schemaBuilder`)| +1 nullable |
| `HttpJsonRpcA2aTransportAutoConfiguration` | 0 字段(static class)| 0 字段 | 无 |

---

## 7. 数据流

**输入**:
```
ApplicationContext.startup
   ↓
HttpJsonRpcA2aTransportAutoConfiguration.@Bean remoteAgentSchemaBuilder(json)
   ↓
RemoteAgentSchemaBuilder(json) 实例化
   ↓
HttpJsonRpcA2aTransportAutoConfiguration.@Bean remoteAgentTool(router, cfg, json, schemaBuilder)
   ↓
new RemoteAgentTool(transport, json, schemaBuilder)
   ↓
RemoteAgentTool.description() 调 schemaBuilder(无 cards 来源,本 Story 简化版 — 加 hint)
```

**未来消费方**(`describeSpecs(...)` / `buildToolSpecs(...)` 调用方):
```
PromptBuilder.build() ─────► buildToolSpecs(cards) ───► List<ToolSpec>
                                                            ↓
                                                  [TOOL SCHEMAS] 段
启动日志 ────────────────► describeSpecs(List<ToolSpec>) ──► 启动日志样例
健康检查端点 ────────────► describeSpecs(List<ToolSpec>) ──► /health response
```

---

## 8. 总结

| 维度 | 数量 | 详情 |
|---|---|---|
| 新增类型 | 1 | `RemoteAgentSchemaBuilder`(@Component,pure function,1 字段 + 2 public 方法 + 4 private helper)|
| 新增 ErrorCode | 0 | 纯 schema 生成,无 RPC |
| 修改类型 | 2 | `RemoteAgentTool`(+1 字段 + +1 构造器 + description() 改写)/ `HttpJsonRpcA2aTransportAutoConfiguration`(+1 @Bean + 改 1 @Bean 签名)|
| 复用类型 | 6 | `Tool` / `ToolSpec` / `ToolCall` / `ToolResult` / `A2aTransport` / `ObjectMapper` |

**关键不变项**(本 Story **不**引入新决策):
- `Tool` interface 不变(`name()` / `inputSchema()` / `execute()` 不变)
- `ToolSpec` 不新建(复用 `lingshu-core.message.ToolSpec`)
- `A2aTransport` 5 方法契约不变
- `AgentConfig.A2a` 字段构造不变
- `AgentCard` / `AgentSkill` 字段不变
- SPI 注册文件 `META-INF/spring/...imports` 行数不变
- `RemoteAgentTool` 2 参构造器保留(向后兼容 #009c 测试)