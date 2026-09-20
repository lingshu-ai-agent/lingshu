package ai.lingshu.core.impl.memory;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.MemorySource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits a JSON snapshot of {@code cfg.identity} into the {@code [PROJECT MEMORY]}
 * segment — a parallel pathway to the structural {@code [ROLE]} segment so the LLM
 * can see Identity in both human-readable (structured ROLE lines) and
 * machine-readable (JSON) forms. See contracts/memory-source.md §3.3.
 *
 * <p>Always returns non-null: if {@code cfg.identity} is somehow null we fall back to
 * {@link AgentConfig.Identity#defaults()}, then to {@code toString()}.
 */
public class IdentityMemorySource implements MemorySource {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityMemorySource.class);

    /** Shared ObjectMapper — Jackson is on classpath via Spring Boot BOM (constitution §2 #5). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentConfig config;

    public IdentityMemorySource(AgentConfig config) {
        this.config = config;
    }

    @Override public String name() { return "identity"; }
    @Override public int priority() { return 30; }

    @Override
    public String load(TurnContext ctx) {
        AgentConfig.Identity id = config.getIdentity();
        if (id == null) {
            id = AgentConfig.Identity.defaults();
        }
        try {
            return MAPPER.writeValueAsString(id);
        } catch (JsonProcessingException ex) {
            LOG.warn("IdentityMemorySource: JSON serialization failed, falling back to toString", ex);
            return id.toString();
        }
    }
}
