# Story #021a `mcp-stdio-transport` — Spec

> **Status**: Draft 2026-09-23
> **Source**: dsh v1.5.37 §6.5 (2.1) `McpServerConnection` 心跳保活与重连(L4553-4871)+ §6.5 (2) `McpTransport` 调用契约(L4454-4551)+ §15 Error Catalog §15.9 编码约定(L7211-7220)
> **Closes gap**:Story #021a 是 MCP 故事链的**第一块砖** — `#021a`(`McpServerConnection` 生命周期 + stdio 实现)→ `#021b`(`McpTransport` 监听器注册 + `McpToolAdapter` 包装)。当前 **5 件全缺**:
> (1) **`McpServerConnection` interface** — dsh §6.5 (2.1) L4569-4591 给的 `extends AutoCloseable` + 8 方法(`name / state / lastHeartbeatAt / listTools / callTool / onStateChange / start / close`)0 实现;
> (2) **`ConnectionState` enum** — dsh L4593-4595 6 态 `IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED`,代码侧 0 枚举;
> (3) **`McpServerConnectionFactory`** — dsh L4601-4614 按 `cfg.transport()` 分派 stdio / SSE / streamable HTTP,代码侧 0 工厂;
> (4) **`StdioMcpServerConnection`** — dsh L4623-4819 完整实现:`ProcessBuilder` 拉子进程 + `AtomicReference<ConnectionState>` + daemon `ScheduledExecutorService` 心跳 + 5 步 start + 双探活 + 指数退避 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` + 无限重试,代码侧 0 实现;
> (5) **`McpServerConfig`** — dsh L4643 构造函数 `StdioMcpServerConnection(McpServerConfig cfg, hbIntervalMs, hbTimeoutMs, reconnectCapMs)` 暗示独立 runtime config,代码侧 0 类型(`AgentConfig.ServerConfig` 当前是 YAML 绑定 POJO 缺 `transport` 枚举 + `url` + 心跳可调参数)。
>
> 后果:#021b 的 `McpTransport.connect(List<McpServerConfig>)` 与 `McpToolAdapter` 注册逻辑没有 connection 实现可调,Agent 用户写 `agent.mcp.servers: [github]` 启动后 0 个 MCP tool 注入 `ToolRegistry`;**整个 MCP 集成链断在第一环**。

---

## WHY

dsh §6.5 (2) L4511-4551 的 `McpTransport` 是 MCP server ↔ LingShu Agent 的总装组件 —— 它 **依赖** `McpServerConnection` 子系统(`factory.create(cfg)` + `conn.onStateChange(listener)` + `conn.start()` + `conn.listTools()` + `conn.callTool(name, input)` + `conn.close()`)。当前 lingshu-core 仓 **完全没有任何 MCP transport 代码** —— `grep -r "McpServerConfig\|McpServerConnection\|ConnectionState\|McpTransport" lingshu-core/src/main/java/` 仅 `AgentConfig.java` 命中(`Mcp` inner class + `ServerConfig` YAML 绑定 POJO,**只** 4 字段 `name / command / args / env`,无 `transport` 枚举无 `url`)。这造成 4 个连锁问题:

1. **dsh §6.5 (2.1) v1.5.29 文档契约是 single-source-of-truth,但代码侧 0 实现** — `McpServerConnection` 8 方法接口 + 6 态状态机 + stdio 心跳保活 + 指数退避重连,L4553-4871 共 ~320 行代码示例,**全部 0 行落地**。Story #021a 实施者打开 IDE 看到的是空 `ai.lingshu.core.mcp` 包,只能照抄 dsh 模板
2. **#021b `McpTransport` 必须等 #021a** — dsh L4515-4524 `connect(List<McpServerConfig>, ToolRegistry)` 内部 `factory.create(cfg)` + `conn.onStateChange(state -> onConnectionStateChange(...))` + `conn.start()`,没有 connection 接口就没法写 mcpl/bert;同理 `McpToolAdapter` 需要 `McpServerConfig.name()` + `listTools()` + `callTool()`(dsh L4462-4493)
3. **`LINGS-Mxx` MCP 错误域未启用** — dsh §15 L7215 域字母表 `C/S/L/T/X/R/A/Z` 没有 `M`(MCP),#021a 引入新域 `M01 MCP_CONNECT_FAILED`(Story #021a 把 dsh §15 误码表 §15.10 MCP 域补全);不引入新错误域,**#021b 的 `McpToolAdapter.execute()` 异常就只能 catch-all 转 `T01 TOOL_NOT_FOUND`,语义错乱**
4. **`AgentConfig.ServerConfig` 当前缺 transport 字段** — dsh L4603 `switch (cfg.transport())` 需要 transport 枚举;现有 `ServerConfig` 只有 `name / command / args / env` 4 字段,**连 stdio 一个变体都描述不全**(SSE/HTTP 还需要 `url` 字段)。Story #021a 必须**扩展** `ServerConfig`(YAML 绑定层) + 新增 `McpServerConfig`(runtime 层,带可调心跳参数),两层解耦保留后续 hot-reload 扩展空间

**Story #021a 目标**:落地 `McpServerConnection` interface(8 方法)+ `ConnectionState` enum(6 态)+ `McpServerConnectionFactory`(按 transport 分派)+ `StdioMcpServerConnection`(daemon 心跳 + 1s→60s 指数退避 + 无限重试)+ `McpServerConfig`(runtime config,3 心跳参数 + transport 枚举 + url 字段),**扩展** `AgentConfig.ServerConfig` 加 `transport` 枚举 + `url` 字段。**MCP 集成链第一块砖**(从"文档契约" → "stdio 可运行的连接生命周期")。

**业务价值**:
- dsh §6.5 (2) + §6.5 (2.1) 文档契约有 single-source-of-truth 代码锚点 — `#021b` 实施者直接调 `factory.create(...)` 不用反推
- dsh §6.5 (2.1) L4559 提到的"MCP server OOM 被杀 / stdio 僵死 / SSE 反向代理超时踢线"3 类生产故障,Story #021a **stdion 全部覆盖**(双探活 `process.isAlive()` + MCP `ping`,断线后自动 scheduleReconnect())
- dsh §0.4 AC-09 "MCP server 多源接入"第 1 步 — Story #021a 完成后,**至少** stdio 类 MCP server(`npx @modelcontextprotocol/server-github` / `uvx mcp-server-filesystem`)能接进来

