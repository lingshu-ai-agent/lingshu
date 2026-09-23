package ai.lingshu.core.impl.compaction;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.spi.Providers;
import org.springframework.stereotype.Component;

/**
 * Story #018 — Default {@link Compactor} Provider (Slot 2, dsh §5.5).
 *
 * <p>YAML binding: {@code agent.compactor.name: "truncating"}.
 *
 * <p>Contract version matches {@link Compactor#CONTRACT_VERSION} {@code "1.0.0"}.
 *
 * <p>Priority 0 — the default; user-supplied alternatives should use {@code priority() >= 10}
 * per dsh §5.5 v1.5.28 "唯一 Bean 名约定".
 *
 * <p>Spring registration: {@code @Bean(name = "compactorProvider_truncating")} via
 * {@link TruncatingCompactorAutoConfiguration}.
 */
@Component
public class TruncatingCompactorProvider implements Providers.CompactorProvider {

    /** Stable identifier — referenced by {@code application.yml}. */
    public static final String NAME = "truncating";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public String version() {
        return Compactor.CONTRACT_VERSION;
    }

    @Override
    public Compactor create(AgentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        CompactorProps props = CompactorProps.from(config);
        return new TruncatingCompactor(props);
    }
}
