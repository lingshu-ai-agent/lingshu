package ai.lingshu.core.runtime;

import lombok.Value;

import java.util.Collections;
import java.util.List;

/**
 * Story #009d — pointer to a remote A2A agent, declared in
 * {@code application.yml} under {@code agent.a2a.remoteAgents[]}.
 *
 * <p>Used by {@link ai.lingshu.a2a.client.RemoteAgentTool#description()} to
 * enumerate configured agents and surface their skills into the tool
 * description (plan.md §5.1 AC-2.2). Future Story OQ-1 may upgrade
 * {@code RemoteAgentTool} into an N-tool Bean mode where each agent becomes
 * its own Spring Bean — that work is out of scope here.</p>
 *
 * <p>Source of truth: {@code agent.a2a.remoteAgents[*]} in
 * {@code application.yml}. Example shape:
 * <pre>{@code
 * agent:
 *   a2a:
 *     remoteAgents:
 *       - name: alice
 *         url: https://alice.example.com
 *         priority: 10
 *         domain-whitelist:
 *           - alice.example.com
 *           - alice-internal.example.com
 *       - name: bob
 *         url: https://bob.example.com
 *         priority: 5
 * }</pre>
 *
 * <p><b>Why {@code lingshu-core}</b>: AgentConfig (also in core) holds the
 * {@code List<AgentRef>} field, so AgentRef must live in core to avoid a
 * reverse dependency from core to a2a-client (CLAUDE.md §5). This class is a
 * pure data POJO; the a2a-client side consumes it but does not extend it.</p>
 *
 * <p>Immutable Lombok {@code @Value} — same convention as the rest of
 * {@code AgentConfig} (dsh §4.12.2).</p>
 *
 * <p><b>🆕 Story #034 — per-remote-agent sandbox domain whitelist</b>. When
 * the field is {@code null} (yml omits {@code domain-whitelist}), callers
 * MUST treat it as {@link Collections#emptyList()} which is the strict-mode
 * default — denying ALL outgoing HTTP from this agent's transport. This
 * mirrors {@code McpServerConfig.domainWhitelist} (Story #033) and
 * {@code WhitelistedHttpClient} (Story #028) semantics.</p>
 */
@Value
public class AgentRef {

    /**
     * Agent name (matches {@code AgentCard.name} on the remote side).
     * Must be non-null and non-empty (validated by Jackson / SnakeYAML
     * binding at startup).
     */
    String name;

    /**
     * Base URL of the remote A2A agent (e.g. {@code "http://alice:8080"}).
     * Optional — when null, the AgentCard is resolved via the locally
     * configured {@link ai.lingshu.core.slot.A2aTransport} (e.g. in-process
     * transport).
     */
    String url;

    /**
     * Hint for ordering when multiple agents advertise overlapping skill
     * ids; higher priority wins. Defaults to {@code 0} when omitted in
     * YAML (Jackson treats missing fields as 0 for primitive ints).
     */
    int priority;

    /**
     * 🆕 Story #034 — per-remote-agent sandbox domain whitelist (strict mode,
     * mirrors {@code McpServerConfig.domainWhitelist} from Story #033).
     *
     * <p>Empty list (or {@code null} — wire-time fallback) denies ALL outgoing
     * HTTP from this agent's {@link ai.lingshu.a2a.client.HttpJsonRpcA2aTransport}.
     * Operators must explicitly populate the field for the agent to function.
     * Case-sensitive exact host match — mirrors
     * {@link ai.lingshu.core.slot.AccessDeniedException}[LINGS-S01] semantics
     * from Story #028.</p>
     *
     * <p>yml key: {@code domain-whitelist} (Jackson kebab-case via the same
     * binding convention used by {@code AgentConfig}). Example:
     * <pre>{@code
     * - name: alice
     *   url: https://alice.example.com
     *   domain-whitelist: [alice.example.com, alice-internal.example.com]
     * }</pre>
     */
    List<String> domainWhitelist;

    /**
     * 🆕 Story #034 — safe accessor that treats {@code null} as
     * {@link Collections#emptyList()} so callers don't have to null-check.
     * Production wiring ({@code HttpJsonRpcA2aTransportFactory.buildByAgentName})
     * uses this to aggregate whitelists across multiple {@link AgentRef}s
     * that share a base URL.
     */
    public List<String> getDomainWhitelistOrEmpty() {
        return domainWhitelist == null ? Collections.<String>emptyList() : domainWhitelist;
    }
}