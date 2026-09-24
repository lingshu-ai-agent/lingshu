package ai.lingshu.a2a.client;

import ai.lingshu.core.spi.Providers;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Story #009c (originally) — SPI registration for {@link HttpJsonRpcA2aTransportProvider}.
 *
 * <p><b>🆕 Story #009e — slimmed down to one Bean</b>: this class now
 * exposes ONLY the {@code a2aTransportProvider_http-jsonrpc-1.0.0} transport
 * provider. The previously-bundled {@code remoteAgentTool} +
 * {@code remoteAgentSchemaBuilder} {@code @Bean} methods have been moved
 * into a brand-new {@link RemoteAgentToolAutoConfiguration} so that all
 * three transports (grpc / in-process / http-jsonrpc) share a single,
 * transport-independent wiring.</p>
 *
 * <p>Bean name follows §5.4 unique-name convention:
 * {@code "a2aTransportProvider_<name>"} → {@code "a2aTransportProvider_http-jsonrpc-1.0.0"},
 * distinct from {@link GrpcA2aTransportAutoConfiguration}'s
 * {@code "a2aTransportProvider_grpc-1.0.0"} and
 * {@link InProcessA2aTransportAutoConfiguration}'s
 * {@code "a2aTransportProvider_in-process-1.0.0"} — all three coexist in
 * the same JVM under the v1.5.28 multi-Provider mode (§5.5).</p>
 */
@AutoConfiguration
public class HttpJsonRpcA2aTransportAutoConfiguration {

    @Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")
    public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider() {
        return new HttpJsonRpcA2aTransportProvider();
    }
}