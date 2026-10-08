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
import ai.lingshu.core.slot.AccessDeniedException;
import ai.lingshu.core.slot.RuntimeSandbox;
import ai.lingshu.core.slot.ToolExecutionContext;
import ai.lingshu.core.slot.ToolException;
import ai.lingshu.core.tenant.TenantConfig;
import ai.lingshu.core.tenant.TenantConfigProvider;
import ai.lingshu.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Default {@link RuntimeSandbox} with multi-tenant process whitelist (Story #006 US4).
 *
 * <p>Three Slot 3 sub-capabilities:
 * <ul>
 *   <li>{@link #fs()} — default {@link FileSystems#getDefault} (full host fs; a chroot
 *       variant is a follow-up Story).</li>
 *   <li>{@link #http()} — minimal stub that throws on every call; real HTTP enforcement
 *       belongs to a future Story (out of scope for #006).</li>
 *   <li>{@link #process()} — tenant-aware {@link ProcessRunner} that enforces the
 *       per-tenant command whitelist (US4, FR-002, FR-013, AC-05).</li>
 * </ul>
 *
 * <h2>Tenant-aware whitelist resolution</h2>
 *
 * <p>For each {@code process.run(cmd, ...)} call:
 * <ol>
 *   <li>Snapshot tenant from {@link TenantContext#current()}.</li>
 *   <li>If a tenant is active, look up its whitelist via
 *       {@link TenantConfigProvider#resolve(String)}.</li>
 *   <li>If the tenant is unknown to the provider (returns empty) OR no tenant is
 *       active, fall back to {@link AgentConfig.Sandbox#getCommandWhitelist()}
 *       (the global "single-tenant" whitelist).</li>
 *   <li>If {@code cmd} is not in the resolved whitelist, throw
 *       {@link ToolException.PermissionDeniedException} (FR-002 fail-fast).</li>
 *   <li>Otherwise run via {@link ProcessBuilder} (FR-013).</li>
 * </ol>
 *
 * <p>This keeps the Story #001 {@code chroot}-style fs() work untouched while layering
 * multi-tenant control on top of the process surface only.
 *
 * <h2>Log of "no tenant" calls</h2>
 *
 * <p>When {@code TenantContext.current() == null} and the call falls back to the global
 * whitelist, a WARN is logged once per call (not throttled — production deployments
 * should configure audit log routing per Story #016). This makes it visible during
 * Story #006 E2E tests that the fallback path is exercised.
 *
 * <h2>Invariants</h2>
 * <ul>
 *   <li>I-1: a single {@code run(...)} call either returns a started {@link Process}
 *       or throws; never silently does nothing.</li>
 *   <li>I-2: tenants don't see each other's whitelists — resolution is read-only
 *       against an immutable map held by the provider.</li>
 *   <li>I-3: the {@link TenantContext} stack is not mutated by this class — we only
 *       read {@code current()}.</li>
 *   <li>I-4: an unknown tenantId (provider returns empty) behaves identically to the
 *       "no tenant" fallback (FR-011 turn guard already catches unknown tenants
 *       before this point, but defensive here).</li>
 * </ul>
 */
@Component("defaultRuntimeSandbox")
public class DefaultRuntimeSandbox implements RuntimeSandbox {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultRuntimeSandbox.class);

    private final TenantConfigProvider tenantConfigProvider;
    private final AgentConfig fallbackConfig;

    @Autowired
    public DefaultRuntimeSandbox(TenantConfigProvider tenantConfigProvider,
                                  AgentConfig fallbackConfig) {
        this.tenantConfigProvider = tenantConfigProvider;
        this.fallbackConfig = fallbackConfig;
    }

    @Override
    public FileSystem fs() {
        // 🆕 Story #028 — chrooted FileSystem. When workingDirectory is configured,
        // wrap the default FS with a ChrootedFileSystem that enforces the prefix
        // boundary. When null, fall back to the unconfined default FS (legacy
        // behaviour — #001 demos never set working-directory).
        Path rootDir = (fallbackConfig != null && fallbackConfig.getSandbox() != null)
            ? fallbackConfig.getSandbox().getWorkingDirectory()
            : null;
        if (rootDir == null) {
            return FileSystems.getDefault();
        }
        return new ChrootedFileSystem(FileSystems.getDefault(), rootDir);
    }

    @Override
    public ToolExecutionContext.NetworkClient http() {
        // 🆕 Story #028 — domain-whitelist enforcing HTTP client. Empty whitelist
        // results in every request being denied with [LINGS-S01].
        List<String> whitelist = (fallbackConfig != null
            && fallbackConfig.getSandbox() != null
            && fallbackConfig.getSandbox().getDomainWhitelist() != null)
            ? fallbackConfig.getSandbox().getDomainWhitelist()
            : Collections.<String>emptyList();
        return new WhitelistedHttpClient(whitelist);
    }

    @Override
    public ProcessRunner process() {
        return new TenantAwareProcessRunner(tenantConfigProvider, fallbackConfig);
    }

    /**
     * Process runner that resolves the whitelist per-tenant (US4 / FR-013).
     *
     * <p>Concrete-class form so the outer {@link DefaultRuntimeSandbox} can stay
     * a thin Spring component; an anonymous inner class would also work but adds
     * nesting without value.
     */
    static final class TenantAwareProcessRunner implements ProcessRunner {

        private final TenantConfigProvider tenantConfigProvider;
        private final AgentConfig fallbackConfig;

        TenantAwareProcessRunner(TenantConfigProvider tenantConfigProvider,
                                 AgentConfig fallbackConfig) {
            this.tenantConfigProvider = tenantConfigProvider;
            this.fallbackConfig = fallbackConfig;
        }

        @Override
        public Process run(String command, List<String> args, Path cwd) throws IOException {
            if (command == null || command.isEmpty()) {
                throw new IllegalArgumentException("command must not be null/empty");
            }
            String tid = TenantContext.current();
            List<String> whitelist = resolveWhitelist(tid);

            if (!whitelist.contains(command)) {
                throw new ToolException.PermissionDeniedException(
                    "command '" + command + "' not in "
                        + (tid != null ? ("tenant '" + tid + "' ") : "global ")
                        + "whitelist " + whitelist);
            }

            // Build the full command line — first element is the binary, the rest are args.
            List<String> fullCommand = new ArrayList<>(args.size() + 1);
            fullCommand.add(command);
            fullCommand.addAll(args);
            ProcessBuilder pb = new ProcessBuilder(fullCommand);
            if (cwd != null) {
                pb.directory(cwd.toFile());
            } else if (fallbackConfig != null
                && fallbackConfig.getSandbox() != null
                && fallbackConfig.getSandbox().getWorkingDirectory() != null) {
                pb.directory(fallbackConfig.getSandbox().getWorkingDirectory().toFile());
            }
            pb.redirectErrorStream(false);
            return pb.start();
        }

        /**
         * Resolve the active whitelist:
         * <ul>
         *   <li>Tenant active + known → tenant's whitelist.</li>
         *   <li>Tenant active + unknown → global whitelist (defensive fallback).</li>
         *   <li>No tenant → global whitelist + WARN log (FR-013 single-tenant mode).</li>
         * </ul>
         */
        private List<String> resolveWhitelist(String tid) {
            if (tid != null && tenantConfigProvider != null) {
                TenantConfig tc = tenantConfigProvider.resolve(tid).orElse(null);
                if (tc != null && tc.getSandbox() != null
                    && tc.getSandbox().getCommandWhitelist() != null) {
                    return tc.getSandbox().getCommandWhitelist();
                }
            }
            if (tid == null) {
                LOG.warn("DefaultRuntimeSandbox: no TenantContext active — "
                    + "falling back to global commandWhitelist (single-tenant mode)");
            } else {
                LOG.warn("DefaultRuntimeSandbox: tenant '{}' not in provider — "
                    + "falling back to global commandWhitelist", tid);
            }
            if (fallbackConfig == null
                || fallbackConfig.getSandbox() == null
                || fallbackConfig.getSandbox().getCommandWhitelist() == null) {
                return Collections.emptyList();
            }
            return fallbackConfig.getSandbox().getCommandWhitelist();
        }
    }
}
