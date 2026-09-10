# CLAUDE.md — 灵枢 LingShu 项目 Claude Code 引导

> 给 Claude Code 的项目级上下文。**每个新 Claude 会话 turn 1 自动加载。**
>
> **配套文档**(按需加载,不重复):
> - 设计文档:[`dsh_agent_design.md`](./dsh_agent_design.md) v1.5.9 / 4768+ 行 / 项目真理(v1.5.9 增 §4.5.1 [TOOL SCHEMAS] 段)
> - SpecKit SOP:[`speckit_operator_prompt.md`](./speckit_operator_prompt.md) v1.3 / 859 行 / 操作手册(R-13 mitigation (d) 镜像)
> - Prompt 速查:[`lingshu_spec_prompts.md`](./lingshu_spec_prompts.md) v1.0.1 / 368 行 / 新窗口 Prompt 模板
> - SKILL:`~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` v1.0.2 / 192 行 / 自动触发(R-13 dep-tree 自查链路已纳入)

---

## 1. 项目身份

- **名称**:灵枢 LingShu Agent Engine(代号 DSH Agent)
- **定位**:JDK 8+ Java Agent 引擎,Spring Boot SPI,ReAct Loop,**9 个可插拔 Slot**
- **版本**:v0.1.0-SNAPSHOT(开发中,设计文档锁定 v1.5.6,见 §13)
- **设计灵感**:Apache DSH / Dubbo SPI 风格
- **目标用户**:企业内 AI 编码助手 / 业务配置方(只写 YAML) / 框架贡献者

---

## 2. 技术栈锁定(JDK 8 only)

| 项 | 版本 / 说明 |
|---|---|
| **编译目标** | Java 1.8(`<source>1.8</source>`)|
| **运行 JRE** | JDK 8 / 11 / 17 / 21 LTS(Spring Boot 3.2.5 实际跑需 JDK 17+)|
| **JVM 厂商** | Temurin / Zulu / Alibaba Dragonwell / IBM Semeru |
| **Spring Boot** | 3.2.5(BOM 引入)|
| **Spring AI** | `1.0.0-M6`(BOM 引入,v1.5.7 起)— **只**用于 LLM 协议转换 + `@Tool` Schema 生成,见 §11 #8 |
| **Lombok** | 1.18.30(配置类**全部** `@Value` 不可变风格)|
| **OpenTelemetry** | 1.32.x(**不跨 1.x → 2.x**,API 不兼容)|
| **Reactive Streams** | `org.reactivestreams:reactive-streams:1.0.4`(JDK 8 没内置)|
| **构建** | Maven 3.6.3+ 多模块(父 POM + 5 子模块)|
| **CI** | GitHub Actions matrix:ubuntu + JDK 8 / 17 / 21 |

---

## 3. JDK 8 硬约束(避坑清单)

- ❌ `record`(JDK 14+)
- ❌ `sealed` / `permits`(JDK 17+)
- ❌ `var` 关键字(JDK 10+)
- ❌ `List.of(...)`(JDK 9+)→ 用 `Arrays.asList(...)` 或 `Collections.unmodifiableList(...)`
- ❌ Pattern matching for switch(JDK 17+)
- ❌ Text blocks `"""..."""`(JDK 15+)
- ✅ 用 Lombok `@Value` / `@Builder` / `@NonNull` 替代 records
- ✅ 用 `org.reactivestreams:reactive-streams:1.0.4` 而非 JDK 9+ `Flow` API

---

## 4. 文件地图(本会话可见的资产)

| 用途 | 绝对路径 | 说明 |
|---|---|---|
| 设计文档 | `~/Documents/AIFullStack/MyDSHAgentDesign/dsh_agent_design.md` | **项目真理**,4768 行,**只读** |
| SpecKit SOP | `~/Documents/AIFullStack/MyDSHAgentDesign/speckit_operator_prompt.md` | SpecKit 操作手册,820 行 |
| Prompt 速查 | `~/Documents/AIFullStack/MyDSHAgentDesign/lingshu_spec_prompts.md` | 新窗口 Prompt 模板,368 行 |
| SKILL | `~/.claude/skills/lingshu-spec-driven-dev/SKILL.md` | Claude Code 自动触发 |
| SKILL mirror | `~/Documents/AIFullStack/MyDSHAgentDesign/.claude/skills/lingshu-spec-driven-dev/SKILL.md` | 拷贝版,跨机器用 |
| 主仓根 | `~/code/lingshu/`(或 clone 后的仓根)| 有 `pom.xml` / `lingshu-core/` / ... |

