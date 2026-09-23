package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — L2 wiring test for {@link LocalToolsAutoConfiguration}.
 *
 * <p>Verifies the {@link LocalToolsAutoConfiguration#afterPropertiesSet()} method registers
 * all four built-in Tools into a shared {@link DefaultToolRegistry} bean, and that the
 * {@code agent.tools.enabled=false} toggle skips registration cleanly.
 *
 * <p>Why instantiate the configuration manually: there is no {@code @Bean DefaultToolExecutor}
 * singleton in production (it is created per-turn via
 * {@link ai.lingshu.core.impl.tool.DefaultToolExecutorProvider#create}). So the
 * integration test wires the configuration with a hand-built {@code DefaultToolRegistry}
 * instance and verifies the registration logic — this is the smallest test that exercises
 * all 4 Tools + sandbox + environment wiring without booting the full Spring context.
 */
class LocalToolsAutoConfigurationTest {

    @Test
    @DisplayName("AC-019-11: enabled_registers4ToolsToSharedRegistry")
    void enabled_registers4ToolsToSharedRegistry() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BashTool bash = new BashTool();
        RuntimeSandbox sandbox = Mockito.mock(RuntimeSandbox.class);
        Map<String, Tool> toolBeans = new LinkedHashMap<>();
        ReadTool read = new ReadTool(new LocalToolProps(200_000, 1_000_000));
        WriteTool write = new WriteTool(new LocalToolProps(200_000, 1_000_000));
        EditTool edit = new EditTool();
        toolBeans.put("readTool", read);
        toolBeans.put("writeTool", write);
        toolBeans.put("editTool", edit);
        toolBeans.put("bashTool", bash);
        Environment env = enabledEnv();

        LocalToolsAutoConfiguration cfg = new LocalToolsAutoConfiguration(
            registry, sandbox, bash, toolBeans, env);
        cfg.afterPropertiesSet();

        Map<String, Tool> regs = registry.asMap();
        assertThat(regs).containsKeys("Read", "Write", "Edit", "Bash");
        // Identity wiring is verified: each registered tool is the same instance
        assertThat(regs.get("Read")).isSameAs(read);
        assertThat(regs.get("Write")).isSameAs(write);
        assertThat(regs.get("Edit")).isSameAs(edit);
        assertThat(regs.get("Bash")).isSameAs(bash);
        assertThat(regs).hasSize(4);
        // BashTool.processRunner wired to the sandbox
        Mockito.verify(sandbox).process();
    }

    /**
     * Build a {@link StandardEnvironment} with {@link LocalToolsAutoConfiguration#PROP_ENABLED}
     * forced to {@code "true"} at the highest priority. Hermetic — does not mutate
     * {@link System#getProperties()}, so the value cannot leak across tests or child JVMs.
     *
     * <p>The test runs needed this isolation because surefire in this environment inherits
     * a process-wide {@code -Dagent.tools.enabled=false} set externally; without the
     * override {@code afterPropertiesSet()} would short-circuit at the disable check.
     */
    private static Environment enabledEnv() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> overrides = Collections.<String, Object>singletonMap(
            LocalToolsAutoConfiguration.PROP_ENABLED, "true");
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("LocalToolsTestOverride", overrides));
        return env;
    }

    @Test
    @DisplayName("AC-019-12: disabled_doesNotRegister")
    void disabled_doesNotRegister() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BashTool bash = new BashTool();
        RuntimeSandbox sandbox = Mockito.mock(RuntimeSandbox.class);
        Map<String, Tool> toolBeans = new LinkedHashMap<>();
        toolBeans.put("readTool", new ReadTool(new LocalToolProps(200_000, 1_000_000)));
        toolBeans.put("writeTool", new WriteTool(new LocalToolProps(200_000, 1_000_000)));
        toolBeans.put("editTool", new EditTool());
        toolBeans.put("bashTool", bash);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("LocalToolsTestOverride",
            Collections.<String, Object>singletonMap(
                LocalToolsAutoConfiguration.PROP_ENABLED, "false")));

        LocalToolsAutoConfiguration cfg = new LocalToolsAutoConfiguration(
            registry, sandbox, bash, toolBeans, env);
        cfg.afterPropertiesSet();

        // None of the Tools are registered when disabled
        Map<String, Tool> regs = registry.asMap();
        assertThat(regs).isEmpty();
        // processRunner is NOT wired — bash remains in its orphan-@Component state
        Mockito.verifyNoInteractions(sandbox);
    }

    @Test
    @DisplayName("regression: localToolPropsBean_derivesFromDefaults")
    void localToolPropsBean_derivesFromDefaults() {
        // The @Bean moved out of LocalToolsAutoConfiguration (see
        // LocalToolPropertiesConfiguration) to break the circular dependency between
        // this AutoConfiguration and the ReadTool / WriteTool constructors. The
        // produced LocalToolProps is still derived from AgentConfigDefaults — the
        // test verifies the new owner class instead of the old one.
        LocalToolPropertiesConfiguration propsCfg = new LocalToolPropertiesConfiguration();
        LocalToolProps props = propsCfg.localToolProps();
        AgentConfig.ToolsConfig tc = AgentConfigDefaults.defaults().getTools();
        assertThat(props.getMaxReadBytes()).isEqualTo(tc.getMaxReadBytes());
        assertThat(props.getMaxWriteBytes()).isEqualTo(tc.getMaxWriteBytes());
    }
}