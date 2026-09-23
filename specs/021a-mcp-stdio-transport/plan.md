# Story #021a `mcp-stdio-transport` — Plan

> **Status**: Draft 2026-09-23
> **Implements**: `specs/021a-mcp-stdio-transport/spec.md`
> **Source**: dsh v1.5.37 §6.5 (2.1) L4553-4871(`McpServerConnection` 心跳保活与重连)+ §6.5 (2) L4454-4551(`McpTransport` 调用契约)+ §15.9 L7211-7220(ErrorCode 编码约定)+ §4.10.1 硬规则 2(ToolExecutor 5 步流水线)
> **Pre-req**: 无 —— Story #021a 是 MCP 故事链**第一块砖**,前置已合入 `Tool` / `ToolRegistry` / `ToolExecutor`(Story #019 + #003),后续 Story #021b 直接消费

---

## §1 范围与非范围

### In-Scope(7 核心文件 + 1 测试目录)

| 文件 | 行为 | 行数预算 |
|---|---|---|
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConfig.java`(新)| runtime config POJO,`@Value @Builder @Jacksonized` + 内嵌 `Transport` enum | ~110 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/ConnectionState.java`(新)| enum 6 态 `IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED` | ~30 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnection.java`(新)| interface `extends AutoCloseable`,8 方法 | ~95 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java`(新)| final class + `static create(McpServerConfig)` | ~50 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/StdioMcpServerConnection.java`(新)| 完整实现:process 管理 + daemon 心跳 + 指数退避重连 | ~280 |
| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpTransportException.java`(新)| `extends RuntimeException`,LINGS-M01 载体 | ~30 |
| `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java`(修改)| `ServerConfig` 扩 `transport` + `url` + 3 心跳字段 | +30 |
| `lingshu-core/src/test/java/ai/lingshu/core/mcp/`(新)| 11 测试文件 + 1 fixture | ~700 / ≥31 case |

### Out-of-Scope(显式 deferred)

- **`SseMcpServerConnection` —— Story #021c sse-and-http-transport(下一块砖,见 ROADMAP)**
  - `SseMcpServerConnection implements McpServerConnection`:JDK 17 内置 `java.net.http.HttpClient` + `EventSource` 长连接 + `GET /health` 心跳 + 重建 HttpClient 重连(对齐 dsh §6.5 (2.1) L4821-4835 3 处差异)
- **`StreamableHttpMcpServerConnection` —— Story #021c sse-and-http-transport 同 Story**
  - 无状态 HTTP request/response;`POST {cfg.url}/tools/call` 每次;心跳 = 周期 ping HTTP;无 EventSource
- **`McpServerConnectionFactory` SSE/HTTP 分支** —— Story #021a factory 在 SSE/STREAMABLE_HTTP 分支抛 `McpTransportException("LINGS-M01", "...not implemented in #021a")`;Story #021c 移除该抛点,改为 `new SseMcpServerConnection(cfg)` / `new StreamableHttpMcpServerConnection(cfg)`
- **`McpTransport` orchestration + listener 注册 tools/unregister tools** —— Story #021b
- **`McpToolAdapter` 包装 Tool** —— Story #021b
- MCP server list cache 持久化 —— 当前每次 start() 重拉;Redis/file cache 留给 #022 后
- MCP auth / OAuth —— 当前 stdio 子进程走 env 传 token;OAuth flow 留给后续 Story
- hot-reload —— `McpServerConfig` 本身 immutable,`AgentConfig.ServerConfig` YAML reload 重建 config 但不主动 close+reopen connection(留给 §14.8 hot-reload Story)
- JsonNode 内置实现 —— 用 Jackson 已锁的 `com.fasterxml.jackson.databind.JsonNode`(R-13 锁);不引 `org.json`

---

## §2 接口契约锚点(dsh §6.5 (2.1))

### 2.1 `McpServerConfig` runtime config(dsh §6.5 (2.1) L4643-4648 + L4838-4851)

**字段**(8 + 1 嵌套 enum):

