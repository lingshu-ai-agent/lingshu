package ai.lingshu.core.tool;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story #022 — L1 + L2 tests for {@link SpringAiToolAdapter}.
 *
 * <p>覆盖 Story #022 spec §4 AC-NN-2 / AC-NN-3 / AC-NN-6 / 反向 AC。
 *
 * <p><b>为什么不依赖 Spring 启动 ctx</b> — SpringAiToolAdapter 是 POJO,
 * 不需要 Spring DI;所有测试都是裸 POJO 互操作(reflection + JSON + ToolResult)。
 */
class SpringAiToolAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("AC-022-13: name_andDescription_matchAnnotation")
    void name_andDescription_matchAnnotation() throws Exception {
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("add", int.class, int.class);
        AgentTool at = m.getAnnotation(AgentTool.class);

        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);

        assertThat(t.name()).isEqualTo("add");
        assertThat(t.description()).isEqualTo("两数相加");
    }

    @Test
    @DisplayName("AC-022-14: inputSchema_includesPropertiesAndRequiredForPrimitives")
    void inputSchema_includesPropertiesAndRequiredForPrimitives() throws Exception {
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("add", int.class, int.class);
        AgentTool at = m.getAnnotation(AgentTool.class);

        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);
        JsonNode schema = t.inputSchema();

        assertThat(schema.get("type").asText()).isEqualTo("object");
        assertThat(schema.get("properties").get("a").get("type").asText()).isEqualTo("integer");
        assertThat(schema.get("properties").get("b").get("type").asText()).isEqualTo("integer");
        // primitive 必传 → required
        JsonNode required = schema.get("required");
        assertThat(required.isArray()).isTrue();
        assertThat(required.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-022-15: inputSchema_forStringParam_returnsStringType")
    void inputSchema_forStringParam_returnsStringType() throws Exception {
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("greet", String.class);
        AgentTool at = m.getAnnotation(AgentTool.class);

        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);
        JsonNode schema = t.inputSchema();

        assertThat(schema.get("properties").get("who").get("type").asText()).isEqualTo("string");
    }

    @Test
    @DisplayName("AC-022-16: inputSchema_complexObjectType_returnsObjectPlaceholder")
    void inputSchema_complexObjectType_returnsObjectPlaceholder() throws Exception {
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("complexMethod", Object.class);
        AgentTool at = m.getAnnotation(AgentTool.class);

        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);
        JsonNode schema = t.inputSchema();
        // Object 复杂类型 → "object" placeholder,OQ-Future 给 jackson-module-jsonSchema
        assertThat(schema.get("properties").get("payload").get("type").asText()).isEqualTo("object");
    }

    @Test
    @DisplayName("AC-022-17: execute_happyPath_returnsSuccessWithToStringContent")
    void execute_happyPath_returnsSuccessWithToStringContent() throws Exception {
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("add", int.class, int.class);
        AgentTool at = m.getAnnotation(AgentTool.class);
        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("a", 2);
        input.put("b", 3);
        ToolCall call = new ToolCall("c1", "add", input);

        ToolResult r = t.execute(call, null);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.isError()).isFalse();
        assertThat(r.getToolUseId()).isEqualTo("c1");
        assertThat(r.getContent()).isEqualTo("5");
        // 调用计数 1 次(ac-022 happy path)
        assertThat(bean.addCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-022-18: execute_businessException_returnsToolResultErrorWithLINGS_T08")
    void execute_businessException_returnsToolResultErrorWithLINGS_T08() throws Exception {
        // 业务方法抛 RuntimeException → InvocationTargetException 包 → 转 ToolResult.error
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("boom");
        AgentTool at = m.getAnnotation(AgentTool.class);
        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);

        ToolCall call = new ToolCall("c2", "boom", MAPPER.createObjectNode());
        ToolResult r = t.execute(call, null);

        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.isError()).isTrue();
        assertThat(r.getContent())
            .as("ErrorCode must be embedded in content per McpToolAdapter pattern")
            .contains("[LINGS-T08]")
            .contains("svc down");
    }

    @Test
    @DisplayName("AC-022-19: execute_illegalArgumentTypeMismatch_returnsToolResultError")
    void execute_illegalArgumentTypeMismatch_returnsToolResultError() throws Exception {
        // LLM 给 string 而 method 接 int → JsonArgsConverter 抛 IAE → catch-all → LINGS-T08
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("add", int.class, int.class);
        AgentTool at = m.getAnnotation(AgentTool.class);
        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);

        ObjectNode input = MAPPER.createObjectNode();
        input.put("a", "not_a_number");
        input.put("b", 3);
        ToolCall call = new ToolCall("c3", "add", input);

        ToolResult r = t.execute(call, null);
        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).contains("[LINGS-T08]");
        // business bean 不该被 invoke(arg type 不匹配早早抛)
        assertThat(bean.addCalls()).isZero();
    }

    @Test
    @DisplayName("AC-022-20: capabilities_doesNotAffectExecutionContent")
    void capabilities_doesNotAffectExecutionContent() throws Exception {
        // capabilities 字段保留但不消费(spec §反向 AC)
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("add", int.class, int.class);
        AgentTool at = m.getAnnotation(AgentTool.class);
        assertThat(at.capabilities()).isEmpty();   // 当前 add 上没有声明

        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("a", 1);
        input.put("b", 1);
        ToolCall call = new ToolCall("c4", "add", input);

        ToolResult r = t.execute(call, null);
        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).isEqualTo("2");
    }

    @Test
    @DisplayName("AC-022-21: execute_nullCallReturnsImmediatelyError")
    void execute_nullCallReturnsImmediatelyError() throws Exception {
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("add", int.class, int.class);
        AgentTool at = m.getAnnotation(AgentTool.class);
        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);

        ToolResult r = t.execute(null, null);
        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.ERROR);
        assertThat(r.getContent()).contains("[LINGS-T08]");
    }

    @Test
    @DisplayName("AC-022-22: execute_withCapabilitiesAnnotation_doesNotConsumeIt")
    void execute_withCapabilitiesAnnotation_doesNotConsumeIt() throws Exception {
        // capability 字段保留但 adapter.execute() 不读
        SampleTools bean = new SampleTools();
        Method m = SampleTools.class.getMethod("caps", String.class);
        AgentTool at = m.getAnnotation(AgentTool.class);
        assertThat(at.capabilities()).containsExactly("fs.write", "process.exec");

        SpringAiToolAdapter t = new SpringAiToolAdapter(bean, m, at);
        ObjectNode input = MAPPER.createObjectNode();
        input.put("msg", "hello");
        ToolCall call = new ToolCall("c5", "caps", input);

        ToolResult r = t.execute(call, null);
        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
        assertThat(r.getContent()).isEqualTo("caps:hello");
        // caps 行为 = greet 一样 message,PermissionPolicy 没拦(不会消费 capabilities() 字段)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Test fixture
    // ──────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    public static class SampleTools {
        private int addCalls = 0;

        @AgentTool(name = "add", description = "两数相加")
        public int add(int a, int b) {
            addCalls++;
            return a + b;
        }

        @AgentTool(name = "greet", description = "打个招呼")
        public String greet(String who) {
            return "hi " + who;
        }

        @AgentTool(name = "boom", description = "必抛")
        public String boom() {
            throw new RuntimeException("svc down");
        }

        @AgentTool(name = "complexMethod", description = "复杂类型演示")
        public Object complexMethod(Object payload) {
            return payload;
        }

        @AgentTool(name = "caps",
                   description = "带 capabilities",
                   capabilities = {"fs.write", "process.exec"})
        public String caps(String msg) {
            return "caps:" + msg;
        }

        public int addCalls() {
            return addCalls;
        }
    }
}
