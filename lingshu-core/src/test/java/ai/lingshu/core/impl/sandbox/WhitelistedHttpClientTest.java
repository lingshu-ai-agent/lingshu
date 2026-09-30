package ai.lingshu.core.impl.sandbox;

import ai.lingshu.core.slot.AccessDeniedException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #028 — L1/L2 tests for {@link WhitelistedHttpClient} (dsh §6.3 domain-whitelist HTTP client).
 *
 * <p>AC-NN-3 contract:
 * <ol>
 *   <li>Non-whitelisted host → {@link AccessDeniedException} with {@code [LINGS-S01]} prefix
 *       and a message including {@code "Domain not whitelisted"}</li>
 *   <li>Whitelisted host → real {@code HttpURLConnection} fires and returns the body
 *       (verified via a local {@link HttpServer})</li>
 *   <li>{@code getStream()} → returns an {@link InputStream} readable for whitelisted hosts</li>
 *   <li>URI host parsing edge cases — null / empty / malformed / host-less URL</li>
 * </ol>
 */
class WhitelistedHttpClientTest {

    private HttpServer server;
    private int port;
    private WhitelistedHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        // Whitelist the loopback host — tests bind 127.0.0.1:<random>
        client = new WhitelistedHttpClient(Arrays.asList("127.0.0.1"));
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("AC-NN-3: non-whitelisted host throws AccessDeniedException with [LINGS-S01]")
    void evilDomainThrowsAccessDenied() {
        WhitelistedHttpClient walled = new WhitelistedHttpClient(Arrays.asList("api.openai.com"));

        assertThatThrownBy(() -> walled.check("https://evil.example.com/foo"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("Domain not whitelisted")
            .hasMessageContaining("evil.example.com");
    }

    @Test
    @DisplayName("AC-NN-3: whitelisted host fires real connection via HttpURLConnection")
    void whitelistedHostFiresRealHttp() throws IOException {
        server.createContext("/hello", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = "hi".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });

        String body = client.get("http://127.0.0.1:" + port + "/hello");

        assertThat(body).isEqualTo("hi");
    }

    @Test
    @DisplayName("AC-NN-3: whitelisted host POST echoes JSON payload")
    void whitelistedHostPostEchoesPayload() throws IOException {
        server.createContext("/echo", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] reqBody = readAll(ex.getRequestBody());
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, reqBody.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(reqBody);
                }
            }
        });

        String response = client.post(
            "http://127.0.0.1:" + port + "/echo",
            "{\"q\":\"hello\"}");

        assertThat(response).isEqualTo("{\"q\":\"hello\"}");
    }

    @Test
    @DisplayName("AC-NN-3: getStream returns InputStream readable for whitelisted host")
    void getStreamReturnsReadableStream() throws IOException {
        server.createContext("/stream", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = "streamed-body".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });

        try (InputStream in = client.getStream("http://127.0.0.1:" + port + "/stream")) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            assertThat(n).isGreaterThan(0);
            assertThat(new String(buf, 0, n, StandardCharsets.UTF_8))
                .isEqualTo("streamed-body");
        }
    }

    @Test
    @DisplayName("AC-NN-3: null/empty URL throws AccessDeniedException [LINGS-S01]")
    void nullOrEmptyUrlThrows() {
        WhitelistedHttpClient walled = new WhitelistedHttpClient(Arrays.asList("api.openai.com"));

        assertThatThrownBy(() -> walled.check(null))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("URL must not be null/empty");

        assertThatThrownBy(() -> walled.check(""))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("URL must not be null/empty");
    }

    @Test
    @DisplayName("AC-NN-3: malformed URL throws AccessDeniedException [LINGS-S01]")
    void malformedUrlThrows() {
        WhitelistedHttpClient walled = new WhitelistedHttpClient(Arrays.asList("api.openai.com"));

        // "not a url" contains spaces → URI.create throws IllegalArgumentException
        assertThatThrownBy(() -> walled.check("not a url"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("Malformed URL");
    }

    @Test
    @DisplayName("AC-NN-3: host-less URL (relative form) throws AccessDeniedException")
    void hostlessUrlThrows() {
        WhitelistedHttpClient walled = new WhitelistedHttpClient(Arrays.asList("api.openai.com"));

        // "mailto:foo@bar.com" → URI.getHost() returns null
        assertThatThrownBy(() -> walled.check("mailto:foo@bar.com"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]")
            .hasMessageContaining("URL has no host");
    }

    @Test
    @DisplayName("AC-NN-3: null whitelist treated as empty — every request denied")
    void nullWhitelistDeniesAll() {
        WhitelistedHttpClient walled = new WhitelistedHttpClient(null);

        assertThat(walled.getDomainWhitelist()).isEmpty();
        assertThatThrownBy(() -> walled.check("https://api.openai.com/foo"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Domain not whitelisted");
    }

    @Test
    @DisplayName("AC-NN-3: empty whitelist denies all requests")
    void emptyWhitelistDeniesAll() {
        WhitelistedHttpClient walled = new WhitelistedHttpClient(Collections.<String>emptyList());

        assertThat(walled.getDomainWhitelist()).isEmpty();
        assertThatThrownBy(() -> walled.check("https://api.openai.com/foo"))
            .isInstanceOf(AccessDeniedException.class)
            .hasMessageContaining("Domain not whitelisted");
    }

    @Test
    @DisplayName("AC-NN-3: 5xx from whitelisted host surfaces as IOException (not AccessDenied)")
    void serverErrorSurfacesAsIOException() {
        server.createContext("/fail", new HttpHandler() {
            @Override
            public void handle(HttpExchange ex) throws IOException {
                byte[] body = "boom".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(500, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        });

        assertThatThrownBy(() -> client.get("http://127.0.0.1:" + port + "/fail"))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("HTTP 500");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[256];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}