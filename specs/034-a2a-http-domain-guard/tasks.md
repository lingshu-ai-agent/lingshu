# Story #034 `a2a-http-domain-guard` — Tasks(P1–P4 + AC-NN validate)

> **配套**:[spec.md](spec.md)(WHY/WHO/WHAT/AC),[plan.md](plan.md)(接口/文件/测试)
> **核心**:A2A HTTP transport Path B + 复用 MCP #033 helper — `AgentRef.domainWhitelist` + `HttpJsonRpcA2aTransport` 5-arg ctor + 2 hook point + factory wiring + 8 file fixture update

---

## P1:源码改动(3 modify)

### T01:`AgentRef.domainWhitelist` 字段

**文件**:`lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentRef.java`

**改动**:
1. 在 `priority` 字段之后新增(plan §2.1):
   ```java
   /** 🆕 Story #034 — per-remote-agent sandbox domain whitelist. ... */
   List<String> domainWhitelist;
   ```
2. Lombok `@Value` 自动生成 4-arg 构造器(`name` / `url` / `priority` / `domainWhitelist`),existing 3-arg ctor 构造器调用点 (`new AgentRef(name, url, priority)`) 需改为 4-arg 或保留通过 builder

**验证**:
- `mvn -pl lingshu-core compile`(0 编译错误)
- Lombok `@Value` 4-arg 构造器自动生成 OK
- 现有 `RemoteAgentToolTest` / `RemoteAgentSchemaBuilderTest` 等使用 `new AgentRef(name, url, priority)` 的地方全部识别 + 修复

**Commit**:`feat(a2a): Story #034 T01 — AgentRef.domainWhitelist field`

---

### T02:`HttpJsonRpcA2aTransport` 5-arg ctor + 2 hook points

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransport.java`

**改动**:
1. 加 import:
   ```java
   import ai.lingshu.core.mcp.McpHttpSupport;
   ```
2. 新增 final field `private final List<String> domainWhitelist;`
3. 新增 5-arg ctor + 保留 4-arg ctor 作为 backward-compat wrapper(委派到 5-arg + `Collections.emptyList()`)
4. `fetchCard(String agentName)` 第 116 行 `URI uri = ...;` 之后插入 hook #1:
   ```java
   McpHttpSupport.checkOrThrow(uri.toString(), this.domainWhitelist);
   ```
5. `jsonRpcCall(String method, JsonNode params)` 第 285 行 `URI uri = ...;` 之后插入 hook #2:同上
6. 新增 package-private accessor `List<String> getDomainWhitelist()`(test 用)

**验证**:
- `mvn -pl lingshu-a2a-client compile`(0 编译错误)
- `mvn -pl lingshu-a2a-client test`(现有 4-arg ctor 调用点全部走 backward-compat wrapper,**0 regression**)

**Commit**:`feat(a2a): Story #034 T02 — HttpJsonRpcA2aTransport 5-arg ctor + 2 hook points`

---

### T03:`HttpJsonRpcA2aTransportAutoConfiguration` factory wiring

**文件**:`lingshu-a2a-client/src/main/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfiguration.java`

**改动**:
1. 加 import:`AgentConfig` + `AgentRef` + `A2aTransport` + `HashMap` + `HashSet` + `ArrayList`
2. 加 `@Bean(name = "a2aTransportFactory_http-jsonrpc") public HttpJsonRpcA2aTransportFactory httpJsonRpcA2aTransportFactory(AgentConfig cfg, ObjectMapper json, AgentCardCache cardCache)`
3. 加 nested public static class `HttpJsonRpcA2aTransportFactory` 含 `buildByAgentName()` 方法(plan §2.3)
4. `RemoteAgentToolAutoConfiguration#remoteAgentTool()` 改为从 factory 拿 `Map<String, A2aTransport>`,根据 `agentName` 选 transport;单 URL 时降级到单实例(back-compat)

