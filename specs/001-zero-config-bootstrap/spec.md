# Story 001: zero-config-bootstrap(零配置启动 + 27 字段默认)

## 状态

- [x] Draft        (2026-09-20)
- [x] Specified    (2026-09-20)
- [ ] Planned
- [ ] Tasks Ready
- [ ] In Progress
- [ ] Validated
- [ ] Merged

## 来源

- **设计文档**:dsh_agent_design.md v1.5.34 §0.4 AC-01 / §1(12 项决策)/ §4.10.1(Spring AI 3 硬规则)/ §4.12(Core Runtime Types)/ §5.1—§5.5(SPI 体系 + 9 Slot)/ §6.1(LinearTurnEngine)/ §7.1(AgentFactory 生命周期 + 5 维度对比)/ §8.0 / §10.1(13 项依赖)/ §15 错误码 / §17 R-06/R-13
- **对应 AC**:**AC-01**(§0.4 L91-95 零配置启动)
- **对应风险**:**R-06**(JDK 8 vs Spring Boot 3.2.x JDK 17 矛盾)/ **R-13**(Spring AI 误用 transitive 污染)
- **涉及 ErrorCode**:**LINGS-C02**(CONFIG_VALIDATION_FAILED)/ **LINGS-C03**(CONFIG_TYPE_MISMATCH)/ **LINGS-S01**(SLOT_NOT_FOUND)/ **LINGS-S05**(SLOT_INIT_FAILED)/ **LINGS-Z01**(INTERNAL_PANIC)
- **SOP**:speckit_operator_prompt.md v1.18 §3.1 Story #001 + §8 Maven Skeleton Init(冷启动模板)

## 1. WHY(为什么做这个 Story)

LingShu 是 JDK 8+ Java Agent 引擎,**零配置启动**是其"宪法原则"——空 `application.yml` 必须能跑通整个 Agent turn 流程,所有 27 个配置字段都有出厂默认值。这是后续所有 Story 的**前置地基**:
- Story #003 LlmProvider 实现需要 Story #001 的 AgentFactory + 3 Router 容器
- Story #014 SessionStore 需要 Story #001 的 AgentFactory `create()` 启动校验链路
- Story #009 A2A AgentCard 需要 Story #001 的 AgentConfig 27 字段 Schema
- 所有 Story 的 AC 黑盒验证(`lingshu-examples/demo-empty`)都依赖 Story #001 的空 yml 跑通链路

**不做 Story #001,后续 15 个 Story 全部无法启动验证。**

## 2. WHO(谁会用到)

- **Alice(企业 AI 编码助手使用者)**:空 yml 一行启动 demo,验证 LingShu 在企业内 JDK 8 / 17 环境下能跑
- **Bob(业务配置方)**:用 `factory.defaultConfig()` + `withSession(...)` API 在自己 Spring Boot 服务里嵌入 LingShu Agent
- **Charlie(框架贡献者)**:把 9 个 Slot 的默认 Provider 接进来时,AgentFactory 的 7 项启动校验 + SlotRouter 同名竞争机制是骨架

**触发场景**:
1. Alice 拿到 LingShu 二进制 jar,执行 `java -jar lingshu-examples/demo-empty.jar`,期待 30 秒内看到 LLM 回复
2. Bob 在 Spring Boot service 里 `@Autowired AgentFactory factory`,调 `factory.create(cfg)`,期待 1 个调完就能用
3. Charlie 写新 Provider,期待只要 `@Component implements XxxProvider` 就能被 Spring 自动发现,不用改 AgentFactory

## 3. WHAT(交付什么 — 用户视角)

**用户能观察到的新行为**:
- `mvn -pl lingshu-examples/demo-empty package` 打 jar 后 `java -jar` 能起,30 秒内拿到首个 LLM 流式 token
- 空 yml(只有 `spring.application.name`)启动 stderr **零** ERROR 级日志
- 调 `factory.defaultConfig()` 拿到 `AgentConfig` 实例,27 字段全部有非 null 默认值
- 调 `factory.create(cfg)` 拿到 `Agent` 实例,`agent.run("hello")` 走完 ReAct Loop 一轮 turn

