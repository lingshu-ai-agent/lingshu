package ai.lingshu.core.mcp;

import ai.lingshu.core.impl.mcp.McpErrorCodes;
import ai.lingshu.core.impl.mcp.McpTransport;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP tool 适配器 (Story #021b, dsh §6.5 (2) L4456-4493).
 *
 * <p><b>What</b> — 把 MCP server 暴露的一个 tool 包装成 LingShu {@link Tool} 接口,
 * 让 {@code ToolExecutor.dispatch} 通过统一的 5 步流水线(§4.10.1 硬规则 2)调用 MCP tool,
 * 不知道(也不需要知道)execute 实际转发到 MCP server。
 *
 * <p><b>ctor 参数说明</b>(plan §3.2.1):
 * <ul>
 *   <li>{@code transport} — 转发目标</li>
 *   <li>{@code serverName} — 用于 {@code transport.callTool(serverName, remoteToolName, input)}</li>
 *   <li>{@code namespacedName} — 用于 {@link ai.lingshu.core.slot.ToolRegistry} 索引
 *       ({@link #name()} 返回值);e.g. {@code "github:search_repos"}</li>
 *   <li>{@code desc} — 远端 tool 元数据;{@code desc.getName()} 作为
 *       {@code remoteToolName} 用于转发</li>
 * </ul>
 *
 * <p><b>错误语义</b>(plan §3.2.2):
 * <ul>
 *   <li>{@link McpCallResult#isError()} == true → {@link ToolResult.Status#ERROR} 含 errorMessage</li>
 *   <li>{@link McpTransportException} 已知 MCP 错误 → ERROR 含 code + message</li>
 *   <li>其他 {@link Exception} 未预期 → 转 {@link McpErrorCodes#LINGS_M02} ERROR</li>
 *   <li>任何情况下{@link #execute <b>不抛异常</b>}(§4.10.1 硬规则 2)</li>
 * </ul>
 *
 * <p><b>JDK 8 兼容</b> — POJO,无 {@code record} / sealed / {@code var}。
 */
public class McpToolAdapter implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolAdapter.class);

    private final McpTransport transport;
    private final String serverName;
    /** ToolRegistry 索引用的完整名(serverName + ":" + toolName)。 */
    private final String namespacedName;
    /** 转发到 {@code tools/call} 用的远端 tool 名(去除命名空间)。 */
    private final String remoteToolName;
    private final String description;
    private final JsonNode inputSchema;

    /**
     * @param transport       McpTransport 协调者;不能 null
     * @param serverName      MCP server 名;不能 null
     * @param namespacedName  ToolRegistry 索引名 = {@code serverName + ":" + remoteToolName};不能 null
     * @param desc            MCP tool 元数据;不能 null
     * @throws IllegalArgumentException 任一参数为 null
     */
    public McpToolAdapter(McpTransport transport, String serverName,
                          String namespacedName, McpToolDescriptor desc) {
        if (transport == null) {
            throw new IllegalArgumentException("transport must not be null");
        }
        if (serverName == null) {
            throw new IllegalArgumentException("serverName must not be null");
        }
        if (namespacedName == null) {
            throw new IllegalArgumentException("namespacedName must not be null");
        }
        if (desc == null) {
            throw new IllegalArgumentException("desc must not be null");
        }
        this.transport = transport;
        this.serverName = serverName;
        this.namespacedName = namespacedName;
        this.remoteToolName = desc.getName();
        this.description = desc.getDescription();
        this.inputSchema = desc.getInputSchema();
    }

    @Override
    public String name() {
        return namespacedName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public JsonNode inputSchema() {
        return inputSchema;
    }

    /**
     * 转发 tool call 到 MCP server。**永不抛异常** —— 所有失败路径转
     * {@link ToolResult.Status#ERROR}(§4.10.1 硬规则 2)。
     */
    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        try {
            McpCallResult r = transport.callTool(serverName, remoteToolName, call.getInput());
            if (r.isError()) {
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content(r.getErrorMessage() != null ? r.getErrorMessage() : "MCP call failed")
                    .isError(true)
                    .build();
            }
            return ToolResult.builder()
                .status(ToolResult.Status.SUCCESS)
                .toolUseId(call.getId())
                .content(r.getContent() != null ? r.getContent() : "")
                .isError(false)
                .build();
        } catch (McpTransportException e) {
            // 已知 MCP 错误 —— 用原 code 转 LLM 可见 error
            LOG.warn("[MCP:{}] call failed code={} msg={}",
                serverName, e.getCode(), e.getMessage());
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("MCP call failed [" + e.getCode() + "]: "
                    + (e.getMessage() != null ? e.getMessage() : ""))
                .isError(true)
                .build();
        } catch (Exception e) {
            // 未预期 —— 转 LINGS-M02
            LOG.warn("[MCP:{}] call threw {}: {}",
                serverName, e.getClass().getSimpleName(), e.toString());
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("MCP call failed [" + McpErrorCodes.LINGS_M02 + "]: "
                    + (e.getMessage() != null ? e.getMessage() : ""))
                .isError(true)
                .build();
        }
    }
}