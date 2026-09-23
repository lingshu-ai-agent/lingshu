# Story #021b `mcp-tool-adapter` — Spec

> **Status**: Draft 2026-09-23
> **Source**: dsh v1.5.37 §6.5 (2) `McpTransport` 调用契约 + `McpToolAdapter` 包装(L4454-4551)+ §15.9 Error Catalog 编码约定 → §15.10 MCP 域 LINGS-M02 顺延 + §4.10.1 硬规则 2(ToolExecutor 5 步流水线不变)
> **Closes gap**:Story #021b 是 MCP 故事链的**第二块砖** — `#021a`(`McpServerConnection` 生命周期 + stdio 连接)已落,但 **3 件全缺**:
> (1) **`McpTransport`** —— dsh §6.5 (2) L4511-4551 完整给出 `@Component public class McpTransport { connect(List<McpServerConfig> configs, ToolRegistry registry) { ... } callTool(String serverName, String toolName, JsonNode input) {...} }` 模式 —— listener 模式 + `factory.create(cfg)` + `conn.onStateChange(...)` + 异步 `conn.start()` + `onConnectionStateChange()` 私有方法处理 `CONNECTED → register / DISCONNECTED → unregister`,**完全 0 实现**;当前 `grep "McpTransport" lingshu-core/src/main/java/` 仅命中 `AgentConfig.java` 的 `Mcp` inner class 字段,**没有任何 transport 实现**;
> (2) **`McpToolAdapter`** —— dsh L4456-4493 完整给出 `public class McpToolAdapter implements Tool { ctor(McpTransport, String serverName, McpToolDescriptor) + name()/description()/inputSchema() + execute() { McpCallResult r = transport.callTool(serverName, toolName, call.getInput()); ... } }`,**完全 0 实现**;
> (3) **`ToolRegistry.unregister(String)`** —— dsh L4538 明确要求 `registry.unregister(cfg.name + ":" + t.name())`,但当前 `ToolRegistry` interface **缺该方法**(只有 `register(Tool)`),**#021a 没补** —— 这是 dsh 文档与代码实现的最后一个 gap。
>
> 后果:`#021a` 落地的 `McpServerConnection` 子系统是孤岛 —— Agent 启动后用户配 `agent.mcp.servers: [github]` 仍然 0 个 MCP tool 注入 `ToolRegistry`,`ToolExecutor.dispatch()` 永远找不到 `mcp:github:search_repos`。**整个 MCP 集成链断在第二环**,从「MCP server 连上」到「Agent 实际能调用 MCP tool」缺最后一块。

---

## WHY

dsh §6.5 (2) L4454-4551 的 `McpTransport` + `McpToolAdapter` 是 MCP server ↔ LingShu Agent **桥接** 的关键 —— `McpServerConnection` 子系统只解决了「MCP server 长生命周期 + 心跳保活 + 指数退避重连」问题,但 `#021a` 完成后 `ToolRegistry` 仍然 0 MCP tool,**LLM 看不到 MCP server 暴露的 tool,Agent 实际无法驱动 GitHub / filesystem / PostgreSQL 等外部数据源**。当前 lingshu-core 仓存在 4 个连锁问题:

