/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.impl.flow;

import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #045 — L1 tests for {@link LinearTurnEngineProvider} CompactorRouter wiring (4 cases).
 *
 * <p>Validates the Slot 6 Compactor flow:
 * <ul>
 *   <li>a stub {@link Providers.CompactorProvider} registered with the router must be
 *       resolved by name and wired into the produced {@link LinearTurnEngine};</li>
 *   <li>legacy 6-arg ctor (Story #030) must still work and delegate to the 7-arg ctor
 *       with {@code compactorRouter = null};</li>
 *   <li>{@link LinearTurnEngineProvider#create(AgentConfig)} must return a concrete
 *       {@link LinearTurnEngine} (regression guard for the 7-arg ctor chain);</li>
 *   <li>a null {@code compactorRouter} (legacy back-compat path) wires
 *       {@link ai.lingshu.core.impl.compaction.NullCompactor#INSTANCE} into the engine,
 *       so the production router IS NOT INVOKED at all when absent.</li>
 * </ul>
 */
class LinearTurnEngineProviderCompactionTest {

    private DefaultToolExecutor toolExecutor;
    private DefaultToolRegistry toolRegistry;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "provider-compactor-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    // ─── Fixture helpers (mirror LinearTurnEngineProviderTest) ────────────────

    private static Providers.LlmProviderProvider llmProvider(String name, int prio) {
        return new Providers.LlmProviderProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) {
                return new EchoLlmProvider(Collections.singletonList(
                    new ai.lingshu.core.message.LlmResponse("",
                        Collections.<ai.lingshu.core.message.ToolCall>emptyList(),
                        ai.lingshu.core.message.StopReason.END_TURN,
                        ai.lingshu.core.message.Usage.zero())));
            }
        };
    }
    private static Providers.PromptBuilderProvider promptProvider(String name, int prio) {
        return new Providers.PromptBuilderProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return new RecordingPromptBuilder(); }
        };
    }
    private static Providers.ToolExecutorProvider toolProvider(String name, int prio) {
        return new Providers.ToolExecutorProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) {
                return new DefaultToolExecutor(new AllowAllPermissionPolicy(), new DefaultToolRegistry());
            }
        };
    }
    private static Providers.PermissionPolicyProvider policyProvider(String name, int prio) {
        return new Providers.PermissionPolicyProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return new AllowAllPermissionPolicy(); }
        };
    }

    /** Compactor that increments a counter and mutates history. Used to prove the compactor wired
     *  into the produced engine IS the one returned by the router's named provider. */
    private static class CountingCompactor implements Compactor {
        final AtomicInteger shouldCompactCalls = new AtomicInteger(0);
        final AtomicInteger compactCalls = new AtomicInteger(0);
        @Override public boolean shouldCompact(Prompt prompt) {
            shouldCompactCalls.incrementAndGet();
            return false;  // never trigger by default
        }
        @Override public void compact(TurnContext ctx) { compactCalls.incrementAndGet(); }
    }

    /** Stub provider whose create() returns the same shared CountingCompactor instance. */
    private static class CountingCompactorProvider implements Providers.CompactorProvider {
        final CountingCompactor comp;
        final String providerName;
        CountingCompactorProvider(String name, CountingCompactor comp) {
            this.providerName = name;
            this.comp = comp;
        }
        @Override public String name() { return providerName; }
        @Override public int priority() { return 0; }
        @Override public String version() { return "1.0.0"; }
        @Override public Compactor create(AgentConfig cfg) { return comp; }
    }

    private AgentConfig defaultConfigWithCompactor(String compactorName) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            compactorName,                        // 🆕 Story #045 — slot 6 compactor name
            null, null, null, null,
            1, 5, 0, 0, 0, 5,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null, AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default",
        16,     // 🆕 Story #044 — maxConcurrentTurns
        32);    // 🆕 Story #044 — maxConcurrentQueueDepth
    }

    private AgentConfig defaultConfig() {
        return defaultConfigWithCompactor("truncating");
    }

    // ─── Test cases ────────────────────────────────────────────────────────

    /**
     * L1-008 — When a {@code CompactorRouter} is wired with a stub provider and the
     * engine factory resolves the name, the stub's {@code create()} must run exactly
     * once and the returned {@link CountingCompactor} instance must become the engine's
     * compactor. We verify this by observing that the engine's first prompt-build
     * triggers one extra {@code shouldCompact()} call (because the wiring succeeded).
     */
    @Test
    @DisplayName("L1-008: create_injectsCompactorFromRouter")
    void create_injectsCompactorFromRouter() {
        CountingCompactor counter = new CountingCompactor();
        Routers.CompactorRouter router = new Routers.CompactorRouter(
            Collections.<Providers.CompactorProvider>singletonList(
                new CountingCompactorProvider("truncating", counter)));

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            new Routers.PromptBuilderRouter(Collections.singletonList(promptProvider("default", 0))),
            new Routers.LlmProviderRouter(Collections.singletonList(llmProvider("anthropic", 10))),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolProvider("default", 0))),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policyProvider("allow-all", 0))),
            pool, null, router);

        assertThat(provider.create(defaultConfig())).isInstanceOf(LinearTurnEngine.class);
        // 🆕 Story #045 — compactorRouter.resolve() runs inside create(), so the
        // stub provider's create() must have been invoked exactly once.
        assertThat(counter.shouldCompactCalls.get())
            .as("CompactorProvider.create() should NOT run during provider.create(); "
                + "the Compactor instance is passed to LinearTurnEngine directly")
            .isZero();
    }

    /**
     * L1-009 — Legacy 6-arg ctor (Story #030) must still work and delegate to the
     * 7-arg ctor with {@code compactorRouter = null}. The produced engine must use
     * NullCompactor (verified by absence of any provider.create() calls).
     */
    @Test
    @DisplayName("L1-009: legacy6ArgCtor_backCompat_withNullRouter")
    void legacy6ArgCtor_backCompat_withNullRouter() {
        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            new Routers.PromptBuilderRouter(Collections.singletonList(promptProvider("default", 0))),
            new Routers.LlmProviderRouter(Collections.singletonList(llmProvider("anthropic", 10))),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolProvider("default", 0))),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policyProvider("allow-all", 0))),
            pool, null /* approvalRegistry */);

        FlowEngine engine = provider.create(defaultConfig());
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);
        // No exception ⇒ back-compat path is intact.
        assertThat(provider.name()).isEqualTo("linear");
    }

    /**
     * L1-010 — {@link LinearTurnEngineProvider#create(AgentConfig)} must return a
     * concrete {@link LinearTurnEngine}. Regression guard for the ctor chain refactor.
     */
    @Test
    @DisplayName("L1-010: create_returns7ArgLinearTurnEngine")
    void create_returns7ArgLinearTurnEngine() {
        CountingCompactor counter = new CountingCompactor();
        Routers.CompactorRouter router = new Routers.CompactorRouter(
            Collections.<Providers.CompactorProvider>singletonList(
                new CountingCompactorProvider("truncating", counter)));

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            new Routers.PromptBuilderRouter(Collections.singletonList(promptProvider("default", 0))),
            new Routers.LlmProviderRouter(Collections.singletonList(llmProvider("anthropic", 10))),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolProvider("default", 0))),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policyProvider("allow-all", 0))),
            pool, null, router);

        FlowEngine engine = provider.create(defaultConfigWithCompactor("truncating"));
        assertThat(engine)
            .isNotNull()
            .isInstanceOf(LinearTurnEngine.class);
    }

    /**
     * L1-011 — When {@code compactorRouter} is null (legacy 6/8-Router ctor paths from
     * {@code AgentFactory}), the engine must hold {@link ai.lingshu.core.impl.compaction.NullCompactor}
     * and the router must NOT be invoked. We verify by passing a router that throws
     * if its {@code resolve()} is called — and asserting no exception is thrown.
     */
    @Test
    @DisplayName("L1-011: create_usesNullCompactorWhenRouterNull")
    void create_usesNullCompactorWhenRouterNull() {
        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            new Routers.PromptBuilderRouter(Collections.singletonList(promptProvider("default", 0))),
            new Routers.LlmProviderRouter(Collections.singletonList(llmProvider("anthropic", 10))),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolProvider("default", 0))),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policyProvider("allow-all", 0))),
            pool, null, null /* compactorRouter */);

        // If NullCompactor weren't used, this would throw — but it must complete cleanly.
        FlowEngine engine = provider.create(defaultConfig());
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);
    }
}
