# Data Model: Story #009 a2a-agent-card

**Feature**: Story #009 a2a-agent-card
**Created**: 2026-09-21
**Status**: Complete(本 Story 新增 6 数据类型 + 2 ErrorCode)

---

## 0. 结论

**本 Story 新增**:
- `AgentCard`(主类,Lombok `@Data`)
- `AgentCard.AgentSkill`(nested,`@Value`)
- `AgentCard.AgentCapabilities`(nested,`@Value`)
- `AgentCard.AgentProvider`(nested,`@Value`)
- `AgentCard.SecurityScheme`(nested,`@Value`)
- `AgentConfig.A2a`(`@Value` 嵌套类,加 host + port 字段)
- `LINGS-S06 A2A_SERVER_START_FAILED`(ErrorCode,域字母 S = Slot,编号 06)
- `LINGS-T02 A2A_CARD_INVALID_CONFIG`(ErrorCode,域字母 T = Tool,编号 02)

**本 Story 修改**:
- `AgentConfig` 顶层加 `a2a` 字段 + `getA2a()` 方法(非破坏性)
- `AgentConfig.defaults()` 工厂方法同步加 `new A2a.defaults()`(非破坏性)

**本 Story 复用不改**:
- `AgentConfig.Identity`(L140-152)
- `AgentConfig.Llm` / `.Prompt` / `.Memory` / `.Tenant` 等既有嵌套类
- 9 Slot 接口(全部 stub 不变)
- `SlotRouter` / `SlotProvider` 体系

---

## 1. 新增数据模型

### 1.1 `AgentCard`(主类)

**文件**:`lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/AgentCard.java`

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.ALWAYS)  // null 字段也输出 null 字面量(FR-002)
public class AgentCard {

    /** Agent 名(必填)—— 启动期唯一,本 Story 派生自 cfg.getIdentity().getName() */
    private String name;

    /** Agent 人类可读描述(可空)—— 派生自 cfg.getIdentity().getRole() */
    private String description;

    /** Agent 版本(必填)—— 硬编码 "0.1.0"(D-03 + dsh §0 L1 项目版本对齐) */
    private String version;

    /** Agent 暴露的 skills(本 Story 默认空 list,留 #009c) */
    private List<AgentSkill> skills;

    /** Agent 支持的 capability flags(本 Story 默认 empty(),所有 flag = false) */
    private AgentCapabilities capabilities;

    /** 默认输入 MIME 类型(默认 ["text"],A2A v1.0 spec §2.1 必填) */
    private List<String> defaultInputModes;

    /** 默认输出 MIME 类型(默认 ["text"]) */
    private List<String> defaultOutputModes;

    /** 鉴权模式(v0.5 暂只支持 Bearer,本 Story 字段 = null,留 #009b) */
    private Map<String, SecurityScheme> securitySchemes;

    /** 启用的安全 scheme 引用(本 Story 字段 = null) */
    private List<Map<String, List<String>>> security;

    /** Agent 提供方元数据(可空) */
    private AgentProvider provider;

    /** 文档链接(可空) */
    private String documentationUrl;

    /** 图标链接(可空) */
    private String iconUrl;

    /** 校验 name/skills 至少 1 个 —— 本 Story 不调,留 #009b client 侧过滤用 */
    public boolean isValid() {
        return name != null && !name.isEmpty();
        // 本 Story skills 允许空(skills 默认 []),后续 #009c 加 skills 时此处加 && !skills.isEmpty()
    }
}
```

**字段对齐**:`dsh §5.6.3.0 L2492-2563` `AgentCard` 完整定义。**字段集一致**,**嵌套类型结构**一致,**Jackson 注解**多加了 `@JsonInclude(ALWAYS)` 保证 null 字段输出。

### 1.2 `AgentCard.AgentSkill`

```java
@Value
@NoArgsConstructor(force = true)  // Lombok @Value + Jackson 反序列化需要
@AllArgsConstructor
@Builder
class AgentSkill {
    /** skill 唯一 id —— 与 agentName 组成 call_<name>_<id>(#009b 实现) */
    private String id;
    /** skill 人类可读名 */
    private String name;
    /** skill 描述 —— 给 LLM 决定何时调 */
    private String description;
    /** inputSchema(JSON Schema) —— #009b RemoteAgentSchemaBuilder 不重写 */
    @JsonProperty("inputSchema")
    private JsonNode inputSchema;
    /** outputSchema(JSON Schema,可选) */
    @JsonProperty("outputSchema")
    private JsonNode outputSchema;
    /** skill 关联的 input MIME 类型 */
    private List<String> inputModes;
    private List<String> outputModes;
}
```

**本 Story 不使用**(skills 字段默认 `[]`)。定义为 #009c 留接口。

### 1.3 `AgentCard.AgentCapabilities`

```java
@Value
@NoArgsConstructor(force = true)
@AllArgsConstructor
@Builder
class AgentCapabilities {
    /** 支持 SSE / WebSocket 流式 —— §4.10 LlmProvider.subscribe */
    private boolean streaming;
    /** 支持 push webhook —— §4.10 长连接推送 */
    private boolean pushNotifications;
    /** 支持 stateTransitionHistory —— §6.1 LinearTurnEngine 不需要,DagTurnEngine 用 */
    private boolean stateTransitionHistory;

