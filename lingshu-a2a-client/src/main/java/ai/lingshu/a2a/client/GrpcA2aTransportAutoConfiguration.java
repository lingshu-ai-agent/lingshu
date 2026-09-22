package ai.lingshu.a2a.client;

import ai.lingshu.core.spi.Providers;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Story #009a — SPI registration for {@link GrpcA2aTransportProvider}.
 *
 * <p>Bean name follows §5.4 unique-name convention: {@code "a2aTransportProvider_<name>"}.</p>
 *
 * <p>SPI registration file: {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * (Spring Boot 3.x format).</p>
 */
@AutoConfiguration
public class GrpcA2aTransportAutoConfiguration {

    @Bean(name = "a2aTransportProvider_grpc-1.0.0")
    public Providers.A2aTransportProvider grpcA2aTransportProvider() {
        return new GrpcA2aTransportProvider();
    }
}
