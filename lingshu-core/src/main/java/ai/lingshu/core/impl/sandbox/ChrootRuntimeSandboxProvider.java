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
import ai.lingshu.core.spi.Providers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 🆕 Story #028 — default {@link Providers.RuntimeSandboxProvider} implementation
 * (dsh §5.5 v1.5.28 multi-Provider mode plain {@code @Component}).
 *
 * <p>Wires {@code name="chroot"}, matching the previous
 * {@code AgentConfig.Sandbox.runtime} default value and the design doc §6.3
 * template name. Priority {@code 10} so any user-supplied higher-priority provider
 * (e.g. {@code "docker" priority=20}) wins on the same name without surprise.
 *
 * <p>{@link #create(AgentConfig)} returns the {@link DefaultRuntimeSandbox} Spring
 * bean, which is tenant-aware and provides {@code fs()} (chrooted FileSystem),
 * {@code http()} (domain-whitelist HTTP client) and {@code process()} (tenant-aware
 * command whitelist runner). Tests that need a fresh instance can subclass
 * {@link DefaultRuntimeSandbox} directly instead of going through this provider.
 */
@Component
public class ChrootRuntimeSandboxProvider implements Providers.RuntimeSandboxProvider {

    private static final Logger LOG = LoggerFactory.getLogger(ChrootRuntimeSandboxProvider.class);

    private final DefaultRuntimeSandbox defaultRuntimeSandbox;

    @Autowired
    public ChrootRuntimeSandboxProvider(DefaultRuntimeSandbox defaultRuntimeSandbox) {
        this.defaultRuntimeSandbox = defaultRuntimeSandbox;
        LOG.info("registering ChrootRuntimeSandboxProvider name=chroot priority=10");
    }

    @Override public String name() { return "chroot"; }
    @Override public int priority() { return 10; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public RuntimeSandbox create(AgentConfig config) {
        // DefaultRuntimeSandbox holds its own TenantConfigProvider + AgentConfig
        // references from Spring wiring; we simply return that singleton.
        return defaultRuntimeSandbox;
    }
}