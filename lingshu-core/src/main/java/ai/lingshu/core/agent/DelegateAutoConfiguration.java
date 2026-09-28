package ai.lingshu.core.agent;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.reload.AgentConfigRegistry;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

/**
 * Story #023 — Spring wiring for {@link DelegateTool} (dsh v1.5.40 §6.6 L5054-5113).
 *
 * <h3>What</h3>
 * <p>Reads the current {@link AgentConfig} from {@link AgentConfigRegistry} at
 * {@link #afterPropertiesSet()} time. When {@code agent.delegate} is present
 * (and registry has a config), constructs a {@link DelegateTool} and registers
 * it with the shared {@link ToolRegistry}.
 *
 * <h3>Why {@code InitializingBean} (cf. LocalToolsAutoConfiguration)</h3>
 * <p>Aligned with the Story #019 + #020a + #020b convention:
 * {@code @Configuration} + {@link InitializingBean} avoids the
 * {@code javax.annotation.PostConstruct} import (R-13 mitigation —
 * {@code spring-beans} is already a transitive, no new deps).
 *
 * <h3>Why a guard rather than unconditional registration</h3>
 * <p>Spec §5 reverse AC: <i>"agent.delegate 配置存在即启用,缺失即跳过"</i>.
 * {@code delegate == null} means the user did not configure the delegate
 * block, which is the common case — empty {@code application.yml} must boot
 * (Story #001 AC-01-2). Unconditional registration would force every
 * {@code DelegateTool} to depend on yml always populating {@code agent.delegate}.
 *
 * <p>The same gate also protects against the early-refresh race:
 * {@link AgentConfigRegistry#current()} returns {@code null} until something
 * (typically the CLI main or a {@code @Bean} factory) calls
 * {@link AgentConfigRegistry#publishInitial(AgentConfig)}. If the registry has
 * no config yet, we log and skip — the user can wire {@link DelegateTool} via
 * a programmatic {@code @Bean} definition (Story #023.1 follow-up covers a
 * fully yml-driven path with an {@link AgentConfigRegistry#addListener reload
 * hook}).
 *
 * <h3>Threading</h3>
 * <p>{@link InitializingBean#afterPropertiesSet()} runs on the main Spring
 * refresh thread. {@link ToolRegistry#register(Tool)} (per
 * {@code DefaultToolRegistry}) uses a {@code ConcurrentHashMap} so concurrent
 * dispatch threads are race-free.
 *
 * <h3>Why plain {@code @Configuration} (not {@code @AutoConfiguration})</h3>
 * <p>{@code lingshu-core} deliberately does <b>not</b> depend on
 * {@code spring-boot-autoconfigure} (CLAUDE.md §11 #6, R-13 — dependency
 * budget locked at 13 coords). The configuration is bootstrap-automatic
 * because {@code @Component} scan + {@code @Configuration} processing both
 * run during context refresh without the autoconfig extension.
 *
 * @see DelegateTool
 * @see DelegateErrorCodes
 * @since 1.0.0
 */
@Configuration
public class DelegateAutoConfiguration implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(DelegateAutoConfiguration.class);

    private final AgentFactory agentFactory;
    private final ToolRegistry toolRegistry;
    private final AgentConfigRegistry registryCfg;

    /**
     * Constructor injection of all dependencies. {@link AgentFactory} and
     * {@link ToolRegistry} are shared singletons; {@link AgentConfigRegistry}
     * holds the current {@link AgentConfig} (Story #007 hot-reload source).
     */
    @Autowired
    public DelegateAutoConfiguration(AgentFactory agentFactory,
                                    ToolRegistry toolRegistry,
                                    AgentConfigRegistry registryCfg) {
        this.agentFactory = agentFactory;
        this.toolRegistry = toolRegistry;
        this.registryCfg = registryCfg;
    }

    /**
     * Build & register a {@link DelegateTool} when (and only when)
     * {@link AgentConfigRegistry#current()} returns a config carrying a
     * non-null {@code agent.delegate} block. Otherwise log at INFO and return,
     * per spec §5 reverse AC "缺失即跳过".
     */
    @Override
    public void afterPropertiesSet() {
        AgentConfig current = registryCfg.current();
        if (current == null) {
            LOG.info("DelegateTool wiring skipped — AgentConfigRegistry has no current "
                + "config yet (publishInitial not called). To enable delegate, "
                + "loadAgentConfig() before refresh or wire DelegateTool programmatically.");
            return;
        }
        AgentConfig.Delegate delegate = current.getDelegate();
        if (delegate == null) {
            LOG.info("DelegateTool wiring skipped — agent.delegate block absent in current "
                + "config (set agent.delegate.types.<explore|engineer|reviewer> to enable).");
            return;
        }
        try {
            DelegateTool tool = new DelegateTool(agentFactory, current, delegate);
            toolRegistry.register(tool);
            LOG.info("DelegateTool registered — name={} subagent_types={} promptsDir={}",
                tool.name(),
                SubAgentType.allKeys(),
                delegate.getPromptsDir());
        } catch (IllegalStateException ex) {
            // LINGS-D01 — propagate to fail-fast the application context refresh;
            // the message already contains "[LINGS-D01]" + the missing key for
            // actionable diagnosis.
            LOG.error("DelegateTool wiring failed ({}). "
                + "Agent config has malformed agent.delegate.types block.", DelegateErrorCodes.LINGS_D01);
            throw ex;
        }
    }
}