**用户能配置的新参数**:
- `application.yml` 任意字段(LLM provider / model / 沙箱白名单 / ReAct max steps 等)— **不写就走默认**
- 全部 27 个 AgentConfig 字段均可通过 YAML 覆盖

**用户会看到的错误码**:
- `LINGS-C02 CONFIG_VALIDATION_FAILED`(yml 缺必填字段,如 `agent.llm.provider` 缺失)
- `LINGS-S01 SLOT_NOT_FOUND`(yml 指定 `agent.flow-engine: my-engine` 但 classpath 没这个 Provider)
- `LINGS-Z01 INTERNAL_PANIC`(启动期不变量违反,如 AgentConfig.llm 为 null)

## 4. Acceptance Criteria(AC-01,黑盒可断言)

### AC-01-1 空 yml 启动 + 30s 首个 token

**Given** 一份空 `application.yml`(只有 `spring.application.name=lsh-empty` 一行)
**And** 环境变量 `ANTHROPIC_API_KEY` 已设置(从 Claude Code 配置文件读取)
**When** 执行 `time java -jar lingshu-examples/demo-empty-1.0.0.jar "你好,介绍下你自己"`
**Then** 进程在 30 秒内返回首个 LLM 流式 token 到 stdout
**And** stderr 输出零 ERROR 级日志(WARN / INFO 允许)
**And** turn 完成后进程正常退出(exit code 0)

### AC-01-2 27 字段默认值断言

**Given** 空 yml
**When** 调用 `factory.defaultConfig()` 拿到 `AgentConfig cfg`
**Then** 27 个字段**全部非 null**(可空集合用 `Collections.emptyList()` / `Collections.emptyMap()`):
1. `flowEngine = "linear"`
2. `llm = LlmConfig(provider="anthropic", model="claude-sonnet-4-5", maxTokens=4096, temperature=null)`
3. `prompt = Prompt(builder="default", memorySources=Collections.emptyList(), ragTopK=null)`
4. `toolExecutor = "default"`
5. `sandbox = Sandbox(policy="strict", runtime="noop", workingDirectory=Paths.get("."), commandWhitelist=[...], domainWhitelist=[])`
6. `compactor = "truncating"`
7. `sessionStore = "memory"`
8. `toolParallelism = 8`
9. `toolTimeoutSeconds = 30`
10. `approvalTimeoutSeconds = 0`
11. `turnTimeoutSeconds = 300`
12. `llmTimeoutSeconds = 60`
13. `reactMaxSteps = 50`
14. `identity = Identity.defaults()`(name="lingShu-agent", role=null, language="auto", traits=[], tone=null, avatar=null)
15. `instructions = Instructions.empty()`(file=null, inline=null, templateEngine="none", variables={})
16. `memory = Memory.defaults()`(claudeMd.enabled=true, project="./CLAUDE.md", user="~/.lingshu/CLAUDE.md", extras=[])
17. `delegate = null`(未配)
18. `mcp = null`(未配)
19. `skills = null`(未配)
20-27. 其他扩展字段(若 dsh §4.12.2 schema 增加,Story 实施期补全)

### AC-01-3 AgentFactory 7 项启动校验(fail-fast)

**Given** 任意 `AgentConfig cfg` (来自 `factory.defaultConfig()` 或 yml 加载)
**When** 调 `factory.create(cfg)`
**Then** AgentFactory 顺序校验 7 项(任一失败 → 抛对应 ErrorCode,JVM 退出 1):
1. `cfg != null`
2. `cfg.flowEngine != null && !empty`
3. `cfg.llm != null && llm.provider != null && llm.model != null`
4. `cfg.sandbox != null && sandbox.policy != null`
5. `cfg.toolExecutor != null && !empty`
6. `cfg.sessionStore != null && !empty`
7. `cfg.reactMaxSteps >= 1`

### AC-01-4 SlotResolver 启动日志(同名竞争 + 优先级)

