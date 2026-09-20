package ai.lingshu.core.impl.tenant;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.tenant.TenantConfig;
import ai.lingshu.core.tenant.TenantConfigProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link TenantConfigProvider} — sources tenant configs from the
 * {@code agent.tenants.<id>.*} YAML block.
 *
 * <p>Spring-singleton; reads {@link AgentConfig} once at construction and caches the
 * immutable {@link TenantConfig} map for the lifetime of the application context.
 *
 * <p>Bean name {@code "tenantConfigProvider_yaml"} follows the §5.28 convention
 * so multiple providers can coexist without {@code BeanDefinitionOverrideException}.
 */
@Component("tenantConfigProvider_yaml")
public class YamlTenantConfigProvider implements TenantConfigProvider {

    private final Map<String, TenantConfig> configs;

    @Autowired
    public YamlTenantConfigProvider(AgentConfig config) {
        Map<String, TenantConfig> fromYaml = (config != null && config.getTenants() != null)
            ? config.getTenants().getMap()
            : Collections.<String, TenantConfig>emptyMap();
        // Defensive copy — the @Value map reference may be shared with the config.
        this.configs = Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fromYaml));
    }

    @Override
    public String name() {
        return "yaml";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Optional<TenantConfig> resolve(String tenantId) {
        return Optional.ofNullable(configs.get(tenantId));
    }

    @Override
    public List<String> listTenantIds() {
        return Collections.unmodifiableList(new ArrayList<>(configs.keySet()));
    }
}