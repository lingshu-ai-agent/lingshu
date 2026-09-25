package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.mcp.McpServerConfig;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.McpTransportType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Story #025 — bind {@code agent.mcp.servers} YAML into {@link McpServerConfig}
 * runtime objects.
 *
 * <p><b>Why this exists</b> — {@link AgentConfig} is Lombok {@code @Value}
 * (immutable, no setters), so Spring Boot's {@code @ConfigurationProperties}
 * Binder cannot bind YAML directly to it. {@code McpTransportAutoConfiguration}
 * then reads {@code agentConfig.getMcp().getServers()} and gets {@code null}.
 *
 * <p>This POJO + {@link #bindFromEnvironment(Environment)} walks the indexed
 * list properties by hand (same pattern as {@code SkillSourceProperties}),
 * producing a populated {@link List} of {@link McpServerConfig} that we
 * assemble into {@code AgentConfig.Mcp} at {@link
 * ai.lingshu.examples.demoproduct.DemoProductApplication#agentConfig} time.
 *
 * <p><b>Iteration termination</b> — walk {@code agent.mcp.servers[N]} for
 * {@code N = 0, 1, 2, ...} until {@code .name} at index N is missing.
 *
 * <p><b>JDK 8 compatibility</b> — Plain POJO with hand-written setters (no
 * Lombok {@code @Data} on the entry to keep the file readable for first-time
 * contributors; follows the {@code SkillSourceProperties} rationale).
 */
public class McpServerProperties {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerProperties.class);

    /** Ordered list of MCP server entries from YAML; empty when {@code agent.mcp} absent. */
    private List<ServerEntry> servers = new ArrayList<>();

    public List<ServerEntry> getServers() { return servers; }
    public void setServers(List<ServerEntry> servers) { this.servers = servers; }

    /**
     * One MCP server entry. Field names mirror the YAML keys (kebab-case in
     * yml, camelCase here per Spring's relaxed binding convention).
     */
    public static class ServerEntry {
        private String name;
        private String transport;
        private String command;
        private List<String> args;
        private Map<String, String> env;
        private String url;
        private long heartbeatIntervalMs = 30_000L;
        private long heartbeatTimeoutMs = 10_000L;
        private long reconnectCapMs = 60_000L;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getTransport() { return transport; }
        public void setTransport(String transport) { this.transport = transport; }

        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }

        public List<String> getArgs() { return args; }
        public void setArgs(List<String> args) { this.args = args; }

        public Map<String, String> getEnv() { return env; }
        public void setEnv(Map<String, String> env) { this.env = env; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
        public void setHeartbeatIntervalMs(long v) { this.heartbeatIntervalMs = v; }

        public long getHeartbeatTimeoutMs() { return heartbeatTimeoutMs; }
        public void setHeartbeatTimeoutMs(long v) { this.heartbeatTimeoutMs = v; }

        public long getReconnectCapMs() { return reconnectCapMs; }
        public void setReconnectCapMs(long v) { this.reconnectCapMs = v; }
    }

    /**
     * Walk {@code agent.mcp.servers[N].*} properties from {@link Environment}
     * into a fresh {@link McpServerProperties}. Stops at the first index where
     * {@code .name} is missing.
     *
     * <p>For each entry:
     * <ul>
     *   <li>{@code .name} — required (sentinel for iteration)</li>
     *   <li>{@code .transport} — defaults to {@code "stdio"}; uppercased
     *       before {@link McpTransportType#valueOf(String)}</li>
     *   <li>{@code .command} / {@code .args} / {@code .url} — optional;
     *       {@code McpServerConnectionFactory} enforces which are required
     *       per-transport at {@code start()} time</li>
     *   <li>{@code .heartbeat-*-ms} / {@code .reconnect-cap-ms} — have
     *       sensible defaults via the {@link ServerEntry} field initializers
     *       (30 s / 10 s / 60 s), but explicitly-set values override them</li>
     *   <li>{@code .args[N]} — walked by index; missing index terminates the
     *       args list. Mirrors Spring Boot's index-based list binding
     *       semantics for the {@code [a, b, c]} flow-style yml form too.</li>
     * </ul>
     */
    public static McpServerProperties bindFromEnvironment(Environment env) {
        McpServerProperties p = new McpServerProperties();
        List<ServerEntry> entries = new ArrayList<>();
        for (int i = 0; ; i++) {
            String prefix = "agent.mcp.servers[" + i + "]";
            String name = env.getProperty(prefix + ".name");
            if (name == null) {
                break;
            }
            ServerEntry e = new ServerEntry();
            e.setName(name);
            e.setTransport(env.getProperty(prefix + ".transport", "stdio"));
            e.setCommand(env.getProperty(prefix + ".command"));
            e.setArgs(readStringList(env, prefix + ".args"));
            e.setEnv(readStringMap(env, prefix + ".env"));
            e.setUrl(env.getProperty(prefix + ".url"));
            e.setHeartbeatIntervalMs(env.getProperty(prefix + ".heartbeat-interval-ms", Long.class, 30_000L));
            e.setHeartbeatTimeoutMs(env.getProperty(prefix + ".heartbeat-timeout-ms", Long.class, 10_000L));
            e.setReconnectCapMs(env.getProperty(prefix + ".reconnect-cap-ms", Long.class, 60_000L));
            entries.add(e);
        }
        p.setServers(entries);
        LOG.info("MCP server binding: {} server(s) configured from YAML: {}",
            entries.size(), summarizeNames(entries));
        return p;
    }

    private static List<String> readStringList(Environment env, String prefix) {
        List<String> out = new ArrayList<>();
        for (int j = 0; ; j++) {
            String v = env.getProperty(prefix + "[" + j + "]");
            if (v == null) {
                break;
            }
            out.add(v);
        }
        return out.isEmpty() ? Collections.<String>emptyList() : out;
    }

    /**
     * Environment walking for {@code Map<String,String>} env block.
     *
     * <p><b>Simplification for Story #025 demo</b> — Spring's {@code Environment}
     * interface does NOT expose {@code getPropertyNames()}; that's only on
     * {@code EnumerablePropertySource}, requiring us to traverse
     * {@code getPropertySources()} and downcast each source. To avoid that
     * complexity (and keep this file self-contained), we <b>always default
     * the env map to empty</b>. Story #025's MCP servers don't need
     * subprocess env overrides; if a future demo needs them, the right move
     * is {@code Binder.get(env).bind(prefix, Bindable.mapOf(String.class, String.class))}
     * which is in spring-boot (already on the demo classpath).
     */
    private static Map<String, String> readStringMap(Environment env, String prefix) {
        return Collections.<String, String>emptyMap();
    }

    private static String summarizeNames(List<ServerEntry> entries) {
        List<String> names = new ArrayList<>(entries.size());
        for (ServerEntry e : entries) {
            names.add(e.getName() + "(" + e.getTransport() + ")");
        }
        return names.toString();
    }

    // ── Conversions to McpServerConfig (runtime) ────────────────────────

    /**
     * Convert bound entries to runtime {@link McpServerConfig} list. Caller
     * passes the result to {@code McpTransportAutoConfiguration.mcpServerConfigs}
     * (which itself wraps {@code AgentConfig.ServerConfig}, but for our
     * AgentConfig-construction path we need the {@code AgentConfig.ServerConfig}
     * form — see {@link #toAgentConfigServerConfigs()}).
     */
    public List<McpServerConfig> toMcpServerConfigs() {
        List<McpServerConfig> out = new ArrayList<>(servers.size());
        for (ServerEntry e : servers) {
            out.add(toRuntimeConfig(e));
        }
        return out;
    }

    /** Same as {@link #toMcpServerConfigs()} but typed for {@code AgentConfig.Mcp.servers}. */
    public List<AgentConfig.ServerConfig> toAgentConfigServerConfigs() {
        List<AgentConfig.ServerConfig> out = new ArrayList<>(servers.size());
        for (ServerEntry e : servers) {
            out.add(toAgentConfigServerConfig(e));
        }
        return out;
    }

    private static McpServerConfig toRuntimeConfig(ServerEntry e) {
        McpTransportType transport = parseTransport(e.getTransport());
        return McpServerConfig.builder()
            .name(e.getName())
            .transport(transport)
            .command(e.getCommand())
            .args(e.getArgs() != null ? e.getArgs() : Collections.<String>emptyList())
            .env(e.getEnv() != null ? e.getEnv() : Collections.<String, String>emptyMap())
            .url(e.getUrl())
            .heartbeatIntervalMs(e.getHeartbeatIntervalMs() > 0 ? e.getHeartbeatIntervalMs() : 30_000L)
            .heartbeatTimeoutMs(e.getHeartbeatTimeoutMs() > 0 ? e.getHeartbeatTimeoutMs() : 10_000L)
            .reconnectCapMs(e.getReconnectCapMs() > 0 ? e.getReconnectCapMs() : 60_000L)
            .build();
    }

    private static AgentConfig.ServerConfig toAgentConfigServerConfig(ServerEntry e) {
        return new AgentConfig.ServerConfig(
            e.getName(),
            e.getCommand(),
            e.getArgs() != null ? e.getArgs() : Collections.<String>emptyList(),
            e.getEnv() != null ? e.getEnv() : Collections.<String, String>emptyMap(),
            parseTransport(e.getTransport()),
            e.getUrl(),
            e.getHeartbeatIntervalMs() > 0 ? e.getHeartbeatIntervalMs() : 30_000L,
            e.getHeartbeatTimeoutMs() > 0 ? e.getHeartbeatTimeoutMs() : 10_000L,
            e.getReconnectCapMs() > 0 ? e.getReconnectCapMs() : 60_000L
        );
    }

    private static McpTransportType parseTransport(String s) {
        if (s == null || s.isEmpty()) {
            return McpTransportType.STDIO;
        }
        try {
            return McpTransportType.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            LOG.warn("unknown MCP transport '{}' — defaulting to STDIO", s);
            return McpTransportType.STDIO;
        }
    }
}