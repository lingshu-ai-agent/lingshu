package ai.lingshu.a2a.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * 🆕 Story #009 — A2A AgentCard (server side, dsh §5.6.3.0).
 *
 * <p>Immutable JSON DTO rendered by {@code LocalAgentCardGenerator.generate(cfg)}
 * and served at {@code GET /.well-known/agent.json} per A2A v1.0 spec §2.1.
 *
 * <p>Field set is intentionally minimal for this Story (AC-10 only) — see
 * <a href="../../../../../../../../specs/009-a2a-agent-card/data-model.md">data-model.md</a>
 * for the evolution path:
 * <ul>
 *   <li>Story #009b — {@code provider} / {@code documentationUrl} / {@code iconUrl} /
 *       {@code securitySchemes} (Bearer auth) / {@code security} / non-empty {@code skills}
 *       (scanned via {@code RemoteAgentSchemaBuilder}).</li>
 *   <li>v1.5+ — {@code streaming} / {@code pushNotifications} flags once SSE / webhooks land.</li>
 * </ul>
 *
 * <p>Serialization rules (FR-002 + contracts/agent-card-http-api.md §2.4):
 * <ul>
 *   <li>{@code @JsonInclude(ALWAYS)} → null fields emit {@code "field": null} (not omitted).</li>
 *   <li>Empty lists emit {@code []}, never {@code null}.</li>
 *   <li>Field order matches the JSON contract view; {@code @JsonPropertyOrder} enforces
 *       it under {@code ObjectMapper} serialization.</li>
 * </ul>
 */
@Value
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({
    "name", "description", "version", "skills", "capabilities",
    "defaultInputModes", "defaultOutputModes",
    "securitySchemes", "security", "provider", "documentationUrl", "iconUrl"
})
public class AgentCard {

    /** Agent display name — sourced from {@code cfg.getIdentity().getName()} (FR-001). */
    String name;
    /** Free-form description — sourced from {@code cfg.getIdentity().getRole()} (FR-002). */
    String description;
    /** Server-side version; hardcoded {@code "0.1.0"} for this Story. */
    String version;
    /** Skills offered; empty for this Story (Story #009c will scan {@code ToolRegistry}). */
    List<AgentSkill> skills;
    /** Capability flags; {@link AgentCapabilities#empty()} for this Story. */
    AgentCapabilities capabilities;
    /** Supported input modes; {@code ["text"]} per A2A v1.0 default. */
    List<String> defaultInputModes;
    /** Supported output modes; {@code ["text"]} per A2A v1.0 default. */
    List<String> defaultOutputModes;
    /** Security schemes (Bearer auth); null for this Story (deferred to #009b). */
    Object securitySchemes;
    /** Required security; null for this Story (deferred to #009b). */
    Object security;
    /** Provider info; null for this Story (deferred to #009b). */
    AgentProvider provider;
    /** Documentation URL; null for this Story (deferred to #009b). */
    String documentationUrl;
    /** Icon URL; null for this Story (deferred to #009b). */
    String iconUrl;

    /**
     * Zero-config factory (Story #001 AC-01-2 "empty yml must boot" → AgentCard still
     * renders something sensible). Used when a test wants a baseline card without
     * constructing all fields.
     */
    public static AgentCard defaults() {
        return new AgentCard(
            "lingShu-agent",
            null,
            "0.1.0",
            Collections.<AgentSkill>emptyList(),
            AgentCapabilities.empty(),
            Collections.singletonList("text"),
            Collections.singletonList("text"),
            null, null, null, null, null);
    }

    // ── Nested types (dsh §5.6.3.0) ───────────────────────────────────────

    /**
     * Single advertised skill — A2A v1.0 §2.1 {@code skills[]} entry. Story #009c will
     * populate this list from the local {@code ToolRegistry}.
     */
    @Value
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"id", "name", "description", "tags", "examples", "inputModes", "outputModes"})
    public static class AgentSkill {
        String id;
        String name;
        String description;
        List<String> tags;
        List<String> examples;
        List<String> inputModes;
        List<String> outputModes;

        public static AgentSkill empty() {
            return new AgentSkill(null, null, null,
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList(),
                Collections.<String>emptyList());
        }
    }

    /**
     * Capability flags (A2A v1.0 §2.1 {@code capabilities} block). Story #009 leaves
     * all three flags {@code false}; v1.5+ enables streaming + pushNotifications
     * once SSE / webhooks are added.
     */
    @Value
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"streaming", "pushNotifications", "stateTransitionHistory"})
    public static class AgentCapabilities {
        boolean streaming;
        boolean pushNotifications;
        boolean stateTransitionHistory;

        public static AgentCapabilities empty() {
            return new AgentCapabilities(false, false, false);
        }
    }

    /**
     * Provider metadata — A2A v1.0 §2.1 {@code provider} block. Story #009 leaves
     * this null; Story #009b will source from {@code a2a.server.provider.*}.
     */
    @Value
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonPropertyOrder({"organization", "url"})
    public static class AgentProvider {
        String organization;
        String url;
    }

    /**
     * Security scheme descriptor — A2A v1.0 §2.1 {@code securitySchemes}. This Story
     * leaves it null; Story #009b adds Bearer via {@code "type": "http", "scheme": "bearer"}.
     *
     * <p>Modeled as {@code Object} (not a typed POJO) because A2A spec lets multiple
     * security scheme types coexist ({@code apiKey}, {@code http}, {@code oauth2}, ...)
     * and we want Jackson to render whatever shape the future Story picks without
     * a breaking change to this class.
     */
    // intentionally not a typed POJO — see Javadoc above
    // placeholder type kept here only to preserve the contract file annotation;
    // actual schema is rendered via Object at the AgentCard.securitySchemes field.
}