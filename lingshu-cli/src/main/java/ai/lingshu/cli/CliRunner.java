package ai.lingshu.cli;

import ai.lingshu.a2a.server.A2aServer;
import ai.lingshu.a2a.server.LingsA2aServerException;
import ai.lingshu.core.exception.LingsConfigException;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.RunResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintStream;

/**
 * Story #017 — CLI dispatcher. Receives the original argv via Spring's
 * {@link ApplicationRunner} contract, parses via {@link ArgsParser}, then
 * delegates to one of five subcommand handlers.
 *
 * <p>Each subcommand method ({@code doRun / doResume / doServe / doDoctor / doConfig})
 * is package-private so the {@code *HandlerTest} classes can invoke them directly
 * without booting Spring.
 *
 * <p>Error handling:
 * <ul>
 *   <li>{@link LingsCliException} (CLI-arg / YAML errors) → stderr + {@code System.exit(exitCode)}</li>
 *   <li>{@link LingsConfigException} / {@link LingsA2aServerException} / generic
 *       {@link RuntimeException} → wrap as CLI exception with appropriate LINGS code,
 *       stderr + {@code System.exit}</li>
 * </ul>
 */
@Component
public class CliRunner implements ApplicationRunner {

    private final AgentFactory factory;
    private final PrintStream out;
    private final PrintStream err;

    @Autowired
    public CliRunner(AgentFactory factory) {
        this(factory, System.out, System.err);
    }

    /** Test-only constructor — allows stdout / stderr capture in unit tests. */
    CliRunner(AgentFactory factory, PrintStream out, PrintStream err) {
        this.factory = factory;
        this.out = out;
        this.err = err;
    }

    @Override
    public void run(ApplicationArguments appArgs) {
        String[] source = appArgs.getSourceArgs();
        try {
            Args args = ArgsParser.parse(source);
            dispatch(args);
        } catch (LingsCliException e) {
            err.println(e.getMessage());
            System.exit(e.getExitCode());
        } catch (RuntimeException e) {
            err.println("[LINGS-Z99] unexpected: " + e.getMessage());
            e.printStackTrace(err);
            System.exit(1);
        }
    }

    private void dispatch(Args args) {
        switch (args.getSubcommand()) {
            case RUN:
                doRun(args);
                return;
            case RESUME:
                doResume(args);
                return;
            case SERVE:
                doServe(args);
                return;
            case DOCTOR:
                doDoctor(args);
                return;
            case CONFIG:
                doConfig(args);
                return;
            default:
                throw new LingsCliException("LINGS-Z01",
                    "unhandled subcommand: " + args.getSubcommand(),
                    "valid: run / resume / serve / doctor / config");
        }
    }

    // ── run ─ single turn: loadYaml + create + runBlocking(prompt) ─────────

