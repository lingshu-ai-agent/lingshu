/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.reload.AgentConfigRegistry;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.slot.ToolExecutionContext.CancellationToken;
import ai.lingshu.core.spi.SlotRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.springframework.beans.factory.InitializingBean;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   <li>🆕 Story #028 — {@code RuntimeSandbox} resolves</li>
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
public class AgentFactory implements InitializingBean {

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
    /** 🆕 Story #028 — 7th implicit Router for Slot 3 Sandbox (dsh §5.3.1.0). */
    private final Routers.RuntimeSandboxRouter runtimeSandboxRouter;
    /** 🆕 Story #014 — 8th implicit Router for Slot 5 SessionStore (dsh §5.3.1.0). */
    private final Routers.SessionStoreRouter sessionStoreRouter;
    /** 🆕 Story #045 — 9th implicit Router for Slot 6 Compactor (dsh §5.3.1.0). */
    private final Routers.CompactorRouter compactorRouter;

    @Autowired
    public AgentFactory(Routers.LlmProviderRouter llmRouter,
                        Routers.ToolExecutorRouter toolRouter,
                        Routers.PermissionPolicyRouter policyRouter,
                        Routers.PromptBuilderRouter promptBuilderRouter,
                        Routers.FlowEngineRouter flowRouter,
                        Routers.MemorySourceRouter memorySourceRouter,
                        Routers.RuntimeSandboxRouter runtimeSandboxRouter,
                        Routers.SessionStoreRouter sessionStoreRouter,
                        Routers.CompactorRouter compactorRouter) {
        this.llmRouter = llmRouter;
        this.toolRouter = toolRouter;
        this.policyRouter = policyRouter;
        this.promptBuilderRouter = promptBuilderRouter;
        this.flowRouter = flowRouter;
        this.memorySourceRouter = memorySourceRouter;
        this.runtimeSandboxRouter = runtimeSandboxRouter;
        this.sessionStoreRouter = sessionStoreRouter;
        this.compactorRouter = compactorRouter;
    }

    /**
     * 🆕 Story #014 — legacy 8-Router constructor (Slot 5 wired). Sets
     * {@code compactorRouter = null} (Story #045) so test subclasses can bypass the Spring
     * wiring without dragging in a stub CompactorRouter; production code paths always use
     * the 9-Router ctor above so the Slot 6 Compactor is actually resolved. The
     * {@link ai.lingshu.core.impl.flow.LinearTurnEngineProvider#create(AgentConfig)}
     * method's null-guard treats a null {@code compactorRouter} as "no Compactor
     * configured" and falls back to {@link ai.lingshu.core.impl.compaction.NullCompactor}.
     */
    public AgentFactory(Routers.LlmProviderRouter llmRouter,
                        Routers.ToolExecutorRouter toolRouter,
                        Routers.PermissionPolicyRouter policyRouter,
                        Routers.PromptBuilderRouter promptBuilderRouter,
                        Routers.FlowEngineRouter flowRouter,
                        Routers.MemorySourceRouter memorySourceRouter,
                        Routers.RuntimeSandboxRouter runtimeSandboxRouter,
                        Routers.SessionStoreRouter sessionStoreRouter) {
        this(llmRouter, toolRouter, policyRouter, promptBuilderRouter, flowRouter,
            memorySourceRouter, runtimeSandboxRouter, sessionStoreRouter, null);
    }

