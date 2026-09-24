package ai.lingshu.examples.demodelegate;

import ai.lingshu.core.tool.AgentTool;
import org.springframework.stereotype.Component;

/**
 * Story #022 wiring 验证 fixture —— demo-delegate 是 14 demo 中第一个(也是唯一一个)
 * 携带 {@link AgentTool @AgentTool} fixture bean 的 demo。
 *
 * <p>本 fixture 验证 AgentToolScanner 重构后({@code @EventListener(ContextRefreshedEvent)})
 * 在 {@code @SpringBootTest} 真实场景下能自动发现并注册到共享 {@code ToolRegistry},
 * 无需任何 {@code excludeFilters}。
 *
 * <p>历史背景:之前所有 14 个 demo 的 {@code Application.java} 都在
 * {@code @ComponentScan(excludeFilters = ...)} 里硬排
 * {@code AgentToolScanner.class} 绕开 circular ref bug —— 后果是 Story #022 的
 * 核心契约({@code @AgentTool → ToolRegistry})在 demo / test 工程 0 回归覆盖。
 *
 * <p>2026-09-24 circular ref 修复后,scanner 切到 {@code @EventListener},
 * {@code ctx.getBeansWithAnnotation(Component.class)} 在所有 bean init 完毕后
 * 才触发,无 circular ref,demo 全部去掉 exclude。本 fixture 则是首个真实端到端
 * 回归测试:证明标了 {@code @AgentTool} 的 bean 真的进了 {@code ToolRegistry}。
 *
 * @see ai.lingshu.core.tool.AgentToolScanner
 * @see ai.lingshu.core.tool.AgentTool
 */
@Component
public class AgentToolFixture {

    @AgentTool(name = "demo_delegate_add", description = "demo-delegate a+b fixture")
    public int add(int a, int b) {
        return a + b;
    }
}
