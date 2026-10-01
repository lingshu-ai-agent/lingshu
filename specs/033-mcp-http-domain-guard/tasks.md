# Story #033 `mcp-http-domain-guard` — Tasks(P1–P6 + AC-NN validate)

> **配套**:[spec.md](spec.md)(WHY/WHO/WHAT/AC),[plan.md](plan.md)(接口/文件/测试),[quickstart.md](quickstart.md)(30 min 跑通),[data-model.md](data-model.md)
> **核心**:MCP HTTP transports Path B + Mitigation 1 — `McpHttpSupport.checkOrThrow` 守卫钩子 + per-server `domainWhitelist` + fallback 语义

---

## P1:源码改动(4 modify + 1 准备)

### T01:`McpHttpSupport.checkOrThrow()` 静态 helper(~30 行)

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpHttpSupport.java`

**改动**:
1. 加 import:
   ```java
   import ai.lingshu.core.slot.AccessDeniedException;
   import java.net.URI;
   ```
2. 在类末尾(`postNotification` 之后)新增 public static method(plan §2.1):
   ```java
   public static void checkOrThrow(String url, List<String> whitelist) {
       if (url == null || url.isEmpty()) {
           throw new AccessDeniedException("URL must not be null/empty");
       }
       String host;
       try {
           host = URI.create(url).getHost();
       } catch (IllegalArgumentException e) {
           throw new AccessDeniedException("Malformed URL: " + url);
       }
       if (host == null || host.isEmpty()) {
           throw new AccessDeniedException("URL has no host: " + url);
       }
       if (whitelist == null || whitelist.isEmpty() || !whitelist.contains(host)) {
           throw new AccessDeniedException("Domain not whitelisted: " + host);
       }
   }
   ```

**验证**:`mvn -pl lingshu-core compile`(必须 0 编译错误)

**Commit**:`feat(mcp): Story #033 T01 — McpHttpSupport.checkOrThrow static helper`

---

### T02:`McpServerConfig.domainWhitelist` 字段

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConfig.java`

**改动**:在 `reconnectCapMs` 字段之后新增:
```java
/** Story #033 — domain guard whitelist for MCP HTTP requests. */
@Builder.Default
List<String> domainWhitelist = new ArrayList<>();
```

**验证**:`mvn -pl lingshu-core compile`(0 编译错误)+ `@Builder.Default` import 测试通过)

**Commit**:跟随 T01 一起 commit(同 PR)。

---

### T03:`AgentConfig.ServerConfig.domainWhitelist` 字段

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`

**改动**:在 `ServerConfig` inner class 的 `reconnectCapMs` 字段之后新增:
```java
@JsonProperty("domain-whitelist")
@Builder.Default
List<String> domainWhitelist = new ArrayList<>();
```

**验证**:跑 `AgentConfigMcpBackwardCompatTest`(现有 yml 反序列化测试)保证 back-compat。

**Commit**:跟随 T01 一起 commit。

---

### T04:`McpTransportAutoConfiguration.toRuntimeConfig` fallback 逻辑

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportAutoConfiguration.java`

**改动**:
1. `toRuntimeConfig` 签名加 `AgentConfig agentConfig` 第二参
2. `mcpServerConfigs` 循环传 `agentConfig` 给 `toRuntimeConfig`
3. `toRuntimeConfig` 末尾追加 fallback 逻辑(plan §2.4):
   ```java
   List<String> effectiveWhitelist = sc.getDomainWhitelist();
   if (effectiveWhitelist == null || effectiveWhitelist.isEmpty()) {
       if (agentConfig != null && agentConfig.getSandbox() != null
           && agentConfig.getSandbox().getDomainWhitelist() != null
           && !agentConfig.getSandbox().getDomainWhitelist().isEmpty()) {
           effectiveWhitelist = agentConfig.getSandbox().getDomainWhitelist();
       }
   }
   b.domainWhitelist(effectiveWhitelist);
   ```

**验证**:`mvn -pl lingshu-core compile`(0 编译错误)+ `McpTransportAutoConfigurationTest` (如存在) PASS。

**Commit**:跟随 T01 一起 commit。

---

### T05:`SseMcpServerConnection` 集成 7 hook points

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/SseMcpServerConnection.java`

