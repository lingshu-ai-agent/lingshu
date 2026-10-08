# Story #033 `mcp-http-domain-guard` — Quickstart(30 min)

> **面向 Alice(Java 后端开发,用过 Spring Boot,首次接触 LingShu)
> **目标**:30 分钟内跑通"MCP HTTP transports Path B + Mitigation 1 守卫钩子" + sandbox whitelist fallback 链路
> **前置**:`#028` 已合(`WhitelistedHttpClient[LINGS-S01]`)+ `#021a` stdio + `#021b` adapter + `#021c` SSE/Streamable HTTP + `#032` WebFetchTool 已合(同 `LINGS-S01` ErrorCode 复用案例)+ JDK 8+ + Maven 3.6.3+

---

## 0. 学完能做什么

- 理解 LingShu §6.3 Sandbox SPI 的「**Path B + Mitigation 1**」 pattern:本地 Tool + sandbox HTTP client 全路径(Path A)vs MCP HTTP transports check-only 钩子(Path B)
- 看懂 `McpHttpSupport.checkOrThrow(url, whitelist)` 静态守卫契约(`URL 校验 → host 提取 → whitelist.contains`)
- 看懂 `McpServerConfig.domainWhitelist` per-server 配置 + `McpTransportAutoConfiguration` fallback 到 `agent.sandbox.domain-whitelist`
- 看懂 **12 hook points**(SSE 7 + Streamable HTTP 5)集成,100% MCP HTTP 端点拦截
- 跑一次 L1 unit + L2 IT + R-13 dep-tree 自查,验证 9 case 全过 + 0 binary delta 第 18 次

---

## 1. 5 步跑通(每步 5 min)

### 步骤 1(5 min):克隆主仓 + 看 MCP 域顶层 layout

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
ls lingshu-core/src/main/java/ai/lingshu/core/mcp/
```

**预期看到 12 个 MCP 文件**:
```
ConnectionState.java                    # 6 态 enum
McpCallResult.java                    # success/error result POJO
McpHttpSupport.java                   # 🆕 Story #033 加 checkOrThrow() 守卫
McpServerConfig.java                  # 🆕 Story #033 加 domainWhitelist 字段
McpServerConnection.java              # SPI interface
McpServerConnectionFactory.java       # 静态分派(STDIO/SSE/STREAMABLE_HTTP)
McpToolAdapter.java                   # Tool 实现,转发 callTool
McpToolDescriptor.java                # 远端 tool 元数据
McpTransportException.java            # 协议层异常
SseMcpServerConnection.java           # SSE transport(Story #033 加 7 hook)
StdioMcpServerConnection.java         # stdio transport(Story #033 0 改,无 HTTP)
StreamableHttpMcpServerConnection.java # streamable HTTP(Story #033 加 5 hook)
```

### 步骤 2(5 min):跑 L1 单元测试,验证 checkOrThrow 6 段契约

```bash
mvn -pl lingshu-core test -Dtest=McpHttpSupportCheckOrThrowTest
```

**预期看到 6 个 PASS(AC-NN-1—AC-NN-6)**:
```
✅ AC-NN-1: checkOrThrow("https://api.example.com", ["api.example.com"]) → no throw
✅ AC-NN-2: checkOrThrow("https://evil.com", ["api.example.com"]) → AccessDeniedException[LINGS-S01] + contains "evil.com"
✅ AC-NN-3: checkOrThrow("https://x.com", []) → throw(empty whitelist 拒绝所有)
✅ AC-NN-4: checkOrThrow(null, ["x"]) → throw("URL must not be null/empty")
✅ AC-NN-5: checkOrThrow("not-a-url", ["x"]) → throw("Malformed URL")
✅ AC-NN-6: checkOrThrow("https://x.com", null) → throw(null + empty 等价)
```

每个 case 直接调静态 helper,无需 mock,极快(~50ms / case)。

### 步骤 3(5 min):跑 L2 真发请求 IT,验证 12 hook points 实际拦截

```bash
mvn -pl lingshu-core test -Dtest=McpHttpDomainGuardIT
```

**预期看到 3 个 PASS(AC-NN-7 SSE miss + AC-NN-8 hit + AC-NN-9 connect miss)**:
```
✅ AC-NN-7: SSE conn + domainWhitelist=[evil.com] + 真 mock server 127.0.0.1 → callTool 返回 [LINGS-S01] Domain not whitelisted: 127.0.0.1
✅ AC-NN-8: StreamableHttp conn + domainWhitelist=[127.0.0.1] + 真 mock server → callTool 成功(200 OK)
✅ AC-NN-9: StreamableHttp conn + domainWhitelist=[evil.com] + 真 mock server → start() 失败,state=FAILED
```

IT 用 JDK `com.sun.net.httpserver.HttpServer` 开真 socket mock server,绑 127.0.0.1 + 内核分端口(避 CI 8080 冲突)。

### 步骤 4(5 min):跑 L2 装配 IT,验证 factory 转发 domainWhitelist

```bash
mvn -pl lingshu-core test -Dtest=McpServerConnectionFactoryTest
```

**预期看到全部 PASS(原有 case + 新增 1 case)**:
```
✅ 原 case: factory create(STDIO/SSE/STREAMABLE_HTTP cfg) → 返对应 conn + state=IDLE
✅ 🆕 AC-NN-9: factory create(STREAMABLE_HTTP cfg with domainWhitelist=[bad.com]) → conn.callTool 返 [LINGS-S01]
```

**关键**:`McpServerConnectionFactory.create(cfg)` 自身 0 改动,`domainWhitelist` 通过 `cfg.domainWhitelist` getter 传入。

### 步骤 5(10 min):跑 L1 + L2 回归,验证 18 现有测试保持 PASS

```bash
mvn -pl lingshu-core test -Dtest='McpServerConnection*Test'
```

**预期看到 18 个 PASS(0 回归)**:
- 9 stdio tests:0 改动(stdio 不发 HTTP,不走 checkOrThrow)
- 9 SSE/Streamable HTTP tests:cfg builder 加 `.domainWhitelist(["localhost", "127.0.0.1"])`

如果 fail → 检查 cfg builder 是否漏加 `.domainWhitelist(...)`(本 Story commit 已修)。

---

## 2. 故障排查(共 4 个常见坑)

### 坑 1:`checkOrThrow` 编译错误:`URI not found`

**症状**:`找不到符号: 类 URI` 编译失败
**根因**:`McpHttpSupport.java` 没 import `java.net.URI`
**解决**:本 Story commit 已加 `import java.net.URI;`(JDK 1.1 standard)

### 坑 2:HttpServer.create() 后请求挂死

**症状**:`McpHttpDomainGuardIT` 3 个 case 全 timeout 30s
**根因**:`HttpServer.create()` 只构造 server,**必须**调 `server.start()` 才 bind socket 接受连接
**解决**:`@BeforeEach startServer()` 加 `server.start();`(本 Story commit 已修)

### 坑 3:现有 18 MCP 测试 fail:domain not whitelisted

**症状**:`SseMcpServerConnectionStartTest` 等 9 个测试 fail with `[LINGS-S01] Domain not whitelisted`
**根因**:Story #033 加 checkOrThrow 守卫,**现有测试 cfg builder 漏加** `.domainWhitelist(["localhost", "127.0.0.1"])`
**解决**:
```bash
# 检查 9 个测试文件
grep -l "McpServerConfig.builder" lingshu-core/src/test/java/ai/lingshu/core/mcp/SseMcp*Test.java
grep -l "McpServerConfig.builder" lingshu-core/src/test/java/ai/lingshu/core/mcp/StreamableHttp*Test.java