1. **dsh §6.5 (2) L4454-4551 文档契约是 single-source-of-truth,但代码侧 0 实现** — `McpTransport.connect(configs, registry)` 6 行核心逻辑(`factory.create(cfg)` + `conn.onStateChange(...)` + `connections.add(conn)` + `conn.start()`)+ `McpTransport.callTool(serverName, toolName, input)` 转发 + `McpTransport.onConnectionStateChange()` 私有方法 12 行(`CONNECTED → register new McpToolAdapter(this, cfg.name, t)` × N + `DISCONNECTED → registry.unregister(...)` × N),共 ~60 行核心代码,**全部 0 行落地**。
2. **`McpToolAdapter` 完全 0 实现** — dsh L4456-4493 给的 `McpToolAdapter implements Tool` 是 MCP 桥接 LingShu `Tool` 接口的**唯一接口**,`ToolExecutor.dispatch()` 通过它调到 `transport.callTool(serverName, toolName, input)` → `McpServerConnection.callTool(...)` → stdio 子进程 JSON-RPC。**没有它,`ToolRegistry.register(new McpToolAdapter(...))` 都编译不过**。
3. **`ToolRegistry.unregister(String)` SPI 缺** — dsh L4538 明确 `registry.unregister(cfg.name + ":" + t.name())`,但当前 `ToolRegistry` interface **只有 7 方法**(`register / lookup / names / modelVisibleSpecs / findSkill / skillNames / findByName`),**无 unregister**。`DefaultToolRegistry` 实现也没这个方法。这是 #021a 留下的**接口契约 gap** —— #021b 必须补。**向后兼容扩展**:纯新增方法,无破坏性。
4. **`LINGS-Mxx` MCP 错误域 `M01` 已用,但 `M02` 未启用** — dsh §15.9 L7211-7220 编码约定要求 `#021a` 用 `LINGS-M01`(`MCP_CONNECT_FAILED`),**`#021b` 必须用 `LINGS-M02`**(`MCP_TOOL_CALL_FAILED`,对应 `McpToolAdapter.execute()` 捕获到非预期异常时的语义级 error 转化 —— `McpCallResult.isError() == true` 由 connection 自己管,但 adapter 层捕获的 `Exception`(e.g. connection 在 call 间隙突然 down)必须转成 `LINGS-M02`)。

**Story #021b 目标**:落地 `McpTransport`(@Component,listener 模式连接 + register/unregister 钩子 + callTool 转发)+ `McpToolAdapter`(Tool 接口包装,execute 通过 transport 转发)+ 扩展 `ToolRegistry.unregister(String)` SPI + 默认实现 + 新增 `McpTransportException` 复用 `#021a` 已有的错误载体(支持 `LINGS-M02`)+ Spring `@Configuration` 启动期 `connect` 接线,`@PreDestroy` 优雅停机,让 Agent 启动后 MCP server 暴露的 N 个 tool 自动注入 `ToolRegistry`,`ToolExecutor.dispatch()` 能调到 MCP tool。

**业务价值**:
- dsh §6.5 (2) + §6.5 (2.1) 文档契约 → 代码 single-source-of-truth,`#021c`(SSE/HTTP)实施者直接调 `McpTransport` + `McpToolAdapter` 不用反推
- dsh §0.4 AC-09 "MCP server 多源接入"第 2 步 — `#021a` 后 stdio 连接活了,但 0 tool 注册;#021b 后 Agent **真正能调** MCP tool
- LINGS-M02 错误域就位,后续 `#021c`(SSE/HTTP)+ Spring AI `@Tool`(#022)+ Sub-agent(#023)复用同一错误码表
- `ToolRegistry.unregister(String)` SPI 补全 — MCP 重连 / 后续动态注册场景的标准入口,**契约稳定后**未来 plugin 也可用

---

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类)| yml 写 `agent.mcp.servers: [github, filesystem]`,**启动后 LLM 自动看到** MCP server 暴露的 N 个 tool(以 `<server>:<tool>` 命名空间形式注册到 `ToolRegistry`);`/xxx` CLI 命令可直接调 `#021c` 后(`#021b` 不涉及 CLI 层);**Story #021b 完成后用户能用 MCP tool 实际工作** |
| **运维稳定性关注者**(Eve 类)| MCP server 子进程 OOM / stdio 僵死,**ToolRegistry 自动 unregister stale MCP tools**(不再有 dangling reference);重连成功后 **re-register 新 tool 列表**(server 升级新增 tool 自动可见);**`ToolExecutor.dispatch()` 不会被调到 stale tool 报 NPE** |
| **框架贡献者**(plugin 作者)| 写新 MCP 传输(`#021c` SSE / streamable HTTP)只需 `implements McpServerConnection` + `McpServerConnectionFactory` 加一行,**`McpTransport` 与 `McpToolAdapter` 0 改动** |
| **CI 工程师**(Charlie 类)| L1/L2 测试 case ≥ 18,`mvn -pl lingshu-core test` 0 fail;`mvn dependency:tree` 0 增量;**fake MCP server 子进程可控**(复用 `#021a` `McpTestSupport`)|
| **协议研究者**(Dave 类)| `McpTransport.connect(configs, registry)` = dsh §6.5 (2) L4515-4524 字面落地;`McpToolAdapter` = dsh L4456-4493 字面落地;`ToolRegistry.unregister(String)` = dsh L4538 字面落地(向后兼容扩展);**实施者打开 IDE 看到的就是 dsh,无反推** |

