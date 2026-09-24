package ai.lingshu.examples.demoparallel;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/** Story #004 demo — SleepTool(suffix='d). 同 SleepTool / SleepToolB / SleepToolC 行为。 */
@Component("sleepD")
public class SleepToolD implements Tool {

    @Override public String name() { return "sleep_d"; }
    @Override public String description() { return "Sleep for input.ms milliseconds; suffix=d"; }

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
            ? call.getInput().get("ms").asLong(1000L) : 1000L;
        try { Thread.sleep(ms); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.builder().status(ToolResult.Status.ERROR).toolUseId(call.getId())
                .content("interrupted").isError(true).build();
        }
        return ToolResult.builder().status(ToolResult.Status.SUCCESS).toolUseId(call.getId())
            .content("slept " + ms + "ms (suffix=d)").isError(false).build();
    }
}
