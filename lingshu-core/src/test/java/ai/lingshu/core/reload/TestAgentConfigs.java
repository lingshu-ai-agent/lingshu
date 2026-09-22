package ai.lingshu.core.reload;

import ai.lingshu.core.runtime.AgentConfig;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

/**
 * Story #007 — AgentConfig test fixtures.
 *
 * <p>Builds minimally valid {@link AgentConfig} instances for unit tests
 * (Registry / Watcher / InFlightFreeze / IT). All fields are explicit because
 * {@code AgentConfig} is {@code @Value} (Lombok immutable) — no setter / builder.
 *
 * <p>Each method returns a distinct identity ({@code ==}) so tests can use
 * {@code assertSame} to verify freeze semantics.
 */
final class TestAgentConfigs {

    private TestAgentConfigs() {
        // utility class
    }

    /** Sandbox with {@code [ls, cat]} whitelist (Story #007 default). */
    private static AgentConfig.Sandbox defaultSandbox() {
        return new AgentConfig.Sandbox(
            "strict",
            "chroot",
            Paths.get("/tmp"),
            Arrays.asList("ls", "cat"),
            Collections.emptyList());
    }

    private static AgentConfig.Llm defaultLlm() {
        return new AgentConfig.Llm("anthropic", "claude-3-5-sonnet-latest", 8192, 0.7);
    }

    private static AgentConfig.Prompt defaultPrompt() {
        return new AgentConfig.Prompt("default", Collections.emptyList(), 5);
    }

    /** Build a minimal AgentConfig with the given sandbox whitelist. */
    static AgentConfig withSandboxWhitelist(java.util.List<String> whitelist) {
        return new AgentConfig(
            "linear",
            defaultLlm(),
            defaultPrompt(),
            "default",
            new AgentConfig.Sandbox("strict", "chroot", Paths.get("/tmp"),
                whitelist, Collections.emptyList()),
            null,
            null,
            null,
            null,
            null,
            8,
            30,
            60,
            120,
            30,
            50,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,                       // a2aTransport
            null,                       // tenants
            AgentConfig.A2a.defaults(), // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults()); // compactorConfig (Story #018)
    }

    /** Minimal AgentConfig with the {@code [ls, cat]} baseline whitelist. */
    static AgentConfig baseline() {
        return withSandboxWhitelist(Arrays.asList("ls", "cat"));
    }

    /** Minimal AgentConfig with the {@code [ls, cat, git]} extended whitelist. */
    static AgentConfig extended() {
        return withSandboxWhitelist(Arrays.asList("ls", "cat", "git"));
    }

    /** Convenience accessor for the sandbox whitelist. */
    static java.util.List<String> whitelist(AgentConfig cfg) {
        return cfg.getSandbox().getCommandWhitelist();
    }
}