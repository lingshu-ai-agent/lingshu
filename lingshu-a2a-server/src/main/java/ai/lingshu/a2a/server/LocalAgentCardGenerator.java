package ai.lingshu.a2a.server;

import ai.lingshu.core.runtime.AgentConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;

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
     * @param cfg the per-turn config (immutable)
     * @return a fresh, valid {@link AgentCard}
     * @throws LingsA2aServerException with code {@code "LINGS-T02"} when
     *         {@code cfg.getIdentity().getName()} is null / blank / whitespace-only
     */
    public static AgentCard generate(AgentConfig cfg) {
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

        return new AgentCard(
            name,
            description,
            SERVER_VERSION,
            Collections.<AgentCard.AgentSkill>emptyList(),
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
}