---

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类)| yml 写 `agent.mcp.servers: [github, filesystem]`,启动后 `ToolRegistry` 注册到 MCP 暴露的 N 个 tool — Story #021b 之后能 LLM 调 `mcp:github:search_repos`;**#021a 完成前用户配 MCP 配 = 0 效果** |
| **运维稳定性关注者**(Eve 类)| MCP server 子进程 OOM / stdio 僵死 / 反向代理超时踢线 时,**Agent 不崩盘**;`ConnectionState.DISCONNECTED → RECONNECTING → CONNECTED` 自动恢复;**指数退避** `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)` 抗维护窗口 |
| **框架贡献者**(plugin 作者)| 写 `@Component public class MyMcpServerConnectionProvider implements McpServerConnectionProvider` SPI 加新传输(ssh / grpc),**不改** core |
| **CI 工程师**(Charlie 类)| L1/L2 测试 case ≥ 18,`mvn -pl lingshu-core test` 0 fail;`mvn dependency:tree` 0 增量;**fake MCP server** 子进程可控(纯 Java 子进程启动 `< 200ms`,测试用 fixture)|
| **协议研究者**(Dave 类)| `McpServerConnection` interface 8 方法契约 = dsh §6.5 (2.1) L4569-4591 的字面落地;`ConnectionState` 6 态 = dsh L4593-4595 字面落地;**实施者打开 IDE 看到的就是 dsh,无反推** |

---

## WHAT

