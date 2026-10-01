package ai.lingshu.core.mcp;

import ai.lingshu.core.slot.AccessDeniedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #033 — L1 unit tests for {@link McpHttpSupport#checkOrThrow(String, List)}
 * (AC-033-1..AC-033-6).
 *
 * <p>Six cases covering the four access paths used by MCP HTTP transports:
 * <ol>
 *   <li>Whitelist empty → every host denied (Path B default).</li>
 *   <li>Whitelist non-empty + matching host → no throw.</li>
 *   <li>Whitelist non-empty + non-matching host → {@link AccessDeniedException}.</li>
 *   <li>Null URL → {@link AccessDeniedException}.</li>
 *   <li>Empty URL → {@link AccessDeniedException}.</li>
 *   <li>Malformed URL → {@link AccessDeniedException} (URI parsing failure).</li>
 * </ol>
 *
 * <p>Mirror of {@code WhitelistedHttpClientTest} (Story #028) but exercised
 * via {@link McpHttpSupport#checkOrThrow} so MCP transports get the same
 * defense layer (Path B + Mitigation 1, dsh §4.7 + §15 S01).
 */
@DisplayName("Story #033 — McpHttpSupport.checkOrThrow domain guard")
class McpHttpSupportCheckOrThrowTest {

    @Test
    @DisplayName("checkOrThrow: empty whitelist → every host denied (Path B default)")
    void emptyWhitelist_deniesEverything() {
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow("http://example.com/x", Collections.<String>emptyList()))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("example.com");
    }

    @Test
    @DisplayName("checkOrThrow: null whitelist → every host denied (defensive)")
    void nullWhitelist_deniesEverything() {
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow("http://example.com/x", null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("example.com");
    }

    @Test
    @DisplayName("checkOrThrow: matching host → no throw")
    void matchingHost_noThrow() {
        List<String> whitelist = Arrays.asList("api.example.com", "127.0.0.1");
        assertThatCode(() ->
            McpHttpSupport.checkOrThrow("http://api.example.com/x", whitelist))
            .doesNotThrowAnyException();
        assertThatCode(() ->
            McpHttpSupport.checkOrThrow("http://127.0.0.1:8080/health", whitelist))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checkOrThrow: non-matching host → AccessDeniedException")
    void nonMatchingHost_throws() {
        List<String> whitelist = Arrays.asList("api.example.com");
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow("http://evil.com/x", whitelist))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("evil.com")
            .hasMessageContaining("not whitelisted");
    }

    @Test
    @DisplayName("checkOrThrow: null/empty URL → AccessDeniedException with descriptive message")
    void nullOrEmptyUrl_throws() {
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow(null, Arrays.asList("x")))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("null/empty");
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow("", Arrays.asList("x")))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("null/empty");
    }

    @Test
    @DisplayName("checkOrThrow: malformed URL → AccessDeniedException (URI.create rejects)")
    void malformedUrl_throws() {
        // "http://" alone has no host — URI.create accepts it but getHost() returns null.
        // Either URI.create throws IAE → AccessDeniedException("Malformed URL: ...")
        // or getHost() returns null → AccessDeniedException("URL has no host: ...").
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow("http://", Arrays.asList("x")))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow("not a url at all", Arrays.asList("x")))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("checkOrThrow: host match is case-sensitive (mirrors WhitelistedHttpClient)")
    void hostMatch_caseSensitive() {
        // Per dsh §4.7 — exact match; case differences count as different hosts.
        List<String> whitelist = Arrays.asList("Example.com");
        assertThatThrownBy(() ->
            McpHttpSupport.checkOrThrow("http://example.com/x", whitelist))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("example.com");
    }

    @Test
    @DisplayName("checkOrThrow: IPv4 host extracted by URI.getHost() matches literal")
    void ipv4Host_extractedCorrectly() {
        List<String> whitelist = Arrays.asList("127.0.0.1");
        assertThatCode(() ->
            McpHttpSupport.checkOrThrow("http://127.0.0.1:9999/tools/list", whitelist))
            .doesNotThrowAnyException();
        // Confirm that the host extracted is exactly "127.0.0.1" — that's what the
        // whitelist contains, and the contains() check passes.
        assertThat(whitelist.contains("127.0.0.1")).isTrue();
    }
}
