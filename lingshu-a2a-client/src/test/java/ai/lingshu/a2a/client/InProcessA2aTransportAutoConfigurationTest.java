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
 * L1 unit tests — {@link InProcessA2aTransportAutoConfiguration} (3 cases per data-model.md DM-04).
 *
 * <p>Validates the AutoConfiguration class shape + the SPI registration file.
 * We intentionally avoid {@code org.springframework.boot.test.context.runner.ApplicationContextRunner}
 * because that requires {@code spring-boot-test} as a test dependency, which
 * is not in the project's locked dependency set (R-13 mitigation (d)).</p>
 *
 * <p>Spring Boot picks up {@code META-INF/spring/...AutoConfiguration.imports}
 * at runtime — verifying the file contents is sufficient evidence the bean
 * will be registered. The actual Spring integration is covered by the
 * {@link A2aServerLifecycleTest} (uses real Spring context).</p>
 */
class InProcessA2aTransportAutoConfigurationTest {

    @Test
    @DisplayName("TC-AC-IP-1: autoconfig_class_isAnnotated_andExposesProviderBean")
    void autoconfig_class_isAnnotated_andExposesProviderBean() throws Exception {
        // Reflective check — class exists, is annotated with @AutoConfiguration,
        // and has a @Bean method returning Providers.A2aTransportProvider.
        Class<?> clazz = InProcessA2aTransportAutoConfiguration.class;
        assertThat(clazz.isAnnotationPresent(
            org.springframework.boot.autoconfigure.AutoConfiguration.class))
            .as("must be @AutoConfiguration")
            .isTrue();

        boolean hasBeanMethod = false;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (m.isAnnotationPresent(org.springframework.context.annotation.Bean.class)
                && Providers.A2aTransportProvider.class.isAssignableFrom(m.getReturnType())) {
                hasBeanMethod = true;
                // verify @Bean name follows §5.4 unique-name convention
                org.springframework.context.annotation.Bean bean =
                    m.getAnnotation(org.springframework.context.annotation.Bean.class);
                assertThat(bean.name())
                    .as("@Bean name must follow §5.4 convention a2aTransportProvider_<name>")
                    .containsExactly("a2aTransportProvider_in-process-1.0.0");

                // invoke the method and verify the returned Provider
                m.setAccessible(true);
                Object beanInstance = m.invoke(clazz.getDeclaredConstructor().newInstance());
                assertThat(beanInstance)
                    .isInstanceOf(Providers.A2aTransportProvider.class)
                    .isInstanceOf(InProcessA2aTransportProvider.class);
                Providers.A2aTransportProvider provider = (Providers.A2aTransportProvider) beanInstance;
                assertThat(provider.name()).isEqualTo("in-process-1.0.0");
                assertThat(provider.priority()).isEqualTo(10);
                assertThat(provider.version()).isEqualTo("1.0.0");
            }
        }
        assertThat(hasBeanMethod)
            .as("AutoConfiguration must declare a @Bean method returning A2aTransportProvider")
            .isTrue();
    }

    @Test
    @DisplayName("TC-AC-IP-2: inProcessProviderBeanName_isDistinctFromGrpcBeanName")
    void inProcessProviderBeanName_isDistinctFromGrpcBeanName() {
        // bean names from both AutoConfigurations must coexist (🆕 v1.5.28 multi-Provider mode, §5.5)
        String inProcess = beanNameFrom(InProcessA2aTransportAutoConfiguration.class);
        String grpc = beanNameFrom(GrpcA2aTransportAutoConfiguration.class);

        assertThat(inProcess).isEqualTo("a2aTransportProvider_in-process-1.0.0");
        assertThat(grpc).isEqualTo("a2aTransportProvider_grpc-1.0.0");
        assertThat(inProcess).isNotEqualTo(grpc);
    }

    @Test
    @DisplayName("TC-AC-IP-3: importsFile_containsBothGrpcAndInProcessLines")
    void importsFile_containsBothGrpcAndInProcessLines() throws Exception {
        // The SPI registration file at META-INF/spring/...AutoConfiguration.imports
        // must contain both #009a's grpc line and #009b's in-process line.
        URL importsUrl = getClass().getClassLoader().getResource(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
        assertThat(importsUrl)
            .as("AutoConfiguration.imports file must be on classpath")
            .isNotNull();

        Set<String> lines = new HashSet<>();
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
    }

    // --- helpers -----------------------------------------------------------

    private static String beanNameFrom(Class<?> clazz) {
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            org.springframework.context.annotation.Bean bean =
                m.getAnnotation(org.springframework.context.annotation.Bean.class);
            if (bean != null) {
                String[] names = bean.name();
                return names.length > 0 ? names[0] : m.getName();
            }
        }
        return null;
    }
}
