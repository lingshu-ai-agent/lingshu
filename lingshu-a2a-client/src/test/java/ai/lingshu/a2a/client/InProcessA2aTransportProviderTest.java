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

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 unit tests — {@link InProcessA2aTransportProvider} (3 cases per data-model.md DM-03).
 */
class InProcessA2aTransportProviderTest {

    @Test
    @DisplayName("TC-PROV-IP-1: provider_metadata_namePriorityVersionAreCorrect")
    void provider_metadata_namePriorityVersionAreCorrect() {
        InProcessA2aTransportProvider provider = new InProcessA2aTransportProvider();

        assertThat(provider.name()).isEqualTo("in-process-1.0.0");
        assertThat(provider.priority()).isEqualTo(10);
        assertThat(provider.version()).isEqualTo("1.0.0");
        // distinct from grpc-1.0.0 (no name collision per §5.2 / §5.5)
        assertThat(provider.name()).isNotEqualTo("grpc-1.0.0");
    }

    @Test
    @DisplayName("TC-PROV-IP-2: create_validCfg_returnsInProcessA2aTransport_withCardCache")
    void create_validCfg_returnsInProcessA2aTransport_withCardCache() {
        InProcessA2aTransportProvider provider = new InProcessA2aTransportProvider();

        A2aTransport t = provider.create(cfg());

        assertThat(t).isInstanceOf(InProcessA2aTransport.class);
        InProcessA2aTransport it = (InProcessA2aTransport) t;
        assertThat(it.getRegistry()).isNotNull();
        // same singleton across JVM (same instance every time)
        assertThat(it.getRegistry())
            .isSameAs(ai.lingshu.core.a2a.client.InProcessA2aRegistry.getInstance());
        assertThat(it.getCardCache()).isNotNull();
        assertThat(it.getCardCache().getCacheTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("TC-PROV-IP-3: create_nullCfg_usesHardcodedDefaults (forward-compat)")
    void create_nullCfg_usesHardcodedDefaults() {
        InProcessA2aTransportProvider provider = new InProcessA2aTransportProvider();

        A2aTransport t = provider.create(null);

        assertThat(t).isInstanceOf(InProcessA2aTransport.class);
        InProcessA2aTransport it = (InProcessA2aTransport) t;
        assertThat(it.getCardCache().getCacheTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("TC-PROV-IP-4: provider_implementsA2aTransportProviderInterface")
    void provider_implementsA2aTransportProviderInterface() {
        InProcessA2aTransportProvider provider = new InProcessA2aTransportProvider();
        assertThat(provider).isInstanceOf(Providers.A2aTransportProvider.class);
    }

    // --- helpers ---

    private static AgentConfig cfg() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("default", "noop",
                java.nio.file.Paths.get("."), Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            "default", "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            "default",
            null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default"
                ,
        16,		// 🆕 Story #044 — maxConcurrentTurns
        32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }
}
