package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.cost.CostBudgetExceededException;
import ai.lingshu.core.impl.cost.TenantAwareCostTracker;
import ai.lingshu.core.impl.sandbox.DefaultRuntimeSandbox;
import ai.lingshu.core.impl.session.DefaultInMemorySessionStore;
import ai.lingshu.core.impl.tenant.YamlTenantConfigProvider;
import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolException;
import ai.lingshu.core.tenant.TenantConfig;
import ai.lingshu.core.tenant.TenantConfigProvider;
import ai.lingshu.core.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #006 — AC-05 black-box end-to-end integration test (E2E-001).
 *
 * <p>Wires the four isolation dimensions end-to-end without Spring context
 * (matches the {@code AgentFactoryIntegrationTest} pattern — direct wiring is
 * faster and exposes wiring bugs earlier than {@code @SpringBootTest} would).
 *
 * <p>Each tenant gets:
 * <ul>
 *   <li><b>Configuration</b> — distinct {@link TenantConfig} via the
 *       {@link TenantConfigProvider} chain (alice's vs bob's whitelist,
 *       budgets, memory dirs).</li>
 *   <li><b>Session</b> — same logical {@code sessionId "sess-123"}, but
 *       different physical keys ({@code "alice:sess-123"} vs {@code "bob:sess-123"})
 *       via {@link DefaultInMemorySessionStore#buildKey}.</li>
 *   <li><b>Sandbox</b> — per-tenant {@code commandWhitelist} via
 *       {@link DefaultRuntimeSandbox#process()}. alice denies {@code git};
 *       bob allows it.</li>
 *   <li><b>Cost</b> — per-tenant buckets via {@link TenantAwareCostTracker}.
 *       Accumulating under one tenant does not move the other's counter.</li>
 * </ul>
 *
 * <p>Test wall-clock budget: 5 seconds (T032). Direct wiring keeps this at
 * well under 1 second.
 */
class TenantIsolationIT {

    private Path aliceDir;
    private Path bobDir;

    @AfterEach
    void cleanup() throws Exception {
        while (TenantContext.current() != null) {
            TenantContext.clear();
        }
        deleteRecursively(aliceDir);
        deleteRecursively(bobDir);
    }

    @Test
    @DisplayName("E2E-001: aliceAndBobIsolated_across4Dims (AC-05)")
    void aliceAndBobIsolated_across4Dims() throws Exception {
        aliceDir = Files.createTempDirectory("alice-it-");
        bobDir = Files.createTempDirectory("bob-it-");

        // 1) Configuration: build two tenants with distinct memory/sandbox/cost.
        TenantConfig aliceCfg = TenantConfig.builder()
            .tenantId("alice")
            .memory(TenantConfig.Memory.builder().dir(aliceDir).build())
            .sandbox(TenantConfig.Sandbox.ofWhitelist(java.util.Arrays.asList("ls", "cat")))
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(10_000_000L).build())
            .build();
        TenantConfig bobCfg = TenantConfig.builder()
            .tenantId("bob")
            .memory(TenantConfig.Memory.builder().dir(bobDir).build())
            .sandbox(TenantConfig.Sandbox.ofWhitelist(java.util.Arrays.asList("ls", "cat", "git")))
            .cost(TenantConfig.Cost.builder().sessionBudgetMicros(100_000_000L).build())
            .build();
        Map<String, TenantConfig> tenantMap = new HashMap<>();
        tenantMap.put("alice", aliceCfg);
        tenantMap.put("bob", bobCfg);

        AgentConfig rootCfg = new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                java.util.Arrays.asList("ls"), Collections.<String>emptyList()),
            "default", "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null,                                                   // a2aTransport
            new AgentConfig.TenantsConfig(true, tenantMap),          // tenants enabled
            AgentConfig.A2a.defaults(),                             // a2a (Story #009)
            AgentConfig.CompactorConfig.defaults());                // compactorConfig (Story #018)

        // 2) Wire up the four components — no Spring context.
        TenantConfigProvider provider = new YamlTenantConfigProvider(rootCfg);
        TenantAwareCostTracker tracker = new TenantAwareCostTracker(provider);
        DefaultInMemorySessionStore sessionStore = new DefaultInMemorySessionStore();
        RuntimeSandbox sandbox = new DefaultRuntimeSandbox(provider, rootCfg);

        // Pre-populate memory dirs (each tenant has its own). Memory-dir-as-isolation
        // is validated by MemoryPathIsolationTest; the E2E here proves the per-tenant
        // TenantConfig.Memory.dir resolves to the right place via the provider.
        Files.write(aliceDir.resolve("CLAUDE.md"), "alice-secret".getBytes(StandardCharsets.UTF_8));
        Files.write(bobDir.resolve("CLAUDE.md"), "bob-secret".getBytes(StandardCharsets.UTF_8));
        assertThat(provider.resolve("alice").get().getMemory().getDir()).isEqualTo(aliceDir);
        assertThat(provider.resolve("bob").get().getMemory().getDir()).isEqualTo(bobDir);

        // 3) Tenant-scoped turns — alice and bob each do their work.
        // alice turn — consumes cost, runs git (denied), saves session.
        TenantContext.runAs("alice", () -> {
            // Cost dim: 5M micros recorded against alice's bucket.
            tracker.accumulate(5_000_000L);
            // Sandbox dim: git denied (alice's whitelist has no git).
            assertThatThrownBy(() -> {
                try {
                    sandbox.process().run("git", java.util.Arrays.asList("status"), null);
                } catch (java.io.IOException ioe) {
                    throw new RuntimeException(ioe);
                }
            }).isInstanceOf(ToolException.PermissionDeniedException.class)
              .hasMessageContaining("alice");
            // Session dim: save under alice's bucket.
            sessionStore.save(new Checkpoint("sess-123",
                Collections.<Message>emptyList(),
                java.util.Collections.singletonMap("who", "alice"),
                Instant.now()));
            return null;
        });

        // bob turn — consumes cost, runs git (allowed), saves session.
        TenantContext.runAs("bob", () -> {
            // Cost dim: 7M micros recorded against bob's bucket.
            tracker.accumulate(7_000_000L);
            // Sandbox dim: git allowed (whitelist has it). We don't actually run it
            // — we just verify the whitelist gate doesn't throw.
            Throwable[] caught = new Throwable[1];
            try {
                sandbox.process().run("git", java.util.Arrays.asList("status"), null);
            } catch (Exception t) {
                caught[0] = t;
            }
            if (caught[0] != null) {
                assertThat(caught[0]).isNotInstanceOf(ToolException.PermissionDeniedException.class);
            }
            // Session dim: save under bob's bucket (same logical sessionId).
            sessionStore.save(new Checkpoint("sess-123",
                Collections.<Message>emptyList(),
                java.util.Collections.singletonMap("who", "bob"),
                Instant.now()));
            return null;
        });

        // 4) Assertions: cross-check isolation across the four dimensions.

        // (a) Configuration: per-tenant TenantConfig resolves to the right memory dir.
        // (Full MemorySource wiring against TenantConfig.Memory.dir is a follow-up;
        //  we verify the provider side here — that's what the E2E owns.)
        assertThat(provider.resolve("alice").get().getMemory().getDir()).isEqualTo(aliceDir);
        assertThat(provider.resolve("bob").get().getMemory().getDir()).isEqualTo(bobDir);

        // (b) Cost: each tenant's counter reflects only their own usage.
        assertThat(tracker.usedFor("alice")).isEqualTo(5_000_000L);
        assertThat(tracker.usedFor("bob")).isEqualTo(7_000_000L);
        assertThat(tracker.budgetFor("alice")).isEqualTo(10_000_000L);
        assertThat(tracker.budgetFor("bob")).isEqualTo(100_000_000L);

        // (c) Sandbox: bob's git call did not consume alice's whitelist gate.
        assertThatThrownBy(() -> {
            TenantContext.runAs("alice", () -> {
                try {
                    sandbox.process().run("git", java.util.Arrays.asList("status"), null);
                } catch (java.io.IOException ioe) {
                    throw new RuntimeException(ioe);
                }
                return null;
            });
        }).isInstanceOf(ToolException.PermissionDeniedException.class)
          .hasMessageContaining("alice");

        // (d) Session: same logical sessionId, different physical keys.
        Checkpoint aliceLoaded = TenantContext.runAs("alice",
            () -> sessionStore.load("sess-123").get());
        Checkpoint bobLoaded = TenantContext.runAs("bob",
            () -> sessionStore.load("sess-123").get());
        assertThat(aliceLoaded.getMetadata().get("who")).isEqualTo("alice");
        assertThat(bobLoaded.getMetadata().get("who")).isEqualTo("bob");
        assertThat(sessionStore.size()).isEqualTo(2);  // both physically distinct

        // (e) Budget fail-fast: alice accumulating 8M more would total 13M > 10M budget.
        assertThatThrownBy(() ->
                TenantContext.runAs("alice", () -> {
                    tracker.accumulate(8_000_000L);
                    return null;
                }))
            .isInstanceOf(CostBudgetExceededException.class);
        // bob is still fine after alice's exception (I-3: throw doesn't affect other tenants).
        assertThat(tracker.usedFor("bob")).isEqualTo(7_000_000L);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
        }
    }
}
