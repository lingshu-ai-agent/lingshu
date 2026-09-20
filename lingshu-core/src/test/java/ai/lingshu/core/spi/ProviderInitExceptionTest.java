package ai.lingshu.core.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #003 — {@link ProviderInitException} (LINGS-S05) tests (4 scenarios).
 *
 * <p>Covers contracts/slot-version-compat.md §3 — error code, cause chain length,
 * hint field, and message format.
 */
class ProviderInitExceptionTest {

    @Test
    @DisplayName("errorCodeFieldIsLINGS-S05")
    void errorCodeFieldIsLINGS_S05() {
        ProviderInitException ex = new ProviderInitException(
            "test message",
            new IllegalArgumentException("root cause"),
            "test hint");
        assertThat(ex.getErrorCode()).isEqualTo("LINGS-S05");
    }

    @Test
    @DisplayName("causeChainLengthAtLeast2")
    void causeChainLengthAtLeast2() {
        IllegalArgumentException root = new IllegalArgumentException("version 'v1' invalid");
        ProviderInitException ex = new ProviderInitException(
            "Provider version malformed",
            root,
            "implement version() returning '1.0.0'");
        // depth 1: this exception -> depth 2: root IllegalArgumentException
        assertThat(ex.getCause()).isNotNull();
        assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
        assertThat(ex.getCause().getMessage()).isEqualTo("version 'v1' invalid");
        assertThat(ex.getCause().getCause()).isNull(); // root cause has no further cause
        // Chain depth >= 2 (this + at least one cause)
        int depth = 0;
        Throwable cur = ex;
        while (cur != null) {
            depth++;
            cur = cur.getCause();
        }
        assertThat(depth).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("hintFieldNonEmptyOnVersionMismatch")
    void hintFieldNonEmptyOnVersionMismatch() {
        ProviderInitException ex = new ProviderInitException(
            "version mismatch",
            new IllegalArgumentException("major mismatch"),
            "implement version() returning '1.0.0' on your @Component class");
        assertThat(ex.getHint()).isNotEmpty();
        assertThat(ex.getHint()).contains("implement version()");
        // message format must include both code and hint
        assertThat(ex.getMessage()).startsWith("LINGS-S05:");
        assertThat(ex.getMessage()).contains("(hint:");
        assertThat(ex.getMessage()).contains("implement version()");
    }

    @Test
    @DisplayName("hintFieldEmptyWhenNullProvided")
    void hintFieldEmptyWhenNullProvided() {
        ProviderInitException ex = new ProviderInitException(
            "no hint",
            new IllegalArgumentException("cause"),
            null);
        assertThat(ex.getHint()).isEqualTo("");
        // message format must NOT include "(hint:" when hint is null
        assertThat(ex.getMessage()).startsWith("LINGS-S05: no hint");
        assertThat(ex.getMessage()).doesNotContain("(hint:");
    }

    @Test
    @DisplayName("isInstanceOfIllegalStateException_forSpringStartupCompatibility")
    void isInstanceOfIllegalStateException_forSpringStartupCompatibility() {
        // Spring's @Component constructor failure surfaces IllegalStateException
        // to ApplicationContext, which logs at ERROR level + exits JVM with code 1.
        ProviderInitException ex = new ProviderInitException(
            "test",
            new IllegalArgumentException("cause"),
            "hint");
        assertThat(ex).isInstanceOf(IllegalStateException.class);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
