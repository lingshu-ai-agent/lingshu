# Story #017 `cli-entrypoint` — Spec

> **Status**: Draft 2026-09-21
> **Source**: dsh v1.5.36 §10.3 (`lingshu-cli` 已并入) + §14.15.7 L5 (E2E 黑盒)
> **Closes gap**: `lingshu-cli` Maven module 自 Story #001 Step -1 至今只有 `pom.xml`,5 个 subcommand 全缺,dsh §10.3 / §14.15.7 L5 文档与代码长期 drift

---

## WHY

`lingshu-cli` 是 LingShu 唯一对用户暴露的执行入口(企业部署 / 本地 CLI / CI L5 E2E),但自 Story #001 Step -1 搭出空 Maven 模块至今从未落地实现。`pom.xml` description 字段明确写着 "placeholder, full impl in Story #001 follow-up" —— 但 Story #001 实际交付的是 AgentFactory + 7 Router + 6 Default Provider + AC-01-1,**CLI 不在其中**。Story #001—#016 期间所有用户接触 LingShu 的唯一途径是 `lingshu-examples/*` demo 包,**demo 不是产品**。

具体阻塞:
1. **dsh §14.15.7 L5 黑盒无 host** —— "完整 CLI 跑通 `lingshu-cli run` + 真实 LLM" 是 release gate AC-01—AC-10 的 L5 验收路径,**没有 CLI = L5 永远无法跑**
2. **企业部署无产物** —— 用户按 README 走完 `git clone` 后唯一可执行的是 demo,无 main 方法入口
3. **Story #010+ (OTel) / #013 (HealthIndicator) / #014 (SessionStore) 都假设 CLI 入口存在** —— 实际是 Story #017 缺失

## WHO

| 角色 | 关注点 |
|---|---|
| **企业 Java 工程师**(Alice 类) | `mvn -pl lingshu-cli spring-boot:run --args="run --config ... --prompt ..."` 一行启动;不需要懂 Spring Boot |
| **CI 工程师**(Charlie 类) | `mvn -pl lingshu-cli exec:java -Dexec.args="doctor"` 集成到 release pipeline,exit code 0/非0 二值 |
| **本地开发者**(Bob 类) | `./lingshu serve --port 8080` 起 A2A server,`curl /.well-known/agent.json` 验证 |

## WHAT

Story #017 落地 `lingshu-cli` 模块 5 个 subcommand:

