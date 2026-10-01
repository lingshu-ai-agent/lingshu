# Story #032 `web-fetch-local-tool` — Quickstart(30 min)

> **面向 Alice(Java 后端开发,用过 Spring Boot,首次接触 LingShu)**
> **目标**:30 分钟内跑通"本地 `WebFetch` Tool + `WhitelistedHttpClient` domain-whitelist 守卫 + Claude Code parity 三件套"链路
> **前置**:`#019` 已合(Read / Write / Edit / Bash 4 个本地 Tool)+ `#028` 已合(`RuntimeSandbox.http()` 返 `WhitelistedHttpClient` + `LINGS-S01` 沙箱域)+ `#031` 已合(`Tool.sourceCategory()` 默认方法,本 Story 不 override 走默认 `"local"`)+ JDK 8+ + Maven 3.6.3+

---

## 0. 学完能做什么

- 理解 LingShu §4.7 Sandbox SPI 的"本地 Tool + sandbox HTTP client"组合 pattern
- 看懂 `WebFetchTool.execute(call, ctx)` 4 段:url 校验 → max_bytes 解析 → `ctx.http().get(url)` 委托 → 1 MB truncation marker
- 看懂 `WhitelistedHttpClient.check(url)` 的 yml-driven domain-whitelist 守卫(`agent.sandbox.domain-whitelist`)
- 看懂 `WebFetchTool` GET-only 范围锁定(description 显式声明 `POST/PUT/DELETE traffic is NOT supported`)
- 看懂 HTTPS 透明(JDK `HttpsURLConnection` 自动按 scheme 选实现,User-Agent `ChaOS-LingShu-Sandbox/1.0` 透传)
- 跑一次 L1 unit + L2 IT + L3 黑盒,验证 15 case 全过 + R-13 0 binary delta 第 17 次

---

## 1. 5 步跑通(每步 5 min)

