/**
 * Default {@link ai.lingshu.core.slot.MemorySource} implementations (Story #002).
 *
 * <p>Each class in this package implements one MemorySource variant — a pluggable
 * producer of the {@code [PROJECT MEMORY]} segment of the assembled system prompt.
 * All four defaults ship here:
 * <ul>
 *   <li>{@link ai.lingshu.core.impl.memory.ProjectClaudeMdSource} — reads
 *       {@code cfg.memory.claudeMd.project} (default {@code ./CLAUDE.md})</li>
 *   <li>{@link ai.lingshu.core.impl.memory.UserClaudeMdSource} — reads
 *       {@code cfg.memory.claudeMd.user} (default {@code ~/.lingshu/CLAUDE.md})</li>
 *   <li>{@link ai.lingshu.core.impl.memory.IdentityMemorySource} — emits a JSON
 *       snapshot of {@code cfg.identity} as a parallel pathway to the structural
 *       {@code [ROLE]} segment</li>
 *   <li>{@link ai.lingshu.core.impl.memory.ProjectTreeMemorySource} — depth-1
 *       recursive walk of {@code *.md} files in {@code cfg.sandbox.workingDirectory}</li>
 * </ul>
 *
 * <p>All sources share the contract that {@link ai.lingshu.core.slot.MemorySource#load}
 * returns {@code null} on any recoverable failure (missing file, IOException, disabled
 * flag) so that {@code DefaultPromptBuilder} can silently omit the segment rather than
 * fail the turn.
 *
 * <p>Registration is automatic via {@code @Component}; Spring scans
 * {@code ai.lingshu.core.impl.memory.*} and feeds all four Providers into the
 * {@code MemorySourceRouter} (see {@code Routers.MemorySourceRouter}).
 */
package ai.lingshu.core.impl.memory;
