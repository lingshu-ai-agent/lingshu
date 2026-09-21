# Contract: A2aServer Spring Bean 生命周期

**Contract ID**: `lingshu.contract.a2a-server-lifecycle.v1`
**Feature**: Story #009 a2a-agent-card
**Created**: 2026-09-21
**Status**: Stable

---

## 1. 契约方

| 角色 | 类/方法 | 文件 |
|---|---|---|
| **Bean Factory** | `A2aServerAutoConfiguration.a2aServer(AgentConfig)` | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServerAutoConfiguration.java` |
| **Bean** | `A2aServer` | `lingshu-a2a-server/src/main/java/ai/lingshu/a2a/server/A2aServer.java` |
| **Container** | Spring `ApplicationContext`(`ConfigurableApplicationContext`)| — |

---

## 2. Bean 生命周期契约

### 2.1 启动期(`@PostConstruct start()`)

**触发时机**:Spring 上下文 refresh 完成 + 全部 `@Bean` 实例化后,`ConfigurableApplicationContext.start()` 之前

**步骤**:

| 步 | 操作 | 异常处理 |
|---|---|---|
| 1 | `int port = cfg.getA2a().getPort();` | N/A(字段已由 `AgentConfig.@Value` 不可变)|
| 2 | `if (port < 0 \|\| port > 65535)` → 抛 `LINGS-S06` | 立即失败,Spring 上下文 refresh 失败 |
| 3 | `String host = cfg.getA2a().getHost();` | N/A |
| 4 | `this.server = HttpServer.create(new InetSocketAddress(host, port), 0);` | `BindException`(端口占用)/ `UnknownHostException` → catch 抛 `LINGS-S06` |
| 5 | `this.actualPort = server.getAddress().getPort();`(port=0 时由 OS 分配,记录真实端口)| N/A |
| 6 | `server.createContext("/.well-known/agent.json", this::handleAgentCard);` | N/A |
| 7 | `server.createContext("/rpc", this::handleRpc);` | N/A |
| 8 | `server.createContext("/", this::handleNotFound);`(catch-all)| N/A |
| 9 | `server.setExecutor(null);`(default executor)| N/A |
| 10 | `server.start();` | `IllegalStateException`(重复 start) → 抛 `LINGS-S06` |
| 11 | `log.info("[A2aServer] listening on http://{}:{}", host, actualPort);` | N/A |

**幂等性**:**不**保证(重复调用 start() 会抛 `IllegalStateException`,由 catch 转 `LINGS-S06`)

### 2.2 运行期(无主操作)

**职责**:仅响应 HTTP 请求(handler 由 `createContext` 注册,与 `@PostConstruct` 无关)

**关键不变量**:
- `this.server` 引用**不**变(start 后到 stop 前)
- `this.actualPort` 引用**不**变
- `this.json` (`ObjectMapper`) 线程安全,可被多请求并发访问

### 2.3 停止期(`@PreDestroy stop()`)

**触发时机**:Spring 上下文 close 时(`ConfigurableApplicationContext.close()`)

**步骤**:

| 步 | 操作 | 异常处理 |
|---|---|---|
| 1 | `if (server == null) return;`(已 stop 或未 start,直接返回)| N/A |
| 2 | `int port = actualPort;`(记日志用)| N/A |
| 3 | `server.stop(0);`(立即停止,不等 in-flight)| **不**抛异常(stop() 内部捕获所有异常)|
| 4 | `this.server = null;`(防止 `@PreDestroy` 重复调)| N/A |
| 5 | `log.info("[A2aServer] stopped on http://{}:{}", host, port);` | N/A |

**幂等性**:**保证**(重复 stop() 安全,第一次 stop 后 `server == null` 直接返回)

### 2.4 失败模式

| 失败点 | 错误码 | HTTP 端点 | Spring 启动 |
|---|---|---|---|
| Identity.name 校验失败 | `LINGS-T02` | N/A(server 未启动)| 失败 |
| 端口被占用 `BindException` | `LINGS-S06` | N/A | 失败 |
| 端口越界 `IllegalArgumentException` | `LINGS-S06` | N/A | 失败 |
| hostname 解析失败 `UnknownHostException` | `LINGS-S06` | N/A | 失败 |
| `server.start()` 重复调 `IllegalStateException` | `LINGS-S06` | N/A | 失败 |
| 运行期 HTTP handler 抛异常 | (无,handler 内 catch)| 500 + `{"error":"internal server error"}` | (server 进程不受影响)|

---

## 3. 并发契约

### 3.1 HTTP server 线程模型

JDK `HttpServer` 默认使用 `ThreadPoolExecutor`(cached thread pool,无界队列)。每个 HTTP 请求由独立线程处理:

```java
// JDK 内部(jdk.httpserver/ServerImpl.java,大致):
Executor executor = Executors.newCachedThreadPool();
server.setExecutor(executor);  // 或 setExecutor(null) 用 default
```

**线程安全**:
- `A2aServer.handleAgentCard` / `handleRpc` / `handleNotFound`:**只读**访问 `cfg` + `json` 字段 → 线程安全
- `LocalAgentCardGenerator.generate(cfg)`:**static 纯函数** + 无状态 → 线程安全
- `ObjectMapper`:Jackson 默认线程安全

**并发保证**:
- 多个并发 GET 请求可同时被服务(无锁 / 无阻塞)
- POST /rpc 501 占位 handler 不访问共享状态 → 线程安全
- catch-all 404 handler 不访问共享状态 → 线程安全

### 3.2 Bean 实例可见性

- `A2aServer` 是 Spring 单例 Bean(`@Component` / `@Bean` 默认 scope = singleton)
- `cfg` 字段在构造器注入后**不**变(`@Value` 不可变 + Lombok `@AllArgsConstructor` 一次性赋值)
- `server` 字段在 `@PostConstruct` 之前为 `null`,之后不变

---

## 4. 配置契约

### 4.1 yml 字段

```yaml
agent:
  a2a:
    host: 0.0.0.0   # 默认值
    port: 8080      # 默认值
