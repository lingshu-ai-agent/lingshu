package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story #019 — L2 wiring test for {@link LocalToolsAutoConfiguration}.
 *
 * <p>Verifies the {@link LocalToolsAutoConfiguration#registerLocalTools()} method registers
 * all four built-in Tools into a {@link DefaultToolExecutor} registry, and that the
 * {@code agent.tools.enabled=false} toggle skips registration cleanly.
 *
 * <p>Why instantiate the configuration manually: there is no {@code @Bean DefaultToolExecutor}
 * singleton in production (it is created per-turn via
 * {@link ai.lingshu.core.impl.tool.DefaultToolExecutorProvider#create}). So the
 * integration test wires the configuration with a hand-built executor instance and
 * verifies the registration logic — this is the smallest test that exercises all 4
 * Tools + sandbox + environment wiring without booting the full Spring context.
 */
class LocalToolsAutoConfigurationTest {

    @Test
    @DisplayName("AC-019-11: enabled_registers4ToolsToDefaultToolExecutor")
    void enabled_registers4ToolsToDefaultToolExecutor() throws Exception {
        DefaultToolExecutor exec = new DefaultToolExecutor(new AllowAllPermissionPolicy());
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
        Environment env = new StandardEnvironment();

        LocalToolsAutoConfiguration cfg = new LocalToolsAutoConfiguration(
            exec, sandbox, bash, toolBeans, env);
        cfg.registerLocalTools();

        Map<String, Tool> registry = readRegistry(exec);
        assertThat(registry).containsKeys("Read", "Write", "Edit", "Bash");
        // Identity wiring is verified: each registered tool is the same instance
        assertThat(registry.get("Read")).isSameAs(read);
        assertThat(registry.get("Write")).isSameAs(write);
        assertThat(registry.get("Edit")).isSameAs(edit);
        assertThat(registry.get("Bash")).isSameAs(bash);
        assertThat(registry).hasSize(4);
        // BashTool.processRunner wired to the sandbox
        Mockito.verify(sandbox).process();
    }

    @Test
    @DisplayName("AC-019-12: disabled_doesNotRegister")
    void disabled_doesNotRegister() throws Exception {
        DefaultToolExecutor exec = new DefaultToolExecutor(new AllowAllPermissionPolicy());
        BashTool bash = new BashTool();
        RuntimeSandbox sandbox = Mockito.mock(RuntimeSandbox.class);
        Map<String, Tool> toolBeans = new LinkedHashMap<>();
        toolBeans.put("readTool", new ReadTool(new LocalToolProps(200_000, 1_000_000)));
        toolBeans.put("writeTool", new WriteTool(new LocalToolProps(200_000, 1_000_000)));
        toolBeans.put("editTool", new EditTool());
        toolBeans.put("bashTool", bash);
        StandardEnvironment env = new StandardEnvironment();
        env.getSystemProperties().put(LocalToolsAutoConfiguration.PROP_ENABLED, "false");

        LocalToolsAutoConfiguration cfg = new LocalToolsAutoConfiguration(
            exec, sandbox, bash, toolBeans, env);
        cfg.registerLocalTools();

        // None of the Tools are registered when disabled
        Map<String, Tool> registry = readRegistry(exec);
        assertThat(registry).isEmpty();
        // processRunner is NOT wired — bash remains in its orphan-@Component state
        Mockito.verifyNoInteractions(sandbox);
    }

    @Test
    @DisplayName("regression: localToolPropsBean_derivesFromDefaults")
    void localToolPropsBean_derivesFromDefaults() {
        DefaultToolExecutor exec = new DefaultToolExecutor(new AllowAllPermissionPolicy());
        BashTool bash = new BashTool();
        RuntimeSandbox sandbox = Mockito.mock(RuntimeSandbox.class);
        Map<String, Tool> toolBeans = new LinkedHashMap<>();
        toolBeans.put("readTool", new ReadTool(new LocalToolProps(200_000, 1_000_000)));
        toolBeans.put("bashTool", bash);
        Environment env = new StandardEnvironment();

        LocalToolsAutoConfiguration cfg = new LocalToolsAutoConfiguration(
            exec, sandbox, bash, toolBeans, env);

        assertThatCode(cfg::localToolProps).doesNotThrowAnyException();
        LocalToolProps props = cfg.localToolProps();
        assertThat(props.getMaxReadBytes()).isEqualTo(200_000);
        assertThat(props.getMaxWriteBytes()).isEqualTo(1_000_000);
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Reflectively read the private {@code registry} field of {@link DefaultToolExecutor}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Tool> readRegistry(DefaultToolExecutor exec) throws Exception {
        Field f = DefaultToolExecutor.class.getDeclaredField("registry");
        f.setAccessible(true);
        return (Map<String, Tool>) f.get(exec);
    }
}