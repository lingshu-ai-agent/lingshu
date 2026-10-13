/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.lingshu.core.spi;

/**
 * SessionStore 域 ErrorCode 常量集中 (Story #014, dsh v1.5.X §15.X).
 *
 * <p><b>单一真源 (Single Source of Truth)</b> — 集中 {@code LINGS-Xxx} 字符串常量,
 * 避免散落在 {@code FileSessionStore} 多个 call site 的拼写漂移(对齐 #021b
 * {@link ai.lingshu.core.impl.mcp.McpErrorCodes} + #022
 * {@link ai.lingshu.core.tool.ToolErrorCodes} + #023
 * {@link ai.lingshu.core.agent.DelegateErrorCodes} + #026
 * {@link ai.lingshu.core.impl.runtime.YamlPlaceholderErrorCodes} + #027a
 * {@code LlmErrorCodes} 同款模式)。
 *
 * <p><b>本类新增</b>(dsh §15 域字母表新增 {@code X = SessionStore} 段):
 * <ul>
 *   <li>{@link #LINGS_X01} — {@code SESSION_STORE_IO_FAILED}。抛出位置:
 *       {@code FileSessionStore.save} (Jackson 序列化失败 / {@code Files.write}
 *       写 .tmp 失败 / {@code Files.move ATOMIC_MOVE} 失败) 与 {@code FileSessionStore.load}
 *       (Jackson 反序列化失败 / 损坏文件读取失败)。触发条件:文件 IO 阶段任何
 *       底层异常都应该 fail-fast 抛 {@link LingsSessionStoreException},嵌入
 *       {@code [LINGS-X01]} 前缀 + 人读 explanation,而不是让原始 IOException
 *       裸奔到 Agent 主循环里(Plan #006 §4.10.1 错误处理边界 — 沙箱 / 权限 /
 *       checkpoint 全链路都依赖工具调用抛带 ErrorCode 前缀的异常来区分错误源)。</li>
 * </ul>
 *
 * <p><b>关键不变项</b> — dsh §15 域字母 C / S / L / T / X / R / A / M / D / P / Z
 * 编号全部不动,dsh §15 域字母表新增 {@code X = SessionStore} 段(X01 为本批次首批);
 * 域字母总数 11 → 11(原 C/S/T/X/R/A/Z + M(MCP,Story #021) + D(Delegate,Story #023)
 * + L(LlmProvider,Story #027a) + P(Permission,Story #029),X 是首次启用但域字母
 * 在 v1.5.7 §15 即已定义)。
 *
 * <p><b>报错编码约定</b>(dsh §15 + §4.10.1 硬规则 2)——
 * {@link LingsSessionStoreException#getMessage} 必须含 {@code [LINGS-X01]} 前缀
 * + 人读 explanation,沿用 {@link ai.lingshu.core.exception.LingsConfigException}
 * (Story #006 起的 C02 / C03 / C04 嵌入模式)+ Story #023
 * {@code LinearTurnEngine.L117 "LINGS-C02 CONFIG_VALIDATION_FAILED: ..."}
 * 嵌入 message 模式,让 {@code assertThatThrownBy().hasMessageContaining("LINGS-X01")}
 * 工作。
 *
 * <p><b>JDK 8 兼容</b> — 传统 {@code final class} + 私有 ctor 抛 {@link AssertionError}
 * 的 utility class 模式,无 record / sealed / var。
 *
 * @since 1.5.X
 */
public final class SessionStoreErrorCodes {

    /**
     * SessionStore File 后端 IO 失败 (Story #014) ——
     * {@code FileSessionStore.save} 或 {@code FileSessionStore.load} 阶段任何
     * 底层 IO / Jackson 异常都应该包装为 {@link LingsSessionStoreException} 嵌入此码。
     *
     * <p>完整语义见 dsh §15 X 段第 1 行 {@code LINGS-X01 SESSION_STORE_IO_FAILED}。
     */
    public static final String LINGS_X01 = "LINGS-X01";

    private SessionStoreErrorCodes() {
        // utility class — instantiation is a programming error
        throw new AssertionError("SessionStoreErrorCodes must not be instantiated");
    }
}