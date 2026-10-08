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
package ai.lingshu.a2a.client;

import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.AgentRef;
import ai.lingshu.core.spi.Providers;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Story #009c (originally) — SPI registration for {@link HttpJsonRpcA2aTransportProvider}.
 *
 * <p><b>🆕 Story #009e — slimmed down to one Bean</b>: this class now
 * exposes ONLY the {@code a2aTransportProvider_http-jsonrpc-1.0.0} transport
 * provider. The previously-bundled {@code remoteAgentTool} +
 * {@code remoteAgentSchemaBuilder} {@code @Bean} methods have been moved
 * into a brand-new {@link RemoteAgentToolAutoConfiguration} so that all
 * three transports (grpc / in-process / http-jsonrpc) share a single,
 * transport-independent wiring.</p>
 *
 * <p>Bean name follows §5.4 unique-name convention:
 * {@code "a2aTransportProvider_<name>"} → {@code "a2aTransportProvider_http-jsonrpc-1.0.0"},
 * distinct from {@link GrpcA2aTransportAutoConfiguration}'s
 * {@code "a2aTransportProvider_grpc-1.0.0"} and
 * {@link InProcessA2aTransportAutoConfiguration}'s
 * {@code "a2aTransportProvider_in-process-1.0.0"} — all three coexist in
 * the same JVM under the v1.5.28 multi-Provider mode (§5.5).</p>
 *
 * <p><b>🆕 Story #034 — {@link HttpJsonRpcA2aTransportFactory} bean</b>:
 * builds {@link HttpJsonRpcA2aTransport} instances with unioned sandbox
 * domain-whitelist across all configured {@link AgentRef}s. Mirrors MCP #033
 * {@code McpServerConfig.domainWhitelist} (per-server) but aggregated across
 * the list of remote agents since A2A typically serves multiple agents
 * through one base URL.</p>
 */
@AutoConfiguration
public class HttpJsonRpcA2aTransportAutoConfiguration {

    @Bean(name = "a2aTransportProvider_http-jsonrpc-1.0.0")
    public Providers.A2aTransportProvider httpJsonRpcA2aTransportProvider() {
        return new HttpJsonRpcA2aTransportProvider();
    }

    /**
     * 🆕 Story #034 — factory bean used by {@link RemoteAgentToolAutoConfiguration#remoteAgentTool()}
     * to construct the per-JVM {@link HttpJsonRpcA2aTransport} singleton with
     * the union of all configured {@link AgentRef#getDomainWhitelist()} entries.
     *
     * <p>Bean name {@code a2aTransportFactory_http-jsonrpc} is unique per
     * transport variant — future transports (e.g. gRPC, when it adds HTTP/2
     * egress) will register their own factory under a distinct name.</p>
     */
    @Bean(name = "a2aTransportFactory_http-jsonrpc")
    public HttpJsonRpcA2aTransportFactory httpJsonRpcA2aTransportFactory(
            AgentConfig cfg,
            com.fasterxml.jackson.databind.ObjectMapper json,
            AgentCardCache cardCache) {
        return new HttpJsonRpcA2aTransportFactory(cfg, json, cardCache);
    }

    /**
     * 🆕 Story #034 — builds {@link HttpJsonRpcA2aTransport} with sandbox
     * domain-whitelist aggregated from all configured {@link AgentRef}s.
     *
     * <p><b>Design choice (scope-minimal)</b>: produces a single transport
     * instance per factory invocation, sharing one baseUrl across all
     * configured remote agents. This matches the typical A2A deployment
     * topology (one A2A server hosts many agents) and keeps the
     * {@link RemoteAgentTool} API surface unchanged. Future OQ-Future
     * work (N-tool Bean mode — see {@code AgentRef} Javadoc OQ-1) may
     * upgrade this to {@code Map<String, HttpJsonRpcA2aTransport>}.</p>
     *
     * <p><b>Whitelist semantics</b>: union of every
     * {@link AgentRef#getDomainWhitelistOrEmpty()} (deduped via
     * {@link HashSet}). Empty union → empty list → deny-all strict mode
     * (mirrors {@code McpServerConfig.domainWhitelist} default from
     * Story #033).</p>
     */
    public static class HttpJsonRpcA2aTransportFactory {

        private final AgentConfig cfg;
        private final com.fasterxml.jackson.databind.ObjectMapper json;
        private final AgentCardCache cardCache;

        public HttpJsonRpcA2aTransportFactory(AgentConfig cfg,
                                             com.fasterxml.jackson.databind.ObjectMapper json,
                                             AgentCardCache cardCache) {
            this.cfg = cfg;
            this.json = json;
            this.cardCache = cardCache;
        }

        /**
         * Build the singleton {@link HttpJsonRpcA2aTransport} for this JVM.
         * Reads {@code cfg.a2a.httpBaseUrl} (default {@code "http://localhost:8080"})
         * and the union of all configured {@link AgentRef#getDomainWhitelist()}
         * entries (default empty).
         */
        public HttpJsonRpcA2aTransport build() {
            String baseUrl = "http://localhost:8080";  // matches A2a.defaults()
            Set<String> whitelistUnion = new HashSet<>();
            if (cfg != null && cfg.getA2a() != null) {
                if (cfg.getA2a().getHttpBaseUrl() != null
                    && !cfg.getA2a().getHttpBaseUrl().isEmpty()) {
                    baseUrl = cfg.getA2a().getHttpBaseUrl();
                }
                if (cfg.getA2a().getRemoteAgents() != null) {
                    for (AgentRef ref : cfg.getA2a().getRemoteAgents()) {
                        if (ref == null) {
                            continue;
                        }
                        whitelistUnion.addAll(ref.getDomainWhitelistOrEmpty());
                    }
                }
            }
            return new HttpJsonRpcA2aTransport(
                baseUrl,
                json,
                cardCache,
                Duration.ofSeconds(30),  // matches A2a.defaults().callTimeout
                new ArrayList<>(whitelistUnion));
        }

        /**
         * Test-only accessor — exposes the unioned whitelist (deduped,
         * order undefined) for AC-3.1 verification.
         */
        public List<String> previewWhitelistUnion() {
            Set<String> union = new HashSet<>();
            if (cfg != null && cfg.getA2a() != null
                && cfg.getA2a().getRemoteAgents() != null) {
                for (AgentRef ref : cfg.getA2a().getRemoteAgents()) {
                    if (ref != null) {
                        union.addAll(ref.getDomainWhitelistOrEmpty());
                    }
                }
            }
            return Collections.unmodifiableList(new ArrayList<>(union));
        }
    }
}