package ai.lingshu.core.impl.tool.local;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.AccessDeniedException;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Slot 2 built-in Tool — Fetch a URL over HTTP/HTTPS via the sandbox's whitelisted
 * HTTP client (Story #032 — Claude Code parity local WebFetch).
 *
 * <h2>Claude Code parity rationale (PRIMARY)</h2>
 *
 * <p>Claude Code's tool surface includes a built-in {@code WebFetch} that <b>coexists</b>
 * with MCP fetch servers (built-in for the simple default case, MCP for advanced HTTP
 * flows). LingShu mirrors this — {@link WebFetchTool} is the simple built-in Tool,
 * the MCP fetch server (Story #021a/b/c) covers advanced HTTP (POST / PUT / DELETE /
 * streaming / SSE). The two are <b>complementary, NOT mutually exclusive</b>
 * (dsh §6.5 (1) extension — three built-in Tool triplet fs / process / http complete).
 *
 * <h2>GET-only scope lock</h2>
 *
 * <p>Only {@link ToolExecutionContext.NetworkClient#get(String)} is exposed — this
 * Tool does <b>not</b> implement POST / PUT / DELETE. Mutating HTTP traffic must
 * route through the MCP fetch server or a dedicated custom Tool. Rationale: bounded
 * surface for the local Tool path, keeps MCP fetch server's raison d'être intact.
 *
 * <h2>HTTPS is transparent</h2>
 *
 * <p>The underlying JDK {@link java.net.HttpURLConnection} (via
 * {@link ai.lingshu.core.impl.sandbox.WhitelistedHttpClient}) automatically uses
 * {@link java.net.HttpsURLConnection} for {@code https://} URLs — no extra dependencies,
 * R-13 mitigation (d) preserved.
 *
 * <h2>Why {@code @Component} (not constructor-injected dependencies)</h2>
 *
 * <p>The Tool is fully stateless — every input is derived from {@code call.getInput()}
 * + the {@link ToolExecutionContext}. Spring constructor injection is unnecessary;
 * {@code @Component} registration + the {@code Map<String, Tool>} autowiring in
 * {@link LocalToolsAutoConfiguration} picks it up automatically (mirrors the
 * {@code ReadTool} / {@code WriteTool} / {@code EditTool} / {@code BashTool} convention).
 *
 * <h2>Sandbox defense contract (non-negotiable)</h2>
 *
 * <p>Every fetch goes through {@code ctx.http().get(url)} which delegates to
 * {@link ai.lingshu.core.impl.sandbox.WhitelistedHttpClient#check(String)} before
 * opening any connection. Domain miss throws {@link AccessDeniedException} with the
 * {@code [LINGS-S01]} prefix already embedded in {@code getMessage()} — caught here
 * and translated to {@code ToolResult.error("[LINGS-S01] ...")}. <b>This Tool NEVER
 * opens a raw {@link java.net.HttpURLConnection} directly</b> — bypassing
 * {@code ctx.http()} would defeat the sandbox defense (rejected at code review).
 *
 * <h2>Truncation</h2>
 *
 * <p>When response body exceeds {@link #DEFAULT_MAX_BYTES} (1 MB), content is clipped
 * to the first {@code maxBytes} characters and a
 * {@link #TRUNCATION_MARKER} marker appended so the LLM knows the result was
 * clipped. {@code max_bytes} input field overrides the default. Marker format
 * matches {@code ReadTool} for grep-friendliness across built-in tools.
 */
@Component("webFetchTool")
public class WebFetchTool implements Tool {

    /** Tool name exposed to the LLM — snake_case per Story #032 spec. */
    private static final String NAME = "web_fetch";

    /** Default response body cap (1 MB = 1024 × 1024). Override via {@code max_bytes} input field. */
    static final int DEFAULT_MAX_BYTES = 1_048_576;

    /** Truncation marker template — matches {@code ReadTool}'s format. */
    private static final String TRUNCATION_MARKER = "\n...[truncated, original %d bytes]";

    private final ObjectMapper mapper = new ObjectMapper();

    @Override public String name() { return NAME; }

    @Override public String description() {
        return "Fetch a URL over HTTP/HTTPS via the sandbox's domain whitelist "
             + "(GET-only — POST/PUT/DELETE traffic is NOT supported; use the MCP fetch "
             + "server or a custom Tool for mutating HTTP). Returns the response body "
             + "truncated to max_bytes (default 1 MB).";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("url").put("type", "string")
            .put("description",
                "HTTP or HTTPS URL to fetch. The host must be present in "
                + "agent.sandbox.domain-whitelist or the request is denied with [LINGS-S01].");
        props.putObject("max_bytes").put("type", "integer")
            .put("description",
                "Optional response body cap in bytes. Default " + DEFAULT_MAX_BYTES
                + " (1 MB). When the body exceeds this limit, it is clipped and a "
                + "truncation marker is appended.");
        schema.putArray("required").add("url");
        return schema;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        if (input == null
                || !input.has("url")
                || input.get("url").isNull()
                || input.get("url").asText().trim().isEmpty()) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("url is required")
                .isError(true)
                .build();
        }
        String url = input.get("url").asText().trim();

        int maxBytes = DEFAULT_MAX_BYTES;
        if (input.has("max_bytes") && !input.get("max_bytes").isNull()) {
            int requested = input.get("max_bytes").asInt(DEFAULT_MAX_BYTES);
            if (requested > 0) {
                maxBytes = requested;
            }
        }

        try {
            String body = ctx.http().get(url);
            if (body.length() > maxBytes) {
                body = body.substring(0, maxBytes)
                    + String.format(TRUNCATION_MARKER, body.length());
            }
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content(body)
                .isError(false)
                .build();
        } catch (AccessDeniedException e) {
            // [LINGS-S01] prefix already in e.getMessage() per AccessDeniedException
            // contract — propagate verbatim so the LLM and audit log both see the
            // canonical ErrorCode (dsh §15.5 S 段 1 号 + §4.10.1 硬规则 2).
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content(e.getMessage())
                .isError(true)
                .build();
        } catch (IOException e) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("HTTP fetch failed: " + e.getMessage())
                .isError(true)
                .build();
        }
    }
}
