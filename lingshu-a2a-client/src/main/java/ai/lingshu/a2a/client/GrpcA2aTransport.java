package ai.lingshu.a2a.client;

import ai.lingshu.a2a.v1.A2aServiceGrpc;
import ai.lingshu.a2a.v1.AgentName;
import ai.lingshu.a2a.v1.CancelAck;
import ai.lingshu.a2a.v1.Card;
import ai.lingshu.a2a.v1.SubmitRequest;
import ai.lingshu.a2a.v1.Task;
import ai.lingshu.a2a.v1.TaskEvent;
import ai.lingshu.a2a.v1.TaskId;
import ai.lingshu.core.message.ToolResult;
import ai.lingshu.core.slot.A2aTransport;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Story #009a — Contract A1 consumer: gRPC implementation of {@link A2aTransport}.
 *
 * <p>Maps 5 A2aTransport methods to A2aService gRPC RPCs:</p>
 * <ul>
 *   <li>{@link #fetchCard(String)} -> {@code GetCard} (with {@link AgentCardCache})</li>
 *   <li>{@link #submit(String, String, String)} -> {@code Submit}</li>
 *   <li>{@link #get(String)} -> {@code GetTask}</li>
 *   <li>{@link #cancel(String)} -> {@code Cancel}</li>
 *   <li>{@link #subscribe(String, Consumer)} -> {@code Subscribe} (server streaming)</li>
 * </ul>
 *
 * <p>Lifecycle: {@link #close()} (via {@code @PreDestroy}) shuts down the channel with 5s await.</p>
 */
public class GrpcA2aTransport implements A2aTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcA2aTransport.class);

    private final ManagedChannel channel;
    private final A2aServiceGrpc.A2aServiceBlockingStub blockingStub;
    private final A2aServiceGrpc.A2aServiceStub asyncStub;
    private final AgentCardCache cardCache;
    private final String grpcTarget;  // for diagnostics

    public GrpcA2aTransport(ManagedChannel channel, AgentCardCache cardCache, String grpcTarget) {
        if (channel == null) throw new IllegalArgumentException("channel must not be null");
        if (cardCache == null) throw new IllegalArgumentException("cardCache must not be null");
        this.channel = channel;
        this.blockingStub = A2aServiceGrpc.newBlockingStub(channel);
        this.asyncStub = A2aServiceGrpc.newStub(channel);
        this.cardCache = cardCache;
        this.grpcTarget = grpcTarget == null ? "<unknown>" : grpcTarget;
    }

    @Override
    public Map<String, Object> fetchCard(String agentName) {
        if (agentName == null || agentName.isEmpty()) {
            throw new IllegalArgumentException("agentName must not be null/empty");
        }
        // 1) cache lookup
        Map<String, Object> cached = cardCache.get(agentName);
        if (cached != null) {
            log.debug("[A2aTransport] cache hit for agent='{}'", agentName);
            return cached;
        }
        // 2) gRPC call
        try {
            AgentName req = AgentName.newBuilder().setName(agentName).build();
            Card card = blockingStub.getCard(req);
            Map<String, Object> result = toMap(card);
            cardCache.put(agentName, result);
            return result;
        } catch (StatusRuntimeException e) {
            // negative cache on NOT_FOUND / UNAVAILABLE per Contract A1.4
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.NOT_FOUND
                    || code == Status.Code.UNAVAILABLE
                    || code == Status.Code.DEADLINE_EXCEEDED) {
                cardCache.putNegative(agentName);
                if (code == Status.Code.DEADLINE_EXCEEDED) {
                    log.warn("[A2aTransport] DEADLINE_EXCEEDED fetching card for agent='{}'", agentName);
                }
            }
            throw new RuntimeException(
                "gRPC GetCard failed for agent='" + agentName + "' [target=" + grpcTarget + "]: "
                    + e.getStatus(), e);
        }
    }

    @Override
    public ToolResult submit(String agentName, String skill, String inputJson) {
        if (agentName == null || agentName.isEmpty()) {
            return toolError("agentName must not be null/empty");
        }
        if (skill == null || skill.isEmpty()) {
            return toolError("skill must not be null/empty");
        }
        try {
            SubmitRequest req = SubmitRequest.newBuilder()
                .setAgentName(agentName)
                .setSkill(skill)
                .setInputJson(inputJson == null ? "" : inputJson)
                .build();
            Task task = blockingStub.submit(req);
            return taskToToolResult(task);
        } catch (StatusRuntimeException e) {
            log.error("[A2aTransport] gRPC Submit failed: agent='{}' skill='{}' status={}",
                agentName, skill, e.getStatus());
            return toolError("gRPC Submit failed: " + e.getStatus().getDescription());
        }
    }

    @Override
    public ToolResult get(String taskId) {
        if (taskId == null || taskId.isEmpty()) {
            return toolError("taskId must not be null/empty");
        }
        try {
            TaskId req = TaskId.newBuilder().setId(taskId).build();
            Task task = blockingStub.getTask(req);
            return taskToToolResult(task);
        } catch (StatusRuntimeException e) {
            log.error("[A2aTransport] gRPC GetTask failed: taskId='{}' status={}",
                taskId, e.getStatus());
            return toolError("gRPC GetTask failed: " + e.getStatus().getDescription());
        }
    }

    @Override
    public boolean cancel(String taskId) {
        if (taskId == null || taskId.isEmpty()) return false;
        try {
            TaskId req = TaskId.newBuilder().setId(taskId).build();
            CancelAck ack = blockingStub.cancel(req);
            return ack.getAccepted();
        } catch (StatusRuntimeException e) {
            log.error("[A2aTransport] gRPC Cancel failed: taskId='{}' status={}",
                taskId, e.getStatus());
            return false;
        }
    }

    @Override
    public void subscribe(String taskId, Consumer<Map<String, Object>> onEvent) {
        if (taskId == null || taskId.isEmpty()) {
            throw new IllegalArgumentException("taskId must not be null/empty");
        }
        if (onEvent == null) {
            throw new IllegalArgumentException("onEvent must not be null");
        }
        TaskId req = TaskId.newBuilder().setId(taskId).build();
        asyncStub.subscribe(req, new StreamObserver<TaskEvent>() {
            @Override public void onNext(TaskEvent event) {
                try {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("taskId", event.getTaskId());
                    payload.put("eventType", event.getEventType());
                    payload.put("payloadJson", event.getPayloadJson());
                    onEvent.accept(payload);
                } catch (RuntimeException ex) {
                    log.error("[A2aTransport] subscribe onEvent handler threw", ex);
                }
            }
            @Override public void onError(Throwable t) {
                if (t instanceof StatusRuntimeException) {
                    Status.Code code = ((StatusRuntimeException) t).getStatus().getCode();
                    if (code == Status.Code.CANCELLED) {
                        log.info("[A2aTransport] subscribe cancelled for taskId='{}'", taskId);
                        return;
                    }
                }
                log.error("[A2aTransport] subscribe error for taskId='{}'", taskId, t);
            }
            @Override public void onCompleted() {
                log.debug("[A2aTransport] subscribe completed for taskId='{}'", taskId);
            }
        });
    }

    @PreDestroy
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            if (!channel.isShutdown()) {
                log.warn("[A2aTransport] channel shutdown timed out for target='{}'", grpcTarget);
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }

    // --- helpers -----------------------------------------------------------

    private static Map<String, Object> toMap(Card card) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", card.getName());
        m.put("description", card.getDescription());
        m.put("version", card.getVersion());
        m.put("skills", Arrays.asList(card.getSkillsList().toArray(new String[0])));
        return m;
    }

    private static ToolResult taskToToolResult(Task task) {
        String status = task.getStatus();
        String error = task.getError();
        String resultJson = task.getResultJson();
        if ("FAILED".equalsIgnoreCase(status) || "CANCELED".equalsIgnoreCase(status)) {
            String msg = (error == null || error.isEmpty()) ? status : error;
            return toolError(msg);
        }
        // COMPLETED / RUNNING / PENDING — return resultJson as output if present
        if (resultJson == null || resultJson.isEmpty()) {
            return toolSuccess("{}");
        }
        return toolSuccess(resultJson);
    }

    private static ToolResult toolSuccess(String content) {
        return ToolResult.builder()
            .status(ToolResult.Status.SUCCESS)
            .content(content)
            .isError(false)
            .build();
    }

    private static ToolResult toolError(String content) {
        return ToolResult.builder()
            .status(ToolResult.Status.ERROR)
            .content(content)
            .isError(true)
            .build();
    }

    // --- test-only accessors -----------------------------------------------

    ManagedChannel getChannel() { return channel; }
    AgentCardCache getCardCache() { return cardCache; }
    String getGrpcTarget() { return grpcTarget; }
}
