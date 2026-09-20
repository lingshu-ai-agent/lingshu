package ai.lingshu.core.tenant;

import ai.lingshu.core.impl.cost.CostBudgetExceededException;
import ai.lingshu.core.impl.cost.TenantAwareCostTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #006 — L1 unit tests for per-tenant cost budget isolation (US3, FR-012).
 *
 * <p>Two assertions (L1-012, L1-013) prove the central AC-05 property:
 * <ul>
 *   <li><b>Isolation</b> — alice's spend never affects bob's bucket or budget.</li>
 *   <li><b>Fail-fast</b> — exceeding a tenant's configured budget throws
 *       {@link CostBudgetExceededException}, leaving other tenants untouched.</li>
 * </ul>
 *
 * <p>The tests construct a {@link TenantAwareCostTracker} directly with an
 * in-memory {@link TenantConfigProvider} stub (no Spring context, no AgentConfig)
 * to keep this a pure L1 unit test.
 */
class CostBudgetIsolationTest {

    @AfterEach
    void clearTenantContext() {
        // Defensive — should already be null after each test (runAs cleans up),
        // but if a test threw mid-runAs the stack could leak across tests.
        while (TenantContext.current() != null) {
            TenantContext.clear();
        }
    }

    /** Build a TenantConfig with the given budget (everything else stubbed). */
    private static TenantConfig tenantWithBudget(long budgetMicros) {
        return TenantConfig.builder()
            .tenantId("placeholder")
            .memory(TenantConfig.Memory.builder().dir(Paths.get("/tmp/x")).build())
            .sandbox(TenantConfig.Sandbox.builder()
                .commandWhitelist(Collections.singletonList("ls")).build())
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(budgetMicros).build())
            .build();
    }

    /** Stub provider that returns the given tenants (ignores priority for tests). */
    private static TenantConfigProvider stubProvider(TenantConfig... tenants) {
        Map<String, TenantConfig> map = new HashMap<>();
        for (TenantConfig tc : tenants) {
            map.put(tc.getTenantId(), tc);
        }
        return new TenantConfigProvider() {
            @Override public String name() { return "stub"; }
            @Override public int priority() { return 0; }
            @Override public java.util.Optional<TenantConfig> resolve(String tenantId) {
                return java.util.Optional.ofNullable(map.get(tenantId));
            }
            @Override public List<String> listTenantIds() {
                return Collections.unmodifiableList(Arrays.asList(map.keySet().toArray(new String[0])));
            }
        };
    }

    @Test
    @DisplayName("L1-012: aliceBudgetUsed_doesNotAffectBobBudget")
    void aliceBudgetUsed_doesNotAffectBobBudget() {
        TenantConfig alice = tenantWithBudget(10_000_000L).toBuilder().tenantId("alice").build();
        TenantConfig bob = tenantWithBudget(100_000_000L).toBuilder().tenantId("bob").build();
        TenantAwareCostTracker tracker =
            new TenantAwareCostTracker(stubProvider(alice, bob));

        TenantContext.runAs("alice", () -> tracker.accumulate(5_000_000L));
        TenantContext.runAs("bob", () -> tracker.accumulate(3_000_000L));

        assertThat(tracker.usedFor("alice")).isEqualTo(5_000_000L);
        assertThat(tracker.usedFor("bob")).isEqualTo(3_000_000L);
        // Buckets are independent — neither has bled into the other.
        assertThat(tracker.usedFor("alice")).isNotEqualTo(tracker.usedFor("bob"));
    }

    @Test
    @DisplayName("L1-013: aliceExceedsBudget_throwsException")
    void aliceExceedsBudget_throwsException() {
        TenantConfig alice = tenantWithBudget(10_000_000L).toBuilder().tenantId("alice").build();
        TenantConfig bob = tenantWithBudget(100_000_000L).toBuilder().tenantId("bob").build();
        TenantAwareCostTracker tracker =
            new TenantAwareCostTracker(stubProvider(alice, bob));

        // alice consumes 5M of her 10M budget — fine.
        TenantContext.runAs("alice", () -> tracker.accumulate(5_000_000L));
        assertThat(tracker.usedFor("alice")).isEqualTo(5_000_000L);

        // alice tries to add 8M more (would total 13M > 10M budget) → fail-fast.
        assertThatThrownBy(() ->
                TenantContext.runAs("alice", () -> tracker.accumulate(8_000_000L)))
            .isInstanceOf(CostBudgetExceededException.class)
            .extracting(t -> ((CostBudgetExceededException) t).getTenantId())
            .isEqualTo("alice");

        // bob is unaffected — his bucket is still empty and his budget is intact.
        assertThat(tracker.usedFor("bob")).isEqualTo(0L);
        assertThat(tracker.budgetFor("bob")).isEqualTo(100_000_000L);

        // alice's bucket now reflects the attempted 8M (I-2: either fully records
        // or does not record at all; we already recorded 5M, the 8M also lands
        // because LongAdder.add() runs before the budget check).
        assertThat(tracker.usedFor("alice")).isEqualTo(13_000_000L);
    }

    @Test
    @DisplayName("Edge: noTenantContext_silentlyNoOps")
    void noTenantContext_silentlyNoOps() {
        TenantConfig alice = tenantWithBudget(10_000_000L).toBuilder().tenantId("alice").build();
        TenantAwareCostTracker tracker =
            new TenantAwareCostTracker(stubProvider(alice));

        // Without TenantContext.runAs, accumulate() is a no-op (single-tenant
        // mode defers global cost to Story #012).
        assertThatCode(() -> tracker.accumulate(9_999_999L)).doesNotThrowAnyException();
        assertThat(tracker.usedFor("alice")).isEqualTo(0L);
    }

    @Test
    @DisplayName("Edge: unknownTenant_accumulatesWithoutBudgetCheck")
    void unknownTenant_accumulatesWithoutBudgetCheck() {
        TenantConfig alice = tenantWithBudget(10_000_000L).toBuilder().tenantId("alice").build();
        TenantAwareCostTracker tracker =
            new TenantAwareCostTracker(stubProvider(alice));

        // Provider doesn't know "ghost" → no budget check; accumulation is allowed.
        TenantContext.runAs("ghost", () -> tracker.accumulate(999_999_999L));
        assertThat(tracker.usedFor("ghost")).isEqualTo(999_999_999L);
        assertThat(tracker.budgetFor("ghost")).isNull();
    }

    @Test
    @DisplayName("Edge: negativeMicros_treatedAsZero")
    void negativeMicros_treatedAsZero() {
        TenantConfig alice = tenantWithBudget(10_000_000L).toBuilder().tenantId("alice").build();
        TenantAwareCostTracker tracker =
            new TenantAwareCostTracker(stubProvider(alice));

        TenantContext.runAs("alice", () -> tracker.accumulate(-1L));
        assertThat(tracker.usedFor("alice")).isEqualTo(0L);
    }
}
