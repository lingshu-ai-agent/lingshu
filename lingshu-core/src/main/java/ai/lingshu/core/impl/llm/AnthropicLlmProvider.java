package ai.lingshu.core.impl.llm;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.LlmProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Anthropic-protocol LLM provider (Story #001 default).
 *
 * <p>Calls the Anthropic <code>/v1/messages</code> endpoint directly via JDK
 * {@link HttpURLConnection}. This bypasses Spring AI's broken {@code RestClient} URI assembly
 * when the base URL contains a non-empty path component (e.g. proxies mounted under
 * <code>/anthropic</code>) — Spring's {@code DefaultUriBuilderFactory.expand("/v1/messages")}
 * discards the base URL's scheme in that case and produces a relative URI that the JDK
 * HttpClient rejects with "URI with undefined scheme".
 *
 * <p>The transport itself stays vanilla <code>POST application/json</code> — only the body
 * shape and the response parsing are Anthropic-protocol-specific. For providers that need a
 * different wire format, swap this for a sibling class (Story #003).
 *
 * <p>Configuration surface (resolved by {@link AnthropicLlmProviderProvider}):
 * <ul>
 *   <li>{@code ANTHROPIC_AUTH_TOKEN} or {@code ANTHROPIC_API_KEY} env var — bearer token</li>
 *   <li>{@code ANTHROPIC_BASE_URL} env var — full base URL including any proxy path
 *       (e.g. <code>https://api.minimax.cn/anthropic</code>); <code>/v1/messages</code> is
 *       appended automatically</li>
 *   <li>{@code ANTHROPIC_VERSION} env var (optional, default {@code 2023-06-01})</li>
 *   <li>{@code ANTHROPIC_MODEL} env var (optional override of {@code agent.llm.model})</li>
 * </ul>
 *
 * <p>dsh §4.10.1 硬规则 2 (Spring AI only for protocol conversion + schema generation) is
 * honored by not using {@code ChatClient.tools().call()} auto-execution here.
 */
public class AnthropicLlmProvider implements LlmProvider {

    private static final Logger LOG = LoggerFactory.getLogger(AnthropicLlmProvider.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final String baseUrl;          // e.g. https://api.minimax.cn/anthropic
    private final String apiKey;
    private final String anthropicVersion;
    private final String model;
    private final Integer maxTokens;
    private final Double temperature;
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "anthropic-llm-io");
        t.setDaemon(true);
        return t;
    });

    public AnthropicLlmProvider(String baseUrl, String apiKey, String anthropicVersion,
                                String model, Integer maxTokens, Double temperature) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.anthropicVersion = anthropicVersion == null || anthropicVersion.isEmpty()
            ? "2023-06-01" : anthropicVersion;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
    }

    @Override
    public CompletableFuture<LlmResponse> stream(
            ai.lingshu.core.message.Prompt ourPrompt,
            TurnContext ctx,
            Subscriber<? super AgentEvent> sink) {

        String url = baseUrl + "/v1/messages";
        final String requestBody = buildRequestBody(ourPrompt);

        LOG.info("AnthropicLlmProvider calling {} model={} (stream=true)", url, model);

        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            LlmResponse resp = doPostStream(url, requestBody, sink);
            long elapsed = System.currentTimeMillis() - t0;
            LOG.info("AnthropicLlmProvider.stream completed in {}ms (textLen={}, toolCalls={})",
                elapsed, resp.getText() == null ? 0 : resp.getText().length(),
                resp.getToolCalls() == null ? 0 : resp.getToolCalls().size());
            return resp;
        }, ioExecutor);
    }

    // ── HTTP transport (HttpURLConnection — JDK 8 compatible) ────────────

    private String doPost(String url, String body) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            if (conn instanceof HttpsURLConnection) {
                // default SSL config is fine; keep the cast explicit for future tweaks.
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", anthropicVersion);

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            InputStream is = (status >= 200 && status < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
            String responseBody = readAll(is);
            if (status / 100 != 2) {
                throw new RuntimeException("Anthropic HTTP " + status + ": " + responseBody);
            }
            return responseBody;
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Anthropic HTTP call failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ── 🆕 Story #027b — true SSE streaming transport ───────────────────

    /**
     * 🆕 Story #027b — POST to Anthropic's <code>/v1/messages</code> with
     * <code>Accept: text/event-stream</code> and consume the response as a
     * line-delimited SSE stream, feeding each parsed event into
     * {@link AnthropicStreamParser} which in turn pushes
     * {@link AgentEvent.TextDelta} / {@link AgentEvent.ToolStarted} / etc.
     * down to {@code sink}.
     *
     * <p>Compared with the legacy {@link #doPost} +
     * {@link #parseResponse(String, Subscriber)} (Story #001 — kept as the
     * fallback path for the {@code anthropicStreamEnabled=false} config
     * branch and as a test helper for unit tests that don't need real-time
     * emission), this method:
     * <ul>
     *   <li>Reads one event at a time (no full body buffering), so the
     *       caller observes the LLM's first text byte as soon as Anthropic
     *       sends it (LLM NFR §14.15.1 first-token P50 ≤ 1.5 s).</li>
     *   <li>Forwards {@link AgentEvent.TextDelta} per delta — UI subscribers
     *       get a live stream rather than a single dump at completion.</li>
     *   <li>Buffers {@code input_json_delta.partial_json} fragments per
     *       tool-use block and only emits a complete {@link ToolCall} when
     *       {@code content_block_stop} arrives for that block — matching
     *       the Anthropic protocol requirement that {@code tool_use.input}
     *       must be a fully assembled JSON value.</li>
     * </ul>
     *
     * <p><b>Failure handling</b> — HTTP non-2xx reads the error stream
     * verbatim (the legacy path's behavior), wraps the message in a
     * {@link RuntimeException} so the caller sees a clear protocol-level
     * failure rather than a parser exception deep inside the SSE loop.
     * {@link LingsLlmProviderException} thrown by the parser (defensive
     * {@link LlmErrorCodes#LINGS_L01} check on tool_use missing
     * id / name) propagates unwrapped so callers can branch on
     * {@code getCode()} per the Story #027a convention.
     */
    private LlmResponse doPostStream(String url, String body, Subscriber<? super AgentEvent> sink) {
        HttpURLConnection conn = null;
        AnthropicStreamParser parser = new AnthropicStreamParser();
        try {
            URL u = new URL(url);
            conn = (HttpURLConnection) u.openConnection();
            if (conn instanceof HttpsURLConnection) {
                // default SSL config is fine; keep the cast explicit for future tweaks.
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            // 🆕 Story #027b — request SSE response shape. Without this header
            // Anthropic returns its full JSON body on the connection and
            // there is no event stream to read incrementally.
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", anthropicVersion);

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                os.close();
            }

            int status = conn.getResponseCode();
            if (status / 100 != 2) {
                InputStream es = conn.getErrorStream();
                String errBody = es != null ? readAll(es) : "";
                throw new RuntimeException("Anthropic HTTP " + status + ": " + errBody);
            }

            // Read the SSE stream line-by-line. Anthropic closes the
            // connection after sending the final `message_stop` event;
            // readLine() returns null at EOF, ending the loop.
            InputStream is = conn.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder rawSseBlock = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    // Blank line = end of one SSE event block. Parse and
                    // dispatch if the buffer is non-empty (skip heartbeats
                    // / pure whitespace lines).
                    if (rawSseBlock.length() > 0) {
                        AnthropicStreamEvent event = AnthropicStreamEvent.parse(rawSseBlock.toString());
                        parser.feed(event, sink);
                        rawSseBlock.setLength(0);
                    }
                } else {
                    if (rawSseBlock.length() > 0) {
                        rawSseBlock.append('\n');
                    }
                    rawSseBlock.append(line);
                }
            }
            // Flush any trailing event block that wasn't terminated by a
            // blank line before the connection closed.
            if (rawSseBlock.length() > 0) {
                AnthropicStreamEvent event = AnthropicStreamEvent.parse(rawSseBlock.toString());
                parser.feed(event, sink);
            }

            // parser.finish() throws IllegalStateException if message_stop was
            // never seen (e.g. truncated stream) — that propagates as a
            // RuntimeException, matching the legacy doPost path's failure
            // semantics.
            return parser.finish();
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("Anthropic SSE stream failed: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ── Wire format: our Prompt → Anthropic /v1/messages JSON ───────────

    /**
     * 🆕 Story #027a — translate the LingShu prompt to Anthropic's
     * {@code /v1/messages} JSON. Two gaps closed vs. Story #001:
     * <ol>
     *   <li><b>Top-level {@code tools:[]}</b> — populated from {@code Prompt.tools}
     *       (Story #024 contract: a {@code List<ToolSpec>}). Each entry maps to
     *       {@code {name, description, input_schema}} per the dsh §6.5 (1.5)
     *       protocol field table.</li>
     *   <li><b>{@code messages[].content} as array of blocks</b> — User / Assistant /
     *       ToolResult messages are serialized with {@code content:[]} shape rather
     *       than the flat-string {@code content:"..."} that Story #001 used. Assistant
     *       turns emit a {@code text} block (when non-empty) followed by a
     *       {@code tool_use} block for each {@code Message.Assistant.toolCalls}
     *       entry. ToolResult turns emit a {@code tool_result} block under
     *       {@code role:"user"} (the Anthropic convention).</li>
     * </ol>
     *
     * <p>Defensive checks raise {@link LingsLlmProviderException} with
     * {@link LlmErrorCodes#LINGS_L01} for {@code tool_use} blocks missing
     * {@code id}/{@code name} and {@link LlmErrorCodes#LINGS_L02} for
     * {@code tool_result} blocks missing {@code tool_use_id}/{@code content}.
     * Failing fast at request-build time surfaces the bug at the original
     * call site (Anthropic's generic 400 response hides the root cause).
     */
    private String buildRequestBody(ai.lingshu.core.message.Prompt ourPrompt) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("model", model);
            if (maxTokens != null) {
                root.put("max_tokens", maxTokens);
            }
            if (temperature != null) {
                root.put("temperature", temperature.doubleValue());
            }

            // Top-level tools:[] — Story #024 Prompt.tools contract.
            ArrayNode toolsArray = root.putArray("tools");
            List<ToolSpec> tools = ourPrompt.getTools();
            if (tools != null) {
                for (ToolSpec spec : tools) {
                    ObjectNode t = toolsArray.addObject();
                    t.put("name", spec.getName());
                    if (spec.getDescription() != null) {
                        t.put("description", spec.getDescription());
                    }
                    // input_schema is a JsonNode (typically ObjectNode); pass through verbatim.
                    if (spec.getInputSchema() != null) {
                        t.set("input_schema", spec.getInputSchema());
                    } else {
                        t.putObject("input_schema");
                    }
                }
            }

            ArrayNode messages = root.putArray("messages");
            String systemText = null;
            // 🆕 Story #027a follow-up — Anthropic requires all tool_result blocks
            // for a single assistant turn to be emitted as ONE user message with
            // multiple tool_result blocks (in tool_use order). When ReAct produces
            // N parallel tool calls, the session history contains N consecutive
            // Message.ToolResult entries; the provider must merge them into one
            // role:"user" message so the protocol-level pairing is honored.
            ObjectNode openToolResultUserMsg = null;
            ArrayNode openToolResultContent = null;
            for (Message m : ourPrompt.getMessages()) {
                if (m instanceof Message.System) {
                    // Anthropic's system lives at the top level (dsh §6.5 (1.5) table).
                    // Concatenate multiple System messages into a single block —
                    // matches Story #001 behavior preserved here.
                    String next = ((Message.System) m).getContent();
                    systemText = systemText == null ? next : (systemText + "\n\n" + next);
                } else if (m instanceof Message.User) {
                    // A user text message closes any open tool_result block —
                    // the next user message starts a fresh turn boundary.
                    openToolResultUserMsg = null;
                    openToolResultContent = null;
                    appendUserTextMessage(messages, ((Message.User) m).getContent());
                } else if (m instanceof Message.Assistant) {
                    // An assistant message closes any open tool_result block —
                    // assistant text/tool_use comes after, never nested.
                    openToolResultUserMsg = null;
                    openToolResultContent = null;
                    appendAssistantMessage(messages, (Message.Assistant) m);
                } else if (m instanceof Message.ToolResult) {
                    if (openToolResultUserMsg == null) {
                        openToolResultUserMsg = messages.addObject();
                        openToolResultUserMsg.put("role", "user");
                        openToolResultContent = openToolResultUserMsg.putArray("content");
                    }
                    appendToolResultBlock(openToolResultContent, (Message.ToolResult) m);
                }
                // Note: tool_use blocks live on Message.Assistant.toolCalls (handled
                // in the Assistant branch above), not as separate Message.ToolUse
                // entries — see Message.java class-level JavaDoc.
            }
            if (systemText != null && !systemText.isEmpty()) {
                root.put("system", systemText);
            }
            return MAPPER.writeValueAsString(root);
        } catch (LingsLlmProviderException llpe) {
            throw llpe;  // don't wrap our own protocol-layer exception
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Anthropic request body", e);
        }
    }

    /** Append a {@code role:"user"} message with one {@code text} block. */
    private static void appendUserTextMessage(ArrayNode messages, String content) {
        ObjectNode msg = messages.addObject();
        msg.put("role", "user");
        ArrayNode contentArr = msg.putArray("content");
        ObjectNode textBlock = contentArr.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", content == null ? "" : content);
    }

    /**
     * Append a {@code role:"assistant"} message with zero or more {@code text} /
     * {@code tool_use} blocks. Tool calls are validated for required fields;
     * missing id / name throws {@link LlmErrorCodes#LINGS_L01}.
     */
    private static void appendAssistantMessage(ArrayNode messages, Message.Assistant a) {
        ObjectNode msg = messages.addObject();
        msg.put("role", "assistant");
        ArrayNode content = msg.putArray("content");

        String text = a.getText();
        if (text != null && !text.isEmpty()) {
            ObjectNode textBlock = content.addObject();
            textBlock.put("type", "text");
            textBlock.put("text", text);
        }

        List<ToolCall> calls = a.getToolCalls();
        if (calls != null) {
            for (ToolCall call : calls) {
                if (call.getId() == null || call.getId().isEmpty()) {
                    throw new LingsLlmProviderException(
                        LlmErrorCodes.LINGS_L01,
                        "Assistant.toolCalls[].id missing — Anthropic /v1/messages requires every tool_use block to carry an id");
                }
                if (call.getName() == null || call.getName().isEmpty()) {
                    throw new LingsLlmProviderException(
                        LlmErrorCodes.LINGS_L01,
                        "Assistant.toolCalls[].name missing — Anthropic /v1/messages requires every tool_use block to carry a name (id=" + call.getId() + ")");
                }
                ObjectNode toolUseBlock = content.addObject();
                toolUseBlock.put("type", "tool_use");
                toolUseBlock.put("id", call.getId());
                toolUseBlock.put("name", call.getName());
                if (call.getInput() != null) {
                    toolUseBlock.set("input", call.getInput());
                } else {
                    toolUseBlock.putObject("input");
                }
            }
        }
    }

    /**
     * Append one {@code tool_result} block to an open user message's content array.
     * Caller is responsible for creating the {@code role:"user"} message wrapper
     * via {@link #appendUserTextMessage} or {@link #openToolResultMessage}.
     * Required fields {@code tool_use_id} / {@code content} are validated; missing
     * values throw {@link LlmErrorCodes#LINGS_L02}.
     */
    private static void appendToolResultBlock(ArrayNode content, Message.ToolResult tr) {
        if (tr.getToolUseId() == null || tr.getToolUseId().isEmpty()) {
            throw new LingsLlmProviderException(
                LlmErrorCodes.LINGS_L02,
                "Message.ToolResult.toolUseId missing — Anthropic /v1/messages requires every tool_result block to echo back the tool_use id");
        }
        if (tr.getContent() == null) {
            throw new LingsLlmProviderException(
                LlmErrorCodes.LINGS_L02,
                "Message.ToolResult.content missing — Anthropic /v1/messages requires every tool_result block to carry content (use empty string for empty results)");
        }
        ObjectNode toolResultBlock = content.addObject();
        toolResultBlock.put("type", "tool_result");
        toolResultBlock.put("tool_use_id", tr.getToolUseId());
        toolResultBlock.put("content", tr.getContent());
        toolResultBlock.put("is_error", tr.isError());
    }

    // ── Wire format: Anthropic JSON → our LlmResponse ───────────────────

    /**
     * 🆕 Story #027a — parse {@code content[]} into both the text accumulator
     * (existing Story #001 behavior, preserved) and a {@code List<ToolCall>}
     * extracted from {@code tool_use} blocks. Missing id / name on a
     * {@code tool_use} block raises {@link LlmErrorCodes#LINGS_L01}.
     */
    private LlmResponse parseResponse(String body, Subscriber<? super AgentEvent> sink) {
        try {
            JsonNode root = MAPPER.readTree(body);

            StringBuilder textBuf = new StringBuilder();
            List<ToolCall> toolCalls = new java.util.ArrayList<>();
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    String type = block.path("type").asText("");
                    if ("text".equals(type)) {
                        textBuf.append(block.path("text").asText(""));
                    } else if ("tool_use".equals(type)) {
                        String id = block.path("id").asText("");
                        String name = block.path("name").asText("");
                        if (id.isEmpty()) {
                            throw new LingsLlmProviderException(
                                LlmErrorCodes.LINGS_L01,
                                "Anthropic response content[].tool_use.id missing — Anthropic protocol violation (every tool_use block must carry an id)");
                        }
                        if (name.isEmpty()) {
                            throw new LingsLlmProviderException(
                                LlmErrorCodes.LINGS_L01,
                                "Anthropic response content[].tool_use.name missing — Anthropic protocol violation (id=" + id + ")");
                        }
                        JsonNode input = block.path("input");
                        toolCalls.add(new ToolCall(id, name, input));
                    }
                    // Other block types (e.g. future "thinking", "redacted_thinking")
                    // are silently ignored — Anthropic may add new content block
                    // types without breaking existing clients.
                }
            }
            String text = textBuf.toString();

            if (sink != null && !text.isEmpty()) {
                sink.onNext(new AgentEvent.TextDelta(text));
            }

            // Usage
            JsonNode usage = root.path("usage");
            Usage ourUsage = new Usage(
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0));

            // Stop reason
            StopReason reason = StopReason.END_TURN;
            String sr = root.path("stop_reason").asText("");
            if ("tool_use".equalsIgnoreCase(sr)) reason = StopReason.TOOL_USE;
            else if ("max_tokens".equalsIgnoreCase(sr)) reason = StopReason.MAX_TOKENS;

            return new LlmResponse(text, toolCalls, reason, ourUsage);
        } catch (LingsLlmProviderException llpe) {
            throw llpe;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic response", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
