package ai.lingshu.a2a.server;

import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 🆕 Story #009 — local AgentCard generator (dsh §5.6.8).
 *
 * <p>Pure function: takes an immutable {@link AgentConfig} and renders an
 * {@link AgentCard} JSON DTO. Used by {@link A2aServer#handleAgentCard} to
 * serve {@code GET /.well-known/agent.json}.
 *
 * <p>Validation is performed eagerly so a typo in {@code application.yml}
 * surfaces as a {@link LingsA2aServerException} with code {@code LINGS-T02}
 * at startup time, not as a confused 500 at request time.
 *
 * <p>This class is intentionally <b>not</b> a Spring bean — it is a stateless
 * helper with a single static method, no I/O, no Spring references. Marking it
 * {@code @Component} would only add a needless entry to the bean factory index.
 */
public final class LocalAgentCardGenerator {

    /** Hardcoded server version for this Story (FR-013). */
    static final String SERVER_VERSION = "0.1.0";

    /** Default identity name when {@code Identity} is null (Story #001 AC-01-2). */
    static final String DEFAULT_IDENTITY_NAME = "lingShu-agent";

    private LocalAgentCardGenerator() {
        // utility class — no instances
    }

    /**
     * Build an {@link AgentCard} from the given config (FR-001 / FR-002 / FR-005 / FR-013).
     *
     * <p>Single-arg overload — delegates to {@link #generate(AgentConfig, ToolRegistry)} with
     * a {@code null} registry. Resulting card has empty {@code skills[]}.
     *
     * @param cfg the per-turn config (immutable)
     * @return a fresh, valid {@link AgentCard}
     * @throws LingsA2aServerException with code {@code "LINGS-T02"} when
     *         {@code cfg.getIdentity().getName()} is null / blank / whitespace-only
     */
    public static AgentCard generate(AgentConfig cfg) {
        return generate(cfg, null);
    }

    /**
     * 🆕 Story a2a-server-tool-registry-dispatch — Build an {@link AgentCard} whose
     * {@code skills[]} list is scanned from {@link ToolRegistry#modelVisibleSpecs()}.
     *
     * <p>Mirrors the client-side {@code RemoteAgentSchemaBuilder.buildToolSpecs}
     * (Story #009d), but on the <i>server</i> side: each registered {@link ToolSpec}
     * becomes one {@link AgentCard.AgentSkill} entry.
     *
     * <ul>
     *   <li>{@code id} / {@code name} ← {@code ToolSpec.name} (unique tool id)</li>
     *   <li>{@code description} ← {@code ToolSpec.description}</li>
     *   <li>{@code tags} ← prefix split on {@code ':'} when name is namespaced
     *       (e.g. {@code "github:search_repos"} → {@code ["github"]}); empty otherwise</li>
     *   <li>{@code inputModes} / {@code outputModes} ← {@code ["text"]} (A2A v1.0 default)</li>
     *   <li>{@code examples} ← {@code []} (filled by tool author via {@code inputSchema()})</li>
     * </ul>
     *
     * <p>When {@code toolRegistry} is {@code null} or its {@code modelVisibleSpecs()} returns
     * an empty list, the card has empty {@code skills[]} — same behavior as the legacy
     * single-arg {@link #generate(AgentConfig)} overload.
     *
     * @param cfg          the per-turn config (immutable)
     * @param toolRegistry the local tool registry (nullable)
     * @return a fresh, valid {@link AgentCard} with skills[] sourced from the registry
     * @throws LingsA2aServerException with code {@code "LINGS-T02"} when
     *         {@code cfg.getIdentity().getName()} is null / blank / whitespace-only
     */
    public static AgentCard generate(AgentConfig cfg, ToolRegistry toolRegistry) {
        if (cfg == null) {
            throw new LingsA2aServerException(
                "LINGS-T02",
                "AgentConfig is null",
                "ensure application.yml is loaded before A2aServer.start()");
        }

        AgentConfig.Identity id = cfg.getIdentity();
        String name = id == null || id.getName() == null || id.getName().trim().isEmpty()
            ? DEFAULT_IDENTITY_NAME
            : id.getName().trim();

        // LINGS-T02: explicit failure for explicitly-empty config so yml typo is loud.
        if (id != null && (id.getName() == null || id.getName().trim().isEmpty())) {
            throw new LingsA2aServerException(
                "LINGS-T02",
                "AgentConfig.identity.name must not be blank",
                "set agent.identity.name in application.yml or use Identity.defaults()");
        }

        String description = id == null ? null : id.getRole();

        List<AgentCard.AgentSkill> skills = buildSkills(toolRegistry);

        return new AgentCard(
            name,
            description,
            SERVER_VERSION,
            skills,
            AgentCard.AgentCapabilities.empty(),
            Collections.singletonList("text"),
            Collections.singletonList("text"),
            null,    // securitySchemes (Story #009b)
            null,    // security (Story #009b)
            null,    // provider (Story #009b)
            null,    // documentationUrl (Story #009b)
            null);   // iconUrl (Story #009b)
    }

    /**
     * Scan {@link ToolRegistry#modelVisibleSpecs()} and convert each {@link ToolSpec}
     * to an {@link AgentCard.AgentSkill}. Returns an empty list when the registry is
     * null or has no visible tools.
     *
     * <p>Stable order preserved — {@link ToolRegistry#modelVisibleSpecs()} already
     * returns a stable-sorted list per its SPI contract.
     */
    private static List<AgentCard.AgentSkill> buildSkills(ToolRegistry toolRegistry) {
        if (toolRegistry == null) {
            return Collections.<AgentCard.AgentSkill>emptyList();
        }
        List<ToolSpec> specs = toolRegistry.modelVisibleSpecs();
        if (specs == null || specs.isEmpty()) {
            return Collections.<AgentCard.AgentSkill>emptyList();
        }
        List<AgentCard.AgentSkill> out = new ArrayList<AgentCard.AgentSkill>(specs.size());
        for (ToolSpec spec : specs) {
            if (spec == null || spec.getName() == null || spec.getName().isEmpty()) {
                continue;
            }
            List<String> tags = extractTags(spec.getName());
            out.add(new AgentCard.AgentSkill(
                spec.getName(),     // id
                spec.getName(),     // name
                spec.getDescription() == null ? "" : spec.getDescription(),
                tags,
                Collections.<String>emptyList(),  // examples
                Collections.singletonList("text"),
                Collections.singletonList("text")));
        }
        return out;
    }

    /**
     * Derive {@code tags} from a tool name's namespace prefix when present.
     * {@code "github:search_repos"} → {@code ["github"]};
     * {@code "read_file"} (no prefix) → {@code []}.
     */
    private static List<String> extractTags(String toolName) {
        if (toolName == null) {
            return Collections.<String>emptyList();
        }
        int colon = toolName.indexOf(':');
        if (colon <= 0 || colon >= toolName.length() - 1) {
            return Collections.<String>emptyList();
        }
        return Collections.singletonList(toolName.substring(0, colon));
    }

    /**
     * Serialize an {@link AgentCard} to JSON (FR-006 / FR-007).
     *
     * <p>The mapper is created fresh per call (cheap, Jackson is thread-safe to
     * <i>use</i> but configuration mutations on a shared instance are not).
     *
     * @return JSON string with {@code @JsonPropertyOrder} applied
     * @throws JsonProcessingException on Jackson failure (should not happen for this schema)
     */
    public static String toJson(AgentCard card) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(card);
    }

    /**
     * Flatten an {@link AgentCard} into an immutable {@code Map<String, Object>}
     * (Story #009b, dsh §5.6.3.2 L3241-3243).
     *
     * <p>The returned Map is wrapped with {@link Collections#unmodifiableMap}
     * so callers cannot mutate registry state after {@code put}. The underlying
     * {@link LinkedHashMap} preserves field declaration order, mirroring the
     * {@link com.fasterxml.jackson.annotation.JsonPropertyOrder} layout of
     * {@link AgentCard}.</p>
     *
     * <p>Used by {@link A2aServer#registerInProcess()} to register this server's
     * card into the {@code InProcessA2aRegistry} singleton at {@code start()} time,
     * and by peer agents' {@code InProcessA2aTransport.fetchCard()} (which calls
     * {@code registry.get(agentName)} to retrieve the card Map).</p>
     *
     * @param card the AgentCard to flatten; must be non-null
     * @return immutable {@code Map<String, Object>} with 12 fields in declaration order
     * @throws IllegalArgumentException if {@code card} is null
     */
    public static Map<String, Object> toMap(AgentCard card) {
        if (card == null) {
            throw new IllegalArgumentException("card must not be null");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", card.getName());
        map.put("description", card.getDescription());
        map.put("version", card.getVersion());
        map.put("skills", card.getSkills());
        map.put("capabilities", card.getCapabilities());
        map.put("defaultInputModes", card.getDefaultInputModes());
        map.put("defaultOutputModes", card.getDefaultOutputModes());
        map.put("securitySchemes", card.getSecuritySchemes());
        map.put("security", card.getSecurity());
        map.put("provider", card.getProvider());
        map.put("documentationUrl", card.getDocumentationUrl());
        map.put("iconUrl", card.getIconUrl());
        return Collections.unmodifiableMap(map);
    }
}