| 字段 | 类型 | 默认 | 用途 |
|---|---|---|---|
| `name` | `String` | required | 唯一 server 名(对应 yml `entry.name`)|
| `transport` | `Transport` | required | `STDIO / SSE / STREAMABLE_HTTP`,工厂路由依据 |
| `command` | `String` | null | stdio 子进程命令(例 `"npx"`)|
| `args` | `List<String>` | `Collections.emptyList()` | stdio 子进程参数 |
| `env` | `Map<String, String>` | `Collections.emptyMap()` | stdio 子进程环境变量 |
| `url` | `String` | null | SSE/HTTP 服务端 URL |
| `heartbeatIntervalMs` | `long` | `30_000L` | 心跳探测间隔;测试可调小到 50ms |
| `heartbeatTimeoutMs` | `long` | `10_000L` | ping 等回包超时 |
| `reconnectCapMs` | `long` | `60_000L` | 指数退避上限(7 次失败后 cap 在此值)|

**Lombok 注解**:
- `@Value` —— 不可变,所有字段 `final`,getter 自动
- `@Builder` —— builder 模式,`@Builder.Default` 给 `args / env / 3 个心跳参数`默认值
- `@Jacksonized` —— Jackson 反序列化友好(MCP 测试用 YAML/JSON 配置 fixture 时)

**Javadoc 段落**(类级):
1. 介绍 runtime config 概念 + 与 `AgentConfig.ServerConfig` 的两层关系
2. **Why separate layer** —— YAML 绑定层 immutable / runtime 层 mutable 心跳参数(测试 fixture 需要 `hbIntervalMs=50` 才能 1s 内跑完 7 步退避序列)
3. **JDK 8 兼容** —— `@Value + @Builder + @Jacksonized`,不用 `record / sealed / var / List.of`
4. **Default 数值选择 rationale** —— 30s 心跳(节流但能 30s 内发现死进程)/ 10s ping 超时(给 MCP server 充分响应时间)/ 60s 重连上限(避免维护窗口撞 LLM turn 超时)

### 2.2 `ConnectionState` enum(dsh §6.5 (2.1) L4593-4595)

**6 态字面落地**(顺序按 dsh L4593):

```java
public enum ConnectionState {
    IDLE, CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, FAILED
}
```

**状态机图**(Javadoc):

```
IDLE → CONNECTING → CONNECTED ⇄ DISCONNECTED → RECONNECTING → CONNECTED...
                                  FAILED ←──── close()
```

**语义约束**(Javadoc 段落):
- `IDLE` → 初始态,未调 `start()`
- `CONNECTING` → `start()` 进行中(子进程拉起 / initialize 握手 / tools/list 缓存)
- `CONNECTED` → 握手成功,心跳正常运行,`listTools / callTool` 可用
- `DISCONNECTED` → transient,**自动转入** `RECONNECTING`(除非用户主动 close)
- `RECONNECTING` → 指数退避等待下一次 `start()` 重入
- `FAILED` → 终态,只能 `close()` 出来

### 2.3 `McpServerConnection` interface(dsh §6.5 (2.1) L4569-4591)

**8 方法**(对齐 dsh):

| 方法 | 返回 | 语义 |
|---|---|---|
| `name()` | `String` | 唯一 server 名(对应 `cfg.name`)|
| `state()` | `ConnectionState` | 当前状态 |
| `lastHeartbeatAt()` | `Instant` | 最近一次心跳成功时间;启动后未成功则构造时刻 |
| `listTools()` | `List<McpToolDescriptor>` | 当前缓存的 tools/list,重连后会重新拉 |
| `callTool(String, JsonNode)` | `McpCallResult` | **非 CONNECTED 直接返 error,不抛异常** |
| `onStateChange(Consumer<ConnectionState>)` | `void` | listener 注册;多 listener |
| `start()` | `void` | 启动连接(异步非阻塞);失败自动 `scheduleReconnect()` |
| `close()` | `void` | 关闭连接(同步);进入 `FAILED` 终态 |

