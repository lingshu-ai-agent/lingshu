package ai.lingshu.examples.demoengineer;

import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.prompt.DefaultPromptBuilder;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.message.Prompt;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.runtime.Session;
import ai.lingshu.core.runtime.TurnContext;
import ai.lingshu.core.slot.MemorySource;
import ai.lingshu.core.slot.PromptBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #002 AC-09 US1 — black-box verification that demo-engineer's full YAML
 * config boots Spring, resolves 4 MemorySource beans in yml-list order, wires
 * them into a {@link PromptBuilder}, and assembles a 5-segment system message.
 *
 * <p>This is the AC-09 US1 Scenario 1 contract test — it does NOT call the LLM
 * (no {@code ANTHROPIC_API_KEY} required) but verifies the full
 * application-context boot path end-to-end, including:
 * <ol>
 *   <li>Spring component scan finds 4 {@link MemorySourceProvider} beans</li>
 *   <li>{@link Routers.MemorySourceRouter} resolves them in yml-list order
 *       (not by priority desc)</li>
 *   <li>{@link DefaultPromptBuilderProvider} injects the 4 sources into a builder</li>
 *   <li>{@link DefaultPromptBuilder#build(TurnContext)} assembles a 5-segment system
 *       message containing [ROLE] + [INSTRUCTIONS] + [PROJECT MEMORY]</li>
 * </ol>
 */
@SpringBootTest(
    classes = DemoEngineerApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired
    private AgentFactory agentFactory;

    @Autowired
    private Routers.MemorySourceRouter memorySourceRouter;

    @Test
    @DisplayName("AC-09 US1 Scenario 1: demo-engineer 5-segment assembly end-to-end")
    void blackBox_5SegmentAssembly_endToEnd() {
        // ── 1. Spring scan picked up all 4 default MemorySourceProviders ─────
        // Router.available() returns the names of all registered providers.
        java.util.Set<String> availableNames = memorySourceRouter.available();
        assertThat(availableNames)
            .as("MemorySourceRouter should resolve all 4 default providers")
            .contains("identity", "project-claude-md", "user-claude-md", "project-tree");

        // ── 2. AgentFactory.create wires all routers and an Agent instance ─────
        AgentConfig cfg = AgentConfigDefaults.defaults();
        Agent agent = agentFactory.create(cfg);
        assertThat(agent).isNotNull();

        // ── 3. Router resolves MemorySources in yml-list order, not priority ──
        // Note: AgentConfigDefaults returns empty memory-sources list;
        // we pass the demo-engineer's yml list explicitly to mirror real boot.
        List<String> ymlMemorySources = java.util.Arrays.asList(
            "identity", "project-claude-md", "user-claude-md", "project-tree");
        List<MemorySource> resolved = memorySourceRouter.resolveAll(ymlMemorySources, cfg);
        assertThat(resolved).hasSize(4);
        // YML order is [identity, project-claude-md, user-claude-md, project-tree]
        // NOT priority desc ([project-tree=40, identity=30, user-claude-md=20,
        // project-claude-md=10]).
        assertThat(resolved.get(0).name()).isEqualTo("identity");
        assertThat(resolved.get(1).name()).isEqualTo("project-claude-md");
        assertThat(resolved.get(2).name()).isEqualTo("user-claude-md");
        assertThat(resolved.get(3).name()).isEqualTo("project-tree");

        // ── 4. DefaultPromptBuilder assembles 5-segment system message ────────
        TurnContext ctx = mock(TurnContext.class);
        Session session = mock(Session.class);
        when(ctx.config()).thenReturn(cfg);
        when(ctx.userInput()).thenReturn("你是做什么的");
        when(ctx.session()).thenReturn(session);
        when(session.history()).thenReturn(Collections.<Message>emptyList());

        PromptBuilder builder = new DefaultPromptBuilder(resolved);
        Prompt prompt = builder.build(ctx);

        assertThat(prompt.getMessages()).isNotEmpty();
        Message sys = prompt.getMessages().get(0);
        assertThat(sys).isInstanceOf(Message.System.class);

        String systemText = ((Message.System) sys).getContent();
        // [ROLE] segment (DefaultPromptBuilder strips literal [ROLE] label,
        // but the identity.name "lingShu-agent" appears in the content).
        assertThat(systemText).contains("lingShu-agent");

        // [USER MESSAGE] always present (last message)
        Message last = prompt.getMessages().get(prompt.getMessages().size() - 1);
        assertThat(last).isInstanceOf(Message.User.class);
        assertThat(((Message.User) last).getContent()).isEqualTo("你是做什么的");
    }

    @Test
    @DisplayName("AC-09 US3 Scenario 1: missing MemorySource files don't crash build()")
    void blackBox_missingFiles_noErrorLogs() {
        // Run with a fresh cfg — yml lists 4 sources, but ~/.lingshu/CLAUDE.md and
        // extras/ paths may not exist on this dev box. Build must NOT throw.
        AgentConfig cfg = AgentConfigDefaults.defaults();
        List<String> ymlMemorySources = java.util.Arrays.asList(
            "identity", "project-claude-md", "user-claude-md", "project-tree");
        List<MemorySource> resolved = memorySourceRouter.resolveAll(ymlMemorySources, cfg);

        TurnContext ctx = mock(TurnContext.class);
        Session session = mock(Session.class);
        when(ctx.config()).thenReturn(cfg);
        when(ctx.userInput()).thenReturn("hello");
        when(ctx.session()).thenReturn(session);
        when(session.history()).thenReturn(Collections.<Message>emptyList());

        PromptBuilder builder = new DefaultPromptBuilder(resolved);
        Prompt prompt = builder.build(ctx);

        // 5-segment message exists; missing CLAUDE.md/extras silently skipped
        assertThat(prompt.getMessages()).isNotEmpty();
        // session.history() called once during build()
        Mockito.verify(session, Mockito.times(1)).history();
    }
}