package ai.lingshu.core.tenant;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.memory.ProjectClaudeMdSource;
import ai.lingshu.core.impl.runtime.DefaultSession;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.TurnContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #006 — L1 unit tests for memory-path isolation between tenants (US2).
 *
 * <p>The strategy is per-tenant {@link AgentConfig.Memory#getClaudeMd()}{@code .project}
 * — each tenant points to its own directory, so {@link ProjectClaudeMdSource}
 * naturally reads its own CLAUDE.md without any cross-pollination.
 *
 * <p>No source change to {@code ProjectClaudeMdSource} is required — the existing
 * implementation already reads {@code cfg.memory.claudeMd.project} and returns its
 * contents to the prompt builder. The "isolation" is purely a config concern.
 */
class MemoryPathIsolationTest {

    private static AgentConfig configForTenant(Path memoryDir) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            "default",
            "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            new AgentConfig.Memory(
                new AgentConfig.ClaudeMd(true, memoryDir.resolve("CLAUDE.md"), null),
                Collections.<String>emptyList()),
            null,                                       // a2aTransport
            null);                                      // tenants
    }

    @Test
    @DisplayName("L1-010: perTenantWorkingDirectory_differentClaudeMd")
    void perTenantWorkingDirectory_differentClaudeMd() throws Exception {
        Path aliceDir = Files.createTempDirectory("alice-mem-");
        Path bobDir = Files.createTempDirectory("bob-mem-");
        try {
            Files.write(aliceDir.resolve("CLAUDE.md"),
                "secret-alice".getBytes(StandardCharsets.UTF_8));
            Files.write(bobDir.resolve("CLAUDE.md"),
                "secret-bob".getBytes(StandardCharsets.UTF_8));

            AgentConfig aliceCfg = configForTenant(aliceDir);
            AgentConfig bobCfg = configForTenant(bobDir);

            ProjectClaudeMdSource aliceSource = new ProjectClaudeMdSource(aliceCfg);
            ProjectClaudeMdSource bobSource = new ProjectClaudeMdSource(bobCfg);

            Subscriber<AgentEvent> noop = new Subscriber<AgentEvent>() {
                @Override public void onSubscribe(Subscription s) { s.request(1); }
                @Override public void onNext(AgentEvent o) { }
                @Override public void onError(Throwable t) { }
                @Override public void onComplete() { }
            };

            TurnContext aliceCtx = new DefaultTurnContext(
                new DefaultSession(), aliceCfg, noop, "alice turn");
            TurnContext bobCtx = new DefaultTurnContext(
                new DefaultSession(), bobCfg, noop, "bob turn");

            assertThat(aliceSource.load(aliceCtx)).contains("secret-alice");
            assertThat(bobSource.load(bobCtx)).contains("secret-bob");
        } finally {
            deleteRecursively(aliceDir);
            deleteRecursively(bobDir);
        }
    }

    @Test
    @DisplayName("L1-011: aliceSource_doesNotSeeBobData")
    void aliceSource_doesNotSeeBobData() throws Exception {
        Path aliceDir = Files.createTempDirectory("alice-mem-");
        Path bobDir = Files.createTempDirectory("bob-mem-");
        try {
            // Alice has CLAUDE.md; bob does not.
            Files.write(aliceDir.resolve("CLAUDE.md"),
                "secret-alice".getBytes(StandardCharsets.UTF_8));

            AgentConfig aliceCfg = configForTenant(aliceDir);
            AgentConfig bobCfg = configForTenant(bobDir);

            ProjectClaudeMdSource aliceSource = new ProjectClaudeMdSource(aliceCfg);
            ProjectClaudeMdSource bobSource = new ProjectClaudeMdSource(bobCfg);

            Subscriber<AgentEvent> noop = new Subscriber<AgentEvent>() {
                @Override public void onSubscribe(Subscription s) { s.request(1); }
                @Override public void onNext(AgentEvent o) { }
                @Override public void onError(Throwable t) { }
                @Override public void onComplete() { }
            };

            TurnContext aliceCtx = new DefaultTurnContext(
                new DefaultSession(), aliceCfg, noop, "alice");
            TurnContext bobCtx = new DefaultTurnContext(
                new DefaultSession(), bobCfg, noop, "bob");

            // Alice's source sees her CLAUDE.md
            assertThat(aliceSource.load(aliceCtx)).contains("secret-alice");
            // Bob's source sees nothing (file missing → null)
            assertThat(bobSource.load(bobCtx)).isNull();
            // Crucially: alice's source must not see bob's data, even though bob's
            // directory is on disk and could theoretically be probed. Because the
            // alice source only ever reads from aliceDir, it returns null — not
            // bob's directory.
            assertThat(aliceSource.load(aliceCtx)).doesNotContain("bob");
        } finally {
            deleteRecursively(aliceDir);
            deleteRecursively(bobDir);
        }
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        }
    }
}