**`extends AutoCloseable`** —— 配合 try-with-resources 模式

**Javadoc 必含**:
- (1) **非 CONNECTED callTool 直接返 error 不抛异常** —— 与 §4.10.1 硬规则 2 兼容
- (2) **listener 多订阅异常隔离** —— 单 listener 抛异常 catch 住不影响其他
- (3) **JDK 8 兼容** —— 接口默认方法**不**用(全部 abstract,JDK 8 编译器友好)

### 2.4 `McpServerConnectionFactory`(dsh §6.5 (2.1) L4601-4614)

**单 public 静态方法** `static McpServerConnection create(McpServerConfig cfg)`:

```java
public static McpServerConnection create(McpServerConfig cfg) {
    switch (cfg.getTransport()) {
        case STDIO:
            return new StdioMcpServerConnection(cfg);
        case SSE:
        case STREAMABLE_HTTP:
            throw new McpTransportException("LINGS-M01",
                "MCP transport " + cfg.getTransport() + " not implemented in Story #021a "
                + "(only STDIO ships; SSE / STREAMABLE_HTTP deferred)");
    }
    throw new IllegalStateException("Unknown transport: " + cfg.getTransport());
}
```

**类设计**:
- `final class`(不可继承)
- private ctor(utility class 模式)
- 异常载体 `McpTransportException` —— 复用错误码 `LINGS-M01`,Story #021a 引入新错误域

### 2.5 `StdioMcpServerConnection` 完整实现(dsh §6.5 (2.1) L4623-4819)

**字段**:

| 字段 | 类型 | 初始值 | 用途 |
|---|---|---|---|
| `cfg` | `final McpServerConfig` | required | 配置 |
| `hb` | `final ScheduledExecutorService` | `Executors.newSingleThreadScheduledExecutor(daemonTf)` | daemon 心跳 + 调度重连 |
| `listeners` | `final CopyOnWriteArrayList<Consumer<ConnectionState>>` | empty | listener 多订阅 |
| `state` | `final AtomicReference<ConnectionState>` | `IDLE` | 状态机 |
| `lastBeat` | `final AtomicReference<Instant>` | `Instant.now()` | 最近心跳成功 |
| `reconnectAttempts` | `final AtomicInteger` | 0 | 退避计数 |
| `process` | `volatile Process` | null | 子进程句柄 |
| `stdin` | `volatile OutputStream` | null | 子进程 stdin |
| `stdout` | `volatile InputStream` | null | 子进程 stdout |
| `cachedTools` | `volatile List<McpToolDescriptor>` | `Collections.emptyList()` | 缓存 tools/list |

**私有方法**(对齐 dsh 模板):

