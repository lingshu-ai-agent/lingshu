package ai.lingshu.core.permission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #029 — L1 unit test for {@link PermissionErrorCodes} (1 case).
 *
 * <p>Locks the P-domain ErrorCode prefix identity so downstream code can
 * rely on {@code Decision.Deny.reason.startsWith("[LINGS-P01]")}.
 */
class PermissionErrorCodesTest {

    @Test
    @DisplayName("AC-029-7: LINGS_P01_constant_value")
    void LINGS_P01_constant_value() {
        assertThat(PermissionErrorCodes.LINGS_P01).isEqualTo("LINGS-P01");
    }
}
