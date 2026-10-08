# Story #034 `a2a-http-domain-guard` — Quickstart(30 min)

> **面向 Alice(Java 后端开发,用过 Spring Boot,首次接触 LingShu A2A 域)
> **目标**:30 分钟内跑通"A2A Path B + 复用 MCP #033 helper" + per-remote-agent whitelist + factory wiring 链路
> **前置**:`#001` + `#003` + `#009a/c/d/e` + `#028` + `#033` 已合 + JDK 8+ + Maven 3.6.3+

---

## 0. 学完能做什么

- 理解 LingShu §6.5 (2.1) Sandbox SPI 的「**Path B + 复用 helper**」 pattern:A2A HTTP transport 走 check-only 钩子,不引入完整 `WhitelistedHttpClient`
- 看懂 `McpHttpSupport.checkOrThrow(url, whitelist)` 静态守卫契约(从 #033 复用)
- 看懂 `AgentRef.domainWhitelist` per-remote-agent 配置 + yml `agent.a2a.remote-agents[*].domain-whitelist` key
- 看懂 **2 hook points**(`fetchCard` + `jsonRpcCall`)集成,100% A2A HTTP 端点拦截
- 看懂 `HttpJsonRpcA2aTransportFactory` unique baseUrl 分组 + union whitelist 算法
- 跑一次 L1 unit + L2 IT + R-13 dep-tree 自查,验证 13 case 全过 + 0 binary delta 第 19 次

---

## 1. 5 步跑通(每步 5 min)

### 步骤 1(5 min):克隆主仓 + 看 A2A 域顶层 layout

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
ls lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/
```

**预期看到 7 个 A2A 文件**:
- `HttpJsonRpcA2aTransport.java`(本 Story T02 改)
- `HttpJsonRpcA2aTransportAutoConfiguration.java`(本 Story T03 改)
- `HttpJsonRpcA2aTransportProvider.java`
- `AgentCardCache.java`
- `AgentRef.java`(实际在 `lingshu-core/runtime/AgentRef.java`,本 Story T01 改)
- `RemoteAgentTool.java`
- `RemoteAgentToolAutoConfiguration.java`
- `RemoteAgentToolLifecycle.java`
- `RemoteAgentSchemaBuilder.java`

### 步骤 2(5 min):看 A2A HTTP 拓扑 + MCP #033 守卫 helper

```bash
cat lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentRef.java   # 4 字段 POJO(name/url/priority/domainWhitelist)
cat lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java | head -50
grep -n "checkOrThrow" lingshu-core/src/main/java/ai/lingshu/core/mcp/McpHttpSupport.java
```

**关键观察**:
- `AgentRef` 是 per-remote-agent POJO,`domainWhitelist` 是第 4 字段
- `HttpJsonRpcA2aTransport` 是 per-baseUrl 单实例 + JDK `HttpClient`
- `McpHttpSupport.checkOrThrow(url, whitelist)` 是 MCP #033 已落的守卫 helper,A2A 直接 import 复用

### 步骤 3(10 min):跑 L1 unit + L2 IT

```bash
mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportCheckOrThrowTest
# 预期 6/6 pass(AC-1.1 ~ AC-1.6)

mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportDomainGuardIT
# 预期 3/3 pass(AC-2.1 ~ AC-2.3)

mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportAutoConfigurationTest
# 预期全 pass,包含 +1 factory dispatch case(AC-3.1)
```

### 步骤 4(5 min):跑 R-13 dep-tree 自查

```bash
# baseline
git stash
mvn -pl lingshu-core dependency:tree > /tmp/dep-pre.txt
mvn -pl lingshu-a2a-client dependency:tree > /tmp/dep-a2a-pre.txt
git stash pop

# post
mvn -pl lingshu-core dependency:tree > /tmp/dep-post.txt
mvn -pl lingshu-a2a-client dependency:tree > /tmp/dep-a2a-post.txt

# diff
diff /tmp/dep-pre.txt /tmp/dep-post.txt
diff /tmp/dep-a2a-pre.txt /tmp/dep-a2a-post.txt
```

**预期**:仅时间戳差异 = **0 binary delta 第 19 次 PASS**。

### 步骤 5(5 min):看 PR body + AC 验证输出

**PR body 末尾必须包含**:
```markdown
### R-13 dependency:tree 自查

`mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳差异 = **0 binary delta 第 19 次 PASS**
`mvn -pl lingshu-a2a-client dependency:tree` pre/post diff 仅时间戳差异 = **0 binary delta 第 19 次 PASS**

纯 JDK 8 `URI.create` + `List.contains` + `ArrayList` + `AccessDeniedException`(Story #028 已落)+ JDK standard,
0 新 binary 引入。
```

---

## 2. yml 配置示例

### 2.1 最小配置(默认 deny all)

```yaml
agent:
  a2a:
    remoteAgents:
      - name: alice
        url: https://alice.example.com
        # 🆕 Story #034 — 不写 domain-whitelist 默认 empty = deny all HTTP
```

**运行时行为**:`fetchCard("alice")` 抛 `AccessDeniedException[LINGS-S01] Domain not whitelisted: alice.example.com`

### 2.2 推荐配置(显式 whitelist)

```yaml
agent:
  a2a:
    remoteAgents:
      - name: alice
        url: https://alice.example.com
        domain-whitelist:
          - alice.example.com
          - alice-internal.example.com
      - name: bob
        url: https://bob.example.com
        domain-whitelist:
          - bob.example.com
```

**运行时行为**:`alice` 允许 `alice.example.com` + `alice-internal.example.com`;`bob` 仅允许 `bob.example.com`。

### 2.3 测试 fixture 配置(localhost)

```yaml
agent:
  a2a:
    remoteAgents:
      - name: test-alice
        url: http://127.0.0.1:8080
        domain-whitelist:
          - 127.0.0.1
```

**注意**:`http://127.0.0.1:8080` 的 host 是 `127.0.0.1`,**不是** `localhost`(DNS 解析可能给出不同 host);测试 fixture 必须用 IP 而非 hostname,避免 `#033` 35 files updated 的踩坑。

---

## 3. 常见错误 + 排错

| 错误 | 原因 | 修复 |
|---|---|---|
| `AccessDeniedException[LINGS-S01] Domain not whitelisted: 127.0.0.1` | 测试 fixture 没配 `domain-whitelist: [127.0.0.1]` | 在 yml 或 builder 加 `Arrays.asList("127.0.0.1")` |
| `AccessDeniedException[LINGS-S01] Domain not whitelisted: localhost` | yml 写 `localhost` 但 fixture URL 是 `127.0.0.1` | URL 和 whitelist 用同一形态(都 IP 或都 hostname)|
| `IllegalArgumentException: domainWhitelist must not be null` | 5-arg ctor 传 null | 改传 `Collections.emptyList()` |
| `Malformed URL: ...` | URL 格式错误 | 检查 yml `url` 字段,加 scheme(`http://` 或 `https://`)|
| Factory 输出 0 transport | `cfg.getA2a().getRemoteAgents()` 为空 | 默认行为,fallback 到单实例 transport;或加 remoteAgent 配置 |

---

## 4. R-13 dependency:tree 自查清单(实施完成后必跑)

- [ ] `mvn -pl lingshu-core dependency:tree` pre/post diff 仅时间戳
- [ ] `mvn -pl lingshu-a2a-client dependency:tree` pre/post diff 仅时间戳
- [ ] `banned-dependencies` enforcer Rule 0 passed
- [ ] PR body 末尾 `### R-13 dependency:tree 自查` 节已贴

---

## 5. 与 MCP #033 关键差异对照

| 维度 | MCP #033 | A2A #034 |
|---|---|---|
| Hook 点数量 | 12(SSE 7 + Streamable HTTP 5)| **2**(`fetchCard` + `jsonRpcCall`)|
| 配置粒度 | per-MCP-server(`McpServerConfig.domainWhitelist`)| per-remote-agent(`AgentRef.domainWhitelist`)|
| 拓扑 | 每 server 1 连接(McpServerConnection)| per-baseUrl 1 transport(unique URL → 1 instance)|
| Whitelist union 语义 | 单 server 单 whitelist(无 union)| **同 URL 多 AgentRef whitelist 自动 union**(`HashSet` 去重)|
| Transport wiring | per-server `McpServerConnectionFactory.create()` | per-remote-agent `HttpJsonRpcA2aTransportFactory.buildByAgentName()` |
| 测试 fixture update | 35 files | 预计 5—8 files |
| R-13 baseline | 第 18 次 PASS | **第 19 次 PASS** |

**核心对称性**:**复用 `McpHttpSupport.checkOrThrow` + 复用 `LINGS-S01`**(两个 Story 共享 sandbox domain-guard 语义),这是 sandbox 一致性的关键。
