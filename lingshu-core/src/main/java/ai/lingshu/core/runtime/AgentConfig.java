package ai.lingshu.core.runtime;

import ai.lingshu.core.exception.LingsConfigException;
import ai.lingshu.core.tenant.TenantConfig;
import lombok.Value;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable per-turn configuration (dsh §4.12.2). Constructed once by
 * {@code AgentFactory.create(config)}, passed by reference through every Slot for the
 * lifetime of the turn, and never mutated.
 *
 * <p>Source-of-truth for the 27+ tunable fields the engine exposes. Default factories
 * ({@code defaults()}) populate every field so a {@code null} from YAML never reaches a
 * Slot without a fallback.
 */
@Value
public class AgentConfig {

    /** FlowEngine name (e.g. {@code "linear"}, {@code "adk"}). Resolved via FlowEngineRouter. */
    String flowEngine;
    Llm llm;
    Prompt prompt;
    /** ToolExecutor name; resolved via ToolExecutorRouter. */
    String toolExecutor;
    Sandbox sandbox;
    /** Compactor name; resolved via CompactorRouter. */
    String compactor;
    /** SessionStore name; resolved via SessionStoreRouter. */
    String sessionStore;
    /** Optional: only present when {@code agent.delegate.types} is configured. */
    Delegate delegate;
    /** Optional: only present when {@code agent.mcp.servers} is configured. */
    Mcp mcp;
    /** Optional: only present when at least one Skill source is configured. */
    Skills skills;
    /** Max parallel tool calls per turn. {@code -1} = unbounded; default 8; {@code 1} = serial. */
    int toolParallelism;
    /** Per-tool-call timeout (seconds); {@code 0} = no timeout. */
    int toolTimeoutSeconds;
    /** Human-approval timeout (seconds); {@code 0} = wait forever. */
    int approvalTimeoutSeconds;
    /** Wall-clock turn timeout (seconds); {@code 0} = no timeout. */
    int turnTimeoutSeconds;
    /** Single LLM call timeout (seconds). */
    int llmTimeoutSeconds;
    /** ReAct loop cap; {@code 0} = unlimited. Default 50. */
    int reactMaxSteps;
    /** v1.5.5 — Agent business identity. Null uses {@link Identity#defaults()}. */
    Identity identity;
    /** v1.5.5 — System prompt. Null uses {@link Instructions#empty()}. */
    Instructions instructions;
    /** v1.5.5 — Long-term project memory. Null uses {@link Memory#defaults()}. */
    Memory memory;
    /** Slot 9 — A2A transport name; resolved via A2aTransportRouter. */
    String a2aTransport;
    /** 🆕 Story #006 — multi-tenant config; null when no tenants are configured (single-tenant mode). */
    TenantsConfig tenants;
    /** 🆕 Story #009 — embedded A2A HTTP server config (AgentCard endpoint + RPC placeholder). */
    A2a a2a;

    // ── Nested config records ───────────────────────────────────────────

    @Value
    public static class Llm {
        /** Provider key — {@code "anthropic"}, {@code "openai"}, {@code "deepseek"}, etc. */
        String provider;
        /** Model id — {@code "claude-3-5-sonnet-latest"}, {@code "gpt-4o"}, {@code "deepseek-chat"}. */
        String model;
        Integer maxTokens;
        Double temperature;
    }

    @Value
    public static class Prompt {
        /** Builder name — {@code "default"}, {@code "rag-augmented"}. */
        String builder;
        /** Ordered MemorySource names; empty = no extra memory blocks. */
        List<String> memorySources;
        Integer topK;
    }

    @Value
    public static class Sandbox {
        /** Policy name — {@code "strict"}, {@code "permissive"}. */
        String policy;
        /** Runtime name — {@code "chroot"}, {@code "noop"}. */
        String runtime;
        Path workingDirectory;
        List<String> commandWhitelist;
        List<String> domainWhitelist;
    }

    @Value
    public static class Delegate {
        Path promptsDir;
        Map<String, TypeConfig> types;
    }

    @Value
    public static class TypeConfig {
        Llm llm;
        List<String> tools;
        Sandbox sandbox;
        Path systemPromptFile;
    }

    @Value
    public static class Mcp {
        List<ServerConfig> servers;
    }

    @Value
    public static class ServerConfig {
        String name;
        String command;
        List<String> args;
        Map<String, String> env;
    }

    @Value
    public static class Skills {
        List<SkillSource> sources;
        boolean hotReload;
    }

    @Value
    public static class SkillSource {
        String type;
        String location;
    }

    // ── v1.5.5 business config trio ─────────────────────────────────────

    @Value
    public static class Identity {
        String name;
        String role;
        String language;
        List<String> traits;
        String tone;
        String avatar;

        public static Identity defaults() {
            return new Identity("lingShu-agent", null, "auto",
                Collections.emptyList(), null, null);
        }
    }

    @Value
    public static class Instructions {
        Path file;
        String inline;
        String templateEngine;
        Map<String, String> variables;

        public static Instructions empty() {
            return new Instructions(null, null, "none", Collections.emptyMap());
        }
    }

    @Value
    public static class Memory {
        ClaudeMd claudeMd;
        List<String> extras;

        public static Memory defaults() {
            return new Memory(
                new ClaudeMd(true, Paths.get("./CLAUDE.md"),
                    Paths.get(System.getProperty("user.home"), ".lingshu", "CLAUDE.md")),
                Collections.emptyList());
        }
    }

    @Value
    public static class ClaudeMd {
        boolean enabled;
        Path project;
        Path user;
    }