**改动**:
1. 加 import:`java.util.List` 已用
2. 新增 final field `private final List<String> domainWhitelist;`(在 `reconnectCapMs` 之后)
3. 构造器末尾追加:`this.domainWhitelist = cfg.getDomainWhitelist() != null ? new ArrayList<>(cfg.getDomainWhitelist()) : new ArrayList<>();`
4. **7 处 hook point 集成**(plan §2.5):每个 hook 都是 1 行 `McpHttpSupport.checkOrThrow(url, domainWhitelist);` 前置

**注意**:确保 hook 在 `McpHttpSupport.postJsonRpc/postNotification/getJson` 调用**之前**(不是之后)。

**验证**:`mvn -pl lingshu-core compile`(0 编译错误)。

**Commit**:跟随 T01 一起 commit。

---

### T06:`StreamableHttpMcpServerConnection` 集成 5 hook points

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/mcp/StreamableHttpMcpServerConnection.java`

**改动**:
1. 新增 final field `private final List<String> domainWhitelist;`
2. 构造器追加:`this.domainWhitelist = cfg.getDomainWhitelist() != null ? new ArrayList<>(cfg.getDomainWhitelist()) : new ArrayList<>();`
3. **5 处 hook point 集成**(plan §2.6):每个 hook 都是 1 行 `McpHttpSupport.checkOrThrow(url, domainWhitelist);` 前置

**验证**:`mvn -pl lingshu-core compile`(0 编译错误)。

**Commit**:跟随 T01 一起 commit。

---

## P2:测试文件创建(2 new + 1 modify)

### T07:`McpHttpSupportCheckOrThrowTest` L1(6 cases)

**文件**:新创建 `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpSupportCheckOrThrowTest.java`

**测试模板**(JUnit 5 + AssertJ):
```java
package ai.lingshu.core.mcp;

import ai.lingshu.core.slot.AccessDeniedException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class McpHttpSupportCheckOrThrowTest {

    @Test
    public void hitReturnsUrlWhenAllowed() {
        // 不抛
        McpHttpSupport.checkOrThrow(
            "https://api.example.com/path",
            Arrays.asList("api.example.com"));
        // ... no exception
    }

    @Test
    public void missThrowsAccessDeniedWithLingsS01() {
        assertThatThrownBy(() -> McpHttpSupport.checkOrThrow(
            "https://evil.com",
            Arrays.asList("api.example.com")))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("evil.com");
    }

    @Test
    public void emptyWhitelistDeniesAll() {
        assertThatThrownBy(() -> McpHttpSupport.checkOrThrow(
            "https://x.com",
            Collections.emptyList()))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]");
    }

    @Test
    public void nullUrlThrowsUrlMustNotBeNullEmpty() {
        assertThatThrownBy(() -> McpHttpSupport.checkOrThrow(
            null, Arrays.asList("x.com")))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("URL must not be null/empty");
    }

    @Test
    public void malformedUrlThrowsMalformedUrl() {
        assertThatThrownBy(() -> McpHttpSupport.checkOrThrow(
            "not-a-url", Arrays.asList("x.com")))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("Malformed URL");
    }

    @Test
    public void nullWhitelistDeniesAll() {
        assertThatThrownBy(() -> McpHttpSupport.checkOrThrow(
            "https://x.com", null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]");
    }
}
```

**验证**:`mvn -pl lingshu-core test -Dtest=McpHttpSupportCheckOrThrowTest`(6 PASS)

**Commit**:跟随 T01 一起 commit(可拆 2 commit:`feat` + `test`)。

---

### T08:`McpHttpDomainGuardIT` L2(3 cases)

**文件**:新创建 `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpHttpDomainGuardIT.java`

**测试模板**(用 JDK `com.sun.net.httpserver.HttpServer` mock server):

```java
package ai.lingshu.core.mcp;

import ai.lingshu.core.runtime.McpTransportType;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

public class McpHttpDomainGuardIT {

    private HttpServer server;
    private int port;