**Given** classpath 含 `DefaultPermissionPolicyProvider`(name="strict", priority=10)+ `TrustlessPermissionPolicyProvider`(name="trustless", priority=10)
**When** Spring 启动
**Then** stderr 输出形如:
```
[PermissionPolicy] resolved 2 provider(s):
  ✓ strict     -> StrictPermissionPolicyProvider$1 [priority=10]
  ✓ trustless  -> TrustlessPermissionPolicyProvider$1 [priority=10]
```
**And** 同名 Provider(若有)按 priority 收敛,被覆盖者进入 conflict 日志

### AC-01-5 ReAct Loop 单轮跑通(empty tools)

**Given** yml `agent.tool.parallelism: 1` + 注册 0 个 tool(空 tool 列表)
**When** 跑一个 turn,prompt = "回答'你好'两字即可"
**Then** LinearTurnEngine 走完:
1. PromptBuilder.build → Prompt(1 system + 1 user)
2. LlmProvider.stream → LlmResponse(text="你好", toolCalls=[], stopReason=END_TURN)
3. 追加 Assistant 消息到 history
4. 触发 TurnCompleted(stopReason=END_TURN, usage)
5. SessionStore.save(checkpoint)
6. markDone()
**And** Agent.runBlocking 返回 RunResult(finalText="你好", stopReason=END_TURN)

### AC-01-6 maven validate + lingshu-core compile 通过

**Given** 仓根 + 5 子模块目录树就位
**When** 跑 `mvn validate -N && mvn -pl lingshu-core -am compile`
**Then** 两步都 exit code 0
**And** 编译产物在 `lingshu-core/target/classes/ai/lingshu/core/{api,spi,config,event}/`

> **🟡 R-13 mitigation (d) 强制项**
>
> - **AC-01-deps-1**:Given 引入 Spring AI 1.0.0-M6 BOM,When 跑 `mvn dependency:tree -pl lingshu-core -Dverbose`,Then 输出中**必须不包含** banned-dependencies 列表(见 §4)的任何条目;关键子树(`spring-ai-*` / `io.netty:*` / `com.fasterxml.jackson.*` 版本)贴到 PR body 末尾 `### R-13 dependency:tree 自查` 节。
> - **AC-01-deps-2**:Given Story 引入 Spring AI 横向依赖,When `mvn -pl lingshu-core verify` 跑 enforcer,Then `banned-dependencies` 规则**必须在 build 阶段 fail**(若配置未就位,`enforcer.skip=true` 显式跳过 + PR body 说明)。
> - **banned-dependencies 列表**(constitution §10 R-13 + dsh §17 R-13 同步):
>   - `org.springframework.ai:spring-ai-spring-boot-starter`(全家桶)
>   - `org.springframework.ai:spring-ai-vector-store-*`
>   - `org.springframework.ai:spring-ai-etl-*`
>   - `org.springframework.ai:spring-ai-unstructured-*`
>   - `com.knuddels:jtokkit`
>   - `io.netty:netty-all` 版本冲突对(锁定 4.1.106.Final)
>   - `com.fasterxml.jackson.*` 主版本号不一致(锁定 2.15.x 系列)

## 5. 反向 AC(明确不做什么)

