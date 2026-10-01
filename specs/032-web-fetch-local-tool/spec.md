# Story #032 `web-fetch-local-tool` — Spec

> **Status**: Draft 2026-10-01
> **Source**: dsh v1.5.47 §6.3 ChrootRuntimeSandbox `http()` 子能力 + §7 决策 12 Claude Code parity + **2026-10-01 用户反馈**「Claude Code 内部有内置 WebFetch 工具,LingShu 也应该有一个 http() → WebFetchTool 对称」(memory `feedback-mcp-covers-http-not-local-tool.md` 关键架构教训)
> **前置依赖**:`#001` + `#003` + `#004` + `#019` + `#028`(sandbox-runtime-impl)+ `#031`(permission-policy-pattern-matching)`Tool.sourceCategory()` 默认 `"local"` —— **6 个 Story 已合**
> **同 Story 拆解**:无。本 Story 与 #033(mcp-http-domain-guard,Path B + Mitigation 1)分离,各自 ≤ 5 文件,边界干净

---

## 状态

[ ] Draft  [x] Specified  [ ] Planned  [ ] Tasks Ready  [ ] In Progress  [ ] Validated  [ ] Merged

---

## 来源

- **设计文档**:`dsh_agent_design.md` v1.5.47 §6.3 ChrootRuntimeSandbox + §7 决策 12 Claude Code parity + §15 ErrorCode 域 S 段(`LINGS-S01` 已启用,无需新增)
- **架构教训**:2026-10-01 user feedback `ClaudeCode 内部有内置 WebFetchTool 工具吗,如果有那我们有一个 http() → WebFetchTool 对称也是应该的` —— 修了 memory 中的反模式「MCP covers HTTP,no local Tool」
- **业务后果**(当前状态):
  - `RuntimeSandbox.http()` 在 Story #028 已真接通(§6.3),返回 `WhitelistedHttpClient implements NetworkClient`,domain-whitelist 守卫 0 新 Maven 依赖
  - **目前没有任何 local Tool 消费 `ctx.http()`** —— `ReadTool` 走 `ctx.fs()`(本地 fs),`BashTool` 走 `ctx.process()`(本地 process),`http()` 子能力**基建空闲**(MCP 3 transport 用 raw JDK `HttpURLConnection` 走自己路径,见 #033 mitigation 1)
  - 用户想读远程 API 文档 / GitHub raw README / RSS feed,只有 3 条路:(a) 配 MCP fetch server(重),(b) 写 `@AgentTool` 自定义(需要 Java 知识),(c) 直接 `RemoteAgentTool` 跨进程调别的 Agent(过度) —— **缺 Claude Code 那种 1 个 Tool 即用** 的轻量路径
  - dsh §6.3 L5726 占位 `WebFetch 域名白名单` + §6.3 L6383 占位 `web/ WebFetch` 暗示设计意图但 0 实施
- **对应风险**:**R-04**(privilege escalation — 分值 8)+ **R-13** mitigation (d)(R-13 第 17 次 PASS 强制)
- **涉及 ErrorCode**:**0 新 ErrorCode**(复用 Story #028 `LINGS-S01` 由 `WhitelistedHttpClient.check()` 自动抛)

---

## 1. WHY(为什么做这个 Story)

**核心问题**:LingShu 当前架构下,**本地 Tool 三件套**(ReadTool/BashTool/WebFetchTool)缺最后一件 —— WebFetchTool。`RuntimeSandbox.http()` 基建已在 Story #028 落(`WhitelistedHttpClient` + domain whitelist),但**没有任何 local Tool 消费它**,导致:

1. **Claude Code parity 缺失** —— Claude Code 工具表 `Read / Write / Edit / Glob / Grep / Bash / **WebFetch** / WebSearch / Task / Skill` 中 WebFetch 是 built-in,与 Read/Edit 同级;LingShu 只复刻了 Read/Glob/Grep/Bash 子集,**WebFetch 缺失**;CLAUDE.md §7「Claude Code parity」决策 12 要求 LingShu 工具表对齐 Claude Code
2. **基建空闲** —— `WhitelistedHttpClient` 在 #028 合入后 0 真正消费者,MCP 3 transport 走 raw JDK `HttpURLConnection`(Plan B,#033 缓解 1 单独处理);domain-whitelist yml 字段当前**只能挡 future WebFetch 调用**,无法挡当前任何 real Tool 调用
3. **轻量 HTTP 访问门槛高** —— 用户想 `GET https://api.example.com/docs` 拉文档,只能:(a) 配 MCP fetch server(需要 MCP server 子进程,YAML 5+ 行);(b) 写 `@AgentTool` 自定义 Java 方法(需要 Java 知识 + 重新编译);(c) 走 RemoteAgentTool 调别的 Agent(跨 JVM 过度) —— **没有 1 Tool 即用** 的轻量路径
4. **对称性破坏** —— `ReadTool`(local fs) / `BashTool`(local process) / `WebFetchTool`(local http) 三件套,**只有前两件**;`http()` 子能力**未被任何 local Tool 利用**,与 §6.3 设计意图偏差

**业务价值**:

- **Claude Code parity 补完** —— 本地 Tool 三件套对齐:`ReadTool`(fs)/ `BashTool`(process)/ `WebFetchTool`(http);dsh §7 决策 12「Claude Code 工具表 parity」真落地
- **`WhitelistedHttpClient` 基建真启用** —— 从「0 消费空闲」变「1 个 builtin Tool + #033 mitigation 1」的真正 sandbox 防御;domain-whitelist yml 字段首次挡 real Tool 调用
- **轻量 HTTP 访问体验提升** —— 用户配置 `domain-whitelist: [api.openai.com, raw.githubusercontent.com]` 后,LLM 可直接调 `web_fetch(url="https://...")` 拉任意文档 / API / RSS,无需配 MCP fetch server
- **HTTPS 零成本** —— JDK `HttpURLConnection` 对 `https://` 协议自动走 `HttpsURLConnection`(transparent),`WhitelistedHttpClient.openConnection()` 已有 `URI.create(url).toURL().openConnection()`(L142 真实代码),无需任何代码改动

**关键不变项**:

- `Tool` SPI interface — **0 改动**(`sourceCategory()` 默认方法已在 #031 加,默认 `"local"`)
- `ToolRegistry` / `ToolExecutor` / `ToolCall` / `ToolResult` — **0 改动**
- `RuntimeSandbox` interface / `WhitelistedHttpClient` class / `NetworkClient` interface — **0 改动**(只是 `WebFetchTool` 消费)
- `ToolExecutionContext.http()` 契约 — **0 改动**
- `AgentConfig` schema 0 改动;`Sandbox` 子结构 5 字段不变;`ToolsConfig.allowList` / `denyList` 字段不变(复用即可)
- `LocalToolsAutoConfiguration` 风格不变(`@Bean` 命名 `<slot>Provider_<name>` v1.5.28 多 Provider 模式) —— 本 Story 是 **non-Slot Type Bean**(普通 Tool),**不**走 `@ConditionalOnMissingBean`,对齐 §5.5 plugin 样板
- `LinearTurnEngine` ReAct 主循环 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变
- JDK 8 兼容(`HttpURLConnection` / `BufferedReader` / `InputStreamReader` / `HashMap` 全 JDK 8 standard,无新 binary 引入)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2)
- **0 新 Maven 依赖**(`HttpURLConnection` JDK 内置 + `WhitelistedHttpClient` 已锁)
- **0 新 ErrorCode**(复用 #028 `LINGS-S01`)

---

## 2. WHO(谁会用到)

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师(Alice 类)** | 给 Agent 配 `domain-whitelist: [api.openai.com, raw.githubusercontent.com, internal-api.company.com]`,LLM 可直接 `web_fetch(url="https://api.openai.com/v1/models")` 拉文档,无需配 MCP fetch server |
| **多租户平台搭建者** | Tenant Alice 配 `domain-whitelist: [api.openai.com]` —— 限定只能调 OpenAI API;Tenant Bob 配 `domain-whitelist: [internal-api.company.com]` —— 限定内网 API;**Tenant 隔离**通过 `TenantConfig.Sandbox.domainWhitelist`(Story #006 已落)+ `RuntimeSandbox` 实例 per-tenant(Story #028 已落)|
| **运维稳定性关注者(Eve 类)** | yml 改 `domain-whitelist` 后 hot-reload 立即生效(§14.8 N8 已合);新增白名单 domain 不用重启 Agent |
| **CI 工程师(Charlie 类)** | L1 测试覆盖 `WebFetchTool.execute()` 5 case(happy path 200 + 404 抛 IOException + 不在白名单抛 `LINGS-S01` + HTTPS 透明 + truncation > 1MB) + L2 端到端 `LocalToolsAutoConfiguration.webFetchTool()` Bean 注册成功 + `ToolRegistry.findByName("web_fetch")` 真命中 |
| **框架贡献者 / plugin 作者(Bob 类)** | 自定义 Tool 时直接 `@Component public class MyTool implements Tool`,与 `WebFetchTool` 同 `@Bean` 模式自动注册;`Tool.sourceCategory()` 默认 `"local"`(`WebFetchTool` 也不 override,继承默认) |
| **安全审计员(Diana 类)** | audit log(`§14.10 N10` 待实施)能记录 `web_fetch` 调用 `domain` + `status code` + `bytes returned`;domain 不在白名单时 `LINGS-S01` 嵌在 `AccessDeniedException.getMessage()`,审计可追 |

---

## 3. WHAT(交付什么 — 用户视角)

### 3.1 新行为

**`WebFetchTool implements Tool`**(`name()="web_fetch"`):

```java
@Component("webFetchTool")
public class WebFetchTool implements Tool {
    private static final Logger LOG = LoggerFactory.getLogger(WebFetchTool.class);
    private static final int DEFAULT_MAX_BYTES = 1_048_576; // 1 MB
    private static final String TOOL_NAME = "web_fetch";
    private static final String TRUNCATION_MARKER_FMT = "\n...[truncated, original %d bytes]";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String description() {
        return "Fetch the contents of an HTTP(S) URL. Returns up to 1 MB of the response body "
             + "as plain text (Markdown-friendly). Requires the host to be in the sandbox "
             + "domain whitelist. HTTPS is supported transparently. Use this for reading API "
             + "docs, GitHub raw files, RSS feeds, etc. POST/PUT/DELETE traffic is NOT supported "
             + "by this tool — use an MCP fetch server for that.";
    }

    @Override
    public JsonNode inputSchema() {
        // Static JSON Schema (no reflection / no @AgentTool) — keep it dead simple.
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode url = properties.putObject("url");
        url.put("type", "string");
        url.put("description", "Fully-qualified HTTP(S) URL to fetch (e.g. https://api.example.com/docs).");
        ObjectNode maxBytes = properties.putObject("max_bytes");
        maxBytes.put("type", "integer");
        maxBytes.put("description", "Optional override for max response size in bytes (default 1,048,576 / 1 MB).");
        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode args = call.getArguments();
        String url = args.path("url").asText();
        if (url == null || url.isEmpty()) {
            return ToolResult.error(call.getId(), "url is required");
        }
        int maxBytes = args.has("max_bytes") ? args.get("max_bytes").asInt(DEFAULT_MAX_BYTES) : DEFAULT_MAX_BYTES;

        try {
            // ctx.http() goes through WhitelistedHttpClient.check() → throws
            // AccessDeniedException[LINGS-S01] if domain not in whitelist.
            String body = ctx.http().get(url);

            if (body.length() > maxBytes) {
                body = body.substring(0, maxBytes) + String.format(TRUNCATION_MARKER_FMT, body.length());
            }
            return ToolResult.success(call.getId(), body);
        } catch (AccessDeniedException e) {
            // LINGS-S01 — propagate as ToolResult.error so ToolExecutor 5-step pipeline
            // §4.10.1 hard rule 2 stays intact (sandbox denial surfaces as tool error,
            // NOT as a thrown exception that would crash the turn).
            LOG.warn("WebFetchTool denied: {}", e.getMessage());
            return ToolResult.error(call.getId(), e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(call.getId(), "HTTP fetch failed: " + e.getMessage());
        }
    }

    @Override
    public String sourceCategory() {
        return "local"; // #031 default — explicit for clarity (same as Tool default)
    }
}
```

### 3.2 LocalToolsAutoConfiguration 加 `@Bean`

```java
// inside existing LocalToolsAutoConfiguration:
@Bean
public Tool webFetchTool() {
    return new WebFetchTool();
}
```

`LocalToolsAutoConfiguration` 现有结构(`@Configuration` + `@Bean` 4 个 local Tool)只 +5 行,不影响其他 4 个 Bean。

### 3.3 行为契约

| 场景 | 期望行为 |
|---|---|
| 1. 正常 GET(域在白名单)| `ToolResult.success(callId, body)`;body 为 raw HTTP response body(UTF-8 text / Markdown / JSON / HTML 均可);`body.length() ≤ 1MB` 直返,`> 1MB` 加 truncation marker |
| 2. HTTPS URL | `HttpsURLConnection` transparent,`WhitelistedHttpClient.openConnection()` 走 `URI.create(url).toURL().openConnection()`,`HttpURLConnection` 实际是 `HttpsURLConnection` 实例 |
| 3. 域不在白名单 | `WhitelistedHttpClient.check()` 抛 `AccessDeniedException[LINGS-S01] Domain not whitelisted: <host>`;`WebFetchTool.execute()` catch 转 `ToolResult.error(callId, "[LINGS-S01] Domain not whitelisted: <host>")` |
| 4. URL 格式错 | `WhitelistedHttpClient.check()` 抛 `AccessDeniedException[LINGS-S01] Malformed URL: ...`;同上 catch 转 error |
| 5. HTTP 404 | `WhitelistedHttpClient.get()` 抛 `IOException("HTTP 404 from GET ...")`;catch 转 `ToolResult.error(callId, "HTTP fetch failed: HTTP 404 ...")` |
| 6. HTTP 5xx | 同 404 |
| 7. body > 1MB | 截断到 `max_bytes`(默认 1048576)+ 加 marker `\n...[truncated, original N bytes]`;`max_bytes` 参数可覆盖默认 |
| 8. 参数 `url` 缺失 | `ToolResult.error(callId, "url is required")`(defensive —— JSON Schema `required` 已声明,但 LLM 偶尔漏) |
| 9. timeout 30s | `WhitelistedHttpClient.READ_TIMEOUT_MS = 30_000`,超时报 `IOException("Read timed out")`;catch 转 error |
| 10. LLM 调 POST/PUT/DELETE | ❌ 本 Story 不支持(`WhitelistedHttpClient.get()` only);用户必须配 MCP fetch server 或写 `@AgentTool`;`description()` 字段明示该限制 |

### 3.4 inputSchema 锁定

```json
{
  "type": "object",
  "properties": {
    "url": {
      "type": "string",
      "description": "Fully-qualified HTTP(S) URL to fetch"
    },
    "max_bytes": {
      "type": "integer",
      "description": "Optional override for max response size in bytes (default 1048576)"
    }
  },
  "required": ["url"]
}
```

`required: ["url"]` —— `max_bytes` 可选。

---

## 4. Acceptance Criteria(AC 黑盒)

| AC | 描述 | 验证方式 |
|---|---|---|
| **AC-NN-1** | L1 `WebFetchTool.name()` 返 `"web_fetch"` | `assertThat(tool.name()).isEqualTo("web_fetch")` |
| **AC-NN-2** | L1 `WebFetchTool.description()` 非空且含 "domain whitelist" 字符串 | `assertThat(tool.description()).contains("domain whitelist")` |
| **AC-NN-3** | L1 `WebFetchTool.inputSchema()` 含 `properties.url.type=string` + `required=[url]` | `assertThat(schema.at("/properties/url/type").asText()).isEqualTo("string"); assertThat(schema.at("/required/0").asText()).isEqualTo("url")` |
| **AC-NN-4** | L1 `WebFetchTool.sourceCategory()` 返 `"local"` | `assertThat(tool.sourceCategory()).isEqualTo("local")` |
| **AC-NN-5** | L2 happy path:`ctx.http().get(url)` 真发请求(用 JDK `com.sun.net.httpserver.HttpServer` mock)→ 200 返回 raw body | mock server 返 `"hello world"`,调 `tool.execute(call, ctx)`,`assertThat(result.getContent()).isEqualTo("hello world"); assertThat(result.isError()).isFalse()` |
| **AC-NN-6** | L2 HTTPS 透明:mock `HttpsURLConnection` 或用 `http://` 但验证 `HttpURLConnection.getClass()` 路径(简化:测 `URI.create("https://...").toURL().openConnection() instanceof HttpsURLConnection` 等价路径)| 用 `http://` mock + 断言 `ctx.http()` 真接通(mock server 收到 `Accept: */*` + `User-Agent: ChaOS-LingShu-Sandbox/1.0`) |
| **AC-NN-7** | L2 域不在白名单:`ctx.http()` 用空 `domainWhitelist` → `WhitelistedHttpClient.check()` 抛 `AccessDeniedException[LINGS-S01]` → `WebFetchTool.execute()` catch 转 `ToolResult.error` 含 `[LINGS-S01]` | mock ctx,`httpClient` 用 `new WhitelistedHttpClient(Collections.emptyList())`,调 execute,`assertThat(result.isError()).isTrue(); assertThat(result.getContent()).contains("LINGS-S01")` |
| **AC-NN-8** | L2 域在白名单 happy path:`WhitelistedHttpClient(["localhost"])` + mock server at localhost → 200 返回 body | `WhitelistedHttpClient.check("http://localhost:port/")` 不抛,execute 返 success body |
| **AC-NN-9** | L2 HTTP 404:`WhitelistedHttpClient.get()` 抛 `IOException("HTTP 404 ...")`,WebFetchTool catch 转 `ToolResult.error("HTTP fetch failed: HTTP 404 ...")` | mock server 返 404,execute,`assertThat(result.isError()).isTrue(); assertThat(result.getContent()).contains("HTTP fetch failed")` |
| **AC-NN-10** | L2 truncation:body > 1MB 时,`ToolResult.getContent()` = body.substring(0, 1048576) + `\n...[truncated, original N bytes]` | mock server 返 2MB string,execute,`assertThat(result.getContent().length()).isEqualTo(1048576 + marker.length())` |
| **AC-NN-11** | L2 `max_bytes` 覆盖:args `{url: "...", max_bytes: 100}` → 截到 100 bytes | mock server 返 1KB string,execute,`assertThat(result.getContent().length()).isEqualTo(100 + marker.length())` |
| **AC-NN-12** | L2 URL 缺失:args `{}` → `ToolResult.error("url is required")` | execute,`assertThat(result.isError()).isTrue(); assertThat(result.getContent()).isEqualTo("url is required")` |
| **AC-NN-13** | L2 `LocalToolsAutoConfiguration` 注册成功:`@SpringBootTest` 启动后 `applicationContext.getBean("webFetchTool")` 拿到 `Tool` 实例 + `tool.name()=="web_fetch"` | `AgentFactoryIntegrationTest` 或 `LocalToolsAutoConfigurationTest` 加 1 case |
| **AC-NN-14** | L2 `ToolRegistry.findByName("web_fetch")` 命中(经 `ToolRegistry` 自动 pick up `#019 built-in-tools` 路径)| mock `ToolRegistry`,启动后 `toolRegistry.findByName("web_fetch")` 不返 `Optional.empty()` |
| **AC-NN-15** | L3 blackbox IT:`DemoProductApplication` 启动后,`@AgentTool` 路径或 CLI 路径调 `web_fetch` Tool 端到端通(`ToolRegistry.findByName` → `tool.execute(call, ctx)` → ctx.http() 走真实 `WhitelistedHttpClient` → `agent.sandbox.domain-whitelist` 真生效) | 启 Spring Boot,`curl /agent/run` 模拟 LLM 调用,验证 response 含 `web_fetch` 调用结果 |

### 反向 AC(不应发生)

| 反向 AC | 期望不发生 |
|---|---|
| **反向 AC-1** | `WebFetchTool` **不** override `name()` / `description()` / `inputSchema()` / `execute()` 之外的 Tool 方法 | 继承 `Tool.sourceCategory()` 默认 `"local"`(不 override 也 OK,但本 Story 显式 override 以便将来 grep)|
| **反向 AC-2** | `LocalToolsAutoConfiguration` **不** 加 `@ConditionalOnMissingBean` | 对齐 §5.5「唯一 Bean 名约定」—— non-Slot type Bean 自由多注册,本 Story 不强制唯一 |
| **反向 AC-3** | **不**引入 `java.net.http.HttpClient`(JDK 11+) | 保持 JDK 8 兼容,用 JDK 8 `HttpURLConnection` |
| **反向 AC-4** | **不**实现 POST/PUT/DELETE | 本 Story GET-only,scope 锁定;`description()` 明示,`inputSchema` 不暴露 method 字段 |
| **反向 AC-5** | **不**支持 follow redirect(3xx) | `HttpURLConnection` 默认 follow redirects(2 跳上限),本 Story 不重写该行为;若用户需关闭可后续 RFC |
| **反向 AC-6** | **不**支持 authentication(headers / cookies / OAuth) | 本 Story 0 字段;后续 RFC |
| **反向 AC-7** | **不**绕过 `ctx.http()` | 必须走 `ctx.http().get(url)` —— `WhitelistedHttpClient.check()` 是 sandbox 防御,直调 JDK `HttpURLConnection` 破坏 §4.10.1 硬规则 2 |
| **反向 AC-8** | **不**修改 `Tool` SPI | 0 改动;`sourceCategory()` 复用 #031 default |

---

## 5. R-13 mitigation (d) dependency 自查

**预计 0 新 Maven 依赖**(必须由 `mvn -pl lingshu-core dependency:tree` pre/post diff 验证):

| 已锁依赖 | 本 Story 用途 |
|---|---|
| `jackson-databind` | `ObjectMapper` + `JsonNode` 构造 inputSchema |
| JDK `HttpURLConnection` / `HttpsURLConnection` | 实际 HTTP GET,`WhitelistedHttpClient` 已用 |
| JDK `BufferedReader` / `InputStreamReader` | `WhitelistedHttpClient.readBody()` 内部用 |
| Spring `@Component` / `@Bean` | `LocalToolsAutoConfiguration` 注册 |
| Lombok `@Slf4j` / `@Value`(可选,本 Story 倾向 plain Java) | logger 字段 |

**0 新依赖**:✅ R-13 mitigation (d) 第 17 次 PASS 预期成立

---

## 6. 关键不变项(详细列举)

- `Tool` interface — **0 改动**(`sourceCategory()` default 方法已在 #031 加)
- `ToolCall` / `ToolResult` / `ToolExecutionContext` / `NetworkClient` / `AccessDeniedException` — **0 改动**
- `RuntimeSandbox` interface — **0 改动**
- `WhitelistedHttpClient` class — **0 改动**(只是被 `WebFetchTool` 消费)
- `DefaultRuntimeSandbox.http()` — **0 改动**(返回的 `WhitelistedHttpClient` 实例直接被 `WebFetchTool` 用)
- `LocalToolsAutoConfiguration` 现有 4 个 `@Bean`(ReadTool/WriteTool/ListDirTool/BashTool)— **0 改动**,只 +1 `@Bean`
- `AgentConfig` schema — **0 改动**(不引入 `webFetch` 段;`domain-whitelist` 已在 `AgentConfig.Sandbox` 5 字段内,#025 follow-up + #028 已落)
- `ToolExecutor.dispatch()` 5 步流水线 — **0 改动**(§4.10.1 硬规则 2 守住)
- `PermissionPolicy` SPI — **0 改动**(`WebFetchTool.sourceCategory() = "local"` 走 `#031` `PermissionPatterns` ladder,默认 yml allow-list 通配 `["*"]` 即放行)
- `McpHttpSupport` / MCP 3 transport — **0 改动**(本 Story 与 #033 独立)
- 9 Slot 顶层体系不变
- JDK 8 兼容(`HttpURLConnection` / `BufferedReader` / `InputStreamReader` / `HashMap` / `Jackson JsonNode` / `ObjectMapper` 全 JDK 8 standard / spring-ai-bom 已锁,**不**引 `java.net.http.HttpClient` JDK 11+ 类 / **不**引 `java.net.http.HttpRequest` 等)
- `LINGS-S01` ErrorCode 嵌入 message 模式 `[LINGS-S01] Domain not whitelisted: <host>` 不变(#028 已落)
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2)

---

## 7. 反模式(本 Story 拒绝)

- ❌ 引入 `OkHttp` / `Apache HttpClient` / `java.net.http.HttpClient`(JDK 11+)/ `WebClient`(Spring WebFlux)— 0 新 Maven 依赖 / JDK 8 兼容硬约束
- ❌ 复用 MCP fetch server 作为 fallback(`ctx.mcp().fetch(url)` 调用 `#021a/b/c` 链路)— 破坏 single-sandbox 边界,`ctx.http()` 是 contract
- ❌ 实现 POST/PUT/DELETE(本 Story GET-only)— scope creep;MCP fetch server 已覆盖
- ❌ 实现 streaming 响应(`ctx.http().getStream(url)`)— 简单 Tool 优先,streaming 是 #014 follow-up RFC
- ❌ 实现 follow-redirect 配置 / authentication / cookies — 本 Story 0 字段;后续 RFC
- ❌ 加 `web_fetch` 段到 `AgentConfig` schema(`webFetch.timeoutSeconds` / `webFetch.maxBytes` 等)— 用 yml `agent.sandbox.domain-whitelist` 复用 + `WhitelistedHttpClient.READ_TIMEOUT_MS = 30_000` 复用,0 schema 改动
- ❌ 绕过 `ctx.http()` 直调 `HttpURLConnection` — 破坏 sandbox domain-whitelist 防御
- ❌ `WebFetchTool` 写成 `@Configuration` + 工厂 Bean 模式 — `@Component` 直接 new 最简,`LocalToolsAutoConfiguration` 加 `@Bean public Tool webFetchTool() { return new WebFetchTool(); }` 对齐 §5.5 plugin non-Slot type Bean 样板

---

## 8. Open Questions(本 Story 暂不解决)

- **OQ-WF-1**:`max_bytes` 参数默认值 1MB 是否合理?(2MB 适合 API 文档,500KB 适合 RSS;留 RFC 给社区提数据)
- **OQ-WF-2**:是否需要 `headers` 参数(custom User-Agent / Accept)?(本 Story 0 字段,留 RFC)
- **OQ-WF-3**:是否需要 `timeout_seconds` 覆盖?(本 Story 走 `WhitelistedHttpClient.READ_TIMEOUT_MS = 30_000` 固定,留 RFC)
- **OQ-WF-4**:POST/PUT/DELETE 何时纳入? — 需明确 `WebFetchTool` 与 MCP fetch server 边界(本期完全分离:本 Tool GET-only;MCP fetch server POST/PUT/DELETE 高级)

---

## 9. 风险与依赖

| 风险 | 评分 | 缓解 |
|---|---|---|
| **R-13**(新依赖)| 6 | 本 Story 0 新依赖;`mvn -pl lingshu-core dependency:tree` pre/post diff 必须 0 binary delta |
| **R-04**(privilege escalation)| 8 | `WebFetchTool` 必须走 `ctx.http()` → `WhitelistedHttpClient.check()` → `AccessDeniedException[LINGS-S01]`;**反向 AC-7** 显式禁止直调 |
| **R-09**(测试覆盖)| 4 | L1 + L2 + L3 共 15 case(AC-NN-1—AC-NN-15),L3 blackbox IT 覆盖 demo-product 端到端 |
| **R-19**(wiring gap)| 5 | `LocalToolsAutoConfiguration.@Bean public Tool webFetchTool()` 真接通;`ToolRegistry` 自动 pick up via `#019 built-in-tools` 路径 |

---

**Status**: ✅ Specified — 进入 plan.md 起草
