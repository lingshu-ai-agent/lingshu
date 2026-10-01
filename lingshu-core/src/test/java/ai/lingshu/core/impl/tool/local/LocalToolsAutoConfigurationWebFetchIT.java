package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
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
 * Story #032 — L2 wiring test for {@link WebFetchTool} being picked up by
 * {@link LocalToolsAutoConfiguration} alongside the existing four built-in Tools.
 *
 * <p>Mirrors {@link LocalToolsAutoConfigurationTest}'s manual-instantiation pattern
 * (no full {@code @SpringBootTest} — that would force booting a context with all
 * of Spring Boot's autoconfig, which is far heavier than needed for what is
 * effectively a registry-wiring check). The {@link LocalToolsAutoConfiguration}
 * contract under test is purely "build a {@link DefaultToolRegistry} from the
 * {@code Map<String, Tool>} Spring would inject" — exercising that contract
 * doesn't need Spring's classpath scanning.
 *
 * <p>Two cases cover AC-NN-13 (Bean registration) and AC-NN-14 (registry hit +
 * category metadata).
 */
class LocalToolsAutoConfigurationWebFetchIT {

    @Test
    @DisplayName("AC-NN-13: WebFetchTool is registered with shared ToolRegistry when included in Tool beans map")
    void webFetchTool_isRegisteredAlongsideBuiltinFour() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BashTool bash = new BashTool();
        RuntimeSandbox sandbox = Mockito.mock(RuntimeSandbox.class);

        // Spring injects a Map<String, Tool> with bean names as keys. The
        // existing 4 built-ins plus WebFetchTool — mirrors the production
        // wiring where @Component("webFetchTool") gets discovered by the
        // same @ComponentScan that picks up readTool / writeTool / editTool
        // / bashTool.
        Map<String, Tool> toolBeans = new LinkedHashMap<>();
        toolBeans.put("readTool", new ReadTool(new LocalToolProps(200_000, 1_000_000)));
        toolBeans.put("writeTool", new WriteTool(new LocalToolProps(200_000, 1_000_000)));
        toolBeans.put("editTool", new EditTool());
        toolBeans.put("bashTool", bash);
        toolBeans.put("webFetchTool", new WebFetchTool());

        Environment env = enabledEnv();
        LocalToolsAutoConfiguration cfg = new LocalToolsAutoConfiguration(
            registry, sandbox, bash, toolBeans, env);
        cfg.afterPropertiesSet();

        // All 5 Tools are now registered — the WebFetchTool shows up under
        // its Tool.name() "web_fetch", not its bean name "webFetchTool".
        Map<String, Tool> regs = registry.asMap();
        assertThat(regs).containsKeys("Read", "Write", "Edit", "Bash", "web_fetch");
        assertThat(regs).hasSize(5);
    }

    @Test
    @DisplayName("AC-NN-14: toolRegistry.findByName('web_fetch') returns the WebFetchTool + sourceCategory=local")
    void toolRegistry_findByName_returnsWebFetchTool() throws Exception {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        BashTool bash = new BashTool();
        RuntimeSandbox sandbox = Mockito.mock(RuntimeSandbox.class);

        WebFetchTool webFetch = new WebFetchTool();
        Map<String, Tool> toolBeans = new LinkedHashMap<>();
        toolBeans.put("bashTool", bash);
        toolBeans.put("webFetchTool", webFetch);

        LocalToolsAutoConfiguration cfg = new LocalToolsAutoConfiguration(
            registry, sandbox, bash, toolBeans, enabledEnv());
        cfg.afterPropertiesSet();

        // findByName mirrors the contract ToolExecutor.dispatch() uses to
        // resolve a ToolCall.name → Tool instance. findByName throws
        // IllegalArgumentException if absent (per the ToolRegistry SPI
        // contract); lookup(name) returns null — we use findByName here
        // because we expect a successful hit.
        Tool resolved = registry.findByName("web_fetch");
        assertThat(resolved).isSameAs(webFetch);
        // Category metadata feeds StrictPermissionPolicy's "<category>:*" pattern
        // matching (Story #031); webFetchTool must default to "local" since
        // it does NOT override Tool.sourceCategory() — same convention as
        // Read / Write / Edit / Bash.
        assertThat(resolved.sourceCategory()).isEqualTo("local");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static Environment enabledEnv() {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> overrides = Collections.<String, Object>singletonMap(
            LocalToolsAutoConfiguration.PROP_ENABLED, "true");
        MutablePropertySources sources = env.getPropertySources();
        sources.addFirst(new MapPropertySource("WebFetchTestOverride", overrides));
        return env;
    }
}
