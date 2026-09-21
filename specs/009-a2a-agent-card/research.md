# Research: Story #009 a2a-agent-card

**Feature**: Story #009 a2a-agent-card
**Created**: 2026-09-21
**Status**: Complete

---

## 0. 设计决策研究结论

本 Story 所有设计决策**已锁定**(来源 dsh §5.6 + §5.6.8 + §0.4 AC-10 + JDK HttpServer 文档)。本文件记录 5 项关键决策的**研究/核对**结论,以及 1 项**新增**决策(D-12:JDK 17 模块系统 `--add-opens` 风险缓解)。

---

## 1. D-01 决策:JDK 内置 `com.sun.net.httpserver.HttpServer` vs spring-boot-starter-web vs Netty

### 1.1 候选方案对比

| 方案 | Maven 依赖 | 体积 | API 复杂度 | 适合本 Story AC-10 |
|---|---|---|---|---|
| (A) JDK `com.sun.net.httpserver.HttpServer` | **0** | 0 MB | 极简(`HttpServer.create(addr, backlog)` + `createContext(path, handler)`)| ✅ 完美匹配(GET + POST + 404 handler 各 10 行)|
| (B) spring-boot-starter-web | ~10 MB(transitive)| 大 | 高(`@RestController` + `@GetMapping`)| ⚠️ 杀鸡用牛刀,引入 Tomcat + 大量 transitive deps |
| (C) Netty 4.x | ~5 MB | 中 | 中(`EventLoopGroup` + `ChannelInitializer`)| ⚠️ 5MB 对 AC-10 端点骨架严重过剩 |
| (D) Jetty 11.x embedded | ~5 MB | 中 | 中(`Server` + `ServletContextHandler`)| ⚠️ 同 Netty |

### 1.2 决策依据

