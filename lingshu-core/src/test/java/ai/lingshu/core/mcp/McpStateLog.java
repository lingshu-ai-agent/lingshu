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
package ai.lingshu.core.mcp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Records a {@link McpServerConnection}'s state notifications, and separates
 * <em>real transitions</em> from <em>repeat announcements</em>.
 *
 * <p><b>Why this exists</b> — {@code SseMcpServerConnection.relistTools()}
 * re-announces {@code CONNECTED} to the listeners after a
 * {@code notifications/tools/list_changed} push, <em>without</em> the state
 * having changed (the state was already {@code CONNECTED}). A test that
 * counts {@code CONNECTED} notifications would therefore count one
 * "connection" per server push — and could report a reconnect that never
 * happened. A real transition is always preceded by a <em>different</em>
 * state, which is exactly what {@link #transitions()} keeps.
 *
 * <p><b>Second filter — a stale CONNECTED</b> — {@code relistTools()} also
 * guards on {@code CONNECTED} and then does a <em>blocking</em> request
 * before re-announcing. If the connection drops in between, the re-announcement
 * describes a connection that is no longer there. Such a notification is
 * dropped when the transport's own state is not {@code CONNECTED}.
 *
 * <p>Story #054 T032—T036.
 */
final class McpStateLog {

    /** Every notification, repeats included. */
    private final List<ConnectionState> notifications =
        Collections.synchronizedList(new ArrayList<ConnectionState>());

    /** Genuine state transitions only — a repeat of the current state is dropped. */
    private final List<ConnectionState> transitions =
        Collections.synchronizedList(new ArrayList<ConnectionState>());

    /** {@code lastHeartbeatAt} at every genuine {@code CONNECTED} transition. */
    private final List<Instant> beatsAtConnect =
        Collections.synchronizedList(new ArrayList<Instant>());

    private final McpServerConnection conn;

    private ConnectionState previous;

    private McpStateLog(McpServerConnection conn) {
        this.conn = conn;
    }

    /** Attach a log to {@code conn} — call before {@code start()}. */
    static McpStateLog attach(McpServerConnection conn) {
        McpStateLog log = new McpStateLog(conn);
        conn.onStateChange(log::onState);
        return log;
    }

    private void onState(ConnectionState state) {
        notifications.add(state);
        if (state == previous) {
            // A repeat announcement (relistTools) — not a transition.
            return;
        }
        if (state == ConnectionState.CONNECTED
            && conn.state() != ConnectionState.CONNECTED) {
            // A <em>stale</em> re-announcement, not a new connection.
            //
            // <p>{@code relistTools()} guards on {@code state == CONNECTED}
            // when it starts, then does a <em>blocking</em> {@code tools/list}
            // POST before it re-announces CONNECTED. The connection can drop
            // in between — the reader thread transitions DISCONNECTED →
            // RECONNECTING while the relist task is still waiting for its
            // answer. The relist then announces CONNECTED for a connection
            // that is no longer there.
            //
            // <p>Without this filter the raw stream contains a CONNECTED
            // entry that no connection ever produced, and counting CONNECTED
            // entries would report a reconnect that never happened. The
            // transport's own {@code state()} is the truth here: if it is not
            // CONNECTED, there is no live connection to count.
            return;
        }
        transitions.add(state);
        if (state == ConnectionState.CONNECTED) {
            beatsAtConnect.add(conn.lastHeartbeatAt());
        }
        previous = state;
    }

    /** All notifications, in order. */
    List<ConnectionState> notifications() {
        synchronized (notifications) {
            return new ArrayList<>(notifications);
        }
    }

    /** Genuine transitions, in order. */
    List<ConnectionState> transitions() {
        synchronized (transitions) {
            return new ArrayList<>(transitions);
        }
    }

    /** How many times the connection actually became {@code CONNECTED}. */
    int connectCount() {
        int n = 0;
        for (ConnectionState s : transitions()) {
            if (s == ConnectionState.CONNECTED) {
                n++;
            }
        }
        return n;
    }

    /** {@code lastHeartbeatAt} captured at each genuine {@code CONNECTED}. */
    List<Instant> beatsAtConnect() {
        synchronized (beatsAtConnect) {
            return new ArrayList<>(beatsAtConnect);
        }
    }
}
