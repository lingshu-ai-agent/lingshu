package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.exception.LingsConfigException;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Story #026 — post-parse {@code ${...}} placeholder resolver for the
 * hand-rolled {@link AgentFactory#parseMinimalYaml(String) MinimalYamlParser}.
 *
 * <h2>Why this class exists</h2>
 *
 * LingShu has two YAML ingestion paths (dsh §6.5 (1)):
 *
 * <ol>
 *   <li><b>Spring Environment path</b> (demo-product) — Spring's built-in
 *       {@code Environment.getProperty()} resolves {@code ${X}} natively,
 *       so {@code ${user.dir}} in {@code application.yml} just works.</li>
 *   <li><b>Hand-rolled path</b> (CLI / {@code YamlWatcher} hot-reload) —
 *       {@link AgentFactory#parseMinimalYaml(String)} returns {@code ${X}}
 *       as a literal token, so {@code ${user.dir}} silently resolves to the
 *       13-char string {@code "${user.dir}"} and the demo breaks at runtime.</li>
 * </ol>
 *
 * <p>This resolver closes that gap. It runs as a recursive walk <b>after</b>
 * {@code parseMinimalYaml} returns its {@code Map<String, Object>} tree,
 * mutating string scalars in place. Wired in {@code AgentFactory.loadYamlAndValidate}
 * between the parser and {@code toAgentConfig(...)}.
 *
 * <h2>Grammar (4 forms)</h2>
 *
 * <pre>{@code
 *   ${X}              required — env → sys-prop; missing → LINGS-C03
 *   ${X:default}      default — env → sys-prop; missing → use literal "default"
 *                              (first ':' splits; defaults may contain further ':')
 *   ${X:${Y}}         nested — recursively resolve ${Y} first, use as default for ${X}
 *                              (inner may itself be required or have its own default)
 *   $${literal}       escape — emits the literal string "${literal}" without resolving
 * }</pre>
 *
 * <h2>Lookup order (locked)</h2>
 *
 * <ol>
 *   <li>{@link System#getenv(String)} — process environment variables</li>
 *   <li>{@link System#getProperty(String)} — JVM {@code -DX=...} system props</li>
 * </ol>
 *
 * <h2>Non-string scalars</h2>
 *
 * Integers, booleans, and other non-string leaves are passed through verbatim.
 * Lists and maps are walked recursively.
 *
 * <h2>JDK 8 only</h2>
 *
 * No {@code var}, no {@code List.of}, no records / sealed / pattern-matching.
 * Uses {@link Collections#emptyList()}, {@link LinkedHashSet}, {@link ArrayDeque}.
 *
 * @see AgentFactory#loadYamlAndValidate(Path)
 * @see YamlPlaceholderErrorCodes
 */
public final class PlaceholderResolver {

    /** Per-resolve call cycle-detection bound — generous but finite. */
    private static final int MAX_RESOLUTION_DEPTH = 32;

    private PlaceholderResolver() {
        throw new AssertionError("PlaceholderResolver is a static utility — do not instantiate");
    }

    /**
     * Walk a parsed YAML tree in place, resolving every string scalar that
     * contains a {@code ${...}} reference. {@link Map} / {@link List} children
     * are walked recursively; non-string scalars are returned untouched.
     *
     * @param node    a parsed YAML node (typically {@code Map<String,Object>}
     *                returned from {@link AgentFactory#parseMinimalYaml(String)};
     *                may also be a {@code List} or scalar — recursion handles all)
     * @param ymlPath source yml path used only for error messages
     * @return the same node instance with strings mutated in place (mutates input)
     * @throws LingsConfigException {@code LINGS-C03} on unresolved required
     *         placeholder; {@code LINGS-C04} on recursive cycle
     */
    public static Object resolvePlaceholders(Object node, Path ymlPath) {
        return walk(node, ymlPath, Collections.<String>emptySet(), 0);
    }

    /**
     * Resolve a single scalar expression (used both directly and from the
     * nested-default resolver when walking {@code ${X:${Y}}}). Exposed for
     * unit tests; production callers use {@link #resolvePlaceholders(Object, Path)}.
     *
     * @param expr the raw scalar string (e.g. {@code "${user.dir}"},
     *             {@code "literal"}, {@code "${X:fallback}"})
     * @return the resolved string (never {@code null}; empty string allowed)
     * @throws LingsConfigException on unresolved / cycle
     */
    public static String resolvePlaceholderExpression(String expr) {
        return resolveScalar(expr, Collections.<String>emptySet(), 0);
    }

    // ── private walker ──────────────────────────────────────────────────

    private static Object walk(Object node, Path ymlPath,
                               Set<String> visited, int depth) {
        if (depth > MAX_RESOLUTION_DEPTH) {
            throw new LingsConfigException(YamlPlaceholderErrorCodes.LINGS_C04,
                "LINGS-C04 YAML_PLACEHOLDER_CYCLE: placeholder resolution exceeded max depth "
                    + MAX_RESOLUTION_DEPTH + " (possible cycle) in " + ymlPath);
        }
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                e.setValue(walk(e.getValue(), ymlPath, visited, depth + 1));
            }
            return map;
        }
        if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            for (int i = 0; i < list.size(); i++) {
                list.set(i, walk(list.get(i), ymlPath, visited, depth + 1));
            }
            return list;
        }
        if (node instanceof String) {
            return resolveScalar((String) node, visited, depth);
        }
        // Integer / Boolean / null — pass through verbatim
        return node;
    }

    /**
     * Resolve a single scalar string with full 4-form grammar.
     *
     * <p>Implementation: brace-counting scanner (not regex — regex cannot
     * express nested {@code ${X:${Y}}} cleanly). Tracks {@code visited} names
     * across recursive calls so cycles throw {@code LINGS-C04} instead of
     * overflowing the stack.
     */
    private static String resolveScalar(String raw, Set<String> visited, int depth) {
        if (raw == null || raw.isEmpty() || raw.indexOf('$') < 0) {
            return raw;
        }

        StringBuilder out = new StringBuilder(raw.length());
        int i = 0;
        int n = raw.length();
        while (i < n) {
            char c = raw.charAt(i);
            if (c == '$' && i + 1 < n && raw.charAt(i + 1) == '$') {
                // Escape: $$ → literal $
                out.append('$');
                i += 2;
                continue;
            }
            if (c == '$' && i + 1 < n && raw.charAt(i + 1) == '{') {
                // Find matching close brace (skipping nested ${...}).
                int close = findMatchingBrace(raw, i + 2);
                if (close < 0) {
                    // Unclosed ${ — emit literally so user sees the typo
                    // rather than a silent truncation.
                    out.append(raw, i, n);
                    break;
                }
                String expr = raw.substring(i + 2, close);
                String resolved = resolveOneExpression(expr, visited, depth, raw);
                out.append(resolved);
                i = close + 1;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * Find the index of the {@code }} that closes the {@code ${} that opens
     * at position {@code openIdx}. Tracks nested {@code ${...}} so
     * {@code ${X:${Y}}} parses correctly. Returns {@code -1} if unclosed.
     */
    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 1;
        int i = openIdx;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '$' && i + 1 < n && s.charAt(i + 1) == '{') {
                depth++;
                i += 2;
                continue;
            }
            if (c == '}') {
                depth--;
                if (depth == 0) return i;
                i++;
                continue;
            }
            i++;
        }
        return -1;
    }

    /**
     * Resolve the contents between {@code ${} and {@code }} (i.e. {@code X} or
     * {@code X:default}). Splits on the first {@code :} so defaults may contain
     * further {@code :}.
     */
    private static String resolveOneExpression(String expr, Set<String> parentVisited,
                                               int depth, String originalRaw) {
        if (depth > MAX_RESOLUTION_DEPTH) {
            throw new LingsConfigException(YamlPlaceholderErrorCodes.LINGS_C04,
                "LINGS-C04 YAML_PLACEHOLDER_CYCLE: placeholder resolution exceeded max depth "
                    + MAX_RESOLUTION_DEPTH + " (possible cycle) for expression '${" + expr + "}'");
        }

        // Split on first ':'. Default may contain further ${...} which we
        // resolve recursively (handles ${X:${Y}}).
        String name;
        String defaultPart;
        int firstColon = expr.indexOf(':');
        if (firstColon < 0) {
            name = expr.trim();
            defaultPart = null;
        } else {
            name = expr.substring(0, firstColon).trim();
            defaultPart = expr.substring(firstColon + 1);
        }

        // Empty name + non-empty default is a degenerate form: just resolve
        // the default. (No cycle risk — name is empty.)
        if (name.isEmpty()) {
            return resolveScalar(defaultPart == null ? "" : defaultPart, parentVisited, depth + 1);
        }

        // Cycle detection: if name is already on the current resolution stack,
        // we're in a cycle (A's default references B, B's default references A).
        if (parentVisited.contains(name)) {
            throw new LingsConfigException(YamlPlaceholderErrorCodes.LINGS_C04,
                "LINGS-C04 YAML_PLACEHOLDER_CYCLE: placeholder cycle detected involving '"
                    + name + "' (visited: " + parentVisited + ")");
        }

        // Push current name onto a fresh visited set; pass to nested resolution.
        Set<String> nextVisited = new LinkedHashSet<String>(parentVisited);
        nextVisited.add(name);

        String value = lookup(name);
        if (value != null) {
            // value may itself contain ${...} — resolveScalar handles that.
            return resolveScalar(value, nextVisited, depth + 1);
        }
        if (defaultPart != null) {
            // Default may be a nested placeholder (${X:${Y}}); resolveScalar
            // recurses through brace-counting + visited.
            return resolveScalar(defaultPart, nextVisited, depth + 1);
        }
        throw new LingsConfigException(YamlPlaceholderErrorCodes.LINGS_C03,
            "LINGS-C03 YAML_PLACEHOLDER_UNRESOLVED: placeholder '${" + expr + "}' could not be resolved "
                + "(env / system-property '" + name + "' not set)");
    }

    /**
     * Lookup order (locked):
     * <ol>
     *   <li>{@link System#getenv(String)}</li>
     *   <li>{@link System#getProperty(String)}</li>
     * </ol>
     *
     * <p>Returns {@code null} if neither yields a non-null value; the caller
     * then decides fail-fast vs fallback-to-default based on whether a
     * {@code :default} part is present.
     */
    private static String lookup(String name) {
        String envVal = System.getenv(name);
        if (envVal != null) return envVal;
        return System.getProperty(name);
    }
}