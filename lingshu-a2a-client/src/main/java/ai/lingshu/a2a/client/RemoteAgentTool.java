package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Story #009c + #009d — A {@link Tool} that invokes skills on a remote A2A
 * agent (dsh §5.6.1 L2346-2390).
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
 * <p><b>Story #009d — Skill enumeration</b>: when a
 * {@link RemoteAgentSchemaBuilder} is wired AND {@link AgentRef}s are
 * configured under {@code agent.a2a.remoteAgents[]}, {@link #description()}
 * fetches each agent's {@code AgentCard} via {@link A2aTransport#fetchCard}
 * (errors logged + skipped, never propagated) and composes a truncated
 * skills list onto {@link #BASE_DESCRIPTION}. The list is cached after the
 * first successful computation so that repeated {@code description()} calls
 * are O(1).</p>
 */
public class RemoteAgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentTool.class);

    /** Fixed tool name; see {@code specs/009c/.../spec.md FR-006}. */
    public static final String TOOL_NAME = "remote_agent";

    /**
     * Base description text — identical to #009c, extracted so the
     * schemaBuilder-wired variant can compose onto a single source of truth.
     */
    private static final String BASE_DESCRIPTION =
        "Invoke a skill on a remote A2A agent. Input: "
            + "{\"agentName\":\"<X>\", \"skill\":\"<Y>\", \"input\": {...}}.";

    /**
     * Story #009d — fallback hint when the schemaBuilder is wired but no
     * remote agents are configured (or none have fetched yet).
     */
    private static final String SCHEMA_BUILDER_HINT_NO_AGENTS =
        " (RemoteAgentSchemaBuilder wired — configure agent.a2a.remoteAgents to enumerate skills)";

    /**
     * Default cap for the skills list embedded in {@link #description()}.
     * Mirrored from {@code AgentConfig.A2a.descriptionSkillLimit}'s default.
     */
    static final int DEFAULT_DESCRIPTION_SKILL_LIMIT = 10;

    private final A2aTransport transport;
    private final ObjectMapper json;
    private final RemoteAgentSchemaBuilder schemaBuilder;
    private final List<AgentRef> remoteAgents;
    private final int descriptionSkillLimit;

    /**
     * Memoized description — description is intended to be invoked once at
     * tool registration and then cached by the {@code ToolRegistry}. We
     * still cache it here so that even if a caller polls repeatedly we do
     * not re-fetch AgentCards.
     */
    private final AtomicReference<String> cachedDescription = new AtomicReference<String>();

    /**
     * Backward-compatible 2-arg constructor (#009c signature). When this
     * constructor is used, {@link #description()} returns the bare
     * {@link #BASE_DESCRIPTION} — behaviorally identical to #009c.
     *
     * @param transport the resolved {@link A2aTransport} (must not be null —
     *                  wired by HttpJsonRpcA2aTransportAutoConfiguration#remoteAgentTool
     *                  via {@code A2aTransportRouter.resolve(cfg.getA2aTransport(), cfg)}).
     * @param json      Jackson ObjectMapper (must not be null).
     * @throws IllegalArgumentException if either arg is null.
     */
    public RemoteAgentTool(A2aTransport transport, ObjectMapper json) {
        this(transport, json, null, null, DEFAULT_DESCRIPTION_SKILL_LIMIT);
    }

    /**
     * Story #009d — 3-arg constructor that takes an optional
     * {@link RemoteAgentSchemaBuilder} (nullable). When the builder is
     * wired but no remote agents are configured,
     * {@link #description()} appends {@link #SCHEMA_BUILDER_HINT_NO_AGENTS}.
     *
     * @param transport     the resolved {@link A2aTransport} (must not be null).
     * @param json          Jackson ObjectMapper (must not be null).
     * @param schemaBuilder optional — when non-null, {@link #description()}
     *                      will enumerate skills if remote agents are also
     *                      configured (use the 5-arg constructor).
     * @throws IllegalArgumentException if {@code transport} or {@code json}
     *                                  is null.
     */
    public RemoteAgentTool(A2aTransport transport,
                           ObjectMapper json,
                           RemoteAgentSchemaBuilder schemaBuilder) {
        this(transport, json, schemaBuilder, null, DEFAULT_DESCRIPTION_SKILL_LIMIT);
    }

    /**
     * Story #009d — full constructor. Wires the schemaBuilder AND the list
     * of configured remote agents so that {@link #description()} can
     * enumerate skills.
     *
     * @param transport              the resolved {@link A2aTransport} (must not be null).
     * @param json                   Jackson ObjectMapper (must not be null).
     * @param schemaBuilder          optional — when null, description returns
     *                               bare {@link #BASE_DESCRIPTION}.
     * @param remoteAgents           optional — list of configured remote
     *                               agents; null is treated as empty list.
     * @param descriptionSkillLimit  cap on the skills list embedded in
     *                               {@link #description()}; non-positive
     *                               values fall back to
     *                               {@link #DEFAULT_DESCRIPTION_SKILL_LIMIT}.
     */
    public RemoteAgentTool(A2aTransport transport,
                           ObjectMapper json,
                           RemoteAgentSchemaBuilder schemaBuilder,
                           List<AgentRef> remoteAgents,
                           int descriptionSkillLimit) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        this.transport = transport;
        this.json = json;
        this.schemaBuilder = schemaBuilder;
        this.remoteAgents = (remoteAgents == null) ? Collections.<AgentRef>emptyList() : remoteAgents;
        this.descriptionSkillLimit = descriptionSkillLimit > 0
            ? descriptionSkillLimit
            : DEFAULT_DESCRIPTION_SKILL_LIMIT;
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public String description() {
        String cached = cachedDescription.get();
        if (cached != null) {
            return cached;
        }
        String computed = computeDescription();
        cachedDescription.compareAndSet(null, computed);
        return computed;
    }

    private String computeDescription() {
        // Story #009d — wiring marker (plan.md §5.1 + spec.md AC-2.2 / AC-2.3 / EC-9).
        // Branch 1: no schemaBuilder → bare base string (backward compat with #009c).
        if (schemaBuilder == null) {
            return BASE_DESCRIPTION;
        }
        try {
            // Branch 2: schemaBuilder wired but no configured agents → hint string.
            if (remoteAgents.isEmpty()) {
                return BASE_DESCRIPTION + SCHEMA_BUILDER_HINT_NO_AGENTS;
            }
            // Branch 3: full skill enumeration — fetch cards via transport,
            // generate ToolSpecs, append truncated list (EC-9).
            List<Map<String, Object>> cards = fetchCardsForDescription();
            List<ToolSpec> specs = schemaBuilder.buildToolSpecs(cards);
            if (specs.isEmpty()) {
                // AC-2.3: buildToolSpecs returned empty (cards empty or all
                // invalid) → fall back to the hint string so operators can
                // tell the wiring is live but no skills are visible yet.
                return BASE_DESCRIPTION + SCHEMA_BUILDER_HINT_NO_AGENTS;
            }
            return composeSkillsDescription(specs);
        } catch (RuntimeException e) {
            // EC-11 + NFR-004 — anything thrown during composition falls
            // back to the bare base string with a WARN log.
            log.warn(
                "[RemoteAgentTool] description() compose threw, falling back: {}",
                e.getMessage());
            return BASE_DESCRIPTION;
        }
    }

    /**
     * Fetch AgentCards for every configured {@link AgentRef}. Cards that
     * fail to fetch (transport down, 404, etc.) are logged + skipped — they
     * never propagate as exceptions. Returns an empty list when no agents
     * are configured.
     */
    private List<Map<String, Object>> fetchCardsForDescription() {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>(remoteAgents.size());
        for (AgentRef ref : remoteAgents) {
            String agentName = ref.getName();
            if (agentName == null || agentName.isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> card = transport.fetchCard(agentName);
                if (card != null && !card.isEmpty()) {
                    out.add(card);
                }
            } catch (RuntimeException e) {
                log.warn(
                    "[RemoteAgentTool] fetchCard({}) threw while composing description: {}",
                    agentName, e.getMessage());
            }
        }
        return out;
    }

    /**
     * Append a truncated skills list onto the base description. Mirrors
     * {@code describeSpecs(specs)} but embeds directly into the description
     * string so we keep a single {@code String} return value.
     */
    private String composeSkillsDescription(List<ToolSpec> specs) {
        StringBuilder sb = new StringBuilder(BASE_DESCRIPTION);
        sb.append("\n\nAvailable skills (").append(specs.size()).append(" total");
        if (specs.size() > descriptionSkillLimit) {
            sb.append(", showing ").append(descriptionSkillLimit);
        }
        sb.append("):");
        int shown = 0;
        for (ToolSpec spec : specs) {
            if (shown >= descriptionSkillLimit) {
                sb.append("\n  ... and ").append(specs.size() - shown).append(" more");
                break;
            }
            sb.append("\n  - ").append(spec.getName()).append(": ").append(spec.getDescription());
            shown++;
        }
        return sb.toString();
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
    RemoteAgentSchemaBuilder getSchemaBuilder() { return schemaBuilder; }
    List<AgentRef> getRemoteAgents() { return remoteAgents; }
    int getDescriptionSkillLimit() { return descriptionSkillLimit; }
}