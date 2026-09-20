package ai.lingshu.core.impl.router;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #002 US2 — MemorySourceRouter contract tests.
 * See contracts/memory-source.md §6 table.
 */
class MemorySourceRouterTest {

    private static Providers.MemorySourceProvider provider(String name, int priority) {
        return new Providers.MemorySourceProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return priority; }
            @Override public String version() { return "1.0.0"; }
            @Override public MemorySource create(AgentConfig cfg) {
                return new MemorySource() {
                    @Override public String name() { return name; }
                    @Override public int priority() { return priority; }
                    @Override public String load(ai.lingshu.core.runtime.TurnContext c) { return null; }
                };
            }
        };
    }

    @Test
    @DisplayName("resolveAll_emptyList_returnsEmpty")
    void resolveAll_emptyList_returnsEmpty() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(Collections.<Providers.MemorySourceProvider>emptyList());
        List<MemorySource> result = router.resolveAll(Collections.<String>emptyList(), AgentConfigDefaults.defaults());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveAll_nullList_returnsEmpty")
    void resolveAll_nullList_returnsEmpty() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(Collections.<Providers.MemorySourceProvider>emptyList());
        List<MemorySource> result = router.resolveAll(null, AgentConfigDefaults.defaults());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("resolveAll_singleKnownName_returnsOne")
    void resolveAll_singleKnownName_returnsOne() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(
            Arrays.asList(provider("identity", 30)));
        List<MemorySource> result = router.resolveAll(
            Collections.singletonList("identity"), AgentConfigDefaults.defaults());
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("resolveAll_fourKnownNames_preservesInputOrder")
    void resolveAll_fourKnownNames_preservesInputOrder() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(Arrays.asList(
            provider("project-claude-md", 10),
            provider("user-claude-md", 20),
            provider("identity", 30),
            provider("project-tree", 40)));

        // yml order is reverse of priority — Router must NOT re-sort by priority
        List<String> ymlOrder = Arrays.asList("project-tree", "identity", "project-claude-md", "user-claude-md");
        List<MemorySource> result = router.resolveAll(ymlOrder, AgentConfigDefaults.defaults());

        assertThat(result).hasSize(4);
        assertThat(result.get(0).name()).isEqualTo("project-tree");
        assertThat(result.get(1).name()).isEqualTo("identity");
        assertThat(result.get(2).name()).isEqualTo("project-claude-md");
        assertThat(result.get(3).name()).isEqualTo("user-claude-md");
    }

    @Test
    @DisplayName("resolveAll_unknownName_throws")
    void resolveAll_unknownName_throws() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(
            Arrays.asList(provider("identity", 30)));
        assertThatThrownBy(() -> router.resolveAll(
            Arrays.asList("identity", "does-not-exist"), AgentConfigDefaults.defaults()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does-not-exist")
            .hasMessageContaining("identity");
    }

    @Test
    @DisplayName("resolve_knownName_returnsCorrectProvider")
    void resolve_knownName_returnsCorrectProvider() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(Arrays.asList(
            provider("identity", 30),
            provider("project-tree", 40)));
        MemorySource ms = router.resolve("identity", AgentConfigDefaults.defaults());
        assertThat(ms).isNotNull();
        assertThat(ms.name()).isEqualTo("identity");
    }

    @Test
    @DisplayName("resolve_unknownName_throws")
    void resolve_unknownName_throws() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(
            Arrays.asList(provider("identity", 30)));
        assertThatThrownBy(() -> router.resolve("missing", AgentConfigDefaults.defaults()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("constructor_resolvesFourDefaults_allAvailable")
    void constructor_resolvesFourDefaults_allAvailable() {
        // Simulate what Spring would inject for the 4 default Providers
        List<Providers.MemorySourceProvider> defaults = Arrays.asList(
            provider("project-claude-md", 10),
            provider("user-claude-md", 20),
            provider("identity", 30),
            provider("project-tree", 40));
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(defaults);

        assertThat(router.available()).containsExactlyInAnyOrder(
            "project-claude-md", "user-claude-md", "identity", "project-tree");
    }

    @Test
    @DisplayName("resolveAll_duplicateNames_returnsTwoInstancesInOrder")
    void resolveAll_duplicateNames_returnsTwoInstancesInOrder() {
        Routers.MemorySourceRouter router = new Routers.MemorySourceRouter(
            Arrays.asList(provider("identity", 30)));
        List<MemorySource> result = router.resolveAll(
            Arrays.asList("identity", "identity"), AgentConfigDefaults.defaults());
        assertThat(result).hasSize(2);
        // Both are non-null MemorySource instances (caller's responsibility to dedupe)
        assertThat(result.get(0)).isNotNull();
        assertThat(result.get(1)).isNotNull();
    }

    /** Unused helper to keep List/ArrayList imports quiet. */
    @SuppressWarnings("unused")
    private static List<String> touchImports() { return new ArrayList<>(); }
}
