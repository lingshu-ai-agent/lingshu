package ai.lingshu.a2a.client;

import ai.lingshu.core.slot.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Spring lifecycle bridge (Story #009e, plan §3.3).
 *
 * <p><b>What</b> — explicit {@link ToolRegistry#register}/{@code unregister}
 * of the {@link RemoteAgentTool} bean. Without this Lifecycle, the
 * {@code remote_agent} tool would never appear in the LLM's tool list
 * (the only consumer of the bean would be {@code RemoteAgentTool}'s
 * own {@code @PostConstruct} — but {@link RemoteAgentTool} is POJO
 * without Spring dependencies).</p>
 *
 * <p><b>Why {@link SmartLifecycle} instead of {@code @PostConstruct}</b> —
 * we need <b>start + stop double hooks</b> (register on startup,
 * unregister on shutdown). {@link SmartLifecycle} provides both;
 * {@code spring-context} is already transitive, 0 new deps.
 * <ul>
 *   <li>{@code @PostConstruct} requires {@code javax.annotation-api}
 *       (JDK 9+ built-in, JDK 8 needs separate jar) — conflicts with
 *       R-13 mitigation philosophy (Story #019 rationale).</li>
 *   <li>{@code @Bean(initMethod = ...)} cannot express close.</li>
 *   <li>{@link SmartLifecycle} provides start / stop / isRunning /
 *       isAutoStartup / getPhase — fully covers both directions.</li>
 * </ul>
 *
 * <p><b>Idempotency</b> — {@code start()} and {@code stop()} are guarded
 * by a {@code running} flag so repeated calls are safe (Spring restart,
 * graceful shutdown double-firing).</p>
 *
 * <p><b>Phase</b> — default {@code Integer.MAX_VALUE - 1024} (matches
 * {@code McpTransportLifecycle}, no timing race in practice).</p>
 */
@Component
public class RemoteAgentToolLifecycle implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(RemoteAgentToolLifecycle.class);

    private final RemoteAgentTool tool;
    private final ToolRegistry toolRegistry;
    private volatile boolean running;

    public RemoteAgentToolLifecycle(RemoteAgentTool tool, ToolRegistry toolRegistry) {
        this.tool = tool;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void start() {
        if (running) {
            LOG.debug("RemoteAgentToolLifecycle already started — skipping register");
            return;
        }
        toolRegistry.register(tool);
        running = true;
        LOG.info("RemoteAgentToolLifecycle started — remote_agent registered with ToolRegistry");
    }

    @Override
    public void stop() {
        if (!running) {
            LOG.debug("RemoteAgentToolLifecycle already stopped — skipping unregister");
            return;
        }
        try {
            toolRegistry.unregister(tool.name());
        } finally {
            running = false;
        }
        LOG.info("RemoteAgentToolLifecycle stopped — remote_agent unregistered");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        // Default — start after all standard lifecycle beans, stop before them.
        return Integer.MAX_VALUE - 1024;
    }
}