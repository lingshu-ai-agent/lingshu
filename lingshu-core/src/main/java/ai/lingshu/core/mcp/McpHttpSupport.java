package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpErrorCodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared HTTP / JSON-RPC helpers for MCP transports (Story #021c, dsh §6.5 (2)).
 *
 * <p><b>What</b> — Concentrates the {@code HttpURLConnection} + JSON-RPC
 * envelope boilerplate that {@link SseMcpServerConnection} and
 * {@link StreamableHttpMcpServerConnection} both need. Roughly 80 lines of
 * otherwise-duplicated code lives here instead of in each transport class.
 *
 * <p><b>Why here</b> — Single source of truth for: HTTP timeout semantics,
 * 4xx/5xx → {@code LINGS-M03} mapping, JSON-RPC 2024-11-05 envelope shape
 * (initialize params, tools/list / tools/call response parsing).
 *
 * <p><b>JDK 8 compatibility</b> — Uses {@link HttpURLConnection} (JDK 1.1
 * builtin) and Jackson which is already in the locked Maven dependency
 * table. Does <em>not</em> use {@code java.net.http.HttpClient} (JDK 11+,
 * would break the {@code <source>1.8</source>} compile target — see
 * {@code dsh §0 L39}). SSE framing is read via
 * {@link java.io.BufferedReader#readLine()} with a hand-written event parser;
 * no third-party SSE library.
 *
 * <p><b>Timeout semantics</b> — Both {@code setConnectTimeout} and
 * {@code setReadTimeout} are set to {@code timeoutMs}; either timing out
 * raises {@link McpTransportException} with {@code LINGS-M03}.
 *
 * <p><b>Error code attribution</b>:
 * <ul>
 *   <li>4xx / 5xx response → {@link McpTransportException} with code
 *       {@link McpErrorCodes#LINGS_M03} and message
 *       {@code "HTTP <code>: <truncated-body>"}.</li>
 *   <li>{@link IOException} (timeout, connection refused, malformed URL,
 *       socket reset, …) → {@link McpTransportException} with code
 *       {@code LINGS-M03} and message {@code "HTTP IO: <cause-message>"},
 *       the original {@link IOException} as the cause.</li>
 * </ul>
 *
 * <p><b>Thread safety</b> — All methods are static and stateless; the
 * shared {@link #MAPPER} is thread-safe per Jackson's documented guarantees.
 */
public final class McpHttpSupport {

    /** Shared JSON mapper (Jackson is documented thread-safe after configuration). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** MCP initialize protocol version per the 2024-11-05 spec. */
    static final String PROTOCOL_VERSION = "2024-11-05";

    private McpHttpSupport() {
        // utility class — never instantiated
        throw new AssertionError("McpHttpSupport must not be instantiated");
    }

    /**
     * POST a JSON-RPC envelope and return the parsed {@code result} field
     * (the {@code result} is unwrapped — the caller sees the inner JSON-RPC
     * {@code result} object, not the full {@code {jsonrpc, id, result}}.
     *
     * @param url       endpoint URL; must be non-null and non-empty
     * @param body      JSON-RPC body to send (typically {@code {jsonrpc, id, method, params}})
     * @param timeoutMs connect + read timeout (each). Clamped to
     *                  {@link Integer#MAX_VALUE} per
     *                  {@link HttpURLConnection#setConnectTimeout(int)}'s contract.
     * @return parsed response body (the entire JSON-RPC envelope
     *         {@code {jsonrpc, id, result}}); callers extract {@code .get("result")}
     * @throws IllegalArgumentException if {@code url} is null or empty
     * @throws McpTransportException    with {@code LINGS-M03} on any HTTP / IO failure
     */
    public static JsonNode postJsonRpc(String url, JsonNode body, long timeoutMs) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        if (body == null) {
            throw new IllegalArgumentException("body must not be null");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            int t = (int) Math.min(Math.max(timeoutMs, 1L), (long) Integer.MAX_VALUE);
            conn.setConnectTimeout(t);
            conn.setReadTimeout(t);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = MAPPER.writeValueAsBytes(body);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
            byte[] raw = readAllBytes(is);
            if (code < 200 || code >= 300) {
                throw new McpTransportException(McpErrorCodes.LINGS_M03,
                    "HTTP " + code + ": " + truncate(new String(raw, StandardCharsets.UTF_8), 200));
            }
            return MAPPER.readTree(raw);
        } catch (IOException e) {
            throw new McpTransportException(McpErrorCodes.LINGS_M03,
                "HTTP IO: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * GET a URL and return the body as a UTF-8 string.
     *
     * @throws McpTransportException with {@code LINGS-M03} on any HTTP / IO failure
     */
    public static String getJson(String url, long timeoutMs) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setUseCaches(false);
            int t = (int) Math.min(Math.max(timeoutMs, 1L), (long) Integer.MAX_VALUE);
            conn.setConnectTimeout(t);
            conn.setReadTimeout(t);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
            byte[] raw = readAllBytes(is);
            if (code < 200 || code >= 300) {
                throw new McpTransportException(McpErrorCodes.LINGS_M03,
                    "HTTP " + code + ": " + truncate(new String(raw, StandardCharsets.UTF_8), 200));
            }
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new McpTransportException(McpErrorCodes.LINGS_M03,
                "HTTP IO: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * GET a URL and return the body parsed as JSON.
     *
     * @throws McpTransportException with {@code LINGS-M03} on any HTTP / IO failure
     */
    public static JsonNode getJsonNode(String url, long timeoutMs) {
        String body = getJson(url, timeoutMs);
        if (body == null || body.isEmpty()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new McpTransportException(McpErrorCodes.LINGS_M03,
                "HTTP body parse: " + e.getMessage(), e);
        }
    }

    /**
     * Build the JSON body for the MCP {@code initialize} request.
     * Matches {@link StdioMcpServerConnection} field shape exactly so that
     * servers cannot tell which transport sent the request.
     */
    public static ObjectNode buildInitializeParams() {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", "lingshu-agent");
        info.put("version", "0.1.0-SNAPSHOT");
        p.set("clientInfo", info);
        ObjectNode caps = MAPPER.createObjectNode();
        ObjectNode roots = MAPPER.createObjectNode();
        caps.set("roots", roots);
        p.set("capabilities", caps);
        return p;
    }

    /**
     * Wrap the {@code initialize} params in a JSON-RPC 2.0 envelope.
     *
     * @param id request id (caller is responsible for uniqueness within the connection)
     */
    public static ObjectNode wrapJsonRpc(long id, String method, JsonNode params) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.put("id", id);
        env.put("method", method);
        env.set("params", params == null ? MAPPER.createObjectNode() : params);
        return env;
    }

    /**
     * Parse the {@code result.tools} array out of a JSON-RPC
     * {@code tools/list} response.
     */
    public static List<McpToolDescriptor> parseToolList(JsonNode resp) {
        List<McpToolDescriptor> out = new ArrayList<>();
        if (resp == null) {
            return out;
        }
        JsonNode result = resp.get("result");
        if (result == null || result.isNull()) {
            return out;
        }
        JsonNode tools = result.get("tools");
        if (tools == null || !tools.isArray()) {
            return out;
        }
        for (JsonNode tn : tools) {
            out.add(McpToolDescriptor.builder()
                .name(tn.path("name").asText(""))
                .description(tn.path("description").asText(null))
                .inputSchema(tn.path("inputSchema"))
                .build());
        }
        return out;
    }

    /**
     * Parse the {@code result.{content, isError}} fields of a JSON-RPC
     * {@code tools/call} response into an {@link McpCallResult}.
     */
    public static McpCallResult parseCallResult(JsonNode resp) {
        if (resp == null) {
            return McpCallResult.error("null tools/call response");
        }
        if (resp.has("error")) {
            return McpCallResult.error("MCP error: " + resp.get("error").toString());
        }
        JsonNode result = resp.get("result");
        if (result == null || result.isNull()) {
            return McpCallResult.error("MCP server returned null result");
        }
        boolean isError = result.path("isError").asBoolean(false);
        if (isError) {
            return McpCallResult.error(result.path("content").asText("MCP server reported error"));
        }
        JsonNode contentNode = result.get("content");
        String content = contentNode == null
            ? result.toString()
            : (contentNode.isTextual() ? contentNode.asText() : contentNode.toString());
        return McpCallResult.success(content);
    }

    /**
     * Send a notification (no {@code id}, no response expected).
     * Errors are wrapped in {@link McpTransportException} with {@code LINGS-M03}.
     */
    public static void postNotification(String url, JsonNode body, long timeoutMs) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url must not be empty");
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            int t = (int) Math.min(Math.max(timeoutMs, 1L), (long) Integer.MAX_VALUE);
            conn.setConnectTimeout(t);
            conn.setReadTimeout(t);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            byte[] payload = MAPPER.writeValueAsBytes(body);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }
            int code = conn.getResponseCode();
            // Drain and discard the body so the connection can be pooled.
            InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
            if (is != null) {
                readAllBytes(is);
            }
            if (code < 200 || code >= 300) {
                throw new McpTransportException(McpErrorCodes.LINGS_M03,
                    "HTTP " + code + " on notification");
            }
        } catch (IOException e) {
            throw new McpTransportException(McpErrorCodes.LINGS_M03,
                "HTTP IO: " + e.getMessage(), e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Truncate a string to {@code maxLen} characters with "..." if cut. */
    static String truncate(String s, int maxLen) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, Math.max(0, maxLen - 3)) + "...";
    }

    /** Read an InputStream fully into a byte array. Closes the stream. */
    private static byte[] readAllBytes(InputStream is) throws IOException {
        if (is == null) {
            return new byte[0];
        }
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        try {
            while ((n = is.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
        } finally {
            try {
                is.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
        return buf.toByteArray();
    }

    /** @return the shared {@link ObjectMapper} (for tests only). */
    static ObjectMapper mapper() {
        return MAPPER;
    }

    /** @return an empty unmodifiable list (JDK 8 idiom). */
    static <T> List<T> emptyList() {
        return Collections.emptyList();
    }
}