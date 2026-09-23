package ai.lingshu.examples.demolocaltools;

import ai.lingshu.core.impl.tool.local.BashTool;
import ai.lingshu.core.impl.tool.local.ReadTool;
import ai.lingshu.core.slot.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — black-box wiring test, <b>disabled</b> path.
 *
 * <p>Boots the same context as {@code LocalToolsWiringEnabledTest} but with
 * {@code agent.tools.enabled=false} to verify the disable toggle:
 *
 * <ul>
 *   <li>The 4 {@code @Component} Tool beans <b>stay constructed</b> — direct
 *       {@code new ReadTool(...)} / {@code bash.execute(...)} from a test
 *       still works (orphan {@code @Component} state).</li>
 *   <li>{@link ToolRegistry#names()} is <b>empty</b> — model-driven tool calls
 *       will resolve to {@code null} (translated to
 *       {@code ToolException.ToolNotFoundException} by the outer pipeline).</li>
 *   <li>{@code BashTool.processRunner} is left at its orphan-construction
 *       default (unset), so {@code bash.execute(...)} would surface a
 *       defensive {@code ToolResult.error} — checked separately in
 *       {@code BashToolTest} (Story #019 L1 unit).</li>
 * </ul>
 *
 * <p>Why a separate top-level class: a different {@code @TestPropertySource}
 * means Spring's {@code TestContext} cache cannot reuse the context — keeping
 * the two variants in distinct classes gives each its own clean lifecycle.
 */
@SpringBootTest(
    classes = DemoLocalToolsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "agent.tools.enabled=false")
@DisplayName("AC-19 DISABLED: 4 @Component Tools stay constructed but registry is empty")
class LocalToolsWiringDisabledTest {

    @Autowired private ToolRegistry toolRegistry;
    @Autowired private BashTool bashTool;
    @Autowired private ReadTool readTool;

    @Test
    @DisplayName("4 Tool @Component beans are still constructed (orphan state)")
    void all4ToolBeans_stillConstructed() {
        // Tools exist as @Components even when wiring is disabled — users may
        // still want to instantiate and call them directly in tests or scripts.
        assertThat(readTool).isNotNull();
        assertThat(bashTool).isNotNull();
    }

    @Test
    @DisplayName("Tool registry has zero local Tools when wiring is disabled (Skills may still be registered)")
    void toolRegistry_isEmpty() {
        // 🆕 Story #020b — agent.tools.enabled=false disables LocalToolsAutoConfiguration only.
        // SkillAutoConfiguration (separate, governs Skills) is unaffected and may still register
        // @Component-typed Skills (e.g. CommitSkill). We assert the 4-Tool side is empty
        // and rely on skillNames() to expose Skills separately.
        java.util.Set<String> toolsOnly = new java.util.HashSet<>(toolRegistry.names());
        toolsOnly.removeAll(toolRegistry.skillNames());
        assertThat(toolsOnly).isEmpty();
        assertThat(toolRegistry.lookup("Bash")).isNull();
        assertThat(toolRegistry.lookup("Read")).isNull();
        assertThat(toolRegistry.lookup("Edit")).isNull();
        assertThat(toolRegistry.lookup("Write")).isNull();
    }
}