### 步骤 1(5 min):克隆主仓 + 看 Tool 域顶层 layout

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
ls lingshu-core/src/main/java/ai/lingshu/core/impl/tool/local/
```

**预期看到 6 个 local Tool 域文件**:
```
BashTool.java                 ← Story #019 内置 Bash Tool
EditTool.java                 ← Story #019 内置 Edit Tool
ReadTool.java                 ← Story #019 内置 Read Tool
WriteTool.java                ← Story #019 内置 Write Tool
WebFetchTool.java             ← 🆕 Story #032 内置 WebFetch 本地 HTTP/HTTPS 抓取 Tool
LocalToolsAutoConfiguration.java  ← Spring @Configuration 装配(Map<String, Tool> 自动接住 @Component)
LocalToolProps.java           ← Story #019 共享 props(maxBytes / maxLines)
```

### 步骤 2(5 min):跑 L1 单元测试,验证 WebFetchTool 4 段契约

```bash
mvn -pl lingshu-core test -Dtest=WebFetchToolTest
```

**预期看到 8 个 PASS(AC-NN-1—AC-NN-4 metadata + AC-NN-7 reverse + AC-NN-10/11/12 execute)**:
```
✅ AC-NN-1: name()="web_fetch"
✅ AC-NN-2: description() 含 "domain whitelist" + "POST/PUT/DELETE traffic is NOT supported"
✅ AC-NN-3: inputSchema() { url: string required, max_bytes?: integer }
✅ AC-NN-4: sourceCategory()="local" (默认, 不 override)
✅ AC-NN-7 reverse: empty WhitelistedHttpClient(Collections.emptyList()) + http://anywhere.example/path → [LINGS-S01]
✅ AC-NN-10: 2 MB body + 默认 1 MB cap → "first 1048576 chars + ...[truncated, original 2097152 bytes]"
✅ AC-NN-11: 1 KB body + max_bytes=100 override → "first 100 chars + ...[truncated, original 1024 bytes]"
✅ AC-NN-12: missing url → ToolResult.error("url is required")
```

每个 case 直接 `new WebFetchTool().execute(call, ctx)` + Mockito `ToolExecutionContext` 模拟 ctx。

### 步骤 3(5 min):跑 L2 真发请求 IT,验证 HTTPS 透明 + User-Agent 透传

```bash
mvn -pl lingshu-core test -Dtest=WebFetchToolHttpServerIT
```

**预期看到 4 个 PASS(AC-NN-5 happy path + AC-NN-6 HTTPS 透明 + AC-NN-8 whitelist 命中 + AC-NN-9 HTTP 404)**:
```
✅ AC-NN-5: 200 hello world → ToolResult.success("hello world")
✅ AC-NN-6: User-Agent header 透传 = "ChaOS-LingShu-Sandbox/1.0"(证明真过 JDK HttpURLConnection 层)
✅ AC-NN-8: localhost whitelist 命中 → ToolResult.success("whitelist hit")
✅ AC-NN-9: HTTP 404 → ToolResult.error("HTTP fetch failed: ...") startsWith "HTTP fetch failed:" + contains "404"
```

IT 用 JDK `com.sun.net.httpserver.HttpServer`(`HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)` + `server.start()`)开真 socket,绑 127.0.0.1 + 内核分端口(避 CI 8080 冲突)。

### 步骤 4(5 min):跑 L2 装配 IT,验证 LocalToolsAutoConfiguration 自动注册

```bash
mvn -pl lingshu-core test -Dtest=LocalToolsAutoConfigurationWebFetchIT
```

**预期看到 2 个 PASS(AC-NN-13 Bean 注册 + AC-NN-14 ToolRegistry 命中)**:
```
✅ AC-NN-13: Map<String, Tool> 注入 5 个 Tool → registry.asMap() containsKeys ("Read", "Write", "Edit", "Bash", "web_fetch") + size=5
✅ AC-NN-14: registry.findByName("web_fetch") 返回 WebFetchTool 实例 + sourceCategory="local"
```

**关键**:`findByName(name)` 返 `Tool` not `Optional<Tool>`(SPI 契约:缺则抛 IllegalArgumentException)。Manual-instantiation 样板(沿用 Story #019 `LocalToolsAutoConfigurationTest`)—— `new DefaultToolRegistry()` + `new LocalToolsAutoConfiguration(registry, sandbox, bash, toolBeans, env).afterPropertiesSet()`,不复用 `@SpringBootTest` 避免加载全部 Spring Boot autoconfig。

### 步骤 5(10 min):跑 L3 黑盒 demo-product IT,验证 yml 端到端真生效

```bash
mvn -pl lingshu-examples/demo-product test -Dtest=DemoProductWebFetchIT
```

**预期看到 1 个 PASS(AC-NN-15 demo end-to-end + 双半同 case)**:
```
✅ AC-NN-15: localhost 在 test override whitelist → mock server "mock-body" 返回(SUCCESS)
✅ AC-NN-15: example.com 不在 whitelist → [LINGS-S01] Domain not whitelisted: example.com(ERROR)
```

`@SpringBootTest(classes = DemoProductApplication.class, webEnvironment = NONE)` + `@TestPropertySource(properties = {"agent.sandbox.domain-whitelist=localhost"})` —— 覆盖 production yml 7 个 domain 只留 localhost,让断言清晰:yml 是真 gate,不是 hardcoded。

---

## 2. 故障排查(共 4 个常见坑)

### 坑 1:WebFetchTool 找不到符号

**症状**:`找不到符号: 类 WebFetchTool` 编译失败
**根因**:`lingshu-core` 的 `WebFetchTool` 没 publish 到本地 Maven repo,demo-product 依赖没拉到
**解决**:
```bash
mvn -pl lingshu-core install -DskipTests
mvn -pl lingshu-examples/demo-product compile
```

### 坑 2:HttpServer.create() 后请求挂死

**症状**:WebFetchToolHttpServerIT 4 个 case 全 timeout 30s
**根因**:`HttpServer.create()` 只构造 server,**必须**调 `server.start()` 才 bind socket 接受连接
**解决**:在 `@BeforeEach startServer()` 加 `server.start();`(本 Story commit 已修)

### 坑 3:findByName 编译错误:不兼容 Optional<Tool>

**症状**:`Optional<Tool> resolved = registry.findByName("web_fetch")` → `不兼容的类型: Tool 无法转换为 Optional<Tool>`
**根因**:`ToolRegistry` SPI 的 `findByName(name)` 直接返 `Tool`(缺则抛 IllegalArgumentException);`lookup(name)` 返 `Tool` or null
**解决**:用 `Tool resolved = registry.findByName("web_fetch")`,**不要**包 `Optional.of(...)`

### 坑 4:truncation marker 长度 off-by-N

**症状**:expected: 1048610 but was: 1048615(marker template 长度算错)
**根因**:`"\n...[truncated, original %d bytes]"` 模板 34 字符,但 `%d` 占位 2 字符替换为实际数字 bodyLen,marker 实际长度 = 34 - 2 + `String.valueOf(bodyLen).length()`
**解决**:`formattedMarkerLen(int bodyLen)` helper:`templateLen - 2 + String.valueOf(bodyLen).length()`(本 Story commit 已修)

---

## 3. 后续阅读

- **spec.md** — Story #032 完整 spec(WHY / WHO / WHAT / AC-NN / 反向 AC)
- **data-model.md** — `WebFetchTool` 4 method + 1 sourceCategory override + inputSchema JSON Schema + execute 4 段逻辑 + truncation marker + 错误处理 2 catch
- **plan.md** — 实施接口 / 文件 / 测试策略
- **tasks.md** — 8 段 T01-T08 + 6 AC-NN validate + 4 dep-tree 自查 + 11 doc-sync + 7 PR
- **dsh §6.3 WhitelistedHttpClient 段** — Sandbox HTTP client 设计(Story #028 落地,本 Story 是 idle 基建激活)
- **README.md Story #032 retrospective** — 业务视角的故事

---

## 4. 时间戳

- **创建**:2026-10-01
- **最后更新**:2026-10-01
- **对应 dsh 版本**:v1.5.48
- **对应 dsh changelog 行**:v1.5.48 → v1.5.49(待 T-doc-6 落地)
