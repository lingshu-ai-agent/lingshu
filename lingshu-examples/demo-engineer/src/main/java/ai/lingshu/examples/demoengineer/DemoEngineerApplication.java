package ai.lingshu.examples.demoengineer;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.RunResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

/**
 * Story #002 demo — 业务三件套(Identity / Instructions / Memory)+ 5 段 Prompt 装配。
 *
 * <p>AC-09 black-box verification path:
 * <ol>
 *   <li>Spring Boot boots with a fully-populated {@code application.yml}</li>
 *   <li>{@link AgentConfigDefaults#defaults()} supplies all 27 fields; YAML overrides identity / instructions / memory</li>
 *   <li>{@link AgentFactory#create(AgentConfig)} wires 4 default MemorySource Providers and resolves them in yml-list order</li>
 *   <li>{@link Agent#runBlocking(String)} runs a single LLM turn with the 5-segment system prompt</li>
 * </ol>
 *
 * <p>Expected stderr output:
 * <pre>
 *   [MemorySource] resolved 4 provider(s): identity, project-claude-md, user-claude-md, project-tree
 *   [PromptBuilder] segments: ROLE | INSTRUCTIONS | PROJECT MEMORY | HISTORY | USER
 * </pre>
 *
 * <p>Expected stdout:
 * <pre>
 *   Response: 我是 {{name}},Java 后端工程师, ...
 * </pre>
 *
 * <p>Exit code 0 on success, non-zero on any error.
 * First-token budget: P50 ≤ 30s; AC-09 US1 Scenario 1 asserts.
 */
@SpringBootApplication
@ComponentScan(basePackages = {"ai.lingshu.examples.demoengineer", "ai.lingshu.core"})
public class DemoEngineerApplication implements CommandLineRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoEngineerApplication.class);

    private final AgentFactory agentFactory;

    public DemoEngineerApplication(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    public static void main(String[] args) {
        // AC-09 US1 Scenario 1 fail-fast: ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN) is mandatory.
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            apiKey = System.getenv("ANTHROPIC_AUTH_TOKEN");
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("FATAL: ANTHROPIC_API_KEY (or ANTHROPIC_AUTH_TOKEN) environment variable is not set.");
            System.err.println("Set it before running: export ANTHROPIC_API_KEY=sk-ant-...");
            System.exit(2);
        }

        // Disable web auto-detection (spring-ai-anthropic transitively pulls in
        // spring-webflux, which would otherwise try to start a reactive web server)
        SpringApplication app = new SpringApplication(DemoEngineerApplication.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.run(args);
    }

    @Override
    public void run(String... args) {
        String prompt = args.length > 0
            ? args[0]
            : "你是做什么的";

        AgentConfig cfg = AgentConfigDefaults.defaults();
        LOG.info("DemoEngineer starting: flowEngine={}, llm.provider={}, llm.model={}",
            cfg.getFlowEngine(), cfg.getLlm().getProvider(), cfg.getLlm().getModel());
        LOG.info("Identity: name={}, role={}, language={}, tone={}",
            cfg.getIdentity().getName(),
            cfg.getIdentity().getRole(),
            cfg.getIdentity().getLanguage(),
            cfg.getIdentity().getTone());
        LOG.info("Memory sources (yml list order, NOT priority): {}",
            cfg.getPrompt().getMemorySources());

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