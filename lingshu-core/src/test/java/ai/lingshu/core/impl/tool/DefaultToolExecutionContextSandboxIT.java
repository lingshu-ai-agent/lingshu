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
package ai.lingshu.core.impl.tool;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.runtime.DefaultTurnContext;
import ai.lingshu.core.impl.sandbox.ChrootedFileSystem;
import ai.lingshu.core.impl.sandbox.DefaultRuntimeSandbox;
import ai.lingshu.core.impl.sandbox.WhitelistedHttpClient;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.tenant.TenantConfig;
import ai.lingshu.core.tenant.TenantConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.Subscriber;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story #028 — L2 integration tests for {@link DefaultToolExecutionContext} sandbox wiring
 * (dsh §6.3 ToolExecutionContext → RuntimeSandbox delegation).
 *
 * <p>AC-NN-5 / AC-NN-6 / AC-NN-7 contract:
 * <ol>
 *   <li>AC-NN-5: With a resolved {@link DefaultRuntimeSandbox}, {@code fs()} returns the
 *       sandbox's chrooted FileSystem (not the JVM default)</li>
 *   <li>AC-NN-6: With a resolved sandbox, {@code http()} returns the sandbox's
 *       {@link WhitelistedHttpClient} (not the legacy PassThroughHttp stub)</li>
 *   <li>AC-NN-7: Legacy 1-arg {@link DefaultToolExecutionContext#DefaultToolExecutionContext(TurnContext)}
 *       ctor still falls back to default FS + PassThroughHttp stub (back-compat for tests
 *       that don't wire a real sandbox)</li>
 *   <li>AC-NN-7 (cont): Each sandbox method (fs / http) is delegated through the
 *       boundary — boundary bypass would void the dsh §4.10.1 hard rule 2 pipeline</li>
 * </ol>
 */
class DefaultToolExecutionContextSandboxIT {

    private static final TenantConfigProvider EMPTY_PROVIDER = new TenantConfigProvider() {
        @Override public String name() { return "empty-stub"; }
        @Override public int priority() { return 0; }
        @Override public Optional<TenantConfig> resolve(String tid) { return Optional.empty(); }
        @Override public List<String> listTenantIds() { return Collections.emptyList(); }
    };

    @Test
    @DisplayName("AC-NN-5: 2-arg ctor + DefaultRuntimeSandbox.fs() returns chrooted FS, NOT default")
    void fsDelegatesToSandbox(@TempDir Path workDir) {
        AgentConfig cfg = minimalConfig(null, workDir, null);
        DefaultRuntimeSandbox sandbox = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(stubTurn(cfg), sandbox);

        FileSystem fs = ctx.fs();
        assertThat(fs).isInstanceOf(ChrootedFileSystem.class);
        assertThat(fs).isNotSameAs(FileSystems.getDefault());
    }

    @Test
    @DisplayName("AC-NN-6: 2-arg ctor + DefaultRuntimeSandbox.http() returns WhitelistedHttpClient, NOT PassThroughHttp")
    void httpDelegatesToSandbox() {
        AgentConfig cfg = minimalConfig(null, null, Arrays.asList("api.openai.com"));
        DefaultRuntimeSandbox sandbox = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(stubTurn(cfg), sandbox);

        // Must be the sandbox's WhitelistedHttpClient, NOT the legacy PassThroughHttp stub
        assertThat(ctx.http()).isInstanceOf(WhitelistedHttpClient.class);
        // Sanity: an evil domain is denied with [LINGS-S01] (PassThroughHttp stub would
        // throw UnsupportedOperationException instead)
        assertThatThrownBy(() -> ctx.http().get("https://evil.example.com"))
            .isInstanceOf(ai.lingshu.core.slot.AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]");
    }

    @Test
    @DisplayName("AC-NN-7: legacy 1-arg ctor falls back to default FS (back-compat)")
    void legacyCtorFallsBackToDefaultFs() {
        AgentConfig cfg = minimalConfig(null, null, null);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(stubTurn(cfg));

        assertThat(ctx.fs()).isSameAs(FileSystems.getDefault());
    }

    @Test
    @DisplayName("AC-NN-7: legacy 1-arg ctor http() returns PassThroughHttp stub (throws UOE)")
    void legacyCtorHttpFallsBackToPassThroughStub() {
        AgentConfig cfg = minimalConfig(null, null, null);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(stubTurn(cfg));

        // PassThroughHttp throws UnsupportedOperationException on every call
        assertThatThrownBy(() -> ctx.http().get("https://api.openai.com"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("Story");
    }

    @Test
    @DisplayName("AC-NN-5: 2-arg ctor null turnCtx throws IllegalArgumentException")
    void ctorRejectsNullTurnCtx() {
        DefaultRuntimeSandbox sandbox = new DefaultRuntimeSandbox(EMPTY_PROVIDER,
            minimalConfig(null, null, null));

        assertThatThrownBy(() -> new DefaultToolExecutionContext(null, sandbox))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("turnCtx");
    }

    @Test
    @DisplayName("AC-NN-5/6/7: null sandbox in 2-arg ctor falls back to default (defensive)")
    void nullSandboxFallsBackToDefault() {
        AgentConfig cfg = minimalConfig(null, null, null);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(stubTurn(cfg), null);

        assertThat(ctx.fs()).isSameAs(FileSystems.getDefault());
        // http() falls back to PassThroughHttp stub
        assertThatThrownBy(() -> ctx.http().get("https://api.openai.com"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("AC-NN-5: workingDirectory() returns configured sandbox workingDirectory")
    void workingDirectoryDelegates(@TempDir Path workDir) {
        AgentConfig cfg = minimalConfig(null, workDir, null);
        DefaultTurnContext turn = DefaultTurnContext.createWithBroadcast(
            stubSession(), cfg, (Subscriber<AgentEvent>) null, "hi", null);
        DefaultToolExecutionContext ctx = new DefaultToolExecutionContext(turn, null);

        assertThat(ctx.workingDirectory()).isEqualTo(workDir);
    }

    // ── helpers ───────────────────────────────────────────

    private static AgentConfig minimalConfig(List<String> commandWhitelist,
                                             Path workingDirectory,
                                             List<String> domainWhitelist) {
        return new AgentConfig(
            "linear",
            new AgentConfig.Llm("anthropic", "test-model", null, null),
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),
            "default",
            new AgentConfig.Sandbox(null, null, workingDirectory, commandWhitelist, domainWhitelist),
            "default",
            "default",
            null, null, null,
            1, 5, 0, 0, 0, 10,
            AgentConfig.Identity.defaults(),
            AgentConfig.Instructions.empty(),
            AgentConfig.Memory.defaults(),
            null, null,
            AgentConfig.A2a.defaults(),
            AgentConfig.CompactorConfig.defaults(),
                        AgentConfig.ToolsConfig.defaults(), "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }

    private static Session stubSession() {
        Session s = new Session() {
            @Override public String id() { return "test-session"; }
            @Override public List<Message> history() { return Collections.emptyList(); }
            @Override public Session fork(String subagentType) { return this; }
            @Override public ai.lingshu.core.message.Checkpoint checkpoint() {
                return new ai.lingshu.core.message.Checkpoint(
                    "test-session",
                    Collections.<Message>emptyList(),
                    Collections.<String, String>emptyMap(),
                    java.time.Instant.EPOCH);
            }
        };
        return s;
    }

    private static TurnContext stubTurn(AgentConfig cfg) {
        return DefaultTurnContext.createWithBroadcast(
            stubSession(), cfg, (Subscriber<AgentEvent>) null, "hi", null);
    }
}