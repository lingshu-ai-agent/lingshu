package ai.lingshu.core.impl.cost;

import ai.lingshu.core.tenant.TenantConfig;
import ai.lingshu.core.tenant.TenantConfigProvider;
import ai.lingshu.core.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-tenant cost budget tracker (Story #006, US3, FR-012, AC-05).
 *
 * <p>Records accumulated cost (in micro-USD) per tenant and rejects further
 * accumulation when a tenant's configured {@code sessionBudgetMicros} would be
 * exceeded. Other tenants are unaffected — each bucket is independent.
 *
 * <h2>Bucket semantics</h2>
 *
 * <p>Per-tenant usage lives in {@link LongAdder} buckets indexed by tenantId.
 * {@link LongAdder} scales better than {@code AtomicLong} under high write
 * contention (it stripes across cells), which matters here because LLM calls
 * are bursty and per-step accounting may fan in concurrently across tool
 * dispatches.
 *
 * <h2>Budget resolution</h2>
 *
 * <p>Budgets are captured from {@link TenantConfigProvider} at construction
 * time and held in an immutable map. Future Stories that add hot-reload
 * (Story #007 {@code AgentConfigRegistry}) can swap this constructor for an
 * observable budget source without changing the {@link #accumulate} contract.
 *
 * <h2>Tenant scope</h2>
 *
 * <ul>
 *   <li><b>Tenant active</b> — adds to that tenant's bucket and checks budget.
 *       Throws {@link CostBudgetExceededException} if the addition would push
 *       the tenant over budget (FR-012 fail-fast, US3 S2).</li>
 *   <li><b>No tenant</b> (single-tenant mode, {@code AgentConfig.tenants}
 *       disabled) — silently no-ops. The global cost surface is owned by
 *       Story #012; this tracker is purely the per-tenant dimension.</li>
 *   <li><b>Tenant active but unknown to provider</b> — adds to the bucket but
 *       skips the budget check (no budget = no limit). This avoids spurious
 *       failures during config drift; the FR-011 guard at the turn boundary
 *       already rejects unknown tenantIds before the turn starts.</li>
 * </ul>
 *
 * <h2>Concurrency</h2>
 *
 * <p>The budget check happens <i>after</i> the {@link LongAdder} is mutated
 * (read–modify–write). This is intentional: a single in-flight turn at the
 * boundary may briefly read {@code used > budget} and throw, leaving the
 * bucket at the post-throw value. Callers should treat
 * {@link CostBudgetExceededException} as a hard stop — the rest of the turn
 * never runs, so the overshoot is bounded to one LLM call's worth of cost.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I-1: {@code usedFor(t)} is non-decreasing across calls to
 *       {@link #accumulate} for the same {@code t}.</li>
 *   <li>I-2: {@link #accumulate} either fully records (and possibly throws) or
 *       does not record at all (single-step atomicity).</li>
 *   <li>I-3: a throw from {@link #accumulate} for tenant {@code t1} never
 *       affects {@code usedFor(t2)} for any {@code t2}.</li>
 *   <li>I-4: budget map is immutable for the lifetime of this bean.</li>
 * </ul>
 */
@Component("tenantAwareCostTracker")
public final class TenantAwareCostTracker {

    /** Per-tenant running totals — {@link LongAdder} for striped atomicity. */
    private final ConcurrentHashMap<String, LongAdder> usedByTenant =
        new ConcurrentHashMap<>();

    /** Per-tenant configured session budget in micro-USD (immutable snapshot). */
    private final Map<String, Long> budgets;

    /**
     * Construct with a {@link TenantConfigProvider}. Budgets are pulled from
     * every tenant the provider knows about; unknown tenantIds at runtime
     * get no budget (= no limit).
     */
    @Autowired
    public TenantAwareCostTracker(TenantConfigProvider provider) {
        Map<String, Long> resolved = new HashMap<>();
        if (provider != null) {
            for (String tid : provider.listTenantIds()) {
                TenantConfig tc = provider.resolve(tid).orElse(null);
                if (tc != null && tc.getCost() != null) {
                    resolved.put(tid, tc.getCost().getSessionBudgetMicros());
                }
            }
        }
        this.budgets = java.util.Collections.unmodifiableMap(resolved);
    }

    /**
     * Record {@code micros} of cost against the tenant currently active on
     * this thread (via {@link TenantContext#current()}). If the addition would
     * exceed the tenant's configured budget, throws
     * {@link CostBudgetExceededException} — the addition still lands in the
     * bucket but the turn's caller treats the throw as a hard stop (see
     * "Concurrency" above).
     *
     * @param micros cost to add, must be &ge; 0; negative values are
     *               interpreted as 0 (defensive — never trust upstream counters)
     * @throws CostBudgetExceededException if the post-add total exceeds the
     *         tenant's budget
     */
    public void accumulate(long micros) {
        if (micros <= 0) {
            return; // nothing to record
        }
        String tid = TenantContext.current();
        if (tid == null) {
            // Single-tenant mode — global cost tracking is Story #012.
            return;
        }
        LongAdder adder = usedByTenant.computeIfAbsent(tid, k -> new LongAdder());
        adder.add(micros);
        long used = adder.longValue();
        Long budget = budgets.get(tid);
        if (budget != null && used > budget) {
            long usedBefore = used - micros;
            throw new CostBudgetExceededException(tid, budget, usedBefore, micros);
        }
    }

    /**
     * @return the total accumulated micros for {@code tenantId}, or 0 if the
     *         tenant has never recorded any cost on this tracker instance.
     */
    public long usedFor(String tenantId) {
        LongAdder a = usedByTenant.get(tenantId);
        return a == null ? 0L : a.longValue();
    }

    /**
     * @return the configured budget for {@code tenantId} in micro-USD, or
     *         {@code null} if the provider didn't know about this tenant
     *         (no limit applies).
     */
    public Long budgetFor(String tenantId) {
        return budgets.get(tenantId);
    }

    /**
     * Snapshot of all known tenantIds in the budget map. Useful for ops
     * introspection (e.g. log "tracking budgets for N tenants" on startup).
     */
    public java.util.Set<String> knownTenantIds() {
        return budgets.keySet();
    }
}
