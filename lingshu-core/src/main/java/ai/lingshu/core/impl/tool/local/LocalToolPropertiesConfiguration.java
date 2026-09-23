package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Story #019 — exposes the single shared {@link LocalToolProps} bean that
 * {@link ReadTool} and {@link WriteTool} take as a constructor argument.
 *
 * <p><b>Why this lives in its own file (not in {@code LocalToolsAutoConfiguration}):</b>
 * the circular-dependency trap. {@link ReadTool} / {@link WriteTool} both depend on
 * {@link LocalToolProps}, and at the same time {@code LocalToolsAutoConfiguration}
 * depends on the {@code Map<String, Tool>} that contains both — Spring's strict
 * eager bean instantiation could not break that cycle. Lifting the
 * {@code @Bean localToolProps()} declaration up into a separate
 * {@code @Configuration} places the Tool-side dependency on a different bean
 * factory, breaking the cycle while still producing exactly one shared
 * {@link LocalToolProps} instance.
 *
 * <p>Why plain {@code @Configuration} (not {@code @AutoConfiguration}): see the
 * same rationale in {@link LocalToolsAutoConfiguration} — {@code lingshu-core}
 * does not depend on {@code spring-boot-autoconfigure} (CLAUDE.md §11 #6, R-13).
 *
 * <p>Bean name follows §5.4 unique-name convention (multi-Provider mode, v1.5.28)
 * — same {@link #LOCAL_TOOL_PROPS_BEAN} constant as before, so any external
 * reference stays stable.
 */
@Configuration
public class LocalToolPropertiesConfiguration {

    /** 🆕 Bean name follows §5.4 unique-name convention (multi-Provider mode, v1.5.28). */
    public static final String LOCAL_TOOL_PROPS_BEAN = "localToolProps";

    /**
     * Single shared {@link LocalToolProps} derived from
     * {@link AgentConfigDefaults#defaults()} — kept without an
     * {@code AgentConfig} bean dependency so the Slot core stays free of any
     * config type. Real deployments override the byte caps via
     * {@code application.yml}; the {@code AgentConfig.ToolsConfig#validate()}
     * hook fires in {@code AgentFactory.create(...)} so a misconfig surfaces
     * as a {@code LingsConfigException("C02")} before the first turn.
     */
    @Bean(name = LOCAL_TOOL_PROPS_BEAN)
    public LocalToolProps localToolProps() {
        return LocalToolProps.from(AgentConfigDefaults.defaults());
    }
}
