package ai.lingshu.a2a.client;

import ai.lingshu.a2a.server.LingsA2aServerException;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Story #009a — Contract A1 + A2: gRPC {@link A2aTransport} provider.
 *
 * <p>Identifier: {@code "grpc-1.0.0"}, priority {@code 10}, contract version {@code "1.0.0"}.</p>
 *
 * <p>Default values (data-model.md DM-02; hardcoded in Option A2 scope reduction):</p>
 * <ul>
 *   <li>{@code grpcTarget} = {@code "localhost:50051"}</li>
 *   <li>{@code cardTtl} = {@code Duration.ofMinutes(5)}</li>
 * </ul>
 *
 * <p>Validation: {@link #create(AgentConfig)} throws {@link LingsA2aServerException} (errorCode
 * {@code "LINGS-S07"}) if the grpc target is null or empty (EC-1 fail-fast).</p>
 */
@Component
public class GrpcA2aTransportProvider implements Providers.A2aTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(GrpcA2aTransportProvider.class);

    static final String DEFAULT_GRPC_TARGET = "localhost:50051";
    static final Duration DEFAULT_CARD_TTL = Duration.ofMinutes(5);

    @Override
    public String name() {
        return "grpc-1.0.0";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public A2aTransport create(AgentConfig cfg) {
        String grpcTarget = resolveGrpcTarget(cfg);
        if (grpcTarget == null || grpcTarget.trim().isEmpty()) {
            throw new LingsA2aServerException(
                "LINGS-S07",
                "AgentConfig.a2a.grpcTarget must not be null/empty",
                "set 'agent.a2a.grpcTarget' in application.yml (e.g. 'localhost:50051')"
            );
        }
        Duration cardTtl = resolveCardTtl(cfg);
        log.info("[A2aTransport] creating GrpcA2aTransport: target='{}' cardTtl={}",
            grpcTarget, cardTtl);
        ManagedChannel channel = ManagedChannelBuilder.forTarget(grpcTarget)
            .usePlaintext()
            .build();
        AgentCardCache cache = new AgentCardCache(cardTtl);
        return new GrpcA2aTransport(channel, cache, grpcTarget);
    }

    private String resolveGrpcTarget(AgentConfig cfg) {
        // Read from AgentConfig.A2a if present, otherwise fall back to hardcoded default.
        // Direct field access via cfg.getA2a().getGrpcTarget() — Lombok @Value generates this getter.
        try {
            if (cfg != null && cfg.getA2a() != null && cfg.getA2a().getGrpcTarget() != null) {
                return cfg.getA2a().getGrpcTarget();
            }
        } catch (NoSuchMethodError | RuntimeException ignored) {
            // older AgentConfig.A2a (pre-#009a) lacks getGrpcTarget() — fall through
        }
        return DEFAULT_GRPC_TARGET;
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
