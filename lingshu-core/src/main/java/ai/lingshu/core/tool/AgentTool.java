package ai.lingshu.core.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Spring AI 风格 Tool 注解 (Story #022, dsh v1.5.40 §6.5 (3) L4883-4889).
 *
 * <p><b>用途</b> — 在 {@code @Component} Bean 的 method 上标注本注解,Spring 容器刷新时由
 * {@link AgentToolScanner} 自动扫描并包装成 {@link SpringAiToolAdapter} 注册到
 * {@link ai.lingshu.core.slot.ToolRegistry}。LLM 通过
 * {@link ai.lingshu.core.slot.ToolRegistry#modelVisibleSpecs()} 看到本 tool 的
 * JSON Schema 并可在 ReAct Action 阶段发起调用。
 *
 * <p><b>与 {@code spring-ai @Tool} 注解的关系</b> —
 * <ul>
 *   <li>设计意图对齐 Spring AI 风格 {@code @Tool} 注解(零样板地把已有 Java method
 *       暴露为 LLM tool),实现走 LingShu 自己的反射路径,绝不引入
 *       {@code spring-ai-spring-boot-starter} 全家桶执行管道
 *       (dsh §4.10.1 硬规则 2 + R-13 mitigation)</li>
 *   <li>执行路径**永远**走 {@link ai.lingshu.core.slot.ToolExecutor#dispatch} 5 步流水线
 *       (permission → registry lookup → timeout → sandbox → execute → checkpoint),
 *       沙箱 / 权限 / checkpoint **不**让步</li>
 *   <li>本注解**只**取 Spring AI 注解风格的 Schema 生成侧,实现侧完全自己控制</li>
 * </ul>
 *
 * <p><b>3 字段</b>(对齐 dsh §6.5 (3) 字面):
 * <ul>
 *   <li>{@code name} — 必填;tool 名(对应 {@link ai.lingshu.core.slot.Tool#name()});
 *       注册时由 {@link ai.lingshu.core.impl.tool.DefaultToolRegistry} 做 dup 检测(first-wins + log warn)</li>
 *   <li>{@code description} — 必填;人话描述,LLM 决策时</li>
 *   <li>{@code capabilities} — 选填;预留字段,**当前不消费**(留 OQ-Future 给
 *       §14.10 N10 audit-log Story 接入 {@link ai.lingshu.core.slot.ToolExecutionContext#approval()}
 *       决策时启用);<b>#022 不</b>读此字段</li>
 * </ul>
 *
 * <p><b>使用样例</b>(对齐 §6.5 (3) L4993-5010 统一视图):
 * <pre>{@code
 * @Component
 * public class CalcTools {
 *     @AgentTool(name = "add", description = "两数相加")
 *     public int add(int a, int b) { return a + b; }
 *
 *     @AgentTool(name = "format",
 *                description = "格式化输出",
 *                capabilities = {"fs.read", "process.exec"})
 *     public String format(String prefix, int count) { return prefix + ":" + count; }
 * }
 * }</pre>
 *
 * <p><b>🆕 Story #022 — 关键不变项</b>:
 * <ul>
 *   <li>{@link ai.lingshu.core.slot.Tool} interface 0 改动</li>
 *   <li>{@link ai.lingshu.core.slot.ToolRegistry} interface 0 改动</li>
 *   <li>{@link ai.lingshu.core.slot.ToolExecutor#dispatch} 5 步流水线 0 改动</li>
 *   <li>0 新 Maven 依赖 — 复用 spring-ai-bom(已锁)+ Jackson + Lombok 已锁 13 项</li>
 *   <li>本注解**不**支持 static method / {@code @Bean} 工厂方法 —— 仅扫
 *       {@code @Component} Bean 实例方法(对齐 dsh §6.5 (3) L4970 字面
 *       {@code ctx.getBeansWithAnnotation(Component.class).values()})</li>
 * </ul>
 *
 * <p><b>JDK 8 兼容</b> — 标准 {@code @Retention(RUNTIME)} 元注解,不引
 * {@code spring-ai-spring-boot-starter} / {@code javax.validation}。
 *
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AgentTool {

    /**
     * Tool 名;the LLM sees this in {@code ToolSpec.name} and uses it for
     * {@code tool_use.name} in ReAct Action phase.
     *
     * <p>Must be unique within the {@code @Component} Bean scope — duplicate
     * registrations are first-wins + log warn
     * (see {@link ai.lingshu.core.impl.tool.DefaultToolRegistry#register}).
     */
    String name();

    /**
     * Human-readable description; surfaced to the LLM so it can decide when to call this tool.
     */
    String description();

    /**
     * 预留 capability tags (e.g. {@code "fs.write"}, {@code "net.http"}, {@code "process.exec"}).
     *
     * <p>当前 <b>未消费</b> —— {@link SpringAiToolAdapter#execute} 不读此字段。
     * 留给 §14.10 N10 audit-log Story 或 §4.7 权限审批门扩展时一并启用。
     *
     * <p>默认空数组表示无声明 capability。
     */
    String[] capabilities() default {};
}