    /**
     * 🆕 Story #006 — multi-tenant configuration block.
     *
     * <p>{@code null} (the default) means single-tenant mode — {@link AgentFactory}
     * skips tenant validation, no {@link TenantConfigProvider} is consulted, and
     * {@code TenantContext.current()} is allowed to be {@code null} throughout.
     *
     * <p>When {@code map} is non-empty, {@link #isEnabled()} returns {@code true}
     * and the engine enforces the four-dimensional isolation (Configuration /
     * Session / Sandbox / Cost) per dsh §14.9 N9.
     *
     * <p>Validation is performed eagerly in {@link #validate()} — fail-fast at
     * {@link AgentFactory#create} time so a typo in {@code application.yml}
     * surfaces as a {@link LingsConfigException} with the exact field paths
     * rather than a confusing {@code NullPointerException} mid-turn.
     */
    @Value
    public static class TenantsConfig {

        /** True iff the map is non-empty (i.e. multi-tenant mode is active). */
        boolean enabled;

        /** tenantId → TenantConfig mapping; empty in single-tenant mode. */
        Map<String, TenantConfig> map;

        /**
         * Validate every tenant config; aggregate all failures into a single
         * {@link LingsConfigException} so the user sees every problem in one shot.
         *
         * @throws LingsConfigException with code {@code "C02"} when any field is
         *         missing, the size limit is exceeded, or a key/value mismatch is detected
         */
        public void validate() {
            List<String> errors = new ArrayList<>();
            if (map.size() > 1000) {
                errors.add("tenants.map.size() = " + map.size() + " exceeds limit 1000");
            }
            for (Map.Entry<String, TenantConfig> e : map.entrySet()) {
                String tid = e.getKey();
                TenantConfig tc = e.getValue();
                if (tid == null || !tid.matches("[a-zA-Z0-9_-]{1,64}")) {
                    errors.add("tenants." + tid + ": invalid tenantId format");
                }
                if (tc == null) {
                    errors.add("tenants." + tid + ": config is null");
                    continue;
                }
                if (!tid.equals(tc.getTenantId())) {
                    errors.add("tenants." + tid + ": key/value tenantId mismatch (key='"
                        + tid + "', value='" + tc.getTenantId() + "')");
                }
                if (tc.getMemory() == null || tc.getMemory().getDir() == null) {
                    errors.add("tenants." + tid + ".memory.dir is required");
                }
                if (tc.getSandbox() == null || tc.getSandbox().getCommandWhitelist() == null) {
                    errors.add("tenants." + tid + ".sandbox.commandWhitelist is required");
                }
                if (tc.getCost() == null || tc.getCost().getSessionBudgetMicros() <= 0) {
                    errors.add("tenants." + tid + ".cost.sessionBudgetMicros must be > 0");
                }
            }
            if (!errors.isEmpty()) {
                throw new LingsConfigException("C02",
                    "tenants config validation failed:\n  - " + String.join("\n  - ", errors));
            }
        }

        /** Single-tenant mode default — empty map, {@link #isEnabled()} returns false. */
        public static TenantsConfig defaults() {
            return new TenantsConfig(false, Collections.emptyMap());
        }
    }

    // ── 🆕 Story #009 — embedded A2A HTTP server config ─────────────────

    /**
     * Embedded A2A server config (Story #009, dsh §5.6.8 LocalAgentCardGenerator).
     *
     * <p>Drives {@code A2aServer} (JDK built-in {@code com.sun.net.httpserver.HttpServer})
     * — listend on {@link #host}{@code :}{@link #port}, serves
     * {@code GET /.well-known/agent.json} (A2A v1.0 spec §2.1 fixed path) plus
     * {@code POST /rpc} 501 placeholder + catch-all 404.
     *
     * <p>Validated eagerly at {@code A2aServer} startup — fail-fast so a typo in
     * {@code application.yml} surfaces as a {@code LINGS-S06}
     * ({@code A2A_SERVER_START_FAILED}) or {@code LINGS-T02}
     * ({@code A2A_CARD_INVALID_CONFIG}) error rather than a confusing
     * {@code BindException} mid-turn.
     */
    @Value
    public static class A2a {

        /** Bind address; {@code "0.0.0.0"} = all interfaces, {@code "127.0.0.1"} = loopback only. */
        String host;
        /** TCP port; {@code 0} = OS-assigned; default {@code 8080}; valid range {@code 0..65535}. */
        Integer port;
        /** Story #009a: gRPC channel target (host:port); default {@code "localhost:50051"}. */
        String grpcTarget;
        /** Story #009a: AgentCard cache TTL; default {@code Duration.ofMinutes(5)}. */
        java.time.Duration cardTtl;
        /** Story #009c: HTTP-JSON-RPC server base URL; default {@code "http://localhost:8080"} (aligns with A2aServer host=0.0.0.0 + port=8080). */
        String httpBaseUrl;
        /** Story #009c: HTTP client call timeout; default {@code Duration.ofSeconds(30)}. */
        java.time.Duration callTimeout;

        /**
         * Zero-config default (dsh §5.6.8 default + Story #009a + #009c extensions) —
         * bind on all interfaces port 8080; gRPC localhost:50051; 5-min card cache;
         * http-jsonrpc at {@code http://localhost:8080} with 30s call timeout.
         * Matches {@code application.yml} absent — empty yml must boot (Story #001 AC-01-2).
         */
        public static A2a defaults() {
            return new A2a(
                "0.0.0.0", 8080,
                "localhost:50051", java.time.Duration.ofMinutes(5),
                "http://localhost:8080", java.time.Duration.ofSeconds(30)
            );
        }
    }
}