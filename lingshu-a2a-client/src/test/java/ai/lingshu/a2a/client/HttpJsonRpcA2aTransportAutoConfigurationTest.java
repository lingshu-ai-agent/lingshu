package ai.lingshu.a2a.client;

import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
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
        // 🆕 Story #009e: only 1 @Bean (the provider) — remoteAgentTool + remoteAgentSchemaBuilder
        // have moved to RemoteAgentToolAutoConfiguration.
        assertThat(totalBeanMethods)
            .as("HttpJsonRpcA2aTransportAutoConfiguration now exposes exactly 1 @Bean (provider)")
            .isEqualTo(1);
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
}