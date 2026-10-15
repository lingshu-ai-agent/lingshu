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
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.ApprovalRegistry;
import ai.lingshu.core.impl.sessionstore.NullSessionStore;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.spi.Providers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;

/**
 * Default FlowEngineProvider — wires {@link LinearTurnEngine} with the resolved
 * {@code PromptBuilder} + {@code LlmProvider} + {@code ToolExecutor} +
 * {@code PermissionPolicy} + shared {@code agentToolPool} from the same config.
 *
 * <p>🆕 Story #004 — adds {@code ToolExecutorRouter} + {@code PermissionPolicyRouter}
 * injections + the {@code agentToolPool} {@link ExecutorService} so the
 * {@link LinearTurnEngine#dispatchParallel} method can run tool calls concurrently.
 *
 * <p><b>🆕 Story #030 — optional {@link ApprovalRegistry} injection</b>: when present
 * (e.g. demo-product Spring context), the engine registers each pending approval
 * so an HTTP endpoint can deliver the human's answer back. When absent (e.g.
 * {@code lingshu-cli} or {@code demo-empty} Spring contexts that don't need
 * HTTP-driven approvals), the registry is null and the engine still works —
 * tests / fixtures invoke the continuation directly off the
 * {@code ApprovalRequired} event.
 *
 * <p><b>🆕 Story #045 — optional {@link Compactor} injection</b>: when present,
 * the engine resolves the {@link Compactor} by name from the
 * {@code CompactorRouter} (default {@code TruncatingCompactor} via
 * {@code TruncatingCompactorProvider}). When absent (legacy 6-arg constructor
 * used by Story #001-#029 fixtures), {@link NullCompactor#INSTANCE} is wired
 * in so the engine never invokes compaction when no router is registered.
 *
 * <p><b>🆕 Story #046 — optional {@link SessionStore} injection</b>: when present,
 * the engine resolves the {@link SessionStore} by name from the
 * {@code SessionStoreRouter} (default {@code FileSessionStore} via
 * {@code FileSessionStoreProvider}). When absent (legacy 6/7-arg constructors
 * or direct instantiation tests), {@link NullSessionStore#INSTANCE} is wired
 * in so the engine never throws a checkpoint-related error on turn-completion
 * when no router is registered. The engine invokes {@link SessionStore#save}
 * at each of the four turn-completion points; failures are logged and swallowed
 * so transient persistence errors never crash an already-completed turn.
 */
@Component
public class LinearTurnEngineProvider implements Providers.FlowEngineProvider {

    /** name "linear" wins on absence of any other FlowEngineProvider — see dsh §4.11.4. */
    @Override
    public String name() { return "linear"; }

    /** Default priority (lower than the external adapters' 5). */
    @Override
    public int priority() { return 0; }

    /** Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    private final Routers.PromptBuilderRouter promptBuilderRouter;
    private final Routers.LlmProviderRouter llmProviderRouter;
    private final Routers.ToolExecutorRouter toolExecutorRouter;
    private final Routers.PermissionPolicyRouter permissionPolicyRouter;
    private final ExecutorService agentToolPool;
    /** 🆕 Story #030 — optional; null when no Spring context registers the bean. */
    private final ApprovalRegistry approvalRegistry;
    /** 🆕 Story #045 — optional; null in legacy 6-arg ctor; resolved into {@link Compactor} in {@link #create(AgentConfig)}. */
    private final Routers.CompactorRouter compactorRouter;
    /** 🆕 Story #046 — optional; null in legacy 6/7-arg ctors; resolved into {@link SessionStore} in {@link #create(AgentConfig)}. */
    private final Routers.SessionStoreRouter sessionStoreRouter;

    /**
     * 🆕 Story #030 — legacy 6-arg constructor. Since Story #046 it delegates to
     * the 8-arg constructor with {@code compactorRouter = null} and
     * {@code sessionStoreRouter = null}; {@link #create} falls back to
     * {@link NullCompactor#INSTANCE} and {@link NullSessionStore#INSTANCE} so
     * Story #001-#029 fixtures that pre-date both compactor + sessionStore stay
     * compaction-free and persistence-free.
     */
    public LinearTurnEngineProvider(Routers.PromptBuilderRouter promptBuilderRouter,
                                   Routers.LlmProviderRouter llmProviderRouter,
                                   Routers.ToolExecutorRouter toolExecutorRouter,
                                   Routers.PermissionPolicyRouter permissionPolicyRouter,
                                   @Qualifier("agentToolPool") ExecutorService agentToolPool,
                                   @Autowired(required = false) @Nullable ApprovalRegistry approvalRegistry) {
        this(promptBuilderRouter, llmProviderRouter, toolExecutorRouter, permissionPolicyRouter,
             agentToolPool, approvalRegistry, null, null);
    }

