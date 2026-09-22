package ai.lingshu.core.runtime;

import lombok.Value;

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
 *         url: http://alice:8080
 *         priority: 10
 *       - name: bob
 *         url: http://bob:8080
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
}