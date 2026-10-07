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
package ai.lingshu.core.spi;

import java.util.Map;

/**
 * 🆕 v0.1.0 — Structured audit log SPI (dsh §4.10 + §16 + D4 release decision).
 *
 * <p>Captures each Agent runtime event (tool dispatch, permission decision, LLM call,
 * checkpoint, error) for downstream consumption by SIEM / observability stacks.
 *
 * <p><b>Why this is an SPI</b> — different deployments need different sinks:
 * <ul>
 *   <li>Local dev: console (JSON lines)</li>
 *   <li>Staging: structured file (rolling JSON)</li>
 *   <li>Production: OpenTelemetry export, Kafka, or cloud vendor (CloudWatch / Stackdriver)</li>
 * </ul>
 *
 * <p>The {@code AgentFactory} resolves a single {@code AuditLogger} bean from the Spring
 * context at startup; if multiple beans exist, the one marked with the highest
 * {@link ai.lingshu.core.spi.SlotProvider#priority() priority} wins (no-op default if none).
 *
 * <p><b>v0.1.0 stub</b> — this interface defines the contract only. The production
 * implementations (NoOpAuditLogger default + ConsoleAuditLogger for local dev) will land
 * in a follow-up Story. The interface is published in v0.1.0 so plugin authors have a
 * stable target to implement against and downstream teams can write their own sink
 * without waiting for the Story to close.
 *
 * <p><b>Hot-path discipline</b> — implementations must NOT throw, must NOT block on I/O,
 * and should be safe to invoke from any thread including the ReAct main thread. Use
 * an internal bounded queue + background flusher if the sink is slow (mirror
 * {@link ai.lingshu.core.spi.ContractVersionRef} hygiene).
 *
 * @since 0.1.0
 */
@PublicApi(PublicApi.Level.STABLE)
public interface AuditLogger {

    /** 🆕 v0.1.0 — Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Stable identifier used for configuration ({@code agent.audit.logger: <name>}).
     * Must be unique among all registered providers.
     */
    String name();

    /**
     * Emit a structured audit event. The {@code fields} map is treated as immutable by
     * the implementation; consumers must not mutate it after the call returns.
     *
     * <p>Conventional keys (not enforced — implementations may pass through anything):
     * <ul>
     *   <li>{@code session.id}    — agent session id</li>
     *   <li>{@code turn.id}       — current turn id</li>
     *   <li>{@code tool.name}     — tool invoked (Tool / Skill / A2A)</li>
     *   <li>{@code decision}      — permission decision (allow / deny / ask-user)</li>
     *   <li>{@code llm.model}     — model name</li>
     *   <li>{@code llm.tokens.in} — input token count</li>
     *   <li>{@code llm.tokens.out} — output token count</li>
     *   <li>{@code duration.ms}   — measured duration</li>
     * </ul>
     *
     * @param eventType short dot-separated category (e.g. {@code "tool.dispatched"},
     *                  {@code "permission.denied"}, {@code "llm.completed"})
     * @param fields    structured payload; never null, may be empty
     */
    void emit(String eventType, Map<String, Object> fields);
}
