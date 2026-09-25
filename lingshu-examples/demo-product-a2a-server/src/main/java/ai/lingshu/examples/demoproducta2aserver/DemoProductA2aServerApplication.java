package ai.lingshu.examples.demoproducta2aserver;

import ai.lingshu.a2a.server.A2aServerAutoConfiguration;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.AgentConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #025b demo — companion to {@code demo-product} running on port 9090.
 *
 * <p>Exposes a single A2A agent named {@code translator} that offers one skill
 * ({@code translate}) backed by the {@link TranslateTools#translate} {@code @AgentTool}
 * method. Paired with {@code demo-product} (port 8080), the chat UI can
 * invoke the {@code remote_agent} tool to translate text across JVM
 * boundaries.
 *
 * <p><b>Why we exclude {@link A2aServerAutoConfiguration}</b> —
 * lingshu's stock {@code A2aServer.RpcDispatcherHandler} handles
 * {@code message/send}, {@code tasks/get}, and {@code tasks/cancel}, but does
 * <b>not</b> dispatch the actual skill execution to a local {@code ToolRegistry}.
 * It echoes back a synthetic taskId with no real translation. See
 * {@code A2aServer.java:330-504} (Story #009c stub) for the gap; fixing it in
 * core would require resolving TaskExecutionContext ownership — out of scope
 * for Story #025b. The demo therefore rolls its own {@link DemoA2aServer}
 * (~150 lines) that excludes the stock autoconfig and wires {@code ToolRegistry}
 * directly into the JSON-RPC dispatcher.
 *
 * <p><b>What stays the same</b> — the on-wire format matches
 * {@code HttpJsonRpcA2aTransport}'s contract (params.{agentName, skill, inputJson})
 * so the demo-product client doesn't know the difference. The
 * {@code AgentCard.skills} array is populated by scanning {@code ToolRegistry}
 * on startup, just like {@code RemoteAgentSchemaBuilder} does on the client
 * side (#009d).
 *
 * <p><b>Run alongside {@code demo-product}</b>:
 * <pre>
 *   mvn -pl lingshu-examples/demo-product-a2a-server -am spring-boot:run &
 *   mvn -pl lingshu-examples/demo-product         -am spring-boot:run
 * </pre>
 */
@SpringBootApplication(exclude = {
    // Replace stock A2aServer with our custom one — see class Javadoc.
    A2aServerAutoConfiguration.class
})
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demoproducta2aserver", "ai.lingshu.core"},
    // Exclude YamlWatcher — this is a long-lived Spring Boot web service,
    // not a CommandLineRunner. Same rationale as demo-product.
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoProductA2aServerApplication {

    /**
     * Minimal AgentConfig — only the identity block matters (for AgentCard.name
     * / description), and only Identity.name drives {@code DemoA2aServer}'s
     * startup log. Everything else uses defaults.
     */
    @Bean
    public AgentConfig agentConfig() {
        AgentConfig defaults = AgentConfigDefaults.defaults();
        return new AgentConfig(
            defaults.getFlowEngine(),
            defaults.getLlm(),
            defaults.getPrompt(),
            defaults.getToolExecutor(),
            defaults.getSandbox(),
            defaults.getCompactor(),
            defaults.getSessionStore(),
            defaults.getDelegate(),
            defaults.getMcp(),
            defaults.getSkills(),
            defaults.getToolParallelism(),
            defaults.getToolTimeoutSeconds(),
            defaults.getApprovalTimeoutSeconds(),
            defaults.getTurnTimeoutSeconds(),
            defaults.getLlmTimeoutSeconds(),
            defaults.getReactMaxSteps(),
            new AgentConfig.Identity("translator", "A2A translation agent", "auto",
                java.util.Collections.emptyList(), "neutral", null),
            defaults.getInstructions(),
            defaults.getMemory(),
            defaults.getA2aTransport(),
            defaults.getTenants(),
            defaults.getA2a(),
            defaults.getCompactorConfig(),
            defaults.getTools()
        );
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoProductA2aServerApplication.class, args);
    }
}