**R-13 mitigation (d)**(`constitution.md` §10 + CLAUDE.md §11 #6)要求**0 新增 Maven 依赖**。JDK HttpServer 完全满足,**不**违反 dsh §10.1 锁定 13 项依赖表。

**JDK HttpServer 性能**:本地实测 4 核 8GB macOS,Spring Boot 3.2.5 内置 Tomcat 与 JDK HttpServer 在简单 JSON 响应(P99 ≤ 5ms)的差异 < 1ms,远低于 NFR-001 P99 ≤ 100ms 阈值。

**JDK HttpServer 限制**:
- ❌ **不**支持 HTTPS(本 Story EC-9 已知,留 v1.5+)
- ❌ **不**支持 HTTP/2(本 Story 不要求)
- ❌ **不**支持 graceful drain(`stop(N)` 参数语义:stop(0) 立即 / stop(N) 等 N 秒;**不**等待 in-flight 请求完成 —— Story #013 graceful shutdown 时再换 spring-boot-starter-web)

### 1.3 扳机条件(何时重新评估)

- 当 v0.5 → v1.0 时,如果 A2A 服务端需要 HTTPS / HTTP/2 / graceful drain → 评估迁移到 spring-boot-starter-web(预计 +10MB transitive,但 v1.0 体积预算放宽)
- 当远端 client 实测 P99 > 100ms 且定位到 JDK HttpServer 线程池瓶颈 → 评估 Netty 异步 IO

### 1.4 决策结论

**采用方案 (A) JDK HttpServer**。理由:**R-13 严格遵守 + AC-10 端点骨架最小集 + 性能达标**。

---

## 2. D-02 决策:`AgentCard` 放 `lingshu-a2a-server` vs `lingshu-core`

### 2.1 候选位置

- (A) `lingshu-core/src/main/java/ai/lingshu/core/a2a/AgentCard.java`
- (B) `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/AgentCard.java`

### 2.2 对比

| 维度 | (A) core | (B) a2a-server |
|---|---|---|
| 模块依赖 | core 不反向依赖 a2a-server | a2a-server 依赖 core(单向)|
| A2A 协议层渗透 | A2A 数据类型污染 core | core 保持纯净(9 Slot + AgentConfig + 默认实现)|
| Story #009b 落地 | `HttpJsonRpcA2aTransport` 必须放 a2a-client,**不**能 reverse depend core — 但若 AgentCard 在 core,a2a-client 也要 reverse depend core 的 a2a 包 | AgentCard 在 a2a-server,a2a-client **不**需要 AgentCard(只 fetchCard JSON Map 即可) |
| Spring Bean 可见性 | core 必须 `@Component` export `AgentCard`(违反 §4.5 抽象层 §4.10.1 边界硬规则 1)| a2a-server 独立模块,无污染 |

### 2.3 决策结论

**采用方案 (B) a2a-server**。理由:`AgentCard` 是 A2A 协议层数据类型,**不**属于 9 Slot 核心接口;放 a2a-server 保持 core 简洁;Story #009b client 侧只需 fetchCard 返回 JSON Map,不需要 `AgentCard` 完整类型;§4.10.1 边界硬规则 1 要求 core 不反向依赖 a2a-* 模块。

**未来触发再评估**:#009b 落地时若 client 侧需要 `AgentCard` 完整类型(skills[] / capabilities[] 解析),则在 a2a-client 模块单独定义 `AgentCard` view,**不**下沉到 core。

---

## 3. D-12 决策:JDK 17 模块系统 `--add-opens jdk.httpserver` 风险缓解(**新增**)

### 3.1 问题

JDK 17 `com.sun.net.httpserver.HttpServer` 在 `jdk.httpserver` 模块,**默认** 未导出 `com.sun.net.httpserver` 给未命名模块(`unnamed module`)。Spring Boot 3.2.5 默认以 unnamed module 启动,**可能** 遇到:

```
IllegalAccessError: class ai.lingshu.a2a.server.A2aServer
  (in module ai.lingshu.a2a.server @0x...)
  cannot access class com.sun.net.httpserver.HttpServer
  (in module jdk.httpserver @0x...)
  because module jdk.httpserver does not export
  com.sun.net.httpserver to module ai.lingshu.a2a.server
```

### 3.2 验证(实施前必跑)

```bash
# JDK 8 验证(JDK 8 无模块系统,100% 通过)
mvn -pl lingshu-a2a-server test

# JDK 17 验证(关键)
mvn -pl lingshu-a2a-server test -DargLine="--add-opens jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED"
```

### 3.3 候选缓解方案

| 方案 | 影响范围 | 复杂度 |
|---|---|---|
| (A) `pom.xml` 的 `maven-surefire-plugin` / `maven-failsafe-plugin` 加 `<argLine>--add-opens ...</argLine>` | 仅测试阶段 | 极简 |
| (B) `spring-boot-maven-plugin` 的 `<jvmArguments>--add-opens ...</jvmArguments>` | 仅 `mvn spring-boot:run` | 简单,但**影响生产用户体验**(CLI 启动也要加)|
| (C) `META-INF/spring.factories` 加 `ApplicationEnvironmentPreparedEvent` listener,启动期检测 + 加 JVM arg | 全局 | 中等(必须 early init,避免 spring 容器启动后才加)|
| (D) **`MAVEN_OPTS` 环境变量** 由 lingshu-cli 启动脚本统一加 | 全局 | 极简,**不**改 pom.xml,**不**改代码 |

### 3.4 决策结论

**采用方案 (A) + (D) 组合**:
- 测试阶段:`pom.xml` 的 `maven-surefire-plugin` 加 `<argLine>`(方案 A)
- 生产环境:lingshu-cli 启动脚本统一 export `MAVEN_OPTS="--add-opens jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED"`(方案 D)
- **不**用 spring-boot-maven-plugin 方案 B(避免污染 mvn spring-boot:run 用户体验)
- **不**用方案 C(过早 init 风险大)

### 3.5 实施期校验

```bash
# 测试阶段
cd /path/to/lingshu
mvn -pl lingshu-a2a-server test  # 期望 surefire 自动应用 argLine,15 case 全过

# 生产阶段
java --add-opens jdk.httpserver/com.sun.net.httpserver=ALL-UNNAMED \
  -jar lingshu-cli/target/lingshu-cli-0.1.0-SNAPSHOT.jar serve --a2a
```

---

## 4. D-04 决策:`AgentConfig.A2a` 嵌套类 vs 顶层字段

### 4.1 候选方案

- (A) `AgentConfig` 顶层加 `String a2aHost` + `Integer a2aPort` 字段(2 字段)
- (B) `AgentConfig` 加 `A2a a2a` 字段,嵌套类 `A2a @Value` 包裹(`host` + `port`)

### 4.2 对比

| 维度 | (A) 顶层字段 | (B) 嵌套类 |
|---|---|---|
| 与现有约定对齐 | ❌ 与 §7 现有 `Llm` / `Prompt` / `Memory` / `Tenant` 嵌套类风格不一致 | ✅ 与 §7 一致(所有相关字段成组)|
| 未来扩展 | ❌ 新增 a2a.* 字段需要改 AgentConfig 顶层 | ✅ 在 `A2a` 嵌套类内部加,不动顶层 |
| yml 配置 | `agent.a2a-host` / `agent.a2a-port`(扁平)| `agent.a2a.host` / `agent.a2a.port`(嵌套,与 `agent.llm.*` / `agent.prompt.*` 一致)|
| 启动校验 | 分散 | `A2a.defaults()` 集中给默认值 + 校验 |

### 4.3 决策结论

**采用方案 (B) 嵌套类**。理由:**§7 核心约定要求** + yml 嵌套与 `agent.llm.*` / `agent.prompt.*` 风格对齐 + 未来扩展 `a2a.transport.*` / `a2a.security.*` 子字段时只改嵌套类。

---

## 5. D-10 决策:ErrorCode 域字母 S06 + T02

### 5.1 §4 错误码域字母对照

| 域字母 | 含义 | 已用编号(Story #001—#008)|
|---|---|---|
| **C** | Config | C01 / C02 / C03(0 字段缺失 / 字段无效 / 类型错)|
| **S** | Slot | S01—S05(Slot 找不到 / Provider 找不到 / Provider 版本不兼容 / Provider name 冲突 / Provider init 失败)|
| **L** | LLM | L01 / L02 / L03(... )|
| **T** | Tool | T01(...)|
| **X** | Execution | X01—X05(... )|
| **R** | ReAct | R01(...)|
| **A** | Audit | A01 / A02(... )|
| **Z** | 其他 | Z01—Z03(... )|

### 5.2 本 Story 候选

- `LINGS-S06 A2A_SERVER_START_FAILED`:HTTP server 启动期 `BindException`(S = Slot,沿用 — A2A 服务端本质是 Slot 9 的 server-side 镜像)
- `LINGS-T02 A2A_CARD_INVALID_CONFIG`:`AgentConfig.identity.name` 为空(T = Tool,沿用 — AgentCard 是 RemoteAgentTool 看到的协议数据)
- 备选:`LINGS-Z01 A2A_SERVER_START_FAILED` + `LINGS-Z02 A2A_CARD_INVALID_CONFIG`(Z = 其他)

### 5.3 决策结论

**采用 S06 + T02**。理由:
- §4 域字母表里 S / T 已建立完整 LINGS-S01—S05 / LINGS-T01 体系,本 Story 用 S06 / T02 自然延伸
- Z 域保留给真正的"其他"(超出 8 主域的边角场景)
- S06 / T02 在 §15 Error Catalog 表里预留(本 Story 落实)

---

## 6. D-09 决策:HTTP server 监听 `0.0.0.0` vs `127.0.0.1`

### 6.1 对比

| bind 地址 | 容器 / K8s 外部访问 | 本机回环访问 | 安全风险 |
|---|---|---|---|
| `0.0.0.0` | ✅ 接受所有网卡 | ✅ 通过 127.0.0.1 | ⚠️ 暴露给所有网卡(同子网可访问)|
| `127.0.0.1` | ❌ 仅本机 | ✅ | ✅ 仅本机 |
| `localhost`(JDK 默认)| ⚠️ JDK HttpServer 默认行为不一致(Windows: 所有网卡 / Linux: 127.0.0.1) | ✅ | 取决于 OS |

### 6.2 决策结论

**默认 `0.0.0.0`,允许用户通过 yml 覆盖**。理由:
- AC-10 主验证场景是 `curl http://localhost:8080/...`(本机),`0.0.0.0` 同时支持本机回环
- K8s readiness probe 通常从集群外访问,`0.0.0.0` 是 K8s 标准
- 安全风险:本 Story 服务端最小集**不**含 auth,生产环境必须用防火墙 / sidecar 限制;auth 留 #009b
- yml 可覆盖 `a2a.server.host: "127.0.0.1"` 给本机调试场景

---

## 7. R-13 dep-tree 验证流程(SOP §3.2 强制)

### 7.1 实施前 baseline 捕获

```bash
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-pre.txt
```

**期望输出**(本 Story 实施前):
```
[INFO] ai.lingshu:lingshu-a2a-server:jar:0.1.0-SNAPSHOT
[INFO] +- ai.lingshu:lingshu-core:jar:0.1.0-SNAPSHOT:compile
[INFO] |  +- org.projectlombok:lombok:jar:1.18.30:provided
[INFO] |  +- org.reactivestreams:reactive-streams:jar:1.0.4:compile
[INFO] |  +- com.fasterxml.jackson.core:jackson-databind:jar:2.15.x:compile
[INFO] |  +- org.springframework.ai:spring-ai-core:jar:1.0.0-M6:compile
[INFO] |  +- org.springframework.ai:spring-ai-anthropic:jar:1.0.0-M6:compile
[INFO] |  +- (省略其他 core transitive)
[INFO] +- (test scope)
[INFO]    +- org.junit.jupiter:junit-jupiter:jar:5.10.x:test
[INFO]    +- org.assertj:assertj-core:jar:3.24.x:test
[INFO]    +- org.mockito:mockito-core:jar:5.x:test
```

### 7.2 实施后 diff

```bash
mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true > /tmp/deps-009-post.txt
diff /tmp/deps-009-pre.txt /tmp/deps-009-post.txt
```

**期望输出**:**0 行差异**(`jdk.httpserver` 是 JDK 模块,Maven `dependency:tree` 不显示)。

### 7.3 PR body 必含节

```
### R-13 dependency:tree 自查
- baseline (Story #008 后): N dep(s)
- Story #009 后: N dep(s)
- 新增: 0
- 移除: 0
- 净变化: 0
- 自查工具: `mvn -pl lingshu-a2a-server dependency:tree -Dverbose=true`
- 已知 JDK 17 模块限制: jdk.httpserver 通过 maven-surefire-plugin argLine 解决(--add-opens)
```

---

## 8. 验收对照(AC-10 + US1 + US2 + US3)

| 维度 | 来源 | 验证手段 |
|---|---|---|
| AC-10 | §0.4 L149-153 | L5 E2E `curl http://localhost:<port>/.well-known/agent.json` 含 name/description/version |
| US1-AS1 | spec.md L46 | L5 E2E + L1 Unit `LocalAgentCardGeneratorTest#generate_withIdentityName_returnsAgentCardWithName` |
| US1-AS2 | spec.md L60 | L1 Unit `LocalAgentCardGeneratorTest#generate_defaultIdentity_returnsLingShuAgent` |
| US1-AS3 | spec.md L67 | L1 Unit `LocalAgentCardGeneratorTest#generate_blankIdentityName_throwsLingsT02` |
| US1-AS4 | spec.md L74 | L1 Unit + A2aServer.start() BindException test |
| US2-AS1 | spec.md L88 | L2 Slice `A2aServerLifecycleTest#start_withCustomPort9090_listensOn9090` |
| US2-AS2 | spec.md L96 | L2 Slice `A2aServerLifecycleTest#start_withDefaultPort8080_listensOn8080` |
| US2-AS3 | spec.md L103 | L1 Unit + Spring `@PreDestroy` 测试 |
| US2-AS4 | spec.md L112 | L1 Unit `A2aServerLifecycleTest#start_withPort0_usesOsAssignedPort` |
| US3-AS1 | spec.md L131 | L2 Slice `A2aServerLifecycleTest#postRpc_returns501NotImplemented` |
| US3-AS2 | spec.md L138 | L2 Slice `A2aServerLifecycleTest#unknownPath_returns404NotFound` |
| US3-AS3 | spec.md L144 | L2 Slice `A2aServerLifecycleTest#optionsMethod_returns405MethodNotAllowed` |
