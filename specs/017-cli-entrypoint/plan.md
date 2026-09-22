# Story #017 `cli-entrypoint` — Plan

> **Status**: Draft 2026-09-21
> **Implements**: `specs/017-cli-entrypoint/spec.md`
> **Reference**: plan at `/Users/lineng/.claude/plans/polished-crafting-popcorn.md`(已 user-approved via ExitPlanMode)

---

## 接口设计

### `Main`(@SpringBootApplication)

```java
package ai.lingshu.cli;

@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        new SpringApplicationBuilder(Main.class)
            .web(WebApplicationType.NONE)  // 禁掉 Tomcat,Story #017 不要 HTTP server(serve 走 A2aServer)
            .run(args);
    }
}
```

**Why**:`AgentFactory` 是 `@Component` + `@Autowired 6 Router`,不能 `new` 直接调;Spring Boot 走 `@SpringBootApplication` 自动 component scan + 自动 wire。`web(NONE)` 避免拉 Tomcat(serve subcommand 走 `new A2aServer(cfg)` 而不是 Spring MVC)。

### `Subcommand`(enum)

```java
public enum Subcommand {
    RUN("run", "Run a single turn"),
    RESUME("resume", "Resume an existing session"),
    SERVE("serve", "Start A2A HTTP server"),
    DOCTOR("doctor", "Diagnose configuration"),
    CONFIG("config", "Print effective AgentConfig");

    private final String cmd;
    private final String description;
    Subcommand(String cmd, String description) { this.cmd = cmd; this.description = description; }

    public static Subcommand fromString(String s) {
        for (Subcommand sc : values()) if (sc.cmd.equalsIgnoreCase(s)) return sc;
        throw new LingsCliException("LINGS-Z01",
            "unknown subcommand: " + s,
            "valid: run / resume / serve / doctor / config");
    }
}
```

### `Args`(Lombok @Value)

```java
@Value
public class Args {
    Subcommand subcommand;
    Path configPath;       // 默认 application.yml
    String prompt;          // 仅 run
    String sessionId;       // 仅 resume
    Integer port;           // 仅 serve, 默认 8080
    boolean printEffective; // 仅 config
    boolean printSchema;    // 仅 doctor / config
}
```

### `ArgsParser`(手写 ~80 行)

```java
public final class ArgsParser {
    private ArgsParser() {}

    public static Args parse(String[] argv) {
        if (argv.length == 0) {
            throw new LingsCliException("LINGS-Z01",
                "no subcommand given",
                "usage: lingshu {run|resume|serve|doctor|config} [--flags]");
        }
        Subcommand sc = Subcommand.fromString(argv[0]);
        Path config = Paths.get(flag(argv, "--config", "application.yml"));
        // ... per-sc 必填校验
        return new Args(sc, config, prompt, sessionId, port, printEffective, printSchema);
    }
}
```

### `LingsCliException`(镜像 `LingsA2aServerException`)

```java
public class LingsCliException extends RuntimeException {
    @Getter private final String errorCode;  // "LINGS-Z01" / "LINGS-Z02"
    @Getter private final String hint;
    @Getter private final int exitCode;      // 2 / 3 / ...

    public LingsCliException(String code, String message, String hint) {
        super(message);
        this.errorCode = code;
        this.hint = hint;
        this.exitCode = exitCodeFor(code);
    }

    @Override public String getMessage() {
        return "[" + errorCode + "] " + super.getMessage()
            + (hint == null || hint.isEmpty() ? "" : "\nhint: " + hint);
    }

    private static int exitCodeFor(String code) {
        switch (code) {
            case "LINGS-Z01": return 2;
            case "LINGS-Z02": return 3;
            case "LINGS-C02": return 4;
            case "LINGS-S06": return 6;
            default:          return 1;
        }
    }
}
```

### `CliRunner`(@Component ApplicationRunner)

```java
@Component
public class CliRunner implements ApplicationRunner {
    private final AgentFactory factory;
    private final PrintStream out;
    private final PrintStream err;

    public CliRunner(AgentFactory factory,
                     @Value("${lingshu.cli.stdout:#{system.out}}") PrintStream out,
                     @Value("${lingshu.cli.stderr:#{system.err}}") PrintStream err) {
        this.factory = factory;
        this.out = out;
        this.err = err;
    }

    @Override
    public void run(ApplicationArguments appArgs) {
        Args args = ArgsParser.parse(appArgs.getSourceArgs());
        try {
            switch (args.getSubcommand()) {
                case RUN:    doRun(args); break;
                case RESUME: doResume(args); break;
                case SERVE:  doServe(args); break;
                case DOCTOR: doDoctor(args); break;
                case CONFIG: doConfig(args); break;
            }
        } catch (LingsCliException e) {
            err.println(e.getMessage());
            System.exit(e.getExitCode());
        } catch (RuntimeException e) {
            err.println("[LINGS-Z99] unexpected: " + e.getMessage());
            System.exit(1);
        }
    }

    // 5 个 subcommand 方法(package-private 供测试)
    void doRun(Args args)    { /* loadYaml + create + runBlocking */ }
    void doResume(Args args) { /* loadYaml + create + continueWithUserMessage */ }
    void doServe(Args args)  { /* new A2aServer(cfg).start() + CountDownLatch */ }
    void doDoctor(Args args) { /* loadYaml + factory.description() */ }
    void doConfig(Args args) { /* loadYaml + Jackson toJson */ }
}
```

