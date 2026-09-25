package ai.lingshu.examples.demoproduct;

import ai.lingshu.a2a.server.A2aServerAutoConfiguration;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Story #025 demo — comprehensive HTTP streaming product.
 *
 * <p>Combines 8 features in one Spring Boot web app:
 * <ol>
 *   <li>{@link ChatController} — SSE chat endpoint</li>
 *   <li>{@link SessionRegistry} — multi-turn sessionId → Agent map</li>
 *   <li>{@link AgentEventMapper} — 12 AgentEvent → SSE event names</li>
 *   <li>Local {@code @Component} Tools (read/write/list/bash_safe)</li>
 *   <li>{@code @AgentTool} methods (time/calc/random/uuid)</li>
 *   <li>Skill markdown sources (/help /clear /compact)</li>
 *   <li>Delegate sub-agent config (explore / engineer / reviewer)</li>
 *   <li>Compactor token threshold + browser event panel</li>
 * </ol>
 *
 * <p>Unlike {@code demo-empty} which uses {@code CommandLineRunner} and exits
 * before the web layer starts, this demo keeps the embedded Tomcat running so
 * the browser UI at {@code http://localhost:8080} is interactive.
 */
@SpringBootApplication(exclude = {
    // 🆕 Story #025b — exclude the stock A2aServer. demo-product is the CLIENT
    // calling out to demo-product-a2a-server (port 9090). lingshu-a2a-server
    // is on the classpath transitively via lingshu-a2a-client, and its
    // A2aServerAutoConfiguration would try to bind on the default A2aServer
    // port (8080), conflicting with Tomcat. Excluding it leaves the A2A
    // client wiring (RemoteAgentTool + HttpJsonRpcA2aTransport) intact.
    A2aServerAutoConfiguration.class
})
public class DemoProductApplication {

    private static final Logger LOG = LoggerFactory.getLogger(DemoProductApplication.class);

    /**
     * Expose {@link AgentConfig} as a Spring bean so that
     * {@code YamlTenantConfigProvider} (Story #006) + {@code McpTransportAutoConfiguration}
     * (Story #021b) can inject it.
     *
     * <p><b>Why we reconstruct the full 24-field AgentConfig here</b> —
     * {@link AgentConfig} is Lombok {@code @Value} (immutable, no setters),
     * so Spring Boot's Binder cannot bind {@code agent.*} directly. The default
     * factory returns {@code mcp = null} and {@code a2a.remoteAgents = []}, which means
     * {@code McpTransportAutoConfiguration} sees an empty server list and {@code McpTransport}
     * logs "idle" forever, and {@code RemoteAgentTool} advertises zero skills.
     *
     * <p>This {@code @Bean} reads {@code agent.mcp.servers} (via {@link McpServerProperties})
     * and {@code agent.a2a.remote-agents} (inline — only 3 fields per entry) from
     * {@link Environment}, then constructs an {@link AgentConfig} with the parsed entries
     * and re-assembles every other field from {@link AgentConfigDefaults#defaults()}.
     *
     * <p>{@code ChatController} does <b>not</b> use this bean — each session
     * builds its own config via {@link AgentConfigDefaults#defaults()}. (To
     * make per-session configs MCP-aware too, we'd swap that for
     * {@code agentConfig(Environment)} as well — out of scope for Story #025.)
     */
    @Bean
    public AgentConfig agentConfig(Environment env) {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        McpServerProperties mcpProps = McpServerProperties.bindFromEnvironment(env);

        // Walk agent.a2a.remote-agents[N] by hand — same pattern as McpServerProperties.
        List<AgentRef> remoteAgents = readRemoteAgents(env);

        // 🆕 Story #025b — read A2aTransport name from YAML.
        // AgentConfigDefaults returns a2aTransport="default" but the
        // A2aTransportRouter providers are registered under versioned names
        // like "http-jsonrpc-1.0.0" / "grpc-1.0.0" / "in-process-1.0.0".
        // RemoteAgentToolAutoConfiguration resolves by name, so without
        // this override the bean fails to instantiate with
        // "Unknown A2aTransportRouter 'default'".
        String a2aTransportName = env.getProperty("agent.a2a.transport",
            "http-jsonrpc-1.0.0");

        // 🆕 Story #025b — read A2aTransport httpBaseUrl from YAML.
        // HttpJsonRpcA2aTransport uses a SINGLE transport-wide base URL
        // (one Transport Bean, one URL — all remoteAgent[] entries share
        // the same base). The default in AgentConfigDefaults is
        // http://localhost:8080 (demo-product's own Tomcat), but for the
        // Story #025b demo the real A2A server lives on port 9090. Without
        // this override RemoteAgentTool.fetchCard() hits demo-product's
        // own /.well-known/agent.json and gets 404.
        String a2aHttpBaseUrl = env.getProperty("agent.a2a.http-base-url",
            defaults.getA2a() != null ? defaults.getA2a().getHttpBaseUrl()
                                      : "http://localhost:8080");

        // Build the AgentConfig.A2a with the remote agents list.
        AgentConfig.A2a a2a = (defaults.getA2a() != null)
            ? new AgentConfig.A2a(
                defaults.getA2a().getHost(),
                defaults.getA2a().getPort(),
                defaults.getA2a().getGrpcTarget(),
                defaults.getA2a().getCardTtl(),
                a2aHttpBaseUrl,                                    // 🆕 Story #025b
                defaults.getA2a().getCallTimeout(),
                remoteAgents,                                       // 🆕 Story #025b
                defaults.getA2a().getDescriptionSkillLimit())
            : new AgentConfig.A2a(
                "0.0.0.0", 8080,
                "localhost:50051", java.time.Duration.ofMinutes(5),
                a2aHttpBaseUrl, java.time.Duration.ofSeconds(30),  // 🆕 Story #025b
                remoteAgents, 10);

        if (mcpProps.getServers().isEmpty()) {
            LOG.info("agentConfig: no MCP servers in YAML, using defaults (McpTransport will idle)");
            // Even with no MCP servers, still propagate remoteAgents.
            return mergeConfig(defaults, null, a2a, remoteAgents, a2aTransportName);
        }

        AgentConfig.Mcp mcp = new AgentConfig.Mcp(mcpProps.toAgentConfigServerConfigs());
        LOG.info("agentConfig: MCP servers bound from YAML — {} server(s): {}",
            mcp.getServers().size(), summarizeMcpNames(mcp));
        LOG.info("agentConfig: A2aTransport name from YAML — {}", a2aTransportName);
        return mergeConfig(defaults, mcp, a2a, remoteAgents, a2aTransportName);
    }

    /**
     * Walk {@code agent.a2a.remote-agents[N].{name,url,priority}} until the
     * first index where {@code .name} is missing. Mirrors the
     * {@link McpServerProperties#bindFromEnvironment} pattern.
     *
     * <p>Why hand-written rather than @ConfigurationProperties? — see the
     * class Javadoc on {@link McpServerProperties} (AgentConfig immutability).
     */
    private static List<AgentRef> readRemoteAgents(Environment env) {
        List<AgentRef> out = new ArrayList<>();
        for (int i = 0; ; i++) {
            String prefix = "agent.a2a.remote-agents[" + i + "]";
            String name = env.getProperty(prefix + ".name");
            if (name == null) {
                break;
            }
            String url = env.getProperty(prefix + ".url");
            int priority = env.getProperty(prefix + ".priority", Integer.class, 0);
            out.add(new AgentRef(name, url, priority));
        }
        if (!out.isEmpty()) {
            LOG.info("agentConfig: remote A2A agents bound from YAML — {} agent(s): {}",
                out.size(), summarizeAgentRefs(out));
        } else {
            LOG.info("agentConfig: no remote A2A agents in YAML (RemoteAgentTool will advertise 0 skills)");
        }
        return out;
    }

    private static String summarizeAgentRefs(List<AgentRef> refs) {
        List<String> names = new ArrayList<>(refs.size());
        for (AgentRef r : refs) {
            names.add(r.getName() + "(" + r.getUrl() + ")");
        }
        return names.toString();
    }

    /**
     * Assemble the full 24-field {@link AgentConfig}, overriding only the
     * fields that need values from YAML. 21 fields stay untouched; only
     * {@code mcp} (Story #025), {@code a2a.remoteAgents} (Story #025b), and
     * {@code a2aTransport} (Story #025b) come from the environment.
     */
    private static AgentConfig mergeConfig(AgentConfig defaults, AgentConfig.Mcp mcp,
                                           AgentConfig.A2a a2a, List<AgentRef> remoteAgents,
                                           String a2aTransportName) {
        return new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            mcp,                                       // may be null
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            defaults.getReactMaxSteps(),
            defaults.getIdentity(),
            defaults.getInstructions(),
            defaults.getMemory(),
            a2aTransportName,                          // 🆕 Story #025b — populated from YAML
            defaults.getTenants(),
            a2a,                                       // 🆕 Story #025b — populated from YAML
            defaults.getCompactorConfig(),
            defaults.getTools()
        );
    }

    private static String summarizeMcpNames(AgentConfig.Mcp mcp) {
        if (mcp == null) {
            return Collections.<String>emptyList().toString();
        }
        List<String> names = new ArrayList<>(mcp.getServers().size());
        for (AgentConfig.ServerConfig sc : mcp.getServers()) {
            names.add(sc.getName() + "(" + sc.getTransport() + ")");
        }
        return names.toString();
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoProductApplication.class, args);
        LOG.info("demo-product ready — open http://localhost:8080");
    }
}