---

## WHAT

Story #021b 落地 **5 个新接口 / 类 + 2 个修改**:

| 产出 | 类型 | 路径 | 行数预算 |
|---|---|---|---|
| `McpTransport` | `@Component public class` | `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransport.java` | ~150 |
| `McpToolAdapter` | `public class implements Tool` | `lingshu-core/src/main/java/ai/lingshu/core/mcp/McpToolAdapter.java` | ~90 |
| `McpTransportLifecycle` | `@Component public class implements SmartLifecycle` | `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportLifecycle.java` | ~80 |
| `McpTransportAutoConfiguration` | `@Configuration` + `@Bean` 提供 `McpTransport`(从 `AgentConfig.Mcp` 解构 `List<McpServerConfig>`)| `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpTransportAutoConfiguration.java` | ~70 |
| `McpServerConfigYmlBinder` | `@ConfigurationProperties`(`@Bean` 风格)从 yml 字段到 `McpServerConfig` runtime config | `lingshu-core/src/main/java/ai/lingshu/core/impl/mcp/McpServerConfigYmlBinder.java` | ~80 |
| `ToolRegistry` | 修改:扩 `void unregister(String name)` 方法 | `lingshu-core/src/main/java/ai/lingshu/core/slot/ToolRegistry.java` | +20 |
| `DefaultToolRegistry` | 修改:实现 `unregister` | `lingshu-core/src/main/java/ai/lingshu/core/impl/tool/DefaultToolRegistry.java` | +25 |
| 测试 | L1 + L2 + L3 集成 + 复用 `#021a` `McpTestSupport` fixture | `lingshu-core/src/test/java/ai/lingshu/core/impl/mcp/` | ~600 行 / ≥18 case |

**7 核心文件 = 5 新建 + 2 修改 + 1 测试目录**,**复用 #021a 的 `LINGS-M01` + 新增 `LINGS-M02`**(`MCP_TOOL_CALL_FAILED`,`mcp.McpToolAdapter.execute()` 捕获到非预期 exception 转此码),`mvn dependency:tree` **0 增量**(MCP transport 用 JDK 内置 `ProcessBuilder` + Jackson `JsonNode`,无新增 Maven 坐标)。

### 1. `McpTransport` 组件(dsh §6.5 (2) L4511-4551)