## 文件改动清单

### 生产代码(6 files, ~500 LOC)

| 文件 | LOC | 复用 / 依赖 |
|---|---|---|
| `lingshu-cli/pom.xml` | +15 | 加 `lingshu-a2a-server` / `spring-boot-starter` / test deps |
| `src/main/java/ai/lingshu/cli/Main.java` | ~25 | `spring-boot-starter` |
| `src/main/java/ai/lingshu/cli/Subcommand.java` | ~30 | 无 |
| `src/main/java/ai/lingshu/cli/Args.java` | ~25 | Lombok |
| `src/main/java/ai/lingshu/cli/ArgsParser.java` | ~100 | `Args` + `Subcommand` + `LingsCliException` |
| `src/main/java/ai/lingshu/cli/LingsCliException.java` | ~50 | 镜像 `LingsA2aServerException` |
| `src/main/java/ai/lingshu/cli/CliRunner.java` | ~250 | `AgentFactory` + `A2aServer` + `LingsCliException` |

### 测试代码(8 files, ~600 LOC, 26 cases)

| 文件 | cases | 测试模式 |
|---|---|---|
| `ArgsParserTest.java` | 8 | L1 单元测试 |
| `LingsCliExceptionTest.java` | 2 | L1 单元测试 |
| `RunHandlerTest.java` | 3 | L2 集成(直接调 `CliRunner.doRun` + stub LlmProvider)|
| `ResumeHandlerTest.java` | 2 | L2 集成(内存 session)|
| `ServeHandlerTest.java` | 4 | L2 集成(真 A2aServer)|
| `DoctorHandlerTest.java` | 2 | L2 集成 |
| `ConfigHandlerTest.java` | 2 | L2 集成 |
| `MainIntegrationTest.java` | 3 | L5 整 CLI 黑盒 |

## 复用现有能力

