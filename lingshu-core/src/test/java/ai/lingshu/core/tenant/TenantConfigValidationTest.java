package ai.lingshu.core.tenant;

import ai.lingshu.core.exception.LingsConfigException;
import ai.lingshu.core.runtime.AgentConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #006 — L1 unit tests for {@link AgentConfig.TenantsConfig#validate()}.
 *
 * <p>Covers FR-010 (fail-fast on misconfig) and the LINGS-C02 error mapping.
 */
class TenantConfigValidationTest {

    private static TenantConfig goodTenant(String tid) {
        return TenantConfig.builder()
            .tenantId(tid)
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/" + tid)).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(10_000_000L).build())
            .build();
    }

    @Test
    @DisplayName("L1-018: missingCostField_validationFails")
    void missingCostField_validationFails() {
        // Tenant with null cost → validate() should fail-fast
        Map<String, TenantConfig> map = new HashMap<>();
        map.put("alice", TenantConfig.builder()
            .tenantId("alice")
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/alice")).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            // cost intentionally omitted
            .build());

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .extracting(t -> ((LingsConfigException) t).getCode())
            .isEqualTo("C02");
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("tenants.alice.cost.sessionBudgetMicros");
    }

    @Test
    @DisplayName("L1-019: tooManyTenants_validationFails")
    void tooManyTenants_validationFails() {
        // 1001 tenant entries — exceeds the 1000 limit
        Map<String, TenantConfig> map = new HashMap<>();
        for (int i = 0; i < 1001; i++) {
            String tid = "t" + i;
            map.put(tid, goodTenant(tid));
        }

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("tenants.map.size() = 1001 exceeds limit 1000");
    }

    @Test
    @DisplayName("Edge: emptyTenants_singleTenantMode_succeeds")
    void emptyTenants_singleTenantMode_succeeds() {
        // Empty map → enabled=false → validate is a no-op
        AgentConfig.TenantsConfig empty = new AgentConfig.TenantsConfig(
            false, Collections.<String, TenantConfig>emptyMap());

        assertThatCode(empty::validate).doesNotThrowAnyException();
        assertThat(empty.isEnabled()).isFalse();
        assertThat(AgentConfig.TenantsConfig.defaults().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Edge: missingMemoryDir_validationFails")
    void missingMemoryDir_validationFails() {
        Map<String, TenantConfig> map = new HashMap<>();
        map.put("alice", TenantConfig.builder()
            .tenantId("alice")
            .memory(TenantConfig.Memory.builder().dir(null).build())  // null dir
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(10_000_000L).build())
            .build());

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("tenants.alice.memory.dir is required");
    }

    @Test
    @DisplayName("Edge: keyValueMismatch_validationFails")
    void keyValueMismatch_validationFails() {
        Map<String, TenantConfig> map = new HashMap<>();
        // key 'alice' but value's tenantId is 'eve'
        map.put("alice", goodTenant("eve"));

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("key/value tenantId mismatch");
    }

    @Test
    @DisplayName("Edge: invalidTenantIdFormat_validationFails")
    void invalidTenantIdFormat_validationFails() {
        Map<String, TenantConfig> map = new HashMap<>();
        // tenantId with colon — disallowed by the regex [a-zA-Z0-9_-]{1,64}
        map.put("alice:bob", goodTenant("alice:bob"));

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("invalid tenantId format");
    }

    @Test
    @DisplayName("Edge: aggregateAllErrors_singleFailure")
    void aggregateAllErrors_singleFailure() {
        // Two tenants, one fully good, one missing cost — single exception listing both
        // would list only the broken one because validate() short-circuits the moment
        // the field check fails. We assert the error message contains alice's issue.
        Map<String, TenantConfig> map = new HashMap<>();
        map.put("alice", goodTenant("alice"));
        TenantConfig bad = TenantConfig.builder()
            .tenantId("bob")
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/bob")).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            // cost missing
            .build();
        map.put("bob", bad);

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("tenants.bob.cost.sessionBudgetMicros");
    }

    @Test
    @DisplayName("Edge: allErrorsCollected_inOneMessage")
    void allErrorsCollected_inOneMessage() {
        // Two tenants with different failures — both should appear in the same error.
        Map<String, TenantConfig> map = new HashMap<>();
        map.put("alice", TenantConfig.builder()
            .tenantId("alice")
            .memory(TenantConfig.Memory.builder().dir(null).build())  // missing dir
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(10L).build())
            .build());
        TenantConfig bobMissingCost = TenantConfig.builder()
            .tenantId("bob")
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/bob")).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            // cost missing
            .build();
        map.put("bob", bobMissingCost);

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            // Both errors are surfaced in one go — saves the user from running → fixing
            // → running → fixing iteratively.
            .hasMessageContaining("tenants.alice.memory.dir")
            .hasMessageContaining("tenants.bob.cost.sessionBudgetMicros");
    }

    @Test
    @DisplayName("Edge: zeroBudget_validationFails")
    void zeroBudget_validationFails() {
        Map<String, TenantConfig> map = new HashMap<>();
        map.put("alice", TenantConfig.builder()
            .tenantId("alice")
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/var/lib/alice")).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(0L).build())   // 0 → invalid
            .build());

        AgentConfig.TenantsConfig tc = new AgentConfig.TenantsConfig(true, map);
        assertThatThrownBy(tc::validate)
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("tenants.alice.cost.sessionBudgetMicros must be > 0");
    }

    @Test
    @DisplayName("Edge: defaults_singleTenantMode")
    void defaults_singleTenantMode() {
        AgentConfig.TenantsConfig def = AgentConfig.TenantsConfig.defaults();
        assertThat(def.isEnabled()).isFalse();
        assertThat(def.getMap()).isEmpty();
        assertThatCode(def::validate).doesNotThrowAnyException();
    }
}