Story #021a 落地 5 个新接口 / 类 + 2 个修改:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `McpServerConfig` | `@Value` runtime config | `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConfig.java` | ~110 |
| `ConnectionState` | enum 6 态 | `lingshu-core/src/main/java/ai/lingshu/core/mcp/ConnectionState.java` | ~30 |
| `McpServerConnection` | interface `extends AutoCloseable` 8 方法 | `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnection.java` | ~95 |
| `McpServerConnectionFactory` | final class + `static create(McpServerConfig)` | `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java` | ~50 |
| `StdioMcpServerConnection` | `implements McpServerConnection`(process 管理 + 心跳 + 重连)| `lingshu-core/src/main/java/ai/lingshu/core/mcp/StdioMcpServerConnection.java` | ~280 |
| `McpTransportException` | `extends RuntimeException`(LINGS-M01 错误载体)| `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpTransportException.java` | ~30 |
| `AgentConfig.ServerConfig` | 修改:扩 `transport` 枚举 + `url` 字段 + `heartbeatIntervalMs` + `reconnectCapMs` | `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` | +30 / 改 inner class |
| 测试 | L1 + L2 + L3 集成 + fake MCP server 子进程 fixture | `lingshu-core/src/test/java/ai/lingshu/core/mcp/` | ~700 行 / ≥18 case |

**7 核心文件 = 6 新建 + 1 修改 + 1 测试目录**,1 新 ErrorCode(`LINGS-M01`),`mvn dependency:tree` **0 增量**(MCP stdio 用 JDK 内置 `ProcessBuilder`,无新增 Maven 坐标)。

### 1. `McpServerConfig` runtime config(dsh §6.5 (2.1) L4643-4648 + L4838-4851)

```java
package ai.lingshu.core.mcp;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Runtime 配置:单个 MCP server 连接的所有可调参数。
 *
 * <p><b>🆕 Story #021a</b> — runtime-side 配置(对齐 dsh §6.5 (2.1) L4643-4648);
 * YAML 绑定层是 {@link ai.lingshu.core.runtime.AgentConfig.ServerConfig},
 * 两层解耦 —— runtime 层额外带 {@code heartbeatIntervalMs / heartbeatTimeoutMs / reconnectCapMs}
 * 三个可调参数,允许测试 / 生产覆盖默认值。
 *
 * <p><b>为什么单独一层</b>:
 * <ol>
 *   <li>YAML 绑定层 (`AgentConfig.ServerConfig`) 是 immutable {@code @Value},
 *       Spring reload 时整对象替换;runtime 层可被业务代码 / 测试 fixture 灵活构造。</li>
 *   <li>三个心跳参数 (hb / hbTimeout / reconnectCap) 默认值适合"保守生产",但
 *       单元测试需要"快速失败" (hbIntervalMs=100) 才能在 < 1s 内跑完。</li>
 *   <li>未来 hot-reload 场景:reload 不重建 {@code McpServerConfig} 本身(连接不动),
 *       只重读 server-specific 配置。</li>
 * </ol>
 *
 * <p><b>JDK 8 兼容</b>:用 Lombok {@code @Value} + {@code @Builder};不用 {@code record}
 * / {@code sealed} / {@code var} / {@code List.of}。
 */
@Value
@Builder
@Jacksonized
public class McpServerConfig {

    /** 唯一 server 名(对应 yml entry.name)。 */
    String name;

    /** 传输类型 —— 决定 {@code McpServerConnectionFactory.create} 走哪个分支。 */
    Transport transport;

    /** stdio:子进程命令(例 {@code "npx"});SSE/HTTP:可空。 */
    String command;

    /** stdio:子进程参数(例 {@code ["-y", "@modelcontextprotocol/server-github"]})。 */
    @Builder.Default List<String> args;

    /** stdio:子进程环境变量(例 {@code {"GITHUB_TOKEN": "..."}})。 */
    @Builder.Default Map<String, String> env;

    /** SSE / streamable HTTP:服务端 URL;stdio:可空。 */
    String url;

    /** 心跳探测间隔(ms)。默认 30s,生产保守值;测试可调小。 */
    @Builder.Default long heartbeatIntervalMs = 30_000L;

    /** 心跳 ping 等回包超时(ms)。默认 10s。 */
    @Builder.Default long heartbeatTimeoutMs = 10_000L;

    /** 指数退避上限(ms)。默认 60s —— 7 次失败后 cap 在 60s。 */
    @Builder.Default long reconnectCapMs = 60_000L;

    public enum Transport { STDIO, SSE, STREAMABLE_HTTP }
}
```

