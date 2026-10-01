# Story #032 `web-fetch-local-tool` — Data Model(`WebFetchTool` 4 method + execute 4 段 + 1 MB truncation + 2 catch)

> **范围**:`WebFetchTool` 完整契约(name / description / inputSchema / execute / sourceCategory 默认)+ 1 MB truncation marker + 错误处理 2 catch(AccessDeniedException → [LINGS-S01] / IOException → HTTP fetch failed)
> **配套 spec**:[spec.md](spec.md)(Story 整体),[plan.md](plan.md)(文件 / 接口设计),[tasks.md](tasks.md)(P1-P6 任务),[quickstart.md](quickstart.md)(30 min 跑通)

---

## 1. `WebFetchTool` 完整契约

### 1.1 类签名

```java
package ai.lingshu.core.impl.tool.local;

@Component("webFetchTool")
public class WebFetchTool implements Tool {
    public static final String TOOL_NAME = "web_fetch";
    public static final int DEFAULT_MAX_BYTES = 1_048_576;  // 1 MB
    public static final String TRUNCATION_MARKER = "\n...[truncated, original %d bytes]";
    
    public WebFetchTool() { /* no-arg ctor for Spring @Component instantiation */ }
    
    @Override public String name() { return TOOL_NAME; }
    @Override public String description() { /* 含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported" */ }
    @Override public JsonNode inputSchema() { /* 静态 JSON Schema */ }
    @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) throws ... { /* 4 段 */ }
    // sourceCategory() 不 override —— 走 Tool default "local"(对齐 ReadTool/WriteTool/EditTool/BashTool 约定)
}
```

### 1.2 name() / description() / inputSchema() / sourceCategory() 契约

| Method | 返回值 | 契约 |
|---|---|---|
| `name()` | `"web_fetch"`(snake_case per §6.5 convention) | LLM 视角工具名,`Prompt.tools[].name` 字段 |
| `description()` | 含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported" 的静态字符串 | LLM 视角工具描述,显式声明范围(GET-only)|
| `inputSchema()` | `{ "type": "object", "properties": { "url": {"type": "string"}, "max_bytes": {"type": "integer"} }, "required": ["url"] }` | LLM 视角 JSON Schema,`url` 必填,`max_bytes` 可选 |
| `sourceCategory()` | `"local"`(default, 不 override) | Story #031 `Tool.sourceCategory()` 默认方法,`LocalToolsAutoConfiguration` 注入 5 个 Tool 都默认 `"local"` |
| `execute(call, ctx)` | `ToolResult`(SUCCESS / ERROR) | 4 段:url 校验 → max_bytes 解析 → `ctx.http().get(url)` → truncation |

### 1.3 inputSchema() JSON Schema 细节

```json
{
  "type": "object",
  "properties": {
    "url": {
      "type": "string",
      "description": "HTTP/HTTPS URL to fetch (domain must be in agent.sandbox.domain-whitelist)"
    },
    "max_bytes": {
      "type": "integer",
      "description": "Override default 1 MB truncation cap (default: 1048576)"
    }
  },
  "required": ["url"],
  "additionalProperties": false
}
```

**契约锚点**:
- `url` 必填(`required: ["url"]`)—— 缺则 `ToolResult.error("url is required")`
- `max_bytes` 可选 —— 缺则走 `DEFAULT_MAX_BYTES = 1_048_576`(1 MB)
- `additionalProperties: false` —— LLM 不应传 `url` / `max_bytes` 以外的字段

---

## 2. `execute(call, ctx)` 4 段逻辑

### 2.1 流程图

```
call.getArgs().path("url").asText()
       │
       ▼
  url == null or empty?
       │
       ├── YES → return ToolResult.error("url is required")
       │
       ▼ NO
  Parse max_bytes (if present in args)
       │
       ▼
  ctx.http().get(url)  ←─ WhitelistedHttpClient.check(url) 在内部 enforce
       │
       ├── AccessDeniedException → ToolResult.error("[LINGS-S01] Domain not whitelisted: " + url)
       │
       ▼ OK
  body = String (response body)
       │
       ▼
  body.length() > maxBytes?
       │
       ├── YES → truncate + append TRUNCATION_MARKER (formatted with original bodyLen)
       │
       ▼ NO
  return ToolResult.success(body)
```

### 2.2 段 1:URL 校验

```java
String url = call.getArgs().path("url").asText();
if (url == null || url.isEmpty()) {
    return ToolResult.error("url is required");
}
```

**契约**:缺 `url` 字段 / `url` 空字符串 → ERROR,不等沙箱检查。

### 2.3 段 2:max_bytes 解析

```java
int maxBytes = DEFAULT_MAX_BYTES;
if (call.getArgs().has("max_bytes")) {
    JsonNode mbNode = call.getArgs().get("max_bytes");
    if (mbNode.isInt() || mbNode.isLong()) {
        maxBytes = mbNode.asInt(DEFAULT_MAX_BYTES);
    }
    // 非整数类型 → 静默用 default (defensive, 不抛)
}
```