    @BeforeEach
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/health", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.createContext("/tools/call", ex -> {
            byte[] body = "{\"result\":{\"content\":\"mock-call-result\"}}"
                .getBytes();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    public void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    public void sseCallToolDomainMissReturnsLingsS01Error() {
        // SSE conn 配 evil.com whitelist + 真 mock server(127.0.0.1 不在 whitelist)
        McpServerConfig cfg = McpServerConfig.builder()
            .name("test-sse")
            .transport(McpTransportType.SSE)
            .url("http://127.0.0.1:" + port)
            .domainWhitelist(Arrays.asList("evil.com"))   // ← 故意 miss
            .heartbeatIntervalMs(50L)
            .heartbeatTimeoutMs(2000L)
            .build();
        SseMcpServerConnection conn = new SseMcpServerConnection(cfg);
        // 不 start() 直接 callTool(走 hook → throw → 转 McpCallResult.error)
        McpCallResult result = conn.callTool("foo", null);
        assertThat(result.isError()).isTrue();
        assertThat(result.getErrorMessage())
            .startsWith("[LINGS-S01]")
            .contains("Domain not whitelisted: 127.0.0.1");
    }

    @Test
    public void streamableHttpCallToolDomainHitRealServer() {
        // StreamableHttp conn 配 127.0.0.1 whitelist + 真 mock server
        McpServerConfig cfg = McpServerConfig.builder()
            .name("test-sh")
            .transport(McpTransportType.STREAMABLE_HTTP)
            .url("http://127.0.0.1:" + port)
            .domainWhitelist(Arrays.asList("127.0.0.1"))
            .heartbeatIntervalMs(50L)
            .heartbeatTimeoutMs(2000L)
            .build();
        StreamableHttpMcpServerConnection conn = new StreamableHttpMcpServerConnection(cfg);
        // 不 start() 直接 callTool 也会 fail(state != CONNECTED),改成 start() 后
        conn.start();
        // 等 CONNECTED
        long deadline = System.currentTimeMillis() + 3000L;
        while (conn.state() != ConnectionState.CONNECTED
               && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        McpCallResult result = conn.callTool("foo", null);
        assertThat(result.isError()).isFalse();
        assertThat(result.getContent()).contains("mock-call-result");
        conn.close();
    }

    @Test
    public void streamableHttpConnectDomainMissThrowsLingsS01() {
        McpServerConfig cfg = McpServerConfig.builder()
            .name("test-miss")
            .transport(McpTransportType.STREAMABLE_HTTP)
            .url("http://127.0.0.1:" + port)
            .domainWhitelist(Arrays.asList("evil.com"))   // ← 故意 miss
            .heartbeatIntervalMs(50L)
            .heartbeatTimeoutMs(2000L)
            .build();
        StreamableHttpMcpServerConnection conn = new StreamableHttpMcpServerConnection(cfg);
        conn.start();
        // 等若干秒看 state 变化
        long deadline = System.currentTimeMillis() + 3000L;
        while (System.currentTimeMillis() < deadline) {
            if (conn.state() == ConnectionState.FAILED) break;
            try { Thread.sleep(50); } catch (InterruptedException e) { break; }
        }
        assertThat(conn.state()).isEqualTo(ConnectionState.FAILED);
        conn.close();
    }
}
```

**验证**:`mvn -pl lingshu-core test -Dtest=McpHttpDomainGuardIT`(3 PASS)

**Commit**:跟随 T01 一起 commit。

---

### T09:`McpServerConnectionFactoryTest` 增 1 case

**文件**:修改 `lingshu-core/src/test/java/ai/lingshu/core/mcp/McpServerConnectionFactoryTest.java`

**新增 case**:
```java
@Test
public void streamableHttpConnectionCarriesDomainWhitelistForGuard() {
    McpServerConfig cfg = McpServerConfig.builder()
        .name("test-guard")
        .transport(McpTransportType.STREAMABLE_HTTP)
        .url("http://example.com")
        .domainWhitelist(Arrays.asList("bad.com"))
        .build();
    McpServerConnection conn = McpServerConnectionFactory.create(cfg);
    // 通过 reflection 或 protected getter 拿到 domainWhitelist field,验证非空
    // 简化方案:直接调 callTool 看 error message
    McpCallResult result = conn.callTool("foo", null);
    assertThat(result.isError()).isTrue();
    assertThat(result.getErrorMessage()).startsWith("[LINGS-S01]");
}
```

**验证**:`mvn -pl lingshu-core test -Dtest=McpServerConnectionFactoryTest`(全部 PASS,包括 1 new case)

**Commit**:跟随 T01 一起 commit。

---

## P3:回归测试 + 现有测试 cfg 改写

### T10:现有 18 个 MCP 测试跑一遍,识别哪些需要 cfg builder 加 `domainWhitelist=["localhost", "127.0.0.1"]`

**命令**:
```bash
mvn -pl lingshu-core test -Dtest='McpServerConnection*Test,McpServer*Test,McpTransport*Test'
```

**预期发现**:
- stdio 测试:0 改动(stdio 不发 HTTP,不走 checkOrThrow)
- SSE / Streamable HTTP 测试:每测试 cfg builder 加 `.domainWhitelist(["localhost", "127.0.0.1"])`

**改写清单**(9 个测试):
1. `SseMcpServerConnectionStartTest.java` — 找到 `McpServerConfig.builder()` 调用,加 `.domainWhitelist(["localhost", "127.0.0.1"])`
2. `SseMcpServerConnectionHeartbeatTest.java` — 同上
3. `SseMcpServerConnectionReconnectTest.java` — 同上
4. `SseMcpServerConnectionCloseAndCallTest.java` — 同上
5. `SseMcpServerConnectionListenerTest.java` — 同上
6. `StreamableHttpMcpServerConnectionStartTest.java` — 同上
7. `StreamableHttpMcpServerConnectionHeartbeatTest.java` — 同上
8. `StreamableHttpMcpServerConnectionReconnectTest.java` — 同上
9. `StreamableHttpMcpServerConnectionCloseAndCallTest.java` — 同上

**commit message**:
```
test(mcp): SseMcpServerConnection*Test + StreamableHttpMcpServerConnection*Test
add .domainWhitelist(["localhost", "127.0.0.1"]) to keep green
under Story #033 sandbox guard
```

**Commit**:跟随 T01 一起 commit(可拆 2 commit:source + test)。

---

## P4:AC-NN 黑盒验证 + R-13 自查

### T11:跑 AC-NN validate(9 cases 全部 PASS)

**命令**:
```bash
# L1
mvn -pl lingshu-core test -Dtest=McpHttpSupportCheckOrThrowTest
# L2
mvn -pl lingshu-core test -Dtest=McpHttpDomainGuardIT
# Factory case
mvn -pl lingshu-core test -Dtest=McpServerConnectionFactoryTest
```

**预期**:全部 PASS,共 9 new + 18 existing = 27 tests。

### T12:R-13 mitigation (d) dep-tree 自查(0 binary delta 第 18 次 PASS)

**命令**:
```bash
# pre 镜像
mvn -pl lingshu-core dependency:tree -DoutputType=text > /tmp/lingshu-core-deps-pre.txt 2>&1

# impl 已完成(本 Story 9 文件改动,0 Maven 引入)

# post 镜像
mvn -pl lingshu-core dependency:tree -DoutputType=text > /tmp/lingshu-core-deps-post.txt 2>&1

# diff
diff /tmp/lingshu-core-deps-pre.txt /tmp/lingshu-core-deps-post.txt
```

**预期**:`diff` 仅时间戳差异(`[INFO] lingshu-core:jar:0.1.0-SNAPSHOT:...` 类似,**无** Maven coordinate 变化)。`grep -v '[INFO] BUILD '` 后两个文件**应完全一致**。

**PR body 必须包含**:
```
### R-13 dependency:tree 自查
pre:  /tmp/lingshu-core-deps-pre.txt
post: /tmp/lingshu-core-deps-post.txt
diff: 仅时间戳差异,0 binary delta(第 18 次 PASS)
```

**Commit**:不单独 commit(T11 验证日志附在 PR body)。

---

## P5:文档同步(8 + 7 PR 共 15 件)

### T-doc-1:README.md 加 Story #033 retrospective

**文件**:`README.md`

**改动**:
1. 「更新日期」段加 🆕 v1.5.50 Story #033 顶部 blockquote
2. 「核心特性」段补 🛡️ MCP domain guard 已上线 bullet
3. 「Story 路线图」段追加 #033 retrospective(Path B + Mitigation 1 + 12 hook points + 9 case AC + R-13 第 18 次 PASS)

### T-doc-2:specs/032 archive(`spec/plan/tasks` 三件套)

**已生成**:`specs/033-mcp-http-domain-guard/{spec,plan,tasks,quickstart,data-model}.md` 5 件。

### T-doc-3:specs/ROADMAP.md ✅ 加 #033 行

**文件**:`specs/ROADMAP.md`

**改动**:段一 ✅ 已完成加 #033 行,段二 🟡 待补 #033 划掉,统计 19 已合 → 20 已合 / 1 待补 → 0 待补 🎉。

### T-doc-4:constitution.md §10 R-13 缓解 Story 列表加 #033

**文件**:`.specify/memory/constitution.md`

**改动**:§10 R-13 缓解 Story 列表补 `#033` 行**第 18 次** R-13 mitigation (d) PASS 0 binary delta + 累计计数 21 → **22 个 Story**。

### T-doc-5:CLAUDE.md v1.3.45 + dsh v1.5.49 → v1.5.50

**文件**:`CLAUDE.md`

**改动**:head 段加 v1.3.45 Story #033 entry(15 节)+「对应设计文档」版本号 v1.5.49 → v1.5.50。

### T-doc-6:dsh §13 changelog 加 v1.5.50 行

**文件**:`~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md`

**改动**:§13 changelog 表格新增 v1.5.50 行 + 15 节记录 Story #033 完成内容。

### T-doc-7:dsh §6.5 (2.1) 加 Story #033 marker

**文件**:`dsh_agent_design.md`

**改动**:§6.5 (2.1) `McpHttpSupport` 段加「🆕 v1.5.50 Story #033 落地」blockquote + `checkOrThrow` 静态 helper 1 段说明 + §6.3 `WhitelistedHttpClient` 段 cross-ref 加「Story #033 MCP 端同步落地」blockquote。

---

## P6:PR 创建

### T-pr-1:`git status` + `git diff` + `git log` 三个 bash 并行

### T-pr-2:commit(推荐拆 2 commit)

**commit 1**(source + new tests):
```
feat(mcp): Story #033 mcp-http-domain-guard — MCP HTTP transports Path B guard

MCP 3 transport 保留 raw `HttpURLConnection` 不变,加 check-only 钩子
`McpHttpSupport.checkOrThrow(url, whitelist)` 在每个 MCP HTTP 请求前调
用。复用 Story #028 `AccessDeniedException[LINGS-S01]` 错误语义。

12 hook point 覆盖:SSE 7(callTool/doConnect x3/heartbeat/readSseLoop/relistTools)
+ Streamable HTTP 5(callTool/doConnect x3/heartbeat)。`McpServerConfig`
+ `AgentConfig.ServerConfig` 扩 `domainWhitelist: List<String>` 字段,
`McpTransportAutoConfiguration` fallback 到 `agent.sandbox.domain-whitelist`。

9 new case(6 L1 + 3 L2),18 existing MCP 测试 0 回归。
R-13 mitigation (d) PASS 0 binary delta(第 18 次)。

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
```

**commit 2**(docs sync):
```
docs(claude): T-doc-9 — v1.3.45 Story #033 SPEC + PLAN + TASKS 三件套归档

🤖 Generated with [Claude Code](https://claude.com/claude-code)

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>
```

### T-pr-3:`git push -u origin feature/story-033-mcp-http-domain-guard`

### T-pr-4:`gh pr create` with full PR body

**PR 模板**:
```bash
gh pr create --title "feat(mcp): Story #033 mcp-http-domain-guard — MCP HTTP transports Path B guard" --body "$(cat <<'EOF'
## Summary

Story #033 落地 sandbox 防护深度(Path B + Mitigation 1):MCP 3 transport 保留 raw `HttpURLConnection` 不变,**仅加** `McpHttpSupport.checkOrThrow(url, whitelist)` check-only 守卫钩子在每个 MCP HTTP 请求前调用。复用 Story #028 `AccessDeniedException[LINGS-S01]` 错误语义,与 WebFetchTool (Story #032) 共享 ErrorCode。

## Why (not Path A)

Path A = MCP 也走 `WhitelistedHttpClient.get/post/getStream` 整体路径。**会改 MCP 请求流**,破坏 SSE 长流 / MCP 5 步握手 / JSON-RPC framing 状态机(2026-10-01 用户确认走 Path B + Mitigation 1,见 memory `feedback-mcp-covers-http-not-local-tool.md`)。

## Key Changes

| # | 文件 | 改动 |
|---|---|---|
| 1 | `McpHttpSupport.java` | +1 public static `checkOrThrow(url, whitelist)` (~30 行) |
| 2 | `McpServerConfig.java` | +1 field `domainWhitelist: List<String> = []` |
| 3 | `AgentConfig.java` | `ServerConfig` +1 field `domain-whitelist` |
| 4 | `McpTransportAutoConfiguration.java` | `toRuntimeConfig` 加 fallback 到 sandbox |
| 5 | `SseMcpServerConnection.java` | +1 final field + **7 hook points** |
| 6 | `StreamableHttpMcpServerConnection.java` | +1 final field + **5 hook points** |
| 7 | `McpHttpSupportCheckOrThrowTest.java` (new L1) | 6 cases |
| 8 | `McpHttpDomainGuardIT.java` (new L2) | 3 cases |
| 9 | `McpServerConnectionFactoryTest.java` | +1 case |

**总 12 hook points**(SSE 7 + Streamable HTTP 5)= **100% MCP HTTP 请求拦截**。

## Acceptance Criteria

- AC-NN-1..6 (L1): `McpHttpSupportCheckOrThrowTest` 6 PASS
- AC-NN-7..9 (L2): `McpHttpDomainGuardIT` 3 PASS
- AC-NN-9 (Factory): `McpServerConnectionFactoryTest` +1 PASS
- 18 existing `McpServerConnection*Test` 全部 PASS(0 回归,9 SSE/Streamable HTTP 测试 cfg builder 加 `.domainWhitelist(["localhost", "127.0.0.1"])`)

## R-13 dependency:tree 自查

pre:  /tmp/lingshu-core-deps-pre.txt  
post: /tmp/lingshu-core-deps-post.txt  
diff: 仅时间戳差异,0 binary delta(**第 18 次 PASS**)

JDK 8 守住:`URI.create` / `List.contains` + Jackson / Lombok / Spring / Mockito / AssertJ 已锁 13 项依赖表内 + `com.sun.net.httpserver.HttpServer` JDK 内置。

## Key Invariants(关键不变项)

- `McpServerConnection` SPI 不变 (8 方法契约 0 改)
- `ConnectionState` enum 不变 (6 态 0 改)
- `McpToolAdapter` 不变 (callTool 失败 → McpCallResult.error)
- `McpTransport` 不变 (公开方法签名 0 改)
- `AgentConfig` 不可变契约**仅扩 1 字段**(`ServerConfig.domainWhitelist`, back-compat 旧 yml 0 改)
- `AgentFactory` SPI 不变 (@Autowired 6-Router ctor 不动)
- 9 Slot 体系不变 (MCP 仍 Slot 9)
- `AccessDeniedException[LINGS-S01]` 不变 (复用 Story #028)
- **0 新 Maven 依赖**
- **0 新 ErrorCode**

## Risk Register

- **R-24 MCP sandbox bypass(R-13 子项)** — 本 Story **100% 缓解**:12 hook points 覆盖所有 MCP HTTP 端点。

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

### T-pr-5:验证 PR status = MERGEABLE

```bash
gh pr view <PR-number> --json state | grep MERGEABLE
```

---

## 时间戳

- **创建**:2026-10-01
- **最后更新**:2026-10-01
- **对应 dsh 版本**:v1.5.49 → v1.5.50(待 T-doc-6 落地)