| 方法 | 语义 | 行数 |
|---|---|---|
| `start()` | 5 步 start + 抢占守卫 + catch Exception → scheduleReconnect | ~40 |
| `probe()` | 双探活(process.isAlive + ping) | ~15 |
| `scheduleReconnect()` | `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 退避调度 | ~20 |
| `stopProcess()` | destroy → 等 5s → destroyForcibly | ~15 |
| `transition(ConnectionState)` | state.set(next) + for-each listener(异常隔离)| ~10 |
| `callTool(name, input)` | 非 CONNECTED 返 error;否则 writeJSONRPC + readResponse | ~15 |
| `close()` | hb.shutdownNow + stopProcess + transition(FAILED) | ~10 |
| `sendAndAwait(method, params, timeoutMs)` | 写 JSON-RPC frame + 阻塞读 stdout 等匹配 id response 或 timeout | ~30 |
| `sendNotification(method, params)` | 写 JSON-RPC frame 无 id | ~10 |
| `buildInitializeParams()` | `{protocolVersion:"2024-11-05", capabilities:{}, clientInfo:{name:"lingshu-agent", version:"1.0"}}` | ~10 |
| `parseToolList(resp)` | 解析 `resp.result.tools` → `List<McpToolDescriptor>` | ~15 |
| `parseCallResult(resp)` | 解析 `resp.result.{content, isError}` → `McpCallResult` | ~10 |

**测试用 ctor**(可调参数):

```java
public StdioMcpServerConnection(McpServerConfig cfg) {
    this(cfg, cfg.getHeartbeatIntervalMs(), cfg.getHeartbeatTimeoutMs(), cfg.getReconnectCapMs());
}
public StdioMcpServerConnection(McpServerConfig cfg,
                                 long hbIntervalMs, long hbTimeoutMs, long reconnectCapMs) {
    // ... dsh L4647-4662
}
```

**关键不变量**(Javadoc 强调):
1. `start()` 抢占守卫:只允许 IDLE / DISCONNECTED / RECONNECTING 状态调 start()(否则 return)— 防止重入导致子进程多开
2. `probe()` 双探活:先 process.isAlive() 再 ping,任一失败 → DISCONNECTED → scheduleReconnect
3. `scheduleReconnect()` 抢占守卫:DISCONNECTED → RECONNECTING 用 CAS;已经是 RECONNECTING 不重复调度
4. `transition()` listener 异常隔离:try/catch per listener
5. `close()` 幂等:hb.shutdownNow 后再调安全

### 2.6 `McpTransportException`(dsh §15 编码约定)

```java
package ai.lingshu.core.mcp;

/**
 * MCP transport 域异常,载 LINGS-M01 错误码(dsh §15 编码约定 L7211-7220)。
 *
 * <p>Story #021a 引入新错误域 <b>M</b>(MCP transport)—— 此前 §15 域字母 = C/S/L/T/X/R/A/Z,
 * 新域 M01 = MCP_CONNECT_FAILED。
 */
public class McpTransportException extends RuntimeException {

    private final String code;

    public McpTransportException(String code, String message) {
        super(message);
        this.code = code;
    }

    public McpTransportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() { return code; }
}
```

### 2.7 `AgentConfig.ServerConfig` 扩展(dsh §6.5 (2) L4534-4548)

**现状**(Story #020a 后):
```java
@Value
public static class ServerConfig {
    String name;
    String command;
    List<String> args;
    Map<String, String> env;
}
```

**扩展后**:
```java
@Value
public static class ServerConfig {
    String name;
    /** 🆕 Story #021a — transport discriminator;默认 STDIO(向后兼容)| */
    ai.lingshu.core.mcp.McpServerConfig.Transport transport;
    String command;
    List<String> args;
    Map<String, String> env;
    /** 🆕 Story #021a — SSE/HTTP URL;stdio 可空 | */
    String url;
    /** 🆕 Story #021a — 心跳间隔(ms);默认 30000 | */
    long heartbeatIntervalMs;
    /** 🆕 Story #021a — 心跳超时(ms);默认 10000 | */
    long heartbeatTimeoutMs;
    /** 🆕 Story #021a — 重连上限(ms);默认 60000 | */
    long reconnectCapMs;
}
```

**注意事项**:
- 加 `transport / url / 3 心跳参数` 共 5 新字段,老 yml 4 字段启动后缺这些 → Lombok `@Builder.Default` 不适用(因为 `@Value` 是 final 字段),改用静态工厂 `default()` 方法,或在 ctor 初始化默认值
- **更稳妥方案**:`@Builder.Default` 加在每个新字段上,确保 builder 调用不带这些字段时默认值生效
- 反向兼容测试:Story #020a yml 格式(只 4 字段)反序列化后 `transport=STDIO, url=null, 3 心跳=30000/10000/60000`,见 T-15

**子包 import 警告**:`AgentConfig` 在 `ai.lingshu.core.runtime`,引用 `ai.lingshu.core.mcp.McpServerConfig.Transport` —— `mcp` 包是 `runtime` 的子包吗?**不是** —— 同级包。这会**产生循环依赖**:`runtime/AgentConfig` → `mcp/McpServerConfig`,而 `#021b` 又要在 `mcp` 包里 import `runtime/AgentConfig`(McpTransport.connect 接 `List<McpServerConfig>`,但 yml 端 `AgentConfig.Mcp.servers` 是 `List<ServerConfig>`)→ 双向依赖,违反 §5.7 SPI 单向原则。

