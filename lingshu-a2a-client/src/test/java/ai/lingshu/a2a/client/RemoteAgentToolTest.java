package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.slot.ToolCallConfig;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1 unit tests — {@link RemoteAgentTool} (4 cases per data-model.md DM-03).
 * Uses a hand-rolled {@link FakeA2aTransport} stub to avoid adding Mockito as a
 * new dependency (R-13 mitigation (d) — 0 new Maven dependencies).
 */
class RemoteAgentToolTest {

    /**
     * Minimal hand-rolled A2aTransport stub. Records submit calls and returns
     * a configured {@link ToolResult} or throws a configured exception.
     *
     * <p>Story #009d — also serves {@link #fetchCard(String)} from a small
     * configurable map so description() skills-enumeration tests can assert
     * the composed string without spinning up a real HTTP server.</p>
     */
    static final class FakeA2aTransport implements A2aTransport {
        private ToolResult nextResult = ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .content("{\"echo\":true}")
            .isError(false)
            .build();
        private RuntimeException nextException;
        private final List<String[]> submitCalls = new ArrayList<String[]>();
        private final AtomicInteger submitCount = new AtomicInteger(0);
        /** agentName → AgentCard map (Map form). */
        private final Map<String, Map<String, Object>> cardsByName = new java.util.HashMap<>();
        /** Optional exception for {@link #fetchCard(String)} (Story #009d). */
        private RuntimeException fetchException;

        void setNextResult(ToolResult r) { this.nextResult = r; this.nextException = null; }
        void setNextException(RuntimeException e) { this.nextException = e; }

        void putCard(String agentName, Map<String, Object> card) {
            cardsByName.put(agentName, card);
        }
        void setFetchException(RuntimeException e) { this.fetchException = e; }

        int getSubmitCount() { return submitCount.get(); }
        List<String[]> getSubmitCalls() { return Collections.unmodifiableList(submitCalls); }

        @Override
        public Map<String, Object> fetchCard(String agentName) {
            if (fetchException != null) throw fetchException;
            return cardsByName.get(agentName);
        }

        @Override
        public ToolResult submit(String agentName, String skill, String inputJson) {
            submitCount.incrementAndGet();
            submitCalls.add(new String[]{agentName, skill, inputJson});
            if (nextException != null) throw nextException;
            return nextResult;
        }

