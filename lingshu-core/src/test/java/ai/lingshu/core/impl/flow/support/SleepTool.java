package ai.lingshu.core.impl.flow.support;

import ai.lingshu.core.message.ToolCall;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.Tool;
import ai.lingshu.core.slot.ToolExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Test fixture for Story #004 — {@link Tool} that sleeps for a configurable
 * duration then returns a SUCCESS {@link ToolResult}.
 *
 * <p>Two constructors:
 * <ul>
 *   <li>{@link #SleepTool(String, long)} — sleep N ms, return "OK"</li>
 *   <li>{@link #SleepTool(String, long, CountDownLatch)} — await external latch
 *       (for AC-03 black-box: synchronize all 4 parallel tool starts before
 *       measuring wall-clock)</li>
 * </ul>
 *
 * <p>Test-only class.
 */
public class SleepTool implements Tool {

    private static final Logger LOG = LoggerFactory.getLogger(SleepTool.class);

    private final String toolName;
    private final long sleepMillis;
    private final CountDownLatch startLatch;

    public SleepTool(String toolName, long sleepMillis) {
        this(toolName, sleepMillis, null);
    }

    public SleepTool(String toolName, long sleepMillis, CountDownLatch startLatch) {
        this.toolName = toolName;
        this.sleepMillis = sleepMillis;
        this.startLatch = startLatch;
    }

    @Override
    public String name() { return toolName; }

    @Override
    public String description() {
        return "Test tool that sleeps " + sleepMillis + "ms then returns OK. toolName=" + toolName;
    }

    @Override
    public JsonNode inputSchema() {
        return NullNode.getInstance();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolExecutionContext ctx) {
        if (startLatch != null) {
            try {
                startLatch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ToolResult.builder()
                    .status(ToolResult.Status.ERROR)
                    .toolUseId(call.getId())
                    .content("start latch interrupted")
                    .isError(true)
                    .build();
            }
        }
        LOG.debug("SleepTool[{}] starting {}ms sleep", toolName, sleepMillis);
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.builder()
                .status(ToolResult.Status.ERROR)
                .toolUseId(call.getId())
                .content("interrupted during sleep")
                .isError(true)
                .build();
        }
        LOG.debug("SleepTool[{}] done", toolName);
        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .toolUseId(call.getId())
            .content(toolName + ":OK")
            .isError(false)
            .build();
    }
}
