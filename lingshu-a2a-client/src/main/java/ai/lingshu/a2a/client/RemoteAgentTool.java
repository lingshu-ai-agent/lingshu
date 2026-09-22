package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Story #009c — A {@link Tool} that invokes skills on a remote A2A agent
 * (dsh §5.6.1 L2346-2390).
 *
 * <p>The Tool is registered under the fixed name {@value #TOOL_NAME}
 * ({@code "remote_agent"}). The target agent and skill are passed as
 * structured input fields (not embedded in the tool name) — see
 * {@link #inputSchema()} for the exact shape.</p>
 *
 * <p><b>Tool execution contract</b>: {@link #execute(ToolCall, ToolExecutionContext)}
 * parses the input JSON, delegates to {@link A2aTransport#submit(String, String, String)},
 * and propagates the result. If the transport raises an
 * {@link HttpJsonRpcA2aTransport.HttpJsonRpcException}, this Tool converts it
 * into a {@link ToolResult.Status#ERROR} (rather than rethrowing) so that the
 * {@code ToolExecutor} 5-step pipeline can still apply checkpoint semantics.</p>
 *
 * <p><b>Note</b>: this class is <b>not</b> annotated {@code @Component} — it is
 * instantiated by {@link HttpJsonRpcA2aTransportAutoConfiguration#remoteAgentTool}
 * to keep Bean lifecycle in one place (and avoid double-registration).</p>
 *
 * <p><b>Single tool vs N tools trade-off</b>: this Story uses a single tool
 * {@code remote_agent} with structured input (per dsh §5.6.1 + spec.md OQ-1).
 * Future Story {@code #009d} may upgrade to N tools via
 * {@code RemoteAgentSchemaBuilder.buildToolSpecs()} when LLMs broadly support
 * nested {@code oneOf} schemas.</p>
 */
public class RemoteAgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentTool.class);

    /** Fixed tool name; see {@code specs/009c/.../spec.md FR-006}. */
    public static final String TOOL_NAME = "remote_agent";

    private final A2aTransport transport;
    private final ObjectMapper json;

    /**
     * @param transport the resolved {@link A2aTransport} (must not be null —
     *                  wired by HttpJsonRpcA2aTransportAutoConfiguration#remoteAgentTool
     *                  via {@code A2aTransportRouter.resolve(cfg.getA2aTransport(), cfg)}).
     * @param json      Jackson ObjectMapper (must not be null).
     * @throws IllegalArgumentException if either arg is null.
     */
    public RemoteAgentTool(A2aTransport transport, ObjectMapper json) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        this.transport = transport;
        this.json = json;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        return "Invoke a skill on a remote A2A agent. Input: "
            + "{\"agentName\":\"<X>\", \"skill\":\"<Y>\", \"input\": {...}}.";
    }

    @Override
    public JsonNode inputSchema() {
        ObjectNode root = json.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode agentNameNode = props.putObject("agentName");
        agentNameNode.put("type", "string");
        agentNameNode.put("description", "Target remote agent Identity.name");
        ObjectNode skillNode = props.putObject("skill");
        skillNode.put("type", "string");
        skillNode.put("description", "Skill id to invoke");
        ObjectNode inputNode = props.putObject("input");
        inputNode.put("type", "object");
        inputNode.put("description", "JSON args matching the skill's input schema");
        root.putArray("required").add("agentName").add("skill").add("input");
        return root;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        Objects.requireNonNull(call, "call");
        JsonNode input = call.getInput();
        if (input == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .content("input must not be null")
                .isError(true)
                .build();
        }
        String agentName = input.path("agentName").asText("");
        String skill = input.path("skill").asText("");
        JsonNode inputArgs = input.path("input");
        if (agentName.isEmpty() || skill.isEmpty()) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .content("agentName and skill must be non-empty")
                .isError(true)
                .build();
        }
        String inputJson;
        try {
            inputJson = json.writeValueAsString(inputArgs);
        } catch (Exception e) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .content("Failed to serialize input: " + e.getMessage())
                .isError(true)
                .build();
        }
        try {
            ToolResult result = transport.submit(agentName, skill, inputJson);
            log.debug("[RemoteAgentTool] submit({}/{}) -> {}", agentName, skill, result.getStatus());
            return result;
        } catch (HttpJsonRpcA2aTransport.HttpJsonRpcException e) {
            // EC-11: convert LINGS-S08 to ToolResult.error (double-belt with ToolExecutor's catch)
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .content("Remote agent call failed: " + e.getMessage())
                .isError(true)
                .build();
        } catch (Exception e) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .content("Remote agent call threw " + e.getClass().getSimpleName() + ": " + e.getMessage())
                .isError(true)
                .build();
        }
    }

    // ─── test-only accessors ──────────────────────────────────────────────

    A2aTransport getTransport() { return transport; }
    ObjectMapper getJson() { return json; }
}