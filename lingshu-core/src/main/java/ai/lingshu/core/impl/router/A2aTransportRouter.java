package ai.lingshu.core.impl.router;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.spi.SlotRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Story #009a — Contract A2: {@link A2aTransport} slot router.
 *
 * <p>Resolves an A2aTransport by {@code cfg.getA2aTransport()} (name lookup), validates
 * provider contract version compatibility, and returns the {@link A2aTransport} instance.</p>
 *
 * <p>Standard {@link SlotRouter} behavior (inherited):</p>
 * <ul>
 *   <li>byName map built from injected {@code List<Providers.A2aTransportProvider>}</li>
 *   <li>priority-based tie-break (higher priority wins)</li>
 *   <li>Startup log: {@code [A2aTransport] resolved N provider(s) [contract v1.0.0]: ...}</li>
 *   <li>unknown name → {@link IllegalArgumentException} (LINGS-S01)</li>
 *   <li>version mismatch → {@code ProviderInitException} (LINGS-S05)</li>
 * </ul>
 */
@Component
public class A2aTransportRouter extends SlotRouter<Providers.A2aTransportProvider, A2aTransport> {

    private static final Logger log = LoggerFactory.getLogger(A2aTransportRouter.class);

    public A2aTransportRouter(List<Providers.A2aTransportProvider> providers) {
        super(providers, "A2aTransport", log);
    }

    @Override
    protected Class<A2aTransport> getSlotInterface() {
        return A2aTransport.class;
    }
}
