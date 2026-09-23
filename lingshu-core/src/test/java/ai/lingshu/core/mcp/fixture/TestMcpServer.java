package ai.lingshu.core.mcp.fixture;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal fake MCP server used by Story #021a L3 integration tests.
 *
 * <p><b>What</b> — Reads JSON-RPC requests from stdin (one per line,
 * line-delimited JSON framing — same simplified scheme that
 * {@link ai.lingshu.core.mcp.StdioMcpServerConnection} uses), dispatches by
 * {@code method} name, and writes a JSON-RPC response back to stdout.
 *
 * <p><b>Handlers</b>:
 * <ul>
 *   <li>{@code initialize} → returns a fake protocolVersion + serverInfo</li>
 *   <li>{@code notifications/initialized} → no response (notification)</li>
 *   <li>{@code tools/list} → returns one tool named {@code echo}</li>
 *   <li>{@code tools/call} → returns a fake result with {@code content: "fake-result"}</li>
 *   <li>{@code ping} → returns empty result</li>
 * </ul>
 *
 * <p><b>Behavior switches</b> (controlled via env / system property):
 * <ul>
 *   <li>{@code test.mcp.dontReplyPing=true} — silently drops ping messages
 *       (used by heartbeat-timeout tests)</li>
 *   <li>{@code test.mcp.exitAfter=NNN} — exit after handling {@code NNN} messages
 *       (used to simulate a subprocess death mid-test)</li>
 *   <li>{@code test.mcp.delayMs=NNN} — sleep {@code NNN} ms before any reply
 *       (used to simulate a slow / hanging server)</li>
 * </ul>
 *
 * <p><b>JDK 8 compatibility</b> — No {@code var} / {@code List.of} / sealed / records.
 */
public final class TestMcpServer {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestMcpServer() {
        // utility
    }

    public static void main(String[] args) throws IOException {
        final boolean dropPings = Boolean.parseBoolean(System.getProperty("test.mcp.dontReplyPing", "false"));
        final String exitAfterProp = System.getProperty("test.mcp.exitAfter", "");
        final int exitAfter = exitAfterProp.isEmpty() ? -1 : Integer.parseInt(exitAfterProp);
        final long delayMs = Long.parseLong(System.getProperty("test.mcp.delayMs", "0"));

        PrintStream log = System.err;
        final AtomicBoolean exitFlag = new AtomicBoolean(false);
        if (exitAfter > 0) {
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    log.println("[TestMcpServer] shutdown hook fired");
                }
            }));
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        OutputStream out = System.out;
        int msgCount = 0;
        String line;
        while ((line = in.readLine()) != null && !exitFlag.get()) {
            if (line.isEmpty()) {
                continue;
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            JsonNode req;
            try (JsonParser p = JSON_FACTORY.createParser(line)) {
                req = MAPPER.readTree(p);
            } catch (Exception e) {
                log.println("[TestMcpServer] parse error: " + e.getMessage());
                continue;
            }
            if (req == null) {
                continue;
            }
            String method = req.path("method").asText("");
            JsonNode idNode = req.get("id");
            JsonNode params = req.get("params");
            msgCount++;

            switch (method) {
                case "initialize":
                    writeResponse(out, idNode, buildInitializeResult());
                    break;
                case "notifications/initialized":
                    // no response
                    break;
                case "tools/list":
                    writeResponse(out, idNode, buildToolsListResult());
                    break;
                case "ping":
                    if (!dropPings) {
                        writeResponse(out, idNode, MAPPER.createObjectNode());
                    }
                    break;
                case "tools/call":
                    writeResponse(out, idNode, buildCallResult(params));
                    break;
                default:
                    // unknown — ignore
                    break;
            }
            out.flush();

            if (exitAfter > 0 && msgCount >= exitAfter) {
                exitFlag.set(true);
            }
        }
        log.println("[TestMcpServer] stdin closed, exiting");
    }

    private static void writeResponse(OutputStream out, JsonNode idNode, JsonNode result) throws IOException {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (idNode != null && !idNode.isNull()) {
            resp.set("id", idNode);
        }
        resp.set("result", result);
        synchronized (out) {
            out.write((MAPPER.writeValueAsString(resp) + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static JsonNode buildInitializeResult() {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("protocolVersion", "2024-11-05");
        ObjectNode info = MAPPER.createObjectNode();
        info.put("name", "test-mcp-server");
        info.put("version", "1.0.0");
        r.set("serverInfo", info);
        r.set("capabilities", MAPPER.createObjectNode());
        return r;
    }

    private static JsonNode buildToolsListResult() {
        ObjectNode r = MAPPER.createObjectNode();
        com.fasterxml.jackson.databind.node.ArrayNode tools = MAPPER.createArrayNode();
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", "echo");
        tool.put("description", "echo input");
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        ObjectNode inputProp = MAPPER.createObjectNode();
        inputProp.put("type", "string");
        props.set("input", inputProp);
        schema.set("properties", props);
        tool.set("inputSchema", schema);
        tools.add(tool);
        r.set("tools", tools);
        return r;
    }

    private static JsonNode buildCallResult(JsonNode params) {
        ObjectNode r = MAPPER.createObjectNode();
        r.put("content", "fake-result");
        r.put("isError", false);
        return r;
    }
}