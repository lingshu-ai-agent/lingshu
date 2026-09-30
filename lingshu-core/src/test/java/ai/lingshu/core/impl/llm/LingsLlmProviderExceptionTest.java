package ai.lingshu.core.impl.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #027a — {@link LingsLlmProviderException} L1 tests (AC-NN-deps-1
 * adjacent). Verifies the carrier exception preserves code + message,
 * embeds the {@code [<code>]} prefix in {@link #getMessage()}, and is
 * idempotent against double-wrapping.
 *
 * <p>Aligns with {@code McpTransportExceptionTest} (Story #021a) and
 * {@code YamlPlaceholderErrorCodes} exception-bearer pattern.
 */
@DisplayName("Story #027a — LingsLlmProviderException contract")
class LingsLlmProviderExceptionTest {

    @Test
    @DisplayName("carriesCodeAndMessage_basic")
    void carriesCodeAndMessage_basic() {
        LingsLlmProviderException ex = new LingsLlmProviderException(
            LlmErrorCodes.LINGS_L01,
            "tool_use id missing");

        assertThat(ex.getCode()).isEqualTo("LINGS-L01");
        // getMessage must embed [LINGS-L01] prefix so test assertions like
        // hasMessageContaining("LINGS-L01") work without explicit code lookup.
        assertThat(ex.getMessage()).isEqualTo("[LINGS-L01] tool_use id missing");
    }

    @Test
    @DisplayName("getMessage_doubleWrapIdempotent")
    void getMessage_doubleWrapIdempotent() {
        // Simulate a caller that pre-formats a message with the bracket prefix
        // (e.g. a re-throw wrapper). The exception must not double-wrap.
        LingsLlmProviderException ex = new LingsLlmProviderException(
            LlmErrorCodes.LINGS_L02,
            "[LINGS-L02] tool_result toolUseId missing");

        assertThat(ex.getMessage()).isEqualTo("[LINGS-L02] tool_result toolUseId missing");
    }

    @Test
    @DisplayName("getMessage_handlesNullMessage")
    void getMessage_handlesNullMessage() {
        LingsLlmProviderException ex = new LingsLlmProviderException(
            LlmErrorCodes.LINGS_L01, null);

        assertThat(ex.getCode()).isEqualTo("LINGS-L01");
        assertThat(ex.getMessage()).isEqualTo("[LINGS-L01]");
    }

    @Test
    @DisplayName("ctorWithCause_preservesBoth")
    void ctorWithCause_preservesBoth() {
        Throwable cause = new IllegalStateException("upstream");
        LingsLlmProviderException ex = new LingsLlmProviderException(
            LlmErrorCodes.LINGS_L01, "wrapping", cause);

        assertThat(ex.getCode()).isEqualTo("LINGS-L01");
        assertThat(ex.getMessage()).isEqualTo("[LINGS-L01] wrapping");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("assertjHasMessageContaining_worksViaBracketPrefix")
    void assertjHasMessageContaining_worksViaBracketPrefix() {
        // Demonstrates the canonical assertion form documented in the exception Javadoc.
        assertThatThrownBy(() -> {
            throw new LingsLlmProviderException(LlmErrorCodes.LINGS_L01, "missing id");
        })
            .isInstanceOf(LingsLlmProviderException.class)
            .hasMessageContaining("LINGS-L01")
            .hasMessageContaining("missing id");
    }
}