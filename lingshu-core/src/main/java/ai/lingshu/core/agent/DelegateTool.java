package ai.lingshu.core.agent;

import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.RunResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Story #023 — The {@code Task} tool: dispatches to a typed sub-Agent whose
 * {@link AgentConfig} is field-level-merged from the parent at construction
 * time (dsh v1.5.40 §6.6 L5054-5113 + §6.6.1 L5131-5146).
 *
 * <h3>What</h3>
 * <p>Wraps {@code AgentFactory.create(childConfig) → child.runBlocking(prompt)}
 * behind a {@link Tool} so the parent Agent can delegate one turn of reasoning
 * to a specialized sub-role. The LLM sees {@code Task(subagent_type, prompt)}
 * with three closed values for {@code subagent_type} ({@code explore},
 * {@code engineer}, {@code reviewer}).
 *
 * <h3>Sub-agent config composition (per dsh §6.6.1)</h3>
 * <p>Each sub-agent's {@link AgentConfig} is built <b>once at construction</b>
 * — not per-dispatch — by calling
 * {@link SubAgentInheritance#inheritFromParent(AgentConfig, AgentConfig, SubAgentType)}
 * for every {@link SubAgentType}. The child skeleton is constructed from
 * {@link AgentConfig.TypeConfig} as follows (anything left {@code null}/{@code 0}
 * is inherited from the parent):
 * <ul>
 *   <li><b>{@code llm}</b> — TypeConfig.llm verbatim</li>
 *   <li><b>{@code sandbox}</b> — TypeConfig.sandbox verbatim</li>
 *   <li><b>{@code instructions}</b> —
 *       {@code new Instructions(TypeConfig.systemPromptFile, null, "none", emptyMap())}
 *       when {@code systemPromptFile != null}; else {@code null} → inherit parent</li>
 *   <li><b>{@code tools}</b> — falls back to {@code parent.tools} (TypeConfig.tools
 *       allowlist is OQ-Future #023.x; sub-agent inherits parent's enabled set)</li>
 *   <li>everything else — {@code null}/{@code 0} so {@link SubAgentInheritance}
 *       pulls parent</li>
 * </ul>
 *
 * <h3>Why pre-build (vs per-execute)</h3>
 * <p>The parent Agent is captured at construction; the merge result is
 * deterministic for as long as the parent config does not change. Pre-building
 * keeps {@code execute} O(1) wrt inheritance work and surfaces
 * {@link DelegateErrorCodes#LINGS_D01} misconfiguration at startup, not on the
 * first LLM dispatch (spec §3 reverse AC — startup fail-fast).
 *
 * <h3>Why {@code Agent.runBlocking} (vs {@code AgentCollectors.collectBlocking})</h3>
 * <p>{@code dsh §6.6 L5107} references a {@code AgentCollectors.collectBlocking}
 * helper that does not exist in the codebase — {@code Agent.runBlocking(String)}
 * already implements the synchronous-collect path (Story #001 baseline + Story
 * #020c {@code continueWithUserMessageBlocking}). Reusing it removes the need
 * for a parallel collector class.
 *
 * <h3>Failure modes</h3>
 * <ul>
 *   <li><b>Construction</b> — missing {@link SubAgentType} entry in
 *       {@code delegate.types} → {@link IllegalStateException} carrying
 *       {@code [LINGS-D01]} + the missing key.</li>
 *   <li><b>Construction</b> — duplicate {@link SubAgentType#configKey()} entry
 *       in {@code delegate.types} → the duplicate is ignored (first wins).</li>
 *   <li><b>Construction</b> — {@code AgentFactory.create(...)} succeeds
 *       (validates cleanly because {@link SubAgentInheritance} ensures every
 *       field is populated) — no exception surfaces.</li>
 *   <li><b>execute</b> — unknown {@code subagent_type} in the LLM-emitted
 *       {@link ToolCall} → {@link IllegalArgumentException} from
 *       {@link SubAgentType#fromKey(String)}.</li>
 *   <li><b>execute</b> — missing {@code prompt} field →
 *       {@link NullPointerException} from
 *       {@code JsonNode.get("prompt").asText()}.</li>
 * </ul>
 *
 * <h3>JDK 8 compatibility</h3>
 * <p>Plain class (no {@code record}); {@link JsonNode} built via Jackson
 * {@link ObjectMapper} (already on the classpath via {@code spring-boot-bom},
 * {@code jackson-databind} — no new dependencies). {@code Collections.emptyMap()}
 * rather than {@code Map.of(...) per §3.
 *
 * @see SubAgentType
 * @see SubAgentInheritance
 * @see DelegateAutoConfiguration
 * @since 1.0.0
 */
public final class DelegateTool implements Tool {

    /** Tool name as surfaced to the LLM (mirrors Claude Code fixed name). */
    private static final String TOOL_NAME = "Task";

    /** Reused Jackson mapper — schemas are static, the mapper can be too. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** JSON Schema advertised to the LLM — built once at class init. */
    private static final JsonNode INPUT_SCHEMA = buildInputSchema();

    /** AgentFactory used to spin up a fresh sub-Agent session per dispatch. */
    private final AgentFactory agentFactory;

    /**
     * Parent Agent's config snapshot, captured at construction time. Frozen for
     * the lifetime of this {@code DelegateTool} — hot-reload of the parent
     * config requires a {@code DelegateTool} rebuild via
     * {@link DelegateAutoConfiguration}.
     */
    private final AgentConfig parentConfig;

    /** The {@code agent.delegate} config block (promptsDir + types map). */
    @SuppressWarnings("unused") // exposed via typeConfigs; kept for future reload hooks
    private final AgentConfig.Delegate delegateProps;

    /**
     * Pre-built child configs keyed by sub-agent type. Populated eagerly by
     * {@link #loadConfigs(AgentConfig.Delegate)} at construction so {@link #execute}
     * stays O(1). Iteration order matches {@link SubAgentType#values()} (the
     * underlying map is a {@link LinkedHashMap}).
     */
    private final Map<SubAgentType, AgentConfig> typeConfigs;

    /**
     * Construct and eagerly validate. Wiring is performed by
     * {@link DelegateAutoConfiguration} (or any programmatic caller).
     *
     * @param factory     the Spring-injected {@link AgentFactory} — used to
     *                    spin up a fresh sub-Agent session per dispatch;
     *                    never {@code null}
     * @param parentConfig the parent Agent's config snapshot — frozen for the
     *                     lifetime of this tool (no hot-reload); never
     *                     {@code null}
     * @param props       the {@code agent.delegate} block — must contain a
     *                    {@code TypeConfig} entry for every
     *                    {@link SubAgentType#values() subagent_type} (or
     *                    construction fails fast with {@link DelegateErrorCodes#LINGS_D01})
     * @throws IllegalStateException if {@code props.types} is missing any
     *         {@link SubAgentType}'s key (msg carries {@code [LINGS-D01]})
     * @throws IllegalArgumentException on {@code null} factory / parent / props
     */
    public DelegateTool(AgentFactory factory,
                        AgentConfig parentConfig,
                        AgentConfig.Delegate props) {
        if (factory == null) {
            throw new IllegalArgumentException(
                "AgentFactory must not be null");
        }
        if (parentConfig == null) {
            throw new IllegalArgumentException(
                "parent AgentConfig must not be null");
        }
        if (props == null) {
            throw new IllegalArgumentException(
                "Delegate properties must not be null");
        }
        this.agentFactory = factory;
        this.parentConfig = parentConfig;
        this.delegateProps = props;
        this.typeConfigs = Collections.unmodifiableMap(loadConfigs(props));
    }

    /**
     * Iterate {@link SubAgentType#values()}; for each type, fetch its
     * {@link AgentConfig.TypeConfig} from {@code delegate.types}; missing
     * entries trigger {@link DelegateErrorCodes#LINGS_D01} startup fail-fast;
     * otherwise build a child skeleton and apply
     * {@link SubAgentInheritance#inheritFromParent(AgentConfig, AgentConfig, SubAgentType)}.
     *
     * <p>Returns a {@link LinkedHashMap} for stable iteration in
     * {@link #description()} and predictable log ordering; conversion to an
     * unmodifiable view happens in the field initializer of the ctor.
     *
     * @param props validated non-null {@code agent.delegate} block
     * @return per-type child configs keyed by {@link SubAgentType}; never
     *         {@code null}, never empty (size == {@link SubAgentType#values()}.length)
     * @throws IllegalStateException if any {@link SubAgentType#configKey()} has
     *         no entry in {@code props.types}; message format:
     *         {@code "[LINGS-D01] DelegateTool: missing subagent_type: <key> (known: [explore, engineer, reviewer])"}
     */
    private Map<SubAgentType, AgentConfig> loadConfigs(AgentConfig.Delegate props) {
        Map<String, AgentConfig.TypeConfig> types = props.getTypes();
        if (types == null) {
            throw new IllegalStateException(buildMissingKeyMessage("<null-types>"));
        }
        Map<SubAgentType, AgentConfig> built = new LinkedHashMap<>();
        for (SubAgentType t : SubAgentType.values()) {
            AgentConfig.TypeConfig tc = types.get(t.configKey());
            if (tc == null) {
                throw new IllegalStateException(buildMissingKeyMessage(t.configKey()));
            }
            AgentConfig skeleton = toChildSkeleton(tc);
            built.put(t, SubAgentInheritance.inheritFromParent(parentConfig, skeleton, t));
        }
        return built;
    }

    private static String buildMissingKeyMessage(String missingKey) {
        return "[LINGS-D01] DelegateTool: missing subagent_type: " + missingKey
            + " (known: " + SubAgentType.allKeys() + ")";
    }

    /**
     * Convert a {@link AgentConfig.TypeConfig} block into a child-skeleton
     * {@link AgentConfig}. Fields the user did not populate stay
     * {@code null}/{@code 0} so {@link SubAgentInheritance} inherits them from
     * the parent.
     *
     * <p>The four {@code TypeConfig} fields map to {@link AgentConfig} as
     * follows:
     * <ul>
     *   <li>{@code TypeConfig.llm} → {@link AgentConfig#getLlm()} (verbatim if
     *       non-null)</li>
     *   <li>{@code TypeConfig.sandbox} → {@link AgentConfig#getSandbox()}
     *       (verbatim if non-null)</li>
     *   <li>{@code TypeConfig.systemPromptFile} →
     *       {@link AgentConfig#getInstructions()} with
     *       {@code file = systemPromptFile}, {@code templateEngine = "none"}</li>
     *   <li>{@code TypeConfig.tools} — currently dropped; OQ-#023.x sub-agent
     *       tool allowlist feature deferred to a later Story</li>
     * </ul>
     *
     * <p>All other 20 fields are filled with the {@code AgentFactory.validate}
     * skip-friendly defaults ({@code 0} for ints, {@code null} for refs) so the
     * inheritFromParent pass fills them from the parent.
     */
    private static AgentConfig toChildSkeleton(AgentConfig.TypeConfig tc) {
        AgentConfig.Instructions instructions = null;
        Path systemPromptFile = tc.getSystemPromptFile();
        if (systemPromptFile != null) {
            instructions = new AgentConfig.Instructions(
                systemPromptFile, null, "none", Collections.<String, String>emptyMap());
        }
        return new AgentConfig(
            /* flowEngine      */ null,
            /* llm             */ tc.getLlm(),
            /* prompt          */ null,
            /* toolExecutor    */ null,
            /* sandbox         */ tc.getSandbox(),
            /* compactor       */ null,
            /* sessionStore    */ null,
            /* delegate        */ null, // never set here — DelegateTool forces null in result
            /* mcp             */ null,
            /* skills          */ null,
            /* toolParallelism */ 0,
            /* toolTimeoutSec  */ 0,
            /* approvalTimeSec */ 0,
            /* turnTimeoutSec  */ 0,
            /* llmTimeoutSec   */ 0,
            /* reactMaxSteps   */ 0,
            /* identity        */ null,
            /* instructions    */ instructions,
            /* memory          */ null,
            /* a2aTransport    */ null,
            /* tenants         */ null,
            /* a2a             */ null,
            /* compactorConfig */ null,
            /* tools           */ null
        );
    }

    // ─────────────────────────────────────────────────────────────────────
    // Tool SPI — 4 methods (dsh §4.6)
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        // Generic description — three declared SubAgentType configKeys are
        // surfaced via inputSchema().enum. The description intentionally avoids
        // hard-coding the keys (SubAgentType may grow in future versions).
        return "Delegate one turn of reasoning to a specialized sub-agent. "
            + "Sub-agent runs in a fresh session and inherits the parent's config.";
    }

    @Override
    public JsonNode inputSchema() {
        return INPUT_SCHEMA;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        JsonNode input = call.getInput();
        if (input == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("[LINGS-D01] DelegateTool: missing 'input' on ToolCall")
                .isError(true)
                .build();
        }
        JsonNode typeNode = input.get("subagent_type");
        JsonNode promptNode = input.get("prompt");
        if (typeNode == null || promptNode == null) {
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("[LINGS-D01] DelegateTool: required fields 'subagent_type' and 'prompt' missing")
                .isError(true)
                .build();
        }

        SubAgentType type = SubAgentType.fromKey(typeNode.asText());
        String prompt = promptNode.asText();

        AgentConfig childConfig = typeConfigs.get(type);
        Agent child = agentFactory.create(childConfig);
        RunResult result = child.runBlocking(prompt);

        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId(call.getId())
            .content(result.getFinalText())
            .isError(false)
            .build();
    }

    /**
     * Build the immutable JSON Schema advertised via {@link #inputSchema()}.
     *
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "subagent_type": { "type": "string", "enum": ["explore","engineer","reviewer"] },
     *     "prompt":        { "type": "string" }
     *   },
     *   "required": ["subagent_type","prompt"]
     * }
     * }</pre>
     */
    private static JsonNode buildInputSchema() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = root.putObject("properties");
        ObjectNode subagentType = properties.putObject("subagent_type");
        subagentType.put("type", "string");
        ArrayNode enumValues = subagentType.putArray("enum");
        for (String k : SubAgentType.allKeys()) {
            enumValues.add(k);
        }
        ObjectNode prompt = properties.putObject("prompt");
        prompt.put("type", "string");

        ArrayNode required = root.putArray("required");
        required.add("subagent_type");
        required.add("prompt");

        return root;
    }
}
