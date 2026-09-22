# Story #017 `cli-entrypoint` — Tasks

> **Status**: Draft 2026-09-21
> **Implements**: `specs/017-cli-entrypoint/plan.md`
> **Test budget**: 26 cases / 8 files

---

## T-01 — `lingshu-cli/pom.xml` 依赖补全

**操作**:
1. 加 `lingshu-a2a-server` 直接依赖(`serve` subcommand 用)
2. 加 `spring-boot-starter`(`@SpringBootApplication` + `SpringApplicationBuilder` 用)
3. 加 test scope:`spring-boot-starter-test` + `junit-jupiter` + `assertj-core`
4. 跑 `mvn -pl lingshu-cli -am dependency:tree -DincludeScope=runtime` 验证 0 新 Maven coordinates

**DoD**: `mvn validate -N` 通过 + `dependency:tree` 输出符合 R-13。

---

## T-02 — `Subcommand` enum

**文件**:`src/main/java/ai/lingshu/cli/Subcommand.java`

**实现**:5 个值 `RUN/RESUME/SERVE/DOCTOR/CONFIG` + `fromString(String)` 抛 `LingsCliException(LINGS-Z01, ...)`。

**DoD**: `mvn -pl lingshu-cli compile` 通过。

---

## T-03 — `Args` Lombok @Value

**文件**:`src/main/java/ai/lingshu/cli/Args.java`

**字段**:`subcommand / configPath / prompt / sessionId / port / printEffective / printSchema`。

**DoD**: 编译过( Lombok `@Value` 生成全字段构造器 + getter)。

---

## T-04 — `LingsCliException`

**文件**:`src/main/java/ai/lingshu/cli/LingsCliException.java`

**实现**:镜像 `LingsA2aServerException` —— `errorCode` + `hint` + `exitCode` 字段;`exitCodeFor("LINGS-Z01") = 2`, `"LINGS-Z02") = 3`, `"LINGS-C02") = 4`, `"LINGS-S06") = 6`, 默认 1。

**DoD**: `LingsCliExceptionTest` 2 case 全过(参考 T-09)。

---

## T-05 — `ArgsParser`

**文件**:`src/main/java/ai/lingshu/cli/ArgsParser.java`

**实现**:
- `parse(String[] argv)` → `Args`
- `argv[0]` 通过 `Subcommand.fromString()` 转
- 后续 `--key value` flag 解析:`--config` / `--prompt` / `--session` / `--port` / `--print-effective` / `--print-schema`
- 必填校验 per subcommand:
  - `run`:`--prompt` 非空
  - `resume`:`--session` 非空
  - `serve`:`--port` 1—65535(默认 8080)
- 解析失败统一抛 `LingsCliException(LINGS-Z01, ..., hint)`

**DoD**: `ArgsParserTest` 8 case 全过(T-09)。

---

## T-06 — `Main`

**文件**:`src/main/java/ai/lingshu/cli/Main.java`

**实现**:
```java
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        new SpringApplicationBuilder(Main.class)
            .web(WebApplicationType.NONE)
            .run(args);
    }
}
```

**DoD**: 编译过 + Spring 启动不抛异常(`mvn spring-boot:run` smoke)。

---

## T-07 — `CliRunner`

**文件**:`src/main/java/ai/lingshu/cli/CliRunner.java`

**实现**:
- `@Component implements ApplicationRunner`
- `@Autowired AgentFactory factory` + `@Value` 注入 `PrintStream out/err`
- `run(ApplicationArguments)` 解析 args → switch 5 case → 调对应 `doRun/doResume/doServe/doDoctor/doConfig`
- catch `LingsCliException` / `RuntimeException` → 输出 + `System.exit`
- 5 个 do 方法实现:
  - `doRun`: `factory.loadYamlAndValidate(path)` → `factory.create(cfg)` → `agent.runBlocking(prompt)` → stdout `finalText`
  - `doResume`: 同上,但 `continueWithUserMessage(prompt)`(memory session 即可,`sessionId` 暂作 stub)
  - `doServe`: `factory.loadYamlAndValidate(path)` → 覆盖 `cfg.a2a.port`(若提供) → `new A2aServer(cfg).start()` → `CountDownLatch.await()` → JVM hook `srv.stop()`
  - `doDoctor`: `factory.loadYamlAndValidate(path)` → `factory.create(cfg)` + stdout `factory.description()` + 6 Router 计数
  - `doConfig`: `factory.loadYamlAndValidate(path)` → Jackson `ObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(cfg)` → stdout

