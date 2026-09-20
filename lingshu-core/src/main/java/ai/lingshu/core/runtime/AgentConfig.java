package ai.lingshu.core.runtime;

import lombok.Value;

import java.nio.file.Path;
import java.nio.file.Paths;
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

    // ── Nested config records ───────────────────────────────────────────

    @Value
    public static class Llm {
        /** Provider key — {@code "anthropic"}, {@code "openai"}, {@code "deepseek"}, etc. */
        String provider;
        /** Model id — {@code "claude-sonnet-4-5"}, {@code "gpt-4o"}, {@code "deepseek-chat"}. */
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
}