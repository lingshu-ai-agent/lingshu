<div align="center">
  <img src="https://raw.githubusercontent.com/lingshu-ai-agent/lingshu/main/assets/lingshu_logo.svg" alt="LingShu" width="120"/>

  <h1>lingshu · 灵枢</h1>
  <p><strong>The Pivot of Agent Orchestration</strong></p>
  <p>Open-source Java Agent Engine for JDK 8+ · Spring Boot SPI · ReAct Loop · 9 Pluggable Slots</p>

  <p>
    <a href="https://github.com/lingshu-ai-agent/lingshu/stargazers"><img src="https://img.shields.io/github/stars/lingshu-ai-agent/lingshu?style=for-the-badge" alt="stars"/></a>
    <a href="https://github.com/lingshu-ai-agent/lingshu/network/members"><img src="https://img.shields.io/github/forks/lingshu-ai-agent/lingshu?style=for-the-badge" alt="forks"/></a>
    <a href="https://github.com/lingshu-ai-agent/lingshu/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-Apache_2.0-blue?style=for-the-badge" alt="license"/></a>
    <a href="https://github.com/lingshu-ai-agent/lingshu/issues"><img src="https://img.shields.io/github/issues/lingshu-ai-agent/lingshu?style=for-the-badge" alt="issues"/></a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/java-8+-D97706?style=for-the-badge&logo=openjdk&logoColor=white" alt="java"/>
    <img src="https://img.shields.io/badge/spring--boot-2.7%2B-6DB33F?style=for-the-badge&logo=springboot&logoColor=white" alt="spring"/>
    <img src="https://img.shields.io/badge/maven-3.6%2B-C71A36?style=for-the-badge&logo=apachemaven&logoColor=white" alt="maven"/>
    <img src="https://img.shields.io/badge/license-Apache_2.0-blue?style=for-the-badge" alt="license"/>
  </p>
</div>

---

## 灵枢 · The Pivot

**灵枢**(`líng shū`,意为"针灸的关键枢轴")是 LingShu 引擎的核心仓库 —— 一个为 **JDK 8+** 企业 Java 栈设计的、生产级 **ReAct Loop Agent Engine**。

> 名字的由来:**Agent 的本质是一个循环**(ReAct),而循环需要一个**枢轴**才能转得稳。
> 我们把这个"枢轴"叫做 LingShu。

