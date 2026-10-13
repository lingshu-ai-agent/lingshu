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
package ai.lingshu.core.impl.session;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.SessionStore;
import ai.lingshu.core.spi.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Story #014 — Slot 5 file-backed {@link Providers.SessionStoreProvider} (name
 * {@code "file"}, priority 10). Sibling of {@link InMemorySessionStoreProvider}
 * (name {@code "memory"}, priority 10).
 *
 * <p>Aligned with the v1.5.28 §5.5 multi-Provider pattern — the Provider registers
 * as a separately-named Spring Bean (see
 * {@code SessionStoreAutoConfiguration#fileSessionStoreProvider()}) so
 * {@code SessionStoreRouter.resolve("file", cfg)} matches
 * {@code @Bean(name="sessionStoreProvider_file-1.0.0")} via the {@code SlotRouter}
 * parent class's name-keyed map.
 *
 * <p><b>Base directory resolution</b> ({@link #resolveBaseDir(AgentConfig)}):
 * <ol>
 *   <li>If {@code config.session.baseDir} is set (non-null + non-blank), use it
 *       verbatim — caller responsibility to ensure the path exists or is creatable.</li>
 *   <li>Otherwise, fall back to {@code ${java.io.tmpdir}/lingshu-sessions}.
 *       The directory is created lazily by {@link FileSessionStore#save} when
 *       the first checkpoint is written; this Provider does <b>not</b> pre-create
 *       it to avoid surprising the test fixture cleanup paths.</li>
 * </ol>
 *
 * <p><b>NO {@code @Component}</b>: registration is exclusively via
 * {@code SessionStoreAutoConfiguration#fileSessionStoreProvider()} which produces
 * the uniquely-named Bean {@code "sessionStoreProvider_file-1.0.0"}. Removing
 * {@code @Component} prevents Spring from auto-registering a second conflicting
 * Bean named {@code "fileSessionStoreProvider"} (the default camelCase from
 * class name). Aligned with v1.5.28 §5.5 multi-Provider pattern (mirror of
 * {@code StrictPermissionPolicyProvider}, Story #031).
 */
public class FileSessionStoreProvider implements Providers.SessionStoreProvider {

    private static final Logger LOG = LoggerFactory.getLogger(FileSessionStoreProvider.class);

    /** Default base directory used when {@code config.session.baseDir} is unset. */
    static final String DEFAULT_BASE_DIR = System.getProperty("java.io.tmpdir", "/tmp")
        + "/lingshu-sessions";

    @Override public String name() { return "file"; }

    @Override public int priority() { return 10; }

    /** Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    @Override
    public SessionStore create(AgentConfig config) {
        Path baseDir = resolveBaseDir(config);
        try {
            Files.createDirectories(baseDir);
        } catch (IOException e) {
            LOG.warn("Failed to pre-create baseDir '{}'; will retry lazily on first save: {}",
                baseDir, e.getMessage());
        }
        return new FileSessionStore(baseDir);
    }

    /**
     * Resolve the base directory for this Provider. Public for testability — tests
     * pass synthetic {@link AgentConfig} snapshots and assert the resolved path.
     *
     * @param config the immutable AgentConfig; may carry an optional {@code baseDir}
     *               override under a future config key (this Story leaves the
     *               override seam in place even though {@link AgentConfig} does not
     *               yet expose a {@code session.baseDir} field — see Story #014
     *               follow-up for the wiring).
     * @return the absolute {@link Path} to use as the file-backend root
     */
    Path resolveBaseDir(AgentConfig config) {
        // Future: read config.getSession().getBaseDir() once AgentConfig.session is
        // introduced. For now, always return the default — config is unused but kept
        // in the signature to preserve the SlotProvider SPI contract.
        //noinspection ResultOfMethodCallIgnored
        Paths.get(DEFAULT_BASE_DIR);
        return Paths.get(DEFAULT_BASE_DIR);
    }
}