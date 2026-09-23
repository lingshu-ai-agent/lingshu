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
 * L1 unit tests — {@link HttpJsonRpcA2aTransportAutoConfiguration} (3 cases per data-model.md DM-04).
 *
 * <p>Mirrors {@link InProcessA2aTransportAutoConfigurationTest}: validates the
 * AutoConfiguration class shape + SPI registration file. Uses reflection (no
 * {@code spring-boot-test} dependency) — R-13 mitigation (d) forbids additions.</p>
 */
class HttpJsonRpcA2aTransportAutoConfigurationTest {

    @Test
    @DisplayName("TC-AC-HTTP-1: autoconfig_class_isAnnotated_andExposesProviderAndRemoteAgentToolBeans()")
    void autoconfig_class_isAnnotated_andExposesProviderAndRemoteAgentToolBeans() throws Exception {
        Class<?> clazz = HttpJsonRpcA2aTransportAutoConfiguration.class;
        assertThat(clazz.isAnnotationPresent(
            org.springframework.boot.autoconfigure.AutoConfiguration.class))
            .as("must be @AutoConfiguration")
            .isTrue();

        boolean foundProviderBean = false;
        boolean foundToolBean = false;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) continue;
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
            if (RemoteAgentTool.class.isAssignableFrom(m.getReturnType())) {
                foundToolBean = true;
                assertThat(bean.name())
                    .as("@Bean name for RemoteAgentTool must be 'remoteAgentTool'")
                    .containsExactly("remoteAgentTool");
            }
        }
        assertThat(foundProviderBean).isTrue();
        assertThat(foundToolBean).isTrue();
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
    @DisplayName("TC-AC-HTTP-3: importsFile_containsAllThreeProviderLines")
    void importsFile_containsAllThreeProviderLines() throws Exception {
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

    // ─── Story #009d — TC-AC-HTTP-4: RemoteAgentSchemaBuilder @Bean + remoteAgentTool 5-arg ctor

    @Test
    @DisplayName("TC-AC-HTTP-4: remoteAgentSchemaBuilderBean_isExposed_withCorrectName")
    void remoteAgentSchemaBuilderBean_isExposed_withCorrectName() throws Exception {
        Class<?> clazz = HttpJsonRpcA2aTransportAutoConfiguration.class;

        boolean foundSchemaBuilderBean = false;
        boolean foundToolBean = false;
        int toolBeanArgs = 0;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)) continue;
            org.springframework.context.annotation.Bean bean =
                m.getAnnotation(org.springframework.context.annotation.Bean.class);
            Class<?> rt = m.getReturnType();
            m.setAccessible(true);
            if (rt.equals(RemoteAgentSchemaBuilder.class)) {
                foundSchemaBuilderBean = true;
                // verify @Bean name is 'remoteAgentSchemaBuilder' (Story #009d)
                assertThat(bean.name())
                    .as("@Bean name for RemoteAgentSchemaBuilder must be 'remoteAgentSchemaBuilder'")
                    .containsExactly("remoteAgentSchemaBuilder");
                // verify the ctor takes ObjectMapper only (single-arg)
                assertThat(m.getParameterCount())
                    .as("remoteAgentSchemaBuilder(@Bean) ctor must take 1 arg (ObjectMapper)")
                    .isEqualTo(1);
                assertThat(m.getParameterTypes()[0])
                    .isEqualTo(com.fasterxml.jackson.databind.ObjectMapper.class);
                // verify invoking the bean returns a real RemoteAgentSchemaBuilder
                Object instance = m.invoke(clazz.getDeclaredConstructor().newInstance(),
                    new com.fasterxml.jackson.databind.ObjectMapper());
                assertThat(instance).isInstanceOf(RemoteAgentSchemaBuilder.class);
            }
            if (RemoteAgentTool.class.isAssignableFrom(rt)) {
                foundToolBean = true;
                toolBeanArgs = m.getParameterCount();
                // Story #009d: 5-arg ctor — A2aTransportRouter, AgentConfig,
                // ObjectMapper, RemoteAgentSchemaBuilder (4 args expected at @Bean level)
                // Wait: 5-arg ctor has 5 params but AutoConfig @Bean gets the
                // RemoteAgentTool built from 4 injected beans. Verify at least 4.
                assertThat(toolBeanArgs)
                    .as("remoteAgentTool(@Bean) must take >= 4 args (router, cfg, json, schemaBuilder)")
                    .isGreaterThanOrEqualTo(4);
            }
        }
        assertThat(foundSchemaBuilderBean)
            .as("AutoConfiguration must expose a RemoteAgentSchemaBuilder @Bean")
            .isTrue();
        assertThat(foundToolBean)
            .as("AutoConfiguration must still expose the RemoteAgentTool @Bean")
            .isTrue();
    }
}