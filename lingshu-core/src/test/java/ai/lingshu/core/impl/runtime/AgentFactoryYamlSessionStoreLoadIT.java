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
package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.FlowEngine;
import ai.lingshu.core.slot.Compactor;
import ai.lingshu.core.slot.LlmProvider;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.PromptBuilder;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.spi.Providers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #046 — L2 slice test complementing {@link AgentFactoryYamlSessionStoreSaveIT}.
 * Locks the additional YAML-parse contracts that {@link LinearTurnEngineProvider}
 * and the Slot 5 SessionStore wiring rely on, beyond what {@code SaveIT} covers:
 *
 * <ul>
 *   <li>AC-046-4 — The full 9-slot yml shape (all slot names configured
 *       simultaneously) parses with {@code agent.session-store} bound
 *       alongside, locking the contract that demo-product's {@code application.yml}
 *       (which carries ~50 top-level keys interleaved across all 9 slots)
 *       continues to parse correctly even as the project adds slot-specific
 *       keys in later Stories.</li>
 *   <li>AC-046-5 — Unknown session-store names (e.g. {@code redis}) parse
 *       through the YAML layer unchanged — the parse layer does NOT validate
 *       names; that's {@link ai.lingshu.core.impl.router.Routers.SessionStoreRouter#resolve(String, AgentConfig)}'s
 *       job. This separation of concerns is the precondition for Story #014's
 *       multi-Provider Router pattern to work cleanly.</li>
 * </ul>
 */
class AgentFactoryYamlSessionStoreLoadIT {

    private static Path writeTemp(String content) throws IOException {
        Path tmp = Files.createTempFile("agent-yml-sessionstore-load-", ".yml");
        Files.write(tmp, content.getBytes("UTF-8"));
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    /** Minimal AgentFactory with 9 stub routers (incl. Slot 5 SessionStore). */
    private static AgentFactory stubFactory() {
        Providers.LlmProviderProvider llm = new Providers.LlmProviderProvider() {
            @Override public String name() { return "anthropic"; }
            @Override public int priority() { return 10; }
            @Override public String version() { return "1.0.0"; }
            @Override public LlmProvider create(AgentConfig cfg) { return null; }
        };
        Providers.ToolExecutorProvider tool = new Providers.ToolExecutorProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public ToolExecutor create(AgentConfig cfg) { return null; }
        };
        Providers.PermissionPolicyProvider policy = new Providers.PermissionPolicyProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PermissionPolicy create(AgentConfig cfg) { return null; }
        };
        Providers.PromptBuilderProvider prompt = new Providers.PromptBuilderProvider() {
            @Override public String name() { return "default"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public PromptBuilder create(AgentConfig cfg) { return null; }
        };
        Providers.FlowEngineProvider flow = new Providers.FlowEngineProvider() {
            @Override public String name() { return "linear"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public FlowEngine create(AgentConfig cfg) { return null; }
        };
        Providers.MemorySourceProvider memory = new Providers.MemorySourceProvider() {
            @Override public String name() { return "project-claude-md"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public MemorySource create(AgentConfig cfg) { return null; }
        };
        Providers.RuntimeSandboxProvider sandbox = new Providers.RuntimeSandboxProvider() {
            @Override public String name() { return "chroot"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public RuntimeSandbox create(AgentConfig cfg) { return null; }
        };
        Providers.SessionStoreProvider sessionStore = new Providers.SessionStoreProvider() {
            @Override public String name() { return "memory"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public SessionStore create(AgentConfig cfg) { return null; }
        };
        Providers.CompactorProvider compactor = new Providers.CompactorProvider() {
            @Override public String name() { return "truncating"; }
            @Override public int priority() { return 0; }
            @Override public String version() { return "1.0.0"; }
            @Override public Compactor create(AgentConfig cfg) { return null; }
        };
        return new AgentFactory(
            new Routers.LlmProviderRouter(Collections.singletonList(llm)),
            new Routers.ToolExecutorRouter(Collections.singletonList(tool)),
            new Routers.PermissionPolicyRouter(Collections.singletonList(policy)),
            new Routers.PromptBuilderRouter(Collections.singletonList(prompt)),
            new Routers.FlowEngineRouter(Collections.singletonList(flow)),
            new Routers.MemorySourceRouter(Collections.singletonList(memory)),
            new Routers.RuntimeSandboxRouter(Collections.singletonList(sandbox)),
            new Routers.SessionStoreRouter(Collections.singletonList(sessionStore)),
            new Routers.CompactorRouter(Collections.singletonList(compactor)));
    }

    /**
     * AC-046-4 — Full 9-slot yml with session-store configured alongside all
     * other slot names must parse correctly. Locks the contract that as the
     * project adds slot-specific keys in later Stories, the existing
     * {@code session-store} binding is not regressed by parse-order changes in the
     * hand-rolled minimal yaml parser.
     */
    @Test
    @DisplayName("AC-046-4: yamlFullSlotConfig_parsesWithSessionStore")
    void yamlFullSlotConfig_parsesWithSessionStore() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  llm:\n" +
            "    provider: anthropic\n" +
            "    model: claude-3-5-sonnet-latest\n" +
            "  flow-engine: linear\n" +
            "  prompt-builder: default\n" +
            "  tool-executor: default\n" +
            "  permission-policy: default\n" +
            "  sandbox: chroot\n" +
            "  memory-source: project-claude-md\n" +
            "  session-store: file\n" +
            "  compactor: truncating\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        // Slot 5 must be bound alongside every other slot name.
        assertThat(cfg.getSessionStore()).isEqualTo("file");
        assertThat(cfg.getFlowEngine()).isEqualTo("linear");
        assertThat(cfg.getCompactor()).isEqualTo("truncating");
    }

    /**
     * AC-046-5 — The YAML parse layer must NOT validate session-store names.
     * Custom / unknown names (e.g. a hypothetical {@code redis} provider from
     * a future Story) must parse through to {@link AgentConfig#getSessionStore()}
     * unchanged; validation is the Router's responsibility at
     * {@code resolve()} time, not the parser's. This separation lets future
     * plugin Providers add new session-store backends without forcing a
     * parser change.
     */
    @Test
    @DisplayName("AC-046-5: yamlUnknownSessionStoreName_parsesThroughUnvalidated")
    void yamlUnknownSessionStoreName_parsesThroughUnvalidated() throws IOException {
        Path yml = writeTemp(
            "agent:\n" +
            "  session-store: redis\n");
        AgentConfig cfg = stubFactory().loadYamlAndValidate(yml);
        // Parser does NOT validate — unknown name round-trips to AgentConfig.
        // A real Router would throw at resolve() time; the parser contract
        // is intentionally permissive so plugin Providers can add new
        // backends (redis / jdbc / etc.) without parser changes.
        assertThat(cfg.getSessionStore()).isEqualTo("redis");
    }
}