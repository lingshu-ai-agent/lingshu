package ai.lingshu.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #017 — Smoke test for {@link Main} and the CLI error reporting path.
 *
 * <p>Full Spring Boot bootstrap is covered by manual E2E (see {@code Main.main} via
 * {@code mvn spring-boot:run}); spinning a Spring context in unit tests tends to
 * trigger unwanted auto-config attempts (MongoDB / Redis / metrics exporters) that
 * hang in CI sandboxes without those services. We instead verify:
 *
 * <ul>
 *   <li>{@link Main} exists with the right class shape (entry point, no-arg main)</li>
 *   <li>CLI exit-code mapping works for LINGS-Z01 → 2 (the most common error class)</li>
 * </ul>
 */
class MainIntegrationTest {

    @Test
    void main_class_hasExpectedShape() throws Exception {
        Class<?> mainClass = Class.forName("ai.lingshu.cli.Main");
        assertThat(mainClass.getDeclaredMethod("main", String[].class))
            .isNotNull();
        assertThat(java.lang.reflect.Modifier.isPublic(mainClass.getModifiers()))
            .isTrue();
    }

    @Test
    void cliRunner_isAnnotatedAsSpringComponent() {
        // CliRunner must be a @Component for Spring to autowire it
        assertThat(CliRunner.class.isAnnotationPresent(
            org.springframework.stereotype.Component.class)).isTrue();
        assertThat(java.util.Arrays.asList(CliRunner.class.getInterfaces()))
            .extracting(Class::getName)
            .contains("org.springframework.boot.ApplicationRunner");
    }

    @Test
    void lingsZ01_exitCode_is2() {
        LingsCliException ex = new LingsCliException("LINGS-Z01", "no subcommand", "usage");
        assertThat(ex.getExitCode()).isEqualTo(2);
        assertThat(ex.getMessage()).contains("[LINGS-Z01]");
    }
}
