package ai.lingshu.a2a.server;

import ai.lingshu.core.runtime.AgentConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 🆕 Story #009 — embedded A2A HTTP server (dsh §5.6.8).
 *
 * <p>Wraps JDK built-in {@code com.sun.net.httpserver.HttpServer} (zero new Maven
 * dependencies — see R-13 mitigation (d) in dsh §17) to serve:
 * <ul>
 *   <li>{@code GET /.well-known/agent.json} → JSON AgentCard (A2A v1.0 spec §2.1 fixed path)</li>
 *   <li>{@code POST /rpc} → 501 placeholder (deferred to Story #009b)</li>
 *   <li>anything else → 404 (catch-all)</li>
 * </ul>
 *
 * <p>Lifecycle (contracts/a2a-server-lifecycle.md):
 * <ul>
 *   <li>{@link #start()} — invoked by Spring via {@code @Bean(initMethod = "start")} after
 *       bean construction; binds, registers handlers, calls {@code server.start()}. Fails
 *       Spring context startup on port collision / unknown host / invalid port (LINGS-S06)
 *       or blank {@code Identity.name} (LINGS-T02).</li>
 *   <li>running — handlers serve concurrent GET requests; thread-safe because
 *       {@code LocalAgentCardGenerator} is stateless and {@code ObjectMapper} is thread-safe.</li>
 *   <li>{@link #stop()} — invoked by Spring via {@code @Bean(destroyMethod = "stop")} on
 *       context close; calls {@code server.stop(0)} and releases the port. Idempotent
 *       (a second call no-ops because {@code server} is nulled).</li>
 * </ul>
 */
@Component
public class A2aServer {

    private static final Logger LOG = LoggerFactory.getLogger(A2aServer.class);

    /** Cached AgentCard — generated once at startup; immutable thereafter. */
    private final AtomicReference<AgentCard> cardRef = new AtomicReference<AgentCard>();

    /** The JDK HttpServer; null before {@link #start()} and after {@link #stop()}. */
    private HttpServer server;

    /** Actual bound port; differs from {@code cfg.a2a.port} when port = 0 (OS-assigned). */
    private int actualPort;

    private final AgentConfig cfg;

    @Autowired
    public A2aServer(AgentConfig cfg) {
        this.cfg = cfg;
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Bind and start listening. Called by Spring via {@code @Bean(initMethod = "start")}
     * after the bean is constructed.
     *
     * @throws LingsA2aServerException {@code LINGS-S06} on bind failure / unknown host /
     *         port out of range / repeated start; {@code LINGS-T02} on empty
     *         {@code Identity.name}.
     */
    public void start() {
        // Step 1: validate Identity.name eagerly so yml typo fails fast (FR-007 / EC-1).
        AgentCard card = LocalAgentCardGenerator.generate(cfg);
        cardRef.set(card);

        // Step 2: validate port range.
        AgentConfig.A2a a2a = cfg.getA2a() != null ? cfg.getA2a() : AgentConfig.A2a.defaults();
        int port = a2a.getPort() == null ? 8080 : a2a.getPort();
        if (port < 0 || port > 65535) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "invalid a2a.server.port=" + port,
                "port must be in range 0..65535 (0 = OS-assigned)");
        }
        String host = a2a.getHost() == null ? "0.0.0.0" : a2a.getHost();

        // Step 3: bind.
        try {
            this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        } catch (java.net.BindException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Failed to bind on " + host + ":" + port + ": " + e.getMessage(),
                "change 'a2a.server.port' in application.yml or stop the conflicting process",
                e);
        } catch (java.net.UnknownHostException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Unknown host '" + host + "': " + e.getMessage(),
                "set 'a2a.server.host' to a valid hostname or IP (e.g. '0.0.0.0' / '127.0.0.1')",
                e);
        } catch (IOException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Failed to create HttpServer on " + host + ":" + port + ": " + e.getMessage(),
                "check 'a2a.server.host' and 'a2a.server.port' in application.yml",
                e);
        } catch (IllegalArgumentException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "Invalid bind address " + host + ":" + port + ": " + e.getMessage(),
                "check 'a2a.server.host' and 'a2a.server.port' in application.yml",
                e);
        }

        this.actualPort = server.getAddress().getPort();

        // Step 4: register handlers.
        server.createContext("/.well-known/agent.json", new AgentCardHandler());
        server.createContext("/rpc", new RpcPlaceholderHandler());
        server.createContext("/", new NotFoundHandler());

        // Step 5: start (use default executor — cached thread pool, see contracts/...lifecycle.md §3.1).
        server.setExecutor(null);
        try {
            server.start();
        } catch (IllegalStateException e) {
            throw new LingsA2aServerException(
                "LINGS-S06",
                "HttpServer.start() failed: " + e.getMessage(),
                "A2aServer.start() called more than once (not idempotent)",
                e);
        }

        LOG.info("[A2aServer] listening on http://{}:{}", host, actualPort);
    }

    /**
     * Stop listening and release the port. Called by Spring via {@code @Bean(destroyMethod = "stop")}
     * on context close. Idempotent — a second call no-ops because {@code server} is nulled out.
     */
    public void stop() {
        if (server == null) {
            return;
        }
        int port = actualPort;
        server.stop(0);
        this.server = null;
        LOG.info("[A2aServer] stopped on port {}", port);
    }

    /** Bound port — useful when {@code a2a.port = 0} (OS-assigned). */
    public int getActualPort() {
        return actualPort;
    }

    /** Cached card — read-only view for tests. */
    AgentCard getCard() {
        return cardRef.get();
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    /** Serve the cached AgentCard as JSON on GET. */
    private final class AgentCardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendJson(ex, 405,
                        "{\"error\":\"method not allowed\",\"method\":\""
                            + ex.getRequestMethod() + "\"}",
                        "GET");
                    return;
                }
                AgentCard card = cardRef.get();
                String body = LocalAgentCardGenerator.toJson(card);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=60");
                ex.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (JsonProcessingException jpe) {
                sendJson(ex, 500,
                    "{\"error\":\"internal server error\",\"errorCode\":\"LINGS-S06\"}",
                    null);
            } catch (RuntimeException re) {
                LOG.warn("[A2aServer] agent.json handler failed", re);
                sendJson(ex, 500,
                    "{\"error\":\"internal server error\",\"errorCode\":\"LINGS-S06\"}",
                    null);
            }
        }
    }

    /** POST /rpc → 501 placeholder (Story #009b will replace with JSON-RPC dispatcher). */
    private static final class RpcPlaceholderHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = "{\"error\":\"not implemented\",\"path\":\"/rpc\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(501, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /** Catch-all 404 for any other path. */
    private static final class NotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = "{\"error\":\"not found\",\"path\":\""
                + ex.getRequestURI().getPath() + "\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(404, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // ── Shared helper ───────────────────────────────────────────────────────

    private static void sendJson(HttpExchange ex, int status, String body, String allowHeader)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        if (allowHeader != null) {
            ex.getResponseHeaders().set("Allow", allowHeader);
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}