| 现有 | 复用方式 |
|---|---|
| `AgentFactory.loadYamlAndValidate(Path)`(L343)| `CliRunner.doRun/doResume/doServe/doDoctor/doConfig` 直接调 |
| `AgentFactory.create(AgentConfig)`(L176)| 同上 |
| `AgentFactory.description()`(L293)| `CliRunner.doDoctor` |
| `AgentFactory.registerJvmShutdownHook()`(L118)| Spring `@PostConstruct` 自动触发(Spring lifecycle)|
| `DefaultAgent.runBlocking(String)`(L98)| `CliRunner.doRun` |
| `DefaultAgent.continueWithUserMessage(String)`(L149)| `CliRunner.doResume`(需 story #014 才支持 file session;Story #017 走 memory)|
| `A2aServer.start() / stop() / getActualPort()`(Story #009)| `CliRunner.doServe` |
| `LingsA2aServerException`(Story #009)| `CliRunner.doServe` catch 后 rethrow as `LingsCliException(LINGS-S06, ...)` |
| `LingsConfigException`(LINGS-C02,Story #001)| `CliRunner.doRun/doDoctor` catch 后 rethrow |
| Spring Boot `@SpringBootApplication` + `SpringApplicationBuilder.web(NONE)` | 复用 `lingshu-examples/demo-engineer/DemoEngineerApplication.java:67` 模式 |

## R-13 dependency:tree 自查

```bash
mvn -pl lingshu-cli dependency:tree -DincludeScope=runtime
```

**新增直接依赖**:
- `ai.lingshu:lingshu-a2a-server:0.1.0-SNAPSHOT` ← 本仓 sibling 模块
- `org.springframework.boot:spring-boot-starter:3.2.5` ← Spring Boot BOM 管理
- `org.springframework.boot:spring-boot-starter-test:3.2.5` (test) ← 同上
- `org.junit.jupiter:junit-jupiter:5.10.x` (test) ← **已在 dsh §10.1 第 8 行锁定**
- `org.assertj:assertj-core:3.24.x` (test) ← **已在 dsh §10.1 第 9 行锁定**

**新增 transitive 依赖**:`spring-boot-starter` 拉 `spring-boot` / `spring-context` / `spring-boot-autoconfigure` / logback / snakeyaml / jackson — **全部由 Spring Boot BOM 统一管理**,**dsh §10.1 第 2 行 `spring-boot-dependencies` BOM 已锁**,0 坐标增量。

**判断**:**0 新 Maven coordinates** ✅ R-13 mitigation (d) 满足。

## 测试策略

### L1 单元(ArgsParser / LingsCliException)

- `ArgsParserTest.parse_withRunSubcommand_extractsPromptAndConfig`
- `ArgsParserTest.parse_withUnknownSubcommand_throwsLingsZ01`
- `ArgsParserTest.parse_withMissingConfigFlag_defaultsToApplicationYml`
- `ArgsParserTest.parse_withResumeRequiresSessionId_throwsLingsZ01`(EC)
- `ArgsParserTest.parse_withServeAcceptsPortFlag`
- `ArgsParserTest.parse_withDoctorAcceptsPrintSchemaFlag`
- `ArgsParserTest.parse_withEmptyArgs_throwsLingsZ01`(EC-2)
- `ArgsParserTest.parse_withShortFlagForm_supported`
- `LingsCliExceptionTest.ctor_withCodeAndHint_rendersBothInMessage`
- `LingsCliExceptionTest.ctor_withCause_propagatesToGetCause`

### L2 切片(单 subcommand + 真实依赖)

- `RunHandlerTest.run_withValidYaml_callsAgentRunBlocking`(用 `lingshu-examples/demo-empty` 的 mock LlmProvider 模式)
- `RunHandlerTest.run_withMissingYaml_throwsLingsZ02`(EC-3)
- `RunHandlerTest.run_withBlankPrompt_throwsLingsZ01`(EC-1)
- `ResumeHandlerTest.resume_withUnknownSessionId_throwsLingsZ01`
- `ResumeHandlerTest.resume_withMemorySession_continueWithUserMessage`
- `ServeHandlerTest.serve_withDefaultPort_startsA2aServer`(L2 真 HTTP,镜像 `A2aServerLifecycleTest`)
- `ServeHandlerTest.serve_withCustomPort_passesToA2aServer`
- `ServeHandlerTest.serve_withBindFailure_throwsLingsS06`(EC-4)
- `ServeHandlerTest.serve_shutdownHook_stopsServer`(EC-5)
- `DoctorHandlerTest.doctor_withDefaultConfig_printsDescription`
- `DoctorHandlerTest.doctor_withMissingYaml_throwsLingsZ02`(EC-3)
- `ConfigHandlerTest.config_withEffectiveFlag_printsResolvedJson`
- `ConfigHandlerTest.config_withMissingYaml_throwsLingsZ02`(EC-3)

### L5 黑盒(整 CLI)

- `MainIntegrationTest.main_withRunArgs_invokesCliRunner`(全 Spring 启动)
- `MainIntegrationTest.main_withServeArgs_startsA2aServer_thenShutsDown`
- `MainIntegrationTest.main_withInvalidArgs_returnsExitCode2`(EC-2)

## 测试入口命令

```bash
mvn -pl lingshu-cli -am test
# expect: Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
```

## 集成验证

```bash
# 黑盒:run subcommand
mvn -pl lingshu-cli spring-boot:run \
  -Dspring-boot.run.arguments="run --config lingshu-examples/demo-empty/src/main/resources/application.yml --prompt 'say hi'"

# 黑盒:serve subcommand(后台跑)
mvn -pl lingshu-cli spring-boot:run \
  -Dspring-boot.run.arguments="serve --port 18099" &
sleep 3
curl -sf http://127.0.0.1:18099/.well-known/agent.json | head -c 200
# expect: {"name":"...","version":"0.1.0",...}
```

## 不变项(Story #017 必须不改)

- ❌ `AgentFactory` 任何字段 / 方法签名
- ❌ `DefaultAgent.runBlocking` / `continueWithUserMessage` 任何签名
- ❌ `A2aServer.start() / stop() / getActualPort()` 任何签名
- ❌ dsh §5.6.4 Slot 9 `A2aTransport` 5 方法契约
- ❌ dsh §7.1 `AgentFactory` 是 Spring `@Component` 单例(本 Story **用** Spring,不破坏)
- ❌ dsh §10.1 13 锁定依赖
- ❌ `LinearTurnEngine` ReAct 主循环
- ❌ `ToolExecutor` 5 步流水线
- ❌ `constitution.md`(任何字段)
- ❌ `lingshu-core` / `lingshu-a2a-server` 测试 fixture