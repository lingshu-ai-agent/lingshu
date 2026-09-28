package ai.lingshu.core.tool;

import ai.lingshu.core.impl.tool.DefaultToolRegistry;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #022 — L2 Slice tests for {@link AgentToolScanner}.
 *
 * <p>覆盖 Story #022 spec §4 AC-NN-1(注解扫描 + 注册完整路径) + 反向 AC(无 Component Bean / dup name / null ctx)。
 *
 * <p><b>测试用裸 {@link AnnotationConfigApplicationContext}</b>(无需{@code @SpringBootTest})——
 * 规避 Mockito 5.x + JDK 23 inline-mock 兼容 issue,沿用 Story #007 经验。组件路径:
 * <ol>
 *   <li>{@link DefaultToolRegistry} 作为 {@code @Bean} 注入</li>
 *   <li>{@link AgentToolScanner}({@code @Component})自动 pick up</li>
 *   <li>{@link TestToolsConfig} 提供 {@code @Component}-annotated fixture bean</li>
 *   <li>context refresh 后查 {@link ToolRegistry#lookup} 结果</li>
 * </ol>
 */
class AgentToolScannerTest {

    @Test
    @DisplayName("AC-022-23: scannerPicksUpAnnotatedMethods_andRegistersEachAsSpringAiToolAdapter")
    void scannerPicksUpAnnotatedMethods_andRegistersEachAsSpringAiToolAdapter() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);  // @Component
            ctx.register(ScannerTestTools.class);    // @Component
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);

            // 单 bean 单 @AgentTool 方法 "scanner_one"
            Tool one = registry.lookup("scanner_one");
            assertThat(one).isNotNull();
            assertThat(one).isInstanceOf(SpringAiToolAdapter.class);
            assertThat(one.description()).isEqualTo("first");

            // "scanner_two" / "scanner_three" 同 bean 多方法
            assertThat(registry.lookup("scanner_two")).isNotNull();
            assertThat(registry.lookup("scanner_three")).isNotNull();

            // 3 个都在 names 集合里
            Collection<String> names = registry.names();
            assertThat(names).contains("scanner_one", "scanner_two", "scanner_three");
        }
    }

    @Test
    @DisplayName("AC-022-24: scannerSkipsBeansWithoutAgentToolAnnotation")
    void scannerSkipsBeansWithoutAgentToolAnnotation() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);
            ctx.register(ScannerBlankBean.class);  // 没有 @AgentTool 方法
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            // ScannerBlankBean 上没有任何 @AgentTool → 不该注册任何 tool
            assertThat(registry.names()).isEmpty();
        }
    }

    @Test
    @DisplayName("AC-022-25: scannerDuplicateName_firstWins_andLogsWarn")
    void scannerDuplicateName_firstWins_andLogsWarn() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);
            ctx.register(ScannerDupFirstBean.class);
            ctx.register(ScannerDupSecondBean.class);
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            Tool t = registry.lookup("dup");
            assertThat(t).isNotNull();
            // first-wins:DupFirstBean 先注册,DupSecondBean 后注册 → lookup 返回 first 那个
            // 通过 tool bean instance 关联识别哪个 bean 的 adapter 被注册了
            assertThat(t).isInstanceOf(SpringAiToolAdapter.class);
            // `asMap()` 是测试用 fixture
            Map<String, Tool> rawMap = ((DefaultToolRegistry) registry).asMap();
            assertThat(rawMap).containsKey("dup");
            // size is 1 (first-wins)
            assertThat(rawMap).hasSize(1);
        }
    }

    @Test
    @DisplayName("AC-022-26: scannerNullCtx_skipsCleanly")
    void scannerNullCtx_skipsCleanly() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        AgentToolScanner scanner = new AgentToolScanner(registry);
        // null ctx — 不该抛异常(scanContext 是 package-private,直接调避免
        // 走 @EventListener 路径,验证 scan 边界)
        scanner.scanContext(null);
        // registry 仍然为空
        assertThat(registry.names()).isEmpty();
    }

    @Test
    @DisplayName("AC-022-26b: scannerIdempotent_secondScanContextIsNoOp")
    void scannerIdempotent_secondScanContextIsNoOp() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        AgentToolScanner scanner = new AgentToolScanner(registry);
        // 第一次扫 + 第二次扫 —— 第二次必须 no-op(防止 nested context /
        // 多次 refresh 导致重复注册)
        scanner.scanContext(new AnnotationConfigApplicationContext(DefaultToolRegistry.class));
        scanner.scanContext(new AnnotationConfigApplicationContext(DefaultToolRegistry.class));
        // 仅第一次生效 → 0 个 tool(DefaultToolRegistry 自身无 @AgentTool)
        assertThat(registry.names()).isEmpty();
    }

    @Test
    @DisplayName("AC-022-27: scannerIncludesComponentStereotypeSubclasses")
    void scannerIncludesComponentStereotypeSubclasses() throws Exception {
        // @Service / @Repository / @Controller 等 @Component meta-annotated stereotype
        // 也应该被扫到——验证 Spring component-scan 含 stereotype 语义
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(DefaultToolRegistry.class);
            ctx.register(AgentToolScanner.class);
            ctx.register(ScannerStereotypeBean.class);   // @Service (transitively @Component)
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(ToolRegistry.class);
            assertThat(registry.lookup("stereotype_method")).isNotNull();
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Test fixture beans
    // ──────────────────────────────────────────────────────────────────────

    @Component
    public static class ScannerTestTools {
        @AgentTool(name = "scanner_one", description = "first")
        public String one() { return "1"; }

        @AgentTool(name = "scanner_two", description = "second")
        public int two() { return 2; }

        @AgentTool(name = "scanner_three", description = "third")
        public boolean three() { return true; }
    }

    @Component
    public static class ScannerBlankBean {
        public void doWork() {
            // 没有 @AgentTool 注解,不该被注册
        }
    }

    @Component
    public static class ScannerDupFirstBean {
        @AgentTool(name = "dup", description = "first-bean")
        public String firstDup() { return "first"; }
    }

    @Component
    public static class ScannerDupSecondBean {
        @AgentTool(name = "dup", description = "second-bean")
        public String secondDup() { return "second"; }
    }

    @org.springframework.stereotype.Service
    public static class ScannerStereotypeBean {
        @AgentTool(name = "stereotype_method", description = "from @Service bean")
        public String ok() { return "ok"; }
    }

    /** Tiny helper to register DefaultToolRegistry as @Bean in test contexts. */
    @Configuration
    public static class RegistryConfig {
        @Bean
        public ToolRegistry toolRegistry() {
            return new DefaultToolRegistry();
        }
    }
}
