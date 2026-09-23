package ai.lingshu.examples.demolocaltools;

import ai.lingshu.core.impl.tool.DefaultToolExecutorProvider;
import ai.lingshu.core.impl.tool.local.BashTool;
import ai.lingshu.core.impl.tool.local.EditTool;
import ai.lingshu.core.impl.tool.local.ReadTool;
import ai.lingshu.core.impl.tool.local.WriteTool;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #019 — black-box wiring test, <b>enabled</b> path.
 *
 * <p>Boots a full Spring context ({@code @SpringBootTest}) so the entire
 * wiring tree — {@code @Component} Tool beans → {@code LocalToolProps @Bean}
 * → {@code LocalToolsAutoConfiguration#afterPropertiesSet()} →
 * {@code ToolRegistry#register} — runs exactly as in production.
 *
 * <p>Verifies:
 * <ol>
 *   <li>All 4 {@code @Component} Tool beans (Read/Write/Edit/Bash) are present
 *       in the context, and Spring's {@code Map<String, Tool>} resolution
 *       collects exactly 4 entries keyed by bean name.</li>
 *   <li>The shared {@link ToolRegistry} <b>SPI</b> bean (Slot 2 outer half)
 *       contains exactly the 4 tools registered by their {@code Tool.name()},
 *       and the registered instances are the same objects the {@code @Component}
 *       beans expose — proof that the SPI refactor (LocalToolsAutoConfiguration
 *       depends on the {@code ToolRegistry} interface, not on a concrete
 *       {@code DefaultToolExecutor}) preserves identity wiring.</li>
 *   <li>{@code DefaultToolExecutorProvider} resolves through the same shared
 *       registry (provides nothing to verify by itself here, but bean exists).</li>
 * </ol>
 *
 * <p>Why a separate class from {@code LocalToolsWiringDisabledTest}: the disabled
 * variant overrides {@code agent.tools.enabled=false} via a different
 * {@code @TestPropertySource}, so Spring's test context cache cannot reuse the
 * context — two top-level classes, each with its own {@code @SpringBootTest},
 * is the cleanest separation.
 */
@SpringBootTest(
    classes = DemoLocalToolsApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("AC-19 ENABLED: registers 4 local Tools into shared ToolRegistry SPI")
class LocalToolsWiringEnabledTest {

    @Autowired private ToolRegistry toolRegistry;
    @Autowired private ReadTool readTool;
    @Autowired private WriteTool writeTool;
    @Autowired private EditTool editTool;
    @Autowired private BashTool bashTool;
    @Autowired private Map<String, Tool> toolBeans;
    @Autowired private DefaultToolExecutorProvider toolExecutorProvider;

    @Test
    @DisplayName("all 4 Tool @Component beans are present in the context (Skills excluded)")
    void all4ToolBeans_present() {
        assertThat(readTool).isNotNull();
        assertThat(writeTool).isNotNull();
        assertThat(editTool).isNotNull();
        assertThat(bashTool).isNotNull();

        // Spring resolves Map<String, Tool> to all @Component beans implementing Tool.
        // 🆕 Story #020b — SkillAutoConfiguration is a SEPARATE @Configuration from
        // LocalToolsAutoConfiguration, but its injected Map<String, Skill> does not
        // overlap with Map<String, Tool>. The CommitSkill @Component is BOTH a Tool
        // and a Skill (Skill extends Tool), so it appears in Map<String, Tool> too.
        // Filter to plain (non-Skill) Tools for the Story #019 4-Tool assertion.
        Map<String, Tool> plainTools = new java.util.HashMap<>();
        for (Map.Entry<String, Tool> e : toolBeans.entrySet()) {
            if (!(e.getValue() instanceof ai.lingshu.core.slot.Skill)) {
                plainTools.put(e.getKey(), e.getValue());
            }
        }
        assertThat(plainTools).containsKeys("readTool", "writeTool", "editTool", "bashTool");
        assertThat(plainTools).hasSize(4);
    }

    @Test
    @DisplayName("ToolRegistry SPI has exactly 4 tools registered by their .name() (Skills excluded)")
    void toolRegistry_has4RegisteredByName() {
        // 🆕 Story #020b — SkillAutoConfiguration also registers @Component Skills
        // (e.g. CommitSkill) in addition to Local Tools. The 4-Tool contract for
        // Story #019 is verified by subtracting Skill names from the total.
        Collection<String> toolOnly = new java.util.HashSet<>(toolRegistry.names());
        toolOnly.removeAll(toolRegistry.skillNames());
        assertThat(toolOnly)
            .as("LocalToolsAutoConfiguration.afterPropertiesSet() should "
                + "have registered exactly 4 tools by .name() (Skills counted separately)")
            .containsExactlyInAnyOrder("Read", "Write", "Edit", "Bash");

        // Identity wiring: the same instances exposed by the @Component beans
        // are visible through the shared registry. This proves the SPI refactor
        // (LocalToolsAutoConfiguration now depends on the ToolRegistry interface,
        // not on a concrete DefaultToolExecutor) preserves the wiring path.
        assertThat(toolRegistry.lookup("Read")).isSameAs(readTool);
        assertThat(toolRegistry.lookup("Write")).isSameAs(writeTool);
        assertThat(toolRegistry.lookup("Edit")).isSameAs(editTool);
        assertThat(toolRegistry.lookup("Bash")).isSameAs(bashTool);
    }

    @Test
    @DisplayName("DefaultToolExecutorProvider resolves via the shared ToolRegistry")
    void toolExecutorProvider_usesSharedRegistry() {
        // The Provider's @Autowired ToolRegistry must be the same singleton
        // that LocalToolsAutoConfiguration writes into — otherwise the 4 tool
        // registrations would be invisible to the dispatch pipeline.
        // The dispatch contract itself is covered by lingshu-core unit tests
        // (DefaultToolExecutorTest, MaxStepsGuardTest, etc.) — here we only
        // assert bean availability.
        assertThat(toolExecutorProvider).isNotNull();
    }
}