| subcommand | 行为 | 入口依赖 |
|---|---|---|
| `run` | `loadYaml + create + runBlocking(prompt) → stdout finalText` | `AgentFactory.create` + `DefaultAgent.runBlocking` |
| `resume` | `loadYaml + create(sessionId) + continueWithUserMessage → stdout finalText` | `AgentFactory.create` + `DefaultAgent.continueWithUserMessage`(memory session)|
| `serve` | `new A2aServer(cfg).start() + block on shutdown hook` | Story #009 `A2aServer` 直调 |
| `doctor` | `loadYaml + factory.description() + Slot Router 计数 → stdout` | `AgentFactory.description()`(Story #003)+ 6 Router 自省 |
| `config` | `loadYaml + toJson(AgentConfig) → stdout` | Jackson 默认序列化 |

**约束**:
- dsh §10.1 锁定 13 项依赖,**0 新增 Maven coordinates**(R-13 mitigation (d))
- argv 解析**手写 ~30—80 行**,**不**引入 picocli(新增 = 第 14 项,需 RFC + enforcer + 体积膨胀)
- CLI bootstrap 走 Spring Boot `SpringApplicationBuilder.web(NONE)`,**不**破坏 `AgentFactory` `@Component` 单例契约(dsh §7.1)
- 5 subcommand 共享 `ArgsParser`,每 subcommand 必填 flag 由 `Subcommand.requiredFlags[]` 声明

## 反向 AC(明确不做)

- ❌ REPL / 交互模式(留 Story #017b)
- ❌ Bash completion / man page(留 Story #017b)
- ❌ Config mutation / `config set`(留 Story #017b)
- ❌ Shell 配色 / 进度条(纯 stdout JSON 或 plain text)
- ❌ 多语言 i18n(英文 / 简中错误消息混用,不强制)

## AC 编号(Story #017 新增,不入 dsh §0.4)

| AC | 描述 | 验证 |
|---|---|---|
| **AC-L5-1** | `mvn spring-boot:run --args="run --config <yml> --prompt 'text'"` 调通 `AgentFactory.create` + `agent.runBlocking(prompt)`,stdout 输出 `RunResult.finalText`,exit code 0 | `RunHandlerTest.run_withValidYaml_callsAgentRunBlocking`(L2)+ `MainIntegrationTest.main_withRunArgs_invokesCliRunner` |
| **AC-L5-2** | `mvn spring-boot:run --args="serve --port N --config <yml>"` 启动 A2aServer,curl `/`.well-known/agent.json` 返回 200 + JSON | `ServeHandlerTest.serve_withDefaultPort_startsA2aServer`(L2)+ `MainIntegrationTest.main_withServeArgs_startsA2aServer_thenShutsDown` |
| **AC-L5-3** | `mvn spring-boot:run --args="doctor"` 打印 `factory.description()` 输出 + 9 Slot Router 计数 + JVM info | `DoctorHandlerTest.doctor_withDefaultConfig_printsDescription`(L2) |
| **AC-L5-4** | `mvn spring-boot:run --args="config --print-effective --config <yml>"` 打印 Jackson JSON 序列化 resolved `AgentConfig` | `ConfigHandlerTest.config_withEffectiveFlag_printsResolvedJson`(L2) |
| **AC-L5-5** | `mvn spring-boot:run --args="resume --session <id> --prompt 'text'"` 加载 memory session + continueWithUserMessage,exit code 0 | `ResumeHandlerTest.resume_withMemorySession_continueWithUserMessage`(L2,memory-only) |
| **EC-L5-1** | 缺 `--prompt` flag → `LINGS-Z01` + exit code 2 | `RunHandlerTest.run_withBlankPrompt_throwsLingsZ01` |
| **EC-L5-2** | 未知 subcommand (`foo`) → `LINGS-Z01` + exit code 2 | `ArgsParserTest.parse_withUnknownSubcommand_throwsLingsZ01` + `MainIntegrationTest.main_withInvalidArgs_returnsExitCode2` |
| **EC-L5-3** | YAML 文件不存在 → `LINGS-Z02` + exit code 3 | `RunHandlerTest.run_withMissingYaml_throwsLingsZ02` + Doctor / Config 各 1 |
| **EC-L5-4** | `--port 80` 已占用 → `LINGS-S06` + exit code 6(复用 Story #009) | `ServeHandlerTest.serve_withBindFailure_throwsLingsS06` |
| **EC-L5-5** | SIGTERM / Ctrl+C → JVM shutdown hook 触发 `A2aServer.stop()` + 端口释放 < 1s | `ServeHandlerTest.serve_shutdownHook_stopsServer` |

## ErrorCode 引入(2 条,Story 边界 ≤3 内)

| 码 | 域 | 触发场景 |
|---|---|---|
| `LINGS-Z01` | Z(其他) | CLI 参数缺失 / 未知 subcommand / 必填 flag 缺失 / subcommand-specific 校验失败 |
| `LINGS-Z02` | Z(其他) | YAML 文件不存在 / 解析失败 / 缺顶层 `agent:` map |

**复用 3 条**:
- `LINGS-C02`(Story #001)—— 配置校验失败
- `LINGS-S06`(Story #009)—— A2A 端口占用
- `LINGS-T02`(Story #009)—— `identity.name` 空(CLI bootstrap 时若空,`doctor` / `config` 会暴露)

## 出口标准(DoD)

- [ ] `specs/017-cli-entrypoint/{spec,plan,tasks}.md` 三件套合入主分支
- [ ] 5 subcommand 实现 + 26 测试 case 全过(`mvn -pl lingshu-cli test`)
- [ ] `mvn -pl lingshu-core,lingshu-a2a-server,lingshu-cli -am test` 全模块回归 230/26/0 = 256 case 全绿
- [ ] R-13 `mvn dependency:tree` 自查:`0 新 Maven coordinates`
- [ ] PR body 含 `### R-13 dependency:tree 自查` 节
- [ ] README.md 累计测试 +26 → 230 case + 2 新 ErrorCode + "## ⚡ 30 秒上手" 加 CLI 段 + Story #017 narrative section
- [ ] constitution.md §10 R-13 mitigation 加 "Story #017: CLI module 不引入 picocli 等额外 Maven 坐标"
- [ ] dsh v1.5.37 单独 PR 同步(沿用 Story #007 / #008 模式)

## 不在 Story #017 范围(显式 deferred)

- ❌ `serve` 的 RPC `/rpc` 端点(留 Story #009a)
- ❌ `serve` 的 gRPC transport(留 Story #009a)
- ❌ `resume` 用 FileSessionStore / Redis / JDBC(留 Story #014;本 Story 只支持内存 session)
- ❌ `doctor` 的 HealthIndicator 深度检查(留 Story #013)
- ❌ `config set` mutation / `config validate` lint
- ❌ REPL / Bash completion / man page
- ❌ 多语言 i18n
- ❌ lingshu-cli 二进制 release pipeline(留 Story #018)

---

**Last updated**: 2026-09-21
**Spec author**: Claude Code (per user 2026-09-21 conversation)
**Reviewer**: 待 PR review