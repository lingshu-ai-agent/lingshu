package ai.lingshu.a2a.client;

import ai.lingshu.a2a.v1.A2aServiceGrpc;
import ai.lingshu.a2a.v1.AgentName;
import ai.lingshu.a2a.v1.CancelAck;
import ai.lingshu.a2a.v1.Card;
import ai.lingshu.a2a.v1.SubmitRequest;
import ai.lingshu.a2a.v1.Task;
import ai.lingshu.a2a.v1.TaskId;
import ai.lingshu.core.message.ToolResult;
import io.grpc.ManagedChannel;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L2 slice tests — {@link GrpcA2aTransport} against an in-process gRPC server
 * with a mocked A2aServiceImpl. Covers Contract A1.3 (5 RPC methods).
 */
class GrpcA2aTransportTest {

    private Server server;
    private ManagedChannel channel;
    private AgentCardCache cache;
    private GrpcA2aTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        String name = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(name)
            .directExecutor()
            .addService(new FakeA2aService())
            .build()
            .start();
        channel = InProcessChannelBuilder.forName(name).directExecutor().build();
        cache = new AgentCardCache(Duration.ofMinutes(5));
        transport = new GrpcA2aTransport(channel, cache, "in-process:" + name);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    @DisplayName("TC-T-1: fetchCard_returnsCardMap (cache miss → hit on 2nd call)")
    void fetchCard_returnsCardMap() {
        Map<String, Object> card = transport.fetchCard("alice");
        assertThat(card).containsEntry("name", "alice");
        // 2nd call hits cache
        Map<String, Object> card2 = transport.fetchCard("alice");
        assertThat(card2).isSameAs(card);
        assertThat(cache.stats().getHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-T-2: submit_returnsToolResultSuccess")
    void submit_returnsToolResultSuccess() {
        ToolResult r = transport.submit("alice", "code", "{\"prompt\":\"hi\"}");
        assertThat(r.isError()).isFalse();
        assertThat(r.getContent()).contains("\"output\"");
    }

    @Test
    @DisplayName("TC-T-3: get_returnsToolResultForCompletedTask")
    void get_returnsToolResultForCompletedTask() {
        ToolResult r = transport.get("task-1");
        assertThat(r.isError()).isFalse();
        assertThat(r.getStatus()).isEqualTo(ToolResult.Status.SUCCESS);
    }

    @Test
    @DisplayName("TC-T-4: cancel_returnsTrue")
    void cancel_returnsTrue() {
        assertThat(transport.cancel("task-1")).isTrue();
    }

    @Test
    @DisplayName("TC-T-5: subscribe_invokesCallback")
    void subscribe_invokesCallback() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Map<String, Object>> received = new AtomicReference<>();
        transport.subscribe("task-1", payload -> {
            received.set(payload);
            latch.countDown();
        });
        // Give callback a chance to fire (in-process is synchronous-ish)
        latch.await(2, TimeUnit.SECONDS);
        assertThat(received.get()).isNotNull();
    }

    @Test
    @DisplayName("TC-T-6: fetchCard_nullAgentName_throwsIAE")
    void fetchCard_nullAgentName_throwsIAE() {
        assertThatThrownBy(() -> transport.fetchCard(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-T-7: fetchCard_emptyAgentName_throwsIAE")
    void fetchCard_emptyAgentName_throwsIAE() {
        assertThatThrownBy(() -> transport.fetchCard(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- mock service impl ------------------------------------------------

    private static class FakeA2aService extends A2aServiceGrpc.A2aServiceImplBase {
        @Override
        public void getCard(AgentName request, StreamObserver<Card> responseObserver) {
            responseObserver.onNext(Card.newBuilder()
                .setName(request.getName())
                .setDescription("desc-" + request.getName())
                .setVersion("1.0.0")
                .addSkills("code").addSkills("review")
                .build());
            responseObserver.onCompleted();
        }

        @Override
        public void submit(SubmitRequest request, StreamObserver<Task> responseObserver) {
            responseObserver.onNext(Task.newBuilder()
                .setId("task-1")
                .setStatus("COMPLETED")
                .setResultJson("{\"output\":\"ok\"}")
                .build());
            responseObserver.onCompleted();
        }

        @Override
        public void getTask(TaskId request, StreamObserver<Task> responseObserver) {
            responseObserver.onNext(Task.newBuilder()
                .setId(request.getId())
                .setStatus("COMPLETED")
                .setResultJson("{}")
                .build());
            responseObserver.onCompleted();
        }

        @Override
        public void cancel(TaskId request, StreamObserver<CancelAck> responseObserver) {
            responseObserver.onNext(CancelAck.newBuilder().setAccepted(true).build());
            responseObserver.onCompleted();
        }

        @Override
        public void subscribe(TaskId request, StreamObserver<ai.lingshu.a2a.v1.TaskEvent> responseObserver) {
            responseObserver.onNext(ai.lingshu.a2a.v1.TaskEvent.newBuilder()
                .setTaskId(request.getId())
                .setEventType("PROGRESS")
                .setPayloadJson("{\"step\":1}")
                .build());
            responseObserver.onNext(ai.lingshu.a2a.v1.TaskEvent.newBuilder()
                .setTaskId(request.getId())
                .setEventType("LOG")
                .setPayloadJson("{\"msg\":\"working\"}")
                .build());
            responseObserver.onCompleted();
        }
    }
}
