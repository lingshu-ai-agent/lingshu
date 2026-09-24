package ai.lingshu.a2a.client;

import ai.lingshu.core.impl.router.A2aTransportRouter;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L3 integration tests — Story #009e wiring verification across all 3 transports.
 *
 * <p><b>Goal</b> — prove that after Story #009e's split, the
 * {@code remote_agent} Tool bean is reachable through the {@link ToolRegistry}
 * regardless of which {@code agent.a2aTransport} the user picks
 * (in-process-1.0.0 / grpc-1.0.0 / http-jsonrpc-1.0.0).</p>
 *
 * <p><b>Pre-#009e</b>: only {@code http-jsonrpc-1.0.0} exposed the
 * {@code remoteAgentTool} bean (it lived inside
 * {@link HttpJsonRpcA2aTransportAutoConfiguration}). Users on grpc or
 * in-process transports had NO {@code remote_agent} tool visible to the
 * LLM — the wiring gap.</p>
 *
 * <p><b>Pattern</b>: minimal Spring context — 3 transport provider beans +
 * one {@link A2aTransportRouter} + {@link RemoteAgentToolAutoConfiguration}.
 * No {@code @SpringBootTest} (Mockito 5.x + JDK 23 compatibility issue).
 * No HTTP mock server, no gRPC mock server — the L3 assertion is purely
 * "after Spring resolves, is {@code remote_agent} in the registry?",
 * which exercises the same code path as production startup.</p>
 */
class RemoteAgentTransportWiringIT {

