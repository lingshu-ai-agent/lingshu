package ai.lingshu.core.impl.tool;

import ai.lingshu.core.decision.Decision;
import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.PermissionPolicy;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Story #004 — L1 Unit tests for DefaultToolExecutor translation logic (FR-007 + FR-008).
 *
 * <p>Verifies that {@link DefaultToolExecutor#dispatch(ToolCall, ToolExecutionContext)}
 * translates exceptions into {@link ToolResult#error} responses instead of throwing.
 * This is the contract the engine relies on to keep the ReAct loop running past failures.
 */
class DefaultToolExecutorTest {

    private DefaultToolExecutor executor;
    private DefaultToolRegistry registry;
    private PermissionPolicy policy;
    private ToolExecutionContext ctx;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        policy = mock(PermissionPolicy.class);
        registry = new DefaultToolRegistry();
        executor = new DefaultToolExecutor(policy, registry);
        ctx = mock(ToolExecutionContext.class);
        mapper = new ObjectMapper();
    }

    private static ToolCall call(String name, String id) {
        JsonNode empty = NullNode.getInstance();
        return new ToolCall(id, name, empty);
    }

    /** Simple success Tool stub. */
    private static Tool successTool(String name, String resultContent) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "ok"; }
            @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext c) {
                return ToolResult.builder()
                    .status(ToolResult.Status.SUCCESS)
                    .toolUseId(call.getId())
                    .content(resultContent)
                    .isError(false)
                    .build();
            }
        };
    }

    /** Tool that throws a generic RuntimeException. */
    private static Tool throwingTool(String name, RuntimeException ex) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "throws"; }
            @Override public JsonNode inputSchema() { return NullNode.getInstance(); }
            @Override public ToolResult execute(ToolCall call, ToolExecutionContext c) {
                throw ex;
            }
        };
    }

    @Test
    @DisplayName("L1-004: dispatch_success_returnsOriginalResult (regression)")
    void dispatch_success_returnsOriginalResult() {
        registry.register(successTool("read_file", "contents-of-file"));
        when(policy.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new Decision.Allow("test"));

        ToolResult result = executor.dispatch(call("read_file", "call-1"), ctx);

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(result.getToolUseId()).isEqualTo("call-1");
        assertThat(result.getContent()).isEqualTo("contents-of-file");
        assertThat(result.isError()).isFalse();
    }

    @Test
    @DisplayName("L1-001: dispatch_permissionDenied_returnsErrorResult (FR-007)")
    void dispatch_permissionDenied_returnsErrorResult() {
        when(policy.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new Decision.Deny("path /etc is forbidden"));

        ToolResult result = executor.dispatch(call("read_file", "call-1"), ctx);

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.getToolUseId()).isEqualTo("call-1");
        assertThat(result.getContent()).isEqualTo("Permission denied: path /etc is forbidden");
        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("L1-002: dispatch_toolNotFound_returnsErrorResult (FR-007)")
    void dispatch_toolNotFound_returnsErrorResult() {
        when(policy.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new Decision.Allow("test"));

        ToolResult result = executor.dispatch(call("nonexistent_tool", "call-2"), ctx);

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.getToolUseId()).isEqualTo("call-2");
        assertThat(result.getContent()).isEqualTo("Tool not registered: nonexistent_tool");
        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("L1-005: dispatch_permissionAskUser_returnsErrorResult (Edge Case)")
    void dispatch_permissionAskUser_returnsErrorResult() {
        when(policy.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new Decision.AskUser("Run rm -rf?", Collections.<Decision.Option>emptyList()));

        ToolResult result = executor.dispatch(call("bash", "call-3"), ctx);

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.getToolUseId()).isEqualTo("call-3");
        assertThat(result.getContent()).contains("AskUser approval flow");
        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("L1-003: dispatch_unexpectedException_returnsErrorResult (FR-008)")
    void dispatch_unexpectedException_returnsErrorResult() {
        registry.register(throwingTool("buggy_tool", new NullPointerException("NPE inside tool")));
        when(policy.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new Decision.Allow("test"));

        ToolResult result = executor.dispatch(call("buggy_tool", "call-4"), ctx);

        assertThat(result.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(result.getToolUseId()).isEqualTo("call-4");
        assertThat(result.getContent()).startsWith("tool error:");
        assertThat(result.getContent()).contains("NPE inside tool");
        assertThat(result.isError()).isTrue();
    }

    @Test
    @DisplayName("L1-006: register_duplicateSecondWarnsAndKeepsFirst")
    void register_duplicateSecondWarnsAndKeepsFirst() {
        Tool first = successTool("read_file", "first");
        Tool second = successTool("read_file", "second");
        registry.register(first);
        registry.register(second);

        // Second register call should warn + keep first tool
        when(policy.check(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new Decision.Allow("test"));
        ToolResult result = executor.dispatch(call("read_file", "call-5"), ctx);

        assertThat(result.getContent()).isEqualTo("first");
    }
}