# 每个 .builder() 后追加:
.domainWhitelist(Arrays.asList("localhost", "127.0.0.1"))
```
本 Story commit 已修 9 个文件。

### 坑 4:`McpServerConfig.domainWhitelist` 默认值是 null 不是 empty

**症状**:`@Builder.Default List<String> domainWhitelist = new ArrayList<>()` 编译器警告
**根因**:Lombok `@Builder.Default` 需要明确初始化,**不能**用 `Arrays.asList()`(那 immutable)
**解决**:本 Story 用 `new ArrayList<>()`(可变空 list,符合 `@Builder.Default` 契约 + 0 警告)。

---

## 3. 后续阅读

- **spec.md** — Story #033 完整 spec(Path B + Mitigation 1 WHY / WHO / WHAT / AC-NN / R-24 风险 / OQ-Future)
- **data-model.md** — `McpHttpSupport.checkOrThrow` 3 段契约 + `McpServerConfig.domainWhitelist` + 12 hook points 集成清单
- **plan.md** — 实施接口 / 文件 / 测试策略
- **tasks.md** — T01-T12 + 9 AC-NN validate + R-13 自查 + 11 doc-sync + 5 PR
- **dsh §6.3 WhitelistedHttpClient** — Story #028 落地,本 Story 复用其 `AccessDeniedException[LINGS-S01]`
- **README.md Story #033 retrospective** — 业务视角的故事
- **memory note `feedback-mcp-covers-http-not-local-tool.md`** — 2026-10-01 用户纠正 + Path B + Mitigation 1 决策

---

## 4. 时间戳

- **创建**:2026-10-01
- **最后更新**:2026-10-01
- **对应 dsh 版本**:v1.5.49 → v1.5.50(待 T-doc-6 落地)