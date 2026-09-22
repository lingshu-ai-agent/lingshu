package ai.lingshu.cli;

import lombok.Value;

import java.nio.file.Path;

/**
 * Story #017 — Parsed CLI arguments container. Immutable Lombok {@code @Value} so
 * {@link ArgsParser} builds it once and {@link CliRunner} reads only.
 *
 * <p>Per-subcommand relevant fields (others may be {@code null}/{@code false}):
 * <ul>
 *   <li>{@code RUN}:      {@link #prompt} (required)</li>
 *   <li>{@code RESUME}:   {@link #sessionId} + {@link #prompt} (required)</li>
 *   <li>{@code SERVE}:    {@link #port} (optional, default 8080)</li>
 *   <li>{@code DOCTOR}:   {@link #printSchema} (optional)</li>
 *   <li>{@code CONFIG}:   {@link #printEffective} / {@link #printSchema} (optional)</li>
 * </ul>
 * All subcommands accept {@link #configPath} (default {@code application.yml}).
 */
@Value
public class Args {
    Subcommand subcommand;
    Path configPath;
    String prompt;
    String sessionId;
    Integer port;
    boolean printEffective;
    boolean printSchema;
}