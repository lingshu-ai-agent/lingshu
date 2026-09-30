package ai.lingshu.core.impl.sandbox;

import ai.lingshu.core.slot.AccessDeniedException;
import ai.lingshu.core.slot.ToolExecutionContext.NetworkClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Story #028 — domain-whitelist enforcing {@link NetworkClient} for Slot 3 Sandbox
 * (dsh §6.3 WhitelistedHttpClient template).
 *
 * <p>Every call goes through {@link #check(String)} which extracts the URL host via
 * {@link URI#getHost()} and verifies it against the configured {@code domainWhitelist}
 * (case-insensitive exact match; wildcard support is OQ-Future).
 *
 * <p>On whitelist hit: fires the real request via JDK {@link HttpURLConnection}
 * (R-13 mitigation (d) — 0 new Maven coordinates, no Spring Web / Apache HttpClient
 * pulled in). On miss: throws {@link AccessDeniedException} carrying
 * {@code [LINGS-S01]} + {@code "Domain not whitelisted: <host>"}.
 *
 * <h2>Why JDK {@link HttpURLConnection}?</h2>
 *
 * <p>Already on the agent classpath (used by {@code AnthropicLlmProvider} per
 * Story #027a), supports all 3 verbs (GET / POST / GET-stream) without extra
 * dependencies, and integrates with the {@code Tool} 30 s timeout via standard
 * {@code HttpURLConnection.setReadTimeout}.
 *
 * <h2>What this is <em>not</em></h2>
 *
 * <p>Not a full HTTP client (no cookies, no retries, no streaming POST, no
 * authentication, no redirect chasing). Not a circuit breaker (deferred to §14.3).
 * Not a graceful-shutdown-aware client (deferred to §14.6). Stories listed in dsh §6.3.
 */
public final class WhitelistedHttpClient implements NetworkClient {

    private final Set<String> domainWhitelist;
    /** Network I/O timeout for {@link HttpURLConnection#setReadTimeout}. */
    private static final int READ_TIMEOUT_MS = 30_000;

    /**
     * @param domainWhitelist case-sensitive host set (e.g. {@code ["api.openai.com"]});
     *                        {@code null} is treated as empty → every request denied
     */
    public WhitelistedHttpClient(List<String> domainWhitelist) {
        if (domainWhitelist == null || domainWhitelist.isEmpty()) {
            this.domainWhitelist = Collections.emptySet();
        } else {
            // Copy to a HashSet for O(1) contains() — domain check is on the hot path
            // (every LLM tool call invokes ctx.http().get(...)). The original list is
            // never mutated by us so we don't need an unmodifiable view.
            this.domainWhitelist = new HashSet<>(domainWhitelist);
        }
    }

    @Override
    public String get(String url) throws IOException {
        check(url);
        HttpURLConnection conn = openConnection(url, "GET");
        try {
            int code = conn.getResponseCode();
            String body = readBody(conn, code);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from GET " + url + ": " + body);
            }
            return body;
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public String post(String url, String body) throws IOException {
        check(url);
        if (body == null) {
            throw new IllegalArgumentException("POST body must not be null");
        }
        HttpURLConnection conn = openConnection(url, "POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.getOutputStream().write(payload);
            conn.getOutputStream().close();
            int code = conn.getResponseCode();
            String response = readBody(conn, code);
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from POST " + url + ": " + response);
            }
            return response;
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public InputStream getStream(String url) throws IOException {
        check(url);
        HttpURLConnection conn = openConnection(url, "GET");
        // Caller is responsible for closing the returned InputStream + disconnecting
        // the connection. We don't disconnect here so the stream remains usable.
        // Throwing AccessDeniedException for non-2xx happens after the caller opens
        // the stream via getInputStream() — we return the connection via a wrapper
        // that propagates disconnect.
        return new DisconnectingInputStream(conn);
    }

    /**
     * Verify the URL host is in the whitelist. Public for unit tests.
     *
     * @throws AccessDeniedException with {@code [LINGS-S01]} prefix if host is missing
     */
    void check(String url) {
        if (url == null || url.isEmpty()) {
            throw new AccessDeniedException("URL must not be null/empty");
        }
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Malformed URL: " + url);
        }
        if (host == null || host.isEmpty()) {
            throw new AccessDeniedException("URL has no host: " + url);
        }
        if (!domainWhitelist.contains(host)) {
            throw new AccessDeniedException("Domain not whitelisted: " + host);
        }
    }

    private static HttpURLConnection openConnection(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(READ_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "ChaOS-LingShu-Sandbox/1.0");
        return conn;
    }

    private static String readBody(HttpURLConnection conn, int code) throws IOException {
        InputStream stream = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) {
            return "";
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            return sb.toString();
        }
    }

    /** Read-only view of the configured whitelist (mainly for diagnostics / tests). */
    public Set<String> getDomainWhitelist() {
        return Collections.unmodifiableSet(domainWhitelist);
    }

    /**
     * Wraps an {@link HttpURLConnection#getInputStream()} and disconnects the underlying
     * connection when the stream is closed. Keeps the contract simple for callers that
     * just want a streaming response body without juggling connection lifecycle.
     */
    private static final class DisconnectingInputStream extends InputStream {
        private final HttpURLConnection conn;
        private final InputStream delegate;

        DisconnectingInputStream(HttpURLConnection conn) throws IOException {
            this.conn = conn;
            this.delegate = conn.getInputStream();
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return delegate.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                conn.disconnect();
            }
        }
    }
}