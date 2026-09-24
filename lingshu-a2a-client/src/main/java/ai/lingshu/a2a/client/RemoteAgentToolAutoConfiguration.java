package ai.lingshu.a2a.client;

import ai.lingshu.core.impl.router.A2aTransportRouter;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.slot.A2aTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;

/**
 * Story #009e — Independent {@code @AutoConfiguration} for the
 * {@link RemoteAgentTool} + {@link RemoteAgentSchemaBuilder} beans.
 *
 * <p><b>Why split from {@link HttpJsonRpcA2aTransportAutoConfiguration}</b>
 * — Story #009c originally merged both beans into the transport-specific
 * AutoConfiguration (to fit CLAUDE.md §11 #4 ≤ 5 file boundary). However,
 * this meant {@code agent.a2aTransport: grpc-1.0.0} or
 * {@code agent.a2aTransport: in-process-1.0.0} users had NO
 * {@code remote_agent} tool in the LLM's tool list — the wiring gap
 * discovered in #009d testing.</p>
 *
 * <p><b>Three transports now share this single bean</b> — switching
 * {@code agent.a2aTransport} in {@code application.yml} now transparently
 * swaps the underlying transport without losing the {@code remote_agent}
 * tool. Backed by {@link A2aTransportRouter#resolve(String, AgentConfig)}
 * (§5.3.1.2).</p>
 *
 * <p><b>Registration</b> — the {@link RemoteAgentTool} bean is <b>not</b>
 * auto-registered with {@link ai.lingshu.core.slot.ToolRegistry}; explicit
 * registration is the job of {@link RemoteAgentToolLifecycle}, see #009e
 * spec.md §2 US-2 for rationale.</p>
 */
@AutoConfiguration
public class RemoteAgentToolAutoConfiguration {

    @Bean(name = "remoteAgentTool")
    public RemoteAgentTool remoteAgentTool(A2aTransportRouter router,
                                           AgentConfig cfg,
                                           ObjectMapper json,
                                           RemoteAgentSchemaBuilder schemaBuilder) {
        String transportName = cfg != null && cfg.getA2aTransport() != null
            ? cfg.getA2aTransport()
            : "http-jsonrpc-1.0.0";
        A2aTransport transport = router.resolve(transportName, cfg);

        List<AgentRef> remoteAgents = (cfg != null && cfg.getA2a() != null
            && cfg.getA2a().getRemoteAgents() != null)
            ? cfg.getA2a().getRemoteAgents()
            : Collections.<AgentRef>emptyList();
        int skillLimit = (cfg != null && cfg.getA2a() != null
            && cfg.getA2a().getDescriptionSkillLimit() > 0)
            ? cfg.getA2a().getDescriptionSkillLimit()
            : RemoteAgentTool.DEFAULT_DESCRIPTION_SKILL_LIMIT;

        return new RemoteAgentTool(transport, json, schemaBuilder, remoteAgents, skillLimit);
    }

    @Bean(name = "remoteAgentSchemaBuilder")
    public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json) {
        return new RemoteAgentSchemaBuilder(json);
    }
}