package ai.lingshu.a2a.client;

import ai.lingshu.core.impl.router.A2aTransportRouter;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Story #009c — SPI registration for {@link HttpJsonRpcA2aTransportProvider}
 * <b>and</b> the {@link RemoteAgentTool} bean (single AutoConfiguration
 * exposing 2 beans — keeps core-new-file count within CLAUDE.md §11 #4 ≤ 5).
 *
 * <p>Two beans:</p>
 * <ol>
 *     <li>{@code a2aTransportProvider_http-jsonrpc-1.0.0} — the A2aTransport
 *         provider, registered as Spring Boot SPI via
 *         {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *         (third line, after #009a grpc + #009b in-process).</li>
 *     <li>{@code remoteAgentTool} — a {@code Tool} bean implementing the
 *         {@code remote_agent} callable. {@code Tool} is <b>not</b> one of the
 *         9 Slot SPI types (ToolRegistry auto-collects all {@code Tool} beans),
 *         so it goes through plain {@code @Bean} registration — not through
 *         {@code Providers.XxxProvider} SPI.</li>
 * </ol>
 *
 * <p>The {@code remoteAgentTool} bean resolves its {@link A2aTransport} via
 * {@link A2aTransportRouter} (named by {@code cfg.getA2aTransport()}), so
 * switching transport in {@code application.yml} transparently swaps the
 * underlying transport — <b>no need to change Spring config</b>.</p>
 */
@AutoConfiguration
public class HttpJsonRpcA2aTransportAutoConfiguration {

    /**
     * Registers {@link HttpJsonRpcA2aTransportProvider} under the convention
     * {@code "a2aTransportProvider_<name>"} so it coexists with
     * {@code "a2aTransportProvider_grpc-1.0.0"} (from #009a) and
     * {@code "a2aTransportProvider_in-process-1.0.0"} (from #009b).
     */
    @Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")
    public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider() {
        return new HttpJsonRpcA2aTransportProvider();
    }

    /**
     * The {@code remote_agent} Tool bean — when {@code application.yml} has
     * {@code agent.a2aTransport: http-jsonrpc-1.0.0}, the Tool dispatches
     * via {@link HttpJsonRpcA2aTransport}; with {@code grpc-1.0.0} or
     * {@code in-process-1.0.0}, it dispatches via the selected transport.
     */
    @Bean(name = "remoteAgentTool")
    public RemoteAgentTool remoteAgentTool(A2aTransportRouter router,
                                           AgentConfig cfg,
                                           ObjectMapper json) {
        String transportName = cfg != null && cfg.getA2aTransport() != null
            ? cfg.getA2aTransport()
            : "http-jsonrpc-1.0.0";
        A2aTransport transport = router.resolve(transportName, cfg);
        return new RemoteAgentTool(transport, json);
    }
}