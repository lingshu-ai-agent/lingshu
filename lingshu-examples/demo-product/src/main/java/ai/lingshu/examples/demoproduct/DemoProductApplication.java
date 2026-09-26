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

import java.nio.file.Path;
import java.nio.file.Paths;
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

        // 🆕 Story #025 follow-up — read agent.sandbox.* (5-field Slot 3 schema,
        // dsh §5623-5627). Mirrors readRemoteAgents pattern.
        AgentConfig.Sandbox sandbox = readSandbox(env);

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
            // Even with no MCP servers, still propagate remoteAgents + sandbox.
            return mergeConfig(defaults, null, a2a, remoteAgents, a2aTransportName, sandbox);
        }

        AgentConfig.Mcp mcp = new AgentConfig.Mcp(mcpProps.toAgentConfigServerConfigs());
        LOG.info("agentConfig: MCP servers bound from YAML — {} server(s): {}",
            mcp.getServers().size(), summarizeMcpNames(mcp));
        LOG.info("agentConfig: A2aTransport name from YAML — {}", a2aTransportName);
        return mergeConfig(defaults, mcp, a2a, remoteAgents, a2aTransportName, sandbox);
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
     * 🆕 Story #025 follow-up — read {@code agent.sandbox.*} properties from
     * {@link Environment} into a fresh {@link AgentConfig.Sandbox}. Mirrors the
     * {@link #readRemoteAgents(Environment)} pattern for inline per-AgentConfig
     * overrides (Story #025 had this gap — the {@code agentConfig(Environment)}
     * @Bean was using {@code defaults.getSandbox()}, so the demo's
     * {@code application.yml} {@code agent.sandbox} block was never consumed).
     *
     * <p>5-field schema (dsh §5623-5627, matches {@link AgentConfig.Sandbox}):
     * <ul>
     *   <li>{@code .policy} — String, defaults to AgentConfigDefaults's value</li>
     *   <li>{@code .runtime} — String, defaults to AgentConfigDefaults's value</li>
     *   <li>{@code .working-directory} — Path; Spring resolves
     *       {@code ${user.dir}} placeholder at {@code env.getProperty} time,
     *       so the resulting String is the absolute path; we then convert
     *       via {@link Paths#get(String, String...)}</li>
     *   <li>{@code .command-whitelist} / {@code .domain-whitelist} —
     *       {@code List<String>} walked by index, default to
     *       AgentConfigDefaults's empty list</li>
     * </ul>
     *
     * <p>Returns the AgentConfigDefaults {@link AgentConfig.Sandbox} verbatim
     * when the {@code agent.sandbox} block is absent from YAML — preserves
     * Story #001's "empty yml must boot" contract.
     */
    private static AgentConfig.Sandbox readSandbox(Environment env) {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        String policy = env.getProperty("agent.sandbox.policy",
            defaults.getSandbox().getPolicy());
        String runtime = env.getProperty("agent.sandbox.runtime",
            defaults.getSandbox().getRuntime());
        String wdRaw = env.getProperty("agent.sandbox.working-directory");
        Path workingDirectory = (wdRaw == null || wdRaw.isEmpty())
            ? defaults.getSandbox().getWorkingDirectory()
            : Paths.get(wdRaw);
        List<String> commandWhitelist = readSandboxList(env, "agent.sandbox.command-whitelist",
            defaults.getSandbox().getCommandWhitelist());
        List<String> domainWhitelist = readSandboxList(env, "agent.sandbox.domain-whitelist",
            defaults.getSandbox().getDomainWhitelist());
        if (env.containsProperty("agent.sandbox.policy")
            || env.containsProperty("agent.sandbox.runtime")
            || env.containsProperty("agent.sandbox.working-directory")
            || env.containsProperty("agent.sandbox.command-whitelist")
            || env.containsProperty("agent.sandbox.domain-whitelist")) {
            LOG.info("agentConfig: sandbox bound from YAML — policy={} runtime={} "
                + "workingDir={} cmdWhitelist(size={}) domainWhitelist(size={})",
                policy, runtime, workingDirectory,
                commandWhitelist.size(), domainWhitelist.size());
        } else {
            LOG.info("agentConfig: no agent.sandbox in YAML — using defaults");
        }
        return new AgentConfig.Sandbox(policy, runtime, workingDirectory,
            commandWhitelist, domainWhitelist);
    }

    /**
     * Walk {@code prefix[N]} (zero-based index) from {@link Environment} into a
     * {@code List<String>}. Mirrors the index-walking helper in
     * {@link McpServerProperties#bindFromEnvironment} but takes a fallback list
     * (defaults-based) so absence is preserved verbatim rather than coerced
     * to {@code Collections.emptyList()}.
     */
    private static List<String> readSandboxList(Environment env, String prefix,
                                                List<String> fallback) {
        List<String> out = new ArrayList<>();
        for (int j = 0; ; j++) {
            String v = env.getProperty(prefix + "[" + j + "]");
            if (v == null) {
                break;
            }
            out.add(v);
        }
        return out.isEmpty() ? fallback : out;
    }

    /**
     * Assemble the full 24-field {@link AgentConfig}, overriding only the
     * fields that need values from YAML. 20 fields stay untouched; only
     * {@code mcp} (Story #025), {@code a2a.remoteAgents} (Story #025b),
     * {@code a2aTransport} (Story #025b), and {@code sandbox} (Story #025
     * follow-up) come from the environment.
     */
    private static AgentConfig mergeConfig(AgentConfig defaults, AgentConfig.Mcp mcp,
                                           AgentConfig.A2a a2a, List<AgentRef> remoteAgents,
                                           String a2aTransportName,
                                           AgentConfig.Sandbox sandbox) {
        return new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            sandbox,                                    // 🆕 Story #025 follow-up — populated from YAML
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