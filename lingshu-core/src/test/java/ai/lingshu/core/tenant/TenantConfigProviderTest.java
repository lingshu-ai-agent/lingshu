package ai.lingshu.core.tenant;

import ai.lingshu.core.impl.tenant.YamlTenantConfigProvider;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #006 — L1 unit tests for {@link TenantConfigProvider} /
 * {@link YamlTenantConfigProvider}.
 *
 * <p>Covers FR-008 (SPI contract) and the empty-yaml fallback (US6 S3).
 */
class TenantConfigProviderTest {

    private static AgentConfig withTenants(Map<String, TenantConfig> tenantsMap) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            "default",
            "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,                                       // a2aTransport
            new AgentConfig.TenantsConfig(
                tenantsMap != null && !tenantsMap.isEmpty(),
                tenantsMap == null ? Collections.<String, TenantConfig>emptyMap() : tenantsMap),
            AgentConfig.A2a.defaults(), // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults()); // compactorConfig (Story #018)
    }

    private static TenantConfig aliceTenant() {
        return TenantConfig.builder()
            .tenantId("alice")
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/alice")).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(java.util.Arrays.asList("ls", "cat")).build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(10_000_000L).build())
            .build();
    }

    private static TenantConfig bobTenant() {
        return TenantConfig.builder()
            .tenantId("bob")
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/bob")).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(java.util.Arrays.asList("ls", "cat", "git")).build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(100_000_000L).build())
            .build();
    }

    @Test
    @DisplayName("L1-007: resolve_existingTenant_returnsConfig")
    void resolve_existingTenant_returnsConfig() {
        Map<String, TenantConfig> map = new HashMap<>();
        map.put("alice", aliceTenant());
        map.put("bob", bobTenant());

        YamlTenantConfigProvider provider = new YamlTenantConfigProvider(withTenants(map));

        assertThat(provider.resolve("alice"))
            .isPresent()
            .get()
            .extracting(TenantConfig::getTenantId).isEqualTo("alice");
        assertThat(provider.resolve("bob"))
            .isPresent()
            .get()
            .extracting(TenantConfig::getTenantId).isEqualTo("bob");
    }

    @Test
    @DisplayName("L1-008: resolve_missingTenant_returnsEmpty")
    void resolve_missingTenant_returnsEmpty() {
        YamlTenantConfigProvider provider = new YamlTenantConfigProvider(
            withTenants(Collections.singletonMap("alice", aliceTenant())));

        assertThat(provider.resolve("ghost")).isEmpty();
        assertThat(provider.resolve(null)).isEmpty();
    }

    @Test
    @DisplayName("L1-009: listTenantIds_returnsAllConfigured")
    void listTenantIds_returnsAllConfigured() {
        Map<String, TenantConfig> map = new HashMap<>();
        map.put("alice", aliceTenant());
        map.put("bob", bobTenant());

        YamlTenantConfigProvider provider = new YamlTenantConfigProvider(withTenants(map));

        assertThat(provider.listTenantIds()).containsExactlyInAnyOrder("alice", "bob");
        // listTenantIds() must return an immutable list.
        org.junit.jupiter.api.Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> provider.listTenantIds().add("eve"));
    }

    @Test
    @DisplayName("Edge: provider_handlesSingleTenantMode_nullTenants")
    void provider_handlesSingleTenantMode_nullTenants() {
        YamlTenantConfigProvider provider = new YamlTenantConfigProvider(withTenants(null));

        assertThat(provider.listTenantIds()).isEmpty();
        assertThat(provider.resolve("alice")).isEmpty();
    }

    @Test
    @DisplayName("Edge: provider_handlesEmptyTenants_singleTenantMode")
    void provider_handlesEmptyTenants_singleTenantMode() {
        YamlTenantConfigProvider provider = new YamlTenantConfigProvider(
            withTenants(Collections.<String, TenantConfig>emptyMap()));

        assertThat(provider.listTenantIds()).isEmpty();
        assertThat(provider.resolve("alice")).isEmpty();
    }
}