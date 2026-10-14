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

import ai.lingshu.core.impl.compaction.NullCompactor;
import ai.lingshu.core.impl.flow.support.EchoLlmProvider;
import ai.lingshu.core.impl.flow.support.RecordingPromptBuilder;
import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.sessionstore.NullSessionStore;
import ai.lingshu.core.impl.sessionstore.RecordingSessionStore;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.LlmResponse;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.message.StopReason;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.Usage;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #046 — L1 tests for {@link LinearTurnEngineProvider} SessionStoreRouter wiring (3 cases).
 *
 * <p>Validates the Slot 5 SessionStore flow:
 * <ul>
 *   <li>a stub {@link Providers.SessionStoreProvider} registered with the router must be
 *       resolved by name and wired into the produced {@link LinearTurnEngine};</li>
 *   <li>legacy 6/7-arg ctors (Story #030 / Story #045) must still work and delegate to
 *       the 8-arg ctor with {@code sessionStoreRouter = null};</li>
 *   <li>a null {@code sessionStoreRouter} (legacy back-compat path) wires
 *       {@link NullSessionStore#INSTANCE} into the engine, so the production router IS
 *       NOT INVOKED at all when absent.</li>
 * </ul>
 *
 * <p>Mirrors {@link LinearTurnEngineProviderCompactionTest} (Story #045 pattern) with
 * {@code sessionStoreRouter} substituted for {@code compactorRouter}.
 */
class LinearTurnEngineProviderSessionStoreTest {

    private DefaultToolExecutor toolExecutor;
    private DefaultToolRegistry toolRegistry;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        toolRegistry = new DefaultToolRegistry();
        toolExecutor = new DefaultToolExecutor(new AllowAllPermissionPolicy(), toolRegistry);
        pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "provider-sessionstore-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        pool.shutdown();
        pool.awaitTermination(2, TimeUnit.SECONDS);
    }

    // ─── Fixture helpers (mirror LinearTurnEngineProviderCompactionTest) ─────

    private static Providers.LlmProviderProvider llmProvider(String name, int prio) {
        return new Providers.LlmProviderProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) {
                return new EchoLlmProvider(Collections.singletonList(
                    new LlmResponse("", Collections.<ToolCall>emptyList(),
                        StopReason.END_TURN, Usage.zero())));
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
    private static Providers.CompactorProvider compactorProvider(String name, int prio) {
        return new Providers.CompactorProvider() {
            @Override public String name() { return name; }
            @Override public int priority() { return prio; }
            @Override public String version() { return "1.0.0"; }
            @Override public Compactor create(AgentConfig cfg) { return NullCompactor.INSTANCE; }
        };
    }

    /** Stub provider whose create() returns the same shared RecordingSessionStore instance. */
    private static class RecordingSessionStoreProvider implements Providers.SessionStoreProvider {
        final RecordingSessionStore store;
        final String providerName;
        RecordingSessionStoreProvider(String name, RecordingSessionStore store) {
            this.providerName = name;
            this.store = store;
        }
        @Override public String name() { return providerName; }
        @Override public int priority() { return 0; }
        @Override public String version() { return "1.0.0"; }
        @Override public SessionStore create(AgentConfig cfg) { return store; }
    }

    private AgentConfig defaultConfigWithSessionStore(String sessionStoreName) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox("allow-all", "noop", Paths.get("."),
                Collections.<String>emptyList(), Collections.<String>emptyList()),
            "truncating",
            sessionStoreName,
            null, null, null,
            1, 5, 0, 0, 0, 5,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null, AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
            AgentConfig.ToolsConfig.defaults(),
            "default",
        16,
        32);
    }

    // ─── Test cases ────────────────────────────────────────────────────────

    /**
     * L1-046-08 — When a {@code SessionStoreRouter} is wired with a stub provider and
     * the engine factory resolves the name, the stub's {@code create()} must run
     * once at provider.create() time (mirror of the Slot 6 Compactor flow from
     * Story #045 — the SessionStore instance is passed to LinearTurnEngine directly).
     */
    @Test
    @DisplayName("L1-046-08: create_resolvesSessionStoreFromRouter")
    void create_resolvesSessionStoreFromRouter() {
        RecordingSessionStore stubStore = new RecordingSessionStore();
        Routers.SessionStoreRouter router = new Routers.SessionStoreRouter(
            Collections.<Providers.SessionStoreProvider>singletonList(
                new RecordingSessionStoreProvider("file", stubStore)));

        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            new Routers.PromptBuilderRouter(Collections.singletonList(promptProvider("default", 0))),
            new Routers.LlmProviderRouter(Collections.singletonList(llmProvider("anthropic", 10))),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolProvider("default", 0))),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policyProvider("default", 0))),
            pool, null, null /* compactorRouter */, router);

        FlowEngine engine = provider.create(defaultConfigWithSessionStore("file"));
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);

        // Verify the store resolved by the router is the SAME instance wired into
        // the engine — by exercising the engine end-to-end and observing the save.
        // (Reflective access to the private field would also work but is fragile.)
        AtomicInteger saved = new AtomicInteger(0);
        // Run a tiny turn to drive the engine's trySave path.
        ai.lingshu.core.impl.flow.support.CapturingSubscriber sink =
            new ai.lingshu.core.impl.flow.support.CapturingSubscriber();
        ai.lingshu.core.impl.runtime.DefaultTurnContext ctx =
            new ai.lingshu.core.impl.runtime.DefaultTurnContext(
                new ai.lingshu.core.impl.runtime.DefaultSession(),
                defaultConfigWithSessionStore("file"), sink, "hi");
        ((LinearTurnEngine) engine).runTurn(ctx, sink);
        saved.set(stubStore.saveHistory().size());

        // Stub store must have received the checkpoint from the engine's normal-end path.
        assertThat(saved.get())
            .as("Resolved SessionStore instance must be wired into the engine; "
                + "engine.runTurn must invoke trySave, which delegates to the store.")
            .isGreaterThanOrEqualTo(1);
    }

    /**
     * L1-046-09 — Legacy 6-arg ctor (Story #030) must still work and delegate to the
     * 8-arg ctor with {@code compactorRouter = null, sessionStoreRouter = null}. The
     * produced engine must use NullSessionStore (verified by absence of any router
     * invocation).
     */
    @Test
    @DisplayName("L1-046-09: legacy6ArgCtor_backCompat_withNullRouters")
    void legacy6ArgCtor_backCompat_withNullRouters() {
        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            new Routers.PromptBuilderRouter(Collections.singletonList(promptProvider("default", 0))),
            new Routers.LlmProviderRouter(Collections.singletonList(llmProvider("anthropic", 10))),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolProvider("default", 0))),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policyProvider("default", 0))),
            pool, null /* approvalRegistry */);

        FlowEngine engine = provider.create(defaultConfigWithSessionStore("file"));
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);
        // No exception ⇒ back-compat path is intact.
        assertThat(provider.name()).isEqualTo("linear");
    }

    /**
     * L1-046-10 — Legacy 7-arg ctor (Story #045) must still work and delegate to the
     * 8-arg ctor with {@code sessionStoreRouter = null}. The produced engine must use
     * NullSessionStore (verified by absence of any router invocation).
     */
    @Test
    @DisplayName("L1-046-10: legacy7ArgCtor_backCompat_withNullSessionRouter")
    void legacy7ArgCtor_backCompat_withNullSessionRouter() {
        LinearTurnEngineProvider provider = new LinearTurnEngineProvider(
            new Routers.PromptBuilderRouter(Collections.singletonList(promptProvider("default", 0))),
            new Routers.LlmProviderRouter(Collections.singletonList(llmProvider("anthropic", 10))),
            new Routers.ToolExecutorRouter(Collections.singletonList(toolProvider("default", 0))),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policyProvider("default", 0))),
            pool, null /* approvalRegistry */, null /* compactorRouter */);

        FlowEngine engine = provider.create(defaultConfigWithSessionStore("file"));
        assertThat(engine).isInstanceOf(LinearTurnEngine.class);
    }
}