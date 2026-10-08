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

/**
 * MCP connection lifecycle state machine (Story #021a, dsh §6.5 (2.1)).
 *
 * <p><b>What</b> — Six discrete states that {@link McpServerConnection}
 * transitions through as the subprocess comes up, runs, dies, and recovers.
 *
 * <p><b>State diagram</b>:
 * <pre>
 *   IDLE → CONNECTING → CONNECTED ⇄ DISCONNECTED → RECONNECTING → CONNECTED (cycle)
 *                                                  ↘ FAILED ← close()
 * </pre>
 *
 * <p><b>Semantics</b>:
 * <ul>
 *   <li>{@link #IDLE} — fresh instance, {@code start()} not yet called</li>
 *   <li>{@link #CONNECTING} — {@code start()} in progress (subprocess spawn /
 *       MCP initialize handshake)</li>
 *   <li>{@link #CONNECTED} — subprocess alive, initialize OK, tools/list OK,
 *       heartbeat probe succeeding</li>
 *   <li>{@link #DISCONNECTED} — heartbeat probe failed or subprocess died;
 *       about to schedule reconnect</li>
 *   <li>{@link #RECONNECTING} — exponential backoff sleep; next attempt pending</li>
 *   <li>{@link #FAILED} — terminal state, set by {@code close()}; no further
 *       transitions allowed</li>
 * </ul>
 *
 * <p><b>JDK 8 compatibility</b> — Plain {@code enum} with six ordered literals.
 */
public enum ConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    RECONNECTING,
    FAILED
}