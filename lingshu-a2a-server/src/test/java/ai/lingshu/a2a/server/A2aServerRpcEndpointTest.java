package ai.lingshu.a2a.server;

import ai.lingshu.core.runtime.AgentConfig;
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
 * (Story #009c T014: {@code RpcPlaceholderHandler} → {@code RpcDispatcherHandler}).
 * Uses real JDK {@link HttpURLConnection} POST to verify end-to-end wire format.
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
    @DisplayName("TC-RPC-1: postRpc_messageSend_echoesInputAndReturnsTaskId")
    void testPostRpcMessageSendEcho() throws Exception {
        server = new A2aServer(cfg());
        server.start();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"message/send\","
            + "\"params\":{\"agentName\":\"alice\",\"skill\":\"echo\",\"inputJson\":\"{\\\"x\\\":1}\"}}";
        String[] resp = postJsonRpc(server.getActualPort(), body);
        assertThat(resp[0]).isEqualTo("200");
        assertThat(resp[1]).contains("\"result\"");
        assertThat(resp[1]).contains("\"taskId\"");
        assertThat(resp[1]).contains("\"status\":\"COMPLETED\"");
        assertThat(resp[1]).contains("\"jsonrpc\":\"2.0\"");
    }

    @Test
    @DisplayName("TC-RPC-2: postRpc_unknownMethod_returnsErrorCode_minus_32601")
    void testPostRpcUnknownMethodError() throws Exception {
        server = new A2aServer(cfg());
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