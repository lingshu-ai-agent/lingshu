package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import ai.lingshu.core.spi.SlotRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AgentFactory — the Spring-singleton entry point (dsh §7.1) that produces prototype-like
 * {@link Agent} instances per turn.
 *
 * <p>Boot invariants enforced in {@link #create(AgentConfig)} (fail-fast on misconfig):
 * <ol>
 *   <li>{@code config != null}</li>
 *   <li>{@code config.getReactMaxSteps() > 0}</li>
 *   <li>{@code config.getLlmTimeoutSeconds() > 0}</li>
 *   <li>{@code LlmProvider} resolves</li>
 *   <li>{@code ToolExecutor} resolves</li>
 *   <li>{@code PermissionPolicy} resolves</li>
 *   <li>{@code PromptBuilder} resolves</li>
 *   <li>{@code FlowEngine} resolves</li>
 * </ol>
 *
 * <p>dsh §7.1.1: this factory is a stateless singleton; each call to {@code create(config)}
 * yields a fresh Agent holding its own session + resolved Slot instances. Users who want
 * multi-turn conversation hold the {@link Session} across turns (or call
 * {@code Agent.continueWithUserMessage}).
 *
 * <p>🆕 Story #003 — also exposes a {@link #description()} self-describe method that
 * lists every registered Slot Provider (name, version, priority) plus JVM info.
 * Per dsh §5.3.1.0 the factory holds 6 Routers; {@code MemorySourceRouter} is included
 * here (in addition to its consumer wiring in {@code DefaultPromptBuilderProvider}) so
 * US3 / {@code /slots}-style introspection can list all 9 Slots from one entry point.
 */
@Component
public class AgentFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AgentFactory.class);

    /**
     * 🆕 Story #005 — Static broadcast registry shared across all AgentFactory instances.
     *
     * <p>Effectively singleton because Spring only instantiates one AgentFactory
     * {@code @Component} per application context. Made static so that
     * {@link DefaultTurnContext#createWithBroadcast} (called from
     * {@code DefaultAgent.buildContext}) can register tokens without holding an
     * AgentFactory reference.
     *
     * <p>CopyOnWriteArrayList — safe iteration while {@code broadcastCancel} runs
     * concurrent with {@code registerCancellation} (NFR-008).
     */
    private static final CopyOnWriteArrayList<CancellationToken> BROADCAST_REGISTRY =
        new CopyOnWriteArrayList<>();

    /** 🆕 Story #005 — for test isolation: clear static registry. */
    static void clearBroadcastRegistryForTest() {
        BROADCAST_REGISTRY.clear();
    }

    private final Routers.LlmProviderRouter llmRouter;
    private final Routers.ToolExecutorRouter toolRouter;
    private final Routers.PermissionPolicyRouter policyRouter;
    private final Routers.PromptBuilderRouter promptBuilderRouter;
    private final Routers.FlowEngineRouter flowRouter;
    private final Routers.MemorySourceRouter memorySourceRouter;

    @Autowired
    public AgentFactory(Routers.LlmProviderRouter llmRouter,
                        Routers.ToolExecutorRouter toolRouter,
                        Routers.PermissionPolicyRouter policyRouter,
                        Routers.PromptBuilderRouter promptBuilderRouter,
                        Routers.FlowEngineRouter flowRouter,
                        Routers.MemorySourceRouter memorySourceRouter) {
        this.llmRouter = llmRouter;
        this.toolRouter = toolRouter;
        this.policyRouter = policyRouter;
        this.promptBuilderRouter = promptBuilderRouter;
        this.flowRouter = flowRouter;
        this.memorySourceRouter = memorySourceRouter;
    }

    /**
     * 🆕 Story #005 (FR-009) — Register the broadcast-cancel shutdown hook after Spring
     * finishes wiring this AgentFactory. The hook fires on Ctrl-C / SIGTERM and calls
     * {@link #broadcastCancel()} so all in-flight turns receive cancellation within the
     * AC-04 200ms budget.
     *
     * <p>JVM {@code Runtime.addShutdownHook} is idempotent on the same Thread instance
     * — repeated registrations of the same hook thread are silently ignored.
     */
    @PostConstruct
    public void registerJvmShutdownHook() {
        Thread hook = new Thread(new Runnable() {
            @Override
            public void run() {
                broadcastCancel();
            }
        }, "lingshu-shutdown-cancel");
        Runtime.getRuntime().addShutdownHook(hook);
        LOG.info("registered JVM shutdown hook for cancellation broadcast");
    }

    /**
     * 🆕 Story #005 (FR-009) — Register a cancellation token. The token receives
     * {@link CancellationToken#fire()} when {@link #broadcastCancel()} runs.
     *
     * <p>Static facade — called from {@link DefaultTurnContext#createWithBroadcast}.
     * Internally uses static registry shared across all AgentFactory instances
     * (Spring guarantees singleton semantics in practice).
     */
    public static void registerCancellationStatic(CancellationToken token) {
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }
        BROADCAST_REGISTRY.add(token);
    }

    /**
     * 🆕 Story #005 (FR-009 + NFR-006) — Trigger cancellation on all in-flight turns.
     * Synchronously iterates the broadcast registry; per-token exceptions are caught
     * and logged at WARN so one bad token does not block siblings.
     *
     * <p>Idempotent — calling twice is safe (each token's {@code fire()} is itself
     * idempotent via AtomicBoolean CAS).
     */
    public void broadcastCancel() {
        int n = BROADCAST_REGISTRY.size();
        LOG.info("broadcast cancel: {} active turn(s)", n);
        for (CancellationToken token : BROADCAST_REGISTRY) {
            try {
                token.fire();
            } catch (Exception e) {
                LOG.warn("cancel token.fire() failed", e);
            }
        }
    }

    /** 🆕 Story #005 — current broadcast registry size (for introspection + tests). */
    public static int activeTurnCount() {
        return BROADCAST_REGISTRY.size();
    }

    /**
     * Build an Agent for the given immutable config snapshot. Creates a fresh {@link Session}.
     *
     * @throws IllegalArgumentException if config is null, timeouts are non-positive, or
     *         any of the named Slot providers is unknown
     */
    public Agent create(AgentConfig config) {
        validate(config);
        // 🆕 Story #006 — fail-fast on malformed tenant config (FR-010 + LINGS-C02).
        // No-op in single-tenant mode (config.getTenants() == null).
        validateTenants(config);

        // Resolve Slots (each Router logs which Provider was chosen)
        LlmProvider llmProvider = llmRouter.resolve(config.getLlm().getProvider(), config);
        ToolExecutor toolExecutor = toolRouter.resolve(config.getToolExecutor(), config);
        PermissionPolicy permissionPolicy = policyRouter.resolve(config.getSandbox().getPolicy(), config);
        PromptBuilder promptBuilder = promptBuilderRouter.resolve(config.getPrompt().getBuilder(), config);
        FlowEngine engine = flowRouter.resolve(config.getFlowEngine(), config);

        Session session = new DefaultSession();
        LOG.info("AgentFactory.create: sessionId={} flowEngine={} llm={}/{}",
            session.id(),
            config.getFlowEngine(),
            config.getLlm().getProvider(),
            config.getLlm().getModel());

        // Story #001: ToolExecutor / PermissionPolicy / PromptBuilder are resolved but
        // not yet injected into the LinearTurnEngine — the engine wires only prompt+llm
        // for the demo path. Story #004 / #008 inject them into a richer engine variant.
        // 🆕 Story #005: DefaultAgent.buildContext now uses DefaultTurnContext.createWithBroadcast
        // to auto-register cancellation tokens (FR-011).
        return new DefaultAgent(session, config, engine);
    }

    private static void validate(AgentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("AgentConfig must not be null");
        }
        if (config.getReactMaxSteps() <= 0) {
            throw new IllegalArgumentException(
                "config.reactMaxSteps must be > 0, got " + config.getReactMaxSteps());
        }
        if (config.getLlmTimeoutSeconds() <= 0) {
            throw new IllegalArgumentException(
                "config.llmTimeoutSeconds must be > 0, got " + config.getLlmTimeoutSeconds());
        }
        // The Router.resolve calls below also enforce non-null slot names; doing them
        // explicitly here gives a clearer stack trace for AC-01-1 failures.
        if (config.getFlowEngine() == null || config.getFlowEngine().isEmpty()) {
            throw new IllegalArgumentException("config.flowEngine is required");
        }
        if (config.getLlm() == null || config.getLlm().getProvider() == null) {
            throw new IllegalArgumentException("config.llm.provider is required");
        }
        if (config.getToolExecutor() == null || config.getToolExecutor().isEmpty()) {
            throw new IllegalArgumentException("config.toolExecutor is required");
        }
        if (config.getSandbox() == null || config.getSandbox().getPolicy() == null) {
            throw new IllegalArgumentException("config.sandbox.policy is required");
        }
        if (config.getPrompt() == null || config.getPrompt().getBuilder() == null) {
            throw new IllegalArgumentException("config.prompt.builder is required");
        }
    }

    /**
     * 🆕 Story #006 (FR-010) — validate the {@code agent.tenants} block, if present.
     *
     * <p>Single-tenant mode (config.getTenants() == null) skips entirely — Story #001—#005
     * behavior is unchanged. Multi-tenant mode raises {@link ai.lingshu.core.exception.LingsConfigException}
     * ({@code LINGS-C02}) if the map exceeds 1000 entries, any tenant has missing
     * required fields, or key/value tenantId mismatch is detected.
     */
    private static void validateTenants(AgentConfig config) {
        if (config.getTenants() == null) {
            return;
        }
        config.getTenants().validate();
    }

    /**
     * 🆕 Story #003 — return a self-describe snapshot of all 6 Routers + JVM info
     * (US3 Scenario 1 / quickstart.md Validation 3).
     *
     * <p>Format (newline-separated):
     * <pre>{@code
     * AgentFactory v0.1.0-SNAPSHOT for JVM <java.version>
     * LlmProvider: <name> v<version> (priority=<n>)
     * ...
     * MemorySource: <name> v<version> (priority=<n>)
     * Turn=0 Session=<sessionId>
     * }</pre>
     *
     * <p>MemorySource is listed N times (once per registered Provider) so users
     * can see priority order at a glance.
     *
     * @return newline-separated self-describe string, never null
     */
    public String description() {
        List<String> lines = new ArrayList<>();
        lines.add("AgentFactory v0.1.0-SNAPSHOT for JVM " + System.getProperty("java.version", "?"));
        lines.addAll(llmRouter.describe().stream()
            .map(s -> "LlmProvider: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        lines.addAll(toolRouter.describe().stream()
            .map(s -> "ToolExecutor: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        lines.addAll(policyRouter.describe().stream()
            .map(s -> "PermissionPolicy: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        lines.addAll(promptBuilderRouter.describe().stream()
            .map(s -> "PromptBuilder: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        lines.addAll(flowRouter.describe().stream()
            .map(s -> "FlowEngine: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        lines.addAll(memorySourceRouter.describe().stream()
            .map(s -> "MemorySource: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        // Append a placeholder session line so the contract output is complete
        // even when description() is called outside an Agent turn.
        lines.add("Turn=0 Session=" + new DefaultSession().id());
        return String.join("\n", lines);
    }
}