package ai.lingshu.examples.demoparallel;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Story #004 demo — 4 个独立 SleepTool @Component,各 sleep 1000ms。
 *
 * <p>4 个 component bean 名 sleepA / sleepB / sleepC / sleepD 对应 Tool 名 sleep_a/b/c/d。
 * 实测 4 个并发 dispatch 时 wall-clock ≤ 1.3s(vs 4s 串行)。
 *
 * <p>Tool 接口契约见 dsh §4.6:name() / description() / inputSchema() / execute() 4 方法;
 * 本实现零反射,直接 JSON parse input.ms 后 Thread.sleep + 返回 ToolResult.success。
 */
@Component("sleepA")
public class SleepTool implements Tool {

    private final String suffix;

    public SleepTool() {
        this.suffix = "a";
    }

    @Override public String name() { return "sleep_" + suffix; }
    @Override public String description() { return "Sleep for input.ms milliseconds; name suffix='" + suffix + "'"; }

    @Override
    public JsonNode inputSchema() {
        com.fasterxml.jackson.databind.node.ObjectNode root =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        root.put("type", "object");
        com.fasterxml.jackson.databind.node.ObjectNode props =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        com.fasterxml.jackson.databind.node.ObjectNode ms =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        ms.put("type", "integer");
        props.set("ms", ms);
        root.set("properties", props);
        com.fasterxml.jackson.databind.node.ArrayNode required =
            com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        required.add("ms");
        root.set("required", required);
        return root;
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        long ms = call.getInput() != null && call.getInput().has("ms")
            ? call.getInput().get("ms").asLong(1000L)
            : 1000L;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.builder().status(ToolResult.Status.ERROR).toolUseId(call.getId())
                .content("interrupted").isError(true).build();
        }
        return ToolResult.builder().status(ToolResult.Status.SUCCESS).toolUseId(call.getId())
            .content("slept " + ms + "ms (suffix=" + suffix + ")").isError(false).build();
    }
}