### 2. `ConnectionState` enum(dsh §6.5 (2.1) L4593-4595)

```java
package ai.lingshu.core.mcp;

/**
 * MCP server 连接生命周期状态机(dsh §6.5 (2.1) L4593-4595)。
 *
 * <p>状态转移:
 * <pre>
 *   IDLE → CONNECTING → CONNECTED ⇄ DISCONNECTED → RECONNECTING → CONNECTED...
 *                                       FAILED ←──── close()
 * </pre>
 *
 * <p>6 态语义:
 * <ul>
 *   <li>{@link #IDLE} — 初始态,未调 {@code start()}</li>
 *   <li>{@link #CONNECTING} — {@code start()} 进行中(子进程拉起 / initialize 握手 / tools/list 缓存)</li>
 *   <li>{@link #CONNECTED} — 握手成功,心跳正常运行,listTools / callTool 可用</li>
 *   <li>{@link #DISCONNECTED} — 心跳失败 / 子进程死 / 探活超时;**transient** — 自动转入 RECONNECTING</li>
 *   <li>{@link #RECONNECTING} — 指数退避等待下一次重试;{@code start()} 重入</li>
 *   <li>{@link #FAILED} — 终态,只能 {@code close()} 出来;用户主动 close 或不可恢复错误</li>
 * </ul>
 */
public enum ConnectionState {
    IDLE, CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING, FAILED
}
```

### 3. `McpServerConnection` interface(dsh §6.5 (2.1) L4569-4591)

```java
package ai.lingshu.core.mcp;

import ai.lingshu.core.message.JsonNode;        // 实际:com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * 单个 MCP server 连接的生命周期接口 —— 实现心跳保活 + 指数退避重连(dsh §6.5 (2.1))。
 *
 * <p><b>🆕 Story #021a</b> — McpTransport 通过 listener 拿到状态变化,据此
 * register / unregister McpToolAdapter。非 CONNECTED 状态 callTool 直接返 error,
 * **不抛异常**(与 §4.10.1 硬规则 2 兼容 —— ToolResult.error 转 LLM 可见的 error message)。
 *
 * <p><b>JDK 8 兼容</b>:接口默认方法全部显式,JDK 8 编译器友好。
 */
public interface McpServerConnection extends AutoCloseable {

    /** 唯一 server 名(对应 cfg.name)。 */
    String name();

    /** 当前连接状态。 */
    ConnectionState state();

    /** 最近一次心跳成功的时间;启动后未成功过则返回构造时刻。 */
    Instant lastHeartbeatAt();

    /**
     * 当前缓存的 tools/list —— 仅在 CONNECTED 状态有有效值;
     * 重连后会重新拉(见 dsh L4705-4708)。
     */
    List<McpToolDescriptor> listTools();

    /**
     * 转发 tools/call;**非 CONNECTED 状态直接返 {@link McpCallResult#error(String)},
     * 不抛异常**(与 §4.10.1 硬规则 2 兼容)。
     */
    McpCallResult callTool(String toolName, JsonNode input);

    /** 状态变化订阅 —— 多个 listener 各自回调,单个 listener 抛异常不影响其他。 */
    void onStateChange(Consumer<ConnectionState> listener);

    /** 启动连接(异步非阻塞);失败会自动 {@code scheduleReconnect()}。 */
    void start();

    /** 关闭连接(同步);进入 FAILED 终态。 */
    @Override void close();
}
```

### 4. `McpServerConnectionFactory`(dsh §6.5 (2.1) L4601-4614)

```java
package ai.lingshu.core.mcp;

/**
 * 按 {@link McpServerConfig#transport} 分派具体实现 ——
 * stdio / SSE / streamable HTTP(对齐 dsh §6.5 (2.1) L4601-4614)。
 *
 * <p><b>Story #021a 范围</b>:只实现 STDIO 分支;SSE / STREAMABLE_HTTP 抛
 * {@link McpTransportException}(LINGS-M01 + "not implemented in #021a")。
 * SSE / HTTP 实现留给后续 Story(#021a.5 或 plugin 作者)。
 */
public final class McpServerConnectionFactory {
    private McpServerConnectionFactory() {}

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
        // unreachable
        throw new IllegalStateException("Unknown transport: " + cfg.getTransport());
    }
}
```

