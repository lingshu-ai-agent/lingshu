package ai.lingshu.a2a.client;

import ai.lingshu.core.a2a.client.InProcessA2aRegistry;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Story #009b — Provider for the {@code "in-process-1.0.0"} variant of
 * {@link A2aTransport} (dsh §5.6.3.2 L3270-3276).
 *
 * <p>Identifier: {@code "in-process-1.0.0"}, priority {@code 10},
 * contract version {@code "1.0.0"} — same priority as the gRPC variant
 * but a distinct {@code name()} so the SlotRouter can resolve both
 * without conflict (§5.5 multi-Provider mode, 🆕 v1.5.28).</p>
 *
 * <p>Defaults (data-model.md DM-02; matches {@link GrpcA2aTransportProvider}
 * where applicable):</p>
 * <ul>
 *   <li>{@code cardTtl} = {@code Duration.ofMinutes(5)} (resolved from
 *       {@code AgentConfig.a2a.cardTtl} when available)</li>
 * </ul>
 *
 * <p>No validation required: an InProcess transport requires no host,
 * port, or credentials — it is purely intra-JVM.</p>
 */
@Component
public class InProcessA2aTransportProvider implements Providers.A2aTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(InProcessA2aTransportProvider.class);

    /** Provider name — must NOT collide with {@code "grpc-1.0.0"} or future {@code "http-jsonrpc-1.0.0"}. */
    public static final String NAME = "in-process-1.0.0";

    /** Default AgentCard TTL when {@code AgentConfig.a2a.cardTtl} is unset. */
    static final Duration DEFAULT_CARD_TTL = Duration.ofMinutes(5);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    /**
     * Build an {@link InProcessA2aTransport}. Wires:
     * <ul>
     *   <li>the singleton {@link InProcessA2aRegistry#getInstance()} (shared across all
     *       InProcess transports in the JVM — same card for all callers);</li>
     *   <li>a fresh {@link AgentCardCache} keyed off {@code AgentConfig.a2a.cardTtl}
     *       (each transport instance owns its own cache to keep lifecycles independent).</li>
     * </ul>
     */
    @Override
    public A2aTransport create(AgentConfig cfg) {
        Duration cardTtl = resolveCardTtl(cfg);
        log.info("[A2aTransport] creating InProcessA2aTransport: cardTtl={}", cardTtl);
        InProcessA2aRegistry registry = InProcessA2aRegistry.getInstance();
        AgentCardCache cache = new AgentCardCache(cardTtl);
        return new InProcessA2aTransport(registry, cache);
    }

    private Duration resolveCardTtl(AgentConfig cfg) {
        try {
            if (cfg != null && cfg.getA2a() != null && cfg.getA2a().getCardTtl() != null) {
                return cfg.getA2a().getCardTtl();
            }
        } catch (NoSuchMethodError | RuntimeException ignored) {
            // older AgentConfig.A2a (pre-#009a) lacks getCardTtl() — fall through
        }
        return DEFAULT_CARD_TTL;
    }
}
