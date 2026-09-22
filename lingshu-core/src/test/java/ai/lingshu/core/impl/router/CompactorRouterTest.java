package ai.lingshu.core.impl.router;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.compaction.TruncatingCompactorProvider;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.spi.Providers;
import ai.lingshu.core.spi.ProviderInitException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #018 — L1 unit tests for {@link Routers.CompactorRouter}.
 *
 * <p>Mirrors the contract pattern used by {@link MemorySourceRouterTest} —
 * verifies resolution, name conflict handling, and contract-version enforcement.
 */
class CompactorRouterTest {

    @Test
    @DisplayName("AC-018-7: singleProvider_resolvesCorrectly")
    void singleProvider_resolvesCorrectly() {
        TruncatingCompactorProvider truncating = new TruncatingCompactorProvider();
        Routers.CompactorRouter router = new Routers.CompactorRouter(
            Collections.<Providers.CompactorProvider>singletonList(truncating));

        Compactor c = router.resolve("truncating", AgentConfigDefaults.defaults());
        assertThat(c).isNotNull();
        assertThat(router.available()).contains("truncating");
    }

    @Test
    @DisplayName("EC-018-11: unknownName_throwsIllegalArgumentException")
    void unknownName_throwsIllegalArgumentException() {
        TruncatingCompactorProvider truncating = new TruncatingCompactorProvider();
        Routers.CompactorRouter router = new Routers.CompactorRouter(
            Collections.<Providers.CompactorProvider>singletonList(truncating));

        assertThatThrownBy(() -> router.resolve("does-not-exist", AgentConfigDefaults.defaults()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("EC-018-12: versionMismatch_throwsProviderInitException")
    void versionMismatch_throwsProviderInitException() {
        Providers.CompactorProvider bad = new Providers.CompactorProvider() {
            @Override public String name() { return "weird"; }
            @Override public int priority() { return 5; }
            @Override public String version() { return "99.0.0"; }
            @Override public Compactor create(AgentConfig cfg) { return null; }
        };

        assertThatThrownBy(() -> new Routers.CompactorRouter(
            Collections.<Providers.CompactorProvider>singletonList(bad)))
            .isInstanceOf(ProviderInitException.class)
            .hasMessageContaining("LINGS-S05");
    }

    @Test
    @DisplayName("EC-018-13: emptyProviderList_routerResolvesNothing")
    void emptyProviderList_routerResolvesNothing() {
        Routers.CompactorRouter router = new Routers.CompactorRouter(
            Collections.<Providers.CompactorProvider>emptyList());

        assertThat(router.available()).isEmpty();
        assertThatThrownBy(() -> router.resolve("anything", AgentConfigDefaults.defaults()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