```

| 字段 | 类型 | 默认 | 范围 | 必填 |
|---|---|---|---|---|
| `agent.a2a.host` | String | `"0.0.0.0"` | 任意合法 hostname / IP | ❌ |
| `agent.a2a.port` | Integer | `8080` | 0—65535(0 = OS auto)| ❌ |

### 4.2 yml 缺省行为

| 场景 | 结果 |
|---|---|
| 完全不写 `a2a.*` | 用 `A2a.defaults()` = `("0.0.0.0", 8080)` |
| 写 `a2a.port: 0` | OS 自动分配端口,`A2aServer.getActualPort()` 返回真实端口 |
| 写 `a2a.port: 9090` | 监听 9090 |
| 写 `a2a.port: -1` | 启动期抛 `LINGS-S06` |
| 写 `a2a.host: "127.0.0.1"` | 仅本机回环访问 |
| 写 `a2a.host: "invalid.host.local"` | 启动期抛 `LINGS-S06`(`UnknownHostException`)|

### 4.3 MinimalYamlParser 兼容性(Story #007 落地)

`agent.a2a.host` / `agent.a2a.port` 是 yml 嵌套字段,Story #007 的 `MinimalYamlParser` 支持(§14.8 N8 yaml-hot-reload + `MinimalYamlParser` 手写 YAML parser,深度 3 支持)。

验证方法:
- 改 `application.yml` 加 `agent.a2a.port: 9090`,触发 YamlWatcher reload
- 检查 `A2aServer.start()` 重启时(本 Story **不**实现 hot reload,Spring 上下文启动期一次 start())|

---

## 5. 测试覆盖

| 测试 ID | 文件 | 验证点 |
|---|---|---|
| TC-LC-1 | `A2aServerLifecycleTest#start_withCustomPort9090_listensOn9090` | US2-AS1 |
| TC-LC-2 | `A2aServerLifecycleTest#start_withDefaultPort8080_listensOn8080` | US2-AS2 |
| TC-LC-3 | `A2aServerLifecycleTest#stop_releasesPortForRebind` | US2-AS3 |
| TC-LC-4 | `A2aServerLifecycleTest#start_withPortZero_returnsOsAssignedPort` | US2-AS4 |
| TC-LC-5 | `A2aServerLifecycleTest#start_withPortAlreadyInUse_throwsLingsS06` | Edge Case EC-4 |
| TC-LC-6 | `A2aServerLifecycleTest#start_withInvalidPort_throwsLingsS06` | EC-5 |

---

## 6. SemVer 影响

- **MAJOR**:无变化(Bean 生命周期契约稳定)
- **MINOR**:本 Story 新增 Bean(由 release manager 统一 bump)
- **PATCH**:无变化

**契约版本**:`v1`(稳定)。