---

## 5. 仓目录结构(Maven 多模块,5 个 — 速查)

> **以 `dsh_agent_design.md §10` 为准**;本节只列顶层模块边界,Claude `ls` 能发现的不写。

```
lingshu/                                  ← 主仓根
├── pom.xml                               ← 父 POM(Spring Boot 3.2.5 父继承)
├── lingshu-core/                         ← 核心:9 Slot 接口 + AgentConfig + 默认实现
├── lingshu-a2a-client/                   ← A2A 客户端(§5.6,可独立打包)
├── lingshu-a2a-server/                   ← A2A 服务端 + AgentCard 生成
├── lingshu-examples/                     ← 教学示例(≤ 10 个,各 ≤ 100 行,见 dsh §10.2)
└── lingshu-cli/                          ← CLI 入口(`mvn exec:java`,见 dsh §10.3)
```

**包路径**:`ai.lingshu.core.*`(lingshu-core)/ `ai.lingshu.a2a.{client,server}.*`
**模块依赖方向**:core 不依赖 a2a-*;examples/cli 依赖 core + a2a-*;**严禁反向依赖**

**每个 Story 默认改动模块**(速查):

| Story | 主要改哪个模块 |
|---|---|
| #001 zero-config-bootstrap | lingshu-core + lingshu-examples |
| #002 identity-instructions-memory | lingshu-core(`PromptBuilder` 默认实现)|
| #003 spi-slot-router | lingshu-core(`SlotRouter` 接口)|
| #009 a2a-agent-card | lingshu-a2a-server(主)+ lingshu-core(接入 `A2aTransport`)|
| #016 audit-log | lingshu-core(`AuditLogger` SPI)|
| 其它 Story | 主要 lingshu-core;详见 dsh §3.1 SOP |

---

## 6. 9 个 Slot 接口(dsh §4 — 核心骨架)

