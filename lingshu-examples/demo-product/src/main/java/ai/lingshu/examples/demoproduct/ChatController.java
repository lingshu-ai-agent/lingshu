package ai.lingshu.examples.demoproduct;

import ai.lingshu.core.event.AgentEvent;
import ai.lingshu.core.impl.config.AgentConfigDefaults;
import ai.lingshu.core.impl.runtime.AgentFactory;
import ai.lingshu.core.reload.YamlWatcher;
import ai.lingshu.core.runtime.Agent;
import ai.lingshu.core.runtime.AgentConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP API for the demo-product chat.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/sessions} → create session, returns {@code {sessionId}}</li>
 *   <li>{@code POST /api/chat/{sessionId}} (Accept: text/event-stream) → SSE stream of {@link AgentEvent}s</li>
 *   <li>{@code GET /api/sessions/{sessionId}/history} → JSON array of session messages</li>
 *   <li>{@code GET /api/sessions} → list of live sessions with age (ms)</li>
 *   <li>{@code DELETE /api/sessions/{sessionId}} → evict session</li>
 * </ul>
 *
 * <p><b>Reactive subscribe (Story #025 milestone):</b> this controller is the
 * first caller in the codebase to subscribe to {@link Agent#run(String)}'s
 * {@code Publisher<AgentEvent>}. All other demos use {@code runBlocking}.
 *
 * <p><b>Scanner exclusion:</b> excludeFilters {@link YamlWatcher} for the same
 * reason as {@code demo-empty}: this demo keeps the embedded Tomcat running,
 * and the file-watcher daemon would conflict with long-lived process semantics.
 */
@RestController
@ComponentScan(
    basePackages = {"ai.lingshu.examples.demoproduct", "ai.lingshu.core"},
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = {YamlWatcher.class}))
public class ChatController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);

    private final AgentFactory agentFactory;
    private final SessionRegistry sessions;
    private final AgentEventMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    public ChatController(AgentFactory agentFactory,
                          SessionRegistry sessions,
                          AgentEventMapper mapper) {
        this.agentFactory = agentFactory;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    @PostMapping("/api/sessions")
    public Map<String, String> createSession() {
        String id = sessions.create(() -> {
            AgentConfig cfg = AgentConfigDefaults.defaults();
            return agentFactory.create(cfg);
        });
        return Map.of("sessionId", id);
    }

    @GetMapping("/api/sessions")
    public Map<String, Long> listSessions() {
        return sessions.snapshot();
    }

    @GetMapping("/api/sessions/{sessionId}/history")
    public Object getHistory(@PathVariable String sessionId) {
        Agent agent = sessions.touch(sessionId);
        if (agent == null) {
            throw new NotFoundException("session not found: " + sessionId);
        }
        return agent.session().history();
    }

    @DeleteMapping("/api/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        // R-4: broadcastCancel gives any in-flight turn a chance to clean up
        // before we drop the Agent reference.
        agentFactory.broadcastCancel();
        sessions.evict(sessionId);
        return Map.of("status", "evicted");
    }

    /**
     * SSE chat endpoint — the centerpiece of the demo.
     *
     * <p>Subscribes to {@code Agent.run(prompt)}'s {@code Publisher<AgentEvent>},
     * emits each event as an SSE message via {@link AgentEventMapper}, then closes.
     *
     * <p>R-2 back-pressure: we call {@code subscription.request(Long.MAX_VALUE)}
     * upfront so the engine drains events as fast as SSE can ship them; slow
     * client = backed-up TCP buffer, not engine stall.
     */
    @PostMapping(value = "/api/chat/{sessionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@PathVariable String sessionId, @RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        if (prompt == null || prompt.isEmpty()) {
            throw new BadRequestException("missing 'prompt' field");
        }
        Agent agent = sessions.touch(sessionId);
        if (agent == null) {
            throw new NotFoundException("session not found: " + sessionId);
        }

        // Reset history baseline so the next Compacted event gets a clean delta.
        mapper.resetBaseline(agent.session().history().size());

        SseEmitter emitter = new SseEmitter(-1L); // no timeout
        ObjectNode payload = json.createObjectNode();

        agent.run(prompt).subscribe(new Subscriber<AgentEvent>() {
            @Override
            public void onSubscribe(Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AgentEvent event) {
                try {
                    payload.removeAll();
                    mapper.toJson(event, payload);
                    emitter.send(SseEmitter.event()
                        .name(eventType(payload))
                        .data(payload.toString()));
                } catch (IOException e) {
                    LOG.warn("SSE send failed for session={}: {}", sessionId, e.toString());
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onError(Throwable t) {
                LOG.warn("turn error for session={}: {}", sessionId, t.toString());
                emitter.completeWithError(t);
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }
        });

        return emitter;
    }

    /** Extract event-name string from the JSON payload for the SSE {@code event:} field. */
    private static String eventType(ObjectNode payload) {
        JsonNode t = payload.get("type");
        return t == null ? "unknown" : t.asText();
    }

    // ── Tiny exception classes (avoid bringing in @ControllerAdvice for 2 endpoints) ─

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NOT_FOUND)
    public static class NotFoundException extends RuntimeException {
        NotFoundException(String msg) { super(msg); }
    }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public static class BadRequestException extends RuntimeException {
        BadRequestException(String msg) { super(msg); }
    }
}