    /**
     * 🆕 Story #028 — legacy 6-Router constructor. Sets {@code runtimeSandboxRouter = null}
     * and {@code sessionStoreRouter = null} (Story #014) so test subclasses
     * (e.g. {@code StubAgentFactory} in {@code DelegateToolTest}) can bypass the Spring
     * wiring without dragging in stub Routers. Production code paths always use the
     * 9-Router ctor above so Slot 3 Sandbox, Slot 5 SessionStore and Slot 6 Compactor
     * are all actually resolved.
     *
     * <p>The {@link #create(AgentConfig, AgentConfigRegistry)} method's null-guard treats a
     * null {@code runtimeSandboxRouter} as "no Sandbox configured" — pre-#028 tests that
     * override {@code create(...)} entirely (rather than calling it) keep working.
     */
    public AgentFactory(Routers.LlmProviderRouter llmRouter,
                        Routers.ToolExecutorRouter toolRouter,
                        Routers.PermissionPolicyRouter policyRouter,
                        Routers.PromptBuilderRouter promptBuilderRouter,
                        Routers.FlowEngineRouter flowRouter,
                        Routers.MemorySourceRouter memorySourceRouter) {
        this(llmRouter, toolRouter, policyRouter, promptBuilderRouter, flowRouter,
            memorySourceRouter, null, null);
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
    @Override
    public void afterPropertiesSet() throws Exception {
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
        return create(config, null);
    }

    /**
     * 🆕 Story #007 (AC-06 / spec §FR-006) — Build an Agent that reads the latest
     * {@link AgentConfig} from {@code registry} at the start of every turn.
     *
     * <p>This enables YAML hot-reload semantics: the registry is the single writer
     * for {@code AgentConfig}; the Agent freezes a snapshot once per turn at entry
     * (per dsh §14.8 R-03 mitigation). T1 (before reload) sees the original config,
     * T2 (after a successful reload that publishes a new config) sees the new one.
     * A turn already in flight is unaffected (immutable @Value config + Java
     * reference freeze).
     *
     * <p>Pass {@code null} to disable hot-reload (single-turn Agent, identical
     * behaviour to {@link #create(AgentConfig)}).
     *
     * @param config   the initial / fallback config (used if {@code registry == null}
     *                 or its current value is unavailable)
     * @param registry the hot-reload source of truth, or {@code null} for static mode
     */
    public Agent create(AgentConfig config, AgentConfigRegistry registry) {
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
        // 🆕 Story #028 — resolve Sandbox runtime (Slot 3 sub-slot via the 7th implicit Router).
        // Null-guard for the legacy 6-Router ctor path (pre-#028 tests); production paths
        // always inject the runtimeSandboxRouter via the 7-Router @Autowired ctor above.
        RuntimeSandbox runtimeSandbox = (runtimeSandboxRouter != null)
            ? runtimeSandboxRouter.resolve(config.getSandbox().getRuntime(), config)
            : null;
        // 🆕 Story #014 — resolve SessionStore (Slot 5 via the 8th implicit Router).
        // Null-guard for the legacy 6-Router ctor path; production paths always inject
        // the sessionStoreRouter via the 8-Router @Autowired ctor above.
        // The resolved instance is logged at INFO below for visibility but not yet
        // injected into DefaultAgent — full save/load wiring into the turn loop is the
        // work of a follow-up Story that integrates Checkpoint persistence.
        SessionStore sessionStore = (sessionStoreRouter != null)
            ? sessionStoreRouter.resolve(config.getSessionStore(), config)
            : null;

        Session session = new DefaultSession();
        LOG.info("AgentFactory.create: sessionId={} flowEngine={} llm={}/{} sandbox={} sessionStore={} registry={}",
            session.id(),
            config.getFlowEngine(),
            config.getLlm().getProvider(),
            config.getLlm().getModel(),
            config.getSandbox().getRuntime(),
            sessionStore != null ? sessionStore.getClass().getSimpleName() : "none",
            registry != null ? "hot-reload (frozen per turn)" : "static");

        // Story #001: ToolExecutor / PermissionPolicy / PromptBuilder are resolved but
        // not yet injected into the LinearTurnEngine — the engine wires only prompt+llm
        // for the demo path. Story #004 / #008 inject them into a richer engine variant.
        // 🆕 Story #005: DefaultAgent.buildContext now uses DefaultTurnContext.createWithBroadcast
        // to auto-register cancellation tokens (FR-011).
        // 🆕 Story #007: registry (if non-null) is held by the Agent and read once per turn
        // at run() entry — provides AC-06 "freeze old config, see new config next turn" semantics.
        // 🆕 Story #028: DefaultAgent.buildContext now passes the resolved RuntimeSandbox to
        // DefaultToolExecutionContext so the sandbox boundary (fs/http/process) actually fires
        // per-tool-call (§6.3 + §4.10.1 hard rule 2 pipeline step 4).
        return new DefaultAgent(session, config, engine, registry, runtimeSandbox);
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
        // 🆕 Story #028 — runtime is also required so the Slot 3 Sandbox Router can resolve.
        if (config.getSandbox().getRuntime() == null || config.getSandbox().getRuntime().isEmpty()) {
            throw new IllegalArgumentException("config.sandbox.runtime is required");
        }
        // 🆕 Story #014 — sessionStore is also required so the Slot 5 SessionStore Router can resolve.
        if (config.getSessionStore() == null || config.getSessionStore().isEmpty()) {
            throw new IllegalArgumentException("config.sessionStore is required");
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
     * SessionStore: <name> v<version> (priority=<n>)
     * Compactor: <name> v<version> (priority=<n>)     ← 🆕 Story #045
     * Turn=0 Session=<sessionId>
     * }</pre>
     *
     * <p>MemorySource is listed N times (once per registered Provider) so users
     * can see priority order at a glance. SessionStore and Compactor
     * (Story #014, Story #045) are null-guarded so the legacy 6-Router / 8-Router
     * ctor paths produce the same output without crashing.
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
        // 🆕 Story #028 — surface the RuntimeSandbox slot in the self-describe output so
        // users can see which chroot-style provider is active. Null-guard for the legacy
        // 6-Router ctor path; in production the runtimeSandboxRouter is always non-null.
        if (runtimeSandboxRouter != null) {
            lines.addAll(runtimeSandboxRouter.describe().stream()
                .map(s -> "RuntimeSandbox: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        }
        // 🆕 Story #014 — surface the SessionStore slot in the self-describe output so
        // users can see which memory/file backend is active. Null-guard for the legacy
        // 6-Router ctor path; in production the sessionStoreRouter is always non-null.
        if (sessionStoreRouter != null) {
            lines.addAll(sessionStoreRouter.describe().stream()
                .map(s -> "SessionStore: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        }
        // 🆕 Story #045 — surface the Slot 6 Compactor router in the self-describe output so
        // users can see which summarizer is active (e.g. truncating). Null-guard for the
        // legacy 6-Router / 8-Router ctor paths; in production the compactorRouter is always
        // non-null (Spring autowires it via the @Autowired 9-Router ctor).
        if (compactorRouter != null) {
            lines.addAll(compactorRouter.describe().stream()
                .map(s -> "Compactor: " + s.trim()).collect(java.util.stream.Collectors.toList()));
        }
        // Append a placeholder session line so the contract output is complete
        // even when description() is called outside an Agent turn.
        lines.add("Turn=0 Session=" + new DefaultSession().id());
        return String.join("\n", lines);
    }

    // ─────────────────────────────────────────────────────────────────────
    // 🆕 Story #007 — YAML hot-reload helpers (spec §FR-005 + FR-009)
    //
    // The YamlWatcher daemon (ai.lingshu.core.reload.YamlWatcher) detects a
    // mtime change on application.yml and calls back into the factory to
    // reload + validate the file. We deliberately do NOT pull a full YAML
    // parser dependency (R-13 mitigation d: 0 new transitive deps); instead
    // we ship a small line-based reader that handles the subset of YAML
    // actually used by lingshu-core's runtime config — flat key:value
    // entries under `agent:` plus list items under a parent key (e.g.
    // `agent.sandbox.command-whitelist: [a, b]` and `- a` / `- b` forms).
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 🆕 Story #007 (FR-005) — Load and validate an {@link AgentConfig} from a
     * YAML file. Used by {@link YamlWatcher} on every mtime change to produce
     * a fresh candidate config for {@link AgentConfigRegistry#publish}.
     *
     * <p>Reads the file bytes, parses the limited YAML subset, maps into an
     * {@link AgentConfig} (with sensible defaults for fields not present in the
     * file), and runs {@link #validate(AgentConfig)}. Any I/O / parse /
     * validation failure is propagated as a {@link RuntimeException} so the
     * caller can log + keep the previous config.
     *
     * @param ymlPath path to the yml file; must exist and be readable
     * @return a fully validated {@link AgentConfig}; never null
     * @throws IOException          if the file cannot be read
     * @throws RuntimeException     if YAML parsing fails or validation rejects the config
     */
    public AgentConfig loadYamlAndValidate(Path ymlPath) throws IOException {
        String content = new String(Files.readAllBytes(ymlPath), StandardCharsets.UTF_8);
        Map<String, Object> root = parseMinimalYaml(content);
        // 🆕 Story #026 — resolve ${...} placeholders in the parsed tree.
        // The hand-rolled parser returns ${X} as a literal token; without this
        // hook, `${user.dir}` would silently become the 13-char string
        // "${user.dir}" — see PlaceholderResolver Javadoc for the 4-form grammar.
        // Spring Environment path (demo-product @Bean) already resolves natively
        // so this hook is only needed for the CLI / YamlWatcher hot-reload path.
        Object agentNode = root.get("agent");
        if (!(agentNode instanceof Map)) {
            throw new IllegalStateException(
                "yml file " + ymlPath + " missing top-level 'agent:' map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) agentNode;
        agent = (Map<String, Object>) PlaceholderResolver.resolvePlaceholders(agent, ymlPath);
        AgentConfig cfg = toAgentConfig(agent, ymlPath);
        validate(cfg);
        return cfg;
    }

    /**
     * 🆕 Story #007 — minimal YAML → nested {@code Map<String,Object>} parser.
     *
     * <p>Handles only what lingshu-core needs:
     * <ul>
     *   <li>{@code key: value} — string values (inline form)</li>
     *   <li>{@code key: [a, b, c]} — flow-style list</li>
     *   <li>{@code key:} + indented {@code - item} — block-style list</li>
     *   <li>{@code key:} + indented sub-map</li>
     *   <li>{@code #} comments (whole line + trailing any to end of line)</li>
     *   <li>blank lines</li>
     * </ul>
     *
     * <p>Indentation is measured in 2-space units. Anything more elaborate
     * (anchors, multi-doc, complex flow styles) raises an
     * {@link IllegalStateException} which {@link YamlWatcher} catches +
     * logs at ERROR and keeps the previous config (per AC-06 rollback).
     *
     * @return a hierarchical map rooted at the top-level {@code key: value}
     *         pairs; never null
     */
    static Map<String, Object> parseMinimalYaml(String content) {
        Map<String, Object> root = new LinkedHashMap<>();
        // Stack frames: each frame = (indent in 2-space units, container Map)
        // The root frame has indent -1 so the first key always fits.
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(-1, root));

        // Most recent value-less key (a "candidate" for either a child map or
        // a block list). When the next non-blank line is `- item` we promote
        // the candidate into a List; otherwise we leave it as a Map.
        String lastContainerKey = null;
        Map<String, Object> lastContainerMap = null;
        int lastContainerIndent = -1;

        for (String rawLine : content.split("\\r?\\n")) {
            int hash = rawLine.indexOf('#');
            String line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).replaceAll("\\s+$", "");
            if (line.trim().isEmpty()) continue;

            int indentUnits = countIndent(line);
            String body = line.trim();

            if (body.startsWith("- ")) {
                // Block-list item: target = lastContainerMap's most-recent value-less key
                if (lastContainerKey == null || lastContainerMap == null
                    || indentUnits != lastContainerIndent + 1) {
                    throw new IllegalStateException(
                        "yml parse error: orphan list item: " + rawLine);
                }
                Object existing = lastContainerMap.get(lastContainerKey);
                List<Object> list;
                if (existing instanceof List) {
                    list = (List<Object>) existing;
                } else if (existing instanceof Map) {
                    // Promote placeholder map (from value-less key:) to List
                    list = new ArrayList<>();
                    lastContainerMap.put(lastContainerKey, list);
                } else if (existing == null) {
                    list = new ArrayList<>();
                    lastContainerMap.put(lastContainerKey, list);
                } else {
                    throw new IllegalStateException(
                        "yml parse error: list item under non-list parent '" + lastContainerKey + "'");
                }
                list.add(unquote(body.substring(2).trim()));
                continue;
            }

            int colon = body.indexOf(':');
            if (colon < 0) {
                throw new IllegalStateException(
                    "yml parse error: line without ':' separator: " + rawLine);
            }
            String key = body.substring(0, colon).trim();
            String valuePart = body.substring(colon + 1).trim();

            // Pop stack until top.indent < current indent
            while (!stack.isEmpty() && stack.peek().indent >= indentUnits) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                throw new IllegalStateException(
                    "yml parse error: indent underflow at: " + rawLine);
            }
            Map<String, Object> current = stack.peek().container;
            if (valuePart.isEmpty()) {
                // Value-less key: defer decision (map vs list) until we see the
                // next line. Push a fresh Map now; promote to List if `- item`
                // arrives.
                Map<String, Object> child = new LinkedHashMap<>();
                current.put(key, child);
                stack.push(new Frame(indentUnits, child));
                lastContainerKey = key;
                lastContainerMap = current;
                lastContainerIndent = indentUnits;
            } else if (valuePart.startsWith("[") && valuePart.endsWith("]")) {
                // Flow-style list
                List<Object> list = new ArrayList<>();
                String inner = valuePart.substring(1, valuePart.length() - 1).trim();
                if (!inner.isEmpty()) {
                    for (String tok : inner.split(",")) {
                        String t = tok.trim();
                        if (t.isEmpty()) continue;
                        list.add(unquote(t));
                    }
                }
                current.put(key, list);
                lastContainerKey = null;
                lastContainerMap = null;
                lastContainerIndent = -1;
            } else {
                // Scalar
                current.put(key, unquote(valuePart));
                lastContainerKey = null;
                lastContainerMap = null;
                lastContainerIndent = -1;
            }
        }
        return root;
    }

    /** Stack frame for {@link #parseMinimalYaml(String)}. */
    private static final class Frame {
        final int indent;
        final Map<String, Object> container;
        Frame(int indent, Map<String, Object> container) {
            this.indent = indent;
            this.container = container;
        }
    }

    /**
     * 🆕 Story #007 — convert a parsed {@code agent:} map to a fully populated
     * {@link AgentConfig} with defaults for missing fields.
     */
    @SuppressWarnings("unchecked")
    private static AgentConfig toAgentConfig(Map<String, Object> agent, Path ymlPath) {
        String flowEngine = stringOr(agent, "flowEngine", "linear");
        String toolExecutor = stringOr(agent, "toolExecutor", "default");
        String sandboxPolicy = stringOr(agent.get("sandbox"), "policy", "strict");
        String sandboxRuntime = stringOr(agent.get("sandbox"), "runtime", "chroot");
        String sandboxWd = stringOr(agent.get("sandbox"), "working-directory", "/tmp");
        List<String> whitelist = stringListOr(agent.get("sandbox"), "command-whitelist",
            Collections.emptyList());

        String llmProvider = stringOr(agent.get("llm"), "provider", "anthropic");
        String llmModel = stringOr(agent.get("llm"), "model", "claude-3-5-sonnet-latest");

        String promptBuilder = stringOr(agent.get("prompt"), "builder", "default");
        int topK = intOr(agent.get("prompt"), "topK", 5);

        int toolParallelism = intOr(agent, "toolParallelism", 8);
        int toolTimeoutSeconds = intOr(agent, "toolTimeoutSeconds", 30);
        int approvalTimeoutSeconds = intOr(agent, "approvalTimeoutSeconds", 0);
        int turnTimeoutSeconds = intOr(agent, "turnTimeoutSeconds", 120);
        int llmTimeoutSeconds = intOr(agent, "llmTimeoutSeconds", 30);
        int reactMaxSteps = intOr(agent, "reactMaxSteps", 50);
        // 🆕 Story #044 — top-level turn concurrency cap (dsh §10 NFR row 4).
        // Mirrors agent.reactMaxSteps top-level camelCase precedent (NOT agent.factory.max-turns
        // nested as the dsh footnote phrased it). Default 16 + queue 32.
        int maxConcurrentTurns = intOr(agent, "maxConcurrentTurns", 16);
        int maxConcurrentQueueDepth = intOr(agent, "maxConcurrentQueueDepth", 32);

        AgentConfig.Llm llm = new AgentConfig.Llm(llmProvider, llmModel, 8192, 0.7);
        AgentConfig.Prompt prompt = new AgentConfig.Prompt(promptBuilder, Collections.emptyList(), topK);
        AgentConfig.Sandbox sandbox = new AgentConfig.Sandbox(
            sandboxPolicy, sandboxRuntime, java.nio.file.Paths.get(sandboxWd),
            whitelist, Collections.emptyList());

        // ── Story #029 + 🆕 Story #030 ── PermissionPolicy (top-level) + ToolsConfig allow/deny/ask lists.
        // Default permissionPolicy="default" preserves Story #001 zero-config back-compat;
        // demo yml may opt into `permission-policy: strict` or `permission-policy: ask` (Story #030)
        // to switch Slot 4 router target. askList triggers AskUser → host UI approval flow.
        String permissionPolicy = stringOr(agent, "permission-policy", "default");
        Map<String, Object> toolsMap = agent.get("tools") instanceof Map
            ? (Map<String, Object>) agent.get("tools") : Collections.<String, Object>emptyMap();
        List<String> toolAllowList = stringListOr(toolsMap, "allow-list", Collections.<String>emptyList());
        List<String> toolDenyList = stringListOr(toolsMap, "deny-list", Collections.<String>emptyList());
        // 🆕 Story #030 — ask-list (kebab-case `ask-list` mirrors allow-list/deny-list);
        // empty default means AskUserPermissionPolicy falls through to default-allow path.
        List<String> toolAskList = stringListOr(toolsMap, "ask-list", Collections.<String>emptyList());
        boolean toolsEnabled = booleanOr(toolsMap, "enabled", true);
        AgentConfig.ToolsConfig toolsCfg = new AgentConfig.ToolsConfig(
            toolsEnabled, toolAllowList, toolDenyList, toolAskList,
            intOr(toolsMap, "max-file-bytes", 200_000),
            intOr(toolsMap, "max-write-bytes", 1_000_000));

        LOG.info("loadYamlAndValidate: parsed {} (provider={} model={} whitelist={} permissionPolicy={} allowList={} denyList={} askList={} maxConcurrentTurns={} maxConcurrentQueueDepth={})",
            ymlPath.getFileName(), llmProvider, llmModel, whitelist, permissionPolicy, toolAllowList, toolDenyList, toolAskList,
            maxConcurrentTurns, maxConcurrentQueueDepth);

        // 🆕 Story #014 — session-store name (top-level, mirrors permission-policy style).
        // Default "memory" preserves Story #001 zero-config back-compat; demo yml may
        // opt into `session-store: file` to switch Slot 5 router target to FileSessionStore.
        String sessionStore = stringOr(agent, "session-store", "memory");

        // 🆕 Story #045 — compactor name (top-level, mirrors session-store style).
        // Default "truncating" preserves Story #018 zero-config back-compat; demo yml may
        // opt into `compactor: truncating` to switch Slot 6 router target explicitly. The
        // LinearTurnEngineProvider.create(AgentConfig) reads this name to resolve the
        // Compactor via CompactorRouter.resolve(name, cfg); NullCompactor is wired in
        // when the router is absent (legacy 6/8-Router ctors) so the back-compat fixture
        // chain keeps emitting zero compaction events.
        String compactor = stringOr(agent, "compactor", "truncating");

        return new AgentConfig(
            flowEngine,
            llm,
            prompt,
            toolExecutor,
            sandbox,
            compactor,                       // 🆕 Story #045 — Slot 6 Compactor name (resolved by LinearTurnEngineProvider)
            sessionStore,    // 🆕 Story #014 — sessionStore
            null,   // delegate
            null,   // mcp
            null,   // skills
            toolParallelism,
            toolTimeoutSeconds,
            approvalTimeoutSeconds,
            turnTimeoutSeconds,
            llmTimeoutSeconds,
            reactMaxSteps,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,                       // a2aTransport (Story #009 — leave to A2aServerAutoConfig)
            null,                       // tenants (Story #006 — null = single-tenant mode)
            AgentConfig.A2a.defaults(),    // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults(),  // compactorConfig (Story #018)
            toolsCfg,                                // tools (Story #019 + #029 allow/deny lists)
            permissionPolicy,                        // permissionPolicy (Story #029)
            maxConcurrentTurns,                      // 🆕 Story #044 — top-level turn cap (dsh §10 NFR row 4)
            maxConcurrentQueueDepth);                // 🆕 Story #044 — bounded queue depth (dsh §10 NFR row 4)
    }

    @SuppressWarnings("unchecked")
    private static String stringOr(Object parentObj, String key, String fallback) {
        if (!(parentObj instanceof Map)) return fallback;
        Map<String, Object> parent = (Map<String, Object>) parentObj;
        Object v = parent.get(key);
        return v == null ? fallback : v.toString();
    }

    @SuppressWarnings("unchecked")
    private static int intOr(Object parentObj, String key, int fallback) {
        if (!(parentObj instanceof Map)) return fallback;
        Map<String, Object> parent = (Map<String, Object>) parentObj;
        Object v = parent.get(key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Story #029 — boolean scalar reader for YAML map. Treats {@code "true"} (case-insensitive),
     * {@code true}, {@code 1} as {@code true}; everything else {@code false}.
     * Tolerates missing keys (returns fallback).
     */
    @SuppressWarnings("unchecked")
    private static boolean booleanOr(Object parentObj, String key, boolean fallback) {
        if (!(parentObj instanceof Map)) return fallback;
        Map<String, Object> parent = (Map<String, Object>) parentObj;
        Object v = parent.get(key);
        if (v == null) return fallback;
        String s = v.toString().trim().toLowerCase();
        if ("true".equals(s) || "1".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "0".equals(s) || "no".equals(s)) return false;
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringListOr(Object parentObj, String key, List<String> fallback) {
        if (!(parentObj instanceof Map)) return fallback;
        Map<String, Object> parent = (Map<String, Object>) parentObj;
        Object v = parent.get(key);
        if (v instanceof List) {
            List<Object> raw = (List<Object>) v;
            List<String> out = new ArrayList<>(raw.size());
            for (Object item : raw) {
                out.add(item == null ? null : item.toString());
            }
            return out;
        }
        return fallback;
    }

    private static String unquote(String s) {
        if (s == null || s.length() < 2) return s;
        if ((s.startsWith("\"") && s.endsWith("\"")) ||
            (s.startsWith("'")  && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static int countIndent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n / 2;
    }
}