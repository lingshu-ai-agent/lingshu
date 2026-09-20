package ai.lingshu.core.impl.llm;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
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
import java.util.Collections;
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

        LOG.info("AnthropicLlmProvider calling {} model={}", url, model);

        return CompletableFuture.supplyAsync(() -> {
            long t0 = System.currentTimeMillis();
            String body = doPost(url, requestBody);
            long elapsed = System.currentTimeMillis() - t0;
            LOG.info("AnthropicLlmProvider.call returned in {}ms ({} bytes)", elapsed, body.length());
            return parseResponse(body, sink);
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

    // ── Wire format: our Prompt → Anthropic /v1/messages JSON ───────────

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
            ArrayNode messages = root.putArray("messages");
            String systemText = null;
            for (Message m : ourPrompt.getMessages()) {
                if (m instanceof Message.System) {
                    systemText = ((Message.System) m).getContent();
                } else if (m instanceof Message.User) {
                    ObjectNode msg = messages.addObject();
                    msg.put("role", "user");
                    msg.put("content", ((Message.User) m).getContent());
                } else if (m instanceof Message.Assistant) {
                    String text = ((Message.Assistant) m).getText();
                    ObjectNode msg = messages.addObject();
                    msg.put("role", "assistant");
                    msg.put("content", text == null ? "" : text);
                }
                // Message.ToolUse / Message.ToolResult — skipped in Story #001.
            }
            if (systemText != null && !systemText.isEmpty()) {
                root.put("system", systemText);
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Anthropic request body", e);
        }
    }

    // ── Wire format: Anthropic JSON → our LlmResponse ───────────────────

    private LlmResponse parseResponse(String body, Subscriber<? super AgentEvent> sink) {
        try {
            JsonNode root = MAPPER.readTree(body);

            // Concatenate all text blocks from content[].
            StringBuilder textBuf = new StringBuilder();
            JsonNode content = root.path("content");
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        textBuf.append(block.path("text").asText(""));
                    }
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

            // Tool calls — Story #001 demo has no tools; Story #009 will parse content[] tool_use blocks.
            List<ToolCall> ourToolCalls = Collections.emptyList();

            return new LlmResponse(text, ourToolCalls, reason, ourUsage);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Anthropic response", e);
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
