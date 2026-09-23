package ai.lingshu.core.slot;

import ai.lingshu.core.spi.ContractVersionRef;

import java.io.IOException;
import java.util.List;

/**
 * Slot 4 sub-SPI — a single Skill source (dsh §6.4 L4066-4097).
 *
 * <p>A {@code SkillSource} discovers {@link Skill Skills} from one location
 * (classpath prefix, filesystem directory, git repo, S3 mount, etc.). v1 ships
 * two implementations:
 * <ul>
 *   <li>{@code ClasspathSkillSource} — scans {@code classpath*:prefix/&#42;/SKILL.md}</li>
 *   <li>{@code DirectorySkillSource} — scans {@code &lt;dir&gt;/&#42;/SKILL.md}</li>
 * </ul>
 *
 * <p>Future sources ({@code git}, {@code s3}, {@code http}) are added by writing
 * a new {@code SkillSource} + {@link SkillSourceProvider} pair — no core changes
 * required. {@code SkillSourceRouter} auto-discovers all {@code SkillSourceProvider}
 * beans at startup.
 *
 * <p><b>🆕 Story #020b — Skill source SPI:</b> the contract that backs
 * {@code CompositeSkillLoader.discover()}. {@link #discover()} is called once at
 * startup by {@code SkillAutoConfiguration.afterPropertiesSet()}. Implementations
 * are NOT required to be thread-safe — the call site runs single-threaded.
 *
 * <p><b>Error semantics:</b> {@link #discover()} may throw {@link IOException}
 * for filesystem / classpath read errors. {@code CompositeSkillLoader} catches
 * and logs the failure (per source) — one bad source does not block others
 * (dsh §10 R-09 mitigation philosophy).
 *
 * <p><b>Hot-reload hook:</b> {@link #watchable()} returns {@code true} if the
 * source supports mtime watching (§14.8 future Story). v1 returns {@code false}
 * for classpath (jar-immutable) and {@code true} for directory (local mutable).
 */
public interface SkillSource {

    /** Contract version (semver MAJOR.MINOR.PATCH). */
    @ContractVersionRef
    String CONTRACT_VERSION = "1.0.0";

    /**
     * Source type key — used by {@code SkillSourceRouter} for {@code yml → Provider}
     * routing. Must be unique across all {@link SkillSourceProvider Providers}.
     * Examples: {@code "classpath"}, {@code "directory"}, {@code "git"}, {@code "s3"}.
     */
    String type();

    /**
     * Original location string as configured (e.g. {@code "classpath:skills/foo/"},
     * {@code "/abs/or/rel/path"}). Returned verbatim for debug logging.
     */
    String location();

    /**
     * Discover all Skills from this source.
     *
     * <p>v1 implementations:
     * <ul>
     *   <li>ClasspathSkillSource: scan {@code classpath*:prefix/&#42;/SKILL.md},
     *       parse each file as Markdown via {@code SkillTool.fromMarkdown}.</li>
     *   <li>DirectorySkillSource: scan {@code &lt;dir&gt;/&#42;/SKILL.md},
     *       parse each file as Markdown via {@code SkillTool.fromMarkdown}.</li>
     * </ul>
     *
     * @return List of Skills (possibly empty), in directory-scan order. Stable order
     *         is preferred for prompt cache hits downstream (cf. #009d design philosophy).
     * @throws IOException if the underlying storage cannot be read (e.g. classpath
     *         resource not found, directory I/O error). {@code CompositeSkillLoader}
     *         catches this per source and continues.
     */
    List<Skill> discover() throws IOException;

    /**
     * Whether this source supports mtime watching — for future §14.8 hot-reload.
     *
     * <p>v1 defaults:
     * <ul>
     *   <li>ClasspathSkillSource → {@code false} (jar contents immutable at runtime)</li>
     *   <li>DirectorySkillSource → {@code true} (local files mutable)</li>
     * </ul>
     */
    default boolean watchable() { return false; }
}