### 5. `StdioMcpServerConnection` 完整实现(dsh §6.5 (2.1) L4623-4819)

```java
package ai.lingshu.core.mcp;

/**
 * stdio 实现 —— 拉起 MCP server 子进程,通过 stdin/stdout 走 JSON-RPC(dsh §6.5 (2.1))。
 *
 * <p>心跳 = {@code process.isAlive()} + MCP {@code ping} 请求;
 * 重连 = 销毁旧进程 + 重启 + 重拉 tools/list。失败路径(子进程死 / ping 超时 / initialize 失败)
 * 统一走 {@code scheduleReconnect()} —— 不会让 Agent 进程因为 MCP server 抖动崩。
 *
 * <p><b>JDK 8 兼容</b>:用 {@link java.util.concurrent.atomic.AtomicReference} /
 * {@link java.util.concurrent.atomic.AtomicInteger} /
 * {@link java.util.concurrent.CopyOnWriteArrayList} +
 * {@link java.util.Collections#emptyList()};不用 {@code List.of} / {@code var} /
 * sealed / records。
 */
public class StdioMcpServerConnection implements McpServerConnection {
    // ... 详见 plan.md §2.5 (完整 ~280 行代码模板)
}
```

---

## §0 Acceptance Criteria(AC-021a-NN)

> AC 由 dsh §0.4 AC 列表(已合 Story 衍生)+ Story #021a WHY 段 4 个问题交叉得出。
> **每个 AC 必须有对应的黑盒测试 case(在 `lingshu-core/src/test/.../mcp/`)**。

### AC-021a-1:`McpServerConfig` runtime config 类型契约

**AC 内容**:`McpServerConfig` 是 Lombok `@Value @Builder @Jacksonized` 不可变 POJO,8 字段(`name / transport / command / args / env / url / heartbeatIntervalMs / heartbeatTimeoutMs / reconnectCapMs`)+ 1 内嵌 `Transport` enum(3 值);默认心跳参数 `30_000 / 10_000 / 60_000`。

**反向 AC**(不应出现):
- ❌ 用 `record`(JDK 14+)— Story #021a 是 JDK 8 only
- ❌ 默认值不为 `30_000 / 10_000 / 60_000`(否则生产心跳频率会偏)

**DoD**:`McpServerConfigTest`(≥ 4 case,见 tasks.md T-08)

### AC-021a-2:`ConnectionState` 6 态字面落地

**AC 内容**:`ConnectionState` enum 严格 6 值 `IDLE / CONNECTING / CONNECTED / DISCONNECTED / RECONNECTING / FAILED`,顺序按 dsh L4593-4595。

**反向 AC**:
- ❌ 缺任何一态
- ❌ 顺序错乱(状态机文档会失同步)

**DoD**:`ConnectionStateTest`(1 case,`assertValues()`)— AC-021a-2

### AC-021a-3:`McpServerConnection` interface 8 方法契约

**AC 内容**:`McpServerConnection extends AutoCloseable` + 8 方法 `name() / state() / lastHeartbeatAt() / listTools() / callTool(String, JsonNode) / onStateChange(Consumer<ConnectionState>) / start() / close()`,Javadoc 明确"非 CONNECTED 状态 callTool 直接返 error 不抛异常"(与 §4.10.1 硬规则 2 兼容)。

**反向 AC**:
- ❌ 缺任何一方法
- ❌ callTool 抛异常而非返 error(破坏 §4.10.1 硬规则 2 兼容性)

**DoD**:`McpServerConnectionContractTest`(1 case,`interfaceHasEightMethods`)

### AC-021a-4:`McpServerConnectionFactory` 按 transport 分派

**AC 内容**:`McpServerConnectionFactory.create(cfg)` 按 `cfg.transport()` 分派 —— `STDIO` → `new StdioMcpServerConnection(cfg)`;`SSE` / `STREAMABLE_HTTP` → 抛 `McpTransportException("LINGS-M01", "...not implemented in #021a")`。

