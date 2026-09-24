package ai.lingshu.core.tool;

/**
 * Tool 域 ErrorCode 常量集中 (Story #022, dsh v1.5.40 §15.4).
 *
 * <p><b>单一真源 (Single Source of Truth)</b> —— 集中 {@code LINGS-Txx} 字符串常量,
 * 避免散落在 {@link SpringAiToolAdapter#execute} 等多个 call site 的拼写漂移
 * (对齐 #021b {@code McpErrorCodes} 同款模式,plan §1)。
 *
 * <p><b>本类新增</b>:
 * <ul>
 *   <li>{@link #LINGS_T08} — {@code TOOL_REFLECTION_FAILED}。
 *       抛出位置:{@link SpringAiToolAdapter#execute} catch-all 子句。
 *       触发条件: {@code @AgentTool} method 在反射调用时抛 {@code InvocationTargetException}
 *       包裹的业务异常、或 {@code Exception}(e.g. {@code IllegalArgumentException}
 *       参数类型不匹配 / {@code IllegalAccessException} 非 public method)。
 *       用户响应:检查 {@code @AgentTool} 方法实现 / 检查 LLM 输出参数类型与 method signature 对齐。</li>
 * </ul>
 *
 * <p><b>关键不变项</b> — dsh §15.4 域字母 T 编号表 T01—T07 编号全部不动
 * ({@code T01 = TOOL_NOT_FOUND / T02 = TOOL_TIMEOUT / T03 = TOOL_VALIDATION_FAILED /
 * T04 = TOOL_EXECUTION_FAILED / T05 = TOOL_APPROVAL_DENIED /
 * T06 = TOOL_PARALLELISM_EXCEEDED / T07 = TOOL_CIRCUIT_OPEN}),
 * <b>只</b>新增 {@code T08}。修正 {@code ROADMAP.md} 段二表第 7 行的「LINGS-T02」标记
 * (实为抄写误差;{@code LINGS-T02 TOOL_TIMEOUT} 已被 {@code ToolExecutor.dispatch}
 * 用于 {@code toolTimeoutSec} 超时,不可复用)。
 *
 * <p><b>报错编码约定</b>(dsh §15 + §4.10.1 硬规则 2)——
 * {@link SpringAiToolAdapter#execute} 抛出的 ToolResult.error() 必须在
 * {@code content} 字段嵌入 {@code [LINGS-T08]} 形式编码,LLM 能从 error message 中
 * 识别 cause 类别(对齐 {@code McpToolAdapter.execute()} 同款模式)。
 *
 * <p><b>JDK 8 兼容</b> — 传统静态常量类,无 {@code record} / sealed / var。
 *
 * @since 1.0.0
 */
public final class ToolErrorCodes {

    private ToolErrorCodes() {
        // utility class
    }

    /**
     * Tool 反射调用失败(Story #022)—— {@code SpringAiToolAdapter.execute()} 内部
     * catch-all 转 LLM 可见 error 时嵌入此码。
     *
     * <p>完整语义见 dsh §15.4 第 8 行 {@code LINGS-T08 TOOL_REFLECTION_FAILED}。
     */
    public static final String LINGS_T08 = "LINGS-T08";
}
