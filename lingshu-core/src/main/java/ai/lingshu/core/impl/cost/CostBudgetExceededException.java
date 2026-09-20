package ai.lingshu.core.impl.cost;

/**
 * Thrown when a tenant's session cost accumulates past its configured budget
 * (Story #006 US3 S2).
 *
 * <p>Thrown by {@link TenantAwareCostTracker#accumulate(long, String)} when adding
 * the new usage would push the tenant's total over the {@code sessionBudgetMicros}
 * limit. Other tenants are unaffected — each bucket is independent.
 */
public class CostBudgetExceededException extends RuntimeException {

    private final String tenantId;
    private final long budgetMicros;
    private final long usedMicros;
    private final long attemptedMicros;

    public CostBudgetExceededException(String tenantId, long budgetMicros,
                                       long usedMicros, long attemptedMicros) {
        super("Cost budget exceeded for tenant '" + tenantId + "': budget="
            + budgetMicros + " micros, used=" + usedMicros
            + " micros, attempted to add " + attemptedMicros + " micros");
        this.tenantId = tenantId;
        this.budgetMicros = budgetMicros;
        this.usedMicros = usedMicros;
        this.attemptedMicros = attemptedMicros;
    }

    public String getTenantId() { return tenantId; }
    public long getBudgetMicros() { return budgetMicros; }
    public long getUsedMicros() { return usedMicros; }
    public long getAttemptedMicros() { return attemptedMicros; }
}