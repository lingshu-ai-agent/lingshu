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
package ai.lingshu.core.impl.sandbox;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolException;
import ai.lingshu.core.tenant.TenantConfig;
import ai.lingshu.core.tenant.TenantConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * Story #028 — L1/L2 tests for {@link DefaultRuntimeSandbox} (dsh §6.3 chroot runtime
 * sandbox tenant-aware process whitelist + chrooted FS + domain-whitelist HTTP).
 *
 * <p>AC-NN-4 contract:
 * <ol>
 *   <li>{@code process().run("unwhitelisted")} throws {@link ToolException.PermissionDeniedException}
 *       (FR-002 fail-fast)</li>
 *   <li>{@code process().run("whitelisted")} gets through the whitelist gate and reaches
 *       {@link ProcessBuilder}</li>
 *   <li>{@code fs()} returns a {@link ChrootedFileSystem} when {@code workingDirectory}
 *       is set, otherwise the default {@link FileSystems#getDefault}</li>
 *   <li>{@code http()} returns a {@link WhitelistedHttpClient} wired with the configured
 *       domain whitelist</li>
 * </ol>
 */
class DefaultRuntimeSandboxTest {

    /** Minimal stub provider — no tenant resolution, returns Optional.empty() for all. */
    private static final TenantConfigProvider EMPTY_PROVIDER = new TenantConfigProvider() {
        @Override public String name() { return "empty-stub"; }
        @Override public int priority() { return 0; }
        @Override public Optional<TenantConfig> resolve(String tenantId) { return Optional.empty(); }
        @Override public List<String> listTenantIds() { return Collections.emptyList(); }
    };

    @Test
    @DisplayName("AC-NN-4: process().run(\"evil-cmd\") throws PermissionDeniedException")
    void processUnwhitelistedCmdThrows() {
        AgentConfig cfg = minimalConfig(Arrays.asList("ls", "cat"), null, null);
        DefaultRuntimeSandbox sb = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        RuntimeSandbox.ProcessRunner runner = sb.process();
        assertThatThrownBy(() -> runner.run("rm-rf", null, null))
            .isInstanceOf(ToolException.PermissionDeniedException.class)
            .hasMessageContaining("rm-rf")
            .hasMessageContaining("not in")
            .hasMessageContaining("whitelist");
    }

    @Test
    @DisplayName("AC-NN-4: process().run(\"ls\") passes whitelist gate (reaches ProcessBuilder.start)")
    void processWhitelistedCmdReachesProcessBuilder(@TempDir Path workDir) {
        AgentConfig cfg = minimalConfig(Arrays.asList("ls"), workDir, null);
        DefaultRuntimeSandbox sb = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        RuntimeSandbox.ProcessRunner runner = sb.process();
        // "ls" passes the whitelist gate; ProcessBuilder.start() may throw IOException
        // on exotic platforms but the IMPORTANT thing is that NO PermissionDeniedException
        // is thrown — meaning the whitelist check passed.
        try {
            Process p = runner.run("ls", Arrays.asList("/tmp"), workDir);
            // If we get here, ls actually ran — clean up
            p.destroy();
        } catch (java.io.IOException expectedOnPathFailure) {
            // Acceptable — whitelist gate passed, ProcessBuilder was invoked
            assertThat(expectedOnPathFailure).isNotNull();
        } catch (ToolException.PermissionDeniedException denied) {
            // This is the FAILURE mode — the whitelist should NOT have denied "ls"
            throw new AssertionError(
                "AC-NN-4: 'ls' is in the whitelist but got denied: "
                    + denied.getMessage(), denied);
        }
    }

    @Test
    @DisplayName("AC-NN-4: fs() with workingDirectory returns ChrootedFileSystem")
    void fsReturnsChrootedFileSystemWhenWorkingDirSet(@TempDir Path workDir) {
        AgentConfig cfg = minimalConfig(null, workDir, null);
        DefaultRuntimeSandbox sb = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        FileSystem fs = sb.fs();

        assertThat(fs).isInstanceOf(ChrootedFileSystem.class);
        ChrootedFileSystem chroot = (ChrootedFileSystem) fs;
        assertThat(chroot.getRootDir())
            .isEqualTo(workDir.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("AC-NN-4: fs() without workingDirectory returns default FileSystem")
    void fsReturnsDefaultWhenWorkingDirNull() {
        AgentConfig cfg = minimalConfig(null, null, null);
        DefaultRuntimeSandbox sb = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        FileSystem fs = sb.fs();

        assertThat(fs).isSameAs(FileSystems.getDefault());
    }

    @Test
    @DisplayName("AC-NN-4: http() returns WhitelistedHttpClient wired with domainWhitelist")
    void httpReturnsWhitelistedClientWiredWithConfig() {
        AgentConfig cfg = minimalConfig(null, null, Arrays.asList("api.openai.com"));
        DefaultRuntimeSandbox sb = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        assertThat(sb.http()).isInstanceOf(WhitelistedHttpClient.class);
        WhitelistedHttpClient http = (WhitelistedHttpClient) sb.http();
        assertThat(http.getDomainWhitelist()).containsExactly("api.openai.com");

        // Sanity: a non-listed host still throws [LINGS-S01]
        assertThatThrownBy(() -> http.check("https://evil.example.com"))
            .isInstanceOf(ai.lingshu.core.slot.AccessDeniedException.class)
            .hasMessageStartingWith("[LINGS-S01]");
    }

    @Test
    @DisplayName("AC-NN-4: http() with null domainWhitelist returns client that denies everything")
    void httpReturnsDeniesAllWhenWhitelistNull() {
        AgentConfig cfg = minimalConfig(null, null, null);
        DefaultRuntimeSandbox sb = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        WhitelistedHttpClient http = (WhitelistedHttpClient) sb.http();
        assertThat(http.getDomainWhitelist()).isEmpty();
    }

    @Test
    @DisplayName("AC-NN-4: process().run(\"\") throws IllegalArgumentException")
    void processRejectsEmptyCommand() {
        AgentConfig cfg = minimalConfig(Arrays.asList("ls"), null, null);
        DefaultRuntimeSandbox sb = new DefaultRuntimeSandbox(EMPTY_PROVIDER, cfg);

        RuntimeSandbox.ProcessRunner runner = sb.process();
        assertThatThrownBy(() -> runner.run("", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("command");
        assertThatThrownBy(() -> runner.run(null, null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("command");
    }

    /**
     * Minimal {@link AgentConfig} with optional overrides for the Sandbox fields relevant
     * to {@link DefaultRuntimeSandbox}. All other fields use their defaults / nulls —
     * we only exercise the Sandbox branch of {@code DefaultRuntimeSandbox.fs/http/process}.
     */
    private static AgentConfig minimalConfig(List<String> commandWhitelist,
                                             Path workingDirectory,
                                             List<String> domainWhitelist) {
        AgentConfig.Sandbox sandbox = new AgentConfig.Sandbox(
            null,                          // policy
            null,                          // runtime
            workingDirectory,              // workingDirectory
            commandWhitelist,              // commandWhitelist
            domainWhitelist);              // domainWhitelist
        return new AgentConfig(
            "linear",                                          // flowEngine
            new AgentConfig.Llm("anthropic", "test-model", null, null),  // llm
            new AgentConfig.Prompt("default", Collections.<String>emptyList(), null),  // prompt
            "default",                                         // toolExecutor
            sandbox,                                           // sandbox
            "default",                                         // compactor
            "default",                                         // sessionStore
            null,                                              // delegate
            null,                                              // mcp
            null,                                              // skills
            1,                                                 // toolParallelism
            5,                                                 // toolTimeoutSeconds
            0,                                                 // approvalTimeoutSeconds
            0,                                                 // turnTimeoutSeconds
            0,                                                 // llmTimeoutSeconds
            10,                                                // reactMaxSteps
            AgentConfig.Identity.defaults(),                   // identity
            AgentConfig.Instructions.empty(),                  // instructions
            AgentConfig.Memory.defaults(),                     // memory
            null,                                              // a2aTransport
            null,                                              // tenants
            AgentConfig.A2a.defaults(),                        // a2a
            AgentConfig.CompactorConfig.defaults(),            // compactorConfig
                        AgentConfig.ToolsConfig.defaults(), "default",
            16,		// 🆕 Story #044 — maxConcurrentTurns
            32);		// 🆕 Story #044 — maxConcurrentQueueDepth
    }
}