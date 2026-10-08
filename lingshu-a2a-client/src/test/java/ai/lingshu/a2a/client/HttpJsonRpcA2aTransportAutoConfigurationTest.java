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
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.spi.Providers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 unit tests — {@link HttpJsonRpcA2aTransportAutoConfiguration} (Story #009c + #009e).
 *
 * <p><b>Story #009e</b>: this class was slimmed down to ONE {@code @Bean}
 * (the transport provider). The previously-tested
 * {@code remoteAgentTool} + {@code remoteAgentSchemaBuilder} beans have
 * moved to {@link RemoteAgentToolAutoConfiguration} and are tested by
 * {@link RemoteAgentToolAutoConfigurationTest}.</p>
 *
 * <p>Mirrors {@link InProcessA2aTransportAutoConfigurationTest}: validates the
 * AutoConfiguration class shape + SPI registration file. Uses reflection (no
 * {@code spring-boot-test} dependency) — R-13 mitigation (d) forbids additions.</p>
 */
class HttpJsonRpcA2aTransportAutoConfigurationTest {

    @Test
    @DisplayName("TC-AC-HTTP-1: autoconfig_class_isAnnotated_andExposesProviderBean_only")
    void autoconfig_class_isAnnotated_andExposesProviderBean_only() throws Exception {
        Class<?> clazz = HttpJsonRpcA2aTransportAutoConfiguration.class;
        assertThat(clazz.isAnnotationPresent(
            org.springframework.boot.autoconfigure.AutoConfiguration.class))
            .as("must be @AutoConfiguration")
            .isTrue();

        boolean foundProviderBean = false;
        int totalBeanMethods = 0;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) continue;
            totalBeanMethods++;
            org.springframework.context.annotation.Bean bean =
                m.getAnnotation(org.springframework.context.annotation.Bean.class);
            m.setAccessible(true);
            if (Providers.A2aTransportProvider.class.isAssignableFrom(m.getReturnType())) {
                foundProviderBean = true;
                // verify @Bean name follows §5.4 unique-name convention
                assertThat(bean.name())
                    .as("@Bean name must follow §5.4 convention a2aTransportProvider_<name>")
                    .containsExactly("a2aTransportProvider_http-jsonrpc-1.0.0");
                // invoke the method and verify the returned Provider
                Object beanInstance = m.invoke(clazz.getDeclaredConstructor().newInstance());
                assertThat(beanInstance)
                    .isInstanceOf(Providers.A2aTransportProvider.class)
                    .isInstanceOf(HttpJsonRpcA2aTransportProvider.class);
                Providers.A2aTransportProvider provider = (Providers.A2aTransportProvider) beanInstance;
                assertThat(provider.name()).isEqualTo("http-jsonrpc-1.0.0");
                assertThat(provider.priority()).isEqualTo(10);
                assertThat(provider.version()).isEqualTo("1.0.0");
            }
        }
        assertThat(foundProviderBean).isTrue();
        // 🆕 Story #009e: 1 provider @Bean (remoteAgentTool + remoteAgentSchemaBuilder
        // have moved to RemoteAgentToolAutoConfiguration).
        // 🆕 Story #034: 2nd @Bean is HttpJsonRpcA2aTransportFactory for sandbox
        // domain-whitelist aggregation.
        assertThat(totalBeanMethods)
            .as("HttpJsonRpcA2aTransportAutoConfiguration exposes 2 @Beans "
                + "(provider + HttpJsonRpcA2aTransportFactory) post-#034")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("TC-AC-HTTP-2: httpJsonRpcBeanName_isDistinctFromGrpcAndInProcessBeanNames")
    void httpJsonRpcBeanName_isDistinctFromGrpcAndInProcessBeanNames() {
        String http = beanNameFrom(HttpJsonRpcA2aTransportAutoConfiguration.class);
        String grpc = beanNameFrom(GrpcA2aTransportAutoConfiguration.class);
        String inProcess = beanNameFrom(InProcessA2aTransportAutoConfiguration.class);

        assertThat(http).isEqualTo("a2aTransportProvider_http-jsonrpc-1.0.0");
        assertThat(grpc).isEqualTo("a2aTransportProvider_grpc-1.0.0");
        assertThat(inProcess).isEqualTo("a2aTransportProvider_in-process-1.0.0");
        // All 3 must be distinct (🆕 v1.5.28 multi-Provider mode)
        assertThat(http).isNotEqualTo(grpc).isNotEqualTo(inProcess);
    }

    @Test
    @DisplayName("TC-AC-HTTP-3: importsFile_containsAllFourLines_includingRemoteAgentToolAutoConfiguration")
    void importsFile_containsAllFourLines_includingRemoteAgentToolAutoConfiguration() throws Exception {
        URL importsUrl = getClass().getClassLoader().getResource(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(importsUrl)
            .as("AutoConfiguration.imports file must be on classpath")
            .isNotNull();

        Set<String> lines = new HashSet<String>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(importsUrl.openStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    lines.add(trimmed);
                }
            }
        }

        // 🆕 Story #009e — 4 lines now: RemoteAgentTool + 3 transports
        assertThat(lines)
            .as("imports file must contain 4 entries (RemoteAgentTool + 3 transports)")
            .hasSize(4);
        assertThat(lines).anyMatch(l -> l.contains("RemoteAgentToolAutoConfiguration"));
        assertThat(lines).anyMatch(l -> l.contains("GrpcA2aTransportAutoConfiguration"));
        assertThat(lines).anyMatch(l -> l.contains("InProcessA2aTransportAutoConfiguration"));
        assertThat(lines).anyMatch(l -> l.contains("HttpJsonRpcA2aTransportAutoConfiguration"));
    }

    private static String beanNameFrom(Class<?> clazz) {
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            org.springframework.context.annotation.Bean bean =
                m.getAnnotation(org.springframework.context.annotation.Bean.class);
            if (bean != null
                && Providers.A2aTransportProvider.class.isAssignableFrom(m.getReturnType())) {
                String[] names = bean.name();
                return names.length > 0 ? names[0] : m.getName();
            }
        }
        return null;
    }

    // ─── Story #034 — factory dispatch + union whitelist ──────────────────

    @Test
    @DisplayName("TC-AC-HTTP-4: factory_build_unionsDomainWhitelistAcrossAllAgentRefs")
    void factory_build_unionsDomainWhitelistAcrossAllAgentRefs() {
        // 🆕 Story #034 — verify HttpJsonRpcA2aTransportFactory.build() unions
        // domainWhitelist across N configured AgentRefs (deduped).
        AgentRef refA = new AgentRef("alice", "http://alice:8080", 10,
            Arrays.asList("a.com", "b.com"));
        AgentRef refB = new AgentRef("bob", "http://bob:8080", 5,
            Arrays.asList("b.com", "c.com"));  // "b.com" duplicates refA
        AgentRef refC = new AgentRef("carol", null, 1,
            null);  // null whitelist — must not NPE, just no-op

        AgentConfig cfg = buildCfgWithRemoteAgents(Arrays.asList(refA, refB, refC));
        AgentCardCache cache = new AgentCardCache(Duration.ofMinutes(5));

        HttpJsonRpcA2aTransportAutoConfiguration.HttpJsonRpcA2aTransportFactory factory =
            new HttpJsonRpcA2aTransportAutoConfiguration.HttpJsonRpcA2aTransportFactory(
                cfg, new ObjectMapper(), cache);

        // previewWhitelistUnion is the test-only accessor; build() is exercised
        // implicitly by union computation here.
        List<String> union = factory.previewWhitelistUnion();

        // Deduped across A+B; C contributes nothing; order undefined (HashSet-backed).
        assertThat(union)
            .as("factory must dedupe whitelists across AgentRefs and skip null/empty ones")
            .containsExactlyInAnyOrder("a.com", "b.com", "c.com")
            .hasSize(3);

        // build() returns a transport whose whitelist matches the union.
        HttpJsonRpcA2aTransport transport = factory.build();
        assertThat(transport.getDomainWhitelist())
            .as("factory.build() must wire the unioned whitelist into the transport")
            .containsExactlyInAnyOrder("a.com", "b.com", "c.com")
            .hasSize(3);
    }

    /**
     * Minimal {@link AgentConfig} carrying three A2A customisations. Mirrors
     * {@code RemoteAgentToolAutoConfigurationTest#buildDefaultAgentConfig} shape.
     */
    private static AgentConfig buildCfgWithRemoteAgents(List<AgentRef> remoteAgents) {
        AgentConfig.A2a a2a = new AgentConfig.A2a(
            "0.0.0.0", 8080,
            "localhost:50051", Duration.ofMinutes(5),
            "http://localhost:8080", Duration.ofSeconds(30),
            remoteAgents, 10
        );
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
            null,
            null,
            a2a,
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default"
                ,
        16,		// 🆕 Story #044 — maxConcurrentTurns
        32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }
}