    public static AgentCapabilities empty() {
        return new AgentCapabilities(false, false, false);
    }
}
```

**本 Story 使用**:`LocalAgentCardGenerator` 生成 `AgentCapabilities.empty()`(FR-005)。

### 1.4 `AgentCard.AgentProvider`

```java
@Value
@NoArgsConstructor(force = true)
@AllArgsConstructor
@Builder
class AgentProvider {
    /** 组织名 —— e.g. "lingshu-ai-agent" */
    private String organization;
    /** 组织 URL —— e.g. "https://lingshu.ai" */
    private String url;
}
```

**本 Story 不使用**(provider 字段 = null)。

### 1.5 `AgentCard.SecurityScheme`

```java
@Value
@NoArgsConstructor(force = true)
@AllArgsConstructor
@Builder
class SecurityScheme {
    /** scheme 类型 —— e.g. "http"/"oauth2"/"openIdConnect" */
    private String type;
    /** scheme 名 —— e.g. "bearer" */
    private String scheme;
    /** bearerFormat —— e.g. "JWT" */
    private String bearerFormat;
    /** OpenID Connect discovery URL —— 仅 type=openIdConnect */
    @JsonProperty("openIdConnectUrl")
    private String openIdConnectUrl;
    /** OAuth2 flows —— 仅 type=oauth2 */
    private Map<String, Object> flows;
}
```

**本 Story 不使用**(securitySchemes 字段 = null)。

### 1.6 `AgentConfig.A2a`

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(L140 之后插入)

```java
@Value
@Builder
public static class A2a {

    /** HTTP server bind host,默认 "0.0.0.0"(US2 + D-09) */
    String host;

    /** HTTP server bind port,默认 8080。范围 0—65535(0 = OS 自动分配,测试用) */
    Integer port;

    public static A2a defaults() {
        return new A2a("0.0.0.0", 8080);
    }
}
```

**顶层字段**(在 `AgentConfig` 加 `a2a` 字段 + `getA2a()`):

```java
A2a a2a;  // 与 llm / prompt / memory / tenant 平级
```

**`AgentConfig.defaults()` 工厂方法**(L233-235 附近)同步加:
```java
public static AgentConfig defaults() {
    return new AgentConfig(
        ...,
        new Tenant.defaults(),
        new A2a.defaults()  // 🆕 Story #009
    );
}
```

**yml 嵌套配置**:
```yaml
agent:
  identity:
    name: my-coding-agent
    role: AI 编码助手
  a2a:
    host: 0.0.0.0
    port: 8080
```

---

## 2. 新增 ErrorCode

### 2.1 `LINGS-S06 A2A_SERVER_START_FAILED`

**域字母**:S = Slot
**编号**:06
**触发条件**:`A2aServer.start()` 抛 `BindException`(端口占用)或 `IllegalArgumentException`(port 越界)/ `UnknownHostException` 等
**携带信息**:
- `message`:人类可读描述(包含 port + cause message)
- `cause`:原始异常(BindException / IllegalArgumentException 等)
- `hint`:修复建议("端口被占用,换一个或停占用进程" / "port 必须在 0—65535 之间")
**抛出位置**:`A2aServer.start()` catch block + `LocalAgentCardGenerator.generate()` 校验 name

**示例 message**:
```
[A2aServer] Failed to start on port 8080: BindException: Address already in use
hint: change 'a2a.server.port' in application.yml or stop the conflicting process
```

### 2.2 `LINGS-T02 A2A_CARD_INVALID_CONFIG`

**域字母**:T = Tool
**编号**:02
**触发条件**:`AgentConfig.identity.name` 为 `null` / 空字符串 / 纯空白
**携带信息**:
- `message`:"AgentConfig.identity.name must not be blank"
- `cause`:`IllegalArgumentException`
- `hint`:"set agent.identity.name in application.yml or use Identity.defaults()"
**抛出位置**:`LocalAgentCardGenerator.generate()` 校验逻辑

---

## 3. 既有数据模型(本 Story 复用,不改)

### 3.1 `AgentConfig.Identity`(L140-152)

```java
@Value
@Builder
public static class Identity {
    String name;
    String role;
    String language;
    List<String> traits;
    String tone;
    String avatar;

    public static Identity defaults() {
        return new Identity("lingShu-agent", null, "auto",
            Collections.emptyList(), null, null);
    }
}
```

**本 Story 读取**:`cfg.getIdentity().getName()` + `cfg.getIdentity().getRole()`。**字段不变,契约不变**。

### 3.2 `AgentConfig.Llm` / `.Prompt` / `.Memory` / `.Tenant`

**全部不动**。本 Story 只新增 `A2a` 嵌套类,与 §7 核心约定「能 @Value 就不用 @Data」「不可变性优先」一致。

### 3.3 9 Slot 接口

**全部不动**。`A2aTransport` 接口仍是 stub(5 方法),本 Story **不**实现 HttpJsonRpcA2aTransport / A2aTransportRouter,留 #009b。

---

## 4. 数据模型变更总结

| 数据模型 | 类型 | 影响 | 不变项 |
|---|---|---|---|
| `AgentCard` + 4 nested type | 新增 | a2a-server 模块新增文件 | core 不变 |
| `AgentConfig.A2a` | 新增 | core 加 1 嵌套类 + 1 顶层字段 | 现有嵌套类不变 |
| `AgentConfig.defaults()` | 修改 | 加 `new A2a.defaults()` 一行 | 现有 defaults 调用顺序不变 |
| `LINGS-S06` / `LINGS-T02` | 新增 ErrorCode | §15 Error Catalog 加 2 行 | 既有 ErrorCode 不变 |
| `AgentConfig.Identity` | 复用 | **不**改 | name/role/language/traits/tone/avatar 字段不变 |
| 9 Slot 接口 | 复用 | **不**改 | A2aTransport 仍是 stub |
| 13 Maven 依赖 | 复用 | **不**改 | 0 新增 |
