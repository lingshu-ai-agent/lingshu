/**
 * Story #007 — YAML hot-reload without interrupting in-flight turns.
 *
 * <p>Three collaborating types form the AC-06 contract:
 * <ul>
 *   <li>{@link AgentConfigRegistry} — single source of truth for the current
 *       {@link ai.lingshu.core.runtime.AgentConfig}; single-writer / multi-reader
 *       lock-free {@link java.util.concurrent.atomic.AtomicReference}.</li>
 *   <li>{@link ConfigChangeListener} — synchronous SPI for components that need
 *       to react to a config swap (audit / metric / cache invalidate).</li>
 *   <li>{@link YamlWatcher} — daemon scheduler polling the application yml
 *       {@link java.nio.file.attribute.FileTime} every 5 s; on change, reloads +
 *       validates + publishes via the registry.</li>
 * </ul>
 *
 * <h3>Freeze semantics (AC-06 R-03 mitigation)</h3>
 * <p>{@link ai.lingshu.core.impl.runtime.DefaultAgent} reads
 * {@link AgentConfigRegistry#current()} ONCE at turn entry and uses the local
 * variable thereafter. Java reference semantics + {@code @Value} immutable
 * configuration guarantee freeze without any explicit snapshot copy.
 *
 * <h3>SpecKit traceability</h3>
 * <ul>
 *   <li>spec.md §FR-001 — registry single source of truth</li>
 *   <li>spec.md §FR-002 — listener SPI + exception isolation</li>
 *   <li>spec.md §FR-003 — watcher 5 s mtime poll</li>
 *   <li>spec.md §FR-004 — watcher rollback on parse / validation failure</li>
 *   <li>spec.md §FR-005 — {@code AgentFactory.create(cfg, registry)} overload</li>
 *   <li>spec.md §FR-006 — {@code DefaultAgent.run()} entry-time freeze</li>
 *   <li>spec.md §FR-007 — invalid yml / IOException / validation failure keep old</li>
 *   <li>spec.md §FR-008 — daemon watcher thread + Spring lifecycle</li>
 *   <li>spec.md §FR-009 — no new transitive deps (R-13 mitigation d)</li>
 * </ul>
 *
 * @see ai.lingshu.core.reload.AgentConfigRegistry
 * @see ai.lingshu.core.reload.ConfigChangeListener
 * @see ai.lingshu.core.reload.YamlWatcher
 */
package ai.lingshu.core.reload;