**DoD**: `RunHandlerTest` / `ResumeHandlerTest` / `ServeHandlerTest` / `DoctorHandlerTest` / `ConfigHandlerTest` 共 13 case 全过(T-10)。

---

## T-08 — 测试基线准备

**操作**:
1. 给 `lingshu-cli/pom.xml` 配 `surefire-plugin` 跑 JUnit 5(Spring Boot 父 POM 默认带,需确认)
2. 给 test scope 加 `mockito-core`(可能用得到 stub `LlmProvider`)

**DoD**: `mvn -pl lingshu-cli test` 能跑通(哪怕 0 case)。

---

## T-09 — L1 单元测试(ArgsParser + LingsCliException)

**文件**:
- `src/test/java/ai/lingshu/cli/ArgsParserTest.java`(8 case)
- `src/test/java/ai/lingshu/cli/LingsCliExceptionTest.java`(2 case)

**实现**:标准 JUnit 5 + AssertJ 模式,镜像 `LocalAgentCardGeneratorTest`(Story #009)。

**DoD**: 10/10 case 全过。

---

## T-10 — L2 切片测试(5 subcommand × 2—4 case)

**文件**:
- `RunHandlerTest.java`(3 case):`run_withValidYaml_callsAgentRunBlocking`(用 stub `LlmProvider`)/ `run_withMissingYaml_throwsLingsZ02`(EC-3)/ `run_withBlankPrompt_throwsLingsZ01`(EC-1)
- `ResumeHandlerTest.java`(2 case):`resume_withUnknownSessionId_throwsLingsZ01` + `resume_withMemorySession_continueWithUserMessage`
- `ServeHandlerTest.java`(4 case):`serve_withDefaultPort_startsA2aServer`(真 `A2aServer` + `HttpURLConnection`)/ `serve_withCustomPort_passesToA2aServer` / `serve_withBindFailure_throwsLingsS06`(EC-4)/ `serve_shutdownHook_stopsServer`(EC-5)
- `DoctorHandlerTest.java`(2 case):`doctor_withDefaultConfig_printsDescription` + `doctor_withMissingYaml_throwsLingsZ02`
- `ConfigHandlerTest.java`(2 case):`config_withEffectiveFlag_printsResolvedJson` + `config_withMissingYaml_throwsLingsZ02`

**DoD**: 13/13 case 全过。

---

## T-11 — L5 整 CLI 黑盒测试

**文件**:`src/test/java/ai/lingshu/cli/MainIntegrationTest.java`

**实现**(3 case):
- `main_withRunArgs_invokesCliRunner`:用 `SpringApplicationBuilder` 启动 + 注入 `ApplicationArguments` + 调 `cliRunner.run(args)` + 验证 stdout
- `main_withServeArgs_startsA2aServer_thenShutsDown`:启动 + `serve` subcommand + `curl /` 验证 + `srv.stop()`
- `main_withInvalidArgs_returnsExitCode2`:启动 + 注入空 args + 验证 `System.exit(2)` 被调(用 SecurityManager 或 output capture)

**DoD**: 3/3 case 全过。

---

## T-12 — 全模块回归

**操作**:
```bash
mvn -pl lingshu-core,lingshu-a2a-server,lingshu-cli -am test
```

**DoD**:
- `lingshu-core` 187 case(Story #008)+ 17(Story #009)= 204 全绿
- `lingshu-a2a-server` 17 case 全绿
- `lingshu-cli` 26 case 全绿
- **总计 230 case**,0 failure,0 error,0 skipped

---

## T-13 — R-13 dependency:tree 自查

**操作**:
```bash
mvn -pl lingshu-cli -am dependency:tree -DincludeScope=runtime > /tmp/deps-017-after.txt
git show origin/main:lingshu-cli/pom.xml > /tmp/pom-baseline.xml
diff <(mvn -f /tmp/pom-baseline.xml dependency:tree -DincludeScope=runtime 2>/dev/null) /tmp/deps-017-after.txt
```

**DoD**:`diff` 输出**只**包含 Spring Boot `spring-boot-starter` + `spring-boot-starter-test` 新增分支,无新 `groupId:artifactId` 行。

---

## T-14 — commit + push + PR

**操作**:
1. `git checkout -b story-017-cli-entrypoint`
2. `git add lingshu-cli/` + `specs/017-cli-entrypoint/`
3. `git commit -m "feat(cli): Story #017 cli-entrypoint — 5 subcommands + 26 cases + 0 new Maven coords"`
4. `git push -u origin story-017-cli-entrypoint`
5. `gh pr create --title "feat(cli): Story #017 cli-entrypoint — run/resume/serve/doctor/config (L5 blackbox)" --body "$(...)"`

**PR body 必备节**:
- `## Story` 段:链接 spec.md / plan.md / tasks.md
- `## Scope` 段:5 subcommand + Out-of-Scope 列表
- `## Files Changed` 段:14 files 列表 + Story 边界超限 justification
- `## AC 验证` 段:26 case 全过输出 + 全模块回归 230 case
- `## ErrorCode 引入` 段:2 新增 (LINGS-Z01/Z02) + 3 复用 (C02/S06/T02)
- `### R-13 dependency:tree 自查` 段:T-13 diff 输出

**DoD**:PR URL 拿回 + review 反馈已闭环。

---

## T-15 — README + constitution 同步

**操作**(沿用 Story #007 / #008 / #009 docs commit 模式,**单独** commit):
1. README.md L43 核心特性加 "## ⚡ CLI 入口" bullet
2. README.md L515 累计测试 204 → 230
3. README.md L519 ErrorCode 数 "2 新增" 同步 LINGS-Z01/Z02
4. README.md 加 `### Story #017 cli-entrypoint` narrative section(AC-L5 黑盒输出)
5. README.md L536 文档链接加 `docs/concepts/cli-entrypoint.md`
6. `constitution.md §10 R-13 mitigation` 加 "Story #017: CLI module 不引入 picocli 等额外 Maven 坐标"

**DoD**:`git diff README.md` 与 plan.md "Cross-Module Documentation Sync" 表对齐。

---

## 任务依赖图

```
T-01 ──┬─> T-02 ──┐
       ├─> T-03 ──┤
       ├─> T-04 ──┼─> T-05 ──┐
       │           │          ├─> T-07 ──> T-10
       │           │          │           │
       │           │          │           ├─> T-12 ──> T-13 ──> T-14 ──> T-15
       │           │          │           │
       │           │          └─> T-06 ──┘
       │           │
       │           └─> T-08 (parallel with T-02—T-05)
       │
       └─> T-09 (parallel with T-10, after T-05)
                  │
                  └─> T-11 (after T-07)
```

**关键路径**:T-01 → T-04 → T-05 → T-07 → T-10 → T-12 → T-14

---

## 工时记录(参考 Story #009 节奏)

| Story | 提交 → 合入 | 工时估算 |
|---|---|---|
| #009 a2a-agent-card | 1 commit + 1 PR + 1 README fix + 1 #009c fix | ~3—4 小时 |
| **#017 cli-entrypoint**(预估)| 1 feat commit + 1 PR + 1 docs commit(README + constitution)| ~4—5 小时(模块更大,5 subcommand)|

---

**Last updated**: 2026-09-21