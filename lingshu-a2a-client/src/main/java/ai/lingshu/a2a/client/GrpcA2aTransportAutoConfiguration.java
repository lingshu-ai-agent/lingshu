/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
