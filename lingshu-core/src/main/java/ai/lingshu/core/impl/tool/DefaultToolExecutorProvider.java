package ai.lingshu.core.impl.tool;

import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.ToolExecutor;
import ai.lingshu.core.slot.ToolRegistry;
import ai.lingshu.core.spi.Providers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Default Provider for Slot 2 outer half ({@link ToolExecutor}).
 *
 * <p>Wires {@link DefaultToolExecutor} with the resolved {@link PermissionPolicy}
 * from the same config + the shared {@link ToolRegistry} singleton. Story #002+
 * add tool registration via Spring auto-discovery; Story #019 lifts the tool
 * registry out of the executor itself into a dedicated {@code @Component}
 * singleton so {@code LocalToolsAutoConfiguration} can wire Tools via the SPI
 * boundary (no longer needs to depend on the concrete {@code DefaultToolExecutor}).
 */
@Component
public class DefaultToolExecutorProvider implements Providers.ToolExecutorProvider {

    @Override public String name() { return "default"; }

    @Override public int priority() { return 0; }

    /** 🆕 Story #003 — contract version. */
    @Override public String version() { return "1.0.0"; }

    private final Routers.PermissionPolicyRouter permissionPolicyRouter;
    private final ToolRegistry toolRegistry;

    @Autowired
    public DefaultToolExecutorProvider(
            Routers.PermissionPolicyRouter permissionPolicyRouter,
            ToolRegistry toolRegistry) {
        this.permissionPolicyRouter = permissionPolicyRouter;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public ToolExecutor create(AgentConfig config) {
        PermissionPolicy policy = permissionPolicyRouter.resolve(config.getSandbox().getPolicy(), config);
        return new DefaultToolExecutor(policy, toolRegistry);
    }
}