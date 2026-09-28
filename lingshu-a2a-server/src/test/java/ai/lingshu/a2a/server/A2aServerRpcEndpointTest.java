package ai.lingshu.a2a.server;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L2 slice tests — {@link A2aServer#start()} POST {@code /rpc} JSON-RPC 2.0 dispatcher
 * (Story #009c T014: {@code RpcPlaceholderHandler} → {@code RpcDispatcherHandler},
 * 🆕 Story a2a-server-tool-registry-dispatch: now routes through local
 * {@link ToolRegistry}). Uses real JDK {@link HttpURLConnection} POST to verify
 * end-to-end wire format.
 */
class A2aServerRpcEndpointTest {

    private A2aServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static AgentConfig cfg() {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("default", "noop",
                java.nio.file.Paths.get("."), Collections.<String>emptyList(),
                Collections.<String>emptyList()),
            "default", "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            "default",
            null,
            new AgentConfig.A2a("127.0.0.1", 0, "localhost:50051",
                java.time.Duration.ofMinutes(5), "http://localhost:8080",
                java.time.Duration.ofSeconds(30), java.util.Collections.emptyList(), 10),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults());
    }

    /**
     * 🆕 Story a2a-server-tool-registry-dispatch — build a fresh
     * {@link DefaultToolRegistry} pre-populated with a single stub {@link Tool}
     * named {@code "echo"} whose {@code execute} returns {@code SUCCESS} with
     * the input JSON echoed verbatim as the content.
     *
     * <p>Mirrors how the {@code DemoA2aServer} tests wire a real tool —
     * {@code A2aServer.RpcDispatcherHandler.handleMessageSend} is exercised
     * end-to-end: {@code toolRegistry.lookup} → {@code tool.execute} →
     * {@code ToolResult.content} → JSON-RPC {@code resultJson}.
     *
     * <p>{@link DefaultToolRegistry}'s default constructor is public, so we
     * can {@code new} it directly in tests without going through Spring DI.
     * Keeps the test hermetic — no Spring context bootstrap, no port sharing
     * with other tests, no real {@code @AgentTool} scanner.
     */
    private static ToolRegistry stubToolRegistry() {
        DefaultToolRegistry reg = new DefaultToolRegistry();
        reg.register(new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() {
                return "test stub: echoes input JSON verbatim as ToolResult.content";
            }
            @Override public JsonNode inputSchema() {
                ObjectNode schema = new ObjectMapper().createObjectNode();
                schema.put("type", "object");
                return schema;
            }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
                return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content(call.getInput().toString())
                    .isError(false)
                    .build();
            }
        });
        return reg;
    }

    /** Send a JSON-RPC 2.0 POST and return the body + status code. */
    private static String[] postJsonRpc(int port, String body) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(
            "http://127.0.0.1:" + port + "/rpc").openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json");
        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = con.getResponseCode();
        java.io.InputStream is = (status >= 400) ? con.getErrorStream() : con.getInputStream();
        if (is == null) {
            return new String[]{String.valueOf(status), ""};
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return new String[]{String.valueOf(status), sb.toString()};
    }

    @Test
    @DisplayName("TC-RPC-1: postRpc_messageSend_dispatchesViaToolRegistry")
    void testPostRpcMessageSendEcho() throws Exception {
        // 🆕 Story a2a-server-tool-registry-dispatch — the dispatcher now
        // delegates message/send to the local ToolRegistry, so we register a
        // stub "echo" tool that returns SUCCESS with the input JSON echoed
        // verbatim as the content. Mirrors how DemoA2aServer wires
        // TranslateTools.translate.
        //
        // agentName must equal the server's Identity.defaults() name
        // ("lingShu-agent") — the cross-agent guard added in Story #024
        // rejects requests aimed at a different agent name with ERR_INVALID_PARAMS.
        server = new A2aServer(cfg(), stubToolRegistry());
        server.start();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\","
            + "\"params\":{\"agentName\":\"lingShu-agent\",\"skill\":\"echo\","
            + "\"inputJson\":\"{\\\"x\\\":1}\"}}";
        String[] resp = postJsonRpc(server.getActualPort(), body);
        assertThat(resp[0]).isEqualTo("200");
        assertThat(resp[1]).contains("\"result\"");
        assertThat(resp[1]).contains("\"taskId\"");
        assertThat(resp[1]).contains("\"status\":\"COMPLETED\"");
        assertThat(resp[1]).contains("\"jsonrpc\":\"2.0\"");
        // Verify the stub Tool's output reached the wire: inputJson
        // {"x":1} is echoed verbatim in resultJson.
        assertThat(resp[1]).contains("\\\"x\\\":1");
    }

    @Test
    @DisplayName("TC-RPC-2: postRpc_unknownMethod_returnsErrorCode_minus_32601")
    void testPostRpcUnknownMethodError() throws Exception {
        // The JSON-RPC method-not-found path runs before any ToolRegistry
        // lookup, so passing null is intentional — we are testing the method
        // dispatch layer, not the tool dispatch layer.
        server = new A2aServer(cfg(), null);
        server.start();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"method\":\"unknown/method\"}";
        String[] resp = postJsonRpc(server.getActualPort(), body);
        assertThat(resp[0]).isEqualTo("200");
        assertThat(resp[1]).contains("\"error\"");
        assertThat(resp[1]).contains("\"code\":-32601");
        assertThat(resp[1]).contains("Method not found: unknown/method");
        assertThat(resp[1]).doesNotContain("\"result\"");
    }
}