    /**
     * 🆕 Story #045 — 7-arg constructor that wires the optional {@link Compactor}
     * via the {@link Routers.CompactorRouter}. Since Story #046 it delegates to
     * the 8-arg constructor with {@code sessionStoreRouter = null}; {@link #create}
     * falls back to {@link NullSessionStore#INSTANCE} so legacy direct-instantiation
     * tests don't need to mock the router.
     */
    public LinearTurnEngineProvider(Routers.PromptBuilderRouter promptBuilderRouter,
                                   Routers.LlmProviderRouter llmProviderRouter,
                                   Routers.ToolExecutorRouter toolExecutorRouter,
                                   Routers.PermissionPolicyRouter permissionPolicyRouter,
                                   @Qualifier("agentToolPool") ExecutorService agentToolPool,
                                   @Autowired(required = false) @Nullable ApprovalRegistry approvalRegistry,
                                   @Autowired(required = false) @Nullable Routers.CompactorRouter compactorRouter) {
        this(promptBuilderRouter, llmProviderRouter, toolExecutorRouter, permissionPolicyRouter,
             agentToolPool, approvalRegistry, compactorRouter, null);
    }

    /**
     * 🆕 Story #046 — primary 8-arg constructor that wires the optional
     * {@link SessionStore} via the {@link Routers.SessionStoreRouter}. Spring's
     * constructor-injection auto-resolves the router bean when present
     * (production); null is tolerated for direct-instantiation tests.
     *
     * <p>Constructor-level {@code @Autowired} is required (Story #045 follow-up)
     * because with three public constructors visible (the legacy 6/7-arg and this
     * primary 8-arg), Spring's "most parameters with @Autowired" heuristic no
     * longer suffices — Spring 6 needs an explicit primary ctor marker. Without
     * it the bean factory falls back to "no default constructor found" at startup.
     */
    @Autowired
    public LinearTurnEngineProvider(Routers.PromptBuilderRouter promptBuilderRouter,
                                   Routers.LlmProviderRouter llmProviderRouter,
                                   Routers.ToolExecutorRouter toolExecutorRouter,
                                   Routers.PermissionPolicyRouter permissionPolicyRouter,
                                   @Qualifier("agentToolPool") ExecutorService agentToolPool,
                                   @Autowired(required = false) @Nullable ApprovalRegistry approvalRegistry,
                                   @Autowired(required = false) @Nullable Routers.CompactorRouter compactorRouter,
                                   @Autowired(required = false) @Nullable Routers.SessionStoreRouter sessionStoreRouter) {
        this.promptBuilderRouter = promptBuilderRouter;
        this.llmProviderRouter = llmProviderRouter;
        this.toolExecutorRouter = toolExecutorRouter;
        this.permissionPolicyRouter = permissionPolicyRouter;
        this.agentToolPool = agentToolPool;
        this.approvalRegistry = approvalRegistry;
        this.compactorRouter = compactorRouter;
        this.sessionStoreRouter = sessionStoreRouter;
    }

    @Override
    public FlowEngine create(AgentConfig config) {
        // 🆕 Story #045 — resolve the Slot 6 Compactor from the router. When no
        // router has been registered (legacy / direct instantiation) we wire in
        // NullCompactor so the engine never calls compaction.
        Compactor compactor = compactorRouter != null
            ? compactorRouter.resolve(config.getCompactor(), config)
            : NullCompactor.INSTANCE;
        // 🆕 Story #046 — resolve the Slot 5 SessionStore from the router. When no
        // router has been registered (legacy / direct instantiation) we wire in
        // NullSessionStore so the engine never calls a real persistence backend.
        // Falls back to "memory" name resolution matches §5.6.4 SPI default so empty
        // yml stays in-memory without the router wiring firing.
        SessionStore sessionStore = sessionStoreRouter != null
            ? sessionStoreRouter.resolve(config.getSessionStore(), config)
            : NullSessionStore.INSTANCE;
        return new LinearTurnEngine(
            promptBuilderRouter.resolve(config.getPrompt().getBuilder(), config),
            llmProviderRouter.resolve(config.getLlm().getProvider(), config),
            toolExecutorRouter.resolve(config.getToolExecutor(), config),
            permissionPolicyRouter.resolve(config.getPermissionPolicy(), config),
            agentToolPool,
            approvalRegistry,
            compactor,
            sessionStore);
    }
}
