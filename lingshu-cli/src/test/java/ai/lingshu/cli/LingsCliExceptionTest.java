package ai.lingshu.cli;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #017 — {@link LingsCliException} unit tests. 2 cases covering:
 * <ul>
 *   <li>message rendering: code + message + hint are concatenated for stderr</li>
 *   <li>cause propagation: constructor preserves the wrapped exception</li>
 * </ul>
 *
 * <p>Exit-code mapping is also covered (table-driven via {@link LingsCliException#getExitCode()}).
 */
class LingsCliExceptionTest {

    @Test
    void ctor_withCodeAndHint_rendersBothInMessage() {
        LingsCliException ex = new LingsCliException("LINGS-Z01",
            "no subcommand given",
            "usage: lingshu {run|resume|serve|doctor|config}");

        assertThat(ex.getErrorCode()).isEqualTo("LINGS-Z01");
        assertThat(ex.getHint()).isEqualTo("usage: lingshu {run|resume|serve|doctor|config}");
        assertThat(ex.getExitCode()).isEqualTo(2);
        assertThat(ex.getMessage())
            .contains("[LINGS-Z01]")
            .contains("no subcommand given")
            .contains("hint: usage: lingshu");
    }

    @Test
    void ctor_withCause_propagatesToGetCause() {
        IllegalStateException root = new IllegalStateException("yaml bad");
        LingsCliException ex = new LingsCliException("LINGS-Z02",
            "config parse failed: yaml bad",
            "verify yaml syntax",
            root);

        assertThat(ex.getErrorCode()).isEqualTo("LINGS-Z02");
        assertThat(ex.getExitCode()).isEqualTo(3); // LINGS-Z02 → 3
        assertThat(ex.getCause()).isSameAs(root);
        assertThat(ex.getMessage()).contains("[LINGS-Z02]").contains("yaml bad");
    }
}
