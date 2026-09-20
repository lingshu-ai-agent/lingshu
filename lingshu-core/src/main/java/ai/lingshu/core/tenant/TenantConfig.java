package ai.lingshu.core.tenant;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable configuration for a single tenant (Story #006, dsh §14.9 N9).
 *
 * <p>Source of truth for per-tenant overrides:
 * <ul>
 *   <li>{@link Memory} — tenant memory directory (file isolation root)</li>
 *   <li>{@link Sandbox} — per-tenant command whitelist (process-level isolation)</li>
 *   <li>{@link Cost} — per-tenant session budget (cost isolation)</li>
 * </ul>
 *
 * <p>Built by YAML binding ({@code agent.tenants.<id>.*}) and exposed to the rest of
 * the engine via {@link TenantConfigProvider}.
 *
 * <p><b>Immutability.</b> {@link Value} + {@link Builder}; the {@code commandWhitelist}
 * list is wrapped in {@link Collections#unmodifiableList} at construction time so that
 * later mutation of the YAML-bound list cannot leak through to the running engine.
 */
@Value
@Builder(toBuilder = true)
public class TenantConfig {

    /** Tenant identifier; matches {@code [a-zA-Z0-9_-]{1,64}}. */
    String tenantId;

    /** Tenant memory configuration — must not be {@code null}. */
    Memory memory;

    /** Tenant sandbox configuration — must not be {@code null}. */
    Sandbox sandbox;

    /** Tenant cost configuration — must not be {@code null}. */
    Cost cost;

    @Value
    @Builder(toBuilder = true)
    public static class Memory {
        /** Tenant memory root directory; absolute path. */
        Path dir;
    }

    @Value
    @Builder(toBuilder = true)
    public static class Sandbox {
        /**
         * Commands the tenant is allowed to run (e.g. {@code ["ls", "cat"]}); empty
         * list = deny everything. Always wrapped in unmodifiableList at construction
         * time (see {@link #ofWhitelist(List)}) so external mutation cannot leak through.
         */
        List<String> commandWhitelist;

        /**
         * Build a {@link Sandbox} with a defensively-copied whitelist.
         * {@code null} is normalised to an empty list.
         */
        public static Sandbox ofWhitelist(List<String> commandWhitelist) {
            List<String> copy = (commandWhitelist == null)
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(commandWhitelist));
            return new Sandbox(copy);
        }
    }

    @Value
    @Builder(toBuilder = true)
    public static class Cost {
        /**
         * Per-session budget in micro-USD (1 USD = 1_000_000 micros). Positive long;
         * zero / negative is rejected by {@code TenantsConfig.validate()}.
         */
        long sessionBudgetMicros;
    }
}