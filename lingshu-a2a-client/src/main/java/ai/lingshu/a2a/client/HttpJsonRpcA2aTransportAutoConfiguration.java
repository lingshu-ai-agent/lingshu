package ai.lingshu.a2a.client;

import ai.lingshu.core.impl.router.A2aTransportRouter;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.slot.A2aTransport;
import ai.lingshu.core.spi.Providers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Collections;
import java.util.List;

/**
 * Story #009c + #009d — SPI registration for {@link HttpJsonRpcA2aTransportProvider}
 * <b>and</b> the {@link RemoteAgentTool} bean (single AutoConfiguration
 * exposing 3 beans — keeps core-new-file count within CLAUDE.md §11 #4 ≤ 5).
 *
 * <p>Three beans:</p>
 * <ol>
 *     <li>{@code a2aTransportProvider_http-jsonrpc-1.0.0} — the A2aTransport
 *         provider, registered as Spring Boot SPI via
 *         {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 *         (third line, after #009a grpc + #009b in-process).</li>
 *     <li>{@code remoteAgentSchemaBuilder} — Story #009d, the pure-function
 *         {@link RemoteAgentSchemaBuilder} used by {@link RemoteAgentTool}
 *         to mark its {@code description()} as schemaBuilder-wired.</li>
 *     <li>{@code remoteAgentTool} — a {@code Tool} bean implementing the
 *         {@code remote_agent} callable. {@code Tool} is <b>not</b> one of the
 *         9 Slot SPI types (ToolRegistry auto-collects all {@code Tool} beans),
 *         so it goes through plain {@code @Bean} registration — not through
 *         {@code Providers.XxxProvider} SPI.</li>
 * </ol>
 *
 * <p>The {@code remoteAgentTool} bean resolves its {@link A2aTransport} via
 * {@link A2aTransportRouter} (named by {@code cfg.getA2aTransport()}), so
 * switching transport in {@code application.yml} transparently swaps the
 * underlying transport — <b>no need to change Spring config</b>.</p>
 */
@AutoConfiguration
public class HttpJsonRpcA2aTransportAutoConfiguration {

    /**
     * Registers {@link HttpJsonRpcA2aTransportProvider} under the convention
     * {@code "a2aTransportProvider_<name>"} so it coexists with
     * {@code "a2aTransportProvider_grpc-1.0.0"} (from #009a) and
     * {@code "a2aTransportProvider_in-process-1.0.0"} (from #009b).
     */
    @Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")
    public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider() {
        return new HttpJsonRpcA2aTransportProvider();
    }

    /**
     * Story #009d — exposes the {@link RemoteAgentSchemaBuilder} as a Spring
     * bean so {@link RemoteAgentTool} can be wired with it (and so the
     * schemaBuilder can be autowired into future {@code PromptBuilder}
     * integrations / startup-log dumps — OQ-5 / OQ-6 in spec.md §9).
     */
    @Bean(name = "remoteAgentSchemaBuilder")
    public RemoteAgentSchemaBuilder remoteAgentSchemaBuilder(ObjectMapper json) {
        return new RemoteAgentSchemaBuilder(json);
    }

    /**
     * The {@code remote_agent} Tool bean — when {@code application.yml} has
     * {@code agent.a2aTransport: http-jsonrpc-1.0.0}, the Tool dispatches
     * via {@link HttpJsonRpcA2aTransport}; with {@code grpc-1.0.0} or
     * {@code in-process-1.0.0}, it dispatches via the selected transport.
     *
     * <p>Story #009d — the 5-arg variant wires the
     * {@link RemoteAgentSchemaBuilder} bean <b>and</b> pulls
     * {@code remoteAgents} + {@code descriptionSkillLimit} from
     * {@code cfg.getA2a()} so the {@code description()} can enumerate the
     * configured agents' skills (AC-2.2 + EC-9). When no remote agents are
     * configured, the hint string is appended instead.</p>
     */
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
}