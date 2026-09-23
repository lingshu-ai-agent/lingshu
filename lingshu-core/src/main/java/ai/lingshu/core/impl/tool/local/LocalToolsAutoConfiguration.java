package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Story #019 — Wire the four built-in local {@link Tool}s into the engine (dsh §6.5 (1) L4427-4452).
 *
 * <p><b>Why {@code @Configuration} (not {@code @AutoConfiguration}):</b> the four Tools are
 * {@code @Component}s already (component-scanned in {@code lingshu-core}); this class only
 * (1) exposes a single {@link LocalToolProps} bean derived from
 * {@link AgentConfigDefaults#defaults()} (the Slot core stays free of any AgentConfig type —
 * see {@link LocalToolProps} for the rationale), and (2) registers the Tools with the
 * shared {@link ToolRegistry} bean + injects the sandbox-aware {@code ProcessRunner}
 * into {@link BashTool}.
 *
 * <p><b>Why {@link ToolRegistry} (not the concrete {@code DefaultToolExecutor}):</b>
 * dsh §4.6 defines the tool registry as the Slot 2 outer-half complement to
 * {@code ToolExecutor}. Story #019 lifts the previously-executor-owned registry into
 * a dedicated singleton {@link ToolRegistry} bean — {@code LocalToolsAutoConfiguration}
 * now depends on the SPI interface, not the default implementation, so a user-supplied
 * {@code ToolExecutor} provider (e.g. {@code ParallelToolExecutorProvider}) sees the
 * same registered Tools without any wiring change.
 *
 * <p><b>Disable:</b> set {@code agent.tools.enabled: false} in {@code application.yml} to
 * keep the four Tools as orphan {@code @Component}s (callable directly in tests) but to
 * skip registration with the engine dispatcher — useful when shipping a fully-remote A2A
 * agent that does not need local filesystem access. The toggle is read once via
 * {@link Environment#getProperty(String, Class, Object)} at {@link #afterPropertiesSet()}
 * time; flipping it at runtime requires a context refresh.
 *
 * <p><b>Why plain {@code @Configuration} + {@code Environment} (not {@code @AutoConfiguration}
 * with {@code @ConditionalOnProperty}):</b> {@code lingshu-core} deliberately does not
 * depend on {@code spring-boot-autoconfigure} (CLAUDE.md §11 #6, R-13 — dependency budget
 * is locked at 13 coords). Spring's plain {@code Environment} API is sufficient for this
 * one boolean toggle.
 *
 * <p><b>Why {@link InitializingBean} (not {@code @PostConstruct}):</b> the JSR-250
 * {@code javax.annotation.PostConstruct} type is <b>not</b> on the lingshu-core classpath
 * (R-13 mitigation philosophy: do not pull in {@code javax.annotation-api} / Jakarta's
 * annotation-api just for a single lifecycle hook — Story #009 used the same
 * {@code @Bean(initMethod = ...)} workaround for {@code A2aServer}). Here we use
 * Spring's own {@link InitializingBean} callback, which lives in {@code spring-beans}
 * (already a transitive of {@code spring-context} on the lingshu-core classpath) and
 * requires zero new dependencies. Story #009 narrative: "避开 {@code @PostConstruct} /
 * {@code @PreDestroy} javax.annotation 依赖,符合 R-13".
 *
 * <p><b>Bean wiring order:</b>
 * <ol>
 *   <li>Spring creates {@link ReadTool}, {@link WriteTool}, {@link EditTool},
 *       {@link BashTool} as {@code @Component}s (each with a {@link LocalToolProps} ctor arg).</li>
 *   <li>{@link #afterPropertiesSet()} fires after constructor injection; we (a) inject the
 *       tenant-aware process runner into {@link BashTool}, and (b) register all four
 *       Tools by name with the shared {@link ToolRegistry} bean.</li>
 *   <li>From this point on, any {@code ToolExecutor} (whatever the active Provider —
 *       {@code DefaultToolExecutorProvider} or a user-supplied alternative) reads
 *       these registrations through the shared registry, and the dispatch 5-step
 *       pipeline (permission → registry → timeout → sandbox → execute → checkpoint,
 *       dsh §4.6) can resolve the names {@code "Read" / "Write" / "Edit" / "Bash"} to
 *       these concrete classes.</li>
 * </ol>
 *
 * <p>Threading note: {@link InitializingBean#afterPropertiesSet()} runs on the main
 * Spring refresh thread; {@link DefaultToolRegistry#register} uses a
 * {@code ConcurrentHashMap} so concurrent dispatch threads are race-free.
 */
@Configuration
public class LocalToolsAutoConfiguration implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(LocalToolsAutoConfiguration.class);

    /** Property key — set to {@code false} in {@code application.yml} to disable local tool wiring. */
    public static final String PROP_ENABLED = "agent.tools.enabled";

    /**
     * Back-compat alias for {@link LocalToolPropertiesConfiguration#LOCAL_TOOL_PROPS_BEAN}.
     * Kept so any user code referencing {@code LocalToolsAutoConfiguration.LOCAL_TOOL_PROPS_BEAN}
     * continues to compile — the {@code @Bean} itself is now declared in
     * {@link LocalToolPropertiesConfiguration} (split out as a separate file to break the
     * circular dependency between {@code LocalToolsAutoConfiguration} and the
     * {@link ReadTool}/{@link WriteTool} constructors that need {@link LocalToolProps}).
     */
    public static final String LOCAL_TOOL_PROPS_BEAN = LocalToolPropertiesConfiguration.LOCAL_TOOL_PROPS_BEAN;

    private final ToolRegistry toolRegistry;
    private final RuntimeSandbox sandbox;
    private final BashTool bashTool;
    private final Map<String, Tool> tools;
    private final Environment environment;

    /**
     * Constructor injection of all dependencies. {@link Tool} beans are injected as a
     * {@code Map<String, Tool>} where keys are the {@code @Component} bean names
     * ({@code "readTool"}, {@code "writeTool"}, {@code "editTool"}, {@code "bashTool"}).
     *
     * <p>The first argument is the SPI-level {@link ToolRegistry} (not the concrete
     * {@code DefaultToolExecutor}) — this keeps the wiring open to any
     * {@code ToolExecutor} implementation, including user-supplied alternatives.
     *
     * <p><b>{@code @Lazy} on the {@link Map} parameter:</b> the
     * {@code LocalToolsAutoConfiguration.localToolProps()} {@code @Bean} is the upstream
     * constructor dependency of {@link ReadTool} and {@link WriteTool}. Spring's eager
     * bean-creation order therefore resolves us <em>after</em> those Tools, but those
     * Tools are themselves members of the {@code Map<String, Tool>} we receive here. The
     * resulting cycle (this {@code @Configuration} ↔ {@code readTool}/{@code writeTool})
     * is broken by injecting a {@code @Lazy} proxy for the map: Spring returns a proxy
     * placeholder that defers actual lookup until {@link #afterPropertiesSet()} iterates
     * it. By that point all Tools have been instantiated, so the proxy unblocks without
     * a cycle.
     */
    @Autowired
    public LocalToolsAutoConfiguration(
            ToolRegistry toolRegistry,
            RuntimeSandbox sandbox,
            BashTool bashTool,
            @org.springframework.context.annotation.Lazy
                    Map<String, Tool> tools,
            Environment environment) {
        this.toolRegistry = toolRegistry;
        this.sandbox = sandbox;
        this.bashTool = bashTool;
        this.tools = tools;
        this.environment = environment;
    }

    /**
     * Wire {@link BashTool}'s tenant-aware {@link RuntimeSandbox.ProcessRunner} and
     * register the four Tools with the shared {@link ToolRegistry} bean.
     * Runs once after Spring finishes bean construction but before any
     * {@code Agent.run} turn begins.
     *
     * <p>Honors the {@link #PROP_ENABLED} toggle: when set to {@code false} the Tools
     * stay constructed (callable directly in tests) but {@link ToolRegistry} does not
     * see them — model-driven calls will then return
     * {@link ai.lingshu.core.slot.ToolException.ToolNotFoundException}, which the
     * outer pipeline translates to {@code ToolResult.error("tool not found: ...")}.
     *
     * <p>Renamed from {@code registerLocalTools()} to Spring's
     * {@link InitializingBean#afterPropertiesSet()} hook — same semantics, no
     * {@code javax.annotation.PostConstruct} import required.
     */
    @Override
    public void afterPropertiesSet() {
        boolean enabled = environment.getProperty(PROP_ENABLED, Boolean.class, Boolean.TRUE);
        if (!enabled) {
            LOG.info("LocalTools disabled via {}={} — Tools constructed but NOT registered",
                PROP_ENABLED, enabled);
            return;
        }

        // 1. Wire the process runner into BashTool — the only Tool that touches
        //    RuntimeSandbox directly. If unwired, BashTool.execute returns a defensive
        //    ToolResult.error rather than NPE (see BashTool#setProcessRunner Javadoc).
        bashTool.setProcessRunner(sandbox.process());

        // 2. Register every Tool bean (readTool/writeTool/editTool/bashTool) by its
        //    Tool.name() — duplicates are logged-and-skipped by DefaultToolRegistry.
        int registered = 0;
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            Tool tool = e.getValue();
            toolRegistry.register(tool);
            registered++;
            LOG.debug("Registered local tool: beanName={} toolName={} class={}",
                e.getKey(), tool.name(), tool.getClass().getSimpleName());
        }
        LOG.info("LocalTools ready — {} tool(s) registered: {}",
            registered, collectNames());
    }

    /** Sorted tool names for the INFO log line — stable ordering aids log grep. */
    private String collectNames() {
        List<String> names = new ArrayList<>();
        for (Tool t : tools.values()) {
            names.add(t.name());
        }
        Collections.sort(names);
        return names.toString();
    }
}