    void doRun(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);
        Agent agent = factory.create(cfg);
        RunResult result = agent.runBlocking(args.getPrompt());
        out.println(result.getFinalText());
        out.println();
        out.println("[LINGS-Z99] turns=" + result.getTurns()
            + " usage=" + result.getTotalUsage()
            + " stopReason=" + result.getStopReason()
            + " elapsedMs=" + result.getElapsedMillis());
    }

    // ── resume: memory-session continuation (Story #014 will replace with file/redis/jdbc) ─

    void doResume(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);
        Agent agent = factory.create(cfg);
        // Story #017 scope — memory-only session; --session id is acknowledged but not
        // actually persisted. Story #014 will wire FileSessionStore / Redis / JDBC.
        out.println("[LINGS-Z99] resuming memory session: " + args.getSessionId()
            + " (note: in-memory only — server restart loses history)");
        RunResult result = agent.runBlocking(args.getPrompt());
        out.println(result.getFinalText());
        out.println();
        out.println("[LINGS-Z99] turns=" + result.getTurns()
            + " usage=" + result.getTotalUsage()
            + " stopReason=" + result.getStopReason()
            + " elapsedMs=" + result.getElapsedMillis());
    }

    // ── serve: start A2aServer, block until SIGTERM ──────────────────────

    void doServe(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);
        // Override A2a port if --port provided (cli flag wins)
        if (args.getPort() != null && !args.getPort().equals(ArgsParser.DEFAULT_PORT)) {
            cfg = withPort(cfg, args.getPort());
        }
        A2aServer server;
        try {
            server = new A2aServer(cfg);
            server.start();
        } catch (LingsA2aServerException e) {
            throw new LingsCliException(e.getErrorCode(),
                "A2aServer failed to start: " + e.getMessage(),
                e.getHint() != null ? e.getHint() : "check --port and identity.name",
                e);
        }
        int port = server.getActualPort();
        out.println("[LINGS-Z99] A2A server started on port " + port
            + " (GET http://127.0.0.1:" + port + "/.well-known/agent.json)");
        out.println("[LINGS-Z99] press Ctrl+C / send SIGTERM to stop");

        // JVM shutdown hook — fires on Ctrl+C / SIGTERM / normal exit
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                out.println("[LINGS-Z99] shutdown hook firing — stopping A2aServer");
                server.stop();
            }
        }, "lingshu-cli-serve-shutdown"));

        // Block main thread until JVM exit signal arrives
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            server.stop();
            throw new LingsCliException("LINGS-Z99",
                "serve interrupted",
                "restart with the same command");
        }
    }

    // ── doctor: factory.description() + 6 Router introspection ────────────

    void doDoctor(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);
        // Trigger router resolution (logs each Provider chosen); produces side-effect
        // for L1 diagnostic visibility.
        Agent agent = factory.create(cfg);
        out.println("=== LingShu Doctor ===");
        out.println();
        out.println(factory.description());
        out.println();
        if (args.isPrintSchema()) {
            try {
                ObjectMapper m = new ObjectMapper();
                m.enable(SerializationFeature.INDENT_OUTPUT);
                out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(cfg));
            } catch (Exception e) {
                throw new LingsCliException("LINGS-Z99",
                    "schema dump failed: " + e.getMessage(),
                    "retry without --print-schema", e);
            }
        }
        out.println();
        out.println("agent ready (turns=" + (agent == null ? 0 : 1) + ")");
    }

    // ── config: dump effective AgentConfig as JSON ────────────────────────

    void doConfig(Args args) {
        AgentConfig cfg = loadYamlOrThrow(args);
        if (args.isPrintEffective() || args.isPrintSchema()) {
            try {
                ObjectMapper m = new ObjectMapper();
                m.enable(SerializationFeature.INDENT_OUTPUT);
                out.println(m.writerWithDefaultPrettyPrinter().writeValueAsString(cfg));
            } catch (Exception e) {
                throw new LingsCliException("LINGS-Z99",
                    "config dump failed: " + e.getMessage(),
                    "retry without --print-effective", e);
            }
        } else {
            // Default behaviour: short summary
            out.println("=== LingShu Effective Config ===");
            out.println("flowEngine: " + cfg.getFlowEngine());
            out.println("llm.provider: " + cfg.getLlm().getProvider());
            out.println("llm.model: " + cfg.getLlm().getModel());
            out.println("toolExecutor: " + cfg.getToolExecutor());
            out.println("sandbox.policy: " + cfg.getSandbox().getPolicy());
            out.println("prompt.builder: " + cfg.getPrompt().getBuilder());
            out.println("memory.sources: " + cfg.getMemory().getClaudeMd());
            out.println("react.maxSteps: " + cfg.getReactMaxSteps());
            out.println("a2a.host: " + cfg.getA2a().getHost());
            out.println("a2a.port: " + cfg.getA2a().getPort());
            out.println();
            out.println("(use --print-effective for full JSON)");
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private AgentConfig loadYamlOrThrow(Args args) {
        try {
            return factory.loadYamlAndValidate(args.getConfigPath());
        } catch (java.nio.file.NoSuchFileException e) {
            throw new LingsCliException("LINGS-Z02",
                "config file not found: " + args.getConfigPath(),
                "check --config path; default is 'application.yml' in CWD", e);
        } catch (IOException e) {
            throw new LingsCliException("LINGS-Z02",
                "config file read failed: " + args.getConfigPath() + " (" + e.getMessage() + ")",
                "check file permissions / encoding (UTF-8 expected)", e);
        } catch (IllegalStateException e) {
            // parseMinimalYaml failures + missing top-level 'agent:' map
            String msg = e.getMessage() == null ? "" : e.getMessage();
            String hint = msg.contains("missing top-level 'agent:'")
                ? "yml must have a top-level 'agent:' map (see README §⚡ 30 秒上手)"
                : "verify yaml syntax (no tabs, 2-space indent, no orphan list items)";
            throw new LingsCliException("LINGS-Z02",
                "config parse failed: " + msg,
                hint, e);
        } catch (IllegalArgumentException e) {
            // validate(config) failures — slot names / timeouts
            throw new LingsCliException("LINGS-C02",
                "config validation failed: " + e.getMessage(),
                "fix the offending value or rely on defaults (see AgentConfigDefaults)",
                e);
        } catch (LingsConfigException e) {
            // Tenants validation etc.
            throw new LingsCliException("LINGS-" + e.getCode(),
                "config validation failed: " + e.getMessage(),
                "see dsh §15 for LINGS-" + e.getCode() + " definition", e);
        }
    }

    /**
     * Build a new {@link AgentConfig} with overridden A2a port. Re-constructs via the
     * 22-arg constructor preserving every other field. Same pattern used in
     * Story #009 AC-10 tests (port override).
     */
    private static AgentConfig withPort(AgentConfig cfg, int newPort) {
        AgentConfig.A2a newA2a = new AgentConfig.A2a(cfg.getA2a().getHost(), newPort);
        // AgentConfig has 22 fields (Story #006 added tenants, #009 added a2a);
        // preserve all 21, swap only a2a (22nd).
        return new AgentConfig(
            cfg.getFlowEngine(),
            cfg.getLlm(),
            cfg.getPrompt(),
            cfg.getToolExecutor(),
            cfg.getSandbox(),
            cfg.getCompactor(),
            cfg.getSessionStore(),
            cfg.getDelegate(),
            cfg.getMcp(),
            cfg.getSkills(),
            cfg.getToolParallelism(),
            cfg.getToolTimeoutSeconds(),
            cfg.getApprovalTimeoutSeconds(),
            cfg.getTurnTimeoutSeconds(),
            cfg.getLlmTimeoutSeconds(),
            cfg.getReactMaxSteps(),
            cfg.getIdentity(),
            cfg.getInstructions(),
            cfg.getMemory(),
            cfg.getA2aTransport(),
            cfg.getTenants(),
            newA2a);
    }
}