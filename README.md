<div align="center">
  <img src="https://raw.githubusercontent.com/lingshu-ai-agent/lingshu/main/assets/lingshu_logo.svg" alt="LingShu" width="120"/>

  <h1>lingshu · 灵枢</h1>
  <p><strong>The Pivot of Agent Orchestration</strong></p>
  <p>Open-source Java Agent Engine for JDK 8+ · Spring Boot SPI · ReAct Loop · 8 Pluggable Slots</p>

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
- 🧩 **8 个 SPI 槽位** — PromptBuilder / LlmProvider / ToolExecutor / PermissionPolicy / RuntimeSandbox / SessionStore / Compactor / **FlowEngine** —— 全部一行 SPI 替换
- 🔁 **ReAct Loop 一等公民** — 默认 `LinearTurnEngine`,显式 step 计数 + ReasoningStarted / ObservationAppended / MaxStepsExceeded 三类事件
- 🔌 **MCP 客户端内置** — 通过 `McpToolAdapter` 把任意 MCP server 当 Tool 源
- 📜 **Skill = Tool 标记接口** — `SKILL.md` 解析 → 自动注册为 Tool,classpath + 目录双源
- 🛡️ **双层沙箱** — `PermissionPolicy`(模型层)+ `RuntimeSandbox`(系统层,chroot/seccomp/sysbox)
- 🔄 **FlowEngine 可替换** — `LinearTurnEngine` 默认,`GoogleAdkFlowEngine` / `AlibabaGraphFlowEngine` / 自研 DAG 可平替
- 🪶 **Lombok 友好** — `@Value` 不可变风格,拒绝过度抽象
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
            │                 Tool / Skill Pool                 │
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
```

详见 [docs/concepts/slots.md](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/slots.md)。

---

## 🔌 SPI 替换示例

```java
// 1. 替换 FlowEngine:接入 Google ADK
@AutoService(FlowEngineProvider.class)
public class GoogleAdkFlowEngineProvider implements FlowEngineProvider {
    @Override public String name() { return "google-adk"; }
    @Override public int priority() { return 100; }
    @Override public FlowEngine create(EngineContext ctx) { return new GoogleAdkFlowEngine(ctx); }
}

// 2. 替换 LlmProvider:接入 OpenAI
@AutoService(LlmProviderFactory.class)
public class OpenAiLlmFactory implements LlmProviderFactory {
    @Override public String name() { return "openai"; }
    @Override public LlmProvider create(LlmConfig cfg) { return new OpenAiLlmProvider(cfg); }
}

// 3. 加自定义 Tool
@AgentTool(name = "db_query", description = "Execute read-only SQL")
public List<Map<String, Object>> dbQuery(String sql) {
    // 你的实现
}
```

只要把以上类打进 jar,放到 classpath,引擎自动加载,**零配置**。

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

```bash
git clone https://github.com/lingshu-ai-agent/lingshu.git
cd lingshu
mvn -pl lingshu-examples/demo-fibonacci -am exec:java \
    -Dexec.mainClass=ai.lingshu.examples.fibonacci.DemoFibonacciApp
```

看到 `Final: public static long fib(int n) {...}` 即成功。

---

## 📚 文档

完整文档见 [lingshu-ai-agent/lingshu-docs](https://github.com/lingshu-ai-agent/lingshu-docs):

- 📘 [30s 入门](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/intro.md)
- 🧠 [ReAct Loop 概念](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/react-loop.md)
- 🔌 [SPI 扩展指南](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/spi.md)
- 🛡️ [Sandbox 与安全](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/concepts/sandbox.md)
- 🏭 [生产部署](https://github.com/lingshu-ai-agent/lingshu-docs/blob/main/docs/ops/deployment.md)

设计文档:`dsh_agent_design.md`(v1.5.3)

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