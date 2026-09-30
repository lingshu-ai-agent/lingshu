package ai.lingshu.core.impl.llm;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Test fixture — mock Anthropic SSE HTTP server (Story #027b).
 *
 * <p>Provides:
 * <ul>
 *   <li>{@link #findFreePort()} — picks a free TCP port via
 *       {@code new ServerSocket(0)} (avoids hard-coded ports in tests).</li>
 *   <li>{@link #startSseServer(int, List, Consumer)} — boots a JDK-built-in
 *       {@link HttpServer} that responds with a sequence of pre-canned SSE
 *       events, sleeping 20 ms between each event to simulate the real
 *       Anthropic streaming cadence (so tests can observe incremental
 *       {@code AgentEvent.TextDelta} emissions rather than a single burst).</li>
 *   <li>{@link #stopServer(HttpServer)} — graceful shutdown with executor
 *       pool drain.</li>
 * </ul>
 *
 * <p>The {@code requestBodyCapture} consumer receives the raw POST body so
 * callers can assert that the LLM provider sent the expected Anthropic
 * protocol payload (e.g. {@code tools:[]} + interleaved
 * {@code messages[].content} blocks per Story #027a).
 *
 * <p><b>JDK 8 / no new binaries</b> — uses {@code com.sun.net.httpserver.HttpServer}
 * (JDK built-in, same as the Story #027a mock server pattern) plus a
 * daemon-thread {@link ExecutorService}. No third-party test fixture library
 * introduced — keeps the dep-tree at 0 binary delta per R-13 mitigation (d).
 */
public final class AnthropicStreamTestSupport {

    /**
     * Per-event pacing. 20 ms matches Anthropic's typical chunk-arrival
     * cadence on a small model and is short enough that the full test
     * sequence completes well under the 5-second timeout the LLM provider
     * sets via {@code conn.setReadTimeout}.
     */
    private static final long EVENT_PACING_MS = 20L;

    private AnthropicStreamTestSupport() {
        // utility class
    }

    /**
     * Allocate a free TCP port on the loopback interface. Uses the
     * "open / close immediately" idiom so the kernel can hand the same
     * port to {@link HttpServer#create(InetSocketAddress, int)} without a
     * race window in practice.
     *
     * @return a port number that is currently free on {@code 127.0.0.1}.
     * @throws IOException if no free port can be allocated (very rare).
     */
    public static int findFreePort() throws IOException {
        ServerSocket s = new ServerSocket(0);
        try {
            return s.getLocalPort();
        } finally {
            s.close();
        }
    }

    /**
     * Boot a mock Anthropic SSE server on {@code port} that responds to
     * {@code POST /v1/messages} with the given sequence of pre-canned SSE
     * events. The server runs on a daemon-thread pool so the JVM can exit
     * cleanly without explicit {@link #stopServer}.
     *
     * <p>The HTTP handler:
     * <ol>
     *   <li>Reads the POST body to end-of-stream.</li>
     *   <li>Passes the body string to {@code requestBodyCapture} (may be
     *       {@code null} for tests that don't care about the request).</li>
     *   <li>Writes {@code Content-Type: text/event-stream} + chunked
     *       transfer encoding.</li>
     *   <li>Iterates {@code sseEvents}, writing each as
     *       {@code event: <type>\ndata: <json>\n\n}, sleeping 20 ms
     *       between events.</li>
     *   <li>Closes the exchange (which Anthropic also does after sending
     *       {@code message_stop}).</li>
     * </ol>
     *
     * @param port               TCP port to bind. Pass 0 to let the OS
     *                           choose; recover the actual port via
     *                           {@code server.getAddress().getPort()}.
     * @param sseEvents          ordered list of raw SSE blocks, each in
     *                           the form {@code event: <type>\ndata: <json>\n\n}.
     *                           The 6 Anthropic event types are typically
     *                           emitted in order: message_start →
     *                           content_block_start → content_block_stop →
     *                           message_delta → message_stop.
     * @param requestBodyCapture consumer invoked with the raw POST body
     *                           (UTF-8 decoded); may be {@code null}.
     * @return a started {@link HttpServer} bound to the requested port.
     */
    public static HttpServer startSseServer(int port,
                                            List<String> sseEvents,
                                            Consumer<String> requestBodyCapture) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            ExecutorService pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "anthropic-sse-mock");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.createContext("/v1/messages", sseHandler(sseEvents, requestBodyCapture));
            server.start();
            return server;
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to start Anthropic SSE mock server on port " + port, ioe);
        }
    }

    /**
     * Convenience overload — boots on a free port chosen by the OS and
     * returns both. Saves tests a {@link #findFreePort} + start pair.
     *
     * @param sseEvents          ordered list of SSE blocks (see full overload).
     * @param requestBodyCapture consumer for the raw POST body; may be {@code null}.
     * @return a {@link StartedServer} handle carrying the running server
     *         and its bound port.
     */
    public static StartedServer startSseServerOnFreePort(
            List<String> sseEvents,
            Consumer<String> requestBodyCapture) {
        try {
            int port = findFreePort();
            HttpServer server = startSseServer(port, sseEvents, requestBodyCapture);
            return new StartedServer(server, port);
        } catch (IOException ioe) {
            throw new RuntimeException("Failed to start Anthropic SSE mock server on free port", ioe);
        }
    }

    /**
     * Stop a mock server started via {@link #startSseServer} or
     * {@link #startSseServerOnFreePort}. Performs a 1-second drain on
     * the executor pool before stopping to let in-flight handler threads
     * close cleanly.
     *
     * @param server the {@link HttpServer} to stop; {@code null} is a no-op.
     */
    public static void stopServer(HttpServer server) {
        if (server == null) return;
        // Stop accepting new connections immediately, then drain in-flight handlers.
        server.stop(0);
        java.util.concurrent.Executor exec = server.getExecutor();
        if (exec instanceof ExecutorService) {
            ExecutorService es = (ExecutorService) exec;
            es.shutdown();
            try {
                if (!es.awaitTermination(1, TimeUnit.SECONDS)) {
                    es.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                es.shutdownNow();
            }
        }
    }

    private static HttpHandler sseHandler(List<String> sseEvents,
                                          Consumer<String> requestBodyCapture) {
        return exchange -> {
            // 1. Read POST body to EOF so the provider's write() unblocks.
            String body;
            BufferedReader br = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            try {
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } finally {
                br.close();
            }
            body = sb.toString();

            if (requestBodyCapture != null) {
                try {
                    requestBodyCapture.accept(body);
                } catch (RuntimeException re) {
                    // Capture consumer exceptions are non-fatal — log via the
                    // exchange's stack and continue streaming so the test can
                    // still observe what arrived.
                    re.printStackTrace();
                }
            }

            // 2. Set SSE headers + chunked transfer encoding.
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, 0);  // 0 == chunked transfer encoding

            // 3. Stream each event with a small pause to simulate real pacing.
            try {
                for (String ev : sseEvents) {
                    if (ev != null && !ev.isEmpty()) {
                        exchange.getResponseBody().write(ev.getBytes(StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                    }
                    Thread.sleep(EVENT_PACING_MS);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (IOException ioe) {
                // Client may close early (e.g. on stream error) — non-fatal.
            } finally {
                exchange.close();
            }
        };
    }

    /**
     * Handle returned by {@link #startSseServerOnFreePort}.
     */
    public static final class StartedServer {
        private final HttpServer server;
        private final int port;

        StartedServer(HttpServer server, int port) {
            this.server = server;
            this.port = port;
        }

        public HttpServer getServer() { return server; }
        public int getPort() { return port; }

        /** Build the {@code baseUrl} string for the mock server. */
        public String baseUrl() {
            return "http://127.0.0.1:" + port;
        }
    }
}