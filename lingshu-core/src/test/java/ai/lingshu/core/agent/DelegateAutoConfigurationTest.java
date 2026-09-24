package ai.lingshu.core.agent;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.reload.AgentConfigRegistry;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Story #023 — L2 wiring test for {@link DelegateAutoConfiguration}
 * (dsh v1.5.40 §6.6 L5054-5113).
 *
 * <p>Four cases covering:
 * <ul>
 *   <li>afterPropertiesSet with delegate block present registers DelegateTool</li>
 *   <li>afterPropertiesSet with delegate block absent skips registration (no exception)</li>
 *   <li>afterPropertiesSet with malformed delegate block (missing configKey) fail-fasts with [LINGS-D01]</li>
 *   <li>afterPropertiesSet with empty registry (no publish yet) skips registration (early-refresh race protection)</li>
 * </ul>
 *
 * <p>Both {@link AgentFactory} and {@link AgentConfigRegistry} are concrete Spring
 * {@code @Component}s — we use a no-op {@link StubAgentFactory} subclass and a
 * real {@link AgentConfigRegistry} (with {@code publish()}/{@code current()} used
 * directly) to avoid Mockito 5.x + JDK 23 inline mockmaker gap (Story #007
 * workaround pattern).
 */
class DelegateAutoConfigurationTest {

    private static AgentConfig makeParentConfig(AgentConfig.Delegate delegate) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "claude-3-5-sonnet-latest", 8192, 0.7),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), 5),
            "default",
            new AgentConfig.Sandbox("strict", "chroot", Paths.get("/tmp"),
                Arrays.asList("ls", "cat"), Collections.<String>emptyList()),
            null, null, delegate, null, null,
            8, 30, 60, 120, 30, 50,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults()
        );
    }

    private static AgentConfig.Delegate completeDelegate() {
        Map<String, AgentConfig.TypeConfig> types = new LinkedHashMap<>();
        types.put("explore",
            new AgentConfig.TypeConfig(null,
                Collections.<String>emptyList(), null, null));
        types.put("engineer",
            new AgentConfig.TypeConfig(null,
                Collections.<String>emptyList(), null, null));
        types.put("reviewer",
            new AgentConfig.TypeConfig(null,
                Collections.<String>emptyList(), null, null));
        return new AgentConfig.Delegate(Paths.get("/prompts"), types);
    }

    private static AgentConfig.Delegate incompleteDelegate() {
        // Missing engineer + reviewer
        Map<String, AgentConfig.TypeConfig> types = new LinkedHashMap<>();
        types.put("explore",
            new AgentConfig.TypeConfig(null,
                Collections.<String>emptyList(), null, null));
        return new AgentConfig.Delegate(Paths.get("/prompts"), types);
    }

    @Test
    @DisplayName("AC-023-DAC-1: afterPropertiesSet_delegatePresent_registersDelegateTool")
    void afterPropertiesSet_delegatePresent_registersDelegateTool() {
        StubAgentFactory factory = new StubAgentFactory();
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentConfigRegistry cfgRegistry = new AgentConfigRegistry();
        cfgRegistry.publish(makeParentConfig(completeDelegate()));

        DelegateAutoConfiguration cfg = new DelegateAutoConfiguration(factory, registry, cfgRegistry);
        cfg.afterPropertiesSet();

        // Verify tool registered with the canonical "Task" name
        org.mockito.ArgumentCaptor<DelegateTool> captor =
            org.mockito.ArgumentCaptor.forClass(DelegateTool.class);
        verify(registry).register(captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Task");
    }

    @Test
    @DisplayName("AC-023-DAC-2: afterPropertiesSet_delegateNull_skipsRegistration_gracefully")
    void afterPropertiesSet_delegateNull_skipsRegistration_gracefully() {
        StubAgentFactory factory = new StubAgentFactory();
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentConfigRegistry cfgRegistry = new AgentConfigRegistry();
        cfgRegistry.publish(makeParentConfig(null));

        DelegateAutoConfiguration cfg = new DelegateAutoConfiguration(factory, registry, cfgRegistry);
        // Must NOT throw — spec §5 reverse AC: "缺失即跳过"
        cfg.afterPropertiesSet();

        verify(registry, never()).register(org.mockito.ArgumentMatchers.<ai.lingshu.core.slot.Tool>any());
    }

    @Test
    @DisplayName("AC-023-DAC-3: afterPropertiesSet_malformedDelegate_failsFast_LINGS_D01")
    void afterPropertiesSet_malformedDelegate_failsFast_LINGS_D01() {
        StubAgentFactory factory = new StubAgentFactory();
        ToolRegistry registry = mock(ToolRegistry.class);
        AgentConfigRegistry cfgRegistry = new AgentConfigRegistry();
        cfgRegistry.publish(makeParentConfig(incompleteDelegate()));

        DelegateAutoConfiguration cfg = new DelegateAutoConfiguration(factory, registry, cfgRegistry);
        assertThatThrownBy(cfg::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[LINGS-D01]")
            .hasMessageContaining("missing subagent_type: engineer");
    }

    @Test
    @DisplayName("AC-023-DAC-4: afterPropertiesSet_registryHasNoCurrent_skipsRegistration")
    void afterPropertiesSet_registryHasNoCurrent_skipsRegistration() {
        StubAgentFactory factory = new StubAgentFactory();
        ToolRegistry registry = mock(ToolRegistry.class);
        // No publish — registry.current() returns null
        AgentConfigRegistry cfgRegistry = new AgentConfigRegistry();

        DelegateAutoConfiguration cfg = new DelegateAutoConfiguration(factory, registry, cfgRegistry);
        // Must NOT throw — early-refresh race protection
        cfg.afterPropertiesSet();

        verify(registry, never()).register(org.mockito.ArgumentMatchers.<ai.lingshu.core.slot.Tool>any());
    }

    // ── Test-only AgentFactory subclass (Story #007 workaround for JDK 23 + Mockito inline mockmaker) ──

    /** Test-only stub — no Router is invoked during these wiring tests. */
    static final class StubAgentFactory extends AgentFactory {
        StubAgentFactory() {
            super(null, null, null, null, null, null);
        }
    }
}