package ai.lingshu.core.impl.llm;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.LinearTurnEngine;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.impl.flow.support.SleepTool;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.ModelHints;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.PromptBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #027a — L2 slice integration test (AC-NN-7 end-to-end). Wires the
 * real {@link AnthropicLlmProvider} + real {@link LinearTurnEngine} + real
 * {@link DefaultToolExecutor} against a JDK-built-in
 * {@link com.sun.net.httpserver.HttpServer} that returns canned
 * Anthropic-shaped JSON.
 *
 * <p><b>Why an IT and not another unit test</b> — the protocol gap was a
 * 4-segment chain (buildRequestBody → parseResponse → TurnContext → engine
 * wire-through). Even with all unit tests passing, an off-by-one in the
 * engine's call site or a regression in the assistant-message-to-history
 * mapping would silently break the {@code tool_use}/{@code tool_result}
 * round-trip. The IT exercises the full ReAct loop with real provider +
 * real executor + mock transport.
 *
 * <p><b>Why no Spring</b> — AnthropicLlmProvider is a POJO; LinearTurnEngine
 * is a POJO. Test wires them directly. This is consistent with Story #007 /
 * Story #023 IT patterns.
 */
@DisplayName("Story #027a — AnthropicLlmProvider ↔ ReAct loop end-to-end")
class AnthropicToolReActIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private String baseUrl;
    private final List<String> capturedRequestBodies = new ArrayList<>();
    private final AtomicInteger responseIndex = new AtomicInteger(0);

    private final List<String> cannedResponses = new ArrayList<>();

    @BeforeEach
    void startMockServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/v1/messages", exchange -> {
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(
                    exchange.getRequestBody(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            try {
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
            } finally {
                br.close();
            }
            String body = sb.toString();
            synchronized (capturedRequestBodies) {
                capturedRequestBodies.add(body);
            }
            int idx = responseIndex.getAndIncrement();
            String response = idx < cannedResponses.size()
                ? cannedResponses.get(idx)
                : buildMockTextResponse("(no more canned responses)");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopMockServer() {
        if (server != null) server.stop(0);
    }

    private AnthropicLlmProvider newProvider() {
        return new AnthropicLlmProvider(
            baseUrl, "test-key", "2023-06-01", "test-model", 1024, 0.7);
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
            2, 5, 0, 0, 0,
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

    /** Canned text-only response. */
    private static String buildMockTextResponse(String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("stop_reason", "end_turn");
        ArrayNode content = root.putArray("content");
        ObjectNode textBlock = content.addObject();
        textBlock.put("type", "text");
        textBlock.put("text", text);
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 10);
        usage.put("output_tokens", 5);
        return root.toString();
    }

    /** Canned tool_use response with 1 tool call. */
    private static String buildMockSingleToolUseResponse(String id, String name, String path) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        ObjectNode toolUse = content.addObject();
        toolUse.put("type", "tool_use");
        toolUse.put("id", id);
        toolUse.put("name", name);
        ObjectNode inputNode = toolUse.putObject("input");
        inputNode.put("path", path);
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 20);
        usage.put("output_tokens", 8);
        return root.toString();
    }

    /** Canned tool_use response with N parallel tool calls. Each row is {id, name, path}. */
    private static String buildMockParallelToolUseResponse(String[][] calls) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("stop_reason", "tool_use");
        ArrayNode content = root.putArray("content");
        for (String[] c : calls) {
            ObjectNode toolUse = content.addObject();
            toolUse.put("type", "tool_use");
            toolUse.put("id", c[0]);
            toolUse.put("name", c[1]);
            ObjectNode inputNode = toolUse.putObject("input");
            inputNode.put("path", c[2]);
        }
        ObjectNode usage = root.putObject("usage");
        usage.put("input_tokens", 30);
        usage.put("output_tokens", 12);
        return root.toString();
    }

    /**
     * AC-NN-7 Case 1 — Single tool call → tool result → end turn. Exercises:
     * (1) AnthropicLlmProvider.parseResponse extracts tool_use correctly,
     * (2) LinearTurnEngine.dispatchParallel executes the tool,
     * (3) ToolResult.success is appended as Message.ToolResult to history,
     * (4) The next loop iteration's buildRequestBody serializes the
     * tool_result block back into messages[].content (this is the round-trip
     * the 4-segment protocol gap broke), and the LLM's text response causes
     * a natural break with END_TURN.
     */
    @Test
    @DisplayName("AC-NN-7.1: singleToolCall_fullRoundTrip_naturalEndTurn")
    void singleToolCall_fullRoundTrip_naturalEndTurn() throws Exception {
        cannedResponses.add(buildMockSingleToolUseResponse(
            "tu_1", "read_file", "/tmp/x"));
        cannedResponses.add(buildMockTextResponse("done"));

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new SleepTool("read_file", 10));

        AnthropicLlmProvider provider = newProvider();
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "test-llm-io-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        LinearTurnEngine engine = new LinearTurnEngine(
            new HistoryAwarePromptBuilder(Collections.singletonList(
                new ToolSpec("read_file", "Read a file", MAPPER.createObjectNode()))),
            provider,
            new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry),
            new AllowAllPermissionPolicy(),
            pool);

        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, defaultConfig(),
            new CapturingSubscriber(), "read /tmp/x");

        engine.runTurn(ctx, ctx.sink());

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        // 1) Two HTTP calls: 1st = initial, 2nd = post-tool-result
        assertThat(capturedRequestBodies).hasSize(2);
        // 2) Final event must be TurnCompleted with END_TURN
        List<AgentEvent> events = ((CapturingSubscriber) ctx.sink()).events();
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) last).getReason()).isEqualTo(StopReason.END_TURN);

        // 3) The second request body must contain the tool_result block
        //    (proving Round-Trip works — assistant's tool_use + user's tool_result
        //    both serialized into messages[].content)
        JsonNode secondRequest = MAPPER.readTree(capturedRequestBodies.get(1));
        JsonNode messages = secondRequest.path("messages");
        // Messages: User → Assistant(tool_use) → User(tool_result)
        assertThat(messages.size()).isEqualTo(3);

        JsonNode assistantMsg = messages.get(1);
        assertThat(assistantMsg.path("role").asText()).isEqualTo("assistant");
        JsonNode aContent = assistantMsg.path("content");
        assertThat(aContent.size()).isEqualTo(1);
        assertThat(aContent.get(0).path("type").asText()).isEqualTo("tool_use");
        assertThat(aContent.get(0).path("id").asText()).isEqualTo("tu_1");
        assertThat(aContent.get(0).path("name").asText()).isEqualTo("read_file");

        JsonNode toolResultMsg = messages.get(2);
        assertThat(toolResultMsg.path("role").asText()).isEqualTo("user");
        JsonNode trContent = toolResultMsg.path("content");
        assertThat(trContent.size()).isEqualTo(1);
        assertThat(trContent.get(0).path("type").asText()).isEqualTo("tool_result");
        assertThat(trContent.get(0).path("tool_use_id").asText()).isEqualTo("tu_1");
        assertThat(trContent.get(0).path("is_error").asBoolean()).isFalse();

        // 4) Session history must contain Assistant(tool_calls) + ToolResult
        List<Message> history = session.history();
        boolean foundAssistantWithToolCalls = false;
        boolean foundToolResult = false;
        for (Message m : history) {
            if (m instanceof Message.Assistant) {
                Message.Assistant a = (Message.Assistant) m;
                if (a.getToolCalls() != null && a.getToolCalls().size() == 1
                    && "tu_1".equals(a.getToolCalls().get(0).getId())) {
                    foundAssistantWithToolCalls = true;
                }
            } else if (m instanceof Message.ToolResult) {
                Message.ToolResult tr = (Message.ToolResult) m;
                if ("tu_1".equals(tr.getToolUseId())) {
                    foundToolResult = true;
                }
            }
        }
        assertThat(foundAssistantWithToolCalls)
            .as("Session history must contain an Assistant turn with the LLM-emitted tool call")
            .isTrue();
        assertThat(foundToolResult)
            .as("Session history must contain a ToolResult echoing the tool_use id")
            .isTrue();
    }

    /**
     * AC-NN-7 Case 2 — Multiple parallel tool calls in a single response.
     * Exercises dispatchParallel (LinearTurnEngine.L177) over multiple
     * ToolCalls parsed from a single Anthropic response.
     */
    @Test
    @DisplayName("AC-NN-7.2: parallelToolCalls_dispatchParallel_endTurn")
    void parallelToolCalls_dispatchParallel_endTurn() throws Exception {
        cannedResponses.add(buildMockParallelToolUseResponse(new String[][]{
            {"tu_a", "read_file", "/a"},
            {"tu_b", "read_file", "/b"},
        }));
        cannedResponses.add(buildMockTextResponse("both done"));

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new SleepTool("read_file", 10));

        AnthropicLlmProvider provider = newProvider();
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "test-llm-io-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });

        LinearTurnEngine engine = new LinearTurnEngine(
            new HistoryAwarePromptBuilder(Collections.singletonList(
                new ToolSpec("read_file", "Read a file", MAPPER.createObjectNode()))),
            provider,
            new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry),
            new AllowAllPermissionPolicy(),
            pool);

        DefaultSession session = new DefaultSession();
        TurnContext ctx = new DefaultTurnContext(session, defaultConfig(),
            new CapturingSubscriber(), "read both");

        engine.runTurn(ctx, ctx.sink());

        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);

        // 2 HTTP calls (initial + post-tool-result)
        assertThat(capturedRequestBodies).hasSize(2);

        // The 2nd request must have 2 tool_use blocks in the assistant message
        // (proves both were serialized, not just the first)
        JsonNode secondRequest = MAPPER.readTree(capturedRequestBodies.get(1));
        JsonNode assistantMsg = secondRequest.path("messages").get(1);
        JsonNode aContent = assistantMsg.path("content");
        int toolUseCount = 0;
        for (JsonNode block : aContent) {
            if ("tool_use".equals(block.path("type").asText())) {
                toolUseCount++;
            }
        }
        assertThat(toolUseCount).isEqualTo(2);

        // 2 tool_result blocks in the user message (proves both ToolResults
        // were preserved in session history and re-serialized)
        JsonNode toolResultMsg = secondRequest.path("messages").get(2);
        JsonNode trContent = toolResultMsg.path("content");
        int toolResultCount = 0;
        for (JsonNode block : trContent) {
            if ("tool_result".equals(block.path("type").asText())) {
                toolResultCount++;
            }
        }
        assertThat(toolResultCount).isEqualTo(2);

        // Final state — TurnCompleted
        List<AgentEvent> events = ((CapturingSubscriber) ctx.sink()).events();
        AgentEvent last = events.get(events.size() - 1);
        assertThat(last).isInstanceOf(AgentEvent.TurnCompleted.class);
        assertThat(((AgentEvent.TurnCompleted) last).getReason()).isEqualTo(StopReason.END_TURN);
    }

    /**
     * Test-only {@link PromptBuilder} that returns the current session
     * history as the prompt's {@code messages[]} along with a single
     * {@code read_file} tool spec. Replaces {@code RecordingPromptBuilder}
     * (which returns an empty prompt) because this IT needs to verify that
     * the {@code tool_use}/{@code tool_result} round-trip survives a 2-iteration
     * loop — i.e. the 2nd request's {@code messages[]} must echo back the
     * 1st response's {@code tool_use} and the dispatched ToolResult.
     */
    private static final class HistoryAwarePromptBuilder implements PromptBuilder {

        private final List<ToolSpec> tools;

        HistoryAwarePromptBuilder(List<ToolSpec> tools) {
            this.tools = tools;
        }

        @Override
        public Prompt build(TurnContext ctx) {
            // LinearTurnEngine does NOT auto-append ctx.userInput() to session
            // history (only Assistant/ToolResult messages get appended via the
            // ReAct loop). Real DefaultPromptBuilder's 5-segment assembly
            // ([ROLE] / [INSTRUCTIONS] / [PROJECT MEMORY] / [CONVERSATION
            // HISTORY] / [USER MESSAGE]) always includes the user message as
            // the last segment. For IT we mirror that by prepending a User
            // message to the history list before passing it to the provider.
            List<Message> history = new ArrayList<Message>();
            history.add(new Message.User(ctx.userInput()));
            history.addAll(ctx.session().history());
            return Prompt.builder()
                .messages(history)
                .tools(tools)
                .hints(new ModelHints(
                    ctx.config().getLlm().getModel(),
                    ctx.config().getLlm().getTemperature(),
                    ctx.config().getLlm().getMaxTokens()))
                .build();
        }
    }
}