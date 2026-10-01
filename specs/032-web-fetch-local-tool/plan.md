# Plan: Story #032 `web-fetch-local-tool`

> **Spec anchors**: specs/032-web-fetch-local-tool/spec.md
> **Design anchors**: dsh v1.5.47 §6.3 ChrootRuntimeSandbox `http()` 子能力 + §7 决策 12 Claude Code parity + §4.10.1 硬规则 2 ToolExecutor 5 步流水线 + §5.5 plugin non-Slot type Bean 样板 + §15.7 ErrorCode 域 S 段(`LINGS-S01` 复用)+ §6.3 L5726 + L6383 占位 `WebFetch` 对齐
> **Stratum**: A-Story(`specification` → `plan` → `tasks` → `implementation` 单 Story 闭环)
> **前置依赖**:#001 + #003 + #004 + #019 + #028 + #031 全部已合(`Tool` SPI + `ToolRegistry` + `RuntimeSandbox.http()` + `WhitelistedHttpClient` + `Tool.sourceCategory()` 全部就位)

---

## 约束(从 constitution + spec 继承)

- **JDK 8 only** — 不许 `var` / `List.of` / `Map.of` / `record` / `sealed` / `java.net.http.HttpClient`(JDK 11+)/ `java.net.http.HttpRequest`(constitution §1 第 1 项 + §6 兼容性矩阵);本 Story 用 JDK 8 `HttpURLConnection` / `HttpsURLConnection`(transparent)/ `BufferedReader` / `InputStreamReader` + Jackson `JsonNode` + `ObjectMapper`(已锁 13 项依赖表内)
- **Lombok `@Value` 不可变优先** — `WebFetchTool` 不持有可变状态(纯 stateless 工具类),用 plain Java + `@Component`(对齐 `ReadTool` / `WriteTool` / `ListDirTool` precedent)
- **新 ErrorCode 走 `LINGS-<域><编号>` 命名** — 本期 **0 新抛 ErrorCode**(复用 Story #028 `LINGS-S01 SANDBOX_ACCESS_DENIED`,`WhitelistedHttpClient.check()` 自动抛)
- **性能预算 §14.15.1 不退化** — `WebFetchTool.execute()` 是 stateless 同步操作;`ctx.http().get(url)` 由 `WhitelistedHttpClient` 处理 30 s timeout;turn 完成 P50 ≤ 30s / P99 ≤ 60s 不退化;Tool 单次调用 P99 ≤ toolTimeoutSec(本期走 30 s 默认)
- **`ToolExecutor.dispatch()` 5 步流水线不变** — `WebFetchTool.execute()` 是流水线第 5 步(`tool.execute()` 调用),5 步结构 `PermissionPolicy.check() §4.7 → ToolRegistry.lookup(name) → TimeoutWrap → SandboxApply(fs/http/process) §4.7 → tool.execute() → Checkpoint` 全部 0 改动
- **0 新 Maven 依赖** — `HttpURLConnection` / `HttpsURLConnection` / `BufferedReader` / `InputStreamReader` / `ObjectMapper` / `JsonNode` / `String.format` / `Pattern`(regex 不需要)/ Lombok `@Slf4j`(可选)/ Spring `@Component` / `@Bean` / `@Autowired` 全 JDK 8 standard + 已锁 13 项依赖表内
- **测试用裸 `AnnotationConfigApplicationContext` 或 mock**(沿用 Story #028 模式)— 不引 `@SpringBootTest`(规避 Mockito 5.x + JDK 23 inline mockmaker 兼容 issue)
- **HTTPS 透明** — `URI.create(url).toURL().openConnection()` 自动返回 `HttpsURLConnection`(JDK 标准行为);`WhitelistedHttpClient.openConnection()` 已有该逻辑(L142 真实代码)
- **Spring AI `ChatClient.tools().call()` 仍禁止使用**(§4.10.1 硬规则 2),`WebFetchTool` 走 `ToolExecutor.dispatch()` 第 5 步
- **必须走 `ctx.http()`** — 禁止直调 JDK `HttpURLConnection`(绕过 sandbox domain-whitelist 防御,违反反向 AC-7)

---

## 1. 涉及接口(新增 / 修改)

### 新增

| 类 | 路径 | 角色 |
|---|---|---|
| `WebFetchTool`(@Component `name="webFetchTool"`)| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/WebFetchTool.java` | `Tool` SPI 实现,`name()="web_fetch"`,`inputSchema` 静态 JSON Schema(`{url: string, max_bytes?: number}`),`execute()` 委托 `ctx.http().get(url)`,`AccessDeniedException[LINGS-S01]` catch 转 `ToolResult.error`,`IOException` catch 转 `ToolResult.error("HTTP fetch failed: ...")`,1 MB truncation marker;`sourceCategory()` 显式 override `"local"` |
| `WebFetchToolTest` | `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/WebFetchToolTest.java` | L1 Unit 测试 8 case(AC-NN-1 — AC-NN-4 + AC-NN-7 — AC-NN-12 + truncation)|
| `WebFetchToolHttpServerIT` | `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/WebFetchToolHttpServerIT.java` | L2 集成测试 4 case(AC-NN-5 + AC-NN-6 + AC-NN-8 + AC-NN-9 真发请求到 JDK `com.sun.net.httpserver.HttpServer` mock)|
| `LocalToolsAutoConfigurationWebFetchIT` | `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfigurationWebFetchIT.java` | L2 Spring 装配测试 2 case(AC-NN-13 + AC-NN-14 `webFetchTool` Bean 注册 + `ToolRegistry.findByName("web_fetch")` 命中)|
| `DemoProductWebFetchIT` | `lingshu-examples/demo-product/src/test/java/.../DemoProductWebFetchIT.java` | L3 blackbox IT 1 case(AC-NN-15 `DemoProductApplication` 启动后端到端 `web_fetch` 调用 + `agent.sandbox.domain-whitelist` 真生效)|

### 修改

| 接口 / 类 | 修改 |
|---|---|
| `LocalToolsAutoConfiguration`(`lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfiguration.java`)| 加 1 `@Bean public Tool webFetchTool() { return new WebFetchTool(); }`;现有 4 `@Bean`(`readTool` / `writeTool` / `listDirTool` / `bashTool`)**0 改动** |
| `demo-product/src/main/resources/application.yml` | `agent.sandbox.domain-whitelist` 段加 3-5 个示例 domain(`api.openai.com` / `api.anthropic.com` / `raw.githubusercontent.com` / `huggingface.co` / `localhost`(for IT));注释引用本 Story #032 spec + dsh §6.3 |
| `README.md` | 「核心特性」段加 🌐 WebFetch 本地 HTTP 抓取已上线 bullet(对齐 Story #019 + #028 风格);「Story 路线图」段追加 #032 retrospective(5 文件改动 / 15 case AC 黑盒验证 / R-13 0 binary delta / Claude Code parity)|

### 不变(back-compat 守住)

- `Tool` SPI interface — **0 改动**(`sourceCategory()` default 方法已在 #031 加,`WebFetchTool` 显式 override `"local"`)
- `ToolCall` / `ToolResult` / `ToolExecutionContext` / `NetworkClient` / `AccessDeniedException` — **0 改动**
- `RuntimeSandbox` interface / `WhitelistedHttpClient` class — **0 改动**
- `DefaultRuntimeSandbox.http()` — **0 改动**(返回 `WhitelistedHttpClient` 实例直接被 `WebFetchTool` 用)
- `ToolExecutor` SPI + 5 步流水线 — **0 改动**
- `PermissionPolicy` SPI + `StrictPermissionPolicy.check()`(`sourceCategory()="local"` 走 #031 `PermissionPatterns` ladder)— **0 改动**
- `LocalToolsAutoConfiguration` 现有 4 `@Bean`(`ReadTool` / `WriteTool` / `ListDirTool` / `BashTool`)**0 改动**
- `AgentConfig` 不可变契约(`@Value` + `@Builder` 25 字段 final,Story #031 0 改动)— **0 字段新增**
- `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**(`domainWhitelist` 已在 #025 follow-up + #028 接通执行)
- `ToolsConfig` 5 字段(`enabled` / `allowList` / `denyList` / `maxReadBytes` / `maxWriteBytes`)**0 改动**
- `ToolRegistry` SPI — **0 改动**(`findByName("web_fetch")` 走 #019 已落 `LocalToolsAutoConfiguration` 路径)
- `Agent` 4 final 字段(T1→T4 不变)/ `AgentFactory.create()` 7 项校验 —— **0 改动**
- `LinearTurnEngine` ReAct 主循环结构 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段契约 — **全部 0 改动**
- 9 Slot 顶层体系不变(`WebFetchTool` 是 non-Slot type Bean,**不**走 SlotRouter)
- `McpHttpSupport` / MCP 3 transport — **0 改动**(本 Story 与 #033 独立;`http()` 子能力是 local Tool 契约,MCP 走 raw JDK `HttpURLConnection` 路径不动)
- `A2aTransport` 5 方法契约 / `RemoteAgentTool` —— **0 改动**
- `LINGS-S01` ErrorCode 嵌入 message 模式 `"[LINGS-S01] " + reason` 不变(#028 已落)
- JDK 8 兼容(`HttpURLConnection` / `BufferedReader` / `InputStreamReader` / `HashMap` / Jackson `JsonNode` / `ObjectMapper` 全 JDK 8 standard / spring-ai-bom 已锁,**不**引 `java.net.http.HttpClient` JDK 11+ 类 / **不**引 `OkHttp` / **不**引 `Apache HttpClient` / **不**引 `WebClient`)
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2)

**新增 + 修改严格遵循 dsh §6.3 + §7 决策 12 + §5.5 字面落地**,不引入新接口契约(只新增 1 个 Tool 实现 + 1 个 `@Bean` 接线)

---

## 2. 文件清单

| 文件 | 状态 | 行数预估 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/WebFetchTool.java` | 新增 | ~120(`@Component implements Tool` + 4 method override + 1 default method override + truncation marker + 注释)|
| `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfiguration.java` | modify(加 1 `@Bean`)| +5 行(`@Bean public Tool webFetchTool()`)|
| `lingshu-examples/demo-product/src/main/resources/application.yml` | modify(`agent.sandbox.domain-whitelist` 段加示例)| +5 行(注释 + 5 示例 domain)|
| `README.md` | modify(核心特性 + Story 路线图段)| +30 行 |
| `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/WebFetchToolTest.java` | 新增 L1 | ~250 行(8 case:AC-NN-1/2/3/4 + 7/10/11/12)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/WebFetchToolHttpServerIT.java` | 新增 L2 | ~200 行(4 case:AC-NN-5/6/8/9 真发请求到 mock HTTP server)|
| `lingshu-core/src/test/java/ai/lingshu/core/impl/tool/local/LocalToolsAutoConfigurationWebFetchIT.java` | 新增 L2 | ~80 行(2 case:AC-NN-13/14 Spring 装配)|
| `lingshu-examples/demo-product/src/test/java/.../DemoProductWebFetchIT.java` | 新增 L3 | ~150 行(1 case:AC-NN-15 demo-product 端到端)|

**2 新增(1 源码 + 3 测试 = 4)+ 3 modify = 5 核心文件**(严格 ≤ 5 边界,符合 §11.4 Story 约束)

> **R-13 mitigation (d) 强制** — 2 new + 3 modify + 4 new test,**0 新 Maven 依赖**(`HttpURLConnection` / `BufferedReader` / `InputStreamReader` / `HashMap` / `String.format` / `ObjectMapper` / `JsonNode` / `Collections.emptyList()` + Lombok `@Slf4j` 可选 + Spring `@Component` / `@Bean` 全 JDK 8 standard + 已锁 13 项依赖表内)

---

## 3. 测试策略

### L1 单元测试(8 case,`WebFetchToolTest`)

- **AC-NN-1**:`tool.name() == "web_fetch"` — `@DisplayName("name returns web_fetch")`
- **AC-NN-2**:`tool.description()` 含 "domain whitelist" — `@DisplayName("description mentions domain whitelist")`
- **AC-NN-3**:`tool.inputSchema()` 静态 JSON Schema 校验 — `@DisplayName("inputSchema has url+max_bytes+required")`,`assertThat(schema.at("/properties/url/type").asText()).isEqualTo("string"); assertThat(schema.at("/required/0").asText()).isEqualTo("url"); assertThat(schema.at("/properties/max_bytes/type").asText()).isEqualTo("integer")`
- **AC-NN-4**:`tool.sourceCategory() == "local"` — `@DisplayName("sourceCategory returns local")`
- **AC-NN-7**:空 whitelist `ctx.http()` → `ToolResult.error("[LINGS-S01] ...")` — mock `ToolExecutionContext`,注入 `WhitelistedHttpClient(Collections.emptyList())`,execute,断言 `result.isError() && result.getContent().contains("LINGS-S01")`
- **AC-NN-10**:truncation > 1MB — `ctx.http().get(url)` mock 返 2MB string,execute,断言 `result.getContent().length() == 1048576 + markerLen`
- **AC-NN-11**:`max_bytes` 覆盖 — args `{url, max_bytes: 100}`,execute,断言 `result.getContent().length() == 100 + markerLen`
- **AC-NN-12**:URL 缺失 — args `{}`,execute,断言 `result.isError() && result.getContent() == "url is required"`

### L2 集成测试(6 case,2 个 IT 文件)

- **AC-NN-5**:`HttpServer` mock 返 200 → execute 返 `ToolResult.success(callId, "hello world")` — `WebFetchToolHttpServerIT`
- **AC-NN-6**:HTTPS 透明(简化:mock `http://` 但验证 `ctx.http()` 真接通) — `WebFetchToolHttpServerIT`
- **AC-NN-8**:`WhitelistedHttpClient(["localhost"])` + mock localhost → 200 返回 body — `WebFetchToolHttpServerIT`
- **AC-NN-9**:HTTP 404 mock → `ToolResult.error("HTTP fetch failed: HTTP 404 ...")` — `WebFetchToolHttpServerIT`
- **AC-NN-13**:`LocalToolsAutoConfiguration.webFetchTool()` Bean 注册成功 — `LocalToolsAutoConfigurationWebFetchIT`,`@SpringBootTest(classes = LocalToolsAutoConfiguration.class)`,`applicationContext.getBean("webFetchTool", Tool.class); assertThat(tool.name()).isEqualTo("web_fetch")`
- **AC-NN-14**:`ToolRegistry.findByName("web_fetch")` 命中 — `LocalToolsAutoConfigurationWebFetchIT`,`toolRegistry.findByName("web_fetch"); assertThat(optional).isPresent()`

### L3 blackbox IT(1 case,`DemoProductWebFetchIT`)

- **AC-NN-15**:demo-product 启动 + `agent.sandbox.domain-whitelist` 真生效 + 端到端 `web_fetch` 调用 — `@SpringBootTest(classes = DemoProductApplication.class)`,mock HTTP server(`com.sun.net.httpserver.HttpServer`),加进 `domain-whitelist`;`agent.run(prompt="fetch URL...")` → 验证 response 含 mock server 返回的 body + audit log

### Mock 策略

- **`ToolExecutionContext` mock**:`Mockito.mock(ToolExecutionContext.class)`,`when(ctx.http()).thenReturn(new WhitelistedHttpClient(whitelist))`(真对象而非 mock,因为 `WhitelistedHttpClient.check()` 是 sandbox 防御核心,真测更稳)
- **HTTP server mock**:JDK `com.sun.net.httpserver.HttpServer` + `HttpHandler`(L1 happy path + L2 集成);fixture `WebFetchTestSupport` 静态 helper(~50 行,`findFreePort()` + `HttpServer.create()` + `start()` + close())
- **`ToolCall` mock**:`new ToolCall("call-1", "web_fetch", objectMapper.readTree(argsJson))`(真对象)
- **`ToolResult` 真对象**:`ToolResult.success(callId, body)` / `ToolResult.error(callId, msg)`(真对象,无 mock 必要)

---

## 4. 接口契约 / 实现要点

### `WebFetchTool implements Tool`

```java
package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.slot.AccessDeniedException;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolCall;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Story #032 — local HTTP(S) fetch tool backed by {@link
 * ai.lingshu.core.slot.ToolExecutionContext#http()}. Claude Code parity:
 * LingShu's local Tool triplet (Read/Bash/WebFetch) is now complete.
 *
 * <p>GET-only by design — POST/PUT/DELETE traffic belongs to MCP fetch servers
 * (Story #021a/b/c). Domain-whitelist enforcement is delegated to the
 * sandbox {@code WhitelistedHttpClient.check()} which throws
 * {@link AccessDeniedException} carrying {@code [LINGS-S01]} on miss.
 *
 * <p>HTTPS is supported transparently via JDK {@code HttpsURLConnection}
 * (no extra dependencies).
 *
 * <h2>Why no @Autowired?</h2>
 *
 * <p>Stateless utility class — no per-instance dependencies. The sandbox
 * client is obtained per-call from {@link ToolExecutionContext#http()}, which
 * is the single source of truth for the configured domain whitelist.
 *
 * <h2>Why no streaming?</h2>
 *
 * <p>Simple in-memory text response is sufficient for the 80% use case
 * (docs, RSS, JSON APIs). Streaming is a future RFC — for now we cap at
 * 1 MB to bound memory + turn latency.
 */
@Component("webFetchTool")
public class WebFetchTool implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(WebFetchTool.class);

    public static final String TOOL_NAME = "web_fetch";
    private static final int DEFAULT_MAX_BYTES = 1_048_576; // 1 MB
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
        int maxBytes = args.has("max_bytes") && args.get("max_bytes").isInt()
            ? args.get("max_bytes").asInt(DEFAULT_MAX_BYTES)
            : DEFAULT_MAX_BYTES;

        try {
            String body = ctx.http().get(url);
            if (body.length() > maxBytes) {
                body = body.substring(0, maxBytes) + String.format(TRUNCATION_MARKER_FMT, body.length());
            }
            return ToolResult.success(call.getId(), body);
        } catch (AccessDeniedException e) {
            // [LINGS-S01] sandbox domain whitelist miss — surface as tool error
            // (don't propagate; §4.10.1 hard rule 2 — sandbox denial is a ToolResult,
            // NOT an exception that would crash the turn).
            LOG.warn("WebFetchTool denied: {}", e.getMessage());
            return ToolResult.error(call.getId(), e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(call.getId(), "HTTP fetch failed: " + e.getMessage());
        }
    }

    @Override
    public String sourceCategory() {
        return "local"; // explicit for grep-ability; same as Tool default (#031)
    }
}
```

### `LocalToolsAutoConfiguration` 加 1 `@Bean`

```java
// inside existing class:
@Bean
public Tool webFetchTool() {
    return new WebFetchTool();
}
```

(注:5 行 modify,不动现有 4 `@Bean`)

### `demo-product/application.yml` 接线

```yaml
agent:
  sandbox:
    policy: default
    runtime: chroot
    working-directory: ${user.dir}
    command-whitelist: [bash, sh, echo, cat, ls, pwd, grep, find, head, tail, wc]
    # Story #032 — domain-whitelist now enforced for local web_fetch Tool too
    domain-whitelist:
      - api.openai.com
      - api.anthropic.com
      - raw.githubusercontent.com
      - huggingface.co
      - localhost  # for IT mock servers
```

---

## 5. 风险登记

| 风险 | 评分 | 缓解 |
|---|---|---|
| **R-13**(新依赖)| 6 | 0 新 Maven 依赖;`mvn -pl lingshu-core dependency:tree` pre/post diff 验证 |
| **R-04**(privilege escalation)| 8 | `WebFetchTool.execute()` 必须走 `ctx.http()` → `WhitelistedHttpClient.check()`;反向 AC-7 显式禁止直调;L2/L3 测试覆盖 `[LINGS-S01]` 抛出 |
| **R-09**(测试覆盖)| 4 | L1 (8) + L2 (6) + L3 (1) = **15 case** AC 黑盒(AC-NN-1—AC-NN-15)|
| **R-19**(wiring gap)| 5 | `LocalToolsAutoConfiguration.@Bean` 真接通;`ToolRegistry` 自动 pick up via `#019 built-in-tools` 路径;`DemoProductWebFetchIT` 端到端验证 |

---

## 6. 实施顺序

按依赖顺序(每个 T-NN 一个 commit):

1. **T-NN-1**:新增 `WebFetchTool.java`(~120 行)
2. **T-NN-2**:`LocalToolsAutoConfiguration` 加 `@Bean public Tool webFetchTool()`
3. **T-NN-3**:新增 `WebFetchToolTest.java` L1 (8 case)
4. **T-NN-4**:新增 `WebFetchToolHttpServerIT.java` L2 (4 case 真发请求)
5. **T-NN-5**:新增 `LocalToolsAutoConfigurationWebFetchIT.java` L2 (2 case Spring 装配)
6. **T-NN-6**:新增 `DemoProductWebFetchIT.java` L3 (1 case demo-product 端到端)
7. **T-NN-7**:`demo-product/application.yml` `domain-whitelist` 加示例
8. **T-NN-8**:`README.md` 加核心特性 bullet + Story 路线图段
9. **T-NN-9**:`mvn -pl lingshu-core test` 全跑通(647 pass + 新 8 + 6 + 1 = 662 expected)
10. **T-NN-10**:`mvn -pl lingshu-core dependency:tree` pre/post diff 验证 0 binary delta(R-13 第 17 次 PASS)
11. **T-NN-11**:`mvn validate -N` + 全模块 `mvn -DskipTests=true install`
12. **T-NN-12**:PR commit + 文档同步

---

## 7. 关键不变项(详细列举)

**0 SPI 改动清单**:

- `Tool` interface(4 方法 + 1 default method)— **0 改动**
- `ToolCall` / `ToolResult` / `ToolExecutionContext` / `NetworkClient` / `AccessDeniedException` — **0 改动**
- `RuntimeSandbox` interface — **0 改动**
- `WhitelistedHttpClient` class — **0 改动**
- `DefaultRuntimeSandbox.http()` — **0 改动**
- `ToolExecutor` SPI + 5 步流水线 — **0 改动**
- `PermissionPolicy` SPI — **0 改动**(`sourceCategory()="local"` 走 #031 `PermissionPatterns` ladder)
- `ToolRegistry` SPI — **0 改动**
- `LocalToolsAutoConfiguration` 现有 4 `@Bean` — **0 改动**
- `AgentConfig` schema 25 字段 — **0 改动**
- `AgentConfig.Sandbox` 5 字段(`policy` / `runtime` / `workingDirectory` / `commandWhitelist` / `domainWhitelist`)**0 改动**
- `ToolsConfig` 5 字段 — **0 改动**
- `LinearTurnEngine` ReAct 主循环 / `Message` 4 子类 / `Prompt` 契约 / `LlmResponse` 5 字段 — **0 改动**
- 9 Slot 顶层体系不变
- `McpHttpSupport` / MCP 3 transport — **0 改动**(本 Story 与 #033 独立)
- `A2aTransport` 5 方法契约 / `RemoteAgentTool` — **0 改动**
- `LINGS-S01` ErrorCode 嵌入 message 模式 `"[LINGS-S01] " + reason` 不变(#028 已落)
- Spring AI `ChatClient.tools().call()` 仍**禁止**使用(§4.10.1 硬规则 2)

**0 新 Maven 依赖**:✅ R-13 mitigation (d) 第 17 次 PASS 预期成立

---

**Status**: ✅ Planned — 进入 tasks.md 起草
