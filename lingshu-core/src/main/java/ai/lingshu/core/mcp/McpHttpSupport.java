/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpErrorCodes;
import ai.lingshu.core.slot.AccessDeniedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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
 * 4xx/5xx → {@code LINGS-M03} mapping, JSON-RPC 2.0 envelope shape for the
 * negotiated protocol version (initialize params, tools/list / tools/call
 * response parsing).
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

    /**
     * MCP protocol version requested in {@code initialize}. The server's answer
     * (from {@code InitializeResult.protocolVersion}) is the negotiated version
     * and must be echoed in the {@code MCP-Protocol-Version} header afterwards.
     */
    static final String PROTOCOL_VERSION = "2025-06-18";

    /** Header carrying the negotiated protocol version on every post-initialize request. */
    static final String HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version";

    /** Header carrying the server-assigned session id (Streamable HTTP). */
    static final String HEADER_SESSION_ID = "Mcp-Session-Id";

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
        // Empty capabilities: we advertise nothing we do not implement. The old
        // code declared "roots" while never serving roots/list, which is a lie
        // that some servers act on (they send roots/list and we drop it).
        p.set("capabilities", MAPPER.createObjectNode());
        return p;
    }

    /**
     * Wrap a JSON-RPC <em>notification</em>: no {@code id}, no response expected.
     */
    public static ObjectNode wrapNotification(String method, JsonNode params) {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jsonrpc", "2.0");
        env.put("method", method);
        if (params != null) {
            env.set("params", params);
        }
        return env;
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
        String content = extractContent(result.get("content"), result);
        if (isError) {
            return McpCallResult.error(content == null || content.isEmpty()
                ? "MCP server reported error" : content);
        }
        return McpCallResult.success(content);
    }

    /**
     * Flatten a {@code CallToolResult.content} value into a plain string.
     *
     * <p>MCP types this as an array of ContentBlocks — typically
     * {@code [{"type":"text","text":"..."}]} — so joining the {@code text}
     * fields is the only reading a model can use. Non-text blocks
     * ({@code image}, {@code resource}, …) and the legacy string form are
     * passed through verbatim.
     */
    static String extractContent(JsonNode contentNode, JsonNode fallback) {
        if (contentNode == null || contentNode.isNull()) {
            return fallback == null ? "" : fallback.toString();
        }
        if (contentNode.isTextual()) {
            return contentNode.asText();
        }
        if (!contentNode.isArray()) {
            return contentNode.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : contentNode) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            if ("text".equals(block.path("type").asText(""))) {
                sb.append(block.path("text").asText(""));
            } else {
                sb.append(block.toString());
            }
        }
        return sb.toString();
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

    // ── Streamable HTTP / spec-compliant POST primitives ────────────────

    /**
     * Body + metadata of a spec-compliant MCP POST.
     *
     * <p>Streamable HTTP allows the server to answer a POST with either
     * {@code application/json} (one JSON-RPC message) or
     * {@code text/event-stream} (one or more messages). {@link #getBody()} is
     * the JSON-RPC envelope in both cases — the SSE frames have already been
     * unwrapped and the message matching the request id selected.
     */
    public static final class HttpRpcResponse {

        private final int status;
        private final String sessionId;
        private final JsonNode body;

        HttpRpcResponse(int status, String sessionId, JsonNode body) {
            this.status = status;
            this.sessionId = sessionId;
            this.body = body;
        }

        /** HTTP status code (always 2xx — non-2xx raises). */
        public int getStatus() {
            return status;
        }

        /** {@code Mcp-Session-Id} response header, or {@code null} if absent. */
        public String getSessionId() {
            return sessionId;
        }

        /** Parsed JSON-RPC envelope; an empty object if the server sent no body. */
        public JsonNode getBody() {
            return body;
        }
    }

    /** {@link McpTransportException} that preserves the HTTP status code. */
    public static final class McpHttpStatusException extends McpTransportException {

        private static final long serialVersionUID = 1L;

        private final int status;

        McpHttpStatusException(int status, String code, String message) {
            super(code, message);
            this.status = status;
        }

        /** @return the HTTP status code (e.g. 404 for an expired session). */
        public int getStatus() {
            return status;
        }
    }

    /**
     * POST one JSON-RPC message to the MCP endpoint, per the Streamable HTTP
     * transport rules.
     *
     * <p>Sets the headers the spec requires and the ones the server needs:
     * <ul>
     *   <li>{@code Content-Type: application/json}</li>
     *   <li>{@code Accept: application/json, text/event-stream} — the server
     *       MAY answer in either</li>
     *   <li>{@code Mcp-Session-Id} — when the server issued one</li>
     *   <li>{@code MCP-Protocol-Version} — after version negotiation</li>
     * </ul>
     *
     * @param url             the single MCP endpoint (never a per-method path)
     * @param body            JSON-RPC envelope
     * @param timeoutMs       connect + read timeout
     * @param sessionId       {@code Mcp-Session-Id} to send, or {@code null}
     * @param protocolVersion negotiated version to send, or {@code null} (initialize)
     * @param expectedId      id of the response to pick out of an SSE answer;
     *                        {@code null} for notifications
     * @throws McpHttpStatusException on non-2xx (keeps the status code)
     */
    public static HttpRpcResponse postJsonRpcSpec(String url, JsonNode body, long timeoutMs,
                                                  String sessionId, String protocolVersion,
                                                  Long expectedId) {
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
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            if (sessionId != null && !sessionId.isEmpty()) {
                conn.setRequestProperty(HEADER_SESSION_ID, sessionId);
            }
            if (protocolVersion != null && !protocolVersion.isEmpty()) {
                conn.setRequestProperty(HEADER_PROTOCOL_VERSION, protocolVersion);
            }
            byte[] payload = MAPPER.writeValueAsBytes(body);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }
            int code = conn.getResponseCode();
            String respSession = conn.getHeaderField(HEADER_SESSION_ID);
            if (code < 200 || code >= 300) {
                byte[] raw = readAllBytes(conn.getErrorStream());
                throw new McpHttpStatusException(code, McpErrorCodes.LINGS_M03,
                    "HTTP " + code + ": "
                        + truncate(new String(raw, StandardCharsets.UTF_8), 200));
            }
            String contentType = conn.getContentType();
            JsonNode parsed;
            if (contentType != null
                && contentType.toLowerCase().contains("text/event-stream")) {
                parsed = readSseResponse(conn, expectedId);
            } else {
                byte[] raw = readAllBytes(conn.getInputStream());
                parsed = raw.length == 0
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(raw);
            }
            return new HttpRpcResponse(code, respSession, parsed);
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
     * Read SSE frames from a POST response and return the JSON-RPC message that
     * answers {@code expectedId}. Per the spec an SSE answer may carry several
     * messages (notifications then the response), so frames are consumed until
     * the matching id shows up.
     */
    private static JsonNode readSseResponse(HttpURLConnection conn, Long expectedId)
        throws IOException {
        BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        try {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        String payload = data.toString();
                        data.setLength(0);
                        String currentEvent = event;
                        event = null;
                        if (currentEvent == null || "message".equals(currentEvent)) {
                            try {
                                JsonNode node = MAPPER.readTree(payload);
                                if (expectedId == null) {
                                    return node;
                                }
                                if (node.has("id")
                                    && node.get("id").canConvertToLong()
                                    && node.get("id").asLong() == expectedId.longValue()) {
                                    return node;
                                }
                            } catch (IOException ignored) {
                                // non-JSON frame — keep reading
                            }
                        }
                    }
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
                // best-effort
            }
        }
        throw new McpTransportException(McpErrorCodes.LINGS_M03,
            "SSE response stream ended without a matching JSON-RPC response");
    }

    // ── Story #033 — Domain guard hook (Path B + Mitigation 1) ──────────

    /**
     * Check-only domain guard hook for MCP HTTP transports. Extracts the URL
     * host via {@link URI#getHost()} and verifies it against {@code whitelist}
     * (case-sensitive exact match — mirrors {@link WhitelistedHttpClient#check}
     * semantics from Story #028).
     *
     * <p><b>Caller contract</b> — invoke immediately before any MCP HTTP request
     * (raw {@link HttpURLConnection#openConnection()} or via
     * {@link #postJsonRpc}/{@link #postNotification}). Throws
     * {@link AccessDeniedException} carrying the {@code [LINGS-S01]} prefix if
     * the host is not whitelisted — same ErrorCode as
     * {@link WhitelistedHttpClient} (Story #028) and {@code WebFetchTool}
     * (Story #032), keeping the sandbox-domain contract in one place.
     *
     * <p><b>Why static helper, not full {@link WhitelistedHttpClient}</b> —
     * MCP HTTP transports own their connection lifecycle (5-step handshake,
     * exponential-backoff reconnect, SSE long-lived stream). Path A
     * (Story #032 WebFetchTool) delegates to {@code ctx.http().get(url)} which
     * routes through {@link WhitelistedHttpClient}. Path B (this Story) keeps
     * the raw JDK {@link HttpURLConnection} flow unchanged — only adds a
     * check-only hook before each request, minimising risk to SSE streaming,
     * MCP handshake, and JSON-RPC framing.
     *
     * @param url       full HTTP/HTTPS URL to validate
     * @param whitelist case-sensitive host list; null/empty → every host denied
     * @throws AccessDeniedException with {@code [LINGS-S01]} prefix if host missing
     */
    public static void checkOrThrow(String url, List<String> whitelist) {
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
        if (whitelist == null || whitelist.isEmpty() || !whitelist.contains(host)) {
            throw new AccessDeniedException("Domain not whitelisted: " + host);
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