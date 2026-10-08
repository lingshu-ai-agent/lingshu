/*
 * Copyright 2026 The LingShu Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
    /**
     * Sub-agent role this session was forked for (e.g. {@code "explore"} / {@code "engineer"} /
     * {@code "reviewer"} from {@link ai.lingshu.core.agent.SubAgentType#configKey()}).
     * {@code null} for top-level / main-agent sessions. Immutable after construction —
     * stamped once in {@link #fork(String)} and never mutated thereafter.
     */
    private final String subagentType;

    public DefaultSession() {
        this(UUID.randomUUID().toString(), new ArrayList<>(), null);
    }

    public DefaultSession(String id) {
        this(id, new ArrayList<>(), null);
    }

    public DefaultSession(String id, List<Message> history) {
        this(id, history, null);
    }

    /**
     * Full-control constructor used by {@link #fork(String)} to stamp a sub-agent role on
     * the child session. {@code subagentType} is the {@code SubAgentType.configKey()}
     * value (e.g. {@code "explore"}); pass {@code null} for a top-level session.
     */
    public DefaultSession(String id, List<Message> history, String subagentType) {
        this.id = id;
        this.history = new ArrayList<>(history);
        this.subagentType = subagentType;
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
            return new DefaultSession(id + ":" + subagentType, history, subagentType);
        }
    }

    @Override
    public Checkpoint checkpoint() {
        Map<String, String> meta = new HashMap<>();
        // Stamp the sub-agent role on forked sessions only. Top-level sessions (and any
        // session whose subagentType is unset) produce a clean metadata map with no
        // dead/empty "subagent" key — replaces the previous `meta.put("subagent", "")`
        // placeholder which wrote the same empty value on every checkpoint regardless
        // of whether the session was actually a sub-agent.
        if (subagentType != null) {
            meta.put("subagent", subagentType);
        }
        return new Checkpoint(id, history(), meta, Instant.now());
    }

    /**
     * 🆕 Story #018 — Atomically replace history under the same lock that guards
     * {@link #append(Message)} and {@link #fork(String)} so a concurrent tool-result
     * append cannot interleave with a compactor swap.
     *
     * <p>Caller-owned {@code newHistory} is defensively copied; the original list
     * is not retained.
     */
    @Override
    public void compact(List<Message> newHistory) {
        if (newHistory == null) {
            throw new IllegalArgumentException("newHistory must not be null");
        }
        synchronized (history) {
            history.clear();
            history.addAll(new ArrayList<>(newHistory));
        }
    }
}