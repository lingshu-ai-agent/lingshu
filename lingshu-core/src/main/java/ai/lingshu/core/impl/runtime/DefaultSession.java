package ai.lingshu.core.impl.runtime;

import ai.lingshu.core.message.Checkpoint;
import ai.lingshu.core.message.Message;
import ai.lingshu.core.runtime.Session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal in-memory {@link Session} implementation (Story #001 default).
 *
 * <p>Story #014 replaces this with a {@code SessionStore}-backed implementation that can
 * survive process restarts. For now, all history lives in a {@code CopyOnWriteArrayList}
 * inside the JVM.
 *
 * <p>Thread-safety:
 * <ul>
 *   <li>{@link #append(Message)} — synchronized, atomic append</li>
 *   <li>{@link #history()} — defensive immutable snapshot</li>
 *   <li>{@link #fork(String)} — copy-on-write of current history</li>
 * </ul>
 */
public class DefaultSession implements Session {

    private final String id;
    private final List<Message> history;

    public DefaultSession() {
        this(UUID.randomUUID().toString());
    }

    public DefaultSession(String id) {
        this.id = id;
        this.history = new ArrayList<>();
    }

    public DefaultSession(String id, List<Message> history) {
        this.id = id;
        this.history = new ArrayList<>(history);
    }

    @Override public String id() { return id; }

    @Override
    public List<Message> history() {
        synchronized (history) {
            return Collections.unmodifiableList(new ArrayList<>(history));
        }
    }

    /** Append a message to history; synchronized to prevent race with compactor / concurrent tools. */
    public void append(Message m) {
        synchronized (history) {
            history.add(m);
        }
    }

    @Override
    public Session fork(String subagentType) {
        synchronized (history) {
            DefaultSession copy = new DefaultSession(id + ":" + subagentType, history);
            return copy;
        }
    }

    @Override
    public Checkpoint checkpoint() {
        Map<String, String> meta = new HashMap<>();
        meta.put("subagent", "");
        return new Checkpoint(id, history(), meta, Instant.now());
    }
}