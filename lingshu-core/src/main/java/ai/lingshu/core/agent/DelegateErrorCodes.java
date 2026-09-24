package ai.lingshu.core.agent;

/**
 * Delegate 域 ErrorCode 常量集中 (Story #023, dsh v1.5.40 §15.5).
 *
 * <p><b>单一真源 (Single Source of Truth)</b> —— 集中 {@code LINGS-Dxx} 字符串常量,
 * 避免散落在 {@link DelegateTool#loadConfigs} / {@link DelegateAutoConfiguration}
 * 多个 call site 的拼写漂移(对齐 #021b {@link ai.lingshu.core.impl.mcp.McpErrorCodes}
 * + #022 {@link ai.lingshu.core.tool.ToolErrorCodes} 同款模式,plan §1)。
 *
 * <p><b>本类新增</b>:
 * <ul>
 *   <li>{@link #LINGS_D01} — {@code DELEGATE_CONFIG_INVALID}。
 *       抛出位置:{@link DelegateTool#loadConfigs} 启动期(构造时)。
 *       触发条件: {@code AgentConfig.delegate.types} map 缺少某个
 *       {@link SubAgentType} 的 {@code configKey} 条目(例如 {@code agent.delegate.types}
 *       写漏了 {@code reviewer: ...},则 {@link SubAgentType#REVIEWER} 启动期无法 resolve)。
 *       用户响应:补全 {@code agent.delegate.types} 中缺失的 sub-agent type 配置。</li>
 * </ul>
 *
 * <p><b>关键不变项</b> — dsh §15 域字母 C/S/L/T/X/R/A/Z 编号全部不动,
 * <b>只</b>新增 D 域字母 = {@code Delegate(子 Agent)},将域字母表从 8 扩到 9。
 * 后续若新增 {@code LINGS-D02...Dnn} 仍在本类追加。
 *
 * <p><b>报错编码约定</b>(dsh §15 + §4.10.1 硬规则 2)——
 * {@link DelegateTool#loadConfigs} 抛 {@link IllegalStateException} 时
 * message 必须含 {@code [LINGS-D01]} + {@code missing subagent_type: <key>} 形式编码,
 * 启动期 fail-fast 让用户一眼看出 cause。
 *
 * <p><b>JDK 8 兼容</b> — 传统 {@code final class} + 私有 ctor 抛 {@link AssertionError}
 * 的 utility class 模式,无 record / sealed / var。
 *
 * @since 1.0.0
 */
public final class DelegateErrorCodes {

    /**
     * Delegate 子 Agent 配置无效(Story #023)——
     * {@code agent.delegate.types} map 缺少某个 {@link SubAgentType} 的
     * {@code configKey} 条目时启动期 fail-fast,嵌入此码。
     *
     * <p>完整语义见 dsh §15.5 第 1 行 {@code LINGS-D01 DELEGATE_CONFIG_INVALID}。
     */
    public static final String LINGS_D01 = "LINGS-D01";

    private DelegateErrorCodes() {
        // utility class — instantiation is a programming error
        throw new AssertionError("DelegateErrorCodes must not be instantiated");
    }
}
