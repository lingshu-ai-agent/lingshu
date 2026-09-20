package ai.lingshu.core.spi;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.LlmProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test-only Slot interface at v1.10.0 — lets us exercise the
 * "Provider minor 5 ≤ Slot minor 10" compatibility scenario
 * without polluting real Slot contracts.
 */
interface FutureLlmProvider {
    @ContractVersionRef
    String CONTRACT_VERSION = "1.10.0";
    String name();
}

/**
 * Story #003 — {@link SlotRouter} compatibility validation tests (6 scenarios).
 *
 * <p>Covers contracts/slot-version-compat.md §2.2 compatibility matrix. Uses a
 * minimal {@code LlmProviderRouter}-equivalent inline subclass to avoid loading
 * Spring, so each test is fast + isolated.
 *
 * <p>Each test constructs a Router with a single Slot (real {@link LlmProvider}
 * interface whose {@code CONTRACT_VERSION = "1.0.0"}), and one stub
 * {@link SlotProvider}.
 */
class SlotRouterCompatTest {

    /**
     * Build a Router backed by the real {@link LlmProvider} interface (whose
     * CONTRACT_VERSION is "1.0.0"). Pass a list of 1 stub Provider whose
     * version() is controlled by the test.
     */
    private static SlotRouter<SlotProvider<LlmProvider>, LlmProvider> buildRouter(
            SlotProvider<LlmProvider> p) {
        return new SlotRouter<SlotProvider<LlmProvider>, LlmProvider>(
            Collections.singletonList(p),
            "LlmProvider",
            LoggerFactory.getLogger(SlotRouterCompatTest.class)) {
            @Override
            protected Class<LlmProvider> getSlotInterface() {
                return LlmProvider.class;
            }
        };
    }

    /**
     * Build a Router backed by the test-only {@link FutureLlmProvider} interface
     * whose CONTRACT_VERSION is "1.10.0" — used to exercise the
     * "Provider minor 5 ≤ Slot minor 10" scenario.
     */
    private static SlotRouter<SlotProvider<FutureLlmProvider>, FutureLlmProvider> buildFutureRouter(
            SlotProvider<FutureLlmProvider> p) {
        return new SlotRouter<SlotProvider<FutureLlmProvider>, FutureLlmProvider>(
            Collections.singletonList(p),
            "FutureLlmProvider",
            LoggerFactory.getLogger(SlotRouterCompatTest.class)) {
            @Override
            protected Class<FutureLlmProvider> getSlotInterface() {
                return FutureLlmProvider.class;
            }
        };
    }

    /** Stub Provider whose metadata fields are fixed for the test. */
    private static SlotProvider<LlmProvider> stubProvider(String name, int priority, String version) {
        return new SlotProvider<LlmProvider>() {
            @Override public String name() { return name; }
            @Override public int priority() { return priority; }
            @Override public String version() { return version; }
            @Override public LlmProvider create(AgentConfig cfg) {
                throw new UnsupportedOperationException("stub — test should not call create()");
            }
        };
    }

    /** Stub Provider backed by FutureLlmProvider. */
    private static SlotProvider<FutureLlmProvider> futureStubProvider(String name, int priority, String version) {
        return new SlotProvider<FutureLlmProvider>() {
            @Override public String name() { return name; }
            @Override public int priority() { return priority; }
            @Override public String version() { return version; }
            @Override public FutureLlmProvider create(AgentConfig cfg) {
                throw new UnsupportedOperationException("stub — test should not call create()");
            }
        };
    }

    @Test
    @DisplayName("compatible_exactMatch_constructorSucceeds")
    void compatible_exactMatch_constructorSucceeds() {
        // Provider v1.0.0 vs slot v1.0.0 — compatible
        SlotRouter<SlotProvider<LlmProvider>, LlmProvider> router =
            buildRouter(stubProvider("anthropic", 10, "1.0.0"));
        assertThat(router.available()).containsExactly("anthropic");
    }

    @Test
    @DisplayName("compatible_minorLags_constructorSucceeds")
    void compatible_minorLags_constructorSucceeds() {
        // Provider v1.5.3 vs slot v1.10.0 — Provider minor 5 ≤ slot minor 10
        // Uses FutureLlmProvider (test-only Slot) to model a slot that has
        // evolved past v1.0.0.
        SlotRouter<SlotProvider<FutureLlmProvider>, FutureLlmProvider> router =
            buildFutureRouter(futureStubProvider("my-llm", 10, "1.5.3"));
        assertThat(router.available()).containsExactly("my-llm");
    }

    @Test
    @DisplayName("incompatible_minorAhead_throwsLINGS-S05")
    void incompatible_minorAhead_throwsLINGS_S05() {
        // Provider v1.10.0 vs slot v1.5.0 — Provider minor 10 > slot minor 5
        SlotProvider<LlmProvider> bad = stubProvider("my-llm", 10, "1.10.0");
        assertThatThrownBy(() -> buildRouter(bad))
            .isInstanceOf(ProviderInitException.class)
            .hasMessageContaining("LINGS-S05")
            .hasMessageContaining("my-llm")
            .hasMessageContaining("1.10.0")
            .hasMessageContaining("incompatible")
            .hasMessageContaining("hint")
            .extracting(e -> ((ProviderInitException) e).getErrorCode())
            .isEqualTo("LINGS-S05");
    }

    @Test
    @DisplayName("incompatible_majorMismatch_throwsLINGS-S05")
    void incompatible_majorMismatch_throwsLINGS_S05() {
        // Provider v2.0.0 vs slot v1.0.0 — major mismatch
        SlotProvider<LlmProvider> bad = stubProvider("my-llm", 10, "2.0.0");
        assertThatThrownBy(() -> buildRouter(bad))
            .isInstanceOf(ProviderInitException.class)
            .hasMessageContaining("LINGS-S05")
            .hasMessageContaining("major version mismatch")
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nullVersion_throwsLINGS-S05")
    void nullVersion_throwsLINGS_S05() {
        SlotProvider<LlmProvider> bad = stubProvider("bad-llm", 10, null);
        assertThatThrownBy(() -> buildRouter(bad))
            .isInstanceOf(ProviderInitException.class)
            .hasMessageContaining("LINGS-S05")
            .hasMessageContaining("bad-llm")
            .hasMessageContaining("null")
            .hasMessageContaining("hint")
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("invalidSemver_throwsLINGS-S05")
    void invalidSemver_throwsLINGS_S05() {
        // 'v1' is not 3-segment semver (also has v-prefix)
        SlotProvider<LlmProvider> bad = stubProvider("bad-llm", 10, "v1");
        assertThatThrownBy(() -> buildRouter(bad))
            .isInstanceOf(ProviderInitException.class)
            .hasMessageContaining("LINGS-S05")
            .hasMessageContaining("bad-llm")
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Bonus: verify describe() returns one line per Provider in the documented
     * format — proves US3 description output contract.
     */
    @Test
    @DisplayName("describe_returnsLinesPerProvider")
    void describe_returnsLinesPerProvider() {
        SlotRouter<SlotProvider<LlmProvider>, LlmProvider> router =
            buildRouter(stubProvider("anthropic", 10, "1.0.0"));
        List<String> lines = router.describe();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).isEqualTo("  anthropic v1.0.0 (priority=10)");
    }
}