        @Override public ToolResult get(String taskId) { return nextResult; }
        @Override public boolean cancel(String taskId) { return true; }
        @Override public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent) { }
    }

    /** Minimal hand-rolled ToolExecutionContext (interface — anonymous class). */
    static final class FakeToolExecutionContext implements ToolExecutionContext {
        @Override public ai.lingshu.core.runtime.Session session() { return null; }
        @Override public ToolSink sink() { return null; }
        @Override public Path workingDirectory() { return Paths.get("."); }
        @Override public FileSystem fs() { return null; }
        @Override public NetworkClient http() { return null; }
        @Override public ApprovalGate approval() { return null; }
        @Override public CancellationToken cancellation() { return null; }
        @Override public ToolCallConfig callConfig() { return null; }
    }

    private FakeA2aTransport transport;
    private ObjectMapper json;
    private RemoteAgentTool tool;

    @BeforeEach
    void setUp() {
        transport = new FakeA2aTransport();
        json = new ObjectMapper();
        tool = new RemoteAgentTool(transport, json);
    }

    // ─── Test 1: execute happy path (VS-4 + FR-007) ──────────────────────

    @Test
    @DisplayName("TC-RAT-1: execute_happyPath_invokesTransportAndPropagatesResult")
    void testExecuteHappyPath() {
        // Build input: {agentName:"alice", skill:"echo", input:{x:1}}
        ObjectNode input = json.createObjectNode();
        input.put("agentName", "alice");
        input.put("skill", "echo");
        ObjectNode args = input.putObject("input");
        args.put("x", 1);

        ToolCall call = new ToolCall(
            "call-1", RemoteAgentTool.TOOL_NAME, input);
        ToolResult result = tool.execute(call, new FakeToolExecutionContext());

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(result.getContent()).isEqualTo("{\"echo\":true}");
        // Verify transport.submit was called once with serialized input
        assertThat(transport.getSubmitCount()).isEqualTo(1);
        assertThat(transport.getSubmitCalls().get(0)[0]).isEqualTo("alice");
        assertThat(transport.getSubmitCalls().get(0)[1]).isEqualTo("echo");
        assertThat(transport.getSubmitCalls().get(0)[2]).isEqualTo("{\"x\":1}");
    }

    // ─── Test 2: transport throws LINGS-S08 → ToolResult.toolError (VS-5 + EC-11) ──

    @Test
    @DisplayName("TC-RAT-2: execute_transportThrowsLingsS08_returnsToolError")
    void testExecuteTransportThrowsLingsS08() {
        transport.setNextException(new HttpJsonRpcA2aTransport.HttpJsonRpcException(
            "fetchCard HTTP 503: service unavailable",
            "verify remote agent is reachable"));

        ObjectNode input = json.createObjectNode();
        input.put("agentName", "ghost");
        input.put("skill", "echo");
        input.putObject("input");

        ToolCall call = new ToolCall("call-2", RemoteAgentTool.TOOL_NAME, input);
        ToolResult result = tool.execute(call, new FakeToolExecutionContext());

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.isError()).isTrue();
        assertThat(result.getContent()).contains("Remote agent call failed");
        assertThat(result.getContent()).contains("503");
        // Verify the exception's errorCode is preserved in the message
        assertThat(result.getContent()).contains("LINGS-S08");
    }

    // ─── Test 3: inputSchema has fixed shape (FR-013) ────────────────────

    @Test
    @DisplayName("TC-RAT-3: inputSchema_fixedShape")
    void testInputSchemaFixedShape() {
        JsonNode schema = tool.inputSchema();
        assertThat(schema).isNotNull();
        assertThat(schema.path("type").asText()).isEqualTo("object");
        JsonNode props = schema.path("properties");
        assertThat(props).isNotNull();
        assertThat(props.has("agentName")).isTrue();
        assertThat(props.has("skill")).isTrue();
        assertThat(props.has("input")).isTrue();
        JsonNode required = schema.path("required");
        assertThat(required.isArray()).isTrue();
        assertThat(required.size()).isEqualTo(3);
    }

    // ─── Test 4: description is non-blank (FR-006) ────────────────────────

    @Test
    @DisplayName("TC-RAT-4: description_nonBlank")
    void testDescriptionNotBlank() {
        String desc = tool.description();
        assertThat(desc).isNotNull();
        assertThat(desc).isNotBlank();
        assertThat(desc).contains("remote");
        assertThat(desc).contains("agent");
    }

    // ─── Story #009d — Test 5: schemaBuilder wired but no agents → hint ──

    @Test
    @DisplayName("TC-RAT-5: description_schemaBuilderWired_butNoAgents_returnsHint")
    void testDescriptionSchemaBuilderWiredButNoAgents() {
        RemoteAgentSchemaBuilder sb = new RemoteAgentSchemaBuilder(json);
        RemoteAgentTool wiredTool = new RemoteAgentTool(transport, json, sb);
        String desc = wiredTool.description();

        // Branch 2 (plan.md §5.1): schemaBuilder != null, remoteAgents empty
        // → BASE_DESCRIPTION + SCHEMA_BUILDER_HINT_NO_AGENTS
        assertThat(desc).startsWith("Invoke a skill on a remote A2A agent.");
        assertThat(desc).contains("RemoteAgentSchemaBuilder wired");
        assertThat(desc).contains("configure agent.a2a.remoteAgents");
    }

    // ─── Story #009d — Test 6: full skill enumeration with truncation ────

    @Test
    @DisplayName("TC-RAT-6: description_fullEnumeration_composesSkillsList")
    void testDescriptionFullEnumeration() {
        // Wire FakeA2aTransport with 2 cards so description() can fetch them
        Map<String, Object> aliceCard = new java.util.HashMap<>();
        aliceCard.put("name", "alice");
        aliceCard.put("description", "Alice agent");
        aliceCard.put("skills", Arrays.asList(
            skillMap("echo", "Echo back input"),
            skillMap("greet", "Greet user")
        ));
        transport.putCard("alice", aliceCard);

        Map<String, Object> bobCard = new java.util.HashMap<>();
        bobCard.put("name", "bob");
        bobCard.put("description", "Bob agent");
        bobCard.put("skills", Arrays.asList(
            skillMap("search", "Search docs")
        ));
        transport.putCard("bob", bobCard);

        RemoteAgentSchemaBuilder sb = new RemoteAgentSchemaBuilder(json);
        List<AgentRef> refs = Arrays.asList(
            new AgentRef("alice", "http://alice:8080", 10),
            new AgentRef("bob", "http://bob:8080", 5));
        RemoteAgentTool fullTool = new RemoteAgentTool(
            transport, json, sb, refs, 10);

        String desc = fullTool.description();
        assertThat(desc).startsWith("Invoke a skill on a remote A2A agent.");
        assertThat(desc).contains("Available skills (3 total):");
        assertThat(desc).contains("- call_alice_echo: Echo back input (via alice: Alice agent)");
        assertThat(desc).contains("- call_alice_greet: Greet user (via alice: Alice agent)");
        assertThat(desc).contains("- call_bob_search: Search docs (via bob: Bob agent)");
    }

    // ─── Story #009d — Test 7: skill enumeration truncates with "... and N more" ──

    @Test
    @DisplayName("TC-RAT-7: description_skillEnumeration_truncatesWithMore")
    void testDescriptionSkillEnumerationTruncates() {
        // 12 single-letter skills on alice, limit = 5 → shows 5 then "... and 7 more".
        // Single-letter ids keep the dictionary sort predictable
        // (skill_a..skill_l rather than skill_10..skill_11..skill_2).
        List<Map<String, Object>> manySkills = new ArrayList<>();
        for (char c = 'a'; c <= 'l'; c++) {
            manySkills.add(skillMap("skill_" + c, "Skill " + c));
        }
        Map<String, Object> aliceCard = new java.util.HashMap<>();
        aliceCard.put("name", "alice");
        aliceCard.put("description", "Alice");
        aliceCard.put("skills", manySkills);
        transport.putCard("alice", aliceCard);

        RemoteAgentSchemaBuilder sb = new RemoteAgentSchemaBuilder(json);
        RemoteAgentTool fullTool = new RemoteAgentTool(
            transport, json, sb,
            Collections.singletonList(new AgentRef("alice", null, 0)),
            5);

        String desc = fullTool.description();
        assertThat(desc).contains("Available skills (12 total, showing 5):");
        assertThat(desc).contains("- call_alice_skill_a:");
        assertThat(desc).contains("- call_alice_skill_e:");
        assertThat(desc).doesNotContain("call_alice_skill_f:");
        assertThat(desc).contains("... and 7 more");
    }

    // ─── Story #009d — Test 8: fetchCard throws → falls back to hint ─────

    @Test
    @DisplayName("TC-RAT-8: description_fetchCardThrows_fallsBackGracefully")
    void testDescriptionFetchCardThrows() {
        transport.setFetchException(new RuntimeException("network down"));

        RemoteAgentSchemaBuilder sb = new RemoteAgentSchemaBuilder(json);
        RemoteAgentTool fullTool = new RemoteAgentTool(
            transport, json, sb,
            Collections.singletonList(new AgentRef("alice", null, 0)),
            10);

        String desc = fullTool.description();
        // Branch 2 fallback: cards empty after fetch failure → hint
        assertThat(desc).contains("RemoteAgentSchemaBuilder wired");
        assertThat(desc).contains("configure agent.a2a.remoteAgents");
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> skillMap(String id, String description) {
        Map<String, Object> s = new java.util.HashMap<>();
        s.put("id", id);
        s.put("description", description);
        return s;
    }
}