package ai.lingshu.core.impl.llm;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.flow.support.CapturingSubscriber;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #027b L2 integration tests — true SSE streaming end-to-end against
 * a mock Anthropic HTTP server (AC-NN-1 + AC-NN-7). The mock server uses
 * {@link AnthropicStreamTestSupport} and streams events with 20 ms
 * inter-event pacing so the test can observe incremental
 * {@link AgentEvent} emissions rather than a single burst.
 *
 * <p><b>AC-NN-9 (R-13 dep-tree 0 binary delta)</b> is verified separately by
 * the {@code T-dep-tree-*} shell tasks in {@code tasks.md} P4 — it requires
 * {@code mvn dependency:tree} introspection that doesn't belong in a unit
 * test. See {@code PR body → R-13 dependency:tree 自查} for the expected
 * diff output.
 */
@DisplayName("Story #027b — AnthropicLlmProvider ↔ SSE true streaming L2 IT")
class AnthropicStreamProviderIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnthropicStreamTestSupport.StartedServer server;

    @BeforeEach
    void startMockServer() {
        // Default no-op server setup; tests start a fresh server in @BeforeEach
        // via startSseServerOnFreePort() so each test gets its own port.
    }

    @AfterEach
    void stopMockServer() {
        if (server != null) {
            AnthropicStreamTestSupport.stopServer(server.getServer());
            server = null;
        }
    }

    // ── AC-NN-1: Accept: text/event-stream header ────────────────────────

    @Test
    @DisplayName("AC-NN-1: acceptHeaderIsTextEventStream")
    void acceptHeaderIsTextEventStream() throws Exception {
        AtomicReference<String> capturedAccept = new AtomicReference<>();
        // Empty SSE event list = server hangs up immediately. We only need to
        // observe the request headers — the response body is irrelevant.
        server = AnthropicStreamTestSupport.startSseServerOnFreePort(
            Arrays.asList(),
            body -> { /* body unused */ });
        // Override the handler with one that captures Accept header.
        // (Re-create because the lambda above doesn't expose the header.)
        AnthropicStreamTestSupport.stopServer(server.getServer());
        server = null;

        // Manually build a server that captures the Accept header.
        com.sun.net.httpserver.HttpServer httpServer = com.sun.net.httpserver.HttpServer.create(
            new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/messages", exchange -> {
            capturedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
            // Send a minimal valid SSE sequence so stream() doesn't throw.
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            // Empty body — stream() will time out waiting for message_stop.
            // We don't care: just verify the header was sent.
            exchange.close();
        });
        httpServer.start();
        String baseUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort();

        AnthropicLlmProvider provider = new AnthropicLlmProvider(
            baseUrl, "test-key", "2023-06-01", "test-model", 1024, 0.7);

        try {
            // stream() will fail because no message_stop was sent — that's OK,
            // we only care that the Accept header was captured on the way in.
            try {
                CompletableFuture<LlmResponse> f = provider.stream(
                    buildPromptWithToolSpec(), newTurn(), null);
                f.get(2, TimeUnit.SECONDS);
            } catch (Exception expected) {
                // The stream error is irrelevant — Accept header was sent.
            }
            assertThat(capturedAccept.get()).isEqualTo("text/event-stream");
        } finally {
            httpServer.stop(0);
        }
    }

    // ── AC-NN-7: end-to-end true SSE streaming ──────────────────────────

    @Test
    @DisplayName("AC-NN-7: endToEndSseStreaming_mockServerEmitsIncrementally")
    void endToEndSseStreaming_mockServerEmitsIncrementally() throws Exception {
        List<String> sseEvents = new ArrayList<>();
        sseEvents.add(buildSseMessageStart(42));
        sseEvents.add(buildSseContentBlockStartToolUse(0, "tu_1", "read_file"));
        sseEvents.add(buildSseInputJsonDelta(0, "{\"path\":\"/tmp/y\"}"));
        sseEvents.add(buildSseContentBlockStop(0));
        sseEvents.add(buildSseMessageDelta("tool_use", 7));
        sseEvents.add(buildSseMessageStop());

        server = AnthropicStreamTestSupport.startSseServerOnFreePort(sseEvents, null);

        AnthropicLlmProvider provider = new AnthropicLlmProvider(
            server.baseUrl(), "test-key", "2023-06-01", "test-model", 1024, 0.7);
        CapturingSubscriber sink = new CapturingSubscriber();

        CompletableFuture<LlmResponse> future = provider.stream(
            buildPromptWithToolSpec(), newTurn(), sink);
        LlmResponse resp = future.get(5, TimeUnit.SECONDS);

        // ── Verify the assembled LlmResponse ──
        assertThat(resp.getToolCalls()).hasSize(1);
        assertThat(resp.getToolCalls().get(0).getId()).isEqualTo("tu_1");
        assertThat(resp.getToolCalls().get(0).getName()).isEqualTo("read_file");
        assertThat(resp.getToolCalls().get(0).getInput().path("path").asText()).isEqualTo("/tmp/y");
        assertThat(resp.getStopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(resp.getUsage().getInputTokens()).isEqualTo(42);
        assertThat(resp.getUsage().getOutputTokens()).isEqualTo(7);

        // ── Verify the sink received incremental AgentEvents ──
        // Expect: ReasoningStarted + ToolStarted (from the SSE stream above).
        // No TextDelta because the response had only tool_use, no text.
        long reasoningCount = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ReasoningStarted).count();
        long toolStartedCount = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ToolStarted).count();
        long textDeltaCount = sink.events().stream()
            .filter(e -> e instanceof AgentEvent.TextDelta).count();
        assertThat(reasoningCount).isEqualTo(1);
        assertThat(toolStartedCount).isEqualTo(1);
        assertThat(textDeltaCount).isEqualTo(0);

        AgentEvent.ToolStarted ts = (AgentEvent.ToolStarted) sink.events().stream()
            .filter(e -> e instanceof AgentEvent.ToolStarted).findFirst()
            .orElseThrow(new java.util.function.Supplier<RuntimeException>() {
                @Override public RuntimeException get() {
                    return new RuntimeException("expected ToolStarted in sink");
                }
            });
        assertThat(ts.getToolCallId()).isEqualTo("tu_1");
        assertThat(ts.getName()).isEqualTo("read_file");
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /** Build a {@link Prompt} with a user message + 1 {@link ToolSpec}. */
    private static Prompt buildPromptWithToolSpec() {
        ObjectNode inputSchema = MAPPER.createObjectNode()
            .put("type", "object")
            .set("properties", MAPPER.createObjectNode()
                .set("path", MAPPER.createObjectNode().put("type", "string")));
        ToolSpec spec = new ToolSpec(
            "read_file",
            "Read a file from disk",
            inputSchema);
        return Prompt.builder()
            .messages(Arrays.asList(new Message.User("read /tmp/y")))
            .tools(Arrays.asList(spec))
            .build();
    }

    private TurnContext newTurn() {
        AgentConfig cfg = new AgentConfig(
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
            AgentConfig.ToolsConfig.defaults(), "default"
        );
        return new DefaultTurnContext(new DefaultSession(), cfg,
            new CapturingSubscriber(), "test");
    }

    // SSE event block builders (return raw SSE text format with blank-line terminator).

    private static String buildSseMessageStart(int inputTokens) {
        return "event: message_start\n"
            + "data: {\"type\":\"message_start\",\"message\":{"
            + "\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"output_tokens\":0}}}\n"
            + "\n";
    }

    private static String buildSseContentBlockStartToolUse(int index, String id, String name) {
        return "event: content_block_start\n"
            + "data: {\"type\":\"content_block_start\",\"index\":" + index + ","
            + "\"content_block\":{\"type\":\"tool_use\",\"id\":\"" + id + "\","
            + "\"name\":\"" + name + "\"}}\n"
            + "\n";
    }

    private static String buildSseInputJsonDelta(int index, String partialJson) {
        return "event: content_block_delta\n"
            + "data: {\"type\":\"content_block_delta\",\"index\":" + index + ","
            + "\"delta\":{\"type\":\"input_json_delta\","
            + "\"partial_json\":" + MAPPER.valueToTree(partialJson).toString() + "}}\n"
            + "\n";
    }

    private static String buildSseContentBlockStop(int index) {
        return "event: content_block_stop\n"
            + "data: {\"type\":\"content_block_stop\",\"index\":" + index + "}\n"
            + "\n";
    }

    private static String buildSseMessageDelta(String stopReason, int outputTokens) {
        return "event: message_delta\n"
            + "data: {\"type\":\"message_delta\","
            + "\"delta\":{\"stop_reason\":\"" + stopReason + "\"},"
            + "\"usage\":{\"output_tokens\":" + outputTokens + "}}\n"
            + "\n";
    }

    private static String buildSseMessageStop() {
        return "event: message_stop\n"
            + "data: {\"type\":\"message_stop\"}\n"
            + "\n";
    }
}