package ai.lingshu.core.impl.llm;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Story #027a — L1 + L2 tests for {@link AnthropicLlmProvider} protocol
 * conversion (AC-NN-1 / AC-NN-2 / AC-NN-3 / AC-NN-4 / AC-NN-5 / AC-NN-6 /
 * AC-NN-7).
 *
 * <p><b>How we test private methods</b> — {@code buildRequestBody} and
 * {@code parseResponse} are private (dsh spec: "在 2 private method 内做协议转换").
 * Direct unit tests use {@link Method#setAccessible(boolean)} (consistent with
 * {@code MaxStepsGuardTest.L311} precedent). The end-to-end AC-NN-7 case uses
 * a JDK-built-in {@link HttpServer} to verify the full
 * request-body / response-parsing round trip without changing visibility.
 *
 * <p>Provider is wired to the mock HTTP server at construction time via the
 * {@code baseUrl} constructor arg. {@code stream()} runs the request through
 * the wire so AC-NN-7 is a true round-trip — no Spring, no Spring AI.
 */
@DisplayName("Story #027a — AnthropicLlmProvider protocol conversion")
class AnthropicLlmProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrlCapture;

    @BeforeEach
    void startMockServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrlCapture = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopMockServer() {
        if (server != null) server.stop(0);
    }

    private AnthropicLlmProvider newProvider() {
        return new AnthropicLlmProvider(
            baseUrlCapture, "test-key", "2023-06-01", "test-model", 1024, 0.7);
    }

    private static Method buildRequestBodyMethod() throws NoSuchMethodException {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod(
            "buildRequestBody", Prompt.class);
        m.setAccessible(true);
        return m;
    }

    private static Method parseResponseMethod() throws NoSuchMethodException {
        Method m = AnthropicLlmProvider.class.getDeclaredMethod(
            "parseResponse", String.class, org.reactivestreams.Subscriber.class);
        m.setAccessible(true);
        return m;
    }

    /** Build a {@link Prompt} with a user message + 1 {@link ToolSpec}. */
    private static Prompt buildPromptWithToolSpec() {
        ToolSpec spec = new ToolSpec(
            "read_file",
            "Read a file from disk",
            MAPPER.createObjectNode()
                .put("type", "object")
                .set("properties", MAPPER.createObjectNode()
                    .set("path", MAPPER.createObjectNode().put("type", "string"))));
        return Prompt.builder()
            .messages(Arrays.asList(new Message.User("read /tmp/x")))
            .tools(Arrays.asList(spec))
            .build();
    }

    /** Build a canned Anthropic JSON response with a single tool_use block. */
    private static String buildMockToolUseResponse(String id, String name, String path) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "msg_test");
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", id);
        toolUse.put("name", name);
        ObjectNode inputNode = toolUse.putObject("input");
        inputNode.put("path", path);
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 42);
        usage.put("output_tokens", 7);
        return root.toString();
    }

    /** Build a canned Anthropic JSON response with a text block only. */
    private static String buildMockTextResponse(String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("id", "msg_test");
        root.put("type", "message");
        root.put("role", "assistant");
        root.put("stop_reason", "end_turn");
        ArrayNode content = root.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 1);
        usage.put("output_tokens", 1);
        return root.toString();
    }

    private AgentConfig defaultConfig() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            null, null, null, null, null,
            1, 5, 0, 0, 0,
            10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults()
        );
    }

    private TurnContext newTurn() {
        return new DefaultTurnContext(new DefaultSession(), defaultConfig(),
            new CapturingSubscriber(), "test");
    }

    // ── AC-NN-1: top-level tools:[] translation ──────────────────────────

    @Test
    @DisplayName("AC-NN-1: buildRequestBody_toolsTopLevelTranslation")
    void buildRequestBody_toolsTopLevelTranslation() throws Exception {
        Prompt p = buildPromptWithToolSpec();
        String body = (String) buildRequestBodyMethod().invoke(newProvider(), p);
        JsonNode root = MAPPER.readTree(body);

        JsonNode tools = root.path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.size()).isEqualTo(1);

        JsonNode t0 = tools.get(0);
        assertThat(t0.path("name").asText()).isEqualTo("read_file");
        assertThat(t0.path("description").asText()).isEqualTo("Read a file from disk");
        // input_schema passed through verbatim
        assertThat(t0.path("input_schema").path("type").asText()).isEqualTo("object");
        assertThat(t0.path("input_schema").path("properties").path("path").path("type").asText())
            .isEqualTo("string");

        // messages[] also present
        assertThat(root.path("messages").isArray()).isTrue();
        assertThat(root.path("messages").size()).isEqualTo(1);
    }

    // ── AC-NN-2: messages[].content as array of blocks ───────────────────

    @Test
    @DisplayName("AC-NN-2: messagesContentBlocks_userAndAssistantAndToolResult")
    void messagesContentBlocks_userAndAssistantAndToolResult() throws Exception {
        ToolCall call = new ToolCall("c1", "read_file",
            MAPPER.createObjectNode().put("path", "/tmp/a"));
        Prompt p = Prompt.builder()
            .messages(Arrays.asList(
                new Message.User("do it"),
                new Message.Assistant("calling tool", Arrays.asList(call),
                    StopReason.TOOL_USE,
                    ai.lingshu.core.message.Usage.zero()),
                new Message.ToolResult("c1", "file contents", false)))
            .tools(Arrays.asList(new ToolSpec("read_file", "Read", MAPPER.createObjectNode())))
            .build();

        String body = (String) buildRequestBodyMethod().invoke(newProvider(), p);
        JsonNode root = MAPPER.readTree(body);
        JsonNode messages = root.path("messages");
        assertThat(messages.size()).isEqualTo(3);

        // msg[0]: User → role:"user", content:[{type:"text", text}]
        JsonNode m0 = messages.get(0);
        assertThat(m0.path("role").asText()).isEqualTo("user");
        assertThat(m0.path("content").isArray()).isTrue();
        assertThat(m0.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(m0.path("content").get(0).path("text").asText()).isEqualTo("do it");

        // msg[1]: Assistant → role:"assistant", content:[{type:"text"},{type:"tool_use"}]
        JsonNode m1 = messages.get(1);
        assertThat(m1.path("role").asText()).isEqualTo("assistant");
        JsonNode c1 = m1.path("content");
        assertThat(c1.size()).isEqualTo(2);
        assertThat(c1.get(0).path("type").asText()).isEqualTo("text");
        assertThat(c1.get(0).path("text").asText()).isEqualTo("calling tool");
        assertThat(c1.get(1).path("type").asText()).isEqualTo("tool_use");
        assertThat(c1.get(1).path("id").asText()).isEqualTo("c1");
        assertThat(c1.get(1).path("name").asText()).isEqualTo("read_file");
        assertThat(c1.get(1).path("input").path("path").asText()).isEqualTo("/tmp/a");

        // msg[2]: ToolResult → role:"user", content:[{type:"tool_result"}]
        JsonNode m2 = messages.get(2);
        assertThat(m2.path("role").asText()).isEqualTo("user");
        JsonNode c2 = m2.path("content");
        assertThat(c2.size()).isEqualTo(1);
        assertThat(c2.get(0).path("type").asText()).isEqualTo("tool_result");
        assertThat(c2.get(0).path("tool_use_id").asText()).isEqualTo("c1");
        assertThat(c2.get(0).path("content").asText()).isEqualTo("file contents");
        assertThat(c2.get(0).path("is_error").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("AC-NN-2-edge: assistantWithEmptyText_omitsTextBlock_emitsToolUseOnly")
    void assistantWithEmptyText_omitsTextBlock_emitsToolUseOnly() throws Exception {
        // Pure tool_use turn: assistant text is empty, toolCalls non-empty.
        ToolCall call = new ToolCall("c2", "bash",
            MAPPER.createObjectNode().put("cmd", "ls"));
        Prompt p = Prompt.builder()
            .messages(Arrays.asList(
                new Message.Assistant("", Arrays.asList(call),
                    StopReason.TOOL_USE,
                    ai.lingshu.core.message.Usage.zero())))
            .build();

        String body = (String) buildRequestBodyMethod().invoke(newProvider(), p);
        JsonNode content = MAPPER.readTree(body).path("messages").get(0).path("content");
        // Only 1 block — the tool_use — text block is omitted (no empty text block).
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).path("type").asText()).isEqualTo("tool_use");
        assertThat(content.get(0).path("name").asText()).isEqualTo("bash");
    }

    // ── AC-NN-3: parseResponse tool_use ─────────────────────────────────

    @Test
    @DisplayName("AC-NN-3: parseResponse_toolUse_extractsToolCalls")
    void parseResponse_toolUse_extractsToolCalls() throws Exception {
        String responseJson = buildMockToolUseResponse(
            "tu_1", "read_file", "/tmp/x");
        LlmResponse r = (LlmResponse) parseResponseMethod().invoke(
            newProvider(), responseJson, null);

        assertThat(r.getToolCalls()).hasSize(1);
        ToolCall c = r.getToolCalls().get(0);
        assertThat(c.getId()).isEqualTo("tu_1");
        assertThat(c.getName()).isEqualTo("read_file");
        assertThat(c.getInput().path("path").asText()).isEqualTo("/tmp/x");
        assertThat(r.getStopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(r.getUsage().getInputTokens()).isEqualTo(42);
        assertThat(r.getUsage().getOutputTokens()).isEqualTo(7);
    }

    // ── AC-NN-4: defensive L01 parseResponse ───────────────────────────

    @Test
    @DisplayName("AC-NN-4: parseResponse_toolUseMissingIdThrowsL01")
    void parseResponse_toolUseMissingIdThrowsL01() throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        // missing id!
        toolUse.put("name", "read_file");
        toolUse.put("input", MAPPER.createObjectNode());

        // Reflection wraps the original exception in InvocationTargetException,
        // so we extract the cause and assert on it directly. This mirrors the
        // canonical Javadoc on LingsLlmProviderException ("[LINGS-L01] ...")
        // embedded by its getMessage() override.
        Throwable thrown = catchThrowable(() -> parseResponseMethod().invoke(
            newProvider(), root.toString(), null));
        assertThat(thrown).isInstanceOf(java.lang.reflect.InvocationTargetException.class);
        assertThat(thrown.getCause()).isInstanceOf(LingsLlmProviderException.class);
        assertThat(thrown.getCause().getMessage()).contains("LINGS-L01");
    }

    // ── AC-NN-5: defensive L01 buildRequestBody ────────────────────────

    @Test
    @DisplayName("AC-NN-5: buildRequestBody_toolCallMissingIdThrowsL01")
    void buildRequestBody_toolCallMissingIdThrowsL01() throws Exception {
        ToolCall callBad = new ToolCall(null /* missing id */, "read_file",
            MAPPER.createObjectNode());
        Prompt p = Prompt.builder()
            .messages(Arrays.asList(
                new Message.Assistant("go", Arrays.asList(callBad),
                    StopReason.TOOL_USE,
                    ai.lingshu.core.message.Usage.zero())))
            .build();

        Throwable thrown = catchThrowable(() -> buildRequestBodyMethod().invoke(newProvider(), p));
        assertThat(thrown).isInstanceOf(java.lang.reflect.InvocationTargetException.class);
        assertThat(thrown.getCause()).isInstanceOf(LingsLlmProviderException.class);
        assertThat(thrown.getCause().getMessage()).contains("LINGS-L01");
    }

    // ── AC-NN-6: defensive L02 buildRequestBody ────────────────────────

    @Test
    @DisplayName("AC-NN-6: buildRequestBody_toolResultMissingToolUseIdThrowsL02")
    void buildRequestBody_toolResultMissingToolUseIdThrowsL02() throws Exception {
        Prompt p = Prompt.builder()
            .messages(Arrays.asList(
                new Message.ToolResult(null /* missing toolUseId */, "x", false)))
            .build();

        Throwable thrown = catchThrowable(() -> buildRequestBodyMethod().invoke(newProvider(), p));
        assertThat(thrown).isInstanceOf(java.lang.reflect.InvocationTargetException.class);
        assertThat(thrown.getCause()).isInstanceOf(LingsLlmProviderException.class);
        assertThat(thrown.getCause().getMessage()).contains("LINGS-L02");
    }

    // ── AC-NN-7: end-to-end mock HTTP server round trip ─────────────────

    @Test
    @DisplayName("AC-NN-7: endToEnd_mockHttpServer_requestBodyAndResponseParsing")
    void endToEnd_mockHttpServer_requestBodyAndResponseParsing() throws Exception {
        // Mock server captures the request body and returns a canned tool_use response.
        final String[] capturedBody = new String[1];
        server.createContext("/v1/messages", exchange -> {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    exchange.getRequestBody(), java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            try {
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } finally {
                br.close();
            }
            capturedBody[0] = sb.toString();
            byte[] resp = buildMockToolUseResponse(
                "tu_99", "read_file", "/tmp/y")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });

        AnthropicLlmProvider provider = newProvider();
        Prompt prompt = buildPromptWithToolSpec();
        TurnContext ctx = newTurn();

        CompletableFuture<LlmResponse> future = provider.stream(prompt, ctx, null);
        LlmResponse r = future.get(5, TimeUnit.SECONDS);

        // Captured request body should have top-level tools:[] and messages[0].content block shape
        JsonNode sentRoot = MAPPER.readTree(capturedBody[0]);
        assertThat(sentRoot.path("tools").size()).isEqualTo(1);
        assertThat(sentRoot.path("tools").get(0).path("name").asText()).isEqualTo("read_file");
        JsonNode msgContent = sentRoot.path("messages").get(0).path("content");
        assertThat(msgContent.isArray()).isTrue();
        assertThat(msgContent.get(0).path("type").asText()).isEqualTo("text");

        // Parsed response should carry the tool_use block as a ToolCall
        assertThat(r.getToolCalls()).hasSize(1);
        assertThat(r.getToolCalls().get(0).getId()).isEqualTo("tu_99");
        assertThat(r.getToolCalls().get(0).getName()).isEqualTo("read_file");
        assertThat(r.getToolCalls().get(0).getInput().path("path").asText()).isEqualTo("/tmp/y");
        assertThat(r.getStopReason()).isEqualTo(StopReason.TOOL_USE);
    }
}