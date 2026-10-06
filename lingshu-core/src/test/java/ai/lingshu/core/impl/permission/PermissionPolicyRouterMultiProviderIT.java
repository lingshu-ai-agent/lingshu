package ai.lingshu.core.impl.permission;

import ai.lingshu.core.impl.router.Routers.PermissionPolicyRouter;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.ToolRegistry;
import ai.lingshu.core.spi.Providers.PermissionPolicyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #037 — L2 integration tests for the v1.5.28 §5.5 multi-Provider alignment
 * of Slot 4 {@link PermissionPolicyProvider} (5 cases).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>{@code resolve("default", cfg)} → {@link AllowAllPermissionPolicy}
 *       (US2-AS1 — confirms Story #037 explicit {@code @Bean(name="permissionPolicyProvider_default-1.0.0")}
 *       produces a functional Provider that the Router can resolve by name);</li>
 *   <li>{@code resolve("strict", cfg)} → {@link StrictPermissionPolicy} (US2-AS2 —
 *       Story #029 back-compat);</li>
 *   <li>{@code resolve("ask", cfg)} → {@link AskUserPermissionPolicy} (US2-AS3 —
 *       Story #030 back-compat);</li>
 *   <li>The Router's internal {@code Map<String, Provider>} (built from the
 *       Spring-injected Provider list) contains all 3 entries simultaneously
 *       (US2-AS4 — proves the v1.5.28 multi-Provider pattern works for all 3
 *       Beans under the new naming convention);</li>
 *   <li>{@link AllowAllPermissionPolicyProvider} no longer carries
 *       {@code @Component} (US2-AS5 — proves the Story #001 {@code @Component}
 *       annotation has been removed so Spring doesn't auto-register a
 *       duplicate Bean named {@code "allowAllPermissionPolicyProvider"}).</li>
 * </ul>
 *
 * <p>Uses the same direct-construction pattern as sibling ITs
 * ({@link PermissionPolicyRouterStrictIT}, {@link PermissionPolicyRouterAskIT})
 * — no {@code @SpringBootTest} needed because the
 * {@link PermissionPolicyAutoConfiguration} contract under test is purely
 * "given a list of Providers, the Router resolves by name and the class is
 * not Spring-managed". The full Spring context wiring is exercised by
 * {@code T-37-12~14} (demo-product {@code permission-policy: default|strict|ask}
 * yml back-compat).
 */
class PermissionPolicyRouterMultiProviderIT {

    private static AgentConfig.ToolsConfig emptyTools() {
        return new AgentConfig.ToolsConfig(
            true,
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            200_000, 1_000_000);
    }

    private static AgentConfig cfg() {
        return new AgentConfig(
            "linear", null, null, "default", null, "default", "default",
            null, null, null,
            8, 60, 0, 0, 60, 50,
            null, null, null,
            "default", null, null, null,
            emptyTools(),
                        "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    /**
     * Build the 3 Providers exactly the way {@link PermissionPolicyAutoConfiguration}
     * would after Story #037 lands — call the 3 {@code @Bean} methods directly
     * (with a real {@link DefaultToolRegistry} for the strict / ask providers that
     * need it). This mirrors the Spring container's bean-wiring without needing
     * {@code @SpringBootTest}.
     */
    private static PermissionPolicyRouter routerViaAutoConfiguration() {
        ToolRegistry registry = new DefaultToolRegistry();
        PermissionPolicyAutoConfiguration cfg = new PermissionPolicyAutoConfiguration();
        // Set the @Autowired toolRegistry field via reflection (mimics Spring
        // field-injection that happens before @Bean method invocation).
        try {
            Field f = PermissionPolicyAutoConfiguration.class.getDeclaredField("toolRegistry");
            f.setAccessible(true);
            f.set(cfg, registry);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to set toolRegistry via reflection", e);
        }
        PermissionPolicyProvider def = cfg.defaultPermissionPolicyProvider();
        PermissionPolicyProvider strict = cfg.strictPermissionPolicyProvider();
        PermissionPolicyProvider ask = cfg.askUserPermissionPolicyProvider();
        return new PermissionPolicyRouter(Arrays.asList(def, strict, ask));
    }

    @Test
    @DisplayName("US2-AS1: resolve_default_returnsAllowAllPolicy")
    void resolve_default_returnsAllowAllPolicy() {
        PermissionPolicyRouter router = routerViaAutoConfiguration();

        PermissionPolicy policy = router.resolve("default", cfg());

        assertThat(policy).isInstanceOf(AllowAllPermissionPolicy.class);
    }

    @Test
    @DisplayName("US2-AS2: resolve_strict_returnsStrictPolicy")
    void resolve_strict_returnsStrictPolicy() {
        PermissionPolicyRouter router = routerViaAutoConfiguration();

        PermissionPolicy policy = router.resolve("strict", cfg());

        assertThat(policy).isInstanceOf(StrictPermissionPolicy.class);
    }

    @Test
    @DisplayName("US2-AS3: resolve_ask_returnsAskUserPolicy")
    void resolve_ask_returnsAskUserPolicy() {
        PermissionPolicyRouter router = routerViaAutoConfiguration();

        PermissionPolicy policy = router.resolve("ask", cfg());

        assertThat(policy).isInstanceOf(AskUserPermissionPolicy.class);
    }

    @Test
    @DisplayName("US2-AS4: routerInternalMap_containsAllThreeProviders")
    @SuppressWarnings("unchecked")
    void routerInternalMap_containsAllThreeProviders() throws Exception {
        PermissionPolicyRouter router = routerViaAutoConfiguration();

        // The parent SlotRouter stores providers in a private final Map<String, P> byName.
        Field byNameField = router.getClass().getSuperclass().getDeclaredField("byName");
        byNameField.setAccessible(true);
        Map<String, PermissionPolicyProvider> byName =
            (Map<String, PermissionPolicyProvider>) byNameField.get(router);

        assertThat(byName)
            .as("Router's internal byName map must contain all 3 Providers")
            .hasSize(3)
            .containsKeys("default", "strict", "ask");
    }

    @Test
    @DisplayName("US2-AS5: allowAllProviderClass_hasNoComponentAnnotation")
    void allowAllProviderClass_hasNoComponentAnnotation() {
        // AC-37-2: AllowAllPermissionPolicyProvider must no longer be @Component
        // — registration is exclusively via @Bean(name="permissionPolicyProvider_default-1.0.0").
        Component annotation = AllowAllPermissionPolicyProvider.class
            .getDeclaredAnnotation(Component.class);

        assertThat(annotation)
            .as("AllowAllPermissionPolicyProvider must not be @Component (Story #037 migration)")
            .isNull();
    }
}
