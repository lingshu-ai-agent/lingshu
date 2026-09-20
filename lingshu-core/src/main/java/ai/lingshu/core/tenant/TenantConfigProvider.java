package ai.lingshu.core.tenant;

import ai.lingshu.core.spi.ContractVersionRef;

import java.util.List;
import java.util.Optional;

/**
 * SPI — multi-tenant config provider (Story #006, dsh §14.9 N9).
 *
 * <p>Multiple providers may coexist (dsh §5.28 multi-Provider mode). Each provider's
 * {@link #name()} must be unique across the JVM — duplicate names cause
 * {@code BeanDefinitionOverrideException} at Spring startup. When multiple providers
 * resolve the same {@code tenantId}, the one with the higher {@link #priority()}
 * wins.
 *
 * <p>Default implementation is {@code YamlTenantConfigProvider}, which reads
 * {@code AgentConfig.tenants.map}. Future Stories may add:
 * <ul>
 *   <li>{@code DatabaseTenantConfigProvider} (Story #007+ hot-reload)</li>
 *   <li>{@code VaultTenantConfigProvider} (centralised secrets)</li>
 *   <li>{@code RemoteTenantConfigProvider} (multi-region)</li>
 * </ul>
 *
 * <p>Implementations are <b>stateless singletons</b>: the {@link TenantConfig} they
 * return must be immutable (Lombok {@code @Value}) so callers can cache them
 * without coordinating concurrent reads.
 */
public interface TenantConfigProvider {

    /** Contract version (semver MAJOR.MINOR.PATCH), aligned with §5.3 SPI standard. */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /** Stable identifier — must be unique across all registered providers. */
    String name();

    /**
     * Higher priority wins when multiple providers resolve the same tenantId.
     * {@code YamlTenantConfigProvider} uses {@code 10}; user-supplied providers
     * should use {@code ≥ 20} to take precedence, or {@code 0} to be the fallback.
     */
    int priority();

    /**
     * Resolve the TenantConfig for the given tenantId.
     *
     * @return {@code Optional.of(config)} if this provider knows the tenant;
     *         {@code Optional.empty()} otherwise (caller may try the next-priority
     *         provider; if all return empty, the turn is rejected — FR-011).
     */
    Optional<TenantConfig> resolve(String tenantId);

    /**
     * List all tenantIds known to this provider. Used for:
     * <ul>
     *   <li>Ops introspection ({@code AgentFactory.description()})</li>
     *   <li>Startup validation ({@code TenantsConfig.map.size() ≤ 1000})</li>
     * </ul>
     */
    List<String> listTenantIds();
}