> 🟢 **We're an Engine, not an OS.**
> [OryxOS](https://github.com/oryx-labs/oryxos) 等项目定位是"Distributed Agent OS"——单 JAR 部署、跨节点协调、Java 21 + virtual threads。
> LingShu 不跟他们抢这条赛道:LingShu 是一个**嵌进你 Spring Boot 进程的 Engine**,
> 不需要为 Agent 单独搭集群、不需要把 JDK 升到 21、不需要新运维模型。
> 如果你的团队还在 JDK 8 LTS 上、并且你的服务已经跑在 Spring Boot 里 —— LingShu 是默认选项。

---

## ✨ 核心特性

- 🚀 **JDK 8 优先** — 不用 `var` / sealed / records / `List.of` / pattern-switch,主流 JDK 8 LTS 系统直接跑
- 🧩 **9 个 SPI 槽位** — PromptBuilder / LlmProvider / ToolExecutor / PermissionPolicy / RuntimeSandbox / SessionStore / Compactor / **FlowEngine** / **A2aTransport** —— 全部 Spring `@Component` + `@AutoConfiguration` 注册,Provider 按 `name()` 路由,SlotRouter 启动期校验 `version()` 兼容性
- 🔁 **ReAct Loop 一等公民** — 默认 `LinearTurnEngine`,显式 step 计数 + ReasoningStarted / ObservationAppended / MaxStepsExceeded 三类事件
- ⚡ **并行工具调度** — `LinearTurnEngine.dispatchParallel` Semaphore-bounded `CompletableFuture` 池,`config.tool.parallelism` 控制并发度,结果按 LLM-return 顺序回填
- 🔌 **MCP 客户端内置** — 通过 `McpToolAdapter` 把任意 MCP server 当 Tool 源
- 📜 **Skill = Tool 标记接口** — `SKILL.md` 解析 → 自动注册为 Tool,classpath + 目录双源
- 🛡️ **双层沙箱** — `PermissionPolicy`(模型层)+ `RuntimeSandbox`(系统层,chroot/seccomp/sysbox)
- 🔄 **FlowEngine 可替换** — `LinearTurnEngine` 默认,`GoogleAdkFlowEngine` / `AlibabaGraphFlowEngine` / 自研 DAG 可平替
- 🪶 **Lombok 友好** — `@Value` 不可变风格,拒绝过度抽象
- 🔁 **YAML 热更无中断** — `AgentConfigRegistry` `AtomicReference` 单写多读 + `Files.getLastModifiedTime` 5s poll + `DefaultAgent.run()` 入口一次性 freeze,旧 turn 冻结 cfg 引用语义自然隔离(Story #007)
- 🌐 **A2A-ready (roadmap)** — Agent-to-Agent 协议对齐 v0.5,跟 [OryxOS](https://github.com/oryx-labs/oryxos) 的"三件套"对齐

---

## ⚡ 30 秒上手

### Maven

```xml
<dependency>
    <groupId>ai.lingshu</groupId>
    <artifactId>lingshu-core</artifactId>
    <version>0.1.0-alpha</version>
</dependency>
```

### 第一个 Agent

```java
import ai.lingshu.core.*;
import ai.lingshu.core.engine.*;
import ai.lingshu.core.provider.anthropic.*;

@SpringBootApplication
public class MyFirstAgent {
    public static void main(String[] args) {
        SpringApplication.run(MyFirstAgent.class, args);
    }

    @Bean
    CommandLineRunner run(AgentFactory factory) {
        return args -> {
            Agent agent = factory.create(AgentConfig.builder()
                .flowEngine("linear")
                .llm(LlmConfig.builder()
                    .provider("anthropic")
                    .model("claude-sonnet-4-5")
                    .build())
                .skills(SkillSources.of(
                    ClasspathSource.of("classpath:skills/agent-builtin/"),
                    DirectorySource.of("./skills/")
                ))
                .build());

            RunResult r = agent.runBlocking("用 Java 写一个 Fibonacci 函数");
            System.out.println(r.getFinalText());
        };
    }
}
```

### YAML 配置(可选)

```yaml
agent:
  flow-engine: linear        # linear | google-adk | alibaba-graph | dag
  llm:
    provider: anthropic      # anthropic | openai | gemini | ollama
    model: claude-sonnet-4-5
    api-key: ${ANTHROPIC_API_KEY}
  sandbox:
    policy: strict           # strict | permissive
    runtime: chroot          # chroot | sysbox | seccomp | none
  max-steps: 25
  skills:
    sources:
      - { type: classpath, location: classpath:skills/agent-builtin/ }
      - { type: directory, location: ./skills/ }
      - { type: git,      location: https://github.com/lingshu-ai-agent/lingshu-skill-market }
```

---

## 🏛️ 架构:8 个 SPI 槽位

```
                            ┌──────────────────────────────────┐
                            │         FlowEngine (SPI)          │
                            │ LinearTurnEngine | Google ADK | … │
                            └─────────┬──────────────┬───────────┘
                                      │ drives         │ emits events
            ┌─────────────────────────▼────┐  ┌───────▼───────────┐
            │   PromptBuilder  (SPI)       │  │   AgentEvent bus    │
            │   LlmProvider    (SPI)       │  │  (Reactive Streams)│
            └──────────────────────────────┘  └─────────────────────┘
                                      │
            ┌───────────────────────────▼──────────────────────┐
            │              ToolExecutor (SPI, Story #004)       │
            │  ┌────────────────────────────────────────────┐  │
            │  │   PermissionPolicy → ToolRegistry → Execute │  │
            │  │   dispatchParallel: Semaphore(parallelism) │  │
            │  │   CompletableFuture 池 → 结果按 LLM 顺序回填  │  │
            │  └────────────────────────────────────────────┘  │
            │  Tool SPI  →  McpToolAdapter  →  SkillTool adapter │
            │  Spring AI @AgentTool annotation → SkillTool       │
            └────────────┬───────────────────┬──────────────────┘
                         │ permission        │ runtime
                  ┌──────▼─────────┐  ┌──────▼──────────┐
                  │ PermissionPolicy│  │ RuntimeSandbox  │
                  │   (SPI, model)  │  │  (SPI, system)  │
                  └────────────────┘  └─────────────────┘

            ┌────────────────────────────────────────────────────┐
            │     SessionStore (SPI)  +  Compactor (SPI)         │
            │     持久化历史 / 滑动窗口 / 摘要压缩                │
            └────────────────────────────────────────────────────┘

            ┌────────────────────────────────────────────────────┐
            │     A2aTransport (SPI, Story #009 roadmap)          │
            │     Agent-to-Agent RPC + AgentCard discovery        │
            └────────────────────────────────────────────────────┘
```

每个 SPI 槽位的 `SlotRouter` 在启动期按 `Provider.version()` 校验 Slot 契约兼容性(Story #003),MAJOR 不匹配抛 `LINGS-S05` 启动失败 —— **杜绝运行时静默降级**。详见 [docs/concepts/slots.md](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/slots.md) 与 [docs/concepts/spi-versioning.md](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi-versioning.md)。

---

## 🔌 SPI 替换示例

> **Story #003 重要更新**:LingShu 选择 **Spring Boot SPI** 而非 Java SPI / OSGi / ClassLoader 隔离(详见 [docs/concepts/spi-vs-osgi.md](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi-vs-osgi.md))。
> 所有 Provider 通过 `@Component` 或 `@AutoConfiguration` + `@Bean` 注册,**禁止**使用 Google auto-service 的 `@AutoService` 注解(那是 Java SPI,与我们的决策冲突)。

```java
// 1. 替换 FlowEngine:接入 Google ADK(Story #003 校验 version() 兼容性)
@Component
public class GoogleAdkFlowEngineProvider implements FlowEngineProvider {
    @Override public String name() { return "google-adk"; }
    @Override public int priority() { return 100; }
    @Override public String version() { return "1.0.0"; }   // 必须与 FlowEngine 契约版本 MAJOR 一致
    @Override public FlowEngine create(AgentConfig cfg) { return new GoogleAdkFlowEngine(cfg); }
}

// 2. 替换 LlmProvider:接入 OpenAI
@Component
public class OpenAiLlmProviderProvider implements LlmProviderProvider {
    @Override public String name() { return "openai"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }
    @Override public LlmProvider create(AgentConfig cfg) {
        return new OpenAiLlmProvider(cfg.getLlm());
    }
}

// 3. 加自定义 Tool(不需要 Provider,直接 @Component)
@Component
public class DbQueryTool implements Tool {
    @Override public String name() { return "db_query"; }
    @Override public String description() { return "Execute read-only SQL"; }
    @Override public JsonNode inputSchema() { /* JSON Schema */ }
    @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        // 你的实现 —— 异常会被 DefaultToolExecutor 翻译成 ToolResult.error,
        // 不会让引擎循环崩溃(Story #004 / FR-007/FR-008)
    }
}
```

**YAML 切换 Provider**(Story #003 多 Provider 模式,改 yaml 不改代码):

```yaml
agent:
  llm:
    provider: openai        # 从 anthropic 切换到 openai,无需 exclude classpath
    model: gpt-4o
  flow-engine: google-adk   # 切到 ADK
  sandbox:
    policy: strict
```

只要把以上类打进 jar,放到 classpath,Spring 启动时 SlotRouter 按 `name()` 路由 + `version()` 校验,**零配置**。

---

## 📦 模块结构

```
lingshu/
├── lingshu-core/                 ← 核心 API + ReAct 引擎
│   ├── engine/                  ← LinearTurnEngine
│   ├── loop/                    ← ReAct step state machine
│   ├── prompt/                  ← PromptBuilder
│   ├── llm/                     ← LlmProvider SPI
│   ├── tool/                    ← Tool SPI + McpToolAdapter
│   ├── skill/                   ← Skill SPI + SkillTool adapter
│   ├── sandbox/                 ← PermissionPolicy + RuntimeSandbox
│   ├── session/                 ← SessionStore + Compactor
│   ├── event/                   ← AgentEvent types
│   └── flow/                    ← FlowEngine SPI
├── lingshu-boot-starter/         ← Spring Boot 启动器
├── lingshu-providers/
│   ├── lingshu-anthropic/        ← Anthropic Claude
│   ├── lingshu-openai/           ← OpenAI / GPT
│   ├── lingshu-ollama/           ← 本地 Ollama
│   └── lingshu-mcp-client/       ← MCP server 适配
├── lingshu-adapters/
│   ├── lingshu-google-adk/       ← 接入 Google ADK 作为 FlowEngine
│   └── lingshu-alibaba-graph/   ← 接入 Alibaba Graph 作为 FlowEngine
└── lingshu-bom/                  ← Maven BOM
```

---

## 🧪 跑通一个最小 Demo

### Story #001 zero-config-bootstrap(空 yml 启动 + 首 token)

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
export ANTHROPIC_AUTH_TOKEN=<your-key>
export ANTHROPIC_BASE_URL=https://api.anthropic.com        # 或代理路径如 https://your-proxy/anthropic
mvn -pl lingshu-examples/demo-empty -am spring-boot:run
```

`application.yml` 故意为空,所有 27 个 `AgentConfig` 字段由 `AgentConfigDefaults.defaults()` 提供。
首次 LLM token 在 ~5s 内返回,stderr 无 ERROR(AC-01-1)。

### Story #002 identity-instructions-memory(业务三件套 + 5 段 Prompt 装配)

```bash
cd lingshu
export ANTHROPIC_AUTH_TOKEN=<your-key>
export ANTHROPIC_BASE_URL=https://api.anthropic.com        # 或代理路径
mvn -pl lingshu-examples/demo-engineer -am package -DskipTests
java -jar lingshu-examples/demo-engineer/target/demo-engineer-0.1.0-SNAPSHOT.jar "你是做什么的"
```

`demo-engineer` 演示 AC-09 黑盒契约:
- 4 个 `MemorySourceProvider` 自动注册(`identity` / `project-claude-md` / `user-claude-md` / `project-tree`)
- `DefaultPromptBuilder` 5 段装配:`[ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY] / [CONVERSATION HISTORY] / [USER MESSAGE]`
- 缺失文件静默跳过,首 token ≤ 30s

**黑盒测试(无需 API key)**:
```bash
mvn -pl lingshu-examples/demo-engineer -am test -Dtest=BlackBoxVerificationTest
```

### Story #003 spi-slot-router(`Provider.version()` + `SlotRouter` 兼容性校验)

```bash
mvn -pl lingshu-core test -Dtest=SlotRouterCompatTest
mvn -pl lingshu-core test -Dtest=VersionTest
mvn -pl lingshu-core test -Dtest=ProviderInitExceptionTest
```

`SlotRouterCompatTest`(7 case)+ `VersionTest`(24 case)+ `ProviderInitExceptionTest`(5 case)覆盖:
- 启动期按 `Provider.version()` 与 Slot `CONTRACT_VERSION` MAJOR 比对 —— 不匹配抛 `LINGS-S05` **启动失败**,杜绝运行时静默降级(AC-02)
- `LinkedHashMap` 保留用户配置顺序(同 `agent.prompt.memory-sources` 输入一致),priority 只用于同名竞争(AC-08)

### Story #004 tool-parallel-dispatch(`LinearTurnEngine.dispatchParallel`)

```bash
mvn -pl lingshu-core test -Dtest=LinearTurnEngineParallelDispatchTest
```

`LinearTurnEngineParallelDispatchTest` 跑 AC-03 黑盒契约(无需 API key):
- 4 个独立 Tool 各 sleep 1s,`tool.parallelism: 4` → wall-clock **≤ 1.3s**(实测 1011ms)
- 相比 4s 串行 baseline,加速比 **≥ 3.0×**(实测 3.96×)
- `DefaultToolExecutor` 把异常翻译成 `ToolResult.error`,引擎循环不因单 Tool 崩溃(FR-007/FR-008)
- 结果按 LLM-return 顺序回填 history,不按完成顺序(FR-003)

**YAML 调并行度**:
```yaml
agent:
  tool:
    parallelism: 8          # 同时执行最多 8 个 Tool;1 = 串行;0 = 不限
    timeout-seconds: 30     # 单个 Tool 超时(0 = 不超时)
```

### Story #005 cancellation-token(协作式取消 + 三层贯通 + AC-04 200ms)

dsh §14.12 N12: FlowEngine / ToolExecutor / LlmProvider 三层共用同一个 `CancellationToken`,Ctrl-C / JVM shutdown hook / turn 超时 / 编程式 `markDone` 4 种触发源都通过它发出信号。

**关键不变量**:
- `TurnContext.cancellation() == ToolExecutionContext.cancellation()`(共享引用,非 equals)
- `DefaultTurnContext.createWithBroadcast` 自动把 turn 的 token 注册到 `AgentFactory.BROADCAST_REGISTRY`
- `AgentFactory.@PostConstruct registerJvmShutdownHook` 在 JVM 关停时调 `broadcastCancel()`,所有 in-flight turns 在 AC-04 200ms 内退出
- `LinearTurnEngine` 用 200ms 轮询预算(`waitForLlm` + `waitForTool`),即使 LLM / Tool 永远不返回也能在 200ms 内感知取消

```bash
mvn -pl lingshu-core test -Dtest='CancellationTokensTest,CancellationTokenSharingTest,LinearTurnEngineCancellationIT,AgentFactoryBroadcastCancelTest'
```

**测试覆盖**(23 case / 4 类):
- `CancellationTokensTest`(8 case)— AtomicBoolean 幂等 fire / CopyOnWriteArrayList 安全迭代 / per-callback 异常隔离 / 并发注册 stress
- `CancellationTokenSharingTest`(6 case)— `==` 身份共享 / 多 turn 隔离 / 4-arg 构造器 back-compat
- `LinearTurnEngineCancellationIT`(3 case)— **AC-04 黑盒**(实测 cancel→exit **0ms**,预算 200ms)/ 预取消 / mid-tool-dispatch 取消
- `AgentFactoryBroadcastCancelTest`(6 case)— broadcast 全发 / 幂等 / `activeTurnCount` 反射 / 未注册 turn 忽略

**AC-04 黑盒输出**:
```
[AC-04] cancel→exit elapsedMs=0 (budget=200)
```

**LlmProvider 取消内部轮询**(US3)推迟到 Story #005b — 不阻塞 AC-04:200ms `waitForLlm` 预算 + 引擎侧轮询已覆盖 N12 三层贯通契约。

### Story #006 multi-tenant(`TenantContext` ThreadLocal + 配置/Session/Sandbox/Cost 四维隔离 AC-05)

dsh §14.9 N9:多租户隔离是 B2B SaaS 化刚需 —— 一个 JVM 实例同时服务多个客户,每客户有独立 memory dir / sandbox whitelist / cost budget / session namespace,互不可见。

**核心交付**:
- `TenantContext` ThreadLocal **嵌套栈** + `snapshot/runWithSnapshot` 显式跨线程传递(主动放弃 `InheritableThreadLocal`,避免线程池复用场景下"上一个任务的 tenant 泄漏到下一个任务")
- `AgentConfig.tenants` 新字段(28th)+ `TenantsConfig.validate()` 启动期 fail-fast(错误码 `LINGS-C02`)
- `TenantConfigProvider` SPI + `YamlTenantConfigProvider` 默认实现(`@Component("tenantConfigProvider_yaml")`,§5.28 多 Provider 模式)
- `TenantAwareCostTracker` per-tenant `LongAdder` 桶 + 超预算 `CostBudgetExceededException`
- `DefaultRuntimeSandbox` `process.run` 走 tenant 白名单(全局兜底,单租户 mode 不变)
- `DefaultInMemorySessionStore` session key 加 `tenantId` 前缀(`alice:sess-123` vs `bob:sess-123`)
- `LinearTurnEngine.runTurn` 入口 FR-011 守卫(tenants 已配但无 `TenantContext` → `IllegalStateException` + `LINGS-C02` 提示)

```bash
mvn -pl lingshu-core test -Dtest='TenantContextTest,TenantConfigProviderTest,TenantConfigValidationTest,MemoryPathIsolationTest,CostBudgetIsolationTest,SandboxWhitelistIsolationTest,SessionKeyIsolationTest,TenantIsolationIT'
```

**测试覆盖**(43 case / 8 文件):
- `TenantContextTest`(9 case)— 嵌套栈 / try-finally / snapshot+runWithSnapshot / 跨线程显式传递
- `TenantConfigProviderTest`(5 case)— 多 Provider 优先级 / 空 yml 单租户 fallback
- `TenantConfigValidationTest`(10 case)— 4 项校验(tenantId 格式 / key=value 一致 / dir 必填 / cost>0)+ 14 个 Edge Case
- `MemoryPathIsolationTest`(2 case)— per-tenant `AgentConfig.Memory.claudeMd.project`
- `CostBudgetIsolationTest`(5 case)— alice/bob 独立计数 + 超预算 fail-fast
- `SandboxWhitelistIsolationTest`(6 case)— alice 拒 git / bob 允许 / 单租户 fallback
- `SessionKeyIsolationTest`(5 case)— 同 sessionId 不同物理 bucket
- `TenantIsolationIT`(1 case E2E)— **AC-05 黑盒**(83ms):4 维隔离跨 alice/bob 一次性验证

**YAML 多租户配置**:
```yaml
agent:
  tenants:
    enabled: true
    map:
      alice:
        memory:    { dir: /var/lib/alice }
        sandbox:   { command-whitelist: [ls, cat] }
        cost:      { session-budget-micros: 10000000 }
      bob:
        memory:    { dir: /var/lib/bob }
        sandbox:   { command-whitelist: [ls, cat, git] }
        cost:      { session-budget-micros: 100000000 }
```

**TenantContext 用法**:
```java
TenantContext.runAs("alice", () -> {
    // 业务代码 —— TenantContext.current() == "alice"
    Agent agent = factory.create(cfg);
    return agent.runBlocking("...");
});
// 退出 lambda 后自动 clear(R-02 缓解)

// 跨线程显式传递
String snap = TenantContext.snapshot();
executor.submit(() -> {
    TenantContext.runWithSnapshot(snap, () -> doWork());
});
```

**R-13 dependency:tree 自查**:`diff /tmp/deps-005-baseline.txt /tmp/deps-006-after.txt` → 仅 `[INFO] Total time` 时间戳差异,**0 新依赖**。

### Story #007 yaml-hot-reload(`AgentConfigRegistry` AtomicReference + 5s mtime poll + DefaultAgent freeze AC-06)

dsh §14.8 N8:**YAML 热更无中断** —— Agent 跑 turn T1 时外部修改 `application.yml`(扩 sandbox whitelist / 换 model / 调 `react.max-steps`),T1 全程冻结旧 cfg 引用语义自然隔离;T2 启动立即看到新 cfg。零停机 + 零重启 + 零数据竞争 —— 7×24 长生命周期运维刚需。

**核心交付**:
- `AgentConfigRegistry` `AtomicReference<AgentConfig>` **单写多读 lock-free**(NFR-005:单 publish ≤ 1ms)
- `ConfigChangeListener` SPI + listener 异常不阻断 publish 主流程(异常隔离 + ERROR 日志 + 后续 reader 仍看到新 cfg)
- `YamlWatcher` daemon `ScheduledExecutorService` 5s `Files.getLastModifiedTime` poll(cross-platform stable,**不用** `WatchService` 的 macOS polling fallback 不兼容)
- `AgentFactory.create(cfg, registry)` 新签名 + 旧 `create(cfg)` `@Deprecated`(向后兼容 Story #001—#006)
- `DefaultAgent.run()` 入口一次性 `registry.current()` freeze(Java 引用语义 + `@Value` immutable 字段自然冻结,无锁 / 无 snapshot copy)
- `validateOrThrow` 拒绝破坏性 cfg + rollback 保留旧 cfg + `lastSeen` **不**更新 → 下次 5s 自动重试

**关键不变量**:
- 旧 turn T1 全程持有 cfg1 引用(`assertSame` 验证),即使中途 `registry.publish(cfg2)` 也无影响
- 新 turn T2 入口 `registry.current()` 立即看到 cfg2,无需重启 / cancel / 等待
- 校验失败 / YAML parse 失败 / IOException **不**更新 `lastSeen`,下一次 poll 自动重试(R-03 缓解)
- Listener 抛 RuntimeException → ERROR log + publish **不**回滚,后续 reader 仍看到新 cfg
- Listener 内禁止调 `registry.publish`(重入死循环,契约显式说明)

**测试覆盖**(14 case / 4 文件):
- `AgentConfigRegistryTest`(7 case)— publish 立即 swap / 100 线程并发 `current()` 全看到新 cfg / listener 异常隔离 / listener 重入禁止 / addListener / removeListener
- `YamlWatcherTest`(5 case)— mtime 变更触发 reload / invalid YAML 保留旧 cfg / validate 失败保留旧 cfg / `lastSeen` 不更新 / 自动重试
- `InFlightFreezeTest`(1 case)— **AC-06 核心**(T1 freeze + T2 立即生效)
- `YamlHotReloadIT`(1 case E2E)— **AC-06 黑盒主路径**(T1 跑 ls + 中途 touch yml 加 git + T2 跑 git status)

```bash
mvn -pl lingshu-core test -Dtest='AgentConfigRegistryTest,YamlWatcherTest,InFlightFreezeTest,YamlHotReloadIT'
```

**ConfigChangeListener 用法**:
```java
@Component
public class MyAuditListener implements ConfigChangeListener {
    @Override
    public void onConfigChange(AgentConfig prev, AgentConfig next) {
        // prev → next 的 diff 审计 / metric 计数 / cache invalidate
        // 不要在 listener 内调 registry.publish(重入死循环,契约禁止)
    }
}
```

**YAML 热更示例**:
```bash
# T1 在跑,sandbox whitelist = [ls, cat]
# 外部运维修改 yml:
vi application.yml    # 追加 git 到 whitelist
:wq
# 5s 内 YamlWatcher poll 检测 mtime 变化 → reload + validate + publish(cfg2)
# T1 全程冻结 cfg1 引用(sandbox 仍只允许 ls / cat,不被中断)
# T2 启动立即看到 cfg2(sandbox 允许 ls / cat / git)
```

**R-13 dependency:tree 自查**:`diff /tmp/deps-006-baseline.txt /tmp/deps-007-after.txt` → **0 new dependencies**(`AtomicReference` / `ScheduledExecutorService` / `Files` / SnakeYAML 已在 baseline)。

**0 新增 ErrorCode**(沿用 Story #001 `LINGS-C02` 验证错误码;R-03 缓解在 `validateOrThrow` 已有路径)。

---

## 📚 文档

完整文档见 [lingshu-ai-agent/lingshu-docs](https://github.com/lingshu-ai-agent/lingshu-docs):

- 📘 [30s 入门](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/intro.md)
- 🧠 [ReAct Loop 概念](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/react-loop.md)
- 🔌 [SPI 扩展指南](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi.md)
- 🔄 [SPI 版本兼容与 SlotRouter(Story #003)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi-versioning.md)
- ⚡ [并行 Tool 调度与并发配置(Story #004)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/parallel-tools.md)
- ⏹️ [协作式取消与三层贯通(Story #005)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/cancellation.md)
- 🛡️ [Sandbox 与安全](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/sandbox.md)
- 👥 [多租户隔离与 TenantContext(Story #006)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/multi-tenant.md)
- 🔁 [YAML 热更与 in-flight freeze(Story #007)](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/yaml-hot-reload.md)
- 🏭 [生产部署](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/ops/deployment.md)

设计文档:`dsh_agent_design.md`(v1.5.34)

---

## 🤝 参与贡献

- 🐛 [提交 Issue](https://github.com/lingshu-ai-agent/lingshu/issues/new?template=bug_report.yml)
- 💡 [提特性建议](https://github.com/lingshu-ai-agent/lingshu/issues/new?template=feature_request.yml)
- 🔧 [Pull Request 流程](https://github.com/lingshu-ai-agent/.github/blob/main/CONTRIBUTING.md)
- 🛡️ [安全漏洞上报](https://github.com/lingshu-ai-agent/.github/blob/main/SECURITY.md)

---

## 📜 License

Apache 2.0 — see [LICENSE](LICENSE).

---

<sub align="center">Built with 🪷 by the LingShu community · Apache 2.0 · JDK 8+</sub>