| # | Slot | 接口 | 默认实现 |
|---|---|---|---|
| 1 | LLM | `LlmProvider` | Anthropic LlmProvider(Story #003)|
| 2 | Tool | `Tool` + `ToolExecutor` | 默认 Executor(Story #004)|
| 3 | Sandbox | `Sandbox` | JVM 内 chroot(Story #001 后)|
| 4 | Skill | `SkillSource` + `Skill` | classpath + directory 双源(Story #002)|
| 5 | SessionStore | `SessionStore` | memory(Story #014)|
| 6 | Compactor | `Compactor` | 无(Story #015)|
| 7 | PromptBuilder | `PromptBuilder` | 5 段装配(Story #002)|
| 8 | FlowEngine | `FlowEngine` | LinearTurnEngine = ReAct Loop(Story #001)|
| 9 | A2aTransport | `A2aTransport` | LocalAgentCardGenerator(Story #009)|

**包命名**:`ai.lingshu.core.*`

---

## 7. 核心约定(读 CLAUDE.md 时必看)

- **配置类**:全部 `@Value` + `@Builder`,**无 setter
- **错误码命名**:`LINGS-<域><编号>`,域字母 = `C/S/L/T/X/R/A/Z`(详见 dsh §15)
- **不可变性优先**:能 `@Value` 就不用 `@Data`
- **常量**:`SCREAMING_SNAKE_CASE`
- **业务三件套**:Identity / Instructions / Memory(详见 dsh §8.1)
- **零配置原则**:空 application.yml 必须能启动,所有 27 字段有默认值
- **5 段 Prompt**:`[ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY] / [CONVERSATION HISTORY] / [USER MESSAGE]`

---

## 8. 常用命令

```bash
# 环境检查
mvn -v                                       # Maven 3.6.3+ / JDK 1.8.0_xxx+

# POM 语法校验(不下依赖)
mvn validate -N

# 编译 + 测试
mvn -pl lingshu-core -am compile
mvn -pl lingshu-core test

# 全模块编译
mvn -DskipTests=true install

# 跑某个 Story 的 AC 测试
mvn test -Dtest=AC_NN_ClassName

# 性能压测(Story #010 后才有)
k6 run perf/load/<scenario>.js
```

---

## 9. Git workflow

```bash
# 提交约定
feat(agent): Story #NNN <slug> — <一句话>
docs(design): vX.Y.Z — <一句话>
fix(slot-<N>): <一句话>
chore: <一句话>

# 不允许的提交
× 直接 commit 到 main
× 跨多个 Story 的"feat:" commit
× 没跑 AC 的 PR
× 把 SOP / Prompt 速查 / SKILL push 到 lingshu 仓
```

---

## 10. 性能预算(dsh §14.15.1,实施时对齐)

| 指标 | 目标 |
|---|---|
| LLM 流式首 token | P50 ≤ 1.5s / P99 ≤ 3.0s |
| turn 完成(10 steps)| P50 ≤ 30s / P99 ≤ 60s |
| Tool 调用 | P99 ≤ toolTimeoutSec |
| 单 turn history | ≤ 100K tokens |
| 冷启动 | ≤ 30s(空 yml)|
| 并发 turn 数 | 默认 16,排队 ≤ 32 |

---

## 11. 硬约束(违反即 reject)

1. **不 push SOP 到 lingshu 仓**(`speckit_operator_prompt.md` / `lingshu_spec_prompts.md` 是本地流程制品)
2. **不省略 AC 黑盒验证**(每个 Story 必须跑对应的 AC-NN 才能合)
3. **不跨 Story 改 constitution**(改宪章必须走 RFC 流程,先开 issue + 评审)
4. **Story 边界**:≤ 5 个核心文件改动,≤ 3 个 ErrorCode 引入(超过就拆)
5. **不绕过 Spring Boot SPI**(新增 Slot 必须走 Provider + SlotRouter,**不要硬编码**)
6. **不引入额外依赖**(dsh §10.1 已锁 14 项[v1.5.8 起,含 `spring-ai-bom`],新依赖需 RFC + `dependency:tree` CI 卡点 + **`banned-dependencies` enforcer build 阶段 fail**(见 dsh §17 R-13);任何 Story 实施者必须按 SOP §3.2 AC-NN-deps-* + §3.4 T-dep-tree-* 流程自查后提交,**PR body 末尾**必须有 `### R-13 dependency:tree 自查` 节)
7. **ReAct Loop 必须自实现**(不得用 Spring AI `ChatClient.prompt().call()` 自动工具执行;核心循环 ~ 数十行,完整掌握 Agent 工作机制,保留定制循环行为的空间 — dsh §4.10.1 硬规则 1)
8. **Spring AI 只用两件事**:① LLM Provider 协议转换(OpenAI / Anthropic / Gemini / DeepSeek / Qwen / Kimi 等格式差异) ② `@Tool` 注解 JSON Schema 生成。**必须禁用** Spring AI 自动 tool 执行 — 会绕过 ToolExecutor 的沙箱/权限/checkpoint,导致 tool 被调两次(dsh §4.10.1 硬规则 2)
9. **Provider 必须显式映射**:多 `ChatModel` 并存时 Bean 类型相同,必须维护 `Map<String, ChatModel> providerMap` 显式查找;不得靠 Spring 容器扫 Bean 类型区分(dsh §4.10.1 硬规则 3)

---

## 12. 常用反问(避免无脑实现)

- 用户说"实现 X" → 先问"对应哪个 Story?或对应哪条 AC?"
- 用户说"改 Y" → 先确认 Y 是否在已有 Story 范围,避免 Scope creep
- 用户说"加依赖 Z" → 先查 dsh §10.1 锁定表
- 用户说"用 record/sealed/var" → 提醒 JDK 8 约束
- 用户说"push SOP" → 提醒归档边界

---

## 13. 项目节奏(KPI)

- **Story 完成率**:每周 / 每月闭合几个 Story
- **平均 AC 通过率**:第一遍跑过的比例(目标 ≥ 80%)
- **宪章稳定性**:`constitution.md` 一个月改几次(目标 0 次,改必须 RFC)
- **Risk Register 缓解率**:R-XX 中已缓解的比例(目标 ≥ 60% by v1.0 GA)

---

**Last updated**: 2026-09-10  
**Version**: 1.3.1(+§4.5.1 5 段 Prompt 装配补 [TOOL SCHEMAS] 段,配套同步 dsh v1.5.9 / SKILL v1.0.3;SOP v1.3 / prompts v1.0.1 不变)
**对应设计文档**: `dsh_agent_design.md` v1.5.9  
**对应 SpecKit SOP**: `speckit_operator_prompt.md` v1.3  
**对应 SKILL**: `lingshu-spec-driven-dev` v1.0.3
