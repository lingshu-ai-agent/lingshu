package ai.lingshu.a2a.client;

import ai.lingshu.core.message.ToolSpec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Story #009d — startup-time schema generator that scans a list of
 * A2A agent-card maps and produces a {@link List}{@code <ToolSpec>} — one
 * per (agent, skill) pair.
 *
 * <p><b>Purpose</b> (dsh §5.6.3.0 L2729-2833): callers that want to surface
 * remote-agent skills as discrete LLM-callable tools (rather than going through
 * the single {@code remote_agent} tool with structured input) call this helper
 * once at startup, then feed the {@code ToolSpec} list into the
 * {@code PromptBuilder} (OQ-Future). Today, this builder's primary consumer is
 * {@link RemoteAgentTool#description()} which uses the wiring as a feature flag.</p>
 *
 * <p><b>Contract</b>:
 * <ul>
 *   <li>Inputs are {@code List<Map<String, Object>>} (the same shape
 *       {@link AgentCardCache} hands out) — using the untyped map form keeps
 *       this class free of any cycle with the server-side {@code AgentSkill}
 *       type, which currently does not carry {@code inputSchema} anyway.</li>
 *   <li>Output is sorted by {@code ToolSpec.name} ascending (stable prompt
 *       cache hits per §4.5.1) and wrapped in
 *       {@link Collections#unmodifiableList}.</li>
 *   <li>Empty / null inputs yield {@link Collections#emptyList()} — never
 *       throw NPE upstream.</li>
 *   <li>Cards with missing {@code name}, non-{@code List} {@code skills}, or
 *       skills with missing {@code id} are silently skipped (with WARN log)
 *       — bad data is logged but not propagated as exceptions.</li>
 *   <li>Per-skill {@code inputSchema} is preferred when present and parseable;
 *       otherwise a permissive {@code {"type":"object","additionalProperties":true}}
 *       fallback is emitted (matches current {@code AgentSkill} shape — no
 *       input-schema field today, OQ-2 in spec.md §9).</li>
 * </ul>
 *
 * <p><b>Thread-safety</b>: this class is annotated {@code @Component} but
 * holds no mutable state — every method is a pure function over its inputs.
 * Spring singleton-scope is safe; the {@code ObjectMapper} it accepts is the
 * shared, thread-safe Jackson instance.</p>
 *
 * @see RemoteAgentTool#description()
 * @see #describeSpecs(List)
 */
@Component
public class RemoteAgentSchemaBuilder {

    private static final Logger log = LoggerFactory.getLogger(RemoteAgentSchemaBuilder.class);

    /** Truncation length for {@link #describeSpecs(List)}. */
    private static final int DESC_PREVIEW_MAX = 80;

    private final ObjectMapper json;

    /**
     * @param json Jackson {@link ObjectMapper} (must not be null — used to
     *             parse per-skill {@code inputSchema} fields and to build the
     *             fallback schema).
     * @throws IllegalArgumentException if {@code json} is null.
     */
    public RemoteAgentSchemaBuilder(ObjectMapper json) {
        if (json == null) {
            throw new IllegalArgumentException("json must not be null");
        }
        this.json = json;
    }

    /**
     * Build the {@link ToolSpec} list from a batch of agent-card maps.
     *
     * <p>Each card is expected to look like
     * <pre>{@code
     * {
     *   "name": "<agentName>",
     *   "description": "<agent description>",
     *   "skills": [
     *     { "id": "<skillId>", "description": "<skill desc>", "inputSchema": { ... } },
     *     ...
     *   ]
     * }
     * }</pre>
     *
     * <p>Malformed entries (missing {@code name}, non-list {@code skills},
     * skill without {@code id}, etc.) are dropped with a WARN log rather
     * than propagated. Cards that throw while being parsed are wrapped in
     * try/catch and skipped — never crash startup.
     *
     * @param cards list of agent-card maps (may be null or empty).
     * @return immutable, name-sorted {@link ToolSpec} list; empty list when
     *         input is null/empty or every entry was invalid.
     */
    public List<ToolSpec> buildToolSpecs(List<Map<String, Object>> cards) {
        if (cards == null || cards.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolSpec> out = new ArrayList<ToolSpec>();
        for (Map<String, Object> card : cards) {
            try {
                appendSpecsForCard(card, out);
            } catch (RuntimeException e) {
                log.warn("[RemoteAgentSchemaBuilder] skipping card due to parse error: {}",
                    e.getMessage());
            }
        }
        Collections.sort(out, new Comparator<ToolSpec>() {
            @Override
            public int compare(ToolSpec a, ToolSpec b) {
                return a.getName().compareTo(b.getName());
            }
        });
        return Collections.unmodifiableList(out);
    }

    /**
     * Format a {@link ToolSpec} list as a human-readable debug string.
     *
     * <p>Layout:
     * <pre>
     * [N tools]
     *   - &lt;name1&gt;: &lt;desc80&gt;
     *   - &lt;name2&gt;: &lt;desc80&gt;
     *   ...
     * </pre>
     *
     * @param specs specs to render (may be null or empty).
     * @return {@code "(empty)"} for null/empty input, otherwise the
     *         formatted multi-line string.
     */
    public String describeSpecs(List<ToolSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            return "(empty)";
        }
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(specs.size()).append(" tools]\n");
        for (ToolSpec spec : specs) {
            sb.append("  - ")
              .append(spec.getName())
              .append(": ")
              .append(truncate(spec.getDescription(), DESC_PREVIEW_MAX))
              .append('\n');
        }
        return sb.toString();
    }

    // ─── private helpers ──────────────────────────────────────────────────

    private void appendSpecsForCard(Map<String, Object> card, List<ToolSpec> sink) {
        if (card == null) {
            return;
        }
        String agentName = asString(card.get("name"));
        if (agentName == null || agentName.isEmpty()) {
            log.warn("[RemoteAgentSchemaBuilder] skipping card: missing 'name'");
            return;
        }
        String agentDesc = asString(card.get("description"));
        Object skillsRaw = card.get("skills");
        if (!(skillsRaw instanceof List)) {
            log.warn("[RemoteAgentSchemaBuilder] skipping card '{}': 'skills' is not a list",
                agentName);
            return;
        }
        List<?> skills = (List<?>) skillsRaw;
        for (Object skillRaw : skills) {
            if (!(skillRaw instanceof Map)) {
                continue;
            }
            Map<String, Object> skill = (Map<String, Object>) skillRaw;
            String skillId = asString(skill.get("id"));
            if (skillId == null || skillId.isEmpty()) {
                log.warn(
                    "[RemoteAgentSchemaBuilder] skipping skill under agent '{}': missing 'id'",
                    agentName);
                continue;
            }
            String skillDesc = asString(skill.get("description"));
            String name = "call_" + agentName + "_" + skillId;
            String description = formatDescription(skillDesc, agentName, agentDesc);
            JsonNode inputSchema = resolveInputSchema(skill);
            sink.add(new ToolSpec(name, description, inputSchema));
        }
    }

    /**
     * Type-safe string coercion that returns {@code null} for any non-String
     * value. Keeps callers from accidentally calling {@code toString()} on a
     * nested map or list (which would produce useless output like
     * {@code "{key=value}"}).
     */
    private static String asString(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof String) {
            return (String) o;
        }
        return null;
    }

    /**
     * Truncate {@code s} to at most {@code max} characters, suffixing with
     * {@code "..."} when truncation happens. Returns the empty string for
     * null input.
     */
    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        if (max <= 3) {
            // pathologically small — just return the leading dots
            return "...".substring(0, Math.max(0, max));
        }
        return s.substring(0, max - 3) + "...";
    }

    /**
     * Compose the per-tool description:
     * {@code "<skillDesc> (via <agentName>: <agentDesc>)"}.
     * Empty skill/agent descriptions are replaced with {@code "[no description]"}.
     */
    private static String formatDescription(String skillDesc, String agentName, String agentDesc) {
        String s = (skillDesc == null || skillDesc.isEmpty()) ? "[no description]" : skillDesc;
        String a = (agentDesc == null || agentDesc.isEmpty()) ? "[no description]" : agentDesc;
        return s + " (via " + agentName + ": " + a + ")";
    }

    /**
     * Resolve a skill's {@code inputSchema}: prefer the field on the skill
     * map when present and convertible to {@link JsonNode}; otherwise emit a
     * permissive fallback that accepts any object. The fallback matches
     * today's {@code AgentSkill} shape — which does not carry
     * {@code inputSchema} — and gives the LLM enough room to call the tool
     * even when the schema is unknown.
     */
    private JsonNode resolveInputSchema(Map<String, Object> skill) {
        Object raw = skill.get("inputSchema");
        if (raw instanceof JsonNode) {
            return (JsonNode) raw;
        }
        if (raw instanceof Map) {
            try {
                return json.convertValue(raw, JsonNode.class);
            } catch (RuntimeException e) {
                log.warn(
                    "[RemoteAgentSchemaBuilder] skill inputSchema not parseable, "
                        + "falling back to permissive schema: {}",
                    e.getMessage());
            }
        }
        ObjectNode fallback = json.createObjectNode();
        fallback.put("type", "object");
        fallback.put("additionalProperties", true);
        return fallback;
    }
}