- ❌ **不实现** LlmProvider 完整版(只做最小 Anthropic 一个,用于 AC-01-1 黑盒验证;OpenAI / Gemini / DeepSeek → Story #003)
- ❌ **不实现** Provider.version()(SlotProvider 接口本 Story 不加 version 字段,Story #003 再加)
- ❌ **不实现** OpenAi / Gemini / DeepSeek LlmProvider(→ Story #003)
- ❌ **不实现** Spring AI MCP server 连接(→ Story #009)
- ❌ **不实现** Skill 多源加载(→ Story #002)
- ❌ **不实现** SessionStore 4 后端(只做默认 memory,完整 4 后端 → Story #014)
- ❌ **不实现** Compactor 摘要版(只做 TruncatingCompactor stub,完整 → Story #015)
- ❌ **不引入** Spring AI `spring-ai-spring-boot-starter` 全家桶(→ R-13)
- ❌ **不跨版本** Spring AI 1.x → 2.x(→ constitution §7)
- ❌ **不实现** 子 Agent(DelegateTool → Story #006)
- ❌ **不实现** 多租户(→ Story #006)
- ❌ **不实现** YAML 热更(→ Story #007)
- ❌ **不实现** 性能压测(binary size < 35MB 验证在 PR body,完整压测 → Story #010)

## 6. 与其他 Story 的依赖

- **前置 Story**:无(冷启动 Story,所有后续 Story 的前置)
- **后续 Story**(本 Story 是其前置):
  - Story #002 identity-instructions-memory → 复用 AgentConfig.identity / instructions / memory 字段
  - Story #003 spi-slot-router → 在本 Story 3 Router 基础上加 LlmProviderRouter + 5 个 LlmProvider
  - Story #005 cancellation-token → 在本 Story LinearTurnEngine 基础上加 cancel 逻辑
  - Story #008 react-max-steps → 复用本 Story 的 reactMaxSteps 字段 + 验证
  - Story #009 a2a-agent-card → 在本 Story 9 Slot 接口骨架上加 Slot 9 实现
  - Story #014 session-store → 在本 Story SessionStore 接口 + memory 默认实现上加 4 后端
  - Story #015 prompt-cache → 在本 Story Compactor 接口 + truncating stub 上加 Summary 实现

---

## 附录 A:Story 涉及的关键接口契约(从 dsh 蒸馏)

### 9 个 Slot 接口(包 `ai.lingshu.core.api`)

```text
LlmProvider           // Slot 1,§4.10
Tool + ToolExecutor   // Slot 2,§4.6
Sandbox               // Slot 3,§4.7
Skill + SkillSource   // Slot 4,§4.6
SessionStore          // Slot 5,§4.8
Compactor             // Slot 6,§4.9
PromptBuilder         // Slot 7,§4.5
FlowEngine            // Slot 8,§4.11
A2aTransport          // Slot 9,§5.6
```

### 3 个 Router(本 Story 落地,包 `ai.lingshu.core.spi`)

```text
PermissionPolicyRouter     // Slot 3 子接口,@Autowired AgentFactory
ToolExecutorRouter         // Slot 2,@Autowired AgentFactory
FlowEngineRouter           // Slot 8,@Autowired AgentFactory
```

### 3 个默认 Provider(本 Story 落地)

```text
StrictPermissionPolicyProvider      // Slot 3,name="strict",priority=10
DefaultToolExecutorProvider          // Slot 2,name="default",priority=0
LinearTurnEngineProvider             // Slot 8,name="linear",priority=0
```

### 最小 Anthropic LlmProvider(本 Story 落地,Story #003 扩展)

```text
AnthropicLlmProvider                  // Slot 1,name="anthropic",priority=10
+ AnthropicLlmProviderAutoConfiguration // @Bean(name="llmProviderProvider_anthropic")
+ LlmProviderRouter                   // Slot 1,@Autowired AgentFactory
```

### AgentFactory 主类(本 Story 落地)

```text
AgentFactory                          // @Component,@Autowired 4 Router + 7 validate
DefaultAgent                          // §4.12.3,4 final 字段(config/session/engine/toolPool)
DefaultTurnContext                    // §4.12.1,AtomicBoolean done + synchronized history
```

### AgentConfig @Value 主类(本 Story 落地)

```text
AgentConfig                           // §4.12.2,27 字段 + Identity/Instructions/Memory 业务三件套
```

### demo-empty 示例(本 Story 落地)

```text
lingshu-examples/demo-empty/
├── pom.xml                            // 依赖 lingshu-core + spring-boot-starter
└── src/main/java/ai/lingshu/examples/demoempty/
    └── DemoEmptyApplication.java     // Spring Boot main,调 factory.create + agent.run
```

---

**Spec Author**:Claude Code(经用户 2026-09-20 会话指令蒸馏,基于 SOP v1.18 + dsh v1.5.34)
**Spec Date**:2026-09-20