**反向 AC**:
- ❌ 缺 STDIO 分支(否则 stdio MCP server 接不进)
- ❌ SSE / HTTP 不抛 LINGS-M01 而返回 null(静默失败)

**DoD**:`McpServerConnectionFactoryTest`(≥ 4 case,见 tasks.md T-09)

### AC-021a-5:`StdioMcpServerConnection.start()` 5 步流程

**AC 内容**:`start()` 严格按 dsh L4689-4722 的 5 步执行:
1. `ProcessBuilder.start()` 拉子进程 + 重定向 stderr → stdout
3. 发 MCP `initialize` 帧 → 等回包(超时 `heartbeatTimeoutMs`)
4. 发 MCP `initialized` notification(server 不回包)
5. 发 MCP `tools/list` 帧 → 解析响应缓存 `cachedTools`
6. 切 `ConnectionState.CONNECTED` + 重置 `reconnectAttempts=0` + 启 daemon 心跳

**反向 AC**:
- ❌ 跳过 initialize 握手(破坏 MCP 协议)
- ❌ 不缓存 tools/list(后续 callTool 不知道参数 schema)
- ❌ 启心跳前未重置 reconnectAttempts(指数退避计数永远不归零)

**DoD**:`StdioMcpServerConnectionStartTest`(≥ 5 case,见 tasks.md T-10)— 用 fake MCP server fixture(纯 Java `##`)

### AC-021a-6:`StdioMcpServerConnection` 心跳保活 + 双探活

**AC 内容**:daemon `ScheduledExecutorService` 每 `heartbeatIntervalMs` 探活一次,**双探活**:
- `process.isAlive()`(子进程死了 → DISCONNECTED → scheduleReconnect)
- MCP `ping` 请求(子进程僵死但没死 → 超时 → DISCONNECTED → scheduleReconnect)

成功后 `lastHeartbeatAt.set(Instant.now())`。

**反向 AC**:
- ❌ 只用 process.isAlive()(僵死检测漏掉 → scheduler 永远以为活)
- ❌ 只用 ping(子进程死掉但 stdin 没关 → ping hang)
- ❌ 探活间隔过长(进程死了 30s 后才发现 → 30s 内 LLM 调 MCP 全 error)

**DoD**:`StdioMcpServerConnectionHeartbeatTest`(≥ 4 case,见 tasks.md T-11)

### AC-021a-7:指数退避 `1s → 2s → 4s → 8s → 16s → 32s → 60s(cap)`

**AC 内容**:`scheduleReconnect()` 调度公式 `delayMs = Math.min(reconnectCapMs, (1L << Math.min(attempt - 1, 6)) * 1000L)` —— attempt=1 → 1s, 2 → 2s, 3 → 4s, ..., 7+ → 60s(cap);`reconnectAttempts.incrementAndGet()` 在每次 schedule 时;失败**无限**重试(无 maxAttempts 上限)。

**反向 AC**:
- ❌ 退避序列错(2s → 4s → ... 漏 1s 起点)
- ❌ 上限超过 reconnectCapMs(60s 之后还在指数上升)
- ❌ 限制重试次数(用户运维场景 MCP server 可能维护几小时,应无限重试)

**DoD**:`StdioMcpServerConnectionReconnectTest`(≥ 3 case,见 tasks.md T-12)— 用 `hbIntervalMs=50` 测试 fixture 1s 内验证 7 步序列

### AC-021a-8:listener 多订阅 + 异常隔离

**AC 内容**:`onStateChange(Consumer)` 注册 listener,内部 `CopyOnWriteArrayList` 持；状态变化时 for-each 调用,**单个 listener 抛异常 catch 住不影响其他 listener**。CONNECTED → `McpTransport` 注册 tool,DISCONNECTED / FAILED → 注销 tool。

**反向 AC**:
- ❌ listener 抛异常中断后续 listener(`McpTransport` 可能漏注册 tool)
- ❌ listener 注册时机过晚(错过首次 CONNECTED → 用户首 turn 看不到 MCP tool)

