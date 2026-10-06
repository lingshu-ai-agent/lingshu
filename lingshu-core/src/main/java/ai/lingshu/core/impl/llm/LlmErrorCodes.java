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
package ai.lingshu.core.impl.llm;

/**
 * LlmProvider 域 ErrorCode 常量集中 (Story #027a, dsh v1.5.44 §15.6).
 *
 * <p><b>单一真源 (Single Source of Truth)</b> —— 集中 {@code LINGS-Lxx} 字符串常量,
 * 避免散落在 {@link AnthropicLlmProvider#buildRequestBody} /
 * {@link AnthropicLlmProvider#parseResponse} 多个 call site 的拼写漂移(对齐 #021b
 * {@link ai.lingshu.core.impl.mcp.McpErrorCodes} + #022
 * {@link ai.lingshu.core.tool.ToolErrorCodes} + #023
 * {@link ai.lingshu.core.agent.DelegateErrorCodes} + #026
 * {@link ai.lingshu.core.impl.runtime.YamlPlaceholderErrorCodes} 同款模式,plan §1)。
 *
 * <p><b>本类新增</b>(dsh §15 域字母表新增 {@code L = LlmProvider} 段):
 * <ul>
 *   <li>{@link #LINGS_L01} — {@code LLM_PROTOCOL_TOOL_USE_INVALID}。
 *       抛出位置:{@link AnthropicLlmProvider#buildRequestBody}(构建请求时
 *       {@link ai.lingshu.core.message.Message.Assistant} 嵌入的 {@code ToolCall}
 *       缺 {@code id} / {@code name})与
 *       {@link AnthropicLlmProvider#parseResponse}(解析响应时
 *       {@code content[].tool_use} block 缺 {@code id} / {@code name})。
 *       触发条件:Anthropic {@code /v1/messages} 协议要求每个 {@code tool_use}
 *       block 必须带 {@code id} + {@code name};若内部 {@link ai.lingshu.core.message.Message.Assistant#toolCalls}
 *       元素缺字段(Story #024 / #020a / #022 等任意 Tool 来源拼接 history 时 bug)
 *       或 Anthropic 响应缺字段(LLM 协议越界),必须 fail-fast 抛
 *       {@link LingsLlmProviderException}(<b>不</b>静默丢弃,否则 ReAct Action
 *       阶段 {@code dispatchParallel} 收到残缺 ToolCall 会执行到一半才发现
 *       {@code name=null} 抛 NPE,根因被掩盖)。</li>
 *
 *   <li>{@link #LINGS_L02} — {@code LLM_PROTOCOL_TOOL_RESULT_INVALID}。
 *       抛出位置:{@link AnthropicLlmProvider#buildRequestBody}(构建请求时
 *       {@code Message.ToolResult} 缺 {@code toolUseId} / {@code content})。
 *       触发条件:Anthropic {@code tool_result} block 必须带 {@code tool_use_id}
 *       回链对应 {@code tool_use.id};若 {@link ai.lingshu.core.message.Message.ToolResult}
 *       缺 {@code toolUseId}(§4.6 {@code ToolExecutor.dispatch} 链路漏掉 id 透传
 *       或 §4.10 {@code MessageAssembler} 拼装 bug),Anthropic API 直接
 *       {@code 400 Bad Request},延迟到 HTTP 层报错的根因会被 LLM provider
 *       转成 {@code "Anthropic HTTP 400"} 笼统消息。fail-fast 在 buildRequestBody
 *       阶段抛 {@link LingsLlmProviderException} 嵌入此码,让 Story 实施者一眼
 *       看出 {@code toolUseId} 透传链路断裂。</li>
 * </ul>
 *
 * <p><b>关键不变项</b> — dsh §15 域字母 C / S / L / X / R / A / Z 编号全部不动,
 * dsh §15 域字母表新增 {@code L = LlmProvider} 段(L01 / L02 为本批次首批,后续
 * <b>#027b</b> 流式 SSE + OpenAI / Gemini 协议转换等 Story 顺延 L03+);域字母
 * 总数 9 → 10(原 C/S/T/X/R/A/Z + M(MCP,Story #021) + D(Delegate,Story #023)
 * + L(LlmProvider,Story #027a))。
 *
 * <p><b>报错编码约定</b>(dsh §15 + §4.10.1 硬规则 2)——
 * {@link LingsLlmProviderException#getMessage} 必须含 {@code [LINGS-L0X]} 前缀
 * + 人读 explanation,沿用 {@link ai.lingshu.core.exception.LingsConfigException}
 * (Story #006 起的 C02 / C03 / C04 嵌入模式)+ Story #023
 * {@code LinearTurnEngine.L117 "LINGS-C02 CONFIG_VALIDATION_FAILED: ..."}
 * 嵌入 message 模式,让 {@code assertThatThrownBy().hasMessageContaining("LINGS-L0X")}
 * 工作。
 *
 * <p><b>JDK 8 兼容</b> — 传统 {@code final class} + 私有 ctor 抛 {@link AssertionError}
 * 的 utility class 模式,无 record / sealed / var。
 *
 * @since 1.5.44
 */
public final class LlmErrorCodes {

    /**
     * LLM 协议 tool_use 字段无效(Story #027a)——
     * Anthropic {@code /v1/messages} 请求 / 响应中 {@code tool_use} block 缺
     * {@code id} / {@code name} 必填字段时抛 {@link LingsLlmProviderException}
     * 嵌入此码。
     *
     * <p>完整语义见 dsh §15.6 第 1 行 {@code LINGS-L01 LLM_PROTOCOL_TOOL_USE_INVALID}。
     */
    public static final String LINGS_L01 = "LINGS-L01";

    /**
     * LLM 协议 tool_result 字段无效(Story #027a)——
     * Anthropic {@code /v1/messages} 请求中 {@code tool_result} block 缺
     * {@code tool_use_id} / {@code content} 必填字段时抛
     * {@link LingsLlmProviderException} 嵌入此码。
     *
     * <p>完整语义见 dsh §15.6 第 2 行 {@code LINGS-L02 LLM_PROTOCOL_TOOL_RESULT_INVALID}。
     */
    public static final String LINGS_L02 = "LINGS-L02";

    /**
     * 🆕 Story #027b reserved — 占位常量,本期 <b>不</b>抛,预留给 dsh §14 N6
     * <i>graceful shutdown</i> 在 Anthropic SSE 流式连接中途被取消时上报用。
     *
     * <p>完整语义待 dsh §14 N6 实施时补 —— 见 dsh §15.6 第 3 行
     * {@code LINGS-L03 LLM_STREAM_ABORTED_BY_SHUTDOWN}。在 #027b 内,此常量仅
     * 用于单测与契约文档占位,实现层 <b>不</b>触发。
     *
     * <p>编号约束:dsh §15 域字母表新增 {@code L = LlmProvider} 段,L01 / L02
     * 由 #027a 实装;L03+ 顺延;#027b 锁定 L03 占位但本期不抛。
     */
    public static final String LINGS_L03 = "LINGS-L03";

    private LlmErrorCodes() {
        // utility class — instantiation is a programming error
        throw new AssertionError("LlmErrorCodes must not be instantiated");
    }
}