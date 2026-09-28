package ai.lingshu.examples.demoempty;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Story #001 demo — zero-config boot + a single LLM turn.
 *
 * <p>AC-01-1 black-box verification path:
 * <ol>
 *   <li>Spring Boot boots with an effectively empty {@code application.yml}</li>
 *   <li>{@link AgentConfigDefaults#defaults()} supplies all 27 fields</li>
 *   <li>{@link AgentFactory#create(AgentConfig)} wires default Providers and returns an {@link Agent}</li>
 *   <li>{@link Agent#runBlocking(String)} runs a single LLM call and prints the result</li>
 * </ol>
 *
 * <p>Exit code 0 on success, non-zero on any error. Used by the AC-01-1 timing harness
 * (target: first token within 30 s, zero ERROR-level logs on stderr).
 *
 * <p>排除 {@link YamlWatcher}(Story #007 hot reload daemon —— 本 demo 跑一次就退,不需要 mtime 轮询)
 * 与 {@link AgentToolScanner}(Story #022 @AgentTool 引入后启动期 ctx.getBeansWithAnnotation 自引用
 * circular ref)—— 避免 spring-boot:run 启动失败。
 */
@SpringBootApplication
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demoempty", "ai.lingshu.core"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class DemoEmptyApplication implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoEmptyApplication.class);

    private final AgentFactory agentFactory;

    public DemoEmptyApplication(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoEmptyApplication.class, args);
    }

    @Override
    public void run(String... args) {
        String prompt = args.length > 0
            ? args[0]
            : "Reply with a single sentence describing what you are.";

        AgentConfig cfg = AgentConfigDefaults.defaults();
        LOG.info("DemoEmpty starting: flowEngine={}, llm.provider={}, llm.model={}",
            cfg.getFlowEngine(), cfg.getLlm().getProvider(), cfg.getLlm().getModel());

        Agent agent = agentFactory.create(cfg);
        RunResult result = agent.runBlocking(prompt);

        System.out.println("──────────────────────────────────────────────────");
        System.out.println("Prompt  : " + prompt);
        System.out.println("Response: " + result.getFinalText());
        System.out.println("Reason  : " + result.getStopReason());
        System.out.println("Tokens  : in=" + result.getTotalUsage().getInputTokens()
            + " out=" + result.getTotalUsage().getOutputTokens());
        System.out.println("Elapsed : " + result.getElapsedMillis() + " ms");
        System.out.println("──────────────────────────────────────────────────");
    }
}