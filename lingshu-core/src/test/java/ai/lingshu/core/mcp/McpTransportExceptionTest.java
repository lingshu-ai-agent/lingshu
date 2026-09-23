package ai.lingshu.core.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — {@link McpTransportException} L1 test (AC-021a-deps-2).
 *
 * <p>Single case verifying the carrier exception preserves code + message.
 */
@DisplayName("Story #021a — McpTransportException contract")
class McpTransportExceptionTest {

    @Test
    @DisplayName("carriesCodeAndMessage")
    void carriesCodeAndMessage() {
        McpTransportException ex = new McpTransportException("LINGS-M01", "SSE not implemented");
        assertThat(ex.getCode()).isEqualTo("LINGS-M01");
        assertThat(ex.getMessage()).isEqualTo("SSE not implemented");
    }
}