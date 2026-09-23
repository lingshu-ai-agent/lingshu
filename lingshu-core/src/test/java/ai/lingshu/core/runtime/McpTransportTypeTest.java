package ai.lingshu.core.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #021a — McpTransportType enum contract test (L1, AC-021a-deps-1).
 *
 * <p>Single black-box case asserting the three values exist in the documented
 * order. Order is part of the contract because downstream code may rely on
 * {@code values()[0]} being {@code STDIO}.
 */
@DisplayName("Story #021a — McpTransportType contract")
class McpTransportTypeTest {

    @Test
    @DisplayName("values() returns [STDIO, SSE, STREAMABLE_HTTP] in declared order")
    void assertThreeValuesInOrder() {
        McpTransportType[] vs = McpTransportType.values();
        assertThat(vs).hasSize(3);
        assertThat(vs[0]).isEqualTo(McpTransportType.STDIO);
        assertThat(vs[1]).isEqualTo(McpTransportType.SSE);
        assertThat(vs[2]).isEqualTo(McpTransportType.STREAMABLE_HTTP);
    }
}