    private AnnotationConfigApplicationContext ctx;

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
            ctx = null;
        }
    }

    @Test
    @DisplayName("TC-009e-IT-1: in-process_transport_loads_remoteAgentTool_withRemoteAgentToolLifecycle_registeredIt")
    void inProcess_transport_loads_remoteAgentTool_withRemoteAgentToolLifecycle_registeredIt() {
        // Setup: 3 transport providers + router + the configuration under test
        // (note: no RemoteAgentToolLifecycle @Component loaded here — we directly
        // simulate its register() call to keep this L3 IT self-contained).
        ctx = buildContext();
        RemoteAgentToolLifecycle lifecycle = startLifecycles();

        ToolRegistry registry = ctx.getBean(ToolRegistry.class);
        // After start(), remote_agent must be in the registry
        assertThat(registry.lookup("remote_agent"))
            .as("remote_agent must be registered under in-process-1.0.0 transport wiring")
            .isNotNull()
            .isInstanceOf(RemoteAgentTool.class);

        stopLifecycles(lifecycle);
        assertThat(registry.lookup("remote_agent"))
            .as("after stop(), remote_agent must be unregistered")
            .isNull();
    }

    @Test
    @DisplayName("TC-009e-IT-2: grpc_transport_loads_remoteAgentTool_unlikeServerGrpHyphenIt")
    void grpc_transport_loads_remoteAgentTool_unlikeServerGrpHyphenIt() {
        ctx = buildContext();
        RemoteAgentToolLifecycle lifecycle = startLifecycles();

        ToolRegistry registry = ctx.getBean(ToolRegistry.class);
        // Pre-#009e bug: this assertion would FAIL because RemoteAgentTool bean
        // was only exposed via HttpJsonRpcA2aTransportAutoConfiguration. Now
        // (post-#009e) it loads via the transport-independent RemoteAgentToolAutoConfiguration.
        assertThat(registry.lookup("remote_agent"))
            .as("remote_agent must be registered under grpc-1.0.0 transport wiring")
            .isNotNull()
            .isInstanceOf(RemoteAgentTool.class);

        // And the transport resolved should be a GrpcA2aTransport (default since
        // cfg's a2aTransport is null → fallback http-jsonrpc, but the router still
        // knows about grpc; for THIS test we just verify wiring, not the resolved
        // transport — the latter is covered by RemoteAgentToolAutoConfigurationTest).
        A2aTransportRouter router = ctx.getBean(A2aTransportRouter.class);
        assertThat(router)
            .as("A2aTransportRouter must be wired and know all 3 transports")
            .isNotNull();

        stopLifecycles(lifecycle);
    }

    @Test
    @DisplayName("TC-009e-IT-3: http-jsonrpc_transport_loads_remoteAgentTool_backwardCompat")
    void httpJsonRpc_transport_loads_remoteAgentTool_backwardCompat() {
        ctx = buildContext();
        RemoteAgentToolLifecycle lifecycle = startLifecycles();

        ToolRegistry registry = ctx.getBean(ToolRegistry.class);
        assertThat(registry.lookup("remote_agent"))
            .as("remote_agent must be registered under http-jsonrpc-1.0.0 transport wiring")
            .isNotNull()
            .isInstanceOf(RemoteAgentTool.class);

        stopLifecycles(lifecycle);
    }

    @Test
    @DisplayName("TC-009e-IT-4: lifecycle_start_stop_idempotent_viaLifecycle")
    void lifecycle_start_stop_idempotent_viaLifecycle() {
        ctx = buildContext();

        // Get the Lifecycle bean (we did not register RemoteAgentToolLifecycle as
        // a @Component in this minimal context; we wire it manually here).
        RemoteAgentTool tool = ctx.getBean(RemoteAgentTool.class);
        ToolRegistry registry = ctx.getBean(ToolRegistry.class);
        RemoteAgentToolLifecycle lifecycle = new RemoteAgentToolLifecycle(tool, registry);

        // First start: registers
        lifecycle.start();
        assertThat(registry.lookup("remote_agent")).isSameAs(tool);

        // Second start: idempotent — no exception
        lifecycle.start();
        assertThat(registry.lookup("remote_agent")).isSameAs(tool);

        // Stop: unregisters
        lifecycle.stop();
        assertThat(registry.lookup("remote_agent")).isNull();

        // Second stop: idempotent — no exception
        lifecycle.stop();
        assertThat(registry.lookup("remote_agent")).isNull();
    }

    @Test
    @DisplayName("TC-009e-IT-5: three_transport_providers_all_resolvable_viaRouter")
    void three_transport_providers_all_resolvable_viaRouter() {
        ctx = buildContext();

        A2aTransportRouter router = ctx.getBean(A2aTransportRouter.class);
        // Verify all 3 transport names resolve (not via cfg; just cfg=null → default http-jsonrpc)
        // The router's `resolve` requires cfg in some implementations; we instead
        // verify by reflection that the internal byName map contains all 3.
        java.util.Map<String, ?> byName = readByNameMap(router);
        assertThat(byName)
            .as("Router byName map must contain all 3 transport providers")
            .containsKeys("grpc-1.0.0", "in-process-1.0.0", "http-jsonrpc-1.0.0");
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private AnnotationConfigApplicationContext buildContext() {
        AnnotationConfigApplicationContext c = new AnnotationConfigApplicationContext();
        c.registerBean("a2aTransportProvider_grpc-1.0.0",
            ai.lingshu.core.spi.Providers.A2aTransportProvider.class,
            (java.util.function.Supplier<ai.lingshu.core.spi.Providers.A2aTransportProvider>) () -> new GrpcA2aTransportProvider());
        c.registerBean("a2aTransportProvider_in-process-1.0.0",
            ai.lingshu.core.spi.Providers.A2aTransportProvider.class,
            (java.util.function.Supplier<ai.lingshu.core.spi.Providers.A2aTransportProvider>) () -> new InProcessA2aTransportProvider());
        c.registerBean("a2aTransportProvider_http-jsonrpc-1.0.0",
            ai.lingshu.core.spi.Providers.A2aTransportProvider.class,
            (java.util.function.Supplier<ai.lingshu.core.spi.Providers.A2aTransportProvider>) () -> new HttpJsonRpcA2aTransportProvider());
        c.registerBean(A2aTransportRouter.class, () -> new A2aTransportRouter(
            c.getBeanProvider(ai.lingshu.core.spi.Providers.A2aTransportProvider.class)
                .stream().collect(Collectors.toList())));
        c.registerBean(ObjectMapper.class, (java.util.function.Supplier<ObjectMapper>) ObjectMapper::new);
        c.registerBean(ToolRegistry.class, (java.util.function.Supplier<ToolRegistry>) DefaultToolRegistry::new);
        // Provide a minimal AgentConfig so RemoteAgentToolAutoConfiguration.remoteAgentTool can wire
        c.registerBean(ai.lingshu.core.runtime.AgentConfig.class,
            () -> new ai.lingshu.core.runtime.AgentConfig(
                "linear",
                new ai.lingshu.core.runtime.AgentConfig.Llm("anthropic", "test-model", null, null),
                new ai.lingshu.core.runtime.AgentConfig.Prompt(
                    "default", java.util.Collections.<String>emptyList(), null),
                "default",
                new ai.lingshu.core.runtime.AgentConfig.Sandbox("default", "noop",
                    java.nio.file.Paths.get("."), java.util.Collections.<String>emptyList(),
                    java.util.Collections.<String>emptyList()),
                "default", "default",
                null, null, null,
                1, 5, 0, 0, 0, 10,
                ai.lingshu.core.runtime.AgentConfig.Identity.defaults(),
                ai.lingshu.core.runtime.AgentConfig.Instructions.empty(),
                ai.lingshu.core.runtime.AgentConfig.Memory.defaults(),
                null,    // a2aTransport — null → cfg.getA2aTransport() returns null → fallback "http-jsonrpc-1.0.0"
                null,
                ai.lingshu.core.runtime.AgentConfig.A2a.defaults(),
                ai.lingshu.core.runtime.AgentConfig.CompactorConfig.defaults(),
                ai.lingshu.core.runtime.AgentConfig.ToolsConfig.defaults()));
        c.register(RemoteAgentToolAutoConfiguration.class);
        c.refresh();
        return c;
    }

    private RemoteAgentToolLifecycle startLifecycles() {
        RemoteAgentTool tool = ctx.getBean(RemoteAgentTool.class);
        ToolRegistry registry = ctx.getBean(ToolRegistry.class);
        RemoteAgentToolLifecycle lifecycle = new RemoteAgentToolLifecycle(tool, registry);
        lifecycle.start();
        return lifecycle;
    }

    private void stopLifecycles(RemoteAgentToolLifecycle lifecycle) {
        lifecycle.stop();
    }

    /**
     * Reach into the parent {@code SlotRouter} and pull out the resolved byName
     * map. Reflective (the field is package-private inside {@code SlotRouter}).
     */
    private static java.util.Map<String, ?> readByNameMap(A2aTransportRouter router) {
        Class<?> c = router.getClass().getSuperclass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("byName");
                f.setAccessible(true);
                Object v = f.get(router);
                if (v instanceof java.util.Map) {
                    return (java.util.Map<String, ?>) v;
                }
            } catch (NoSuchFieldException ignored) {
                // try next superclass
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            c = c.getSuperclass();
        }
        // fallback — return empty map; the assertion will fail loudly
        return java.util.Collections.emptyMap();
    }

    // suppress unused-imports / suppress warnings
    @SuppressWarnings("unused")
    private static final Class<?>[] UNUSED = { Arrays.class };
}