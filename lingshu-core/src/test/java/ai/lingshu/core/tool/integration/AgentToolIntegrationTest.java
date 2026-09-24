package ai.lingshu.core.tool.integration;

import ai.lingshu.core.impl.permission.AllowAllPermissionPolicy;
import ai.lingshu.core.impl.tool.DefaultToolExecutor;
import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.message.ToolSpec;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import ai.lingshu.core.tool.AgentTool;
import ai.lingshu.core.tool.AgentToolScanner;
import ai.lingshu.core.tool.SpringAiToolAdapter;
import ai.lingshu.core.tool.ToolErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #022 — L2/L3 Integration test for the @AgentTool end-to-end pipeline.
 *
 * <p>覆盖 Story #022 spec §4 AC-NN-1 (注册完整路径) + AC-NN-2 (反射调用) + AC-NN-3
 * (reflection failure 转 LINGS-T08 ErrorCode) + 端到端 LLM 视角下 @AgentTool 可发现 + 可调用。
 *
 * <p><b>测试链路</b>:
 * <pre>{@code
 * AnnotationConfigApplicationContext refresh
 *   → AgentToolScanner.setApplicationContext() 回调
 *     → ctx.getBeansWithAnnotation(Component.class).values() 迭代
 *       → 反射找 @AgentTool method
 *         → registry.register(new SpringAiToolAdapter(bean, method, annotation))
 *           → ToolRegistry.modelVisibleSpecs() 含本 tool
 *             → ToolExecutor.dispatch(call, ctx) → SpringAiToolAdapter.execute() → method.invoke()
 * }</pre>
 *
 * <p><b>不引 {@code @SpringBootTest}</b> — 规避 Mockito 5.x + JDK 23 inline-mock 兼容 issue,
 * 沿用 Story #007 / Story #019 / Story #021b 同款裸 ApplicationContext 模式。
 */
class AgentToolIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-022-28: endToEnd_annotatedBeanMethodIsDiscoverableAndCallableViaToolExecutor")
    void endToEnd_annotatedBeanMethodIsDiscoverableAndCallableViaToolExecutor() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);
            ctx.register(E2ETools.class);
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);

            // (1) Discoverable — modelVisibleSpecs() 包含 @AgentTool
            List<ToolSpec> specs = registry.modelVisibleSpecs();
            assertThat(specs).extracting(ToolSpec::getName).contains(
                "e2e_add", "e2e_greet", "e2e_boom");
            // schema 正确生成(简单 assertion)
            ToolSpec addSpec = specs.stream()
                .filter(s -> s.getName().equals("e2e_add"))
                .findFirst().orElseThrow(IllegalStateException::new);
            assertThat(addSpec.getInputSchema().get("properties").get("a").get("type").asText())
                .isEqualTo("integer");

            // (2) Callable through ToolExecutor.dispatch
            DefaultToolExecutor executor = new DefaultToolExecutor(
                new AllowAllPermissionPolicy(), registry);

            ObjectNode input = MAPPER.createObjectNode();
            input.put("a", 10);
            input.put("b", 32);
            ToolCall call = new ToolCall("e2e-id-1", "e2e_add", input);
            ToolResult r = executor.dispatch(call, null);

            assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
            assertThat(r.getContent()).isEqualTo("42");
            assertThat(r.getToolUseId()).isEqualTo("e2e-id-1");
        }
    }

    @Test
    @DisplayName("AC-022-29: integrationBusinessException_reachesToolExecutor_andEmitsLINGS_T08")
    void integrationBusinessException_reachesToolExecutor_andEmitsLINGS_T08() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);
            ctx.register(E2ETools.class);
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            DefaultToolExecutor executor = new DefaultToolExecutor(
                new AllowAllPermissionPolicy(), registry);

            // e2e_boom 抛 RuntimeException → SpringAiToolAdapter catch → LINGS-T08
            ToolCall call = new ToolCall("e2e-id-2", "e2e_boom",
                MAPPER.createObjectNode());
            ToolResult r = executor.dispatch(call, null);

            assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
            assertThat(r.isError()).isTrue();
            assertThat(r.getContent())
                .contains("[" + ToolErrorCodes.LINGS_T08 + "]");
        }
    }

    @Test
    @DisplayName("AC-022-30: integrationStereotypeServiceBean_alsoScanned")
    void integrationStereotypeServiceBean_alsoScanned() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);
            ctx.register(E2EStereotypeTools.class);
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            // @Service 是 @Component 的 stereotype — Spring component-scan 包括
            Tool t = registry.lookup("stereotype_in_e2e");
            assertThat(t).isNotNull();
            assertThat(t).isInstanceOf(SpringAiToolAdapter.class);
        }
    }

    @Test
    @DisplayName("AC-022-31: modelVisibleSpecs_sortedDeterministically")
    void modelVisibleSpecs_sortedDeterministically() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);
            ctx.register(E2ETools.class);
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            List<ToolSpec> specs = registry.modelVisibleSpecs();
            // 字典序排序 — 与 DefaultToolRegistry.modelVisibleSpecs 内 sort 一致
            List<String> names = new java.util.ArrayList<>();
            for (ToolSpec s : specs) {
                names.add(s.getName());
            }
            List<String> sorted = new java.util.ArrayList<>(names);
            java.util.Collections.sort(sorted);
            assertThat(names).isEqualTo(sorted);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Test fixture beans
    // ──────────────────────────────────────────────────────────────────────

    @Component
    public static class E2ETools {
        @AgentTool(name = "e2e_add", description = "a+b")
        public int add(int a, int b) { return a + b; }

        @AgentTool(name = "e2e_greet", description = "greet")
        public String greet(String name) { return "hi " + name; }

        @AgentTool(name = "e2e_boom", description = "always throws")
        public String boom() { throw new RuntimeException("e2e failure"); }
    }

    @Service
    public static class E2EStereotypeTools {
        @AgentTool(name = "stereotype_in_e2e", description = "in @Service")
        public String stereotype() { return "from-stereotype"; }
    }
}
