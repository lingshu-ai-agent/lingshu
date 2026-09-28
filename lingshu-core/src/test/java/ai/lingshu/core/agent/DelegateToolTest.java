package ai.lingshu.core.agent;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.RunResult;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #023 — L1+L2 tests for {@link DelegateTool} (dsh v1.5.40 §6.6 L5054-5113).
 *
 * <p>Seven cases covering:
 * <ul>
 *   <li>name() returns the canonical {@code "Task"} string</li>
 *   <li>constructor with complete props loads 3 SubAgentType child configs</li>
 *   <li>constructor with missing type throws {@link IllegalStateException} carrying {@code [LINGS-D01]}</li>
 *   <li>description() contains all three configKeys</li>
 *   <li>inputSchema() exposes a 3-value enum on {@code subagent_type} + a {@code prompt} string</li>
 *   <li>execute() happy path — StubAgentFactory returns an Agent whose runBlocking returns a fixed result</li>
 *   <li>execute() with unknown {@code subagent_type} throws IllegalArgumentException via {@link SubAgentType#fromKey}</li>
 * </ul>
 *
 * <p>Why a {@link StubAgentFactory} test-only subclass (per Story #007 workaround pattern):
 * {@link AgentFactory} is a concrete Spring {@code @Component}. Mockito 5.x + JDK 23's
 * inline mockmaker cannot mock concrete {@code InitializingBean} classes, so we subclass
 * and override {@link AgentFactory#create(AgentConfig)} with a configurable lambda.
 */
class DelegateToolTest {

    private StubAgentFactory factory;
    private AgentConfig parentConfig;
    private AgentConfig.Delegate completeProps;
    private ToolExecutionContext ctx;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        factory = new StubAgentFactory();
        ctx = mock(ToolExecutionContext.class);
        mapper = new ObjectMapper();

        // Build a parent AgentConfig with required validation fields
        parentConfig = new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "claude-3-5-sonnet-latest", 8192, 0.7),
            new AgentConfig.Prompt("default", java.util.Collections.<String>emptyList(), 5),
            "default",
            new AgentConfig.Sandbox("strict", "chroot", Paths.get("/tmp"),
                java.util.Collections.singletonList("ls"), java.util.Collections.<String>emptyList()),
            null, null, null, null, null,
            8, 30, 60, 120, 30, 50,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults()
        );

        // Build a complete delegate.types map with one TypeConfig per SubAgentType
        Map<String, AgentConfig.TypeConfig> types = new LinkedHashMap<>();
        types.put("explore",
            new AgentConfig.TypeConfig(null,
                java.util.Collections.<String>emptyList(), null, null));
        types.put("engineer",
            new AgentConfig.TypeConfig(null,
                java.util.Collections.<String>emptyList(), null, null));
        types.put("reviewer",
            new AgentConfig.TypeConfig(null,
                java.util.Collections.<String>emptyList(), null, null));
        completeProps = new AgentConfig.Delegate(Paths.get("/prompts"), types);
    }

    @Test
    @DisplayName("AC-023-DT-1: name_returnsTaskString")
    void name_returnsTaskString() {
        DelegateTool tool = new DelegateTool(factory, parentConfig, completeProps);
        assertThat(tool.name()).isEqualTo("Task");
    }

    @Test
    @DisplayName("AC-023-DT-2: constructor_withCompleteProps_loads3ChildConfigs")
    void constructor_withCompleteProps_loads3ChildConfigs() {
        DelegateTool tool = new DelegateTool(factory, parentConfig, completeProps);
        // Smoke test: tool is constructed without throwing; loadConfigs ran successfully.
        // The 3 SubAgentType values get child configs through SubAgentInheritance.inheritFromParent
        // which is verified separately by SubAgentInheritanceTest. Here we just ensure no ISE.
        assertThat(tool).isNotNull();
        assertThat(tool.description()).isNotBlank();
        assertThat(tool.inputSchema()).isNotNull();
    }

    @Test
    @DisplayName("AC-023-DT-3: constructor_missingSubagentType_throwsISE_LINGS_D01")
    void constructor_missingSubagentType_throwsISE_LINGS_D01() {
        Map<String, AgentConfig.TypeConfig> incomplete = new LinkedHashMap<>();
        incomplete.put("explore",
            new AgentConfig.TypeConfig(null,
                java.util.Collections.<String>emptyList(), null, null));
        // engineer + reviewer intentionally missing
        AgentConfig.Delegate bad = new AgentConfig.Delegate(Paths.get("/prompts"), incomplete);

        assertThatThrownBy(() -> new DelegateTool(factory, parentConfig, bad))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[LINGS-D01]")
            .hasMessageContaining("missing subagent_type: engineer")
            .hasMessageContaining("known: [explore, engineer, reviewer]");
    }

    @Test
    @DisplayName("AC-023-DT-4: description_containsAll3SubAgentTypeConfigKeys")
    void description_containsAll3SubAgentTypeConfigKeys() {
        DelegateTool tool = new DelegateTool(factory, parentConfig, completeProps);
        String description = tool.description();
        assertThat(description).contains("explore");
        assertThat(description).contains("engineer");
        assertThat(description).contains("reviewer");
    }

    @Test
    @DisplayName("AC-023-DT-5: inputSchema_has3ValueEnum_andRequiredFields")
    void inputSchema_has3ValueEnum_andRequiredFields() {
        DelegateTool tool = new DelegateTool(factory, parentConfig, completeProps);
        JsonNode schema = tool.inputSchema();

        // top-level object with properties
        assertThat(schema.get("type").asText()).isEqualTo("object");
        JsonNode properties = schema.get("properties");
        assertThat(properties).isNotNull();

        // subagent_type property has 3-value enum
        JsonNode subagentType = properties.get("subagent_type");
        assertThat(subagentType.get("type").asText()).isEqualTo("string");
        JsonNode enumValues = subagentType.get("enum");
        assertThat(enumValues.isArray()).isTrue();
        assertThat(enumValues.size()).isEqualTo(3);
        assertThat(enumValues.get(0).asText()).isEqualTo("explore");
        assertThat(enumValues.get(1).asText()).isEqualTo("engineer");
        assertThat(enumValues.get(2).asText()).isEqualTo("reviewer");

        // prompt property is a string
        JsonNode prompt = properties.get("prompt");
        assertThat(prompt.get("type").asText()).isEqualTo("string");

        // required = [subagent_type, prompt]
        JsonNode required = schema.get("required");
        assertThat(required.size()).isEqualTo(2);
        assertThat(required.get(0).asText()).isEqualTo("subagent_type");
        assertThat(required.get(1).asText()).isEqualTo("prompt");
    }

    @Test
    @DisplayName("AC-023-DT-6: execute_happyPath_returnsSuccessToolResult")
    void execute_happyPath_returnsSuccessToolResult() throws Exception {
        DelegateTool tool = new DelegateTool(factory, parentConfig, completeProps);

        // Wire the stub factory: any create() returns a child Agent whose runBlocking("Find the answer") yields "explored-result"
        Agent childAgent = mock(Agent.class);
        RunResult runResult = new RunResult("explored-result", 1, null, null, 0L);
        when(childAgent.runBlocking("Find the answer")).thenReturn(runResult);
        factory.childAgent = childAgent;

        // LLM-emitted ToolCall: {name=Task, input={"subagent_type":"explore","prompt":"Find the answer"}}
        JsonNode input = mapper.readTree(
            "{\"subagent_type\":\"explore\",\"prompt\":\"Find the answer\"}");
        ToolCall call = new ToolCall("call-1", "Task", input);

        ToolResult result = tool.execute(call, ctx);

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(result.getToolUseId()).isEqualTo("call-1");
        assertThat(result.getContent()).isEqualTo("explored-result");
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("AC-023-DT-7: execute_unknownSubagentType_throwsIAE")
    void execute_unknownSubagentType_throwsIAE() throws Exception {
        DelegateTool tool = new DelegateTool(factory, parentConfig, completeProps);
        JsonNode input = mapper.readTree(
            "{\"subagent_type\":\"unknown\",\"prompt\":\"anything\"}");
        ToolCall call = new ToolCall("call-2", "Task", input);

        assertThatThrownBy(() -> tool.execute(call, ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown subagent_type: unknown");
    }

    // ── Test-only AgentFactory subclass (Story #007 workaround for JDK 23 + Mockito inline mockmaker) ──

    /**
     * Extends {@link AgentFactory} so we can control {@code create(AgentConfig)} outcomes.
     * The {@code @Autowired} 6-Router ctor is bypassed by passing {@code null} for each Router
     * (no Router method is invoked in tests — we override {@link #create(AgentConfig)} entirely).
     */
    static final class StubAgentFactory extends AgentFactory {
        Agent childAgent;

        StubAgentFactory() {
            super(null, null, null, null, null, null);
        }

        @Override
        public Agent create(AgentConfig config) {
            return childAgent;
        }
    }
}