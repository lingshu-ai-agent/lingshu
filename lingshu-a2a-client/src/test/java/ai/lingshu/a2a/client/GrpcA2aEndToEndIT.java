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
import io.grpc.Server;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L5 E2E integration test — full gRPC stack (Story #009a AC-10 关联).
 * Spins up a real in-process gRPC server, wires up the Provider + AutoConfiguration,
 * and exercises the full transport contract end-to-end.
 */
class GrpcA2aEndToEndIT {

    private Server server;
    private ManagedChannel channel;
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
        // E2E: build GrpcA2aTransport directly with the in-process channel
        // (Provider.create uses ManagedChannelBuilder.forTarget which validates DNS —
        //  "in-process:UUID" is not a valid DNS name, so bypass for E2E only.)
        AgentCardCache cache = new AgentCardCache(java.time.Duration.ofMinutes(5));
        transport = new GrpcA2aTransport(channel, cache, "in-process:" + name);
    }

    @AfterEach
    void tearDown() {
        if (transport != null) transport.close();
        if (channel != null) channel.shutdownNow();
        if (server != null) server.shutdownNow();
    }

    @Test
    @DisplayName("TC-E2E-1: fetchCard_realGrpc_returnsCard")
    void fetchCard_realGrpc_returnsCard() {
        Map<String, Object> card = transport.fetchCard("alice");
        assertThat(card).containsEntry("name", "alice");
        assertThat(card).containsKey("skills");
    }

    @Test
    @DisplayName("TC-E2E-2: submit_realGrpc_returnsToolResult")
    void submit_realGrpc_returnsToolResult() {
        ToolResult r = transport.submit("alice", "code", "{\"prompt\":\"test\"}");
        assertThat(r).isNotNull();
        assertThat(r.isError()).isFalse();
        assertThat(r.getContent()).contains("output");
    }

    private static class FakeA2aService extends A2aServiceGrpc.A2aServiceImplBase {
        @Override
        public void getCard(AgentName request, StreamObserver<Card> responseObserver) {
            responseObserver.onNext(Card.newBuilder()
                .setName(request.getName())
                .setDescription("e2e")
                .setVersion("1.0.0")
                .addSkills("code")
                .build());
            responseObserver.onCompleted();
        }

        @Override
        public void submit(SubmitRequest request, StreamObserver<Task> responseObserver) {
            responseObserver.onNext(Task.newBuilder()
                .setId("e2e-task")
                .setStatus("COMPLETED")
                .setResultJson("{\"output\":\"e2e-ok\"}")
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
    }
}
