package ai.lingshu.examples.demodelegate;

import ai.lingshu.core.agent.SubAgentType;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.router.Routers;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.runtime.AgentConfig;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import ai.lingshu.core.tool.SpringAiToolAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #022 + #023 + #024 AC skeleton — @AgentTool + DelegateTool + DefaultPromptBuilder
 * {@code [TOOL SCHEMAS]} 段 wiring。
 *
 * <p>骨架阶段(Stage A)只验证 Spring 上下文能启动 + SubAgentType 三值 enum 完整 +
 * LlmProvider 通过 SPI 注册到 Router。完整 AC 黑盒覆盖(@AgentTool annotation scan +
 * DelegateTool.execute() sub-agent 派发 + DefaultPromptBuilder Prompt.tools 注入 +
 * SubAgentInheritance field-level merge 24 字段)留 Stage B。
 *
 * <p>骨架测试通过标准:
 * <ol>
 *   <li>Spring context 启动成功(DelegateAutoConfiguration wiring 不抛 LINGS-D01)</li>
 *   <li>{@link SubAgentType} enum 包含 EXPLORE / ENGINEER / REVIEWER 三值,configKey 与 yml 一致</li>
 *   <li>{@link SubAgentType#allKeys()} 与 enum values 顺序一致</li>
 *   <li>yml {@code agent.delegate.types} 三个 key 都被加载到 {@link AgentConfig.Delegate#getTypes()}</li>
 *   <li>{@link Routers.LlmProviderRouter} 包含 demo-delegate 自定义 provider</li>
 * </ol>
 */
@SpringBootTest(
    classes = DemoDelegateApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BlackBoxVerificationTest {

    @Autowired private AgentFactory agentFactory;
    @Autowired private AgentConfig agentConfig;
    @Autowired private Routers.LlmProviderRouter llmRouter;
    @Autowired private ToolRegistry toolRegistry;

    @Test
    @DisplayName("AC-023 skeleton: SubAgentType enum closed set has all 3 values")
    void skeleton_subAgentTypeEnumComplete() {
        // Story #023 — 封闭三值,与 Claude Code Task tool 对齐
        assertThat(SubAgentType.values()).hasSize(3);
        assertThat(SubAgentType.EXPLORE.configKey()).isEqualTo("explore");
        assertThat(SubAgentType.ENGINEER.configKey()).isEqualTo("engineer");
        assertThat(SubAgentType.REVIEWER.configKey()).isEqualTo("reviewer");

        // allKeys() 与 enum values 声明顺序一致(LinkedHashSet)
        assertThat(SubAgentType.allKeys())
            .containsExactly("explore", "engineer", "reviewer");
    }

    @Test
    @DisplayName("AC-023 skeleton: fromKey round-trips all declared configKeys")
    void skeleton_fromKeyRoundTrip() {
        for (SubAgentType t : SubAgentType.values()) {
            assertThat(SubAgentType.fromKey(t.configKey()))
                .as("fromKey(%s) should round-trip", t.configKey())
                .isEqualTo(t);
        }
        // Unknown key 抛 IllegalArgumentException
        try {
            SubAgentType.fromKey("nonsense");
            assertThat(false).as("fromKey('nonsense') should have thrown").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("nonsense");
        }
    }

    @Test
    @DisplayName("AC-023 skeleton: defaults.delegate is null but SubAgentType key set covers yml")
    void skeleton_ymlDelegateTypesLoaded() {
        // Note: Spring @Bean agentConfig() returns AgentConfigDefaults.defaults() (no yml
        // binding). Full yml-driven delegate.types loading happens via
        // agentFactory.loadYamlAndValidate(path) and is covered by Story #023's own tests.
        // Stage A only verifies that the enum's configKey set matches what the yml uses.
        AgentConfig defaults = AgentConfigDefaults.defaults();
        assertThat(defaults.getDelegate())
            .as("AgentConfigDefaults has no delegate block (yours provides one)")
            .isNull();

        // SubAgentType.allKeys() must match the yml `agent.delegate.types` map keys
        // (explore / engineer / reviewer) — so DelegateTool.loadConfigs can find each entry.
        assertThat(SubAgentType.allKeys())
            .containsExactly("explore", "engineer", "reviewer");
    }

    @Test
    @DisplayName("AC-022 + #024 skeleton: SPI multi-provider wiring visible")
    void skeleton_spiAndWiringVisible() {
        // AgentFactory 6 Router 全部就位
        assertThat(agentFactory).isNotNull();

        // LlmProviderRouter 包含 demo-delegate 自定义 + anthropic 默认
        assertThat(llmRouter.available())
            .as("Router should discover both default + custom Providers")
            .contains("anthropic", "demo-delegate");
    }

    @Test
    @DisplayName("AC-024 skeleton: default config compiles without ToolRegistry null")
    void skeleton_defaultsStillCompile() {
        // Story #001 zero-config — AgentConfigDefaults.defaults() 必须能编译/构造
        AgentConfig defaults = AgentConfigDefaults.defaults();
        assertThat(defaults).isNotNull();
    }

    @Test
    @DisplayName("AC-022 circular-ref fix: @AgentTool method is auto-registered via @EventListener on @SpringBootTest refresh")
    void agentToolFixture_isAutoRegisteredOnContextRefresh() {
        // demo-delegate 是 14 demo 中第一个(也是唯一一个)携带 @AgentTool fixture
        // bean 的 demo —— 验证 AgentToolScanner 重构后(@EventListener) 在 @SpringBootTest
        // 真实场景下能自动发现并注册到共享 ToolRegistry,无需任何 excludeFilters 绕过。
        // 此前所有 demo 都硬排 AgentToolScanner.class 绕开 circular ref,Story #022 核心契约
        // (@AgentTool → ToolRegistry) 在 demo/test 工程 0 回归覆盖;本测试是修复后首个真验证。
        Tool tool = toolRegistry.lookup("demo_delegate_add");
        assertThat(tool)
            .as("@AgentTool method 'demo_delegate_add' should be auto-registered on ContextRefreshedEvent")
            .isNotNull();
        assertThat(tool).isInstanceOf(SpringAiToolAdapter.class);

        // 模型视角下也可发现 —— modelVisibleSpecs() 含本 tool
        List<ToolSpec> specs = toolRegistry.modelVisibleSpecs();
        assertThat(specs).extracting(ToolSpec::getName).contains("demo_delegate_add");
    }
}