```java
package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.mcp.ConnectionState;
import ai.lingshu.core.mcp.McpCallResult;
import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.mcp.McpServerConnection;
import ai.lingshu.core.mcp.McpServerConnectionFactory;
import ai.lingshu.core.mcp.McpToolDescriptor;
import ai.lingshu.core.mcp.McpTransportException;
import ai.lingshu.core.mcp.McpToolAdapter;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MCP server 总装组件(Story #021b, dsh §6.5 (2) L4454-4551)——
 * {@link McpServerConnection} 子系统的"协调者"。
 *
 * <p><b>职责</b>(dsh §6.5 (2) L4511-4551):
 * <ol>
 *   <li>持有 {@code List<McpServerConnection> connections},每个 MCP server 一份</li>
 *   <li>{@link #connect(List, ToolRegistry)} 按 cfg 走 {@link McpServerConnectionFactory}
 *       拉连接,挂 listener,异步 start()</li>
 *   <li>{@link #callTool(String, String, JsonNode)} 转发 tool call 到对应 server 的 connection</li>
 *   <li>状态变化回调:CONNECTED → {@code registry.register(new McpToolAdapter(this, cfg.name, t))}
 *       × N;DISCONNECTED/FAILED → {@code registry.unregister(...)} × N</li>
 * </ol>
 *
 * <p><b>🆕 Story #021b</b> —— `connect()` 收 {@link McpServerConfig} runtime config list(与
 * `#021a` 落地的 `McpServerConnectionFactory.create(McpServerConfig)` 对齐);YAML 绑定层
 * 由 {@link McpServerConfigYmlBinder} 负责把 {@code AgentConfig.Mcp.servers}
 * (扩展了 `transport / url / heartbeat*3 / reconnectCapMs` 5 字段,#021a)转 runtime config。
 *
 * <p><b>命名空间</b> — `McpToolAdapter.name()` 走 `serverName + ":" + toolName` 形式
 * (如 {@code "github:search_repos"}),**避免与本地 Read/Write/Edit/Bash 冲突**;
 * `ToolExecutor.dispatch()` 通过 `ToolRegistry.lookup("github:search_repos")` 找到 adapter,
 * adapter 内部已知 `serverName`,execute 时 `transport.callTool("github", "search_repos", input)`
 * 转发。
 *
 * <p><b>与 §4.10.1 硬规则 2 兼容</b> —— `McpToolAdapter.execute()` 拿到的永远是
 * `McpCallResult`(success/error 包装),`ToolExecutor.dispatch()` 走 5 步流水线
 * (`PermissionPolicy.check() → ToolRegistry.lookup(name) → TimeoutWrap → SandboxApply
 * → tool.execute() → Checkpoint`)不绕过任何一步,MCP 断线只是「error 替代 success」。
 *
 * <p><b>JDK 8 兼容</b> — `@Component`,`CopyOnWriteArrayList`,无 `var` / `List.of` /
 * sealed / records。
 */
@Component
public class McpTransport {

    private static final Logger LOG = LoggerFactory.getLogger(McpTransport.class);

    private final List<McpServerConnection> connections = new CopyOnWriteArrayList<>();

    /** 启动期调用,按 cfg 拉连接 + 挂 listener + 异步 start。 */
    public void connect(List<McpServerConfig> configs, ToolRegistry registry) {
        if (configs == null || configs.isEmpty()) {
            LOG.info("No MCP servers configured — McpTransport idle");
            return;
        }
        for (McpServerConfig cfg : configs) {
            try {
                // 🆕 #021b:用 factory 按 transport 分派(stdio 已实现,SSE/HTTP 抛 LINGS-M01)
                McpServerConnection conn = McpServerConnectionFactory.create(cfg);
                conn.onStateChange(state -> onConnectionStateChange(cfg, conn, state, registry));
                connections.add(conn);
                conn.start();   // 异步非阻塞,首次 CONNECTED 由 listener 触发注册
            } catch (McpTransportException e) {
                LOG.error("[MCP:{}] factory rejected transport={}: code={} msg={}",
                    cfg.getName(), cfg.getTransport(), e.getCode(), e.getMessage());
                // 不重试,等 #021c 落 SSE/HTTP — 当前直接打 error 日志让用户知道
            }
        }
        LOG.info("McpTransport connected to {} server(s): {}",
            connections.size(), summarizeNames());
    }

    /** 状态变化回调 —— CONNECTED 注册 tools,DISCONNECTED/FAILED 注销 tools。 */
    private void onConnectionStateChange(McpServerConfig cfg, McpServerConnection conn,
                                          ConnectionState state, ToolRegistry registry) {
        if (state == ConnectionState.CONNECTED) {
            // 重连成功后**重新**拉 tools/list —— 可能新增 / 删除 / 改 schema
            int registered = 0;
            for (McpToolDescriptor t : conn.listTools()) {
                String namespacedName = cfg.getName() + ":" + t.getName();
                registry.register(new McpToolAdapter(this, cfg.getName(), namespacedName, t));
                registered++;
            }
            LOG.info("[MCP:{}] (re)connected, {} tools registered", cfg.getName(), registered);
        } else if (state == ConnectionState.DISCONNECTED || state == ConnectionState.FAILED) {
            // 断了先把 tools 撤掉 —— ToolExecutor.dispatch() 不会调到 stale tool
            int unregistered = 0;
            for (McpToolDescriptor t : conn.listTools()) {
                String namespacedName = cfg.getName() + ":" + t.getName();
                registry.unregister(namespacedName);
                unregistered++;
            }
            LOG.warn("[MCP:{}] disconnected ({}), {} tools unregistered",
                cfg.getName(), state, unregistered);
        }
        // CONNECTING / RECONNECTING / IDLE 不动 registry(中间态,避免抖动)
    }

    /**
     * 转发 tool call 到对应 server 的 connection。
     * @throws IllegalStateException 找不到对应 serverName
     */
    public McpCallResult callTool(String serverName, String toolName, JsonNode input) {
        McpServerConnection conn = connections.stream()
            .filter(c -> c.name().equals(serverName))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Unknown MCP server: " + serverName));
        return conn.callTool(toolName, input);
    }

    /** 优雅停机 —— 关闭所有 connection,清理 connections 列表。 */
    public void close() {
        LOG.info("McpTransport shutting down — closing {} connection(s)", connections.size());
        for (McpServerConnection conn : new ArrayList<>(connections)) {
            try {
                conn.close();
            } catch (Exception e) {
                LOG.warn("[MCP:{}] close() failed: {}", conn.name(), e.toString());
            }
        }
        connections.clear();
    }

    /** 当前持有的 connection 列表(只读快照)。 */
    public List<McpServerConnection> getConnections() {
        return Collections.unmodifiableList(connections);
    }

    private String summarizeNames() {
        List<String> names = new ArrayList<>(connections.size());
        for (McpServerConnection c : connections) names.add(c.name());
        return names.toString();
    }
}
```

### 2. `McpToolAdapter` Tool 接口包装(dsh §6.5 (2) L4456-4493)

```java
package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpTransport;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP tool 适配器(Story #021b, dsh §6.5 (2) L4456-4493)——
 * 把 MCP server 暴露的一个 tool 包装成 LingShu {@link Tool} 接口。
 *
 * <p><b>执行路径</b>(dsh §4.10.1 硬规则 2)——
 * {@link ToolExecutor#dispatch} → {@code PermissionPolicy.check()} →
 * {@code registry.lookup(name)} 命中本 adapter → {@code TimeoutWrap} →
 * {@code SandboxApply(fs / http / process)} → {@code tool.execute()} → {@code Checkpoint}。
 * ToolExecutor 看到的都是同一个 {@link Tool} 接口,**不知道** execute 转发到 MCP server。
 *
 * <p><b>错误语义</b>——
 * <ul>
 *   <li>{@link McpCallResult#isError()} == true → {@link ToolResult#error} 转 LLM 可见的 error message</li>
 *   <li>execute() 内捕获非预期 {@link Exception} → 转 {@link McpTransportException}
 *       码 {@code LINGS-M02}({@code MCP_TOOL_CALL_FAILED})→ {@link ToolResult#error}</li>
 *   <li>任何情况下**不抛异常**(对齐 §4.10.1 硬规则 2)</li>
 * </ul>
 *
 * <p><b>JDK 8 兼容</b> — 传统 POJO,无 {@code record} / {@code var} / sealed。
 */
public class McpToolAdapter implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolAdapter.class);

    private final McpTransport transport;
    private final String serverName;
    /** 完整 tool 名(`serverName + ":" + toolName`)—— 用于 {@link ToolRegistry} 索引。 */
    private final String namespacedName;
    /** 远端 tool 名(去除命名空间)—— 用于 {@code tools/call} 转发。 */
    private final String remoteToolName;
    private final String description;
    private final JsonNode inputSchema;

    public McpToolAdapter(McpTransport transport, String serverName,
                          String namespacedName, McpToolDescriptor desc) {
        this.transport = transport;
        this.serverName = serverName;
        this.namespacedName = namespacedName;
        this.remoteToolName = desc.getName();
        this.description = desc.getDescription();
        this.inputSchema = desc.getInputSchema();
    }

    @Override public String name()        { return namespacedName; }
    @Override public String description() { return description; }
    @Override public JsonNode inputSchema() { return inputSchema; }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        try {
            McpCallResult r = transport.callTool(serverName, remoteToolName, call.getInput());
            return r.isError()
                ? ToolResult.error(call.getId(), r.getErrorMessage())
                : ToolResult.success(call.getId(), r.getContent());
        } catch (McpTransportException e) {
            // 已知 MCP 错误(转发阶段捕获)—— 转 LLM 可见 error
            LOG.warn("[MCP:{}] call failed code={} msg={}",
                serverName, e.getCode(), e.getMessage());
            return ToolResult.error(call.getId(),
                "MCP call failed [" + e.getCode() + "]: " + e.getMessage());
        } catch (Exception e) {
            // 未预期 —— 转 LINGS-M02
            LOG.warn("[MCP:{}] call threw {}: {}", serverName, e.getClass().getSimpleName(), e.toString());
            return ToolResult.error(call.getId(),
                "MCP call failed [LINGS-M02]: " + e.getMessage());
        }
    }
}
```

### 3. `McpTransportLifecycle` Spring 生命周期桥接

```java
package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring lifecycle 桥接(Story #021b)——
 * 把 {@link McpTransport#connect} 挂到 ApplicationContext 启动阶段,把
 * {@link McpTransport#close} 挂到关闭阶段。
 *
 * <p><b>为什么 {@link SmartLifecycle} 而不是 {@code @PostConstruct}</b> —
 * dsh §17 R-13 规避 {@code javax.annotation-api} 依赖(lingshu-core 不引 spring-boot-autoconfigure
 * & 不引 javax.annotation-api,见 #019 `LocalToolsAutoConfiguration` 同款 rationale);Spring 自带
 * {@code SmartLifecycle} 在 {@code spring-context} 已 transitive,不增依赖。
 *
 * <p><b>phase</b> —— 默认 `Integer.MAX_VALUE - 1024`(SmartLifecycle 默认值),在所有
 * 标准 lifecycle bean 之后启动,**所有**正常 business bean 之后停止。
 */
@Component
public class McpTransportLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(McpTransportLifecycle.class);

    private final McpTransport transport;
    private final List<ai.lingshu.core.mcp.McpServerConfig> configs;
    private final ToolRegistry toolRegistry;
    private volatile boolean running;

    public McpTransportLifecycle(McpTransport transport,
                                  List<ai.lingshu.core.mcp.McpServerConfig> configs,
                                  ToolRegistry toolRegistry) {
        this.transport = transport;
        this.configs = configs;
        this.toolRegistry = toolRegistry;
    }

    @Override public void start() {
        transport.connect(configs, toolRegistry);
        running = true;
        LOG.info("McpTransportLifecycle started — McpTransport connected");
    }

    @Override public void stop() {
        try {
            transport.close();
        } finally {
            running = false;
        }
        LOG.info("McpTransportLifecycle stopped");
    }

    @Override public boolean isRunning() { return running; }
}
```

### 4. `McpTransportAutoConfiguration` `@Bean` 接线

```java
package ai.lingshu.core.impl.mcp;

import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.McpTransportType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * McpTransport 启动接线(Story #021b)—— 把 {@link AgentConfig.Mcp} 里的 server 列表
 * 解构为 {@link McpTransport} 需要的 runtime config list。
 *
 * <p><b>为什么不用 {@code @ConfigurationProperties}</b> 走 Spring Boot 自动绑定
 * —— {@link McpServerConfig} 是 lombok {@code @Value} POJO(由 #021a 落地),
 * 没有 Spring 元数据,JDK 8 不允许 record;从 {@link AgentConfig.ServerConfig}(YAML 绑定层)
 * 手写 to-runtime 转换,**零依赖**。
 */
@Configuration
public class McpTransportAutoConfiguration {

    @Bean(name = "mcpServerConfigs")
    public List<McpServerConfig> mcpServerConfigs(AgentConfig agentConfig) {
        if (agentConfig == null || agentConfig.getMcp() == null
                || agentConfig.getMcp().getServers() == null
                || agentConfig.getMcp().getServers().isEmpty()) {
            return Collections.emptyList();
        }
        List<McpServerConfig> out = new ArrayList<>();
        for (AgentConfig.ServerConfig sc : agentConfig.getMcp().getServers()) {
            out.add(toRuntimeConfig(sc));
        }
        return Collections.unmodifiableList(out);
    }

    /** AgentConfig.ServerConfig(YAML) → McpServerConfig(runtime) 转换器。 */
    private McpServerConfig toRuntimeConfig(AgentConfig.ServerConfig sc) {
        McpServerConfig.McpServerConfigBuilder b = McpServerConfig.builder()
            .name(sc.getName())
            .transport(toRuntimeTransport(sc.getTransport()))
            .command(sc.getCommand())
            .args(sc.getArgs() != null ? sc.getArgs() : Collections.<String>emptyList())
            .env(sc.getEnv() != null ? sc.getEnv() : Collections.<String, String>emptyMap())
            .url(sc.getUrl());
        // 三个心跳参数:AgentConfig.ServerConfig 已扩 5 字段(#021a);runtime 层可被
        // 测试 / 生产覆盖,但 AgentConfig 0 字段 → 用 0L sentinel 走默认
        if (sc.getHeartbeatIntervalMs() > 0) b.heartbeatIntervalMs(sc.getHeartbeatIntervalMs());
        if (sc.getHeartbeatTimeoutMs() > 0) b.heartbeatTimeoutMs(sc.getHeartbeatTimeoutMs());
        if (sc.getReconnectCapMs() > 0) b.reconnectCapMs(sc.getReconnectCapMs());
        return b.build();
    }

    /** AgentConfig.McpTransportType → McpServerConfig.Transport 转换。 */
    private McpServerConfig.Transport toRuntimeTransport(McpTransportType t) {
        if (t == null) return McpServerConfig.Transport.STDIO;   // 默认 stdio
        switch (t) {
            case STDIO:         return McpServerConfig.Transport.STDIO;
            case SSE:           return McpServerConfig.Transport.SSE;
            case STREAMABLE_HTTP: return McpServerConfig.Transport.STREAMABLE_HTTP;
            default: throw new IllegalArgumentException("Unknown MCP transport: " + t);
        }
    }
}
```

### 5. `ToolRegistry.unregister(String)` SPI 扩展(dsh §6.5 (2) L4538)

```java
// ToolRegistry.java —— 新增方法(向后兼容扩展,不影响现有 7 方法)

/**
 * 🆕 Story #021b — Unregister a previously-registered tool by name.
 *
 * <p>Used by {@code McpTransport#onConnectionStateChange} when a connection
 * goes {@link ConnectionState#DISCONNECTED} / {@link ConnectionState#FAILED}
 * — stale MCP tools must be removed so {@link ToolExecutor#dispatch} does not
 * route to a dead {@link McpToolAdapter}.
 *
 * <p><b>Symmetric contract</b> with {@link #register(Tool)}: a name registered
 * via {@code register} must be removable via {@code unregister} with the same key.
 *
 * <p><b>No-op semantics</b>: removing a name that was never registered (or already
 * removed) is a silent no-op (not an exception). This avoids forcing the MCP
 * state-machine listener to track prior registration state across reconnects.
 *
 * <p><b>Thread-safe</b> — implementations MUST support concurrent calls from
 * {@code McpTransport}'s listener thread + the Tool dispatch threads.
 *
 * <p><b>🆕 Story #021b — Skill dual-index:</b> if the unregistered tool was
 * also a {@link Skill}, the parallel skill index must be cleared in lock-step.
 *
 * @param name the tool's {@link Tool#name()} (with namespace, e.g. {@code "github:search_repos"})
 * @return {@code true} if a tool was removed; {@code false} if the name was not registered
 * @since 1.0.0
 */
boolean unregister(String name);
```

```java
// DefaultToolRegistry.java —— 新增方法

@Override
public boolean unregister(String name) {
    if (name == null) return false;
    Tool removed = registry.remove(name);
    if (removed == null) return false;
    // 🆕 Story #021b — Skill dual-index cleanup
    if (removed instanceof Skill) {
        skillsByName.remove(name);
    }
    LOG.info("Unregistered tool: name={} class={}", name, removed.getClass().getSimpleName());
    return true;
}
```

### 6. LINGS-M02 错误码复用 `#021a` `McpTransportException`

`#021a` 已落地 `McpTransportException extends RuntimeException`(码载体)。`#021b` **复用同一异常**,在 `McpToolAdapter.execute()` 捕获非预期 `Exception` 时:

```java
// McpTransportException 常量集中(放 #021b McpToolAdapter.java 同包)
// 也可考虑放 #021a McpTransportException.java 作内嵌常量,但 #021a 已合,
// #021b 通过静态 import 复用

public final class McpErrorCodes {
    private McpErrorCodes() {}
    public static final String LINGS_M01 = "LINGS-M01";   // MCP_CONNECT_FAILED (#021a)
    public static final String LINGS_M02 = "LINGS-M02";   // MCP_TOOL_CALL_FAILED (#021b)
}
```

---

## 反向 AC(明确不做)

| ❌ 不做 | Why |
|---|---|
| `McpToolAdapter` 加 `capabilities` 字段 | §6.5 (2) L4462-4493 文档无,`PermissionPolicy` 暂未消费 MCP 域,留 OQ-Future |
| Tool 命名空间方案:`serverName + ":" + toolName` vs `<serverName>_<toolName>` vs 全部 2 种 | 设计选 `:` —— 与 dsh §6.5 (2) L4538 `cfg.name + ":" + t.name()` 字面一致 |
| `McpTransport` 加 `connectionMetrics()`(心跳次数 / 断线次数)| 留给 §14 N1(OpenTelemetry),#021b 不引 OTel dep |
| `McpServerConfig` 加 builder.fromYaml() 静态工厂 | runtime 层与 YAML 层解耦保持,转换在 `McpTransportAutoConfiguration` 做 |
| MCP tool 调用加 circuit-breaker / retry | §14 N2 / N3,留给独立 Story |
| `ToolRegistry.unregister(Tool)` 重载 | 留 OQ-Future,McpTransport 当前已知 name,直接传 name 即可 |

---

## 反 pattern 拒止

- ❌ **`McpTransport` 直接 `@PostConstruct` 启动期 `connect`** — 引 `javax.annotation-api` 依赖,R-13 violation。用 `SmartLifecycle`(spring-context 已 transitive)。
- ❌ **`McpToolAdapter.execute()` 抛异常** — §4.10.1 硬规则 2 violation。ToolExecutor 5 步流水线必须包,异常只能转 `ToolResult.error`。
- ❌ **`ToolRegistry.unregister` 抛 `IllegalArgumentException` 当 name 不存在** — 强制 MCP listener 跟踪 prior registration state,违反 dsh L4536 注释「断了先把 tools 撤掉」的 idempotent 语义。改为 silent no-op。
- ❌ **`McpToolAdapter.name()` 用裸 tool name**(e.g. `search_repos`)| 可能与本地 Read/Write/Edit/Bash 撞名,必须 `serverName + ":" + toolName` 命名空间。
- ❌ **改写 `McpServerConnection`/`StdioMcpServerConnection`/`ConnectionState`/`McpServerConfig`/`McpCallResult`/`McpToolDescriptor`/`McpServerConnectionFactory`/`McpTransportException`/`McpTransportType`** — 这些 `#021a` 已合并,**0 行改动**(对接而非覆盖)。
- ❌ **`ToolRegistry.register`/`lookup`/`names`/`findByName`/`modelVisibleSpecs`/`findSkill`/`skillNames` 签名变更** — 仅**新增** `unregister(String)`,向后兼容。