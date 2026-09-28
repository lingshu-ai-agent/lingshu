package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.exception.LingsConfigException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #026 — unit tests for {@link PlaceholderResolver}.
 *
 * <p>Deliberately a separate file from {@link MinimalYamlParserTest}:
 * the parser tests {@link AgentFactory#parseMinimalYaml(String)} (string → tree),
 * these tests cover the placeholder resolver (tree → tree-with-substitutions).
 * Keeping them apart makes the failure surface easier to read.
 *
 * <p>System properties are cleared in {@code @AfterEach} so cases don't bleed
 * across tests. Environment variables are read-only — tests that need env-var
 * semantics use the env-var lookup fallback (test the system-property path
 * instead, which is mocked the same way via {@link System#setProperty}).
 */
class PlaceholderResolverTest {

    /** Any key this test sets must be cleared afterwards to avoid bleed. */
    private static final String[] TRACKED_SYS_PROPS = {
        "LINGS_TEST_USER_DIR",
        "LINGS_TEST_HOST",
        "LINGS_TEST_A",
        "LINGS_TEST_B",
        "LINGS_TEST_C",
        "LINGS_TEST_CYCLE_A",
        "LINGS_TEST_CYCLE_B"
    };

    @BeforeEach
    @AfterEach
    void clearTrackedProps() {
        for (String k : TRACKED_SYS_PROPS) {
            System.clearProperty(k);
        }
    }

    // ── happy paths ────────────────────────────────────────────────────

    @Test
    @DisplayName("${X} resolves via system property")
    void resolvesFromSystemProperty() {
        System.setProperty("LINGS_TEST_USER_DIR", "/home/alice");
        Map<String, Object> tree = map("sandbox",
            map("working-directory", "${LINGS_TEST_USER_DIR}"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        assertThat(tree.get("sandbox")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        assertThat(sandbox.get("working-directory")).isEqualTo("/home/alice");
    }

    @Test
    @DisplayName("${X:default} returns default when X is missing")
    void defaultUsedWhenMissing() {
        Map<String, Object> tree = map("sandbox",
            map("working-directory", "${LINGS_TEST_USER_DIR:/tmp/default}"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        assertThat(sandbox.get("working-directory")).isEqualTo("/tmp/default");
    }

    @Test
    @DisplayName("${X:default} uses env value when present (default ignored)")
    void envValueWinsOverDefault() {
        System.setProperty("LINGS_TEST_HOST", "from-sysprop");
        Map<String, Object> tree = map("llm", map("model", "${LINGS_TEST_HOST:fallback}"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) tree.get("llm");
        assertThat(llm.get("model")).isEqualTo("from-sysprop");
    }

    @Test
    @DisplayName("${X:${Y}} nested — Y resolves first then used as default for X")
    void nestedDefaultResolvesFirst() {
        System.setProperty("LINGS_TEST_B", "from-B");
        Map<String, Object> tree = map("sandbox",
            map("working-directory", "${LINGS_TEST_A:${LINGS_TEST_B}}"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        assertThat(sandbox.get("working-directory")).isEqualTo("from-B");
    }

    @Test
    @DisplayName("${X:${Y:fallback}} nested with own default")
    void nestedWithItsOwnDefault() {
        // Neither LINGS_TEST_A nor LINGS_TEST_B set → use fallback
        Map<String, Object> tree = map("sandbox",
            map("working-directory", "${LINGS_TEST_A:${LINGS_TEST_B:/deep-fallback}}"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        assertThat(sandbox.get("working-directory")).isEqualTo("/deep-fallback");
    }

    @Test
    @DisplayName("$${literal} escape emits ${literal} without resolving")
    void dollarEscape() {
        // $${USER} should NOT try to resolve USER from env
        Map<String, Object> tree = map("prompt",
            map("builder", "$${USER}_literal"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> prompt = (Map<String, Object>) tree.get("prompt");
        assertThat(prompt.get("builder")).isEqualTo("${USER}_literal");
    }

    @Test
    @DisplayName("lone $ not followed by ${ passes through verbatim")
    void loneDollarPassthrough() {
        Map<String, Object> tree = map("prompt", map("builder", "$ harmless $x"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> prompt = (Map<String, Object>) tree.get("prompt");
        assertThat(prompt.get("builder")).isEqualTo("$ harmless $x");
    }

    @Test
    @DisplayName("unclosed ${X emits literally (typo safety)")
    void unclosedBraceEmitsLiterally() {
        Map<String, Object> tree = map("prompt",
            map("builder", "${UNCLOSED is not valid"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> prompt = (Map<String, Object>) tree.get("prompt");
        assertThat(prompt.get("builder")).isEqualTo("${UNCLOSED is not valid");
    }

    @Test
    @DisplayName("multiple placeholders in single scalar all resolved")
    void multiplePlaceholdersInOneScalar() {
        System.setProperty("LINGS_TEST_HOST", "alpha");
        System.setProperty("LINGS_TEST_USER_DIR", "beta");
        Map<String, Object> tree = map("sandbox",
            map("policy", "${LINGS_TEST_HOST}-policy-${LINGS_TEST_USER_DIR}"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        assertThat(sandbox.get("policy")).isEqualTo("alpha-policy-beta");
    }

    @Test
    @DisplayName("block-list items are resolved individually")
    void blockListItemsResolved() {
        System.setProperty("LINGS_TEST_A", "alpha");
        Map<String, Object> tree = map("sandbox",
            map("command-whitelist", listOf("${LINGS_TEST_A}", "literal", "${MISSING:gamma}")));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        @SuppressWarnings("unchecked")
        List<Object> wl = (List<Object>) sandbox.get("command-whitelist");
        assertThat(wl).containsExactly("alpha", "literal", "gamma");
    }

    @Test
    @DisplayName("non-string scalars (Integer, Boolean) pass through untouched")
    void nonStringScalarsPassThrough() {
        Map<String, Object> tree = new LinkedHashMap<String, Object>();
        Map<String, Object> llm = new LinkedHashMap<String, Object>();
        llm.put("maxTokens", 8192);
        llm.put("deterministic", Boolean.TRUE);
        llm.put("model", "${MISSING:fallback}");
        tree.put("llm", llm);

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> llmAfter = (Map<String, Object>) tree.get("llm");
        assertThat(llmAfter.get("maxTokens")).isEqualTo(8192);
        assertThat(llmAfter.get("deterministic")).isEqualTo(Boolean.TRUE);
        assertThat(llmAfter.get("model")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("null scalar passes through as null")
    void nullScalarPassesThrough() {
        Map<String, Object> tree = map("sandbox", map("working-directory", null));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        assertThat(sandbox.get("working-directory")).isNull();
    }

    @Test
    @DisplayName("plain scalar without ${ passes through verbatim")
    void plainScalarUnchanged() {
        Map<String, Object> tree = map("sandbox", map("policy", "strict"));

        PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sandbox = (Map<String, Object>) tree.get("sandbox");
        assertThat(sandbox.get("policy")).isEqualTo("strict");
    }

    // ── failure paths ──────────────────────────────────────────────────

    @Test
    @DisplayName("${MISSING} throws LingsConfigException LINGS-C03")
    void missingPlaceholderThrowsC03() {
        Map<String, Object> tree = map("sandbox",
            map("working-directory", "${LINGS_TEST_USER_DIR}"));

        assertThatThrownBy(() ->
            PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml")))
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("LINGS-C03")
            .hasMessageContaining("LINGS_TEST_USER_DIR");
    }

    @Test
    @DisplayName("${MISSING} without default AND missing key throws C03")
    void missingNestedInnerThrowsC03() {
        // ${A:${B}} where both are missing — B has no default → C03
        Map<String, Object> tree = map("sandbox",
            map("working-directory", "${LINGS_TEST_A:${LINGS_TEST_B}}"));

        assertThatThrownBy(() ->
            PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml")))
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("LINGS-C03");
    }

    @Test
    @DisplayName("placeholder cycle (A→B→A via nested defaults) throws LINGS-C04")
    void cycleDetectedThrowsC04() {
        // A is missing → fallback to ${B:${A}}. B is missing → fallback to ${A}.
        // Resolution stack grows to {A, B, A} → cycle detected on re-entry to A.
        System.clearProperty("LINGS_TEST_CYCLE_A");
        System.clearProperty("LINGS_TEST_CYCLE_B");
        Map<String, Object> tree = map("sandbox",
            map("working-directory",
                "${LINGS_TEST_CYCLE_A:${LINGS_TEST_CYCLE_B:${LINGS_TEST_CYCLE_A}}}"));

        assertThatThrownBy(() ->
            PlaceholderResolver.resolvePlaceholders(tree, Paths.get("application.yml")))
            .isInstanceOf(LingsConfigException.class)
            .hasMessageContaining("LINGS-C04")
            .hasMessageContaining("cycle");
    }

    @Test
    @DisplayName("resolvePlaceholderExpression single-form API resolves scalar")
    void singleExpressionApi() {
        System.setProperty("LINGS_TEST_C", "from-c");
        assertThat(PlaceholderResolver.resolvePlaceholderExpression("${LINGS_TEST_C}"))
            .isEqualTo("from-c");
        assertThat(PlaceholderResolver.resolvePlaceholderExpression("${MISSING:fb}"))
            .isEqualTo("fb");
        assertThat(PlaceholderResolver.resolvePlaceholderExpression("plain"))
            .isEqualTo("plain");
    }

    // ── helpers ────────────────────────────────────────────────────────

    /** Builds a one-key map (LinkedHashMap to preserve insertion order). */
    private static Map<String, Object> map(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put(k, v);
        return m;
    }

    @SafeVarargs
    private static <T> List<T> listOf(T... items) {
        List<T> l = new ArrayList<T>(items.length);
        for (T item : items) l.add(item);
        return l;
    }
}