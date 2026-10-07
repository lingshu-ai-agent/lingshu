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
 * Story #009b — SPI registration for {@link InProcessA2aTransportProvider}.
 *
 * <p>Bean name follows §5.4 unique-name convention:
 * {@code "a2aTransportProvider_<name>"} → {@code "a2aTransportProvider_in-process-1.0.0"} —
 * distinct from {@link GrpcA2aTransportAutoConfiguration}'s
 * {@code "a2aTransportProvider_grpc-1.0.0"}, allowing the two Providers
 * to coexist in the same JVM (🆕 v1.5.28 multi-Provider mode).</p>
 *
 * <p>SPI registration file:
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * (Spring Boot 3.x format). #009a already added the gRPC line; this Story
 * appends a second line.</p>
 */
@AutoConfiguration
public class InProcessA2aTransportAutoConfiguration {

    @Bean(name = "a2aTransportProvider_in-process-1.0.0")
    public Providers.A2aTransportProvider inProcessA2aTransportProvider() {
        return new InProcessA2aTransportProvider();
    }
}