**验证**:
- `mvn -pl lingshu-a2a-client compile`(0 编译错误)
- `mvn -pl lingshu-a2a-client test`(所有 wiring 测试 pass)
- 单 URL back-compat 路径等价于现有行为(同 URL → 1 transport instance,agentName → transport map 长度 = unique URL 数)

**Commit**:`feat(a2a): Story #034 T03 — HttpJsonRpcA2aTransportAutoConfiguration factory wiring`

---

## P2:测试改动(2 new + 5—8 fixture update)

### T04:`HttpJsonRpcA2aTransportCheckOrThrowTest`(L1,~6 case,新文件)

**文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportCheckOrThrowTest.java`(new)

**改动**:6 个 L1 单元 case(plan §3.1):
- `AC-1.1 whitelistedHost_noThrow`
- `AC-1.2 nonWhitelistedHost_throws[LINGS-S01]`
- `AC-1.3 emptyWhitelist_deniesAll`
- `AC-1.4 nullUrl_throws`
- `AC-1.5 malformedUrl_throws`
- `AC-1.6 ipv4Host_extractedCorrectly`

**验证**:`mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportCheckOrThrowTest`(6/6 pass)

**Commit**:跟随 T03 一起 commit(同 PR)。

---

### T05:`HttpJsonRpcA2aTransportDomainGuardIT`(L2,~3 case,新文件)

**文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportDomainGuardIT.java`(new)

**改动**:3 个 L2 集成 case(plan §3.2):
- `AC-2.1 whitelistedHost_reachesConnected`(JDK `com.sun.net.httpserver.HttpServer` mock + `127.0.0.1` whitelist)
- `AC-2.2 nonWhitelistedHost_callToolDenied[LINGS-S01]`(verify mock server `getRequestCount() == 0`)
- `AC-2.3 nonWhitelistedHost_ToolResultError`(`RemoteAgentTool.execute` → `ToolResult.error("[LINGS-S01] ...")`)

**验证**:`mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportDomainGuardIT`(3/3 pass)

**Commit**:跟随 T03 一起 commit。

---

### T06:工厂分派测试(`HttpJsonRpcA2aTransportAutoConfigurationTest` +1 case)

**文件**:`lingshu-a2a-client/src/test/java/ai/lingshu/a2a/client/HttpJsonRpcA2aTransportAutoConfigurationTest.java`(modify)

**改动**:
- `AC-3.1 uniqueUrlPerAgent_createsNTransports`(2 remoteAgents 不同 URL → factory 输出 2 transport instance)

**验证**:`mvn -pl lingshu-a2a-client test -Dtest=HttpJsonRpcA2aTransportAutoConfigurationTest`(全 pass)

**Commit**:跟随 T03 一起 commit。

---

### T07:现有 A2A 测试 fixture update(5—8 files)

**文件**:预计涉及(list 见 plan §2.4):
- `HttpJsonRpcA2aTransportTest.java`
- `HttpJsonRpcA2aTransportProviderTest.java`
- `HttpJsonRpcA2aTransportAutoConfigurationTest.java`
- `RemoteAgentToolTest.java`
- `RemoteAgentToolLifecycleTest.java`
- `RemoteAgentToolAutoConfigurationTest.java`
- `RemoteAgentSchemaBuilderTest.java`(可能)
- `RemoteAgentTransportWiringIT.java`(可能)

**改动**:每个文件中所有 `new HttpJsonRpcA2aTransport(url, json, cardCache, timeout)` 调用点改为 5-arg 版本,加 `.domainWhitelist(Arrays.asList("127.0.0.1"))`(fixture 用 localhost 必须显式 whitelist);加 `import java.util.Arrays;`