**DoD**:`StdioMcpServerConnectionListenerTest`(≥ 3 case,见 tasks.md T-13)

### AC-021a-9:`McpServerConnection.close()` 同步进入 FAILED 终态

**AC 内容**:`close()` 同步:
- `hb.shutdownNow()`(停心跳)
- `stopProcess()`(`process.destroy()` → 等 5s → `destroyForcibly()`)
- `transition(FAILED)`

幂等(多次 close 安全)。

**反向 AC**:
- ❌ close 后还启心跳(daemon 线程泄露)
- ❌ 子进程 destroy 不 waitFor(僵尸进程)
- ❌ 不切 FAILED 状态(McpTransport 不知道连接死了)

**DoD**:`StdioMcpServerConnectionCloseTest`(≥ 2 case,见 tasks.md T-14)

### AC-021a-10:`AgentConfig.ServerConfig` 扩 `transport` 枚举 + `url` 字段

**AC 内容**:`AgentConfig.ServerConfig`(`@Value` inner class)从 4 字段扩到 6 字段:`name / transport / command / args / env / url`,`transport` 默认 `STDIO`(向后兼容 Story #020c 之前的所有 yml)。

**反向 AC**:
- ❌ 加 required 字段(老 yml 启动失败)
- ❌ 不给 transport 默认值(老 yml 反序列化失败)

**DoD**:`AgentConfigMcpExpansionTest`(≥ 3 case,见 tasks.md T-15)

### AC-021a-11:R-13 dep-tree 0 增量

**AC 内容**:`mvn -pl lingshu-core dependency:tree` pre/post 对比,**0 binary delta**(MCP stdio 用 JDK 内置 `java.lang.ProcessBuilder`,无需额外 Maven 坐标)。

**反向 AC**:
- ❌ 引入 `net.java.dev.jna`(JNI 错误提示映射)— R-13 锁
- ❌ 引入 `org.json`(JSON 解析)— Jackson 已锁

**DoD**:`AC-021a-deps-1`(dependency:tree pre/post 截图,在 PR body 末尾)

---

## EC(Edge Cases)

### EC-021a-1:`StdioMcpServerConnection` start() 期间子进程立即死

**触发**:cfg.command 是无效命令(如 `this-command-does-not-exist`)→ `ProcessBuilder.start()` 抛 `IOException`。
**期望**:`catch (Exception e)` → `scheduleReconnect()` → 状态 `RECONNECTING`(非 IDLE);日志 `[MCP:xxx] start failed: ...`。
**DoD**:`StdioMcpServerConnectionStartErrorTest`(1 case)

### EC-021a-2:`callTool()` 在非 CONNECTED 状态被调用

**触发**:MCP server 突然断开(LLM 调 MCP tool 的同时),`StdioMcpServerConnection.state()` 已变 `DISCONNECTED`。
**期望**:`callTool()` 直接返 `McpCallResult.error("MCP server xxx not connected: DISCONNECTED")`,**不抛异常**。
**DoD**:`StdioMcpServerConnectionCallToolNotConnectedTest`(1 case)

### EC-021a-3:`process.destroyForcibly()` 后进程仍存在(Windows 测试)

**触发**:Windows 平台 `process.destroy()` 经常无法 kill 子进程。
**期望**:`stopProcess()` 等 5s 后强杀;若 destroyForcibly 后 `process.isAlive()` 仍 true,记日志但**不抛异常**(JDK 限制)。
**DoD**:跨平台 CI 测试已 green(在 GitHub Actions matrix ubuntu + Windows runner 跑过)— 见 T-16

### EC-021a-4:`McpServerConfig.builder()` args / env 为 null

**触发**:调用 `McpServerConfig.builder().name("x").transport(STDIO).command("c").build()`(不显式 set args / env)。
**期望**:`@Builder.Default` 让 `args = Collections.emptyList()` + `env = Collections.emptyMap()`,非 null。
**DoD**:`McpServerConfigTest.builderDefaults`(1 case,见 T-08)

---

## 反向 AC(Story 边界守门员)

- ❌ **实现 SSE / STREAMABLE_HTTP** —— Story #021a 只做 stdio;SSE/HTTP 留给后续 Story(plugin 作者或 #021a.5)
- ❌ **修改 `McpTransport` 或 `McpToolAdapter`** —— Story #021a 只做 connection 子系统;#021b 才组装 McpTransport + 包装 McpToolAdapter
- ❌ **改 `ToolExecutor` 5 步流水线** —— dsh §4.10.1 硬规则 2,MCP 断流在 `ToolResult` 层只表现为 error,**不会绕过沙箱 / 权限 / checkpoint 任何一步**
- ❌ **引入额外 Maven 依赖** —— R-13 dep-tree 0 增量(MCP stdio 用 JDK 内置 ProcessBuilder)
- ❌ **改 `AgentConfig.ServerConfig` 的 `name / command / args / env` 4 字段** —— Story #020a + #020b + #020c 都用了这 4 字段,改语义 = 破向后兼容;只允许**新增** `transport / url / heartbeatIntervalMs / reconnectCapMs`
- ❌ **支持 Windows 平台特定特性** —— Story #021a 范围是 POSIX 子进程;Windows 兼容性靠 EC-021a-3 的 graceful fallback,不开 Windows-only 配置
- ❌ **把 `McpServerConfig` 标 `@Component` 注入 Spring** —— 这是 runtime config,**不**走 Spring SPI(避免与 `AgentConfig.ServerConfig` 双源);由 `#021b McpTransport.start()` 显式从 `AgentConfig` 构造

---

## 文件清单(7 文件,1 测试目录)

**新建**(6):
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConfig.java`
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/ConnectionState.java`
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnection.java`
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpServerConnectionFactory.java`
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/StdioMcpServerConnection.java`
- `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpTransportException.java`

**修改**(1):
- `lingshu-core/src/main/java/ai/lingshu/core/runtime/AgentConfig.java` —— `ServerConfig` inner class +6 字段(`transport / url / heartbeatIntervalMs / heartbeatTimeoutMs / reconnectCapMs`)

**新建测试**(1 目录 + ≥18 case):
- `lingshu-core/src/test/java/ai/lingshu/core/mcp/`:
  - `McpServerConfigTest.java`(L1, 4 case)
  - `ConnectionStateTest.java`(L1, 1 case)
  - `McpServerConnectionContractTest.java`(L1, 1 case)
  - `McpServerConnectionFactoryTest.java`(L2, 4 case)
  - `StdioMcpServerConnectionStartTest.java`(L2 + L3, 5 case)
  - `StdioMcpServerConnectionHeartbeatTest.java`(L2 + L3, 4 case)
  - `StdioMcpServerConnectionReconnectTest.java`(L2 + L3, 3 case)
  - `StdioMcpServerConnectionListenerTest.java`(L2, 3 case)
  - `StdioMcpServerConnectionCloseTest.java`(L2, 2 case)
  - `StdioMcpServerConnectionCallToolNotConnectedTest.java`(EC, 1 case)
  - `AgentConfigMcpExpansionTest.java`(L1, 3 case)
  - fixture: `TestMcpServer.java`(L3 集成测试用 fake MCP server,可启动为子进程返回 JSON-RPC frames)

**总计**:12 测试文件 / ≥31 case / ~700 行测试代码 / ~600 行生产代码

---

## 关联文档

- dsh v1.5.37 §6.5 (2.1) L4553-4871(McpServerConnection 心跳保活与重连)
- dsh v1.5.37 §6.5 (2) L4454-4551(McpTransport 调用契约)
- dsh v1.5.37 §15.9 L7211-7220(ErrorCode 编码约定)
- dsh v1.5.37 §4.10.1 硬规则 2(ToolExecutor 5 步流水线)
- Story #021b(下一块砖:`McpTransport` 监听器注册 + `McpToolAdapter` 包装)
- Story #020a skill-foundation(`Skill` interface + `ToolRegistry` 双索引模式,日后 MCP tool 也走 ToolRegistry)
- Story #020c cli-skill-trigger(`--list-skills` banner 模式,日后 `--list-mcp` 可复用)
- ROADMAP §6.5 (2.1) 提议 Story 列表第 4 行(`#021a mcp-stdio-transport`)