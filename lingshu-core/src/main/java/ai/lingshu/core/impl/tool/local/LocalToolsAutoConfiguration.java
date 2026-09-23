package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import javax.annotation.PostConstruct;
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
 * {@link DefaultToolExecutor} registry + injects the sandbox-aware {@code ProcessRunner}
 * into {@link BashTool}.
 *
 * <p><b>Disable:</b> set {@code agent.tools.enabled: false} in {@code application.yml} to
 * keep the four Tools as orphan {@code @Component}s (callable directly in tests) but to
 * skip registration with the engine dispatcher — useful when shipping a fully-remote A2A
 * agent that does not need local filesystem access. The toggle is read once via
 * {@link Environment#getProperty(String, Class, Object)} at {@link PostConstruct} time;
 * flipping it at runtime requires a context refresh.
 *
 * <p><b>Why plain {@code @Configuration} + {@code Environment} (not {@code @AutoConfiguration}
 * with {@code @ConditionalOnProperty}):</b> {@code lingshu-core} deliberately does not
 * depend on {@code spring-boot-autoconfigure} (CLAUDE.md §11 #6, R-13 — dependency budget
 * is locked at 13 coords). Spring's plain {@code Environment} API is sufficient for this
 * one boolean toggle.
 *
 * <p><b>Bean wiring order:</b>
 * <ol>
 *   <li>Spring creates {@link ReadTool}, {@link WriteTool}, {@link EditTool},
 *       {@link BashTool} as {@code @Component}s (each with a {@link LocalToolProps} ctor arg).</li>
 *   <li>{@code @PostConstruct} below fires after construction; we then (a) wire the
 *       tenant-aware process runner into {@link BashTool}, and (b) register all four
 *       Tools by name with the shared {@link DefaultToolExecutor} registry.</li>
 *   <li>From this point on, the {@link DefaultToolExecutor#dispatch} 5-step pipeline
 *       (permission → registry → timeout → sandbox → execute → checkpoint, dsh §4.6)
 *       can resolve the names {@code "Read" / "Write" / "Edit" / "Bash"} to these concrete
 *       classes.</li>
 * </ol>
 *
 * <p>Threading note: {@code @PostConstruct} runs on the main Spring refresh thread;
 * {@link DefaultToolExecutor#register} uses a {@code ConcurrentHashMap} so concurrent
 * dispatch threads are race-free.
 */
@Configuration
public class LocalToolsAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(LocalToolsAutoConfiguration.class);

    /** Property key — set to {@code false} in {@code application.yml} to disable local tool wiring. */
    public static final String PROP_ENABLED = "agent.tools.enabled";

    /** 🆕 Bean name follows §5.4 unique-name convention (multi-Provider mode, v1.5.28). */
    public static final String LOCAL_TOOL_PROPS_BEAN = "localToolProps";

    private final DefaultToolExecutor toolExecutor;
    private final RuntimeSandbox sandbox;
    private final BashTool bashTool;
    private final Map<String, Tool> tools;
    private final Environment environment;

    /**
     * Constructor injection of all dependencies. {@link Tool} beans are injected as a
     * {@code Map<String, Tool>} where keys are the {@code @Component} bean names
     * ({@code "readTool"}, {@code "writeTool"}, {@code "editTool"}, {@code "bashTool"}).
     */
    @Autowired
    public LocalToolsAutoConfiguration(
            DefaultToolExecutor toolExecutor,
            RuntimeSandbox sandbox,
            BashTool bashTool,
            Map<String, Tool> tools,
            Environment environment) {
        this.toolExecutor = toolExecutor;
        this.sandbox = sandbox;
        this.bashTool = bashTool;
        this.tools = tools;
        this.environment = environment;
    }

    /**
     * Expose {@link LocalToolProps} as a single Spring bean so {@link ReadTool} and
     * {@link WriteTool} (which require byte caps in their constructors) share one
     * immutable instance. Derives from {@link AgentConfigDefaults#defaults()} so the
     * bean can be created without depending on a process-wide {@code AgentConfig} bean
     * (the Slot core — Tool interface, DefaultToolExecutor — never imports AgentConfig).
     *
     * <p>Real deployments override the byte caps via {@code application.yml}; the
     * {@code AgentConfig.ToolsConfig#validate()} hook fires in
     * {@code AgentFactory.create(...)} so a misconfig surfaces as a
     * {@code LingsConfigException("C02")} before the first turn — the bean below is a
     * fallback only and is read at module-build, not turn-build.
     */
    @Bean(name = LOCAL_TOOL_PROPS_BEAN)
    public LocalToolProps localToolProps() {
        return LocalToolProps.from(AgentConfigDefaults.defaults());
    }

    /**
     * Wire {@link BashTool}'s tenant-aware {@link RuntimeSandbox.ProcessRunner} and
     * register the four Tools with the engine's {@link DefaultToolExecutor} registry.
     * Runs once after Spring finishes bean construction but before any
     * {@code Agent.run} turn begins.
     *
     * <p>Honors the {@link #PROP_ENABLED} toggle: when set to {@code false} the Tools
     * stay constructed (callable directly in tests) but {@link DefaultToolExecutor}
     * does not see them — model-driven calls will then return
     * {@link ai.lingshu.core.slot.ToolException.ToolNotFoundException}, which the
     * outer pipeline translates to {@code ToolResult.error("tool not found: ...")}.
     */
    @PostConstruct
    public void registerLocalTools() {
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
        //    Tool.name() — duplicates are logged-and-skipped by DefaultToolExecutor.
        int registered = 0;
        for (Map.Entry<String, Tool> e : tools.entrySet()) {
            Tool tool = e.getValue();
            toolExecutor.register(tool);
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