**验证**:
- 跑 `mvn -pl lingshu-a2a-client test`,**0 false-positive deny**
- 任何漏更新的 fixture 立即 fix(参考 MCP #033 35 files updated 经验)

**Commit**:`test(a2a): Story #034 T07 — existing fixture whitelist update (~5-8 files)`

---

## P3:R-13 验证 + 文档同步

### T08:R-13 mitigation (d) dep-tree 验证

**步骤**:
1. `git stash`(临时保存改动)
2. `mvn -pl lingshu-core dependency:tree > /tmp/dep-pre.txt`
3. `mvn -pl lingshu-a2a-client dependency:tree > /tmp/dep-a2a-pre.txt`
4. `git stash pop`
5. 同样命令 post → diff pre/post
6. **期望**:仅时间戳差异 = **0 binary delta 第 19 次 PASS**

**验证**:diff 报告贴 PR body 末尾 `### R-13 dependency:tree 自查` 节

**Commit**:不单独 commit,跟随 T07 一起 commit。

---

### T09:CLAUDE.md + README.md + dsh + ROADMAP + constitution 同步

**文件**:
- `dsh_agent_design.md`:v1.5.50 → v1.5.51,§0 标题 + §13 changelog 新增 v1.5.51 行 + §5.6.3.1 补 `domainWhitelist` + §15.9 域 cross-ref
- `README.md`:「核心特性」补 🛡️ A2A 沙箱守卫已上线 bullet + 「Story 路线图」追加 #034 retrospective
- `specs/ROADMAP.md`:段一 ✅ 加 #034 行 + 段二 🟡 划掉 #034 + 段五 🎯 收口 + 统计 38 已合
- `constitution.md`:§10 R-13 缓解 Story 列表补 `#034` 第 19 次 PASS
- `CLAUDE.md`:v1.3.45 → v1.3.46

**验证**:`grep -c "Story #034"` 各文件 ≥ 1

**Commit**:`docs(claude): T-doc-9 — v1.3.46 Story #034 a2a-http-domain-guard 文档同步`

---

## P4:最终验证

### T10:全模块验证 + AC 黑盒总跑

**步骤**:
1. `mvn -pl lingshu-core -am compile`(0 错误)
2. `mvn -pl lingshu-a2a-client -am compile`(0 错误)
3. `mvn -pl lingshu-core test`(全 pass,无 regression)
4. `mvn -pl lingshu-a2a-client test`(全 pass,无 regression)
5. `mvn -DskipTests=true install`(全模块编译 OK)
6. **AC-NN-deps-***:`mvn dependency:tree` diff 仅时间戳 = 0 binary delta 第 19 次 PASS

**统计预期**:
- 全模块 pass 总数 = **673 pass**(= 660 pre-#034 chain + 13 新 case: 6 L1 + 3 L2 + 1 factory L1 + 3 现有 fixture update 内嵌)
- R-13 0 binary delta 第 19 次
- 0 新 Maven 依赖
- 0 新 ErrorCode

**Commit**:不单独 commit,跟随 T09 一起。

---

## Task 依赖图

```
T01 (AgentRef)
    ↓
T02 (HttpJsonRpcA2aTransport ctor + hooks)
    ↓
T03 (AutoConfiguration factory wiring)
    ↓
T04 (L1 unit)  ── T05 (L2 IT)  ── T06 (L1 factory)  ── T07 (fixture update)
                                                          ↓
                                                       T08 (R-13)
                                                          ↓
                                                       T09 (doc sync)
                                                          ↓
                                                       T10 (final validate)
```

---

## 实施节奏预估

| Task | 工作量 | 累计 |
|---|---|---|
| T01 | ~15 min | 15 min |
| T02 | ~30 min | 45 min |
| T03 | ~45 min | 1.5 hr |
| T04 | ~30 min | 2 hr |
| T05 | ~45 min | 2.75 hr |
| T06 | ~15 min | 3 hr |
| T07 | ~30 min | 3.5 hr |
| T08 | ~10 min | 3.7 hr |
| T09 | ~30 min | 4.2 hr |
| T10 | ~15 min | 4.5 hr |

**总预估**:~4.5 hr 一次性实施。
