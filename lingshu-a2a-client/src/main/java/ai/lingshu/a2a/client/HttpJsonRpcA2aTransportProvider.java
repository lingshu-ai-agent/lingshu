package ai.lingshu.a2a.client;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Story #009c — Contract A1 + A2: HTTP/JSON-RPC {@link A2aTransport} provider.
 *
 * <p>Identifier: {@code "http-jsonrpc-1.0.0"}, priority {@code 10}, contract
 * version {@code "1.0.0"}. Distinct from {@link GrpcA2aTransportProvider}'s
 * {@code "grpc-1.0.0"} and {@link InProcessA2aTransportProvider}'s
 * {@code "in-process-1.0.0"} (🆕 v1.5.28 multi-Provider mode).</p>
 *
 * <p>Reads from {@code AgentConfig.A2a}:</p>
 * <ul>
 *   <li>{@code httpBaseUrl} — default {@code "http://localhost:8080"} (aligns with
 *       A2aServer host=0.0.0.0 + port=8080).</li>
 *   <li>{@code callTimeout} — default {@code Duration.ofSeconds(30)}.</li>
 *   <li>{@code cardTtl} — default {@code Duration.ofMinutes(5)} (matches #009a/#009b).</li>
 * </ul>
 *
 * <p>Each fallback uses {@code try/catch} for forward-compatibility with
 * pre-#009c {@code AgentConfig.A2a} versions.</p>
 */
@Component
public class HttpJsonRpcA2aTransportProvider implements Providers.A2aTransportProvider {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcA2aTransportProvider.class);

    static final String DEFAULT_HTTP_BASE_URL = "http://localhost:8080";
    static final Duration DEFAULT_CALL_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_CARD_TTL = Duration.ofMinutes(5);

    @Override
    public String name() {
        return "http-jsonrpc-1.0.0";
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
        String httpBaseUrl = resolveHttpBaseUrl(cfg);
        Duration callTimeout = resolveCallTimeout(cfg);
        Duration cardTtl = resolveCardTtl(cfg);
        log.info("[A2aTransport] creating HttpJsonRpcA2aTransport: httpBaseUrl='{}' callTimeout={} cardTtl={}",
            httpBaseUrl, callTimeout, cardTtl);
        return new HttpJsonRpcA2aTransport(
            httpBaseUrl,
            new ObjectMapper(),
            new AgentCardCache(cardTtl),
            callTimeout
        );
    }

    private String resolveHttpBaseUrl(AgentConfig cfg) {
        try {
            if (cfg != null && cfg.getA2a() != null && cfg.getA2a().getHttpBaseUrl() != null) {
                String v = cfg.getA2a().getHttpBaseUrl();
                if (!v.isEmpty()) {
                    return v;
                }
            }
        } catch (NoSuchMethodError | RuntimeException ignored) {
            // older AgentConfig.A2a (pre-#009c) lacks getHttpBaseUrl() — fall through
        }
        return DEFAULT_HTTP_BASE_URL;
    }

    private Duration resolveCallTimeout(AgentConfig cfg) {
        try {
            if (cfg != null && cfg.getA2a() != null && cfg.getA2a().getCallTimeout() != null) {
                return cfg.getA2a().getCallTimeout();
            }
        } catch (NoSuchMethodError | RuntimeException ignored) {
            // older AgentConfig.A2a (pre-#009c) lacks getCallTimeout() — fall through
        }
        return DEFAULT_CALL_TIMEOUT;
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