**契约**:`max_bytes` 字段缺省 = `DEFAULT_MAX_BYTES`(1 MB);显式传 → override;非整数类型 → 静默回退 default。

### 2.4 段 3:`ctx.http().get(url)` 委托 + 错误处理 2 catch

```java
String body;
try {
    body = ctx.http().get(url);
} catch (AccessDeniedException e) {
    // Sandbox domain-whitelist 拒绝
    return ToolResult.error("[LINGS-S01] Domain not whitelisted: " + url);
} catch (IOException e) {
    // 网络错误 / 非 2xx / SSL handshake / DNS resolve 失败
    return ToolResult.error("HTTP fetch failed: " + e.getMessage());
}
```

**`AccessDeniedException`** 来源:`WhitelistedHttpClient.check(url)` 抛 `ai.lingshu.core.slot.AccessDeniedException`(Story #028 落地),message 嵌 `[LINGS-S01]` 前缀。

**`IOException`** 来源:`WhitelistedHttpClient.get(url)` 内部 `HttpURLConnection.getResponseCode() != 200` 抛 `new IOException("HTTP " + code + " " + msg)`(AC-NN-9 L2 IT 验证)。

### 2.5 段 4:Truncation Marker

```java
if (body.length() > maxBytes) {
    body = body.substring(0, maxBytes)
         + String.format(TRUNCATION_MARKER, body.length());
    // marker 模板: "\n...[truncated, original %d bytes]"
    // %d 替换为原始 body.length()(不是 maxBytes, 业务方看真实大小)
}
return ToolResult.success(body);
```

**契约**:
- 截断长度 = `maxBytes`(per-call 或默认 1 MB)
- marker 拼接 `String.format(TRUNCATION_MARKER, body.length())` —— 嵌入**原始**长度(不是截断后长度),业务方看真实大小
- marker template `"\n...[truncated, original %d bytes]"` template 长度 34 字符,`%d` 占位 2 字符 → 实际 marker 长度 = 34 - 2 + `String.valueOf(bodyLen).length()`

---

## 3. `WhitelistedHttpClient.check()` 沙箱守卫契约(Story #028 落地)

### 3.1 check() 流程

```java
public String get(String url) {
    check(url);  // ← 沙箱守卫
    // ... JDK HttpURLConnection / HttpsURLConnection 实际发请求 ...
}

private void check(String url) {
    String host = extractHost(url);  // e.g. "localhost" / "api.openai.com"
    if (!whitelist.contains(host)) {
        throw new AccessDeniedException("[LINGS-S01] Domain not whitelisted: " + url);
    }
}
```

**契约**:
- `whitelist` 来源:`agent.sandbox.domain-whitelist` yml 字段(`@TestPropertySource` 可 override)
- 空 whitelist → 任何 host 都拒绝
- whitelist 命中 → 走 JDK `HttpURLConnection` / `HttpsURLConnection`
- User-Agent 自动 stamp `"ChaOS-LingShu-Sandbox/1.0"`(AC-NN-6 L2 IT 验证)

### 3.2 yml 绑定

```yaml
agent:
  sandbox:
    domain-whitelist:
      - github.com
      - maven.aliyun.com
      - api.openai.com
      - api.anthropic.com
      - raw.githubusercontent.com
      - huggingface.co
      - localhost   # ← for IT, remove in prod
```

`@TestPropertySource(properties = {"agent.sandbox.domain-whitelist=localhost"})` 覆盖 production yml 7 个 domain,只留 localhost 让 L3 IT 断言清晰。

---

## 4. `LocalToolsAutoConfiguration` 自动注册契约(Story #019 落地)

### 4.1 Bean 注册

```java
@AutoConfiguration
public class LocalToolsAutoConfiguration {
    public LocalToolsAutoConfiguration(
            DefaultToolRegistry registry,
            RuntimeSandbox sandbox,
            BashTool bash,
            Map<String, Tool> tools,           // ← Spring 自动注入所有 @Component Tool
            Environment environment) {
        // ...
    }
    
    public void afterPropertiesSet() {
        // 遍历 tools map,调 registry.register(tool)
        // Bean 名 ("webFetchTool") ≠ Tool name ("web_fetch"),但 Tool.name() 是 LLM 视角唯一标识
    }
}
```

**契约**:
- `Map<String, Tool> tools` 注入所有 `@Component implements Tool` 的 bean(WebFetchTool 自动接入,**无需** `@Bean public Tool webFetchTool()` 显式注册)
- `registry.asMap()` containsKeys 用 `Tool.name()` 而非 Bean 名 → 5 个 Tool 名字 = `("Read", "Write", "Edit", "Bash", "web_fetch")`
- 本 Story `LocalToolsAutoConfiguration.java` **0 改动**(spec §4 T02 原本建议加 `@Bean public Tool webFetchTool()`,实测发现 Map<String, Tool> autowiring 已经覆盖,**cleaner**)

### 4.2 5 个 Tool 名字集合

| Bean name | Tool.name() | sourceCategory() | Story |
|---|---|---|---|
| `readTool` | `"Read"` | `"local"`(default)| #019 |
| `writeTool` | `"Write"` | `"local"`(default)| #019 |
| `editTool` | `"Edit"` | `"local"`(default)| #019 |
| `bashTool` | `"Bash"` | `"local"`(default)| #019 |
| `webFetchTool` | `"web_fetch"` | `"local"`(default,**不** override)| #032 |

---

## 5. 错误码契约(LINGS-S01 + 复用)

| ErrorCode | 来源 | 触发条件 | 文案 |
|---|---|---|---|
| `LINGS-S01` | Story #028 `AccessDeniedException` | `WhitelistedHttpClient.check(url)` 拒绝 host | `"[LINGS-S01] Domain not whitelisted: <url>"` |
| **(无新 ErrorCode)** | —— | HTTP 非 2xx / IOException | `"HTTP fetch failed: <exception message>"` |

**契约**:
- **0 新 ErrorCode**(复用 Story #028 已落的 `LINGS-S01`,Sandbox 域 S 段 1 号)
- IO 错误(`HTTP fetch failed: ...`)不嵌 ErrorCode 前缀 —— 业务方看文案判断是 network issue 还是 sandbox denial

---

## 6. 测试契约矩阵

| AC | 测试类 | case 数 | 验证 |
|---|---|---|---|
| AC-NN-1 | WebFetchToolTest | 1 | `name()="web_fetch"` |
| AC-NN-2 | WebFetchToolTest | 1 | description 含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported" |
| AC-NN-3 | WebFetchToolTest | 1 | inputSchema: url string required + max_bytes integer optional |
| AC-NN-4 | WebFetchToolTest | 1 | `sourceCategory()="local"`(default) |
| AC-NN-5 | WebFetchToolHttpServerIT | 1 | 200 hello world → success |
| AC-NN-6 | WebFetchToolHttpServerIT | 1 | User-Agent `"ChaOS-LingShu-Sandbox/1.0"` 透传 |
| AC-NN-7 reverse | WebFetchToolTest | 1 | empty whitelist → `[LINGS-S01]` |
| AC-NN-8 | WebFetchToolHttpServerIT | 1 | localhost whitelist 命中 |
| AC-NN-9 | WebFetchToolHttpServerIT | 1 | HTTP 404 → `"HTTP fetch failed: ..."` |
| AC-NN-10 | WebFetchToolTest | 1 | 2 MB body → 1 MB + truncation marker |
| AC-NN-11 | WebFetchToolTest | 1 | 1 KB body + max_bytes=100 → 100 + marker |
| AC-NN-12 | WebFetchToolTest | 1 | missing url → `"url is required"` |
| AC-NN-13 | LocalToolsAutoConfigurationWebFetchIT | 1 | 5 Tool 注册到 registry |
| AC-NN-14 | LocalToolsAutoConfigurationWebFetchIT | 1 | findByName("web_fetch") 返 WebFetchTool + sourceCategory="local" |
| AC-NN-15 | DemoProductWebFetchIT | 1(2 半)| demo yml 双半同 case(localhost allow + example.com deny)|

**总计:15 new case,0 回归**

---

## 7. 关键不变项(SPI 不变 + JDK 8 兼容)

| 项 | 状态 | 说明 |
|---|---|---|
| `Tool` SPI | 不变 | `sourceCategory()` 是 Story #031 加的 default 方法,本 Story 不 override |
| `ToolExecutor.dispatch()` | 不变 | §4.10.1 硬规则 2 守住,WebFetchTool 走 5 步流水线 |
| `WhitelistedHttpClient` SPI | 不变 | Story #028 落地,本 Story 是 idle 基建激活 |
| `RuntimeSandbox.http()` | 不变 | Story #028 落地,返 `WhitelistedHttpClient` |
| `AccessDeniedException` SPI | 不变 | Story #028 落地,`[LINGS-S01]` 前缀不变 |
| `LocalToolsAutoConfiguration` | 0 改动 | Map<String, Tool> autowiring 自动接住 |
| `AgentConfig` 不可变契约 | 不变 | 0 字段改动 |
| `AgentFactory` SPI | 不变 | @Autowired 6-Router ctor **不动** |
| 9 Slot 体系 | 不变 | WebFetchTool 仍是 Slot 2 Tool |
| JDK 8 兼容 | 守住 | `com.sun.net.httpserver.HttpServer` JDK 内置 + `HttpURLConnection` / `HttpsURLConnection` JDK 内置 + `Jackson ObjectMapper` 已锁 + `Mockito` + `AssertJ` 已锁;no `var` / `List.of` / sealed / records |
| Maven 依赖 | 0 新增 | R-13 mitigation (d) 第 17 次 PASS 0 binary delta |
| ErrorCode | 0 新增 | 复用 `LINGS-S01` |

---

## 8. 时间戳

- **创建**:2026-10-01
- **最后更新**:2026-10-01
- **对应 dsh 版本**:v1.5.48
- **对应 dsh changelog 行**:v1.5.48 → v1.5.49(待 T-doc-6 落地)