**修复方案**:`McpServerConfig.Transport` enum 移到 `ai.lingshu.core.runtime` 包作为顶层类 `McpTransport`(避免与 #021b 的 `McpTransport` orchestration class 冲突,可改名 `McpTransportType`):

```java
// 在 ai.lingshu.core.runtime 包新增
public enum McpTransportType {
    STDIO, SSE, STREAMABLE_HTTP
}
```

`AgentConfig.ServerConfig.transport` 改为 `McpTransportType transport`;`McpServerConfig.transport` 也用同一个 `McpTransportType`(import 即可,无循环依赖)。

详细 plan 见 §3.1 子节。

---

## §3 关键技术决策

### 3.1 `McpTransportType` enum 提到 `ai.lingshu.core.runtime` 包(避免循环依赖)

**问题**:
- `AgentConfig.ServerConfig` 在 `ai.lingshu.core.runtime`
- `McpServerConfig` 在 `ai.lingshu.core.mcp`
- 如果 `McpServerConfig.Transport` 在 `mcp` 包 → `runtime/AgentConfig` import `mcp/McpServerConfig.Transport` → `mcp` 包不能 import `runtime/AgentConfig`(否则双向)
- 但 #021b `McpTransport.connect(List<McpServerConfig>)` 又要 import `runtime/AgentConfig`(yml → runtime 解析)
- **结论**:`McpServerConfig.Transport` 不能在 `mcp` 包

**方案**:`McpTransportType` enum 在 `ai.lingshu.core.runtime` 包(独立类型,无任何依赖),`AgentConfig.ServerConfig.transport` 和 `McpServerConfig.transport` 都 import 这个 enum。

**不放在 mcp 包的原因**:`mcp` 包下 #021b 要 import `runtime/AgentConfig`(yml 端桥接),反过来 `runtime/AgentConfig` 也 import `mcp/Transport` 就循环了。把 enum 放 `runtime` → `mcp` 单向依赖 `runtime`(对)→ 不循环。

### 3.2 心跳可调参数通过 ctor 暴露(不只暴露给测试)

**问题**:Story #021a 测试需要 `hbIntervalMs=50` 才能 1s 内跑完 7 步退避序列验证;但生产默认是 30s。

**方案**:
- 公开 ctor `StdioMcpServerConnection(McpServerConfig cfg)`(用 cfg 里的心跳参数)
- 公开 ctor `StdioMcpServerConnection(McpServerConfig cfg, long hbIntervalMs, long hbTimeoutMs, long reconnectCapMs)`(覆盖默认)
- **前者由 #021b McpTransport 调**,**后者由测试 fixture 调**(直接传小值)

**为什么不用 Builder**:dsh L4643-4662 就是 4-arg ctor,遵循 dsh 样板。

### 3.3 `ProcessBuilder` 不显式销毁 stale 子进程(Graceful first)

**问题**:子进程死掉后 `process.destroy()` 不一定立即生效(尤其 Windows)。

**方案**(对齐 dsh L4760-4772):
```java
private void stopProcess() {
    if (process != null && process.isAlive()) {
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
    process = null;
}
```

**测试验证**:EC-021a-3 在 Windows runner 上跑;**主验证** = GitHub Actions matrix ubuntu(Story #021a CI 范围)。

### 3.4 listener 异常隔离用 per-listener try/catch(不批量 try/catch)

**问题**:状态变化时,多个 listener 注册;若 listener 1 抛异常,listeners 2/3/4 应该仍收到通知。

**方案**(对齐 dsh L4777-4784):
```java
private void transition(ConnectionState next) {
    ConnectionState prev = state.getAndSet(next);
    if (prev != next) {
        for (Consumer<ConnectionState> l : listeners) {
            try {
                l.accept(next);
            } catch (Exception e) {
                log.warn("[MCP:{}] listener threw: {}", cfg.getName(), e.toString());
            }
        }
    }
}
```

**测试验证**:AC-021a-8 + T-13,3 case 覆盖(正常 / 单 listener 抛异常 / 多 listener 顺序)。

### 3.5 `sendAndAwait` JSON-RPC framing(MCP spec Content-Length header)

**MCP stdio 协议**(MCP spec 2024-11-05):
```
Content-Length: 82\r\n
\r\n
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05",...}}
```

**实现**:`sendAndAwait`:
1. 序列化 JSON-RPC frame 字符串
2. 写 `Content-Length: <bytes>\r\n\r\n<frame>` 到 stdin(UTF-8)
3. 阻塞读 stdout:
   - 累积字节到 Buffer
   - parse Content-Length header
   - 读 Content-Length bytes body
   - parse JSON body
   - 检查 `id == expected_id` 是 response(否则继续读下一帧)
4. timeout 用 `ScheduledFuture.get(timeout)` —— 但 stdin/stdout 是 blocking I/O,不能用 Future,改用 `Future<Process>` + `process.waitFor(timeout)` 不行(进程不会主动 exit)

**简化方案**:用 `PipedInputStream / PipedOutputStream` + `ExecutorService.submit` + `Future.get(timeout)`,阻塞读转 future。这增加复杂度,Story #021a 范围**只实现 synchronous blocking read with timeout**(单线程读,timeout 用 `InputStream.read(byte[], off, len)` 配合 `System.nanoTime()` 循环)。

**测试 fixture**(TestMcpServer.java):
- 子进程 main:从 stdin 读 MCP frames → 写对应 response frames(initialize / initialized / tools/list / ping)
- 测试启动子进程 + 写 stdio → 等 start() 状态切 CONNECTED → 验证 listTools()
- 测试用 fake server **不必**实现完整 MCP spec(只覆盖 Story #021a 测的 4 个 method:initialize / tools/list / ping / tools/call)

### 3.6 EC 子进程立即死(EC-021a-1):catch all → scheduleReconnect

**实现**(对齐 dsh L4718-4721):
```java
} catch (Exception e) {
    log.warn("[MCP:{}] start failed: {}", cfg.getName(), e.toString());
    scheduleReconnect();
}
```

**测试验证**:`StdioMcpServerConnectionStartErrorTest`(EC-021a-1)。

---

## §4 文件布局(7 文件 + 1 测试目录)

```
lingshu-core/src/main/java/ai/lingshu/core/
├── mcp/                                              ← 🆕 新包
│   ├── McpServerConfig.java                          ← @Value POJO + Transport 内嵌
│   ├── ConnectionState.java                          ← enum 6 态
│   ├── McpServerConnection.java                      ← interface 8 方法
│   ├── McpServerConnectionFactory.java               ← factory dispatch
│   ├── StdioMcpServerConnection.java                 ← 完整实现 (~280 行)
│   └── McpTransportException.java                    ← LINGS-M01 载体
└── runtime/
    ├── AgentConfig.java                              ← 🆕 +Mc pTransportType enum(顶层类)
    │                                                  ← 🆕 ServerConfig.transport/url/3 心跳字段
    └── McpTransportType.java                         ← 🆕 独立 enum,避免循环依赖
```

```
lingshu-core/src/test/java/ai/lingshu/core/
├── mcp/                                              ← 🆕 测试目录
│   ├── McpServerConfigTest.java                      ← L1 4 case
│   ├── ConnectionStateTest.java                      ← L1 1 case
│   ├── McpServerConnectionContractTest.java          ← L1 1 case
│   ├── McpServerConnectionFactoryTest.java           ← L2 4 case
│   ├── StdioMcpServerConnectionStartTest.java        ← L2 + L3 5 case
│   ├── StdioMcpServerConnectionHeartbeatTest.java    ← L2 + L3 4 case
│   ├── StdioMcpServerConnectionReconnectTest.java    ← L2 + L3 3 case
│   ├── StdioMcpServerConnectionListenerTest.java     ← L2 3 case
│   ├── StdioMcpServerConnectionCloseTest.java        ← L2 2 case
│   ├── StdioMcpServerConnectionCallToolNotConnectedTest.java  ← EC 1 case
│   ├── AgentConfigMcpExpansionTest.java              ← L1 3 case
│   └── fixture/
│       └── TestMcpServer.java                        ← fake MCP server 子进程
└── runtime/
    └── AgentConfigMcpBackwardCompatTest.java         ← L1 3 case(老 yml 4 字段反序列化)
```

**总计**:7 新文件 + 1 修改 + 13 测试文件(含 fixture)/ ~600 行生产代码 / ~800 行测试代码

---

## §5 测试策略(7 层金字塔对齐 dsh §14.15.7)

| 层 | 类型 | 范围 | case 数 |
|---|---|---|---|
| **L1** | Unit | enum 字面值 / interface 契约 / POJO 默认值 / 反序列化 | 13 |
| **L2** | Integration(单 class,无 Spring)| `McpServerConnectionFactory` dispatch / `McpTransportException` / `AgentConfigMcpBackwardCompat` | 10 |
| **L2+L3** | Hybrid(fixture 子进程)| `StdioMcpServerConnection` start / heartbeat / reconnect / listener / close / callTool error path | 18 |
| **L3** | End-to-end(fixture 子进程 + Spring)| 暂不写(等 #021b McpTransport 集成 Spring 后)| 0 |
| **EC** | Edge case | 子进程立即死 / callTool 非 CONNECTED / Windows 平台 graceful fallback / builder defaults | 4 |
| **总计** | | | **45 case** |

**fixture 设计**(`TestMcpServer.java`):
- 简单 Java main class,启动时从 stdin 读 JSON-RPC frames
- 4 个 method handler:
  - `initialize` → 回 `{"jsonrpc":"2.0","id":N,"result":{"protocolVersion":"2024-11-05",...}}`
  - `notifications/initialized`(无 id) → 不回
  - `tools/list` → 回 `{"jsonrpc":"2.0","id":N,"result":{"tools":[]}}`
  - `ping` → 回 `{"jsonrpc":"2.0","id":N,"result":{}}`
  - `tools/call` → 回 `{"jsonrpc":"2.0","id":N,"result":{"content":"fake-result","isError":false}}`
- 用 `System.in` + `BufferedReader` 读 line,用 Jackson `ObjectMapper.writeValueAsString` 写 line(JSON-RPC over stdio 简化版,只支持 line-delimited,**不**支持 Content-Length header — start() 内部用 Content-Length 但 TestMcpServer 简化版的 reader 可以 dual-mode)
- 实际实现选择:**Content-Length 模式**(对齐 MCP spec),避免测试 fixture 与生产代码协议不一致
- 测试启动方式:`new ProcessBuilder("java", "-cp", System.getProperty("java.class.path"), "ai.lingshu.core.mcp.fixture.TestMcpServer").start()`

**fake vs real MCP server**:**fake** —— 真 MCP server(如 `@modelcontextprotocol/server-everything`)需要 npm/npx,CI 环境难复现;fake 子进程纯 Java,跨平台,JDK 8 兼容。

**R-13 自查链路**(SOP §3.2 AC-NN-deps-* + §3.4 T-dep-tree-*):
- AC-021a-deps-1:pre `mvn -pl lingshu-core dependency:tree` baseline → 实施 → post `dependency:tree` → diff 仅时间戳差异 → 0 binary delta
- PR body 末尾 `### R-13 dependency:tree 自查` 节

---

## §6 性能预算(dsh §14.15.1)

| 指标 | 目标 | Story #021a 验证方式 |
|---|---|---|
| 启动期 `factory.create(cfg)` 阻塞时间 | < 10ms | 测试用例`McpServerConnectionFactoryTest.create_stdio_dispatchFast`(无 I/O,纯 switch)|
| `start()` 异步非阻塞 | 立即返回(子进程拉起 < 500ms 异步)| `StdioMcpServerConnectionStartTest.start_async_nonBlocking`(验证 start() 返回后 state=CONNECTING,后续切 CONNECTED)|
| 心跳调度精度 | ±100ms(`hbIntervalMs=1000` 时)| `StdioMcpServerConnectionHeartbeatTest.probe_intervalAccuracy` |
| 重连退避序列准确性 | ±50ms(`hbIntervalMs=50`)| `StdioMcpServerConnectionReconnectTest.backoffSequence` |

**不引入性能测试**(Story #021a 范围是**功能契约**而非性能优化);性能预算 dsh §14.15.1 由 #021b + 后续 §14 N1 OpenTelemetry Story 覆盖。

---

## §7 关键不变量(Story 边界守门员)

1. **ToolExecutor 5 步流水线不变** —— dsh §4.10.1 硬规则 2,Story #021a 不改 ToolExecutor / ToolRegistry / PermissionPolicy / AuditLogger
2. **Skill 系统不变** —— Story #020a + #020b + #020c 已合,Story #021a 不动 Skill 任何类
3. **`McpTransportType` enum 单点定义** —— 只在 `ai.lingshu.core.runtime` 包,`McpServerConfig` import 用,**不在 mcp 包定义 enum**(避免 #021b 循环依赖)
4. **`McpServerConfig` 不标 `@Component`** —— runtime config,显式从 `AgentConfig.ServerConfig` 构造(#021b 做这步)
5. **MCP stdio 用 JDK 内置 `ProcessBuilder`** —— R-13 dep-tree 0 增量,**不引** `net.java.dev.jna` / `org.json` / 任何 MCP SDK
7. **JDK 8 兼容** —— `@Value @Jacksonized` / `AtomicReference` / `CopyOnWriteArrayList` / `Collections.emptyList()`,**不使用** `record / sealed / var / List.of`
9. **errors 包单点识别** —— Story #021a 引入新域 `M`(MCP),不是 `L`(LLM)` 也不是 `T`(Tool)`;`LINGS-M01 = MCP_CONNECT_FAILED`
10. **start() / scheduleReconnect() 抢占守卫** —— 防止重入导致子进程多开;CAS / state 检查保证线程安全
11. **listener 异常隔离** —— per-listener try/catch,单 listener 抛异常不影响其他
12. **close() 幂等** —— hb.shutdownNow + stopProcess + transition(FAILED),多次调用安全

---

## §8 关联文档

- dsh v1.5.37 §6.5 (2.1) L4553-4871 —— McpServerConnection 心跳保活与重连(主参考)
- dsh v1.5.37 §6.5 (2) L4454-4551 —— McpTransport 调用契约(#021b 消费)
- dsh v1.5.37 §4.10.1 硬规则 2 —— ToolExecutor 5 步流水线(不变量)
- dsh v1.5.37 §15.9 L7211-7220 —— ErrorCode 编码约定(`M01 MCP_CONNECT_FAILED`)
- dsh v1.5.37 §14.15.7 —— 7 层测试金字塔(L1+L2+L3 分布)
- Story #021b mcp-tool-adapter —— 下一块砖(消费 McpServerConnection + 包装 McpToolAdapter)
- Story #021c mcp-sse-and-http-transport —— HTTP transport 收官(SseMcpServerConnection + StreamableHttpMcpServerConnection + factory dispatch,见 ROADMAP 行6)
- Story #020a skill-foundation —— Skill interface + ToolRegistry 双索引(同 Tool 体系,MCP tool 也走 ToolRegistry)
- Story #020c cli-skill-trigger —— `--list-skills` banner(日后 `--list-mcp` 可复用相同模式)
- ROADMAP §6.5 (2.1) 提议 Story 列表第 4 行(`#021a mcp-stdio-transport`)
- SOP v1.21 §3.2 AC-NN-deps-* + §3.4 T-dep-tree-*(R-13 自查链路)